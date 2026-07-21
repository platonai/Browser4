#!/usr/bin/env pwsh
<#
.SYNOPSIS
Run an agent-scenario task defined in a markdown file.

.DESCRIPTION
Reads a task description from a .md file, combines it with the shared
usability-evaluation prompt (common.ps1), and invokes the configured agent CLI
(claude or kimi).

The first "# Heading" in the markdown file becomes the scenario name; the
remaining body becomes the task-specific prompt.

Set $browser4cliMode = 'production' before running to switch to production mode.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-task.ps1 -TaskFile tasks/real-world/generic/amazon.md

    Run the amazon scenario in dev mode.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-task.ps1 -TaskFile tasks/real-world/generic/search-summary.md -Silent

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
    [switch] $Production,

    # Maximum minutes to wait for the agent to complete.
    # 0 (default) means no timeout.  On timeout the process is killed and
    # exit code 124 is returned (matching the Unix `timeout` convention).
    [int] $TimeoutMinutes = 0
)

$ErrorActionPreference = 'Stop'

# ── Set mode before loading common.ps1 ────────────────────────────────────────
# Guard against overwriting pre-set values from run-task-production.ps1.
# Must be set before dot-sourcing common.ps1 so $generalPrompt picks it up.
if ($Production -and -not $browser4cliMode -and -not $env:BROWSER4CLI_MODE) {
    $browser4cliMode = 'production'
}

# ── Dot-source the shared helpers ─────────────────────────────────────────────
# common.ps1 defines Read-TaskFile, Resolve-TaskFilePath, $generalPrompt,
# Invoke-Agent, Assert-Browser4CliLatest, and $script:RepoRoot.
. "$PSScriptRoot/common.ps1"

# ── Resolve task file path ────────────────────────────────────────────────────
# Uses the three-tier lookup from Resolve-TaskFilePath:
#   1. As-given (handles absolute paths passed by run-tests.ps1)
#   2. Relative to CWD (backward-compatible for manual invocation)
#   3. Relative to scenarios/ dir (parent of this scripts/ directory)
$resolvedPath = Resolve-TaskFilePath -TaskFile $TaskFile -ScriptsDir $PSScriptRoot

if (-not $resolvedPath) {
    Write-Host "ERROR: Task file not found: $TaskFile" -ForegroundColor Red
    Write-Host "  Tried as-given, CWD, and scenarios/ directory." -ForegroundColor DarkGray
    exit 1
}

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

    # Write capture file path to a marker so parent processes can
    # discover and display the agent output after the run completes.
    $markerFile = Join-Path $targetDir '.current-capture-path'
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($markerFile, $rawOutputFile, $utf8NoBom)

    $invokeParams = @{
        Prompt       = $prompt
        ScenarioName = $scenarioName
        OutputFile   = $rawOutputFile
    }
    if ($Silent) {
        $invokeParams['Silent'] = $true
    }
    if ($TimeoutMinutes -gt 0) {
        $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
    }

    Invoke-Agent @invokeParams
} catch {
    Write-Host "ERROR: run-task.ps1 failed: $_" -ForegroundColor Red
    exit 1
}
