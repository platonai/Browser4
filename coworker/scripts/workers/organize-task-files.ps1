#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Reorganize task directories with more than 10 files into YYYY/MMDD subdirectories.

.DESCRIPTION
    Scans all directories recursively under the coworker tasks root. For any
    directory containing more than 10 files directly (not counting files in
    subdirectories), moves those files into YYYY/MMDD subdirectories based on
    each file's last git commit timestamp (falling back to file creation time).

    Files already inside a date-pattern subdirectory (matching \d{4}/\d{4}) are
    not reorganized. The .locks directory is skipped entirely.

    The script is idempotent: directories with <= 10 files and directories
    already containing date-organized content are left untouched.

.PARAMETER DryRun
    Show what would be done without moving any files.

.EXAMPLE
    .\organize-task-files.ps1

.EXAMPLE
    .\organize-task-files.ps1 -DryRun
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
. $configPath

# ── Script-level mutex ───────────────────────────────────────────────────────
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-CoworkerLog -Component 'organize-task-files' -Level 'WARN' -Message 'Another instance is already running. Exiting.'
    exit 0
}

$repoRoot = Get-WorkspaceRoot
$tasksRoot = Get-TasksRoot

# ══════════════════════════════════════════════════════════════════════════════
# Helper Functions
# ══════════════════════════════════════════════════════════════════════════════

function Get-FileDateUtc {
<#
.SYNOPSIS
    Get the last git commit date for a file, falling back to file creation time.

.DESCRIPTION
    Uses git log to retrieve the Unix epoch timestamp of the last commit that
    touched the file. If the file is not committed or git fails, falls back to
    the file's CreationTime from the filesystem.
#>
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath
    )

    # Try git log for last commit timestamp (Unix epoch seconds)
    $epoch = git -C $repoRoot log -1 --format='%ct' -- $FilePath 2>$null
    if (-not [string]::IsNullOrWhiteSpace($epoch)) {
        $parsed = $epoch.Trim()
        if ($parsed -match '^\d+$') {
            return ([DateTimeOffset]::FromUnixTimeSeconds([long]$parsed)).UtcDateTime
        }
    }

    # Fallback: file creation time (for uncommitted files)
    return (Get-Item -LiteralPath $FilePath).CreationTime.ToUniversalTime()
}

function Test-IsDatePatternLeaf {
<#
.SYNOPSIS
    Returns $true if the directory is a date-pattern leaf (MMDD under YYYY).

.DESCRIPTION
    Detects directories that are already date-organized. A directory matches
    when its name is a 4-digit day (e.g. "0621") AND its parent name is a
    4-digit year (e.g. "2026"). These directories are skipped during
    reorganization to prevent nesting like 2026/0621/2026/0621/.
#>
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.DirectoryInfo]$Directory
    )

    if ($Directory.Name -match '^\d{4}$') {
        $parent = $Directory.Parent
        if ($null -ne $parent -and $parent.Name -match '^\d{4}$') {
            return $true
        }
    }
    return $false
}

function Get-CollisionSafePath {
<#
.SYNOPSIS
    Generate a unique destination path, appending a numeric suffix on collision.

.DESCRIPTION
    If the requested destination path already exists, appends -1, -2, -3, etc.
    to the base filename until a non-existent path is found.
#>
    param(
        [Parameter(Mandatory = $true)]
        [string]$DestinationDir,
        [Parameter(Mandatory = $true)]
        [string]$FileName
    )

    $candidatePath = Join-Path $DestinationDir $FileName
    if (-not (Test-Path -LiteralPath $candidatePath)) {
        return $candidatePath
    }

    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($FileName)
    $extension = [System.IO.Path]::GetExtension($FileName)
    $counter = 1
    do {
        $newName = "{0}-{1}{2}" -f $baseName, $counter, $extension
        $candidatePath = Join-Path $DestinationDir $newName
        $counter++
    } while (Test-Path -LiteralPath $candidatePath)

    return $candidatePath
}

function Invoke-OrganizeDirectory {
<#
.SYNOPSIS
    Reorganize a single directory if it has more than 10 direct child files.

.DESCRIPTION
    Counts direct child files in the directory (filtered by
    Test-CoworkerPendingFile). If the count exceeds 10, moves each file into a
    YYYY/MMDD subdirectory based on its date.

    Returns the number of files moved (0 if below threshold).
#>
    param(
        [Parameter(Mandatory = $true)]
        [string]$DirectoryPath
    )

    # Get direct child files only, filtered by coworker conventions
    $files = @(Get-ChildItem -LiteralPath $DirectoryPath -File -ErrorAction SilentlyContinue |
        Where-Object { Test-CoworkerPendingFile -Item $_ })

    if ($files.Count -le 10) {
        return 0
    }

    Write-CoworkerLog -Component 'organize-task-files' -Level 'INFO' `
        -Message "Directory with $($files.Count) files (over threshold): $DirectoryPath"

    $movedCount = 0
    $errorCount = 0

    foreach ($file in $files) {
        try {
            $timestamp = Get-FileDateUtc -FilePath $file.FullName
            $year = $timestamp.ToString('yyyy')
            $date = $timestamp.ToString('MMdd')
            $targetDir = Join-Path $DirectoryPath $year $date
            $destPath = Get-CollisionSafePath -DestinationDir $targetDir -FileName $file.Name

            if ($DryRun) {
                Write-CoworkerLog -Component 'organize-task-files' -Level 'INFO' `
                    -Message "(DryRun) Would move: $($file.Name) -> ${year}\${date}\$($file.Name)"
                $movedCount++
                continue
            }

            Ensure-CoworkerDirectory -Path $targetDir
            Move-Item -LiteralPath $file.FullName -Destination $destPath -Force -ErrorAction Stop
            Write-CoworkerLog -Component 'organize-task-files' -Level 'INFO' `
                -Message "Moved: $($file.Name) -> ${year}\${date}\$($file.Name)"
            $movedCount++
        }
        catch {
            Write-CoworkerLog -Component 'organize-task-files' -Level 'WARN' `
                -Message "Failed to move $($file.Name): $_"
            $errorCount++
        }
    }

    if ($errorCount -gt 0) {
        Write-CoworkerLog -Component 'organize-task-files' -Level 'WARN' `
            -Message "$errorCount file(s) failed to move in $DirectoryPath"
    }

    return $movedCount
}

# ══════════════════════════════════════════════════════════════════════════════
# Main Logic
# ══════════════════════════════════════════════════════════════════════════════

function Invoke-OrganizeTaskFiles {
    $mode = if ($DryRun) { 'DRY RUN' } else { 'LIVE' }
    Write-CoworkerLog -Component 'organize-task-files' -Level 'INFO' `
        -Message "Starting task file organization scan ($mode). Tasks root: $tasksRoot"

    if (-not (Test-Path -LiteralPath $tasksRoot -PathType Container)) {
        Write-CoworkerLog -Component 'organize-task-files' -Level 'ERROR' `
            -Message "Tasks root directory not found: $tasksRoot"
        return @{ moved = 0; dirsScanned = 0; dirsSkipped = 0 }
    }

    # Collect all directories recursively
    $allDirs = @(Get-ChildItem -LiteralPath $tasksRoot -Directory -Recurse -ErrorAction SilentlyContinue |
        Where-Object {
            # Exclude the .locks directory and anything under it
            $_.FullName -notmatch ([regex]::Escape([System.IO.Path]::DirectorySeparatorChar) + '\.locks')
        })

    Write-CoworkerLog -Component 'organize-task-files' -Level 'INFO' `
        -Message "Found $($allDirs.Count) directories to evaluate"

    $totalMoved = 0
    $skippedDatePattern = 0

    foreach ($dir in $allDirs) {
        # Skip directories that are already date-pattern leaves (YYYY/MMDD)
        if (Test-IsDatePatternLeaf -Directory $dir) {
            $skippedDatePattern++
            continue
        }

        $moved = Invoke-OrganizeDirectory -DirectoryPath $dir.FullName
        $totalMoved += $moved
    }

    Write-CoworkerLog -Component 'organize-task-files' -Level 'INFO' `
        -Message "Organization complete: $totalMoved file(s) moved, $skippedDatePattern date-pattern directories skipped, $($allDirs.Count) total directories scanned"

    return @{
        moved        = $totalMoved
        dirsScanned  = $allDirs.Count
        dirsSkipped  = $skippedDatePattern
    }
}

# ── Run ──────────────────────────────────────────────────────────────────────
try {
    $result = Invoke-OrganizeTaskFiles

    $jsonSummary = @{
        moved        = $result.moved
        dirsScanned  = $result.dirsScanned
        dirsSkipped  = $result.dirsSkipped
        dryRun       = $DryRun.IsPresent
        completedAt  = Get-CoworkerTimestamp
    } | ConvertTo-Json -Compress

    Write-Host $jsonSummary
    exit 0
}
finally {
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
}
