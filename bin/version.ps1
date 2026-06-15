#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

$VERSION = "v$(Get-Content "$repoRoot/VERSION")"

if ($args.Count -gt 0 -and $args[0] -eq "-v") {
    # dynamically pull more interesting stuff from latest git commit
    $HASH = (git show-ref --head --hash=7 head).Substring(0, 7)      # first 7 letters of hash should be enough; that's what GitHub uses
    $BRANCH = (git rev-parse --abbrev-ref HEAD)
    $DATE = (git log -1 --pretty=%ad --date=short)

    # Return the version string used to describe this version of Metabase.
    Write-Output "$VERSION $HASH $BRANCH $DATE"
} else {
    Write-Output $VERSION
}
