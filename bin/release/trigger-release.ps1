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

    main is the single release source: the tag must be created from the latest
    origin/main commit. The script verifies HEAD matches origin/main and warns
    (asking for confirmation in -Apply mode) when it does not — release.yml
    hard-fails any release whose tag does not point at the latest main, so the
    workflow never rewrites main to match a tag.

    By default the script runs in DRY RUN mode: it performs all read-only
    checks, previews the tag and release notes, and exits without changing
    anything. Pass -Apply to actually create and push the tag.

    Release notes: by default the script does NOT call an AI agent. Notes are
    built from the commit list into categorized sections (Features, Fixes,
    Performance, ...; chore/ci/style/revert/build and version-bump commits are
    skipped). Pass -Agent auto to opt in: the AI agent generates ONLY the
    "What's New" highlights section on top of those sections (backend
    auto-resolved via coworker/scripts/workers/agent.ps1: claude, codex, kimi,
    dsh, or gh copilot), or -Agent <name> to pin a specific backend (claude,
    kimi, codex, dsh, copilot). The combined notes are used as the annotated
    tag message (unless -message is given) and shown as a preview.

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

# ── Non-interactive confirmation ────────────────────────────────────
# Every prompt routes through Confirm-Step. When BROWSER4_RELEASE_YES
# is set (CI / automation / non-TTY shells), all prompts auto-confirm
# (or auto-skip for the optional release message) instead of calling
# Read-Host, which fails in NonInteractive mode.
function Confirm-Step {
    param([string]$Prompt, [string]$Default = '')
    if ($env:BROWSER4_RELEASE_YES) {
        if ($Default) { return $Default }
        return 'y'
    }
    return Read-Host $Prompt
}

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
        $continue = Confirm-Step "Continue anyway? (y/n)"
        if ($continue -ne 'y') {
            Write-Host "Cancelled"
            exit 0
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# Main-branch guard: releases must be tagged from the latest
# origin/main. main is the single release source — release.yml verifies
# the tag points at the latest origin/main and aborts the workflow
# otherwise. Fail fast here instead of pushing a tag that CI will reject.
# ═══════════════════════════════════════════════════════════════════

Write-Host ""
Write-Host "Verifying HEAD is the latest $remote/main ..."
git fetch $remote main 2>$null

$headSha = git rev-parse HEAD
$mainSha = git rev-parse "$remote/main" 2>$null

if ($null -eq $mainSha -or -not $mainSha) {
    Write-Warning "Could not resolve $remote/main (fetch failed?). Skipping main-branch check."
} elseif ($headSha -ne $mainSha) {
    Write-Warning "HEAD ($headSha) does not match $remote/main ($mainSha)."
    Write-Warning "Releases must be tagged from the latest main commit — release.yml will abort the workflow if the tag is off main."
    Write-Warning "Run 'git checkout main && git pull' (or push your commits to main) before tagging."
    if (-not $isDryRun) {
        $continue = Confirm-Step "Continue anyway? (y/n)"
        if ($continue -ne 'y') {
            Write-Host "Cancelled"
            exit 0
        }
    }
} else {
    Write-Host "[OK] HEAD is the latest $remote/main ($mainSha)"
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
        $confirm = Confirm-Step "Continue anyway? (y/n)"
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
            $confirm = Confirm-Step "Continue anyway? (y/n)"
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

        $confirm = Confirm-Step "Do you want to overwrite it? (y/n)"
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

    # Release scale stats for the prompt (match the shown commit list).
    $statsText = ''
    if ($prevTag) {
        $commitCount = (git rev-list --count --no-merges "$prevTag..HEAD" 2>$null) -as [int]
        $contributorCount = ((git shortlog -sn --no-merges "$prevTag..HEAD" 2>$null) | Where-Object { $_.Trim() } | Measure-Object).Count
        if ($commitCount -gt 0) { $statsText = "$commitCount commits from $contributorCount contributors" }
    }

    $prompt = @"
Write the "What's New" highlights section for a software release.

Repository: Browser4
Version: $Version
Release stats: $statsText

Analyze the commit list below and write a concise "What's New" section
summarizing this release for end users.

Style:
- English, concise, user-facing and scannable: an optional one-sentence
  lead followed by 3-5 bullet points.
- Lead with the biggest user-visible wins. Mention the release scale
  (commits/contributors) only if it reads naturally.
- Do not restate the version or release date (the release title already
  has them) and do not repeat the categorized change list — focus on
  value and impact instead.
- If any change affects install or upgrade steps, call it out explicitly.
- No commit hashes; no markdown headings; no code fences; no preamble
  such as "Here are the release notes".

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

# ── Build commit-derived release note sections (deterministic) ─────────
# Classifies each commit by conventional-commit type into categorized
# markdown sections. Chore/ci/style/revert/build commits and version-bump
# noise are skipped; non-conventional subjects land in "Other".
function Get-ReleaseNoteSections {
    param(
        [string]$Changes
    )

    if ([string]::IsNullOrWhiteSpace($Changes)) { return '' }

    $sectionTitles = @{
        feat     = 'Features'
        fix      = 'Fixes'
        perf     = 'Performance'
        refactor = 'Refactor'
        docs     = 'Documentation'
        test     = 'Tests'
    }
    $skippedTypes = @('chore', 'ci', 'style', 'revert', 'build')
    $otherTitle = 'Other'

    $groups = @{}
    foreach ($line in ($Changes -split "`r?`n")) {
        $subject = $line -replace '^\s*[0-9a-f]{7,40}\s+', ''
        if ([string]::IsNullOrWhiteSpace($subject)) { continue }

        if ($subject -match '^(?<type>[a-zA-Z]+)(?:\([^)]*\))?:\s*(?<desc>.*)$') {
            $type = $matches['type'].ToLower()
            $desc = $matches['desc'].Trim()
            if ($type -in $skippedTypes -or -not $desc) { continue }
            $section = if ($sectionTitles.ContainsKey($type)) { $sectionTitles[$type] } else { $otherTitle }
        } else {
            $desc = $subject.Trim()
            if (-not $desc) { continue }
            # Skip version-bump noise (e.g. "Auto-bump version to X.Y.Z-SNAPSHOT").
            if ($desc -match '^(auto-?bump|bump version|prepare release|release prep)') { continue }
            $section = $otherTitle
        }

        if (-not $groups.ContainsKey($section)) {
            $groups[$section] = [System.Collections.Generic.List[string]]::new()
        }
        $groups[$section].Add($desc)
    }

    $order = @('Features', 'Fixes', 'Performance', 'Refactor', 'Documentation', 'Tests', 'Other')
    $sb = [System.Text.StringBuilder]::new()
    foreach ($section in $order) {
        if ($groups.ContainsKey($section) -and $groups[$section].Count -gt 0) {
            [void]$sb.AppendLine("### $section")
            foreach ($item in $groups[$section]) {
                [void]$sb.AppendLine("- $item")
            }
            [void]$sb.AppendLine('')
        }
    }
    return $sb.ToString().TrimEnd()
}

# ── Generate release notes ─────────────────────────────────────────────
# Structure: "What's New" (AI-generated, only when -Agent is passed) on top
# of commit-derived categorized sections (always deterministic).
$releaseNotes = ''
$agentUsed = $false

$sectionNotes = Get-ReleaseNoteSections -Changes $changesText

$whatsNewText = ''
if ($useAgent) {
    Write-Host ""
    Write-Host "Checking for an AI agent to generate the What's New section..." -ForegroundColor DarkGray
    if ($changesText) {
        $whatsNewText = Invoke-ReleaseNotesAgent -Changes $changesText -Version $newTag
        if ($whatsNewText) {
            $agentUsed = $true
            Write-Host "  What's New generated with $($script:ReleaseAgent.Backend)." -ForegroundColor Green
        }
    }
}

if ($agentUsed) {
    $releaseNotes = "## What's New`n`n$whatsNewText`n`n$sectionNotes"
} else {
    $releaseNotes = $sectionNotes
}

if (-not $changesText) {
    Write-Host "  No changes to summarize." -ForegroundColor DarkGray
} else {
    Write-Host ""
    Write-Host "──────────────────────────────────────────────────────────" -ForegroundColor Cyan
    if ($agentUsed) {
        Write-Host "  Release notes (preview) — What's New: AI ($($script:ReleaseAgent.Backend)), sections: commit-derived" -ForegroundColor Cyan
    } else {
        Write-Host "  Release notes (preview) — commit-derived sections" -ForegroundColor Cyan
    }
    Write-Host "──────────────────────────────────────────────────────────" -ForegroundColor Cyan
    Write-Host $releaseNotes
    Write-Host "──────────────────────────────────────────────────────────" -ForegroundColor Cyan
    if (-not $useAgent) {
        Write-Host "  (AI What's New disabled by default — pass -Agent auto to enable.)" -ForegroundColor DarkGray
    } elseif (-not $agentUsed) {
        Write-Host "  (No AI agent available — What's New omitted.)" -ForegroundColor DarkGray
    }
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
        Write-Host "  Release notes: What's New (AI via $($script:ReleaseAgent.Backend)) + commit sections" -ForegroundColor Green
    } else {
        Write-Host "  Release notes: commit-derived sections"
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
    $effectiveMessage = Confirm-Step "Enter release message (optional, press Enter to skip)" ''
    $tagType = if ([string]::IsNullOrWhiteSpace($effectiveMessage)) { "lightweight" } else { "annotated" }
}

# Confirm creation
Write-Host ""
$confirm = Confirm-Step "Create and push $tagType tag '$newTag'? (y/n)"
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

    # Explicit success exit: without this, $LASTEXITCODE is left at whatever the
    # last native command returned (e.g. `git config --get remote.<url>.url` fails
    # with exit 1 when $remote is a URL), and monitor-release.ps1 misreads a
    # successful tag push as a trigger failure.
    exit 0
} catch {
    Write-Error "Failed to create/push tag: $_"
    exit 1
}
