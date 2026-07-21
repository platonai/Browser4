//! HTTP helpers for calling Browser4 MCP tools and scrape REST endpoints.

use reqwest::Client;
use serde_json::{json, Value};

use crate::state::resolve_ref;

const DEFAULT_REQUEST_TIMEOUT_SECS: u64 = 30;
const NAVIGATION_REQUEST_TIMEOUT_SECS: u64 = 120;
const TEXT_INPUT_REQUEST_TIMEOUT_SECS: u64 = 90;
const AGENT_REQUEST_TIMEOUT_SECS: u64 = 180;
const SNAPSHOT_REQUEST_TIMEOUT_SECS: u64 = 60;
const BATCH_REQUEST_TIMEOUT_SECS: u64 = 120;
const CRAWL_REQUEST_TIMEOUT_SECS: u64 = 600;
const CRAWL_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_CRAWL_TIMEOUT_SECS";
const DEFAULT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_HTTP_TIMEOUT_SECS";
const NAVIGATION_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS";
const TEXT_INPUT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_INPUT_TIMEOUT_SECS";
const SNAPSHOT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_SNAPSHOT_TIMEOUT_SECS";
const AGENT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_AGENT_TIMEOUT_SECS";
const ACT_REQUEST_TIMEOUT_SECS: u64 = 60;
const ACT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_ACT_TIMEOUT_SECS";

fn timeout_secs_from_env(env_key: &str, default_secs: u64) -> u64 {
    std::env::var(env_key)
        .ok()
        .and_then(|value| value.trim().parse::<u64>().ok())
        .filter(|secs| *secs > 0)
        .unwrap_or(default_secs)
}

fn default_request_timeout() -> std::time::Duration {
    std::time::Duration::from_secs(timeout_secs_from_env(
        DEFAULT_REQUEST_TIMEOUT_ENV,
        DEFAULT_REQUEST_TIMEOUT_SECS,
    ))
}

fn navigation_request_timeout() -> std::time::Duration {
    std::time::Duration::from_secs(timeout_secs_from_env(
        NAVIGATION_REQUEST_TIMEOUT_ENV,
        NAVIGATION_REQUEST_TIMEOUT_SECS,
    ))
}

fn text_input_request_timeout() -> std::time::Duration {
    std::time::Duration::from_secs(timeout_secs_from_env(
        TEXT_INPUT_REQUEST_TIMEOUT_ENV,
        TEXT_INPUT_REQUEST_TIMEOUT_SECS,
    ))
}

fn agent_request_timeout() -> std::time::Duration {
    std::time::Duration::from_secs(timeout_secs_from_env(
        AGENT_REQUEST_TIMEOUT_ENV,
        AGENT_REQUEST_TIMEOUT_SECS,
    ))
}

fn snapshot_request_timeout() -> std::time::Duration {
    std::time::Duration::from_secs(timeout_secs_from_env(
        SNAPSHOT_REQUEST_TIMEOUT_ENV,
        SNAPSHOT_REQUEST_TIMEOUT_SECS,
    ))
}

fn is_navigation_tool(tool: &str) -> bool {
    matches!(
        tool,
        "browser_navigate"
            | "browser_reload"
            | "browser_navigate_back"
            | "browser_navigate_forward"
    )
}

/// Tools that can trigger navigation as a side effect (clicking links, submitting forms,
/// pressing Enter on focused inputs). The backend runs post-action navigation detection
/// that can take up to ~31s, so these need the same timeout budget as explicit navigation.
fn is_navigation_triggering_tool(tool: &str) -> bool {
    matches!(tool, "browser_click" | "browser_press_key")
}

fn is_text_input_tool(tool: &str) -> bool {
    matches!(
        tool,
        "browser_press_sequentially" | "browser_type" | "browser_fill_form"
    )
}

fn is_agent_tool(tool: &str) -> bool {
    matches!(tool, "agent_extract" | "agent_summarize")
}

fn is_snapshot_tool(tool: &str) -> bool {
    matches!(
        tool,
        "browser_snapshot"
            | "aria_snapshot"
            | "html_snapshot_inspect"
            | "html_snapshot_capture"
            | "html_snapshot_get"
            | "html_snapshot_get_all"
            | "html_snapshot_query"
            | "html_snapshot_export"
            | "html_snapshot_summary"
            | "html_snapshot_grep"
    )
}

fn is_wait_tool(tool: &str) -> bool {
    matches!(
        tool,
        "wait_for_selector" | "wait_for_function" | "wait_for_page" | "delay"
    )
}

/// Extract the server-side timeout in milliseconds from wait-tool arguments.
///
/// Wait tools carry their own deadline (`timeoutMillis` or `millis`).  The HTTP
/// client budget must be at least that deadline plus a small buffer so the
/// server has time to send back a timeout error before the HTTP layer gives up.
fn wait_tool_server_timeout_ms(args: &Value) -> Option<i64> {
    args.get("timeoutMillis")
        .or_else(|| args.get("millis"))
        .and_then(|v| v.as_i64())
}

fn timeout_for_tool(tool: &str) -> std::time::Duration {
    if is_navigation_tool(tool) || is_navigation_triggering_tool(tool) {
        navigation_request_timeout()
    } else if is_text_input_tool(tool) {
        text_input_request_timeout()
    } else if is_agent_tool(tool) {
        agent_request_timeout()
    } else if is_snapshot_tool(tool) {
        snapshot_request_timeout()
    } else {
        default_request_timeout()
    }
}

/// Build a `reqwest::Client` configured for Browser4 MCP calls.
pub fn make_client() -> Client {
    Client::builder()
        .timeout(default_request_timeout())
        .build()
        .expect("HTTP client construction should not fail")
}

/// Resolve element ref fields inside the tool arguments.
///
/// The following keys are normalised: `selector`, `ref`, `startRef`, `endRef`.
fn normalize_refs(args: &mut Value) {
    let ref_keys = ["selector", "ref", "startRef", "endRef"];
    if let Value::Object(map) = args {
        for key in &ref_keys {
            if let Some(Value::String(val)) = map.get(*key) {
                let resolved = resolve_ref(val);
                map.insert(key.to_string(), json!(resolved));
            }
        }
    }
}

fn extract_mcp_text_payload(data: &Value) -> Option<String> {
    if let Some(text) = data.as_str() {
        return Some(text.to_string());
    }

    if let Some(content) = data.get("content").and_then(|value| value.as_array()) {
        for item in content {
            if let Some(text) = item.get("text").and_then(|value| value.as_str()) {
                return Some(text.to_string());
            }
            if let Some(json_payload) = item.get("json") {
                return Some(json_payload.to_string());
            }
        }
    }

    if let Some(structured) = data.get("structuredContent") {
        return Some(structured.to_string());
    }

    if data.is_object() || data.is_array() {
        return Some(data.to_string());
    }

    None
}

fn summarize_mcp_request(
    tool: &str,
    endpoint: &str,
    args: &Value,
    timeout: Option<std::time::Duration>,
) -> String {
    let mut parts = vec![format!("tool={tool}"), format!("endpoint={endpoint}")];

    if let Some(timeout) = timeout {
        parts.push(format!("timeout={}s", timeout.as_secs()));
    }

    if let Some(session_id) = args.get("sessionId").and_then(|value| value.as_str()) {
        parts.push(format!("sessionId={session_id}"));
    }

    if let Some(url) = args.get("url").and_then(|value| value.as_str()) {
        parts.push(format!("url={url}"));
    }

    format!("[{}]", parts.join(", "))
}

fn format_mcp_transport_error(
    tool: &str,
    endpoint: &str,
    args: &Value,
    timeout: Option<std::time::Duration>,
    error: &reqwest::Error,
) -> String {
    let context = summarize_mcp_request(tool, endpoint, args, timeout);
    if error.is_timeout() {
        let mut msg = format!("HTTP request timed out {context}: {error}");
        if is_text_input_tool(tool) {
            msg.push_str(
                "\nNote: Text input operations may partially execute despite timeout. \
                 Verify the field content with `snapshot` or `get-text` after a timeout.",
            );
        }
        if is_navigation_triggering_tool(tool) {
            msg.push_str(
                "\nNote: Click/press actions may trigger page navigation that succeeds \
                 despite the timeout. Check the current page with `snapshot` to verify \
                 whether the action completed.",
            );
        }
        if is_agent_tool(tool) {
            msg.push_str(
                "\nNote: AI-powered agent operations (extract/summarize) may take \
                 several minutes on large pages. Increase the timeout with the \
                 BROWSER4_CLI_AGENT_TIMEOUT_SECS environment variable.",
            );
        }
        msg
    } else {
        format!("HTTP request failed {context}: {error}")
    }
}

/// Call an MCP tool on the Browser4 server.
///
/// Makes a `POST /mcp/call-tool` request and returns the text of the first
/// content block, or an error message from the server.
/// Result of an MCP tool call, including optional server-side pagination metadata.
pub struct CallToolResult {
    pub text: String,
    /// Server-side pagination metadata, if the server paginated the response.
    pub pagination: Option<ServerPaginationMeta>,
}

/// Server-provided pagination metadata from the `_pagination` field in MCP responses.
#[derive(Debug, Clone)]
pub struct ServerPaginationMeta {
    pub page: usize,
    pub total_pages: usize,
    pub total_lines: usize,
    pub page_size: usize,
    pub truncated: bool,
}

impl ServerPaginationMeta {
    fn from_json(v: &Value) -> Option<Self> {
        Some(Self {
            page: v.get("page")?.as_u64()? as usize,
            total_pages: v.get("totalPages")?.as_u64()? as usize,
            total_lines: v.get("totalLines")?.as_u64()? as usize,
            page_size: v.get("pageSize")?.as_u64()? as usize,
            truncated: v.get("truncated").and_then(|t| t.as_bool()).unwrap_or(true),
        })
    }
}

pub async fn call_tool(
    client: &Client,
    base_url: &str,
    tool: &str,
    args: Value,
) -> Result<String, String> {
    call_tool_with_result(client, base_url, tool, args)
        .await
        .map(|r| r.text)
}

/// Like [call_tool] but also returns any server-side pagination metadata present
/// in the response.
pub async fn call_tool_with_result(
    client: &Client,
    base_url: &str,
    tool: &str,
    args: Value,
) -> Result<CallToolResult, String> {
    let timeout = effective_timeout(tool, &args);
    call_tool_with_timeout(client, base_url, tool, args, Some(timeout)).await
}

/// Compute the HTTP-level timeout for a tool call.
///
/// For wait tools the server-side operation can take up to `timeoutMillis` (or
/// `millis`).  We add a 5-second buffer so the server has time to send its
/// timeout error before the HTTP layer gives up.
fn effective_timeout(tool: &str, args: &Value) -> std::time::Duration {
    let base = timeout_for_tool(tool);
    if !is_wait_tool(tool) {
        return base;
    }
    if let Some(server_ms) = wait_tool_server_timeout_ms(args) {
        let server_secs = (server_ms as u64).div_ceil(1000);
        let http_secs = server_secs.saturating_add(5); // 5 s buffer
        return std::time::Duration::from_secs(http_secs.max(base.as_secs()));
    }
    // No explicit server timeout in params — use the default with a buffer.
    base.saturating_add(std::time::Duration::from_secs(5))
}

async fn call_tool_with_timeout(
    client: &Client,
    base_url: &str,
    tool: &str,
    mut args: Value,
    timeout: Option<std::time::Duration>,
) -> Result<CallToolResult, String> {
    normalize_refs(&mut args);

    let url = format!("{}/mcp/call-tool", base_url.trim_end_matches('/'));
    let body = json!({ "tool": tool, "arguments": args });

    let request = client
        .post(&url)
        .header("Content-Type", "application/json")
        .json(&body);
    let request = if let Some(timeout) = timeout {
        request.timeout(timeout)
    } else {
        request
    };

    let response = request
        .send()
        .await
        .map_err(|e| format_mcp_transport_error(tool, &url, &args, timeout, &e))?;

    let status = response.status();
    let response_text = response
        .text()
        .await
        .map_err(|e| format!("Failed to read response body: {e}"))?;

    if !status.is_success() {
        let message = response_text.trim();
        if message.is_empty() {
            return Err(format!(
                "HTTP request failed with status {} and an empty response body.",
                status
            ));
        }
        return Err(format!(
            "HTTP request failed with status {}: {}",
            status, message
        ));
    }

    let data: Value = serde_json::from_str(&response_text)
        .map_err(|e| format!("Failed to parse response JSON: {e}"))?;

    if data
        .get("isError")
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
    {
        let msg = data
            .get("content")
            .and_then(|c| c.as_array())
            .and_then(|arr| arr.first())
            .and_then(|item| item.get("text"))
            .and_then(|t| t.as_str())
            .unwrap_or("Unknown MCP error");
        return Err(msg.to_string());
    }

    let text = extract_mcp_text_payload(&data)
        .ok_or_else(|| "MCP response did not contain a readable payload.".to_string())?;

    let pagination = data
        .get("_pagination")
        .and_then(ServerPaginationMeta::from_json);

    Ok(CallToolResult { text, pagination })
}

/// Check whether a server error message indicates a stale/expired session.
pub fn is_stale_session_error(message: &str) -> bool {
    let lower = message.to_lowercase();
    lower.contains("cannot find context with specified id")
        || lower.contains("invalid session id")
        || lower.contains("session not found")
        || lower.contains("session does not exist")
        || lower.contains("target closed")
        || lower.contains("session closed")
}

/// Submit a plain-text command to the Browser4 server via the MCP endpoint.
///
/// When `async_mode` is true, the server returns a task ID immediately.
/// When false, the server blocks until execution completes and returns the CommandStatus JSON.
pub async fn submit_plain_command(
    client: &Client,
    base_url: &str,
    command: &str,
    async_mode: bool,
) -> Result<String, String> {
    call_tool(
        client,
        base_url,
        "command_run",
        serde_json::json!({ "command": command, "async": async_mode }),
    )
    .await
}

/// Submit a natural language description to the server, which translates it
/// to a CLI command via LLM and executes it synchronously.
/// Returns the command output on success.
pub async fn execute_act_command(
    client: &Client,
    base_url: &str,
    description: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/act");
    let timeout = std::time::Duration::from_secs(timeout_secs_from_env(
        ACT_REQUEST_TIMEOUT_ENV,
        ACT_REQUEST_TIMEOUT_SECS,
    ));
    let response = client
        .post(&url)
        .header("Content-Type", "application/json")
        .json(&json!({ "description": description }))
        .timeout(timeout)
        .send()
        .await
        .map_err(|e| {
            if e.is_timeout() {
                format!(
                    "Act request timed out after {}s. Increase with {} env var.",
                    timeout.as_secs(),
                    ACT_REQUEST_TIMEOUT_ENV
                )
            } else {
                format!("Failed to call act endpoint: {e}")
            }
        })?;

    let status = response.status();
    let response_text = response
        .text()
        .await
        .map_err(|e| format!("Failed to read act response body: {e}"))?;

    if !status.is_success() {
        return Err(format_http_error(status, &response_text));
    }

    // Parse the JSON response: { "success": true, "output": "..." }
    // or { "success": false, "error": "..." }
    let parsed: Value = serde_json::from_str(&response_text)
        .map_err(|e| format!("Failed to parse act response: {e}"))?;

    let success = parsed
        .get("success")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    if success {
        Ok(parsed
            .get("output")
            .and_then(|v| v.as_str())
            .map(String::from)
            .unwrap_or_default())
    } else {
        let error_msg = parsed
            .get("error")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown error");
        Err(error_msg.to_string())
    }
}

/// Submit a swarm payload through `SwarmController.submit(payload)`.
pub async fn submit_swarm_payload(
    client: &Client,
    base_url: &str,
    payload: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/swarm/submit");
    send_rest_request(
        client
            .post(url)
            .header("Content-Type", "text/plain; charset=utf-8")
            .body(payload.to_string()),
    )
    .await
}

/// Submit a swarm X-SQL query through `SwarmController.query(query)`.
pub async fn submit_swarm_query(
    client: &Client,
    base_url: &str,
    query: serde_json::Value,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/swarm/query");
    send_rest_request(
        client
            .post(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .body(query.to_string()),
    )
    .await
}

/// Read swarm task status through `SwarmController.getStatus(id)`.
pub async fn get_swarm_status(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/swarm/{task_id}/status"));
    send_rest_request(client.get(url)).await
}

/// Read swarm task result through `SwarmController.getResult(id)`.
pub async fn get_swarm_result(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/swarm/{task_id}/result"));
    send_rest_request(client.get(url)).await
}


fn build_endpoint_url(base_url: &str, path: &str) -> String {
    format!("{}{}", base_url.trim_end_matches('/'), path)
}

fn format_http_error(status: reqwest::StatusCode, response_text: &str) -> String {
    let message = response_text.trim();
    if message.is_empty() {
        format!(
            "HTTP request failed with status {} and an empty response body.",
            status
        )
    } else {
        format!("HTTP request failed with status {}: {}", status, message)
    }
}

async fn send_rest_request(
    request: reqwest::RequestBuilder,
) -> Result<String, String> {
    let response = request
        .send()
        .await
        .map_err(|e| format!("HTTP request failed: {e}"))?;

    let status = response.status();
    let response_text = response
        .text()
        .await
        .map_err(|e| format!("Failed to read response body: {e}"))?;

    if !status.is_success() {
        return Err(format_http_error(status, &response_text));
    }

    Ok(response_text)
}

// ---------------------------------------------------------------------------
// Plugin REST API helpers
// ---------------------------------------------------------------------------

/// List all installed plugins via `GET /api/plugins`.
pub async fn list_plugins(client: &Client, base_url: &str) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/plugins");
    send_rest_request(client.get(url)).await
}

/// Get a single plugin by name via `GET /api/plugins/{name}`.
pub async fn get_plugin(client: &Client, base_url: &str, name: &str) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/plugins/{}", name));
    send_rest_request(client.get(url)).await
}

/// Install a plugin JAR via `POST /api/plugins/install` (multipart upload).
pub async fn install_plugin(
    client: &Client,
    base_url: &str,
    file_path: &str,
    replace: bool,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/plugins/install");
    let file_bytes = std::fs::read(file_path)
        .map_err(|e| format!("Failed to read plugin file '{}': {}", file_path, e))?;
    let file_name = std::path::Path::new(file_path)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("plugin.jar");

    let part = reqwest::multipart::Part::bytes(file_bytes)
        .file_name(file_name.to_string())
        .mime_str("application/java-archive")
        .map_err(|e| format!("Failed to set MIME type: {}", e))?;

    let form = reqwest::multipart::Form::new()
        .part("file", part)
        .text("replace", replace.to_string());

    send_rest_request(client.post(url).multipart(form)).await
}

/// Remove a plugin by name via `DELETE /api/plugins/{name}`.
pub async fn remove_plugin(
    client: &Client,
    base_url: &str,
    name: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/plugins/{}", name));
    send_rest_request(client.delete(url)).await
}

/// Submit a crawl task via the MCP `crawl_submit` tool.
pub async fn submit_crawl(
    client: &Client,
    base_url: &str,
    params: &Value,
) -> Result<String, String> {
    call_tool(client, base_url, "crawl_submit", params.clone()).await
}

pub fn crawl_request_timeout() -> std::time::Duration {
    std::time::Duration::from_secs(timeout_secs_from_env(
        CRAWL_REQUEST_TIMEOUT_ENV,
        CRAWL_REQUEST_TIMEOUT_SECS,
    ))
}

/// Cancel a running crawl task via `CrawlController.cancelCrawl(id)`.
pub async fn cancel_crawl(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/crawl/{task_id}/cancel"));
    send_rest_request(client.post(url)).await
}

/// Clear all terminal-state crawl tasks via `CrawlController.clearCrawls()`.
pub async fn clear_crawls(
    client: &Client,
    base_url: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/crawl/clear");
    send_rest_request(client.post(url)).await
}

/// Get the status of a crawl task via `CrawlController.getStatus(id)`.
pub async fn get_crawl_status(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/crawl/{task_id}/status"));
    send_rest_request(client.get(url)).await
}

/// Get the result of a crawl task via `CrawlController.getResult(id)`.
pub async fn get_crawl_result(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/crawl/{task_id}/result"));
    send_rest_request(client.get(url)).await
}

/// Get the status of a command by its task ID via the MCP endpoint.
pub async fn get_command_status(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    call_tool(
        client,
        base_url,
        "command_status",
        serde_json::json!({ "id": task_id }),
    )
    .await
}

/// Get the result of a completed command by its task ID via the MCP endpoint.
pub async fn get_command_result(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    call_tool(
        client,
        base_url,
        "command_result",
        serde_json::json!({ "id": task_id }),
    )
    .await
}

/// Execute a batch of CLI-derived operations in a single backend request.
pub async fn submit_batch_commands(
    client: &Client,
    base_url: &str,
    args: Value,
) -> Result<String, String> {
    call_tool_with_timeout(
        client,
        base_url,
        "command_batch",
        args,
        Some(std::time::Duration::from_secs(BATCH_REQUEST_TIMEOUT_SECS)),
    )
    .await
    .map(|r| r.text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::Mutex;
    use std::thread;

    static TIMEOUT_ENV_MUTEX: Mutex<()> = Mutex::new(());

    struct TimeoutEnvGuard {
        default_timeout: Option<String>,
        navigation_timeout: Option<String>,
        text_input_timeout: Option<String>,
    }

    impl TimeoutEnvGuard {
        fn set(default_timeout_secs: &str, navigation_timeout_secs: &str) -> Self {
            let guard = Self {
                default_timeout: std::env::var(DEFAULT_REQUEST_TIMEOUT_ENV).ok(),
                navigation_timeout: std::env::var(NAVIGATION_REQUEST_TIMEOUT_ENV).ok(),
                text_input_timeout: std::env::var(TEXT_INPUT_REQUEST_TIMEOUT_ENV).ok(),
            };
            std::env::set_var(DEFAULT_REQUEST_TIMEOUT_ENV, default_timeout_secs);
            std::env::set_var(NAVIGATION_REQUEST_TIMEOUT_ENV, navigation_timeout_secs);
            guard
        }

        fn set_all(
            default_timeout_secs: &str,
            navigation_timeout_secs: &str,
            text_input_timeout_secs: &str,
        ) -> Self {
            let guard = Self {
                default_timeout: std::env::var(DEFAULT_REQUEST_TIMEOUT_ENV).ok(),
                navigation_timeout: std::env::var(NAVIGATION_REQUEST_TIMEOUT_ENV).ok(),
                text_input_timeout: std::env::var(TEXT_INPUT_REQUEST_TIMEOUT_ENV).ok(),
            };
            std::env::set_var(DEFAULT_REQUEST_TIMEOUT_ENV, default_timeout_secs);
            std::env::set_var(NAVIGATION_REQUEST_TIMEOUT_ENV, navigation_timeout_secs);
            std::env::set_var(TEXT_INPUT_REQUEST_TIMEOUT_ENV, text_input_timeout_secs);
            guard
        }
    }

    impl Drop for TimeoutEnvGuard {
        fn drop(&mut self) {
            if let Some(value) = &self.default_timeout {
                std::env::set_var(DEFAULT_REQUEST_TIMEOUT_ENV, value);
            } else {
                std::env::remove_var(DEFAULT_REQUEST_TIMEOUT_ENV);
            }

            if let Some(value) = &self.navigation_timeout {
                std::env::set_var(NAVIGATION_REQUEST_TIMEOUT_ENV, value);
            } else {
                std::env::remove_var(NAVIGATION_REQUEST_TIMEOUT_ENV);
            }

            if let Some(value) = &self.text_input_timeout {
                std::env::set_var(TEXT_INPUT_REQUEST_TIMEOUT_ENV, value);
            } else {
                std::env::remove_var(TEXT_INPUT_REQUEST_TIMEOUT_ENV);
            }
        }
    }

    fn spawn_delayed_mcp_server(delay: std::time::Duration) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind delayed MCP test server");
        let addr = listener
            .local_addr()
            .expect("read delayed MCP test server addr");

        thread::spawn(move || {
            let (mut stream, _) = listener
                .accept()
                .expect("accept delayed MCP test connection");
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                .ok();

            let mut buffer = [0_u8; 8192];
            let _ = stream.read(&mut buffer);

            thread::sleep(delay);

            let body = r#"{"content":[{"type":"text","text":"ok"}]}"#;
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.flush();
        });

        format!("http://{}", addr)
    }

    /// Spawn a TCP server that accepts connections but never sends a response.
    ///
    /// This reliably triggers a client-side timeout on all platforms (Linux,
    /// macOS, Windows). Connecting to a closed port (`127.0.0.1:1`) is not
    /// portable: Linux silently drops the SYN producing a timeout, while
    /// Windows sends an immediate RST producing "connection refused".
    fn spawn_hanging_server() -> String {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind hanging test server");
        let addr = listener
            .local_addr()
            .expect("read hanging test server addr");

        thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept hanging test connection");
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                .ok();
            // Read the HTTP request so the client can finish sending it.
            let mut buffer = [0_u8; 8192];
            let _ = stream.read(&mut buffer);
            // Never respond — the client will time out.
            thread::sleep(std::time::Duration::from_secs(300));
        });

        format!("http://{}", addr)
    }

    fn is_timeout_error(error: &str) -> bool {
        let lower = error.to_ascii_lowercase();
        lower.contains("timed out")
            || lower.contains("deadline has elapsed")
            || lower.contains("error sending request for url")
    }

    #[test]
    fn test_normalize_refs_e_notation() {
        let mut args = json!({ "ref": "e15", "selector": "e42" });
        normalize_refs(&mut args);
        assert_eq!(args["ref"], "backend:15");
        assert_eq!(args["selector"], "backend:42");
    }

    #[test]
    fn test_normalize_refs_passthrough() {
        let mut args = json!({ "ref": ".my-class", "startRef": "backend:7" });
        normalize_refs(&mut args);
        assert_eq!(args["ref"], ".my-class");
        assert_eq!(args["startRef"], "backend:7");
    }

    #[test]
    fn test_is_stale_session_error() {
        assert!(is_stale_session_error(
            "Cannot find context with specified id"
        ));
        assert!(is_stale_session_error("Invalid session ID"));
        assert!(is_stale_session_error("Session not found"));
        assert!(is_stale_session_error("Target closed"));
        assert!(!is_stale_session_error("Connection refused"));
    }

    #[test]
    fn test_extract_mcp_text_payload_prefers_text_content() {
        let payload = json!({
            "content": [{ "type": "text", "text": "ok" }],
            "structuredContent": { "ignored": true }
        });
        assert_eq!(extract_mcp_text_payload(&payload).as_deref(), Some("ok"));
    }

    #[test]
    fn test_extract_mcp_text_payload_supports_structured_content() {
        let payload = json!({ "structuredContent": { "sessionId": "s-1" } });
        assert_eq!(
            extract_mcp_text_payload(&payload).as_deref(),
            Some("{\"sessionId\":\"s-1\"}")
        );
    }

    #[test]
    fn test_extract_mcp_text_payload_supports_direct_object_response() {
        let payload = json!({
            "sessionId": "s-1",
            "results": []
        });
        assert_eq!(
            extract_mcp_text_payload(&payload).as_deref(),
            Some("{\"results\":[],\"sessionId\":\"s-1\"}")
        );
    }

    #[test]
    fn test_summarize_mcp_request_includes_timeout_and_common_navigation_fields() {
        let summary = summarize_mcp_request(
            "browser_navigate",
            "http://localhost:8182/mcp/call-tool",
            &json!({
                "sessionId": "default",
                "url": "https://www.amazon.com/"
            }),
            Some(std::time::Duration::from_secs(120)),
        );

        assert_eq!(
            summary,
            "[tool=browser_navigate, endpoint=http://localhost:8182/mcp/call-tool, timeout=120s, sessionId=default, url=https://www.amazon.com/]"
        );
    }

    #[test]
    fn test_timeout_for_tool_uses_navigation_budget_for_navigation_commands() {
        let _env_lock = TIMEOUT_ENV_MUTEX
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _env_guard = TimeoutEnvGuard::set_all("3", "7", "11");

        assert_eq!(timeout_for_tool("browser_navigate").as_secs(), 7);
        assert_eq!(timeout_for_tool("browser_reload").as_secs(), 7);
        assert_eq!(timeout_for_tool("browser_navigate_back").as_secs(), 7);
        assert_eq!(timeout_for_tool("browser_navigate_forward").as_secs(), 7);
        // Navigation-triggering tools should also get the navigation budget
        assert_eq!(timeout_for_tool("browser_click").as_secs(), 7);
        assert_eq!(timeout_for_tool("browser_press_key").as_secs(), 7);
        assert_eq!(timeout_for_tool("browser_press_sequentially").as_secs(), 11);
        assert_eq!(timeout_for_tool("browser_type").as_secs(), 11);
        assert_eq!(timeout_for_tool("browser_fill_form").as_secs(), 11);
        assert_eq!(timeout_for_tool("page_title").as_secs(), 3);
        // Agent tools use their own 180s default when the env var is not set
        assert_eq!(timeout_for_tool("agent_extract").as_secs(), 180);
        assert_eq!(timeout_for_tool("agent_summarize").as_secs(), 180);
    }

    #[test]
    fn test_text_input_tool_timeout_uses_custom_env() {
        let _env_lock = TIMEOUT_ENV_MUTEX
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _env_guard = TimeoutEnvGuard::set_all("30", "120", "60");

        assert_eq!(timeout_for_tool("browser_press_sequentially").as_secs(), 60);
        assert_eq!(timeout_for_tool("browser_type").as_secs(), 60);
        assert_eq!(timeout_for_tool("browser_fill_form").as_secs(), 60);
        // Navigation-triggering tools get the navigation timeout, not the default
        assert_eq!(timeout_for_tool("browser_click").as_secs(), 120);
        assert_eq!(timeout_for_tool("browser_press_key").as_secs(), 120);
        // Non-navigation, non-input, non-navigation-triggering tools get default
        assert_eq!(timeout_for_tool("page_title").as_secs(), 30);
    }

    #[test]
    fn test_is_text_input_tool_detection() {
        assert!(is_text_input_tool("browser_press_sequentially"));
        assert!(is_text_input_tool("browser_type"));
        assert!(is_text_input_tool("browser_fill_form"));
        assert!(!is_text_input_tool("browser_click"));
        assert!(!is_text_input_tool("browser_navigate"));
        assert!(!is_text_input_tool("page_title"));
    }

    #[test]
    fn test_is_navigation_triggering_tool_detection() {
        assert!(is_navigation_triggering_tool("browser_click"));
        assert!(is_navigation_triggering_tool("browser_press_key"));
        assert!(!is_navigation_triggering_tool("browser_navigate"));
        assert!(!is_navigation_triggering_tool("browser_type"));
        assert!(!is_navigation_triggering_tool("page_title"));
    }

    #[test]
    fn test_is_agent_tool_detection() {
        assert!(is_agent_tool("agent_extract"));
        assert!(is_agent_tool("agent_summarize"));
        assert!(!is_agent_tool("browser_navigate"));
        assert!(!is_agent_tool("page_title"));
    }

    #[test]
    fn test_agent_tool_timeout_uses_custom_env() {
        let _env_lock = TIMEOUT_ENV_MUTEX
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        std::env::set_var(AGENT_REQUEST_TIMEOUT_ENV, "240");
        // Also set a low default so we can confirm agent tools don't use it
        std::env::set_var(DEFAULT_REQUEST_TIMEOUT_ENV, "10");

        assert_eq!(timeout_for_tool("agent_extract").as_secs(), 240);
        assert_eq!(timeout_for_tool("agent_summarize").as_secs(), 240);
        // Non-agent tools still use the default
        assert_eq!(timeout_for_tool("page_title").as_secs(), 10);

        std::env::remove_var(AGENT_REQUEST_TIMEOUT_ENV);
        std::env::remove_var(DEFAULT_REQUEST_TIMEOUT_ENV);
    }

    #[test]
    fn test_text_input_timeout_error_includes_partial_execution_note() {
        let server_url = spawn_hanging_server();
        let error = format_mcp_transport_error(
            "browser_type",
            "http://localhost:8182/mcp/call-tool",
            &json!({ "text": "hello" }),
            Some(std::time::Duration::from_secs(90)),
            &reqwest::blocking::Client::new()
                .post(server_url)
                .timeout(std::time::Duration::from_millis(1))
                .send()
                .expect_err("should time out"),
        );
        assert!(
            error.contains("HTTP request timed out"),
            "Expected timeout prefix, got: {error}"
        );
        assert!(
            error.contains("may partially execute despite timeout"),
            "Expected partial execution note, got: {error}"
        );
    }

    #[test]
    fn test_navigation_triggering_tool_timeout_error_includes_snapshot_note() {
        let server_url = spawn_hanging_server();
        let error = format_mcp_transport_error(
            "browser_click",
            "http://localhost:8182/mcp/call-tool",
            &json!({ "ref": "e15" }),
            Some(std::time::Duration::from_secs(30)),
            &reqwest::blocking::Client::new()
                .post(server_url)
                .timeout(std::time::Duration::from_millis(1))
                .send()
                .expect_err("should time out"),
        );
        assert!(
            error.contains("HTTP request timed out"),
            "Expected timeout prefix, got: {error}"
        );
        assert!(
            error.contains("Check the current page with `snapshot`"),
            "Expected snapshot suggestion for navigation-triggering tool, got: {error}"
        );
        assert!(
            !error.contains("may partially execute despite timeout"),
            "Should NOT contain text input partial execution note for click tool, got: {error}"
        );
    }

    #[test]
    fn test_non_navigation_non_input_tool_timeout_error_has_no_notes() {
        let server_url = spawn_hanging_server();
        let error = format_mcp_transport_error(
            "page_title",
            "http://localhost:8182/mcp/call-tool",
            &json!({}),
            Some(std::time::Duration::from_secs(30)),
            &reqwest::blocking::Client::new()
                .post(server_url)
                .timeout(std::time::Duration::from_millis(1))
                .send()
                .expect_err("should time out"),
        );
        assert!(
            error.contains("HTTP request timed out"),
            "Expected timeout prefix, got: {error}"
        );
        assert!(
            !error.contains("may partially execute despite timeout"),
            "Should NOT contain partial execution note for page_title, got: {error}"
        );
        assert!(
            !error.contains("Check the current page with `snapshot`"),
            "Should NOT contain snapshot suggestion for page_title, got: {error}"
        );
    }

    #[test]
    fn test_agent_tool_timeout_error_includes_env_var_note() {
        let server_url = spawn_hanging_server();
        let error = format_mcp_transport_error(
            "agent_extract",
            "http://localhost:8182/mcp/call-tool",
            &json!({ "instruction": "product name, price" }),
            Some(std::time::Duration::from_secs(180)),
            &reqwest::blocking::Client::new()
                .post(server_url)
                .timeout(std::time::Duration::from_millis(1))
                .send()
                .expect_err("should time out"),
        );
        assert!(
            error.contains("HTTP request timed out"),
            "Expected timeout prefix, got: {error}"
        );
        assert!(
            error.contains("BROWSER4_CLI_AGENT_TIMEOUT_SECS"),
            "Expected env var suggestion for agent tool, got: {error}"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn test_call_tool_applies_navigation_timeout_override() {
        let _env_lock = TIMEOUT_ENV_MUTEX
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _env_guard = TimeoutEnvGuard::set("1", "2");

        let client = make_client();
        let base_url = spawn_delayed_mcp_server(std::time::Duration::from_millis(1_500));

        let result = call_tool(
            &client,
            &base_url,
            "browser_navigate",
            json!({ "url": "https://example.com/slow" }),
        )
        .await;

        assert_eq!(result.as_deref(), Ok("ok"));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn test_call_tool_keeps_default_timeout_for_non_navigation_commands() {
        let _env_lock = TIMEOUT_ENV_MUTEX
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _env_guard = TimeoutEnvGuard::set("1", "2");

        let client = make_client();
        let base_url = spawn_delayed_mcp_server(std::time::Duration::from_millis(1_500));

        let result = call_tool(&client, &base_url, "page_title", json!({})).await;

        let error = result.expect_err("non-navigation tool should still time out");
        assert!(
            is_timeout_error(&error),
            "Expected timeout-related error, got: {error}"
        );
        assert!(
            error.contains("tool=page_title"),
            "Expected tool diagnostics, got: {error}"
        );
        assert!(
            error.contains("timeout=1s"),
            "Expected timeout diagnostics, got: {error}"
        );
        assert!(
            error.contains("endpoint=http://"),
            "Expected endpoint diagnostics, got: {error}"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn test_call_tool_timeout_diagnostics_include_navigation_context() {
        let _env_lock = TIMEOUT_ENV_MUTEX
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _env_guard = TimeoutEnvGuard::set("1", "1");

        let client = make_client();
        let base_url = spawn_delayed_mcp_server(std::time::Duration::from_millis(1_500));

        let result = call_tool(
            &client,
            &base_url,
            "browser_navigate",
            json!({
                "sessionId": "default",
                "url": "https://www.amazon.com/"
            }),
        )
        .await;

        let error =
            result.expect_err("navigation request should time out with the forced 1s budget");
        assert!(
            is_timeout_error(&error),
            "Expected timeout-related error, got: {error}"
        );
        assert!(
            error.contains("tool=browser_navigate"),
            "Expected tool diagnostics, got: {error}"
        );
        assert!(
            error.contains("timeout=1s"),
            "Expected timeout diagnostics, got: {error}"
        );
        assert!(
            error.contains("sessionId=default"),
            "Expected session diagnostics, got: {error}"
        );
        assert!(
            error.contains("url=https://www.amazon.com/"),
            "Expected URL diagnostics, got: {error}"
        );
    }

    // -------------------------------------------------------------------
    // build_endpoint_url tests (used by crawl REST endpoints)
    // -------------------------------------------------------------------

    #[test]
    fn build_endpoint_url_no_trailing_slash() {
        let url = build_endpoint_url("http://localhost:8182", "/api/crawl/task-1/status");
        assert_eq!(url, "http://localhost:8182/api/crawl/task-1/status");
    }

    #[test]
    fn build_endpoint_url_with_trailing_slash() {
        let url = build_endpoint_url("http://localhost:8182/", "/api/crawl/task-1/result");
        assert_eq!(url, "http://localhost:8182/api/crawl/task-1/result");
    }

    #[test]
    fn build_endpoint_url_multiple_slashes() {
        let url = build_endpoint_url("http://localhost:8182///", "/api/crawl/clear");
        assert_eq!(url, "http://localhost:8182/api/crawl/clear");
    }

    // -------------------------------------------------------------------
    // format_http_error tests
    // -------------------------------------------------------------------

    #[test]
    fn format_http_error_with_body() {
        let status = reqwest::StatusCode::INTERNAL_SERVER_ERROR;
        let msg = format_http_error(status, "something broke");
        assert!(msg.contains("500"), "should contain status code");
        assert!(msg.contains("something broke"), "should contain message");
    }

    #[test]
    fn format_http_error_empty_body() {
        let status = reqwest::StatusCode::NOT_FOUND;
        let msg = format_http_error(status, "");
        assert!(msg.contains("404"), "should contain status code");
        assert!(msg.contains("empty response body"), "should mention empty body");
    }

    // -------------------------------------------------------------------
    // crawl timeout constants
    // -------------------------------------------------------------------

    #[test]
    fn crawl_request_timeout_uses_correct_env_var_name() {
        // Verify the constant is exactly what the docs tell users
        assert_eq!(CRAWL_REQUEST_TIMEOUT_ENV, "BROWSER4_CLI_CRAWL_TIMEOUT_SECS");
    }

    #[test]
    fn crawl_request_timeout_default_10_minutes() {
        assert_eq!(CRAWL_REQUEST_TIMEOUT_SECS, 600);
    }

    // -------------------------------------------------------------------
    // swarm REST endpoint tests
    // -------------------------------------------------------------------

    /// Spawn a TCP server that doubles as a lightweight swarm REST mock.
    /// Returns the base URL to use.
    fn spawn_swarm_mock_server(expected_path: &'static str, response_body: &'static str) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind swarm mock server");
        let addr = listener.local_addr().expect("read swarm mock server addr");

        thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept swarm test connection");
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                .ok();

            let mut buffer = [0_u8; 8192];
            let n = stream.read(&mut buffer).unwrap_or(0);
            let request = String::from_utf8_lossy(&buffer[..n]);

            // Only respond if the request targets the expected path
            if request.contains(expected_path) {
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    response_body.len(),
                    response_body
                );
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.flush();
            } else {
                // Return 404 for unexpected paths
                let body = r#"{"error":"not found"}"#;
                let response = format!(
                    "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.flush();
            }
        });

        format!("http://{}", addr)
    }

    #[tokio::test(flavor = "current_thread")]
    async fn test_submit_swarm_payload_returns_task_id() {
        let base_url = spawn_swarm_mock_server("/api/swarm/submit", r#""swarm-task-42""#);
        let client = make_client();

        let result = submit_swarm_payload(&client, &base_url, "https://example.com -parse")
            .await
            .expect("submit_swarm_payload should succeed");

        assert_eq!(result, r#""swarm-task-42""#);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn test_submit_swarm_query_returns_task_id() {
        let base_url = spawn_swarm_mock_server("/api/swarm/query", r#""swarm-task-99""#);
        let client = make_client();

        let result = submit_swarm_query(
            &client,
            &base_url,
            json!({"url": "https://example.com", "args": "-parse", "query": "SELECT 1"}),
        )
        .await
        .expect("submit_swarm_query should succeed");

        assert_eq!(result, r#""swarm-task-99""#);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn test_get_swarm_status_returns_status_json() {
        let status_json = r#"{"id":"swarm-task-1","statusCode":102,"isDone":false,"status":"Processing"}"#;
        let base_url = spawn_swarm_mock_server("/api/swarm/swarm-task-1/status", status_json);
        let client = make_client();

        let result = get_swarm_status(&client, &base_url, "swarm-task-1")
            .await
            .expect("get_swarm_status should succeed");

        let parsed: Value = serde_json::from_str(&result).expect("should be valid JSON");
        assert_eq!(parsed["id"], "swarm-task-1");
        assert_eq!(parsed["isDone"], false);
        assert_eq!(parsed["status"], "Processing");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn test_get_swarm_result_returns_result_json() {
        let result_json = r#"{"id":"swarm-task-7","statusCode":200,"isDone":true,"resultSet":[{"url":"https://example.com"}],"status":"OK"}"#;
        let base_url = spawn_swarm_mock_server("/api/swarm/swarm-task-7/result", result_json);
        let client = make_client();

        let result = get_swarm_result(&client, &base_url, "swarm-task-7")
            .await
            .expect("get_swarm_result should succeed");

        let parsed: Value = serde_json::from_str(&result).expect("should be valid JSON");
        assert_eq!(parsed["id"], "swarm-task-7");
        assert_eq!(parsed["isDone"], true);
        assert!(
            parsed["resultSet"].as_array().is_some_and(|a| a.len() == 1),
            "expected resultSet with 1 entry, got: {:?}",
            parsed["resultSet"]
        );
    }

    #[test]
    fn test_swarm_endpoint_url_construction() {
        // Verify that build_endpoint_url handles the swarm API paths correctly
        assert_eq!(
            build_endpoint_url("http://127.0.0.1:8080", "/api/swarm/submit"),
            "http://127.0.0.1:8080/api/swarm/submit"
        );
        assert_eq!(
            build_endpoint_url("http://127.0.0.1:8080/", "/api/swarm/swarm-task-1/status"),
            "http://127.0.0.1:8080/api/swarm/swarm-task-1/status"
        );
        assert_eq!(
            build_endpoint_url("http://127.0.0.1:8080", "/api/swarm/swarm-task-1/result"),
            "http://127.0.0.1:8080/api/swarm/swarm-task-1/result"
        );
    }
}
