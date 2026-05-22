//! HTTP helpers for calling Browser4 MCP tools and scrape REST endpoints.

use reqwest::Client;
use serde_json::{json, Value};

use crate::state::resolve_ref;

const DEFAULT_REQUEST_TIMEOUT_SECS: u64 = 30;
const BATCH_REQUEST_TIMEOUT_SECS: u64 = 120;

/// Build a `reqwest::Client` configured for Browser4 MCP calls.
pub fn make_client() -> Client {
    Client::builder()
        .timeout(std::time::Duration::from_secs(DEFAULT_REQUEST_TIMEOUT_SECS))
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
    format!("{}/{}", base_url.trim_end_matches('/'), path.trim_start_matches('/'))
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
    call_tool_with_timeout(client, base_url, tool, args, None).await
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
        .map_err(|e| format!("HTTP request failed: {e}"))?;

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

/// Submit a scrape payload through `ScrapeController.submit(payload)`.
pub async fn submit_scrape_payload(
    client: &Client,
    base_url: &str,
    payload: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, "/api/x/submit");
    send_rest_request(
        client
            .post(url)
            .header("Content-Type", "text/plain; charset=utf-8")
            .body(payload.to_string()),
    )
    .await
}

/// Read scrape task status through `ScrapeController.getStatus(id)`.
pub async fn get_scrape_status(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/x/{task_id}/status"));
    send_rest_request(client.get(url)).await
}

/// Read scrape task result through `ScrapeController.getResult(id)`.
pub async fn get_scrape_result(
    client: &Client,
    base_url: &str,
    task_id: &str,
) -> Result<String, String> {
    let url = build_endpoint_url(base_url, &format!("/api/x/{task_id}/result"));
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
        assert_eq!(extract_http_text_payload("\"co-task-1\""), "co-task-1");
    }

    #[test]
    fn test_extract_http_text_payload_preserves_plain_text() {
        assert_eq!(extract_http_text_payload("co-task-1\n"), "co-task-1");
    }

    #[test]
    fn test_extract_http_text_payload_minifies_json_object() {
        let payload = r#"{
            "id": "co-task-1",
            "isDone": false
        }"#;
        assert_eq!(
            extract_http_text_payload(payload),
            "{\"id\":\"co-task-1\",\"isDone\":false}"
        );
    }
}
