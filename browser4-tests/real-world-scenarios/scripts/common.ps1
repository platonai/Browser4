#!/usr/bin/env pwsh
<#
.SYNOPSIS
Shared helpers for browser4-cli agent-scenario test scripts.

.DESCRIPTION
Dot-source this module to reuse the shared usability-evaluation prompt and the
standard agent invocation.  The prompt adapts to the environment automatically:

  - Dev (default):  `cargo run -- help`  + local `skill/SKILL.md`.
  - Production:      `browser4-cli help` + `https://browser4.io/SKILL.md`.

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
# The caller may set $browser4cliMode = 'production' before dot-sourcing, or
# set $env:BROWSER4CLI_MODE = 'production' (useful when run-tests.ps1 spawns a
# child pwsh process — env vars cross process boundaries, PS vars don't).
if (-not $browser4cliMode -and $env:BROWSER4CLI_MODE) {
    $browser4cliMode = $env:BROWSER4CLI_MODE
}
# PowerShell here-strings expand variables, so $helpCmd / $cliInvocation are
# resolved when $generalPrompt is defined below.
if ($browser4cliMode -eq 'production') {
    $helpCmd        = '`browser4-cli help`'
    $skillPath      = 'https://browser4.io/SKILL.md'
    $cliInvocation  = '`browser4-cli`'
} else {
    # Dev mode: use cargo run so the agent tests the locally-built CLI and
    # the daemon auto-starts the locally-built backend JAR.  The repo root
    # is the CWD when the agent runs, so the relative "cd" resolves correctly.
    $helpCmd        = '`cd cli/browser4-cli && cargo run -- help`'
    $skillPath      = '`skill/SKILL.md`'
    $cliInvocation  = '`cd cli/browser4-cli && cargo run --`'
}

# ── Path resolution ──────────────────────────────────────────────────────────
# Repo root is 3 levels up from scripts/ (scripts -> tests -> browser4-cli -> repo root)
$script:RepoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$script:IssuesReadyDir = [System.IO.Path]::GetFullPath(
    (Join-Path $script:RepoRoot 'coworker\tasks\200issues\draft')
)

# ── Shared evaluation prompt ────────────────────────────────────────────────
# Every scenario prepends this to its task-specific prompt so the agent
# consistently evaluates browser4-cli usability while completing the task.
$generalPrompt = @"
You are evaluating the usability, discoverability, and reliability of browser4-cli while completing a real-world task.

## Preparation

Before performing any browser interaction:

0. Verify your working directory is the repository root (the directory containing `cli/`, `skill/`, `pom.xml`, etc.). If you are not in the repo root, navigate there first with `cd` using the absolute path to the repository. All `cd cli/browser4-cli` commands assume you start from the repo root.
1. Run $helpCmd.
2. Read $skillPath completely.
3. Learn the available commands, workflows, and conventions directly from the documentation.
4. Do not assume any prior knowledge of browser4-cli.

## Command Invocation

Every browser4-cli command in this session MUST be invoked as:

$cliInvocation <command>

For example:
  $cliInvocation goto "https://example.com"
  $cliInvocation snapshot -i
  $cliInvocation click e5

Do NOT use a plain `browser4-cli` command unless the invocation above fails after a genuine attempt.  Using the wrong invocation will test a stale installed binary instead of the local source code, invalidating the evaluation.

## Tool Usage Rules

* Use the invocation method above for ALL browser interactions.
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

For every issue discovered, provide a structured entry using the format below.
Each issue MUST begin with an `### Issue N: <title>` header and use `**Bold Label:**`
lines for every field.

#### Required format for each issue:

### Issue N: <brief descriptive title>

**Severity:** Critical | High | Medium | Low

**Category:** Product | Documentation | UX | Reliability | Discoverability

**Reproduction:** Exact command(s) or steps to reproduce the issue.

**Expected:** What should have happened.

**Actual:** What actually happened.

**Root Cause:** Your best analysis of the technical cause. Infer from observed
behavior when possible; note what investigation is needed when uncertain. This
is essential for an AI coder to fix the issue later.

**Code Pointer:** File path and function name where a fix should likely be
applied (e.g. `cli/browser4-cli/src/snapshot.rs:render_snapshot()`). If unknown,
leave the value empty — a follow-up analysis will fill it in.

**AI Suggested Improvement:**
- First concrete suggestion (use a bullet list — each suggestion on its own line)
- Second concrete suggestion
- Additional suggestions as needed

**Human Review (TOP PRIORITY):** (leave empty — reserved for human review)

Use `---` (horizontal rule) to separate issues.

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
        headings and isolates individual issues.

        Supports two formats (newer first, with fallback):
          1. New:  "### Issue N: <title>" headers with "**Key:** Value" fields
          2. Old:  "#### Title" headers with "#### Key" / "value" pairs

        Returns an array of hashtables with fields: Title, Severity, Category,
        Reproduction, Expected, Actual, RootCause, CodePointer, Review, Suggestion.
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

    # ── Strategy 1 (new format): "### Issue N: <title>" headers ──────────────
    # Split on "### Issue" followed by optional "#" and a number, then ":"
    $blocks = @($section -split '(?=###\s+Issue\s+)') |
        Where-Object { $_ -match '###\s+Issue\s+' }

    # ── Strategy 2 (old format): "#### Title" blocks ──────────────────────────
    if ($blocks.Count -eq 0) {
        $blocks = @($section -split '(?=####\s+Title)') |
            Where-Object { $_ -match '####\s+Title' }
    }

    # ── Strategy 3 (generic): any ### heading (excluding C/D section heads) ──
    if ($blocks.Count -eq 0) {
        $blocks = @($section -split '(?=###\s+)') |
            Where-Object { $_ -match '###\s+' -and $_ -notmatch '^###\s+[CD]\.' }
    }

    # ── Field-name mapping: bold label → hashtable key ──────────────────────
    $fieldMap = @{
        'Severity'                     = 'Severity'
        'Category'                     = 'Category'
        'Reproduction'                 = 'Reproduction'
        'Expected'                     = 'Expected'
        'Actual'                       = 'Actual'
        'Root Cause'                   = 'RootCause'
        'Code Pointer'                 = 'CodePointer'
        'Review'                       = 'Review'
        'Human Review (TOP PRIORITY)'  = 'Review'
        'Suggested Improvement'        = 'Suggestion'
        'AI Suggested Improvement'     = 'Suggestion'
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
            RootCause    = ''
            CodePointer  = ''
            Review       = ''
            Suggestion   = ''
        }

        # ── Extract title ──────────────────────────────────────────────────
        # New format: "### Issue N: <title>"
        $null = if ($_ -match '###\s+Issue\s+#?\d+:\s*(.+?)(?:\n|$)') {
            $issue.Title = $Matches[1].Trim()
        }
        # Old format: "#### Title\n<title text>"
        if (-not $issue.Title) {
            $null = if ($_ -match '(?s)####\s*Title\s*\n(.+?)(?=\n####\s|\n###\s|\Z)') {
                $issue.Title = $Matches[1].Trim()
            }
        }

        # ── Extract "**Key:** Value" fields (new format) ────────────────────
        # Position-based parsing: find each **Key:** marker position, then
        # the value is everything between that marker and the next one.
        # This is more robust than a single regex with lookahead because it
        # correctly handles empty fields, multi-line values, and fields in
        # any order.
        $keyPositions = [System.Collections.ArrayList]::new()
        foreach ($key in $fieldMap.Keys) {
            $escapedKey = [regex]::Escape($key)
            $markerPattern = "\*\*${escapedKey}:\*\*"
            $markerMatch = [regex]::Match($_, $markerPattern)
            if ($markerMatch.Success) {
                [void]$keyPositions.Add(@{
                    FieldName = $fieldMap[$key]
                    Start     = $markerMatch.Index
                    End       = $markerMatch.Index + $markerMatch.Length
                })
            }
        }

        # Sort by position in the block so we can extract text between markers
        $sorted = @($keyPositions | Sort-Object Start)
        for ($i = 0; $i -lt $sorted.Count; $i++) {
            $kp = $sorted[$i]
            $valueStart = $kp.End
            if ($i -lt $sorted.Count - 1) {
                $valueEnd = $sorted[$i + 1].Start
            } else {
                $valueEnd = $_.Length
            }
            $rawValue = $_.Substring($valueStart, $valueEnd - $valueStart)

            # Clean up: trim leading/trailing whitespace, strip trailing
            # "---" separators that belong between issues (not to the value)
            $cleanValue = $rawValue -replace '(?s)^\s+', '' -replace '(?s)\s+$', ''
            $cleanValue = $cleanValue -replace '(?s)\n---\s*$', ''

            if ($cleanValue) {
                $issue[$kp.FieldName] = $cleanValue
            }
        }

        # ── Fallback: old-format "#### Key\nvalue" extraction ────────────────
        if (-not $issue.Severity) {
            $null = if ($_ -match '(?s)####\s*Severity\s*\n(.+?)(?=\n####\s|\Z)') {
                $issue.Severity = $Matches[1].Trim()
            }
        }
        if (-not $issue.Category) {
            $null = if ($_ -match '(?s)####\s*Category\s*\n(.+?)(?=\n####\s|\Z)') {
                $issue.Category = $Matches[1].Trim()
            }
        }
        if (-not $issue.Reproduction) {
            $null = if ($_ -match '(?s)####\s*Reproduction Steps?\s*\n(.+?)(?=\n####\s|\Z)') {
                $issue.Reproduction = $Matches[1].Trim()
            }
        }
        if (-not $issue.Expected) {
            $null = if ($_ -match '(?s)####\s*Expected Behavior\s*\n(.+?)(?=\n####\s|\Z)') {
                $issue.Expected = $Matches[1].Trim()
            }
        }
        if (-not $issue.Actual) {
            $null = if ($_ -match '(?s)####\s*Actual Behavior\s*\n(.+?)(?=\n####\s|\Z)') {
                $issue.Actual = $Matches[1].Trim()
            }
        }
        if (-not $issue.Suggestion) {
            $null = if ($_ -match '(?s)####\s*Suggested Improvement\s*\n(.+?)(?=\n####\s|\Z)') {
                $issue.Suggestion = $Matches[1].Trim()
            }
        }

        # ── Last-resort fallback: bullet-point Severity / Category ──────────
        if (-not $issue.Severity) {
            $null = if ($_ -match 'Severity[:\s]*\*?\*?(Critical|High|Medium|Low)\*?\*?') {
                $issue.Severity = $Matches[1].Trim()
            }
        }
        if (-not $issue.Category) {
            $null = if ($_ -match 'Category[:\s]*\*?\*?(Product|Documentation|UX|Reliability|Discoverability)\*?\*?') {
                $issue.Category = $Matches[1].Trim()
            }
        }

        if ($issue.Title) {
            [void]$results.Add($issue)
        }
    })

    # Return as array (ArrayList.ToArray() avoids pipeline wrapping)
    return $results.ToArray()
}

# ── Background context extraction ──────────────────────────────────────────────

function Extract-BackgroundContext {
    <#
    .SYNOPSIS
        Extracts task background and execution context from agent evaluation output.
    .DESCRIPTION
        Parses Sections A (Task Result) and B (Execution Trace) from the full
        agent output to provide the context an AI needs to understand and reproduce
        the reported issues.  Handles both ## and ### heading levels, optional
        emoji/decorations in headings, and varied subsection formats within the
        execution trace.
    .OUTPUTS
        Hashtable with keys: TaskSummary, ExecutionTrace, Commands, Workarounds.
        Empty strings for sections that cannot be extracted.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content
    )

    $normalized = $Content -replace '\r\n', "`n"

    $result = @{
        TaskSummary    = ''
        ExecutionTrace = ''
        Commands       = ''
        Workarounds    = ''
    }

    # ── Extract Section A (Task Result) ──────────────────────────────────────
    # Handles: "### A. Task Result", "## A. Task Result", "## ✅ Task Result: ..."
    $aStart = -1
    $aMarkers = @(
        '### A. Task Result', '## A. Task Result', '## ✅ Task Result',
        '### A Task Result', '## A Task Result', '## Task Result'
    )
    foreach ($m in $aMarkers) {
        $aStart = $normalized.IndexOf($m, [StringComparison]::OrdinalIgnoreCase)
        if ($aStart -ge 0) { break }
    }

    if ($aStart -ge 0) {
        $aContentStart = $normalized.IndexOf("`n", $aStart) + 1
        if ($aContentStart -le 0) { $aContentStart = $aStart }

        $bMarkers = @(
            '### B. Execution Trace', '## B. Execution Trace',
            '### B Execution Trace', '## B Execution Trace',
            '## B. Execution Trace'
        )
        $aEnd = $normalized.Length
        foreach ($m in $bMarkers) {
            $idx = $normalized.IndexOf($m, $aContentStart, [StringComparison]::OrdinalIgnoreCase)
            if ($idx -ge 0) { $aEnd = $idx; break }
        }
        $len = [Math]::Max(0, $aEnd - $aContentStart)
        $result.TaskSummary = $normalized.Substring($aContentStart, $len).Trim()
    }

    # ── Extract Section B (Execution Trace) ──────────────────────────────────
    $bStart = -1
    $bMarkers = @(
        '### B. Execution Trace', '## B. Execution Trace',
        '### B Execution Trace', '## B Execution Trace',
        '## B. Execution Trace'
    )
    foreach ($m in $bMarkers) {
        $bStart = $normalized.IndexOf($m, [StringComparison]::OrdinalIgnoreCase)
        if ($bStart -ge 0) { break }
    }

    if ($bStart -ge 0) {
        $bContentStart = $normalized.IndexOf("`n", $bStart) + 1
        if ($bContentStart -le 0) { $bContentStart = $bStart }

        $cMarkers = @(
            '### C. Issues Found', '## C. Issues Found',
            '### C Issues Found', '## C Issues Found',
            '## C. Issues Found'
        )
        $bEnd = $normalized.Length
        foreach ($m in $cMarkers) {
            $idx = $normalized.IndexOf($m, $bContentStart, [StringComparison]::OrdinalIgnoreCase)
            if ($idx -ge 0) { $bEnd = $idx; break }
        }
        $len = [Math]::Max(0, $bEnd - $bContentStart)
        $fullTrace = $normalized.Substring($bContentStart, $len).Trim()
        $result.ExecutionTrace = $fullTrace

        # Extract "Commands Used" subsection (if present)
        if ($fullTrace -match '(?s)(?:###\s+)?Commands?\s*Used[:\s]*\n(.+?)(?=\n###\s|\n##\s|\Z)') {
            $result.Commands = $Matches[1].Trim()
        }
        # Extract "Workarounds Required" subsection (if present)
        if ($fullTrace -match '(?s)(?:###\s+)?Workarounds?\s*Required[:\s]*\n(.+?)(?=\n###\s|\n##\s|\Z)') {
            $result.Workarounds = $Matches[1].Trim()
        }
    }

    return $result
}

# ── Issue file output ─────────────────────────────────────────────────────────

function Write-IssuesToReadyQueue {
    <#
    .SYNOPSIS
        Write the agent evaluation output to the 200issues draft ready queue.
    .DESCRIPTION
        Saves the complete agent output (containing A. Task Result, B. Execution Trace,
        C. Issues Found, D. Overall Assessment) as a markdown file in the
        200issues/draft directory for downstream refinement.

        Also extracts background context (Sections A + B) and parses individual
        issues from Section C, then writes a SINGLE consolidated issues file
        (.issues.md) containing all issues, their reproduction context, and a
        reproduction guide — because the issues discovered in a single scenario
        are often interrelated and should be analyzed together.

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

    # 2) Extract background context (Sections A + B) for AI reproduction
    $bg = Extract-BackgroundContext -Content $Content

    # 3) Parse individual issues from Section C
    $issues = ConvertFrom-IssuesSection -Content $Content

    # 4) Write a SINGLE consolidated issues file with background context and
    #    reproduction guide.  Writing all issues together preserves their
    #    interrelationships — issues in one scenario often share root causes,
    #    share reproduction environments, or cascade from each other.
    $consFileName = "$timestamp-$safeName.issues.md"
    $consFilePath = Join-Path $OutputDirectory $consFileName
    $absoluteConsPath = [System.IO.Path]::GetFullPath($consFilePath)

    # Build the consolidated file body
    $consBody = "# Issues: $ScenarioName`n`n"
    $consBody += "> **Source:** ``$fullFileName`` | **Date:** $timestamp | "
    $consBody += "**Mode:** $(if ($browser4cliMode -eq 'production') { 'production' } else { 'dev' })`n`n"

    # ── Background section ──────────────────────────────────────────────────
    if ($bg.TaskSummary) {
        $consBody += "## Scenario Background`n`n"
        $consBody += "### Task`n`n"
        $consBody += "$($bg.TaskSummary)`n`n"
    }

    if ($bg.ExecutionTrace) {
        $consBody += "### Execution Context`n`n"
        if ($bg.Commands) {
            $consBody += "**Key Commands:**`n`n$($bg.Commands)`n`n"
        }
        if ($bg.Workarounds) {
            $consBody += "**Workarounds Applied During Task:**`n`n$($bg.Workarounds)`n`n"
        }
        # If we have execution trace but couldn't extract subsections, include
        # a condensed version (first 800 chars) so the AI has some context.
        if (-not $bg.Commands -and -not $bg.Workarounds) {
            $condensed = $bg.ExecutionTrace
            if ($condensed.Length -gt 800) {
                $condensed = $condensed.Substring(0, 800) + "...`n`n(truncated — see full.md for complete trace)"
            }
            $consBody += "$condensed`n`n"
        }
    }

    $consBody += "---`n`n"

    # ── Issues section ──────────────────────────────────────────────────────
    if ($issues.Count -gt 0) {
        $consBody += "## Issues Found ($($issues.Count) issue$(if ($issues.Count -ne 1) { 's' }))`n`n"
        $issueIndex = 0
        foreach ($issue in $issues) {
            $issueIndex++
            $consBody += "### Issue $issueIndex`: $($issue.Title)`n`n"
            $consBody += "**Severity:** $($issue.Severity)`n"
            $consBody += "**Category:** $($issue.Category)`n`n"

            if ($issue.Reproduction) {
                $consBody += "#### Reproduction`n`n$($issue.Reproduction)`n`n"
            }
            if ($issue.Expected) {
                $consBody += "#### Expected Behavior`n`n$($issue.Expected)`n`n"
            }
            if ($issue.Actual) {
                $consBody += "#### Actual Behavior`n`n$($issue.Actual)`n`n"
            }
            if ($issue.RootCause) {
                $consBody += "#### Root Cause Analysis`n`n$($issue.RootCause)`n`n"
            }
            if ($issue.CodePointer) {
                $consBody += "#### Code Pointer`n`n``$($issue.CodePointer)```n`n"
            }
            if ($issue.Suggestion) {
                $consBody += "#### AI Suggested Improvement`n`n$($issue.Suggestion)`n`n"
            }
            if ($issue.Review) {
                $consBody += "#### Human Review`n`n$($issue.Review)`n`n"
            }

            $consBody += "---`n`n"
        }

        # ── Reproduction guide ──────────────────────────────────────────────
        # Synthesize a practical reproduction guide that an AI coder can follow.
        $consBody += "## How to Reproduce`n`n"
        $consBody += "### Common Setup`n`n"
        $consBody += "1. Clone the repository and ``cd`` to the repo root.`n"
        if ($browser4cliMode -eq 'production') {
            $consBody += "2. Install browser4-cli: ``cargo install --path cli/browser4-cli```n"
            $consBody += "3. Ensure the backend server is running.`n"
            $consBody += "4. All commands: ``browser4-cli <command>```n`n"
        } else {
            $consBody += "2. Build the CLI: ``cd cli/browser4-cli && cargo build```n"
            $consBody += "3. The backend server starts automatically in dev mode.`n"
            $consBody += "4. All commands from repo root: ``cd cli/browser4-cli && cargo run -- <command>```n`n"
        }

        $consBody += "### Per-Issue Reproduction Steps`n`n"
        $issueIndex = 0
        foreach ($issue in $issues) {
            $issueIndex++
            $consBody += "#### Issue $issueIndex`: $($issue.Title)`n`n"
            if ($issue.Reproduction) {
                $consBody += "$($issue.Reproduction)`n`n"
            } else {
                $consBody += "(No reproduction steps recorded — see full.md for surrounding context)`n`n"
            }
        }
    } else {
        $consBody += "## Issues Found (0)`n`n"
        $consBody += "No issues could be parsed from Section C of the agent output.`n`n"
        $consBody += "See ``$fullFileName`` for the complete evaluation output.`n`n"
    }

    [System.IO.File]::WriteAllText($absoluteConsPath, $consBody, $utf8NoBom)
    Write-Host "  Wrote consolidated issues: $absoluteConsPath" -ForegroundColor DarkGray
    if ($issues.Count -gt 0) {
        Write-Host "  $($issues.Count) issue(s) in one file (interrelated issues stay together)" -ForegroundColor DarkGray
    } else {
        Write-Host "  (No individual issues parsed -- full output + background context saved)" -ForegroundColor DarkGray
    }
}

# ── Task file parsing ───────────────────────────────────────────────────────────

function Read-TaskFile {
    <#
    .SYNOPSIS
        Parse a task markdown file, returning the scenario name and body.
    .DESCRIPTION
        Reads a .md task file, extracts the first "# Heading" as the scenario
        name, and returns the remaining content (heading stripped, leading
        blank lines trimmed) as the body.

        Returns a PSCustomObject with Name and Body properties.
        Throws a terminating error if the file is missing, empty, or
        contains no body content after the heading.
    .PARAMETER Path
        Absolute or relative path to the .md task file.
    .OUTPUTS
        PSCustomObject with Name (string) and Body (string).
    .EXAMPLE
        $task = Read-TaskFile -Path 'tasks/search-summary.md'
        $task.Name  # "search-summary"
        $task.Body  # "1. Go to ..."
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Task file not found: $Path"
    }

    $rawContent = Get-Content -Path $Path -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($rawContent)) {
        throw "Task file is empty: $Path"
    }

    $name = ''
    $body = $rawContent

    # Match the first "# Heading" (optionally preceded by whitespace).
    if ($rawContent -match '(?m)^\s*#\s+(.+?)\s*$') {
        $name = $Matches[1].Trim()
        # Remove the heading line and any following blank lines.
        $body = $rawContent -replace '(?m)^\s*#\s+.+?\s*\r?\n[\s\r\n]*', ''
    }

    $body = $body.TrimStart()
    if ([string]::IsNullOrWhiteSpace($body)) {
        throw "No task body found after heading in: $Path"
    }

    return [PSCustomObject]@{
        Name = $name
        Body = $body
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Safe native-command invocation
# ═══════════════════════════════════════════════════════════════════════════════
#
# When PowerShell pipes a native (non-PowerShell) command and writes the output
# to a file, three things reliably produce garbled text.  Every ps1 script that
# captures native-command output must handle ALL THREE:
#
# 1. ENCODING MISMATCH
#    PowerShell decodes native-command stdout through [Console]::OutputEncoding,
#    which defaults to the system ANSI code page (cp1252 / cp936 / …).
#    Modern CLI tools (Node.js, Rust, Go) emit UTF-8.
#    Mismatch → every non-ASCII byte is reinterpreted as a code-page glyph.
#    FIX: set [Console]::OutputEncoding = UTF-8 before invocation; restore after.
#
# 2. ERROR-OBJECT LEAKAGE
#    The "2>&1" redirect merges stderr into the pipeline as ErrorRecord *objects*,
#    not strings.  Writing them raw to a StreamWriter calls .ToString(), which
#    emits the FQ type name ("System.Management.Automation.ErrorRecord …") instead
#    of the error message.
#    FIX: coerce every pipeline object to string with "$_" before writing.
#
# 3. BOM INCONSISTENCY
#    [System.Text.Encoding]::UTF8 includes a 3-byte BOM in .NET Framework / .NET.
#    Mixing BOM and non-BOM sources in a single pipeline writes garbage bytes at
#    file boundaries.
#    FIX: use [System.Text.UTF8Encoding]::new($false) for all file I/O.
#
# Use the Start-NativeCommand helper below; it applies all three fixes.

function Start-NativeCommand {
    <#
    .SYNOPSIS
        Invoke a native command safely, capturing combined stdout+stderr to a file.

    .DESCRIPTION
        Wraps a native command invocation with the three fixes described above:
        UTF-8 output decoding, safe string coercion of ErrorRecord objects, and
        BOM-free file I/O.  Output is streamed to the console in real time while
        simultaneously written to the capture file.

    .PARAMETER FilePath
        Path to the native executable.

    .PARAMETER ArgumentList
        Array of arguments to pass to the command.

    .PARAMETER CaptureFile
        File path to capture combined stdout+stderr (UTF-8 without BOM).  When
        omitted, output goes to the console only.

    .PARAMETER PassThru
        When set, also returns the captured output as a single string.

    .EXAMPLE
        Start-NativeCommand -FilePath 'claude' -ArgumentList @('-p', $prompt) `
            -CaptureFile $tempFile

    .EXAMPLE
        $out = Start-NativeCommand -FilePath 'node' -ArgumentList @('script.js') `
            -CaptureFile $log -PassThru
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,

        [string[]] $ArgumentList = @(),

        [string] $CaptureFile,

        [switch] $PassThru
    )

    $writer = $null
    $exitCode = 0
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)

    try {
        if ($CaptureFile) {
            $writer = [System.IO.StreamWriter]::new($CaptureFile, $false, $utf8NoBom)
        }

        # Fix 1: decode native-command stdout as UTF-8
        $prevEncoding = [Console]::OutputEncoding
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8

        try {
            & $FilePath @ArgumentList 2>&1 | ForEach-Object {
                # Fix 2: "$_" safely coerces ErrorRecord / string / etc.
                $line = "$_"
                if ($writer) {
                    $writer.WriteLine($line)
                    $writer.Flush()
                }
                # Write directly to the console host so the user sees real-time
                # output without polluting the function's output stream.
                # The function returns only the integer exit code (line 536).
                [Console]::WriteLine($line)
            }
            $exitCode = $LASTEXITCODE
        } finally {
            [Console]::OutputEncoding = $prevEncoding
        }
    } finally {
        if ($writer) { $writer.Dispose() }
    }

    # Let the caller decide what to do with the exit code; also set it in the
    # script scope so $LASTEXITCODE is visible to callers that check it directly.
    $script:LastNativeExitCode = $exitCode

    if ($PassThru -and $CaptureFile -and (Test-Path -LiteralPath $CaptureFile)) {
        $content = [System.IO.File]::ReadAllText(
            [System.IO.Path]::GetFullPath($CaptureFile), $utf8NoBom
        )
        Remove-Item -LiteralPath $CaptureFile -ErrorAction SilentlyContinue
        return $content
    }

    return $exitCode
}

# ── CLI version check ────────────────────────────────────────────────────────

function Assert-Browser4CliLatest {
    <#
    .SYNOPSIS
        Checks that the installed browser4-cli is at the expected version.
    .DESCRIPTION
        Reads the expected version from cli/VERSION-CLI in the repo root and
        compares it against the installed binary.

        In production mode, runs `browser4-cli --version` to get the installed
        version.  If it differs from the expected version, writes a prominent
        warning with upgrade instructions and returns a non-zero exit code so
        the caller can abort.

        In dev mode, simply reports the expected version (cargo run always
        builds from the latest source).
    .PARAMETER Silent
        Suppress informational messages.  Warnings are still emitted.
    .OUTPUTS
        Integer.  Returns 0 when everything is up to date or the check is
        not applicable; returns 1 when the installed version is outdated.
    #>
    param(
        [switch] $Silent
    )

    # Read the expected version from cli/VERSION-CLI (the canonical source).
    $versionCliPath = Join-Path $script:RepoRoot 'cli\VERSION-CLI'
    if (-not (Test-Path -LiteralPath $versionCliPath -PathType Leaf)) {
        if (-not $Silent) {
            Write-Host "WARNING: VERSION-CLI not found at $versionCliPath -- cannot verify CLI version." -ForegroundColor Yellow
        }
        return 0
    }

    $expectedVersion = (Get-Content -LiteralPath $versionCliPath -TotalCount 1).Trim()
    if (-not $expectedVersion) {
        if (-not $Silent) {
            Write-Host "WARNING: VERSION-CLI is empty -- cannot verify CLI version." -ForegroundColor Yellow
        }
        return 0
    }

    # ── Dev mode: cargo run always builds from the latest source ──────────
    if ($browser4cliMode -ne 'production') {
        if (-not $Silent) {
            Write-Host "Dev mode: cargo run builds browser4-cli from source (expected v$expectedVersion)." -ForegroundColor DarkGray
        }
        return 0
    }

    # ── Production mode: check the installed binary ───────────────────────
    $installedVersion = ''
    try {
        $versionOutput = & browser4-cli --version 2>&1 | Out-String
        if ($versionOutput -match '(\d+\.\d+\.\d+)') {
            $installedVersion = $Matches[1].Trim()
        }
    } catch {
        Write-Host "WARNING: Could not run 'browser4-cli --version'." -ForegroundColor Yellow
        Write-Host "  $_" -ForegroundColor DarkGray
        Write-Host '  Ensure browser4-cli is installed and on your PATH.' -ForegroundColor DarkGray
        return 0
    }

    if (-not $installedVersion) {
        Write-Host "WARNING: Could not determine installed browser4-cli version from output:" -ForegroundColor Yellow
        Write-Host "  $versionOutput" -ForegroundColor DarkGray
        return 0
    }

    if ($installedVersion -eq $expectedVersion) {
        if (-not $Silent) {
            Write-Host "browser4-cli v$installedVersion is up to date." -ForegroundColor Green
        }
        return 0
    }

    # Version mismatch — emit a clear warning with upgrade instructions.
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor Red
    Write-Host "  browser4-cli is OUTDATED." -ForegroundColor Red
    Write-Host "  Installed: v$installedVersion" -ForegroundColor Red
    Write-Host "  Expected:  v$expectedVersion" -ForegroundColor Red
    Write-Host ('=' * 72) -ForegroundColor Red
    Write-Host ''
    Write-Host '  To upgrade, run one of the following from the repo root:' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '    cargo install --path cli\browser4-cli --force' -ForegroundColor White
    Write-Host '    npm install -g' -ForegroundColor White
    Write-Host ''
    Write-Host '  Then verify with: browser4-cli --version' -ForegroundColor Yellow
    Write-Host ''

    return 1
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
        Write-Host "  This may take several minutes -- the agent runs browser4-cli commands" -ForegroundColor DarkGray
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

    $targetDir = Join-Path $script:RepoRoot 'target'
    if (-not (Test-Path -LiteralPath $targetDir)) {
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    }
    $tempFile = Join-Path $targetDir ([System.IO.Path]::GetRandomFileName())

    # Start-NativeCommand applies all three anti-garbling fixes:
    #   [Console]::OutputEncoding = UTF-8
    #   ErrorRecord → string coercion
    #   UTF-8 without BOM file I/O
    $exitCode = Start-NativeCommand -FilePath 'claude' `
        -ArgumentList $claudeArgs `
        -CaptureFile $tempFile

    if (-not $Silent) {
        Write-Host ""
        Write-Host "Agent finished (exit code: $exitCode)." `
            -ForegroundColor $(if ($exitCode -eq 0) { 'Green' } else { 'Red' })
    }

    # Read back the captured output (Start-NativeCommand uses UTF-8 no-BOM)
    $capturedOutput = ''
    if (Test-Path -LiteralPath $tempFile) {
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        $capturedOutput = [System.IO.File]::ReadAllText(
            [System.IO.Path]::GetFullPath($tempFile), $utf8NoBom
        )
        Remove-Item -LiteralPath $tempFile -ErrorAction SilentlyContinue
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
