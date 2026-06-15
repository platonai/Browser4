//! HTTP helpers for calling Browser4 MCP tools and scrape REST endpoints.

use serde_json::{json, Value};
use ureq::Agent;

use crate::state::resolve_ref;

const DEFAULT_REQUEST_TIMEOUT_SECS: u64 = 30;
const BATCH_REQUEST_TIMEOUT_SECS: u64 = 120;
const DEFAULT_REQUEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_HTTP_TIMEOUT_SECS";

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

fn batch_request_timeout() -> std::time::Duration {
    std::time::Duration::from_secs(BATCH_REQUEST_TIMEOUT_SECS)
}

/// Read the full body of a ureq response into a String.
pub fn read_body(mut body: ureq::Body) -> Result<String, String> {
    body.read_to_string().map_err(|e| format!("Failed to read response body: {e}"))
}

/// Build a `ureq::Agent` configured for Browser4 MCP calls.
pub fn make_client() -> Agent {
    let config = Agent::config_builder()
        .timeout_global(Some(default_request_timeout()))
        .build();
    Agent::new_with_config(config)
}

/// Build a `ureq::Agent` with an extended timeout for batch operations.
pub fn make_batch_client() -> Agent {
    let config = Agent::config_builder()
        .timeout_global(Some(batch_request_timeout()))
        .build();
    Agent::new_with_config(config)
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

fn format_http_error(status: u16, response_text: &str) -> String {
    let message = response_text.trim();
    if message.is_empty() {
        format!(
            "HTTP request failed with status {status} and an empty response body."
        )
    } else {
        format!("HTTP request failed with status {status}: {message}")
    }
}

/// Perform a REST request using the given agent.
fn send_rest_request(
    agent: &Agent,
    method: &str,
    url: &str,
    content_type: Option<&str>,
    body: Option<&str>,
) -> Result<String, String> {
    let response = match method {
        "GET" => agent.get(url).call(),
        "POST" => {
            let req = agent.post(url);
            let req = match content_type {
                Some(ct) => req.header("Content-Type", ct),
                None => req,
            };
            match body {
                Some(b) => req.send(b),
                None => req.send_empty(),
            }
        }
        _ => return Err(format!("Unsupported HTTP method: {method}")),
    };

    match response {
        Ok(resp) => {
            let response_text = read_body(resp.into_body())?;
            Ok(extract_http_text_payload(&response_text))
        }
        Err(ureq::Error::StatusCode(status)) => {
            Err(format_http_error(status, ""))
        }
        Err(err) => {
            Err(format!("HTTP request failed: {err}"))
        }
    }
}

/// Call an MCP tool on the Browser4 server.
///
/// Makes a `POST /mcp/call-tool` request and returns the text of the first
/// content block, or an error message from the server.
pub fn call_tool(
    agent: &Agent,
    base_url: &str,
    tool: &str,
    mut args: Value,
) -> Result<String, String> {
    normalize_refs(&mut args);

    let url = format!("{}/mcp/call-tool", base_url.trim_end_matches('/'));
    let body = json!({ "tool": tool, "arguments": args });

    let response = agent
        .post(&url)
        .content_type("application/json")
        .send_json(&body);

    match response {
        Ok(resp) => {
            let response_text = read_body(resp.into_body())?;

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
        Err(ureq::Error::StatusCode(status)) => {
            Err(format!(
                "HTTP request failed with status {status} [tool={tool}, endpoint={url}]"
            ))
        }
        Err(err) => {
            if err.to_string().to_ascii_lowercase().contains("timed out") {
                Err(format!(
                    "HTTP request timed out [tool={tool}, endpoint={url}]: {err}"
                ))
            } else {
                Err(format!(
                    "HTTP request failed [tool={tool}, endpoint={url}]: {err}"
                ))
            }
        }
    }
}

/// Call an MCP tool with a batch-specific agent (extended timeout).
pub fn call_tool_batch(
    agent: &Agent,
    base_url: &str,
    tool: &str,
    args: Value,
) -> Result<String, String> {
    call_tool(agent, base_url, tool, args)
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
pub fn submit_plain_command(
    agent: &Agent,
    base_url: &str,
    command: &str,
    async_mode: bool,
) -> Result<String, String> {
    call_tool(
        agent,
        base_url,
        "command_run",
        serde_json::json!({ "command": command, "async": async_mode }),
    )
}

/// Submit a swarm payload through `SwarmController.submit(payload)`.
pub fn submit_swarm_payload(
    agent: &Agent,
    base_url: &str,
    payload: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/swarm/submit");
    send_rest_request(
        agent,
        "POST",
        &url,
        Some("text/plain; charset=utf-8"),
        Some(payload),
    )
}

/// Submit a swarm X-SQL query through `SwarmController.query(query)`.
pub fn submit_swarm_query(
    agent: &Agent,
    base_url: &str,
    query: serde_json::Value,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/swarm/query");
    send_rest_request(
        agent,
        "POST",
        &url,
        Some("application/json; charset=utf-8"),
        Some(&query.to_string()),
    )
}

/// Read swarm task status through `SwarmController.getStatus(id)`.
pub fn get_swarm_status(
    agent: &Agent,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/swarm/{task_id}/status"));
    send_rest_request(agent, "GET", &url, None, None)
}

/// Read swarm task result through `SwarmController.getResult(id)`.
pub fn get_swarm_result(
    agent: &Agent,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/swarm/{task_id}/result"));
    send_rest_request(agent, "GET", &url, None, None)
}

/// Get the status of a command by its task ID via the MCP endpoint.
pub fn get_command_status(
    agent: &Agent,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    call_tool(
        agent,
        base_url,
        "command_status",
        serde_json::json!({ "id": task_id }),
    )
}

/// Get the result of a completed command by its task ID via the MCP endpoint.
pub fn get_command_result(
    agent: &Agent,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    call_tool(
        agent,
        base_url,
        "command_result",
        serde_json::json!({ "id": task_id }),
    )
}

/// Execute a batch of CLI-derived operations in a single backend request.
pub fn submit_batch_commands(
    agent: &Agent,
    base_url: &str,
    args: Value,
) -> Result<String, String> {
    call_tool_batch(
        agent,
        base_url,
        "command_batch",
        args,
    )
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
    }

    impl TimeoutEnvGuard {
        fn set(default_timeout_secs: &str) -> Self {
            let guard = Self {
                default_timeout: std::env::var(DEFAULT_REQUEST_TIMEOUT_ENV).ok(),
            };
            std::env::set_var(DEFAULT_REQUEST_TIMEOUT_ENV, default_timeout_secs);
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
            || lower.contains("timed out")
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
            format_http_error(404, ""),
            "HTTP request failed with status 404 and an empty response body."
        );
        assert_eq!(
            format_http_error(400, "bad request"),
            "HTTP request failed with status 400: bad request"
        );
    }
}
