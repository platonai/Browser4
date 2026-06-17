#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════
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
      helpers — unit tests for test infrastructure helpers
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
    [switch] $CI,
    [string] $Locale = ''
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# -------------------------------------------------------------------
# Load shared test utilities (for copilot analysis on failure)
# -------------------------------------------------------------------
Import-Module "$ScriptDir\test-utils.psm1" -Force

$RunnerLogDir = Join-Path $ScriptDir 'logs'
$null = New-Item -Path $RunnerLogDir -ItemType Directory -Force -ErrorAction SilentlyContinue

# Per-category timeout overrides (seconds).  Stress tests involve server
# cold-starts and many page loads; they need more time than the default.
$CategoryTimeoutOverrides = @{
    stress  = 900   # 15 min — accounts for cold-start overhead
    swarm   = 900   # 15 min — swarm tests may also cold-start
    agent   = 900   # 15 min — agent tasks can be slow
}

# Server pre-start: run a warm-up open+close so the Browser4 runtime is
# downloaded and started once before any test runs.  Saves 60–120s of
# cold-start overhead per test.
$ShouldPreStartServer = $true
if ($env:BROWSER4_SKIP_PRESTART -eq '1') {
    $ShouldPreStartServer = $false
}

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
    [PSCustomObject]@{ Name = 'stress-swarm-agents';            Category = 'stress'; Description = 'Swarm stress test with seed URLs' },
    [PSCustomObject]@{ Name = 'stress-session';                 Category = 'stress'; Description = 'Session lifecycle stress test' },
    [PSCustomObject]@{ Name = 'stress-install';                 Category = 'stress'; Description = 'Install / server lifecycle stress test' },
    [PSCustomObject]@{ Name = 'test-production-helpers';       Category = 'helpers'; Description = 'Unit tests for test-production.ps1 helper functions' },
    [PSCustomObject]@{ Name = 'test-utils-helpers';            Category = 'helpers'; Description = 'Unit tests for test-utils.psm1 helper functions' }
)

# Map category name → list of test names
$Categories = @{
    smoke  = @('cli-basics')
    agent  = @('agent-run-free-command', 'agent-run-page-visit', 'agent-run-page-visit-interact')
    swarm  = @('swarm-agents', 'stress-swarm-agents')
    stress  = @('stress-swarm-agents', 'stress-session', 'stress-install')
    helpers = @('test-production-helpers', 'test-utils-helpers')
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
    $cmd = Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { $cmd.Source } else { $null }
}

# Resolve and export locale for child process inheritance.
# Each child script picks this up via Get-TestLocale -> $env:BROWSER4_TEST_LOCALE.
$env:BROWSER4_TEST_LOCALE = Get-TestLocale -Locale $Locale

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
Write-Host "  Locale    : $env:BROWSER4_TEST_LOCALE"
Write-Host '══════════════════════════════════════════════════════'
Write-Host ''

# -------------------------------------------------------------------
# Pre-start the Browser4 server (download + warm start)
# -------------------------------------------------------------------
if ($ShouldPreStartServer -and $cliBin) {
    Write-Host '── Pre-starting Browser4 server (warm-up) ──' -ForegroundColor DarkYellow
    try {
        # Kill any stale port holders first.
        Clear-Browser4Port -Port 8182 -WaitSeconds 2

        # Open a trivial session to trigger server download/start.
        # Use Start-Process with a 120 s timeout so we don't hang if
        # the page load stalls (the server will still be running and
        # warm even if the open doesn't complete).
        $preTimeout = 120
        $tmpOut = Join-Path $env:TEMP "b4_prestart_stdout_${pid}.txt"
        $tmpErr = Join-Path $env:TEMP "b4_prestart_stderr_${pid}.txt"
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        $preProc = Start-Process -FilePath $cliBin `
            -ArgumentList 'open', 'https://example.com' `
            -NoNewWindow -PassThru `
            -RedirectStandardOutput $tmpOut `
            -RedirectStandardError $tmpErr

        $preCompleted = $preProc.WaitForExit($preTimeout * 1000)
        $preExitCode = if ($preCompleted) { $preProc.ExitCode } else { $preProc.Kill($true); -99 }

        $preOut = @()
        if (Test-Path $tmpOut) { $preOut += Get-Content $tmpOut -Encoding UTF8 -ErrorAction SilentlyContinue }
        if (Test-Path $tmpErr) { $preOut += Get-Content $tmpErr -Encoding UTF8 -ErrorAction SilentlyContinue |
            ForEach-Object { "[stderr] $_" } }
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        if ($preCompleted) {
            Write-Host "  open (exit=$preExitCode): $($preOut -join '; ')" -ForegroundColor DarkGray
        } else {
            Write-Host "  ⚠ open timed out after ${preTimeout}s — server may still be starting in background" -ForegroundColor DarkYellow
        }

        # Give the server a moment to finish initialising, then close.
        Start-Sleep -Seconds 5
        $preCloseOut = & $cliBin close 2>&1
        Write-Host "  close: $($preCloseOut -join '; ')" -ForegroundColor DarkGray
        Start-Sleep -Seconds 2
        Write-Host '  ✅ Server pre-start complete.' -ForegroundColor Green
    } catch {
        Write-Host "  ⚠ Server pre-start failed (non-fatal): $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
    Write-Host ''
}

# -------------------------------------------------------------------
# Run
# -------------------------------------------------------------------
$startedAt = Get-Date
$results = [System.Collections.ArrayList]::new()
$passes = 0
$failures = 0

$index = 0

foreach ($testName in $toRun) {
    # Resolve per-test timeout: category override → explicit parameter → default
    $entry = $Tests | Where-Object { $_.Name -eq $testName } | Select-Object -First 1
    $testTimeout = $TimeoutSeconds
    if ($entry -and $CategoryTimeoutOverrides.ContainsKey($entry.Category)) {
        $testTimeout = $CategoryTimeoutOverrides[$entry.Category]
    }
    $index++
    $scriptPath = Join-Path $ScriptDir "$testName.ps1"

    # Look up description
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
        Write-Host "::group::[$index/$($toRun.Count)] $testName — $desc (timeout=${testTimeout}s)"
    } else {
        Write-Host "[$index/$($toRun.Count)] $testName — $desc" -ForegroundColor White
    }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $testStarted = Get-Date

    try {
        $proc = Start-Process -FilePath 'pwsh' `
            -ArgumentList @('-NoProfile', '-NonInteractive', '-File', $scriptPath) `
            -NoNewWindow `
            -PassThru

        # Wait with timeout, printing progress every 10 s for runs
        # that exceed 30 s so the user can tell it hasn't hung.
        if ($testTimeout -gt 30) {
            $deadline = [DateTime]::UtcNow.AddSeconds($testTimeout)
            $completed = $false
            while (-not $completed -and ([DateTime]::UtcNow -lt $deadline)) {
                $completed = $proc.WaitForExit(10000)
                if (-not $completed -and ([DateTime]::UtcNow -lt $deadline)) {
                    $elapsed = [Math]::Floor($sw.Elapsed.TotalSeconds)
                    Write-Host ("`r  ⏳ {0} — {1}s / {2}s … " -f $testName, $elapsed, $testTimeout) -NoNewline -ForegroundColor DarkGray
                }
            }
            Write-Host ''
        } else {
            $completed = $proc.WaitForExit($testTimeout * 1000)
        }
        if (-not $completed) {
            Write-Host "  ⚠ TIMEOUT after ${testTimeout}s — killing" -ForegroundColor Red
            $proc.Kill()
            $proc.WaitForExit(5000) | Out-Null
        }

        $sw.Stop()
        $exitCode = if ($completed) { $proc.ExitCode } else { -99 }

        $null = $results.Add([PSCustomObject]@{
            Name     = $testName
            Passed   = ($exitCode -eq 0)
            Elapsed  = $sw.Elapsed
            ExitCode = $exitCode
            Error    = $(if (-not $completed) { "Timeout after ${testTimeout}s" } else { '' })
        })

        if ($exitCode -eq 0) {
            $passes++
            Write-Host "  ✅ PASS  (${exitCode})  $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s" -ForegroundColor Green
        } else {
            $failures++
            if (-not $completed) {
                Write-Host "  ❌ TIMEOUT  $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s" -ForegroundColor Red
            } else {
                Write-Host "  ❌ FAIL  (exit=$exitCode)  $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s" -ForegroundColor Red
            }
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
Write-Host "  Logs    : $RunnerLogDir" -ForegroundColor DarkGray

# -------------------------------------------------------------------
# Failure log collection & copilot analysis
# -------------------------------------------------------------------
if ($failures -gt 0) {
    Write-Host "`n──────────────────────────────────────────────────────" -ForegroundColor Red
    Write-Host "  FAILURE LOGS" -ForegroundColor Red
    Write-Host "──────────────────────────────────────────────────────" -ForegroundColor Red

    $allFailureLogs = [System.Collections.ArrayList]::new()

    foreach ($r in $results | Where-Object { -not $_.Passed }) {
        Write-Host "`n  ❌ $($r.Name) (exit=$($r.ExitCode))" -ForegroundColor Red

        # Find the most recent log directory for this test
        $testLogPattern = "$($r.Name)_*"
        $testLogDirs = @(Get-ChildItem -Path $RunnerLogDir -Directory -Filter $testLogPattern `
            -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)

        if ($testLogDirs.Count -gt 0) {
            $testLogDir = $testLogDirs[0]
            Write-Host "     Log dir: $($testLogDir.FullName)" -ForegroundColor DarkGray

            # Collect all .log files from this test's log dir
            $logFiles = @(Get-ChildItem -Path $testLogDir.FullName -Filter '*.log' `
                -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })

            if ($logFiles.Count -gt 0) {
                foreach ($logFile in $logFiles) {
                    Write-Host "     📄 $logFile" -ForegroundColor DarkGray
                    $null = $allFailureLogs.Add($logFile)
                }
            } else {
                Write-Host "     (no log files found in directory)" -ForegroundColor DarkGray
            }
        } else {
            Write-Host "     (no log directory found for this test)" -ForegroundColor DarkGray
        }
    }

    # Invoke copilot analysis
    if ($allFailureLogs.Count -gt 0) {
        Write-Host "`n──────────────────────────────────────────────────────" -ForegroundColor Magenta
        $analysisPrompt = "Browser4 CLI test suite run. $failures test(s) failed out of $($results.Count) total. See individual test logs for details."
        $analysisResult = Invoke-CopilotAnalysis -LogPaths $allFailureLogs.ToArray() -ExtraPrompt $analysisPrompt
    } else {
        Write-Host "`n  ℹ No detailed log files found for failed tests" -ForegroundColor Yellow
    }
}

exit $(if ($failures -eq 0) { 0 } else { 1 })
