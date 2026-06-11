#!/usr/bin/env pwsh

param(
    [string]$remote = "origin",
    [string]$message = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

# Import common utility script
. $repoRoot\bin\common\Util.ps1

Fix-Encoding-UTF8

Write-Host "Working in: $repoRoot"

# Check if we're in a git repo
if (!(Test-Path ".git")) {
    Write-Error "Not a git repository"
    exit 1
}

# Check current branch
$branch = git rev-parse --abbrev-ref HEAD

Write-Host "Current branch: $branch"

# Check for uncommitted changes
$status = git status --porcelain
if ($status) {
    Write-Warning "Uncommitted changes detected"
    $continue = Read-Host "Continue anyway? (y/n)"
    if ($continue -ne 'y') {
        Write-Host "Cancelled"
        exit 0
    }
}

# Read and process version
$version = (Get-Content "VERSION").Trim()
Write-Host "Version from file: $version"

if ($version.EndsWith("-SNAPSHOT")) {
    $version = $version.Replace("-SNAPSHOT", "")
    Write-Host "Cleaned version: $version"
}

# Validate version format (support rc tags like x.y.z-rc.1)
if ($version -notmatch "^\d+\.\d+\.\d+(?:-rc\.\d+)?$") {
    Write-Error "Invalid version format: $version"
    exit 1
}

$newTag = "v$version"

# Check if tag already exists
$existingTag = git tag -l $newTag
if ($existingTag) {
    Write-Host "Tag '$newTag' already exists"

    $confirm = Read-Host "Do you want to overwrite it? (y/n)"
    if ($confirm -ne 'y') {
        Write-Host "Cancelled"
        exit 0
    }
    try {
        # Delete local tag
        git tag -d $newTag
        Write-Host "Deleted local tag: $newTag"

        # Delete remote tag if it exists
        $remoteTag = git ls-remote --tags $remote "refs/tags/$newTag" 2>$null
        if ($remoteTag) {
            git push $remote --delete $newTag
            Write-Host "Deleted remote tag: $newTag"
        }
    } catch {
        Write-Error "Failed to delete existing tag: $_"
        exit 1
    }
}

function Get-TagSortKey {
    param(
        [string]$Tag
    )

    $clean = $Tag -replace '^v',''
    if ($clean -notmatch '^(?<base>\d+\.\d+\.\d+)(?:-rc\.(?<rc>\d+))?$') {
        return $null
    }

    $baseVersion = [version]$matches['base']
    $rcValue = if ($matches['rc']) { [int]$matches['rc'] } else { [int]::MaxValue }

    return [pscustomobject]@{
        Base = $baseVersion
        Rc = $rcValue
    }
}

# Get previous tag for release notes (supports vX.Y.Z and X.Y.Z-rc.N)
$tagCandidates = git tag --list | Where-Object { $_ -match '^(v\d+\.\d+\.\d+|\d+\.\d+\.\d+-rc\.\d+)$' }
$prevTag = $tagCandidates |
        ForEach-Object {
            $key = Get-TagSortKey $_
            if ($key) {
                [pscustomobject]@{ Tag = $_; Base = $key.Base; Rc = $key.Rc }
            }
        } |
        Sort-Object Base, Rc -Descending |
        Select-Object -First 1 |
        ForEach-Object { $_.Tag }

if ($prevTag) {
    Write-Host "`nChanges since $prevTag :"
    $changes = git log --oneline --no-merges "$prevTag..HEAD"
    if ($changes) {
        $changes | ForEach-Object { Write-Host "  - $_" }
    } else {
        Write-Host "  No changes"
    }
} else {
    Write-Host "`nRecent commits:"
    git log --oneline --no-merges -5 | ForEach-Object { Write-Host "  • $_" }
}

# Prompt for tag message if not provided
if ([string]::IsNullOrWhiteSpace($message)) {
    Write-Host ""
    $message = Read-Host "Enter release message (optional, press Enter to skip)"
}

# Confirm creation
Write-Host ""
$tagType = if ([string]::IsNullOrWhiteSpace($message)) { "lightweight" } else { "annotated" }
$confirm = Read-Host "Create and push $tagType tag '$newTag'? (y/n)"
if ($confirm -ne 'y') {
    Write-Host "Cancelled"
    exit 0
}

# Create and push tag
try {
    # Create annotated tag if message provided, otherwise lightweight tag
    if ([string]::IsNullOrWhiteSpace($message)) {
        git tag $newTag
        Write-Host "Created lightweight tag: $newTag"
    } else {
        git tag -a $newTag -m $message
        Write-Host "Created annotated tag: $newTag"
    }

    # Push tag to remote
    git push $remote $newTag
    Write-Host "Successfully pushed tag: $newTag"

    # Try to show GitHub URL
    $remoteUrl = git config --get remote.$remote.url
    if ($remoteUrl -match 'github\.com[:/](.+?)(?:\.git)?$') {
        $repo = $matches[1]
        Write-Host "Release URL: https://github.com/$repo/releases/tag/$newTag"
    }

    Write-Output $newTag
} catch {
    Write-Error "Failed to create/push tag: $_"
    exit 1
}
