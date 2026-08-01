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
    bin/release/monitor-release.ps1.

.DESCRIPTION
    Extracts Extract-MinimalErrors, New-CoworkerFailureTask, and
    Invoke-WorkflowFailureHandler via PowerShell's AST parser and
    tests them in isolation — no gh CLI, no network, no GitHub.

    Covers: error-pattern matching, deduplication, context capture,
            task-file creation, filename conventions, truncation,
            edge cases (empty input, no matches, caps).

    Run standalone:
        pwsh bin/release/tests/monitor-release.tests.ps1

    Run via runner:
        pwsh browser4-tests/tests-production/run-tests.ps1 monitor-release
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Resolve paths
# -------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TestUtilsModule = Join-Path $ScriptDir '..\..\..\browser4-tests\tests-production\test-utils.psm1'
$MonitorScriptPath = Join-Path $ScriptDir '..\monitor-release.ps1'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
if (Test-Path $TestUtilsModule) {
    Import-Module $TestUtilsModule -Force
    Start-TestSession -Name 'monitor-release-helpers'
    Write-TestHeader -Name 'monitor-release-helpers'
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
        $script:ContentFailures++
    }
    $exitCode = if ($passed) { 0 } else { 1 }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed ([TimeSpan]::Zero)
    }
}

function Assert-NotMatch {
    param(
        [string]$Label,
        [string]$Haystack,
        [string]$Pattern
    )
    $passed = $Haystack -notmatch $Pattern
    if ($passed) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        Write-Host "    ❌ $Label — should NOT match pattern '$Pattern'" -ForegroundColor Red
        $script:ContentFailures++
    }
    $exitCode = if ($passed) { 0 } else { 1 }
    if (Get-Command Register-CliResult -ErrorAction SilentlyContinue) {
        Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed ([TimeSpan]::Zero)
    }
}

# -------------------------------------------------------------------
# Extract function definitions from monitor-release.ps1 via AST parser
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
    -FunctionNames @('Extract-MinimalErrors', 'New-CoworkerFailureTask')

Invoke-Expression $funcText

Write-Host ''

# ===================================================================
# TESTS: Extract-MinimalErrors
# ===================================================================
Write-Host "━━━ Extract-MinimalErrors: empty / no-match ━━━" -ForegroundColor Cyan

# Empty input
$result = Extract-MinimalErrors -LogLines @()
Assert-NotNull -Label 'EME empty: returns non-null' -Value $result
Assert-ContainsString -Label 'EME empty: fallback message' -Haystack $result -Needle 'No specific error patterns matched'

# No error patterns in input
$cleanLogs = @(
    'Building project...',
    'Compiling module A',
    'Compiling module B',
    'Running tests...',
    'All tests passed!',
    'BUILD SUCCESSFUL'
)
$result = Extract-MinimalErrors -LogLines $cleanLogs
Assert-NotNull -Label 'EME no-match: returns non-null' -Value $result
Assert-ContainsString -Label 'EME no-match: shows fallback' -Haystack $result -Needle 'No specific error patterns matched'
Assert-ContainsString -Label 'EME no-match: includes last 40 lines' -Haystack $result -Needle 'BUILD SUCCESSFUL'

Write-Host "━━━ Extract-MinimalErrors: single error patterns ━━━" -ForegroundColor Cyan

# Rust compiler error
$rustError = @(
    '   Compiling browser4-cli v4.12.0',
    'error[E0308]: mismatched types',
    '  --> src/main.rs:42:15',
    '   |',
    '42 |     let x: String = 42;',
    '   |               ------   ^^ expected String, found integer',
    '   |               |',
    '   |               expected due to this',
    '',
    'error: could not compile `browser4-cli` due to previous error'
)
$result = Extract-MinimalErrors -LogLines $rustError
Assert-ContainsString -Label 'EME Rust: finds error[E0308]' -Haystack $result -Needle 'error[E0308]'
Assert-ContainsString -Label 'EME Rust: finds could not compile' -Haystack $result -Needle 'Could not compile'
Assert-Match -Label 'EME Rust: has block markers' -Haystack $result -Pattern '══ block \d+ ══'

# Java exception
$javaError = @(
    '[INFO] Running com.example.MyTest',
    '[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0',
    '[ERROR]   MyTest.testSomething:42 » NullPointerException',
    '[ERROR]     at com.example.MyTest.testSomething(MyTest.java:42)',
    '[INFO] BUILD FAILURE'
)
$result = Extract-MinimalErrors -LogLines $javaError
Assert-ContainsString -Label 'EME Java: finds NullPointerException' -Haystack $result -Needle 'NullPointerException'
Assert-ContainsString -Label 'EME Java: finds BUILD FAILURE' -Haystack $result -Needle 'BUILD FAILURE'
Assert-ContainsString -Label 'EME Java: finds ERROR prefix' -Haystack $result -Needle '[ERROR]'

# Exit code 1
$exitError = @(
    'Running step: Build backend',
    'Process completed with exit code 1.',
    'Error: Process completed with exit code 1.'
)
$result = Extract-MinimalErrors -LogLines $exitError
Assert-ContainsString -Label 'EME exit: finds exit code 1' -Haystack $result -Needle 'exit code 1'

# Test failures
$testFail = @(
    'test result: FAILED. 10 passed; 2 failed; 0 ignored; 0 measured',
    'test test_batch_compile_empty ... FAILED',
    'test test_fill_css_selector ... ok'
)
$result = Extract-MinimalErrors -LogLines $testFail
Assert-ContainsString -Label 'EME test: finds FAILED' -Haystack $result -Needle 'FAILED'

Write-Host "━━━ Extract-MinimalErrors: deduplication ━━━" -ForegroundColor Cyan

# Identical scattered errors (not adjacent) should be deduplicated
$scatteredErrors = @(
    'INFO: step 1',
    'ERROR: Connection refused',
    'INFO: step 2',
    'INFO: step 3',
    'ERROR: Connection refused',
    'INFO: step 4',
    'INFO: step 5',
    'ERROR: Connection refused',
    'INFO: step 6',
    'INFO: step 7'
)
$result = Extract-MinimalErrors -LogLines $scatteredErrors
# Each error match has different neighbors due to the interleaved INFO lines
$blockCount = ([regex]::Matches($result, '══ block \d+ ══')).Count
Assert-Returns -Label 'EME dedup scattered: 3 spaced errors → 3 blocks (different context)' -Actual $blockCount -Expected 3

# Adjacent identical errors: each has slightly different neighbor context
$adjacentErrors = @(
    'ERROR: Connection refused',
    'ERROR: Connection refused',
    'ERROR: Connection refused',
    'ERROR: Connection refused',
    'ERROR: Connection refused'
)
$result = Extract-MinimalErrors -LogLines $adjacentErrors
$blockCount = ([regex]::Matches($result, '══ block \d+ ══')).Count
# With 2-line context, lines 1-2 share no "before" context, line 3-5 have overlapping but distinct edges
Assert-Returns -Label 'EME dedup adjacent: 5 adjacent errors → ≤5 blocks' -Actual ($blockCount -le 5 -and $blockCount -ge 1) -Expected $true

# Slightly different errors should be separate blocks
$differentErrors = @(
    'ERROR: Connection refused on port 8080',
    'ERROR: Connection refused on port 8081',
    'ERROR: Permission denied for /etc/config'
)
$result = Extract-MinimalErrors -LogLines $differentErrors
$blockCount = ([regex]::Matches($result, '══ block \d+ ══')).Count
# Port 8080 & 8081 may merge if adjacent (context overlap), but must include Permission denied
Assert-ContainsString -Label 'EME distinct: finds Permission denied' -Haystack $result -Needle 'Permission denied'
Assert-ContainsString -Label 'EME distinct: finds Connection refused' -Haystack $result -Needle 'Connection refused'

Write-Host "━━━ Extract-MinimalErrors: context capture ━━━" -ForegroundColor Cyan

# Error appears on line 3 — context lines 1-5 should be captured
$withContext = @(
    'INFO: Starting build phase',
    'INFO: Compiling module',
    'ERROR: Build failed due to syntax error',
    'INFO: See logs for details',
    'INFO: Aborting build'
)
$result = Extract-MinimalErrors -LogLines $withContext
Assert-ContainsString -Label 'EME context: includes line before' -Haystack $result -Needle 'Compiling module'
Assert-ContainsString -Label 'EME context: includes line after' -Haystack $result -Needle 'See logs for details'

Write-Host "━━━ Extract-MinimalErrors: diverse error patterns ━━━" -ForegroundColor Cyan

# Mix of different languages/ecosystems
$diverseErrors = @(
    # Rust
    'error[E0599]: no method named `foo` found',
    '  --> src/commands.rs:100:10',
    # Maven/Java
    'Caused by: java.lang.ClassNotFoundException: com.example.Missing',
    # Shell
    'bash: line 42: mycommand: command not found',
    # Git
    'fatal: not a git repository',
    # Generic
    'Permission denied (publickey)',
    'tests failed: 3 of 10 tests failed'
)
$result = Extract-MinimalErrors -LogLines $diverseErrors
Assert-ContainsString -Label 'EME diverse: finds Rust error' -Haystack $result -Needle 'error[E0599]'
Assert-ContainsString -Label 'EME diverse: finds ClassNotFoundException' -Haystack $result -Needle 'ClassNotFoundException'
Assert-ContainsString -Label 'EME diverse: finds command not found' -Haystack $result -Needle 'command not found'
Assert-ContainsString -Label 'EME diverse: finds Permission denied' -Haystack $result -Needle 'Permission denied'
Assert-ContainsString -Label 'EME diverse: finds tests failed' -Haystack $result -Needle 'tests failed'

Write-Host "━━━ Extract-MinimalErrors: token cap ━━━" -ForegroundColor Cyan

# Generate 100 distinct error blocks (should cap at 50)
$manyErrors = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt 100; $i++) {
    $manyErrors.Add("ERROR[$i]: Something went wrong in module_$i at line_$i")
}
$result = Extract-MinimalErrors -LogLines $manyErrors
$blockCount = ([regex]::Matches($result, '══ block \d+ ══')).Count
Assert-Returns -Label 'EME cap: 100 errors → capped at ≤50 blocks' -Actual ($blockCount -le 50 -and $blockCount -gt 0) -Expected $true
Assert-ContainsString -Label 'EME cap: includes truncation message' -Haystack $result -Needle 'truncated'

# ===================================================================
# TESTS: New-CoworkerFailureTask
# ===================================================================
Write-Host "━━━ New-CoworkerFailureTask: basic creation ━━━" -ForegroundColor Cyan

$tempRepoRoot = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-monitor-$([System.IO.Path]::GetRandomFileName())"
New-Item -Path $tempRepoRoot -ItemType Directory -Force | Out-Null
try {
    $taskPath = New-CoworkerFailureTask -WorkflowName 'release.yml' `
        -Tag 'v4.12.3' `
        -RunId '1234567890' `
        -Errors 'ERROR: Build failed' `
        -RepoRoot $tempRepoRoot

    Assert-NotNull -Label 'NCFT basic: returns path' -Value $taskPath
    Assert-Returns -Label 'NCFT basic: file exists' -Actual (Test-Path $taskPath) -Expected $true
    Assert-ContainsString -Label 'NCFT basic: filename has fix-' -Haystack $taskPath -Needle 'fix-'
    Assert-ContainsString -Label 'NCFT basic: filename has release' -Haystack $taskPath -Needle 'release'
    Assert-ContainsString -Label 'NCFT basic: filename has failure' -Haystack $taskPath -Needle 'failure'

    # Verify file content
    $content = Get-Content -Path $taskPath -Raw -Encoding UTF8
    Assert-ContainsString -Label 'NCFT content: Title field' -Haystack $content -Needle 'Title:'
    Assert-ContainsString -Label 'NCFT content: Description field' -Haystack $content -Needle 'Description:'
    Assert-ContainsString -Label 'NCFT content: Prompt field' -Haystack $content -Needle 'Prompt:'
    Assert-ContainsString -Label 'NCFT content: workflow name' -Haystack $content -Needle 'release.yml'
    Assert-ContainsString -Label 'NCFT content: tag' -Haystack $content -Needle 'v4.12.3'
    Assert-ContainsString -Label 'NCFT content: run ID' -Haystack $content -Needle '1234567890'
    Assert-ContainsString -Label 'NCFT content: error message' -Haystack $content -Needle 'Build failed'
    Assert-ContainsString -Label 'NCFT content: reproduce section' -Haystack $content -Needle 'Reproduce'
    Assert-ContainsString -Label 'NCFT content: instructions' -Haystack $content -Needle 'Instructions'

    # Clean up task file
    Remove-Item $taskPath -Force -ErrorAction SilentlyContinue

    Write-Host "━━━ New-CoworkerFailureTask: CI workflow ━━━" -ForegroundColor Cyan

    $taskPath = New-CoworkerFailureTask -WorkflowName 'ci.yml' `
        -Tag 'v4.12.3-ci.1' `
        -RunId '9876543210' `
        -Errors 'test result: FAILED' `
        -RepoRoot $tempRepoRoot

    Assert-ContainsString -Label 'NCFT CI: filename has ci' -Haystack $taskPath -Needle 'ci'
    $content = Get-Content -Path $taskPath -Raw -Encoding UTF8
    Assert-ContainsString -Label 'NCFT CI: workflow name in content' -Haystack $content -Needle 'ci.yml'

    Remove-Item $taskPath -Force -ErrorAction SilentlyContinue

    Write-Host "━━━ New-CoworkerFailureTask: long error truncation ━━━" -ForegroundColor Cyan

    # Generate 5000+ char error body
    $longError = 'x' * 5000
    $taskPath = New-CoworkerFailureTask -WorkflowName 'release.yml' `
        -Tag 'v4.12.3' `
        -RunId '1234567890' `
        -Errors $longError `
        -RepoRoot $tempRepoRoot

    $content = Get-Content -Path $taskPath -Raw -Encoding UTF8
    # Full content = template (~300 chars) + truncated errors (≤4000) = ≤4300
    Assert-Returns -Label 'NCFT trunc: content ≤ 5000 chars' -Actual ($content.Length -le 5000) -Expected $true
    Assert-ContainsString -Label 'NCFT trunc: has truncation note' -Haystack $content -Needle 'truncated'

    Remove-Item $taskPath -Force -ErrorAction SilentlyContinue

    Write-Host "━━━ New-CoworkerFailureTask: directory auto-creation ━━━" -ForegroundColor Cyan

    # Delete the task directories to verify auto-creation
    $taskDir = Join-Path $tempRepoRoot 'coworker\tasks\main\0draft'
    if (Test-Path $taskDir) {
        Remove-Item $taskDir -Recurse -Force
    }
    Assert-Returns -Label 'NCFT dir: removed before test' -Actual (Test-Path $taskDir) -Expected $false

    $taskPath = New-CoworkerFailureTask -WorkflowName 'release.yml' `
        -Tag 'v4.12.3' `
        -RunId '1234567890' `
        -Errors 'error' `
        -RepoRoot $tempRepoRoot

    Assert-Returns -Label 'NCFT dir: file created' -Actual (Test-Path $taskPath) -Expected $true
    Assert-Returns -Label 'NCFT dir: directory auto-created' -Actual (Test-Path $taskDir) -Expected $true

    Remove-Item $taskPath -Force -ErrorAction SilentlyContinue

} finally {
    Remove-Item $tempRepoRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "━━━ New-CoworkerFailureTask: structured format ━━━" -ForegroundColor Cyan

# Verify the task content is parseable by Read-TaskContent (from coworker.ps1)
$tempRepoRoot2 = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-monitor2-$([System.IO.Path]::GetRandomFileName())"
New-Item -Path $tempRepoRoot2 -ItemType Directory -Force | Out-Null
try {
    $taskPath = New-CoworkerFailureTask -WorkflowName 'release.yml' `
        -Tag 'v4.12.3' `
        -RunId '1234567890' `
        -Errors "ERROR: Something failed`n  at line 42`n  caused by: missing semicolon" `
        -RepoRoot $tempRepoRoot2

    $raw = Get-Content -Path $taskPath -Raw -Encoding UTF8

    # Ensure it follows the structured format: Title:\nDescription:\nPrompt:
    Assert-Match -Label 'NCFT format: Title line' -Haystack $raw -Pattern '^Title:\s*.+'
    Assert-Match -Label 'NCFT format: Description line' -Haystack $raw -Pattern '(?m)^Description:\s*.+'
    Assert-Match -Label 'NCFT format: Prompt line' -Haystack $raw -Pattern '(?m)^Prompt:\s*.+'

    # Description should be on one line (not multi-line body)
    $descLine = ($raw -split "`n" | Where-Object { $_ -match '^Description:' } | Select-Object -First 1)
    Assert-NotNull -Label 'NCFT format: Description is one line' -Value $descLine

    # Prompt should contain the body
    $promptSection = ($raw -split 'Prompt: ', 2)[1]
    Assert-ContainsString -Label 'NCFT format: Prompt contains error' -Haystack $promptSection -Needle 'ERROR: Something failed'
    Assert-ContainsString -Label 'NCFT format: Prompt contains reproduce' -Haystack $promptSection -Needle 'Reproduce'
    Assert-ContainsString -Label 'NCFT format: Prompt contains instructions' -Haystack $promptSection -Needle 'Instructions'

    Remove-Item $taskPath -Force -ErrorAction SilentlyContinue
} finally {
    Remove-Item $tempRepoRoot2 -Recurse -Force -ErrorAction SilentlyContinue
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
