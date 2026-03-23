//! Browser4 CLI — drive a Browser4 server from the command line.
//!
//! All operations are routed through the Browser4 MCP Server tool interface
//! via `POST /mcp/call-tool`.
//!
//! # State persistence
//! The active session ID and server URL are kept in `~/.browser4/cli-state.json`
//! between invocations.
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

use std::collections::HashSet;

use base64::Engine;
use reqwest::Client;
use serde_json::{json, Value};

use args::{build_command_args, parse_global_flags, parse_raw_args};
use commands::commands_map;
use daemon::{ensure_server_running, resolve_base_url};
use help::{generate_command_help, generate_help};
use http::{call_tool, is_stale_session_error, make_client};
use managed_processes::{
    read_managed_server_processes, shutdown_managed_server_processes, ShutdownResult,
};
use snapshot::{resolve_output_path, save_binary, save_snapshot};
use state::{clear_state, read_state, write_state, CliState};

const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Commands that should NOT trigger a post-command snapshot.
fn no_snapshot_commands() -> HashSet<&'static str> {
    [
        "open", "close", "close-all", "kill-all", "list", "help", "snapshot", "screenshot", "pdf",
    ]
    .into()
}

// ---------------------------------------------------------------------------
// Session helpers
// ---------------------------------------------------------------------------

fn require_session() -> Result<CliState, String> {
    let state = read_state(None);
    if state.session_id.is_none() {
        return Err(
            r#"No active session. Run "browser4-cli open" first."#.to_string(),
        );
    }
    Ok(state)
}

fn get_session_id(state: &CliState) -> Result<&str, String> {
    state
        .session_id
        .as_deref()
        .ok_or_else(|| r#"No active session. Run "browser4-cli open" first."#.to_string())
}

async fn create_session(client: &Client, base_url: &str, state: &CliState, capabilities: Option<Value>) -> Result<String, String> {
    let params = match capabilities {
        Some(caps) => json!({ "capabilities": caps }),
        None => json!({}),
    };
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
    write_state(&new_state, None).map_err(|e| e.to_string())?;
    Ok(session_id)
}

fn invalidate_session(state: &CliState, base_url: &str) {
    let mut new_state = state.clone();
    new_state.session_id = None;
    new_state.base_url = base_url.to_string();
    let _ = write_state(&new_state, None);
}

/// Execute an action with the current session, recovering stale sessions if requested.
async fn with_session<F, Fut>(
    client: &Client,
    base_url: &str,
    recover_stale: bool,
    action: F,
) -> Result<String, String>
where
    F: Fn(String) -> Fut + Send,
    Fut: std::future::Future<Output = Result<String, String>> + Send,
{
    let state = require_session()?;
    let session_id = get_session_id(&state)?.to_string();

    match action(session_id.clone()).await {
        Ok(result) => Ok(result),
        Err(err) => {
            if !is_stale_session_error(&err) {
                return Err(err);
            }
            invalidate_session(&state, base_url);
            if !recover_stale {
                return Err(r#"Saved session expired. Run "browser4-cli open" first."#.to_string());
            }
            let new_session_id = create_session(client, base_url, &state, None).await?;
            action(new_session_id).await
        }
    }
}

// ---------------------------------------------------------------------------
// Post-command snapshot
// ---------------------------------------------------------------------------

async fn post_command_snapshot(client: &Client, base_url: &str, session_id: &str) {
    let (page_url, page_title, snapshot_content) = tokio::join!(
        call_tool(client, base_url, "page_url", json!({ "sessionId": session_id })),
        call_tool(client, base_url, "page_title", json!({ "sessionId": session_id })),
        call_tool(client, base_url, "browser_snapshot", json!({ "sessionId": session_id })),
    );

    let (url_result, title_result, snap_result) =
        match (page_url, page_title, snapshot_content) {
            (Ok(u), Ok(t), Ok(s)) => (u, t, s),
            _ => return, // silently ignore failures (e.g. session just closed)
        };

    let out_path = resolve_output_path(None, "page", "yml");
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
    parsed_args: &std::collections::HashMap<String, Value>,
) -> Result<(), String> {
    let mut state = read_state(None);
    state.session_name = session_name.map(|s| s.to_string());

    // Build capabilities from --persistent and --profile flags
    let mut caps = serde_json::Map::new();
    if let Some(Value::Bool(true)) = parsed_args.get("persistent") {
        caps.insert("persistent".to_string(), json!(true));
    }
    if let Some(Value::String(profile)) = parsed_args.get("profile") {
        caps.insert("profile".to_string(), json!(profile));
    }
    let capabilities = if caps.is_empty() { None } else { Some(Value::Object(caps)) };

    let session_id = create_session(client, base_url, &state, capabilities).await?;

    let url = tool_params.get("url").and_then(|u| u.as_str()).unwrap_or("about:blank");
    if !url.is_empty() && url != "about:blank" {
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        let result = call_tool(client, base_url, tool_name, params).await?;
        if !result.is_empty() {
            println!("{}", result);
        }
    } else {
        println!("Session opened: {}", session_id);
    }
    Ok(())
}

async fn handle_close(client: &Client, base_url: &str) -> Result<(), String> {
    let state = require_session()?;
    let session_id = get_session_id(&state)?.to_string();
    // Ignore errors — session might already be closed
    let _ = call_tool(
        client,
        base_url,
        "close_session",
        json!({ "sessionId": session_id }),
    )
    .await;
    clear_state(None);
    println!("Session closed.");
    Ok(())
}

async fn handle_close_all(client: &Client, base_url: &str) -> Result<(), String> {
    // Collect all known base URLs (current + managed processes)
    let mut base_urls = std::collections::HashSet::new();
    base_urls.insert(base_url.to_string());
    for proc in read_managed_server_processes(None) {
        base_urls.insert(proc.base_url.trim_end_matches('/').to_string());
    }

    let mut close_results: Vec<String> = Vec::new();
    let mut close_errors: Vec<String> = Vec::new();

    for url in &base_urls {
        match call_tool(client, url, "close_all_sessions", json!({})).await {
            Ok(result) => {
                if url == base_url {
                    close_results.push(result);
                } else {
                    close_results.push(format!("{}: {}", url, result));
                }
            }
            Err(e) => close_errors.push(format!("{}: {}", url, e)),
        }
    }

    let shutdown_result =
        shutdown_managed_server_processes(false, None, 5_000, 250);
    clear_state(None);

    if close_results.is_empty() {
        println!("No reachable Browser4 servers responded to close-all.");
    } else {
        for r in &close_results {
            println!("{}", r);
        }
    }
    log_shutdown_result("Stopped", &shutdown_result);

    if !close_errors.is_empty() {
        eprintln!("close-all warnings: {}", close_errors.join(" | "));
    }
    Ok(())
}

async fn handle_kill_all() -> Result<(), String> {
    let result = shutdown_managed_server_processes(true, None, 5_000, 250);
    clear_state(None);
    log_shutdown_result("Killed", &result);
    Ok(())
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
        let pids: Vec<String> = result.remaining_pids.iter().map(|p| p.to_string()).collect();
        eprintln!(
            "Browser4 process(es) still running after {}: {}",
            action.to_lowercase(),
            pids.join(", ")
        );
    }
}

async fn handle_list(client: &Client, base_url: &str) -> Result<(), String> {
    let result = call_tool(client, base_url, "list_sessions", json!({})).await?;
    println!("{}", result);
    Ok(())
}

async fn handle_delete_data(client: &Client, base_url: &str) -> Result<(), String> {
    let result = with_session(client, base_url, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        async move {
            call_tool(&client, &base_url, "delete_session_data", json!({ "sessionId": session_id })).await
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
) -> Result<(), String> {
    let filename = tool_params.get("filename").and_then(|v| v.as_str()).map(|s| s.to_string());
    let snapshot_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
        }
        a
    };

    let combined = with_session(client, base_url, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut snap_args = snapshot_args.clone();
        snap_args["sessionId"] = json!(session_id.clone());

        async move {
            let (url_res, title_res, snap_res) = tokio::join!(
                call_tool(&client, &base_url, "page_url", json!({ "sessionId": session_id })),
                call_tool(&client, &base_url, "page_title", json!({ "sessionId": session_id })),
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
) -> Result<(), String> {
    let filename = tool_params.get("filename").and_then(|v| v.as_str()).map(|s| s.to_string());
    let capture_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
        }
        a
    };

    let base64_data = with_session(client, base_url, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut args = capture_args.clone();
        args["sessionId"] = json!(session_id);
        async move {
            call_tool(&client, &base_url, &tool_name, args).await
        }
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
) -> Result<(), String> {
    let result = with_session(client, base_url, recover_stale, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move {
            call_tool(&client, &base_url, &tool_name, params).await
        }
    })
    .await?;

    if !result.is_empty() {
        println!("{}", result);
    }
    Ok(())
}

fn should_ensure_server_running(command: &str) -> bool {
    command != "close-all" && command != "kill-all"
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

#[tokio::main]
async fn main() {
    let raw_args: Vec<String> = std::env::args().skip(1).collect();
    let global = parse_global_flags(&raw_args);
    let command = global.args.first().map(|s| s.as_str()).unwrap_or("");

    if let Err(e) = run(command, &global).await {
        eprintln!("Error: {}", e);
        std::process::exit(1);
    }
}

async fn run(command: &str, global: &args::GlobalFlags) -> Result<(), String> {
    // Handle help or no command
    if command.is_empty() || command == "help" || command == "--help" || command == "-h" {
        let sub = global.args.get(1).map(|s| s.as_str());
        print_help(sub);
        return Ok(());
    }

    // Handle version
    if command == "--version" || command == "-v" || command == "version" {
        println!("browser4-cli {}", VERSION);
        return Ok(());
    }

    // Resolve base URL: --server flag > persisted state > default
    let base_url = resolve_base_url(global.server_url.as_deref());

    // Persist server URL override if different from current state
    if let Some(ref server_url) = global.server_url {
        let current_state = read_state(None);
        if server_url != &current_state.base_url {
            let mut updated = current_state;
            updated.base_url = server_url.clone();
            write_state(&updated, None).map_err(|e| e.to_string())?;
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
    let parsed = build_command_args(&raw_parsed, &arg_names)
        .map_err(|e| e.to_string())?;

    // Resolve tool name and parameters
    let tool_name = (cmd_def.tool_name_fn)(&parsed);
    let tool_params = (cmd_def.tool_params_fn)(&parsed);

    // Dispatch the command
    match command {
        "open" => {
            handle_open(&client, &base_url, &tool_name, &tool_params, global.session_name.as_deref(), &parsed).await?;
        }
        "close" => {
            handle_close(&client, &base_url).await?;
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
            handle_delete_data(&client, &base_url).await?;
        }
        "snapshot" => {
            handle_snapshot(&client, &base_url, &tool_name, &tool_params).await?;
        }
        "screenshot" => {
            handle_screenshot(&client, &base_url, &tool_name, &tool_params).await?;
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
                command == "goto",
            )
            .await?;
        }
    }

    // Post-command snapshot for commands that modify browser state
    let no_snap = no_snapshot_commands();
    if !no_snap.contains(command) {
        let state = read_state(None);
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
