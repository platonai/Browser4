@{
    Scheduler = @{
        TickSeconds          = 5
        # Use 'pwsh' for PowerShell 7+ (cross-platform). On Windows, switch to 'powershell.exe'
        # only if you explicitly need Windows PowerShell 5.1 behavior.
        PowerShellExecutable = 'pwsh'
        WorkingDirectory     = '..\..'
        LogDirectory         = 'coworker\tasks\300logs\scheduler'
        StatusFile           = 'logs\scheduled-tasks.status.json'
    }

    Tasks = @(
        @{
            Name            = 'coworker'
            Description     = 'Process queued coworker tasks.'
            Enabled         = $true
            IntervalSeconds = 15
            DependsOn       = @('process-task-source')
            PendingPaths    = @(
                'coworker\tasks\1created'
                'coworker\tasks\5approved'
            )
            ScriptPath      = 'coworker\scripts\coworker.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'draft-refinement'
            Description     = 'Process the draft refinement queue.'
            Enabled         = $true
            IntervalSeconds = 15
            PendingPaths    = @('coworker\tasks\0draft\refine\1ready')
            ScriptPath      = 'coworker\scripts\workers\refine-drafts.ps1'
            Arguments       = @('-Path', 'coworker\tasks\0draft\refine\1ready')
        }
        @{
            Name            = 'process-task-source'
            Description     = 'Poll configured task sources and dispatch new tasks.'
            Enabled         = $false
            IntervalSeconds = 60
            ScriptPath      = 'coworker\scripts\process-task-source.ps1'
            Arguments       = @('-Once')
        }
    )
}
