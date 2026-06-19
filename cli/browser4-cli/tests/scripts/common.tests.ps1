#!/usr/bin/env pwsh
<#
.SYNOPSIS
Unit tests for common.ps1 — the shared helpers module for agent-scenario scripts.

.DESCRIPTION
Tests the three main pieces of common.ps1:
  1. Mode detection ($browser4cliMode → $helpCmd, $skillPath)
  2. $generalPrompt content and structure
  3. Invoke-Agent function signature and argument forwarding

.NOTES
Each test group runs in a clean scope by dot-sourcing common.ps1 in a script block.
This prevents test state from leaking between test groups.
#>

$ErrorActionPreference = 'Stop'

$script:TestsPassed  = 0
$script:TestsFailed  = 0
$script:TestsSkipped = 0

# ── Test helpers ───────────────────────────────────────────────────────────────

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] $Expected,
        [Parameter(Mandatory = $true)] $Actual
    )
    if ($Expected -eq $Actual) {
        Write-Host "    PASS: $Name" -ForegroundColor Green
        $script:TestsPassed++
    } else {
        Write-Host "    FAIL: $Name" -ForegroundColor Red
        Write-Host "      Expected: $Expected" -ForegroundColor Red
        Write-Host "      Actual:   $Actual" -ForegroundColor Red
        $script:TestsFailed++
    }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Haystack,
        [Parameter(Mandatory = $true)] [string] $Needle
    )
    if ($Haystack -match [regex]::Escape($Needle)) {
        Write-Host "    PASS: $Name" -ForegroundColor Green
        $script:TestsPassed++
    } else {
        Write-Host "    FAIL: $Name" -ForegroundColor Red
        Write-Host "      String does not contain expected text: $Needle" -ForegroundColor Red
        $script:TestsFailed++
    }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Haystack,
        [Parameter(Mandatory = $true)] [string] $Needle
    )
    if ($Haystack -notmatch [regex]::Escape($Needle)) {
        Write-Host "    PASS: $Name" -ForegroundColor Green
        $script:TestsPassed++
    } else {
        Write-Host "    FAIL: $Name" -ForegroundColor Red
        Write-Host "      String should NOT contain: $Needle" -ForegroundColor Red
        $script:TestsFailed++
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [bool] $Condition
    )
    if ($Condition) {
        Write-Host "    PASS: $Name" -ForegroundColor Green
        $script:TestsPassed++
    } else {
        Write-Host "    FAIL: $Name" -ForegroundColor Red
        $script:TestsFailed++
    }
}

function Assert-NotNullOrEmpty {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [string] $Value
    )
    if (-not [string]::IsNullOrEmpty($Value)) {
        Write-Host "    PASS: $Name" -ForegroundColor Green
        $script:TestsPassed++
    } else {
        Write-Host "    FAIL: $Name — value is null or empty" -ForegroundColor Red
        $script:TestsFailed++
    }
}

function Write-TestGroup {
    param([string] $Text)
    Write-Host ''
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host '  ' + ('-' * 60)
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 1: Mode detection — dev (default)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Mode Detection: Dev (default) ━━━' -ForegroundColor Yellow

& {
    # In dev mode, $browser4cliMode is not set.
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup '$helpCmd in dev mode'
    Assert-Equal 'is exactly "`cargo run -- help`" (with backticks)' `
        '`cargo run -- help`' $helpCmd
    Assert-Contains 'contains cargo run' $helpCmd 'cargo run'
    Assert-Contains 'contains help subcommand' $helpCmd 'help'

    Write-TestGroup '$skillPath in dev mode'
    Assert-Equal 'is exactly "`cli/skill/SKILL.md`" (with backticks)' `
        '`cli/skill/SKILL.md`' $skillPath
    Assert-Contains 'contains SKILL.md' $skillPath 'SKILL.md'
    Assert-Contains 'contains cli/skill' $skillPath 'cli/skill'
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 2: Mode detection — production
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Mode Detection: Production ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'production'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup '$helpCmd in production mode'
    Assert-Equal 'is exactly "`browser4-cli help`" (with backticks)' `
        '`browser4-cli help`' $helpCmd
    Assert-Contains 'contains browser4-cli' $helpCmd 'browser4-cli'
    Assert-Contains 'contains help subcommand' $helpCmd 'help'

    Write-TestGroup '$skillPath in production mode'
    Assert-Equal 'is exactly the remote URL' `
        'https://browser4.ioSKILL.md' $skillPath
    Assert-Contains 'contains https://' $skillPath 'https://'
    Assert-Contains 'contains browser4.io' $skillPath 'browser4.io'
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 3: Mode detection — edge cases
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Mode Detection: Edge Cases ━━━' -ForegroundColor Yellow

& {
    # An unrecognized value should fall through to dev mode.
    $browser4cliMode = 'staging'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'unrecognized mode falls back to dev'
    Assert-Equal '$helpCmd falls back to dev value' `
        '`cargo run -- help`' $helpCmd
    Assert-Equal '$skillPath falls back to dev value' `
        '`cli/skill/SKILL.md`' $skillPath
}

& {
    # PowerShell -eq is case-insensitive, so 'Production' matches 'production'.
    $browser4cliMode = 'Production'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'case insensitivity (PowerShell -eq default)'
    Assert-Equal "'Production' (capital P) matches 'production' (case-insensitive -eq)" `
        '`browser4-cli help`' $helpCmd
    Assert-Equal '$skillPath is production URL' `
        'https://browser4.ioSKILL.md' $skillPath
}

& {
    # Null or empty mode should fall back to dev.
    $browser4cliMode = $null
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'null mode falls back to dev'
    Assert-Equal '$helpCmd resolves to dev default' `
        '`cargo run -- help`' $helpCmd
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 4: $generalPrompt content (dev mode)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ $generalPrompt (Dev Mode) ━━━' -ForegroundColor Yellow

& {
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'existence and structure'
    Assert-NotNullOrEmpty '$generalPrompt is defined and non-empty' $generalPrompt
    Assert-True '$generalPrompt is longer than 100 chars' ($generalPrompt.Length -gt 100)

    Write-TestGroup 'key sections'
    $sections = @(
        'Preparation',
        'Tool Usage Rules',
        'Evaluation Objective',
        'Installation & Setup',
        'Discoverability',
        'Documentation',
        'CLI Experience',
        'Task Execution',
        'Reliability',
        'User Experience',
        'Investigation Guidelines',
        'Deliverables',
        'A. Task Result',
        'B. Execution Trace',
        'C. Issues Found',
        'D. Overall Assessment',
        '# Task',
        'Important'
    )
    foreach ($section in $sections) {
        Assert-Contains "contains: $section" $generalPrompt $section
    }

    Write-TestGroup 'severity levels'
    Assert-Contains 'contains: Critical' $generalPrompt 'Critical'
    Assert-Contains 'contains: High' $generalPrompt 'High'
    Assert-Contains 'contains: Medium' $generalPrompt 'Medium'
    Assert-Contains 'contains: Low' $generalPrompt 'Low'

    Write-TestGroup 'category labels'
    $categories = @('Product', 'Documentation', 'UX', 'Reliability', 'Discoverability')
    foreach ($cat in $categories) {
        Assert-Contains "contains category: $cat" $generalPrompt $cat
    }

    Write-TestGroup 'mode-specific values (dev)'
    Assert-Contains 'contains cargo run -- help' $generalPrompt 'cargo run -- help'
    Assert-Contains 'contains cli/skill/SKILL.md' $generalPrompt 'cli/skill/SKILL.md'
    Assert-NotContains 'should NOT contain browser4-cli help' $generalPrompt 'browser4-cli help'
    Assert-NotContains 'should NOT contain browser4.io' $generalPrompt 'browser4.io'

    Write-TestGroup 'first-time user language'
    Assert-Contains 'mentions first-time user' $generalPrompt 'first-time user'
    Assert-Contains 'mentions new user' $generalPrompt 'new user'
    Assert-Contains 'mentions usability' $generalPrompt 'usability'
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 5: $generalPrompt content (production mode)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ $generalPrompt (Production Mode) ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'production'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'mode-specific values (production)'
    Assert-Contains 'contains browser4-cli help' $generalPrompt 'browser4-cli help'
    Assert-Contains 'contains browser4.io' $generalPrompt 'browser4.io'
    Assert-NotContains 'should NOT contain cargo run -- help' $generalPrompt 'cargo run -- help'
    Assert-NotContains 'should NOT contain cli/skill/SKILL.md' $generalPrompt 'cli/skill/SKILL.md'
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 6: Invoke-Agent function — existence and signature
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Invoke-Agent: Signature ━━━' -ForegroundColor Yellow

& {
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'function existence'
    Assert-True 'Invoke-Agent is defined' `
        ($null -ne (Get-Command Invoke-Agent -ErrorAction SilentlyContinue))

    Write-TestGroup 'parameter: -Prompt (mandatory)'
    $promptParam = (Get-Command Invoke-Agent).Parameters['Prompt']
    Assert-True '-Prompt parameter exists' ($null -ne $promptParam)
    Assert-True '-Prompt accepts string' ($promptParam.ParameterType -eq [string])

    Write-TestGroup 'parameter: -Silent (switch, optional)'
    $silentParam = (Get-Command Invoke-Agent).Parameters['Silent']
    Assert-True '-Silent parameter exists' ($null -ne $silentParam)
    Assert-True '-Silent is a switch' ($silentParam.ParameterType -eq [switch])
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 7: Invoke-Agent function — argument forwarding
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Invoke-Agent: Argument Forwarding ━━━' -ForegroundColor Yellow

& {
    # Mock 'claude' to capture the arguments it receives.
    $script:CapturedArgs = $null

    function global:claude {
        $script:CapturedArgs = $args
    }

    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'base arguments (without -Silent)'
    Invoke-Agent -Prompt 'test prompt'
    Assert-True 'claude was invoked' ($null -ne $script:CapturedArgs)
    Assert-Contains 'has --dangerously-skip-permissions' ($script:CapturedArgs -join ' ') `
        '--dangerously-skip-permissions'
    Assert-Contains 'has -p flag' ($script:CapturedArgs -join ' ') '-p'
    Assert-True 'prompt value is the last argument' ($script:CapturedArgs[-1] -eq 'test prompt')
    Assert-NotContains 'should NOT have --silent' ($script:CapturedArgs -join ' ') '--silent'

    Write-TestGroup 'with -Silent flag'
    $script:CapturedArgs = $null
    Invoke-Agent -Prompt 'silent test' -Silent
    Assert-Contains 'has --dangerously-skip-permissions' ($script:CapturedArgs -join ' ') `
        '--dangerously-skip-permissions'
    Assert-Contains 'has -p flag' ($script:CapturedArgs -join ' ') '-p'
    Assert-Contains 'has --silent' ($script:CapturedArgs -join ' ') '--silent'
    # --silent is appended after the prompt, so prompt is at [-2]
    Assert-True 'prompt follows -p flag' ($script:CapturedArgs[-2] -eq 'silent test')

    # Clean up the mock so it does not leak.
    Remove-Item function:claude -ErrorAction SilentlyContinue
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 8: Invoke-Agent — error handling
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Invoke-Agent: Error Handling ━━━' -ForegroundColor Yellow

& {
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'omitting -Prompt is a parameter binding error'
    $errorCaught = $false
    try {
        Invoke-Agent
    } catch {
        $errorCaught = $true
    }
    Assert-True 'throws when -Prompt is omitted' $errorCaught
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 9: $ErrorActionPreference side-effect
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ $ErrorActionPreference Side-Effect ━━━' -ForegroundColor Yellow

& {
    $originalEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'  # change before dot-sourcing

    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'sets ErrorActionPreference to Stop'
    Assert-Equal 'ErrorActionPreference is now "Stop"' 'Stop' $ErrorActionPreference

    # Restore
    $ErrorActionPreference = $originalEAP
}

# ═══════════════════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '══════════════════════════════════════════════════' -ForegroundColor Cyan
$total = $TestsPassed + $TestsFailed + $TestsSkipped
Write-Host "Results: $TestsPassed passed, $TestsFailed failed, $TestsSkipped skipped ($total total)" `
    -ForegroundColor $(if ($TestsFailed -eq 0) { 'Green' } else { 'Red' })
Write-Host '══════════════════════════════════════════════════' -ForegroundColor Cyan

if ($TestsFailed -gt 0) {
    exit 1
}
exit 0
