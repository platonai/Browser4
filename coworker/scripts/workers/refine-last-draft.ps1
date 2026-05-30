#!/usr/bin/env pwsh

$ErrorActionPreference = "Stop"

$agentHelper = Join-Path $PSScriptRoot "agent.ps1"
. $agentHelper
$repoRoot = Get-WorkspaceRoot
$agentCommand = Get-AgentCommand -RepoRoot $repoRoot

$draftDir = Resolve-TasksPath '0draft'

if (-not (Test-Path $draftDir)) {
    Write-Host "Draft directory not found: $draftDir"
    exit 1
}

# Find the last modified .md file
$latestDraft = Get-ChildItem -Path $draftDir -Filter "*.md" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $latestDraft) {
    Write-Host "No draft files found in $draftDir"
    exit 0
}

$draftPath = $latestDraft.FullName
Write-Host "Found latest draft: $draftPath"

$prompt = "Refine the content of the draft file: $draftPath. Improve the writing, clarity, and structure."

Write-Host "Starting agent to refine the draft..."

$process = Start-AgentProcess -Executable $agentCommand.Executable -BaseArgs $agentCommand.BaseArgs -Prompt $prompt -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') -WorkingDirectory $repoRoot -NoNewWindow
$process.WaitForExit()
exit $process.ExitCode
