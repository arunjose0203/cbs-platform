"""Run a coordinated cold backup in a reviewed maintenance window (Linux systemd)."""
import argparse
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import uuid
import recovery

def atomic(path, value):
    path=Path(path);tmp=path.with_suffix('.tmp')
    if tmp.exists():tmp.unlink()
    recovery.private_write(tmp,recovery.canonical(value));os.replace(tmp,path)

def unit(action,name):
    try:
        return subprocess.run(['systemctl',action,name],capture_output=True,timeout=150).returncode
    except (OSError, subprocess.TimeoutExpired):
        return 1

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--config',required=True);parser.add_argument('--recover',action='store_true');args=parser.parse_args()
    config=json.loads(Path(args.config).read_text());job=config['automation'];writers=job['writer_units']
    if not writers or any(not re.fullmatch(r'(?:cbs-[a-z0-9-]+|keycloak)\.service',x) for x in writers):
        raise recovery.OpsError('Specify reviewed CBS/identity writer service units')
    state=Path(job['state_file']);state.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    with open(str(state)+'.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if args.recover:
            pending=json.loads(state.read_text()).get('restart',[]) if state.exists() else []
            if not set(pending)<=set(writers):raise recovery.OpsError('Recovery state contains unknown service units')
            failed=[name for name in pending if unit('start',name)]
            atomic(state,{'restart':failed})
            return 1 if failed else 0
        recovery.validate(config)
        if state.exists() and json.loads(state.read_text()).get('restart'):
            raise recovery.OpsError('Recover the previous maintenance job before starting another')
        running=[name for name in writers if unit('is-active',name)==0]
        atomic(state,{'restart':running})
        status={'status':'FAILED','started_at':datetime.now(timezone.utc).isoformat()}
        def interrupted(*_):raise recovery.OpsError('Maintenance job interrupted')
        signal.signal(signal.SIGTERM,interrupted)
        try:
            for name in running:
                if unit('stop',name):raise recovery.OpsError('Could not stop writer service')
            directory=Path(job['backup_root'])/(datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8])
            status.update(recovery.backup(config,directory))
            # Copy this complete encrypted directory to your separately controlled immutable backup store.
            # This job does not delete backups or claim off-host durability.
        finally:
            failed=[name for name in running if unit('start',name)]
            atomic(state,{'restart':failed})
            if failed:status['status']='WRITER_RESTART_FAILED'
            status['completed_at']=datetime.now(timezone.utc).isoformat();atomic(job['status_file'],status)
        print(json.dumps(status));return 0 if status['status']=='BACKUP_COMPLETE' else 1

if __name__=='__main__':
    try:raise SystemExit(main())
    except Exception:print('{"status":"FAILED","reason":"Maintenance backup failed; check protected state and writer services"}');raise SystemExit(1)
