@{
    Paths = @{
        WorkspaceRoot        = '..\..'
        CoworkerRoot         = '..'
        TasksRoot            = '..\tasks'
        TargetRepositoryRoot = '..\'
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
