#!/usr/bin/env pwsh
<#
.SYNOPSIS
Shared helpers for browser4-cli agent-scenario test scripts.

.DESCRIPTION
Dot-source this module to reuse the shared usability-evaluation prompt and the
standard agent invocation.  The prompt adapts to the environment automatically:

  - Dev (default):  `cargo run -- help`  + local `cli/skill/SKILL.md`.
  - Production:      `browser4-cli help` + `https://browser4.ioSKILL.md`.

Set `$browser4cliMode = 'production'` BEFORE dot-sourcing this module to switch
to production mode.

Every scenario script follows the same pattern:

    . "$PSScriptRoot/common.ps1"

    $taskPrompt = @"
    ...task-specific instructions...
"@
    $prompt = $generalPrompt + $taskPrompt
    Invoke-Agent -Prompt $prompt

#>

$ErrorActionPreference = "Stop"

# ── Mode detection ──────────────────────────────────────────────────────────
# The caller may set $browser4cliMode = 'production' before dot-sourcing.
# PowerShell here-strings expand variables, so $helpCmd is resolved when
# $generalPrompt is defined below.
if ($browser4cliMode -eq 'production') {
    $helpCmd   = '`browser4-cli help`'
    $skillPath = 'https://browser4.ioSKILL.md'
} else {
    $helpCmd   = '`cargo run -- help`'
    $skillPath = '`cli/skill/SKILL.md`'
}

# ── Path resolution ──────────────────────────────────────────────────────────
# Repo root is 3 levels up from scripts/ (scripts -> tests -> browser4-cli -> repo root)
$script:RepoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$script:IssuesReadyDir = [System.IO.Path]::GetFullPath(
    (Join-Path $script:RepoRoot 'coworker\tasks\200issues\draft\refine\0ready')
)

# ── Shared evaluation prompt ────────────────────────────────────────────────
# Every scenario prepends this to its task-specific prompt so the agent
# consistently evaluates browser4-cli usability while completing the task.
$generalPrompt = @"
You are evaluating the usability, discoverability, and reliability of browser4-cli while completing a real-world task.

## Preparation

Before performing any browser interaction:

1. Run $helpCmd.
2. Read $skillPath completely.
3. Learn the available commands, workflows, and conventions directly from the documentation.
4. Do not assume any prior knowledge of browser4-cli.

## Tool Usage Rules

* Use browser4-cli for ALL browser interactions.
* Do NOT use Playwright, Puppeteer, Selenium, CDP libraries, external browser APIs, or any other browser automation tool.
* If a browser action is required, first identify the documented browser4-cli command that should perform it.
* Prefer documented workflows over assumptions.
* If documentation is ambiguous, incomplete, inaccurate, outdated, or difficult to discover, record it as an issue.

## Evaluation Objective

Your goal is not only to complete the task, but also to evaluate the usability of browser4-cli from the perspective of a first-time user.

Actively look for issues involving:

### Installation & Setup

* Missing prerequisites
* Environment assumptions
* Setup complexity
* Platform-specific issues

### Discoverability

* Help output quality
* Command discoverability
* Missing examples
* Missing documentation

### Documentation

* Incomplete instructions
* Incorrect instructions
* Ambiguous wording
* Undocumented behavior
* Inconsistent terminology

### CLI Experience

* Command naming consistency
* Parameter naming consistency
* Workflow clarity
* Session management
* Browser lifecycle management
* State management

### Task Execution

* Navigation workflow
* Search workflow
* Content extraction workflow
* Form interaction workflow
* Waiting/synchronization behavior
* Error recovery

### Reliability

* Unexpected failures
* Flaky behavior
* Misleading outputs
* Poor error messages
* Silent failures

### User Experience

* Learnability
* Efficiency
* Cognitive load
* Friction points
* Missing shortcuts
* Missing quality-of-life features

## Investigation Guidelines

Whenever you encounter a problem:

1. Attempt to understand the root cause.
2. Determine whether it is:

   * Product issue
   * Documentation issue
   * UX issue
   * Reliability issue
   * Discoverability issue
3. Continue the task whenever reasonably possible.
4. Record all findings, even if a workaround exists.

## Deliverables

### A. Task Result

Provide the requested task outcome.

### B. Execution Trace

Summarize:

* Commands used
* Major steps performed
* Important decisions made
* Workarounds required

### C. Issues Found

For every issue discovered, provide:

#### Title

#### Severity

* Critical
* High
* Medium
* Low

#### Category

* Product
* Documentation
* UX
* Reliability
* Discoverability

#### Reproduction Steps

#### Expected Behavior

#### Actual Behavior

#### Suggested Improvement

### D. Overall Assessment

Include:

* Task completion status
* Estimated task success rate
* Number of issues found
* Major blockers
* Most confusing aspects
* Most valuable improvements
* Overall usability rating (1–10)

## Important

* Think like a new user who has never used browser4-cli before.
* Do not assume undocumented functionality exists.
* Prefer evidence gathered from actual usage over assumptions.
* Record both major and minor usability issues.
* The task is considered successful only if both the task itself and the usability evaluation are completed.

# Task

"@

# ── Issue extraction ─────────────────────────────────────────────────────────

function ConvertFrom-IssuesSection {
    <#
    .SYNOPSIS
        Best-effort parsing of individual issues from the "C. Issues Found" section
        of the agent output.
    .DESCRIPTION
        Extracts the text between the "C. Issues Found" and "D. Overall Assessment"
        headings, then splits on "#### Title" markers to isolate individual issues.
        Each issue is expected to follow the template from the $generalPrompt.

        Returns an array of hashtables with fields: Title, Severity, Category,
        Reproduction, Expected, Actual, Suggestion.
        Returns empty array if no issues can be parsed (the full output is always
        preserved by Write-IssuesToReadyQueue).
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content
    )

    # Normalize line endings to LF for reliable processing
    $normalized = $Content -replace '\r\n', "`n"

    # Find C section start — use simple string search for reliability
    $cIdx = -1
    $cMarkers = @('### C. Issues Found', '## C. Issues Found', '### C Issues Found')
    foreach ($m in $cMarkers) {
        $cIdx = $normalized.IndexOf($m, [StringComparison]::OrdinalIgnoreCase)
        if ($cIdx -ge 0) { break }
    }
    if ($cIdx -lt 0) { return @() }

    $cStart = $normalized.IndexOf("`n", $cIdx) + 1
    if ($cStart -le 0 -or $cStart -ge $normalized.Length) { return @() }

    # Find D section as the end boundary
    $dIdx = -1
    $dMarkers = @('### D. Overall Assessment', '## D. Overall Assessment', '### D Overall Assessment')
    foreach ($m in $dMarkers) {
        $dIdx = $normalized.IndexOf($m, $cStart, [StringComparison]::OrdinalIgnoreCase)
        if ($dIdx -ge 0) { break }
    }
    if ($dIdx -lt 0) { $dIdx = $normalized.Length }

    $section = $normalized.Substring($cStart, $dIdx - $cStart).Trim()
    if (-not $section) { return @() }

    # Split into individual issues at "#### Title" markers
    $blocks = @($section -split '(?=####\s+Title)') |
        Where-Object { $_ -match '####\s+Title' }

    if ($blocks.Count -eq 0) {
        # Try alternative: issues with ### headings
        $blocks = @($section -split '(?=###\s+)') |
            Where-Object { $_ -match '###\s+' -and $_ -notmatch '^###\s+[CD]\.' }
    }

    $results = [System.Collections.ArrayList]::new()
    # Use .ForEach() instead of foreach statement to avoid pipeline output leakage.
    # $_ is the current block in the .ForEach script block.
    $null = $blocks.ForEach({
        $issue = @{
            Title        = ''
            Severity     = ''
            Category     = ''
            Reproduction = ''
            Expected     = ''
            Actual       = ''
            Suggestion   = ''
        }

        # Extract each field using a bounded regex.
        # Use $null = if (...) {...} so the assignment does not leak to the output stream.
        $null = if ($_ -match '(?s)####\s*Title\s*\n(.+?)(?=\n####\s|\Z)')                { $issue.Title        = $Matches[1].Trim() }
        $null = if ($_ -match '(?s)####\s*Severity\s*\n(.+?)(?=\n####\s|\Z)')             { $issue.Severity     = $Matches[1].Trim() }
        $null = if ($_ -match '(?s)####\s*Category\s*\n(.+?)(?=\n####\s|\Z)')             { $issue.Category     = $Matches[1].Trim() }
        $null = if ($_ -match '(?s)####\s*Reproduction Steps?\s*\n(.+?)(?=\n####\s|\Z)')  { $issue.Reproduction = $Matches[1].Trim() }
        $null = if ($_ -match '(?s)####\s*Expected Behavior\s*\n(.+?)(?=\n####\s|\Z)')    { $issue.Expected     = $Matches[1].Trim() }
        $null = if ($_ -match '(?s)####\s*Actual Behavior\s*\n(.+?)(?=\n####\s|\Z)')      { $issue.Actual       = $Matches[1].Trim() }
        $null = if ($_ -match '(?s)####\s*Suggested Improvement\s*\n(.+?)(?=\n####\s|\Z)'){ $issue.Suggestion   = $Matches[1].Trim() }

        # Fallback: try bullet-point format for Severity and Category
        if (-not $issue.Severity) {
            $null = if ($_ -match 'Severity[:\s]*\*?\*?(Critical|High|Medium|Low)\*?\*?') { $issue.Severity = $Matches[1].Trim() }
        }
        if (-not $issue.Category) {
            $null = if ($_ -match 'Category[:\s]*\*?\*?(Product|Documentation|UX|Reliability|Discoverability)\*?\*?') { $issue.Category = $Matches[1].Trim() }
        }

        if ($issue.Title) {
            [void]$results.Add($issue)
        }
    })

    # Return as array (ArrayList.ToArray() avoids pipeline wrapping)
    return $results.ToArray()
}

# ── Issue file output ─────────────────────────────────────────────────────────

function Write-IssuesToReadyQueue {
    <#
    .SYNOPSIS
        Write the agent evaluation output to the 200issues draft ready queue.
    .DESCRIPTION
        Saves the complete agent output (containing A. Task Result, B. Execution Trace,
        C. Issues Found, D. Overall Assessment) as a markdown file in the
        200issues/draft/refine/0ready directory for downstream refinement.

        Also attempts best-effort parsing of individual issues from the C section
        via ConvertFrom-IssuesSection and writes each as a separate file.

        Always writes the full output regardless of whether individual issues
        can be parsed.
    .PARAMETER ScenarioName
        Short name identifying the scenario (e.g. "amazon", "hacker-news").
    .PARAMETER Content
        The full text output from the agent evaluation.
    .PARAMETER OutputDirectory
        Optional override for the ready queue directory. Defaults to $IssuesReadyDir.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScenarioName,

        [Parameter(Mandatory = $true)]
        [string]$Content,

        [string]$OutputDirectory = $script:IssuesReadyDir
    )

    if ([string]::IsNullOrWhiteSpace($Content)) {
        Write-Host "  WARNING: Cannot write empty content for '$ScenarioName'" -ForegroundColor Yellow
        return
    }

    # Ensure the output directory exists
    if (-not (Test-Path -LiteralPath $OutputDirectory)) {
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
        Write-Host "  Created output directory: $OutputDirectory" -ForegroundColor DarkGray
    }

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
    $safeName = $ScenarioName -replace '[\\/:*?"<>|]', '_'
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)

    # 1) Write the full output as a reference file
    $fullFileName = "$timestamp-$safeName.full.md"
    $fullFilePath = Join-Path $OutputDirectory $fullFileName
    $absoluteFullPath = [System.IO.Path]::GetFullPath($fullFilePath)
    [System.IO.File]::WriteAllText($absoluteFullPath, $Content, $utf8NoBom)
    Write-Host "  Wrote full output: $absoluteFullPath" -ForegroundColor DarkGray

    # 2) Best-effort: parse individual issues from the "C. Issues Found" section
    $issues = ConvertFrom-IssuesSection -Content $Content
    $issueIndex = 1
    foreach ($issue in $issues) {
        $paddedIndex = '{0:d3}' -f $issueIndex
        $issueFileName = "$timestamp-$safeName.issue-$paddedIndex.md"
        $issueFilePath = Join-Path $OutputDirectory $issueFileName
        $absoluteIssuePath = [System.IO.Path]::GetFullPath($issueFilePath)

        $issueBody = @"
# $($issue.Title)

**Severity:** $($issue.Severity)
**Category:** $($issue.Category)

## Reproduction Steps

$($issue.Reproduction)

## Expected Behavior

$($issue.Expected)

## Actual Behavior

$($issue.Actual)

## Suggested Improvement

$($issue.Suggestion)
"@
        [System.IO.File]::WriteAllText($absoluteIssuePath, $issueBody, $utf8NoBom)
        Write-Host "  Wrote issue: $absoluteIssuePath" -ForegroundColor DarkGray
        $issueIndex++
    }

    if ($issueIndex -eq 1) {
        Write-Host "  (No individual issues parsed — full output saved)" -ForegroundColor DarkGray
    } else {
        Write-Host "  Parsed $($issueIndex - 1) individual issue(s)" -ForegroundColor DarkGray
    }
}

# ── Agent invocation ────────────────────────────────────────────────────────

function Invoke-Agent {
    <#
    .SYNOPSIS
        Invoke Claude Code agent to run a scenario and evaluate browser4-cli usability.
    .DESCRIPTION
        Runs claude with the given prompt. When -ScenarioName is provided, captures
        output and writes evaluation results to the 200issues draft ready queue.
        When -ScenarioName is omitted, preserves the original behavior (direct call,
        real-time output, no capture).

        In capture mode, output is simultaneously streamed to the console (so the
        user can watch the agent work) and saved to a temp file for post-processing.
    .PARAMETER Prompt
        The full prompt including the general evaluation instructions and
        task-specific instructions.
    .PARAMETER ScenarioName
        Optional scenario name (e.g. "amazon", "hacker-news"). When provided, output
        is captured and written to the issues ready queue at $IssuesReadyDir.
    .PARAMETER OutputFile
        Optional explicit path to save the raw agent output. Auto-generated from
        ScenarioName and timestamp when omitted.
    .PARAMETER Silent
        Suppress status messages (passed through to claude --silent).
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,

        [string]$ScenarioName = '',

        [string]$OutputFile = '',

        [switch]$Silent
    )

    # ── Status header ────────────────────────────────────────────────────────
    if (-not $Silent) {
        $promptLen = $Prompt.Length
        $promptLines = ($Prompt -split "`n").Count
        Write-Host "Invoking Claude Code agent..." -ForegroundColor Cyan
        Write-Host "  Prompt: $promptLen chars, $promptLines lines" -ForegroundColor DarkGray
        if ($ScenarioName) {
            Write-Host "  Scenario: $ScenarioName (output will be captured)" -ForegroundColor DarkGray
        }
        Write-Host "  This may take several minutes — the agent runs browser4-cli commands" -ForegroundColor DarkGray
        Write-Host "  and evaluates usability. Output appears as the agent works." -ForegroundColor DarkGray
        Write-Host ""
    }

    # ── Path 1: Legacy mode (no capture) ─────────────────────────────────────
    # Preserves the exact original behavior for backward compatibility.
    if (-not $ScenarioName -and -not $OutputFile) {
        $claudeArgs = @('--dangerously-skip-permissions', '-p', $Prompt)
        if ($Silent) { $claudeArgs += '--silent' }
        claude @claudeArgs

        if (-not $Silent) {
            Write-Host ""
            Write-Host "Agent finished (exit code: $LASTEXITCODE)." `
                -ForegroundColor $(if ($LASTEXITCODE -eq 0) { 'Green' } else { 'Red' })
        }
        return
    }

    # ── Path 2: Capture mode (with ScenarioName or OutputFile) ────────────────
    $claudeArgs = @('--dangerously-skip-permissions', '-p', $Prompt)
    if ($Silent) { $claudeArgs += '--silent' }

    $tempFile = [System.IO.Path]::GetTempFileName()
    $writer = $null
    $exitCode = 0

    try {
        $writer = [System.IO.StreamWriter]::new($tempFile, $false, [System.Text.Encoding]::UTF8)

        # Run claude, tee output to both console and temp file in real time.
        # ForEach-Object passes each line through to the host while also
        # writing to the capture file.  This preserves the real-time UX.
        & claude @claudeArgs 2>&1 | ForEach-Object {
            $line = $_
            $writer.WriteLine($line)
            $writer.Flush()
            $line  # pass through to console
        }
        $exitCode = $LASTEXITCODE
    }
    catch {
        $exitCode = 1
    }
    finally {
        if ($writer) { $writer.Dispose() }
    }

    if (-not $Silent) {
        Write-Host ""
        Write-Host "Agent finished (exit code: $exitCode)." `
            -ForegroundColor $(if ($exitCode -eq 0) { 'Green' } else { 'Red' })
    }

    # Read back the captured output
    $capturedOutput = ''
    if (Test-Path $tempFile) {
        $capturedOutput = Get-Content -Path $tempFile -Raw -Encoding UTF8
        Remove-Item $tempFile -ErrorAction SilentlyContinue
    }

    if ([string]::IsNullOrWhiteSpace($capturedOutput)) {
        Write-Host "  WARNING: No output captured from agent." -ForegroundColor Yellow
        return
    }

    # Write to the 200issues ready queue
    if ($ScenarioName) {
        Write-IssuesToReadyQueue -ScenarioName $ScenarioName -Content $capturedOutput
    }

    # Save raw output to local file if requested
    if ($OutputFile) {
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        $outputDir = Split-Path -Parent $OutputFile
        if ($outputDir -and -not (Test-Path $outputDir)) {
            New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
        }
        [System.IO.File]::WriteAllText($OutputFile, $capturedOutput, $utf8NoBom)
        Write-Host "  Saved raw output: $OutputFile" -ForegroundColor DarkGray
    }
}
