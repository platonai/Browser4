use crate::*;

// ---------------------------------------------------------------------------
// `agent run` end-to-end tests with pre-designed mock AI replies
//
// These scenarios exercise the full CLI -> MCP-over-HTTP -> mock Browser4
// backend path for `browser4-cli agent run`.  The AI-generated portions are
// replaced by deterministic status/result payloads so the tests assert exact
// user-visible output without depending on a real LLM.
// ---------------------------------------------------------------------------

fn completed_status(
    task_id: &str,
    instruction: &str,
    process_state: &str,
) -> serde_json::Value {
    serde_json::json!({
        "id": task_id,
        "statusCode": 200,
        "processState": process_state,
        "isDone": true,
        "message": "Mock agent completed successfully",
        "agentHistory": {
            "states": [
                {
                    "step": 1,
                    "instruction": instruction,
                    "summary": "Mock completion summary"
                }
            ]
        }
    })
}

/// Async submission returns a task ID immediately and does not block for the
/// AI answer; `agent result` later returns the pre-designed summary.
pub(super) fn test_agent_run_async_with_predefined_result(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Summarize the dashboard KPIs";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"KPI summary: 12 active users, 3% churn"}"#,
    );

    let run_result = run_command(ctx, &["agent", "run", task]);
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "Expected async task submission in:\n{}",
        run_result.stdout
    );
    assert!(
        run_result
            .stdout
            .contains("agent status agent-task-1"),
        "Expected agent status hint in:\n{}",
        run_result.stdout
    );
    assert!(
        !run_result.stdout.contains("Agent completed in"),
        "Async `agent run` should not block for the result:\n{}",
        run_result.stdout
    );

    let result_result = run_command(ctx, &["agent", "result", "agent-task-1"]);
    let result_output = strip_snapshot_output(&result_result.stdout);
    assert!(
        result_output.contains("KPI summary") && result_output.contains("12 active users"),
        "Expected pre-designed AI result in:\n{}",
        result_output
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.plain_commands, vec![task.to_string()]);
    assert_eq!(snapshot.result_queries, vec!["agent-task-1".to_string()]);
    assert!(
        snapshot.status_queries.iter().any(|query| query == "agent-task-1"),
        "Expected at least one status probe for agent-task-1, got {:?}",
        snapshot.status_queries
    );
}

/// `agent run --wait` polls until the mock backend reports `processState: done`
/// and then prints the pre-designed result.
pub(super) fn test_agent_run_wait_completed_with_predefined_summary(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the release notes";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Release notes: browser4-cli now supports agent run --wait."}"#,
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--wait"]);
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "Expected task submission in:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("Agent completed in"),
        "Expected wait mode to report completion timing in:\n{}",
        run_result.stdout
    );
    assert!(
        run_result
            .stdout
            .contains("browser4-cli now supports agent run --wait"),
        "Expected pre-designed summary in wait-mode output:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.plain_commands, vec![task.to_string()]);
    assert!(snapshot.result_queries.iter().any(|query| query == "agent-task-1"));
}

/// `agent run --wait` turns a pre-designed terminal failure into a non-zero
/// exit code and an actionable error message.
pub(super) fn test_agent_run_wait_failure_with_predefined_reason(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Trigger a planned agent failure";
    mock_server.set_command_status_response(
        "agent-task-1",
        serde_json::json!({
            "id": "agent-task-1",
            "statusCode": 500,
            "processState": "done",
            "isDone": true,
            "failureReason": "Planned failure: auth expired",
            "message": "Agent task failed"
        }),
    );

    let failure = run_command_expecting_failure(
        ctx,
        &["agent", "run", task, "--wait"],
        "Agent task failed: Planned failure: auth expired",
    );
    let combined = format!("{}\n{}", failure.stdout, failure.stderr);
    assert!(
        combined.contains("Planned failure: auth expired"),
        "Expected pre-designed failure reason in:\n{}",
        combined
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.plain_commands, vec![task.to_string()]);
    assert!(snapshot.result_queries.is_empty());
}

/// The wait loop must also accept the modern `processState: completed`
/// terminal state, not only the legacy `done` spelling.
pub(super) fn test_agent_run_wait_completed_state_variant(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Collect the changelog";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "completed"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Changelog: one new agent command."}"#,
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--wait"]);
    assert!(
        run_result.stdout.contains("Agent completed in"),
        "Expected `processState: completed` to be treated as terminal:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("Changelog: one new agent command"),
        "Expected pre-designed summary in:\n{}",
        run_result.stdout
    );
}

/// `agent run --wait` writes the completed task into the local tracking list so
/// `agent list` reflects it without a separate `agent status` call.
pub(super) fn test_agent_run_wait_completed_tracks_task_in_list(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the release notes";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Release notes: wait mode tracks the task."}"#,
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--wait"]);
    assert!(
        run_result.stdout.contains("Agent completed in"),
        "Expected wait mode to report completion timing in:\n{}",
        run_result.stdout
    );

    // The completed task must be visible in the local tracking list.
    let list_result = run_command(ctx, &["agent", "list"]);
    let list_output = format!("{}\n{}", list_result.stdout, list_result.stderr);
    assert!(
        list_output.contains("agent-task-1"),
        "Completed wait-mode task should appear in agent list:\n{}",
        list_output
    );
    assert!(
        list_output.contains("completed"),
        "Completed wait-mode task should be marked completed:\n{}",
        list_output
    );
}

/// `agent run --wait` with a short `--wait-timeout` exits non-zero while the
/// task keeps running server-side, and the task stays visible in `agent list`
/// so the user can poll it later with `agent status`.
pub(super) fn test_agent_run_wait_timeout_keeps_task_tracked(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Keep working forever";
    // No custom command_status registered — the mock returns a RUNNING status
    // without a terminal processState, so the wait loop must hit its timeout.
    let failure = run_command_expecting_failure(
        ctx,
        &["agent", "run", task, "--wait", "--wait-timeout=2"],
        "timed out after 2s",
    );
    let combined = format!("{}\n{}", failure.stdout, failure.stderr);
    assert!(
        combined.contains("Use 'agent status agent-task-1' to check later"),
        "Timeout error should point at the task id:\n{}",
        combined
    );

    // The task must remain tracked locally even though the wait loop gave up.
    let list_result = run_command(ctx, &["agent", "list"]);
    let list_output = format!("{}\n{}", list_result.stdout, list_result.stderr);
    assert!(
        list_output.contains("agent-task-1"),
        "Timed-out task should appear in agent list:\n{}",
        list_output
    );
    assert!(
        list_output.contains("processing"),
        "Timed-out task should be shown as processing:\n{}",
        list_output
    );
}

/// Multiple `agent run` invocations map to distinct mock replies and remain
/// independently retrievable through `agent result`.
pub(super) fn test_agent_run_multiple_tasks_distinct_replies(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task_one = "Extract product titles";
    let task_two = "Extract product prices";

    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task_one, "done"),
    );
    mock_server.set_command_status_response(
        "agent-task-2",
        completed_status("agent-task-2", task_two, "done"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Mock title list: Alpha, Beta, Gamma"}"#,
    );
    mock_server.set_command_result_response(
        "agent-task-2",
        r#"{"summary":"Mock price list: $10, $20, $30"}"#,
    );

    let run_one = run_command(ctx, &["agent", "run", task_one]);
    assert!(
        run_one.stdout.contains("Task submitted: agent-task-1"),
        "Expected first task ID in:\n{}",
        run_one.stdout
    );
    let run_two = run_command(ctx, &["agent", "run", task_two]);
    assert!(
        run_two.stdout.contains("Task submitted: agent-task-2"),
        "Expected second task ID in:\n{}",
        run_two.stdout
    );

    let result_one = run_command(ctx, &["agent", "result", "agent-task-1"]);
    let output_one = strip_snapshot_output(&result_one.stdout);
    assert!(
        output_one.contains("Mock title list") && output_one.contains("Alpha, Beta, Gamma"),
        "Expected first pre-designed result in:\n{}",
        output_one
    );
    assert!(
        !output_one.contains("Mock price list"),
        "First result should not contain the second task's reply:\n{}",
        output_one
    );

    let result_two = run_command(ctx, &["agent", "result", "agent-task-2"]);
    let output_two = strip_snapshot_output(&result_two.stdout);
    assert!(
        output_two.contains("Mock price list") && output_two.contains("$10, $20, $30"),
        "Expected second pre-designed result in:\n{}",
        output_two
    );
    assert!(
        !output_two.contains("Mock title list"),
        "Second result should not contain the first task's reply:\n{}",
        output_two
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec![task_one.to_string(), task_two.to_string()]
    );
    assert_eq!(
        snapshot.result_queries,
        vec!["agent-task-1".to_string(), "agent-task-2".to_string()]
    );
}

/// `agent run` forwards `--engine` and `--noop-limit` into the MCP
/// `command_run` payload while still returning the pre-designed AI result.
/// `--engine=observe-act` selects the legacy engine (DEPRECATED) — kept here
/// to verify the forwarding path still works.
pub(super) fn test_agent_run_forwards_engine_and_noop_limit(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Do a long coding task";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Mock result for engine forwarding test"}"#,
    );

    let run_result = run_command(
        ctx,
        &[
            "agent",
            "run",
            task,
            "--engine=observe-act",
            "--noop-limit=9",
        ],
    );
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "Expected task submission in:\n{}",
        run_result.stdout
    );

    let result_result = run_command(ctx, &["agent", "result", "agent-task-1"]);
    assert!(
        strip_snapshot_output(&result_result.stdout)
            .contains("Mock result for engine forwarding test"),
        "Expected pre-designed result in:\n{}",
        result_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert_eq!(
        command_call.arguments["command"],
        serde_json::json!(task),
        "command_run payload should contain the original task: {:?}",
        command_call.arguments
    );
    assert_eq!(
        command_call.arguments["engine"],
        serde_json::json!("observe-act"),
        "command_run payload should forward engine: {:?}",
        command_call.arguments
    );
    assert_eq!(
        command_call.arguments["noopLimit"],
        serde_json::json!(9),
        "command_run payload should forward noopLimit: {:?}",
        command_call.arguments
    );
}
