#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Merge open PRs targeting the current branch created by the current user,
    resolve conflicts via agent, run minimal tests, and queue a coworker task
    on failure.

.DESCRIPTION
    1. Lists open PRs targeting the current branch via gh CLI.
    2. Filters to PRs authored by the current GitHub user (use -AllAuthors to
       merge everyone's PRs).
    3. For each PR: attempts direct merge; on conflict, checks out the PR
       branch, merges base, invokes the agent to resolve conflicts, pushes,
       then merges.
    4. Runs minimal tests (./bin/test.ps1 fast).
    5. If tests fail, writes a coworker task file into 1ready and triggers
       process-coworker-queue.

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
#>

param(
    [string]$TestType = 'fast',
    [string]$BaseBranch = '',
    [ValidateSet('--merge', '--squash', '--rebase')]
    [string]$MergeMethod = '--merge',
    [switch]$SkipTests,
    [switch]$AllAuthors
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

        Write-Host "`n── PR #$prNum: $prTitle ──" -ForegroundColor Yellow

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

    # ── Run tests ──────────────────────────────────────────────────────────
    if ($SkipTests) {
        Write-Host "`nTests skipped (-SkipTests)." -ForegroundColor DarkGray
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        Pop-Location
        exit 0
    }

    Write-Host "`n── Running minimal tests: ./bin/test.ps1 $TestType ──" -ForegroundColor Cyan
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
