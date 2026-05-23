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
use serde::Deserialize;
use serde_json::{json, Value};

use args::{
    build_command_args, parse_batch_args, parse_batch_json_commands, parse_command_string,
    parse_global_flags, parse_raw_args,
};
use commands::commands_map;
use daemon::{ensure_server_running, init_root_search_start_dir_from_startup, resolve_base_url};
use help::{generate_command_help, generate_help};
use http::{
    call_tool, get_command_result, get_command_status, get_scrape_result, get_scrape_status,
    is_stale_session_error, make_client, submit_batch_commands, submit_plain_command,
    submit_scrape_payload,
};
use managed_processes::{
    read_managed_server_processes, stop_browser4_server_forcibly, ManagedServerProcess,
    ShutdownResult,
};
use snapshot::{resolve_output_path, save_binary, save_snapshot};
use state::{
    clear_all_state, clear_state, read_state, resolve_default_state_dir, resolve_ref, write_state,
    CliState, MousePosition,
};

const VERSION: &str = env!("CARGO_PKG_VERSION");
const TEST_TEMPORARY_PROFILE_ENV: &str = "BROWSER4_CLI_TEST_TEMPORARY_PROFILE";
const AGENT_RUN_FAILURE_POLL_ATTEMPTS: usize = 5;
const AGENT_RUN_FAILURE_POLL_INTERVAL_MS: u64 = 250;
const SWARM_SESSION_ID: &str = "SWARM";

/// Commands that should NOT trigger a post-command snapshot.
fn no_snapshot_commands() -> HashSet<&'static str> {
    [
        "open",
        "goto",
        "close",
        "close-all",
        "kill-all",
        "list",
        "help",
        "eval",
        "summarize",
        "snapshot",
        "screenshot",
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

fn goto_requires_active_session_message() -> String {
    format_session_guidance_message(
        "Active session required",
        Some("goto"),
        "`goto` needs an active reusable session, but none is available right now.",
        &["run `browser4-cli open` to create or refresh the session first."],
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

    println!("### Page");
    println!("- Page URL: {}", url_result);
    println!("- Page Title: {}", title_result);
    println!("### Snapshot");
    println!("[Snapshot]({})", out_path.display());
}

// ---------------------------------------------------------------------------
// Command handlers
// ---------------------------------------------------------------------------

async fn handle_open(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
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
        println!("Session opened: {}", new_id);
        new_id
    };

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
                    println!("Session already open: {}", session_id);
                }
                if !result.is_empty() {
                    println!("{}", result);
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
                // The browser context was not ready yet (CDP initialization race).
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
                println!("Session opened: {}", retry_id);
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
                    println!("{}", retry_result);
                }
                post_command_snapshot(client, base_url, &retry_id).await;
            }
        }
    } else if reused_existing_session {
        println!("Session already open: {}", session_id);
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
    let session_id = require_active_session_for_goto(client, base_url, session_name).await?;
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
                println!("{}", result);
            }
            post_command_snapshot(client, base_url, &session_id).await;
        }
        Err(err) => {
            let should_suggest_refresh = should_retry_open_after_navigation_error(&err, true);
            println!(
                "{}",
                format_navigation_failure_message(
                    target_url,
                    &session_id,
                    &err,
                    should_suggest_refresh,
                )
            );
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
        return Ok(());
    };
    // Ignore errors — session might already be closed
    let _ = call_tool(
        client,
        base_url,
        "close_session",
        json!({ "sessionId": session_id }),
    )
    .await;
    clear_state(None, session_name);
    println!("Session closed.");
    Ok(())
}

async fn handle_close_all(client: &Client, base_url: &str) -> Result<(), String> {
    let close_summary = close_all_sessions_across_servers(client, base_url).await;

    // `close-all` is intentionally session-scoped. Keep any tracked Browser4
    // backend process alive so callers can continue using the same service and
    // reserve JVM shutdown for the explicit `kill-all` flow.
    clear_all_state(None);

    log_close_all_summary(&close_summary, "close-all");
    Ok(())
}

async fn handle_kill_all() -> Result<(), String> {
    let result = stop_browser4_server_forcibly();
    let shutdown_result = result.shutdown;
    finalize_global_cleanup("Killed", &shutdown_result);

    if !shutdown_result.fallback_killed_server_pids.is_empty() {
        let pids: Vec<String> = shutdown_result
            .fallback_killed_server_pids
            .iter()
            .map(|p| p.to_string())
            .collect();
        println!(
            "Fallback-killed Browser4 backend process(es): {}",
            pids.join(", ")
        );
    }

    let browser_result = result.browser_kill;
    if !browser_result.killed_pids.is_empty() {
        let pids: Vec<String> = browser_result
            .killed_pids
            .iter()
            .map(|p| p.to_string())
            .collect();
        println!(
            "Killed found Browser4 browser process(es): {}",
            pids.join(", ")
        );
    }

    if !browser_result.remaining_pids.is_empty() {
        let pids: Vec<String> = browser_result
            .remaining_pids
            .iter()
            .map(|p| p.to_string())
            .collect();
        return Err(format!(
            "Browser cleanup incomplete. Remaining Browser4 browser process(es): {}",
            pids.join(", ")
        ));
    }

    Ok(())
}

#[derive(Debug, Default, PartialEq, Eq)]
struct CloseAllSummary {
    results: Vec<String>,
    errors: Vec<String>,
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
        println!(
            "No reachable Browser4 servers responded to {}.",
            command_name
        );
    } else {
        for result in &summary.results {
            println!("{}", result);
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
        println!("{} Browser4 process(es): {}", action, pids.join(", "));
    } else if result.missing_pids.is_empty() {
        println!("No tracked Browser4 processes found.");
    }

    if !result.missing_pids.is_empty() {
        let pids: Vec<String> = result.missing_pids.iter().map(|p| p.to_string()).collect();
        println!("Already stopped Browser4 process(es): {}", pids.join(", "));
    }

    if !result.forced_pids.is_empty() && action == "Stopped" {
        let pids: Vec<String> = result.forced_pids.iter().map(|p| p.to_string()).collect();
        println!(
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

    println!(
        "{:<20} | {:<40} | {:<8} | {}",
        "Name", "Session ID", "Status", "Next open"
    );
    println!("{:-<20}-+-{:-<40}-+-{:-<8}-+-{:-<9}", "", "", "", "");

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
                                println!(
                                    "{:<20} | {:<40} | {:<8} | {}",
                                    name, sid, status, next_open
                                );
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
        println!(
            "{:<20} | {:<40} | {:<8} | {}",
            "(default)", sid, status, next_open
        );
    }

    if let Some(note) = backend_note {
        println!("\n{}", note);
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

async fn require_active_session_for_goto(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<String, String> {
    let state = read_state(None, session_name);

    find_reusable_persisted_session_id(client, base_url, &state, session_name)
        .await?
        .ok_or_else(goto_requires_active_session_message)
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
        println!("Session data deleted.");
    } else {
        println!("{}", result);
    }
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

    println!("### Page");
    println!("- Page URL: {}", url);
    println!("- Page Title: {}", title);
    println!("### Snapshot");
    println!("[Snapshot]({})", out_path.display());
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
    println!("[Screenshot]({})", out_path.display());
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
        println!("{}", result);
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
    println!("Task submitted: {}", task_id);
    println!(
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
    println!("{}", result);
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
    println!("{}", result);
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

    println!("Swarm session created: {}", session_id);
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
    for u in &urls {
        let command = if opts_str.is_empty() {
            u.clone()
        } else {
            format!("{} {}", u, opts_str)
        };

        let result = submit_scrape_payload(client, base_url, &command).await?;
        let task_id = result.trim().trim_matches('"').to_string();
        println!("Task Submitted: {} -> Task ID: {}", u, task_id);
    }

    if urls.len() > 1 {
        println!("{} URL(s) submitted.", urls.len());
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

    let result = get_scrape_status(client, base_url, id).await?;
    println!("{}", result);
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

    let result = get_scrape_result(client, base_url, id).await?;
    println!("{}", result);
    Ok(())
}

fn should_ensure_server_running(command: &str) -> bool {
    command != "close" && command != "close-all" && command != "kill-all" && command != "list"
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
            use_maven_startup: global.use_maven_startup,
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
        if nested_global.use_maven_startup {
            if push_batch_local_failure(
                &mut entries,
                spec,
                "Batch subcommands cannot override --use-maven-startup.".to_string(),
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
                println!("{}", text);
            }
        }
        PlannedBatchOutput::Snapshot { path } => {
            let snapshot = result.snapshot.as_deref().ok_or_else(|| {
                "Batch snapshot response was missing snapshot content.".to_string()
            })?;
            save_snapshot(path, snapshot).map_err(|e| e.to_string())?;
            println!("### Page");
            println!(
                "- Page URL: {}",
                result.page_url.as_deref().unwrap_or_default()
            );
            println!(
                "- Page Title: {}",
                result.page_title.as_deref().unwrap_or_default()
            );
            println!("### Snapshot");
            println!("[Snapshot]({})", path.display());
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
            println!("[Screenshot]({})", path.display());
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

    ensure_server_running(&base_url, global.use_maven_startup).await?;
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
    let (command, effective_global, from_spaced_prefix) = normalize_command_invocation(&global);

    if let Err(e) = run(&command, &effective_global, from_spaced_prefix).await {
        eprintln!("{}", format_cli_error_output(&e));
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

async fn run(
    command: &str,
    global: &args::GlobalFlags,
    from_spaced_prefix: bool,
) -> Result<(), String> {
    // Handle help or no command
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
        println!("browser4-cli {}", VERSION);
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
        ensure_server_running(&base_url, global.use_maven_startup).await?;
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
        "delete-data" => {
            handle_delete_data(&client, &base_url, global.session_name.as_deref()).await?;
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
                println!("Command '{}' is not yet implemented.", command);
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

    Ok(())
}

fn print_help(command_name: Option<&str>) {
    if let Some(name) = command_name {
        if name != "--help" {
            let cmd_map = commands_map();
            if let Some(cmd) = cmd_map.get(name) {
                println!("{}", generate_command_help(cmd));
                return;
            } else {
                eprintln!("Unknown command: {}", name);
            }
        }
    }
    println!("{}", generate_help());
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

        assert_eq!(request["capabilities"]["sessionId"], json!(SWARM_SESSION_ID));
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
    fn goto_requires_active_session_message_mentions_command_name() {
        let message = goto_requires_active_session_message();

        assert!(message.contains("🔐 Active session required"));
        assert!(message.contains("Command: goto"));
        assert!(message.contains("run `browser4-cli open` to create or refresh the session first."));
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
    fn normalize_command_invocation_preserves_use_maven_startup() {
        let global = args::GlobalFlags {
            session_name: Some("team".to_string()),
            server_url: Some("http://127.0.0.1:8182".to_string()),
            use_maven_startup: true,
            args: vec!["swarm".to_string(), "create".to_string()],
        };

        let (command, normalized, from_spaced_prefix) = normalize_command_invocation(&global);

        assert_eq!(command, "swarm-create");
        assert!(normalized.use_maven_startup);
        assert!(from_spaced_prefix);
    }

    #[test]
    fn normalize_command_invocation_maps_agent_prefix_to_agent_command() {
        let global = args::GlobalFlags {
            session_name: None,
            server_url: None,
            use_maven_startup: false,
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
            use_maven_startup: false,
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
    fn compile_batch_request_rejects_nested_use_maven_startup_override() {
        let commands = vec![BatchCommandSpec {
            display: "--use-maven-startup open https://example.com".to_string(),
            tokens: vec![
                "--use-maven-startup".to_string(),
                "open".to_string(),
                "https://example.com".to_string(),
            ],
        }];

        let compiled =
            compile_batch_request(&commands, false, "http://127.0.0.1:8182", None).unwrap();

        assert_eq!(compiled.entries.len(), 1);
        match &compiled.entries[0] {
            PlannedBatchEntry::LocalFailure { error, .. } => {
                assert_eq!(
                    error,
                    "Batch subcommands cannot override --use-maven-startup."
                );
            }
            other => panic!("expected local failure entry, got {other:?}"),
        }
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
}
