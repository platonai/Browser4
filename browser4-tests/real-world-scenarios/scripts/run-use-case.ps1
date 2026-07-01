#!/usr/bin/env pwsh
<#
.SYNOPSIS
Run an agent-scenario use case defined in a .txt file.

.DESCRIPTION
Reads a use-case description from a .txt file (comment-based metadata +
numbered task steps), combines it with the shared usability-evaluation
prompt (common.ps1), and invokes the Claude Code agent.

The use-case file format:
  # Use Case N: Title
  # Level: Simple|Complex|Enterprise
  # Type: <type>
  # Description: <description>

  1. First step.
  2. Second step.

.EXAMPLE
./run-use-case.ps1 -TaskFile "../browser4-tests-common/src/main/resources/e2e/scenarios/happy_path/use-cases/01-ecommerce-product-comparison.txt"

    Run the ecommerce comparison use case in dev mode.

.EXAMPLE
./run-use-case.ps1 -TaskFile "path/to/use-case.txt" -Production -Silent

    Run in production mode with silent output.

.NOTES
Mirrors the pattern of run-task.ps1. Dot-sources orchestration-common.ps1
for use-case parsing and common.ps1 for agent invocation.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $TaskFile,

    [switch] $Silent,

    # Skip the browser4-cli version check.
    [switch] $SkipVersionCheck
)

$ErrorActionPreference = 'Stop'

# ── Resolve task file path ────────────────────────────────────────────────────
$cwdPath = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine((Get-Location).Path, $TaskFile)
)
if (Test-Path -LiteralPath $cwdPath -PathType Leaf) {
    $resolvedPath = $cwdPath
}
else {
    # Try relative to the repo root
    $scriptsDir = $PSScriptRoot
    $repoRoot = (Resolve-Path "$scriptsDir/../../..").Path
    $resolvedPath = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::Combine($repoRoot, $TaskFile)
    )
}

if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
    Write-Host "ERROR: Use-case file not found: $resolvedPath" -ForegroundColor Red
    Write-Host "  Tried CWD:  $cwdPath" -ForegroundColor DarkGray
    Write-Host "  Tried repo: $resolvedPath" -ForegroundColor DarkGray
    exit 1
}

# ── Dot-source the shared helpers ─────────────────────────────────────────────
# common.ps1 checks $browser4cliMode at load time; ensure it's set before
# StrictMode (from orchestration-common.ps1) prevents reading undefined vars.
if (-not $browser4cliMode -and $env:BROWSER4CLI_MODE) {
    $browser4cliMode = $env:BROWSER4CLI_MODE
}
. "$PSScriptRoot/orchestration-common.ps1"
. "$PSScriptRoot/common.ps1"

# ── Parse the use-case file ───────────────────────────────────────────────────
try {
    $parsed = ConvertFrom-UseCaseFile -FilePath $resolvedPath
}
catch {
    Write-Host "ERROR: Failed to parse use-case file: $_" -ForegroundColor Red
    exit 1
}

$scenarioName = $parsed.ScenarioName
$taskInstructions = $parsed.Instructions

if (-not $Silent) {
    Write-Host "Task file:  $resolvedPath" -ForegroundColor DarkGray
    Write-Host "Scenario:   $scenarioName" -ForegroundColor DarkGray
    Write-Host "Level:      $($parsed.Level)" -ForegroundColor DarkGray
    if ($parsed.Description) {
        Write-Host "Description: $($parsed.Description)" -ForegroundColor DarkGray
    }
    Write-Host ''
}

# ── Verify the CLI is up to date ─────────────────────────────────────────────
if (-not $SkipVersionCheck) {
    $versionStatus = Assert-Browser4CliLatest -Silent:$Silent
    if ($versionStatus -ne 0) {
        Write-Host 'Run with -SkipVersionCheck to bypass this check.' -ForegroundColor DarkGray
        exit $versionStatus
    }
}

# ── Build the full prompt and invoke ──────────────────────────────────────────
$prompt = $generalPrompt + $taskInstructions

# ── Compute raw output file path ─────────────────────────────────────────────
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
