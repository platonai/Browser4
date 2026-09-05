use crate::*;

// ---------------------------------------------------------------------------
// profile-import (browser4-profile-import plugin tool surface)
// ---------------------------------------------------------------------------

pub(super) fn test_profile_import_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // --list-sources routes to profile_import_list_sources and prints raw JSON
    let list = run_command(ctx, &["profile-import", "--list-sources"]);
    assert_eq!(list.exit_code, 0, "expected list-sources to succeed");
    assert!(
        list.stdout.contains("Person 1") && list.stdout.contains("chrome"),
        "Expected mock source listing in:\n{}",
        list.stdout
    );

    // import routes to profile_import_import and pretty-prints key fields
    let import = run_command(
        ctx,
        &["profile-import", "--source", "chrome", "--data", "bookmarks,cookies"],
    );
    assert_eq!(import.exit_code, 0, "expected import to succeed");
    assert!(
        import.stdout.contains("Import dir: /mock/imports/chrome-Default-20260825"),
        "Expected import dir line in:\n{}",
        import.stdout
    );
    assert!(
        import.stdout.contains("Files copied: 42"),
        "Expected files copied line in:\n{}",
        import.stdout
    );
    assert!(
        import.stdout.contains("Warning: Passwords were not imported"),
        "Expected password warning in:\n{}",
        import.stdout
    );
    assert!(
        import.stdout.contains("Next step: browser4-cli open --profile"),
        "Expected next-step hint in:\n{}",
        import.stdout
    );

    // --json is a CLI-global flag (wraps output); the tool call itself must
    // not carry it.
    let raw = run_command(
        ctx,
        &["profile-import", "--source", "edge", "--json"],
    );
    assert_eq!(raw.exit_code, 0, "expected --json import to succeed");

    // Dynamic plugin path: no CLI change needed — `plugin <domain>` (spaced,
    // matching every other prefixed command style) rewrites to
    // `plugin-<domain>`, discovers the tools from the server's /mcp/tools and
    // calls them generically. The dynamic path routes through the
    // session-aware tool executor, so an open session is needed first.
    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);
    let dynamic = run_command(
        ctx,
        &["plugin", "profile_import", "import", "--source", "chrome", "--data", "cookies"],
    );
    assert_eq!(dynamic.exit_code, 0, "expected dynamic plugin command to succeed:\n{}", dynamic.stdout);
    assert!(
        dynamic.stdout.contains("\"importDir\""),
        "Expected raw JSON result from the dynamic plugin path in:\n{}",
        dynamic.stdout
    );

    // Bare `plugin` is intentionally rejected with a subcommand hint (see
    // preferred_prefixed_group_form); server-driven listing lives behind
    // `plugin list` and `plugin-<domain>` invocations. The dynamic path above
    // already proves server-side discovery works end to end.

    // Plugin-declared CLI command: the plugin manifest (ToolSpec.cliName)
    // declares `profile import`; the CLI discovers it from /mcp/tools/specs
    // and renders it as a first-class named command — no CLI code change.
    let declared = run_command(
        ctx,
        &["profile", "import", "--source", "chrome", "--data", "cookies"],
    );
    assert_eq!(declared.exit_code, 0, "expected declared command to succeed:\n{}", declared.stdout);
    assert!(
        declared.stdout.contains("\"importDir\""),
        "Expected import result from the declared command in:\n{}",
        declared.stdout
    );

    // `plugin commands` lists plugin-declared commands with their origin
    // domain — the source-of-truth way to tell plugin commands apart from
    // built-in ones.
    let plugin_commands = run_command(ctx, &["plugin", "commands"]);
    assert_eq!(plugin_commands.exit_code, 0, "expected plugin commands to succeed:\n{}", plugin_commands.stdout);
    assert!(
        plugin_commands.stdout.contains("profile import [--source --data]")
            && plugin_commands.stdout.contains("profile_import.import"),
        "Expected declared command listing in:\n{}",
        plugin_commands.stdout
    );

    // `help` badges plugin-declared commands with [plugin], so the origin of
    // every command is visible at a glance.
    let help_out = run_command(ctx, &["help"]);
    assert_eq!(help_out.exit_code, 0, "expected help to succeed");
    assert!(
        help_out.stdout.contains("[plugin] profile import [--source --data]"),
        "Expected [plugin] badge in help:\n{}",
        help_out.stdout
    );

    // `help profile` shows both the built-in profile-import command and the
    // plugin-declared `profile import` with its badge.
    let help_profile = run_command(ctx, &["help", "profile"]);
    assert_eq!(help_profile.exit_code, 0, "expected help profile to succeed");
    assert!(
        help_profile.stdout.contains("[plugin] profile import"),
        "Expected [plugin] badge in help profile:\n{}",
        help_profile.stdout
    );

    // Recorded tool calls carry the right names and camelCase params
    let tool_calls = mock_server.snapshot().tool_calls;
    assert!(
        tool_calls.iter().any(|c| c.tool == "profile_import_list_sources"),
        "expected list_sources call, got: {:?}",
        tool_calls.iter().map(|c| c.tool.as_str()).collect::<Vec<_>>()
    );
    let import_call = tool_calls.iter().find(|c| c.tool == "profile_import_import").expect("import call");
    assert_eq!(import_call.arguments.get("source").and_then(|v| v.as_str()), Some("chrome"));
    assert_eq!(
        import_call.arguments.get("data").and_then(|v| v.as_str()),
        Some("bookmarks,cookies")
    );
    assert!(import_call.arguments.get("json").is_none(), "CLI-only flag must not be forwarded");
}

// ---------------------------------------------------------------------------
// close
// ---------------------------------------------------------------------------

pub(super) fn test_close_active_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write a persisted state with an active session.
    let state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(state_file_path(&ctx.state_dir, None), state.to_string())
        .expect("write state fixture");

    let result = run_command(ctx, &["close"]);
    assert_eq!(result.exit_code, 0, "expected close to succeed");
    assert!(
        result.stdout.contains("Session closed."),
        "Expected 'Session closed.' in:\n{}",
        result.stdout
    );

    // Verify the close_session tool was called.
    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.close_session_calls,
        vec!["swarm-session-1".to_string()]
    );

    // Verify the state file was removed.
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected state file to be removed after close"
    );
}

pub(super) fn test_close_no_active_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let result = run_command(ctx, &["close"]);
    assert_eq!(
        result.exit_code, 0,
        "expected close without session to succeed (exit 0)"
    );

    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains("Session required") || combined.contains("No active session"),
        "Expected no-active-session guidance in combined output:\n{combined}"
    );

    // Verify no close_session tool was called.
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot.close_session_calls.is_empty(),
        "Expected no close_session call when no session is active"
    );
}

pub(super) fn test_close_ignores_backend_close_failure(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Pre-populate state.
    let state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(state_file_path(&ctx.state_dir, None), state.to_string())
        .expect("write state fixture");

    // Queue a failure for close_session — the real handler ignores errors.
    mock_server.queue_tool_failure(
        "close_session",
        Some("swarm-session-1"),
        None,
        "backend already closed this session",
    );

    let result = run_command(ctx, &["close"]);
    assert_eq!(
        result.exit_code, 0,
        "expected close to succeed despite backend error"
    );

    assert!(
        result.stdout.contains("Session closed."),
        "Expected 'Session closed.' even when backend close fails:\n{}",
        result.stdout
    );

    // Verify the tool was attempted.
    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.close_session_calls,
        vec!["swarm-session-1".to_string()]
    );

    // State must be cleared regardless of backend error.
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected state file to be removed even when backend close fails"
    );
}

pub(super) fn test_close_named_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write a named-session state file.
    let state = serde_json::json!({
        "sessionId": "swarm-session-auth",
        "baseUrl": mock_server.base_url(),
    });
    let named_state_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(named_state_path.parent().unwrap()).expect("create sessions dir");
    fs::write(&named_state_path, state.to_string()).expect("write named state fixture");

    let result = run_command(ctx, &["-s=auth", "close"]);
    assert_eq!(result.exit_code, 0);
    assert!(
        result.stdout.contains("Session closed."),
        "Expected 'Session closed.' for named session:\n{}",
        result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.close_session_calls,
        vec!["swarm-session-auth".to_string()]
    );

    // Named state file must be removed.
    assert!(
        !named_state_path.exists(),
        "Expected named state file to be removed"
    );

    // Default state file must never have been created.
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected default state file to remain absent for named session"
    );
}

// ---------------------------------------------------------------------------
// close-all
// ---------------------------------------------------------------------------

pub(super) fn test_close_all_single_server(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write default state + 2 named sessions.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-default",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write auth state");

    let scraper_path = state_file_path(&ctx.state_dir, Some("scraper"));
    fs::write(
        &scraper_path,
        serde_json::json!({
            "sessionId": "swarm-session-scraper",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write scraper state");

    let result = run_command(ctx, &["close-all"]);
    assert_eq!(result.exit_code, 0, "expected close-all to succeed");
    assert!(
        result.stdout.contains("Closed") && result.stdout.contains("session(s)"),
        "Expected close_all_sessions result in:\n{}",
        result.stdout
    );

    // Verify close_all_sessions was called.
    let snapshot = mock_server.snapshot();
    assert!(
        !snapshot.close_all_sessions_calls.is_empty(),
        "Expected at least one close_all_sessions call"
    );

    // All state files must be removed.
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected default state to be removed"
    );
    assert!(!auth_path.exists(), "Expected auth state to be removed");
    assert!(
        !scraper_path.exists(),
        "Expected scraper state to be removed"
    );
}

// ---------------------------------------------------------------------------
// session-default
// ---------------------------------------------------------------------------

pub(super) fn test_session_default_promotes_named_to_default(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write a named session file.
    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write auth state");

    let result = run_command(ctx, &["session-default", "auth"]);
    assert_eq!(result.exit_code, 0, "expected session-default to succeed");
    assert!(
        result.stdout.contains("now the DEFAULT session"),
        "Expected success message in:\n{}",
        result.stdout
    );

    // Default state file must now have the named session's ID.
    assert_eq!(
        read_persisted_session_id(&ctx.state_dir),
        "swarm-session-auth"
    );

    // Named session file must be removed after promotion (prevents split state).
    assert!(
        !auth_path.exists(),
        "expected named session file to be removed after promotion"
    );
}

pub(super) fn test_session_default_warns_when_overwriting_default(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write a default session.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-old",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write default state");

    // Write a named session.
    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write auth state");

    let result = run_command(ctx, &["session-default", "auth"]);
    assert_eq!(result.exit_code, 0);

    // Warning about replacing existing default must appear in stderr.
    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains("Replacing existing default session"),
        "Expected overwrite warning, got stdout+stderr:\n{}",
        combined
    );
    assert!(
        combined.contains("swarm-session-old"),
        "Expected old session ID in warning, got stdout+stderr:\n{}",
        combined
    );

    // Named file must be removed.
    assert!(!auth_path.exists());
}

pub(super) fn test_session_default_errors_on_nonexistent(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let result =
        run_command_expecting_failure(ctx, &["session-default", "nonexistent"], "No session found");
    assert_ne!(result.exit_code, 0);
}

pub(super) fn test_session_default_updates_timestamp(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write a named session with a known old timestamp.
    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
            "lastAccessedAt": "2020-01-01T00:00:00+00:00",
        })
        .to_string(),
    )
    .expect("write auth state");

    let result = run_command(ctx, &["session-default", "auth"]);
    assert_eq!(result.exit_code, 0);

    // Read the default state and verify the timestamp was refreshed.
    let default_path = state_file_path(&ctx.state_dir, None);
    let raw = fs::read_to_string(&default_path).expect("read default state");
    let parsed: serde_json::Value = serde_json::from_str(&raw).expect("valid JSON");
    let ts = parsed["lastAccessedAt"]
        .as_str()
        .expect("lastAccessedAt must exist");
    // The old timestamp must have been replaced.
    assert_ne!(
        ts, "2020-01-01T00:00:00+00:00",
        "Expected lastAccessedAt to be refreshed, but got old value: {}",
        ts
    );
    // It should parse as a recent year (not the epoch default).
    assert!(
        ts.starts_with("202"),
        "Expected recent timestamp, got: {}",
        ts
    );
}

pub(super) fn test_close_all_no_active_sessions(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // No state files at all.
    let result = run_command(ctx, &["close-all"]);
    assert_eq!(
        result.exit_code, 0,
        "expected close-all to succeed with no sessions"
    );

    // It should still call close_all_sessions on the backend.
    let snapshot = mock_server.snapshot();
    assert!(
        !snapshot.close_all_sessions_calls.is_empty(),
        "Expected close_all_sessions call even when no local sessions exist"
    );
}

pub(super) fn test_close_all_server_unreachable(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Pre-populate state so we can verify it's still cleaned up.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write state fixture");

    // Shut down the mock server before running the command.
    mock_server.shutdown();

    let result = run_command_allowing_failure(ctx, &["close-all"]);
    // close-all never fails fatally — it treats errors as warnings.
    assert_eq!(
        result.exit_code, 0,
        "expected close-all to exit 0 even when server unreachable"
    );

    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains("close-all warnings:")
            || combined.contains("No reachable Browser4 servers responded"),
        "Expected close-all to report unreachable server:\n{combined}"
    );

    // State must still be cleaned up locally.
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected state file to be removed even when server is unreachable"
    );
}

pub(super) fn test_close_all_preserves_managed_process_registry(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write default state.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write state fixture");

    // Write a mock managed process registry (simulating a running server).
    let registry_path = ctx.state_dir.join("cli-managed-processes.json");
    let registry = serde_json::json!({
        "processes": [{
            "pid": 12345,
            "baseUrl": mock_server.base_url(),
            "port": 8182,
            "jarPath": "/fake/browser4.jar",
            "startedAt": "2026-01-01T00:00:00Z"
        }]
    });
    fs::write(&registry_path, registry.to_string()).expect("write managed process registry");

    let result = run_command(ctx, &["close-all"]);
    assert_eq!(result.exit_code, 0);

    // Session state must be removed.
    assert!(!state_file_path(&ctx.state_dir, None).exists());

    // Managed process registry must be preserved (close-all keeps the server alive).
    assert!(
        registry_path.exists(),
        "Expected managed process registry to survive close-all"
    );
}

// ---------------------------------------------------------------------------
// list
// ---------------------------------------------------------------------------

pub(super) fn test_list_active_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write default state with a session.
    let state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(state_file_path(&ctx.state_dir, None), state.to_string())
        .expect("write state fixture");

    // Configure the mock to list this session as active.
    mock_server.set_listed_sessions(vec![MockListedSession::active("swarm-session-1")]);

    let result = run_command(ctx, &["list"]);
    assert_eq!(result.exit_code, 0, "expected list to succeed");

    // The table should show the session as Active with timestamps.
    assert!(
        result.stdout.contains("Active"),
        "Expected 'Active' status in list output:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("swarm-session-1"),
        "Expected session ID in list output:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Created"),
        "Expected 'Created' column header in list output:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Last Access"),
        "Expected 'Last Access' column header in list output:\n{}",
        result.stdout
    );

    // Verify list_sessions was called.
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot
            .tool_calls
            .iter()
            .any(|c| c.tool == "list_sessions"),
        "Expected list_sessions tool call"
    );
}

pub(super) fn test_list_stale_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write default state with a session.
    let state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(state_file_path(&ctx.state_dir, None), state.to_string())
        .expect("write state fixture");

    // Configure the mock to list this session as stopped.
    mock_server.set_listed_sessions(vec![MockListedSession::stopped("swarm-session-1")]);

    let result = run_command(ctx, &["list"]);
    assert_eq!(result.exit_code, 0);

    assert!(
        result.stdout.contains("Stale"),
        "Expected 'Stale' status for stopped session:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Created") && result.stdout.contains("Last Access"),
        "Expected 'Created' and 'Last Access' column headers:\n{}",
        result.stdout
    );
}

pub(super) fn test_list_backend_unreachable(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write default state with a session.
    let state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(state_file_path(&ctx.state_dir, None), state.to_string())
        .expect("write state fixture");

    // Shut down the mock server before running list.
    mock_server.shutdown();

    let result = run_command(ctx, &["list"]);
    // list should exit 0 even when backend is unreachable.
    assert_eq!(
        result.exit_code, 0,
        "expected list to succeed when backend unreachable"
    );

    assert!(
        result.stdout.contains("Unknown"),
        "Expected 'Unknown' status when backend is unreachable:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("not started or unreachable"),
        "Expected unreachable note in output:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Created") && result.stdout.contains("Last Access"),
        "Expected 'Created' and 'Last Access' column headers:\n{}",
        result.stdout
    );
}

pub(super) fn test_list_no_sessions(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // No state files at all.
    let result = run_command(ctx, &["list"]);
    assert_eq!(result.exit_code, 0);

    // The table header should appear but no session rows.
    assert!(
        result.stdout.contains("Name")
            && result.stdout.contains("Session ID")
            && result.stdout.contains("Status")
            && result.stdout.contains("Created")
            && result.stdout.contains("Last Access"),
        "Expected table header in list output:\n{}",
        result.stdout
    );

    // Verify list_sessions was still called.
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot
            .tool_calls
            .iter()
            .any(|c| c.tool == "list_sessions"),
        "Expected list_sessions tool call even with no local sessions"
    );
}

pub(super) fn test_list_multiple_named_sessions(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write a default session.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-default",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write default state");

    // Write two named sessions.
    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write auth state");

    let scraper_path = state_file_path(&ctx.state_dir, Some("scraper"));
    fs::write(
        &scraper_path,
        serde_json::json!({
            "sessionId": "swarm-session-scraper",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write scraper state");

    // Backend: auth is active, scraper is stopped, default is active.
    mock_server.set_listed_sessions(vec![
        MockListedSession::active("swarm-session-default"),
        MockListedSession::active("swarm-session-auth"),
        MockListedSession::stopped("swarm-session-scraper"),
    ]);

    let result = run_command(ctx, &["list"]);
    assert_eq!(result.exit_code, 0);

    let output = &result.stdout;
    assert!(output.contains("auth"), "Expected 'auth' row:\n{output}");
    assert!(
        output.contains("scraper"),
        "Expected 'scraper' row:\n{output}"
    );
    assert!(
        output.contains("(default)"),
        "Expected '(default)' row:\n{output}"
    );

    // Verify statuses: default and auth are active, scraper is stale.
    assert!(
        output.contains("Active"),
        "Expected 'Active' for default and auth:\n{output}"
    );
    assert!(
        output.contains("Stale"),
        "Expected 'Stale' for scraper:\n{output}"
    );
    assert!(
        output.contains("Created") && output.contains("Last Access"),
        "Expected 'Created' and 'Last Access' column headers:\n{output}"
    );
}

// ---------------------------------------------------------------------------
// status
// ---------------------------------------------------------------------------

pub(super) fn test_status_server_up(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let result = run_command(ctx, &["status"]);
    assert_eq!(result.exit_code, 0, "expected status to succeed");

    assert!(
        result.stdout.contains("CLI version:"),
        "Expected CLI version in:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Server URL:"),
        "Expected Server URL in:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Server health: UP"),
        "Expected 'Server health: UP' in:\n{}",
        result.stdout
    );
}

pub(super) fn test_status_server_down(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Configure mock to return a 503 for health.
    mock_server.set_health_response(503, "application/json", r#"{"status":"DOWN"}"#);

    let result = run_command(ctx, &["status"]);
    assert_eq!(result.exit_code, 0);

    assert!(
        result.stdout.contains("Server health: DOWN"),
        "Expected 'Server health: DOWN' in:\n{}",
        result.stdout
    );
}

pub(super) fn test_status_server_unreachable(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Shut down the mock server so the health check fails.
    mock_server.shutdown();

    let result = run_command(ctx, &["status"]);
    assert_eq!(
        result.exit_code, 0,
        "expected status to succeed even when unreachable"
    );

    assert!(
        result.stdout.contains("Server health: UNREACHABLE"),
        "Expected 'Server health: UNREACHABLE' in:\n{}",
        result.stdout
    );
}

pub(super) fn test_status_installed_runtime(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Use the canonical runtime dir so that the test-created files are found
    // by resolve_runtime_data_dir(), which calls .canonicalize() on
    // BROWSER4_RUNTIME_DIR.  On some platforms (notably Windows with junction
    // points or symlinks under %TEMP%) canonicalize may resolve to a different
    // path than the raw env-var value.
    let runtime_dir = ctx
        .runtime_dir
        .canonicalize()
        .unwrap_or_else(|_| ctx.runtime_dir.clone());

    // Override BROWSER4_RUNTIME_DIR with the canonicalised path so that the
    // child CLI process resolves to the same directory where we created the
    // fake runtime files.
    ctx.set_env("BROWSER4_RUNTIME_DIR", &runtime_dir.to_string_lossy());

    // Write a runtime install metadata file using the new versioned layout.
    let tag = "v4.10.0";
    let versions_dir = runtime_dir.join("runtime");
    let install_dir = versions_dir.join(tag);
    let metadata_path = install_dir.join("browser4-installation.json");
    fs::create_dir_all(metadata_path.parent().unwrap()).expect("create versioned runtime dir");

    // Create the lib/ directory with a jar and the runtime/ directory
    // with a java binary — both required by install_dir_contains_runtime.
    let lib_dir = install_dir.join("lib");
    fs::create_dir_all(&lib_dir).expect("create lib dir");
    fs::write(lib_dir.join("browser4-core.jar"), "fake-jar-content").expect("write jar");
    let runtime_bin = install_dir.join("runtime").join("bin");
    fs::create_dir_all(&runtime_bin).expect("create runtime bin dir");
    let java_name = if cfg!(windows) { "java.exe" } else { "java" };
    let java_path = runtime_bin.join(java_name);
    fs::write(&java_path, "fake-java-binary").expect("write java");

    // On Unix, mark the fake java binary as executable so that
    // install_dir_contains_runtime — which checks mode & 0o111 on the
    // bundled JRE binary — does not reject it.  Without this the
    // status command reports "not installed" on Linux CI.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(&java_path)
            .expect("read fake java metadata")
            .permissions();
        perms.set_mode(0o755);
        fs::set_permissions(&java_path, perms).expect("set fake java executable");
    }

    // Also write the current.tag marker file so the CLI can find this install.
    fs::write(versions_dir.join("current.tag"), format!("{tag}\n")).expect("write current.tag");
    let metadata = serde_json::json!({
        "tag": tag,
        "asset_name": "browser4-runtime-v4.10.0.zip",
        "download_url": "https://example.com/releases/v4.10.0/browser4-runtime.zip",
        "installed_at": "2026-05-15T10:00:00Z"
    });
    fs::write(&metadata_path, metadata.to_string()).expect("write install metadata");

    let result = run_command(ctx, &["status"]);
    assert_eq!(result.exit_code, 0);

    assert!(
        result.stdout.contains("Installed bundle: v4.10.0"),
        "Expected 'Installed bundle: v4.10.0' in:\n{}",
        result.stdout
    );
}

pub(super) fn test_status_no_installed_runtime(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // reset_cli_artifacts keeps runtime_dir intact while a CLI-managed
    // backend may be running from it (deleting a live JVM's cwd breaks all
    // later process spawns on Linux/macOS).  This scenario genuinely needs
    // an empty runtime dir, so clear it explicitly.
    let _ = fs::remove_dir_all(&ctx.runtime_dir);
    fs::create_dir_all(&ctx.runtime_dir).ok();

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // No install metadata file exists.
    let result = run_command(ctx, &["status"]);
    assert_eq!(result.exit_code, 0);

    assert!(
        result.stdout.contains("Installed bundle: not installed"),
        "Expected 'Installed bundle: not installed' in:\n{}",
        result.stdout
    );
}

// ---------------------------------------------------------------------------
// stop
// ---------------------------------------------------------------------------

pub(super) fn test_stop_no_running_server(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // stop doesn't need a mock server — it interacts only with local state
    // and OS processes.  We use a mock server only to set server URL, but
    // the command won't contact it.
    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let result = run_command(ctx, &["stop"]);
    assert_eq!(result.exit_code, 0, "expected stop to succeed");

    // stop may report "No Browser4 server was running." when no server is
    // active, or it may report the actual server shutdown steps when a
    // real server (started by a previous live test) is still running.
    assert!(
        result.stdout.contains("No Browser4 server was running.")
            || result.stdout.contains("Browser4 server stopped."),
        "Expected either 'No Browser4 server was running.' or 'Browser4 server stopped.' in:\n{}",
        result.stdout
    );
}

pub(super) fn test_stop_clears_state(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write default + named session state files.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write auth state");

    let result = run_command(ctx, &["stop"]);
    assert_eq!(result.exit_code, 0);

    // stop must clear all state.
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected default state to be removed after stop"
    );
    assert!(
        !auth_path.exists(),
        "Expected named state to be removed after stop"
    );
}

// ---------------------------------------------------------------------------
// kill-all
// ---------------------------------------------------------------------------

pub(super) fn test_kill_all_no_running_processes(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let result = run_command(ctx, &["kill-all"]);
    assert_eq!(
        result.exit_code, 0,
        "expected kill-all to succeed when no processes"
    );

    // kill-all with no tracked processes should report that nothing was found
    // and succeed without error.
    assert!(
        result
            .stdout
            .contains("No tracked Browser4 processes found")
            || result.stdout.contains("Already stopped"),
        "Expected kill-all to report no tracked processes in:\n{}",
        result.stdout
    );
}

pub(super) fn test_kill_all_clears_state_and_registry(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write default + named session state files.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write auth state");

    // Write a managed process registry (simulating registered server process).
    let registry_path = ctx.state_dir.join("cli-managed-processes.json");
    let registry = serde_json::json!({
        "processes": [{
            "pid": 12345,
            "baseUrl": mock_server.base_url(),
            "port": 8182,
            "jarPath": "/fake/browser4.jar",
            "startedAt": "2026-01-01T00:00:00Z"
        }]
    });
    fs::write(&registry_path, registry.to_string()).expect("write managed process registry");

    let result = run_command(ctx, &["kill-all"]);
    assert_eq!(result.exit_code, 0, "expected kill-all to succeed");

    // All session state must be cleared.
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected default state to be removed after kill-all"
    );
    assert!(
        !auth_path.exists(),
        "Expected named state to be removed after kill-all"
    );
}

// ---------------------------------------------------------------------------
// Session management stability
// ---------------------------------------------------------------------------

pub(super) fn test_close_twice_idempotent(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write a persisted state.
    let state = serde_json::json!({
        "sessionId": "swarm-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(state_file_path(&ctx.state_dir, None), state.to_string())
        .expect("write state fixture");

    // First close.
    let first = run_command(ctx, &["close"]);
    assert_eq!(first.exit_code, 0);
    assert!(first.stdout.contains("Session closed."));

    // Second close — should still succeed without error.
    let second = run_command(ctx, &["close"]);
    assert_eq!(
        second.exit_code, 0,
        "expected second close to still exit 0 (idempotent)"
    );

    let combined = format!("{}\n{}", second.stdout, second.stderr);
    assert!(
        combined.contains("Session required") || combined.contains("No active session"),
        "Expected no-active-session guidance on second close:\n{combined}"
    );
}

pub(super) fn test_state_isolation_named_vs_default(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write both a default state and a named session state.
    let default_state = serde_json::json!({
        "sessionId": "swarm-session-default",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(
        state_file_path(&ctx.state_dir, None),
        default_state.to_string(),
    )
    .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(
        &auth_path,
        serde_json::json!({
            "sessionId": "swarm-session-auth",
            "baseUrl": mock_server.base_url(),
        })
        .to_string(),
    )
    .expect("write auth state");

    // Close only the named session.
    let result = run_command(ctx, &["-s=auth", "close"]);
    assert_eq!(result.exit_code, 0);

    // Named session file must be removed.
    assert!(!auth_path.exists(), "expected auth session to be removed");

    // Default session must be unaffected.
    assert!(
        state_file_path(&ctx.state_dir, None).exists(),
        "expected default session to remain after closing named session"
    );
    assert_eq!(
        read_persisted_session_id(&ctx.state_dir),
        "swarm-session-default"
    );
}

pub(super) fn test_corrupted_state_file_treated_as_missing(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Write malformed JSON as the state file.
    fs::write(
        state_file_path(&ctx.state_dir, None),
        "this is not valid json {{{",
    )
    .expect("write corrupted state");

    // close should treat corrupted state as missing and succeed.
    let result = run_command(ctx, &["close"]);
    assert_eq!(
        result.exit_code, 0,
        "expected close to handle corrupted state gracefully"
    );

    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains("Session required") || combined.contains("No active session"),
        "Expected guidance when state file is corrupted:\n{combined}"
    );
}

pub(super) fn test_open_uses_temporary_profile_mode(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_command(ctx, &["open", "--profile-mode=TEMPORARY"]);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_call = tool_calls
        .iter()
        .find(|call| call.tool == "open_session")
        .expect("expected open_session call");
    assert_eq!(
        open_session_call.arguments["capabilities"]["profileMode"],
        "TEMPORARY"
    );
}

pub(super) fn test_open_with_url_prints_page_state(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_command(
        ctx,
        &[
            "open",
            OPEN_PROFILE_MODE_ARG,
            "https://example.com/opened-from-open-command",
        ],
    );
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected session output in:\n{}",
        open_result.stdout
    );
    assert!(
        open_result.stdout.contains("### Page"),
        "Expected page block in:\n{}",
        open_result.stdout
    );
    assert!(
        open_result
            .stdout
            .contains("- Page URL: https://mock.browser4.local/current"),
        "Expected page URL in:\n{}",
        open_result.stdout
    );
    assert!(
        open_result
            .stdout
            .contains("- Page Title: Mock Browser4 Page"),
        "Expected page title in:\n{}",
        open_result.stdout
    );
    assert!(
        open_result.stdout.contains("[Snapshot]("),
        "Expected snapshot link in:\n{}",
        open_result.stdout
    );
    assert!(
        open_result.stdout.find("Session opened:") < open_result.stdout.find("### Page"),
        "Expected session output before page block in:\n{}",
        open_result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let navigate_call = tool_calls
        .iter()
        .find(|call| call.tool == "browser_navigate")
        .expect("expected browser_navigate call");
    assert_eq!(navigate_call.arguments["sessionId"], "swarm-session-1");
    assert_eq!(
        navigate_call.arguments["url"],
        "https://example.com/opened-from-open-command"
    );
    assert!(
        tool_calls.iter().any(|call| call.tool == "page_url"),
        "expected page_url call"
    );
    assert!(
        tool_calls.iter().any(|call| call.tool == "page_title"),
        "expected page_title call"
    );
    assert!(
        tool_calls
            .iter()
            .any(|call| call.tool == "browser_snapshot"),
        "expected browser_snapshot call"
    );
}

pub(super) fn test_open_reuses_existing_active_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["swarm-session-1", "swarm-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(
        ctx,
        &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        first_open
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    let second_open = run_command(
        ctx,
        &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        second_open
            .stdout
            .contains("Session already open: swarm-session-1"),
        "Expected second open to reuse the active session:\n{}",
        second_open.stdout
    );
    assert!(
        !second_open
            .stdout
            .contains("Session opened: swarm-session-2"),
        "Expected second open to avoid creating a new session:\n{}",
        second_open.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "open_session")
        .collect();
    assert_eq!(
        open_session_calls.len(),
        1,
        "Expected only the first open to call open_session when the saved session is still active"
    );
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "swarm-session-1");
}

pub(super) fn test_named_session_reuses_opened_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let session_name = "amazon";
    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_command(
        ctx,
        &[
            "-s=amazon",
            "open",
            "https://example.com/",
            OPEN_PROFILE_MODE_ARG,
        ],
    );
    assert!(
        open_result
            .stdout
            .contains("Session opened: amazon (swarm-session-1)"),
        "Expected named-session open output in:\n{}",
        open_result.stdout
    );

    let named_state_path = state_file_path(&ctx.state_dir, Some(session_name));
    assert!(
        named_state_path.exists(),
        "Expected named-session state file at {}",
        named_state_path.display()
    );
    assert!(
        !state_file_path(&ctx.state_dir, None).exists(),
        "Expected the default state file to remain unused for named sessions"
    );

    let persisted_session_id =
        read_persisted_session_id_for_session(&ctx.state_dir, Some(session_name));
    assert_eq!(persisted_session_id, "swarm-session-1");

    let goto_result = run_command(ctx, &["-s=amazon", "goto", "https://example.com/"]);
    let combined_output = format!("{}\n{}", goto_result.stdout, goto_result.stderr);
    assert!(
        !combined_output.contains("No active session"),
        "Expected named-session goto to reuse the opened session:\n{combined_output}"
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "open_session")
        .collect();
    assert_eq!(
        open_session_calls.len(),
        1,
        "Expected exactly one open_session call when reusing a named session"
    );

    let navigate_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_navigate")
        .collect();
    // open navigates once (when given a URL), and goto navigates once.
    assert_eq!(
        navigate_calls.len(),
        2,
        "Expected open and goto to each make one browser_navigate call"
    );
    // First navigate from open, second from goto.
    assert_eq!(
        navigate_calls[1].arguments["sessionId"],
        persisted_session_id
    );
    assert_eq!(navigate_calls[1].arguments["url"], "https://example.com/");
}

pub(super) fn test_open_refreshes_inactive_saved_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["swarm-session-1", "swarm-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(
        ctx,
        &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        first_open
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    mock_server.set_listed_sessions(vec![MockListedSession::stopped("swarm-session-1")]);

    let second_open = run_command(
        ctx,
        &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        second_open
            .stdout
            .contains("Session opened: swarm-session-2"),
        "Expected stale saved session to be refreshed with a new backend session:\n{}",
        second_open.stdout
    );
    assert!(
        !second_open.stdout.contains("Session already open"),
        "Expected stale saved session to be refreshed instead of reused:\n{}",
        second_open.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "open_session")
        .collect();
    assert_eq!(
        open_session_calls.len(),
        2,
        "Expected open to call open_session again when the saved session is no longer active"
    );
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "swarm-session-2");
}

pub(super) fn test_open_fresh_closes_existing_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["swarm-session-1", "swarm-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(
        ctx,
        &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        first_open
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    let second_open = run_command(
        ctx,
        &[
            "open",
            "--fresh",
            "https://example.com/",
            OPEN_PROFILE_MODE_ARG,
        ],
    );
    assert!(
        second_open
            .stdout
            .contains("Closing existing session swarm-session-1 — starting fresh (--fresh)."),
        "Expected --fresh to announce closing the existing session:\n{}",
        second_open.stdout
    );
    assert!(
        second_open
            .stdout
            .contains("Session opened: swarm-session-2"),
        "Expected --fresh to open a new session:\n{}",
        second_open.stdout
    );
    assert!(
        !second_open.stdout.contains("Using existing session"),
        "Expected no reconnect message when using --fresh:\n{}",
        second_open.stdout
    );
    assert!(
        !second_open.stdout.contains("Session already open"),
        "Expected no reuse confirmation when using --fresh:\n{}",
        second_open.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let close_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "close_session")
        .collect();
    assert_eq!(
        close_session_calls.len(),
        1,
        "Expected --fresh to close the existing session exactly once"
    );
    assert_eq!(
        close_session_calls[0].arguments["sessionId"], "swarm-session-1",
        "Expected --fresh to close the previously-opened session"
    );
    let open_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "open_session")
        .collect();
    assert_eq!(
        open_session_calls.len(),
        2,
        "Expected --fresh to call open_session again after closing"
    );
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "swarm-session-2");
}

pub(super) fn test_open_reconnect_warns_when_display_flag_ignored(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["swarm-session-1"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(
        ctx,
        &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        first_open
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );
    assert!(
        !first_open
            .stderr
            .contains("ignored: reconnecting to existing session"),
        "Expected no display-flag warning on the first open:\n{}",
        first_open.stderr
    );

    let second_open = run_command(
        ctx,
        &[
            "open",
            "--headless",
            "https://example.com/",
            OPEN_PROFILE_MODE_ARG,
        ],
    );
    assert!(
        second_open.stdout.contains("Using existing session"),
        "Expected second open to reconnect to the existing session:\n{}",
        second_open.stdout
    );
    assert!(
        second_open
            .stderr
            .contains("--headless ignored: reconnecting to existing session"),
        "Expected a stderr warning that --headless was ignored on reconnect:\n{}",
        second_open.stderr
    );

    // No new session should have been created by the reconnect.
    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "open_session")
        .collect();
    assert_eq!(
        open_session_calls.len(),
        1,
        "Expected the reconnect to reuse the session without calling open_session"
    );
}

pub(super) fn test_open_reopens_saved_session_after_human_closed_tab(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let workflow_url = "https://example.com/human-closed-tab";
    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["swarm-session-1", "swarm-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(
        ctx,
        &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        first_open
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    mock_server.queue_tool_failure(
        "browser_navigate",
        Some("swarm-session-1"),
        Some(workflow_url),
        "browser_navigate failed: Target closed",
    );

    let second_open = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, workflow_url]);
    assert!(
        second_open
            .stdout
            .contains("Session opened: swarm-session-2"),
        "Expected open to recreate a saved session whose tab was closed externally:\n{}",
        second_open.stdout
    );
    assert!(
        !second_open
            .stdout
            .contains("Session already open: swarm-session-1"),
        "Expected open to avoid confirming reuse after the saved session proved unusable:\n{}",
        second_open.stdout
    );
    assert!(
        second_open.stdout.contains("### Page"),
        "Expected page block after reopening the session:\n{}",
        second_open.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "open_session")
        .collect();
    assert_eq!(
        open_session_calls.len(),
        2,
        "Expected open to create a replacement session after the reused session failed"
    );

    let close_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "close_session")
        .collect();
    assert_eq!(
        close_session_calls.len(),
        1,
        "Expected open to close the unusable saved session before reopening"
    );
    assert_eq!(
        close_session_calls[0].arguments["sessionId"],
        "swarm-session-1"
    );

    let navigate_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_navigate")
        .collect();
    // First open navigates (1 call), second open fails then retries (2 calls).
    assert_eq!(
        navigate_calls.len(),
        3,
        "Expected open to retry browser_navigate with the replacement session"
    );
    // First navigate: initial open to example.com/
    assert_eq!(navigate_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(navigate_calls[0].arguments["url"], "https://example.com/");
    // Second navigate (failed): reuse session with human-closed-tab URL
    assert_eq!(navigate_calls[1].arguments["sessionId"], "swarm-session-1");
    assert_eq!(navigate_calls[1].arguments["url"], workflow_url);
    // Third navigate (retry): new session with human-closed-tab URL
    assert_eq!(navigate_calls[2].arguments["sessionId"], "swarm-session-2");
    assert_eq!(navigate_calls[2].arguments["url"], workflow_url);
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "swarm-session-2");
}

pub(super) fn test_open_navigation_failure_uses_structured_message(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let workflow_url = "https://example.com/invalid-open-target";
    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    mock_server.queue_tool_failure(
        "browser_navigate",
        Some("swarm-session-1"),
        Some(workflow_url),
        "browser_navigate failed: invalid URL",
    );

    let open_result =
        run_command_allowing_failure(ctx, &["open", OPEN_PROFILE_MODE_ARG, workflow_url]);
    let combined_output = format!("{}\n{}", open_result.stdout, open_result.stderr);

    assert_ne!(
        open_result.exit_code, 0,
        "Expected open to fail for invalid navigation target:\n{combined_output}"
    );
    assert!(
        combined_output.contains("❌ Navigation failed"),
        "Expected structured failure heading in:\n{combined_output}"
    );
    assert!(
        combined_output.contains("URL: https://example.com/invalid-open-target"),
        "Expected failed target URL in:\n{combined_output}"
    );
    assert!(
        combined_output.contains("Session: swarm-session-1"),
        "Expected session id in:\n{combined_output}"
    );
    assert!(
        combined_output.contains("🧾 Details"),
        "Expected details section in:\n{combined_output}"
    );
    assert!(
        combined_output.contains("browser_navigate failed: invalid URL"),
        "Expected backend error details in:\n{combined_output}"
    );
    assert!(
        !combined_output.contains("Error: ❌ Navigation failed"),
        "Expected structured errors to be printed without an extra Error: prefix:\n{combined_output}"
    );
}

pub(super) fn test_goto_opens_session_when_missing_or_inactive(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["swarm-session-1", "swarm-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_goto = run_command(ctx, &["goto", "https://example.com/missing-session"]);
    assert!(
        first_goto
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected goto to auto-open a session when none is persisted:\n{}",
        first_goto.stdout
    );
    assert!(
        !first_goto.stdout.contains("🔐 Active session required"),
        "Expected goto to avoid the old missing-session guidance once auto-open is enabled:\n{}",
        first_goto.stdout
    );

    mock_server.set_listed_sessions(vec![MockListedSession::stopped("swarm-session-1")]);

    let second_goto = run_command(ctx, &["goto", "https://example.com/inactive-session"]);
    assert!(
        second_goto
            .stdout
            .contains("Session opened: swarm-session-2"),
        "Expected goto to refresh the saved session when the backend marks it inactive:\n{}",
        second_goto.stdout
    );
    let bin_name = cli_binary()
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| "browser4-cli".to_string());
    let manual_recovery_guidance = format!("run `{bin_name} open` to create or refresh the session first.");
    assert!(
        !second_goto.stdout.contains(&manual_recovery_guidance),
        "Expected goto to refresh automatically instead of printing manual recovery guidance:\n{}",
        second_goto.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "open_session")
        .collect();
    assert_eq!(
        open_session_calls.len(),
        2,
        "Expected goto to create a session initially and refresh it after the backend reported it inactive"
    );

    let navigate_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_navigate")
        .collect();
    assert!(
        navigate_calls.len() >= 2,
        "Expected goto to navigate after auto-opening or refreshing the session: {:?}",
        tool_calls
    );
    assert_eq!(navigate_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(
        navigate_calls[0].arguments["url"],
        "https://example.com/missing-session"
    );
    assert_eq!(navigate_calls[1].arguments["sessionId"], "swarm-session-2");
    assert_eq!(
        navigate_calls[1].arguments["url"],
        "https://example.com/inactive-session"
    );
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "swarm-session-2");
}

pub(super) fn test_batch_reduces_transport_round_trips(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let workflow_url = "https://example.com/batch-performance";
    let individual_server = MockBrowser4Server::start();
    ctx.browser4_base_url = individual_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, workflow_url]);
    let eval_result = run_command(ctx, &["eval", "document.title"]);
    let press_result = run_command(ctx, &["press", "!", "#type-target"]);

    assert_eq!(
        strip_snapshot_output(&eval_result.stdout),
        "Mock Browser4 Page"
    );
    assert_eq!(
        strip_snapshot_output(&press_result.stdout),
        "mock response for browser_press_key"
    );

    let individual_snapshot = individual_server.snapshot();
    assert!(
        individual_snapshot.tool_calls.len() >= 7,
        "Expected individual commands to make multiple backend tool calls, got {:?}",
        individual_snapshot.tool_calls
    );
    assert!(
        individual_snapshot
            .tool_calls
            .iter()
            .all(|call| call.tool != "command_batch"),
        "Expected individual commands to avoid command_batch transport: {:?}",
        individual_snapshot.tool_calls
    );

    reset_cli_artifacts(ctx);

    let batch_server = MockBrowser4Server::start();
    ctx.browser4_base_url = batch_server.base_url();
    let batch_navigate_command = format!("goto {workflow_url}");

    let batch_result = run_command(
        ctx,
        &[
            "batch",
            batch_navigate_command.as_str(),
            "eval document.title",
            "press ! #type-target",
        ],
    );

    let batch_output = strip_snapshot_output(&batch_result.stdout);
    assert!(
        batch_output.contains("Mock Browser4 Page"),
        "Expected batch output to include the eval result:\n{}",
        batch_result.stdout
    );
    assert!(
        batch_output.contains("mock response for browser_press_key"),
        "Expected batch output to include the press result:\n{}",
        batch_result.stdout
    );

    let batch_snapshot = batch_server.snapshot();
    assert_eq!(
        batch_snapshot.tool_calls.len(),
        1,
        "Expected batched workflow to collapse into one backend transport call: {:?}",
        batch_snapshot.tool_calls
    );
    assert_eq!(batch_snapshot.tool_calls[0].tool, "command_batch");

    let steps = batch_snapshot.tool_calls[0].arguments["steps"]
        .as_array()
        .expect("expected command_batch steps array");
    assert_eq!(
        steps.len(),
        3,
        "Expected open, navigate, eval, and press batch steps"
    );
    assert_eq!(steps[0]["tool"], "browser_navigate");
    assert_eq!(steps[1]["tool"], "browser_evaluate");
    assert_eq!(steps[2]["op"], "tool");

    assert!(
        batch_snapshot.tool_calls.len() < individual_snapshot.tool_calls.len(),
        "Expected batch transport to require fewer backend calls than individual commands"
    );
}

pub(super) fn test_eval_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let page_eval = run_command(ctx, &["eval", "document.title"]);
    assert_eq!(
        strip_snapshot_output(&page_eval.stdout),
        "Mock Browser4 Page"
    );
    assert!(
        !page_eval.stdout.contains("### Page"),
        "eval should not print a post-command snapshot block:\n{}",
        page_eval.stdout
    );

    let element_eval = run_command(ctx, &["eval", "element => element.textContent", "e5"]);
    assert_eq!(
        strip_snapshot_output(&element_eval.stdout),
        "Mock element text for backend:5"
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let eval_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_evaluate")
        .collect();
    assert_eq!(eval_calls.len(), 2, "expected two browser_evaluate calls");
    assert_eq!(eval_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(eval_calls[0].arguments["expression"], "document.title");
    assert!(eval_calls[0].arguments.get("ref").is_none());
    assert_eq!(eval_calls[1].arguments["sessionId"], "swarm-session-1");
    assert_eq!(
        eval_calls[1].arguments["expression"],
        "element => element.textContent"
    );
    assert_eq!(eval_calls[1].arguments["ref"], "backend:5");
}

pub(super) fn test_cdp_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Open a session first
    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // Test 1: CDP command with method only (no params)
    let result = run_command(ctx, &["cdp", "Page.captureScreenshot"]);
    assert!(
        result.stdout.contains("mock-cdp-result"),
        "cdp output should contain mock-cdp-result, got:\n{}",
        result.stdout
    );

    // Test 2: CDP command with --json params
    let result_json = run_command(
        ctx,
        &[
            "cdp",
            "Runtime.evaluate",
            "--json",
            r#"{"expression":"1+1"}"#,
        ],
    );
    assert!(
        result_json.stdout.contains(r#""expression":"1+1""#),
        "cdp --json output should include the expression param, got:\n{}",
        result_json.stdout
    );

    // Test 3: CDP command without method should fail.
    // The CLI argument parser catches the missing required <method> arg
    // before the CDP handler runs, so the error mentions the arg, not the
    // handler's error message.
    let bad_result = run_command_expecting_failure(ctx, &["cdp"], "Missing required argument");
    assert_ne!(bad_result.exit_code, 0);

    // Verify the mock server recorded the execute_cdp_command tool calls
    let tool_calls = mock_server.snapshot().tool_calls;
    let cdp_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "execute_cdp_command")
        .collect();
    assert!(
        cdp_calls.len() >= 2,
        "expected at least 2 execute_cdp_command calls, got {}",
        cdp_calls.len()
    );

    // First call: method-only
    assert_eq!(cdp_calls[0].arguments["method"], "Page.captureScreenshot");
    assert_eq!(cdp_calls[0].arguments["sessionId"], "swarm-session-1");

    // Second call: with JSON params (parsed by CLI into `params` key)
    assert_eq!(cdp_calls[1].arguments["method"], "Runtime.evaluate");
    let params_val = &cdp_calls[1].arguments["params"];
    assert!(
        params_val["expression"].as_str().unwrap_or("") == "1+1",
        "expected expression=1+1 in params, got: {}",
        params_val
    );
}

pub(super) fn test_eval_css_selector_passthrough(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // CSS selectors should be passed through *without* the eN → backend:N
    // conversion that happens for snapshot refs.
    let css_eval = run_command(
        ctx,
        &["eval", "element => element.textContent", "#click-target"],
    );
    assert_eq!(
        strip_snapshot_output(&css_eval.stdout),
        "Mock element text for #click-target"
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let eval_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_evaluate")
        .collect();
    assert_eq!(eval_calls.len(), 1, "expected one browser_evaluate call");
    assert_eq!(eval_calls[0].arguments["ref"], "#click-target");
    assert!(
        !eval_calls[0].arguments["ref"]
            .as_str()
            .unwrap()
            .contains("backend:"),
        "CSS selector ref should NOT be converted to backend:N, got {:?}",
        eval_calls[0].arguments["ref"]
    );
}

pub(super) fn test_eval_complex_expression_falls_to_default(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // An expression that does not match any of the mock's hardcoded patterns
    // should fall through to the default "mock evaluation result" text.
    let result = run_command(
        ctx,
        &[
            "eval",
            "Array.from(document.querySelectorAll('button')).map(b => b.id)",
        ],
    );
    assert_eq!(
        strip_snapshot_output(&result.stdout),
        "mock evaluation result"
    );
    assert!(
        !result.stdout.contains("### Page"),
        "eval should not print a post-command snapshot block:\n{}",
        result.stdout
    );
}

pub(super) fn test_eval_await_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // eval --await should pass awaitPromise: true through to the MCP tool call
    let result = run_command(
        ctx,
        &[
            "eval",
            "--await",
            "new Promise(r => setTimeout(() => r(42), 100))",
        ],
    );
    assert_eq!(
        strip_snapshot_output(&result.stdout),
        "mock evaluation result"
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let eval_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_evaluate")
        .collect();
    assert_eq!(eval_calls.len(), 1, "expected one browser_evaluate call");
    assert_eq!(
        eval_calls[0].arguments["awaitPromise"], true,
        "expected awaitPromise: true in tool call arguments, got: {:?}",
        eval_calls[0].arguments
    );
    assert_eq!(
        eval_calls[0].arguments["expression"],
        "new Promise(r => setTimeout(() => r(42), 100))"
    );
}

pub(super) fn test_eval_without_await_omits_flag(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // eval without --await should NOT include awaitPromise in the arguments
    let result = run_command(ctx, &["eval", "document.title"]);
    assert_eq!(strip_snapshot_output(&result.stdout), "Mock Browser4 Page");

    let tool_calls = mock_server.snapshot().tool_calls;
    let eval_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_evaluate")
        .collect();
    assert_eq!(eval_calls.len(), 1, "expected one browser_evaluate call");
    assert!(
        eval_calls[0].arguments.get("awaitPromise").is_none(),
        "expected no awaitPromise in tool call arguments without --await, got: {:?}",
        eval_calls[0].arguments
    );
}

pub(super) fn test_eval_in_standalone_batch(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // Run a batch that contains two eval commands with different ref styles.
    // Quote multi-word expressions inside batch commands so the parser sees
    // them as single arguments.
    let batch_result = run_command(
        ctx,
        &[
            "batch",
            "eval document.title",
            "eval 'element => element.textContent' '#my-input'",
        ],
    );

    let batch_output = strip_snapshot_output(&batch_result.stdout);
    assert!(
        batch_output.contains("Mock Browser4 Page"),
        "Expected batch output to include the first eval result:\n{}",
        batch_result.stdout
    );
    assert!(
        batch_output.contains("Mock element text for #my-input"),
        "Expected batch output to include the second eval result:\n{}",
        batch_result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let batch_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "command_batch")
        .collect();
    assert_eq!(
        batch_calls.len(),
        1,
        "Expected one command_batch call, got {:?}",
        tool_calls
    );

    let steps = batch_calls[0].arguments["steps"]
        .as_array()
        .expect("expected command_batch steps array");
    let eval_steps: Vec<_> = steps
        .iter()
        .filter(|s| s["tool"] == "browser_evaluate")
        .collect();
    assert_eq!(
        eval_steps.len(),
        2,
        "Expected two browser_evaluate steps in batch, got {:?}",
        steps
    );
    assert_eq!(eval_steps[0]["arguments"]["expression"], "document.title");
    assert!(eval_steps[0]["arguments"].get("ref").is_none());
    assert_eq!(
        eval_steps[1]["arguments"]["expression"],
        "element => element.textContent"
    );
    assert_eq!(eval_steps[1]["arguments"]["ref"], "#my-input");
}

pub(super) fn test_press_command_uses_direct_tool_dispatch(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let press_result = run_command(ctx, &["press", "!", "#type-target"]);
    assert_eq!(
        strip_snapshot_output(&press_result.stdout),
        "mock response for browser_press_key"
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let press_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_press_key")
        .collect();
    assert_eq!(press_calls.len(), 1, "expected one browser_press_key call");
    assert_eq!(press_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(press_calls[0].arguments["ref"], "#type-target");
    assert_eq!(press_calls[0].arguments["key"], "!");
    assert!(
        tool_calls
            .iter()
            .all(|call| call.tool != "browser_evaluate"),
        "press should not synthesize browser_evaluate calls: {tool_calls:?}"
    );
}

/// Covers the agent-browser A/B-tier command-gap fills that ship as plain
/// MCP tool calls through the generic dispatch path:
/// `dialog-status`, `errors`, `focus`, `is-visible`, `is-enabled`,
/// `is-checked`, `key`, `keyboard`, `scrollintoview`, `pushstate`,
/// `highlight`, `set`, and `window-new`.  Each command is exercised against
/// the mock backend and its recorded tool call is verified.
pub(super) fn test_agent_browser_command_gaps(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // ── 1. dialog-status ──────────────────────────────────────────────
    let dialog_status = run_command(ctx, &["dialog-status"]);
    assert_eq!(
        strip_snapshot_output(&dialog_status.stdout),
        "mock response for browser_dialog_status"
    );

    // ── 2. errors (console error listing alias) ───────────────────────
    let errors = run_command(ctx, &["errors"]);
    assert_eq!(
        strip_snapshot_output(&errors.stdout),
        "mock response for browser_console_messages"
    );

    // ── 3. focus ──────────────────────────────────────────────────────
    let focus = run_command(ctx, &["focus", "#search"]);
    assert_eq!(
        strip_snapshot_output(&focus.stdout),
        "mock response for browser_focus"
    );

    // ── 4-6. is-visible / is-enabled / is-checked ────────────────────
    // These commands are exposed in spaced form (`is visible <sel>`).
    let is_visible = run_command(ctx, &["is", "visible", "#submit"]);
    assert_eq!(
        strip_snapshot_output(&is_visible.stdout),
        "mock response for browser_is_visible"
    );

    let is_enabled = run_command(ctx, &["is", "enabled", "#submit"]);
    assert_eq!(
        strip_snapshot_output(&is_enabled.stdout),
        "mock response for browser_is_enabled"
    );

    let is_checked = run_command(ctx, &["is", "checked", "#agree"]);
    assert_eq!(
        strip_snapshot_output(&is_checked.stdout),
        "mock response for browser_is_checked"
    );

    // ── 7-8. key / keyboard (press aliases) ───────────────────────────
    let key = run_command(ctx, &["key", "Enter"]);
    assert_eq!(
        strip_snapshot_output(&key.stdout),
        "mock response for browser_press_key"
    );

    let keyboard = run_command(ctx, &["keyboard", "Tab"]);
    assert_eq!(
        strip_snapshot_output(&keyboard.stdout),
        "mock response for browser_press_key"
    );

    // ── 9. scrollintoview (synthesizes browser_evaluate) ──────────────
    let scrollintoview = run_command(ctx, &["scrollintoview", "#results"]);
    assert_eq!(
        strip_snapshot_output(&scrollintoview.stdout),
        "mock evaluation result"
    );

    // ── 10. pushstate (synthesizes browser_evaluate) ──────────────────
    let pushstate = run_command(ctx, &["pushstate", "/new-path"]);
    assert_eq!(
        strip_snapshot_output(&pushstate.stdout),
        "mock evaluation result"
    );

    // ── 11. highlight (synthesizes browser_evaluate) ──────────────────
    let highlight = run_command(ctx, &["highlight", "#price"]);
    assert_eq!(
        strip_snapshot_output(&highlight.stdout),
        "mock evaluation result"
    );

    // ── 12. set (CDP emulation) ───────────────────────────────────────
    let set_geo = run_command(
        ctx,
        &["set", "geo", "--lat=37.7749", "--lon=-122.4194"],
    );
    assert!(
        set_geo.stdout.contains("Emulation.setGeolocationOverride"),
        "set geo should call Emulation.setGeolocationOverride, got:\n{}",
        set_geo.stdout
    );
    assert!(
        set_geo.stdout.contains("mock-cdp-result"),
        "set geo output should contain mock-cdp-result, got:\n{}",
        set_geo.stdout
    );

    // ── 13. window-new (browser_tabs "new" action) ────────────────────
    let window_new = run_command(
        ctx,
        &["window", "new", "https://mock.browser4.local/two"],
    );
    assert!(
        window_new.stdout.contains("Created tab with GUID: mock-tab-guid-1"),
        "window-new should report the mocked tab GUID, got:\n{}",
        window_new.stdout
    );

    // ── 14-17. network requests / request detail / HAR start / stop ──────
    let network_requests = run_command(
        ctx,
        &["network", "requests", "--filter", "api", "--status", "2xx"],
    );
    assert_eq!(
        strip_snapshot_output(&network_requests.stdout),
        "mock response for browser_network_requests",
        "network requests should reach the backend tool"
    );

    let network_request = run_command(ctx, &["network", "request", "1234.5"]);
    assert_eq!(
        strip_snapshot_output(&network_request.stdout),
        "mock response for browser_network_request"
    );

    // Numeric request ids (CDP ids can look like plain digits) must be
    // forwarded as strings, not dropped by the arg parser.
    let numeric_request = run_command(ctx, &["network", "request", "42238"]);
    assert_eq!(
        strip_snapshot_output(&numeric_request.stdout),
        "mock response for browser_network_request"
    );

    let har_start = run_command(ctx, &["network", "har", "start", "--content", "all"]);
    assert_eq!(
        strip_snapshot_output(&har_start.stdout),
        "mock response for browser_har_start"
    );

    let har_stop = run_command(ctx, &["network", "har", "stop"]);
    assert_eq!(
        strip_snapshot_output(&har_stop.stdout),
        "mock response for browser_har_stop"
    );

    // `har stop [path]` accepts a positional path (agent-browser form).
    let har_path = ctx.state_dir.join("mock-har-stop.har");
    let har_stop_path = run_command(
        ctx,
        &["network", "har", "stop", har_path.to_str().expect("har path")],
    );
    assert!(
        har_stop_path.stdout.contains("HAR saved"),
        "har stop with a positional path should report the saved file, got:\n{}",
        har_stop_path.stdout
    );

    // ── 18-19. network route / unroute ─────────────────────────────────
    let route = run_command(
        ctx,
        &["network", "route", "**/api/users", "--body", "{\"users\":[]}", "--resource-type", "xhr"],
    );
    assert_eq!(
        strip_snapshot_output(&route.stdout),
        "mock response for browser_network_route"
    );

    let unroute = run_command(ctx, &["network", "unroute", "**/api/users"]);
    assert_eq!(
        strip_snapshot_output(&unroute.stdout),
        "mock response for browser_network_unroute"
    );

    // ── Verify recorded tool calls ────────────────────────────────────
    let tool_calls = mock_server.snapshot().tool_calls;
    let names: Vec<&str> = tool_calls.iter().map(|call| call.tool.as_str()).collect();

    for tool in [
        "browser_dialog_status",
        "browser_console_messages",
        "browser_focus",
        "browser_is_visible",
        "browser_is_enabled",
        "browser_is_checked",
        "browser_press_key",
        "browser_evaluate",
        "execute_cdp_command",
        "browser_tabs",
        "browser_network_requests",
        "browser_network_request",
        "browser_network_route",
        "browser_network_unroute",
        "browser_har_start",
        "browser_har_stop",
    ] {
        assert!(
            names.contains(&tool),
            "expected recorded tool call {tool}, got: {names:?}"
        );
    }

    // Route args reach the backend.
    let route_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_network_route")
        .collect();
    assert_eq!(route_calls.len(), 1, "expected one route call");
    assert_eq!(route_calls[0].arguments["urlPattern"], "**/api/users");
    assert_eq!(route_calls[0].arguments["body"], "{\"users\":[]}");
    assert_eq!(route_calls[0].arguments["resourceType"], "xhr");
    let unroute_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_network_unroute")
        .collect();
    assert_eq!(unroute_calls.len(), 1, "expected one unroute call");
    assert_eq!(unroute_calls[0].arguments["urlPattern"], "**/api/users");

    // Network filter args reach the backend.
    let network_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_network_requests")
        .collect();
    assert_eq!(network_calls.len(), 1, "expected one network requests call");
    assert_eq!(network_calls[0].arguments["filter"], "api");
    assert_eq!(network_calls[0].arguments["status"], "2xx");
    let har_start_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_har_start")
        .collect();
    assert_eq!(har_start_calls.len(), 1, "expected one har start call");
    assert_eq!(har_start_calls[0].arguments["contentMode"], "all");
    // Both request-detail ids arrive as strings (numeric ids included).
    let detail_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_network_request")
        .collect();
    assert_eq!(detail_calls.len(), 2, "expected two network request calls");
    assert_eq!(detail_calls[0].arguments["requestId"], "1234.5");
    assert_eq!(detail_calls[1].arguments["requestId"], "42238");
    // The positional har-stop path is consumed CLI-side (the backend returns
    // the HAR document; the CLI writes the file), so the backend tool call
    // carries no path argument.
    let har_stop_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_har_stop")
        .collect();
    assert_eq!(har_stop_calls.len(), 2, "expected two har stop calls");
    assert!(
        har_stop_calls[1].arguments.get("path").is_none(),
        "har-stop path should stay on the CLI side, got: {:?}",
        har_stop_calls[1].arguments
    );

    // press-key aliases (key + keyboard) each issue their own call.
    let press_calls = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_press_key")
        .count();
    assert_eq!(press_calls, 2, "expected key + keyboard press calls, got {press_calls}");

    // evaluate-backed commands: scrollintoview, pushstate, highlight.
    let evaluate_calls = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_evaluate")
        .count();
    assert_eq!(
        evaluate_calls, 3,
        "expected scrollintoview+pushstate+highlight evaluate calls, got {evaluate_calls}"
    );

    // window-new drives the full tab lifecycle: new → list → select.
    let tabs_calls = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_tabs")
        .count();
    assert_eq!(
        tabs_calls, 3,
        "expected browser_tabs new+list+select calls, got {tabs_calls}"
    );

    // Geo emulation params reach the backend.
    let cdp_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "execute_cdp_command")
        .collect();
    assert_eq!(cdp_calls.len(), 1, "expected one execute_cdp_command call");
    assert_eq!(cdp_calls[0].arguments["method"], "Emulation.setGeolocationOverride");
    let params = &cdp_calls[0].arguments["params"];
    assert!(
        (params["latitude"].as_f64().unwrap_or(0.0) - 37.7749).abs() < 1e-9,
        "expected latitude 37.7749, got: {}",
        params
    );
}

pub(super) fn test_swarm_session_and_agent_tools(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_swarm_session(ctx);
    assert_swarm_session_call(&mock_server);

    let extract_result = run_command(
        ctx,
        &[
            "extract",
            "product name, price",
            "--schema={\"type\":\"object\"}",
            "--raw",
        ],
    );
    let extracted = strip_snapshot_output(&extract_result.stdout);
    assert!(
        extracted.contains("\"Mock Product\""),
        "Expected extract output to contain extracted data in:\n{}",
        extract_result.stdout
    );

    let summarize_result = run_command(
        ctx,
        &[
            "summarize",
            "summarize the page marker",
            "--selector=#page-marker",
            "--raw",
        ],
    );
    let summary = strip_snapshot_output(&summarize_result.stdout);
    assert_eq!(summary, "Mock summary for #page-marker");
    assert!(
        !summarize_result.stdout.contains("### Page"),
        "Expected summarize output to remain plain text without a snapshot block:\n{}",
        summarize_result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let extract_call = tool_calls
        .iter()
        .find(|call| call.tool == "agent_extract")
        .expect("expected agent_extract call");
    assert_eq!(extract_call.arguments["sessionId"], "SWARM");
    assert_eq!(extract_call.arguments["instruction"], "product name, price");
    assert_eq!(extract_call.arguments["schema"], "{\"type\":\"object\"}");

    let summarize_call = tool_calls
        .iter()
        .find(|call| call.tool == "agent_summarize")
        .expect("expected agent_summarize call");
    assert_eq!(summarize_call.arguments["sessionId"], "SWARM");
    assert_eq!(
        summarize_call.arguments["instruction"],
        "summarize the page marker"
    );
    assert_eq!(summarize_call.arguments["selector"], "#page-marker");
}

pub(super) fn test_agent_task_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let agent_run_result = run_command(ctx, &["agent", "run", "collect the latest updates"]);
    assert!(
        agent_run_result
            .stdout
            .contains("Task submitted: agent-task-1"),
        "Expected task submission output in:\n{}",
        agent_run_result.stdout
    );
    assert!(
        agent_run_result
            .stdout
            .contains("agent status agent-task-1"),
        "Expected agent status hint in:\n{}",
        agent_run_result.stdout
    );

    let agent_status_result = run_command(ctx, &["agent", "status", "agent-task-1"]);
    assert_eq!(
        strip_snapshot_output(&agent_status_result.stdout),
        r#"{"id":"agent-task-1","status":"RUNNING"}"#
    );

    let agent_result_result = run_command(ctx, &["agent", "result", "agent-task-1"]);
    assert_eq!(
        strip_snapshot_output(&agent_result_result.stdout),
        "result for agent-task-1"
    );
    assert_eq!(
        mock_server.snapshot().plain_commands,
        vec!["collect the latest updates".to_string()]
    );
    assert!(
        mock_server
            .snapshot()
            .status_queries
            .iter()
            .all(|query| query == "agent-task-1"),
        "Expected all status queries to target agent-task-1, got {:?}",
        mock_server.snapshot().status_queries
    );
    assert!(
        mock_server.snapshot().status_queries.len() >= 2,
        "Expected at least one agent-run probe and one explicit agent-status lookup, got {:?}",
        mock_server.snapshot().status_queries
    );
    assert_eq!(
        mock_server.snapshot().result_queries,
        vec!["agent-task-1".to_string()]
    );
}

pub(super) fn test_agent_run_missing_llm_key(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let failure = run_command_expecting_failure(
        ctx,
        &["agent", "run", "task missing llm key"],
        "requires an LLM API key",
    );
    let combined = format!("{}\n{}", failure.stdout, failure.stderr);
    assert!(
        combined.contains("The LLM is not configured"),
        "Expected missing-LLM detail in:\n{combined}"
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec!["task missing llm key".to_string()]
    );
    assert!(
        snapshot
            .status_queries
            .contains(&"agent-task-missing-llm".to_string()),
        "Expected agent-run to probe status for the missing-LLM task, got {:?}",
        snapshot.status_queries
    );
}

// ---------------------------------------------------------------------------
// Agent status/result tracking — verifies the fixes for:
//   1. Integer statusCode parsing (was: .as_str() on integer → empty string)
//   2. Status labels use standard lifecycle vocabulary (completed/failed, not done)
//   3. agent result returns content for failed tasks (not null)
//   4. agent list shows tracked tasks and prunes terminal ones
// ---------------------------------------------------------------------------

/// Verify that `agent status` correctly handles integer `statusCode` values
/// (the Kotlin backend serializes it as an integer, e.g. `417`, not a string).
pub(super) fn test_agent_status_with_integer_status_code(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // Register a custom status response with integer statusCode and
    // terminal processState — simulating a failed agent task.
    mock_server.set_command_status_response(
        "agent-task-1",
        serde_json::json!({
            "id": "agent-task-1",
            "statusCode": 417,
            "processState": "done",
            "isDone": true,
            "message": "Agent produced no results (no page content). The task may not have navigated to a valid page or the agent encountered an error.",
            "agentHistory": {"states": []},
        }),
    );

    // Submit the task
    let run_result = run_command(ctx, &["agent", "run", "test integer statusCode"]);
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "Expected task submission in:\n{}",
        run_result.stdout
    );

    // Phase 1: agent list BEFORE agent status — the live refresh from the
    // mock server should produce the correct "failed (417)" label.  We check
    // this first because agent status would sync the terminal label into
    // the local tracking file, causing prune to remove it on the next list.
    let list_result = run_command(ctx, &["agent", "list"]);
    let list_output = strip_snapshot_output(&list_result.stdout);
    assert!(
        list_output.contains("failed (417)"),
        "Expected 'failed (417)' label (integer statusCode parsed correctly) in list output:\n{}",
        list_output
    );
    assert!(
        !list_output.contains("\"done\""),
        "Expected NO 'done' label in list output (should use standard vocabulary):\n{}",
        list_output
    );

    // Phase 2: agent status should contain the integer statusCode in raw JSON
    let status_result = run_command(ctx, &["agent", "status", "agent-task-1"]);
    let status_output = strip_snapshot_output(&status_result.stdout);
    assert!(
        status_output.contains("\"statusCode\":417")
            || status_output.contains("\"statusCode\": 417"),
        "Expected integer statusCode 417 in status output:\n{}",
        status_output
    );

    // Verify the mock recorded expected calls
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot.status_queries.iter().any(|q| q == "agent-task-1"),
        "Expected status query for agent-task-1, got {:?}",
        snapshot.status_queries
    );
}

/// Verify that `agent result` returns actual content (not "null") for failed
/// agent tasks.  Before the fix, `commandResult` was null for any task that
/// didn't produce an agent summary.
pub(super) fn test_agent_result_not_null_for_failed_task(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // A failed task with a message but no summary should still produce a
    // commandResult (via the fallback chain: summary → message → failureReason).
    mock_server.set_command_status_response(
        "agent-task-1",
        serde_json::json!({
            "id": "agent-task-1",
            "statusCode": 417,
            "processState": "done",
            "isDone": true,
            "message": "Agent produced no results (no page content).",
            "agentHistory": {"states": [
                {"step": 1, "instruction": "test commandResult for failed task"}
            ]},
        }),
    );

    // Register a custom result response that simulates the backend's
    // CommandResult JSON with a summary populated from the fallback chain
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Agent produced no results (no page content)."}"#,
    );

    let _run_result = run_command(ctx, &["agent", "run", "test commandResult for failed task"]);

    // agent result should return the CommandResult JSON, not "null"
    let result_result = run_command(ctx, &["agent", "result", "agent-task-1"]);
    let result_output = strip_snapshot_output(&result_result.stdout);
    assert!(
        !result_output.trim().is_empty(),
        "Expected non-empty result, got empty output"
    );
    assert_ne!(
        result_output.trim(),
        "null",
        "Expected agent result to NOT be 'null' — it should contain the CommandResult:\n{}",
        result_output
    );
    assert!(
        result_output.contains("summary"),
        "Expected result to contain 'summary' field:\n{}",
        result_output
    );

    // Verify mock recorded the result query
    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.result_queries, vec!["agent-task-1".to_string()]);
}

/// Verify that `agent list` shows tracked tasks with correct lifecycle labels
/// ("completed", "failed (code)", "queued", "processing") — never "done".
pub(super) fn test_agent_list_lifecycle_labels(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // Run two tasks so we can check different labels
    let _run1 = run_command(ctx, &["agent", "run", "first task"]);
    let _run2 = run_command(ctx, &["agent", "run", "second task"]);

    // Register custom statuses: one completed, one failed
    mock_server.set_command_status_response(
        "agent-task-1",
        serde_json::json!({
            "id": "agent-task-1",
            "statusCode": 200,
            "processState": "done",
            "isDone": true,
            "message": "Task completed successfully",
        }),
    );
    mock_server.set_command_status_response(
        "agent-task-2",
        serde_json::json!({
            "id": "agent-task-2",
            "statusCode": 417,
            "processState": "done",
            "isDone": true,
            "message": "Task failed",
        }),
    );

    // agent list should show both tasks with correct lifecycle labels
    let list_result = run_command(ctx, &["agent", "list"]);
    let list_output = strip_snapshot_output(&list_result.stdout);
    assert!(
        list_output.contains("completed"),
        "Expected 'completed' label for agent-task-1 in list output:\n{}",
        list_output
    );
    assert!(
        list_output.contains("failed (417)"),
        "Expected 'failed (417)' label for agent-task-2 in list output:\n{}",
        list_output
    );
    assert!(
        !list_output.contains("\"done\""),
        "Expected NO 'done' label in list output (all labels should use standard vocabulary):\n{}",
        list_output
    );
}

/// Verify that `agent list` prunes completed/failed tasks so they don't
/// accumulate indefinitely.  After a terminal status is refreshed into the
/// local tracking file, the next `agent list` call removes it.
pub(super) fn test_agent_list_prunes_terminal_tasks(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // Submit two tasks
    let _run1 = run_command(ctx, &["agent", "run", "prune test task 1"]);
    let _run2 = run_command(ctx, &["agent", "run", "prune test task 2"]);

    // Set both to terminal statuses
    mock_server.set_command_status_response(
        "agent-task-1",
        serde_json::json!({
            "id": "agent-task-1",
            "statusCode": 200,
            "processState": "done",
            "isDone": true,
        }),
    );
    mock_server.set_command_status_response(
        "agent-task-2",
        serde_json::json!({
            "id": "agent-task-2",
            "statusCode": 417,
            "processState": "done",
            "isDone": true,
        }),
    );

    // First list call: shows both tasks with standard lifecycle labels.
    // Terminal tasks persist across multiple list calls — auto-pruning is
    // deferred to explicit `agent list --clear`.
    let list1 = run_command(ctx, &["agent", "list"]);
    let output1 = strip_snapshot_output(&list1.stdout);
    assert!(
        output1.contains("agent-task-1") || output1.contains("agent-task-2"),
        "First list should show tasks:\n{}",
        output1
    );
    assert!(
        output1.contains("completed") || output1.contains("failed"),
        "First list should use standard lifecycle labels:\n{}",
        output1
    );

    // Second list call: tasks still appear (they persist, not auto-pruned).
    let list2 = run_command(ctx, &["agent", "list"]);
    let output2 = strip_snapshot_output(&list2.stdout);
    assert!(
        output2.contains("agent-task-1") || output2.contains("agent-task-2"),
        "Second list should still show terminal tasks (persist):\n{}",
        output2
    );

    // Explicit clear removes all tracked agent tasks.
    let clear = run_command(ctx, &["agent", "list", "--clear"]);
    let clear_out = strip_snapshot_output(&clear.stdout);
    assert!(
        clear_out.contains("Cleared"),
        "Clear should confirm removal. Got:\n{}",
        clear_out
    );

    // After clear, no tasks remain.
    let list3 = run_command(ctx, &["agent", "list"]);
    let output3 = strip_snapshot_output(&list3.stdout);
    assert!(
        output3.contains("No tracked async tasks"),
        "After clear, should show no tasks. Got:\n{}",
        output3
    );
}

/// Full lifecycle test: `agent run` → `agent list` → `agent status` →
/// `agent result`, verifying correct labels and content at each stage.
///
/// Terminal tasks persist across multiple `agent list` calls — auto-pruning
/// is deferred to explicit `agent list --clear`.
pub(super) fn test_agent_full_lifecycle_with_mock(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // ---- Phase 1: agent run ----
    let run_result = run_command(ctx, &["agent", "run", "describe the latest AI trends"]);
    assert!(
        run_result.stdout.contains("Task submitted:"),
        "Expected 'Task submitted:' in:\n{}",
        run_result.stdout
    );
    let task_id = extract_submitted_task_id(&run_result.stdout);
    assert!(!task_id.is_empty(), "Expected a task ID from agent run");

    // Register custom status and result responses BEFORE querying them
    mock_server.set_command_status_response(
        &task_id,
        serde_json::json!({
            "id": &task_id,
            "statusCode": 200,
            "processState": "done",
            "isDone": true,
            "message": "Successfully described latest AI trends",
            "agentHistory": {"states": [
                {"step": 1, "instruction": "describe the latest AI trends", "summary": "AI trends in 2026 include..."}
            ]},
        }),
    );
    mock_server.set_command_result_response(
        &task_id,
        r#"{"summary":"AI trends in 2026 include advances in reasoning models, multimodal agents, and on-device inference."}"#,
    );

    // ---- Phase 2: agent list (BEFORE agent status, so the task isn't pruned yet) ----
    let list_result = run_command(ctx, &["agent", "list"]);
    let list_output = strip_snapshot_output(&list_result.stdout);
    assert!(
        list_output.contains(&task_id),
        "agent list should show the task:\n{}",
        list_output
    );
    assert!(
        list_output.contains("completed") || list_output.contains("1 total"),
        "agent list should show the task with a status label:\n{}",
        list_output
    );
    assert!(
        !list_output.contains("\"done\""),
        "agent list should NOT show old 'done' label:\n{}",
        list_output
    );

    // ---- Phase 3: agent status ----
    let status_result = run_command(ctx, &["agent", "status", &task_id]);
    let status_output = strip_snapshot_output(&status_result.stdout);
    assert!(
        status_output.contains("\"statusCode\":200")
            || status_output.contains("\"statusCode\": 200"),
        "Status should include integer statusCode 200:\n{}",
        status_output
    );
    assert!(
        status_output.contains(&task_id),
        "Status should reference the task ID:\n{}",
        status_output
    );

    // ---- Phase 4: agent result ----
    let result_result = run_command(ctx, &["agent", "result", &task_id]);
    let result_output = strip_snapshot_output(&result_result.stdout);
    assert!(
        !result_output.trim().is_empty() && result_output.trim() != "null",
        "agent result should return content, not null/empty:\n{}",
        result_output
    );
    assert!(
        result_output.contains("summary"),
        "Result should be a CommandResult JSON with 'summary' field:\n{}",
        result_output
    );

    // ---- Verify mock server recorded all expected interactions ----
    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec!["describe the latest AI trends".to_string()]
    );
    assert!(
        snapshot.status_queries.iter().any(|q| q == &task_id),
        "Expected status queries for the task, got {:?}",
        snapshot.status_queries
    );
    assert!(
        snapshot.result_queries.iter().any(|q| q == &task_id),
        "Expected result queries for the task, got {:?}",
        snapshot.result_queries
    );
}

/// Full lifecycle test for `agent run "给出第100个素数"` that verifies:
/// 1. Each `agent list` correctly tracks task history across the lifecycle
/// 2. `agent result` contains the correct 100th prime number (541)
///
/// This test exercises the exact sequence from the user's workflow:
///   agent list → agent run → agent list → agent status → agent list →
///   agent result → agent list
pub(super) fn test_agent_run_100th_prime(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // ---- Phase 1: Initial agent list (should be empty) ----
    let list0 = run_command(ctx, &["agent", "list"]);
    let output0 = strip_snapshot_output(&list0.stdout);
    assert!(
        output0.contains("No tracked async tasks"),
        "Initial agent list should show no tracked tasks. Got:\n{}",
        output0
    );

    // ---- Phase 2: agent run ----
    let run_result = run_command(ctx, &["agent", "run", "给出第100个素数"]);
    assert!(
        run_result.stdout.contains("Task submitted:"),
        "Expected 'Task submitted:' in agent run output:\n{}",
        run_result.stdout
    );
    let task_id = extract_submitted_task_id(&run_result.stdout);
    assert!(!task_id.is_empty(), "Expected a task ID from agent run");

    // Register custom status and result responses that simulate a completed
    // agent task whose answer is the 100th prime number (541).
    mock_server.set_command_status_response(
        &task_id,
        serde_json::json!({
            "id": &task_id,
            "statusCode": 200,
            "processState": "done",
            "isDone": true,
            "message": "Successfully computed the 100th prime number",
            "agentHistory": {"states": [
                {"step": 1, "instruction": "给出第100个素数", "summary": "第100个素数是541"}
            ]},
        }),
    );
    mock_server.set_command_result_response(&task_id, r#"{"summary":"第100个素数是541。"}"#);

    // ---- Phase 3: agent list (task should appear with completed label) ----
    let list1 = run_command(ctx, &["agent", "list"]);
    let output1 = strip_snapshot_output(&list1.stdout);
    assert!(
        output1.contains(&task_id),
        "agent list after run should show the task ID '{}'. Got:\n{}",
        task_id,
        output1
    );
    assert!(
        output1.contains("completed"),
        "agent list after run should show 'completed' label. Got:\n{}",
        output1
    );
    assert!(
        !output1.contains("\"done\""),
        "agent list should NOT show deprecated 'done' label. Got:\n{}",
        output1
    );

    // ---- Phase 4: agent status ----
    let status_result = run_command(ctx, &["agent", "status", &task_id]);
    let status_output = strip_snapshot_output(&status_result.stdout);
    assert!(
        status_output.contains(&task_id),
        "agent status should reference the task ID '{}'. Got:\n{}",
        task_id,
        status_output
    );
    assert!(
        status_output.contains("\"statusCode\":200")
            || status_output.contains("\"statusCode\": 200"),
        "agent status should include integer statusCode 200. Got:\n{}",
        status_output
    );

    // ---- Phase 5: agent list after status (task still tracked) ----
    let list2 = run_command(ctx, &["agent", "list"]);
    let output2 = strip_snapshot_output(&list2.stdout);
    assert!(
        output2.contains(&task_id),
        "agent list after status should still show the task '{}'. Got:\n{}",
        task_id,
        output2
    );
    assert!(
        output2.contains("completed"),
        "agent list after status should still show 'completed'. Got:\n{}",
        output2
    );

    // ---- Phase 6: agent result (must contain the 100th prime: 541) ----
    let result_result = run_command(ctx, &["agent", "result", &task_id]);
    let result_output = strip_snapshot_output(&result_result.stdout);
    assert!(
        !result_output.trim().is_empty() && result_output.trim() != "null",
        "agent result should return content, not null/empty. Got:\n{}",
        result_output
    );
    assert!(
        result_output.contains("summary"),
        "agent result should be a CommandResult JSON with 'summary' field. Got:\n{}",
        result_output
    );
    assert!(
        result_output.contains("541"),
        "agent result MUST contain the 100th prime number (541). Got:\n{}",
        result_output
    );

    // ---- Phase 7: Final agent list (task still tracked after result) ----
    let list3 = run_command(ctx, &["agent", "list"]);
    let output3 = strip_snapshot_output(&list3.stdout);
    assert!(
        output3.contains(&task_id),
        "Final agent list should still show the task '{}'. Got:\n{}",
        task_id,
        output3
    );
    assert!(
        output3.contains("completed"),
        "Final agent list should still show 'completed'. Got:\n{}",
        output3
    );

    // ---- Verify mock server recorded all expected interactions ----
    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.plain_commands, vec!["给出第100个素数".to_string()]);
    assert!(
        snapshot.status_queries.iter().any(|q| q == &task_id),
        "Expected status queries for task '{}', got {:?}",
        task_id,
        snapshot.status_queries
    );
    assert!(
        snapshot.result_queries.iter().any(|q| q == &task_id),
        "Expected result queries for task '{}', got {:?}",
        task_id,
        snapshot.result_queries
    );
}

pub(super) fn test_prefixed_flat_forms_are_rejected(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    run_command_expecting_failure(
        ctx,
        &["agent-run", "collect the latest updates"],
        "Use 'browser4-cli agent run' instead.",
    );
    run_command_expecting_failure(
        ctx,
        &["swarm-create"],
        "Use 'browser4-cli swarm create' instead.",
    );
    run_command_expecting_failure(
        ctx,
        &["co", "create"],
        "Use 'browser4-cli swarm <subcommand>' instead.",
    );
    // help <flat-form> no longer rejected — the help handler intentionally
    // accepts flat-form command names.  Spaced-form preference is enforced
    // during command dispatch, not during help lookups.
}

pub(super) fn test_swarm_submission_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_swarm_session(ctx);
    assert_swarm_session_call(&mock_server);

    let seed_file = ctx.workspace_dir.join("swarm-seeds.txt");
    fs::write(
        &seed_file,
        b"# seed urls\nhttps://example.com/seed-1\n\nhttps://example.com/seed-2\n",
    )
    .expect("write seed file failed");
    let seed_file_arg = format!("--seed-file={}", seed_file.to_string_lossy());

    let swarm_submit_result = run_command(
        ctx,
        &[
            "swarm",
            "submit",
            "https://example.com/direct",
            &seed_file_arg,
            "--deadline=2026-03-30T00:00:00Z",
            "--expires=1d",
            "--refresh",
            "--parse",
        ],
    );
    assert!(
        swarm_submit_result.stdout.contains("3 URL(s) submitted."),
        "Expected aggregate swarm submit output in:\n{}",
        swarm_submit_result.stdout
    );
    assert!(
        swarm_submit_result
            .stdout
            .contains("Task Submitted: https://example.com/direct"),
        "Expected direct URL submission output in:\n{}",
        swarm_submit_result.stdout
    );

    let swarm_status_result = run_command(ctx, &["swarm", "status", "swarm-job-42"]);
    let swarm_status_payload = strip_snapshot_output(&swarm_status_result.stdout);
    assert!(
        swarm_status_payload.contains("swarm-job-42"),
        "Expected scrape status payload to contain the task id in:\n{}",
        swarm_status_result.stdout
    );
    assert!(
        swarm_status_payload.contains(r#""isDone": false"#)
            || swarm_status_payload.contains(r#""isDone":false"#),
        "Expected scrape status payload to remain in-progress in:\n{}",
        swarm_status_result.stdout
    );

    let swarm_result_result = run_command(ctx, &["swarm", "result", "swarm-job-42"]);
    let swarm_result_payload = strip_snapshot_output(&swarm_result_result.stdout);
    assert!(
        swarm_result_payload.contains("swarm-job-42"),
        "Expected scrape result payload to contain the task id in:\n{}",
        swarm_result_result.stdout
    );
    // The result payload contains resultSet and error, but not isDone
    // (isDone is only present in swarm status, not swarm result).
    assert!(
        swarm_result_payload.contains("mock.browser4.local/result/swarm-job-42"),
        "Expected scrape result payload to contain a resultSet in:\n{}",
        swarm_result_result.stdout
    );
    assert!(
        swarm_result_payload.contains(r#""error": null"#)
            || swarm_result_payload.contains(r#""error":null"#),
        "Expected scrape result payload to have no error in:\n{}",
        swarm_result_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec![
            "https://example.com/direct -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse"
                .to_string(),
            "https://example.com/seed-1 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse"
                .to_string(),
            "https://example.com/seed-2 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse"
                .to_string(),
        ]
    );
    assert!(
        snapshot
            .tool_calls
            .iter()
            .all(|call| call.tool != "command_run"
                && call.tool != "command_status"
                && call.tool != "command_result"),
        "Expected swarm submission/status/result to avoid MCP command_* calls: {:?}",
        snapshot.tool_calls
    );
    assert_eq!(snapshot.status_queries, vec!["swarm-job-42".to_string()]);
    assert_eq!(snapshot.result_queries, vec!["swarm-job-42".to_string()]);
}

pub(super) fn test_swarm_query_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_swarm_session(ctx);
    assert_swarm_session_call(&mock_server);

    let seed_file = ctx.workspace_dir.join("swarm-query-seeds.txt");
    fs::write(
        &seed_file,
        b"# seed urls for query\nhttps://example.com/query-seed-1\n\nhttps://example.com/query-seed-2\n",
    )
    .expect("write seed file failed");
    let seed_file_arg = format!("--seed-file={}", seed_file.to_string_lossy());

    let query_sql = "select dom_base_uri(dom) as url from load_and_select('@url', ':root')";

    let swarm_query_result = run_command(
        ctx,
        &[
            "swarm",
            "query",
            "https://example.com/direct-query",
            "--sql",
            query_sql,
            &seed_file_arg,
            "--deadline=2026-03-30T00:00:00Z",
            "--expires=1d",
            "--refresh",
        ],
    );
    assert!(
        swarm_query_result
            .stdout
            .contains("Query Submitted: https://example.com/direct-query"),
        "Expected direct URL query output in:\n{}",
        swarm_query_result.stdout
    );
    assert!(
        swarm_query_result.stdout.contains("3 URL(s) queried."),
        "Expected aggregate query count in:\n{}",
        swarm_query_result.stdout
    );

    let swarm_status_result = run_command(ctx, &["swarm", "status", "swarm-job-42"]);
    let swarm_status_payload = strip_snapshot_output(&swarm_status_result.stdout);
    assert!(
        swarm_status_payload.contains("swarm-job-42"),
        "Expected query status payload to contain the task id in:\n{}",
        swarm_status_result.stdout
    );
    assert!(
        swarm_status_payload.contains(r#""isDone": false"#)
            || swarm_status_payload.contains(r#""isDone":false"#),
        "Expected query status payload to remain in-progress in:\n{}",
        swarm_status_result.stdout
    );

    let swarm_result_result = run_command(ctx, &["swarm", "result", "swarm-job-42"]);
    let swarm_result_payload = strip_snapshot_output(&swarm_result_result.stdout);
    assert!(
        swarm_result_payload.contains("swarm-job-42"),
        "Expected query result payload to contain the task id in:\n{}",
        swarm_result_result.stdout
    );
    // The result payload contains resultSet and error, but not isDone
    // (isDone is only present in swarm status, not swarm result).
    assert!(
        swarm_result_payload.contains("mock.browser4.local/result/swarm-job-42"),
        "Expected query result payload to contain a resultSet in:\n{}",
        swarm_result_result.stdout
    );
    assert!(
        swarm_result_payload.contains(r#""error": null"#)
            || swarm_result_payload.contains(r#""error":null"#),
        "Expected query result payload to have no error in:\n{}",
        swarm_result_result.stdout
    );

    let snapshot = mock_server.snapshot();
    // Verify that the X-SQL queries were submitted via /api/swarm/query (REST),
    // not through MCP command_* calls.
    assert_eq!(
        snapshot.swarm_queries.len(),
        3,
        "Expected three swarm query submissions (1 direct + 2 seed), got {:?}",
        snapshot.swarm_queries
    );
    // Each query payload should contain url, args, and query fields.
    for (i, query) in snapshot.swarm_queries.iter().enumerate() {
        assert!(
            query.get("url").and_then(|v| v.as_str()).is_some(),
            "Query {i} missing url field: {query}"
        );
        assert!(
            query.get("query").and_then(|v| v.as_str()) == Some(query_sql),
            "Query {i} missing or mismatched query field: {query}"
        );
    }
    assert!(
        snapshot
            .tool_calls
            .iter()
            .all(|call| call.tool != "command_run"
                && call.tool != "command_status"
                && call.tool != "command_result"),
        "Expected swarm query/status/result to avoid MCP command_* calls: {:?}",
        snapshot.tool_calls
    );
    assert_eq!(snapshot.status_queries, vec!["swarm-job-42".to_string()]);
    assert_eq!(snapshot.result_queries, vec!["swarm-job-42".to_string()]);
}

pub(super) fn test_swarm_command_help_and_validation(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let swarm_create_help = run_command(ctx, &["help", "swarm", "create"]);
    assert!(
        swarm_create_help
            .stdout
            .contains("browser4-cli swarm create"),
        "Expected swarm create usage in:\n{}",
        swarm_create_help.stdout
    );
    assert!(
        !swarm_create_help
            .stdout
            .contains("browser4-cli swarm-create"),
        "Expected flat swarm-create form to be absent in:\n{}",
        swarm_create_help.stdout
    );
    assert!(
        swarm_create_help.stdout.contains("--max-browser-contexts"),
        "Expected swarm-create options in:\n{}",
        swarm_create_help.stdout
    );

    let swarm_submit_help = run_command(ctx, &["help", "swarm", "submit"]);
    assert!(
        swarm_submit_help.stdout.contains("--seed-file"),
        "Expected seed-file option in:\n{}",
        swarm_submit_help.stdout
    );
    assert!(
        swarm_submit_help
            .stdout
            .contains("blank lines and lines beginning with `#` are ignored"),
        "Expected seed-file note in:\n{}",
        swarm_submit_help.stdout
    );
    assert!(
        swarm_submit_help
            .stdout
            .contains("browser4-cli swarm submit https://example.com/direct"),
        "Expected swarm-submit example in:\n{}",
        swarm_submit_help.stdout
    );

    let swarm_status_help = run_command(ctx, &["help", "swarm", "status"]);
    assert!(
        swarm_status_help
            .stdout
            .contains("browser4-cli swarm status <task-id>"),
        "Expected swarm-status example in:\n{}",
        swarm_status_help.stdout
    );

    let swarm_result_help = run_command(ctx, &["help", "swarm", "result"]);
    assert!(
        swarm_result_help
            .stdout
            .contains("browser4-cli swarm result <task-id>"),
        "Expected swarm-result example in:\n{}",
        swarm_result_help.stdout
    );

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let submit_failure = run_command_expecting_failure(
        ctx,
        &["swarm", "submit"],
        "A URL or --seed-file is required.",
    );
    let submit_failure_output = format!("{}\n{}", submit_failure.stdout, submit_failure.stderr);
    assert!(
        submit_failure_output.contains("A URL or --seed-file is required."),
        "Expected swarm-submit validation error in:\n{}",
        submit_failure_output
    );
}

pub(super) fn test_swarm_status_validation_missing_id(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // The arg parser catches missing required positional args before the
    // handler runs — verify the CLI rejects `swarm status` with no ID.
    let failure =
        run_command_expecting_failure(ctx, &["swarm", "status"], "Missing required argument");
    let output = format!("{}\n{}", failure.stdout, failure.stderr);
    assert!(
        output.contains("Missing required argument"),
        "Expected swarm-status arg-parser validation error in:\n{}",
        output
    );
    // Verify no HTTP requests were made to the mock
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot.status_queries.is_empty(),
        "Expected no status queries for missing-ID validation, got {:?}",
        snapshot.status_queries
    );
}

pub(super) fn test_swarm_result_validation_missing_id(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // The arg parser catches missing required positional args before the
    // handler runs — verify the CLI rejects `swarm result` with no ID.
    let failure =
        run_command_expecting_failure(ctx, &["swarm", "result"], "Missing required argument");
    let output = format!("{}\n{}", failure.stdout, failure.stderr);
    assert!(
        output.contains("Missing required argument"),
        "Expected swarm-result arg-parser validation error in:\n{}",
        output
    );
    // Verify no HTTP requests were made to the mock
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot.result_queries.is_empty(),
        "Expected no result queries for missing-ID validation, got {:?}",
        snapshot.result_queries
    );
}

pub(super) fn test_swarm_query_validation_errors(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // Validation 1: Missing URL and --seed-file — caught by early validation
    // before server start (url is optional to allow --seed-file).
    let failure = run_command_expecting_failure(
        ctx,
        &["swarm", "query", "--sql=SELECT 1"],
        "A URL or --seed-file is required.",
    );
    let output = format!("{}\n{}", failure.stdout, failure.stderr);
    assert!(
        output.contains("A URL or --seed-file is required."),
        "Expected swarm-query missing-URL validation in:\n{}",
        output
    );

    // Validation 2: Missing --sql requires a mock server to establish
    // a swarm session before the handler validates the SQL requirement.
    let mock_server = start_mock_swarm_session(ctx);
    assert_swarm_session_call(&mock_server);

    let failure = run_command_expecting_failure(
        ctx,
        &["swarm", "query", "https://example.com"],
        "--sql is required",
    );
    let output = format!("{}\n{}", failure.stdout, failure.stderr);
    assert!(
        output.contains("--sql is required"),
        "Expected swarm-query missing-SQL validation in:\n{}",
        output
    );

    // Verify no HTTP requests were made for the validation (it should
    // fail before reaching the REST layer).
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot.swarm_queries.is_empty(),
        "Expected no swarm query submissions for SQL validation, got {:?}",
        snapshot.swarm_queries
    );
}

pub(super) fn test_swarm_list_and_clear(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_swarm_session(ctx);
    assert_swarm_session_call(&mock_server);

    // Submit a task so there's something to list
    run_command(ctx, &["swarm", "submit", "https://example.com/list-test"]);

    // List should show the submitted task
    let list_result = run_command(ctx, &["swarm", "list"]);
    assert!(
        list_result.stdout.contains("swarm"),
        "Expected swarm list to contain 'swarm' in:\n{}",
        list_result.stdout
    );

    // Clear the tracked swarm tasks
    let clear_result = run_command(ctx, &["swarm", "list", "--clear"]);
    let clear_output = strip_snapshot_output(&clear_result.stdout);
    assert!(
        clear_output.contains("Cleared"),
        "Expected clear confirmation in:\n{}",
        clear_result.stdout
    );

    // After clearing, list should show no swarm tasks
    let list_after = run_command(ctx, &["swarm", "list"]);
    let list_after_output = strip_snapshot_output(&list_after.stdout);
    assert!(
        list_after_output.contains("No tracked async tasks")
            || !list_after_output.contains("swarm-task-"),
        "Expected empty swarm list after clear in:\n{}",
        list_after.stdout
    );
}

pub(super) fn test_swarm_close_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_swarm_session(ctx);
    assert_swarm_session_call(&mock_server);

    let close_result = run_command(ctx, &["swarm", "close"]);
    let close_output = strip_snapshot_output(&close_result.stdout);
    assert!(
        close_output.contains("session closed") || close_output.contains("Swarm"),
        "Expected session-close confirmation in:\n{}",
        close_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.close_session_calls,
        vec!["SWARM".to_string()],
        "Expected close_session call with SWARM session ID, got {:?}",
        snapshot.close_session_calls
    );
    assert_eq!(
        snapshot.swarm_close_calls, 1,
        "swarm close must ask the backend to abort pending tasks (DELETE /api/swarm)"
    );
}

// ---------------------------------------------------------------------------
// crawl tests
// ---------------------------------------------------------------------------

pub(super) fn test_crawl_submission_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_crawl_session(ctx);

    let seed_file = ctx.workspace_dir.join("crawl-seeds.txt");
    fs::write(
        &seed_file,
        b"# seed urls\nhttps://example.com/crawl-seed-1\n\nhttps://example.com/crawl-seed-2\n",
    )
    .expect("write seed file failed");
    let seed_file_arg = format!("--seed-file={}", seed_file.to_string_lossy());

    let crawl_submit_result = run_command(
        ctx,
        &[
            "crawl",
            "https://example.com/crawl-direct",
            &seed_file_arg,
            "--background",
            "--depth=2",
            "--refresh",
            "--parse",
        ],
    );
    assert!(
        crawl_submit_result
            .stdout
            .contains("Crawl task submitted: crawl-job-42"),
        "Expected crawl task submission output in:\n{}",
        crawl_submit_result.stdout
    );
    assert!(
        crawl_submit_result.stdout.contains("URLs: 3"),
        "Expected 3 URLs (1 direct + 2 seed) in:\n{}",
        crawl_submit_result.stdout
    );

    let crawl_status_result = run_command(ctx, &["crawl", "status", "crawl-job-42"]);
    let crawl_status_payload = strip_snapshot_output(&crawl_status_result.stdout);
    assert!(
        crawl_status_payload.contains("crawl-job-42"),
        "Expected crawl status payload to contain the task id in:\n{}",
        crawl_status_result.stdout
    );

    let crawl_result_result = run_command(ctx, &["crawl", "result", "crawl-job-42"]);
    let crawl_result_payload = strip_snapshot_output(&crawl_result_result.stdout);
    assert!(
        crawl_result_payload.contains("crawl-job-42"),
        "Expected crawl result payload to contain the task id in:\n{}",
        crawl_result_result.stdout
    );
    assert!(
        crawl_result_payload.contains(r#""status": "OK""#)
            || crawl_result_payload.contains(r#""status":"OK""#),
        "Expected crawl result payload to have status OK in:\n{}",
        crawl_result_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert!(
        snapshot
            .tool_calls
            .iter()
            .any(|call| call.tool == "crawl_submit"),
        "Expected crawl_submit MCP call, got tool_calls: {:?}",
        snapshot.tool_calls
    );
    assert!(
        snapshot
            .tool_calls
            .iter()
            .all(|call| call.tool != "command_run"
                && call.tool != "command_status"
                && call.tool != "command_result"),
        "Expected crawl commands to avoid MCP command_* calls: {:?}",
        snapshot.tool_calls
    );
    assert_eq!(
        snapshot.crawl_submissions.len(),
        1,
        "Expected one crawl submission, got {:?}",
        snapshot.crawl_submissions
    );
    assert_eq!(snapshot.status_queries, vec!["crawl-job-42".to_string()]);
    assert_eq!(snapshot.result_queries, vec!["crawl-job-42".to_string()]);
}

pub(super) fn test_crawl_lifecycle_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_crawl_session(ctx);

    // Submit a crawl to get a task ID tracked
    let _submit = run_command(
        ctx,
        &[
            "crawl",
            "https://example.com/crawl-lifecycle",
            "--background",
        ],
    );

    // Cancel the task
    let cancel_result = run_command(ctx, &["crawl", "cancel", "crawl-job-42"]);
    assert!(
        cancel_result.stdout.contains("cancelled"),
        "Expected cancel confirmation in:\n{}",
        cancel_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.crawl_cancel_calls,
        vec!["crawl-job-42".to_string()]
    );

    // Clear terminal tasks
    let clear_result = run_command(ctx, &["crawl", "clear"]);
    assert!(
        clear_result.stdout.contains("Cleared") || clear_result.stdout.contains("terminal"),
        "Expected clear confirmation in:\n{}",
        clear_result.stdout
    );

    let snapshot2 = mock_server.snapshot();
    assert!(
        snapshot2.crawl_clear_calls > 0,
        "Expected at least one crawl clear call"
    );
}

pub(super) fn test_crawl_command_help_and_validation(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // help crawl
    let crawl_help = run_command(ctx, &["help", "crawl"]);
    assert!(
        crawl_help.stdout.contains("browser4-cli crawl"),
        "Expected crawl usage in:\n{}",
        crawl_help.stdout
    );
    assert!(
        crawl_help.stdout.contains("--depth"),
        "Expected --depth option in:\n{}",
        crawl_help.stdout
    );
    assert!(
        crawl_help.stdout.contains("--seed-file"),
        "Expected --seed-file option in:\n{}",
        crawl_help.stdout
    );
    assert!(
        crawl_help.stdout.contains("--background"),
        "Expected --background option in:\n{}",
        crawl_help.stdout
    );
    assert!(
        crawl_help.stdout.contains("--sql"),
        "Expected --sql option in:\n{}",
        crawl_help.stdout
    );

    // help crawl-status
    let crawl_status_help = run_command(ctx, &["help", "crawl", "status"]);
    assert!(
        crawl_status_help
            .stdout
            .contains("browser4-cli crawl status"),
        "Expected crawl-status usage in:\n{}",
        crawl_status_help.stdout
    );

    // help crawl-result
    let crawl_result_help = run_command(ctx, &["help", "crawl", "result"]);
    assert!(
        crawl_result_help
            .stdout
            .contains("browser4-cli crawl result"),
        "Expected crawl-result usage in:\n{}",
        crawl_result_help.stdout
    );

    // help crawl-cancel
    let crawl_cancel_help = run_command(ctx, &["help", "crawl", "cancel"]);
    assert!(
        crawl_cancel_help
            .stdout
            .contains("browser4-cli crawl cancel"),
        "Expected crawl-cancel usage in:\n{}",
        crawl_cancel_help.stdout
    );

    // help crawl-clear
    let crawl_clear_help = run_command(ctx, &["help", "crawl", "clear"]);
    assert!(
        crawl_clear_help.stdout.contains("browser4-cli crawl clear"),
        "Expected crawl-clear usage in:\n{}",
        crawl_clear_help.stdout
    );

    // help crawl-list
    let crawl_list_help = run_command(ctx, &["help", "crawl", "list"]);
    assert!(
        crawl_list_help.stdout.contains("browser4-cli crawl list"),
        "Expected crawl-list usage in:\n{}",
        crawl_list_help.stdout
    );

    // Validation: crawl with no URL and no --seed-file
    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let submit_failure = run_command_expecting_failure(
        ctx,
        &["crawl"],
        "No URLs provided. Specify a URL argument or --seed-file.",
    );
    let submit_failure_output = format!("{}\n{}", submit_failure.stdout, submit_failure.stderr);
    assert!(
        submit_failure_output.contains("No URLs provided"),
        "Expected crawl validation error in:\n{}",
        submit_failure_output
    );
}

pub(super) fn test_crawl_with_seed_file(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_crawl_session(ctx);

    let seed_file = ctx.workspace_dir.join("crawl-seeds-only.txt");
    fs::write(
        &seed_file,
        b"# seed urls only\nhttps://example.com/seed-page-1\n\nhttps://example.com/seed-page-2\n",
    )
    .expect("write seed file failed");
    let seed_file_arg = format!("--seed-file={}", seed_file.to_string_lossy());

    let result = run_command(ctx, &["crawl", &seed_file_arg, "--background", "--depth=0"]);
    assert!(
        result.stdout.contains("Crawl task submitted: crawl-job-42"),
        "Expected crawl task submission from seed file in:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("URLs: 2"),
        "Expected 2 URLs from seed file in:\n{}",
        result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.crawl_submissions.len(),
        1,
        "Expected one crawl submission from seed file, got {:?}",
        snapshot.crawl_submissions
    );
    let submission = &snapshot.crawl_submissions[0];
    let urls = submission["urls"].as_array().unwrap();
    assert_eq!(
        urls.len(),
        2,
        "Expected 2 URLs in submission, got {:?}",
        urls
    );
    assert_eq!(urls[0].as_str().unwrap(), "https://example.com/seed-page-1");
    assert_eq!(urls[1].as_str().unwrap(), "https://example.com/seed-page-2");
}

// ---------------------------------------------------------------------------
// crawl — foreground (non-background) polling path
// ---------------------------------------------------------------------------

pub(super) fn test_crawl_foreground(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_crawl_session(ctx);

    // Submit a crawl WITHOUT --background — exercises the foreground
    // polling loop. The mock server's /api/crawl/{id}/result always
    // returns status=OK immediately, so the CLI should complete on
    // the first poll.
    let result = run_command(
        ctx,
        &[
            "crawl",
            "https://example.com/foreground-test",
            "--depth=0",
            "--refresh",
        ],
    );

    let stdout = &result.stdout;
    assert!(
        stdout.contains("Crawl task submitted: crawl-job-42"),
        "Expected submission confirmation in:\n{}",
        stdout
    );
    assert!(
        stdout.contains("Crawl completed. 1 pages found.")
            || stdout.contains("Crawl completed. 1 pages found"),
        "Expected foreground completion message in:\n{}",
        stdout
    );
    assert!(
        stdout.contains("Mock Crawled Page"),
        "Expected crawled page title in output:\n{}",
        stdout
    );
    // Foreground path should NOT print the background tip
    assert!(
        !stdout.contains("Running in background"),
        "Foreground crawl should not mention background mode:\n{}",
        stdout
    );

    let snapshot = mock_server.snapshot();
    assert!(
        snapshot
            .tool_calls
            .iter()
            .any(|call| call.tool == "crawl_submit"),
        "Expected crawl_submit MCP call"
    );
    // result_queries should have been called (the polling loop)
    assert!(
        !snapshot.result_queries.is_empty(),
        "Expected result queries from foreground polling"
    );
}

pub(super) fn test_crawl_foreground_with_sql(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let _mock_server = start_mock_crawl_session(ctx);

    // Foreground crawl with --sql to exercise the extracted-data formatting path
    let result = run_command(
        ctx,
        &[
            "crawl",
            "https://example.com/sql-test",
            "--depth=0",
            "--sql",
            "SELECT dom_first_text(dom, 'h1') FROM load_and_select(@url, ':root')",
        ],
    );

    let stdout = &result.stdout;
    assert!(
        stdout.contains("Crawl task submitted: crawl-job-42"),
        "Expected submission in:\n{}",
        stdout
    );
    assert!(
        stdout.contains("X-SQL extraction: enabled"),
        "Expected X-SQL indicator in:\n{}",
        stdout
    );
    // The mock server result page doesn't have extracted data, so we should
    // see either "No extracted data" or a completion message
    assert!(
        stdout.contains("No extracted data") || stdout.contains("Crawl completed"),
        "Expected completion or no-data message in:\n{}",
        stdout
    );
}

pub(super) fn test_crawl_with_sql_and_csv_format(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let _mock_server = start_mock_crawl_session(ctx);

    let result = run_command(
        ctx,
        &[
            "crawl",
            "https://example.com/csv-test",
            "--depth=0",
            "--sql",
            "SELECT dom_first_text(dom, 'h1') FROM load_and_select(@url, ':root')",
            "--format",
            "csv",
        ],
    );

    let stdout = &result.stdout;
    assert!(
        stdout.contains("No extracted data") || stdout.contains("Crawl completed"),
        "Expected output in:\n{}",
        stdout
    );
}

pub(super) fn test_crawl_with_output_file(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let _mock_server = start_mock_crawl_session(ctx);

    let output_path = ctx.workspace_dir.join("crawl-output.txt");
    let output_arg = format!("--output={}", output_path.to_string_lossy());

    let result = run_command(
        ctx,
        &[
            "crawl",
            "https://example.com/file-output-test",
            "--depth=0",
            &output_arg,
        ],
    );

    let stdout = &result.stdout;
    assert!(
        stdout.contains("Results written to") || stdout.contains("Crawl completed"),
        "Expected file-written confirmation in:\n{}",
        stdout
    );

    // Verify the output file was actually written
    let file_content = fs::read_to_string(&output_path).unwrap_or_default();
    assert!(
        !file_content.is_empty(),
        "Expected output file to be non-empty, but {} is empty or missing",
        output_path.display()
    );
    assert!(
        file_content.contains("Mock Crawled Page") || file_content.contains("pages found"),
        "Expected page info in output file, got:\n{}",
        file_content
    );
}

// ---------------------------------------------------------------------------
// crawl — subcommand validation (missing task ID errors)
// ---------------------------------------------------------------------------

pub(super) fn test_crawl_status_missing_id(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let _mock_server = start_mock_crawl_session(ctx);

    // The CLI argument parser catches the missing required <id> argument
    // before the handler runs, so the error is "Missing required argument".
    let result =
        run_command_expecting_failure(ctx, &["crawl", "status"], "Missing required argument");
    assert!(
        result.stdout.contains("Missing required argument")
            || result.stderr.contains("Missing required argument"),
        "Expected 'Missing required argument' in output:\nstdout: {}\nstderr: {}",
        result.stdout,
        result.stderr
    );
}

pub(super) fn test_crawl_result_missing_id(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let _mock_server = start_mock_crawl_session(ctx);

    let result =
        run_command_expecting_failure(ctx, &["crawl", "result"], "Missing required argument");
    assert!(
        result.stdout.contains("Missing required argument")
            || result.stderr.contains("Missing required argument"),
        "Expected 'Missing required argument' in output"
    );
}

pub(super) fn test_crawl_cancel_missing_id(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let _mock_server = start_mock_crawl_session(ctx);

    let result =
        run_command_expecting_failure(ctx, &["crawl", "cancel"], "Missing required argument");
    assert!(
        result.stdout.contains("Missing required argument")
            || result.stderr.contains("Missing required argument"),
        "Expected 'Missing required argument' in output"
    );
}

// ---------------------------------------------------------------------------
// install / upgrade (mock download server)
// ---------------------------------------------------------------------------

const INSTALL_TAG: &str = "--tag=v4.10.0";

/// Redirect `BROWSER4_RUNTIME_DIR` at a fresh per-test subdir of the shared
/// runtime dir and return it.
///
/// `reset_cli_artifacts` keeps the shared runtime dir intact once a
/// CLI-managed backend may be running from it (deleting a live JVM's cwd
/// breaks every later process spawn on Linux/macOS — `posix_spawn failed,
/// error: 2`), so install/upgrade scenarios that need a clean install slate
/// must isolate their installs instead of relying on a wiped runtime dir.
fn isolate_runtime_dir(ctx: &mut E2ECtx, name: &str) -> PathBuf {
    let dir = ctx.runtime_dir.join(format!("iso-{name}"));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).expect("create isolated runtime dir");
    ctx.set_env("BROWSER4_RUNTIME_DIR", &dir.to_string_lossy());
    dir
}

pub(super) fn test_install_downloads_and_installs(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // The harness points BROWSER4_AGENTS_SKILLS_DIR at this temp dir so the
    // test never touches the real ~/.agents.
    let agents_skills_dir = ctx.workspace_dir.join("agents-skills");

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &download_server.base_url());

    // Use --tag so the download URL contains the real tag (without a GitHub
    // redirect, parse_release_tag_from_url needs the tag in the path).
    let result = run_command(ctx, &["install", INSTALL_TAG]);
    assert_eq!(
        result.exit_code, 0,
        "expected install to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("installed successfully"),
        "Expected 'installed successfully' in:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("v4.10.0"),
        "Expected tag in output:\n{}",
        result.stdout
    );

    // Verify the download server received a request.
    let requests = download_server.snapshot_requests();
    assert!(
        requests.iter().any(|p| p.contains("/download/")),
        "Expected at least one download request, got: {:?}",
        requests
    );

    // Verify the metadata file was written (versioned layout).
    let metadata_path = ctx
        .runtime_dir
        .join("runtime")
        .join("v4.10.0")
        .join("browser4-installation.json");
    assert!(
        metadata_path.exists(),
        "Expected install metadata at {}",
        metadata_path.display()
    );

    // Verify the runtime was extracted (lib/ subdirectory inside the versioned install).
    let lib_dir = ctx.runtime_dir.join("runtime").join("v4.10.0").join("lib");
    assert!(lib_dir.is_dir(), "Expected runtime lib dir after install");

    // Verify bundled skill files were installed alongside the runtime.
    let skills_skill_md = ctx
        .runtime_dir
        .join("runtime")
        .join("v4.10.0")
        .join("skills")
        .join("browser4-cli")
        .join("SKILL.md");
    assert!(
        skills_skill_md.is_file(),
        "Expected bundled skills unpacked to {}",
        skills_skill_md.display()
    );
    assert!(
        result.stderr.contains("Unpacked"),
        "Expected skill unpack message in stderr:\n{}",
        result.stderr
    );

    // Verify bundled skills were also installed into the AI-agent skills dir.
    let agents_skill_md = agents_skills_dir.join("browser4-cli").join("SKILL.md");
    assert!(
        agents_skill_md.is_file(),
        "Expected bundled skills installed to AI-agent skills dir {}",
        agents_skill_md.display()
    );
    assert!(
        result.stderr.contains("for AI agents"),
        "Expected AI-agent skills install message in stderr:\n{}",
        result.stderr
    );
}

pub(super) fn test_install_skips_when_already_installed(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let agents_skills_dir = ctx.workspace_dir.join("agents-skills");

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &download_server.base_url());

    // First install.
    let first = run_command(ctx, &["install", INSTALL_TAG]);
    assert_eq!(first.exit_code, 0);

    // Second install — should skip download (already has v4.10.0).
    let second = run_command(ctx, &["install", INSTALL_TAG]);
    assert_eq!(second.exit_code, 0);
    assert!(
        second.stdout.contains("already installed"),
        "Expected 'already installed' in:\n{}",
        second.stdout
    );
    // Skills are still re-synced on the reuse path, but nothing is rewritten.
    assert!(
        second.stderr.contains("already up to date"),
        "Expected 'already up to date' skill sync message in stderr:\n{}",
        second.stderr
    );
    // The AI-agent skills dir is refreshed too (idempotent no-op).
    let agents_skill_md = agents_skills_dir.join("browser4-cli").join("SKILL.md");
    assert!(
        agents_skill_md.is_file(),
        "Expected AI-agent skills still present at {}",
        agents_skill_md.display()
    );
    assert!(
        second.stderr.contains("already up to date for AI agents"),
        "Expected AI-agent skills 'up to date' message in stderr:\n{}",
        second.stderr
    );
}

pub(super) fn test_install_force_re_downloads(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &download_server.base_url());

    // First install.
    run_command(ctx, &["install", INSTALL_TAG]);

    let requests_before = download_server.snapshot_requests().len();

    // Second install with --force — should download again.
    let result = run_command(ctx, &["install", INSTALL_TAG, "--force"]);
    assert_eq!(result.exit_code, 0);
    assert!(
        result.stdout.contains("installed successfully"),
        "Expected 'installed successfully' with --force:\n{}",
        result.stdout
    );

    let requests_after = download_server.snapshot_requests().len();
    assert!(
        requests_after > requests_before,
        "Expected additional download request with --force"
    );
}

pub(super) fn test_install_specific_tag(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.9.3");
    let download_server = FixtureDownloadServer::start(bundle_bytes, "v4.9.3");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &download_server.base_url());

    let result = run_command(ctx, &["install", "--tag=v4.9.3"]);
    assert_eq!(
        result.exit_code, 0,
        "expected install --tag to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("installed successfully"),
        "Expected 'installed successfully' in:\n{}",
        result.stdout
    );

    // Verify the download URL contained the requested tag.
    let requests = download_server.snapshot_requests();
    let has_tagged_url = requests.iter().any(|p| p.contains("v4.9.3"));
    assert!(
        has_tagged_url,
        "Expected download request containing v4.9.3, got: {:?}",
        requests
    );
}

pub(super) fn test_upgrade_already_latest(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // Never touch npm or download/execute the real install scripts.
    ctx.set_env("BROWSER4_CLI_SKIP_SELF_UPGRADE", "1");

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &download_server.base_url());

    // Install first.
    run_command(ctx, &["install", INSTALL_TAG]);

    // Upgrade to same version — should say already latest.
    let result = run_command(ctx, &["upgrade", INSTALL_TAG]);
    assert_eq!(result.exit_code, 0);
    assert!(
        result.stdout.contains("already at the latest version"),
        "Expected 'already at the latest version' in:\n{}",
        result.stdout
    );
}

pub(super) fn test_upgrade_to_new_version(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    // Install into an isolated runtime dir so a pre-existing runtime from
    // earlier scenarios (kept alive while a CLI-managed backend runs) does
    // not short-circuit the install/upgrade flow.
    let runtime_dir = isolate_runtime_dir(ctx, "upgrade-to-new");

    // Never touch npm or download/execute the real install scripts.
    ctx.set_env("BROWSER4_CLI_SKIP_SELF_UPGRADE", "1");

    // The harness points BROWSER4_AGENTS_SKILLS_DIR at this temp dir.
    let agents_skills_dir = ctx.workspace_dir.join("agents-skills");

    // Install an older version first.
    let (old_bundle, _) = build_fake_runtime_bundle("v4.9.0");
    let server1 = FixtureDownloadServer::start(old_bundle, "v4.9.0");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &server1.base_url());
    run_command(ctx, &["install", "--tag=v4.9.0"]);
    drop(server1);

    // Now upgrade to a newer version.
    let (new_bundle, _) = build_fake_runtime_bundle("v4.10.0");
    let server2 = FixtureDownloadServer::start(new_bundle, "v4.10.0");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &server2.base_url());

    let result = run_command(ctx, &["upgrade"]);
    assert_eq!(result.exit_code, 0);
    assert!(
        result.stdout.contains("upgraded successfully"),
        "Expected 'upgraded successfully' in:\n{}",
        result.stdout
    );
    // Restart hint is now emitted on stderr.
    assert!(
        result.stderr.contains("Restart the server"),
        "Expected restart hint in stderr:\n{}",
        result.stderr
    );
    // The upgraded runtime version also received the bundled skills.  The
    // active tag is read from current.tag: when latest-tag resolution flakes
    // the CLI falls back to an `unknown-<timestamp>` identifier, and skills
    // are unpacked into whichever versioned dir the runtime landed in.
    let current_tag_path = runtime_dir.join("runtime").join("current.tag");
    let active_tag = fs::read_to_string(&current_tag_path)
        .unwrap_or_else(|e| panic!("expected current.tag after upgrade: {e}"));
    let skills_skill_md = runtime_dir
        .join("runtime")
        .join(active_tag.trim())
        .join("skills")
        .join("browser4-cli")
        .join("SKILL.md");
    assert!(
        skills_skill_md.is_file(),
        "Expected bundled skills unpacked into the upgraded install (tag '{}') at {}\nstderr:\n{}",
        active_tag.trim(),
        skills_skill_md.display(),
        result.stderr
    );
    // The AI-agent skills dir is refreshed by the upgrade too.
    let agents_skill_md = agents_skills_dir.join("browser4-cli").join("SKILL.md");
    assert!(
        agents_skill_md.is_file(),
        "Expected AI-agent skills refreshed after upgrade at {}\nstderr:\n{}",
        agents_skill_md.display(),
        result.stderr
    );
    assert!(
        result.stderr.contains("for AI agents"),
        "Expected AI-agent skills message in upgrade stderr:\n{}",
        result.stderr
    );
}

pub(super) fn test_upgrade_shows_rc_hint(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    // Isolate so a pre-existing runtime from earlier scenarios does not
    // short-circuit the install/upgrade flow.
    isolate_runtime_dir(ctx, "upgrade-rc-hint");

    // Never touch npm or download/execute the real install scripts.
    ctx.set_env("BROWSER4_CLI_SKIP_SELF_UPGRADE", "1");

    // ── Phase 1: a newer release candidate exists → the hint is shown ──
    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let with_rc = FixtureDownloadServer::start_with_rc(bundle_bytes, "v4.10.0", "v4.11.0-rc.1");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &with_rc.base_url());

    let result = run_command(ctx, &["upgrade"]);
    assert_eq!(
        result.exit_code, 0,
        "upgrade with RC metadata should succeed:\n{}",
        result.stderr
    );
    // The stable release is still what gets installed.
    assert!(
        result.stdout.contains("upgraded successfully"),
        "Expected 'upgraded successfully' in:\n{}",
        result.stdout
    );
    // The RC hint names the candidate and the way to opt in.
    assert!(
        result.stderr.contains("release candidate"),
        "Expected RC hint in stderr:\n{}",
        result.stderr
    );
    assert!(
        result.stderr.contains("v4.11.0-rc.1"),
        "Expected RC tag in stderr:\n{}",
        result.stderr
    );
    assert!(
        result.stderr.contains("upgrade --tag v4.11.0-rc.1"),
        "Expected actionable upgrade command in stderr:\n{}",
        result.stderr
    );
    drop(with_rc);

    // ── Phase 2: no RC published → no hint ──
    let (bundle_bytes2, _dir_name2) = build_fake_runtime_bundle("v4.10.0");
    let without_rc = FixtureDownloadServer::start(bundle_bytes2, "v4.10.0");
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &without_rc.base_url());

    let second = run_command(ctx, &["upgrade"]);
    assert_eq!(second.exit_code, 0);
    let combined = format!("{}\n{}", second.stdout, second.stderr);
    assert!(
        second.stdout.contains("already at the latest version"),
        "Expected 'already at the latest version' in:\n{combined}"
    );
    assert!(
        !second.stderr.contains("release candidate"),
        "RC hint must not appear when no RC metadata is published:\n{}",
        second.stderr
    );
    // The metadata endpoint must have been consulted (and found no RC).
    let requests = without_rc.snapshot_requests();
    assert!(
        requests.iter().any(|p| p.contains("latest-rc.json")),
        "Expected a latest-rc.json lookup, got: {:?}",
        requests
    );
}

pub(super) fn test_install_download_failure(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // Start a download server but don't register any bundle — all paths 404.
    // Actually, the fixture server serves 200 for /download/ paths.  To
    // simulate a failure, we point at a free port where nothing listens.
    let free_port = find_free_port();
    ctx.set_env(
        "BROWSER4_RELEASES_BASE_URL",
        &format!("http://127.0.0.1:{free_port}/releases"),
    );

    let result = run_command_allowing_failure(ctx, &["install"]);
    // install should fail because the server is unreachable.
    assert_ne!(
        result.exit_code, 0,
        "Expected install to fail when download server is unreachable"
    );
    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains("error") || combined.contains("Error") || combined.contains("failed"),
        "Expected error message in:\n{combined}"
    );
}

// ---------------------------------------------------------------------------
// install / upgrade — mirror failover
// ---------------------------------------------------------------------------

pub(super) fn test_install_mirror_failover(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    // Isolate installs from any runtime left by earlier scenarios (see
    // isolate_runtime_dir).
    let runtime_dir = isolate_runtime_dir(ctx, "mirror-failover");

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    // Start the reachable mirror (serves the fake runtime bundle).
    let download_server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");
    // Find a free port that will be unreachable (nothing listening).
    let dead_port = find_free_port();

    // Write mirrors.json with two mirrors: first unreachable, second reachable.
    let mirrors_path = runtime_dir.join("mirrors.json");
    let mirrors_json = serde_json::json!({
        "mirrors": [
            {
                "name": "dead-mirror",
                "base_url": format!("http://127.0.0.1:{}/releases", dead_port)
            },
            {
                "name": "live-mirror",
                "base_url": download_server.base_url()
            }
        ]
    });
    fs::write(&mirrors_path, mirrors_json.to_string()).expect("write mirrors.json");
    ctx.set_env("BROWSER4_MIRRORS_CONFIG", &mirrors_path.to_string_lossy());
    // IMPORTANT: do NOT set BROWSER4_RELEASES_BASE_URL — let the mirror system work.
    // Clear any stale BROWSER4_RELEASES_BASE_URL left over from earlier tests
    // (E2ECtx.extra_env persists across scenarios).
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", "");

    let result = run_command(ctx, &["install", INSTALL_TAG]);
    assert_eq!(
        result.exit_code, 0,
        "install should succeed via mirror failover:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("installed successfully"),
        "Expected 'installed successfully' in:\n{}",
        result.stdout
    );

    // Verify the download hit the live mirror.
    let requests = download_server.snapshot_requests();
    assert!(
        requests.iter().any(|p| p.contains("/download/")),
        "Expected at least one download request from the live mirror, got: {:?}",
        requests
    );
}

pub(super) fn test_install_all_mirrors_unreachable(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // Both mirrors point to free ports — neither is reachable.
    let dead1 = find_free_port();
    let dead2 = find_free_port();

    let mirrors_path = ctx.runtime_dir.join("mirrors.json");
    let mirrors_json = serde_json::json!({
        "mirrors": [
            {
                "name": "dead-one",
                "base_url": format!("http://127.0.0.1:{}/releases", dead1)
            },
            {
                "name": "dead-two",
                "base_url": format!("http://127.0.0.1:{}/releases", dead2)
            }
        ]
    });
    fs::write(&mirrors_path, mirrors_json.to_string()).expect("write mirrors.json");
    ctx.set_env("BROWSER4_MIRRORS_CONFIG", &mirrors_path.to_string_lossy());

    let result = run_command_allowing_failure(ctx, &["install"]);
    // install should fail because all mirrors are unreachable.
    assert_ne!(
        result.exit_code, 0,
        "Expected install to fail when all mirrors are unreachable"
    );
    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains("No mirror is reachable")
            || combined.contains("error")
            || combined.contains("Error")
            || combined.contains("failed"),
        "Expected fallback or error message in:\n{combined}"
    );
}

pub(super) fn test_install_loads_mirrors_json_from_runtime_dir(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    // Isolate installs from any runtime left by earlier scenarios (see
    // isolate_runtime_dir).
    let runtime_dir = isolate_runtime_dir(ctx, "mirrors-default-location");

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");

    // Write mirrors.json at the default location: {runtime_data_dir}/mirrors.json.
    // The isolated runtime dir IS the runtime data dir (set via BROWSER4_RUNTIME_DIR).
    let mirrors_path = runtime_dir.join("mirrors.json");
    let mirrors_json = serde_json::json!({
        "mirrors": [
            {
                "name": "test-mirror",
                "base_url": download_server.base_url()
            }
        ]
    });
    fs::write(&mirrors_path, mirrors_json.to_string()).expect("write mirrors.json");
    // Do NOT set BROWSER4_MIRRORS_CONFIG — the CLI should find it at its
    // default location under ctx.runtime_dir (which is the runtime data dir).
    // Do NOT set BROWSER4_RELEASES_BASE_URL — let the mirror system work.
    // Clear any stale BROWSER4_RELEASES_BASE_URL left over from earlier tests.
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", "");

    let result = run_command(ctx, &["install", INSTALL_TAG]);
    assert_eq!(
        result.exit_code, 0,
        "install should succeed with mirrors.json at default location:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("installed successfully"),
        "Expected 'installed successfully' in:\n{}",
        result.stdout
    );

    // Verify the download hit our custom mirror.
    let requests = download_server.snapshot_requests();
    assert!(
        requests.iter().any(|p| p.contains("/download/")),
        "Expected at least one download request from the custom mirror, got: {:?}",
        requests
    );
}

// ---------------------------------------------------------------------------
// install / upgrade — speed-test-based mirror selection
// ---------------------------------------------------------------------------

/// Verify that speed-testing selects the faster of two mirrors, not just the
/// first one in the list.
pub(super) fn test_install_speed_test_selects_fastest_mirror(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");

    // Two download servers: one fast, one slow.
    let fast_server = FixtureDownloadServer::start(bundle_bytes.clone(), "v4.10.0");
    let slow_server = FixtureDownloadServer::start_with_latency(
        bundle_bytes.clone(),
        "v4.10.0",
        Duration::from_secs(2),
    );

    // Put the SLOW mirror FIRST in the list so we can verify that the
    // speed test overrides simple list-order selection.
    let mirrors_path = ctx.runtime_dir.join("mirrors.json");
    let mirrors_json = serde_json::json!({
        "mirrors": [
            {
                "name": "slow-mirror",
                "base_url": slow_server.base_url()
            },
            {
                "name": "fast-mirror",
                "base_url": fast_server.base_url()
            }
        ]
    });
    fs::write(&mirrors_path, mirrors_json.to_string()).expect("write mirrors.json");
    // Clear any stale BROWSER4_RELEASES_BASE_URL left over from earlier tests.
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", "");

    let result = run_command(ctx, &["install", INSTALL_TAG, "--force"]);
    assert_eq!(
        result.exit_code, 0,
        "install should succeed with speed-test mirror selection:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("installed successfully"),
        "Expected 'installed successfully' in:\n{}",
        result.stdout
    );

    // The fast mirror should have received at least one download request
    // (either the speed-test probe or the full download).
    let fast_requests = fast_server.snapshot_requests();
    assert!(
        fast_requests.iter().any(|p| p.contains("/download/")),
        "Fast mirror should have received download requests, got: {:?}",
        fast_requests
    );

    // Verify speed-test messages appeared in stderr.
    assert!(
        result.stderr.contains("Speed-testing mirror"),
        "Expected speed-test progress messages in stderr:\n{}",
        result.stderr
    );
}

/// Verify that after a successful speed-test the mirror preference is cached and
/// the next forced install skips speed testing (even though the download cache is
/// also bypassed).
pub(super) fn test_install_mirror_preference_cache_hit(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");

    let mirrors_path = ctx.runtime_dir.join("mirrors.json");
    let mirrors_json = serde_json::json!({
        "mirrors": [
            {
                "name": "test-mirror",
                "base_url": server.base_url()
            }
        ]
    });
    fs::write(&mirrors_path, mirrors_json.to_string()).expect("write mirrors.json");
    // Clear any stale BROWSER4_RELEASES_BASE_URL left over from earlier tests.
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", "");

    // First install with --force: bypasses download cache, speed-tests mirrors,
    // caches the fastest mirror in mirror-preference.json.
    let first = run_command(ctx, &["install", INSTALL_TAG, "--force"]);
    assert_eq!(
        first.exit_code, 0,
        "first install failed:\n{}",
        first.stderr
    );
    assert!(
        first.stdout.contains("installed successfully"),
        "first install should succeed:\n{}",
        first.stdout
    );
    // First install should contain speed-test messages.
    assert!(
        first.stderr.contains("Speed-testing mirror"),
        "First install should have speed-test messages:\n{}",
        first.stderr
    );

    // Clear server request log for the second install.
    server.requests.lock().unwrap().clear();

    // Second install with --force: bypasses download cache but mirror
    // preference from step 1 is still valid — should use cached mirror.
    let second = run_command(ctx, &["install", INSTALL_TAG, "--force"]);
    assert_eq!(
        second.exit_code, 0,
        "second install failed:\n{}",
        second.stderr
    );

    // The cached preference message should appear for the second install.
    assert!(
        second.stderr.contains("Using cached mirror"),
        "Expected 'Using cached mirror' in second-install stderr:\n{}",
        second.stderr
    );
    // Second install should NOT contain speed-test messages.
    assert!(
        !second.stderr.contains("Speed-testing mirror"),
        "Second install should skip speed testing:\n{}",
        second.stderr
    );
}

/// Verify that setting BROWSER4_CLI_DISABLE_MIRROR_SPEED_TEST=1 skips the
/// speed-test phase and falls back to TCP reachability.
pub(super) fn test_install_speed_test_disabled_env_var(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let server = FixtureDownloadServer::start(bundle_bytes, "v4.10.0");

    let mirrors_path = ctx.runtime_dir.join("mirrors.json");
    let mirrors_json = serde_json::json!({
        "mirrors": [
            {
                "name": "test-mirror",
                "base_url": server.base_url()
            }
        ]
    });
    fs::write(&mirrors_path, mirrors_json.to_string()).expect("write mirrors.json");
    // Clear any stale BROWSER4_RELEASES_BASE_URL left over from earlier tests.
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", "");

    // Disable speed testing.
    ctx.set_env("BROWSER4_CLI_DISABLE_MIRROR_SPEED_TEST", "1");

    let result = run_command(ctx, &["install", INSTALL_TAG, "--force"]);
    assert_eq!(
        result.exit_code, 0,
        "install should succeed with speed tests disabled:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("installed successfully"),
        "Expected 'installed successfully' in:\n{}",
        result.stdout
    );

    // Speed-test log messages should NOT appear.
    assert!(
        !result.stderr.contains("Speed-testing mirror"),
        "Speed-test messages should not appear when disabled:\n{}",
        result.stderr
    );
}

// ---------------------------------------------------------------------------
// snapshot --stdout
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_stdout(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["snapshot", "--stdout"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot --stdout to succeed:\n{}",
        result.stderr
    );

    // --stdout prints raw snapshot content, not formatted output
    assert!(
        result.stdout.contains("mock snapshot"),
        "Expected snapshot content in stdout:\n{}",
        result.stdout
    );
    assert!(
        !result.stdout.contains("### Page"),
        "--stdout should not print formatted headers, got:\n{}",
        result.stdout
    );
    assert!(
        !result.stdout.contains("### Snapshot"),
        "--stdout should not print formatted headers, got:\n{}",
        result.stdout
    );

    // Verify browser_snapshot was called (use last to skip handle_open's auto-snapshot)
    let tool_calls = mock_server.snapshot().tool_calls;
    let snap_call = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_snapshot")
        .last()
        .expect("expected browser_snapshot tool call");
    assert!(snap_call.arguments.get("sessionId").is_some());
}

// ---------------------------------------------------------------------------
// snapshot --raw (backward compat alias for --stdout)
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_raw(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["snapshot", "--raw"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot --raw to succeed:\n{}",
        result.stderr
    );

    // --raw prints raw snapshot content, not formatted output
    assert!(
        result.stdout.contains("mock snapshot"),
        "Expected snapshot content in stdout:\n{}",
        result.stdout
    );
    assert!(
        !result.stdout.contains("### Page"),
        "--raw should not print formatted headers, got:\n{}",
        result.stdout
    );

    // Verify browser_snapshot was called (use last to skip handle_open's auto-snapshot)
    let tool_calls = mock_server.snapshot().tool_calls;
    let snap_call = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_snapshot")
        .last()
        .expect("expected browser_snapshot tool call");
    assert!(snap_call.arguments.get("sessionId").is_some());
}

// ---------------------------------------------------------------------------
// snapshot grep
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_grep(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    // The mock server returns "mock snapshot" for browser_snapshot.
    // Grep for "mock" should find a match.
    let result = run_command(ctx, &["snapshot", "grep", "mock"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("mock snapshot"),
        "Expected grep output to contain matching line:\n{}",
        result.stdout
    );

    // Grep for non-existent pattern should produce no output but succeed
    let no_match_result = run_command(ctx, &["snapshot", "grep", "nonexistent-pattern-xyz"]);
    assert_eq!(
        no_match_result.exit_code, 0,
        "expected snapshot grep (no match) to succeed:\n{}",
        no_match_result.stderr
    );

    // Verify browser_snapshot was called (twice, once for each grep)
    let tool_calls = mock_server.snapshot().tool_calls;
    let snap_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_snapshot")
        .collect();
    assert!(
        snap_calls.len() >= 2,
        "expected at least 2 browser_snapshot calls, got {}",
        snap_calls.len()
    );
}

// ---------------------------------------------------------------------------
// snapshot --count (grep short-circuit mode)
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_grep_count(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    // Put pattern before flags so --count isn't treated as consuming the pattern
    let result = run_command(ctx, &["snapshot", "grep", "mock", "--count"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot grep -c to succeed:\n{}",
        result.stderr
    );
    // --count should print just the number
    let stripped = result.stdout.trim();
    assert!(
        stripped.parse::<usize>().is_ok(),
        "Expected count output to be a number, got:\n{}",
        result.stdout
    );
}

// ---------------------------------------------------------------------------
// snapshot --viewport (ViewportSpec format)
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_viewport(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    // Test comma-separated list format
    let result = run_command(ctx, &["snapshot", "--viewport=0,2,4"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot --viewport=0,2,4 to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("[Snapshot]("),
        "Expected formatted snapshot output:\n{}",
        result.stdout
    );

    // Verify viewports was passed through to the server.
    // Use the LAST browser_snapshot call, as handle_open also calls
    // post_command_snapshot internally.
    let tool_calls = mock_server.snapshot().tool_calls;
    let snap_call = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_snapshot")
        .last()
        .expect("expected browser_snapshot tool call");
    assert_eq!(
        snap_call
            .arguments
            .get("viewports")
            .and_then(|v| v.as_str()),
        Some("0,2,4"),
        "Expected viewports to be passed through, got: {:?}",
        snap_call.arguments
    );
}

// ---------------------------------------------------------------------------
// snapshot --viewport with range format
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_viewport_range(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    // Test range format 1-3
    let result = run_command(ctx, &["snapshot", "--viewport=1-3"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot --viewport=1-3 to succeed:\n{}",
        result.stderr
    );

    // Verify viewports range was passed through (use last call to skip
    // the post_command_snapshot call from handle_open).
    let tool_calls = mock_server.snapshot().tool_calls;
    let snap_call = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_snapshot")
        .last()
        .expect("expected browser_snapshot tool call");
    assert_eq!(
        snap_call
            .arguments
            .get("viewports")
            .and_then(|v| v.as_str()),
        Some("1-3"),
        "Expected viewports=1-3, got: {:?}",
        snap_call.arguments
    );
}

// ---------------------------------------------------------------------------
// snapshot-grep -- flag coverage (-i, -v, -F, -w)
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_grep_flags(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    // --ignore-case (-i): case-insensitive match
    let result = run_command(ctx, &["snapshot", "grep", "-i", "MOCK"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -i to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("mock snapshot"),
        "Expected -i MOCK to match 'mock snapshot':\n{}",
        result.stdout
    );

    // --invert-match (-v): lines NOT matching pattern
    let result = run_command(ctx, &["snapshot", "grep", "-v", "nonexistent-pattern-xyz"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -v to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("mock snapshot"),
        "Expected -v to print non-matching line:\n{}",
        result.stdout
    );

    // --fixed-strings (-F): literal string match (not regex)
    let result = run_command(ctx, &["snapshot", "grep", "-F", "mock snap"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -F to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("mock snapshot"),
        "Expected -F 'mock snap' to match:\n{}",
        result.stdout
    );

    // -F with regex special chars should treat them literally (no match)
    let result = run_command(ctx, &["snapshot", "grep", "-F", "mock*shot"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -F with special chars to succeed:\n{}",
        result.stderr
    );
    assert!(
        !result.stdout.contains("mock snapshot"),
        "Expected -F 'mock*shot' NOT to match 'mock snapshot' (literal):\n{}",
        result.stdout
    );

    // --word-regexp (-w): whole-word match
    let result = run_command(ctx, &["snapshot", "grep", "-w", "mock"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -w to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("mock snapshot"),
        "Expected -w mock to match whole word:\n{}",
        result.stdout
    );

    // --word-regexp (-w): partial word should NOT match
    let result = run_command(ctx, &["snapshot", "grep", "-w", "moc"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -w (no match) to succeed:\n{}",
        result.stderr
    );
    assert!(
        !result.stdout.contains("mock snapshot"),
        "Expected -w moc NOT to match 'mock' (partial word):\n{}",
        result.stdout
    );

    // Combined flags: -i -v (invert case-insensitive match)
    let result = run_command(ctx, &["snapshot", "grep", "-i", "-v", "MOCK"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -i -v to succeed:\n{}",
        result.stderr
    );
    assert!(
        !result.stdout.contains("mock snapshot"),
        "Expected -i -v MOCK to exclude matching line:\n{}",
        result.stdout
    );
}

// ---------------------------------------------------------------------------
// snapshot-grep -- Unicode / Chinese text matching
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_grep_unicode(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Simulate a snapshot that contains Chinese text in YAML format
    // (mimicking what browser_snapshot returns for a Baidu search results page).
    let chinese_snapshot = r#"- document:
  - heading "百度一下"
  - link "武汉龙虾节"
  - text: 2026年武汉小龙虾消费季
  - text: 汉口江滩三阳广场
  - link "肥肥虾庄"
  - text: 不嘬虾，枉夏天"#;
    mock_server.set_browser_snapshot_response(chinese_snapshot);

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    // Basic Chinese text match — substring of a YAML node value
    let result = run_command(ctx, &["snapshot", "grep", "龙虾节"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep for Chinese text to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("武汉龙虾节"),
        "Expected '龙虾节' to match '武汉龙虾节':\n{}",
        result.stdout
    );

    // Fixed-string match with full Chinese phrase
    let result = run_command(ctx, &["snapshot", "grep", "-F", "不嘬虾，枉夏天"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -F to succeed for Chinese text:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("不嘬虾，枉夏天"),
        "Expected -F for Chinese text to match:\n{}",
        result.stdout
    );

    // Case-insensitive match (no-op for CJK, but shouldn't break)
    let result = run_command(ctx, &["snapshot", "grep", "-i", "汉口江滩"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -i for Chinese text to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("汉口江滩"),
        "Expected -i for Chinese text to match:\n{}",
        result.stdout
    );

    // Text NOT in the snapshot
    let result = run_command(ctx, &["snapshot", "grep", "不存在的文本"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep for absent Chinese text to succeed:\n{}",
        result.stderr
    );
    assert!(
        !result.stdout.contains("不存在的文本"),
        "Expected absent Chinese text NOT to match:\n{}",
        result.stdout
    );

    // -c (count) mode with Chinese text
    let result = run_command(ctx, &["snapshot", "grep", "-c", "虾"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -c for Chinese text to succeed:\n{}",
        result.stderr
    );
    let count: i32 = result.stdout.trim().parse().unwrap_or(-1);
    assert!(
        count >= 3,
        "Expected '虾' to appear at least 3 times (龙虾, 肥肥虾庄, 不嘬虾): got count={}:\n{}",
        count,
        result.stdout
    );

    // -F with literal Chinese characters containing regex-like patterns
    // (ensures the regex engine doesn't misinterpret CJK chars as regex syntax)
    let result = run_command(ctx, &["snapshot", "grep", "-F", "武汉"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -F for '武汉' to succeed:\n{}",
        result.stderr
    );
    assert!(
        result.stdout.contains("武汉龙虾节"),
        "Expected -F '武汉' to match:\n{}",
        result.stdout
    );

    // Count mode should show correct count
    let result = run_command(ctx, &["snapshot", "grep", "-c", "武汉"]);
    assert_eq!(
        result.exit_code, 0,
        "expected snapshot-grep -c for '武汉' to succeed:\n{}",
        result.stderr
    );
    let count: i32 = result.stdout.trim().parse().unwrap_or(-1);
    assert!(
        count >= 2,
        "Expected '武汉' to appear at least 2 times: got count={}:\n{}",
        count,
        result.stdout
    );
}

// ---------------------------------------------------------------------------
// htmlsnapshot
// ---------------------------------------------------------------------------

/// `htmlsnapshot` (bare) sends `html_snapshot_capture`.
pub(super) fn test_htmlsnapshot_capture(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot to succeed:\n{}",
        result.stderr
    );
    // The bare `htmlsnapshot` command should output snapshot metadata.
    assert!(
        result.stdout.contains("Mock Page"),
        "expected snapshot metadata in output:\n{}",
        result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let capture_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_capture")
        .expect("expected html_snapshot_capture tool call");
    assert!(
        capture_call.arguments.get("sessionId").is_some(),
        "expected sessionId in html_snapshot_capture arguments, got: {:?}",
        capture_call.arguments
    );
}

/// `htmlsnapshot capture` (explicit form) also sends `html_snapshot_capture`.
pub(super) fn test_htmlsnapshot_capture_explicit(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "capture"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot capture to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let capture_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_capture")
        .expect("expected html_snapshot_capture tool call from htmlsnapshot capture command");
    assert!(
        capture_call.arguments.get("sessionId").is_some(),
        "expected sessionId in html_snapshot_capture arguments, got: {:?}",
        capture_call.arguments
    );
}

/// `htmlsnapshot get text h2` sends `html_snapshot_scrape` with field and selector.
pub(super) fn test_htmlsnapshot_get_text(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "get", "text", "h2"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot get text h2 to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let scrape_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_scrape")
        .expect("expected html_snapshot_scrape tool call");
    assert_eq!(
        scrape_call.arguments.get("field").and_then(|v| v.as_str()),
        Some("text"),
        "expected field=text in html_snapshot_scrape arguments, got: {:?}",
        scrape_call.arguments
    );
    assert_eq!(
        scrape_call
            .arguments
            .get("selector")
            .and_then(|v| v.as_str()),
        Some("h2"),
        "expected selector=h2 in html_snapshot_scrape arguments, got: {:?}",
        scrape_call.arguments
    );
}

/// `htmlsnapshot get text` without a selector defaults to `:root`.
pub(super) fn test_htmlsnapshot_get_text_default_selector(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "get", "text"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot get text (no selector) to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let scrape_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_scrape")
        .expect("expected html_snapshot_scrape tool call");
    assert_eq!(
        scrape_call.arguments.get("field").and_then(|v| v.as_str()),
        Some("text"),
        "expected field=text, got: {:?}",
        scrape_call.arguments
    );
    // When no selector is provided, it defaults to ":root"
    assert_eq!(
        scrape_call
            .arguments
            .get("selector")
            .and_then(|v| v.as_str()),
        Some(":root"),
        "expected selector to default to :root, got: {:?}",
        scrape_call.arguments
    );
}

/// `htmlsnapshot get attr h1 class` sends `html_snapshot_scrape` with field=attr and attrName.
pub(super) fn test_htmlsnapshot_get_attr(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "get", "attr", "h1", "class"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot get attr h1 class to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let scrape_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_scrape")
        .expect("expected html_snapshot_scrape tool call");
    assert_eq!(
        scrape_call.arguments.get("field").and_then(|v| v.as_str()),
        Some("attr"),
        "expected field=attr, got: {:?}",
        scrape_call.arguments
    );
    assert_eq!(
        scrape_call
            .arguments
            .get("attrName")
            .and_then(|v| v.as_str()),
        Some("class"),
        "expected attrName=class, got: {:?}",
        scrape_call.arguments
    );
}

/// `htmlsnapshot get all text .product` sends `html_snapshot_scrape_all`.
pub(super) fn test_htmlsnapshot_get_all(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "get", "all", "text", ".product"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot get all to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let scrape_all_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_scrape_all")
        .expect("expected html_snapshot_scrape_all tool call");
    assert_eq!(
        scrape_all_call
            .arguments
            .get("field")
            .and_then(|v| v.as_str()),
        Some("text"),
        "expected field=text, got: {:?}",
        scrape_all_call.arguments
    );
    assert_eq!(
        scrape_all_call
            .arguments
            .get("selector")
            .and_then(|v| v.as_str()),
        Some(".product"),
        "expected selector=.product, got: {:?}",
        scrape_all_call.arguments
    );
}

/// `htmlsnapshot get all text .product --offset 2 --limit 5` passes them through.
pub(super) fn test_htmlsnapshot_get_all_offset_limit(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(
        ctx,
        &[
            "htmlsnapshot",
            "get",
            "all",
            "text",
            ".product",
            "--offset",
            "2",
            "--limit",
            "5",
        ],
    );
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot get all with offset/limit to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let scrape_all_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_scrape_all")
        .expect("expected html_snapshot_scrape_all tool call");
    assert_eq!(
        scrape_all_call
            .arguments
            .get("offset")
            .and_then(|v| v.as_i64()),
        Some(2),
        "expected offset=2, got: {:?}",
        scrape_all_call.arguments
    );
    assert_eq!(
        scrape_all_call
            .arguments
            .get("limit")
            .and_then(|v| v.as_i64()),
        Some(5),
        "expected limit=5, got: {:?}",
        scrape_all_call.arguments
    );
}

/// `htmlsnapshot query --sql <query>` sends `html_snapshot_query`.
pub(super) fn test_htmlsnapshot_query(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(
        ctx,
        &["htmlsnapshot", "query", "--sql", "SELECT h1 FROM page"],
    );
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot query to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let query_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_query")
        .expect("expected html_snapshot_query tool call");
    assert_eq!(
        query_call.arguments.get("sql").and_then(|v| v.as_str()),
        Some("SELECT h1 FROM page"),
        "expected sql='SELECT h1 FROM page', got: {:?}",
        query_call.arguments
    );
}

/// `htmlsnapshot export` sends `html_snapshot_export`.
pub(super) fn test_htmlsnapshot_export(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "export"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot export to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let export_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_export")
        .expect("expected html_snapshot_export tool call");
    assert!(
        export_call.arguments.get("sessionId").is_some(),
        "expected sessionId in html_snapshot_export arguments, got: {:?}",
        export_call.arguments
    );
}

/// `htmlsnapshot summary` sends `html_snapshot_summary`.
pub(super) fn test_htmlsnapshot_summary(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "summary"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot summary to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let summary_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_summary")
        .expect("expected html_snapshot_summary tool call");
    assert!(
        summary_call.arguments.get("sessionId").is_some(),
        "expected sessionId in html_snapshot_summary arguments, got: {:?}",
        summary_call.arguments
    );
}

/// `htmlsnapshot inspect` sends `html_snapshot_inspect`.
pub(super) fn test_htmlsnapshot_inspect(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "inspect"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot inspect to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let inspect_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_inspect")
        .expect("expected html_snapshot_inspect tool call");
    assert!(
        inspect_call.arguments.get("sessionId").is_some(),
        "expected sessionId in html_snapshot_inspect arguments, got: {:?}",
        inspect_call.arguments
    );
}

/// `htmlsnapshot inspect .product --max 5 --depth 3` passes all arguments.
pub(super) fn test_htmlsnapshot_inspect_with_options(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(
        ctx,
        &[
            "htmlsnapshot",
            "inspect",
            ".product",
            "--max",
            "5",
            "--depth",
            "3",
        ],
    );
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot inspect with options to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let inspect_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_inspect")
        .expect("expected html_snapshot_inspect tool call");
    assert_eq!(
        inspect_call
            .arguments
            .get("selector")
            .and_then(|v| v.as_str()),
        Some(".product"),
        "expected selector=.product, got: {:?}",
        inspect_call.arguments
    );
    assert_eq!(
        inspect_call.arguments.get("max").and_then(|v| v.as_i64()),
        Some(5),
        "expected max=5, got: {:?}",
        inspect_call.arguments
    );
    assert_eq!(
        inspect_call.arguments.get("depth").and_then(|v| v.as_i64()),
        Some(3),
        "expected depth=3, got: {:?}",
        inspect_call.arguments
    );
}

/// `htmlsnapshot readability` sends `html_snapshot_readability` and renders metadata.
pub(super) fn test_htmlsnapshot_readability(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(ctx, &["htmlsnapshot", "readability"]);
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot readability to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let readability_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_readability")
        .expect("expected html_snapshot_readability tool call");
    assert!(
        readability_call.arguments.get("sessionId").is_some(),
        "expected sessionId in html_snapshot_readability arguments, got: {:?}",
        readability_call.arguments
    );
}

/// `htmlsnapshot readability <url>` passes the url through to the tool.
pub(super) fn test_htmlsnapshot_readability_with_url(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(
        ctx,
        &["htmlsnapshot", "readability", "https://example.com/article"],
    );
    assert_eq!(
        result.exit_code, 0,
        "expected htmlsnapshot readability with url to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let readability_call = tool_calls
        .iter()
        .find(|call| call.tool == "html_snapshot_readability")
        .expect("expected html_snapshot_readability tool call");
    assert_eq!(
        readability_call
            .arguments
            .get("url")
            .and_then(|v| v.as_str()),
        Some("https://example.com/article"),
        "expected url=https://example.com/article, got: {:?}",
        readability_call.arguments
    );
}

/// `plugin-markdown read --url <url>` routes through the dynamic plugin
/// command system and calls the `markdown_read` MCP tool.
pub(super) fn test_plugin_markdown_read(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    let result = run_command(
        ctx,
        &["plugin-markdown", "read", "--url", "https://example.com/article"],
    );
    assert_eq!(
        result.exit_code, 0,
        "expected plugin-markdown read to succeed:\n{}",
        result.stderr
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let read_call = tool_calls
        .iter()
        .find(|call| call.tool == "markdown_read")
        .expect("expected markdown_read tool call from plugin-markdown read");
    assert_eq!(
        read_call.arguments.get("url").and_then(|v| v.as_str()),
        Some("https://example.com/article"),
        "expected url=https://example.com/article, got: {:?}",
        read_call.arguments
    );
    assert!(
        read_call.arguments.get("sessionId").is_some(),
        "expected sessionId in markdown_read arguments, got: {:?}",
        read_call.arguments
    );
}

/// When the server returns an error for html_snapshot_capture, the CLI propagates it.
pub(super) fn test_htmlsnapshot_error_propagation(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    mock_server.queue_tool_failure(
        "html_snapshot_capture",
        None, // matches any session
        None, // matches any url
        "simulated backend failure for html_snapshot_capture",
    );

    let result = run_command_expecting_failure(ctx, &["htmlsnapshot"], "simulated backend failure");
    assert_ne!(
        result.exit_code, 0,
        "expected htmlsnapshot to fail when backend returns error"
    );
}

// ---------------------------------------------------------------------------
// upload -- backend error propagation
// ---------------------------------------------------------------------------

/// Upload with backend failure should propagate the error to the user.
pub(super) fn test_upload_error_backend_failure(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, "https://example.com"]);

    mock_server.queue_tool_failure(
        "browser_file_upload",
        None,
        None,
        "simulated upload failure",
    );

    // Create a temporary file for the upload command
    let tmp_path = ctx.workspace_dir.join("_e2e_upload_test.txt");
    std::fs::write(&tmp_path, "test content").expect("write temp file");

    let result = run_command_expecting_failure(
        ctx,
        &["upload", &tmp_path.to_string_lossy(), "#file-input"],
        "simulated upload failure",
    );
    assert_ne!(
        result.exit_code, 0,
        "expected upload to fail when backend returns error:\n{}",
        result.stderr
    );

    let _ = std::fs::remove_file(&tmp_path);
}

// ---------------------------------------------------------------------------
// Chat commands — synchronous and async (chat / chat-result)
// ---------------------------------------------------------------------------

pub(super) fn test_chat_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // ---- synchronous chat ----
    let chat_result = run_command(ctx, &["chat", "Hello, how are you?"]);
    assert!(
        chat_result
            .stdout
            .contains("Mock chat response for your prompt."),
        "Expected mock chat response in:\n{}",
        chat_result.stdout
    );

    // ---- async chat ----
    let async_result = run_command(ctx, &["chat", "--async", "Tell me a joke"]);
    assert!(
        async_result.stdout.contains("Chat task submitted:"),
        "Expected async task submission output in:\n{}",
        async_result.stdout
    );
    assert!(
        async_result.stdout.contains("chat-task-"),
        "Expected chat task ID in:\n{}",
        async_result.stdout
    );
    assert!(
        async_result.stdout.contains("chat result chat-task-"),
        "Expected chat result hint in:\n{}",
        async_result.stdout
    );

    // Extract task ID from output
    let task_id: String = async_result
        .stdout
        .lines()
        .find_map(|line| {
            if line.contains("chat-task-") {
                line.split("chat-task-").nth(1).map(|s| {
                    format!(
                        "chat-task-{}",
                        s.trim().trim_matches(|c: char| !c.is_ascii_digit())
                    )
                })
            } else {
                None
            }
        })
        .expect("Failed to extract chat task ID");

    // ---- chat result ----
    let result = run_command(ctx, &["chat", "result", &task_id]);
    assert_eq!(
        strip_snapshot_output(&result.stdout),
        format!("Mock chat result for task {task_id}.")
    );

    // Verify mock server recorded the right calls
    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec![
            "Hello, how are you?".to_string(),
            "Tell me a joke".to_string(),
        ]
    );
    assert!(
        snapshot.result_queries.contains(&task_id),
        "Expected chat-result to query task {task_id}, got {:?}",
        snapshot.result_queries
    );
}

// ---------------------------------------------------------------------------
// Wait command
// ---------------------------------------------------------------------------

pub(super) fn test_e2e_wait_selector(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let result = run_command(ctx, &["wait", "#output"]);
    assert_eq!(result.exit_code, 0, "expected wait selector to succeed");

    let tool_calls = mock_server.snapshot().tool_calls;
    let wait_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "wait_for_selector")
        .collect();
    assert_eq!(wait_calls.len(), 1, "expected one wait_for_selector call");
    assert_eq!(wait_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(wait_calls[0].arguments["selector"], "#output");
    assert_eq!(wait_calls[0].arguments["timeoutMillis"], 30000);
}

pub(super) fn test_e2e_wait_millis(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let result = run_command(ctx, &["wait", "2000"]);
    assert_eq!(result.exit_code, 0, "expected wait millis to succeed");

    let tool_calls = mock_server.snapshot().tool_calls;
    let delay_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "delay")
        .collect();
    assert_eq!(delay_calls.len(), 1, "expected one delay call");
    assert_eq!(delay_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(delay_calls[0].arguments["millis"], 2000);
}

pub(super) fn test_e2e_wait_text(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let result = run_command(ctx, &["wait", "--text", "Success"]);
    assert_eq!(result.exit_code, 0, "expected wait text to succeed");

    let tool_calls = mock_server.snapshot().tool_calls;
    let wait_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "wait_for_function")
        .collect();
    assert_eq!(wait_calls.len(), 1, "expected one wait_for_function call");
    assert_eq!(wait_calls[0].arguments["sessionId"], "swarm-session-1");
    let func = wait_calls[0].arguments["pageFunction"].as_str().unwrap();
    assert!(
        func.contains("document.body.innerText.includes"),
        "expected innerText check"
    );
    assert!(
        func.contains("Success"),
        "expected the target text in the expression"
    );
    assert_eq!(wait_calls[0].arguments["timeoutMillis"], 30000);
}

pub(super) fn test_e2e_wait_url(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let result = run_command(ctx, &["wait", "--url", "**/dashboard"]);
    assert_eq!(result.exit_code, 0, "expected wait url to succeed");

    let tool_calls = mock_server.snapshot().tool_calls;
    let wait_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "wait_for_page")
        .collect();
    assert_eq!(wait_calls.len(), 1, "expected one wait_for_page call");
    assert_eq!(wait_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(wait_calls[0].arguments["url"], "**/dashboard");
    assert_eq!(wait_calls[0].arguments["timeoutMillis"], 30000);
}

pub(super) fn test_e2e_wait_load(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let result = run_command(ctx, &["wait", "--load", "domcontentloaded"]);
    assert_eq!(result.exit_code, 0, "expected wait load to succeed");

    let tool_calls = mock_server.snapshot().tool_calls;
    let wait_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "wait_for_function")
        .collect();
    assert_eq!(wait_calls.len(), 1, "expected one wait_for_function call");
    assert_eq!(wait_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(
        wait_calls[0].arguments["pageFunction"],
        "document.readyState !== 'loading'"
    );
    assert_eq!(wait_calls[0].arguments["timeoutMillis"], 30000);
}

pub(super) fn test_e2e_wait_fn(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let result = run_command(ctx, &["wait", "--fn", "window.app.ready === true"]);
    assert_eq!(result.exit_code, 0, "expected wait fn to succeed");

    let tool_calls = mock_server.snapshot().tool_calls;
    let wait_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "wait_for_function")
        .collect();
    assert_eq!(wait_calls.len(), 1, "expected one wait_for_function call");
    assert_eq!(wait_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(
        wait_calls[0].arguments["pageFunction"],
        "window.app.ready === true"
    );
    assert_eq!(wait_calls[0].arguments["timeoutMillis"], 30000);
}

// NOTE: Custom timeout (`--timeout <ms>`) for the wait command cannot be
// tested via E2E because `--timeout` is always consumed as a global CLI flag
// (see args.rs parse_global_flags). The unit tests in commands.rs cover the
// custom-timeout logic via the tool_params_fn directly.

// ---------------------------------------------------------------------------
// agent-cancel (POST /api/commands/{id}/cancel)
// ---------------------------------------------------------------------------

pub(super) fn test_agent_cancel_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Submit an async agent task, then cancel it by id.
    let submitted = run_command(ctx, &["agent", "run", "collect the latest updates"]);
    let task_id = extract_submitted_task_id(&submitted.stdout);

    let cancelled = run_command(ctx, &["agent", "cancel", &task_id]);
    assert!(
        cancelled.stdout.contains("cancelled"),
        "Expected a cancellation confirmation in:\n{}",
        cancelled.stdout
    );

    // The mock server must have received POST /api/commands/{id}/cancel.
    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.agent_cancel_calls,
        vec![task_id.clone()],
        "Expected the cancel call to be recorded for {task_id}"
    );

    // Missing id is a hard CLI error before any HTTP request.
    run_command_expecting_failure(ctx, &["agent", "cancel"], "Missing required argument");
}

// ---------------------------------------------------------------------------
// config family (local config.json + server-side /api/config REST)
// ---------------------------------------------------------------------------

pub(super) fn test_config_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // ── Local keys: set → get → list → delete, all against the isolated
    // state dir config.json (no server round trip). ──
    let set = run_command(ctx, &["config", "set", "timeout", "42"]);
    assert!(
        set.stdout.contains("Set 'timeout' = '42'"),
        "Expected config set confirmation in:\n{}",
        set.stdout
    );

    let get = run_command(ctx, &["config", "get", "timeout"]);
    assert!(
        get.stdout.contains("42"),
        "Expected config get to print the value in:\n{}",
        get.stdout
    );

    let list = run_command(ctx, &["config", "list"]);
    assert!(
        list.stdout.contains("Config file:")
            && list.stdout.contains("timeout") && list.stdout.contains("42"),
        "Expected config list to show the key in:\n{}",
        list.stdout
    );

    let del = run_command(ctx, &["config", "delete", "timeout"]);
    assert!(
        del.stdout.contains("Deleted 'timeout'"),
        "Expected config delete confirmation in:\n{}",
        del.stdout
    );

    // Unknown keys are rejected with the valid-keys hint.
    run_command_expecting_failure(ctx, &["config", "set", "bogus-key", "1"], "Unknown config key");

    // ── Server-side keys route to the unified /api/config/{key} REST API. ──
    let server_get = run_command(ctx, &["config", "get", "agent.llm.maxRequestTokens"]);
    assert!(
        server_get
            .stdout
            .contains("Server config 'agent.llm.maxRequestTokens'"),
        "Expected server config header in:\n{}",
        server_get.stdout
    );

    let server_set = run_command(
        ctx,
        &["config", "set", "agent.llm.maxRequestTokens", "16000"],
    );
    assert!(
        server_set.stdout.contains("16000"),
        "Expected the runtime override in:\n{}",
        server_set.stdout
    );

    let server_delete = run_command(ctx, &["config", "delete", "agent.llm.maxRequestTokens"]);
    assert!(
        server_delete
            .stdout
            .contains("Server config 'agent.llm.maxRequestTokens'"),
        "Expected server config reset output in:\n{}",
        server_delete.stdout
    );

    // Verify the REST traffic: GET, PUT (with ?value=), DELETE.
    let calls = mock_server.snapshot().config_calls;
    assert!(
        calls.iter().any(|(method, key, _)| method == "GET" && key == "agent.llm.maxRequestTokens"),
        "Expected a GET /api/config/agent.llm.maxRequestTokens call, got: {calls:?}"
    );
    assert!(
        calls.iter().any(|(method, key, value)| method == "PUT"
            && key == "agent.llm.maxRequestTokens"
            && value == "16000"),
        "Expected PUT /api/config/...?value=16000 call, got: {calls:?}"
    );
    assert!(
        calls.iter().any(|(method, key, _)| method == "DELETE" && key == "agent.llm.maxRequestTokens"),
        "Expected a DELETE /api/config/agent.llm.maxRequestTokens call, got: {calls:?}"
    );
}

// ---------------------------------------------------------------------------
// diff-snapshot (filesystem-only)
// ---------------------------------------------------------------------------

pub(super) fn test_snapshot_diff_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // diff-snapshot is a pure local-file command; craft two accessibility
    // tree snapshots in the CLI's snapshot directory (the CLI runs with
    // workspace_dir as its working directory).
    let snap_dir = ctx.workspace_dir.join(".browser4-cli").join("snapshot");
    std::fs::create_dir_all(&snap_dir).expect("create snapshot dir");
    let before = snap_dir.join("before.yml");
    let after = snap_dir.join("after.yml");
    std::fs::write(
        &before,
        "- button \"Search\" [box=10,20,100,30] [ref=e1]:\n\
         - textbox \"Query\" [box=10,60,100,30] [ref=e2]:\n\
         \x20\x20- /value: \"hello\"\n\
         - link \"Old\" [box=10,100,100,30] [ref=e4]:\n",
    )
    .expect("write before snapshot");
    std::fs::write(
        &after,
        "- button \"Search\" [box=10,20,100,30] [ref=e1]:\n\
         - textbox \"Query\" [box=10,60,100,30] [ref=e2]:\n\
         \x20\x20- /value: \"hello2\"\n\
         - link \"New\" [box=10,140,100,30] [ref=e3]:\n",
    )
    .expect("write after snapshot");

    let diff = run_command(
        ctx,
        &[
            "diff",
            "snapshot",
            before.to_str().expect("before path"),
            after.to_str().expect("after path"),
        ],
    );
    assert!(
        diff.stdout.contains("Snapshot Diff"),
        "Expected a diff header in:\n{}",
        diff.stdout
    );
    assert!(
        diff.stdout.contains("1 added, 1 removed, 1 modified"),
        "Expected the change summary in:\n{}",
        diff.stdout
    );
    assert!(
        diff.stdout.contains("/value: \"hello\" → \"hello2\""),
        "Expected the value change in:\n{}",
        diff.stdout
    );
    assert!(
        diff.stdout.contains("- link e4 \"Old\"") && diff.stdout.contains("+ link e3 \"New\""),
        "Expected removed/added link entries in:\n{}",
        diff.stdout
    );

    // Missing files produce a readable error instead of a panic.
    let missing = snap_dir.join("missing.yml");
    let bad = run_command_allowing_failure(
        ctx,
        &["diff", "snapshot", missing.to_str().expect("missing path"), "nope.yml"],
    );
    assert!(
        bad.stdout.contains("Cannot read") || bad.exit_code != 0,
        "Expected a readable error for missing files, got:\n{}",
        bad.stdout
    );
}

// ---------------------------------------------------------------------------
// profiles-list (filesystem-only, honours HOME/USERPROFILE)
// ---------------------------------------------------------------------------

pub(super) fn test_profiles_list_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Redirect HOME/USERPROFILE so the CLI scans an isolated fake home, then
    // restore the original values so later scenarios are unaffected.
    let fake_home = ctx.workspace_dir.join("fake-home");
    let chrome_base = fake_home.join(".browser4").join("browser").join("chrome");
    std::fs::create_dir_all(chrome_base.join("prototype").join("google-chrome"))
        .expect("create prototype profile");
    std::fs::create_dir_all(chrome_base.join("default")).expect("create default profile");
    let orig_home = std::env::var("HOME").ok();
    let orig_userprofile = std::env::var("USERPROFILE").ok();
    let fake_home_str = fake_home.to_string_lossy().into_owned();
    ctx.set_env("HOME", &fake_home_str);
    ctx.set_env("USERPROFILE", &fake_home_str);

    let list = run_command(ctx, &["profiles", "list"]);
    assert!(
        list.stdout.contains("prototype"),
        "Expected the prototype profile in:\n{}",
        list.stdout
    );
    assert!(
        list.stdout.contains("default"),
        "Expected the default profile in:\n{}",
        list.stdout
    );

    // Restore the inherited environment for later scenarios.
    match orig_home {
        Some(home) => ctx.set_env("HOME", &home),
        None => ctx.unset_env("HOME"),
    }
    match orig_userprofile {
        Some(up) => ctx.set_env("USERPROFILE", &up),
        None => ctx.unset_env("USERPROFILE"),
    }
}

// ---------------------------------------------------------------------------
// code-* family (session-independent coding tools → coding_* MCP tools)
// ---------------------------------------------------------------------------

pub(super) fn test_code_command_family(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // No session is opened: code commands are session-independent and go
    // straight to the backend's coding tools.
    let file = "sample.txt";
    let other = "other.txt";

    // Content-bearing commands: positional content, plus the --stdin path.
    run_command(ctx, &["code", "write", file, "hello world"]);
    let write_stdin = run_command_with_stdin(
        ctx,
        &["code", "write", file, "--stdin"],
        "content from stdin",
    );
    assert_eq!(write_stdin.exit_code, 0, "code write --stdin should succeed");

    run_command(ctx, &["code", "append", file, "more"]);
    run_command(ctx, &["code", "replace", file, "hello", "bye"]);
    run_command(ctx, &["code", "read", file]);
    run_command(ctx, &["code", "delete", other]);
    run_command(ctx, &["code", "copy", file, other]);
    run_command(ctx, &["code", "move", other, "moved.txt"]);
    run_command(ctx, &["code", "list", "."]);
    run_command(ctx, &["code", "stat", file]);
    run_command(ctx, &["code", "glob", "**/*.kt"]);
    run_command(ctx, &["code", "grep", "TODO", "."]);
    run_command(ctx, &["code", "mkdir", "new-dir"]);
    run_command(ctx, &["code", "diff", file]);
    run_command(ctx, &["code", "changes"]);
    run_command(ctx, &["code", "shell", "echo hi"]);
    run_command(ctx, &["code", "scaffold", "js"]);
    run_command(ctx, &["code", "validate", "js"]);
    run_command(ctx, &["code", "mvn", "browser4-rest"]);
    run_command(ctx, &["code", "run", "bash", "echo hi"]);
    run_command(ctx, &["code", "devtask", "test dev task"]);
    run_command(ctx, &["code", "impact", file]);
    run_command(ctx, &["code", "workspace"]);
    run_command(ctx, &["code", "javap", "java.lang.String"]);

    // Every expected coding_* tool must have been called exactly once (the
    // two code-write invocations count separately).
    let tool_calls = mock_server.snapshot().tool_calls;
    let mut names: Vec<&str> = tool_calls.iter().map(|call| call.tool.as_str()).collect();
    names.sort_unstable();
    for tool in [
        "coding_write",
        "coding_append",
        "coding_replace",
        "coding_read",
        "coding_delete",
        "coding_copy",
        "coding_move",
        "coding_listDir",
        "coding_stat",
        "coding_glob",
        "coding_grep",
        "coding_mkdir",
        "coding_diff",
        "coding_changeSummary",
        "coding_shell",
        "coding_scaffold",
        "coding_validate",
        "coding_mvnBuild",
        "coding_runCode",
        "coding_devTask",
        "coding_impact",
        "coding_workspaceRoot",
        "coding_classInfo",
    ] {
        let count = names.iter().filter(|n| **n == tool).count();
        assert!(
            count >= 1,
            "expected at least one {tool} call, got: {names:?}"
        );
    }

    // --stdin content must reach the backend as the `content` argument, and
    // the CLI-only flag must not leak into the tool call.
    let write_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "coding_write")
        .collect();
    assert_eq!(write_calls.len(), 2, "expected two coding_write calls");
    let stdin_call = write_calls
        .iter()
        .find(|call| call.arguments.get("content").and_then(|v| v.as_str()) == Some("content from stdin"))
        .unwrap_or_else(|| panic!("expected stdin content in coding_write args: {:?}", write_calls));
    assert!(
        stdin_call.arguments.get("stdin").is_none(),
        "CLI-only --stdin flag must not be forwarded, got: {:?}",
        stdin_call.arguments
    );

    // camelCase mapping: code-diff sends the path as `path`.
    let diff_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "coding_diff")
        .collect();
    assert_eq!(diff_calls.len(), 1, "expected one coding_diff call");
    assert_eq!(diff_calls[0].arguments["path"], "sample.txt");
}

// ---------------------------------------------------------------------------
// vitals / web-vitals (dispatch + shared VITALS_JS evaluation)
// ---------------------------------------------------------------------------

pub(super) fn test_vitals_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let vitals = run_command(ctx, &["vitals"]);
    assert_eq!(
        strip_snapshot_output(&vitals.stdout),
        "mock evaluation result",
        "vitals should evaluate VITALS_JS through browser_evaluate"
    );

    let web_vitals = run_command(ctx, &["web-vitals"]);
    assert_eq!(
        strip_snapshot_output(&web_vitals.stdout),
        "mock evaluation result",
        "web-vitals (alias) should behave like vitals"
    );

    // Both commands must issue the same browser_evaluate call: the VITALS
    // injection script with awaitPromise enabled.
    let snapshot = mock_server.snapshot();
    let evaluate_calls: Vec<_> = snapshot
        .tool_calls
        .iter()
        .filter(|call| call.tool == "browser_evaluate")
        .collect();
    assert_eq!(
        evaluate_calls.len(),
        2,
        "expected two browser_evaluate calls (vitals + web-vitals), got {evaluate_calls:?}"
    );
    for call in &evaluate_calls {
        assert_eq!(
            call.arguments.get("awaitPromise").and_then(|v| v.as_bool()),
            Some(true),
            "expected awaitPromise=true, got: {:?}",
            call.arguments
        );
        let expression = call
            .arguments
            .get("expression")
            .and_then(|v| v.as_str())
            .unwrap_or_default();
        assert!(
            expression.contains("web-vitals"),
            "expected the web-vitals injection script, got a {} char expression",
            expression.len()
        );
    }
}

// ---------------------------------------------------------------------------
// doctor-status (aggregated /api/system/status panel)
// ---------------------------------------------------------------------------

pub(super) fn test_doctor_status_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // Default view renders the summary layers of the canned report.
    let status = run_command(ctx, &["doctor", "status"]);
    assert!(
        status.stdout.contains("Status Panel Report"),
        "Expected the status panel header in:\n{}",
        status.stdout
    );
    assert!(
        status.stdout.contains("UP"),
        "Expected the health layer in:\n{}",
        status.stdout
    );

    // --section drills into one report layer.
    let section = run_command(ctx, &["doctor", "status", "--section", "build"]);
    assert!(
        section.stdout.contains("4.14.0-mock"),
        "Expected the build section content in:\n{}",
        section.stdout
    );

    // Unknown sections are reported with the available list (the command
    // itself still exits 0 — it degrades gracefully).
    let unknown = run_command(ctx, &["doctor", "status", "--section", "bogus"]);
    assert!(
        unknown.stdout.contains("Unknown section"),
        "Expected the unknown-section hint in:\n{}",
        unknown.stdout
    );

    // --json embeds the raw report document.
    let json_out = run_command(ctx, &["doctor", "status", "--json"]);
    assert!(
        json_out.stdout.contains("status_report")
            && json_out.stdout.contains("mock-plugin"),
        "Expected the raw report under status_report in:\n{}",
        json_out.stdout
    );
}

// ---------------------------------------------------------------------------
// Frame commands (frames / frame) — CLI↔backend contract
// ---------------------------------------------------------------------------

/// `frames` / `frame` / `frame main` must map to the frame_list /
/// frame_switch / frame_main backend tools with the right arguments, render
/// the backend response, and surface backend "Frame not found" errors.
pub(super) fn test_e2e_frame_commands_contract(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    // ── frames → frame_list (no extra arguments) and prints the response ──
    let frames_result = run_command(ctx, &["frames"]);
    assert_eq!(frames_result.exit_code, 0, "expected frames to succeed");
    assert!(
        frames_result
            .stdout
            .contains("mock response for frame_list"),
        "Expected the frame_list mock response in:\n{}",
        frames_result.stdout
    );

    // ── frame <selector> → frame_switch with the frame argument ──
    let switch_result = run_command(ctx, &["frame", "#pay-frame"]);
    assert_eq!(switch_result.exit_code, 0, "expected frame switch to succeed");

    // ── frame main → frame_main (no extra arguments) ──
    let main_result = run_command(ctx, &["frame", "main"]);
    assert_eq!(main_result.exit_code, 0, "expected frame main to succeed");

    let tool_calls = mock_server.snapshot().tool_calls;
    let list_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "frame_list")
        .collect();
    assert_eq!(list_calls.len(), 1, "expected one frame_list call");
    assert_eq!(list_calls[0].arguments["sessionId"], "swarm-session-1");

    let switch_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "frame_switch")
        .collect();
    assert_eq!(switch_calls.len(), 1, "expected one frame_switch call");
    assert_eq!(switch_calls[0].arguments["sessionId"], "swarm-session-1");
    assert_eq!(switch_calls[0].arguments["frame"], "#pay-frame");

    let main_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "frame_main")
        .collect();
    assert_eq!(main_calls.len(), 1, "expected one frame_main call");
    assert_eq!(main_calls[0].arguments["sessionId"], "swarm-session-1");

    // ── backend error surfaces to the CLI with a non-zero exit ──
    mock_server.queue_tool_failure(
        "frame_switch",
        Some("swarm-session-1"),
        None,
        "Frame not found: #no-such-frame",
    );
    run_command_expecting_failure(ctx, &["frame", "#no-such-frame"], "Frame not found");
}
