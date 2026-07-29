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

.EXAMPLE
    .\bin\release\monitor-release.ps1

    .\bin\release\monitor-release.ps1 -message "Hotfix for login crash"

    .\bin\release\monitor-release.ps1 -NoWatch -PollIntervalSeconds 10
#>

param(
    [string]$remote = "origin",
    [string]$message = "",
    [int]$PollIntervalSeconds = 5,
    [switch]$NoWatch
)

$ErrorActionPreference = "Stop"

# ═══════════════════════════════════════════════════════════════════════════
# Shared helpers: workflow-failure → coworker task dispatch
# ═══════════════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Extract minimal, deduplicated error messages from log output.
    Token-efficient: keeps only lines near error indicators, deduplicates
    near-duplicate blocks, and caps at a reasonable size.
#>
function Extract-MinimalErrors {
    param([string[]]$LogLines)

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
        'error[E', 'error: [E',          # Rust compiler errors
        'Aborting due to',
        'Could not compile',
        'Failed to compile',
        'Tests failed',
        'test failed',
        'test result: FAILED'
    )

    $seen = @{}
    $errors = [System.Collections.Generic.List[string]]::new()
    $prevWasSeparator = $false

    for ($i = 0; $i -lt $LogLines.Count; $i++) {
        $line = $LogLines[$i]
        $matched = $false
        foreach ($pat in $errorPatterns) {
            if ($line -match [regex]::Escape($pat)) {
                $matched = $true
                break
            }
        }

        if ($matched) {
            # Capture context: up to 2 lines before, the match, up to 2 lines after
            $ctxBefore = 2
            $ctxAfter  = 2
            $start = [Math]::Max(0, $i - $ctxBefore)
            $end   = [Math]::Min($LogLines.Count - 1, $i + $ctxAfter)

            $block = ($LogLines[$start..$end] -join "`n").Trim()

            # Deduplicate using a simple hash of the block
            $hash = [System.BitConverter]::ToString(
                [System.Security.Cryptography.SHA256]::Create().ComputeHash(
                    [System.Text.Encoding]::UTF8.GetBytes($block)
                )
            )

            if (-not $seen.ContainsKey($hash)) {
                $seen[$hash] = $true
                if (-not $prevWasSeparator -and $errors.Count -gt 0) {
                    $errors.Add("")
                }
                $errors.Add("══ block $($errors.Count / 2 + 1) ══")
                $errors.Add($block)
                $prevWasSeparator = $false

                # Token safety: ~50 blocks max (≈200-300 lines)
                if ($seen.Count -ge 50) {
                    $errors.Add("")
                    $errors.Add("... (truncated at 50 blocks for token efficiency — run " +
                                "`gh run view $RunId --log-failed` for full logs)")
                    break
                }
            }
        }
    }

    if ($errors.Count -eq 0) {
        # No patterns matched — return last 40 lines as fallback
        $tail = @($LogLines | Select-Object -Last 40)
        $errors.Add("(No specific error patterns matched — last 40 log lines)")
        $errors.AddRange($tail)
    }

    return $errors -join "`n"
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
        [string]$RepoRoot
    )

    $taskDir = Join-Path $RepoRoot "coworker\tasks\main\0draft"
    if (-not (Test-Path $taskDir)) {
        New-Item -ItemType Directory -Path $taskDir -Force | Out-Null
    }

    $ts = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
    $safeName = "fix-$($WorkflowName -replace '\.yml$','')-failure-$ts"
    $safeName = $safeName -replace '[\\/*?:"<>|]', '_' -replace '\s+', '-'
    $safeName = $safeName -replace '[^A-Za-z0-9._-]', '-' -replace '-+', '-'
    $safeName = $safeName.Trim(' ', '.', '-', '_')

    $taskPath = Join-Path $taskDir "$safeName.md"

    # Cap error body at 4000 chars for token efficiency
    $errorBody = if ($Errors.Length -gt 4000) {
        $Errors.Substring(0, 4000) + "`n`n... (truncated — full logs: gh run view $RunId --log-failed)"
    } else {
        $Errors
    }

    $taskContent = @"
Title: Fix $WorkflowName failure for tag $Tag
Description: The $WorkflowName run $RunId (tag $Tag) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The $WorkflowName run $RunId for tag $Tag failed.

## Reproduce
```bash
gh run view $RunId --log-failed
gh run view $RunId --web
```

## Errors

$errorBody

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
"@

    Set-Content -Path $taskPath -Value $taskContent -Encoding UTF8
    Write-Host "  Coworker task created: $taskPath" -ForegroundColor Green

    return $taskPath
}

<#
.SYNOPSIS
    When a workflow fails, extract errors from failed job logs, create a
    coworker task, and dispatch it via b4w.ps1 coworker fix.
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
    Write-Host "  Workflow FAILED — extracting errors for coworker" -ForegroundColor Yellow
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
            Write-Host "  No log data available. Coworker task will contain the workflow metadata only." -ForegroundColor Yellow
            $rawLogs = @("(No failed logs available — use `gh run view $RunId --web` to inspect the run)")
        }
    }

    # 2. Extract minimal errors (token-efficient)
    Write-Host "Extracting minimal error messages..." -ForegroundColor DarkGray
    $errors = Extract-MinimalErrors -LogLines $rawLogs
    Write-Host "  Extracted ~$(([regex]::Matches($errors, '══ block')).Count) distinct error block(s)" -ForegroundColor DarkGray

    # 3. Create coworker task
    $taskPath = New-CoworkerFailureTask -WorkflowName $WorkflowName -Tag $Tag -RunId $RunId -Errors $errors -RepoRoot $RepoRoot

    if (-not $taskPath -or -not (Test-Path $taskPath)) {
        Write-Host "  ERROR: Failed to create coworker task file." -ForegroundColor Red
        return
    }

    # 4. Dispatch to coworker fix
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

# ── 1. Trigger release ─────────────────────────────────────────────

$triggerScript = Join-Path $repoRoot "bin\release\trigger-release.ps1"
if (-not (Test-Path $triggerScript)) {
    Write-Error "trigger-release.ps1 not found at $triggerScript"
    exit 1
}

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  Step 1/3: Triggering release via tag push" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

# Build args for trigger-release.ps1 — passthrough of remote and message
$triggerArgs = @{}
if ($remote)      { $triggerArgs['remote'] = $remote }
if ($message)     { $triggerArgs['message'] = $message }

# Capture all output streams so we can extract the tag
$tagOutput = & $triggerScript @triggerArgs 2>&1
$exitCode = $LASTEXITCODE

# trigger-release.ps1 uses Write-Output for the tag on its last line.
# Tags look like: v4.12.0, v4.12.0-rc.1, v4.12.0-alpha.1, etc.
$tagLines = $tagOutput | Where-Object { $_ -match '^v\d+\.\d+\.\d+(-(rc|alpha|beta|dry_run)\.\d+)?$' }
$tag = $tagLines | Select-Object -Last 1

if ($exitCode -ne 0 -or -not $tag) {
    Write-Error "trigger-release.ps1 failed (exit code: $exitCode). Output:`n$($tagOutput -join "`n")"
    exit 1
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
        --limit 5 2>$null `
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
    exit 0
} else {
    # Extract errors from failed logs and dispatch a coworker task to fix them
    Invoke-WorkflowFailureHandler -RunId $run.databaseId -WorkflowName $workflowFile -Tag $tag -RepoRoot $repoRoot
    exit 1
}
