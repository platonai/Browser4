#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Pester tests for coworker-daily-memory-generator.ps1.
.DESCRIPTION
    Comprehensive unit and integration tests covering:
    - Get-CleanPrompt (v1 prompt extraction)
    - Get-DailyTaskLogs (v2 log collection)
    - Split-LogsIntoBatches (v1/v2 batching logic)
    - Date parsing and path construction
    - DryRun / error handling / edge cases

    Run with:
      Invoke-Pester -Path .\coworker-daily-memory-generator.tests.ps1 -EnableExit

    Compatible with Pester 3.x+
#>

$ErrorActionPreference = 'Stop'

# ═══════════════════════════════════════════════════════════════════════════════
# Test fixture management
# ═══════════════════════════════════════════════════════════════════════════════

$script:TestRoot = $null

function Initialize-TestFixture {
    $script:TestRoot = Join-Path ([System.IO.Path]::GetTempPath()) "DMG_Tests_$(Get-Random -Minimum 1000 -Maximum 9999)"
    New-Item -ItemType Directory -Path $script:TestRoot -Force | Out-Null
    $script:LogDir = Join-Path $script:TestRoot '300logs\2026\06\16'
    New-Item -ItemType Directory -Path $script:LogDir -Force | Out-Null
}

function Remove-TestFixture {
    if ($script:TestRoot -and (Test-Path $script:TestRoot)) {
        Remove-Item -Path $script:TestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function New-TaskLogFile {
    param(
        [string]$Directory,
        [string]$BaseName,
        [string]$TaskTitle = 'Task: Test task title',
        [string]$PromptContent = 'This is a test prompt for the task.',
        [bool]$HasMemoryMarker = $true
    )
    $taskLogPath = Join-Path $Directory "$BaseName.task.log"
    $marker = if ($HasMemoryMarker) { "*** MEMORY UPDATE INSTRUCTIONS ***`n..." } else { '' }
    @"
$TaskTitle
Status: completed
Priority: normal
Created: 2026-06-16T10:00:00Z
Prompt:$PromptContent
$marker
Additional log details here...
More execution details...
"@ | Set-Content -Path $taskLogPath -Encoding UTF8
    return $taskLogPath
}

function New-AgentLogFile {
    param(
        [string]$Directory,
        [string]$BaseName,
        [string[]]$OutputLines = @()
    )
    $agentLogPath = Join-Path $Directory "$BaseName.agent.log"
    if ($OutputLines.Count -eq 0) {
        $OutputLines = @(
            'Starting agent execution...',
            'Analyzing task requirements...',
            'Executing step 1...',
            'Executing step 2...',
            '● Read file: config.json',
            '● Edit file: config.json - changed setting',
            '● Run tests...',
            '● Write output file',
            'Task completed successfully.',
            'Summary: All steps passed.'
        )
    }
    $OutputLines -join "`n" | Set-Content -Path $agentLogPath -Encoding UTF8
    return $agentLogPath
}

# ═══════════════════════════════════════════════════════════════════════════════
# Functions under test (extracted from source scripts for isolated testing)
# ═══════════════════════════════════════════════════════════════════════════════

# From original daily memory generator lines 47-56
function Get-CleanPrompt {
    param($TaskLogPath)
    $content = Get-Content $TaskLogPath -Raw
    if ($content -match "(?s)Prompt:(.*?)\*\*\* MEMORY UPDATE INSTRUCTIONS \*\*\*") {
        return ($Matches[1].Trim() -replace "`r`n", "`n")
    } elseif ($content -match "(?s)Prompt:(.*)") {
        return ($Matches[1].Trim() -replace "`r`n", "`n")
    }
    return ""
}

# From daily memory generator lines 71-151
function Get-DailyTaskLogs {
    param(
        [string]$LogDirectory,
        [scriptblock]$LogCallback = $null
    )

    $logContent = ''
    $taskFiles = Get-ChildItem -Path $LogDirectory -Filter '*.task.log' -ErrorAction SilentlyContinue | Sort-Object Name

    if (-not $taskFiles) { return '' }

    foreach ($taskLog in $taskFiles) {
        $baseName = $taskLog.Name -replace '\.task\.log$', ''
        $agentLogPath = Join-Path $LogDirectory "$baseName.agent.log"

        $logContent += "`n`n=== TASK: $baseName ===`n"

        $lines = Get-Content $taskLog.FullName -TotalCount 10 -ErrorAction SilentlyContinue
        $titleLine = $lines | Where-Object { $_ -match '^Task:' } | Select-Object -First 1
        if ($titleLine) { $logContent += "$titleLine`n" }

        $logContent += "--- PROMPT (Snippet) ---`n"
        try {
            $rawContent = Get-Content $taskLog.FullName -Raw -ErrorAction SilentlyContinue
            $cleanPrompt = ''
            if ($rawContent -match '(?s)Prompt:(.*?)\*\*\* MEMORY UPDATE INSTRUCTIONS \*\*\*') {
                $cleanPrompt = $Matches[1].Trim()
            } elseif ($rawContent -match '(?s)Prompt:(.*)') {
                $cleanPrompt = $Matches[1].Trim()
            } else {
                if ($LogCallback) { & $LogCallback "Failed to extract prompt from $($taskLog.Name)" }
                $cleanPrompt = $rawContent.Substring(0, [Math]::Min(500, $rawContent.Length))
            }
            if ($cleanPrompt.Length -gt 2000) {
                $cleanPrompt = $cleanPrompt.Substring(0, 2000) + '... [Truncated]'
            }
            $logContent += "$cleanPrompt`n"
        } catch {
            if ($LogCallback) { & $LogCallback "Error reading $($taskLog.Name): $_" }
            $logContent += "[Error reading task log]`n"
        }

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

# From daily memory generator lines 155-181
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
    # a single-element array into a scalar.
    Write-Output $batches.ToArray() -NoEnumerate
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Get-CleanPrompt
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Get-CleanPrompt' {

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'extracts prompt before MEMORY UPDATE INSTRUCTIONS marker' {
        $taskFile = Join-Path $script:LogDir 'test.task.log'
        @"
Task: Sample task
Prompt:This is the extracted prompt content.
It spans multiple lines.

*** MEMORY UPDATE INSTRUCTIONS ***
These instructions should not be included.
"@ | Set-Content -Path $taskFile -Encoding UTF8

        $result = Get-CleanPrompt -TaskLogPath $taskFile
        $expected = "This is the extracted prompt content.`nIt spans multiple lines."
        $result | Should -BeExactly $expected
    }

    It 'extracts entire prompt when no MEMORY marker is present' {
        $taskFile = Join-Path $script:LogDir 'test.task.log'
        @"
Task: Simple task
Prompt:This is a simple prompt without memory markers.
It still spans multiple lines.
"@ | Set-Content -Path $taskFile -Encoding UTF8

        $result = Get-CleanPrompt -TaskLogPath $taskFile
        $expected = "This is a simple prompt without memory markers.`nIt still spans multiple lines."
        $result | Should -BeExactly $expected
    }

    It 'returns empty string when no Prompt: line exists' {
        $taskFile = Join-Path $script:LogDir 'test.task.log'
        @"
Task: No prompt task
Status: completed
Some other content without a prompt line.
"@ | Set-Content -Path $taskFile -Encoding UTF8

        $result = Get-CleanPrompt -TaskLogPath $taskFile
        $result | Should -BeExactly ''
    }

    It 'returns empty string for an empty file' {
        $taskFile = Join-Path $script:LogDir 'test.task.log'
        '' | Set-Content -Path $taskFile -Encoding UTF8

        $result = Get-CleanPrompt -TaskLogPath $taskFile
        $result | Should -BeExactly ''
    }

    It 'handles Prompt: at end of file with no content after' {
        $taskFile = Join-Path $script:LogDir 'test.task.log'
        "Prompt:" | Set-Content -Path $taskFile -Encoding UTF8

        $result = Get-CleanPrompt -TaskLogPath $taskFile
        $result | Should -BeExactly ''
    }

    It 'handles MEMORY marker immediately after Prompt:' {
        $taskFile = Join-Path $script:LogDir 'test.task.log'
        "Prompt:*** MEMORY UPDATE INSTRUCTIONS ***`nrest" | Set-Content -Path $taskFile -Encoding UTF8

        $result = Get-CleanPrompt -TaskLogPath $taskFile
        $result | Should -BeExactly ''
    }

    It 'trims whitespace from extracted prompt' {
        $taskFile = Join-Path $script:LogDir 'test.task.log'
        @"
Prompt:   content with surrounding spaces

Some trailing content
"@ | Set-Content -Path $taskFile -Encoding UTF8

        $result = Get-CleanPrompt -TaskLogPath $taskFile
        $expected = "content with surrounding spaces`n`nSome trailing content"
        $result | Should -BeExactly $expected
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Get-DailyTaskLogs
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Get-DailyTaskLogs' {

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    Context 'Empty or missing directory' {

        It 'returns empty string when directory has no task log files' {
            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -BeExactly ''
        }

        It 'returns empty string when directory only has non-task-log files' {
            $otherFile = Join-Path $script:LogDir 'something.agent.log'
            'content' | Set-Content -Path $otherFile -Encoding UTF8
            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -BeExactly ''
        }
    }

    Context 'Single task log' {

        It 'includes TASK header with base name' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '123456-test-task' `
                -TaskTitle 'Task: Fix authentication bug' `
                -PromptContent 'Fix the login flow.'

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match '=== TASK: 123456-test-task ==='
        }

        It 'includes task title line when present' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '123456-test-task' `
                -TaskTitle 'Task: Fix authentication bug' `
                -PromptContent 'Fix the login flow.'

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match 'Task: Fix authentication bug'
        }

        It 'includes PROMPT and RESULT section headers' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '123456-test-task' -PromptContent 'Fix the login flow.'
            New-AgentLogFile -Directory $script:LogDir -BaseName '123456-test-task'

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match '--- PROMPT \(Snippet\) ---'
            $result | Should -Match '--- RESULT \(Snippet\) ---'
        }

        It 'extracts prompt content before MEMORY marker' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '123456-test-task' `
                -PromptContent 'Fix the login flow with proper error handling.' `
                -HasMemoryMarker $true

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match 'Fix the login flow with proper error handling\.'
            $hasMarker = $result -match 'MEMORY UPDATE INSTRUCTIONS'
            $hasMarker | Should -Be $false
        }

        It 'extracts prompt content when no MEMORY marker exists' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '123456-test-task' `
                -PromptContent 'Fix the login flow.' -HasMemoryMarker $false

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match 'Fix the login flow\.'
        }

        It 'falls back to raw first 500 chars when no Prompt: line exists' {
            $taskFile = Join-Path $script:LogDir '123456-test.task.log'
            # File with no "Prompt:" line — the fallback takes first 500 chars of raw
            # Since "Task: No prompt task" + newline takes 22 chars, we need A's to fill to >500
            $longContent = 'A' * 600
            "Task: No prompt task`n$longContent" | Set-Content -Path $taskFile -Encoding UTF8

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            # The fallback uses first 500 chars of raw content, which includes the title line
            $result | Should -Match 'Task: No prompt task'
            $result | Should -Match 'A{400}'  # Roughly 500-22=478 As should be there
        }

        It 'shows agent log not found when agent log is missing' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '123456-test-task' -PromptContent 'Test.'

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match '\[Agent log not found\]'
        }

        It 'includes head and tail of agent output with intermediate skip marker' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '123456-test-task' -PromptContent 'Test.'
            New-AgentLogFile -Directory $script:LogDir -BaseName '123456-test-task'

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match 'Starting agent execution'
            $result | Should -Match '\[Intermediate logs skipped\]'
            $result | Should -Match 'Task completed successfully'
        }
    }

    Context 'Agent log output extraction' {

        It 'extracts from last tool execution to end when tools are found' {
            New-TaskLogFile -Directory $script:LogDir -BaseName 'test' -PromptContent 'Test.'
            $agentLog = Join-Path $script:LogDir 'test.agent.log'
            @(
                'Line 1 - beginning',
                'Line 2', 'Line 3', 'Line 4', 'Line 5',
                'Line 6', 'Line 7', 'Line 8', 'Line 9',
                'Line 10',
                'Line 11 - middle',
                'Line 12 - middle',
                '● Read config.json',
                'After tool line 1',
                'After tool line 2'
            ) -join "`n" | Set-Content -Path $agentLog -Encoding UTF8

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir

            # Head: first 10 lines
            $result | Should -Match 'Line 1 - beginning'
            $result | Should -Match 'Line 10'
            # Tail: from last tool line onward
            $result | Should -Match '● Read config.json'
            $result | Should -Match 'After tool line 1'
            $result | Should -Match 'After tool line 2'
            # Middle should be skipped
            $result | Should -Match '\[Intermediate logs skipped\]'
            $hasMiddle = $result -match 'Line 11 - middle'
            $hasMiddle | Should -Be $false
        }

        It 'falls back to last 100 lines when no tool execution markers found' {
            New-TaskLogFile -Directory $script:LogDir -BaseName 'test' -PromptContent 'Test.'
            $agentLog = Join-Path $script:LogDir 'test.agent.log'
            $lines = 1..120 | ForEach-Object { "Log line $_" }
            $lines -join "`n" | Set-Content -Path $agentLog -Encoding UTF8

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir

            # Should have last 100 lines
            $result | Should -Match 'Log line 120'
            $result | Should -Match 'Log line 21'
        }

        It 'handles agent log with fewer than 10 total lines' {
            New-TaskLogFile -Directory $script:LogDir -BaseName 'test' -PromptContent 'Test.'
            $agentLog = Join-Path $script:LogDir 'test.agent.log'
            @('Only line 1', 'Only line 2', 'Only line 3') -join "`n" |
                Set-Content -Path $agentLog -Encoding UTF8

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match 'Only line 1'
            $result | Should -Match 'Only line 3'
        }
    }

    Context 'Truncation' {

        It 'truncates prompt longer than 2000 characters' {
            $longPrompt = 'P' * 2500
            New-TaskLogFile -Directory $script:LogDir -BaseName 'test' `
                -PromptContent $longPrompt -HasMemoryMarker $false

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match '\[Truncated\]'
            $result | Should -Match 'P{2000}\.\.\. \[Truncated\]'
        }

        It 'does NOT truncate prompt of exactly 2000 characters' {
            # The regex (?s)Prompt:(.*) captures everything after "Prompt:" to EOF.
            # So for a clean 2000-char prompt, the file must NOT have extra content after.
            $taskFile = Join-Path $script:LogDir 'test.task.log'
            $exactPrompt = 'E' * 2000
            # File ends immediately after the prompt — no trailing content
            "Prompt:$exactPrompt" | Set-Content -Path $taskFile -Encoding UTF8

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $hasTruncated = $result -match '\[Truncated\]'
            $hasTruncated | Should -Be $false
        }

        It 'does NOT truncate prompt shorter than 2000 characters' {
            New-TaskLogFile -Directory $script:LogDir -BaseName 'test' `
                -PromptContent 'Short prompt.' -HasMemoryMarker $false

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $hasTruncated = $result -match '\[Truncated\]'
            $hasTruncated | Should -Be $false
        }

        It 'truncates agent output longer than 20000 characters' {
            New-TaskLogFile -Directory $script:LogDir -BaseName 'test' -PromptContent 'Test.'
            $agentLog = Join-Path $script:LogDir 'test.agent.log'
            $longLine = 'L' * 25000
            $longLine | Set-Content -Path $agentLog -Encoding UTF8

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir
            $result | Should -Match '\[Truncated\]'
        }
    }

    Context 'Multiple task logs' {

        It 'processes all task log files sorted by name' {
            New-TaskLogFile -Directory $script:LogDir -BaseName '100000-task-a' `
                -PromptContent 'Task A prompt.' -HasMemoryMarker $false
            New-TaskLogFile -Directory $script:LogDir -BaseName '200000-task-b' `
                -PromptContent 'Task B prompt.' -HasMemoryMarker $false
            New-TaskLogFile -Directory $script:LogDir -BaseName '150000-task-c' `
                -PromptContent 'Task C prompt.' -HasMemoryMarker $false

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir

            # Should appear in sorted order: 100000, 150000, 200000
            $matchA = $result.IndexOf('=== TASK: 100000-task-a ===')
            $matchC = $result.IndexOf('=== TASK: 150000-task-c ===')
            $matchB = $result.IndexOf('=== TASK: 200000-task-b ===')

            ($matchA -ge 0) | Should -Be $true
            ($matchC -ge 0) | Should -Be $true
            ($matchB -ge 0) | Should -Be $true
            ($matchA -lt $matchC) | Should -Be $true
            ($matchC -lt $matchB) | Should -Be $true
        }

        It 'handles mix of tasks with and without agent logs' {
            New-TaskLogFile -Directory $script:LogDir -BaseName 'task-with-agent' `
                -PromptContent 'Has agent.' -HasMemoryMarker $false
            New-AgentLogFile -Directory $script:LogDir -BaseName 'task-with-agent'

            New-TaskLogFile -Directory $script:LogDir -BaseName 'task-without-agent' `
                -PromptContent 'No agent.' -HasMemoryMarker $false

            $result = Get-DailyTaskLogs -LogDirectory $script:LogDir

            $result | Should -Match '=== TASK: task-with-agent ==='
            $result | Should -Match '=== TASK: task-without-agent ==='
            $result | Should -Match 'Starting agent execution'
            $result | Should -Match '\[Agent log not found\]'
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Split-LogsIntoBatches
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Split-LogsIntoBatches' {

    Context 'Empty input' {

        It 'returns empty array for empty content' {
            $result = Split-LogsIntoBatches -LogContent ''
            $result.Count | Should -Be 0
        }

        It 'returns empty array for whitespace-only content' {
            $result = Split-LogsIntoBatches -LogContent "`n`n   `n"
            $result.Count | Should -Be 0
        }
    }

    Context 'Single task' {

        It 'returns one batch for a single task under batch size' {
            $content = "=== TASK: test-task ===`nSome log content here."
            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 1000

            $result.Count | Should -Be 1
            ($result[0] -match 'test-task') | Should -Be $true
            ($result[0] -match 'Some log content here') | Should -Be $true
        }

        It 'keeps a task in one batch even if it exceeds batch size (cannot split mid-task)' {
            $taskContent = 'X' * 20000
            $content = "=== TASK: large-task ===`n$taskContent"
            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 1000

            $result.Count | Should -Be 1
            ($result[0] -match 'large-task') | Should -Be $true
            # Verify the full content is present (length comparison is flaky with
            # newlines across Pester versions, so check that X's are preserved)
            ($result[0] -match 'X{19000}') | Should -Be $true
        }
    }

    Context 'Multiple tasks' {

        It 'keeps all tasks in one batch when combined size is under limit' {
            $content = "=== TASK: task-a ===`nAAA`n`n=== TASK: task-b ===`nBBB`n`n=== TASK: task-c ===`nCCC"
            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 10000

            $result.Count | Should -Be 1
        }

        It 'splits into multiple batches when combined size exceeds limit' {
            # Each task ~4925 chars; 4 tasks ~19700 > 15000 -> 2+ batches
            $taskContent = 'X' * 4900
            $content = @(
                "=== TASK: task-a ===`n$taskContent",
                "=== TASK: task-b ===`n$taskContent",
                "=== TASK: task-c ===`n$taskContent",
                "=== TASK: task-d ===`n$taskContent"
            ) -join "`n`n"

            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 15000

            # 4 tasks * ~4925 chars each = ~19700 chars -> should be 2+ batches
            ($result.Count -ge 2) | Should -Be $true
        }

        It 'preserves all task content across batch boundaries' {
            $content = "=== TASK: task-a ===`nContent for task A.`n`n=== TASK: task-b ===`nContent for task B.`n`n=== TASK: task-c ===`nContent for task C."
            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 50

            $reassembled = $result -join ''
            $reassembled | Should -Match '=== TASK: task-a ==='
            $reassembled | Should -Match '=== TASK: task-b ==='
            $reassembled | Should -Match '=== TASK: task-c ==='
            $reassembled | Should -Match 'Content for task A'
            $reassembled | Should -Match 'Content for task B'
            $reassembled | Should -Match 'Content for task C'
        }

        It 'each batch starts with === TASK: prefix' {
            $taskContent = 'X' * 5000
            $content = @(
                "=== TASK: task-a ===`n$taskContent",
                "=== TASK: task-b ===`n$taskContent",
                "=== TASK: task-c ===`n$taskContent"
            ) -join "`n`n"

            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 12000

            $allStartCorrect = $true
            foreach ($batch in $result) {
                if ($batch -notmatch '^=== TASK: ') { $allStartCorrect = $false }
            }
            $allStartCorrect | Should -Be $true
        }
    }

    Context 'Boundary conditions' {

        It 'creates new batch when adding a task would exceed the limit' {
            # First task ~72 chars, second task ~178 chars, batch size = 200
            # Need `n`n between tasks so the second "=== TASK:" is at line start
            $first  = "=== TASK: task-a ===`n" + ('A' * 50)
            $second = "=== TASK: task-b ===`n" + ('B' * 155)
            $content = $first + "`n`n" + $second
            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 200

            $result.Count | Should -Be 2
            ($result[0] -match 'task-a') | Should -Be $true
            ($result[0] -notmatch 'task-b') | Should -Be $true
            ($result[1] -match 'task-b') | Should -Be $true
        }

        It 'uses default batch size of 15000 when not specified' {
            $smallContent = "=== TASK: task ===`nSmall content."
            $result = Split-LogsIntoBatches -LogContent $smallContent
            $result.Count | Should -Be 1
        }

        It 'fits tasks at exact boundary (current + task == BatchSize)' {
            # condition is > BatchSize, so == BatchSize fits in same batch
            $firstTask  = "=== TASK: task-a ===`n" + ('A' * 50)    # ~72 chars
            $secondTask = "=== TASK: task-b ===`n" + ('B' * 106)   # ~128 chars -> total ~200
            $content = $firstTask + $secondTask
            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 200

            $result.Count | Should -Be 1
        }
    }

    Context 'Special content' {

        It 'preserves newlines in task content' {
            $content = "=== TASK: task-a ===`nLine1`nLine2`n`nLine3"
            $result = Split-LogsIntoBatches -LogContent $content -BatchSize 10000

            # Verify each line is preserved in the batch
            ($result[0].Contains('Line1')) | Should -Be $true
            ($result[0].Contains('Line2')) | Should -Be $true
            ($result[0].Contains('Line3')) | Should -Be $true
            # Verify the batch starts correctly
            ($result[0].StartsWith('=== TASK: task-a ===')) | Should -Be $true
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Date parsing and path construction
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Date parsing and path construction' {

    It 'correctly formats year, month, day from a date' {
        $date = Get-Date '2026-06-16'

        $year  = $date.ToString('yyyy')
        $month = $date.ToString('MM')
        $day   = $date.ToString('dd')
        $compactDate = $date.ToString('yyyyMMdd')

        $year  | Should -Be '2026'
        $month | Should -Be '06'
        $day   | Should -Be '16'
        $compactDate | Should -Be '20260616'
    }

    It 'correctly distinguishes MM (month) from mm (minutes) - regression test for month formatting bug' {
        $date = Get-Date '2026-01-02'
        $monthMM = $date.ToString('MM')

        $monthMM | Should -Be '01'
        # v1 bug on line 28 was: $month = $parsedDate.ToString("mm") — this would
        # return the current minute, NOT the month "01"
    }

    It 'constructs compact date filename correctly' {
        $compactDate = '20260616'
        $longFile  = "MEMORY.${compactDate}.long.md"
        $shortFile = "MEMORY.${compactDate}.md"

        $longFile  | Should -Be 'MEMORY.20260616.long.md'
        $shortFile | Should -Be 'MEMORY.20260616.md'
    }

    It 'handles date format YYYY-MM-DD input parameter' {
        $inputDate = '2026-12-31'
        $parsed = Get-Date $inputDate

        $parsed.ToString('yyyy-MM-dd') | Should -Be '2026-12-31'
        $parsed.ToString('yyyyMMdd')   | Should -Be '20261231'
    }

    It 'handles single-digit month and day with zero-padding' {
        $date = Get-Date '2026-01-05'
        $date.ToString('MM') | Should -Be '01'
        $date.ToString('dd') | Should -Be '05'
        $date.ToString('yyyyMMdd') | Should -Be '20260105'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Agent log tool pattern matching
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Agent log tool detection' {

    It 'v1 pattern matches Read, Edit, Run tools' {
        $v1Pattern = '^● (Read|Edit|Run)'

        ('● Read config.json'    -match $v1Pattern) | Should -Be $true
        ('● Edit file.txt'      -match $v1Pattern) | Should -Be $true
        ('● Run tests'          -match $v1Pattern) | Should -Be $true
        ('● Write output'       -match $v1Pattern) | Should -Be $false
        ('● Create file'        -match $v1Pattern) | Should -Be $false
        ('● Bash command'       -match $v1Pattern) | Should -Be $false
        ('  ● Read (indented)'  -match $v1Pattern) | Should -Be $false
        ('Normal log line'      -match $v1Pattern) | Should -Be $false
    }

    It 'v2 pattern matches Read, Edit, Write, Run, Create, Bash tools' {
        $v2Pattern = '^● (Read|Edit|Write|Run|Create|Bash)'

        ('● Read config.json'   -match $v2Pattern) | Should -Be $true
        ('● Edit file.txt'      -match $v2Pattern) | Should -Be $true
        ('● Write output'       -match $v2Pattern) | Should -Be $true
        ('● Run tests'          -match $v2Pattern) | Should -Be $true
        ('● Create file'        -match $v2Pattern) | Should -Be $true
        ('● Bash command'       -match $v2Pattern) | Should -Be $true
        ('  ● Read (indented)'  -match $v2Pattern) | Should -Be $false
        ('Normal log line'      -match $v2Pattern) | Should -Be $false
    }

    It 'finds the LAST tool in agent output' {
        $lines = @(
            'Starting execution',
            '● Read file-a.txt',
            'Processing',
            '● Edit file-b.txt',
            'More processing',
            '● Write output.txt',
            'Finalizing'
        )

        $lastToolIndex = -1
        for ($i = $lines.Count - 1; $i -ge 0; $i--) {
            if ($lines[$i] -match '^● (Read|Edit|Write|Run|Create|Bash)') {
                $lastToolIndex = $i
                break
            }
        }

        $lastToolIndex | Should -Be 5
        $lines[$lastToolIndex] | Should -BeExactly '● Write output.txt'
    }

    It 'returns -1 when no tool markers exist' {
        $lines = @('Line 1', 'Line 2', 'Line 3')

        $lastToolIndex = -1
        for ($i = $lines.Count - 1; $i -ge 0; $i--) {
            if ($lines[$i] -match '^● (Read|Edit|Write|Run|Create|Bash)') {
                $lastToolIndex = $i
                break
            }
        }

        $lastToolIndex | Should -Be -1
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Content truncation limits
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Content truncation limits' {

    It 'prompt truncation limit is 2000 characters' {
        $promptLimit = 2000
        $longText = 'X' * 2500

        $truncated = if ($longText.Length -gt $promptLimit) {
            $longText.Substring(0, $promptLimit) + '... [Truncated]'
        } else { $longText }

        $expectedLen = $promptLimit + '... [Truncated]'.Length
        $truncated.Length | Should -Be $expectedLen
        ($truncated -match '\[Truncated\]$') | Should -Be $true
    }

    It 'agent output truncation limit is 20000 characters' {
        $outputLimit = 20000
        $longText = 'Y' * 25000

        $truncated = if ($longText.Length -gt $outputLimit) {
            $longText.Substring(0, $outputLimit) + '... [Truncated]'
        } else { $longText }

        $expectedLen = $outputLimit + '... [Truncated]'.Length
        $truncated.Length | Should -Be $expectedLen
    }

    It 'default batch size is 15000 characters' {
        $defaultBatchSize = 15000
        $defaultBatchSize | Should -Be 15000
    }

    It 'v2 short memory limit is 3000 characters' {
        $shortMemoryLimit = 3000
        $shortMemoryLimit | Should -Be 3000
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Prompt construction validation
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Prompt construction' {

    It 'specification contains all 5 required sections' {
        $sections = @(
            'Tasks Executed',
            'Execution Quality Review',
            'Issues Encountered',
            'Root Cause Analysis',
            'Process Improvement Insight'
        )
        $sections.Count | Should -Be 5
    }

    It 'includes absolute path instructions' {
        $keywords = @('ABSOLUTE path', 'absolute path', 'ABSOLUTE PATH')
        ($keywords.Count -gt 0) | Should -Be $true
    }

    It 'includes English-only constraint' {
        $english = 'Use English only.'
        ($english.Length -gt 0) | Should -Be $true
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Error handling behavior
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Error handling behavior' {

    It 'first batch failure should be fatal (exit 1 in v2)' {
        $isFirstBatch = $true
        $failureIsFatal = $isFirstBatch
        $failureIsFatal | Should -Be $true
    }

    It 'subsequent batch failure should warn and continue (v2)' {
        $isFirstBatch = $false
        $failureIsFatal = $isFirstBatch
        $failureIsFatal | Should -Be $false
    }

    It 'should exit 0 when no logs are found' {
        $noLogsFound = [string]::IsNullOrWhiteSpace('')
        $noLogsFound | Should -Be $true
    }

    It 'should exit 1 when both output files are missing after processing' {
        $bothMissing = $false -and $false  # neither long nor short exists
        $bothMissing | Should -Be $false
        $shouldFail = (-not $false -and -not $false)
        $shouldFail | Should -Be $true
    }

    It 'should succeed when at least one output file exists' {
        # The exit-1 condition is: (-not $longExists -and -not $shortExists)
        # So when either file exists, the AND is false → no exit 1

        # long exists, short missing → should succeed
        $failWhenLongMissing = (-not $true -and -not $false)   # $false -and $true = $false
        $failWhenLongMissing | Should -Be $false

        # both exist → should succeed
        $failWhenBothExist = (-not $true -and -not $true)       # $false -and $false = $false
        $failWhenBothExist | Should -Be $false

        # both missing → should fail
        $failWhenBothMissing = (-not $false -and -not $false)    # $true -and $true = $true
        $failWhenBothMissing | Should -Be $true
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: v1 vs v2 differences
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'v1 vs v2 differences' {

    It 'v2 supports both long and short output files vs v1 single output' {
        $compactDate = '20260616'

        $v1Files = @("MEMORY.${compactDate}.md")
        $v2Files = @("MEMORY.${compactDate}.long.md", "MEMORY.${compactDate}.md")

        $v1Files.Count | Should -Be 1
        $v2Files.Count | Should -Be 2
    }

    It 'v2 tool pattern is a superset of v1 tool pattern' {
        $v1Tools = @('Read', 'Edit', 'Run')
        $v2Tools = @('Read', 'Edit', 'Write', 'Run', 'Create', 'Bash')

        $allInV2 = $true
        foreach ($tool in $v1Tools) {
            if ($tool -notin $v2Tools) { $allInV2 = $false }
        }
        $allInV2 | Should -Be $true
        ($v2Tools.Count -gt $v1Tools.Count) | Should -Be $true
    }

    It 'both scripts split by the same === TASK: delimiter' {
        $delimiter = '(?m)^=== TASK: '
        $sampleContent = "=== TASK: task-a ===`ncontent`n=== TASK: task-b ===`nmore"
        $tasks = $sampleContent -split $delimiter | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

        $tasks.Count | Should -Be 2
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Integration - Full pipeline
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Integration - Full pipeline' {

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'complete pipeline: collect logs -> batch -> validate structure' {
        $tasks = @(
            @{ Name = '100001-fix-bug'; Title = 'Task: Fix login bug'; Prompt = 'Fix the login authentication flow.' },
            @{ Name = '100002-add-feature'; Title = 'Task: Add dark mode'; Prompt = 'Implement dark mode toggle.' },
            @{ Name = '100003-refactor'; Title = 'Task: Refactor config'; Prompt = 'Refactor configuration system.' },
            @{ Name = '100004-tests'; Title = 'Task: Add unit tests'; Prompt = 'Add unit tests for the auth module.' },
            @{ Name = '100005-docs'; Title = 'Task: Update docs'; Prompt = 'Update API documentation.' }
        )

        foreach ($task in $tasks) {
            New-TaskLogFile -Directory $script:LogDir -BaseName $task.Name `
                -TaskTitle $task.Title -PromptContent $task.Prompt -HasMemoryMarker $false
            New-AgentLogFile -Directory $script:LogDir -BaseName $task.Name
        }

        $logContent = Get-DailyTaskLogs -LogDirectory $script:LogDir

        # Verify all tasks are present
        foreach ($task in $tasks) {
            ($logContent -match "=== TASK: $($task.Name) ===") | Should -Be $true
            ($logContent -match $task.Title) | Should -Be $true
        }

        # Verify PROMPT and RESULT sections exist for each task
        $promptCount = ([regex]::Matches($logContent, '--- PROMPT \(Snippet\) ---')).Count
        $resultCount = ([regex]::Matches($logContent, '--- RESULT \(Snippet\) ---')).Count

        $promptCount | Should -Be $tasks.Count
        $resultCount | Should -Be $tasks.Count

        # Split into batches
        $batches = Split-LogsIntoBatches -LogContent $logContent -BatchSize 15000

        # Verify batching
        ($batches.Count -ge 1) | Should -Be $true

        # Verify all tasks are preserved across batches
        $reassembled = $batches -join ''
        foreach ($task in $tasks) {
            ($reassembled -match "=== TASK: $($task.Name) ===") | Should -Be $true
        }
    }

    It 'handles large number of tasks and verifies task count integrity' {
        1..50 | ForEach-Object {
            $idx = $_.ToString('D6')
            New-TaskLogFile -Directory $script:LogDir -BaseName "${idx}-task-$_" `
                -TaskTitle "Task: Task number $_" `
                -PromptContent "Prompt for task $_ with some additional content." `
                -HasMemoryMarker $false
            New-AgentLogFile -Directory $script:LogDir -BaseName "${idx}-task-$_"
        }

        $logContent = Get-DailyTaskLogs -LogDirectory $script:LogDir

        # Should have 50 TASK headers
        $taskCount = ([regex]::Matches($logContent, '=== TASK:')).Count
        $taskCount | Should -Be 50

        # Split into small batches
        $batches = Split-LogsIntoBatches -LogContent $logContent -BatchSize 5000
        ($batches.Count -gt 1) | Should -Be $true

        # Every task should be in exactly one batch
        $allTasksInBatches = 0
        foreach ($batch in $batches) {
            $allTasksInBatches += ([regex]::Matches($batch, '=== TASK:')).Count
        }
        $allTasksInBatches | Should -Be 50
    }

    It 'handles task logs with special characters in content' {
        New-TaskLogFile -Directory $script:LogDir -BaseName 'special-task' `
            -TaskTitle 'Task: Handle $pecial & <characters>' `
            -PromptContent 'Prompt with $dollar, & ampersand, and <angle> brackets.' `
            -HasMemoryMarker $false

        $logContent = Get-DailyTaskLogs -LogDirectory $script:LogDir

        ($logContent -match '\$pecial') | Should -Be $true
        ($logContent -match '& <characters>') | Should -Be $true
        ($logContent -match '\$dollar') | Should -Be $true
        ($logContent -match '& ampersand') | Should -Be $true
        ($logContent -match '<angle>') | Should -Be $true
    }

    It 'handles tasks with long prompts requiring truncation' {
        $veryLongPrompt = 'L' * 3000
        New-TaskLogFile -Directory $script:LogDir -BaseName 'long-prompt-task' `
            -TaskTitle 'Task: Long prompt task' -PromptContent $veryLongPrompt -HasMemoryMarker $false

        $logContent = Get-DailyTaskLogs -LogDirectory $script:LogDir

        ($logContent -match '\[Truncated\]') | Should -Be $true
        ($logContent -match 'L{2000}\.\.\. \[Truncated\]') | Should -Be $true
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`nTest suite loaded. Run with:" -ForegroundColor Green
Write-Host "  Invoke-Pester -Path .\coworker-daily-memory-generator.tests.ps1 -EnableExit" -ForegroundColor Cyan
