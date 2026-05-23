#!/usr/bin/env pwsh

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Enable-Utf8NativeOutput {
    try {
        $utf8 = [System.Text.Encoding]::UTF8
        $global:OutputEncoding = $utf8
        [Console]::OutputEncoding = $utf8
        [Console]::InputEncoding = $utf8

        if (($IsWindows -or $env:OS -eq 'Windows_NT') -and [Console]::OutputEncoding.CodePage -ne 65001) {
            chcp.com 65001 > $null
            [Console]::OutputEncoding = $utf8
            [Console]::InputEncoding = $utf8
        }
    } catch {
        # Best effort only; continue even if the host cannot switch encodings.
    }
}

Enable-Utf8NativeOutput

cargo run -- open
cargo run -- swarm create
$output = cargo run --quiet -- swarm submit "https://example.com" 2>&1
$output = ($output | Out-String).Trim()
$submittedLine = $output -split "`r?`n" | Where-Object { $_ -match 'Task ID:\s*\S+' } | Select-Object -First 1
if (-not $submittedLine) {
    $submittedLine = $output
}
Write-Host "Submitted swarm task: $submittedLine"

$taskIdMatch = [regex]::Match($output, 'Task ID:\s*(\S+)')
if (-not $taskIdMatch.Success) {
    throw "Unable to parse Task ID from swarm submit output:`n$output"
}

$taskId = $taskIdMatch.Groups[1].Value
Write-Host "Waiting for agent task $taskId to finish..."

for ($i = 1; $i -le 3; $i++) {
    $status = cargo run --quiet -- swarm status $taskId 2>&1
    $status = ($status | Out-String).Trim()
    Write-Host $status

    if ($status -match '"done"\s*:\s*true' -or $status -match '"isDone"\s*:\s*true' -or $status -match 'status:\s*done') {
        Write-Host "Task $taskId is done."
        break
    }
    Start-Sleep -Seconds 3
}

exit 0

