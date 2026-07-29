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

# ── Pre-run summary ──────────────────────────────────────────────────────
$scriptDescriptions = @{
    'agent.ps1'      = 'Agent command lifecycle (agent list/run/status/result)'
    'attach.ps1'     = 'Extension attach + multi-tab lifecycle'
    'tab.ps1'        = 'Tab commands across all session types'
    'experience.ps1' = 'Experience system (PEM v2) full pipeline'
}
foreach ($script in $scripts) {
    $desc = if ($scriptDescriptions.ContainsKey($script.Name)) {
        $scriptDescriptions[$script.Name]
    } else {
        '(no description)'
    }
    Write-Host "  $($script.Name) — $desc" -ForegroundColor DarkGray
}
Write-Host ''
Write-Host "Estimated total: 35–90 minutes for all scripts." -ForegroundColor DarkGray
Write-Host 'Progress markers (>>> STEP N/M) will appear in agent output as each step runs.' -ForegroundColor DarkGray
Write-Host ''

$results = [ordered]@{}
$globalStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

foreach ($script in $scripts) {
    $label = $script.Name
    $scriptStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host "── [$($results.Count + 1)/$($scripts.Count)] $label ──" -ForegroundColor Cyan

    try {
        & $script.FullName
        $scriptStopwatch.Stop()
        $elapsed = [Math]::Round($scriptStopwatch.Elapsed.TotalSeconds, 1)
        if ($LASTEXITCODE -ne 0) {
            # The script itself exited non-zero
            $results[$label] = "FAIL (exit code $LASTEXITCODE, ${elapsed}s)"
            Write-Host "FAIL (exit code $LASTEXITCODE, ${elapsed}s)" -ForegroundColor Red
        } else {
            $results[$label] = "PASS (${elapsed}s)"
            Write-Host "PASS (${elapsed}s)" -ForegroundColor Green
        }
    } catch {
        $scriptStopwatch.Stop()
        $elapsed = [Math]::Round($scriptStopwatch.Elapsed.TotalSeconds, 1)
        $results[$label] = "FAIL ($($_.Exception.Message), ${elapsed}s)"
        Write-Host "FAIL ($($_.Exception.Message), ${elapsed}s)" -ForegroundColor Red
    }
    Write-Host ''
}

$globalStopwatch.Stop()

# ── Summary ──────────────────────────────────────────────────────────
Write-Host '=== Summary ===' -ForegroundColor Cyan
$passed = 0
$failed = 0

foreach ($entry in $results.GetEnumerator()) {
    $isPass = $entry.Value -match '^PASS'
    $color = if ($isPass) { 'Green' } else { 'Red' }
    Write-Host "  $($entry.Key): " -NoNewline
    Write-Host $entry.Value -ForegroundColor $color
    if ($isPass) { $passed++ } else { $failed++ }
}

Write-Host ''
$totalTime = [math]::Round($globalStopwatch.Elapsed.TotalSeconds, 1)
Write-Host "Total: $($scripts.Count)  |  Passed: $passed  |  Failed: $failed  |  Time: ${totalTime}s" -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })

exit $failed
