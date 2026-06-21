#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Extract GitHub issues from draft files, split into individual issues, refine
    them, and stage them for creation via commit-github-issues.ps1.

.DESCRIPTION
    Scans coworker/tasks/200issues/draft/refine/0ready for draft files that may
    contain one or more issues described in natural language.

    For each file the worker:
    1. Moves it to 1working
    2. Calls the AI agent to extract individual issues, split them, and format
       each as a properly structured GitHub issue markdown file
    3. Writes each extracted issue to 200issues/github/open (where
       commit-github-issues.ps1 will pick them up)
    4. Moves the original draft file to:
       - 200issues/github/open if #auto-approve is found in the last 5 lines
         (so the original also gets committed as a GitHub issue)
       - 200issues/github/draft otherwise (for manual review/approval)

    Issue format written to github/open:
        # <Title>
        <Body>

        Labels: bug, enhancement          (optional)
        Assignees: username               (optional)
        Repo: owner/repo                  (optional)

    #auto-approve behavior:
        When #auto-approve appears in the last 5 lines of a draft file, the
        original file is moved to github/open alongside the extracted issues.
        This means both the extracted individual issues AND the original draft
        will be committed as GitHub issues. Use this when the original draft
        itself should also become an issue (e.g., as a parent/epic issue).

.PARAMETER Path
    File or directory of draft issue files to process. Defaults to the
    0ready directory.

.PARAMETER MaxRetries
    Maximum agent attempts per file before moving to dead-letter. Defaults to 3.

.PARAMETER TimeoutSeconds
    Maximum agent invocation time. Defaults to 600.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Path = '',

    [int]$MaxRetries = 3,

    [int]$TimeoutSeconds = 600,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path (Split-Path -Parent $workerDir) 'config.ps1')
. (Join-Path $workerDir 'agent-reliability.ps1')

# ── Script-level mutex: only one refine-github-issues.ps1 instance at a time
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-CoworkerLog -Component 'refine-github-issues' -Level 'WARN' -Message 'Another refine-github-issues.ps1 instance is already running. Exiting.'
    exit 0
}

$repoRoot = Get-WorkspaceRoot

# ── Directories ──────────────────────────────────────────────────────────────
$issuesRoot     = Resolve-TasksPath '200issues'
$refineRoot     = Join-Path $issuesRoot 'draft\refine'
$readyDir       = Join-Path $refineRoot '0ready'
$workingDir     = Join-Path $refineRoot '1working'
$doneDir        = Join-Path $refineRoot '2done'
$errorDir       = Join-Path $refineRoot '0error'
$githubOpenDir   = Join-Path $issuesRoot 'github\open'
$githubDraftDir  = Join-Path $issuesRoot 'github\draft'

foreach ($directory in @($readyDir, $workingDir, $doneDir, $errorDir, $githubOpenDir, $githubDraftDir)) {
    Ensure-CoworkerDirectory -Path $directory
}

if ([string]::IsNullOrWhiteSpace($Path)) {
    $Path = $readyDir
}

# ── Issue boundary marker ────────────────────────────────────────────────────
$script:IssueBoundary = '<!-- COWORKER_ISSUE_BOUNDARY -->'

# ── Recovery: return orphaned files from working back to ready ───────────────
function Restore-OrphanedWorkingFiles {
    param([int]$MaxAgeMinutes = 30)

    $orphans = Get-ChildItem -Path $workingDir -File -ErrorAction SilentlyContinue |
        Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }

    foreach ($orphan in $orphans) {
        $age = [DateTime]::UtcNow - $orphan.LastWriteTimeUtc
        if ($age.TotalMinutes -gt $MaxAgeMinutes) {
            Write-CoworkerLog -Message "Orphaned issue draft in working (age: $([Math]::Round($age.TotalMinutes))min): $($orphan.Name) — returning to ready" -Level WARN -Component 'refine-github-issues'

            $sidecarPath = Join-Path $workingDir "$($orphan.BaseName).retries.txt"
            $retryCount = 0
            if (Test-Path $sidecarPath) {
                $retryCount = [int](Get-Content $sidecarPath -Raw).Trim()
            }

            if ($retryCount -ge $MaxRetries) {
                $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
                $deadPath = Join-Path $errorDir "$timestamp-$($orphan.Name)"
                if ($PSCmdlet.ShouldProcess($orphan.Name, 'Move to error')) {
                    Move-Item -Path $orphan.FullName -Destination $deadPath -Force
                    Remove-Item $sidecarPath -ErrorAction SilentlyContinue
                }
                Write-CoworkerLog -Message "Dead-lettered: $($orphan.Name) (after $retryCount retries)" -Level ERROR -Component 'refine-github-issues'
            }
            else {
                $retryCount++
                $retryCount | Out-File -FilePath $sidecarPath -Encoding UTF8 -Force
                $readyPath = Join-Path $readyDir $orphan.Name
                if ($PSCmdlet.ShouldProcess($orphan.Name, 'Return to ready for retry')) {
                    Move-Item -Path $orphan.FullName -Destination $readyPath -Force
                }
                Write-CoworkerLog -Message "Returned orphan to ready (retry $retryCount/$MaxRetries): $($orphan.Name)" -Level WARN -Component 'refine-github-issues'
            }
        }
    }
}

# ── Target resolution ────────────────────────────────────────────────────────
function Get-IssueDraftTargets {
    param([string]$InputPath)

    if (-not (Test-Path $InputPath)) {
        throw "Issue draft path not found: $InputPath"
    }

    $item = Get-Item $InputPath

    if ($item.PSIsContainer) {
        return @(Get-ChildItem -Path $item.FullName -File |
            Where-Object { Test-CoworkerActionableDraftRefinementFile -Item $_ } |
            Sort-Object Name)
    }

    if (Test-CoworkerActionableDraftRefinementFile -Item $item) {
        return @($item)
    }

    return @()
}

function Resolve-UniquePath {
    param(
        [Parameter(Mandatory)] [string]$Directory,
        [Parameter(Mandatory)] [string]$BaseName,
        [Parameter(Mandatory)] [string]$Extension
    )

    $candidatePath = Join-Path $Directory "$BaseName$Extension"
    if (-not (Test-Path $candidatePath)) {
        return $candidatePath
    }

    $counter = 2
    while ($true) {
        $nextPath = Join-Path $Directory "$BaseName.$counter$Extension"
        if (-not (Test-Path $nextPath)) {
            return $nextPath
        }
        $counter++
    }
}

# ── Filename generation ──────────────────────────────────────────────────────
function New-IssueFileName {
    param(
        [Parameter(Mandatory)] [string]$Title,
        [string]$Extension = '.md'
    )

    if ([string]::IsNullOrWhiteSpace($Title)) {
        return "issue-$(Get-Date -Format 'yyyyMMdd-HHmmss')$Extension"
    }

    # Derive a kebab-case filename from the title
    $name = $Title.ToLowerInvariant() -replace '[^\w\s-]', ''
    $name = $name -replace '\s+', '-'
    $name = $name -replace '-{2,}', '-'
    $name = $name.Trim('-')

    if ([string]::IsNullOrWhiteSpace($name)) {
        return "issue-$(Get-Date -Format 'yyyyMMdd-HHmmss')$Extension"
    }

    # Truncate to reasonable length
    if ($name.Length -gt 80) {
        $name = $name.Substring(0, 80).TrimEnd('-')
    }

    return "$name$Extension"
}

# ── Issue parsing ────────────────────────────────────────────────────────────
function Split-ExtractedIssues {
    param(
        [Parameter(Mandatory)] [string]$AgentOutput,
        [Parameter(Mandatory)] [string]$SourceFileName
    )

    if ([string]::IsNullOrWhiteSpace($AgentOutput)) {
        throw "Agent returned empty output for $SourceFileName"
    }

    # Split on the boundary marker
    $blocks = $AgentOutput -split [regex]::Escape($script:IssueBoundary) |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    if ($blocks.Count -eq 0) {
        throw "No issue blocks found in agent output for $SourceFileName"
    }

    Write-CoworkerLog -Message "Extracted $($blocks.Count) issue(s) from $SourceFileName" -Level INFO -Component 'refine-github-issues'

    $issues = [System.Collections.ArrayList]::new()
    foreach ($block in $blocks) {
        $issue = Parse-IssueBlock -Block $block -SourceFileName $SourceFileName
        if ($null -ne $issue) {
            [void]$issues.Add($issue)
        }
    }

    if ($issues.Count -eq 0) {
        throw "Failed to parse any valid issues from agent output for $SourceFileName"
    }

    return @($issues)
}

function Parse-IssueBlock {
    param(
        [Parameter(Mandatory)] [string]$Block,
        [Parameter(Mandatory)] [string]$SourceFileName
    )

    $lines = @($block -split '\r?\n')

    # ── Parse title ───────────────────────────────────────────────────────
    $title = ''
    $bodyStartIndex = 0

    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^#\s+(?<title>.+)$') {
            $title = $Matches['title'].Trim()
            $bodyStartIndex = $i + 1
            break
        }
    }

    if ([string]::IsNullOrWhiteSpace($title)) {
        Write-CoworkerLog -Message "Issue block in $SourceFileName has no title (starting with '# '). Skipping block." -Level WARN -Component 'refine-github-issues'
        return $null
    }

    # ── Parse metadata lines from the end of the body ──────────────────────
    $labels = @()
    $assignees = @()
    $repo = ''

    $metadataPatterns = @(
        @{ Pattern = '^Labels:\s*(?<value>.+)$'; Target = 'labels' }
        @{ Pattern = '^Assignees:\s*(?<value>.+)$'; Target = 'assignees' }
        @{ Pattern = '^Repo:\s*(?<value>.+)$'; Target = 'repo' }
    )

    $bodyLines = [System.Collections.ArrayList]::new()
    for ($i = $bodyStartIndex; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $matched = $false

        foreach ($meta in $metadataPatterns) {
            if ($line -match $meta.Pattern) {
                $value = $Matches['value'].Trim()
                if ($value) {
                    switch ($meta.Target) {
                        'labels'    { $labels = @($value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
                        'assignees' { $assignees = @($value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
                        'repo'      { $repo = $value }
                    }
                }
                $matched = $true
                break
            }
        }

        if (-not $matched) {
            [void]$bodyLines.Add($line)
        }
    }

    # ── Clean body: trim trailing blank lines ──────────────────────────────
    while ($bodyLines.Count -gt 0 -and [string]::IsNullOrWhiteSpace($bodyLines[-1])) {
        [void]$bodyLines.RemoveAt($bodyLines.Count - 1)
    }

    $bodyContent = ($bodyLines -join "`n").Trim()

    return @{
        Title     = $title
        Body      = $bodyContent
        Labels    = $labels
        Assignees = $assignees
        Repo      = $repo
    }
}

# ── Issue file writing ───────────────────────────────────────────────────────
function Write-IssueFile {
    param(
        [Parameter(Mandatory)] [hashtable]$Issue,
        [Parameter(Mandatory)] [string]$OutputDirectory,
        [Parameter(Mandatory)] [string]$SourceFileName
    )

    $fileName = New-IssueFileName -Title $Issue.Title
    $outputPath = Resolve-UniquePath -Directory $OutputDirectory -BaseName ([System.IO.Path]::GetFileNameWithoutExtension($fileName)) -Extension '.md'

    $lines = [System.Collections.ArrayList]::new()
    [void]$lines.Add("# $($Issue.Title)")
    [void]$lines.Add('')

    if ($Issue.Body) {
        [void]$lines.Add($Issue.Body)
        [void]$lines.Add('')
    }

    if ($Issue.Labels -and $Issue.Labels.Count -gt 0) {
        [void]$lines.Add("Labels: $($Issue.Labels -join ', ')")
    }

    if ($Issue.Assignees -and $Issue.Assignees.Count -gt 0) {
        [void]$lines.Add("Assignees: $($Issue.Assignees -join ', ')")
    }

    if ($Issue.Repo) {
        [void]$lines.Add("Repo: $($Issue.Repo)")
    }

    $content = ($lines -join "`n").Trim() + "`n"

    if ($PSCmdlet.ShouldProcess($outputPath, 'Write issue file')) {
        Set-Content -Path $outputPath -Value $content -Encoding UTF8
    }

    Write-CoworkerLog -Message "Wrote issue to github/open: $([System.IO.Path]::GetFileName($outputPath)) — `"$($Issue.Title)`"" -Level INFO -Component 'refine-github-issues'

    return $outputPath
}

# ── Prompt construction ──────────────────────────────────────────────────────
function New-IssueExtractionPrompt {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string]$Content,
        [string]$Audience = 'GitHub issue readers (developers, maintainers, users)'
    )

    $boundary = $script:IssueBoundary

    return @"
You are processing a draft file that may contain one or more GitHub issues described in natural language.

TASK — extract, split, and format each distinct issue:

1. Identify every separate issue described in the draft.
2. For EACH issue, write a polished, self-contained GitHub issue in this format:

# <Clear, concise issue title>

<Well-written issue body including:
- Brief summary
- Steps to reproduce (if a bug)
- Expected vs actual behavior (if a bug)
- Any relevant context, constraints, or details
- Acceptance criteria if the draft suggests them>

Labels: <comma-separated labels, e.g. bug, enhancement, documentation>
Assignees: <GitHub username, only if specified in the draft>
Repo: <owner/repo, only if specified in the draft>

3. Separate each issue block with this exact marker on its own line:
$boundary

RULES:
- If the draft describes N distinct issues, produce N issue blocks.
- If the draft does NOT mention labels, assignees, or repo — OMIT those lines entirely (do NOT write "Labels:" with nothing after it).
- Preserve ALL technical details, reproduction steps, error messages, and context from the original draft.
- Polish the language for clarity and professionalism but do NOT change the technical meaning.
- The output must contain ONLY the issue blocks separated by the boundary marker.
- Do NOT include introductory text, explanations, code fences, or sign-offs.

Source file: $FilePath

--- BEGIN DRAFT ---
$Content
--- END DRAFT ---
"@
}

# ── Content validation ───────────────────────────────────────────────────────
function Test-ExtractionValid {
    param(
        [string]$AgentOutput,
        [string]$FileName
    )

    if ([string]::IsNullOrWhiteSpace($AgentOutput)) {
        Write-CoworkerLog -Message "Agent output is empty for: $FileName" -Level ERROR -Component 'refine-github-issues'
        return $false
    }

    # Reject conversational framing
    $badPrefixes = @(
        'Here is',
        'Certainly!',
        'Sure,',
        'I have',
        'Below is',
        'The following'
    )
    foreach ($prefix in $badPrefixes) {
        if ($AgentOutput.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            Write-CoworkerLog -Message "Agent output contains conversational prefix: '$prefix'. Will retry." -Level WARN -Component 'refine-github-issues'
            return $false
        }
    }

    # Must contain at least one issue boundary marker or a level-1 heading
    if (($AgentOutput -notmatch [regex]::Escape($script:IssueBoundary)) -and ($AgentOutput -notmatch '^#\s')) {
        Write-CoworkerLog -Message "Agent output lacks issue boundary markers or headings: $FileName" -Level WARN -Component 'refine-github-issues'
        return $false
    }

    return $true
}

# ── Auto-approve detection ────────────────────────────────────────────────────
function Test-AutoApprove {
    param(
        [Parameter(Mandatory)] [System.IO.FileInfo]$File
    )

    $lines = Get-Content -Path $File.FullName -Encoding UTF8
    if ($lines.Count -eq 0) {
        return $false
    }

    $tailStart = [Math]::Max(0, $lines.Count - 5)
    for ($i = $tailStart; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '#auto-approve') {
            return $true
        }
    }

    return $false
}

# ── Issue extraction (main worker function) ──────────────────────────────────
function Invoke-IssueExtraction {
    param(
        [Parameter(Mandatory)] [System.IO.FileInfo]$WorkingFile
    )

    $draftContent = Get-Content -Path $WorkingFile.FullName -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($draftContent)) {
        throw "Draft file is empty: $($WorkingFile.FullName)"
    }

    $prompt = New-IssueExtractionPrompt -FilePath $WorkingFile.FullName -Content $draftContent

    if ($DryRun) {
        Write-CoworkerLog -Message "[DRY RUN] Would invoke agent for: $($WorkingFile.Name)" -Level INFO -Component 'refine-github-issues'
        return @()
    }

    if (-not $PSCmdlet.ShouldProcess($WorkingFile.Name, 'Invoke agent for issue extraction')) {
        return @()
    }

    $agentOutput = Invoke-AgentWithRetry `
        -Prompt $prompt `
        -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') `
        -CaptureOutput `
        -TimeoutSeconds $TimeoutSeconds `
        -MaxRetries 2 `
        -LogComponent 'refine-github-issues' `
        -RepoRoot $repoRoot

    $agentOutput = $agentOutput.Trim("`r", "`n", ' ')

    if (-not (Test-ExtractionValid -AgentOutput $agentOutput -FileName $WorkingFile.Name)) {
        throw "Issue extraction validation failed for $($WorkingFile.Name)"
    }

    $issues = Split-ExtractedIssues -AgentOutput $agentOutput -SourceFileName $WorkingFile.Name

    return $issues
}

# ══════════════════════════════════════════════════════════════════════════════
# Main processing loop
# ══════════════════════════════════════════════════════════════════════════════

Restore-OrphanedWorkingFiles -MaxAgeMinutes 30

$targets = Get-IssueDraftTargets -InputPath $Path
if ($targets.Count -eq 0) {
    Write-CoworkerLog -Message "No actionable issue draft files found in $Path" -Level INFO -Component 'refine-github-issues'
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 0
}

Write-CoworkerLog -Message "Processing $($targets.Count) issue draft(s) from $Path" -Level INFO -Component 'refine-github-issues'
$failureCount = 0

foreach ($target in $targets) {
    $workingPath = Resolve-UniquePath -Directory $workingDir -BaseName $target.BaseName -Extension $target.Extension

    if ($PSCmdlet.ShouldProcess($target.Name, 'Move to working')) {
        Move-Item -Path $target.FullName -Destination $workingPath -Force
    }
    $workingFile = Get-Item $workingPath
    Write-CoworkerLog -Message "Moved to working: $workingPath" -Level DEBUG -Component 'refine-github-issues'

    # Remove any previous retry sidecar
    $sidecarPath = Join-Path $workingDir "$($workingFile.BaseName).retries.txt"
    Remove-Item $sidecarPath -ErrorAction SilentlyContinue

    try {
        $issues = Invoke-IssueExtraction -WorkingFile $workingFile

        if ($issues.Count -eq 0) {
            if ($DryRun) {
                Write-CoworkerLog -Message "[DRY RUN] Would move original to $($(if (Test-AutoApprove -File $workingFile) { 'github/open (#auto-approve)' } else { 'github/draft' })) (no issues extracted yet): $($workingFile.Name)" -Level INFO -Component 'refine-github-issues'
                continue
            }
            throw "No issues extracted from $($workingFile.Name)"
        }

        # Write each extracted issue to github/open
        $writtenPaths = @()
        foreach ($issue in $issues) {
            $outputPath = Write-IssueFile -Issue $issue -OutputDirectory $githubOpenDir -SourceFileName $workingFile.Name
            $writtenPaths += $outputPath
        }

        Write-CoworkerLog -Message "Wrote $($writtenPaths.Count) issue file(s) to github/open from $($workingFile.Name)" -Level INFO -Component 'refine-github-issues'

        # Route original: if #auto-approve is in the last 5 lines, send to
        # github/open so it gets committed as an issue too; otherwise send to github/draft for manual review.
        if (Test-AutoApprove -File $workingFile) {
            $autoApprovePath = Resolve-UniquePath -Directory $githubOpenDir -BaseName $workingFile.BaseName -Extension $workingFile.Extension
            if ($PSCmdlet.ShouldProcess($workingFile.Name, 'Move to github/open (#auto-approve)')) {
                Move-Item -Path $workingFile.FullName -Destination $autoApprovePath -Force
            }
            Write-CoworkerLog -Message "#auto-approve: moved original draft to github/open: $autoApprovePath" -Level INFO -Component 'refine-github-issues'
        }
        else {
            $draftPath = Resolve-UniquePath -Directory $githubDraftDir -BaseName $workingFile.BaseName -Extension $workingFile.Extension
            if ($PSCmdlet.ShouldProcess($workingFile.Name, 'Move to github/draft')) {
                Move-Item -Path $workingFile.FullName -Destination $draftPath -Force
            }
            Write-CoworkerLog -Message "Moved original draft to github/draft: $draftPath" -Level INFO -Component 'refine-github-issues'
        }
    }
    catch {
        $failureCount++
        Write-CoworkerLog -Message "Failed to process $($workingFile.Name): $_" -Level ERROR -Component 'refine-github-issues'

        # Return to ready for retry
        if (Test-Path $workingFile.FullName) {
            $readyRetryPath = Join-Path $readyDir $workingFile.Name
            if ($PSCmdlet.ShouldProcess($workingFile.Name, 'Return to ready for retry')) {
                Move-Item -Path $workingFile.FullName -Destination $readyRetryPath -Force
            }
            Write-CoworkerLog -Message "Returned to ready for retry: $($workingFile.Name)" -Level WARN -Component 'refine-github-issues'
        }
    }
}

if ($failureCount -gt 0) {
    Write-CoworkerLog -Message "Issue extraction complete with $failureCount failure(s) out of $($targets.Count)" -Level WARN -Component 'refine-github-issues'
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}

Write-CoworkerLog -Message "All $($targets.Count) issue draft(s) processed successfully." -Level INFO -Component 'refine-github-issues'
Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
exit 0
