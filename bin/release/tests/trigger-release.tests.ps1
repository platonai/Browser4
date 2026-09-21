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
    Unit tests for the non-interactive confirmation helpers in
    bin/release/trigger-release.ps1.

.DESCRIPTION
    Extracts Get-NonInteractiveFlag and Confirm-Step via PowerShell's AST
    parser and tests them in isolation — no git, no gh, no network.

    Covers: the canonical BROWSER4_RELEASE_YES switch, the legacy
            BROWSER4_RELEASE_ASSUME_YES alias (the name bin/release/README.md
            documented for several releases after the rename — automation
            written against those docs must keep working instead of falling
            through to Read-Host and dying in a NonInteractive host),
            precedence when both names are set, auto-skip of the optional
            release-message prompt, and delegation to Read-Host when no flag
            is set.

    Also guards the documentation itself: README.md has to name the canonical
    switch, and may only mention the alias as a legacy name. The regression
    this file exists for was a silent doc/implementation drift.

    Run standalone:
        pwsh bin/release/tests/trigger-release.tests.ps1

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
$TestUtilsModule = Join-Path $ScriptDir '..\..\..\browser4-tests\tests-production\test-utils.psm1'
$TriggerScriptPath = Join-Path $ScriptDir '..\trigger-release.ps1'
$ReadmePath = Join-Path $ScriptDir '..\README.md'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
if (Test-Path $TestUtilsModule) {
    Import-Module $TestUtilsModule -Force
    Start-TestSession -Name 'trigger-release-helpers'
    Write-TestHeader -Name 'trigger-release-helpers'
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
    $passed = ($Actual -eq $Expected) -or
              ($null -eq $Actual -and $null -eq $Expected)
    $exitCode = if ($passed) { 0 } else { 1 }
    $detail = if ($Description) { $Description } else { "expected=$Expected actual=$Actual" }
    if ($passed) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        Write-Host "    ❌ $Label — $detail" -ForegroundColor Red
        $script:ContentFailures++
    }
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
        Write-Host "    ❌ $Label — should match pattern '$Pattern'" -ForegroundColor Red
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
# Extract function definitions from trigger-release.ps1 via AST parser
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
# Environment isolation
# -------------------------------------------------------------------
# The flags are process-wide, so every case sets exactly the names it needs
# and the runner's own environment is restored before the process exits.
$script:FlagNames = @('BROWSER4_RELEASE_YES', 'BROWSER4_RELEASE_ASSUME_YES')
$script:SavedFlags = @{}
foreach ($name in $script:FlagNames) {
    $script:SavedFlags[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Clear-ReleaseFlags {
    foreach ($name in $script:FlagNames) {
        Remove-Item -Path "env:$name" -ErrorAction SilentlyContinue
    }
}

function Set-ReleaseFlag {
    param([string]$Name, [string]$Value)
    Set-Item -Path "env:$Name" -Value $Value
}

# Confirm-Step falls back to Read-Host when no flag is set. The interactive
# branch is covered by shadowing the cmdlet with a script-scoped function
# (function lookup happens before the cmdlet lookup), so the test never blocks
# on stdin.
$script:MockReadHostResult = 'n'
function Read-Host {
    param([string]$Prompt)
    Write-Host "    (mock Read-Host: '$Prompt' -> '$($script:MockReadHostResult)')" -ForegroundColor DarkGray
    return $script:MockReadHostResult
}

# -------------------------------------------------------------------
# Load functions
# -------------------------------------------------------------------
Write-Host "Source : $TriggerScriptPath" -ForegroundColor DarkGray

$funcText = Get-FunctionsFromScript -ScriptPath $TriggerScriptPath `
    -FunctionNames @('Get-NonInteractiveFlag', 'Confirm-Step')

Invoke-Expression $funcText

# Loud guard: if a function is renamed or moved, every assertion below would
# either error out or silently disappear. Fail here, with the reason named.
Assert-Returns -Label 'LOAD: Get-NonInteractiveFlag extracted from the script' `
    -Actual ([bool](Get-Command 'Get-NonInteractiveFlag' -ErrorAction SilentlyContinue)) -Expected $true
Assert-Returns -Label 'LOAD: Confirm-Step extracted from the script' `
    -Actual ([bool](Get-Command 'Confirm-Step' -ErrorAction SilentlyContinue)) -Expected $true

Write-Host ''

# ===================================================================
# TESTS: Get-NonInteractiveFlag (name resolution)
# ===================================================================
Write-Host "━━━ Get-NonInteractiveFlag: canonical name, alias, precedence ━━━" -ForegroundColor Cyan

Clear-ReleaseFlags
Assert-Returns -Label 'FLAG none: no flag set => empty (interactive)' `
    -Actual (Get-NonInteractiveFlag) -Expected ''

Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value '1'
Assert-Returns -Label 'FLAG canonical: BROWSER4_RELEASE_YES=1 is reported as canonical' `
    -Actual (Get-NonInteractiveFlag) -Expected 'BROWSER4_RELEASE_YES'

Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_ASSUME_YES' -Value '1'
Assert-Returns -Label 'FLAG alias: legacy BROWSER4_RELEASE_ASSUME_YES is still honoured' `
    -Actual (Get-NonInteractiveFlag) -Expected 'BROWSER4_RELEASE_ASSUME_YES'

# Both set: the canonical name is the one reported (it decides the semantics).
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value '1'
Assert-Returns -Label 'FLAG precedence: canonical wins when both are set' `
    -Actual (Get-NonInteractiveFlag) -Expected 'BROWSER4_RELEASE_YES'

# Truthy values other than '1' behave like the documented '=1' (any non-empty
# string is on) — existing automation setting "true"/"yes" must not regress.
Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value 'true'
Assert-Returns -Label 'FLAG canonical: any non-empty value is on' `
    -Actual (Get-NonInteractiveFlag) -Expected 'BROWSER4_RELEASE_YES'

Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_ASSUME_YES' -Value 'yes'
Assert-Returns -Label 'FLAG alias: any non-empty value is on' `
    -Actual (Get-NonInteractiveFlag) -Expected 'BROWSER4_RELEASE_ASSUME_YES'

# Empty strings are off (an explicitly blank variable must not auto-confirm).
Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value ''
Assert-Returns -Label 'FLAG canonical: empty value => off' `
    -Actual (Get-NonInteractiveFlag) -Expected ''
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_ASSUME_YES' -Value ''
Assert-Returns -Label 'FLAG alias: empty value => off' `
    -Actual (Get-NonInteractiveFlag) -Expected ''

# ===================================================================
# TESTS: Confirm-Step (prompt behaviour)
# ===================================================================
Write-Host "━━━ Confirm-Step: auto-confirm, auto-skip, interactive fallback ━━━" -ForegroundColor Cyan

Clear-ReleaseFlags

# No flag: the interactive path is delegated to Read-Host.
$script:MockReadHostResult = 'n'
Assert-Returns -Label 'STEP interactive: no flag delegates to Read-Host (n)' `
    -Actual (Confirm-Step 'Continue anyway? (y/n)') -Expected 'n'
$script:MockReadHostResult = 'y'
Assert-Returns -Label 'STEP interactive: no flag delegates to Read-Host (y)' `
    -Actual (Confirm-Step 'Continue anyway? (y/n)') -Expected 'y'

# Canonical flag: yes/no prompts auto-confirm without touching Read-Host.
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value '1'
$script:MockReadHostResult = 'n'   # must be ignored
Assert-Returns -Label 'STEP canonical: y/n prompt auto-confirms with y' `
    -Actual (Confirm-Step 'Continue anyway? (y/n)') -Expected 'y'

# Legacy alias: the documented-then-renamed name has to behave identically,
# otherwise automation written against README would hit Read-Host and throw.
Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_ASSUME_YES' -Value '1'
Assert-Returns -Label 'STEP alias: y/n prompt auto-confirms with y' `
    -Actual (Confirm-Step 'Continue anyway? (y/n)') -Expected 'y'
Assert-Returns -Label 'STEP alias: prompt with a default returns the default' `
    -Actual (Confirm-Step 'Enter release message (optional, press Enter to skip)' 'skip-me') -Expected 'skip-me'

# The release-message prompt passes an EMPTY default and must auto-skip to it.
# Regression: testing the default for truthiness instead of testing that the
# parameter was bound returned 'y', which annotated the tag with a literal "y"
# and prepended that to the GitHub release body.
Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value '1'
Assert-Returns -Label 'STEP canonical: empty default auto-skips to empty (lightweight tag)' `
    -Actual (Confirm-Step 'Enter release message (optional, press Enter to skip)' '') -Expected ''

Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_ASSUME_YES' -Value '1'
Assert-Returns -Label 'STEP alias: empty default auto-skips to empty (lightweight tag)' `
    -Actual (Confirm-Step 'Enter release message (optional, press Enter to skip)' '') -Expected ''

# An explicit default survives on both names.
Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value '1'
Assert-Returns -Label 'STEP canonical: explicit default is returned' `
    -Actual (Confirm-Step 'Enter release message' 'my notes') -Expected 'my notes'

# Several auto-confirmed prompts in a row stay consistent (regression: the
# flag used to be re-read per call, and a partially-set environment produced
# mixed answers within one release run).
Clear-ReleaseFlags
Set-ReleaseFlag -Name 'BROWSER4_RELEASE_YES' -Value '1'
$script:MockReadHostResult = 'n'
$seq = @(1..5 | ForEach-Object { Confirm-Step "Confirm step $_ ? (y/n)" })
Assert-Returns -Label 'STEP canonical: five prompts all auto-confirm' `
    -Actual (($seq | Where-Object { $_ -ne 'y' }).Count) -Expected 0

Clear-ReleaseFlags

# ===================================================================
# TESTS: documentation / implementation consistency
# ===================================================================
Write-Host "━━━ README/script: the documented switch is the implemented one ━━━" -ForegroundColor Cyan

$scriptErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile(
    $TriggerScriptPath, [ref]$null, [ref]$scriptErrors
)
Assert-Returns -Label 'DOC script: trigger-release.ps1 parses without syntax errors' `
    -Actual ($scriptErrors.Count -eq 0) -Expected $true

$scriptText = Get-Content -Path $TriggerScriptPath -Raw
$readmeText = Get-Content -Path $ReadmePath -Raw

Assert-Match -Label 'DOC script: reads the canonical BROWSER4_RELEASE_YES' `
    -Haystack $scriptText -Pattern '\$env:BROWSER4_RELEASE_YES'
Assert-Match -Label 'DOC README: documents BROWSER4_RELEASE_YES=1' `
    -Haystack $readmeText -Pattern 'BROWSER4_RELEASE_YES=1'

# The alias may only appear as a legacy name — never as the switch to use.
Assert-Match -Label 'DOC README: mentions the alias (kept for compatibility)' `
    -Haystack $readmeText -Pattern 'BROWSER4_RELEASE_ASSUME_YES'
$aliasLines = @($readmeText -split "`r?`n" | Where-Object { $_ -match 'BROWSER4_RELEASE_ASSUME_YES' })
$aliasLinesUnexplained = @($aliasLines | Where-Object { $_ -notmatch '(?i)legacy|pre-rename|alias' })
Assert-Returns -Label 'DOC README: every alias mention is labelled legacy/alias' `
    -Actual $aliasLinesUnexplained.Count -Expected 0 `
    -Description "unexplained lines: $($aliasLinesUnexplained -join ' | ')"

# The migration warning and the auto-skip contract are what make the alias
# safe to keep: both are asserted so a later cleanup cannot drop either.
Assert-Match -Label 'DOC script: legacy alias emits a migration warning' `
    -Haystack $scriptText -Pattern 'BROWSER4_RELEASE_ASSUME_YES is a legacy alias'
Assert-Match -Label 'DOC script: message prompt uses the presence test for auto-skip' `
    -Haystack $scriptText -Pattern 'PSBoundParameters\.ContainsKey\(''Default''\)'

# ===================================================================
# Summary
# ===================================================================
Write-Host ''
if ($script:ContentFailures -gt 0) {
    Write-Host "❌ $($script:ContentFailures) content assertion(s) failed." -ForegroundColor Red
}

# Restore the runner's environment before reporting, so a failure cannot leak
# the flags into a later test file in the same run.
foreach ($name in $script:FlagNames) {
    $saved = $script:SavedFlags[$name]
    if ([string]::IsNullOrEmpty($saved)) {
        Remove-Item -Path "env:$name" -ErrorAction SilentlyContinue
    } else {
        Set-Item -Path "env:$name" -Value $saved
    }
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
