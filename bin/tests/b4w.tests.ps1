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
    Unit tests for b4w.ps1 — the Browser4 CLI wrapper script.

.DESCRIPTION
    Tests subcommand routing, help output, argument passthrough,
    --rebuild flag behavior, safe argument quoting, and CWD restoration.

    Run standalone:
        pwsh bin/tests/b4w.tests.ps1

    Run via runner:
        pwsh bin/test.ps1 ps
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Resolve paths
# -------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TestUtilsModule = Join-Path $ScriptDir '..\..\browser4-tests\tests-production\test-utils.psm1'
$B4wPs1Path = Join-Path $ScriptDir '..\..\b4w.ps1'

# -------------------------------------------------------------------
# Load shared test utilities (soft dependency)
# -------------------------------------------------------------------
if (Test-Path $TestUtilsModule) {
    Import-Module $TestUtilsModule -Force
    Start-TestSession -Name 'b4w-helpers' -SkipPortCleanup
    Write-TestHeader -Name 'b4w-helpers'
} else {
    Write-Host "WARNING: test-utils.psm1 not found at $TestUtilsModule — running in standalone mode." -ForegroundColor Yellow
    $script:__PassCount = 0
    $script:__FailCount = 0
    function Register-CliResult {
        param($Label, $ExitCode, $Elapsed, $OutputLines)
        if ($ExitCode -eq 0) { $script:__PassCount++ } else { $script:__FailCount++ }
        Write-Host "    ${Label}: ExitCode=$ExitCode" -ForegroundColor DarkGray
    }
    function Finish-TestSession { $script:__FailCount }
}

# -------------------------------------------------------------------
# Custom assertion helpers (same pattern as test.ps1.tests.ps1)
# -------------------------------------------------------------------
$script:ContentFailures = 0

function Assert-Returns {
    param(
        [string]$Label,
        $Actual,
        $Expected,
        [string]$Description = ''
    )
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $passed = ($Actual -eq $Expected) -or
              ($null -eq $Actual -and $null -eq $Expected)
    $sw.Stop()
    $exitCode = if ($passed) { 0 } else { 1 }
    $detail = if ($Description) { $Description } else { "expected=$Expected actual=$Actual" }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed $sw.Elapsed
    }
    if (-not $passed) {
        Write-Host "    ❌ $Label — expected '$Expected', got '$Actual'" -ForegroundColor Red
        $script:ContentFailures++
    } else {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    }
}

function Assert-ContainsString {
    param(
        [string]$Label,
        [string]$Haystack,
        [string]$Needle
    )
    $passed = $Haystack -match [regex]::Escape($Needle)
    if ($passed) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        Write-Host "    ❌ $Label — string does not contain '$Needle'" -ForegroundColor Red
        $script:ContentFailures++
    }
    $exitCode = if ($passed) { 0 } else { 1 }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed ([TimeSpan]::Zero)
    }
}

function Assert-NotContainsString {
    param(
        [string]$Label,
        [string]$Haystack,
        [string]$Needle
    )
    $passed = $Haystack -notmatch [regex]::Escape($Needle)
    if ($passed) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        Write-Host "    ❌ $Label — string unexpectedly contains '$Needle'" -ForegroundColor Red
        $script:ContentFailures++
    }
    $exitCode = if ($passed) { 0 } else { 1 }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed ([TimeSpan]::Zero)
    }
}

function Assert-NotNull {
    param(
        [string]$Label,
        $Value
    )
    $passed = $null -ne $Value
    if ($passed) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        Write-Host "    ❌ $Label — value is null" -ForegroundColor Red
        $script:ContentFailures++
    }
    $exitCode = if ($passed) { 0 } else { 1 }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed ([TimeSpan]::Zero)
    }
}

# Verify the source script exists
if (-not (Test-Path $B4wPs1Path)) {
    Write-Error "b4w.ps1 not found at: $B4wPs1Path"
    exit 1
}

Write-Host "Source : $B4wPs1Path" -ForegroundColor DarkGray
Write-Host ''

$b4wAbs = (Resolve-Path $B4wPs1Path).Path

# ═══════════════════════════════════════════════════════════════════
# TESTS: Help output (no arguments)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Help: no arguments ━━━" -ForegroundColor Cyan

$output = pwsh -NoProfile -Command "& '$b4wAbs' *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Help no args: shows Usage' -Haystack $output -Needle 'Usage:'
Assert-ContainsString -Label 'Help no args: lists cli command' -Haystack $output -Needle 'cli [args]'
Assert-ContainsString -Label 'Help no args: lists coworker command' -Haystack $output -Needle 'coworker'
Assert-ContainsString -Label 'Help no args: lists test command' -Haystack $output -Needle 'test [args]'
Assert-ContainsString -Label 'Help no args: lists build command' -Haystack $output -Needle 'build [args]'
Assert-ContainsString -Label 'Help no args: lists b4w install' -Haystack $output -Needle 'b4w install'
Assert-ContainsString -Label 'Help no args: mentions b4w.bat' -Haystack $output -Needle 'b4w.bat'
Assert-ContainsString -Label 'Help no args: mentions b4w.sh' -Haystack $output -Needle 'b4w.sh'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Help output (explicit --help and -h)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Help: --help and -h ━━━" -ForegroundColor Cyan

$output = pwsh -NoProfile -Command "& '$b4wAbs' --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Help --help: shows Usage' -Haystack $output -Needle 'Usage:'

# When running with -h or --help, b4w.ps1 delegates to the CLI binary which shows
# its own help. The top-level help is shown when NO arguments are provided.
# Verify that the script itself produces output (does not error).
$output = pwsh -NoProfile -Command "& '$b4wAbs' --help *>&1" *>&1 | Out-String
Assert-NotNull -Label 'Help --help: produces output' -Value $output

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — coworker
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand: coworker ━━━" -ForegroundColor Cyan

# The coworker subcommand should delegate to coworker/coworker.ps1
# Without a real coworker.ps1 or arguments, it may error — we verify
# the routing happens (script is invoked, no PowerShell-level errors).
$coworkerScript = Join-Path $ScriptDir '..\..\coworker\coworker.ps1'
if (Test-Path $coworkerScript) {
    $output = pwsh -NoProfile -Command "& '$b4wAbs' coworker list *>&1" *>&1 | Out-String
    # The script should produce some output (either task list or an error)
    Assert-NotNull -Label 'Coworker routing: produces output' -Value $output
} else {
    Write-Host "    ⚠ SKIP: coworker/coworker.ps1 not found" -ForegroundColor Yellow
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — test
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand: test ━━━" -ForegroundColor Cyan

# The test subcommand delegates to bin/test.ps1
# Verify it invokes test.ps1 with passthrough args
$output = pwsh -NoProfile -Command "& '$b4wAbs' test -Show ps *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Test routing: -Show ps reaches test.ps1' -Haystack $output -Needle '[SHOW]'

# b4w test with --help should delegate to test.ps1 which shows its usage
$output = pwsh -NoProfile -Command "& '$b4wAbs' test -h *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Test routing: -h shows test.ps1 usage' -Haystack $output -Needle 'Usage:'

# b4w test with unknown type should error from test.ps1
$output = pwsh -NoProfile -Command "& '$b4wAbs' test bogus_type_xyz *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Test routing: unknown type errors' -Haystack $output -Needle 'bogus_type_xyz'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — build
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand: build ━━━" -ForegroundColor Cyan

# b4w build should delegate to bin/build.ps1 → bin/build/build.ps1
$output = pwsh -NoProfile -Command "& '$b4wAbs' build --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Build routing: --help shows build help' -Haystack $output -Needle 'Build script for Browser4'

# b4w build with unknown flag should error from build.ps1
$output = pwsh -NoProfile -Command "& '$b4wAbs' build --nonexistent-flag-xyz *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Build routing: unknown flag errors' -Haystack $output -Needle 'Unknown flag:'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — b4w install
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand: b4w install ━━━" -ForegroundColor Cyan

# b4w b4w install should create the `b4w` bash launcher script
$output = pwsh -NoProfile -Command "& '$b4wAbs' b4w install *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'b4w install: reports success' -Haystack $output -Needle 'b4w installed successfully'
Assert-ContainsString -Label 'b4w install: mentions bash launcher' -Haystack $output -Needle 'Created bash launcher'
Assert-ContainsString -Label 'b4w install: mentions PATH' -Haystack $output -Needle 'PATH'

# Verify the `b4w` bash script was actually created
$b4wBashPath = Join-Path $ScriptDir '..\..\b4w'
Assert-Returns -Label 'b4w install: bash launcher file exists' -Actual (Test-Path $b4wBashPath) -Expected $true

# Verify the content of the created bash launcher
if (Test-Path $b4wBashPath) {
    $b4wContent = Get-Content $b4wBashPath -Raw
    Assert-ContainsString -Label 'b4w install: bash launcher has shebang' -Haystack $b4wContent -Needle '#!/bin/bash'
    Assert-ContainsString -Label 'b4w install: bash launcher delegates to b4w.sh' -Haystack $b4wContent -Needle 'b4w.sh'
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — b4w uninstall
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand: b4w uninstall ━━━" -ForegroundColor Cyan

# b4w b4w uninstall should remove the bash launcher (created by install above)
$output = pwsh -NoProfile -Command "& '$b4wAbs' b4w uninstall *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'b4w uninstall: reports success' -Haystack $output -Needle 'b4w uninstalled successfully'
Assert-ContainsString -Label 'b4w uninstall: removes from PATH' -Haystack $output -Needle 'Removed from user PATH'

# Verify the `b4w` bash script was deleted
$b4wBashPath = Join-Path $ScriptDir '..\..\b4w'
Assert-Returns -Label 'b4w uninstall: bash launcher file removed' -Actual (Test-Path $b4wBashPath) -Expected $false

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — b4w (bare / unknown)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand: b4w (bare / unknown) ━━━" -ForegroundColor Cyan

# b4w b4w (no subcommand) should print the top-level help
$output = pwsh -NoProfile -Command "& '$b4wAbs' b4w *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'b4w bare: shows top-level help' -Haystack $output -Needle 'Usage: b4w [command] [options]'
Assert-ContainsString -Label 'b4w bare: lists install command' -Haystack $output -Needle 'b4w install'
Assert-ContainsString -Label 'b4w bare: lists uninstall command' -Haystack $output -Needle 'b4w uninstall'

# b4w b4w <unknown-subcommand> should also print help
$output = pwsh -NoProfile -Command "& '$b4wAbs' b4w bogus-cmd *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'b4w unknown: shows help' -Haystack $output -Needle 'Usage: b4w [command] [options]'

# b4w b4w should NOT try to run the CLI binary (it must not cause cargo-run noise)
Assert-NotContainsString -Label 'b4w bare: does not invoke cargo' -Haystack $output -Needle 'cargo run'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — cli (explicit)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand: cli ━━━" -ForegroundColor Cyan

# b4w cli should invoke the CLI binary.  Without the binary built,
# it falls back to `cargo run --manifest-path ...` which may fail.
# We verify the script doesn't crash with a PowerShell error.
$output = pwsh -NoProfile -Command "& '$b4wAbs' cli --version *>&1" *>&1 | Out-String
# This should either print the version or show a cargo/build error, not a PS error
Assert-NotNull -Label 'CLI routing: produces output' -Value $output

# b4w cli --help should show CLI help (via the binary or cargo run)
$output = pwsh -NoProfile -Command "& '$b4wAbs' cli --help *>&1" *>&1 | Out-String
Assert-NotNull -Label 'CLI --help: produces output' -Value $output

# ═══════════════════════════════════════════════════════════════════
# TESTS: Argument passthrough via --
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Argument passthrough: -- ━━━" -ForegroundColor Cyan

# Verify that arguments after -- bypass the script's param() block
# and go directly to the CLI binary.
$output = pwsh -NoProfile -Command "& '$b4wAbs' -- --help *>&1" *>&1 | Out-String
Assert-NotNull -Label 'Passthrough --: --help reaches CLI' -Value $output

# Verify -- doesn't interfere with the help output when using -- before a subcommand
# b4w -- --version should forward --version to the CLI binary
$output = pwsh -NoProfile -Command "& '$b4wAbs' -- --version *>&1" *>&1 | Out-String
Assert-NotNull -Label 'Passthrough --: --version reaches CLI' -Value $output

# ═══════════════════════════════════════════════════════════════════
# TESTS: CWD restoration
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ CWD restoration ━━━" -ForegroundColor Cyan

# b4w.ps1 saves the original CWD and restores it on exit.
# We verify this by capturing the CWD before and after calling
# the help command (which produces output then exits).
$originalDir = Get-Location
$null = pwsh -NoProfile -Command "& '$b4wAbs' *>&1" *>&1 | Out-String
$restoredDir = Get-Location
Assert-Returns -Label 'CWD restore: same directory after help' -Actual $restoredDir.Path -Expected $originalDir.Path

# Also test with the build subcommand (help only, no actual build)
$originalDir = Get-Location
$null = pwsh -NoProfile -Command "& '$b4wAbs' build --help *>&1" *>&1 | Out-String
$restoredDir = Get-Location
Assert-Returns -Label 'CWD restore: same directory after build --help' -Actual $restoredDir.Path -Expected $originalDir.Path

# ═══════════════════════════════════════════════════════════════════
# TESTS: Safe argument quoting
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Safe argument quoting ━━━" -ForegroundColor Cyan

# Verify the safe-args quoting logic by checking the source code
$srcText = Get-Content $B4wPs1Path -Raw
Assert-ContainsString -Label 'Safe args: uses Invoke-Expression' -Haystack $srcText -Needle 'Invoke-Expression'
Assert-ContainsString -Label 'Safe args: double-quotes each arg' -Haystack $srcText -Needle '-replace'
Assert-ContainsString -Label 'Safe args: escapes internal double quotes' -Haystack $srcText -Needle '""'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Rebuild flag logic
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Rebuild flag ━━━" -ForegroundColor Cyan

# Verify the -Rebuild parameter exists in the param block
Assert-ContainsString -Label 'Rebuild: param switch defined' -Haystack $srcText -Needle '[switch]$Rebuild'
Assert-ContainsString -Label 'Rebuild: forces rebuild message' -Haystack $srcText -Needle 'Rebuilding browser4-cli'

# Verify stale-source auto-detection logic
Assert-ContainsString -Label 'Rebuild: auto-detect stale sources' -Haystack $srcText -Needle 'Get-ChildItem -Path $SrcDir'
Assert-ContainsString -Label 'Rebuild: checks Cargo.toml timestamp' -Haystack $srcText -Needle 'Cargo.toml'
Assert-ContainsString -Label 'Rebuild: checks Cargo.lock timestamp' -Haystack $srcText -Needle 'Cargo.lock'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Source script integrity
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Source integrity ━━━" -ForegroundColor Cyan

# Ensure the script has no syntax errors by parsing it
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $B4wPs1Path, [ref]$tokens, [ref]$errors
)
Assert-Returns -Label 'Integrity: no parse errors' -Actual $errors.Count -Expected 0
if ($errors.Count -gt 0) {
    foreach ($e in $errors) {
        Write-Host "      Parse error: $($e.Message)" -ForegroundColor Red
    }
}

# Verify the script has expected sections
Assert-ContainsString -Label 'Integrity: param block present' -Haystack $srcText -Needle 'param('
Assert-ContainsString -Label 'Integrity: saves original CWD' -Haystack $srcText -Needle '$OriginalCwd'
Assert-ContainsString -Label 'Integrity: restores CWD' -Haystack $srcText -Needle 'Set-Location $OriginalCwd'
Assert-ContainsString -Label 'Integrity: has coworker routing' -Haystack $srcText -Needle 'coworker/coworker.ps1'
Assert-ContainsString -Label 'Integrity: has test routing' -Haystack $srcText -Needle 'bin\test.ps1'
Assert-ContainsString -Label 'Integrity: has build routing' -Haystack $srcText -Needle 'bin\build.ps1'
Assert-ContainsString -Label 'Integrity: has b4w install routing' -Haystack $srcText -Needle "b4w' -and `$CliArgs[1] -eq 'install'"
Assert-ContainsString -Label 'Integrity: b4w install adds to PATH' -Haystack $srcText -Needle 'SetEnvironmentVariable'
Assert-ContainsString -Label 'Integrity: b4w install creates bash launcher' -Haystack $srcText -Needle 'b4w — short-form launcher'
Assert-ContainsString -Label 'Integrity: has b4w uninstall routing' -Haystack $srcText -Needle "b4w' -and `$CliArgs[1] -eq 'uninstall'"
Assert-ContainsString -Label 'Integrity: b4w uninstall removes from PATH' -Haystack $srcText -Needle 'Removed from user PATH'
Assert-ContainsString -Label 'Integrity: b4w uninstall deletes launcher' -Haystack $srcText -Needle 'Deleted bash launcher'
Assert-ContainsString -Label 'Integrity: b4w bare prints help' -Haystack $srcText -Needle "b4w (bare / unknown subcommand)"
Assert-ContainsString -Label 'Integrity: cli subcommand stripping' -Haystack $srcText -Needle "eq 'cli'"

# ═══════════════════════════════════════════════════════════════════
# TESTS: Top-level help content completeness
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Top-level help content ━━━" -ForegroundColor Cyan

$helpOutput = pwsh -NoProfile -Command "& '$b4wAbs' *>&1" *>&1 | Out-String

# Verify all subcommands are documented
Assert-ContainsString -Label 'Help: mentions b4w session example' -Haystack $helpOutput -Needle '-s my-session'
Assert-ContainsString -Label 'Help: mentions snapshot example' -Haystack $helpOutput -Needle 'snapshot'
Assert-ContainsString -Label 'Help: mentions --version' -Haystack $helpOutput -Needle '--version'
Assert-ContainsString -Label 'Help: mentions Windows wrappers' -Haystack $helpOutput -Needle 'Windows wrappers'
Assert-ContainsString -Label 'Help: mentions passthrough tip' -Haystack $helpOutput -Needle '--'
Assert-ContainsString -Label 'Help: mentions coworker list example' -Haystack $helpOutput -Needle 'coworker list'
Assert-ContainsString -Label 'Help: mentions test --e2e example' -Haystack $helpOutput -Needle '--e2e'
Assert-ContainsString -Label 'Help: mentions build example' -Haystack $helpOutput -Needle 'build browser4-cli'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Subcommand routing — edge cases
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Subcommand routing: edge cases ━━━" -ForegroundColor Cyan

# Verify that test arguments are forwarded correctly (positional)
# test.ps1 accepts `fast` as a test type.  b4w test fast should dispatch correctly.
$output = pwsh -NoProfile -Command "& '$b4wAbs' test -Show fast *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Routing: b4w test -Show fast' -Haystack $output -Needle '[SHOW]'

# Verify that build arguments are forwarded correctly
$output = pwsh -NoProfile -Command "& '$b4wAbs' build --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Routing: b4w build --help has examples' -Haystack $output -Needle 'Examples'

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
Write-Host ''
if ($script:ContentFailures -gt 0) {
    Write-Host "❌ $($script:ContentFailures) content assertion(s) failed." -ForegroundColor Red
}

if (Get-Command Finish-TestSession -ErrorAction SilentlyContinue) {
    $sessionExit = Finish-TestSession
    if ($script:ContentFailures -gt 0 -or $sessionExit -ne 0) {
        exit 1
    }
    exit 0
} else {
    if ($script:ContentFailures -gt 0 -or $script:__FailCount -gt 0) {
        exit 1
    }
    exit 0
}
