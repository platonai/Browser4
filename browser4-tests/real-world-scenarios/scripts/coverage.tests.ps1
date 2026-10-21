#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: must run on Linux, macOS and Windows (see common.tests.ps1).
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
Coverage guard for the real-world-scenarios suite.

.DESCRIPTION
`bin/test.ps1 ps` discovers every `*.tests.ps1` in the repository, so this thin
wrapper makes the coverage audit part of the PowerShell gate: every PR that
touches PowerShell code, the CLI command registry or a scenario task file fails
when it introduces a command with no scenario.

The audit itself lives in coverage.ps1 — run it directly with `-List` to see the
inventory, or `-UpdateBaseline` after landing scenarios:

    pwsh ./browser4-tests/real-world-scenarios/scripts/coverage.ps1 -List
    pwsh ./browser4-tests/real-world-scenarios/scripts/coverage.ps1 -UpdateBaseline

.NOTES
No browser, backend, agent CLI or network access is required: the audit parses
commands.rs, main.rs and the task markdown files only.
#>

$ErrorActionPreference = 'Stop'

trap {
    Write-Host ''
    Write-Host '══════════════════════════════════════════════════' -ForegroundColor Red
    Write-Host 'FATAL ERROR in coverage.tests.ps1' -ForegroundColor Red
    Write-Host "  $_" -ForegroundColor Red
    Write-Host "  $($_.InvocationInfo.PositionMessage.Trim())" -ForegroundColor Red
    Write-Host '══════════════════════════════════════════════════' -ForegroundColor Red
    exit 1
}

$scriptDir = $PSScriptRoot
$coverageScript = Join-Path $scriptDir 'coverage.ps1'

Write-Host ''
Write-Host '══════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host 'Test group: real-world-scenarios command coverage' -ForegroundColor Cyan
Write-Host '══════════════════════════════════════════════════' -ForegroundColor Cyan

if (-not (Test-Path $coverageScript)) {
    throw "coverage.ps1 not found next to coverage.tests.ps1: $coverageScript"
}

& $coverageScript
$exitCode = $LASTEXITCODE
if ($null -eq $exitCode) { $exitCode = if ($?) { 0 } else { 1 } }

Write-Host ''
if ($exitCode -eq 0) {
    Write-Host 'Test group result: PASS' -ForegroundColor Green
} else {
    Write-Host 'Test group result: FAIL' -ForegroundColor Red
    Write-Host '  A new command without a scenario?  Either add a task file under' -ForegroundColor DarkGray
    Write-Host '  browser4-tests/real-world-scenarios/tasks/ or record the reason in' -ForegroundColor DarkGray
    Write-Host '  scripts/coverage-exclusions.psd1.' -ForegroundColor DarkGray
}
exit $exitCode
