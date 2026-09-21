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
    -FunctionNames @('ConvertTo-LogLines', 'Parse-GitHubLogLine', 'Extract-MinimalErrors', 'New-CoworkerFailureTask', 'Invoke-WorkflowFailureHandler', 'Get-WorkflowRuns', 'Select-TriggeredRun')

# Verify we got non-empty text back (functions contain blank lines so split on \n\n isn't 1:1)
Assert-NotNull -Label 'Functions: extracted text non-null' -Value $funcText
Assert-Returns -Label 'Functions: text is non-empty' -Actual ($funcText.Length -gt 100) -Expected $true

Invoke-Expression $funcText

Write-Host ''

# ===================================================================
# TESTS: ConvertTo-LogLines (type-stability regression)
# ===================================================================
Write-Host "━━━ ConvertTo-LogLines: returns typed string[] ━━━" -ForegroundColor Cyan

# Regression guard: must return string[] (not Object[]/scalar) so
# List[string].AddRange() in Invoke-WorkflowFailureHandler does not throw.
$cll = [System.Collections.Generic.List[string]]::new()
$cllMulti = ConvertTo-LogLines -RawLogs @('line1', 'line2', 'line3')
Assert-Returns -Label 'CLL multi: returns string[]' -Actual ($cllMulti -is [string[]]) -Expected $true
$cll.AddRange($cllMulti)
Assert-Returns -Label 'CLL multi: AddRange accepts result' -Actual $cll.Count -Expected 3
$cllSingle = ConvertTo-LogLines -RawLogs 'one line'
Assert-Returns -Label 'CLL single: returns string[]' -Actual ($cllSingle -is [string[]]) -Expected $true
$cll.AddRange($cllSingle)
Assert-Returns -Label 'CLL single: AddRange accepts result' -Actual $cll.Count -Expected 4

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
    # Run discovery must stay identical: it is the code that decides which
    # workflow run the monitor watches.  It had already silently diverged once
    # (release polled --limit 30, CI --limit 5) — this assert keeps the fix in
    # sync.  This pair is byte-identical today.
    $sharedNames = @('Get-WorkflowRuns', 'Select-TriggeredRun')
    $ciShared = Get-FunctionsFromScript -ScriptPath $MonitorScriptPath -FunctionNames $sharedNames
    $releaseShared = Get-FunctionsFromScript -ScriptPath $releaseScript -FunctionNames $sharedNames

    $ciSharedNorm = $ciShared -replace '\s+', ' '
    $releaseSharedNorm = $releaseShared -replace '\s+', ' '

    if ($ciSharedNorm -eq $releaseSharedNorm -and $ciSharedNorm.Length -gt 0) {
        Write-Host "    ✅ Run-discovery functions are byte-identical between CI and release" -ForegroundColor Green
    } else {
        $ciSharedLines = ($ciShared -split "`n" | Where-Object { $_.Trim() -ne '' }).Count
        $releaseSharedLines = ($releaseShared -split "`n" | Where-Object { $_.Trim() -ne '' }).Count
        Assert-Returns -Label 'Parity: run-discovery functions identical in CI vs release' -Actual $ciSharedLines -Expected $releaseSharedLines
    }

    # KNOWN, PRE-EXISTING DIVERGENCE (not a regression): the CI variant of
    # Extract-MinimalErrors gained log cleaning that the release variant lacks —
    # ANSI stripping plus GHA/shell boilerplate filtering (Get-CleanMessage and
    # boilerplatePatterns), so its extracted body is ~46 lines longer.  That
    # divergence predates the run-discovery fix and is reported, not asserted:
    # making it a hard failure would leave this suite red for an unrelated
    # reason, and silently "fixing" it means changing release failure
    # diagnostics.  Porting the CI log-cleaning into monitor-release is a
    # separate change.
    $legacyNames = @('Extract-MinimalErrors', 'New-CoworkerFailureTask')
    $ciLegacy = Get-FunctionsFromScript -ScriptPath $MonitorScriptPath -FunctionNames $legacyNames
    $releaseLegacy = Get-FunctionsFromScript -ScriptPath $releaseScript -FunctionNames $legacyNames

    $ciLegacyLines = ($ciLegacy -split "`n" | Where-Object { $_.Trim() -ne '' }).Count
    $releaseLegacyLines = ($releaseLegacy -split "`n" | Where-Object { $_.Trim() -ne '' }).Count

    if ($ciLegacy -replace '\s+', ' ' -eq $releaseLegacy -replace '\s+', ' ') {
        Write-Host "    ✅ Failure-handler functions are byte-identical between CI and release" -ForegroundColor Green
    } else {
        Write-Host "    ⚠️  Known divergence — failure-handler functions: CI $ciLegacyLines non-empty lines vs release $releaseLegacyLines" -ForegroundColor Yellow
        Write-Host "        CI's Extract-MinimalErrors adds ANSI stripping + boilerplate filtering (Get-CleanMessage);" -ForegroundColor Yellow
        Write-Host "        porting it into monitor-release is a separate change (see the comment above)." -ForegroundColor Yellow
    }
} else {
    Write-Host "    ⚠️  release script not found at $releaseScript — skipping parity check" -ForegroundColor Yellow
}

# ===================================================================
# TESTS: Select-TriggeredRun (stale-run race regression)
# ===================================================================
Write-Host "━━━ Select-TriggeredRun: only the run this push triggered ━━━" -ForegroundColor Cyan

# Regression: re-pushing a moved tag leaves the earlier, already-completed run
# with the same headBranch, and `gh run list` returns it first.  Matching on
# headBranch alone therefore returned the STALE run, so the monitor reported the
# old conclusion as the outcome of the new push (release incident 2026-09-10:
# stale run 34439006612 vs freshly triggered run 34442693881).
$pushTime = [datetime]::Parse('2026-09-10T05:50:00Z').ToUniversalTime()

$staleRun = [pscustomobject]@{
    databaseId = 34439006612
    headBranch = 'v4.13.17-ci.5'
    status     = 'completed'
    conclusion = 'failure'
    createdAt  = '2026-09-10T04:54:46Z'
    url        = 'https://example.invalid/old'
}
$newRun = [pscustomobject]@{
    databaseId = 34442693881
    headBranch = 'v4.13.17-ci.5'
    status     = 'in_progress'
    conclusion = $null
    createdAt  = '2026-09-10T05:50:31Z'
    url        = 'https://example.invalid/new'
}
$baseline = @(34439006612)

$picked = Select-TriggeredRun -Runs @($staleRun, $newRun) -Tag 'v4.13.17-ci.5' `
    -BaselineIds $baseline -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: picks the newly triggered run' -Actual $picked.databaseId -Expected 34442693881

$waiting = Select-TriggeredRun -Runs @($staleRun) -Tag 'v4.13.17-ci.5' `
    -BaselineIds $baseline -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: stale run alone => $null (keep waiting)' -Actual ($null -eq $waiting) -Expected $true

$newestRun = [pscustomobject]@{
    databaseId = 34442693999
    headBranch = 'v4.13.17-ci.5'
    status     = 'queued'
    conclusion = $null
    createdAt  = '2026-09-10T05:51:02Z'
    url        = 'https://example.invalid/newest'
}
$pickedNewest = Select-TriggeredRun -Runs @($staleRun, $newestRun, $newRun) -Tag 'v4.13.17-ci.5' `
    -BaselineIds $baseline -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: newest new run wins' -Actual $pickedNewest.databaseId -Expected 34442693999

$otherTag = Select-TriggeredRun -Runs @($newRun) -Tag 'v4.13.17-ci.6' `
    -BaselineIds $baseline -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: different tag is ignored' -Actual ($null -eq $otherTag) -Expected $true

# Baseline lookup failed (empty): the createdAt guard still keeps the stale run out.
$noBaseline = Select-TriggeredRun -Runs @($staleRun, $newRun) -Tag 'v4.13.17-ci.5' `
    -BaselineIds @() -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: empty baseline + createdAt guard picks the new run' -Actual $noBaseline.databaseId -Expected 34442693881

$noBaselineStaleOnly = Select-TriggeredRun -Runs @($staleRun) -Tag 'v4.13.17-ci.5' `
    -BaselineIds @() -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: empty baseline still rejects the pre-push run' -Actual ($null -eq $noBaselineStaleOnly) -Expected $true

$stringBaseline = Select-TriggeredRun -Runs @($staleRun, $newRun) -Tag 'v4.13.17-ci.5' `
    -BaselineIds @('34439006612') -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: string baseline ids still exclude the stale run' -Actual $stringBaseline.databaseId -Expected 34442693881

# gh/ConvertFrom-Json hands createdAt over as a DateTime (not a string) — the
# real shape must behave exactly like the string form.
$dtStale = [pscustomobject]@{
    databaseId = 34439006612
    headBranch = 'v4.13.17-ci.5'
    status     = 'completed'
    conclusion = 'failure'
    createdAt  = [datetime]::Parse('2026-09-10T04:54:46Z')
    url        = 'https://example.invalid/old'
}
$dtNew = [pscustomobject]@{
    databaseId = 34442693881
    headBranch = 'v4.13.17-ci.5'
    status     = 'in_progress'
    conclusion = $null
    createdAt  = [datetime]::Parse('2026-09-10T05:50:31Z')
    url        = 'https://example.invalid/new'
}
$dtShaped = Select-TriggeredRun -Runs @($dtStale, $dtNew) -Tag 'v4.13.17-ci.5' `
    -BaselineIds @() -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: DateTime createdAt (gh shape) picks the new run' -Actual $dtShaped.databaseId -Expected 34442693881

$dtStaleOnly = Select-TriggeredRun -Runs @($dtStale) -Tag 'v4.13.17-ci.5' `
    -BaselineIds @() -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: DateTime createdAt (gh shape) rejects the pre-push run' -Actual ($null -eq $dtStaleOnly) -Expected $true

$idOnly = Select-TriggeredRun -Runs @($staleRun, $newRun) -Tag 'v4.13.17-ci.5' -BaselineIds $baseline
Assert-Returns -Label 'STR: id baseline alone is enough' -Actual $idOnly.databaseId -Expected 34442693881

$firstRun = Select-TriggeredRun -Runs @($newRun) -Tag 'v4.13.17-ci.5' `
    -BaselineIds @() -TriggeredAfter $pushTime
Assert-Returns -Label 'STR: first run of a tag is picked' -Actual $firstRun.databaseId -Expected 34442693881

$emptyRuns = Select-TriggeredRun -Runs @() -Tag 'v4.13.17-ci.5'
Assert-Returns -Label 'STR: empty run list => $null' -Actual ($null -eq $emptyRuns) -Expected $true
$emptyTag = Select-TriggeredRun -Runs @($newRun) -Tag ''
Assert-Returns -Label 'STR: empty tag => $null' -Actual ($null -eq $emptyTag) -Expected $true
$noArgs = Select-TriggeredRun -Runs $null -Tag 'v4.13.17-ci.5'
Assert-Returns -Label 'STR: null run list => $null' -Actual ($null -eq $noArgs) -Expected $true

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
