#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Tag a new browser4-cli release and push to remote.

.DESCRIPTION
    1. Validates the version string.
    2. Updates the version in sdks/browser4-cli/package.json.
    3. Creates and pushes a git tag "cli-v<version>".
    4. The tag push triggers the publish-cli.yml GitHub Actions workflow,
       which builds, tests, publishes to npm, and creates a GitHub release.

.PARAMETER Version
    Release version (e.g., 0.2.0 or 0.2.0-rc.1).

.EXAMPLE
    .\bin\release\release-cli.ps1 0.2.0
    .\bin\release\release-cli.ps1 0.2.0-rc.1
#>
[CmdletBinding()]
param (
    [Parameter(Mandatory = $true)]
    [string]$Version
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Die([string]$Msg) { Write-Error "❌ $Msg"; exit 1 }

# ── Validate version ──────────────────────────────────────────────────────────

if ($Version -notmatch '^\d+\.\d+\.\d+(-[a-zA-Z0-9._-]+)?$') {
    Die "Invalid version '$Version'. Expected format: X.Y.Z or X.Y.Z-<pre-release>"
}

$Tag = "cli-v$Version"

# ── Project root ──────────────────────────────────────────────────────────────

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppHome   = git -C $ScriptDir rev-parse --show-toplevel
$CliDir    = Join-Path $AppHome 'sdks/browser4-cli'

if (-not (Test-Path "$CliDir/package.json")) {
    Die "sdks/browser4-cli/package.json not found."
}

Set-Location $AppHome

# ── Check working tree ────────────────────────────────────────────────────────

$status = git status --porcelain
if ($status) {
    Die "Uncommitted changes detected. Commit or stash them before releasing."
}

# ── Duplicate tag guard ───────────────────────────────────────────────────────

$localTag = git rev-parse $Tag 2>$null
if ($LASTEXITCODE -eq 0) { Die "Tag '$Tag' already exists locally." }

$remoteTag = git ls-remote --tags origin $Tag
if ($remoteTag) { Die "Tag '$Tag' already exists on remote." }

# ── Bump version in package.json ─────────────────────────────────────────────

Write-Host "📝 Updating sdks/browser4-cli/package.json → $Version"
$pkgPath = "$CliDir/package.json"
$pkg     = Get-Content $pkgPath -Raw | ConvertFrom-Json
$pkg.version = $Version
$pkg | ConvertTo-Json -Depth 10 | Set-Content $pkgPath

# Also update the version constant in src/version.ts
$VersionFile = "$CliDir/src/version.ts"
if (Test-Path $VersionFile) {
    (Get-Content $VersionFile -Raw) -replace "export const VERSION = '.*';", "export const VERSION = '$Version';" |
        Set-Content $VersionFile
    Write-Host "📝 Updated src/version.ts → $Version"
}

# ── Commit version bump ───────────────────────────────────────────────────────

git add "$pkgPath" "$VersionFile" 2>$null
$staged = git diff --cached --name-only
if ($staged) {
    git commit -m "chore(cli): bump version to $Version"
    git push
    Write-Host "✅ Version bump committed and pushed."
} else {
    Write-Host "ℹ️  No version changes to commit (already at $Version)."
}

# ── Create and push tag ───────────────────────────────────────────────────────

Write-Host "🏷️  Creating tag $Tag …"
git tag -a $Tag -m "Release browser4-cli $Version"
git push origin $Tag

Write-Host ""
Write-Host "✅ Tag '$Tag' pushed. The publish-cli workflow will now:"
Write-Host "   1. Build and test the CLI"
Write-Host "   2. Publish @platonai/browser4-cli@$Version to npm"
Write-Host "   3. Create a GitHub Release at https://github.com/platonai/Browser4/releases/tag/$Tag"
