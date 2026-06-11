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

function Write-ConsoleLine {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [System.ConsoleColor]$ForegroundColor,
        [switch]$ErrorStream
    )

    $canUseHost = $false
    try {
        $canUseHost = [Environment]::UserInteractive -and $null -ne $Host -and $null -ne $Host.UI -and $null -ne $Host.UI.RawUI
    }
    catch {
        $canUseHost = $false
    }

    if ($canUseHost) {
        if ($PSBoundParameters.ContainsKey('ForegroundColor')) {
            Write-Host $Message -ForegroundColor $ForegroundColor
        } else {
            Write-Host $Message
        }
        return
    }

    $isRedirected = if ($ErrorStream) { [Console]::IsErrorRedirected } else { [Console]::IsOutputRedirected }
    if ($isRedirected) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Message + [Environment]::NewLine)
        $stream = if ($ErrorStream) { [Console]::OpenStandardError() } else { [Console]::OpenStandardOutput() }
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        return
    }

    if ($PSBoundParameters.ContainsKey('ForegroundColor')) {
        Write-Host $Message -ForegroundColor $ForegroundColor
    } else {
        Write-Host $Message
    }
}

Write-ConsoleLine -Message "Starting Coworker Task Runner..." -ForegroundColor Cyan

$configScriptPath = Join-Path $PSScriptRoot 'config.ps1'
. $configScriptPath

# Handle specified TaskFile
if (-not [string]::IsNullOrWhiteSpace($TaskFile)) {
    # Resolve full path before changing location
    if (Test-Path $TaskFile) {
        $TaskFile = Resolve-Path $TaskFile
    }
}

$repoRoot = Get-WorkspaceRoot

$tasksRoot = Join-Path $repoRoot "coworker\tasks"
$scriptsDir = $PSScriptRoot
$agentHelper = Join-Path $scriptsDir "workers\agent.ps1"
. $agentHelper
$targetRepoRoot = $repoRoot
$agentCommand = $null
$agentExecutable = $null
$agentBaseArgs = @()
$agentWorkingDirectory = $repoRoot
$taskRoots = @(
    @{
        Prepare = (Join-Path $tasksRoot "0draft")
        Created = (Join-Path $tasksRoot "1created")
        Working = (Join-Path $tasksRoot "2working")
        Finished = (Join-Path $tasksRoot "3_1complete")
        Review = (Join-Path $tasksRoot "4review")
        Approved = (Join-Path $tasksRoot "5approved")
        Pushed = (Join-Path $tasksRoot "6git-pushed")
        Label = "tasks"
    }
)

$logsDir = Join-Path "$HOME\.browser4\development" "logs"
$memoryDir = $logsDir

# Ensure all required task directories exist
foreach ($root in $taskRoots) {
    foreach ($dir in @($root.Prepare, $root.Created, $root.Working, $root.Finished, $root.Review, $root.Approved, $root.Pushed)) {
        if (!(Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    }
}
# Ensure logs directory exists
if (!(Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }

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
$currentTime = (Get-Date).ToUniversalTime().ToString("HHmmss")
$logsSubDir = Join-Path $logsDir "$currentYear\$currentMonth\$currentDay"
if (!(Test-Path $logsSubDir)) { New-Item -ItemType Directory -Path $logsSubDir | Out-Null }

$scriptLogPath = Join-Path $logsSubDir "${currentTime}-coworker.log"
$scriptStartTime = (Get-Date).ToUniversalTime()

$agentNameTimeoutSeconds = 60
$agentRunTimeoutSeconds = 6000

function New-AgentPromptArguments {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,
        [string[]]$AdditionalArguments = @()
    )

    return @(New-AgentArguments -BaseArgs $script:agentBaseArgs -Prompt $Prompt -AdditionalArguments $AdditionalArguments)
}

function Format-AgentPromptCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    return Format-AgentCommand -Executable $script:agentExecutable -Arguments $Arguments
}

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

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
    $logEntry = "[$timestamp] [$Level] $Message"

    # Write to console (or redirected stdout when launched by scheduler)
    switch ($Level) {
        'INFO' { Write-ConsoleLine -Message $logEntry }
        'WARN' { Write-ConsoleLine -Message $logEntry -ForegroundColor Yellow }
        'ERROR' { Write-ConsoleLine -Message $logEntry -ForegroundColor Red }
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

    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
    $logEntry = "[$timestamp] [DEBUG] $Message"

    # Append to script log file only (not console)
    $logEntry | Out-File -FilePath $scriptLogPath -Append -Encoding UTF8
}

try {
    $targetRepoRoot = Get-TargetRepositoryRoot
    $agentCommand = Get-AgentCommand -RepoRoot $targetRepoRoot
    $agentExecutable = $agentCommand.Executable
    $agentBaseArgs = $agentCommand.BaseArgs
    $agentWorkingDirectory = $agentCommand.WorkingDirectory
}
catch {
    Write-LogMessage "Failed to resolve target repository root for task execution: $_" ERROR
    exit 1
}

Write-LogMessage "Control repository root: $repoRoot" INFO
Write-LogMessage "Target repository root: $targetRepoRoot" INFO
Write-LogMessage "Agent working directory: $agentWorkingDirectory" INFO

function Ensure-DraftPlaceholders {
    param(
        [Parameter(Mandatory=$true)]
        [string]$DraftDirectory
    )

    foreach ($draftNumber in 1..5) {
        $draftPath = Join-Path $DraftDirectory "$draftNumber.md"
        if (!(Test-Path $draftPath)) {
            Set-Content -Path $draftPath -Value '' -Encoding UTF8
            Write-LogMessage "Created missing draft placeholder: $draftPath" INFO
        }
    }
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
        $nameArguments = New-AgentPromptArguments -Prompt $namingPrompt

        Write-LogVerbose ("Executing Agent for naming: {0}" -f (Format-AgentPromptCommand -Arguments $nameArguments))
        Write-LogVerbose "Naming agent working directory: $agentWorkingDirectory"

        $nameStdOut = [System.IO.Path]::GetTempFileName()
        $nameStdErr = [System.IO.Path]::GetTempFileName()
        $nameProcess = Start-AgentProcess -Executable $agentExecutable -BaseArgs $agentBaseArgs -Prompt $namingPrompt -WorkingDirectory $agentWorkingDirectory -StdOutPath $nameStdOut -StdErrPath $nameStdErr -NoNewWindow

        $waited = $false
        try {
            $null = Wait-Process -Id $nameProcess.Id -Timeout $agentNameTimeoutSeconds -ErrorAction Stop
            $waited = $true
        } catch {
            $waited = $false
            Write-LogMessage "Agent naming timed out after ${agentNameTimeoutSeconds}s" WARN
        }

        if (-not $waited -or -not $nameProcess.HasExited) {
            Stop-Process -Id $nameProcess.Id -Force -ErrorAction SilentlyContinue

            if (Test-Path $nameStdErr) {
                $errContent = Get-Content -Path $nameStdErr -Encoding UTF8
                Write-LogVerbose "Naming agent STDERR (Timeout): $errContent"
            }

            Remove-Item $nameStdOut -ErrorAction SilentlyContinue
            Remove-Item $nameStdErr -ErrorAction SilentlyContinue
            return $Fallback
        }

        $rawName = ""
        if (Test-Path $nameStdOut) {
            $rawName = (Get-Content -Path $nameStdOut -Encoding UTF8 | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1)
            Write-LogVerbose "Naming agent STDOUT: $rawName"
        } else {
            Write-LogVerbose "Naming agent STDOUT file not found"
        }

        if (Test-Path $nameStdErr) {
            $errContent = Get-Content $nameStdErr -Encoding UTF8
            if ($errContent) {
                Write-LogVerbose "Naming agent STDERR: $errContent"
            }
        }

        Remove-Item $nameStdOut -ErrorAction SilentlyContinue
        Remove-Item $nameStdErr -ErrorAction SilentlyContinue

        if ($nameProcess.ExitCode -ne 0) {
            Write-LogVerbose "Naming agent exited with code $($nameProcess.ExitCode)"
            return $Fallback
        }

        if ([string]::IsNullOrWhiteSpace($rawName)) {
            Write-LogVerbose "Naming agent returned empty name"
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
    $draftDir = $taskRoot.Prepare
    $createdDir = $taskRoot.Created
    $workingDir = $taskRoot.Working
    $finishedDir = $taskRoot.Finished
    $reviewDir = $taskRoot.Review
    $approvedDir = $taskRoot.Approved
    $pushedDir = $taskRoot.Pushed
    $logsDir = $taskRoot.Logs

    $currentYear = (Get-Date).ToUniversalTime().ToString("yyyy")
    $currentMonth = (Get-Date).ToUniversalTime().ToString("MM")
    $currentDay = (Get-Date).ToUniversalTime().ToString("dd")
    $currentDate = "$currentMonth$currentDay"
    $currentTime = (Get-Date).ToUniversalTime().ToString("HHmmss")

    Ensure-DraftPlaceholders -DraftDirectory $draftDir

    # 1. Process 0draft
    $prepareFiles = Get-ChildItem -Path $draftDir -File
    foreach ($file in $prepareFiles) {
        Write-LogMessage "[PREPARE] Task: $($file.Name)" INFO
    }

    # 2. Process 3_1complete (newly added to show pending reviews)
    if (Test-Path $finishedDir) {
        $finishedFiles = Get-ChildItem -Path $finishedDir -Recurse -File
        foreach ($file in $finishedFiles) {
            # Only show files from the last 24 hours to avoid noise
            if ($file.LastWriteTimeUtc -ge (Get-Date).ToUniversalTime().AddDays(-1)) {
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

    # 4. Process 6git-pushed (last 2 days)
    # Recursively find files in 6git-pushed
    if (Test-Path $pushedDir) {
        $pushedFiles = Get-ChildItem -Path $pushedDir -Recurse -File
        $twoDaysAgo = (Get-Date).ToUniversalTime().AddDays(-2)
        foreach ($file in $pushedFiles) {
            if ($file.LastWriteTimeUtc -ge $twoDaysAgo) {
                Write-LogMessage "[PUSHED] Task: $($file.Name) (updated $($file.LastWriteTime))" INFO
            }
        }
    }

    $files = Get-ChildItem -Path $createdDir -File

    # Process each task file found in the created directory
    foreach ($file in $files) {
        # 1. Determine the descriptive name based on content (while still in created dir)
        $renameScript = Join-Path $scriptsDir "workers\rename.ps1"
        $descriptiveName = ""

        # Read content for fallback title
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
        $safeTitle = $file.BaseName -replace '[\\/*?:"<>|]', '_'
        if ([string]::IsNullOrWhiteSpace($safeTitle)) { $safeTitle = "task" }

        # Check if the file needs renaming (numeric or generic names, or always rename?)
        # User implies "numeric filenames are treated as random filenames... coworker needs to rename these"
        # The current implementation attempts to rename ALL files using the agent via rename.ps1.
        # This seems to cover the requirement "improve-coworker-daily-memory-generator.md, 2.md... are treated as random... rename these".

        Write-LogVerbose "renameScript path: $renameScript"
        Write-LogVerbose "Test-Path renameScript: $(Test-Path $renameScript)"

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
                        if ($retryCount -lt $maxRetries) { Start-Sleep -Seconds 2 }
                    }
                } catch {
                    $retryCount++
                    Write-LogMessage "Rename script failed (Attempt $retryCount/$maxRetries): $_" WARN
                    if ($retryCount -lt $maxRetries) { Start-Sleep -Seconds 2 }
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
            $file = Get-Item $renamedPath
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

        # Call coworker-memory-generator to initialize memory context
        $memoryGeneratorScript = Join-Path $PSScriptRoot "workers\coworker-memory-generator.ps1"
        try {
            $memoryResultJson = & $memoryGeneratorScript -Type init -Date "$currentYear-$currentMonth-$currentDay" | Out-String

            if (-not [string]::IsNullOrWhiteSpace($memoryResultJson)) {
                $memoryResult = $memoryResultJson | ConvertFrom-Json
                $memoryContext = $memoryResult.context
                $memoryInstructions = $memoryResult.instructions
                Write-LogMessage "Memory context initialized via generator." INFO
            } else {
                 Write-LogMessage "Memory generator returned empty result." WARN
            }
        } catch {
            Write-LogMessage "Failed to initialize memory context: $_" ERROR
            $memoryContext = ""
            $memoryInstructions = ""
        }

        $prompt = @"
Finish the task described in file: $workingPath.
Do not move **this** task file, just execute the task based on its content, the system will move it after you finished the task.
"@

        # Try to parse structured content
        if ($content -match "(?ms)^Title:\s*(?<title>.*?)(\r\n|\n)Description:\s*(?<desc>.*?)(\r\n|\n)Prompt:\s*(?<prompt>.*)$") {
            $title = $Matches['title'].Trim()
            $description = $Matches['desc'].Trim()
            $prompt = $Matches['prompt'].Trim()
        }

        # Append Memory Instructions and Context
        $prompt += "`n`n$memoryInstructions`n`n$memoryContext"

        # Define log file paths

        $logsSubDir = Join-Path $logsDir "$currentYear\$currentMonth\$currentDay"
        if (!(Test-Path $logsSubDir)) { New-Item -ItemType Directory -Path $logsSubDir | Out-Null }

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
Started: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))
Prompt:
$prompt
---
Agent Execution Output:
"@ | Out-File -FilePath $taskLogPath -Encoding UTF8

        try {
            $agentArguments = New-AgentPromptArguments -Prompt $prompt -AdditionalArguments @('--allow-all-tools', '--allow-all-paths')

            # Define paths for temporary output and error logs (for agent external tool)
            $stdOutLog = $agentLogPath + ".stdout"
            $stdErrLog = $agentLogPath + ".stderr"

            Write-LogMessage "=== Starting agent execution ===" INFO
            Write-LogVerbose "Task agent working directory: $agentWorkingDirectory"

            # Execute agent tool with the task prompt
            # Capture both standard output and error output to separate files
            $process = Start-AgentProcess -Executable $agentExecutable -BaseArgs $agentBaseArgs -Prompt $prompt -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') -WorkingDirectory $agentWorkingDirectory -StdOutPath $stdOutLog -StdErrPath $stdErrLog -NoNewWindow

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
                                Write-ConsoleLine -Message $line
                            }
                        }
                        $lastOutputLineCount = $currentLineCount
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
                $remainingLines = @(Get-Content $stdOutLog -Encoding UTF8 -ErrorAction SilentlyContinue)
                if ($remainingLines.Count -gt $lastOutputLineCount) {
                    $newLines = $remainingLines[$lastOutputLineCount..($remainingLines.Count - 1)]
                    foreach ($line in $newLines) {
                        if (-not [string]::IsNullOrWhiteSpace($line)) {
                            Write-ConsoleLine -Message $line
                        }
                    }
                }
            }

            # Capture stderr output and display to console
            if (Test-Path $stdErrLog) {
                $errContent = @(Get-Content $stdErrLog -Encoding UTF8 -ErrorAction SilentlyContinue)
                if ($errContent) {
                    Write-ConsoleLine -Message "`n[STDERR OUTPUT]" -ForegroundColor Yellow -ErrorStream
                    foreach ($line in $errContent) {
                        if (-not [string]::IsNullOrWhiteSpace($line)) {
                            Write-ConsoleLine -Message $line -ForegroundColor Yellow -ErrorStream
                        }
                    }
                }
            }

            # Combine agent stdout and stderr logs into the agent-specific log
            # First append stdout if it exists
            if (Test-Path $stdOutLog) { Get-Content $stdOutLog -Encoding UTF8 | Out-File -FilePath $agentLogPath -Append -Encoding UTF8 }
            # Then append stderr if it exists and contains content
            if (Test-Path $stdErrLog) {
                $errContent = Get-Content $stdErrLog -Encoding UTF8
                if ($errContent) {
                    "`r`n=== AGENT STDERR ===`r`n" | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
                    $errContent | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
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

        Move-Item -Path $workingPath -Destination $targetInfo.Path -Force
        Write-LogMessage "$targetMessage : $($targetInfo.Path)" INFO
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

