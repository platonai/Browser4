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
    Triggers CI on GitHub via tag push and monitors the workflow until completion.

.DESCRIPTION
    1. Calls trigger-ci.ps1 to create and push a pre-release CI tag.
    2. Captures the tag name and locates the triggered workflow run.
    3. Streams the workflow logs in real time.
    4. Reports the final conclusion (success/failure) and exits with the same code.

    On failure, by default the script does NOT call an AI agent to analyze the
    failure. It extracts minimal error diagnostics from the failed logs and
    prints them to the console so a human can review them. Pass -Agent auto
    (or a pinned backend name) to opt in: the script then creates a coworker
    task in 1ready/ and dispatches it via `b4w.ps1 coworker fix` so an AI
    agent analyzes and fixes the failure.

    Requires: gh CLI authenticated with the repo, and pwsh (PowerShell Core).

.PARAMETER PreReleaseVersion
    The pre-release label to use (default: "ci").  Passed through to trigger-ci.ps1.

.PARAMETER remote
    The git remote to push the tag to (default: "origin").

.PARAMETER PollIntervalSeconds
    How many seconds to wait between polls while the workflow is queued (default: 5).

.PARAMETER NoWatch
    Skip interactive `gh run watch` and poll with `gh run list` / `gh run view` instead.
    Useful on CI or non-interactive terminals.

.PARAMETER Agent
    On workflow failure, dispatch the failure to an AI agent for analysis and
    fixing. Values: auto (resolve the backend via coworker/scripts/workers/
    agent.ps1: claude, kimi, codex, dsh, or gh copilot), or a specific backend
    name (claude, kimi, codex, dsh, copilot). Without this flag the script
    only prints extracted error diagnostics and never invokes an AI agent.
    A pinned backend overrides $env:BROWSER4_AGENT for this invocation.

.EXAMPLE
    .\bin\ci\monitor-ci.ps1
    .\bin\ci\monitor-ci.ps1 -NoWatch
    .\bin\ci\monitor-ci.ps1 -PreReleaseVersion rc -PollIntervalSeconds 10
    .\bin\ci\monitor-ci.ps1 -NoWatch -Agent auto       # dispatch failures to an AI agent
#>

param(
    [string]$PreReleaseVersion = "ci",
    [string]$remote = "origin",
    [int]$PollIntervalSeconds = 5,
    [switch]$NoWatch,
    [ValidateSet('auto', 'claude', 'kimi', 'codex', 'dsh', 'copilot')]
    [string]$Agent = ""
)

$ErrorActionPreference = "Stop"

# Failure analysis is opt-in via -Agent. By default no AI agent is invoked:
# the script extracts and prints error diagnostics, then exits non-zero.
# Passing -Agent (auto or a pinned backend name) creates a coworker task and
# dispatches `b4w.ps1 coworker fix` to analyze and fix the failure.

# -Agent auto leaves the backend to the normal resolution chain (config.psd1
# order); a pinned name overrides it via $env:BROWSER4_AGENT, the canonical
# override honored first by the coworker agent resolution.
if ($Agent -and $Agent -ne 'auto') {
    $env:BROWSER4_AGENT = $Agent
}

# ═══════════════════════════════════════════════════════════════════════════
# Shared helpers: workflow-failure → coworker task dispatch
# ═══════════════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Normalize raw log output (string or array) into a clean string[].
    gh run view --log-failed returns a single string; other paths return
    arrays. This ensures consistent line-by-line iteration downstream.

.NOTES
    Every return uses a unary comma (`,`) so PowerShell does NOT unroll the
    array into individual pipeline objects. Without it the caller receives
    Object[] (2+ lines) or a scalar string (1 line), and
    List[string].AddRange(...) then throws:
        Cannot convert argument "collection", with value "System.Object[]" ...
    because the generic AddRange(IEnumerable[string]) overload cannot bind.
#>
function ConvertTo-LogLines {
    param([object]$RawLogs)
    if ($null -eq $RawLogs) { return ,[string[]]@() }
    if ($RawLogs -is [string]) {
        return ,[string[]]($RawLogs -split '\r?\n')
    }
    if ($RawLogs -is [System.Collections.IEnumerable]) {
        $result = [System.Collections.Generic.List[string]]::new()
        foreach ($item in $RawLogs) {
            if ($item -is [string]) {
                $sub = [string[]]($item -split '\r?\n')
                $result.AddRange($sub)
            } else {
                $result.Add([string]$item)
            }
        }
        return ,[string[]]$result.ToArray()
    }
    return ,[string[]]@([string]$RawLogs)
}

<#
.SYNOPSIS
    Parse a GitHub Actions log line in tab-separated format:
        job_name<TAB>step_name<TAB>timestamp<TAB>message
    Returns a hashtable with Job, Step, Timestamp, Message keys,
    or $null if the line doesn't match.
#>
function Parse-GitHubLogLine {
    param([string]$Line)

    if ($Line -notmatch "`t") { return $null }

    $parts = $Line -split "`t"
    if ($parts.Count -lt 3) { return $null }

    return @{
        Job       = $parts[0]
        Step      = $parts[1]
        Timestamp = $parts[2]
        Message   = if ($parts.Count -gt 3) { ($parts[3..($parts.Count - 1)] -join "`t") } else { '' }
    }
}

<#
.SYNOPSIS
    Extract minimal, deduplicated error messages from log output.
    Token-efficient: keeps only lines near error indicators, deduplicates
    near-duplicate blocks, and caps at a reasonable size.
#>
function Extract-MinimalErrors {
    param(
        [object]$LogLines,
        [string]$RunId = ''
    )

    $lines = ConvertTo-LogLines -RawLogs $LogLines
    if ($lines.Count -eq 0) {
        return "(No log output to analyze.)"
    }

    # ── Helper: extract the message from a log line and strip ANSI escapes ──
    function Get-CleanMessage {
        param([string]$RawLine)
        $parsed = Parse-GitHubLogLine -Line $RawLine
        if ($parsed -and $parsed.Message) {
            return ($parsed.Message -replace '\x1b\[[0-9;]*m', '').Trim()
        }
        return ($RawLine -replace '\x1b\[[0-9;]*m', '').Trim()
    }

    # ── Helper: test whether a cleaned message is shell script boilerplate ──
    # These are structural shell / GHA workflow lines that happen to contain
    # error-like words ("failed", "error") but are not themselves errors.
    $boilerplatePatterns = @(
        '^##\[(group|endgroup|debug|warning|notice)\]',   # GHA workflow commands
        '^\s*if\s+\[',           # if [ condition ]
        '^\s*if\s+\[\[',         # if [[ condition ]]
        '^\s*then\b',            # then
        '^\s*else\b',            # else
        '^\s*elif\s',            # elif
        '^\s*\bfi\b\s*$',        # fi
        '^\s*\bdo\b\s*$',        # do
        '^\s*\bdone\b\s*$',      # done
        '^\s*\besac\b\s*$',      # esac
        '^\s*echo\s',            # echo statements (reporting, not the error itself)
        '^\s*printf\s',          # printf statements
        '^\s*\w+=\S',            # variable assignments (VAR=value)
        '^\s*export\s',          # export VAR=...
        '^\s*\#\s'               # shell comments
    )

    function Test-IsBoilerplate {
        param([string]$CleanMessage)
        if ([string]::IsNullOrWhiteSpace($CleanMessage)) { return $true }
        foreach ($bp in $boilerplatePatterns) {
            if ($CleanMessage -match $bp) { return $true }
        }
        return $false
    }

    # ── Pass 1: Extract specific failing test names ──────────────────────
    $testFailures = [System.Collections.Generic.List[string]]::new()
    $seenTests    = @{}

    # Rust test: "test test_e2e_session_lifecycle ... FAILED"
    # Kotlin:    "Tests failed: 3, passed: 100"
    # Go:        "--- FAIL: TestName"
    # Generic:   "test_e2e_foo => FAILED"
    foreach ($ln in $lines) {
        $msg = Get-CleanMessage $ln

        if ($msg -match 'test\s+(\S+)\s+\.\.\.\s+FAILED') {
            $tn = $Matches[1]
            if (-not $seenTests.ContainsKey($tn)) { $seenTests[$tn] = $true; $testFailures.Add($tn) }
        }
        if ($msg -match '(test_e2e_\S+)\s.*=>\s*FAILED') {
            $tn = $Matches[1]
            if (-not $seenTests.ContainsKey($tn)) { $seenTests[$tn] = $true; $testFailures.Add($tn) }
        }
        if ($msg -match '---\s+FAIL:\s+(\S+)') {
            $tn = $Matches[1]
            if (-not $seenTests.ContainsKey($tn)) { $seenTests[$tn] = $true; $testFailures.Add($tn) }
        }
    }

    # ── Pass 2: Extract error blocks with context ────────────────────────
    $errorPatterns = @(
        'error:', 'Error:', 'ERROR:',
        '\[ERROR\]', '\[error\]',
        'FAILED', 'FAILURE',
        'exception', 'Exception', 'EXCEPTION',
        'panic:', 'fatal:', 'FATAL:',
        'exit code 1',
        'exit status 1',
        'Process completed with exit code',
        'BUILD FAILURE', 'BUILD FAILED',
        'Compilation failure', 'COMPILATION ERROR',
        'Caused by:',
        'assertion failed', 'AssertionError',
        'NullPointerException', 'IndexOutOfBounds',
        'unresolved reference', 'Unresolved reference',
        'Cannot resolve', 'Could not resolve',
        'Permission denied',
        'refused',
        'timeout', 'Timeout', 'TIMEOUT',
        'command not found', 'No such file',
        'NoClassDefFoundError', 'ClassNotFoundException',
        'error[E', 'error: [E',
        'Aborting due to',
        'Could not compile',
        'Failed to compile',
        'Tests failed',
        'test result: FAILED'
    )

    $seen = @{}
    $errorBlocks = [System.Collections.Generic.List[string]]::new()
    $prevWasSeparator = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $msg = Get-CleanMessage $line

        # Skip shell boilerplate: lines that contain error-indicator words
        # but are really just workflow script code (if/fi/echo/##[group]/…).
        if (Test-IsBoilerplate $msg) { continue }

        # Match error patterns against the cleaned message (not the raw line).
        $matched = $false
        foreach ($pat in $errorPatterns) {
            if ($msg -match [regex]::Escape($pat)) {
                $matched = $true
                break
            }
        }

        if ($matched) {
            $ctxBefore = 2
            $ctxAfter  = 3
            $start = [Math]::Max(0, $i - $ctxBefore)
            $end   = [Math]::Min($lines.Count - 1, $i + $ctxAfter)

            # Render block: for GH-format lines, strip the timestamp and show [Job/Step] prefix
            $blockLines = foreach ($j in $start..$end) {
                $ln = $lines[$j]
                $p = Parse-GitHubLogLine -Line $ln
                if ($p -and $p.Message -and $p.Message.Trim().Length -gt 0) {
                    "[$($p.Job) / $($p.Step)] $($p.Message)"
                } else {
                    $ln
                }
            }
            $block = ($blockLines -join "`n").Trim()
            if ($block.Length -lt 5) { continue }

            $hash = [System.BitConverter]::ToString(
                [System.Security.Cryptography.SHA256]::Create().ComputeHash(
                    [System.Text.Encoding]::UTF8.GetBytes($block)
                )
            )

            if (-not $seen.ContainsKey($hash)) {
                $seen[$hash] = $true
                if (-not $prevWasSeparator -and $errorBlocks.Count -gt 0) {
                    $errorBlocks.Add("")
                }
                $errorBlocks.Add("══ block $($seen.Count) ══")
                $errorBlocks.Add($block)
                $prevWasSeparator = $false

                if ($seen.Count -ge 50) {
                    $errorBlocks.Add("")
                    $truncMsg = "... (truncated at 50 blocks for token efficiency"
                    if ($RunId) {
                        $truncMsg += " — run `gh run view $RunId --log-failed` for full logs)"
                    } else {
                        $truncMsg += " — use `gh run view --log-failed` for full logs)"
                    }
                    $errorBlocks.Add($truncMsg)
                    break
                }
            }
        }
    }

    # ── Build output ──
    $output = [System.Collections.Generic.List[string]]::new()

    if ($testFailures.Count -gt 0) {
        $output.Add("## Failing Tests")
        $output.Add("")
        foreach ($t in $testFailures) {
            $output.Add("- $t")
        }
        $output.Add("")
    }

    if ($errorBlocks.Count -gt 0) {
        $output.Add("## Error Details")
        $output.Add("")
        $output.AddRange($errorBlocks)
    }

    if ($testFailures.Count -eq 0 -and $errorBlocks.Count -eq 0) {
        $output.Add("(No specific error patterns or test failures matched — last 40 log lines)")
        $tail = $lines | Select-Object -Last 40
        foreach ($t in $tail) { $output.Add([string]$t) }
    }

    return $output -join "`n"
}

<#
.SYNOPSIS
    Create a coworker task file in 0draft/ for a workflow failure.
    Returns the path to the created .md file.
#>
function New-CoworkerFailureTask {
    param(
        [string]$WorkflowName,
        [string]$Tag,
        [string]$RunId,
        [string]$Errors,
        [string[]]$FailingTests,
        [string[]]$FlakyTests,
        [string]$Changelog,
        [string]$RepoRoot,
        [hashtable]$FailedJobs = @{}
    )

    # Write directly to 1ready/ so the task is immediately executable.
    # The old code wrote to 0draft/, which required a manual "coworker assign" step.
    $taskDir = Join-Path $RepoRoot "coworker\tasks\main\1ready"
    if (-not (Test-Path $taskDir)) {
        New-Item -ItemType Directory -Path $taskDir -Force | Out-Null
    }

    $ts = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
    $safeName = "fix-$($WorkflowName -replace '\.yml$','')-failure-$ts"
    $safeName = $safeName -replace '[\\/*?:"<>|]', '_' -replace '\s+', '-'
    $safeName = $safeName -replace '[^A-Za-z0-9._-]', '-' -replace '-+', '-'
    $safeName = $safeName.Trim(' ', '.', '-', '_')

    $taskPath = Join-Path $taskDir "$safeName.md"

    # ── Build failing-test summary ──
    $testSection = ''
    if ($FailingTests -and $FailingTests.Count -gt 0) {
        $testSection = "`n## Failing Tests`n`n"
        foreach ($t in $FailingTests) {
            $flakyMark = if ($FlakyTests -and $t -in $FlakyTests) { ' ⚠️ (also fails in other recent runs — may be flaky)' } else { '' }
            $testSection += "- $t$flakyMark`n"
        }
    }

    # ── Build failed-jobs summary ──
    $jobsSection = ''
    if ($FailedJobs -and $FailedJobs.Count -gt 0) {
        $jobsSection = "`n## Failed Jobs`n`n"
        foreach ($kv in $FailedJobs.GetEnumerator()) {
            $jobsSection += "- **$($kv.Key)** (job ID: $($kv.Value))`n"
        }
    }

    # ── Build changelog section ──
    $changelogSection = ''
    if ($Changelog) {
        $changelogSection = "`n## Release Changelog`n`n``````text`n$Changelog`n``````"
    }

    # ── Build domain-specific investigation hints ──
    $hintSection = ''
    if ($FailingTests -and ($FailingTests -match 'e2e')) {
        $hintSection = @'

## Investigation Hints (E2E test failures)

- E2E tests live in `cli/browser4-cli/tests/e2e/scenarios/`
- Test registration is in `cli/browser4-cli/tests/e2e/scenarios/mod.rs`
- The fixture HTTP server is in the Rust test harness
- These tests run against a real Browser4 backend; backend changes can break them
- Check if fixture server port resolution, HTML fixtures, or state verification changed
- Key files (per CLAUDE.md): `PulsarWebDriver.kt`, `MCPToolController.kt`, `commands.rs`, `main.rs`
- Run locally: `cd cli/browser4-cli && cargo test --test e2e -- --nocapture`
'@
    } elseif ($FailingTests -and (($FailingTests -match 'Snapshot') -or ($FailingTests -match 'Pulsar'))) {
        $hintSection = @'

## Investigation Hints (Kotlin/backend test failures)

- Backend tests: `mvn test -pl browser4-rest -am`
- Browser driver tests: check `browser4-core/browser4-browser/`
- Key files: `PulsarWebDriver.kt`, `MCPToolControllerTest.kt`, `ArgumentNormalizersTest.kt`
- Snapshot tests may need AX accessible name checks — see recent commits for patterns
'@
    }

    # ── Cap error body at 4000 chars ──
    $errorBody = if ($Errors.Length -gt 4000) {
        $Errors.Substring(0, 4000) + "`n`n... (truncated — run `gh run view $RunId --log-failed` for full logs)"
    } else {
        $Errors
    }

    # ── Determine the GitHub repo path for the run URL ──
    $repoSlug = ''
    try {
        $remoteUrl = git -C $RepoRoot remote get-url origin 2>$null
        if ($remoteUrl -match 'github\.com[:/](.+?)(\.git)?$') {
            $repoSlug = $Matches[1].TrimEnd('.git')
        }
    } catch { }

    $runUrl = if ($repoSlug) {
        "https://github.com/$repoSlug/actions/runs/$RunId"
    } else {
        "(run: gh run view $RunId --web)"
    }

    $taskContent = @'
Title: Fix $WorkflowName failure for tag $Tag
Description: The $WorkflowName workflow run $RunId (tag $Tag) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow `$WorkflowName` (run $RunId) for tag `$Tag` failed in CI.

## Context

- **Workflow:** $WorkflowName
- **Tag:** $Tag
- **Run ID:** $RunId
- **Run URL:** $runUrl

$testSection$jobsSection$changelogSection

## Reproduce

```bash
# View all failed logs
gh run view $RunId --log-failed

# View the run in browser
gh run view $RunId --web
```

## Error Diagnostics

$errorBody
$hintSection
## Instructions

1. **Categorize the failure:** Is it a test assertion change, a real regression, or an
   infrastructure/flake issue? Check the release changelog above to see what code changed.
2. **If tests need updating** (assertion format changed, output text changed):
   - Update the test assertions to match the new expected output
   - Check `cli/browser4-cli/tests/e2e/scenarios/` for Rust E2E tests
   - Look at the recent commits for patterns in how test assertions are structured
3. **If it is a real regression:**
   - Identify the root cause from the error diagnostics
   - Trace the code path using the repository structure
   - Apply the minimal fix
4. **If it is a flaky test** (same test fails sporadically across runs):
   - Do NOT delete or skip the test
   - Add retry logic or fix the race condition
   - Check CLAUDE.md "Known CDP pitfalls" for common causes
5. **Verify:** Run the relevant test suite locally or examine the CI output for
   the specific test that failed. Make sure your change would resolve it.
6. **Commit:** Use a conventional-commit message, e.g.:
   ``fix(test): update test assertions for changed CLI output``
'@

    # Expand variables in the single-quoted template
    $taskContent = $ExecutionContext.InvokeCommand.ExpandString($taskContent)

    Set-Content -Path $taskPath -Value $taskContent -Encoding UTF8
    Write-Host "  Coworker task created: $taskPath" -ForegroundColor Green

    return $taskPath
}

<#
.SYNOPSIS
    When a workflow fails, extract errors from failed job logs, print them to
    the console, and — only when -Agent is passed — create a coworker task and
    dispatch it via b4w.ps1 coworker fix.

    By default (no -Agent) the script does NOT call an AI agent: it prints the
    extracted error diagnostics so a human can review them, then returns.
#>
function Invoke-WorkflowFailureHandler {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunId,
        [Parameter(Mandatory = $true)]
        [string]$WorkflowName,
        [Parameter(Mandatory = $true)]
        [string]$Tag,
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,
        [string]$Agent = ""
    )

    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    Write-Host "  Workflow FAILED — extracting error diagnostics" -ForegroundColor Yellow
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow

    # 1. Fetch failed job logs
    Write-Host "`nFetching failed job logs (gh run view $RunId --log-failed) ..." -ForegroundColor DarkGray
    $rawLogs = gh run view $RunId --log-failed 2>&1
    $logExit = $LASTEXITCODE

    if ($logExit -ne 0 -or (-not $rawLogs) -or ($rawLogs -is [string] -and [string]::IsNullOrWhiteSpace($rawLogs))) {
        Write-Host "  Could not fetch failed logs (exit code: $logExit)." -ForegroundColor Yellow
        Write-Host "  Trying job summary fallback..." -ForegroundColor DarkGray
        $rawLogs = gh run view $RunId --json jobs 2>&1
        if (-not $rawLogs) {
            Write-Host "  No log data available." -ForegroundColor Yellow
            $rawLogs = @("(No failed logs available — use `gh run view $RunId --web` to inspect the run)")
        }
    }

    # 2. Extract minimal errors (token-efficient)
    Write-Host "Extracting minimal error messages..." -ForegroundColor DarkGray
    $errors = Extract-MinimalErrors -LogLines $rawLogs
    Write-Host "  Extracted ~$(([regex]::Matches($errors, '══ block')).Count) distinct error block(s)" -ForegroundColor DarkGray

    # 3. Print the diagnostics so a human can review the failure
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    Write-Host "  ERROR DIAGNOSTICS (run $RunId, tag $Tag)" -ForegroundColor Yellow
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    Write-Host $errors
    Write-Host "───────────────────────────────────────────────────────────" -ForegroundColor DarkGray
    Write-Host "  Full logs: gh run view $RunId --log-failed   |   Web: gh run view $RunId --web" -ForegroundColor DarkGray

    # 4. AI agent dispatch — opt-in only (default: no agent)
    if (-not $Agent) {
        Write-Host ""
        Write-Host "  No agent analysis requested (default). Review the error diagnostics above." -ForegroundColor DarkGray
        Write-Host "  To dispatch an AI agent next time, pass -Agent auto (or a backend name):" -ForegroundColor DarkGray
        Write-Host "    .\bin\ci\monitor-ci.ps1 -Agent auto" -ForegroundColor DarkGray
        return
    }

    Write-Host "  Agent dispatch requested (-Agent $Agent) — creating coworker task..." -ForegroundColor Cyan

    # 5. Create coworker task
    $taskPath = New-CoworkerFailureTask -WorkflowName $WorkflowName -Tag $Tag -RunId $RunId -Errors $errors -RepoRoot $RepoRoot

    if (-not $taskPath -or -not (Test-Path $taskPath)) {
        Write-Host "  ERROR: Failed to create coworker task file." -ForegroundColor Red
        return
    }

    # 6. Dispatch to coworker fix
    $b4wScript = Join-Path $RepoRoot "b4w.ps1"
    if (Test-Path $b4wScript) {
        Write-Host "`nDispatching to coworker: b4w.ps1 coworker fix -Path '$taskPath'" -ForegroundColor Cyan
        $pushPrev = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $b4wScript coworker fix -Path $taskPath
        } catch {
            Write-Host "  Warning: coworker dispatch failed: $_" -ForegroundColor Yellow
            Write-Host "  Task file is ready. Run manually:" -ForegroundColor Yellow
            Write-Host "    .\b4w.ps1 coworker fix -Path '$taskPath'" -ForegroundColor Yellow
        }
        $ErrorActionPreference = $pushPrev
    } else {
        Write-Host "  b4w.ps1 not found at $b4wScript" -ForegroundColor Yellow
        Write-Host "  Task created. Run manually:" -ForegroundColor Yellow
        Write-Host "    .\b4w.ps1 coworker fix -Path '$taskPath'" -ForegroundColor Yellow
    }
}

$repoRoot = (git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Error "Not inside a git repository."
    exit 1
}
Set-Location $repoRoot

# ── 1. Trigger CI ──────────────────────────────────────────────────

$triggerScript = Join-Path $repoRoot "bin\ci\trigger-ci.ps1"
if (-not (Test-Path $triggerScript)) {
    Write-Error "trigger-ci.ps1 not found at $triggerScript"
    exit 1
}

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  Step 1/3: Triggering CI via tag push" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

$tagOutput = & $triggerScript -PreReleaseVersion $PreReleaseVersion -remote $remote 2>&1
$exitCode = $LASTEXITCODE

# trigger-ci.ps1 uses Write-Output for the tag, so grab the last line that looks like a tag
$tagLines = $tagOutput | Where-Object { $_ -match '^v\d+\.\d+\.\d+-.*$' }
$tag = $tagLines | Select-Object -Last 1

if ($exitCode -ne 0 -or -not $tag) {
    Write-Error "trigger-ci.ps1 failed (exit code: $exitCode). Output:`n$($tagOutput -join "`n")"
    exit 1
}

Write-Host "Tag pushed: $tag" -ForegroundColor Green

# ── 2. Locate the workflow run ─────────────────────────────────────

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  Step 2/3: Waiting for workflow run to appear" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

# CI workflow filename (the one triggered by ci tags)
$workflowFile = "ci.yml"
$maxWaitSeconds = 120
$elapsed = 0

# --- Helper: resolve a run ID from a tag by polling gh run list ----------
function Find-RunByTag {
    param(
        [string]$Tag,
        [string]$WorkflowFile
    )
    # gh run list returns newest first; filter by the workflow file and the tag
    $runs = gh run list --workflow "$WorkflowFile" --json databaseId,headBranch,status,conclusion,url `
        --limit 5 2>$null `
        | ConvertFrom-Json

    if (-not $runs) { return $null }

    # The tag should appear as the headBranch
    $match = $runs | Where-Object { $_.headBranch -eq $Tag } | Select-Object -First 1
    return $match
}

$run = $null
do {
    $run = Find-RunByTag -Tag $tag -WorkflowFile $workflowFile
    if ($run) { break }

    Write-Host "  Run not yet visible (${elapsed}s elapsed) — retrying in ${PollIntervalSeconds}s ..."
    Start-Sleep -Seconds $PollIntervalSeconds
    $elapsed += $PollIntervalSeconds
} while ($elapsed -lt $maxWaitSeconds)

if (-not $run) {
    Write-Error "Workflow run for tag '$tag' did not appear within ${maxWaitSeconds}s.`n" +
                "Check manually: gh run list --workflow '$workflowFile'"
    exit 1
}

Write-Host "Workflow run found:" -ForegroundColor Green
Write-Host "  ID:     $($run.databaseId)"
Write-Host "  URL:    $($run.url)"
Write-Host "  Status: $($run.status)"

# ── 3. Monitor until completion ────────────────────────────────────

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  Step 3/3: Monitoring workflow" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

if ($NoWatch) {
    # Non-interactive poll loop
    Write-Host "Polling every ${PollIntervalSeconds}s (non-interactive mode) ..."
    $done = $false
    do {
        Start-Sleep -Seconds $PollIntervalSeconds
        $info = gh run view $run.databaseId --json status,conclusion,displayTitle 2>$null | ConvertFrom-Json
        Write-Host "  [$((Get-Date).ToString('HH:mm:ss'))] Status: $($info.status)" +
                   $(if ($info.conclusion) { " | Conclusion: $($info.conclusion)" } else { "" })
        if ($info.status -eq "completed") {
            $done = $true
            $finalConclusion = $info.conclusion
        }
    } while (-not $done)
} else {
    # Interactive: stream logs in real time
    gh run watch $run.databaseId

    # After watch returns, get the final conclusion
    $finalConclusion = gh run view $run.databaseId --jq '.conclusion' 2>$null
    if ($LASTEXITCODE -ne 0) {
        # fallback for older gh without --jq
        $info = gh run view $run.databaseId --json conclusion 2>$null | ConvertFrom-Json
        $finalConclusion = $info.conclusion
    }
}

# ── 4. Report ─────────────────────────────────────────────────────

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor $(if ($finalConclusion -eq "success") { "Green" } else { "Red" })
Write-Host "  CI workflow finished: $finalConclusion" -ForegroundColor $(if ($finalConclusion -eq "success") { "Green" } else { "Red" })
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor $(if ($finalConclusion -eq "success") { "Green" } else { "Red" })

# Show the full run summary (jobs, durations) with a single call
Write-Host "`nJob summary:"
gh run view $run.databaseId --json jobs --jq '.jobs[] | "  \(.name)  →  \(.conclusion)  (\(.startedAt) … \(.completedAt))"' 2>$null
if ($LASTEXITCODE -ne 0) {
    # fallback for older gh without --jq
    gh run view $run.databaseId --json jobs 2>$null
}

# Dispatch coworker task on failure, then exit
if ($finalConclusion -eq "success") {
    exit 0
} else {
    Invoke-WorkflowFailureHandler -RunId $run.databaseId -WorkflowName $workflowFile -Tag $tag -RepoRoot $repoRoot -Agent $Agent
    exit 1
}
