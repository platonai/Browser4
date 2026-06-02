//! Browser4 CLI — drive a Browser4 server from the command line.
//!
//! Most operations are routed through the Browser4 MCP Server tool interface
//! via `POST /mcp/call-tool`.
//! Swarm scrape submission/status/result flows also use the scrape REST
//! endpoints under `/api/x`.
//!
//! # State persistence
//! CLI state is persisted between invocations under `~/.browser4` by default.
//! The default session uses `~/.browser4/cli-state.json`; named sessions use
//! `~/.browser4/sessions/<name>.json`.
//!
//! # Element selectors
//! Use the short `e<N>` form from `snapshot` output; the CLI automatically
//! converts them to `backend:<N>` selectors expected by the server.

mod args;
mod commands;
mod daemon;
mod help;
mod http;
mod managed_processes;
mod snapshot;
mod state;

use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::PathBuf;

use base64::Engine;
use reqwest::Client;
use serde::{de::DeserializeOwned, Deserialize};
use serde_json::{json, Value};

use args::{
    build_command_args, parse_batch_args, parse_batch_json_commands, parse_command_string,
    parse_global_flags, parse_raw_args,
};
use commands::commands_map;
use daemon::{
    ensure_chrome_available, ensure_server_running, init_root_search_start_dir_from_startup,
    install_browser4_runtime, resolve_base_url, InstalledBrowser4Runtime,
};
use help::{generate_command_help, generate_help};
use http::{
    call_tool, get_command_result, get_command_status, get_swarm_result, get_swarm_status,
    is_stale_session_error, make_client, submit_batch_commands, submit_plain_command,
    submit_swarm_payload,
};
use managed_processes::{
    read_managed_server_processes, stop_browser4_server_forcibly, ManagedServerProcess,
    ShutdownResult,
};
use snapshot::{resolve_output_path, save_binary, save_snapshot, timestamped_filename};
use state::{
    clear_all_state, clear_state, read_state, resolve_default_state_dir, resolve_ref, write_state,
    CliState, MousePosition,
};

const VERSION: &str = env!("CARGO_PKG_VERSION");
const TEST_TEMPORARY_PROFILE_ENV: &str = "BROWSER4_CLI_TEST_TEMPORARY_PROFILE";
const AGENT_RUN_FAILURE_POLL_ATTEMPTS: usize = 5;
const AGENT_RUN_FAILURE_POLL_INTERVAL_MS: u64 = 250;
const SWARM_SESSION_ID: &str = "SWARM";

// ---------------------------------------------------------------------------
// JSON output support (--json global flag)
// ---------------------------------------------------------------------------

use std::cell::RefCell;

thread_local! {
    /// When `--json` is active, handlers write structured fields here instead
    /// of (or in addition to) printing human-readable text.  `None` means
    /// human-output mode (the default).
    static JSON_OUTPUT: RefCell<Option<serde_json::Map<String, serde_json::Value>>> =
        RefCell::new(None);
}

/// Initialise the JSON output accumulator for the current command.
fn json_init() {
    JSON_OUTPUT.with(|cell| {
        *cell.borrow_mut() = Some(serde_json::Map::new());
    });
}

/// Store a field in the JSON output.  No-op when `--json` is not active.
fn json_field(key: &str, value: serde_json::Value) {
    JSON_OUTPUT.with(|cell| {
        if let Some(ref mut map) = *cell.borrow_mut() {
            map.insert(key.to_string(), value);
        }
    });
}

/// True when `--json` mode is active.
#[allow(dead_code)]
fn json_active() -> bool {
    JSON_OUTPUT.with(|cell| cell.borrow().is_some())
}

/// Take the accumulated JSON fields and tear down the accumulator.
fn json_finish() -> Option<serde_json::Map<String, serde_json::Value>> {
    JSON_OUTPUT.with(|cell| cell.borrow_mut().take())
}

// ---------------------------------------------------------------------------
// Quiet output support (-q / --quiet global flag)
// ---------------------------------------------------------------------------

thread_local! {
    /// When `-q` / `--quiet` is active, normal output is suppressed.
    /// Errors still go to stderr.
    static QUIET: RefCell<bool> = RefCell::new(false);
}

fn quiet_init(quiet: bool) {
    QUIET.with(|cell| *cell.borrow_mut() = quiet);
}

fn quiet_active() -> bool {
    QUIET.with(|cell| *cell.borrow())
}

/// Print to stdout unless `-q` / `--quiet` or `--json` is active.
/// When `--json` is active all output must be machine-readable;
/// human-oriented text is suppressed.
macro_rules! cli_println {
    () => {
        if !$crate::quiet_active() && !$crate::json_active() {
            ::std::println!();
        }
    };
    ($($arg:tt)*) => {
        if !$crate::quiet_active() && !$crate::json_active() {
            ::std::println!($($arg)*);
        }
    };
}

/// Commands that should NOT trigger a post-command snapshot.
fn no_snapshot_commands() -> HashSet<&'static str> {
    [
        "open",
        "goto",
        "close",
        "close-all",
        "kill-all",
        "list",
        "install",
        "help",
        "eval",
        "summarize",
        "snapshot",
        "screenshot",
        "state-save",
        "state-load",
        "cookie-list",
        "cookie-get",
        "cookie-set",
        "cookie-delete",
        "cookie-clear",
        "localstorage-list",
        "localstorage-get",
        "localstorage-set",
        "localstorage-delete",
        "localstorage-clear",
        "sessionstorage-list",
        "sessionstorage-get",
        "sessionstorage-set",
        "sessionstorage-delete",
        "sessionstorage-clear",
        "pdf",
        "agent-run",
        "agent-status",
        "agent-result",
        "swarm-create",
        "swarm-submit",
        "swarm-status",
        "swarm-result",
    ]
    .into()
}

// ---------------------------------------------------------------------------
// Session helpers
// ---------------------------------------------------------------------------

fn require_session(session_name: Option<&str>) -> Result<CliState, String> {
    let state = read_state(None, session_name);
    if state.session_id.is_none() {
        return Err(no_active_session_message());
    }
    Ok(state)
}

fn format_session_guidance_message(
    title: &str,
    command_hint: Option<&str>,
    details: &str,
    suggestions: &[&str],
) -> String {
    let mut message = vec![format!("🔐 {title}")];

    if let Some(command_hint) = command_hint {
        message.push(format!("  Command: {command_hint}"));
    }

    if !suggestions.is_empty() {
        message.push(String::new());
        message.push("💡 What to try".to_string());
        message.extend(suggestions.iter().map(|line| format!("  - {line}")));
    }

    message.push(String::new());
    message.push("🧾 Details".to_string());
    message.push(format!("  {details}"));
    message.join("\n")
}

fn no_active_session_message() -> String {
    format_session_guidance_message(
        "Session required",
        None,
        "No active session is currently stored for this CLI context.",
        &["run `browser4-cli open` first."],
    )
}

fn saved_session_expired_message() -> String {
    format_session_guidance_message(
        "Session refresh needed",
        None,
        "The saved session expired or is no longer usable.",
        &["run `browser4-cli open` to create a fresh session, then retry."],
    )
}

fn get_session_id(state: &CliState) -> Result<&str, String> {
    state
        .session_id
        .as_deref()
        .ok_or_else(no_active_session_message)
}

fn get_session_id_for_close(state: &CliState) -> Option<&str> {
    state
        .session_id
        .as_deref()
        .map(str::trim)
        .filter(|session_id| !session_id.is_empty())
}

fn tracked_selector(tool_params: &Value) -> Option<&str> {
    tool_params
        .get("ref")
        .and_then(|value| value.as_str())
        .or_else(|| tool_params.get("selector").and_then(|value| value.as_str()))
        .map(str::trim)
        .filter(|selector| !selector.is_empty())
}

fn persist_active_selector(
    base_url: &str,
    session_name: Option<&str>,
    selector: Option<&str>,
) -> Result<(), String> {
    let Some(selector) = selector else {
        return Ok(());
    };

    let mut state = read_state(None, session_name);
    state.base_url = base_url.to_string();
    state.active_selector = Some(selector.to_string());
    write_state(&state, None, session_name).map_err(|e| e.to_string())
}

async fn restore_active_selector(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<(), String> {
    let state = read_state(None, session_name);
    let Some(selector) = state.active_selector.as_deref() else {
        return Ok(());
    };

    if selector.starts_with("backend:") {
        return Ok(());
    }

    let selector_literal = serde_json::to_string(selector)
        .map_err(|e| format!("Failed to encode active selector: {e}"))?;
    let focus_expression = format!(
        "(() => {{ \
            try {{ \
                const el = document.querySelector({selector_literal}); \
                if (!el) return 'missing'; \
                if (typeof el.focus === 'function') {{ \
                    el.focus(); \
                }} \
                return document.activeElement === el ? 'focused' : 'unfocused'; \
            }} catch (error) {{ \
                return `invalid:${{error}}`; \
            }} \
        }})()"
    );

    let focus_result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let focus_expression = focus_expression.clone();

        async move {
            call_tool(
                &client,
                &base_url,
                "browser_evaluate",
                json!({
                    "sessionId": session_id,
                    "expression": focus_expression,
                }),
            )
            .await
        }
    })
    .await?;

    match focus_result.trim() {
        "focused" => Ok(()),
        "missing" => Err(format!(
            "Saved active selector '{selector}' no longer exists on the page."
        )),
        "unfocused" => Err(format!(
            "Failed to focus saved active selector '{selector}' before keyboard command."
        )),
        other if other.starts_with("invalid:") => Err(format!(
            "Saved active selector '{selector}' is not a valid query selector: {other}"
        )),
        other => Err(format!(
            "Unexpected focus result for saved active selector '{selector}': {other}"
        )),
    }
}

async fn create_session(
    client: &Client,
    base_url: &str,
    state: &CliState,
    session_name: Option<&str>,
    capabilities: Option<Value>,
) -> Result<String, String> {
    let params = build_open_session_request(capabilities, session_name);
    let result = call_tool(client, base_url, "open_session", params).await?;
    // The server response may be a JSON object `{"sessionId":"..."}` or a plain
    // string. Try JSON first; fall back to using the raw string as the session ID.
    let session_id = if let Ok(parsed) = serde_json::from_str::<Value>(&result) {
        parsed
            .get("sessionId")
            .and_then(|v| v.as_str())
            .unwrap_or(&result)
            .to_string()
    } else {
        result
    };

    let mut new_state = state.clone();
    new_state.session_id = Some(session_id.clone());
    new_state.base_url = base_url.to_string();
    new_state.active_selector = None;
    new_state.last_mouse_position = None;
    write_state(&new_state, None, session_name).map_err(|e| e.to_string())?;
    Ok(session_id)
}

fn build_open_session_capabilities(tool_params: &Value) -> Value {
    build_open_session_capabilities_with_test_mode(tool_params, should_use_test_temporary_profile())
}

fn build_open_session_request(capabilities: Option<Value>, session_name: Option<&str>) -> Value {
    let requested_session_id = session_name
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or("default");

    let mut caps = capabilities
        .and_then(|value| value.as_object().cloned())
        .unwrap_or_default();
    caps.insert("sessionId".to_string(), json!(requested_session_id));

    json!({ "capabilities": Value::Object(caps) })
}

fn build_swarm_create_capabilities(tool_params: &Value) -> Result<Value, String> {
    let profile_mode = tool_params
        .get("profileMode")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or("SEQUENTIAL")
        .to_ascii_uppercase();

    match profile_mode.as_str() {
        "SEQUENTIAL" | "TEMPORARY" => {}
        _ => {
            return Err(format!(
                "Swarm create only supports --profile-mode=SEQUENTIAL or --profile-mode=TEMPORARY. Received: {}",
                profile_mode
            ))
        }
    }

    let mut capabilities = serde_json::Map::new();
    capabilities.insert("profileMode".to_string(), json!(profile_mode));

    if let Some(v) = tool_params
        .get("maxOpenTabs")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        capabilities.insert("maxOpenTabs".to_string(), json!(v));
    }
    if let Some(v) = tool_params
        .get("maxBrowserContexts")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        capabilities.insert("maxBrowserContexts".to_string(), json!(v));
    }
    if let Some(v) = tool_params
        .get("displayMode")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        capabilities.insert("displayMode".to_string(), json!(v));
    }

    Ok(Value::Object(capabilities))
}

fn should_navigate_after_open(url: &str) -> bool {
    !url.is_empty() && url != "about:blank"
}

fn should_reuse_open_session(
    existing_session_id: Option<&str>,
    _session_name: Option<&str>,
) -> bool {
    existing_session_id
        .map(str::trim)
        .filter(|session_id| !session_id.is_empty())
        .is_some()
}

fn should_use_test_temporary_profile() -> bool {
    matches!(
        std::env::var(TEST_TEMPORARY_PROFILE_ENV).ok().as_deref(),
        Some("1" | "true" | "TRUE" | "yes" | "YES" | "on" | "ON")
    )
}

fn build_open_session_capabilities_with_test_mode(
    tool_params: &Value,
    use_test_temporary_profile: bool,
) -> Value {
    let mut caps = json!({});

    if let Some(pm) = tool_params.get("profileMode") {
        caps["profileMode"] = pm.clone();
    }

    if let Some(h) = tool_params.get("headed") {
        caps["headed"] = h.clone();
    }

    let persistent = tool_params
        .get("persistent")
        .and_then(|value| value.as_bool())
        .unwrap_or(false);
    if let Some(p) = tool_params.get("persistent") {
        caps["persistent"] = p.clone();
    }

    let has_profile_path = tool_params
        .get("profilePath")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .is_some();
    if let Some(pp) = tool_params.get("profilePath") {
        caps["profilePath"] = pp.clone();
    }

    if let Some(interact_level) = tool_params.get("interactLevel") {
        caps["interactLevel"] = interact_level.clone();
    }

    if use_test_temporary_profile && !persistent && !has_profile_path {
        caps["profileMode"] = json!("TEMPORARY");
    }

    caps
}

fn invalidate_session(state: &CliState, base_url: &str, session_name: Option<&str>) {
    let mut new_state = state.clone();
    new_state.session_id = None;
    new_state.base_url = base_url.to_string();
    new_state.active_selector = None;
    new_state.last_mouse_position = None;
    let _ = write_state(&new_state, None, session_name);
}

/// Execute an action with the current session, recovering stale sessions if requested.
async fn with_session<F, Fut>(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
    recover_stale: bool,
    action: F,
) -> Result<String, String>
where
    F: Fn(String) -> Fut + Send,
    Fut: std::future::Future<Output = Result<String, String>> + Send,
{
    let state = require_session(session_name)?;
    let session_id = get_session_id(&state)?.to_string();

    match action(session_id.clone()).await {
        Ok(result) => Ok(result),
        Err(err) => {
            if !is_stale_session_error(&err) {
                return Err(err);
            }
            invalidate_session(&state, base_url, session_name);
            if !recover_stale {
                return Err(saved_session_expired_message());
            }
            let new_session_id =
                create_session(client, base_url, &state, session_name, None).await?;
            action(new_session_id).await
        }
    }
}

// ---------------------------------------------------------------------------
// Post-command snapshot
// ---------------------------------------------------------------------------

async fn post_command_snapshot(client: &Client, base_url: &str, session_id: &str) {
    let (page_url, page_title, snapshot_content) = tokio::join!(
        call_tool(
            client,
            base_url,
            "page_url",
            json!({ "sessionId": session_id })
        ),
        call_tool(
            client,
            base_url,
            "page_title",
            json!({ "sessionId": session_id })
        ),
        call_tool(
            client,
            base_url,
            "browser_snapshot",
            json!({ "sessionId": session_id })
        ),
    );

    let (url_result, title_result, snap_result) = match (page_url, page_title, snapshot_content) {
        (Ok(u), Ok(t), Ok(s)) => (u, t, s),
        _ => return, // silently ignore failures (e.g. session just closed)
    };

    let out_path = resolve_output_path(None, "snapshot", "yml");
    if let Err(e) = save_snapshot(&out_path, &snap_result) {
        eprintln!("Warning: failed to save snapshot: {e}");
        return;
    }

    json_field("page_url", json!(&url_result));
    json_field("page_title", json!(&title_result));
    json_field("snapshot_path", json!(out_path.display().to_string()));

    cli_println!("### Page");
    cli_println!("- Page URL: {}", url_result);
    cli_println!("- Page Title: {}", title_result);
    cli_println!("### Snapshot");
    cli_println!("[Snapshot]({})", out_path.display());
}

// ---------------------------------------------------------------------------
// Command handlers
// ---------------------------------------------------------------------------

async fn get_or_create_navigation_session(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(CliState, String, bool), String> {
    let mut state = read_state(None, session_name);
    state.session_name = session_name.map(|s| s.to_string());

    let reusable_session_id =
        find_reusable_persisted_session_id(client, base_url, &state, session_name).await?;
    let reused_existing_session = reusable_session_id.is_some();
    let session_id = if let Some(existing_id) = reusable_session_id {
        existing_id
    } else {
        let capabilities = build_open_session_capabilities(tool_params);
        let new_id =
            create_session(client, base_url, &state, session_name, Some(capabilities)).await?;
        cli_println!("Session opened: {}", new_id);
        new_id
    };

    Ok((state, session_id, reused_existing_session))
}

async fn handle_open(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let (state, session_id, reused_existing_session) =
        get_or_create_navigation_session(client, base_url, tool_params, session_name).await?;

    json_field("session_id", json!(&session_id));
    json_field("reused", json!(reused_existing_session));

    let url = tool_params
        .get("url")
        .and_then(|u| u.as_str())
        .unwrap_or("about:blank");
    if should_navigate_after_open(url) {
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id.clone());
        let navigate_result = call_tool(client, base_url, tool_name, params.clone()).await;
        match navigate_result {
            Ok(result) => {
                if reused_existing_session {
                    cli_println!("Session already open: {}", session_id);
                }
                if !result.is_empty() {
                    cli_println!("{}", result);
                }
                post_command_snapshot(client, base_url, &session_id).await;
            }
            Err(err) => {
                if !should_retry_open_after_navigation_error(&err, reused_existing_session) {
                    return Err(format_navigation_failure_message(
                        url,
                        &session_id,
                        &err,
                        false,
                    ));
                }
                // The browser context was not ready yet (BrowserProtocol initialization race).
                // Or the reused saved session no longer has a usable browser tab.
                // Close the failed session, create a fresh one, and retry navigation.
                let _ = call_tool(
                    client,
                    base_url,
                    "close_session",
                    json!({ "sessionId": session_id }),
                )
                .await;
                invalidate_session(&state, base_url, session_name);
                let capabilities = build_open_session_capabilities(tool_params);
                let retry_id =
                    create_session(client, base_url, &state, session_name, Some(capabilities))
                        .await?;
                cli_println!("Session opened: {}", retry_id);
                params["sessionId"] = json!(retry_id);
                let retry_result = call_tool(client, base_url, tool_name, params)
                    .await
                    .map_err(|err| {
                        format_navigation_failure_message(
                            url,
                            &retry_id,
                            &err,
                            should_retry_open_after_navigation_error(&err, true),
                        )
                    })?;
                if !retry_result.is_empty() {
                    cli_println!("{}", retry_result);
                }
                post_command_snapshot(client, base_url, &retry_id).await;
            }
        }
    } else if reused_existing_session {
        cli_println!("Session already open: {}", session_id);
    }
    Ok(())
}

async fn handle_goto(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let (state, session_id, reused_existing_session) =
        get_or_create_navigation_session(client, base_url, tool_params, session_name).await?;
    let target_url = tool_params
        .get("url")
        .and_then(|value| value.as_str())
        .unwrap_or("<unknown>");
    let mut params = tool_params.clone();
    params["sessionId"] = json!(session_id.clone());
    let navigate_result = call_tool(client, base_url, tool_name, params.clone()).await;
    match navigate_result {
        Ok(result) => {
            if !result.is_empty() {
                cli_println!("{}", result);
            }
            post_command_snapshot(client, base_url, &session_id).await;
        }
        Err(err) => {
            if !should_retry_open_after_navigation_error(&err, reused_existing_session) {
                let should_suggest_refresh = should_retry_open_after_navigation_error(&err, true);
                cli_println!(
                    "{}",
                    format_navigation_failure_message(
                        target_url,
                        &session_id,
                        &err,
                        should_suggest_refresh,
                    )
                );
                return Ok(());
            }

            let _ = call_tool(
                client,
                base_url,
                "close_session",
                json!({ "sessionId": session_id }),
            )
            .await;
            invalidate_session(&state, base_url, session_name);
            let capabilities = build_open_session_capabilities(tool_params);
            let retry_id =
                create_session(client, base_url, &state, session_name, Some(capabilities)).await?;
            cli_println!("Session opened: {}", retry_id);
            params["sessionId"] = json!(retry_id.clone());

            match call_tool(client, base_url, tool_name, params).await {
                Ok(result) => {
                    if !result.is_empty() {
                        cli_println!("{}", result);
                    }
                    post_command_snapshot(client, base_url, &retry_id).await;
                }
                Err(retry_err) => {
                    let should_suggest_refresh =
                        should_retry_open_after_navigation_error(&retry_err, true);
                    cli_println!(
                        "{}",
                        format_navigation_failure_message(
                            target_url,
                            &retry_id,
                            &retry_err,
                            should_suggest_refresh,
                        )
                    );
                }
            }
        }
    }

    Ok(())
}

fn is_timeout_error_message(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("timed out") || lower.contains("deadline has elapsed")
}

fn format_navigation_failure_message(
    target_url: &str,
    session_id: &str,
    error: &str,
    suggest_refresh: bool,
) -> String {
    let mut message = vec![
        "❌ Navigation failed".to_string(),
        format!("  URL: {target_url}"),
        format!("  Session: {session_id}"),
    ];

    let mut suggestions = Vec::new();

    if suggest_refresh {
        suggestions.push("run `browser4-cli open` to refresh the session, then retry.".to_string());
    }

    if is_timeout_error_message(error) {
        suggestions.push(
            "if the page eventually opens, increase `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` and retry."
                .to_string(),
        );
    }

    if !suggestions.is_empty() {
        message.push(String::new());
        message.push("💡 What to try".to_string());
        message.extend(suggestions.into_iter().map(|line| format!("  - {line}")));
    }

    message.push(String::new());
    message.push("🧾 Details".to_string());
    message.push(format!("  {error}"));
    message.join("\n")
}

async fn handle_close(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<(), String> {
    // `close` is intentionally scoped to one browser session. Global Browser4
    // server shutdown and PULSAR_CHROME cleanup belong to `close-all`/`kill-all`.
    let state = read_state(None, session_name);
    let Some(session_id) = get_session_id_for_close(&state).map(str::to_string) else {
        clear_state(None, session_name);
        eprintln!("{}", no_active_session_message());
        json_field("session_id", json!(null));
        json_field("closed", json!(false));
        return Ok(());
    };
    json_field("session_id", json!(&session_id));
    // Ignore errors — session might already be closed
    let _ = call_tool(
        client,
        base_url,
        "close_session",
        json!({ "sessionId": session_id }),
    )
    .await;
    clear_state(None, session_name);
    cli_println!("Session closed.");
    json_field("closed", json!(true));
    Ok(())
}

async fn handle_close_all(client: &Client, base_url: &str) -> Result<(), String> {
    let close_summary = close_all_sessions_across_servers(client, base_url).await;

    // `close-all` is intentionally session-scoped. Keep any tracked Browser4
    // backend process alive so callers can continue using the same service and
    // reserve JVM shutdown for the explicit `kill-all` flow.
    clear_all_state(None);

    json_field("results", json!(close_summary.results));
    json_field("errors", json!(close_summary.errors));

    log_close_all_summary(&close_summary, "close-all");
    Ok(())
}

async fn handle_kill_all() -> Result<(), String> {
    eprintln!("🔪 Stopping Browser4 backend and cleaning up browser processes ...");
    eprintln!();

    let result = stop_browser4_server_forcibly();
    let shutdown_result = result.shutdown;
    finalize_global_cleanup("Killed", &shutdown_result);

    // JSON fields
    {
        let server_pids: Vec<u32> = shutdown_result.stopped_pids.clone();
        json_field("server_pids", json!(server_pids));
        let browser_result = &result.browser_kill;
        json_field("browser_pids_killed", json!(browser_result.killed_pids));
        json_field("browser_pids_remaining", json!(browser_result.remaining_pids));
        json_field(
            "fallback_killed_pids",
            json!(shutdown_result.fallback_killed_server_pids),
        );
    }

    eprintln!();

    // ── Summary ──
    let server_pids: Vec<String> = shutdown_result
        .stopped_pids
        .iter()
        .map(|p| p.to_string())
        .collect();
    if !server_pids.is_empty() {
        cli_println!("✅ Server stopped (pid(s): {})", server_pids.join(", "));
    } else if shutdown_result.remaining_pids.is_empty()
        && shutdown_result.missing_pids.is_empty()
        && shutdown_result.forced_pids.is_empty()
    {
        cli_println!("✅ No Browser4 server was running.");
    }

    if !shutdown_result.fallback_killed_server_pids.is_empty() {
        let pids: Vec<String> = shutdown_result
            .fallback_killed_server_pids
            .iter()
            .map(|p| p.to_string())
            .collect();
        cli_println!(
            "⚠  Fallback-killed server process(es): {}",
            pids.join(", ")
        );
    }

    let browser_result = result.browser_kill;
    let total_browsers = browser_result.killed_pids.len() + browser_result.remaining_pids.len();
    if total_browsers > 0 {
        if !browser_result.killed_pids.is_empty() {
            let pids: Vec<String> = browser_result
                .killed_pids
                .iter()
                .map(|p| p.to_string())
                .collect();
            cli_println!("✅ Killed browser process(es): {}", pids.join(", "));
        }
        if !browser_result.remaining_pids.is_empty() {
            let pids: Vec<String> = browser_result
                .remaining_pids
                .iter()
                .map(|p| p.to_string())
                .collect();
            return Err(format!(
                "❌ Browser cleanup incomplete. Remaining process(es): {}",
                pids.join(", ")
            ));
        }
    } else {
        cli_println!("ℹ  No Browser4 browser processes found.");
    }

    cli_println!();
    cli_println!("✅ kill-all complete.");
    Ok(())
}

#[derive(Debug, Default, PartialEq, Eq)]
struct CloseAllSummary {
    results: Vec<String>,
    errors: Vec<String>,
}

#[derive(Debug, Default, Deserialize, PartialEq, Eq)]
struct StorageStateLoadSummary {
    #[serde(default)]
    cookies: usize,
    #[serde(default)]
    origins: usize,
    #[serde(rename = "localStorageEntries", default)]
    local_storage_entries: usize,
}

#[derive(Debug, Default, Deserialize, PartialEq, Eq)]
struct StorageLookupResult {
    #[serde(default)]
    found: bool,
    #[serde(default)]
    value: String,
}

#[derive(Debug, Default, Deserialize, PartialEq, Eq)]
struct StorageDeleteResult {
    #[serde(default)]
    existed: bool,
}

#[derive(Debug, Default, Deserialize, PartialEq, Eq)]
struct StorageClearResult {
    #[serde(default)]
    cleared: usize,
}

fn parse_json_output<T: DeserializeOwned>(value: &str, context: &str) -> Result<T, String> {
    serde_json::from_str(value).map_err(|e| format!("Failed to parse {context}: {e}"))
}

async fn call_session_tool(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
    tool_name: &str,
    tool_params: Value,
) -> Result<String, String> {
    with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let tool_params = tool_params.clone();
        async move {
            let mut payload = tool_params;
            payload["sessionId"] = json!(session_id);
            call_tool(&client, &base_url, &tool_name, payload).await
        }
    })
    .await
}

async fn current_session_storage_state(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<Value, String> {
    let response = call_session_tool(
        client,
        base_url,
        session_name,
        "browser_save_storage_state",
        json!({}),
    )
    .await?;
    parse_json_output(&response, "storage state JSON")
}

async fn current_session_url(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<String, String> {
    call_session_tool(client, base_url, session_name, "current_url", json!({})).await
}

fn storage_area_label(storage_area: &str) -> &'static str {
    match storage_area {
        "localStorage" => "localStorage",
        "sessionStorage" => "sessionStorage",
        _ => "storage",
    }
}

fn build_storage_list_expression(storage_area: &str) -> String {
    format!(
        "(() => {{ \
            const storage = window.{storage_area}; \
            const entries = []; \
            for (let index = 0; index < storage.length; index += 1) {{ \
                const name = storage.key(index); \
                if (name == null) continue; \
                entries.push({{ name, value: storage.getItem(name) ?? '' }}); \
            }} \
            return JSON.stringify(entries); \
        }})()"
    )
}

fn build_storage_get_expression(storage_area: &str, key: &str) -> Result<String, String> {
    let key_json = serde_json::to_string(key).map_err(|e| format!("Failed to encode key: {e}"))?;
    Ok(format!(
        "(() => {{ \
            const storage = window.{storage_area}; \
            const key = {key_json}; \
            const value = storage.getItem(key); \
            return JSON.stringify({{ found: value !== null, value: value ?? '' }}); \
        }})()"
    ))
}

fn build_storage_set_expression(
    storage_area: &str,
    key: &str,
    value: &str,
) -> Result<String, String> {
    let key_json = serde_json::to_string(key).map_err(|e| format!("Failed to encode key: {e}"))?;
    let value_json =
        serde_json::to_string(value).map_err(|e| format!("Failed to encode value: {e}"))?;
    Ok(format!(
        "(() => {{ \
            const storage = window.{storage_area}; \
            const key = {key_json}; \
            const value = {value_json}; \
            storage.setItem(key, value); \
            return JSON.stringify({{ found: true, value: storage.getItem(key) ?? '' }}); \
        }})()"
    ))
}

fn build_storage_delete_expression(storage_area: &str, key: &str) -> Result<String, String> {
    let key_json = serde_json::to_string(key).map_err(|e| format!("Failed to encode key: {e}"))?;
    Ok(format!(
        "(() => {{ \
            const storage = window.{storage_area}; \
            const key = {key_json}; \
            const existed = storage.getItem(key) !== null; \
            storage.removeItem(key); \
            return JSON.stringify({{ existed }}); \
        }})()"
    ))
}

fn build_storage_clear_expression(storage_area: &str) -> String {
    format!(
        "(() => {{ \
            const storage = window.{storage_area}; \
            const cleared = storage.length; \
            storage.clear(); \
            return JSON.stringify({{ cleared }}); \
        }})()"
    )
}

async fn evaluate_storage_expression(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
    expression: String,
) -> Result<String, String> {
    call_session_tool(
        client,
        base_url,
        session_name,
        "browser_evaluate",
        json!({ "expression": expression }),
    )
    .await
}

fn known_server_base_urls(
    base_url: &str,
    managed_processes: &[ManagedServerProcess],
) -> Vec<String> {
    let mut base_urls: Vec<String> = managed_processes
        .iter()
        .map(|proc| proc.base_url.trim_end_matches('/').to_string())
        .chain(std::iter::once(base_url.trim_end_matches('/').to_string()))
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    base_urls.sort();
    base_urls
}

async fn close_all_sessions_across_servers(client: &Client, base_url: &str) -> CloseAllSummary {
    let normalized_base_url = base_url.trim_end_matches('/').to_string();
    let mut summary = CloseAllSummary::default();

    for url in known_server_base_urls(base_url, &read_managed_server_processes(None)) {
        match call_tool(client, &url, "close_all_sessions", json!({})).await {
            Ok(result) => {
                if url == normalized_base_url {
                    summary.results.push(result);
                } else {
                    summary.results.push(format!("{}: {}", url, result));
                }
            }
            Err(error) => summary.errors.push(format!("{}: {}", url, error)),
        }
    }

    summary
}

fn log_close_all_summary(summary: &CloseAllSummary, command_name: &str) {
    if summary.results.is_empty() {
        cli_println!(
            "No reachable Browser4 servers responded to {}.",
            command_name
        );
    } else {
        for result in &summary.results {
            cli_println!("{}", result);
        }
    }

    if !summary.errors.is_empty() {
        eprintln!("{} warnings: {}", command_name, summary.errors.join(" | "));
    }
}

fn finalize_global_cleanup(action: &str, result: &ShutdownResult) {
    clear_all_state(None);
    log_shutdown_result(action, result);
}

fn log_shutdown_result(action: &str, result: &ShutdownResult) {
    if !result.stopped_pids.is_empty() {
        let pids: Vec<String> = result.stopped_pids.iter().map(|p| p.to_string()).collect();
        cli_println!("{} Browser4 process(es): {}", action, pids.join(", "));
    } else if result.missing_pids.is_empty() {
        cli_println!("No tracked Browser4 processes found.");
    }

    if !result.missing_pids.is_empty() {
        let pids: Vec<String> = result.missing_pids.iter().map(|p| p.to_string()).collect();
        cli_println!("Already stopped Browser4 process(es): {}", pids.join(", "));
    }

    if !result.forced_pids.is_empty() && action == "Stopped" {
        let pids: Vec<String> = result.forced_pids.iter().map(|p| p.to_string()).collect();
        cli_println!(
            "Forced Browser4 process(es) after graceful timeout: {}",
            pids.join(", ")
        );
    }

    if !result.remaining_pids.is_empty() {
        let pids: Vec<String> = result
            .remaining_pids
            .iter()
            .map(|p| p.to_string())
            .collect();
        eprintln!(
            "Browser4 process(es) still running after {}: {}",
            action.to_lowercase(),
            pids.join(", ")
        );
    }
}

async fn handle_list(client: &Client, base_url: &str) -> Result<(), String> {
    let (backend_sessions, backend_note): (Option<Vec<BackendSessionRecord>>, Option<String>) =
        match call_tool(client, base_url, "list_sessions", json!({})).await {
            Ok(result) => (Some(parse_backend_session_records(&result)), None),
            Err(error) if is_backend_unreachable_error(&error) => (
                None,
                Some(format!(
                    "Note: Browser4 backend is not started or unreachable at {}. Showing local persisted sessions only; the next `open` will refresh any saved session because its active state cannot be verified.",
                    base_url
                )),
            ),
            Err(error) => return Err(error),
        };

    cli_println!(
        "{:<20} | {:<40} | {:<8} | {}",
        "Name", "Session ID", "Status", "Next open"
    );
    cli_println!("{:-<20}-+-{:-<40}-+-{:-<8}-+-{:-<9}", "", "", "", "");

    let mut json_sessions: Vec<serde_json::Value> = Vec::new();
    let backend_reachable = backend_sessions.is_some();

    // List named sessions
    let state_dir = resolve_default_state_dir().join("sessions");
    if state_dir.exists() {
        if let Ok(entries) = std::fs::read_dir(state_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.extension().map_or(false, |ext| ext == "json") {
                    let name = path.file_stem().unwrap().to_string_lossy();
                    if let Ok(content) = std::fs::read_to_string(&path) {
                        if let Ok(state) = serde_json::from_str::<CliState>(&content) {
                            if let Some(sid) = state.session_id {
                                let status = list_session_status(backend_sessions.as_deref(), &sid);
                                let next_open = list_session_next_open_action(
                                    backend_sessions.as_deref(),
                                    &sid,
                                );
                                cli_println!(
                                    "{:<20} | {:<40} | {:<8} | {}",
                                    name, sid, status, next_open
                                );
                                json_sessions.push(json!({
                                    "name": name.to_string(),
                                    "session_id": sid,
                                    "status": status.to_lowercase(),
                                    "next_open": next_open.to_lowercase(),
                                }));
                            }
                        }
                    }
                }
            }
        }
    }

    // List default session
    let default_state = read_state(None, None);
    if let Some(sid) = default_state.session_id {
        let status = list_session_status(backend_sessions.as_deref(), &sid);
        let next_open = list_session_next_open_action(backend_sessions.as_deref(), &sid);
        cli_println!(
            "{:<20} | {:<40} | {:<8} | {}",
            "(default)", sid, status, next_open
        );
        json_sessions.push(json!({
            "name": "(default)",
            "session_id": sid,
            "status": status.to_lowercase(),
            "next_open": next_open.to_lowercase(),
        }));
    }

    json_field("sessions", json!(json_sessions));
    json_field("backend_reachable", json!(backend_reachable));

    if let Some(note) = backend_note {
        cli_println!("\n{}", note);
    }

    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct BackendSessionRecord {
    session_id: String,
    status: Option<String>,
}

fn list_session_status(
    backend_sessions: Option<&[BackendSessionRecord]>,
    session_id: &str,
) -> &'static str {
    match backend_sessions {
        Some(records) => records
            .iter()
            .find(|record| record.session_id == session_id)
            .map(|record| {
                if session_status_is_active(record.status.as_deref()) {
                    "Active"
                } else {
                    "Stale"
                }
            })
            .unwrap_or("Stale"),
        None => "Unknown",
    }
}

fn list_session_next_open_action(
    backend_sessions: Option<&[BackendSessionRecord]>,
    session_id: &str,
) -> &'static str {
    if backend_sessions
        .map(|records| session_is_active_in_records(records, session_id))
        .unwrap_or(false)
    {
        "Reuse"
    } else {
        "Refresh"
    }
}

async fn find_reusable_persisted_session_id(
    client: &Client,
    base_url: &str,
    state: &CliState,
    session_name: Option<&str>,
) -> Result<Option<String>, String> {
    let Some(session_id) = state.session_id.clone() else {
        return Ok(None);
    };

    if !should_reuse_open_session(Some(&session_id), session_name) {
        return Ok(None);
    }

    let list_result = match call_tool(client, base_url, "list_sessions", json!({})).await {
        Ok(result) => result,
        Err(error) if is_backend_unreachable_error(&error) => {
            invalidate_session(state, base_url, session_name);
            return Ok(None);
        }
        Err(error) => return Err(error),
    };

    if session_is_active(&list_result, &session_id) {
        Ok(Some(session_id))
    } else {
        invalidate_session(state, base_url, session_name);
        Ok(None)
    }
}

fn parse_backend_session_records(result: &str) -> Vec<BackendSessionRecord> {
    serde_json::from_str::<Value>(result)
        .ok()
        .and_then(|value| {
            value.as_array().map(|entries| {
                entries
                    .iter()
                    .filter_map(|entry| {
                        entry
                            .as_str()
                            .map(|session_id| BackendSessionRecord {
                                session_id: session_id.to_string(),
                                status: Some("active".to_string()),
                            })
                            .or_else(|| {
                                entry
                                    .get("sessionId")
                                    .and_then(|value| value.as_str())
                                    .filter(|session_id| !session_id.is_empty())
                                    .map(|session_id| BackendSessionRecord {
                                        session_id: session_id.to_string(),
                                        status: entry
                                            .get("status")
                                            .and_then(|value| value.as_str())
                                            .map(str::to_string),
                                    })
                            })
                    })
                    .collect()
            })
        })
        .unwrap_or_default()
}

fn session_status_is_active(status: Option<&str>) -> bool {
    status
        .map(|value| value.eq_ignore_ascii_case("active"))
        .unwrap_or(true)
}

fn session_is_active_in_records(records: &[BackendSessionRecord], session_id: &str) -> bool {
    records.iter().any(|record| {
        record.session_id == session_id && session_status_is_active(record.status.as_deref())
    })
}

fn session_is_active(result: &str, session_id: &str) -> bool {
    let records = parse_backend_session_records(result);
    session_is_active_in_records(&records, session_id)
}

#[cfg(test)]
fn parse_active_session_ids(result: &str) -> Vec<String> {
    parse_backend_session_records(result)
        .into_iter()
        .filter(|record| session_status_is_active(record.status.as_deref()))
        .map(|record| record.session_id)
        .collect()
}

fn is_backend_unreachable_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    [
        "connection refused",
        "error sending request",
        "tcp connect error",
        "failed to connect",
        "dns error",
        "timed out",
    ]
    .iter()
    .any(|pattern| lower.contains(pattern))
}

fn should_retry_open_after_navigation_error(error: &str, reused_existing_session: bool) -> bool {
    is_stale_session_error(error)
        || (reused_existing_session && is_backend_unreachable_error(error))
}

async fn handle_delete_data(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<(), String> {
    let result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        async move {
            call_tool(
                &client,
                &base_url,
                "delete_session_data",
                json!({ "sessionId": session_id }),
            )
            .await
        }
    })
    .await?;
    if result.is_empty() {
        cli_println!("Session data deleted.");
    } else {
        cli_println!("{}", result);
    }
    Ok(())
}

fn resolve_storage_state_path(filename: Option<&str>) -> Result<PathBuf, String> {
    let trimmed = filename.map(str::trim).filter(|value| !value.is_empty());
    let file_name = trimmed
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(timestamped_filename("storage-state", "json")));
    if file_name.is_absolute() {
        return Ok(file_name);
    }

    let cwd = std::env::current_dir().map_err(|e| e.to_string())?;
    Ok(cwd.join(file_name))
}

async fn handle_state_save(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let output_path =
        resolve_storage_state_path(tool_params.get("filename").and_then(|value| value.as_str()))?;
    let result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        async move {
            call_tool(
                &client,
                &base_url,
                &tool_name,
                json!({ "sessionId": session_id }),
            )
            .await
        }
    })
    .await?;

    let state_json = serde_json::from_str::<Value>(&result)
        .map_err(|e| format!("Browser4 returned invalid storage state JSON: {e}"))?;
    let formatted = serde_json::to_string_pretty(&state_json)
        .map_err(|e| format!("Failed to format storage state JSON: {e}"))?;
    save_snapshot(&output_path, &formatted).map_err(|e| e.to_string())?;
    cli_println!("Storage state saved: {}", output_path.display());
    Ok(())
}

async fn handle_state_load(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let filename = tool_params
        .get("filename")
        .and_then(|value| value.as_str())
        .ok_or_else(|| "state-load requires a storage-state JSON file path".to_string())?;
    let input_path = resolve_storage_state_path(Some(filename))?;
    let state_json = std::fs::read_to_string(&input_path).map_err(|e| {
        format!(
            "Failed to read storage state file {}: {}",
            input_path.display(),
            e
        )
    })?;
    serde_json::from_str::<Value>(&state_json).map_err(|e| {
        format!(
            "Failed to parse storage state file {}: {}",
            input_path.display(),
            e
        )
    })?;

    let (_, session_id, _) =
        get_or_create_navigation_session(client, base_url, &json!({}), session_name).await?;
    let result = call_tool(
        client,
        base_url,
        tool_name,
        json!({
            "sessionId": session_id,
            "state": state_json,
        }),
    )
    .await?;
    let summary: StorageStateLoadSummary = serde_json::from_str(&result)
        .map_err(|e| format!("Browser4 returned an invalid storage-state load summary: {e}"))?;
    cli_println!(
        "Storage state loaded: {} (cookies: {}, origins: {}, localStorage entries: {})",
        input_path.display(),
        summary.cookies,
        summary.origins,
        summary.local_storage_entries
    );
    Ok(())
}

async fn handle_cookie_list(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let state = current_session_storage_state(client, base_url, session_name).await?;
    let domain_filter = tool_params.get("domain").and_then(|value| value.as_str());
    let path_filter = tool_params.get("path").and_then(|value| value.as_str());
    let cookies = state["cookies"]
        .as_array()
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .filter(|cookie| {
            domain_filter
                .map(|domain| cookie.get("domain").and_then(|value| value.as_str()) == Some(domain))
                .unwrap_or(true)
                && path_filter
                    .map(|path| cookie.get("path").and_then(|value| value.as_str()) == Some(path))
                    .unwrap_or(true)
        })
        .collect::<Vec<_>>();
    cli_println!(
        "{}",
        serde_json::to_string_pretty(&cookies)
            .map_err(|e| format!("Failed to format cookies: {e}"))?
    );
    Ok(())
}

async fn handle_cookie_get(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let target_name = tool_params
        .get("name")
        .and_then(|value| value.as_str())
        .ok_or_else(|| "cookie-get requires a cookie name".to_string())?;
    let state = current_session_storage_state(client, base_url, session_name).await?;
    let cookie = state["cookies"]
        .as_array()
        .and_then(|cookies| {
            cookies.iter().find(|cookie| {
                cookie.get("name").and_then(|value| value.as_str()) == Some(target_name)
            })
        })
        .cloned()
        .ok_or_else(|| format!("Cookie not found: {target_name}"))?;
    cli_println!(
        "{}",
        serde_json::to_string_pretty(&cookie)
            .map_err(|e| format!("Failed to format cookie: {e}"))?
    );
    Ok(())
}

async fn handle_cookie_set(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let name = tool_params
        .get("name")
        .and_then(|value| value.as_str())
        .ok_or_else(|| "cookie-set requires a cookie name".to_string())?;
    let value = tool_params
        .get("value")
        .and_then(|value| value.as_str())
        .ok_or_else(|| "cookie-set requires a cookie value".to_string())?;

    let mut cookie = serde_json::Map::new();
    cookie.insert("name".to_string(), json!(name));
    cookie.insert("value".to_string(), json!(value));

    if let Some(domain) = tool_params.get("domain").and_then(|value| value.as_str()) {
        cookie.insert("domain".to_string(), json!(domain));
    } else {
        let current_url = current_session_url(client, base_url, session_name).await?;
        if !(current_url.starts_with("http://") || current_url.starts_with("https://")) {
            return Err(
                "cookie-set requires an HTTP(S) page in the current session or an explicit --domain option."
                    .to_string(),
            );
        }
        cookie.insert("url".to_string(), json!(current_url));
    }

    if let Some(path) = tool_params.get("path").and_then(|value| value.as_str()) {
        cookie.insert("path".to_string(), json!(path));
    }
    if let Some(expires) = tool_params.get("expires").and_then(|value| value.as_str()) {
        let expires_number = expires
            .parse::<f64>()
            .map_err(|e| format!("Invalid --expires value '{expires}': {e}"))?;
        cookie.insert("expires".to_string(), json!(expires_number));
    }
    if let Some(http_only) = tool_params
        .get("httpOnly")
        .and_then(|value| value.as_bool())
    {
        cookie.insert("httpOnly".to_string(), json!(http_only));
    }
    if let Some(secure) = tool_params.get("secure").and_then(|value| value.as_bool()) {
        cookie.insert("secure".to_string(), json!(secure));
    }
    if let Some(same_site) = tool_params.get("sameSite").and_then(|value| value.as_str()) {
        cookie.insert("sameSite".to_string(), json!(same_site));
    }

    let state = json!({
        "cookies": [Value::Object(cookie)],
        "origins": [],
    });
    let payload = serde_json::to_string(&state)
        .map_err(|e| format!("Failed to encode cookie payload: {e}"))?;
    let result = call_session_tool(
        client,
        base_url,
        session_name,
        "browser_load_storage_state",
        json!({ "state": payload }),
    )
    .await?;
    let _: StorageStateLoadSummary = parse_json_output(&result, "cookie-set summary")?;
    cli_println!("Cookie set: {}", name);
    Ok(())
}

async fn handle_cookie_delete(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let name = tool_params
        .get("name")
        .and_then(|value| value.as_str())
        .ok_or_else(|| "cookie-delete requires a cookie name".to_string())?;
    let mut payload = json!({ "name": name });
    if let Some(domain) = tool_params.get("domain").and_then(|value| value.as_str()) {
        payload["domain"] = json!(domain);
    } else {
        let current_url = current_session_url(client, base_url, session_name).await?;
        if !(current_url.starts_with("http://") || current_url.starts_with("https://")) {
            return Err(
                "cookie-delete requires an HTTP(S) page in the current session or an explicit --domain option."
                    .to_string(),
            );
        }
        payload["url"] = json!(current_url);
    }
    if let Some(path) = tool_params.get("path").and_then(|value| value.as_str()) {
        payload["path"] = json!(path);
    }
    let _ = call_session_tool(client, base_url, session_name, tool_name, payload).await?;
    cli_println!("Cookie deleted: {}", name);
    Ok(())
}

async fn handle_cookie_clear(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    session_name: Option<&str>,
) -> Result<(), String> {
    let _ = call_session_tool(client, base_url, session_name, tool_name, json!({})).await?;
    cli_println!("Cookies cleared.");
    Ok(())
}

async fn handle_storage_list(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
    storage_area: &str,
) -> Result<(), String> {
    let raw = evaluate_storage_expression(
        client,
        base_url,
        session_name,
        build_storage_list_expression(storage_area),
    )
    .await?;
    let parsed: Value = parse_json_output(
        &raw,
        &format!("{} list JSON", storage_area_label(storage_area)),
    )?;
    cli_println!(
        "{}",
        serde_json::to_string_pretty(&parsed).map_err(|e| format!(
            "Failed to format {} list: {e}",
            storage_area_label(storage_area)
        ))?
    );
    Ok(())
}

async fn handle_storage_get(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    storage_area: &str,
) -> Result<(), String> {
    let key = tool_params
        .get("key")
        .and_then(|value| value.as_str())
        .ok_or_else(|| format!("{}-get requires a key", storage_area.to_ascii_lowercase()))?;
    let raw = evaluate_storage_expression(
        client,
        base_url,
        session_name,
        build_storage_get_expression(storage_area, key)?,
    )
    .await?;
    let result: StorageLookupResult = parse_json_output(
        &raw,
        &format!("{} get result", storage_area_label(storage_area)),
    )?;
    if !result.found {
        return Err(format!(
            "{} key not found: {}",
            storage_area_label(storage_area),
            key
        ));
    }
    cli_println!("{}", result.value);
    Ok(())
}

async fn handle_storage_set(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    storage_area: &str,
) -> Result<(), String> {
    let key = tool_params
        .get("key")
        .and_then(|value| value.as_str())
        .ok_or_else(|| format!("{}-set requires a key", storage_area.to_ascii_lowercase()))?;
    let value = tool_params
        .get("value")
        .and_then(|value| value.as_str())
        .ok_or_else(|| format!("{}-set requires a value", storage_area.to_ascii_lowercase()))?;
    let raw = evaluate_storage_expression(
        client,
        base_url,
        session_name,
        build_storage_set_expression(storage_area, key, value)?,
    )
    .await?;
    let result: StorageLookupResult = parse_json_output(
        &raw,
        &format!("{} set result", storage_area_label(storage_area)),
    )?;
    if !result.found {
        return Err(format!(
            "Failed to set {} key: {}",
            storage_area_label(storage_area),
            key
        ));
    }
    cli_println!("{} key set: {}", storage_area_label(storage_area), key);
    Ok(())
}

async fn handle_storage_delete(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    storage_area: &str,
) -> Result<(), String> {
    let key = tool_params
        .get("key")
        .and_then(|value| value.as_str())
        .ok_or_else(|| {
            format!(
                "{}-delete requires a key",
                storage_area.to_ascii_lowercase()
            )
        })?;
    let raw = evaluate_storage_expression(
        client,
        base_url,
        session_name,
        build_storage_delete_expression(storage_area, key)?,
    )
    .await?;
    let result: StorageDeleteResult = parse_json_output(
        &raw,
        &format!("{} delete result", storage_area_label(storage_area)),
    )?;
    if result.existed {
        cli_println!("{} key deleted: {}", storage_area_label(storage_area), key);
    } else {
        cli_println!(
            "{} key not present: {}",
            storage_area_label(storage_area),
            key
        );
    }
    Ok(())
}

async fn handle_storage_clear(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
    storage_area: &str,
) -> Result<(), String> {
    let raw = evaluate_storage_expression(
        client,
        base_url,
        session_name,
        build_storage_clear_expression(storage_area),
    )
    .await?;
    let result: StorageClearResult = parse_json_output(
        &raw,
        &format!("{} clear result", storage_area_label(storage_area)),
    )?;
    cli_println!(
        "{} cleared: {} entrie(s).",
        storage_area_label(storage_area),
        result.cleared
    );
    Ok(())
}

async fn handle_snapshot(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let filename = tool_params
        .get("filename")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let snapshot_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
        }
        a
    };

    let combined = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut snap_args = snapshot_args.clone();
        snap_args["sessionId"] = json!(session_id.clone());

        async move {
            let (url_res, title_res, snap_res) = tokio::join!(
                call_tool(
                    &client,
                    &base_url,
                    "page_url",
                    json!({ "sessionId": session_id })
                ),
                call_tool(
                    &client,
                    &base_url,
                    "page_title",
                    json!({ "sessionId": session_id })
                ),
                call_tool(&client, &base_url, &tool_name, snap_args),
            );
            let url = url_res?;
            let title = title_res?;
            let snap = snap_res?;
            Ok(format!("{}\n{}\n{}", url, title, snap))
        }
    })
    .await?;

    // The combined result has url, title, and snapshot separated by newlines
    let parts: Vec<&str> = combined.splitn(3, '\n').collect();
    let (url, title, snap) = match parts.as_slice() {
        [u, t, s] => (*u, *t, *s),
        _ => ("", "", combined.as_str()),
    };

    let out_path = resolve_output_path(filename.as_deref(), "snapshot", "yml");
    save_snapshot(&out_path, snap).map_err(|e| e.to_string())?;

    json_field("page_url", json!(url));
    json_field("page_title", json!(title));
    json_field("snapshot_path", json!(out_path.display().to_string()));

    cli_println!("### Page");
    cli_println!("- Page URL: {}", url);
    cli_println!("- Page Title: {}", title);
    cli_println!("### Snapshot");
    cli_println!("[Snapshot]({})", out_path.display());
    Ok(())
}

async fn handle_screenshot(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let filename = tool_params
        .get("filename")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let capture_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
        }
        a
    };

    let base64_data = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut args = capture_args.clone();
        args["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, args).await }
    })
    .await?;

    let bytes = base64::engine::general_purpose::STANDARD
        .decode(base64_data.trim())
        .map_err(|e| format!("Failed to decode screenshot: {e}"))?;

    let out_path = resolve_output_path(filename.as_deref(), "screenshot", "png");
    save_binary(&out_path, &bytes).map_err(|e| e.to_string())?;
    cli_println!("[Screenshot]({})", out_path.display());
    Ok(())
}

async fn handle_tool_command(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    recover_stale: bool,
    session_name: Option<&str>,
) -> Result<(), String> {
    let result = with_session(
        client,
        base_url,
        session_name,
        recover_stale,
        |session_id| {
            let client = client.clone();
            let base_url = base_url.to_string();
            let tool_name = tool_name.to_string();
            let mut params = tool_params.clone();
            params["sessionId"] = json!(session_id);
            async move { call_tool(&client, &base_url, &tool_name, params).await }
        },
    )
    .await?;

    if !result.is_empty() {
        cli_println!("{}", result);
    }
    // Structured JSON fields for eval-like commands.
    if tool_name == "browser_evaluate" {
        json_field("result", json!(&result));
        if let Some(expression) = tool_params.get("expression").and_then(|v| v.as_str()) {
            json_field("expression", json!(expression));
        }
        if let Some(r) = tool_params.get("ref").and_then(|v| v.as_str()) {
            json_field("ref", json!(r));
        }
    }
    persist_active_selector(base_url, session_name, tracked_selector(tool_params))?;
    Ok(())
}

fn parse_number_arg(tool_params: &Value, name: &str) -> Result<f64, String> {
    tool_params
        .get(name)
        .and_then(|value| value.as_f64().or_else(|| value.as_i64().map(|n| n as f64)))
        .ok_or_else(|| format!("Missing numeric parameter: {name}"))
}

fn persist_mouse_position(
    base_url: &str,
    session_name: Option<&str>,
    position: MousePosition,
) -> Result<(), String> {
    let mut state = read_state(None, session_name);
    state.base_url = base_url.to_string();
    state.last_mouse_position = Some(position);
    write_state(&state, None, session_name).map_err(|e| e.to_string())
}

async fn restore_mouse_position(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<(), String> {
    let state = read_state(None, session_name);
    let Some(position) = state.last_mouse_position else {
        return Ok(());
    };

    with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();

        async move {
            call_tool(
                &client,
                &base_url,
                "browser_mouse_move_xy",
                json!({
                    "sessionId": session_id,
                    "x": position.x,
                    "y": position.y,
                }),
            )
            .await
        }
    })
    .await?;

    Ok(())
}

async fn handle_mouse_move(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    handle_tool_command(
        client,
        base_url,
        tool_name,
        tool_params,
        false,
        session_name,
    )
    .await?;
    let x = parse_number_arg(tool_params, "x")?;
    let y = parse_number_arg(tool_params, "y")?;
    persist_mouse_position(base_url, session_name, MousePosition { x, y })?;
    Ok(())
}

async fn handle_mouse_positioned_command(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    restore_mouse_position(client, base_url, session_name).await?;
    handle_tool_command(
        client,
        base_url,
        tool_name,
        tool_params,
        false,
        session_name,
    )
    .await
}

async fn handle_key_command(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    restore_active_selector(client, base_url, session_name).await?;
    handle_tool_command(
        client,
        base_url,
        tool_name,
        tool_params,
        false,
        session_name,
    )
    .await?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Agent command handlers
// ---------------------------------------------------------------------------

async fn handle_agent_run(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    _session_name: Option<&str>,
) -> Result<(), String> {
    let task = tool_params
        .get("task")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if task.is_empty() {
        return Err("Task description is required.".to_string());
    }

    let result = submit_plain_command(client, base_url, task, true).await?;

    // The async response is a task ID (possibly JSON-quoted)
    let task_id = result.trim().trim_matches('"').to_string();
    if let Some(message) =
        detect_missing_llm_error_for_submitted_agent_task(client, base_url, &task_id).await?
    {
        return Err(format!(
            "Agent task requires an LLM key and cannot execute: {}",
            message
        ));
    }
    cli_println!("Task submitted: {}", task_id);
    json_field("task_id", json!(&task_id));
    cli_println!(
        "Use 'browser4-cli agent status {}' to check progress.",
        task_id
    );
    Ok(())
}

async fn detect_missing_llm_error_for_submitted_agent_task(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<Option<String>, String> {
    for _ in 0..AGENT_RUN_FAILURE_POLL_ATTEMPTS {
        tokio::time::sleep(std::time::Duration::from_millis(
            AGENT_RUN_FAILURE_POLL_INTERVAL_MS,
        ))
        .await;

        let status = get_command_status(client, base_url, task_id).await?;
        if let Some(message) = extract_missing_llm_configuration_message(&status) {
            return Ok(Some(message));
        }

        let Some(parsed) = serde_json::from_str::<Value>(&status).ok() else {
            return Ok(None);
        };
        let Some(process_state) = parsed.get("processState").and_then(|value| value.as_str())
        else {
            return Ok(None);
        };
        if process_state == "done" {
            return Ok(None);
        }
    }

    Ok(None)
}

fn extract_missing_llm_configuration_message(status_payload: &str) -> Option<String> {
    let parsed = serde_json::from_str::<Value>(status_payload).ok()?;
    let process_state = parsed
        .get("processState")
        .and_then(|value| value.as_str())?;
    let status_code = parsed.get("statusCode").and_then(|value| value.as_i64())?;
    if process_state != "done" || status_code < 400 {
        return None;
    }

    let message = parsed
        .get("message")
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())?;
    if is_missing_llm_configuration_message(message) {
        return Some(message.to_string());
    }

    None
}

fn is_missing_llm_configuration_message(message: &str) -> bool {
    let lower = message.to_ascii_lowercase();
    lower.contains("llm is not configured")
        || lower.contains("llm.api.key is not set")
        || (lower.contains("llm") && lower.contains("api key") && lower.contains("not set"))
}

async fn handle_agent_status(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let id = tool_params
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if id.is_empty() {
        return Err("Task ID is required.".to_string());
    }

    let result = get_command_status(client, base_url, id).await?;
    cli_println!("{}", result);
    json_field("task_id", json!(id));
    json_field("raw", json!(serde_json::from_str::<Value>(&result).unwrap_or(Value::String(result.clone()))));
    Ok(())
}

async fn handle_agent_result(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let id = tool_params
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if id.is_empty() {
        return Err("Task ID is required.".to_string());
    }

    let result = get_command_result(client, base_url, id).await?;
    cli_println!("{}", result);
    json_field("task_id", json!(id));
    json_field("raw", json!(&result));
    Ok(())
}

// ---------------------------------------------------------------------------
// Swarm command handlers
// ---------------------------------------------------------------------------

async fn handle_swarm_create(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let capabilities = build_swarm_create_capabilities(tool_params)?;
    let open_args = build_open_session_request(Some(capabilities), Some(SWARM_SESSION_ID));

    let result = call_tool(client, base_url, "open_session", open_args).await?;

    // Parse session ID from response
    let session_id = if let Ok(parsed) = serde_json::from_str::<Value>(&result) {
        parsed
            .get("sessionId")
            .and_then(|v| v.as_str())
            .unwrap_or(SWARM_SESSION_ID)
            .to_string()
    } else {
        let trimmed = result.trim();
        if trimmed.is_empty() {
            SWARM_SESSION_ID.to_string()
        } else {
            trimmed.trim_matches('"').to_string()
        }
    };

    if session_id != SWARM_SESSION_ID {
        return Err(format!(
            "Swarm create must use the fixed session ID '{}', but Browser4 returned '{}'.",
            SWARM_SESSION_ID, session_id
        ));
    };

    let mut state = read_state(None, session_name);
    state.session_name = session_name.map(|s| s.to_string());
    state.session_id = Some(session_id.clone());
    state.base_url = base_url.to_string();
    write_state(&state, None, session_name).map_err(|e| e.to_string())?;

    cli_println!("Swarm session created: {}", session_id);
    Ok(())
}

async fn handle_swarm_submit(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let url = tool_params
        .get("url")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let seed_file = tool_params.get("seedFile").and_then(|v| v.as_str());

    if url.is_empty() && seed_file.is_none() {
        return Err("Either a URL or --seed-file is required.".to_string());
    }

    // Collect URLs to submit
    let mut urls: Vec<String> = Vec::new();
    if !url.is_empty() {
        urls.push(url.to_string());
    }
    if let Some(file_path) = seed_file {
        let content = std::fs::read_to_string(file_path)
            .map_err(|e| format!("Failed to read seed file '{}': {}", file_path, e))?;
        for line in content.lines() {
            let line = line.trim();
            if !line.is_empty() && !line.starts_with('#') {
                urls.push(line.to_string());
            }
        }
    }

    if urls.is_empty() {
        return Err("No URLs to submit.".to_string());
    }

    // Build load options string from flags
    let mut load_opts = Vec::new();
    if let Some(v) = tool_params.get("deadline").and_then(|v| v.as_str()) {
        load_opts.push(format!("-deadline {}", v));
    }
    if let Some(v) = tool_params.get("expires").and_then(|v| v.as_str()) {
        load_opts.push(format!("-expires {}", v));
    }
    if tool_params
        .get("refresh")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        load_opts.push("-refresh".to_string());
    }
    if tool_params
        .get("parse")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        load_opts.push("-parse".to_string());
    }
    if tool_params
        .get("storeContent")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        load_opts.push("-storeContent".to_string());
    }
    let opts_str = load_opts.join(" ");

    // Submit each URL through the scrape REST API.
    let mut json_submissions: Vec<serde_json::Value> = Vec::new();
    for u in &urls {
        let command = if opts_str.is_empty() {
            u.clone()
        } else {
            format!("{} {}", u, opts_str)
        };

        let result = submit_swarm_payload(client, base_url, &command).await?;
        let task_id = result.trim().trim_matches('"').to_string();
        cli_println!("Task Submitted: {} -> Task ID: {}", u, task_id);
        json_submissions.push(json!({
            "url": u,
            "task_id": task_id,
        }));
    }
    json_field("submissions", json!(json_submissions));

    if urls.len() > 1 {
        cli_println!("{} URL(s) submitted.", urls.len());
    }
    Ok(())
}

async fn handle_swarm_status(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let id = tool_params
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if id.is_empty() {
        return Err("Task ID is required.".to_string());
    }

    let result = get_swarm_status(client, base_url, id).await?;
    cli_println!("{}", result);
    json_field("task_id", json!(id));
    json_field("raw", json!(serde_json::from_str::<Value>(&result).unwrap_or(Value::String(result.clone()))));
    Ok(())
}

async fn handle_swarm_result(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let id = tool_params
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if id.is_empty() {
        return Err("Task ID is required.".to_string());
    }

    let result = get_swarm_result(client, base_url, id).await?;
    cli_println!("{}", result);
    json_field("task_id", json!(id));
    json_field("raw", json!(serde_json::from_str::<Value>(&result).unwrap_or(Value::String(result.clone()))));
    Ok(())
}

/// Format the output lines for `handle_install`.  Extracted as a pure function
/// so the branching logic can be unit-tested without network I/O.
fn format_install_output(runtime: &InstalledBrowser4Runtime) -> Vec<String> {
    let mut lines = Vec::new();
    if runtime.reused_existing {
        lines.push("Browser4 runtime already installed.".to_string());
    } else {
        lines.push("Browser4 runtime installed successfully.".to_string());
    }
    lines.push(format!("- Tag: {}", runtime.tag));
    lines.push(format!("- Asset: {}", runtime.asset_name));
    lines.push(format!("- Install dir: {}", runtime.install_dir.display()));
    lines.push(format!("- Lib dir: {}", runtime.lib_dir.display()));
    lines.push(format!("- Java: {}", runtime.java_path.display()));
    lines.push(format!("- Source: {}", runtime.download_url));
    lines
}

async fn handle_install(tool_params: &Value) -> Result<(), String> {
    let tag = tool_params.get("tag").and_then(|value| value.as_str());
    let force = tool_params
        .get("force")
        .and_then(|value| value.as_bool())
        .unwrap_or(false);
    let runtime = install_browser4_runtime(tag, force).await?;
    for line in format_install_output(&runtime) {
        cli_println!("{}", line);
    }

    // Check that a supported browser is available.  Auto-install Chrome on
    // Debian/Ubuntu; print guidance on other platforms.  Failure is non-fatal
    // — the runtime bundle is already installed at this point.
    if let Err(e) = ensure_chrome_available() {
        eprintln!("⚠  Chrome check failed: {e}");
    }

    json_field("tag", json!(&runtime.tag));
    json_field("asset_name", json!(&runtime.asset_name));
    json_field("install_dir", json!(runtime.install_dir.display().to_string()));
    json_field("reused_existing", json!(runtime.reused_existing));
    json_field("source_url", json!(&runtime.download_url));
    Ok(())
}

/// Format the output lines for `handle_upgrade`.  Extracted as a pure function
/// so the branching logic can be unit-tested without network I/O.
fn format_upgrade_output(runtime: &InstalledBrowser4Runtime, force: bool) -> Vec<String> {
    if runtime.reused_existing && !force {
        return vec![format!(
            "Browser4 is already at the latest version ({}).",
            runtime.tag
        )];
    }
    vec![
        format!("Browser4 upgraded successfully to {}.", runtime.tag),
        format!("- Install dir: {}", runtime.install_dir.display()),
        format!("- Lib dir: {}", runtime.lib_dir.display()),
        format!("- Java: {}", runtime.java_path.display()),
        "Restart the server to use the new version: browser4-cli stop && browser4-cli open"
            .to_string(),
    ]
}

async fn handle_upgrade(tool_params: &Value) -> Result<(), String> {
    let tag = tool_params.get("tag").and_then(|value| value.as_str());
    let force = tool_params
        .get("force")
        .and_then(|value| value.as_bool())
        .unwrap_or(false);

    eprintln!("Upgrading Browser4 runtime...");
    let runtime = install_browser4_runtime(tag, force).await?;
    for line in format_upgrade_output(&runtime, force) {
        cli_println!("{}", line);
    }
    json_field("tag", json!(&runtime.tag));
    json_field("asset_name", json!(&runtime.asset_name));
    json_field("install_dir", json!(runtime.install_dir.display().to_string()));
    json_field("reused_existing", json!(runtime.reused_existing));
    json_field("source_url", json!(&runtime.download_url));
    Ok(())
}

async fn handle_stop() -> Result<(), String> {
    eprintln!("🛑 Stopping Browser4 server ...");
    eprintln!();

    let result = stop_browser4_server_forcibly();
    let shutdown_result = result.shutdown;
    finalize_global_cleanup("Stopped", &shutdown_result);

    let server_was_running = !(shutdown_result.stopped_pids.is_empty()
        && shutdown_result.missing_pids.is_empty()
        && shutdown_result.forced_pids.is_empty()
        && shutdown_result.fallback_killed_server_pids.is_empty());
    json_field("server_was_running", json!(server_was_running));
    json_field("server_pids", json!(shutdown_result.stopped_pids));

    eprintln!();

    if !shutdown_result.fallback_killed_server_pids.is_empty() {
        let pids: Vec<String> = shutdown_result
            .fallback_killed_server_pids
            .iter()
            .map(|p| p.to_string())
            .collect();
        cli_println!(
            "Fallback-killed Browser4 backend process(es): {}",
            pids.join(", ")
        );
    }

    if shutdown_result.stopped_pids.is_empty()
        && shutdown_result.missing_pids.is_empty()
        && shutdown_result.forced_pids.is_empty()
        && shutdown_result.fallback_killed_server_pids.is_empty()
    {
        cli_println!("No Browser4 server was running.");
    } else {
        cli_println!("Browser4 server stopped.");
    }
    Ok(())
}

async fn handle_status(client: &Client, base_url: &str) -> Result<(), String> {
    cli_println!("Browser4 Status");
    cli_println!("===============");
    cli_println!("CLI version: {}", VERSION);
    cli_println!("Server URL: {}", base_url);

    json_field("cli_version", json!(VERSION));
    json_field("server_url", json!(base_url));

    // Check installed runtime
    if let Some(metadata) = daemon::read_installed_browser4_runtime_metadata() {
        cli_println!("Installed version: {}", metadata.tag);
        cli_println!("Installed at: {}", metadata.installed_at);
        json_field("installed_version", json!(&metadata.tag));
        json_field("installed_at", json!(&metadata.installed_at));
    } else {
        cli_println!("Installed version: not installed (run 'browser4-cli install')");
        json_field("installed_version", json!(null));
        json_field("installed_at", json!(null));
    }

    // Check server health
    let health_url = format!("{base_url}/actuator/health");
    let health;
    match client.get(&health_url).send().await {
        Ok(response) => {
            if response.status().is_success() {
                match response.text().await {
                    Ok(body) if body.contains("\"status\":\"UP\"") => {
                        cli_println!("Server health: UP");
                        health = "UP";
                    }
                    Ok(body) => {
                        cli_println!("Server health: NOT READY ({})", body);
                        health = "NOT_READY";
                    }
                    Err(e) => {
                        cli_println!("Server health: ERROR ({})", e);
                        health = "ERROR";
                    }
                }
            } else {
                cli_println!("Server health: DOWN (HTTP {})", response.status());
                health = "DOWN";
            }
        }
        Err(_) => {
            cli_println!("Server health: UNREACHABLE (no response from {})", base_url);
            health = "UNREACHABLE";
        }
    }
    json_field("health", json!(health));

    Ok(())
}

fn should_ensure_server_running(command: &str) -> bool {
    command != "close"
        && command != "close-all"
        && command != "kill-all"
        && command != "list"
        && command != "install"
        && command != "upgrade"
        && command != "stop"
        && command != "status"
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/// Rewrite spaced prefixed subcommands like `swarm create` / `agent run`
/// to their internal kebab-case command forms.
fn rewrite_prefixed_command(args: &[String]) -> Option<Vec<String>> {
    let prefix = args.first().map(|s| s.as_str())?;
    let sub = args.get(1)?;
    let rewritten_command = match prefix {
        "swarm" => format!("swarm-{}", sub),
        "agent" => format!("agent-{}", sub),
        _ => return None,
    };
    let mut rewritten = vec![rewritten_command];
    rewritten.extend(args[2..].iter().cloned());
    Some(rewritten)
}

fn preferred_spaced_command_form(command: &str) -> Option<&'static str> {
    match command {
        "agent-run" => Some("agent run"),
        "agent-status" => Some("agent status"),
        "agent-result" => Some("agent result"),
        "swarm-create" => Some("swarm create"),
        "swarm-submit" => Some("swarm submit"),
        "swarm-status" => Some("swarm status"),
        "swarm-result" => Some("swarm result"),
        "co-create" => Some("swarm create"),
        "co-submit" => Some("swarm submit"),
        "co-status" => Some("swarm status"),
        "co-result" => Some("swarm result"),
        _ => None,
    }
}

fn preferred_prefixed_group_form(command: &str) -> Option<&'static str> {
    match command {
        "agent" => Some("agent <subcommand>"),
        "swarm" => Some("swarm <subcommand>"),
        "co" => Some("swarm <subcommand>"),
        _ => None,
    }
}

fn normalize_command_invocation(global: &args::GlobalFlags) -> (String, args::GlobalFlags, bool) {
    if let Some(rewritten) = rewrite_prefixed_command(&global.args) {
        let cmd = rewritten[0].clone();
        let new_global = args::GlobalFlags {
            session_name: global.session_name.clone(),
            server_url: global.server_url.clone(),
            json: global.json,
            quiet: global.quiet,
            args: rewritten,
        };
        (cmd, new_global, true)
    } else {
        let Some(raw_command) = global.args.first() else {
            return (String::new(), global.clone(), false);
        };
        (raw_command.clone(), global.clone(), false)
    }
}

#[derive(Debug, Clone)]
struct BatchCommandSpec {
    display: String,
    tokens: Vec<String>,
}

#[derive(Debug, Clone)]
enum PlannedBatchOutput {
    Text,
    Snapshot { path: PathBuf },
    Screenshot { path: PathBuf },
}

#[derive(Debug, Clone)]
enum PlannedBatchEntry {
    Backend {
        display: String,
        request_indices: Vec<usize>,
        outputs: Vec<PlannedBatchOutput>,
    },
    LocalFailure {
        display: String,
        error: String,
    },
}

#[derive(Debug, Clone)]
struct CompiledBatchRequest {
    steps: Vec<Value>,
    entries: Vec<PlannedBatchEntry>,
    final_state: CliState,
    requires_response_session_id: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BatchExecutionResponse {
    session_id: Option<String>,
    stopped_on_error: bool,
    results: Vec<BatchExecutionResult>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BatchExecutionResult {
    index: usize,
    ok: bool,
    text: Option<String>,
    error: Option<String>,
    page_url: Option<String>,
    page_title: Option<String>,
    snapshot: Option<String>,
    screenshot: Option<String>,
}

fn read_batch_stdin() -> Result<String, String> {
    let mut input = String::new();
    std::io::stdin()
        .read_to_string(&mut input)
        .map_err(|e| format!("Failed to read batch JSON from stdin: {e}"))?;

    if input.trim().is_empty() {
        return Err("Batch --json mode requires JSON input on stdin.".to_string());
    }

    Ok(input)
}

fn format_batch_command(tokens: &[String]) -> String {
    tokens
        .iter()
        .map(|token| {
            if token.chars().any(char::is_whitespace) {
                serde_json::to_string(token).unwrap_or_else(|_| token.clone())
            } else {
                token.clone()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn normalize_batch_step_args(args: &Value) -> Value {
    let mut normalized = args.clone();
    let ref_keys = ["selector", "ref", "startRef", "endRef"];
    if let Value::Object(map) = &mut normalized {
        for key in ref_keys {
            if let Some(Value::String(value)) = map.get(key) {
                map.insert(key.to_string(), json!(resolve_ref(value)));
            }
        }
    }
    normalized
}

fn push_batch_local_failure(
    entries: &mut Vec<PlannedBatchEntry>,
    spec: &BatchCommandSpec,
    error: String,
    bail: bool,
) -> bool {
    entries.push(PlannedBatchEntry::LocalFailure {
        display: spec.display.clone(),
        error,
    });
    bail
}

fn compile_batch_request(
    commands: &[BatchCommandSpec],
    bail: bool,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<CompiledBatchRequest, String> {
    let mut final_state = read_state(None, session_name);
    final_state.base_url = base_url.to_string();

    let mut active_selector = final_state.active_selector.clone();
    let mut last_mouse_position = final_state.last_mouse_position.clone();
    let requires_response_session_id = false;
    let mut steps: Vec<Value> = Vec::new();
    let mut entries: Vec<PlannedBatchEntry> = Vec::new();
    let command_map = commands_map();

    for spec in commands {
        let nested_global = parse_global_flags(&spec.tokens);
        if nested_global.server_url.is_some() {
            if push_batch_local_failure(
                &mut entries,
                spec,
                "Batch subcommands cannot override --server.".to_string(),
                bail,
            ) {
                break;
            }
            continue;
        }
        if nested_global.session_name.is_some() {
            if push_batch_local_failure(
                &mut entries,
                spec,
                "Batch subcommands cannot override -s/--session.".to_string(),
                bail,
            ) {
                break;
            }
            continue;
        }
        let (nested_command, effective_nested_global, _) =
            normalize_command_invocation(&nested_global);
        if nested_command.is_empty() {
            if push_batch_local_failure(
                &mut entries,
                spec,
                "Batch command is empty.".to_string(),
                bail,
            ) {
                break;
            }
            continue;
        }
        if nested_command == "batch" {
            if push_batch_local_failure(
                &mut entries,
                spec,
                "Nested batch commands are not supported.".to_string(),
                bail,
            ) {
                break;
            }
            continue;
        }

        let cmd_def = match command_map.get(&nested_command) {
            Some(def) => def,
            None => {
                if push_batch_local_failure(
                    &mut entries,
                    spec,
                    format!(
                        "Unknown command: {}. Run 'browser4-cli help' for usage.",
                        nested_command
                    ),
                    bail,
                ) {
                    break;
                }
                continue;
            }
        };

        if !cmd_def.batch_supported {
            if push_batch_local_failure(
                &mut entries,
                spec,
                format!(
                    "Command '{}' is not supported in batch mode. Batch mode only supports DOM operations.",
                    nested_command
                ),
                bail,
            ) {
                break;
            }
            continue;
        }

        let raw_parsed = parse_raw_args(&effective_nested_global.args);
        let arg_names: Vec<&str> = cmd_def.args.iter().map(|arg| arg.name).collect();
        let parsed = match build_command_args(&raw_parsed, &arg_names) {
            Ok(parsed) => parsed,
            Err(error) => {
                if push_batch_local_failure(&mut entries, spec, error, bail) {
                    break;
                }
                continue;
            }
        };

        let tool_name = (cmd_def.tool_name_fn)(&parsed);
        let tool_params = (cmd_def.tool_params_fn)(&parsed);

        match nested_command.as_str() {
            "open" | "close" => {
                if push_batch_local_failure(
                    &mut entries,
                    spec,
                    format!(
                        "Batch command only supports DOM operations. '{}' is not allowed. Please execute it separately.",
                        nested_command
                    ),
                    bail,
                ) {
                    break;
                }
                continue;
            }
            "snapshot" => {
                let filename = tool_params.get("filename").and_then(|value| value.as_str());
                let output_path = resolve_output_path(filename, "snapshot", "yml");
                let mut arguments = tool_params.clone();
                if let Value::Object(map) = &mut arguments {
                    map.remove("filename");
                }

                let request_index = steps.len();
                steps.push(json!({
                    "op": "snapshot",
                    "command": spec.display,
                    "tool": tool_name,
                    "arguments": normalize_batch_step_args(&arguments),
                }));
                entries.push(PlannedBatchEntry::Backend {
                    display: spec.display.clone(),
                    request_indices: vec![request_index],
                    outputs: vec![PlannedBatchOutput::Snapshot { path: output_path }],
                });
            }
            "screenshot" => {
                let filename = tool_params.get("filename").and_then(|value| value.as_str());
                let output_path = resolve_output_path(filename, "screenshot", "png");
                let mut arguments = tool_params.clone();
                if let Value::Object(map) = &mut arguments {
                    map.remove("filename");
                }

                let request_index = steps.len();
                steps.push(json!({
                    "op": "screenshot",
                    "command": spec.display,
                    "tool": tool_name,
                    "arguments": normalize_batch_step_args(&arguments),
                }));
                entries.push(PlannedBatchEntry::Backend {
                    display: spec.display.clone(),
                    request_indices: vec![request_index],
                    outputs: vec![PlannedBatchOutput::Screenshot { path: output_path }],
                });
            }
            "press" => {
                let selector = tool_params
                    .get("ref")
                    .and_then(|value| value.as_str())
                    .or_else(|| tool_params.get("selector").and_then(|value| value.as_str()));
                let key = tool_params.get("key").and_then(|value| value.as_str());

                let key = match key {
                    Some(key) => key.to_string(),
                    None => {
                        if push_batch_local_failure(
                            &mut entries,
                            spec,
                            "Press requires a key.".to_string(),
                            bail,
                        ) {
                            break;
                        }
                        continue;
                    }
                };

                let selector = selector.map(ToOwned::to_owned);
                let request_index = steps.len();
                let mut tool_params_json = json!({ "key": key });
                if let Some(selector) = selector.as_deref() {
                    tool_params_json["ref"] = json!(selector);
                }
                let step = json!({
                    "op": "tool",
                    "command": spec.display,
                    "tool": "browser_press_key",
                    "arguments": normalize_batch_step_args(&tool_params_json),
                });
                steps.push(step);
                entries.push(PlannedBatchEntry::Backend {
                    display: spec.display.clone(),
                    request_indices: vec![request_index],
                    outputs: vec![PlannedBatchOutput::Text],
                });

                if let Some(selector) = selector {
                    active_selector = Some(selector);
                    final_state.active_selector = active_selector.clone();
                }
            }
            "list" | "close-all" | "kill-all" | "delete-data" | "agent-run" | "agent-status"
            | "agent-result" | "swarm-create" | "swarm-submit" | "swarm-status"
            | "swarm-result" => {
                if push_batch_local_failure(
                    &mut entries,
                    spec,
                    format!("Batch does not support command '{}'.", nested_command),
                    bail,
                ) {
                    break;
                }
            }
            _ => {
                if tool_name.is_empty() {
                    if push_batch_local_failure(
                        &mut entries,
                        spec,
                        format!("Batch does not support command '{}'.", nested_command),
                        bail,
                    ) {
                        break;
                    }
                    continue;
                }

                let mut step = json!({
                    "op": "tool",
                    "command": spec.display,
                    "tool": tool_name,
                    "arguments": normalize_batch_step_args(&tool_params),
                });

                if matches!(nested_command.as_str(), "keydown" | "keyup") {
                    if let Some(selector) =
                        active_selector
                            .as_deref()
                            .map(str::trim)
                            .filter(|selector| {
                                !selector.is_empty() && !selector.starts_with("backend:")
                            })
                    {
                        step["preFocusSelector"] = json!(selector);
                    }
                }

                if matches!(
                    nested_command.as_str(),
                    "mousedown" | "mouseup" | "mousewheel"
                ) {
                    if let Some(position) = &last_mouse_position {
                        step["preMousePosition"] = json!({
                            "x": position.x,
                            "y": position.y,
                        });
                    }
                }

                let request_index = steps.len();
                steps.push(step);
                entries.push(PlannedBatchEntry::Backend {
                    display: spec.display.clone(),
                    request_indices: vec![request_index],
                    outputs: vec![PlannedBatchOutput::Text],
                });

                if nested_command == "mousemove" {
                    let x = parse_number_arg(&tool_params, "x")?;
                    let y = parse_number_arg(&tool_params, "y")?;
                    last_mouse_position = Some(MousePosition { x, y });
                    final_state.last_mouse_position = last_mouse_position.clone();
                }

                if let Some(selector) = tracked_selector(&tool_params) {
                    active_selector = Some(selector.to_string());
                    final_state.active_selector = active_selector.clone();
                }
            }
        }
    }

    Ok(CompiledBatchRequest {
        steps,
        entries,
        final_state,
        requires_response_session_id,
    })
}

fn render_batch_result(
    output: &PlannedBatchOutput,
    result: &BatchExecutionResult,
) -> Result<(), String> {
    match output {
        PlannedBatchOutput::Text => {
            if let Some(text) = result
                .text
                .as_deref()
                .map(str::trim)
                .filter(|text| !text.is_empty())
            {
                cli_println!("{}", text);
            }
        }
        PlannedBatchOutput::Snapshot { path } => {
            let snapshot = result.snapshot.as_deref().ok_or_else(|| {
                "Batch snapshot response was missing snapshot content.".to_string()
            })?;
            save_snapshot(path, snapshot).map_err(|e| e.to_string())?;
            cli_println!("### Page");
            cli_println!(
                "- Page URL: {}",
                result.page_url.as_deref().unwrap_or_default()
            );
            cli_println!(
                "- Page Title: {}",
                result.page_title.as_deref().unwrap_or_default()
            );
            cli_println!("### Snapshot");
            cli_println!("[Snapshot]({})", path.display());
        }
        PlannedBatchOutput::Screenshot { path } => {
            let encoded = result
                .screenshot
                .as_deref()
                .ok_or_else(|| "Batch screenshot response was missing image data.".to_string())?;
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(encoded.trim())
                .map_err(|e| format!("Failed to decode screenshot: {e}"))?;
            save_binary(path, &bytes).map_err(|e| e.to_string())?;
            cli_println!("[Screenshot]({})", path.display());
        }
    }

    Ok(())
}

fn resolve_batch_commands(
    global: &args::GlobalFlags,
) -> Result<(bool, Vec<BatchCommandSpec>), String> {
    let batch_args = parse_batch_args(&global.args[1..])?;
    let commands = if batch_args.json {
        parse_batch_json_commands(&read_batch_stdin()?)?
            .into_iter()
            .map(|tokens| BatchCommandSpec {
                display: format_batch_command(&tokens),
                tokens,
            })
            .collect()
    } else {
        batch_args
            .commands
            .iter()
            .map(|command| {
                Ok(BatchCommandSpec {
                    display: command.clone(),
                    tokens: parse_command_string(command)?,
                })
            })
            .collect::<Result<Vec<_>, String>>()?
    };

    Ok((batch_args.bail, commands))
}

async fn handle_batch(global: &args::GlobalFlags) -> Result<(), String> {
    let (bail, commands) = resolve_batch_commands(global)?;
    let base_url = resolve_base_url(global.server_url.as_deref(), global.session_name.as_deref());

    if let Some(ref server_url) = global.server_url {
        let current_state = read_state(None, global.session_name.as_deref());
        if server_url != &current_state.base_url {
            let mut updated = current_state;
            updated.base_url = server_url.clone();
            write_state(&updated, None, global.session_name.as_deref())
                .map_err(|e| e.to_string())?;
        }
    }

    ensure_server_running(&base_url).await?;
    let client = make_client();
    let compiled =
        compile_batch_request(&commands, bail, &base_url, global.session_name.as_deref())?;

    let backend_response = if compiled.steps.is_empty() {
        None
    } else {
        let initial_state = read_state(None, global.session_name.as_deref());
        let initial_session_id = initial_state.session_id.filter(|id| !id.trim().is_empty());
        let payload = json!({
            "bail": bail,
            "sessionId": initial_session_id,
            "steps": compiled.steps,
        });
        let raw = submit_batch_commands(&client, &base_url, payload).await?;
        let raw_trimmed = raw.trim();
        if raw_trimmed.is_empty() {
            return Err(
                "Batch backend returned an empty payload. Check that Browser4 server and CLI versions are compatible."
                    .to_string(),
            );
        }
        let preview = if raw_trimmed.chars().count() > 240 {
            let head = raw_trimmed.chars().take(240).collect::<String>();
            format!("{head}...")
        } else {
            raw_trimmed.to_string()
        };
        Some(
            serde_json::from_str::<BatchExecutionResponse>(&raw).map_err(|e| {
                format!("Failed to parse batch response JSON: {e}. Response preview: {preview}")
            })?,
        )
    };

    let mut result_map: HashMap<usize, BatchExecutionResult> = backend_response
        .as_ref()
        .map(|response| {
            response
                .results
                .iter()
                .cloned()
                .map(|result| (result.index, result))
                .collect()
        })
        .unwrap_or_default();

    let mut failures: Vec<String> = Vec::new();
    let mut stop_processing = false;

    for (index, entry) in compiled.entries.iter().enumerate() {
        if stop_processing {
            break;
        }

        match entry {
            PlannedBatchEntry::LocalFailure { display, error } => {
                let failure = format!(
                    "Batch command {} failed ({}): {}",
                    index + 1,
                    display,
                    error
                );
                if bail {
                    stop_processing = true;
                }
                eprintln!("{failure}");
                failures.push(failure);
            }
            PlannedBatchEntry::Backend {
                display,
                request_indices,
                outputs,
            } => {
                let mut command_failed = false;
                for (request_index, output) in request_indices.iter().zip(outputs.iter()) {
                    let Some(result) = result_map.remove(request_index) else {
                        if backend_response
                            .as_ref()
                            .map(|response| response.stopped_on_error)
                            .unwrap_or(false)
                        {
                            stop_processing = true;
                            break;
                        }
                        return Err(format!(
                            "Batch backend response was missing command {} ({}).",
                            index + 1,
                            display
                        ));
                    };

                    if result.ok {
                        render_batch_result(output, &result)?;
                    } else {
                        let failure = format!(
                            "Batch command {} failed ({}): {}",
                            index + 1,
                            display,
                            result
                                .error
                                .as_deref()
                                .unwrap_or("Unknown batch execution error")
                        );
                        eprintln!("{failure}");
                        failures.push(failure);
                        command_failed = true;
                        if bail
                            || backend_response
                                .as_ref()
                                .map(|response| response.stopped_on_error)
                                .unwrap_or(false)
                        {
                            stop_processing = true;
                        }
                        break;
                    }
                }
                if command_failed && bail {
                    stop_processing = true;
                }
            }
        }
    }

    let mut final_state = compiled.final_state.clone();
    if let Some(response) = &backend_response {
        if compiled.requires_response_session_id || response.session_id.is_some() {
            final_state.session_id = response.session_id.clone();
        }
    }
    write_state(&final_state, None, global.session_name.as_deref()).map_err(|e| e.to_string())?;

    if failures.is_empty() {
        Ok(())
    } else {
        Err(format!("{} batch command(s) failed.", failures.len()))
    }
}

#[tokio::main]
async fn main() {
    init_root_search_start_dir_from_startup();

    let raw_args: Vec<String> = std::env::args().skip(1).collect();
    let global = parse_global_flags(&raw_args);
    let json_mode = global.json;
    let (command, effective_global, from_spaced_prefix) = normalize_command_invocation(&global);

    if let Err(e) = run(&command, &effective_global, from_spaced_prefix).await {
        if json_mode {
            // In JSON mode, emit a structured error envelope.
            let error = serde_json::json!({
                "message": e,
                "code": "COMMAND_FAILED"
            });
            cli_println!(
                "{}",
                json_envelope("error", &command, serde_json::json!({}), Some(error))
            );
        } else {
            eprintln!("{}", format_cli_error_output(&e));
        }
        std::process::exit(1);
    }
}

fn format_cli_error_output(error: &str) -> String {
    if error.contains('\n') {
        error.to_string()
    } else {
        format!("Error: {error}")
    }
}

fn json_envelope(
    status: &str,
    command: &str,
    output: serde_json::Value,
    error: Option<serde_json::Value>,
) -> String {
    let mut envelope = serde_json::Map::new();
    envelope.insert("status".to_string(), json!(status));
    envelope.insert("command".to_string(), json!(command));
    if let Some(err) = error {
        envelope.insert("error".to_string(), err);
    }
    envelope.insert("output".to_string(), output);
    serde_json::Value::Object(envelope).to_string()
}

async fn run(
    command: &str,
    global: &args::GlobalFlags,
    from_spaced_prefix: bool,
) -> Result<(), String> {
    // Initialise JSON output accumulator when --json is active.
    if global.json {
        json_init();
    }
    // Initialise quiet mode when -q / --quiet is active.
    quiet_init(global.quiet);

    // Handle help or no command — these always print human-readable text.
    if command.is_empty() || command == "help" || command == "--help" || command == "-h" {
        // Resolve spaced prefixed help targets such as
        // `help swarm create` / `help agent run`.
        let help_args: Vec<String> = global.args.iter().skip(1).cloned().collect();
        let sub = if let Some(rewritten) = rewrite_prefixed_command(&help_args) {
            Some(rewritten[0].clone())
        } else {
            if let Some(target) = global.args.get(1) {
                if let Some(preferred) = preferred_spaced_command_form(target) {
                    return Err(format!(
                        "Unsupported command form: {}. Use 'browser4-cli help {}' instead.",
                        target, preferred
                    ));
                }
            }
            global.args.get(1).cloned()
        };
        print_help(sub.as_deref());
        return Ok(());
    }

    // Handle version
    if command == "--version" || command == "-v" || command == "version" {
        cli_println!("browser4-cli {}", VERSION);
        return Ok(());
    }

    if command == "batch" {
        return handle_batch(global).await;
    }

    if !from_spaced_prefix {
        if let Some(preferred) = preferred_spaced_command_form(command) {
            return Err(format!(
                "Unsupported command form: {}. Use 'browser4-cli {}' instead.",
                command, preferred
            ));
        }
    }

    if let Some(preferred) = preferred_prefixed_group_form(command) {
        return Err(format!(
            "Unsupported command form: {}. Use 'browser4-cli {}' instead.",
            command, preferred
        ));
    }

    // Resolve base URL: --server flag > persisted state > default
    let base_url = resolve_base_url(global.server_url.as_deref(), global.session_name.as_deref());

    // Persist server URL override if different from current state
    if let Some(ref server_url) = global.server_url {
        let current_state = read_state(None, global.session_name.as_deref());
        if server_url != &current_state.base_url {
            let mut updated = current_state;
            updated.base_url = server_url.clone();
            write_state(&updated, None, global.session_name.as_deref())
                .map_err(|e| e.to_string())?;
        }
    }

    // Ensure the Browser4 server is running (for relevant commands)
    if should_ensure_server_running(command) {
        ensure_server_running(&base_url).await?;
    }

    let client = make_client();

    // Look up the command definition
    let cmd_map = commands_map();
    let cmd_def = match cmd_map.get(command) {
        Some(def) => def,
        None => {
            return Err(format!(
                "Unknown command: {}. Run 'browser4-cli help' for usage.",
                command
            ));
        }
    };

    // Parse positional + named arguments
    let raw_parsed = parse_raw_args(&global.args);
    let arg_names: Vec<&str> = cmd_def.args.iter().map(|a| a.name).collect();
    let parsed = build_command_args(&raw_parsed, &arg_names).map_err(|e| e.to_string())?;

    // Resolve tool name and parameters
    let tool_name = (cmd_def.tool_name_fn)(&parsed);
    let tool_params = (cmd_def.tool_params_fn)(&parsed);

    // Dispatch the command
    match command {
        "open" => {
            handle_open(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "goto" => {
            handle_goto(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "close" => {
            handle_close(&client, &base_url, global.session_name.as_deref()).await?;
        }
        "close-all" => {
            handle_close_all(&client, &base_url).await?;
        }
        "kill-all" => {
            handle_kill_all().await?;
        }
        "list" => {
            handle_list(&client, &base_url).await?;
        }
        "install" => {
            handle_install(&tool_params).await?;
        }
        "upgrade" => {
            handle_upgrade(&tool_params).await?;
        }
        "stop" => {
            handle_stop().await?;
        }
        "status" => {
            handle_status(&client, &base_url).await?;
        }
        "delete-data" => {
            handle_delete_data(&client, &base_url, global.session_name.as_deref()).await?;
        }
        "state-save" => {
            handle_state_save(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "state-load" => {
            handle_state_load(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "cookie-list" => {
            handle_cookie_list(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "cookie-get" => {
            handle_cookie_get(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "cookie-set" => {
            handle_cookie_set(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "cookie-delete" => {
            handle_cookie_delete(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "cookie-clear" => {
            handle_cookie_clear(
                &client,
                &base_url,
                &tool_name,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "localstorage-list" => {
            handle_storage_list(
                &client,
                &base_url,
                global.session_name.as_deref(),
                "localStorage",
            )
            .await?;
        }
        "localstorage-get" => {
            handle_storage_get(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                "localStorage",
            )
            .await?;
        }
        "localstorage-set" => {
            handle_storage_set(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                "localStorage",
            )
            .await?;
        }
        "localstorage-delete" => {
            handle_storage_delete(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                "localStorage",
            )
            .await?;
        }
        "localstorage-clear" => {
            handle_storage_clear(
                &client,
                &base_url,
                global.session_name.as_deref(),
                "localStorage",
            )
            .await?;
        }
        "sessionstorage-list" => {
            handle_storage_list(
                &client,
                &base_url,
                global.session_name.as_deref(),
                "sessionStorage",
            )
            .await?;
        }
        "sessionstorage-get" => {
            handle_storage_get(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                "sessionStorage",
            )
            .await?;
        }
        "sessionstorage-set" => {
            handle_storage_set(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                "sessionStorage",
            )
            .await?;
        }
        "sessionstorage-delete" => {
            handle_storage_delete(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                "sessionStorage",
            )
            .await?;
        }
        "sessionstorage-clear" => {
            handle_storage_clear(
                &client,
                &base_url,
                global.session_name.as_deref(),
                "sessionStorage",
            )
            .await?;
        }
        "snapshot" => {
            handle_snapshot(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "screenshot" => {
            handle_screenshot(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "press" => {
            handle_tool_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                false,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "keydown" | "keyup" => {
            handle_key_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "mousemove" => {
            handle_mouse_move(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "mousedown" | "mouseup" | "mousewheel" => {
            handle_mouse_positioned_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        // Agent commands
        "agent-run" => {
            handle_agent_run(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "agent-status" => {
            handle_agent_status(&client, &base_url, &tool_params).await?;
        }
        "agent-result" => {
            handle_agent_result(&client, &base_url, &tool_params).await?;
        }
        // Swarm commands
        "swarm-create" => {
            handle_swarm_create(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "swarm-submit" => {
            handle_swarm_submit(&client, &base_url, &tool_params).await?;
        }
        "swarm-status" => {
            handle_swarm_status(&client, &base_url, &tool_params).await?;
        }
        "swarm-result" => {
            handle_swarm_result(&client, &base_url, &tool_params).await?;
        }
        _ => {
            if tool_name.is_empty() {
                cli_println!("Command '{}' is not yet implemented.", command);
                return Ok(());
            }
            handle_tool_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                matches!(command, "goto"),
                global.session_name.as_deref(),
            )
            .await?;
        }
    }

    // Post-command snapshot for commands that modify browser state
    let no_snap = no_snapshot_commands();
    if !no_snap.contains(command) {
        let state = read_state(None, global.session_name.as_deref());
        if let Some(session_id) = state.session_id {
            post_command_snapshot(&client, &base_url, &session_id).await;
        }
    }

    // Emit JSON envelope when --json is active.
    if global.json {
        if let Some(fields) = json_finish() {
            cli_println!(
                "{}",
                json_envelope("ok", command, serde_json::Value::Object(fields), None)
            );
        }
    }

    Ok(())
}

fn print_help(command_name: Option<&str>) {
    if let Some(name) = command_name {
        if name != "--help" {
            let cmd_map = commands_map();
            if let Some(cmd) = cmd_map.get(name) {
                cli_println!("{}", generate_command_help(cmd));
                return;
            } else {
                eprintln!("Unknown command: {}", name);
            }
        }
    }
    cli_println!("{}", generate_help());
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tempfile::TempDir;

    fn test_temp_dir() -> TempDir {
        let root = std::env::temp_dir()
            .join("browser4")
            .join("browser4-cli")
            .join("main-tests");
        std::fs::create_dir_all(&root).unwrap();
        tempfile::Builder::new()
            .prefix("main-")
            .tempdir_in(&root)
            .unwrap()
    }

    #[test]
    fn no_snapshot_commands_include_eval() {
        assert!(no_snapshot_commands().contains("eval"));
    }

    #[test]
    fn no_snapshot_commands_include_summarize() {
        assert!(no_snapshot_commands().contains("summarize"));
    }

    #[test]
    fn no_snapshot_commands_include_install() {
        assert!(no_snapshot_commands().contains("install"));
    }

    #[test]
    fn no_snapshot_commands_include_state_save() {
        assert!(no_snapshot_commands().contains("state-save"));
    }

    #[test]
    fn no_snapshot_commands_include_state_load() {
        assert!(no_snapshot_commands().contains("state-load"));
    }

    #[test]
    fn no_snapshot_commands_include_cookie_list() {
        assert!(no_snapshot_commands().contains("cookie-list"));
    }

    #[test]
    fn no_snapshot_commands_include_localstorage_set() {
        assert!(no_snapshot_commands().contains("localstorage-set"));
    }

    #[test]
    fn no_snapshot_commands_include_sessionstorage_clear() {
        assert!(no_snapshot_commands().contains("sessionstorage-clear"));
    }

    #[test]
    fn resolve_storage_state_path_uses_current_directory() {
        let tmp = test_temp_dir();
        let previous_dir = std::env::current_dir().unwrap();
        std::env::set_current_dir(tmp.path()).unwrap();
        let resolved = resolve_storage_state_path(Some("auth-state.json")).unwrap();
        std::env::set_current_dir(previous_dir).unwrap();

        assert_eq!(resolved, tmp.path().join("auth-state.json"));
    }

    #[test]
    fn resolve_storage_state_path_defaults_to_timestamped_json_in_current_directory() {
        let tmp = test_temp_dir();
        let previous_dir = std::env::current_dir().unwrap();
        std::env::set_current_dir(tmp.path()).unwrap();
        let resolved = resolve_storage_state_path(None).unwrap();
        std::env::set_current_dir(previous_dir).unwrap();

        assert_eq!(resolved.parent(), Some(tmp.path()));
        assert!(resolved
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with("storage-state-") && name.ends_with(".json")));
    }

    #[test]
    fn tracked_selector_prefers_ref() {
        let params = json!({
            "ref": "#target",
            "selector": "#fallback"
        });

        assert_eq!(tracked_selector(&params), Some("#target"));
    }

    #[test]
    fn tracked_selector_falls_back_to_selector() {
        let params = json!({
            "selector": "#target"
        });

        assert_eq!(tracked_selector(&params), Some("#target"));
    }

    #[test]
    fn tracked_selector_ignores_blank_values() {
        let params = json!({
            "ref": "   "
        });

        assert_eq!(tracked_selector(&params), None);
    }

    #[test]
    fn known_server_base_urls_deduplicate_and_trim_trailing_slashes() {
        let urls = known_server_base_urls(
            "http://127.0.0.1:9222/",
            &[
                ManagedServerProcess {
                    pid: 1,
                    base_url: "http://127.0.0.1:9444/".to_string(),
                    port: 9444,
                    jar_path: "browser4.jar".to_string(),
                    started_at: "2026-04-17T00:00:00Z".to_string(),
                },
                ManagedServerProcess {
                    pid: 2,
                    base_url: "http://127.0.0.1:9222".to_string(),
                    port: 9222,
                    jar_path: "browser4.jar".to_string(),
                    started_at: "2026-04-17T00:00:01Z".to_string(),
                },
            ],
        );

        assert_eq!(
            urls,
            vec![
                "http://127.0.0.1:9222".to_string(),
                "http://127.0.0.1:9444".to_string(),
            ]
        );
    }

    #[test]
    fn build_open_session_capabilities_does_not_default_to_temporary_profile_outside_tests() {
        let caps = build_open_session_capabilities_with_test_mode(&json!({}), false);

        assert!(caps.get("profileMode").is_none());
    }

    #[test]
    fn build_open_session_capabilities_defaults_to_temporary_profile_for_tests() {
        let caps = build_open_session_capabilities_with_test_mode(&json!({}), true);

        assert_eq!(caps["profileMode"], json!("TEMPORARY"));
    }

    #[test]
    fn build_open_session_capabilities_does_not_force_temporary_when_persistent() {
        let caps = build_open_session_capabilities_with_test_mode(
            &json!({
                "persistent": true,
            }),
            true,
        );

        assert_eq!(caps["persistent"], json!(true));
        assert!(caps.get("profileMode").is_none());
    }

    #[test]
    fn build_open_session_capabilities_does_not_force_temporary_when_profile_path_is_set() {
        let caps = build_open_session_capabilities_with_test_mode(
            &json!({
                "profilePath": "C:/tmp/browser-profile",
            }),
            true,
        );

        assert_eq!(caps["profilePath"], json!("C:/tmp/browser-profile"));
        assert!(caps.get("profileMode").is_none());
    }

    #[test]
    fn build_open_session_capabilities_keeps_explicit_profile_mode() {
        let caps = build_open_session_capabilities_with_test_mode(
            &json!({
                "profileMode": "TEMPORARY",
            }),
            false,
        );

        assert_eq!(caps["profileMode"], json!("TEMPORARY"));
    }

    #[test]
    fn build_open_session_capabilities_keeps_interact_level() {
        let caps = build_open_session_capabilities_with_test_mode(
            &json!({
                "interactLevel": "FASTEST",
            }),
            false,
        );

        assert_eq!(caps["interactLevel"], json!("FASTEST"));
    }

    #[test]
    fn build_open_session_request_defaults_to_default_session_id() {
        let request = build_open_session_request(None, None);

        assert_eq!(request["capabilities"]["sessionId"], json!("default"));
    }

    #[test]
    fn build_open_session_request_uses_named_session_id() {
        let request = build_open_session_request(
            Some(json!({
                "profileMode": "SEQUENTIAL",
            })),
            Some("team-a"),
        );

        assert_eq!(request["capabilities"]["sessionId"], json!("team-a"));
        assert_eq!(request["capabilities"]["profileMode"], json!("SEQUENTIAL"));
    }

    #[test]
    fn build_swarm_create_capabilities_defaults_profile_mode_to_sequential() {
        let caps = build_swarm_create_capabilities(&json!({})).unwrap();

        assert_eq!(caps["profileMode"], json!("SEQUENTIAL"));
    }

    #[test]
    fn build_swarm_create_capabilities_treats_blank_profile_mode_as_default() {
        let caps = build_swarm_create_capabilities(&json!({
            "profileMode": "   ",
        }))
        .unwrap();

        assert_eq!(caps["profileMode"], json!("SEQUENTIAL"));
    }

    #[test]
    fn build_swarm_create_capabilities_accepts_supported_profile_modes_case_insensitively() {
        let caps = build_swarm_create_capabilities(&json!({
            "profileMode": "temporary",
            "maxOpenTabs": "8",
            "maxBrowserContexts": "2",
            "displayMode": "HEADLESS",
        }))
        .unwrap();

        assert_eq!(caps["profileMode"], json!("TEMPORARY"));
        assert_eq!(caps["maxOpenTabs"], json!("8"));
        assert_eq!(caps["maxBrowserContexts"], json!("2"));
        assert_eq!(caps["displayMode"], json!("HEADLESS"));
    }

    #[test]
    fn build_swarm_create_capabilities_rejects_unsupported_profile_modes() {
        let error = build_swarm_create_capabilities(&json!({
            "profileMode": "DEFAULT",
        }))
        .unwrap_err();

        assert_eq!(
            error,
            "Swarm create only supports --profile-mode=SEQUENTIAL or --profile-mode=TEMPORARY. Received: DEFAULT"
        );
    }

    #[test]
    fn build_swarm_create_request_uses_fixed_swarm_session_id() {
        let capabilities = build_swarm_create_capabilities(&json!({
            "profileMode": "SEQUENTIAL",
        }))
        .unwrap();
        let request = build_open_session_request(Some(capabilities), Some(SWARM_SESSION_ID));

        assert_eq!(
            request["capabilities"]["sessionId"],
            json!(SWARM_SESSION_ID)
        );
        assert_eq!(request["capabilities"]["profileMode"], json!("SEQUENTIAL"));
    }

    #[test]
    fn should_reuse_open_session_for_default_when_any_session_id_is_saved() {
        assert!(should_reuse_open_session(Some("default"), None));
        assert!(should_reuse_open_session(Some("team-a"), None));
        assert!(should_reuse_open_session(Some("  session-1  "), None));
        assert!(!should_reuse_open_session(Some("   "), None));
    }

    #[test]
    fn should_reuse_open_session_for_named_session_when_the_slot_has_a_saved_session() {
        assert!(should_reuse_open_session(Some("team-a"), Some("team-a")));
        assert!(should_reuse_open_session(
            Some("session-42"),
            Some("team-a")
        ));
        assert!(!should_reuse_open_session(None, Some("team-a")));
    }

    #[test]
    fn should_not_navigate_after_open_for_empty_url() {
        assert!(!should_navigate_after_open(""));
    }

    #[test]
    fn should_not_navigate_after_open_for_about_blank() {
        assert!(!should_navigate_after_open("about:blank"));
    }

    #[test]
    fn should_navigate_after_open_for_non_empty_url() {
        assert!(should_navigate_after_open("https://example.com"));
    }

    #[test]
    fn should_retry_open_after_navigation_error_for_stale_session_errors() {
        assert!(should_retry_open_after_navigation_error(
            "browser_navigate failed: Cannot find context with specified id",
            false
        ));
        assert!(should_retry_open_after_navigation_error(
            "browser_navigate failed: Target closed",
            true
        ));
    }

    #[test]
    fn should_retry_open_after_navigation_error_for_backend_disconnects_only_when_reusing() {
        let error = "HTTP request failed: error sending request for url (http://localhost:8182/mcp/call-tool)";
        assert!(!should_retry_open_after_navigation_error(error, false));
        assert!(should_retry_open_after_navigation_error(error, true));
    }

    #[test]
    fn format_navigation_failure_message_includes_refresh_guidance_for_retryable_errors() {
        let message = format_navigation_failure_message(
            "https://www.amazon.com/",
            "default",
            "HTTP request failed [tool=browser_navigate]: error sending request for url",
            true,
        );

        assert!(message.contains("❌ Navigation failed"));
        assert!(message.contains("  URL: https://www.amazon.com/"));
        assert!(message.contains("  Session: default"));
        assert!(message.contains("💡 What to try"));
        assert!(message.contains("run `browser4-cli open` to refresh the session"));
        assert!(message.contains("🧾 Details"));
        assert!(message.contains("HTTP request failed [tool=browser_navigate]"));
    }

    #[test]
    fn format_navigation_failure_message_adds_timeout_tip_for_timeout_errors() {
        let message = format_navigation_failure_message(
            "https://www.amazon.com/",
            "default",
            "HTTP request timed out [tool=browser_navigate, timeout=120s]: deadline has elapsed",
            true,
        );

        assert!(message.contains("💡 What to try"));
        assert!(message.contains("BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS"));
    }

    #[test]
    fn format_navigation_failure_message_omits_retry_guidance_for_non_retryable_errors() {
        let message = format_navigation_failure_message(
            "https://example.com/",
            "default",
            "browser_navigate failed: invalid URL",
            false,
        );

        assert!(message.contains("❌ Navigation failed"));
        assert!(!message.contains("💡 What to try"));
        assert!(message.contains("🧾 Details"));
        assert!(!message.contains("run `browser4-cli open` to refresh the session"));
        assert!(!message.contains("BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS"));
    }

    #[test]
    fn no_active_session_message_uses_structured_cli_format() {
        let message = no_active_session_message();

        assert!(message.contains("🔐 Session required"));
        assert!(message.contains("💡 What to try"));
        assert!(message.contains("run `browser4-cli open` first."));
        assert!(message.contains("🧾 Details"));
        assert!(message.contains("No active session is currently stored"));
    }

    #[test]
    fn saved_session_expired_message_uses_refresh_guidance() {
        let message = saved_session_expired_message();

        assert!(message.contains("🔐 Session refresh needed"));
        assert!(message.contains("saved session expired or is no longer usable"));
        assert!(message.contains("run `browser4-cli open` to create a fresh session, then retry."));
    }

    #[test]
    fn format_cli_error_output_keeps_structured_multiline_messages_readable() {
        let error = "❌ Navigation failed\n\n🧾 Details\n  HTTP request timed out";

        assert_eq!(format_cli_error_output(error), error);
    }

    #[test]
    fn format_cli_error_output_preserves_prefix_for_simple_errors() {
        assert_eq!(
            format_cli_error_output("Unknown command"),
            "Error: Unknown command"
        );
    }

    #[test]
    fn should_not_ensure_server_for_list() {
        assert!(!should_ensure_server_running("list"));
    }

    #[test]
    fn should_not_ensure_server_for_close() {
        assert!(!should_ensure_server_running("close"));
    }

    #[test]
    fn should_not_ensure_server_for_install() {
        assert!(!should_ensure_server_running("install"));
    }

    #[test]
    fn should_ensure_server_for_open() {
        assert!(should_ensure_server_running("open"));
    }

    #[test]
    fn get_session_id_for_close_returns_none_without_session_id() {
        assert_eq!(get_session_id_for_close(&CliState::default()), None);
    }

    #[test]
    fn get_session_id_for_close_ignores_blank_session_id() {
        let state = CliState {
            session_id: Some("   ".to_string()),
            ..CliState::default()
        };

        assert_eq!(get_session_id_for_close(&state), None);
    }

    #[test]
    fn get_session_id_for_close_preserves_non_session_state() {
        let tmp = test_temp_dir();
        let state = CliState {
            base_url: "http://127.0.0.1:9555".to_string(),
            ..CliState::default()
        };
        write_state(&state, Some(tmp.path()), Some("amazon")).unwrap();

        let read_back = read_state(Some(tmp.path()), Some("amazon"));

        assert_eq!(get_session_id_for_close(&read_back), None);
        assert_eq!(read_back.base_url, "http://127.0.0.1:9555");
    }

    #[test]
    fn normalize_command_invocation_maps_agent_prefix_to_agent_command() {
        let global = args::GlobalFlags {
            session_name: None,
            server_url: None,
            json: false,
            quiet: false,
            args: vec![
                "agent".to_string(),
                "status".to_string(),
                "agent-task-1".to_string(),
            ],
        };

        let (command, normalized, from_spaced_prefix) = normalize_command_invocation(&global);

        assert_eq!(command, "agent-status");
        assert_eq!(normalized.args[0], "agent-status");
        assert_eq!(normalized.args[1], "agent-task-1");
        assert!(from_spaced_prefix);
    }

    #[test]
    fn normalize_command_invocation_leaves_flat_prefixed_forms_unchanged() {
        let global = args::GlobalFlags {
            session_name: None,
            server_url: None,
            json: false,
            quiet: false,
            args: vec!["agent-run".to_string(), "task".to_string()],
        };

        let (command, normalized, from_spaced_prefix) = normalize_command_invocation(&global);

        assert_eq!(command, "agent-run");
        assert_eq!(normalized.args[0], "agent-run");
        assert_eq!(normalized.args[1], "task");
        assert!(!from_spaced_prefix);
    }

    #[test]
    fn rewrite_prefixed_command_supports_agent_run() {
        let rewritten = rewrite_prefixed_command(&[
            "agent".to_string(),
            "run".to_string(),
            "Open example.com".to_string(),
        ])
        .unwrap();

        assert_eq!(rewritten[0], "agent-run");
        assert_eq!(rewritten[1], "Open example.com");
    }

    #[test]
    fn rewrite_prefixed_command_rejects_legacy_co_prefix() {
        assert!(rewrite_prefixed_command(&["co".to_string(), "create".to_string(),]).is_none());
    }

    #[test]
    fn preferred_spaced_command_form_maps_flat_aliases() {
        assert_eq!(
            preferred_spaced_command_form("agent-run"),
            Some("agent run")
        );
        assert_eq!(
            preferred_spaced_command_form("swarm-submit"),
            Some("swarm submit")
        );
        assert_eq!(
            preferred_spaced_command_form("co-status"),
            Some("swarm status")
        );
        assert_eq!(preferred_spaced_command_form("goto"), None);
    }

    #[test]
    fn preferred_prefixed_group_form_maps_legacy_and_bare_prefixes() {
        assert_eq!(
            preferred_prefixed_group_form("agent"),
            Some("agent <subcommand>")
        );
        assert_eq!(
            preferred_prefixed_group_form("swarm"),
            Some("swarm <subcommand>")
        );
        assert_eq!(
            preferred_prefixed_group_form("co"),
            Some("swarm <subcommand>")
        );
        assert_eq!(preferred_prefixed_group_form("open"), None);
    }

    #[test]
    fn compile_batch_request_press_uses_browser_press_key_tool_step() {
        let commands = vec![BatchCommandSpec {
            display: "press ! #type-target".to_string(),
            tokens: vec![
                "press".to_string(),
                "!".to_string(),
                "#type-target".to_string(),
            ],
        }];

        let compiled =
            compile_batch_request(&commands, false, "http://127.0.0.1:8182", None).unwrap();

        assert_eq!(compiled.steps.len(), 1);
        assert_eq!(compiled.steps[0]["op"], json!("tool"));
        assert_eq!(compiled.steps[0]["tool"], json!("browser_press_key"));
        assert_eq!(compiled.steps[0]["arguments"]["ref"], json!("#type-target"));
        assert_eq!(compiled.steps[0]["arguments"]["key"], json!("!"));
    }

    #[test]
    fn compile_batch_request_press_without_selector_uses_focused_element() {
        let commands = vec![BatchCommandSpec {
            display: "press Enter".to_string(),
            tokens: vec!["press".to_string(), "Enter".to_string()],
        }];

        let compiled =
            compile_batch_request(&commands, false, "http://127.0.0.1:8182", None).unwrap();

        assert_eq!(compiled.steps.len(), 1);
        assert_eq!(compiled.steps[0]["op"], json!("tool"));
        assert_eq!(compiled.steps[0]["tool"], json!("browser_press_key"));
        assert_eq!(compiled.steps[0]["arguments"]["key"], json!("Enter"));
        assert!(compiled.steps[0]["arguments"].get("ref").is_none());
    }

    #[test]
    fn unreachable_backend_error_detection_matches_connection_failures() {
        assert!(is_backend_unreachable_error(
            "HTTP request failed: error sending request for url: tcp connect error: Connection refused"
        ));
        assert!(!is_backend_unreachable_error(
            "Tool execution failed: invalid arguments"
        ));
    }

    #[test]
    fn parse_active_session_ids_supports_backend_object_payloads() {
        let ids = parse_active_session_ids(
            r#"[{"sessionId":"session-1","url":"https://example.com","status":"active"},{"sessionId":"session-2","url":"","status":"active"}]"#,
        );

        assert_eq!(ids, vec!["session-1".to_string(), "session-2".to_string()]);
    }

    #[test]
    fn parse_active_session_ids_ignores_non_active_backend_object_payloads() {
        let ids = parse_active_session_ids(
            r#"[{"sessionId":"session-1","url":"https://example.com","status":"active"},{"sessionId":"session-2","url":"","status":"stopped"}]"#,
        );

        assert_eq!(ids, vec!["session-1".to_string()]);
    }

    #[test]
    fn parse_active_session_ids_keeps_legacy_string_payloads() {
        let ids = parse_active_session_ids(r#"["session-1","session-2"]"#);

        assert_eq!(ids, vec!["session-1".to_string(), "session-2".to_string()]);
    }

    #[test]
    fn session_is_active_requires_active_status_for_backend_objects() {
        let listed_sessions = r#"[{"sessionId":"session-1","status":"stopped"},{"sessionId":"session-2","status":"active"}]"#;

        assert!(!session_is_active(listed_sessions, "session-1"));
        assert!(session_is_active(listed_sessions, "session-2"));
        assert!(!session_is_active(listed_sessions, "missing-session"));
    }

    #[test]
    fn list_session_status_marks_backend_reachable_active_and_stale_sessions() {
        let records = vec![
            BackendSessionRecord {
                session_id: "session-1".to_string(),
                status: Some("active".to_string()),
            },
            BackendSessionRecord {
                session_id: "session-2".to_string(),
                status: Some("stopped".to_string()),
            },
        ];

        assert_eq!(list_session_status(Some(&records), "session-1"), "Active");
        assert_eq!(list_session_status(Some(&records), "session-2"), "Stale");
        assert_eq!(list_session_status(Some(&records), "missing"), "Stale");
    }

    #[test]
    fn list_session_status_marks_backend_unreachable_sessions_unknown() {
        assert_eq!(list_session_status(None, "session-1"), "Unknown");
    }

    #[test]
    fn list_session_next_open_action_matches_reuse_and_refresh_behavior() {
        let records = vec![
            BackendSessionRecord {
                session_id: "session-1".to_string(),
                status: Some("active".to_string()),
            },
            BackendSessionRecord {
                session_id: "session-2".to_string(),
                status: Some("stopped".to_string()),
            },
        ];

        assert_eq!(
            list_session_next_open_action(Some(&records), "session-1"),
            "Reuse"
        );
        assert_eq!(
            list_session_next_open_action(Some(&records), "session-2"),
            "Refresh"
        );
        assert_eq!(
            list_session_next_open_action(Some(&records), "missing"),
            "Refresh"
        );
        assert_eq!(list_session_next_open_action(None, "session-1"), "Refresh");
    }

    #[test]
    fn extract_missing_llm_configuration_message_matches_done_failures() {
        let status = json!({
            "id": "agent-task-1",
            "statusCode": 417,
            "processState": "done",
            "message": "The LLM is not configured, see docs/config/llm/llm-config.md"
        })
        .to_string();

        assert_eq!(
            extract_missing_llm_configuration_message(&status),
            Some("The LLM is not configured, see docs/config/llm/llm-config.md".to_string())
        );
    }

    #[test]
    fn extract_missing_llm_configuration_message_ignores_non_llm_failures() {
        let status = json!({
            "id": "agent-task-1",
            "statusCode": 417,
            "processState": "done",
            "message": "Browser crashed before agent execution started"
        })
        .to_string();

        assert_eq!(extract_missing_llm_configuration_message(&status), None);
    }

    #[test]
    fn extract_missing_llm_configuration_message_ignores_in_progress_status() {
        let status = json!({
            "id": "agent-task-1",
            "statusCode": 102,
            "processState": "in_progress",
            "message": "The LLM is not configured, see docs/config/llm/llm-config.md"
        })
        .to_string();

        assert_eq!(extract_missing_llm_configuration_message(&status), None);
    }

    // -----------------------------------------------------------------------
    // is_backend_unreachable_error
    // -----------------------------------------------------------------------

    #[test]
    fn is_backend_unreachable_detects_connection_refused() {
        assert!(is_backend_unreachable_error("Connection refused (os error 61)"));
        assert!(is_backend_unreachable_error("connection refused"));
    }

    #[test]
    fn is_backend_unreachable_detects_tcp_errors() {
        assert!(is_backend_unreachable_error("tcp connect error"));
        assert!(is_backend_unreachable_error("error sending request"));
        assert!(is_backend_unreachable_error("failed to connect to the server"));
    }

    #[test]
    fn is_backend_unreachable_detects_timeout() {
        assert!(is_backend_unreachable_error("request timed out after 30s"));
    }

    #[test]
    fn is_backend_unreachable_rejects_other_errors() {
        assert!(!is_backend_unreachable_error("invalid URL"));
        assert!(!is_backend_unreachable_error("session not found"));
        assert!(!is_backend_unreachable_error("browser_navigate failed"));
    }

    // -----------------------------------------------------------------------
    // get_session_id_for_close (additional edge cases)
    // -----------------------------------------------------------------------

    #[test]
    fn get_session_id_for_close_handles_whitespace_only_session_id() {
        let state = CliState {
            session_id: Some("   ".to_string()),
            ..CliState::default()
        };
        assert_eq!(get_session_id_for_close(&state), None);
    }

    // -----------------------------------------------------------------------
    // session_status helpers
    // -----------------------------------------------------------------------

    #[test]
    fn list_session_status_active() {
        let records = vec![BackendSessionRecord {
            session_id: "s1".to_string(),
            status: Some("active".to_string()),
        }];
        assert_eq!(list_session_status(Some(&records), "s1"), "Active");
    }

    #[test]
    fn list_session_status_stopped() {
        let records = vec![BackendSessionRecord {
            session_id: "s1".to_string(),
            status: Some("stopped".to_string()),
        }];
        assert_eq!(list_session_status(Some(&records), "s1"), "Stale");
    }

    #[test]
    fn list_session_status_unknown_session_id() {
        let records = vec![BackendSessionRecord {
            session_id: "s1".to_string(),
            status: Some("active".to_string()),
        }];
        // s2 is not in the backend records → Stale
        assert_eq!(list_session_status(Some(&records), "s2"), "Stale");
    }

    #[test]
    fn list_session_status_no_backend() {
        // Backend unreachable → Unknown
        assert_eq!(list_session_status(None, "s1"), "Unknown");
    }

    #[test]
    fn list_session_status_empty_backend() {
        // Backend returned no sessions → Stale
        assert_eq!(list_session_status(Some(&[]), "s1"), "Stale");
    }

    // -----------------------------------------------------------------------
    // session_status_is_active
    // -----------------------------------------------------------------------

    #[test]
    fn session_status_is_active_variants() {
        assert!(session_status_is_active(Some("active")));
        assert!(session_status_is_active(Some("ACTIVE")));
        assert!(!session_status_is_active(Some("stopped")));
        assert!(!session_status_is_active(Some("error")));
        // None (missing status) defaults to true — backend records without
        // explicit status fields are treated as active.
        assert!(session_status_is_active(None));
    }

    // -------------------------------------------------------------------
    // format_install_output / format_upgrade_output
    // -------------------------------------------------------------------

    fn make_test_runtime(reused: bool) -> InstalledBrowser4Runtime {
        InstalledBrowser4Runtime {
            tag: "v4.10.0".to_string(),
            asset_name: "browser4-bundle-runtime-linux-x64.tar.gz".to_string(),
            download_url: "https://github.com/platonai/Browser4/releases/download/v4.10.0/bundle.tar.gz".to_string(),
            install_dir: PathBuf::from("/tmp/browser4/lib"),
            lib_dir: PathBuf::from("/tmp/browser4/lib"),
            jar_path: PathBuf::from("/tmp/browser4/lib/browser4.jar"),
            java_path: PathBuf::from("/tmp/browser4/lib/runtime/bin/java"),
            reused_existing: reused,
        }
    }

    #[test]
    fn test_format_install_output_reused() {
        let runtime = make_test_runtime(true);
        let lines = format_install_output(&runtime);
        assert!(lines[0].contains("already installed"), "expected 'already installed', got: {:?}", lines);
        assert!(lines.iter().any(|l| l.contains("v4.10.0")), "expected tag in output");
        assert!(lines.iter().any(|l| l.contains("- Install dir:")), "expected install dir");
    }

    #[test]
    fn test_format_install_output_fresh() {
        let runtime = make_test_runtime(false);
        let lines = format_install_output(&runtime);
        assert!(lines[0].contains("installed successfully"), "expected 'installed successfully', got: {:?}", lines);
    }

    #[test]
    fn test_format_upgrade_output_already_latest() {
        let runtime = make_test_runtime(true);
        let lines = format_upgrade_output(&runtime, false);
        assert_eq!(lines.len(), 1);
        assert!(lines[0].contains("already at the latest version"), "got: {}", lines[0]);
        assert!(lines[0].contains("v4.10.0"), "expected tag in message: {}", lines[0]);
    }

    #[test]
    fn test_format_upgrade_output_fresh_install() {
        let runtime = make_test_runtime(false);
        let lines = format_upgrade_output(&runtime, false);
        assert!(lines[0].contains("upgraded successfully"), "got: {}", lines[0]);
        assert!(lines.iter().any(|l| l.contains("Restart the server")));
    }

    #[test]
    fn test_format_upgrade_output_force_reinstall() {
        let runtime = make_test_runtime(true); // reused_existing = true
        let lines = format_upgrade_output(&runtime, true); // force = true → not "already latest"
        assert!(lines[0].contains("upgraded successfully"), "force=true should not print 'already latest': {:?}", lines);
        assert!(lines.iter().any(|l| l.contains("Restart the server")));
    }
}
