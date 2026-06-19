//! HTTP helpers for calling Browser4 MCP tools and scrape REST endpoints.

use reqwest::Client;
use serde_json::{json, Value};

use crate::state::resolve_ref;

const DEFAULT_REQUEST_TIMEOUT_SECS: u64 = 30;
const NAVIGATION_REQUEST_TIMEOUT_SECS: u64 = 120;
const TEXT_INPUT_REQUEST_TIMEOUT_SECS: u64 = 90;
const BATCH_REQUEST_TIMEOUT_SECS: u64 = 120;
const DEFAULT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_HTTP_TIMEOUT_SECS";
const NAVIGATION_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS";
const TEXT_INPUT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_INPUT_TIMEOUT_SECS";

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

fn is_navigation_tool(tool: &str) -> bool {
    matches!(
        tool,
        "browser_navigate"
            | "browser_reload"
            | "browser_navigate_back"
            | "browser_navigate_forward"
    )
}

fn is_text_input_tool(tool: &str) -> bool {
    matches!(
        tool,
        "browser_press_sequentially" | "browser_type" | "browser_fill_form"
    )
}

fn timeout_for_tool(tool: &str) -> std::time::Duration {
    if is_navigation_tool(tool) {
        navigation_request_timeout()
    } else if is_text_input_tool(tool) {
        text_input_request_timeout()
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

fn extract_http_text_payload(response_text: &str) -> String {
    let trimmed = response_text.trim();
    if trimmed.is_empty() {
        return String::new();
    }

    match serde_json::from_str::<Value>(trimmed) {
        Ok(Value::String(text)) => text,
        Ok(value) => value.to_string(),
        Err(_) => trimmed.to_string(),
    }
}

fn build_endpoint_url(base_url: &str, path: &str) -> String {
    format!(
        "{}/{}",
        base_url.trim_end_matches('/'),
        path.trim_start_matches('/')
    )
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
        msg
    } else {
        format!("HTTP request failed {context}: {error}")
    }
}

async fn send_rest_request(request: reqwest::RequestBuilder) -> Result<String, String> {
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

    Ok(extract_http_text_payload(&response_text))
}

/// Call an MCP tool on the Browser4 server.
///
/// Makes a `POST /mcp/call-tool` request and returns the text of the first
/// content block, or an error message from the server.
pub async fn call_tool(
    client: &Client,
    base_url: &str,
    tool: &str,
    args: Value,
) -> Result<String, String> {
    call_tool_with_timeout(client, base_url, tool, args, Some(timeout_for_tool(tool))).await
}

async fn call_tool_with_timeout(
    client: &Client,
    base_url: &str,
    tool: &str,
    mut args: Value,
    timeout: Option<std::time::Duration>,
) -> Result<String, String> {
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

    extract_mcp_text_payload(&data)
        .ok_or_else(|| "MCP response did not contain a readable payload.".to_string())
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

        fn set_all(default_timeout_secs: &str, navigation_timeout_secs: &str, text_input_timeout_secs: &str) -> Self {
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
    fn test_extract_http_text_payload_unquotes_json_string() {
        assert_eq!(
            extract_http_text_payload("\"swarm-task-1\""),
            "swarm-task-1"
        );
    }

    #[test]
    fn test_extract_http_text_payload_preserves_plain_text() {
        assert_eq!(extract_http_text_payload("swarm-task-1\n"), "swarm-task-1");
    }

    #[test]
    fn test_extract_http_text_payload_minifies_json_object() {
        let payload = r#"{
            "id": "swarm-task-1",
            "isDone": false
        }"#;
        assert_eq!(
            extract_http_text_payload(payload),
            "{\"id\":\"swarm-task-1\",\"isDone\":false}"
        );
    }

    #[test]
    fn test_format_http_error_handles_empty_and_non_empty_bodies() {
        assert_eq!(
            format_http_error(reqwest::StatusCode::NOT_FOUND, ""),
            "HTTP request failed with status 404 Not Found and an empty response body."
        );
        assert_eq!(
            format_http_error(reqwest::StatusCode::BAD_REQUEST, "bad request"),
            "HTTP request failed with status 400 Bad Request: bad request"
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
        assert_eq!(timeout_for_tool("browser_press_sequentially").as_secs(), 11);
        assert_eq!(timeout_for_tool("browser_type").as_secs(), 11);
        assert_eq!(timeout_for_tool("browser_fill_form").as_secs(), 11);
        assert_eq!(timeout_for_tool("page_title").as_secs(), 3);
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
        // Default for non-navigation, non-input tools
        assert_eq!(timeout_for_tool("browser_click").as_secs(), 30);
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
    fn test_text_input_timeout_error_includes_partial_execution_note() {
        let error = format_mcp_transport_error(
            "browser_type",
            "http://localhost:8182/mcp/call-tool",
            &json!({ "text": "hello" }),
            Some(std::time::Duration::from_secs(90)),
            &reqwest::blocking::Client::new()
                .post("http://127.0.0.1:1")
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
    fn test_non_input_timeout_error_has_no_partial_execution_note() {
        let error = format_mcp_transport_error(
            "browser_click",
            "http://localhost:8182/mcp/call-tool",
            &json!({ "ref": "e15" }),
            Some(std::time::Duration::from_secs(30)),
            &reqwest::blocking::Client::new()
                .post("http://127.0.0.1:1")
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
            "Should NOT contain partial execution note for non-input tool, got: {error}"
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
}
