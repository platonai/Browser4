#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Detect stale README.md files and optionally queue them for AI-driven update.

.DESCRIPTION
    Runs a multi-signal staleness detection algorithm against every README.md
    tracked by git (excluding build output directories like target/). Each
    README receives a 0-100 score across four weighted dimensions:

      | Signal               | Weight | What it measures
      |----------------------|--------|--------------------------------------------------
      | Git change recency   | 40%    | Source code changed since the README was last touched
      | Content quality      | 30%    | Too short, has TODO markers, broken relative links
      | Version consistency  | 20%    | README references an outdated version vs VERSION file
      | Directory coverage   | 10%    | Subdirs exist that aren't mentioned in the README

    A README is considered stale when its score >= 40.  Severity levels:
      40-59  = low
      60-79  = medium
      80-100 = high

    Without -Update, the script only reports scores to the log.  With -Update,
    it creates a coworker task file in 1ready for each stale README so the
    normal coworker pipeline picks it up for AI-driven regeneration.

.PARAMETER Update
    Create coworker task files for stale READMEs instead of just reporting.

.PARAMETER Threshold
    Minimum score to consider a README stale. Default: 40.

.PARAMETER MaxTasks
    Maximum number of update task files to create in a single run (safety cap).
    Default: 3.

.PARAMETER ExcludePatterns
    Additional glob patterns to exclude (beyond the built-in exclusions).
    Default: @()

.PARAMETER DryRun
    Show what would be done without writing any files or creating tasks.

.EXAMPLE
    # Report only — safe for hourly cron
    .\update-readmes.ps1

.EXAMPLE
    # Create task files for the top 3 stalest READMEs
    .\update-readmes.ps1 -Update

.EXAMPLE
    # Dry-run to see what would be queued
    .\update-readmes.ps1 -Update -DryRun
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$Update,

    [int]$Threshold = 40,

    [int]$MaxTasks = 3,

    [string[]]$ExcludePatterns = @(),

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path (Split-Path -Parent $workerDir) 'config.ps1')

# ── Script-level mutex: only one update-readmes.ps1 instance at a time
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-CoworkerLog -Component 'update-readmes' -Level 'WARN' -Message 'Another update-readmes.ps1 instance is already running. Exiting.'
    exit 0
}

$repoRoot = Get-WorkspaceRoot
$today = (Get-Date).ToUniversalTime()

# ── Built-in exclusions ──────────────────────────────────────────────────────
# Directories that should never be scanned for README staleness.
$builtinExcludeGlobs = @(
    '**/target/**'          # Maven build output
    '**/node_modules/**'    # npm dependencies
    '**/.git/**'            # git internals
    '**/logs/**'            # log directories
    '**/.claude/**'         # Claude Code harness
    '**/coworker/tasks/main/3done/**'
    '**/coworker/tasks/main/5approved/**'
    '**/coworker/tasks/main/6git-pushed/**'
    '**/coworker/tasks/700archive/**'
    '**/coworker/tasks/issues/github/commit/ready/**'
    '**/coworker/tasks/issues/draft/refine/2done/**'
    '**/coworker/tasks/issues/draft/refine/0error/**'
    '**/coworker/tasks/300logs/**'
)

# ── Helper: test a path against a list of glob patterns ─────────────────────
function Test-PathMatchesGlob {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string[]]$Globs
    )

    $normalizedPath = $Path.Replace('\', '/')
    foreach ($glob in $Globs) {
        $pattern = $glob.Replace('\', '/')
        # Simple wildcard matching: ** matches any depth, * matches within one segment
        $regex = [regex]::Escape($pattern)
        $regex = $regex.Replace('\*\*/', '.*/')      # **/  → any-depth
        $regex = $regex.Replace('/\*\*', '/.*')       # /**   → any-depth suffix
        $regex = $regex.Replace('\*\*', '.*')         # **    → any-depth (no slashes)
        $regex = $regex.Replace('\*', '[^/]*')        # *     → single segment
        $regex = '^' + $regex + '$'

        if ($normalizedPath -match $regex) {
            return $true
        }

        # Also match prefix (the glob may target a directory prefix)
        if ($regex -match '\.\*\/$' -or $regex -match '/\.\*$') {
            # already handled by the replacements above
        }
    }

    return $false
}

# ── Signal 1: Git change recency (0-40 points) ───────────────────────────────
function Get-GitChangeRecencyScore {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReadmePath,
        [Parameter(Mandatory = $true)]
        [string]$ModuleDir
    )

    $score = 0.0
    $details = @()

    try {
        # Last commit that touched this README
        $readmeLastEpoch = git -C $repoRoot log -1 --format='%ct' -- $ReadmePath 2>$null
        if ([string]::IsNullOrWhiteSpace($readmeLastEpoch)) {
            # README has never been committed — treat as maximally stale
            return @{ Score = 40.0; Details = 'Never committed (untracked or new)' }
        }

        # Last commit that touched any file in the module directory (excluding the README)
        $moduleLastEpoch = git -C $repoRoot log -1 --format='%ct' -- "${ModuleDir}/" ':!*/README.md' 2>$null
        if ([string]::IsNullOrWhiteSpace($moduleLastEpoch)) {
            # No commits to the module at all — README is current by default
            return @{ Score = 0.0; Details = 'No module changes to compare against' }
        }

        $readmeLast = [long]$readmeLastEpoch.Trim()
        $moduleLast = [long]$moduleLastEpoch.Trim()

        if ($moduleLast -le $readmeLast) {
            return @{ Score = 0.0; Details = "README is current (module: $moduleLast, readme: $readmeLast)" }
        }

        # Module changed more recently than README — compute staleness
        $secondsStale = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - $readmeLast
        $daysStale = [Math]::Max(0, $secondsStale / 86400.0)
        $decayFactor = [Math]::Min($daysStale / 30.0, 1.0)   # caps at 30 days
        $score = [Math]::Round(40.0 * $decayFactor, 1)

        $details = @(
            "Module changed $([Math]::Round($daysStale, 1)) days after README was last updated",
            "README last: $(Get-Date -Date (Get-Date 1970-01-01).AddSeconds($readmeLast) -Format 'yyyy-MM-dd HH:mm')",
            "Module last: $(Get-Date -Date (Get-Date 1970-01-01).AddSeconds($moduleLast) -Format 'yyyy-MM-dd HH:mm')"
        ) -join '; '
    }
    catch {
        $details = "Git error: $_"
    }

    return @{ Score = $score; Details = $details }
}

# ── Signal 2: Content quality (0-30 points) ──────────────────────────────────
function Get-ContentQualityScore {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReadmePath
    )

    $score = 0.0
    $reasons = @()

    $content = Get-Content -LiteralPath $ReadmePath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if ([string]::IsNullOrWhiteSpace($content)) {
        return @{ Score = 30.0; Details = 'File is empty or unreadable' }
    }

    $lines = @($content -split '\r?\n')
    $nonBlankLines = @($lines | Where-Object { $_ -match '\S' })

    # 2a: Too short (< 10 non-blank lines) — likely a placeholder
    if ($nonBlankLines.Count -lt 10) {
        $score += 15.0
        $reasons += "Too short: $($nonBlankLines.Count) non-blank lines"
    }

    # 2b: Has TODO / TBD / FIXME markers
    $todoPattern = '\b(TODO|TBD|FIXME|XXX|HACK|WORKAROUND)\b'
    $todoMatches = [regex]::Matches($content, $todoPattern, 'IgnoreCase')
    if ($todoMatches.Count -gt 0) {
        $penalty = [Math]::Min($todoMatches.Count * 3, 10)
        $score += $penalty
        $reasons += "Has $($todoMatches.Count) TODO/TBD/FIXME markers"
    }

    # 2c: Broken relative links (check .md and directory references)
    $linkPattern = '\]\(\.\.?/[^)]+\)'
    $linkMatches = [regex]::Matches($content, $linkPattern)
    $brokenCount = 0
    $readmeDir = Split-Path -Parent $ReadmePath
    foreach ($match in $linkMatches) {
        $linkTarget = $match.Value -replace '\]\(', '' -replace '\)', ''
        # Strip anchor
        $linkTarget = $linkTarget -replace '#.*$', ''
        if ([string]::IsNullOrWhiteSpace($linkTarget)) { continue }

        $resolvedTarget = [System.IO.Path]::GetFullPath((Join-Path $readmeDir $linkTarget))
        if (-not (Test-Path -LiteralPath $resolvedTarget)) {
            $brokenCount++
        }
    }
    if ($brokenCount -gt 0) {
        $penalty = [Math]::Min($brokenCount * 5, 15)
        $score += $penalty
        $reasons += "Has $brokenCount broken relative link(s)"
    }

    $score = [Math]::Min($score, 30)  # cap
    return @{ Score = [Math]::Round($score, 1); Details = ($reasons -join '; ') }
}

# ── Signal 3: Version consistency (0-20 points) ──────────────────────────────
function Get-VersionConsistencyScore {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReadmePath
    )

    $versionFile = Join-Path $repoRoot 'VERSION'
    if (-not (Test-Path -LiteralPath $versionFile)) {
        return @{ Score = 0.0; Details = 'No VERSION file found' }
    }

    $currentVersion = (Get-Content -LiteralPath $versionFile -Raw).Trim()
    $currentMajorMinor = $currentVersion -replace '^(\d+\.\d+).*', '$1'

    $content = Get-Content -LiteralPath $ReadmePath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if ([string]::IsNullOrWhiteSpace($content)) {
        return @{ Score = 0.0; Details = 'File unreadable' }
    }

    # Look for version patterns like "4.9.x", "4.10.x", "v4.9.3" etc.
    $versionPattern = '\b(v?(\d+\.\d+)(?:\.\d+)?(?:-SNAPSHOT)?)\b'
    $versionMatches = [regex]::Matches($content, $versionPattern)

    $outdatedVersions = @()
    foreach ($match in $versionMatches) {
        $foundVersion = $match.Groups[2].Value  # major.minor
        if ($foundVersion -ne $currentMajorMinor) {
            $outdatedVersions += $match.Groups[1].Value
        }
    }

    if ($outdatedVersions.Count -eq 0) {
        return @{ Score = 0.0; Details = "Version $currentMajorMinor is current" }
    }

    $uniqueOutdated = $outdatedVersions | Select-Object -Unique
    return @{
        Score   = [Math]::Min(20, $uniqueOutdated.Count * 10)
        Details = "References outdated version(s): $($uniqueOutdated -join ', '); current: $currentVersion"
    }
}

# ── Signal 4: Directory structure coverage (0-10 points) ─────────────────────
function Get-DirectoryCoverageScore {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReadmePath
    )

    $moduleDir = Split-Path -Parent $ReadmePath
    $content = Get-Content -LiteralPath $ReadmePath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if ([string]::IsNullOrWhiteSpace($content)) {
        return @{ Score = 0.0; Details = 'File unreadable' }
    }

    # Get immediate subdirectories
    $subdirs = @(Get-ChildItem -LiteralPath $moduleDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '^\.' -and $_.Name -ne 'target' -and $_.Name -ne 'node_modules' } |
        Select-Object -ExpandProperty Name)

    if ($subdirs.Count -eq 0) {
        return @{ Score = 0.0; Details = 'No subdirectories to cover' }
    }

    # Check how many subdirs are mentioned by name in the README
    $unmentioned = @($subdirs | Where-Object {
        $escaped = [regex]::Escape($_)
        $content -notmatch "\b$escaped\b"
    })

    if ($unmentioned.Count -eq 0) {
        return @{ Score = 0.0; Details = "All $($subdirs.Count) subdirectories mentioned" }
    }

    $score = [Math]::Min(10, $unmentioned.Count * 5)
    return @{
        Score   = [Math]::Round($score, 1)
        Details = "$($unmentioned.Count)/$($subdirs.Count) subdirs not mentioned: $($unmentioned -join ', ')"
    }
}

# ── Main: collect all tracked README paths ───────────────────────────────────
function Get-TrackedReadmePaths {
    param(
        [string[]]$ExcludeGlobs = @()
    )

    $allExcludes = $builtinExcludeGlobs + $ExcludeGlobs

    $readmePaths = @(git -C $repoRoot ls-files -- '**/README.md' 2>$null |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

    if ($readmePaths.Count -eq 0) {
        Write-CoworkerLog -Component 'update-readmes' -Level 'WARN' -Message 'No README.md files found via git ls-files'
        return @()
    }

    $filtered = @($readmePaths | Where-Object {
        $fullPath = Join-Path $repoRoot $_
        -not (Test-PathMatchesGlob -Path $_ -Globs $allExcludes)
    })

    return $filtered
}

# ── Main: evaluate a single README ───────────────────────────────────────────
function Measure-ReadmeStaleness {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReadmeRelativePath
    )

    $fullPath = Join-Path $repoRoot $ReadmeRelativePath
    $moduleDir = Split-Path -Parent $fullPath

    $signal1 = Get-GitChangeRecencyScore -ReadmePath $fullPath -ModuleDir $moduleDir
    $signal2 = Get-ContentQualityScore -ReadmePath $fullPath
    $signal3 = Get-VersionConsistencyScore -ReadmePath $fullPath
    $signal4 = Get-DirectoryCoverageScore -ReadmePath $fullPath

    $totalScore = [Math]::Round(
        $signal1.Score + $signal2.Score + $signal3.Score + $signal4.Score, 1)

    $severity = if ($totalScore -ge 80) { 'high' }
        elseif ($totalScore -ge 60) { 'medium' }
        elseif ($totalScore -ge $Threshold) { 'low' }
        else { 'none' }

    return [PSCustomObject]@{
        Path              = $ReadmeRelativePath
        Score             = $totalScore
        Severity          = $severity
        ShouldUpdate      = $totalScore -ge $Threshold
        Signal1Score      = $signal1.Score
        Signal1Detail      = $signal1.Details
        Signal2Score      = $signal2.Score
        Signal2Detail      = $signal2.Details
        Signal3Score      = $signal3.Score
        Signal3Detail      = $signal3.Details
        Signal4Score      = $signal4.Score
        Signal4Detail      = $signal4.Details
    }
}

# ── Main: create a coworker task file for a stale README ─────────────────────
function New-ReadmeUpdateTaskFile {
    param(
        [Parameter(Mandatory = $true)]
        [psobject]$Result,
        [Parameter(Mandatory = $true)]
        [string]$TasksReadyDir
    )

    $timestamp = $today.ToString('yyyyMMdd-HHmmss')
    $safeName = $Result.Path.Replace('/', '-').Replace('\', '-').Replace(' ', '_')
    $taskFileName = "${timestamp}-readme-update-${safeName}.md"
    $taskFilePath = Join-Path $TasksReadyDir $taskFileName

    $taskContent = @"
# README Update Task

**Generated:** $($today.ToString('yyyy-MM-dd HH:mm:ss UTC'))
**README:** `$($Result.Path)`
**Staleness Score:** $($Result.Score)/100 ($($Result.Severity) severity)

## Why this README needs updating

| Signal | Score | Detail |
|--------|-------|--------|
| Git change recency (40) | $($Result.Signal1Score) | $($Result.Signal1Detail) |
| Content quality (30) | $($Result.Signal2Score) | $($Result.Signal2Detail) |
| Version consistency (20) | $($Result.Signal3Score) | $($Result.Signal3Detail) |
| Directory coverage (10) | $($Result.Signal4Score) | $($Result.Signal4Detail) |

## Instructions

1. Read the current README at `$($Result.Path)`.
2. Read any relevant source files in the same directory tree to understand the current state.
3. Update the README to reflect the current state of the module — fix outdated version
   references, document new subdirectories/files, resolve any TODO markers, fix broken
   links, and ensure the content is comprehensive.
4. Keep the existing style, tone, and formatting conventions of the original README.
5. Write the updated README back to `$($Result.Path)`.

#auto-approve
"@

    if ($DryRun) {
        Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
            -Message "(DryRun) Would create task file: $taskFileName"
        return $taskFilePath
    }

    Ensure-CoworkerDirectory -Path $TasksReadyDir
    Set-Content -LiteralPath $taskFilePath -Value $taskContent -Encoding UTF8 -NoNewline
    Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
        -Message "Created task file: $taskFileName"

    return $taskFilePath
}

# ── Main: generate summary report ────────────────────────────────────────────
function Write-StalenessReport {
    param(
        [Parameter(Mandatory = $true)]
        [psobject[]]$Results
    )

    $stale = @($Results | Where-Object { $_.ShouldUpdate })
    $current = @($Results | Where-Object { -not $_.ShouldUpdate })

    Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
        -Message "Scan complete: $($Results.Count) READMEs — $($stale.Count) stale, $($current.Count) current"

    if ($stale.Count -eq 0) {
        return
    }

    Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
        -Message '--- Stale READMEs ---'

    $sorted = $stale | Sort-Object Score -Descending
    foreach ($r in $sorted) {
        $prefix = switch ($r.Severity) {
            'high' { '🔴' }
            'medium' { '🟡' }
            'low' { '🟢' }
            default { '  ' }
        }
        Write-CoworkerLog -Component 'update-readmes' -Level $(if ($r.Severity -eq 'high') { 'WARN' } else { 'INFO' }) `
            -Message ("{0} [{1}] {2} (score: {3})" -f $prefix, $r.Severity.ToUpper(), $r.Path, $r.Score)

        # Emit per-signal detail at DEBUG level
        Write-CoworkerLog -Component 'update-readmes' -Level 'DEBUG' `
            -Message "  S1 (git): $($r.Signal1Score) — $($r.Signal1Detail)"
        Write-CoworkerLog -Component 'update-readmes' -Level 'DEBUG' `
            -Message "  S2 (content): $($r.Signal2Score) — $($r.Signal2Detail)"
        Write-CoworkerLog -Component 'update-readmes' -Level 'DEBUG' `
            -Message "  S3 (version): $($r.Signal3Score) — $($r.Signal3Detail)"
        Write-CoworkerLog -Component 'update-readmes' -Level 'DEBUG' `
            -Message "  S4 (dir coverage): $($r.Signal4Score) — $($r.Signal4Detail)"
    }
}

# ── Entry point ──────────────────────────────────────────────────────────────
function Invoke-ReadmeUpdateCycle {
    Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
        -Message "README staleness scan started (threshold=$Threshold, update=$Update, maxTasks=$MaxTasks)"

    $readmePaths = Get-TrackedReadmePaths -ExcludeGlobs $ExcludePatterns
    Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
        -Message "Found $($readmePaths.Count) tracked README.md files to evaluate"

    $results = @()
    foreach ($readmePath in $readmePaths) {
        $result = Measure-ReadmeStaleness -ReadmeRelativePath $readmePath
        $results += $result
    }

    Write-StalenessReport -Results $results

    # ── Optionally create update tasks ───────────────────────────────────────
    if ($Update) {
        $stale = @($results | Where-Object { $_.ShouldUpdate } | Sort-Object Score -Descending)
        if ($stale.Count -eq 0) {
            Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
                -Message 'No stale READMEs — nothing to queue'
            return @{ Scanned = $results.Count; Stale = 0; Queued = 0 }
        }

        $tasksDir = Resolve-TasksPath 'main\1ready'
        $queued = 0
        foreach ($r in $stale) {
            if ($queued -ge $MaxTasks) {
                Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
                    -Message "Reached MaxTasks limit ($MaxTasks) — $($stale.Count - $queued) stale README(s) deferred to next run"
                break
            }
            New-ReadmeUpdateTaskFile -Result $r -TasksReadyDir $tasksDir
            $queued++
        }

        Write-CoworkerLog -Component 'update-readmes' -Level 'INFO' `
            -Message "Queued $queued task file(s) in 1ready for README regeneration"

        return @{ Scanned = $results.Count; Stale = $stale.Count; Queued = $queued }
    }

    return @{ Scanned = $results.Count; Stale = ($results | Where-Object { $_.ShouldUpdate }).Count; Queued = 0 }
}

# ── Run ──────────────────────────────────────────────────────────────────────
$result = Invoke-ReadmeUpdateCycle

# Emit JSON summary to stdout for machine consumption (scheduler logs capture this)
$jsonSummary = @{
    scanned      = $result.Scanned
    stale        = $result.Stale
    queued       = $result.Queued
    threshold    = $Threshold
    updateMode   = $Update.IsPresent
    dryRun       = $DryRun.IsPresent
    completedAt  = $today.ToString('o')
} | ConvertTo-Json -Compress

Write-Host $jsonSummary

Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
exit 0
