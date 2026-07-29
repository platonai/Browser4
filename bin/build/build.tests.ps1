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
    Unit tests for helper functions and argument parsing in bin/build/build.ps1.

.DESCRIPTION
    Extracts pure helper functions from build.ps1 via PowerShell's AST parser
    and tests them in isolation — no Maven execution, no Cargo, no network.

    Covers: Write-TrackedFile, Read-TrackedFile, Write-SystemInfo, Write-Help,
            argument parsing, build-state directory management.

    Run standalone:
        pwsh bin/build/build.tests.ps1

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
$BuildPs1Path = Join-Path $ScriptDir 'build.ps1'

# -------------------------------------------------------------------
# Load shared test utilities (soft dependency)
# -------------------------------------------------------------------
if (Test-Path $TestUtilsModule) {
    Import-Module $TestUtilsModule -Force
    Start-TestSession -Name 'build.ps1-helpers' -SkipPortCleanup
    Write-TestHeader -Name 'build.ps1-helpers'
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
# Custom assertion helpers
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

function Assert-True {
    param(
        [string]$Label,
        [bool]$Condition,
        [string]$Detail = ''
    )
    $passed = $Condition
    if ($passed) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        Write-Host "    ❌ $Label — condition was false. $Detail" -ForegroundColor Red
        $script:ContentFailures++
    }
    $exitCode = if ($passed) { 0 } else { 1 }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed ([TimeSpan]::Zero)
    }
}

# ═══════════════════════════════════════════════════════════════════
# Helper: extract function definitions from build.ps1 via AST parser
# ═══════════════════════════════════════════════════════════════════
function Get-FunctionDefinitionsFromScript {
    param([string]$ScriptPath)

    if (-not (Test-Path $ScriptPath)) {
        Write-Host "ERROR: build.ps1 not found at: $ScriptPath" -ForegroundColor Red
        throw "Script not found: $ScriptPath"
    }

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $ScriptPath, [ref]$tokens, [ref]$errors
    )

    if ($errors.Count -gt 0) {
        Write-Host "ERROR: Parse errors in $ScriptPath" -ForegroundColor Red
        foreach ($e in $errors) {
            Write-Host "  $($e.Message)" -ForegroundColor Red
        }
        throw "Failed to parse $ScriptPath"
    }

    $functionDefs = $ast.FindAll({
        param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
    }, $true)

    if ($functionDefs.Count -eq 0) {
        Write-Host "WARNING: No function definitions found in $ScriptPath" -ForegroundColor Yellow
    }

    Write-Host "Extracted $($functionDefs.Count) function definitions from build.ps1" -ForegroundColor DarkGray

    return ($functionDefs | ForEach-Object { $_.Extent.Text }) -join "`n`n"
}

# -------------------------------------------------------------------
# Verify the source script exists
# -------------------------------------------------------------------
if (-not (Test-Path $BuildPs1Path)) {
    Write-Error "build.ps1 not found at: $BuildPs1Path"
    exit 1
}

Write-Host "Source : $BuildPs1Path" -ForegroundColor DarkGray

# Extract and evaluate all function definitions from build.ps1
$functionText = Get-FunctionDefinitionsFromScript -ScriptPath $BuildPs1Path
Invoke-Expression $functionText

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Write-TrackedFile / Read-TrackedFile
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Write-TrackedFile / Read-TrackedFile ━━━" -ForegroundColor Cyan

$tempStateDir = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-buildstate-$([System.IO.Path]::GetRandomFileName())"
try {
    # Override script-scoped $BuildStateDir for tests
    $script:BuildStateDir = $tempStateDir

    # Write a tracked file
    $testPath = Join-Path $tempStateDir 'test.txt'
    Write-TrackedFile -Path $testPath -Content 'hello world'
    Assert-True -Label 'Write-TrackedFile: creates file' -Condition (Test-Path $testPath)
    Assert-Returns -Label 'Write-TrackedFile: writes correct content' `
        -Actual (Read-TrackedFile -Path $testPath) -Expected 'hello world'

    # Write another tracked file with multi-line content
    $testPath2 = Join-Path $tempStateDir 'other.txt'
    Write-TrackedFile -Path $testPath2 -Content "first-part`nsecond-part"
    $content = Read-TrackedFile -Path $testPath2
    Assert-ContainsString -Label 'Write-TrackedFile: multi-line preserves first part' -Haystack $content -Needle 'first-part'
    Assert-ContainsString -Label 'Write-TrackedFile: multi-line preserves second part' -Haystack $content -Needle 'second-part'

    # Read non-existent file returns null
    $missingPath = Join-Path $tempStateDir 'nonexistent.txt'
    Assert-Returns -Label 'Read-TrackedFile: missing file → null' `
        -Actual (Read-TrackedFile -Path $missingPath) -Expected $null

    # Overwrite existing file
    Write-TrackedFile -Path $testPath -Content 'updated'
    Assert-Returns -Label 'Write-TrackedFile: overwrite updates content' `
        -Actual (Read-TrackedFile -Path $testPath) -Expected 'updated'

    # Empty content
    Write-TrackedFile -Path $testPath -Content ''
    Assert-Returns -Label 'Write-TrackedFile: empty content' `
        -Actual (Read-TrackedFile -Path $testPath) -Expected ''

} finally {
    Remove-Item $tempStateDir -Recurse -Force -ErrorAction SilentlyContinue
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: Write-SystemInfo
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Write-SystemInfo ━━━" -ForegroundColor Cyan

$sysOutput = Write-SystemInfo *>&1 | Out-String
Assert-NotNull -Label 'SystemInfo: produces output' -Value $sysOutput
Assert-ContainsString -Label 'SystemInfo: has OS section' -Haystack $sysOutput -Needle '[OS]'
Assert-ContainsString -Label 'SystemInfo: has CPU section' -Haystack $sysOutput -Needle '[CPU]'
Assert-ContainsString -Label 'SystemInfo: has Memory section' -Haystack $sysOutput -Needle '[Memory]'
Assert-ContainsString -Label 'SystemInfo: has Disk section' -Haystack $sysOutput -Needle '[Disk]'
Assert-ContainsString -Label 'SystemInfo: has Git section' -Haystack $sysOutput -Needle '[Git]'
Assert-ContainsString -Label 'SystemInfo: has Java section' -Haystack $sysOutput -Needle '[Java]'
Assert-ContainsString -Label 'SystemInfo: has Maven section' -Haystack $sysOutput -Needle '[Maven]'
Assert-ContainsString -Label 'SystemInfo: has PowerShell section' -Haystack $sysOutput -Needle '[PowerShell]'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Write-Help
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Write-Help ━━━" -ForegroundColor Cyan

$helpOutput = Write-Help *>&1 | Out-String

Assert-ContainsString -Label 'Help: mentions Build script' -Haystack $helpOutput -Needle 'Build script for Browser4'
Assert-ContainsString -Label 'Help: has -main flag' -Haystack $helpOutput -Needle '-main'
Assert-ContainsString -Label 'Help: has --all-test-modules' -Haystack $helpOutput -Needle '--all-test-modules'
Assert-ContainsString -Label 'Help: has --all-modules' -Haystack $helpOutput -Needle '--all-modules'
Assert-ContainsString -Label 'Help: has -test flag' -Haystack $helpOutput -Needle '-test'
Assert-ContainsString -Label 'Help: has -pl flag' -Haystack $helpOutput -Needle '-pl'
Assert-ContainsString -Label 'Help: has --resume' -Haystack $helpOutput -Needle '--resume'
Assert-ContainsString -Label 'Help: has --cli' -Haystack $helpOutput -Needle '--cli'
Assert-ContainsString -Label 'Help: has --extension' -Haystack $helpOutput -Needle '--extension'
Assert-ContainsString -Label 'Help: has -clean' -Haystack $helpOutput -Needle '-clean'
Assert-ContainsString -Label 'Help: has --debug' -Haystack $helpOutput -Needle '--debug'
Assert-ContainsString -Label 'Help: has -ac flag' -Haystack $helpOutput -Needle '-ac'
Assert-ContainsString -Label 'Help: has -ae flag' -Haystack $helpOutput -Needle '-ae'
Assert-ContainsString -Label 'Help: has -h flag' -Haystack $helpOutput -Needle '-h'
Assert-ContainsString -Label 'Help: has Example section' -Haystack $helpOutput -Needle 'Examples'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Argument parsing (via child process)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Argument parsing: via child process ━━━" -ForegroundColor Cyan

$buildAbs = (Resolve-Path $BuildPs1Path).Path

# --help shows help and exits 0
$output = pwsh -NoProfile -Command "& '$buildAbs' --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg --help: shows help' -Haystack $output -Needle 'Build script for Browser4'

# -h shows help
$output = pwsh -NoProfile -Command "& '$buildAbs' -h *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg -h: shows help' -Haystack $output -Needle 'Build script for Browser4'

# Unknown flag should error
$output = pwsh -NoProfile -Command "& '$buildAbs' --nonexistent-flag-xyz *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: unknown flag errors' -Haystack $output -Needle 'Unknown flag:'
Assert-ContainsString -Label 'Arg: unknown flag name in error' -Haystack $output -Needle '--nonexistent-flag-xyz'

# Unknown short flag should error
$output = pwsh -NoProfile -Command "& '$buildAbs' -Z *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: unknown short flag errors' -Haystack $output -Needle 'Unknown flag:'

# -pl without a value should error
$output = pwsh -NoProfile -Command "& '$buildAbs' -pl *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: -pl without value errors' -Haystack $output -Needle 'requires a value'

# --projects without a value should error
$output = pwsh -NoProfile -Command "& '$buildAbs' --projects *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: --projects without value errors' -Haystack $output -Needle 'requires a value'

# No arguments: shows system info + help and exits
$output = pwsh -NoProfile -Command "& '$buildAbs' *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg no args: shows system info' -Haystack $output -Needle 'SYSTEM & BUILD ENVIRONMENT'
Assert-ContainsString -Label 'Arg no args: shows help' -Haystack $output -Needle 'Build script for Browser4'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Argument parsing — valid flag combinations
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Argument parsing: valid flags ━━━" -ForegroundColor Cyan

# -main flag should be accepted (even if the build ultimately fails)
$output = pwsh -NoProfile -Command "& '$buildAbs' -main --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: -main accepted' -Haystack $output -Needle 'Build script for Browser4'

# -test flag
$output = pwsh -NoProfile -Command "& '$buildAbs' -test --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: -test accepted' -Haystack $output -Needle 'Build script for Browser4'

# -clean flag
$output = pwsh -NoProfile -Command "& '$buildAbs' -clean --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: -clean accepted' -Haystack $output -Needle 'Build script for Browser4'

# -X (debug) flag
$output = pwsh -NoProfile -Command "& '$buildAbs' -X --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: -X accepted' -Haystack $output -Needle 'Build script for Browser4'

# --cli flag
$output = pwsh -NoProfile -Command "& '$buildAbs' --cli --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: --cli accepted' -Haystack $output -Needle 'Build script for Browser4'

# --all-modules flag
$output = pwsh -NoProfile -Command "& '$buildAbs' --all-modules --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: --all-modules accepted' -Haystack $output -Needle 'Build script for Browser4'

# --resume flag
$output = pwsh -NoProfile -Command "& '$buildAbs' --resume --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Arg: --resume accepted' -Haystack $output -Needle 'Build script for Browser4'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Build state directory tracking
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Build state tracking ━━━" -ForegroundColor Cyan

$srcText = Get-Content $BuildPs1Path -Raw

# Verify build state file paths are defined
Assert-ContainsString -Label 'State: defines BuildStateDir' -Haystack $srcText -Needle '$BuildStateDir'
Assert-ContainsString -Label 'State: defines BuildLogFile' -Haystack $srcText -Needle '$BuildLogFile'
Assert-ContainsString -Label 'State: defines LastFailedModuleFile' -Haystack $srcText -Needle '$LastFailedModuleFile'
Assert-ContainsString -Label 'State: defines BuildEnvFile' -Haystack $srcText -Needle '$BuildEnvFile'
Assert-ContainsString -Label 'State: defines BuildErrorFile' -Haystack $srcText -Needle '$BuildErrorFile'
Assert-ContainsString -Label 'State: defines BuildStatusFile' -Haystack $srcText -Needle '$BuildStatusFile'

# Verify build state directory creation
Assert-ContainsString -Label 'State: creates build-state dir' -Haystack $srcText -Needle '.build-state'

# Verify resume module tracking
Assert-ContainsString -Label 'State: writes last-failed-module' -Haystack $srcText -Needle 'LastFailedModuleFile'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Cross-platform detection
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Cross-platform detection ━━━" -ForegroundColor Cyan

# Verify PS 5.1 compatibility pattern
Assert-ContainsString -Label 'Platform: defines $IsWin' -Haystack $srcText -Needle '$IsWin = ($IsWindows -or $env:OS'
Assert-ContainsString -Label 'Platform: uses $IsWin for mvnw selection' -Haystack $srcText -Needle '$IsWin'
Assert-ContainsString -Label 'Platform: has OS detection in Write-SystemInfo' -Haystack $srcText -Needle '$osName'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Source script integrity
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Source integrity ━━━" -ForegroundColor Cyan

$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $BuildPs1Path, [ref]$tokens, [ref]$errors
)
Assert-Returns -Label 'Integrity: no parse errors' -Actual $errors.Count -Expected 0
if ($errors.Count -gt 0) {
    foreach ($e in $errors) {
        Write-Host "      Parse error: $($e.Message)" -ForegroundColor Red
    }
}

# Verify expected functions are defined
$funcNames = $ast.FindAll({
    param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
}, $true) | ForEach-Object { $_.Name }

$expectedFunctions = @(
    'Write-TrackedFile', 'Read-TrackedFile',
    'Write-SystemInfo', 'Write-RustInfo', 'Write-NodeInfo',
    'Write-Help',
    'Invoke-MavenBuild', 'Invoke-CargoBuild', 'Invoke-ExtensionBuild',
    'Invoke-BugReportAgent'
)

foreach ($fn in $expectedFunctions) {
    $found = $fn -in $funcNames
    Assert-True -Label "Integrity: function '$fn' defined" -Condition $found -Detail "Missing function: $fn"
}

# Verify parameter binding names
Assert-ContainsString -Label 'Integrity: param() or args loop' -Haystack $srcText -Needle '$args'
Assert-ContainsString -Label 'Integrity: switch -Wildcard parsing' -Haystack $srcText -Needle 'switch -Wildcard'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Invoke-MavenBuild stale target cleanup
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Stale target cleanup ━━━" -ForegroundColor Cyan

Assert-ContainsString -Label 'Cleanup: removes browser4-bundle target' -Haystack $srcText -Needle 'browser4-bundle'
Assert-ContainsString -Label 'Cleanup: removes browser4-standalone target' -Haystack $srcText -Needle 'browser4-standalone'
Assert-ContainsString -Label 'Cleanup: uses Remove-Item -Recurse' -Haystack $srcText -Needle 'Remove-Item -Recurse -Force'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Bug-report agent integration
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Bug-report agent ━━━" -ForegroundColor Cyan

Assert-ContainsString -Label 'Bug: Invoke-BugReportAgent defined' -Haystack $srcText -Needle 'Invoke-BugReportAgent'
# The Invoke-BugReportAgent writes bug reports to coworker/tasks/issues/draft/
Assert-ContainsString -Label 'Bug: writes to coworker tasks dir' -Haystack $srcText -Needle 'coworker'
Assert-ContainsString -Label 'Bug: writes to issues/draft subdir' -Haystack $srcText -Needle 'draft'
Assert-ContainsString -Label 'Bug: has fallback template' -Haystack $srcText -Needle 'Fallback template'
Assert-ContainsString -Label 'Bug: called on build failure' -Haystack $srcText -Needle 'Invoke-BugReportAgent -BuildSystem'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Argument parsing — combinations that should work
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Flag combinations ━━━" -ForegroundColor Cyan

# Multiple flags together
$output = pwsh -NoProfile -Command "& '$buildAbs' -main -test -clean --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Flags combo: -main -test -clean' -Haystack $output -Needle 'Build script for Browser4'

# -ac (also-cli)
$output = pwsh -NoProfile -Command "& '$buildAbs' -ac --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Flags: -ac accepted' -Haystack $output -Needle 'Build script for Browser4'

# -ae (also-extension)
$output = pwsh -NoProfile -Command "& '$buildAbs' -ae --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Flags: -ae accepted' -Haystack $output -Needle 'Build script for Browser4'

# Long-forms with double-dash
$output = pwsh -NoProfile -Command "& '$buildAbs' --also-cli --also-extension --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Flags: --also-cli --also-extension' -Haystack $output -Needle 'Build script for Browser4'

# -pl with value
$output = pwsh -NoProfile -Command "& '$buildAbs' -pl browser4-core --help *>&1" *>&1 | Out-String
Assert-ContainsString -Label 'Flags: -pl with value' -Haystack $output -Needle 'Build script for Browser4'

# ═══════════════════════════════════════════════════════════════════
# TESTS: Error handling patterns in source
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Error handling ━━━" -ForegroundColor Cyan

Assert-ContainsString -Label 'Error: sets $ErrorActionPreference' -Haystack $srcText -Needle '$ErrorActionPreference = "Stop"'
Assert-ContainsString -Label 'Error: try/catch around build' -Haystack $srcText -Needle 'try {'
Assert-ContainsString -Label 'Error: catch writes BuildStatusFile' -Haystack $srcText -Needle 'FAILED time='
Assert-ContainsString -Label 'Error: catch writes BuildErrorFile' -Haystack $srcText -Needle 'BuildErrorFile'
Assert-ContainsString -Label 'Error: references --resume in failure message' -Haystack $srcText -Needle '--resume to continue'

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
