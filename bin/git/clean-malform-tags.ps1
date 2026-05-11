#!/usr/bin/env pwsh

param (
    [string]$Remote = "origin",
    [switch]$DryRun
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$args = @("-Remote", $Remote)
if ($DryRun) {
    $args += "-DryRun"
}

Write-Host "`nStep 2: Removing non-version tags (tags not starting with 'v')..."
$localTags = git tag
$nonVersionTags = $localTags | Where-Object { $_ -notmatch "^v" }

if (-not $nonVersionTags) {
    Write-Host "No non-version tags found."
    exit 0
}

Write-Host "Non-version tags:"
$nonVersionTags | ForEach-Object { Write-Host "  $_" }

if ($DryRun) {
    Write-Host "`nDryRun mode enabled. No tags were deleted."
    exit 0
}

foreach ($tag in $nonVersionTags) {
    Write-Host "Deleting local tag: $tag"
    git tag -d $tag | Out-Null
    Write-Host "Deleting remote tag: $tag"
    git push origin --delete $tag | Out-Null
}

Write-Host "`nDone."
