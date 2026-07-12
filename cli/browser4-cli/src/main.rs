//! Browser4 CLI — drive a Browser4 server from the command line.
//!
//! Most operations are routed through the Browser4 MCP Server tool interface
//! via `POST /mcp/call-tool`.
//! Swarm scrape submission/status/result flows also use the scrape REST
//! endpoints under `/api/x`.
//!
//! # State persistence
//! CLI session state is persisted between invocations under `~/.browser4` by
//! default.  The default session uses `~/.browser4/cli-state.json`; named
//! sessions use `~/.browser4/sessions/<name>.json`.
//!
//! The Browser4 runtime bundle (JRE, JARs, launchers) lives separately in a
//! platform-conventional data directory so that clearing CLI state does not
//! require re-downloading the ~200 MB runtime.  See `state::resolve_runtime_data_dir`.
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
mod skills;
mod snapshot;
mod snapshot_diff;
mod state;
mod tips;

use std::collections::{HashMap, HashSet};
use std::io::{IsTerminal, Read};
use std::path::PathBuf;

use base64::Engine;
use chrono::Utc;
use reqwest::Client;
use serde::{de::DeserializeOwned, Deserialize};
use serde_json::{json, Value};

use args::{
    build_command_args, build_short_option_map, parse_batch_args, parse_batch_json_commands,
    parse_command_string, parse_global_flags, parse_raw_args,
};
use commands::{commands_map, is_element_reference};
use daemon::{
    ensure_chrome_available, ensure_server_running, init_root_search_start_dir_from_startup,
    install_browser4_runtime, is_local_port_open, read_current_tag, resolve_base_url,
    resolve_channel_to_endpoint,
    InstalledBrowser4Runtime,
};
use help::{
    commands_in_category, generate_command_help, generate_help, generate_help_entry,
    public_command_name, resolve_category_alias, CATEGORY_TITLES,
};
use http::{
    call_tool, call_tool_with_result, cancel_crawl, clear_crawls, crawl_request_timeout,
    get_command_result, get_command_status, get_crawl_result, get_crawl_status,
    get_swarm_result, get_swarm_status,
    is_stale_session_error, make_client, submit_batch_commands, submit_crawl,
    submit_plain_command, submit_swarm_payload, submit_swarm_query, CallToolResult,
};
use managed_processes::{
    read_managed_server_processes, stop_browser4_server_forcibly, ManagedServerProcess,
    ShutdownResult,
};
use snapshot::{resolve_output_path, save_binary, save_snapshot, timestamped_filename};
use state::{
    clear_all_state, clear_state, format_async_task_list, prune_async_tasks,
    read_async_tasks, read_state, resolve_default_state_dir, resolve_ref, track_async_task,
    update_async_task_status,
    write_async_tasks, write_state, CliState, MousePosition,
};

const VERSION: &str = env!("BROWSER4_CLI_VERSION");
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

thread_local! {
    /// When `--json` is active, human-readable output is suppressed and
    /// a JSON envelope is emitted on exit.  This flag persists for the
    /// entire command lifetime — unlike `JSON_OUTPUT` which is taken by
    /// `json_finish()` before the envelope is printed.
    static JSON_MODE: RefCell<bool> = RefCell::new(false);
}

/// Initialise the JSON output accumulator for the current command.
fn json_init() {
    JSON_MODE.with(|cell| *cell.borrow_mut() = true);
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
///
/// Uses the persistent `JSON_MODE` flag so the check remains reliable
/// even after `json_finish()` has taken the accumulator.  This is
/// important for suppressing tips and human-readable output that are
/// emitted after the JSON envelope.
#[allow(dead_code)]
fn json_active() -> bool {
    JSON_MODE.with(|cell| *cell.borrow())
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

// ---------------------------------------------------------------------------
// Show-tip support (--show-tip / -tip global flag)
// ---------------------------------------------------------------------------

thread_local! {
    /// When `--show-tip` / `-tip` is active, tips are shown on stderr after
    /// each successful command.  Tips are suppressed by default.
    static SHOW_TIP: RefCell<bool> = RefCell::new(false);
}

fn show_tip_init(show_tip: bool) {
    SHOW_TIP.with(|cell| *cell.borrow_mut() = show_tip);
}

fn show_tip_active() -> bool {
    SHOW_TIP.with(|cell| *cell.borrow())
}

// ---------------------------------------------------------------------------
// Raw / stdout output support (--raw / --stdout per-command flags)
// ---------------------------------------------------------------------------

thread_local! {
    /// When `--raw` or `--stdout` is active, the command emits machine-readable
    /// content directly to stdout (bypassing `cli_println!`).  Tips and other
    /// human-oriented stderr chatter should be suppressed so the output is
    /// clean for piping.
    static RAW_MODE: RefCell<bool> = RefCell::new(false);
}

fn raw_init(raw: bool) {
    RAW_MODE.with(|cell| *cell.borrow_mut() = raw);
}

fn raw_active() -> bool {
    RAW_MODE.with(|cell| *cell.borrow())
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

// ---------------------------------------------------------------------------
// Exit codes
// ---------------------------------------------------------------------------

/// Normalised exit codes reported by every command path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
enum ExitCode {
    #[allow(dead_code)]
    /// Command completed successfully.
    Success = 0,
    /// Catch-all for unexpected internal errors.
    General = 1,
    /// Invalid arguments, unknown command, bad URL, missing required args.
    Usage = 2,
    /// No active session, session expired, session conflict.
    Session = 3,
    /// Server unreachable, health-check timeout, daemon startup failure.
    Server = 4,
    /// One or more commands in a batch failed (processing itself succeeded).
    BatchPartial = 5,
}

/// Normalised error type that pairs a machine-readable exit code with a
/// human-readable message.
#[derive(Debug, Clone)]
struct CliError(ExitCode, String);

impl CliError {
    fn code(&self) -> ExitCode {
        self.0
    }
    fn message(&self) -> &str {
        &self.1
    }
}

impl std::fmt::Display for CliError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.1.fmt(f)
    }
}

impl From<String> for CliError {
    fn from(message: String) -> Self {
        CliError(ExitCode::General, message)
    }
}

impl From<&str> for CliError {
    fn from(message: &str) -> Self {
        CliError(ExitCode::General, message.to_string())
    }
}

/// Parsed arguments for the `loop` command.
#[derive(Debug, Default)]
struct LoopArgs {
    /// The raw tokens forming the task to execute.
    task_tokens: Vec<String>,
    /// True when `--shell` was specified.
    is_shell: bool,
    /// True when `--` was used to separate loop options from a browser4-cli subcommand.
    is_subcommand: bool,
    /// Seconds between iterations (default: 3600 = 1 hour).
    interval_secs: u64,
    /// Maximum number of iterations (None = infinite).
    count: Option<u64>,
    /// Maximum total duration in seconds (default: 604800 = 1 week).
    timeout_secs: Option<u64>,
    /// Loop name for persistence (None = "default").
    name: Option<String>,
    /// True when `--stop` was specified — stop a running/persisted loop.
    stop: bool,
    /// True when `--stop-all` was specified — stop and clear all loops.
    stop_all: bool,
    /// True when `--status` was specified — show current loop state.
    status: bool,
    /// True when `--list` was specified — list all persisted loops.
    list: bool,
    /// True when `--pause` was specified — pause a running loop.
    pause: bool,
    /// True when `--resume` was specified — resume a paused loop.
    resume: bool,
    /// True when `--pause-all` was specified — pause all running loops.
    pause_all: bool,
    /// True when `--resume-all` was specified — resume all paused loops.
    resume_all: bool,
    /// True when `--history` was specified — show completed loop history.
    history: bool,
    /// True when `--keep-state` was specified — preserve state file on completion.
    keep_state: bool,
}

/// Commands that should NOT trigger a post-command snapshot.
fn no_snapshot_commands() -> HashSet<&'static str> {
    [
        "open",
        "attach",
        "goto",
        "act",
        "batch",
        "close",
        "close-all",
        "console",
        "delete-data",
        "kill-all",
        "list",
        "status",
        "stop",
        "loop",
        "install",
        "uninstall",
        "upgrade",
        "doctor",
        "doctor-log",
        "help",
        "eval",
        "generate-locator",
        "extract",
        "summarize",
        "snapshot",
        "snapshot-grep",
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
        "get",
        "wait",
        "agent-run",
        "agent-status",
        "agent-result",
        "agent-list",
        "swarm-create",
        "swarm-submit",
        "swarm-query",
        "swarm-status",
        "swarm-result",
        "swarm-list",
        "crawl",
        "crawl-status",
        "crawl-result",
        "crawl-cancel",
        "crawl-clear",
        "crawl-list",
        "htmlsnapshot",
        "htmlsnapshot-capture",
        "htmlsnapshot-get",
        "htmlsnapshot-get-all",
        "htmlsnapshot-query",
        "htmlsnapshot-export",
        "htmlsnapshot-summary",
        "htmlsnapshot-grep",
        "htmlsnapshot-inspect",
        "scroll",
        "resize",
        "skills",
        "skills-list",
        "skills-get",
        "skills-path",
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
        &["run `browser4-cli open <url>` first."],
    )
}

fn saved_session_expired_message() -> String {
    format_session_guidance_message(
        "Session refresh needed",
        None,
        "The saved session expired or is no longer usable.",
        &["run `browser4-cli open <url>` to create a fresh session, then retry."],
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

// ---------------------------------------------------------------------------
// Tab tracking helpers (for click --follow)
// ---------------------------------------------------------------------------

/// Lightweight tab entry parsed from the `browser_tabs` list response.
#[derive(Debug, Clone)]
struct TabInfo {
    index: usize,
    url: String,
    title: String,
}

/// Parse the JSON response from a `browser_tabs` "list" action into a vec of
/// [TabInfo] entries.  The response can be a JSON array of tab objects, an
/// object with a `"tabs"` array, or a plain text representation.
fn parse_tab_list(response: &str) -> Vec<TabInfo> {
    let trimmed = response.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    // Try parsing as a JSON array first.
    if let Ok(array) = serde_json::from_str::<Vec<Value>>(trimmed) {
        return array
            .into_iter()
            .filter_map(|v| parse_tab_entry(&v))
            .collect();
    }

    // Try parsing as a JSON object with a "tabs" key.
    if let Ok(obj) = serde_json::from_str::<Value>(trimmed) {
        if let Some(tabs) = obj.get("tabs").and_then(|t| t.as_array()) {
            return tabs
                .iter()
                .filter_map(|v| parse_tab_entry(v))
                .collect();
        }
        // It might be a single tab object — try that.
        if let Some(tab) = parse_tab_entry(&obj) {
            return vec![tab];
        }
    }

    Vec::new()
}

fn parse_tab_entry(value: &Value) -> Option<TabInfo> {
    let obj = value.as_object()?;
    // Accept "index", "id" (if numeric), or derive position.
    let index = obj
        .get("index")
        .and_then(|v| {
            v.as_u64()
                .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
        })
        .or_else(|| {
            obj.get("id").and_then(|v| {
                v.as_u64()
                    .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
            })
        })
        .map(|n| n as usize);
    let url = obj
        .get("url")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let title = obj
        .get("title")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    // We need at least an index to track the tab.
    index.map(|idx| TabInfo {
        index: idx,
        url,
        title,
    })
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
                "Swarm create only supports --profile-mode SEQUENTIAL or --profile-mode TEMPORARY. Received: {}",
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
    F: Fn(String) -> Fut + Send + Sync,
    Fut: std::future::Future<Output = Result<String, String>> + Send,
{
    with_session_paginated(client, base_url, session_name, recover_stale, |sid| {
        let fut = action(sid);
        async move { fut.await.map(|text| CallToolResult { text, pagination: None }) }
    })
    .await
    .map(|r| r.text)
}

/// Like [with_session] but returns [CallToolResult] which includes optional
/// server-side pagination metadata from the `_pagination` response field.
async fn with_session_paginated<F, Fut>(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
    recover_stale: bool,
    action: F,
) -> Result<CallToolResult, String>
where
    F: Fn(String) -> Fut + Send,
    Fut: std::future::Future<Output = Result<CallToolResult, String>> + Send,
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
    // Prepend a header comment documenting the snapshot.
    let header = "# Auto-snapshot after command — full viewport (viewport 0).\n\
                  # Use `browser4-cli snapshot grep <pattern>` to search the tree.\n";
    let snap_with_header = format!("{}\n{}", header, snap_result);
    if let Err(e) = save_snapshot(&out_path, &snap_with_header) {
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
    if !json_active() {
        eprintln!(
            "💡 Tip: Run `snapshot -v 0` to see interactive element refs"
        );
    }
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

    // When reconnecting to an existing session, inform the user what page is active
    if reused_existing_session {
        if let Ok(url_result) = call_tool(
            client,
            base_url,
            "page_url",
            json!({ "sessionId": session_id }),
        ).await {
            if !url_result.is_empty() {
                let label = session_name.unwrap_or("DEFAULT");
                cli_println!("Using existing session {} (current page: {}).", label, url_result);
            }
        }
    }

    Ok((state, session_id, reused_existing_session))
}

// ---------------------------------------------------------------------------
// Attach command
// ---------------------------------------------------------------------------

/// Resolve a `--cdp` argument value to a CDP HTTP endpoint URL.
///
/// Accepts:
/// - HTTP/HTTPS URL (passes through unchanged)
/// - WebSocket URL (`ws://` / `wss://`) — extracts host:port as HTTP
/// - Bare port number (e.g. `"9222"`) — becomes `http://localhost:9222`
/// - `host:port` (e.g. `"localhost:9222"`) — becomes `http://host:port`
/// - Channel name (e.g. `"chrome"`, `"msedge"`) — resolved via process
///   scanning and port probing
fn resolve_cdp_endpoint(raw: &str) -> Result<String, String> {
    // Already an HTTP(S) URL
    if raw.starts_with("http://") || raw.starts_with("https://") {
        return Ok(raw.to_string());
    }

    // WebSocket URL — extract host:port and rewrite as HTTP
    if raw.starts_with("ws://") || raw.starts_with("wss://") {
        // Strip the scheme prefix
        let after_scheme = if raw.starts_with("ws://") {
            raw.strip_prefix("ws://").unwrap()
        } else {
            raw.strip_prefix("wss://").unwrap()
        };
        // Strip path (everything after the first '/')
        let host_port = after_scheme.split('/').next().unwrap_or(after_scheme);
        if host_port.contains(':') {
            return Ok(format!("http://{host_port}"));
        }
        return Ok(format!("http://{host_port}:9222"));
    }

    // Bare port number
    if raw.chars().all(|c| c.is_ascii_digit()) {
        let port: u16 = raw
            .parse()
            .map_err(|_| format!("Invalid port: {raw}"))?;
        return Ok(format!("http://localhost:{port}"));
    }

    // host:port (no scheme, no path)
    if raw.contains(':') && !raw.contains('/') {
        return Ok(format!("http://{raw}"));
    }

    // Channel name — delegate to daemon.rs resolution
    resolve_channel_to_endpoint(raw)
}

async fn handle_attach(
    client: &Client,
    base_url: &str,
    _tool_params: &Value,
    session_name: Option<&str>,
    parsed_args: &HashMap<String, Value>,
) -> Result<(), String> {
    // --cdp value
    let cdp_raw = parsed_args
        .get("cdp")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty());

    // --endpoint value (overrides base_url for remote Browser4 servers)
    let endpoint_override = parsed_args
        .get("endpoint")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty());

    let effective_base_url = if let Some(endpoint) = endpoint_override {
        if !endpoint.starts_with("http://") && !endpoint.starts_with("https://") {
            return Err("--endpoint must be an HTTP(S) URL".to_string());
        }
        endpoint.to_string()
    } else {
        base_url.to_string()
    };

    // If --endpoint is provided without --cdp, just switch the CLI to the
    // remote server without calling attach_browser.  The remote server
    // manages its own browser sessions; subsequent commands (open, goto,
    // list, etc.) will target the remote endpoint.
    if cdp_raw.is_none() && endpoint_override.is_some() {
        let mut state = read_state(None, session_name);
        state.session_name = session_name.map(|s| s.to_string());
        state.session_id = None;
        state.base_url = effective_base_url.clone();
        state.active_selector = None;
        state.last_mouse_position = None;
        write_state(&state, None, session_name).map_err(|e| e.to_string())?;

        json_field("endpoint", json!(&effective_base_url));

        cli_println!("Switched to remote Browser4 server: {}", effective_base_url);
        cli_println!("Use 'browser4-cli list' to see sessions, or 'browser4-cli open' to start one.");
        return Ok(());
    }

    // Resolve the CDP endpoint — channel-name resolution uses blocking I/O
    // (process scanning, port probing via reqwest::blocking) and must run
    // outside the tokio async context to avoid runtime-drop panics.
    let cdp_endpoint = if let Some(raw) = cdp_raw {
        let raw_owned = raw.to_string();
        let resolved = tokio::task::spawn_blocking(move || resolve_cdp_endpoint(&raw_owned))
            .await
            .map_err(|e| format!("CDP endpoint resolution failed: {e}"))?;
        resolved?
    } else {
        return Err(
            "attach requires --cdp <url|channel> or --endpoint <url>.\n\
             Examples:\n  \
             browser4-cli attach --cdp chrome\n  \
             browser4-cli attach --cdp http://localhost:9222\n  \
             browser4-cli attach --endpoint http://browser4-server:8182\n  \
             browser4-cli attach --endpoint http://remote:8182 --cdp chrome"
                .to_string(),
        );
    };

    // Call the backend attach_browser MCP tool
    let attach_params = json!({
        "cdpEndpoint": &cdp_endpoint,
    });

    let result = call_tool(client, &effective_base_url, "attach_browser", attach_params).await?;

    // Extract session ID from the response
    let session_id = if let Ok(parsed) = serde_json::from_str::<Value>(&result) {
        parsed
            .get("sessionId")
            .and_then(|v| v.as_str())
            .unwrap_or(&result)
            .to_string()
    } else {
        result.clone()
    };

    // Persist session state for subsequent commands
    let mut state = read_state(None, session_name);
    state.session_name = session_name.map(|s| s.to_string());
    state.session_id = Some(session_id.clone());
    state.base_url = effective_base_url.clone();
    state.active_selector = None;
    state.last_mouse_position = None;
    state.is_attached = true;
    write_state(&state, None, session_name).map_err(|e| e.to_string())?;

    json_field("session_id", json!(&session_id));
    json_field("cdp_endpoint", json!(&cdp_endpoint));

    cli_println!("Attached to browser at {}", cdp_endpoint);
    cli_println!("Session opened: {}", session_id);

    // Take an initial snapshot so the user can see the current state
    post_command_snapshot(client, &effective_base_url, &session_id).await;

    Ok(())
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
        let mut params = json!({ "url": url });
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
                return Err(format_navigation_failure_message(
                    target_url,
                    &session_id,
                    &err,
                    should_suggest_refresh,
                ));
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
                    return Err(format_navigation_failure_message(
                        target_url,
                        &retry_id,
                        &retry_err,
                        should_suggest_refresh,
                    ));
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

fn is_not_focusable_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("not focusable")
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
        suggestions
            .push("run `browser4-cli open <url>` to refresh the session, then retry.".to_string());
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

/// Handle the `click`, `dblclick`, and `press` commands.
///
/// When `follow` is true, the handler detects new browser tabs that may have
/// been opened by the action (common on JS-heavy search engines like Baidu) and
/// switches to the newest one so the post-command snapshot reflects the
/// navigated page.
async fn handle_navigation_action(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    follow: bool,
) -> Result<(), String> {
    if !follow {
        return handle_tool_command(client, base_url, tool_name, tool_params, false, session_name)
            .await;
    }

    // --- follow mode: detect new tabs after click ---

    let state = require_session(session_name)?;
    let sid = get_session_id(&state)?;

    // 1. Capture page URL before the click to detect silent navigation failures.
    let url_before = call_tool(
        client,
        base_url,
        "page_url",
        json!({ "sessionId": &sid }),
    )
    .await
    .ok();

    // 2. Record tabs before the click.
    let tabs_before: HashSet<usize> = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": &sid, "action": "list" }),
    )
    .await
    .map(|resp| {
        parse_tab_list(&resp)
            .into_iter()
            .map(|t| t.index)
            .collect()
    })
    .unwrap_or_default();

    // 3. Perform the click (backend handles same-tab navigation detection).
    handle_tool_command(client, base_url, tool_name, tool_params, false, session_name).await?;

    // 4. Check for new tabs.
    let tabs_after: Vec<TabInfo> = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": &sid, "action": "list" }),
    )
    .await
    .map(|resp| parse_tab_list(&resp))
    .unwrap_or_default();

    let new_tabs: Vec<&TabInfo> = tabs_after
        .iter()
        .filter(|t| !tabs_before.contains(&t.index))
        .collect();

    if new_tabs.is_empty() {
        // No new tabs — verify same-tab navigation and warn if URL unchanged.
        verify_click_navigation(client, base_url, &sid, tool_params, &url_before).await;
        return Ok(());
    }

    // 5. Report new tabs and switch to the newest one.
    cli_println!("🌐 {} new tab(s) opened by click:", new_tabs.len());
    for tab in &new_tabs {
        let label = if tab.title.is_empty() {
            tab.url.clone()
        } else {
            format!("{} — {}", tab.title, tab.url)
        };
        cli_println!("  • [tab {}] {}", tab.index, label);
    }

    // Switch to the tab with the highest index (most recently opened).
    let newest = new_tabs
        .iter()
        .max_by_key(|t| t.index)
        .expect("new_tabs is non-empty");
    call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": &sid, "action": "select", "index": newest.index }),
    )
    .await
    .map_err(|e| format!("Failed to switch to new tab {}: {}", newest.index, e))?;

    cli_println!("✓ Switched to tab {}", newest.index);
    Ok(())
}

/// Verify that a click resulted in navigation. Emits a warning to stderr if
/// the page URL is unchanged after the click, which may indicate a silent
/// failure (e.g. clicking an off-screen element).
async fn verify_click_navigation(
    client: &Client,
    base_url: &str,
    session_id: &str,
    tool_params: &Value,
    url_before: &Option<String>,
) {
    let Some(ref before) = url_before else {
        return;
    };

    let url_after = call_tool(
        client,
        base_url,
        "page_url",
        json!({ "sessionId": session_id }),
    )
    .await
    .ok();

    let Some(ref after) = url_after else {
        return;
    };

    if before == after {
        let ref_val = tool_params
            .get("ref")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        // Suppress warning in --json or --quiet mode to keep machine output clean.
        let suppressed = json_active() || quiet_active();
        if !ref_val.is_empty() && !suppressed {
            eprintln!(
                "⚠️  Click on {} did not result in navigation — page URL is unchanged. \
                 The element may be off-screen; try scrolling it into view first or \
                 use --follow to detect new-tab navigation.",
                ref_val
            );
        }
    }
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
    let is_attached = state.is_attached;
    // Ignore errors — session might already be closed
    let _ = call_tool(
        client,
        base_url,
        "close_session",
        json!({ "sessionId": session_id }),
    )
    .await;
    clear_state(None, session_name);
    if is_attached {
        cli_println!("Disconnected from attached browser. The browser remains running.");
    } else {
        cli_println!("Session closed. Browser terminated.");
    }
    json_field("closed", json!(true));
    Ok(())
}

// ---------------------------------------------------------------------------
// Tab command handlers
// ---------------------------------------------------------------------------

async fn handle_tab_list(
    client: &Client,
    base_url: &str,
    session_name: Option<&str>,
) -> Result<(), String> {
    let state = read_state(None, session_name);
    let Some(session_id) = state.session_id.as_deref() else {
        return Err(no_active_session_message());
    };

    let result = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "list" }),
    )
    .await?;

    let tabs = parse_tab_list(&result);
    if tabs.is_empty() {
        if json_active() {
            cli_println!("[]");
        } else {
            cli_println!("No tabs found.");
        }
        return Ok(());
    }

    if json_active() {
        // Machine-readable JSON (consistent with other commands' --json behavior)
        let json_tabs: Vec<Value> = tabs
            .iter()
            .map(|t| {
                json!({
                    "index": t.index,
                    "url": t.url,
                    "title": t.title,
                })
            })
            .collect();
        cli_println!("{}", serde_json::to_string_pretty(&json_tabs).unwrap_or_default());
    } else {
        // Human-readable table: Index | Title | URL
        let idx_w = "Index".len().max(
            tabs.last().map(|t| t.index.to_string().len()).unwrap_or(0),
        );
        let title_w = "Title".len().max(
            tabs.iter().map(|t| t.title.len()).max().unwrap_or(0).min(60),
        );
        let url_w = "URL".len().max(
            tabs.iter().map(|t| t.url.len()).max().unwrap_or(0).min(80),
        );

        cli_println!(
            "  {:<idx_w$}  {:<title_w$}  {:<url_w$}",
            "Index", "Title", "URL",
            idx_w = idx_w,
            title_w = title_w,
            url_w = url_w,
        );
        cli_println!(
            "  {:-<idx_w$}  {:-<title_w$}  {:-<url_w$}",
            "", "", "",
            idx_w = idx_w,
            title_w = title_w,
            url_w = url_w,
        );
        for tab in &tabs {
            let title = if tab.title.len() > 60 {
                format!("{}…", &tab.title[..59])
            } else {
                tab.title.clone()
            };
            let url = if tab.url.len() > 80 {
                format!("{}…", &tab.url[..79])
            } else {
                tab.url.clone()
            };
            cli_println!(
                "  {:<idx_w$}  {:<title_w$}  {:<url_w$}",
                tab.index, title, url,
                idx_w = idx_w,
                title_w = title_w,
                url_w = url_w,
            );
        }
    }
    Ok(())
}

async fn handle_tab_new(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let url = tool_params
        .get("url")
        .and_then(|v| v.as_str())
        .unwrap_or("about:blank");

    let state = read_state(None, session_name);
    let Some(session_id) = state.session_id.as_deref() else {
        return Err(no_active_session_message());
    };

    let result = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "new", "url": url }),
    )
    .await?;

    cli_println!("{}", result);

    // Auto-switch to the newly created tab by listing tabs, finding the
    // highest-index entry, and issuing a select action.
    let list_result = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "list" }),
    )
    .await?;

    let tabs = parse_tab_list(&list_result);
    if let Some(new_tab) = tabs.last() {
        let new_index = new_tab.index;
        // Select the new tab
        let _ = call_tool(
            client,
            base_url,
            "browser_tabs",
            json!({ "sessionId": session_id, "action": "select", "index": new_index }),
        )
        .await;
        cli_println!("Switched to tab {} ({})", new_index, url);

        // Refresh page info for the "### Page" section
        let (page_url, page_title) = tokio::join!(
            call_tool(
                client,
                base_url,
                "page_url",
                json!({ "sessionId": session_id }),
            ),
            call_tool(
                client,
                base_url,
                "page_title",
                json!({ "sessionId": session_id }),
            ),
        );
        if let (Ok(pu), Ok(pt)) = (page_url, page_title) {
            json_field("page_url", json!(&pu));
            json_field("page_title", json!(&pt));
            cli_println!("### Page");
            cli_println!("- Page URL: {}", pu);
            cli_println!("- Page Title: {}", pt);
        }
    } else {
        // Couldn't parse the tab list; still show a tip
        if !json_active() {
            eprintln!("💡 Tip: Use `tab-select <index>` to switch to the new tab.");
        }
    }

    Ok(())
}

async fn handle_tab_select(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let index_val = tool_params.get("index").cloned().unwrap_or_default();

    let state = read_state(None, session_name);
    let Some(session_id) = state.session_id.as_deref() else {
        return Err(no_active_session_message());
    };

    let result = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "select", "index": index_val }),
    )
    .await?;

    cli_println!("{}", result);

    // Refresh page info after tab switch so the "### Page" section shows the
    // newly selected tab's URL and title instead of stale cached metadata.
    let (page_url, page_title) = tokio::join!(
        call_tool(
            client,
            base_url,
            "page_url",
            json!({ "sessionId": session_id }),
        ),
        call_tool(
            client,
            base_url,
            "page_title",
            json!({ "sessionId": session_id }),
        ),
    );
    if let (Ok(pu), Ok(pt)) = (page_url, page_title) {
        json_field("page_url", json!(&pu));
        json_field("page_title", json!(&pt));
        cli_println!("### Page");
        cli_println!("- Page URL: {}", pu);
        cli_println!("- Page Title: {}", pt);
    }

    Ok(())
}

async fn handle_close_all(client: &Client, base_url: &str) -> Result<(), String> {
    // Count locally-tracked sessions the same way `list` does, so the
    // count reported to the user matches what `list` would have shown.
    let local_count = count_tracked_sessions();

    let close_summary = close_all_sessions_across_servers(client, base_url).await;

    // `close-all` is intentionally session-scoped. Keep any tracked Browser4
    // backend process alive so callers can continue using the same service and
    // reserve JVM shutdown for the explicit `kill-all` flow.
    clear_all_state(None);

    json_field("results", json!(close_summary.results));
    json_field("errors", json!(close_summary.errors));

    // Report the locally-tracked count instead of the server's count, which may
    // include internal sessions that the user never sees in `list` output.
    cli_println!("Closed {} session(s)", local_count);
    if !close_summary.errors.is_empty() {
        eprintln!("close-all warnings: {}", close_summary.errors.join(" | "));
    }
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
        json_field(
            "browser_pids_remaining",
            json!(browser_result.remaining_pids),
        );
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
        cli_println!("⚠  Fallback-killed server process(es): {}", pids.join(", "));
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
        "Name",
        "Session ID",
        "Status",
        "Next open"
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
                                    name,
                                    sid,
                                    status,
                                    next_open
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

    // List default session — only show when the backend also knows about it.
    // A default session that exists in local state but not on the backend was
    // auto-created by a previous run and never actually navigated; hide it to
    // avoid session clutter when all commands use `-s <name>`.
    let default_state = read_state(None, None);
    if let Some(sid) = default_state.session_id {
        let backend_knows_session = backend_sessions.as_ref().map_or(true, |records| {
            records.iter().any(|r| r.session_id == sid)
        });
        if backend_knows_session {
            let status = list_session_status(backend_sessions.as_deref(), &sid);
            let next_open = list_session_next_open_action(backend_sessions.as_deref(), &sid);
            cli_println!(
                "{:<20} | {:<40} | {:<8} | {}",
                "(default)",
                sid,
                status,
                next_open
            );
            json_sessions.push(json!({
                "name": "(default)",
                "session_id": sid,
                "status": status.to_lowercase(),
                "next_open": next_open.to_lowercase(),
            }));
        }
    }

    json_field("sessions", json!(json_sessions));
    json_field("backend_reachable", json!(backend_reachable));

    if let Some(note) = backend_note {
        cli_println!("\n{}", note);
    }

    Ok(())
}

/// Count locally-tracked sessions the same way `handle_list` does:
/// named sessions in the state directory + default session if present.
fn count_tracked_sessions() -> usize {
    let mut count: usize = 0;
    let dir = resolve_default_state_dir();

    // Count named sessions
    let sessions_dir = dir.join("sessions");
    if sessions_dir.exists() {
        if let Ok(entries) = std::fs::read_dir(sessions_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.extension().map_or(false, |ext| ext == "json") {
                    if let Ok(content) = std::fs::read_to_string(&path) {
                        if let Ok(state) = serde_json::from_str::<CliState>(&content) {
                            if state.session_id.is_some() {
                                count += 1;
                            }
                        }
                    }
                }
            }
        }
    }

    // Count default session
    let default_state = read_state(Some(&dir), None);
    if default_state.session_id.is_some() {
        count += 1;
    }

    count
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
    // --viewport is now passed through directly to the server as the
    // "viewports" key (renamed in tool_params_fn).  The server's
    // ViewportSpec.parse() handles the flexible format natively.
    let snapshot_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
            m.remove("raw");      // CLI-side flag, not a server parameter
            m.remove("stdout");   // CLI-side flag, not a server parameter
            m.remove("auto-diff");// CLI-side flag, not a server parameter
            m.remove("page");      // CLI-side pagination, not a server parameter
            m.remove("page-size"); // CLI-side pagination, not a server parameter
            m.remove("all");       // CLI-side pagination, not a server parameter
        }
        a
    };

    let combined_result = with_session_paginated(
        client, base_url, session_name, false,
        |session_id| {
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
                    call_tool_with_result(&client, &base_url, &tool_name, snap_args),
                );
                let url = url_res?;
                let title = title_res?;
                let snap_result = snap_res?;
                Ok(CallToolResult {
                    text: format!("{}\n{}\n{}", url, title, snap_result.text),
                    pagination: snap_result.pagination,
                })
            }
        },
    )
    .await?;

    let server_pagination = combined_result.pagination;

    // The combined result has url, title, and snapshot separated by newlines
    let parts: Vec<&str> = combined_result.text.splitn(3, '\n').collect();
    let (url, title, snap) = match parts.as_slice() {
        [u, t, s] => (*u, *t, *s),
        _ => ("", "", combined_result.text.as_str()),
    };

    let out_path = resolve_output_path(filename.as_deref(), "snapshot", "yml");

    // Prepend a YAML comment header documenting the snapshot scope so users
    // understand that the file may not contain the full accessibility tree
    // (e.g. when viewport filtering is active).  Use `snapshot grep <pattern>`
    // to search the complete in-memory tree regardless of viewport.
    let viewports = tool_params
        .get("viewports")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty());
    let header = if let Some(vp) = viewports {
        format!(
            "# Snapshot viewport(s): {}\n\
             # This file contains the accessibility tree for the requested viewport(s) only.\n\
             # Use `browser4-cli snapshot grep <pattern>` to search the full in-memory tree.\n\
             # Use `browser4-cli snapshot -v all` to capture all viewports.\n",
            vp
        )
    } else {
        format!(
            "# Snapshot — full viewport (viewport 0 by default).\n\
             # Use `browser4-cli snapshot grep <pattern>` to search the tree.\n"
        )
    };
    let snap_with_header = format!("{}\n{}", header, snap);
    save_snapshot(&out_path, &snap_with_header).map_err(|e| e.to_string())?;

    // snapshot does not produce JSON output — warn if --json is active
    if json_active() {
        eprintln!(
            "⚠️  snapshot does not support --json output (snapshots are YAML for human readability). \
             Use `htmlsnapshot` commands (query, get, export) for machine-readable JSON output."
        );
    }

    let raw = tool_params
        .get("raw")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
        || tool_params
            .get("stdout")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);

    // Suppress tips when --raw/--stdout is active so stderr doesn't
    // interleave with machine-readable stdout output.
    raw_init(raw);

    json_field("page_url", json!(url));
    json_field("page_title", json!(title));
    json_field("snapshot_path", json!(out_path.display().to_string()));

    // Detect whether filtering flags are already in use
    let has_filter =
        tool_params.get("selector").and_then(|v| v.as_str()).map_or(false, |s| !s.is_empty())
        || tool_params.get("viewports").and_then(|v| v.as_str()).map_or(false, |s| !s.is_empty())
        || tool_params.get("interactive").and_then(|v| v.as_bool()).unwrap_or(false)
        || tool_params.get("depth").and_then(|v| v.as_str()).map_or(false, |s| !s.is_empty());

    let depth_used = tool_params
        .get("depth")
        .and_then(|v| v.as_str())
        .map_or(false, |s| !s.is_empty());

    let snap_len = snap.len();
    let snap_kb = snap_len / 1024;
    let snap_lines = snap.lines().count();

    // Pagination: prefer server-side metadata when available; fall back to
    // local pagination otherwise.
    let (page, page_size, show_all) = parse_page_opts(tool_params);

    if raw {
        if let Some(ref pm) = server_pagination {
            // Server already paginated — just print the content and footer.
            println!("{}", snap);
            if pm.truncated && !json_active() {
                eprintln!(
                    "[Page {}/{} · {} lines of {} total · use --page N for next page · --all to show all]",
                    pm.page,
                    pm.total_pages,
                    (pm.page.min(pm.total_pages) * pm.page_size).min(pm.total_lines),
                    pm.total_lines
                );
            }
        } else if !skip_pagination(show_all) {
            let (page_text, meta) = paginate_output(snap, page, page_size);
            println!("{}", page_text);
            if meta.is_truncated && !json_active() {
                eprintln!("{}", format_pagination_footer(&meta));
            }
        } else {
            println!("{}", snap);
        }
        // Depth truncation warning (stderr so stdout stays clean for piping;
        // suppress in --json mode since JSON output is consumed by machines, not pipes)
        if depth_used && !json_active() {
            eprintln!(
                "⚠️  Depth limited to {}. Elements deeper than this are not shown. \
                 Increase --depth to see more content.",
                tool_params.get("depth").and_then(|v| v.as_str()).unwrap_or("?")
            );
        }
        // Ref lifecycle note (suppress in --json mode)
        if !json_active() {
            eprintln!(
                "ℹ️  Element refs (e.g. e5, e36) are valid only until the next browser \
                 interaction. Re-run snapshot before reusing refs."
            );
        }
    } else {
        cli_println!("### Page");
        cli_println!("- Page URL: {}", url);
        cli_println!("- Page Title: {}", title);
        cli_println!("### Snapshot");
        cli_println!("[Snapshot]({})", out_path.display());
        cli_println!("- Snapshot size: {} KB ({} nodes/lines)", snap_kb, snap_lines);
        // Viewport count hint: when the page has multiple viewports and no
        // viewport filter is in use, suggest scrolling down.
        let viewports_used = tool_params
            .get("viewports")
            .and_then(|v| v.as_str())
            .map_or(false, |s| !s.is_empty());
        if !viewports_used && !json_active() {
            // Parse viewportsTotal from the snapshot header (e.g. "# - viewportsTotal: 3")
            if let Some(total_str) = snap
                .lines()
                .find(|line| line.starts_with("# - viewportsTotal:"))
                .and_then(|line| line.split(':').nth(1))
                .map(|s| s.trim())
            {
                if let Ok(total) = total_str.parse::<u32>() {
                    if total > 1 {
                        eprintln!(
                            "💡 Tip: This page has {total} viewports (page chunks split by viewport height). \
                             Use `snapshot -v 1` to scroll down, or `snapshot -v all` to capture all viewports at once.",
                        );
                    }
                }
            }
        }
        // Depth truncation warning (suppress in --json mode)
        if depth_used && !json_active() {
            eprintln!(
                "⚠️  Depth limited to {}. Elements deeper than this are not shown. \
                 Increase --depth to see more content.",
                tool_params.get("depth").and_then(|v| v.as_str()).unwrap_or("?")
            );
        }
        // Ref lifecycle note (suppress in --json mode)
        if !json_active() {
            eprintln!(
                "ℹ️  Element refs (e.g. e5, e36) are valid only until the next browser \
                 interaction. Re-run snapshot before reusing refs."
            );
        }
        // Hint: suggest --stdout to print inline instead of opening the file
        if !json_active() {
            eprintln!(
                "💡 Tip: Use `--stdout` to print element refs inline instead of opening the snapshot file"
            );
        }
        // Brief preview: show first few non-comment lines of the snapshot
        if !json_active() {
            let preview_lines: Vec<&str> = snap
                .lines()
                .filter(|line| !line.starts_with('#') && !line.trim().is_empty())
                .take(10)
                .collect();
            if !preview_lines.is_empty() {
                eprintln!("\n--- Snapshot preview (first {} lines) ---", preview_lines.len());
                for line in &preview_lines {
                    eprintln!("{}", line);
                }
                if snap.lines().filter(|l| !l.starts_with('#') && !l.trim().is_empty()).count() > 10 {
                    eprintln!("... (use --stdout or open the file for full content)");
                }
                eprintln!("---");
            }
        }
        // Warn when a non-zero viewport snapshot is suspiciously small (may
        // indicate the AX tree wasn't re-expanded after scrolling — a known
        // server-side limitation).
        let viewport_val = tool_params
            .get("viewports")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let is_nonzero_viewport = !viewport_val.is_empty()
            && viewport_val != "0"
            && viewport_val != "all";
        if is_nonzero_viewport && snap_lines <= 20 && !json_active() {
            eprintln!(
                "⚠️  Viewport snapshot for '{}' contains only {} lines ({} nodes). \
                 The accessibility tree may not have been re-expanded after scrolling. \
                 This is a known server-side limitation. As a workaround, use \
                 `snapshot -v 0` for the current viewport or `snapshot grep <pattern>` \
                 to search the full tree.",
                viewport_val, snap_lines, snap_lines
            );
        }
    }

    // Auto-diff: compare against the previous snapshot in this directory
    let auto_diff = tool_params
        .get("auto-diff")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    if auto_diff {
        if let Some(prev_path) = snapshot_diff::find_previous_snapshot(&out_path) {
            cli_println!("### Diff");
            let diff_output = snapshot_diff::diff_snapshots(&prev_path, &out_path);
            cli_println!("{}", diff_output);
            json_field("diff_previous", json!(prev_path.display().to_string()));
        } else {
            cli_println!("### Diff");
            cli_println!("# No previous snapshot found — this is the first capture in this session.");
        }
    }

    if !raw && !json_active() {
        if snap_len > 10_240 && !has_filter {
            eprintln!(
                "\n💡 Tip: Snapshot is large ({} KB, {} lines). To focus the output, read the page viewport by viewport — just like a human scrolls. Important content usually comes first:\n\
                   --viewport, -v <N>       Capture a specific viewport (start with -v 0)\n\
                   -s, --selector <CSS>     Scope to a CSS selector\n\
                   -i, --interactive        Only show interactive elements\n\
                   -d, --depth <N>           Limit tree depth\n\
                   --raw --page 1            View first page of snapshot content",
                snap_kb,
                snap_lines
            );
        }
    }
    Ok(())
}

async fn handle_snapshot_grep(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    grep_options: &GrepOptions,
) -> Result<(), String> {
    let source = if let Some(selector) = &grep_options.selector {
        // Scoped search: first get snapshot scoped to the CSS selector
        with_session(client, base_url, session_name, false, |session_id| {
            let client = client.clone();
            let base_url = base_url.to_string();
            let sel = selector.clone();
            async move {
                call_tool(
                    &client,
                    &base_url,
                    "browser_snapshot",
                    json!({
                        "sessionId": session_id,
                        "selector": sel,
                    }),
                )
                .await
            }
        })
        .await?
    } else {
        // Full page snapshot
        with_session(client, base_url, session_name, false, |session_id| {
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
        .await?
    };

    let (page, page_size, show_all) = parse_page_opts(tool_params);
    run_grep_on_source(&source, grep_options, "snapshot", page, page_size, show_all)
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

async fn handle_pdf(
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
        .map_err(|e| format!("Failed to decode PDF: {e}"))?;

    let out_path = resolve_output_path(filename.as_deref(), "pdf", "pdf");
    save_binary(&out_path, &bytes).map_err(|e| e.to_string())?;
    cli_println!("[PDF]({})", out_path.display());
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
    handle_tool_command_with_options(client, base_url, tool_name, tool_params, recover_stale, session_name, false).await
}

/// Like [handle_tool_command] but with an `eval_json` flag that, when true,
/// ensures the eval result is printed as valid JSON (scalar strings are
/// quoted, objects/arrays/numbers are printed as-is).
async fn handle_tool_command_with_options(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    recover_stale: bool,
    session_name: Option<&str>,
    eval_json: bool,
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

    // Null-aware output for eval: always print the result, distinguishing
    // JS null/undefined (which arrive as the literal string "null") from
    // genuinely empty strings (which arrive as "").
    if tool_name == "browser_evaluate" {
        if result == "null" {
            cli_println!("null");
        } else if result.is_empty() {
            cli_println!("\"\"");
        } else if eval_json {
            // --json: ensure output is valid JSON. Try to parse the result
            // as JSON first (objects, arrays, numbers, booleans, null); if
            // that fails, wrap it as a JSON string.
            let json_val: serde_json::Value = match serde_json::from_str(&result) {
                Ok(v) => v,
                Err(_) => serde_json::Value::String(result.clone()),
            };
            cli_println!(
                "{}",
                serde_json::to_string(&json_val).unwrap_or_else(|_| result.clone())
            );
        } else {
            cli_println!("{}", result);
        }
        json_field("result", json!(&result));
        if let Some(expression) = tool_params.get("expression").and_then(|v| v.as_str()) {
            json_field("expression", json!(expression));
        }
        if let Some(r) = tool_params.get("ref").and_then(|v| v.as_str()) {
            json_field("ref", json!(r));
        }
    } else if tool_name.starts_with("wait_") || tool_name == "delay" {
        // Wait tools return internal driver JSON — replace with user-friendly messages.
        // Always format even when the raw result is empty (e.g. `delay` returns "").
        let formatted = format_wait_result(tool_name, tool_params, &result);
        cli_println!("{}", formatted);
        json_field("result", json!(&result));
    } else if tool_name == "scroll_by" {
        // scroll_by returns the absolute scrollY position.  Format it with the
        // requested direction and pixel count for a descriptive output.
        let pixels = tool_params
            .get("pixels")
            .and_then(|v| v.as_f64())
            .unwrap_or(0.0);
        let direction = if pixels >= 0.0 { "down" } else { "up" };
        let abs_pixels = pixels.abs();
        let position = result.trim();
        cli_println!(
            "Scrolled {} {:.0}px (position: {})",
            direction,
            abs_pixels,
            position
        );
        json_field("result", json!(&result));
        json_field("pixels", json!(pixels));
        json_field("position", json!(position));
    } else if tool_name == "browser_mouse_wheel" {
        // browser_mouse_wheel uses deltaX/deltaY for horizontal/vertical scrolling.
        let delta_x = tool_params
            .get("deltaX")
            .and_then(|v| v.as_f64())
            .unwrap_or(0.0);
        let delta_y = tool_params
            .get("deltaY")
            .and_then(|v| v.as_f64())
            .unwrap_or(0.0);
        let h_dir = if delta_x >= 0.0 { "right" } else { "left" };
        let v_dir = if delta_y >= 0.0 { "down" } else { "up" };
        if delta_x.abs() > 0.0 && delta_y.abs() > 0.0 {
            cli_println!(
                "Scrolled {} {:.0}px, {} {:.0}px (position: {})",
                h_dir, delta_x.abs(), v_dir, delta_y.abs(), result.trim()
            );
        } else if delta_x.abs() > 0.0 {
            cli_println!(
                "Scrolled {} {:.0}px (position: {})",
                h_dir, delta_x.abs(), result.trim()
            );
        } else {
            cli_println!(
                "Scrolled {} {:.0}px (position: {})",
                v_dir, delta_y.abs(), result.trim()
            );
        }
        json_field("result", json!(&result));
        json_field("deltaX", json!(delta_x));
        json_field("deltaY", json!(delta_y));
    } else if !result.is_empty() {
        cli_println!("{}", result);
        json_field("result", json!(&result));
    }

    // Success confirmation for interaction commands.
    let ref_val = tool_params.get("ref").and_then(|v| v.as_str()).unwrap_or("");
    if !ref_val.is_empty() {
        match tool_name {
            "browser_click" => {
                let is_double = tool_params
                    .get("doubleClick")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                if is_double {
                    cli_println!("✓ Double-clicked {}", ref_val);
                } else {
                    cli_println!("✓ Clicked {}", ref_val);
                }
            }
            "browser_hover" => {
                cli_println!("✓ Hovered {}", ref_val);
            }
            "browser_drag" => {
                let end_ref = tool_params
                    .get("endRef")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?");
                cli_println!("✓ Dragged {} → {}", ref_val, end_ref);
            }
            "browser_check" => {
                cli_println!("✓ Checked {}", ref_val);
            }
            "browser_uncheck" => {
                cli_println!("✓ Unchecked {}", ref_val);
            }
            _ => {} // No confirmation for other tools
        }
    }

    // Success confirmation for browser_resize (uses width/height, not ref).
    if tool_name == "browser_resize" {
        let w = tool_params
            .get("width")
            .and_then(|v| v.as_f64())
            .map(|v| v as i64)
            .unwrap_or(0);
        let h = tool_params
            .get("height")
            .and_then(|v| v.as_f64())
            .map(|v| v as i64)
            .unwrap_or(0);
        if w > 0 && h > 0 {
            cli_println!("✓ Resized to {}×{}", w, h);
        }
    }

    persist_active_selector(base_url, session_name, tracked_selector(tool_params))?;
    Ok(())
}

/// Handle the `get` command with null-aware output formatting.
///
/// Distinguishes three cases in the response:
/// - JSON `null` — the element, attribute, or property does not exist
/// - Empty string `""` — the element exists but the requested value is empty
/// - Any other value — printed as-is
async fn handle_get(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, params).await }
    })
    .await?;

    // The MCP response serialises the tool result to a string.
    // A JSON `null` result arrives as the literal string "null".
    // An empty-string result arrives as "".
    // An empty array `[]` means no elements matched (get all variants).
    let empty_result = result == "null" || result.is_empty() || result.trim() == "[]";

    if empty_result {
        let selector = tool_params
            .get("selector")
            .and_then(|v| v.as_str())
            .or_else(|| tool_params.get("ref").and_then(|v| v.as_str()))
            .unwrap_or(":root");
        cli_println!("{}", result);
        cli_println!(
            "No elements matched \"{}\".",
            selector
        );
        cli_println!(
            "  The `get` command queries the live page through the accessibility tree — CSS selectors from htmlsnapshot may not apply here."
        );
        cli_println!(
            "  For CSS selector-based extraction, capture the DOM first with `htmlsnapshot`, then use `htmlsnapshot get text \"{}\"`.",
            selector
        );
    } else {
        cli_println!("{}", result);
    }

    json_field("result", json!(&result));
    if let Some(mode) = tool_params.get("mode").and_then(|v| v.as_str()) {
        json_field("mode", json!(mode));
    }
    if let Some(sel) = tool_params.get("selector").and_then(|v| v.as_str()) {
        json_field("selector", json!(sel));
    } else if let Some(r) = tool_params.get("ref").and_then(|v| v.as_str()) {
        json_field("selector", json!(r));
    }
    if let Some(name) = tool_params.get("name").and_then(|v| v.as_str()) {
        json_field("name", json!(name));
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Agent extract / summarize handlers
// ---------------------------------------------------------------------------

/// Handle the `extract` command: save AI-extracted content to a file by default,
/// print to stdout with `--raw`.
async fn handle_extract(
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
    let raw = tool_params
        .get("raw")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
        || tool_params
            .get("stdout")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
    raw_init(raw);
    let extract_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
            m.remove("raw");      // CLI-side flag, not a server parameter
            m.remove("stdout");   // CLI-side flag, not a server parameter
        }
        a
    };

    let combined = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut args = extract_args.clone();
        args["sessionId"] = json!(session_id.clone());

        async move {
            let (url_res, title_res, extract_res) = tokio::join!(
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
                call_tool(&client, &base_url, &tool_name, args),
            );
            let url = url_res?;
            let title = title_res?;
            let content = extract_res?;
            Ok(format!("{}\n{}\n{}", url, title, content))
        }
    })
    .await?;

    let parts: Vec<&str> = combined.splitn(3, '\n').collect();
    let (url, title, content) = match parts.as_slice() {
        [u, t, c] => (*u, *t, *c),
        _ => ("", "", combined.as_str()),
    };

    let out_path = resolve_output_path(filename.as_deref(), "extract", "txt");
    save_snapshot(&out_path, content).map_err(|e| e.to_string())?;

    // Detect silent extraction failures: the server may return a metadata-only
    // response with "completed": false and zero extracted data.  Warn the user
    // explicitly instead of silently saving an empty/ metadata-only file.
    let extraction_empty = detect_empty_extraction(content);

    json_field("page_url", json!(url));
    json_field("page_title", json!(title));
    json_field("extract_path", json!(out_path.display().to_string()));
    json_field("extracted_content", json!(content));
    if extraction_empty {
        json_field("extraction_empty", json!(true));
    }

    if raw {
        println!("{}", content);
    } else {
        cli_println!("### Page");
        cli_println!("- Page URL: {}", url);
        cli_println!("- Page Title: {}", title);
        if extraction_empty {
            cli_println!("### ⚠️  Extraction produced no data");
            cli_println!("The extract command completed but returned no structured data. The AI model may not have been able to identify the requested information on this page. Try:");
            cli_println!("  - Narrowing the scope with a more specific instruction");
            cli_println!("  - Using `eval` with a JavaScript selector for precise data extraction");
            cli_println!("  - Using `htmlsnapshot get` for CSS-based DOM extraction");
            cli_println!("[Raw response]({})", out_path.display());
        } else {
            cli_println!("### Extracted content");
            cli_println!("[Extracted content]({})", out_path.display());
        }
    }
    Ok(())
}

/// Check whether an extract response looks like a silent failure — the server
/// returned metadata (`"completed": false`) with no actual extracted data.
fn detect_empty_extraction(content: &str) -> bool {
    let trimmed = content.trim();
    if trimmed.is_empty() {
        return true;
    }
    // Try to parse as JSON and look for the "completed": false pattern that
    // signals the extraction pipeline produced no results.
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(trimmed) {
        if let Some(completed) = v
            .pointer("/data/metadata/completed")
            .and_then(|c| c.as_bool())
        {
            if !completed {
                return true;
            }
        }
        // Also detect the top-level form: {"success":true,"completed":false,…}
        if let Some(completed) = v
            .pointer("/completed")
            .and_then(|c| c.as_bool())
        {
            if !completed {
                return true;
            }
        }
        // If the response is a small metadata-only object (no data array/string),
        // treat it as empty.
        if let Some(data) = v.get("data") {
            if data.is_object() && data.get("metadata").is_some() && data.as_object().map_or(true, |o| o.len() <= 2) {
                return true;
            }
        }
    }
    false
}

/// Handle the `summarize` command: save AI-generated summary to a file by default,
/// print to stdout with `--raw`.
async fn handle_summarize(
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
    let raw = tool_params
        .get("raw")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
        || tool_params
            .get("stdout")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
    raw_init(raw);
    let summarize_args = {
        let mut a = tool_params.clone();
        if let Value::Object(ref mut m) = a {
            m.remove("filename");
            m.remove("raw");      // CLI-side flag, not a server parameter
            m.remove("stdout");   // CLI-side flag, not a server parameter
        }
        a
    };

    let combined = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut args = summarize_args.clone();
        args["sessionId"] = json!(session_id.clone());

        async move {
            let (url_res, title_res, summary_res) = tokio::join!(
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
                call_tool(&client, &base_url, &tool_name, args),
            );
            let url = url_res?;
            let title = title_res?;
            let content = summary_res?;
            Ok(format!("{}\n{}\n{}", url, title, content))
        }
    })
    .await?;

    let parts: Vec<&str> = combined.splitn(3, '\n').collect();
    let (url, title, content) = match parts.as_slice() {
        [u, t, c] => (*u, *t, *c),
        _ => ("", "", combined.as_str()),
    };

    let out_path = resolve_output_path(filename.as_deref(), "summarize", "txt");
    save_snapshot(&out_path, content).map_err(|e| e.to_string())?;

    let summary_empty = detect_empty_extraction(content);

    json_field("page_url", json!(url));
    json_field("page_title", json!(title));
    json_field("summarize_path", json!(out_path.display().to_string()));
    json_field("summarized_content", json!(content));
    if summary_empty {
        json_field("summarize_empty", json!(true));
    }

    if raw {
        println!("{}", content);
    } else {
        cli_println!("### Page");
        cli_println!("- Page URL: {}", url);
        cli_println!("- Page Title: {}", title);
        if summary_empty {
            cli_println!("### ⚠️  Summary produced no data");
            cli_println!("The summarize command completed but returned no content. The AI model may not have been able to process this page.");
            cli_println!("[Raw response]({})", out_path.display());
        } else {
            cli_println!("### Summary");
            cli_println!("[Summary]({})", out_path.display());
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// htmlsnapshot helpers
// ---------------------------------------------------------------------------

/// Extract the HTML tag name from a Section-8 element reference.
///
/// Ref format: `"#ancestorId tag#ownId.class1.class2"` or `"tag#id.class"` or bare `"tag"`.
/// Returns the tag portion (e.g. `"form"`, `"button"`, `"a"`).
fn extract_tag_from_ref(elem_ref: &str) -> &str {
    let after_ancestor = if elem_ref.starts_with('#') {
        elem_ref
            .find(' ')
            .map(|i| &elem_ref[i + 1..])
            .unwrap_or(elem_ref)
    } else {
        elem_ref
    };
    let tag_end = after_ancestor
        .find(|c: char| c == '#' || c == '.')
        .unwrap_or(after_ancestor.len());
    &after_ancestor[..tag_end]
}

/// Returns true if the element ref contains an auto-generated CSS module class name
/// (e.g. `a.css-2ietpx` where `css-2ietpx` is a hash-based CSS Module identifier).
/// These class names change on every site deployment and shouldn't be relied on
/// for long-lived extraction scripts.
fn is_ephemeral_css_module_ref(elem_ref: &str) -> bool {
    // Match patterns like "css-2ietpx" or "css-1a2b3c" (hash-based CSS module classes)
    // Also match patterns like "sc-bdvvtL", "sc-dkPtRN" (styled-components)
    // and "jss123", "emotion-1a2b3c" (other CSS-in-JS libraries)
    let re = regex::Regex::new(
        r"\b(css-[a-z0-9]+|sc-[a-zA-Z]+|jss\d+|emotion-[a-z0-9]+)\b"
    ).expect("ephemeral CSS class regex");
    re.is_match(elem_ref)
}

// ---------------------------------------------------------------------------
// htmlsnapshot handlers
// ---------------------------------------------------------------------------

async fn handle_html_snapshot_capture(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, params).await }
    })
    .await?;

    // Parse and display metadata JSON
    let metadata: Value = serde_json::from_str(&result)
        .map_err(|e| format!("Failed to parse snapshot metadata: {e}"))?;

    json_field("snapshot_metadata", json!(&metadata));

    // Display page info
    let url = metadata.get("url").and_then(|v| v.as_str()).unwrap_or("");
    let title = metadata.get("title").and_then(|v| v.as_str()).unwrap_or("");
    let size = metadata.get("sizeBytes").and_then(|v| v.as_str()).unwrap_or("");
    let captured = metadata.get("capturedAt").and_then(|v| v.as_str()).unwrap_or("");
    let content_type = metadata.get("contentType").and_then(|v| v.as_str()).unwrap_or("");

    let image_count = metadata.get("imageCount").and_then(|v| v.as_i64()).unwrap_or(0);
    let link_count = metadata.get("linkCount").and_then(|v| v.as_i64()).unwrap_or(0);
    let elements = metadata.get("interactiveElements").and_then(|v| v.as_array());
    let interactive_count = elements.map_or(0, |e| e.len());

    // Compact header: one-line title, one-line metadata
    if !title.is_empty() {
        cli_println!("Snapshot: \"{}\"", title);
    } else if !url.is_empty() {
        cli_println!("Snapshot: {}", url);
    } else {
        cli_println!("Snapshot captured");
    }

    // Line 2: URL + size + content-type + timestamp
    {
        let mut parts: Vec<String> = Vec::new();
        if !url.is_empty() {
            parts.push(url.to_string());
        }
        let size_bytes: i64 = size.parse().unwrap_or(0);
        if size_bytes >= 1024 {
            parts.push(format!("{} KB", size_bytes / 1024));
        } else if size_bytes > 0 {
            parts.push(format!("{} bytes", size_bytes));
        }
        if !content_type.is_empty() {
            parts.push(content_type.to_string());
        }
        if !captured.is_empty() {
            parts.push(format!("captured {}", captured));
        }
        if !parts.is_empty() {
            cli_println!("{}", parts.join(" · "));
        }
    }

    // Line 3: stats summary
    {
        let mut stats: Vec<String> = Vec::new();
        if image_count > 0 {
            stats.push(format!("{} images", image_count));
        }
        if link_count > 0 {
            stats.push(format!("{} links", link_count));
        }
        if interactive_count > 0 {
            stats.push(format!("{} interactive elements", interactive_count));
        }
        if !stats.is_empty() {
            cli_println!("{}", stats.join(" · "));
        }
    }

    // Display interactive elements sorted by visual prominence (weight descending).
    // The server returns elements in weight order; we preserve that order within
    // each tier group.  Each element carries a `ref` field that serves as both
    // its description and a unique CSS selector.
    if let Some(elements) = elements {
        if !elements.is_empty() {
            cli_println!("");
            cli_println!("### Interactive Elements");

            // Group by tier, keeping server order (already sorted by weight descending).
            // Tier "primary" = buttons, inputs, form controls (area-weighted).
            // Tier "link"    = anchor elements (group-area-weighted).
            let mut primary: Vec<&Value> = Vec::new();
            let mut links: Vec<&Value> = Vec::new();
            let mut other: Vec<&Value> = Vec::new();

            for el in elements.iter() {
                let tier = el.get("tier").and_then(|v| v.as_str()).unwrap_or("");
                match tier {
                    "link" => links.push(el),
                    "primary" => primary.push(el),
                    _ => other.push(el),
                }
            }

            let mut global_index: usize = 0;
            let desc_width: usize = 44; // wider for CSS selectors
            let text_width: usize = 48;


            // Helper to format a single element line.
            let format_element = |i: usize, el: &Value| -> String {
                let elem_ref = el.get("ref").and_then(|v| v.as_str()).unwrap_or("");
                let box_val = el.get("box").and_then(|v| v.as_str()).unwrap_or("");
                let text_val = el.get("text").and_then(|v| v.as_str()).unwrap_or("");
                let weight = el.get("weight").and_then(|v| v.as_i64()).unwrap_or(0);

                // The `ref` field IS the unique CSS selector (Section 8 format).
                let desc = elem_ref.to_string();

                // Truncate text
                let display_text = if text_val.is_empty() {
                    String::new()
                } else {
                    let trimmed: String = text_val.chars().take(text_width).collect();
                    if trimmed.len() < text_val.chars().count() {
                        format!("\"{}\u{2026}\"", trimmed) // … (ellipsis)
                    } else {
                        format!("\"{}\"", trimmed)
                    }
                };

                // Build extras: box and weight
                let mut extras: Vec<String> = Vec::new();
                if !box_val.is_empty() {
                    extras.push(format!("box={}", box_val));
                }
                extras.push(format!("w={}", weight));

                let mut line = format!("  {:>3}. ", i + 1);
                // Pad description to desc_width
                let desc_padded = if desc.len() < desc_width {
                    format!("{:<width$}", desc, width = desc_width)
                } else {
                    desc.to_string()
                };
                line.push_str(&desc_padded);

                if !display_text.is_empty() {
                    line.push_str(&format!(" {}  ", display_text));
                } else {
                    line.push_str("  ");
                }

                if !extras.is_empty() {
                    line.push_str(&format!("[{}]", extras.join("; ")));
                }

                line
            };

            // Helper to print a group.
            let mut print_group = |label: &str, group: &Vec<&Value>| {
                if !group.is_empty() {
                    cli_println!("  {} ({}):", label, group.len());
                    for el in group {
                        global_index += 1;
                        cli_println!("{}", format_element(global_index, el));
                    }
                    cli_println!("");
                }
            };

            // Sub-group primary controls by HTML tag for readability.
            let mut buttons: Vec<&Value> = Vec::new();
            let mut inputs: Vec<&Value> = Vec::new();
            let mut controls: Vec<&Value> = Vec::new();
            for el in &primary {
                let elem_ref = el.get("ref").and_then(|v| v.as_str()).unwrap_or("");
                let tag = extract_tag_from_ref(elem_ref);
                match tag {
                    "button" => buttons.push(el),
                    "input" | "textarea" | "select" => inputs.push(el),
                    _ => controls.push(el),
                }
            }

            print_group("Buttons", &buttons);
            print_group("Inputs", &inputs);
            if !controls.is_empty() {
                print_group("Controls", &controls);
            }
            print_group("Links", &links);
            print_group("Other", &other);

            // Warn if auto-generated CSS module class names are present.
            // These are ephemeral — they change on every site redeployment.
            let has_ephemeral = elements.iter().any(|el| {
                el.get("ref")
                    .and_then(|v| v.as_str())
                    .map(|r| is_ephemeral_css_module_ref(r))
                    .unwrap_or(false)
            });
            if has_ephemeral {
                cli_println!("  ⚠️  Auto-generated CSS class names detected (e.g. `css-2ietpx`).");
                cli_println!("    These are ephemeral — they may change on page reload or site redeployment.");
                cli_println!("    Use `htmlsnapshot inspect` to discover more resilient structural selectors,");
                cli_println!("    or `htmlsnapshot summary` to explore content by visual clustering.");
                cli_println!("");
            }
        }
    }

    // Next-step hints
    cli_println!("  💡 Try these next:");
    cli_println!("    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.");
    cli_println!("    The SQL variant lets you query with full expressive power (joins, filters, aggregates).");
    if !title.is_empty() {
        cli_println!("     htmlsnapshot get text \"h1\" --limit 5   # page heading");
    }
    cli_println!("     htmlsnapshot get all text \"a\" --limit 20  # link texts");
    cli_println!("     htmlsnapshot get attr \"img[src]\" src --limit 20  # image URLs");
    cli_println!("     htmlsnapshot get attr \"a[href]\" href --limit 20  # link URLs");
    if image_count > 0 {
        cli_println!("     htmlsnapshot get attr \"img[src]:expr(width > 200 && height > 200)\" src --limit 20  # large images only");
    }
    cli_println!("     htmlsnapshot inspect  # discover recurring patterns");
    if !url.is_empty() {
        cli_println!("     htmlsnapshot query --sql \"SELECT dom_text(dom) as text FROM load_and_select(@url, 'a')\"");
    }

    Ok(())
}

async fn handle_html_snapshot_get(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    // Validate field
    let field = tool_params
        .get("field")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    if field.is_empty() {
        return Err("Field is required: text, html, or attr.".to_string());
    }
    if !["text", "html", "attr"].contains(&field) {
        return Err(format!(
            "Unknown field '{}'. Use text, html, or attr.",
            field
        ));
    }

    // Validate selector - reject element references
    let selector = tool_params
        .get("selector")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    if is_element_reference(selector) {
        return Err(format!(
            "Element references ('{selector}') are not supported in htmlsnapshot get. Use a CSS selector instead."
        ));
    }

    // Validate attr field requires a name
    if field == "attr" {
        let attr_name = tool_params
            .get("attrName")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if attr_name.is_empty() {
            return Err(
                "The 'attr' field requires an attribute name as the third argument.".to_string(),
            );
        }
    }

    let result = with_session_paginated(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool_with_result(&client, &base_url, &tool_name, params).await }
    })
    .await
    .map_err(|e| {
        // Improve error messages for htmlsnapshot get failures.
        // The backend auto-captures when no snapshot exists, but if capture
        // itself fails, guide the user toward a fix.
        if e.contains("htmlsnapshot get failed") || e.contains("html_snapshot_scrape") {
            format!(
                "{}\n\nTip: Run `htmlsnapshot` first to explicitly capture the page, then try again.",
                e
            )
        } else {
            e
        }
    })?;

    let server_pagination = result.pagination;
    let text = result.text;

    // Output the result
    let empty_result = text == "null" || text.is_empty() || text.trim() == "[]";
    // Only HTML output is paginated by default; text extraction rarely exceeds
    // practical limits for single-field extraction so it defaults to --all.
    let paginate = (field == "html") && !empty_result;

    if empty_result {
        let display_selector = if selector.is_empty() { ":root" } else { selector };
        cli_println!("{}", text);
        cli_println!(
            "No elements matched \"{}\". Try `htmlsnapshot inspect \"{}\"` to discover valid selectors, or run `htmlsnapshot` to see the full DOM tree.",
            display_selector, display_selector
        );
    } else if paginate {
        if let Some(ref pm) = server_pagination {
            // Server already paginated — display as-is with server footer.
            cli_println!("{}", text);
            if pm.truncated {
                cli_println!(
                    "[Page {}/{} · {} lines of {} total · use --page N for next page · --all to show all]",
                    pm.page,
                    pm.total_pages,
                    (pm.page.min(pm.total_pages) * pm.page_size).min(pm.total_lines),
                    pm.total_lines
                );
            }
        } else {
            let (page, page_size, show_all) = parse_page_opts(tool_params);
            if skip_pagination(show_all) {
                cli_println!("{}", text);
            } else {
                let (page_content, meta) = paginate_output(&text, page, page_size);
                cli_println!("{}", page_content);
                if meta.is_truncated {
                    cli_println!("{}", format_pagination_footer(&meta));
                }
            }
        }
        json_field("total_chars", json!(text.len()));
        json_field("total_lines", json!(text.lines().count()));
        if let Some(ref pm) = server_pagination {
            json_field("page_size", json!(pm.page_size));
            json_field("truncated", json!(pm.truncated));
            json_field("page", json!(pm.page));
            json_field("total_pages", json!(pm.total_pages));
        } else {
            let (_page, page_size, show_all) = parse_page_opts(tool_params);
            json_field("page_size", json!(page_size));
            json_field("truncated", json!(!skip_pagination(show_all) && text.lines().count() > page_size));
        }
    } else {
        cli_println!("{}", text);
    }

    json_field("result", json!(&text));
    json_field("mode", json!(field));
    if !selector.is_empty() {
        json_field("selector", json!(selector));
    }

    Ok(())
}

/// Resolves an `@file` path for --sql and --selector. Tries the Browser4 repo
/// root first so that `@file` paths work consistently from any subdirectory
/// (including `cli/browser4-cli` when using `cargo run`), then falls back to
/// CWD for local file references.
fn resolve_sql_file(file_path: &str) -> Result<String, String> {
    let path = std::path::Path::new(file_path);

    // Absolute path — just try to read it
    if path.is_absolute() {
        return std::fs::read_to_string(path).map_err(|e| {
            format!("Failed to read SQL file '{}' ({})", file_path, e)
        });
    }

    let cwd = std::env::current_dir()
        .map_err(|e| format!("Cannot determine current directory: {e}"))?;
    let cwd_path = cwd.join(file_path);

    // Try Browser4 repo root first (consistent resolution from any subdirectory)
    if let Some(root) = daemon::find_browser4_root() {
        let root_path = root.join(file_path);
        if root_path != cwd_path {
            if let Ok(content) = std::fs::read_to_string(&root_path) {
                return Ok(content);
            }
        }
    }

    // Fall back to CWD (for files that only exist relative to the working directory)
    if let Ok(content) = std::fs::read_to_string(&cwd_path) {
        return Ok(content);
    }

    // Build a clear error message showing where we looked
    let mut tried = vec![cwd_path.display().to_string()];
    if let Some(root) = daemon::find_browser4_root() {
        let root_path = root.join(file_path);
        let root_display = root_path.display().to_string();
        if root_display != tried[0] {
            tried.push(root_display);
        }
    }
    Err(format!(
        "Failed to read file '{}'\n  Tried: {}",
        file_path,
        tried.join("\n  Tried: "),
    ))
}

/// Base64-decode the SQL string if `--sql-base64` was passed.
/// Supports two modes:
///   1. `--sql-base64 <base64-query>` — the value is read directly from the option.
///   2. `--sql <base64-query> --sql-base64` — legacy boolean-flag mode.
/// Applied after `@file` resolution so base64-encoded files are also supported.
fn maybe_decode_base64_sql(sql: String, tool_params: &Value) -> Result<String, String> {
    // Mode 1: --sql-base64 <value> (direct value mode)
    if let Some(base64_val) = tool_params.get("sqlBase64").and_then(|v| v.as_str()) {
        let trimmed = base64_val.trim();
        if trimmed.is_empty() {
            return Err("--sql-base64 was set but the value is empty.".to_string());
        }
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(trimmed)
            .map_err(|e| format!("Failed to base64-decode SQL: {e}"))?;
        return String::from_utf8(bytes)
            .map_err(|e| format!("Base64-decoded SQL is not valid UTF-8: {e}"));
    }
    // Mode 2: --sql <value> --sql-base64 (legacy boolean-flag mode)
    let use_base64 = tool_params
        .get("sqlBase64")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    if !use_base64 {
        return Ok(sql);
    }
    if sql.trim().is_empty() {
        return Err("--sql-base64 was set but the SQL value is empty. Use --sql <base64-value> --sql-base64, or --sql-base64 <base64-value>.".to_string());
    }
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(sql.trim())
        .map_err(|e| format!("Failed to base64-decode SQL: {e}"))?;
    String::from_utf8(bytes)
        .map_err(|e| format!("Base64-decoded SQL is not valid UTF-8: {e}"))
}

async fn handle_html_snapshot_query(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let url = tool_params
        .get("url")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    // Handle --sql-stdin: read query from stdin (avoids shell quoting issues on Windows)
    let use_sql_stdin = tool_params
        .get("sqlStdin")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let sql_raw = if use_sql_stdin {
        let mut input = String::new();
        std::io::stdin()
            .read_to_string(&mut input)
            .map_err(|e| format!("Failed to read X-SQL query from stdin: {e}"))?;
        if input.trim().is_empty() {
            return Err(
                "Stdin was empty. Provide a non-empty X-SQL query via stdin.".to_string(),
            );
        }
        input
    } else {
        tool_params
            .get("sql")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string()
    };

    // Allow --sql-base64 standalone (without --sql): the base64 value is decoded
    // later by maybe_decode_base64_sql. If sqlBase64 has a non-empty string value,
    // we can proceed with an empty sql_raw; the decode will supply the actual SQL.
    let has_sql_base64_value = tool_params
        .get("sqlBase64")
        .and_then(|v| v.as_str())
        .map(|s| !s.trim().is_empty())
        .unwrap_or(false);

    if sql_raw.is_empty() && !has_sql_base64_value {
        return Err(
            "--sql is required. Provide an inline X-SQL query, @file.sql, --sql-stdin, or --sql-base64."
                .to_string(),
        );
    }

    // Track whether the SQL was inline (not @file, not stdin, not base64)
    // for a post-query tip suggesting @file.sql to avoid shell quoting issues
    let is_inline_sql = !use_sql_stdin
        && !sql_raw.starts_with('@')
        && !sql_raw.is_empty()
        && !has_sql_base64_value;

    // Handle --sql @file.sql pattern
    let sql = if sql_raw.starts_with('@') {
        let file_path = &sql_raw[1..];
        resolve_sql_file(file_path)?
    } else {
        sql_raw
    };

    // Handle --sql-base64: decode base64-encoded SQL
    let sql = maybe_decode_base64_sql(sql, tool_params)?;

    // @url replacement is handled server-side by SQLTemplate.createSQL(url),
    // which properly escapes the URL value. Do NOT eagerly replace @url here —
    // naive string substitution would break on URLs containing quotes or other
    // special characters. Note: @url must appear UNQUOTED in the SQL
    // (e.g. `load_and_select(@url, ':root')` not `load_and_select('@url', ':root')`).
    let processed_sql = sql;

    let mut params = json!({ "sql": processed_sql });
    if !url.is_empty() {
        params["url"] = json!(url);
    }

    let result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut p = params.clone();
        p["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, p).await }
    })
    .await?;

    let result_only = tool_params
        .get("resultOnly")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let output_file = tool_params
        .get("outputFile")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let format = tool_params
        .get("format")
        .and_then(|v| v.as_str())
        .unwrap_or("json")
        .to_ascii_lowercase();

    // Validate --format value
    match format.as_str() {
        "json" | "csv" | "table" => {}
        _ => return Err(format!("Invalid --format '{}'. Expected: json, csv, or table", format)),
    }

    // Process output: extract resultSet if --result-only, then write to file or stdout
    let output = if result_only || format.as_str() != "json" {
        // Try to parse the server JSON and extract the resultSet
        match serde_json::from_str::<Value>(&result) {
            Ok(parsed) => {
                let rows: Option<&Vec<Value>> = parsed
                    .get("resultSet")
                    .and_then(|rs| rs.as_array());

                match rows {
                    Some(rows) if format.as_str() != "json" => {
                        // Format as table or CSV
                        let summary = format!("\n{} row{} returned.\n", rows.len(), if rows.len() == 1 { "" } else { "s" });
                        match format.as_str() {
                            "csv" => format_csv(rows) + &summary,
                            "table" => format_table(rows) + &summary,
                            _ => unreachable!(),
                        }
                    }
                    _ => {
                        // JSON format: pretty-print resultSet or full result
                        let output_data = if let Some(result_set) = parsed.get("resultSet") {
                            serde_json::to_string_pretty(result_set)
                                .unwrap_or_else(|_| result.clone())
                        } else if result_only {
                            if !json_active() {
                                eprintln!(
                                    "⚠️  --result-only set but no 'resultSet' field found in response. \
                                     Showing full result."
                                );
                            }
                            result.clone()
                        } else {
                            result.clone()
                        };
                        output_data
                    }
                }
            }
            Err(_) => {
                // Not valid JSON — return as-is
                if result_only && !json_active() {
                    eprintln!(
                        "⚠️  --result-only set but response is not valid JSON. \
                         Showing raw result."
                    );
                }
                result.clone()
            }
        }
    } else {
        result.clone()
    };

    if let Some(out_file) = output_file {
        std::fs::write(&out_file, &output)
            .map_err(|e| format!("Failed to write output to '{}': {}", out_file, e))?;
        cli_println!("Output written to: {}", out_file);
    } else if !output.is_empty() {
        cli_println!("{}", output);
    }

    // Tip: if SQL was provided inline (not @file, not stdin, not base64), suggest
    // using @file.sql to avoid shell quoting issues on Windows.
    if is_inline_sql {
        cli_println!(
            "\n💡 Tip: Use --sql @file.sql to avoid shell quoting issues. \
             Write your X-SQL query to a file and reference it with @filename.sql."
        );
    }

    json_field("result", json!(&result));

    Ok(())
}

async fn handle_html_snapshot_export(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let file_path = tool_params
        .get("file")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            let name = snapshot::timestamped_filename("htmlsnapshot", "html");
            snapshot::snapshot_dir().join(name)
        });

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

    snapshot::save_binary(&file_path, result.as_bytes()).map_err(|e| e.to_string())?;
    cli_println!("Snapshot saved to: {}", file_path.display());
    json_field("path", json!(file_path.display().to_string()));

    Ok(())
}

/// Parse the WPSI YAML summary and produce a compact outline for stdout display.
/// The full summary is always saved to file; this extracts the key structure.
fn format_summary_outline(yaml: &str, verbose: bool) -> String {
    let mut outline = String::new();

    // Track which top-level section we're in and accumulate items.
    enum Section { None, Page, Structure, Content, Lists, LinkGroups, Tables, Stats }
    let mut section = Section::None;
    let mut page_type = "";
    let mut structure_count = 0;
    let mut structure_items: Vec<String> = Vec::new();
    let mut content_count = 0;
    let mut content_items: Vec<(String, String, String, String)> = Vec::new(); // (type, score, text, repeats)
    let mut list_count = 0;
    let mut list_items: Vec<String> = Vec::new();
    let mut linkgroup_count = 0;
    let mut linkgroup_items: Vec<String> = Vec::new();
    let mut linkgroup_selectors: Vec<String> = Vec::new(); // for suggested commands
    let mut cur_linkgroup_selector = String::new();
    let mut table_count = 0;
    let mut table_items: Vec<String> = Vec::new();
    let mut stats_lines: Vec<String> = Vec::new();

    // Track current content item being built across multiple lines
    let mut cur_content_type = String::new();
    let mut cur_content_score = String::new();
    let mut cur_content_text = String::new();
    let mut cur_content_repeats = String::new();

    for line in yaml.lines() {
        // Detect top-level section keys
        if line == "page:" {
            section = Section::Page;
            continue;
        } else if line == "structure:" {
            section = Section::Structure;
            continue;
        } else if line == "content:" {
            section = Section::Content;
            continue;
        } else if line == "lists:" {
            section = Section::Lists;
            continue;
        } else if line == "linkGroups:" {
            section = Section::LinkGroups;
            continue;
        } else if line == "tables:" {
            section = Section::Tables;
            continue;
        } else if line == "stats:" {
            section = Section::Stats;
            continue;
        }

        // Skip empty lines and YAML document markers
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed == "---" {
            continue;
        }

        match section {
            Section::Page => {
                if let Some(val) = trim_prefix(trimmed, "type:") {
                    page_type = val.trim_matches('"');
                }
            }
            Section::Structure => {
                // Lines starting with "  - box:" start a new structure item
                if trimmed.starts_with("- box:") {
                    structure_count += 1;
                } else if let Some(tag) = trim_prefix(trimmed, "tag:") {
                    structure_items.push(format!("  {}", tag.trim()));
                } else if let Some(sel) = trim_prefix(trimmed, "selector:") {
                    if let Some(last) = structure_items.last_mut() {
                        *last = format!("{}  {}", last, sel.trim().trim_matches('"'));
                    }
                }
            }
            Section::Content => {
                // YAML content entries start with "  - ref:", not "- box:"
                if trimmed.starts_with("- ref:") {
                    // Flush previous content item
                    if !cur_content_type.is_empty() {
                        content_items.push((
                            std::mem::take(&mut cur_content_type),
                            std::mem::take(&mut cur_content_score),
                            std::mem::take(&mut cur_content_text),
                            std::mem::take(&mut cur_content_repeats),
                        ));
                    }
                    content_count += 1;
                    cur_content_type.clear();
                    cur_content_score.clear();
                    cur_content_text.clear();
                    cur_content_repeats.clear();
                } else if let Some(t) = trim_prefix(trimmed, "type:") {
                    cur_content_type = t.trim().to_string();
                } else if let Some(s) = trim_prefix(trimmed, "score:") {
                    cur_content_score = s.trim().to_string();
                } else if let Some(r) = trim_prefix(trimmed, "repeats:") {
                    cur_content_repeats = r.trim().to_string();
                } else if let Some(txt) = trim_prefix(trimmed, "text:") {
                    cur_content_text = txt.trim().trim_matches('"').to_string();
                }
            }
            Section::Lists => {
                // Each list entry starts with "  - parentTag: label"
                if let Some(parent) = trim_prefix(trimmed, "- parentTag:") {
                    list_count += 1;
                    list_items.push(format!("  {}", parent.trim()));
                } else if let Some(item) = trim_prefix(trimmed, "itemTag:") {
                    if let Some(last) = list_items.last_mut() {
                        *last = format!("{} > {}", last, item.trim());
                    }
                } else if let Some(count) = trim_prefix(trimmed, "count:") {
                    if let Some(last) = list_items.last_mut() {
                        *last = format!("{} ({} items)", last, count.trim());
                    }
                }
            }
            Section::LinkGroups => {
                // Each link group entry starts with "  - container: label"
                if let Some(container) = trim_prefix(trimmed, "- container:") {
                    linkgroup_count += 1;
                    linkgroup_items.push(format!("  {}", container.trim().trim_matches('"')));
                    cur_linkgroup_selector.clear();
                } else if let Some(sel) = trim_prefix(trimmed, "selector:") {
                    cur_linkgroup_selector = sel.trim().trim_matches('"').to_string();
                } else if let Some(item) = trim_prefix(trimmed, "itemTag:") {
                    // When we see the itemTag, the selector for this group is complete
                    if !cur_linkgroup_selector.is_empty() {
                        linkgroup_selectors.push(cur_linkgroup_selector.clone());
                    }
                    if let Some(last) = linkgroup_items.last_mut() {
                        *last = format!("{} > {}", last, item.trim());
                    }
                } else if let Some(count) = trim_prefix(trimmed, "count:") {
                    if let Some(last) = linkgroup_items.last_mut() {
                        *last = format!("{}  {} items", last, count.trim());
                    }
                } else if let Some(cols) = trim_prefix(trimmed, "columnCount:") {
                    if let Some(last) = linkgroup_items.last_mut() {
                        let label = if cols.trim() == "1" { "list" } else { &format!("grid({} cols)", cols.trim()) };
                        *last = format!("{}  {}", last, label);
                    }
                } else if let Some(w) = trim_prefix(trimmed, "avgCardWidth:") {
                    if let Some(last) = linkgroup_items.last_mut() {
                        *last = format!("{}  avg:{}×", last, fmt_num(w));
                    }
                } else if let Some(h) = trim_prefix(trimmed, "avgCardHeight:") {
                    if let Some(last) = linkgroup_items.last_mut() {
                        *last = format!("{}{}", last, fmt_num(h));
                    }
                } else if let Some(links) = trim_prefix(trimmed, "allHaveLinks:") {
                    if links.trim() == "true" {
                        if let Some(last) = linkgroup_items.last_mut() {
                            *last = format!("{}  +links", last);
                        }
                    }
                } else if let Some(imgs) = trim_prefix(trimmed, "anyHaveImages:") {
                    if imgs.trim() == "true" {
                        if let Some(last) = linkgroup_items.last_mut() {
                            *last = format!("{}  +imgs", last);
                        }
                    }
                } else if let Some(score) = trim_prefix(trimmed, "score:") {
                    if verbose {
                        if let Some(last) = linkgroup_items.last_mut() {
                            *last = format!("{}  score:{}", last, fmt_num(score));
                        }
                    }
                }
            }
            Section::Tables => {
                if trimmed.starts_with("- box:") {
                    table_count += 1;
                } else if let Some(rows) = trim_prefix(trimmed, "rows:") {
                    table_items.push(format!("  {} rows", rows.trim()));
                } else if let Some(cols) = trim_prefix(trimmed, "cols:") {
                    if let Some(last) = table_items.last_mut() {
                        *last = format!("{} × {} cols", last, cols.trim());
                    }
                } else if let Some(_h) = trim_prefix(trimmed, "headers:") {
                    // Headers follow on indented lines
                } else if trimmed.starts_with("- ") && trimmed.len() > 2 {
                    // Header value like "  - Name"
                    let hdr = trimmed[2..].trim().trim_matches('"');
                    if let Some(last) = table_items.last_mut() {
                        if !last.contains('[') {
                            *last = format!("{} [{}", last, hdr);
                        } else {
                            *last = format!("{}, {}", last, hdr);
                        }
                    }
                }
            }
            Section::Stats => {
                if trimmed.contains(':') && !trimmed.starts_with('-') {
                    stats_lines.push(trimmed.to_string());
                }
            }
            Section::None => {}
        }
    }

    // Flush final content item
    if !cur_content_type.is_empty() {
        content_items.push((cur_content_type, cur_content_score, cur_content_text, cur_content_repeats));
    }
    // Close any open table bracket
    for item in &mut table_items {
        if item.contains('[') && !item.contains(']') {
            item.push(']');
        }
    }

    // ---- Build output ----
    // Page section
    outline.push_str("### Page\n");
    outline.push_str(&format!("- Type: {}\n", if page_type.is_empty() { "—" } else { page_type }));

    // Link Groups section (highest priority — product/comment/article lists contain the most important data)
    if linkgroup_count > 0 {
        outline.push_str(&format!("\n### Link Groups ({} detected)\n", linkgroup_count));
        for item in &linkgroup_items.iter().take(10).collect::<Vec<_>>() {
            outline.push_str(&format!("{}\n", item));
        }
        if linkgroup_items.len() > 10 {
            outline.push_str(&format!("  ... and {} more\n", linkgroup_items.len() - 10));
        }
    }

    // Structure section
    if structure_count > 0 {
        outline.push_str(&format!("\n### Structure ({} {})\n", structure_count,
            if structure_count == 1 { "landmark" } else { "landmarks" }));
        for item in &structure_items {
            outline.push_str(&format!("{}\n", item));
        }
    }

    // Content section — show top 20 scored items
    if !content_items.is_empty() {
        let shown = content_items.len().min(20);
        outline.push_str(&format!("\n### Content ({} of {} nodes)\n", shown, content_count));
        for (i, (typ, score, text, repeats)) in content_items.iter().take(20).enumerate() {
            let display_text = if text.len() > 60 {
                format!("{}…", &text[..60])
            } else {
                text.clone()
            };
            let repeat_suffix = if repeats.is_empty() || repeats == "1" {
                String::new()
            } else {
                format!(" ×{}", repeats)
            };
            if verbose {
                if display_text.is_empty() {
                    outline.push_str(&format!("  {:>2}. {:<10} score:{}{}\n", i + 1, typ, fmt_num(score), repeat_suffix));
                } else {
                    outline.push_str(&format!("  {:>2}. {:<10} score:{:<4} \"{}\"{}\n", i + 1, typ, fmt_num(score), display_text, repeat_suffix));
                }
            } else {
                if display_text.is_empty() {
                    outline.push_str(&format!("  {:>2}. {:<10}{}\n", i + 1, typ, repeat_suffix));
                } else {
                    outline.push_str(&format!("  {:>2}. {:<10} \"{}\"{}\n", i + 1, typ, display_text, repeat_suffix));
                }
            }
        }
        if verbose {
            // Score legend: explain what the numbers mean
            outline.push_str("  ── Score scale: h1=100 h2=50 h3=30 table=60 btn/input=50 form=40 img=20(alt)/5 a=15 p~len/4 +id(10) +cls(5)\n");
        }
    }

    // Lists section
    if list_count > 0 {
        outline.push_str(&format!("\n### Lists ({} detected)\n", list_count));
        for item in &list_items.iter().take(10).collect::<Vec<_>>() {
            outline.push_str(&format!("{}\n", item));
        }
        if list_items.len() > 10 {
            outline.push_str(&format!("  ... and {} more\n", list_items.len() - 10));
        }
    }

    // Tables section
    if table_count > 0 {
        outline.push_str(&format!("\n### Tables ({} detected)\n", table_count));
        for item in &table_items.iter().take(10).collect::<Vec<_>>() {
            outline.push_str(&format!("{}\n", item));
        }
    }

    // Stats section
    if !stats_lines.is_empty() {
        outline.push_str("\n### Stats\n");
        // Format as compact single line
        let compact: Vec<String> = stats_lines.iter().map(|s| s.trim().to_string()).collect();
        outline.push_str(&format!("  {}\n", compact.join("  ")));
    }

    // Suggested commands — copy-paste ready extraction commands based on
    // discovered link groups and content sections
    let has_actionable_selectors = !linkgroup_selectors.is_empty()
        || content_items.iter().any(|(typ, _, _, _)| typ == "h1" || typ == "h2");
    if has_actionable_selectors {
        outline.push_str("\n### Suggested Commands\n");
        outline.push_str("  Copy-paste ready — use these to extract data:\n");

        // Suggest commands for link groups (best available targets)
        for sel in linkgroup_selectors.iter().take(3) {
            let cmd = format!("htmlsnapshot get all text \"{} a\" --limit 20", sel);
            outline.push_str(&format!("  {}\n", cmd));
        }

        // If no link groups, suggest based on content types
        if linkgroup_selectors.is_empty() {
            if content_items.iter().any(|(typ, _, _, _)| typ == "h1") {
                outline.push_str("  htmlsnapshot get all text \"h1\"\n");
            }
            if content_items.iter().any(|(typ, _, _, _)| typ == "h2") {
                outline.push_str("  htmlsnapshot get all text \"h2\"\n");
            }
            outline.push_str("  htmlsnapshot get all text \"a\" --limit 20\n");
        }

        if verbose {
            outline.push_str("  # Use --verbose to see internal scoring that ranks these suggestions.\n");
        } else {
            outline.push_str("  # Add --verbose to see internal scoring and score legend.\n");
        }
    }

    outline
}

/// Trim a prefix from a string, returning the remainder trimmed.
fn trim_prefix<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    s.strip_prefix(prefix).map(|r| r.trim())
}

/// Format a numeric string to at most one decimal place.
fn fmt_num(s: &str) -> String {
    match s.trim().parse::<f64>() {
        Ok(v) => {
            let s = format!("{:.1}", v);
            let s = s.trim_end_matches('0').trim_end_matches('.');
            s.to_string()
        }
        Err(_) => s.to_string(),
    }
}

async fn handle_html_snapshot_summary(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let raw = tool_params
        .get("raw")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
        || tool_params
            .get("stdout")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
    raw_init(raw);

    let verbose = tool_params
        .get("verbose")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let combined = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();

        async move {
            let (url_res, title_res, summary_res) = tokio::join!(
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
                call_tool(&client, &base_url, &tool_name, json!({ "sessionId": session_id })),
            );
            let url = url_res?;
            let title = title_res?;
            let summary = summary_res?;
            Ok(format!("{}\n{}\n{}", url, title, summary))
        }
    })
    .await?;

    // The combined result has url, title, and summary separated by newlines
    let parts: Vec<&str> = combined.splitn(3, '\n').collect();
    let (url, title, summary) = match parts.as_slice() {
        [u, t, s] => (*u, *t, *s),
        _ => ("", "", combined.as_str()),
    };

    let out_path = resolve_output_path(None, "htmlsnapshot-summary", "yml");
    save_snapshot(&out_path, summary).map_err(|e| e.to_string())?;

    json_field("page_url", json!(url));
    json_field("page_title", json!(title));
    json_field("summary_path", json!(out_path.display().to_string()));

    if raw {
        println!("{}", summary);
    } else {
        let outline = format_summary_outline(summary, verbose);
        cli_println!("### Page");
        cli_println!("- Page URL: {}", url);
        cli_println!("- Page Title: {}", title);
        cli_println!("{}", outline);
        cli_println!("");
        cli_println!("💾 Full summary saved to {}", out_path.display());
    }
    Ok(())
}

async fn handle_html_snapshot_inspect(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    // --- Resolve selector from file/stdin/base64 (CLI-side, stripped before server call) ---

    // Check --stdin flag: read selector from stdin (avoids shell quoting issues on Windows)
    let use_stdin = tool_params
        .get("stdin")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let raw_selector = if use_stdin {
        let mut input = String::new();
        std::io::stdin()
            .read_to_string(&mut input)
            .map_err(|e| format!("Failed to read CSS selector from stdin: {e}"))?;
        if input.trim().is_empty() {
            return Err("Stdin was empty but --stdin was specified.".to_string());
        }
        Some(input.trim().to_string())
    } else {
        tool_params
            .get("selector")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
    };

    // Resolve @file prefix: read selector from a file
    let selector = match raw_selector {
        Some(ref s) if s.starts_with('@') => {
            let file_path = &s[1..];
            resolve_sql_file(file_path)? // reuses the existing file-resolution helper
        }
        Some(s) => s,
        None => String::new(), // empty = use server default (:root)
    };

    // Handle --selector-base64: decode base64-encoded selector
    let selector = if let Some(base64_val) = tool_params
        .get("selectorBase64")
        .and_then(|v| v.as_str())
    {
        let trimmed = base64_val.trim();
        if trimmed.is_empty() {
            return Err("--selector-base64 was set but the value is empty.".to_string());
        }
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(trimmed)
            .map_err(|e| format!("Failed to base64-decode CSS selector: {e}"))?;
        String::from_utf8(bytes)
            .map_err(|e| format!("Base64-decoded CSS selector is not valid UTF-8: {e}"))?
    } else {
        selector
    };

    // Build server-bound params (strip CLI-only keys)
    let mut server_params = tool_params.clone();
    if let Value::Object(ref mut m) = server_params {
        m.remove("stdin");
        m.remove("selectorBase64");
        if !selector.is_empty() {
            m.insert("selector".to_string(), json!(selector));
        } else {
            m.remove("selector");
        }
    }

    let result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = server_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, params).await }
    })
    .await?;

    let data: Value = serde_json::from_str(&result)
        .map_err(|e| format!("Failed to parse inspect result: {e}"))?;

    let selector = data.get("selector").and_then(|v| v.as_str()).unwrap_or(":root");
    let match_count = data.get("matchCount").and_then(|v| v.as_i64()).unwrap_or(0);
    let analyzed = data.get("analyzed").and_then(|v| v.as_u64()).unwrap_or(0);
    let auto_discovered = data.get("autoDiscovered").and_then(|v| v.as_bool()).unwrap_or(false);
    let original_selector = data.get("originalSelector").and_then(|v| v.as_str());
    let speculative_selector = data.get("speculativeSuggestion").and_then(|v| v.as_str());
    let speculative_count = data.get("speculativeMatchCount").and_then(|v| v.as_i64());

    // Render speculative suggestion (Mode B: visual detection found a better
    // repeating pattern than the user's selector, but we didn't override).
    let render_speculative = |sel: &str, count: i64| {
        cli_println!("");
        cli_println!("  💡 Visual geometry detection found a potentially better repeating pattern:");
        cli_println!("     \"{}\" — {} occurrences with consistent bounding-box geometry.", sel, count);
        cli_println!("     Try: htmlsnapshot inspect \"{}\"", sel);
    };

    if match_count == 0 {
        cli_println!("### Inspect: \"{}\" (0 matches)", selector);
        if auto_discovered {
            if let Some(orig) = original_selector {
                cli_println!("  Auto-discovered selector \"{}\" from \"{}\" also had no matches.", selector, orig);
            }
        }
        if let (Some(sel), Some(count)) = (speculative_selector, speculative_count) {
            render_speculative(sel, count);
        }
        cli_println!("- No elements matched. Check the CSS selector and ensure a HTML snapshot has been captured (`browser4-cli htmlsnapshot`).");
        json_field("matchCount", json!(0));
        json_field("selector", json!(selector));
        return Ok(());
    }

    cli_println!(
        "### Inspect: \"{}\" ({} matches, {} analyzed)",
        selector, match_count, analyzed
    );
    if auto_discovered {
        if let Some(orig) = original_selector {
            cli_println!("  🔍 Auto-discovered repeating pattern from \"{}\"", orig);
            cli_println!("    The tool found that \"{}\" repeats as a sibling group (e.g. a product grid, search result list).", orig);
            cli_println!("    It analyzed {} of the {} occurrences to find selectors that work consistently.", analyzed, match_count);
        }
    } else if let (Some(sel), Some(count)) = (speculative_selector, speculative_count) {
        // Only show speculative suggestion when we did NOT auto-discover
        // (auto-discovery already overrides the selector; speculative is for
        // when the user's selector was kept but a better one exists).
        render_speculative(sel, count);
    }

    // Sample structures
    if let Some(samples) = data.get("samples").and_then(|v| v.as_array()) {
        if !samples.is_empty() {
            cli_println!("");
            cli_println!(
                "  Sample structure ({} of {}):",
                samples.len(),
                match_count
            );
            cli_println!("    Showing {} representative element(s) out of {} total matches.", samples.len(), match_count);
            cli_println!("    Each element shows its CSS selector and bounding box (x y width height in px).");
            cli_println!("    Indented lines are child elements found inside it.");
            for (i, sample) in samples.iter().enumerate() {
                // Section 8 format: use "ref" (e.g. "li.feed-carousel-card") instead of
                // separate tag/id/class fields. Fall back to legacy fields for compatibility.
                let elem_ref = sample.get("ref").and_then(|v| v.as_str()).unwrap_or("");
                let text = sample.get("text").and_then(|v| v.as_str()).unwrap_or("");
                let box_val = sample.get("box").and_then(|v| v.as_str()).unwrap_or("");

                let mut desc = elem_ref.to_string();
                if desc.is_empty() {
                    // Legacy fallback: reconstruct from tag/id/class
                    let tag = sample.get("tag").and_then(|v| v.as_str()).unwrap_or("");
                    let id = sample.get("id").and_then(|v| v.as_str()).unwrap_or("");
                    let class = sample.get("class").and_then(|v| v.as_str()).unwrap_or("");
                    desc = tag.to_string();
                    if !id.is_empty() { desc.push_str(&format!("#{}", id)); }
                    if !class.is_empty() { desc.push_str(&format!(".{}", class)); }
                }
                cli_println!("  -- Element {}: {}", i + 1, desc);
                if !box_val.is_empty() {
                    cli_println!("     box: {}", box_val);
                }
                if !text.is_empty() {
                    cli_println!("     text: \"{}\"", text);
                }

                // Children
                if let Some(children) = sample.get("children").and_then(|v| v.as_array()) {
                    for child in children {
                        let cref = child.get("ref").and_then(|v| v.as_str()).unwrap_or("");
                        let ctext = child.get("text").and_then(|v| v.as_str()).unwrap_or("");
                        let cbox = child.get("box").and_then(|v| v.as_str()).unwrap_or("");

                        let mut cdesc = if cref.is_empty() {
                            // Legacy fallback
                            let ctag = child.get("tag").and_then(|v| v.as_str()).unwrap_or("");
                            let cid = child.get("id").and_then(|v| v.as_str()).unwrap_or("");
                            let cclass = child.get("class").and_then(|v| v.as_str()).unwrap_or("");
                            let mut d = format!("{:>4} ", ctag);
                            if !cid.is_empty() { d.push_str(&format!("#{}", cid)); }
                            if !cclass.is_empty() { d.push_str(&format!(".{}", cclass)); }
                            d
                        } else {
                            format!("{:>4} {}", "", cref)
                        };
                        if !cbox.is_empty() {
                            cdesc.push_str(&format!("  [{}]", cbox));
                        }
                        if !ctext.is_empty() {
                            cdesc.push_str(&format!("  \"{}\"", ctext));
                        }
                        cli_println!("   {}", cdesc);
                    }
                }
            }
        }
    }

    // Suggestions
    if let Some(suggestions) = data.get("suggestions").and_then(|v| v.as_array()) {
        if !suggestions.is_empty() {
            // Split into quality suggestions and bare-tag fallbacks
            let (quality_sugs, bare_sugs): (Vec<_>, Vec<_>) = suggestions
                .iter()
                .partition(|s| {
                    let tag = s.get("tag").and_then(|v| v.as_str()).unwrap_or("");
                    let sel = s.get("selector").and_then(|v| v.as_str()).unwrap_or("");
                    // Bare tag = selector is just the tag name (no class/id/attr brackets)
                    sel != tag
                });

            // Helper to render a single suggestion row
            let render_suggestion = |sug: &Value| {
                let sel = sug.get("selector").and_then(|v| v.as_str()).unwrap_or("");
                let count = sug.get("matchCount").and_then(|v| v.as_i64()).unwrap_or(0);
                let coverage = sug.get("coverage").and_then(|v| v.as_str()).unwrap_or("");
                let quality = sug.get("quality").and_then(|v| v.as_str()).unwrap_or("");

                // Quality indicator
                let star = match quality {
                    "high" => "★ ",
                    _ => "  ",
                };

                // Value samples from textSamples (new) or fall back to textPreview (old)
                let text_hint = if let Some(samples) = sug.get("textSamples").and_then(|v| v.as_array()) {
                    let vals: Vec<&str> = samples.iter()
                        .filter_map(|v| v.as_str())
                        .filter(|s| !s.is_empty())
                        .take(3)
                        .collect();
                    if vals.is_empty() {
                        String::new()
                    } else {
                        format!("→ \"{}\"", vals.join("\" | \""))
                    }
                } else {
                    let text = sug.get("textPreview").and_then(|v| v.as_str()).unwrap_or("");
                    if text.is_empty() {
                        String::new()
                    } else {
                        format!("→ \"{}\"", text)
                    }
                };

                cli_println!(
                    "  {}{:>3}/{} ({})  {:<40} {}",
                    star, count, analyzed, coverage, sel, text_hint
                );
            };

            cli_println!("");
            cli_println!("  Suggested selectors (recurring across matches):");
            cli_println!("    Each row is a CSS selector that finds the same kind of element inside each match.");
            cli_println!("    ★ = high-quality (specific enough to use reliably).");
            cli_println!("    N/N (%) = how many of the analyzed matches contained this element / coverage.");
            cli_println!("    → \"...\" = sample values extracted by this selector.");
            cli_println!("    Use these selectors with `htmlsnapshot get` to extract data.");

            // Render quality suggestions first (with class/id/attr specificity)
            for sug in &quality_sugs {
                render_suggestion(sug);
            }

            // Render bare-tag fallbacks grouped at bottom
            if !bare_sugs.is_empty() {
                cli_println!("");
                cli_println!("  Structural (bare tags, low specificity):");
                cli_println!("    These match too broadly — use only as a fallback or with :expr() filters.");
                for sug in &bare_sugs {
                    render_suggestion(sug);
                }
            }
        }
    }

    // Dynamic next-step tips based on discovered selectors
    cli_println!("");
    if let Some(suggestions) = data.get("suggestions").and_then(|v| v.as_array()) {
        // Pick up to 3 medium+ quality selectors with class/id specificity
        let actionable: Vec<&str> = suggestions
            .iter()
            .filter_map(|s| {
                let sel = s.get("selector").and_then(|v| v.as_str()).unwrap_or("");
                let tag = s.get("tag").and_then(|v| v.as_str()).unwrap_or("");
                let quality = s.get("quality").and_then(|v| v.as_str()).unwrap_or("");
                // Only suggest specific selectors (not bare tags) and not low quality
                if sel != tag && quality != "low" {
                    Some(sel)
                } else {
                    None
                }
            })
            .take(3)
            .collect();

        if !actionable.is_empty() {
            cli_println!("  💡 Try these next:");
            cli_println!("    Use `get all text` to extract visible text, or `get all attr <name>` for attribute values.");
            cli_println!("    The SQL variant lets you query with full expressive power (joins, filters, aggregates).");
            for sel in &actionable {
                cli_println!("     htmlsnapshot get all text \"{}\" --limit 20", sel);
            }
            cli_println!("     htmlsnapshot get attr \"img[src]\" src --limit 20  # image URLs");
            cli_println!("     htmlsnapshot get attr \"a[href]\" href --limit 20  # link URLs");
            cli_println!("     htmlsnapshot get attr \"img[src]:expr(width > 200 && height > 200)\" src --limit 20  # large images only");
            if let Some(first) = actionable.first() {
                cli_println!("     htmlsnapshot query --sql \"SELECT dom_text(dom) as text FROM load_and_select(@url, '{}')\"", first);
            }
        } else {
            // Fallback when no quality selectors found (e.g., all bare tags)
            cli_println!("  💡 Try these next:");
            cli_println!("     htmlsnapshot get attr \"img[src]\" src --limit 20  # image URLs");
            cli_println!("     htmlsnapshot get attr \"a[href]\" href --limit 20  # link URLs");
            cli_println!("     htmlsnapshot get attr \"img[src]:expr(width > 200 && height > 200)\" src --limit 20  # large images only");
            cli_println!("    Use a more specific CSS selector for targeted extraction:");
            cli_println!("     htmlsnapshot inspect \".card\" --max 20 --depth 6");
        }
    } else {
        // Fallback when no suggestions at all
        cli_println!("  💡 Try these next:");
        cli_println!("     htmlsnapshot get attr \"img[src]\" src --limit 20  # image URLs");
        cli_println!("     htmlsnapshot get attr \"a[href]\" href --limit 20  # link URLs");
        cli_println!("     htmlsnapshot get attr \"img[src]:expr(width > 200 && height > 200)\" src --limit 20  # large images only");
        cli_println!("    Narrow the scope with a more specific CSS selector for targeted extraction:");
        cli_println!("     htmlsnapshot inspect \".card\" --max 20 --depth 6");
    }

    // When auto-discovery was triggered, show alternative candidates that were
    // also found — the user may prefer one of these over the auto-selected one.
    if auto_discovered {
        if let Some(candidates) = data.get("autoDiscoveredCandidates").and_then(|v| v.as_array()) {
            if !candidates.is_empty() {
                cli_println!("");
                cli_println!("  📋 Alternative repeating patterns also found:");
                for (i, c) in candidates.iter().take(5).enumerate() {
                    let sel = c.get("selector").and_then(|v| v.as_str()).unwrap_or("");
                    let count = c.get("matchCount").and_then(|v| v.as_i64()).unwrap_or(0);
                    let sample = c.get("sampleText").and_then(|v| v.as_str()).unwrap_or("");
                    let sample_hint = if sample.len() > 50 {
                        format!("\"{}…\"", &sample[..50])
                    } else if !sample.is_empty() {
                        format!("\"{}\"", sample)
                    } else {
                        String::new()
                    };
                    cli_println!("    {}. \"{}\" ({} matches) {}", i + 1, sel, count, sample_hint);
                    cli_println!("       Try: htmlsnapshot inspect \"{}\" --max 20", sel);
                }
            }
        }

        cli_println!("");
        cli_println!("  📋 Tip: htmlsnapshot summary uses visual clustering (not DOM patterns)");
        cli_println!("    to group visible content. It can surface product info even when");
        cli_println!("    auto-discovery picks up navigation elements.");
        cli_println!("    Try: browser4-cli htmlsnapshot summary");
    }

    json_field("inspect", data);
    Ok(())
}

// ---------------------------------------------------------------------------
// grep support (shared between htmlsnapshot-grep and snapshot-grep)
// ---------------------------------------------------------------------------

#[derive(Debug, Default)]
struct GrepOptions {
    pattern: String,
    /// Additional patterns from `-e` / `--regexp` flags (repeatable).
    extra_patterns: Vec<String>,
    ignore_case: bool,
    no_line_number: bool,
    after_context: Option<usize>,
    before_context: Option<usize>,
    context: Option<usize>,
    invert_match: bool,
    count: bool,
    files_with_matches: bool,
    fixed_strings: bool,
    word_regexp: bool,
    selector: Option<String>,
    /// CSS selector using querySelectorAll semantics — search across all matched elements.
    selector_all: Option<String>,
    /// When true, search the raw HTML including <script> and <style> content.
    /// By default (false), script and style tag content is stripped before matching.
    raw_html: bool,
}

fn parse_grep_options(tool_params: &Value) -> Result<GrepOptions, String> {
    let pattern = tool_params
        .get("pattern")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .unwrap_or("")
        .to_string();

    // Collect -e / --regexp patterns (supports both single string and array
    // of strings when the flag is repeated, e.g. -e price -e rating -e stars).
    let extra_patterns: Vec<String> = match tool_params.get("regexp") {
        Some(Value::Array(arr)) => arr
            .iter()
            .filter_map(|v| v.as_str().map(String::from))
            .collect(),
        Some(Value::String(s)) if !s.is_empty() => vec![s.clone()],
        _ => vec![],
    };

    if pattern.is_empty() && extra_patterns.is_empty() {
        return Err("Pattern is required. Provide a positional pattern, or use -e PATTERN (repeatable) for multiple patterns.".to_string());
    }

    let parse_usize = |key: &str| -> Option<usize> {
        tool_params
            .get(key)
            .and_then(|v| {
                v.as_u64()
                    .map(|n| n as usize)
                    .or_else(|| v.as_str().and_then(|s| s.parse::<usize>().ok()))
            })
    };

    Ok(GrepOptions {
        pattern,
        extra_patterns,
        ignore_case: tool_params
            .get("ignore-case")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        no_line_number: tool_params
            .get("no-line-number")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        after_context: parse_usize("after-context"),
        before_context: parse_usize("before-context"),
        context: parse_usize("context"),
        invert_match: tool_params
            .get("invert-match")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        count: tool_params
            .get("count")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        files_with_matches: tool_params
            .get("files-with-matches")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        fixed_strings: tool_params
            .get("fixed-strings")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        word_regexp: tool_params
            .get("word-regexp")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        selector: tool_params
            .get("selector")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string()),
        selector_all: tool_params
            .get("selector-all")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string()),
        raw_html: tool_params
            .get("raw-html")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
    })
}

async fn handle_html_snapshot_grep(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    grep_options: &GrepOptions,
) -> Result<(), String> {
    let source = if let Some(selector) = &grep_options.selector_all {
        // Scoped search across ALL matching elements (querySelectorAll semantics).
        // Uses html_snapshot_scrape_all to get inner HTML of every matched element,
        // then concatenates with element index annotations for traceable results.
        let raw = with_session(client, base_url, session_name, false, |session_id| {
            let client = client.clone();
            let base_url = base_url.to_string();
            let sel = selector.clone();
            async move {
                call_tool(
                    &client,
                    &base_url,
                    "html_snapshot_scrape_all",
                    json!({
                        "sessionId": session_id,
                        "field": "html",
                        "selector": sel,
                    }),
                )
                .await
            }
        })
        .await?;

        // Parse the JSON array of HTML strings and concatenate with separators
        concat_scrape_all_results(&raw, selector)
    } else if let Some(selector) = &grep_options.selector {
        // Scoped search: use html_snapshot_scrape to get inner HTML of the first
        // matching element (querySelector semantics).
        with_session(client, base_url, session_name, false, |session_id| {
            let client = client.clone();
            let base_url = base_url.to_string();
            let sel = selector.clone();
            async move {
                call_tool(
                    &client,
                    &base_url,
                    "html_snapshot_scrape",
                    json!({
                        "sessionId": session_id,
                        "field": "html",
                        "selector": sel,
                    }),
                )
                .await
            }
        })
        .await?
    } else {
        // Full page: use html_snapshot_export
        with_session(client, base_url, session_name, false, |session_id| {
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
        .await?
    };

    let (page, page_size, show_all) = parse_page_opts(tool_params);
    run_grep_on_source(&source, grep_options, "htmlsnapshot", page, page_size, show_all)
}

/// Parse the JSON array result from `html_snapshot_scrape_all` (field: "html")
/// and concatenate all element HTML strings with annotated separators so grep
/// output can trace matches back to the source element index.
fn concat_scrape_all_results(raw: &str, selector: &str) -> String {
    // Try to parse as JSON array of strings
    let parsed: Vec<String> = match serde_json::from_str(raw) {
        Ok(arr) => arr,
        Err(_) => {
            // If parsing fails, return raw as-is (might be an error message)
            return raw.to_string();
        }
    };

    if parsed.is_empty() {
        return String::new();
    }

    let mut result = String::new();
    for (i, html) in parsed.iter().enumerate() {
        if i > 0 {
            result.push('\n');
        }
        // Annotate each element's content with its index for traceability
        result.push_str(&format!(
            "--- element[{}] of {} ---\n{}",
            i + 1,
            selector,
            html
        ));
    }
    result
}

/// Convert grep-style escaped-alternation `\|` to Rust regex bare-pipe `|`.
///
/// In GNU grep BRE (basic regular expressions), alternation is written `\|`.
/// Rust's `regex` crate uses ERE-like syntax where `|` is alternation and
/// `\|` matches a literal pipe character.  This function bridges the two so
/// that users who type `price\|rating\|stars` (the grep idiom) get the
/// alternation they intended.
///
/// The conversion is safe:
/// - If the pattern already contains bare `|`, we do nothing — the user
///   already knows the correct syntax.
/// - If the pattern contains `\|` but no bare `|`, we replace all `\|`
///   with `|` (the user meant alternation).
fn convert_alternation(pattern: &str) -> String {
    // Already using bare | → user knows the correct syntax; leave it alone.
    if pattern.contains('|') && !pattern.contains("\\|") {
        return pattern.to_string();
    }
    // Contains \| → convert to bare | (grep BRE → Rust regex).
    // Conversion is silent — Rust regex syntax is documented in --help.
    // Users who need literal pipe matching can use -F (fixed strings).
    if pattern.contains("\\|") {
        return pattern.replace("\\|", "|");
    }
    pattern.to_string()
}

/// Strip `<script>...</script>` and `<style>...</style>` tag content from HTML.
///
/// On JS-heavy pages (e.g. Amazon: 2.5 MB HTML with massive minified JS),
/// script and style content dwarfs actual page text.  Grep without stripping
/// produces hundreds of KB of false-positive matches, making the output
/// unusable.  This function replaces each `<script>...</script>` and
/// `<style>...</style>` block with a blank line so line numbering is
/// preserved for the remaining content.
///
/// The stripping is case-insensitive for the tag names.  Nested content is
/// removed via a simple scan — it handles the vast majority of real-world
/// HTML.  Edge cases (script/style tags inside CDATA or comments) are left
/// as-is since they are rare and the cost of a full HTML parser is not
/// justified for a grep pre-processor.
fn strip_html_scripts_and_styles(html: &str) -> String {
    // Early return for obviously clean input (no script/style tags at all).
    let lower = html.to_lowercase();
    if !lower.contains("<script") && !lower.contains("<style") {
        return html.to_string();
    }

    let mut result = String::with_capacity(html.len());
    let bytes = html.as_bytes();
    let mut i = 0;
    let len = bytes.len();

    while i < len {
        // Check for <script or <style tag (case-insensitive)
        if bytes[i] == b'<' && i + 7 < len {
            let tag_check = &lower[i..std::cmp::min(i + 8, lower.len())];
            let (close_tag, tag_len) = if tag_check.starts_with("<script") {
                ("</script>", 7usize)
            } else if tag_check.starts_with("<style") {
                ("</style>", 7usize)
            } else {
                ("", 0)
            };

            if tag_len > 0 {
                // Find the closing tag (case-insensitive)
                let close_lower = close_tag.to_lowercase();
                if let Some(end_pos) = lower[i + tag_len..].find(&close_lower) {
                    let abs_end = i + tag_len + end_pos + close_tag.len();
                    // Replace the entire script/style block with a newline
                    // to preserve approximate line numbering.
                    let newlines = html[i..abs_end].chars().filter(|&c| c == '\n').count();
                    for _ in 0..newlines.max(1) {
                        result.push('\n');
                    }
                    i = abs_end;
                    continue;
                }
                // No closing tag found — fall through and output the '<' char.
            }
        }
        result.push(bytes[i] as char);
        i += 1;
    }

    result
}

/// Run grep matching on an already-fetched source text, printing results
/// via cli_println! and recording json fields via json_field().
///
/// `source_label` is used as the "filename" in --files-with-matches output
/// (e.g., "snapshot" or "htmlsnapshot").
fn run_grep_on_source(
    source: &str,
    grep_options: &GrepOptions,
    source_label: &str,
    page: usize,
    page_size: usize,
    show_all: bool,
) -> Result<(), String> {
    // Strip <script> and <style> tag content from HTML source before matching,
    // unless --raw-html is set.  On JS-heavy pages like Amazon (2.5 MB HTML
    // with massive minified JS payloads), script content can produce hundreds
    // of KB of false-positive matches, drowning out actual page content.
    let effective_source: std::borrow::Cow<'_, str>;
    let source_to_search = if grep_options.raw_html {
        source
    } else {
        effective_source = std::borrow::Cow::Owned(strip_html_scripts_and_styles(source));
        &effective_source
    };

    // If the source is "null", no element matched the selector
    if source_to_search == "null" || source_to_search.is_empty() {
        if grep_options.count {
            cli_println!("0");
            json_field("count", json!(0));
        }
        json_field("matches", json!(0));
        json_field("total_lines", json!(0));
        return Ok(());
    }

    // Build the regex pattern
    let pattern_str = &grep_options.pattern;

    // Combine main pattern with any -e patterns using alternation.
    let mut all_patterns: Vec<String> = Vec::new();
    if !pattern_str.is_empty() {
        all_patterns.push(pattern_str.clone());
    }
    for p in &grep_options.extra_patterns {
        all_patterns.push(p.clone());
    }

    let combined_pattern = if all_patterns.len() > 1 {
        // Wrap each pattern in a non-capturing group so that alternation
        // and word-boundary anchors apply per-pattern.
        all_patterns
            .iter()
            .map(|p| format!("(?:{})", p))
            .collect::<Vec<_>>()
            .join("|")
    } else if all_patterns.len() == 1 {
        all_patterns.into_iter().next().unwrap()
    } else {
        return Err("No pattern provided".to_string());
    };

    // Auto-convert grep-style \| (escaped pipe) to Rust regex | (bare pipe).
    // In GNU grep BRE, alternation is \|; in Rust's regex crate (like ERE),
    // alternation is | and \| matches a literal pipe character.
    // We detect the grep-idiom and silently fix it for a better UX.
    let converted_pattern = convert_alternation(&combined_pattern);

    let regex_str = if grep_options.fixed_strings {
        regex::escape(&converted_pattern)
    } else {
        converted_pattern.clone()
    };

    let final_pattern = if grep_options.word_regexp {
        format!(r"\b{}\b", regex_str)
    } else {
        regex_str
    };

    let re = regex::RegexBuilder::new(&final_pattern)
        .case_insensitive(grep_options.ignore_case)
        .build()
        .map_err(|e| {
            let mut msg = format!("Invalid regex pattern: {e}");
            // Help users who accidentally shell-escape regex metacharacters or use
            // unsupported syntax — suggest -F (fixed-strings) for literal matching.
            if final_pattern.contains('\\') {
                msg.push_str("\n💡 Tip: The pattern contains backslash escapes. If you meant to match literal text, try -F (--fixed-strings) to disable regex matching.");
            } else if final_pattern.contains('|') {
                msg.push_str("\n💡 Tip: Alternation (|) is supported. If you meant a literal pipe character, try -F (--fixed-strings).");
            }
            msg.push_str("\n   Supported regex syntax: https://docs.rs/regex/latest/regex/#syntax");
            msg
        })?;

    // Split into lines and find matches
    let lines: Vec<&str> = source_to_search.lines().collect();
    let total_lines = lines.len();

    let context_before = grep_options
        .before_context
        .unwrap_or(0)
        .max(grep_options.context.unwrap_or(0));
    let context_after = grep_options
        .after_context
        .unwrap_or(0)
        .max(grep_options.context.unwrap_or(0));

    // Find matching line indices
    let matched_indices: Vec<usize> = lines
        .iter()
        .enumerate()
        .filter_map(|(i, line)| {
            let is_match = re.is_match(line);
            let selected = if grep_options.invert_match {
                !is_match
            } else {
                is_match
            };
            if selected {
                Some(i)
            } else {
                None
            }
        })
        .collect();

    // --count: print only the count
    if grep_options.count {
        cli_println!("{}", matched_indices.len());
        json_field("count", json!(matched_indices.len()));
        json_field("matches", json!(matched_indices.len()));
        json_field("total_lines", json!(total_lines));
        return Ok(());
    }

    // --files-with-matches: print the source label only if there are matches
    if grep_options.files_with_matches {
        if !matched_indices.is_empty() {
            cli_println!("{}", source_label);
        }
        json_field("matched", json!(!matched_indices.is_empty()));
        json_field("matches", json!(matched_indices.len()));
        json_field("total_lines", json!(total_lines));
        return Ok(());
    }

    // Defer context when -v is set (standard grep ignores context with -v)
    let effective_before = if grep_options.invert_match {
        0
    } else {
        context_before
    };
    let effective_after = if grep_options.invert_match {
        0
    } else {
        context_after
    };

    // Build the set of lines to display (matches + context)
    let mut display_set: std::collections::BTreeSet<usize> =
        std::collections::BTreeSet::new();
    for &idx in &matched_indices {
        let start = if idx >= effective_before {
            idx - effective_before
        } else {
            0
        };
        let end = std::cmp::min(idx + effective_after + 1, total_lines);
        for i in start..end {
            display_set.insert(i);
        }
    }

    let show_line_numbers = !grep_options.no_line_number;

    // Print with context separators
    let mut output_parts: Vec<String> = Vec::new();
    let mut last_printed: Option<usize> = None;
    for &line_idx in &display_set {
        if let Some(last) = last_printed {
            if line_idx > last + 1 {
                output_parts.push("--".to_string());
            }
        }

        let prefix = if show_line_numbers {
            format!("{}:", line_idx + 1)
        } else {
            String::new()
        };

        if matched_indices.contains(&line_idx) {
            output_parts.push(format!("{}{}", prefix, lines[line_idx]));
        } else {
            output_parts.push(format!("{}-{}", prefix, lines[line_idx]));
        }
        last_printed = Some(line_idx);
    }

    if !output_parts.is_empty() {
        let full_output = output_parts.join("\n");
        if skip_pagination(show_all) {
            cli_println!("{}", full_output);
        } else {
            let (page_content, meta) = paginate_output(&full_output, page, page_size);
            cli_println!("{}", page_content);
            if meta.is_truncated {
                cli_println!("{}", format_pagination_footer(&meta));
            }
        }
        json_field("total_chars", json!(full_output.len()));
        json_field("total_lines", json!(full_output.lines().count()));
        json_field("page_size", json!(page_size));
        json_field("truncated", json!(!skip_pagination(show_all) && full_output.lines().count() > page_size));
    } else if !grep_options.files_with_matches && !grep_options.count {
        // Normal mode with no matches: always print a count so the output
        // is never silently empty.  Silent empty output is confusing and
        // often mistaken for a bug rather than "no matches in this viewport".
        cli_println!("0 matches found");
    }

    json_field("matches", json!(matched_indices.len()));
    json_field("lines_printed", json!(display_set.len()));
    json_field("total_lines", json!(total_lines));
    if let Some(sel) = &grep_options.selector {
        json_field("selector", json!(sel));
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// pagination support (shared between htmlsnapshot get/grep and snapshot grep)
// ---------------------------------------------------------------------------

/// Metadata about a paginated output.
#[derive(Debug)]
struct PaginationMeta {
    #[allow(dead_code)]
    total_chars: usize,
    total_lines: usize,
    current_page: usize,
    total_pages: usize,
    page_size: usize,
    /// Whether the output was actually truncated (false when it fits in one page).
    is_truncated: bool,
}

/// Paginate `text` into pages of `page_size` **lines** (not characters).
///
/// This is line-oriented because snapshot and HTML snapshot output is structured
/// with one element/node per line.  Returns the content for `page` (1-based)
/// and metadata about the pagination.  When `page_size` is 0 or the text fits
/// in one page, returns the full text with `is_truncated: false`.
fn paginate_output(text: &str, page: usize, page_size: usize) -> (String, PaginationMeta) {
    let total_chars = text.len();
    let total_lines = text.lines().count();
    let effective_page = if page == 0 { 1 } else { page };

    // If page_size is 0 (unlimited) or content fits in one page, return all.
    if page_size == 0 || total_lines <= page_size {
        let meta = PaginationMeta {
            total_chars,
            total_lines,
            current_page: 1,
            total_pages: 1,
            page_size,
            is_truncated: false,
        };
        return (text.to_string(), meta);
    }

    let total_pages = (total_lines + page_size - 1) / page_size;
    let current_page = effective_page.min(total_pages);

    // Build line-based page: take lines from (current_page-1)*page_size to
    // current_page*page_size (or end).
    let start_line = (current_page - 1) * page_size;
    let end_line = (start_line + page_size).min(total_lines);

    let selected: Vec<&str> = text
        .lines()
        .skip(start_line)
        .take(end_line - start_line)
        .collect();
    let page_content = selected.join("\n");

    let meta = PaginationMeta {
        total_chars,
        total_lines,
        current_page,
        total_pages,
        page_size,
        is_truncated: true,
    };

    (page_content, meta)
}

/// Format a human-readable pagination footer line.
fn format_pagination_footer(meta: &PaginationMeta) -> String {
    let lines_on_page = (meta.current_page.min(meta.total_pages) * meta.page_size).min(meta.total_lines);
    format!(
        "[Page {}/{} · {} lines of {} total · use --page N for next page · --all to show all]",
        meta.current_page,
        meta.total_pages,
        lines_on_page,
        meta.total_lines,
    )
}

/// Extract pagination options from tool_params.
/// Returns (page, page_size, show_all).
///
/// Default page size is 2000 **lines** (was 500 lines prior to v4.12).
fn parse_page_opts(tool_params: &serde_json::Value) -> (usize, usize, bool) {
    let show_all = tool_params
        .get("all")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
        || tool_params
            .get("all")
            .and_then(|v| v.as_str())
            .map(|s| s == "true")
            .unwrap_or(false);

    let page = tool_params
        .get("page")
        .and_then(|v| v.as_str())
        .and_then(|s| s.parse::<usize>().ok())
        .unwrap_or(1);

    let page_size = tool_params
        .get("page-size")
        .and_then(|v| v.as_str())
        .and_then(|s| s.parse::<usize>().ok())
        .unwrap_or(2000);

    (page, page_size, show_all)
}

/// Whether pagination should be skipped (JSON output, quiet mode, or explicit --all).
fn skip_pagination(show_all: bool) -> bool {
    show_all || json_active() || quiet_active()
}

// ---------------------------------------------------------------------------
// generate-locator handler
// ---------------------------------------------------------------------------

async fn handle_generate_locator(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, params).await }
    })
    .await?;

    if !result.is_empty() {
        cli_println!("{}", result);
    }

    json_field("selector", json!(&result));
    if let Some(r) = tool_params.get("ref").and_then(|v| v.as_str()) {
        json_field("ref", json!(r));
    }

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
// Text input verification
// ---------------------------------------------------------------------------

/// Read the current value of an element and compare it with the expected text.
///
/// Returns `Ok(report)` with a human-readable comparison: fully typed,
/// partially typed (with character counts), or not typed. Returns `Err(msg)`
/// when the verification itself fails (e.g. element not found, session lost).
async fn verify_element_text(
    client: &Client,
    base_url: &str,
    session_id: &str,
    element_ref: Option<&str>,
    expected_text: &str,
) -> Result<String, String> {
    // Build a JavaScript expression that reads the element's value.
    // When a ref is provided, the expression runs as an arrow function over
    // that element (browser_evaluate passes the element as the argument).
    // Without a ref, read from document.activeElement.
    let expression = if element_ref.is_some() {
        "element => (element.value !== undefined ? (element.value || '') : (element.textContent || ''))".to_string()
    } else {
        "(() => { \
            const el = document.activeElement; \
            return el \
                ? (el.value !== undefined ? (el.value || '') : (el.textContent || '')) \
                : ''; \
        })()"
            .to_string()
    };

    let mut params = json!({
        "sessionId": session_id,
        "expression": expression,
    });
    if let Some(ref_sel) = element_ref {
        params["ref"] = json!(ref_sel);
    }

    let result = call_tool(client, base_url, "browser_evaluate", params).await?;

    // The MCP response may wrap the value in JSON quotes or be a plain string.
    let actual = result.trim().trim_matches('"').to_string();
    let expected_chars = expected_text.chars().count();
    let actual_chars = actual.chars().count();

    if actual == expected_text {
        Ok(format!(
            "Verification: text fully typed ({} chars).",
            expected_chars
        ))
    } else if actual.is_empty() {
        Ok("Verification: text was NOT typed — element is empty.".to_string())
    } else if expected_text.starts_with(&actual) {
        Ok(format!(
            "Verification: text PARTIALLY typed ({}/{} chars). Current value: '{}'",
            actual_chars, expected_chars, actual
        ))
    } else {
        Ok(format!(
            "Verification: text mismatch. Expected '{}', got '{}'.",
            expected_text, actual
        ))
    }
}

/// Handle `type` and `fill` commands with optional verification and automatic
/// post-timeout verification.
async fn handle_text_input_command(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    verify: bool,
) -> Result<(), String> {
    // Extract the text and optional ref from tool_params (already resolved by
    // tool_params_fn). These are the values sent to the server.
    let expected_text = tool_params
        .get("text")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let element_ref = tool_params.get("ref").and_then(|v| v.as_str());

    // Warn when `type` (browser_press_sequentially) is used without a ref —
    // text may go nowhere if no element is currently focused on the page.
    if tool_name == "browser_press_sequentially"
        && element_ref.is_none()
        && !expected_text.is_empty()
    {
        eprintln!(
            "Warning: No element ref specified for 'type' command. \
             Text will be typed into whatever element currently has focus. \
             If nothing is focused, the text will go nowhere. \
             Use 'type <text> <ref>' or 'type <text> --ref <css-selector>' for reliable targeting."
        );
    }

    // Step 1: Call the text input tool.
    let tool_result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, params).await }
    })
    .await;

    match tool_result {
        Ok(text) => {
            // Tool succeeded. Print result if non-empty.
            if !text.is_empty() {
                cli_println!("{}", text);
            }

            // Success confirmation: tell the user what was done.
            let action_label = if tool_name == "browser_press_sequentially" {
                "Typed"
            } else {
                "Filled"
            };
            if let Some(ref_sel) = element_ref {
                cli_println!(
                    "✓ {} '{}' into {}",
                    action_label,
                    expected_text,
                    ref_sel
                );
            } else {
                cli_println!("✓ {} '{}'", action_label, expected_text);
            }

            // Optional --verify: confirm the text was applied.
            if verify {
                let state = require_session(session_name)?;
                let session_id = get_session_id(&state)?.to_string();
                match verify_element_text(client, base_url, &session_id, element_ref, expected_text)
                    .await
                {
                    Ok(report) => {
                        cli_println!("{}", report);
                        json_field("verification", json!(&report));
                    }
                    Err(verify_err) => {
                        cli_println!("Verification could not be completed: {}", verify_err);
                        json_field("verification_error", json!(&verify_err));
                    }
                }
            }

            persist_active_selector(base_url, session_name, tracked_selector(tool_params))?;
            Ok(())
        }
        Err(err) => {
            if is_timeout_error_message(&err) {
                // Automatic verification after timeout — always attempted.
                let state = require_session(session_name)?;
                let session_id = get_session_id(&state)?.to_string();
                let verify_msg = match verify_element_text(
                    client,
                    base_url,
                    &session_id,
                    element_ref,
                    expected_text,
                )
                .await
                {
                    Ok(report) => report,
                    Err(verify_err) => {
                        format!("Verification could not be completed: {}", verify_err)
                    }
                };
                let enriched = format!("{}\n{}", err, verify_msg);
                Err(enriched)
            } else if is_not_focusable_error(&err) {
                // Element can't receive focus — suggest click-first workaround.
                let enriched = format!(
                    "{}\n\nTip: Some elements (e.g. Google search box) cannot receive focus directly.\n\
                     Try 'click <ref>' first to focus the element, then 'type <text>' to enter text.",
                    err
                );
                Err(enriched)
            } else {
                // Not a timeout or focusability issue — propagate as-is.
                Err(err)
            }
        }
    }
}

/// Handle `press` command with optional verification and automatic
/// post-timeout verification.
///
/// For verification, reads the element's current value. If the key is a
/// single printable character the verification checks whether it was
/// appended; for modifier / navigation keys it simply reports the current
/// value so the user can decide.
async fn handle_press_command(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    verify: bool,
) -> Result<(), String> {
    let key_pressed = tool_params
        .get("key")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let element_ref = tool_params.get("ref").and_then(|v| v.as_str());

    // Build the expected check for printable single-character keys.
    let is_printable =
        key_pressed.chars().count() == 1 && !key_pressed.chars().all(|c| c.is_control());

    // Step 1: Call the press tool.
    let tool_result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, params).await }
    })
    .await;

    match tool_result {
        Ok(text) => {
            if !text.is_empty() {
                cli_println!("{}", text);
            }

            // Success confirmation.
            if let Some(ref_sel) = element_ref {
                cli_println!("✓ Pressed '{}' on {}", key_pressed, ref_sel);
            } else {
                cli_println!("✓ Pressed '{}'", key_pressed);
            }

            if verify {
                let state = require_session(session_name)?;
                let session_id = get_session_id(&state)?.to_string();
                match verify_press_result(
                    client,
                    base_url,
                    &session_id,
                    element_ref,
                    key_pressed,
                    is_printable,
                )
                .await
                {
                    Ok(report) => {
                        cli_println!("{}", report);
                        json_field("verification", json!(&report));
                    }
                    Err(verify_err) => {
                        cli_println!("Verification could not be completed: {}", verify_err);
                        json_field("verification_error", json!(&verify_err));
                    }
                }
            }

            persist_active_selector(base_url, session_name, tracked_selector(tool_params))?;
            Ok(())
        }
        Err(err) => {
            if is_timeout_error_message(&err) {
                let state = require_session(session_name)?;
                let session_id = get_session_id(&state)?.to_string();
                let verify_msg = match verify_press_result(
                    client,
                    base_url,
                    &session_id,
                    element_ref,
                    key_pressed,
                    is_printable,
                )
                .await
                {
                    Ok(report) => report,
                    Err(verify_err) => {
                        format!("Verification could not be completed: {}", verify_err)
                    }
                };
                let enriched = format!("{}\n{}", err, verify_msg);
                Err(enriched)
            } else {
                Err(err)
            }
        }
    }
}

/// Verify the result of a `press` command by reading the element value.
async fn verify_press_result(
    client: &Client,
    base_url: &str,
    session_id: &str,
    element_ref: Option<&str>,
    key_pressed: &str,
    is_printable: bool,
) -> Result<String, String> {
    let expression = if element_ref.is_some() {
        "element => (element.value !== undefined ? (element.value || '') : (element.textContent || ''))".to_string()
    } else {
        "(() => { \
            const el = document.activeElement; \
            return el \
                ? (el.value !== undefined ? (el.value || '') : (el.textContent || '')) \
                : ''; \
        })()"
            .to_string()
    };

    let mut params = json!({
        "sessionId": session_id,
        "expression": expression,
    });
    if let Some(ref_sel) = element_ref {
        params["ref"] = json!(ref_sel);
    }

    let result = call_tool(client, base_url, "browser_evaluate", params).await?;
    let actual = result.trim().trim_matches('"').to_string();

    if is_printable {
        if actual.ends_with(key_pressed) {
            Ok(format!(
                "Verification: key '{}' was pressed — value ends with expected character. Current value: '{}'",
                key_pressed, actual
            ))
        } else if actual.is_empty() {
            Ok("Verification: key was NOT applied — element is empty.".to_string())
        } else {
            Ok(format!(
                "Verification: key '{}' may not have been applied. Current value: '{}'",
                key_pressed, actual
            ))
        }
    } else {
        Ok(format!(
            "Verification: element value after '{}' press: '{}'",
            key_pressed, actual
        ))
    }
}

/// Handle `select` command with optional verification and automatic
/// post-timeout verification.
async fn handle_select_command(
    client: &Client,
    base_url: &str,
    tool_name: &str,
    tool_params: &Value,
    session_name: Option<&str>,
    verify: bool,
) -> Result<(), String> {
    // Read the display value from the "values" array (set by tool_params_fn).
    // The user types a human-readable label like "United States", but the
    // underlying <option> value may differ (e.g. "us"). We show what the user
    // asked for so the confirmation message is meaningful.
    let expected_value = tool_params
        .get("values")
        .and_then(|v| v.as_array())
        .and_then(|arr| arr.first())
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let element_ref = tool_params.get("ref").and_then(|v| v.as_str());

    let tool_result = with_session(client, base_url, session_name, false, |session_id| {
        let client = client.clone();
        let base_url = base_url.to_string();
        let tool_name = tool_name.to_string();
        let mut params = tool_params.clone();
        params["sessionId"] = json!(session_id);
        async move { call_tool(&client, &base_url, &tool_name, params).await }
    })
    .await;

    match tool_result {
        Ok(text) => {
            if !text.is_empty() {
                cli_println!("{}", text);
            }

            // Success confirmation.
            if let Some(ref_sel) = element_ref {
                cli_println!("✓ Selected '{}' in {}", expected_value, ref_sel);
            } else {
                cli_println!("✓ Selected '{}'", expected_value);
            }

            if verify {
                let state = require_session(session_name)?;
                let session_id = get_session_id(&state)?.to_string();
                match verify_select_result(
                    client,
                    base_url,
                    &session_id,
                    element_ref,
                    expected_value,
                )
                .await
                {
                    Ok(report) => {
                        cli_println!("{}", report);
                        json_field("verification", json!(&report));
                    }
                    Err(verify_err) => {
                        cli_println!("Verification could not be completed: {}", verify_err);
                        json_field("verification_error", json!(&verify_err));
                    }
                }
            }

            persist_active_selector(base_url, session_name, tracked_selector(tool_params))?;
            Ok(())
        }
        Err(err) => {
            if is_timeout_error_message(&err) {
                let state = require_session(session_name)?;
                let session_id = get_session_id(&state)?.to_string();
                let verify_msg = match verify_select_result(
                    client,
                    base_url,
                    &session_id,
                    element_ref,
                    expected_value,
                )
                .await
                {
                    Ok(report) => report,
                    Err(verify_err) => {
                        format!("Verification could not be completed: {}", verify_err)
                    }
                };
                let enriched = format!("{}\n{}", err, verify_msg);
                Err(enriched)
            } else {
                Err(err)
            }
        }
    }
}

/// Verify the result of a `select` command by reading the element's value.
async fn verify_select_result(
    client: &Client,
    base_url: &str,
    session_id: &str,
    element_ref: Option<&str>,
    expected_value: &str,
) -> Result<String, String> {
    let expression = if element_ref.is_some() {
        "element => (element.value !== undefined ? (element.value || '') : (element.textContent || ''))".to_string()
    } else {
        return Err("Verification requires an element ref for select.".to_string());
    };

    let mut params = json!({
        "sessionId": session_id,
        "expression": expression,
    });
    if let Some(ref_sel) = element_ref {
        params["ref"] = json!(ref_sel);
    }

    let result = call_tool(client, base_url, "browser_evaluate", params).await?;
    let actual = result.trim().trim_matches('"').to_string();

    if actual == expected_value {
        Ok(format!(
            "Verification: option '{}' is selected.",
            expected_value
        ))
    } else if actual.is_empty() {
        Ok("Verification: no option appears to be selected — value is empty.".to_string())
    } else {
        Ok(format!(
            "Verification: expected '{}' to be selected, but current value is '{}'.",
            expected_value, actual
        ))
    }
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

    let wait = tool_params
        .get("wait")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    if wait {
        cli_println!("Waiting for agent to complete (task {})...", task_id);
        let start = std::time::Instant::now();
        let timeout = std::time::Duration::from_secs(600); // 10 minutes
        loop {
            if start.elapsed() > timeout {
                return Err(format!(
                    "Agent task {} timed out after {}s. Use 'agent status {}' to check later.",
                    task_id,
                    start.elapsed().as_secs(),
                    task_id
                ));
            }

            let status = get_command_status(client, base_url, &task_id).await?;
            let parsed: Value = serde_json::from_str(&status)
                .unwrap_or(Value::String(status.clone()));
            let process_state = parsed
                .get("processState")
                .and_then(|v| v.as_str())
                .unwrap_or("");

            match process_state {
                "done" => {
                    let result_text = get_command_result(client, base_url, &task_id).await?;
                    cli_println!("Agent completed in {:.1}s:", start.elapsed().as_secs_f64());
                    cli_println!("{}", result_text);
                    json_field(
                        "raw",
                        json!(serde_json::from_str::<Value>(&result_text)
                            .unwrap_or(Value::String(result_text.clone()))),
                    );
                    let _ = update_async_task_status(&task_id, "completed", None);
                    return Ok(());
                }
                "failed" | "error" => {
                    let message = parsed
                        .get("message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Agent task failed");
                    return Err(format!("Agent task failed: {}", message));
                }
                _ => {
                    // Show progress indicator
                    let elapsed = start.elapsed().as_secs();
                    if elapsed > 0 && elapsed % 5 == 0 {
                        let msg = parsed
                            .get("message")
                            .and_then(|v| v.as_str())
                            .unwrap_or("processing...");
                        cli_println!("  [{}s] {}", elapsed, msg);
                    }
                }
            }

            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        }
    }

    cli_println!(
        "Use 'browser4-cli agent status {}' to check progress, or 'browser4-cli agent list' to view all tracked tasks.",
        task_id
    );

    // Persist the task for cross-session tracking
    let _ = track_async_task(&task_id, "agent", task, None);

    Ok(())
}

async fn handle_act(
    client: &Client,
    base_url: &str,
    description: &str,
) -> Result<(), String> {
    let result = http::execute_act_command(client, base_url, description).await?;

    if result.is_empty() {
        cli_println!("Action completed (no output).");
    } else {
        cli_println!("{}", result);
    }

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
    json_field(
        "raw",
        json!(serde_json::from_str::<Value>(&result).unwrap_or(Value::String(result.clone()))),
    );

    // Sync the latest status back to the local task list so `agent list`
    // reflects accurate status without requiring a server round-trip.
    sync_agent_status_to_local(id, &result);

    Ok(())
}

/// Update the local task-tracking file with the latest server status for a task.
fn sync_agent_status_to_local(task_id: &str, status_json: &str) {
    let mut list = read_async_tasks(None);
    if let Some(entry) = list.tasks.iter_mut().find(|t| t.task_id == task_id) {
        if let Ok(parsed) = serde_json::from_str::<Value>(status_json) {
            entry.last_status = extract_readable_agent_status(&parsed);
            let _ = write_async_tasks(&list, None);
        }
    }
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

async fn handle_agent_list(client: &Client, base_url: &str) -> Result<(), String> {
    let _ = prune_async_tasks(None);
    let mut list = read_async_tasks(None);

    // Refresh status for agent tasks from the server so the display is consistent
    // with `agent status`. Tasks that fail to query keep their cached last_status.
    let mut updated = false;
    for entry in &mut list.tasks {
        if entry.command != "agent" {
            continue;
        }
        if let Ok(status_json) = get_command_status(client, base_url, &entry.task_id).await {
            if let Ok(parsed) = serde_json::from_str::<Value>(&status_json) {
                entry.last_status = extract_readable_agent_status(&parsed);
                updated = true;
            }
        }
    }

    if updated {
        let _ = write_async_tasks(&list, None);
    }

    let filtered: Vec<_> = list.tasks.iter().filter(|t| t.command == "agent").cloned().collect();
    let display = state::AsyncTaskList { tasks: filtered };
    cli_println!("{}", format_async_task_list(&display));
    Ok(())
}

/// Extract a human-readable status string from the server's `command_status` JSON response.
fn extract_readable_agent_status(status: &Value) -> String {
    let process_state = status
        .get("processState")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let is_done = status
        .get("isDone")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    if is_done || process_state == "done" {
        if let Some(status_code) = status.get("statusCode").and_then(|v| v.as_str()) {
            if status_code == "SC_OK" || status_code == "200" {
                return "done".to_string();
            }
            return format!("done ({})", status_code);
        }
        return "done".to_string();
    }

    if !process_state.is_empty() {
        return process_state.to_string();
    }

    // Fall back to top-level status field
    if let Some(s) = status.get("status").and_then(|v| v.as_str()) {
        if !s.is_empty() {
            return s.to_string();
        }
    }

    "running".to_string()
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
    let query_raw = tool_params.get("sql").and_then(|v| v.as_str());

    if url.is_empty() && seed_file.is_none() {
        return Err("Either a URL or --seed-file is required.".to_string());
    }

    // Read query from file if prefixed with @
    let query = match query_raw {
        Some(q) if q.starts_with('@') => {
            let file_path = &q[1..];
            Some(resolve_sql_file(file_path)?)
        }
        Some(q) => Some(q.to_string()),
        None => None,
    };

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

    // Submit each URL through the appropriate REST API.
    let mut json_submissions: Vec<serde_json::Value> = Vec::new();
    for u in &urls {
        let (result, method) = if let Some(ref q) = query {
            // X-SQL query mode: send structured JSON to /api/swarm/query
            let payload = json!({
                "url": u,
                "args": opts_str,
                "query": q,
            });
            (
                submit_swarm_query(client, base_url, payload).await?,
                "query",
            )
        } else {
            // Plain scrape mode: send URL with options to /api/swarm/submit
            let command = if opts_str.is_empty() {
                u.clone()
            } else {
                format!("{} {}", u, opts_str)
            };
            (
                submit_swarm_payload(client, base_url, &command).await?,
                "submit",
            )
        };

        let task_id = result.trim().trim_matches('"').to_string();
        cli_println!(
            "Task Submitted: {} -> Task ID: {} (via {})",
            u,
            task_id,
            method
        );
        json_submissions.push(json!({
            "url": u,
            "task_id": task_id,
        }));

        // Persist each task for cross-session tracking
        let _ = track_async_task(&task_id, "swarm", u, None);
    }
    json_field("submissions", json!(json_submissions));

    if urls.len() > 1 {
        cli_println!("{} URL(s) submitted. Use 'browser4-cli swarm list' to view all tracked tasks.", urls.len());
    }

    // Support --wait: poll until all submitted jobs complete
    if tool_params
        .get("wait")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        let task_ids: Vec<String> = json_submissions
            .iter()
            .filter_map(|s| s.get("task_id").and_then(|v| v.as_str()).map(|s| s.to_string()))
            .collect();
        if !task_ids.is_empty() {
            swarm_wait_for_jobs(client, base_url, &task_ids).await?;
        }
    }

    Ok(())
}

async fn handle_swarm_query(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let url = tool_params
        .get("url")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let seed_file = tool_params.get("seedFile").and_then(|v| v.as_str());

    // Handle --sql-stdin: read query from stdin (avoids shell quoting issues on Windows)
    let use_sql_stdin = tool_params
        .get("sqlStdin")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let query_raw: Option<String> = if use_sql_stdin {
        let mut input = String::new();
        std::io::stdin()
            .read_to_string(&mut input)
            .map_err(|e| format!("Failed to read X-SQL query from stdin: {e}"))?;
        if input.trim().is_empty() {
            return Err(
                "Stdin was empty. Provide a non-empty X-SQL query via stdin.".to_string(),
            );
        }
        Some(input)
    } else {
        tool_params
            .get("sql")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
    };

    if url.is_empty() && seed_file.is_none() {
        return Err("A URL or --seed-file is required.".to_string());
    }
    // Allow --sql-base64 standalone (without --sql): the base64 value is decoded
    // later by maybe_decode_base64_sql. If sqlBase64 has a non-empty string value,
    // we can proceed with an empty query_raw; the decode will supply the actual SQL.
    let has_sql_base64_value = tool_params
        .get("sqlBase64")
        .and_then(|v| v.as_str())
        .map(|s| !s.trim().is_empty())
        .unwrap_or(false);

    if query_raw.as_ref().map_or(true, |q| q.is_empty()) && !has_sql_base64_value {
        return Err(
            "--sql is required. Provide an inline X-SQL query, @file.sql, --sql-stdin, or --sql-base64."
                .to_string(),
        );
    }

    // Read query from file if prefixed with @
    let query = match &query_raw {
        Some(q) if q.starts_with('@') => {
            let file_path = &q[1..];
            Some(resolve_sql_file(file_path)?)
        }
        Some(q) => Some(q.clone()),
        None if has_sql_base64_value => Some(String::new()),
        None => None,
    };

    // Handle --sql-base64: decode base64-encoded SQL
    let query = query
        .map(|q| maybe_decode_base64_sql(q, tool_params))
        .transpose()?;

    // Collect URLs to query
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
        return Err("No URLs to query.".to_string());
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
    let opts_str = load_opts.join(" ");

    // Submit each URL via /api/swarm/query
    let mut json_submissions: Vec<serde_json::Value> = Vec::new();
    let q = query.expect("query was validated above");
    for u in &urls {
        let payload = json!({
            "url": u,
            "args": opts_str,
            "query": q,
        });
        let result = submit_swarm_query(client, base_url, payload).await?;
        let task_id = result.trim().trim_matches('"').to_string();
        cli_println!("Query Submitted: {} -> Task ID: {}", u, task_id);
        json_submissions.push(json!({
            "url": u,
            "task_id": task_id,
        }));

        // Persist each task for cross-session tracking (so they appear in swarm list)
        let _ = track_async_task(&task_id, "swarm", u, None);
    }
    json_field("submissions", json!(json_submissions));

    if urls.len() > 1 {
        cli_println!("{} URL(s) queried. Use 'browser4-cli swarm list' to view all tracked tasks.", urls.len());
    }

    // Support --wait: poll until all submitted jobs complete
    if tool_params
        .get("wait")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        let task_ids: Vec<String> = json_submissions
            .iter()
            .filter_map(|s| s.get("task_id").and_then(|v| v.as_str()).map(|s| s.to_string()))
            .collect();
        if !task_ids.is_empty() {
            swarm_wait_for_jobs(client, base_url, &task_ids).await?;
        }
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
    let parsed: Value =
        serde_json::from_str(&result).unwrap_or(Value::String(result.clone()));

    // Show metadata only: id, status, isDone, message, timestamps
    let summary = json!({
        "id": parsed.get("id").and_then(|v| v.as_str()).unwrap_or(&id),
        "isDone": parsed.get("isDone").unwrap_or(&json!(null)),
        "statusCode": parsed.get("statusCode").unwrap_or(&json!(null)),
        "message": parsed.get("message").unwrap_or(&json!("")),
        "lastModifiedTime": parsed.get("lastModifiedTime").unwrap_or(&json!(null)),
    });
    cli_println!("{}", serde_json::to_string_pretty(&summary).unwrap_or_default());
    json_field("task_id", json!(id));
    json_field("raw", parsed);
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
    let parsed: Value =
        serde_json::from_str(&result).unwrap_or(Value::String(result.clone()));

    // Show result payload only: resultSet, pageContentBytes
    let payload = json!({
        "id": parsed.get("id").and_then(|v| v.as_str()).unwrap_or(&id),
        "resultSet": parsed.get("resultSet").unwrap_or(&json!([])),
        "pageContentBytes": parsed.get("pageContentBytes").unwrap_or(&json!(null)),
        "error": parsed.get("error").unwrap_or(&json!(null)),
    });
    cli_println!("{}", serde_json::to_string_pretty(&payload).unwrap_or_default());
    json_field("task_id", json!(id));
    json_field("raw", parsed);
    Ok(())
}

async fn handle_swarm_list(tool_params: &Value) -> Result<(), String> {
    // Support --clear to remove all tracked swarm tasks
    if tool_params
        .get("clear")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        let mut list = read_async_tasks(None);
        let before = list.tasks.len();
        list.tasks.retain(|t| t.command != "swarm");
        let removed = before - list.tasks.len();
        write_async_tasks(&list, None).map_err(|e| e.to_string())?;
        cli_println!("Cleared {} tracked swarm task(s).", removed);
        json_field("cleared", json!(removed));
        return Ok(());
    }

    let _ = prune_async_tasks(None);
    let list = read_async_tasks(None);
    let filtered: Vec<_> = list.tasks.iter().filter(|t| t.command == "swarm").cloned().collect();
    let display = state::AsyncTaskList { tasks: filtered };
    cli_println!("{}", format_async_task_list(&display));
    Ok(())
}

/// Poll a list of swarm task IDs until all complete or timeout.
async fn swarm_wait_for_jobs(
    client: &Client,
    base_url: &str,
    task_ids: &[String],
) -> Result<(), String> {
    let total = task_ids.len();
    cli_println!(
        "Waiting for {} job(s) to complete...",
        total
    );

    let poll_interval = std::time::Duration::from_secs(2);
    let max_wait = std::time::Duration::from_secs(300); // 5-minute timeout
    let start = std::time::Instant::now();

    let mut completed = vec![false; total];
    let mut last_report = start;

    loop {
        let mut all_done = true;
        for (i, id) in task_ids.iter().enumerate() {
            if completed[i] {
                continue;
            }
            match get_swarm_status(client, base_url, id).await {
                Ok(result) => {
                    let parsed: Value = serde_json::from_str(&result).unwrap_or_default();
                    let is_done = parsed
                        .get("isDone")
                        .and_then(|v| v.as_bool())
                        .unwrap_or(false);
                    if is_done {
                        completed[i] = true;
                    } else {
                        all_done = false;
                    }
                }
                Err(_) => {
                    // Keep waiting on transient errors
                    all_done = false;
                }
            }
        }

        if all_done {
            cli_println!(
                "All {} job(s) completed in {:.0}s.",
                total,
                start.elapsed().as_secs_f64()
            );
            // Print a summary table
            cli_println!("\n  {:^8}  {:^12}  {}", "STATUS", "TASK ID", "URL");
            cli_println!("  {:-<8}  {:-<12}  {:-<40}", "", "", "");
            for (i, id) in task_ids.iter().enumerate() {
                let short_id = if id.len() > 8 { &id[..8] } else { id };
                let status = if completed[i] { "done" } else { "timeout" };
                cli_println!("  {:^8}  {:<12}  ...", status, short_id);
            }
            return Ok(());
        }

        if start.elapsed() > max_wait {
            let pending: Vec<_> = task_ids
                .iter()
                .enumerate()
                .filter(|(i, _)| !completed[*i])
                .map(|(_, id)| id.clone())
                .collect();
            cli_println!(
                "Timeout after {:.0}s. {} of {} job(s) completed. {} job(s) still pending. Use 'swarm status <id>' to check manually.",
                start.elapsed().as_secs_f64(),
                completed.iter().filter(|&&c| c).count(),
                total,
                pending.len(),
            );
            json_field("pending_task_ids", json!(pending));
            return Ok(());
        }

        // Progress report every 30 seconds
        if last_report.elapsed() >= std::time::Duration::from_secs(30) {
            let done_count = completed.iter().filter(|&&c| c).count();
            cli_println!(
                "  ... {}/{} job(s) completed (elapsed: {:.0}s)",
                done_count,
                total,
                start.elapsed().as_secs_f64()
            );
            last_report = std::time::Instant::now();
        }

        tokio::time::sleep(poll_interval).await;
    }
}

async fn handle_crawl_list(
    client: &Client,
    base_url: &str,
) -> Result<(), String> {
    let _ = prune_async_tasks(None);
    let list = read_async_tasks(None);
    let filtered: Vec<_> = list.tasks.iter().filter(|t| t.command == "crawl").cloned().collect();

    if filtered.is_empty() {
        cli_println!("No tracked crawl tasks. Start one with 'crawl <url>'.");
        return Ok(());
    }

    // Query backend for live status of each tracked task, merging
    // backend state with local tracking for a unified view.
    cli_println!("{:<38}  {:<12}  {:<12}  {}", "TASK ID", "CLI STATUS", "SERVER STATUS", "URL");
    cli_println!("{}", "-".repeat(100));

    for task in &filtered {
        let server_status = match get_crawl_result(client, base_url, &task.task_id).await {
            Ok(text) => {
                serde_json::from_str::<Value>(&text)
                    .ok()
                    .and_then(|v| v["status"].as_str().map(String::from))
                    .unwrap_or_else(|| "parse error".to_string())
            }
            Err(_) => "unreachable".to_string(),
        };

        cli_println!(
            "{:<38}  {:<12}  {:<12}  {}",
            task.task_id,
            task.last_status,
            server_status,
            task.description
        );
    }

    cli_println!("\nTip: use 'crawl cancel <id>' to cancel a stuck task, 'crawl clear' to remove terminal tasks.");
    Ok(())
}

async fn handle_crawl_status(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let id = tool_params
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if id.is_empty() {
        return Err("Task ID is required. Use 'crawl list' to see tracked tasks.".to_string());
    }

    let result = get_crawl_status(client, base_url, id).await?;
    cli_println!("{}", result);
    json_field("task_id", json!(id));
    json_field(
        "raw",
        json!(serde_json::from_str::<Value>(&result).unwrap_or(Value::String(result.clone()))),
    );
    Ok(())
}

async fn handle_crawl_result(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let id = tool_params
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if id.is_empty() {
        return Err("Task ID is required. Use 'crawl list' to see tracked tasks.".to_string());
    }

    let result = get_crawl_result(client, base_url, id).await?;
    cli_println!("{}", result);
    json_field("task_id", json!(id));
    json_field(
        "raw",
        json!(serde_json::from_str::<Value>(&result).unwrap_or(Value::String(result.clone()))),
    );
    Ok(())
}

async fn handle_crawl_cancel(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
) -> Result<(), String> {
    let id = tool_params
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default();

    if id.is_empty() {
        return Err("Task ID is required. Use 'crawl list' to see tracked tasks.".to_string());
    }

    let result = cancel_crawl(client, base_url, id).await?;
    cli_println!("{}", result);
    json_field("task_id", json!(id));

    // Update local tracking
    let _ = update_async_task_status(id, "cancelled", None);
    Ok(())
}

async fn handle_crawl_clear(
    client: &Client,
    base_url: &str,
) -> Result<(), String> {
    let result = clear_crawls(client, base_url).await?;
    cli_println!("{}", result);
    // Also clean local tracking
    let _ = prune_async_tasks(None);
    Ok(())
}

async fn handle_crawl(
    client: &Client,
    base_url: &str,
    tool_params: &Value,
    _session_name: Option<&str>,
) -> Result<(), String> {
    // ---- Resolve URLs ----
    let url = tool_params
        .get("url")
        .and_then(|v| v.as_str())
        .unwrap_or("");

    let seed_file = tool_params
        .get("seedFile")
        .and_then(|v| v.as_str());

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
        return Err("No URLs provided. Specify a URL argument or --seed-file.".to_string());
    }

    // ---- Resolve X-SQL query ----
    let use_sql_stdin = tool_params
        .get("sqlStdin")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let query_raw: Option<String> = if use_sql_stdin {
        let mut input = String::new();
        std::io::stdin()
            .read_to_string(&mut input)
            .map_err(|e| format!("Failed to read X-SQL query from stdin: {e}"))?;
        if input.trim().is_empty() {
            return Err("Stdin was empty but --sql-stdin was specified.".to_string());
        }
        Some(input)
    } else {
        tool_params
            .get("sql")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
    };

    let has_sql = query_raw.is_some();
    let resolved_sql: Option<String> = match query_raw {
        Some(q) if q.starts_with('@') => {
            let file_path = &q[1..];
            Some(resolve_sql_file(file_path)?)
        }
        Some(q) => {
            let decoded = maybe_decode_base64_sql(q, tool_params)?;
            Some(decoded)
        }
        None => None,
    };

    // ---- Resolve args: @file prefix and --args-stdin ----
    let use_args_stdin = tool_params
        .get("argsStdin")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let args_stdin_content: Option<String> = if use_args_stdin {
        let mut input = String::new();
        std::io::stdin()
            .read_to_string(&mut input)
            .map_err(|e| format!("Failed to read args from stdin: {e}"))?;
        if input.trim().is_empty() {
            return Err("Stdin was empty but --args-stdin was specified.".to_string());
        }
        Some(input.trim().to_string())
    } else {
        None
    };

    // If any --args value starts with '@', treat it as a file path and read its content.
    // Resolve after the initial tool_params_fn assembly, before sending to server.
    let resolved_args: Option<String> = {
        let raw_args = tool_params
            .get("args")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        if raw_args.is_empty() && args_stdin_content.is_none() {
            None
        } else {
            let mut parts: Vec<String> = Vec::new();
            // Split existing args by whitespace and resolve any @file entries
            for part in raw_args.split_whitespace() {
                if part.starts_with('@') {
                    let file_path = &part[1..];
                    let content = std::fs::read_to_string(file_path)
                        .map_err(|e| format!("Failed to read args file '{}': {}", file_path, e))?;
                    parts.push(content.trim().to_string());
                } else {
                    parts.push(part.to_string());
                }
            }
            // Append stdin content if provided
            if let Some(ref stdin_content) = args_stdin_content {
                parts.push(stdin_content.clone());
            }
            Some(parts.join(" "))
        }
    };

    // ---- Resolve output options ----
    let format = tool_params
        .get("format")
        .and_then(|v| v.as_str())
        .unwrap_or("table")
        .to_ascii_lowercase();

    match format.as_str() {
        "json" | "csv" | "table" => {}
        _ => return Err(format!("Invalid --format '{}'. Expected: json, csv, or table", format)),
    }

    let output_file = tool_params
        .get("output")
        .and_then(|v| v.as_str());

    // ---- Build server-bound params (strip CLI-only keys) ----
    let mut server_params = tool_params.clone();
    if let Value::Object(ref mut m) = server_params {
        m.remove("seedFile");
        m.remove("sqlStdin");
        m.remove("sqlBase64");
        m.remove("argsStdin");
        m.remove("format");
        m.remove("output");
        // Insert resolved urls array and resolved sql
        let url_array: Vec<Value> = urls.iter().map(|u| json!(u)).collect();
        m.insert("urls".to_string(), json!(url_array));
        if let Some(ref sql) = resolved_sql {
            m.insert("sql".to_string(), json!(sql));
        }
        // Override args with resolved value (handles @file and stdin)
        if let Some(ref args) = resolved_args {
            m.insert("args".to_string(), json!(args));
        } else {
            // Ensure args is always present (Issue 2 fix: prevent Kotlin null)
            m.entry("args".to_string()).or_insert(json!(""));
        }
        // Ensure url is set to the first URL for backward compat
        if url.is_empty() {
            m.insert("url".to_string(), json!(urls[0]));
        }
    }

    let primary_url = &urls[0];
    let task_id = submit_crawl(client, base_url, &server_params).await?;
    let task_id = task_id.trim().trim_matches('"').to_string();
    cli_println!("Crawl task submitted: {}", task_id);
    cli_println!("  URLs: {}", urls.len());
    if has_sql {
        cli_println!("  X-SQL extraction: enabled");
    }
    json_field("task_id", json!(task_id));

    // Persist the task for cross-session tracking
    let _ = track_async_task(&task_id, "crawl", primary_url, None);

    let background = tool_params
        .get("background")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    if background {
        cli_println!(
            "Running in background. Task ID: {}. Use 'browser4-cli crawl list' to view all tracked tasks.",
            task_id
        );
        return Ok(());
    }

    let poll_interval = std::time::Duration::from_secs(2);
    let timeout = crawl_request_timeout();
    let start = std::time::Instant::now();
    let mut last_report = std::time::Duration::ZERO;
    let report_interval = std::time::Duration::from_secs(15);

    cli_println!("Waiting for crawl to complete (task {}). Use --background for long-running crawls.", task_id);

    loop {
        if start.elapsed() > timeout {
            let _ = update_async_task_status(
                &task_id,
                &format!("timeout after {}s", timeout.as_secs()),
                None,
            );
            return Err(format!(
                "Crawl timed out after {} seconds. Task ID: {}. \
                 Increase the timeout with the BROWSER4_CLI_CRAWL_TIMEOUT_SECS environment variable.",
                timeout.as_secs(),
                task_id
            ));
        }

        tokio::time::sleep(poll_interval).await;

        let response_text = get_crawl_result(client, base_url, &task_id).await?;
        let parsed: Value = serde_json::from_str(&response_text)
            .map_err(|e| format!("Failed to parse crawl response: {}", e))?;

        let status = parsed["status"].as_str().unwrap_or("");
        let pages_found = parsed["pagesFound"].as_i64().unwrap_or(0);
        let error = parsed["error"].as_str();

        // Periodic progress indicator so foreground crawls don't look hung
        let elapsed = start.elapsed();
        if elapsed - last_report >= report_interval {
            last_report = elapsed;
            if pages_found > 0 {
                cli_println!(
                    "Still crawling... {} pages found so far ({}s elapsed)",
                    pages_found,
                    elapsed.as_secs()
                );
            } else {
                cli_println!(
                    "Still waiting for crawl to start... ({}s elapsed). \
                     If the queue is congested, try stopping old tasks or using --background. \
                     Run 'crawl cancel {}' to cancel this task.",
                    elapsed.as_secs(),
                    task_id
                );
            }
        }

        match status {
            "OK" | "SC_OK" => {
                let pages = parsed["pages"].as_array();
                let page_count = pages.map(|p| p.len()).unwrap_or(0);

                // Collect extracted data from all pages when X-SQL was provided
                let mut all_extracted: Vec<Value> = Vec::new();
                if has_sql {
                    if let Some(pages) = pages {
                        for page in pages {
                            if let Some(extracted) = page["extracted"].as_array() {
                                all_extracted.extend(extracted.iter().cloned());
                            }
                        }
                    }
                }

                // Format output
                if has_sql {
                    let extracted_output: String = if all_extracted.is_empty() {
                        "No extracted data.".to_string()
                    } else {
                        match format.as_str() {
                            "json" => serde_json::to_string_pretty(&all_extracted)
                                .unwrap_or_else(|_| "[]".to_string()),
                            "csv" => format_csv(&all_extracted),
                            "table" | _ => format_table(&all_extracted),
                        }
                    };

                    if let Some(ref file_path) = output_file {
                        std::fs::write(file_path, &extracted_output)
                            .map_err(|e| format!("Failed to write output file '{}': {}", file_path, e))?;
                        cli_println!("Results written to {}", file_path);
                    } else {
                        cli_println!("\n{}", extracted_output);
                    }

                    json_field("extracted", json!(all_extracted));
                } else {
                    let mut page_lines: Vec<String> = Vec::new();
                    page_lines.push(format!("Crawl completed. {} pages found.", page_count));

                    // Display diagnostic info when 0 pages found (e.g. selector matched no elements)
                    if page_count == 0 {
                        if let Some(diag) = parsed["diagnostic"].as_str() {
                            page_lines.push(format!("\n  Diagnostic: {}", diag));
                        }
                        page_lines.push("\n  Tips:".to_string());
                        page_lines.push("    - Verify the --out-link-selector targets the correct elements".to_string());
                        page_lines.push("    - Use 'snapshot' or 'htmlsnapshot' to inspect the page structure first".to_string());
                    }

                    if let Some(pages) = pages {
                        for page in pages {
                            let page_url = page["url"].as_str().unwrap_or("");
                            let page_title = page["title"].as_str().unwrap_or("");
                            let page_depth = page["depth"].as_i64().unwrap_or(0);
                            page_lines.push(format!(
                                "  depth={} | {} | {}",
                                page_depth, page_url, page_title
                            ));
                        }
                    }
                    let page_output = page_lines.join("\n");

                    if let Some(ref file_path) = output_file {
                        std::fs::write(file_path, &page_output)
                            .map_err(|e| format!("Failed to write output file '{}': {}", file_path, e))?;
                        cli_println!("\nCrawl completed. {} pages found. Results written to {}", page_count, file_path);
                    } else {
                        cli_println!("\n{}", page_output);
                    }
                }

                json_field("pages", json!(pages));
                json_field("pages_found", json!(page_count));
                // Update the locally-tracked task status so `crawl list`
                // reflects completion instead of forever showing "pending".
                let _ = update_async_task_status(
                    &task_id,
                    &format!("{} ({} pages)", status, page_count),
                    None,
                );
                return Ok(());
            }
            "SC_REQUEST_TIMEOUT" | "SC_INTERNAL_SERVER_ERROR" => {
                let err_msg = error.unwrap_or("Unknown crawl error");
                let _ = update_async_task_status(
                    &task_id,
                    &format!("error: {}", err_msg),
                    None,
                );
                return Err(format!("Crawl failed: {}", err_msg));
            }
            _ => {
                // Still running — report progress
                if pages_found > 0 {
                    cli_println!("Crawling... {} pages found so far", pages_found);
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Output formatting helpers for extracted crawl data
// ---------------------------------------------------------------------------

/// Format a list of extracted data rows as CSV with header row.
/// Uses manual escaping (no csv crate dependency needed).
fn format_csv(rows: &[Value]) -> String {
    if rows.is_empty() {
        return String::new();
    }

    // Collect column names in order of first appearance
    let mut columns: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for row in rows {
        if let Value::Object(map) = row {
            for key in map.keys() {
                if seen.insert(key.clone()) {
                    columns.push(key.clone());
                }
            }
        }
    }

    if columns.is_empty() {
        return String::new();
    }

    // CSV escape: wrap in double quotes if contains comma, quote, or newline
    let escape = |s: &str| -> String {
        if s.contains(',') || s.contains('"') || s.contains('\n') {
            format!("\"{}\"", s.replace('"', "\"\""))
        } else {
            s.to_string()
        }
    };

    let mut out = String::new();
    // Header row
    out.push_str(
        &columns
            .iter()
            .map(|c| escape(c))
            .collect::<Vec<_>>()
            .join(","),
    );
    out.push('\n');
    // Data rows
    for row in rows {
        let vals: Vec<String> = columns
            .iter()
            .map(|col| {
                row.get(col)
                    .map(|v| match v {
                        Value::String(s) => escape(s),
                        Value::Null => String::new(),
                        other => escape(&other.to_string()),
                    })
                    .unwrap_or_default()
            })
            .collect();
        out.push_str(&vals.join(","));
        out.push('\n');
    }
    out
}

/// Format extracted data as a human-readable aligned table.
fn format_table(rows: &[Value]) -> String {
    if rows.is_empty() {
        return "No data.".to_string();
    }

    // Collect column names in order of first appearance
    let mut columns: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for row in rows {
        if let Value::Object(map) = row {
            for key in map.keys() {
                if seen.insert(key.clone()) {
                    columns.push(key.clone());
                }
            }
        }
    }

    if columns.is_empty() {
        return "No data.".to_string();
    }

    // Build string data and compute column widths
    let mut col_widths: Vec<usize> = columns.iter().map(|c| c.len()).collect();
    let cell_data: Vec<Vec<String>> = rows
        .iter()
        .map(|row| {
            columns
                .iter()
                .enumerate()
                .map(|(i, col)| {
                    let val = row
                        .get(col)
                        .map(|v| match v {
                            Value::String(s) => s.clone(),
                            Value::Null => String::new(),
                            Value::Bool(b) => b.to_string(),
                            Value::Number(n) => n.to_string(),
                            _ => v.to_string(),
                        })
                        .unwrap_or_default();
                    col_widths[i] = col_widths[i].max(val.len());
                    val
                })
                .collect()
        })
        .collect();

    let mut out = String::new();

    // Header
    let header: Vec<String> = columns
        .iter()
        .enumerate()
        .map(|(i, c)| format!("{:width$}", c, width = col_widths[i]))
        .collect();
    out.push_str("  ");
    out.push_str(&header.join(" | "));
    out.push('\n');

    // Separator
    let sep: Vec<String> = col_widths.iter().map(|w| "-".repeat(*w)).collect();
    out.push_str("  ");
    out.push_str(&sep.join("-+-"));
    out.push('\n');

    // Data rows
    for row_cells in &cell_data {
        let formatted: Vec<String> = row_cells
            .iter()
            .enumerate()
            .map(|(i, val)| format!("{:width$}", val, width = col_widths[i]))
            .collect();
        out.push_str("  ");
        out.push_str(&formatted.join(" | "));
        out.push('\n');
    }

    out
}

// ---------------------------------------------------------------------------
// Loop command — periodic task execution
// ---------------------------------------------------------------------------

/// Parse arguments for the `loop` command from raw CLI tokens (everything
/// after the command name).
///
/// Recognises `--shell`, `--`, `--interval`/`-i`, `--count`/`-n`,
/// `--timeout`/`-t`, `--pause`/`--resume`/`--pause-all`/`--resume-all`/`--stop-all`,
/// and collects everything else as the task.
const DEFAULT_LOOP_INTERVAL_SECS: u64 = 3600; // 1 hour
const DEFAULT_LOOP_TIMEOUT_SECS: u64 = 604800; // 1 week
const PAUSE_POLL_INTERVAL_SECS: u64 = 2; // How often to check for resume while paused

fn parse_loop_args(args: &[String]) -> Result<LoopArgs, String> {
    let mut out = LoopArgs {
        interval_secs: DEFAULT_LOOP_INTERVAL_SECS,
        timeout_secs: Some(DEFAULT_LOOP_TIMEOUT_SECS),
        ..Default::default()
    };
    let mut after_dash_dash = false;
    let mut i = 0;

    // Set of known loop-level flags. When any of these appear after `--`
    // (i.e. in subcommand position), they are silently captured by the
    // subcommand rather than being parsed by the loop command. We detect
    // this and warn the user.
    let known_loop_flags: &[&str] = &[
        "--name", "--interval", "-i", "--count", "-n", "--timeout", "-t",
        "--shell", "--pause", "--pause-all", "--resume", "--resume-all",
        "--stop", "--stop-all", "--status", "--list",
    ];

    while i < args.len() {
        let arg = &args[i];

        if after_dash_dash {
            // Detect known loop flags that appear after `--` and warn.
            // The user likely intended these as loop-level options.
            let flag_name = if let Some(_val) = arg.strip_prefix("--name=") {
                Some("--name")
            } else if let Some(_val) = arg.strip_prefix("--interval=") {
                Some("--interval")
            } else if let Some(_val) = arg.strip_prefix("--count=") {
                Some("--count")
            } else if let Some(_val) = arg.strip_prefix("--timeout=") {
                Some("--timeout")
            } else if known_loop_flags.contains(&arg.as_str()) {
                Some(arg.as_str())
            } else {
                None
            };

            if let Some(flag) = flag_name {
                eprintln!(
                    "[WARN] `{}` after `--` is treated as a nested browser4-cli argument, \
                     not a loop option.  Did you mean `loop {} <value> -- ...`?",
                    arg, flag,
                );
            }

            out.task_tokens.push(arg.clone());
            i += 1;
            continue;
        }

        if arg == "--" {
            after_dash_dash = true;
            out.is_subcommand = true;
            i += 1;
            continue;
        }

        // Control flags (no task required)
        if arg == "--stop" {
            out.stop = true;
            i += 1;
            continue;
        }

        if arg == "--stop-all" {
            out.stop_all = true;
            i += 1;
            continue;
        }

        if arg == "--status" {
            out.status = true;
            i += 1;
            continue;
        }

        if arg == "--list" {
            out.list = true;
            i += 1;
            continue;
        }

        if arg == "--pause" {
            out.pause = true;
            i += 1;
            continue;
        }

        if arg == "--resume" {
            out.resume = true;
            i += 1;
            continue;
        }

        if arg == "--pause-all" {
            out.pause_all = true;
            i += 1;
            continue;
        }

        if arg == "--resume-all" {
            out.resume_all = true;
            i += 1;
            continue;
        }

        if arg == "--history" {
            out.history = true;
            i += 1;
            continue;
        }

        if arg == "--keep-state" {
            out.keep_state = true;
            i += 1;
            continue;
        }

        if arg == "--shell" {
            out.is_shell = true;
            i += 1;
            continue;
        }

        if let Some(val) = arg.strip_prefix("--name=") {
            let name = val.to_string();
            validate_loop_name(&name)?;
            out.name = Some(name);
        } else if arg == "--name" {
            let name = next_arg(&args, &mut i, "name")?.to_string();
            validate_loop_name(&name)?;
            out.name = Some(name);
        } else if let Some(val) = arg.strip_prefix("--interval=") {
            out.interval_secs = parse_u64_required(val, "--interval")?;
        } else if arg == "--interval" || arg == "-i" {
            out.interval_secs = parse_u64_required(next_arg(&args, &mut i, "interval")?, "interval")?;
        } else if let Some(val) = arg.strip_prefix("--count=") {
            out.count = Some(parse_u64_required(val, "--count")?);
        } else if arg == "--count" || arg == "-n" {
            out.count = Some(parse_u64_required(next_arg(&args, &mut i, "count")?, "count")?);
        } else if let Some(val) = arg.strip_prefix("--timeout=") {
            out.timeout_secs = Some(parse_u64_required(val, "--timeout")?);
        } else if arg == "--timeout" || arg == "-t" {
            out.timeout_secs = Some(parse_u64_required(next_arg(&args, &mut i, "timeout")?, "timeout")?);
        } else if arg.starts_with('-') {
            return Err(format!("Unknown option: {}", arg));
        } else {
            out.task_tokens.push(arg.clone());
        }
        i += 1;
    }

    // Flags that are control-only — they reject combining with a task.
    // --pause is excluded from this set: combining --pause with a task starts
    // the loop in paused state (the user can --resume later).
    let no_task_flags = out.stop || out.stop_all || out.status || out.list
        || out.resume || out.pause_all || out.resume_all || out.history;

    if no_task_flags {
        if !out.task_tokens.is_empty() {
            let flag = if out.stop { "--stop" }
                else if out.stop_all { "--stop-all" }
                else if out.status { "--status" }
                else if out.list { "--list" }
                else if out.resume { "--resume" }
                else if out.pause_all { "--pause-all" }
                else if out.resume_all { "--resume-all" }
                else { "--history" };
            return Err(format!(
                "The {} flag cannot be combined with a task. Use just `browser4-cli loop {}`.",
                flag, flag,
            ));
        }
        // --list doesn't make sense with --name
        if out.list && out.name.is_some() {
            return Err("The --list and --name flags cannot be combined. Use just `browser4-cli loop --list`.".to_string());
        }
        // --pause-all, --resume-all, --stop-all don't need --name
        if (out.pause_all || out.resume_all || out.stop_all) && out.name.is_some() {
            let flag = if out.pause_all { "--pause-all" }
                else if out.resume_all { "--resume-all" }
                else { "--stop-all" };
            return Err(format!(
                "The {} flag cannot be combined with --name. It applies to all loops.",
                flag
            ));
        }
        // --pause and --resume are mutually exclusive
        if out.pause && out.resume {
            return Err("The --pause and --resume flags are mutually exclusive.".to_string());
        }
        return Ok(out);
    }

    // --pause without other control flags: if a task is provided, this is
    // "start paused"; if not, it's a control op handled above.
    // --resume with a task is always an error — you resume an existing loop.
    if out.resume && !out.task_tokens.is_empty() {
        return Err("The --resume flag cannot be combined with a task. Use just `browser4-cli loop --resume` to resume an existing loop.".to_string());
    }

    // --pause with a task: start-paused is valid (falls through to normal validation)
    if out.pause && out.task_tokens.is_empty() {
        return Ok(out); // control op: pause running loop, no task needed
    }

    // Validate
    if out.is_shell && out.is_subcommand {
        return Err("The --shell flag and -- separator are mutually exclusive. Use --shell for shell commands or -- for browser4-cli subcommands.".to_string());
    }

    if out.task_tokens.is_empty() {
        return Err("A task is required. Provide a plain text command, x-sql query, --shell <cmd>, -- <browser4-cli subcommand>, --list, --stop, --status, --pause, or --resume.".to_string());
    }

    Ok(out)
}

/// Helper: get the next argument from the iterator, advancing past it.
fn next_arg<'a>(args: &'a [String], i: &mut usize, name: &str) -> Result<&'a str, String> {
    *i += 1;
    args.get(*i)
        .map(|s| s.as_str())
        .ok_or_else(|| format!("Expected a value after --{}", name))
}

/// Helper: parse a string to u64, emitting a contextual error.
fn parse_u64_required(s: &str, flag: &str) -> Result<u64, String> {
    s.parse::<u64>()
        .map_err(|_| format!("Invalid value for {}: '{}'. Expected a non-negative integer.", flag, s))
}

/// Validate a loop --name value. Only allows alphanumeric, dot, hyphen,
/// and underscore characters.  Rejects names that contain path separators,
/// parent-directory sequences, or other characters that could be used for
/// path traversal.
fn validate_loop_name(name: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err("Loop name must not be empty.".to_string());
    }
    if name.contains('/') || name.contains('\\') || name.contains("..") {
        return Err(format!(
            "Loop name contains invalid path characters: '{}'. \
             Use only letters, digits, dots, hyphens, and underscores.",
            name,
        ));
    }
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_') {
        return Err(format!(
            "Invalid loop name: '{}'. \
             Use only letters, digits, dots, hyphens, and underscores.",
            name,
        ));
    }
    Ok(())
}

/// Format a duration in seconds as a human-readable string.
///
/// Examples: `30s`, `5m 30s`, `2h 15m`, `3d 6h`, `1w 2d`.
fn format_duration(total_secs: u64) -> String {
    if total_secs == 0 {
        return "0s".to_string();
    }
    let weeks = total_secs / 604800;
    let days = (total_secs % 604800) / 86400;
    let hours = (total_secs % 86400) / 3600;
    let minutes = (total_secs % 3600) / 60;
    let seconds = total_secs % 60;

    let mut parts: Vec<String> = Vec::new();
    if weeks > 0 {
        parts.push(format!("{}w", weeks));
    }
    if days > 0 {
        parts.push(format!("{}d", days));
    }
    if hours > 0 {
        parts.push(format!("{}h", hours));
    }
    if minutes > 0 {
        parts.push(format!("{}m", minutes));
    }
    if seconds > 0 || parts.is_empty() {
        parts.push(format!("{}s", seconds));
    }
    parts.join(" ")
}

/// Execute a shell command via the OS shell.
///
/// Uses `cmd /C` on Windows and `sh -c` on Unix.
fn run_shell_command(task: &str) -> Result<String, String> {
    #[cfg(windows)]
    let (shell, flag) = ("cmd", "/C");
    #[cfg(not(windows))]
    let (shell, flag) = ("sh", "-c");

    // On Windows, prepend `chcp 65001 >nul &&` to switch the console code
    // page to UTF-8 (65001) so that non-ASCII characters (e.g. Chinese
    // day-of-week from `date /t`) are captured correctly by the UTF-8
    // String::from_utf8_lossy decoder.
    #[cfg(windows)]
    let command = format!("chcp 65001 >nul && {}", task);
    #[cfg(not(windows))]
    let command = task.to_string();

    let output = std::process::Command::new(shell)
        .arg(flag)
        .arg(&command)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .output()
        .map_err(|e| format!("Failed to execute shell command: {}", e))?;

    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();

    if output.status.success() {
        Ok(stdout.trim().to_string())
    } else {
        Err(if stderr.is_empty() {
            format!("Shell command exited with {}: {}", output.status, stdout.trim())
        } else {
            stderr.trim().to_string()
        })
    }
}

/// Execute a `browser4-cli` subcommand by spawning the current binary with
/// the given tokens.
async fn run_browser4_cli(tokens: &[String]) -> Result<String, String> {
    let exe = std::env::current_exe()
        .map_err(|e| format!("Cannot determine CLI path: {}", e))?;

    let tokens = tokens.to_vec();
    let output = tokio::task::spawn_blocking(move || {
        std::process::Command::new(&exe)
            .args(&tokens)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .output()
    })
    .await
    .map_err(|e| format!("Subprocess spawn failed: {}", e))?
    .map_err(|e| format!("Failed to execute browser4-cli: {}", e))?;

    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();

    if output.status.success() {
        Ok(stdout.trim().to_string())
    } else {
        Err(if stderr.is_empty() {
            format!(
                "browser4-cli exited with {}: {}",
                output.status,
                stdout.trim()
            )
        } else {
            stderr.trim().to_string()
        })
    }
}

/// Spawn a detached background process that survives parent exit.
///
/// On Windows, uses `CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS` so the
/// child is not tied to the parent console / process tree.  On Unix the
/// child is simply spawned — the OS reparents it to init when the parent
/// exits.
fn spawn_detached(exe: &std::path::Path, args: &[String]) -> Result<u32, String> {
    let mut cmd = std::process::Command::new(exe);
    cmd.args(args)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .stdin(std::process::Stdio::null());

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        cmd.creation_flags(CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS);
    }

    let child = cmd
        .spawn()
        .map_err(|e| format!("Failed to spawn background process: {}", e))?;

    Ok(child.id())
}

/// Handle the `loop` command — execute a task periodically with persistence,
/// resume, pause, and stop support.
async fn handle_loop(
    client: &Client,
    base_url: &str,
    global: &args::GlobalFlags,
) -> Result<(), String> {
    let parsed = parse_loop_args(&global.args[1..])?;

    let loop_name: Option<&str> = parsed.name.as_deref();

    // --- --list: list all persisted loops ---
    if parsed.list {
        let entries = state::list_loop_states(None);
        if entries.is_empty() {
            cli_println!("No persisted loops. Start one with `browser4-cli loop <task>`.");
        } else {
            cli_println!("{} persisted loop(s):\n", entries.len());
            for entry in &entries {
                let (icon, status_label) = match entry.status.as_str() {
                    "running" => ("▶", "running"),
                    "paused" => ("⏸", "paused"),
                    "stopped" => ("⏹", "stopped"),
                    "completed" => ("✓", "completed"),
                    _ => ("?", &entry.status[..]),
                };
                let name_display = if entry.name == "default" {
                    "(default)".to_string()
                } else {
                    entry.name.clone()
                };
                let count_display = match entry.status.as_str() {
                    "completed" => entry.iterations_completed.to_string(),
                    _ => match entry.count {
                        Some(max) => format!("{}/{}", entry.iterations_completed, max),
                        None => format!("{}", entry.iterations_completed),
                    },
                };
                let interval_display = format_duration(entry.interval_secs);
                cli_println!(
                    "  {}  {:<20}  {:>6} iters  {:>5} intv  {:<8}  {}",
                    icon,
                    name_display,
                    count_display,
                    interval_display,
                    status_label,
                    entry.task,
                );
            }
            cli_println!("\nColumns: name, iters (completed[/max]), interval, status, task.");
            cli_println!("Use --status [name] for full details, --pause/--resume [name] to control, --stop [name] to clear.");
        }
        json_field("loops", json!(entries));
        return Ok(());
    }

    // --- --history: show recently completed loops ---
    if parsed.history {
        let entries = state::read_loop_history(None);
        if entries.is_empty() {
            cli_println!("No completed loops in history. Start one with `browser4-cli loop <task>`.");
        } else {
            cli_println!("{} completed loop(s) in history (newest last):\n", entries.len());
            for entry in &entries {
                let reason_label = match entry.exit_reason.as_str() {
                    "count-reached" => "count reached",
                    "timeout" => "timeout",
                    "stopped" => "stopped by user",
                    "interrupted" => "interrupted",
                    _ => &entry.exit_reason,
                };
                let name_display = if entry.name == "default" {
                    "(default)".to_string()
                } else {
                    entry.name.clone()
                };
                cli_println!(
                    "  ✓  {:<20}  {:>3} iters  {:<16}  {}  {}",
                    name_display,
                    entry.iterations_completed,
                    reason_label,
                    entry.completed_at,
                    entry.task_tokens.join(" "),
                );
            }
            cli_println!("\nHistory keeps the most recent {} completed loops.", state::MAX_HISTORY_ENTRIES);
        }
        json_field("history", json!(entries));
        return Ok(());
    }

    // --- --status: print loop state and exit ---
    if parsed.status {
        let state_path = state::loop_state_path(None, loop_name);
        match state::read_loop_state(None, loop_name) {
            Some(ls) => {
                let label = if let Some(ref n) = parsed.name {
                    format!("Loop \"{}\"", n)
                } else {
                    "Loop".to_string()
                };
                let (icon, status_label) = match ls.status.as_str() {
                    "running" => ("▶", "Running"),
                    "paused" => ("⏸", "Paused"),
                    "stopped" => ("⏹", "Stopped by user"),
                    "completed" => ("✓", "Completed"),
                    _ => ("?", &ls.status[..]),
                };
                cli_println!("{}  {} {}", icon, label, status_label);
                cli_println!("   Task:       {}", ls.task_tokens.join(" "));
                cli_println!("   Mode:       {}", ls.mode);
                cli_println!("   Interval:   {}", format_duration(ls.interval_secs));
                if let Some(n) = ls.count {
                    let remaining = n.saturating_sub(ls.iterations_completed);
                    cli_println!(
                        "   Iterations: {}/{} ({} remaining)",
                        ls.iterations_completed, n, remaining
                    );
                } else {
                    cli_println!("   Iterations: {} (no limit)", ls.iterations_completed);
                }
                if let Some(t) = ls.timeout_secs {
                    if let Ok(started) = chrono::DateTime::parse_from_rfc3339(&ls.started_at) {
                        let started_utc = started.with_timezone(&Utc);
                        let elapsed = Utc::now()
                            .signed_duration_since(started_utc)
                            .to_std()
                            .unwrap_or_default();
                        let elapsed_secs = elapsed.as_secs();
                        let remaining_secs = t.saturating_sub(elapsed_secs);
                        cli_println!(
                            "   Timeout:    {} total, {} elapsed, {} remaining",
                            format_duration(t),
                            format_duration(elapsed_secs),
                            format_duration(remaining_secs),
                        );
                    } else {
                        cli_println!("   Timeout:    {}", format_duration(t));
                    }
                }
                cli_println!("   Started:    {}", ls.started_at);
                cli_println!("   Updated:    {}", ls.updated_at);
                cli_println!("   State file: {}", state_path.display());
                json_field("loop_state", json!({
                    "name": loop_name.unwrap_or("default"),
                    "task_tokens": ls.task_tokens,
                    "mode": ls.mode,
                    "interval_secs": ls.interval_secs,
                    "count": ls.count,
                    "timeout_secs": ls.timeout_secs,
                    "iterations_completed": ls.iterations_completed,
                    "started_at": ls.started_at,
                    "updated_at": ls.updated_at,
                    "status": ls.status,
                    "state_file": state_path.to_string_lossy(),
                }));
            }
            None => {
                if let Some(n) = loop_name {
                    cli_println!("No loop named \"{}\".", n);
                    cli_println!("State file: {}", state_path.display());
                } else {
                    cli_println!("No active loop.");
                    cli_println!("State file: {}", state_path.display());
                    cli_println!("Start one with `browser4-cli loop <task>`.");
                }
            }
        }
        return Ok(());
    }

    // --- --stop: clear persisted loop state and exit ---
    if parsed.stop {
        let state_path = state::loop_state_path(None, loop_name);
        match state::read_loop_state(None, loop_name) {
            Some(ls) => {
                let total = ls.iterations_completed;
                let was_active = ls.status == "running" || ls.status == "paused";

                // Write a history entry before clearing state.
                let history_entry = state::LoopHistoryEntry {
                    name: loop_name.unwrap_or("default").to_string(),
                    task_tokens: ls.task_tokens.clone(),
                    mode: ls.mode.clone(),
                    iterations_completed: total,
                    exit_reason: "stopped".to_string(),
                    completed_at: Utc::now().to_rfc3339(),
                };
                let _ = state::write_loop_history(&history_entry, None);

                state::clear_loop_state(None, loop_name);
                let label = loop_name.unwrap_or("default");
                if was_active {
                    cli_println!(
                        "⏹  Loop \"{}\" stopped. {} iteration(s) completed. State cleared.",
                        label, total
                    );
                } else {
                    cli_println!(
                        "✓  Loop \"{}\" state cleared (was {} with {} iteration(s)).",
                        label, ls.status, total
                    );
                }
                cli_println!("   Removed: {}", state_path.display());
                json_field("stopped", json!(true));
                json_field("name", json!(label));
                json_field("iterations_completed", json!(total));
                json_field("previous_status", json!(ls.status));
            }
            None => {
                if let Some(n) = loop_name {
                    cli_println!("No loop named \"{}\" to stop.", n);
                } else {
                    cli_println!("No active loop to stop.");
                }
                cli_println!("State file: {}", state_path.display());
            }
        }
        return Ok(());
    }

    // --- --stop-all: clear all persisted loop states ---
    if parsed.stop_all {
        let entries = state::list_loop_states(None);
        if entries.is_empty() {
            cli_println!("No persisted loops to stop.");
            return Ok(());
        }
        let count = entries.len();
        let cleared = state::clear_all_loop_states(None);
        cli_println!(
            "⏹  Stopped and cleared {} loop(s) ({} state file(s) removed).",
            count, cleared
        );
        for entry in &entries {
            cli_println!("   - {} ({} iters, was {})", entry.name, entry.iterations_completed, entry.status);
        }
        json_field("stopped_all", json!(true));
        json_field("cleared_count", json!(cleared));
        json_field("loops", json!(entries));
        return Ok(());
    }

    // --- --pause: control op (pause running loop) or start-paused ---
    if parsed.pause {
        // If a task is provided, this is "start paused" — persist the
        // loop in paused state so the user can --resume later.
        if !parsed.task_tokens.is_empty() {
            let mode_key = if parsed.is_subcommand {
                "subcommand"
            } else if parsed.is_shell {
                "shell"
            } else {
                "plain"
            };
            let mode_label = if parsed.is_subcommand {
                "browser4-cli subcommand"
            } else if parsed.is_shell {
                "shell command"
            } else {
                "plain-text command"
            };
            let started_at = Utc::now().to_rfc3339();
            let state = state::LoopState {
                task_tokens: parsed.task_tokens.clone(),
                mode: mode_key.to_string(),
                interval_secs: parsed.interval_secs,
                count: parsed.count,
                timeout_secs: parsed.timeout_secs,
                iterations_completed: 0,
                started_at: started_at.clone(),
                updated_at: Utc::now().to_rfc3339(),
                status: "paused".to_string(),
            };
            let state_path = state::loop_state_path(None, loop_name);
            if let Err(e) = state::write_loop_state(&state, None, loop_name) {
                eprintln!("[WARN] Failed to persist loop state: {}", e);
            }
            let label = loop_name.unwrap_or("default");
            cli_println!(
                "Loop: \"{}\" — every {}s{}",
                parsed.task_tokens.join(" "),
                parsed.interval_secs,
                match (parsed.count, parsed.timeout_secs) {
                    (Some(n), Some(t)) => format!(", up to {} iterations or {}s", n, t),
                    (Some(n), None) => format!(", up to {} iterations", n),
                    (None, Some(t)) => format!(", up to {}s", t),
                    (None, None) => String::new(),
                }
            );
            cli_println!("  Mode: {}", mode_label);
            cli_println!(
                "⏸  Created as paused. Use `browser4-cli loop --resume{}` to start.",
                if parsed.name.is_some() {
                    format!(" --name {}", parsed.name.as_ref().unwrap())
                } else {
                    String::new()
                },
            );
            cli_println!("   State file: {}", state_path.display());
            json_field("started_paused", json!(true));
            json_field("name", json!(label));
            return Ok(());
        }

        // No task — control op: pause an existing running loop.
        let state_path = state::loop_state_path(None, loop_name);
        match state::set_loop_status(None, loop_name, "paused") {
            Some(prev) => {
                let label = loop_name.unwrap_or("default");
                if prev == "paused" {
                    cli_println!("⏸  Loop \"{}\" is already paused.", label);
                } else {
                    cli_println!(
                        "⏸  Loop \"{}\" paused (was {}). Use `browser4-cli loop --resume{}` to continue.",
                        label,
                        prev,
                        if parsed.name.is_some() { format!(" --name {}", parsed.name.as_ref().unwrap()) } else { String::new() },
                    );
                }
                cli_println!("   State file: {}", state_path.display());
                json_field("paused", json!(true));
                json_field("name", json!(label));
                json_field("previous_status", json!(prev));
            }
            None => {
                if let Some(n) = loop_name {
                    cli_println!("No loop named \"{}\" to pause.", n);
                    cli_println!("State file: {}", state_path.display());
                } else {
                    cli_println!("No active loop to pause.");
                }
            }
        }
        return Ok(());
    }

    // --- --resume: set loop status to "running" and spawn execution ---
    if parsed.resume {
        let state_path = state::loop_state_path(None, loop_name);
        match state::set_loop_status(None, loop_name, "running") {
            Some(prev) => {
                let label = loop_name.unwrap_or("default");
                if prev == "running" {
                    cli_println!("▶  Loop \"{}\" is already running.", label);
                    cli_println!("   State file: {}", state_path.display());
                    json_field("resumed", json!(true));
                    json_field("name", json!(label));
                    json_field("previous_status", json!(prev));
                } else {
                    cli_println!("▶  Loop \"{}\" resumed (was {}).", label, prev);

                    // Read the full loop state to reconstruct the command and
                    // spawn a detached background process that actually executes
                    // the iterations.
                    if let Some(ls) = state::read_loop_state(None, loop_name) {
                        // Reconstruct the CLI arguments for the loop command.
                        let mut cmd_args: Vec<String> = vec!["loop".to_string()];

                        // Add --name if named
                        if let Some(ref n) = parsed.name {
                            cmd_args.push("--name".to_string());
                            cmd_args.push(n.clone());
                        }

                        // Add mode flags
                        match ls.mode.as_str() {
                            "shell" => {
                                cmd_args.push("--shell".to_string());
                            }
                            "subcommand" => {
                                cmd_args.push("--".to_string());
                            }
                            _ => {} // plain mode — no flag
                        }

                        // Add task tokens
                        cmd_args.extend(ls.task_tokens.clone());

                        // Add scheduling flags
                        cmd_args.push("--interval".to_string());
                        cmd_args.push(ls.interval_secs.to_string());
                        if let Some(c) = ls.count {
                            cmd_args.push("--count".to_string());
                            cmd_args.push(c.to_string());
                        }
                        if let Some(t) = ls.timeout_secs {
                            cmd_args.push("--timeout".to_string());
                            cmd_args.push(t.to_string());
                        }

                        // Spawn a detached background process.
                        let exe = std::env::current_exe()
                            .map_err(|e| format!("Cannot determine CLI path: {}", e))?;

                        match spawn_detached(&exe, &cmd_args) {
                            Ok(child_id) => {
                                cli_println!(
                                    "   Spawned background process (PID: {}). Use --list to monitor, \
                                     --pause to pause, --stop to clear.",
                                    child_id,
                                );
                            }
                            Err(e) => {
                                eprintln!(
                                    "[WARN] Failed to spawn background process: {}. \
                                     Run the same loop command manually to start execution.",
                                    e,
                                );
                            }
                        }
                    }

                    cli_println!("   State file: {}", state_path.display());
                    json_field("resumed", json!(true));
                    json_field("name", json!(label));
                    json_field("previous_status", json!(prev));
                }
            }
            None => {
                if let Some(n) = loop_name {
                    cli_println!("No loop named \"{}\" to resume.", n);
                    cli_println!("State file: {}", state_path.display());
                } else {
                    cli_println!("No active loop to resume.");
                    cli_println!("State file: {}", state_path.display());
                    cli_println!("Start one with `browser4-cli loop <task>`.");
                }
            }
        }
        return Ok(());
    }

    // --- --pause-all: pause all running loops ---
    if parsed.pause_all {
        let entries = state::list_loop_states(None);
        let running: Vec<_> = entries.iter().filter(|e| e.status == "running").collect();
        if running.is_empty() {
            cli_println!("No running loops to pause.");
            if !entries.is_empty() {
                cli_println!("Use --list to see all loops.");
            }
            return Ok(());
        }
        let updated = state::set_all_loop_statuses_filtered(None, Some("running"), "paused");
        cli_println!("⏸  Paused {} running loop(s):", updated);
        for entry in &running {
            cli_println!("   - {}", entry.name);
        }
        cli_println!("\nUse `browser4-cli loop --resume-all` to resume all, or --resume --name <n> for a specific loop.");
        json_field("paused_all", json!(true));
        json_field("paused_count", json!(updated));
        json_field("loops", json!(running));
        return Ok(());
    }

    // --- --resume-all: resume all paused loops and spawn execution ---
    if parsed.resume_all {
        let entries = state::list_loop_states(None);
        let paused: Vec<_> = entries.iter().filter(|e| e.status == "paused").collect();
        if paused.is_empty() {
            cli_println!("No paused loops to resume.");
            if !entries.is_empty() {
                cli_println!("Use --list to see all loops.");
            }
            return Ok(());
        }
        let updated = state::set_all_loop_statuses_filtered(None, Some("paused"), "running");
        cli_println!("▶  Resumed {} paused loop(s):", updated);

        let exe = std::env::current_exe().unwrap_or_default();
        for entry in &paused {
            let name = if entry.name == "default" { None } else { Some(entry.name.as_str()) };
            if let Some(ls) = state::read_loop_state(None, name) {
                // Reconstruct CLI arguments
                let mut cmd_args: Vec<String> = vec!["loop".to_string()];
                if let Some(n) = name {
                    cmd_args.push("--name".to_string());
                    cmd_args.push(n.to_string());
                }
                match ls.mode.as_str() {
                    "shell" => { cmd_args.push("--shell".to_string()); }
                    "subcommand" => { cmd_args.push("--".to_string()); }
                    _ => {}
                }
                cmd_args.extend(ls.task_tokens.clone());
                cmd_args.push("--interval".to_string());
                cmd_args.push(ls.interval_secs.to_string());
                if let Some(c) = ls.count {
                    cmd_args.push("--count".to_string());
                    cmd_args.push(c.to_string());
                }
                if let Some(t) = ls.timeout_secs {
                    cmd_args.push("--timeout".to_string());
                    cmd_args.push(t.to_string());
                }

                match spawn_detached(&exe, &cmd_args) {
                    Ok(child_id) => {
                        cli_println!("   - {} (PID: {})", entry.name, child_id);
                    }
                    Err(e) => {
                        cli_println!("   - {} (failed to spawn: {})", entry.name, e);
                    }
                }
            }
        }
        json_field("resumed_all", json!(true));
        json_field("resumed_count", json!(updated));
        json_field("loops", json!(paused));
        return Ok(());
    }

    // Ensure the server is running before loop execution.
    // (Loop control-flow commands like --list / --stop return early above
    // without reaching here, so the server is only started when needed.)
    ensure_server_running(base_url).await?;

    // --- build the mode label ---
    let mode_label = if parsed.is_subcommand {
        "browser4-cli subcommand"
    } else if parsed.is_shell {
        "shell command"
    } else {
        "plain-text command"
    };
    let mode_key = if parsed.is_subcommand {
        "subcommand"
    } else if parsed.is_shell {
        "shell"
    } else {
        "plain"
    };

    // --- check for existing persisted loop state (resume) ---
    let mut iteration: u64 = 1;
    let overall_start: std::time::Instant;
    let started_at: String;

    if let Some(existing) = state::read_loop_state(None, loop_name) {
        if existing.status == "stopped" {
            // Previous loop was explicitly stopped — start fresh
            state::clear_loop_state(None, loop_name);
            started_at = Utc::now().to_rfc3339();
            overall_start = std::time::Instant::now();
        } else if existing.task_tokens == parsed.task_tokens && existing.mode == mode_key {
            // Same task, same mode — resume
            iteration = existing.iterations_completed + 1;
            cli_println!(
                "Resuming loop: \"{}\" from iteration {}",
                parsed.task_tokens.join(" "),
                iteration
            );
            started_at = existing.started_at;
            // Use the original started_at to calculate the overall elapsed time
            let started = chrono::DateTime::parse_from_rfc3339(&started_at)
                .map(|dt| dt.with_timezone(&Utc))
                .unwrap_or_else(|_| Utc::now());
            let elapsed = Utc::now()
                .signed_duration_since(started)
                .to_std()
                .unwrap_or_default();
            overall_start = std::time::Instant::now()
                .checked_sub(elapsed)
                .unwrap_or(std::time::Instant::now());
            if existing.status == "paused" {
                cli_println!("  Loop was paused — resuming now.");
            }
        } else {
            // Different task — warn and start fresh
            cli_println!(
                "Note: A different loop task was persisted. Starting fresh. \
                 Use --stop to clear the previous loop first if needed."
            );
            state::clear_loop_state(None, loop_name);
            started_at = Utc::now().to_rfc3339();
            overall_start = std::time::Instant::now();
        }
    } else {
        started_at = Utc::now().to_rfc3339();
        overall_start = std::time::Instant::now();
    }

    cli_println!(
        "Loop: \"{}\" — every {}s{}",
        parsed.task_tokens.join(" "),
        parsed.interval_secs,
        match (parsed.count, parsed.timeout_secs) {
            (Some(n), Some(t)) => format!(", up to {} iterations or {}s", n, t),
            (Some(n), None) => format!(", up to {} iterations", n),
            (None, Some(t)) => format!(", up to {}s", t),
            (None, None) => " (Ctrl+C to stop, --pause to pause)".to_string(),
        }
    );
    cli_println!("  Mode: {}", mode_label);
    if iteration > 1 {
        cli_println!("  Resumed at iteration: {}", iteration);
    }

    let mut results: Vec<Value> = Vec::new();

    // Persist initial state before the first iteration.
    // Track first write failure so we warn exactly once.
    let write_warned = std::cell::Cell::new(false);
    let persist = |task_tokens: &[String], mode: &str, interval: u64,
                    count: Option<u64>, timeout: Option<u64>,
                    completed: u64, started: &str, status: &str| {
        let state = state::LoopState {
            task_tokens: task_tokens.to_vec(),
            mode: mode.to_string(),
            interval_secs: interval,
            count,
            timeout_secs: timeout,
            iterations_completed: completed,
            started_at: started.to_string(),
            updated_at: Utc::now().to_rfc3339(),
            status: status.to_string(),
        };
        if let Err(e) = state::write_loop_state(&state, None, loop_name) {
            if !write_warned.replace(true) {
                eprintln!(
                    "[WARN] Failed to persist loop state: {}. Progress will not survive a restart.",
                    e,
                );
            }
        }
    };

    persist(
        &parsed.task_tokens, mode_key, parsed.interval_secs,
        parsed.count, parsed.timeout_secs,
        iteration.saturating_sub(1), &started_at, "running",
    );

    loop {
        // --- check for external signals (stop / pause) ---
        if let Some(existing) = state::read_loop_state(None, loop_name) {
            if existing.status == "stopped" {
                cli_println!(
                    "\nStop signal detected. Halting after {} iteration(s).",
                    iteration.saturating_sub(1)
                );
                break;
            }
            if existing.status == "paused" {
                cli_println!(
                    "\n⏸  Pause signal detected after {} iteration(s). Waiting for resume...",
                    iteration.saturating_sub(1)
                );
                cli_println!(
                    "   Use `browser4-cli loop --resume{}` to continue, or --stop to clear.",
                    if let Some(ref n) = parsed.name {
                        format!(" --name {}", n)
                    } else {
                        String::new()
                    }
                );

                // Poll until resumed or stopped
                loop {
                    if let Some(current) = state::read_loop_state(None, loop_name) {
                        if current.status == "stopped" {
                            cli_println!("Stop signal detected while paused. Halting.");
                            // Break outer loop by using the stopped check at top
                            break;
                        }
                        if current.status == "running" {
                            cli_println!("▶  Resumed — continuing loop.\n");
                            break;
                        }
                    }
                    // Also check for Ctrl+C while paused
                    tokio::select! {
                        _ = tokio::time::sleep(std::time::Duration::from_secs(PAUSE_POLL_INTERVAL_SECS)) => {},
                        _ = tokio::signal::ctrl_c() => {
                            cli_println!(
                                "\n\nInterrupted while paused. {} iteration(s) completed.",
                                iteration.saturating_sub(1)
                            );
                            cli_println!(
                                "State remains paused. Use --resume to continue or --stop to clear."
                            );
                            json_field("iterations", json!(results));
                            json_field("total_iterations", json!(iteration.saturating_sub(1)));
                            json_field("status", json!("paused"));
                            return Ok(());
                        },
                    }
                }

                // Re-check status after the inner loop to handle "stopped"
                continue;
            }
        }

        // --- limit checks (at top so --count 0 returns immediately) ---
        if let Some(max) = parsed.count {
            if iteration > max {
                break;
            }
        }

        if let Some(timeout) = parsed.timeout_secs {
            if overall_start.elapsed().as_secs() >= timeout {
                cli_println!(
                    "\nTimeout reached ({}s). Stopping after {} iteration(s).",
                    timeout,
                    iteration.saturating_sub(1)
                );
                break;
            }
        }

        let iter_start = std::time::Instant::now();
        let timestamp = Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string();

        cli_println!(
            "\n--- Iteration {} [{}] ---",
            iteration,
            timestamp
        );

        // --- execute ---
        // Race the execution against Ctrl+C so the loop can persist progress
        // and exit cleanly instead of leaving a stale state file.
        let ctrl_c_fut = tokio::signal::ctrl_c();
        tokio::pin!(ctrl_c_fut);

        let exec_fut = async {
            if parsed.is_subcommand {
                run_browser4_cli(&parsed.task_tokens).await
            } else if parsed.is_shell {
                run_shell_command(&parsed.task_tokens.join(" "))
            } else {
                // Plain text command (or x-sql — server auto-detects)
                submit_plain_command(
                    client,
                    base_url,
                    &parsed.task_tokens.join(" "),
                    false, // sync — the loop itself provides the pacing
                )
                .await
            }
        };
        tokio::pin!(exec_fut);

        let result: Result<String, String> = tokio::select! {
            r = &mut exec_fut => r,
            _ = &mut ctrl_c_fut => {
                // Ctrl+C arrived mid-execution.  Persist the progress we have
                // (the current iteration did not complete) so the loop can be
                // resumed from the last finished iteration.
                persist(
                    &parsed.task_tokens, mode_key, parsed.interval_secs,
                    parsed.count, parsed.timeout_secs,
                    iteration.saturating_sub(1), &started_at, "running",
                );
                let state_path = state::loop_state_path(None, loop_name);
                cli_println!(
                    "\n\n⏸  Interrupted during iteration {} — {} iteration(s) completed.",
                    iteration,
                    iteration.saturating_sub(1),
                );
                cli_println!(
                    "   State saved to {}. Run the same command to resume, \
                     or `browser4-cli loop --stop` to clear.",
                    state_path.display(),
                );
                json_field("iterations", json!(results));
                json_field("total_iterations", json!(iteration.saturating_sub(1)));
                return Ok(());
            },
        };

        match &result {
            Ok(output) => {
                if output.is_empty() {
                    cli_println!("(empty)");
                } else {
                    cli_println!("{}", output);
                }
                results.push(json!({
                    "iteration": iteration,
                    "timestamp": timestamp,
                    "ok": true,
                    "output": output,
                }));
            }
            Err(err) => {
                eprintln!("[ERROR] Iteration {}: {}", iteration, err);
                results.push(json!({
                    "iteration": iteration,
                    "timestamp": timestamp,
                    "ok": false,
                    "error": err,
                }));
            }
        }

        // --- persist progress after each iteration ---
        persist(
            &parsed.task_tokens, mode_key, parsed.interval_secs,
            parsed.count, parsed.timeout_secs,
            iteration, &started_at, "running",
        );

        iteration += 1;

        // --- check count again so we don't print a separator after the last ---
        if let Some(max) = parsed.count {
            if iteration > max {
                break;
            }
        }

        // --- sleep (minus elapsed execution time) ---
        let elapsed = iter_start.elapsed();
        let interval = std::time::Duration::from_secs(parsed.interval_secs);
        if elapsed < interval {
            let mut remaining = interval - elapsed;

            // If a timeout is set, cap the sleep so we wake up in time to
            // honour the timeout at the top of the next iteration.
            if let Some(timeout) = parsed.timeout_secs {
                let budget = std::time::Duration::from_secs(timeout)
                    .saturating_sub(overall_start.elapsed());
                if budget < remaining {
                    remaining = budget;
                }
            }

            if remaining > std::time::Duration::ZERO {
                // Race: sleep vs ctrl+c vs pause signal
                // Use a polling loop so we can detect external pause/stop signals
                let poll_interval = std::time::Duration::from_secs(PAUSE_POLL_INTERVAL_SECS);
                let mut slept = std::time::Duration::ZERO;

                while slept < remaining {
                    let chunk = std::cmp::min(poll_interval, remaining - slept);
                    tokio::select! {
                        _ = tokio::time::sleep(chunk) => {
                            slept += chunk;
                        },
                        _ = tokio::signal::ctrl_c() => {
                            // Persist progress on Ctrl+C so it can be resumed
                            persist(
                                &parsed.task_tokens, mode_key, parsed.interval_secs,
                                parsed.count, parsed.timeout_secs,
                                iteration.saturating_sub(1), &started_at, "running",
                            );
                            let state_path = state::loop_state_path(None, loop_name);
                            cli_println!(
                                "\n\n⏸  Interrupted — {} of {} iteration(s) completed.",
                                iteration.saturating_sub(1),
                                parsed.count.map_or("∞".to_string(), |n| n.to_string()),
                            );
                            cli_println!(
                                "   State saved to {}. Run the same command to resume, \
                                 or `browser4-cli loop --stop` to clear.",
                                state_path.display(),
                            );
                            json_field("iterations", json!(results));
                            json_field("total_iterations", json!(iteration.saturating_sub(1)));
                            return Ok(());
                        },
                    }

                    // Check for external pause/stop signal during sleep
                    if let Some(current) = state::read_loop_state(None, loop_name) {
                        if current.status == "stopped" || current.status == "paused" {
                            // Persist and let the top-of-loop check handle it
                            persist(
                                &parsed.task_tokens, mode_key, parsed.interval_secs,
                                parsed.count, parsed.timeout_secs,
                                iteration.saturating_sub(1), &started_at, &current.status,
                            );
                            break; // exit the sleep loop, top-of-loop will handle
                        }
                    }
                }
            }
        }
    }

    // --- summary ---
    let total = iteration.saturating_sub(1);

    // Determine the exit reason for the history log.
    let exit_reason = {
        let count_reached = parsed.count.map_or(false, |max| total >= max);
        let timed_out = parsed.timeout_secs.map_or(false, |t| overall_start.elapsed().as_secs() >= t);
        if count_reached && !timed_out {
            "count-reached"
        } else if timed_out {
            "timeout"
        } else {
            "interrupted"
        }
    };

    cli_println!("\n========================================");
    cli_println!("✓  Loop finished — {} iteration(s) completed.", total);

    // Write a history entry so users can review past loop completions.
    let history_entry = state::LoopHistoryEntry {
        name: loop_name.unwrap_or("default").to_string(),
        task_tokens: parsed.task_tokens.clone(),
        mode: mode_key.to_string(),
        iterations_completed: total,
        exit_reason: exit_reason.to_string(),
        completed_at: Utc::now().to_rfc3339(),
    };
    let _ = state::write_loop_history(&history_entry, None);

    // Clear persisted state on normal completion — the summary was already
    // printed and re-running the same command starts a fresh loop anyway.
    // Unless --keep-state was specified.
    if !parsed.keep_state {
        state::clear_loop_state(None, loop_name);
    } else {
        // Persist final completed state for inspection
        persist(
            &parsed.task_tokens, mode_key, parsed.interval_secs,
            parsed.count, parsed.timeout_secs,
            total, &started_at, "completed",
        );
        let state_path = state::loop_state_path(None, loop_name);
        cli_println!("   State preserved at: {}", state_path.display());
    }

    json_field("iterations", json!(results));
    json_field("total_iterations", json!(total));
    json_field("exit_reason", json!(exit_reason));

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
    // Skip this check when the runtime was already present: Chrome hasn't
    // changed and running the check every time produces spurious warnings.
    if !runtime.reused_existing {
        if let Err(e) = ensure_chrome_available() {
            eprintln!("⚠  Chrome check failed: {e}");
        }
    }

    json_field("tag", json!(&runtime.tag));
    json_field("asset_name", json!(&runtime.asset_name));
    json_field(
        "install_dir",
        json!(runtime.install_dir.display().to_string()),
    );
    json_field("reused_existing", json!(runtime.reused_existing));
    json_field("source_url", json!(&runtime.download_url));

    // Unpack bundled skill files into the versioned installation directory.
    // The skills directory is at <install_dir>/skills/ alongside the runtime.
    if !runtime.reused_existing {
        let skills_dir = runtime.install_dir.join("skills");
        match skills::unpack_skills_to(&skills_dir) {
            Ok(n) => {
                eprintln!("📦 Unpacked {n} skill files to {}", skills_dir.display());
            }
            Err(e) => {
                eprintln!("⚠  Failed to unpack skill files: {e}");
            }
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// skills command handlers
// ---------------------------------------------------------------------------

fn handle_skills_list() -> Result<(), String> {
    let names = skills::list_skill_names();
    if names.is_empty() {
        cli_println!("No skills bundled.");
        return Ok(());
    }
    cli_println!("Bundled skills:");
    for name in &names {
        let file_count = skills::skill_files_for(name).len();
        cli_println!("  {} ({} files)", name, file_count);
    }
    Ok(())
}

fn handle_skills_get(tool_params: &Value) -> Result<(), String> {
    let all = tool_params
        .get("all")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let full = tool_params
        .get("full")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let name = tool_params
        .get("name")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty());

    if all {
        cli_println!("{}", skills::get_all_skills());
        return Ok(());
    }

    let skill_name = match name {
        Some(n) => n.to_string(),
        None => {
            // No name given — list available skills.
            return handle_skills_list();
        }
    };

    match skills::get_skill(&skill_name, full) {
        Some(content) => {
            cli_println!("{}", content);
            Ok(())
        }
        None => {
            let available = skills::list_skill_names();
            Err(format!(
                "Unknown skill '{}'. Available skills: {}",
                skill_name,
                available.join(", ")
            ))
        }
    }
}

fn handle_skills_path(tool_params: &Value) -> Result<(), String> {
    // Check BROWSER4_SKILLS_DIR first, then the current versioned install dir,
    // then the default skills directory.
    let skills_dir = if std::env::var("BROWSER4_SKILLS_DIR")
        .map(|v| !v.trim().is_empty())
        .unwrap_or(false)
    {
        skills::get_skills_dir()
    } else if let Some(tag) = daemon::read_current_tag() {
        let versioned = state::resolve_runtime_data_dir()
            .join("versions")
            .join(&tag)
            .join("skills");
        if versioned.is_dir() {
            versioned
        } else {
            skills::get_skills_dir()
        }
    } else {
        skills::get_skills_dir()
    };

    let name = tool_params
        .get("name")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty());

    match name {
        Some(n) => {
            cli_println!("{}", skills_dir.join(n).display());
        }
        None => {
            cli_println!("{}", skills_dir.display());
        }
    }
    Ok(())
}

/// Returns true when npm's output indicates browser4-cli was not installed
/// via npm, as opposed to a genuine uninstall failure.
fn npm_not_installed_message(msg: &str) -> bool {
    let lower = msg.to_ascii_lowercase();
    lower.contains("enoent")
        || lower.contains("not found")
        || lower.contains("not installed")
        || lower.contains("no such package")
        || lower.is_empty()
}

/// Returns true when cargo's output indicates browser4-cli was not installed
/// via cargo, as opposed to a genuine uninstall failure.
fn cargo_not_installed_message(msg: &str) -> bool {
    let lower = msg.to_ascii_lowercase();
    lower.contains("did not match any packages")
        || lower.contains("no packages found")
        || lower.contains("not found")
        || lower.is_empty()
}

/// Format the output lines for `handle_uninstall`.  Extracted as a pure function
/// so the branching logic can be unit-tested without network I/O.
fn format_uninstall_output(
    npm_removed: bool,
    npm_error: Option<&str>,
    cargo_removed: bool,
    cargo_error: Option<&str>,
    runtime_dir_removed: bool,
    runtime_dir: &str,
    cache_dir_removed: bool,
    cache_dir: &str,
    dry_run: bool,
) -> Vec<String> {
    let mut lines = Vec::new();

    if dry_run {
        lines.push("🔍 DRY RUN — no changes will be made.".to_string());
        lines.push(String::new());
    }

    // npm
    if dry_run {
        if npm_removed {
            lines.push("🔍 Would remove browser4-cli from npm global packages.".to_string());
        } else {
            lines.push("ℹ  npm not found or browser4-cli not installed via npm.".to_string());
        }
    } else if npm_removed {
        lines.push("✅ Removed browser4-cli from npm global packages.".to_string());
    } else if let Some(err) = npm_error {
        lines.push(format!("⚠  npm uninstall failed: {err}"));
    } else {
        lines.push("ℹ  npm not found or browser4-cli not installed via npm.".to_string());
    }

    // cargo
    if dry_run {
        if cargo_removed {
            lines.push("🔍 Would remove browser4-cli from cargo installs.".to_string());
        } else {
            lines.push("ℹ  cargo not found or browser4-cli not installed via cargo.".to_string());
        }
    } else if cargo_removed {
        lines.push("✅ Removed browser4-cli from cargo installs.".to_string());
    } else if let Some(err) = cargo_error {
        lines.push(format!("⚠  cargo uninstall failed: {err}"));
    } else {
        lines.push("ℹ  cargo not found or browser4-cli not installed via cargo.".to_string());
    }

    // runtime data dir
    if dry_run {
        if runtime_dir_removed {
            lines.push(format!(
                "🔍 Would remove runtime data directory: {runtime_dir}"
            ));
        } else {
            lines.push(format!(
                "ℹ  Runtime data directory not present: {runtime_dir}"
            ));
        }
    } else if runtime_dir_removed {
        lines.push(format!("✅ Removed runtime data directory: {runtime_dir}"));
    } else {
        lines.push(format!(
            "ℹ  Runtime data directory not present: {runtime_dir}"
        ));
    }

    // cache dir
    if dry_run {
        if cache_dir_removed {
            lines.push(format!(
                "🔍 Would remove runtime cache directory: {cache_dir}"
            ));
        } else {
            lines.push(format!(
                "ℹ  Runtime cache directory not present: {cache_dir}"
            ));
        }
    } else if cache_dir_removed {
        lines.push(format!("✅ Removed runtime cache directory: {cache_dir}"));
    } else {
        lines.push(format!(
            "ℹ  Runtime cache directory not present: {cache_dir}"
        ));
    }

    lines
}

/// Attempt to remove the running browser4-cli binary from disk.
///
/// On Unix, the file can be unlinked while the process is running — the
/// directory entry disappears immediately and the inode is freed when the
/// process exits.  On Windows the running executable is locked, so we
/// schedule a deferred deletion via a detached PowerShell script.
fn attempt_self_removal(exe_path: &std::path::Path) -> bool {
    #[cfg(windows)]
    {
        let exe_str = exe_path.display().to_string();

        // Use a PowerShell one-liner spawned through cmd.exe with
        // CREATE_NO_WINDOW so no console window flashes on screen.
        // The script sleeps briefly to let this process exit, then
        // deletes the binary and itself.
        let ps_script = format!(
            "Start-Sleep -Milliseconds 1500; \
             Remove-Item -Force -LiteralPath '{}' -ErrorAction SilentlyContinue; \
             Remove-Item -Force -LiteralPath $MyInvocation.MyCommand.Path -ErrorAction SilentlyContinue",
            exe_str.replace('\'', "''")
        );

        let tmp_dir = std::env::temp_dir();
        let script_path =
            tmp_dir.join(format!("browser4-cli-uninstall-{}.ps1", std::process::id()));

        if let Err(e) = std::fs::write(&script_path, &ps_script) {
            cli_println!("  Warning: Could not write cleanup script: {e}");
            return false;
        }

        match std::process::Command::new("powershell")
            .args([
                "-WindowStyle",
                "Hidden",
                "-NoProfile",
                "-NonInteractive",
                "-File",
            ])
            .arg(&script_path)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
        {
            Ok(_) => {
                cli_println!("  Binary scheduled for removal after exit.");
                true
            }
            Err(e) => {
                cli_println!("  Warning: Could not schedule binary removal: {e}");
                false
            }
        }
    }

    #[cfg(not(windows))]
    {
        match std::fs::remove_file(exe_path) {
            Ok(()) => {
                cli_println!("  Binary removed.");
                true
            }
            Err(e) => {
                cli_println!("  Warning: Could not remove binary: {e}");
                false
            }
        }
    }
}

async fn handle_uninstall(tool_params: &Value) -> Result<(), String> {
    use std::process::Command;

    eprintln!("🧹 Uninstalling browser4-cli ...");
    eprintln!();

    let dry_run = tool_params
        .get("dry_run")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    if dry_run {
        eprintln!("🔍 DRY RUN — no changes will be made.");
        eprintln!();
    }

    // Helper: run a subprocess with a wall-clock timeout.  Returns the
    // process output or a timeout/spawn error.
    //
    // stdout and stderr are drained on background threads to prevent
    // pipe-buffer deadlock: if the child process writes more than the
    // OS pipe buffer (~64 KB on Windows) and no one is reading, the
    // child blocks on write → wait_with_output never returns.
    fn run_with_timeout(
        mut cmd: Command,
        timeout_secs: u64,
    ) -> Result<std::process::Output, String> {
        let mut child = cmd.spawn().map_err(|e| e.to_string())?;

        // Take the pipe handles and drain them on background threads.
        let stdout_reader = child.stdout.take().map(|mut out| {
            std::thread::spawn(move || {
                let mut buf = Vec::new();
                let _ = std::io::Read::read_to_end(&mut out, &mut buf);
                buf
            })
        });
        let stderr_reader = child.stderr.take().map(|mut err| {
            std::thread::spawn(move || {
                let mut buf = Vec::new();
                let _ = std::io::Read::read_to_end(&mut err, &mut buf);
                buf
            })
        });

        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    let stdout = stdout_reader
                        .and_then(|h| h.join().ok())
                        .unwrap_or_default();
                    let stderr = stderr_reader
                        .and_then(|h| h.join().ok())
                        .unwrap_or_default();
                    return Ok(std::process::Output {
                        status,
                        stdout,
                        stderr,
                    });
                }
                Ok(None) => {
                    if std::time::Instant::now() >= deadline {
                        let _ = child.kill();
                        // Drain the pipe readers after killing so the
                        // threads don't linger.
                        let _stdout = stdout_reader.and_then(|h| h.join().ok());
                        let _stderr = stderr_reader.and_then(|h| h.join().ok());
                        return Err(format!("process did not complete within {timeout_secs}s"));
                    }
                    std::thread::sleep(std::time::Duration::from_millis(200));
                }
                Err(e) => return Err(e.to_string()),
            }
        }
    }

    // ── 1. npm global uninstall ──
    let (npm_removed, npm_error) = if dry_run {
        // Check whether npm would find the package, but don't remove anything.
        let installed = Command::new("npm")
            .args(["list", "-g", "browser4-cli", "--depth=0"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);
        (installed, None)
    } else {
        let mut cmd = Command::new("npm");
        cmd.args(["uninstall", "-g", "browser4-cli"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        match run_with_timeout(cmd, 60) {
            Ok(output) => {
                if output.status.success() {
                    (true, None)
                } else {
                    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
                    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
                    let msg = if stderr.is_empty() { stdout } else { stderr };
                    if npm_not_installed_message(&msg) {
                        (false, None)
                    } else {
                        (false, Some(msg))
                    }
                }
            }
            Err(_) => {
                // npm not on PATH or spawn failed — treat as not installed
                (false, None)
            }
        }
    };

    // ── 2. cargo uninstall ──
    let (cargo_removed, cargo_error) = if dry_run {
        // Check whether cargo would find the package, but don't remove anything.
        let installed = Command::new("cargo")
            .args(["install", "--list"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .output()
            .map(|o| {
                String::from_utf8_lossy(&o.stdout)
                    .lines()
                    .any(|l| l.contains("browser4-cli"))
            })
            .unwrap_or(false);
        (installed, None)
    } else {
        let mut cmd = Command::new("cargo");
        cmd.args(["uninstall", "browser4-cli"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        match run_with_timeout(cmd, 120) {
            Ok(output) => {
                if output.status.success() {
                    (true, None)
                } else {
                    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
                    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
                    let msg = if stderr.is_empty() { stdout } else { stderr };
                    if cargo_not_installed_message(&msg) {
                        (false, None)
                    } else {
                        (false, Some(msg))
                    }
                }
            }
            Err(_) => {
                // cargo not on PATH or spawn failed — treat as not installed
                (false, None)
            }
        }
    };

    // ── 3. Remove runtime data directory (with confirmation if it exists) ──
    let runtime_dir = state::resolve_runtime_data_dir();
    let runtime_dir_str = runtime_dir.display().to_string();
    let cache_dir = state::resolve_runtime_cache_dir();
    let cache_dir_str = cache_dir.display().to_string();

    let has_data = runtime_dir.exists();
    let has_cache = cache_dir.exists();

    let skip_confirm = tool_params
        .get("yes")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let (runtime_dir_removed, cache_dir_removed) = if has_data || has_cache {
        // In dry-run mode, skip the confirmation prompt — we're just
        // previewing what would happen.
        let confirmed = if dry_run || skip_confirm {
            true
        } else if !std::io::stdin().is_terminal() {
            // Stdin is not a terminal (piped or redirected, e.g. from
            // a CI runner or a non-interactive PowerShell session).
            // Blocking on read_line would hang indefinitely, so auto-deny
            // the removal and tell the caller to pass --yes explicitly.
            eprintln!();
            eprintln!("The following directories would be removed:");
            if has_data {
                eprintln!("  Runtime data:  {runtime_dir_str}");
            }
            if has_cache {
                eprintln!("  Runtime cache: {cache_dir_str}");
            }
            eprintln!();
            eprintln!("Non-interactive session — confirmation skipped (directories preserved).");
            eprintln!("Re-run with  browser4-cli uninstall --yes  to remove them.");
            false
        } else {
            eprintln!();
            eprintln!("The following directories will be removed:");
            if has_data {
                eprintln!("  Runtime data:  {runtime_dir_str}");
            }
            if has_cache {
                eprintln!("  Runtime cache: {cache_dir_str}");
            }
            eprintln!();
            eprintln!("Type 'yes' to confirm, anything else to skip:");

            let mut input = String::new();
            match std::io::stdin().read_line(&mut input) {
                Ok(_) if input.trim().eq_ignore_ascii_case("yes") => true,
                _ => false,
            }
        };

        if confirmed {
            if dry_run {
                (has_data, has_cache)
            } else {
                let removed_data = if has_data {
                    std::fs::remove_dir_all(&runtime_dir).is_ok()
                } else {
                    false
                };
                let removed_cache = if has_cache {
                    std::fs::remove_dir_all(&cache_dir).is_ok()
                } else {
                    false
                };
                (removed_data, removed_cache)
            }
        } else {
            eprintln!("Skipped directory removal.");
            (false, false)
        }
    } else {
        (false, false)
    };

    // ── Print output ──
    let lines = format_uninstall_output(
        npm_removed,
        npm_error.as_deref(),
        cargo_removed,
        cargo_error.as_deref(),
        runtime_dir_removed,
        &runtime_dir_str,
        cache_dir_removed,
        &cache_dir_str,
        dry_run,
    );
    for line in &lines {
        cli_println!("{}", line);
    }

    // ── JSON fields ──
    json_field("npm_removed", json!(npm_removed));
    if let Some(ref err) = npm_error {
        json_field("npm_error", json!(err));
    }
    json_field("cargo_removed", json!(cargo_removed));
    if let Some(ref err) = cargo_error {
        json_field("cargo_error", json!(err));
    }
    json_field("runtime_dir_removed", json!(runtime_dir_removed));
    json_field("runtime_dir", json!(&runtime_dir_str));
    json_field("cache_dir_removed", json!(cache_dir_removed));
    json_field("cache_dir", json!(&cache_dir_str));
    json_field("dry_run", json!(dry_run));

    if !npm_removed && !cargo_removed && npm_error.is_none() && cargo_error.is_none() {
        cli_println!();
        cli_println!("ℹ  browser4-cli was not found in npm or cargo global installs.");

        if dry_run {
            // Locate the running binary and report what would happen.
            if let Ok(exe_path) = std::env::current_exe() {
                cli_println!("   Running binary: {}", exe_path.display());
                let inside_repo = daemon::find_browser4_root()
                    .map(|root| exe_path.starts_with(&root))
                    .unwrap_or(false);
                if inside_repo {
                    cli_println!(
                        "   This is a development build inside a Browser4 repo — remove it from"
                    );
                    cli_println!("   your PATH or delete it manually after leaving the repo.");
                } else {
                    cli_println!("   🔍 Would attempt to remove the binary.");
                }
            } else {
                cli_println!("   Could not determine binary location. Remove it manually.");
            }
        } else {
            // Locate the running binary and attempt self-removal.
            if let Ok(exe_path) = std::env::current_exe() {
                cli_println!("   Running binary: {}", exe_path.display());

                // Check whether the binary lives inside a Browser4 repository
                // checkout (i.e. a local dev build that should not be auto-deleted).
                let inside_repo = daemon::find_browser4_root()
                    .map(|root| exe_path.starts_with(&root))
                    .unwrap_or(false);

                if inside_repo {
                    cli_println!(
                        "   This is a development build inside a Browser4 repo — remove it from"
                    );
                    cli_println!("   your PATH or delete it manually after leaving the repo.");
                } else {
                    cli_println!("   Attempting to remove the binary...");
                    let removed = attempt_self_removal(&exe_path);
                    json_field("binary_removed", json!(removed));
                    json_field("binary_path", json!(exe_path.display().to_string()));
                }
            } else {
                cli_println!("   Could not determine binary location. Remove it manually.");
            }
        }
    }

    cli_println!();
    if dry_run {
        cli_println!("🔍 dry run complete (no changes made).");
    } else {
        cli_println!("✅ uninstall complete.");
    }
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
    ]
}

/// Check whether `npm` is available on PATH.
fn is_npm_available() -> bool {
    std::process::Command::new("npm")
        .arg("--version")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

/// Upgrade the `browser4-cli` binary itself.
///
/// Strategy:
/// 1. If `npm` is on PATH, use `npm install -g browser4-cli` (fast, atomic, handles running binary).
/// 2. Otherwise, use the platform-specific install script from the README.
///    - Windows: PowerShell with `irm … | iex`
///    - Linux / macOS: `curl … | bash`
///
/// On Unix the running binary can be safely replaced in-place (the old inode
/// stays alive until this process exits).  On Windows the install script may
/// fail with a "file locked" error when the binary is running; the error is
/// surfaced and the runtime upgrade proceeds regardless.
///
/// All failures are non-fatal — a warning is printed and the runtime upgrade continues.
async fn upgrade_cli_binary(tag: Option<&str>) {
    // ── 1. npm (preferred) ──
    if is_npm_available() {
        eprintln!("Upgrading browser4-cli binary via npm...");

        let package_spec = match tag {
            Some(t) => {
                // Strip a leading 'v' so that `--tag v4.11.0` maps to `browser4-cli@4.11.0`.
                let version = t.strip_prefix('v').unwrap_or(t);
                format!("browser4-cli@{}", version)
            }
            None => "browser4-cli".to_string(),
        };

        let result = tokio::task::spawn_blocking(move || {
            std::process::Command::new("npm")
                .args(["install", "-g", &package_spec])
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .output()
        })
        .await;

        match result {
            Ok(Ok(output)) if output.status.success() => {
                cli_println!("✅ browser4-cli upgraded via npm.");
                return;
            }
            Ok(Ok(output)) => {
                let stderr = String::from_utf8_lossy(&output.stderr);
                eprintln!("⚠  npm upgrade failed: {}", stderr.trim());
                eprintln!("   Falling back to install script...");
            }
            Ok(Err(e)) => {
                eprintln!("⚠  npm command failed: {e}");
                eprintln!("   Falling back to install script...");
            }
            Err(e) => {
                eprintln!("⚠  npm spawn failed: {e}");
                eprintln!("   Falling back to install script...");
            }
        }
    }

    // ── 2. Platform-specific install script (fallback) ──
    eprintln!("Upgrading browser4-cli via install script...");

    #[cfg(windows)]
    {
        let result = tokio::task::spawn_blocking(|| {
            std::process::Command::new("powershell.exe")
                .args([
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    "irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex",
                ])
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .output()
        })
        .await;

        match result {
            Ok(Ok(output)) if output.status.success() => {
                cli_println!("✅ browser4-cli upgraded via install script.");
            }
            Ok(Ok(output)) => {
                let stderr = String::from_utf8_lossy(&output.stderr);
                let combined = if stderr.is_empty() {
                    String::from_utf8_lossy(&output.stdout).trim().to_string()
                } else {
                    stderr.trim().to_string()
                };
                eprintln!("⚠  Install script failed: {combined}");
                if combined.to_lowercase().contains("access")
                    || combined.to_lowercase().contains("denied")
                    || combined.to_lowercase().contains("locked")
                {
                    eprintln!("   The binary may be locked because browser4-cli is running.");
                    eprintln!("   Close all browser4-cli processes and run 'browser4-cli upgrade' again,");
                    eprintln!("   or install the latest version manually from https://browser4.io.");
                }
                eprintln!("   The runtime upgrade will still proceed.");
            }
            Ok(Err(e)) => {
                eprintln!("⚠  Failed to run install script: {e}");
                eprintln!("   The runtime upgrade will still proceed.");
            }
            Err(e) => {
                eprintln!("⚠  Failed to spawn PowerShell: {e}");
                eprintln!("   The runtime upgrade will still proceed.");
            }
        }
    }
    #[cfg(not(windows))]
    {
        let result = tokio::task::spawn_blocking(|| {
            std::process::Command::new("sh")
                .args([
                    "-c",
                    "curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash",
                ])
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .output()
        })
        .await;

        match result {
            Ok(Ok(output)) if output.status.success() => {
                cli_println!("✅ browser4-cli upgraded via install script.");
            }
            Ok(Ok(output)) => {
                let stderr = String::from_utf8_lossy(&output.stderr);
                let combined = if stderr.is_empty() {
                    String::from_utf8_lossy(&output.stdout).trim().to_string()
                } else {
                    stderr.trim().to_string()
                };
                eprintln!("⚠  Install script failed: {combined}");
                eprintln!("   The runtime upgrade will still proceed.");
            }
            Ok(Err(e)) => {
                // Missing curl / bash / sh on a minimal system
                let msg = format!("{e}");
                if msg.to_lowercase().contains("not found") {
                    eprintln!("⚠  Cannot run install script: curl and bash are required.");
                    eprintln!("   Install npm first, or run the install script manually.");
                } else {
                    eprintln!("⚠  Failed to run install script: {e}");
                }
                eprintln!("   The runtime upgrade will still proceed.");
            }
            Err(e) => {
                eprintln!("⚠  Failed to spawn shell: {e}");
                eprintln!("   The runtime upgrade will still proceed.");
            }
        }
    }
}

async fn handle_upgrade(tool_params: &Value) -> Result<(), String> {
    let tag = tool_params.get("tag").and_then(|value| value.as_str());
    let force = tool_params
        .get("force")
        .and_then(|value| value.as_bool())
        .unwrap_or(false);

    // ── 1. Upgrade the CLI binary itself ──
    // This is non-fatal: if it fails we still attempt the runtime upgrade.
    upgrade_cli_binary(tag).await;

    // ── 2. Upgrade the runtime ──

    // Snapshot the currently-installed tag *before* the upgrade so we can
    // detect the "same version re-downloaded via latest" case.
    let prev_tag = read_current_tag();

    eprintln!("Upgrading Browser4 runtime...");
    let mut runtime = install_browser4_runtime(tag, force).await?;

    // When no explicit tag was requested (`upgrade` → "latest"), the early-exit
    // fast-path in `install_browser4_runtime` does not fire, so `reused_existing`
    // is always `false` even if the resolved tag matches what is already installed.
    // Detect this case here and flip the flag so the output message is correct.
    if !force && !runtime.reused_existing && tag.is_none() {
        if prev_tag.as_deref() == Some(&runtime.tag) {
            runtime.reused_existing = true;
        }
    }

    let output = format_upgrade_output(&runtime, force);
    let was_upgraded = !runtime.reused_existing || force;
    for line in &output {
        cli_println!("{}", line);
    }
    if was_upgraded {
        eprintln!(
            "NOTE: Restart the server to use the new version: browser4-cli stop && browser4-cli open <url>"
        );
    }
    json_field("tag", json!(&runtime.tag));
    json_field("asset_name", json!(&runtime.asset_name));
    json_field(
        "install_dir",
        json!(runtime.install_dir.display().to_string()),
    );
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

async fn handle_doctor(client: &Client, base_url: &str, args: &HashMap<String, Value>) -> Result<(), String> {
    cli_println!("Browser4 Doctor");
    cli_println!("================");
    cli_println!("");

    let is_fix = args.get("fix").and_then(|v| v.as_bool()).unwrap_or(false);

    // ---- Auto-clean Stale Daemon Files ----
    cli_println!("-- Stale File Cleanup --");
    let cleaned = clean_stale_daemon_files();
    if cleaned > 0 {
        cli_println!("  Cleaned {} stale daemon file(s)", cleaned);
    } else {
        cli_println!("  No stale daemon files found");
    }
    json_field("stale_files_cleaned", json!(cleaned));

    // ---- CLI Build Info (always available) ----
    cli_println!("");
    cli_println!("-- CLI Build Info --");
    cli_println!("  CLI version: {}", VERSION);
    json_field("cli_version", json!(VERSION));

    if let Some(metadata) = daemon::read_installed_browser4_runtime_metadata() {
        cli_println!("  Installed runtime: {}", metadata.tag);
        cli_println!("  Installed at: {}", metadata.installed_at);
        json_field("installed_runtime", json!({
            "tag": &metadata.tag,
            "asset_name": &metadata.asset_name,
            "download_url": &metadata.download_url,
            "installed_at": &metadata.installed_at,
        }));
    } else {
        cli_println!("  Installed runtime: not installed (run 'browser4-cli install')");
        json_field("installed_runtime", json!(null));
    }

    // ---- Backend Build Info (conditional) ----
    cli_println!("");
    cli_println!("-- Backend Build Info --");
    let build_url = format!("{base_url}/api/system/build");
    match get_json(client, &build_url).await {
        Ok(build_info) => {
            if let Some(obj) = build_info.as_object() {
                for (key, value) in obj {
                    cli_println!("  {}: {}", key, value);
                }
            }
            json_field("backend_build", build_info);
        }
        Err(e) => {
            cli_println!("  (server not running or unreachable: {})", e);
            json_field("backend_build", json!(null));
        }
    }

    // ---- Backend Logs (conditional) ----
    let log_file = args.get("file").and_then(|v| v.as_str()).unwrap_or("pulsar");
    let log_lines: u32 = args.get("lines")
        .and_then(|v| v.as_str())
        .and_then(|v| v.parse().ok())
        .unwrap_or(50)
        .min(500);
    let log_filter = args.get("log_filter").and_then(|v| v.as_str()).unwrap_or("");

    cli_println!("");
    if log_filter.is_empty() {
        cli_println!("-- Backend Logs: {}.log (last {} lines) --", log_file, log_lines);
    } else {
        cli_println!("-- Backend Logs: {}.log (last {} lines, filter: \"{}\") --", log_file, log_lines, log_filter);
    }
    let log_url = format!("{base_url}/api/doctor/logs?file={}&lines={}&filter={}",
        log_file, log_lines,
        urlencoding::encode(log_filter));
    match get_json(client, &log_url).await {
        Ok(log_data) => {
            if let Some(entries) = log_data.get("lines").and_then(|v| v.as_array()) {
                for line in entries {
                    if let Some(text) = line.as_str() {
                        cli_println!("  {}", text);
                    }
                }
                if entries.is_empty() {
                    if log_filter.is_empty() {
                        cli_println!("  (log file empty or not found)");
                    } else {
                        cli_println!("  (no log lines matched filter \"{}\")", log_filter);
                    }
                }
            }
            json_field("backend_logs", log_data);
        }
        Err(e) => {
            cli_println!("  (server not running or log unavailable: {})", e);
            json_field("backend_logs", json!(null));
        }
    }

    // ---- Backend Metrics (conditional) ----
    let metric_filter = args.get("metric_filter").and_then(|v| v.as_str()).unwrap_or("");

    cli_println!("");
    if metric_filter.is_empty() {
        cli_println!("-- Backend Metrics --");
    } else {
        cli_println!("-- Backend Metrics (filter: \"{}\") --", metric_filter);
    }
    let metrics_url = if metric_filter.is_empty() {
        format!("{base_url}/api/doctor/metrics")
    } else {
        format!("{base_url}/api/doctor/metrics?filter={}", urlencoding::encode(metric_filter))
    };
    match get_json(client, &metrics_url).await {
        Ok(metrics) => {
            if let Some(gauges) = metrics.get("gauges").and_then(|v| v.as_object()) {
                for (key, value) in gauges {
                    if is_zero_value(value) {
                        continue;
                    }
                    cli_println!("  {}: {}", key, value);
                }
            }
            // Print meter summary counts
            if let Some(meters) = metrics.get("meters").and_then(|v| v.as_object()) {
                let mut shown_header = false;
                for (key, val) in meters {
                    if let Some(obj) = val.as_object() {
                        let count = obj.get("count").and_then(|v| v.as_u64()).unwrap_or(0);
                        let rate = obj.get("meanRate").and_then(|v| v.as_f64()).unwrap_or(0.0);
                        if count == 0 && rate == 0.0 {
                            continue;
                        }
                        if !shown_header {
                            cli_println!("  -- Meters --");
                            shown_header = true;
                        }
                        cli_println!("  {}: {} count, {:.2}/s avg", key, count, rate);
                    }
                }
            }
            json_field("backend_metrics", metrics);
        }
        Err(e) => {
            cli_println!("  (server not running or metrics unavailable: {})", e);
            json_field("backend_metrics", json!(null));
        }
    }

    // ---- Destructive Repairs (--fix) ----
    if is_fix {
        cli_println!("");
        cli_println!("-- Destructive Repairs (--fix) --");
        let fix_count = run_destructive_repairs();
        cli_println!("  Repairs applied: {}", fix_count);
        json_field("repairs_applied", json!(fix_count));
    } else {
        cli_println!("");
        cli_println!("💡 Tip: Run 'browser4-cli doctor --fix' to auto-repair common issues (reinstall Chrome, purge old state, clean temp files).");
    }

    Ok(())
}

/// GET a JSON endpoint and return the parsed Value.
async fn get_json(client: &Client, url: &str) -> Result<Value, String> {
    let response = client.get(url).send().await
        .map_err(|e| format!("HTTP request failed: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()));
    }
    response.json::<Value>().await
        .map_err(|e| format!("JSON parse failed: {e}"))
}

/// Returns true if a JSON value represents zero (0, 0.0, 0 as string, etc.).
fn is_zero_value(value: &Value) -> bool {
    match value {
        Value::Number(n) => {
            n.as_f64().map(|f| f == 0.0).unwrap_or(false)
        }
        Value::String(s) => {
            s.is_empty() || s == "0" || s == "0.0"
        }
        Value::Null => true,
        Value::Bool(b) => !*b,
        Value::Array(a) => a.is_empty(),
        Value::Object(o) => o.is_empty(),
    }
}

/// Clean up stale daemon files (PID files, socket files, lock files) from the
/// state directory that may have been left behind by previous runs.
/// Returns the number of files cleaned.
fn clean_stale_daemon_files() -> usize {
    let state_dir = state::resolve_default_state_dir();
    let mut cleaned = 0usize;

    // Patterns to clean: PID files, socket files, lock files, temp files
    let stale_patterns = [".pid", ".sock", ".lock", ".tmp"];
    let stale_extensions = ["pid", "sock", "lock", "tmp"];

    // Clean files in the state directory root
    if let Ok(entries) = std::fs::read_dir(&state_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() {
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    let should_clean = stale_patterns.iter().any(|p| name.contains(p))
                        || stale_extensions.iter().any(|ext| {
                            path.extension().and_then(|e| e.to_str()) == Some(ext)
                        });
                    if should_clean {
                        if std::fs::remove_file(&path).is_ok() {
                            cleaned += 1;
                        }
                    }
                }
            }
        }
    }

    // Clean dedicated sessions directory
    let sessions_dir = state_dir.join("sessions");
    if let Ok(entries) = std::fs::read_dir(&sessions_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() {
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if stale_patterns.iter().any(|p| name.contains(p)) {
                        if std::fs::remove_file(&path).is_ok() {
                            cleaned += 1;
                        }
                    }
                }
            }
        }
    }

    // Clean the tmp directory if it exists
    let tmp_dir = state_dir.join("tmp");
    if tmp_dir.exists() && tmp_dir.is_dir() {
        if let Ok(entries) = std::fs::read_dir(&tmp_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() {
                    if std::fs::remove_file(&path).is_ok() {
                        cleaned += 1;
                    }
                }
            }
        }
    }

    cleaned
}

/// Run destructive repairs when `--fix` is passed.
/// - Removes Chrome profile directories that may be corrupted
/// - Purges the runtime download cache
/// - Removes temp cli directories
/// Returns the number of repair actions applied.
fn run_destructive_repairs() -> usize {
    let mut repairs = 0usize;
    let runtime_data_dir = state::resolve_runtime_data_dir();
    let runtime_cache_dir = state::resolve_runtime_cache_dir();

    // 1. Clean stale daemon files (same as auto-clean)
    repairs += clean_stale_daemon_files();

    // 2. Clean temp/cli directory under runtime data dir
    let cli_tmp = runtime_data_dir.join("tmp").join("cli");
    if cli_tmp.exists() {
        if std::fs::remove_dir_all(&cli_tmp).is_ok() {
            repairs += 1;
        }
    }

    // 3. Clean downloads cache (frees disk space, forces fresh downloads)
    let downloads_dir = runtime_cache_dir.join("downloads");
    if downloads_dir.exists() {
        if std::fs::remove_dir_all(&downloads_dir).is_ok() {
            repairs += 1;
        }
    }

    // 4. Remove install lock directory (may be stale from interrupted installs)
    let install_lock = runtime_data_dir.join(".install.lock");
    if install_lock.exists() {
        if install_lock.is_dir() {
            let _ = std::fs::remove_dir_all(&install_lock);
        } else {
            let _ = std::fs::remove_file(&install_lock);
        }
        repairs += 1;
    }

    repairs
}

/// Handle `doctor log` subcommand:
/// - `doctor log` or `doctor log list` → list available log files
/// - `doctor log <name>` → show log file content
/// - `doctor log <name> --tail` → show last few lines
/// - `doctor log <name> grep <pattern>` → search log file with grep syntax
async fn handle_doctor_log(
    client: &Client,
    base_url: &str,
    args: &HashMap<String, Value>,
) -> Result<(), String> {
    let log_name = args.get("name").and_then(|v| v.as_str())
        .filter(|s| !s.is_empty() && *s != "list");
    let is_tail = args.get("tail").and_then(|v| v.as_bool()).unwrap_or(false);
    let grep_pattern = args.get("grep").and_then(|v| v.as_str()).map(String::from);

    // Mode: list log files (no name, or name == "list")
    if log_name.is_none() {
        cli_println!("Available Log Files");
        cli_println!("===================");
        cli_println!("");

        let list_url = format!("{base_url}/api/doctor/log-files");
        match get_json(client, &list_url).await {
            Ok(data) => {
                if let Some(files) = data.get("files").and_then(|v| v.as_array()) {
                    if files.is_empty() {
                        cli_println!("  (no log files found)");
                    } else {
                        cli_println!("  {:<25} {:>10}  {:>20}", "NAME", "SIZE", "LAST MODIFIED");
                        cli_println!("  {:<25} {:>10}  {:>20}", "----", "----", "-------------");
                        for file in files {
                            let name = file.get("nameWithoutExt").and_then(|v| v.as_str()).unwrap_or("?");
                            let size = file.get("sizeHuman").and_then(|v| v.as_str()).unwrap_or("?");
                            let modified_ms = file.get("lastModified").and_then(|v| v.as_i64()).unwrap_or(0);
                            let modified_str = if modified_ms > 0 {
                                let secs = modified_ms / 1000;
                                let datetime = chrono::DateTime::from_timestamp(secs, 0)
                                    .map(|dt| dt.format("%Y-%m-%d %H:%M:%S").to_string())
                                    .unwrap_or_else(|| "unknown".to_string());
                                datetime
                            } else {
                                "unknown".to_string()
                            };
                            cli_println!("  {:<25} {:>10}  {:>20}", name, size, modified_str);
                        }
                        cli_println!("");
                        cli_println!("  {} log file(s). Use 'doctor log <name>' to view a specific log.", files.len());
                    }
                }
                json_field("log_files", data);
            }
            Err(e) => {
                cli_println!("  (server not running or log listing unavailable: {})", e);
                json_field("log_files", json!(null));
            }
        }
        return Ok(());
    }

    let name = log_name.unwrap();
    let display_name = format!("{}.log", name);

    // Mode: grep — search log file content using grep syntax
    if let Some(pattern) = grep_pattern {
        let lines: u32 = args.get("lines")
            .and_then(|v| v.as_str())
            .and_then(|v| v.parse().ok())
            .unwrap_or(50000);
        let log_url = format!("{base_url}/api/doctor/logs?file={}&lines={}&filter=",
            name, lines.min(50000));
        let log_content = match get_json(client, &log_url).await {
            Ok(data) => {
                if let Some(entries) = data.get("lines").and_then(|v| v.as_array()) {
                    entries.iter()
                        .filter_map(|v| v.as_str())
                        .collect::<Vec<&str>>()
                        .join("\n")
                } else {
                    return Err(format!("Failed to read log file: {}", display_name));
                }
            }
            Err(e) => return Err(format!("Failed to fetch log file '{}': {}", display_name, e)),
        };

        // Build GrepOptions from args, using the grep pattern
        let mut grep_params = args.clone();
        grep_params.insert("pattern".to_string(), json!(pattern));
        let grep_params_value: Value = json!(grep_params);
        let grep_options = parse_grep_options(&grep_params_value)?;

        let (page, page_size, show_all) = parse_page_opts(&grep_params_value);
        cli_println!("--- doctor log {} grep \"{}\" ---", display_name, grep_options.pattern);
        run_grep_on_source(&log_content, &grep_options, &display_name, page, page_size, show_all)?;
        return Ok(());
    }

    // Mode: view log file (with or without --tail)
    let lines: u32 = args.get("lines")
        .and_then(|v| v.as_str())
        .and_then(|v| v.parse().ok())
        .unwrap_or(if is_tail { 200 } else { 50000 });
    let log_url = format!("{base_url}/api/doctor/logs?file={}&lines={}&filter=",
        name, lines.min(50000));

    cli_println!("--- doctor log {} ({}) ---", display_name,
        if is_tail { format!("last {} lines", lines) } else { "full".to_string() });
    cli_println!("");

    match get_json(client, &log_url).await {
        Ok(log_data) => {
            if let Some(entries) = log_data.get("lines").and_then(|v| v.as_array()) {
                if entries.is_empty() {
                    cli_println!("  (log file empty or not found)");
                } else {
                    for line in entries {
                        if let Some(text) = line.as_str() {
                            cli_println!("{}", text);
                        }
                    }
                    cli_println!("");
                    cli_println!("  {} line(s). Use 'doctor log {} grep <pattern>' to search, '--tail' for recent lines.", entries.len(), name);
                }
            }
            json_field("log_content", log_data);
        }
        Err(e) => {
            cli_println!("  (server not running or log unavailable: {})", e);
            json_field("log_content", json!(null));
        }
    }

    Ok(())
}

fn should_ensure_server_running(command: &str) -> bool {
    command != "close"
        && command != "close-all"
        && command != "kill-all"
        && command != "list"
        && command != "install"
        && command != "uninstall"
        && command != "upgrade"
        && command != "stop"
        && command != "status"
        && command != "doctor"
        && command != "doctor-log"
        && command != "agent-list"
        && command != "crawl-list"
        && command != "swarm-list"
        && command != "skills"
        && command != "skills-list"
        && command != "skills-get"
        && command != "skills-path"
        && command != "loop"
}

/// Commands that require a web page to already be loaded in the browser.
/// For these commands, the CLI will NOT auto-start the server — instead
/// it guides the user to run `open <url>` or `goto <url>` first.
fn is_page_dependent_command(command: &str) -> bool {
    matches!(command,
        // Interaction — needs elements on a page
        "click" | "dblclick" | "hover"
        | "type" | "fill" | "press" | "key" | "keydown" | "keyup"
        | "check" | "uncheck"
        | "mousemove" | "mousedown" | "mouseup" | "mousewheel"
        // Snapshot / screenshot / export — needs page content
        | "snapshot" | "snapshot-grep"
        | "screenshot" | "pdf"
        // HTML snapshot commands — need page content
        | "htmlsnapshot" | "htmlsnapshot-capture" | "htmlsnapshot-get"
        | "htmlsnapshot-get-all" | "htmlsnapshot-query" | "htmlsnapshot-export"
        | "htmlsnapshot-summary" | "htmlsnapshot-grep" | "htmlsnapshot-inspect"
        // Queries — need page content
        | "get" | "extract" | "summarize" | "eval"
        | "generate-locator"
        // Viewport management — needs a page
        | "scroll" | "resize"
        // Console — needs a page to intercept messages
        | "console"
        // Storage commands — need page origin context
        | "cookie-list" | "cookie-get" | "cookie-set" | "cookie-delete" | "cookie-clear"
        | "localstorage-list" | "localstorage-get" | "localstorage-set"
        | "localstorage-delete" | "localstorage-clear"
        | "sessionstorage-list" | "sessionstorage-get" | "sessionstorage-set"
        | "sessionstorage-delete" | "sessionstorage-clear"
        // Storage state — needs a page for save/load
        | "state-save" | "state-load" | "delete-data"
        // Wait — needs timing on a page
        | "wait"
    )
}

/// Validate that all required (non-optional) positional arguments are present
/// in the parsed argument map.  Catches malformed commands (e.g. `htmlsnapshot grep`
/// without a pattern) before the backend is started.
fn validate_required_args(cmd_def: &commands::CommandDef, parsed: &HashMap<String, Value>) -> Result<(), String> {
    for arg in cmd_def.args {
        if !arg.optional {
            let has_value = parsed
                .get(arg.name)
                .and_then(|v| v.as_str())
                .map_or(false, |s| !s.is_empty());
            if !has_value {
                // Build a concise usage line from the command definition
                let usage_args: Vec<String> = cmd_def
                    .args
                    .iter()
                    .map(|a| {
                        if a.optional {
                            format!("[{}]", a.name)
                        } else {
                            format!("<{}>", a.name)
                        }
                    })
                    .collect();
                return Err(format!(
                    "Missing required argument: <{}>.\nUsage: browser4-cli {} {}",
                    arg.name,
                    cmd_def.name,
                    usage_args.join(" ")
                ));
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/// Rewrite spaced prefixed subcommands like `swarm create` / `agent run`
/// to their internal kebab-case command forms.
fn rewrite_prefixed_command(args: &[String]) -> Option<Vec<String>> {
    let prefix = args.first().map(|s| s.as_str())?;
    let sub = args.get(1)?;
    // Do not rewrite when the second argument looks like a flag (e.g. --help)
    // rather than a subcommand name.
    if sub.starts_with('-') {
        return None;
    }
    // `htmlsnapshot get all` is a two-level subcommand — rewrite to the flat
    // `htmlsnapshot-get-all` form so the dispatch matches it correctly.
    if prefix == "htmlsnapshot" && sub == "get" {
        if let Some(inner) = args.get(2) {
            if inner == "all" {
                let mut rewritten = vec!["htmlsnapshot-get-all".to_string()];
                rewritten.extend(args[3..].iter().cloned());
                return Some(rewritten);
            }
        }
    }
    // "crawl" works standalone (crawl <url>) AND as a prefix (crawl list).
    // Only rewrite known crawl subcommands so positional URLs pass through.
    if prefix == "crawl" {
        let known_subs = ["status", "result", "cancel", "clear", "list"];
        if known_subs.contains(&sub.as_str()) {
            let mut rewritten = vec![format!("crawl-{}", sub)];
            rewritten.extend(args[2..].iter().cloned());
            return Some(rewritten);
        }
        return None;
    }
    // "doctor" works standalone (doctor) AND as a prefix (doctor log).
    if prefix == "doctor" {
        let known_subs = ["log"];
        if known_subs.contains(&sub.as_str()) {
            let rest: Vec<String> = args[2..].iter().cloned().collect();
            // Handle `doctor log <name> grep <pattern> [grep-options]`:
            // rewrite so that "grep" and its pattern become --grep <pattern>,
            // while the log name stays as the first positional arg.
            // Example: doctor log pulsar grep ERROR -i
            //      → doctor-log pulsar --grep ERROR -i
            if let Some(grep_pos) = rest.iter().position(|a| a == "grep") {
                let mut rewritten = vec!["doctor-log".to_string()];
                // Everything before "grep" (the log name)
                rewritten.extend(rest[..grep_pos].iter().cloned());
                // Replace "grep" with "--grep" and add the pattern
                rewritten.push("--grep".to_string());
                rewritten.extend(rest[grep_pos + 1..].iter().cloned());
                return Some(rewritten);
            }
            let mut rewritten = vec![format!("doctor-{}", sub)];
            rewritten.extend(args[2..].iter().cloned());
            return Some(rewritten);
        }
        return None;
    }
    let rewritten_command = match prefix {
        "swarm" => format!("swarm-{}", sub),
        "agent" => format!("agent-{}", sub),
        "htmlsnapshot" => format!("htmlsnapshot-{}", sub),
        "snapshot" => format!("snapshot-{}", sub),
        "skills" => format!("skills-{}", sub),
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
        "agent-list" => Some("agent list"),
        "swarm-create" => Some("swarm create"),
        "swarm-submit" => Some("swarm submit"),
        "swarm-query" => Some("swarm query"),
        "swarm-status" => Some("swarm status"),
        "swarm-result" => Some("swarm result"),
        "swarm-list" => Some("swarm list"),
        "swarm-close" => Some("swarm close"),
        "crawl-status" => Some("crawl status"),
        "crawl-result" => Some("crawl result"),
        "crawl-cancel" => Some("crawl cancel"),
        "crawl-clear" => Some("crawl clear"),
        "crawl-list" => Some("crawl list"),
        "co-create" => Some("swarm create"),
        "co-submit" => Some("swarm submit"),
        "co-query" => Some("swarm query"),
        "co-status" => Some("swarm status"),
        "co-result" => Some("swarm result"),
        "htmlsnapshot-capture" => Some("htmlsnapshot capture"),
        "htmlsnapshot-get" => Some("htmlsnapshot get"),
        "htmlsnapshot-get-all" => Some("htmlsnapshot get all"),
        "htmlsnapshot-query" => Some("htmlsnapshot query"),
        "htmlsnapshot-export" => Some("htmlsnapshot export"),
        "htmlsnapshot-summary" => Some("htmlsnapshot summary"),
        "htmlsnapshot-grep" => Some("htmlsnapshot grep"),
        "htmlsnapshot-inspect" => Some("htmlsnapshot inspect"),
        "skills-list" => Some("skills list"),
        "skills-get" => Some("skills get"),
        "skills-path" => Some("skills path"),
        "doctor-log" => Some("doctor log"),
        _ => None,
    }
}

fn preferred_prefixed_group_form(command: &str) -> Option<&'static str> {
    match command {
        "agent" => Some("agent <subcommand>"),
        "swarm" => Some("swarm <subcommand>"),
        "co" => Some("swarm <subcommand>"),
        // `skills` is a valid standalone command (lists skills) as well as a
        // prefix for subcommands (skills list → skills-list), so it is
        // intentionally absent here.  Likewise `htmlsnapshot` and `crawl`.
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
            proxy_url: global.proxy_url.clone(),
            show_tip: global.show_tip,
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
    Pdf { path: PathBuf },
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
    pdf: Option<String>,
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

        let (nested_short_to_long, nested_bool_opts) = build_short_option_map(cmd_def.options);
        let raw_parsed = parse_raw_args(&effective_nested_global.args, Some(&nested_short_to_long), Some(&nested_bool_opts));
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
        let mut tool_params = (cmd_def.tool_params_fn)(&parsed);

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
            "eval" => {
                // When --stdin or --file is provided, read the expression.
                let use_stdin = tool_params
                    .get("stdin")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);

                // When --file or --stdin is used, migrate a positional ref
                // (mis-parsed as the expression) to the ref field.
                let has_batch_file = tool_params
                    .get("file")
                    .and_then(|v| v.as_str())
                    .map(|s| !s.trim().is_empty())
                    .unwrap_or(false);
                if use_stdin || has_batch_file {
                    let ref_empty = tool_params
                        .get("ref")
                        .and_then(|v| v.as_str())
                        .map(|s| s.is_empty())
                        .unwrap_or(true);
                    if ref_empty {
                        if let Some(expr_val) = tool_params
                            .get("expression")
                            .and_then(|v| v.as_str())
                            .map(|s| s.to_string())
                        {
                            let looks_like_ref = expr_val.starts_with("e")
                                && expr_val[1..].chars().all(|c| c.is_ascii_digit())
                                || expr_val.starts_with("backend:")
                                || expr_val.starts_with('#')
                                || expr_val.starts_with('.')
                                || expr_val.starts_with('[');
                            if looks_like_ref {
                                if let Value::Object(ref mut m) = tool_params {
                                    m.insert("ref".to_string(), json!(expr_val));
                                    m.insert("expression".to_string(), json!(""));
                                }
                            }
                        }
                    }
                }

                if use_stdin {
                    let mut expression = String::new();
                    match std::io::stdin().read_to_string(&mut expression) {
                        Ok(_) => {
                            let expression = expression.trim().to_string();
                            if expression.is_empty() {
                                if push_batch_local_failure(
                                    &mut entries,
                                    spec,
                                    "Stdin was empty. Provide a non-empty JavaScript expression via stdin.".to_string(),
                                    bail,
                                ) {
                                    break;
                                }
                                continue;
                            }
                            if let Value::Object(ref mut m) = tool_params {
                                m.insert("expression".to_string(), json!(expression));
                            }
                        }
                        Err(e) => {
                            if push_batch_local_failure(
                                &mut entries,
                                spec,
                                format!("Failed to read JavaScript expression from stdin: {e}"),
                                bail,
                            ) {
                                break;
                            }
                            continue;
                        }
                    }
                }

                // When --file is provided, read the expression from the file.
                if !use_stdin {
                    if let Some(file_path) = tool_params
                        .get("file")
                        .and_then(|v| v.as_str())
                        .map(str::trim)
                        .filter(|v| !v.is_empty())
                    {
                        match std::fs::read_to_string(file_path) {
                            Ok(content) => {
                                let expression = content.trim().to_string();
                                if expression.is_empty() {
                                    if push_batch_local_failure(
                                        &mut entries,
                                        spec,
                                        format!(
                                            "Eval file '{}' is empty. Provide a non-empty JavaScript expression.",
                                            file_path
                                        ),
                                        bail,
                                    ) {
                                        break;
                                    }
                                    continue;
                                }
                                if let Value::Object(ref mut m) = tool_params {
                                    m.insert("expression".to_string(), json!(expression));
                                }
                            }
                            Err(e) => {
                                if push_batch_local_failure(
                                    &mut entries,
                                    spec,
                                    format!("Failed to read eval file '{}': {}", file_path, e),
                                    bail,
                                ) {
                                    break;
                                }
                                continue;
                            }
                        }
                    }
                }

                // Strip --file, --stdin, and --json keys so they aren't sent to the server
                // (they're CLI-side only — the content has already been read and
                // inserted as the "expression" parameter above; --json controls
                // local output formatting, not a server parameter).
                if let Value::Object(ref mut m) = tool_params {
                    m.remove("file");
                    m.remove("stdin");
                    m.remove("json");
                }

                // Validate that an expression is provided.
                let expression_empty = tool_params
                    .get("expression")
                    .and_then(|v| v.as_str())
                    .map(str::is_empty)
                    .unwrap_or(true);
                if expression_empty {
                    if push_batch_local_failure(
                        &mut entries,
                        spec,
                        "A JavaScript expression is required. Provide it as a positional argument, via --stdin, or via --file.".to_string(),
                        bail,
                    ) {
                        break;
                    }
                    continue;
                }

                let request_index = steps.len();
                steps.push(json!({
                    "op": "tool",
                    "command": spec.display,
                    "tool": tool_name,
                    "arguments": normalize_batch_step_args(&tool_params),
                }));
                entries.push(PlannedBatchEntry::Backend {
                    display: spec.display.clone(),
                    request_indices: vec![request_index],
                    outputs: vec![PlannedBatchOutput::Text],
                });

                let selector = tracked_selector(&tool_params);
                if let Some(selector) = selector {
                    active_selector = Some(selector.to_string());
                    final_state.active_selector = active_selector.clone();
                }
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
            "pdf" => {
                let filename = tool_params.get("filename").and_then(|value| value.as_str());
                let output_path = resolve_output_path(filename, "pdf", "pdf");
                let mut arguments = tool_params.clone();
                if let Value::Object(map) = &mut arguments {
                    map.remove("filename");
                }

                let request_index = steps.len();
                steps.push(json!({
                    "op": "pdf",
                    "command": spec.display,
                    "tool": tool_name,
                    "arguments": normalize_batch_step_args(&arguments),
                }));
                entries.push(PlannedBatchEntry::Backend {
                    display: spec.display.clone(),
                    request_indices: vec![request_index],
                    outputs: vec![PlannedBatchOutput::Pdf { path: output_path }],
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
            "list" | "close-all" | "kill-all" | "delete-data" | "install" | "uninstall"
            | "upgrade" | "agent-run" | "agent-status" | "agent-result" | "swarm-create"
            | "swarm-submit" | "swarm-query" | "swarm-status" | "swarm-result"
            | "agent-list" | "crawl-list" | "swarm-list"
            | "skills" | "skills-list" | "skills-get" | "skills-path" => {
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
            // Always print text results — even empty or "null" values carry
            // meaningful information (e.g. JS eval returning undefined/null).
            if let Some(text) = result.text.as_deref() {
                let trimmed = text.trim();
                if trimmed == "null" {
                    cli_println!("null");
                } else if trimmed.is_empty() {
                    cli_println!("\"\"");
                } else {
                    cli_println!("{}", text);
                }
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
        PlannedBatchOutput::Pdf { path } => {
            let encoded = result
                .pdf
                .as_deref()
                .ok_or_else(|| "Batch PDF response was missing PDF data.".to_string())?;
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(encoded.trim())
                .map_err(|e| format!("Failed to decode PDF: {e}"))?;
            save_binary(path, &bytes).map_err(|e| e.to_string())?;
            cli_println!("[PDF]({})", path.display());
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

async fn handle_batch(global: &args::GlobalFlags) -> Result<(), CliError> {
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
            return Err(CliError(
                ExitCode::Server,
                "Batch backend returned an empty payload. Check that Browser4 server and CLI versions are compatible."
                    .to_string(),
            ));
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
                        return Err(CliError(
                            ExitCode::Server,
                            format!(
                                "Batch backend response was missing command {} ({}).",
                                index + 1,
                                display
                            ),
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
        Err(CliError(
            ExitCode::BatchPartial,
            format!("{} batch command(s) failed.", failures.len()),
        ))
    }
}

#[tokio::main]
async fn main() {
    init_root_search_start_dir_from_startup();

    let raw_args: Vec<String> = std::env::args().skip(1).collect();
    let global = parse_global_flags(&raw_args);
    let json_mode = global.json;
    let (command, effective_global, from_spaced_prefix) = normalize_command_invocation(&global);

    if let Err(err) = run(&command, &effective_global, from_spaced_prefix).await {
        if json_mode {
            // Use println! directly -- cli_println! checks json_active()
            // which is true here (json_init was called inside run()),
            // and we MUST emit the JSON error envelope regardless.
            let error = serde_json::json!({
                "message": err.message(),
                "code": if err.code() == ExitCode::Usage { "USAGE_ERROR" }
                        else if err.code() == ExitCode::Session { "SESSION_ERROR" }
                        else { "COMMAND_FAILED" }
            });
            println!(
                "{}",
                json_envelope("error", &command, serde_json::json!({}), Some(error))
            );
        } else {
            eprintln!("{}", format_cli_error_output(err.message()));
        }
        std::process::exit(err.code() as i32);
    }
}

fn format_cli_error_output(error: &str) -> String {
    if error.contains('\n') {
        error.to_string()
    } else {
        format!("Error: {error}")
    }
}

/// Compute Levenshtein distance between two strings.
fn levenshtein_distance(a: &str, b: &str) -> usize {
    let a_chars: Vec<char> = a.chars().collect();
    let b_chars: Vec<char> = b.chars().collect();
    let m = a_chars.len();
    let n = b_chars.len();

    // Use two rows for memory efficiency
    let mut prev = (0..=n).collect::<Vec<usize>>();
    let mut curr = vec![0; n + 1];

    for i in 1..=m {
        curr[0] = i;
        for j in 1..=n {
            let cost = if a_chars[i - 1] == b_chars[j - 1] { 0 } else { 1 };
            curr[j] = (prev[j] + 1).min(curr[j - 1] + 1).min(prev[j - 1] + cost);
        }
        std::mem::swap(&mut prev, &mut curr);
    }
    prev[n]
}

/// Find commands similar to the given input, sorted by Levenshtein distance.
fn suggest_similar_commands(input: &str, max_distance: usize, max_suggestions: usize) -> Vec<String> {
    let cmd_map = commands_map();
    let mut candidates: Vec<(usize, String)> = cmd_map
        .keys()
        .map(|name| (levenshtein_distance(input, name), name.clone()))
        .filter(|(dist, _)| *dist <= max_distance)
        .collect();
    candidates.sort_by_key(|(dist, _)| *dist);
    candidates
        .into_iter()
        .take(max_suggestions)
        .map(|(_, name)| name)
        .collect()
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
) -> Result<(), CliError> {
    // Initialise JSON output accumulator when --json is active.
    if global.json {
        json_init();
    }
    // Initialise quiet mode when -q / --quiet is active.
    quiet_init(global.quiet);
    // Initialise show-tip mode when --show-tip / -tip is active.
    show_tip_init(global.show_tip);

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
                    return Err(CliError(
                        ExitCode::Usage,
                        format!(
                            "Unsupported command form: {}. Use 'browser4-cli help {}' instead.",
                            target, preferred
                        ),
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

    // When the user passes --help/-h after a command (e.g. `htmlsnapshot --help`),
    // print the help for that command instead of complaining about the form.
    if global.args.iter().any(|a| a == "--help" || a == "-h") {
        print_help(Some(command));
        return Ok(());
    }

    if !from_spaced_prefix {
        if let Some(preferred) = preferred_spaced_command_form(command) {
            return Err(CliError(
                ExitCode::Usage,
                format!(
                    "Unsupported command form: {}. Use 'browser4-cli {}' instead.",
                    command, preferred
                ),
            ));
        }
    }

    if let Some(preferred) = preferred_prefixed_group_form(command) {
        return Err(CliError(
            ExitCode::Usage,
            format!(
                "Unsupported command form: {}. Use 'browser4-cli {}' instead.",
                command, preferred
            ),
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

    // Forward --proxy to the download layer via env var so it can be
    // picked up by resolve_download_proxy() without threading through
    // every function signature.  Has highest priority over auto-detection.
    if let Some(ref proxy_url) = global.proxy_url {
        // Rust 2024 marks process-wide env mutation as unsafe.
        unsafe {
            std::env::set_var("BROWSER4_CLI_PROXY", proxy_url);
        }
    }

    // Look up the command definition — validate the command BEFORE starting
    // the backend so that non-existent commands and help requests don't
    // trigger an unnecessary server launch.
    let cmd_map = commands_map();
    let cmd_def = match cmd_map.get(command) {
        Some(def) => def,
        None => {
            let suggestions = suggest_similar_commands(command, 3, 5);
            if suggestions.is_empty() {
                eprintln!("Unknown command: '{}'", command);
            } else {
                eprintln!(
                    "Unknown command: '{}'. Did you mean: {}?",
                    command,
                    suggestions
                        .iter()
                        .map(|s| format!("'{}'", s))
                        .collect::<Vec<_>>()
                        .join(", ")
                );
            }
            print_help(None);
            return Ok(());
        }
    };

    // `act` is handled early — its description is variadic (multi-word),
    // so we join all remaining positionals before the standard arg parser
    // would reject them as "too many positional arguments".
    if command == "act" {
        let description = global.args.iter().skip(1)
            .skip_while(|s| *s == "--")
            .cloned()
            .collect::<Vec<_>>()
            .join(" ");
        if description.is_empty() {
            return Err(CliError(
                ExitCode::Usage,
                "A description is required. Usage: browser4-cli act \"<natural language description>\"".to_string()
            ));
        }
        // Ensure the server is running before dispatching to act
        ensure_server_running(&base_url).await?;
        let client = make_client();
        handle_act(&client, &base_url, &description).await?;
        return Ok(());
    }

    // Parse positional + named arguments BEFORE starting the backend, so
    // malformed/illegal commands fail fast without a 30 s server launch.
    let (short_to_long, bool_opts) = build_short_option_map(cmd_def.options);
    let raw_parsed = parse_raw_args(&global.args, Some(&short_to_long), Some(&bool_opts));
    let arg_names: Vec<&str> = cmd_def.args.iter().map(|a| a.name).collect();
    let parsed = build_command_args(&raw_parsed, &arg_names).map_err(|e| e.to_string())?;

    // Validate required positional arguments (fast-fail for malformed commands).
    validate_required_args(cmd_def, &parsed)?;

    // Resolve tool name and parameters
    let tool_name = (cmd_def.tool_name_fn)(&parsed);
    let mut tool_params = (cmd_def.tool_params_fn)(&parsed);

    // Early validation for grep commands — at least one of <pattern> or -e
    // must be provided, so catch the missing-pattern case before server start.
    if command == "htmlsnapshot-grep" || command == "snapshot-grep" {
        parse_grep_options(&tool_params)?;
    }

    // Ensure the Browser4 server is running (for relevant commands).
    // Page-dependent commands targeting localhost do NOT auto-start the server
    // — the user should run `open <url>` or `goto <url>` first so a page is
    // already loaded.  Remote servers (--server <url>) always proceed normally.
    if should_ensure_server_running(command) {
        let is_local = base_url.contains("localhost") || base_url.contains("127.0.0.1");
        if is_page_dependent_command(command) && is_local && !is_local_port_open(&base_url) {
            return Err(CliError(
                ExitCode::Session,
                format!(
                    "No active browser session. Run 'browser4-cli open <url>' or 'browser4-cli goto <url>' first to load a page, then try '{}' again.",
                    command
                ),
            ));
        }
        ensure_server_running(&base_url).await?;
    }

    let client = make_client();

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
        "attach" => {
            handle_attach(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
                &parsed,
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
        "skills" | "skills-list" => {
            handle_skills_list()?;
        }
        "skills-get" => {
            handle_skills_get(&tool_params)?;
        }
        "skills-path" => {
            handle_skills_path(&tool_params)?;
        }
        "uninstall" => {
            handle_uninstall(&tool_params).await?;
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
        "doctor" => {
            handle_doctor(&client, &base_url, &parsed).await?;
        }
        "doctor-log" => {
            handle_doctor_log(&client, &base_url, &parsed).await?;
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
        "extract" => {
            handle_extract(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "summarize" => {
            handle_summarize(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
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
        "pdf" => {
            handle_pdf(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "eval" => {
            // When --stdin is provided, read the expression from stdin.
            // This avoids shell quoting complexity for multi-line JS scripts.
            let use_stdin = tool_params
                .get("stdin")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);

            // When --file or --stdin or --base64 is used, the positional
            // argument may be a ref (e.g. e34) that was mis-parsed as the
            // expression. Move it to the ref field so the expression can be
            // replaced by the file/stdin/base64 content.
            let use_base64_arg = tool_params
                .get("base64")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let has_file = tool_params
                .get("file")
                .and_then(|v| v.as_str())
                .map(|s| !s.trim().is_empty())
                .unwrap_or(false);
            if use_stdin || use_base64_arg || has_file {
                let ref_empty = tool_params
                    .get("ref")
                    .and_then(|v| v.as_str())
                    .map(|s| s.is_empty())
                    .unwrap_or(true);
                if ref_empty {
                    if let Some(expr_val) = tool_params
                        .get("expression")
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string())
                    {
                        // A ref looks like: eNNN, backend:NNN, or a CSS selector
                        // (#id, .class, [attr]). JS expressions never start with
                        // these patterns.
                        let looks_like_ref = expr_val.starts_with("e")
                            && expr_val[1..].chars().all(|c| c.is_ascii_digit())
                            || expr_val.starts_with("backend:")
                            || expr_val.starts_with('#')
                            || expr_val.starts_with('.')
                            || expr_val.starts_with('[');
                        if looks_like_ref {
                            if let Value::Object(ref mut m) = tool_params {
                                m.insert("ref".to_string(), json!(expr_val));
                                m.insert("expression".to_string(), json!(""));
                            }
                        }
                    }
                }
            }

            if use_stdin {
                let mut expression = String::new();
                std::io::stdin()
                    .read_to_string(&mut expression)
                    .map_err(|e| format!("Failed to read JavaScript expression from stdin: {e}"))?;
                let expression = expression.trim().to_string();
                if expression.is_empty() {
                    return Err(CliError(
                        ExitCode::Usage,
                        "Stdin was empty. Provide a non-empty JavaScript expression via stdin.".to_string(),
                    ));
                }
                if let Value::Object(ref mut m) = tool_params {
                    m.insert("expression".to_string(), json!(expression));
                }
            }

            // When --base64 is provided, decode the expression from base64.
            // This avoids shell quoting issues on Windows for complex JavaScript.
            let use_base64 = tool_params
                .get("base64")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            if use_base64 {
                let encoded = tool_params
                    .get("expression")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string();
                if encoded.is_empty() {
                    return Err(CliError(
                        ExitCode::Usage,
                        "--base64 was set but the expression argument is empty.".to_string(),
                    ));
                }
                let decoded = base64::engine::general_purpose::STANDARD
                    .decode(&encoded)
                    .map_err(|e| format!("Failed to base64-decode eval expression: {e}"))?;
                let expression = String::from_utf8(decoded)
                    .map_err(|e| format!("Base64-decoded expression is not valid UTF-8: {e}"))?;
                if let Value::Object(ref mut m) = tool_params {
                    m.insert("expression".to_string(), json!(expression));
                }
            }

            // When --file is provided, read the expression from the file.
            if let Some(file_path) = tool_params
                .get("file")
                .and_then(|v| v.as_str())
                .map(str::trim)
                .filter(|v| !v.is_empty())
                {
                    // --stdin and --base64 take precedence; skip --file if they were already used.
                    if !use_stdin && !use_base64 {
                        let expression = std::fs::read_to_string(file_path)
                            .map_err(|e| format!("Failed to read eval file '{}': {}", file_path, e))?;
                        let expression = expression.trim().to_string();
                        if expression.is_empty() {
                            return Err(CliError(
                                ExitCode::Usage,
                                format!(
                                    "Eval file '{}' is empty. Provide a non-empty JavaScript expression.",
                                    file_path
                                ),
                            ));
                        }
                        if let Value::Object(ref mut m) = tool_params {
                            m.insert("expression".to_string(), json!(expression));
                        }
                    }
                }

            // Strip --file, --stdin, --base64, and --json keys so they aren't sent to the server
            // (they're CLI-side only — the content has already been read and
            // inserted as the "expression" parameter above; --json controls
            // local output formatting, not a server parameter).
            if let Value::Object(ref mut m) = tool_params {
                m.remove("file");
                m.remove("stdin");
                m.remove("base64");
                m.remove("json");
            }

            // Validate that an expression is provided (either positional, --stdin, or --file).
            let expression_empty = tool_params
                .get("expression")
                .and_then(|v| v.as_str())
                .map(str::is_empty)
                .unwrap_or(true);
            if expression_empty {
                return Err(CliError(
                    ExitCode::Usage,
                    "A JavaScript expression is required. Provide it as a positional argument, via --file, via --stdin, or via --base64."
                        .to_string(),
                ));
            }

            let eval_json = parsed
                .get("json")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            handle_tool_command_with_options(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                false,
                global.session_name.as_deref(),
                eval_json,
            )
            .await?;
        }
        "get" => {
            handle_get(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "type" => {
            // --focus flag: click the target element first so it is in an
            // interactive state before typing (needed for elements that
            // require a real click, not just programmatic focus).
            let focus = parsed
                .get("focus")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let has_ref = tool_params
                .get("ref")
                .and_then(|v| v.as_str())
                .map(|s| !s.is_empty())
                .unwrap_or(false);

            if focus {
                if !has_ref {
                    return Err(CliError(
                        ExitCode::Usage,
                        "--focus requires a target ref. Provide a CSS selector, element reference (e5), or --ref X.".to_string(),
                    ));
                }
                let ref_val = tool_params.get("ref").cloned().unwrap();
                let click_params = json!({ "ref": ref_val });
                handle_tool_command(
                    &client,
                    &base_url,
                    "browser_click",
                    &click_params,
                    false,
                    global.session_name.as_deref(),
                )
                .await?;
            }

            let verify = parsed
                .get("verify")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            handle_text_input_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
                verify,
            )
            .await?;
        }
        "fill" => {
            let verify = parsed
                .get("verify")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            handle_text_input_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
                verify,
            )
            .await?;
        }
        "press" => {
            let verify = parsed
                .get("verify")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let follow = parsed
                .get("follow")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            if follow {
                handle_navigation_action(
                    &client,
                    &base_url,
                    &tool_name,
                    &tool_params,
                    global.session_name.as_deref(),
                    true,
                )
                .await?;
            } else {
                handle_press_command(
                    &client,
                    &base_url,
                    &tool_name,
                    &tool_params,
                    global.session_name.as_deref(),
                    verify,
                )
                .await?;
            }
        }
        "select" => {
            let verify = parsed
                .get("verify")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            handle_select_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
                verify,
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
        "agent-list" => {
            handle_agent_list(&client, &base_url).await?;
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
        "swarm-query" => {
            handle_swarm_query(&client, &base_url, &tool_params).await?;
        }
        "swarm-status" => {
            handle_swarm_status(&client, &base_url, &tool_params).await?;
        }
        "swarm-result" => {
            handle_swarm_result(&client, &base_url, &tool_params).await?;
        }
        "swarm-list" => {
            handle_swarm_list(&tool_params).await?;
        }
        "swarm-close" => {
            handle_close(&client, &base_url, global.session_name.as_deref()).await?;
        }
        "crawl" => {
            handle_crawl(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "crawl-status" => {
            handle_crawl_status(&client, &base_url, &tool_params).await?;
        }
        "crawl-result" => {
            handle_crawl_result(&client, &base_url, &tool_params).await?;
        }
        "crawl-cancel" => {
            handle_crawl_cancel(&client, &base_url, &tool_params).await?;
        }
        "crawl-clear" => {
            handle_crawl_clear(&client, &base_url).await?;
        }
        "crawl-list" => {
            handle_crawl_list(&client, &base_url).await?;
        }
        "loop" => {
            handle_loop(&client, &base_url, global).await?;
        }
        "htmlsnapshot" | "htmlsnapshot-capture" => {
            handle_html_snapshot_capture(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "htmlsnapshot-get" => {
            handle_html_snapshot_get(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "htmlsnapshot-get-all" => {
            handle_html_snapshot_get(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "htmlsnapshot-query" => {
            handle_html_snapshot_query(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "htmlsnapshot-export" => {
            handle_html_snapshot_export(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "htmlsnapshot-summary" => {
            handle_html_snapshot_summary(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "htmlsnapshot-grep" => {
            let grep_options = parse_grep_options(&tool_params)?;
            handle_html_snapshot_grep(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
                &grep_options,
            )
            .await?;
        }
        "htmlsnapshot-inspect" => {
            handle_html_snapshot_inspect(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "snapshot-grep" => {
            let grep_options = parse_grep_options(&tool_params)?;
            handle_snapshot_grep(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
                &grep_options,
            )
            .await?;
        }
        "generate-locator" => {
            handle_generate_locator(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "click" | "dblclick" => {
            let follow = parsed
                .get("follow")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            handle_navigation_action(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                global.session_name.as_deref(),
                follow,
            )
            .await?;
        }
        "tab-list" => {
            handle_tab_list(&client, &base_url, global.session_name.as_deref()).await?;
        }
        "tab-new" => {
            handle_tab_new(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "tab-select" => {
            handle_tab_select(
                &client,
                &base_url,
                &tool_params,
                global.session_name.as_deref(),
            )
            .await?;
        }
        "cdp" => {
            // Support --json, --file, and --stdin for CDP params.
            // After resolving params, pass method and params to execute_cdp_command.
            let method = tool_params
                .get("method")
                .and_then(|v| v.as_str())
                .map(String::from)
                .unwrap_or_default();

            if method.is_empty() {
                return Err(CliError(
                    ExitCode::Usage,
                    "A CDP method name is required (e.g. 'cdp Page.captureScreenshot'). Use 'cdp --help' for details."
                        .to_string(),
                ));
            }

            // Resolve params from --json, --file, or --stdin
            let params_str = {
                let use_stdin = tool_params
                    .get("stdin")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);

                let from_json = tool_params
                    .get("json")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty());

                let from_file = tool_params
                    .get("file")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty());

                if use_stdin {
                    let mut input = String::new();
                    std::io::stdin()
                        .read_to_string(&mut input)
                        .map_err(|e| CliError(ExitCode::General, format!("Failed to read stdin: {}", e)))?;
                    Some(input.trim().to_string())
                } else if let Some(path) = from_file {
                    let file_path = std::path::Path::new(path);
                    if file_path.is_absolute() {
                        std::fs::read_to_string(file_path)
                            .map(|s| s.trim().to_string())
                            .map_err(|e| {
                                CliError(
                                    ExitCode::General,
                                    format!("Failed to read params file '{}': {}", path, e),
                                )
                            })?
                            .into()
                    } else {
                        let cwd = std::env::current_dir().map_err(|e| {
                            CliError(ExitCode::General, format!("Cannot determine current directory: {}", e))
                        })?;
                        let cwd_path = cwd.join(file_path);
                        std::fs::read_to_string(&cwd_path)
                            .map(|s| s.trim().to_string())
                            .map_err(|e| {
                                CliError(
                                    ExitCode::General,
                                    format!("Failed to read params file '{}': {}", cwd_path.display(), e),
                                )
                            })?
                            .into()
                    }
                } else if let Some(js) = from_json {
                    Some(js.to_string())
                } else {
                    None
                }
            };

            // Parse the params as JSON if provided
            let mut final_params = json!({ "method": method });
            if let Some(ref ps) = params_str {
                if !ps.is_empty() {
                    let parsed_json: Value = serde_json::from_str(ps).map_err(|e| {
                        CliError(
                            ExitCode::Usage,
                            format!("Invalid JSON for CDP params: {}\nParams must be a valid JSON object, e.g. '{{\"format\": \"jpeg\"}}'", e),
                        )
                    })?;
                    if let Value::Object(map) = parsed_json {
                        final_params["params"] = json!(map);
                    } else {
                        return Err(CliError(
                            ExitCode::Usage,
                            "CDP params must be a JSON object, e.g. '{\"format\": \"jpeg\"}'".to_string(),
                        ));
                    }
                }
            }

            handle_tool_command(
                &client,
                &base_url,
                &tool_name,
                &final_params,
                false,
                global.session_name.as_deref(),
            )
            .await?;
        }
        _ => {
            if tool_name.is_empty() {
                cli_println!("Command '{}' is not yet implemented.", command);
                return Ok(());
            }
            let result = handle_tool_command(
                &client,
                &base_url,
                &tool_name,
                &tool_params,
                matches!(command, "goto"),
                global.session_name.as_deref(),
            )
            .await;
            // When `wait --load networkidle` (or any wait_for_function tool
            // call) fails with a Java serialization error, suggest using a
            // fixed delay as a reliable fallback.
            if let Err(ref e) = result {
                if tool_name == "wait_for_function"
                    && (e.contains("java.time.Instant")
                        || e.contains("Jackson")
                        || e.contains("not supported by default"))
                {
                    return Err(CliError(
                        ExitCode::Server,
                        format!(
                            "{e}\n💡 Tip: The server failed to serialize the response (Java Jackson \
                             module issue). As a reliable fallback, use a fixed delay instead: \
                             `wait 3000` (or adjust the ms value to match your page)."
                        ),
                    ));
                }
            }
            result?;
        }
    }

    // Post-command snapshot for commands that modify browser state.
    // Users can opt out with --no-snapshot on individual interaction commands.
    let no_snap = no_snapshot_commands();
    let no_snap_flag = parsed
        .get("no-snapshot")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    if !no_snap.contains(command) && !no_snap_flag {
        let state = read_state(None, global.session_name.as_deref());
        if let Some(session_id) = state.session_id {
            post_command_snapshot(&client, &base_url, &session_id).await;
        }
    }

    // Emit JSON envelope when --json is active.
    // Use println! directly — cli_println! checks json_active() which is
    // true for the entire command lifetime, and we MUST emit the JSON
    // envelope regardless.  (Same pattern as the error envelope below.)
    if global.json {
        if let Some(fields) = json_finish() {
            println!(
                "{}",
                json_envelope("ok", command, serde_json::Value::Object(fields), None)
            );
        }
    }

    // Show a relevant tip on stderr to help AI agents discover advanced features.
    tips::show_tip(command);

    Ok(())
}

fn print_help(command_name: Option<&str>) {
    if let Some(name) = command_name {
        if name != "--help" {
            let cmd_map = commands_map();
            if let Some(cmd) = cmd_map.get(name) {
                cli_println!("{}", generate_command_help(cmd));
                return;
            }
            // Not an exact command — collect every command whose public
            // name starts with this prefix (e.g. "swarm" matches
            // "swarm create", "swarm submit", ...).
            let matching: Vec<&crate::commands::CommandDef> = cmd_map
                .values()
                .filter(|c| !c.hidden && public_command_name(c.name).starts_with(name))
                .collect();
            if !matching.is_empty() {
                let mut lines: Vec<String> = vec![format!("{} subcommands:\n", name)];
                for cmd in matching {
                    lines.push(generate_help_entry(cmd));
                }
                cli_println!("{}", lines.join("\n"));
                return;
            }
            // Try category alias — shows all commands in that category
            if let Some(canonical) = resolve_category_alias(name) {
                let cat_cmds = commands_in_category(canonical);
                if !cat_cmds.is_empty() {
                    // Find the display title for this category
                    let title = CATEGORY_TITLES
                        .iter()
                        .find(|(c, _)| *c == canonical)
                        .map(|(_, t)| *t)
                        .unwrap_or(canonical);
                    let mut lines: Vec<String> = vec![format!("\n{} commands:\n", title)];
                    for cmd in &cat_cmds {
                        lines.push(generate_help_entry(cmd));
                    }
                    cli_println!("{}", lines.join("\n"));
                    return;
                }
            }
            eprintln!("Unknown command: {}", name);
        }
    }
    cli_println!("{}", generate_help());
}

/// Format the result of wait-related tools into a user-friendly message.
/// Wait tools (wait_for_function, delay, etc.) return internal driver JSON
/// that is meaningless to users. This function maps them to readable messages.
fn format_wait_result(tool_name: &str, tool_params: &Value, result: &str) -> String {
    if tool_name == "wait_for_function" {
        "✓ Wait complete".to_string()
    } else if tool_name == "wait_for_page" {
        "✓ URL matched".to_string()
    } else if tool_name == "delay" {
        let millis = tool_params
            .get("millis")
            .and_then(|v| v.as_i64())
            .unwrap_or(0);
        format!("✓ Waited {}ms", millis)
    } else if tool_name == "wait_for_selector" {
        let selector = tool_params
            .get("selector")
            .and_then(|v| v.as_str())
            .unwrap_or("?");
        format!("✓ Element found: {}", selector)
    } else {
        result.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::Mutex;
    use tempfile::TempDir;

    /// Serialize tests that change the process-wide current directory so they
    /// don't race with each other or with other tests that read `current_dir`.
    static CWD_MUTEX: Mutex<()> = Mutex::new(());

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

    // -----------------------------------------------------------------------
    // resolve_cdp_endpoint tests
    // -----------------------------------------------------------------------

    #[test]
    fn resolve_cdp_endpoint_http_url_passes_through() {
        assert_eq!(
            resolve_cdp_endpoint("http://localhost:9222").unwrap(),
            "http://localhost:9222"
        );
        assert_eq!(
            resolve_cdp_endpoint("https://browser.example.com:9222").unwrap(),
            "https://browser.example.com:9222"
        );
    }

    #[test]
    fn resolve_cdp_endpoint_ws_url_converts_to_http() {
        assert_eq!(
            resolve_cdp_endpoint("ws://localhost:9222/devtools/browser").unwrap(),
            "http://localhost:9222"
        );
        assert_eq!(
            resolve_cdp_endpoint("ws://127.0.0.1:9223").unwrap(),
            "http://127.0.0.1:9223"
        );
    }

    #[test]
    fn resolve_cdp_endpoint_wss_url_converts_to_http() {
        assert_eq!(
            resolve_cdp_endpoint("wss://localhost:9222").unwrap(),
            "http://localhost:9222"
        );
        // ws without explicit port defaults to 9222
        assert_eq!(
            resolve_cdp_endpoint("ws://localhost").unwrap(),
            "http://localhost:9222"
        );
    }

    #[test]
    fn resolve_cdp_endpoint_bare_port() {
        assert_eq!(
            resolve_cdp_endpoint("9222").unwrap(),
            "http://localhost:9222"
        );
        assert_eq!(
            resolve_cdp_endpoint("9223").unwrap(),
            "http://localhost:9223"
        );
    }

    #[test]
    fn resolve_cdp_endpoint_host_port() {
        assert_eq!(
            resolve_cdp_endpoint("localhost:9222").unwrap(),
            "http://localhost:9222"
        );
        assert_eq!(
            resolve_cdp_endpoint("192.168.1.5:9222").unwrap(),
            "http://192.168.1.5:9222"
        );
    }

    #[test]
    fn resolve_cdp_endpoint_invalid_port() {
        assert!(resolve_cdp_endpoint("99999").is_err());
    }

    #[test]
    fn test_no_snapshot_commands_include_attach() {
        assert!(no_snapshot_commands().contains("attach"));
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
    fn no_snapshot_commands_include_doctor() {
        assert!(no_snapshot_commands().contains("doctor"));
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
    fn no_snapshot_commands_include_get() {
        assert!(no_snapshot_commands().contains("get"));
    }

    #[test]
    fn no_snapshot_commands_include_wait() {
        assert!(no_snapshot_commands().contains("wait"));
    }

    #[test]
    fn no_snapshot_commands_include_html_snapshot_variants() {
        assert!(no_snapshot_commands().contains("htmlsnapshot"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-capture"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-get"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-get-all"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-query"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-export"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-summary"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-grep"));
        assert!(no_snapshot_commands().contains("htmlsnapshot-inspect"));
    }

    #[test]
    fn no_snapshot_commands_include_generate_locator() {
        assert!(no_snapshot_commands().contains("generate-locator"));
    }

    #[test]
    fn no_snapshot_commands_include_scroll() {
        assert!(no_snapshot_commands().contains("scroll"));
    }

    #[test]
    fn no_snapshot_commands_include_resize() {
        assert!(no_snapshot_commands().contains("resize"));
    }

    #[test]
    fn no_snapshot_commands_include_status() {
        assert!(no_snapshot_commands().contains("status"));
    }

    #[test]
    fn no_snapshot_commands_include_stop() {
        assert!(no_snapshot_commands().contains("stop"));
    }

    #[test]
    fn no_snapshot_commands_include_list() {
        assert!(no_snapshot_commands().contains("list"));
    }

    #[test]
    fn resolve_storage_state_path_uses_current_directory() {
        // Serialize with other tests that modify the process-wide cwd so
        // they don't race and cause flaky failures.
        let _cwd_guard = CWD_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
        let tmp = test_temp_dir();
        let previous_dir = std::env::current_dir().unwrap();
        std::env::set_current_dir(tmp.path()).unwrap();
        let resolved = resolve_storage_state_path(Some("auth-state.json")).unwrap();
        std::env::set_current_dir(previous_dir).unwrap();

        assert_eq!(resolved, tmp.path().join("auth-state.json"));
    }

    #[test]
    fn resolve_storage_state_path_defaults_to_timestamped_json_in_current_directory() {
        let _cwd_guard = CWD_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
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
    fn build_open_session_capabilities_headed_true() {
        let caps = build_open_session_capabilities_with_test_mode(
            &json!({
                "headed": true,
            }),
            false,
        );

        assert_eq!(caps["headed"], json!(true));
    }

    #[test]
    fn build_open_session_capabilities_headless() {
        let caps = build_open_session_capabilities_with_test_mode(
            &json!({
                "headed": false,
            }),
            false,
        );

        assert_eq!(caps["headed"], json!(false));
    }

    #[test]
    fn build_open_session_capabilities_without_headed_does_not_set_key() {
        let caps = build_open_session_capabilities_with_test_mode(&json!({}), false);

        assert!(caps.get("headed").is_none());
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
            "Swarm create only supports --profile-mode SEQUENTIAL or --profile-mode TEMPORARY. Received: DEFAULT"
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
        assert!(message.contains("run `browser4-cli open <url>` to refresh the session"));
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
        assert!(!message.contains("run `browser4-cli open <url>` to refresh the session"));
        assert!(!message.contains("BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS"));
    }

    #[test]
    fn no_active_session_message_uses_structured_cli_format() {
        let message = no_active_session_message();

        assert!(message.contains("🔐 Session required"));
        assert!(message.contains("💡 What to try"));
        assert!(message.contains("run `browser4-cli open <url>` first."));
        assert!(message.contains("🧾 Details"));
        assert!(message.contains("No active session is currently stored"));
    }

    #[test]
    fn saved_session_expired_message_uses_refresh_guidance() {
        let message = saved_session_expired_message();

        assert!(message.contains("🔐 Session refresh needed"));
        assert!(message.contains("saved session expired or is no longer usable"));
        assert!(message
            .contains("run `browser4-cli open <url>` to create a fresh session, then retry."));
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
    fn should_not_ensure_server_for_doctor() {
        assert!(!should_ensure_server_running("doctor"));
    }

    #[test]
    fn should_ensure_server_for_open() {
        assert!(should_ensure_server_running("open"));
    }

    // -----------------------------------------------------------------------
    // is_page_dependent_command tests
    // -----------------------------------------------------------------------

    #[test]
    fn is_page_dependent_for_interaction_commands() {
        assert!(is_page_dependent_command("click"));
        assert!(is_page_dependent_command("dblclick"));
        assert!(is_page_dependent_command("hover"));
        assert!(is_page_dependent_command("type"));
        assert!(is_page_dependent_command("fill"));
        assert!(is_page_dependent_command("press"));
        assert!(is_page_dependent_command("check"));
        assert!(is_page_dependent_command("uncheck"));
    }

    #[test]
    fn is_page_dependent_for_snapshot_commands() {
        assert!(is_page_dependent_command("snapshot"));
        assert!(is_page_dependent_command("snapshot-grep"));
        assert!(is_page_dependent_command("screenshot"));
        assert!(is_page_dependent_command("pdf"));
    }

    #[test]
    fn is_page_dependent_for_htmlsnapshot_commands() {
        assert!(is_page_dependent_command("htmlsnapshot"));
        assert!(is_page_dependent_command("htmlsnapshot-capture"));
        assert!(is_page_dependent_command("htmlsnapshot-get"));
        assert!(is_page_dependent_command("htmlsnapshot-get-all"));
        assert!(is_page_dependent_command("htmlsnapshot-query"));
        assert!(is_page_dependent_command("htmlsnapshot-export"));
        assert!(is_page_dependent_command("htmlsnapshot-summary"));
        assert!(is_page_dependent_command("htmlsnapshot-grep"));
        assert!(is_page_dependent_command("htmlsnapshot-inspect"));
    }

    #[test]
    fn is_page_dependent_for_query_commands() {
        assert!(is_page_dependent_command("get"));
        assert!(is_page_dependent_command("extract"));
        assert!(is_page_dependent_command("summarize"));
        assert!(is_page_dependent_command("eval"));
        assert!(is_page_dependent_command("generate-locator"));
    }

    #[test]
    fn is_page_dependent_for_viewport_commands() {
        assert!(is_page_dependent_command("scroll"));
        assert!(is_page_dependent_command("resize"));
    }

    #[test]
    fn is_page_dependent_for_storage_commands() {
        assert!(is_page_dependent_command("cookie-list"));
        assert!(is_page_dependent_command("localstorage-get"));
        assert!(is_page_dependent_command("sessionstorage-clear"));
        assert!(is_page_dependent_command("state-save"));
        assert!(is_page_dependent_command("state-load"));
        assert!(is_page_dependent_command("delete-data"));
        assert!(is_page_dependent_command("wait"));
        assert!(is_page_dependent_command("console"));
    }

    #[test]
    fn is_not_page_dependent_for_navigation() {
        assert!(!is_page_dependent_command("open"));
        assert!(!is_page_dependent_command("goto"));
        assert!(!is_page_dependent_command("attach"));
    }

    #[test]
    fn is_not_page_dependent_for_management() {
        assert!(!is_page_dependent_command("close"));
        assert!(!is_page_dependent_command("close-all"));
        assert!(!is_page_dependent_command("list"));
        assert!(!is_page_dependent_command("stop"));
        assert!(!is_page_dependent_command("install"));
        assert!(!is_page_dependent_command("doctor"));
        assert!(!is_page_dependent_command("status"));
    }

    #[test]
    fn is_not_page_dependent_for_async() {
        assert!(!is_page_dependent_command("act"));
        assert!(!is_page_dependent_command("batch"));
        assert!(!is_page_dependent_command("crawl"));
        assert!(!is_page_dependent_command("swarm-create"));
        assert!(!is_page_dependent_command("agent-run"));
        assert!(!is_page_dependent_command("loop"));
    }

    #[test]
    fn is_not_page_dependent_for_skills() {
        assert!(!is_page_dependent_command("skills"));
        assert!(!is_page_dependent_command("skills-list"));
        assert!(!is_page_dependent_command("skills-get"));
        assert!(!is_page_dependent_command("skills-path"));
    }

    // -----------------------------------------------------------------------
    // validate_required_args tests
    // -----------------------------------------------------------------------

    #[test]
    fn validate_required_args_passes_when_optional_only() {
        let cmd_def = commands::CommandDef {
            name: "test-cmd",
            description: "",
            category: commands::Category::Core,
            hidden: false,
            batch_supported: false,
            args: &[commands::ArgDef { name: "x", description: "", optional: true }],
            options: &[],
            tool_name_fn: |_| "test".to_string(),
            tool_params_fn: |_| json!({}),
        };
        let parsed = HashMap::new(); // no args provided, but all are optional
        assert!(validate_required_args(&cmd_def, &parsed).is_ok());
    }

    #[test]
    fn validate_required_args_fails_when_required_missing() {
        let cmd_def = commands::CommandDef {
            name: "test-cmd",
            description: "",
            category: commands::Category::Core,
            hidden: false,
            batch_supported: false,
            args: &[commands::ArgDef { name: "url", description: "", optional: false }],
            options: &[],
            tool_name_fn: |_| "test".to_string(),
            tool_params_fn: |_| json!({}),
        };
        let parsed = HashMap::new(); // missing required "url"
        let err = validate_required_args(&cmd_def, &parsed).unwrap_err();
        assert!(err.contains("Missing required argument"));
        assert!(err.contains("url"));
    }

    #[test]
    fn validate_required_args_passes_when_required_provided() {
        let cmd_def = commands::CommandDef {
            name: "test-cmd",
            description: "",
            category: commands::Category::Core,
            hidden: false,
            batch_supported: false,
            args: &[commands::ArgDef { name: "url", description: "", optional: false }],
            options: &[],
            tool_name_fn: |_| "test".to_string(),
            tool_params_fn: |_| json!({}),
        };
        let mut parsed = HashMap::new();
        parsed.insert("url".to_string(), json!("https://example.com"));
        assert!(validate_required_args(&cmd_def, &parsed).is_ok());
    }

    #[test]
    fn validate_required_args_fails_when_required_empty_string() {
        let cmd_def = commands::CommandDef {
            name: "test-cmd",
            description: "",
            category: commands::Category::Core,
            hidden: false,
            batch_supported: false,
            args: &[commands::ArgDef { name: "url", description: "", optional: false }],
            options: &[],
            tool_name_fn: |_| "test".to_string(),
            tool_params_fn: |_| json!({}),
        };
        let mut parsed = HashMap::new();
        parsed.insert("url".to_string(), json!("")); // empty string
        let err = validate_required_args(&cmd_def, &parsed).unwrap_err();
        assert!(err.contains("Missing required argument"));
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
            proxy_url: None,
            show_tip: false,
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
            proxy_url: None,
            show_tip: false,
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
    fn rewrite_prefixed_command_handles_htmlsnapshot_get_all() {
        let rewritten = rewrite_prefixed_command(&[
            "htmlsnapshot".to_string(),
            "get".to_string(),
            "all".to_string(),
            "text".to_string(),
            ".product-title".to_string(),
        ])
        .unwrap();
        assert_eq!(rewritten[0], "htmlsnapshot-get-all");
        assert_eq!(rewritten[1], "text");
        assert_eq!(rewritten[2], ".product-title");
    }

    #[test]
    fn rewrite_prefixed_command_handles_htmlsnapshot_get_all_with_options() {
        let rewritten = rewrite_prefixed_command(&[
            "htmlsnapshot".to_string(),
            "get".to_string(),
            "all".to_string(),
            "text".to_string(),
            "a".to_string(),
            "--limit".to_string(),
            "5".to_string(),
            "--offset".to_string(),
            "10".to_string(),
        ])
        .unwrap();
        assert_eq!(rewritten[0], "htmlsnapshot-get-all");
        assert_eq!(rewritten[1], "text");
        assert_eq!(rewritten[2], "a");
        assert_eq!(rewritten[3], "--limit");
        assert_eq!(rewritten[4], "5");
        assert_eq!(rewritten[5], "--offset");
        assert_eq!(rewritten[6], "10");
    }

    #[test]
    fn rewrite_prefixed_command_does_not_rewrite_htmlsnapshot_get_when_not_all() {
        // `htmlsnapshot get text` should still rewrite to `htmlsnapshot-get text`
        let rewritten = rewrite_prefixed_command(&[
            "htmlsnapshot".to_string(),
            "get".to_string(),
            "text".to_string(),
            ".product-title".to_string(),
        ])
        .unwrap();
        assert_eq!(rewritten[0], "htmlsnapshot-get");
        assert_eq!(rewritten[1], "text");
        assert_eq!(rewritten[2], ".product-title");
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
        assert_eq!(
            preferred_spaced_command_form("htmlsnapshot-get-all"),
            Some("htmlsnapshot get all")
        );
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
        // `htmlsnapshot` is a valid standalone command — not just a prefix group.
        assert_eq!(preferred_prefixed_group_form("htmlsnapshot"), None);
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
        assert!(is_backend_unreachable_error(
            "Connection refused (os error 61)"
        ));
        assert!(is_backend_unreachable_error("connection refused"));
    }

    #[test]
    fn is_backend_unreachable_detects_tcp_errors() {
        assert!(is_backend_unreachable_error("tcp connect error"));
        assert!(is_backend_unreachable_error("error sending request"));
        assert!(is_backend_unreachable_error(
            "failed to connect to the server"
        ));
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
            download_url:
                "https://github.com/platonai/Browser4/releases/download/v4.10.0/bundle.tar.gz"
                    .to_string(),
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
        assert!(
            lines[0].contains("already installed"),
            "expected 'already installed', got: {:?}",
            lines
        );
        assert!(
            lines.iter().any(|l| l.contains("v4.10.0")),
            "expected tag in output"
        );
        assert!(
            lines.iter().any(|l| l.contains("- Install dir:")),
            "expected install dir"
        );
    }

    #[test]
    fn test_format_install_output_fresh() {
        let runtime = make_test_runtime(false);
        let lines = format_install_output(&runtime);
        assert!(
            lines[0].contains("installed successfully"),
            "expected 'installed successfully', got: {:?}",
            lines
        );
    }

    #[test]
    fn test_format_upgrade_output_already_latest() {
        let runtime = make_test_runtime(true);
        let lines = format_upgrade_output(&runtime, false);
        assert_eq!(lines.len(), 1);
        assert!(
            lines[0].contains("already at the latest version"),
            "got: {}",
            lines[0]
        );
        assert!(
            lines[0].contains("v4.10.0"),
            "expected tag in message: {}",
            lines[0]
        );
    }

    #[test]
    fn test_format_upgrade_output_fresh_install() {
        let runtime = make_test_runtime(false);
        let lines = format_upgrade_output(&runtime, false);
        assert!(
            lines[0].contains("upgraded successfully"),
            "got: {}",
            lines[0]
        );
        // The restart message is now emitted by handle_upgrade, not format_upgrade_output.
        assert!(!lines.iter().any(|l| l.contains("Restart the server")));
    }

    #[test]
    fn test_format_upgrade_output_force_reinstall() {
        let runtime = make_test_runtime(true); // reused_existing = true
        let lines = format_upgrade_output(&runtime, true); // force = true → not "already latest"
        assert!(
            lines[0].contains("upgraded successfully"),
            "force=true should not print 'already latest': {:?}",
            lines
        );
    }

    // -----------------------------------------------------------------------
    // parse_loop_args tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_parse_loop_args_basic_task() {
        let args: Vec<String> = vec!["loop", "load", "https://example.com"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.task_tokens, vec!["load", "https://example.com"]);
        assert!(!parsed.is_shell);
        assert!(!parsed.is_subcommand);
        assert_eq!(parsed.interval_secs, 3600); // default 1 hour
        assert_eq!(parsed.count, None);
        assert_eq!(parsed.timeout_secs, Some(604800)); // default 1 week
    }

    #[test]
    fn test_parse_loop_args_with_interval() {
        let args: Vec<String> = vec!["loop", "--interval", "5", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.interval_secs, 5);
        assert_eq!(parsed.task_tokens, vec!["task"]);
    }

    #[test]
    fn test_parse_loop_args_with_count() {
        let args: Vec<String> = vec!["loop", "--count", "3", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.count, Some(3));
    }

    #[test]
    fn test_parse_loop_args_with_timeout() {
        let args: Vec<String> = vec!["loop", "--timeout", "60", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.timeout_secs, Some(60));
    }

    #[test]
    fn test_parse_loop_args_shell_mode() {
        // Quoted shell command arrives as a single token from the shell.
        let args: Vec<String> = vec!["loop", "--shell", "curl -s https://example.com"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert!(parsed.is_shell);
        assert!(!parsed.is_subcommand);
        assert_eq!(
            parsed.task_tokens,
            vec!["curl -s https://example.com"]
        );
    }

    #[test]
    fn test_parse_loop_args_dash_dash_subcommand() {
        let args: Vec<String> = vec!["loop", "--", "eval", "document.title"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert!(parsed.is_subcommand);
        assert!(!parsed.is_shell);
        assert_eq!(parsed.task_tokens, vec!["eval", "document.title"]);
    }

    #[test]
    fn test_parse_loop_args_short_options() {
        let args: Vec<String> = vec!["loop", "-i", "5", "-n", "3", "-t", "30", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.interval_secs, 5);
        assert_eq!(parsed.count, Some(3));
        assert_eq!(parsed.timeout_secs, Some(30));
        assert_eq!(parsed.task_tokens, vec!["task"]);
    }

    #[test]
    fn test_parse_loop_args_equals_forms() {
        let args: Vec<String> = vec!["loop", "--interval=15", "--count=7", "--timeout=45", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.interval_secs, 15);
        assert_eq!(parsed.count, Some(7));
        assert_eq!(parsed.timeout_secs, Some(45));
    }

    #[test]
    fn test_parse_loop_args_stop_flag() {
        let args: Vec<String> = vec!["loop", "--stop"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert!(parsed.stop);
        assert!(!parsed.status);
        assert!(parsed.task_tokens.is_empty()); // --stop doesn't require a task
    }

    #[test]
    fn test_parse_loop_args_status_flag() {
        let args: Vec<String> = vec!["loop", "--status"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert!(parsed.status);
        assert!(!parsed.stop);
        assert!(parsed.task_tokens.is_empty()); // --status doesn't require a task
    }

    #[test]
    fn test_parse_loop_args_empty_task_error() {
        let args: Vec<String> = vec!["loop", "--interval", "5"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("task is required"));
    }

    #[test]
    fn test_parse_loop_args_shell_and_dash_dash_conflict() {
        let args: Vec<String> = vec!["loop", "--shell", "--", "cmd"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("mutually exclusive"));
    }

    #[test]
    fn test_parse_loop_args_invalid_interval() {
        let args: Vec<String> = vec!["loop", "--interval", "abc", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("Invalid value"));
    }

    #[test]
    fn test_parse_loop_args_invalid_count() {
        let args: Vec<String> = vec!["loop", "--count", "-1", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("Invalid value"));
    }

    #[test]
    fn test_parse_loop_args_unknown_option() {
        let args: Vec<String> = vec!["loop", "--unknown", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("Unknown option"));
    }

    #[test]
    fn test_parse_loop_args_count_flag_before_dash_dash() {
        let args: Vec<String> = vec!["loop", "--count", "2", "--", "eval", "x"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.count, Some(2));
        assert!(parsed.is_subcommand);
        assert_eq!(parsed.task_tokens, vec!["eval", "x"]);
    }

    #[test]
    fn test_parse_loop_args_count_zero() {
        let args: Vec<String> = vec!["loop", "--count", "0", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.count, Some(0));
    }

    #[test]
    fn test_parse_loop_args_status_with_task_error() {
        let args: Vec<String> = vec!["loop", "--status", "some-task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("cannot be combined with a task"));
    }

    #[test]
    fn test_parse_loop_args_stop_with_task_error() {
        let args: Vec<String> = vec!["loop", "--stop", "some-task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("cannot be combined with a task"));
    }

    #[test]
    fn test_parse_loop_args_list_flag() {
        let args: Vec<String> = vec!["loop", "--list"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert!(parsed.list);
        assert!(parsed.task_tokens.is_empty());
    }

    #[test]
    fn test_parse_loop_args_list_with_task_error() {
        let args: Vec<String> = vec!["loop", "--list", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("cannot be combined with a task"));
    }

    #[test]
    fn test_parse_loop_args_name_flag() {
        let args: Vec<String> = vec!["loop", "--name", "monitor", "--shell", "echo hi"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.name, Some("monitor".to_string()));
        assert!(parsed.is_shell);
        assert_eq!(parsed.task_tokens, vec!["echo hi"]);
    }

    #[test]
    fn test_parse_loop_args_name_equals_form() {
        let args: Vec<String> = vec!["loop", "--name=health-check", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.name, Some("health-check".to_string()));
        assert_eq!(parsed.task_tokens, vec!["task"]);
    }

    #[test]
    fn test_parse_loop_args_name_invalid_path_traversal() {
        let args: Vec<String> = vec!["loop", "--name", "../../etc/passwd", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("invalid path characters") || err.contains("Invalid loop name"));
    }

    #[test]
    fn test_parse_loop_args_name_invalid_special_chars() {
        let args: Vec<String> = vec!["loop", "--name", "my loop!", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("Invalid loop name"));
    }

    #[test]
    fn test_parse_loop_args_name_empty() {
        let args: Vec<String> = vec!["loop", "--name=", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("must not be empty"));
    }

    #[test]
    fn test_parse_loop_args_name_valid_chars() {
        let args: Vec<String> = vec!["loop", "--name", "my-monitor_v2.0", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert_eq!(parsed.name, Some("my-monitor_v2.0".to_string()));
    }

    #[test]
    fn test_parse_loop_args_pause_with_task() {
        // --pause + task = start paused
        let args: Vec<String> = vec!["loop", "--pause", "--shell", "echo hi"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert!(parsed.pause);
        assert!(parsed.is_shell);
        assert!(!parsed.task_tokens.is_empty());
    }

    #[test]
    fn test_parse_loop_args_resume_with_task_error() {
        // --resume cannot be combined with a task
        let args: Vec<String> = vec!["loop", "--resume", "task"]
            .into_iter()
            .map(String::from)
            .collect();
        let err = parse_loop_args(&args[1..]).unwrap_err();
        assert!(err.contains("cannot be combined with a task"));
    }

    #[test]
    fn test_parse_loop_args_pause_alone_no_existing_loop() {
        // --pause alone (control op) is valid — handle_loop will report "no active loop"
        let args: Vec<String> = vec!["loop", "--pause"]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_loop_args(&args[1..]).unwrap();
        assert!(parsed.pause);
        assert!(parsed.task_tokens.is_empty());
    }

    // -----------------------------------------------------------------------
    // format_duration tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_format_duration_zero() {
        assert_eq!(format_duration(0), "0s");
    }

    #[test]
    fn test_format_duration_seconds() {
        assert_eq!(format_duration(30), "30s");
    }

    #[test]
    fn test_format_duration_minutes() {
        assert_eq!(format_duration(90), "1m 30s");
    }

    #[test]
    fn test_format_duration_hours() {
        assert_eq!(format_duration(3600), "1h");
        assert_eq!(format_duration(3750), "1h 2m 30s");
    }

    #[test]
    fn test_format_duration_days() {
        assert_eq!(format_duration(90000), "1d 1h");
    }

    #[test]
    fn test_format_duration_weeks() {
        assert_eq!(format_duration(604800), "1w");
        assert_eq!(format_duration(691200), "1w 1d");
    }

    #[test]
    fn test_format_duration_exact_minute() {
        assert_eq!(format_duration(60), "1m");
    }

    #[test]
    fn test_format_duration_exact_hour() {
        assert_eq!(format_duration(7200), "2h");
    }

    // -----------------------------------------------------------------------
    // parse_grep_options tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_parse_grep_options_basic_pattern() {
        let opts = parse_grep_options(&json!({"pattern": "error"})).unwrap();
        assert_eq!(opts.pattern, "error");
        assert!(!opts.ignore_case);
        assert!(!opts.no_line_number);
        assert!(!opts.invert_match);
        assert!(!opts.count);
        assert!(!opts.files_with_matches);
        assert!(!opts.fixed_strings);
        assert!(!opts.word_regexp);
        assert_eq!(opts.after_context, None);
        assert_eq!(opts.before_context, None);
        assert_eq!(opts.context, None);
        assert_eq!(opts.selector, None);
    }

    #[test]
    fn test_parse_grep_options_missing_pattern() {
        let err = parse_grep_options(&json!({})).unwrap_err();
        assert!(err.contains("Pattern is required"));
    }

    #[test]
    fn test_parse_grep_options_empty_pattern() {
        let err = parse_grep_options(&json!({"pattern": ""})).unwrap_err();
        assert!(err.contains("Pattern is required"));
    }

    #[test]
    fn test_parse_grep_options_ignore_case() {
        let opts = parse_grep_options(&json!({"pattern": "err", "ignore-case": true})).unwrap();
        assert!(opts.ignore_case);
    }

    #[test]
    fn test_parse_grep_options_ignore_case_short_form() {
        // The short form -i gets resolved to ignore-case by build_command_args,
        // so parse_grep_options only sees the long form.
        let opts = parse_grep_options(&json!({"pattern": "err", "ignore-case": true})).unwrap();
        assert!(opts.ignore_case);
    }

    #[test]
    fn test_parse_grep_options_no_line_number() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "no-line-number": true})).unwrap();
        assert!(opts.no_line_number);
    }

    #[test]
    fn test_parse_grep_options_after_context() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "after-context": 3})).unwrap();
        assert_eq!(opts.after_context, Some(3));
    }

    #[test]
    fn test_parse_grep_options_before_context() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "before-context": 2})).unwrap();
        assert_eq!(opts.before_context, Some(2));
    }

    #[test]
    fn test_parse_grep_options_context() {
        let opts = parse_grep_options(&json!({"pattern": "err", "context": 5})).unwrap();
        assert_eq!(opts.context, Some(5));
    }

    #[test]
    fn test_parse_grep_options_context_string_value() {
        // Context options may arrive as strings from CLI parsing
        let opts = parse_grep_options(&json!({"pattern": "err", "context": "5"})).unwrap();
        assert_eq!(opts.context, Some(5));
    }

    #[test]
    fn test_parse_grep_options_invert_match() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "invert-match": true})).unwrap();
        assert!(opts.invert_match);
    }

    #[test]
    fn test_parse_grep_options_count() {
        let opts = parse_grep_options(&json!({"pattern": "err", "count": true})).unwrap();
        assert!(opts.count);
    }

    #[test]
    fn test_parse_grep_options_files_with_matches() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "files-with-matches": true})).unwrap();
        assert!(opts.files_with_matches);
    }

    #[test]
    fn test_parse_grep_options_fixed_strings() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "fixed-strings": true})).unwrap();
        assert!(opts.fixed_strings);
    }

    #[test]
    fn test_parse_grep_options_word_regexp() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "word-regexp": true})).unwrap();
        assert!(opts.word_regexp);
    }

    #[test]
    fn test_parse_grep_options_selector() {
        let opts =
            parse_grep_options(&json!({"pattern": "err", "selector": "main"})).unwrap();
        assert_eq!(opts.selector, Some("main".to_string()));
    }

    #[test]
    fn test_parse_grep_options_all_options_combined() {
        let opts = parse_grep_options(&json!({
            "pattern": "error",
            "ignore-case": true,
            "no-line-number": true,
            "after-context": 2,
            "before-context": 1,
            "context": 3,
            "invert-match": true,
            "count": false,
            "files-with-matches": false,
            "fixed-strings": true,
            "word-regexp": true,
            "selector": "body"
        }))
        .unwrap();
        assert_eq!(opts.pattern, "error");
        assert!(opts.ignore_case);
        assert!(opts.no_line_number);
        assert_eq!(opts.after_context, Some(2));
        assert_eq!(opts.before_context, Some(1));
        assert_eq!(opts.context, Some(3));
        assert!(opts.invert_match);
        assert!(!opts.count);
        assert!(!opts.files_with_matches);
        assert!(opts.fixed_strings);
        assert!(opts.word_regexp);
        assert_eq!(opts.selector, Some("body".to_string()));
    }

    #[test]
    fn test_parse_grep_options_defaults_all_false() {
        let opts = parse_grep_options(&json!({"pattern": "test"})).unwrap();
        // All boolean flags default to false
        assert!(!opts.ignore_case);
        assert!(!opts.no_line_number);
        assert!(!opts.invert_match);
        assert!(!opts.count);
        assert!(!opts.files_with_matches);
        assert!(!opts.fixed_strings);
        assert!(!opts.word_regexp);
        // All optional values default to None
        assert_eq!(opts.after_context, None);
        assert_eq!(opts.before_context, None);
        assert_eq!(opts.context, None);
        assert_eq!(opts.selector, None);
    }

    #[test]
    fn test_parse_grep_options_regex_special_chars() {
        let opts =
            parse_grep_options(&json!({"pattern": r"error|warning|panic", "ignore-case": true}))
                .unwrap();
        assert_eq!(opts.pattern, "error|warning|panic");
        assert!(opts.ignore_case);
    }

    // -----------------------------------------------------------------------
    // run_grep_on_source tests
    // -----------------------------------------------------------------------

    fn make_grep_opts(pattern: &str) -> GrepOptions {
        GrepOptions {
            pattern: pattern.to_string(),
            ..Default::default()
        }
    }

    #[test]
    fn test_run_grep_source_null_or_empty() {
        // null source should succeed with zero matches
        run_grep_on_source("null", &make_grep_opts("x"), "test", 1, 0, true).unwrap();
        // empty source should succeed with zero matches
        run_grep_on_source("", &make_grep_opts("x"), "test", 1, 0, true).unwrap();
    }

    #[test]
    fn test_run_grep_invalid_regex() {
        let result = run_grep_on_source(
            "some text",
            &GrepOptions {
                pattern: "[invalid".to_string(),
                ..Default::default()
            },
            "test",
            1, 0, true,
        );
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Invalid regex"));
    }

    #[test]
    fn test_run_grep_basic_match() {
        let source = "line one\nline two\nline three\n";
        let opts = make_grep_opts("two");
        // Since run_grep_on_source prints via cli_println! and json_field,
        // we test that it returns Ok for valid inputs.
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_fixed_strings_escapes_regex() {
        let source = "text with dots... and more";
        // "..." is a regex special pattern, but with -F it should match literally
        let opts = GrepOptions {
            pattern: "dots...".to_string(),
            fixed_strings: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_word_regexp() {
        let source = "word boundary test\nnotawordmatch\n";
        let opts = GrepOptions {
            pattern: "word".to_string(),
            word_regexp: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_count_mode() {
        let source = "apple\nbanana\napple pie\n";
        let opts = GrepOptions {
            pattern: "apple".to_string(),
            count: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_invert_match() {
        let source = "match this\nskip this\nmatch again\n";
        let opts = GrepOptions {
            pattern: "skip".to_string(),
            invert_match: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_files_with_matches() {
        let source = "content with match here\n";
        let opts = GrepOptions {
            pattern: "match".to_string(),
            files_with_matches: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test-label", 1, 0, true);
        assert!(result.is_ok());

        // No-match case
        let opts_no = GrepOptions {
            pattern: "nonexistent".to_string(),
            files_with_matches: true,
            ..Default::default()
        };
        let result_no = run_grep_on_source(source, &opts_no, "test-label", 1, 0, true);
        assert!(result_no.is_ok());
    }

    #[test]
    fn test_run_grep_context_lines() {
        let source = "line 1\nline 2\nline 3\nline 4\nline 5\n";
        let opts = GrepOptions {
            pattern: "line 3".to_string(),
            context: Some(1),
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_no_line_number() {
        let source = "line a\nline b\n";
        let opts = GrepOptions {
            pattern: "a".to_string(),
            no_line_number: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_case_insensitive() {
        let source = "UPPERCASE\n";
        let opts = GrepOptions {
            pattern: "uppercase".to_string(),
            ignore_case: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_no_matches_produces_empty_output() {
        let source = "nothing here\nreally nothing\n";
        let opts = make_grep_opts("absent");
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    // -----------------------------------------------------------------------
    // paginate_output and parse_page_opts tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_paginate_output_small_text_no_pagination() {
        let text = "short text";
        let (page, meta) = paginate_output(text, 1, 100);
        assert_eq!(page, "short text");
        assert!(!meta.is_truncated);
        assert_eq!(meta.total_lines, 1);
        assert_eq!(meta.total_pages, 1);
    }

    #[test]
    fn test_paginate_output_multiline_first_page() {
        let text: String = (0..250).map(|i| format!("line {}\n", i)).collect();
        // 250 lines, page_size = 100 → 3 pages
        let (page, meta) = paginate_output(&text, 1, 100);
        assert_eq!(page.lines().count(), 100);
        assert!(meta.is_truncated);
        assert_eq!(meta.total_lines, 250);
        assert_eq!(meta.total_pages, 3);
        assert_eq!(meta.current_page, 1);
    }

    #[test]
    fn test_paginate_output_multiline_second_page() {
        let text: String = (0..250).map(|i| format!("line {}\n", i)).collect();
        let (page, meta) = paginate_output(&text, 2, 100);
        assert_eq!(page.lines().count(), 100);
        assert_eq!(meta.current_page, 2);
    }

    #[test]
    fn test_paginate_output_last_page() {
        let text: String = (0..250).map(|i| format!("line {}\n", i)).collect();
        let (page, meta) = paginate_output(&text, 3, 100);
        // Last page: 50 lines remaining
        assert_eq!(page.lines().count(), 50);
        assert_eq!(meta.current_page, 3);
        assert_eq!(meta.total_pages, 3);
    }

    #[test]
    fn test_paginate_output_page_zero_defaults_to_one() {
        let text: String = (0..250).map(|i| format!("line {}\n", i)).collect();
        let (_page, meta) = paginate_output(&text, 0, 100);
        assert_eq!(meta.current_page, 1);
    }

    #[test]
    fn test_paginate_output_page_beyond_clamped() {
        let text: String = (0..150).map(|i| format!("line {}\n", i)).collect();
        let (page, meta) = paginate_output(&text, 5, 100);
        // Clamped to page 2 (150 lines, 100 lines/page)
        assert_eq!(meta.current_page, 2);
        assert_eq!(page.lines().count(), 50);
    }

    #[test]
    fn test_paginate_output_page_size_zero_shows_all() {
        let text: String = (0..500).map(|i| format!("line {}\n", i)).collect();
        let (page, meta) = paginate_output(&text, 1, 0);
        assert_eq!(page.lines().count(), 500);
        assert!(!meta.is_truncated);
        assert_eq!(meta.total_pages, 1);
    }

    #[test]
    fn test_parse_page_opts_defaults() {
        let params = json!({});
        let (page, page_size, show_all) = parse_page_opts(&params);
        assert_eq!(page, 1);
        assert_eq!(page_size, 2000);
        assert!(!show_all);
    }

    #[test]
    fn test_parse_page_opts_explicit() {
        let params = json!({"page": "3", "page-size": "50", "all": true});
        let (page, page_size, show_all) = parse_page_opts(&params);
        assert_eq!(page, 3);
        assert_eq!(page_size, 50);
        assert!(show_all);
    }

    #[test]
    fn test_format_pagination_footer() {
        let meta = PaginationMeta {
            total_chars: 25000,
            total_lines: 2500,
            current_page: 1,
            total_pages: 5,
            page_size: 500,
            is_truncated: true,
        };
        let footer = format_pagination_footer(&meta);
        assert!(footer.contains("Page 1/5"));
        assert!(footer.contains("500 lines of 2500 total"));
        assert!(footer.contains("--page N"));
        assert!(footer.contains("--all"));
    }

    #[test]
    fn test_skip_pagination_flags() {
        // Default: don't skip
        assert!(!skip_pagination(false));
        // --all: skip
        assert!(skip_pagination(true));
        // --json and --quiet are tested elsewhere (state is thread-local)
    }

    // -----------------------------------------------------------------------
    // Snapshot command arg handling tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_snapshot_viewport_renamed_to_viewports() {
        // Simulate what tool_params_fn does: viewport → viewports
        let mut args: HashMap<String, Value> = HashMap::new();
        args.insert("viewport".to_string(), json!("0,2,4"));

        // Replicate the tool_params_fn logic
        let mut p = serde_json::Map::new();
        if let Some(v) = args.get("viewport").and_then(|v| v.as_str()) {
            p.insert("viewports".to_string(), json!(v));
        }

        assert_eq!(p.get("viewports").and_then(|v| v.as_str()), Some("0,2,4"));
        assert!(p.get("viewport").is_none());
    }

    #[test]
    fn test_snapshot_tool_params_includes_raw_and_stdout() {
        let mut args: HashMap<String, Value> = HashMap::new();
        args.insert("stdout".to_string(), json!(true));

        // Replicate the tool_params_fn logic for stdout
        let mut p = serde_json::Map::new();
        if let Some(true) = args.get("stdout").and_then(|v| v.as_bool()) {
            p.insert("stdout".to_string(), json!(true));
        }

        assert_eq!(p.get("stdout").and_then(|v| v.as_bool()), Some(true));
    }

    #[test]
    fn test_snapshot_tool_params_viewport_with_range() {
        let mut args: HashMap<String, Value> = HashMap::new();
        args.insert("viewport".to_string(), json!("1-3"));

        let mut p = serde_json::Map::new();
        if let Some(v) = args.get("viewport").and_then(|v| v.as_str()) {
            p.insert("viewports".to_string(), json!(v));
        }

        assert_eq!(p.get("viewports").and_then(|v| v.as_str()), Some("1-3"));
    }

    // -----------------------------------------------------------------------
    // convert_alternation tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_convert_alternation_bare_pipe_unchanged() {
        // User already using correct Rust regex syntax — leave it alone.
        assert_eq!(convert_alternation("price|rating|stars"), "price|rating|stars");
        assert_eq!(convert_alternation("foo|bar"), "foo|bar");
    }

    #[test]
    fn test_convert_alternation_escaped_pipe_converted() {
        // grep BRE style \| → Rust regex |
        assert_eq!(
            convert_alternation(r"price\|rating\|stars"),
            "price|rating|stars"
        );
        assert_eq!(convert_alternation(r"error\|warning"), "error|warning");
    }

    #[test]
    fn test_convert_alternation_no_pipe_unchanged() {
        assert_eq!(convert_alternation("simple pattern"), "simple pattern");
        assert_eq!(convert_alternation(r"\d+\.\d+"), r"\d+\.\d+");
    }

    #[test]
    fn test_convert_alternation_escaped_pipe_in_middle() {
        // grep BRE alternation in the middle of a pattern.
        assert_eq!(convert_alternation(r"a\|b"), "a|b");
    }

    #[test]
    fn test_convert_alternation_single_pattern() {
        // Single word, no pipes at all.
        assert_eq!(convert_alternation("price"), "price");
    }

    // -----------------------------------------------------------------------
    // grep alternation tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_run_grep_alternation_bare_pipe() {
        // Correct Rust regex syntax: bare | = alternation
        let source = "apple\nbanana\ncherry\ndate\n";
        let opts = make_grep_opts("banana|cherry");
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_alternation_escaped_pipe_converted() {
        // grep BRE style \| is auto-converted to | (alternation)
        let source = "apple\nbanana\ncherry\ndate\n";
        let opts = make_grep_opts(r"banana\|cherry");
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_alternation_matches_multiple() {
        let source = "The price is $10\nRating: 4 stars\nNo match here\nPrice: $20\n";
        let opts = GrepOptions {
            pattern: "price|rating|stars".to_string(),
            ignore_case: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_extra_patterns_with_e_flag() {
        // Simulate -e price -e rating -e stars
        let source = "The price is $10\nRating: 4 stars\nNo match here\n";
        let opts = GrepOptions {
            pattern: String::new(),
            extra_patterns: vec![
                "price".to_string(),
                "rating".to_string(),
                "stars".to_string(),
            ],
            ignore_case: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_extra_patterns_combined_with_main() {
        // Main pattern + -e patterns should all match via alternation
        let source = "apple\nbanana\ncherry\ndate\nelderberry\n";
        let opts = GrepOptions {
            pattern: "apple".to_string(),
            extra_patterns: vec!["cherry".to_string(), "elderberry".to_string()],
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_run_grep_count_with_alternation() {
        let source = "price: $10\nrating: 4\nprice: $20\nother\n";
        let opts = GrepOptions {
            pattern: "price|rating".to_string(),
            count: true,
            ..Default::default()
        };
        let result = run_grep_on_source(source, &opts, "test", 1, 0, true);
        assert!(result.is_ok());
    }

    #[test]
    fn test_parse_grep_options_with_regexp_string() {
        let opts = parse_grep_options(&json!({
            "pattern": "main",
            "regexp": "extra"
        }))
        .unwrap();
        assert_eq!(opts.pattern, "main");
        assert_eq!(opts.extra_patterns, vec!["extra".to_string()]);
    }

    #[test]
    fn test_parse_grep_options_with_regexp_array() {
        let opts = parse_grep_options(&json!({
            "regexp": ["price", "rating", "stars"]
        }))
        .unwrap();
        assert_eq!(opts.pattern, "");
        assert_eq!(
            opts.extra_patterns,
            vec!["price".to_string(), "rating".to_string(), "stars".to_string()]
        );
    }

    #[test]
    fn test_parse_grep_options_no_pattern_no_regexp() {
        let result = parse_grep_options(&json!({}));
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Pattern is required"));
    }

    #[test]
    fn test_parse_grep_options_empty_pattern_with_regexp() {
        // Pattern is empty but -e patterns are provided — should succeed
        let opts = parse_grep_options(&json!({
            "pattern": "",
            "regexp": ["price", "rating"]
        }))
        .unwrap();
        assert_eq!(opts.pattern, "");
        assert_eq!(opts.extra_patterns.len(), 2);
    }

    // -----------------------------------------------------------------------
    // resolve_sql_file tests
    // -----------------------------------------------------------------------

    #[test]
    fn resolve_sql_file_absolute_path_succeeds() {
        let tmp = test_temp_dir();
        let file_path = tmp.path().join("query.sql");
        std::fs::write(&file_path, "SELECT 1").unwrap();

        let result = resolve_sql_file(&file_path.to_string_lossy());
        assert_eq!(result.unwrap(), "SELECT 1");
    }

    #[test]
    fn resolve_sql_file_absolute_path_not_found_shows_path() {
        let tmp = test_temp_dir();
        let file_path = tmp.path().join("nonexistent.sql");

        let result = resolve_sql_file(&file_path.to_string_lossy());
        let err = result.unwrap_err();
        let path_str = file_path.to_string_lossy();
        assert!(
            err.contains(path_str.as_ref()),
            "error should contain the resolved path, got: {err}"
        );
    }

    #[test]
    fn resolve_sql_file_cwd_relative_found() {
        let _cwd_guard = CWD_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
        let tmp = test_temp_dir();
        let sql_content = "SELECT * FROM test";
        std::fs::write(tmp.path().join("query.sql"), sql_content).unwrap();

        let previous_dir = std::env::current_dir().unwrap();
        std::env::set_current_dir(tmp.path()).unwrap();

        let result = resolve_sql_file("query.sql");
        std::env::set_current_dir(previous_dir).unwrap();

        assert_eq!(result.unwrap(), sql_content);
    }

    #[test]
    fn resolve_sql_file_finds_at_repo_root_from_subdirectory() {
        let _cwd_guard = CWD_MUTEX.lock().unwrap_or_else(|e| e.into_inner());

        // Set up a fake repo root with ROOT.md + pom.xml
        let repo_root = test_temp_dir();
        std::fs::write(repo_root.path().join("ROOT.md"), "").unwrap();
        std::fs::write(repo_root.path().join("pom.xml"), "").unwrap();
        let sql_content = "SELECT * FROM repo_root";
        std::fs::write(repo_root.path().join("query.sql"), sql_content).unwrap();

        // Set up a subdirectory (like cli/browser4-cli) as CWD with no query.sql
        let sub_dir = repo_root.path().join("cli").join("browser4-cli");
        std::fs::create_dir_all(&sub_dir).unwrap();

        let previous_dir = std::env::current_dir().unwrap();
        std::env::set_current_dir(&sub_dir).unwrap();

        // Repo root is tried first; file at repo root is found without ../../ prefix
        let result = resolve_sql_file("query.sql");
        std::env::set_current_dir(previous_dir).unwrap();

        assert_eq!(result.unwrap(), sql_content);
    }

    #[test]
    fn resolve_sql_file_not_found_shows_attempted_paths() {
        let _cwd_guard = CWD_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
        let tmp = test_temp_dir();
        // No query.sql exists, and tmp is not a browser4 root

        let previous_dir = std::env::current_dir().unwrap();
        std::env::set_current_dir(tmp.path()).unwrap();

        let result = resolve_sql_file("ghost.sql");
        std::env::set_current_dir(previous_dir).unwrap();

        let err = result.unwrap_err();
        let cwd_path = tmp.path().join("ghost.sql");
        let cwd_str = cwd_path.to_string_lossy();
        assert!(
            err.contains(cwd_str.as_ref()),
            "error should mention the CWD-resolved path, got: {err}"
        );
        assert!(
            err.contains("ghost.sql"),
            "error should mention the original filename, got: {err}"
        );
    }

    #[test]
    fn resolve_sql_file_prefers_repo_root_over_cwd_file() {
        // When the file exists in both CWD and repo root, repo root is preferred.
        // This matches the common workflow: SQL files at the repo root, CLI
        // invoked from cli/browser4-cli via cargo run. Use @./query.sql to
        // explicitly target a CWD-local file instead.
        let _cwd_guard = CWD_MUTEX.lock().unwrap_or_else(|e| e.into_inner());

        let repo_root = test_temp_dir();
        std::fs::write(repo_root.path().join("ROOT.md"), "").unwrap();
        std::fs::write(repo_root.path().join("pom.xml"), "").unwrap();
        std::fs::write(
            repo_root.path().join("query.sql"),
            "REPO ROOT VERSION",
        )
        .unwrap();

        let sub_dir = repo_root.path().join("cli").join("browser4-cli");
        std::fs::create_dir_all(&sub_dir).unwrap();
        std::fs::write(sub_dir.join("query.sql"), "CWD VERSION").unwrap();

        let previous_dir = std::env::current_dir().unwrap();
        std::env::set_current_dir(&sub_dir).unwrap();

        let result = resolve_sql_file("query.sql");
        std::env::set_current_dir(previous_dir).unwrap();

        // Repo root takes priority — this is the common case for @file paths
        assert_eq!(result.unwrap(), "REPO ROOT VERSION");
    }

    // -----------------------------------------------------------------------
    // maybe_decode_base64_sql tests
    // -----------------------------------------------------------------------

    #[test]
    fn decode_base64_sql_disabled_passthrough() {
        let params = json!({}); // no sqlBase64 key
        let result = maybe_decode_base64_sql("SELECT 1".to_string(), &params);
        assert_eq!(result.unwrap(), "SELECT 1");
    }

    #[test]
    fn decode_base64_sql_explicitly_false() {
        let params = json!({"sqlBase64": false});
        let result = maybe_decode_base64_sql("SELECT 1".to_string(), &params);
        assert_eq!(result.unwrap(), "SELECT 1");
    }

    #[test]
    fn decode_base64_sql_valid_decode() {
        use base64::Engine;
        let original = "SELECT DOM_FIRST_TEXT(DOM, 'h2') AS title\nFROM DOM_LOAD_AND_SELECT(@url, ':root')";
        let encoded = base64::engine::general_purpose::STANDARD.encode(original);

        let params = json!({"sqlBase64": true});
        let result = maybe_decode_base64_sql(encoded, &params);
        assert_eq!(result.unwrap(), original);
    }

    #[test]
    fn decode_base64_sql_valid_with_whitespace() {
        use base64::Engine;
        let original = "SELECT 1";
        let encoded = base64::engine::general_purpose::STANDARD.encode(original);
        let padded = format!("  {encoded}  \n");

        let params = json!({"sqlBase64": true});
        let result = maybe_decode_base64_sql(padded, &params);
        assert_eq!(result.unwrap(), original);
    }

    #[test]
    fn decode_base64_sql_empty_input_errors() {
        let params = json!({"sqlBase64": true});
        let result = maybe_decode_base64_sql("   ".to_string(), &params);
        assert!(
            result.unwrap_err().contains("empty"),
            "should error on empty/blank input"
        );
    }

    #[test]
    fn decode_base64_sql_invalid_base64_errors() {
        let params = json!({"sqlBase64": true});
        let result = maybe_decode_base64_sql("!!!not valid base64!!!".to_string(), &params);
        let err = result.unwrap_err();
        assert!(
            err.contains("base64"),
            "error should mention base64, got: {err}"
        );
    }

    #[test]
    fn decode_base64_sql_non_utf8_errors() {
        use base64::Engine;
        // 0xFE 0xFF = invalid UTF-8 (BOM-like but broken)
        let bytes = vec![0xFE, 0xFF, 0x00, 0x01];
        let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);

        let params = json!({"sqlBase64": true});
        let result = maybe_decode_base64_sql(encoded, &params);
        assert!(
            result.is_err(),
            "non-UTF-8 bytes should fail, got: {result:?}",
        );
    }

    // -----------------------------------------------------------------------
    // format_wait_result tests
    // -----------------------------------------------------------------------

    #[test]
    fn format_wait_result_wait_for_function() {
        let msg = format_wait_result(
            "wait_for_function",
            &json!({"pageFunction": "document.readyState === 'complete'"}),
            "{\"type\":\"Driver\"}",
        );
        assert_eq!(msg, "✓ Wait complete");
    }

    #[test]
    fn format_wait_result_wait_for_page() {
        let msg = format_wait_result(
            "wait_for_page",
            &json!({"url": "https://example.com/*"}),
            "{}",
        );
        assert_eq!(msg, "✓ URL matched");
    }

    #[test]
    fn format_wait_result_delay() {
        let msg = format_wait_result(
            "delay",
            &json!({"millis": 3000}),
            "",
        );
        assert_eq!(msg, "✓ Waited 3000ms");
    }

    #[test]
    fn format_wait_result_delay_default_millis() {
        let msg = format_wait_result(
            "delay",
            &json!({}),
            "",
        );
        assert_eq!(msg, "✓ Waited 0ms");
    }

    #[test]
    fn format_wait_result_wait_for_selector() {
        let msg = format_wait_result(
            "wait_for_selector",
            &json!({"selector": ".product-card"}),
            "",
        );
        assert_eq!(msg, "✓ Element found: .product-card");
    }

    #[test]
    fn format_wait_result_wait_for_selector_default() {
        let msg = format_wait_result(
            "wait_for_selector",
            &json!({}),
            "",
        );
        assert_eq!(msg, "✓ Element found: ?");
    }

    #[test]
    fn format_wait_result_non_wait_tool_unchanged() {
        let msg = format_wait_result(
            "browser_click",
            &json!({"ref": "e5"}),
            "clicked",
        );
        assert_eq!(msg, "clicked");
    }

    #[test]
    fn format_wait_result_empty_result_for_non_wait() {
        let msg = format_wait_result(
            "browser_snapshot",
            &json!({}),
            "",
        );
        assert_eq!(msg, "");
    }

    // -----------------------------------------------------------------------
    // Crawl command rewriting tests
    // -----------------------------------------------------------------------

    #[test]
    fn rewrite_prefixed_command_supports_crawl_status() {
        let rewritten = rewrite_prefixed_command(&[
            "crawl".to_string(),
            "status".to_string(),
            "task-id-123".to_string(),
        ])
        .unwrap();

        assert_eq!(rewritten[0], "crawl-status");
        assert_eq!(rewritten[1], "task-id-123");
    }

    #[test]
    fn rewrite_prefixed_command_supports_crawl_result() {
        let rewritten = rewrite_prefixed_command(&[
            "crawl".to_string(),
            "result".to_string(),
            "task-id-456".to_string(),
        ])
        .unwrap();

        assert_eq!(rewritten[0], "crawl-result");
        assert_eq!(rewritten[1], "task-id-456");
    }

    #[test]
    fn rewrite_prefixed_command_supports_crawl_cancel() {
        let rewritten = rewrite_prefixed_command(&[
            "crawl".to_string(),
            "cancel".to_string(),
            "task-id-789".to_string(),
        ])
        .unwrap();

        assert_eq!(rewritten[0], "crawl-cancel");
        assert_eq!(rewritten[1], "task-id-789");
    }

    #[test]
    fn rewrite_prefixed_command_supports_crawl_clear() {
        let rewritten = rewrite_prefixed_command(&[
            "crawl".to_string(),
            "clear".to_string(),
        ])
        .unwrap();

        assert_eq!(rewritten[0], "crawl-clear");
    }

    #[test]
    fn rewrite_prefixed_command_supports_crawl_list() {
        let rewritten = rewrite_prefixed_command(&[
            "crawl".to_string(),
            "list".to_string(),
        ])
        .unwrap();

        assert_eq!(rewritten[0], "crawl-list");
    }

    #[test]
    fn rewrite_prefixed_command_crawl_with_url_passes_through() {
        // crawl <url> should NOT be rewritten — it's a standalone crawl command
        let result = rewrite_prefixed_command(&[
            "crawl".to_string(),
            "https://example.com".to_string(),
        ]);

        assert!(result.is_none(), "crawl <url> should not be rewritten");
    }

    #[test]
    fn no_snapshot_commands_includes_crawl_status_and_result() {
        let cmds = no_snapshot_commands();
        assert!(cmds.contains("crawl-status"));
        assert!(cmds.contains("crawl-result"));
        assert!(cmds.contains("crawl-cancel"));
        assert!(cmds.contains("crawl-clear"));
        assert!(cmds.contains("crawl-list"));
    }

    #[test]
    fn crawl_command_not_rewritten_for_unknown_subcommand() {
        // crawl <unknown-sub> should pass through as-is (treated as positional URL)
        let result = rewrite_prefixed_command(&[
            "crawl".to_string(),
            "unknown-sub".to_string(),
        ]);
        assert!(result.is_none());
    }

    // -----------------------------------------------------------------------
    // preferred_spaced_command_form tests
    // -----------------------------------------------------------------------

    #[test]
    fn preferred_spaced_command_form_includes_crawl_status() {
        assert_eq!(
            preferred_spaced_command_form("crawl-status"),
            Some("crawl status")
        );
    }

    #[test]
    fn preferred_spaced_command_form_includes_crawl_result() {
        assert_eq!(
            preferred_spaced_command_form("crawl-result"),
            Some("crawl result")
        );
    }

    #[test]
    fn preferred_spaced_command_form_includes_crawl_cancel() {
        assert_eq!(
            preferred_spaced_command_form("crawl-cancel"),
            Some("crawl cancel")
        );
    }

    #[test]
    fn preferred_spaced_command_form_includes_crawl_clear() {
        assert_eq!(
            preferred_spaced_command_form("crawl-clear"),
            Some("crawl clear")
        );
    }

    // -----------------------------------------------------------------------
    // is_not_focusable_error / is_timeout_error_message tests
    // -----------------------------------------------------------------------

    #[test]
    fn not_focusable_error_detects_lowercase() {
        assert!(is_not_focusable_error("element is not focusable"));
    }

    #[test]
    fn not_focusable_error_detects_mixed_case() {
        assert!(is_not_focusable_error("Element is Not Focusable"));
    }

    #[test]
    fn not_focusable_error_detects_in_message() {
        // The helper looks for the substring "not focusable" (case-insensitive)
        assert!(is_not_focusable_error("Element is not focusable: #shadow-root"));
    }

    #[test]
    fn not_focusable_error_rejects_unrelated() {
        assert!(!is_not_focusable_error("element not found"));
        assert!(!is_not_focusable_error("timeout waiting for selector"));
        assert!(!is_not_focusable_error(""));
    }

    #[test]
    fn timeout_error_detects_timed_out() {
        assert!(is_timeout_error_message("operation timed out after 30s"));
    }

    #[test]
    fn timeout_error_detects_deadline_elapsed() {
        assert!(is_timeout_error_message("deadline has elapsed"));
    }

    #[test]
    fn timeout_error_detects_mixed_case() {
        assert!(is_timeout_error_message("Operation Timed Out"));
    }

    #[test]
    fn timeout_error_rejects_unrelated() {
        assert!(!is_timeout_error_message("element not found"));
        assert!(!is_timeout_error_message(""));
    }
}
