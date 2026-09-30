use crate::*;

pub(super) fn test_agent_run_live_or_missing_llm_key(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let task = format!(
        "Navigate to {} and report the page title.",
        ctx.interactive_url()
    );
    let result = run_command_allowing_failure(ctx, &["agent", "run", &task]);

    if result.exit_code == 0 {
        assert!(
            result.stdout.contains("Task submitted:"),
            "Expected task submission output in:\n{}",
            result.stdout
        );
        let task_id = extract_submitted_task_id(&result.stdout);
        let status_result = run_command(ctx, &["agent", "status", &task_id]);
        let status = strip_snapshot_output(&status_result.stdout);
        assert!(
            status.contains(&task_id),
            "Expected task status to mention submitted task id '{task_id}', got:\n{status}"
        );
        assert!(
            status.contains("\"status\""),
            "Expected JSON status payload in:\n{status}"
        );
    } else {
        let combined = format!("{}\n{}", result.stdout, result.stderr);
        // The error message format depends on what the server returns.
        // - With older backends: "requires an LLM API key" / "The LLM is not configured"
        // - With newer backends: "Failed to send chat message … User not found (401)"
        // Accept any of these variants.
        let has_legacy_msg = combined.contains("requires an LLM API key")
            || combined.contains("The LLM is not configured");
        let has_new_msg = combined.contains("Failed to send chat message")
            && (combined.contains("User not found") || combined.contains("401"));
        assert!(
            has_legacy_msg || has_new_msg,
            "Expected missing-LLM message in:\n{combined}"
        );
    }
}

/// Multi-step CLI-engine agent run backed by FileBackedChatModel: two real
/// `cli.run` tool calls followed by a plain-text final report.
pub(super) fn test_agent_run_mock_llm_multi_step_cli_tools(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let scenario = "test_e2e_agent_run_mock_llm_multi_step_cli_tools";
    let fixture_url = ctx.interactive_url();
    write_file_backed_llm_responses(
        ctx,
        scenario,
        &[
            &format!(
                r#"{{"text":"step 1: open","toolCalls":[{{"id":"t1","name":"cli_run","arguments":{{"args":"open {fixture_url}"}}}}]}}"#
            ),
            r#"{"text":"step 2: snapshot","toolCalls":[{"id":"t2","name":"cli_run","arguments":{"args":"snapshot --stdout"}}]}"#,
            r#"{"text":"work done, finalizing"}"#,
            r#"{"text":"multi-step mock completed: page opened and snapshotted"}"#,
        ],
    );

    let result = run_command(
        ctx,
        &[
            "agent",
            "run",
            "Open the fixture page, take a snapshot, and report the result",
            "--wait",
        ],
    );
    assert!(
        result.stdout.contains("Agent completed in"),
        "Expected wait mode completion in:\n{}",
        result.stdout
    );
    assert!(
        result
            .stdout
            .contains("multi-step mock completed: page opened and snapshotted"),
        "Expected file-backed final report in:\n{}",
        result.stdout
    );
}

/// Multi-step run that completes through the `system.taskComplete` protocol.
/// A trailing text reply is scripted so the LangChain4j loop can return after
/// executing the completion tool; the agent uses the taskComplete summary.
pub(super) fn test_agent_run_mock_llm_task_complete_protocol(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let scenario = "test_e2e_agent_run_mock_llm_task_complete_protocol";
    let fixture_url = ctx.interactive_url();
    write_file_backed_llm_responses(
        ctx,
        scenario,
        &[
            &format!(
                r#"{{"text":"step 1: open","toolCalls":[{{"id":"t1","name":"cli_run","arguments":{{"args":"open {fixture_url}"}}}}]}}"#
            ),
            r#"{"text":"completing","toolCalls":[{"id":"t9","name":"system_taskComplete","arguments":{"summary":"taskComplete summary: mock finished","keyFindings":["opened","reported"]}}]}"#,
            r#"{"text":"trailing text after completion tool"}"#,
            r#"{"text":"final trailing reply"}"#,
        ],
    );

    let result = run_command(
        ctx,
        &[
            "agent",
            "run",
            "Open the fixture page and finish with a structured summary",
            "--wait",
        ],
    );
    assert!(
        result.stdout.contains("Agent completed in"),
        "Expected wait mode completion in:\n{}",
        result.stdout
    );
    assert!(
        result
            .stdout
            .contains("taskComplete summary: mock finished"),
        "Expected system.taskComplete summary in:\n{}",
        result.stdout
    );
}

/// Async `agent run` with file-backed LLM: submit, poll status, then read the
/// scripted result with `agent result`.
pub(super) fn test_agent_run_mock_llm_async_then_result(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let scenario = "test_e2e_agent_run_mock_llm_async_then_result";
    let fixture_url = ctx.interactive_url();
    write_file_backed_llm_responses(
        ctx,
        scenario,
        &[
            &format!(
                r#"{{"text":"step 1: open","toolCalls":[{{"id":"t1","name":"cli_run","arguments":{{"args":"open {fixture_url}"}}}}]}}"#
            ),
            r#"{"text":"continuing after tool"}"#,
            r#"{"text":"async mock result: scripted multi-step summary"}"#,
        ],
    );

    let run_result = run_command(
        ctx,
        &["agent", "run", "Open the fixture page and summarize it"],
    );
    assert!(
        run_result.stdout.contains("Task submitted:"),
        "Expected async submission in:\n{}",
        run_result.stdout
    );
    let task_id = extract_submitted_task_id(&run_result.stdout);

    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let status_result = run_command(ctx, &["agent", "status", &task_id]);
        let status = strip_snapshot_output(&status_result.stdout);
        if status.contains("\"processState\":\"done\"")
            || status.contains("\"processState\":\"completed\"")
            || status.contains("\"status\":\"COMPLETED\"")
        {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "Agent task did not reach a terminal state within 60s. Last status:\n{status}"
        );
        sleep(Duration::from_millis(500));
    }

    let result_result = run_command(ctx, &["agent", "result", &task_id]);
    let result_output = strip_snapshot_output(&result_result.stdout);
    assert!(
        result_output.contains("async mock result: scripted multi-step summary"),
        "Expected scripted async result in:\n{}",
        result_output
    );
}

/// The agent executes a failing CLI tool, receives the error as tool output,
/// and then completes with a text-only final report.
pub(super) fn test_agent_run_mock_llm_failure_then_recovery(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let scenario = "test_e2e_agent_run_mock_llm_failure_then_recovery";
    write_file_backed_llm_responses(
        ctx,
        scenario,
        &[
            r#"{"text":"step 1: run bad command","toolCalls":[{"id":"t1","name":"cli_run","arguments":{"args":"definitely-not-a-real-command --bogus"}}]}"#,
            r#"{"text":"continuing after tool failure"}"#,
            r#"{"text":"recovered after tool failure"}"#,
        ],
    );

    let result = run_command(
        ctx,
        &[
            "agent",
            "run",
            "Run a command that fails, then report the recovery",
            "--wait",
        ],
    );
    assert!(
        result.stdout.contains("Agent completed in"),
        "Expected wait mode completion in:\n{}",
        result.stdout
    );
    assert!(
        result.stdout.contains("recovered after tool failure"),
        "Expected file-backed recovery report in:\n{}",
        result.stdout
    );
}
