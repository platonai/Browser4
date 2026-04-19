#!/usr/bin/env pwsh

[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$ListOnly
)

. (Join-Path $PSScriptRoot 'browser4-process-common.ps1')

# Get java/javaw processes whose command line indicates Browser4.
$procs = Get-Browser4JavaProcesses

if (-not $procs)
{
    Write-Output 'NO_BROWSER4_PROCESSES'
}
else
{
    if ($ListOnly)
    {
        foreach ($proc in $procs)
        {
            Write-Output "PID=$($proc.ProcessId) CMD=$($proc.CommandLine)"
        }
        return
    }

    foreach ($proc in $procs)
    {
        $target = "process ID $( $proc.ProcessId )"
        if ($PSCmdlet.ShouldProcess($target, 'Stop-Process -Force'))
        {
            try
            {
                # Attempt to kill the process
                Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
                Write-Output "Killed process with ID: $( $proc.ProcessId )"
            }
            catch
            {
                Write-Output "Failed to kill process with ID: $( $proc.ProcessId ). Error: $_"
            }
        }
        else
        {
            Write-Output "Would kill process with ID: $( $proc.ProcessId )"
        }
    }
}
