#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Fetch the latest GitHub issues from the repo and save them locally,
    assigning any unassigned issues to the authenticated user.

.DESCRIPTION
    Pulls the most recent open issues from the configured GitHub repository,
    writes each one as a markdown file in coworker/tasks/0draft/issues/github,
    and self-assigns any issue that currently has no assignee.

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
$outputDir = Resolve-TasksPath '0draft\issues\github'
$includeClosed = $false  # set $true to also pull closed issues
$cutoffDate = [datetime]'2026-06-06T06:06:06Z'  # ignore issues created before this UTC time

Ensure-CoworkerDirectory -Path $outputDir

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
$cutoffFormatted = $cutoffDate.ToString('yyyy-MM-dd HH:mm:ss UTC')
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
$skippedCount = 0
$excludedByDateCount = 0

foreach ($issue in $issues) {
    $issueNumber = $issue.number

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

if ($savedCount -gt 0) {
    Write-CoworkerLog -Message "Done: $savedCount saved, $assignedCount assigned, $skippedCount skipped, $excludedByDateCount excluded (before $cutoffFormatted)." -Level 'INFO' -Component 'fetch-github-issues'
}
Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
exit 0
