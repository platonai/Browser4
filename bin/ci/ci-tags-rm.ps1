#!/usr/bin/env pwsh

<#
.SYNOPSIS
  Interactively remove CI tags matching a specified pattern from both local and remote.

.DESCRIPTION
  This script finds all git tags matching a versioned CI tag pattern (e.g., v1.2.3-ci.4), then asks for confirmation before deleting each tag locally and remotely.

.PARAMETER LocalOnly
  If specified, only delete matching tags locally and skip deleting them from the remote.
#>

param(
    [string]$remote = "origin",
    [string]$pattern = ".*-ci.*",
    [switch]$LocalOnly
)

# 🔍 Find the first parent directory containing the VERSION file
$AppHome=(Get-Item -Path $MyInvocation.MyCommand.Path).Directory
while ($AppHome -ne $null -and !(Test-Path "$AppHome/VERSION")) {
    $AppHome = Split-Path -Parent $AppHome
}
Set-Location $AppHome

function Get-MatchingTags {
    param([string]$pattern)
    return git tag --list | Where-Object { $_ -match "^$pattern$" }
}

function Remove-Tag {
    param(
        [string]$tag,
        [string]$remote,
        [switch]$LocalOnly
    )
    Write-Host "Removing tag '$tag' locally."
    git tag -d $tag | Out-Null

    if (-not $LocalOnly) {
        Write-Host "Removing tag '$tag' from remote '$remote'."
        git push $remote --delete $tag | Out-Null
    } else {
        Write-Host "Skipped removing tag '$tag' from remote (LocalOnly)."
    }
}

function Confirm-And-Remove-CITags {
    param(
        [string]$pattern,
        [string]$remote,
        [switch]$LocalOnly
    )
    $tags = Get-MatchingTags $pattern
    if ($tags) {
        Write-Host "Found CI tags to potentially remove: $($tags -join ', ')"
        $removeAll = $false
        foreach ($tag in $tags) {
            if (-not $removeAll) {
                if ($LocalOnly) {
                    $confirm = Read-Host "Do you want to remove tag '$tag' locally only? (y/N/all)"
                } else {
                    $confirm = Read-Host "Do you want to remove tag '$tag' from local and remote '$remote'? (y/N/all)"
                }

                if ($confirm -match '^(all)$') {
                    if ($LocalOnly) {
                        Write-Host "Removing all remaining CI tags locally only without further prompts."
                    } else {
                        Write-Host "Removing all remaining CI tags without further prompts."
                    }
                    $removeAll = $true
                } elseif ($confirm -notmatch '^(y|yes)$') {
                    Write-Host "Skipped removing tag '$tag'."
                    continue
                }
            }
            Remove-Tag -tag $tag -remote $remote -LocalOnly:$LocalOnly
        }
    } else {
        Write-Host "No matching tags found to remove."
    }
}

Confirm-And-Remove-CITags -pattern $pattern -remote $remote -LocalOnly:$LocalOnly
