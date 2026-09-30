#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Avoid Windows-only cmdlets; keep to core PowerShell.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    VERSION-file parsing shared by the CI trigger/release scripts.

.DESCRIPTION
    The repository VERSION file has three shapes:

        4.13.18-SNAPSHOT   development version — '-SNAPSHOT' is a build marker
                           and is dropped when building a tag base
        4.13.18            released version
        4.14.0-rc.5        release candidate — the pre-release label is PART of
                           the version a CI tag must name (ci.yml accepts
                           vX.Y.Z-rc.N-ci.M as well as vX.Y.Z-ci.N)

    Only `-SNAPSHOT` is stripped: everything after the numeric
    major.minor.patch core is preserved as the pre-release label and never
    reaches an [int] cast.  Splitting `4.14.0-rc.5` on '.' yields the fragment
    `0-rc`, which is how trigger-ci.ps1 failed on rc lines with

        Cannot convert value "0-rc" to type "System.Int32"

    when it derived the patch number from the raw split.

.EXAMPLE
    . ./lib/VersionInfo.ps1
    (Get-VersionInfo '4.14.0-rc.5').Full        # 4.14.0-rc.5
    (Get-VersionInfo '4.14.0-rc.5').MajorMinor  # 4.14
    (Get-VersionInfo '4.13.18-SNAPSHOT').Full   # 4.13.18
#>

function Get-VersionInfo {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true, Position = 0)]
        [AllowEmptyString()]
        [string]$Version
    )

    $full = ($Version -replace '-SNAPSHOT$', '').Trim()

    # Numeric core: major.minor, optionally with a patch. Anything after it is a
    # pre-release/build label and never reaches an [int] cast.
    $isNumericCore = $full -match '^(\d+\.\d+(?:\.\d+)?)'
    $core = if ($isNumericCore) { $matches[1] } else { $full }
    $coreParts = if ($isNumericCore) { $core -split '\.' } else { @() }

    # A malformed VERSION yields an empty MajorMinor so callers abort with their
    # own clear message instead of inventing a version like "abc.0".
    $majorMinor = if ($coreParts.Count -ge 2) { "$($coreParts[0]).$($coreParts[1])" } else { '' }
    $patch = if ($coreParts.Count -ge 3) { [int]$coreParts[2] } else { 0 }

    [pscustomobject]@{
        # Version used as the tag base, without the leading 'v' (4.14.0-rc.5).
        Full         = $full
        # Numeric major.minor.patch core (4.14.0); equals Full when malformed.
        Core         = $core
        # Suffix after the core, '' when there is none ('-rc.5').
        PreRelease   = if ($isNumericCore) { $full.Substring($core.Length) } else { '' }
        # Major.minor line, the value branch names and release lines use (4.14);
        # '' when the VERSION is not a numeric major.minor.patch version.
        MajorMinor   = $majorMinor
        Patch        = $patch
        IsPreRelease = $isNumericCore -and $full.Length -gt $core.Length
    }
}
