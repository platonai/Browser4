#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Coworker Daily Memory Generator
.DESCRIPTION
    Analyzes daily logs and generates a memory summary using the agent.
.PARAMETER Date
    The date to generate memory for (format: YYYY-MM-DD). Defaults to today.
#>
param(
    [string]$Date = ((Get-Date).ToUniversalTime().ToString("yyyy-MM-dd"))
)

$ErrorActionPreference = "Stop"

$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) "config.ps1"
. $configPath
$repoRoot = Get-WorkspaceRoot
$agentHelper = Join-Path $PSScriptRoot 'agent.ps1'
. $agentHelper
$agentCommand = Get-AgentCommand -RepoRoot $repoRoot
$agentExecutable = $agentCommand.Executable
$agentBaseArgs = $agentCommand.BaseArgs

$parsedDate = Get-Date $Date
$year = $parsedDate.ToString("yyyy")
$month = $parsedDate.ToString("mm") # Wait, mm is minutes? No, "MM"
$month = $parsedDate.ToString("MM")
$day = $parsedDate.ToString("dd")
$dateStr = $parsedDate.ToString("yyyy-MM-dd")
$compactDate = $parsedDate.ToString("yyyyMMdd")

$logDir = Join-Path (Get-LogDirectory) "$year\$month\$day"
$memoryFile = Join-Path $logDir "MEMORY.$compactDate.md"

if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

Write-Host "Generating daily memory for $dateStr from logs in $logDir..."

# Collect logs
$logContent = ""

# Function to extract clean prompt from task log
function Get-CleanPrompt {
    param($TaskLogPath)
    $content = Get-Content $TaskLogPath -Raw
    if ($content -match "(?s)Prompt:(.*?)\*\*\* MEMORY UPDATE INSTRUCTIONS \*\*\*") {
        return $Matches[1].Trim()
    } elseif ($content -match "(?s)Prompt:(.*)") {
        return $Matches[1].Trim()
    }
    return ""
}

Get-ChildItem -Path $logDir -Filter "*.task.log" | ForEach-Object {
    $taskLog = $_
    $baseName = $taskLog.Name -replace ".task.log$", ""
    $agentLogPath = Join-Path $logDir "$baseName.agent.log"

    $logContent += "`n`n=== TASK: $baseName ===`n"

    # Extract metadata
    $lines = Get-Content $taskLog.FullName -TotalCount 10
    $titleLine = $lines | Where-Object { $_ -match "^Task:" } | Select-Object -First 1
    if ($titleLine) { $logContent += "$titleLine`n" }

    $logContent += "--- PROMPT (Snippet) ---`n"
    $cleanPrompt = Get-CleanPrompt -TaskLogPath $taskLog.FullName
    # Truncate prompt to 2000 chars
    if ($cleanPrompt.Length -gt 2000) {
        $cleanPrompt = $cleanPrompt.Substring(0, 2000) + "... [Truncated]"
    }
    $logContent += "$cleanPrompt`n"

    $logContent += "--- RESULT (Snippet) ---`n"
    if (Test-Path $agentLogPath) {
        $agentOutput = @(Get-Content $agentLogPath)

        $lastToolIndex = -1
        for ($i = $agentOutput.Count - 1; $i -ge 0; $i--) {
            if ($agentOutput[$i] -match "^● (Read|Edit|Run)") {
                $lastToolIndex = $i
                break
            }
        }

        $head = $agentOutput | Select-Object -First 10
        $tailContent = ""

        if ($lastToolIndex -ge 0) {
             # Take from the last tool execution to the end
             $tailLines = $agentOutput | Select-Object -Skip $lastToolIndex
             $tailContent = $tailLines -join "`n"
        } else {
             # Fallback: take last 100 lines if no tool found
             $tailLines = $agentOutput | Select-Object -Last 100
             $tailContent = $tailLines -join "`n"
        }

        $agentText = ($head -join "`n") + "`n... [Intermediate logs skipped] ...`n" + $tailContent

        # Truncate output to 20000 chars to avoid token limit if lines are very long
        if ($agentText.Length -gt 20000) {
            $agentText = $agentText.Substring(0, 20000) + "... [Truncated]"
        }
        $logContent += "$agentText`n"
    } else {
        $logContent += "[Agent log not found]`n"
    }
}

if ([string]::IsNullOrWhiteSpace($logContent)) {
    Write-Host "No logs found for $dateStr."
    exit 0
}

# --- BATCH PROCESSING LOGIC ---

# Split logs into chunks based on character count
# A simple way is to split by "=== TASK:" delimiter
$tasks = $logContent -split "(?m)^=== TASK: " | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$batches = @()
$currentBatch = ""
$maxBatchSize = 15000 # Adjust as needed for token limits

foreach ($task in $tasks) {
    # Re-add the delimiter removed by split
    $taskStr = "=== TASK: $task"

    if (($currentBatch.Length + $taskStr.Length) -gt $maxBatchSize -and $currentBatch.Length -gt 0) {
        $batches += $currentBatch
        $currentBatch = $taskStr
    } else {
        $currentBatch += $taskStr
    }
}
if ($currentBatch.Length -gt 0) {
    $batches += $currentBatch
}

Write-Host "Split logs into $($batches.Count) batches."

for ($i = 0; $i -lt $batches.Count; $i++) {
    $batchContent = $batches[$i]
    $isFirstBatch = ($i -eq 0)
    $batchNum = $i + 1

    Write-Host "Processing batch $batchNum of $($batches.Count)..."

    if ($isFirstBatch) {
        $instruction = @"
You are an AI assistant helping to generate a daily memory summary for a developer coworker.
Based on the following development logs, generate the content for the daily memory file and save it to the ABSOLUTE path: $memoryFile

SPECIFICATION:
# MEMORY.$compactDate.md
## Daily Memory - $dateStr

### Tasks Executed
- ...

### Execution Quality Review
- What worked well
- What was inefficient

### Issues Encountered
- ...

### Root Cause Analysis
- ...

### Process Improvement Insight
- At least one concrete improvement for future execution

CONSTRAINTS:
- Use English only.
- Be concise but insightful.
- Focus on structural issues and improvements.
- Do NOT just list logs, synthesize them.
- Use the `create` tool to write the file directly using the ABSOLUTE path: $memoryFile. If the file exists, overwrite it (I have already backed it up).
"@
    } else {
        $instruction = @"
You are continuing to generate the daily memory summary for $dateStr.
The memory file '$memoryFile' has already been created with the summary of previous tasks.

YOUR TASK:
1. READ the existing content of '$memoryFile' (using ABSOLUTE path).
2. ANALYZE the NEW logs provided below.
3. UPDATE '$memoryFile' to include the summary of these NEW logs:
    - Append the new tasks to 'Tasks Executed'.
    - Update 'Execution Quality Review', 'Issues Encountered', etc., if the new logs provide additional insights.
    - Consolidate similar points if possible.
4. Ensure the final file maintains the markdown structure.

CONSTRAINTS:
- Use the `edit` tool (or `read` then `create` if needed) to update the file using the ABSOLUTE path: $memoryFile.
- Do NOT overwrite the entire file with just the new logs; you must MERGE/APPEND.
- Keep the existing summary valid while adding new information.
"@
    }

    $prompt = "$instruction`n`nLOGS (Batch $batchNum):`n$batchContent"

    # Use Start-Process to handle arguments safely
    $safePrompt = $prompt.Replace('"', '\"')
    $agentArgList = @($agentBaseArgs + @(
        '--',
        '-p',
        "`"$safePrompt`"",
        '--allow-all-tools'
    ))

    Start-Process -FilePath $agentExecutable -ArgumentList $agentArgList -WorkingDirectory $repoRoot -NoNewWindow -Wait
}
