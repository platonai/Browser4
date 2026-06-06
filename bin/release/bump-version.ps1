#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Bumps the project version based on the specified part (major, minor, or patch).

.DESCRIPTION
    This script automates the process of updating the project version. It reads the current version from the VERSION file,
    increments the specified part (major, minor, or patch), and then updates the version number in all relevant files,
    including pom.xml, READMEs, and the VERSION file itself. Finally, it commits the changes to Git.

    Before bumping, by default the script runs a publish-status precheck via check-publish-status.ps1 which verifies:
      1. The current version (vX.Y.Z) is the latest release on GitHub.
      2. The pulsar-bom artifact for the current version is available on Maven Central.
    These checks ensure that you are bumping from a version that has actually been published.
    Use -SkipPrecheck to bypass this verification.

.PARAMETER Part
    Specifies which part of the version to bump. Must be one of 'major', 'minor', or 'patch'.

.PARAMETER SkipPrecheck
    Skips the publish-status precheck. By default the script verifies that the current version
    is the latest GitHub release and is available on Maven Central before bumping.

.EXAMPLE
    .\bump-version.ps1 -Part patch
    Bumps the patch version (e.g., 1.2.3 -> 1.2.4). Runs the publish-status precheck first.

.EXAMPLE
    .\bump-version.ps1 -Part minor
    Bumps the minor version and resets the patch version to 0 (e.g., 1.2.3 -> 1.3.0).

.EXAMPLE
    .\bump-version.ps1 -Part major
    Bumps the major version and resets minor and patch versions to 0 (e.g., 1.2.3 -> 2.0.0).

.EXAMPLE
    .\bump-version.ps1 -Part patch -SkipPrecheck
    Bumps the patch version without running the publish-status precheck.
#>
[CmdletBinding()]
param (
    [Parameter(Mandatory = $true, HelpMessage = "The part of the version to bump: 'major', 'minor', or 'patch'")]
    [ValidateSet('major', 'minor', 'patch')]
    [string]$Part,

    [Parameter(HelpMessage = "Skip the publish-status precheck (GitHub release + Maven Central)")]
    [switch]$SkipPrecheck
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

# ---------------------------------------------------------------
# Precheck: verify the current version has been fully published
# ---------------------------------------------------------------
if (-not $SkipPrecheck) {
    Write-Host ""
    Write-Host "Running publish-status precheck for version v$VERSION..."
    Write-Host "---------------------------------------------------------"

    $checkScript = Join-Path $repoRoot "bin\release\check-publish-status.ps1"
    if (Test-Path $checkScript) {
        & $checkScript -Version $VERSION
        # Check both $LASTEXITCODE (set by exit N) and $? (set on error)
        $precheckFailed = ($LASTEXITCODE -ne 0) -or (-not $?)
        if ($precheckFailed) {
            Write-Host ""
            Write-Error ("Precheck failed: version v$VERSION has not been fully published." `
                + " Ensure the version is the latest GitHub release and pulsar-bom is on Maven Central." `
                + " Use -SkipPrecheck to bypass this check if you know what you're doing.")
            exit 1
        }
    } else {
        Write-Warning "check-publish-status.ps1 not found at '$checkScript'. Skipping precheck."
    }
} else {
    Write-Host ""
    Write-Host "Precheck skipped (-SkipPrecheck specified). Proceeding without publish-status verification."
}
Write-Host "---------------------------------------------------------"
Write-Host ""

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

