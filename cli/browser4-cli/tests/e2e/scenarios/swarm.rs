use crate::*;

pub(super) fn test_swarm_submission_commands_live(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let swarm_create_result = run_command(
        ctx,
        &[
            "swarm",
            "create",
            "--profile-mode=TEMPORARY",
            "--max-open-tabs=4",
            "--max-browser-contexts=2",
            "--display-mode=HEADLESS",
        ],
    );
    let swarm_create_output = strip_snapshot_output(&swarm_create_result.stdout);
    assert!(
        swarm_create_output.contains("Swarm session created:"),
        "Expected swarm session creation output in:\n{}",
        swarm_create_result.stdout
    );

    let session_id = read_persisted_session_id(&ctx.state_dir);
    assert!(
        !session_id.trim().is_empty(),
        "Expected swarm create to persist a non-empty session id"
    );
    assert!(
        swarm_create_output.contains(&session_id),
        "Expected swarm create output to include persisted session id '{session_id}' in:\n{}",
        swarm_create_result.stdout
    );

    let list_result = run_command(ctx, &["list"]);
    let list_output = strip_snapshot_output(&list_result.stdout);
    assert!(
        list_output.contains(&session_id),
        "Expected swarm-created session id '{session_id}' in list output:\n{list_output}"
    );

    let expected_url = ctx.interactive_url();
    let swarm_sql = format!(
        "select dom_base_uri(dom) as url from load_and_select('{}', ':root')",
        expected_url
    );

    let swarm_submit_result = run_command(ctx, &["swarm", "submit", &swarm_sql]);
    let swarm_submit_output = strip_snapshot_output(&swarm_submit_result.stdout);
    assert!(
        swarm_submit_output.contains("Submitted:"),
        "Expected swarm submit output in:\n{}",
        swarm_submit_result.stdout
    );

    let submitted_tasks = extract_swarm_submissions(&swarm_submit_output);
    assert_eq!(
        submitted_tasks.len(),
        1,
        "Expected one submitted swarm task in:\n{}",
        swarm_submit_result.stdout
    );
    assert!(
        submitted_tasks.iter().any(
            |(submitted_payload, task_id)| submitted_payload == &swarm_sql && !task_id.is_empty()
        ),
        "Expected submitted swarm output to include the X-SQL payload with a task id in:\n{}",
        swarm_submit_result.stdout
    );

    for (_submitted_payload, task_id) in &submitted_tasks {
        let swarm_status_result = run_command(ctx, &["swarm", "status", task_id]);
        let swarm_status = parse_json_output(&swarm_status_result.stdout, "swarm status");
        assert_eq!(
            swarm_status["id"].as_str(),
            Some(task_id.as_str()),
            "Expected swarm status payload to reference task id '{task_id}', got:\n{}",
            swarm_status_result.stdout
        );
        assert!(
            swarm_done_flag(&swarm_status).is_some(),
            "Expected swarm status payload to include a boolean done/isDone field, got:\n{}",
            swarm_status_result.stdout
        );

        let swarm_result = wait_for_swarm_result(ctx, task_id, 45_000);
        assert_eq!(
            swarm_result["id"].as_str(),
            Some(task_id.as_str()),
            "Expected swarm result payload to reference task id '{task_id}', got:\n{swarm_result}"
        );
        assert_eq!(
            swarm_done_flag(&swarm_result),
            Some(true),
            "Expected swarm result payload to be done for task '{task_id}', got:\n{swarm_result}"
        );
        assert!(
            swarm_result["statusCode"]
                .as_i64()
                .is_some_and(|status| (200..400).contains(&status)),
            "Expected swarm result payload to report a successful statusCode for task '{task_id}', got:\n{swarm_result}"
        );

        let result_set = swarm_result["resultSet"].as_array().unwrap_or_else(|| {
            panic!("Expected swarm resultSet array for task '{task_id}', got:\n{swarm_result}")
        });
        assert!(
            !result_set.is_empty(),
            "Expected swarm resultSet to be non-empty for task '{task_id}', got:\n{swarm_result}"
        );
        assert!(
            result_set
                .iter()
                .filter_map(|row| row.get("url").and_then(|value| value.as_str()))
                .any(|url| url == expected_url),
            "Expected swarm resultSet to contain URL '{expected_url}' for task '{task_id}', got:\n{swarm_result}"
        );
    }

    run_command(ctx, &["close"]);
}
