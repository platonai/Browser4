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
    exit 1
}
