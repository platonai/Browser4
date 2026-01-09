#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Creates a new minor release branch and bumps the version.
.DESCRIPTION
    This script automates the creation of a new minor release branch (e.g., '4.2.x').
    It reads the current version from the VERSION file, determines the next minor version,
    creates a new branch with the name 'MAJOR.MINOR.x', and then bumps the project version
    to the new minor SNAPSHOT version on that branch. Finally, it commits the changes with
    the commit message exactly equal to the branch name (e.g., '4.2.x').
.PARAMETER Push
    When specified, pushes the new branch to the remote and sets upstream.
.PARAMETER Remote
    The Git remote name to push to when -Push is used. Defaults to 'origin'.
.EXAMPLE
    .\new-minor-branch.ps1
    If the current version is 4.1.0-SNAPSHOT, it creates a branch '4.2.x' and bumps the version to 4.2.0-SNAPSHOT,
    committing with message '4.2.x'.
.EXAMPLE
    .\new-minor-branch.ps1 -Push
    Same as above and pushes the new branch to 'origin' with upstream set.
.EXAMPLE
    .\new-minor-branch.ps1 -Push -Remote upstream
    Pushes the new branch to 'upstream' instead of 'origin'.
#>
[CmdletBinding()]
param (
    [switch]$Push = $false,
    [string]$Remote = 'origin'
)

# Find the project root directory containing the VERSION file
$scriptPath = if ($MyInvocation.MyCommand.CommandType -eq 'ExternalScript') {
    $MyInvocation.MyCommand.Path
} else {
    (Get-PSCallStack)[0].ScriptName
}
$AppHome = (Get-Item -Path $scriptPath).Directory.Parent
while ($AppHome -ne $null -and !(Test-Path "$AppHome/VERSION")) {
    $AppHome = Split-Path -Parent $AppHome
}

if ($null -eq $AppHome) {
    Write-Error "VERSION file not found in any parent directory. Could not determine project root."
    exit 1
}

Set-Location $AppHome
Write-Host "Project root is: $AppHome"

# Ensure working tree is clean to avoid accidental commits
& git update-index -q --refresh
& git diff --quiet --ignore-submodules HEAD -- 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Working tree has uncommitted changes. Please commit or stash them before proceeding."
    exit 1
}

# Get current version
$SNAPSHOT_VERSION = Get-Content "$AppHome\VERSION" -TotalCount 1
$VERSION = $SNAPSHOT_VERSION -replace "-SNAPSHOT$", ""

# Parse version components
$versionParts = $VERSION -split "\."
if ($versionParts.Length -lt 3) {
    Write-Error "Invalid VERSION format '$SNAPSHOT_VERSION'. Expected MAJOR.MINOR.PATCH[-SNAPSHOT]"
    exit 1
}
$major = [int]$versionParts[0]
$minor = [int]$versionParts[1]
$patch = [int]$versionParts[2]

# Calculate the next minor version and branch name
$nextMinor = $minor + 1
$newBranchName = "$major.$nextMinor.x"
$NEXT_VERSION = "$major.$nextMinor.0"
$NEXT_SNAPSHOT_VERSION = "$NEXT_VERSION-SNAPSHOT"

Write-Host "Current version: $SNAPSHOT_VERSION"
Write-Host "Creating branch: $newBranchName"
Write-Host "Next snapshot version: $NEXT_SNAPSHOT_VERSION"

# Create and switch to the new branch
& git checkout -b $newBranchName
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to create new branch '$newBranchName'."
    exit 1
}

# Update VERSION file
$NEXT_SNAPSHOT_VERSION | Set-Content "$AppHome\VERSION"

# Update pom.xml files using Maven
$mvnCmd = if ($IsWindows) { Join-Path $AppHome "mvnw.cmd" } else { Join-Path $AppHome "mvnw" }
& $mvnCmd versions:set -DnewVersion=$NEXT_SNAPSHOT_VERSION -DprocessAllModules -DgenerateBackupPoms=false
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven versions:set command failed. Reverting VERSION file and branch."
    $SNAPSHOT_VERSION | Set-Content "$AppHome\VERSION"
    & git checkout -
    & git branch -D $newBranchName | Out-Null
    exit 1
}

# Update root pom.xml's git tag (if present)
$pomXmlPath = "$AppHome\pom.xml"
if (Test-Path $pomXmlPath) {
    $pomContent = Get-Content $pomXmlPath -Raw
    $pomContent = $pomContent -replace "<tag>v$([regex]::Escape($VERSION))</tag>", "<tag>v$NEXT_VERSION</tag>"
    Set-Content -Path $pomXmlPath -Value $pomContent
}

# Files containing the version number to upgrade (if present)
$VERSION_AWARE_FILES = @(
    "$AppHome\README.md",
    "$AppHome\README.zh.md"
)
foreach ($F in $VERSION_AWARE_FILES) {
    if (Test-Path $F) {
        $content = Get-Content $F -Raw
        # Replace the full snapshot string safely
        $content = $content -replace [regex]::Escape($SNAPSHOT_VERSION), $NEXT_SNAPSHOT_VERSION
        # Update 'vMAJOR.MINOR.PATCH' tokens to new release version (digits only)
        $content = $content -replace "v[0-9]+\.[0-9]+\.[0-9]+", "v$NEXT_VERSION"
        Set-Content $F $content
    }
}

# Commit changes with the branch name as the message
& git add -A
& git commit -m $newBranchName
if ($LASTEXITCODE -ne 0) {
    Write-Error "Git commit failed. Please check repository status."
    exit 1
}

# Optionally push to remote and set upstream
if ($Push) {
    $null = & git remote get-url $Remote 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Remote '$Remote' not found. Use -Remote to specify a valid remote."
        exit 1
    }
    & git push -u $Remote $newBranchName
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to push branch '$newBranchName' to '$Remote'."
        exit 1
    }
    Write-Host "Pushed branch '$newBranchName' to '$Remote' and set upstream."
} else {
    Write-Host "Push skipped. Run with -Push to push the branch to '$Remote'."
}

Write-Host "Branch '$newBranchName' created and version bumped to $NEXT_SNAPSHOT_VERSION. Commit message: '$newBranchName'"

