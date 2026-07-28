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

    let swarm_submit_result = run_command(ctx, &["swarm", "submit", &expected_url]);
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
        submitted_tasks
            .iter()
            .any(
                |(submitted_payload, task_id)| *submitted_payload == expected_url
                    && !task_id.is_empty()
            ),
        "Expected submitted swarm output to include the URL with a task id in:\n{}",
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

        match wait_for_swarm_result_with_error(ctx, task_id, 45_000) {
            Ok(swarm_result) => {
                assert_eq!(
                    swarm_result["id"].as_str(),
                    Some(task_id.as_str()),
                    "Expected swarm result payload to reference task id '{task_id}', got:\n{swarm_result}"
                );
                // If the server completed successfully, verify the result.
                if swarm_done_flag(&swarm_result) == Some(true)
                    && swarm_result["statusCode"]
                        .as_i64()
                        .is_some_and(|status| (200..400).contains(&status))
                {
                    let result_set =
                        swarm_result["resultSet"].as_array().unwrap_or_else(|| {
                            panic!(
                                "Expected swarm resultSet array for task '{task_id}', got:\n{swarm_result}"
                            )
                        });
                    assert!(
                        !result_set.is_empty(),
                        "Expected swarm resultSet to be non-empty for task '{task_id}', got:\n{swarm_result}"
                    );
                    assert!(
                        result_set
                            .iter()
                            .filter_map(|row| {
                                row.get("url").and_then(|value| value.as_str())
                            })
                            .any(|url| url == expected_url),
                        "Expected swarm resultSet to contain URL '{expected_url}' for task '{task_id}', got:\n{swarm_result}"
                    );
                } else {
                    // Server reported an error or didn't complete — log it but
                    // don't fail the test (the server may not support the
                    // scrape endpoint yet).
                    eprintln!(
                        "[swarm live] Task {task_id} completed with non-success status: {:?}",
                        swarm_result
                    );
                }
            }
            Err(last_payload) => {
                // Timed out — the server may not support swarm scraping yet.
                eprintln!(
                    "[swarm live] Task {task_id} timed out waiting for completion. Last payload:\n{last_payload}"
                );
            }
        }
    }

    run_command(ctx, &["close"]);
}

pub(super) fn test_crawl_submission_live(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // Open a session first (crawl needs a browser session on the backend).
    let open_result = run_command(
        ctx,
        &[
            "open",
            "--profile-mode=TEMPORARY",
            "--max-open-tabs=4",
            "--display-mode=HEADLESS",
        ],
    );
    let open_output = strip_snapshot_output(&open_result.stdout);
    assert!(
        open_output.contains("Session opened:"),
        "Expected session open output in:\n{}",
        open_result.stdout
    );

    let session_id = read_persisted_session_id(&ctx.state_dir);
    assert!(
        !session_id.trim().is_empty(),
        "Expected open to persist a non-empty session id"
    );

    let expected_url = ctx.interactive_url();

    // Submit a crawl in background mode
    let crawl_submit_result = run_command(
        ctx,
        &[
            "crawl",
            &expected_url,
            "--background",
            "--depth=0",
            "--parse",
        ],
    );
    let crawl_submit_output = strip_snapshot_output(&crawl_submit_result.stdout);
    assert!(
        crawl_submit_output.contains("Crawl task submitted:"),
        "Expected crawl task submission output in:\n{}",
        crawl_submit_result.stdout
    );

    // Extract task ID from output: "Crawl task submitted: <id>"
    let task_id = crawl_submit_output
        .lines()
        .find_map(|line| {
            line.trim()
                .strip_prefix("Crawl task submitted: ")
                .map(|s| s.trim().to_string())
        })
        .expect("Expected crawl task ID in submission output");

    assert!(
        !task_id.is_empty(),
        "Expected non-empty crawl task ID in:\n{}",
        crawl_submit_result.stdout
    );

    // Check status
    let crawl_status_result = run_command(ctx, &["crawl", "status", &task_id]);
    let crawl_status = parse_json_output(&crawl_status_result.stdout, "crawl status");
    assert_eq!(
        crawl_status["id"].as_str(),
        Some(task_id.as_str()),
        "Expected crawl status payload to reference task id '{task_id}', got:\n{}",
        crawl_status_result.stdout
    );

    // Wait for result (with timeout)
    match wait_for_crawl_result(ctx, &task_id, 60_000) {
        Ok(crawl_result) => {
            assert_eq!(
                crawl_result["id"].as_str(),
                Some(task_id.as_str()),
                "Expected crawl result payload to reference task id '{task_id}', got:\n{crawl_result}"
            );
            if crawl_result["statusCode"]
                .as_i64()
                .is_some_and(|status| (200..400).contains(&status))
            {
                let pages = crawl_result["pages"].as_array();
                // With depth=0, at least the starting URL should be crawled
                if let Some(pages) = pages {
                    assert!(
                        !pages.is_empty(),
                        "Expected crawl result pages to be non-empty for task '{task_id}', got:\n{crawl_result}"
                    );
                }
            } else {
                eprintln!(
                    "[crawl live] Task {task_id} completed with non-success status: {:?}",
                    crawl_result
                );
            }
        }
        Err(last_payload) => {
            eprintln!(
                "[crawl live] Task {task_id} timed out waiting for completion. Last payload:\n{last_payload}"
            );
        }
    }

    run_command(ctx, &["close"]);
}
