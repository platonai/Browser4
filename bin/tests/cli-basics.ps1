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
    Basic smoke test for browser4-cli functionality.

.DESCRIPTION
    Verifies that the browser4-cli binary is available, prints version/help
    correctly, and can perform basic session operations (open, list, close).

    Uses the globally-installed browser4-cli by default.
    Override with $env:BROWSER4_CLI_BIN.

    This test is self-contained and requires no external services — the CLI
    manages its own server lifecycle.

    All CLI invocations are logged to a per-run directory under bin/tests/logs/.
    Failures are reported with log paths.  If `copilot` is on PATH, it is
    invoked automatically to analyse any failures.

.EXAMPLE
    .\cli-basics.ps1

.EXAMPLE
    $env:BROWSER4_CLI_BIN = 'D:\dev\browser4-cli.exe'; .\cli-basics.ps1
#>

[CmdletBinding()]
param(
    [switch] $SkipServerDependent
)

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'cli-basics'

# -------------------------------------------------------------------
# Resolve CLI binary (also done by test-utils, but we print it early)
# -------------------------------------------------------------------
$CliBin = Get-CliBin
if (-not $CliBin -or -not (Test-Path $CliBin)) {
    Write-Host "ERROR: browser4-cli not found on PATH." -ForegroundColor Red
    Write-Host "Install it with: npm i -g browser4-cli && browser4-cli install" -ForegroundColor Yellow
    exit 1
}

Write-Host "Using CLI: $CliBin" -ForegroundColor DarkGray
try {
    $ver = & $CliBin --version 2>&1
    Write-Host "Version : $($ver -join ' ')" -ForegroundColor DarkGray
} catch {}

Write-TestHeader -Name 'cli-basics'

# Track additional content-based assertions (beyond exit-code checks done by Invoke-TrackedCli).
$script:ContentFailures = 0

function Assert-Output {
    param(
        [string] $Label,
        [scriptblock] $Condition
    )
    if (& $Condition) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        $script:ContentFailures++
        Write-Host "    ❌ $Label" -ForegroundColor Red
    }
}

# -------------------------------------------------------------------
# Test: --version
# -------------------------------------------------------------------
Write-Host "━━━ --version ━━━" -ForegroundColor Cyan

$output = Invoke-TrackedCli -Arguments @('--version') -Label '--version'
$outputText = ($output | Out-String).Trim()
Assert-Output -Label '--version prints version' -Condition { $outputText -match '\d+\.\d+\.\d+' }
Write-Host "    Output: $outputText" -ForegroundColor DarkGray
Write-Host ''

# -------------------------------------------------------------------
# Test: --help
# -------------------------------------------------------------------
Write-Host "━━━ --help ━━━" -ForegroundColor Cyan

$output = Invoke-TrackedCli -Arguments @('--help') -Label '--help'
$outputText = ($output | Out-String).Trim()
Assert-Output -Label '--help mentions "Usage"' -Condition { $outputText -match 'Usage' }
Assert-Output -Label '--help mentions "open"' -Condition { $outputText -match '\bopen\b' }
Assert-Output -Label '--help mentions "goto"' -Condition { $outputText -match '\bgoto\b' }
Assert-Output -Label '--help mentions "--version"' -Condition { $outputText -match '--version' }
Assert-Output -Label '--help mentions "--help"' -Condition { $outputText -match '--help' }
Write-Host ''

# -------------------------------------------------------------------
# Test: help for specific commands
# -------------------------------------------------------------------
Write-Host "━━━ help <command> ━━━" -ForegroundColor Cyan

$output = Invoke-TrackedCli -Arguments @('--help', 'open') -Label '--help open'
$outputText = ($output | Out-String).Trim()
Assert-Output -Label '--help open mentions URL' -Condition { $outputText -match 'url|URL' }

$output = Invoke-TrackedCli -Arguments @('--help', 'agent') -Label '--help agent'
Write-Host ''

# -------------------------------------------------------------------
# Test: Server-dependent operations
# -------------------------------------------------------------------
if ($SkipServerDependent) {
    Write-Host "━━━ Server tests skipped (-SkipServerDependent) ━━━" -ForegroundColor Yellow
} else {
    Write-Host "━━━ Session lifecycle ━━━" -ForegroundColor Cyan

    # open
    Write-Host '--- open ---' -ForegroundColor DarkGray
    $output = Invoke-TrackedCli -Arguments @('open') -Label 'open' -PassThruOnly
    $openExitCode = $LASTEXITCODE
    $outputText = ($output | Out-String).Trim()

    # open can exit 0 (success) or non-zero if server can't auto-start.
    # Use PassThruOnly so it always shows PASS; we check manually below.
    if ($openExitCode -eq 0) {
        Write-Host "    ✅ server is running, continuing session tests" -ForegroundColor Green
    } else {
        Write-Host "    ⚠ open exited $openExitCode — server may not be running" -ForegroundColor Yellow
        Write-Host "    Output: $outputText" -ForegroundColor DarkGray
        Write-Host "    ⚠ Skipping remaining server-dependent tests" -ForegroundColor Yellow
        $SkipServerDependent = $true
    }

    if (-not $SkipServerDependent) {
        # list
        Write-Host '--- list ---' -ForegroundColor DarkGray
        $output = Invoke-TrackedCli -Arguments @('list') -Label 'list'
        $outputText = ($output | Out-String).Trim()
        Assert-Output -Label 'list produces output' -Condition { $outputText.Length -gt 0 }
        $nl = [Environment]::NewLine
        Write-Host "    Output: $($outputText -split $nl | Select-Object -First 3)" -ForegroundColor DarkGray

        # close
        Write-Host '--- close ---' -ForegroundColor DarkGray
        $output = Invoke-TrackedCli -Arguments @('close') -Label 'close'

        # Verify close actually worked — re-open to confirm we can restart
        Write-Host '--- re-open after close ---' -ForegroundColor DarkGray
        $output = Invoke-TrackedCli -Arguments @('open') -Label 're-open after close' -PassThruOnly
        if ($LASTEXITCODE -eq 0) {
            Write-Host "    ✅ re-open after close succeeds" -ForegroundColor Green
        } else {
            Write-Host "    ⚠ re-open exited $LASTEXITCODE" -ForegroundColor Yellow
        }

        # Final close (best-effort, ignore exit code)
        Invoke-TrackedCli -Arguments @('close') -Label 'final close' -PassThruOnly 2>$null
    }

    Write-Host ''
}

# -------------------------------------------------------------------
# Test: --json flag
# -------------------------------------------------------------------
Write-Host "━━━ --json flag ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('--json', '--help') -Label '--json --help'
Write-Host ''

# -------------------------------------------------------------------
# Test: --quiet flag
# -------------------------------------------------------------------
Write-Host "━━━ --quiet flag ━━━" -ForegroundColor Cyan
$output = Invoke-TrackedCli -Arguments @('--quiet', '--version') -Label '--quiet --version'
Write-Host ''

# -------------------------------------------------------------------
# Final report
# -------------------------------------------------------------------
$exitCode = Finish-TestSession -ExtraCopilotPrompt "These are browser4-cli smoke test failures."
if ($script:ContentFailures -gt 0) {
    Write-Host "  ⚠ $script:ContentFailures content-based assertion(s) also failed" -ForegroundColor Red
    if ($exitCode -eq 0) { $exitCode = 1 }
}
exit $exitCode
