#!/usr/bin/env pwsh

<#!
.SYNOPSIS
  删除早于指定版本的稳定版 Git tag（默认 v4.0.0）。

.DESCRIPTION
  仅处理形如 vMAJOR.MINOR.PATCH 的稳定版标签。
  例如当 BeforeTag=v4.0.0 时，会删除 v3.x.x、v2.x.x 等所有更低版本的标签。

.PARAMETER BeforeTag
  阈值标签，默认 v4.0.0。小于该版本的稳定版标签会被删除。

.PARAMETER Remote
  远程仓库名称，默认 origin。

.PARAMETER DeleteRemote
  同时删除远程同名 tag。

.PARAMETER SkipFetch
  跳过执行 git fetch --tags。

.PARAMETER DryRun
  仅展示删除计划，不执行删除。

.EXAMPLE
  .\bin\git\remove-tags-before.ps1 -DryRun

.EXAMPLE
  .\bin\git\remove-tags-before.ps1 -BeforeTag v4.0.0 -DeleteRemote

.EXAMPLE
  .\bin\git\remove-tags-before.ps1 -BeforeTag v4.0.0 -DeleteRemote -WhatIf
#>

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param (
    [string]$BeforeTag = 'v4.0.0',
    [string]$Remote = 'origin',
    [switch]$DeleteRemote,
    [switch]$SkipFetch,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Get-StableTagVersion {
    param(
        [Parameter(Mandatory)]
        [string]$Tag
    )

    if ($Tag -notmatch '^v(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)$') {
        return $null
    }

    return [Version]::new([int]$matches['major'], [int]$matches['minor'], [int]$matches['patch'])
}

$cutoffVersion = Get-StableTagVersion -Tag $BeforeTag
if (-not $cutoffVersion) {
    throw "BeforeTag must match stable format vMAJOR.MINOR.PATCH, current value: $BeforeTag"
}

$repoRoot = (& git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $repoRoot) {
    throw 'Not a git repository.'
}

$repoRoot = ($repoRoot | Select-Object -First 1).ToString().Trim()
Set-Location $repoRoot

Write-Host "Working in: $repoRoot"
Write-Host "Delete stable tags older than: $BeforeTag"

if (-not $SkipFetch) {
    Write-Host "Fetching tags from remote '$Remote'..."
    git fetch --tags $Remote | Out-Null
}

$allTags = @(git tag --list)
if (-not $allTags) {
    Write-Host 'No local tags found.'
    exit 0
}

$stableTagInfos = foreach ($tag in $allTags) {
    if ([string]::IsNullOrWhiteSpace($tag)) {
        continue
    }

    $tagVersion = Get-StableTagVersion -Tag $tag
    if (-not $tagVersion) {
        continue
    }

    [pscustomobject]@{
        Tag     = $tag
        Version = $tagVersion
    }
}

if (-not $stableTagInfos) {
    Write-Host 'No stable tags matching vMAJOR.MINOR.PATCH were found.'
    exit 0
}

$tagsToDelete = @(
    $stableTagInfos |
        Where-Object { $_.Version -lt $cutoffVersion } |
        Sort-Object Version, Tag
)

if (-not $tagsToDelete) {
    Write-Host "No stable tags older than $BeforeTag were found."
    exit 0
}

Write-Host "`nTags scheduled for deletion ($($tagsToDelete.Count)):"
$tagsToDelete | ForEach-Object { Write-Host "  $($_.Tag)" }

if ($DryRun) {
    Write-Host "`nDryRun mode enabled. No tags were deleted."
    exit 0
}

if (-not $WhatIfPreference) {
    $target = if ($DeleteRemote) { "local and remote '$Remote'" } else { 'local' }
    $confirm = Read-Host "`nDelete these $($tagsToDelete.Count) tags from $target? (y/N)"
    if ($confirm -notmatch '^(y|yes)$') {
        Write-Host 'Cancelled. No tags were deleted.'
        exit 0
    }
}

$remoteTags = @()
if ($DeleteRemote) {
    $remoteTags = @(
        git ls-remote --tags $Remote |
            ForEach-Object {
                $parts = ($_ -split "\s+", 2)
                if ($parts.Count -ge 2) {
                    $parts[1] -replace '^refs/tags/', '' -replace '\^\{\}$', ''
                }
            } |
            Where-Object { $_ } |
            Sort-Object -Unique
    )
}

$deletedLocal = 0
$deletedRemote = 0

foreach ($item in $tagsToDelete) {
    $tag = $item.Tag

    if ($PSCmdlet.ShouldProcess($tag, 'Delete local tag')) {
        git tag -d $tag | Out-Null
        Write-Host "Deleted local tag: $tag"
        $deletedLocal++
    }

    if ($DeleteRemote -and $tag -in $remoteTags) {
        if ($PSCmdlet.ShouldProcess("$Remote/$tag", 'Delete remote tag')) {
            git push $Remote --delete $tag | Out-Null
            Write-Host "Deleted remote tag: $tag"
            $deletedRemote++
        }
    }
}

Write-Host "`nDone. Deleted local tags: $deletedLocal"
if ($DeleteRemote) {
    Write-Host "Done. Deleted remote tags: $deletedRemote"
}

