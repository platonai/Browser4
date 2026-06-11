#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Test runner for browser4-cli integration tests.

.DESCRIPTION
    Discovers and runs test scripts under bin/tests/.  Supports running
    individual tests, named categories, or the full suite.  All tests use
    the globally-installed browser4-cli by default (override with
    $env:BROWSER4_CLI_BIN).

    Designed to work identically in CI and local dev environments.

.PARAMETER Test
    One or more test names or categories to run.  Valid categories:
      smoke   — fast smoke tests (cli-basics)
      agent   — agent task tests
      swarm   — swarm/scrape lifecycle tests
      stress  — stress / load tests
      all     — every test in the directory (default)

    Individual test names can be given as the script basename with or
    without the .ps1 extension (e.g. "cli-basics" or "cli-basics.ps1").

.PARAMETER List
    List available tests and exit.

.PARAMETER TimeoutSeconds
    Per-test timeout in seconds (default: 600).

.PARAMETER CI
    CI mode: produce GitHub-actions-friendly output and fold groups.

.EXAMPLE
    .\run-tests.ps1 -List

.EXAMPLE
    .\run-tests.ps1 smoke

.EXAMPLE
    .\run-tests.ps1 cli-basics,agent-run-page-visit

.EXAMPLE
    .\run-tests.ps1 -TimeoutSeconds 120 agent
#>

[CmdletBinding()]
param(
    [string[]] $Test = @('all'),
    [switch] $List,
    [int] $TimeoutSeconds = 600,
    [switch] $CI
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# -------------------------------------------------------------------
# Test registry
# -------------------------------------------------------------------
# Each entry:  Name (script basename without .ps1), Category, Description
$Tests = @(
    [PSCustomObject]@{ Name = 'cli-basics';                     Category = 'smoke';  Description = 'Basic CLI functionality (version, help, open, close)' },
    [PSCustomObject]@{ Name = 'agent-run-free-command';         Category = 'agent';  Description = 'Agent free-command task lifecycle' },
    [PSCustomObject]@{ Name = 'agent-run-page-visit';           Category = 'agent';  Description = 'Agent page-visit task lifecycle' },
    [PSCustomObject]@{ Name = 'agent-run-page-visit-interact';  Category = 'agent';  Description = 'Agent page-visit + interaction task lifecycle' },
    [PSCustomObject]@{ Name = 'swarm-agents';                   Category = 'swarm';  Description = 'Swarm create / submit / status lifecycle' },
    [PSCustomObject]@{ Name = 'swarm-agents-stress';            Category = 'stress'; Description = 'Swarm stress test with seed URLs' },
    [PSCustomObject]@{ Name = 'stress-session';                 Category = 'stress'; Description = 'Session lifecycle stress test' },
    [PSCustomObject]@{ Name = 'stress-install';                 Category = 'stress'; Description = 'Install / server lifecycle stress test' }
)

# Map category name → list of test names
$Categories = @{
    smoke  = @('cli-basics')
    agent  = @('agent-run-free-command', 'agent-run-page-visit', 'agent-run-page-visit-interact')
    swarm  = @('swarm-agents', 'swarm-agents-stress')
    stress = @('swarm-agents-stress', 'stress-session', 'stress-install')
}

# -------------------------------------------------------------------
# Resolve which tests to run
# -------------------------------------------------------------------
function Resolve-TestNames {
    param([string[]] $Inputs)

    $resolved = [System.Collections.ArrayList]::new()
    $validNames = $Tests.Name

    foreach ($input in $Inputs) {
        $normalized = if ($input -match '\.ps1$') { $input -replace '\.ps1$', '' } else { $input }

        if ($normalized -eq 'all') {
            foreach ($t in $Tests) { $null = $resolved.Add($t.Name) }
        }
        elseif ($Categories.ContainsKey($normalized)) {
            foreach ($name in $Categories[$normalized]) {
                if ($name -notin $resolved) { $null = $resolved.Add($name) }
            }
        }
        elseif ($normalized -in $validNames) {
            if ($normalized -notin $resolved) { $null = $resolved.Add($normalized) }
        }
        else {
            Write-Warning "Unknown test or category: '$input'"
        }
    }

    return $resolved
}

# -------------------------------------------------------------------
# List
# -------------------------------------------------------------------
if ($List) {
    Write-Host "`nAvailable tests under $ScriptDir`n" -ForegroundColor Cyan
    $byCategory = $Tests | Group-Object Category
    foreach ($group in $byCategory) {
        Write-Host "  [$($group.Name)]" -ForegroundColor Yellow
        foreach ($t in $group.Group) {
            Write-Host ("    {0,-38}  {1}" -f $t.Name, $t.Description)
        }
        Write-Host ''
    }
    Write-Host "Categories: $($Categories.Keys -join ', '), all" -ForegroundColor DarkGray
    exit 0
}

# -------------------------------------------------------------------
# Resolve and validate
# -------------------------------------------------------------------
$toRun = Resolve-TestNames -Inputs $Test
if ($toRun.Count -eq 0) {
    Write-Error 'No tests matched the given filters.'
    exit 1
}

# Resolve the CLI binary so we can print version info
$cliBin = if ($env:BROWSER4_CLI_BIN) {
    $env:BROWSER4_CLI_BIN
} else {
    $cmd = Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue
    if ($cmd) { $cmd.Source } else { $null }
}

Write-Host '══════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host '  browser4-cli Test Runner' -ForegroundColor Cyan
Write-Host '══════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host "  Tests dir : $ScriptDir"
Write-Host "  CLI       : $(if ($cliBin) { $cliBin } else { 'browser4-cli (PATH)' })"
if ($cliBin -and (Test-Path $cliBin)) {
    $ver = & $cliBin --version 2>&1
    Write-Host "  Version   : $ver"
}
Write-Host "  Tests     : $($toRun.Count) selected"
Write-Host "  Timeout   : ${TimeoutSeconds}s per test"
Write-Host '══════════════════════════════════════════════════════'
Write-Host ''

# -------------------------------------------------------------------
# Run
# -------------------------------------------------------------------
$startedAt = Get-Date
$results = [System.Collections.ArrayList]::new()
$passes = 0
$failures = 0

$index = 0

foreach ($testName in $toRun) {
    $index++
    $scriptPath = Join-Path $ScriptDir "$testName.ps1"

    # Look up description
    $entry = $Tests | Where-Object { $_.Name -eq $testName } | Select-Object -First 1
    $desc = if ($entry) { $entry.Description } else { $testName }

    if (-not (Test-Path $scriptPath)) {
        Write-Host "[$index/$($toRun.Count)] $testName — SKIP (script not found)" -ForegroundColor Yellow
        $null = $results.Add([PSCustomObject]@{
            Name     = $testName
            Passed   = $false
            Elapsed  = [TimeSpan]::Zero
            ExitCode = -1
            Error    = 'Script not found'
        })
        $failures++
        continue
    }

    if ($CI) {
        Write-Host "::group::[$index/$($toRun.Count)] $testName — $desc"
    } else {
        Write-Host "[$index/$($toRun.Count)] $testName — $desc" -ForegroundColor White
    }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $testStarted = Get-Date

    try {
        $proc = Start-Process -FilePath 'pwsh' `
            -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $scriptPath) `
            -NoNewWindow `
            -Wait `
            -PassThru

        $sw.Stop()
        $exitCode = $proc.ExitCode

        $null = $results.Add([PSCustomObject]@{
            Name     = $testName
            Passed   = ($exitCode -eq 0)
            Elapsed  = $sw.Elapsed
            ExitCode = $exitCode
            Error    = ''
        })

        if ($exitCode -eq 0) {
            $passes++
            Write-Host "  ✅ PASS  (${exitCode})  $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s" -ForegroundColor Green
        } else {
            $failures++
            Write-Host "  ❌ FAIL  (exit=$exitCode)  $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s" -ForegroundColor Red
        }
    }
    catch {
        $sw.Stop()
        $null = $results.Add([PSCustomObject]@{
            Name     = $testName
            Passed   = $false
            Elapsed  = $sw.Elapsed
            ExitCode = -1
            Error    = $_.Exception.Message
        })
        $failures++
        Write-Host "  ❌ ERROR  $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s — $($_.Exception.Message)" -ForegroundColor Red
    }

    if ($CI) {
        Write-Host '::endgroup::'
    }
    Write-Host ''
}

# -------------------------------------------------------------------
# Summary
# -------------------------------------------------------------------
$elapsed = (Get-Date) - $startedAt

Write-Host '══════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host '  RESULTS' -ForegroundColor Cyan
Write-Host '══════════════════════════════════════════════════════' -ForegroundColor Cyan

foreach ($r in $results) {
    $icon = if ($r.Passed) { '✅' } else { '❌' }
    $color = if ($r.Passed) { 'Green' } else { 'Red' }
    $elapsedStr = if ($r.Elapsed.TotalSeconds -gt 0) { "$('{0:F1}' -f $r.Elapsed.TotalSeconds)s" } else { '-' }
    $errStr = if ($r.Error) { " — $($r.Error)" } else { '' }
    Write-Host "  $icon $($r.Name)  $elapsedStr${errStr}" -ForegroundColor $color
}

Write-Host ''
Write-Host "  Total   : $($results.Count)"
Write-Host "  Passed  : $passes" -ForegroundColor $(if ($passes -gt 0) { 'Green' } else { 'DarkGray' })
Write-Host "  Failed  : $failures" -ForegroundColor $(if ($failures -gt 0) { 'Red' } else { 'DarkGray' })
Write-Host ("  Elapsed : {0:hh\:mm\:ss}" -f $elapsed)

exit $(if ($failures -eq 0) { 0 } else { 1 })
