@{
    Paths = @{
        WorkspaceRoot        = '../..'
        CoworkerRoot         = '..'
        TasksRoot            = '../tasks'
        TargetRepositoryRoot = '../'
        LogDirectory         = '~/.browser4-coworker/tasks/300logs'
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

    # kimi -p runs non-interactively with auto permission (no approvals), so no
    # extra flags are needed. Do NOT add --yolo/--auto here: they conflict with -p.
    # KIMI = @(
    #     'kimi'
    # )

    CLAUDE = @(
        'claude'
        '--dangerously-skip-permissions'
        '--verbose'
    )
}
