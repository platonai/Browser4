#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

. (Join-Path $PSScriptRoot 'browser4-process-common.ps1')

$procs = Get-Browser4JavaProcesses

if (-not $procs)
{
    Write-Output 'NO_BROWSER4_PROCESSES'
}
else
{
    $procs |
            ForEach-Object {
                $p = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue; [PSCustomObject]@{
                    ProcessId = $_.ProcessId; Name = $_.Name; WorkingSetMB = if ($p)
                    {
                        [math]::Round($p.WorkingSet64 / 1MB, 2)
                    }
                    else
                    {
                        $null
                    };

                    CPUSeconds = if ($p)
                    {
                        [math]::Round($p.CPU, 2)
                    }
                    else
                    {
                        $null
                    };

                    StartTime = if ($p)
                    {
                        $p.StartTime
                    }
                    else
                    {
                        $null
                    };

                    ExecutablePath = $_.ExecutablePath; CommandLine = $_.CommandLine
                }
            } | Sort-Object ProcessId | Format-List
}
