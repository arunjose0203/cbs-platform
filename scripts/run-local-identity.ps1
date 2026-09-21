$ErrorActionPreference = 'Stop'
$cbsRoot = Split-Path -Parent $PSScriptRoot
$cbsKeycloak = Join-Path $cbsRoot '.tools\keycloak-26.7.4'
$cbsCredentials = Get-Content (Join-Path $cbsRoot '.local\identity-credentials.json') -Raw | ConvertFrom-Json
$cbsJdk = Get-ChildItem (Join-Path $cbsRoot '.tools') -Directory -Filter 'jdk-21*' | Select-Object -First 1
if ($cbsJdk) { $env:JAVA_HOME = $cbsJdk.FullName }
$env:KC_BOOTSTRAP_ADMIN_USERNAME = $cbsCredentials.bootstrapUsername
$env:KC_BOOTSTRAP_ADMIN_PASSWORD = $cbsCredentials.bootstrapPassword
$env:JAVA_OPTS_APPEND = '-Djdk.net.unixdomain.tmpdir="' + (Join-Path $cbsRoot '.local') + '"'
& (Join-Path $cbsKeycloak 'bin\kc.bat') start-dev --http-host=127.0.0.1 --http-port=8180 --hostname=http://127.0.0.1:8180 --import-realm
exit $LASTEXITCODE
