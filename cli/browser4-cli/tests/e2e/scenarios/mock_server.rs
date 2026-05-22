use crate::*;

pub(super) fn test_open_uses_temporary_profile_mode(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_command(ctx, &["open", "--profile-mode=TEMPORARY"]);
    assert!(
        open_result
            .stdout
            .contains("Session opened: collective-session-1"),
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
            .contains("Session opened: collective-session-1"),
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
    assert_eq!(navigate_call.arguments["sessionId"], "collective-session-1");
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
    mock_server.queue_open_session_ids(vec!["collective-session-1", "collective-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG]);
    assert!(
        first_open.stdout.contains("Session opened: collective-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    let second_open = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG]);
    assert!(
        second_open
            .stdout
            .contains("Session already open: collective-session-1"),
        "Expected second open to reuse the active session:\n{}",
        second_open.stdout
    );
    assert!(
        !second_open.stdout.contains("Session opened: collective-session-2"),
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
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "collective-session-1");
}

pub(super) fn test_named_session_reuses_opened_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let session_name = "amazon";
    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_command(ctx, &["-s=amazon", "open", OPEN_PROFILE_MODE_ARG]);
    assert!(
        open_result
            .stdout
            .contains("Session opened: collective-session-1"),
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
    assert_eq!(persisted_session_id, "collective-session-1");

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
    assert_eq!(
        navigate_calls.len(),
        1,
        "Expected goto to make exactly one browser_navigate call"
    );
    assert_eq!(
        navigate_calls[0].arguments["sessionId"],
        persisted_session_id
    );
    assert_eq!(navigate_calls[0].arguments["url"], "https://example.com/");
}

pub(super) fn test_open_refreshes_inactive_saved_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["collective-session-1", "collective-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG]);
    assert!(
        first_open.stdout.contains("Session opened: collective-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    mock_server.set_listed_sessions(vec![MockListedSession::stopped(
        "collective-session-1",
    )]);

    let second_open = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG]);
    assert!(
        second_open.stdout.contains("Session opened: collective-session-2"),
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
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "collective-session-2");
}

pub(super) fn test_open_reopens_saved_session_after_human_closed_tab(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let workflow_url = "https://example.com/human-closed-tab";
    let mock_server = MockBrowser4Server::start();
    mock_server.queue_open_session_ids(vec!["collective-session-1", "collective-session-2"]);
    ctx.browser4_base_url = mock_server.base_url();

    let first_open = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG]);
    assert!(
        first_open.stdout.contains("Session opened: collective-session-1"),
        "Expected first open to create the initial session:\n{}",
        first_open.stdout
    );

    mock_server.queue_tool_failure(
        "browser_navigate",
        Some("collective-session-1"),
        Some(workflow_url),
        "browser_navigate failed: Target closed",
    );

    let second_open = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, workflow_url]);
    assert!(
        second_open.stdout.contains("Session opened: collective-session-2"),
        "Expected open to recreate a saved session whose tab was closed externally:\n{}",
        second_open.stdout
    );
    assert!(
        !second_open
            .stdout
            .contains("Session already open: collective-session-1"),
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
        "collective-session-1"
    );

    let navigate_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_navigate")
        .collect();
    assert_eq!(
        navigate_calls.len(),
        2,
        "Expected open to retry browser_navigate with the replacement session"
    );
    assert_eq!(navigate_calls[0].arguments["sessionId"], "collective-session-1");
    assert_eq!(navigate_calls[1].arguments["sessionId"], "collective-session-2");
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "collective-session-2");
}

pub(super) fn test_goto_requires_existing_active_session(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    run_command_expecting_failure(
        ctx,
        &["goto", "https://example.com/missing-session"],
        "No active session for \"goto\".",
    );

    let open_result = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG]);
    assert!(
        open_result.stdout.contains("Session opened: collective-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    mock_server.set_listed_sessions(vec![MockListedSession::stopped(
        "collective-session-1",
    )]);

    run_command_expecting_failure(
        ctx,
        &["goto", "https://example.com/inactive-session"],
        "Run \"browser4-cli open\" to create or refresh the session first.",
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let navigate_calls: Vec<_> = tool_calls
        .iter()
        .filter(|call| call.tool == "browser_navigate")
        .collect();
    assert!(
        navigate_calls.is_empty(),
        "Expected goto to avoid navigation when no active session is available: {:?}",
        tool_calls
    );
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
            .contains("Session opened: collective-session-1"),
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
    assert_eq!(eval_calls[0].arguments["sessionId"], "collective-session-1");
    assert_eq!(eval_calls[0].arguments["expression"], "document.title");
    assert!(eval_calls[0].arguments.get("ref").is_none());
    assert_eq!(eval_calls[1].arguments["sessionId"], "collective-session-1");
    assert_eq!(
        eval_calls[1].arguments["expression"],
        "element => element.textContent"
    );
    assert_eq!(eval_calls[1].arguments["ref"], "backend:5");
}

pub(super) fn test_press_command_uses_direct_tool_dispatch(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result
            .stdout
            .contains("Session opened: collective-session-1"),
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
    assert_eq!(
        press_calls[0].arguments["sessionId"],
        "collective-session-1"
    );
    assert_eq!(press_calls[0].arguments["ref"], "#type-target");
    assert_eq!(press_calls[0].arguments["key"], "!");
    assert!(
        tool_calls
            .iter()
            .all(|call| call.tool != "browser_evaluate"),
        "press should not synthesize browser_evaluate calls: {tool_calls:?}"
    );
}

pub(super) fn test_collective_session_and_agent_tools(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_collective_session(ctx);
    assert_collective_session_call(&mock_server);

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
        summarize_result.stdout.contains("### Page"),
        "Expected summarize output to include a snapshot block:\n{}",
        summarize_result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let extract_call = tool_calls
        .iter()
        .find(|call| call.tool == "agent_extract")
        .expect("expected agent_extract call");
    assert_eq!(extract_call.arguments["sessionId"], "collective-session-1");
    assert_eq!(extract_call.arguments["instruction"], "product name, price");
    assert_eq!(extract_call.arguments["schema"], "{\"type\":\"object\"}");

    let summarize_call = tool_calls
        .iter()
        .find(|call| call.tool == "agent_summarize")
        .expect("expected agent_summarize call");
    assert_eq!(
        summarize_call.arguments["sessionId"],
        "collective-session-1"
    );
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

    let agent_run_result = run_command(ctx, &["agent-run", "collect the latest updates"]);
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
            .contains("browser4-cli agent-status agent-task-1"),
        "Expected agent status hint in:\n{}",
        agent_run_result.stdout
    );

    let agent_status_result = run_command(ctx, &["agent-status", "agent-task-1"]);
    assert_eq!(
        strip_snapshot_output(&agent_status_result.stdout),
        r#"{"id":"agent-task-1","status":"RUNNING"}"#
    );

    let agent_result_result = run_command(ctx, &["agent-result", "agent-task-1"]);
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
        &["agent-run", "task missing llm key"],
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

pub(super) fn test_collective_submission_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_collective_session(ctx);
    assert_collective_session_call(&mock_server);

    let seed_file = ctx.workspace_dir.join("collective-seeds.txt");
    fs::write(
        &seed_file,
        b"# seed urls\nhttps://example.com/seed-1\n\nhttps://example.com/seed-2\n",
    )
    .expect("write seed file failed");
    let seed_file_arg = format!("--seed-file={}", seed_file.to_string_lossy());

    let co_submit_result = run_command(
        ctx,
        &[
            "co",
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
        co_submit_result.stdout.contains("3 URL(s) submitted."),
        "Expected aggregate co submit output in:\n{}",
        co_submit_result.stdout
    );
    assert!(
        co_submit_result
            .stdout
            .contains("Submitted: https://example.com/direct → task co-task-1"),
        "Expected direct URL submission output in:\n{}",
        co_submit_result.stdout
    );

    let co_status_result = run_command(ctx, &["co", "status", "collective-job-42"]);
    let co_status_payload = strip_snapshot_output(&co_status_result.stdout);
    assert!(
        co_status_payload.contains(r#""id":"collective-job-42""#),
        "Expected scrape status payload to contain the task id in:\n{}",
        co_status_result.stdout
    );
    assert!(
        co_status_payload.contains(r#""isDone":false"#),
        "Expected scrape status payload to remain in-progress in:\n{}",
        co_status_result.stdout
    );

    let co_result_result = run_command(ctx, &["co", "result", "collective-job-42"]);
    let co_result_payload = strip_snapshot_output(&co_result_result.stdout);
    assert!(
        co_result_payload.contains(r#""id":"collective-job-42""#),
        "Expected scrape result payload to contain the task id in:\n{}",
        co_result_result.stdout
    );
    assert!(
        co_result_payload.contains(r#""isDone":true"#),
        "Expected scrape result payload to be done in:\n{}",
        co_result_result.stdout
    );
    assert!(
        co_result_payload.contains(r#""resultSet":[{"url":"https://mock.browser4.local/result/collective-job-42"}]"#),
        "Expected scrape result payload to contain a resultSet in:\n{}",
        co_result_result.stdout
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
            .all(|call| call.tool != "command_run" && call.tool != "command_status" && call.tool != "command_result"),
        "Expected collective submission/status/result to avoid MCP command_* calls: {:?}",
        snapshot.tool_calls
    );
    assert_eq!(
        snapshot.status_queries,
        vec!["collective-job-42".to_string()]
    );
    assert_eq!(
        snapshot.result_queries,
        vec!["collective-job-42".to_string()]
    );
}

pub(super) fn test_collective_command_help_and_validation(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let co_create_help = run_command(ctx, &["help", "co-create"]);
    assert!(
        co_create_help.stdout.contains("browser4-cli co-create"),
        "Expected co-create usage in:\n{}",
        co_create_help.stdout
    );
    assert!(
        co_create_help.stdout.contains("browser4-cli co create"),
        "Expected co-create alias example in:\n{}",
        co_create_help.stdout
    );
    assert!(
        co_create_help.stdout.contains("--max-browser-contexts"),
        "Expected co-create options in:\n{}",
        co_create_help.stdout
    );

    let co_submit_help = run_command(ctx, &["help", "co-submit"]);
    assert!(
        co_submit_help.stdout.contains("--seed-file"),
        "Expected seed-file option in:\n{}",
        co_submit_help.stdout
    );
    assert!(
        co_submit_help
            .stdout
            .contains("blank lines and lines beginning with `#` are ignored"),
        "Expected seed-file note in:\n{}",
        co_submit_help.stdout
    );
    assert!(
        co_submit_help
            .stdout
            .contains("browser4-cli co submit https://example.com/direct"),
        "Expected co-submit example in:\n{}",
        co_submit_help.stdout
    );

    let co_status_help = run_command(ctx, &["help", "co-status"]);
    assert!(
        co_status_help.stdout.contains("browser4-cli co status co-task-4"),
        "Expected co-status example in:\n{}",
        co_status_help.stdout
    );

    let co_result_help = run_command(ctx, &["help", "co-result"]);
    assert!(
        co_result_help.stdout.contains("browser4-cli co result co-task-4"),
        "Expected co-result example in:\n{}",
        co_result_help.stdout
    );

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let submit_failure = run_command_expecting_failure(
        ctx,
        &["co", "submit"],
        "Either a URL or --seed-file is required.",
    );
    let submit_failure_output = format!("{}\n{}", submit_failure.stdout, submit_failure.stderr);
    assert!(
        submit_failure_output.contains("Either a URL or --seed-file is required."),
        "Expected co-submit validation error in:\n{}",
        submit_failure_output
    );
}

