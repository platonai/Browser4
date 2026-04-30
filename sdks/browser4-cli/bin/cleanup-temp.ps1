#!/usr/bin/env pwsh

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Browser4CliTempRoot {
    return (Join-Path ([System.IO.Path]::GetTempPath()) '.browser4\browser4-cli')
}

$tempRoot = Get-Browser4CliTempRoot
$persistentStateRoot = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.browser4'

Write-Host "browser4-cli temp root: $tempRoot"
Write-Host "Persistent Browser4 home state is NOT removed: $persistentStateRoot"

if (-not (Test-Path -LiteralPath $tempRoot)) {
    Write-Host 'Nothing to clean.'
    exit 0
}

if ($PSCmdlet.ShouldProcess($tempRoot, 'Remove browser4-cli temp artifacts')) {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
    Write-Host 'Cleanup complete.'
}

