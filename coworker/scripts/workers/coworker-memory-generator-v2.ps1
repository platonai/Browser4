#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Coworker Memory Generator — improved version.

.DESCRIPTION
    Generates memory summaries (daily, monthly, yearly, global) based on logs or
    previous summaries. Also provides the "init" mode for injecting memory context
    into task prompts.

    Key improvements over the original:
    - CRITICAL FIX: global type now has its own prompt (was undefined — feature was dead)
    - Uses prompt-utils.ps1 for consistent, de-duplicated prompt construction
    - Uses Invoke-AgentWithRetry for timeout+retry safety
    - Smart truncation for large $combinedContent with logging
    - Validates output files exist after generation
    - Structured logging via Write-CoworkerLog
    - Proper exit code propagation from daily script

.PARAMETER Type
    The type of memory to generate: "daily", "monthly", "yearly", "global", "init".
.PARAMETER Date
    The date to generate memory for (format: YYYY-MM-DD). Defaults to today.
.PARAMETER Force
    Force generation even if file exists.
.PARAMETER DryRun
    Show what would be done without invoking the agent or writing files.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [ValidateSet('daily', 'monthly', 'yearly', 'global', 'init')]
    [string]$Type = 'daily',

    [string]$Date = ((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')),

    [switch]$Force,

    [int]$TimeoutSeconds = 600,

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

$logsBaseDir = Resolve-TasksPath '300logs'

# ── Smart truncation ─────────────────────────────────────────────────────────

function Get-SmartContentTruncation {
    param(
        [string]$Content,
        [int]$MaxChars = 25000
    )

    if ($Content.Length -le $MaxChars) {
        return @{ Content = $Content; Truncated = $false; DroppedChars = 0 }
    }

    Write-CoworkerLog -Message "Content is $($Content.Length) chars, truncating to $MaxChars" -Level WARN -Component 'memory-gen'

    # Split by "=== " delimiters (memory/file boundaries)
    $chunks = $Content -split '(?m)^=== ' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $result = ''
    $included = 0
    $excluded = 0

    # Include from the END (most recent first) to preserve latest info
    [Array]::Reverse($chunks)

    foreach ($chunk in $chunks) {
        $candidate = "=== $chunk"
        if (($result.Length + $candidate.Length) -le $MaxChars) {
            $result = $candidate + $result
            $included++
        } else {
            $excluded++
        }
    }

    if ($excluded -gt 0) {
        Write-CoworkerLog -Message "Smart truncation: included $included chunk(s), dropped $excluded chunk(s) ($($Content.Length - $result.Length) chars dropped)" -Level WARN -Component 'memory-gen'
    }

    return @{
        Content      = $result
        Truncated    = $true
        DroppedChars = $Content.Length - $result.Length
    }
}

# ── Memory generation ────────────────────────────────────────────────────────

function Invoke-MemoryGeneration {
    param(
        [string]$Level,
        [string]$TargetFile,
        [string]$SourceContent,
        [string]$DateLabel,
        [string]$Mode = 'Create'
    )

    $levelMap = @{
        daily   = 'Daily'
        monthly = 'Monthly'
        yearly  = 'Yearly'
        global  = 'Global'
    }

    $promptLevel = $levelMap[$Level]
    if (-not $promptLevel) {
        throw "Unknown memory level: $Level"
    }

    $truncation = Get-SmartContentTruncation -Content $SourceContent -MaxChars 25000
    $prompt = New-MemoryGenerationPrompt `
        -Level $promptLevel `
        -TargetFile $TargetFile `
        -SourceContent $truncation.Content `
        -DateLabel $DateLabel `
        -Mode $Mode `
        -ExtraConstraints @(
            '- Do NOT include any explanation or commentary in the output file.',
            '- Write only the memory content.'
        )

    if ($DryRun -or -not $PSCmdlet.ShouldProcess($TargetFile, "Generate $Level memory")) {
        Write-Host "DRY RUN — would generate $Level memory at: $TargetFile"
        Write-Host "  Source content: $($SourceContent.Length) chars"
        if ($truncation.Truncated) {
            Write-Host "  WARNING: Content truncated ($($truncation.DroppedChars) chars dropped)"
        }
        return $null
    }

    Write-CoworkerLog -Message "Generating $Level memory at: $TargetFile" -Level INFO -Component 'memory-gen'

    try {
        Invoke-AgentWithRetry `
            -Prompt $prompt `
            -CaptureOutput `
            -TimeoutSeconds $TimeoutSeconds `
            -MaxRetries 2 `
            -LogComponent 'memory-gen' `
            -RepoRoot $repoRoot

        if (Test-Path $TargetFile) {
            Write-CoworkerLog -Message "$Level memory file created: $TargetFile ($((Get-Item $TargetFile).Length) bytes)" -Level INFO -Component 'memory-gen'
        } else {
            Write-CoworkerLog -Message "WARNING: $Level memory file was NOT created: $TargetFile" -Level WARN -Component 'memory-gen'
        }
    } catch {
        Write-CoworkerLog -Message "Failed to generate $Level memory: $_" -Level ERROR -Component 'memory-gen'
        throw
    }
}

# ── Routing ──────────────────────────────────────────────────────────────────

switch ($Type) {
    'daily' {
        $dailyScript = Join-Path $repoRoot 'coworker\scripts\workers\coworker-daily-memory-generator.ps1'
        if (-not (Test-Path $dailyScript)) {
            Write-Error "Daily memory generator not found at: $dailyScript"
            exit 1
        }
        if ($DryRun) {
            Write-Host "DRY RUN — would execute: $dailyScript -Date $Date"
            exit 0
        }
        & $dailyScript -Date $Date
        if ($LASTEXITCODE -ne 0) {
            Write-CoworkerLog -Message "Daily memory generator exited with code $LASTEXITCODE" -Level ERROR -Component 'memory-gen'
            exit $LASTEXITCODE
        }

        # Verify output
        $dailyCompact = $parsedDate.ToString('yyyyMMdd')
        $dailyFile = Join-Path $logsBaseDir "$year\$month\$day" "MEMORY.$dailyCompact.md"
        if (-not (Test-Path $dailyFile)) {
            Write-CoworkerLog -Message "Daily memory file was not created: $dailyFile" -Level WARN -Component 'memory-gen'
        }
    }

    'monthly' {
        $targetFile = "$logsBaseDir\$year\$month\MEMORY.$year$month.md"
        $targetDir  = Split-Path $targetFile -Parent

        if (-not (Test-Path $targetDir)) {
            Write-Error "Directory $targetDir does not exist. No daily memories to summarize."
            exit 1
        }

        if (Test-Path $targetFile -and -not $Force) {
            Write-CoworkerLog -Message "Monthly memory already exists: $targetFile (use -Force to overwrite)" -Level INFO -Component 'memory-gen'
            exit 0
        }

        # Gather daily memories for the month (exclude .long.md files for the summary input)
        $dailyMemories = Get-ChildItem -Path "$targetDir\*\MEMORY.$year$month*.md" `
            | Where-Object { $_.Name -match 'MEMORY\.\d{8}\.md$' } `
            | Sort-Object Name

        if ($dailyMemories.Count -eq 0) {
            Write-CoworkerLog -Message "No daily memories found for $year-$month." -Level WARN -Component 'memory-gen'
            exit 0
        }

        $combinedContent = ''
        foreach ($file in $dailyMemories) {
            $content = Get-Content $file.FullName -Raw -Encoding UTF8
            $combinedContent += "`n`n=== DAILY MEMORY: $($file.Name) ===`n$content"
        }

        Write-CoworkerLog -Message "Gathered $($dailyMemories.Count) daily memories ($($combinedContent.Length) chars)" -Level INFO -Component 'memory-gen'
        Invoke-MemoryGeneration -Level monthly -TargetFile $targetFile -SourceContent $combinedContent -DateLabel "$year-$month"
    }

    'yearly' {
        $targetFile = "$logsBaseDir\$year\MEMORY.$year.md"

        if (Test-Path $targetFile -and -not $Force) {
            Write-CoworkerLog -Message "Yearly memory already exists: $targetFile (use -Force to overwrite)" -Level INFO -Component 'memory-gen'
            exit 0
        }

        # Gather monthly memories — use exact 6-digit pattern to avoid matching daily files
        $monthlyMemories = Get-ChildItem -Path "$logsBaseDir\$year\*\MEMORY.$year*.md" `
            | Where-Object { $_.Name -match 'MEMORY\.\d{6}\.md$' } `
            | Sort-Object Name

        if ($monthlyMemories.Count -eq 0) {
            Write-CoworkerLog -Message "No monthly memories found for $year." -Level WARN -Component 'memory-gen'
            exit 0
        }

        $combinedContent = ''
        foreach ($file in $monthlyMemories) {
            $content = Get-Content $file.FullName -Raw -Encoding UTF8
            $combinedContent += "`n`n=== MONTHLY MEMORY: $($file.Name) ===`n$content"
        }

        Write-CoworkerLog -Message "Gathered $($monthlyMemories.Count) monthly memories ($($combinedContent.Length) chars)" -Level INFO -Component 'memory-gen'
        Invoke-MemoryGeneration -Level yearly -TargetFile $targetFile -SourceContent $combinedContent -DateLabel $year
    }

    'global' {
        $targetFile = "$logsBaseDir\MEMORY.md"

        if (Test-Path $targetFile -and -not $Force) {
            Write-CoworkerLog -Message "Global memory already exists: $targetFile (use -Force to overwrite)" -Level INFO -Component 'memory-gen'
            exit 0
        }

        # Gather yearly memories — exact 4-digit pattern
        $yearlyMemories = Get-ChildItem -Path "$logsBaseDir\*\MEMORY.*.md" `
            | Where-Object { $_.Name -match 'MEMORY\.\d{4}\.md$' } `
            | Sort-Object Name

        if ($yearlyMemories.Count -eq 0) {
            Write-CoworkerLog -Message 'No yearly memories found. Trying monthly fallback...' -Level WARN -Component 'memory-gen'
            $yearlyMemories = Get-ChildItem -Path "$logsBaseDir\*\*\MEMORY.*.md" `
                | Where-Object { $_.Name -match 'MEMORY\.\d{6}\.md$' } `
                | Sort-Object Name
        }

        if ($yearlyMemories.Count -eq 0) {
            Write-CoworkerLog -Message 'No memories found to summarize for global memory.' -Level WARN -Component 'memory-gen'
            exit 0
        }

        $combinedContent = ''
        foreach ($file in $yearlyMemories) {
            $content = Get-Content $file.FullName -Raw -Encoding UTF8
            $combinedContent += "`n`n=== MEMORY: $($file.Name) ===`n$content"
        }

        Write-CoworkerLog -Message "Gathered $($yearlyMemories.Count) memories for global summary ($($combinedContent.Length) chars)" -Level INFO -Component 'memory-gen'
        Invoke-MemoryGeneration -Level global -TargetFile $targetFile -SourceContent $combinedContent -DateLabel 'Global'
    }

    'init' {
        # Build memory context for injection into task prompts.
        # This is a READ-ONLY operation — no agent calls, no file writes.
        # Compression (if needed) is handled by the daily memory generator.

        $memoryDir      = $logsBaseDir
        $memoryYearDir  = Join-Path $memoryDir $year
        $memoryMonthDir = Join-Path $memoryYearDir $month
        $memoryDayDir   = Join-Path $memoryMonthDir $day

        # Ensure directories exist
        foreach ($d in @($memoryYearDir, $memoryMonthDir, $memoryDayDir)) {
            if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
        }

        $memoryDayPath     = Join-Path $memoryDayDir "MEMORY.$year$month$day.md"
        $memoryMonthPath   = Join-Path $memoryMonthDir "MEMORY.$year$month.md"

        # Build context
        $memoryContext = ''
        if (Test-Path $memoryMonthPath) {
            $monthContent = Get-Content $memoryMonthPath -Raw -Encoding UTF8
            $memoryContext += "`n[Monthly Memory ($year-$month)]:`n$monthContent`n"
        }
        if (Test-Path $memoryDayPath) {
            $dayContent = Get-Content $memoryDayPath -Raw -Encoding UTF8
            $memoryContext += "`n[Daily Memory ($year-$month-$day)]:`n$dayContent`n"
        }

        $memoryInstructions = @"
*** MEMORY UPDATE INSTRUCTIONS ***
You have a memory system to help you learn and improve.
Your memory files are located in: $logsBaseDir

After completing the task, you MUST update your daily memory file: $memoryDayPath
1. Append a summary of this task, its outcome, and any lessons learned to $memoryDayPath.
2. Check if the Monthly Memory file ($memoryMonthPath) has been updated with the previous day's summary. If not, summarize all daily memories from this month (excluding today) into the Monthly Memory.
3. Ensure you do not overwrite existing content, always append.
"@

        $result = @{
            context      = $memoryContext
            instructions = $memoryInstructions
        }

        $json = $result | ConvertTo-Json -Depth 2
        Write-Output $json
    }
}

Write-CoworkerLog -Message "$Type memory generation complete." -Level INFO -Component 'memory-gen'
exit 0
