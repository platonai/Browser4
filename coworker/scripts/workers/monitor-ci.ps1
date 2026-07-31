#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Trigger and monitor a GitHub Actions CI workflow run.

.DESCRIPTION
    1. Triggers the specified workflow via gh workflow run.
    2. Polls for the newly created workflow run ID.
    3. Streams run progress with gh run watch.
    4. Reports the final conclusion and exits with the appropriate code.
    5. On success, resets the CI merge counter so merge-prs.ps1 starts a
       fresh accumulation window.

.PARAMETER Workflow
    Workflow file name or ID to trigger. Default: ci.yml.

.PARAMETER Ref
    Branch or tag to run the workflow against. Default: current branch.

.PARAMETER TimeoutMinutes
    Maximum time to wait for the workflow to complete. Default: 60.

.PARAMETER PollIntervalSeconds
    How often to poll for the run ID after triggering. Default: 5.
#>

param(
    [string]$Workflow = 'ci.yml',
    [string]$Ref = '',
    [int]$TimeoutMinutes = 60,
    [int]$PollIntervalSeconds = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Load shared utilities ──────────────────────────────────────────────────
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $scriptsRoot 'config.ps1'
if (Test-Path $configPath) { . $configPath }

# ── Resolve repository root ────────────────────────────────────────────────
$repoRoot = Get-TargetRepositoryRoot
if (-not $repoRoot) { $repoRoot = Get-WorkspaceRoot }
if (-not (Test-Path $repoRoot)) {
    Write-Host "ERROR: Repository root not found."
    exit 1
}

Push-Location $repoRoot
try {
    # ── Verify prerequisites ───────────────────────────────────────────────
    $ghCmd = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $ghCmd) {
        Write-Host "ERROR: gh CLI is not installed or not on PATH."
        Pop-Location
        exit 1
    }

    # ── Resolve ref ────────────────────────────────────────────────────────
    if (-not $Ref) {
        $Ref = & git rev-parse --abbrev-ref HEAD 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "ERROR: Could not determine current branch."
            Pop-Location
            exit 1
        }
    }
    Write-Host "CI Workflow: $Workflow" -ForegroundColor Cyan
    Write-Host "Ref:         $Ref" -ForegroundColor Cyan

    # Ensure the ref exists on the remote
    & git fetch origin $Ref 2>&1 | Out-Null

    # ── Trigger the workflow ───────────────────────────────────────────────
    Write-Host "`n── Triggering workflow: gh workflow run $Workflow --ref $Ref ──" -ForegroundColor Yellow
    $triggerOutput = & gh workflow run $Workflow --ref $Ref 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to trigger workflow: $triggerOutput" -ForegroundColor Red
        Pop-Location
        exit 1
    }
    Write-Host "Workflow triggered successfully." -ForegroundColor Green

    # ── Find the new run ───────────────────────────────────────────────────
    # The triggered run may take a moment to appear. Poll until we find it.
    Write-Host "`n── Waiting for workflow run to appear... ──" -ForegroundColor Yellow
    $runId = $null
    $pollDeadline = (Get-Date).AddMinutes(2)
    $knownRunIds = @{}

    # Seed: capture existing runs so we can detect the new one
    $existingRuns = & gh run list --workflow=$Workflow --limit=10 --json databaseId 2>&1
    if ($LASTEXITCODE -eq 0) {
        $existing = @($existingRuns | ConvertFrom-Json)
        foreach ($r in $existing) { $knownRunIds[$r.databaseId] = $true }
    }

    while (-not $runId -and (Get-Date) -lt $pollDeadline) {
        Start-Sleep -Seconds $PollIntervalSeconds
        $runsJson = & gh run list --workflow=$Workflow --limit=10 --json databaseId,status,createdAt 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  Waiting for run list..." -ForegroundColor DarkGray
            continue
        }
        $runs = @($runsJson | ConvertFrom-Json)
        foreach ($r in $runs) {
            if (-not $knownRunIds.ContainsKey($r.databaseId)) {
                $runId = $r.databaseId
                Write-Host "Found new run: $runId (status: $($r.status))" -ForegroundColor Green
                break
            }
        }
    }

    if (-not $runId) {
        Write-Host "ERROR: Could not find the triggered workflow run within 2 minutes." -ForegroundColor Red
        Pop-Location
        exit 1
    }

    # ── Monitor the run ────────────────────────────────────────────────────
    Write-Host "`n── Monitoring run $runId (timeout: ${TimeoutMinutes}m)... ──" -ForegroundColor Cyan
    Write-Host "  URL: $(& gh run view $runId --json url --jq '.url' 2>&1)" -ForegroundColor DarkGray

    # gh run watch blocks until the run completes, showing live progress.
    # We don't rely on its exit code — the conclusion check below is authoritative.
    $watchOutput = & gh run watch $runId 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "WARN: gh run watch exited with code $LASTEXITCODE. Checking conclusion..." -ForegroundColor Yellow
    }

    # ── Get the final conclusion ───────────────────────────────────────────
    $conclusion = ''
    $runView = & gh run view $runId --json conclusion,status,displayTitle,headBranch 2>&1
    if ($LASTEXITCODE -eq 0) {
        $runInfo = $runView | ConvertFrom-Json
        $conclusion = $runInfo.conclusion
        Write-Host "`n========== CI Run Summary ==========" -ForegroundColor Cyan
        Write-Host "  Workflow:   $($runInfo.displayTitle)" -ForegroundColor White
        Write-Host "  Branch:     $($runInfo.headBranch)" -ForegroundColor White
        Write-Host "  Status:     $($runInfo.status)" -ForegroundColor White
        Write-Host "  Conclusion: $conclusion" -ForegroundColor ($conclusion -eq 'success' ? 'Green' : 'Red')
    }
    else {
        Write-Host "WARN: Could not retrieve run conclusion." -ForegroundColor Yellow
    }

    # ── Reset CI merge counter on success ──────────────────────────────────
    if ($conclusion -eq 'success') {
        $ciStateDir = Join-Path $repoRoot '.coworker\state'
        $ciStateFile = Join-Path $ciStateDir 'ci-merge-counter.json'
        if (-not (Test-Path $ciStateDir)) {
            New-Item -ItemType Directory -Path $ciStateDir -Force | Out-Null
        }
        $state = @{
            mergedSinceLastCi = 0
            lastCiTriggered   = Get-CoworkerTimestamp
        }
        $state | ConvertTo-Json -Compress | Set-Content -Path $ciStateFile -Encoding UTF8
        Write-Host "CI merge counter reset." -ForegroundColor DarkGray
    }

    # ── Exit with the appropriate code ─────────────────────────────────────
    if ($conclusion -eq 'success') {
        Write-Host "`nCI pipeline SUCCEEDED." -ForegroundColor Green
        Pop-Location
        exit 0
    }
    elseif ($conclusion -eq 'cancelled') {
        Write-Host "`nCI pipeline was CANCELLED." -ForegroundColor Yellow
        Pop-Location
        exit 2
    }
    else {
        Write-Host "`nCI pipeline FAILED (conclusion: $conclusion)." -ForegroundColor Red
        # Show failed jobs if available
        $failedJobs = & gh run view $runId --json jobs --jq '.jobs[] | select(.conclusion == "failure") | "  \(.name)"' 2>&1
        if ($LASTEXITCODE -eq 0 -and $failedJobs) {
            Write-Host "Failed jobs:" -ForegroundColor Red
            Write-Host $failedJobs
        }
        Pop-Location
        exit 1
    }
}
finally {
    Pop-Location
}
