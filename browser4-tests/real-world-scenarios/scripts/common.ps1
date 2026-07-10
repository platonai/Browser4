#!/usr/bin/env pwsh
<#
.SYNOPSIS
Shared helpers for browser4-cli agent-scenario test scripts.

.DESCRIPTION
Dot-source this module to reuse the shared usability-evaluation prompt and the
standard agent invocation.  The prompt adapts to the environment automatically:

  - Dev (default):  `cargo run -- help`  + local `skills/browser4-cli/SKILL.md`.
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

# ── Windows command-line argument escaping ────────────────────────────────────
# Adapted from coworker/scripts/workers/agent.ps1.
# Windows CreateProcess receives a single lpCommandLine string; the child parses
# it via CommandLineToArgvW.  Backslashes preceding a double-quote must be
# doubled, and trailing backslashes must be doubled, to prevent the quote from
# being treated as an escape.  The simplistic .Replace('"', '\"') is incorrect
# and silently corrupts arguments containing Windows paths.
function ConvertTo-WindowsCommandLineArgument {
    param(
        [AllowEmptyString()]
        [string]$Argument
    )

    if ($null -eq $Argument -or $Argument.Length -eq 0) {
        return '""'
    }

    if ($Argument -notmatch '[\s"]') {
        return $Argument
    }

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append('"')

    $backslashCount = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq '\') {
            $backslashCount++
            continue
        }

        if ($character -eq '"') {
            if ($backslashCount -gt 0) {
                [void]$builder.Append('\' * ($backslashCount * 2))
                $backslashCount = 0
            }
            [void]$builder.Append('\"')
            continue
        }

        if ($backslashCount -gt 0) {
            [void]$builder.Append('\' * $backslashCount)
            $backslashCount = 0
        }

        [void]$builder.Append($character)
    }

    if ($backslashCount -gt 0) {
        [void]$builder.Append('\' * ($backslashCount * 2))
    }

    [void]$builder.Append('"')
    return $builder.ToString()
}

# ── Compiled output handler (C#) ──────────────────────────────────────────────
# DataReceived events fire on .NET threadpool threads.  PowerShell scriptblocks
# cast to delegates require a Runspace on the executing thread, which threadpool
# threads do not have — causing "There is no Runspace available to run scripts
# in this thread."  A compiled C# class avoids PowerShell entirely for event
# handling and works reliably on any thread.
if (-not $script:_NativeCommandHandlerCompiled) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;

public class NativeCommandOutputHandler
{
    private readonly string _capturePath;
    private readonly UTF8Encoding _utf8;

    public NativeCommandOutputHandler(string capturePath)
    {
        _capturePath = capturePath;
        _utf8 = new UTF8Encoding(false);
    }

    public void OnOutputReceived(object sender, System.Diagnostics.DataReceivedEventArgs e)
    {
        if (e.Data != null)
        {
            Console.WriteLine(e.Data);
            File.AppendAllText(_capturePath, e.Data + Environment.NewLine, _utf8);
        }
    }
}
'@
    $script:_NativeCommandHandlerCompiled = $true
}

# ── Path resolution ──────────────────────────────────────────────────────────
# Repo root is 3 levels up from scripts/ (scripts -> tests -> browser4-tests -> repo root)
$script:RepoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$script:IssuesReadyDir = [System.IO.Path]::GetFullPath(
    (Join-Path $script:RepoRoot 'coworker\tasks\issues\draft')
)

# Local alias for string interpolation in the here-string below.  Forward
# slashes are used so paths work in bash/Git Bash shells that agents run in.
$RepoRootPath = $script:RepoRoot -replace '\\', '/'

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
    $helpCmd        = 'browser4-cli help'
    $skillPath      = 'https://browser4.io/SKILL.md'
    $cliInvocation  = 'browser4-cli'
} else {
    # Dev mode: use cargo run so the agent tests the locally-built CLI and
    # the daemon auto-starts the locally-built backend JAR.  The repo root
    # is the CWD when the agent runs.
    $helpCmd        = "cd `"$RepoRootPath`" && cd cli/browser4-cli && cargo run -- help"
    $skillPath      = 'skills/browser4-cli/SKILL.md'
    $cliInvocation  = "cd `"$RepoRootPath`" && cd cli/browser4-cli && cargo run --"
}

# ── Shared evaluation prompt ────────────────────────────────────────────────
# Every scenario prepends this to its task-specific prompt so the agent
# consistently evaluates browser4-cli usability while completing the task.
$generalPrompt = @"
You are evaluating the usability, discoverability, and reliability of browser4-cli while completing a real-world task.

## Preparation

Before performing any browser interaction:

0. Verify your working directory is the repository root: `$RepoRootPath`. If `pwd` is anything other than this directory, navigate there immediately with `cd "$RepoRootPath"`. The `$cliInvocation` pattern already starts every command from the repo root — use it consistently for all browser4-cli commands and you never need to think about your current working directory.
1. Run `$helpCmd`.
2. Read `$skillPath` completely.
3. Learn the available commands, workflows, and conventions directly from the documentation.
4. Do not assume any prior knowledge of browser4-cli.

## Backend Server

$(if ($browser4cliMode -eq 'production') {
"Production mode: browser4-cli connects to a separately-managed backend server. Ensure the **latest runtime bundle release** is deployed and running before starting the task. The CLI does not auto-start a server in production mode — if no server is reachable, commands will fail with a connection error."
} else {
"Dev mode: the CLI daemon **auto-starts the locally-built backend JAR** from the repository. No manual server setup is needed — the first \`cargo run\` command will start the daemon and backend automatically. The backend runs from the local source tree, matching the code currently checked out. Do NOT download or install a separate runtime bundle — that would test a stale release instead of the local changes.`n`nIf the daemon or backend fails to start automatically: (a) check for port conflicts on the default port, (b) verify a Java runtime is available, (c) retry the command once. If it still fails after one retry, record the error as a **Reliability** issue with the full error output and continue with any commands that do not require a running browser."
})

## Command Invocation

Every browser4-cli command in this session MUST be invoked as:

`$cliInvocation <command>`

For example:
  `$cliInvocation goto "https://example.com"`
  `$cliInvocation snapshot -i`
  `$cliInvocation click e5`

Do NOT use a plain `browser4-cli` command unless the invocation above fails after a genuine attempt.  Using the wrong invocation will test a stale installed binary instead of the local source code, invalidating the evaluation.

## Tool Usage Rules

* Use the invocation method above for ALL browser interactions.
* Do NOT use Playwright, Puppeteer, Selenium, CDP libraries, external browser APIs, or any other browser automation tool.
* If a browser action is required, first identify the documented browser4-cli command that should perform it.
* Prefer documented workflows over assumptions.
* If documentation is ambiguous, incomplete, inaccurate, outdated, or difficult to discover, record it as an issue.

## Evaluation Objective

Your goal is not only to complete the task, but also to evaluate the usability of browser4-cli from the perspective of a first-time user. Actively look for issues in these categories:

* **Installation & Setup** — prerequisites, environment assumptions, setup complexity, platform-specific issues
* **Discoverability** — help output quality, command discoverability, missing examples, missing documentation
* **Documentation** — incomplete, incorrect, or ambiguous instructions; undocumented behavior; inconsistent terminology
* **CLI Experience** — naming consistency, workflow clarity, session/browser lifecycle, state management
* **Task Execution** — navigation, search, content extraction, form interaction, waiting/synchronization, error recovery
* **Reliability** — unexpected failures, flaky behavior, misleading outputs, poor error messages, silent failures
* **User Experience** — learnability, efficiency, cognitive load, friction points, missing shortcuts or quality-of-life features

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

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:** (free-text for refinement details, counter-arguments, or follow-up actions)

Leave the checkboxes empty — they are for the human reviewer to fill in.

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
    $cMarkers = @('### C. Issues Found', '## C. Issues Found', '# C. Issues Found', '### C Issues Found', '## C Issues Found', '# C Issues Found')
    foreach ($m in $cMarkers) {
        $cIdx = $normalized.IndexOf($m, [StringComparison]::OrdinalIgnoreCase)
        if ($cIdx -ge 0) { break }
    }
    if ($cIdx -lt 0) { return @() }

    $cStart = $normalized.IndexOf("`n", $cIdx) + 1
    if ($cStart -le 0 -or $cStart -ge $normalized.Length) { return @() }

    # Find D section as the end boundary
    $dIdx = -1
    $dMarkers = @('### D. Overall Assessment', '## D. Overall Assessment', '# D. Overall Assessment', '### D Overall Assessment', '## D Overall Assessment', '# D Overall Assessment')
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
        'Human Review'                 = 'Review'
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
        '### A. Task Result', '## A. Task Result', '# A. Task Result', '## ✅ Task Result',
        '### A Task Result', '## A Task Result', '# A Task Result', '## Task Result', '# Task Result'
    )
    foreach ($m in $aMarkers) {
        $aStart = $normalized.IndexOf($m, [StringComparison]::OrdinalIgnoreCase)
        if ($aStart -ge 0) { break }
    }

    if ($aStart -ge 0) {
        $aContentStart = $normalized.IndexOf("`n", $aStart) + 1
        if ($aContentStart -le 0) { $aContentStart = $aStart }

        $bMarkers = @(
            '### B. Execution Trace', '## B. Execution Trace', '# B. Execution Trace',
            '### B Execution Trace', '## B Execution Trace', '# B Execution Trace',
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
        '### B. Execution Trace', '## B. Execution Trace', '# B. Execution Trace',
        '### B Execution Trace', '## B Execution Trace', '# B Execution Trace',
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
            '### C. Issues Found', '## C. Issues Found', '# C. Issues Found',
            '### C Issues Found', '## C Issues Found', '# C Issues Found',
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
        if ($fullTrace -match '(?s)(?:###\s+)?Commands?\s*Used[^\n]*\n(.+?)(?=\n###\s|\n##\s|\Z)') {
            $result.Commands = $Matches[1].Trim()
        }
        # Extract "Workarounds Required" subsection (if present)
        if ($fullTrace -match '(?s)(?:###\s+)?Workarounds?\s*Required[:\s]*\n(.+?)(?=\n###\s|\n##\s|\Z)') {
            $result.Workarounds = $Matches[1].Trim()
        }
    }

    return $result
}

# ── JSON conversion (shared schema with coworker/gui/frontend/issue-model.js) ─

function ConvertTo-IssueJson {
    <#
    .SYNOPSIS
        Convert parsed issues to the canonical JSON schema shared with the GUI.
    .DESCRIPTION
        Takes the output of ConvertFrom-IssuesSection plus background context
        and produces a JSON string matching the schema in coworker/gui/frontend/issue-model.js.
        This is the interchange format between PowerShell scripts and the web GUI.
    .PARAMETER ScenarioName
        Short scenario identifier (e.g. "form-filling").
    .PARAMETER SourceFile
        Source .full.md filename.
    .PARAMETER Timestamp
        ISO-style timestamp string (yyyyMMdd-HHmmss).
    .PARAMETER Mode
        "dev" or "production".
    .PARAMETER Background
        Hashtable from Extract-BackgroundContext.
    .PARAMETER Issues
        Array of issue hashtables from ConvertFrom-IssuesSection.
    .OUTPUTS
        JSON string.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScenarioName,
        [string]$SourceFile = '',
        [string]$Timestamp = '',
        [string]$Mode = 'dev',
        [hashtable]$Background = @{},
        [object[]]$Issues = @()
    )

    $sectionLabels = @(
        'Reproduction',
        'Expected Behavior',
        'Actual Behavior',
        'Root Cause Analysis',
        'Code Pointer',
        'AI Suggested Improvement'
    )

    $issueArray = @()
    $issueNum = 0
    foreach ($issue in $Issues) {
        $issueNum++
        $sections = @()
        foreach ($label in $sectionLabels) {
            $body = ''
            switch ($label) {
                'Reproduction'           { $body = $issue.Reproduction }
                'Expected Behavior'      { $body = $issue.Expected }
                'Actual Behavior'        { $body = $issue.Actual }
                'Root Cause Analysis'    { $body = $issue.RootCause }
                'Code Pointer'           { $body = $issue.CodePointer }
                'AI Suggested Improvement' { $body = $issue.Suggestion }
            }
            if ($body) {
                $sections += @{ label = $label; body = $body }
            }
        }

        $reviewDecision = $null
        $reviewNotes = ''
        if ($issue.Review) {
            if ($issue.Review -match '\[x\]\s*\*\*(ACCEPT|ACCEPT with improvements|DEFER|WONTFIX|REJECT)\*\*') {
                $reviewDecision = $Matches[1]
            }
            if ($issue.Review -match '\*\*Notes:\*\*\s*\n(.+)') {
                $reviewNotes = $Matches[1].Trim()
            }
        }

        $issueObj = [ordered]@{
            number   = $issueNum
            title    = $issue.Title
            severity = $issue.Severity
            category = $issue.Category
            sections = $sections
            review   = @{ decision = $reviewDecision; notes = $reviewNotes }
        }
        $issueArray += $issueObj
    }

    $result = [ordered]@{
        meta       = @{
            scenario = $ScenarioName
            source   = $SourceFile
            date     = $Timestamp
            mode     = $Mode
        }
        background = @{
            task             = $Background.TaskSummary
            executionContext = $Background.ExecutionTrace
        }
        issues     = $issueArray
    }

    $jsonSettings = [System.Web.Script.Serialization.JavaScriptSerializer]::new()
    return $jsonSettings.Serialize($result)
}

function ConvertFrom-IssueJson {
    <#
    .SYNOPSIS
        Parse the canonical JSON schema back into PowerShell hashtables.
    .DESCRIPTION
        Inverse of ConvertTo-IssueJson.  Accepts a JSON string matching the
        shared schema and returns the components as PowerShell objects.
    .PARAMETER Json
        JSON string in the canonical issue schema format.
    .OUTPUTS
        Hashtable with keys: ScenarioName, SourceFile, Timestamp, Mode,
        Background (hashtable), Issues (array of hashtables).
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Json
    )

    $jsonSettings = [System.Web.Script.Serialization.JavaScriptSerializer]::new()
    $data = $jsonSettings.DeserializeObject($Json)

    $issues = @()
    foreach ($iss in $data.issues) {
        $suggestionBody = ''
        foreach ($sec in $iss.sections) {
            if ($sec.label -eq 'AI Suggested Improvement') {
                $suggestionBody = $sec.body
                break
            }
        }
        $reviewText = ''
        $decision = ''
        if ($iss.review) {
            $decision = if ($iss.review.decision) { $iss.review.decision } else { '' }
            $notes = if ($iss.review.notes) { $iss.review.notes } else { '' }
            if ($decision) {
                $reviewText = "- [x] **${decision}**`n- **Notes:**"
                if ($notes) { $reviewText += "`n${notes}" }
            } else {
                $reviewText = "- [ ] **ACCEPT**`n- [ ] **ACCEPT with improvements**`n- [ ] **DEFER**`n- [ ] **WONTFIX**`n- [ ] **REJECT**`n- **Notes:**"
                if ($notes) { $reviewText += "`n${notes}" }
            }
        }

        $issue = @{
            Title        = $iss.title
            Severity     = $iss.severity
            Category     = $iss.category
            Reproduction = ''
            Expected     = ''
            Actual       = ''
            RootCause    = ''
            CodePointer  = ''
            Review       = $reviewText
            Suggestion   = $suggestionBody
        }
        foreach ($sec in $iss.sections) {
            switch ($sec.label) {
                'Reproduction'              { $issue.Reproduction = $sec.body }
                'Expected Behavior'         { $issue.Expected = $sec.body }
                'Actual Behavior'           { $issue.Actual = $sec.body }
                'Root Cause Analysis'       { $issue.RootCause = $sec.body }
                'Code Pointer'              { $issue.CodePointer = $sec.body }
                'AI Suggested Improvement'  { $issue.Suggestion = $sec.body }
            }
        }
        $issues += $issue
    }

    return @{
        ScenarioName = $data.meta.scenario
        SourceFile   = $data.meta.source
        Timestamp    = $data.meta.date
        Mode         = $data.meta.mode
        Background   = @{
            TaskSummary    = $data.background.task
            ExecutionTrace = $data.background.executionContext
        }
        Issues       = $issues
    }
}

# ── Issue file output ─────────────────────────────────────────────────────────

function Write-IssuesToReadyQueue {
    <#
    .SYNOPSIS
        Write the agent evaluation output to the issues draft ready queue.
    .DESCRIPTION
        Saves the complete agent output (containing A. Task Result, B. Execution Trace,
        C. Issues Found, D. Overall Assessment) as a markdown file in the
        issues/draft directory for downstream refinement.

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

# ═══════════════════════════════════════════════════════════════════════════════
# Safe native-command invocation
# ═══════════════════════════════════════════════════════════════════════════════
#
# Start-NativeCommand wraps System.Diagnostics.Process with .NET async output
# handlers (OutputDataReceived / ErrorDataReceived) so the PowerShell thread
# stays free to print periodic heartbeat messages during silent stretches.
#
# This replaces the old pipeline-based approach (& cmd 2>&1 | ForEach-Object),
# which suffered from three problems:
#
# 1. ENCODING MISMATCH — [Console]::OutputEncoding had to be swapped to UTF-8
#    before every invocation.  Solved by setting StandardOutputEncoding on
#    ProcessStartInfo directly.
#
# 2. ERROR-OBJECT LEAKAGE — "2>&1" merged stderr as ErrorRecord *objects*,
#    whose .ToString() emitted FQ type names instead of messages.
#    Solved by reading stdout/stderr as separate streams.
#
# 3. BOM INCONSISTENCY — mixing BOM and non-BOM sources wrote garbage bytes.
#    Solved by using UTF8Encoding($false) for all file I/O.
#
# Additional benefit: heartbeats.  The pipeline blocked the PS thread, making
# it impossible to print "still running" messages while the child process was
# alive but not emitting output.  The polling loop in WaitForExit(timeout)
# gives us a natural heartbeat cadence.

function Start-NativeCommand {
    <#
    .SYNOPSIS
        Invoke a native command safely, capturing combined stdout+stderr to a file.

    .DESCRIPTION
        Uses System.Diagnostics.Process with .NET async output handlers so the
        PowerShell thread is free to print periodic heartbeat messages during
        silent stretches.  Output streams to the console in real time while
        simultaneously written to the capture file (UTF-8 without BOM).

        Replaces the old pipeline-based approach (& cmd 2>&1 | ForEach-Object)
        which blocked the PowerShell thread and offered no way to report progress
        while the child process was running but not yet emitting output.

    .PARAMETER FilePath
        Path to the native executable.

    .PARAMETER ArgumentList
        Array of arguments to pass to the command.

    .PARAMETER CaptureFile
        File path to capture combined stdout+stderr (UTF-8 without BOM).  When
        omitted, a temp file is used and cleaned up on return.

    .PARAMETER PassThru
        When set, also returns the captured output as a single string.

    .PARAMETER HeartbeatIntervalSec
        Seconds between "still running" messages when no output is produced
        (default 10).  Set to 0 to disable heartbeats.

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

        [switch] $PassThru,

        [int] $HeartbeatIntervalSec = 10,

        # Maximum seconds to wait before killing the process.
        # 0 (default) means no timeout.  Exit code 124 is returned on timeout
        # (matching the Unix `timeout` command convention).
        [int] $TimeoutSeconds = 0
    )

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)

    # ── Build a display-friendly argument list (hide -p <prompt>) ──────────
    $displayArgs = @()
    $skipNext = $false
    foreach ($a in $ArgumentList) {
        if ($skipNext) { $skipNext = $false; continue }
        if ($a -eq '-p') { $skipNext = $true; $displayArgs += '<prompt>'; continue }
        $displayArgs += $a
    }

    $startTime = Get-Date
    $startFmt = $startTime.ToString('HH:mm:ss')
    Write-Host ''
    Write-Host "-> Started at ${startFmt}: $FilePath $($displayArgs -join ' ')" -ForegroundColor Yellow
    Write-Host ''

    # ── Resolve a capture file path ────────────────────────────────────────
    # Even when the caller doesn't need a capture file we still write to a temp
    # file so the async output handlers (which run on .NET threadpool threads)
    # always have a valid static file path to append to.
    $capturePath = if ($CaptureFile) {
        $CaptureFile
    } else {
        [System.IO.Path]::GetTempFileName()
    }
    # Initialize the capture file (create or truncate)
    [System.IO.File]::WriteAllText($capturePath, '', $utf8NoBom)

    # ── .NET event handlers for stdout / stderr ────────────────────────────
    # Use a compiled C# class (NativeCommandOutputHandler) instead of
    # PowerShell scriptblocks cast to delegates.  DataReceived events fire
    # on .NET threadpool threads where no PowerShell Runspace is guaranteed;
    # pure-C# handlers avoid "There is no Runspace available" crashes.
    $nativeHandler = New-Object NativeCommandOutputHandler $capturePath
    $streamHandler = [Delegate]::CreateDelegate(
        [System.Diagnostics.DataReceivedEventHandler],
        $nativeHandler,
        'OnOutputReceived'
    )

    # ── Resolve executable path (handle .cmd wrappers on Windows) ───
    # System.Diagnostics.Process uses CreateProcess, which only directly
    # launches .exe / .com files.  On Windows, npm-installed tools (claude,
    # node, etc.) are often .cmd wrappers.  An extensionless executable like
    # "claude" would match the npm-installed POSIX shell script (#!/bin/sh)
    # rather than claude.cmd, producing:
    #   "%1 is not a valid Win32 application"
    # Resolve to the .cmd wrapper explicitly on Windows — CreateProcess
    # delegates .cmd/.bat to cmd.exe internally without the extra quoting
    # layer that "cmd.exe /c <script>" introduces.
    $isWindowsPlatform = $false
    if ($null -ne $PSVersionTable -and $PSVersionTable.PSEdition -eq 'Desktop') {
        $isWindowsPlatform = $true
    } elseif ($null -ne (Get-Variable -Name IsWindows -ErrorAction SilentlyContinue)) {
        $isWindowsPlatform = [bool]$IsWindows
    }

    $resolvedExe = $FilePath
    $stdinRedirectPath = $null

    if ($isWindowsPlatform) {
        if (-not [System.IO.Path]::HasExtension($resolvedExe) -and
            -not $resolvedExe.Contains([System.IO.Path]::DirectorySeparatorChar)) {
            $cmdCandidate = "$resolvedExe.cmd"
            $resolvedCmd = Get-Command $cmdCandidate -ErrorAction SilentlyContinue
            if ($resolvedCmd) {
                $resolvedExe = $resolvedCmd.Source
            }
        }
    }

    # ── Build the process ──────────────────────────────────────────────────
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $resolvedExe

    # ── Build argument list ─────────────────────────────────────────────────
    # Use ProcessStartInfo.ArgumentList (available in .NET 6+ / pwsh 7+) to
    # pass each argument as a separate string.  This avoids the need for manual
    # escaping entirely:
    #   - On Unix: entries are passed directly as argv[] via execve().
    #   - On Windows: .NET joins entries into lpCommandLine using correct
    #     CommandLineToArgvW escaping — no manual quoting needed.
    $stdinRedirectPath = $null

    # On Windows, redirect large prompts via stdin to avoid CreateProcess
    # command-line length limits (~32 KiB, or ~8191 with legacy cmd.exe).
    if ($isWindowsPlatform) {
        $promptValue = $null
        $foundP = $false
        foreach ($a in $ArgumentList) {
            if ($foundP) { $promptValue = $a; break }
            if ($a -eq '-p') { $foundP = $true }
        }
        $promptLen = if ($promptValue) { $promptValue.Length } else { 0 }

        # Estimate total command-line length to decide whether to redirect.
        # We sum the lengths of all args plus separators as a rough estimate.
        $estimatedLen = $resolvedExe.Length + 1
        foreach ($a in $ArgumentList) { $estimatedLen += $a.Length + 1 }

        if ($promptLen -gt 0 -and $estimatedLen -gt 6000) {
            # Strip -p <prompt> and pipe the prompt via stdin instead.
            $skipNext = $false
            foreach ($a in $ArgumentList) {
                if ($skipNext) { $skipNext = $false; continue }
                if ($a -eq '-p') { $skipNext = $true; continue }
                $psi.ArgumentList.Add($a)
            }

            $stdinRedirectPath = [System.IO.Path]::GetTempFileName()
            [System.IO.File]::WriteAllText($stdinRedirectPath, $promptValue, $utf8NoBom)
            $psi.RedirectStandardInput = $true
            Write-Host "  · Prompt length ${promptLen} chars → redirected via stdin to avoid command-line limit" -ForegroundColor DarkGray
        } else {
            foreach ($a in $ArgumentList) { $psi.ArgumentList.Add($a) }
        }
    } else {
        # Unix: no command-line length concerns — pass all arguments directly.
        foreach ($a in $ArgumentList) { $psi.ArgumentList.Add($a) }
    }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = $utf8NoBom
    $psi.StandardErrorEncoding = $utf8NoBom
    $psi.CreateNoWindow = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi

    # Wire up event handlers (single C# delegate for both streams)
    $null = $proc.add_OutputDataReceived($streamHandler)
    $null = $proc.add_ErrorDataReceived($streamHandler)

    $exitCode = -1
    $stopwatch = [System.Diagnostics.Stopwatch]::new()

    try {
        try {
            $proc.Start() | Out-Null
        } catch {
            Write-Host "ERROR: Failed to start '$FilePath': $_" -ForegroundColor Red
            Write-Host "  Is '$FilePath' installed and on your PATH?" -ForegroundColor DarkGray
            $script:LastNativeExitCode = 127
            return 127
        }

        # Begin async reading
        $proc.BeginOutputReadLine()
        $proc.BeginErrorReadLine()

        # ── Stdin redirect: feed the prompt after async readers are wired ──
        if ($stdinRedirectPath -and (Test-Path -LiteralPath $stdinRedirectPath)) {
            try {
                $stdinContent = [System.IO.File]::ReadAllText(
                    [System.IO.Path]::GetFullPath($stdinRedirectPath), $utf8NoBom
                )
                $proc.StandardInput.Write($stdinContent)
            } finally {
                $proc.StandardInput.Close()
                Remove-Item -LiteralPath $stdinRedirectPath -ErrorAction SilentlyContinue
            }
        }

        # ── Heartbeat loop ─────────────────────────────────────────────────
        # Poll the process with WaitForExit(timeout).  While waiting, the
        # OutputDataReceived / ErrorDataReceived events fire on background
        # .NET threads and call [Console]::WriteLine directly — that output
        # interleaves naturally with the heartbeats below.
        #
        # Heartbeat interval uses progressive backoff so the user isn't
        # spammed with repetitive "still running" messages:
        #   0 –  5 min  → every 30 s
        #   5 – 15 min  → every 60 s
        #  15+ min      → every 120 s
        $stopwatch.Start()
        $lastHeartbeatSec = 0
        $heartbeatCount = 0

        while (-not $proc.HasExited) {
            $proc.WaitForExit(2000) | Out-Null
            $elapsed = $stopwatch.Elapsed.TotalSeconds

            # ── Timeout check ─────────────────────────────────────────────
            if ($TimeoutSeconds -gt 0 -and $elapsed -ge $TimeoutSeconds -and -not $proc.HasExited) {
                break
            }

            # Progressive backoff
            $interval = if ($elapsed -lt 300) {
                30
            } elseif ($elapsed -lt 900) {
                60
            } else {
                120
            }

            if ($HeartbeatIntervalSec -gt 0 -and
                ($elapsed - $lastHeartbeatSec) -ge $interval -and
                -not $proc.HasExited) {
                $heartbeatCount++
                $mins = [Math]::Floor($elapsed / 60)
                $secs = [Math]::Floor($elapsed % 60)
                $cmdLabel = if ($displayArgs.Count -gt 0) {
                    "$FilePath $($displayArgs[0])"
                } else {
                    $FilePath
                }
                [Console]::WriteLine(
                    "  · ${cmdLabel} — running (${mins}m ${secs}s elapsed, heartbeat #${heartbeatCount})"
                )
                $lastHeartbeatSec = $elapsed
            }
        }

        # ── Drain final output ─────────────────────────────────────────────
        # The process has exited but async reads may still have data in flight.
        # Cancel the async readers, then drain synchronously to capture
        # everything that remains.
        try { $proc.CancelOutputRead() } catch { }
        try { $proc.CancelErrorRead() } catch { }

        # Small grace period for in-flight async events to land
        Start-Sleep -Milliseconds 300

        # Synchronous drain of any remaining output
        try {
            $rem = $proc.StandardOutput.ReadToEnd()
            if ($rem) {
                [Console]::Write($rem)
                [System.IO.File]::AppendAllText($capturePath, $rem, $utf8NoBom)
            }
        } catch { }
        try {
            $rem = $proc.StandardError.ReadToEnd()
            if ($rem) {
                [Console]::Write($rem)
                [System.IO.File]::AppendAllText($capturePath, $rem, $utf8NoBom)
            }
        } catch { }

        # ── Timeout: kill the process if it exceeded the limit ──────────
        if ($TimeoutSeconds -gt 0 -and $elapsed -ge $TimeoutSeconds -and -not $proc.HasExited) {
            try { $proc.Kill() } catch { }
            # Brief wait for the kill to take effect
            Start-Sleep -Milliseconds 500
            $exitCode = 124
            $timeoutMsg = "TIMEOUT: killed after ${TimeoutSeconds}s (elapsed: $([Math]::Floor($elapsed))s)"
            [Console]::WriteLine("")
            [Console]::WriteLine("  · $timeoutMsg")
            [System.IO.File]::AppendAllText($capturePath, "`n`n$timeoutMsg`n", $utf8NoBom)
        } else {
            $exitCode = $proc.ExitCode
        }

    } finally {
        # Clean up event handlers before Dispose
        try { $proc.remove_OutputDataReceived($streamHandler) } catch { }
        try { $proc.remove_ErrorDataReceived($streamHandler) } catch { }
        if ($proc -and -not $proc.HasExited) {
            try { $proc.Kill() } catch { }
        }
        $proc.Dispose()
        $stopwatch.Stop()
    }

    # ── Finish banner ──────────────────────────────────────────────────────
    $duration = $stopwatch.Elapsed
    $color = if ($exitCode -eq 0) { 'Green' } elseif ($exitCode -eq 124) { 'Yellow' } else { 'Red' }
    $durStr = "$([Math]::Floor($duration.TotalMinutes))m $($duration.Seconds)s"
    $statusSuffix = if ($exitCode -eq 124) { ', TIMEOUT' } else { '' }
    Write-Host ''
    Write-Host "<- Finished at $(Get-Date -Format 'HH:mm:ss') (duration: ${durStr}, exit code: $exitCode${statusSuffix})" -ForegroundColor $color

    $script:LastNativeExitCode = $exitCode

    # ── PassThru / temp-file cleanup ───────────────────────────────────────
    if ($PassThru) {
        $content = ''
        if (Test-Path -LiteralPath $capturePath) {
            $content = [System.IO.File]::ReadAllText(
                [System.IO.Path]::GetFullPath($capturePath), $utf8NoBom
            )
        }
        if (-not $CaptureFile) {
            Remove-Item -LiteralPath $capturePath -ErrorAction SilentlyContinue
        }
        return $content
    }

    if (-not $CaptureFile -and (Test-Path -LiteralPath $capturePath)) {
        Remove-Item -LiteralPath $capturePath -ErrorAction SilentlyContinue
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
        output and writes evaluation results to the issues draft ready queue.
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
    .PARAMETER TimeoutSeconds
        Maximum seconds to wait for the agent to complete.  0 (default) means
        no timeout.  On timeout the process is killed and exit code 124 is
        returned (matching the Unix `timeout` command convention).
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,

        [string]$ScenarioName = '',

        [string]$OutputFile = '',

        [switch]$Silent,

        [int]$TimeoutSeconds = 0
    )

    # ── Status header ────────────────────────────────────────────────────────
    if (-not $Silent) {
        $promptLen = $Prompt.Length
        $promptLines = ($Prompt -split "`n").Count
        Write-Host "Invoking Claude Code agent (prompt: $promptLen chars, $promptLines lines)" -ForegroundColor Cyan
        if ($ScenarioName) {
            Write-Host "  Scenario: $ScenarioName" -ForegroundColor DarkGray
        }
    }

    # ── Build claude arguments ──────────────────────────────────────────────
    $claudeArgs = @('--dangerously-skip-permissions', '-p', $Prompt)
    if ($Silent) { $claudeArgs += '--silent' }

    # ── Resolve capture file path ──────────────────────────────────────────
    # Write directly to the final output path (not a temp file) so partial
    # output survives crashes.  The known path in target/ also makes it easy
    # for the user to tail the file during long-running scenarios.
    $captureFile = ''
    if ($ScenarioName -or $OutputFile) {
        $targetDir = Join-Path $script:RepoRoot 'target'
        if (-not (Test-Path -LiteralPath $targetDir)) {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }
        if ($OutputFile) {
            $captureFile = $OutputFile
        } else {
            # Auto-generate a predictable path when only ScenarioName is provided
            $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
            $safeName = $ScenarioName -replace '[\\/:*?"<>|]', '_'
            $captureFile = Join-Path $targetDir "$timestamp-$safeName.raw.md"
        }
        # Ensure parent directory exists
        $captureDir = Split-Path -Parent $captureFile
        if ($captureDir -and -not (Test-Path -LiteralPath $captureDir)) {
            New-Item -ItemType Directory -Path $captureDir -Force | Out-Null
        }
        Write-Host "  Output: $captureFile" -ForegroundColor DarkGray
    }

    # ── Invoke claude via Start-NativeCommand ───────────────────────────────
    $startParams = @{
        FilePath     = 'claude'
        ArgumentList = $claudeArgs
    }
    if ($captureFile) {
        $startParams['CaptureFile'] = $captureFile
    }
    if ($TimeoutSeconds -gt 0) {
        $startParams['TimeoutSeconds'] = $TimeoutSeconds
    }
    $exitCode = Start-NativeCommand @startParams

    # ── Post-processing (only when capture was requested) ───────────────────
    if (-not $captureFile) {
        if ($exitCode -ne 0) {
            $host.UI.WriteErrorLine("Agent exited with non-zero code: $exitCode")
        }
        return
    }

    # Read back the captured output (written incrementally by Start-NativeCommand)
    $capturedOutput = ''
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    if (Test-Path -LiteralPath $captureFile) {
        $capturedOutput = [System.IO.File]::ReadAllText(
            [System.IO.Path]::GetFullPath($captureFile), $utf8NoBom
        )
    }

    if ([string]::IsNullOrWhiteSpace($capturedOutput)) {
        Write-Host "  WARNING: No output captured from agent (file: $captureFile)" -ForegroundColor Yellow
        return
    }

    # Write to the issues ready queue
    if ($ScenarioName) {
        Write-IssuesToReadyQueue -ScenarioName $ScenarioName -Content $capturedOutput
    }
}
