#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Start the Browser4 runtime bundle server.

.DESCRIPTION
    Launches the Browser4 server using the bundled Java runtime and application jars.
    Resolves paths relative to the project root automatically.

.PARAMETER args
    Additional arguments passed through to the Browser4BundleApplication.

.EXAMPLE
    .\bin\start-runtime.ps1
    .\bin\start-runtime.ps1 --debug
#>

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$ErrorActionPreference = "Stop"

# Resolve project root (2 levels up from this script's location)
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\.."
$BundleRoot  = Join-Path $ProjectRoot "browser4-apps\browser4-bundle\target\runtime-bundle\_work\browser4-bundle-runtime-windows-x64\browser4-bundle-runtime-windows-x64"

if (-not (Test-Path $BundleRoot)) {
    Write-Error "Runtime bundle not found at: $BundleRoot"
    Write-Host "Have you built the runtime bundle? Try: .\bin\build.ps1 runtimeBundle" -ForegroundColor Yellow
    exit 1
}

$RuntimeExe   = Join-Path $BundleRoot "runtime\bin\java.exe"
$LibClasspath = Join-Path $BundleRoot "lib\*"
$MainClass    = "ai.platon.pulsar.apps.Browser4BundleApplicationKt"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "         Browser4 Runtime Bundle Server                     " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Bundle root : $BundleRoot" -ForegroundColor DarkGray
Write-Host "  Java runtime: $RuntimeExe" -ForegroundColor DarkGray
Write-Host "  Main class  : $MainClass" -ForegroundColor DarkGray
Write-Host ""

# Build argument list
$JavaArgs = @("-cp", $LibClasspath, $MainClass)
if ($RemainingArgs) {
    $JavaArgs += $RemainingArgs
}

Write-Host "Starting server (PID: $PID)..." -ForegroundColor Green
Write-Host "Health check: http://localhost:18182/actuator/health" -ForegroundColor DarkGray
Write-Host "Press Ctrl+C to stop." -ForegroundColor DarkGray
Write-Host ""

# Use the call operator (&) instead of Start-Process so the Java process
# runs in the same process group as PowerShell.  This ensures Ctrl+C
# propagates to the JVM, which triggers Spring Boot's shutdown hook and
# releases the port.  Start-Process creates a new process group, which
# does not receive CTRL_C_EVENT on Windows.
& $RuntimeExe @JavaArgs
