@{
    Scheduler = @{
        TickSeconds          = 5
        PowerShellExecutable = 'pwsh'
        WorkingDirectory     = '..\..'
        LogDirectory         = '..\Browser4Team\coworker\tasks\300logs'
        StatusFile           = 'logs\scheduled-tasks.status.json'
    }

    Tasks = @(
        @{
            Name            = 'coworker'
            Description     = 'Process queued coworker tasks.'
            Enabled         = $true
            IntervalSeconds = 15
            WindowStyle     = 'Minimized'
            PendingPaths    = @(
                'coworker\tasks\1ready'
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
            WindowStyle     = 'Minimized'
            PendingPaths    = @('coworker\tasks\0draft\refine\1ready')
            ScriptPath      = 'coworker\scripts\workers\refine-drafts.ps1'
            Arguments       = @('-Path', 'coworker\tasks\0draft\refine\1ready')
        }
        @{
            Name            = 'commit-github-issues'
            Description     = 'Scan for pending GitHub issue files and create them via gh CLI.'
            Enabled         = $true
            IntervalSeconds = 15
            WindowStyle     = 'Minimized'
            PendingPaths    = @('coworker\tasks\200issues\github\open')
            ScriptPath      = 'coworker\scripts\workers\commit-github-issues.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'refine-github-issues'
            Description     = 'Extract issues from draft files, split into individual issues, refine as GitHub issues, and stage for creation.'
            Enabled         = $true
            IntervalSeconds = 15
            WindowStyle     = 'Minimized'
            PendingPaths    = @('coworker\tasks\200issues\draft\refine\0ready')
            ScriptPath      = 'coworker\scripts\workers\refine-github-issues.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'fetch-github-issues'
            Description     = 'Fetch latest GitHub issues, save them locally, and self-assign unassigned ones.'
            Enabled         = $true
            IntervalSeconds = 120
            WindowStyle     = 'Minimized'
            PendingPaths    = @()
            ScriptPath      = 'coworker\scripts\workers\fetch-github-issues.ps1'
            Arguments       = @()
        }
    )
}
