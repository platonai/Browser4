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
//! ```
//!
//! The Browser4 service is resolved in this order:
//! 1. `BROWSER4_E2E_SERVICE_URL` environment variable – connect to an already-running
//!    service (Docker-friendly; no JAR is needed).
//! 2. `BROWSER4_E2E_SERVER_URL` environment variable – alias for the above.
//! 3. Otherwise, each local run lets `browser4-cli` auto-start the backend.
//!    Startup uses Maven `spring-boot:run` only when the CLI current directory
//!    is inside a Browser4 source checkout; all other directories use the jar
//!    fallback path.
//!
//! When running against an external Docker service, also set:
//! - `BROWSER4_E2E_FIXTURE_HOST` – hostname/IP the Browser4 container uses to
//!   reach the fixture HTTP server on the host (e.g. `host.docker.internal` or
//!   the Docker bridge gateway IP such as `172.17.0.1`). Defaults to `127.0.0.1`.

use std::collections::HashSet;
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use browser4_cli::commands::all_commands;
use browser4_cli::managed_processes::stop_browser4_server_forcibly;

#[path = "e2e/scenarios/mod.rs"]
mod scenarios;

const OPEN_TEMPORARY_PROFILE_ARG: &str = "--profile-mode=TEMPORARY";

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
                    serde_json::json!({
                        "id": task_id,
                        "status": "RUNNING",
                    })
                    .to_string()
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
                "agent_extract" => {
                    r#"{"items":[{"title":"Mock Product","price":"$19.99"}]}"#.to_string()
                }
                "agent_summarize" => "Mock summary for #page-marker".to_string(),
                "page_url" => "https://mock.browser4.local/current".to_string(),
                "page_title" => "Mock Browser4 Page".to_string(),
                "browser_snapshot" => "mock snapshot".to_string(),
                other => format!("mock response for {other}"),
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

/// Context for running CLI commands in isolation.
struct E2ECtx {
    fixture_base_url: String,
    browser4_base_url: String,
    invocation_dir: PathBuf,
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
        let startup_result = run_cli_process_with_live_output(&self.ctx, &["list"]);
        let startup_log_hint = format_browser4_startup_log_hint(&startup_result.stderr);
        let started_via_maven = startup_result
            .stderr
            .contains("Starting server via Maven spring-boot:run");
        let expect_maven_startup = is_browser4_repo_or_child(&self.ctx.invocation_dir);
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
                        "Expected local e2e startup to use Maven spring-boot:run when current directory is inside a Browser4 checkout.{}\nstdout:\n{}\nstderr:\n{}",
                        startup_log_hint,
                        startup_result.stdout,
                        startup_result.stderr,
                    );
                } else {
                    assert!(
                        !started_via_maven,
                        "Expected local e2e startup outside a Browser4 checkout to use jar fallback instead of Maven.{}\nstdout:\n{}\nstderr:\n{}",
                        startup_log_hint,
                        startup_result.stdout,
                        startup_result.stderr,
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

fn is_browser4_repo_or_child(path: &Path) -> bool {
    let mut current = if path.is_dir() {
        Some(path)
    } else {
        path.parent()
    };
    while let Some(dir) = current {
        if is_browser4_repo_root(dir) {
            return true;
        }
        current = dir.parent();
    }
    false
}

fn is_browser4_repo_root(path: &Path) -> bool {
    path.join("VERSION").is_file()
        && path.join("pom.xml").is_file()
        && path
            .join("browser4")
            .join("browser4-agents")
            .join("pom.xml")
            .is_file()
        && path
            .join("sdks")
            .join("browser4-cli")
            .join("Cargo.toml")
            .is_file()
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
    full_args.extend_from_slice(args);

    let mut command = Command::new(cli_binary());
    command
        .args(&full_args)
        .current_dir(&ctx.workspace_dir)
        .env(ROOT_SEARCH_START_DIR_ENV, &ctx.invocation_dir)
        .env("BROWSER4_CLI_STATE_DIR", &ctx.state_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

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

    println!(
        "browser4-cli command completed in {}s with exit code {}: {}",
        started_at.elapsed().as_secs(),
        status.code().unwrap_or(-1),
        full_args.join(" ")
    );

    let stdout = finish_output_collector(stdout_handle, "stdout", full_args);
    let stderr = finish_output_collector(stderr_handle, "stderr", full_args);

    std::process::Output {
        status,
        stdout: stdout.into_bytes(),
        stderr: stderr.into_bytes(),
    }
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

/// Extract a tab ID for the given URL from `tab-list` output.
///
/// Looks for a pattern like `id:<VALUE>` (or `id="<VALUE>"`) followed — on the
/// same or a later line — by the URL, similar to the TypeScript `extractTabId`.
fn extract_tab_id(output: &str, url: &str) -> String {
    // Match "id" followed by ":" or "=" then an optional quote, then the value,
    // somewhere before the URL on the same block.  We iterate over lines/chunks
    // because the backend may format as YAML / JSON / plaintext.
    static RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let re = RE.get_or_init(|| {
        regex::Regex::new(r#"id[:=]"?([^",}\s]+)"?"#).expect("tab id regex compile")
    });

    // Collect all (id_value, byte_position) pairs.
    let ids: Vec<(String, usize)> = re
        .captures_iter(output)
        .filter_map(|cap| {
            let m = cap.get(0)?;
            Some((cap[1].to_string(), m.start()))
        })
        .collect();

    // Find the position of our target URL in the output.
    let url_pos = output
        .find(url)
        .unwrap_or_else(|| panic!("URL '{}' not found in tab list output:\n{}", url, output));

    // Pick the id that appears immediately before the URL.
    ids.iter()
        .filter(|(_, pos)| *pos < url_pos)
        .last()
        .map(|(id, _)| id.clone())
        .unwrap_or_else(|| panic!("Could not find tab id for '{}' in:\n{}", url, output))
}

// ---------------------------------------------------------------------------
// State helpers
// ---------------------------------------------------------------------------

fn read_persisted_session_id(state_dir: &Path) -> String {
    let path = state_dir.join("cli-state.json");
    let raw = fs::read_to_string(&path).expect("cli-state.json not found");
    let parsed: serde_json::Value =
        serde_json::from_str(&raw).expect("cli-state.json is not valid JSON");
    parsed["sessionId"]
        .as_str()
        .expect("no sessionId in cli-state.json")
        .to_string()
}

fn eval_text(ctx: &mut E2ECtx, expression: &str) -> String {
    let started_at = Instant::now();
    let result = run_checked_cli_process(ctx, &["eval", expression]);
    ctx.record_step(format_eval_step_label(expression), started_at.elapsed());
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

fn assume_wait_for_state<F>(
    ctx: &mut E2ECtx,
    predicate: F,
    timeout_ms: u64,
    failure_message: &str,
) where
    F: Fn(&serde_json::Value) -> bool,
{
    if let Err(error) = wait_for_state_or_return_error(ctx, predicate, timeout_ms, failure_message) {
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

fn create_e2e_test_resources() -> E2ETestResources {
    let service_url = external_service_url();
    let is_external = service_url.is_some();
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
    let invocation_dir = std::env::current_dir().expect("failed to read e2e invocation directory");

    let temp_dir = tempfile::TempDir::new().expect("tempdir creation failed");
    let workspace_dir = temp_dir.path().join("workspace");
    let state_dir = temp_dir.path().join("state");
    fs::create_dir_all(&workspace_dir).unwrap();
    fs::create_dir_all(&state_dir).unwrap();

    let upload_file_path = temp_dir.path().join("upload.txt");
    fs::write(&upload_file_path, b"browser4-cli e2e upload payload")
        .expect("write upload file failed");

    E2ETestResources {
        _temp_dir: temp_dir,
        _fixture: fixture,
        external_service: is_external,
        local_browser4_started: false,
        ctx: E2ECtx {
            fixture_base_url,
            browser4_base_url,
            invocation_dir,
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

fn open_interactive_page(ctx: &mut E2ECtx) {
    run_open_command(ctx);
    let interactive_url = ctx.interactive_url();
    run_command(ctx, &["goto", &interactive_url]);
}

fn run_open_command(ctx: &mut E2ECtx) -> CliRunResult {
    run_command(ctx, &["open", OPEN_TEMPORARY_PROFILE_ARG])
}

fn batch_open_command(url: &str) -> String {
    format!("open {OPEN_TEMPORARY_PROFILE_ARG} {url}")
}

fn write_json_fixture(ctx: &E2ECtx, file_name: &str, value: &serde_json::Value) -> PathBuf {
    let path = ctx.workspace_dir.join(file_name);
    let json = serde_json::to_string_pretty(value).expect("serialize JSON fixture failed");
    fs::write(&path, json).expect("write JSON fixture failed");
    path
}

fn open_resized_interactive_page(ctx: &mut E2ECtx) {
    open_interactive_page(ctx);

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

fn cleanup_browser4_sessions(resources: &mut E2ETestResources) -> Result<Vec<TimedStep>, String> {
    let started_at = Instant::now();
    let result = run_cli_process(&resources.ctx, &["close-all"]);
    let duration = started_at.elapsed();
    resources.local_browser4_started = false;

    let mut steps = vec![TimedStep::new(
        "browser4 session cleanup (close-all)",
        duration,
    )];
    if result.exit_code == 0 {
        return Ok(steps);
    }

    let fallback_started_at = Instant::now();
    stop_browser4_server_forcibly();
    steps.push(TimedStep::new(
        "browser4 forced cleanup fallback",
        fallback_started_at.elapsed(),
    ));
    Err(format!(
        "browser4-cli close-all failed (exit={}):\nstdout:\n{}\nstderr:\n{}",
        result.exit_code, result.stdout, result.stderr
    ))
}

fn cleanup_after_scenario(
    resources: &mut E2ETestResources,
    requires_browser4: bool,
) -> Result<Vec<TimedStep>, String> {
    if !requires_browser4 {
        return Ok(Vec::new());
    }

    cleanup_browser4_sessions(resources)
}

fn run_final_cleanup() -> Duration {
    let started_at = Instant::now();
    stop_browser4_server_forcibly();
    started_at.elapsed()
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
        // eval is exercised indirectly by the eval_text helper
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
    print!("test {name} ... ");
    std::io::stdout().flush().expect("stdout flush failed");
    let started_at = Instant::now();
    test_fn();
    let duration = started_at.elapsed();
    println!("ok ({})", format_duration(duration));
    TimingReport::new(name, duration, vec![TimedStep::new("test body", duration)])
}

fn run_named_scenario(
    name: &str,
    resources: &mut E2ETestResources,
    requires_browser4: bool,
    restart_browser4: bool,
    test_fn: fn(&mut E2ECtx),
) -> TimingReport {
    println!("testing {name} ... ");

    std::io::stdout().flush().expect("stdout flush failed");
    resources.ctx.clear_step_timings();
    let total_started_at = Instant::now();
    let mut harness_steps = Vec::new();
    // Wrap the test in catch_unwind so that Browser4 and Chrome are always
    // force-stopped even when the test panics, preventing leaked processes
    // from contaminating later scenarios.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if requires_browser4 {
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
        cleanup_after_scenario(resources, requires_browser4)
    }));

    let mut steps = harness_steps;
    steps.extend(resources.ctx.take_step_timings());

    let cleanup_error = match cleanup_result {
        Ok(Ok(cleanup_steps)) => {
            steps.extend(cleanup_steps);
            None
        }
        Ok(Err(error)) => Some(error),
        Err(payload) => Some(
            payload
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "<non-string panic during cleanup>".to_string()),
        ),
    };
    let report = TimingReport::new(name, total_started_at.elapsed(), steps);

    match result {
        Ok(()) => {
            if let Some(error) = cleanup_error {
                println!("FAILED ({}) - {}", format_duration(report.total), error);
                print_timing_steps(&report.steps);
                panic!("{error}");
            }
            println!("ok ({})", format_duration(report.total));
            report
        }
        Err(payload) => {
            let msg = payload
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "<non-string panic>".to_string());
            println!("FAILED ({}) - {}", format_duration(report.total), msg);
            if let Some(error) = cleanup_error {
                println!("cleanup FAILED - {error}");
            }
            print_timing_steps(&report.steps);
            std::panic::resume_unwind(payload);
        }
    }
}

fn parse_scenario_filter() -> Option<String> {
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        if let Some(value) = arg.strip_prefix("--scenario=") {
            return Some(value.to_string());
        }
        if arg == "--scenario" {
            return args.next();
        }
    }
    None
}

fn resolve_scenario(name: &str) -> Option<scenarios::ScenarioDef> {
    scenarios::all_scenarios()
        .iter()
        .copied()
        .find(|scenario| scenario.name == name || scenario.short_name == name)
}

fn main() {
    let scenario_filter = parse_scenario_filter();
    let all_scenarios = scenarios::all_scenarios();
    let selected_scenarios: Vec<scenarios::ScenarioDef> = if let Some(filter) = scenario_filter {
        let scenario = resolve_scenario(&filter).unwrap_or_else(|| {
            let names = all_scenarios
                .iter()
                .map(|s| format!("{} ({})", s.name, s.short_name))
                .collect::<Vec<_>>()
                .join(", ");
            panic!("Unknown scenario '{filter}'. Available scenarios: {names}");
        });
        vec![scenario]
    } else {
        all_scenarios.to_vec()
    };

    let run_coverage = selected_scenarios.len() == all_scenarios.len();
    let scenario_runs: usize = selected_scenarios
        .iter()
        .map(|scenario| scenario.effective_test_count())
        .sum();
    let total_tests = scenario_runs + usize::from(run_coverage);
    println!("running {total_tests} tests");
    let mut resources = create_e2e_test_resources();
    let run_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut timings: Vec<TimingReport> = Vec::with_capacity(total_tests);

        if run_coverage {
            let report = run_named_test("test_e2e_command_coverage", verify_e2e_command_coverage);
            timings.push(report);
        }

        for scenario in selected_scenarios {
            let test_count = scenario.effective_test_count();
            for run_index in 0..test_count {
                let display_name = if test_count == 1 {
                    scenario.name.to_string()
                } else {
                    format!("{} [{}/{}]", scenario.name, run_index + 1, test_count)
                };
                let report = run_named_scenario(
                    &display_name,
                    &mut resources,
                    scenario.requires_browser4,
                    scenario.restart_browser4,
                    scenario.test_fn,
                );
                timings.push(report);
            }
        }

        println!("All scenarios complete!");
        timings
    }));

    let final_cleanup_duration = run_final_cleanup();

    match run_result {
        Ok(timings) => {
            println!(
                "test result: ok. {} passed; 0 failed; 0 ignored; 0 measured; 0 filtered out",
                total_tests
            );
            println!("per-test timing:");
            for report in timings {
                println!("  {}: {}", report.name, format_duration(report.total));
                print_timing_steps(&report.steps);
            }
            println!(
                "final service cleanup: {}",
                format_duration(final_cleanup_duration)
            );
        }
        Err(payload) => {
            eprintln!(
                "final service cleanup: {}",
                format_duration(final_cleanup_duration)
            );
            std::panic::resume_unwind(payload);
        }
    }
}
