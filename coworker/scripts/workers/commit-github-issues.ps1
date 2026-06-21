#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Scan coworker/tasks/200issues/github/commit/ready for issue files and create them
    on GitHub via the gh CLI.

.DESCRIPTION
    Each file in the ready directory represents a GitHub issue to be created.
    After a successful creation the file moves to the "done" directory; on
    failure it moves to "failed" so the operator can inspect and retry.

    A daily commit guard caps creations at 20 per UTC day to avoid tripping
    GitHub's spam detection.  Overflow files stay in "ready" and are picked up
    on the next scheduled run after the UTC date rolls over.  The daily counter
    persists in .daily-commit-state.json alongside the task directories.

    File format (markdown):
        # <Title>
        <Body>

        Labels: bug, enhancement          (optional)
        Assignees: username               (optional)
        Repo: owner/repo                  (optional — defaults to gh default)
#>

$ErrorActionPreference = 'Stop'

$workerDir = $PSScriptRoot
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
. $configPath

# ── Script-level mutex: only one commit-github-issues.ps1 instance at a time
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-CoworkerLog -Component 'commit-github-issues' -Level 'WARN' -Message 'Another commit-github-issues.ps1 instance is already running. Exiting.'
    exit 0
}

$repoRoot = Get-WorkspaceRoot

$issuesRoot = Resolve-TasksPath '200issues\github\commit'
$readyDir = Join-Path $issuesRoot 'ready'
$doneDir = Join-Path $issuesRoot 'done'
$failedDir = Join-Path $issuesRoot 'failed'

foreach ($directory in @($readyDir, $doneDir, $failedDir)) {
    Ensure-CoworkerDirectory -Path $directory
}

# ── Daily commit guard ─────────────────────────────────────────────────────
# GitHub may flag repos that create issues too aggressively as spam.  We cap
# the number of issues this worker creates per UTC day and defer any overflow
# to the next scheduled run so the pace stays organic.
$maxDailyCommits = 20
$dailyStateFile = Join-Path $issuesRoot '.daily-commit-state.json'

function Get-DailyCommitState {
    param([string]$StateFilePath)
    $todayUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
    if (Test-Path -LiteralPath $StateFilePath) {
        try {
            $state = Get-Content -LiteralPath $StateFilePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($state.Date -eq $todayUtc) {
                return @{ Date = $state.Date; Count = [int]$state.Count }
            }
        }
        catch {
            # Corrupt state file — reset
        }
    }
    return @{ Date = $todayUtc; Count = 0 }
}

function Set-DailyCommitState {
    param([string]$StateFilePath, [int]$Count)
    $todayUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
    $state = @{ Date = $todayUtc; Count = $Count } | ConvertTo-Json -Compress
    Set-Content -LiteralPath $StateFilePath -Value $state -Encoding UTF8 -NoNewline
}

$dailyState = Get-DailyCommitState -StateFilePath $dailyStateFile
$remainingCommits = $maxDailyCommits - $dailyState.Count

if ($remainingCommits -le 0) {
    Write-CoworkerLog -Message "Daily commit limit reached ($maxDailyCommits/$maxDailyCommits). Deferring remaining issues to next run." -Level 'WARN' -Component 'commit-github-issues'
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 0
}

$files = @(Get-ChildItem -Path $readyDir -File |
    Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) } |
    Sort-Object Name)

if ($files.Count -eq 0) {
    Write-CoworkerLog -Message "No GitHub issue files found in $readyDir" -Level 'INFO' -Component 'commit-github-issues'
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 0
}

Write-CoworkerLog -Message "Found $($files.Count) issue file(s) to commit ($remainingCommits remaining of $maxDailyCommits daily limit)" -Level 'INFO' -Component 'commit-github-issues'

$failureCount = 0
$committedToday = $dailyState.Count

foreach ($file in $files) {
    # ── Enforce daily commit limit ───────────────────────────────────────
    if ($committedToday -ge $maxDailyCommits) {
        $deferred = $files.Count - $files.IndexOf($file)
        Write-CoworkerLog -Message "Daily commit limit reached ($maxDailyCommits). Deferring $deferred remaining issue(s) to next run." -Level 'WARN' -Component 'commit-github-issues'
        break
    }

    Write-CoworkerLog -Message "Processing: $($file.Name)" -Level 'INFO' -Component 'commit-github-issues'

    try {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8

        if ([string]::IsNullOrWhiteSpace($content)) {
            throw 'File is empty.'
        }

        # ── Parse title ────────────────────────────────────────────────────
        $title = ''
        $bodyLines = @()

        if ($content -match "(?m)^#\s+(?<title>.+?)(\r?\n|$)") {
            $title = $Matches['title'].Trim()
            $titleIndex = $content.IndexOf($Matches[0]) + $Matches[0].Length
            $remaining = $content.Substring($titleIndex).TrimStart("`r", "`n")
        }
        else {
            $lines = @($content -split '\r?\n' | Where-Object { $_.Trim() -ne '' })
            if ($lines.Count -gt 0) {
                $title = $lines[0].Trim()
                $remaining = ($lines[1..($lines.Count - 1)] -join "`n").Trim()
            }
        }

        if ([string]::IsNullOrWhiteSpace($title)) {
            throw 'Could not parse issue title.'
        }

        # ── Parse metadata fields ──────────────────────────────────────────
        $labels = @()
        $assignees = @()
        $repo = ''
        $metadataPatterns = @(
            @{ Pattern = "(?m)^Labels:\s*(?<value>.+?)(\r?\n|$)"; Target = 'labels' },
            @{ Pattern = "(?m)^Assignees:\s*(?<value>.+?)(\r?\n|$)"; Target = 'assignees' },
            @{ Pattern = "(?m)^Repo:\s*(?<value>.+?)(\r?\n|$)"; Target = 'repo' }
        )

        foreach ($meta in $metadataPatterns) {
            if ($content -match $meta.Pattern) {
                $value = $Matches['value'].Trim()
                if ($value) {
                    switch ($meta.Target) {
                        'labels' { $labels = @($value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
                        'assignees' { $assignees = @($value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
                        'repo' { $repo = $value }
                    }
                }
            }
        }

        # ── Build body (everything after metadata lines) ────────────────────
        if ($remaining) {
            $bodyLines = @($remaining -split '\r?\n')
        }

        $bodyContent = ($bodyLines | Where-Object {
            $_ -notmatch '^Labels:\s*' -and
            $_ -notmatch '^Assignees:\s*' -and
            $_ -notmatch '^Repo:\s*'
        } | ForEach-Object { $_.TrimEnd() }) -join "`n"

        $bodyContent = $bodyContent.Trim()

        # ── Build gh arguments ─────────────────────────────────────────────
        $ghArgs = [System.Collections.ArrayList]::new()
        [void]$ghArgs.Add('issue')
        [void]$ghArgs.Add('create')
        [void]$ghArgs.Add('--title')
        [void]$ghArgs.Add($title)

        if ($bodyContent) {
            [void]$ghArgs.Add('--body')
            [void]$ghArgs.Add($bodyContent)
        }

        foreach ($label in $labels) {
            [void]$ghArgs.Add('--label')
            [void]$ghArgs.Add($label)
        }

        foreach ($assignee in $assignees) {
            [void]$ghArgs.Add('--assignee')
            [void]$ghArgs.Add($assignee)
        }

        if ($repo) {
            [void]$ghArgs.Add('--repo')
            [void]$ghArgs.Add($repo)
        }

        # ── Create the issue ───────────────────────────────────────────────
        Write-CoworkerLog -Message "Creating issue: $title" -Level 'INFO' -Component 'commit-github-issues'

        $ghOutput = & gh $ghArgs 2>&1
        $exitCode = $LASTEXITCODE

        if ($exitCode -ne 0) {
            throw "gh exited with code ${exitCode}: $ghOutput"
        }

        Write-CoworkerLog -Message "Issue created: $ghOutput" -Level 'INFO' -Component 'commit-github-issues'

        # ── Move to done ───────────────────────────────────────────────────
        $donePath = Join-Path $doneDir $file.Name
        Move-Item -Path $file.FullName -Destination $donePath -Force
        Write-CoworkerLog -Message "Moved to done: $donePath" -Level 'INFO' -Component 'commit-github-issues'

        # ── Update daily commit count ────────────────────────────────────
        $committedToday++
        Set-DailyCommitState -StateFilePath $dailyStateFile -Count $committedToday
    }
    catch {
        $failureCount++
        Write-CoworkerLog -Message "Failed to create issue from $($file.Name): $_" -Level 'ERROR' -Component 'commit-github-issues'

        $failedPath = Join-Path $failedDir $file.Name
        Move-Item -Path $file.FullName -Destination $failedPath -Force -ErrorAction SilentlyContinue
        Write-CoworkerLog -Message "Moved to failed: $failedPath" -Level 'WARN' -Component 'commit-github-issues'
    }
}

if ($failureCount -gt 0) {
    Write-CoworkerLog -Message "$failureCount issue(s) failed to create. $committedToday/$maxDailyCommits daily commits used. Check the failed directory: $failedDir" -Level 'WARN' -Component 'commit-github-issues'
    Set-DailyCommitState -StateFilePath $dailyStateFile -Count $committedToday
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}

Write-CoworkerLog -Message "All issues committed successfully ($committedToday/$maxDailyCommits daily commits used)." -Level 'INFO' -Component 'commit-github-issues'
Set-DailyCommitState -StateFilePath $dailyStateFile -Count $committedToday
Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
exit 0
