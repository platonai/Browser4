#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

param(
    # Only delete items older than this many hours (default: 24).
    [ValidateRange(1, 24 * 365)]
    [int]$MinAgeHours = 24,

    # Preview mode: show what would be deleted without actually deleting.
    [switch]$WhatIf
)

# Function to safely remove specific items
function Remove-ItemsSafely {
    param(
        [string]$Path,
        [string]$Description,
        [string[]]$Patterns,
        [int]$MinAgeHours,
        [switch]$WhatIf
    )

    if (Test-Path $Path) {
        $cutoff = (Get-Date).AddHours(-$MinAgeHours)
        Write-Host "Clearing $Description with patterns: $($Patterns -join ', ') (min age: ${MinAgeHours}h)..." -ForegroundColor Yellow

        # Remove matching files
        try {
            $matchedFiles = Get-ChildItem -Path $Path -Recurse -Force -File |
                Where-Object {
                    $file = $_.FullName
                    (($Patterns | Where-Object { $file -match $_ }) -ne $null) -and
                    ($_.LastWriteTime -lt $cutoff)
                }

            if ($matchedFiles) {
                if ($WhatIf) {
                    Write-Host "[WhatIf] Would remove $($matchedFiles.Count) matching file(s) from $Description" -ForegroundColor DarkYellow
                } else {
                    $matchedFiles | Remove-Item -Force -ErrorAction SilentlyContinue
                    Write-Host "Removed $($matchedFiles.Count) matching files from $Description" -ForegroundColor Green
                }
            } else {
                Write-Host "No matching old files found in $Description" -ForegroundColor DarkYellow
            }
        }
        catch {
            Write-Warning "Some files in $Description could not be removed: $($_.Exception.Message)"
        }

        # Remove matching empty directories
        try {
            $matchedDirs = Get-ChildItem -Path $Path -Recurse -Force -Directory |
                Where-Object {
                    $dir = $_.FullName
                    (($Patterns | Where-Object { $dir -match $_ }) -ne $null) -and
                    ($_.LastWriteTime -lt $cutoff)
                } |
                Sort-Object FullName -Descending |
                Where-Object { (Get-ChildItem $_.FullName -Force | Measure-Object).Count -eq 0 }

            if ($matchedDirs) {
                if ($WhatIf) {
                    Write-Host "[WhatIf] Would remove $($matchedDirs.Count) matching empty director(ies) from $Description" -ForegroundColor DarkYellow
                } else {
                    $matchedDirs | Remove-Item -Force -ErrorAction SilentlyContinue
                    Write-Host "Removed $($matchedDirs.Count) matching empty directories from $Description" -ForegroundColor Green
                }
            } else {
                Write-Host "No matching old empty directories found in $Description" -ForegroundColor DarkYellow
            }
        }
        catch {
            Write-Warning "Some directories in $Description could not be removed: $($_.Exception.Message)"
        }
    }
    else {
        Write-Warning "$Description path not found: $Path"
    }
}

# Removes VS installer residue folders that match a strict name pattern and contain the required file set.
function Remove-VSInstallerTempFolders {
    param(
        [string]$Path,
        [string]$Description,
        [int]$MinAgeHours,
        [switch]$WhatIf
    )

    if (-not (Test-Path $Path)) {
        Write-Warning "$Description path not found: $Path"
        return
    }

    $cutoff = (Get-Date).AddHours(-$MinAgeHours)
    $folderNameRegex = '^\w{8}\.\w{3}$'
    $requiredFiles = @(
        'vs_installer.windows.exe',
        'vs_installer.windows.exe.config',
        'Microsoft.VisualStudio.Setup.ToastNotification.exe',
        'Microsoft.VisualStudio.Setup.ToastNotification.exe.config'
    )

    Write-Host "Scanning $Description for VS installer temp folders (name: $folderNameRegex, min age: ${MinAgeHours}h)..." -ForegroundColor Yellow

    try {
        $candidateDirs = Get-ChildItem -Path $Path -Force -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match $folderNameRegex }

        if (-not $candidateDirs) {
            Write-Host "No candidate VS installer temp folders found in $Description" -ForegroundColor DarkYellow
            return
        }

        $removed = 0
        $failed = 0
        $skippedYoung = 0

        foreach ($dir in $candidateDirs) {
            # Age safeguard to avoid interfering with an ongoing VS install/update.
            if ($dir.LastWriteTime -ge $cutoff) {
                $skippedYoung++
                continue
            }

            $hasAll = $true
            foreach ($fileName in $requiredFiles) {
                if (-not (Test-Path (Join-Path $dir.FullName $fileName))) {
                    $hasAll = $false
                    break
                }
            }

            if (-not $hasAll) {
                continue
            }

            try {
                if ($WhatIf) {
                    Write-Host "[WhatIf] Would remove VS installer temp folder: $($dir.FullName)" -ForegroundColor DarkYellow
                    $removed++
                } else {
                    Remove-Item -LiteralPath $dir.FullName -Recurse -Force -ErrorAction Stop
                    $removed++
                    Write-Host "Removed VS installer temp folder: $($dir.FullName)" -ForegroundColor Green
                }
            }
            catch {
                $failed++
                Write-Warning "Failed to remove folder $($dir.FullName): $($_.Exception.Message)"
            }
        }

        if ($skippedYoung -gt 0) {
            Write-Host "Skipped $skippedYoung candidate folder(s) because they are newer than ${MinAgeHours}h" -ForegroundColor DarkYellow
        }

        if ($removed -eq 0 -and $failed -eq 0) {
            Write-Host "No matching old VS installer temp folders to remove in $Description" -ForegroundColor DarkYellow
        }
        elseif ($failed -eq 0) {
            Write-Host "Removed $removed VS installer temp folder(s) from $Description" -ForegroundColor Green
        }
        else {
            Write-Warning "Removed $removed VS installer temp folder(s) from $Description, failed: $failed"
        }
    }
    catch {
        Write-Warning "Failed scanning $Description for VS installer temp folders: $($_.Exception.Message)"
    }
}

# Define temp directories
$systemTemp = [System.IO.Path]::GetTempPath()
$userTemp = $env:LOCALAPPDATA + "\Temp"

# Define patterns to match
$patterns = @('tomcat', 'chrome', 'test', 'pkg-', '.jar', 'koltin', '.tmp', '.ps', '.ps1',
'tsServerCancellationPipes', 'pulsar-skill-', 'playwright-'
)

Write-Host "Starting targeted temporary file cleanup..." -ForegroundColor Cyan
Write-Host "MinAgeHours: $MinAgeHours; WhatIf: $WhatIf" -ForegroundColor Cyan
Write-Host "Will only remove items containing: $($patterns -join ', ')" -ForegroundColor Cyan

# Remove VS installer residue folders (strict match + required files + age safeguard)
Remove-VSInstallerTempFolders -Path $systemTemp -Description "System Temp ($systemTemp)" -MinAgeHours $MinAgeHours -WhatIf:$WhatIf
Remove-VSInstallerTempFolders -Path $userTemp -Description "User Temp ($userTemp)" -MinAgeHours $MinAgeHours -WhatIf:$WhatIf

# Clear System Temp with patterns
Remove-ItemsSafely -Path $systemTemp -Description "System Temp ($systemTemp)" -Patterns $patterns -MinAgeHours $MinAgeHours -WhatIf:$WhatIf

# Clear User Temp with patterns
Remove-ItemsSafely -Path $userTemp -Description "User Temp ($userTemp)" -Patterns $patterns -MinAgeHours $MinAgeHours -WhatIf:$WhatIf

Write-Host "`nTargeted temporary files cleanup completed successfully!" -ForegroundColor Green
