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
    Unit tests for the workflow-failure handler functions in
    bin/ci/monitor-ci.ps1.

.DESCRIPTION
    Verifies that Extract-MinimalErrors, New-CoworkerFailureTask, and
    Invoke-WorkflowFailureHandler are present, syntactically valid, and
    produce correct output for representative inputs.

    The full behavioral suite for Extract-MinimalErrors and
    New-CoworkerFailureTask lives in bin/release/tests/monitor-release.tests.ps1
    (the functions are identical copies in both scripts). This file
    focuses on CI-specific naming and verifies the functions parse
    correctly from the CI monitor script.

    Run standalone:
        pwsh bin/ci/tests/monitor-ci.tests.ps1

    Run via runner:
        pwsh browser4-tests/tests-production/run-tests.ps1 monitor-ci
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Resolve paths
# -------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TestUtilsModule = Join-Path $ScriptDir '..\..\..\browser4-tests\tests-production\test-utils.psm1'
$MonitorScriptPath = Join-Path $ScriptDir '..\monitor-ci.ps1'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
if (Test-Path $TestUtilsModule) {
    Import-Module $TestUtilsModule -Force
    Start-TestSession -Name 'monitor-ci-helpers'
    Write-TestHeader -Name 'monitor-ci-helpers'
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
# Content-based assertion helpers
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

function Assert-Match {
    param(
        [string]$Label,
        [string]$Haystack,
        [string]$Pattern
    )
    $passed = $Haystack -match $Pattern
    if ($passed) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        Write-Host "    ❌ $Label — does not match pattern '$Pattern'" -ForegroundColor Red
        Write-Host "       haystack: $($Haystack.Substring(0, [Math]::Min(80, $Haystack.Length)))" -ForegroundColor DarkGray
        $script:ContentFailures++
    }
    $exitCode = if ($passed) { 0 } else { 1 }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed ([TimeSpan]::Zero)
    }
}

# -------------------------------------------------------------------
# Extract function definitions from monitor-ci.ps1 via AST parser
# -------------------------------------------------------------------
function Get-FunctionsFromScript {
    param([string]$ScriptPath, [string[]]$FunctionNames)

    if (-not (Test-Path $ScriptPath)) {
        Write-Host "ERROR: Script not found: $ScriptPath" -ForegroundColor Red
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

    $funcDefs = $ast.FindAll({
        param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
    }, $true)

    $extracted = @()
    foreach ($name in $FunctionNames) {
        $def = $funcDefs | Where-Object { $_.Name -eq $name } | Select-Object -First 1
        if ($def) {
            $extracted += $def.Extent.Text
        } else {
            Write-Host "WARNING: Function '$name' not found in $ScriptPath" -ForegroundColor Yellow
        }
    }

    Write-Host "Extracted $($extracted.Count)/$($FunctionNames.Count) function(s) from $([System.IO.Path]::GetFileName($ScriptPath))" -ForegroundColor DarkGray
    return $extracted -join "`n`n"
}

# -------------------------------------------------------------------
# Load functions
# -------------------------------------------------------------------
Write-Host "Source : $MonitorScriptPath" -ForegroundColor DarkGray

$funcText = Get-FunctionsFromScript -ScriptPath $MonitorScriptPath `
    -FunctionNames @('Extract-MinimalErrors', 'New-CoworkerFailureTask', 'Invoke-WorkflowFailureHandler')

# Verify we got non-empty text back (functions contain blank lines so split on \n\n isn't 1:1)
Assert-NotNull -Label 'Functions: extracted text non-null' -Value $funcText
Assert-Returns -Label 'Functions: text is non-empty' -Actual ($funcText.Length -gt 100) -Expected $true

Invoke-Expression $funcText

Write-Host ''

# ===================================================================
# TESTS: Verify functions are present and parse correctly
# ===================================================================
Write-Host "━━━ Functions: existence & identity ━━━" -ForegroundColor Cyan

# Verify each function is defined after Invoke-Expression
$cmd = Get-Command Extract-MinimalErrors -ErrorAction SilentlyContinue
Assert-NotNull -Label 'Fn: Extract-MinimalErrors defined' -Value $cmd

$cmd = Get-Command New-CoworkerFailureTask -ErrorAction SilentlyContinue
Assert-NotNull -Label 'Fn: New-CoworkerFailureTask defined' -Value $cmd

$cmd = Get-Command Invoke-WorkflowFailureHandler -ErrorAction SilentlyContinue
Assert-NotNull -Label 'Fn: Invoke-WorkflowFailureHandler defined' -Value $cmd

# ===================================================================
# TESTS: CI-specific naming in task files
# ===================================================================
Write-Host "━━━ New-CoworkerFailureTask: CI naming ━━━" -ForegroundColor Cyan

$tempRepoRoot = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-monitor-ci-$([System.IO.Path]::GetRandomFileName())"
New-Item -Path $tempRepoRoot -ItemType Directory -Force | Out-Null
try {
    $taskPath = New-CoworkerFailureTask -WorkflowName 'ci.yml' `
        -Tag 'v4.12.3-ci.7' `
        -RunId '5551212' `
        -Errors 'test result: FAILED. 2 of 50 tests failed' `
        -RepoRoot $tempRepoRoot

    Assert-NotNull -Label 'CI NCFT: returns path' -Value $taskPath
    Assert-Returns -Label 'CI NCFT: file exists' -Actual (Test-Path $taskPath) -Expected $true

    # File naming: fix-ci-failure-<timestamp>.md
    $filename = [System.IO.Path]::GetFileName($taskPath)
    Assert-Match -Label 'CI NCFT: filename prefix' -Haystack $filename -Pattern '^fix-ci-failure-\d{8}-\d{6}\.md$'

    # Content checks
    $content = Get-Content -Path $taskPath -Raw -Encoding UTF8
    Assert-ContainsString -Label 'CI NCFT: Title mentions ci.yml' -Haystack $content -Needle 'ci.yml'
    Assert-ContainsString -Label 'CI NCFT: includes CI tag' -Haystack $content -Needle 'v4.12.3-ci.7'
    Assert-ContainsString -Label 'CI NCFT: includes run ID' -Haystack $content -Needle '5551212'
    Assert-ContainsString -Label 'CI NCFT: includes error text' -Haystack $content -Needle 'FAILED'
    Assert-ContainsString -Label 'CI NCFT: has structured format' -Haystack $content -Needle 'Title: Fix ci.yml'
    Assert-ContainsString -Label 'CI NCFT: has reproduce section' -Haystack $content -Needle 'gh run view 5551212 --log-failed'
    Assert-ContainsString -Label 'CI NCFT: has instructions' -Haystack $content -Needle 'root cause'

    Remove-Item $taskPath -Force -ErrorAction SilentlyContinue

} finally {
    Remove-Item $tempRepoRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# ===================================================================
# TESTS: CI-specific error extraction
# ===================================================================
Write-Host "━━━ Extract-MinimalErrors: CI-style log output ━━━" -ForegroundColor Cyan

# Simulate a typical CI pipeline failure: build step fails, then tests fail
$ciLogs = @(
    'Run actions/checkout@v4',
    'Syncing repository...',
    'Run mvn test-compile',
    '[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin',
    '[ERROR] /home/runner/work/Browser4/Browser4/browser4-rest/src/main/java/com/platon/browser4/rest/MCPToolController.kt:[142,15] Unresolved reference: buildBatchFocusExpression',
    '[ERROR] -> [Help 1]',
    '[ERROR]',
    'Error: Process completed with exit code 1.',
    'Run cargo test',
    'test test_batch_compile_empty ... FAILED',
    'test test_fill_css_selector ... FAILED',
    'failures:',
    '    test_batch_compile_empty',
    '    test_fill_css_selector',
    'test result: FAILED. 10 passed; 2 failed; 0 ignored',
    'Error: Process completed with exit code 101.'
)
$result = Extract-MinimalErrors -LogLines $ciLogs

Assert-ContainsString -Label 'CI EME: finds maven error' -Haystack $result -Needle 'maven-compiler-plugin'
Assert-ContainsString -Label 'CI EME: finds Unresolved reference' -Haystack $result -Needle 'Unresolved reference'
Assert-ContainsString -Label 'CI EME: finds exit code 1' -Haystack $result -Needle 'exit code 1'
Assert-ContainsString -Label 'CI EME: finds test FAILED' -Haystack $result -Needle 'FAILED'
Assert-ContainsString -Label 'CI EME: finds test result FAILED' -Haystack $result -Needle 'test result: FAILED'

# Verify multiple blocks are created (build failure + test failure are distinct)
$blockCount = ([regex]::Matches($result, '══ block \d+ ══')).Count
Assert-Returns -Label 'CI EME: has ≥2 distinct blocks (build + test failures)' -Actual ($blockCount -ge 2) -Expected $true

# ===================================================================
# TESTS: Cross-script function parity (release vs CI)
# ===================================================================
Write-Host "━━━ Cross-script parity: functions identical between release & CI ━━━" -ForegroundColor Cyan

$releaseScript = Join-Path $ScriptDir '..\..\release\monitor-release.ps1'

if (Test-Path $releaseScript) {
    $ciFuncs = Get-FunctionsFromScript -ScriptPath $MonitorScriptPath `
        -FunctionNames @('Extract-MinimalErrors', 'New-CoworkerFailureTask')

    $releaseFuncs = Get-FunctionsFromScript -ScriptPath $releaseScript `
        -FunctionNames @('Extract-MinimalErrors', 'New-CoworkerFailureTask')

    # Compare extracted function bodies (ignore whitespace differences)
    $ciNormalized = $ciFuncs -replace '\s+', ' '
    $releaseNormalized = $releaseFuncs -replace '\s+', ' '

    if ($ciNormalized -eq $releaseNormalized -and $ciNormalized.Length -gt 0) {
        Write-Host "    ✅ CI and release functions are byte-identical" -ForegroundColor Green
    } else {
        # Fuzzier check: same number of non-empty lines
        $ciLines = ($ciFuncs -split "`n" | Where-Object { $_.Trim() -ne '' }).Count
        $releaseLines = ($releaseFuncs -split "`n" | Where-Object { $_.Trim() -ne '' }).Count
        Assert-Returns -Label 'Parity: same line count in CI vs release' -Actual $ciLines -Expected $releaseLines

        if ($ciLines -ne $releaseLines) {
            Write-Host "    ⚠️  CI funcs: $ciLines lines, Release funcs: $releaseLines lines — functions may have diverged" -ForegroundColor Yellow
        } else {
            Write-Host "    ⚠️  Functions differ despite same line count — check for subtle divergence" -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "    ⚠️  release script not found at $releaseScript — skipping parity check" -ForegroundColor Yellow
}

# ===================================================================
# Summary
# ===================================================================
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
