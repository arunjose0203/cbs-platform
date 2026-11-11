#!/bin/sh
# One-command staging bring-up:
#   preflight -> offline migrations -> runtime grants -> app + edge -> readiness over HTTPS.
#
#   deploy/staging/up.sh [--env-file /etc/cbs/staging.env] [--skip-migrations]
#
# Runtime grants must be reapplied by the database owner after every migration
# (docs/production-deployment-recovery.md, step 8). Set CBS_PRIVILEGES_HOOK in the env file to an
# executable that does this through your secret store; without it the script stops after the
# migrations so nothing starts on stale grants. Nothing here prints secret values.
set -eu

env_file=/etc/cbs/staging.env
migrate=yes
while [ $# -gt 0 ]; do
    case "$1" in
        --env-file) env_file=$2; shift 2 ;;
        --skip-migrations) migrate=no; shift ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done
[ -f "$env_file" ] || { echo "Environment file not found: $env_file" >&2; exit 2; }

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"
set -a
. "$env_file"
set +a
python=${CBS_OPS_PYTHON:-python3}
compose() { docker compose --env-file "$env_file" -f deploy/staging/compose.yml "$@"; }

echo "1/5 Preflight checks"
"$python" ops/staging_preflight.py

if [ "$migrate" = yes ]; then
    echo "2/5 Stopping writers and applying migrations"
    compose stop app >/dev/null 2>&1 || true
    compose --profile tools run --rm migrate
    echo "3/5 Reapplying runtime grants"
    if [ -z "${CBS_PRIVILEGES_HOOK:-}" ]; then
        echo "Migrations applied. Reapply ops/runtime-privileges.sql as the owner, then rerun with --skip-migrations." >&2
        exit 3
    fi
    "$CBS_PRIVILEGES_HOOK"
else
    echo "2/5 Migrations skipped"
    echo "3/5 Runtime grants unchanged"
fi

echo "4/5 Starting app and edge"
compose up -d --wait app edge

echo "5/5 Checking readiness through the edge"
attempt=0
until curl --fail --silent --show-error --max-time 10 "https://${CBS_DOMAIN}/health/ready" >/dev/null; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "${CBS_READY_ATTEMPTS:-12}" ]; then
        echo "CBS did not become ready at https://${CBS_DOMAIN}/health/ready" >&2
        compose ps >&2 || true
        exit 4
    fi
    sleep "${CBS_READY_INTERVAL:-5}"
done
echo "CBS staging is ready at https://${CBS_DOMAIN}/admin/"
