use crate::*;

pub(super) fn test_session_lifecycle(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let open_result = run_open_command(ctx);
    assert!(
        open_result.stdout.contains("Session opened:"),
        "Expected 'Session opened:' in:\n{}",
        open_result.stdout
    );

    let session_id = read_persisted_session_id(&ctx.state_dir);
    let list_result = run_command(ctx, &["list"]);
    assert!(
        list_result.stdout.contains(&session_id),
        "Expected session id '{session_id}' in list output:\n{}",
        list_result.stdout
    );

    let close_result = run_command(ctx, &["close"]);
    assert!(
        close_result.stdout.contains("Session closed."),
        "Expected 'Session closed.' in:\n{}",
        close_result.stdout
    );
}

pub(super) fn test_navigation_and_storage(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    run_open_command(ctx);

    let interactive_url = ctx.interactive_url();
    let other_url = ctx.other_url();

    run_command(ctx, &["goto", &interactive_url]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        INTERACTIVE_PATH,
        5_000,
        "Expected to be on interactive path",
    );

    run_command(ctx, &["goto", &other_url]);
    wait_for_eval_text(
        ctx,
        "document.title",
        OTHER_TITLE,
        5_000,
        "Expected other page title",
    );

    run_command(ctx, &["go-back"]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        INTERACTIVE_PATH,
        5_000,
        "Expected to be back on interactive path after go-back",
    );

    run_command(ctx, &["go-forward"]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        OTHER_PATH,
        5_000,
        "Expected to be on other path after go-forward",
    );

    run_command(ctx, &["reload"]);
    wait_for_eval_text(
        ctx,
        "document.title",
        OTHER_TITLE,
        5_000,
        "Expected other page title after reload",
    );

    let delete_result = run_command(ctx, &["delete-data"]);
    let stripped = strip_snapshot_output(&delete_result.stdout).to_lowercase();
    assert!(
        stripped.contains("deleted"),
        "Expected 'deleted' in delete-data output:\n{}",
        stripped
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_interaction_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["type", "#type-target", "hello world"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["typeValue"].as_str() == Some("hello world"),
        5_000,
        "Expected typeValue to become 'hello world' after type",
    );

    run_command(ctx, &["fill", "#fill-target", "filled text"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["fillValue"].as_str() == Some("filled text"),
        5_000,
        "Expected fillValue to become 'filled text' after fill",
    );

    let press_before = read_interactive_state(ctx);
    let press_before_events = key_event_count(&press_before);
    run_command(ctx, &["press", "#type-target", "!"]);
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["typeValue"].as_str() == Some("hello world!")
                && key_event_count(s) > press_before_events
        },
        5_000,
        "Expected press to append '!' to typeValue and emit a key event",
    );

    run_command(ctx, &["click", "#type-target"]);
    let keydown_before = key_event_count(&read_interactive_state(ctx));
    run_command(ctx, &["keydown", "Shift"]);
    assume_wait_for_state(
        ctx,
        |s| {
            key_event_count(s) > keydown_before
                && s["keyEvents"]
                    .as_array()
                    .and_then(|events| events.last())
                    .and_then(|event| event.as_str())
                    == Some("down:Shift")
        },
        5_000,
        "Expected keydown to record a final 'down:Shift' key event",
    );

    let keyup_before = key_event_count(&read_interactive_state(ctx));
    run_command(ctx, &["keyup", "Shift"]);
    assume_wait_for_state(
        ctx,
        |s| {
            key_event_count(s) > keyup_before
                && s["keyEvents"]
                    .as_array()
                    .and_then(|events| events.last())
                    .and_then(|event| event.as_str())
                    == Some("up:Shift")
        },
        5_000,
        "Expected keyup to record a final 'up:Shift' key event",
    );

    run_command(ctx, &["click", "#click-target"]);
    assume_wait_for_state(
        ctx,
        |s| s["clickCount"].as_u64() == Some(1),
        5_000,
        "Expected clickCount to become 1 after click",
    );

    run_command(ctx, &["dblclick", "#dblclick-target"]);
    assume_wait_for_state(
        ctx,
        |s| s["doubleClickCount"].as_u64() == Some(1),
        5_000,
        "Expected doubleClickCount to become 1 after dblclick",
    );

    run_command(ctx, &["hover", "#hover-target"]);
    assume_wait_for_state(
        ctx,
        |s| s["hovered"].as_bool() == Some(true),
        5_000,
        "Expected hovered to become true after hover",
    );

    run_command(ctx, &["drag", "#drag-source", "#drag-target"]);
    assume_wait_for_state(
        ctx,
        |s| {
            s["dragStarted"].as_bool() == Some(true)
                && s["dragDropped"].as_str() == Some("drag-source")
        },
        5_000,
        "Expected drag to start and drop drag-source onto the target",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_wait_for_state_failure_modes(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    open_interactive_page(ctx);

    let error = wait_for_state(
        ctx,
        |s| s["clickCount"].as_u64() == Some(999),
        700,
        "Expected ReturnError mode to report a timeout without aborting the scenario",
        WaitForStateFailureMode::ReturnError,
    )
    .expect_err(
        "wait_for_state should return Err in ReturnError mode when the predicate never matches",
    );

    assert!(
        error.contains(
            "Expected ReturnError mode to report a timeout without aborting the scenario"
        ),
        "Expected original failure message in wait_for_state error:\n{}",
        error
    );
    assert!(
        error.contains("Timed out after 700ms waiting for interactive state"),
        "Expected timeout details in wait_for_state error:\n{}",
        error
    );
    assert!(
        error.contains("Last state:"),
        "Expected last state dump in wait_for_state error:\n{}",
        error
    );

    run_command(ctx, &["fill", "#fill-target", "continued after error"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["fillValue"].as_str() == Some("continued after error"),
        5_000,
        "Expected scenario to continue after ReturnError mode and update fillValue",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_form_controls_and_exports(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    open_interactive_page(ctx);

    run_command(ctx, &["select", "#select-target", "green"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["selectValue"].as_str() == Some("green"),
        5_000,
        "Expected selectValue to become 'green' after select",
    );

    run_command(ctx, &["check", "#check-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["checkbox"].as_bool() == Some(true),
        5_000,
        "Expected checkbox to become true after check",
    );

    run_command(ctx, &["uncheck", "#check-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["checkbox"].as_bool() == Some(false),
        5_000,
        "Expected checkbox to become false after uncheck",
    );

    let upload_path = ctx.upload_file_path.to_string_lossy().into_owned();
    run_command(ctx, &["upload", "#file-input", &upload_path]);
    wait_for_state_or_abort(
        ctx,
        |s| s["uploadName"].as_str() == Some("upload.txt"),
        5_000,
        "Expected uploadName to become 'upload.txt' after upload",
    );

    run_command_expecting_failure(
        ctx,
        &["console", "info"],
        "Unknown tool: browser_console_messages",
    );

    let snapshot_result = run_command(ctx, &["snapshot", "--filename=interactive.yml"]);
    assert!(
        snapshot_result.stdout.contains("[Snapshot]("),
        "Expected '[Snapshot](' in snapshot output:\n{}",
        snapshot_result.stdout
    );
    let snapshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("interactive.yml");
    assert!(
        snapshot_path.exists(),
        "Snapshot file not found at {snapshot_path:?}"
    );

    run_command(ctx, &["screenshot", "--filename=interactive.png"]);
    let screenshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("interactive.png");
    assert!(screenshot_path.exists(), "Screenshot file not found");
    assert!(
        fs::metadata(&screenshot_path).unwrap().len() > 0,
        "Screenshot file is empty"
    );

    run_command_expecting_failure(
        ctx,
        &["pdf", "--filename=interactive.pdf"],
        "Unknown tool: browser_pdf_save",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_mouse_and_dialog(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["mousemove", "120", "120"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["lastMouse"][0].as_i64() == Some(120) && s["lastMouse"][1].as_i64() == Some(120),
        5_000,
        "Expected mousemove to update lastMouse to [120, 120]",
    );

    let before_mouse_down = read_interactive_state(ctx)["mouseDownCount"]
        .as_u64()
        .unwrap_or(0);
    run_command(ctx, &["mousedown", "left"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["mouseDownCount"].as_u64() == Some(before_mouse_down + 1),
        5_000,
        "Expected mousedown to increment mouseDownCount",
    );

    let before_mouse_up = read_interactive_state(ctx)["mouseUpCount"]
        .as_u64()
        .unwrap_or(0);
    run_command(ctx, &["mouseup", "left"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["mouseUpCount"].as_u64() == Some(before_mouse_up + 1),
        5_000,
        "Expected mouseup to increment mouseUpCount",
    );

    run_command(ctx, &["mousewheel", "0", "160"]);
    let wheel_state = wait_for_state_or_abort(
        ctx,
        |s| s["lastWheel"][0].as_i64() == Some(160) && s["lastWheel"][1].as_i64() == Some(0),
        5_000,
        "Expected mousewheel to update lastWheel to [160, 0]",
    );
    assert!(
        wheel_state["lastWheel"][0].as_i64() == Some(160)
            && wheel_state["lastWheel"][1].as_i64() == Some(0),
        "Expected lastWheel to equal [160, 0], got {wheel_state:#?}"
    );

    eval_text(
        ctx,
        "(() => { setTimeout(() => document.getElementById('prompt-target').click(), 100); return 'scheduled'; })()",
    );
    thread::sleep(Duration::from_millis(500));
    run_command(ctx, &["dialog-accept", "accepted by cli"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["promptResult"].as_str() == Some("accepted by cli"),
        5_000,
        "Expected dialog-accept to set promptResult to 'accepted by cli'",
    );

    eval_text(
        ctx,
        "(() => { setTimeout(() => document.getElementById('confirm-target').click(), 100); return 'scheduled'; })()",
    );
    thread::sleep(Duration::from_millis(500));
    run_command(ctx, &["dialog-dismiss"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["confirmResult"].as_str() == Some("dismissed"),
        5_000,
        "Expected dialog-dismiss to set confirmResult to 'dismissed'",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_tab_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    open_interactive_page(ctx);
    let interactive_url = ctx.interactive_url();
    let other_url = ctx.other_url();

    let initial_tabs = run_command(ctx, &["tab-list"]);
    let tab_output = strip_snapshot_output(&initial_tabs.stdout);
    assert!(
        tab_output.contains(&interactive_url),
        "Expected interactive URL in tab-list output:\n{tab_output}"
    );

    run_command(ctx, &["tab-new", &other_url]);
    let updated_tabs = run_command(ctx, &["tab-list"]);
    let tab_output = strip_snapshot_output(&updated_tabs.stdout);
    assert!(
        tab_output.contains(&interactive_url),
        "Expected interactive URL in updated tab-list"
    );
    assert!(
        tab_output.contains(&other_url),
        "Expected other URL in updated tab-list"
    );

    let other_tab_id = extract_tab_id(&tab_output, &other_url);

    run_command(ctx, &["tab-select", &other_tab_id]);
    run_command(ctx, &["tab-close", &other_tab_id]);
    run_command(ctx, &["close"]);
}
