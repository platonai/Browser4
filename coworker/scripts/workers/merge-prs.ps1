#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Merge open PRs targeting the current branch created by the current user,
    resolve conflicts via agent, run minimal tests, and queue a coworker task
    on failure. When more than -CiThreshold PRs have accumulated since the
    last CI run, triggers a full CI pipeline via monitor-ci.ps1 instead.

.DESCRIPTION
    1. Lists open PRs targeting the current branch via gh CLI.
    2. Filters to PRs authored by the current GitHub user (use -AllAuthors to
       merge everyone's PRs).
    3. For each PR: attempts direct merge; on conflict, checks out the PR
       branch, merges base, invokes the agent to resolve conflicts, pushes,
       then merges.
    4. Updates a persistent merge counter (tracked across runs).
    5. If the counter exceeds -CiThreshold (default 5), invokes
       monitor-ci.ps1 to trigger and monitor the full CI pipeline. The
       counter resets when CI is triggered.
    6. Otherwise, runs local tests (./bin/test.ps1 fast).
    7. If tests or CI fail, writes a coworker task file into 1ready and
       triggers process-coworker-queue.

.PARAMETER TestType
    Test types to run after merges (default: "fast"). Passed to ./bin/test.ps1.
    Examples: fast, cli, ps, "fast ps", it.

.PARAMETER BaseBranch
    The base branch PRs target. Defaults to the current branch.

.PARAMETER MergeMethod
    gh pr merge strategy: --merge, --squash, or --rebase. Default: --merge.

.PARAMETER SkipTests
    Skip the post-merge test run.

.PARAMETER AllAuthors
    Merge all open PRs regardless of author. Default: only merge PRs created
    by the currently authenticated GitHub user.

.PARAMETER CiThreshold
    Number of PRs merged since last CI run that triggers a full CI pipeline
    via monitor-ci.ps1 instead of local tests. Default: 5.
    The counter is persistent across merge-prs.ps1 runs and resets when CI
    is triggered.
#>

param(
    [string]$TestType = 'fast',
    [string]$BaseBranch = '',
    [ValidateSet('--merge', '--squash', '--rebase')]
    [string]$MergeMethod = '--merge',
    [switch]$SkipTests,
    [switch]$AllAuthors,
    [int]$CiThreshold = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Load shared utilities ──────────────────────────────────────────────────
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $scriptsRoot 'config.ps1'
if (Test-Path $configPath) { . $configPath }

# ── Script-level mutex ─────────────────────────────────────────────────────
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-Host "Another merge-prs.ps1 instance is already running. Exiting."
    exit 0
}

# ── Load agent helper ──────────────────────────────────────────────────────
$agentHelper = Join-Path $PSScriptRoot 'agent.ps1'
if (-not (Test-Path $agentHelper)) {
    Write-Host "ERROR: agent.ps1 not found at $agentHelper"
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}
. $agentHelper

# ── Resolve repo root and base branch ──────────────────────────────────────
$repoRoot = Get-TargetRepositoryRoot
if (-not $repoRoot) { $repoRoot = Get-WorkspaceRoot }
if (-not (Test-Path $repoRoot)) {
    Write-Host "ERROR: Repository root not found."
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}

# ── CI merge-counter state (tracks PRs merged since last CI run) ──────────
$ciStateDir = Join-Path $repoRoot '.coworker\state'
$ciStateFile = Join-Path $ciStateDir 'ci-merge-counter.json'

function Get-CiMergeState {
    if (-not (Test-Path $ciStateFile)) {
        return @{ mergedSinceLastCi = 0; lastCiTriggered = $null }
    }
    try {
        $state = Get-Content -Path $ciStateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        return @{
            mergedSinceLastCi = [int]$state.mergedSinceLastCi
            lastCiTriggered   = $state.lastCiTriggered
        }
    }
    catch {
        Write-Host "WARN: Could not read CI merge state, resetting to 0." -ForegroundColor DarkGray
        return @{ mergedSinceLastCi = 0; lastCiTriggered = $null }
    }
}

function Set-CiMergeState {
    param([int]$Count, [string]$LastTriggered)
    if (-not (Test-Path $ciStateDir)) {
        New-Item -ItemType Directory -Path $ciStateDir -Force | Out-Null
    }
    $state = @{
        mergedSinceLastCi = $Count
        lastCiTriggered   = $LastTriggered
    }
    $state | ConvertTo-Json -Compress | Set-Content -Path $ciStateFile -Encoding UTF8
}

function Reset-CiMergeCounter {
    $now = Get-CoworkerTimestamp
    Set-CiMergeState -Count 0 -LastTriggered $now
    Write-Host "CI merge counter reset. Last CI triggered: $now" -ForegroundColor DarkGray
}

Push-Location $repoRoot
try {
    if (-not $BaseBranch) {
        $BaseBranch = & git rev-parse --abbrev-ref HEAD 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "ERROR: Could not determine current branch."
            Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
            Pop-Location
            exit 1
        }
    }
    Write-Host "Base branch: $BaseBranch" -ForegroundColor Cyan

    # ── Verify prerequisites ───────────────────────────────────────────────
    $ghCmd = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $ghCmd) {
        Write-Host "ERROR: gh CLI is not installed or not on PATH."
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 1
    }

    # Ensure we're on the base branch and up to date
    & git checkout $BaseBranch 2>&1 | Out-Null
    & git pull origin $BaseBranch 2>&1 | Out-Null

    # ── Resolve current GitHub user ────────────────────────────────────────
    $currentUser = ''
    if (-not $AllAuthors) {
        $currentUser = & gh api user --jq '.login' 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "WARN: Could not determine current GitHub user. Falling back to all authors." -ForegroundColor Yellow
            $currentUser = ''
        }
        else {
            Write-Host "Filtering to PRs authored by: $currentUser" -ForegroundColor DarkGray
        }
    }

    # ── Discover open PRs ──────────────────────────────────────────────────
    $prsJson = & gh pr list --base $BaseBranch --state open --json number,title,headRefName,mergeable,author 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: gh pr list failed: $prsJson"
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 1
    }

    $allPrs = @($prsJson | ConvertFrom-Json)
    if ($allPrs.Count -eq 0) {
        Write-Host "No open PRs targeting '$BaseBranch'." -ForegroundColor Green
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 0
    }

    # ── Filter by author ───────────────────────────────────────────────────
    $foreignPrs = @()
    if ($currentUser) {
        $prs = @($allPrs | Where-Object { $_.author.login -eq $currentUser })
        $foreignPrs = @($allPrs | Where-Object { $_.author.login -ne $currentUser })
    }
    else {
        $prs = $allPrs
    }

    if ($prs.Count -eq 0) {
        Write-Host "No PRs by $currentUser targeting '$BaseBranch'." -ForegroundColor Green
        if ($foreignPrs.Count -gt 0) {
            Write-Host "  ($($foreignPrs.Count) PR(s) by other authors skipped. Use -AllAuthors to merge them.)" -ForegroundColor DarkGray
        }
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 0
    }

    Write-Host "Found $($prs.Count) PR(s) by $currentUser targeting '$BaseBranch':" -ForegroundColor Cyan
    foreach ($pr in $prs) {
        $mergeableIcon = if ($pr.mergeable -eq 'MERGEABLE') { '+' } else { '!' }
        Write-Host "  [$mergeableIcon] #$($pr.number) $($pr.title)  <-- $($pr.headRefName)" -ForegroundColor White
    }
    if ($foreignPrs.Count -gt 0) {
        Write-Host "  ($($foreignPrs.Count) PR(s) by other authors skipped. Use -AllAuthors to merge them.)" -ForegroundColor DarkGray
    }

    # ── Merge each PR ──────────────────────────────────────────────────────
    $merged = @()
    $conflictResolved = @()
    $skipped = @()

    foreach ($pr in $prs) {
        $prNum = $pr.number
        $prTitle = $pr.title
        $prBranch = $pr.headRefName

        Write-Host "`n── PR #${prNum}: $prTitle ──" -ForegroundColor Yellow

        # Try direct merge first
        $mergeOutput = & gh pr merge $prNum $MergeMethod --delete-branch 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  Merged directly." -ForegroundColor Green
            $merged += $prNum
            & git pull origin $BaseBranch 2>&1 | Out-Null
            continue
        }

        # Merge failed — likely conflicts. Check out PR branch and resolve.
        if ($mergeOutput -match 'conflict|not mergeable|mergeable') {
            Write-Host "  Direct merge blocked (conflicts or not mergeable). Resolving..." -ForegroundColor Yellow

            # Fetch and checkout PR branch
            & git fetch origin $prBranch 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  WARN: Could not fetch $prBranch. Skipping." -ForegroundColor Yellow
                $skipped += $prNum
                continue
            }

            & git checkout $prBranch 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  WARN: Could not checkout $prBranch. Skipping." -ForegroundColor Yellow
                $skipped += $prNum
                continue
            }

            # Merge base branch into PR branch to surface conflicts
            $mergeBaseOutput = & git merge "origin/$BaseBranch" 2>&1
            if ($LASTEXITCODE -ne 0) {
                # Conflicts exist — invoke agent to resolve
                $conflictFiles = & git diff --name-only --diff-filter=U 2>&1
                Write-Host "  Conflicts in: $($conflictFiles -join ', ')" -ForegroundColor Magenta

                $conflictPrompt = @"
Resolve merge conflicts in the following files. The branch `$prBranch` is being
merged with `origin/$BaseBranch`. For each conflicted file, read it, resolve
ALL conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`), keeping the correct
combined logic from both sides. Then stage the resolved files with `git add`.

Conflicted files:
$($conflictFiles -join "`n")

After resolving all conflicts, commit the merge. Do NOT push — the script handles that.
"@
                Write-Host "  Invoking agent to resolve conflicts..." -ForegroundColor Cyan
                try {
                    $resolveOutput = Invoke-Agent -Prompt $conflictPrompt -WorkingDirectory $repoRoot -CaptureOutput -TimeoutSeconds 300
                    Write-Host "  Agent output: $($resolveOutput -replace '\n',' ')" -ForegroundColor DarkGray
                }
                catch {
                    Write-Host "  Agent resolution failed: $_" -ForegroundColor Red
                    & git merge --abort 2>&1 | Out-Null
                    & git checkout $BaseBranch 2>&1 | Out-Null
                    $skipped += $prNum
                    continue
                }

                # Verify conflicts are resolved
                $remainingConflicts = & git diff --name-only --diff-filter=U 2>&1
                if ($remainingConflicts) {
                    Write-Host "  WARN: Conflicts remain in: $($remainingConflicts -join ', ')" -ForegroundColor Yellow
                    & git merge --abort 2>&1 | Out-Null
                    & git checkout $BaseBranch 2>&1 | Out-Null
                    $skipped += $prNum
                    continue
                }

                # Stage and commit if agent didn't
                & git add -A 2>&1 | Out-Null
                $isClean = & git diff --cached --quiet 2>&1
                if ($LASTEXITCODE -ne 0) {
                    & git commit -m "fix: resolve merge conflicts with $BaseBranch`n`nCo-Authored-By: Builtin Coworker" 2>&1 | Out-Null
                }
            }

            # Push resolution
            & git push origin $prBranch 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  WARN: Push failed for $prBranch. Skipping merge." -ForegroundColor Yellow
                & git checkout $BaseBranch 2>&1 | Out-Null
                $skipped += $prNum
                continue
            }

            # Now merge the PR
            & git checkout $BaseBranch 2>&1 | Out-Null
            & git pull origin $BaseBranch 2>&1 | Out-Null
            $mergeOutput = & gh pr merge $prNum $MergeMethod --delete-branch 2>&1
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  Merged after conflict resolution." -ForegroundColor Green
                $conflictResolved += $prNum
                & git pull origin $BaseBranch 2>&1 | Out-Null
            }
            else {
                Write-Host "  Merge still failed: $mergeOutput" -ForegroundColor Red
                $skipped += $prNum
            }
        }
        else {
            Write-Host "  Merge failed (unknown reason): $mergeOutput" -ForegroundColor Red
            $skipped += $prNum
        }
    }

    # ── Summary ────────────────────────────────────────────────────────────
    Write-Host "`n========== Merge Summary ==========" -ForegroundColor Cyan
    Write-Host "  Merged directly:      $($merged.Count) $($merged -join ', ')" -ForegroundColor Green
    Write-Host "  Merged after resolve: $($conflictResolved.Count) $($conflictResolved -join ', ')" -ForegroundColor Green
    Write-Host "  Skipped:              $($skipped.Count) $($skipped -join ', ')" -ForegroundColor Yellow

    # ── Update CI merge counter ────────────────────────────────────────────
    $totalMergedThisRun = $merged.Count + $conflictResolved.Count
    $ciState = Get-CiMergeState
    $mergedSinceLastCi = $ciState.mergedSinceLastCi + $totalMergedThisRun
    Set-CiMergeState -Count $mergedSinceLastCi -LastTriggered $ciState.lastCiTriggered

    Write-Host "PRs merged since last CI: $mergedSinceLastCi (threshold: $CiThreshold)" -ForegroundColor DarkGray

    # ── Skip all tests ─────────────────────────────────────────────────────
    if ($SkipTests) {
        Write-Host "`nTests skipped (-SkipTests)." -ForegroundColor DarkGray
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 0
    }

    # ── Threshold exceeded: trigger full CI pipeline ───────────────────────
    if ($mergedSinceLastCi -gt $CiThreshold) {
        Write-Host "`n── $mergedSinceLastCi PRs merged since last CI (> $CiThreshold). Triggering CI pipeline... ──" -ForegroundColor Cyan

        $monitorCiScript = Join-Path $PSScriptRoot 'monitor-ci.ps1'
        if (-not (Test-Path $monitorCiScript)) {
            Write-Host "WARN: monitor-ci.ps1 not found at $monitorCiScript. Falling back to local tests." -ForegroundColor Yellow
            # Fall through to local tests below (counter is NOT reset)
        }
        else {
            # Reset counter before invoking CI so we don't re-trigger on failure
            Reset-CiMergeCounter

            $ciArgs = @('-Ref', $BaseBranch)
            $ciOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $monitorCiScript @ciArgs 2>&1
            $ciExitCode = $LASTEXITCODE

            # Always show CI output
            Write-Host ($ciOutput -join "`n")

            if ($ciExitCode -eq 0) {
                Write-Host "`nCI pipeline passed." -ForegroundColor Green
                Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
                Pop-Location
                exit 0
            }

            # CI failed — create coworker task
            Write-Host "`nCI pipeline FAILED (exit $ciExitCode). Creating coworker task..." -ForegroundColor Red

            $tasksRoot = Join-Path $repoRoot 'coworker\tasks\main'
            $readyDir = Join-Path $tasksRoot '1ready'
            if (-not (Test-Path $readyDir)) {
                New-Item -ItemType Directory -Path $readyDir -Force | Out-Null
            }

            $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $taskFileName = "fix-ci-after-pr-merge-$timestamp.md"
            $taskFilePath = Join-Path $readyDir $taskFileName

            $mergedSummary = if ($merged.Count -gt 0) { "Merged: #$($merged -join ', #')" } else { "No direct merges" }
            $resolvedSummary = if ($conflictResolved.Count -gt 0) { "Resolved: #$($conflictResolved -join ', #')" } else { "No conflict resolutions" }

            $taskContent = @"
Title: Fix CI failures after PR merge into $BaseBranch
Description: CI pipeline failed after merging $totalMergedThisRun PR(s) into $BaseBranch. $mergedSummary. $resolvedSummary.
Prompt: |
  The following PRs were just merged into `$BaseBranch`:
  - Direct merges: $($merged -join ', ' -replace '^$','none')
  - Conflict-resolved merges: $($conflictResolved -join ', ' -replace '^$','none')

  CI pipeline failed with exit code $ciExitCode. Investigate the CI failures
  and fix them. Read the CI output above, identify the root cause(s), and
  apply fixes.

  CI workflow: ci.yml (ref: $BaseBranch)

  #auto-approve
"@

            Set-Content -Path $taskFilePath -Value $taskContent -Encoding UTF8
            Write-Host "  Task written: $taskFilePath" -ForegroundColor Cyan

            # Trigger coworker to process the task
            $queueScript = Join-Path $scriptsRoot 'process-coworker-queue.ps1'
            if (Test-Path $queueScript) {
                Write-Host "  Triggering coworker queue processor..." -ForegroundColor Cyan
                & pwsh -NoProfile -ExecutionPolicy Bypass -File $queueScript -Once 2>&1 | Out-Null
            }
            else {
                Write-Host "  WARN: process-coworker-queue.ps1 not found. Task awaits scheduler." -ForegroundColor Yellow
            }

            Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
            Pop-Location
            exit 1
        }
    }

    # ── Within threshold: run local tests ──────────────────────────────────
    Write-Host "`n── Running local tests: ./bin/test.ps1 $TestType ──" -ForegroundColor Cyan
    $testScript = Join-Path $repoRoot 'bin\test.ps1'
    if (-not (Test-Path $testScript)) {
        Write-Host "WARN: test.ps1 not found at $testScript. Skipping tests." -ForegroundColor Yellow
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 0
    }

    $testArgs = @('-NoSession')
    foreach ($t in ($TestType -split '\s+')) {
        if ($t) { $testArgs += $t }
    }

    $testOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $testScript @testArgs 2>&1
    $testExitCode = $LASTEXITCODE

    # Always show test output
    Write-Host ($testOutput -join "`n")

    if ($testExitCode -eq 0) {
        Write-Host "`nTests passed." -ForegroundColor Green
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 0
    }

    # ── Tests failed — create coworker task ────────────────────────────────
    Write-Host "`nTests FAILED (exit $testExitCode). Creating coworker task..." -ForegroundColor Red

    $tasksRoot = Join-Path $repoRoot 'coworker\tasks\main'
    $readyDir = Join-Path $tasksRoot '1ready'
    if (-not (Test-Path $readyDir)) {
        New-Item -ItemType Directory -Path $readyDir -Force | Out-Null
    }

    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $taskFileName = "fix-tests-after-pr-merge-$timestamp.md"
    $taskFilePath = Join-Path $readyDir $taskFileName

    # Build a concise but informative task
    $mergedSummary = if ($merged.Count -gt 0) { "Merged: #$($merged -join ', #')" } else { "No direct merges" }
    $resolvedSummary = if ($conflictResolved.Count -gt 0) { "Resolved: #$($conflictResolved -join ', #')" } else { "No conflict resolutions" }

    $taskContent = @"
Title: Fix failing tests after PR merge into $BaseBranch
Description: Tests failed after merging PRs into $BaseBranch. $mergedSummary. $resolvedSummary.
Prompt: |
  The following PRs were just merged into `$BaseBranch`:
  - Direct merges: $($merged -join ', ' -replace '^$','none')
  - Conflict-resolved merges: $($conflictResolved -join ', ' -replace '^$','none')

  Tests failed with exit code $testExitCode when running `./bin/test.ps1 $TestType`.
  Investigate the test failures and fix them. Read the test output below,
  identify the root cause(s), and apply fixes. Run the tests again to verify.

  Test command: ./bin/test.ps1 $TestType
  Test output (last lines):
  $($testOutput[-30..-1] -join "`n")

  #auto-approve
"@

    Set-Content -Path $taskFilePath -Value $taskContent -Encoding UTF8
    Write-Host "  Task written: $taskFilePath" -ForegroundColor Cyan

    # ── Trigger coworker to process the task ───────────────────────────────
    $queueScript = Join-Path $scriptsRoot 'process-coworker-queue.ps1'
    if (Test-Path $queueScript) {
        Write-Host "  Triggering coworker queue processor..." -ForegroundColor Cyan
        & pwsh -NoProfile -ExecutionPolicy Bypass -File $queueScript -Once 2>&1 | Out-Null
    }
    else {
        Write-Host "  WARN: process-coworker-queue.ps1 not found. Task awaits scheduler." -ForegroundColor Yellow
    }

    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    Pop-Location
    exit 1
}
finally {
    # Ensure we always return to base branch
    & git checkout $BaseBranch 2>&1 | Out-Null
    Pop-Location
}
