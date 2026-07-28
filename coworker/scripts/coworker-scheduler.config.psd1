@{
    Scheduler = @{
        TickSeconds          = 5
        PowerShellExecutable = 'pwsh'
        WorkingDirectory     = '..\..'
        LogDirectory         = '~\.browser4-coworker\tasks\300logs'
        StatusFile           = 'logs\scheduled-tasks.status.json'
    }

    Tasks = @(
        @{
            Name            = 'coworker'
            Description     = 'Process queued coworker tasks.'
            Enabled         = $true
            IntervalSeconds = 15
            WindowStyle     = 'Hidden'
            PendingPaths    = @(
                'coworker\tasks\main\1ready'
                'coworker\tasks\main\5approved'
            )
            ScriptPath      = 'coworker\scripts\engineer.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'draft-refinement'
            Description     = 'Process the draft refinement queue.'
            Enabled         = $true
            IntervalSeconds = 15
            WindowStyle     = 'Hidden'
            PendingPaths    = @('coworker\tasks\main\0draft\refine\1ready')
            ScriptPath      = 'coworker\scripts\workers\refine-drafts.ps1'
            Arguments       = @('-Path', 'coworker\tasks\main\0draft\refine\1ready')
        }
        @{
            Name            = 'commit-github-issues'
            Description     = 'Scan for pending GitHub issue files and create them via gh CLI.'
            Enabled         = $false
            IntervalSeconds = 15
            WindowStyle     = 'Hidden'
            PendingPaths    = @('coworker\tasks\issues\github\commit\ready')
            ScriptPath      = 'coworker\scripts\workers\commit-github-issues.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'refine-github-issues'
            Description     = 'Extract issues from draft files, split into individual issues, refine as GitHub issues, and stage for creation.'
            Enabled         = $false
            IntervalSeconds = 15
            WindowStyle     = 'Hidden'
            PendingPaths    = @('coworker\tasks\issues\draft\refine\0ready')
            ScriptPath      = 'coworker\scripts\workers\refine-github-issues.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'fetch-github-issues'
            Description     = 'Fetch latest GitHub issues, save them locally, and self-assign unassigned ones.'
            Enabled         = $true
            IntervalSeconds = 600
            WindowStyle     = 'Hidden'
            PendingPaths    = @()
            ScriptPath      = 'coworker\scripts\workers\fetch-github-issues.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'organize-task-files'
            Description     = 'Reorganize task directories with more than 10 files into YYYY/MMDD subdirectories.'
            Enabled         = $true
            IntervalSeconds = 300
            WindowStyle     = 'Hidden'
            PendingPaths    = @()
            ScriptPath      = 'coworker\scripts\workers\organize-task-files.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'triage-github-issues'
            Description     = 'Scan fetched GitHub issues and auto-queue low-risk, high-relevance ones for AI execution.'
            Enabled         = $true
            IntervalSeconds = 1800
            WindowStyle     = 'Hidden'
            PendingPaths    = @('coworker\tasks\main\0draft\issues\github')
            ScriptPath      = 'coworker\scripts\workers\triage-github-issues.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'update-readmes'
            Description     = 'Scan all README.md files for staleness every hour and queue stale ones for AI-driven update.'
            Enabled         = $true
            IntervalSeconds = 3600
            WindowStyle     = 'Hidden'
            PendingPaths    = @()
            ScriptPath      = 'coworker\scripts\workers\update-readmes.ps1'
            Arguments       = @('-Update', '-MaxTasks', '2')
        }
        @{
            Name            = 'review-recent-issues'
            Description     = 'Find .issues.md files from the last 3 days in draft/ and review/, move draft files to review/, and run inline AI review on each.'
            Enabled         = $true
            IntervalSeconds = 20
            WindowStyle     = 'Hidden'
            PendingPaths    = @()
            ScriptPath      = 'coworker\scripts\workers\review-recent-issues.ps1'
            Arguments       = @()
        }
    )
}
