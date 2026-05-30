@{
    Paths = @{
        WorkspaceRoot        = '..\..'
        CoworkerRoot         = '..'
        TasksRoot            = '..\tasks'
        TargetRepositoryRoot = '..\..'
    }

    Scheduler = @{
        WorkingDirectory = '..\..'
    }

    COPILOT = @(
        'gh'
        'copilot'
        '--no-ask-user'
        '--log-level'
        'info'
        '--allow-all'
    )

    # Uncomment CLAUDE (and comment out COPILOT above) to use Claude Code instead of GitHub Copilot.
    # Flags are kept consistent with the COPILOT configuration:
    #   Copilot --no-ask-user  ->  Claude -p mode is non-interactive by default
    #   Copilot --log-level info ->  Claude --verbose provides equivalent diagnostic output
    #   Copilot --allow-all     ->  Claude allows all tools by default
    # CLAUDE = @(
    #     'claude'
    #     '--verbose'
    # )
}
