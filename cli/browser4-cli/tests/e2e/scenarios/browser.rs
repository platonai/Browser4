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

    let close_without_session_result = run_command(ctx, &["close"]);
    let combined_output = format!(
        "{}\n{}",
        close_without_session_result.stdout, close_without_session_result.stderr
    );
    assert!(
        combined_output.contains("🔐 Session required"),
        "Expected missing-session close message in output:\n{}",
        combined_output
    );
    assert!(
        combined_output.contains("run `browser4-cli open <url>` first."),
        "Expected missing-session guidance in output:\n{}",
        combined_output
    );
    assert!(
        !combined_output.contains("Error:"),
        "Close without a session should not bubble up as a process error:\n{}",
        combined_output
    );
}

pub(super) fn test_newly_opened_session_shows_active(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let open_result = run_open_command(ctx);
    assert!(
        open_result.stdout.contains("Session opened:"),
        "Expected 'Session opened:' in:\n{}",
        open_result.stdout
    );

    let session_id = read_persisted_session_id(&ctx.state_dir);
    let list_result = run_command(ctx, &["list"]);
    let list_output = strip_snapshot_output(&list_result.stdout);
    let session_line = list_output
        .lines()
        .find(|line| line.contains(&session_id))
        .unwrap_or_else(|| {
            panic!(
                "Expected session id '{session_id}' in list output:\n{}",
                list_output
            )
        });

    assert!(
        session_line.contains("Active"),
        "Expected session line to contain 'Active':\n{}",
        session_line
    );
    assert!(
        !session_line.contains("Stale"),
        "Expected session line not to contain 'Stale':\n{}",
        session_line
    );
    assert!(
        session_line.contains("Reuse"),
        "Expected session line to show that open will reuse the active session:\n{}",
        session_line
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_open_recovery_after_browser_kill(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // 1. Open a session with a URL (navigates immediately)
    let interactive_url = ctx.interactive_url();
    let open_result = run_command(
        ctx,
        &["open", &interactive_url, OPEN_PROFILE_MODE_ARG],
    );
    assert!(
        open_result.stdout.contains("Session opened:"),
        "Expected session to open with a URL. Output:\n{}",
        open_result.stdout
    );
    // Give the browser a moment to finish launching.
    sleep(Duration::from_secs(2));

    // Verify the page loaded correctly.
    let title = eval_text(ctx, "document.title");
    assert_eq!(title.trim(), INTERACTIVE_TITLE);

    // 2. Kill browser processes (not the server).
    let kill_result = browser4_cli::managed_processes::kill_all_browsers();
    if !kill_result.killed_pids.is_empty() {
        eprintln!(
            "[test_open_recovery_after_browser_kill] killed browser PIDs: {:?}",
            kill_result.killed_pids
        );
    }
    // Give the server a moment to detect the browser disconnection.
    sleep(Duration::from_millis(1500));

    // 3. Goto another URL — should re-launch the browser via stale-session recovery.
    let other_url = ctx.other_url();
    let goto_result = run_command(ctx, &["goto", &other_url]);
    assert!(
        !goto_result.stdout.is_empty(),
        "Expected goto to produce page output after browser-kill recovery (got empty stdout)"
    );

    // Verify we landed on the target page.
    wait_for_eval_text(
        ctx,
        "document.title",
        OTHER_TITLE,
        10_000,
        "Expected goto to navigate to the other page after browser recovery",
    );

    // 4. Server should still be UP (only browsers were killed, not the server).
    let status_result = run_command(ctx, &["status"]);
    assert!(
        status_result.stdout.contains("Server health: UP"),
        "Expected 'Server health: UP' after browser kill. Status output:\n{}",
        status_result.stdout
    );

    // 5. The recovered session should show as Active in list output.
    let list_result = run_command(ctx, &["list"]);
    assert!(
        list_result.stdout.contains("Active"),
        "Expected session to be 'Active' in list output after goto recovery:\n{}",
        list_result.stdout
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_navigation_and_storage(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    let interactive_url = ctx.interactive_url();
    let other_url = ctx.other_url();

    run_command(ctx, &["goto", &interactive_url]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        INTERACTIVE_PATH,
        2_000,
        "Expected to be on interactive path",
    );

    run_command(ctx, &["goto", &other_url]);
    wait_for_eval_text(
        ctx,
        "document.title",
        OTHER_TITLE,
        2_000,
        "Expected other page title",
    );

    run_command(ctx, &["go-back"]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        INTERACTIVE_PATH,
        2_000,
        "Expected to be back on interactive path after go-back",
    );

    run_command(ctx, &["go-forward"]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        OTHER_PATH,
        2_000,
        "Expected to be on other path after go-forward",
    );

    run_command(ctx, &["reload"]);
    wait_for_eval_text(
        ctx,
        "document.title",
        OTHER_TITLE,
        2_000,
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

pub(super) fn test_storage_state_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let interactive_url = ctx.interactive_url();
    let storage_state_path = ctx.workspace_dir.join("auth-state.json");
    let storage_state_path_text = storage_state_path.to_string_lossy().into_owned();
    let initial_state = serde_json::json!({
        "cookies": [
            {
                "name": "restoredCookie",
                "value": "restored-value",
                "url": interactive_url,
            }
        ],
        "origins": [
            {
                "origin": ctx.fixture_base_url,
                "localStorage": [
                    { "name": "restoredKey", "value": "restored-value" }
                ]
            }
        ]
    });
    fs::write(
        &storage_state_path,
        serde_json::to_string_pretty(&initial_state).unwrap(),
    )
    .unwrap();

    let load_without_session = run_command(ctx, &["state-load", &storage_state_path_text]);
    assert!(
        load_without_session
            .stdout
            .contains("Storage state loaded:"),
        "Expected storage-state load output:\n{}",
        load_without_session.stdout
    );

    run_command(ctx, &["goto", &interactive_url]);
    wait_for_eval_text(
        ctx,
        "document.cookie.includes('restoredCookie=restored-value').toString()",
        "true",
        2_000,
        "Expected state-load to restore cookies into a fresh session",
    );
    wait_for_eval_text(
        ctx,
        "window.localStorage.getItem('restoredKey') ?? ''",
        "restored-value",
        2_000,
        "Expected state-load to restore localStorage into a fresh session",
    );

    let cookie_set_result = run_command(ctx, &["cookie-set", "session_id", "abc123", "--path=/"]);
    assert!(
        cookie_set_result.stdout.contains("Cookie set: session_id"),
        "Expected cookie-set output:\n{}",
        cookie_set_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "document.cookie.includes('session_id=abc123').toString()",
        "true",
        2_000,
        "Expected cookie-set to restore the session_id cookie",
    );

    let cookie_get_result = run_command(ctx, &["cookie-get", "session_id"]);
    let cookie_get: serde_json::Value = serde_json::from_str(cookie_get_result.stdout.trim())
        .expect("cookie-get should return JSON");
    assert_eq!(cookie_get["name"].as_str(), Some("session_id"));
    assert_eq!(cookie_get["value"].as_str(), Some("abc123"));

    let cookie_list_result = run_command(ctx, &["cookie-list"]);
    let cookie_list: serde_json::Value = serde_json::from_str(cookie_list_result.stdout.trim())
        .expect("cookie-list should return a JSON array");
    let cookie_domain = cookie_list
        .as_array()
        .and_then(|cookies| {
            cookies.iter().find_map(|cookie| {
                (cookie["name"].as_str() == Some("session_id"))
                    .then(|| cookie["domain"].as_str().map(str::to_string))
                    .flatten()
            })
        })
        .expect("cookie-list should include session_id with a domain");
    assert!(
        cookie_list.as_array().is_some_and(|cookies| {
            cookies
                .iter()
                .any(|cookie| cookie["name"].as_str() == Some("session_id"))
        }),
        "Expected cookie-list to contain session_id:\n{}",
        cookie_list
    );

    let cookie_list_domain_result =
        run_command(ctx, &["cookie-list", &format!("--domain={cookie_domain}")]);
    let cookie_list_by_domain: serde_json::Value =
        serde_json::from_str(cookie_list_domain_result.stdout.trim())
            .expect("cookie-list --domain should return a JSON array");
    assert!(
        cookie_list_by_domain.as_array().is_some_and(|cookies| {
            cookies
                .iter()
                .any(|cookie| cookie["name"].as_str() == Some("session_id"))
        }),
        "Expected cookie-list --domain to contain session_id:\n{}",
        cookie_list_by_domain
    );

    let cookie_list_path_result = run_command(ctx, &["cookie-list", "--path=/"]);
    let cookie_list_by_path: serde_json::Value =
        serde_json::from_str(cookie_list_path_result.stdout.trim())
            .expect("cookie-list --path should return a JSON array");
    assert!(
        cookie_list_by_path.as_array().is_some_and(|cookies| {
            cookies
                .iter()
                .any(|cookie| cookie["name"].as_str() == Some("session_id"))
        }),
        "Expected cookie-list --path to contain session_id:\n{}",
        cookie_list_by_path
    );

    let cookie_delete_result = run_command(ctx, &["cookie-delete", "session_id"]);
    assert!(
        cookie_delete_result
            .stdout
            .contains("Cookie deleted: session_id"),
        "Expected cookie-delete output:\n{}",
        cookie_delete_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "document.cookie.includes('session_id=abc123').toString()",
        "false",
        2_000,
        "Expected cookie-delete to remove the session_id cookie",
    );

    run_command(ctx, &["cookie-set", "clear_one", "1", "--path=/"]);
    run_command(ctx, &["cookie-set", "clear_two", "2", "--path=/"]);
    let cookie_clear_result = run_command(ctx, &["cookie-clear"]);
    assert!(
        cookie_clear_result.stdout.contains("Cookies cleared."),
        "Expected cookie-clear output:\n{}",
        cookie_clear_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "document.cookie.includes('clear_one=1').toString()",
        "false",
        2_000,
        "Expected cookie-clear to remove clear_one",
    );
    wait_for_eval_text(
        ctx,
        "document.cookie.includes('clear_two=2').toString()",
        "false",
        2_000,
        "Expected cookie-clear to remove clear_two",
    );

    let localstorage_set_result = run_command(ctx, &["localstorage-set", "theme", "dark"]);
    assert!(
        localstorage_set_result
            .stdout
            .contains("localStorage key set: theme"),
        "Expected localstorage-set output:\n{}",
        localstorage_set_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "window.localStorage.getItem('theme') ?? ''",
        "dark",
        2_000,
        "Expected localstorage-set to persist the theme key",
    );

    let localstorage_get_result = run_command(ctx, &["localstorage-get", "theme"]);
    assert_eq!(localstorage_get_result.stdout.trim(), "dark");

    run_command(
        ctx,
        &[
            "localstorage-set",
            "user_settings",
            "{\"theme\":\"dark\",\"language\":\"en\"}",
        ],
    );
    let localstorage_list_result = run_command(ctx, &["localstorage-list"]);
    let localstorage_list: serde_json::Value =
        serde_json::from_str(localstorage_list_result.stdout.trim())
            .expect("localstorage-list should return a JSON array");
    assert!(
        localstorage_list.as_array().is_some_and(|entries| {
            entries.iter().any(|entry| {
                entry["name"].as_str() == Some("theme") && entry["value"].as_str() == Some("dark")
            }) && entries.iter().any(|entry| {
                entry["name"].as_str() == Some("user_settings")
                    && entry["value"].as_str() == Some("{\"theme\":\"dark\",\"language\":\"en\"}")
            })
        }),
        "Expected localstorage-list to include theme and user_settings:\n{}",
        localstorage_list
    );

    let localstorage_delete_result = run_command(ctx, &["localstorage-delete", "theme"]);
    assert!(
        localstorage_delete_result
            .stdout
            .contains("localStorage key deleted: theme"),
        "Expected localstorage-delete output:\n{}",
        localstorage_delete_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "window.localStorage.getItem('theme') ?? ''",
        "",
        2_000,
        "Expected localstorage-delete to remove theme",
    );

    let localstorage_clear_result = run_command(ctx, &["localstorage-clear"]);
    assert!(
        localstorage_clear_result
            .stdout
            .contains("localStorage cleared:"),
        "Expected localstorage-clear output:\n{}",
        localstorage_clear_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "window.localStorage.length.toString()",
        "0",
        2_000,
        "Expected localstorage-clear to clear all entries",
    );

    let sessionstorage_set_result = run_command(ctx, &["sessionstorage-set", "step", "3"]);
    assert!(
        sessionstorage_set_result
            .stdout
            .contains("sessionStorage key set: step"),
        "Expected sessionstorage-set output:\n{}",
        sessionstorage_set_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "window.sessionStorage.getItem('step') ?? ''",
        "3",
        2_000,
        "Expected sessionstorage-set to persist the step key",
    );

    let sessionstorage_get_result = run_command(ctx, &["sessionstorage-get", "step"]);
    assert_eq!(sessionstorage_get_result.stdout.trim(), "3");

    run_command(
        ctx,
        &["sessionstorage-set", "form_data", "{\"name\":\"Ada\"}"],
    );
    let sessionstorage_list_result = run_command(ctx, &["sessionstorage-list"]);
    let sessionstorage_list: serde_json::Value =
        serde_json::from_str(sessionstorage_list_result.stdout.trim())
            .expect("sessionstorage-list should return a JSON array");
    assert!(
        sessionstorage_list.as_array().is_some_and(|entries| {
            entries.iter().any(|entry| {
                entry["name"].as_str() == Some("step") && entry["value"].as_str() == Some("3")
            }) && entries.iter().any(|entry| {
                entry["name"].as_str() == Some("form_data")
                    && entry["value"].as_str() == Some("{\"name\":\"Ada\"}")
            })
        }),
        "Expected sessionstorage-list to include step and form_data:\n{}",
        sessionstorage_list
    );

    let sessionstorage_delete_result = run_command(ctx, &["sessionstorage-delete", "step"]);
    assert!(
        sessionstorage_delete_result
            .stdout
            .contains("sessionStorage key deleted: step"),
        "Expected sessionstorage-delete output:\n{}",
        sessionstorage_delete_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "window.sessionStorage.getItem('step') ?? ''",
        "",
        2_000,
        "Expected sessionstorage-delete to remove step",
    );

    let sessionstorage_clear_result = run_command(ctx, &["sessionstorage-clear"]);
    assert!(
        sessionstorage_clear_result
            .stdout
            .contains("sessionStorage cleared:"),
        "Expected sessionstorage-clear output:\n{}",
        sessionstorage_clear_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "window.sessionStorage.length.toString()",
        "0",
        2_000,
        "Expected sessionstorage-clear to clear all entries",
    );

    run_command(
        ctx,
        &[
            "eval",
            "(() => { document.cookie = 'savedCookie=saved-value; path=/'; window.localStorage.setItem('savedKey', 'saved-value'); return 'ok'; })()",
        ],
    );

    let save_result = run_command(ctx, &["state-save", &storage_state_path_text]);
    assert!(
        save_result.stdout.contains("Storage state saved:"),
        "Expected storage-state save output:\n{}",
        save_result.stdout
    );

    let saved_state: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&storage_state_path).unwrap()).unwrap();
    assert!(
        saved_state["cookies"]
            .as_array()
            .is_some_and(|cookies| cookies.iter().any(|cookie| {
                cookie["name"].as_str() == Some("savedCookie")
                    && cookie["value"].as_str() == Some("saved-value")
            })),
        "Expected saved state file to contain savedCookie:\n{}",
        saved_state
    );
    assert!(
        saved_state["origins"]
            .as_array()
            .is_some_and(|origins| origins.iter().any(|origin| {
                origin["origin"].as_str() == Some(&ctx.fixture_base_url)
                    && origin["localStorage"].as_array().is_some_and(|entries| {
                        entries.iter().any(|entry| {
                            entry["name"].as_str() == Some("savedKey")
                                && entry["value"].as_str() == Some("saved-value")
                        })
                    })
            })),
        "Expected saved state file to contain savedKey localStorage entry:\n{}",
        saved_state
    );

    run_command(ctx, &["delete-data"]);
    wait_for_eval_text(
        ctx,
        "document.cookie.includes('savedCookie=saved-value').toString()",
        "false",
        2_000,
        "Expected delete-data to remove cookies before restoring state",
    );
    wait_for_eval_text(
        ctx,
        "window.localStorage.getItem('savedKey') ?? ''",
        "",
        2_000,
        "Expected delete-data to clear localStorage before restoring state",
    );

    let load_result = run_command(ctx, &["state-load", &storage_state_path_text]);
    assert!(
        load_result.stdout.contains("Storage state loaded:"),
        "Expected storage-state reload output:\n{}",
        load_result.stdout
    );
    wait_for_eval_text(
        ctx,
        "document.cookie.includes('savedCookie=saved-value').toString()",
        "true",
        2_000,
        "Expected state-load to restore saved cookies",
    );
    wait_for_eval_text(
        ctx,
        "window.localStorage.getItem('savedKey') ?? ''",
        "saved-value",
        2_000,
        "Expected state-load to restore saved localStorage",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_interaction_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["type", "hello world", "#type-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["typeValue"].as_str() == Some("hello world"),
        2_000,
        "Expected typeValue to become 'hello world' after type",
    );

    run_command(ctx, &["fill", "#fill-target", "filled text"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["fillValue"].as_str() == Some("filled text"),
        2_000,
        "Expected fillValue to become 'filled text' after fill",
    );

    for (key, expected_value) in [
        ("!", "hello world!"),
        ("?", "hello world!?"),
        (":", "hello world!?:"),
        ("+", "hello world!?:+"),
        (")", "hello world!?:+)"),
    ] {
        let press_before = read_interactive_state(ctx);
        let press_before_events = key_event_count(&press_before);
        run_command(ctx, &["press", key, "#type-target"]);
        wait_for_state_or_abort(
            ctx,
            |s| {
                s["typeValue"].as_str() == Some(expected_value)
                    && key_event_count(s) >= press_before_events + 2
                    && s["keyEvents"]
                        .as_array()
                        .map(|events| {
                            let new_events: Vec<_> = events
                                .iter()
                                .skip(press_before_events)
                                .filter_map(|event| event.as_str())
                                .collect();
                            new_events.contains(&format!("down:{key}").as_str())
                                && new_events.contains(&format!("up:{key}").as_str())
                        })
                        .unwrap_or(false)
            },
            2_000,
            &format!(
                "Expected press to append '{key}' to typeValue and emit down/up key events for '{key}'"
            ),
        );
    }

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
        2_000,
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
        2_000,
        "Expected keyup to record a final 'up:Shift' key event",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_pointer_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["click", "#click-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["clickCount"].as_u64() == Some(1),
        2_000,
        "Expected clickCount to become 1 after click",
    );

    run_command(ctx, &["dblclick", "#dblclick-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["doubleClickCount"].as_u64() == Some(1),
        2_000,
        "Expected doubleClickCount to become 1 after dblclick",
    );

    run_command(ctx, &["hover", "#hover-target"]);
    assume_wait_for_state(
        ctx,
        |s| s["hovered"].as_bool() == Some(true),
        2_000,
        "Expected hovered to become true after hover",
    );

    run_command(ctx, &["drag", "#drag-source", "#drag-target"]);
    assume_wait_for_state(
        ctx,
        |s| {
            s["dragStarted"].as_bool() == Some(true)
                && s["dragDropped"].as_str() == Some("drag-source")
        },
        2_000,
        "Expected drag to start and drop drag-source onto the target",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_eval_command(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    open_resized_interactive_page(ctx);

    let title = eval_text(ctx, "document.title");
    assert_eq!(title.trim(), INTERACTIVE_TITLE);

    let button_text = eval_text_for_target(
        ctx,
        "element => element.textContent.trim()",
        "#click-target",
    );
    assert_eq!(button_text.trim(), "Click");

    let state_text = eval_text(
        ctx,
        "(() => { document.getElementById('click-target').click(); return document.getElementById('state-log').textContent; })()",
    );
    let state: serde_json::Value =
        serde_json::from_str(state_text.trim()).expect("eval should return JSON state text");
    assert_eq!(state["clickCount"].as_u64(), Some(1));

    run_command(ctx, &["close"]);
}

pub(super) fn test_eval_return_types(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    open_resized_interactive_page(ctx);

    // --- number ---
    let num = eval_text(ctx, "40 + 2");
    // Server returns numbers as their decimal string representation.
    let parsed_num: i64 = num
        .trim()
        .parse()
        .unwrap_or_else(|_| panic!("Expected numeric result, got: {num}"));
    assert_eq!(parsed_num, 42);

    // --- boolean ---
    let bool_val = eval_text(ctx, "true");
    assert_eq!(bool_val.trim(), "true", "Expected boolean true, got: {bool_val}");

    let bool_false = eval_text(ctx, "1 > 2");
    assert_eq!(bool_false.trim(), "false", "Expected boolean false, got: {bool_false}");

    // --- array (JSON) ---
    let arr = eval_text(ctx, "[1, 2, 3]");
    let parsed_arr: serde_json::Value = serde_json::from_str(arr.trim())
        .unwrap_or_else(|_| panic!("Expected valid JSON array, got: {arr}"));
    assert!(parsed_arr.is_array(), "Expected JSON array, got: {arr}");
    assert_eq!(parsed_arr.as_array().unwrap().len(), 3);

    // --- object ---
    // The server may return either valid JSON ({"answer":42,"name":"eval-test"})
    // or a non-JSON representation ({answer=42, name=eval-test}) depending on
    // how the browser serializes JavaScript objects.
    //
    // TODO(server): The Browser4 server should consistently return valid JSON
    // for JavaScript objects evaluated via browser_evaluate.  Currently it
    // sometimes emits a Java-style Map.toString() representation (keys
    // unquoted, = instead of :).  Once fixed server-side, this test should
    // revert to requiring strict JSON parsing.
    let obj = eval_text(ctx, "({answer: 42, name: 'eval-test'})");
    let trimmed_obj = obj.trim();
    // Try JSON first; if that fails, verify the result contains the expected values.
    if let Ok(parsed_obj) = serde_json::from_str::<serde_json::Value>(trimmed_obj) {
        assert_eq!(parsed_obj["answer"].as_i64(), Some(42));
        assert_eq!(parsed_obj["name"].as_str(), Some("eval-test"));
    } else {
        assert!(
            trimmed_obj.contains("42") && trimmed_obj.contains("eval-test"),
            "Expected object result to contain 42 and eval-test, got: {obj}"
        );
    }

    // --- null ---
    // The server may return "null" or an empty string for JavaScript null.
    let null_val = eval_text(ctx, "null");
    let trimmed_null = null_val.trim();
    assert!(
        trimmed_null == "null" || trimmed_null.is_empty(),
        "Expected 'null' or empty string for `null`, got: {null_val}"
    );

    // --- undefined ---
    // `void 0` evaluates to undefined in JS. Some servers serialize
    // undefined as the empty string, others as "undefined".
    let undef_val = eval_text(ctx, "void 0");
    // Accept either "undefined" or "" as valid serializations.
    let trimmed = undef_val.trim();
    assert!(
        trimmed == "undefined" || trimmed.is_empty(),
        "Expected undefined or empty string for `void 0`, got: {undef_val}"
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_eval_css_selector_scoping(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    open_resized_interactive_page(ctx);

    // Eval scoped to an element via id selector.
    let button_tag = eval_text_for_target(
        ctx,
        "element => element.tagName",
        "#click-target",
    );
    assert_eq!(button_tag.trim().to_uppercase(), "BUTTON");

    // Eval scoped to an input element via id selector.
    let input_tag = eval_text_for_target(
        ctx,
        "element => element.tagName",
        "#type-target",
    );
    assert_eq!(input_tag.trim().to_uppercase(), "INPUT");

    // Eval scoped to a <select> element.
    let select_tag = eval_text_for_target(
        ctx,
        "element => element.tagName",
        "#select-target",
    );
    assert_eq!(select_tag.trim().to_uppercase(), "SELECT");

    // Eval that reads the element's own attribute.
    let checkbox_type = eval_text_for_target(
        ctx,
        "element => element.getAttribute('type')",
        "#check-target",
    );
    assert_eq!(checkbox_type.trim(), "checkbox");

    // Eval without a ref at page scope should still work independently.
    let title = eval_text(ctx, "document.title");
    assert_eq!(title.trim(), INTERACTIVE_TITLE);

    run_command(ctx, &["close"]);
}

pub(super) fn test_wait_for_state_failure_modes(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    goto_interactive_page(ctx);

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
        2_000,
        "Expected scenario to continue after ReturnError mode and update fillValue",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_form_controls_and_exports(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    goto_interactive_page(ctx);

    run_command(ctx, &["select", "#select-target", "green"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["selectValue"].as_str() == Some("green"),
        2_000,
        "Expected selectValue to become 'green' after select",
    );

    run_command(ctx, &["check", "#check-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["checkbox"].as_bool() == Some(true),
        2_000,
        "Expected checkbox to become true after check",
    );

    run_command(ctx, &["uncheck", "#check-target"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["checkbox"].as_bool() == Some(false),
        2_000,
        "Expected checkbox to become false after uncheck",
    );

    let upload_path = ctx.upload_file_path.to_string_lossy().into_owned();
    run_command(ctx, &["upload", "#file-input", &upload_path]);
    wait_for_state_or_abort(
        ctx,
        |s| s["uploadName"].as_str() == Some("upload.txt"),
        2_000,
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
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["mousemove", "120", "120"]);
    wait_for_state_or_abort(
        ctx,
        |s| s["lastMouse"][0].as_i64() == Some(120) && s["lastMouse"][1].as_i64() == Some(120),
        2_000,
        "Expected mousemove to update lastMouse to [120, 120]",
    );

    let before_mouse_down = read_interactive_state(ctx)["mouseDownCount"]
        .as_u64()
        .unwrap_or(0);
    run_command(ctx, &["mousedown", "left"]);
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["mouseDownCount"]
                .as_u64()
                .is_some_and(|value| value >= before_mouse_down + 1)
        },
        2_000,
        "Expected mousedown to increment mouseDownCount",
    );

    let before_mouse_up = read_interactive_state(ctx)["mouseUpCount"]
        .as_u64()
        .unwrap_or(0);
    run_command(ctx, &["mouseup", "left"]);
    wait_for_state_or_abort(
        ctx,
        |s| {
            s["mouseUpCount"]
                .as_u64()
                .is_some_and(|value| value >= before_mouse_up + 1)
        },
        2_000,
        "Expected mouseup to increment mouseUpCount",
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
        2_000,
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
        2_000,
        "Expected dialog-dismiss to set confirmResult to 'dismissed'",
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_mousewheel(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["mousewheel", "160", "0"]);
    let wheel_state = wait_for_state_or_abort(
        ctx,
        |s| s["lastWheel"][0].as_i64() == Some(160) && s["lastWheel"][1].as_i64() == Some(0),
        2_000,
        "Expected mousewheel to update lastWheel to [160, 0]",
    );
    assert!(
        wheel_state["lastWheel"][0].as_i64() == Some(160)
            && wheel_state["lastWheel"][1].as_i64() == Some(0),
        "Expected lastWheel to equal [160, 0], got {wheel_state:#?}"
    );

    run_command(ctx, &["close"]);
}

pub(super) fn test_tab_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    run_command(ctx, &["open", &ctx.interactive_url(), OPEN_PROFILE_MODE_ARG]);

    goto_interactive_page(ctx);
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

    let other_tab_index = extract_tab_index(&tab_output, &other_url).to_string();

    run_command(ctx, &["tab-select", &other_tab_index]);
    let current_url = eval_text(ctx, "document.location.href");
    assert!(
        current_url.contains(&other_url),
        "Expected tab-select {other_tab_index} to activate '{other_url}', got:\n{current_url}"
    );

    run_command(ctx, &["tab-close", &other_tab_index]);
    let final_tabs = run_command(ctx, &["tab-list"]);
    let final_tab_output = strip_snapshot_output(&final_tabs.stdout);
    assert!(
        !final_tab_output.contains(&other_url),
        "Expected tab-close {other_tab_index} to remove '{other_url}' from tab-list:\n{final_tab_output}"
    );
    run_command(ctx, &["close"]);
}
