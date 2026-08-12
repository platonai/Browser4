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
    Creates and pushes a pre-release CI tag. Branch-aware: on version branches
    (e.g. 4.12.x), tags are scoped to that branch's major.minor so CI runs are
    isolated per release line.

.DESCRIPTION
    1. Verifies the local VERSION equals the latest GitHub release patch + 1,
       aborting with a confirmation prompt if it does not.
    2. Detects the current git branch.
    3. If the branch matches X.Y.x (e.g. 4.12.x), uses X.Y as the version prefix
       and the patch from the current VERSION file, then searches for existing
       tags like vX.Y.<patch>-ci.* — scoped to that branch.
    4. Increments only the pre-release counter (ci.N); the patch tracks the
       current VERSION file: v4.12.5-ci.3 → v4.12.5-ci.4 (same patch), or
       v4.12.5-ci.3 → v4.12.6-ci.1 after a version bump.
    5. On non-version branches (main, develop, feature/*), falls back to the
       VERSION file for the base version and bumps the pre-release counter as
       before.

.PARAMETER PreReleaseVersion
    The pre-release label to use (default: "ci").

.PARAMETER remote
    The git remote to push the tag to (default: "origin").

.EXAMPLE
    # On branch 4.12.x with existing tag v4.12.5-ci.3 → creates v4.12.5-ci.4
    .\bin\ci\trigger-ci.ps1

.EXAMPLE
    # On main branch → uses VERSION file for base version
    .\bin\ci\trigger-ci.ps1 -PreReleaseVersion rc
#>

param(
    [string]$PreReleaseVersion = "ci",
    [string]$remote = "origin"
)

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

# ═══════════════════════════════════════════════════════════════════
# 0. Verify local version is the latest GitHub release patch + 1
# ═══════════════════════════════════════════════════════════════════

# Resolve the latest stable GitHub release tag (gh CLI first, then git tags).
$latestRelease = $null
try {
    $ghTags = @(gh release list --limit 100 --json tagName 2>$null | ConvertFrom-Json | ForEach-Object { $_.tagName })
    $latestRelease = $ghTags | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | Select-Object -First 1
} catch { }

if (-not $latestRelease) {
    $latestRelease = @(git tag --sort=-v:refname 2>$null |
        Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } |
        Select-Object -First 1) -join ''
}

$localSnapshot = Get-Content "$repoRoot\VERSION" -TotalCount 1
$localVersion = ($localSnapshot -replace '-SNAPSHOT', '').Trim()

if ($latestRelease -and $latestRelease -match '^v?(\d+)\.(\d+)\.(\d+)$') {
    $expectedVersion = "$($matches[1]).$($matches[2]).$([int]$matches[3] + 1)"
    if ($localVersion -ne $expectedVersion) {
        Write-Warning "Local VERSION '$localVersion' is not latest release '$latestRelease' patch + 1 (expected '$expectedVersion')."
        try { $answer = Read-Host "Continue anyway? [y/N]" } catch { $answer = '' }
        if ($answer -notmatch '^[Yy]$') {
            Write-Error "Aborted: local VERSION '$localVersion' does not equal latest release '$latestRelease' patch + 1 ('$expectedVersion')."
            exit 1
        }
        Write-Host "Continuing despite version mismatch (user confirmed)."
    } else {
        Write-Host "Local VERSION '$localVersion' matches latest release '$latestRelease' patch + 1."
    }
} else {
    Write-Warning "Could not determine the latest stable release (got '$latestRelease'); skipping the patch+1 check."
}

# ═══════════════════════════════════════════════════════════════════
# 1. Determine version prefix from current branch
# ═══════════════════════════════════════════════════════════════════

$branch = git rev-parse --abbrev-ref HEAD 2>$null
$branchBased = $false

if ($branch -match '^(\d+)\.(\d+)\.x$') {
    # Version branch like "4.12.x" → prefix is "4.12"
    $majorMinor = "$($matches[1]).$($matches[2])"
    $branchBased = $true
    Write-Host "Branch '$branch' -> version prefix: $majorMinor (branch-based, ci counter will bump)"
} else {
    # Non-version branch → fall back to VERSION file
    $SNAPSHOT_VERSION = Get-Content "$repoRoot\VERSION" -TotalCount 1
    $version = $SNAPSHOT_VERSION -replace "-SNAPSHOT", ""
    $parts = $version -split "\."
    $majorMinor = $parts[0] + "." + $parts[1]
    Write-Host "Branch '$branch' (non-version) -> VERSION file prefix: $majorMinor"
}

# ═══════════════════════════════════════════════════════════════════
# 2. Find existing CI tags for this version prefix
# ═══════════════════════════════════════════════════════════════════

$escapedPrefix = [regex]::Escape($majorMinor)
$tagPattern = "^v${escapedPrefix}\.\d+-$([regex]::Escape($PreReleaseVersion))\.\d+$"
$tags = @(git tag --list | Where-Object { $_ -match $tagPattern })

if ($tags.Count -eq 0) {
    # No existing CI tags for this prefix — seed from VERSION file or start at 0
    $SNAPSHOT_VERSION = Get-Content "$repoRoot\VERSION" -TotalCount 1
    $fileVersion = $SNAPSHOT_VERSION -replace "-SNAPSHOT", ""
    $fileParts = $fileVersion -split "\."
    $fileMajorMinor = $fileParts[0] + "." + $fileParts[1]
    $initialPatch = if ($fileMajorMinor -eq $majorMinor) { [int]$fileParts[2] } else { 0 }
    $newTag = "v$majorMinor.$initialPatch-$PreReleaseVersion.1"
    Write-Host "No existing tags for v$majorMinor.*-$PreReleaseVersion.*. Creating new tag: $newTag"
    git tag $newTag
    git push $remote $newTag
    Write-Host "Created new tag '$newTag' and pushed it to remote '$remote'."
    Write-Output $newTag
    exit 0
}

# ═══════════════════════════════════════════════════════════════════
# 3. Determine next tag
# ═══════════════════════════════════════════════════════════════════

if ($branchBased) {
    # ── Branch-based: base the tag on the CURRENT version's patch (read from
    #    the VERSION file) and bump only the pre-release counter (ci.N). This
    #    keeps the tag tracking version bumps (e.g. VERSION 4.13.3-SNAPSHOT →
    #    v4.13.3-ci.1) instead of freezing at the patch that was current when
    #    the first CI tag was seeded (the old behavior pinned every tag to
    #    v4.13.0-ci.N regardless of later version bumps). ──
    $SNAPSHOT_VERSION = Get-Content "$repoRoot\VERSION" -TotalCount 1
    $currentVersion = $SNAPSHOT_VERSION -replace "-SNAPSHOT", ""
    $currentParts = $currentVersion -split "\."
    $currentPatch = if ($currentParts.Count -ge 3) { [int]$currentParts[2] } else { 0 }

    $escapedCurrent = [regex]::Escape("v$majorMinor.$currentPatch")
    $exactPattern = "^${escapedCurrent}-$([regex]::Escape($PreReleaseVersion))\.(\d+)$"
    $matchingTags = @($tags | Where-Object { $_ -match $exactPattern })

    if ($matchingTags.Count -eq 0) {
        $newTag = "v$majorMinor.$currentPatch-$PreReleaseVersion.1"
        Write-Host "No existing tags for v$majorMinor.$currentPatch-$PreReleaseVersion.*. Creating new tag: $newTag"
    } else {
        $latestTag = $matchingTags | Sort-Object {
            if ($_ -match $exactPattern) { return [int]$matches[1] }
            return 0
        } -Descending | Select-Object -First 1
        Write-Host "Latest tag for v$majorMinor.$currentPatch-$PreReleaseVersion.*: $latestTag"
        if ($latestTag -match $exactPattern) {
            $ciNumber = [int]$matches[1]
            $newCiNumber = $ciNumber + 1
            $newTag = "v$majorMinor.$currentPatch-$PreReleaseVersion.$newCiNumber"
        } else {
            Write-Error "Latest tag $latestTag does not match expected pattern."
            exit 1
        }
    }

    git tag $newTag
    git push $remote $newTag
    Write-Host "Created new tag '$newTag' and pushed it to remote '$remote'."
    Write-Output $newTag
} else {
    # ── VERSION-file-based: bump pre-release counter (legacy behavior) ──
    $escapedVersion = [regex]::Escape($version)
    $exactPattern = "^v$escapedVersion-$PreReleaseVersion\.(\d+)$"
    $matchingTags = $tags | Where-Object { $_ -match $exactPattern }

    if ($matchingTags.Count -eq 0) {
        $newTag = "v$version-$PreReleaseVersion.1"
        Write-Host "No existing tags for exact version $version. Creating new tag: $newTag"
        git tag $newTag
        git push $remote $newTag
        Write-Host "Created new tag '$newTag' and pushed it to remote '$remote'."
        Write-Output $newTag
        exit 0
    }

    $latestTag = $matchingTags | Sort-Object {
        if ($_ -match $exactPattern) { return [int]$matches[1] }
        return 0
    } -Descending | Select-Object -First 1

    Write-Host "Latest tag found: $latestTag"

    if ($latestTag -match $exactPattern) {
        $baseVersion = "v$version"
        $prNumber = [int]$matches[1]
        $newPrNumber = $prNumber + 1
        $newTag = "$baseVersion-$PreReleaseVersion.$newPrNumber"
        git tag $newTag
        git push $remote $newTag
        Write-Host "Created new tag '$newTag' and pushed it to remote '$remote'."
        Write-Output $newTag
    } else {
        Write-Error "Latest tag $latestTag does not match expected pattern."
        exit 1
    }
}
