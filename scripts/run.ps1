# Run the packaged application with a workspace socket directory for Windows JDKs.
param([switch]$UseEnvironmentDatabase, [switch]$UseEnvironmentIdentity)

$ErrorActionPreference = 'Stop'
$cbsRoot = Split-Path -Parent $PSScriptRoot
$cbsIdentityConfig = Join-Path $cbsRoot '.local\development-identity.json'
if (-not $UseEnvironmentIdentity -and (Test-Path -LiteralPath $cbsIdentityConfig)) {
    $cbsIdentity = Get-Content $cbsIdentityConfig -Raw | ConvertFrom-Json
    foreach ($cbsSetting in @('issuer', 'jwkSetUri', 'audience', 'clientId', 'redirectUri', 'bootstrapAdminSubject')) {
        if ([string]::IsNullOrWhiteSpace($cbsIdentity.$cbsSetting)) { throw "Local identity configuration is missing $cbsSetting." }
    }
    $env:CBS_SECURITY_ENABLED = 'true'
    $env:CBS_ALLOW_LOCAL_IDENTITY_HTTP = 'true'
    $env:CBS_JWT_ISSUER = $cbsIdentity.issuer
    $env:CBS_JWT_JWKS_URI = $cbsIdentity.jwkSetUri
    $env:CBS_JWT_AUDIENCE = $cbsIdentity.audience
    $env:CBS_PORTAL_CLIENT_ID = $cbsIdentity.clientId
    $env:CBS_PORTAL_REDIRECT_URI = $cbsIdentity.redirectUri
    $env:CBS_BOOTSTRAP_ADMIN_SUBJECT = $cbsIdentity.bootstrapAdminSubject
    $cbsMfaExceptionFile = Join-Path $cbsRoot '.local\development-mfa-exception.json'
    $env:CBS_LOCAL_MFA_BYPASS_UNTIL = ''
    if (Test-Path -LiteralPath $cbsMfaExceptionFile) {
        $cbsMfaException = Get-Content $cbsMfaExceptionFile -Raw | ConvertFrom-Json
        $env:CBS_LOCAL_MFA_BYPASS_UNTIL = ([DateTimeOffset]$cbsMfaException.until).ToUniversalTime().ToString('o')
        Write-Output "Temporary development MFA exception expires at $env:CBS_LOCAL_MFA_BYPASS_UNTIL."
    }
    Write-Output 'Using the saved local development identity configuration.'
}
$cbsDatabaseConfig = Join-Path $cbsRoot '.local\development-database.json'
if (-not $UseEnvironmentDatabase -and (Test-Path -LiteralPath $cbsDatabaseConfig)) {
    $cbsDatabase = Get-Content -LiteralPath $cbsDatabaseConfig -Raw | ConvertFrom-Json
    foreach ($cbsSetting in @('url', 'migrationUrl', 'username', 'password')) {
        if ([string]::IsNullOrWhiteSpace($cbsDatabase.$cbsSetting)) {
            throw "Local database configuration is missing $cbsSetting."
        }
    }
    $env:CBS_DB_URL = $cbsDatabase.url
    $env:CBS_DB_USER = $cbsDatabase.username
    $env:CBS_DB_PASSWORD = $cbsDatabase.password
    # Migrate the same database over a direct connection; runtime can use a pooler.
    $env:SPRING_FLYWAY_URL = $cbsDatabase.migrationUrl
    $env:SPRING_FLYWAY_USER = $cbsDatabase.username
    $env:SPRING_FLYWAY_PASSWORD = $cbsDatabase.password
    Write-Output 'Using the saved local development database configuration.'
}
$cbsJar = Join-Path $cbsRoot 'target\cbs-platform-0.1.0-SNAPSHOT.jar'
$cbsBankConfig = Join-Path $cbsRoot '.local\bank-databases.yml'
if (-not $UseEnvironmentDatabase -and (Test-Path -LiteralPath $cbsBankConfig)) {
    $cbsBankLocation = ([Uri]$cbsBankConfig).AbsoluteUri
    $cbsLocations = @($env:SPRING_CONFIG_ADDITIONAL_LOCATION -split ',' | Where-Object { $_ })
    if ($cbsLocations -notcontains $cbsBankLocation) { $cbsLocations += $cbsBankLocation }
    $env:SPRING_CONFIG_ADDITIONAL_LOCATION = $cbsLocations -join ','
    Write-Output 'Loading approved development bank database bindings.'
}
if (-not (Test-Path -LiteralPath $cbsJar)) { throw 'Build first: ./scripts/mvn.ps1 verify' }
if (-not $env:CBS_DB_PASSWORD) { throw 'Configure .local/development-database.json or set CBS_DB_PASSWORD before running the application.' }
$cbsJdk = Get-ChildItem -LiteralPath (Join-Path $cbsRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($cbsJdk) {
    $cbsJava = Join-Path $cbsJdk.FullName 'bin\java.exe'
} else {
    $cbsJavaCommand = Get-Command java -ErrorAction SilentlyContinue
    if (-not $cbsJavaCommand) { throw 'Java 21 is required.' }
    $cbsJava = $cbsJavaCommand.Source
}
$cbsSocketDir = Join-Path $cbsRoot '.local\sockets'
New-Item -ItemType Directory -Path $cbsSocketDir -Force | Out-Null
& $cbsJava "-Djdk.net.unixdomain.tmpdir=$cbsSocketDir" -jar $cbsJar
exit $LASTEXITCODE
