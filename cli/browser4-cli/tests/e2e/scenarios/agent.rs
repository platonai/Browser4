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
        assert!(
            combined.contains("requires an LLM API key"),
            "Expected missing-LLM message in:\n{combined}"
        );
        assert!(
            combined.contains("The LLM is not configured")
                || combined.to_ascii_lowercase().contains("api key"),
            "Expected specific LLM configuration detail in:\n{combined}"
        );
    }
}
