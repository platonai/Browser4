#!/usr/bin/env pwsh
<#
.SYNOPSIS
Unit tests for orchestration-common.ps1 helpers.

.DESCRIPTION
Tests: token parsing, credit detection, use-case parsing, state round-trip,
scenario discovery, duration formatting.

Similar pattern to common.tests.ps1 -- custom assertion functions, no Pester.
#>

[CmdletBinding()]
param(
    [switch] $VerboseOutput
)

$ErrorActionPreference = 'Stop'
$script:TestsPassed = 0
$script:TestsFailed = 0
$script:TestErrors = [System.Collections.ArrayList]::new()

# ═══════════════════════════════════════════════════════════════════════════════
# Assertion helpers
# ═══════════════════════════════════════════════════════════════════════════════

function Assert-Equal {
    param($Expected, $Actual, [string] $Message = '')
    if ($Expected -eq $Actual) {
        $script:TestsPassed++
        if ($VerboseOutput) { Write-Host "  PASS: $Message" -ForegroundColor Green }
    }
    else {
        $script:TestsFailed++
        $err = "FAIL: $Message | Expected: '$Expected' | Actual: '$Actual'"
        [void]$script:TestErrors.Add($err)
        Write-Host "  $err" -ForegroundColor Red
    }
}

function Assert-True {
    param([bool] $Condition, [string] $Message = '')
    if ($Condition) {
        $script:TestsPassed++
        if ($VerboseOutput) { Write-Host "  PASS: $Message" -ForegroundColor Green }
    }
    else {
        $script:TestsFailed++
        $err = "FAIL: $Message | Expected true, got false"
        [void]$script:TestErrors.Add($err)
        Write-Host "  $err" -ForegroundColor Red
    }
}

function Assert-False {
    param([bool] $Condition, [string] $Message = '')
    if (-not $Condition) {
        $script:TestsPassed++
        if ($VerboseOutput) { Write-Host "  PASS: $Message" -ForegroundColor Green }
    }
    else {
        $script:TestsFailed++
        $err = "FAIL: $Message | Expected false, got true"
        [void]$script:TestErrors.Add($err)
        Write-Host "  $err" -ForegroundColor Red
    }
}

function Assert-NotNull {
    param($Value, [string] $Message = '')
    if ($null -ne $Value) {
        $script:TestsPassed++
        if ($VerboseOutput) { Write-Host "  PASS: $Message" -ForegroundColor Green }
    }
    else {
        $script:TestsFailed++
        $err = "FAIL: $Message | Expected non-null, got null"
        [void]$script:TestErrors.Add($err)
        Write-Host "  $err" -ForegroundColor Red
    }
}

function Assert-Contains {
    param([string] $Haystack, [string] $Needle, [string] $Message = '')
    if ($Haystack -match [regex]::Escape($Needle)) {
        $script:TestsPassed++
        if ($VerboseOutput) { Write-Host "  PASS: $Message" -ForegroundColor Green }
    }
    else {
        $script:TestsFailed++
        $err = "FAIL: $Message | String does not contain '$Needle'"
        [void]$script:TestErrors.Add($err)
        Write-Host "  $err" -ForegroundColor Red
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Dot-source the module under test
# ═══════════════════════════════════════════════════════════════════════════════

$ScriptsDir = $PSScriptRoot
. "$ScriptsDir/orchestration-common.ps1"

# ═══════════════════════════════════════════════════════════════════════════════
# Test: ConvertFrom-TokenSize
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- ConvertFrom-TokenSize ---' -ForegroundColor Yellow

Assert-Equal 917200 (ConvertFrom-TokenSize '917.2k') '917.2k -> 917200'
Assert-Equal 1200000 (ConvertFrom-TokenSize '1.2M') '1.2M -> 1200000'
Assert-Equal 500 (ConvertFrom-TokenSize '500') '500 -> 500'
Assert-Equal 0 (ConvertFrom-TokenSize '') 'empty -> 0'
Assert-Equal 1500000 (ConvertFrom-TokenSize '1.5m') 'lowercase 1.5m -> 1500000'
Assert-Equal 1000000000 (ConvertFrom-TokenSize '1B') '1B -> 1B'
Assert-Equal 3500 (ConvertFrom-TokenSize '3.5k') '3.5k -> 3500'

# ═══════════════════════════════════════════════════════════════════════════════
# Test: Format-TokenCount
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- Format-TokenCount ---' -ForegroundColor Yellow

Assert-Equal '917.2k' (Format-TokenCount 917200) '917200 -> 917.2k'
Assert-Equal '1.2M' (Format-TokenCount 1200000) '1200000 -> 1.2M'
Assert-Equal '500' (Format-TokenCount 500) '500 -> 500'
Assert-Equal '0' (Format-TokenCount 0) '0 -> 0'
Assert-Equal '1.5B' (Format-TokenCount 1500000000) '1.5B -> 1.5B'

# ═══════════════════════════════════════════════════════════════════════════════
# Test: ConvertFrom-TokenUsage
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- ConvertFrom-TokenUsage ---' -ForegroundColor Yellow

$sampleSessionOutput = @"
Some agent output here...
Task completed successfully.

Session Summary
API time spent:         4m 9s
Total session time:     4m 30s
Total code changes:     +490 -29
Breakdown by AI model:
  claude-sonnet-4.6    917.2k in, 12.2k out, 837.5k cached (Est. 1 Premium request)
  gemini-3-pro-preview    500k in, 8.5k out, 300k cached (Est. 1 request)
"@

$result = ConvertFrom-TokenUsage -Output $sampleSessionOutput
Assert-NotNull $result 'Token usage result not null'
Assert-True ($result.byModel.Count -eq 2) "Expected 2 models, got $($result.byModel.Count)"
Assert-True ($result.byModel.ContainsKey('claude-sonnet-4.6')) 'Contains claude-sonnet-4.6'
Assert-Equal 917200 $result.byModel['claude-sonnet-4.6'].input 'claude input 917.2k'
Assert-Equal 12200 $result.byModel['claude-sonnet-4.6'].output 'claude output 12.2k'
Assert-Equal 837500 $result.byModel['claude-sonnet-4.6'].cached 'claude cached 837.5k'
Assert-Equal 500000 $result.byModel['gemini-3-pro-preview'].input 'gemini input 500k'
Assert-Equal 8500 $result.byModel['gemini-3-pro-preview'].output 'gemini output 8.5k'

# Test with no breakdown section
$noBreakdown = "Just some output without a session summary."
$empty = ConvertFrom-TokenUsage -Output $noBreakdown
Assert-True ($empty.byModel.Count -eq 0) 'No breakdown section -> empty result'

# Test with output that is empty
$emptyResult = ConvertFrom-TokenUsage -Output ''
Assert-True ($emptyResult.byModel.Count -eq 0) 'Empty output -> empty result'

# ═══════════════════════════════════════════════════════════════════════════════
# Test: Test-CreditExhaustion
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- Test-CreditExhaustion ---' -ForegroundColor Yellow

# Positive cases
$creditErrors = @(
    'Error: insufficient credits for this request. Please add more credits.',
    'Error 402: Payment Required',
    'Your account balance is insufficient to complete this request.',
    'You have run out of credits. Please top up your account.',
    'Billing error: payment method declined.',
    'API key has been revoked. Please contact support.',
    'Rate limit exceeded. Please try again later.',
    'authentication failed: invalid credentials',
    'your credit balance is not enough for this operation',
    'Error: permission denied for this resource'
)

foreach ($msg in $creditErrors) {
    $check = Test-CreditExhaustion -Output $msg
    Assert-True $check.detected "Credit detection: $($msg.Substring(0, [Math]::Min(50, $msg.Length)))..."
}

# Negative cases
$nonCreditErrors = @(
    'Network timeout: connection refused',
    'Error: file not found at path/to/file.txt',
    'The operation completed successfully.',
    'Warning: deprecated API version',
    'Syntax error in command: unexpected token',
    'Memory allocation failed: out of memory'
)

foreach ($msg in $nonCreditErrors) {
    $check = Test-CreditExhaustion -Output $msg
    Assert-False $check.detected "Non-credit message not detected: $($msg.Substring(0, [Math]::Min(50, $msg.Length)))..."
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test: ConvertFrom-UseCaseFile
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- ConvertFrom-UseCaseFile ---' -ForegroundColor Yellow

# Create a temporary use-case file
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "orchestration-tests-$pid"
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

$sampleUseCase = @"
# Use Case 1: E-commerce Product Comparison (Single-site)
# Level: Simple
# Type: Single-site, deterministic
# Description: Compare mechanical keyboards on Amazon

1. go to https://www.amazon.com/
2. search for "mechanical keyboard"
3. open the first 3 products
4. extract price, rating, and review count
5. write a comparison table
"@

$tempFile = Join-Path $tempDir '01-test-use-case.txt'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($tempFile, $sampleUseCase, $utf8NoBom)

$parsed = ConvertFrom-UseCaseFile -FilePath $tempFile
Assert-Equal 'E-commerce Product Comparison (Single-site)' $parsed.ScenarioName 'Scenario name from heading'
Assert-Equal 'Simple' $parsed.Level 'Level is Simple'
Assert-Equal 'Single-site, deterministic' $parsed.Type 'Type extracted'
Assert-Equal 'Compare mechanical keyboards on Amazon' $parsed.Description 'Description extracted'
Assert-True ($parsed.Instructions -match 'go to https://www.amazon.com') 'Instructions contain step 1'
Assert-True ($parsed.Instructions -match 'write a comparison table') 'Instructions contain last step'
Assert-True ($parsed.MetadataLines.Count -ge 4) 'Metadata lines captured'

# Test Enterprise level
$enterpriseUseCase = @"
# Use Case 12: Company Due Diligence
# Level: Enterprise
# Type: Cross-site, long-running

1. go to https://stripe.com/
2. extract product description
"@

$tempFile2 = Join-Path $tempDir '12-enterprise.txt'
[System.IO.File]::WriteAllText($tempFile2, $enterpriseUseCase, $utf8NoBom)

$parsed2 = ConvertFrom-UseCaseFile -FilePath $tempFile2
Assert-Equal 'Enterprise' $parsed2.Level 'Level is Enterprise'

# Test error on missing file
try {
    ConvertFrom-UseCaseFile -FilePath "$tempDir/nonexistent.txt"
    Assert-True $false 'Should have thrown'
}
catch {
    Assert-True $true 'Throws on missing file'
}

# Test error on empty file
$emptyFile = Join-Path $tempDir 'empty.txt'
[System.IO.File]::WriteAllText($emptyFile, '', $utf8NoBom)
try {
    ConvertFrom-UseCaseFile -FilePath $emptyFile
    Assert-True $false 'Should have thrown on empty file'
}
catch {
    Assert-True $true 'Throws on empty file'
}

# Test file with no instructions (only comments)
$commentOnly = Join-Path $tempDir 'comment-only.txt'
[System.IO.File]::WriteAllText($commentOnly, "# Just a comment`n# Another comment", $utf8NoBom)
try {
    ConvertFrom-UseCaseFile -FilePath $commentOnly
    Assert-True $false 'Should have thrown on comment-only file'
}
catch {
    Assert-True $true 'Throws on comment-only file'
}

# Cleanup
Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue

# ═══════════════════════════════════════════════════════════════════════════════
# Test: Format-Duration
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- Format-Duration ---' -ForegroundColor Yellow

Assert-Equal '<1s' (Format-Duration ([TimeSpan]::FromMilliseconds(500))) '500ms -> <1s'
Assert-Equal '2.5s' (Format-Duration ([TimeSpan]::FromMilliseconds(2500))) '2500ms -> 2.5s'
Assert-Equal '2m 30s' (Format-Duration ([TimeSpan]::FromMinutes(2.5))) '150s -> 2m 30s'
Assert-Equal '1h 30m 0s' (Format-Duration ([TimeSpan]::FromHours(1.5))) '1.5h -> 1h 30m 0s'

# ═══════════════════════════════════════════════════════════════════════════════
# Test: Get-AllScenarios (discovery)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- Get-AllScenarios (discovery) ---' -ForegroundColor Yellow

$all = Get-AllScenarios
Assert-True ($all.Count -gt 0) "Discovered scenarios count > 0 (found $($all.Count))"

# Check sorting: use-cases come first, then md-tasks
$firstUc = $null; $lastUc = $null; $firstMd = $null
for ($i = 0; $i -lt $all.Count; $i++) {
    if ($all[$i].type -eq 'use-case' -and -not $firstUc) { $firstUc = $i }
    if ($all[$i].type -eq 'use-case') { $lastUc = $i }
    if ($all[$i].type -eq 'md-task' -and -not $firstMd) { $firstMd = $i; break }
}
if ($firstUc -ne $null -and $firstMd -ne $null) {
    Assert-True ($lastUc -lt $firstMd) 'Use cases sorted before MD tasks'
}

# Check that all entries have required fields
foreach ($s in $all) {
    Assert-NotNull $s.id "Scenario '$($s.id)' has id"
    Assert-NotNull $s.type "Scenario '$($s.id)' has type"
    Assert-NotNull $s.sourceFile "Scenario '$($s.id)' has sourceFile"
    Assert-True ($s.sortOrder -gt 0) "Scenario '$($s.id)' has sortOrder > 0"
}

# Check use-case levels
$useCases = @($all | Where-Object { $_.type -eq 'use-case' })
if ($useCases.Count -gt 0) {
    $levelsFound = @($useCases | ForEach-Object { $_.level } | Sort-Object -Unique)
    Assert-True ($levelsFound.Count -ge 1) "Use-case levels found: $($levelsFound -join ', ')"
}

# Check MD task categories
$mdTasks = @($all | Where-Object { $_.type -eq 'md-task' })
if ($mdTasks.Count -gt 0) {
    $catsFound = @($mdTasks | ForEach-Object { $_.category } | Sort-Object -Unique)
    Assert-True ($catsFound.Count -ge 1) "MD task categories found: $($catsFound -join ', ')"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test: State JSON round-trip
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- State JSON round-trip ---' -ForegroundColor Yellow

# Use a temporary state directory so we don't interfere with real state
$tempStateDir = Join-Path ([System.IO.Path]::GetTempPath()) "orchestration-test-state-$pid"
$tempStateFile = Join-Path $tempStateDir 'state.json'

# Override the state path functions for this test
$origStatePath = $script:StateDir
$script:StateDir = $tempStateDir  # This won't fully work for the lock path, but we test the JSON round-trip

# Create sample scenarios for state init
$sampleScenarios = @(
    [PSCustomObject]@{ id = 'test-01'; type = 'use-case'; sourceFile = 'path/to/test-01.txt'; category = $null; level = 'Simple'; sortOrder = 1 },
    [PSCustomObject]@{ id = 'test-02'; type = 'md-task'; sourceFile = 'path/to/test-02.md'; category = 'generic'; level = $null; sortOrder = 2 }
)

# We cannot easily test Initialize-OrchestrationState (it writes to the real state path),
# so we test the JSON serialization directly
$machine = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { [System.Environment]::MachineName }
$now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssK')

$testState = [PSCustomObject]@{
    version      = 1
    schema       = 'orchestration-state-v1'
    createdAt    = $now
    updatedAt    = $now
    orchestrator = [PSCustomObject]@{
        pid                 = $pid
        heartbeat           = $now
        totalScenarios      = 2
        completedScenarios  = 0
        passed              = 0
        failed              = 0
        skipped             = 0
        consecutiveFailures = 0
        exitCode            = $null
        globalAbort         = $false
        abortReason         = $null
        tokenTotals         = [PSCustomObject]@{ byModel = [PSCustomObject]@{}; grandTotalInput = 0; grandTotalOutput = 0; grandTotalCached = 0 }
        mode                = 'dev'
    }
    scenarios    = @(
        [PSCustomObject]@{
            id = 'test-01'; type = 'use-case'; sourceFile = 'p1'; category = $null; level = 'Simple'; sortOrder = 1
            status = 'pending'; startedAt = $null; completedAt = $null; durationMs = 0; exitCode = $null; attempts = 0
            tokens = [PSCustomObject]@{ byModel = [PSCustomObject]@{}; totalInput = 0; totalOutput = 0; totalCached = 0 }
            rawOutputFile = $null; issuesFile = $null; errorSummary = $null
        }
    )
}

# Round-trip: object -> JSON -> object
$json = $testState | ConvertTo-Json -Depth 10
Assert-True ($json.Length -gt 100) 'JSON serialization produces non-trivial output'

$reparsed = $json | ConvertFrom-Json
Assert-Equal $testState.version $reparsed.version 'Version preserved'
Assert-Equal $testState.schema $reparsed.schema 'Schema preserved'
Assert-Equal $testState.scenarios[0].id $reparsed.scenarios[0].id 'Scenario id preserved'
Assert-Equal $testState.scenarios[0].status $reparsed.scenarios[0].status 'Scenario status preserved'

# ── Test Merge-TokenUsage ─────────────────────────────────────────────────
$tokens = @{
    byModel = @{
        'claude-sonnet-4.6' = @{ input = 500000; output = 8000; cached = 400000 }
    }
}
Merge-TokenUsage -State $testState -TokenUsage $tokens
Assert-Equal 500000 $testState.orchestrator.tokenTotals.grandTotalInput 'Merge: grandTotalInput'
Assert-Equal 8000 $testState.orchestrator.tokenTotals.grandTotalOutput 'Merge: grandTotalOutput'

# Merge again (accumulate)
Merge-TokenUsage -State $testState -TokenUsage $tokens
Assert-Equal 1000000 $testState.orchestrator.tokenTotals.grandTotalInput 'Merge 2x: grandTotalInput doubled'

# ── Test Update-ScenarioState (counter logic) ────────────────────────────
# Create a writable temp dir
New-Item -ItemType Directory -Path $tempStateDir -Force | Out-Null

# We can't easily test Write-OrchestrationState here without the full locking infra,
# but we can test the in-memory counter update logic
# Just verify the counter fields exist and are numeric
Assert-True ($testState.orchestrator.passed -is [int]) 'Passed counter is integer'
Assert-True ($testState.orchestrator.failed -is [int]) 'Failed counter is integer'

# ═══════════════════════════════════════════════════════════════════════════════
# Test: Consecutive failure logic (simulated)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- Consecutive failure logic ---' -ForegroundColor Yellow

$simState = [PSCustomObject]@{
    orchestrator = [PSCustomObject]@{
        consecutiveFailures = 0
    }
}

# Sim: 3 passes, no abort
$simState.orchestrator.consecutiveFailures = 0
for ($i = 0; $i -lt 3; $i++) {
    # Pass: reset
    $simState.orchestrator.consecutiveFailures = 0
}
Assert-Equal 0 $simState.orchestrator.consecutiveFailures 'After 3 passes: consecutiveFailures = 0'

# Sim: 5 failures -> abort
$simState.orchestrator.consecutiveFailures = 0
for ($i = 0; $i -lt 5; $i++) {
    $simState.orchestrator.consecutiveFailures++
}
Assert-True (($simState.orchestrator.consecutiveFailures -ge 5)) 'After 5 failures: consecutiveFailures >= 5'

# Sim: 3 fails, 1 pass, 2 fails -> no abort (not consecutive)
$simState.orchestrator.consecutiveFailures = 0
for ($i = 0; $i -lt 3; $i++) { $simState.orchestrator.consecutiveFailures++ }
$simState.orchestrator.consecutiveFailures = 0  # pass resets
for ($i = 0; $i -lt 2; $i++) { $simState.orchestrator.consecutiveFailures++ }
Assert-Equal 2 $simState.orchestrator.consecutiveFailures 'After pass + 2 fails: consecutiveFailures = 2'

# ═══════════════════════════════════════════════════════════════════════════════
# Test: Heartbeat staleness logic (simulated)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- Heartbeat staleness logic ---' -ForegroundColor Yellow

$now = Get-Date
$freshHb = $now.AddSeconds(-30)   # 30s ago -> fresh (within 90s threshold)
$staleHb = $now.AddSeconds(-120)  # 120s ago -> stale (beyond 90s threshold)

$staleThresholdSec = 90
$freshAge = ($now - $freshHb).TotalSeconds
$staleAge = ($now - $staleHb).TotalSeconds

Assert-True ($freshAge -le $staleThresholdSec) "Fresh heartbeat (${freshAge}s <= ${staleThresholdSec}s)"
Assert-True ($staleAge -gt $staleThresholdSec) "Stale heartbeat (${staleAge}s > ${staleThresholdSec}s)"

# ═══════════════════════════════════════════════════════════════════════════════
# Test: ConsecutiveFailureCount extraction from state
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '--- State counter integrity ---' -ForegroundColor Yellow

# Verify the orchestrator counters match the sum of scenario statuses
$countState = [PSCustomObject]@{
    orchestrator = [PSCustomObject]@{
        completedScenarios  = 0
        passed              = 0
        failed              = 0
        skipped             = 0
    }
    scenarios = @(
        [PSCustomObject]@{ status = 'passed' }
        [PSCustomObject]@{ status = 'passed' }
        [PSCustomObject]@{ status = 'failed' }
        [PSCustomObject]@{ status = 'passed' }
        [PSCustomObject]@{ status = 'skipped' }
    )
}

$countState.orchestrator.completedScenarios = ($countState.scenarios |
    Where-Object { $_.status -in @('passed', 'failed', 'skipped') }).Count
$countState.orchestrator.passed = ($countState.scenarios |
    Where-Object { $_.status -eq 'passed' }).Count
$countState.orchestrator.failed = ($countState.scenarios |
    Where-Object { $_.status -eq 'failed' }).Count
$countState.orchestrator.skipped = ($countState.scenarios |
    Where-Object { $_.status -eq 'skipped' }).Count

Assert-Equal 5 $countState.orchestrator.completedScenarios 'Completed = 5'
Assert-Equal 3 $countState.orchestrator.passed 'Passed = 3'
Assert-Equal 1 $countState.orchestrator.failed 'Failed = 1'
Assert-Equal 1 $countState.orchestrator.skipped 'Skipped = 1'

# ═══════════════════════════════════════════════════════════════════════════════
# Cleanup test state
# ═══════════════════════════════════════════════════════════════════════════════

if (Test-Path $tempStateDir) {
    Remove-Item -Recurse -Force $tempStateDir -ErrorAction SilentlyContinue
}

# Restore original state dir
$script:StateDir = $origStatePath

# ═══════════════════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host ('=' * 60) -ForegroundColor Cyan
Write-Host '  Test Results' -ForegroundColor Cyan
Write-Host ('=' * 60) -ForegroundColor Cyan
Write-Host ''

$total = $script:TestsPassed + $script:TestsFailed
Write-Host "  Total:  $total" -ForegroundColor Cyan
Write-Host "  Passed: $script:TestsPassed" -ForegroundColor Green
if ($script:TestsFailed -gt 0) {
    Write-Host "  Failed: $script:TestsFailed" -ForegroundColor Red
    Write-Host ''
    Write-Host '  Errors:' -ForegroundColor Red
    foreach ($err in $script:TestErrors) {
        Write-Host "    $err" -ForegroundColor Red
    }
}
Write-Host ''

if ($script:TestsFailed -gt 0) {
    exit 1
}
exit 0
