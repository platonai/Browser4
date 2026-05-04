use crate::*;

pub(super) fn test_open_uses_temporary_profile_mode(ctx: &mut E2ECtx) {
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
            OPEN_TEMPORARY_PROFILE_ARG,
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

    let co_scrape_result = run_command(
        ctx,
        &[
            "co",
            "scrape",
            "https://example.com/scrape-source",
            "--selector=.item",
            "--attribute=textContent",
            "--output=items.json",
            "--deadline=2026-03-30T00:00:00Z",
            "--expires=6h",
            "--refresh",
        ],
    );
    assert!(
        co_scrape_result
            .stdout
            .contains("Scrape submitted: https://example.com/scrape-source → task co-task-4"),
        "Expected scrape submission output in:\n{}",
        co_scrape_result.stdout
    );
    assert!(co_scrape_result.stdout.contains("selector: .item"));
    assert!(co_scrape_result.stdout.contains("attribute: textContent"));
    assert!(co_scrape_result.stdout.contains("output: items.json"));

    let co_status_result = run_command(ctx, &["co", "status", "collective-job-42"]);
    assert_eq!(
        strip_snapshot_output(&co_status_result.stdout),
        r#"{"id":"collective-job-42","status":"RUNNING"}"#
    );

    let co_result_result = run_command(ctx, &["co", "result", "collective-job-42"]);
    assert_eq!(
        strip_snapshot_output(&co_result_result.stdout),
        "result for collective-job-42"
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec![
            "https://example.com/direct -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/seed-1 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/seed-2 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/scrape-source -deadline 2026-03-30T00:00:00Z -expires 6h -refresh".to_string(),
        ]
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
