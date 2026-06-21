#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Fetch the latest GitHub issues from the repo and save them locally,
    assigning any unassigned issues to the authenticated user.

.DESCRIPTION
    Pulls the most recent open issues from the configured GitHub repository,
    writes each one as a markdown file in coworker/tasks/main/0draft/issues/github,
    and self-assigns any issue that currently has no assignee.

    Persists a .fetch-state.json alongside the output directory to track the last
    fetch timestamp, which issues are on disk, and to detect closed/deleted issues
    that need their local copies updated.

    Saved file format (markdown):
        # <Title>
        URL: <url>
        State: open | closed
        Author: <login>
        Assignees: <login1>, <login2>
        Labels: <label1>, <label2>
        Created: <iso-date>
        Updated: <iso-date>

        <Body>
#>

$ErrorActionPreference = 'Stop'

$workerDir = $PSScriptRoot
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
. $configPath

# ── Script-level mutex: only one fetch-github-issues.ps1 instance at a time
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-CoworkerLog -Component 'fetch-github-issues' -Level 'WARN' -Message 'Another fetch-github-issues.ps1 instance is already running. Exiting.'
    exit 0
}

$repoRoot = Get-WorkspaceRoot

# ── Configuration ──────────────────────────────────────────────────────────
$githubRepo = 'platonai/Browser4'
$issuesLimit = 20
$outputDir = Resolve-TasksPath 'main\0draft\issues\github'
$includeClosed = $false  # set $true to also pull closed issues
$maxAssignmentsPerRun = 5

Ensure-CoworkerDirectory -Path $outputDir

# ── Fetch state persistence ─────────────────────────────────────────────────
$fetchStateFile = Join-Path $outputDir '.fetch-state.json'

function Get-FetchState {
    param([string]$StateFilePath)

    if (Test-Path -LiteralPath $StateFilePath) {
        try {
            $state = Get-Content -LiteralPath $StateFilePath -Raw -Encoding UTF8 | ConvertFrom-Json
            return @{
                LastFetchedAt  = if ($state.LastFetchedAt) { $state.LastFetchedAt } else { $null }
                LastIssueNumber = if ($state.LastIssueNumber) { [int]$state.LastIssueNumber } else { 0 }
                IssuesOnDisk   = if ($state.IssuesOnDisk) { @($state.IssuesOnDisk | ForEach-Object { [int]$_ }) } else { @() }
            }
        }
        catch {
            Write-CoworkerLog -Message "Corrupt fetch state file, resetting: $_" -Level 'WARN' -Component 'fetch-github-issues'
        }
    }

    return @{
        LastFetchedAt  = $null
        LastIssueNumber = 0
        IssuesOnDisk   = @()
    }
}

function Set-FetchState {
    param(
        [string]$StateFilePath,
        [hashtable]$State
    )

    $json = @{
        LastFetchedAt  = $State.LastFetchedAt
        LastIssueNumber = $State.LastIssueNumber
        IssuesOnDisk   = @($State.IssuesOnDisk | Sort-Object)
    } | ConvertTo-Json -Compress

    Set-Content -LiteralPath $StateFilePath -Value $json -Encoding UTF8 -NoNewline
}

$fetchState = Get-FetchState -StateFilePath $fetchStateFile

# ── Dynamic cutoff date ─────────────────────────────────────────────────────
# Use the last fetch time (with a 5-minute backoff to catch edge cases), or
# default to 24 hours ago on the very first run.
$cutoffDate = if ($fetchState.LastFetchedAt) {
    ([datetime]::Parse($fetchState.LastFetchedAt)).AddMinutes(-5)
} else {
    (Get-Date).ToUniversalTime().AddHours(-24)
}
$cutoffFormatted = $cutoffDate.ToString('yyyy-MM-dd HH:mm:ss UTC')

# ── Build gh arguments ─────────────────────────────────────────────────────
$ghArgs = [System.Collections.ArrayList]::new()
[void]$ghArgs.Add('issue')
[void]$ghArgs.Add('list')
[void]$ghArgs.Add('--repo')
[void]$ghArgs.Add($githubRepo)
[void]$ghArgs.Add('--limit')
[void]$ghArgs.Add($issuesLimit)
[void]$ghArgs.Add('--json')
[void]$ghArgs.Add('number,title,state,body,createdAt,updatedAt,author,assignees,labels,url')
$searchQuery = 'sort:created-desc created:>={0:yyyy-MM-ddTHH:mm:ss}Z' -f $cutoffDate
[void]$ghArgs.Add('--search')
[void]$ghArgs.Add($searchQuery)

if (-not $includeClosed) {
    [void]$ghArgs.Add('--state')
    [void]$ghArgs.Add('open')
}

# ── Fetch issues ────────────────────────────────────────────────────────────
$ghOutput = & gh $ghArgs 2>&1
$exitCode = $LASTEXITCODE

if ($exitCode -ne 0) {
    Write-CoworkerLog -Message ("gh exited with code ${exitCode}: ${ghOutput}") -Level 'ERROR' -Component 'fetch-github-issues'
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}

if ([string]::IsNullOrWhiteSpace($ghOutput)) {
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 0
}

try {
    $issues = $ghOutput | ConvertFrom-Json
}
catch {
    Write-CoworkerLog -Message "Failed to parse gh JSON output: $_" -Level 'ERROR' -Component 'fetch-github-issues'
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}

if ($null -eq $issues -or $issues.Count -eq 0) {
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 0
}

# ── Resolve current user ────────────────────────────────────────────────────
$meOutput = & gh api user --jq '.login' 2>&1
$currentUser = if ($LASTEXITCODE -eq 0 -and $meOutput) { $meOutput.Trim() } else { $null }

if ([string]::IsNullOrWhiteSpace($currentUser)) {
    Write-CoworkerLog -Message 'Could not determine authenticated GitHub user.' -Level 'WARN' -Component 'fetch-github-issues'
}

# ── Process each issue ──────────────────────────────────────────────────────
$savedCount = 0
$assignedCount = 0
$alreadyAssignedCount = 0
$skippedCount = 0
$excludedByDateCount = 0
$deferredAssignCount = 0
$fetchedNumbers = [System.Collections.Generic.HashSet[int]]::new()

foreach ($issue in $issues) {
    $issueNumber = $issue.number
    [void]$fetchedNumbers.Add($issueNumber)

    # ── Guard: skip issues created before the cutoff ──────────────────────
    $created = $null
    if ($issue.createdAt) {
        $created = $issue.createdAt -as [datetime]
    }
    if ($created -and $created -lt $cutoffDate) {
        $excludedByDateCount++
        continue
    }
    $fileName = "$issueNumber.md"
    $filePath = Join-Path $outputDir $fileName

    # ── Build markdown content ──────────────────────────────────────────
    $title = $issue.title
    $url = $issue.url
    $state = $issue.state
    $authorLogin = if ($issue.author) { $issue.author.login } else { 'unknown' }
    $assigneeLogins = if ($issue.assignees) {
        ($issue.assignees | ForEach-Object { $_.login }) -join ', '
    }
    else { '' }
    $labelNames = if ($issue.labels) {
        ($issue.labels | ForEach-Object { $_.name }) -join ', '
    }
    else { '' }
    $createdAt = $issue.createdAt
    $updatedAt = $issue.updatedAt
    $body = $issue.body

    # Build the markdown
    $markdown = @()
    $markdown += "# $title"
    $markdown += ''
    $markdown += "URL: $url"
    $markdown += "State: $state"
    $markdown += "Author: $authorLogin"
    if ($assigneeLogins) {
        $markdown += "Assignees: $assigneeLogins"
    }
    if ($labelNames) {
        $markdown += "Labels: $labelNames"
    }
    $markdown += "Created: $createdAt"
    $markdown += "Updated: $updatedAt"
    $markdown += ''

    if ($body) {
        $markdown += $body
    }

    $fileContent = ($markdown -join "`n") + "`n"

    # ── Write file ──────────────────────────────────────────────────────
    $existingContent = $null
    if (Test-Path -LiteralPath $filePath) {
        $existingContent = Get-Content -Path $filePath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    }

    if ($existingContent -eq $fileContent) {
        $skippedCount++
    }
    else {
        Set-Content -Path $filePath -Value $fileContent -Encoding UTF8 -NoNewline
        $savedCount++
        if ($existingContent) {
            Write-CoworkerLog -Message ("Updated issue #${issueNumber}: ${title}") -Level 'INFO' -Component 'fetch-github-issues'
        }
        else {
            Write-CoworkerLog -Message ("Saved new issue #${issueNumber}: ${title}") -Level 'INFO' -Component 'fetch-github-issues'
        }
    }

    # ── Self-assign if unassigned ────────────────────────────────────────
    if ($currentUser -and (-not $issue.assignees -or $issue.assignees.Count -eq 0)) {
        # Check local file: is the current user already listed as an assignee?
        $localAlreadyAssigned = $false
        if ($existingContent -and $existingContent -match "(?m)^Assignees:\s*(?<assignees>.+)$") {
            $localAssignees = @($Matches['assignees'] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
            if ($currentUser -in $localAssignees) {
                $localAlreadyAssigned = $true
            }
        }

        if ($localAlreadyAssigned) {
            $alreadyAssignedCount++
            continue
        }

        # Enforce per-run assignment cap
        if ($assignedCount -ge $maxAssignmentsPerRun) {
            $deferredAssignCount++
            continue
        }

        try {
            $assignResult = & gh issue edit $issueNumber --repo $githubRepo --add-assignee $currentUser 2>&1
            if ($LASTEXITCODE -eq 0) {
                $assignedCount++
                Write-CoworkerLog -Message "Self-assigned issue #$issueNumber to $currentUser" -Level 'INFO' -Component 'fetch-github-issues'
            }
            else {
                Write-CoworkerLog -Message ("Failed to assign issue #${issueNumber}: ${assignResult}") -Level 'WARN' -Component 'fetch-github-issues'
            }
        }
        catch {
            Write-CoworkerLog -Message ("Error assigning issue #${issueNumber}: $_") -Level 'WARN' -Component 'fetch-github-issues'
        }
    }
}

# ── Detect closed / deleted issues ───────────────────────────────────────────
# Issues that exist on disk but are absent from this fetch batch may have been
# closed or deleted.  Query GitHub for their current state and update locally.
# Skip on the first run (IssuesOnDisk is empty) to avoid an API storm.
$closedCount = 0
$deletedCount = 0

if ($fetchState.IssuesOnDisk.Count -gt 0) {
    foreach ($diskNumber in $fetchState.IssuesOnDisk) {
        if ($fetchedNumbers.Contains($diskNumber)) {
            continue
        }

        $diskFile = Join-Path $outputDir "$diskNumber.md"
        if (-not (Test-Path -LiteralPath $diskFile)) {
            continue
        }

        Write-CoworkerLog -Message "Issue #$diskNumber not in fetch batch — checking current state on GitHub…" -Level 'INFO' -Component 'fetch-github-issues'

        try {
            $viewOutput = & gh issue view $diskNumber --repo $githubRepo --json state,updatedAt,title,body,url,createdAt,author,assignees,labels 2>&1
            $viewExitCode = $LASTEXITCODE

            if ($viewExitCode -ne 0) {
                # Issue may have been deleted or transferred
                Write-CoworkerLog -Message "Issue #$diskNumber not found on GitHub (may be deleted). Removing local file." -Level 'WARN' -Component 'fetch-github-issues'
                Remove-Item -LiteralPath $diskFile -Force -ErrorAction SilentlyContinue
                $deletedCount++
                continue
            }

            $issueDetail = $viewOutput | ConvertFrom-Json
            $newState = $issueDetail.state

            if ($newState -eq 'closed') {
                # Rebuild the markdown with State: closed and updated timestamp
                $updatedAt = $issueDetail.updatedAt
                $authorLogin = if ($issueDetail.author) { $issueDetail.author.login } else { 'unknown' }
                $assigneeLogins = if ($issueDetail.assignees) {
                    ($issueDetail.assignees | ForEach-Object { $_.login }) -join ', '
                } else { '' }
                $labelNames = if ($issueDetail.labels) {
                    ($issueDetail.labels | ForEach-Object { $_.name }) -join ', '
                } else { '' }

                $closedMarkdown = @()
                $closedMarkdown += "# $($issueDetail.title)"
                $closedMarkdown += ''
                $closedMarkdown += "URL: $($issueDetail.url)"
                $closedMarkdown += "State: $newState"
                $closedMarkdown += "Author: $authorLogin"
                if ($assigneeLogins) {
                    $closedMarkdown += "Assignees: $assigneeLogins"
                }
                if ($labelNames) {
                    $closedMarkdown += "Labels: $labelNames"
                }
                $closedMarkdown += "Created: $($issueDetail.createdAt)"
                $closedMarkdown += "Updated: $updatedAt"
                $closedMarkdown += ''
                if ($issueDetail.body) {
                    $closedMarkdown += $issueDetail.body
                }

                $closedContent = ($closedMarkdown -join "`n") + "`n"
                Set-Content -Path $diskFile -Value $closedContent -Encoding UTF8 -NoNewline
                $closedCount++
                Write-CoworkerLog -Message "Updated issue #$diskNumber to State: closed" -Level 'INFO' -Component 'fetch-github-issues'
            }
        }
        catch {
            Write-CoworkerLog -Message "Error checking state of issue #$diskNumber: $_" -Level 'WARN' -Component 'fetch-github-issues'
        }
    }
}

# ── Persist fetch state ──────────────────────────────────────────────────────
$fetchState.LastFetchedAt = (Get-Date).ToUniversalTime().ToString('o')
if ($issues.Count -gt 0) {
    $fetchState.LastIssueNumber = ($issues | Sort-Object { $_.number } -Descending | Select-Object -First 1).number
}
$fetchState.IssuesOnDisk = @(
    Get-ChildItem -Path $outputDir -Filter '*.md' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(\d+)\.md$' } |
        ForEach-Object { [int]$Matches[1] } |
        Sort-Object
)
Set-FetchState -StateFilePath $fetchStateFile -State $fetchState

# ── Summary ───────────────────────────────────────────────────────────────────
$logParts = @()
if ($savedCount -gt 0) { $logParts += "$savedCount saved" }
if ($assignedCount -gt 0) { $logParts += "$assignedCount assigned" }
if ($alreadyAssignedCount -gt 0) { $logParts += "$alreadyAssignedCount already-assigned" }
if ($deferredAssignCount -gt 0) { $logParts += "$deferredAssignCount assignments deferred" }
if ($skippedCount -gt 0) { $logParts += "$skippedCount skipped" }
if ($excludedByDateCount -gt 0) { $logParts += "$excludedByDateCount excluded" }
if ($closedCount -gt 0) { $logParts += "$closedCount closed-updated" }
if ($deletedCount -gt 0) { $logParts += "$deletedCount deleted" }

if ($logParts.Count -gt 0) {
    Write-CoworkerLog -Message ("Done: $($logParts -join ', ') (before $cutoffFormatted).") -Level 'INFO' -Component 'fetch-github-issues'
}
else {
    Write-CoworkerLog -Message "Done: no changes (before $cutoffFormatted)." -Level 'INFO' -Component 'fetch-github-issues'
}

if ($deferredAssignCount -gt 0) {
    Write-CoworkerLog -Message "Assignment cap reached ($maxAssignmentsPerRun). $deferredAssignCount unassigned issue(s) deferred to next run." -Level 'WARN' -Component 'fetch-github-issues'
}

Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
exit 0
