#!/usr/bin/env pwsh

# ============================================================================
# Coworker Task Runner - PowerShell Version
# ============================================================================
# Purpose:
#   Automatically processes task files in the 'created' directory
#   and executes them using the agent tool. Task files are moved through
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

# $env:GH_DEBUG = 'api'      # 打印 API 请求
# $env:GH_DEBUG = '1'        # 打印调试信息

# Load task logger early for startup message
$taskLoggerHelper = Join-Path $PSScriptRoot 'workers\task-logger.ps1'
if (Test-Path -LiteralPath $taskLoggerHelper) {
    . $taskLoggerHelper
}

Write-ConsoleLine -Message "Starting Coworker Task Runner..." -ForegroundColor Cyan

$configScriptPath = Join-Path $PSScriptRoot 'config.ps1'
. $configScriptPath

# ── Script-level mutex: only one coworker.ps1 instance at a time ─────────
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-ConsoleLine -Message "Another coworker.ps1 instance is already running. Exiting." -ForegroundColor Yellow
    exit 0
}

# Handle specified TaskFile
if (-not [string]::IsNullOrWhiteSpace($TaskFile)) {
    # Resolve full path before changing location
    if (Test-Path $TaskFile) {
        try {
            $TaskFile = Resolve-Path $TaskFile -ErrorAction Stop
        } catch {
            Write-Error "Failed to resolve path '$TaskFile': $_"
            exit 1
        }
    }
}

$repoRoot = Get-WorkspaceRoot

$tasksRoot = Join-Path $repoRoot "coworker\tasks"
$scriptsDir = $PSScriptRoot
$agentHelper = Join-Path $scriptsDir "workers\agent.ps1"
. $agentHelper
$workflowHelper = Join-Path $scriptsDir "workers\workflow.ps1"
. $workflowHelper
$targetRepoRoot = $repoRoot
$agentCommand = $null
$agentExecutable = $null
$agentBaseArgs = @()
$agentBackend = 'copilot'
$agentWorkingDirectory = $repoRoot

$logsDir = Get-LogDirectory
$memoryDir = $logsDir

$taskRoots = @(
    @{
        Prepare = (Join-Path $tasksRoot "main\0draft")
        Created = (Join-Path $tasksRoot "main\1ready")
        Working = (Join-Path $tasksRoot "main\2working")
        Finished = (Join-Path $tasksRoot "main\3done")
        Review = (Join-Path $tasksRoot "main\4review")
        Approved = (Join-Path $tasksRoot "main\5approved")
        Pushed = (Join-Path $tasksRoot "main\6git-pushed")
        Logs = $logsDir
        Label = "tasks"
    }
)

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
        Write-ConsoleLine -Message "Moved specified task file to: $destPath"
    } else {
        Write-Error "Specified task file not found: $TaskFile"
        exit 1
    }
}

# Initialize script-level logging
# Main log file for all script output
$currentYear = (Get-Date).ToUniversalTime().ToString("yyyy")
$currentMonth = (Get-Date).ToUniversalTime().ToString("MM")
$currentDay = (Get-Date).ToUniversalTime().ToString("dd")
$currentDate = "$currentMonth$currentDay"
$currentTime = (Get-Date).ToUniversalTime().ToString("HHmmss")
$logsSubDir = Join-Path $logsDir "$currentYear\$currentMonth\$currentDay"
if (!(Test-Path $logsSubDir)) { New-Item -ItemType Directory -Path $logsSubDir | Out-Null }

$scriptLogPath = Join-Path $logsSubDir "${currentTime}-coworker.log"
$script:__ScriptLogPath = $scriptLogPath
$scriptStartTime = (Get-Date).ToUniversalTime()

$agentNameTimeoutSeconds = 60
$agentRunTimeoutSeconds = 6000

try {
    $targetRepoRoot = Get-TargetRepositoryRoot
    $agentCommand = Get-AgentCommand -RepoRoot $targetRepoRoot
    $agentExecutable = $agentCommand.Executable
    $agentBaseArgs = $agentCommand.BaseArgs
    $agentBackend = $agentCommand.Backend
    $agentWorkingDirectory = $agentCommand.WorkingDirectory
}
catch {
    Write-LogMessage "Failed to resolve target repository root for task execution: $_" ERROR
    exit 1
}

Write-LogMessage "Control repository root: $repoRoot" INFO
Write-LogMessage "Target repository root: $targetRepoRoot" INFO
Write-LogMessage "Agent working directory: $agentWorkingDirectory" INFO

# Log script startup
Write-LogMessage "===========================================================================" INFO
Write-LogMessage "Coworker Task Runner - PowerShell Version" INFO
Write-LogMessage "Started at: $scriptStartTime" INFO
Write-LogMessage "Script Log: $scriptLogPath" INFO
Write-LogMessage "==========================================================================" INFO

foreach ($taskRoot in $taskRoots) {
    $draftDir = $taskRoot.Prepare
    $createdDir = $taskRoot.Created
    $workingDir = $taskRoot.Working
    $finishedDir = $taskRoot.Finished
    $reviewDir = $taskRoot.Review
    $approvedDir = $taskRoot.Approved
    $pushedDir = $taskRoot.Pushed
    $logsDir = $taskRoot.Logs

    Ensure-DraftPlaceholders -DraftDirectory $draftDir

    # 1. Process 0draft
    $prepareFiles = Get-ChildItem -Path $draftDir -File |
        Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }
    foreach ($file in $prepareFiles) {
        Write-LogMessage "[PREPARE] Task: $($file.Name)" INFO
    }

    # 2. Process 3done (newly added to show pending reviews)
    if (Test-Path $finishedDir) {
        $finishedFiles = Get-ChildItem -Path $finishedDir -Recurse -File |
            Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }
        foreach ($file in $finishedFiles) {
            # Only show files from the last 24 hours to avoid noise
            if ($file.LastWriteTimeUtc -ge (Get-Date).ToUniversalTime().AddDays(-1)) {
                Write-LogMessage "[COMPLETE] Task waiting for review: $($file.Name)" INFO
            }
        }
    }

    # 3. Process 4review
    $reviewFiles = Get-ChildItem -Path $reviewDir -File |
        Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }
    foreach ($file in $reviewFiles) {
        Write-LogMessage "[REVIEW] Task: $($file.Name)" INFO
    }

    # 4. Process 5approved
    # If there are any files in 5approved or its subdirectories, move them to 6git-pushed with date-based organization, and then call the commit script
    if (Test-Path $approvedDir) {
        $approvedFiles = Get-ChildItem -Path $approvedDir -Recurse -File |
            Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }
        if ($approvedFiles.Count -gt 0) {
            # Move files to pushed directory
            foreach ($file in $approvedFiles) {
                Write-ConsoleLine -Message "Moving approved task to pushed: $($file.FullName)" -ForegroundColor Green

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
            $commitScript = Join-Path $scriptsDir "workers/git-sync.ps1"
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
        $pushedFiles = Get-ChildItem -Path $pushedDir -Recurse -File |
            Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }
        $twoDaysAgo = (Get-Date).ToUniversalTime().AddDays(-2)
        foreach ($file in $pushedFiles) {
            if ($file.LastWriteTimeUtc -ge $twoDaysAgo) {
                Write-LogMessage "[PUSHED] Task: $($file.Name) (updated $($file.LastWriteTime))" INFO
            }
        }
    }

    $files = Get-ChildItem -Path $createdDir -File |
        Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }

    # Process each task file found in the created directory
    foreach ($file in $files) {
        # 1. Determine the descriptive name based on content (while still in created dir)
        $renameScript = Join-Path $scriptsDir "workers\rename.ps1"
        $descriptiveName = ""

        # Read content for fallback title
        if (-not (Test-Path -Path $file.FullName)) {
            Write-LogMessage "[SKIP] Task file vanished before reading: $($file.FullName) — likely moved/renamed by another process" WARN
            continue
        }
        try {
            $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction Stop
        } catch {
            Write-LogMessage "[ERROR] Failed to read task file: $($file.FullName) — $($_.Exception.Message)" ERROR
            continue
        }
        $safeTitle = $file.BaseName -replace '[\\/*?:"<>|]', '_'
        if ([string]::IsNullOrWhiteSpace($safeTitle)) { $safeTitle = "task" }

        # Check if the file needs renaming (numeric or generic names, or always rename?)
        # User implies "numeric filenames are treated as random filenames... coworker needs to rename these"
        # The current implementation attempts to rename ALL files using the agent via rename.ps1.
        # This seems to cover the requirement "improve-coworker-daily-memory-generator.md, 2.md... are treated as random... rename these".


        if (Test-Path $renameScript) {
            # Execute rename.ps1 script with retry
            $maxRetries = 3
            $retryCount = 0
            $success = $false

            while (-not $success -and $retryCount -lt $maxRetries) {
                try {
                    $generatedName = & $renameScript -FilePath $file.FullName

                    # check for common failure patterns in output
                    if (-not [string]::IsNullOrWhiteSpace($generatedName) -and
                        $generatedName -notmatch "Error" -and
                        $generatedName -notmatch "Timeout") {
                        $descriptiveName = $generatedName
                        $success = $true
                    } else {
                        Write-LogVerbose "Rename returned invalid name: $generatedName"
                        $retryCount++
                        if ($retryCount -lt $maxRetries) { Start-Sleep -Seconds ([Math]::Pow(2, $retryCount)) }
                    }
                } catch {
                    $retryCount++
                    Write-LogMessage "Rename script failed (Attempt $retryCount/$maxRetries): $_" WARN
                    if ($retryCount -lt $maxRetries) { Start-Sleep -Seconds ([Math]::Pow(2, $retryCount)) }
                }
            }

            if (-not $success) {
                Write-LogMessage "Renaming failed after $maxRetries attempts. Using fallback safe title." WARN
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
            try {
                $file = Get-Item $renamedPath -ErrorAction Stop
            } catch {
                Write-LogMessage "[SKIP] Renamed file vanished before moving to working: $renamedPath — $_" WARN
                continue
            }
        }

        # 3. Move to working directory
        $finalTaskInfo = Resolve-UniquePath -Directory $workingDir -BaseName $file.BaseName -Extension $file.Extension
        $workingPath = $finalTaskInfo.Path

        Move-Item -Path $file.FullName -Destination $workingPath -Force
        Write-LogMessage "Moved to working: $workingPath" INFO

        # 3. Parse content for execution (logging purposes)
        $title = $descriptiveName
        $description = "Task from $($file.Name)"
        # $prompt = $content
        $workingBaseName = $finalTaskInfo.FileName -replace [regex]::Escape($file.Extension), ''

        # --- MEMORY SYSTEM INTEGRATION ---
        $memoryContext = ""
        $memoryInstructions = ""

        # Call standalone memory-context script (handles generator + robust JSON parsing)
        $memoryContextScript = Join-Path $PSScriptRoot "workers\coworker-memory-context.ps1"
        try {
            # Capture stderr to a temp file so failures are diagnosable
            $memStderrPath = Join-Path $logsSubDir "${currentTime}-memory-context.err"
            $memoryResultJson = & $memoryContextScript -Type init -Date "$currentYear-$currentMonth-$currentDay" 2>$memStderrPath
            if ($memoryResultJson) {
                $memoryResult = ($memoryResultJson -join "`n") | ConvertFrom-Json
                $memoryContext = $memoryResult.context
                $memoryInstructions = $memoryResult.instructions
                if ($memoryContext -or $memoryInstructions) {
                    Write-LogMessage "Memory context initialized." INFO
                } else {
                    Write-LogVerbose "Memory context empty (no relevant memories found)."
                }
            } elseif (Test-Path $memStderrPath) {
                $memStderr = Get-Content $memStderrPath -Raw -ErrorAction SilentlyContinue
                if ($memStderr) {
                    Write-LogMessage "Memory context script produced no output. Stderr: $memStderr" WARN
                }
            }
            Remove-Item $memStderrPath -ErrorAction SilentlyContinue
        } catch {
            Write-LogMessage "Failed to initialize memory context: $_" WARN
        }

        # Parse task content: if structured (Title:/Description:/Prompt:), extract the Prompt field.
        # Otherwise use the full file content as the prompt (fixes bug where unstructured
        # task content was never passed to the agent).
        if ($content -match "(?s)\A\s*Title:\s*(?<title>.*?)(\r\n|\n)Description:\s*(?<desc>.*?)(\r\n|\n)Prompt:\s*(?<prompt>.*)$") {
            $title = $Matches['title'].Trim()
            $description = $Matches['desc'].Trim()
            $prompt = $Matches['prompt'].Trim()
        } else {
            $prompt = $content
        }

        # ── Task framing preamble ─────────────────────────────────────────
        # Wrap the task content with clear instructions so the AI agent
        # understands what these files are and how to approach them.
        $taskPreamble = @"

You are working on the Browser4 project in the repository at: $targetRepoRoot

The task file below contains issues, feature requests, or improvements for this project.
Each item may be:
  - A new feature to implement
  - An improvement to existing code or documentation
  - A bug in the code that needs to be fixed

INSTRUCTIONS:
1. Read the task file carefully and identify each distinct issue/feature/improvement it describes.
   a. Fix all issues with ACCEPT state in the file.
   b. Fix all issues with ACCEPT state in the file.
   c. Fix all issues with ACCEPT state in the file.
2. If the file contains multiple issues, work through them ONE BY ONE — fix each completely
   before moving to the next.
3. For each issue:
   a. Search the codebase to find the relevant source files.
   b. Understand the root cause before making changes.
   c. Implement the fix or feature.
   d. Verify your change is correct (review the diff, run tests if applicable).
4. After all issues are addressed, provide a brief summary of what was changed and why.
5. If an issue is already fixed, unclear, or not actionable, note that explicitly instead
   of making unnecessary changes.

--- TASK CONTENT BELOW ---

"@
        $prompt = $taskPreamble + $prompt

        # Append Memory Instructions and Context (if any)
        if ($memoryInstructions -or $memoryContext) {
            $prompt += "`n`n$memoryInstructions`n`n$memoryContext"
        }

        # Define log file paths (reusing script-level $logsSubDir and $currentTime)
        $taskLogPath = Join-Path $logsSubDir "${currentTime}-${workingBaseName}.task.log"
        $agentLogPath = Join-Path $logsSubDir "${currentTime}-${workingBaseName}.agent.log"

        Write-LogVerbose "Task log will be written to: $taskLogPath"

        Write-LogMessage "Executing agent for task: $workingBaseName" INFO
        Write-LogMessage "Task repositories -> control: $repoRoot | target: $targetRepoRoot | Agent cwd: $agentWorkingDirectory" INFO
        Write-LogVerbose "Prompt length: $($prompt.Length) characters"

        # Record task execution details to task log
        @"
Task: $title
Description: $description
Original File: $($file.Name)
Control Repo: $repoRoot
Target Repo: $targetRepoRoot
Agent Working Directory: $agentWorkingDirectory
Started: $(Get-CoworkerTimestamp)
Prompt:
$prompt
---
Agent Execution Output:
"@ | Out-File -FilePath $taskLogPath -Encoding UTF8

        try {
            # Define paths for temporary output and error logs
            $stdOutLog = $agentLogPath + ".stdout"
            $stdErrLog = $agentLogPath + ".stderr"

            Write-LogMessage "=== Starting agent execution ===" INFO
            Write-LogVerbose "Task agent working directory: $agentWorkingDirectory"

            # Execute agent tool with the task prompt
            # Capture both standard output and error output to separate files
            $process = Start-AgentProcess -Executable $agentExecutable -BaseArgs $agentBaseArgs -Prompt $prompt -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') -WorkingDirectory $agentWorkingDirectory -StdOutPath $stdOutLog -StdErrPath $stdErrLog -NoNewWindow -Backend $agentCommand.Backend

            $lastOutputLineCount = 0

            # Monitor output in real-time while process is running
            while (-not $process.HasExited) {
                Start-Sleep -Milliseconds 500

                # Check and display new stdout lines
                if (Test-Path $stdOutLog) {
                    try {
                        $currentLines = @(Get-Content $stdOutLog -Encoding UTF8 -ErrorAction Stop)
                        $currentLineCount = $currentLines.Count
                        if ($currentLineCount -gt $lastOutputLineCount) {
                            $newLines = $currentLines[$lastOutputLineCount..($currentLineCount - 1)]
                            foreach ($line in $newLines) {
                                if (-not [string]::IsNullOrWhiteSpace($line)) {
                                    Write-ConsoleLine -Message $line
                                }
                            }
                            $lastOutputLineCount = $currentLineCount
                        }
                    } catch {
                        # File may be temporarily locked by the writer; retry next iteration
                        Write-LogVerbose "Stdout monitor: unable to read $stdOutLog (retrying): $_"
                    }
                }

                # Check timeout
                try {
                    $startTime = $process.StartTime
                    if ($null -ne $startTime) {
                        $elapsed = (Get-Date) - $startTime
                        if ($elapsed.TotalSeconds -gt $agentRunTimeoutSeconds) {
                            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                            Write-LogMessage "Agent timed out after ${agentRunTimeoutSeconds}s" WARN
                            Write-ConsoleLine -Message "[TIMEOUT] Agent execution exceeded ${agentRunTimeoutSeconds}s timeout" -ForegroundColor Yellow
                            break
                        }
                    }
                } catch {
                    # Ignore errors accessing StartTime (process might have just exited or not fully started)
                }
            }

            # Final output capture after process ends
            if (Test-Path $stdOutLog) {
                try {
                    $remainingLines = @(Get-Content $stdOutLog -Encoding UTF8 -ErrorAction Stop)
                    if ($remainingLines.Count -gt $lastOutputLineCount) {
                        $newLines = $remainingLines[$lastOutputLineCount..($remainingLines.Count - 1)]
                        foreach ($line in $newLines) {
                            if (-not [string]::IsNullOrWhiteSpace($line)) {
                                Write-ConsoleLine -Message $line
                            }
                        }
                    }
                } catch {
                    Write-LogVerbose "Failed to read remaining stdout from $stdOutLog : $_"
                }
            }

            # Capture stderr output and display to console
            if (Test-Path $stdErrLog) {
                try {
                    $errContent = @(Get-Content $stdErrLog -Encoding UTF8 -ErrorAction Stop)
                    if ($errContent) {
                        Write-ConsoleLine -Message "`n[STDERR OUTPUT]" -ForegroundColor Yellow -ErrorStream
                        foreach ($line in $errContent) {
                            if (-not [string]::IsNullOrWhiteSpace($line)) {
                                Write-ConsoleLine -Message $line -ForegroundColor Yellow -ErrorStream
                            }
                        }
                    }
                } catch {
                    Write-LogVerbose "Failed to read stderr from $stdErrLog : $_"
                }
            }

            # Combine agent stdout and stderr logs into the agent-specific log
            # First append stdout if it exists
            if (Test-Path $stdOutLog) {
                try {
                    Get-Content $stdOutLog -Encoding UTF8 -ErrorAction Stop | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
                } catch {
                    Write-LogMessage "Failed to append stdout to agent log: $_" WARN
                }
            }
            # Then append stderr if it exists and contains content
            if (Test-Path $stdErrLog) {
                try {
                    $errContent = Get-Content $stdErrLog -Encoding UTF8 -ErrorAction Stop
                    if ($errContent) {
                        "`r`n=== AGENT STDERR ===`r`n" | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
                        $errContent | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
                    }
                } catch {
                    Write-LogMessage "Failed to append stderr to agent log: $_" WARN
                }
            }

            # Clean up temporary log files
            Remove-Item $stdOutLog -ErrorAction SilentlyContinue
            Remove-Item $stdErrLog -ErrorAction SilentlyContinue

            Write-LogMessage "Agent execution finished with exit code $($process.ExitCode)" INFO
            Write-LogMessage "=== Agent execution completed ===" INFO
            Write-LogVerbose "Agent external tool log: $agentLogPath"

            # Append agent result to task log
            @"

Agent Exit Code: $($process.ExitCode)
Agent Log: $agentLogPath
"@ | Out-File -FilePath $taskLogPath -Append -Encoding UTF8

            # Warn if agent exited with an error code
            if ($process.ExitCode -ne 0) {
                Write-LogMessage "Warning: agent exited with non-zero code. Check log: $agentLogPath" WARN
            }
        }
        catch {
            # Handle any errors that occur during agent execution
            Write-LogMessage "Failed to execute agent: $_" ERROR
            "Error executing agent: $_" | Out-File -FilePath $taskLogPath -Append -Encoding UTF8
        }

        # Move completed task from working directory to finished or approved directory
        # Create date-based subdirectory: YYYY/MMDD

        # Check for #auto-approve tag in content
        $targetDir = $finishedDir
        $targetMessage = "Task moved to finished"

        if ($content -match "#auto-approve") {
            $targetDir = $approvedDir
            $targetMessage = "Task AUTO-APPROVED and moved to"
        }

        $targetSubDir = Join-Path $targetDir "$currentYear\$currentDate"
        if (!(Test-Path $targetSubDir)) {
            New-Item -ItemType Directory -Path $targetSubDir | Out-Null
        }

        $targetInfo = Resolve-UniquePath -Directory $targetSubDir -BaseName $workingBaseName -Extension $file.Extension

        if (Test-Path $workingPath) {
            Move-Item -Path $workingPath -Destination $targetInfo.Path -Force
            Write-LogMessage "$targetMessage : $($targetInfo.Path)" INFO
        } else {
            Write-LogMessage "Task file not found at working path (may have been moved/deleted by agent): $workingPath" WARN
        }

        # Auto-commit changes for finished tasks (3done) without pushing.
        # Tasks with #auto-approve go to 5approved and are committed+ pushed
        # later by git-sync.ps1 — skip those here to avoid a double commit.
        if ($targetDir -eq $finishedDir) {
            try {
                $gitAvailable = Get-Command git -ErrorAction Stop
                Push-Location $targetRepoRoot
                try {
                    # Stage all changes the agent made while working on this task
                    & git add -A 2>&1 | Out-Null

                    # Check whether there is anything staged to commit
                    & git diff --cached --quiet 2>&1 | Out-Null
                    if ($LASTEXITCODE -ne 0) {
                        # Build a multi-line commit message: subject + file list from --stat
                        $stat = & git diff --cached --stat 2>&1
                        $commitBody = "fix(done): $workingBaseName`n`n$stat"
                        $tmpCommitMsgFile = [System.IO.Path]::GetTempFileName()
                        try {
                            Set-Content -Path $tmpCommitMsgFile -Value $commitBody -Encoding UTF8
                            & git commit -F $tmpCommitMsgFile 2>&1 | Out-Null
                            if ($LASTEXITCODE -eq 0) {
                                Write-LogMessage "Auto-committed changes for finished task: $workingBaseName" INFO
                            } else {
                                Write-LogMessage "Git commit failed for task: $workingBaseName" WARN
                            }
                        } finally {
                            Remove-Item $tmpCommitMsgFile -ErrorAction SilentlyContinue
                        }
                    } else {
                        Write-LogVerbose "No changes to commit for task: $workingBaseName"
                    }
                } finally {
                    Pop-Location
                }
            } catch {
                Write-LogMessage "Git unavailable, skipping auto-commit for task: $workingBaseName" WARN
            }
        }

        Ensure-DraftPlaceholders -DraftDirectory $draftDir

        Write-LogMessage "---" INFO
    }
}

# Log script completion
$scriptEndTime = (Get-Date).ToUniversalTime()
Write-LogMessage "===========================================================================" INFO
Write-LogMessage "All tasks completed" INFO
Write-LogMessage "Ended at: $scriptEndTime" INFO
Write-LogMessage "Script Log: $scriptLogPath" INFO
Write-LogMessage "==========================================================================" INFO

# Release script-level mutex
Remove-CoworkerScriptLock -Lock $script:__CoworkerLock

