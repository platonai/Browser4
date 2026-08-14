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
    Create and push a release tag for the current version. Dry-run by default.

.DESCRIPTION
    Reads the version from the VERSION file, runs prerelease consistency checks
    (delegating to version.mjs), shows the changes since the previous release,
    and creates + pushes a vX.Y.Z tag that triggers the release workflow.

    By default the script runs in DRY RUN mode: it performs all read-only
    checks, previews the tag and release notes, and exits without changing
    anything. Pass -Apply to actually create and push the tag.

    Release notes: by default the script does NOT call an AI agent and uses the
    raw commit list. Pass -Agent auto to opt in with the backend auto-resolved
    (claude, codex, kimi, dsh, or gh copilot — via
    coworker/scripts/workers/agent.ps1), or -Agent <name> to pin a specific
    backend (claude, kimi, codex, dsh, copilot). The commit list since the
    previous tag is sent to the agent to produce structured release notes, used
    as the annotated tag message (unless -message is given) and shown as a
    preview. Falls back to the raw commit list if no agent is available.

.PARAMETER remote
    The git remote to push the tag to (default: "origin").

.PARAMETER message
    Explicit release message for the annotated tag. Takes precedence over
    AI-generated release notes.

.PARAMETER Apply
    Actually create and push the tag. Without this flag the script runs in
    dry-run mode (default).

.PARAMETER DryRun
    Explicit dry-run mode. This is the default; the flag exists for clarity and
    for callers (e.g. monitor-release.ps1) that forward a verbatim flag.

.PARAMETER Agent
    Generate AI release notes using an agent backend. Values: auto (resolve the
    backend via coworker/scripts/workers/agent.ps1: claude, kimi, codex, dsh,
    or gh copilot), or a specific backend name (claude, kimi, codex, dsh,
    copilot). Without this flag the script uses the raw commit list and never
    invokes an AI agent. A pinned backend overrides $env:BROWSER4_AGENT and the
    config.psd1 backend order for this invocation.

.EXAMPLE
    .\bin\release\trigger-release.ps1                       # dry run (preview only)
    .\bin\release\trigger-release.ps1 -Apply                # actually create + push
    .\bin\release\trigger-release.ps1 -Apply -message "Hotfix for login crash"
    .\bin\release\trigger-release.ps1 -Agent auto           # dry run, AI notes (auto backend)
    .\bin\release\trigger-release.ps1 -Agent dsh            # dry run, AI notes via dsh
#>

param(
    [string]$remote = "origin",
    [string]$message = "",
    [switch]$Apply,
    [switch]$DryRun,
    [ValidateSet('auto', 'claude', 'kimi', 'codex', 'dsh', 'copilot')]
    [string]$Agent = ""
)

$ErrorActionPreference = "Stop"

# Dry run is the default. -Apply opts into real execution; -DryRun is an
# explicit (redundant) confirmation of the default.
$isDryRun = $DryRun -or -not $Apply

# AI release notes are opt-in via -Agent. Any non-empty value (auto or a
# pinned backend name) enables them; ValidateSet guards the allowed values.
$useAgent = [bool]$Agent

# -Agent auto leaves the backend to the normal resolution chain
# (config.psd1 order); a pinned name overrides it via $env:BROWSER4_AGENT,
# the canonical override honored first by config.ps1's Get-AgentBackend.
if ($Agent -and $Agent -ne 'auto') {
    $env:BROWSER4_AGENT = $Agent
}

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

# Import common utility script
. (Join-Path $repoRoot "bin" "common" "Util.ps1")

Fix-Encoding-UTF8

Write-Host "Working in: $repoRoot"

if ($isDryRun) {
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Yellow
    Write-Host "  DRY RUN MODE — nothing will be created or pushed." -ForegroundColor Yellow
    Write-Host "  Re-run with -Apply to actually create and push the tag." -ForegroundColor Yellow
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Yellow
}

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
    if (-not $isDryRun) {
        $continue = Read-Host "Continue anyway? (y/n)"
        if ($continue -ne 'y') {
            Write-Host "Cancelled"
            exit 0
        }
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
# Prerelease checks: version consistency across all files (VERSION,
# pom.xml, Cargo.toml, package.json) and next-patch guard against
# the last GitHub release.  Delegates to version.mjs so there is a
# single source of truth for all version-comparison logic.
# ═══════════════════════════════════════════════════════════════════

Write-Host ""
Write-Host "Running prerelease checks (version.mjs prerelease-check)..."

$checkScript = Join-Path $repoRoot "bin" "version.mjs"
if (!(Test-Path $checkScript)) {
    Write-Error "version.mjs not found at: $checkScript"
    exit 1
}

# Capture stdout only — prerelease-check writes JSON to stdout and
# exits 0 (all OK) or 1 (issues found).
$checkOutput = node $checkScript prerelease-check 2>$null
$checkExitCode = $LASTEXITCODE

if ($null -eq $checkExitCode) {
    Write-Error "Failed to run node. Is Node.js installed and on PATH?"
    exit 1
}

if ($null -eq $checkOutput -or ($checkOutput -is [string] -and $checkOutput.Trim() -eq '')) {
    Write-Error "Prerelease check produced no output. Verify node is installed and version.mjs exists at: $checkScript"
    exit 1
}

# Parse JSON output from version.mjs
$parseOk = $true
try {
    if ($checkOutput -is [array]) {
        $checkJson = ($checkOutput -join "`n") | ConvertFrom-Json
    } else {
        $checkJson = $checkOutput | ConvertFrom-Json
    }
} catch {
    $parseOk = $false
    Write-Warning "Could not parse prerelease-check output."
    if ($checkOutput) {
        Write-Host "Raw output:"
        Write-Host $checkOutput
    }
    if (-not $isDryRun) {
        $confirm = Read-Host "Continue anyway? (y/n)"
        if ($confirm -ne 'y') {
            Write-Host "Cancelled"
            exit 0
        }
    }
    Write-Host "Proceeding despite parse failure..."
}

if ($parseOk) {
    if ($checkExitCode -eq 0) {
        # All checks passed
        Write-Host "[OK] All version files consistent (v$($checkJson.currentVersion))"
        if ($checkJson.lastRelease) {
            Write-Host "[OK] v$($checkJson.currentVersion) is the next patch after $($checkJson.lastRelease)"
        } else {
            Write-Host "[OK] First release — no previous release to compare against"
        }
    } else {
        # Issues found — display them clearly and ask for confirmation
        Write-Host ""
        Write-Warning "Prerelease checks found issues:"
        Write-Host ""

        if (-not $checkJson.consistent) {
            Write-Host "  Version inconsistency across files:"
            foreach ($issue in $checkJson.issues) {
                Write-Host "    - $issue"
            }
            Write-Host ""
            Write-Host "  Run 'node bin/version.mjs sync' to fix version mismatches."
            Write-Host ""
        }

        if (-not $checkJson.isNextPatch) {
            Write-Host "  Version is not the next patch after the last release:"
            Write-Host "    Last release:       $($checkJson.lastRelease)"
            Write-Host "    Current version:    v$($checkJson.currentVersion)"
            if ($checkJson.expectedNextPatch) {
                Write-Host "    Expected next patch: v$($checkJson.expectedNextPatch)"
            }
            Write-Host ""
            Write-Host "  Run 'node bin/version.mjs auto' to auto-bump to the next patch."
            Write-Host ""
        }

        if (-not $isDryRun) {
            $confirm = Read-Host "Continue anyway? (y/n)"
            if ($confirm -ne 'y') {
                Write-Host "Cancelled"
                exit 0
            }
        }
        Write-Host "Proceeding despite version issues..."
    }
}

$newTag = "v$version"

# Check if tag already exists
$existingTag = git tag -l $newTag
if ($existingTag) {
    if ($isDryRun) {
        Write-Warning "Tag '$newTag' already exists (would be overwritten with -Apply)."
    } else {
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

# Capture the raw change list (used for AI release notes and as the fallback)
$changesText = ''
if ($prevTag) {
    Write-Host "`nChanges since $prevTag :"
    $changes = git log --oneline --no-merges "$prevTag..HEAD"
    if ($changes) {
        $changesText = ($changes | Out-String).Trim()
        $changes | ForEach-Object { Write-Host "  - $_" }
    } else {
        Write-Host "  No changes"
    }
} else {
    Write-Host "`nRecent commits:"
    $recentCommits = git log --oneline --no-merges -5
    $changesText = ($recentCommits | Out-String).Trim()
    $recentCommits | ForEach-Object { Write-Host "  • $_" }
}

# ═══════════════════════════════════════════════════════════════════
# AI agent helpers — generate release notes via an available agent.
# Reuses coworker/scripts/workers/agent.ps1 (the canonical per-backend
# invocation) when present, so claude/kimi/codex/dsh/copilot all work
# with their correct CLI flags and Windows stdin handling.
# ═══════════════════════════════════════════════════════════════════

# Dot-source the agent helpers at SCRIPT scope. Dot-sourcing inside a
# function scopes the defined functions to that function only, so
# Invoke-Agent would not be visible to Invoke-ReleaseNotesAgent below
# (it failed with: "The term 'Invoke-Agent' is not recognized").
$script:AgentScriptPath = Join-Path $repoRoot 'coworker\scripts\workers\agent.ps1'
$script:AgentHelpersLoaded = $false
if (Test-Path -LiteralPath $script:AgentScriptPath) {
    try {
        . $script:AgentScriptPath
        if (Get-Command Invoke-Agent -ErrorAction SilentlyContinue) {
            $script:AgentHelpersLoaded = $true
        }
    } catch {
        $script:AgentHelpersLoaded = $false
    }
}

$script:ReleaseAgent = @{ Initialized = $false; Backend = ''; Executable = '' }

function Initialize-ReleaseAgent {
    if ($script:ReleaseAgent.Initialized) { return }
    $script:ReleaseAgent.Initialized = $true

    if (-not $script:AgentHelpersLoaded) { return }

    try {
        $command = Get-AgentCommand -RepoRoot $repoRoot -WorkingDirectory $repoRoot
        if ($command -and $command.Executable) {
            $script:ReleaseAgent.Backend = [string]$command.Backend
            $script:ReleaseAgent.Executable = [string]$command.Executable
        }
    } catch {
        # Agent resolution failed — leave the agent state empty (no agent).
    }
}

function Test-ReleaseAgentAvailable {
    Initialize-ReleaseAgent
    if (-not $script:ReleaseAgent.Executable) { return $false }
    return ($null -ne (Get-Command $script:ReleaseAgent.Executable -ErrorAction SilentlyContinue))
}

function Invoke-ReleaseNotesAgent {
    param(
        [string]$Changes,
        [string]$Version
    )

    if (-not (Test-ReleaseAgentAvailable)) { return $null }
    if ([string]::IsNullOrWhiteSpace($Changes)) { return $null }

    $prompt = @"
Generate release notes for a software release.

Repository: Browser4
Version: $Version

Analyze the commit list below and produce well-structured release notes
as Markdown.

Rules:
- Start with a one-paragraph summary of the highlights of this release.
- Then group changes into categories by conventional-commit type:
  Features (feat), Fixes (fix), Performance (perf), Refactor (refactor),
  Documentation (docs), Tests (test).
- Skip pure chore/ci/style/revert commits unless user-visible.
- One concise bullet per meaningful change; no commit hashes.
- Omit empty categories.
- Output ONLY the release notes Markdown body — no code fences, no
  preamble such as "Here are the release notes".

Commit list (most recent first):
---
$Changes
---
"@

    try {
        $notes = Invoke-Agent -Prompt $prompt -RepoRoot $repoRoot -WorkingDirectory $repoRoot -CaptureOutput -TimeoutSeconds 300
        if ($notes -is [string] -and -not [string]::IsNullOrWhiteSpace($notes)) {
            # Strip any stray leading/trailing code fences the agent may emit.
            $cleaned = $notes -replace '^\s*```[a-zA-Z]*\s*', '' -replace '\s*```\s*$', ''
            $cleaned = $cleaned.Trim()
            if ($cleaned) { return $cleaned }
        }
    } catch {
        Write-Warning "Agent release-notes generation failed: $($_.Exception.Message)"
    }

    return $null
}

# ── Generate release notes (agent if available, otherwise raw commits) ──
$releaseNotes = ''
$agentUsed = $false

if ($useAgent) {
    Write-Host ""
    Write-Host "Checking for an AI agent to generate release notes..." -ForegroundColor DarkGray
    if ($changesText) {
        $releaseNotes = Invoke-ReleaseNotesAgent -Changes $changesText -Version $newTag
        if ($releaseNotes) {
            $agentUsed = $true
            Write-Host "  Release notes generated with $($script:ReleaseAgent.Backend)." -ForegroundColor Green
        }
    }
}

if ($agentUsed) {
    Write-Host ""
    Write-Host "──────────────────────────────────────────────────────────" -ForegroundColor Cyan
    Write-Host "  AI-generated release notes (preview)" -ForegroundColor Cyan
    Write-Host "──────────────────────────────────────────────────────────" -ForegroundColor Cyan
    Write-Host $releaseNotes
    Write-Host "──────────────────────────────────────────────────────────" -ForegroundColor Cyan
} else {
    if (-not $useAgent) {
        Write-Host "  AI release notes disabled by default — pass -Agent auto to enable." -ForegroundColor DarkGray
    } elseif (-not $changesText) {
        Write-Host "  No changes to summarize — skipping AI release notes." -ForegroundColor DarkGray
    } else {
        Write-Host "  No AI agent available — falling back to the raw commit list." -ForegroundColor DarkGray
    }
    $releaseNotes = $changesText
}

# ── Resolve the effective tag message ──────────────────────────────────
# Priority: explicit -message > AI-generated notes > (prompt / lightweight).
$effectiveMessage = $message
if ([string]::IsNullOrWhiteSpace($effectiveMessage) -and $agentUsed) {
    $effectiveMessage = $releaseNotes
}

$tagType = if ([string]::IsNullOrWhiteSpace($effectiveMessage)) { "lightweight" } else { "annotated" }

# ── DRY RUN: preview only ──────────────────────────────────────────────
if ($isDryRun) {
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    Write-Host "  DRY RUN — nothing was created or pushed" -ForegroundColor Yellow
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    Write-Host "  Version:       $version"
    Write-Host "  Tag:           $newTag"
    Write-Host "  Tag type:      $tagType"
    Write-Host "  Remote:        $remote"
    if ($existingTag) {
        Write-Host "  Note:          tag '$newTag' already exists (would be overwritten)" -ForegroundColor Yellow
    }
    if ($agentUsed) {
        Write-Host "  Release notes: AI-generated ($($script:ReleaseAgent.Backend))" -ForegroundColor Green
    } else {
        Write-Host "  Release notes: raw commit list"
    }
    Write-Host ""
    Write-Host "  Run with -Apply to actually create and push the tag." -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow

    # Emit the tag name so monitor-release.ps1 can report what would have
    # been triggered (it detects dry-run separately and will not monitor).
    Write-Output $newTag
    exit 0
}

# ── APPLY: prompt for message if still missing, then create + push ──────
if ([string]::IsNullOrWhiteSpace($effectiveMessage)) {
    Write-Host ""
    $effectiveMessage = Read-Host "Enter release message (optional, press Enter to skip)"
    $tagType = if ([string]::IsNullOrWhiteSpace($effectiveMessage)) { "lightweight" } else { "annotated" }
}

# Confirm creation
Write-Host ""
$confirm = Read-Host "Create and push $tagType tag '$newTag'? (y/n)"
if ($confirm -ne 'y') {
    Write-Host "Cancelled"
    exit 0
}

# Create and push tag
try {
    # Create annotated tag if message provided, otherwise lightweight tag
    if ([string]::IsNullOrWhiteSpace($effectiveMessage)) {
        git tag $newTag
        Write-Host "Created lightweight tag: $newTag"
    } else {
        git tag -a $newTag -m $effectiveMessage
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
