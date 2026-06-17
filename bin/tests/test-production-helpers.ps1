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
    Unit tests for helper functions defined in test-production.ps1.

.DESCRIPTION
    Extracts pure helper functions from test-production.ps1 via PowerShell's
    AST parser and tests them in isolation -- no browser4-cli installation,
    no network access, no side effects.

    Covers: Assert-OutputContains, Assert-ExitOk, Write-StepResult,
            Get-RuntimeBundleDir, Update-SessionPath, Resolve-CliPath.

    Run standalone:
        pwsh bin/tests/test-production-helpers.ps1

    Run via runner:
        pwsh bin/tests/run-tests.ps1 test-production-helpers
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'test-production-helpers'

$CliBin = Get-CliBin  # printed for diagnostic purposes
Write-Host "Using CLI: $CliBin" -ForegroundColor DarkGray

Write-TestHeader -Name 'test-production-helpers'

# -------------------------------------------------------------------
# Track additional content-based assertions
# -------------------------------------------------------------------
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
# Helper: extract function definitions from test-production.ps1
# via the PowerShell AST parser.  This avoids executing the full
# acceptance test while still testing the exact current source.
# -------------------------------------------------------------------
function Get-FunctionDefinitionsFromScript {
    param([string]$ScriptPath)

    if (-not (Test-Path $ScriptPath)) {
        Write-Host "ERROR: test-production.ps1 not found at: $ScriptPath" -ForegroundColor Red
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

    # FindAll with $true = searchNestedFunctions so helpers inside other
    # functions are also extracted (though test-production.ps1 has none).
    $functionDefs = $ast.FindAll({
        param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
    }, $true)

    if ($functionDefs.Count -eq 0) {
        Write-Host "WARNING: No function definitions found in $ScriptPath" -ForegroundColor Yellow
    }

    Write-Host "Extracted $($functionDefs.Count) function definitions from test-production.ps1" -ForegroundColor DarkGray

    return ($functionDefs | ForEach-Object { $_.Extent.Text }) -join "`n`n"
}

# -------------------------------------------------------------------
# Set up script-scoped variables that the extracted functions reference.
# These mirror lines 108-176 of test-production.ps1 exactly.
# -------------------------------------------------------------------

# OS detection (mirrors lines 108-121 of test-production.ps1)
if ($PSVersionTable.PSVersion.Major -ge 6) {
    $script:OSWin   = $IsWindows
    $script:OSLinux = $IsLinux
    $script:OSMac   = $IsMacOS
} else {
    $script:OSWin   = [System.Environment]::OSVersion.Platform -eq 'Win32NT'
    $script:OSMac   = $false
    $script:OSLinux = $false
}

# Platform-specific paths (mirrors lines 130-146)
$script:TempDir = if ($env:TEMP) {
    $env:TEMP
} elseif ($env:TMPDIR) {
    $env:TMPDIR
} else {
    '/tmp'
}
$script:LocalAppData = if ($env:LOCALAPPDATA) {
    $env:LOCALAPPDATA
} else {
    Join-Path $env:HOME '.local'
}
$script:AppData = if ($env:APPDATA) {
    $env:APPDATA
} else {
    $env:HOME
}

# Constants (mirrors lines 151-169)
$InstallPs1Url   = 'https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1'
$InstallShUrl    = 'https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh'
$Browser4Home    = if ($script:OSWin) { Join-Path $env:USERPROFILE '.browser4' } else { Join-Path $env:HOME '.browser4' }
$ServerBaseUrl   = 'http://localhost:8182'
$ServerHealthUrl = "$ServerBaseUrl/actuator/health"

# Runtime data directory (mirrors lines 158-169)
$RuntimeDataDir = if ($script:OSWin) {
    Join-Path $script:AppData 'browser4'
} elseif ($script:OSMac) {
    Join-Path $env:HOME 'Library/Application Support/browser4'
} else {
    if ($env:XDG_DATA_HOME) {
        Join-Path $env:XDG_DATA_HOME 'browser4'
    } else {
        Join-Path $env:HOME '.local/share/browser4'
    }
}

# State tracking counters (mirrors lines 174-176)
$script:TotalSteps  = 0
$script:PassedSteps = 0
$script:FailedSteps = 0

# -------------------------------------------------------------------
# Resolve the path to test-production.ps1 and extract function defs
# -------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TestProductionPath = Join-Path $ScriptDir '..\test-production.ps1'

Write-Host "Source : $TestProductionPath" -ForegroundColor DarkGray
Write-Host "OS     : Win=$($script:OSWin) Linux=$($script:OSLinux) Mac=$($script:OSMac)" -ForegroundColor DarkGray

$functionText = Get-FunctionDefinitionsFromScript -ScriptPath $TestProductionPath
Invoke-Expression $functionText

Write-Host ''

# -------------------------------------------------------------------
# Helper: reset step counters between Write-StepResult test cases
# -------------------------------------------------------------------
function Reset-Counters {
    $script:TotalSteps  = 0
    $script:PassedSteps = 0
    $script:FailedSteps = 0
}

# -------------------------------------------------------------------
# Helper: assert a function returns an expected value and register
# the result with the test framework.
# -------------------------------------------------------------------
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
    Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed $sw.Elapsed

    if (-not $passed) {
        Write-Host "    ❌ $Label — expected '$Expected', got '$Actual'" -ForegroundColor Red
        $script:ContentFailures++
    } else {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    }
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: Assert-OutputContains
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Assert-OutputContains: basic matching ━━━" -ForegroundColor Cyan

$actual = Assert-OutputContains -Output 'hello world' -Pattern 'hello'
Assert-Returns -Label 'pattern at start' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'say hello world' -Pattern 'hello'
Assert-Returns -Label 'pattern in middle' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'goodbye hello' -Pattern 'hello'
Assert-Returns -Label 'pattern at end' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'hello world' -Pattern 'xyz'
Assert-Returns -Label 'pattern absent' -Actual $actual -Expected $false

$actual = Assert-OutputContains -Output 'hello world' -Pattern 'hello world'
Assert-Returns -Label 'pattern equals entire output' -Actual $actual -Expected $true

Write-Host "━━━ Assert-OutputContains: empty / null edge cases ━━━" -ForegroundColor Cyan

$actual = Assert-OutputContains -Output '' -Pattern 'x'
Assert-Returns -Label 'empty output, non-empty pattern' -Actual $actual -Expected $false

$actual = Assert-OutputContains -Output '' -Pattern ''
Assert-Returns -Label 'both empty' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output $null -Pattern 'x'
Assert-Returns -Label 'null output' -Actual $actual -Expected $false

Write-Host "━━━ Assert-OutputContains: regex meta-character escaping ━━━" -ForegroundColor Cyan

# [regex]::Escape must treat these as literals, not regex operators.
$actual = Assert-OutputContains -Output 'price is 5.99' -Pattern '5.99'
Assert-Returns -Label 'dot is literal (not "any char")' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'a+b*c' -Pattern 'a+b*c'
Assert-Returns -Label 'plus and star are literal' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'file [v1].txt' -Pattern '[v1]'
Assert-Returns -Label 'square brackets are literal' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output '(test) {1,2}' -Pattern '(test) {1,2}'
Assert-Returns -Label 'parentheses and braces are literal' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'cost ^ 2 = 4' -Pattern '^'
Assert-Returns -Label 'caret is literal (not start anchor)' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'a|b|c' -Pattern 'a|b'
Assert-Returns -Label 'pipe is literal (not alternation)' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'C:\Users\test' -Pattern 'C:\Users\test'
Assert-Returns -Label 'backslash is literal' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'costs $5.00' -Pattern '$5.00'
Assert-Returns -Label 'dollar sign is literal (not end anchor)' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'ready?' -Pattern 'ready?'
Assert-Returns -Label 'question mark is literal (not optional quantifier)' -Actual $actual -Expected $true

Write-Host "━━━ Assert-OutputContains: multi-line / long output ━━━" -ForegroundColor Cyan

$actual = Assert-OutputContains -Output "line1`nline2`nline3" -Pattern 'line2'
Assert-Returns -Label 'multi-line output' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output "line1`nline2`nline3" -Pattern "line2`nline3"
Assert-Returns -Label 'multi-line pattern' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output ('x' * 600) -Pattern 'notfound'
Assert-Returns -Label 'output >500 chars, pattern not present' -Actual $actual -Expected $false

Write-Host "━━━ Assert-OutputContains: case sensitivity ━━━" -ForegroundColor Cyan

# PowerShell -match is case-insensitive by default.
$actual = Assert-OutputContains -Output 'hello world' -Pattern 'HELLO'
Assert-Returns -Label 'case-insensitive match (lowercase output, uppercase pattern)' -Actual $actual -Expected $true

$actual = Assert-OutputContains -Output 'HELLO WORLD' -Pattern 'hello'
Assert-Returns -Label 'case-insensitive match (uppercase output, lowercase pattern)' -Actual $actual -Expected $true

# ═══════════════════════════════════════════════════════════════════
# TESTS: Assert-ExitOk
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Assert-ExitOk ━━━" -ForegroundColor Cyan

$actual = Assert-ExitOk 0
Assert-Returns -Label 'exit code 0 returns true' -Actual $actual -Expected $true

$actual = Assert-ExitOk 1
Assert-Returns -Label 'exit code 1 returns false' -Actual $actual -Expected $false

$actual = Assert-ExitOk (-1)
Assert-Returns -Label 'exit code -1 returns false' -Actual $actual -Expected $false

$actual = Assert-ExitOk 255
Assert-Returns -Label 'exit code 255 returns false' -Actual $actual -Expected $false

# Note: [int] type constraint coerces $null to 0, so $null is treated as OK.
# In practice, exit codes from process objects are never $null.
$actual = Assert-ExitOk $null
Assert-Returns -Label 'null exit code coerces to 0 → returns true' -Actual $actual -Expected $true

# ═══════════════════════════════════════════════════════════════════
# TESTS: Write-StepResult (counter behavior)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Write-StepResult: single pass ━━━" -ForegroundColor Cyan

Reset-Counters
$out = Write-StepResult -Step 'alpha' -Passed $true -Detail 'ok'
Assert-Returns -Label 'single pass: TotalSteps=1' -Actual $script:TotalSteps -Expected 1
Assert-Returns -Label 'single pass: PassedSteps=1' -Actual $script:PassedSteps -Expected 1
Assert-Returns -Label 'single pass: FailedSteps=0' -Actual $script:FailedSteps -Expected 0

Write-Host "━━━ Write-StepResult: single fail ━━━" -ForegroundColor Cyan

Reset-Counters
$out = Write-StepResult -Step 'beta' -Passed $false -Detail 'fail'
Assert-Returns -Label 'single fail: TotalSteps=1' -Actual $script:TotalSteps -Expected 1
Assert-Returns -Label 'single fail: PassedSteps=0' -Actual $script:PassedSteps -Expected 0
Assert-Returns -Label 'single fail: FailedSteps=1' -Actual $script:FailedSteps -Expected 1

Write-Host "━━━ Write-StepResult: accumulation ━━━" -ForegroundColor Cyan

Reset-Counters
$null = Write-StepResult -Step 'a' -Passed $true
$null = Write-StepResult -Step 'b' -Passed $true
$null = Write-StepResult -Step 'c' -Passed $false
$null = Write-StepResult -Step 'd' -Passed $true
$null = Write-StepResult -Step 'e' -Passed $false
Assert-Returns -Label 'accumulation: TotalSteps=5' -Actual $script:TotalSteps -Expected 5
Assert-Returns -Label 'accumulation: PassedSteps=3' -Actual $script:PassedSteps -Expected 3
Assert-Returns -Label 'accumulation: FailedSteps=2' -Actual $script:FailedSteps -Expected 2

Write-Host "━━━ Write-StepResult: edge cases ━━━" -ForegroundColor Cyan

Reset-Counters
$out = Write-StepResult -Step 'gamma' -Passed $true -Detail ''
Assert-Returns -Label 'empty detail: TotalSteps=1' -Actual $script:TotalSteps -Expected 1
Assert-Returns -Label 'empty detail: PassedSteps=1' -Actual $script:PassedSteps -Expected 1

Reset-Counters
$out = Write-StepResult -Step 'delta' -Passed $true
# When Detail is omitted, param $Detail defaults to '' (not $null, due to [string] type)
Assert-Returns -Label 'no detail param: TotalSteps=1' -Actual $script:TotalSteps -Expected 1
Assert-Returns -Label 'no detail param: PassedSteps=1' -Actual $script:PassedSteps -Expected 1

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-RuntimeBundleDir
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-RuntimeBundleDir: directory discovery ━━━" -ForegroundColor Cyan

# We test by creating a controlled temp tree and overriding $Browser4Home.
# Each test case gets its own isolated temp directory.
function New-TestHome {
    param([scriptblock]$Setup)
    $testRoot = Join-Path ([System.IO.Path]::GetTempPath()) "b4-test-grbd-$([System.IO.Path]::GetRandomFileName())"
    $null = New-Item -Path $testRoot -ItemType Directory -Force
    try {
        & $Setup $testRoot
        $originalHome = $Browser4Home
        $originalAppData = $script:AppData
        $originalLocalAppData = $script:LocalAppData
        $originalOSWin = $script:OSWin
        # Override paths so Get-RuntimeBundleDir searches the test tree.
        # Force Windows search path to exercise both roots on all platforms.
        Set-Variable -Scope script -Name Browser4Home -Value $testRoot
        Set-Variable -Scope script -Name AppData     -Value $testRoot
        Set-Variable -Scope script -Name LocalAppData -Value $testRoot
        Set-Variable -Scope script -Name OSWin       -Value $true  # enable multi-root search
        try {
            $result = Get-RuntimeBundleDir
            return @{ Root = $testRoot; Result = $result }
        } finally {
            Set-Variable -Scope script -Name Browser4Home -Value $originalHome
            Set-Variable -Scope script -Name AppData      -Value $originalAppData
            Set-Variable -Scope script -Name LocalAppData -Value $originalLocalAppData
            Set-Variable -Scope script -Name OSWin        -Value $originalOSWin
        }
    } catch {
        # Best-effort cleanup on failure
        Remove-Item $testRoot -Recurse -Force -ErrorAction SilentlyContinue
        throw
    }
}

# Case 1: No home directory at all
$r = New-TestHome -Setup { param($root) }
Remove-Item $r.Root -Recurse -Force -ErrorAction SilentlyContinue
Assert-Returns -Label 'GRBD: no home dir → null' -Actual $r.Result -Expected $null

# Case 2: Home dir exists but empty
$r = New-TestHome -Setup { param($root) { } }
Assert-Returns -Label 'GRBD: empty home dir → null' -Actual $r.Result -Expected $null
Remove-Item $r.Root -Recurse -Force -ErrorAction SilentlyContinue

# Case 3: browser4-bundle dir exists but no Browser4Bundle.jar inside
$r = New-TestHome -Setup {
    param($root)
    $null = New-Item -Path (Join-Path $root 'runtime\browser4-bundle') -ItemType Directory -Force
}
Assert-Returns -Label 'GRBD: bundle dir without jar → null' -Actual $r.Result -Expected $null
Remove-Item $r.Root -Recurse -Force -ErrorAction SilentlyContinue

# Case 4: browser4-bundle dir with Browser4Bundle.jar — found
$r = New-TestHome -Setup {
    param($root)
    $bundleDir = Join-Path $root 'runtime\browser4-bundle'
    $null = New-Item -Path $bundleDir -ItemType Directory -Force
    $null = New-Item -Path (Join-Path $bundleDir 'Browser4Bundle.jar') -ItemType File -Force
}
$found = ($null -ne $r.Result) -and $r.Result.EndsWith('browser4-bundle')
Assert-Returns -Label 'GRBD: bundle dir with jar → path' -Actual $found -Expected $true
Remove-Item $r.Root -Recurse -Force -ErrorAction SilentlyContinue

# Case 5: Two nested browser4-bundle dirs, only one has the jar
$r = New-TestHome -Setup {
    param($root)
    $emptyDir = Join-Path $root 'a\browser4-bundle'
    $fullDir  = Join-Path $root 'b\browser4-bundle'
    $null = New-Item -Path $emptyDir -ItemType Directory -Force
    $null = New-Item -Path $fullDir  -ItemType Directory -Force
    $null = New-Item -Path (Join-Path $fullDir 'Browser4Bundle.jar') -ItemType File -Force
}
$found = ($null -ne $r.Result) -and $r.Result.EndsWith('browser4-bundle')
Assert-Returns -Label 'GRBD: finds bundle with jar among multiple' -Actual $found -Expected $true
Remove-Item $r.Root -Recurse -Force -ErrorAction SilentlyContinue

# Case 6: Bundle is deep (nested several levels)
$r = New-TestHome -Setup {
    param($root)
    $bundleDir = Join-Path $root 'x\y\z\browser4-bundle'
    $null = New-Item -Path $bundleDir -ItemType Directory -Force
    $null = New-Item -Path (Join-Path $bundleDir 'Browser4Bundle.jar') -ItemType File -Force
}
$found = ($null -ne $r.Result) -and $r.Result.EndsWith('browser4-bundle')
Assert-Returns -Label 'GRBD: finds deeply nested bundle' -Actual $found -Expected $true
Remove-Item $r.Root -Recurse -Force -ErrorAction SilentlyContinue

# Case 7: Browser4Bundle.jar is a directory, not a file
# Test-Path returns $true for directories too, so this is a doc-edge-case.
$r = New-TestHome -Setup {
    param($root)
    $bundleDir = Join-Path $root 'runtime\browser4-bundle'
    $null = New-Item -Path $bundleDir -ItemType Directory -Force
    # Create Browser4Bundle.jar as a directory (not a file)
    $null = New-Item -Path (Join-Path $bundleDir 'Browser4Bundle.jar') -ItemType Directory -Force
}
$found = ($null -ne $r.Result) -and $r.Result.EndsWith('browser4-bundle')
Assert-Returns -Label 'GRBD: jar-is-directory (finds it — Test-Path is true for dirs)' -Actual $found -Expected $true
Remove-Item $r.Root -Recurse -Force -ErrorAction SilentlyContinue

# ═══════════════════════════════════════════════════════════════════
# TESTS: Update-SessionPath
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Update-SessionPath ━━━" -ForegroundColor Cyan

if ($script:OSWin) {
    Write-Host "    ℹ️  Skipping Update-SessionPath detailed tests on Windows (registry-dependent)" -ForegroundColor DarkGray
    # Smoke test: call it and verify it doesn't crash
    $originalPath = $env:Path
    try {
        Update-SessionPath
        Assert-Returns -Label 'USP: does not crash on Windows' -Actual $true -Expected $true
    } catch {
        Assert-Returns -Label "USP: crashed on Windows: $_" -Actual $false -Expected $true
    }
    $env:Path = $originalPath
} else {
    # Unix: test adding ~/.local/bin to PATH
    $localBin = Join-Path $env:HOME '.local/bin'
    $originalPath = $env:Path
    try {
        # Case 1: ~/.local/bin exists but not in PATH
        $null = New-Item -Path $localBin -ItemType Directory -Force -ErrorAction SilentlyContinue
        # Remove ~/.local/bin from PATH if present
        $cleanPath = ($env:Path -split [System.IO.Path]::PathSeparator |
            Where-Object { $_ -ne $localBin }) -join [System.IO.Path]::PathSeparator
        $env:Path = $cleanPath
        Update-SessionPath
        $hasLocalBin = ($env:Path -split [System.IO.Path]::PathSeparator) -contains $localBin
        Assert-Returns -Label 'USP: adds ~/.local/bin when exists and not in PATH' -Actual $hasLocalBin -Expected $true
    } finally {
        $env:Path = $originalPath
    }

    # Case 2: ~/.local/bin already in PATH — no duplicate
    $originalPath = $env:Path
    try {
        $null = New-Item -Path $localBin -ItemType Directory -Force -ErrorAction SilentlyContinue
        $env:Path = "$localBin$([System.IO.Path]::PathSeparator)$originalPath"
        $countBefore = ($env:Path -split [System.IO.Path]::PathSeparator |
            Where-Object { $_ -eq $localBin }).Count
        Update-SessionPath
        $countAfter = ($env:Path -split [System.IO.Path]::PathSeparator |
            Where-Object { $_ -eq $localBin }).Count
        $notDuped = $countAfter -eq $countBefore
        Assert-Returns -Label 'USP: does not duplicate ~/.local/bin' -Actual $notDuped -Expected $true
    } finally {
        $env:Path = $originalPath
    }

    # Case 3: ~/.local/bin does not exist — PATH unchanged
    $originalPath = $env:Path
    try {
        Remove-Item $localBin -Recurse -Force -ErrorAction SilentlyContinue
        $env:Path = ($env:Path -split [System.IO.Path]::PathSeparator |
            Where-Object { $_ -ne $localBin }) -join [System.IO.Path]::PathSeparator
        $pathBefore = $env:Path
        Update-SessionPath
        $unchanged = $env:Path -eq $pathBefore
        Assert-Returns -Label 'USP: PATH unchanged when ~/.local/bin missing' -Actual $unchanged -Expected $true
    } finally {
        $env:Path = $originalPath
    }
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: Resolve-CliPath
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Resolve-CliPath ━━━" -ForegroundColor Cyan

# Test that Resolve-CliPath returns $null when browser4-cli is NOT on PATH.
# We do this by temporarily stripping PATH entries that contain browser4-cli.
$originalPath = $env:Path
try {
    # Remove any path entries that contain browser4-cli
    $strippedEntries = ($env:Path -split [System.IO.Path]::PathSeparator) |
        Where-Object {
            $entry = $_
            if (-not $entry) { return $false }
            $cliCandidate = Join-Path $entry 'browser4-cli'
            $cliCandidateExe = Join-Path $entry 'browser4-cli.exe'
            $cliCandidateCmd = Join-Path $entry 'browser4-cli.cmd'
            -not ((Test-Path $cliCandidate) -or (Test-Path $cliCandidateExe) -or (Test-Path $cliCandidateCmd))
        }
    $env:Path = ($strippedEntries -join [System.IO.Path]::PathSeparator)
    $result = Resolve-CliPath
    Assert-Returns -Label 'RCP: returns null when CLI not on PATH' -Actual $result -Expected $null
} finally {
    $env:Path = $originalPath
}

# Also smoke-test: if CLI IS available, it returns a non-null path.
$env:Path = $originalPath
$cliAvailable = $null -ne (Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue)
if ($cliAvailable) {
    $result = Resolve-CliPath
    $found = $null -ne $result
    Assert-Returns -Label 'RCP: returns non-null when CLI is on PATH' -Actual $found -Expected $true
} else {
    Write-Host "    ℹ️  browser4-cli not on PATH — skipping positive case for Resolve-CliPath" -ForegroundColor DarkGray
}

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
Write-Host ''
$exitCode = Finish-TestSession
exit $exitCode
