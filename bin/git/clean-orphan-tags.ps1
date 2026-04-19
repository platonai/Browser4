#!/usr/bin/env pwsh

param (
    [string]$Remote = "origin",
    [switch]$DryRun
)

Write-Host "Fetching tags from remote '$Remote'..."
git fetch --tags $Remote | Out-Null

# 获取远程 tag 列表
$remoteTags = git ls-remote --tags $Remote |
        ForEach-Object {
            ($_ -split "\s+")[1] -replace "refs/tags/", "" -replace "\^\{\}", ""
        } |
        Sort-Object -Unique

# 获取本地 tag 列表
$localTags = git tag

# 找出本地存在但远程不存在的 tag
$orphanTags = $localTags | Where-Object { $_ -notin $remoteTags }

if (-not $orphanTags) {
    Write-Host "No orphan tags found."
    exit 0
}

Write-Host "Orphan tags:"
$orphanTags | ForEach-Object { Write-Host "  $_" }

if ($DryRun) {
    Write-Host "`nDryRun mode enabled. No tags were deleted."
    exit 0
}

# 删除本地孤立 tag
foreach ($tag in $orphanTags) {
    Write-Host "Deleting local tag: $tag"
    git tag -d $tag | Out-Null
}

Write-Host "`nDone."
