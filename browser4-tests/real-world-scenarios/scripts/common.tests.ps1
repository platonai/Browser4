#!/usr/bin/env pwsh
<#
.SYNOPSIS
Unit tests for common.ps1 — the shared helpers module for agent-scenario scripts.

.DESCRIPTION
Tests common.ps1 functionality:
  1. Mode detection ($browser4cliMode → $helpCmd, $skillPath)
  2. $generalPrompt content and structure
  3. Invoke-Agent function signature and argument forwarding
  4. Path resolution ($IssuesReadyDir, $RepoRoot)
  5. ConvertFrom-IssuesSection parsing
  6. Write-IssuesToReadyQueue file output
  7. Invoke-Agent backward compatibility

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
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup '$helpCmd in dev mode'
    Assert-Equal 'is exactly "./b4.ps1 help"' `
        './b4.ps1 help' $helpCmd
    Assert-Contains 'contains b4.ps1' $helpCmd 'b4.ps1'
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
        './b4.ps1 help' $helpCmd
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
    # Null or empty mode should fall back to dev.
    $browser4cliMode = $null
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup 'null mode falls back to dev'
    Assert-Equal '$helpCmd resolves to dev default' `
        './b4.ps1 help' $helpCmd
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
    Assert-Contains 'contains ./b4.ps1 help' $generalPrompt './b4.ps1 help'
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
    Assert-NotContains 'should NOT contain ./b4.ps1 help' $generalPrompt './b4.ps1 help'
    Assert-NotContains 'should NOT contain skills/browser4-cli/SKILL.md' $generalPrompt 'skills/browser4-cli/SKILL.md'
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
    # Mock the native-command boundary to capture the arguments Invoke-Agent forwards.
    $script:CapturedArgs = $null

    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

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

    # Clean up the mock so it does not leak.
    Remove-Item function:Start-NativeCommand -ErrorAction SilentlyContinue
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

    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
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
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    Write-TestGroup '$script:IssuesReadyDir is an absolute path'
    $isAbs = [System.IO.Path]::IsPathRooted([string]$script:IssuesReadyDir)
    Assert-True 'IssuesReadyDir is absolute' $isAbs

    Write-TestGroup '$script:IssuesReadyDir ends with the expected suffix'
    $expectedSuffix = 'issues\draft'
    $normalized = ([string]$script:IssuesReadyDir) -replace '[/\\]', '\'
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
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
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
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
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
# Test group 13: Write-IssuesToReadyQueue
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ''
Write-Host '━━━ Write-IssuesToReadyQueue ━━━' -ForegroundColor Yellow

& {
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
    . "$PSScriptRoot/common.ps1"

    # Use a temp directory to avoid polluting the real ready queue
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
        Write-IssuesToReadyQueue -ScenarioName 'unit-test' -Content $sampleContent -OutputDirectory $tempDir
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
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
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
    Remove-Variable -Name 'browser4cliMode' -Scope Local -ErrorAction SilentlyContinue
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
