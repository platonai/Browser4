#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
Trigger the browser4-cli release workflow (release-cli.yml).

.DESCRIPTION
Two modes:
  Tag mode (default): creates and pushes a v{version}-cli tag, which triggers
    release-cli.yml via the on.push.tags trigger.
  Dispatch mode (-Dispatch): uses `gh workflow run` to trigger the workflow
    directly without creating a tag.

.PARAMETER Version
CLI version to release (default: from cli/VERSION-CLI).

.PARAMETER DryRun
Tag as v{version}-cli_dry_run.N. Auto-increments the dry_run counter.

.PARAMETER Dispatch
Use `gh workflow run` instead of creating a tag.

.PARAMETER SkipBinaryBuild
Skip building CLI binaries (dispatch mode only).

.PARAMETER Ref
Branch/tag/commit to run the workflow from (dispatch mode only).

.PARAMETER Repo
GitHub owner/repo (default: from git remote origin).

.PARAMETER Yes
Skip confirmation prompts.

.EXAMPLE
.\bin\release\trigger-cli-release-action.ps1
Tag mode with the current version from cli/VERSION-CLI.

.EXAMPLE
.\bin\release\trigger-cli-release-action.ps1 -DryRun
Create a dry_run tag and push it.

.EXAMPLE
.\bin\release\trigger-cli-release-action.ps1 -Dispatch
Trigger via workflow_dispatch without creating a tag.

.EXAMPLE
.\bin\release\trigger-cli-release-action.ps1 -Version 0.2.0 -Dispatch -SkipBinaryBuild
Dispatch with a specific version and skip the binary build step.
#>

param(
    [string]$Version = "",
    [switch]$DryRun = $false,
    [switch]$Dispatch = $false,
    [switch]$SkipBinaryBuild = $false,
    [string]$Ref = "",
    [string]$Repo = "",
    [switch]$Yes = $false
)

$ErrorActionPreference = "Stop"

# ──────────────────────────────────────────────
# Resolve repository root
# ──────────────────────────────────────────────
$repoRoot = (git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Error "Could not locate repository root"
    exit 1
}
Set-Location $repoRoot

$versionCliFile = Join-Path $repoRoot "cli/VERSION-CLI"
if (-not (Test-Path $versionCliFile)) {
    Write-Error "Could not find cli/VERSION-CLI at $versionCliFile"
    exit 1
}

# ──────────────────────────────────────────────
# Resolve version (cli/VERSION-CLI is the single source of truth)
# ──────────────────────────────────────────────
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = (Get-Content $versionCliFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($Version)) {
        Write-Error "cli/VERSION-CLI is empty"
        exit 1
    }
}

# Validate version format
if ($Version -notmatch '^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$') {
    Write-Error "Invalid version format: $Version (expected X.Y.Z or X.Y.Z-suffix.N)"
    exit 1
}

# ──────────────────────────────────────────────
# Check npm registry — local version must be higher than what's published
# ──────────────────────────────────────────────
$PACKAGE_NAME = "browser4-cli"
Write-Host "Checking npm registry for $PACKAGE_NAME ..."

$npmVersion = $null
try {
    $raw = npm view "$PACKAGE_NAME" version 2>$null
    if ($raw) {
        $npmVersion = ($raw -split '\s+')[0].Trim()
    }
} catch {
    # npm view failed — package may not be published yet, which is fine
}

if ($npmVersion -and $npmVersion -match '^\d+\.\d+\.\d+') {
    Write-Host "  Local:  $Version"
    Write-Host "  npm:    $npmVersion"

    # Extract base X.Y.Z for comparison (strip suffixes like -alpha.1, -SNAPSHOT)
    if ($Version -match '^(\d+\.\d+\.\d+)') { $localBase = $matches[1] } else { $localBase = $Version }
    if ($npmVersion -match '^(\d+\.\d+\.\d+)') { $npmBase = $matches[1] } else { $npmBase = $npmVersion }

    try {
        $localVer = [version]$localBase
        $npmVer = [version]$npmBase

        if ($localVer -lt $npmVer) {
            Write-Error @"
Local version ($Version) is LOWER than the published npm version ($npmVersion).
Bump cli/VERSION-CLI to a version higher than $npmVersion before releasing.
"@
            exit 1
        } elseif ($localVer -eq $npmVer) {
            Write-Warning "Local version ($Version) matches the published npm version ($npmVersion)."
            Write-Warning "The npm publish step will be skipped unless the version has changed."
            if (-not $Yes) {
                $confirm = Read-Host "Continue anyway? (y/N)"
                if ($confirm -ne 'y' -and $confirm -ne 'Y') {
                    Write-Host "Cancelled."
                    exit 0
                }
            }
        } else {
            Write-Host "  ✓ Local version is newer than npm" -ForegroundColor Green
        }
    } catch {
        Write-Warning "Could not compare versions numerically ($localBase vs $npmBase). Proceeding."
    }
} else {
    Write-Host "  No published version found on npm (first release?)" -ForegroundColor Yellow
}

# ──────────────────────────────────────────────
# Sync version to dependent files (package.json, Cargo.toml, Cargo.lock)
# ──────────────────────────────────────────────
Write-Host ""
Write-Host "Syncing version from cli/VERSION-CLI to all dependent files ..." -ForegroundColor Cyan
$syncScript = Join-Path $repoRoot "cli/scripts/sync-version.js"
if (Test-Path $syncScript) {
    node $syncScript
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "sync-version.js exited with code $LASTEXITCODE"
    }
} else {
    Write-Warning "sync-version.js not found at $syncScript — skipping sync"
}

# ──────────────────────────────────────────────
# Resolve repository
# ──────────────────────────────────────────────
if ([string]::IsNullOrWhiteSpace($Repo)) {
    $remoteUrl = git config --get remote.origin.url 2>$null
    if ($remoteUrl -match 'github\.com[:/](.+?)(\.git)?$') {
        $Repo = $matches[1]
    } elseif ($env:GITHUB_REPOSITORY) {
        $Repo = $env:GITHUB_REPOSITORY
    } else {
        Write-Error "Could not determine GitHub repository. Use -Repo owner/name"
        exit 1
    }
}

# ──────────────────────────────────────────────
# Build tag name (tag mode only)
# ──────────────────────────────────────────────
if ($DryRun) {
    $existing = git tag -l "v${Version}-cli_dry_run.*" 2>$null
    if ($existing) {
        $lastNum = ($existing | Sort-Object { [int]($_ -split '\.')[-1] } | Select-Object -Last 1) -split '\.' | Select-Object -Last 1
        $nextNum = [int]$lastNum + 1
    } else {
        $nextNum = 1
    }
    $Tag = "v${Version}-cli_dry_run.${nextNum}"
} else {
    $Tag = "v${Version}-cli"
}

# ──────────────────────────────────────────────
# Check for existing tag (tag mode)
# ──────────────────────────────────────────────
if (-not $Dispatch) {
    $existingSha = git rev-parse --verify "refs/tags/$Tag" 2>$null
    if ($existingSha) {
        $shortSha = git rev-parse --short "refs/tags/$Tag"
        Write-Warning "Tag '$Tag' already exists (points to $shortSha)."

        if (-not $Yes) {
            $confirm = Read-Host "Delete existing tag and recreate? (y/N)"
            if ($confirm -ne 'y' -and $confirm -ne 'Y') {
                Write-Host "Cancelled."
                exit 0
            }
        }

        git tag -d $Tag
        git push origin --delete $Tag 2>$null
        Write-Host "Deleted existing tag: $Tag"
    }
}

# ──────────────────────────────────────────────
# Display summary and confirm
# ──────────────────────────────────────────────
Write-Host ""
Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  browser4-cli Release"                     -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Repository:   $Repo"
Write-Host "  CLI Version:  $Version"
Write-Host "  Mode:         $(if ($Dispatch) { 'workflow_dispatch' } else { 'tag push' })"
Write-Host "  Dry Run:      $DryRun"
if (-not $Dispatch) {
    $currentBranch = git rev-parse --abbrev-ref HEAD
    $currentSha = git rev-parse --short HEAD
    Write-Host "  Tag:          $Tag"
    Write-Host "  Current ref:  $currentSha ($currentBranch)"
}
if ($Dispatch) {
    Write-Host "  Skip build:   $SkipBinaryBuild"
    if ($Ref) { Write-Host "  Ref:          $Ref" }
}
Write-Host ""

if (-not $Yes) {
    $confirm = Read-Host "Proceed? (y/N)"
    if ($confirm -ne 'y' -and $confirm -ne 'Y') {
        Write-Host "Cancelled."
        exit 0
    }
    Write-Host ""
}

# ──────────────────────────────────────────────
# Execute
# ──────────────────────────────────────────────
if ($Dispatch) {
    # ── workflow_dispatch mode ──
    Write-Host "▶ Triggering release-cli.yml via workflow_dispatch ..." -ForegroundColor Green
    Write-Host ""

    $ghArgs = @(
        'workflow', 'run', 'release-cli.yml',
        '--repo', $Repo,
        '--ref', $(if ($Ref) { $Ref } else { git rev-parse --abbrev-ref HEAD })
    )

    if ($DryRun)           { $ghArgs += '-f'; $ghArgs += 'dry_run=true' }
    if ($SkipBinaryBuild)  { $ghArgs += '-f'; $ghArgs += 'skip_binary_build=true' }

    gh @ghArgs

    Write-Host ""
    Write-Host "✓ Workflow dispatched." -ForegroundColor Green
    Write-Host "  Monitor: gh run list --repo $Repo --workflow release-cli.yml"
} else {
    # ── Tag push mode ──
    Write-Host "▶ Creating tag: $Tag ..." -ForegroundColor Green

    $tagMsg = "browser4-cli v${Version}"
    if ($DryRun) { $tagMsg += " (dry-run)" }

    git tag -a $Tag -m $tagMsg
    Write-Host "  Created local tag."

    Write-Host "▶ Pushing tag to origin ..."
    git push origin $Tag

    Write-Host ""
    Write-Host "✓ Tag '$Tag' pushed to origin." -ForegroundColor Green
    Write-Host "  Workflow:  https://github.com/$Repo/actions/workflows/release-cli.yml"
    Write-Host "  Release:   https://github.com/$Repo/releases/tag/$Tag (once complete)"
}

Write-Host ""
Write-Host "Done."
