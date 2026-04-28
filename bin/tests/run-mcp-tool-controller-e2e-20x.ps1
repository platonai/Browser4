#!/usr/bin/env pwsh

param(
    [Parameter(Mandatory = $false)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$RepeatCount = 20
)

Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
$mvnCmd = Join-Path $repoRoot "mvnw.cmd"

if (-not (Test-Path $mvnCmd)) {
    Write-Error "Maven wrapper not found: $mvnCmd"
    exit 1
}

$mvnArgs = @(
    "-P=-examples,browser4-tests",
    "-pl", "browser4-tests/browser4-rest-tests",
    "-am",
    "test",
    "-DrunE2ETests=true",
    "-Dtest=MCPToolControllerE2ETest",
    "-Dsurefire.failIfNoSpecifiedTests=false"
)

$successCount = 0
$failureCount = 0
$failedRounds = New-Object System.Collections.Generic.List[int]

Push-Location $repoRoot
try {
    for ($i = 1; $i -le $RepeatCount; $i++) {
        Write-Host ""
        Write-Host "========== Round $i/$RepeatCount =========="

        & $mvnCmd @mvnArgs
        $exitCode = $LASTEXITCODE

        if ($exitCode -eq 0) {
            $successCount++
            Write-Host "Round $i passed"
        }
        else {
            $failureCount++
            $failedRounds.Add($i)
            Write-Host "Round $i failed (exit code: $exitCode)"
        }
    }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "========== Summary =========="
Write-Host "Total rounds : $RepeatCount"
Write-Host "Passed       : $successCount"
Write-Host "Failed       : $failureCount"
if ($failedRounds.Count -gt 0) {
    Write-Host "Failed rounds: $($failedRounds -join ', ')"
}

if ($failureCount -gt 0) {
    exit 1
}

exit 0

