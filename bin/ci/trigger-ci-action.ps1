#!/usr/bin/env pwsh

param(
    [string]$PreReleaseVersion = "ci",
    [string]$remote = "origin"
)

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

# Get version information
$SNAPSHOT_VERSION = Get-Content "$repoRoot\VERSION" -TotalCount 1
$version = $SNAPSHOT_VERSION -replace "-SNAPSHOT", ""

$parts = $version -split "\."
$PREFIX = $parts[0] + "." + $parts[1]
$escapedVersion = [regex]::Escape($version)
$pattern = "^v$escapedVersion-$PreReleaseVersion\.[0-9]+$"

# Get all matching tags and sort them by version and pre-release number
$tags = git tag --list | Where-Object { $_ -match "^$pattern$" }
if (-not $tags) {
    $newTag = "v$version-$PreReleaseVersion.1"
    Write-Host "No existing tags found. Creating new tag: $newTag"
    git tag $newTag
    git push $remote $newTag
    Write-Host "Created new tag '$newTag' and pushed it to remote '$remote'."
    Write-Output $newTag
    exit 0
}

# Sort tags by pre-release number (natural order within the same base version)
$latestTag = $tags | Sort-Object {
    if ($_ -match "^v$escapedVersion-$PreReleaseVersion\.(\d+)$") {
        return [int]$matches[1]
    }
    return 0
} -Descending | Select-Object -First 1

Write-Host "Latest tag found: $latestTag"

if ($latestTag -match "^v$escapedVersion-$PreReleaseVersion\.(\d+)$") {
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
