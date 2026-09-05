@{
    Paths = @{
        WorkspaceRoot        = '../..'
        CoworkerRoot         = '..'
        TasksRoot            = '../tasks'
        TargetRepositoryRoot = '../..'
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

    # codex exec runs non-interactively; --dangerously-bypass-approvals-and-sandbox
    # auto-approves all tool use; --ephemeral skips session persistence for clean exit.
    # Do NOT add -p or --yolo: they conflict with exec mode.
    # CODEX = @(
    #     'codex'
    #     '--dangerously-bypass-approvals-and-sandbox'
    #     '--ephemeral'
    # )

    # dsh run <prompt> runs non-interactively through a headless profile.
    # No extra flags needed beyond `run`.
    # DSH = @(
    #     'dsh'
    # )

    CLAUDE = @(
        'claude'
        '--dangerously-skip-permissions'
        '--allow-dangerously-skip-permissions'
        '--verbose'
    )
}
