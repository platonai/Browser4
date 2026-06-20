#!/usr/bin/env pwsh
<#
.SYNOPSIS
Runs every agent-scenario test script in this directory.

.DESCRIPTION
Auto-discovers and executes the PS1 scenario scripts (excluding common.ps1 and
itself).  Each script invokes claude with a usability-evaluation prompt, so a
working Claude Code installation is required.

Without arguments the script runs every discovered scenario.  Pass -List to see
what would run, or name one or more scripts to run a subset.

.EXAMPLE
./cli/browser4-cli/tests/scripts/run-tests.ps1

    Run every scenario in this directory.

.EXAMPLE
./cli/browser4-cli/tests/scripts/run-tests.ps1 -List

    List discovered scenarios without running them.

.EXAMPLE
./cli/browser4-cli/tests/scripts/run-tests.ps1 search-summary.ps1 amazon.ps1

    Run only the two named scenarios.

.EXAMPLE
./cli/browser4-cli/tests/scripts/test-runner.ps1 -FailFast

    Stop after the first failing scenario.

.NOTES
Each scenario invokes claude (Claude Code), requires an active LLM subscription,
and may take several minutes.  Run them selectively during development.
#>

[CmdletBinding()]
param(
    # One or more scenario script names to run (e.g. "search-summary.ps1").
    # When omitted every discovered script runs.
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Scenarios,

    # Stop after the first failure instead of continuing.
    [switch] $FailFast,

    # List discovered scenarios and exit.
    [switch] $List
)

$ErrorActionPreference = 'Stop'
$script:StartTime = Get-Date

# ═══════════════════════════════════════════════════════════════════════════════
# Discovery — every .ps1 in this directory except common.ps1 and this script
# ═══════════════════════════════════════════════════════════════════════════════

$script:ScriptsDir = $PSScriptRoot
$script:OwnName    = [System.IO.Path]::GetFileName($PSCommandPath)
# Repo root is 3 levels up from scripts/ (scripts -> tests -> browser4-cli -> repo root)
$script:RepoRoot   = Resolve-Path "$ScriptsDir/../../.."

$script:Discovered = Get-ChildItem -Path $ScriptsDir -Filter '*.ps1' `
    | Where-Object {
        $_.Name -ne 'common.ps1' -and
        $_.Name -ne $script:OwnName
    } `
    | Sort-Object Name `
    | ForEach-Object { $_.Name }

if ($Discovered.Count -eq 0) {
    Write-Host 'No scenario scripts found.' -ForegroundColor Yellow
    exit 0
}

# Resolve which scripts to run
if ($Scenarios -and $Scenarios.Count -gt 0) {
    $script:Selected = foreach ($name in $Scenarios) {
        $base = [System.IO.Path]::GetFileName($name)
        if ($base -in $Discovered) {
            $base
        } else {
            Write-Host "WARNING: '$base' not found among discovered scripts, skipping." -ForegroundColor Yellow
        }
    }

    if ($Selected.Count -eq 0) {
        Write-Host 'No matching scenario scripts to run.' -ForegroundColor Yellow
        Write-Host "Discovered scripts: $($Discovered -join ', ')"
        exit 0
    }
} else {
    $script:Selected = $Discovered
}

# ═══════════════════════════════════════════════════════════════════════════════
# List mode
# ═══════════════════════════════════════════════════════════════════════════════

if ($List) {
    Write-Host 'Agent scenario scripts:' -ForegroundColor Cyan
    Write-Host ''
    foreach ($name in $Discovered) {
        $marker = if ($name -in $Selected) { ' [selected]' } else { '' }
        $scriptPath = Join-Path $ScriptsDir $name

        # Extract the first line of the task prompt as a quick description.
        $desc = ''
        $content = Get-Content $scriptPath -Raw -ErrorAction SilentlyContinue
        if ($content -match '\$taskPrompt\s*=\s*@"\s*\r?\n\s*(.+?)\.?\s*\r?\n') {
            $desc = " -- $($Matches[1].Trim())"
        } elseif ($content -match '\$taskPrompt\s*=\s*@"\r?\n\s*\d+\.\s+(.+)"@') {
            $desc = " -- $($Matches[1].Trim())"
        }

        Write-Host "  $name$marker$desc"
    }
    Write-Host ''
    Write-Host "$($Selected.Count) script(s) selected out of $($Discovered.Count) discovered."
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

function Format-Duration {
    param([TimeSpan] $Duration)
    if ($Duration.TotalSeconds -lt 1) { return '<1s' }
    if ($Duration.TotalMinutes -lt 1) {
        return '{0:F1}s' -f $Duration.TotalSeconds
    }
    if ($Duration.TotalHours -lt 1) {
        return '{0}m {1}s' -f $Duration.Minutes, $Duration.Seconds
    }
    return '{0}h {1}m {2}s' -f $Duration.Hours, $Duration.Minutes, $Duration.Seconds
}

# ═══════════════════════════════════════════════════════════════════════════════
# Pre-flight: check that claude is available
# ═══════════════════════════════════════════════════════════════════════════════

$claudeAvailable = $null -ne (Get-Command claude -ErrorAction SilentlyContinue)
if (-not $claudeAvailable) {
    Write-Host 'WARNING: claude CLI not found on PATH.' -ForegroundColor Yellow
    Write-Host 'Each scenario script invokes claude.  Without it, every script will fail.'
    Write-Host ''
}

# ═══════════════════════════════════════════════════════════════════════════════
# Run
# ═══════════════════════════════════════════════════════════════════════════════

$Results = [System.Collections.ArrayList]::new()
$Passed  = 0
$Failed  = 0

Write-Banner "Agent Scenarios ($($Selected.Count) script(s))"

foreach ($name in $Selected) {
    $scriptPath = Join-Path $ScriptsDir $name

    Write-Section $name
    $start = Get-Date
    $exitCode = 0

    try {
        # Run directly so stdout/stderr stream to this console in real time.
        # Working directory is the repo root so claude finds the project.
        Push-Location $RepoRoot
        try {
            & pwsh -NoProfile -ExecutionPolicy Bypass -File $scriptPath
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
    $icon  = if ($entry.Status -eq 'PASS') { '[OK]  ' } else { '[FAIL]' }
    $color = if ($entry.Status -eq 'PASS') { 'Green' } else { 'Red' }
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
    Write-Host 'Some scenarios failed.' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'All scenarios passed.' -ForegroundColor Green
exit 0
