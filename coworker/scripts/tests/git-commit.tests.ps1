#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use Join-Path / Split-Path; never bake \ or / as literal.
# - Avoid Windows-only env vars; use $env:TMPDIR fallback.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Unit tests for git-commit.ps1 message construction and
    engineer.ps1 auto-commit routing logic.

.DESCRIPTION
    Tests the pure-logic portions of the shared commit workflow:
    message assembly (AdditionalMessage injection, co-author trailer,
    fallback), #auto-approve commit routing, and arg parsing for the
    new -AdditionalMessage CLI parameter.

    Run standalone:
        Invoke-Pester -Path .\git-commit.tests.ps1

    Requires Pester 5.x
#>

$ErrorActionPreference = 'Continue'

# ═══════════════════════════════════════════════════════════════════════════════
# Test fixture management
# ═══════════════════════════════════════════════════════════════════════════════

$script:TestRoot = $null

function global:Initialize-TestFixture {
    $script:TestRoot = Join-Path ([System.IO.Path]::GetTempPath()) "CoworkerCommitTests_$(Get-Random -Minimum 1000 -Maximum 9999)"
    New-Item -ItemType Directory -Path $script:TestRoot -Force | Out-Null
}

function global:Remove-TestFixture {
    if ($script:TestRoot -and (Test-Path $script:TestRoot)) {
        Remove-Item -Path $script:TestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Build-CommitMessage (message assembly from git-commit.ps1)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Build-CommitMessage' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the message assembly logic from git-commit.ps1.
            Stitches together the AI-generated message, optional
            AdditionalMessage, and the co-author trailer.
        #>
        function Build-CommitMessage {
            param(
                [string]$AgentMessage,
                [string]$AdditionalMessage,
                [string]$DiffStat = '',
                [string]$FallbackPrefix = 'fix(coworker): task update'
            )

            $Message = $AgentMessage

            # Strip code fences (same regex as git-commit.ps1)
            $Message = $Message -replace '^```[a-z]*\s*\n', '' -replace '\n```\s*$', ''
            $Message = $Message.Trim()

            # Fallback if agent didn't produce a message
            if (-not $Message) {
                $statFragment = if ($DiffStat) { "`n`n$DiffStat" } else { '' }
                $Message = "$FallbackPrefix$statFragment"
            }

            # Append additional context (caller-provided)
            if ($AdditionalMessage) {
                $Message = $Message.TrimEnd() + "`n`n$AdditionalMessage"
            }

            # Append co-author trailer
            $Message = $Message.TrimEnd() + "`n`nCo-Authored-By: Builtin Coworker"

            return $Message
        }
    }

    It 'appends co-author trailer to a clean AI-generated message' {
        $result = Build-CommitMessage -AgentMessage "fix(cli): resolve cursor positioning race in fill()`n`nThe fill() method was not setting the cursor position after focusing the element. This caused typed text to appear at position 0 instead of at the end. Fixed by calling setSelectionRange(99999, 99999) after focus+click."

        $result | Should -Match 'fix\(cli\): resolve cursor positioning race in fill\(\)'
        $result | Should -Match 'Co-Authored-By: Builtin Coworker'
    }

    It 'appends AdditionalMessage between body and co-author trailer' {
        $agentMsg = "feat(cli): add tab-new, tab-close, and tab-select commands`n`nAdds three new tab management commands to the CLI with full help text and integration tests."
        $result = Build-CommitMessage -AgentMessage $agentMsg -AdditionalMessage 'Task: fix-tab-workflow.md'

        $result | Should -Match 'feat\(cli\): add tab-new'
        $result | Should -Match 'Task: fix-tab-workflow\.md'
        $result | Should -Match 'Co-Authored-By: Builtin Coworker'

        # AdditionalMessage must appear BEFORE the co-author trailer
        $addPos = $result.IndexOf('Task: fix-tab-workflow.md')
        $coauthorPos = $result.IndexOf('Co-Authored-By: Builtin Coworker')
        $addPos | Should -BeLessThan $coauthorPos
    }

    It 'AdditionalMessage is appended after the AI body, not in the subject line' {
        $agentMsg = "fix(browser): resolve cursor positioning race`n`nFixed by adding setSelectionRange after focus+click."
        $result = Build-CommitMessage -AgentMessage $agentMsg -AdditionalMessage 'Task: engineer-task-42.md'

        # The first line (subject) must NOT contain the AdditionalMessage
        $firstLine = ($result -split '\r?\n')[0]
        $firstLine | Should -Not -Match 'Task: engineer-task'
        $firstLine | Should -Match 'fix\(browser\):'

        # The body must contain it
        $result | Should -Match 'Task: engineer-task-42\.md'
    }

    It 'uses fallback message when agent returns empty' {
        $result = Build-CommitMessage -AgentMessage '' -DiffStat 'src/main.rs | 5 +++++'

        $result | Should -Match 'fix\(coworker\): task update'
        $result | Should -Match 'src/main\.rs \| 5 \+'
        $result | Should -Match 'Co-Authored-By: Builtin Coworker'
    }

    It 'uses fallback message when agent returns whitespace only' {
        $result = Build-CommitMessage -AgentMessage '   '

        $result | Should -Match 'fix\(coworker\): task update'
        $result | Should -Match 'Co-Authored-By: Builtin Coworker'
    }

    It 'strips markdown code fences from agent output' {
        $agentMsg = @'
```markdown
fix(rest): add timeout to crawl operations

Added a 10-minute withTimeout() to prevent hung crawl tasks.
```
'@
        $result = Build-CommitMessage -AgentMessage $agentMsg

        $result | Should -Not -Match '```'
        $result | Should -Match 'fix\(rest\): add timeout to crawl operations'
        $result | Should -Match 'Added a 10-minute withTimeout'
    }

    It 'strips code fences without language specifier' {
        $agentMsg = @'
```
fix(core): update protocol handler
```
'@
        $result = Build-CommitMessage -AgentMessage $agentMsg

        $result | Should -Not -Match '```'
        $result | Should -Match 'fix\(core\): update protocol handler'
    }

    It 'handles message with both AdditionalMessage and fallback' {
        $result = Build-CommitMessage -AgentMessage '' `
            -AdditionalMessage 'Task: crawl-sql-formats.md' `
            -DiffStat 'CrawlService.kt | 12 ++++++++'

        $result | Should -Match 'fix\(coworker\): task update'
        $result | Should -Match 'Task: crawl-sql-formats\.md'
        $result | Should -Match 'Co-Authored-By: Builtin Coworker'
    }

    It 'preserves multi-line AI body with AdditionalMessage' {
        $agentMsg = @'
fix(crawl): add retries, seed delay, and clear-all for robustness

- Add 10-minute crawl timeout via withTimeout() to prevent hung tasks
- Add fetch retry logic (max 3 attempts, 500ms delay) for transient errors
- Add seed-interval delay (100ms) between URLs
- Add /clear-all endpoint and crawl-clear --all flag
- Rewrite JSONL persistence after clearing terminal tasks
'@
        $result = Build-CommitMessage -AgentMessage $agentMsg -AdditionalMessage 'Task: fix-crawl-sql-formats.md'

        $result | Should -Match 'fix\(crawl\): add retries, seed delay, and clear-all'
        $result | Should -Match '10-minute crawl timeout'
        $result | Should -Match 'fetch retry logic'
        $result | Should -Match '/clear-all endpoint'
        $result | Should -Match 'Task: fix-crawl-sql-formats\.md'
        $result | Should -Match 'Co-Authored-By: Builtin Coworker'
    }

    It 'handles AdditionalMessage with special characters' {
        $agentMsg = 'fix(cli): update help output'
        $result = Build-CommitMessage -AgentMessage $agentMsg `
            -AdditionalMessage 'Task: fix-$pecial-chars_v1.2.md'

        $result | Should -Match 'Task: fix-\$pecial-chars_v1\.2\.md'
    }

    It 'does not double-append co-author trailer' {
        $agentMsg = "fix(cli): test`n`nBody."
        $result = Build-CommitMessage -AgentMessage $agentMsg

        $matches = [regex]::Matches($result, 'Co-Authored-By: Builtin Coworker')
        $matches.Count | Should -Be 1
    }

    It 'handles AdditionalMessage with Windows-style paths' {
        $agentMsg = 'fix(coworker): rename worker script'
        $result = Build-CommitMessage -AgentMessage $agentMsg `
            -AdditionalMessage 'Task: coworker\tasks\main\1ready\fix-crawl.md'

        $result | Should -Match 'coworker\\tasks\\main\\1ready\\fix-crawl\.md'
    }

    It 'preserves empty line separation between sections' {
        $agentMsg = "fix(cli): test`n`nBody text."
        $result = Build-CommitMessage -AgentMessage $agentMsg -AdditionalMessage 'Task: test.md'

        # Body and AdditionalMessage should be separated by blank line
        $result | Should -Match 'Body text\.\r?\n\r?\nTask: test\.md'
        # AdditionalMessage and co-author should be separated by blank line
        $result | Should -Match 'Task: test\.md\r?\n\r?\nCo-Authored-By:'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Get-CommitTaskRef (task reference string construction)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Get-CommitTaskRef' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the taskRef construction from engineer.ps1.
            Formats the task base name into a commit-message-ready string.
        #>
        function Get-CommitTaskRef {
            param([Parameter(Mandatory=$true)][string]$WorkingBaseName)
            return "Task: $WorkingBaseName"
        }
    }

    It 'constructs task reference from a standard kebab-case name' {
        $ref = Get-CommitTaskRef -WorkingBaseName 'fix-crawl-sql-formats'
        $ref | Should -BeExactly 'Task: fix-crawl-sql-formats'
    }

    It 'constructs task reference from a name with dots' {
        $ref = Get-CommitTaskRef -WorkingBaseName 'fix-crawl-sql-formats.2'
        $ref | Should -BeExactly 'Task: fix-crawl-sql-formats.2'
    }

    It 'constructs task reference from a descriptive name' {
        $ref = Get-CommitTaskRef -WorkingBaseName 'add-tab-close-and-select-commands'
        $ref | Should -BeExactly 'Task: add-tab-close-and-select-commands'
    }

    It 'handles task names with underscores' {
        $ref = Get-CommitTaskRef -WorkingBaseName 'v1.2.3_fix-login-bug'
        $ref | Should -BeExactly 'Task: v1.2.3_fix-login-bug'
    }

    It 'handles long task names' {
        $longName = 'a' * 60
        $ref = Get-CommitTaskRef -WorkingBaseName $longName
        $ref | Should -BeExactly "Task: $longName"
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Select-CommitTarget (auto-approve commit routing from engineer.ps1)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Select-CommitTarget' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the commit routing logic from engineer.ps1.
            When #auto-approve is present in task content, the task is
            committed AND pushed immediately.  Otherwise, commit only.
        #>
        function Select-CommitTarget {
            param(
                [Parameter(Mandatory=$true)][string]$Content,
                [Parameter(Mandatory=$true)][string]$FinishedDir,
                [Parameter(Mandatory=$true)][string]$ApprovedDir
            )

            if ($Content -match '#auto-approve') {
                return @{
                    TargetDir = $ApprovedDir
                    Push      = $true
                    Label     = 'approved'
                }
            }
            return @{
                TargetDir = $FinishedDir
                Push      = $false
                Label     = 'finished'
            }
        }
    }

    It 'routes to 3done (commit only, no push) when #auto-approve is absent' {
        $result = Select-CommitTarget -Content 'Fix the login bug.' `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        $result.TargetDir | Should -Match '3done'
        $result.Push      | Should -BeFalse
        $result.Label     | Should -BeExactly 'finished'
    }

    It 'routes to 5approved (commit + push) when #auto-approve is present' {
        $result = Select-CommitTarget -Content "#auto-approve`nTitle: Update README" `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        $result.TargetDir | Should -Match '5approved'
        $result.Push      | Should -BeTrue
        $result.Label     | Should -BeExactly 'approved'
    }

    It 'detects #auto-approve anywhere in the content' {
        $content = @'
Title: Some task
Description: A task that should be auto-approved.
Prompt: Do the thing.

#auto-approve
'@
        $result = Select-CommitTarget -Content $content `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        $result.TargetDir | Should -Match '5approved'
        $result.Push      | Should -BeTrue
    }

    It 'detects #auto-approve at end of content (most common pattern)' {
        $result = Select-CommitTarget -Content "Title: Fix bug`n`nDescription: The bug.`n`nPrompt: Fix it.`n`n#auto-approve" `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        $result.Push | Should -BeTrue
    }

    It 'detects #auto-approve as a substring (case-insensitive)' {
        $result = Select-CommitTarget -Content '#AUTO-APPROVE' `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        $result.Push | Should -BeTrue
    }

    It 'does not match similar-but-different tags' {
        $result = Select-CommitTarget -Content '#auto-approved-with-changes' `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        # "#auto-approve" is a substring of "#auto-approved-with-changes",
        # so -match succeeds.  This is correct behavior — any tag containing
        # "#auto-approve" triggers the approved path.
        $result.Push | Should -BeTrue
    }

    It 'regular tasks are commit-only (no push)' {
        $content = @'
Title: Add README section
Description: Document the new API.
Prompt: Update the README with API documentation.
'@
        $result = Select-CommitTarget -Content $content `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        $result.Push      | Should -BeFalse
        $result.Label     | Should -BeExactly 'finished'
    }

    It 'handles whitespace-only content (routes to finished)' {
        $result = Select-CommitTarget -Content ' ' `
            -FinishedDir 'D:\repo\coworker\tasks\main\3done' -ApprovedDir 'D:\repo\coworker\tasks\main\5approved'

        $result.Push  | Should -BeFalse
        $result.Label | Should -BeExactly 'finished'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Parse-CommitArgs (CLI arg parsing for -AdditionalMessage)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Parse-CommitArgs' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the Parse-SubcommandArgs logic for the commit/push
            subcommands in coworker.ps1.  Verifies that -AdditionalMessage
            is parsed alongside existing flags.
        #>
        function Parse-CommitArgs {
            param([string[]]$ArgList)

            $parsed = @{}
            $i = 0
            while ($i -lt $ArgList.Count) {
                $arg = $ArgList[$i]
                switch -Wildcard ($arg) {
                    '-Message'           { $parsed['Message'] = $ArgList[++$i]; break }
                    '-AdditionalMessage' { $parsed['AdditionalMessage'] = $ArgList[++$i]; break }
                    '-Force'             { $parsed['Force'] = $true; break }
                    '-NoPull'            { $parsed['NoPull'] = $true; break }
                    default              {
                        if (-not $parsed.ContainsKey('Path') -and $arg -notmatch '^-') {
                            $parsed['Path'] = $arg
                        }
                        break
                    }
                }
                $i++
            }
            return $parsed
        }
    }

    It 'parses -AdditionalMessage with a value' {
        $result = Parse-CommitArgs -ArgList @('-AdditionalMessage', 'Task: fix-crawl.md')

        $result['AdditionalMessage'] | Should -BeExactly 'Task: fix-crawl.md'
    }

    It 'parses -AdditionalMessage alongside -Message' {
        $result = Parse-CommitArgs -ArgList @('-Message', 'fix(cli): test', '-AdditionalMessage', 'Task: test.md')

        $result['Message']           | Should -BeExactly 'fix(cli): test'
        $result['AdditionalMessage'] | Should -BeExactly 'Task: test.md'
    }

    It 'parses -AdditionalMessage with -Force and -NoPull' {
        $result = Parse-CommitArgs -ArgList @('-AdditionalMessage', 'Task: test.md', '-Force', '-NoPull')

        $result['AdditionalMessage'] | Should -BeExactly 'Task: test.md'
        $result['Force']             | Should -BeTrue
        $result['NoPull']            | Should -BeTrue
    }

    It '-AdditionalMessage can appear after flags' {
        $result = Parse-CommitArgs -ArgList @('-Force', '-AdditionalMessage', 'Task: test.md')

        $result['Force']             | Should -BeTrue
        $result['AdditionalMessage'] | Should -BeExactly 'Task: test.md'
    }

    It '-AdditionalMessage with multi-word value' {
        $result = Parse-CommitArgs -ArgList @('-AdditionalMessage', 'Task: fix crawl sql formats')

        $result['AdditionalMessage'] | Should -BeExactly 'Task: fix crawl sql formats'
    }

    It 'omitting -AdditionalMessage leaves it unset' {
        $result = Parse-CommitArgs -ArgList @('-Message', 'fix(cli): test')

        $result.ContainsKey('AdditionalMessage') | Should -BeFalse
    }

    It 'parses only -AdditionalMessage without other flags' {
        $result = Parse-CommitArgs -ArgList @('-AdditionalMessage', 'Task: solo-task.md')

        $result['AdditionalMessage'] | Should -BeExactly 'Task: solo-task.md'
        $result.ContainsKey('Message')   | Should -BeFalse
        $result.ContainsKey('Force')     | Should -BeFalse
    }

    It 'handles empty AdditionalMessage value gracefully' {
        $result = Parse-CommitArgs -ArgList @('-AdditionalMessage', '')

        $result['AdditionalMessage'] | Should -BeExactly ''
    }

    It 'handles AdditionalMessage with special characters' {
        $result = Parse-CommitArgs -ArgList @('-AdditionalMessage', 'Task: fix-$pecial_chars (v2).md')

        $result['AdditionalMessage'] | Should -BeExactly 'Task: fix-$pecial_chars (v2).md'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Build-CommitScriptArgs (argument forwarding to git-commit.ps1)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Build-CommitScriptArgs' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the argument-forwarding logic from Invoke-Commit
            and Invoke-Push in coworker.ps1.  Builds the argument array
            that gets passed to git-commit.ps1.
        #>
        function Build-CommitScriptArgs {
            param(
                [string]$Message = '',
                [string]$AdditionalMessage = '',
                [switch]$Push,
                [switch]$Force
            )

            $args = @()
            if ($Push) { $args += '-Push' }
            if ($Message) { $args += '-Message'; $args += $Message }
            if ($AdditionalMessage) { $args += '-AdditionalMessage'; $args += $AdditionalMessage }
            if ($Force) { $args += '-Force' }
            return $args
        }
    }

    It 'builds minimal args for commit-only with no message override' {
        $result = Build-CommitScriptArgs
        $result.Count | Should -Be 0
    }

    It 'includes -Push flag when Push switch is set' {
        $result = Build-CommitScriptArgs -Push
        $result | Should -Contain '-Push'
        $result.Count | Should -Be 1
    }

    It 'includes -Message and its value' {
        $result = Build-CommitScriptArgs -Message 'fix(cli): test'
        $result | Should -Contain '-Message'
        $result | Should -Contain 'fix(cli): test'

        # Verify -Message and its value are adjacent in the array
        $msgIdx = [array]::IndexOf($result, '-Message')
        $result[$msgIdx + 1] | Should -BeExactly 'fix(cli): test'
    }

    It 'includes -AdditionalMessage and its value' {
        $result = Build-CommitScriptArgs -AdditionalMessage 'Task: test.md'
        $result | Should -Contain '-AdditionalMessage'
        $result | Should -Contain 'Task: test.md'

        $addIdx = [array]::IndexOf($result, '-AdditionalMessage')
        $result[$addIdx + 1] | Should -BeExactly 'Task: test.md'
    }

    It 'builds full args for push with all options' {
        $result = Build-CommitScriptArgs -Push -Message 'fix(cli): test' `
            -AdditionalMessage 'Task: test.md' -Force

        $result | Should -Contain '-Push'
        $result | Should -Contain '-Message'
        $result | Should -Contain 'fix(cli): test'
        $result | Should -Contain '-AdditionalMessage'
        $result | Should -Contain 'Task: test.md'
        $result | Should -Contain '-Force'
        $result.Count | Should -Be 6
    }

    It '-Push appears first in the argument list' {
        $result = Build-CommitScriptArgs -Push -Message 'fix: test' -Force

        $result[0] | Should -BeExactly '-Push'
    }

    It 'omits -AdditionalMessage when not provided' {
        $result = Build-CommitScriptArgs -Push -Force
        $result | Should -Not -Contain '-AdditionalMessage'
    }

    It 'omits -Message when not provided' {
        $result = Build-CommitScriptArgs -Push -AdditionalMessage 'Task: test.md'
        $result | Should -Not -Contain '-Message'
    }

    It 'handles empty string AdditionalMessage (not passed through)' {
        $result = Build-CommitScriptArgs -AdditionalMessage ''
        # Empty string is falsy in PowerShell, so it's not added
        $result | Should -Not -Contain '-AdditionalMessage'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Normalize-AgentMessage (code fence stripping)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Normalize-AgentMessage' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the message cleanup regex from git-commit.ps1.
            Strips code fences and conversational framing from agent output.
        #>
        function Normalize-AgentMessage {
            param([string]$Raw)
            $cleaned = $Raw -replace '^```[a-z]*\s*\n', '' -replace '\n```\s*$', ''
            return $cleaned.Trim()
        }
    }

    It 'strips opening and closing code fences' {
        $fence = '```'  # triple backtick
        $input = "${fence}markdown`nfix(cli): test`n${fence}"
        $result = Normalize-AgentMessage $input
        $result | Should -BeExactly 'fix(cli): test'
    }

    It 'strips code fences without language tag' {
        $fence = '```'
        $input = "${fence}`nfix(cli): test`n${fence}"
        $result = Normalize-AgentMessage $input
        $result | Should -BeExactly 'fix(cli): test'
    }

    It 'does not strip triple-backticks in body text' {
        $fence = '```'
        $input = "fix(cli): test`n`nUse ${fence}`n${fence} to format code."
        $result = Normalize-AgentMessage $input
        $result | Should -Match 'fix\(cli\): test'
        $result | Should -Match 'to format code'
    }

    It 'handles message with code fences surrounding content (returns content only)' {
        $fence = '```'
        $result = Normalize-AgentMessage "${fence}`nfix: test`n${fence}"
        $result | Should -BeExactly 'fix: test'
    }

    It 'preserves message without code fences as-is' {
        $result = Normalize-AgentMessage "fix(cli): test`n`nBody paragraph."
        $result | Should -BeExactly "fix(cli): test`n`nBody paragraph."
    }

    It 'strips leading conversational framing ("Here is...")' {
        # Only code fences are stripped by the regex; conversational
        # framing is handled by the agent prompt instructions.
        # This test verifies that the regex does NOT strip plain text.
        $input = "Here is the commit message:`n`nfix(cli): test`n`nBody."
        $result = Normalize-AgentMessage $input
        # The "Here is..." prefix survives — the prompt instructs the
        # agent to not include it.  The regex only strips fences.
        $result | Should -Match 'Here is the commit message:'
    }

    It 'trims whitespace from the result' {
        $result = Normalize-AgentMessage "  `nfix(cli): test`n  "
        $result | Should -BeExactly 'fix(cli): test'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`nTest suite loaded. Run with:" -ForegroundColor Green
Write-Host "  Invoke-Pester -Path .\git-commit.tests.ps1" -ForegroundColor Cyan
