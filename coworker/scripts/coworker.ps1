#!/usr/bin/env pwsh

# ============================================================================
# Coworker Task Runner - PowerShell Version
# ============================================================================
# Purpose:
#   Automatically processes task files in the 'created' directory
#   and executes them using the Copilot tool. Task files are moved through
#   a workflow: created -> working -> finished, with execution logs recorded.
#
# Task File Format (optional structured format):
#   Title: <task title>
#   Description: <task description>
#   Prompt: <task prompt content>
#
#   If not in structured format, the entire file content is treated as the prompt.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File coworker.ps1
# ============================================================================

param(
    [Parameter(Position=0)]
    [string]$TaskFile
)

# Handle specified TaskFile
if (-not [string]::IsNullOrWhiteSpace($TaskFile)) {
    # Resolve full path before changing location
    if (Test-Path $TaskFile) {
        $TaskFile = Resolve-Path $TaskFile
    }
}

$repoRoot = (git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Host "Repo root not found. Exiting."
    exit 1
}
Set-Location $repoRoot

# Import common utility script
. $repoRoot\bin\common\Util.ps1

Fix-Encoding-UTF8

$tasksRoot = Join-Path $repoRoot "coworker\tasks"
$scriptsDir = Join-Path $repoRoot "coworker\scripts"
$taskRoots = @(
    @{
        Prepare = (Join-Path $tasksRoot "0prepare")
        Created = (Join-Path $tasksRoot "1created")
        Working = (Join-Path $tasksRoot "2working")
        Finished = (Join-Path $tasksRoot "3complete")
        Review = (Join-Path $tasksRoot "4review")
        Approved = (Join-Path $tasksRoot "5approved")
        Pushed = (Join-Path $tasksRoot "6git-pushed")
        Logs = (Join-Path $tasksRoot "300logs")
        Label = "tasks"
    }
)

$logsDir = $taskRoots[0].Logs

# Ensure all required directories exist
# Create them if they don't already exist
foreach ($root in $taskRoots) {
    foreach ($dir in @($root.Prepare, $root.Created, $root.Working, $root.Finished, $root.Review, $root.Approved, $root.Pushed, $root.Logs)) {
        if (!(Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    }
}

# Handle specified TaskFile
if (-not [string]::IsNullOrWhiteSpace($TaskFile)) {
    if (Test-Path $TaskFile) {
        $fileItem = Get-Item $TaskFile
        $createdDir = $taskRoots[0].Created
        # Move directly to createdDir with original name
        $destPath = Join-Path $createdDir $fileItem.Name
        Move-Item -Path $fileItem.FullName -Destination $destPath -Force
        Write-Host "Moved specified task file to: $destPath"
    } else {
        Write-Error "Specified task file not found: $TaskFile"
        exit 1
    }
}

# Initialize script-level logging
# Main log file for all script output
$currentYear = Get-Date -Format "yyyy"
$currentMonth = Get-Date -Format "MM"
$currentDay = Get-Date -Format "dd"
$currentTime = Get-Date -Format "HHmmss"
$logsSubDir = Join-Path $logsDir "$currentYear\$currentMonth\$currentDay"
if (!(Test-Path $logsSubDir)) { New-Item -ItemType Directory -Path $logsSubDir | Out-Null }

$scriptLogPath = Join-Path $logsSubDir "${currentTime}-coworker.log"
$scriptStartTime = Get-Date

$copilotNameTimeoutSeconds = 60
$copilotRunTimeoutSeconds = 6000

# ============================================================================
# Logging Functions
# ============================================================================

# Function: Write message to console and main script log file
function Write-LogMessage {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Message,
        [ValidateSet('INFO', 'WARN', 'ERROR')]
        [string]$Level = 'INFO'
    )

    $timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $logEntry = "[$timestamp] [$Level] $Message"

    # Write to console
    switch ($Level) {
        'INFO' { Write-Host $logEntry }
        'WARN' { Write-Host $logEntry -ForegroundColor Yellow }
        'ERROR' { Write-Host $logEntry -ForegroundColor Red }
    }

    # Append to script log file
    $logEntry | Out-File -FilePath $scriptLogPath -Append -Encoding UTF8
}

# Function: Write message only to log file (for verbose output)
function Write-LogVerbose {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Message
    )

    $timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $logEntry = "[$timestamp] [DEBUG] $Message"

    # Append to script log file only (not console)
    $logEntry | Out-File -FilePath $scriptLogPath -Append -Encoding UTF8
}

function Get-TaskBaseName {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Title,
        [Parameter(Mandatory=$true)]
        [string]$Description,
        [Parameter(Mandatory=$true)]
        [string]$Prompt,
        [Parameter(Mandatory=$true)]
        [string]$Fallback
    )

    $promptSample = $Prompt
    if ($promptSample.Length -gt 600) {
        $promptSample = $promptSample.Substring(0, 600)
    }

    $namingPrompt = @"
Create a short, descriptive task name in kebab-case (3-6 words max). Output only the name.
Title: $Title
Description: $Description
Prompt: $promptSample
"@

    try {
        # Pass arguments as an array to avoid fragile manual escaping/quoting.
        # This is more reliable on Windows PowerShell when prompts contain quotes or newlines.
        $nameArgList = @(
            'copilot'
            '-p'
            $namingPrompt
        )

        Write-LogVerbose ("Executing GH Copilot for naming: gh {0}" -f ($nameArgList -join ' '))

        $nameStdOut = [System.IO.Path]::GetTempFileName()
        $nameStdErr = [System.IO.Path]::GetTempFileName()
        $nameProcess = Start-Process -FilePath 'gh' -ArgumentList $nameArgList -NoNewWindow -PassThru -RedirectStandardOutput $nameStdOut -RedirectStandardError $nameStdErr

        $waited = $false
        try {
            $null = Wait-Process -Id $nameProcess.Id -Timeout $copilotNameTimeoutSeconds -ErrorAction Stop
            $waited = $true
        } catch {
            $waited = $false
            Write-LogMessage "GH Copilot naming timed out after ${copilotNameTimeoutSeconds}s" WARN
        }

        if (-not $waited -or -not $nameProcess.HasExited) {
            Stop-Process -Id $nameProcess.Id -Force -ErrorAction SilentlyContinue

            if (Test-Path $nameStdErr) {
                $errContent = Get-Content $nameStdErr
                Write-LogVerbose "Naming Copilot STDERR (Timeout): $errContent"
            }

            Remove-Item $nameStdOut -ErrorAction SilentlyContinue
            Remove-Item $nameStdErr -ErrorAction SilentlyContinue
            return $Fallback
        }

        $rawName = ""
        if (Test-Path $nameStdOut) {
            $rawName = (Get-Content -Path $nameStdOut -Encoding UTF8 | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1)
            Write-LogVerbose "Naming Copilot STDOUT: $rawName"
        } else {
            Write-LogVerbose "Naming Copilot STDOUT file not found"
        }

        if (Test-Path $nameStdErr) {
            $errContent = Get-Content $nameStdErr -Encoding UTF8
            if ($errContent) {
                Write-LogVerbose "Naming Copilot STDERR: $errContent"
            }
        }

        Remove-Item $nameStdOut -ErrorAction SilentlyContinue
        Remove-Item $nameStdErr -ErrorAction SilentlyContinue

        if ($nameProcess.ExitCode -ne 0) {
            Write-LogVerbose "Naming Copilot exited with code $($nameProcess.ExitCode)"
            return $Fallback
        }

        if ([string]::IsNullOrWhiteSpace($rawName)) {
            Write-LogVerbose "Naming Copilot returned empty name"
            return $Fallback
        }

        $normalized = $rawName.Trim()
        $normalized = $normalized -replace '\s+', '-'
        $normalized = $normalized -replace '[^A-Za-z0-9._-]', '-'
        $normalized = $normalized -replace '-+', '-'
        $normalized = $normalized.Trim(' ', '.', '-', '_')
        if ($normalized.Length -gt 60) {
            $normalized = $normalized.Substring(0, 60).Trim(' ', '.', '-', '_')
        }

        if ([string]::IsNullOrWhiteSpace($normalized)) {
            return $Fallback
        }

        return $normalized
    }
    catch {
        return $Fallback
    }
}

function Resolve-UniquePath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Directory,
        [Parameter(Mandatory=$true)]
        [string]$BaseName,
        [Parameter(Mandatory=$true)]
        [string]$Extension
    )

    $candidateName = "$BaseName$Extension"
    $candidatePath = Join-Path $Directory $candidateName
    if (!(Test-Path $candidatePath)) {
        return @{ Path = $candidatePath; FileName = $candidateName }
    }

    $counter = 2
    while ($true) {
        $nextName = "$BaseName.$counter$Extension"
        $nextPath = Join-Path $Directory $nextName
        if (!(Test-Path $nextPath)) {
            return @{ Path = $nextPath; FileName = $nextName }
        }
        $counter++
    }
}

# Log script startup
Write-LogMessage "===========================================================================" INFO
Write-LogMessage "Coworker Task Runner - PowerShell Version" INFO
Write-LogMessage "Started at: $scriptStartTime" INFO
Write-LogMessage "Script Log: $scriptLogPath" INFO
Write-LogMessage "==========================================================================" INFO

foreach ($taskRoot in $taskRoots) {
    $prepareDir = $taskRoot.Prepare
    $createdDir = $taskRoot.Created
    $workingDir = $taskRoot.Working
    $finishedDir = $taskRoot.Finished
    $reviewDir = $taskRoot.Review
    $approvedDir = $taskRoot.Approved
    $pushedDir = $taskRoot.Pushed
    $logsDir = $taskRoot.Logs

    $currentYear = Get-Date -Format "yyyy"
    $currentMonth = Get-Date -Format "MM"
    $currentDay = Get-Date -Format "dd"
    $currentDate = Get-Date -Format "MMdd"
    $currentTime = Get-Date -Format "HHmmss"

    # 1. Process 0prepare
    $prepareFiles = Get-ChildItem -Path $prepareDir -File
    foreach ($file in $prepareFiles) {
        Write-LogMessage "[PREPARE] Task: $($file.Name)" INFO
    }

    # 2. Process 3complete (newly added to show pending reviews)
    if (Test-Path $finishedDir) {
        $finishedFiles = Get-ChildItem -Path $finishedDir -Recurse -File
        foreach ($file in $finishedFiles) {
            # Only show files from the last 24 hours to avoid noise
            if ($file.LastWriteTime -ge (Get-Date).AddDays(-1)) {
                Write-LogMessage "[COMPLETE] Task waiting for review: $($file.Name)" INFO
            }
        }
    }

    # 3. Process 4review
    $reviewFiles = Get-ChildItem -Path $reviewDir -File
    foreach ($file in $reviewFiles) {
        Write-LogMessage "[REVIEW] Task: $($file.Name)" INFO
    }

    # 4. Process 5approved
    # If there are any files in 5approved or its subdirectories, move them to 6git-pushed with date-based organization, and then call the commit script
    if (Test-Path $approvedDir) {
        $approvedFiles = Get-ChildItem -Path $approvedDir -Recurse -File
        if ($approvedFiles.Count -gt 0) {
            # Move files to pushed directory
            foreach ($file in $approvedFiles) {
                Write-Host "Moving approved task to pushed: $($file.FullName)" -ForegroundColor Green

                # Create date-based subdirectory: YYYY/MMDD
                $pushedSubDir = Join-Path $pushedDir "$currentYear\$currentDate"
                if (!(Test-Path $pushedSubDir)) {
                    New-Item -ItemType Directory -Path $pushedSubDir | Out-Null
                }

                $pushedInfo = Resolve-UniquePath -Directory $pushedSubDir -BaseName $file.BaseName -Extension $file.Extension
                Move-Item -Path $file.FullName -Destination $pushedInfo.Path -Force
                Write-LogMessage "Task moved to pushed: $($pushedInfo.Path)" INFO
            }

            # Call commit script
            $commitScript = Join-Path $scriptsDir "git-sync.ps1"
            if (Test-Path $commitScript) {
                Write-LogMessage "Executing commit script for approved tasks..." INFO
                & $commitScript
                if ($LASTEXITCODE -eq 0) {
                    Write-LogMessage "Git sync executed successfully." INFO
                } else {
                    Write-LogMessage "Git sync failed with exit code $LASTEXITCODE." ERROR
                }
            } else {
                Write-LogMessage "Commit script not found at $commitScript" WARN
            }
        }
    }

    # 5. Process 6git-pushed (last 2 days)
    # Recursively find files in 6git-pushed
    if (Test-Path $pushedDir) {
        $pushedFiles = Get-ChildItem -Path $pushedDir -Recurse -File
        $twoDaysAgo = (Get-Date).AddDays(-2)
        foreach ($file in $pushedFiles) {
            if ($file.LastWriteTime -ge $twoDaysAgo) {
                Write-LogMessage "[PUSHED] Task: $($file.Name) (updated $($file.LastWriteTime))" INFO
            }
        }
    }

    $files = Get-ChildItem -Path $createdDir -File

    # Process each task file found in the created directory
    foreach ($file in $files) {
        # 1. Determine the descriptive name based on content (while still in created dir)
        $renameScript = Join-Path $scriptsDir "rename.ps1"
        $descriptiveName = ""

        # Read content for fallback title
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
        $safeTitle = $file.BaseName -replace '[\\/*?:"<>|]', '_'
        if ([string]::IsNullOrWhiteSpace($safeTitle)) { $safeTitle = "task" }

        # Check if the file needs renaming (numeric or generic names, or always rename?)
        # User implies "numeric filenames are treated as random filenames... coworker needs to rename these"
        # The current implementation attempts to rename ALL files using gh copilot via rename.ps1.
        # This seems to cover the requirement "1.md, 2.md... are treated as random... rename these".

        Write-LogVerbose "renameScript path: $renameScript"
        Write-LogVerbose "Test-Path renameScript: $(Test-Path $renameScript)"

        if (Test-Path $renameScript) {
            # Execute rename.ps1 script
            $generatedName = & $renameScript -FilePath $file.FullName
            if (-not [string]::IsNullOrWhiteSpace($generatedName) -and $generatedName -notmatch "Error") {
                $descriptiveName = $generatedName
            }
        } else {
            # Fallback to internal function if rename.ps1 is missing
            $descriptiveName = Get-TaskBaseName -Title $safeTitle -Description "Task from $($file.Name)" -Prompt $content -Fallback $safeTitle
        }

        if ([string]::IsNullOrWhiteSpace($descriptiveName)) {
            $descriptiveName = $safeTitle
        }

        # 2. Rename in place (in created dir) then Move to working directory

        # Only rename if the name is different
        if ($descriptiveName -ne $file.BaseName) {
            $renamedPath = Join-Path $createdDir "$descriptiveName$($file.Extension)"
            if (Test-Path $renamedPath) {
                # Collision handling in created dir
                $counter = 2
                while (Test-Path (Join-Path $createdDir "$descriptiveName.$counter$($file.Extension)")) {
                    $counter++
                }
                $renamedPath = Join-Path $createdDir "$descriptiveName.$counter$($file.Extension)"
                $descriptiveName = "$descriptiveName.$counter"
            }
            Move-Item -Path $file.FullName -Destination $renamedPath -Force
            Write-LogMessage "Renamed in created: $($file.Name) -> $(Split-Path $renamedPath -Leaf)" INFO

            # Update $file to point to the new location for the next step (move to working)
            $file = Get-Item $renamedPath
        }

        # 3. Move to working directory
        $finalTaskInfo = Resolve-UniquePath -Directory $workingDir -BaseName $file.BaseName -Extension $file.Extension
        $workingPath = $finalTaskInfo.Path

        Move-Item -Path $file.FullName -Destination $workingPath -Force
        Write-LogMessage "Moved to working: $workingPath" INFO

        # 4. Parse content for execution (logging purposes)
        $title = $descriptiveName
        $description = "Task from $($file.Name)"
        $prompt = $content

        # Try to parse structured content
        if ($content -match "(?ms)^Title:\s*(?<title>.*?)(\r\n|\n)Description:\s*(?<desc>.*?)(\r\n|\n)Prompt:\s*(?<prompt>.*)$") {
            $title = $Matches['title'].Trim()
            $description = $Matches['desc'].Trim()
            $prompt = $Matches['prompt'].Trim()
        }

        # Define log file paths
        $workingBaseName = $finalTaskInfo.FileName -replace [regex]::Escape($file.Extension), ''

        $logsSubDir = Join-Path $logsDir "$currentYear\$currentMonth\$currentDay"
        if (!(Test-Path $logsSubDir)) { New-Item -ItemType Directory -Path $logsSubDir | Out-Null }

        $taskLogPath = Join-Path $logsSubDir "${currentTime}-${workingBaseName}.task.log"
        $copilotLogPath = Join-Path $logsSubDir "${currentTime}-${workingBaseName}.copilot.log"

        Write-LogVerbose "Task log will be written to: $taskLogPath"

        # Change working directory to repository root
        Push-Location $repoRoot

        Write-LogMessage "Executing Copilot for task: $workingBaseName" INFO
        Write-LogVerbose "Prompt length: $($prompt.Length) characters"

        # Record task execution details to task log
        @"
Task: $title
Description: $description
Original File: $($file.Name)
Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Prompt:
$prompt
---
Copilot Execution Output:
"@ | Out-File -FilePath $taskLogPath -Encoding UTF8

        try {
            # Pass arguments as an array to avoid fragile manual escaping/quoting.
            # This keeps quotes/newlines intact in the -p prompt.
            $copilotArgList = @(
                'copilot'
                '-p'
                $prompt
                '--allow-all-tools'
                '--allow-all-paths'
            )

            # Define paths for temporary output and error logs (for copilot external tool)
            $stdOutLog = $copilotLogPath + ".stdout"
            $stdErrLog = $copilotLogPath + ".stderr"

            Write-LogMessage "=== Starting Copilot execution ===" INFO

            # Execute Copilot tool with the task prompt
            # Capture both standard output and error output to separate files
            $process = Start-Process -FilePath 'gh' -ArgumentList $copilotArgList -NoNewWindow -PassThru -RedirectStandardOutput $stdOutLog -RedirectStandardError $stdErrLog

            $runWaited = $false
            $lastOutputLineCount = 0

            # Monitor output in real-time while process is running
            while (-not $process.HasExited) {
                Start-Sleep -Milliseconds 500

                # Check and display new stdout lines
                if (Test-Path $stdOutLog) {
                    $currentLines = @(Get-Content $stdOutLog -Encoding UTF8 -ErrorAction SilentlyContinue)
                    $currentLineCount = $currentLines.Count
                    if ($currentLineCount -gt $lastOutputLineCount) {
                        $newLines = $currentLines[$lastOutputLineCount..($currentLineCount - 1)]
                        foreach ($line in $newLines) {
                            if (-not [string]::IsNullOrWhiteSpace($line)) {
                                Write-Host $line
                            }
                        }
                        $lastOutputLineCount = $currentLineCount
                    }
                }

                # Check timeout
                $elapsed = (Get-Date) - $process.StartTime
                if ($elapsed.TotalSeconds -gt $copilotRunTimeoutSeconds) {
                    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                    Write-LogMessage "Copilot timed out after ${copilotRunTimeoutSeconds}s" WARN
                    Write-Host "[TIMEOUT] Copilot execution exceeded ${copilotRunTimeoutSeconds}s timeout" -ForegroundColor Yellow
                    break
                }
            }

            # Final output capture after process ends
            if (Test-Path $stdOutLog) {
                $remainingLines = @(Get-Content $stdOutLog -Encoding UTF8 -ErrorAction SilentlyContinue)
                if ($remainingLines.Count -gt $lastOutputLineCount) {
                    $newLines = $remainingLines[$lastOutputLineCount..($remainingLines.Count - 1)]
                    foreach ($line in $newLines) {
                        if (-not [string]::IsNullOrWhiteSpace($line)) {
                            Write-Host $line
                        }
                    }
                }
            }

            # Capture stderr output and display to console
            if (Test-Path $stdErrLog) {
                $errContent = @(Get-Content $stdErrLog -Encoding UTF8 -ErrorAction SilentlyContinue)
                if ($errContent) {
                    Write-Host "`n[STDERR OUTPUT]" -ForegroundColor Yellow
                    foreach ($line in $errContent) {
                        if (-not [string]::IsNullOrWhiteSpace($line)) {
                            Write-Host $line -ForegroundColor Yellow
                        }
                    }
                }
            }

            # Combine copilot stdout and stderr logs into the copilot-specific log
            # First append stdout if it exists
            if (Test-Path $stdOutLog) { Get-Content $stdOutLog -Encoding UTF8 | Out-File -FilePath $copilotLogPath -Append -Encoding UTF8 }
            # Then append stderr if it exists and contains content
            if (Test-Path $stdErrLog) {
                $errContent = Get-Content $stdErrLog -Encoding UTF8
                if ($errContent) {
                    "`r`n=== COPILOT STDERR ===`r`n" | Out-File -FilePath $copilotLogPath -Append -Encoding UTF8
                    $errContent | Out-File -FilePath $copilotLogPath -Append -Encoding UTF8
                }
            }

            # Clean up temporary log files
            Remove-Item $stdOutLog -ErrorAction SilentlyContinue
            Remove-Item $stdErrLog -ErrorAction SilentlyContinue

            Write-LogMessage "Copilot execution finished with exit code $($process.ExitCode)" INFO
            Write-LogMessage "=== Copilot execution completed ===" INFO
            Write-LogVerbose "Copilot external tool log: $copilotLogPath"

            # Append copilot result to task log
            @"

Copilot Exit Code: $($process.ExitCode)
Copilot Log: $copilotLogPath
"@ | Out-File -FilePath $taskLogPath -Append -Encoding UTF8

            # Warn if Copilot exited with an error code
            if ($process.ExitCode -ne 0) {
                Write-LogMessage "Warning: Copilot exited with non-zero code. Check log: $copilotLogPath" WARN
            }
        }
        catch {
            # Handle any errors that occur during Copilot execution
            Write-LogMessage "Failed to execute copilot: $_" ERROR
            "Error executing copilot: $_" | Out-File -FilePath $taskLogPath -Append -Encoding UTF8
        }
        finally {
            # Always return to the original directory after execution
            Pop-Location
        }

        # Move completed task from working directory to finished or approved directory
        # Check for #auto-approve tag in content
        $autoApprove = $content -match '#auto-approve'

        # Create date-based subdirectory: YYYY/MMDD
        $currentYear = Get-Date -Format "yyyy"
        $currentDate = Get-Date -Format "MMdd"

        if ($autoApprove) {
            $destDir = Join-Path $approvedDir "$currentYear\$currentDate"
            $destLabel = "approved (auto-approve)"
        } else {
            $destDir = Join-Path $finishedDir "$currentYear\$currentDate"
            $destLabel = "finished"
        }

        if (!(Test-Path $destDir)) {
            New-Item -ItemType Directory -Path $destDir | Out-Null
        }

        $destInfo = Resolve-UniquePath -Directory $destDir -BaseName $workingBaseName -Extension $file.Extension

        Move-Item -Path $workingPath -Destination $destInfo.Path -Force
        Write-LogMessage "Task moved to ${destLabel}: $($destInfo.Path)" INFO


        Write-LogMessage "---" INFO
    }
}

# Log script completion
$scriptEndTime = Get-Date
Write-LogMessage "===========================================================================" INFO
Write-LogMessage "All tasks completed" INFO
Write-LogMessage "Ended at: $scriptEndTime" INFO
Write-LogMessage "Script Log: $scriptLogPath" INFO
Write-LogMessage "==========================================================================" INFO

