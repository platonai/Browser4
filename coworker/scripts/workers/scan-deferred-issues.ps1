#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Scan all .issues.json and .issues.md files created/modified in the last N days,
    collect every issue marked as "Defer" (DEFER), and write a consolidated report
    to coworker/tasks/issues/.report/.

.DESCRIPTION
    Walks the coworker/tasks/issues/ tree (skipping dot-directories), finds
    .issues.json and .issues.md files whose LastWriteTime falls within
    the scan window, extracts every issue whose review decision is DEFER, and
    writes a timestamped markdown report.

    Supported formats:

    .issues.json (draft — rare for DEFER, but handled):
        { "issues": [{ "number": 1, "title": "...", "severity": "...",
           "category": "...", "review": { "decision": "DEFER", "notes": "..." } }] }

    .issues.md (reviewed):
        ### Issue N: <title>
        **Severity:** High
        **Category:** Reliability
        ...
        #### Human Review
        - [x] **DEFER** — ...
        - **Notes:**
        <rationale>

    Dot-directories (e.g. .report, .claude, .git) are skipped during the scan so
    the report output directory never feeds back into future scans.

.PARAMETER DaysBack
    Number of days to look back. Defaults to 7.

.PARAMETER OutputDir
    Directory to write the report into. Defaults to coworker/tasks/issues/.report.

.PARAMETER Quiet
    Suppress console summary output (useful when run by the scheduler).

.EXAMPLE
    .\coworker\scripts\workers\scan-deferred-issues.ps1
    (scans last 7 days, writes report to coworker/tasks/issues/.report/)

.EXAMPLE
    .\coworker\scripts\workers\scan-deferred-issues.ps1 -DaysBack 14 -Quiet
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [int]$DaysBack = 7,

    [string]$OutputDir = '',

    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

# ── Dot-source coworker config (loads Util, Paths, Watchers, Logging, Locks) ─
$workerDir = $PSScriptRoot
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
if (-not (Test-Path -LiteralPath $configPath)) {
    # Fallback: run without full coworker config (manual path resolution)
    Write-Warning "config.ps1 not found at $configPath — running with manual path resolution."
    $issuesRoot = Join-Path (Get-Location) 'coworker\tasks\issues'
} else {
    . $configPath
    $issuesRoot = Resolve-TasksPath 'issues'
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $issuesRoot '.report'
}

# ── Ensure output directory exists ─────────────────────────────────────────
if (-not (Test-Path -LiteralPath $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

# ── Scan window ────────────────────────────────────────────────────────────
$now = Get-Date
$cutoff = $now.AddDays(-$DaysBack)

# ── Helper: is a directory component a dot-directory? ──────────────────────
function Test-PathHasDotDirectory {
    param([string]$Path)
    # Split the relative path into components; flag any component that
    # starts with "." (but is not "." or "..").
    $parts = ($Path -replace '\\', '/') -split '/'
    foreach ($part in $parts) {
        if ($part.StartsWith('.') -and $part -ne '.' -and $part -ne '..') {
            return $true
        }
    }
    return $false
}

# ── Helper: extract DEFER issues from a .issues.json file ──────────────────
function Get-DeferredIssuesFromJson {
    param(
        [string]$FilePath,
        [datetime]$Cutoff
    )

    if ((Get-Item -LiteralPath $FilePath).LastWriteTime -lt $Cutoff) {
        return @()
    }

    try {
        $content = Get-Content -Raw -LiteralPath $FilePath -ErrorAction Stop
        $data = $content | ConvertFrom-Json -ErrorAction Stop
    } catch {
        Write-Warning "Failed to parse JSON: $FilePath — $_"
        return @()
    }

    $meta = $data.meta
    $scenario  = if ($meta) { $meta.scenario } else { '' }
    $sourceDate = if ($meta -and $meta.date) { $meta.date } else { '' }

    $deferred = @()
    foreach ($issue in $data.issues) {
        $review = $issue.review
        if (-not $review) { continue }
        if ($review.decision -ne 'DEFER') { continue }

        $deferred += [PSCustomObject]@{
            Number     = $issue.number
            Title      = $issue.title
            Severity   = $issue.severity
            Category   = $issue.category
            Notes      = $review.notes
            SourceFile = (Resolve-Path -Relative -LiteralPath $FilePath) -replace '\\', '/'
            Scenario   = $scenario
            SourceDate = $sourceDate
        }
    }
    return $deferred
}

# ── Helper: extract DEFER issues from a .issues.md file ────────────────────
function Get-DeferredIssuesFromMarkdown {
    param(
        [string]$FilePath,
        [datetime]$Cutoff
    )

    if ((Get-Item -LiteralPath $FilePath).LastWriteTime -lt $Cutoff) {
        return @()
    }

    try {
        $lines = Get-Content -LiteralPath $FilePath -ErrorAction Stop
    } catch {
        Write-Warning "Failed to read: $FilePath — $_"
        return @()
    }

    # Parse header metadata (first ~10 lines)
    $scenario   = ''
    $sourceDate = ''
    for ($i = 0; $i -lt [Math]::Min($lines.Count, 10); $i++) {
        $line = $lines[$i]
        if ($line -match '^# Issues:\s*(.+)$') {
            $scenario = $Matches[1].Trim()
        }
        if ($line -match '\*\*Date:\*\*\s*(\d{14,})') {
            $sourceDate = $Matches[1]
        }
        if ($line -match '^## Issues Found') { break }
    }

    $deferred = @()
    $i = 0
    while ($i -lt $lines.Count) {
        # Each issue block begins with "### Issue N:" (not "## Issues Found")
        if ($lines[$i] -match '^### Issue (\d+):\s*(.+)$') {
            $issueNum   = [int]$Matches[1]
            $issueTitle = $Matches[2].Trim()
            $severity   = ''
            $category   = ''
            $notes      = ''
            $isDeferred = $false

            # Collect lines for this block until the next "### Issue N" or "## "
            $j = $i + 1
            $blockLines = @()
            while ($j -lt $lines.Count -and $lines[$j] -notmatch '^### Issue \d+' -and $lines[$j] -notmatch '^## ') {
                $blockLines += $lines[$j]
                $j++
            }

            for ($k = 0; $k -lt $blockLines.Count; $k++) {
                $bl = $blockLines[$k]
                if ($bl -match '^\*\*Severity:\*\*\s*(.+)$') {
                    $severity = $Matches[1].Trim()
                }
                if ($bl -match '^\*\*Category:\*\*\s*(.+)$') {
                    $category = $Matches[1].Trim()
                }
                # Detect a checked DEFER checkbox
                if ($bl -match '- \[[xX]\] \*\*DEFER\*\*') {
                    $isDeferred = $true
                }
                # Collect the notes paragraph
                if ($isDeferred -and $bl -match '^- \*\*Notes:\*\*$') {
                    $notesLines = @()
                    for ($n = $k + 1; $n -lt $blockLines.Count; $n++) {
                        $nl = $blockLines[$n]
                        # Stop at a different checkbox, a new section heading, or a horizontal rule
                        if ($nl -match '^- \[.?\] \*\*[A-Z]' -or $nl -match '^#### ' -or $nl -match '^---') {
                            break
                        }
                        # Skip leading blank lines before notes content
                        if ($notesLines.Count -eq 0 -and [string]::IsNullOrWhiteSpace($nl)) {
                            continue
                        }
                        # Stop at a blank line after we have content (notes complete)
                        if ($notesLines.Count -gt 0 -and [string]::IsNullOrWhiteSpace($nl)) {
                            break
                        }
                        $notesLines += $nl
                    }
                    $notes = ($notesLines -join "`n").Trim()
                }
            }

            if ($isDeferred) {
                $deferred += [PSCustomObject]@{
                    Number     = $issueNum
                    Title      = $issueTitle
                    Severity   = $severity
                    Category   = $category
                    Notes      = $notes
                    SourceFile = (Resolve-Path -Relative -LiteralPath $FilePath) -replace '\\', '/'
                    Scenario   = $scenario
                    SourceDate = $sourceDate
                }
            }

            $i = $j
        } else {
            $i++
        }
    }
    return $deferred
}

# ── Main scan ──────────────────────────────────────────────────────────────

Write-Host '=== Deferred Issues Scanner ===' -ForegroundColor Cyan
Write-Host "Scan window : $($cutoff.ToString('yyyy-MM-dd HH:mm:ss'))  →  $($now.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "Issues root : $issuesRoot"
Write-Host "Output dir  : $OutputDir"
Write-Host ''

$allDeferred = [System.Collections.Generic.List[PSCustomObject]]::new()
$filesScanned = 0
$jsonCount = 0
$mdCount = 0

# Walk the issues tree recursively, gathering .issues.json / .issues.md files.
# Dot-directories are excluded so the .report output folder is never scanned.
$allFiles = Get-ChildItem -LiteralPath $issuesRoot -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match '\.issues\.(json|md)$'
    } |
    Where-Object {
        # Compute path relative to issuesRoot, then check for dot-directory components
        $rel = $_.FullName.Substring($issuesRoot.Length).TrimStart('\', '/')
        return -not (Test-PathHasDotDirectory -Path $rel)
    }

foreach ($file in $allFiles) {
    $ext = $file.Extension.ToLower()
    $filesScanned++

    if ($ext -eq '.json') {
        $deferred = @(Get-DeferredIssuesFromJson -FilePath $file.FullName -Cutoff $cutoff)
        if ($deferred.Count -gt 0) {
            $jsonCount++
            foreach ($d in $deferred) { $allDeferred.Add($d) }
        }
    } elseif ($ext -eq '.md') {
        $deferred = @(Get-DeferredIssuesFromMarkdown -FilePath $file.FullName -Cutoff $cutoff)
        if ($deferred.Count -gt 0) {
            $mdCount++
            foreach ($d in $deferred) { $allDeferred.Add($d) }
        }
    }
}

# ── Summarize ──────────────────────────────────────────────────────────────

$totalDeferred = $allDeferred.Count

$bySeverity = $allDeferred | Group-Object -Property Severity | Sort-Object Count -Descending
$byCategory = $allDeferred | Group-Object -Property Category | Sort-Object Count -Descending
$bySource   = $allDeferred | Group-Object -Property SourceFile | Sort-Object Count -Descending

# ── Generate report ────────────────────────────────────────────────────────

$timestamp  = $now.ToString('yyyyMMdd-HHmmss')
$reportName = "deferred-issues-report-${timestamp}.md"
$reportPath = Join-Path $OutputDir $reportName

$R = @()
$R += '# Deferred Issues Report'
$R += ''
$R += "> **Generated:** $($now.ToString('yyyy-MM-dd HH:mm:ss'))"
$R += "> **Scan window:** $($cutoff.ToString('yyyy-MM-dd')) to $($now.ToString('yyyy-MM-dd')) ($DaysBack days)"
$R += "> **Total files scanned:** $filesScanned"
$R += "> **Files with deferred issues:** $(($jsonCount + $mdCount)) ($jsonCount .issues.json, $mdCount .issues.md)"
$R += "> **Total deferred issues:** $totalDeferred"
$R += ''

# ── Severity breakdown ─────────────────────────────────────────────────────

$R += '## Summary by Severity'
$R += ''
$R += '| Severity | Count |'
$R += '|----------|-------|'
foreach ($grp in $bySeverity) {
    $label = if ([string]::IsNullOrWhiteSpace($grp.Name)) { '(unset)' } else { $grp.Name }
    $R += "| $label | $($grp.Count) |"
}
$R += ''

# ── Category breakdown ─────────────────────────────────────────────────────

$R += '## Summary by Category'
$R += ''
$R += '| Category | Count |'
$R += '|----------|-------|'
foreach ($grp in $byCategory) {
    $label = if ([string]::IsNullOrWhiteSpace($grp.Name)) { '(unset)' } else { $grp.Name }
    $R += "| $label | $($grp.Count) |"
}
$R += ''

# ── Per-file breakdown ─────────────────────────────────────────────────────

$R += '## Summary by Source File'
$R += ''
$R += '| Source File | Deferred |'
$R += '|-------------|----------|'
foreach ($grp in $bySource) {
    $R += "| $($grp.Name) | $($grp.Count) |"
}
$R += ''

# ── Full detail table ──────────────────────────────────────────────────────

$R += '## Deferred Issues — Full Detail'
$R += ''

$sorted = $allDeferred | Sort-Object -Property SourceFile, Number

$currentSource = ''
foreach ($issue in $sorted) {
    if ($issue.SourceFile -ne $currentSource) {
        $currentSource = $issue.SourceFile
        $R += "### $currentSource"
        $R += ''
        if ($issue.Scenario) {
            $R += "> **Scenario:** $($issue.Scenario)  "
        }
        if ($issue.SourceDate) {
            $d = $issue.SourceDate
            if ($d.Length -ge 8) {
                try {
                    $d = ([datetime]::ParseExact($d.Substring(0, 8), 'yyyyMMdd', $null)).ToString('yyyy-MM-dd')
                } catch { }
            }
            $R += "> **Date:** $d  "
        }
        $R += ''
        $R += '| # | Title | Severity | Category |'
        $R += '|---|-------|----------|----------|'
    }
    $R += "| $($issue.Number) | $($issue.Title) | $($issue.Severity) | $($issue.Category) |"
}
$R += ''

# ── Deferral rationale ─────────────────────────────────────────────────────

$withNotes = @($allDeferred | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Notes) })
if ($withNotes.Count -gt 0) {
    $R += '## Deferral Rationale (Notes)'
    $R += ''

    $currentSource = ''
    foreach ($issue in ($withNotes | Sort-Object -Property SourceFile, Number)) {
        if ($issue.SourceFile -ne $currentSource) {
            $currentSource = $issue.SourceFile
            $R += "### $currentSource"
            $R += ''
        }
        $R += "**Issue #$($issue.Number) — $($issue.Title)**"
        $R += ''
        # Quote each note line with "> " so it renders as a blockquote
        $quoted = ($issue.Notes -split "`n" | ForEach-Object { "> $_" }) -join "`n"
        $R += $quoted
        $R += ''
    }
}

# ── Footer ─────────────────────────────────────────────────────────────────

$R += '---'
$R += ''
$R += "*Report generated by \`scan-deferred-issues.ps1\` at $($now.ToString('yyyy-MM-dd HH:mm:ss'))*"
$R += ''

$reportContent = $R -join "`n"
Set-Content -LiteralPath $reportPath -Value $reportContent -Encoding UTF8

# ── Console summary ────────────────────────────────────────────────────────

if (-not $Quiet) {
    Write-Host '=== Scan Complete ===' -ForegroundColor Green
    Write-Host "Files scanned      : $filesScanned"
    Write-Host "Deferred issues    : $totalDeferred"
    Write-Host "Report written to  : $reportPath"
    Write-Host ''
    if ($totalDeferred -gt 0) {
        Write-Host 'By severity:' -ForegroundColor Yellow
        foreach ($grp in $bySeverity) {
            $label = if ([string]::IsNullOrWhiteSpace($grp.Name)) { '(unset)' } else { $grp.Name }
            Write-Host "  $label : $($grp.Count)"
        }
        Write-Host ''
        Write-Host 'By category:' -ForegroundColor Yellow
        foreach ($grp in $byCategory) {
            $label = if ([string]::IsNullOrWhiteSpace($grp.Name)) { '(unset)' } else { $grp.Name }
            Write-Host "  $label : $($grp.Count)"
        }
    } else {
        Write-Host 'No deferred issues found in the scan window.' -ForegroundColor Green
    }
}

# ── Return a result object for programmatic callers ─────────────────────────
@{
    ReportPath     = $reportPath
    TotalScanned   = $filesScanned
    TotalDeferred  = $totalDeferred
    BySeverity     = $bySeverity
    DeferredIssues = $allDeferred
}
