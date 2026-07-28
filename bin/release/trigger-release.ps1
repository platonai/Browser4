#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

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

# ═══════════════════════════════════════════════════════════════════
# Version guard: verify current version is exactly the next patch
# after the last GitHub release. If not, ask for confirmation.
# ═══════════════════════════════════════════════════════════════════

function Get-SemverBase {
    param([string]$V)
    # Strip leading 'v' and trailing -rc.N / -SNAPSHOT suffix
    $v = $V -replace '^v', ''
    $v = $v -replace '-(rc\.\d+|SNAPSHOT)$', ''
    return $v
}

function Get-LastReleaseTag {
    # Try GitHub Releases first
    try {
        $tag = gh release list --limit 1 --json tagName --jq '.[0].tagName' 2>$null
        if ($tag) { return $tag.Trim() }
    } catch { }

    # Fallback: git tags sorted by version (only stable semver tags)
    try {
        $tag = git tag --list 'v*' --sort=-v:refname 2>$null |
            Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } |
            Select-Object -First 1
        if ($tag) { return $tag.Trim() }
    } catch { }

    # Last resort: git describe
    try {
        $tag = git describe --tags --abbrev=0 2>$null
        if ($tag) { return $tag.Trim() }
    } catch { }

    return $null
}

$lastReleaseTag = Get-LastReleaseTag

if ($lastReleaseTag) {
    $lastBase = Get-SemverBase $lastReleaseTag
    $currentBase = Get-SemverBase $version

    Write-Host "`nLast GitHub release: $lastReleaseTag"
    Write-Host "Current version:     v$version"

    if ($lastBase -match '^(\d+)\.(\d+)\.(\d+)') {
        $expectedNext = "$($matches[1]).$($matches[2]).$([int]$matches[3] + 1)"

        if ($currentBase -ne $expectedNext) {
            Write-Host ""
            Write-Warning "Version mismatch: current version is not the next patch after the last release"
            Write-Host "  Last release:         $lastReleaseTag  (base: $lastBase)"
            Write-Host "  Expected next patch:  v$expectedNext"
            Write-Host "  Current version:      v$version  (base: $currentBase)"
            Write-Host ""
            $confirm = Read-Host "Continue anyway? (y/n)"
            if ($confirm -ne 'y') {
                Write-Host "Cancelled"
                exit 0
            }
            Write-Host "Proceeding despite version mismatch..."
        } else {
            Write-Host "[OK] v$version is exactly the next patch after $lastReleaseTag"
        }
    } else {
        Write-Warning "Could not parse last release version: $lastReleaseTag"
        $confirm = Read-Host "Continue anyway? (y/n)"
        if ($confirm -ne 'y') {
            Write-Host "Cancelled"
            exit 0
        }
    }
} else {
    Write-Host "`nNo previous GitHub release found (first release?). Proceeding..."
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
