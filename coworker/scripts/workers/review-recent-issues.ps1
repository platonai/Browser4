#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Find recently created .issues.md files in draft/ and review/, move them to
    review/ if needed, and run inline AI review on each.

.DESCRIPTION
    Scans coworker/tasks/issues/draft/ and coworker/tasks/issues/review/ for
    .issues.md files modified within the last N days (default: 3).

    Directories whose names start with '.' are silently skipped.

    For each file the worker:
    1. If the file is in draft/, moves it to review/ (preserving the relative
       path under review/ to avoid name collisions)
    2. Calls b4w.ps1 coworker review -Inline -Path <path> to run AI batch
       review on all issues in the file
    3. The review command parses issues, AI-reviews them, writes decisions,
       and moves the file to tasks/main/1ready/ for Coworker execution

    Files already in review/done/ or review/done/discard/ are excluded (those
    have already been reviewed).

.PARAMETER RecentDays
    Only process files modified within this many days. Default: 3.

.PARAMETER DraftDir
    Path to the draft issues directory. Defaults to tasks/issues/draft.

.PARAMETER ReviewDir
    Path to the review issues directory. Defaults to tasks/issues/review.

.PARAMETER AutoApprove
    Pass -AutoApprove to the review command so reviewed issues skip manual
    approval and go straight to 5approved.

.PARAMETER DryRun
    Show what would be done without actually moving files or calling review.

.PARAMETER MaxFiles
    Limit processing to the first N files. Default: 20. Use 0 for unlimited.

.PARAMETER TimeoutMinutes
    Maximum time to wait for each review call. Default: 3.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [int]$RecentDays = 3,

    [string]$DraftDir = '',

    [string]$ReviewDir = '',

    [switch]$AutoApprove,

    [switch]$DryRun,

    [int]$MaxFiles = 20,

    [int]$TimeoutMinutes = 3
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path (Split-Path -Parent $workerDir) 'config.ps1')

# ── Script-level mutex ──────────────────────────────────────────────────────
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-CoworkerLog -Component 'review-recent-issues' -Level 'WARN' `
        -Message 'Another review-recent-issues.ps1 instance is already running. Exiting.'
    exit 0
}

$repoRoot = Get-WorkspaceRoot

# ── Directories ──────────────────────────────────────────────────────────────
$issuesRoot = Resolve-TasksPath 'issues'

if ([string]::IsNullOrWhiteSpace($DraftDir)) {
    $DraftDir = Join-Path $issuesRoot 'draft'
}
if ([string]::IsNullOrWhiteSpace($ReviewDir)) {
    $ReviewDir = Join-Path $issuesRoot 'review'
}

$reviewDoneDir    = Join-Path $ReviewDir 'done'
$reviewDiscardDir = Join-Path $reviewDoneDir 'discard'

# Ensure review/ exists
if (-not (Test-Path $ReviewDir)) {
    New-Item -ItemType Directory -Path $ReviewDir -Force | Out-Null
}

# ── Helper: test whether a directory name starts with '.' ────────────────────
function Test-IsDotDirectory {
    param([Parameter(Mandatory)] [string]$DirectoryName)
    return $DirectoryName.StartsWith('.')
}

# ── Helper: check if a path is under a done/discard tree ─────────────────────
function Test-IsAlreadyReviewed {
    param([Parameter(Mandatory)] [string]$FilePath)

    $normalized = [System.IO.Path]::GetFullPath($FilePath)
    $doneNorm = [System.IO.Path]::GetFullPath($reviewDoneDir)
    $discardNorm = [System.IO.Path]::GetFullPath($reviewDiscardDir)

    return ($normalized.StartsWith($doneNorm, [StringComparison]::OrdinalIgnoreCase) -or
            $normalized.StartsWith($discardNorm, [StringComparison]::OrdinalIgnoreCase))
}

# ── Helper: resolve a path relative to repo root for b4w.ps1 ────────────────
function Get-RepoRelativePath {
    param([Parameter(Mandatory)] [string]$AbsolutePath)

    $repoNorm = [System.IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/')
    $fileNorm = [System.IO.Path]::GetFullPath($AbsolutePath)

    if ($fileNorm.StartsWith($repoNorm, [StringComparison]::OrdinalIgnoreCase)) {
        $relative = $fileNorm.Substring($repoNorm.Length).TrimStart('\', '/')
        return $relative -replace '\\', '/'
    }
    return $AbsolutePath
}

# ── Recursively collect recent .issues.md files ──────────────────────────────
function Find-RecentIssueFiles {
    param(
        [Parameter(Mandatory)] [string]$BaseDirectory,
        [Parameter(Mandatory)] [datetime]$CutoffDate,
        [string]$SourceLabel = 'unknown'
    )

    if (-not (Test-Path $BaseDirectory)) {
        Write-CoworkerLog -Component 'review-recent-issues' -Level 'DEBUG' `
            -Message "Directory not found, skipping: $BaseDirectory"
        return @()
    }

    $result = [System.Collections.ArrayList]::new()

    try {
        $stack = [System.Collections.Stack]::new()
        $stack.Push($BaseDirectory)

        while ($stack.Count -gt 0) {
            $currentDir = $stack.Pop()

            # Skip dot-directories (but not the root scan directory itself)
            $dirName = Split-Path -Leaf $currentDir
            if ($currentDir -ne $BaseDirectory -and (Test-IsDotDirectory -DirectoryName $dirName)) {
                Write-CoworkerLog -Component 'review-recent-issues' -Level 'DEBUG' `
                    -Message "Skipping dot-directory: $currentDir"
                continue
            }

            # Collect .issues.md files modified after the cutoff
            $files = Get-ChildItem -Path $currentDir -File -Filter '*.issues.md' -ErrorAction SilentlyContinue |
                Where-Object { $_.LastWriteTime -ge $CutoffDate } |
                Where-Object { -not (Test-IsAlreadyReviewed -FilePath $_.FullName) }

            foreach ($file in $files) {
                [void]$result.Add(@{
                    FileInfo     = $file
                    SourceLabel  = $SourceLabel
                    SourceDir    = $BaseDirectory
                    IsInDraft    = ($SourceLabel -eq 'draft')
                })
            }

            # Push subdirectories onto the stack
            $subdirs = Get-ChildItem -Path $currentDir -Directory -ErrorAction SilentlyContinue |
                Where-Object { -not (Test-IsDotDirectory -DirectoryName $_.Name) }
            foreach ($subdir in $subdirs) {
                $stack.Push($subdir.FullName)
            }
        }
    }
    catch {
        Write-CoworkerLog -Component 'review-recent-issues' -Level 'ERROR' `
            -Message "Error scanning $BaseDirectory : $_"
    }

    # Sort: draft files first (they need moving), then by modification time (newest first)
    $sorted = $result | Sort-Object { -not $_.IsInDraft }, { $_.FileInfo.LastWriteTime.ToString('o') }
    return , @($sorted)
}

# ── Move a file from draft/ to review/ ──────────────────────────────────────
function Move-DraftToReview {
    param(
        [Parameter(Mandatory)] [System.IO.FileInfo]$File,
        [string]$DraftBase = $DraftDir
    )

    $draftNorm = [System.IO.Path]::GetFullPath($DraftBase)
    $fileNorm  = [System.IO.Path]::GetFullPath($File.FullName)

    # Compute relative path under draft/
    $relativePath = $fileNorm.Substring($draftNorm.Length).TrimStart('\', '/')
    $reviewNorm  = [System.IO.Path]::GetFullPath($ReviewDir)

    # Preserve any date-based subdirectory structure
    $destDir = Join-Path $reviewNorm (Split-Path -Parent $relativePath)
    $destPath = Join-Path $destDir $File.Name

    # Ensure destination directory exists
    if (-not (Test-Path $destDir)) {
        if ($PSCmdlet.ShouldProcess($destDir, 'Create directory')) {
            New-Item -ItemType Directory -Path $destDir -Force | Out-Null
        }
    }

    # Handle collisions: append a counter
    if (Test-Path $destPath) {
        $baseName = $File.BaseName
        $ext = $File.Extension
        $counter = 2
        while (Test-Path $destPath) {
            $destPath = Join-Path $destDir "$baseName.$counter$ext"
            $counter++
        }
    }

    if ($DryRun) {
        Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
            -Message "[DRY RUN] Would move draft→review: $($File.Name) → $destPath"
        return $destPath
    }

    if ($PSCmdlet.ShouldProcess($File.Name, 'Move draft to review')) {
        Move-Item -Path $File.FullName -Destination $destPath -Force
    }

    Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
        -Message "Moved draft→review: $($File.Name) → $(Split-Path -Leaf (Split-Path -Parent $destPath))/\$(Split-Path -Leaf $destPath)"

    return $destPath
}

# ── Run inline review for a single file ─────────────────────────────────────
function Invoke-ReviewForFile {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [switch]$WithAutoApprove
    )

    $relativePath = Get-RepoRelativePath -AbsolutePath $FilePath
    $b4wScript = Join-Path $repoRoot 'b4w.ps1'

    $reviewArgs = @('coworker', 'review', '-Inline', '-Path', $relativePath)
    if ($WithAutoApprove) {
        $reviewArgs += '-AutoApprove'
    }

    Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
        -Message "Calling review: b4w.ps1 $($reviewArgs -join ' ')"

    if ($DryRun) {
        Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
            -Message "[DRY RUN] Would call: b4w.ps1 $($reviewArgs -join ' ')"
        return @{ Success = $true; Output = '[DRY RUN]'; Path = $FilePath }
    }

    if (-not $PSCmdlet.ShouldProcess($relativePath, 'Run inline AI review')) {
        return @{ Success = $true; Output = 'Skipped by user'; Path = $FilePath }
    }

    $stdOutPath = [System.IO.Path]::GetTempFileName()
    $stdErrPath = [System.IO.Path]::GetTempFileName()

    try {
        $process = Start-Process -FilePath 'pwsh' `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $b4wScript) + $reviewArgs `
            -PassThru -NoNewWindow `
            -RedirectStandardOutput $stdOutPath `
            -RedirectStandardError $stdErrPath

        $timeoutMs = $TimeoutMinutes * 60 * 1000
        $completed = $process.WaitForExit($timeoutMs)

        $stdOut = if (Test-Path $stdOutPath) { Get-Content -Path $stdOutPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue } else { '' }
        $stdErr = if (Test-Path $stdErrPath) { Get-Content -Path $stdErrPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue } else { '' }

        if (-not $completed) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            Write-CoworkerLog -Component 'review-recent-issues' -Level 'ERROR' `
                -Message "Review timed out after ${TimeoutMinutes}min: $relativePath"
            return @{ Success = $false; Output = "Timeout after ${TimeoutMinutes}min"; Path = $FilePath; Error = $stdErr }
        }

        if ($process.ExitCode -ne 0) {
            Write-CoworkerLog -Component 'review-recent-issues' -Level 'ERROR' `
                -Message "Review failed (exit code $($process.ExitCode)): $relativePath"
            if ($stdErr) {
                Write-CoworkerLog -Component 'review-recent-issues' -Level 'ERROR' -Message "stderr: $stdErr"
            }
            return @{ Success = $false; Output = $stdOut; Path = $FilePath; Error = $stdErr; ExitCode = $process.ExitCode }
        }

        Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
            -Message "Review completed: $relativePath"

        return @{ Success = $true; Output = $stdOut; Path = $FilePath }
    }
    catch {
        Write-CoworkerLog -Component 'review-recent-issues' -Level 'ERROR' `
            -Message "Exception during review of $relativePath : $_"
        return @{ Success = $false; Output = ''; Path = $FilePath; Error = $_ }
    }
    finally {
        Remove-Item $stdOutPath -ErrorAction SilentlyContinue
        Remove-Item $stdErrPath -ErrorAction SilentlyContinue
    }
}

# ══════════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════════

$cutoffDate = (Get-Date).AddDays(-$RecentDays)
Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
    -Message "Scanning for .issues.md files modified since $($cutoffDate.ToString('yyyy-MM-dd HH:mm:ss')) (last $RecentDays day(s))"

# ── Discover recent files ───────────────────────────────────────────────────
$draftFiles  = Find-RecentIssueFiles -BaseDirectory $DraftDir -CutoffDate $cutoffDate -SourceLabel 'draft'
$reviewFiles = Find-RecentIssueFiles -BaseDirectory $ReviewDir -CutoffDate $cutoffDate -SourceLabel 'review'

# Deduplicate: a file might appear in both scans (if draft/ is inside the review scan area)
$seen = [System.Collections.Generic.HashSet[string]]::new()
$allFiles = [System.Collections.ArrayList]::new()
foreach ($entry in ($draftFiles + $reviewFiles)) {
    $key = $entry.FileInfo.FullName
    if ($seen.Add($key)) {
        [void]$allFiles.Add($entry)
    }
}

# Sort: draft files first, then newest first
$allFiles = @($allFiles | Sort-Object { -not $_.IsInDraft }, { $_.FileInfo.LastWriteTime.ToString('o') })

if ($MaxFiles -gt 0 -and $allFiles.Count -gt $MaxFiles) {
    $allFiles = @($allFiles | Select-Object -First $MaxFiles)
    Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
        -Message "Limited to first $MaxFiles file(s)"
}

if ($allFiles.Count -eq 0) {
    Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
        -Message "No recent .issues.md files found (cutoff: last $RecentDays day(s))."
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 0
}

$draftCount = ($allFiles | Where-Object { $_.IsInDraft }).Count
$reviewOnlyCount = $allFiles.Count - $draftCount
Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
    -Message "Found $($allFiles.Count) recent file(s): $draftCount in draft/, $reviewOnlyCount in review/ (excluding done/discard)"

# ── Process each file ────────────────────────────────────────────────────────
$successCount = 0
$failureCount = 0

foreach ($entry in $allFiles) {
    $filePath = $entry.FileInfo.FullName
    $fileName = $entry.FileInfo.Name

    Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
        -Message "Processing: $fileName ($(if ($entry.IsInDraft) { 'draft' } else { 'review' }))"

    try {
        # Step 1: Move draft files to review/
        if ($entry.IsInDraft) {
            $filePath = Move-DraftToReview -File $entry.FileInfo
            if (-not $filePath) {
                Write-CoworkerLog -Component 'review-recent-issues' -Level 'ERROR' `
                    -Message "Failed to move draft file: $fileName"
                $failureCount++
                continue
            }
        }

        # Step 2: Run inline review
        $result = Invoke-ReviewForFile -FilePath $filePath -WithAutoApprove:$AutoApprove

        if ($result.Success) {
            $successCount++
        }
        else {
            $failureCount++
            Write-CoworkerLog -Component 'review-recent-issues' -Level 'WARN' `
                -Message "Review failed for $fileName — file remains in review/ for retry"
        }
    }
    catch {
        $failureCount++
        Write-CoworkerLog -Component 'review-recent-issues' -Level 'ERROR' `
            -Message "Unhandled error processing $fileName : $_"
    }
}

# ── Summary ─────────────────────────────────────────────────────────────────
Write-CoworkerLog -Component 'review-recent-issues' -Level 'INFO' `
    -Message "Done: $successCount succeeded, $failureCount failed (out of $($allFiles.Count) total)"

Remove-CoworkerScriptLock -Lock $script:__CoworkerLock

if ($failureCount -gt 0) {
    exit 1
}
exit 0
