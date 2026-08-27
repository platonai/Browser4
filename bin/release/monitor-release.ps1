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
    Triggers a release on GitHub via tag push and monitors the workflow until completion.

.DESCRIPTION
    1. Calls trigger-release.ps1 to create and push a release tag (interactive — you will
       be prompted for confirmations, just as with trigger-release.ps1 directly).
    2. Captures the tag name and locates the triggered Release workflow run.
    3. Streams the workflow logs in real time.
    4. Reports the final conclusion (success/failure) and exits with the same code.
    5. On SUCCESS (unless -SkipVersionBump): automatically bumps the version to the
       next patch (X.Y.Z-SNAPSHOT -> X.Y.(Z+1)-SNAPSHOT) across VERSION, all pom.xml,
       cli/package.json, Cargo.toml and Cargo.lock, then commits and pushes the bump
       as "Auto-bump version to X.Y.(Z+1)-SNAPSHOT" — so the next release starts
       from the next patch. On FAILURE: dispatches a coworker fix task.

    By default this script runs in DRY RUN mode: it calls trigger-release.ps1 in
    dry-run mode (preview only, no tag pushed, no workflow triggered) and exits
    without monitoring. Pass -Apply to actually trigger and monitor the release.

    Requires: gh CLI authenticated with the repo, and pwsh (PowerShell Core).

.PARAMETER remote
    The git remote to push the tag to (default: "origin").
    Passed through to trigger-release.ps1.

.PARAMETER message
    Release message for an annotated tag.  Passed through to trigger-release.ps1.
    If omitted, trigger-release.ps1 will prompt for one.

.PARAMETER PollIntervalSeconds
    How many seconds to wait between polls while the workflow is queued (default: 5).

.PARAMETER NoWatch
    Skip interactive `gh run watch` and poll with `gh run list` / `gh run view` instead.
    Useful on CI or non-interactive terminals.

.PARAMETER Apply
    Actually trigger the release and monitor the workflow. Without this flag the
    script runs in dry-run mode (default) and only previews what would happen.

.PARAMETER DryRun
    Explicit dry-run mode. This is the default; the flag exists for clarity.

.PARAMETER Agent
    Passed through to trigger-release.ps1 to generate AI release notes: auto
    (resolve the backend) or a specific backend name (claude, kimi, codex, dsh,
    copilot). Without it, the raw commit list is used.

.PARAMETER SkipVersionBump
    Skip the automatic post-release version bump. By default, after a successful
    release the script bumps the version — an rc release advances to the next
    rc candidate (rc.N -> rc.N+1), a patch release to the next patch SNAPSHOT
    (X.Y.Z-SNAPSHOT -> X.Y.(Z+1)-SNAPSHOT) — across VERSION, all pom.xml,
    Cargo.toml, Cargo.lock and package.json, then commits and pushes the bump
    as "Auto-bump version to …". The bump rules are computed by
    `node bin/version.mjs next`. Pass this switch to leave the version untouched.

.PARAMETER NoSyncMain
    By default the release trigger fast-forwards origin/main to the current
    branch before tagging (release.yml requires the tag on the latest main).
    Pass this switch to disable the automatic sync; the trigger script then
    warns and asks for confirmation instead.

.PARAMETER MaxMonitorMinutes
    Upper bound (in minutes) for the -NoWatch monitor loop. Default 0 = no limit.
    Use this to guarantee the script eventually exits if a workflow run gets
    stuck (e.g. a job queued forever). Ignored in interactive (-NoWatch off) mode.

.EXAMPLE
    .\bin\release\monitor-release.ps1                              # dry run (preview)

    .\bin\release\monitor-release.ps1 -Apply                       # trigger + monitor

    .\bin\release\monitor-release.ps1 -Apply -message "Hotfix for login crash"

    .\bin\release\monitor-release.ps1 -Apply -NoWatch -PollIntervalSeconds 10

    .\bin\release\monitor-release.ps1 -Apply -SkipVersionBump      # release, no auto-bump

    .\bin\release\monitor-release.ps1 -Apply -NoWatch -MaxMonitorMinutes 90
#>

param(
    [string]$remote = "origin",
    [string]$message = "",
    [int]$PollIntervalSeconds = 5,
    [switch]$NoWatch,
    [switch]$Apply,
    [switch]$DryRun,
    [switch]$SkipVersionBump,
    [switch]$NoSyncMain,
    [int]$MaxMonitorMinutes = 0,
    [ValidateSet('auto', 'claude', 'kimi', 'codex', 'dsh', 'copilot')]
    [string]$Agent = ""
)

$ErrorActionPreference = "Stop"

# Dry run is the default. -Apply opts into real execution; -DryRun is an
# explicit (redundant) confirmation of the default.
$isDryRun = $DryRun -or -not $Apply

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
    Extract deduplicated failing-test names from log lines.

.DESCRIPTION
    Recognizes the common failure formats emitted by test runners in CI logs:
      - Rust:      "test test_e2e_foo ... FAILED"
      - Kotlin:    "Tests failed: 3, passed: 100" (no name, skipped)
      - Go:        "--- FAIL: TestName"
      - e2e:       "test_e2e_foo => FAILED"
    Returns a string[] (possibly empty) of unique test names, in first-seen order.
#>
function Get-FailingTestNames {
    param([object]$LogLines)

    $names = [System.Collections.Generic.List[string]]::new()
    $seen = @{}

    foreach ($ln in (ConvertTo-LogLines -RawLogs $LogLines)) {
        if ($ln -match 'test\s+(\S+)\s+\.\.\.\s+FAILED') {
            $tn = $Matches[1]
            if (-not $seen.ContainsKey($tn)) { $seen[$tn] = $true; $names.Add($tn) }
        }
        if ($ln -match '(test_e2e_\S+)\s.*=>\s*FAILED') {
            $tn = $Matches[1]
            if (-not $seen.ContainsKey($tn)) { $seen[$tn] = $true; $names.Add($tn) }
        }
        if ($ln -match '---\s+FAIL:\s+(\S+)') {
            $tn = $Matches[1]
            if (-not $seen.ContainsKey($tn)) { $seen[$tn] = $true; $names.Add($tn) }
        }
    }

    return ,$names.ToArray()
}

<#
.SYNOPSIS
    Extract structured, deduplicated error diagnostics from log output.
    Produces a report with:
      1. "Failing Tests" — specific test names, deduplicated
      2. "Error Details" — contextual error blocks with GH Actions structure parsed

    Token-efficient: caps blocks at 50 and deduplicates with SHA-256 hashing.
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

    # Create once, reuse for every block (was being recreated per match).
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
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

                # Render block: for GH-format lines, strip the timestamp and show [Job/Step] prefix.
                # ANSI escapes must be stripped here too — matching already runs on cleaned
                # messages, so the rendered diagnostics stay clean (raw ESC sequences would
                # otherwise leak into the output and coworker task files).
                $blockLines = foreach ($j in $start..$end) {
                    $ln = $lines[$j]
                    $p = Parse-GitHubLogLine -Line $ln
                    if ($p -and $p.Message -and $p.Message.Trim().Length -gt 0) {
                        "[$($p.Job) / $($p.Step)] $($p.Message -replace '\x1b\[[0-9;]*m', '')"
                    } else {
                        $ln -replace '\x1b\[[0-9;]*m', ''
                    }
                }
                $block = ($blockLines -join "`n").Trim()
                if ($block.Length -lt 5) { continue }

                $hash = [System.BitConverter]::ToString(
                    $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($block))
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
    } finally {
        $sha256.Dispose()
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
        foreach ($t in $tail) {
            $cleanLine = ([string]$t) -replace '\x1b\[[0-9;]*m', ''
            $output.Add($cleanLine)
        }
    }

    return $output -join "`n"
}

<#
.SYNOPSIS
    Fetch structured job objects for a workflow run.
    Returns an array of job objects (with name, databaseId, conclusion), or $null.
#>
function Get-WorkflowJobs {
    param([string]$RunId)

    try {
        $json = gh run view $RunId --json jobs 2>&1
        if ($LASTEXITCODE -ne 0) { return $null }
        $data = $json | ConvertFrom-Json
        return $data.jobs
    } catch {
        return $null
    }
}

<#
.SYNOPSIS
    Fetch the log for a single job by database ID.
#>
function Get-JobLogs {
    param([string]$RunId, [string]$JobId)

    try {
        $logs = gh run view $RunId --job $JobId --log 2>&1
        if ($LASTEXITCODE -ne 0) { return $null }
        return $logs
    } catch {
        return $null
    }
}

<#
.SYNOPSIS
    Cross-reference failing test names against recent workflow failures
    to flag potential flaky tests. Returns an array of test names that
    also appear in other recent failure logs.
#>
function Find-PotentialFlakyTests {
    param(
        [string[]]$FailingTests,
        [string]$WorkflowName,
        [int]$LookbackRuns = 5
    )

    if ($FailingTests.Count -eq 0) { return @() }

    $flaky = [System.Collections.Generic.List[string]]::new()
    try {
        $recentRuns = gh run list --workflow "$WorkflowName" --limit ($LookbackRuns + 1) `
            --json databaseId,conclusion 2>$null | ConvertFrom-Json

        if (-not $recentRuns) { return @() }

        foreach ($run in $recentRuns) {
            if ($run.conclusion -ne 'failure') { continue }
            $recentLogs = gh run view $run.databaseId --log-failed 2>&1
            if (-not $recentLogs) { continue }
            $recentText = if ($recentLogs -is [array]) { $recentLogs -join "`n" } else { [string]$recentLogs }

            foreach ($test in $FailingTests) {
                if ($recentText -match [regex]::Escape($test)) {
                    if ($test -notin $flaky) { $flaky.Add($test) }
                }
            }
        }
    } catch {
        # Best-effort; flaky detection is non-critical
    }

    return $flaky.ToArray()
}

<#
.SYNOPSIS
    Retrieve the commit range summary for a release tag.
    Falls back to merge-base if no previous tag exists.
    Returns a string or empty string on failure.
#>
function Get-ReleaseChangelog {
    param([string]$Tag, [string]$RepoRoot)

    try {
        Push-Location $RepoRoot

        # Find the previous tag (the one before $Tag, sorted by version)
        $prevTag = (git tag --sort=-v:refname 2>$null | Select-Object -Skip 1 | Select-Object -First 1)
        if (-not $prevTag) {
            $prevTag = (git merge-base origin/main HEAD 2>$null)
        }

        if ($prevTag) {
            $log = git log --oneline "$prevTag..$Tag" -- . 2>&1
            if ($log -and $LASTEXITCODE -eq 0) {
                Pop-Location
                return "Commits since ${prevTag}:`n$log"
            }
        }

        Pop-Location
    } catch {
        # Best-effort
    }

    return ''
}

<#
.SYNOPSIS
    Create a coworker task file in 1ready/ for a workflow failure.
    The task is designed to be immediately actionable by the AI coworker:
    it includes failing test names, error diagnostics, flaky-test flags,
    release changelog, and domain-specific investigation hints.

    Writing to 1ready/ (not 0draft/) means the task is queued for
    execution — no manual "assign" step needed.
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
    # NOTE: forward slashes — Join-Path with backslashes produces a literal
    # backslash path on Linux/macOS (this script must stay cross-platform).
    $taskDir = Join-Path $RepoRoot "coworker/tasks/main/1ready"
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

    # ── Cap error body at 4000 chars (UTF-16 code units) ──
    # Substring(0,4000) can split a surrogate pair mid-way, which then fails to
    # encode as valid UTF-8. Trim one code unit when the cut lands on a high
    # surrogate so the written file stays valid.
    $errorBody = if ($Errors.Length -gt 4000) {
        $cut = $Errors.Substring(0, 4000)
        if ($cut.Length -gt 0 -and [char]::IsHighSurrogate($cut[$cut.Length - 1])) {
            $cut = $cut.Substring(0, $cut.Length - 1)
        }
        $cut + "`n`n... (truncated — run `gh run view $RunId --log-failed` for full logs)"
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
    When a workflow fails, extract errors from failed job logs, create a
    coworker task in 1ready/, and dispatch it via b4w.ps1 coworker fix.

    Pipeline (7 stages):
    1. Fetch structured job info to identify exactly which jobs failed.
    2. Collect per-job logs (more granular than --log-failed) or fall back
       to --log-failed if per-job fetch isn't available.
    3. Parse logs to extract specific failing test names and error blocks.
    4. Cross-reference with recent runs to flag potential flaky tests.
    5. Collect release changelog (commits since previous tag).
    6. Build a comprehensive task with investigation hints.
    7. Dispatch it via coworker fix.
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
        [string]$RepoRoot
    )

    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    Write-Host "  Workflow FAILED — diagnosing for coworker" -ForegroundColor Yellow
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow

    # ── 1. Fetch structured job info ──────────────────────────────────────
    Write-Host "`n[1/7] Fetching job structure (gh run view $RunId --json jobs) ..." -ForegroundColor DarkGray
    $jobs = Get-WorkflowJobs -RunId $RunId
    $failedJobs = @{}
    if ($jobs) {
        foreach ($j in $jobs) {
            if ($j.conclusion -eq 'failure' -or $j.conclusion -eq 'cancelled') {
                $failedJobs[$j.name] = $j.databaseId
            }
        }
        Write-Host "  Found $($failedJobs.Count) failed job(s):" -ForegroundColor DarkGray
        foreach ($kv in $failedJobs.GetEnumerator()) {
            Write-Host "    - $($kv.Key) (job ID: $($kv.Value))" -ForegroundColor DarkGray
        }
    } else {
        Write-Host "  Could not parse job structure; will fall back to --log-failed." -ForegroundColor Yellow
    }

    # ── 2. Collect all log output ────────────────────────────────────────
    Write-Host "`n[2/7] Collecting failed-job logs..." -ForegroundColor DarkGray
    $allLogs = [System.Collections.Generic.List[string]]::new()

    if ($failedJobs.Count -gt 0) {
        # Per-job logs: scoped to one job, avoids interleaving noise
        foreach ($kv in $failedJobs.GetEnumerator()) {
            $jobLogs = Get-JobLogs -RunId $RunId -JobId $kv.Value
            if ($jobLogs) {
                $allLogs.Add("=== Job: $($kv.Key) (ID: $($kv.Value)) ===")
                $allLogs.AddRange((ConvertTo-LogLines -RawLogs $jobLogs))
                Write-Host "  Collected logs for: $($kv.Key)" -ForegroundColor DarkGray
            } else {
                Write-Host "  Warning: Could not fetch logs for job '$($kv.Key)'" -ForegroundColor Yellow
            }
        }
    }

    if ($allLogs.Count -eq 0) {
        # Fall back to --log-failed (all failed jobs combined)
        Write-Host "  Falling back to gh run view $RunId --log-failed ..." -ForegroundColor DarkGray
        $rawLogs = gh run view $RunId --log-failed 2>&1
        $logExit = $LASTEXITCODE

        if ($logExit -ne 0 -or (-not $rawLogs) -or ($rawLogs -is [string] -and [string]::IsNullOrWhiteSpace($rawLogs))) {
            Write-Host "  Could not fetch failed logs (exit code: $logExit)." -ForegroundColor Yellow
            Write-Host "  Coworker task will contain metadata only." -ForegroundColor Yellow
            $allLogs.Add("(No failed logs available — use `gh run view $RunId --web` to inspect the run)")
        } else {
            $allLogs.AddRange((ConvertTo-LogLines -RawLogs $rawLogs))
        }
    }

    Write-Host "  Total log lines collected: $($allLogs.Count)" -ForegroundColor DarkGray

    # ── 3. Extract structured error diagnostics ──────────────────────────
    Write-Host "`n[3/7] Extracting error diagnostics..." -ForegroundColor DarkGray
    $errors = Extract-MinimalErrors -LogLines $allLogs -RunId $RunId

    # Re-extract failing test names from the line collection (needed as an
    # array for the flaky-check; Extract-MinimalErrors already dedupes).
    $failingTests = [System.Collections.Generic.List[string]]::new()
    foreach ($tn in (Get-FailingTestNames -LogLines $allLogs)) {
        $failingTests.Add($tn)
    }
    $testCount = $failingTests.Count
    $blockCount = ([regex]::Matches($errors, '══ block')).Count
    Write-Host "  Found $testCount failing test(s), $blockCount distinct error block(s)" -ForegroundColor DarkGray

    # ── 4. Check for flaky tests ────────────────────────────────────────
    Write-Host "`n[4/7] Checking for potential flaky tests (cross-reference with recent runs) ..." -ForegroundColor DarkGray
    $flakyTests = @()
    if ($failingTests.Count -gt 0) {
        $flakyTests = Find-PotentialFlakyTests -FailingTests $failingTests.ToArray() -WorkflowName $WorkflowName
        if ($flakyTests.Count -gt 0) {
            Write-Host "  ⚠️  $($flakyTests.Count) test(s) also failed in other recent runs (possible flakes):" -ForegroundColor Yellow
            foreach ($ft in $flakyTests) {
                Write-Host "    - $ft" -ForegroundColor Yellow
            }
        } else {
            Write-Host "  No flaky-test pattern detected (failures appear to be new)." -ForegroundColor DarkGray
        }
    } else {
        Write-Host "  No test names to check." -ForegroundColor DarkGray
    }

    # ── 5. Collect release changelog ────────────────────────────────────
    Write-Host "`n[5/7] Collecting release changelog ..." -ForegroundColor DarkGray
    $changelog = Get-ReleaseChangelog -Tag $Tag -RepoRoot $RepoRoot
    if ($changelog) {
        $commitCount = ([regex]::Matches($changelog, "`n")).Count
        Write-Host "  Changelog collected ($commitCount commits)." -ForegroundColor DarkGray
    } else {
        Write-Host "  No changelog available (could not determine previous tag)." -ForegroundColor DarkGray
    }

    # ── 6. Create comprehensive coworker task ────────────────────────────
    Write-Host "`n[6/7] Creating coworker task..." -ForegroundColor Cyan
    $taskPath = New-CoworkerFailureTask `
        -WorkflowName $WorkflowName `
        -Tag $Tag `
        -RunId $RunId `
        -Errors $errors `
        -FailingTests $failingTests.ToArray() `
        -FlakyTests $flakyTests `
        -Changelog $changelog `
        -RepoRoot $RepoRoot `
        -FailedJobs $failedJobs

    if (-not $taskPath -or -not (Test-Path $taskPath)) {
        Write-Host "  ERROR: Failed to create coworker task file." -ForegroundColor Red
        return
    }

    # ── 7. Dispatch to coworker fix ──────────────────────────────────────
    $b4wScript = Join-Path $RepoRoot "b4w.ps1"
    if (Test-Path $b4wScript) {
        Write-Host "`n[7/7] Dispatching to coworker: b4w.ps1 coworker fix -Path '$taskPath'" -ForegroundColor Cyan
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

<#
.SYNOPSIS
    After a successful release, bump the current version's patch and sync all
    version files, then commit and push the bump.

.DESCRIPTION
    Reads VERSION (expected "X.Y.Z-SNAPSHOT" or "X.Y.Z"), increments the patch
    (X.Y.Z -> X.Y.(Z+1)), writes the new "-SNAPSHOT" value back to VERSION and
    every version-bearing file (VERSION, all pom.xml, cli/package.json,
    cli/browser4-cli/Cargo.toml, cli/browser4-cli/Cargo.lock), then commits as
    "Auto-bump version to X.Y.(Z+1)-SNAPSHOT" and pushes.

    Implemented in pure PowerShell (no node/mvn dependency) so it also works in
    restricted sandboxes where `node bin/version.mjs auto` cannot spawn cmd.exe.
    Mirrors what `node bin/version.mjs auto --commit` would do, minus the Maven
    versions:set call (the pom.xml files are updated with direct text replacement
    of the exact version strings).

    File set is discovered dynamically (all tracked pom.xml + the fixed CLI
    files), so newly added modules are covered automatically.

.NOTES
    Skips gracefully (warning, exit code unaffected) when:
      - VERSION is missing or not parseable as X.Y.Z(-SNAPSHOT)
      - the working tree has uncommitted changes other than the bump itself
        (to avoid sweeping unrelated changes into the bump commit)
#>
function Invoke-PostReleaseVersionBump {
    param(
        [string]$RepoRoot,
        [string]$Remote,
        [switch]$SkipPush
    )

    $versionFile = Join-Path $RepoRoot "VERSION"
    if (-not (Test-Path $versionFile)) {
        Write-Host "  [bump] VERSION not found at $versionFile — skipping version bump." -ForegroundColor Yellow
        return
    }

    $current = (Get-Content $versionFile -Raw).Trim()
    if ($current -notmatch '^(\d+)\.(\d+)\.(\d+)(-(SNAPSHOT|rc\.\d+))?$') {
        Write-Host "  [bump] VERSION '$current' is not X.Y.Z, X.Y.Z-SNAPSHOT or X.Y.Z-rc.N — skipping version bump." -ForegroundColor Yellow
        return
    }

    # ── Compute the next version ──────────────────────────────────────────
    # The bump rules live in version.mjs (`next`), the single source of
    # truth: an rc release advances rc.N -> rc.N+1, a patch/SNAPSHOT release
    # advances to the next patch SNAPSHOT. Fall back to the built-in rules
    # (mirroring `version.mjs next`) only when node is unavailable.
    $bumpKind = if ($current -match '-rc\.\d+$') { 'rc' } else { 'patch' }
    $nextVer = $null
    try {
        $nextVer = (node (Join-Path $RepoRoot 'bin' 'version.mjs') next $bumpKind 2>$null | Select-Object -Last 1)
        if ($nextVer) { $nextVer = $nextVer.Trim() }
    } catch { $nextVer = $null }
    if ([string]::IsNullOrWhiteSpace($nextVer)) {
        if ($bumpKind -eq 'rc' -and $current -match '^(?<base>\d+\.\d+\.\d+)-rc\.(?<n>\d+)$') {
            $nextVer = "$($Matches['base'])-rc.$([int]$Matches['n'] + 1)"
        } elseif ($current -match '^(?<maj>\d+)\.(?<min>\d+)\.(?<pat>\d+)') {
            $nextVer = "$($Matches['maj']).$($Matches['min']).$([int]$Matches['pat'] + 1)-SNAPSHOT"
        }
    }
    if ([string]::IsNullOrWhiteSpace($nextVer)) {
        Write-Host "  [bump] Could not compute the next version from '$current' — skipping version bump." -ForegroundColor Yellow
        return
    }

    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host "  Post-release version bump: $current -> $nextVer" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

    # ── Guard: working tree must be clean (except files we are about to touch) ──
    $dirty = git -C $RepoRoot status --porcelain 2>$null
    if ($dirty) {
        Write-Host "  [bump] Working tree has uncommitted changes — skipping version bump to avoid sweeping them into the bump commit:" -ForegroundColor Yellow
        $dirty | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
        return
    }

    # ── Build the file set: VERSION + all tracked pom.xml + CLI version files ──
    $targets = [System.Collections.Generic.List[string]]::new()
    $targets.Add("VERSION")

    $tracked = git -C $RepoRoot ls-files 2>$null
    foreach ($line in $tracked) {
        $f = $line.Trim()
        if (-not $f) { continue }
        if ($f -match '(^|/)pom\.xml$') { $targets.Add($f); continue }
        if ($f -in @('cli/package.json', 'cli/browser4-cli/Cargo.toml', 'cli/browser4-cli/Cargo.lock')) {
            $targets.Add($f)
        }
    }

    # ── Apply replacements ────────────────────────────────────────────────
    # Version-bearing strings differ per bump shape:
    #   SNAPSHOT:  pom <version>X.Y.Z-SNAPSHOT</version>, <tag>vX.Y.Z</tag>,     CLI "X.Y.Z"
    #   rc:        pom <version>X.Y.Z-rc.N</version>,   <tag>vX.Y.Z-rc.N</tag>, CLI "X.Y.Z-rc.N"
    $oldCli = $current -replace '-SNAPSHOT$', ''
    $newCli = $nextVer -replace '-SNAPSHOT$', ''
    $oldTag = 'v' + $oldCli
    $newTag = 'v' + $newCli
    $changed = [System.Collections.Generic.List[string]]::new()

    foreach ($rel in $targets) {
        $full = Join-Path $RepoRoot $rel
        if (-not (Test-Path $full)) { continue }

        $content = Get-Content -Path $full -Raw
        if ($null -eq $content) { continue }

        $updated = $content
        if ($rel -eq 'VERSION') {
            $updated = $nextVer + "`n"
        } elseif ($rel -match 'pom\.xml$') {
            # Replace the tag first ("vX.Y.Z…") so the version replacement
            # cannot corrupt it ("X.Y.Z" is a substring of "vX.Y.Z").
            if ($oldTag -ne $newTag) { $updated = $updated.Replace($oldTag, $newTag) }
            if ($current -ne $nextVer) { $updated = $updated.Replace($current, $nextVer) }
        } else {
            # Cargo.toml / Cargo.lock / package.json carry the bare "X.Y.Z" (or "X.Y.Z-rc.N")
            if ($oldCli -ne $newCli) { $updated = $updated.Replace('"' + $oldCli + '"', '"' + $newCli + '"') }
        }

        if ($updated -ne $content) {
            try {
                [System.IO.File]::WriteAllText($full, $updated)
                $changed.Add($rel)
                Write-Host "  [bump] updated: $rel" -ForegroundColor DarkGray
            } catch {
                Write-Host "  [bump] WARN: could not write $rel : $($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
    }

    if ($changed.Count -eq 0) {
        Write-Host "  [bump] No version files needed updating (already at $nextVer?)." -ForegroundColor Green
        return
    }

    # ── Commit & push ─────────────────────────────────────────────────────
    $commitMsg = "Auto-bump version to $nextVer"
    Push-Location $RepoRoot
    try {
        git add -- $changed
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  [bump] WARN: git add failed — leaving changes uncommitted." -ForegroundColor Yellow
            return
        }
        git commit -m $commitMsg
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  [bump] WARN: git commit failed — leaving changes staged." -ForegroundColor Yellow
            return
        }
        Write-Host "  [bump] Committed: $commitMsg" -ForegroundColor Green

        if (-not $SkipPush) {
            git push $Remote HEAD
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  [bump] Pushed to $Remote" -ForegroundColor Green
            } else {
                Write-Host "  [bump] WARN: git push failed (exit $LASTEXITCODE) — commit is local only." -ForegroundColor Yellow
            }
        } else {
            Write-Host "  [bump] -SkipPush set — commit left local." -ForegroundColor DarkGray
        }
    } finally {
        Pop-Location
    }
}

$repoRoot = (git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Host "ERROR: Not inside a git repository." -ForegroundColor Red
    exit 1
}
Set-Location $repoRoot

if ($isDryRun) {
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Yellow
    Write-Host "  DRY RUN MODE — no tag will be pushed, no workflow triggered." -ForegroundColor Yellow
    Write-Host "  Re-run with -Apply to actually trigger and monitor the release." -ForegroundColor Yellow
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Yellow
}

# ── Preflight: gh must be installed and authenticated ────────────────
# Fail fast with a clear message instead of a cryptic error deep inside
# trigger-release.ps1 / the monitor loop.
$ghAuth = gh auth status 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: gh CLI is not installed or not authenticated." -ForegroundColor Red
    $ghAuth | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host "  Run 'gh auth login' first, then retry." -ForegroundColor Yellow
    exit 1
}

# ── 1. Trigger release ─────────────────────────────────────────────

$triggerScript = Join-Path $repoRoot "bin/release/trigger-release.ps1"
if (-not (Test-Path $triggerScript)) {
    Write-Host "ERROR: trigger-release.ps1 not found at $triggerScript" -ForegroundColor Red
    exit 1
}

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  Step 1/3: Triggering release via tag push" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

# Build args for trigger-release.ps1 — passthrough of remote, message,
# dry-run state, and the -Agent flag.
$triggerArgs = @{}
if ($remote)      { $triggerArgs['remote'] = $remote }
if ($message)     { $triggerArgs['message'] = $message }
if (-not $isDryRun) { $triggerArgs['Apply'] = $true }
if ($Agent)       { $triggerArgs['Agent'] = $Agent }
# Sync main to the current branch by default so releasing from a dev branch
# is a single command (trigger-release.ps1 fast-forwards main when possible).
if (-not $NoSyncMain) { $triggerArgs['SyncMain'] = $true }

# Capture all output streams so we can extract the tag
$tagOutput = & $triggerScript @triggerArgs 2>&1
$exitCode = $LASTEXITCODE

# trigger-release.ps1 uses Write-Output for the tag on its last line.
# Tags look like: v4.12.0, v4.12.0-rc.1, v4.12.0-alpha.1, etc.
$tagLines = $tagOutput | Where-Object { $_ -match '^v\d+\.\d+\.\d+(-(rc|alpha|beta|dry_run)\.\d+)?$' }
$tag = $tagLines | Select-Object -Last 1

if ($exitCode -ne 0 -or -not $tag) {
    Write-Host "ERROR: trigger-release.ps1 failed (exit code: $exitCode). Output:" -ForegroundColor Red
    $tagOutput | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}

if ($isDryRun) {
    Write-Host ""
    Write-Host "DRY RUN — no workflow was triggered. Nothing to monitor." -ForegroundColor Yellow
    Write-Host "Re-run with -Apply to actually trigger and monitor the release workflow." -ForegroundColor Yellow
    exit 0
}

Write-Host "Tag pushed: $tag" -ForegroundColor Green

# ── 2. Locate the workflow run ─────────────────────────────────────

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  Step 2/3: Waiting for workflow run to appear" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

$workflowFile = "release.yml"
$maxWaitSeconds = 120
$elapsed = 0

# --- Helper: resolve a run ID from a tag by polling gh run list ----------
function Find-RunByTag {
    param(
        [string]$Tag,
        [string]$WorkflowFile
    )
    $runs = gh run list --workflow "$WorkflowFile" --json databaseId,headBranch,status,conclusion,url `
        --limit 30 2>$null `
        | ConvertFrom-Json

    if (-not $runs) { return $null }

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
    Write-Host "ERROR: Workflow run for tag '$tag' did not appear within ${maxWaitSeconds}s." -ForegroundColor Red
    Write-Host "  Check manually: gh run list --workflow '$workflowFile'" -ForegroundColor Red
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
    Write-Host "Polling every ${PollIntervalSeconds}s (non-interactive mode) ..." $(if ($MaxMonitorMinutes -gt 0) { "(max ${MaxMonitorMinutes} min)" } else { "" })
    $done = $false
    $nullInfoStreak = 0
    $monitorStarted = Get-Date
    do {
        Start-Sleep -Seconds $PollIntervalSeconds

        if ($MaxMonitorMinutes -gt 0) {
            $elapsedMin = ((Get-Date) - $monitorStarted).TotalMinutes
            if ($elapsedMin -ge $MaxMonitorMinutes) {
                Write-Host "  ERROR: monitor exceeded -MaxMonitorMinutes ($MaxMonitorMinutes min) — aborting." -ForegroundColor Red
                exit 1
            }
        }

        $info = $null
        try {
            $info = gh run view $run.databaseId --json status,conclusion,displayTitle 2>$null | ConvertFrom-Json
        } catch {
            $info = $null
        }

        # gh returning nothing (rate limit / transient failure): retry a few
        # times before giving up, instead of throwing on $info.status.
        if ($null -eq $info) {
            $nullInfoStreak++
            if ($nullInfoStreak -ge 10) {
                Write-Host "  ERROR: gh run view returned no data 10 times in a row — aborting." -ForegroundColor Red
                exit 1
            }
            Write-Host "  [$((Get-Date).ToString('HH:mm:ss'))] gh run view returned no data (attempt $nullInfoStreak/10) — retrying ..." -ForegroundColor Yellow
            continue
        }
        $nullInfoStreak = 0

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
    $info = gh run view $run.databaseId --json status,conclusion,displayTitle 2>$null | ConvertFrom-Json
    $finalConclusion = $info.conclusion
}

# ── 4. Report ─────────────────────────────────────────────────────

$color = if ($finalConclusion -eq "success") { "Green" } else { "Red" }
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor $color
Write-Host "  Release workflow finished: $finalConclusion" -ForegroundColor $color
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor $color

# Show the full run summary (jobs, durations)
Write-Host "`nJob summary:"
gh run view $run.databaseId --json jobs --jq '.jobs[] | "  \(.name)  →  \(.conclusion)  (\(.startedAt) … \(.completedAt))"' 2>$null
if ($LASTEXITCODE -ne 0) {
    # fallback for older gh without --jq
    gh run view $run.databaseId --json jobs 2>$null
}

if ($finalConclusion -eq "success") {
    if (-not $SkipVersionBump) {
        # Post-release: bump to next patch so the next release starts fresh.
        Invoke-PostReleaseVersionBump -RepoRoot $repoRoot -Remote $remote
    } else {
        Write-Host "Version bump skipped (-SkipVersionBump)." -ForegroundColor DarkGray
    }
    exit 0
} else {
    # Extract errors from failed logs and dispatch a coworker task to fix them
    Invoke-WorkflowFailureHandler -RunId $run.databaseId -WorkflowName $workflowFile -Tag $tag -RepoRoot $repoRoot
    exit 1
}
