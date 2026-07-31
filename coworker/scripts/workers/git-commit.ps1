#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Shared Coworker commit script — stage, generate message, commit, optionally push.

.DESCRIPTION
    Used by both engineer.ps1 (worker auto-commit) and coworker.ps1
    (b4w coworker commit / push CLI).  Accepts an optional AdditionalMessage
    so callers can inject context (e.g. task filename) into the commit body.

.PARAMETER Message
    Explicit commit message override — when provided skips AI generation entirely.

.PARAMETER AdditionalMessage
    Extra text appended to the AI-generated message body (e.g. "Task: fix-crawl.md").
    Ignored when -Message is provided (the caller is responsible for the full message).

.PARAMETER TargetRepo
    Path to the git repository.  Defaults to Get-TargetRepositoryRoot.

.PARAMETER Push
    After committing, pull --rebase and push to origin.

.PARAMETER Force
    Use --force-with-lease on push.
#>

param(
    [string]$Message = '',
    [string]$AdditionalMessage = '',
    [string]$TargetRepo = '',
    [switch]$Push,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Script-level mutex: only one git-commit.ps1 instance at a time
$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'config.ps1'
if (Test-Path $configPath) { . $configPath }

$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-Host "Another git-commit.ps1 instance is already running. Exiting."
    exit 0
}

# ── Load agent helper ─────────────────────────────────────────────────────
$agentHelper = Join-Path $PSScriptRoot 'agent.ps1'
if (-not (Test-Path $agentHelper)) {
    Write-Host "ERROR: agent.ps1 not found at $agentHelper"
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}
. $agentHelper

# ── Resolve target repo ───────────────────────────────────────────────────
if (-not $TargetRepo) {
    $TargetRepo = Get-TargetRepositoryRoot
}
if (-not (Test-Path $TargetRepo)) {
    Write-Host "ERROR: Target repository not found: $TargetRepo"
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}

# ── Verify git is available ───────────────────────────────────────────────
$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitCmd) {
    Write-Host "ERROR: Git is not installed or not on PATH."
    Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
    exit 1
}

Push-Location $TargetRepo
try {
    # ── Stage all changes ─────────────────────────────────────────────────
    & git add -A 2>&1 | Out-Null

    # ── Check if there's anything to commit ───────────────────────────────
    & git diff --cached --quiet 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Nothing to commit — working tree clean."
        Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
        exit 0
    }

    # ── Generate commit message (unless explicitly provided) ──────────────
    if (-not $Message) {
        $diffStat = & git diff --cached --stat 2>&1
        $diffBody = & git diff --cached 2>&1

        # Truncate diff if it's huge (agent has context limits)
        $maxDiffChars = 8000
        if ($diffBody.Length -gt $maxDiffChars) {
            $diffBody = $diffBody.Substring(0, $maxDiffChars) + "`n... (diff truncated, $($diffBody.Length) total chars)"
        }

        $branch = & git rev-parse --abbrev-ref HEAD 2>&1

        $commitPrompt = @"
Generate a conventional commit message for the following staged changes.

Branch: $branch

The message must follow the Conventional Commits format:
  <type>[optional scope]: <imperative description>

Types: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert
Breaking changes: append "!" after the type (e.g. "feat!:").

Rules:
- First line: "<type>: <imperative description>" (max 72 chars)
- ALWAYS include a body paragraph explaining what was changed and why
- Scope must reflect the code area (e.g. cli, tab, browser, rest, core, build,
  coworker), NOT task-state directory names like "done", "draft", "ready",
  "working", "review", "approved", "pushed", or "issues"
- Ignore coworker task files (.md under coworker/tasks/) when determining type
  and scope — they are task-tracker artifacts, not source code. Base the message
  on the actual source-code and documentation changes
- CRITICAL: The VERY FIRST character of your output must be the commit type
  (feat, fix, docs, etc.). Do NOT output ANY text before the commit message.
  NO conversational framing (no "Here's", "Here is", "Below is", "The following").
  NO code fences or quote marks. NO greetings or explanations. Just the raw
  commit message text, starting immediately with the type.

Examples of GOOD messages:
  feat(cli): add tab-new, tab-close, and tab-select commands
  ^^ body explains new commands, help text, and integration

  fix(browser): resolve cursor positioning race in fill()
  ^^ body describes the bug and the fix

  docs(cli): add help text and examples for tab commands
  ^^ body lists which help sections were updated

Examples of BAD messages (DO NOT produce these):
  fix(done): tab-workflow-issues    ← "done" is not a code area, missing body
  chore: update files               ← too vague, no body
  fix: stuff                        ← too vague, no body

Staged changes summary:
$diffStat

Staged diff:
$diffBody
"@

        Write-Host "Generating commit message via AI agent..." -ForegroundColor Cyan

        $agentCommand = Get-AgentCommand -RepoRoot $TargetRepo
        try {
            $msgStdOut = [System.IO.Path]::GetTempFileName()
            $msgStdErr = [System.IO.Path]::GetTempFileName()

            try {
                $msgProcess = Start-AgentProcess -Executable $agentCommand.Executable `
                    -BaseArgs $agentCommand.BaseArgs `
                    -Prompt $commitPrompt `
                    -WorkingDirectory $TargetRepo `
                    -StdOutPath $msgStdOut `
                    -StdErrPath $msgStdErr `
                    -NoNewWindow `
                    -Backend $agentCommand.Backend

                $msgTimeout = 120
                $msgCompleted = $msgProcess.WaitForExit($msgTimeout * 1000)
                if (-not $msgCompleted) {
                    Stop-Process -Id $msgProcess.Id -Force -ErrorAction SilentlyContinue
                    Write-Host "Warning: Commit message generation timed out. Using fallback." -ForegroundColor Yellow
                }
                else {
                    $Message = Get-Content -Path $msgStdOut -Raw -Encoding UTF8 -ErrorAction SilentlyContinue

                    # ── Post-process: strip conversational framing ──────────
                    # The prompt instructs the AI to output only the commit
                    # message, but some models still wrap it in framing like
                    # "Here's the conventional commit message...".  Defensively
                    # strip common intro phrases and find where the actual
                    # conventional-commit first line begins.
                    $Message = $Message.Trim()

                    # 1. Remove common intro phrases
                    $Message = $Message -replace '(?s)^.*?Here''?s\s+(the\s+)?(conventional\s+)?commit\s+message[^\n]*\s*\n\s*', ''

                    # 2. If there's still framing before the commit message,
                    #    find where the conventional commit type prefix starts
                    $ccTypes = 'feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert'
                    if ($Message -match "(?m)^\s*($ccTypes)[(!:)]") {
                        $firstMatch = $Matches[0]
                        $idx = $Message.IndexOf($firstMatch)
                        if ($idx -gt 0) {
                            $Message = $Message.Substring($idx)
                        }
                    }

                    # 3. Strip any remaining code fences
                    $Message = $Message -replace '^```[a-z]*\s*\r?\n', '' -replace '\r?\n```\s*$', ''
                    $Message = $Message.Trim()
                }
            }
            finally {
                Remove-Item $msgStdOut -ErrorAction SilentlyContinue
                Remove-Item $msgStdErr -ErrorAction SilentlyContinue
            }
        }
        catch {
            Write-Host "Warning: Agent invocation failed: $_. Using fallback." -ForegroundColor Yellow
        }

        # Fallback if agent didn't produce a message
        if (-not $Message) {
            $Message = "fix(coworker): task update`n`n$diffStat"
        }
    }

    # ── Append additional context (caller-provided) ───────────────────────
    if ($AdditionalMessage) {
        $Message = $Message.TrimEnd() + "`n`n$AdditionalMessage"
    }

    # ── Append co-author trailer ──────────────────────────────────────────
    $Message = $Message.TrimEnd() + "`n`nCo-Authored-By: Builtin Coworker"

    Write-Host "Commit message:" -ForegroundColor Cyan
    Write-Host "────────────────" -ForegroundColor DarkGray
    Write-Host $Message -ForegroundColor White
    Write-Host "────────────────" -ForegroundColor DarkGray

    # ── Commit ────────────────────────────────────────────────────────────
    $tmpCommitMsgFile = [System.IO.Path]::GetTempFileName()
    try {
        Set-Content -Path $tmpCommitMsgFile -Value $Message -Encoding UTF8
        $output = & git commit -F $tmpCommitMsgFile 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Committed successfully." -ForegroundColor Green
            Write-Output $output
            $sha = & git rev-parse --short HEAD 2>&1
            Write-Host "Commit: $sha" -ForegroundColor Green
        }
        else {
            Write-Host "Commit failed:" -ForegroundColor Red
            Write-Output $output
            Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
            Pop-Location
            exit 1
        }
    }
    finally {
        Remove-Item $tmpCommitMsgFile -ErrorAction SilentlyContinue
    }

    # ── Push (optional) ───────────────────────────────────────────────────
    if ($Push) {
        $remote = & git remote 2>&1 | Select-Object -First 1
        if (-not $remote) {
            Write-Host "Warning: No git remote configured. Skipping push." -ForegroundColor Yellow
            Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
            Pop-Location
            exit 0
        }

        $branch = & git rev-parse --abbrev-ref HEAD 2>&1

        # Pull first
        Write-Host "Pulling from $remote/$branch..." -ForegroundColor Cyan
        $pullOutput = & git pull --rebase $remote $branch 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Error: Pull failed. There may be conflicts." -ForegroundColor Red
            Write-Output $pullOutput
            Write-Host "`nConflicts must be resolved manually. Then run:" -ForegroundColor Yellow
            Write-Host "  git rebase --continue && git push" -ForegroundColor Yellow
            Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
            Pop-Location
            exit 1
        }
        Write-Host "Pull succeeded." -ForegroundColor DarkGray

        # Push
        $pushFlag = if ($Force) { '--force-with-lease' } else { '' }
        Write-Host "Pushing to $remote/$branch..." -ForegroundColor Cyan
        if ($pushFlag) {
            $pushOutput = & git push $pushFlag $remote $branch 2>&1
        }
        else {
            $pushOutput = & git push $remote $branch 2>&1
        }

        if ($LASTEXITCODE -eq 0) {
            Write-Host "Push succeeded!" -ForegroundColor Green
            Write-Output $pushOutput
        }
        else {
            Write-Host "Push failed:" -ForegroundColor Red
            Write-Output $pushOutput
            if ($pushOutput -match 'non-fast-forward') {
                Write-Host "`nRemote has diverged. Use -Force for --force-with-lease." -ForegroundColor Yellow
            }
            Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
            Pop-Location
            exit 1
        }
    }
}
finally {
    Pop-Location
}

Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
exit 0
