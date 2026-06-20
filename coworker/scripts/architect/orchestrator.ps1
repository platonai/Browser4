#!/usr/bin/env pwsh

# AI Software Factory Orchestrator
# Implements the design from coworker/tasks/2working/ai-software-factory-design.md

param(
    [switch]$Loop
)

$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'config.ps1'
. $configPath
$agentHelper = Join-Path (Split-Path -Parent $PSScriptRoot) 'workers\agent.ps1'
. $agentHelper
$repoRoot = Get-WorkspaceRoot

# Configuration
$templatesDir = Resolve-TasksPath '100templates'
$logsBaseDir = Get-LogDirectory
$createdDir = Resolve-TasksPath '1ready'
$workingDir = Resolve-TasksPath '2working'
$finishedDir = Resolve-TasksPath '3_1complete'
$abortedDir = Resolve-TasksPath '3_5aborted'

# Ensure directories exist
foreach ($dir in @($templatesDir, $logsBaseDir, $createdDir, $workingDir, $finishedDir, $abortedDir)) {
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
}

function Invoke-Copilot {
    param(
        [string]$Prompt,
        [string]$OutputFile,
        [string]$LogFile
    )

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$timestamp] PROMPT START`n" | Out-File -FilePath $LogFile -Append -Encoding UTF8
    $Prompt | Out-File -FilePath $LogFile -Append -Encoding UTF8
    "[$timestamp] PROMPT END`n" | Out-File -FilePath $LogFile -Append -Encoding UTF8

    try {
        Write-Host "Invoking agent..."
        $outputContent = Invoke-Agent -Prompt $Prompt -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') -RepoRoot $repoRoot -WorkingDirectory $repoRoot -CaptureOutput

        if (-not [string]::IsNullOrWhiteSpace($outputContent)) {
            $outputContent | Out-File -FilePath $OutputFile -Encoding UTF8
            "[$timestamp] OUTPUT START`n" | Out-File -FilePath $LogFile -Append -Encoding UTF8
            $outputContent | Out-File -FilePath $LogFile -Append -Encoding UTF8
            "[$timestamp] OUTPUT END`n" | Out-File -FilePath $LogFile -Append -Encoding UTF8
        } else {
            "[$timestamp] WARNING: No output captured." | Out-File -FilePath $LogFile -Append -Encoding UTF8
        }

        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
            "[$timestamp] WARNING: Agent exited with code $LASTEXITCODE" | Out-File -FilePath $LogFile -Append -Encoding UTF8
        }
    } catch {
        $msg = "Exception invoking agent: $_"
        Write-Error $msg
        "[$timestamp] EXCEPTION: $msg" | Out-File -FilePath $LogFile -Append -Encoding UTF8
    }
}

function Execute-StoryPipeline {
    param($taskPath)
    
    $storyId = Split-Path $taskPath -Leaf
    Write-Host "Processing Story: $storyId"
    
    $logDir = Join-Path $logsBaseDir $storyId
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    
    $storyFile = Join-Path $taskPath "story.md"
    if (-not (Test-Path $storyFile)) {
        Write-Error "story.md not found in $taskPath"
        Move-Item -Path $taskPath -Destination $abortedDir -Force
        return
    }
    
    $statusFile = Join-Path $taskPath "status.json"
    if (-not (Test-Path $statusFile)) {
        @{ state = "working"; retries = 0; currentNode = "analysis" } | ConvertTo-Json | Out-File $statusFile
    }
    
    try {
        # 1. Analysis
        Write-Host "  Step 1: Analysis"
        $logFile = Join-Path $logDir "01-analysis.log"
        $template = Get-Content (Join-Path $templatesDir "analysis.prompt.md") -Raw
        $story = Get-Content $storyFile -Raw
        $prompt = $template.Replace("{{story.md}}", $story)
        Invoke-Copilot -Prompt $prompt -OutputFile (Join-Path $taskPath "analysis.md") -LogFile $logFile
        
        # 2. Plan
        Write-Host "  Step 2: Plan"
        $logFile = Join-Path $logDir "02-plan.log"
        $template = Get-Content (Join-Path $templatesDir "plan.prompt.md") -Raw
        $prompt = $template.Replace("{{story.md}}", $story)
        Invoke-Copilot -Prompt $prompt -OutputFile (Join-Path $taskPath "plan.json") -LogFile $logFile
        
        # 3. SubFeature Design (Placeholder loop)
        # In real impl, parse plan.json and iterate
        Write-Host "  Step 3: SubFeature Design (Mock)"
        $logFile = Join-Path $logDir "03-subfeature.log"
        $template = Get-Content (Join-Path $templatesDir "subfeature.prompt.md") -Raw
        # Just passing story and mock plan for now
        $prompt = $template.Replace("{{story.md}}", $story).Replace("{{plan.json}}", "{}").Replace("{{sub_feature.name}}", "Feature1")
        Invoke-Copilot -Prompt $prompt -OutputFile (Join-Path $taskPath "design.md") -LogFile $logFile

        # 4. Implementation
        Write-Host "  Step 4: Implementation"
        $logFile = Join-Path $logDir "04-impl.log"
        $template = Get-Content (Join-Path $templatesDir "implementation.prompt.md") -Raw
        $prompt = $template.Replace("{{sub_feature.name}}", "Feature1").Replace("{{sub_feature.description}}", "Desc").Replace("{{context.md}}", "Context").Replace("{{design.md}}", "Design")
        Invoke-Copilot -Prompt $prompt -OutputFile (Join-Path $taskPath "impl.patch") -LogFile $logFile

        # 5. E2E Test
        Write-Host "  Step 5: E2E Test"
        $logFile = Join-Path $logDir "05-e2e.log"
        $template = Get-Content (Join-Path $templatesDir "e2e.prompt.md") -Raw
        $prompt = $template.Replace("{{story.md}}", $story).Replace("{{plan.json}}", "{}")
        Invoke-Copilot -Prompt $prompt -OutputFile (Join-Path $taskPath "e2e.kt") -LogFile $logFile

        # 6. Validation
        Write-Host "  Step 6: Validation"
        $logFile = Join-Path $logDir "06-validation.log"
        $template = Get-Content (Join-Path $templatesDir "validation.prompt.md") -Raw
        $prompt = $template.Replace("{{story.md}}", $story).Replace("{{artifacts}}", "impl.patch, e2e.kt")
        Invoke-Copilot -Prompt $prompt -OutputFile (Join-Path $taskPath "validation.md") -LogFile $logFile

        # Success
        Write-Host "  Story Completed"
        Move-Item -Path $taskPath -Destination $finishedDir -Force
        
    } catch {
        Write-Error "Error processing story: $_"
        Move-Item -Path $taskPath -Destination $abortedDir -Force
    }
}

# Main Loop
do {
    $tasks = Get-ChildItem $createdDir -Directory
    foreach ($task in $tasks) {
        $taskPath = $task.FullName
        $destPath = Join-Path $workingDir $task.Name
        Move-Item -Path $taskPath -Destination $destPath -Force
        Execute-StoryPipeline $destPath
    }
    
    if ($Loop) { Start-Sleep -Seconds 5 }
} while ($Loop)
