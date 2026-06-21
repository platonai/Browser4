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

    [switch] $Silent
)

$ErrorActionPreference = 'Stop'

# ── Resolve task file path ────────────────────────────────────────────────────
# Try the caller's CWD first (backward-compatible), then fall back to the
# real-world-scenarios/ directory next to this script.
$cwdPath = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine((Get-Location).Path, $TaskFile)
)
if (Test-Path -LiteralPath $cwdPath -PathType Leaf) {
    $resolvedPath = $cwdPath
} else {
    $scenariosDir = Join-Path $PSScriptRoot '..'
    $resolvedPath = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::Combine($scenariosDir, $TaskFile)
    )
}

if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
    Write-Host "ERROR: Task file not found: $resolvedPath" -ForegroundColor Red
    Write-Host "  Tried CWD:  $cwdPath" -ForegroundColor DarkGray
    Write-Host "  Tried scenarios/: $resolvedPath" -ForegroundColor DarkGray
    exit 1
}

# ── Parse the task file ───────────────────────────────────────────────────────
$rawContent = Get-Content -Path $resolvedPath -Raw -Encoding UTF8

if ([string]::IsNullOrWhiteSpace($rawContent)) {
    Write-Host "ERROR: Task file is empty: $resolvedPath" -ForegroundColor Red
    exit 1
}

# Extract scenario name from the first "# Heading".
# Match the first line that starts with "# " (optionally preceded by whitespace).
$scenarioName = ''
$taskBody = $rawContent

if ($rawContent -match '(?m)^\s*#\s+(.+?)\s*$') {
    $scenarioName = $Matches[1].Trim()
    # Remove the heading line and any following blank lines from the task body.
    $taskBody = $rawContent -replace '^\s*#\s+.+?\s*\r?\n\s*\r?\n?', ''
}

if ([string]::IsNullOrWhiteSpace($taskBody)) {
    Write-Host "ERROR: No task body found after heading in: $resolvedPath" -ForegroundColor Red
    exit 1
}

if (-not $Silent) {
    Write-Host "Task file:  $resolvedPath" -ForegroundColor DarkGray
    if ($scenarioName) {
        Write-Host "Scenario:   $scenarioName" -ForegroundColor DarkGray
    }
    Write-Host ''
}

# ── Dot-source the shared helpers ─────────────────────────────────────────────
# common.ps1 defines $generalPrompt and Invoke-Agent.
# $browser4cliMode may already be set by a production wrapper.
. "$PSScriptRoot/common.ps1"

# ── Build the full prompt and invoke ──────────────────────────────────────────
$prompt = $generalPrompt + $taskBody

# ── Compute raw output file path in ./target ─────────────────────────────────
$repoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$targetDir = Join-Path $repoRoot 'target'
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
