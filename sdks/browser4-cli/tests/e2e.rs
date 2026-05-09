#![allow(dead_code)]

//! End-to-end tests for the `browser4-cli` Rust binary.
//!
//! The scenarios run sequentially in a custom `harness = false` test target so
//! they can reuse the proven ordering without libtest starting multiple
//! Browser4 backends concurrently. A dedicated coverage check verifies that the
//! union of all tested commands plus the explicitly-excluded set equals the
//! full command list from [`browser4_cli::commands::all_commands`], and the
//! custom runner prints per-test timings to make slow cases easy to spot.
//!
//! # Running
//!
//! ```bash
//! cargo test --test e2e -- --nocapture
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_agent_task_commands
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_*
//! cargo test --test e2e -- --nocapture --scenario-from=test_e2e_mouse_and_dialog
//! cargo test --test e2e -- --nocapture --scenario-from=test_e2e_navigation_and_storage --scenario-limit=5
//! cargo test --test e2e -- --nocapture --failed
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_eval_command --fail-fast
//! ```
//!
//! The `--failed` selector reruns scenario names stored by the previous run in
//! `%TEMP%/browser4/browser4-cli/e2e/last-failed-scenarios.json`.
//!
//! The Browser4 service is resolved in this order:
//! 1. `BROWSER4_E2E_SERVICE_URL` environment variable – connect to an already-running
//!    service (Docker-friendly; no JAR is needed).
//! 2. `BROWSER4_E2E_SERVER_URL` environment variable – alias for the above.
//! 3. Otherwise, each local run lets `browser4-cli` auto-start the backend.
//!    By default, e2e forces the jar startup path (faster than Maven
//!    `spring-boot:run`). Set `BROWSER4_E2E_USE_MAVEN_STARTUP=true` to opt in to
//!    Maven startup checks.
//!
//! When running against an external Docker service, also set:
//! - `BROWSER4_E2E_FIXTURE_HOST` – hostname/IP the Browser4 container uses to
//!   reach the fixture HTTP server on the host (e.g. `host.docker.internal` or
//!   the Docker bridge gateway IP such as `172.17.0.1`). Defaults to `127.0.0.1`.

use base64::Engine as _;
use browser4_cli::commands::all_commands;
use browser4_cli::managed_processes::stop_browser4_server_forcibly;
use chrono::Local;
use std::collections::{BTreeSet, HashSet};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::thread::{sleep, JoinHandle};
use std::time::{Duration, Instant};

#[path = "e2e/scenarios/mod.rs"]
mod scenarios;

const BROWSER_PROFILE_MODE: &str = "SEQUENTIAL";
const OPEN_PROFILE_MODE_ARG: &str = "--profile-mode=SEQUENTIAL";
const USE_MAVEN_STARTUP_FLAG: &str = "--use-maven-startup";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const INTERACTIVE_PATH: &str = "/interactive";
const OTHER_PATH: &str = "/other";
const FORM_PATH: &str = "/form";
const INTERACTIVE_TITLE: &str = "Browser4 CLI Interactive Fixture";
const OTHER_TITLE: &str = "Browser4 CLI Other Fixture";
const FORM_TITLE: &str = "Browser4 CLI Form Fixture";
const ROOT_SEARCH_START_DIR_ENV: &str = "BROWSER4_CLI_INVOKE_DIR";
const USE_MAVEN_STARTUP_ENV: &str = "BROWSER4_E2E_USE_MAVEN_STARTUP";
const LAST_FAILED_SCENARIOS_FILE: &str = "last-failed-scenarios.json";

// ---------------------------------------------------------------------------
// Environment helpers
// ---------------------------------------------------------------------------

fn cli_binary() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_browser4-cli"))
}

/// Returns the external Browser4 service URL if one has been provided via
/// `BROWSER4_E2E_SERVICE_URL` (or its alias `BROWSER4_E2E_SERVER_URL`).
/// When this is `Some`, the test suite connects to the running service instead
/// of spawning its own JAR process.
fn external_service_url() -> Option<String> {
    std::env::var("BROWSER4_E2E_SERVICE_URL")
        .or_else(|_| std::env::var("BROWSER4_E2E_SERVER_URL"))
        .ok()
        .filter(|s| !s.is_empty())
}

/// Host name or IP that the Browser4 service (possibly inside Docker) should
/// use to reach the fixture HTTP server running on the test host.
/// Defaults to `127.0.0.1` (loopback, suitable for local runs).
fn fixture_host() -> String {
    std::env::var("BROWSER4_E2E_FIXTURE_HOST").unwrap_or_else(|_| "127.0.0.1".to_string())
}

fn use_maven_startup_for_local_server() -> bool {
    std::env::var(USE_MAVEN_STARTUP_ENV)
        .ok()
        .map(|value| {
            matches!(
                value.trim().to_ascii_lowercase().as_str(),
                "1" | "true" | "yes" | "on"
            )
        })
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// HTML fixtures
// ---------------------------------------------------------------------------

const INTERACTIVE_FIXTURE_FILE: &str = "mcp-tool-controller-interactive-fixture.html";
const OTHER_FIXTURE_FILE: &str = "mcp-tool-controller-other-fixture.html";
const FORM_FIXTURE_FILE: &str = "mcp-tool-controller-form-fixture.html";

fn load_html_fixture(file_name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..")
        .join("browser4-tests")
        .join("browser4-tests-common")
        .join("src")
        .join("main")
        .join("resources")
        .join("static")
        .join("b4")
        .join(file_name);

    fs::read_to_string(&path).unwrap_or_else(|error| {
        panic!(
            "failed to load HTML fixture {file_name} from {}: {error}",
            path.display()
        )
    })
}

// ---------------------------------------------------------------------------
// Free-port helper
// ---------------------------------------------------------------------------

fn find_free_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind on port 0 failed");
    listener.local_addr().unwrap().port()
}

// ---------------------------------------------------------------------------
// Minimal HTTP fixture server
// ---------------------------------------------------------------------------

struct FixtureServer {
    port: u16,
    /// Host advertised in fixture URLs that are *sent to* the Browser4 service.
    /// When the service runs inside Docker this must be the host's reachable
    /// address (e.g. `host.docker.internal` or the Docker bridge gateway IP).
    fixture_host: String,
    shutdown: Arc<AtomicBool>,
}

struct FixturePages {
    interactive_html: String,
    other_html: String,
    form_html: String,
}

impl FixtureServer {
    /// Start the fixture HTTP server.
    ///
    /// * `bind_addr` – network interface to listen on (`"127.0.0.1"` for
    ///   local-only, `"0.0.0.0"` when an external Docker service must reach it).
    /// * `fixture_host` – hostname/IP used in URLs handed to the Browser4
    ///   service (see [`fixture_host`]).
    fn start(bind_addr: &str, fixture_host: &str) -> Self {
        let listener = TcpListener::bind(format!("{}:0", bind_addr))
            .unwrap_or_else(|e| panic!("fixture server bind failed on {bind_addr}:0 – {e}"));
        let port = listener.local_addr().unwrap().port();
        let shutdown = Arc::new(AtomicBool::new(false));
        let flag = shutdown.clone();
        let pages = Arc::new(FixturePages {
            interactive_html: load_html_fixture(INTERACTIVE_FIXTURE_FILE),
            other_html: load_html_fixture(OTHER_FIXTURE_FILE),
            form_html: load_html_fixture(FORM_FIXTURE_FILE),
        });

        thread::spawn(move || {
            listener.set_nonblocking(true).ok();
            loop {
                if flag.load(Ordering::Relaxed) {
                    break;
                }
                match listener.accept() {
                    Ok((stream, _)) => {
                        let request_pages = pages.clone();
                        thread::spawn(move || serve_fixture_request(stream, request_pages));
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });

        Self {
            port,
            fixture_host: fixture_host.to_string(),
            shutdown,
        }
    }

    fn base_url(&self) -> String {
        format!("http://{}:{}", self.fixture_host, self.port)
    }
}

impl Drop for FixtureServer {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
    }
}

fn serve_fixture_request(mut stream: std::net::TcpStream, pages: Arc<FixturePages>) {
    let mut buf = vec![0u8; 8192];
    let n = match stream.read(&mut buf) {
        Ok(n) => n,
        Err(_) => return,
    };

    let request = std::str::from_utf8(&buf[..n]).unwrap_or("");
    let path = request
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .unwrap_or("/");

    let (status, content_type, body) = if path == INTERACTIVE_PATH || path == "/" {
        (
            "200 OK",
            "text/html; charset=utf-8",
            pages.interactive_html.clone(),
        )
    } else if path == OTHER_PATH {
        (
            "200 OK",
            "text/html; charset=utf-8",
            pages.other_html.clone(),
        )
    } else if path == FORM_PATH {
        (
            "200 OK",
            "text/html; charset=utf-8",
            pages.form_html.clone(),
        )
    } else {
        (
            "404 Not Found",
            "text/plain; charset=utf-8",
            "not found".to_string(),
        )
    };

    let response = format!(
        "HTTP/1.1 {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status,
        content_type,
        body.len(),
        body
    );
    let _ = stream.write_all(response.as_bytes());
}

// ---------------------------------------------------------------------------
// Mock Browser4 server for Agent/Collective E2E coverage
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
struct RecordedToolCall {
    tool: String,
    arguments: serde_json::Value,
}

#[derive(Clone, Debug, Default)]
struct MockBrowser4State {
    tool_calls: Vec<RecordedToolCall>,
    plain_commands: Vec<String>,
    status_queries: Vec<String>,
    result_queries: Vec<String>,
    next_agent_task_id: usize,
    next_collective_task_id: usize,
}

struct MockBrowser4Server {
    port: u16,
    shutdown: Arc<AtomicBool>,
    state: Arc<Mutex<MockBrowser4State>>,
}

impl MockBrowser4Server {
    fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("mock Browser4 server bind failed");
        let port = listener.local_addr().unwrap().port();
        let shutdown = Arc::new(AtomicBool::new(false));
        let state = Arc::new(Mutex::new(MockBrowser4State::default()));
        let flag = shutdown.clone();
        let shared_state = state.clone();

        thread::spawn(move || {
            listener.set_nonblocking(true).ok();
            loop {
                if flag.load(Ordering::Relaxed) {
                    break;
                }
                match listener.accept() {
                    Ok((stream, _)) => {
                        let request_state = shared_state.clone();
                        thread::spawn(move || serve_mock_browser4_request(stream, request_state));
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });

        Self {
            port,
            shutdown,
            state,
        }
    }

    fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    fn snapshot(&self) -> MockBrowser4State {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .clone()
    }
}

impl Drop for MockBrowser4Server {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
    }
}

fn serve_mock_browser4_request(mut stream: TcpStream, state: Arc<Mutex<MockBrowser4State>>) {
    let Some((method, path, body)) = read_http_request(&mut stream) else {
        return;
    };

    let route = path.split('?').next().unwrap_or(path.as_str());

    match (method.as_str(), route) {
        ("GET", "/actuator/health") => write_http_response(
            &mut stream,
            "200 OK",
            "application/json",
            r#"{"status":"UP"}"#,
        ),
        ("GET", "/mcp/tools") => write_http_response(
            &mut stream,
            "200 OK",
            "application/json",
            r#"["open_session","browser_navigate","agent_extract","agent_summarize"]"#,
        ),
        ("POST", "/mcp/call-tool") => {
            let payload: serde_json::Value =
                serde_json::from_slice(&body).expect("mock Browser4 tool payload must be JSON");
            let tool = payload
                .get("tool")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_string();
            let arguments = payload
                .get("arguments")
                .cloned()
                .unwrap_or_else(|| serde_json::json!({}));

            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .tool_calls
                .push(RecordedToolCall {
                    tool: tool.clone(),
                    arguments: arguments.clone(),
                });

            let text = match tool.as_str() {
                "open_session" => r#"{"sessionId":"collective-session-1"}"#.to_string(),
                "command_run" => {
                    let command = arguments
                        .get("command")
                        .and_then(|v| v.as_str())
                        .unwrap_or_default()
                        .trim()
                        .to_string();
                    let task_id = if command == "task missing llm key" {
                        state
                            .lock()
                            .expect("mock Browser4 state mutex poisoned")
                            .plain_commands
                            .push(command);
                        "agent-task-missing-llm".to_string()
                    } else {
                        let mut guard = state.lock().expect("mock Browser4 state mutex poisoned");
                        guard.plain_commands.push(command.clone());
                        if command.starts_with("http://") || command.starts_with("https://") {
                            guard.next_collective_task_id += 1;
                            format!("co-task-{}", guard.next_collective_task_id)
                        } else {
                            guard.next_agent_task_id += 1;
                            format!("agent-task-{}", guard.next_agent_task_id)
                        }
                    };
                    format!(r#""{}""#, task_id)
                }
                "command_status" => {
                    let task_id = arguments
                        .get("id")
                        .and_then(|v| v.as_str())
                        .unwrap_or_default()
                        .to_string();
                    state
                        .lock()
                        .expect("mock Browser4 state mutex poisoned")
                        .status_queries
                        .push(task_id.clone());
                    if task_id == "agent-task-missing-llm" {
                        serde_json::json!({
                            "id": task_id,
                            "status": "EXPECTATION_FAILED",
                            "statusCode": 417,
                            "processState": "done",
                            "message": "The LLM is not configured, see docs/config/llm/llm-config.md",
                        })
                        .to_string()
                    } else {
                        serde_json::json!({
                            "id": task_id,
                            "status": "RUNNING",
                        })
                        .to_string()
                    }
                }
                "command_result" => {
                    let task_id = arguments
                        .get("id")
                        .and_then(|v| v.as_str())
                        .unwrap_or_default();
                    state
                        .lock()
                        .expect("mock Browser4 state mutex poisoned")
                        .result_queries
                        .push(task_id.to_string());
                    format!("result for {task_id}")
                }
                "command_batch" => mock_command_batch_response(&arguments),
                "agent_extract" => {
                    r#"{"items":[{"title":"Mock Product","price":"$19.99"}]}"#.to_string()
                }
                "agent_summarize" => "Mock summary for #page-marker".to_string(),
                other => mock_browser_tool_text(other, &arguments),
            };

            let response = serde_json::json!({
                "content": [
                    {
                        "type": "text",
                        "text": text,
                    }
                ]
            })
            .to_string();
            write_http_response(&mut stream, "200 OK", "application/json", &response);
        }
        _ if method == "POST" && route == "/api/commands/plain" => {
            let command = String::from_utf8_lossy(&body).trim().to_string();
            let task_id = {
                let mut guard = state.lock().expect("mock Browser4 state mutex poisoned");
                guard.plain_commands.push(command.clone());
                if command.starts_with("http://") || command.starts_with("https://") {
                    guard.next_collective_task_id += 1;
                    format!("co-task-{}", guard.next_collective_task_id)
                } else {
                    guard.next_agent_task_id += 1;
                    format!("agent-task-{}", guard.next_agent_task_id)
                }
            };

            write_http_response(
                &mut stream,
                "200 OK",
                "application/json",
                &format!(r#""{}""#, task_id),
            );
        }
        _ if method == "GET"
            && route.starts_with("/api/commands/")
            && route.ends_with("/status") =>
        {
            let Some(task_id) = route
                .strip_prefix("/api/commands/")
                .and_then(|rest| rest.strip_suffix("/status"))
            else {
                write_http_response(&mut stream, "404 Not Found", "text/plain", "not found");
                return;
            };

            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .status_queries
                .push(task_id.to_string());

            let response = serde_json::json!({
                "id": task_id,
                "status": "RUNNING",
            })
            .to_string();
            write_http_response(&mut stream, "200 OK", "application/json", &response);
        }
        _ if method == "GET"
            && route.starts_with("/api/commands/")
            && route.ends_with("/result") =>
        {
            let Some(task_id) = route
                .strip_prefix("/api/commands/")
                .and_then(|rest| rest.strip_suffix("/result"))
            else {
                write_http_response(&mut stream, "404 Not Found", "text/plain", "not found");
                return;
            };

            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .result_queries
                .push(task_id.to_string());

            let response = format!("result for {task_id}");
            write_http_response(
                &mut stream,
                "200 OK",
                "text/plain; charset=utf-8",
                &response,
            );
        }
        _ => write_http_response(
            &mut stream,
            "404 Not Found",
            "text/plain; charset=utf-8",
            "not found",
        ),
    }
}

fn mock_browser_tool_text(tool: &str, arguments: &serde_json::Value) -> String {
    match tool {
        "browser_evaluate" => {
            let expression = arguments
                .get("expression")
                .and_then(|v| v.as_str())
                .unwrap_or_default();
            let target_ref = arguments.get("ref").and_then(|v| v.as_str());
            match (expression, target_ref) {
                ("document.title", None) => "Mock Browser4 Page".to_string(),
                ("element => element.textContent", Some(target)) => {
                    format!("Mock element text for {target}")
                }
                _ => "mock evaluation result".to_string(),
            }
        }
        "page_url" => "https://mock.browser4.local/current".to_string(),
        "page_title" => "Mock Browser4 Page".to_string(),
        "browser_snapshot" => "mock snapshot".to_string(),
        other => format!("mock response for {other}"),
    }
}

fn mock_command_batch_response(arguments: &serde_json::Value) -> String {
    let mut current_session_id = arguments
        .get("sessionId")
        .and_then(|value| value.as_str())
        .map(str::to_string);
    let mut results = Vec::new();

    for (index, step) in arguments
        .get("steps")
        .and_then(|value| value.as_array())
        .into_iter()
        .flatten()
        .enumerate()
    {
        let op = step
            .get("op")
            .and_then(|value| value.as_str())
            .unwrap_or_default();
        let result = match op {
            "open" => {
                let session_id = current_session_id
                    .clone()
                    .unwrap_or_else(|| "collective-session-1".to_string());
                let text = if current_session_id.is_some() {
                    format!("Session already open: {session_id}")
                } else {
                    format!("Session opened: {session_id}")
                };
                current_session_id = Some(session_id.clone());
                serde_json::json!({
                    "index": index,
                    "ok": true,
                    "sessionId": session_id,
                    "text": text,
                })
            }
            "close" => {
                current_session_id = None;
                serde_json::json!({
                    "index": index,
                    "ok": true,
                    "text": "Session closed.",
                })
            }
            "tool" => {
                let tool = step
                    .get("tool")
                    .and_then(|value| value.as_str())
                    .unwrap_or_default();
                let step_arguments = step
                    .get("arguments")
                    .cloned()
                    .unwrap_or_else(|| serde_json::json!({}));
                serde_json::json!({
                    "index": index,
                    "ok": true,
                    "text": mock_browser_tool_text(tool, &step_arguments),
                })
            }
            "snapshot" => serde_json::json!({
                "index": index,
                "ok": true,
                "pageUrl": "https://mock.browser4.local/current",
                "pageTitle": "Mock Browser4 Page",
                "snapshot": "mock snapshot",
            }),
            "screenshot" => serde_json::json!({
                "index": index,
                "ok": true,
                "screenshot": base64::engine::general_purpose::STANDARD.encode(b"mock screenshot"),
            }),
            _ => serde_json::json!({
                "index": index,
                "ok": false,
                "error": format!("Unsupported batch step op: {op}"),
            }),
        };
        results.push(result);
    }

    serde_json::json!({
        "sessionId": current_session_id,
        "failureCount": results.iter().filter(|result| result["ok"] != serde_json::json!(true)).count(),
        "stoppedOnError": false,
        "results": results,
    })
    .to_string()
}

fn read_http_request(stream: &mut TcpStream) -> Option<(String, String, Vec<u8>)> {
    stream.set_read_timeout(Some(Duration::from_secs(2))).ok();

    let mut buffer = Vec::new();
    let mut content_length = 0usize;
    let mut header_end = None;

    loop {
        let mut chunk = [0u8; 4096];
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => buffer.extend_from_slice(&chunk[..n]),
            Err(ref e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                if buffer.is_empty() {
                    return None;
                }
                continue;
            }
            Err(_) => return None,
        }

        if header_end.is_none() {
            header_end = buffer.windows(4).position(|window| window == b"\r\n\r\n");
            if let Some(end) = header_end {
                let headers = String::from_utf8_lossy(&buffer[..end]);
                content_length = headers
                    .lines()
                    .find_map(|line| {
                        line.split_once(':').and_then(|(name, value)| {
                            if name.eq_ignore_ascii_case("Content-Length") {
                                value.trim().parse::<usize>().ok()
                            } else {
                                None
                            }
                        })
                    })
                    .unwrap_or(0);
            }
        }

        if let Some(end) = header_end {
            let total_length = end + 4 + content_length;
            if buffer.len() >= total_length {
                break;
            }
        }
    }

    let end = header_end?;
    let headers = String::from_utf8_lossy(&buffer[..end]);
    let request_line = headers.lines().next()?;
    let mut parts = request_line.split_whitespace();
    let method = parts.next()?.to_string();
    let path = parts.next()?.to_string();
    let body_start = end + 4;
    let body_end = body_start + content_length;
    let body = buffer.get(body_start..body_end)?.to_vec();

    Some((method, path, body))
}

fn write_http_response(stream: &mut TcpStream, status: &str, content_type: &str, body: &str) {
    let response = format!(
        "HTTP/1.1 {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status,
        content_type,
        body.len(),
        body
    );
    let _ = stream.write_all(response.as_bytes());
}

// ---------------------------------------------------------------------------
// Browser4 backend service readiness
// ---------------------------------------------------------------------------

fn wait_for_health(base_url: &str, timeout_ms: u64) -> Result<(), String> {
    let health_url = format!("{}/actuator/health", base_url.trim_end_matches('/'));
    let tools_url = format!("{}/mcp/tools", base_url.trim_end_matches('/'));
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()
        .expect("reqwest blocking client build failed");

    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_error = String::from("unknown");

    while Instant::now() < deadline {
        match client.get(&health_url).send() {
            Ok(resp) => {
                let body = resp.text().unwrap_or_default();
                if body.contains("\"status\":\"UP\"") {
                    match client.get(&tools_url).send() {
                        Ok(tools_resp) => {
                            let tools_body = tools_resp.text().unwrap_or_default();
                            if tools_body.contains("open_session")
                                && tools_body.contains("browser_navigate")
                            {
                                return Ok(());
                            }
                            last_error = format!("MCP tools endpoint not ready: {tools_body}");
                        }
                        Err(e) => {
                            last_error = format!("MCP tools endpoint not ready: {e}");
                        }
                    }
                } else {
                    last_error = body;
                }
            }
            Err(e) => last_error = e.to_string(),
        }
        thread::sleep(Duration::from_secs(1));
    }

    Err(format!(
        "Browser4 did not become healthy within {}ms. Last response: {}",
        timeout_ms, last_error
    ))
}

fn is_browser4_healthy_now(base_url: &str) -> bool {
    let health_url = format!("{}/actuator/health", base_url.trim_end_matches('/'));
    let client = match reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(2))
        .build()
    {
        Ok(client) => client,
        Err(_) => return false,
    };

    match client.get(&health_url).send() {
        Ok(resp) => resp
            .text()
            .map(|body| body.contains("\"status\":\"UP\""))
            .unwrap_or(false),
        Err(_) => false,
    }
}

// ---------------------------------------------------------------------------
// CLI runner
// ---------------------------------------------------------------------------

struct CliRunResult {
    stdout: String,
    stderr: String,
    exit_code: i32,
}

#[derive(Clone, Debug)]
struct TimedStep {
    name: String,
    duration: Duration,
}

impl TimedStep {
    fn new(name: impl Into<String>, duration: Duration) -> Self {
        Self {
            name: name.into(),
            duration,
        }
    }
}

#[derive(Clone, Debug)]
struct TimingReport {
    name: String,
    total: Duration,
    steps: Vec<TimedStep>,
}

impl TimingReport {
    fn new(name: impl Into<String>, total: Duration, steps: Vec<TimedStep>) -> Self {
        Self {
            name: name.into(),
            total,
            steps,
        }
    }
}

#[derive(Clone, Debug)]
struct FailureDetail {
    phase: String,
    expected: String,
    actual: String,
    message: String,
}

#[derive(Clone, Debug)]
struct ScenarioOutcome {
    report: TimingReport,
    failures: Vec<FailureDetail>,
}

type CleanupJoinHandle = JoinHandle<Result<Vec<TimedStep>, String>>;

impl ScenarioOutcome {
    fn passed(&self) -> bool {
        self.failures.is_empty()
    }
}

/// Context for running CLI commands in isolation.
#[derive(Clone)]
struct E2ECtx {
    fixture_base_url: String,
    browser4_base_url: String,
    invocation_dir: PathBuf,
    use_maven_startup: bool,
    workspace_dir: PathBuf,
    state_dir: PathBuf,
    upload_file_path: PathBuf,
    step_timings: Vec<TimedStep>,
}

impl E2ECtx {
    fn interactive_url(&self) -> String {
        format!("{}{}", self.fixture_base_url, INTERACTIVE_PATH)
    }

    fn other_url(&self) -> String {
        format!("{}{}", self.fixture_base_url, OTHER_PATH)
    }

    fn form_url(&self) -> String {
        format!("{}{}", self.fixture_base_url, FORM_PATH)
    }

    fn clear_step_timings(&mut self) {
        self.step_timings.clear();
    }

    fn record_step(&mut self, name: impl Into<String>, duration: Duration) {
        self.step_timings.push(TimedStep::new(name, duration));
    }

    fn take_step_timings(&mut self) -> Vec<TimedStep> {
        std::mem::take(&mut self.step_timings)
    }
}

struct E2ETestResources {
    _temp_dir: tempfile::TempDir,
    _fixture: FixtureServer,
    /// `true` when the Browser4 service was provided externally via
    /// `BROWSER4_E2E_SERVICE_URL`. In this mode the suite never starts or
    /// restarts the server process.
    external_service: bool,
    /// Tracks whether this harness has already started the local Browser4
    /// server on `ctx.browser4_base_url`. Once started, later scenarios may
    /// legitimately reuse the same healthy process without reprinting startup
    /// diagnostics.
    local_browser4_started: bool,
    /// Deferred Browser4 cleanup running in the background, if any.
    pending_cleanup: Option<(String, CleanupJoinHandle)>,
    ctx: E2ECtx,
}

impl E2ETestResources {
    fn ensure_browser4(&mut self) -> Vec<TimedStep> {
        let mut steps = Vec::new();
        if self.external_service {
            let started_at = Instant::now();
            wait_for_health(&self.ctx.browser4_base_url, 120_000)
                .expect("Browser4 did not become healthy in time");
            steps.push(TimedStep::new(
                "browser4 service ready wait (external)",
                started_at.elapsed(),
            ));
            return steps;
        }

        let was_healthy_before = is_browser4_healthy_now(&self.ctx.browser4_base_url);
        let started_at = Instant::now();
        let startup_result =
            run_cli_process_with_live_output(&self.ctx, &["open", OPEN_PROFILE_MODE_ARG]);
        let startup_log_hint = format_browser4_startup_log_hint(&startup_result.stderr);
        let started_via_maven = startup_result
            .stderr
            .contains("Starting server via Maven spring-boot:run");
        let expect_maven_startup = self.ctx.use_maven_startup;
        assert_eq!(
            startup_result.exit_code, 0,
            "Expected CLI-managed Browser4 startup to succeed.{}\nstdout:\n{}\nstderr:\n{}",
            startup_log_hint, startup_result.stdout, startup_result.stderr,
        );
        if !self.local_browser4_started {
            if !was_healthy_before {
                if expect_maven_startup {
                    assert!(
                        started_via_maven,
                        "Expected local e2e startup to use Maven spring-boot:run when {USE_MAVEN_STARTUP_ENV}=true.{}\nstdout:\n{}\nstderr:\n{}",
                        startup_log_hint,
                        startup_result.stdout,
                        startup_result.stderr,
                    );
                } else {
                    assert!(
                        !started_via_maven,
                        "Expected local e2e startup to default to jar fallback (set {USE_MAVEN_STARTUP_ENV}=true to opt in to Maven).{}\nstdout:\n{}\nstderr:\n{}",
                        startup_log_hint,
                        startup_result.stdout,
                        startup_result.stderr,
                    );
                    assert_root_search_log_contains_invocation_dir(
                        &startup_result.stderr,
                        &self.ctx.invocation_dir,
                    );
                }
                assert!(
                    startup_result.stderr.contains("Browser4 startup log:"),
                    "Expected startup diagnostics to include the Browser4 startup log path.{}\nstdout:\n{}\nstderr:\n{}",
                    startup_log_hint,
                    startup_result.stdout,
                    startup_result.stderr,
                );
            }
            self.local_browser4_started = true;
            let step_name = if started_via_maven {
                "browser4 cli startup trigger"
            } else {
                "browser4 cli readiness probe"
            };
            steps.push(TimedStep::new(step_name, started_at.elapsed()));
        } else {
            if started_via_maven {
                assert!(
                    startup_result.stderr.contains("Browser4 startup log:"),
                    "Expected startup diagnostics to include the Browser4 startup log path when Browser4 restarts.{}\nstdout:\n{}\nstderr:\n{}",
                    startup_log_hint,
                    startup_result.stdout,
                    startup_result.stderr,
                );
                steps.push(TimedStep::new(
                    "browser4 cli startup trigger",
                    started_at.elapsed(),
                ));
            } else {
                steps.push(TimedStep::new(
                    "browser4 cli readiness probe",
                    started_at.elapsed(),
                ));
            }
        }

        let started_at = Instant::now();
        wait_for_health(&self.ctx.browser4_base_url, 120_000).unwrap_or_else(|error| {
            panic!(
                "Browser4 did not become healthy in time.{}\nhealth error: {}\nstartup stdout:\n{}\nstartup stderr:\n{}",
                startup_log_hint,
                error,
                startup_result.stdout,
                startup_result.stderr,
            )
        });
        steps.push(TimedStep::new(
            "browser4 service ready wait",
            started_at.elapsed(),
        ));

        // println!("end ensuring browser4");

        steps
    }

    fn restart_browser4(&mut self) -> Vec<TimedStep> {
        let mut steps = Vec::new();
        // Kill any lingering Chrome processes from the previous server before
        // starting a fresh one.  Without this, the new Java server may see
        // stale CDP browser contexts, leading to intermittent
        // "Cannot find context with specified id" errors.
        let cleanup_started_at = Instant::now();
        stop_browser4_server_forcibly();
        self.local_browser4_started = false;
        steps.push(TimedStep::new(
            "browser4 pre-restart cleanup",
            cleanup_started_at.elapsed(),
        ));
        steps.extend(self.ensure_browser4());
        steps
    }

    fn join_pending_cleanup_if_any(&mut self) -> Result<Vec<TimedStep>, String> {
        let Some((origin, handle)) = self.pending_cleanup.take() else {
            return Ok(Vec::new());
        };

        let started_at = Instant::now();
        let mut steps = vec![TimedStep::new(
            format!("wait for async cleanup from {origin}"),
            started_at.elapsed(),
        )];

        let cleanup_result = handle
            .join()
            .map_err(|payload| panic_payload_to_string(payload.as_ref()))
            .map_err(|error| format!("Async Browser4 cleanup from '{origin}' panicked: {error}"))?;

        match cleanup_result {
            Ok(mut cleanup_steps) => {
                if !self.external_service {
                    self.local_browser4_started = true;
                }
                steps.append(&mut cleanup_steps);
                Ok(steps)
            }
            Err(error) => Err(format!(
                "Async Browser4 cleanup from '{origin}' failed:\n{error}"
            )),
        }
    }
}

/// Run `browser4-cli --server=<url> <args...>` in the workspace dir with the isolated state dir.
fn run_cli_process(ctx: &E2ECtx, args: &[&str]) -> CliRunResult {
    run_cli_process_internal(ctx, args, None, false)
}

fn extract_browser4_startup_log_path(stderr: &str) -> Option<&str> {
    stderr.lines().find_map(|line| {
        line.trim()
            .strip_prefix("Browser4 startup log:")
            .map(str::trim)
            .filter(|path| !path.is_empty())
    })
}

fn format_browser4_startup_log_hint(stderr: &str) -> String {
    extract_browser4_startup_log_path(stderr)
        .map(|path| format!("\nStartup log: {path}"))
        .unwrap_or_default()
}

fn assert_root_search_log_contains_invocation_dir(stderr: &str, invocation_dir: &Path) {
    let normalized_stderr = stderr.replace("\\\\", "/").replace('\\', "/");
    let invocation_dir_text = invocation_dir
        .to_string_lossy()
        .replace("\\\\", "/")
        .replace('\\', "/");
    let invocation_dir_suffix = "sdks/browser4-cli";

    assert!(
        normalized_stderr.contains("Finding browser4 root from"),
        "Expected Browser4 root-search diagnostics in stderr.\nstderr:\n{}",
        stderr
    );
    assert!(
        normalized_stderr.contains(&invocation_dir_text)
            || normalized_stderr.contains(invocation_dir_suffix),
        "Expected Browser4 root-search to include invocation dir '{}' (or suffix '{}').\nstderr:\n{}",
        invocation_dir.display(),
        invocation_dir_suffix,
        stderr
    );
}

fn run_cli_process_with_live_output(ctx: &E2ECtx, args: &[&str]) -> CliRunResult {
    run_cli_process_internal(ctx, args, None, true)
}

fn run_cli_process_with_stdin(
    ctx: &E2ECtx,
    args: &[&str],
    stdin_payload: Option<&str>,
) -> CliRunResult {
    run_cli_process_internal(ctx, args, stdin_payload, false)
}

fn run_cli_process_internal(
    ctx: &E2ECtx,
    args: &[&str],
    stdin_payload: Option<&str>,
    stream_output: bool,
) -> CliRunResult {
    let server_arg = format!("--server={}", ctx.browser4_base_url);
    let mut full_args: Vec<&str> = vec![server_arg.as_str()];
    if ctx.use_maven_startup {
        full_args.push(USE_MAVEN_STARTUP_FLAG);
    }
    full_args.extend_from_slice(args);

    let mut command = Command::new(cli_binary());
    command
        .args(&full_args)
        .current_dir(&ctx.workspace_dir)
        .env("BROWSER4_CLI_STATE_DIR", &ctx.state_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    // Always anchor Browser4.jar root search to the CLI launch directory,
    // not the isolated temporary workspace used for test artifacts.
    command.env(ROOT_SEARCH_START_DIR_ENV, &ctx.invocation_dir);

    let output = if let Some(payload) = stdin_payload {
        let mut child = command
            .stdin(Stdio::piped())
            .spawn()
            .expect("failed to spawn browser4-cli process");
        child
            .stdin
            .as_mut()
            .expect("stdin pipe should be available")
            .write_all(payload.as_bytes())
            .expect("failed to write stdin payload");
        drop(child.stdin.take());
        wait_for_cli_output(child, &full_args, stream_output)
    } else {
        let child = command
            .stdin(Stdio::null())
            .spawn()
            .expect("failed to spawn browser4-cli process");
        wait_for_cli_output(child, &full_args, stream_output)
    };

    CliRunResult {
        stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
        exit_code: output.status.code().unwrap_or(-1),
    }
}

const OUTPUT_COLLECTOR_DRAIN_TIMEOUT: Duration = Duration::from_secs(2);

fn wait_for_cli_output(
    mut child: Child,
    full_args: &[&str],
    stream_output: bool,
) -> std::process::Output {
    let stdout_handle = spawn_output_collector(
        child
            .stdout
            .take()
            .expect("stdout pipe should be available"),
        "stdout",
        stream_output,
    );
    let stderr_handle = spawn_output_collector(
        child
            .stderr
            .take()
            .expect("stderr pipe should be available"),
        "stderr",
        stream_output,
    );
    let timeout = cli_process_timeout(full_args);
    let started_at = Instant::now();
    let mut next_progress_log_at = Duration::from_secs(15);

    let status = loop {
        if let Some(status) = child
            .try_wait()
            .expect("failed to inspect browser4-cli process")
        {
            break status;
        }

        let elapsed = started_at.elapsed();
        if elapsed >= timeout {
            let _ = child.kill();
            let status = child
                .wait()
                .expect("failed to wait for timed out browser4-cli process");
            let mut stdout = finish_output_collector(stdout_handle, "stdout", full_args);
            let mut stderr = finish_output_collector(stderr_handle, "stderr", full_args);
            if !stderr.ends_with('\n') && !stderr.is_empty() {
                stderr.push('\n');
            }
            stderr.push_str(&format!(
                "Timed out after {}s waiting for browser4-cli command: {}\n",
                timeout.as_secs(),
                full_args.join(" ")
            ));
            return std::process::Output {
                status,
                stdout: std::mem::take(&mut stdout).into_bytes(),
                stderr: std::mem::take(&mut stderr).into_bytes(),
            };
        }

        if stream_output && elapsed >= next_progress_log_at {
            eprintln!(
                "[browser4-cli wait] still running after {}s: {}",
                elapsed.as_secs(),
                full_args.join(" ")
            );
            next_progress_log_at += Duration::from_secs(15);
        }

        thread::sleep(Duration::from_millis(200));
    };

    let stdout = finish_output_collector(stdout_handle, "stdout", full_args);
    let stderr = finish_output_collector(stderr_handle, "stderr", full_args);
    let session_suffix = extract_session_id_from_cli_output(&stdout)
        .map(|session_id| format!(" | sessionId={session_id}"))
        .unwrap_or_default();

    println!(
        "browser4-cli command completed in {}s with exit code {}: {}{}",
        started_at.elapsed().as_secs(),
        status.code().unwrap_or(-1),
        full_args.join(" "),
        session_suffix
    );

    std::process::Output {
        status,
        stdout: stdout.into_bytes(),
        stderr: stderr.into_bytes(),
    }
}

fn extract_session_id_from_cli_output(stdout: &str) -> Option<String> {
    ["Session opened:", "Session already open:"]
        .into_iter()
        .find_map(|prefix| {
            stdout
                .lines()
                .find_map(|line| line.trim().strip_prefix(prefix))
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_string)
        })
}

struct OutputCollector {
    handle: JoinHandle<()>,
    buffer: Arc<Mutex<String>>,
}

fn spawn_output_collector<R>(
    reader: R,
    stream_name: &'static str,
    stream_output: bool,
) -> OutputCollector
where
    R: Read + Send + 'static,
{
    let buffer = Arc::new(Mutex::new(String::new()));
    let buffer_for_thread = Arc::clone(&buffer);

    let handle = thread::spawn(move || {
        let mut reader = BufReader::new(reader);
        let mut line = String::new();

        loop {
            line.clear();
            let bytes_read = reader
                .read_line(&mut line)
                .expect("failed to read browser4-cli child output");
            if bytes_read == 0 {
                break;
            }

            if stream_output {
                match stream_name {
                    "stderr" => eprint!("[browser4-cli stderr] {line}"),
                    _ => print!("[browser4-cli stdout] {line}"),
                }
            }

            buffer_for_thread
                .lock()
                .expect("output collector buffer lock poisoned")
                .push_str(&line);
        }
    });

    OutputCollector { handle, buffer }
}

fn finish_output_collector(
    collector: OutputCollector,
    stream_name: &str,
    full_args: &[&str],
) -> String {
    let started_at = Instant::now();
    while !collector.handle.is_finished() && started_at.elapsed() < OUTPUT_COLLECTOR_DRAIN_TIMEOUT {
        thread::sleep(Duration::from_millis(25));
    }

    if collector.handle.is_finished() {
        collector
            .handle
            .join()
            .unwrap_or_else(|_| panic!("failed to join browser4-cli {stream_name} collector"));
        return collector
            .buffer
            .lock()
            .expect("output collector buffer lock poisoned")
            .clone();
    }

    let mut output = collector
        .buffer
        .lock()
        .expect("output collector buffer lock poisoned")
        .clone();
    if !output.ends_with('\n') && !output.is_empty() {
        output.push('\n');
    }
    output.push_str(&format!(
        "[test harness] Timed out draining browser4-cli {stream_name} after process exit for command: {}\n",
        full_args.join(" ")
    ));
    output
}

fn cli_process_timeout(full_args: &[&str]) -> Duration {
    if let Ok(raw) = std::env::var("BROWSER4_E2E_CLI_TIMEOUT_SECS") {
        if let Ok(seconds) = raw.trim().parse::<u64>() {
            if seconds > 0 {
                return Duration::from_secs(seconds);
            }
        }
    }

    if full_args.iter().any(|arg| *arg == "list") {
        Duration::from_secs(240)
    } else {
        Duration::from_secs(120)
    }
}

fn truncate_timing_label(text: &str, max_chars: usize) -> String {
    let char_count = text.chars().count();
    if char_count <= max_chars {
        return text.to_string();
    }

    let mut truncated = text
        .chars()
        .take(max_chars.saturating_sub(1))
        .collect::<String>();
    truncated.push('…');
    truncated
}

fn render_timing_arg(arg: &str) -> String {
    if arg.chars().any(char::is_whitespace) || arg.contains('"') || arg.contains('\'') {
        format!("{arg:?}")
    } else {
        arg.to_string()
    }
}

fn format_cli_step_label(args: &[&str], stdin_payload: bool, expects_failure: bool) -> String {
    let rendered = args
        .iter()
        .map(|arg| render_timing_arg(arg))
        .collect::<Vec<_>>()
        .join(" ");
    let mut label = String::from("cli ");
    if expects_failure {
        label.push_str("(expect failure) ");
    }
    label.push_str(&truncate_timing_label(&rendered, 120));
    if stdin_payload {
        label.push_str(" [stdin]");
    }
    label
}

fn format_eval_step_label(expression: &str) -> String {
    format!("cli eval {}", truncate_timing_label(expression.trim(), 96))
}

fn run_checked_cli_process(ctx: &E2ECtx, args: &[&str]) -> CliRunResult {
    let result = run_cli_process_with_retry(ctx, args);
    assert_eq!(
        result.exit_code, 0,
        "Command {:?} failed (exit={}):\nstdout:\n{}\nstderr:\n{}",
        args, result.exit_code, result.stdout, result.stderr
    );
    result
}

fn run_checked_cli_process_with_stdin(
    ctx: &E2ECtx,
    args: &[&str],
    stdin_payload: &str,
) -> CliRunResult {
    let result = run_cli_process_with_retry_and_stdin(ctx, args, stdin_payload);
    assert_eq!(
        result.exit_code, 0,
        "Command {:?} failed (exit={}):\nstdout:\n{}\nstderr:\n{}",
        args, result.exit_code, result.stdout, result.stderr
    );
    result
}

fn run_checked_cli_process_expecting_failure(
    ctx: &E2ECtx,
    args: &[&str],
    pattern: &str,
) -> CliRunResult {
    let result = run_cli_process_with_retry(ctx, args);
    assert_ne!(
        result.exit_code, 0,
        "Expected command {:?} to fail, but it exited with 0.\nstdout:\n{}\nstderr:\n{}",
        args, result.stdout, result.stderr
    );
    let combined = format!("{}\n{}", result.stdout, result.stderr);
    assert!(
        combined.contains(pattern),
        "Expected output to contain '{pattern}', but got:\n{combined}"
    );
    result
}

/// Run a command, asserting it succeeds (exit code 0).
fn run_command<'a>(ctx: &mut E2ECtx, args: &[&'a str]) -> CliRunResult {
    let started_at = Instant::now();
    let result = run_checked_cli_process(ctx, args);
    ctx.record_step(
        format_cli_step_label(args, false, false),
        started_at.elapsed(),
    );
    result
}

fn run_command_with_stdin(ctx: &mut E2ECtx, args: &[&str], stdin_payload: &str) -> CliRunResult {
    let started_at = Instant::now();
    let result = run_checked_cli_process_with_stdin(ctx, args, stdin_payload);
    ctx.record_step(
        format_cli_step_label(args, true, false),
        started_at.elapsed(),
    );
    result
}

/// Run a command, asserting it fails (exit code != 0) and that the combined
/// stdout+stderr contains `pattern`.
fn run_command_expecting_failure(ctx: &mut E2ECtx, args: &[&str], pattern: &str) -> CliRunResult {
    let started_at = Instant::now();
    let result = run_checked_cli_process_expecting_failure(ctx, args, pattern);
    ctx.record_step(
        format_cli_step_label(args, false, true),
        started_at.elapsed(),
    );
    result
}

fn run_command_allowing_failure<'a>(ctx: &mut E2ECtx, args: &[&'a str]) -> CliRunResult {
    let started_at = Instant::now();
    let result = run_cli_process_with_retry(ctx, args);
    ctx.record_step(
        format_cli_step_label(args, false, result.exit_code != 0),
        started_at.elapsed(),
    );
    result
}

fn run_cli_process_with_retry(ctx: &E2ECtx, args: &[&str]) -> CliRunResult {
    run_cli_process_with_retry_and_stdin(ctx, args, "")
}

fn run_cli_process_with_retry_and_stdin(
    ctx: &E2ECtx,
    args: &[&str],
    stdin_payload: &str,
) -> CliRunResult {
    let max_attempts = 5;
    let mut attempt = 0;
    let use_stdin = !stdin_payload.is_empty();

    loop {
        attempt += 1;
        let result = if use_stdin {
            run_cli_process_with_stdin(ctx, args, Some(stdin_payload))
        } else {
            run_cli_process(ctx, args)
        };
        if attempt >= max_attempts || !is_transient_retryable_failure(&result) {
            return result;
        }
        let delay_secs = (attempt as u64) * 2;
        thread::sleep(Duration::from_secs(delay_secs));
    }
}

fn is_transient_retryable_failure(result: &CliRunResult) -> bool {
    if result.exit_code == 0 {
        return false;
    }

    let combined = format!("{}\n{}", result.stdout, result.stderr).to_lowercase();
    combined.contains("http request failed: error sending request for url")
        || combined.contains("connection refused")
        || combined.contains("tcp connect error")
        || combined.contains("failed to launch browser")
        || combined.contains("createtab")
        || combined.contains("cannot find context")
        || combined.contains("createdevtools")
        || combined.contains("browser connection lost")
        || combined.contains("browser unavailable")
        || combined.contains("enableapiagents")
        || combined.contains("failed to enable cdt agents")
}

// ---------------------------------------------------------------------------
// Output helpers
// ---------------------------------------------------------------------------

/// Strip the auto-appended `### Page` snapshot block from CLI stdout.
fn strip_snapshot_output(stdout: &str) -> String {
    let marker = "\n### Page";
    let without = match stdout.find(marker) {
        Some(idx) => &stdout[..idx],
        None => stdout,
    };
    without
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && *l != "ensuring server...")
        .collect::<Vec<_>>()
        .join("\n")
}

fn extract_submitted_task_id(output: &str) -> String {
    output
        .lines()
        .find_map(|line| line.trim().strip_prefix("Task submitted:"))
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| panic!("Expected 'Task submitted:' line in output:\n{output}"))
        .to_string()
}

/// Extract the zero-based tab index for the given URL from `tab-list` output.
fn extract_tab_index(output: &str, url: &str) -> usize {
    static RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let re = RE.get_or_init(|| {
        regex::Regex::new(r#"url\s*[:=]\s*"?([^",}\s]+)"?"#).expect("tab url regex compile")
    });

    let urls: Vec<String> = re
        .captures_iter(output)
        .map(|cap| cap[1].to_string())
        .collect();

    urls.iter()
        .position(|candidate| candidate == url)
        .unwrap_or_else(|| panic!("Could not find tab index for '{}' in:\n{}", url, output))
}

// ---------------------------------------------------------------------------
// State helpers
// ---------------------------------------------------------------------------

fn state_file_path(state_dir: &Path, session_name: Option<&str>) -> PathBuf {
    match session_name {
        Some(name) => state_dir.join("sessions").join(format!("{name}.json")),
        None => state_dir.join("cli-state.json"),
    }
}

fn read_persisted_session_id_for_session(state_dir: &Path, session_name: Option<&str>) -> String {
    let path = state_file_path(state_dir, session_name);
    let raw = fs::read_to_string(&path)
        .unwrap_or_else(|_| panic!("persisted state file not found: {}", path.display()));
    let parsed: serde_json::Value =
        serde_json::from_str(&raw).expect("persisted state file is not valid JSON");
    parsed["sessionId"]
        .as_str()
        .expect("no sessionId in persisted state file")
        .to_string()
}

fn read_persisted_session_id(state_dir: &Path) -> String {
    read_persisted_session_id_for_session(state_dir, None)
}

fn eval_text(ctx: &mut E2ECtx, expression: &str) -> String {
    let started_at = Instant::now();
    let result = run_checked_cli_process(ctx, &["eval", expression]);
    ctx.record_step(format_eval_step_label(expression), started_at.elapsed());
    strip_snapshot_output(&result.stdout)
}

fn eval_text_for_target(ctx: &mut E2ECtx, expression: &str, target: &str) -> String {
    let started_at = Instant::now();
    let result = run_checked_cli_process(ctx, &["eval", expression, target]);
    ctx.record_step(
        format!(
            "{} [{}]",
            format_eval_step_label(expression),
            truncate_timing_label(target.trim(), 32)
        ),
        started_at.elapsed(),
    );
    strip_snapshot_output(&result.stdout)
}

fn read_interactive_state(ctx: &mut E2ECtx) -> serde_json::Value {
    let text = run_checked_cli_process(
        ctx,
        &["eval", "document.getElementById('state-log').textContent"],
    );
    let text = strip_snapshot_output(&text.stdout);
    serde_json::from_str(text.trim()).unwrap_or(serde_json::Value::Null)
}

fn key_event_count(state: &serde_json::Value) -> usize {
    state["keyEvents"]
        .as_array()
        .map_or(0, |events| events.len())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum WaitForStateFailureMode {
    ReturnError,
    AbortScenario,
}

fn format_wait_for_state_timeout_failure(
    timeout_ms: u64,
    failure_message: &str,
    state: &serde_json::Value,
) -> String {
    let parse_hint = if state.is_null() {
        "\nLast state could not be parsed from #state-log and was read as JSON null."
    } else {
        ""
    };
    format!(
        "{failure_message}. Timed out after {timeout_ms}ms waiting for interactive state.{parse_hint}\nLast state:\n{state:#?}"
    )
}

fn wait_for_state<F>(
    ctx: &mut E2ECtx,
    predicate: F,
    timeout_ms: u64,
    failure_message: &str,
    failure_mode: WaitForStateFailureMode,
) -> Result<serde_json::Value, String>
where
    F: Fn(&serde_json::Value) -> bool,
{
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    while Instant::now() < deadline {
        let state = read_interactive_state(ctx);
        if predicate(&state) {
            ctx.record_step(
                format!(
                    "wait for state {} (timeout={}ms)",
                    truncate_timing_label(failure_message.trim(), 64),
                    timeout_ms
                ),
                started_at.elapsed(),
            );
            return Ok(state);
        }
        thread::sleep(Duration::from_millis(300));
    }
    let state = read_interactive_state(ctx);
    let error = format_wait_for_state_timeout_failure(timeout_ms, failure_message, &state);
    match failure_mode {
        WaitForStateFailureMode::ReturnError => Err(error),
        WaitForStateFailureMode::AbortScenario => panic!("{error}"),
    }
}

fn wait_for_state_or_abort<F>(
    ctx: &mut E2ECtx,
    predicate: F,
    timeout_ms: u64,
    failure_message: &str,
) -> serde_json::Value
where
    F: Fn(&serde_json::Value) -> bool,
{
    wait_for_state(
        ctx,
        predicate,
        timeout_ms,
        failure_message,
        WaitForStateFailureMode::AbortScenario,
    )
    .unwrap_or_else(|error| panic!("{error}"))
}

fn wait_for_state_or_return_error<F>(
    ctx: &mut E2ECtx,
    predicate: F,
    timeout_ms: u64,
    failure_message: &str,
) -> Result<serde_json::Value, String>
where
    F: Fn(&serde_json::Value) -> bool,
{
    wait_for_state(
        ctx,
        predicate,
        timeout_ms,
        failure_message,
        WaitForStateFailureMode::ReturnError,
    )
}

fn assume_wait_for_state<F>(ctx: &mut E2ECtx, predicate: F, timeout_ms: u64, failure_message: &str)
where
    F: Fn(&serde_json::Value) -> bool,
{
    if let Err(error) = wait_for_state_or_return_error(ctx, predicate, timeout_ms, failure_message)
    {
        eprintln!("[assumption] {error}");
    }
}

fn wait_for_eval_text(
    ctx: &mut E2ECtx,
    expression: &str,
    expected: &str,
    timeout_ms: u64,
    failure_message: &str,
) {
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_value = String::new();

    while Instant::now() < deadline {
        let result = run_checked_cli_process(ctx, &["eval", expression]);
        last_value = strip_snapshot_output(&result.stdout);
        println!(
            "eval expression: {} \nlast_value:\n>>>{}<<<",
            expression, last_value
        );
        if last_value == expected {
            ctx.record_step(
                format!(
                    "wait for eval {} == {} (timeout={}ms)",
                    truncate_timing_label(expression.trim(), 48),
                    truncate_timing_label(expected.trim(), 32),
                    timeout_ms
                ),
                started_at.elapsed(),
            );
            return;
        }
        thread::sleep(Duration::from_millis(300));
    }

    println!("{failure_message}. Expected '{expected}', got '{last_value}'");
}

// ---------------------------------------------------------------------------
// Per-test isolation helper
// ---------------------------------------------------------------------------

fn reset_cli_artifacts(ctx: &mut E2ECtx) {
    let started_at = Instant::now();
    let _ = fs::remove_dir_all(&ctx.state_dir);
    fs::create_dir_all(&ctx.state_dir).ok();
    let _ = fs::remove_dir_all(ctx.workspace_dir.join(".browser4-cli"));
    ctx.record_step("reset CLI artifacts", started_at.elapsed());
}

fn e2e_temp_root_dir() -> PathBuf {
    std::env::temp_dir()
        .join("browser4")
        .join("browser4-cli")
        .join("e2e")
}

fn last_failed_scenarios_file_path() -> PathBuf {
    e2e_temp_root_dir().join(LAST_FAILED_SCENARIOS_FILE)
}

fn save_last_failed_scenarios(names: impl IntoIterator<Item = String>) {
    let path = last_failed_scenarios_file_path();
    let parent = path
        .parent()
        .expect("failed-scenarios path should always have a parent directory");
    fs::create_dir_all(parent).unwrap_or_else(|error| {
        panic!(
            "failed to create failed-scenarios directory {}: {error}",
            parent.display()
        )
    });

    let unique_sorted: BTreeSet<String> = names
        .into_iter()
        .map(|name| name.trim().to_string())
        .filter(|name| !name.is_empty())
        .collect();
    let payload = serde_json::to_string_pretty(&unique_sorted.into_iter().collect::<Vec<_>>())
        .expect("failed to serialize failed-scenarios JSON");
    fs::write(&path, format!("{payload}\n")).unwrap_or_else(|error| {
        panic!(
            "failed to write failed scenarios to {}: {error}",
            path.display()
        )
    });
}

fn load_last_failed_scenarios() -> Vec<String> {
    let path = last_failed_scenarios_file_path();
    if !path.exists() {
        return Vec::new();
    }

    let raw = match fs::read_to_string(&path) {
        Ok(raw) => raw,
        Err(error) => {
            eprintln!(
                "[warn] failed to read last failed scenarios from {}: {error}",
                path.display()
            );
            return Vec::new();
        }
    };

    match serde_json::from_str::<Vec<String>>(&raw) {
        Ok(entries) => entries
            .into_iter()
            .map(|name| name.trim().to_string())
            .filter(|name| !name.is_empty())
            .collect(),
        Err(error) => {
            eprintln!(
                "[warn] failed to parse last failed scenarios JSON from {}: {error}",
                path.display()
            );
            Vec::new()
        }
    }
}

fn create_e2e_test_resources() -> E2ETestResources {
    let service_url = external_service_url();
    let is_external = service_url.is_some();
    let use_maven_startup = use_maven_startup_for_local_server();
    assert!(
        cli_binary().exists(),
        "CLI binary not found at {:?}. Run `cargo build` first.",
        cli_binary()
    );

    // Bind to 0.0.0.0 when running against an external Docker service so the
    // container can reach the fixture HTTP server on the host machine.
    // NOTE: 0.0.0.0 temporarily exposes the fixture server on all interfaces for
    // the duration of the test run; this is intentional and required for Docker
    // containers to connect back to the host, but should only occur in CI where
    // the runner is not exposed to untrusted networks.
    let bind_addr = if is_external { "0.0.0.0" } else { "127.0.0.1" };
    let fhost = fixture_host();
    let fixture = FixtureServer::start(bind_addr, &fhost);
    let fixture_base_url = fixture.base_url();

    let browser4_base_url =
        service_url.unwrap_or_else(|| format!("http://127.0.0.1:{}", find_free_port()));

    let temp_root = e2e_temp_root_dir();
    fs::create_dir_all(&temp_root).unwrap_or_else(|error| {
        panic!(
            "failed to create e2e temp root {}: {error}",
            temp_root.display()
        )
    });
    let temp_dir = tempfile::Builder::new()
        .prefix("e2e-")
        .tempdir_in(&temp_root)
        .unwrap_or_else(|error| {
            panic!(
                "tempdir creation failed under {}: {error}",
                temp_root.display()
            )
        });
    let workspace_dir = temp_dir.path().join("workspace");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&workspace_dir).unwrap();
    fs::create_dir_all(&state_dir).unwrap();

    let invocation_dir = std::env::current_dir().expect("failed to read e2e invocation directory");

    let upload_file_path = temp_dir.path().join("upload.txt");
    fs::write(&upload_file_path, b"browser4-cli e2e upload payload")
        .expect("write upload file failed");

    E2ETestResources {
        _temp_dir: temp_dir,
        _fixture: fixture,
        external_service: is_external,
        local_browser4_started: false,
        pending_cleanup: None,
        ctx: E2ECtx {
            fixture_base_url,
            browser4_base_url,
            invocation_dir,
            use_maven_startup,
            workspace_dir,
            state_dir,
            upload_file_path,
            step_timings: Vec::new(),
        },
    }
}

// ---------------------------------------------------------------------------
// Scenario helpers
// ---------------------------------------------------------------------------

fn goto_interactive_page(ctx: &mut E2ECtx) {
    let interactive_url = ctx.interactive_url();
    run_command(ctx, &["goto", &interactive_url]);
    sleep(Duration::from_secs(2));
}

fn run_open_command(ctx: &mut E2ECtx) -> CliRunResult {
    let result = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG]);
    sleep(Duration::from_secs(2));
    return result;
}

fn batch_navigate_command(url: &str) -> String {
    format!("goto {url}")
}

fn write_json_fixture(ctx: &E2ECtx, file_name: &str, value: &serde_json::Value) -> PathBuf {
    let path = ctx.workspace_dir.join(file_name);
    let json = serde_json::to_string_pretty(value).expect("serialize JSON fixture failed");
    fs::write(&path, json).expect("write JSON fixture failed");
    path
}

fn open_resized_interactive_page(ctx: &mut E2ECtx) {
    goto_interactive_page(ctx);

    let resize_result = run_command(ctx, &["resize", "1280", "900"]);
    assert!(
        resize_result.stdout.contains("### Page"),
        "Expected '### Page' in resize output:\n{}",
        resize_result.stdout
    );

    let vw: u64 = eval_text(ctx, "window.innerWidth.toString()")
        .parse()
        .unwrap_or(0);
    assert!(vw >= 1000, "Expected viewport width >= 1000, got {vw}");
}

fn start_mock_collective_session(ctx: &mut E2ECtx) -> MockBrowser4Server {
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let co_create_result = run_command(
        ctx,
        &[
            "co",
            "create",
            "--profile-mode=prototype",
            "--max-open-tabs=12",
            "--max-browser-contexts=3",
            "--display-mode=SUPERVISED",
        ],
    );
    assert!(
        co_create_result
            .stdout
            .contains("Collective session created: collective-session-1"),
        "Expected collective session creation output in:\n{}",
        co_create_result.stdout
    );
    assert_eq!(
        read_persisted_session_id(&ctx.state_dir),
        "collective-session-1"
    );

    mock_server
}

fn assert_collective_session_call(mock_server: &MockBrowser4Server) {
    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_call = tool_calls
        .iter()
        .find(|call| call.tool == "open_session")
        .expect("expected open_session call");
    assert_eq!(
        open_session_call.arguments["capabilities"]["profileMode"],
        "prototype"
    );
    assert_eq!(
        open_session_call.arguments["capabilities"]["maxOpenTabs"],
        "12"
    );
    assert_eq!(
        open_session_call.arguments["capabilities"]["maxBrowserContexts"],
        "3"
    );
    assert_eq!(
        open_session_call.arguments["capabilities"]["displayMode"],
        "SUPERVISED"
    );
}

fn cleanup_browser4_sessions_with_ctx(ctx: &E2ECtx) -> Result<Vec<TimedStep>, String> {
    let started_at = Instant::now();
    let result = run_cli_process(ctx, &["close-all"]);
    let duration = started_at.elapsed();

    let mut steps = vec![TimedStep::new(
        "browser4 session cleanup (close-all)",
        duration,
    )];
    if result.exit_code == 0 {
        assert!(
            !result.stderr.contains("Unknown diagnostic command"),
            "browser4-cli close-all should not emit JVM diagnostic errors.\nstdout:\n{}\nstderr:\n{}",
            result.stdout,
            result.stderr
        );
        let health_started_at = Instant::now();
        wait_for_health(&ctx.browser4_base_url, 15_000).map_err(|error| {
            format!(
                "browser4-cli close-all should keep the Browser4 backend alive for subsequent commands:\n{}\nstdout:\n{}\nstderr:\n{}",
                error, result.stdout, result.stderr
            )
        })?;
        steps.push(TimedStep::new(
            "browser4 service remains healthy after close-all",
            health_started_at.elapsed(),
        ));
        return Ok(steps);
    }

    let fallback_started_at = Instant::now();
    stop_browser4_server_forcibly();
    steps.push(TimedStep::new(
        "browser4 forced cleanup fallback",
        fallback_started_at.elapsed(),
    ));
    let errors = vec![format!(
        "browser4-cli close-all failed (exit={}):\nstdout:\n{}\nstderr:\n{}",
        result.exit_code, result.stdout, result.stderr
    )];
    Err(errors.join("\n\n"))
}

fn cleanup_browser4_sessions(resources: &mut E2ETestResources) -> Result<Vec<TimedStep>, String> {
    let steps = cleanup_browser4_sessions_with_ctx(&resources.ctx)?;
    if !resources.external_service {
        resources.local_browser4_started = true;
    }
    Ok(steps)
}

#[derive(Clone, Copy, Debug)]
enum ScenarioCleanupMode {
    Synchronous,
    Deferred,
    None,
}

fn cleanup_after_scenario(
    resources: &mut E2ETestResources,
    scenario_name: &str,
    requires_browser4: bool,
    cleanup_mode: ScenarioCleanupMode,
) -> Result<Vec<TimedStep>, String> {
    if !requires_browser4 {
        return Ok(Vec::new());
    }

    match cleanup_mode {
        ScenarioCleanupMode::Synchronous => cleanup_browser4_sessions(resources),
        ScenarioCleanupMode::Deferred => {
            assert!(
                resources.pending_cleanup.is_none(),
                "pending Browser4 cleanup already exists"
            );

            let started_at = Instant::now();
            let ctx = resources.ctx.clone();
            resources.pending_cleanup = Some((
                scenario_name.to_string(),
                thread::spawn(move || cleanup_browser4_sessions_with_ctx(&ctx)),
            ));

            Ok(vec![TimedStep::new(
                "browser4 session cleanup scheduled (async)",
                started_at.elapsed(),
            )])
        }
        ScenarioCleanupMode::None => Ok(Vec::new()),
    }
}

fn run_final_cleanup() -> Result<Vec<TimedStep>, String> {
    let started_at = Instant::now();
    stop_browser4_server_forcibly();
    let steps = vec![TimedStep::new(
        "browser4 final service cleanup",
        started_at.elapsed(),
    )];
    Ok(steps)
}

// ---------------------------------------------------------------------------
// Command-coverage helpers
// ---------------------------------------------------------------------------

/// Commands that require an LLM/agent backend, destructive global cleanup, or
/// multi-browser contexts and therefore cannot be exercised in the browser-
/// backed e2e suite. Each entry has a brief justification.
///
/// This set is validated by [`test_e2e_command_coverage`]: if a new command is
/// added to `commands.rs` without appearing here *or* in the tested set, the
/// build will fail.
fn excluded_commands() -> HashSet<&'static str> {
    [
        // Destructive across concurrent sessions — would make the suite flaky
        "close-all",
        "kill-all",
    ]
    .into()
}

/// The set of commands that the e2e scenario functions exercise via
/// [`run_command`] / [`run_command_expecting_failure`].  This must be kept in
/// sync with what the test functions actually call.
fn tested_commands() -> HashSet<&'static str> {
    [
        // test_session_lifecycle
        "open",
        "list",
        "close",
        // test_navigation_and_storage
        "goto",
        "go-back",
        "go-forward",
        "reload",
        "delete-data",
        "batch",
        // test_interaction_commands
        "resize",
        "type",
        "fill",
        "press",
        "keydown",
        "keyup",
        "click",
        "dblclick",
        "hover",
        "drag",
        // test_form_controls_and_exports
        "select",
        "check",
        "uncheck",
        "upload",
        "console",
        "snapshot",
        "screenshot",
        "pdf",
        // test_collective_session_and_agent_tools
        "extract",
        "summarize",
        // test_agent_task_commands
        "agent-run",
        "agent-status",
        "agent-result",
        // test_collective_submission_commands
        "co-create",
        "co-submit",
        "co-scrape",
        "co-status",
        "co-result",
        // test_mouse_and_dialog
        "mousemove",
        "mousedown",
        "mouseup",
        "mousewheel",
        "dialog-accept",
        "dialog-dismiss",
        // test_tab_commands
        "tab-list",
        "tab-new",
        "tab-select",
        "tab-close",
        // eval is exercised directly by dedicated scenarios and shared helpers
        "eval",
    ]
    .into()
}

// ---------------------------------------------------------------------------
// Entry point — custom sequential harness
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Coverage assertion — runs without a server
// ---------------------------------------------------------------------------

/// Verify that `tested_commands() ∪ excluded_commands()` equals the full
/// command list from [`browser4_cli::commands::all_commands`].
///
/// This check does **not** require a running server. It is a test-time
/// guard: if a command is added to `commands.rs` without being placed into
/// either [`tested_commands`] or [`excluded_commands`], this test fails.
fn verify_e2e_command_coverage() {
    let all: HashSet<&str> = all_commands().iter().map(|c| c.name).collect();

    let tested = tested_commands();
    let excluded = excluded_commands();

    // 1. No command should appear in both sets.
    let overlap: Vec<&&str> = tested.intersection(&excluded).collect();
    assert!(
        overlap.is_empty(),
        "Commands appear in BOTH tested and excluded sets: {overlap:?}"
    );

    // 2. Every command from commands.rs must be in one of the two sets.
    let accounted: HashSet<&str> = tested.union(&excluded).copied().collect();
    let mut missing: Vec<&str> = all
        .iter()
        .copied()
        .filter(|cmd| !accounted.contains(cmd))
        .collect();
    missing.sort();
    assert!(
        missing.is_empty(),
        "Commands defined in commands.rs are not accounted for in e2e tests \
         (add them to `tested_commands` or `excluded_commands`): {missing:?}"
    );

    // 3. No stale entries: every name in the two sets must exist in commands.rs.
    let mut stale: Vec<&str> = accounted
        .iter()
        .copied()
        .filter(|cmd| !all.contains(cmd))
        .collect();
    stale.sort();
    assert!(
        stale.is_empty(),
        "Stale command names in e2e test sets that no longer exist in commands.rs: {stale:?}"
    );
}

fn format_duration(duration: Duration) -> String {
    format!("{:.2}s", duration.as_secs_f64())
}

fn format_duration_human(duration: Duration) -> String {
    let secs = duration.as_secs();
    if secs == 0 {
        return "<1s".to_string();
    }

    let hours = secs / 3600;
    let minutes = (secs % 3600) / 60;
    let seconds = secs % 60;

    if hours > 0 {
        format!("{}h {}m", hours, minutes)
    } else if minutes > 0 {
        format!("{}m {}s", minutes, seconds)
    } else {
        format!("{}s", seconds)
    }
}

fn render_progress_bar(done: usize, total: usize, width: usize) -> String {
    if total == 0 {
        return "-".repeat(width);
    }

    let filled = ((done * width) + (total / 2)) / total;
    let filled = filled.min(width);
    format!("{}{}", "#".repeat(filled), "-".repeat(width - filled))
}

fn render_progress_percent(done: usize, total: usize) -> usize {
    if total == 0 {
        100
    } else {
        (done * 100) / total
    }
}

fn print_timing_steps(steps: &[TimedStep]) {
    for (index, step) in steps.iter().enumerate() {
        println!(
            "    {}. {}: {}",
            index + 1,
            step.name,
            format_duration(step.duration)
        );
    }
}

fn run_named_test(name: &str, test_fn: fn()) -> TimingReport {
    print!("{} test {name} ... ", Local::now());

    std::io::stdout().flush().expect("stdout flush failed");
    let started_at = Instant::now();
    test_fn();
    let duration = started_at.elapsed();
    println!("ok ({})", format_duration(duration));
    TimingReport::new(name, duration, vec![TimedStep::new("test body", duration)])
}

fn panic_payload_to_string(payload: &(dyn std::any::Any + Send)) -> String {
    payload
        .downcast_ref::<&str>()
        .map(|s| s.to_string())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "<non-string panic>".to_string())
}

fn parse_expected_actual_from_message(message: &str) -> Option<(String, String)> {
    let mut left: Option<String> = None;
    let mut right: Option<String> = None;

    for line in message.lines() {
        let trimmed = line.trim();
        if let Some(value) = trimmed.strip_prefix("left:") {
            left = Some(value.trim().to_string());
        }
        if let Some(value) = trimmed.strip_prefix("right:") {
            right = Some(value.trim().to_string());
        }
    }

    if let (Some(actual), Some(expected)) = (left, right) {
        return Some((expected, actual));
    }

    let expected_marker = "Expected '";
    let got_marker = "', got '";
    if let Some(expected_start) = message.find(expected_marker) {
        let expected_value_start = expected_start + expected_marker.len();
        if let Some(got_start_rel) = message[expected_value_start..].find(got_marker) {
            let got_start = expected_value_start + got_start_rel;
            let expected = message[expected_value_start..got_start].to_string();
            let actual_start = got_start + got_marker.len();
            if let Some(actual_end_rel) = message[actual_start..].find('"') {
                let actual = message[actual_start..actual_start + actual_end_rel].to_string();
                return Some((expected, actual));
            }
            if let Some(actual_end_rel) = message[actual_start..].find('\'') {
                let actual = message[actual_start..actual_start + actual_end_rel].to_string();
                return Some((expected, actual));
            }
        }
    }

    None
}

fn failure_from_panic(phase: &str, message: String) -> FailureDetail {
    let (expected, actual) = parse_expected_actual_from_message(&message).unwrap_or_else(|| {
        (
            "assertion condition is satisfied".to_string(),
            message
                .lines()
                .next()
                .unwrap_or_default()
                .trim()
                .to_string(),
        )
    });

    FailureDetail {
        phase: phase.to_string(),
        expected,
        actual,
        message,
    }
}

fn failure_from_cleanup_error(error: String) -> FailureDetail {
    FailureDetail {
        phase: "cleanup".to_string(),
        expected: "scenario cleanup succeeds".to_string(),
        actual: error.clone(),
        message: error,
    }
}

fn run_named_scenario(
    name: &str,
    resources: &mut E2ETestResources,
    requires_browser4: bool,
    restart_browser4: bool,
    cleanup_mode: ScenarioCleanupMode,
    fail_fast: bool,
    test_fn: fn(&mut E2ECtx),
) -> ScenarioOutcome {
    println!("{} testing {name} ... ", Local::now());

    std::io::stdout().flush().expect("stdout flush failed");
    resources.ctx.clear_step_timings();
    let total_started_at = Instant::now();
    let mut harness_steps = Vec::new();
    if fail_fast {
        if requires_browser4 {
            let pending_steps = resources
                .join_pending_cleanup_if_any()
                .unwrap_or_else(|error| panic!("{error}"));
            harness_steps.extend(pending_steps);

            let setup_steps = if restart_browser4 {
                println!("restarting browser4 ...");
                resources.restart_browser4()
            } else {
                resources.ensure_browser4()
            };
            harness_steps.extend(setup_steps);
        }
        test_fn(&mut resources.ctx);
        let mut steps = harness_steps;
        steps.extend(resources.ctx.take_step_timings());
        let cleanup_steps =
            cleanup_after_scenario(resources, name, requires_browser4, cleanup_mode)
                .unwrap_or_else(|error| panic!("{error}"));
        steps.extend(cleanup_steps);
        let report = TimingReport::new(name, total_started_at.elapsed(), steps);
        println!("ok ({})", format_duration(report.total));
        return ScenarioOutcome {
            report,
            failures: Vec::new(),
        };
    }

    // Wrap the test in catch_unwind so that Browser4 and Chrome are always
    // force-stopped even when the test panics, preventing leaked processes
    // from contaminating later scenarios.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if requires_browser4 {
            let pending_steps = resources
                .join_pending_cleanup_if_any()
                .unwrap_or_else(|error| panic!("{error}"));
            harness_steps.extend(pending_steps);

            let setup_steps = if restart_browser4 {
                println!("restarting browser4 ...");
                resources.restart_browser4()
            } else {
                resources.ensure_browser4()
            };
            harness_steps.extend(setup_steps);
        }
        test_fn(&mut resources.ctx);
    }));

    let cleanup_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        cleanup_after_scenario(resources, name, requires_browser4, cleanup_mode)
    }));

    let mut steps = harness_steps;
    steps.extend(resources.ctx.take_step_timings());

    let cleanup_error = match cleanup_result {
        Ok(Ok(cleanup_steps)) => {
            steps.extend(cleanup_steps);
            None
        }
        Ok(Err(error)) => Some(error),
        Err(payload) => Some(panic_payload_to_string(payload.as_ref())),
    };
    let report = TimingReport::new(name, total_started_at.elapsed(), steps);
    let mut failures = Vec::new();

    match result {
        Ok(()) => {
            if let Some(error) = cleanup_error {
                println!("FAILED ({}) - {}", format_duration(report.total), error);
                print_timing_steps(&report.steps);
                failures.push(failure_from_cleanup_error(error));
                return ScenarioOutcome { report, failures };
            }
            println!("ok ({})", format_duration(report.total));
            ScenarioOutcome { report, failures }
        }
        Err(payload) => {
            let msg = panic_payload_to_string(payload.as_ref());
            println!("FAILED ({}) - {}", format_duration(report.total), msg);
            if let Some(error) = cleanup_error {
                println!("cleanup FAILED - {error}");
                failures.push(failure_from_cleanup_error(error));
            }
            print_timing_steps(&report.steps);
            failures.push(failure_from_panic("scenario", msg));
            ScenarioOutcome { report, failures }
        }
    }
}

#[derive(Debug)]
enum ScenarioFilter {
    Scenario(String),
    From(String),
    Failed,
}

#[derive(Debug)]
struct RunOptions {
    scenario_filter: Option<ScenarioFilter>,
    scenario_limit: Option<usize>,
    has_positional_filter: bool,
    fail_fast: bool,
    list_only: bool,
}

fn parse_scenario_limit(raw: &str) -> usize {
    let normalized = raw.trim();
    assert!(
        !normalized.is_empty(),
        "Missing value for --scenario-limit. Use --scenario-limit=<count>"
    );

    let limit = normalized.parse::<usize>().unwrap_or_else(|_| {
        panic!(
            "Invalid --scenario-limit '{}'. Expected a positive integer.",
            normalized
        )
    });
    assert!(
        limit >= 1,
        "Invalid --scenario-limit '{}'. Value must be >= 1.",
        normalized
    );

    limit
}

fn apply_scenario_limit_filter(
    selected_scenarios: Vec<scenarios::ScenarioDef>,
    scenario_limit: Option<usize>,
) -> Vec<scenarios::ScenarioDef> {
    let Some(limit) = scenario_limit else {
        return selected_scenarios;
    };

    selected_scenarios.into_iter().take(limit).collect()
}

fn parse_named_flag_value(args: &mut impl Iterator<Item = String>, flag: &str) -> String {
    args.next().unwrap_or_else(|| {
        panic!("Missing value for {flag}. Use {flag}=<scenario-name> or {flag} <scenario-name>")
    })
}

fn parse_run_options() -> RunOptions {
    let mut args = std::env::args().skip(1);
    let mut scenario = None;
    let mut scenario_from = None;
    let mut scenario_limit = None;
    let mut rerun_failed = false;
    let mut fail_fast = false;
    let mut has_positional_filter = false;
    let mut list_only = false;

    while let Some(arg) = args.next() {
        if let Some(value) = arg.strip_prefix("--scenario=") {
            scenario = Some(value.to_string());
            continue;
        }
        if arg == "--scenario" {
            scenario = Some(parse_named_flag_value(&mut args, "--scenario"));
            continue;
        }

        if let Some(value) = arg.strip_prefix("--scenario-from=") {
            scenario_from = Some(value.to_string());
            continue;
        }
        if arg == "--scenario-from" {
            scenario_from = Some(parse_named_flag_value(&mut args, "--scenario-from"));
            continue;
        }

        if let Some(value) = arg.strip_prefix("--scenario-limit=") {
            scenario_limit = Some(parse_scenario_limit(value));
            continue;
        }
        if arg == "--scenario-limit" {
            scenario_limit = Some(parse_scenario_limit(&parse_named_flag_value(
                &mut args,
                "--scenario-limit",
            )));
            continue;
        }

        if arg == "--scenario-range" || arg.starts_with("--scenario-range=") {
            panic!(
                "--scenario-range has been removed. Use --scenario-limit=<count> together with existing selectors (for example --scenario-from=... --scenario-limit=5)."
            );
        }

        if arg == "--failed" {
            rerun_failed = true;
            continue;
        }

        if arg == "--fail-fast" {
            fail_fast = true;
            continue;
        }

        if arg == "--list" {
            list_only = true;
            continue;
        }

        if !arg.starts_with('-') {
            has_positional_filter = true;
        }
    }

    if rerun_failed && (scenario.is_some() || scenario_from.is_some()) {
        panic!("--failed cannot be used together with --scenario or --scenario-from");
    }

    let scenario_filter = match (scenario, scenario_from, rerun_failed) {
        (Some(_), Some(_), _) => {
            panic!("--scenario and --scenario-from cannot be used together")
        }
        (Some(value), None, false) => Some(ScenarioFilter::Scenario(value)),
        (None, Some(value), false) => Some(ScenarioFilter::From(value)),
        (None, None, true) => Some(ScenarioFilter::Failed),
        (None, None, false) => None,
        _ => unreachable!("unexpected scenario filter argument combination"),
    };

    RunOptions {
        scenario_filter,
        scenario_limit,
        has_positional_filter,
        fail_fast,
        list_only,
    }
}

fn resolve_scenario(name: &str) -> Option<scenarios::ScenarioDef> {
    scenarios::all_scenarios()
        .iter()
        .copied()
        .find(|scenario| scenario.name == name || scenario.short_name == name)
}

fn resolve_scenario_index(name: &str) -> Option<usize> {
    scenarios::all_scenarios()
        .iter()
        .position(|scenario| scenario.name == name || scenario.short_name == name)
}

fn scenario_filter_uses_pattern(filter: &str) -> bool {
    filter.contains('*') || filter.contains('?')
}

fn compile_scenario_pattern(filter: &str) -> regex::Regex {
    let mut pattern = String::from("^");
    for ch in filter.chars() {
        match ch {
            '*' => pattern.push_str(".*"),
            '?' => pattern.push('.'),
            _ => pattern.push_str(&regex::escape(&ch.to_string())),
        }
    }
    pattern.push('$');

    regex::Regex::new(&pattern)
        .unwrap_or_else(|error| panic!("Invalid scenario pattern '{filter}': {error}"))
}

fn resolve_scenarios_by_filter(filter: &str) -> Vec<scenarios::ScenarioDef> {
    if !scenario_filter_uses_pattern(filter) {
        return resolve_scenario(filter).into_iter().collect();
    }

    let pattern = compile_scenario_pattern(filter);

    scenarios::all_scenarios()
        .iter()
        .copied()
        .filter(|scenario| pattern.is_match(scenario.name) || pattern.is_match(scenario.short_name))
        .collect()
}

const MAX_ALLOWED_FAILED_SCENARIOS: usize = 3;

#[derive(Clone, Copy)]
struct PlannedScenarioRun {
    scenario: scenarios::ScenarioDef,
    run_index: usize,
    test_count: usize,
}

impl PlannedScenarioRun {
    fn display_name(&self) -> String {
        if self.test_count == 1 {
            self.scenario.name.to_string()
        } else {
            format!(
                "{} [{}/{}]",
                self.scenario.name,
                self.run_index + 1,
                self.test_count
            )
        }
    }

}

const COVERAGE_TEST_NAME: &str = "test_e2e_command_coverage";

fn total_declared_e2e_tests() -> usize {
    scenarios::all_scenarios()
        .iter()
        .map(|scenario| scenario.effective_test_count())
        .sum::<usize>()
        + 1
}

fn main() {
    let suite_started_at = Instant::now();
    let run_options = parse_run_options();
    let has_explicit_scenario_filter = run_options.scenario_filter.is_some();
    let all_scenarios = scenarios::all_scenarios();
    let available_names = all_scenarios
        .iter()
        .map(|s| format!("{} ({})", s.name, s.short_name))
        .collect::<Vec<_>>()
        .join(", ");

    let (selected_scenarios, run_coverage): (Vec<scenarios::ScenarioDef>, bool) = match run_options
        .scenario_filter
    {
        Some(ScenarioFilter::Scenario(filter)) => {
            let selected = resolve_scenarios_by_filter(&filter);
            assert!(
                !selected.is_empty(),
                "Unknown scenario or pattern '{filter}'. Available scenarios: {available_names}"
            );
            println!(
                "selected {} scenario(s) via --scenario={}: {}",
                selected.len(),
                filter,
                selected
                    .iter()
                    .map(|scenario| scenario.name)
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            (selected, false)
        }
        Some(ScenarioFilter::From(filter)) => {
            let start_index = resolve_scenario_index(&filter).unwrap_or_else(|| {
                panic!("Unknown scenario '{filter}'. Available scenarios: {available_names}");
            });
            (all_scenarios[start_index..].to_vec(), false)
        }
        Some(ScenarioFilter::Failed) => {
            let failed = load_last_failed_scenarios();
            assert!(
                !failed.is_empty(),
                "No failed scenarios were recorded in the previous run (file: {}).",
                last_failed_scenarios_file_path().display()
            );

            let failed_set: HashSet<&str> = failed.iter().map(String::as_str).collect();
            let selected = all_scenarios
                .iter()
                .copied()
                .filter(|scenario| failed_set.contains(scenario.name))
                .collect::<Vec<_>>();

            let missing = failed
                .iter()
                .filter(|name| resolve_scenario(name).is_none())
                .cloned()
                .collect::<Vec<_>>();
            assert!(
                missing.is_empty(),
                "Recorded failed scenarios are no longer available: {:?}. Available scenarios: {}",
                missing,
                available_names
            );
            assert!(
                !selected.is_empty(),
                "No selectable scenarios were resolved from the previous failed list: {:?}",
                failed
            );

            println!(
                "rerunning {} scenario(s) from previous failures: {}",
                selected.len(),
                selected
                    .iter()
                    .map(|scenario| scenario.name)
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            (selected, false)
        }
        None => (all_scenarios.to_vec(), true),
    };

    let selected_scenarios = apply_scenario_limit_filter(selected_scenarios, run_options.scenario_limit);

    if let Some(limit) = run_options.scenario_limit {
        println!(
            "applied scenario limit: first {} selected scenario(s)",
            limit
        );
    }

    let planned_runs: Vec<PlannedScenarioRun> = selected_scenarios
        .iter()
        .copied()
        .flat_map(|scenario| {
            let test_count = scenario.effective_test_count();
            (0..test_count).map(move |run_index| PlannedScenarioRun {
                scenario,
                run_index,
                test_count,
            })
        })
        .collect();

    let filtered_out = if run_options.has_positional_filter && !has_explicit_scenario_filter {
        total_declared_e2e_tests()
    } else {
        0
    };

    if run_options.list_only {
        if run_coverage {
            println!("{COVERAGE_TEST_NAME}: test");
        }
        for planned_run in &planned_runs {
            println!("{}: test", planned_run.display_name());
        }
        println!("\n{} tests, 0 benchmarks", planned_runs.len() + usize::from(run_coverage));
        return;
    }

    let scenario_runs = planned_runs.len();
    let total_tests = scenario_runs + usize::from(run_coverage);

    if filtered_out > 0 {
        println!("running 0 tests");
        println!(
            "test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; {filtered_out} filtered out"
        );
        return;
    }

    println!("running {total_tests} tests");

    let mut resources = create_e2e_test_resources();
    let run_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut timings: Vec<TimingReport> = Vec::with_capacity(total_tests);
        let mut scenario_failures: Vec<(String, FailureDetail)> = Vec::new();
        let mut failed_scenario_names: HashSet<String> = HashSet::new();

        if run_coverage {
            let report = run_named_test(COVERAGE_TEST_NAME, verify_e2e_command_coverage);
            timings.push(report);
        }

        let mut scenario_progress = 0usize;
        for (_, planned_run) in planned_runs.iter().enumerate() {
            scenario_progress += 1;
            let display_name = planned_run.display_name();
            let scenario_started_at = Local::now();
            let scenario_started_instant = Instant::now();

            let cleanup_mode = if planned_run.scenario.requires_browser4 {
                ScenarioCleanupMode::Synchronous
            } else {
                ScenarioCleanupMode::None
            };

            let outcome = run_named_scenario(
                &display_name,
                &mut resources,
                planned_run.scenario.requires_browser4,
                planned_run.scenario.restart_browser4,
                cleanup_mode,
                run_options.fail_fast,
                planned_run.scenario.test_fn,
            );
            let scenario_finished_at = Local::now();
            let scenario_elapsed = scenario_started_instant.elapsed();
            let status = if outcome.passed() { "ok" } else { "FAILED" };
            let progress_bar = render_progress_bar(scenario_progress, scenario_runs, 24);
            let progress_percent = render_progress_percent(scenario_progress, scenario_runs);
            println!(
                "{} progress [{}] {}/{} ({}%) {} => {} | start={} | end={} | elapsed={}",
                scenario_finished_at,
                progress_bar,
                scenario_progress,
                scenario_runs,
                progress_percent,
                display_name,
                status,
                scenario_started_at.format("%H:%M:%S"),
                scenario_finished_at.format("%H:%M:%S"),
                format_duration_human(scenario_elapsed)
            );
            if !outcome.passed() {
                failed_scenario_names.insert(planned_run.scenario.name.to_string());
            }
            for failure in &outcome.failures {
                scenario_failures.push((display_name.clone(), failure.clone()));
            }
            save_last_failed_scenarios(failed_scenario_names.iter().cloned());
            timings.push(outcome.report);
        }

        let suite_elapsed = suite_started_at.elapsed();
        println!(
            "All scenarios complete at {}! total elapsed={} ({})",
            Local::now(),
            format_duration_human(suite_elapsed),
            format_duration(suite_elapsed)
        );
        (timings, scenario_failures)
    }));

    let pending_cleanup_result = resources.join_pending_cleanup_if_any();
    let final_cleanup_result = run_final_cleanup();

    match (run_result, pending_cleanup_result, final_cleanup_result) {
        (Ok((timings, scenario_failures)), pending_cleanup_result, final_cleanup_result) => {
            let mut final_cleanup_steps = pending_cleanup_result.unwrap_or_else(|error| {
                panic!("{error}");
            });
            final_cleanup_steps.extend(final_cleanup_result.unwrap_or_else(|error| {
                panic!("{error}");
            }));
            let mut failed_scenarios: Vec<String> = Vec::new();
            for (scenario_name, _) in &scenario_failures {
                if !failed_scenarios.iter().any(|name| name == scenario_name) {
                    failed_scenarios.push(scenario_name.clone());
                }
            }
            let failed_scenario_count = failed_scenarios.len();
            let passed = total_tests.saturating_sub(failed_scenario_count);
            if failed_scenario_count == 0 {
                println!(
                    "test result: ok. {} passed; 0 failed; 0 ignored; 0 measured; {} filtered out",
                    total_tests,
                    filtered_out
                );
            } else if failed_scenario_count <= MAX_ALLOWED_FAILED_SCENARIOS {
                println!(
                    "test result: ok (tolerated). {} passed; {} failed; 0 ignored; 0 measured; {} filtered out ({} failure entries; tolerated <= {})",
                    passed,
                    failed_scenario_count,
                    filtered_out,
                    scenario_failures.len(),
                    MAX_ALLOWED_FAILED_SCENARIOS
                );
            } else {
                println!(
                    "test result: FAILED. {} passed; {} failed; 0 ignored; 0 measured; {} filtered out ({} failure entries; allowed <= {})",
                    passed,
                    failed_scenario_count,
                    filtered_out,
                    scenario_failures.len(),
                    MAX_ALLOWED_FAILED_SCENARIOS
                );
            }
            println!("per-test timing:");
            for report in timings {
                println!("  {}: {}", report.name, format_duration(report.total));
                print_timing_steps(&report.steps);
            }
            if !scenario_failures.is_empty() {
                println!("failure summary (grouped by scenario):");
                let mut global_index = 0usize;
                for scenario_name in failed_scenarios {
                    let grouped_failures: Vec<&FailureDetail> = scenario_failures
                        .iter()
                        .filter_map(|(name, failure)| (name == &scenario_name).then_some(failure))
                        .collect();
                    println!(
                        "  scenario={} failures={}",
                        scenario_name,
                        grouped_failures.len()
                    );
                    for failure in grouped_failures {
                        global_index += 1;
                        println!("    {}. phase={}", global_index, failure.phase);
                        println!("       expected: {}", failure.expected);
                        println!("       actual:   {}", failure.actual);
                        println!("       message:  {}", failure.message);
                    }
                }
            }
            println!("final cleanup:");
            print_timing_steps(&final_cleanup_steps);
            if failed_scenario_count > MAX_ALLOWED_FAILED_SCENARIOS {
                panic!(
                    "{} failed scenario(s) exceeded allowed tolerance (<= {}). See failure summary above.",
                    failed_scenario_count,
                    MAX_ALLOWED_FAILED_SCENARIOS
                );
            }
        }
        (Err(payload), pending_cleanup_result, final_cleanup_result) => {
            match pending_cleanup_result {
                Ok(pending_steps) if !pending_steps.is_empty() => {
                    eprintln!("pending async cleanup:");
                    print_timing_steps(&pending_steps);
                }
                Ok(_) => {}
                Err(error) => eprintln!("pending async cleanup FAILED - {error}"),
            }
            match final_cleanup_result {
                Ok(final_cleanup_steps) => {
                    eprintln!("final cleanup:");
                    print_timing_steps(&final_cleanup_steps);
                }
                Err(error) => eprintln!("final cleanup FAILED - {error}"),
            }
            std::panic::resume_unwind(payload);
        }
    }
}
