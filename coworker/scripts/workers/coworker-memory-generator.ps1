#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Coworker Memory Generator
.DESCRIPTION
    Generates memory summaries (daily, monthly, yearly, global) based on logs or previous summaries.
.PARAMETER Type
    The type of memory to generate: "daily", "monthly", "yearly", "global". Defaults to "daily".
.PARAMETER Date
    The date to generate memory for (format: YYYY-MM-DD). Defaults to today.
.PARAMETER Force
    Force generation even if file exists (overwrites).
#>
param(
    [ValidateSet("daily", "monthly", "yearly", "global", "init")]
    [string]$Type = "daily",

    [string]$Date = ((Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")),

    [switch]$Force
)

$ErrorActionPreference = "Stop"

$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) "config.ps1"
. $configPath
$repoRoot = Get-WorkspaceRoot
$agentHelper = Join-Path $PSScriptRoot 'agent.ps1'
. $agentHelper

$parsedDate = Get-Date $Date
$year = $parsedDate.ToString("yyyy")
$month = $parsedDate.ToString("MM")
$day = $parsedDate.ToString("dd")

$logsBaseDir = Join-Path "$HOME\.browser4\development" "logs"

# Function to invoke the agent
function Invoke-CoworkerMemoryAgent {
    param(
        [string]$Prompt,
        [switch]$CaptureOutput
    )

    # Truncate if too long (approx check, limit depends on OS/shell but 20k is safeish)
    if ($Prompt.Length -gt 25000) {
        Write-Warning "Prompt is too long ($($Prompt.Length) chars). Truncating..."
        $Prompt = $Prompt.Substring(0, 25000) + " ... [Truncated]"
    }

    return Invoke-Agent -Prompt $Prompt -AdditionalArguments @('--allow-all-tools') -RepoRoot $repoRoot -WorkingDirectory $repoRoot -CaptureOutput:$CaptureOutput
}

if ($Type -eq "daily") {
    # Reuse existing logic or call the existing script?
    # Better to keep logic self-contained if we want this to be the main entry point.
    # For now, let's call the existing script to avoid duplication if it exists, or reimplement.
    # The existing script is specific to daily. Let's call it.

    $dailyScript = Join-Path $repoRoot "coworker\scripts\workers\coworker-daily-memory-generator.ps1"
    if (Test-Path $dailyScript) {
        & $dailyScript -Date $Date
    } else {
        Write-Error "Daily memory generator script not found at $dailyScript"
    }
}
elseif ($Type -eq "monthly") {
    $targetDir = "$logsBaseDir\$year\$month"
    $targetFile = "$targetDir\MEMORY.$year$month.md"

    if (-not (Test-Path $targetDir)) {
        Write-Error "Directory $targetDir does not exist. No daily memories to summarize."
        exit 1
    }

    # Gather all daily memories for the month
    $dailyMemories = Get-ChildItem -Path "$targetDir\*\MEMORY.*.md" -Recurse

    if ($dailyMemories.Count -eq 0) {
        Write-Warning "No daily memories found for $year-$month."
        exit 0
    }

    $combinedContent = ""
    foreach ($file in $dailyMemories) {
        $content = Get-Content $file.FullName -Raw
        $combinedContent += "`n`n=== DAILY MEMORY: $($file.Name) ===`n$content"
    }

    $prompt = @"
You are an AI assistant helping to generate a MONTHLY memory summary for a developer coworker.
Based on the following DAILY memories, generate the content for the MONTHLY memory file and save it to the ABSOLUTE path: $targetFile

SPECIFICATION:
# MEMORY.$year$month.md
## Monthly Memory - $year-$month

### Work Themes
- Major areas of focus this month

### Recurring Issues
- Problems that happened multiple times

### Structural Bottlenecks
- Process or technical limitations slowing progress

### Efficiency Trend
- Qualitative assessment of speed/quality over the month

### System Adjustments Proposed
- Changes to tools/workflow based on this month's experience

CONSTRAINTS:
- Use English only.
- Synthesize, don't just list.
- Use the `create` tool to write the file directly using the ABSOLUTE path: $targetFile
- Overwrite if exists.

DAILY MEMORIES:
$combinedContent
"@

    Invoke-CoworkerMemoryAgent -Prompt $prompt
}
elseif ($Type -eq "yearly") {
    $targetDir = "$logsBaseDir\$year"
    $targetFile = "$targetDir\MEMORY.$year.md"

    # Gather all monthly memories for the year
    # Monthly memories are at logs/YYYY/MM/MEMORY.YYYYMM.md
    $monthlyMemories = Get-ChildItem -Path "$logsBaseDir\$year\*\MEMORY.$year*.md"

    if ($monthlyMemories.Count -eq 0) {
        Write-Warning "No monthly memories found for $year."
        exit 0
    }

    $combinedContent = ""
    foreach ($file in $monthlyMemories) {
        $content = Get-Content $file.FullName -Raw
        $combinedContent += "`n`n=== MONTHLY MEMORY: $($file.Name) ===`n$content"
    }

    $prompt = @"
You are an AI assistant helping to generate a YEARLY memory summary for a developer coworker.
Based on the following MONTHLY memories, generate the content for the YEARLY memory file and save it to the ABSOLUTE path: $targetFile

SPECIFICATION:
# MEMORY.$year.md
## Annual Strategic Review - $year

### Project State Evolution
- High-level changes in project scope/maturity

### Major Achievements
- Key milestones reached

### Major Failures
- Significant setbacks and lessons

### Structural Problems (Solved / Unsolved)
- Persistent issues

### Capability Upgrades
- New skills/tools acquired

### Strategic Risks
- Potential future threats

### Project Trajectory Forecast
- Where the project is heading

### Three Immediate Strategic Actions
- High-level next steps for next year

CONSTRAINTS:
- Use English only.
- Synthesize, don't just list.
- Use the `create` tool to write the file directly using the ABSOLUTE path: $targetFile
- Overwrite if exists.

MONTHLY MEMORIES:
$combinedContent
"@

    Invoke-CoworkerMemoryAgent -Prompt $prompt
}
elseif ($Type -eq "global") {
    $targetFile = "$logsBaseDir\MEMORY.md"

    # Gather all yearly memories
    # Yearly memories are at logs/YYYY/MEMORY.YYYY.md
    $yearlyMemories = Get-ChildItem -Path "$logsBaseDir\*\MEMORY.*.md" | Where-Object { $_.Name -match "MEMORY\.\d{4}\.md" }

    if ($yearlyMemories.Count -eq 0) {
        # Fallback to monthly if no yearly? Or just warn?
        Write-Warning "No yearly memories found. Trying monthly..."
        $yearlyMemories = Get-ChildItem -Path "$logsBaseDir\*\*\MEMORY.*.md" | Where-Object { $_.Name -match "MEMORY\.\d{6}\.md" }
    }

    if ($yearlyMemories.Count -eq 0) {
        Write-Warning "No memories found to summarize."
        exit 0
    }

    $combinedContent = ""
    foreach ($file in $yearlyMemories) {
        $content = Get-Content $file.FullName -Raw
        $combinedContent += "`n`n=== MEMORY: $($file.Name) ===`n$content"
    }

    Invoke-CoworkerMemoryAgent -Prompt $prompt
}
elseif ($Type -eq "init") {
    $year = $parsedDate.ToString("yyyy")
    $month = $parsedDate.ToString("MM")
    $day = $parsedDate.ToString("dd")

    # 1. Define paths
    $memoryDir = $logsBaseDir
    $memoryYearDir = Join-Path $memoryDir $year
    $memoryMonthDir = Join-Path $memoryYearDir $month
    $memoryDayDir = Join-Path $memoryMonthDir $day

    # 2. Ensure directories exist
    if (-not (Test-Path $memoryYearDir)) { New-Item -ItemType Directory -Path $memoryYearDir -Force | Out-Null }
    if (-not (Test-Path $memoryMonthDir)) { New-Item -ItemType Directory -Path $memoryMonthDir -Force | Out-Null }
    if (-not (Test-Path $memoryDayDir)) { New-Item -ItemType Directory -Path $memoryDayDir -Force | Out-Null }

    $memoryYearPath = Join-Path $memoryYearDir "MEMORY.$year.md"
    $memoryMonthPath = Join-Path $memoryMonthDir "MEMORY.$year$month.md"
    $memoryDayPath = Join-Path $memoryDayDir "MEMORY.$year$month$day.md"
    $memoryDayLongPath = Join-Path $memoryDayDir "MEMORY.$year$month$day.long.md"

    # 3. Check Daily Memory Size and Compress if needed
    if (Test-Path $memoryDayPath) {
        $dailyContent = Get-Content $memoryDayPath -Raw -Encoding UTF8
        if ($dailyContent.Length -gt 3000) {
            Write-Warning "Daily memory exceeds 3000 chars ($($dailyContent.Length)). Initiating compression..."

            # Backup
            Copy-Item -Path $memoryDayPath -Destination $memoryDayLongPath -Force
            Write-Warning "Original memory backed up to: $memoryDayLongPath"

            # Compress
            $compressPrompt = "Compress the following daily memory content to under 3000 characters. Preserve key insights and structural learnings. content:`n$dailyContent"

            # Compress using the agent and capture the replacement markdown for the memory file.
            $compressedContent = Invoke-CoworkerMemoryAgent -Prompt $compressPrompt -CaptureOutput

            if (-not [string]::IsNullOrWhiteSpace($compressedContent)) {
                 # The output might contain explanation text. The agent usually just answers if prompted correctly.
                 # But sometimes it chats.
                 # Assuming it returns markdown.
                 $compressedContent | Out-File -FilePath $memoryDayPath -Encoding UTF8 -Force
                 Write-Warning "Daily memory compressed to $($compressedContent.Length) chars."
            }
        }
    } else {
        # Create empty daily memory if not exists?
        # Maybe unnecessary, Agent will create it.
        # But for context string, it's good to know.
    }

    # 4. Construct Context String
    $memoryContext = ""
    if (Test-Path $memoryMonthPath) {
        $monthContent = Get-Content $memoryMonthPath -Raw -Encoding UTF8
        $memoryContext += "`n[Monthly Memory ($year-$month)]:`n$monthContent`n"
    }

    if (Test-Path $memoryDayPath) {
        $dayContent = Get-Content $memoryDayPath -Raw -Encoding UTF8
        $memoryContext += "`n[Daily Memory ($year-$month-$day)]:`n$dayContent`n"
    }

    # 5. Construct Instructions String
    $memoryInstructions = @"
*** MEMORY UPDATE INSTRUCTIONS ***
You have a memory system to help you learn and improve.
Your memory files are located in: $logsBaseDir

After completing the task, you MUST update your daily memory file: $memoryDayPath
1. Append a summary of this task, its outcome, and any lessons learned to $memoryDayPath.
2. Check if the Monthly Memory file ($memoryMonthPath) has been updated with the previous day's summary. If not, summarize all daily memories from this month (excluding today) into the Monthly Memory.
3. Ensure you do not overwrite existing content, always append.
"@

    # 6. Output JSON
    $result = @{
        context = $memoryContext
        instructions = $memoryInstructions
    }

    $json = $result | ConvertTo-Json -Depth 2
    Write-Output $json
}
