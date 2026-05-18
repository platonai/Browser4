#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Bumps the project version based on the specified part (major, minor, or patch).

.DESCRIPTION
    This script automates the process of updating the project version. It reads the current version from the VERSION file,
    increments the specified part (major, minor, or patch), and then updates the version number in all relevant files,
    including pom.xml, READMEs, and the VERSION file itself. Finally, it commits the changes to Git.

.PARAMETER Part
    Specifies which part of the version to bump. Must be one of 'major', 'minor', or 'patch'.

.EXAMPLE
    .\bump-version.ps1 -Part patch
    Bumps the patch version (e.g., 1.2.3 -> 1.2.4).

.EXAMPLE
    .\bump-version.ps1 -Part minor
    Bumps the minor version and resets the patch version to 0 (e.g., 1.2.3 -> 1.3.0).

.EXAMPLE
    .\bump-version.ps1 -Part major
    Bumps the major version and resets minor and patch versions to 0 (e.g., 1.2.3 -> 2.0.0).
#>
[CmdletBinding()]
param (
    [Parameter(Mandatory = $true, HelpMessage = "The part of the version to bump: 'major', 'minor', or 'patch'")]
    [ValidateSet('major', 'minor', 'patch')]
    [string]$Part
)

# Find the project root directory containing the VERSION file
$scriptPath = if ($MyInvocation.MyCommand.CommandType -eq 'ExternalScript') {
    $MyInvocation.MyCommand.Path
} else {
    (Get-PSCallStack)[0].ScriptName
}
$repoRoot = (git rev-parse --show-toplevel 2>$null)

if ($null -eq $repoRoot) {
    Write-Error "VERSION file not found in any parent directory. Could not determine project root."
    exit 1
}

Set-Location $repoRoot
Write-Host "Project root is: $repoRoot"

# Ensure we are not on the master/main branch
$currentBranch = git rev-parse --abbrev-ref HEAD
if ($currentBranch -in @('master', 'main')) {
    Write-Host "You are on the '$currentBranch' branch. Please switch to a feature branch before running this script."
    exit 1
}

# Get current version
$SNAPSHOT_VERSION = Get-Content "$repoRoot\VERSION" -TotalCount 1
$VERSION = $SNAPSHOT_VERSION -replace "-SNAPSHOT", ""

# Parse version components
if ($VERSION -match "^(\d+)\.(\d+)\.(\d+)") {
    $major = [int]$matches[1]
    $minor = [int]$matches[2]
    $patch = [int]$matches[3]
} else {
    Write-Error "Version string '$VERSION' does not match the expected format X.Y.Z"
    exit 1
}

# Calculate the next version based on the specified part
switch ($Part) {
    'major' {
        $major++
        $minor = 0
        $patch = 0
    }
    'minor' {
        $minor++
        $patch = 0
    }
    'patch' {
        $patch++
    }
}

$NEXT_VERSION = "$major.$minor.$patch"
$NEXT_SNAPSHOT_VERSION = "$NEXT_VERSION-SNAPSHOT"

if ($NEXT_SNAPSHOT_VERSION -notmatch "^\d+\.\d+\.\d+-SNAPSHOT$") {
    Write-Error "Calculated version '$NEXT_SNAPSHOT_VERSION' does not match the expected format X.Y.Z-SNAPSHOT"
    exit 1
}

Write-Host "Current version: $SNAPSHOT_VERSION"
Write-Host "New version: $NEXT_SNAPSHOT_VERSION"

$isWindowsHost = $PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows


# Update VERSION file
$NEXT_SNAPSHOT_VERSION | Set-Content "$repoRoot\VERSION"

# Update pom.xml files using Maven
$mvnCmd = if ($isWindowsHost) { Join-Path $repoRoot "mvnw.cmd" } else { Join-Path $repoRoot "mvnw" }
$mvnArgs = @(
    'versions:set'
    "-DnewVersion=$NEXT_SNAPSHOT_VERSION"
    '-DprocessAllModules'
    '-DgenerateBackupPoms=false'
)
if ($isWindowsHost) {
    & cmd /c $mvnCmd @mvnArgs
} else {
    & $mvnCmd @mvnArgs
}
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven versions:set command failed. Reverting VERSION file."
    $SNAPSHOT_VERSION | Set-Content "$repoRoot\VERSION"
    exit 1
}

# Update root pom.xml's git tag
$pomXmlPath = "$repoRoot\pom.xml"
if (Test-Path $pomXmlPath) {
    ((Get-Content $pomXmlPath -Raw) -replace "<tag>v$VERSION</tag>", "<tag>v$NEXT_VERSION</tag>") | Set-Content $pomXmlPath
}

# Commit changes
$COMMENT = "Bump version to v$($NEXT_VERSION)"
Write-Host "Ready to commit with comment: <$COMMENT>"
$confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
if ($confirm -eq 'Y') {
    git add .
    git commit -m "$COMMENT"
    git push
    Write-Host "Version bumped to $NEXT_VERSION and changes pushed to remote."
} else {
    Write-Host "Operation cancelled. Run 'git checkout .' to revert changes."
}

