#!/usr/bin/env pwsh
<#
.SYNOPSIS
Run all workflow scripts in this directory. Failures are isolated — a failing
script does not stop the rest.

.DESCRIPTION
Discovers every *.ps1 file in the same directory (excluding itself), runs each
in order, and prints a pass/fail summary at the end. Exit code is the number
of failed scripts (0 = all green).

.NOTES
Run from the repo root:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/run-all.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$ownName = $MyInvocation.MyCommand.Name
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$scripts = Get-ChildItem -Path $scriptDir -Filter '*.ps1' |
    Where-Object { $_.Name -ne $ownName } |
    Sort-Object Name

if (-not $scripts) {
    Write-Host 'No workflow scripts found.' -ForegroundColor Yellow
    exit 0
}

Write-Host "=== Workflow runner: $($scripts.Count) script(s) found ===" -ForegroundColor Cyan
Write-Host ''

$results = [ordered]@{}
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

foreach ($script in $scripts) {
    $label = $script.Name
    Write-Host "── [$($results.Count + 1)/$($scripts.Count)] $label ──" -ForegroundColor DarkGray

    try {
        & $script.FullName
        if ($LASTEXITCODE -ne 0) {
            # The script itself exited non-zero
            $results[$label] = "FAIL (exit code $LASTEXITCODE)"
            Write-Host "FAIL (exit code $LASTEXITCODE)" -ForegroundColor Red
        } else {
            $results[$label] = 'PASS'
            Write-Host "PASS" -ForegroundColor Green
        }
    } catch {
        $results[$label] = "FAIL ($($_.Exception.Message))"
        Write-Host "FAIL ($($_.Exception.Message))" -ForegroundColor Red
    }
    Write-Host ''
}

$stopwatch.Stop()

# ── Summary ──────────────────────────────────────────────────────────
Write-Host '=== Summary ===' -ForegroundColor Cyan
$passed = 0
$failed = 0

foreach ($entry in $results.GetEnumerator()) {
    $color = if ($entry.Value -eq 'PASS') { 'Green' } else { 'Red' }
    Write-Host "  $($entry.Key): " -NoNewline
    Write-Host $entry.Value -ForegroundColor $color
    if ($entry.Value -eq 'PASS') { $passed++ } else { $failed++ }
}

Write-Host ''
Write-Host "Total: $($scripts.Count)  |  Passed: $passed  |  Failed: $failed  |  Time: $([math]::Round($stopwatch.Elapsed.TotalSeconds, 1))s" -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })

exit $failed
