# Operator-controlled, temporary MFA exception for the local CBS development client.
param([ValidateSet('Mfa', 'PasswordOnly')][string]$Mode = 'Mfa')
$ErrorActionPreference = 'Stop'
$cbsRoot = Split-Path -Parent $PSScriptRoot
$cbsCredentials = Get-Content (Join-Path $cbsRoot '.local\identity-credentials.json') -Raw | ConvertFrom-Json
$cbsToken = Invoke-RestMethod 'http://127.0.0.1:8180/realms/master/protocol/openid-connect/token' -Method Post -Body @{
    grant_type='password'; client_id='admin-cli'
    username=$cbsCredentials.bootstrapUsername; password=$cbsCredentials.bootstrapPassword
}
$cbsHeaders = @{Authorization='Bearer ' + $cbsToken.access_token}
$cbsBase = 'http://127.0.0.1:8180/admin/realms/cbs-dev'
function Invoke-CbsIdentityChange([string]$Path, [string]$Method, $Body) {
    Invoke-RestMethod ($cbsBase + $Path) -Method $Method -Headers $cbsHeaders -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 30) | Out-Null
}
$cbsClients = @(Invoke-RestMethod ($cbsBase + '/clients?clientId=cbs-administration') -Headers $cbsHeaders | ForEach-Object { $_ })
if ($cbsClients.Count -ne 1) { throw 'Expected exactly one local CBS portal client.' }
$cbsClient = $cbsClients[0]
$cbsUser = Invoke-RestMethod ($cbsBase + '/users/' + $cbsCredentials.administratorId) -Headers $cbsHeaders
$cbsBackupFile = Join-Path $cbsRoot '.local\mfa-exception-backup.json'
$cbsExceptionFile = Join-Path $cbsRoot '.local\development-mfa-exception.json'
$cbsOverrides = @{}
if ($cbsClient.authenticationFlowBindingOverrides) {
    $cbsClient.authenticationFlowBindingOverrides.PSObject.Properties | ForEach-Object { $cbsOverrides[$_.Name] = $_.Value }
}
if ($Mode -eq 'PasswordOnly') {
    if (-not (Test-Path $cbsBackupFile)) {
        @{originalBrowserFlow=$cbsOverrides['browser'];originalRequiredActions=@($cbsUser.requiredActions)} |
            ConvertTo-Json | Set-Content $cbsBackupFile -Encoding UTF8
    }
    $cbsAlias = 'cbs-temporary-password-only'
    $cbsFlows = @(Invoke-RestMethod ($cbsBase + '/authentication/flows') -Headers $cbsHeaders | ForEach-Object { $_ })
    if (-not ($cbsFlows | Where-Object alias -eq $cbsAlias)) {
        Invoke-CbsIdentityChange '/authentication/flows' 'Post' @{
            alias=$cbsAlias;description='Temporary local CBS development login; backend enforces deadline'
            providerId='basic-flow';topLevel=$true;builtIn=$false
        }
        Invoke-CbsIdentityChange "/authentication/flows/$cbsAlias/executions/execution" 'Post' @{provider='auth-username-password-form'}
    }
    $cbsFlows = @(Invoke-RestMethod ($cbsBase + '/authentication/flows') -Headers $cbsHeaders | ForEach-Object { $_ })
    $cbsFlow = $cbsFlows | Where-Object alias -eq $cbsAlias
    $cbsExecutions = @(Invoke-RestMethod ($cbsBase + "/authentication/flows/$cbsAlias/executions") -Headers $cbsHeaders | ForEach-Object { $_ })
    if ($cbsExecutions.Count -ne 1 -or $cbsExecutions[0].providerId -ne 'auth-username-password-form') {
        throw 'The temporary flow must contain exactly one password authenticator.'
    }
    $cbsExecutions[0].requirement = 'REQUIRED'
    Invoke-CbsIdentityChange "/authentication/flows/$cbsAlias/executions" 'Put' $cbsExecutions[0]
    $cbsOverrides['browser'] = $cbsFlow.id
    Invoke-CbsIdentityChange ('/clients/' + $cbsClient.id) 'Put' @{authenticationFlowBindingOverrides=$cbsOverrides}
    # Preserve password-change and all other requirements; do not delete enrolled OTP credentials.
    $cbsActions = @($cbsUser.requiredActions | Where-Object { $_ -ne 'CONFIGURE_TOTP' })
    Invoke-CbsIdentityChange ('/users/' + $cbsUser.id) 'Put' @{requiredActions=$cbsActions}
    $cbsDeadline = [DateTimeOffset]::UtcNow.AddHours(8)
    @{until=$cbsDeadline.ToString('o');reason='User-requested temporary local development MFA exception'} |
        ConvertTo-Json | Set-Content $cbsExceptionFile -Encoding UTF8
    Write-Output "Password-only development login configured until $($cbsDeadline.LocalDateTime.ToString('yyyy-MM-dd HH:mm:ss')). Restart CBS to load the exception."
} else {
    if (-not (Test-Path $cbsBackupFile)) { throw 'The original MFA settings backup is missing.' }
    $cbsBackup = Get-Content $cbsBackupFile -Raw | ConvertFrom-Json
    if ($cbsBackup.originalBrowserFlow) { $cbsOverrides['browser']=$cbsBackup.originalBrowserFlow }
    # Keycloak removes an override only when that key is explicitly empty.
    else { $cbsOverrides['browser'] = '' }
    Invoke-CbsIdentityChange ('/clients/' + $cbsClient.id) 'Put' @{authenticationFlowBindingOverrides=$cbsOverrides}
    $cbsFactors = @(Invoke-RestMethod ($cbsBase + '/users/' + $cbsUser.id + '/credentials') -Headers $cbsHeaders | ForEach-Object { $_ })
    $cbsActions = @($cbsUser.requiredActions)
    if ($cbsBackup.originalRequiredActions -contains 'CONFIGURE_TOTP' -and -not ($cbsFactors | Where-Object type -eq 'otp')) {
        $cbsActions = @($cbsActions + 'CONFIGURE_TOTP' | Select-Object -Unique)
    }
    Invoke-CbsIdentityChange ('/users/' + $cbsUser.id) 'Put' @{requiredActions=$cbsActions}
    # An expired deadline closes the exception and is safe across future restarts.
    @{until='2000-01-01T00:00:00Z';reason='MFA restored'} | ConvertTo-Json | Set-Content $cbsExceptionFile -Encoding UTF8
    Write-Output 'Original MFA login restored. Restart CBS to clear the loaded temporary exception.'
}

