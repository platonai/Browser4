#!/usr/bin/env pwsh
<#
.SYNOPSIS
Run an agent-scenario task defined in a markdown file.

.DESCRIPTION
Reads a task description from a .md file, combines it with the shared
usability-evaluation prompt (common.ps1), and invokes the Claude Code agent.

The first "# Heading" in the markdown file becomes the scenario name; the
remaining body becomes the task-specific prompt.

Set $browser4cliMode = 'production' before running to switch to production mode.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-task.ps1 -TaskFile tasks/amazon.md

    Run the amazon scenario in dev mode.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-task.ps1 -TaskFile tasks/search-summary.md -Silent

    Run the search-summary scenario with silent output.

.NOTES
The task file is expected to follow this format:

    # scenario-name

    Task instructions go here.
    1. First step.
    2. Second step.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $TaskFile,

    [switch] $Silent,

    # Skip the browser4-cli version check (useful when intentionally testing
    # an older version or when the check cannot resolve the version).
    [switch] $SkipVersionCheck,

    # Run in production mode (browser4-cli instead of cargo run).
    [switch] $Production
)

$ErrorActionPreference = 'Stop'

# ── Resolve task file path ────────────────────────────────────────────────────
# Try the caller's CWD first (backward-compatible), then fall back to the
# real-world-scenarios/ directory next to this script.
$cwdPath = Join-Path (Get-Location).Path $TaskFile
if (Test-Path -LiteralPath $cwdPath -PathType Leaf) {
    $resolvedPath = $cwdPath
} else {
    $scenariosDir = Join-Path $PSScriptRoot '..'
    $resolvedPath = Join-Path $scenariosDir $TaskFile
}

if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
    Write-Host "ERROR: Task file not found: $resolvedPath" -ForegroundColor Red
    Write-Host "  Tried CWD:  $cwdPath" -ForegroundColor DarkGray
    Write-Host "  Tried scenarios/: $resolvedPath" -ForegroundColor DarkGray
    exit 1
}

# ── Set mode before loading common.ps1 ────────────────────────────────────────
# Guard against overwriting pre-set values from run-task-production.ps1.
if ($Production -and -not $browser4cliMode -and -not $env:BROWSER4CLI_MODE) {
    $browser4cliMode = 'production'
}

# ── Dot-source the shared helpers ─────────────────────────────────────────────
# common.ps1 defines Read-TaskFile, $generalPrompt, Invoke-Agent,
# Assert-Browser4CliLatest, and $script:RepoRoot.
. "$PSScriptRoot/common.ps1"

try {
    # ── Parse the task file ───────────────────────────────────────────────────
    $task = Read-TaskFile -Path $resolvedPath
    $scenarioName = $task.Name
    $taskBody = $task.Body

    if (-not $Silent) {
        Write-Host "Task file:  $resolvedPath" -ForegroundColor DarkGray
        if ($scenarioName) {
            Write-Host "Scenario:   $scenarioName" -ForegroundColor DarkGray
        }
        Write-Host ''
    }

    # ── Verify the CLI is up to date ──────────────────────────────────────────
    if (-not $SkipVersionCheck) {
        $versionStatus = Assert-Browser4CliLatest -Silent:$Silent
        if ($versionStatus -ne 0) {
            Write-Host 'Run with -SkipVersionCheck to bypass this check.' -ForegroundColor DarkGray
            exit $versionStatus
        }
    }

    # ── Build the full prompt and invoke ──────────────────────────────────────
    $prompt = $generalPrompt + $taskBody

    # ── Compute raw output file path in ./target ──────────────────────────────
    $targetDir = Join-Path $script:RepoRoot 'target'
    if (-not (Test-Path -LiteralPath $targetDir)) {
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    }
    $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
    $safeName = if ($scenarioName) { $scenarioName -replace '[\\/:*?"<>|]', '_' } else { 'unknown' }
    $rawOutputFile = Join-Path $targetDir "$timestamp-$safeName.raw.md"

    $invokeParams = @{
        Prompt       = $prompt
        ScenarioName = $scenarioName
        OutputFile   = $rawOutputFile
    }
    if ($Silent) {
        $invokeParams['Silent'] = $true
    }

    Invoke-Agent @invokeParams
} catch {
    Write-Host "ERROR: run-task.ps1 failed: $_" -ForegroundColor Red
    exit 1
}
