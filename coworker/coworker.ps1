#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════════════════

# ============================================================================
# Coworker Task CLI — Manage tasks across the Coworker state machine
# ============================================================================
# Usage: coworker.ps1 <command> [options]
#
# Commands:
#   draft    Create or edit a task draft in 0draft/
#   refine   Improve a draft task using AI analysis
#   assign   Move a task into 1ready/ for execution (alias: add)
#   list     Show tasks grouped by state
#   view     Display full task content
#   cancel   Move a task back to draft or remove it
#   commit   Git commit workspace changes (no push)
#   push     Commit and push to remote
#   fix      Pick a task from 1ready/ and execute it once
# ============================================================================

param(
    [Parameter(Position = 0)]
    [string]$Command = '',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Remaining = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Help text ────────────────────────────────────────────────────────────────
$script:HelpText = @'
Usage: coworker <command> [options]

Commands:
  draft     Create or edit a task draft in 0draft/
            coworker draft [-Title <str>] [-Content <str>] [-Edit] [-Name <str>]

  refine    Improve a draft task using AI analysis
            coworker refine [-Path <path>] [-Audience <str>] [-InPlace]

  assign    Move a task into 1ready/ for execution
            coworker assign [-Path <path>] [-Name <str>] [-Rename] [-AutoApprove]
            (alias: add)

  list      Show tasks grouped by state (default: 20 per state)
            coworker list [-State <str>] [-Count <int>] [-Brief] [-NoPager]

  view      Display full task content
            coworker view [-Path <path>] [-Name <str>] [-Raw]

  cancel    Move a task back to draft or remove it
            coworker cancel [-Path <path>] [-Remove] [-Force]

  commit    Git commit workspace changes (no push, AI-generated message)
            coworker commit [-Message <str>] [-AdditionalMessage <str>]

  push      Commit (AI-generated msg) and push to remote
            coworker push [-Message <str>] [-AdditionalMessage <str>] [-Force] [-NoPull]

  fix       Pick a task from 1ready/ and execute it once
            coworker fix [-Path <path>] [-Name <str>] [-Latest]

  review    Review .issues.md files (interactive or inline AI review)
            coworker review [-Path <path>] [-Name <str>] [-List] [-All] [-Inline] [-AutoApprove]

Run "coworker <command>" with no additional arguments to see
command-specific help.

'@

# ═══════════════════════════════════════════════════════════════════════════════
# Shared setup — load all coworker utilities
# ═══════════════════════════════════════════════════════════════════════════════

# Early load for Write-ConsoleLine / Write-LogMessage (used during startup)
$taskLoggerHelper = Join-Path $PSScriptRoot 'scripts\workers\task-logger.ps1'
if (Test-Path -LiteralPath $taskLoggerHelper) {
    . $taskLoggerHelper
}

$configScriptPath = Join-Path $PSScriptRoot 'scripts\config.ps1'
if (-not (Test-Path $configScriptPath)) {
    Write-Host "ERROR: config.ps1 not found at $configScriptPath" -ForegroundColor Red
    exit 1
}
. $configScriptPath

# ── Eagerly dot-source worker utilities at script scope ──────────────────────
# These MUST be dot-sourced at script scope (not inside a function) so the
# functions they define are visible to all subcommand functions.

# workflow.ps1 references these script-scope variables; pre-set them.
$script:agentBackend = Get-AgentBackend
$script:agentBaseArgs = @()
$script:agentExecutable = ''
$script:agentWorkingDirectory = Get-WorkspaceRoot

$workflowHelper = Join-Path $PSScriptRoot 'scripts\workers\workflow.ps1'
if (Test-Path $workflowHelper) { . $workflowHelper }

$agentHelper = Join-Path $PSScriptRoot 'scripts\workers\agent.ps1'
if (Test-Path $agentHelper) { . $agentHelper }

$reviewHelper = Join-Path $PSScriptRoot 'scripts\review.ps1'
if (Test-Path $reviewHelper) { . $reviewHelper }

$stateHelper = Join-Path $PSScriptRoot 'scripts\common\State.ps1'
if (Test-Path $stateHelper) { . $stateHelper }

# ═══════════════════════════════════════════════════════════════════════════════
# Shared helper functions
# ═══════════════════════════════════════════════════════════════════════════════

function Get-TaskDirectories {
    $tasksRoot = Get-TasksRoot
    $main = Join-Path $tasksRoot 'main'
    return [pscustomobject]@{
        Draft    = Join-Path $main '0draft'
        Ready    = Join-Path $main '1ready'
        Working  = Join-Path $main '2working'
        Done     = Join-Path $main '3done'
        Review   = Join-Path $main '4review'
        Approved = Join-Path $main '5approved'
        Pushed   = Join-Path $main '6git-pushed'
    }
}

function Get-StateLabel {
    param([string]$DirPath)
    $name = Split-Path -Leaf $DirPath
    switch -Wildcard ($name) {
        '0draft'      { return 'Draft' }
        '1ready'      { return 'Ready' }
        '2working'    { return 'Working' }
        '3done'       { return 'Done' }
        '4review'     { return 'Review' }
        '5approved'   { return 'Approved' }
        '6git-pushed' { return 'Pushed' }
        default       { return $name }
    }
}

function Get-StateColor {
    param([string]$DirPath)
    $name = Split-Path -Leaf $DirPath
    switch -Wildcard ($name) {
        '0draft'      { return 'Gray' }
        '1ready'      { return 'Green' }
        '2working'    { return 'Yellow' }
        '3done'       { return 'Cyan' }
        '4review'     { return 'Magenta' }
        '5approved'   { return 'Blue' }
        '6git-pushed' { return 'DarkGray' }
        default       { return 'White' }
    }
}

function Get-StateFromLabel {
    param([string]$Label)
    switch ($Label) {
        'draft'    { return '0draft' }
        'ready'    { return '1ready' }
        'working'  { return '2working' }
        'done'     { return '3done' }
        'review'   { return '4review' }
        'approved' { return '5approved' }
        'pushed'   { return '6git-pushed' }
        'all'      { return 'all' }
        default    { return $null }
    }
}

<#
.SYNOPSIS
    Extract a human-readable title from a task file.
    Returns the first Title: line (structured format) or the first # heading,
    falling back to the filename.
#>
function Get-TaskTitle {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string]$Content = ''
    )

    if (-not $Content) {
        try { $Content = Get-Content -Path $FilePath -TotalCount 20 -Encoding UTF8 -ErrorAction Stop }
        catch { return (Split-Path -Leaf $FilePath) }
    }

    # Structured format: Title: <text>
    if ($Content -match '^\s*Title:\s*(.+)') {
        return $Matches[1].Trim()
    }
    # Markdown heading: # <text>
    if ($Content -match '^\s*#\s+(.+)') {
        return $Matches[1].Trim()
    }
    return (Split-Path -Leaf $FilePath)
}

<#
.SYNOPSIS
    Search all task directories for a file by name or partial name.
    Returns array of matching full paths.
#>
function Find-TaskFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [string]$State = ''
    )

    $dirs = Get-TaskDirectories
    $searchDirs = if ($State) {
        $stateDirName = Get-StateFromLabel $State
        if (-not $stateDirName) { @() }
        else {
            $propName = (Get-StateLabel -DirPath $stateDirName)
            $propMap = @{
                'Draft'    = $dirs.Draft
                'Ready'    = $dirs.Ready
                'Working'  = $dirs.Working
                'Done'     = $dirs.Done
                'Review'   = $dirs.Review
                'Approved' = $dirs.Approved
                'Pushed'   = $dirs.Pushed
            }
            @($propMap[$propName])
        }
    }
    else {
        @($dirs.Draft, $dirs.Ready, $dirs.Working, $dirs.Done,
          $dirs.Review, $dirs.Approved, $dirs.Pushed)
    }

    # If Name is an absolute or relative path that exists, return it directly
    if (Test-Path -LiteralPath $Name) {
        return @((Resolve-Path $Name).Path)
    }

    $matches = @()
    $searchName = [System.IO.Path]::GetFileNameWithoutExtension($Name)
    $searchExt = [System.IO.Path]::GetExtension($Name)
    if (-not $searchExt) { $searchExt = '.md' }

    foreach ($dir in $searchDirs) {
        if (-not (Test-Path $dir)) { continue }

        # Direct match in this dir
        $directPath = Join-Path $dir "$searchName$searchExt"
        if (Test-Path -LiteralPath $directPath) {
            $matches += (Resolve-Path $directPath).Path
        }

        # Recursive search (for date-organized dirs: 3done, 6git-pushed)
        $found = Get-ChildItem -Path $dir -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) } |
            Where-Object {
                $_.Name -eq "$searchName$searchExt" -or
                $_.BaseName -eq $searchName -or
                $_.Name -like "*$searchName*"
            }
        foreach ($f in $found) {
            if ($f.FullName -notin $matches) {
                $matches += $f.FullName
            }
        }
    }

    return $matches
}

function Resolve-SingleTaskFile {
    param(
        [string]$Path = '',
        [string]$Name = '',
        [string]$State = ''
    )

    $searchKey = if ($Path) { $Path } else { $Name }
    if (-not $searchKey) { return $null }

    $matches = @(Find-TaskFile -Name $searchKey -State $State)

    if ($matches.Count -eq 0) {
        Write-ConsoleLine -Message "No task file found matching '$searchKey'." -ForegroundColor Red
        return $null
    }

    if ($matches.Count -eq 1) {
        return $matches[0]
    }

    # Multiple matches: present choices
    Write-ConsoleLine -Message "Multiple matches for '$searchKey':" -ForegroundColor Yellow
    for ($i = 0; $i -lt $matches.Count; $i++) {
        $stateDir = Split-Path -Parent $matches[$i]
        $stateLabel = Get-StateLabel -DirPath $stateDir
        Write-ConsoleLine -Message "  [$i] [$stateLabel] $($matches[$i])"
    }
    # Default to first match in non-interactive mode
    Write-ConsoleLine -Message "Using first match. Specify with full path to disambiguate." -ForegroundColor DarkGray
    return $matches[0]
}

<#
.SYNOPSIS
    Parse structured task content into Title, Description, Prompt components.
    Returns a hashtable with Title, Description, Prompt, IsStructured keys.
#>
function Read-TaskContent {
    param([string]$Path)
    $content = Get-Content -Path $Path -Raw -Encoding UTF8 -ErrorAction Stop
    $title = ''
    $description = ''
    $prompt = $content
    $isStructured = $false

    if ($content -match "(?s)\A\s*Title:\s*(?<title>.*?)(\r\n|\n)Description:\s*(?<desc>.*?)(\r\n|\n)Prompt:\s*(?<prompt>.*)$") {
        $title = $Matches['title'].Trim()
        $description = $Matches['desc'].Trim()
        $prompt = $Matches['prompt'].Trim()
        $isStructured = $true
    }

    return @{
        Title        = $title
        Description  = $description
        Prompt       = $prompt
        RawContent   = $content
        IsStructured = $isStructured
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: draft — Create or edit a task draft in 0draft/
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Draft {
    param(
        [string]$Title = '',
        [string]$Content = '',
        [string]$Prompt = '',
        [switch]$Edit,
        [string]$Name = '',
        [switch]$RefreshEditor
    )

    $dirs = Get-TaskDirectories
    $draftDir = $dirs.Draft
    if (-not (Test-Path $draftDir)) {
        New-Item -ItemType Directory -Path $draftDir -Force | Out-Null
    }

    Ensure-DraftPlaceholders -DraftDirectory $draftDir

    # Use $Prompt as alias for $Content
    if ($Prompt -and -not $Content) { $Content = $Prompt }

    # Determine filename
    $fileName = ''
    if ($Name) {
        $safeName = $Name -replace '[\\/*?:"<>|]', '_' -replace '\s+', '-'
        $safeName = $safeName -replace '[^A-Za-z0-9._-]', '-' -replace '-+', '-'
        $safeName = $safeName.Trim(' ', '.', '-', '_')
        if ($safeName.Length -gt 60) { $safeName = $safeName.Substring(0, 60).Trim(' ', '.', '-', '_') }
        if (-not $safeName) { $safeName = 'draft' }
        $fileName = "$safeName.md"
    }
    elseif ($Title) {
        $safeName = $Title -replace '[\\/*?:"<>|]', '_' -replace '\s+', '-'
        $safeName = $safeName -replace '[^A-Za-z0-9._-]', '-' -replace '-+', '-'
        $safeName = $safeName.Trim(' ', '.', '-', '_')
        if ($safeName.Length -gt 60) { $safeName = $safeName.Substring(0, 60).Trim(' ', '.', '-', '_') }
        if (-not $safeName) { $safeName = 'draft' }
        $fileName = "$safeName.md"
    }
    else {
        # Generate from timestamp
        $ts = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
        $fileName = "draft-$ts.md"
    }

    $fileInfo = Resolve-UniquePath -Directory $draftDir -BaseName ([System.IO.Path]::GetFileNameWithoutExtension($fileName)) -Extension '.md'
    $filePath = $fileInfo.Path

    # Build content
    if ($Content -or $Title) {
        $desc = if ($Content) { "Task drafted via coworker CLI." } else { "" }
        $body = if ($Content) { $Content } else { "Describe the task here." }
        $fileContent = @"
Title: $Title
Description: $desc
Prompt: $body
"@
        Set-Content -Path $filePath -Value $fileContent -Encoding UTF8
    }
    else {
        # Empty stub
        $stubContent = @"
# Task Draft
Created: $(Get-CoworkerTimestamp)

## Problem

## Solution

## References
"@
        Set-Content -Path $filePath -Value $stubContent -Encoding UTF8
    }

    Write-ConsoleLine -Message "Created draft: $filePath" -ForegroundColor Green

    # Open editor if requested
    if ($Edit) {
        if ($RefreshEditor) {
            Write-ConsoleLine -Message 'Refreshing editor cache...' -ForegroundColor Cyan
            Set-StateEditor -Command $null
        }
        $editorCmd = Find-BestEditor
        Write-ConsoleLine -Message "Opening editor: $($editorCmd -join ' ')" -ForegroundColor Cyan
        try {
            & $editorCmd[0] @($editorCmd | Select-Object -Skip 1) $filePath
            Write-ConsoleLine -Message "Editor closed. Draft saved: $filePath" -ForegroundColor Green
        }
        catch {
            Write-ConsoleLine -Message "Warning: Could not open editor. File saved anyway: $filePath" -ForegroundColor Yellow
        }
    }
}

<#
.SYNOPSIS
    Find the best available GUI editor on the system.
    Returns an array: (executable, flag, ...) — ready to splat with &.
    Includes a --wait flag when the editor supports it so the script
    blocks until the user closes the file.
#>
function Find-BestEditor {
    # Check state cache first (skips the full tier scan on repeat runs)
    $cached = Get-StateEditor
    if ($cached) {
        return $cached
    }

    $desc = ''

    # Tier 1: Modern GUI editors with --wait support (VS Code family)
    $tier1 = @(
        @{ Exe = 'code';     WaitFlag = '--wait'; Desc = 'VS Code' }
        @{ Exe = 'cursor';   WaitFlag = '--wait'; Desc = 'Cursor' }
        @{ Exe = 'windsurf'; WaitFlag = '--wait'; Desc = 'Windsurf' }
        @{ Exe = 'trae';     WaitFlag = '--wait'; Desc = 'Trae' }
    )
    foreach ($e in $tier1) {
        if (Get-Command $e.Exe -ErrorAction SilentlyContinue) {
            $result = @($e.Exe, $e.WaitFlag)
            Set-StateEditor -Command $result -Desc $e.Desc
            return $result
        }
    }

    # Tier 2: JetBrains IDEs (--wait works for IntelliJ family)
    $tier2 = @(
        @{ Exe = 'idea';     WaitFlag = '--wait'; Desc = 'IntelliJ IDEA' }
    )
    foreach ($e in $tier2) {
        if (Get-Command $e.Exe -ErrorAction SilentlyContinue) {
            $result = @($e.Exe, $e.WaitFlag)
            Set-StateEditor -Command $result -Desc $e.Desc
            return $result
        }
    }

    # Tier 3: Sublime Text
    if (Get-Command 'subl' -ErrorAction SilentlyContinue) {
        $result = @('subl', '--wait')
        Set-StateEditor -Command $result -Desc 'Sublime Text'
        return $result
    }

    # Tier 4: Notepad++ (Windows)
    if (Get-Command 'notepad++' -ErrorAction SilentlyContinue) {
        $result = @('notepad++')
        Set-StateEditor -Command $result -Desc 'Notepad++'
        return $result
    }

    # Tier 5: GUI editors without --wait
    $tier5 = @(
        @{ Exe = 'gedit';   Desc = 'Gedit' }
        @{ Exe = 'kate';    Desc = 'Kate' }
        @{ Exe = 'gnome-text-editor'; Desc = 'GNOME Text Editor' }
    )
    foreach ($e in $tier5) {
        if (Get-Command $e.Exe -ErrorAction SilentlyContinue) {
            $result = @($e.Exe)
            Set-StateEditor -Command $result -Desc $e.Desc
            return $result
        }
    }

    # Tier 6: macOS TextEdit
    if ($IsMacOS) {
        $result = @('open', '-a', 'TextEdit')
        Set-StateEditor -Command $result -Desc 'TextEdit'
        return $result
    }

    # Tier 7: $env:EDITOR (user preference)
    if ($env:EDITOR) {
        $result = @($env:EDITOR)
        Set-StateEditor -Command $result -Desc "`$env:EDITOR"
        return $result
    }

    # Tier 8: Terminal editors (powerful fallbacks when no GUI available)
    $tier8 = @(
        @{ Exe = 'vim';  Desc = 'Vim' }
        @{ Exe = 'vi';   Desc = 'Vi' }
    )
    foreach ($e in $tier8) {
        if (Get-Command $e.Exe -ErrorAction SilentlyContinue) {
            $result = @($e.Exe)
            Set-StateEditor -Command $result -Desc $e.Desc
            return $result
        }
    }

    # Tier 9: Platform fallback
    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        $result = @('notepad.exe')
        Set-StateEditor -Command $result -Desc 'Windows Notepad'
        return $result
    }
    $result = @('nano')
    Set-StateEditor -Command $result -Desc 'Nano'
    return $result
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: refine — Improve a draft task using AI
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Refine {
    param(
        [string]$Path = '',
        [string]$Name = '',
        [string]$Audience = '',
        [string]$DomainContext = '',
        [switch]$InPlace,
        [string]$OutputPath = ''
    )

    $dirs = Get-TaskDirectories

    # Resolve input file
    $inputPath = ''
    if ($Path) {
        if (-not (Test-Path -LiteralPath $Path)) {
            Write-ConsoleLine -Message "Error: File not found: $Path" -ForegroundColor Red
            exit 1
        }
        $inputPath = (Resolve-Path $Path).Path
    }
    elseif ($Name) {
        $inputPath = Resolve-SingleTaskFile -Name $Name -State 'draft'
        if (-not $inputPath) { exit 1 }
    }
    else {
        # Default: latest .md file in 0draft (not 1-5.md placeholders)
        if (Test-Path $dirs.Draft) {
            $latest = Get-ChildItem -Path $dirs.Draft -File -ErrorAction SilentlyContinue |
                Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) } |
                Where-Object { $_.BaseName -notmatch '^[1-5]$' } |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if ($latest) {
                $inputPath = $latest.FullName
            }
        }
        if (-not $inputPath) {
            Write-ConsoleLine -Message "Error: No draft files found in 0draft/. Use -Path to specify a file." -ForegroundColor Red
            exit 1
        }
    }

    Write-ConsoleLine -Message "Refining: $inputPath" -ForegroundColor Cyan

    $taskInfo = Read-TaskContent -Path $inputPath
    $draftContent = $taskInfo.RawContent

    if ([string]::IsNullOrWhiteSpace($draftContent)) {
        Write-ConsoleLine -Message "Error: Task file is empty." -ForegroundColor Red
        exit 1
    }

    # Build refinement prompt
    $domainSection = ''
    if ($DomainContext) {
        $domainSection = "`nAdditional domain context:`n$DomainContext`n"
    }

    $audienceText = if ($Audience) { $Audience } else { 'AI agent that executes software engineering tasks' }

    $refinePrompt = @"
You are a task refinement assistant. Analyze and improve the following task draft so
it is clear, specific, and actionable for $audienceText.

$domainSection
Analyze the draft for:
1. Clarity — is the problem clearly stated with concrete examples?
2. Completeness — are there missing details, ambiguous requirements, or implicit assumptions?
3. Actionability — can the task be executed without clarification?
4. Structure — does it follow a logical flow?

Improve the draft:
- Add detail where ambiguous
- Clarify vague statements
- Suggest concrete implementation steps if appropriate
- Keep the SAME overall format as the original

OUTPUT ONLY THE IMPROVED DRAFT. Do NOT include conversational framing,
explanations of what you changed, markdown code fences, or any prefix
like "Here is the refined draft."

Original draft:
---
$draftContent
---
"@

    # Run agent
    $agentCommand = Get-AgentCommand -RepoRoot (Get-WorkspaceRoot)

    Write-ConsoleLine -Message "Calling AI agent for refinement..." -ForegroundColor Cyan

    try {
        $stdOutPath = [System.IO.Path]::GetTempFileName()
        $stdErrPath = [System.IO.Path]::GetTempFileName()

        try {
            $process = Start-AgentProcess -Executable $agentCommand.Executable `
                -BaseArgs $agentCommand.BaseArgs `
                -Prompt $refinePrompt `
                -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') `
                -WorkingDirectory $agentCommand.WorkingDirectory `
                -StdOutPath $stdOutPath `
                -StdErrPath $stdErrPath `
                -NoNewWindow `
                -Backend $agentCommand.Backend

            $timeoutSeconds = 300
            $completed = $process.WaitForExit($timeoutSeconds * 1000)
            if (-not $completed) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                Write-ConsoleLine -Message "Error: Refinement timed out after ${timeoutSeconds}s." -ForegroundColor Red
                exit 1
            }

            $refinedContent = ''
            if (Test-Path $stdOutPath) {
                $refinedContent = Get-Content -Path $stdOutPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            }

            if ($process.ExitCode -ne 0) {
                Write-ConsoleLine -Message "Warning: Agent exited with code $($process.ExitCode)" -ForegroundColor Yellow
            }
        }
        finally {
            Remove-Item $stdOutPath -ErrorAction SilentlyContinue
            Remove-Item $stdErrPath -ErrorAction SilentlyContinue
        }
    }
    catch {
        Write-ConsoleLine -Message "Error: Agent invocation failed: $_" -ForegroundColor Red
        exit 1
    }

    # Validate refined content
    if ([string]::IsNullOrWhiteSpace($refinedContent)) {
        Write-ConsoleLine -Message "Error: Agent returned empty content. Original file unchanged." -ForegroundColor Red
        exit 1
    }

    # Strip conversational prefixes
    $cleaned = $refinedContent -replace '^.*?(?:Here is|Here are|Below is|I have|The following is).*?(?:\n\n|\r\n\r\n)', ''
    $cleaned = $cleaned -replace '^```\w*\s*\n', ''
    $cleaned = $cleaned -replace '\n```\s*$', ''
    $cleaned = $cleaned.Trim()

    if (-not $cleaned) { $cleaned = $refinedContent.Trim() }

    # Determine output path
    $outputFile = if ($OutputPath) { $OutputPath }
                  elseif ($InPlace) { $inputPath }
                  else {
                      $baseName = [System.IO.Path]::GetFileNameWithoutExtension($inputPath)
                      $outDir = $dirs.Draft
                      $info = Resolve-UniquePath -Directory $outDir -BaseName "$baseName-refined" -Extension '.md'
                      $info.Path
                  }

    Set-Content -Path $outputFile -Value $cleaned -Encoding UTF8

    Write-ConsoleLine -Message "Refined task saved: $outputFile" -ForegroundColor Green
    Write-ConsoleLine -Message "  Original: $($draftContent.Length) chars → Refined: $($cleaned.Length) chars" -ForegroundColor DarkGray
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: assign — Move a task into 1ready/ for execution (alias: add)
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Assign {
    param(
        [string]$Path = '',
        [string]$Name = '',
        [switch]$Rename,
        [switch]$AutoApprove,
        [switch]$Force
    )

    $dirs = Get-TaskDirectories

    # Resolve input file
    $inputPath = ''
    if ($Path) {
        if (-not (Test-Path -LiteralPath $Path)) {
            Write-ConsoleLine -Message "Error: File not found: $Path" -ForegroundColor Red
            exit 1
        }
        $inputPath = (Resolve-Path $Path).Path
    }
    elseif ($Name) {
        $inputPath = Resolve-SingleTaskFile -Name $Name -State 'draft'
        if (-not $inputPath) { exit 1 }
    }
    else {
        Write-ConsoleLine -Message "Error: Specify a task file with -Path or -Name." -ForegroundColor Red
        exit 1
    }

    # Validate: reject if already in 1ready
    $readyDir = $dirs.Ready
    $normalizedReady = (Resolve-Path $readyDir -ErrorAction SilentlyContinue).Path
    $normalizedInput = (Resolve-Path $inputPath -ErrorAction SilentlyContinue).Path
    if ($normalizedInput.StartsWith($normalizedReady)) {
        Write-ConsoleLine -Message "Warning: File is already in 1ready/: $inputPath" -ForegroundColor Yellow
        exit 0
    }

    if (-not (Test-Path $readyDir)) {
        New-Item -ItemType Directory -Path $readyDir -Force | Out-Null
    }

    $fileItem = Get-Item $inputPath
    $destName = $fileItem.Name
    $needsRename = $Rename

    # Auto-detect if rename is needed: numeric or generic names
    if (-not $Rename -and $fileItem.BaseName -match '^\d+$') {
        $needsRename = $true
    }

    # Read content for rename and auto-approve injection
    $content = Get-Content -Path $inputPath -Raw -Encoding UTF8 -ErrorAction Stop

    if ($needsRename) {
        Write-ConsoleLine -Message "Generating descriptive name..." -ForegroundColor Cyan
        $renameScript = Join-Path $PSScriptRoot 'scripts\workers\rename.ps1'
        $descriptiveName = ''

        if (Test-Path $renameScript) {
            # Try rename.ps1 with retries
            $maxRetries = 2
            $retryCount = 0
            while (-not $descriptiveName -and $retryCount -lt $maxRetries) {
                try {
                    $generated = & $renameScript -FilePath $inputPath -ErrorAction Stop
                    if ($generated -and $generated -notmatch 'Error|Timeout') {
                        $descriptiveName = $generated.Trim()
                    }
                }
                catch {
                    $retryCount++
                    if ($retryCount -lt $maxRetries) { Start-Sleep -Seconds 2 }
                }
                if (-not $descriptiveName) { $retryCount++ }
            }
        }

        if (-not $descriptiveName) {
            # Fallback: use Get-TaskBaseName
            $taskInfo = Read-TaskContent -Path $inputPath
            $descriptiveName = Get-TaskBaseName -Title $taskInfo.Title -Description $taskInfo.Description -Prompt $taskInfo.Prompt -Fallback $fileItem.BaseName
        }

        if ($descriptiveName) {
            $normalized = $descriptiveName -replace '\s+', '-'
            $normalized = $normalized -replace '[^A-Za-z0-9._-]', '-' -replace '-+', '-'
            $normalized = $normalized.Trim(' ', '.', '-', '_')
            if ($normalized.Length -gt 60) { $normalized = $normalized.Substring(0, 60).Trim(' ', '.', '-', '_') }
            if ($normalized) {
                $destName = "$normalized$($fileItem.Extension)"
            }
        }
    }

    # Inject #auto-approve tag if requested
    if ($AutoApprove) {
        if ($content -notmatch '#auto-approve') {
            $content = "$content`n`n#auto-approve"
            Set-Content -Path $inputPath -Value $content -Encoding UTF8
            Write-ConsoleLine -Message "Added #auto-approve tag." -ForegroundColor DarkGray
        }
    }

    # Resolve destination with collision handling
    $destBaseName = [System.IO.Path]::GetFileNameWithoutExtension($destName)
    $destExt = [System.IO.Path]::GetExtension($destName)
    $destInfo = Resolve-UniquePath -Directory $readyDir -BaseName $destBaseName -Extension $destExt
    $destPath = $destInfo.Path

    Move-Item -Path $inputPath -Destination $destPath -Force
    Write-ConsoleLine -Message "Added to 1ready: $destPath" -ForegroundColor Green

    # Restore draft placeholders if we moved from 0draft
    if ($normalizedInput.StartsWith((Resolve-Path $dirs.Draft -ErrorAction SilentlyContinue).Path)) {
        Ensure-DraftPlaceholders -DraftDirectory $dirs.Draft
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: list — Show tasks grouped by state
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-List {
    param(
        [string]$State = 'all',
        [int]$Count = 20,
        [switch]$Brief,
        [switch]$NoPager
    )

    $dirs = Get-TaskDirectories

    # Map state labels to directory paths
    $stateMap = [ordered]@{
        'Draft'    = $dirs.Draft
        'Ready'    = $dirs.Ready
        'Working'  = $dirs.Working
        'Done'     = $dirs.Done
        'Review'   = $dirs.Review
        'Approved' = $dirs.Approved
        'Pushed'   = $dirs.Pushed
    }

    # Filter states if requested
    if ($State -ne 'all') {
        $targetState = Get-StateFromLabel $State
        if (-not $targetState) {
            Write-ConsoleLine -Message "Error: Unknown state '$State'. Use: draft, ready, working, done, review, approved, pushed, all" -ForegroundColor Red
            exit 1
        }
        $targetLabel = Get-StateLabel -DirPath $targetState
        $stateMap = [ordered]@{ $targetLabel = $stateMap[$targetLabel] }
    }

    # Build output lines as (text, color) tuples
    $outputLines = [System.Collections.Generic.List[object]]::new()
    $totalTasks = 0

    foreach ($label in $stateMap.Keys) {
        $dir = $stateMap[$label]
        if (-not (Test-Path $dir)) { continue }

        $recursive = ($label -in @('Done', 'Pushed'))
        $files = if ($recursive) {
            Get-ChildItem -Path $dir -Recurse -File -ErrorAction SilentlyContinue |
                Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }
        }
        else {
            Get-ChildItem -Path $dir -File -ErrorAction SilentlyContinue |
                Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }
        }

        # Exclude draft placeholders (1.md–5.md) from display
        if ($label -eq 'Draft') {
            $files = $files | Where-Object { $_.BaseName -notmatch '^[1-5]$' }
        }

        # Sort by LastWriteTime descending, limit if Count specified
        $files = @($files | Sort-Object LastWriteTime -Descending)
        if ($Count -gt 0) { $files = @($files | Select-Object -First $Count) }

        $totalTasks += $files.Count
        $stateColor = Get-StateColor -DirPath $dir

        if ($Brief) {
            $outputLines.Add([PSCustomObject]@{Text = "[$label] $($files.Count) task(s)"; Color = $stateColor })
            foreach ($f in $files) {
                $outputLines.Add([PSCustomObject]@{Text = "  $($f.Name)"; Color = $null })
            }
            continue
        }

        $outputLines.Add([PSCustomObject]@{Text = "`n=== $label ($($files.Count) task(s)) ==="; Color = $stateColor })

        if ($files.Count -eq 0) {
            $outputLines.Add([PSCustomObject]@{Text = "  (empty)"; Color = 'DarkGray' })
            continue
        }

        # Table header
        $nameHeader = 'File'.PadRight(45)
        $titleHeader = 'Title'.PadRight(50)
        $dateHeader = 'Modified'
        $outputLines.Add([PSCustomObject]@{Text = "  $nameHeader  $titleHeader  $dateHeader"; Color = 'DarkGray' })
        $outputLines.Add([PSCustomObject]@{Text = "  $('-'*45)  $('-'*50)  $('-'*19)"; Color = 'DarkGray' })

        foreach ($f in $files) {
            $nameField = $f.Name
            if ($nameField.Length -gt 44) { $nameField = $nameField.Substring(0, 41) + '...' }
            $nameField = $nameField.PadRight(45)

            $title = Get-TaskTitle -FilePath $f.FullName
            if ($title.Length -gt 48) { $title = $title.Substring(0, 45) + '...' }
            $titleField = $title.PadRight(50)

            $dateField = $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm')

            $outputLines.Add([PSCustomObject]@{Text = "  $nameField  $titleField  $dateField"; Color = $null })
        }
    }

    if (-not $Brief) {
        $outputLines.Add([PSCustomObject]@{Text = "`nTotal: $totalTasks task(s)"; Color = 'Cyan' })
    }

    # Determine whether to paginate
    $usePager = -not $NoPager
    if ($usePager) {
        # Only paginate when stdout is a terminal and output exceeds screen height
        $isTerminal = $false
        try { $isTerminal = [Console]::IsOutputRedirected -eq $false } catch { }
        if (-not $isTerminal) { $usePager = $false }
    }

    if ($usePager) {
        $pageHeight = 0
        try { $pageHeight = [Math]::Max(($host.UI.RawUI.WindowSize.Height - 2), 5) } catch { }
        if ($pageHeight -eq 0 -or $outputLines.Count -le $pageHeight) { $usePager = $false }
    }

    if ($usePager) {
        # Paginated display with "press any key" prompt
        $shown = 0
        $total = $outputLines.Count
        while ($shown -lt $total) {
            $end = [Math]::Min($shown + $pageHeight, $total) - 1
            for ($i = $shown; $i -le $end; $i++) {
                $item = $outputLines[$i]
                if ($item.Color) {
                    Write-ConsoleLine -Message $item.Text -ForegroundColor $item.Color
                } else {
                    Write-ConsoleLine -Message $item.Text
                }
            }
            $shown = $end + 1
            if ($shown -lt $total) {
                $remaining = $total - $shown
                Write-Host ("`n-- More --  ({0} lines remaining — Enter/space to continue, q to quit) " -f $remaining) -ForegroundColor DarkGray -NoNewline
                $key = $null
                try { $key = [Console]::ReadKey($true) } catch { break }
                if ($key.KeyChar -eq 'q') { Write-Host ""; break }
                # Move cursor up and clear the prompt line
                Write-Host "`r" -NoNewline
                Write-Host (' ' * 80) -NoNewline
                Write-Host "`r" -NoNewline
            }
        }
    }
    else {
        # No pagination — emit all lines directly
        foreach ($item in $outputLines) {
            if ($item.Color) {
                Write-ConsoleLine -Message $item.Text -ForegroundColor $item.Color
            } else {
                Write-ConsoleLine -Message $item.Text
            }
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: view — Display full task content
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-View {
    param(
        [string]$Path = '',
        [string]$Name = '',
        [switch]$Raw
    )

    $filePath = Resolve-SingleTaskFile -Path $Path -Name $Name
    if (-not $filePath) { exit 1 }

    if (-not (Test-Path -LiteralPath $filePath)) {
        Write-ConsoleLine -Message "Error: File not found: $filePath" -ForegroundColor Red
        exit 1
    }

    $fileItem = Get-Item $filePath
    $stateDir = Split-Path -Parent $filePath
    $stateLabel = Get-StateLabel -DirPath $stateDir

    # Check file size
    if ($fileItem.Length -gt 1MB) {
        Write-ConsoleLine -Message "Warning: Large file ($([math]::Round($fileItem.Length / 1KB, 1)) KB). Showing first 200 lines." -ForegroundColor Yellow
    }

    if ($Raw) {
        $content = Get-Content -Path $filePath -Raw -Encoding UTF8
        Write-Output $content
        return
    }

    # Display header
    Write-ConsoleLine -Message "`n════════════════════════════════════════════════════════════" -ForegroundColor DarkGray
    Write-ConsoleLine -Message "  Task: $($fileItem.Name)" -ForegroundColor Cyan
    Write-ConsoleLine -Message "  State: $stateLabel" -ForegroundColor (Get-StateColor -DirPath $stateDir)
    Write-ConsoleLine -Message "  Path: $filePath"
    Write-ConsoleLine -Message "  Size: $($fileItem.Length) bytes | Modified: $($fileItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
    Write-ConsoleLine -Message "════════════════════════════════════════════════════════════`n" -ForegroundColor DarkGray

    # Parse and display content
    $taskInfo = Read-TaskContent -Path $filePath

    if ($taskInfo.IsStructured) {
        Write-ConsoleLine -Message "Title:" -ForegroundColor Yellow
        Write-ConsoleLine -Message "  $($taskInfo.Title)" -ForegroundColor White
        Write-ConsoleLine -Message ""
        Write-ConsoleLine -Message "Description:" -ForegroundColor Yellow
        Write-ConsoleLine -Message "  $($taskInfo.Description)" -ForegroundColor White
        Write-ConsoleLine -Message ""
        Write-ConsoleLine -Message "Prompt:" -ForegroundColor Yellow
        Write-ConsoleLine -Message "─────────────────────────────────────────────────" -ForegroundColor DarkGray
        Write-Output $taskInfo.Prompt
    }
    else {
        # Display first 200 lines for large files
        $lineCount = 0
        $maxLines = 200
        Get-Content -Path $filePath -Encoding UTF8 -TotalCount $maxLines | ForEach-Object {
            Write-Output $_
            $lineCount++
        }
        $actualLines = @(Get-Content -Path $filePath -Encoding UTF8).Count
        if ($actualLines -gt $maxLines) {
            Write-ConsoleLine -Message "`n... (truncated: $actualLines total lines, showing first $maxLines)" -ForegroundColor DarkGray
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: cancel — Move task back to draft or remove it
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Cancel {
    param(
        [string]$Path = '',
        [string]$Name = '',
        [switch]$Remove,
        [switch]$Force
    )

    $filePath = Resolve-SingleTaskFile -Path $Path -Name $Name
    if (-not $filePath) { exit 1 }

    if (-not (Test-Path -LiteralPath $filePath)) {
        Write-ConsoleLine -Message "Error: File not found: $filePath" -ForegroundColor Red
        exit 1
    }

    $dirs = Get-TaskDirectories
    $stateDir = Split-Path -Parent $filePath

    # Determine which state the file is in
    $normalizedPath = (Resolve-Path $filePath).Path
    $donePath = (Resolve-Path $dirs.Done -ErrorAction SilentlyContinue).Path
    $pushedPath = (Resolve-Path $dirs.Pushed -ErrorAction SilentlyContinue).Path
    $workingPath = (Resolve-Path $dirs.Working -ErrorAction SilentlyContinue).Path

    # Reject cancelling completed/pushed tasks
    if ($normalizedPath.StartsWith($donePath) -or $normalizedPath.StartsWith($pushedPath)) {
        Write-ConsoleLine -Message "Error: Cannot cancel a completed/pushed task. Use git to revert changes." -ForegroundColor Red
        exit 1
    }

    # Warn if task is in working
    if ($normalizedPath.StartsWith($workingPath)) {
        Write-ConsoleLine -Message "WARNING: Task is in 2working and may still be executing!" -ForegroundColor Yellow
        if (-not $Force) {
            Write-ConsoleLine -Message "Use -Force to proceed anyway." -ForegroundColor Yellow
            exit 1
        }
    }

    if ($Remove) {
        # Confirm deletion
        if (-not $Force) {
            Write-ConsoleLine -Message "Permanently delete: $filePath ? [y/N]" -ForegroundColor Yellow -NoNewline
            $response = Read-Host
            if ($response -notmatch '^[yY]') {
                Write-ConsoleLine -Message "Cancelled." -ForegroundColor DarkGray
                exit 0
            }
        }
        Remove-Item -Path $filePath -Force
        Write-ConsoleLine -Message "Deleted: $filePath" -ForegroundColor Red
    }
    else {
        # Move back to 0draft
        $draftDir = $dirs.Draft
        if (-not (Test-Path $draftDir)) {
            New-Item -ItemType Directory -Path $draftDir -Force | Out-Null
        }

        $fileItem = Get-Item $filePath
        $ts = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
        $destBaseName = "cancelled-$ts-$($fileItem.BaseName)"
        $destInfo = Resolve-UniquePath -Directory $draftDir -BaseName $destBaseName -Extension $fileItem.Extension
        Move-Item -Path $filePath -Destination $destInfo.Path -Force
        Write-ConsoleLine -Message "Moved back to draft: $($destInfo.Path)" -ForegroundColor Yellow

        # Restore placeholders
        Ensure-DraftPlaceholders -DraftDirectory $draftDir
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: commit — Git commit workspace changes (no push)
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Commit {
    param(
        [string]$Message = '',
        [string]$AdditionalMessage = ''
    )

    $targetRepo = Get-TargetRepositoryRoot

    # Locate the shared commit script
    $commitScriptPath = Join-Path $PSScriptRoot 'scripts\workers\git-commit.ps1'
    if (-not (Test-Path $commitScriptPath)) {
        Write-ConsoleLine -Message "Error: Commit script not found: $commitScriptPath" -ForegroundColor Red
        exit 1
    }

    Write-ConsoleLine -Message "Target repo: $targetRepo" -ForegroundColor DarkGray

    # Build arguments for the shared commit script
    $commitArgs = @()
    if ($Message) { $commitArgs += '-Message'; $commitArgs += $Message }
    if ($AdditionalMessage) { $commitArgs += '-AdditionalMessage'; $commitArgs += $AdditionalMessage }

    # Delegate to shared script — it handles staging, message generation, and commit
    if ($commitArgs) {
        & $commitScriptPath @commitArgs
    } else {
        & $commitScriptPath
    }
    exit $LASTEXITCODE
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: push — Commit and push to remote
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Push {
    param(
        [string]$Message = '',
        [string]$AdditionalMessage = '',
        [switch]$Force,
        [switch]$NoPull
    )

    $targetRepo = Get-TargetRepositoryRoot

    # Locate the shared commit script
    $commitScriptPath = Join-Path $PSScriptRoot 'scripts\workers\git-commit.ps1'
    if (-not (Test-Path $commitScriptPath)) {
        Write-ConsoleLine -Message "Error: Commit script not found: $commitScriptPath" -ForegroundColor Red
        exit 1
    }

    Write-ConsoleLine -Message "Target repo: $targetRepo" -ForegroundColor DarkGray

    # Build arguments for the shared commit script
    $commitArgs = @('-Push')
    if ($Message) { $commitArgs += '-Message'; $commitArgs += $Message }
    if ($AdditionalMessage) { $commitArgs += '-AdditionalMessage'; $commitArgs += $AdditionalMessage }
    if ($Force) { $commitArgs += '-Force' }

    # Note: -NoPull is not forwarded — the shared script always pulls before
    # pushing. If the caller needs to skip the pull they should push manually.

    # Delegate to shared script — it handles staging, message generation, commit, pull, and push
    & $commitScriptPath @commitArgs
    exit $LASTEXITCODE
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand: fix — Pick a task from 1ready/ and execute it once
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Fix {
    param(
        [string]$Path = '',
        [string]$Name = '',
        [switch]$Latest
    )

    $dirs = Get-TaskDirectories
    $readyDir = $dirs.Ready

    # Resolve which task to execute
    $taskFile = ''
    if ($Path) {
        if (-not (Test-Path -LiteralPath $Path)) {
            Write-ConsoleLine -Message "Error: File not found: $Path" -ForegroundColor Red
            exit 1
        }
        $taskFile = (Resolve-Path $Path).Path
    }
    elseif ($Name) {
        $taskFile = Resolve-SingleTaskFile -Name $Name -State 'ready'
        if (-not $taskFile) { exit 1 }
    }
    else {
        # Pick a task from 1ready/ — oldest first (FIFO), or -Latest for newest
        if (-not (Test-Path $readyDir)) {
            Write-ConsoleLine -Message "No tasks in 1ready/." -ForegroundColor Yellow
            exit 0
        }
        $tasks = @(Get-ChildItem -Path $readyDir -File -ErrorAction SilentlyContinue |
            Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) })

        if ($tasks.Count -eq 0) {
            Write-ConsoleLine -Message "No tasks in 1ready/." -ForegroundColor Yellow
            exit 0
        }

        if ($Latest) {
            $taskFile = ($tasks | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
            Write-ConsoleLine -Message "Picked newest task: $(Split-Path -Leaf $taskFile)" -ForegroundColor DarkGray
        }
        else {
            # Default: oldest first (FIFO)
            $taskFile = ($tasks | Sort-Object LastWriteTime | Select-Object -First 1).FullName
            Write-ConsoleLine -Message "Picked oldest task: $(Split-Path -Leaf $taskFile)" -ForegroundColor DarkGray
        }
    }

    Write-ConsoleLine -Message "Fixing task: $taskFile" -ForegroundColor Cyan

    # Locate engineer.ps1
    $runCoworkerScript = Join-Path $PSScriptRoot 'scripts\engineer.ps1'
    if (-not (Test-Path $runCoworkerScript)) {
        Write-ConsoleLine -Message "Error: engineer.ps1 not found at $runCoworkerScript" -ForegroundColor Red
        exit 1
    }

    # Launch engineer.ps1 with the task file, streaming output to the terminal
    $powerShell = if ($IsWindows) {
        if (Get-Command 'pwsh.exe' -ErrorAction SilentlyContinue) { 'pwsh.exe' }
        else { 'powershell.exe' }
    }
    else {
        'pwsh'
    }

    Write-ConsoleLine -Message "Starting Coworker task runner..." -ForegroundColor Cyan

    $process = Start-Process -FilePath $powerShell `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runCoworkerScript, '-TaskFile', $taskFile) `
        -PassThru -NoNewWindow -Wait

    if ($process.ExitCode -ne 0) {
        Write-ConsoleLine -Message "Task runner exited with code $($process.ExitCode)." -ForegroundColor Yellow
    }
    else {
        Write-ConsoleLine -Message "Task runner completed." -ForegroundColor Green
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Subcommand-specific help
# ═══════════════════════════════════════════════════════════════════════════════

function Show-CommandHelp {
    param([string]$Cmd)

    switch ($Cmd) {
        'draft' {
            @'
Usage: coworker draft [options]

Create or edit a task draft in 0draft/.

Options:
  -Title <str>       Task title (creates structured format)
  -Content <str>     Task content / prompt body
  -Prompt <str>      Alias for -Content
  -Edit              Open the draft in an editor after creation
  -Name <str>        Specify the filename (without .md extension)
  -RefreshEditor     Re-detect available editor (ignore state cache)

Examples:
  coworker draft -Title "Fix login timeout" -Content "The login..."
  coworker draft -Edit
  coworker draft -Name my-feature -Title "My Feature"
'@
        }
        'refine' {
            @'
Usage: coworker refine [options]

Use AI to analyze and improve a draft task. Adds detail, clarifies
ambiguity, and suggests implementation steps.

Options:
  -Path <path>       Task file to refine (default: latest .md in 0draft/)
  -Name <str>        Find draft by name
  -Audience <str>    Who the task is for (default: AI agent)
  -DomainContext <str>  Additional context for refinement
  -InPlace           Overwrite original file (default: creates -refined copy)
  -OutputPath <path> Custom output path

Examples:
  coworker refine -Path 0draft/my-task.md
  coworker refine -Name my-task -InPlace
  coworker refine -Audience "Senior engineer"
'@
        }
        'assign' {
            @'
Usage: coworker assign [options]

Move a task file into 1ready/ for execution by the Coworker runner.

Options:
  -Path <path>    File to add (required, unless -Name is used)
  -Name <str>     Find draft by name in 0draft/
  -Rename         AI-generate a descriptive kebab-case filename
  -AutoApprove    Add #auto-approve tag (task goes straight to 5approved)
  -Force          Skip warnings (e.g. if task is already in 2working)

Examples:
  coworker assign -Path 0draft/my-task.md
  coworker assign -Path 0draft/7.md -Rename
  coworker assign -Name my-task -AutoApprove

Note: "add" is a backward-compatible alias for "assign".
'@
        }
        'add' {
            @'
Usage: coworker add [options]

Move a task file into 1ready/ for execution by the Coworker runner.
(DEPRECATED: use "assign" instead. "add" is kept for backward compatibility.)

Options:
  -Path <path>    File to add (required, unless -Name is used)
  -Name <str>     Find draft by name in 0draft/
  -Rename         AI-generate a descriptive kebab-case filename
  -AutoApprove    Add #auto-approve tag (task goes straight to 5approved)
  -Force          Skip warnings (e.g. if task is already in 2working)

Examples:
  coworker assign -Path 0draft/my-task.md
  coworker assign -Path 0draft/7.md -Rename
  coworker assign -Name my-task -AutoApprove
'@
        }
        'list' {
            @'
Usage: coworker list [options]

Show tasks across the Coworker state machine.
Output is paginated automatically when it exceeds terminal height.

Options:
  -State <str>  Filter by state: draft, ready, working, done, review,
                approved, pushed, all (default)
  -Count <int>  Limit to N most recent tasks per state (default: 20, use 0 for all)
  -Brief        Compact output (filenames only)
  -NoPager      Disable pagination (print everything at once)

Examples:
  coworker list
  coworker list -State ready
  coworker list -State done -Count 10
  coworker list -Brief
  coworker list -NoPager
'@
        }
        'view' {
            @'
Usage: coworker view [options]

Display full content of a task file.

Options:
  -Path <path>  Full or relative path to the task file
  -Name <str>   Find task by name across all state directories
  -Raw          Output raw content without header/metadata

Examples:
  coworker view -Path 0draft/my-task.md
  coworker view -Name my-task
  coworker view -Path 3done/2026/0722/my-task.md -Raw
'@
        }
        'cancel' {
            @'
Usage: coworker cancel [options]

Move a task back to 0draft/ or permanently remove it.
Cannot cancel tasks in 3done or 6git-pushed (use git to revert).

Options:
  -Path <path>  Task file to cancel
  -Name <str>   Find task by name
  -Remove       Permanently delete the file
  -Force        Skip confirmation prompts

Examples:
  coworker cancel -Name my-task
  coworker cancel -Path 1ready/broken-task.md -Remove -Force
'@
        }
        'commit' {
            @'
Usage: coworker commit [options]

Stage and commit all changes in the target repository.
Does NOT push to remote.

The commit message is generated by an AI agent that analyzes the
staged diff and produces a conventional-commits message.

Options:
  -Message <str>             Override the AI-generated commit message
  -AdditionalMessage <str>   Extra text appended to the commit body
                             (e.g. "Task: fix-crawl.md")

Examples:
  coworker commit
  coworker commit -Message "fix(auth): resolve token expiry"
  coworker commit -AdditionalMessage "Task: fix-crawl-sql-formats.md"
'@
        }
        'push' {
            @'
Usage: coworker push [options]

Stage, commit, pull, and push all changes to the remote.
The commit message is AI-generated from the staged diff.

Options:
  -Message <str>             Override the AI-generated commit message
  -AdditionalMessage <str>   Extra text appended to the commit body
  -Force                     Use --force-with-lease on push
  -NoPull                    Skip git pull before pushing

Examples:
  coworker push
  coworker push -Message "fix(coworker): resolve issues"
  coworker push -AdditionalMessage "Task: fix-crawl.md"
  coworker push -Force
'@
        }
        'fix' {
            @'
Usage: coworker fix [options]

Pick a task from 1ready/ and execute it once via the Coworker
task runner (engineer.ps1). The task goes through the full
pipeline: rename, execute, move to done, and auto-commit.

Without options, picks the oldest task (FIFO) from 1ready/.

Options:
  -Path <path>   Execute a specific task file (can be outside 1ready/)
  -Name <str>    Find and execute a task by name in 1ready/
  -Latest        Pick the most recently modified task instead of oldest

Examples:
  coworker fix
  coworker fix -Latest
  coworker fix -Name my-task
  coworker fix -Path 0draft/experimental-task.md
'@
        }
        'review' {
            @'
Usage: coworker review [options]

Review .issues.md files — interactively or inline (non-interactive).

Interactive mode (default): Lists files from tasks/issues/draft/ and
tasks/issues/review/, then lets you browse issues, set review decisions,
add notes, and save.

Inline mode (-Inline): Requires -Path.  Runs AI batch review on all
issues, writes decisions, and moves the file to 1ready/ without any
interactive prompts.

Options:
  -Path <path>   Specific .issues.md file to review
  -Name <str>    Find issues file by partial name
  -List           List available files and exit
  -All            Include review/done/ files in the listing
  -Inline         Non-interactive: AI review all issues → move to 1ready/
  -AutoApprove    With -Inline: inject #auto-approve tag

Inline examples:
  coworker review -Inline -Path tasks/issues/draft/my-issues.issues.md
  coworker review -Inline -Path review/issues.issues.md -AutoApprove

Keyboard shortcuts (interactive mode):
  1-6             Set review decision (toggle to deselect)
  n / p           Next / previous issue
  N / P           Next / previous file
  e               Edit review notes for current issue
  a               AI review current issue
  A               AI review ALL issues in file
  v               Toggle single-issue / all-issues view
  m               Mark file as done → moves to 1ready/ for execution
  d               Discard file → review/done/discard/
  q               Quit
  ?               Show help
  State saves automatically after every change.

Decisions:
  1 = ACCEPT      2 = ACCEPT with improvements
  3 = DEFER       4 = WONTFIX
  5 = REJECT      6 = DUPLICATE

Examples:
  coworker review
  coworker review -Path tasks/issues/draft/my-issues.issues.md
  coworker review -Name form-filling
  coworker review -List
  coworker review -Inline -Path tasks/issues/draft/my-issues.issues.md
'@
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Main dispatch
# ═══════════════════════════════════════════════════════════════════════════════

# Parse subcommand-specific arguments from $Remaining
function Parse-SubcommandArgs {
    param([string[]]$ArgList)

    $parsed = @{}
    $i = 0
    while ($i -lt $ArgList.Count) {
        $arg = $ArgList[$i]
        switch -Wildcard ($arg) {
            '-Path'          { $parsed['Path'] = $ArgList[++$i]; break }
            '-Name'          { $parsed['Name'] = $ArgList[++$i]; break }
            '-Title'         { $parsed['Title'] = $ArgList[++$i]; break }
            '-Content'       { $parsed['Content'] = $ArgList[++$i]; break }
            '-Prompt'        { $parsed['Prompt'] = $ArgList[++$i]; break }
            '-State'         { $parsed['State'] = $ArgList[++$i]; break }
            '-Audience'      { $parsed['Audience'] = $ArgList[++$i]; break }
            '-DomainContext' { $parsed['DomainContext'] = $ArgList[++$i]; break }
            '-OutputPath'    { $parsed['OutputPath'] = $ArgList[++$i]; break }
            '-Message'           { $parsed['Message'] = $ArgList[++$i]; break }
            '-AdditionalMessage' { $parsed['AdditionalMessage'] = $ArgList[++$i]; break }
            '-TaskRef'           { $parsed['TaskRef'] = $ArgList[++$i]; break }
            '-Count'         { $parsed['Count'] = [int]$ArgList[++$i]; break }
            '-FileName'      { $parsed['FileName'] = $ArgList[++$i]; break }
            '-Edit'          { $parsed['Edit'] = $true; break }
            '-RefreshEditor' { $parsed['RefreshEditor'] = $true; break }
            '-Rename'        { $parsed['Rename'] = $true; break }
            '-AutoApprove'   { $parsed['AutoApprove'] = $true; break }
            '-Force'         { $parsed['Force'] = $true; break }
            '-Remove'        { $parsed['Remove'] = $true; break }
            '-Raw'           { $parsed['Raw'] = $true; break }
            '-Brief'         { $parsed['Brief'] = $true; break }
            '-NoPager'       { $parsed['NoPager'] = $true; break }
            '-Long'          { $parsed['Long'] = $true; break }
            '-InPlace'       { $parsed['InPlace'] = $true; break }
            '-NoPull'        { $parsed['NoPull'] = $true; break }
            '-List'          { $parsed['List'] = $true; break }
            '-All'           { $parsed['All'] = $true; break }
            '-Inline'        { $parsed['Inline'] = $true; break }
            '-AutoApprove'   { $parsed['AutoApprove'] = $true; break }
            '-Help'          { $parsed['Help'] = $true; break }
            '-h'             { $parsed['Help'] = $true; break }
            '--help'         { $parsed['Help'] = $true; break }
            default {
                # Positional argument — treat as Path if not already set
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

# Parse args
$subArgs = Parse-SubcommandArgs -ArgList $Remaining

# Show help if no command
if (-not $Command) {
    Write-ConsoleLine -Message $script:HelpText -ForegroundColor Cyan
    exit 0
}

# Show command-specific help if -Help flag
if ($subArgs['Help']) {
    Show-CommandHelp -Cmd $Command
    exit 0
}

# Show command help if no meaningful args and it's a command that requires args
$needsArgs = @('refine', 'assign', 'add', 'view', 'cancel', 'commit', 'push')
if ($Command -in $needsArgs) {
    $hasArgs = ($subArgs['Path'] -or $subArgs['Name'] -or
                $subArgs['Message'] -or $subArgs['Edit'])
    if ($Command -eq 'commit' -and -not $hasArgs) {
        # commit can run without args (auto-generates message)
        $hasArgs = $true
    }
    if ($Command -eq 'push' -and -not $hasArgs) {
        # push can run without args
        $hasArgs = $true
    }
    if ($Command -eq 'review' -and -not $hasArgs) {
        # review can run without args (shows file picker)
        $hasArgs = $true
    }
    if (-not $hasArgs) {
        Write-ConsoleLine -Message "Command '$Command' requires arguments. Use -Help for usage." -ForegroundColor Yellow
        Show-CommandHelp -Cmd $Command
        exit 1
    }
}

# Helper: get a value from the parsed args hash, with a default
function Get-Arg {
    param($Hash, [string]$Key, $Default = '')
    if ($Hash.ContainsKey($Key)) { return $Hash[$Key] }
    return $Default
}
function Get-SwitchArg {
    param($Hash, [string]$Key)
    if ($Hash.ContainsKey($Key)) { return $Hash[$Key] }
    return $false
}

# Dispatch
$Command = $Command.ToLowerInvariant()
try {
    switch ($Command) {
        'draft' {
            Invoke-Draft -Title (Get-Arg $subArgs 'Title') `
                -Content (Get-Arg $subArgs 'Content') `
                -Prompt (Get-Arg $subArgs 'Prompt') `
                -Edit:(Get-SwitchArg $subArgs 'Edit') `
                -Name (Get-Arg $subArgs 'Name') `
                -RefreshEditor:(Get-SwitchArg $subArgs 'RefreshEditor')
        }
        'refine' {
            Invoke-Refine -Path (Get-Arg $subArgs 'Path') `
                -Name (Get-Arg $subArgs 'Name') `
                -Audience (Get-Arg $subArgs 'Audience') `
                -DomainContext (Get-Arg $subArgs 'DomainContext') `
                -InPlace:(Get-SwitchArg $subArgs 'InPlace') `
                -OutputPath (Get-Arg $subArgs 'OutputPath')
        }
        'assign' {
            Invoke-Assign -Path (Get-Arg $subArgs 'Path') `
                -Name (Get-Arg $subArgs 'Name') `
                -Rename:(Get-SwitchArg $subArgs 'Rename') `
                -AutoApprove:(Get-SwitchArg $subArgs 'AutoApprove') `
                -Force:(Get-SwitchArg $subArgs 'Force')
        }
        'add' {
            Invoke-Assign -Path (Get-Arg $subArgs 'Path') `
                -Name (Get-Arg $subArgs 'Name') `
                -Rename:(Get-SwitchArg $subArgs 'Rename') `
                -AutoApprove:(Get-SwitchArg $subArgs 'AutoApprove') `
                -Force:(Get-SwitchArg $subArgs 'Force')
        }
        'list' {
            Invoke-List -State (Get-Arg $subArgs 'State' 'all') `
                -Count (Get-Arg $subArgs 'Count' 20) `
                -Brief:(Get-SwitchArg $subArgs 'Brief') `
                -NoPager:(Get-SwitchArg $subArgs 'NoPager')
        }
        'view' {
            Invoke-View -Path (Get-Arg $subArgs 'Path') `
                -Name (Get-Arg $subArgs 'Name') `
                -Raw:(Get-SwitchArg $subArgs 'Raw')
        }
        'cancel' {
            Invoke-Cancel -Path (Get-Arg $subArgs 'Path') `
                -Name (Get-Arg $subArgs 'Name') `
                -Remove:(Get-SwitchArg $subArgs 'Remove') `
                -Force:(Get-SwitchArg $subArgs 'Force')
        }
        'commit' {
            Invoke-Commit -Message (Get-Arg $subArgs 'Message') `
                -AdditionalMessage (Get-Arg $subArgs 'AdditionalMessage')
        }
        'push' {
            Invoke-Push -Message (Get-Arg $subArgs 'Message') `
                -AdditionalMessage (Get-Arg $subArgs 'AdditionalMessage') `
                -Force:(Get-SwitchArg $subArgs 'Force') `
                -NoPull:(Get-SwitchArg $subArgs 'NoPull')
        }
        'fix' {
            Invoke-Fix -Path (Get-Arg $subArgs 'Path') `
                -Name (Get-Arg $subArgs 'Name') `
                -Latest:(Get-SwitchArg $subArgs 'Latest')
        }
        'review' {
            Invoke-Review -Path (Get-Arg $subArgs 'Path') `
                -Name (Get-Arg $subArgs 'Name') `
                -List:(Get-SwitchArg $subArgs 'List') `
                -All:(Get-SwitchArg $subArgs 'All') `
                -Inline:(Get-SwitchArg $subArgs 'Inline') `
                -AutoApprove:(Get-SwitchArg $subArgs 'AutoApprove')
        }
        default {
            Write-ConsoleLine -Message "Unknown command: $Command" -ForegroundColor Red
            Write-ConsoleLine -Message $script:HelpText -ForegroundColor Cyan
            exit 1
        }
    }
}
catch {
    Write-ConsoleLine -Message "Error: $_" -ForegroundColor Red
    if ($_.ScriptStackTrace) {
        Write-ConsoleLine -Message $_.ScriptStackTrace -ForegroundColor DarkGray
    }
    exit 1
}
