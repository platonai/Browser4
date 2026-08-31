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
       aborting if it does not. The check is branch-aware: on version branches
       (X.Y.x) it compares against the latest release on the same X.Y line
       only, and a line with no stable release yet (e.g. a fresh minor branch
       like 4.14.x before any 4.14 release) skips the patch+1 check instead of
       aborting against an unrelated older line. On version branches the VERSION
       file's major.minor MUST match the branch (hard failure, no confirmation
       bypass) — a mismatched VERSION would synthesize a tag for a version that
       does not exist (e.g. branch 4.14.x + stale VERSION 4.13.6-SNAPSHOT
       produced the bogus v4.14.6-ci.N tags).
    2. Detects the current git branch.
    3. If the branch matches X.Y.x (e.g. 4.12.x), uses X.Y as the version prefix
       and the patch from the current VERSION file, then searches for existing
       tags like vX.Y.<patch>-ci.* — scoped to that branch.
    4. Increments only the pre-release counter (ci.N); the patch tracks the
       current VERSION file: v4.12.5-ci.3 → v4.12.5-ci.4 (same patch), or
       v4.12.5-ci.3 → v4.12.6-ci.1 after a version bump. The tag base version
       always equals the VERSION file's version — never a synthesized blend.
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

# Resolve stable release tags (gh CLI first, then git tags). When a major.minor
# line is given (e.g. "4.14" on branch 4.14.x), only releases on that line count.
function Get-LatestStableRelease {
    param([string]$MajorMinor = '')

    $releaseTags = @()
    try {
        $releaseTags = @(gh release list --limit 100 --json tagName 2>$null | ConvertFrom-Json | ForEach-Object { $_.tagName })
    } catch { }

    if ($releaseTags.Count -eq 0) {
        $releaseTags = @(git tag --sort=-v:refname 2>$null)
    }

    if ($MajorMinor) {
        $linePattern = "^v?$([regex]::Escape($MajorMinor))\.\d+$"
        return @($releaseTags | Where-Object { $_ -match $linePattern } | Select-Object -First 1)
    }
    return @($releaseTags | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | Select-Object -First 1)
}

$localSnapshot = Get-Content "$repoRoot\VERSION" -TotalCount 1
$localVersion = ($localSnapshot -replace '-SNAPSHOT', '').Trim()

# Version branches (X.Y.x) must be validated against the same X.Y release line:
# comparing 4.14.x against the latest 4.13.x release would demand "4.13.8" and
# abort a legitimately-new minor line. A line with no stable releases yet skips
# the patch+1 check. The VERSION file must still share the branch's major.minor.
$branch = git rev-parse --abbrev-ref HEAD 2>$null
$branchMajorMinor = if ($branch -match '^(\d+)\.(\d+)\.x$') { "$($matches[1]).$($matches[2])" } else { '' }

if ($branchMajorMinor) {
    $localParts = $localVersion -split '\.'
    if ($localParts.Count -ge 2 -and "$($localParts[0]).$($localParts[1])" -ne $branchMajorMinor) {
        # Hard failure, NOT a confirmable warning: a CI tag built from a
        # mismatched VERSION claims a version that does not exist. Real-world
        # case: branch 4.14.x with a stale VERSION 4.13.6-SNAPSHOT produced the
        # bogus v4.14.6-ci.N tags although v4.14.6 never existed.
        Write-Error "Aborted: local VERSION '$localVersion' major.minor does not match branch '$branch' ('$branchMajorMinor'). Bump the VERSION file (and module poms) to '$branchMajorMinor.x-SNAPSHOT' on this branch before creating CI tags."
        exit 1
    } else {
        $latestRelease = Get-LatestStableRelease -MajorMinor $branchMajorMinor
        if (-not $latestRelease) {
            Write-Host "No stable release yet for line $branchMajorMinor — skipping the patch+1 check (new minor line)."
        } elseif ($latestRelease -match '^v?(\d+)\.(\d+)\.(\d+)$') {
            $expectedVersion = "$($matches[1]).$($matches[2]).$([int]$matches[3] + 1)"
            if ($localVersion -ne $expectedVersion) {
                Write-Warning "Local VERSION '$localVersion' is not latest '$branchMajorMinor' release '$latestRelease' patch + 1 (expected '$expectedVersion')."
                try { $answer = Read-Host "Continue anyway? [y/N]" } catch { $answer = '' }
                if ($answer -notmatch '^[Yy]$') {
                    Write-Error "Aborted: local VERSION '$localVersion' does not equal latest '$branchMajorMinor' release '$latestRelease' patch + 1 ('$expectedVersion')."
                    exit 1
                }
                Write-Host "Continuing despite version mismatch (user confirmed)."
            } else {
                Write-Host "Local VERSION '$localVersion' matches latest '$branchMajorMinor' release '$latestRelease' patch + 1."
            }
        }
    }
} else {
    $latestRelease = Get-LatestStableRelease
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
}

# ═══════════════════════════════════════════════════════════════════
# 1. Determine version prefix from current branch
# ═══════════════════════════════════════════════════════════════════

# $branch was already resolved in step 0 (branch-aware version check).
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
    if ("v$majorMinor.$initialPatch" -ne "v$fileVersion") {
        Write-Error "Aborted: seed tag base 'v$majorMinor.$initialPatch' does not match VERSION file '$fileVersion'. Refusing to create a CI tag for a version that does not exist."
        exit 1
    }
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

    # The tag base must be exactly the VERSION file's version — never a blend
    # of branch major.minor with a patch from a stale/unrelated VERSION file
    # (that blend is how the non-existent v4.14.6-ci.N tags were created).
    if ("v$majorMinor.$currentPatch" -ne "v$currentVersion") {
        Write-Error "Aborted: tag base 'v$majorMinor.$currentPatch' (branch '$branch' major.minor + VERSION patch) does not match VERSION file '$currentVersion'. Refusing to create a CI tag for a version that does not exist."
        exit 1
    }

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
