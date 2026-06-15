# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

<![CDATA[#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Download all assets from a GitHub release for platonai/Browser4.

.DESCRIPTION
    Downloads every asset attached to a GitHub release. By default it pulls the
    latest release (including prereleases).  A specific tag, an output directory,
    and a GITHUB_TOKEN for authenticated requests are all supported.

.PARAMETER Tag
    Release tag to download (e.g. "v4.11.0").
    Defaults to the latest release.

.PARAMETER OutputDir
    Directory to save downloaded assets (default: current directory).

.PARAMETER Help
    Show this help message.

.EXAMPLE
    .\download-release-assets.ps1
    # Download latest release assets to the current directory.

.EXAMPLE
    .\download-release-assets.ps1 -Tag v4.10.0
    # Download assets for tag v4.10.0.

.EXAMPLE
    .\download-release-assets.ps1 -OutputDir .\downloads
    # Download latest release assets to .\downloads\.
#>

[CmdletBinding()]
param(
    [string]$Tag = "",
    [string]$OutputDir = ".",
    [switch]$Help
)

# ------------------------------------------
# Help  (manual so it prints to stdout like the bash version)
# ------------------------------------------
if ($Help) {
    $me = [System.IO.Path]::GetFileName($PSCommandPath)
    @"
Usage: $me [-Tag TAG] [-OutputDir DIR]

Download all release assets from the platonai/Browser4 GitHub repository.

Options:
  -Tag TAG          Release tag to download (e.g. v4.11.0).
                    Defaults to the latest release.
  -OutputDir DIR    Directory to save downloaded assets (default: current directory).
  -Help             Show this help message.

Examples:
  $me                              # Download latest release assets to current dir
  $me -Tag v4.10.0                 # Download assets for tag v4.10.0
  $me -OutputDir .\downloads       # Download latest to .\downloads\
"@
    exit 0
}

$ErrorActionPreference = "Stop"

$Repo = "platonai/Browser4"

# ------------------------------------------
# Prerequisites
# ------------------------------------------
function Test-CommandExists($cmd) {
    return [bool](Get-Command $cmd -ErrorAction SilentlyContinue)
}

# PowerShell has ConvertFrom-Json built in — no jq needed.
# curl.exe is preferred over the Invoke-WebRequest alias so behavior matches
# the bash script (silent, fail-on-error, follow redirects).
if (-not (Test-CommandExists curl.exe)) {
    Write-Error "'curl.exe' is required but not found in PATH."
    exit 1
}

# ------------------------------------------
# Helpers
# ------------------------------------------
function Format-FileSize([long]$bytes) {
    # Mimics `numfmt --to=iec --suffix=B`
    $suffixes = @("B", "KB", "MB", "GB", "TB", "PB")
    $i = 0
    $size = [double]$bytes
    while ($size -ge 1024 -and $i -lt $suffixes.Count - 1) {
        $size /= 1024
        $i++
    }
    if ($i -eq 0) {
        return "$bytes bytes"
    }
    return "{0:N1}{1}" -f $size, $suffixes[$i]
}

function Invoke-Curl {
    <#
    .SYNOPSIS
        Wraps curl.exe so the token header is added when GITHUB_TOKEN is set.
    #>
    param(
        [string[]]$Arguments
    )
    $argsList = [System.Collections.ArrayList]@($Arguments)
    if ($env:GITHUB_TOKEN) {
        [void]$argsList.Insert(1, "-H")
        [void]$argsList.Insert(2, "Authorization: Bearer $env:GITHUB_TOKEN")
    }
    & curl.exe @argsList
    if ($LASTEXITCODE -ne 0) {
        throw "curl exited with code $LASTEXITCODE"
    }
}

# ------------------------------------------
# Resolve the release
# ------------------------------------------
if ($Tag) {
    Write-Host "🔍 Fetching release for tag: $Tag"
    $releaseUrl = "https://api.github.com/repos/$Repo/releases/tags/$Tag"
    try {
        $response = Invoke-Curl -Arguments @("-sSf", "-H", "Accept: application/vnd.github+json", $releaseUrl)
    } catch {
        Write-Error @"
Failed to fetch release for tag '$Tag'.
Make sure the tag exists and you have network access.
For private repos or rate-limit issues, set GITHUB_TOKEN in your environment.
"@
        exit 1
    }
} else {
    Write-Host "🔍 Fetching latest release..."
    try {
        $response = Invoke-Curl -Arguments @("-sSf", "-H", "Accept: application/vnd.github+json",
            "https://api.github.com/repos/$Repo/releases?per_page=1")
    } catch {
        Write-Error @"
Failed to fetch latest release.
For private repos or rate-limit issues, set GITHUB_TOKEN in your environment.
"@
        exit 1
    }
    # Extract first element from the array; round-trip through JSON so the
    # uniform $response | ConvertFrom-Json below works for both code paths.
    $releases = $response | ConvertFrom-Json
    $response = $releases[0] | ConvertTo-Json -Depth 10 -Compress
}

$release = $response | ConvertFrom-Json

# Verify we got a valid release
$releaseName = if ($release.name) { $release.name } else { $release.tag_name }
if (-not $releaseName) {
    $msg = if ($Tag) { "Could not find a release for tag '$Tag'." } else { "No releases found in the repository." }
    Write-Error $msg
    exit 1
}

$releaseTag = $release.tag_name
$assets = @($release.assets)
$assetCount = $assets.Count

Write-Host "✅ Release found: $releaseName (tag: $releaseTag)"
Write-Host "📦 Assets: $assetCount"

if ($assetCount -eq 0) {
    Write-Host "No assets to download for this release."
    exit 0
}

# ------------------------------------------
# Create output directory
# ------------------------------------------
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$resolvedDir = (Resolve-Path $OutputDir).Path

# ------------------------------------------
# Download assets
# ------------------------------------------
Write-Host ""
Write-Host "⬇️  Downloading to: $resolvedDir"
Write-Host ""

$downloadCount = 0
$failCount = 0

foreach ($asset in $assets) {
    $name = $asset.name
    $url  = $asset.browser_download_url
    $size = $asset.size
    $sizeHuman = Format-FileSize $size

    Write-Host "  ⏳ $name ($sizeHuman)"

    $outPath = Join-Path $OutputDir $name
    try {
        Invoke-Curl -Arguments @("-sSfL", "-o", $outPath, $url)
        Write-Host "     ✅ Downloaded"
        $downloadCount++
    } catch {
        Write-Error "     ❌ Failed to download $name"
        $failCount++
    }
}

# ------------------------------------------
# Summary
# ------------------------------------------
Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "Download complete: $downloadCount succeeded, $failCount failed"
Write-Host "Release: $releaseTag"
Write-Host "Location: $resolvedDir"

if ($failCount -gt 0) {
    Write-Host ""
    Write-Host "⚠️  Some assets failed to download."
    Write-Host "   Retry with GITHUB_TOKEN set if you hit rate limits,"
    Write-Host "   or pass a specific -Tag if the release was incomplete."
    exit 1
}

exit 0
]]>