#!/usr/bin/env pwsh
<#
.SYNOPSIS
Runs every agent-scenario task defined in tasks/ (recursive into subdirectories).

.DESCRIPTION
Auto-discovers and executes task markdown files recursively in the tasks/
directory. Tasks are organized in category subdirectories:
  - tasks/real-world/generic/  — universal scenarios (any browser agent)
  - tasks/real-world/browser4/ — scenarios requiring browser4-specific features
  - tasks/mock-site/           — scenarios requiring the local MockSite server
Each task is run via run-task.ps1, which combines the task description with
the shared usability-evaluation prompt and invokes the Claude Code agent.

Without arguments the script runs every discovered task.  Pass -List to see
what would run, or name one or more tasks to run a subset.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1

    Run every task in tasks/.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -List

    List discovered tasks without running them.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 search-summary amazon

    Run only the two named tasks (with or without .md extension).

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category generic

    Run only tasks in tasks/real-world/generic/.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category browser4 -List

    List only browser4-specific tasks.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -Category mock-site

    Run only tasks requiring MockSite.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests.ps1 -FailFast

    Stop after the first failing task.

.NOTES
Each task invokes an agent CLI (claude or kimi), requires an active LLM subscription,
and may take several minutes.  Run them selectively during development.
#>

[CmdletBinding()]
param(
    # One or more task names to run (e.g. "search-summary", "amazon.md").
    # When omitted every discovered task runs.
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Tasks,

    # Stop after the first failure instead of continuing.
    [switch] $FailFast,

    # Filter tasks by category: generic, browser4, real-world, mock-site, or all (default).
    [ValidateSet('generic', 'browser4', 'real-world', 'mock-site', 'all')]
    [string] $Category = 'all',

    # List discovered tasks and exit.
    [switch] $List,

    # Run in production mode (browser4-cli instead of cargo run).
    [switch] $Production,

    # Run tasks silently (forwarded to run-task.ps1).
    [switch] $Silent,

    # Skip the browser4-cli version check (forwarded to run-task.ps1).
    [switch] $SkipVersionCheck,

    # Maximum minutes to wait for each individual task (default 60 = 1 hour).
    # On timeout the task process is killed and the task is marked as
    # TIMEOUT (exit code 124).  Set to 0 to disable the timeout.
    [int] $TimeoutMinutes = 60,

    # Override the agent CLI to use (claude, kimi, or opencode).
    # When empty, auto-detects. Forwarded to run-task.ps1.
    [string] $Agent = '',

    # Custom tasks directory. When set, overrides the default tasks/ directory.
    # Used by test.ps1 rws --dir to run tasks from an arbitrary directory.
    [string] $TasksDir = ''
)

$ErrorActionPreference = 'Stop'
$script:StartTime = Get-Date

# Dot-source shared helpers for Read-TaskFile (used in -List mode).
# Safe: this script already sets ErrorActionPreference=Stop; default dev mode is harmless.
. "$PSScriptRoot/common.ps1"

# ═══════════════════════════════════════════════════════════════════════════════
# Discovery — every .md in tasks/ (recursive into subdirectories)
# ═══════════════════════════════════════════════════════════════════════════════

$script:ScriptsDir = $PSScriptRoot
$script:TasksDir   = if ($TasksDir) { $TasksDir } else { Join-Path $ScriptsDir '..' 'tasks' }
$script:RunnerPath = Join-Path $ScriptsDir 'run-task.ps1'
# Repo root is set by common.ps1 (dot-sourced above at line 103).
# $script:RepoRoot is already available — no need to recompute.

$script:DiscoveredFiles = Get-ChildItem -Path $TasksDir -Filter '*.md' -Recurse `
    | Sort-Object Name
$script:Discovered = $DiscoveredFiles | ForEach-Object { $_.Name }
$script:TaskPathMap = @{}
$DiscoveredFiles | ForEach-Object { $TaskPathMap[$_.Name] = $_.FullName }

if ($Discovered.Count -eq 0) {
    Write-Host 'No task files found in tasks/.' -ForegroundColor Yellow
    exit 0
}

# ── Category filter ──────────────────────────────────────────────────────────
if ($Category -ne 'all') {
    $script:DiscoveredFiles = $DiscoveredFiles | Where-Object {
        Test-TaskCategory -FilePath $_.FullName -Category $Category
    }
    $script:Discovered = $DiscoveredFiles | ForEach-Object { $_.Name }
    $script:TaskPathMap = @{}
    $DiscoveredFiles | ForEach-Object { $TaskPathMap[$_.Name] = $_.FullName }

    if ($Discovered.Count -eq 0) {
        Write-Host "No task files found for category '$Category'." -ForegroundColor Yellow
        exit 0
    }
}

# Resolve which tasks to run — accept names with or without .md extension
if ($Tasks -and $Tasks.Count -gt 0) {
    $script:Selected = Resolve-TaskNames -Requested $Tasks -Discovered $Discovered

    if ($Selected.Count -eq 0) {
        Write-Host 'No matching tasks to run.' -ForegroundColor Yellow
        Write-Host "Discovered tasks: $($Discovered -join ', ')"
        exit 0
    }
} else {
    $script:Selected = $Discovered
}

# ═══════════════════════════════════════════════════════════════════════════════
# List mode
# ═══════════════════════════════════════════════════════════════════════════════

if ($List) {
    Write-Host 'Agent scenario tasks:' -ForegroundColor Cyan
    if ($Category -ne 'all') {
        Write-Host "  Category: $Category" -ForegroundColor DarkGray
    }
    Write-Host ''
    foreach ($name in $Discovered) {
        $marker = if ($name -in $Selected) { ' [selected]' } else { '' }
        $taskPath = $TaskPathMap[$name]

        # Determine category from path
        $cat = ''
        if ($taskPath -match '\\mock-site\\')        { $cat = 'mock-site' }
        elseif ($taskPath -match '\\browser4\\')     { $cat = 'browser4' }
        elseif ($taskPath -match '\\generic\\')      { $cat = 'generic' }
        $catTag = if ($cat) { "[$cat]" } else { '' }

        # Extract the heading and first content line as a quick description.
        $desc = ''
        try {
            $task = Read-TaskFile -Path $taskPath
            $firstLine = ($task.Body -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -First 1)
            if ($firstLine) {
                $desc = " -- $($firstLine.Trim())"
            }
        } catch {
            # Silently skip unparseable files in list mode
        }

        Write-Host "  $catTag $name$marker$desc"
    }
    Write-Host ''
    Write-Host "$($Selected.Count) task(s) selected out of $($Discovered.Count) discovered."
    exit 0
}

# ═══════════════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════════════

function Write-Banner {
    param([string] $Text)
    $line = '=' * [Math]::Min(72, $Text.Length + 8)
    Write-Host ''
    Write-Host $line -ForegroundColor Cyan
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host $line -ForegroundColor Cyan
}

function Write-Section {
    param([string] $Text)
    Write-Host ''
    Write-Host "-- $Text --" -ForegroundColor Yellow
}

# ═══════════════════════════════════════════════════════════════════════════════
# Pre-flight: check that an agent CLI (claude, kimi, or opencode) and run-task.ps1 are available
# ═══════════════════════════════════════════════════════════════════════════════

$knownAgents = @('claude', 'kimi', 'opencode')
$agentAvailable = $false
if ($Agent) {
    $agentAvailable = $null -ne (Get-Command $Agent -ErrorAction SilentlyContinue)
    if (-not $agentAvailable) {
        Write-Host "ERROR: Specified agent '$Agent' not found on PATH." -ForegroundColor Red
        exit 1
    }
} else {
    foreach ($a in $knownAgents) {
        if ($null -ne (Get-Command $a -ErrorAction SilentlyContinue)) {
            $agentAvailable = $true
            break
        }
    }
}
if (-not $agentAvailable) {
    Write-Host 'WARNING: no agent CLI (claude, kimi, or opencode) found on PATH.' -ForegroundColor Yellow
    Write-Host 'Each task invokes an agent CLI.  Without one, every task will fail.'
    Write-Host ''
}

if (-not (Test-Path -LiteralPath $RunnerPath -PathType Leaf)) {
    Write-Host "ERROR: Task runner not found: $RunnerPath" -ForegroundColor Red
    exit 1
}

# ═══════════════════════════════════════════════════════════════════════════════
# Run
# ═══════════════════════════════════════════════════════════════════════════════

$Results = [System.Collections.ArrayList]::new()
$Passed  = 0
$Failed  = 0

$bannerTitle = "Agent Scenarios ($($Selected.Count) task(s))"
if ($Category -ne 'all') { $bannerTitle += " — $Category" }
Write-Banner $bannerTitle

foreach ($name in $Selected) {
    $taskPath = $TaskPathMap[$name]

    Write-Section $name
    $start = Get-Date
    $exitCode = 0

    try {
        # Run the task via run-task.ps1.
        # Working directory is the repo root so the agent CLI finds the project.
        Push-Location $RepoRoot
        try {
            if ($Production) {
                $env:BROWSER4CLI_MODE = 'production'
            }
            $pwshArgs = @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', $RunnerPath,
                '-TaskFile', $taskPath
            )
            if ($Silent) {
                $pwshArgs += '-Silent'
            }
            if ($SkipVersionCheck) {
                $pwshArgs += '-SkipVersionCheck'
            }
            # Always forward the timeout value (even 0) so run-task.ps1
            # receives the caller's intent rather than its own default.
            $pwshArgs += '-TimeoutMinutes', $TimeoutMinutes
            if ($Agent) {
                $pwshArgs += '-Agent', $Agent
            }
            & pwsh @pwshArgs
            $exitCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
    } catch {
        $exitCode = 1
        Write-Host "ERROR: $_" -ForegroundColor Red
    }

    $duration = (Get-Date) - $start

    if ($exitCode -eq 0) {
        $status = 'PASS'
        $color  = 'Green'
        $icon   = '[OK]'
        $Passed++
    } elseif ($exitCode -eq 124) {
        $status = 'TIMEOUT'
        $color  = 'Yellow'
        $icon   = '[TIMEOUT]'
        $Failed++
    } else {
        $status = 'FAIL'
        $color  = 'Red'
        $icon   = '[FAIL]'
        $Failed++
    }

    [void] $Results.Add(@{
        Name     = $name
        Status   = $status
        Duration = $duration
    })

    Write-Host "$icon $name ($status) -- $(Format-Duration $duration)" -ForegroundColor $color

    if ($exitCode -ne 0 -and $FailFast) {
        Write-Host 'FailFast enabled -- stopping.' -ForegroundColor Red
        break
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════════════════

$totalDuration = (Get-Date) - $script:StartTime

Write-Banner 'Results'

Write-Host ''
$total = $Passed + $Failed
foreach ($entry in $Results) {
    $icon  = if ($entry.Status -eq 'PASS') { '[OK]     ' } elseif ($entry.Status -eq 'TIMEOUT') { '[TIMEOUT]' } else { '[FAIL]   ' }
    $color = if ($entry.Status -eq 'PASS') { 'Green' } elseif ($entry.Status -eq 'TIMEOUT') { 'Yellow' } else { 'Red' }
    Write-Host "  $icon $($entry.Name) -- $(Format-Duration $entry.Duration)" -ForegroundColor $color
}

Write-Host ''
$passColor = if ($Failed -eq 0) { 'Green' } else { 'Red' }
Write-Host "Total: " -NoNewline
Write-Host "$Passed passed" -NoNewline -ForegroundColor Green
Write-Host ", " -NoNewline
Write-Host "$Failed failed" -NoNewline -ForegroundColor $(if ($Failed -gt 0) { 'Red' } else { 'White' })
Write-Host " in $(Format-Duration $totalDuration)" -ForegroundColor Cyan

if ($Failed -gt 0) {
    Write-Host ''
    $timeoutCount = ($Results | Where-Object { $_.Status -eq 'TIMEOUT' }).Count
    $msg = 'Some tasks failed.'
    if ($timeoutCount -gt 0) {
        $msg += " ($timeoutCount timed out)"
    }
    Write-Host $msg -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'All tasks passed.' -ForegroundColor Green
exit 0
