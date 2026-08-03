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
