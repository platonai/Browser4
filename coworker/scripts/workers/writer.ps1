#!/usr/bin/env pwsh

# ============================================================================
# Writer Coworker Script
# ============================================================================
# Purpose:
#   Processes writing tasks from 'writer/1ready'.
#   Generates articles based on content and 'writer/responsibilities.md'.
#   Outputs results to 'writer/3done'.
#   Moves processed tasks to 'writer/9archive'.
# ============================================================================

param(
    [switch]$Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Load configuration and helper scripts
$configScriptPath = Join-Path $PSScriptRoot 'config.ps1'
if (Test-Path $configScriptPath) {
    . $configScriptPath
} else {
    Write-Error "Config script not found at $configScriptPath"
    exit 1
}

$agentHelper = Join-Path $PSScriptRoot "workers\agent.ps1"
if (Test-Path $agentHelper) {
    . $agentHelper
} else {
    Write-Error "Agent helper not found at $agentHelper"
    exit 1
}

# Define paths
$repoRoot = Get-WorkspaceRoot
$writerRoot = Join-Path $repoRoot "coworker\writer"
$readyDir = Join-Path $writerRoot "1ready"
$doneDir = Join-Path $writerRoot "3done"
$archiveDir = Join-Path $writerRoot "9archive"
$responsibilitiesFile = Join-Path $writerRoot "responsibilities.md"

# Ensure directories exist
foreach ($dir in @($readyDir, $doneDir, $archiveDir)) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}

# Check for responsibilities file
if (-not (Test-Path $responsibilitiesFile)) {
    Write-Warning "Responsibilities file not found at $responsibilitiesFile. Proceeding without specific guidelines."
    $responsibilitiesContent = ""
} else {
    $responsibilitiesContent = Get-Content -Path $responsibilitiesFile -Raw -Encoding UTF8
}

function Process-Writer-Queue {
    $files = @(Get-ChildItem -Path $readyDir -File)
    
    if ($files.Count -eq 0) {
        Write-Host "No tasks found in $readyDir" -ForegroundColor Gray
        return
    }

    foreach ($file in $files) {
        Write-Host "Processing task: $($file.Name)" -ForegroundColor Cyan
        
        try {
            $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
            
            # Construct prompt
            $prompt = @"
You are a technical writer.
Here are your responsibilities and guidelines:
$responsibilitiesContent

Task:
$content

Please generate the article/document based on the task above.
Output only the content of the article.
"@

            # Generate output filename
            $outputFile = Join-Path $doneDir $file.Name
            
            # Call agent
            # We use a temporary file to capture output to ensure we get it clean
            $tempOut = [System.IO.Path]::GetTempFileName()
            $tempErr = [System.IO.Path]::GetTempFileName()
            
            try {
                $agentCommand = Get-AgentCommand -RepoRoot $repoRoot
                
                Write-Host "Invoking agent..." -ForegroundColor Green
                
                $process = Start-AgentProcess `
                    -Executable $agentCommand.Executable `
                    -BaseArgs $agentCommand.BaseArgs `
                    -Prompt $prompt `
                    -WorkingDirectory $repoRoot `
                    -StdOutPath $tempOut `
                    -StdErrPath $tempErr `
                    -NoNewWindow
                
                $process.WaitForExit()
                
                if ($process.ExitCode -eq 0) {
                    if (Test-Path $tempOut) {
                        # Read output and save to destination
                        $result = Get-Content -Path $tempOut -Raw -Encoding UTF8
                        if (-not [string]::IsNullOrWhiteSpace($result)) {
                            # Clean up tool logs from output
                            $cleanedResult = $result -replace '(?m)^[●│└✗].*$', '' -replace '(?m)^\s*$', ''
                            # Trim leading/trailing whitespace
                            $cleanedResult = $cleanedResult.Trim()
                            
                            $cleanedResult | Out-File -FilePath $outputFile -Encoding UTF8
                            Write-Host "Article generated: $outputFile" -ForegroundColor Green
                            
                            # Move original task to archive
                            $archivePath = Join-Path $archiveDir $file.Name
                            Move-Item -Path $file.FullName -Destination $archivePath -Force
                            Write-Host "Task archived to: $archivePath" -ForegroundColor Gray
                        } else {
                            Write-Warning "Agent produced empty output for $($file.Name)"
                        }
                    }
                } else {
                    Write-Error "Agent exited with code $($process.ExitCode)"
                    if (Test-Path $tempErr) {
                        Get-Content $tempErr | Write-Error
                    }
                }
            }
            finally {
                Remove-Item $tempOut -ErrorAction SilentlyContinue
                Remove-Item $tempErr -ErrorAction SilentlyContinue
            }
            
        } catch {
            Write-Error "Failed to process $($file.Name): $_"
        }
    }
}

# Main loop
if ($Once) {
    Process-Writer-Queue
} else {
    Write-Host "Starting Writer Coworker Service (Ctrl+C to stop)..." -ForegroundColor Cyan
    while ($true) {
        Process-Writer-Queue
        Start-Sleep -Seconds 10
    }
}
