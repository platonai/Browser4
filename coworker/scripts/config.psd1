@{
    Paths = @{
        WorkspaceRoot        = '..\..'
        CoworkerRoot         = '..'
        TasksRoot            = '..\tasks'
        TargetRepositoryRoot = '..\'
        LogDirectory         = '~\.browser4-coworker\tasks\300logs'
    }

    Scheduler = @{
        WorkingDirectory = '..\..'
    }

    # COPILOT = @(
    #     'gh'
    #     'copilot'
    #     '--no-ask-user'
    #     '--log-level'
    #     'info'
    #     '--allow-all'
    # )

    CLAUDE = @(
        'claude'
        '--dangerously-skip-permissions'
        '--verbose'
    )
}
