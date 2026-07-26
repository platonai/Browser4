#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Avoid Windows-only env vars ($env:TEMP) — use $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# - Paths: use Join-Path / Split-Path; never bake \ or / as literal.
# - [System.IO.Path]::IsPathRooted is platform-aware — C:\foo is NOT
#   rooted on Linux; test with platform-appropriate absolute paths.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
Unit tests for common.ps1 — the shared helpers module for agent-scenario scripts.

.DESCRIPTION
Tests common.ps1 functionality:
  1. Mode detection ($browser4cliMode → $helpCmd, $skillPath)
  2. $generalPrompt content and structure
  3. Invoke-Agent function signature and argument forwarding
  4. Path resolution ($IssuesDraftDir, $RepoRoot)
  5. ConvertFrom-IssuesSection parsing
  6. Write-IssuesToDraft file output
  7. Invoke-Agent backward compatibility

.NOTES
Each test group runs in a clean scope by dot-sourcing common.ps1 in a script block.
This prevents test state from leaking between test groups.
#>

$ErrorActionPreference = 'Stop'

# Trap any terminating error so the failure details are visible in CI/test-runner
# output.  Without this, an unhandled error silently kills the script with exit
# code 1 and the runner only sees "(exit 1)" with no clue about what happened.
trap {
    Write-Host ''
    Write-Host '══════════════════════════════════════════════════' -ForegroundColor Red
    Write-Host "FATAL ERROR in common.tests.ps1" -ForegroundColor Red
    Write-Host "  $_" -ForegroundColor Red
    Write-Host "  $($_.InvocationInfo.PositionMessage.Trim())" -ForegroundColor Red
    Write-Host "══════════════════════════════════════════════════" -ForegroundColor Red
    exit 1
}

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
        Write-Host "    FAIL: $Name -- value is null or empty" -ForegroundColor Red
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
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup '$helpCmd in dev mode'
    Assert-Equal 'is exactly "./b4w.ps1 help"' `
        './b4w.ps1 help' $helpCmd
    Assert-Contains 'contains b4w.ps1' $helpCmd 'b4w.ps1'
    Assert-Contains 'contains help subcommand' $helpCmd 'help'

    Write-TestGroup '$skillPath in dev mode'
    Assert-Equal 'is exactly "skills/browser4-cli/SKILL.md"' `
        'skills/browser4-cli/SKILL.md' $skillPath
    Assert-Contains 'contains SKILL.md' $skillPath 'SKILL.md'
    Assert-Contains 'contains skills/' $skillPath 'skills/'
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
    Assert-Equal 'is exactly "browser4-cli help"' `
        'browser4-cli help' $helpCmd
    Assert-Contains 'contains browser4-cli' $helpCmd 'browser4-cli'
    Assert-Contains 'contains help subcommand' $helpCmd 'help'

    Write-TestGroup '$skillPath in production mode'
    Assert-Equal 'is exactly the remote URL' `
        'https://browser4.io/SKILL.md' $skillPath
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
        './b4w.ps1 help' $helpCmd
    Assert-Equal '$skillPath falls back to dev value' `
        'skills/browser4-cli/SKILL.md' $skillPath
}

& {
    # PowerShell -eq is case-insensitive, so 'Production' matches 'production'.
    $browser4cliMode = 'Production'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'case insensitivity (PowerShell -eq default)'
    Assert-Equal "'Production' (capital P) matches 'production' (case-insensitive -eq)" `
        'browser4-cli help' $helpCmd
    Assert-Equal '$skillPath is production URL' `
        'https://browser4.io/SKILL.md' $skillPath
}

& {
    # Null/empty should fall back to dev when no env var overrides it.
    # Clear the env var so BROWSER4CLI_MODE does not mask the null check.
    $env:BROWSER4CLI_MODE = $null
    $browser4cliMode = $null
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'null mode falls back to dev'
    Assert-Equal '$helpCmd resolves to dev default' `
        './b4w.ps1 help' $helpCmd
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 4: $generalPrompt content (dev mode)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ $generalPrompt (Dev Mode) ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
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
    Assert-Contains 'contains ./b4w.ps1 help' $generalPrompt './b4w.ps1 help'
    Assert-Contains 'contains skills/browser4-cli/SKILL.md' $generalPrompt 'skills/browser4-cli/SKILL.md'
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
    Assert-NotContains 'should NOT contain ./b4w.ps1 help' $generalPrompt './b4w.ps1 help'
    Assert-NotContains 'should NOT contain skills/browser4-cli/SKILL.md' $generalPrompt 'skills/browser4-cli/SKILL.md'
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 6: Invoke-Agent function — existence and signature
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Invoke-Agent: Signature ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
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
    # Mock the native-command boundary to capture the arguments Invoke-Agent forwards.
    # We mock BOTH Get-ScenarioAgent and Start-NativeCommand so the test never
    # accidentally invokes a real agent CLI.  Relying on $script:scenarioAgentCli
    # alone is fragile — if scope resolution fails, Get-ScenarioAgent falls
    # through to Get-Command and may launch the real claude/kimi binary.
    $script:CapturedArgs = $null

    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    # Local variable controls the simulated backend.  Using a lexical variable
    # (not $script:) avoids scope-resolution differences across PS versions.
    $testAgentCli = 'claude'

    function Get-ScenarioAgent { return $testAgentCli }

    function Start-NativeCommand {
        param(
            [string]$FilePath,
            [string[]]$ArgumentList,
            [string]$CaptureFile,
            [int]$TimeoutSeconds
        )

        $script:CapturedArgs = $ArgumentList
        return 0
    }

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

    Write-TestGroup 'kimi backend'
    $testAgentCli = 'kimi'
    $script:CapturedArgs = $null
    Invoke-Agent -Prompt 'kimi test'
    Assert-True 'kimi was invoked' ($null -ne $script:CapturedArgs)
    Assert-NotContains 'kimi: no --dangerously-skip-permissions' ($script:CapturedArgs -join ' ') `
        '--dangerously-skip-permissions'
    Assert-Contains 'kimi: has -p flag' ($script:CapturedArgs -join ' ') '-p'
    Assert-True 'kimi: prompt value is the last argument' ($script:CapturedArgs[-1] -eq 'kimi test')

    Write-TestGroup 'kimi backend with -Silent flag'
    $script:CapturedArgs = $null
    Invoke-Agent -Prompt 'kimi silent' -Silent
    Assert-NotContains 'kimi: --silent is never passed' ($script:CapturedArgs -join ' ') '--silent'
    Assert-True 'kimi: prompt value is the last argument' ($script:CapturedArgs[-1] -eq 'kimi silent')

    Write-TestGroup 'opencode backend'
    $testAgentCli = 'opencode'
    $script:CapturedArgs = $null
    Invoke-Agent -Prompt 'opencode test'
    Assert-True 'opencode was invoked' ($null -ne $script:CapturedArgs)
    Assert-NotContains 'opencode: no --dangerously-skip-permissions' ($script:CapturedArgs -join ' ') `
        '--dangerously-skip-permissions'
    Assert-Equal 'opencode: first arg is run' 'run' $script:CapturedArgs[0]
    Assert-True 'opencode: prompt value is the last argument' ($script:CapturedArgs[-1] -eq 'opencode test')

    Write-TestGroup 'opencode backend with -Silent flag'
    $script:CapturedArgs = $null
    Invoke-Agent -Prompt 'opencode silent' -Silent
    Assert-NotContains 'opencode: --silent is never passed' ($script:CapturedArgs -join ' ') '--silent'
    Assert-Equal 'opencode silent: first arg is run' 'run' $script:CapturedArgs[0]
    Assert-True 'opencode silent: prompt value is the last argument' ($script:CapturedArgs[-1] -eq 'opencode silent')

    # Clean up the mocks so they do not leak.
    Remove-Item function:Start-NativeCommand -ErrorAction SilentlyContinue
    Remove-Item function:Get-ScenarioAgent -ErrorAction SilentlyContinue
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 7b: Get-ScenarioAgent — opencode detection
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Get-ScenarioAgent: opencode Detection ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'Get-ScenarioAgent function exists'
    Assert-True 'Function is defined' ($null -ne (Get-Command Get-ScenarioAgent -ErrorAction SilentlyContinue))

    Write-TestGroup 'Overridden via $script:scenarioAgentCli returns opencode'
    $script:scenarioAgentCli = 'opencode'
    $resolved = Get-ScenarioAgent
    Assert-Equal 'Returns opencode when overridden' 'opencode' $resolved

    Write-TestGroup 'Overridden via $script:scenarioAgentCli returns kimi'
    $script:scenarioAgentCli = 'kimi'
    $resolved = Get-ScenarioAgent
    Assert-Equal 'Returns kimi when overridden' 'kimi' $resolved

    Write-TestGroup 'Overridden via $script:scenarioAgentCli returns claude'
    $script:scenarioAgentCli = 'claude'
    $resolved = Get-ScenarioAgent
    Assert-Equal 'Returns claude when overridden' 'claude' $resolved

    # Reset override
    $script:scenarioAgentCli = $null

    # Static analysis: verify opencode is in the auto-detection list
    $commonPath = Join-Path $PSScriptRoot 'common.ps1'
    $commonContent = Get-Content -LiteralPath $commonPath -Raw -Encoding UTF8
    Assert-True 'Auto-detection includes opencode' `
        ($commonContent -match 'Get-Command opencode.*return.*opencode')
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 8: Invoke-Agent — error handling
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Invoke-Agent: Error Handling ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'omitting -Prompt is a parameter binding error'
    # Check via reflection rather than actually invoking without -Prompt,
    # because PowerShell would prompt interactively for the missing mandatory
    # parameter instead of throwing a catchable terminating error.
    $promptParam = (Get-Command Invoke-Agent).Parameters['Prompt']
    $isMandatory = $promptParam.ParameterSets['__AllParameterSets'].IsMandatory
    Assert-True '-Prompt is mandatory (omitting it is a parameter binding error)' $isMandatory
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 9: $ErrorActionPreference side-effect
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ $ErrorActionPreference Side-Effect ━━━' -ForegroundColor Yellow

& {
    $originalEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'  # change before dot-sourcing

    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'sets ErrorActionPreference to Stop'
    Assert-Equal 'ErrorActionPreference is now "Stop"' 'Stop' $ErrorActionPreference

    # Restore
    $ErrorActionPreference = $originalEAP
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 10: Path resolution
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Path Resolution ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup '$script:IssuesDraftDir is an absolute path'
    $isAbs = [System.IO.Path]::IsPathRooted([string]$script:IssuesDraftDir)
    Assert-True 'IssuesDraftDir is absolute' $isAbs

    Write-TestGroup '$script:IssuesDraftDir ends with the expected suffix'
    $expectedSuffix = 'issues\draft'
    $normalized = ([string]$script:IssuesDraftDir) -replace '[/\\]', '\'
    Assert-True "Ends with $expectedSuffix" $normalized.EndsWith($expectedSuffix)

    Write-TestGroup '$script:RepoRoot resolves to an existing directory'
    Assert-True 'RepoRoot exists' (Test-Path -LiteralPath $script:RepoRoot -PathType Container)
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 11: Invoke-Agent new parameters
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Invoke-Agent New Parameters ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    $funcInfo = Get-Command Invoke-Agent -ErrorAction SilentlyContinue

    Write-TestGroup 'Invoke-Agent exists'
    Assert-True 'Function exists' ($null -ne $funcInfo)

    Write-TestGroup 'Invoke-Agent has -ScenarioName parameter'
    $hasScenarioName = $funcInfo.Parameters.ContainsKey('ScenarioName')
    Assert-True 'Has ScenarioName parameter' $hasScenarioName

    Write-TestGroup 'Invoke-Agent -ScenarioName is a string'
    $scenarioType = $funcInfo.Parameters['ScenarioName'].ParameterType.Name
    Assert-Equal 'ScenarioName is string' 'String' $scenarioType

    Write-TestGroup 'Invoke-Agent has -OutputFile parameter'
    Assert-True 'Has OutputFile parameter' $funcInfo.Parameters.ContainsKey('OutputFile')

    Write-TestGroup 'Invoke-Agent still has -Prompt (mandatory)'
    Assert-True 'Has Prompt parameter' $funcInfo.Parameters.ContainsKey('Prompt')

    Write-TestGroup 'Invoke-Agent still has -Silent'
    Assert-True 'Has Silent parameter' $funcInfo.Parameters.ContainsKey('Silent')
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 12: ConvertFrom-IssuesSection
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ ConvertFrom-IssuesSection ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'Returns empty array for non-matching input'
    $result = ConvertFrom-IssuesSection -Content 'no issues section here'
    Assert-True 'Non-matching input → empty array' ($result.Count -eq 0)

    Write-TestGroup 'Returns empty array for input without C section'
    $noC = @'
### A. Task Result
Done.

### B. Execution Trace
Did stuff.

### D. Overall Assessment
All good.
'@
    $result = ConvertFrom-IssuesSection -Content $noC
    Assert-True 'No C section → empty array' ($result.Count -eq 0)

    Write-TestGroup 'Parses a single issue with all fields'
    $singleIssue = @'
### A. Task Result
Task completed.

### B. Execution Trace
Used commands.

### C. Issues Found

#### Title
Help text missing examples

#### Severity
Medium

#### Category
Documentation

#### Reproduction Steps
1. Run `browser4-cli help`
2. Observe output

#### Expected Behavior
Help includes usage examples.

#### Actual Behavior
No examples shown.

#### Suggested Improvement
Add an Examples section to each command.

### D. Overall Assessment
Rating: 7/10
'@
    $rawResult = ConvertFrom-IssuesSection -Content $singleIssue
    # Filter out any incidental output-stream noise
    $result = @($rawResult | Where-Object { $_ -is [hashtable] })
    Write-TestGroup 'Found exactly 1 issue'
    Assert-Equal 'Count is 1' 1 $result.Count

    $issue = $result[0]
    Write-TestGroup 'Parsed Title'
    Assert-Equal 'Title' 'Help text missing examples' $issue.Title
    Write-TestGroup 'Parsed Severity'
    Assert-Equal 'Severity' 'Medium' $issue.Severity
    Write-TestGroup 'Parsed Category'
    Assert-Equal 'Category' 'Documentation' $issue.Category
    Write-TestGroup 'Parsed Reproduction Steps'
    Assert-True 'Reproduction not empty' (-not [string]::IsNullOrWhiteSpace($issue.Reproduction))
    Write-TestGroup 'Parsed Expected Behavior'
    Assert-True 'Expected not empty' (-not [string]::IsNullOrWhiteSpace($issue.Expected))
    Write-TestGroup 'Parsed Actual Behavior'
    Assert-True 'Actual not empty' (-not [string]::IsNullOrWhiteSpace($issue.Actual))
    Write-TestGroup 'Parsed Suggested Improvement'
    Assert-True 'Suggestion not empty' (-not [string]::IsNullOrWhiteSpace($issue.Suggestion))

    Write-TestGroup 'Parses multiple issues'
    $multiIssue = @'
### C. Issues Found

#### Title
First issue

#### Severity
High

#### Category
UX

#### Reproduction Steps
Step one.

#### Expected Behavior
Should work.

#### Actual Behavior
Does not work.

#### Suggested Improvement
Fix it.

#### Title
Second issue

#### Severity
Low

#### Category
Reliability

#### Reproduction Steps
Step two.

#### Expected Behavior
Should be reliable.

#### Actual Behavior
Flaky.

#### Suggested Improvement
Make it stable.
'@
    $rawMulti = ConvertFrom-IssuesSection -Content $multiIssue
    $result = @($rawMulti | Where-Object { $_ -is [hashtable] })
    Assert-Equal 'Found 2 issues' 2 $result.Count
    Assert-Equal 'First issue title' 'First issue' $result[0].Title
    Assert-Equal 'Second issue title' 'Second issue' $result[1].Title

    Write-TestGroup 'Handles malformed input gracefully'
    $malformed = '### C. Issues Found' + "`n" + 'Just some random text with no structured issues.'
    $rawMal = ConvertFrom-IssuesSection -Content $malformed
    $result = @($rawMal | Where-Object { $_ -is [hashtable] })
    Assert-True 'Malformed → empty array' ($result.Count -eq 0)

    Write-TestGroup 'Parses new format: ### Issue N: with **Key:** Value fields'
    $newFormat = @'
### C. Issues Found

### Issue 1: Relative path cd fails from repo root

**Severity:** Low
**Category:** Discoverability / UX
**Reproduction:** Run `cd cli` from repo root.
**Expected:** Command executes.
**Actual:** No such file or directory.
**Root Cause:** The CLI does not inherit the shell CWD.
**Code Pointer:** cli/browser4-cli/src/main.rs:resolve_path()
**Review:**
**Suggested Improvement:**
- Update scripts to cd into correct subdirectory first.
- Add a warning when the working directory is unexpected.

---

### Issue 2: Startup latency is high

**Severity:** Medium
**Category:** UX
**Reproduction:** Run any cargo command.
**Expected:** Fast startup.
**Actual:** ~6 second delay.
**Root Cause:** Full rebuild on every invocation.
**Code Pointer:**
**Review:**
**Suggested Improvement:**
- Cache the compiled bundle.
- Show a progress spinner.
- Offer a daemon mode.
'@
    $rawNew = ConvertFrom-IssuesSection -Content $newFormat
    $newResult = @($rawNew | Where-Object { $_ -is [hashtable] })
    Assert-Equal 'Found 2 issues in new format' 2 $newResult.Count

    $issue1 = $newResult[0]
    Assert-Equal 'Issue 1 Title' 'Relative path cd fails from repo root' $issue1.Title
    Assert-Equal 'Issue 1 Severity' 'Low' $issue1.Severity
    Assert-Equal 'Issue 1 Category' 'Discoverability / UX' $issue1.Category
    Assert-True 'Issue 1 Reproduction not empty' (-not [string]::IsNullOrWhiteSpace($issue1.Reproduction))
    Assert-True 'Issue 1 Expected not empty' (-not [string]::IsNullOrWhiteSpace($issue1.Expected))
    Assert-True 'Issue 1 Actual not empty' (-not [string]::IsNullOrWhiteSpace($issue1.Actual))
    Assert-True 'Issue 1 RootCause extracted' (-not [string]::IsNullOrWhiteSpace($issue1.RootCause))
    Assert-True 'Issue 1 CodePointer extracted' (-not [string]::IsNullOrWhiteSpace($issue1.CodePointer))
    Assert-Equal 'Issue 1 Review is empty' '' $issue1.Review
    Assert-True 'Issue 1 Suggestion is a list' $issue1.Suggestion.Contains('- Update scripts')
    Assert-True 'Issue 1 Suggestion has multiple items' $issue1.Suggestion.Contains('- Add a warning')

    $issue2 = $newResult[1]
    Assert-Equal 'Issue 2 Title' 'Startup latency is high' $issue2.Title
    Assert-Equal 'Issue 2 Severity' 'Medium' $issue2.Severity
    Assert-True 'Issue 2 RootCause extracted' (-not [string]::IsNullOrWhiteSpace($issue2.RootCause))
    Assert-Equal 'Issue 2 CodePointer is empty' '' $issue2.CodePointer
    Assert-Equal 'Issue 2 Review is empty' '' $issue2.Review
    Assert-True 'Issue 2 Suggestion has 3 items' (($issue2.Suggestion -split "`n").Count -ge 3)

    Write-TestGroup 'Parses new format: Issue #N (with hash mark)'
    $hashFormat = @'
### C. Issues Found

### Issue #1: Hash-style numbering

**Severity:** High
**Category:** Product
**Reproduction:** Step.
**Expected:** Good.
**Actual:** Bad.
**Root Cause:** Undetermined.
**Code Pointer:**
**Review:**
**Suggested Improvement:**
- Fix it.
'@
    $rawHash = ConvertFrom-IssuesSection -Content $hashFormat
    $hashResult = @($rawHash | Where-Object { $_ -is [hashtable] })
    Assert-Equal 'Found 1 issue with hash-style numbering' 1 $hashResult.Count
    Assert-Equal 'Hash-style title extracted' 'Hash-style numbering' $hashResult[0].Title
    Assert-Equal 'Hash-style severity' 'High' $hashResult[0].Severity
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 13: Write-IssuesToDraft
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Write-IssuesToDraft ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    # Use a temp directory to avoid polluting the real issues draft directory
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "b4cli-test-$(Get-Random)"
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    try {
        $sampleContent = @'
### A. Task Result
Done.

### B. Execution Trace
Steps.

### C. Issues Found

#### Title
Test issue

#### Severity
Medium

#### Category
UX

#### Reproduction Steps
1. Open app
2. Click button

#### Expected Behavior
Works.

#### Actual Behavior
Broken.

#### Suggested Improvement
Fix.

### D. Overall Assessment
OK.
'@

        Write-TestGroup 'Writes full.md file'
        Write-IssuesToDraft -ScenarioName 'unit-test' -Content $sampleContent -OutputDirectory $tempDir
        $fullFiles = Get-ChildItem -Path $tempDir -Filter '*.full.md'
        Assert-Equal 'Exactly 1 full.md file' 1 $fullFiles.Count

        Write-TestGroup 'Full.md file is not empty'
        $fullContent = Get-Content -Path $fullFiles[0].FullName -Raw -Encoding UTF8
        Assert-True 'Full content non-empty' (-not [string]::IsNullOrWhiteSpace($fullContent))

        Write-TestGroup 'Full.md file name contains scenario name'
        Assert-True 'Name contains unit-test' $fullFiles[0].Name.Contains('unit-test')

        Write-TestGroup 'Writes consolidated .issues.md file (not individual issue files)'
        $issueFiles = Get-ChildItem -Path $tempDir -Filter '*.issues.md'
        $individualFiles = Get-ChildItem -Path $tempDir -Filter '*.issue-*.md'
        Assert-Equal 'Exactly 1 consolidated issues file' 1 $issueFiles.Count
        Assert-Equal 'No individual .issue-NNN.md files' 0 $individualFiles.Count

        Write-TestGroup 'Consolidated issues file contains background context'
        $issueContent = Get-Content -Path $issueFiles[0].FullName -Raw -Encoding UTF8
        Assert-True 'Contains Scenario Background heading' $issueContent.Contains('## Scenario Background')
        Assert-True 'Contains Task subsection' $issueContent.Contains('### Task')
        Assert-True 'Contains Execution Context' $issueContent.Contains('### Execution Context')

        Write-TestGroup 'Consolidated issues file contains the parsed issue'
        Assert-True 'Contains issue title' $issueContent.Contains('Test issue')
        Assert-True 'Contains Severity' $issueContent.Contains('**Severity:** Medium')
        Assert-True 'Contains Category' $issueContent.Contains('**Category:** UX')

        Write-TestGroup 'Consolidated issues file contains reproduction guide'
        Assert-True 'Contains How to Reproduce heading' $issueContent.Contains('## How to Reproduce')
        Assert-True 'Contains Common Setup' $issueContent.Contains('### Common Setup')
        Assert-True 'Contains Per-Issue Reproduction Steps' $issueContent.Contains('### Per-Issue Reproduction Steps')

        Write-TestGroup 'Consolidated issues file contains issue detail sections'
        Assert-True 'Contains Reproduction section' $issueContent.Contains('#### Reproduction')
        Assert-True 'Contains Expected Behavior section' $issueContent.Contains('#### Expected Behavior')
        Assert-True 'Contains Actual Behavior section' $issueContent.Contains('#### Actual Behavior')

        Write-TestGroup 'Consolidated issues file contains source reference'
        Assert-True 'Contains Source link to full.md' $issueContent.Contains('Source:')
        Assert-True 'Contains Mode' $issueContent.Contains('Mode:')
    }
    finally {
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 14: Invoke-Agent backward compatibility
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Invoke-Agent Backward Compatibility ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'Legacy path: with no ScenarioName and no OutputFile, function exists'
    # The function is designed to enter the legacy (non-capture) code path
    # when both ScenarioName and OutputFile are empty/default.
    $funcInfo = Get-Command Invoke-Agent -ErrorAction SilentlyContinue
    Assert-True 'Invoke-Agent is available' ($null -ne $funcInfo)

    Write-TestGroup 'Default ScenarioName is empty string'
    Assert-True 'ScenarioName is not mandatory' (-not $funcInfo.Parameters['ScenarioName'].ParameterSets['__AllParameterSets'].IsMandatory)

    Write-TestGroup 'Default OutputFile is empty string'
    Assert-True 'OutputFile is not mandatory' (-not $funcInfo.Parameters['OutputFile'].ParameterSets['__AllParameterSets'].IsMandatory)

    Write-TestGroup 'Legacy scenario scripts (no -ScenarioName) parameter binding'
    # Verify parameter resolution: ScenarioName defaults to '' so callers can
    # omit it and still get the legacy (non-capture) code path.
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseInput(
        'Invoke-Agent -Prompt ''test'' -Silent', [ref]$null, [ref]$parseErrors
    )
    Assert-True 'No parse errors for legacy call syntax' ($parseErrors.Count -eq 0)

    Write-TestGroup 'Minimal binding: -Prompt alone is valid syntax'
    $null = [System.Management.Automation.Language.Parser]::ParseInput(
        'Invoke-Agent -Prompt ''test''', [ref]$null, [ref]$parseErrors
    )
    Assert-True 'Prompt-only call parses' ($parseErrors.Count -eq 0)
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 15: Read-TaskFile
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Read-TaskFile ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    # Use a temp directory to avoid polluting the real tasks/ directory
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "b4cli-readtask-$(Get-Random)"
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    try {
        # Helper: write a .md file with the given content and return its path.
        function New-TaskFile([string]$Name, [string]$Content, [string]$Encoding = 'UTF8') {
            $path = Join-Path $tempDir "$Name.md"
            if ($Encoding -eq 'ASCII') {
                # Write with explicit LF line endings for controlled tests
                [System.IO.File]::WriteAllText($path, $Content, [System.Text.Encoding]::ASCII)
            } else {
                [System.IO.File]::WriteAllText($path, $Content)
            }
            return $path
        }

        Write-TestGroup 'Valid file: heading + body (blank line between)'
        $path = New-TaskFile 'valid' "# my-scenario`n`nStep 1. Do this.`nStep 2. Do that.`n"
        $task = Read-TaskFile -Path $path
        Assert-Equal 'Name' 'my-scenario' $task.Name
        Assert-True 'Body non-empty' (-not [string]::IsNullOrWhiteSpace($task.Body))
        Assert-Contains 'Body starts with Step 1' $task.Body 'Step 1. Do this.'

        Write-TestGroup 'No blank line after heading'
        $path = New-TaskFile 'no-blank' "# scenario-name`nBody text here.`n"
        $task = Read-TaskFile -Path $path
        Assert-Equal 'Name' 'scenario-name' $task.Name
        Assert-Equal 'Body (trimmed)' 'Body text here.' $task.Body.Trim()

        Write-TestGroup 'Multiple blank lines after heading'
        $path = New-TaskFile 'multi-blank' "# multi`n`n`n`nFirst line of body.`n"
        $task = Read-TaskFile -Path $path
        Assert-Equal 'Name' 'multi' $task.Name
        Assert-True 'Body starts with First line' $task.Body.TrimStart().StartsWith('First line')

        Write-TestGroup 'No heading (body-only file)'
        $path = New-TaskFile 'no-heading' "Just some task body text.`nNo heading here.`n"
        $task = Read-TaskFile -Path $path
        Assert-Equal 'Name is empty' '' $task.Name
        Assert-Contains 'Body preserved' $task.Body 'Just some task body text.'

        Write-TestGroup 'Empty file should throw'
        $path = New-TaskFile 'empty' ''
        $threw = $false
        try { Read-TaskFile -Path $path } catch { $threw = $true }
        Assert-True 'Throws on empty file' $threw

        Write-TestGroup 'Only heading, no body should throw'
        $path = New-TaskFile 'heading-only' "# just-heading`n"
        $threw = $false
        try { Read-TaskFile -Path $path } catch { $threw = $true }
        Assert-True 'Throws on heading-only file' $threw

        Write-TestGroup 'CRLF line endings'
        $path = New-TaskFile 'crlf' "# crlf-scenario`r`n`r`nBody with CRLF.`r`n"
        $task = Read-TaskFile -Path $path
        Assert-Equal 'Name' 'crlf-scenario' $task.Name
        Assert-True 'Body non-empty' (-not [string]::IsNullOrWhiteSpace($task.Body))

        Write-TestGroup 'Missing file should throw'
        $path = Join-Path $tempDir 'does-not-exist.md'
        $threw = $false
        try { Read-TaskFile -Path $path } catch { $threw = $true }
        Assert-True 'Throws on missing file' $threw

        Write-TestGroup 'Returns PSCustomObject'
        $path = New-TaskFile 'psobject-check' "# test`n`nBody.`n"
        $task = Read-TaskFile -Path $path
        Assert-True 'Is PSCustomObject' ($task -is [PSCustomObject])
        Assert-True 'Has Name property' ($null -ne $task.Name)
        Assert-True 'Has Body property' ($null -ne $task.Body)
    }
    finally {
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 16: Resolve-TaskFilePath
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Resolve-TaskFilePath ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    # Model the production layout: scripts/ and tasks/ are siblings.
    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) "b4cli-resolve-$pid"
    $scriptsDir = Join-Path $tempRoot 'scripts'
    $tasksDir = Join-Path $tempRoot 'tasks'
    New-Item -ItemType Directory -Path $scriptsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $tasksDir -Force | Out-Null

    $testFileName = 'resolve-test.md'
    $taskFileRelPath = "tasks/$testFileName"  # relative path used in production
    $taskFileAbsPath = Join-Path $tasksDir $testFileName
    [System.IO.File]::WriteAllText($taskFileAbsPath, '# test' + "`n`nBody.", (New-Object System.Text.UTF8Encoding($false)))

    $spacedFileName = 'task with spaces.md'
    $spacedFileAbsPath = Join-Path $tasksDir $spacedFileName
    [System.IO.File]::WriteAllText($spacedFileAbsPath, '# spaced' + "`n`nBody.", (New-Object System.Text.UTF8Encoding($false)))

    try {
        Write-TestGroup 'As-given: absolute path to existing file'
        $result = Resolve-TaskFilePath -TaskFile $taskFileAbsPath -ScriptsDir $scriptsDir
        Assert-NotNullOrEmpty 'Returns a path' $result
        Assert-Equal 'Resolved path matches original' $taskFileAbsPath $result

        Write-TestGroup 'As-given: absolute path to non-existent file'
        $nonexistent = Join-Path $tasksDir 'does-not-exist.md'
        $result = Resolve-TaskFilePath -TaskFile $nonexistent -ScriptsDir $scriptsDir
        Assert-True 'Returns $null for non-existent absolute path' ($null -eq $result)

        Write-TestGroup 'As-given: path with spaces resolves via -LiteralPath'
        $result = Resolve-TaskFilePath -TaskFile $spacedFileAbsPath -ScriptsDir $scriptsDir
        Assert-Equal 'File with spaces resolves' $spacedFileAbsPath $result

        Write-TestGroup 'CWD-relative: file in current directory'
        $origCwd = Get-Location
        try {
            Set-Location $tasksDir
            $result = Resolve-TaskFilePath -TaskFile $testFileName -ScriptsDir $scriptsDir
            Assert-NotNullOrEmpty 'Returns a path for CWD-relative file' $result
        } finally {
            Set-Location $origCwd
        }

        Write-TestGroup 'CWD-relative: file NOT in CWD falls through to scenarios'
        $otherDir = Join-Path $tempRoot 'other'
        New-Item -ItemType Directory -Path $otherDir -Force | Out-Null

        $origCwd = Get-Location
        try {
            # CWD does NOT contain the file, but scriptsDir/../$TaskFile does
            Set-Location $otherDir
            $result = Resolve-TaskFilePath -TaskFile $taskFileRelPath -ScriptsDir $scriptsDir
            # CWD check: $otherDir/tasks/resolve-test.md → doesn't exist
            # Scenarios check: $scriptsDir/../tasks/resolve-test.md → exists
            Assert-NotNullOrEmpty 'Falls back to scenarios dir when not in CWD' $result
        } finally {
            Set-Location $origCwd
        }

        Write-TestGroup 'Scenarios-relative: resolves via ScriptsDir/../$TaskFile'
        $result = Resolve-TaskFilePath -TaskFile $taskFileRelPath -ScriptsDir $scriptsDir
        Assert-NotNullOrEmpty 'Returns a path via scenarios fallback' $result

        Write-TestGroup 'All three locations fail → $null'
        $result = Resolve-TaskFilePath -TaskFile 'completely-bogus-file.md' -ScriptsDir $scriptsDir
        Assert-True 'Returns $null when file not found anywhere' ($null -eq $result)
    }
    finally {
        Remove-Item -Path $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 17: Resolve-TaskNames
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Resolve-TaskNames ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    $discovered = @('amazon.md', 'hacker-news.md', 'search-summary.md', 'form-filling.md')

    Write-TestGroup 'Name without .md extension matches discovered name'
    $result = @(Resolve-TaskNames -Requested @('amazon') -Discovered $discovered)
    Assert-Equal 'Matches amazon → amazon.md' 1 $result.Count
    Assert-Equal 'Result is amazon.md' 'amazon.md' $result[0]

    Write-TestGroup 'Name with .md extension matches exactly'
    $result = @(Resolve-TaskNames -Requested @('amazon.md') -Discovered $discovered)
    Assert-Equal 'Matches amazon.md → amazon.md' 1 $result.Count
    Assert-Equal 'Result is amazon.md' 'amazon.md' $result[0]

    Write-TestGroup 'Multiple names (mix of with and without .md)'
    $result = @(Resolve-TaskNames -Requested @('amazon', 'hacker-news.md') -Discovered $discovered)
    Assert-Equal 'Two names matched' 2 $result.Count
    Assert-Equal 'First is amazon.md' 'amazon.md' $result[0]
    Assert-Equal 'Second is hacker-news.md' 'hacker-news.md' $result[1]

    Write-TestGroup 'Non-existent name returns empty'
    $result = @(Resolve-TaskNames -Requested @('nonexistent') -Discovered $discovered)
    Assert-Equal 'No matches → empty array' 0 $result.Count

    Write-TestGroup 'Mixed existent and non-existent names'
    $result = @(Resolve-TaskNames -Requested @('amazon', 'bogus', 'form-filling') -Discovered $discovered)
    Assert-Equal 'Only 2 of 3 matched' 2 $result.Count
    Assert-Equal 'First match is amazon.md' 'amazon.md' $result[0]
    Assert-Equal 'Second match is form-filling.md' 'form-filling.md' $result[1]

    Write-TestGroup 'Name without .md that happens to be a substring does NOT match'
    $result = @(Resolve-TaskNames -Requested @('amaz', 'hacker') -Discovered $discovered)
    Assert-Equal 'No substring matches' 0 $result.Count

    Write-TestGroup 'Empty discovered list (guarded by caller, but function handles it)'
    # Passing an empty array to [string[]] is a PowerShell parameter-binding error.
    # In production this case is guarded: run-tests.ps1 exits early when $Discovered.Count -eq 0.
    # Test with a single dummy entry that won't match instead.
    $result = @(Resolve-TaskNames -Requested @('amazon') -Discovered @('unrelated.md'))
    Assert-Equal 'No match in unrelated discovered' 0 $result.Count
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 18: Test-TaskCategory
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Test-TaskCategory ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    # Build platform-appropriate paths for testing
    $sep = [System.IO.Path]::DirectorySeparatorChar

    Write-TestGroup 'generic category matches real-world/generic/ path'
    $path = "D:${sep}repo${sep}tasks${sep}real-world${sep}generic${sep}amazon.md"
    Assert-True 'generic → matches' (Test-TaskCategory -FilePath $path -Category 'generic')

    Write-TestGroup 'generic category rejects real-world/browser4/ path'
    $path = "D:${sep}repo${sep}tasks${sep}real-world${sep}browser4${sep}tab.md"
    Assert-True 'generic → rejects browser4' (-not (Test-TaskCategory -FilePath $path -Category 'generic'))

    Write-TestGroup 'generic category rejects mock-site/ path'
    $path = "D:${sep}repo${sep}tasks${sep}mock-site${sep}form.md"
    Assert-True 'generic → rejects mock-site' (-not (Test-TaskCategory -FilePath $path -Category 'generic'))

    Write-TestGroup 'browser4 category matches real-world/browser4/ path'
    $path = "D:${sep}repo${sep}tasks${sep}real-world${sep}browser4${sep}tab.md"
    Assert-True 'browser4 → matches' (Test-TaskCategory -FilePath $path -Category 'browser4')

    Write-TestGroup 'real-world umbrella matches generic sub-path'
    $path = "D:${sep}repo${sep}tasks${sep}real-world${sep}generic${sep}amazon.md"
    Assert-True 'real-world → matches generic' (Test-TaskCategory -FilePath $path -Category 'real-world')

    Write-TestGroup 'real-world umbrella matches browser4 sub-path'
    $path = "D:${sep}repo${sep}tasks${sep}real-world${sep}browser4${sep}tab.md"
    Assert-True 'real-world → matches browser4' (Test-TaskCategory -FilePath $path -Category 'real-world')

    Write-TestGroup 'real-world umbrella rejects mock-site/ path'
    $path = "D:${sep}repo${sep}tasks${sep}mock-site${sep}form.md"
    Assert-True 'real-world → rejects mock-site' (-not (Test-TaskCategory -FilePath $path -Category 'real-world'))

    Write-TestGroup 'mock-site category matches mock-site/ path'
    $path = "D:${sep}repo${sep}tasks${sep}mock-site${sep}form.md"
    Assert-True 'mock-site → matches' (Test-TaskCategory -FilePath $path -Category 'mock-site')

    Write-TestGroup 'mock-site category rejects real-world/ path'
    $path = "D:${sep}repo${sep}tasks${sep}real-world${sep}generic${sep}amazon.md"
    Assert-True 'mock-site → rejects real-world' (-not (Test-TaskCategory -FilePath $path -Category 'mock-site'))

    Write-TestGroup 'Forward-slash paths also work (platform normalization)'
    $path = "D:/repo/tasks/real-world/generic/amazon.md"
    Assert-True 'Forward-slash path matches generic' (Test-TaskCategory -FilePath $path -Category 'generic')
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 19: run-task.ps1 timeout parameter and forwarding
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ run-task.ps1 Timeout Parameter ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    $taskRunnerPath = Join-Path $PSScriptRoot 'run-task.ps1'

    Write-TestGroup 'run-task.ps1 exists'
    Assert-True 'Script file exists' (Test-Path -LiteralPath $taskRunnerPath -PathType Leaf)

    # Parse the script to verify the $TimeoutMinutes parameter exists.
    $scriptContent = Get-Content -LiteralPath $taskRunnerPath -Raw -Encoding UTF8

    Write-TestGroup 'Has $TimeoutMinutes parameter in param block'
    $hasTimeoutParam = $scriptContent -match '\[\s*int\s*\]\s*\$TimeoutMinutes\s*=\s*0'
    Assert-True 'Script declares [int] $TimeoutMinutes = 0' $hasTimeoutParam

    Write-TestGroup 'Has timeout forwarding logic to Invoke-Agent'
    $hasForwarding = $scriptContent -match '\$invokeParams\[''TimeoutSeconds''\]\s*=\s*\$TimeoutMinutes\s*\*\s*60'
    Assert-True 'Forwards TimeoutMinutes * 60 as TimeoutSeconds' $hasForwarding

    Write-TestGroup 'Writes capture file path to marker for parent monitoring'
    $hasMarker = $scriptContent -match '\.current-capture-path'
    Assert-True 'Writes .current-capture-path marker file' $hasMarker
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 20: timeout conversion logic (minutes → seconds)
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Timeout Conversion Logic ━━━' -ForegroundColor Yellow

& {
    Write-TestGroup 'TimeoutMinutes=0 → no TimeoutSeconds key'
    $TimeoutMinutes = 0
    $invokeParams = @{ Prompt = 'test' }
    if ($TimeoutMinutes -gt 0) {
        $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
    }
    Assert-True 'No TimeoutSeconds when TimeoutMinutes is 0' `
        (-not $invokeParams.ContainsKey('TimeoutSeconds'))

    Write-TestGroup 'TimeoutMinutes=5 → TimeoutSeconds=300'
    $TimeoutMinutes = 5
    $invokeParams = @{ Prompt = 'test' }
    if ($TimeoutMinutes -gt 0) {
        $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
    }
    Assert-Equal 'TimeoutSeconds is 300 (5 * 60)' 300 $invokeParams['TimeoutSeconds']

    Write-TestGroup 'TimeoutMinutes=30 → TimeoutSeconds=1800'
    $TimeoutMinutes = 30
    $invokeParams = @{ Prompt = 'test' }
    if ($TimeoutMinutes -gt 0) {
        $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
    }
    Assert-Equal 'TimeoutSeconds is 1800 (30 * 60)' 1800 $invokeParams['TimeoutSeconds']

    Write-TestGroup 'TimeoutMinutes=1 → TimeoutSeconds=60'
    $TimeoutMinutes = 1
    $invokeParams = @{ Prompt = 'test' }
    if ($TimeoutMinutes -gt 0) {
        $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
    }
    Assert-Equal 'TimeoutSeconds is 60 (1 * 60)' 60 $invokeParams['TimeoutSeconds']
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 21: NativeCommandOutputHandler — C# class compiles and handles errors
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ NativeCommandOutputHandler: Error Resilience ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    # DataReceivedEventArgs has an internal constructor — use reflection.
    $ctorInfo = [System.Diagnostics.DataReceivedEventArgs].GetConstructor(
        [System.Reflection.BindingFlags]'NonPublic,Public,Instance',
        $null, [Type[]]@([string]), $null)

    Write-TestGroup 'C# handler type is compiled and loadable'
    $handlerType = [NativeCommandOutputHandler]
    Assert-True 'NativeCommandOutputHandler type exists' ($null -ne $handlerType)

    Write-TestGroup 'Handler can be instantiated with a valid path'
    $tempFile = [System.IO.Path]::GetTempFileName()
    try {
        $handler = New-Object NativeCommandOutputHandler $tempFile
        Assert-True 'Handler instance created' ($null -ne $handler)
    } finally {
        Remove-Item $tempFile -ErrorAction SilentlyContinue
    }

    Write-TestGroup 'Handler writes data to capture file correctly'
    $tempFile = [System.IO.Path]::GetTempFileName()
    try {
        $handler = New-Object NativeCommandOutputHandler $tempFile
        $testLine = 'Test output line'
        $eventArgs = $ctorInfo.Invoke(@($testLine))
        $handler.OnOutputReceived($null, $eventArgs)

        $content = [System.IO.File]::ReadAllText($tempFile, [System.Text.UTF8Encoding]::new($false))
        Assert-True 'Capture file contains test line' $content.Contains($testLine)
    } finally {
        Remove-Item $tempFile -ErrorAction SilentlyContinue
    }

    Write-TestGroup 'Handler survives write to invalid path (try-catch prevents crash)'
    $invalidPath = 'Z:\nonexistent\path\file.txt'
    $handler = New-Object NativeCommandOutputHandler $invalidPath
    $testLine = 'This write should fail gracefully'
    $eventArgs = $ctorInfo.Invoke(@($testLine))
    $threw = $false
    try {
        $handler.OnOutputReceived($null, $eventArgs)
    } catch {
        $threw = $true
    }
    Assert-True 'Handler does not throw on write failure (try-catch works)' (-not $threw)

    Write-TestGroup 'Multiple writes accumulate in capture file'
    $tempFile = [System.IO.Path]::GetTempFileName()
    try {
        $handler = New-Object NativeCommandOutputHandler $tempFile
        $handler.OnOutputReceived($null, ($ctorInfo.Invoke(@('Line A'))))
        $handler.OnOutputReceived($null, ($ctorInfo.Invoke(@('Line B'))))
        $handler.OnOutputReceived($null, ($ctorInfo.Invoke(@('Line C'))))
        $content = [System.IO.File]::ReadAllText($tempFile, [System.Text.UTF8Encoding]::new($false))
        Assert-True 'All three lines captured' ($content.Contains('Line A') -and $content.Contains('Line B') -and $content.Contains('Line C'))
    } finally {
        Remove-Item $tempFile -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 22: Start-NativeCommand — WaitForExit fix present
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Start-NativeCommand: WaitForExit Fix ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'Start-NativeCommand function exists'
    Assert-True 'Function is defined' ($null -ne (Get-Command Start-NativeCommand -ErrorAction SilentlyContinue))

    # Static analysis: verify the parameterless WaitForExit() call exists
    # after the heartbeat loop and before CancelOutputRead()
    $commonPath = Join-Path $PSScriptRoot 'common.ps1'
    $commonContent = Get-Content -LiteralPath $commonPath -Raw -Encoding UTF8

    Write-TestGroup 'Parameterless WaitForExit() is called before CancelOutputRead'
    # Extract the drain section between the heartbeat loop and the timeout check
    $drainSection = ''
    if ($commonContent -match '(?s)# ── Drain final output.+?# ── Timeout:') {
        $drainSection = $Matches[0]
    }
    $hasWaitForExit = $drainSection -match '\$proc\.WaitForExit\(\)'
    Assert-True 'WaitForExit() (parameterless) is called in drain section' $hasWaitForExit

    Write-TestGroup 'WaitForExit() is called BEFORE CancelOutputRead (code calls)'
    # Use $proc. prefix to avoid matching comment text like
    # "CancelOutputRead() may discard data that hasn't been delivered"
    $waitPos = $drainSection.IndexOf('$proc.WaitForExit()')
    $cancelPos = $drainSection.IndexOf('$proc.CancelOutputRead()')
    Assert-True 'WaitForExit() appears before CancelOutputRead()' ($waitPos -ge 0 -and $cancelPos -ge 0 -and $waitPos -lt $cancelPos)

    Write-TestGroup 'WaitForExit() is wrapped in try-catch'
    $hasTryCatch = $drainSection -match '(?s)try\s*\{\s*\$proc\.WaitForExit\(\)'
    Assert-True 'WaitForExit() is wrapped in try-catch' $hasTryCatch

    Write-TestGroup 'Heartbeat loop polls HasExited before the drain section'
    $hasHeartbeatLoop = $commonContent -match 'while\s*\(-not\s*\$proc\.HasExited\)'
    Assert-True 'Heartbeat loop exists (polls HasExited)' $hasHeartbeatLoop

    Write-TestGroup 'C# handler has try-catch for File.AppendAllText'
    Assert-True 'Handler has try-catch' ($commonContent -match '(?s)try\s*\{.*File\.AppendAllText')
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test group 23: Truncation detection — simulated content validation
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Truncation Detection ━━━' -ForegroundColor Yellow

& {
    $browser4cliMode = 'dev'
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'Full output with all sections is NOT flagged as truncated'
    $fullOutput = @'
### A. Task Result
Task completed successfully.

### B. Execution Trace
Used commands: open, goto, tab-list.

### C. Issues Found

### Issue 1: Test issue
**Severity:** Medium
**Category:** UX

### D. Overall Assessment
All good, rating 8/10.
'@
    $hasSections = $fullOutput -match '(?i)(###?\s+[ABCD][.\s])'
    Assert-True 'Full output has structured sections' $hasSections

    $looksTruncated = (-not $hasSections) -and (
        ($fullOutput -match '(?i)is above\.?\s*$') -or
        ($fullOutput.Length -lt 500 -and $fullOutput -match '(?i)(report|issues?|evaluation)\s+(is|are|complete)')
    )
    Assert-True 'Full output is NOT flagged truncated' (-not $looksTruncated)

    Write-TestGroup 'Closing-only output ("is above") IS flagged as truncated'
    $closingOnly = 'The evaluation is complete. All testing has been performed, and the full report with 10 documented issues is above.'
    $hasSections2 = $closingOnly -match '(?i)(###?\s+[ABCD][.\s])'
    $looksTruncated2 = (-not $hasSections2) -and (
        ($closingOnly -match '(?i)is above\.?\s*$') -or
        ($closingOnly.Length -lt 500 -and $closingOnly -match '(?i)(report|issues?|evaluation)\s+(is|are|complete)')
    )
    Assert-True 'Closing-only output IS flagged truncated' $looksTruncated2

    Write-TestGroup 'Short completion message ("report is complete") IS flagged'
    $shortComplete = 'The full report is complete.'
    $hasSections3 = $shortComplete -match '(?i)(###?\s+[ABCD][.\s])'
    $looksTruncated3 = (-not $hasSections3) -and (
        ($shortComplete -match '(?i)is above\.?\s*$') -or
        ($shortComplete.Length -lt 500 -and $shortComplete -match '(?i)(report|issues?|evaluation)\s+(is|are|complete)')
    )
    Assert-True 'Short completion message IS flagged truncated' $looksTruncated3

    Write-TestGroup 'Long output without sections but without closing markers is NOT flagged'
    $longRandom = ('Random agent output without structured sections. ' * 30).Trim()
    $hasSections4 = $longRandom -match '(?i)(###?\s+[ABCD][.\s])'
    $looksTruncated4 = (-not $hasSections4) -and (
        ($longRandom -match '(?i)is above\.?\s*$') -or
        ($longRandom.Length -lt 500 -and $longRandom -match '(?i)(report|issues?|evaluation)\s+(is|are|complete)')
    )
    Assert-True 'Long random output NOT flagged truncated' (-not $looksTruncated4)

    Write-TestGroup 'Output with ### C. Issues Found section is NOT flagged'
    $hasCIssue = @'
Some preamble text.

### C. Issues Found

### Issue 1: Something broken
**Severity:** High
**Category:** Product
'@
    $hasSections5 = $hasCIssue -match '(?i)(###?\s+[ABCD][.\s])'
    $looksTruncated5 = (-not $hasSections5) -and (
        ($hasCIssue -match '(?i)is above\.?\s*$') -or
        ($hasCIssue.Length -lt 500 -and $hasCIssue -match '(?i)(report|issues?|evaluation)\s+(is|are|complete)')
    )
    Assert-True 'Output with C section NOT flagged truncated' (-not $looksTruncated5)

    Write-TestGroup 'Truncation pattern detects "is above" at end of line'
    $endOfLine = "The report is above.`n"
    $aboveMatch = $endOfLine -match '(?i)is above\.?\s*$'
    Assert-True 'Matches "is above." at end' $aboveMatch

    Write-TestGroup 'Truncation pattern does NOT match "above" mid-sentence'
    $midSentence = 'The above report contains 10 issues.'
    $aboveMatch2 = $midSentence -match '(?i)is above\.?\s*$'
    Assert-True 'Does NOT match "above" mid-sentence' (-not $aboveMatch2)

    Write-TestGroup 'Truncation detection variable exists in common.ps1'
    $commonPath = Join-Path $PSScriptRoot 'common.ps1'
    $commonContent = Get-Content -LiteralPath $commonPath -Raw -Encoding UTF8
    Assert-True 'looksTruncated variable exists' ($commonContent -match 'looksTruncated')
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
