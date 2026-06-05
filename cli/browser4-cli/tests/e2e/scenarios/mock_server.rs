use crate::*;

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
    assert_eq!(result.exit_code, 0, "expected close to succeed despite backend error");

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
    fs::write(state_file_path(&ctx.state_dir, None), default_state.to_string())
        .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(&auth_path, serde_json::json!({
        "sessionId": "swarm-session-auth",
        "baseUrl": mock_server.base_url(),
    }).to_string()).expect("write auth state");

    let scraper_path = state_file_path(&ctx.state_dir, Some("scraper"));
    fs::write(&scraper_path, serde_json::json!({
        "sessionId": "swarm-session-scraper",
        "baseUrl": mock_server.base_url(),
    }).to_string()).expect("write scraper state");

    let result = run_command(ctx, &["close-all"]);
    assert_eq!(result.exit_code, 0, "expected close-all to succeed");
    assert!(
        result.stdout.contains("All sessions closed."),
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
    assert!(
        !auth_path.exists(),
        "Expected auth state to be removed"
    );
    assert!(
        !scraper_path.exists(),
        "Expected scraper state to be removed"
    );
}

pub(super) fn test_close_all_no_active_sessions(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // No state files at all.
    let result = run_command(ctx, &["close-all"]);
    assert_eq!(result.exit_code, 0, "expected close-all to succeed with no sessions");

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
    fs::write(state_file_path(&ctx.state_dir, None), default_state.to_string())
        .expect("write state fixture");

    // Shut down the mock server before running the command.
    mock_server.shutdown();

    let result = run_command_allowing_failure(ctx, &["close-all"]);
    // close-all never fails fatally — it treats errors as warnings.
    assert_eq!(result.exit_code, 0, "expected close-all to exit 0 even when server unreachable");

    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains("close-all warnings:") || combined.contains("No reachable Browser4 servers responded"),
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
    fs::write(state_file_path(&ctx.state_dir, None), default_state.to_string())
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

    // The table should show the session as Active.
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

    // Verify list_sessions was called.
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot.tool_calls.iter().any(|c| c.tool == "list_sessions"),
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
    assert_eq!(result.exit_code, 0, "expected list to succeed when backend unreachable");

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
        result.stdout.contains("Name") && result.stdout.contains("Session ID") && result.stdout.contains("Status"),
        "Expected table header in list output:\n{}",
        result.stdout
    );

    // Verify list_sessions was still called.
    let snapshot = mock_server.snapshot();
    assert!(
        snapshot.tool_calls.iter().any(|c| c.tool == "list_sessions"),
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
    fs::write(state_file_path(&ctx.state_dir, None), default_state.to_string())
        .expect("write default state");

    // Write two named sessions.
    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(&auth_path, serde_json::json!({
        "sessionId": "swarm-session-auth",
        "baseUrl": mock_server.base_url(),
    }).to_string()).expect("write auth state");

    let scraper_path = state_file_path(&ctx.state_dir, Some("scraper"));
    fs::write(&scraper_path, serde_json::json!({
        "sessionId": "swarm-session-scraper",
        "baseUrl": mock_server.base_url(),
    }).to_string()).expect("write scraper state");

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
    assert!(output.contains("scraper"), "Expected 'scraper' row:\n{output}");
    assert!(output.contains("(default)"), "Expected '(default)' row:\n{output}");

    // Verify statuses: default and auth are active, scraper is stale.
    assert!(
        output.contains("Active"),
        "Expected 'Active' for default and auth:\n{output}"
    );
    assert!(
        output.contains("Stale"),
        "Expected 'Stale' for scraper:\n{output}"
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
    assert_eq!(result.exit_code, 0, "expected status to succeed even when unreachable");

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

    // Write a runtime install metadata file.
    let metadata_path = ctx.state_dir.join("lib").join("browser4-installation.json");
    fs::create_dir_all(metadata_path.parent().unwrap()).expect("create lib dir");
    let metadata = serde_json::json!({
        "tag": "v4.10.0",
        "asset_name": "browser4-runtime-v4.10.0.zip",
        "download_url": "https://example.com/releases/v4.10.0/browser4-runtime.zip",
        "installed_at": "2026-05-15T10:00:00Z"
    });
    fs::write(&metadata_path, metadata.to_string()).expect("write install metadata");

    let result = run_command(ctx, &["status"]);
    assert_eq!(result.exit_code, 0);

    assert!(
        result.stdout.contains("Installed version: v4.10.0"),
        "Expected 'Installed version: v4.10.0' in:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Installed at:"),
        "Expected 'Installed at:' in:\n{}",
        result.stdout
    );
}

pub(super) fn test_status_no_installed_runtime(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    // No install metadata file exists.
    let result = run_command(ctx, &["status"]);
    assert_eq!(result.exit_code, 0);

    assert!(
        result.stdout.contains("Installed version: not installed"),
        "Expected 'Installed version: not installed' in:\n{}",
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
    fs::write(state_file_path(&ctx.state_dir, None), default_state.to_string())
        .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(&auth_path, serde_json::json!({
        "sessionId": "swarm-session-auth",
        "baseUrl": mock_server.base_url(),
    }).to_string()).expect("write auth state");

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
    assert_eq!(result.exit_code, 0, "expected kill-all to succeed when no processes");

    // kill-all with no tracked processes should report that nothing was found
    // and succeed without error.
    assert!(
        result.stdout.contains("No tracked Browser4 processes found")
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
    fs::write(state_file_path(&ctx.state_dir, None), default_state.to_string())
        .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(&auth_path, serde_json::json!({
        "sessionId": "swarm-session-auth",
        "baseUrl": mock_server.base_url(),
    }).to_string()).expect("write auth state");

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
    fs::write(state_file_path(&ctx.state_dir, None), default_state.to_string())
        .expect("write default state");

    let auth_path = state_file_path(&ctx.state_dir, Some("auth"));
    fs::create_dir_all(auth_path.parent().unwrap()).expect("create sessions dir");
    fs::write(&auth_path, serde_json::json!({
        "sessionId": "swarm-session-auth",
        "baseUrl": mock_server.base_url(),
    }).to_string()).expect("write auth state");

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
    assert_eq!(result.exit_code, 0, "expected close to handle corrupted state gracefully");

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

    let first_open = run_command(ctx, &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG]);
    assert!(
        first_open
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    let second_open = run_command(ctx, &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG]);
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

    let open_result = run_command(ctx, &["-s=amazon", "open", "https://example.com/", OPEN_PROFILE_MODE_ARG]);
    assert!(
        open_result
            .stdout
            .contains("Session opened: swarm-session-1"),
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

    let first_open = run_command(ctx, &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG]);
    assert!(
        first_open
            .stdout
            .contains("Session opened: swarm-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    mock_server.set_listed_sessions(vec![MockListedSession::stopped("swarm-session-1")]);

    let second_open = run_command(ctx, &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG]);
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

pub(super) fn test_open_reopens_saved_session_after_human_closed_tab(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let workflow_url = "https://example.com/human-closed-tab";
    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["swarm-session-1", "swarm-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(ctx, &["open", "https://example.com/", OPEN_PROFILE_MODE_ARG]);
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
    assert!(
        !second_goto
            .stdout
            .contains("run `browser4-cli open` to create or refresh the session first."),
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
    let css_eval = run_command(ctx, &["eval", "element => element.textContent", "#click-target"]);
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
        !eval_calls[0].arguments["ref"].as_str().unwrap().contains("backend:"),
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
        ],
    );
    let extracted = strip_snapshot_output(&extract_result.stdout);
    assert!(
        extracted.contains("\"Mock Product\"") && extract_result.stdout.contains("### Page"),
        "Expected extract output with snapshot block in:\n{}",
        extract_result.stdout
    );

    let summarize_result = run_command(
        ctx,
        &[
            "summarize",
            "summarize the page marker",
            "--selector=#page-marker",
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
            .contains("browser4-cli agent status agent-task-1"),
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
        "Agent task requires an LLM key and cannot execute",
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
    run_command_expecting_failure(
        ctx,
        &["help", "agent-run"],
        "Use 'browser4-cli help agent run' instead.",
    );
    run_command_expecting_failure(
        ctx,
        &["help", "swarm-create"],
        "Use 'browser4-cli help swarm create' instead.",
    );
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
            "--store-content",
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
        swarm_status_payload.contains(r#""id":"swarm-job-42""#),
        "Expected scrape status payload to contain the task id in:\n{}",
        swarm_status_result.stdout
    );
    assert!(
        swarm_status_payload.contains(r#""isDone":false"#),
        "Expected scrape status payload to remain in-progress in:\n{}",
        swarm_status_result.stdout
    );

    let swarm_result_result = run_command(ctx, &["swarm", "result", "swarm-job-42"]);
    let swarm_result_payload = strip_snapshot_output(&swarm_result_result.stdout);
    assert!(
        swarm_result_payload.contains(r#""id":"swarm-job-42""#),
        "Expected scrape result payload to contain the task id in:\n{}",
        swarm_result_result.stdout
    );
    assert!(
        swarm_result_payload.contains(r#""isDone":true"#),
        "Expected scrape result payload to be done in:\n{}",
        swarm_result_result.stdout
    );
    assert!(
        swarm_result_payload
            .contains(r#""resultSet":[{"url":"https://mock.browser4.local/result/swarm-job-42"}]"#),
        "Expected scrape result payload to contain a resultSet in:\n{}",
        swarm_result_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec![
            "https://example.com/direct -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/seed-1 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/seed-2 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
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
            .contains("browser4-cli swarm status scrape-task-4"),
        "Expected swarm-status example in:\n{}",
        swarm_status_help.stdout
    );

    let swarm_result_help = run_command(ctx, &["help", "swarm", "result"]);
    assert!(
        swarm_result_help
            .stdout
            .contains("browser4-cli swarm result scrape-task-4"),
        "Expected swarm-result example in:\n{}",
        swarm_result_help.stdout
    );

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let submit_failure = run_command_expecting_failure(
        ctx,
        &["swarm", "submit"],
        "Either a URL or --seed-file is required.",
    );
    let submit_failure_output = format!("{}\n{}", submit_failure.stdout, submit_failure.stderr);
    assert!(
        submit_failure_output.contains("Either a URL or --seed-file is required."),
        "Expected swarm-submit validation error in:\n{}",
        submit_failure_output
    );
}

// ---------------------------------------------------------------------------
// install / upgrade (mock download server)
// ---------------------------------------------------------------------------

const INSTALL_TAG: &str = "--tag=v4.10.0";

pub(super) fn test_install_downloads_and_installs(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes);
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &download_server.base_url());

    // Use --tag so the download URL contains the real tag (without a GitHub
    // redirect, parse_release_tag_from_url needs the tag in the path).
    let result = run_command(ctx, &["install", INSTALL_TAG]);
    assert_eq!(result.exit_code, 0, "expected install to succeed:\n{}", result.stderr);
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

    // Verify the metadata file was written.
    let metadata_path = ctx.state_dir.join("lib").join("browser4-installation.json");
    assert!(
        metadata_path.exists(),
        "Expected install metadata at {}",
        metadata_path.display()
    );

    // Verify the runtime was extracted (lib/ dir with jar).
    let lib_dir = ctx.state_dir.join("lib").join("lib");
    assert!(lib_dir.is_dir(), "Expected lib/lib dir after install");
}

pub(super) fn test_install_skips_when_already_installed(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes);
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
}

pub(super) fn test_install_force_re_downloads(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes);
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
    let download_server = FixtureDownloadServer::start(bundle_bytes);
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &download_server.base_url());

    let result = run_command(ctx, &["install", "--tag=v4.9.3"]);
    assert_eq!(result.exit_code, 0, "expected install --tag to succeed:\n{}", result.stderr);
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

    let (bundle_bytes, _dir_name) = build_fake_runtime_bundle("v4.10.0");
    let download_server = FixtureDownloadServer::start(bundle_bytes);
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

    // Install an older version first.
    let (old_bundle, _) = build_fake_runtime_bundle("v4.9.0");
    let server1 = FixtureDownloadServer::start(old_bundle);
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &server1.base_url());
    run_command(ctx, &["install", "--tag=v4.9.0"]);
    drop(server1);

    // Now upgrade to a newer version.
    let (new_bundle, _) = build_fake_runtime_bundle("v4.10.0");
    let server2 = FixtureDownloadServer::start(new_bundle);
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", &server2.base_url());

    let result = run_command(ctx, &["upgrade"]);
    assert_eq!(result.exit_code, 0);
    assert!(
        result.stdout.contains("upgraded successfully"),
        "Expected 'upgraded successfully' in:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("Restart the server"),
        "Expected restart hint in:\n{}",
        result.stdout
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
