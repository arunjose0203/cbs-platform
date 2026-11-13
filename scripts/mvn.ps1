# Use a system JDK/Maven or the workspace-local verified development tools.
$ErrorActionPreference = 'Stop'
$cbsRoot = Split-Path -Parent $PSScriptRoot
$cbsLocalJdk = Get-ChildItem -LiteralPath (Join-Path $cbsRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($cbsLocalJdk) {
    $env:JAVA_HOME = $cbsLocalJdk.FullName
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
}
$cbsMaven = Join-Path $cbsRoot '.tools\apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $cbsMaven)) {
    $cbsMavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $cbsMavenCommand) { throw 'Install Maven 3.9.11+ and Java 21, or use the workspace-local tools.' }
    $cbsMaven = $cbsMavenCommand.Source
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 is required.' }
Push-Location $cbsRoot
try {
    $byteBuddy = (Join-Path $cbsRoot '.tools\m2\net\bytebuddy\byte-buddy-agent\1.18.11\byte-buddy-agent-1.18.11.jar').Replace('\','/')
    $mvnArgs = @('-Dmaven.repo.local=.tools/m2')
    if (Test-Path -LiteralPath $byteBuddy) {
        $mvnArgs += "-DargLine=-Duser.timezone=UTC -javaagent:$byteBuddy"
    }
    & $cbsMaven @mvnArgs @args
    $cbsExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $cbsExitCode
