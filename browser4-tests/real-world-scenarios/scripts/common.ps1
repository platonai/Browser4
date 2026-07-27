#!/usr/bin/env pwsh
<#
.SYNOPSIS
Shared helpers for browser4-cli agent-scenario test scripts.

.DESCRIPTION
Dot-source this module to reuse the shared usability-evaluation prompt and the
standard agent invocation.  The prompt adapts to the environment automatically:

  - Dev (default):  `./b4w.ps1 help`  + local `skills/browser4-cli/SKILL.md`.
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

# ── Task file path resolution ─────────────────────────────────────────────────
# Resolves a task file path using a three-tier strategy:
#   1. As-given (handles absolute paths and already-correct relative paths)
#   2. Relative to the caller's CWD
#   3. Relative to the scenarios directory (parent of $ScriptsDir)
# Returns the resolved absolute path, or $null if none of the locations match.

function Resolve-TaskFilePath {
    <#
    .SYNOPSIS
        Resolve a task file path using a three-tier lookup strategy.
    .DESCRIPTION
        Checks the path as-given first (handles absolute paths and paths that
        already exist relative to the current shell). Falls back to the caller's
        CWD, then to the scenarios directory (parent of ScriptsDir). Returns
        the resolved absolute path, or $null if the file is not found.
    .PARAMETER TaskFile
        Path to the task file (relative or absolute).
    .PARAMETER ScriptsDir
        Path to the scripts directory (typically $PSScriptRoot from the caller).
    .OUTPUTS
        String (resolved absolute path) or $null.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$TaskFile,
        [Parameter(Mandatory = $true)]
        [string]$ScriptsDir
    )

    # 1) Try as-given (handles absolute paths, or already-correct relative paths)
    if (Test-Path -LiteralPath $TaskFile -PathType Leaf) {
        return (Resolve-Path -LiteralPath $TaskFile).Path
    }

    # 2) Try relative to caller's CWD
    $cwdPath = Join-Path (Get-Location).Path $TaskFile
    if (Test-Path -LiteralPath $cwdPath -PathType Leaf) {
        return (Resolve-Path -LiteralPath $cwdPath).Path
    }

    # 3) Try relative to scenarios directory (parent of ScriptsDir)
    $scenariosDir = Join-Path $ScriptsDir '..'
    $scenariosPath = Join-Path $scenariosDir $TaskFile
    if (Test-Path -LiteralPath $scenariosPath -PathType Leaf) {
        return (Resolve-Path -LiteralPath $scenariosPath).Path
    }

    return $null
}

# ── Task name matching ───────────────────────────────────────────────────────
# Resolves a list of user-requested task names against a list of discovered
# task file names. Accepts names with or without the .md extension.
# Returns only the matched names; warns for any name that cannot be resolved.

function Resolve-TaskNames {
    <#
    .SYNOPSIS
        Match user-requested task names against discovered task file names.
    .DESCRIPTION
        Each requested name is matched against the discovered list. Names
        without the .md extension are automatically appended with .md for
        matching. Unmatched names produce a warning and are excluded from
        the result.
    .PARAMETER Requested
        Array of task names from the user (with or without .md extension).
    .PARAMETER Discovered
        Array of discovered task file names (always with .md extension).
    .OUTPUTS
        String array of matched discovered file names.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Requested,
        [Parameter(Mandatory = $true)]
        [string[]]$Discovered
    )

    $selected = foreach ($name in $Requested) {
        $base = [System.IO.Path]::GetFileNameWithoutExtension($name)
        $mdName = "$base.md"
        if ($mdName -in $Discovered) {
            $mdName
        } elseif ($name -in $Discovered) {
            $name
        } else {
            Write-Host "WARNING: '$name' not found among discovered tasks, skipping." -ForegroundColor Yellow
        }
    }

    # .Where({$_}) is the array-method form — always returns a collection,
    # even for single-element results.  This prevents PowerShell from
    # unwrapping a single string return into a scalar.
    return @($selected).Where({ $_ })
}

# ── Task category matching ───────────────────────────────────────────────────
# Checks whether a discovered task file path belongs to the given category.
# Category paths use platform-appropriate directory separators.

function Test-TaskCategory {
    <#
    .SYNOPSIS
        Test whether a task file path belongs to a given category.
    .DESCRIPTION
        Uses a path-to-segment lookup to determine if a file path matches a
        category. Categories are: generic, browser4, real-world, mock-site.
    .PARAMETER FilePath
        The full path of the task file.
    .PARAMETER Category
        The category to test against (generic, browser4, real-world, mock-site).
    .OUTPUTS
        Boolean. $true if the file belongs to the category.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [ValidateSet('generic', 'browser4', 'real-world', 'mock-site')]
        [string]$Category
    )

    # Normalize separators to the platform directory separator for reliable
    # substring matching regardless of whether the path uses \ or /.
    $sep = [System.IO.Path]::DirectorySeparatorChar
    $normalized = $FilePath -replace '[/\\]', $sep

    $segmentMap = @{
        'generic'    = "${sep}real-world${sep}generic${sep}"
        'browser4'   = "${sep}real-world${sep}browser4${sep}"
        'real-world' = "${sep}real-world${sep}"
        'mock-site'  = "${sep}mock-site${sep}"
    }

    return $normalized.Contains($segmentMap[$Category])
}

function Read-TaskFile {
    <#
    .SYNOPSIS
        Parse a task markdown file into a scenario name and body.
    .DESCRIPTION
        Reads a task file, extracts its first level-one heading as the scenario
        name, and returns the remaining non-empty content as the task body.
    .PARAMETER Path
        Path to the task markdown file.
    .OUTPUTS
        PSCustomObject with Name and Body string properties.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Task file not found: $Path"
    }

    $rawContent = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($rawContent)) {
        throw "Task file is empty: $Path"
    }

    $name = ''
    $body = $rawContent
    if ($rawContent -match '(?m)^\s*#\s+(.+?)\s*$') {
        $name = $Matches[1].Trim()
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

# ── Compiled output handler (C#) ──────────────────────────────────────────────
# DataReceived events fire on .NET threadpool threads.  PowerShell scriptblocks
# cast to delegates require a Runspace on the executing thread, which threadpool
# threads do not have — causing "There is no Runspace available to run scripts
# in this thread."  A compiled C# class avoids PowerShell entirely for event
# handling and works reliably on any thread.
# Detect whether the already-loaded type has the GetTail method (added 2026-07-26).
# On re-runs within the same pwsh session the type may be stale — force a
# recompile if the old version is loaded.
# Determine whether the handler type is available and whether it has
# checkpoint support (the 3-arg constructor added July 2026).  Three states:
#   1. Type doesn't exist  → compile it fresh
#   2. Type exists, has checkpoints → use as-is
#   3. Type exists, OLD (no checkpoints) → use old 1-arg ctor, skip checkpoints
# State 3 happens when a pwsh session survives across a git checkout that
# changed the C# source — the old type is still loaded and Add-Type can't
# replace it without restarting the session.
$script:HandlerHasCheckpoints = $false
try {
    $null = [NativeCommandOutputHandler].GetMethod('GetCheckpointStepCount')
    $script:HandlerHasCheckpoints = $true
} catch {
    # Checkpoint method not found — type is either missing or pre-checkpoint
    $typeExists = $false
    try { $null = [NativeCommandOutputHandler]; $typeExists = $true } catch { }

    if ($typeExists) {
        # Old version loaded — can't recompile.  Degrade gracefully.
        if (-not $script:_OldHandlerWarningShown) {
            Write-Host '  Note: NativeCommandOutputHandler (old) loaded — checkpoint files unavailable.' -ForegroundColor DarkGray
            Write-Host '  Restart your PowerShell session for real-time step checkpoints.' -ForegroundColor DarkGray
            $script:_OldHandlerWarningShown = $true
        }
    } else {
        # Type doesn't exist — compile fresh
        Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Collections.Generic;

public class NativeCommandOutputHandler
{
    private readonly string _capturePath;
    private readonly UTF8Encoding _utf8;
    private readonly string[] _ringBuffer;
    private int _ringIndex;
    private int _ringCount;
    private readonly object _lock;

    // ── Checkpoint support ──────────────────────────────────────────────
    private readonly string _checkpointDir;
    private readonly string _checkpointScenario;
    private readonly object _checkpointLock;
    private readonly List<StepRecord> _steps;
    private string _currentStepId;
    private string _currentStepLabel;
    private DateTime _currentStepStart;

    // Regex patterns for step markers the agent emits
    private static readonly Regex _stepStartRx = new Regex(
        @">>>\s+STEP\s+(\S+):\s*(.+)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);
    private static readonly Regex _stepEndRx = new Regex(
        @"<<<\s+STEP\s+(\S+):\s+(PASS|FAIL)\s*[—\-–]\s*(.+)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);
    private static readonly Regex _abortRx = new Regex(
        @"!!!\s+ABORT\s+at\s+step\s+(\S+):\s*(.+)\s*!!!",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public NativeCommandOutputHandler(string capturePath)
        : this(capturePath, null, null) { }

    public NativeCommandOutputHandler(string capturePath,
                                       string checkpointDir,
                                       string checkpointScenario)
    {
        _capturePath = capturePath;
        _utf8 = new UTF8Encoding(false);
        _ringBuffer = new string[10];
        _ringIndex = 0;
        _ringCount = 0;
        _lock = new object();

        _checkpointDir = checkpointDir;
        _checkpointScenario = checkpointScenario;
        _checkpointLock = new object();
        _steps = new List<StepRecord>();
    }

    public void OnOutputReceived(object sender, System.Diagnostics.DataReceivedEventArgs e)
    {
        if (e.Data == null) return;

        Console.WriteLine(e.Data);
        try
        {
            File.AppendAllText(_capturePath, e.Data + Environment.NewLine, _utf8);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(
                "[NativeCommandOutputHandler] Failed to write capture line: " + ex.Message);
        }

        lock (_lock)
        {
            _ringBuffer[_ringIndex] = e.Data;
            _ringIndex = (_ringIndex + 1) % _ringBuffer.Length;
            if (_ringCount < _ringBuffer.Length) _ringCount++;
        }

        // ── Checkpoint detection (runs when checkpointing is enabled) ──
        if (_checkpointDir != null)
        {
            TryDetectStepMarker(e.Data);
        }
    }

    private void TryDetectStepMarker(string line)
    {
        // Step start: >>> STEP 3/7: description
        var startMatch = _stepStartRx.Match(line);
        if (startMatch.Success)
        {
            _currentStepId = startMatch.Groups[1].Value.Trim();
            _currentStepLabel = startMatch.Groups[2].Value.Trim();
            _currentStepStart = DateTime.UtcNow;
            return;
        }

        // Abort: !!! ABORT at step N: reason !!!
        var abortMatch = _abortRx.Match(line);
        if (abortMatch.Success)
        {
            var stepId = abortMatch.Groups[1].Value.Trim();
            var reason = abortMatch.Groups[2].Value.Trim();
            WriteCheckpoint(stepId, "ABORT", reason);
            WriteProgressFile();
            return;
        }

        // Step complete: <<< STEP 3/7: PASS — summary
        var endMatch = _stepEndRx.Match(line);
        if (endMatch.Success)
        {
            var stepId = endMatch.Groups[1].Value.Trim();
            var result = endMatch.Groups[2].Value.Trim();
            var summary = endMatch.Groups[3].Value.Trim();
            WriteCheckpoint(stepId, result, summary);
            WriteProgressFile();
        }
    }

    private void WriteCheckpoint(string stepId, string result, string summary)
    {
        lock (_checkpointLock)
        {
            var elapsed = (_currentStepStart != default(DateTime))
                ? (DateTime.UtcNow - _currentStepStart).TotalMilliseconds
                : 0.0;

            var rec = new StepRecord
            {
                step = stepId,
                label = _currentStepLabel ?? "",
                result = result,
                summary = summary,
                timestamp = DateTime.UtcNow.ToString("o"),
                elapsedMs = (long)elapsed
            };
            _steps.Add(rec);

            try
            {
                var json = StepRecordToJson(rec);
                var safeId = stepId.Replace('/', '-').Replace('\\', '-');
                var fileName = string.Format("{0}-step-{1}.json",
                    _checkpointScenario, safeId);
                var filePath = Path.Combine(_checkpointDir, fileName);
                File.WriteAllText(filePath, json, _utf8);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine(
                    "[NativeCommandOutputHandler] Failed to write step checkpoint: " + ex.Message);
            }
        }
    }

    private void WriteProgressFile()
    {
        lock (_checkpointLock)
        {
            try
            {
                var sb = new StringBuilder();
                sb.Append("{\n");
                sb.AppendFormat("  \"scenario\": {0},\n",
                    JsonEscape(_checkpointScenario));
                sb.AppendFormat("  \"lastStep\": {0},\n",
                    JsonEscape(_steps.Count > 0 ? _steps[_steps.Count - 1].step : ""));
                sb.AppendFormat("  \"lastUpdate\": {0},\n",
                    JsonEscape(DateTime.UtcNow.ToString("o")));
                sb.AppendFormat("  \"stepCount\": {0},\n", _steps.Count);
                sb.Append("  \"steps\": [\n");
                for (int i = 0; i < _steps.Count; i++)
                {
                    if (i > 0) sb.Append(",\n");
                    sb.Append("    ");
                    sb.Append(StepRecordToJson(_steps[i]));
                }
                sb.Append("\n  ]\n}");
                var filePath = Path.Combine(_checkpointDir,
                    _checkpointScenario + "-progress.json");
                File.WriteAllText(filePath, sb.ToString(), _utf8);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine(
                    "[NativeCommandOutputHandler] Failed to write progress file: " + ex.Message);
            }
        }
    }

    private static string StepRecordToJson(StepRecord r)
    {
        var sb = new StringBuilder();
        sb.Append("{");
        sb.AppendFormat("\"step\":{0},", JsonEscape(r.step));
        sb.AppendFormat("\"label\":{0},", JsonEscape(r.label));
        sb.AppendFormat("\"result\":{0},", JsonEscape(r.result));
        sb.AppendFormat("\"summary\":{0},", JsonEscape(r.summary));
        sb.AppendFormat("\"timestamp\":{0},", JsonEscape(r.timestamp));
        sb.AppendFormat("\"elapsedMs\":{0}", r.elapsedMs);
        sb.Append("}");
        return sb.ToString();
    }

    private static string JsonEscape(string s)
    {
        if (string.IsNullOrEmpty(s)) return "\"\"";
        var sb = new StringBuilder();
        sb.Append('"');
        foreach (char c in s)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default: sb.Append(c); break;
            }
        }
        sb.Append('"');
        return sb.ToString();
    }

    private class StepRecord
    {
        public string step;
        public string label;
        public string result;
        public string summary;
        public string timestamp;
        public long elapsedMs;
    }

    /// <summary>
    /// Returns the last <paramref name="maxLines"/> lines received, oldest
    /// first.  Returns null when no output has been received yet.
    /// </summary>
    public string GetTail(int maxLines = 10)
    {
        lock (_lock)
        {
            if (_ringCount == 0) return null;
            int n = maxLines < _ringCount ? maxLines : _ringCount;
            int start = _ringCount < _ringBuffer.Length ? 0 : _ringIndex;
            var sb = new StringBuilder();
            for (int i = _ringCount - n; i < _ringCount; i++)
            {
                int idx = (start + i) % _ringBuffer.Length;
                if (i > _ringCount - n) sb.Append('\n');
                sb.Append(_ringBuffer[idx]);
            }
            return sb.ToString();
        }
    }

    /// <summary>
    /// Returns the number of completed checkpoint steps seen so far.
    /// Used as a recompilation sentinel (GetMethod check above) and
    /// available for heartbeat display.
    /// </summary>
    public int GetCheckpointStepCount()
    {
        lock (_checkpointLock ?? _lock)
        {
            return _steps?.Count ?? 0;
        }
    }
}
'@ -ErrorAction Stop
        $script:_NativeCommandHandlerCompiled = $true
        $script:HandlerHasCheckpoints = $true
    }
}

# ── Path resolution ──────────────────────────────────────────────────────────
# Repo root is 3 levels up from scripts/ (scripts -> tests -> browser4-tests -> repo root)
$script:RepoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$script:IssuesDraftDir = [System.IO.Path]::GetFullPath(
    (Join-Path $script:RepoRoot 'coworker' 'tasks' 'issues' 'draft')
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
    # Dev mode: use ./b4w.ps1 so the agent tests the locally-built CLI and
    # the daemon auto-starts the locally-built backend JAR.  The repo root
    # is the CWD when the agent runs.
    $helpCmd        = "./b4w.ps1 help"
    $skillPath      = 'skills/browser4-cli/SKILL.md'
    $cliInvocation  = "./b4w.ps1"
}

# ── Shared evaluation prompt ────────────────────────────────────────────────
# Every scenario prepends this to its task-specific prompt so the agent
# consistently evaluates browser4-cli usability while completing the task.
$generalPrompt = @"
You are evaluating the usability, discoverability, and reliability of browser4-cli while completing a real-world task.

## Preparation

Before performing any browser interaction:

0. Verify your working directory is the repository root: `$($RepoRootPath)`. If `pwd` is anything other than this directory, navigate there immediately with `cd "$RepoRootPath"`. All browser4-cli commands use `$($cliInvocation)` which works from the repo root — stay in this directory for all commands.
    **IMPORTANT — Temporary files:** Create ALL temporary, intermediate, and scratch files (scripts, data dumps, HTML snapshots, JSON exports, markdown drafts, log files, etc.) inside `./.test-sessions/` (not the repo root). Before creating any file, ensure the directory exists with `mkdir -p .test-sessions`. Do NOT pollute the repository root with temporary files — every generated file that is not a permanent project asset belongs under `.test-sessions/`.
1. Run `$($helpCmd)`.
2. Read `$($skillPath)` completely.
3. Learn the available commands, workflows, and conventions directly from the documentation.
4. Do not assume any prior knowledge of browser4-cli.

## Backend Server

$(if ($browser4cliMode -eq 'production') {
"Production mode: browser4-cli connects to a separately-managed backend server. Ensure the **latest runtime bundle release** is deployed and running before starting the task. The CLI does not auto-start a server in production mode — if no server is reachable, commands will fail with a connection error."
} else {
"Dev mode: the CLI daemon **auto-starts the locally-built backend JAR** from the repository. No manual server setup is needed — the first \`./b4w.ps1\` command will start the daemon and backend automatically. The backend runs from the local source tree, matching the code currently checked out. Do NOT download or install a separate runtime bundle — that would test a stale release instead of the local changes.`n`nIf the daemon or backend fails to start automatically: (a) check for port conflicts on the default port, (b) verify a Java runtime is available, (c) retry the command once. If it still fails after one retry, record the error as a **Reliability** issue with the full error output and continue with any commands that do not require a running browser."
})

## Command Invocation

Every browser4-cli command in this session MUST be invoked as:

`$($cliInvocation) <command>`

For example:
  `$($cliInvocation) goto "https://example.com"`
  `$($cliInvocation) snapshot -i`
  `$($cliInvocation) click e5`

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

#### Alternative JSON format (preferred for machine processing):

As an alternative to the markdown format above, you may deliver Sections C (Issues Found) and D (Overall Assessment) as a **single JSON code block**. This format is **preferred** — it ensures reliable machine parsing, while the markdown format above is a backward-compatible fallback. Sections A (Task Result) and B (Execution Trace) must still be written as prose above the JSON block.

``````json
{
  "issues": [
    {
      "title": "Brief descriptive title",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "Exact command(s) or steps to reproduce the issue.",
      "expected": "What should have happened.",
      "actual": "What actually happened.",
      "rootCause": "Your best analysis of the technical cause. Infer from observed behavior when possible; note what investigation is needed when uncertain. This is essential for an AI coder to fix the issue later.",
      "codePointer": "File path and function name where a fix should likely be applied (e.g. cli/browser4-cli/src/snapshot.rs:render_snapshot()). Leave empty string if unknown.",
      "suggestion": "- First concrete suggestion\n- Second concrete suggestion\n- Additional suggestions as needed"
    }
  ],
  "assessment": {
    "completionStatus": "Successful / Partially Successful / Failed — describe the overall task outcome",
    "successRate": "e.g. 80% — estimated percentage of task steps that succeeded",
    "issuesFound": 8,
    "majorBlockers": "Description of any major blockers encountered, or empty string if none.",
    "mostConfusingAspects": "Most confusing aspects for a first-time user.",
    "mostValuableImprovements": "Most valuable suggested improvements.",
    "usabilityRating": 5
  }
}
``````

**Rules for the JSON format:**

- Every field is a string except **issuesFound** (integer) and **usabilityRating** (integer 1–10).
- **severity** must be one of: Critical, High, Medium, Low.
- **category** must be one of: Product, Documentation, UX, Reliability, Discoverability.
- Empty/unavailable fields should be an empty string "", never omitted.
- Use \n for multi-line content within string values (e.g. bullet lists in **suggestion**).
- Place the JSON block after Sections A and B. It replaces Sections C and D entirely.

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
* **ALL temporary files** (scripts, data files, HTML exports, JSON dumps, screenshots, logs, markdown drafts, etc.) **MUST** be created inside `./.test-sessions/`. Never write temporary files to the repository root. Before creating any file, run `mkdir -p .test-sessions` if the directory does not already exist.

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
        preserved by Write-IssuesToDraft).
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
        'Review'                       = 'Review'
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

# ── JSON evaluation extraction ─────────────────────────────────────────────────
# Preferred format.  Detects a ```json code block containing the canonical
# evaluation schema (issues array + optional assessment object) and returns
# structured hashtables directly — no regex parsing needed.  Returns $null
# when no valid JSON block is found, signalling the caller to fall back to
# markdown parsing.

function ConvertFrom-JsonEvaluation {
    <#
    .SYNOPSIS
        Extract issues and assessment from a JSON code block in agent output.
    .DESCRIPTION
        Searches the agent output for ```json code blocks and attempts to parse
        each as the evaluation JSON schema.  Returns structured hashtables on
        success, or $null when no valid JSON is found so the caller can fall
        back to markdown parsing.

        The expected JSON schema mirrors the prompt instructions:

          { "issues": [ { title, severity, category, reproduction, expected,
              actual, rootCause, codePointer, suggestion } ],
            "assessment": { completionStatus, successRate, issuesFound,
              majorBlockers, mostConfusingAspects, mostValuableImprovements,
              usabilityRating } }

        Issues are returned in the same hashtable format as
        ConvertFrom-IssuesSection so downstream code works unchanged.
    .PARAMETER Content
        The full raw agent output.
    .OUTPUTS
        Hashtable with keys Issues (array of hashtables) and Assessment
        (hashtable), or $null if no valid JSON block is found.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content
    )

    $normalized = $Content -replace '\r\n', "`n"

    # Find all ```json code blocks — try each one until we get a valid parse
    $blockPattern = '(?s)```json\s*\n(.*?)```'
    $blockMatches = [regex]::Matches($normalized, $blockPattern)

    foreach ($blockMatch in $blockMatches) {
        $jsonStr = $blockMatch.Groups[1].Value.Trim()
        if (-not $jsonStr) { continue }

        try {
            $data = $jsonStr | ConvertFrom-Json -ErrorAction Stop
        } catch {
            # Not valid JSON — try the next block
            continue
        }

        # Must have at least an issues array
        if (-not $data.issues) { continue }

        $issueArray = @($data.issues)  # @() guards against single-object unwrapping
        if ($issueArray.Count -eq 0) { continue }

        # ── Map JSON issues → hashtable array (same shape as ConvertFrom-IssuesSection) ──
        $defaultReview = @'
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
'@

        $issues = [System.Collections.ArrayList]::new()
        foreach ($iss in $issueArray) {
            $issueNum = $issues.Count + 1
            [void]$issues.Add(@{
                Title        = [string]$iss.title
                Severity     = [string]$iss.severity
                Category     = [string]$iss.category
                Reproduction = [string]$iss.reproduction
                Expected     = [string]$iss.expected
                Actual       = [string]$iss.actual
                RootCause    = [string]$iss.rootCause
                CodePointer  = [string]$iss.codePointer
                Review       = $defaultReview
                Suggestion   = [string]$iss.suggestion
            })
        }

        # ── Map JSON assessment → hashtable (if present) ──────────────────────
        $assessment = $null
        if ($data.assessment) {
            $a = $data.assessment
            $assessment = @{
                CompletionStatus        = if ($a.completionStatus)        { [string]$a.completionStatus }        else { '' }
                SuccessRate             = if ($a.successRate)             { [string]$a.successRate }             else { '' }
                IssuesFound             = if ($null -ne $a.issuesFound)   { [int]$a.issuesFound }                else { 0 }
                MajorBlockers           = if ($a.majorBlockers)           { [string]$a.majorBlockers }           else { '' }
                MostConfusingAspects    = if ($a.mostConfusingAspects)    { [string]$a.mostConfusingAspects }    else { '' }
                MostValuableImprovements = if ($a.mostValuableImprovements) { [string]$a.mostValuableImprovements } else { '' }
                UsabilityRating         = if ($null -ne $a.usabilityRating) { [int]$a.usabilityRating }           else { 0 }
            }
        }

        return @{
            Issues     = $issues.ToArray()
            Assessment = $assessment
        }
    }

    return $null
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

    return $result | ConvertTo-Json -Depth 10
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

    $data = $Json | ConvertFrom-Json

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

function Write-IssuesToDraft {
    <#
    .SYNOPSIS
        Write the agent evaluation output to the issues draft directory.
    .DESCRIPTION
        Saves the complete agent output (containing A. Task Result, B. Execution Trace,
        C. Issues Found, D. Overall Assessment) as a markdown file in the
        issues/draft directory for downstream refinement.

        Also extracts background context (Sections A + B) and parses individual
        issues from Section C, then writes three files:

          - .full.md    — complete raw agent output (verbatim reference)
          - .issues.md  — consolidated issues with background, reproduction
                           guide, and overall assessment (markdown)
          - .issues.json — canonical JSON per the schema shared with
                            coworker/gui/frontend/issue-model.js
                           (machine-readable, for GUI / CI consumption)

        Issues discovered in a single scenario are kept together because they
        are often interrelated — shared root causes, shared reproduction
        environments, or cascading failures.

        Always writes the full output regardless of whether individual issues
        can be parsed.
    .PARAMETER ScenarioName
        Short name identifying the scenario (e.g. "amazon", "hacker-news").
    .PARAMETER Content
        The full text output from the agent evaluation.
    .PARAMETER OutputDirectory
        Optional override for the draft directory. Defaults to $IssuesDraftDir.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScenarioName,

        [Parameter(Mandatory = $true)]
        [string]$Content,

        [string]$OutputDirectory = $script:IssuesDraftDir
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

    # 3) Parse issues — try JSON first (preferred, more reliable), fall back to
    #    markdown parsing when no valid JSON block is found.
    $jsonEval = ConvertFrom-JsonEvaluation -Content $Content
    if ($jsonEval) {
        $issues = $jsonEval.Issues
        $assessment = $jsonEval.Assessment
        Write-Host "  Parsed $($issues.Count) issue(s) from JSON block" -ForegroundColor DarkGray
    } else {
        $issues = ConvertFrom-IssuesSection -Content $Content
        $assessment = $null
    }

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
        # Sort issues by severity: Critical > High > Medium > Low.
        # Unknown/empty severities sort last.
        $severityRank = @{
            'Critical' = 0
            'High'     = 1
            'Medium'   = 2
            'Low'      = 3
        }
        $issues = @($issues | Sort-Object {
            $sev = $_.Severity
            if ($severityRank.ContainsKey($sev)) { $severityRank[$sev] } else { 4 }
        })

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

        # ── Overall assessment (JSON-parsed evaluations only) ───────────────
        if ($assessment) {
            $consBody += "## Overall Assessment`n`n"
            if ($assessment.CompletionStatus) {
                $consBody += "**Completion Status:** $($assessment.CompletionStatus)`n`n"
            }
            if ($assessment.SuccessRate) {
                $consBody += "**Success Rate:** $($assessment.SuccessRate)`n`n"
            }
            if ($assessment.IssuesFound -gt 0) {
                $consBody += "**Issues Found:** $($assessment.IssuesFound)`n`n"
            }
            if ($assessment.MajorBlockers) {
                $consBody += "**Major Blockers:** $($assessment.MajorBlockers)`n`n"
            }
            if ($assessment.MostConfusingAspects) {
                $consBody += "**Most Confusing Aspects:** $($assessment.MostConfusingAspects)`n`n"
            }
            if ($assessment.MostValuableImprovements) {
                $consBody += "**Most Valuable Improvements:** $($assessment.MostValuableImprovements)`n`n"
            }
            if ($assessment.UsabilityRating -gt 0) {
                $consBody += "**Usability Rating:** $($assessment.UsabilityRating)/10`n`n"
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
            $consBody += "2. The CLI is invoked via ``./b4w.ps1`` which auto-builds from source when needed.`n"
            $consBody += "3. The backend server starts automatically in dev mode.`n"
            $consBody += "4. All commands from repo root: ``./b4w.ps1 <command>```n`n"
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

    # 5) Write the canonical JSON file for machine consumption (GUI, CI, etc.)
    $jsonFileName = "$timestamp-$safeName.issues.json"
    $jsonFilePath = Join-Path $OutputDirectory $jsonFileName
    $absoluteJsonPath = [System.IO.Path]::GetFullPath($jsonFilePath)
    $modeLabel = if ($browser4cliMode -eq 'production') { 'production' } else { 'dev' }
    $jsonOutput = ConvertTo-IssueJson -ScenarioName $ScenarioName `
        -SourceFile $fullFileName `
        -Timestamp $timestamp `
        -Mode $modeLabel `
        -Background $bg `
        -Issues $issues
    [System.IO.File]::WriteAllText($absoluteJsonPath, $jsonOutput, $utf8NoBom)
    Write-Host "  Wrote issues JSON: $absoluteJsonPath" -ForegroundColor DarkGray
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
        [int] $TimeoutSeconds = 0,

        # ── Checkpoint support ──────────────────────────────────────────
        # When both parameters are provided the output handler writes a
        # per-step checkpoint JSON after every `<<< STEP N: PASS|FAIL`
        # marker, plus a cumulative <scenario>-progress.json file.
        # Checkpoints are written in real time as the agent emits markers.
        [string] $CheckpointDir = '',

        [string] $CheckpointScenario = ''
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
    if ($CheckpointDir -and $CheckpointScenario -and $script:HandlerHasCheckpoints) {
        # Ensure checkpoint dir exists before the handler starts writing
        if (-not (Test-Path -LiteralPath $CheckpointDir)) {
            New-Item -ItemType Directory -Path $CheckpointDir -Force | Out-Null
        }
        $nativeHandler = New-Object NativeCommandOutputHandler $capturePath, $CheckpointDir, $CheckpointScenario
    } else {
        $nativeHandler = New-Object NativeCommandOutputHandler $capturePath
    }
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

                # When checkpoints are enabled, show how many steps completed
                if ($CheckpointDir -and $CheckpointScenario -and $script:HandlerHasCheckpoints) {
                    $stepCount = $nativeHandler.GetCheckpointStepCount()
                    if ($stepCount -gt 0) {
                        [Console]::WriteLine(
                            "  · Checkpoints: $stepCount step(s) completed → .test-sessions/${CheckpointScenario}-progress.json"
                        )
                    }
                }

                # Show the last 10 lines of agent output so the heartbeat is
                # meaningful — the user can see what the agent is doing instead
                # of just staring at an elapsed-time counter.
                $tail = $nativeHandler.GetTail()
                if ($tail) {
                    [Console]::WriteLine('  ══ last 10 lines ══')
                    $maxWidth = try { [Math]::Min([Console]::WindowWidth - 4, 160) } catch { 156 }
                    foreach ($line in ($tail -split "`n")) {
                        # Truncate long lines so they don't wrap awkwardly
                        if ($line.Length -gt $maxWidth) {
                            $line = $line.Substring(0, $maxWidth - 1) + [char]0x2026  # '…'
                        }
                        [Console]::WriteLine("  │ ${line}")
                    }
                    [Console]::WriteLine('  ══════════════════')
                } else {
                    [Console]::WriteLine('  · (no output yet)')
                }

                $lastHeartbeatSec = $elapsed
            }
        }

        # ── Drain final output ─────────────────────────────────────────────
        # The process has exited but async reads may still have data in flight.
        # WaitForExit() (parameterless) blocks until all OutputDataReceived /
        # ErrorDataReceived event handlers have finished processing — without
        # this, CancelOutputRead() may discard data that hasn't been delivered
        # yet, losing most of the captured output.
        try { $proc.WaitForExit() } catch { }

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
        Reads the expected version from the repo-root VERSION file and
        compares it against the installed binary.

        In production mode, runs `browser4-cli --version` to get the installed
        version.  If it differs from the expected version, writes a prominent
        warning with upgrade instructions and returns a non-zero exit code so
        the caller can abort.

        In dev mode, simply reports the expected version (./b4w.ps1 always
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

    # Read the expected version from the repo-root VERSION (the canonical source).
    $versionCliPath = Join-Path $script:RepoRoot 'VERSION'
    if (-not (Test-Path -LiteralPath $versionCliPath -PathType Leaf)) {
        if (-not $Silent) {
            Write-Host "WARNING: VERSION not found at $versionCliPath -- cannot verify CLI version." -ForegroundColor Yellow
        }
        return 0
    }

    $expectedVersion = (Get-Content -LiteralPath $versionCliPath -TotalCount 1).Trim()
    if (-not $expectedVersion) {
        if (-not $Silent) {
            Write-Host "WARNING: VERSION is empty -- cannot verify CLI version." -ForegroundColor Yellow
        }
        return 0
    }

    # ── Dev mode: ./b4w.ps1 always builds from the latest source ──────────
    if ($browser4cliMode -ne 'production') {
        if (-not $Silent) {
            Write-Host "Dev mode: ./b4w.ps1 builds browser4-cli from source (expected v$expectedVersion)." -ForegroundColor DarkGray
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

# ── Workflow pre-flight checks ─────────────────────────────────────────────────
# Runs BEFORE the expensive agent invocation so common environment problems
# (missing CLI, dead backend) are caught in seconds instead of waiting 5-20
# minutes for the agent to discover them.

function Test-WorkflowPreflight {
    <#
    .SYNOPSIS
        Quick pre-flight checks before launching the expensive agent call.
    .DESCRIPTION
        Verifies that the CLI is available and the backend responds.  These checks
        run in seconds — failing fast here avoids waiting minutes for an agent
        to discover the same problem.

        In dev mode, the first CLI invocation auto-starts the backend, so a
        successful `help` call confirms both the CLI binary and backend are
        functional.  In production mode it confirms the CLI is installed and
        can reach the backend.

        Returns $true when all checks pass, $false otherwise.  Callers may still
        proceed on failure (the agent will diagnose further), but should print
        a prominent warning.
    .PARAMETER Silent
        Suppress informational messages.  Failures are always reported.
    .OUTPUTS
        Boolean — $true when all checks pass.
    #>
    param(
        [switch] $Silent
    )

    $allPassed = $true
    $checkCount = 2

    if (-not $Silent) {
        Write-Host ''
        Write-Host '=== Pre-flight checks ===' -ForegroundColor Cyan
    }

    # ── Check 1: CLI availability ──────────────────────────────────────────
    if (-not $Silent) {
        Write-Host "  [1/$checkCount] CLI ($cliInvocation) ... " -NoNewline
    }
    try {
        $versionOut = & $cliInvocation --version 2>&1 | Out-String
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
            throw "exit code $LASTEXITCODE"
        }
        if (-not $Silent) {
            $ver = ($versionOut -replace '\s+', ' ').Trim()
            Write-Host "OK ($ver)" -ForegroundColor Green
        }
    } catch {
        $allPassed = $false
        if (-not $Silent) {
            Write-Host 'FAIL' -ForegroundColor Red
            Write-Host "    CLI not functional: $_" -ForegroundColor Red
            if ($browser4cliMode -eq 'production') {
                Write-Host '    Ensure browser4-cli is installed and on PATH.' -ForegroundColor DarkGray
            } else {
                Write-Host '    The CLI may need to be built. Try running ./b4w.ps1 directly first.' -ForegroundColor DarkGray
            }
        }
    }

    # ── Check 2: Backend responds ──────────────────────────────────────────
    if (-not $Silent) {
        Write-Host "  [2/$checkCount] Backend ... " -NoNewline
    }
    try {
        # Stopwatch for backend start timing (dev mode auto-starts the JAR)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $helpOut = & $cliInvocation help 2>&1 | Out-String
        $sw.Stop()
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
            throw "exit code $LASTEXITCODE"
        }
        if ($helpOut -match 'Usage:' -or $helpOut -match 'Commands:' -or $helpOut -match 'SUBCOMMANDS:') {
            if (-not $Silent) {
                $dur = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
                Write-Host "OK (${dur}s)" -ForegroundColor Green
            }
        } else {
            throw 'help output does not contain expected sections'
        }
    } catch {
        $allPassed = $false
        if (-not $Silent) {
            Write-Host 'FAIL' -ForegroundColor Red
            Write-Host "    Backend not responding: $_" -ForegroundColor Red
            if ($browser4cliMode -ne 'production') {
                Write-Host '    In dev mode the backend auto-starts. Check Java and port availability.' -ForegroundColor DarkGray
            } else {
                Write-Host '    Ensure the Browser4 backend server is running.' -ForegroundColor DarkGray
            }
        }
    }

    # ── Summary ────────────────────────────────────────────────────────────
    if (-not $Silent) {
        if ($allPassed) {
            Write-Host 'Pre-flight: ALL CHECKS PASSED' -ForegroundColor Green
        } else {
            Write-Host 'Pre-flight: SOME CHECKS FAILED — agent will run but may encounter errors' -ForegroundColor Yellow
        }
        Write-Host ''
    }

    return $allPassed
}

# ── Workflow banner ────────────────────────────────────────────────────────────
# Prints a standardized header so users know what to expect before the agent
# starts.  The estimated duration sets expectations and the step count provides
# a reference for the progress markers the agent emits.

function Write-WorkflowBanner {
    <#
    .SYNOPSIS
        Print a standardized workflow banner before launching the agent.
    .DESCRIPTION
        Shows the workflow name, step count, and estimated duration so users
        know what to expect.  The agent will emit `>>> STEP N/M` markers as it
        progresses through steps, giving real-time visibility via the heartbeat
        tail display.
    .PARAMETER WorkflowName
        Human-readable name shown in the banner.
    .PARAMETER StepCount
        Total number of verification steps the agent will execute (used for
        progress-marker reference).
    .PARAMETER EstimatedDuration
        Human-readable duration estimate (e.g. "5–10 minutes").
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string] $WorkflowName,

        [Parameter(Mandatory = $true)]
        [int] $StepCount,

        [string] $EstimatedDuration = '5–15 minutes'
    )

    $width = 64
    $line = [string]::new([char]0x2500, $width)

    Write-Host ''
    Write-Host "  $line" -ForegroundColor Cyan
    Write-Host "   Workflow : $WorkflowName" -ForegroundColor Cyan
    Write-Host "   Steps    : $StepCount" -ForegroundColor Cyan
    Write-Host "   Estimate : $EstimatedDuration" -ForegroundColor Cyan
    Write-Host "  $line" -ForegroundColor Cyan
    Write-Host ''

    $agent = Get-ScenarioAgent
    Write-Host "  Agent will emit >>> STEP N/$StepCount markers in real time." -ForegroundColor DarkGray
    Write-Host "  Watch the heartbeat tail (every 30–120 s) for current step." -ForegroundColor DarkGray
    Write-Host ''
}

# ── Agent invocation ────────────────────────────────────────────────────────

function Get-ScenarioAgent {
    <#
    .SYNOPSIS
        Resolve which agent CLI (claude, kimi, or opencode) scenario scripts
        should invoke.
    .DESCRIPTION
        Callers may force a backend by setting $script:scenarioAgentCli = 'kimi'
        (or 'claude', 'opencode') after dot-sourcing this module.  Otherwise
        auto-detects with priority claude > kimi > opencode.  Falls back to
        'claude' when none are on PATH so the invocation fails with a clear
        command-not-found error.
    #>
    if ($script:scenarioAgentCli) { return $script:scenarioAgentCli }
    if (Get-Command claude -ErrorAction SilentlyContinue) { return 'claude' }
    if (Get-Command kimi -ErrorAction SilentlyContinue) { return 'kimi' }
    if (Get-Command opencode -ErrorAction SilentlyContinue) { return 'opencode' }
    return 'claude'
}

function Invoke-Agent {
    <#
    .SYNOPSIS
        Invoke the configured agent CLI (claude or kimi) to run a scenario and
        evaluate browser4-cli usability.
    .DESCRIPTION
        Runs the agent CLI resolved by Get-ScenarioAgent with the given prompt.
        When -ScenarioName is provided, captures output and writes evaluation
        results to the issues draft directory.  When -ScenarioName is omitted,
        preserves the original behavior (direct call, real-time output, no capture).

        In capture mode, output is simultaneously streamed to the console (so the
        user can watch the agent work) and saved to a temp file for post-processing.

        Backend differences:
          - claude: invoked with --dangerously-skip-permissions; -Silent appends
            --silent to the CLI arguments.
          - kimi:   -p mode auto-approves tool calls, so no permission flags are
            passed; kimi has no --silent flag, so -Silent only suppresses this
            script's own status messages.
    .PARAMETER Prompt
        The full prompt including the general evaluation instructions and
        task-specific instructions.
    .PARAMETER ScenarioName
        Optional scenario name (e.g. "amazon", "hacker-news"). When provided, output
        is captured and written to the issues draft directory at $IssuesDraftDir.
    .PARAMETER OutputFile
        Optional explicit path to save the raw agent output. Auto-generated from
        ScenarioName and timestamp when omitted.
    .PARAMETER Silent
        Suppress status messages (for claude, also passed through as --silent).
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

    $agent = Get-ScenarioAgent

    # ── Status header ────────────────────────────────────────────────────────
    if (-not $Silent) {
        $promptLen = $Prompt.Length
        $promptLines = ($Prompt -split "`n").Count
        Write-Host "Invoking $agent agent (prompt: $promptLen chars, $promptLines lines)" -ForegroundColor Cyan
        if ($ScenarioName) {
            Write-Host "  Scenario: $ScenarioName" -ForegroundColor DarkGray
        }
    }

    # ── Build agent arguments ───────────────────────────────────────────────
    # Each agent CLI has a different invocation pattern:
    #   claude:   claude --dangerously-skip-permissions -p <prompt> [--silent]
    #   kimi:     kimi -p <prompt>           (no --silent flag exists)
    #   opencode: opencode run <prompt>      (no --silent flag exists)
    $agentArgs = @()
    switch ($agent) {
        'claude' {
            $agentArgs += '--dangerously-skip-permissions'
            $agentArgs += @('-p', $Prompt)
            if ($Silent) { $agentArgs += '--silent' }
        }
        'kimi' {
            $agentArgs += @('-p', $Prompt)
        }
        'opencode' {
            $agentArgs += @('run', $Prompt)
        }
        default {
            # Unknown agent — assume -p mode (claude-compatible)
            $agentArgs += @('-p', $Prompt)
        }
    }

    # ── Ensure .test-sessions directory exists ────────────────────────────────
    # Agents are instructed to create temp files here.  Pre-create the directory
    # so the agent doesn't fail on the very first `mkdir -p .test-sessions` call.
    $testSessionsDir = Join-Path $script:RepoRoot '.test-sessions'
    if (-not (Test-Path -LiteralPath $testSessionsDir)) {
        New-Item -ItemType Directory -Path $testSessionsDir -Force | Out-Null
        Write-Host "  Created .test-sessions/ for agent temp files" -ForegroundColor DarkGray
    }

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

    # ── Invoke the agent CLI via Start-NativeCommand ────────────────────────
    $startParams = @{
        FilePath     = $agent
        ArgumentList = $agentArgs
    }
    if ($captureFile) {
        $startParams['CaptureFile'] = $captureFile
    }
    if ($TimeoutSeconds -gt 0) {
        $startParams['TimeoutSeconds'] = $TimeoutSeconds
    }
    # Enable real-time checkpoint files when a scenario name is provided
    if ($ScenarioName) {
        $startParams['CheckpointDir'] = $testSessionsDir
        $startParams['CheckpointScenario'] = $ScenarioName
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

    # ── Checkpoint summary ──────────────────────────────────────────────────
    if ($ScenarioName) {
        $progressFile = Join-Path $testSessionsDir "$ScenarioName-progress.json"
        if (Test-Path -LiteralPath $progressFile) {
            try {
                $progress = Get-Content -LiteralPath $progressFile -Raw -Encoding UTF8 | ConvertFrom-Json
                $stepCount = $progress.stepCount
                $lastStep = $progress.lastStep
                if ($stepCount -gt 0) {
                    Write-Host ''
                    Write-Host "  Checkpoint summary ($ScenarioName): $stepCount step(s) completed" -ForegroundColor Cyan
                    foreach ($step in $progress.steps) {
                        $color = if ($step.result -eq 'PASS') { 'Green' } elseif ($step.result -eq 'ABORT') { 'Red' } else { 'Yellow' }
                        Write-Host "    $($step.step): " -NoNewline
                        Write-Host "$($step.result)" -ForegroundColor $color -NoNewline
                        Write-Host " — $($step.summary)"
                    }
                    Write-Host "    Full: .test-sessions/${ScenarioName}-progress.json" -ForegroundColor DarkGray
                }
            } catch {
                Write-Host "  (checkpoint progress file could not be parsed)" -ForegroundColor DarkGray
            }
        }
    }

    # ── Truncation detection ────────────────────────────────────────────────
    # Detect when the capture file contains only a closing statement (e.g.
    # "The full report with N issues is above") without the actual report
    # content.  This indicates the async output capture lost most of the data.
    $hasStructuredSections = $capturedOutput -match '(?i)(###?\s+[ABCD][.\s])'
    $looksTruncated = (-not $hasStructuredSections) -and (
        ($capturedOutput -match '(?i)is above\.?\s*$') -or
        ($capturedOutput.Length -lt 500 -and $capturedOutput -match '(?i)(report|issues?|evaluation)\s+(is|are|complete)')
    )
    if ($looksTruncated) {
        Write-Host "  WARNING: Captured output may be truncated — only $($capturedOutput.Length) chars, no structured sections found." -ForegroundColor Yellow
        Write-Host "    The agent output was likely lost by the async capture mechanism." -ForegroundColor DarkGray
        Write-Host "    Full raw output saved to: $captureFile" -ForegroundColor DarkGray
        Write-Host "    Re-running the scenario may produce a complete capture." -ForegroundColor DarkGray
    }

    # Write to the issues draft directory
    if ($ScenarioName) {
        Write-IssuesToDraft -ScenarioName $ScenarioName -Content $capturedOutput
    }
}
