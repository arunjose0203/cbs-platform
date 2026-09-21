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
    & $cbsMaven '-Dmaven.repo.local=.tools/m2' @args
    $cbsExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $cbsExitCode
