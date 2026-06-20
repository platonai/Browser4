# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
E1 - Version consistency: verifies version alignment across key files.

.DESCRIPTION
Checks that the project version is consistent across:
  - VERSION file
  - pom.xml (<version> element of the project, not the parent)
  - cli/browser4-cli/Cargo.toml (validates semver, independent CLI versioning)
  - cli/package.json (version, if exists)

Also verifies SNAPSHOT suffix is consistent between VERSION and pom.xml.

.OUTPUTS
Standard maintenance result object.
#>

param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "E1" -Name "Version Consistency"
$repoRoot = Get-RepositoryRoot

# Read VERSION file
$versionPath = Join-Path $repoRoot "VERSION"
if (-not (Test-Path $versionPath)) {
    Add-MaintenanceResult -Result $result -Item "VERSION" -Status "error" -Message "VERSION file not found"
    Set-MaintenanceResultSummary -Result $result
    $result
    return
}
$versionFileVersion = (Get-Content $versionPath -Raw).Trim()
Add-MaintenanceResult -Result $result -Item "VERSION" -Status "passed" -Message $versionFileVersion

# Read pom.xml version (project version, not parent)
$pomPath = Join-Path $repoRoot "pom.xml"
$pomVersion = ""
if (Test-Path $pomPath) {
    $pomContent = Get-Content $pomPath -Raw
    # Strip parent block to avoid matching the parent <version>
    $pomWithoutParent = $pomContent -replace '(?s)<parent>.*?</parent>', ''
    if ($pomWithoutParent -match '<version>([^<]+)</version>') {
        $pomVersion = $matches[1]
        if ($pomVersion -eq $versionFileVersion) {
            Add-MaintenanceResult -Result $result -Item "pom.xml" -Status "passed" -Message $pomVersion
        }
        else {
            Add-MaintenanceResult -Result $result -Item "pom.xml" -Status "failed" -Message "$pomVersion (expected $versionFileVersion)"
        }
    }
    else {
        Add-MaintenanceResult -Result $result -Item "pom.xml" -Status "error" -Message "Cannot parse <version>"
    }
}
else {
    Add-MaintenanceResult -Result $result -Item "pom.xml" -Status "error" -Message "File not found"
}

# Read Cargo.toml version (independent CLI versioning)
$cargoPath = Join-Path $repoRoot "cli\browser4-cli\Cargo.toml"
if (Test-Path $cargoPath) {
    $cargoContent = Get-Content $cargoPath -Raw
    if ($cargoContent -match '\[package\][\s\S]*?version\s*=\s*"([^"]+)"') {
        $cargoVersion = $matches[1]
        # CLI has independent versioning; just validate it's valid semver
        if ($cargoVersion -match '^\d+\.\d+\.\d+') {
            Add-MaintenanceResult -Result $result -Item "cli/Cargo.toml" -Status "passed" -Message "$cargoVersion (independent CLI version)"
        }
        else {
            Add-MaintenanceResult -Result $result -Item "cli/Cargo.toml" -Status "failed" -Message "$cargoVersion (not valid semver)"
        }
    }
    else {
        Add-MaintenanceResult -Result $result -Item "cli/Cargo.toml" -Status "error" -Message "Cannot parse package.version"
    }
}
else {
    Add-MaintenanceResult -Result $result -Item "cli/Cargo.toml" -Status "skipped" -Message "File not found"
}

# Check SNAPSHOT consistency
$versionIsSnapshot = $versionFileVersion -match '-SNAPSHOT$'
$pomIsSnapshot = $pomVersion -match '-SNAPSHOT$'
if ($pomVersion -and ($versionIsSnapshot -ne $pomIsSnapshot)) {
    Add-MaintenanceResult -Result $result -Item "SNAPSHOT consistency" -Status "failed" -Message "VERSION and pom.xml disagree on SNAPSHOT status"
}
else {
    Add-MaintenanceResult -Result $result -Item "SNAPSHOT consistency" -Status "passed" -Message $(if ($versionIsSnapshot) { "SNAPSHOT" } else { "RELEASE" })
}

Set-MaintenanceResultSummary -Result $result
$result
