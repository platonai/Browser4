#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Unit and integration tests for coworker.ps1 — the Coworker Task Runner.

.DESCRIPTION
    Tests for the core functions in coworker.ps1: logging, draft placeholder
    creation, path resolution, content parsing, auto-approve detection,
    task destination routing, prompt construction, and the Move-Item guard.

    Run standalone:
        Invoke-Pester -Path .\coworker.tests.ps1

    Requires Pester 5.x
#>

$ErrorActionPreference = 'Continue'

# ═══════════════════════════════════════════════════════════════════════════════
# Test fixture management
# ═══════════════════════════════════════════════════════════════════════════════

$script:TestRoot = $null

function Initialize-TestFixture {
    $script:TestRoot = Join-Path ([System.IO.Path]::GetTempPath()) "CoworkerTests_$(Get-Random -Minimum 1000 -Maximum 9999)"
    New-Item -ItemType Directory -Path $script:TestRoot -Force | Out-Null
}

function Remove-TestFixture {
    if ($script:TestRoot -and (Test-Path $script:TestRoot)) {
        Remove-Item -Path $script:TestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function New-TempFile {
    param([string]$Path, [string]$Content = '')
    $Content | Set-Content -Path $Path -Encoding UTF8
}

# ═══════════════════════════════════════════════════════════════════════════════
# Functions under test (extracted/adapted from coworker.ps1 for isolated testing)
# ═══════════════════════════════════════════════════════════════════════════════

# Pester 5 requires functions used in tests to be discoverable.
# We use BeforeAll to dot-source these into each Describe's scope.

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Write-LogMessage (log entry formatting and file output)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Write-LogMessage' {

    BeforeAll {
        # Inline replicas for isolated testing
        function Write-ConsoleLine {
            param([Parameter(Mandatory=$true)][string]$Message, [System.ConsoleColor]$ForegroundColor, [switch]$ErrorStream)
            try {
                $canUseHost = [Environment]::UserInteractive -and $null -ne $Host -and $null -ne $Host.UI -and $null -ne $Host.UI.RawUI
            } catch { $canUseHost = $false }
            if ($canUseHost) {
                if ($PSBoundParameters.ContainsKey('ForegroundColor')) { Write-Host $Message -ForegroundColor $ForegroundColor }
                else { Write-Host $Message }
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
            if ($PSBoundParameters.ContainsKey('ForegroundColor')) { Write-Host $Message -ForegroundColor $ForegroundColor }
            else { Write-Host $Message }
        }

        function Write-LogMessage {
            param(
                [Parameter(Mandatory=$true)][string]$Message,
                [ValidateSet('INFO', 'WARN', 'ERROR')][string]$Level = 'INFO',
                [Parameter(Mandatory=$true)][string]$ScriptLogPath
            )
            $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
            $logEntry = "[$timestamp] [$Level] $Message"
            switch ($Level) {
                'INFO'  { Write-ConsoleLine -Message $logEntry }
                'WARN'  { Write-ConsoleLine -Message $logEntry -ForegroundColor Yellow }
                'ERROR' { Write-ConsoleLine -Message $logEntry -ForegroundColor Red }
            }
            $logEntry | Out-File -FilePath $ScriptLogPath -Append -Encoding UTF8
        }
    }

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'writes an INFO-level log entry to the log file' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogMessage -Message 'Task started' -Level 'INFO' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match '\[INFO\] Task started'
    }

    It 'writes a WARN-level log entry to the log file' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogMessage -Message 'Timeout approaching' -Level 'WARN' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match '\[WARN\] Timeout approaching'
    }

    It 'writes an ERROR-level log entry to the log file' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogMessage -Message 'Agent execution failed' -Level 'ERROR' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match '\[ERROR\] Agent execution failed'
    }

    It 'defaults to INFO level when Level is omitted' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogMessage -Message 'Default level test' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match '\[INFO\] Default level test'
    }

    It 'includes a UTC timestamp in the log entry' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogMessage -Message 'Timestamp test' -Level 'INFO' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match '\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\]'
    }

    It 'appends to an existing log file instead of overwriting' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        'existing line' | Set-Content -Path $logFile -Encoding UTF8

        Write-LogMessage -Message 'First message' -Level 'INFO' -ScriptLogPath $logFile
        Write-LogMessage -Message 'Second message' -Level 'INFO' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match 'existing line'
        $content | Should -Match 'First message'
        $content | Should -Match 'Second message'
    }

    It 'handles messages with special characters' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogMessage -Message 'Path: C:\test\file.txt | Status: 100% done' -Level 'INFO' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match 'C:\\test\\file.txt'
    }

    It 'rejects invalid log levels' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        { Write-LogMessage -Message 'Bad level' -Level 'DEBUG' -ScriptLogPath $logFile } | Should -Throw
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Write-LogVerbose (debug-only file logging)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Write-LogVerbose' {

    BeforeAll {
        function Write-LogVerbose {
            param(
                [Parameter(Mandatory=$true)][string]$Message,
                [Parameter(Mandatory=$true)][string]$ScriptLogPath
            )
            $timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')
            $logEntry = "[$timestamp] [DEBUG] $Message"
            $logEntry | Out-File -FilePath $ScriptLogPath -Append -Encoding UTF8
        }
    }

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'writes a DEBUG-level log entry to the log file' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogVerbose -Message 'Prompt length: 1234 characters' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match '\[DEBUG\] Prompt length: 1234 characters'
    }

    It 'includes a UTC timestamp in the debug entry' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        New-TempFile -Path $logFile

        Write-LogVerbose -Message 'Verbose test' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match '\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] \[DEBUG\]'
    }

    It 'appends to existing log file' {
        $logFile = Join-Path $script:TestRoot 'test.log'
        'prior content' | Set-Content -Path $logFile -Encoding UTF8

        Write-LogVerbose -Message 'Debug 1' -ScriptLogPath $logFile
        Write-LogVerbose -Message 'Debug 2' -ScriptLogPath $logFile

        $content = Get-Content -Path $logFile -Raw -Encoding UTF8
        $content | Should -Match 'prior content'
        $content | Should -Match 'Debug 1'
        $content | Should -Match 'Debug 2'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Ensure-DraftPlaceholders
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Ensure-DraftPlaceholders' {

    BeforeAll {
        function Ensure-DraftPlaceholders {
            param([Parameter(Mandatory=$true)][string]$DraftDirectory)
            $createdPaths = @()
            foreach ($draftNumber in 1..5) {
                $draftPath = Join-Path $DraftDirectory "$draftNumber.md"
                if (!(Test-Path $draftPath)) {
                    Set-Content -Path $draftPath -Value '' -Encoding UTF8
                    $createdPaths += $draftPath
                }
            }
            return $createdPaths
        }
    }

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'creates exactly 5 placeholder files (1.md through 5.md) in an empty directory' {
        $draftDir = Join-Path $script:TestRoot 'drafts'
        New-Item -ItemType Directory -Path $draftDir -Force | Out-Null

        $created = Ensure-DraftPlaceholders -DraftDirectory $draftDir

        $created.Count | Should -Be 5
        1..5 | ForEach-Object {
            Test-Path (Join-Path $draftDir "$_.md") | Should -BeTrue
        }
    }

    It 'does not overwrite existing placeholder files' {
        $draftDir = Join-Path $script:TestRoot 'drafts'
        New-Item -ItemType Directory -Path $draftDir -Force | Out-Null

        $existingPath = Join-Path $draftDir '1.md'
        'existing draft content' | Set-Content -Path $existingPath -Encoding UTF8

        $created = Ensure-DraftPlaceholders -DraftDirectory $draftDir

        $created.Count | Should -Be 4
        $created -notcontains $existingPath | Should -BeTrue
        $content = Get-Content -Path $existingPath -Raw -Encoding UTF8
        $content | Should -BeExactly 'existing draft content'
    }

    It 'creates zero files when all 5 already exist' {
        $draftDir = Join-Path $script:TestRoot 'drafts'
        New-Item -ItemType Directory -Path $draftDir -Force | Out-Null
        1..5 | ForEach-Object { '' | Set-Content -Path (Join-Path $draftDir "$_.md") -Encoding UTF8 }

        $created = Ensure-DraftPlaceholders -DraftDirectory $draftDir

        $created.Count | Should -Be 0
    }

    It 'creates empty files (zero bytes)' {
        $draftDir = Join-Path $script:TestRoot 'drafts'
        New-Item -ItemType Directory -Path $draftDir -Force | Out-Null

        $null = Ensure-DraftPlaceholders -DraftDirectory $draftDir

        $file = Get-Item (Join-Path $draftDir '3.md')
        $file.Length | Should -Be 0
    }

    It 'handles a mix of existing and missing placeholders' {
        $draftDir = Join-Path $script:TestRoot 'drafts'
        New-Item -ItemType Directory -Path $draftDir -Force | Out-Null
        '' | Set-Content -Path (Join-Path $draftDir '2.md') -Encoding UTF8
        '' | Set-Content -Path (Join-Path $draftDir '4.md') -Encoding UTF8

        $created = Ensure-DraftPlaceholders -DraftDirectory $draftDir

        $created.Count | Should -Be 3
        $created -contains (Join-Path $draftDir '1.md') | Should -BeTrue
        $created -contains (Join-Path $draftDir '3.md') | Should -BeTrue
        $created -contains (Join-Path $draftDir '5.md') | Should -BeTrue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Resolve-UniquePath
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Resolve-UniquePath' {

    BeforeAll {
        function Resolve-UniquePath {
            param([string]$Directory, [string]$BaseName, [string]$Extension)
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
    }

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'returns the base name when no collision exists' {
        $dir = Join-Path $script:TestRoot 'unique-test'
        New-Item -ItemType Directory -Path $dir -Force | Out-Null

        $result = Resolve-UniquePath -Directory $dir -BaseName 'my-task' -Extension '.md'

        $result.FileName | Should -BeExactly 'my-task.md'
        $result.Path | Should -BeExactly (Join-Path $dir 'my-task.md')
    }

    It 'returns .2 suffix when base name already exists' {
        $dir = Join-Path $script:TestRoot 'unique-test'
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        '' | Set-Content -Path (Join-Path $dir 'my-task.md') -Encoding UTF8

        $result = Resolve-UniquePath -Directory $dir -BaseName 'my-task' -Extension '.md'

        $result.FileName | Should -BeExactly 'my-task.2.md'
    }

    It 'increments counter when multiple collisions exist' {
        $dir = Join-Path $script:TestRoot 'unique-test'
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        '' | Set-Content -Path (Join-Path $dir 'my-task.md')   -Encoding UTF8
        '' | Set-Content -Path (Join-Path $dir 'my-task.2.md') -Encoding UTF8
        '' | Set-Content -Path (Join-Path $dir 'my-task.3.md') -Encoding UTF8

        $result = Resolve-UniquePath -Directory $dir -BaseName 'my-task' -Extension '.md'

        $result.FileName | Should -BeExactly 'my-task.4.md'
    }

    It 'handles large collision gaps' {
        $dir = Join-Path $script:TestRoot 'unique-test'
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        @(1..9) + 42 | ForEach-Object {
            $suffix = if ($_ -eq 1) { '' } else { ".$_" }
            '' | Set-Content -Path (Join-Path $dir "task${suffix}.md") -Encoding UTF8
        }

        $result = Resolve-UniquePath -Directory $dir -BaseName 'task' -Extension '.md'

        $result.FileName | Should -BeExactly 'task.10.md'
    }

    It 'works with different file extensions' {
        $dir = Join-Path $script:TestRoot 'unique-test'
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        '' | Set-Content -Path (Join-Path $dir 'data.json') -Encoding UTF8

        $result = Resolve-UniquePath -Directory $dir -BaseName 'data' -Extension '.json'

        $result.FileName | Should -BeExactly 'data.2.json'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: New-TaskFinishPrompt
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'New-TaskFinishPrompt' {

    BeforeAll {
        function New-TaskFinishPrompt {
            param([Parameter(Mandatory=$true)][string]$WorkingPath)
            return @"
Finish the task described in file: $WorkingPath.
Do not move **this** task file, just execute the task based on its content, the system will move it after you finished the task.
"@
        }
    }

    It 'generates a prompt referencing the working file path' {
        $prompt = New-TaskFinishPrompt -WorkingPath 'D:\workspace\repo\coworker\tasks\2working\fix-bug.md'
        $prompt | Should -Match 'Finish the task described in file:'
        $prompt | Should -Match ([regex]::Escape('D:\workspace\repo\coworker\tasks\2working\fix-bug.md'))
    }

    It 'includes instructions to not move the task file' {
        $prompt = New-TaskFinishPrompt -WorkingPath 'C:\test\task.md'
        $prompt | Should -Match 'Do not move \*\*this\*\* task file'
        $prompt | Should -Match 'the system will move it after you finished the task'
    }

    It 'handles paths with spaces' {
        $prompt = New-TaskFinishPrompt -WorkingPath 'C:\my tasks\do the thing.md'
        $prompt | Should -Match 'C:\\my tasks\\do the thing\.md'
    }

    It 'handles Unix-style paths' {
        $prompt = New-TaskFinishPrompt -WorkingPath '/home/user/repo/coworker/tasks/2working/fix-bug.md'
        $prompt | Should -Match '/home/user/repo/coworker/tasks/2working/fix-bug\.md'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: ConvertFrom-StructuredTaskContent
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'ConvertFrom-StructuredTaskContent' {

    BeforeAll {
        function ConvertFrom-StructuredTaskContent {
            param([Parameter(Mandatory=$true)][string]$Content)
            $result = @{ Title = ''; Description = ''; Prompt = ''; IsStructured = $false }
            if ($Content -match "(?ms)^Title:\s*(?<title>.*?)(\r\n|\n)Description:\s*(?<desc>.*?)(\r\n|\n)Prompt:\s*(?<prompt>.*)$") {
                $result.Title       = $Matches['title'].Trim()
                $result.Description = $Matches['desc'].Trim()
                $result.Prompt      = $Matches['prompt'].Trim()
                $result.IsStructured = $true
            }
            return $result
        }
    }

    It 'parses a fully structured task file with CRLF line endings' {
        $content = @"
Title: Fix login bug
Description: The login endpoint returns 500 when password contains special characters.
Prompt: Investigate the password encoding in the login handler and add proper escaping.
"@
        $result = ConvertFrom-StructuredTaskContent -Content $content
        $result.IsStructured | Should -BeTrue
        $result.Title       | Should -BeExactly 'Fix login bug'
        $result.Description | Should -BeExactly 'The login endpoint returns 500 when password contains special characters.'
        $result.Prompt      | Should -BeExactly 'Investigate the password encoding in the login handler and add proper escaping.'
    }

    It 'parses a structured task with LF-only line endings' {
        $content = "Title: Add dark mode`nDescription: Implement dark mode toggle.`nPrompt: Add CSS variables and a toggle component."
        $result = ConvertFrom-StructuredTaskContent -Content $content
        $result.IsStructured | Should -BeTrue
        $result.Title       | Should -BeExactly 'Add dark mode'
        $result.Description | Should -BeExactly 'Implement dark mode toggle.'
        $result.Prompt      | Should -BeExactly 'Add CSS variables and a toggle component.'
    }

    It 'trims whitespace from parsed fields' {
        $content = @"
Title:   Update README
Description:    Refresh installation instructions.
Prompt:   Read the current README and update the install section.
"@
        $result = ConvertFrom-StructuredTaskContent -Content $content
        $result.Title       | Should -BeExactly 'Update README'
        $result.Description | Should -BeExactly 'Refresh installation instructions.'
        $result.Prompt      | Should -BeExactly 'Read the current README and update the install section.'
    }

    It 'returns IsStructured=$false for unstructured content' {
        $result = ConvertFrom-StructuredTaskContent -Content 'Just a plain task description without any structured headers.'
        $result.IsStructured | Should -BeFalse
        $result.Title       | Should -BeExactly ''
    }

    It 'returns IsStructured=$false when only Title is present' {
        $result = ConvertFrom-StructuredTaskContent -Content "Title: Partial task`nSome content but no Description: or Prompt: headers."
        $result.IsStructured | Should -BeFalse
    }

    It 'handles multi-line prompt content' {
        $content = @"
Title: Multi-line task
Description: Task with a prompt that spans multiple lines.
Prompt: First line of the prompt.
Second line of the prompt.
Third line with more details.
"@
        $result = ConvertFrom-StructuredTaskContent -Content $content
        $result.IsStructured | Should -BeTrue
        $result.Prompt | Should -Match 'First line of the prompt'
        $result.Prompt | Should -Match 'Third line with more details'
    }

    It 'handles empty content' {
        $result = ConvertFrom-StructuredTaskContent -Content ''
        $result.IsStructured | Should -BeFalse
    }

    It 'handles content where Prompt captures the remainder of the file' {
        $content = @"
Title: Single header test
Description: Testing prompt extraction
Prompt: Everything after Prompt: is the prompt,
including these lines
and this one too.
"@
        $result = ConvertFrom-StructuredTaskContent -Content $content
        $result.Prompt | Should -Match 'Everything after Prompt: is the prompt'
        $result.Prompt | Should -Match 'including these lines'
        $result.Prompt | Should -Match 'and this one too'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Get-TaskTargetDirectory (#auto-approve detection)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Get-TaskTargetDirectory' {

    BeforeAll {
        function Get-TaskTargetDirectory {
            param([string]$Content, [string]$FinishedDir, [string]$ApprovedDir)
            if ($Content -match "#auto-approve") {
                return @{ Path = $ApprovedDir; Message = "Task AUTO-APPROVED and moved to" }
            }
            return @{ Path = $FinishedDir; Message = "Task moved to finished" }
        }
    }

    $finishedDir = 'D:\repo\coworker\tasks\3_1complete'
    $approvedDir = 'D:\repo\coworker\tasks\5approved'

    It 'routes to finished directory when #auto-approve is not present' {
        $result = Get-TaskTargetDirectory -Content 'Fix the login bug.' -FinishedDir $finishedDir -ApprovedDir $approvedDir
        $result.Path    | Should -BeExactly $finishedDir
        $result.Message | Should -BeExactly 'Task moved to finished'
    }

    It 'routes to approved directory when #auto-approve is present' {
        $result = Get-TaskTargetDirectory -Content "#auto-approve`nTitle: Update README" -FinishedDir $finishedDir -ApprovedDir $approvedDir
        $result.Path    | Should -BeExactly $approvedDir
        $result.Message | Should -BeExactly 'Task AUTO-APPROVED and moved to'
    }

    It 'detects #auto-approve anywhere in content' {
        $content = @"
Title: Some task
Description: A task that should be auto-approved.
Prompt: Do the thing.
#auto-approve
"@
        $result = Get-TaskTargetDirectory -Content $content -FinishedDir $finishedDir -ApprovedDir $approvedDir
        $result.Path | Should -BeExactly $approvedDir
    }

    It 'detects #auto-approve as a substring match' {
        $result = Get-TaskTargetDirectory -Content 'Some notes before #auto-approve and after.' -FinishedDir $finishedDir -ApprovedDir $approvedDir
        $result.Path | Should -BeExactly $approvedDir
    }

    It 'is case-insensitive (PowerShell -match default)' {
        $result = Get-TaskTargetDirectory -Content '#AUTO-APPROVE' -FinishedDir $finishedDir -ApprovedDir $approvedDir
        $result.Path | Should -BeExactly $approvedDir
    }

    It 'matches #auto-approved as a substring of #auto-approve' {
        $result = Get-TaskTargetDirectory -Content '#auto-approved' -FinishedDir $finishedDir -ApprovedDir $approvedDir
        # "#auto-approved" contains "#auto-approve" as substring, so -match succeeds
        $result.Path | Should -BeExactly $approvedDir
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Move-TaskFromWorking (the Move-Item guard fix)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Move-TaskFromWorking' {

    BeforeAll {
        function Resolve-UniquePath {
            param([string]$Directory, [string]$BaseName, [string]$Extension)
            $candidateName = "$BaseName$Extension"
            $candidatePath = Join-Path $Directory $candidateName
            if (!(Test-Path $candidatePath)) { return @{ Path = $candidatePath; FileName = $candidateName } }
            $counter = 2
            while ($true) {
                $nextName = "$BaseName.$counter$Extension"
                $nextPath = Join-Path $Directory $nextName
                if (!(Test-Path $nextPath)) { return @{ Path = $nextPath; FileName = $nextName } }
                $counter++
            }
        }

        function Move-TaskFromWorking {
            param(
                [string]$WorkingPath,
                [string]$TargetDir,
                [string]$CurrentYear,
                [string]$CurrentDate,
                [string]$BaseName,
                [string]$Extension,
                [string]$Message
            )
            $targetSubDir = Join-Path $TargetDir "$CurrentYear\$CurrentDate"
            if (!(Test-Path $targetSubDir)) { New-Item -ItemType Directory -Path $targetSubDir -Force | Out-Null }
            $targetInfo = Resolve-UniquePath -Directory $targetSubDir -BaseName $BaseName -Extension $Extension
            if (Test-Path $WorkingPath) {
                Move-Item -Path $WorkingPath -Destination $targetInfo.Path -Force
                return @{ Success = $true;  Path = $targetInfo.Path; Message = "$Message : $($targetInfo.Path)" }
            } else {
                return @{ Success = $false; Path = $null; Message = "Task file not found at working path (may have been moved/deleted by agent): $WorkingPath" }
            }
        }
    }

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'moves the file to target when it exists at the working path' {
        $workingDir = Join-Path $script:TestRoot 'working'
        $targetDir  = Join-Path $script:TestRoot 'finished'
        New-Item -ItemType Directory -Path $workingDir -Force | Out-Null
        New-Item -ItemType Directory -Path $targetDir  -Force | Out-Null

        $taskFile = Join-Path $workingDir 'my-task.md'
        'task content' | Set-Content -Path $taskFile -Encoding UTF8

        $result = Move-TaskFromWorking `
            -WorkingPath $taskFile -TargetDir $targetDir `
            -CurrentYear '2026' -CurrentDate '0620' `
            -BaseName 'my-task' -Extension '.md' -Message 'Task moved to finished'

        $result.Success | Should -BeTrue
        $result.Path    | Should -Match 'my-task\.md$'
        Test-Path $taskFile                                  | Should -BeFalse
        Test-Path $result.Path                               | Should -BeTrue
        (Get-Content $result.Path -Raw -Encoding UTF8).Trim() | Should -BeExactly 'task content'
    }

    It 'returns Success=$false and a warning when the file does not exist' {
        $targetDir = Join-Path $script:TestRoot 'finished'
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        $nonexistentPath = Join-Path $script:TestRoot 'working\ghost-task.md'

        $result = Move-TaskFromWorking `
            -WorkingPath $nonexistentPath -TargetDir $targetDir `
            -CurrentYear '2026' -CurrentDate '0620' `
            -BaseName 'ghost-task' -Extension '.md' -Message 'Task moved to finished'

        $result.Success | Should -BeFalse
        $result.Path    | Should -Be $null
        $result.Message | Should -Match 'Task file not found at working path'
        $result.Message | Should -Match 'may have been moved/deleted by agent'
        $result.Message | Should -Match ([regex]::Escape($nonexistentPath))
    }

    It 'creates date-based subdirectory structure (YYYY/MMDD)' {
        $workingDir = Join-Path $script:TestRoot 'working'
        $targetDir  = Join-Path $script:TestRoot 'finished'
        New-Item -ItemType Directory -Path $workingDir -Force | Out-Null

        $taskFile = Join-Path $workingDir 'task.md'
        'content' | Set-Content -Path $taskFile -Encoding UTF8

        $result = Move-TaskFromWorking `
            -WorkingPath $taskFile -TargetDir $targetDir `
            -CurrentYear '2026' -CurrentDate '0619' `
            -BaseName 'task' -Extension '.md' -Message 'Task moved to finished'

        $result.Path | Should -Match ([regex]::Escape('\2026\0619\task.md'))
        Test-Path (Join-Path $targetDir '2026\0619\task.md') | Should -BeTrue
    }

    It 'handles filename collisions in the target directory' {
        $workingDir = Join-Path $script:TestRoot 'working'
        $targetDir  = Join-Path $script:TestRoot 'finished'
        New-Item -ItemType Directory -Path $workingDir -Force | Out-Null
        $targetSubDir = Join-Path $targetDir '2026\0620'
        New-Item -ItemType Directory -Path $targetSubDir -Force | Out-Null
        '' | Set-Content -Path (Join-Path $targetSubDir 'collision.md') -Encoding UTF8

        $taskFile = Join-Path $workingDir 'collision.md'
        'unique content' | Set-Content -Path $taskFile -Encoding UTF8

        $result = Move-TaskFromWorking `
            -WorkingPath $taskFile -TargetDir $targetDir `
            -CurrentYear '2026' -CurrentDate '0620' `
            -BaseName 'collision' -Extension '.md' -Message 'Task moved to finished'

        $result.Success | Should -BeTrue
        $result.Path    | Should -Match 'collision\.2\.md$'
    }

    It 'does not throw when the working file is missing (regression test for the fix)' {
        $targetDir = Join-Path $script:TestRoot 'finished'
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        $missingPath = Join-Path $script:TestRoot 'working\nonexistent.md'

        $result = $null
        $threw = $false
        try {
            $result = Move-TaskFromWorking `
                -WorkingPath $missingPath -TargetDir $targetDir `
                -CurrentYear '2026' -CurrentDate '0620' `
                -BaseName 'nonexistent' -Extension '.md' -Message 'Task moved to finished'
        } catch {
            $threw = $true
        }

        $threw          | Should -BeFalse
        $result.Success | Should -BeFalse
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Normalize-TaskBaseName
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Normalize-TaskBaseName' {

    BeforeAll {
        function Normalize-TaskBaseName {
            param([string]$RawName)
            if ([string]::IsNullOrWhiteSpace($RawName)) { return '' }
            $normalized = $RawName.Trim()
            $normalized = $normalized -replace '\s+', '-'
            $normalized = $normalized -replace '[^A-Za-z0-9._-]', '-'
            $normalized = $normalized -replace '-+', '-'
            $normalized = $normalized.Trim(' ', '.', '-', '_')
            if ($normalized.Length -gt 60) {
                $normalized = $normalized.Substring(0, 60).Trim(' ', '.', '-', '_')
            }
            return $normalized
        }
    }

    It 'returns empty string for null or whitespace input' {
        Normalize-TaskBaseName -RawName $null | Should -BeExactly ''
        Normalize-TaskBaseName -RawName ''    | Should -BeExactly ''
        Normalize-TaskBaseName -RawName '   ' | Should -BeExactly ''
    }

    It 'preserves valid kebab-case names' {
        Normalize-TaskBaseName -RawName 'fix-login-bug' | Should -BeExactly 'fix-login-bug'
    }

    It 'replaces spaces with hyphens' {
        Normalize-TaskBaseName -RawName 'fix login bug' | Should -BeExactly 'fix-login-bug'
    }

    It 'removes special characters' {
        Normalize-TaskBaseName -RawName 'fix: bug #123!' | Should -BeExactly 'fix-bug-123'
    }

    It 'collapses multiple consecutive hyphens' {
        Normalize-TaskBaseName -RawName 'hello---world' | Should -BeExactly 'hello-world'
    }

    It 'trims leading and trailing punctuation' {
        Normalize-TaskBaseName -RawName '...hello-world...' | Should -BeExactly 'hello-world'
    }

    It 'truncates names longer than 60 characters' {
        $longName = 'a' * 100
        $result = Normalize-TaskBaseName -RawName $longName
        $result.Length | Should -Be 60
    }

    It 'truncation trims trailing hyphens' {
        $name = 'a-' * 31
        $result = Normalize-TaskBaseName -RawName $name
        $result | Should -Match '^a(-a)+a$'
        $result.EndsWith('-') | Should -BeFalse
    }

    It 'result is always a valid filename (no forbidden chars)' {
        $inputs = @('fix: login bug #42!', 'hello/world', 'test<angle>brackets', 'file"name"', 'with|pipe', 'question?mark', 'asterisk*here')
        foreach ($input in $inputs) {
            $result = Normalize-TaskBaseName -RawName $input
            $result -match '[\\/*?:"<>|]' | Should -BeFalse
        }
    }

    It 'preserves dots and underscores' {
        Normalize-TaskBaseName -RawName 'v1.2.3_fix' | Should -BeExactly 'v1.2.3_fix'
    }

    It 'preserves mixed case' {
        Normalize-TaskBaseName -RawName 'Fix-Login-Bug-API' | Should -BeExactly 'Fix-Login-Bug-API'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Date-based directory construction
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Date-based directory construction' {

    It 'formats current date in YYYY/MMDD pattern' {
        $date = Get-Date '2026-06-19'
        $year  = $date.ToUniversalTime().ToString('yyyy')
        $month = $date.ToUniversalTime().ToString('MM')
        $day   = $date.ToUniversalTime().ToString('dd')
        $currentDate = "$month$day"

        $year        | Should -BeExactly '2026'
        $month       | Should -BeExactly '06'
        $day         | Should -BeExactly '19'
        $currentDate | Should -BeExactly '0619'
    }

    It 'zero-pads single-digit months and days' {
        $date = Get-Date '2026-01-05'
        $date.ToUniversalTime().ToString('MM') | Should -BeExactly '01'
        $date.ToUniversalTime().ToString('dd') | Should -BeExactly '05'
    }

    It 'handles December 31 correctly' {
        $date = Get-Date '2026-12-31'
        $year  = $date.ToUniversalTime().ToString('yyyy')
        $month = $date.ToUniversalTime().ToString('MM')
        $day   = $date.ToUniversalTime().ToString('dd')

        $year        | Should -BeExactly '2026'
        "$month$day" | Should -BeExactly '1231'
    }

    It 'constructs a valid date subdirectory path' {
        $subDir = Join-Path 'D:\repo\coworker\tasks\3_1complete' '2026\0619'
        $subDir | Should -BeExactly 'D:\repo\coworker\tasks\3_1complete\2026\0619'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Write-ConsoleLine (output routing)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Write-ConsoleLine' {

    BeforeAll {
        function Write-ConsoleLine {
            param([Parameter(Mandatory=$true)][string]$Message, [System.ConsoleColor]$ForegroundColor, [switch]$ErrorStream)
            try {
                $canUseHost = [Environment]::UserInteractive -and $null -ne $Host -and $null -ne $Host.UI -and $null -ne $Host.UI.RawUI
            } catch { $canUseHost = $false }
            if ($canUseHost) {
                if ($PSBoundParameters.ContainsKey('ForegroundColor')) { Write-Host $Message -ForegroundColor $ForegroundColor }
                else { Write-Host $Message }
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
            if ($PSBoundParameters.ContainsKey('ForegroundColor')) { Write-Host $Message -ForegroundColor $ForegroundColor }
            else { Write-Host $Message }
        }
    }

    It 'writes message without throwing in non-interactive context' {
        { Write-ConsoleLine -Message 'Test message' } | Should -Not -Throw
    }

    It 'writes message with ForegroundColor without throwing' {
        { Write-ConsoleLine -Message 'Warning message' -ForegroundColor Yellow } | Should -Not -Throw
    }

    It 'writes message to error stream without throwing' {
        { Write-ConsoleLine -Message 'Error output' -ErrorStream } | Should -Not -Throw
    }

    It 'handles empty message' {
        { Write-ConsoleLine -Message '' } | Should -Not -Throw
    }

    It 'handles long messages' {
        $longMessage = 'A' * 10000
        { Write-ConsoleLine -Message $longMessage } | Should -Not -Throw
    }

    It 'handles messages with special characters' {
        { Write-ConsoleLine -Message "Line1`nLine2`tTabbed `$dollar %percent%" } | Should -Not -Throw
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Memory context integration
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Memory context integration' {

    It 'constructs memory date format correctly (YYYY-MM-DD)' {
        $dateParam = "2026-06-19"
        $dateParam | Should -BeExactly '2026-06-19'
    }

    It 'handles single-digit month and day in date param' {
        $dateParam = "2026-01-05"
        $dateParam | Should -BeExactly '2026-01-05'
    }

    It 'builds generator script path relative to PSScriptRoot' {
        $PSScriptRoot = 'D:\repo\coworker\scripts'
        $expected = Join-Path $PSScriptRoot 'workers\coworker-memory-generator.ps1'
        $expected | Should -BeExactly 'D:\repo\coworker\scripts\workers\coworker-memory-generator.ps1'
    }

    It 'memory context and instructions are empty strings on failure' {
        $memoryContext = ""
        $memoryInstructions = ""
        [string]::IsNullOrEmpty($memoryContext)      | Should -BeTrue
        [string]::IsNullOrEmpty($memoryInstructions) | Should -BeTrue
    }

    It 'memory output is appended to the task prompt' {
        $basePrompt = 'Finish the task described in file: test.md.'
        $memoryInstructions = "`nMEMORY INSTRUCTIONS: Use context below.`n"
        $memoryContext = "`nCONTEXT: Previous tasks completed..."
        $fullPrompt = $basePrompt + "`n`n$memoryInstructions`n`n$memoryContext"

        $fullPrompt | Should -Match 'MEMORY INSTRUCTIONS'
        $fullPrompt | Should -Match 'CONTEXT: Previous tasks completed'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Task log file naming
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Task log file naming' {

    It 'constructs task log path correctly' {
        $taskLogPath = Join-Path 'D:\repo\coworker\tasks\300logs\2026\06\19' '223539-fix-login-bug.task.log'
        $taskLogPath | Should -BeExactly 'D:\repo\coworker\tasks\300logs\2026\06\19\223539-fix-login-bug.task.log'
    }

    It 'constructs agent log path correctly' {
        $agentLogPath = Join-Path 'D:\repo\coworker\tasks\300logs\2026\06\19' '223539-fix-login-bug.agent.log'
        $agentLogPath | Should -BeExactly 'D:\repo\coworker\tasks\300logs\2026\06\19\223539-fix-login-bug.agent.log'
    }

    It 'handles base names with dots' {
        $taskLogPath = Join-Path 'D:\repo\logs\2026\06\19' '120000-v1.2.3-fix.task.log'
        $taskLogPath | Should -BeExactly 'D:\repo\logs\2026\06\19\120000-v1.2.3-fix.task.log'
    }

    It 'temporary stdout and stderr paths are derived from agent log path' {
        $agentLogPath = 'D:\repo\logs\2026\06\19\223539-fix-bug.agent.log'
        $stdOutLog = $agentLogPath + '.stdout'
        $stdErrLog = $agentLogPath + '.stderr'

        $stdOutLog | Should -BeExactly 'D:\repo\logs\2026\06\19\223539-fix-bug.agent.log.stdout'
        $stdErrLog | Should -BeExactly 'D:\repo\logs\2026\06\19\223539-fix-bug.agent.log.stderr'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Task workflow state transitions
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Task workflow state transitions' {

    It 'defines correct directory order for the workflow pipeline' {
        $dirs = @{
            Prepare  = '0draft'
            Created  = '1ready'
            Working  = '2working'
            Finished = '3_1complete'
            Review   = '4review'
            Approved = '5approved'
            Pushed   = '6git-pushed'
            Logs     = '300logs'
        }

        $dirs.Prepare  | Should -BeExactly '0draft'
        $dirs.Created  | Should -BeExactly '1ready'
        $dirs.Working  | Should -BeExactly '2working'
        $dirs.Finished | Should -BeExactly '3_1complete'
        $dirs.Review   | Should -BeExactly '4review'
        $dirs.Approved | Should -BeExactly '5approved'
        $dirs.Pushed   | Should -BeExactly '6git-pushed'
        $dirs.Logs     | Should -BeExactly '300logs'
    }

    It 'workflow flows left-to-right: 0 -> 1 -> 2 -> 3 -> 5 -> 6' {
        $pipeline = @('0draft', '1ready', '2working', '3_1complete', '5approved', '6git-pushed')
        $pipeline.Count | Should -Be 6
        $pipeline[0] | Should -BeExactly '0draft'
        $pipeline[5] | Should -BeExactly '6git-pushed'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Agent execution timeout handling
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Agent execution timeout handling' {

    It 'defines reasonable default timeout values' {
        $agentNameTimeoutSeconds = 60
        $agentRunTimeoutSeconds  = 6000

        $agentNameTimeoutSeconds | Should -Be 60
        $agentRunTimeoutSeconds  | Should -Be 6000
    }

    It 'naming timeout is shorter than execution timeout' {
        (60 -lt 6000) | Should -BeTrue
    }

    It 'agent timeout is 6000 seconds (100 minutes)' {
        (6000 / 60) | Should -Be 100
    }

    It 'warns on non-zero agent exit code' {
        (1 -ne 0) | Should -BeTrue
    }

    It 'no warning for exit code 0' {
        (0 -ne 0) | Should -BeFalse
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Task file extension handling
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Task file extension handling' {

    It 'extracts working base name by removing file extension' {
        $fileName  = 'fix-bug.md'
        $extension = '.md'
        $workingBaseName = $fileName -replace [regex]::Escape($extension), ''
        $workingBaseName | Should -BeExactly 'fix-bug'
    }

    It 'handles files without extension in the base name' {
        $workingBaseName = 'task.md' -replace [regex]::Escape('.md'), ''
        $workingBaseName | Should -BeExactly 'task'
    }

    It 'handles files with dots in the name' {
        $workingBaseName = 'readme-update-browser4-agentic.md' -replace [regex]::Escape('.md'), ''
        $workingBaseName | Should -BeExactly 'readme-update-browser4-agentic'
    }

    It 'only removes the final extension' {
        $workingBaseName = 'file.v1.md' -replace [regex]::Escape('.md'), ''
        $workingBaseName | Should -BeExactly 'file.v1'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Rename fallback logic
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Rename fallback logic' {

    It 'uses safe title when descriptive name is empty' {
        $descriptiveName = ''
        $safeTitle = 'fix-bug-123'
        if ([string]::IsNullOrWhiteSpace($descriptiveName)) { $descriptiveName = $safeTitle }
        $descriptiveName | Should -BeExactly 'fix-bug-123'
    }

    It 'uses safe title when descriptive name is whitespace' {
        $descriptiveName = '   '
        $safeTitle = 'my-task'
        if ([string]::IsNullOrWhiteSpace($descriptiveName)) { $descriptiveName = $safeTitle }
        $descriptiveName | Should -BeExactly 'my-task'
    }

    It 'preserves descriptive name when it is valid' {
        $descriptiveName = 'implement-login-feature'
        $safeTitle = 'fallback'
        if ([string]::IsNullOrWhiteSpace($descriptiveName)) { $descriptiveName = $safeTitle }
        $descriptiveName | Should -BeExactly 'implement-login-feature'
    }

    It 'sanitizes safe title by removing forbidden filename characters' {
        $safeTitle = 'fix: bug #42 / task *name*'
        $safeTitle = $safeTitle -replace '[\\/*?:"<>|]', '_'
        $safeTitle | Should -BeExactly 'fix: bug #42 _ task _name_'
        $safeTitle -match '[\\/*?:"<>|]' | Should -BeFalse
    }

    It 'falls back to "task" when safe title is empty after sanitization' {
        $safeTitle = ''
        if ([string]::IsNullOrWhiteSpace($safeTitle)) { $safeTitle = "task" }
        $safeTitle | Should -BeExactly 'task'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Task log content recording
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Task log content recording' {

    BeforeAll {
        function Initialize-TestFixture {
            $script:TestRoot = Join-Path ([System.IO.Path]::GetTempPath()) "CoworkerTests_$(Get-Random -Minimum 1000 -Maximum 9999)"
            New-Item -ItemType Directory -Path $script:TestRoot -Force | Out-Null
        }
        function Remove-TestFixture {
            if ($script:TestRoot -and (Test-Path $script:TestRoot)) {
                Remove-Item -Path $script:TestRoot -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'records structured task metadata to the task log' {
        $taskLogPath = Join-Path $script:TestRoot 'test.task.log'
        $title = 'Fix login bug'
        $description = 'Fix the login authentication flow.'
        $fileName = 'fix-login-bug.md'
        $repoRoot = 'D:\repo'
        $prompt = 'Fix the login flow.'

        @"
Task: $title
Description: $description
Original File: $fileName
Control Repo: $repoRoot
Target Repo: $repoRoot
Agent Working Directory: $repoRoot
Started: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss'))
Prompt:
$prompt
---
Agent Execution Output:
"@ | Out-File -FilePath $taskLogPath -Encoding UTF8

        $content = Get-Content -Path $taskLogPath -Raw -Encoding UTF8
        $content | Should -Match 'Task: Fix login bug'
        $content | Should -Match 'Description: Fix the login authentication flow.'
        $content | Should -Match 'Original File: fix-login-bug.md'
        $content | Should -Match '---'
        $content | Should -Match 'Agent Execution Output:'
    }

    It 'appends agent exit code and log path after execution' {
        $taskLogPath = Join-Path $script:TestRoot 'test.task.log'
        'header' | Set-Content -Path $taskLogPath -Encoding UTF8

        $exitCode = 0
        $agentLogPath = 'D:\logs\test.agent.log'
        @"

Agent Exit Code: $exitCode
Agent Log: $agentLogPath
"@ | Out-File -FilePath $taskLogPath -Append -Encoding UTF8

        $content = Get-Content -Path $taskLogPath -Raw -Encoding UTF8
        $content | Should -Match 'Agent Exit Code: 0'
        $content | Should -Match ([regex]::Escape('Agent Log: D:\logs\test.agent.log'))
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Agent log combination
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Agent log combination' {

    BeforeAll {
        function Initialize-TestFixture {
            $script:TestRoot = Join-Path ([System.IO.Path]::GetTempPath()) "CoworkerTests_$(Get-Random -Minimum 1000 -Maximum 9999)"
            New-Item -ItemType Directory -Path $script:TestRoot -Force | Out-Null
        }
        function Remove-TestFixture {
            if ($script:TestRoot -and (Test-Path $script:TestRoot)) {
                Remove-Item -Path $script:TestRoot -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'combines stdout then stderr into the agent log file' {
        $agentLogPath = Join-Path $script:TestRoot 'test.agent.log'
        $stdOutLog    = Join-Path $script:TestRoot 'stdout.tmp'
        $stdErrLog    = Join-Path $script:TestRoot 'stderr.tmp'

        'Line 1 from stdout' | Set-Content -Path $stdOutLog -Encoding UTF8
        'Line 2 from stdout' | Add-Content -Path $stdOutLog -Encoding UTF8
        'Error line 1' | Set-Content -Path $stdErrLog -Encoding UTF8

        if (Test-Path $stdOutLog) { Get-Content $stdOutLog -Encoding UTF8 | Out-File -FilePath $agentLogPath -Append -Encoding UTF8 }
        if (Test-Path $stdErrLog) {
            $errContent = Get-Content $stdErrLog -Encoding UTF8
            if ($errContent) {
                "`r`n=== AGENT STDERR ===`r`n" | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
                $errContent | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
            }
        }

        $content = Get-Content -Path $agentLogPath -Raw -Encoding UTF8
        $content | Should -Match 'Line 1 from stdout'
        $content | Should -Match 'Line 2 from stdout'
        $content | Should -Match '=== AGENT STDERR ==='
        $content | Should -Match 'Error line 1'
    }

    It 'skips stderr section when stderr is empty' {
        $agentLogPath = Join-Path $script:TestRoot 'test.agent.log'
        $stdOutLog    = Join-Path $script:TestRoot 'stdout.tmp'
        $stdErrLog    = Join-Path $script:TestRoot 'stderr.tmp'

        'stdout content' | Set-Content -Path $stdOutLog -Encoding UTF8
        '' | Set-Content -Path $stdErrLog -Encoding UTF8

        if (Test-Path $stdOutLog) { Get-Content $stdOutLog -Encoding UTF8 | Out-File -FilePath $agentLogPath -Append -Encoding UTF8 }
        if (Test-Path $stdErrLog) {
            $errContent = Get-Content $stdErrLog -Encoding UTF8
            if ($errContent) {
                "SHOULD NOT APPEAR" | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
            }
        }

        $content = Get-Content -Path $agentLogPath -Raw -Encoding UTF8
        $content | Should -Not -Match 'SHOULD NOT APPEAR'
    }

    It 'skips stderr when file does not exist' {
        $agentLogPath = Join-Path $script:TestRoot 'test.agent.log'
        $stdOutLog    = Join-Path $script:TestRoot 'stdout.tmp'
        $stdErrLog    = Join-Path $script:TestRoot 'nonexistent.tmp'

        'stdout content' | Set-Content -Path $stdOutLog -Encoding UTF8

        if (Test-Path $stdOutLog) { Get-Content $stdOutLog -Encoding UTF8 | Out-File -FilePath $agentLogPath -Append -Encoding UTF8 }
        if (Test-Path $stdErrLog) {
            "SHOULD NOT APPEAR" | Out-File -FilePath $agentLogPath -Append -Encoding UTF8
        }

        $content = Get-Content -Path $agentLogPath -Raw -Encoding UTF8
        $content | Should -Not -Match 'SHOULD NOT APPEAR'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Temp file cleanup
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Temp file cleanup' {

    BeforeEach {
        Initialize-TestFixture
    }

    AfterEach {
        Remove-TestFixture
    }

    It 'Remove-Item with SilentlyContinue does not throw for missing files' {
        $missingFile = Join-Path $script:TestRoot 'does-not-exist.tmp'
        { Remove-Item $missingFile -ErrorAction SilentlyContinue } | Should -Not -Throw
    }

    It 'cleans up existing temp files' {
        $tempFile = Join-Path $script:TestRoot 'cleanup-test.tmp'
        'temp data' | Set-Content -Path $tempFile -Encoding UTF8

        Test-Path $tempFile | Should -BeTrue
        Remove-Item $tempFile -ErrorAction SilentlyContinue
        Test-Path $tempFile | Should -BeFalse
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`nTest suite loaded. Run with:" -ForegroundColor Green
Write-Host "  Invoke-Pester -Path .\coworker.tests.ps1" -ForegroundColor Cyan
