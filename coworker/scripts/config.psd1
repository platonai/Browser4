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
    # Each array is the full startup prefix: executable + subcommand +
    # backend-owned flags, in the exact order the CLI requires (subcommand-
    # scoped flags MUST come after their subcommand).  agent.ps1 appends only
    # the prompt slot to whatever this array holds; on Windows the prompt is
    # additionally redirected via stdin (never on the command line).
    #
    # The active blocks below are tried in this order (see Get-AgentBackend in
    # config.ps1): DSH > OPENCODE > CLAUDE > KIMI > CODEX > copilot fallback.
    # dsh (DeepSeek Harness) is the preferred external harness; set
    # BROWSER4_AGENT=<name> to override the order for one run.
    # Comment a block out to fall through to the next backend.

    # dsh headless answers one task, prints the final assistant message, and
    # exits (0 = completed).  The task is a trailing positional argument; `-`
    # reads it from stdin (agent.ps1 uses this for long prompts on Windows).
    # Do NOT re-add `run`: dsh has no `run` subcommand.
    # Headless runs fail closed when a tool asks for approval (no answerer); set
    # DSH_PERMISSION_MODE=danger-full-access for an unfenced run.
    DSH = @(
        'dsh'
        '--profile'
        'headless'
    )

    # opencode run answers one prompt non-interactively.  `--auto` auto-approves
    # permissions that are not explicitly denied — without it headless runs
    # fail closed when a tool asks for approval.  The message is a trailing
    # positional argument; with no message argument opencode reads it from
    # stdin (agent.ps1 uses this for long prompts on Windows).
    OPENCODE = @(
        'opencode'
        'run'
        '--auto'
    )

    # claude `-p` runs non-interactively.  --dangerously-skip-permissions
    # bypasses all permission checks (the --allow- variant unlocks it where the
    # session would otherwise refuse); --verbose raises diagnostics into stderr.
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
    # auto-approves all tool use without a sandbox; --ephemeral skips session
    # persistence for clean exit; --skip-git-repo-check lets codex also run
    # outside a git repository (no-op inside one).  All three flags are
    # exec-scoped — they MUST stay after `exec` (verified: `codex --ephemeral
    # exec ...` fails with "unexpected argument").  The prompt is a trailing
    # positional argument; with no prompt argument codex reads it from stdin.
    # Do NOT add -p or --yolo: they conflict with exec mode.
    CODEX = @(
        'codex'
        'exec'
        '--dangerously-bypass-approvals-and-sandbox'
        '--ephemeral'
        '--skip-git-repo-check'
    )
}
