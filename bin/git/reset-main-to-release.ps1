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
    Hard-reset main and master branches to the latest stable release tag,
    or to a user-specified tag or commit.

.DESCRIPTION
    Fetches the latest tags from the remote, identifies the most recent
    stable release tag (vMAJOR.MINOR.PATCH — no -rc, -ci, or other suffix),
    and force-pushes both main and master to that tag.  If a specific ref
    (tag or commit) is passed, both branches are reset to that ref instead.

    By default the script prints what it would do and exits — pass -Force
    to actually perform the reset and push.

.PARAMETER Ref
    A specific git ref (tag or commit SHA) to reset both branches to.
    When omitted, the script auto-selects the latest stable release tag
    (e.g. v4.11.21).

.PARAMETER Remote
    The remote to fetch from and push to (default: origin).

.PARAMETER Branches
    The branches to reset (default: @("main", "master")).  Branches that
    don't exist locally are created from the target ref; branches that
    already exist are hard-reset.

.PARAMETER Force
    Required to actually perform the reset and force-push.  Without this
    flag the script runs in dry-run mode.

.PARAMETER DryRun
    Print the operations that would be performed without executing them.
    This is the default behaviour; -DryRun is implied when -Force is absent.

.EXAMPLE
    reset-main-to-release.ps1
    # Dry-run: prints the latest release tag and what branches would be reset.

.EXAMPLE
    reset-main-to-release.ps1 -Force
    # Resets main and master to the latest stable release tag and pushes.

.EXAMPLE
    reset-main-to-release.ps1 -Ref v4.10.0 -Force
    # Resets both branches to tag v4.10.0.

.EXAMPLE
    reset-main-to-release.ps1 -Ref abc1234def -Force
    # Resets both branches to commit abc1234def.

.EXAMPLE
    reset-main-to-release.ps1 -Branches @("main") -Force
    # Only reset main, skip master.
#>

[CmdletBinding()]
param (
    [string]$Ref,
    [string]$Remote = "origin",
    [string[]]$Branches = @("main", "master"),
    [switch]$Force,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# ── Resolve target ref ────────────────────────────────────────────

if (-not $Ref) {
    Write-Host "Fetching tags from remote '$Remote'..." -ForegroundColor Cyan
    git fetch --tags $Remote 2>&1 | Out-Null

    # Find the latest stable release tag: v<num>.<num>.<num> with no suffix
    $stableTags = git tag --sort=-v:refname `
        | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' }

    if (-not $stableTags) {
        Write-Error "No stable release tags found (pattern: vMAJOR.MINOR.PATCH)."
        exit 1
    }

    $Ref = $stableTags | Select-Object -First 1
    Write-Host "Latest stable release tag: $Ref" -ForegroundColor Green
}
else {
    # Verify the ref exists
    $type = git cat-file -t $Ref 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Ref '$Ref' does not exist in this repository."
        exit 1
    }
    Write-Host "User-specified ref: $Ref (type: $type)" -ForegroundColor Green
}

# ── Dry-run / confirmation gate ───────────────────────────────────

$reallyDoIt = $Force -and -not $DryRun

if (-not $reallyDoIt) {
    Write-Host ""
    Write-Host "══ DRY-RUN ═══════════════════════════════════════════════════" -ForegroundColor Yellow
    Write-Host "  Target ref : $Ref"
    Write-Host "  Remote     : $Remote"
    Write-Host "  Branches   : $($Branches -join ', ')"
    Write-Host ""
    Write-Host "Operations that would be performed:" -ForegroundColor Yellow
    foreach ($branch in $Branches) {
        Write-Host "  1. git branch -f $branch $Ref"
        Write-Host "  2. git push $Remote $branch --force"
    }
    Write-Host "══════════════════════════════════════════════════════════════" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Run with -Force to actually execute these operations." -ForegroundColor Cyan
    exit 0
}

# ── Execute ───────────────────────────────────────────────────────

Write-Host ""
Write-Host "Resetting branches to $Ref ..." -ForegroundColor Cyan

foreach ($branch in $Branches) {
    Write-Host ""

    # Check if branch exists locally
    $branchExists = $false
    git show-ref --verify --quiet "refs/heads/$branch" 2>$null
    if ($LASTEXITCODE -eq 0) {
        $branchExists = $true
    }

    if ($branchExists) {
        Write-Host "  [$branch] Hard-resetting local branch to $Ref..." -ForegroundColor Cyan
        git branch -f $branch $Ref
    }
    else {
        Write-Host "  [$branch] Creating local branch from $Ref..." -ForegroundColor Cyan
        git branch $branch $Ref
    }

    Write-Host "  [$branch] Force-pushing to $Remote/$branch..." -ForegroundColor Cyan
    git push $Remote "$($branch):refs/heads/$branch" --force

    Write-Host "  [$branch] Done." -ForegroundColor Green
}

Write-Host ""
Write-Host "All branches reset to $Ref and pushed to $Remote." -ForegroundColor Green
