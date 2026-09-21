# Create a persistent development identity server without storing secrets in Git.
$ErrorActionPreference = 'Stop'
$cbsRoot = Split-Path -Parent $PSScriptRoot
$cbsVersion = '26.7.4'
$cbsKeycloak = Join-Path $cbsRoot ".tools\keycloak-$cbsVersion"
$cbsLocal = Join-Path $cbsRoot '.local'
New-Item -ItemType Directory -Path $cbsLocal -Force | Out-Null
if (-not (Test-Path (Join-Path $cbsKeycloak 'bin\kc.bat'))) {
    $cbsRelease = Invoke-RestMethod "https://api.github.com/repos/keycloak/keycloak/releases/tags/$cbsVersion"
    $cbsAsset = $cbsRelease.assets | Where-Object name -eq "keycloak-$cbsVersion.zip"
    if (-not $cbsAsset -or -not $cbsAsset.digest.StartsWith('sha256:')) { throw 'Verified Keycloak release asset not found.' }
    $cbsArchive = Join-Path $cbsRoot ".tools\keycloak-$cbsVersion.zip"
    New-Item -ItemType Directory -Path (Split-Path $cbsArchive) -Force | Out-Null
    Invoke-WebRequest $cbsAsset.browser_download_url -OutFile $cbsArchive -UseBasicParsing
    if (('sha256:' + (Get-FileHash $cbsArchive -Algorithm SHA256).Hash.ToLowerInvariant()) -ne $cbsAsset.digest) {
        throw 'Keycloak archive checksum mismatch.'
    }
    Expand-Archive -LiteralPath $cbsArchive -DestinationPath (Split-Path $cbsKeycloak) -Force
}
$cbsCredentialsFile = Join-Path $cbsLocal 'identity-credentials.json'
if (-not (Test-Path $cbsCredentialsFile)) {
    $cbsCredentials = [ordered]@{
        bootstrapUsername = 'keycloak-admin'
        bootstrapPassword = ('Kc9-' + [guid]::NewGuid().ToString('N'))
        administratorId = [guid]::NewGuid().ToString()
        administratorUsername = 'cbs-admin'
        temporaryPassword = ('Cbs9-' + [guid]::NewGuid().ToString('N'))
    }
    $cbsCredentials | ConvertTo-Json | Set-Content -LiteralPath $cbsCredentialsFile -Encoding UTF8
}
$cbsCredentials = Get-Content $cbsCredentialsFile -Raw | ConvertFrom-Json
$cbsFirstLogin = @"
CBS DEVELOPMENT - FIRST LOGIN

Portal: http://127.0.0.1:8080/admin/index.html
Username: $($cbsCredentials.administratorUsername)
Temporary password: $($cbsCredentials.temporaryPassword)

Click Sign in in CBS. Choose your own password and enroll your authenticator
app using the QR code and a current code. If CBS asks for MFA again immediately
after enrollment, sign in once more with your new password and authenticator code.

This temporary password stops working after you change it. Re-running setup
does not reset an existing password or authenticator. This file is local and
ignored by Git. Do not share it.
"@
$cbsFirstLogin | Set-Content (Join-Path $cbsLocal 'FIRST-LOGIN.txt') -Encoding UTF8
$cbsRealm = Get-Content (Join-Path $cbsRoot 'config\keycloak\cbs-dev-realm.json') -Raw | ConvertFrom-Json
$cbsUser = @{
    id = $cbsCredentials.administratorId; username = $cbsCredentials.administratorUsername
    firstName = 'CBS'; lastName = 'Administrator'; email = 'cbs-admin@localhost.invalid'
    enabled = $true; emailVerified = $false; realmRoles = @('PLATFORM_ADMIN')
    requiredActions = @('UPDATE_PASSWORD', 'CONFIGURE_TOTP')
    credentials = @(@{ type = 'password'; value = $cbsCredentials.temporaryPassword; temporary = $true })
}
$cbsRealm | Add-Member -NotePropertyName users -NotePropertyValue @($cbsUser)
$cbsImport = Join-Path $cbsKeycloak 'data\import'
New-Item -ItemType Directory -Path $cbsImport -Force | Out-Null
$cbsRealm | ConvertTo-Json -Depth 30 | Set-Content (Join-Path $cbsImport 'cbs-dev-realm.json') -Encoding UTF8
@{
    issuer = 'http://127.0.0.1:8180/realms/cbs-dev'
    jwkSetUri = 'http://127.0.0.1:8180/realms/cbs-dev/protocol/openid-connect/certs'
    audience = 'cbs-api'; clientId = 'cbs-administration'
    redirectUri = 'http://127.0.0.1:8080/admin/index.html'
    bootstrapAdminSubject = $cbsCredentials.administratorId
} | ConvertTo-Json | Set-Content (Join-Path $cbsLocal 'development-identity.json') -Encoding UTF8
Write-Output 'Development identity setup prepared. Existing realms and passwords are preserved on restart.'
Write-Output 'Start scripts/run-local-identity.ps1, then scripts/run.ps1.'
Write-Output 'Initial credentials: .local/identity-credentials.json (ignored by Git).'
