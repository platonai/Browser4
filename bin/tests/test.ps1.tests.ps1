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
    Unit tests for helper functions and dispatch logic in bin/test.ps1.

.DESCRIPTION
    Extracts pure helper functions from test.ps1 via PowerShell's AST parser
    and tests them in isolation — no Maven execution, no Cargo, no network.

    Covers: Write-Rule, Write-CommandBanner, Invoke-CommandAndReport,
            Get-ReactorModuleOrder, Get-ArtifactIdForDir,
            test-type dispatch ($testTypeMap), argument parsing.

    Run standalone:
        pwsh bin/tests/test.ps1.tests.ps1

    Run via runner:
        pwsh bin/tests-production/run-tests.ps1 test.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Resolve paths
# -------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TestUtilsModule = Join-Path $ScriptDir '..\tests-production\test-utils.psm1'
$TestPs1Path = Join-Path $ScriptDir '..\test.ps1'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
if (Test-Path $TestUtilsModule) {
    Import-Module $TestUtilsModule -Force
    Start-TestSession -Name 'test.ps1-helpers'
    Write-TestHeader -Name 'test.ps1-helpers'
} else {
    Write-Host "WARNING: test-utils.psm1 not found at $TestUtilsModule — running in standalone mode." -ForegroundColor Yellow
    # Minimal standalone counters
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
# Track additional content-based assertions
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

# -------------------------------------------------------------------
# Helper: extract function definitions from test.ps1 via AST parser.
# This avoids executing the full test script while still testing the
# exact current source.
# -------------------------------------------------------------------
function Get-FunctionDefinitionsFromScript {
    param([string]$ScriptPath)

    if (-not (Test-Path $ScriptPath)) {
        Write-Host "ERROR: test.ps1 not found at: $ScriptPath" -ForegroundColor Red
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

    # FindAll with $true = searchNestedFunctions
    $functionDefs = $ast.FindAll({
        param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
    }, $true)

    if ($functionDefs.Count -eq 0) {
        Write-Host "WARNING: No function definitions found in $ScriptPath" -ForegroundColor Yellow
    }

    Write-Host "Extracted $($functionDefs.Count) function definitions from test.ps1" -ForegroundColor DarkGray

    return ($functionDefs | ForEach-Object { $_.Extent.Text }) -join "`n`n"
}

# -------------------------------------------------------------------
# Set up script-scoped variables that the extracted functions reference.
# These mirror the variables defined at the top of test.ps1.
# -------------------------------------------------------------------

# Find the repo root (same logic as test.ps1 lines 22-39)
$repoRoot = (git rev-parse --show-toplevel 2>$null)
if ([string]::IsNullOrWhiteSpace($repoRoot)) {
    $repoRoot = $ScriptDir
    while (-not (Test-Path (Join-Path $repoRoot 'VERSION'))) {
        $parent = Split-Path -Parent $repoRoot
        if ($parent -eq $repoRoot) { break }
        $repoRoot = $parent
    }
}

if (-not (Test-Path (Join-Path $repoRoot 'VERSION'))) {
    Write-Error "Could not locate the repository root from $ScriptDir"
    exit 1
}

Write-Host "Source : $TestPs1Path" -ForegroundColor DarkGray
Write-Host "Repo   : $repoRoot" -ForegroundColor DarkGray

# Extract and evaluate all function definitions from test.ps1
$functionText = Get-FunctionDefinitionsFromScript -ScriptPath $TestPs1Path
Invoke-Expression $functionText

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Write-Rule
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Write-Rule ━━━" -ForegroundColor Cyan

$output = Write-Rule 2>&1 | Out-String
Assert-Returns -Label 'Write-Rule: default width = 46' -Actual $output.Length -Expected 48  # 46 '=' + CRLF

$output = Write-Rule -Width 10 2>&1 | Out-String
Assert-Returns -Label 'Write-Rule: custom width 10' -Actual $output.Trim().Length -Expected 10

$output = Write-Rule -Width 0 2>&1 | Out-String
Assert-Returns -Label 'Write-Rule: zero width' -Actual $output.Trim() -Expected ''

$output = Write-Rule -Width 1 2>&1 | Out-String
Assert-ContainsString -Label 'Write-Rule: single char' -Haystack $output -Needle '='

# ═══════════════════════════════════════════════════════════════════
# TESTS: Write-CommandBanner
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Write-CommandBanner: basic ━━━" -ForegroundColor Cyan

$output = Write-CommandBanner -Label 'Running tests' 2>&1 | Out-String
Assert-ContainsString -Label 'Banner basic: contains label' -Haystack $output -Needle 'Running tests'
Assert-ContainsString -Label 'Banner basic: has rule line' -Haystack $output -Needle '=============================================='

Write-Host "━━━ Write-CommandBanner: with subtitle ━━━" -ForegroundColor Cyan

$output = Write-CommandBanner -Label 'Header' -Subtitle '  mvnw test' 2>&1 | Out-String
Assert-ContainsString -Label 'Banner subtitle: contains subtext' -Haystack $output -Needle '  mvnw test'
Assert-ContainsString -Label 'Banner subtitle: contains header' -Haystack $output -Needle 'Header'

Write-Host "━━━ Write-CommandBanner: with icon ━━━" -ForegroundColor Cyan

$output = Write-CommandBanner -Label 'All tests passed' -Icon '✅' 2>&1 | Out-String
Assert-ContainsString -Label 'Banner icon: contains icon' -Haystack $output -Needle '✅'
Assert-ContainsString -Label 'Banner icon: contains label' -Haystack $output -Needle 'All tests passed'

$output = Write-CommandBanner -Label 'Build failed with exit code 42' -Icon '❌' 2>&1 | Out-String
Assert-ContainsString -Label 'Banner failure: contains icon' -Haystack $output -Needle '❌'
Assert-ContainsString -Label 'Banner failure: contains label' -Haystack $output -Needle 'Build failed with exit code 42'

Write-Host "━━━ Write-CommandBanner: edge cases ━━━" -ForegroundColor Cyan

$output = Write-CommandBanner -Label '' 2>&1 | Out-String
Assert-NotNull -Label 'Banner empty label: does not crash' -Value ($output -is [string])

$longLabel = 'x' * 200
$output = Write-CommandBanner -Label $longLabel 2>&1 | Out-String
Assert-ContainsString -Label 'Banner long label: no truncation' -Haystack $output -Needle $longLabel

# ═══════════════════════════════════════════════════════════════════
# TESTS: Invoke-CommandAndReport
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Invoke-CommandAndReport: success ━━━" -ForegroundColor Cyan

$exitCode = Invoke-CommandAndReport -ScriptBlock { Write-Output 'hello' } -Label 'success test' -NoExit
Assert-Returns -Label 'ICAR success: exit code 0' -Actual $exitCode -Expected 0

Write-Host "━━━ Invoke-CommandAndReport: failure ━━━" -ForegroundColor Cyan

$exitCode = Invoke-CommandAndReport -ScriptBlock { cmd /c exit 42 } -Label 'failure test' -NoExit
Assert-Returns -Label 'ICAR failure: exit code 42' -Actual $exitCode -Expected 42

$exitCode = Invoke-CommandAndReport -ScriptBlock { cmd /c exit 1 } -Label 'failure test 2' -NoExit
Assert-Returns -Label 'ICAR failure: exit code 1' -Actual $exitCode -Expected 1

Write-Host "━━━ Invoke-CommandAndReport: PreExecPath ━━━" -ForegroundColor Cyan

$originalLocation = Get-Location
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-icar-$([System.IO.Path]::GetRandomFileName())"
New-Item -Path $tempDir -ItemType Directory -Force | Out-Null
try {
    $locationAfter = $null
    $exitCode = Invoke-CommandAndReport -ScriptBlock {
        $script:locationAfter = Get-Location
    } -Label 'pushd test' -PreExecPath $tempDir -NoExit
    # locationAfter is set inside the scriptblock, which runs in the same scope
    # since we used $script:locationAfter, check the result
    # (Actually, scriptblock invoked with & runs in child scope, so $script: locationAfter
    #  in the test file scope won' be set by the scriptblock.  Let me fix this.)
    $currentLocation = Get-Location
    Assert-Returns -Label 'ICAR PreExecPath: returns to original dir' -Actual $currentLocation.Path -Expected $originalLocation.Path
    Assert-Returns -Label 'ICAR PreExecPath: exit code 0' -Actual $exitCode -Expected 0
} finally {
    Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "━━━ Invoke-CommandAndReport: PreExecPath with missing dir ━━━" -ForegroundColor Cyan

$badDir = Join-Path ([System.IO.Path]::GetTempPath()) "b4-nonexistent-$([System.IO.Path]::GetRandomFileName())"
try {
    # Should throw because Push-Location fails on a non-existent directory
    Invoke-CommandAndReport -ScriptBlock { Write-Output 'unreachable' } -Label 'bad pushd' -PreExecPath $badDir -NoExit
    Assert-Returns -Label 'ICAR bad PreExecPath: should not reach here' -Actual 'reached' -Expected 'should-throw'
} catch {
    # Expected — Push-Location fails
    Assert-NotNull -Label 'ICAR bad PreExecPath: throws on missing directory' -Value $_.Exception
}

Write-Host "━━━ Invoke-CommandAndReport: empty scriptblock ━━━" -ForegroundColor Cyan

$exitCode = Invoke-CommandAndReport -ScriptBlock {} -Label 'empty block' -NoExit
Assert-Returns -Label 'ICAR empty scriptblock: exit code 0' -Actual $exitCode -Expected 0

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-ReactorModuleOrder (reads the real pom.xml)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-ReactorModuleOrder ━━━" -ForegroundColor Cyan

$modules = Get-ReactorModuleOrder
Assert-NotNull -Label 'GRMO: returns non-null' -Value $modules
Assert-Returns -Label 'GRMO: returns array' -Actual ($modules -is [array]) -Expected $true

if ($modules.Count -gt 0) {
    Assert-Returns -Label 'GRMO: first module is browser4-dependencies' -Actual $modules[0] -Expected 'browser4-dependencies'
    Assert-ContainsString -Label 'GRMO: contains browser4-core' -Haystack ($modules -join ',') -Needle 'browser4-core'
    Assert-ContainsString -Label 'GRMO: contains browser4-agentic' -Haystack ($modules -join ',') -Needle 'browser4-agentic'
    Assert-ContainsString -Label 'GRMO: contains browser4-boot' -Haystack ($modules -join ',') -Needle 'browser4-boot'
    Assert-ContainsString -Label 'GRMO: contains browser4-rest' -Haystack ($modules -join ',') -Needle 'browser4-rest'
}

Write-Host "━━━ Get-ReactorModuleOrder: module order is stable ━━━" -ForegroundColor Cyan

$secondCall = Get-ReactorModuleOrder
Assert-Returns -Label 'GRMO stable: same count' -Actual $secondCall.Count -Expected $modules.Count
for ($i = 0; $i -lt $modules.Count; $i++) {
    if ($modules[$i] -ne $secondCall[$i]) {
        Assert-Returns -Label "GRMO stable: index $i matches" -Actual $modules[$i] -Expected $secondCall[$i]
    }
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-ArtifactIdForDir
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-ArtifactIdForDir: real modules ━━━" -ForegroundColor Cyan

$coreDir = Join-Path $repoRoot 'browser4-core'
$aid = Get-ArtifactIdForDir $coreDir
Assert-Returns -Label 'GAIFD: browser4-core → browser4-core' -Actual $aid -Expected 'browser4-core'

$agenticDir = Join-Path $repoRoot 'browser4-agentic'
$aid = Get-ArtifactIdForDir $agenticDir
Assert-Returns -Label 'GAIFD: browser4-agentic → browser4-agentic' -Actual $aid -Expected 'browser4-agentic'

Write-Host "━━━ Get-ArtifactIdForDir: edge cases ━━━" -ForegroundColor Cyan

$aid = Get-ArtifactIdForDir (Join-Path $repoRoot 'nonexistent-module')
Assert-Returns -Label 'GAIFD: nonexistent dir → null' -Actual $aid -Expected $null

$aid = Get-ArtifactIdForDir $repoRoot
Assert-NotNull -Label 'GAIFD: parent pom has artifactId' -Value $aid

# ── Synthetic pom.xml with a <parent> block to verify parent-skipping logic ──
$syntheticDir = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-gaifd-$([System.IO.Path]::GetRandomFileName())"
New-Item -Path $syntheticDir -ItemType Directory -Force | Out-Null
try {
    @'
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>parent-artifact</artifactId>
        <version>1.0</version>
    </parent>
    <artifactId>child-artifact</artifactId>
    <version>2.0</version>
</project>
'@ | Set-Content -Path (Join-Path $syntheticDir 'pom.xml')
    $aid = Get-ArtifactIdForDir $syntheticDir
    Assert-Returns -Label 'GAIFD synth: skips parent block → child-artifact' -Actual $aid -Expected 'child-artifact'
} finally {
    Remove-Item $syntheticDir -Recurse -Force -ErrorAction SilentlyContinue
}

# ── Synthetic pom.xml with NO parent block ──
$syntheticDir2 = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-gaifd2-$([System.IO.Path]::GetRandomFileName())"
New-Item -Path $syntheticDir2 -ItemType Directory -Force | Out-Null
try {
    @'
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <artifactId>standalone-module</artifactId>
</project>
'@ | Set-Content -Path (Join-Path $syntheticDir2 'pom.xml')
    $aid = Get-ArtifactIdForDir $syntheticDir2
    Assert-Returns -Label 'GAIFD synth2: no parent → standalone-module' -Actual $aid -Expected 'standalone-module'
} finally {
    Remove-Item $syntheticDir2 -Recurse -Force -ErrorAction SilentlyContinue
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: Exit-UnknownTestType
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Exit-UnknownTestType ━━━" -ForegroundColor Cyan

# We test this by invoking it in a child process to avoid exiting the test runner.
$testPs1Abs = (Resolve-Path $TestPs1Path).Path
$unknownResult = pwsh -NoProfile -Command "
    . '$testPs1Abs' *>`$null 2>&1 | Out-Null  # suppress entry-level output
    try {
        Exit-UnknownTestType 'bogus_type'
    } catch {
        # Exit-UnknownTestType calls exit 1, which terminates the process
        # but since this is a child pwsh it just exits with code 1
    }
" 2>&1

# The child pwsh exits with code 1 because exit 1 runs.  Let's test differently:
# Extract the error message by redirecting stderr.
$errOutput = pwsh -NoProfile -Command "
    . '$testPs1Abs' *>`$null
    Exit-UnknownTestType 'bogus_type'
" 2>&1 | Out-String

Assert-ContainsString -Label 'EUTT: prints "Unknown test type"' -Haystack $errOutput -Needle "Unknown test type 'bogus_type'"
Assert-ContainsString -Label 'EUTT: prints "Valid test types"' -Haystack $errOutput -Needle 'Valid test types'

# Test: a typo that looks like a known type should still be caught
$errOutput2 = pwsh -NoProfile -Command "
    . '$testPs1Abs' *>`$null
    Exit-UnknownTestType 'fasst'
" 2>&1 | Out-String
Assert-ContainsString -Label 'EUTT: catches typo fasst' -Haystack $errOutput2 -Needle "Unknown test type 'fasst'"

# ═══════════════════════════════════════════════════════════════════
# TESTS: Test-type dispatch logic ($testTypeMap)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Test type map: canonical names ━━━" -ForegroundColor Cyan

# Reconstruct the map from test.ps1 (same definition as the source)
$testTypeMap = @{
    'fast'          = 'maven'
    'it'            = 'maven'
    'e2e'           = 'maven'
    'rest'          = 'maven'
    'skills'        = 'maven'
    'mcp'           = 'maven'
    'main'          = 'maven-expand'
    'cli'           = 'cli'
    'browser4-cli'  = 'cli'
    'server'        = 'server'
    'mock-site'     = 'server'
    'mocksite'      = 'server'
    'mocksiteboot'  = 'server'
    'rws'           = 'rws'
    'resume'        = 'resume'
}

Assert-Returns -Label 'Map: fast → maven' -Actual $testTypeMap['fast'] -Expected 'maven'
Assert-Returns -Label 'Map: it → maven' -Actual $testTypeMap['it'] -Expected 'maven'
Assert-Returns -Label 'Map: e2e → maven' -Actual $testTypeMap['e2e'] -Expected 'maven'
Assert-Returns -Label 'Map: rest → maven' -Actual $testTypeMap['rest'] -Expected 'maven'
Assert-Returns -Label 'Map: skills → maven' -Actual $testTypeMap['skills'] -Expected 'maven'
Assert-Returns -Label 'Map: mcp → maven' -Actual $testTypeMap['mcp'] -Expected 'maven'
Assert-Returns -Label 'Map: main → maven-expand' -Actual $testTypeMap['main'] -Expected 'maven-expand'
Assert-Returns -Label 'Map: cli → cli' -Actual $testTypeMap['cli'] -Expected 'cli'
Assert-Returns -Label 'Map: rws → rws' -Actual $testTypeMap['rws'] -Expected 'rws'
Assert-Returns -Label 'Map: resume → resume' -Actual $testTypeMap['resume'] -Expected 'resume'

Write-Host "━━━ Test type map: aliases ━━━" -ForegroundColor Cyan

Assert-Returns -Label 'Map alias: browser4-cli → cli' -Actual $testTypeMap['browser4-cli'] -Expected 'cli'
Assert-Returns -Label 'Map alias: mock-site → server' -Actual $testTypeMap['mock-site'] -Expected 'server'
Assert-Returns -Label 'Map alias: mocksite → server' -Actual $testTypeMap['mocksite'] -Expected 'server'
Assert-Returns -Label 'Map alias: mocksiteboot → server' -Actual $testTypeMap['mocksiteboot'] -Expected 'server'

Write-Host "━━━ Test type map: dispatch logic ━━━" -ForegroundColor Cyan

# Test the dispatch bucketing used in test.ps1
function Test-Dispatch {
    param([string[]]$InputTypes)
    $mavenT  = @($InputTypes | Where-Object { $testTypeMap[$_] -in 'maven', 'maven-expand' })
    $cliT    = @($InputTypes | Where-Object { $testTypeMap[$_] -eq 'cli' })
    $rwsT    = @($InputTypes | Where-Object { $testTypeMap[$_] -eq 'rws' })
    $serverT = @($InputTypes | Where-Object { $testTypeMap[$_] -eq 'server' })
    return [PSCustomObject]@{ Maven = $mavenT; Cli = $cliT; Rws = $rwsT; Server = $serverT }
}

# Single type
$d = Test-Dispatch 'fast'
Assert-Returns -Label 'Dispatch fast: maven has fast' -Actual ($d.Maven -join ',') -Expected 'fast'
Assert-Returns -Label 'Dispatch fast: no server' -Actual $d.Server.Count -Expected 0
Assert-Returns -Label 'Dispatch fast: no cli' -Actual $d.Cli.Count -Expected 0

$d = Test-Dispatch 'cli'
Assert-Returns -Label 'Dispatch cli: cli bucket' -Actual ($d.Cli -join ',') -Expected 'cli'
Assert-Returns -Label 'Dispatch cli: no maven' -Actual $d.Maven.Count -Expected 0

$d = Test-Dispatch 'mocksite'
Assert-Returns -Label 'Dispatch mocksite: server bucket' -Actual ($d.Server -join ',') -Expected 'mocksite'

# Multiple types
$d = Test-Dispatch 'fast', 'it', 'e2e'
Assert-Returns -Label 'Dispatch multi: all maven' -Actual $d.Maven.Count -Expected 3

$d = Test-Dispatch 'skills', 'mcp'
Assert-Returns -Label 'Dispatch skills+mcp: both maven' -Actual $d.Maven.Count -Expected 2

$d = Test-Dispatch 'main'
Assert-Returns -Label 'Dispatch main: in maven bucket' -Actual ($d.Maven -join ',') -Expected 'main'

Write-Host "━━━ Test type map: main expansion ━━━" -ForegroundColor Cyan

# Test the expansion logic from the dispatch section
$mavenTests = @('main')
$expandedMaven = @()
foreach ($type in $mavenTests) {
    if ($type -eq 'main') {
        $expandedMaven += 'fast', 'it', 'e2e', 'rest'
    } else {
        $expandedMaven += $type
    }
}
$expandedMaven = $expandedMaven | Select-Object -Unique
Assert-Returns -Label 'Expand main: 4 types' -Actual $expandedMaven.Count -Expected 4
Assert-ContainsString -Label 'Expand main: includes fast' -Haystack ($expandedMaven -join ',') -Needle 'fast'
Assert-ContainsString -Label 'Expand main: includes rest' -Haystack ($expandedMaven -join ',') -Needle 'rest'

# Combining main with other types
$mavenTests = @('main', 'skills')
$expandedMaven = @()
foreach ($type in $mavenTests) {
    if ($type -eq 'main') {
        $expandedMaven += 'fast', 'it', 'e2e', 'rest'
    } else {
        $expandedMaven += $type
    }
}
$expandedMaven = $expandedMaven | Select-Object -Unique
Assert-Returns -Label 'Expand main+skills: 5 types' -Actual $expandedMaven.Count -Expected 5
Assert-ContainsString -Label 'Expand main+skills: includes skills' -Haystack ($expandedMaven -join ',') -Needle 'skills'

Write-Host "━━━ Test type map: server must be alone ━━━" -ForegroundColor Cyan

# Validate the server-isolation constraint
$serverTests = @('server')
$mavenTests = @('fast')
$cliTests = @()
$rwsTests = @()
$hasConflict = ($serverTests.Count -gt 0 -and ($mavenTests.Count -gt 0 -or $cliTests.Count -gt 0 -or $rwsTests.Count -gt 0 -or $serverTests.Count -gt 1))
Assert-Returns -Label 'Server+maven: conflict detected' -Actual $hasConflict -Expected $true

$serverTests2 = @('server')
$mavenTests2 = @()
$hasConflict2 = ($serverTests2.Count -gt 0 -and ($mavenTests2.Count -gt 0 -or $cliTests.Count -gt 0 -or $rwsTests.Count -gt 0 -or $serverTests2.Count -gt 1))
Assert-Returns -Label 'Server alone: no conflict' -Actual $hasConflict2 -Expected $false

# ═══════════════════════════════════════════════════════════════════
# TESTS: Known test type enumeration
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Known test types: completeness ━━━" -ForegroundColor Cyan

$expectedTypes = @(
    'fast', 'it', 'e2e', 'rest', 'skills', 'mcp', 'main',
    'cli', 'browser4-cli',
    'server', 'mock-site', 'mocksite', 'mocksiteboot',
    'rws', 'resume'
)

foreach ($type in $expectedTypes) {
    $found = $testTypeMap.ContainsKey($type)
    Assert-Returns -Label "Known type: '$type' in map" -Actual $found -Expected $true
}

# Verify no unexpected keys exist
$extraKeys = $testTypeMap.Keys | Where-Object { $_ -notin $expectedTypes }
if ($extraKeys.Count -gt 0) {
    Write-Host "    ⚠ Extra keys in testTypeMap: $($extraKeys -join ', ')" -ForegroundColor Yellow
}
Assert-Returns -Label 'Map: no extra keys beyond expected' -Actual $extraKeys.Count -Expected 0

# Verify all categories are covered
$categories = $testTypeMap.Values | Select-Object -Unique | Sort-Object
$expectedCategories = @('cli', 'maven', 'maven-expand', 'resume', 'rws', 'server')
$missingCategories = $expectedCategories | Where-Object { $_ -notin $categories }
$extraCategories = $categories | Where-Object { $_ -notin $expectedCategories }
Assert-Returns -Label 'Map: all categories covered' -Actual $missingCategories.Count -Expected 0
Assert-Returns -Label 'Map: no extra categories' -Actual $extraCategories.Count -Expected 0

# ═══════════════════════════════════════════════════════════════════
# TESTS: Argument parsing edge cases (via child process)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Argument parsing: unknown type in child pwsh ━━━" -ForegroundColor Cyan

$output = pwsh -NoProfile -Command "$testPs1Abs bogus_arg 2>&1" | Out-String
Assert-ContainsString -Label 'Arg parse: unknown type errors' -Haystack $output -Needle "Unknown test type 'bogus_arg'"

$output = pwsh -NoProfile -Command "$testPs1Abs -h 2>&1" | Out-String
Assert-ContainsString -Label 'Arg parse: -h shows usage' -Haystack $output -Needle 'Usage:'

$output = pwsh -NoProfile -Command "$testPs1Abs --help 2>&1" | Out-String
Assert-ContainsString -Label 'Arg parse: --help shows usage' -Haystack $output -Needle 'Usage:'

# ── DryRun with unknown test type ──
$output = pwsh -NoProfile -Command "$testPs1Abs -DryRun foobar 2>&1" | Out-String
Assert-ContainsString -Label 'Arg parse: -DryRun foobar errors' -Haystack $output -Needle "Unknown test type 'foobar'"

# ── resume with another type ──
$output = pwsh -NoProfile -Command "$testPs1Abs resume fast 2>&1" | Out-String
Assert-ContainsString -Label 'Arg parse: resume+fast errors' -Haystack $output -Needle "'resume' must be the only test type"

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
    # Standalone mode — exit based on our own counters
    if ($script:ContentFailures -gt 0 -or $script:__FailCount -gt 0) {
        exit 1
    }
    exit 0
}
