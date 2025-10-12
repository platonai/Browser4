#!/usr/bin/env pwsh

param(
    [string]$remote = "origin"
)

# 🔍 Find the first parent directory containing the VERSION file
$AppHome=(Get-Item -Path $MyInvocation.MyCommand.Path).Directory
while ($AppHome -ne $null -and !(Test-Path "$AppHome/VERSION")) {
  $AppHome = Split-Path -Parent $AppHome
}

if (-not $AppHome -or -not (Test-Path "$AppHome/VERSION")) {
  Write-Error "VERSION file not found. Abort."
  exit 1
}

Set-Location $AppHome

# Ensure we're inside a Git repository
$null = git rev-parse --is-inside-work-tree 2>$null
if ($LASTEXITCODE -ne 0) {
  Write-Error "Not a Git repository: $AppHome. Abort."
  exit 1
}

# Get version information (first line, trimmed)
$VERSION = (Get-Content (Join-Path $AppHome 'VERSION') -TotalCount 1).Trim()
if (-not $VERSION) {
  Write-Error "VERSION is empty. Abort."
  exit 1
}

# 1) Ensure VERSION contains 'build'
if ($VERSION -notmatch '(?i)build') {
  Write-Host "VERSION '$VERSION' does not contain 'build'. Exit without tagging." -ForegroundColor Yellow
  exit 0
}

# Validate remote exists
$remoteUrl = git remote get-url $remote 2>$null
if ($LASTEXITCODE -ne 0) {
  Write-Error "Git remote '$remote' not found. Abort."
  exit 1
}

# Fetch tags to ensure up-to-date state for existence checks
Write-Host "Fetching tags from '$remote'..."
 git fetch $remote --tags --prune --quiet
if ($LASTEXITCODE -ne 0) {
  Write-Error "Failed to fetch tags from '$remote'. Abort."
  exit 1
}

$newTag = $VERSION
Write-Host "Target tag: $newTag" -ForegroundColor Cyan

# Check if tag exists locally
$null = git rev-parse -q --verify "refs/tags/$newTag" 2>$null
$tagExistsLocal = ($LASTEXITCODE -eq 0)

# Check if tag exists on remote
$remoteTagRef = git ls-remote --tags $remote "refs/tags/$newTag" 2>$null
$tagExistsRemote = -not [string]::IsNullOrWhiteSpace($remoteTagRef)

if ($tagExistsLocal -or $tagExistsRemote) {
  # 2) Tag exists: ask for confirmation before delete
  $where = @()
  if ($tagExistsLocal) { $where += 'local' }
  if ($tagExistsRemote) { $where += 'remote' }
  $whereStr = ($where -join ' & ')
  Write-Host "Tag '$newTag' already exists ($whereStr)." -ForegroundColor Yellow
  $confirm = Read-Host "Delete existing $whereStr tag(s) for '$newTag' and recreate? (y/N)"
  if ($confirm -notmatch '^(?i:y|yes)$') {
    Write-Host "Operation cancelled. No changes made."
    exit 0
  }

  if ($tagExistsLocal) {
    Write-Host "Deleting local tag '$newTag'..."
    git tag -d $newTag | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Error "Failed to delete local tag '$newTag'"; exit 1 }
  }

  if ($tagExistsRemote) {
    Write-Host "Deleting remote tag '$newTag' on '$remote'..."
    git push $remote :refs/tags/$newTag | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Error "Failed to delete remote tag '$newTag' on '$remote'"; exit 1 }
  }
}
else {
  Write-Host "Tag '$newTag' does not exist locally or on remote. Creating new tag..." -ForegroundColor Green
}

# 3) Create tag and push (no confirmation needed when it didn't exist)
Write-Host "Creating tag '$newTag'..."
 git tag $newTag
if ($LASTEXITCODE -ne 0) { Write-Error "Failed to create tag '$newTag'"; exit 1 }

Write-Host "Pushing tag '$newTag' to '$remote'..."
 git push $remote $newTag | Out-Host
if ($LASTEXITCODE -ne 0) { Write-Error "Failed to push tag '$newTag' to '$remote'"; exit 1 }

Write-Host "✅ Done. Tag '$newTag' is now on remote '$remote'." -ForegroundColor Green
