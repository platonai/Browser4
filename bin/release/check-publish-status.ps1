#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Checks if the current project version has been fully published.

.DESCRIPTION
    Verifies that the current version (tagged as vX.Y.Z) is the latest release
    on GitHub. When this condition is satisfied, the version is considered
    "published" and safe to bump. Exits with code 0 if published, non-zero otherwise.

.PARAMETER Version
    The version to check (without the -SNAPSHOT suffix).
    If omitted, reads the version from the VERSION file in the repo root.

.EXAMPLE
    .\check-publish-status.ps1
    Checks the version from the VERSION file.

.EXAMPLE
    .\check-publish-status.ps1 -Version 4.8.4
    Checks version 4.8.4 explicitly.
#>
[CmdletBinding()]
param (
    [Parameter(HelpMessage = "The version to check (without -SNAPSHOT)")]
    [string]$Version
)

$ErrorActionPreference = "Stop"

# Force UTF-8 output encoding for correct display of special characters
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Determine repo root
$repoRoot = (git rev-parse --show-toplevel 2>$null)
if ($null -eq $repoRoot) {
    Write-Error "Could not determine project root. Are you in a git repository?"
    exit 2
}
Set-Location $repoRoot

# Resolve the version
if (-not $Version) {
    if (-not (Test-Path "$repoRoot\VERSION")) {
        Write-Error "VERSION file not found at $repoRoot\VERSION and no -Version argument was provided."
        exit 2
    }
    $snapshotVersion = (Get-Content "$repoRoot\VERSION" -TotalCount 1).Trim()
    $Version = $snapshotVersion -replace "-SNAPSHOT", ""
}

# Validate version format
if ($Version -notmatch "^\d+\.\d+\.\d+") {
    Write-Error "Version '$Version' does not match the expected format X.Y.Z"
    exit 2
}

Write-Host "Checking publish status for version: v$Version"
Write-Host ""

# Derive GitHub repository from the git remote
$remoteUrl = git config --get remote.origin.url
if ($remoteUrl -notmatch 'github\.com[:/](.+?)(?:\.git)?$') {
    Write-Error "Could not determine GitHub repository from remote URL: $remoteUrl"
    exit 2
}
$githubRepo = $matches[1]
Write-Host "GitHub repository : $githubRepo"

# ---------------------------------------------------------------
# Check: Is this version the latest GitHub release?
# ---------------------------------------------------------------
Write-Host "-------------------------------------------------"
Write-Host " Check 1: Latest GitHub release"
Write-Host "-------------------------------------------------"

$isLatestRelease = $false
$latestReleaseTag = $null

# Prefer gh CLI if available (handles auth and rate limiting)
$ghAvailable = $null -ne (Get-Command gh -ErrorAction SilentlyContinue)
if ($ghAvailable) {
    Write-Host "Using gh CLI to fetch latest release..."

    try {
        # gh release list returns releases ordered by creation date (newest first)
        $latestRelease = & gh release list --repo $githubRepo --limit 1 --json tagName 2>&1
        if ($LASTEXITCODE -eq 0) {
            $latestReleaseObj = $latestRelease | ConvertFrom-Json
            if ($latestReleaseObj -and $latestReleaseObj.Count -gt 0) {
                $latestReleaseTag = $latestReleaseObj[0].tagName
                Write-Host "  Latest release : $latestReleaseTag"
                Write-Host "  Current tag    : v$Version"

                if ($latestReleaseTag -eq "v$Version") {
                    $isLatestRelease = $true
                    Write-Host "  [OK] The current version IS the latest GitHub release."
                } else {
                    Write-Host "  [XX] The current version is NOT the latest release."
                }
            } else {
                Write-Host "  No releases found via gh CLI. Falling back to GitHub API."
            }
        } else {
            Write-Host "  gh command returned an error. Falling back to GitHub API."
        }
    } catch {
        Write-Host "  gh error: $_"
        Write-Host "  Falling back to GitHub API."
    }
}

# Fallback: use GitHub API
if (-not $ghAvailable -or $null -eq $latestReleaseTag) {
    Write-Host "Using GitHub API to fetch latest release..."

    try {
        $apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"
        $response = Invoke-RestMethod -Uri $apiUrl -Method Get -Headers @{
            Accept = "application/vnd.github+json"
        } -ErrorAction SilentlyContinue

        if ($response) {
            $latestReleaseTag = $response.tag_name
            Write-Host "  Latest release : $latestReleaseTag"
            Write-Host "  Current tag    : v$Version"

            if ($latestReleaseTag -eq "v$Version") {
                $isLatestRelease = $true
                Write-Host "  [OK] The current version IS the latest GitHub release."
            } else {
                Write-Host "  [XX] The current version is NOT the latest release."
            }
        } else {
            Write-Host "  GitHub API returned no data (no releases yet?)."
        }
    } catch {
        Write-Host "  GitHub API error: $_"
    }
}

# Second fallback: compare against latest git tag
if ($null -eq $latestReleaseTag) {
    Write-Host "Falling back to git tag comparison..."

    # Get the latest tag matching vX.Y.Z pattern
    $latestTag = git ls-remote --tags --sort=-version:refname origin 2>$null `
        | Select-Object -First 1 `
        | ForEach-Object { $_ -match 'refs/tags/(v\d+\.\d+\.\d+)$' | Out-Null; $matches[1] }

    if ($latestTag) {
        Write-Host "  Latest remote tag: $latestTag"
        Write-Host "  Current tag      : v$Version"

        if ($latestTag -eq "v$Version") {
            $isLatestRelease = $true
            Write-Host "  [OK] The current version matches the latest tag."
        } else {
            Write-Host "  [XX] The current version does NOT match the latest tag."
        }
    } else {
        Write-Host "  [XX] No version tags found. Cannot verify release status."
    }
}

# ---------------------------------------------------------------
# Summary
# ---------------------------------------------------------------
Write-Host ""
Write-Host "-------------------------------------------------"
Write-Host " Summary"
Write-Host "-------------------------------------------------"

Write-Host ""
Write-Host "  Version          : v$Version"
Write-Host "  GitHub latest    : $($(if ($isLatestRelease) { '[OK] YES' } else { '[XX] NO' }))"
Write-Host "  Published        : $($(if ($isLatestRelease) { '[OK] YES' } else { '[XX] NO' }))"
Write-Host ""

if ($isLatestRelease) {
    Write-Host "Version v$Version is the latest GitHub release -- safe to bump."
    exit 0
} else {
    Write-Host "Version v$Version is NOT the latest GitHub release."
    exit 1
}
