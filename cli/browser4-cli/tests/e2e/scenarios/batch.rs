use crate::*;

pub(super) fn test_batch_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    let interactive_url = ctx.interactive_url();
    let navigate_command = batch_navigate_command(&interactive_url);
    let type_command = "type 'hello batch' #type-target".to_string();
    let click_command = "click #click-target".to_string();

    run_command(
        ctx,
        &[
            "batch",
            navigate_command.as_str(),
            type_command.as_str(),
            click_command.as_str(),
        ],
    );
    wait_for_state_or_abort(
        ctx,
        |s| s["typeValue"].as_str() == Some("hello batch") && s["clickCount"].as_u64() == Some(1),
        5_000,
        "Expected batch commands to set typeValue to 'hello batch' and clickCount to 1",
    );

    for (key, expected_value) in [
        ("!", "hello batch!"),
        ("?", "hello batch!?"),
        (":", "hello batch!?:"),
        ("+", "hello batch!?:+"),
        (")", "hello batch!?:+)"),
    ] {
        let batch_press_before = read_interactive_state(ctx);
        let batch_press_before_events = key_event_count(&batch_press_before);
        run_command_with_stdin(
            ctx,
            &["batch", "--json"],
            &format!(
                r##"
[
  ["press", "{key}", "#type-target"]
]
"##,
                key = key,
            ),
        );
        wait_for_state_or_abort(
            ctx,
            |s| {
                s["typeValue"].as_str() == Some(expected_value)
                    && key_event_count(s) >= batch_press_before_events + 2
                    && s["keyEvents"]
                        .as_array()
                        .map(|events| {
                            let new_events: Vec<_> = events
                                .iter()
                                .skip(batch_press_before_events)
                                .filter_map(|event| event.as_str())
                                .collect();
                            new_events.contains(&format!("down:{key}").as_str())
                                && new_events.contains(&format!("up:{key}").as_str())
                        })
                        .unwrap_or(false)
            },
            5_000,
            &format!(
                "Expected JSON batch press to append '{key}' and emit down/up key events for '{key}'"
            ),
        );
    }

    let key_events_before = key_event_count(&read_interactive_state(ctx));
    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"
[
  ["fill", "#fill-target", "from json"],
  ["keydown", "Shift"],
  ["keyup", "Shift"]
]
"##,
    );
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["fillValue"].as_str() == Some("from json")
                && key_event_count(s) > key_events_before
                && s["keyEvents"]
                    .as_array()
                    .and_then(|events| events.last())
                    .and_then(|event| event.as_str())
                    == Some("up:Shift")
        },
        5_000,
        "Expected JSON batch to fill text and finish with an 'up:Shift' key event",
    );

    run_command(ctx, &["close"]);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        &format!(
            r##"
[
  ["goto", "{interactive_url}"],
  "type 'json string input' #type-target",
  ["fill", "#fill-target", "json opened session"],
  "click #click-target"
]
"##,
            interactive_url = interactive_url,
        ),
    );
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["typeValue"].as_str() == Some("json string input")
                && s["fillValue"].as_str() == Some("json opened session")
                && s["clickCount"].as_u64() == Some(1)
        },
        5_000,
        "Expected full JSON batch input to open the page and execute string and array commands",
    );

    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"
[
  "fill #fill-target 'json string only'",
  "click #click-target"
]
"##,
    );
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["fillValue"].as_str() == Some("json string only")
                && s["clickCount"].as_u64() == Some(2)
        },
        5_000,
        "Expected JSON batch string entries to run against the active session",
    );

    let continue_failure = run_command_expecting_failure(
        ctx,
        &["batch", "not-a-command", "snapshot"],
        "1 batch command(s) failed.",
    );
    assert!(
        continue_failure.stdout.contains("[Snapshot]("),
        "Expected later batch command output after a non-bailing failure:\n{}",
        continue_failure.stdout
    );

    let bail_failure = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "--bail",
            "not-a-command",
            "fill #fill-target 'should not run'",
        ],
        "Batch command 1 failed",
    );
    assert!(
        !bail_failure.stdout.contains("should not run"),
        "Expected --bail to stop before the second command:\n{}",
        bail_failure.stdout
    );
    let fill_value = read_interactive_state(ctx)["fillValue"]
        .as_str()
        .unwrap_or_default()
        .to_string();
    assert_eq!(
        fill_value, "json string only",
        "Expected fill value unchanged after --bail failure"
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_batch_form_submission(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    let form_url = ctx.form_url();
    let navigate_command = batch_navigate_command(&form_url);

    run_command(
        ctx,
        &[
            "batch",
            navigate_command.as_str(),
            "fill #first-name 'Alice'",
            "fill #last-name 'Johnson'",
            "fill #email 'alice@example.com'",
            "select #country us",
            "check #agree-terms",
            "fill #comments 'batch test comment'",
            "click #submit-btn",
        ],
    );

    wait_for_state_or_abort(
        ctx,
        |s| {
            s["firstName"].as_str() == Some("Alice")
                && s["lastName"].as_str() == Some("Johnson")
                && s["email"].as_str() == Some("alice@example.com")
                && s["country"].as_str() == Some("us")
                && s["agreeTerms"].as_bool() == Some(true)
                && s["comments"].as_str() == Some("batch test comment")
                && s["submitCount"].as_u64() == Some(1)
                && s["validationError"].as_str() == Some("")
        },
        5_000,
        "Expected batch submission to populate the form and submit successfully for Alice",
    );

    let state = read_interactive_state(ctx);
    let submission = &state["lastSubmission"];
    assert_eq!(
        submission["firstName"].as_str(),
        Some("Alice"),
        "Expected firstName in submission"
    );
    assert_eq!(
        submission["email"].as_str(),
        Some("alice@example.com"),
        "Expected email in submission"
    );

    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"[
  ["click", "#reset-btn"],
  ["fill", "#first-name", "Bob"],
  ["fill", "#last-name", "Smith"],
  ["fill", "#email", "bob@example.com"],
  ["select", "#country", "uk"],
  ["check", "#agree-terms"],
  ["click", "#submit-btn"]
]"##,
    );

    wait_for_state_or_abort(
        ctx,
        |s| {
            s["submitCount"].as_u64() == Some(2)
                && s["resetCount"].as_u64() == Some(1)
                && s["firstName"].as_str() == Some("Bob")
        },
        5_000,
        "Expected JSON batch reset flow to submit Bob as the second submission",
    );

    let state = read_interactive_state(ctx);
    let submission = &state["lastSubmission"];
    assert_eq!(submission["firstName"].as_str(), Some("Bob"));
    assert_eq!(submission["lastName"].as_str(), Some("Smith"));
    assert_eq!(submission["country"].as_str(), Some("uk"));

    run_command(ctx, &["close"]);
}

pub(super) fn test_batch_form_submission_from_json_file(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    let form_input_path = write_json_fixture(
        ctx,
        "batch-form-input.json",
        &serde_json::json!({
            "firstName": "Carol",
            "lastName": "Nguyen",
            "email": "carol@example.com",
            "country": "jp",
            "agreeTerms": true,
            "comments": "loaded from json file"
        }),
    );
    let form_input_raw =
        fs::read_to_string(&form_input_path).expect("read batch form input JSON failed");
    let form_input: serde_json::Value =
        serde_json::from_str(&form_input_raw).expect("batch form input JSON is invalid");

    let first_name = form_input["firstName"]
        .as_str()
        .expect("batch form input must include firstName");
    let last_name = form_input["lastName"]
        .as_str()
        .expect("batch form input must include lastName");
    let email = form_input["email"]
        .as_str()
        .expect("batch form input must include email");
    let country = form_input["country"]
        .as_str()
        .expect("batch form input must include country");
    let comments = form_input["comments"]
        .as_str()
        .expect("batch form input must include comments");
    let agree_terms = form_input["agreeTerms"]
        .as_bool()
        .expect("batch form input must include agreeTerms");
    assert!(
        agree_terms,
        "Expected batch form input JSON to enable the agreeTerms checkbox"
    );

    let form_url = ctx.form_url();
    let batch_commands = serde_json::json!([
        ["goto", form_url],
        ["fill", "#first-name", first_name],
        ["fill", "#last-name", last_name],
        ["fill", "#email", email],
        ["select", "#country", country],
        ["check", "#agree-terms"],
        ["fill", "#comments", comments],
        ["click", "#submit-btn"]
    ]);
    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        &serde_json::to_string_pretty(&batch_commands)
            .expect("serialize batch commands from JSON fixture failed"),
    );

    wait_for_state_or_abort(
        ctx,
        |s| {
            s["firstName"].as_str() == Some(first_name)
                && s["lastName"].as_str() == Some(last_name)
                && s["email"].as_str() == Some(email)
                && s["country"].as_str() == Some(country)
                && s["agreeTerms"].as_bool() == Some(true)
                && s["comments"].as_str() == Some(comments)
                && s["submitCount"].as_u64() == Some(1)
                && s["validationError"].as_str() == Some("")
        },
        5_000,
        "Expected JSON file-backed batch submission to populate the form and submit successfully",
    );

    let state = read_interactive_state(ctx);
    let submission = &state["lastSubmission"];
    assert_eq!(submission["firstName"].as_str(), Some(first_name));
    assert_eq!(submission["lastName"].as_str(), Some(last_name));
    assert_eq!(submission["email"].as_str(), Some(email));
    assert_eq!(submission["country"].as_str(), Some(country));
    assert_eq!(submission["comments"].as_str(), Some(comments));

    run_command(ctx, &["close"]);
}

pub(super) fn test_batch_multi_interaction(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    let interactive_url = ctx.interactive_url();
    let navigate_command = batch_navigate_command(&interactive_url);

    run_command(
        ctx,
        &[
            "batch",
            navigate_command.as_str(),
            "type 'batch multi' #type-target",
            "fill #fill-target 'batch fill'",
            "check #check-target",
            "select #select-target blue",
            "click #click-target",
            "click #click-target",
        ],
    );

    wait_for_state_or_abort(
        ctx,
        |s| {
            s["typeValue"].as_str() == Some("batch multi")
                && s["fillValue"].as_str() == Some("batch fill")
                && s["checkbox"].as_bool() == Some(true)
                && s["selectValue"].as_str() == Some("blue")
                && s["clickCount"].as_u64() == Some(2)
        },
        5_000,
        "Expected multi-interaction batch to update text, fill, checkbox, select, and clickCount",
    );

    let batch_result = run_command(
        ctx,
        &[
            "batch",
            "snapshot --filename=batch-snap.yml",
            "screenshot --filename=batch-screen.png",
        ],
    );

    let snapshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("batch-snap.yml");
    assert!(
        snapshot_path.exists(),
        "Batch snapshot file not found at {snapshot_path:?}"
    );

    let screenshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("batch-screen.png");
    assert!(
        screenshot_path.exists(),
        "Batch screenshot file not found at {screenshot_path:?}"
    );
    assert!(
        fs::metadata(&screenshot_path).unwrap().len() > 0,
        "Batch screenshot file is empty"
    );

    assert!(
        batch_result.stdout.contains("[Snapshot]("),
        "Expected snapshot link in batch output:\n{}",
        batch_result.stdout
    );

    run_command(ctx, &["batch", "uncheck #check-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["checkbox"].as_bool() == Some(false),
        5_000,
        "Expected batch uncheck to clear the checkbox",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_batch_error_handling(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    let interactive_url = ctx.interactive_url();
    let navigate_command = batch_navigate_command(&interactive_url);

    run_command(ctx, &["batch", navigate_command.as_str()]);

    let _result = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "type 'before error' #type-target",
            "this-is-not-a-valid-command",
            "fill #fill-target 'after error'",
        ],
        "1 batch command(s) failed.",
    );
    wait_for_state_or_abort(
        ctx,
        |s| s["fillValue"].as_str() == Some("after error"),
        5_000,
        "Expected fill command after a non-bailing batch error to still run",
    );

    let _bail_result = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "--bail",
            "type 'bail test' #type-target",
            "unknown-command-xyz",
            "fill #fill-target 'should not execute'",
        ],
        "Batch command 2 failed",
    );
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["typeValue"]
                .as_str()
                .map(|v| v.contains("bail test"))
                .unwrap_or(false)
        },
        5_000,
        "Expected typeValue to contain 'bail test' after the pre-error batch command",
    );
    let fill_value = read_interactive_state(ctx)["fillValue"]
        .as_str()
        .unwrap_or_default()
        .to_string();
    assert_eq!(
        fill_value, "after error",
        "Expected fill value unchanged after bail"
    );

    let _multi_error = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "bad-cmd-1",
            "bad-cmd-2",
            "type 'still works' #type-target",
        ],
        "2 batch command(s) failed.",
    );
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["typeValue"]
                .as_str()
                .map(|v| v.contains("still works"))
                .unwrap_or(false)
        },
        5_000,
        "Expected typeValue to contain 'still works' despite earlier batch errors",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_batch_json_edge_cases(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    let interactive_url = ctx.interactive_url();
    let navigate_command = batch_navigate_command(&interactive_url);

    run_command(ctx, &["batch", navigate_command.as_str()]);

    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"[
  "type 'json mixed' #type-target",
  ["fill", "#fill-target", "json array fill"],
  "click #click-target"
]"##,
    );
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["typeValue"].as_str() == Some("json mixed")
                && s["fillValue"].as_str() == Some("json array fill")
                && s["clickCount"].as_u64() == Some(1)
        },
        5_000,
        "Expected mixed JSON batch commands to type, fill, and click once",
    );

    run_command(ctx, &["batch", navigate_command.as_str()]);

    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"[
  ["fill", "#fill-target", "special: @#$%&*"]
]"##,
    );
    wait_for_state_or_abort(
        ctx,
        |s| s["fillValue"].as_str() == Some("special: @#$%&*"),
        5_000,
        "Expected JSON batch to preserve special characters in fillValue",
    );

    run_command(ctx, &["batch", navigate_command.as_str()]);

    let bail_json_result = run_cli_process_with_stdin(
        ctx,
        &["batch", "--bail", "--json"],
        Some(r##"["unknown-cmd", "fill #fill-target 'unreachable'"]"##),
    );
    assert_ne!(
        bail_json_result.exit_code, 0,
        "Expected batch --bail --json with unknown command to fail"
    );
    let combined = format!("{}\n{}", bail_json_result.stdout, bail_json_result.stderr);
    assert!(
        !combined.contains("unreachable"),
        "Expected --bail to prevent 'unreachable' command from running:\n{combined}"
    );

    run_command(ctx, &["close"]);
}
