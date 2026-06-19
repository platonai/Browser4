#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Coworker Daily Memory Generator — improved version.

.DESCRIPTION
    Analyzes daily task logs and generates BOTH a comprehensive long memory file
    and a compressed short memory file for efficient workflow loading.

    Key improvements over the original:
    - Generates both MEMORY.yyyyMMdd.long.md (full detail) and MEMORY.yyyyMMdd.md (<3000 chars)
    - Uses Invoke-AgentWithRetry for timeout+retry safety
    - Uses prompt-utils.ps1 for consistent prompt construction
    - Structured logging via Write-CoworkerLog
    - Validates output files were actually created
    - Proper argument escaping via agent.ps1 (not manual quoting)

.PARAMETER Date
    The date to generate memory for (format: YYYY-MM-DD). Defaults to today.
.PARAMETER TimeoutSeconds
    Maximum time per agent invocation. Defaults to 600 (10 minutes).
.PARAMETER MaxBatchSize
    Maximum characters per log batch. Defaults to 15000.
.PARAMETER DryRun
    Show what would be done without invoking the agent or writing files.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Date = ((Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")),

    [int]$TimeoutSeconds = 600,

    [int]$MaxBatchSize = 15000,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path (Split-Path -Parent $workerDir) 'config.ps1')
. (Join-Path $workerDir 'agent-reliability.ps1')
. (Join-Path $workerDir 'prompt-utils.ps1')

$repoRoot = Get-WorkspaceRoot

# ── Parse date ───────────────────────────────────────────────────────────────
$parsedDate = Get-Date $Date
$year  = $parsedDate.ToString('yyyy')
$month = $parsedDate.ToString('MM')
$day   = $parsedDate.ToString('dd')
$dateStr    = $parsedDate.ToString('yyyy-MM-dd')
$compactDate = $parsedDate.ToString('yyyyMMdd')

# ── Paths ────────────────────────────────────────────────────────────────────
$logDir     = Join-Path (Resolve-TasksPath '300logs') "$year\$month\$day"
$memoryDir  = $logDir
$longFile   = Join-Path $memoryDir "MEMORY.$compactDate.long.md"
$shortFile  = Join-Path $memoryDir "MEMORY.$compactDate.md"

Write-CoworkerLog -Message "Generating daily memory for $dateStr from logs in $logDir" -Level INFO -Component 'daily-memory'

if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

# ── Log collection ───────────────────────────────────────────────────────────

function Get-DailyTaskLogs {
    param([string]$LogDirectory)

    $logContent = ''
    $taskFiles = Get-ChildItem -Path $LogDirectory -Filter '*.task.log' -ErrorAction SilentlyContinue | Sort-Object Name

    if (-not $taskFiles) {
        return ''
    }

    foreach ($taskLog in $taskFiles) {
        $baseName = $taskLog.Name -replace '\.task\.log$', ''
        $agentLogPath = Join-Path $LogDirectory "$baseName.agent.log"

        $logContent += "`n`n=== TASK: $baseName ===`n"

        # Extract metadata
        $lines = Get-Content $taskLog.FullName -TotalCount 10 -ErrorAction SilentlyContinue
        $titleLine = $lines | Where-Object { $_ -match '^Task:' } | Select-Object -First 1
        if ($titleLine) { $logContent += "$titleLine`n" }

        # Extract prompt snippet
        $logContent += "--- PROMPT (Snippet) ---`n"
        try {
            $rawContent = Get-Content $taskLog.FullName -Raw -ErrorAction SilentlyContinue
            $cleanPrompt = ''
            if ($rawContent -match '(?s)Prompt:(.*?)\*\*\* MEMORY UPDATE INSTRUCTIONS \*\*\*') {
                $cleanPrompt = $Matches[1].Trim()
            } elseif ($rawContent -match '(?s)Prompt:(.*)') {
                $cleanPrompt = $Matches[1].Trim()
            } else {
                Write-CoworkerLog -Message "Failed to extract prompt from $($taskLog.Name), using raw first 500 chars" -Level WARN -Component 'daily-memory'
                $cleanPrompt = $rawContent.Substring(0, [Math]::Min(500, $rawContent.Length))
            }
            if ($cleanPrompt.Length -gt 2000) {
                $cleanPrompt = $cleanPrompt.Substring(0, 2000) + '... [Truncated]'
            }
            $logContent += "$cleanPrompt`n"
        } catch {
            Write-CoworkerLog -Message "Error reading $($taskLog.Name): $_" -Level WARN -Component 'daily-memory'
            $logContent += "[Error reading task log]`n"
        }

        # Extract agent output
        $logContent += "--- RESULT (Snippet) ---`n"
        if (Test-Path $agentLogPath) {
            try {
                $agentOutput = @(Get-Content $agentLogPath)
                $lastToolIndex = -1
                for ($i = $agentOutput.Count - 1; $i -ge 0; $i--) {
                    if ($agentOutput[$i] -match '^● (Read|Edit|Write|Run|Create|Bash)') {
                        $lastToolIndex = $i
                        break
                    }
                }

                $head = $agentOutput | Select-Object -First 10
                $tailContent = ''
                if ($lastToolIndex -ge 0) {
                    $tailLines = $agentOutput | Select-Object -Skip $lastToolIndex
                    $tailContent = $tailLines -join "`n"
                } else {
                    $tailLines = $agentOutput | Select-Object -Last 100
                    $tailContent = $tailLines -join "`n"
                }

                $agentText = ($head -join "`n") + "`n... [Intermediate logs skipped] ...`n" + $tailContent
                if ($agentText.Length -gt 20000) {
                    $agentText = $agentText.Substring(0, 20000) + '... [Truncated]'
                }
                $logContent += "$agentText`n"
            } catch {
                $logContent += "[Error reading agent log]`n"
            }
        } else {
            $logContent += "[Agent log not found]`n"
        }
    }

    return $logContent
}

# ── Batching ─────────────────────────────────────────────────────────────────

function Split-LogsIntoBatches {
    param(
        [string]$LogContent,
        [int]$BatchSize = 15000
    )

    $tasks = $LogContent -split '(?m)^=== TASK: ' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $batches = [System.Collections.Generic.List[string]]::new()
    $currentBatch = ''

    foreach ($task in $tasks) {
        $taskStr = "=== TASK: $task"

        if (($currentBatch.Length + $taskStr.Length) -gt $BatchSize -and $currentBatch.Length -gt 0) {
            $batches.Add($currentBatch)
            $currentBatch = $taskStr
        } else {
            $currentBatch += $taskStr
        }
    }

    if ($currentBatch.Length -gt 0) {
        $batches.Add($currentBatch)
    }

    # Use Write-Output -NoEnumerate to prevent PowerShell from unrolling
    # a single-element array into a scalar, which would break $batches.Count
    # in the calling for-loop (it would return string length instead of 1).
    Write-Output $batches.ToArray() -NoEnumerate
}

# ── Main logic ───────────────────────────────────────────────────────────────

$logContent = Get-DailyTaskLogs -LogDirectory $logDir

if ([string]::IsNullOrWhiteSpace($logContent)) {
    Write-CoworkerLog -Message "No logs found for $dateStr." -Level INFO -Component 'daily-memory'
    exit 0
}

$batches = Split-LogsIntoBatches -LogContent $logContent -BatchSize $MaxBatchSize
Write-CoworkerLog -Message "Split logs into $($batches.Count) batch(es)" -Level INFO -Component 'daily-memory'

if ($DryRun) {
    Write-Host "DRY RUN — would process $($batches.Count) batch(es) for $dateStr"
    Write-Host "  Long output : $longFile"
    Write-Host "  Short output: $shortFile"
    Write-Host "  Log sources : $logDir"
    for ($i = 0; $i -lt $batches.Count; $i++) {
        Write-Host "  Batch $($i+1): $($batches[$i].Length) chars"
    }
    exit 0
}

# Process batches
for ($i = 0; $i -lt $batches.Count; $i++) {
    $batchContent = $batches[$i]
    $isFirstBatch = ($i -eq 0)
    $batchNum = $i + 1
    $batchLabel = "Batch $batchNum of $($batches.Count)"

    Write-CoworkerLog -Message "Processing $batchLabel..." -Level INFO -Component 'daily-memory'

    if ($isFirstBatch) {
        # First batch: generate BOTH long and short versions
        $prompt = @"
You are generating the daily memory for $dateStr.

STEP 1 — COMPREHENSIVE VERSION:
Write a complete, detailed daily memory to the ABSOLUTE path: $longFile
Include ALL tasks, insights, issues, root causes, and process improvements.
No length limit — be thorough.

STEP 2 — SHORT VERSION:
Compress the comprehensive version to under 3000 characters and write it to the ABSOLUTE path: $shortFile
Preserve the most important outcomes, key learnings, and critical issues.
This short version is loaded into context on every workflow step, so prioritize
information that will be most valuable for future task execution.

STRUCTURE for both files:
# MEMORY.$compactDate.md
## Daily Memory - $dateStr

### Tasks Executed
### Execution Quality Review
### Issues Encountered
### Root Cause Analysis
### Process Improvement Insight

CONSTRAINTS:
- Use English only. Be concise but insightful.
- Focus on structural issues and improvements — synthesize, do not just list logs.
- Write to the EXACT absolute paths provided. Do not modify them.

LOGS ($batchLabel):
$batchContent
"@
    } else {
        # Continuation batch: merge into existing files
        $prompt = @"
You are continuing the daily memory for $dateStr.

The files already exist:
  Long version  (full detail): $longFile
  Short version (<3000 chars): $shortFile

YOUR TASK:
1. READ the existing content of BOTH files (use ABSOLUTE paths above).
2. ANALYZE the NEW logs provided below.
3. UPDATE BOTH files to include insights from these new logs:
   - Append new tasks to 'Tasks Executed'.
   - Update 'Execution Quality Review', 'Issues Encountered', etc., with new insights.
   - Consolidate similar points.
4. For the SHORT version ($shortFile): ensure it remains under 3000 characters
   after merging the new information. If it would exceed the limit, compress
   further by prioritizing the MOST important information.

CONSTRAINTS:
- Use ABSOLUTE paths exactly as given above.
- Do NOT overwrite entirely with only the new logs — MERGE/APPEND.
- Keep existing, valid content while adding new information.
- The short version MUST remain under 3000 characters.

NEW LOGS ($batchLabel):
$batchContent
"@
    }

    if ($PSCmdlet.ShouldProcess("Batch $batchNum", "Invoke agent to generate/update daily memory")) {
        try {
            Invoke-AgentWithRetry `
                -Prompt $prompt `
                -CaptureOutput `
                -TimeoutSeconds $TimeoutSeconds `
                -MaxRetries 2 `
                -LogComponent 'daily-memory' `
                -RepoRoot $repoRoot
        } catch {
            Write-CoworkerLog -Message "Batch $batchNum failed after retries: $_" -Level ERROR -Component 'daily-memory'
            if ($isFirstBatch) {
                # If first batch fails, remaining batches have nothing to merge into
                Write-CoworkerLog -Message 'First batch failed — aborting (subsequent batches would fail on missing file)' -Level ERROR -Component 'daily-memory'
                exit 1
            }
            Write-CoworkerLog -Message "Continuing despite batch $batchNum failure" -Level WARN -Component 'daily-memory'
        }
    }
}

# ── Validation ───────────────────────────────────────────────────────────────
$longExists  = Test-Path $longFile
$shortExists = Test-Path $shortFile

if ($longExists) {
    $longSize = (Get-Item $longFile).Length
    Write-CoworkerLog -Message "Long memory written: $longFile ($longSize bytes)" -Level INFO -Component 'daily-memory'
} else {
    Write-CoworkerLog -Message "WARNING: Long memory file was NOT created: $longFile" -Level WARN -Component 'daily-memory'
}

if ($shortExists) {
    $shortSize = (Get-Item $shortFile).Length
    Write-CoworkerLog -Message "Short memory written: $shortFile ($shortSize bytes)" -Level INFO -Component 'daily-memory'

    # Warn if short version exceeds 3000 chars
    $shortContent = Get-Content $shortFile -Raw -Encoding UTF8
    if ($shortContent.Length -gt 3000) {
        Write-CoworkerLog -Message "WARNING: Short memory is $($shortContent.Length) chars (target: <3000)" -Level WARN -Component 'daily-memory'
    }
} else {
    Write-CoworkerLog -Message "WARNING: Short memory file was NOT created: $shortFile" -Level WARN -Component 'daily-memory'
}

if (-not $longExists -and -not $shortExists) {
    exit 1
}

Write-CoworkerLog -Message "Daily memory generation complete for $dateStr" -Level INFO -Component 'daily-memory'
exit 0
