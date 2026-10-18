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

    # ── Agent backend ────────────────────────────────────────────────────────
    # The active blocks below are tried in this order (see Get-AgentBackend in
    # config.ps1): DSH > CLAUDE > KIMI > CODEX > copilot fallback.
    # dsh (DeepSeek Harness) is the preferred external harness; set
    # BROWSER4_AGENT=<name> to override the order for one run.
    # Comment a block out to fall through to the next backend.

    # dsh needs an explicit profile.  agent.ps1 appends
    # `--profile headless <prompt>` to whatever this array holds, so keep it to
    # the executable only and do NOT add `run`: dsh has no `run` subcommand
    # (the task is a positional argument of the headless app).
    # Headless runs fail closed when a tool asks for approval (no answerer); set
    # DSH_PERMISSION_MODE=danger-full-access for an unfenced run.
    DSH = @(
        'dsh'
    )

    CLAUDE = @(
        'claude'
        '--dangerously-skip-permissions'
        '--allow-dangerously-skip-permissions'
        '--verbose'
    )

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
}
