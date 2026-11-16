use crate::*;

// ---------------------------------------------------------------------------
// `agent run` end-to-end tests with pre-designed mock AI replies
//
// These scenarios exercise the full CLI -> MCP-over-HTTP -> mock Browser4
// backend path for `browser4-cli agent run`.  The AI-generated portions are
// replaced by deterministic status/result payloads so the tests assert exact
// user-visible output without depending on a real LLM.
// ---------------------------------------------------------------------------

/// The scripted step log used by the `--follow` scenarios: create → start →
/// call → slow success → model text → done.
fn scripted_follow_events(task: &str) -> Vec<serde_json::Value> {
    vec![
        serde_json::json!({"seq": 1, "type": "task.created", "epochMillis": 1}),
        serde_json::json!({"seq": 2, "type": "task.started", "epochMillis": 2, "text": task}),
        serde_json::json!({
            "seq": 3,
            "type": "tool.call",
            "epochMillis": 3,
            "tool": "b4.run",
            "arguments": "{\"args\":\"goto https://example.com\"}",
            "turn": 1
        }),
        serde_json::json!({
            "seq": 4,
            "type": "tool.result",
            "epochMillis": 4,
            "tool": "b4.run",
            "ok": true,
            "durationMs": 2500,
            "text": "ok"
        }),
        serde_json::json!({
            "seq": 5,
            "type": "model.text",
            "epochMillis": 5,
            "text": "I have the release notes now."
        }),
        serde_json::json!({"seq": 6, "type": "task.done", "epochMillis": 6, "text": "done"}),
    ]
}

/// `agent run --follow` streams the task's steps and then prints its result.
pub(super) fn test_agent_run_follow_streams_steps(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the release notes";
    mock_server.set_command_events("agent-task-1", scripted_follow_events(task));
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Release notes extracted"}"#,
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--follow"]);
    let stdout = &run_result.stdout;

    assert!(
        stdout.contains("Task submitted: agent-task-1"),
        "expected the task id in:\n{stdout}"
    );
    assert!(
        stdout.contains("#1 b4.run"),
        "expected the first tool call to stream in:\n{stdout}"
    );
    // P0-3: the arguments are rendered by key, not as one JSON blob.
    assert!(
        stdout.contains(r#"#1 b4.run(args="goto https://example.com")"#),
        "the call line must show the argument a reader can act on:\n{stdout}"
    );
    // P0-2: a slow tool's result line names the call it answers.
    assert!(
        stdout.contains("↳ #1 b4.run ok in 2.5s"),
        "a slow tool reports its completion under its call index:\n{stdout}"
    );
    assert!(
        stdout.contains("I have the release notes now."),
        "the model's text streams too:\n{stdout}"
    );
    assert!(
        stdout.contains("Agent completed in"),
        "follow ends with the completion line:\n{stdout}"
    );
    assert!(
        stdout.contains("Release notes extracted"),
        "follow prints the task result:\n{stdout}"
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.plain_commands, vec![task.to_string()]);
    assert!(
        snapshot.command_input_calls.is_empty(),
        "a one-shot follow must not send steering input: {:?}",
        snapshot.command_input_calls
    );
}

/// `agent run --follow --ndjson` emits one JSON object per line, including the
/// submission and the final result — never human prose.
pub(super) fn test_agent_run_follow_ndjson_is_machine_readable(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the release notes";
    mock_server.set_command_events("agent-task-1", scripted_follow_events(task));
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Release notes extracted"}"#,
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--follow", "--ndjson"]);

    let mut kinds: Vec<String> = Vec::new();
    for line in run_result.stdout.lines().filter(|line| !line.trim().is_empty()) {
        let value: serde_json::Value = serde_json::from_str(line).unwrap_or_else(|error| {
            panic!("NDJSON mode emitted a non-JSON line ({error}): {line}")
        });
        assert_eq!(
            value.get("taskId").and_then(|v| v.as_str()),
            Some("agent-task-1"),
            "every line names the task: {line}"
        );
        if let Some(kind) = value.get("type").and_then(|v| v.as_str()) {
            kinds.push(kind.to_string());
        }
    }

    assert!(kinds.contains(&"task.submitted".to_string()), "kinds: {kinds:?}");
    assert!(kinds.contains(&"tool.call".to_string()), "kinds: {kinds:?}");
    assert!(kinds.contains(&"task.result".to_string()), "kinds: {kinds:?}");
    assert!(
        !run_result.stdout.contains("Task submitted:"),
        "human prose must not leak into NDJSON:\n{}",
        run_result.stdout
    );
}

/// In `--ndjson` mode the status fallback (an events-less backend) must stay
/// JSON-only too: a prose progress line would make the stream unparseable.
pub(super) fn test_agent_run_follow_ndjson_stays_json_in_fallback(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the release notes";
    mock_server.disable_command_events();
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response("agent-task-1", r#"{"summary":"fallback result"}"#);

    let run_result = run_command(ctx, &["agent", "run", task, "--follow", "--ndjson"]);

    for line in run_result.stdout.lines().filter(|line| !line.trim().is_empty()) {
        serde_json::from_str::<serde_json::Value>(line).unwrap_or_else(|error| {
            panic!("the fallback leaked a non-JSON line ({error}): {line}")
        });
    }
    assert!(
        !run_result.stdout.contains("falling back to status polling"),
        "the fallback notice is prose and must be silent in NDJSON:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("\"type\":\"task.result\""),
        "the result must still be emitted as a JSON line:\n{}",
        run_result.stdout
    );
}

/// `agent run --follow` falls back to status polling when the backend predates
/// the events endpoint (404), instead of failing the command.
pub(super) fn test_agent_run_follow_falls_back_without_events_endpoint(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the release notes";
    mock_server.disable_command_events();
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Release notes extracted"}"#,
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--follow"]);
    assert!(
        run_result.stdout.contains("falling back to status polling"),
        "expected an explicit fallback notice:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("Agent completed in"),
        "the status fallback must still complete the task:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("Release notes extracted"),
        "the result still comes from command_result:\n{}",
        run_result.stdout
    );
}

/// `agent run --follow` turns a terminal failure into a rendered line plus a
/// non-zero exit, with the backend's real reason.
pub(super) fn test_agent_run_follow_reports_failure(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the release notes";
    mock_server.set_command_events(
        "agent-task-1",
        vec![
            serde_json::json!({"seq": 1, "type": "task.started", "epochMillis": 1, "text": task}),
            serde_json::json!({
                "seq": 2,
                "type": "tool.result",
                "epochMillis": 2,
                "tool": "b4.run",
                "ok": false,
                "durationMs": 120,
                "text": "[fail] the page was blocked"
            }),
            serde_json::json!({
                "seq": 3,
                "type": "task.failed",
                "epochMillis": 3,
                "text": "the page was blocked"
            }),
        ],
    );
    mock_server.set_command_status_response(
        "agent-task-1",
        serde_json::json!({
            "status": "failed",
            "statusCode": 417,
            "isDone": true,
            "error": "the page was blocked",
            "message": "the page was blocked",
            "errorCode": "PAGE_BLOCKED",
            "resumable": true,
        }),
    );

    let failure = run_command_expecting_failure(
        ctx,
        &["agent", "run", task, "--follow"],
        "Agent task failed: the page was blocked",
    );

    assert!(
        failure.stdout.contains("failed after 120ms"),
        "a failed tool prints its output in-line:\n{}",
        failure.stdout
    );
    assert!(
        failure.stdout.contains("✖ failed"),
        "the follower prints its own failure line:\n{}",
        failure.stdout
    );
    // P1-5: the failure line names the machine code, and the way out is printed
    // under it when the backend says the task can be resumed.
    assert!(
        failure
            .stdout
            .contains("✖ failed · agent-task-1 · ")
            && failure.stdout.contains("— page_blocked: the page was blocked"),
        "the failure line carries the task id and the error code:\n{}",
        failure.stdout
    );
    assert!(
        failure
            .stdout
            .contains("resume: agent run --resume agent-task-1"),
        "a resumable failure shows how to continue:\n{}",
        failure.stdout
    );
}

/// `agent run -i` submits a task from stdin, answers /help locally, and exits
/// cleanly on /quit.
pub(super) fn test_agent_run_interactive_runs_a_task_and_quits(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response("agent-task-1", "interactive result");

    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i"],
        "/help\nSummarize the page\n/quit\n",
    );

    assert!(
        run_result.stdout.contains("/status"),
        "/help must list the session commands:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("/stats"),
        "/help must list the run-statistics command:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("▶ task agent-task-1"),
        "the typed line must be submitted as a task:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.plain_commands, vec!["Summarize the page".to_string()]);
}

/// A line typed while a task runs is a steering message, not a second task.
pub(super) fn test_agent_run_interactive_steers_running_task(ctx: &mut E2ECtx) {    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    // The task stays "running" until an input arrives, so the second stdin line
    // deterministically reaches a live task.
    mock_server.await_command_input("agent-task-1");

    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i"],
        "Summarize the page\nplease add the tests\n/quit\n",
    );

    assert!(
        run_result.stdout.contains("steering queued"),
        "the CLI must confirm the steering message:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec!["Summarize the page".to_string()],
        "steering must not submit a second task"
    );
    assert_eq!(
        snapshot.command_input_calls,
        vec![("agent-task-1".to_string(), "please add the tests".to_string())],
        "the typed line must reach the steering endpoint"
    );
}


/// `/stats` reports what the run cost: LLM rounds and requests, token usage, tool
/// calls — served by `GET /api/commands/{id}/stats`.
pub(super) fn test_agent_run_interactive_reports_run_statistics(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response("agent-task-1", "interactive result");
    mock_server.set_command_stats(
        "agent-task-1",
        serde_json::json!({
            "rounds": 3,
            "modelRequests": 11,
            "toolCalls": 39,
            "toolFailures": 2,
            "steerMessages": 1,
            "inputTokens": 48231,
            "outputTokens": 6120,
            "totalTokens": 54351,
            "events": 132,
            "droppedEvents": 0,
            "toolCounts": {"b4.run": 12, "browser_snapshot": 8},
        }),
    );

    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i"],
        "Summarize the page\n/stats\n/quit\n",
    );

    let stdout = &run_result.stdout;
    assert!(
        stdout.contains("agent-task-1 · done · 41.3s"),
        "/stats must head the report with the task, its status and its duration:\n{stdout}"
    );
    assert!(
        stdout.contains("3 round(s) · 11 request(s)"),
        "/stats must report LLM rounds and model requests:\n{stdout}"
    );
    assert!(
        stdout.contains("in 48,231 · out 6,120 · total 54,351"),
        "/stats must report token consumption:\n{stdout}"
    );
    assert!(
        stdout.contains("39 call(s) · 2 failed (b4.run 12, browser_snapshot 8)"),
        "/stats must report tool calls and their breakdown:\n{stdout}"
    );
    // The completion line asks the same endpoint for its compact cost chain, so
    // the task id shows up once per query (completion line, then /stats).
    assert!(
        stdout.contains("· 3 round(s) · 39 tool(s) · 54.4k tokens"),
        "the completion line must carry the run's cost chain:\n{stdout}"
    );
    let stats_calls = mock_server.command_stats_calls();
    assert!(
        stats_calls.len() >= 2 && stats_calls.iter().all(|id| id == "agent-task-1"),
        "both the completion line and /stats must query the stats endpoint for \
         agent-task-1, got {stats_calls:?}"
    );
}

/// A finished task's payload is rendered as human sections, not dumped as the
/// raw `command_result` JSON — `-i` used to print one multi-KB line.
pub(super) fn test_agent_run_interactive_renders_the_result_sections(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response(
        "agent-task-1",
        &result_envelope().to_string(),
    );
    // The completion line carries the run's cost when the backend serves counters.
    mock_server.set_command_stats(
        "agent-task-1",
        serde_json::json!({"rounds": 3, "toolCalls": 12, "totalTokens": 54351}),
    );

    // Submitting polls the task once immediately, so a task that is already
    // finished renders before the session reads the next stdin line.
    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i"],
        "Summarize the page\n/quit\n",
    );

    let stdout = &run_result.stdout;
    assert!(
        stdout.contains("✔ Agent completed in"),
        "the completion line names the verdict and the elapsed time:\n{stdout}"
    );
    assert!(
        stdout.contains("· agent-task-1 · 3 round(s) · 12 tool(s) · 54.4k tokens"),
        "the completion line carries the task id and the cost chain:\n{stdout}"
    );
    assert!(
        stdout.contains("Summary"),
        "the payload must be rendered as a summary section:\n{stdout}"
    );
    assert!(
        stdout.contains("Fixed the module build."),
        "the summary text must be printed:\n{stdout}"
    );
    assert!(
        stdout.contains("Key findings (2)"),
        "the findings must be listed with their count:\n{stdout}"
    );
    assert!(
        stdout.contains("Files written (1) · workspace: /tmp/workspace.agent-task-1"),
        "the files section must name the workspace:\n{stdout}"
    );
    assert!(
        stdout.contains("Problems (1)"),
        "the problems must be listed:\n{stdout}"
    );
    for plumbing in ["statusTool", "resultTool", "cancelTool", "\"summary\""] {
        assert!(
            !stdout.contains(plumbing),
            "the envelope plumbing ({plumbing}) must not reach a human:\n{stdout}"
        );
    }
}

/// `/result --json` stays the machine escape hatch — but pretty-printed, because a
/// human reads it too and the whole envelope used to arrive as one 5 KB line.
pub(super) fn test_agent_run_interactive_result_json_prints_the_raw_envelope(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response(
        "agent-task-1",
        &result_envelope().to_string(),
    );

    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i"],
        "Summarize the page\n/result --json\n/quit\n",
    );

    let stdout = &run_result.stdout;
    assert!(
        stdout.contains("\"statusTool\": \"command_status\""),
        "/result --json must print the `command_result` envelope, indented:\n{stdout}"
    );
    assert!(
        stdout.contains("\"keyFindings\""),
        "the envelope keeps the payload fields machines read:\n{stdout}"
    );
    // One line per field: the pretty form is what makes the envelope readable.
    let indented = stdout
        .lines()
        .filter(|line| line.starts_with("  \""))
        .count();
    assert!(
        indented >= 3,
        "/result --json must indent its fields, got {indented} indented line(s):\n{stdout}"
    );
}

/// The `command_result` body a real backend answers with: the shared task
/// envelope plus the agent's completion report.
fn result_envelope() -> serde_json::Value {
    serde_json::json!({
        "taskId": "agent-task-1",
        "status": "done",
        "processed": 12,
        "statusCode": 200,
        "statusTool": "command_status",
        "resultTool": "command_result",
        "cancelTool": "command_cancel",
        "result": {
            "summary": "Fixed the module build.",
            "keyFindings": ["The BOM pinned an older zip4j", "No source change was needed"],
            "filesChanged": ["browser4-dependencies/pom.xml"],
            "problems": ["The CI gate was not run"],
            "workspace": "/tmp/workspace.agent-task-1"
        }
    })
}

/// `agent result` renders the payload exactly like `-i` does: sections once, no
/// raw envelope. It used to dump the JSON and then print the same findings a
/// second time from a separate helper.
pub(super) fn test_agent_result_renders_the_payload_sections_once(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response("agent-task-1", &result_envelope().to_string());

    let run_result = run_command(ctx, &["agent", "result", "agent-task-1"]);
    let stdout = &run_result.stdout;

    assert!(
        stdout.contains("Summary"),
        "the payload must be rendered as a summary section:\n{stdout}"
    );
    assert!(
        stdout.contains("Fixed the module build."),
        "the summary text must be printed:\n{stdout}"
    );
    assert_eq!(
        stdout.matches("Key findings").count(),
        1,
        "the completion report must be rendered once, not twice:\n{stdout}"
    );
    assert!(
        stdout.contains("Key findings (2)"),
        "the findings must be listed with their count:\n{stdout}"
    );
    assert!(
        stdout.contains("Files written (1) · workspace: /tmp/workspace.agent-task-1"),
        "the files section must name the workspace:\n{stdout}"
    );
    assert!(
        stdout.contains("Problems (1)"),
        "the problems must be listed:\n{stdout}"
    );
    for plumbing in ["statusTool", "resultTool", "cancelTool", "\"keyFindings\""] {
        assert!(
            !stdout.contains(plumbing),
            "the envelope plumbing ({plumbing}) must not reach a human:\n{stdout}"
        );
    }

    let snapshot = mock_server.snapshot();
    assert_eq!(snapshot.result_queries, vec!["agent-task-1".to_string()]);
}

/// `/access`, `/approve` and `/deny` are answered by the session itself against
/// the agent-access REST endpoints — no task is started and the model never
/// sees them. A deny comes back as 409 with the decision body and must still
/// render as a decision, not an error.
pub(super) fn test_agent_run_interactive_slash_access_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // The task id is passed explicitly so nothing depends on session state.
    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i"],
        "/access task-1\n/approve req-1 task-1\n/deny req-2 task-1\n/quit\n",
    );

    let stdout = &run_result.stdout;
    assert!(
        stdout.contains("task task-1: state=pending"),
        "/access must head the view with the task and its state:\n{stdout}"
    );
    assert!(
        stdout.contains("req-1") && stdout.contains("fs.write") && stdout.contains("src/generated"),
        "/access must list the pending request:\n{stdout}"
    );
    assert!(
        stdout.contains("op-req-9") && stdout.contains("fs.exec"),
        "/access must list the active operator grant:\n{stdout}"
    );
    assert!(
        stdout.contains("req-1: approved (effect=fs.write target=src/generated expires=task-end)"),
        "/approve must render the decision body:\n{stdout}"
    );
    assert!(
        stdout.contains("req-2: denied (effect=fs.exec target=cargo build expires=task-end)"),
        "/deny must render the 409 decision body, not an error:\n{stdout}"
    );

    let snapshot = mock_server.snapshot();
    assert!(
        snapshot
            .agent_access_calls
            .contains(&"GET /api/agent/task-1/access".to_string()),
        "/access must hit the access view endpoint: {:?}",
        snapshot.agent_access_calls
    );
    assert!(
        snapshot
            .agent_access_calls
            .contains(&"POST /api/agent/task-1/access/req-1/approve".to_string()),
        "/approve must hit the decision endpoint: {:?}",
        snapshot.agent_access_calls
    );
    assert!(
        snapshot
            .agent_access_calls
            .contains(&"POST /api/agent/task-1/access/req-2/deny".to_string()),
        "/deny must hit the decision endpoint: {:?}",
        snapshot.agent_access_calls
    );
}

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
    mock_server.set_command_stats(
        "agent-task-1",
        serde_json::json!({"rounds": 2, "toolCalls": 5, "totalTokens": 12345}),
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
    // The same terminal line `-i` prints: verdict, id, cost chain.
    assert!(
        run_result
            .stdout
            .contains("✔ Agent completed in")
            && run_result
                .stdout
                .contains("· agent-task-1 · 2 round(s) · 5 tool(s) · 12.3k tokens"),
        "wait mode must print the shared terminal line with the task id and its cost:\n{}",
        run_result.stdout
    );
    assert!(
        run_result
            .stdout
            .contains("browser4-cli now supports agent run --wait"),
        "Expected pre-designed summary in wait-mode output:\n{}",
        run_result.stdout
    );
    // `--wait` shares the `-i` renderer: sections, not one line of raw JSON.
    assert!(
        run_result.stdout.contains("Summary"),
        "Expected the rendered Summary section in wait-mode output:\n{}",
        run_result.stdout
    );
    assert!(
        !run_result.stdout.contains("\"summary\""),
        "wait mode must not dump the raw command_result JSON:\n{}",
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
/// `--engine=cli` is the only remaining engine id (the `observe-act` engine was
/// removed and is rejected by the backend) — kept here to verify the forwarding
/// path still works.
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
            "--engine=cli",
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
        serde_json::json!("cli"),
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

/// `--access` is a per-task grant: it must reach `command_run` untouched, so the
/// backend can apply (and later reset) exactly the preset the user asked for.
pub(super) fn test_agent_run_forwards_access_preset(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Report the failing module";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response(
        "agent-task-1",
        r#"{"summary":"Only browser4-rest fails"}"#,
    );

    let run_result = run_command(
        ctx,
        &["agent", "run", task, "--coding", "--access", "read-build"],
    );
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "expected task submission in:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert_eq!(
        command_call.arguments["access"],
        serde_json::json!("read-build"),
        "command_run payload should forward the access preset: {:?}",
        command_call.arguments
    );
    assert_eq!(
        command_call.arguments["coding"],
        serde_json::json!(true),
        "coding must still be forwarded alongside access: {:?}",
        command_call.arguments
    );
}

/// Without `--access` the payload must carry no `access` key at all — an explicit
/// `null`/`"full"` would overwrite the server-wide `browser4.agent.access`.
pub(super) fn test_agent_run_omits_access_when_absent(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Read the changelog";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--coding"]);
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "expected task submission in:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert!(
        command_call.arguments.get("access").is_none(),
        "an omitted --access must not appear in the payload (the backend default applies): {:?}",
        command_call.arguments
    );
}

/// `--unrestricted` is a per-task power grant: it must reach `command_run` as `true`, so the
/// backend applies — and later resets — exactly the grant the user asked for.
pub(super) fn test_agent_run_forwards_unrestricted(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Rebuild the pinned toolchain";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response("agent-task-1", r#"{"summary":"Toolchain rebuilt"}"#);

    let run_result = run_command(
        ctx,
        &["agent", "run", task, "--coding", "--unrestricted"],
    );
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "expected task submission in:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert_eq!(
        command_call.arguments["unrestricted"],
        serde_json::json!(true),
        "command_run payload should carry the unrestricted grant: {:?}",
        command_call.arguments
    );
    assert_eq!(
        command_call.arguments["coding"],
        serde_json::json!(true),
        "coding must still be forwarded alongside unrestricted: {:?}",
        command_call.arguments
    );
}

/// Without `--unrestricted` the payload must carry no key at all — an absent flag must never
/// look like a decision the user made, and the server-wide rails stay in force.
pub(super) fn test_agent_run_omits_unrestricted_when_absent(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Read the changelog";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--coding"]);
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "expected task submission in:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert!(
        command_call.arguments.get("unrestricted").is_none(),
        "an omitted --unrestricted must not appear in the payload: {:?}",
        command_call.arguments
    );
}

/// `--add-dir` grants a folder besides the workspace: it must reach `command_run`
/// as an array of **absolute** paths. The backend runs in the runtime bundle, so a
/// path relative to the user's shell directory would name a different place there.
pub(super) fn test_agent_run_forwards_add_dir(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Port the helper from the corpus";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );

    // The CLI resolves nothing here: an absolute folder is forwarded verbatim, so the
    // assertion does not depend on the child's working directory.
    let corpus = ctx.workspace_dir.join("added-corpus");
    std::fs::create_dir_all(&corpus).expect("create the granted folder");
    let corpus_arg = corpus.to_string_lossy().to_string();

    let run_result = run_command(
        ctx,
        &["agent", "run", task, "--coding", "--add-dir", corpus_arg.as_str()],
    );
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "expected task submission in:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert_eq!(
        command_call.arguments["addDirs"],
        serde_json::json!([corpus_arg]),
        "command_run payload should forward --add-dir as an absolute path array: {:?}",
        command_call.arguments
    );
    assert!(
        snapshot
            .tool_calls
            .iter()
            .filter(|call| call.tool == "command_run")
            .all(|call| call.arguments.get("addDirs").is_some()),
        "the grant must travel with the submission: {:?}",
        snapshot.tool_calls
    );
}

/// Without `--add-dir` the payload carries no `addDirs` key at all, so the task
/// keeps its workspace-only boundary (an empty array would still be a grant).
pub(super) fn test_agent_run_omits_add_dir_when_absent(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Read the changelog";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );

    let run_result = run_command(ctx, &["agent", "run", task, "--coding"]);
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "expected task submission in:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert!(
        command_call.arguments.get("addDirs").is_none(),
        "an omitted --add-dir must not appear in the payload: {:?}",
        command_call.arguments
    );
}

/// In an interactive session the preset applies to the tasks typed later, not
/// just to the invocation — the same grant, one payload each.
pub(super) fn test_agent_run_interactive_forwards_access_preset(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response("agent-task-1", "interactive result");

    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i", "--coding", "--access", "read"],
        "Report the failing module\n/quit\n",
    );
    assert!(
        run_result.stdout.contains("▶ task agent-task-1"),
        "the typed line must be submitted as a task:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert_eq!(
        command_call.arguments["access"],
        serde_json::json!("read"),
        "the interactive session must carry its access preset: {:?}",
        command_call.arguments
    );
}

/// `/add-dir <dir>` inside `-i` is the live form of `--add-dir`: the next task
/// submitted from the same session must carry the grant, so a long session does not
/// have to be restarted (losing the agent context) to reach another folder.
pub(super) fn test_agent_run_interactive_add_dir_grants_the_next_task(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response("agent-task-1", "interactive result");

    let corpus = ctx.workspace_dir.join("interactive-corpus");
    std::fs::create_dir_all(&corpus).expect("create the granted folder");
    let corpus_arg = corpus.to_string_lossy().to_string();

    let stdin = format!("/add-dir {corpus_arg}\nFix the failing test\n/quit\n");
    let run_result = run_command_with_stdin(ctx, &["agent", "run", "-i", "--coding"], &stdin);
    assert!(
        run_result.stdout.contains("added:"),
        "the session must confirm the grant:\n{}",
        run_result.stdout
    );
    assert!(
        run_result.stdout.contains("▶ task agent-task-1"),
        "the typed line after /add-dir must be submitted as a task:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert_eq!(
        command_call.arguments["addDirs"],
        serde_json::json!([corpus_arg]),
        "a folder granted with /add-dir must travel with the next task: {:?}",
        command_call.arguments
    );
}

/// A one-shot `agent run` has nobody to watch it, so it leaves the decision to the
/// backend: the payload must not invent an `unattended` value of its own (plan P4-1).
pub(super) fn test_agent_run_omits_unattended(ctx: &mut E2ECtx) {
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let task = "Fix the failing test";
    mock_server.set_command_status_response(
        "agent-task-1",
        completed_status("agent-task-1", task, "done"),
    );
    mock_server.set_command_result_response("agent-task-1", r#"{"summary":"Fixed"}"#);

    let run_result = run_command(
        ctx,
        &["agent", "run", task, "--coding", "--access", "read-build"],
    );
    assert!(
        run_result.stdout.contains("Task submitted: agent-task-1"),
        "expected task submission in:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert!(
        command_call.arguments.get("unattended").is_none(),
        "a one-shot run must leave unattended to the backend: {:?}",
        command_call.arguments
    );
    assert_eq!(
        command_call.arguments["access"],
        serde_json::json!("read-build"),
        "the access preset must still be forwarded: {:?}",
        command_call.arguments
    );
}

/// The interactive session is the only place the CLI decides: stdin without a TTY
/// cannot answer an approval, so every task it submits is marked unattended.
pub(super) fn test_agent_run_interactive_marks_unattended(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();
    mock_server.set_command_result_response("agent-task-1", "interactive result");

    let run_result = run_command_with_stdin(
        ctx,
        &["agent", "run", "-i", "--coding"],
        "Fix the failing test\n/quit\n",
    );
    assert!(
        run_result.stdout.contains("▶ task agent-task-1"),
        "the typed line must be submitted as a task:\n{}",
        run_result.stdout
    );

    let snapshot = mock_server.snapshot();
    let command_call = snapshot
        .tool_calls
        .iter()
        .find(|call| call.tool == "command_run")
        .expect("expected command_run MCP tool call");
    assert_eq!(
        command_call.arguments["unattended"],
        serde_json::json!(true),
        "piped stdin is not a TTY, so the session must mark tasks unattended: {:?}",
        command_call.arguments
    );
}
