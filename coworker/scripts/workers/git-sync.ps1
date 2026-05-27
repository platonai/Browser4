#!/usr/bin/env pwsh

$agentHelper = Join-Path $PSScriptRoot "agent.ps1"
. $agentHelper
$repoRoot = Get-WorkspaceRoot
$agentCommand = Get-AgentCommand -RepoRoot $repoRoot

$prompt = @"
Commit all changes in "$repoRoot".
Pull from remote.
Then push to remote.
If conflicts occur, resolve them automatically.
"@

$agentArguments = New-AgentArguments -BaseArgs $agentCommand.BaseArgs -Prompt $prompt -AdditionalArguments @('--allow-all-tools')

Write-Host "Running:"
Write-Host (Format-AgentCommand -Executable $agentCommand.Executable -Arguments $agentArguments)

Invoke-Agent -Prompt $prompt -AdditionalArguments @('--allow-all-tools') -RepoRoot $repoRoot -WorkingDirectory $repoRoot
exit $LASTEXITCODE
