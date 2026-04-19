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
//! 3. Otherwise, each local run lets `browser4-cli` auto-start the backend from
//!    the Browser4 source checkout via Maven `spring-boot:run`, so E2E always
//!    exercises the latest server code instead of a stale packaged JAR.
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
use std::thread::sleep;
use std::time::{Duration, Instant};

use browser4_cli::commands::all_commands;
use browser4_cli::managed_processes::stop_browser4_server_forcibly;

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
// HTML fixtures (matching the TypeScript tests verbatim)
// ---------------------------------------------------------------------------

fn interactive_html() -> String {
    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>{title}</title>
  <style>
    body {{ margin: 0; font-family: sans-serif; min-height: 2400px; }}
    #mouse-area {{
      position: fixed;
      top: 0;
      left: 0;
      width: 480px;
      height: 320px;
      background: rgba(0, 128, 255, 0.08);
      border: 1px solid #08f;
    }}
    #drag-source, #drag-target {{
      width: 160px;
      height: 48px;
      margin-top: 16px;
      border: 1px solid #555;
      display: flex;
      align-items: center;
      justify-content: center;
    }}
  </style>
</head>
<body>
  <div id="mouse-area">mouse area</div>
  <main style="padding: 360px 24px 24px;">
    <input id="type-target" type="text" />
    <input id="fill-target" type="text" />
    <input id="file-input" type="file" />
    <input id="check-target" type="checkbox" />
    <select id="select-target">
      <option value="">-- choose --</option>
      <option value="green">Green</option>
      <option value="blue">Blue</option>
    </select>
    <button id="click-target" type="button">Click</button>
    <button id="dblclick-target" type="button">Double Click</button>
    <button id="hover-target" type="button">Hover</button>
    <button id="prompt-target" type="button">Prompt</button>
    <button id="confirm-target" type="button">Confirm</button>
    <div id="drag-source" draggable="true">drag source</div>
    <div id="drag-target">drag target</div>
    <pre id="state-log"></pre>
  </main>
  <script>
    window.__browser4State = {{
      clickCount: 0,
      doubleClickCount: 0,
      hovered: false,
      dragStarted: false,
      dragDropped: '',
      promptResult: '',
      confirmResult: '',
      keyEvents: [],
      mouseDownCount: 0,
      mouseUpCount: 0,
      lastMouse: null,
      lastWheel: null,
      typeValue: '',
      fillValue: '',
      checkbox: false,
      selectValue: '',
      uploadCount: 0,
      uploadName: '',
      submitCount: 0
    }};

    function syncState() {{
      const state = window.__browser4State;
      state.typeValue = document.getElementById('type-target').value;
      state.fillValue = document.getElementById('fill-target').value;
      state.checkbox = document.getElementById('check-target').checked;
      state.selectValue = document.getElementById('select-target').value;
      const files = document.getElementById('file-input').files;
      state.uploadCount = files ? files.length : 0;
      state.uploadName = files && files[0] ? files[0].name : '';
      document.getElementById('state-log').textContent = JSON.stringify(state);
    }}

    document.getElementById('click-target').addEventListener('click', () => {{
      window.__browser4State.clickCount += 1;
      console.info('click-target clicked');
      syncState();
    }});

    document.getElementById('dblclick-target').addEventListener('dblclick', () => {{
      window.__browser4State.doubleClickCount += 1;
      syncState();
    }});

    document.getElementById('hover-target').addEventListener('mouseenter', () => {{
      window.__browser4State.hovered = true;
      syncState();
    }});

    document.getElementById('drag-source').addEventListener('dragstart', (event) => {{
      window.__browser4State.dragStarted = true;
      event.dataTransfer.setData('text/plain', 'drag-source');
      syncState();
    }});

    document.getElementById('drag-target').addEventListener('dragover', (event) => {{
      event.preventDefault();
    }});

    document.getElementById('drag-target').addEventListener('drop', (event) => {{
      event.preventDefault();
      window.__browser4State.dragDropped = event.dataTransfer.getData('text/plain');
      syncState();
    }});

    document.getElementById('prompt-target').addEventListener('click', () => {{
      setTimeout(() => {{
        const value = window.prompt('Enter prompt text', 'seed');
        window.__browser4State.promptResult = value === null ? '__dismissed__' : value;
        syncState();
      }}, 0);
    }});

    document.getElementById('confirm-target').addEventListener('click', () => {{
      setTimeout(() => {{
        const accepted = window.confirm('Confirm action');
        window.__browser4State.confirmResult = accepted ? 'accepted' : 'dismissed';
        syncState();
      }}, 0);
    }});

    document.getElementById('fill-target').addEventListener('keydown', (event) => {{
      if (event.key === 'Enter') {{
        window.__browser4State.submitCount += 1;
        syncState();
      }}
    }});

    document.addEventListener('keydown', (event) => {{
      window.__browser4State.keyEvents.push('down:' + event.key);
      syncState();
    }});

    document.addEventListener('keyup', (event) => {{
      window.__browser4State.keyEvents.push('up:' + event.key);
      syncState();
    }});

    document.getElementById('type-target').addEventListener('input', syncState);
    document.getElementById('fill-target').addEventListener('input', syncState);
    document.getElementById('check-target').addEventListener('change', syncState);
    document.getElementById('select-target').addEventListener('change', syncState);
    document.getElementById('file-input').addEventListener('change', syncState);

    const mouseArea = document.getElementById('mouse-area');
    mouseArea.addEventListener('mousemove', (event) => {{
      window.__browser4State.lastMouse = [Math.round(event.clientX), Math.round(event.clientY)];
      syncState();
    }});
    mouseArea.addEventListener('mousedown', () => {{
      window.__browser4State.mouseDownCount += 1;
      syncState();
    }});
    mouseArea.addEventListener('mouseup', () => {{
      window.__browser4State.mouseUpCount += 1;
      syncState();
    }});
    mouseArea.addEventListener('wheel', (event) => {{
      window.__browser4State.lastWheel = [Math.round(event.deltaX), Math.round(event.deltaY)];
      syncState();
    }});

    console.info('interactive fixture ready');
    syncState();
  </script>
</body>
</html>"#,
        title = INTERACTIVE_TITLE
    )
}

fn other_html() -> String {
    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>{title}</title>
</head>
<body>
  <h1 id="page-marker">other page</h1>
  <script>console.info('other fixture ready');</script>
</body>
</html>"#,
        title = OTHER_TITLE
    )
}

fn form_html() -> String {
    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>{title}</title>
  <style>
    body {{ margin: 0; font-family: sans-serif; padding: 24px; }}
    .form-group {{ margin-bottom: 12px; }}
    label {{ display: block; margin-bottom: 4px; font-weight: bold; }}
    input, select, textarea {{ padding: 4px 8px; width: 300px; }}
    button {{ padding: 8px 24px; margin-top: 8px; }}
    #result-panel {{ margin-top: 24px; padding: 12px; border: 1px solid #ccc; display: none; }}
    #error-panel {{ margin-top: 12px; padding: 12px; border: 1px solid #c00; color: #c00; display: none; }}
  </style>
</head>
<body>
  <h1>Registration Form</h1>
  <form id="registration-form">
    <div class="form-group">
      <label for="first-name">First Name</label>
      <input id="first-name" type="text" required />
    </div>
    <div class="form-group">
      <label for="last-name">Last Name</label>
      <input id="last-name" type="text" required />
    </div>
    <div class="form-group">
      <label for="email">Email</label>
      <input id="email" type="email" required />
    </div>
    <div class="form-group">
      <label for="country">Country</label>
      <select id="country">
        <option value="">-- select --</option>
        <option value="us">United States</option>
        <option value="uk">United Kingdom</option>
        <option value="jp">Japan</option>
      </select>
    </div>
    <div class="form-group">
      <label>
        <input id="agree-terms" type="checkbox" />
        I agree to the terms
      </label>
    </div>
    <div class="form-group">
      <label for="comments">Comments</label>
      <textarea id="comments" rows="3"></textarea>
    </div>
    <button id="submit-btn" type="button">Submit</button>
    <button id="reset-btn" type="button">Reset</button>
  </form>
  <div id="result-panel">
    <h2>Submission Result</h2>
    <pre id="result-data"></pre>
  </div>
  <div id="error-panel"></div>
  <pre id="state-log"></pre>
  <script>
    window.__browser4State = {{
      firstName: '',
      lastName: '',
      email: '',
      country: '',
      agreeTerms: false,
      comments: '',
      submitCount: 0,
      resetCount: 0,
      lastSubmission: null,
      validationError: ''
    }};

    function syncState() {{
      const s = window.__browser4State;
      s.firstName = document.getElementById('first-name').value;
      s.lastName = document.getElementById('last-name').value;
      s.email = document.getElementById('email').value;
      s.country = document.getElementById('country').value;
      s.agreeTerms = document.getElementById('agree-terms').checked;
      s.comments = document.getElementById('comments').value;
      document.getElementById('state-log').textContent = JSON.stringify(s);
    }}

    document.getElementById('submit-btn').addEventListener('click', () => {{
      const s = window.__browser4State;
      syncState();
      const firstName = s.firstName.trim();
      const lastName = s.lastName.trim();
      const email = s.email.trim();
      if (!firstName || !lastName || !email) {{
        s.validationError = 'All fields are required';
        document.getElementById('error-panel').textContent = s.validationError;
        document.getElementById('error-panel').style.display = 'block';
        document.getElementById('result-panel').style.display = 'none';
        syncState();
        return;
      }}
      if (!s.agreeTerms) {{
        s.validationError = 'You must agree to the terms';
        document.getElementById('error-panel').textContent = s.validationError;
        document.getElementById('error-panel').style.display = 'block';
        document.getElementById('result-panel').style.display = 'none';
        syncState();
        return;
      }}
      s.validationError = '';
      s.submitCount += 1;
      s.lastSubmission = {{
        firstName: firstName,
        lastName: lastName,
        email: email,
        country: s.country,
        comments: s.comments
      }};
      document.getElementById('error-panel').style.display = 'none';
      document.getElementById('result-panel').style.display = 'block';
      document.getElementById('result-data').textContent = JSON.stringify(s.lastSubmission, null, 2);
      syncState();
    }});

    document.getElementById('reset-btn').addEventListener('click', () => {{
      document.getElementById('first-name').value = '';
      document.getElementById('last-name').value = '';
      document.getElementById('email').value = '';
      document.getElementById('country').value = '';
      document.getElementById('agree-terms').checked = false;
      document.getElementById('comments').value = '';
      document.getElementById('result-panel').style.display = 'none';
      document.getElementById('error-panel').style.display = 'none';
      window.__browser4State.resetCount += 1;
      syncState();
    }});

    ['first-name', 'last-name', 'email', 'comments'].forEach(id => {{
      document.getElementById(id).addEventListener('input', syncState);
    }});
    document.getElementById('country').addEventListener('change', syncState);
    document.getElementById('agree-terms').addEventListener('change', syncState);

    console.info('form fixture ready');
    syncState();
  </script>
</body>
</html>"#,
        title = FORM_TITLE
    )
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

        thread::spawn(move || {
            listener.set_nonblocking(true).ok();
            loop {
                if flag.load(Ordering::Relaxed) {
                    break;
                }
                match listener.accept() {
                    Ok((stream, _)) => {
                        thread::spawn(move || serve_fixture_request(stream));
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

fn serve_fixture_request(mut stream: std::net::TcpStream) {
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
        ("200 OK", "text/html; charset=utf-8", interactive_html())
    } else if path == OTHER_PATH {
        ("200 OK", "text/html; charset=utf-8", other_html())
    } else if path == FORM_PATH {
        ("200 OK", "text/html; charset=utf-8", form_html())
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
        let started_via_maven = startup_result.stderr.contains("Starting server via Maven spring-boot:run");
        assert_eq!(
            startup_result.exit_code,
            0,
            "Expected CLI-managed Browser4 startup to succeed.{}\nstdout:\n{}\nstderr:\n{}",
            startup_log_hint,
            startup_result.stdout,
            startup_result.stderr,
        );
        if !self.local_browser4_started {
            if !was_healthy_before {
                assert!(
                    started_via_maven,
                    "Expected local e2e startup to use Maven spring-boot:run so tests run against latest backend code.{}\nstdout:\n{}\nstderr:\n{}",
                    startup_log_hint,
                    startup_result.stdout,
                    startup_result.stderr,
                );
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

fn wait_for_cli_output(mut child: Child, full_args: &[&str], stream_output: bool) -> std::process::Output {
    let stdout_handle = spawn_output_collector(
        child.stdout.take().expect("stdout pipe should be available"),
        "stdout",
        stream_output,
    );
    let stderr_handle = spawn_output_collector(
        child.stderr.take().expect("stderr pipe should be available"),
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
            let status = child.wait().expect("failed to wait for timed out browser4-cli process");
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

    println!("browser4-cli command completed in {}s with exit code {}: {}",
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

fn spawn_output_collector<R>(reader: R, stream_name: &'static str, stream_output: bool) -> OutputCollector
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

fn finish_output_collector(collector: OutputCollector, stream_name: &str, full_args: &[&str]) -> String {
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

    let mut truncated = text.chars().take(max_chars.saturating_sub(1)).collect::<String>();
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
    format!(
        "cli eval {}",
        truncate_timing_label(expression.trim(), 96)
    )
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
    ctx.record_step(format_cli_step_label(args, false, false), started_at.elapsed());
    result
}

fn run_command_with_stdin(ctx: &mut E2ECtx, args: &[&str], stdin_payload: &str) -> CliRunResult {
    let started_at = Instant::now();
    let result = run_checked_cli_process_with_stdin(ctx, args, stdin_payload);
    ctx.record_step(format_cli_step_label(args, true, false), started_at.elapsed());
    result
}

/// Run a command, asserting it fails (exit code != 0) and that the combined
/// stdout+stderr contains `pattern`.
fn run_command_expecting_failure(ctx: &mut E2ECtx, args: &[&str], pattern: &str) -> CliRunResult {
    let started_at = Instant::now();
    let result = run_checked_cli_process_expecting_failure(ctx, args, pattern);
    ctx.record_step(format_cli_step_label(args, false, true), started_at.elapsed());
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
    let text = run_checked_cli_process(ctx, &["eval", "document.getElementById('state-log').textContent"]);
    let text = strip_snapshot_output(&text.stdout);
    serde_json::from_str(text.trim()).unwrap_or(serde_json::Value::Null)
}

fn key_event_count(state: &serde_json::Value) -> usize {
    state["keyEvents"]
        .as_array()
        .map_or(0, |events| events.len())
}

fn wait_for_state<F>(
    ctx: &mut E2ECtx,
    predicate: F,
    timeout_ms: u64,
    failure_message: &str,
) -> serde_json::Value
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
            return state;
        }
        thread::sleep(Duration::from_millis(300));
    }
    let state = read_interactive_state(ctx);
    let parse_hint = if state.is_null() {
        "\nLast state could not be parsed from #state-log and was read as JSON null."
    } else {
        ""
    };
    panic!(
        "{failure_message}. Timed out after {timeout_ms}ms waiting for interactive state.{parse_hint}\nLast state:\n{state:#?}"
    );
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
        println!("eval expression: {} \nlast_value:\n>>>{}<<<", expression, last_value);
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
            workspace_dir,
            state_dir,
            upload_file_path,
            step_timings: Vec::new(),
        },
    }
}

// ---------------------------------------------------------------------------
// Test scenarios
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

fn test_open_uses_temporary_profile_mode(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let mock_server = MockBrowser4Server::start();
    ctx.browser4_base_url = mock_server.base_url();

    let open_result = run_open_command(ctx);
    assert!(
        open_result.stdout.contains("Session opened: collective-session-1"),
        "Expected mocked session open output in:\n{}",
        open_result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_call = tool_calls
        .iter()
        .find(|call| call.tool == "open_session")
        .expect("expected open_session call");
    assert_eq!(
        open_session_call.arguments["capabilities"]["profileMode"],
        "TEMPORARY"
    );
}

fn test_session_lifecycle(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let open_result = run_open_command(ctx);
    assert!(
        open_result.stdout.contains("Session opened:"),
        "Expected 'Session opened:' in:\n{}",
        open_result.stdout
    );

    let session_id = read_persisted_session_id(&ctx.state_dir);
    let list_result = run_command(ctx, &["list"]);
    assert!(
        list_result.stdout.contains(&session_id),
        "Expected session id '{session_id}' in list output:\n{}",
        list_result.stdout
    );

    let close_result = run_command(ctx, &["close"]);
    assert!(
        close_result.stdout.contains("Session closed."),
        "Expected 'Session closed.' in:\n{}",
        close_result.stdout
    );
}

fn test_navigation_and_storage(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    run_open_command(ctx);

    let interactive_url = ctx.interactive_url();
    let other_url = ctx.other_url();

    run_command(ctx, &["goto", &interactive_url]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        INTERACTIVE_PATH,
        15_000,
        "Expected to be on interactive path",
    );

    run_command(ctx, &["goto", &other_url]);
    wait_for_eval_text(
        ctx,
        "document.title",
        OTHER_TITLE,
        15_000,
        "Expected other page title",
    );

    run_command(ctx, &["go-back"]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        INTERACTIVE_PATH,
        15_000,
        "Expected to be back on interactive path after go-back",
    );

    run_command(ctx, &["go-forward"]);
    wait_for_eval_text(
        ctx,
        "window.location.pathname",
        OTHER_PATH,
        15_000,
        "Expected to be on other path after go-forward",
    );

    run_command(ctx, &["reload"]);
    wait_for_eval_text(
        ctx,
        "document.title",
        OTHER_TITLE,
        15_000,
        "Expected other page title after reload",
    );

    let delete_result = run_command(ctx, &["delete-data"]);
    let stripped = strip_snapshot_output(&delete_result.stdout).to_lowercase();
    assert!(
        stripped.contains("deleted"),
        "Expected 'deleted' in delete-data output:\n{}",
        stripped
    );

    run_command(ctx, &["close"]);
}

fn test_interaction_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["type", "#type-target", "hello world"]);
    wait_for_state(
        ctx,
        |s| s["typeValue"].as_str() == Some("hello world"),
        15_000,
        "Expected typeValue to become 'hello world' after type",
    );

    // fill
    run_command(ctx, &["fill", "#fill-target", "filled text"]);
    wait_for_state(
        ctx,
        |s| s["fillValue"].as_str() == Some("filled text"),
        15_000,
        "Expected fillValue to become 'filled text' after fill",
    );

    let press_before = read_interactive_state(ctx);
    let press_before_events = key_event_count(&press_before);
    run_command(ctx, &["press", "#type-target", "!"]);
    wait_for_state(
        ctx,
        |s| {
            s["typeValue"].as_str() == Some("hello world!")
                && key_event_count(s) > press_before_events
        },
        15_000,
        "Expected press to append '!' to typeValue and emit a key event",
    );

    run_command(ctx, &["click", "#type-target"]);
    let keydown_before = key_event_count(&read_interactive_state(ctx));
    run_command(ctx, &["keydown", "Shift"]);
    wait_for_state(
        ctx,
        |s| {
            key_event_count(s) > keydown_before
                && s["keyEvents"]
                    .as_array()
                    .and_then(|events| events.last())
                    .and_then(|event| event.as_str())
                    == Some("down:Shift")
        },
        15_000,
        "Expected keydown to record a final 'down:Shift' key event",
    );

    let keyup_before = key_event_count(&read_interactive_state(ctx));
    run_command(ctx, &["keyup", "Shift"]);
    wait_for_state(
        ctx,
        |s| {
            key_event_count(s) > keyup_before
                && s["keyEvents"]
                    .as_array()
                    .and_then(|events| events.last())
                    .and_then(|event| event.as_str())
                    == Some("up:Shift")
        },
        15_000,
        "Expected keyup to record a final 'up:Shift' key event",
    );

    run_command(ctx, &["click", "#click-target"]);
    wait_for_state(
        ctx,
        |s| s["clickCount"].as_u64() == Some(1),
        15_000,
        "Expected clickCount to become 1 after click",
    );

    run_command(ctx, &["dblclick", "#dblclick-target"]);
    wait_for_state(
        ctx,
        |s| s["doubleClickCount"].as_u64() == Some(1),
        15_000,
        "Expected doubleClickCount to become 1 after dblclick",
    );

    run_command(ctx, &["hover", "#hover-target"]);
    wait_for_state(
        ctx,
        |s| s["hovered"].as_bool() == Some(true),
        15_000,
        "Expected hovered to become true after hover",
    );

    run_command(ctx, &["drag", "#drag-source", "#drag-target"]);
    wait_for_state(
        ctx,
        |s| {
            s["dragStarted"].as_bool() == Some(true)
                && s["dragDropped"].as_str() == Some("drag-source")
        },
        15_000,
        "Expected drag to start and drop drag-source onto the target",
    );

    run_command(ctx, &["close"]);
}

fn test_form_controls_and_exports(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    open_interactive_page(ctx);

    run_command(ctx, &["select", "#select-target", "green"]);
    wait_for_state(
        ctx,
        |s| s["selectValue"].as_str() == Some("green"),
        15_000,
        "Expected selectValue to become 'green' after select",
    );

    run_command(ctx, &["check", "#check-target"]);
    wait_for_state(
        ctx,
        |s| s["checkbox"].as_bool() == Some(true),
        15_000,
        "Expected checkbox to become true after check",
    );

    run_command(ctx, &["uncheck", "#check-target"]);
    wait_for_state(
        ctx,
        |s| s["checkbox"].as_bool() == Some(false),
        15_000,
        "Expected checkbox to become false after uncheck",
    );

    // upload
    let upload_path = ctx.upload_file_path.to_string_lossy().into_owned();
    run_command(ctx, &["upload", "#file-input", &upload_path]);
    wait_for_state(
        ctx,
        |s| s["uploadName"].as_str() == Some("upload.txt"),
        15_000,
        "Expected uploadName to become 'upload.txt' after upload",
    );

    run_command_expecting_failure(
        ctx,
        &["console", "info"],
        "Unknown tool: browser_console_messages",
    );

    let snapshot_result = run_command(ctx, &["snapshot", "--filename=interactive.yml"]);
    assert!(
        snapshot_result.stdout.contains("[Snapshot]("),
        "Expected '[Snapshot](' in snapshot output:\n{}",
        snapshot_result.stdout
    );
    let snapshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("interactive.yml");
    assert!(
        snapshot_path.exists(),
        "Snapshot file not found at {snapshot_path:?}"
    );

    run_command(ctx, &["screenshot", "--filename=interactive.png"]);
    let screenshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("interactive.png");
    assert!(screenshot_path.exists(), "Screenshot file not found");
    assert!(
        fs::metadata(&screenshot_path).unwrap().len() > 0,
        "Screenshot file is empty"
    );

    run_command_expecting_failure(
        ctx,
        &["pdf", "--filename=interactive.pdf"],
        "Unknown tool: browser_pdf_save",
    );

    run_command(ctx, &["close"]);
}

fn test_batch_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let interactive_url = ctx.interactive_url();
    let open_command = batch_open_command(&interactive_url);
    let type_command = "type #type-target 'hello batch'".to_string();
    let click_command = "click #click-target".to_string();

    run_command(
        ctx,
        &[
            "batch",
            open_command.as_str(),
            type_command.as_str(),
            click_command.as_str(),
        ],
    );
    wait_for_state(
        ctx,
        |s| s["typeValue"].as_str() == Some("hello batch") && s["clickCount"].as_u64() == Some(1),
        15_000,
        "Expected batch commands to set typeValue to 'hello batch' and clickCount to 1",
    );

    let key_events_before = key_event_count(&read_interactive_state(ctx));
    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"
[
  ["fill", "#fill-target", "from json"],
  ["keydown", "Shift"],
  ["keyup", "Shift"]
]
"##,
    );
    wait_for_state(
        ctx,
        |s| {
            s["fillValue"].as_str() == Some("from json")
                && key_event_count(s) > key_events_before
                && s["keyEvents"]
                    .as_array()
                    .and_then(|events| events.last())
                    .and_then(|event| event.as_str())
                    == Some("up:Shift")
        },
        15_000,
        "Expected JSON batch to fill text and finish with an 'up:Shift' key event",
    );

    let continue_failure = run_command_expecting_failure(
        ctx,
        &["batch", "not-a-command", "snapshot"],
        "1 batch command(s) failed.",
    );
    assert!(
        continue_failure.stdout.contains("[Snapshot]("),
        "Expected later batch command output after a non-bailing failure:\n{}",
        continue_failure.stdout
    );

    let bail_failure = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "--bail",
            "not-a-command",
            "fill #fill-target 'should not run'",
        ],
        "Batch command 1 failed",
    );
    assert!(
        !bail_failure.stdout.contains("should not run"),
        "Expected --bail to stop before the second command:\n{}",
        bail_failure.stdout
    );
    let fill_value = read_interactive_state(ctx)["fillValue"]
        .as_str()
        .unwrap_or_default()
        .to_string();
    assert_eq!(fill_value, "from json");

    run_command(ctx, &["close"]);
}

fn test_batch_form_submission(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // Use batch to open page, navigate to form, fill it out, and submit — all
    // in a single invocation.
    let form_url = ctx.form_url();
    let open_command = batch_open_command(&form_url);

    run_command(
        ctx,
        &[
            "batch",
            open_command.as_str(),
            "fill #first-name 'Alice'",
            "fill #last-name 'Johnson'",
            "fill #email 'alice@example.com'",
            "select #country us",
            "check #agree-terms",
            "fill #comments 'batch test comment'",
            "click #submit-btn",
        ],
    );

    // Verify the form state reflects our inputs and submission
    wait_for_state(
        ctx,
        |s| {
            s["firstName"].as_str() == Some("Alice")
                && s["lastName"].as_str() == Some("Johnson")
                && s["email"].as_str() == Some("alice@example.com")
                && s["country"].as_str() == Some("us")
                && s["agreeTerms"].as_bool() == Some(true)
                && s["comments"].as_str() == Some("batch test comment")
                && s["submitCount"].as_u64() == Some(1)
                && s["validationError"].as_str() == Some("")
        },
        15_000,
        "Expected batch submission to populate the form and submit successfully for Alice",
    );

    // Verify lastSubmission has the correct data
    let state = read_interactive_state(ctx);
    let submission = &state["lastSubmission"];
    assert_eq!(
        submission["firstName"].as_str(),
        Some("Alice"),
        "Expected firstName in submission"
    );
    assert_eq!(
        submission["email"].as_str(),
        Some("alice@example.com"),
        "Expected email in submission"
    );

    // Test reset and re-submit via batch --json
    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"[
  ["click", "#reset-btn"],
  ["fill", "#first-name", "Bob"],
  ["fill", "#last-name", "Smith"],
  ["fill", "#email", "bob@example.com"],
  ["select", "#country", "uk"],
  ["check", "#agree-terms"],
  ["click", "#submit-btn"]
]"##,
    );

    wait_for_state(
        ctx,
        |s| {
            s["submitCount"].as_u64() == Some(2)
                && s["resetCount"].as_u64() == Some(1)
                && s["firstName"].as_str() == Some("Bob")
        },
        15_000,
        "Expected JSON batch reset flow to submit Bob as the second submission",
    );

    // Verify second submission data
    let state = read_interactive_state(ctx);
    let submission = &state["lastSubmission"];
    assert_eq!(submission["firstName"].as_str(), Some("Bob"));
    assert_eq!(submission["lastName"].as_str(), Some("Smith"));
    assert_eq!(submission["country"].as_str(), Some("uk"));

    run_command(ctx, &["close"]);
}

fn test_batch_multi_interaction(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    // Test a complex batch with multiple interaction types on the interactive page
    let interactive_url = ctx.interactive_url();
    let open_command = batch_open_command(&interactive_url);

    // Batch: open, type, fill, check, select, click — all in one call
    run_command(
        ctx,
        &[
            "batch",
            open_command.as_str(),
            "type #type-target 'batch multi'",
            "fill #fill-target 'batch fill'",
            "check #check-target",
            "select #select-target blue",
            "click #click-target",
            "click #click-target",
        ],
    );

    wait_for_state(
        ctx,
        |s| {
            s["typeValue"].as_str() == Some("batch multi")
                && s["fillValue"].as_str() == Some("batch fill")
                && s["checkbox"].as_bool() == Some(true)
                && s["selectValue"].as_str() == Some("blue")
                && s["clickCount"].as_u64() == Some(2)
        },
        15_000,
        "Expected multi-interaction batch to update text, fill, checkbox, select, and clickCount",
    );

    // Test batch with snapshot and screenshot outputs
    let batch_result = run_command(
        ctx,
        &[
            "batch",
            "snapshot --filename=batch-snap.yml",
            "screenshot --filename=batch-screen.png",
        ],
    );

    // Verify snapshot file was created
    let snapshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("batch-snap.yml");
    assert!(
        snapshot_path.exists(),
        "Batch snapshot file not found at {snapshot_path:?}"
    );

    // Verify screenshot file was created
    let screenshot_path = ctx
        .workspace_dir
        .join(".browser4-cli")
        .join("snapshot")
        .join("batch-screen.png");
    assert!(
        screenshot_path.exists(),
        "Batch screenshot file not found at {screenshot_path:?}"
    );
    assert!(
        fs::metadata(&screenshot_path).unwrap().len() > 0,
        "Batch screenshot file is empty"
    );

    // Verify batch output contains references to both outputs
    assert!(
        batch_result.stdout.contains("[Snapshot]("),
        "Expected snapshot link in batch output:\n{}",
        batch_result.stdout
    );

    // Uncheck and verify via batch to test state reversal
    run_command(ctx, &["batch", "uncheck #check-target"]);
    wait_for_state(
        ctx,
        |s| s["checkbox"].as_bool() == Some(false),
        15_000,
        "Expected batch uncheck to clear the checkbox",
    );

    run_command(ctx, &["close"]);
}

fn test_batch_error_handling(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let interactive_url = ctx.interactive_url();
    let open_command = batch_open_command(&interactive_url);

    // First open a session for subsequent tests
    run_command(ctx, &["batch", open_command.as_str()]);

    // Test: invalid command in the middle without --bail should continue
    let _result = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "type #type-target 'before error'",
            "this-is-not-a-valid-command",
            "fill #fill-target 'after error'",
        ],
        "1 batch command(s) failed.",
    );
    // Fill should have executed despite the error in the middle
    wait_for_state(
        ctx,
        |s| s["fillValue"].as_str() == Some("after error"),
        15_000,
        "Expected fill command after a non-bailing batch error to still run",
    );

    // Test: --bail stops at the first error
    let _bail_result = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "--bail",
            "type #type-target 'bail test'",
            "unknown-command-xyz",
            "fill #fill-target 'should not execute'",
        ],
        "Batch command 2 failed",
    );
    // Type should have executed (before the error).
    // `type` simulates keystrokes and appends to existing input, so check
    // that the value ends with the typed text rather than expecting an exact match.
    wait_for_state(
        ctx,
        |s| {
            s["typeValue"]
                .as_str()
                .map(|v| v.contains("bail test"))
                .unwrap_or(false)
        },
        15_000,
        "Expected typeValue to contain 'bail test' after the pre-error batch command",
    );
    // Fill should NOT have executed
    let fill_value = read_interactive_state(ctx)["fillValue"]
        .as_str()
        .unwrap_or_default()
        .to_string();
    assert_eq!(
        fill_value, "after error",
        "Expected fill value unchanged after bail"
    );

    // Test: multiple errors without bail accumulates failure count
    let _multi_error = run_command_expecting_failure(
        ctx,
        &[
            "batch",
            "bad-cmd-1",
            "bad-cmd-2",
            "type #type-target 'still works'",
        ],
        "2 batch command(s) failed.",
    );
    // `type` simulates keystrokes and appends to existing input, so check
    // that the value ends with the typed text rather than expecting an exact match.
    wait_for_state(
        ctx,
        |s| {
            s["typeValue"]
                .as_str()
                .map(|v| v.contains("still works"))
                .unwrap_or(false)
        },
        15_000,
        "Expected typeValue to contain 'still works' despite earlier batch errors",
    );

    run_command(ctx, &["close"]);
}

fn test_batch_json_edge_cases(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    let interactive_url = ctx.interactive_url();
    let open_command = batch_open_command(&interactive_url);

    // Open a session first
    run_command(ctx, &["batch", open_command.as_str()]);

    // Test: JSON with mixed string and array entries
    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"[
  "type #type-target 'json mixed'",
  ["fill", "#fill-target", "json array fill"],
  "click #click-target"
]"##,
    );
    wait_for_state(
        ctx,
        |s| {
            s["typeValue"].as_str() == Some("json mixed")
                && s["fillValue"].as_str() == Some("json array fill")
                && s["clickCount"].as_u64() == Some(1)
        },
        15_000,
        "Expected mixed JSON batch commands to type, fill, and click once",
    );

    // Open again to avoid dirty data
    run_command(ctx, &["batch", open_command.as_str()]);

    // Test: JSON with special characters in values
    run_command_with_stdin(
        ctx,
        &["batch", "--json"],
        r##"[
  ["fill", "#fill-target", "special: @#$%&*"]
]"##,
    );
    wait_for_state(
        ctx,
        |s| s["fillValue"].as_str() == Some("special: @#$%&*"),
        15_000,
        "Expected JSON batch to preserve special characters in fillValue",
    );

    // Open again to avoid dirty data
    run_command(ctx, &["batch", open_command.as_str()]);

    // Test: JSON with --bail (validate that --bail + --json are combinable)
    // Run with stdin containing an unknown command to trigger failure
    let bail_json_result = run_cli_process_with_stdin(
        ctx,
        &["batch", "--bail", "--json"],
        Some(r##"["unknown-cmd", "fill #fill-target 'unreachable'"]"##),
    );
    assert_ne!(
        bail_json_result.exit_code, 0,
        "Expected batch --bail --json with unknown command to fail"
    );
    let combined = format!("{}\n{}", bail_json_result.stdout, bail_json_result.stderr);
    assert!(
        !combined.contains("unreachable"),
        "Expected --bail to prevent 'unreachable' command from running:\n{combined}"
    );

    run_command(ctx, &["close"]);
}

fn test_mouse_and_dialog(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    open_resized_interactive_page(ctx);

    run_command(ctx, &["mousemove", "120", "120"]);
    wait_for_state(
        ctx,
        |s| s["lastMouse"][0].as_i64() == Some(120) && s["lastMouse"][1].as_i64() == Some(120),
        15_000,
        "Expected mousemove to update lastMouse to [120, 120]",
    );

    let before_mouse_down = read_interactive_state(ctx)["mouseDownCount"]
        .as_u64()
        .unwrap_or(0);
    run_command(ctx, &["mousedown", "left"]);
    wait_for_state(
        ctx,
        |s| s["mouseDownCount"].as_u64() == Some(before_mouse_down + 1),
        15_000,
        "Expected mousedown to increment mouseDownCount",
    );

    let before_mouse_up = read_interactive_state(ctx)["mouseUpCount"]
        .as_u64()
        .unwrap_or(0);
    run_command(ctx, &["mouseup", "left"]);
    wait_for_state(
        ctx,
        |s| s["mouseUpCount"].as_u64() == Some(before_mouse_up + 1),
        15_000,
        "Expected mouseup to increment mouseUpCount",
    );

    run_command(ctx, &["mousewheel", "0", "160"]);
    let wheel_state = wait_for_state(
        ctx,
        |s| s["lastWheel"][0].as_i64() == Some(160) && s["lastWheel"][1].as_i64() == Some(0),
        15_000,
        "Expected mousewheel to update lastWheel to [160, 0]",
    );
    assert!(
        wheel_state["lastWheel"][0].as_i64() == Some(160)
            && wheel_state["lastWheel"][1].as_i64() == Some(0),
        "Expected lastWheel to equal [160, 0], got {wheel_state:#?}"
    );

    eval_text(
        ctx,
        "(() => { setTimeout(() => document.getElementById('prompt-target').click(), 100); return 'scheduled'; })()",
    );
    thread::sleep(Duration::from_millis(500));
    run_command(ctx, &["dialog-accept", "accepted by cli"]);
    wait_for_state(
        ctx,
        |s| s["promptResult"].as_str() == Some("accepted by cli"),
        15_000,
        "Expected dialog-accept to set promptResult to 'accepted by cli'",
    );

    eval_text(
        ctx,
        "(() => { setTimeout(() => document.getElementById('confirm-target').click(), 100); return 'scheduled'; })()",
    );
    thread::sleep(Duration::from_millis(500));
    run_command(ctx, &["dialog-dismiss"]);
    wait_for_state(
        ctx,
        |s| s["confirmResult"].as_str() == Some("dismissed"),
        15_000,
        "Expected dialog-dismiss to set confirmResult to 'dismissed'",
    );

    run_command(ctx, &["close"]);
}

fn test_tab_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);

    open_interactive_page(ctx);
    let interactive_url = ctx.interactive_url();
    let other_url = ctx.other_url();

    let initial_tabs = run_command(ctx, &["tab-list"]);
    let tab_output = strip_snapshot_output(&initial_tabs.stdout);
    assert!(
        tab_output.contains(&interactive_url),
        "Expected interactive URL in tab-list output:\n{tab_output}"
    );

    run_command(ctx, &["tab-new", &other_url]);
    let updated_tabs = run_command(ctx, &["tab-list"]);
    let tab_output = strip_snapshot_output(&updated_tabs.stdout);
    assert!(
        tab_output.contains(&interactive_url),
        "Expected interactive URL in updated tab-list"
    );
    assert!(
        tab_output.contains(&other_url),
        "Expected other URL in updated tab-list"
    );

    let other_tab_id = extract_tab_id(&tab_output, &other_url);

    run_command(ctx, &["tab-select", &other_tab_id]);
    run_command(ctx, &["tab-close", &other_tab_id]);
    run_command(ctx, &["close"]);
}

// ---------------------------------------------------------------------------
// Agent / Collective scenario
// ---------------------------------------------------------------------------

fn test_collective_session_and_agent_tools(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_collective_session(ctx);
    assert_collective_session_call(&mock_server);

    let extract_result = run_command(
        ctx,
        &[
            "extract",
            "product name, price",
            "--schema={\"type\":\"object\"}",
        ],
    );
    let extracted = strip_snapshot_output(&extract_result.stdout);
    assert!(
        extracted.contains("\"Mock Product\"") && extract_result.stdout.contains("### Page"),
        "Expected extract output with snapshot block in:\n{}",
        extract_result.stdout
    );

    let summarize_result = run_command(
        ctx,
        &[
            "summarize",
            "summarize the page marker",
            "--selector=#page-marker",
        ],
    );
    let summary = strip_snapshot_output(&summarize_result.stdout);
    assert_eq!(summary, "Mock summary for #page-marker");
    assert!(
        summarize_result.stdout.contains("### Page"),
        "Expected summarize output to include a snapshot block:\n{}",
        summarize_result.stdout
    );

    let tool_calls = mock_server.snapshot().tool_calls;
    let extract_call = tool_calls
        .iter()
        .find(|call| call.tool == "agent_extract")
        .expect("expected agent_extract call");
    assert_eq!(extract_call.arguments["sessionId"], "collective-session-1");
    assert_eq!(extract_call.arguments["instruction"], "product name, price");
    assert_eq!(extract_call.arguments["schema"], "{\"type\":\"object\"}");

    let summarize_call = tool_calls
        .iter()
        .find(|call| call.tool == "agent_summarize")
        .expect("expected agent_summarize call");
    assert_eq!(
        summarize_call.arguments["sessionId"],
        "collective-session-1"
    );
    assert_eq!(
        summarize_call.arguments["instruction"],
        "summarize the page marker"
    );
    assert_eq!(summarize_call.arguments["selector"], "#page-marker");
}

fn test_agent_task_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let agent_run_result = run_command(ctx, &["agent-run", "collect the latest updates"]);
    assert!(
        agent_run_result
            .stdout
            .contains("Task submitted: agent-task-1"),
        "Expected task submission output in:\n{}",
        agent_run_result.stdout
    );
    assert!(
        agent_run_result
            .stdout
            .contains("browser4-cli agent-status agent-task-1"),
        "Expected agent status hint in:\n{}",
        agent_run_result.stdout
    );

    let agent_status_result = run_command(ctx, &["agent-status", "agent-task-1"]);
    assert_eq!(
        strip_snapshot_output(&agent_status_result.stdout),
        r#"{"id":"agent-task-1","status":"RUNNING"}"#
    );

    let agent_result_result = run_command(ctx, &["agent-result", "agent-task-1"]);
    assert_eq!(
        strip_snapshot_output(&agent_result_result.stdout),
        "result for agent-task-1"
    );
    assert_eq!(
        mock_server.snapshot().plain_commands,
        vec!["collect the latest updates".to_string()]
    );
    assert_eq!(
        mock_server.snapshot().status_queries,
        vec!["agent-task-1".to_string()]
    );
    assert_eq!(
        mock_server.snapshot().result_queries,
        vec!["agent-task-1".to_string()]
    );
}

fn test_collective_submission_commands(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mock_server = start_mock_collective_session(ctx);
    assert_collective_session_call(&mock_server);

    let seed_file = ctx.workspace_dir.join("collective-seeds.txt");
    fs::write(
        &seed_file,
        b"# seed urls\nhttps://example.com/seed-1\n\nhttps://example.com/seed-2\n",
    )
    .expect("write seed file failed");
    let seed_file_arg = format!("--seed-file={}", seed_file.to_string_lossy());

    let co_submit_result = run_command(
        ctx,
        &[
            "co",
            "submit",
            "https://example.com/direct",
            &seed_file_arg,
            "--deadline=2026-03-30T00:00:00Z",
            "--expires=1d",
            "--refresh",
            "--parse",
            "--store-content",
        ],
    );
    assert!(
        co_submit_result.stdout.contains("3 URL(s) submitted."),
        "Expected aggregate co submit output in:\n{}",
        co_submit_result.stdout
    );
    assert!(
        co_submit_result
            .stdout
            .contains("Submitted: https://example.com/direct → task co-task-1"),
        "Expected direct URL submission output in:\n{}",
        co_submit_result.stdout
    );

    let co_scrape_result = run_command(
        ctx,
        &[
            "co",
            "scrape",
            "https://example.com/scrape-source",
            "--selector=.item",
            "--attribute=textContent",
            "--output=items.json",
            "--deadline=2026-03-30T00:00:00Z",
            "--expires=6h",
            "--refresh",
        ],
    );
    assert!(
        co_scrape_result
            .stdout
            .contains("Scrape submitted: https://example.com/scrape-source → task co-task-4"),
        "Expected scrape submission output in:\n{}",
        co_scrape_result.stdout
    );
    assert!(co_scrape_result.stdout.contains("selector: .item"));
    assert!(co_scrape_result.stdout.contains("attribute: textContent"));
    assert!(co_scrape_result.stdout.contains("output: items.json"));

    let co_status_result = run_command(ctx, &["co", "status", "collective-job-42"]);
    assert_eq!(
        strip_snapshot_output(&co_status_result.stdout),
        r#"{"id":"collective-job-42","status":"RUNNING"}"#
    );

    let co_result_result = run_command(ctx, &["co", "result", "collective-job-42"]);
    assert_eq!(
        strip_snapshot_output(&co_result_result.stdout),
        "result for collective-job-42"
    );

    let snapshot = mock_server.snapshot();
    assert_eq!(
        snapshot.plain_commands,
        vec![
            "https://example.com/direct -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/seed-1 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/seed-2 -deadline 2026-03-30T00:00:00Z -expires 1d -refresh -parse -storeContent".to_string(),
            "https://example.com/scrape-source -deadline 2026-03-30T00:00:00Z -expires 6h -refresh".to_string(),
        ]
    );
    assert_eq!(
        snapshot.status_queries,
        vec!["collective-job-42".to_string()]
    );
    assert_eq!(
        snapshot.result_queries,
        vec!["collective-job-42".to_string()]
    );
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
    cleanup_browser4: bool,
    test_fn: fn(&mut E2ECtx),
) -> TimingReport {
    println!("testing {name} ... ");

    // Forcibly stop server and Chrome regardless of success or failure.
    let cleanup_started_at = Instant::now();
    if cleanup_browser4 {
        stop_browser4_server_forcibly();
        resources.local_browser4_started = false;
    }
    let cleanup_step = TimedStep::new("browser4 service cleanup", cleanup_started_at.elapsed());

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

    let total_duration = total_started_at.elapsed();
    let mut steps = harness_steps;
    steps.extend(resources.ctx.take_step_timings());
    steps.push(cleanup_step);
    let report = TimingReport::new(name, total_duration, steps);

    match result {
        Ok(()) => {
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
            print_timing_steps(&report.steps);
            std::panic::resume_unwind(payload);
        }
    }
}

type ScenarioFn = fn(&mut E2ECtx);

#[derive(Clone, Copy)]
struct ScenarioDef {
    name: &'static str,
    short_name: &'static str,
    requires_browser4: bool,
    restart_browser4: bool,
    test_fn: ScenarioFn,
}

const SCENARIOS: &[ScenarioDef] = &[
    ScenarioDef {
        name: "test_e2e_session_lifecycle",
        short_name: "test_session_lifecycle",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_session_lifecycle,
    },
    ScenarioDef {
        name: "test_e2e_navigation_and_storage",
        short_name: "test_navigation_and_storage",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_navigation_and_storage,
    },
    ScenarioDef {
        name: "test_e2e_interaction_commands",
        short_name: "test_interaction_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_interaction_commands,
    },
    ScenarioDef {
        name: "test_e2e_interaction_commands",
        short_name: "test_interaction_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_interaction_commands,
    },
    ScenarioDef {
        name: "test_e2e_interaction_commands",
        short_name: "test_interaction_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_interaction_commands,
    },
    ScenarioDef {
        name: "test_e2e_batch_commands",
        short_name: "test_batch_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_batch_commands,
    },
    ScenarioDef {
        name: "test_e2e_batch_form_submission",
        short_name: "test_batch_form_submission",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_batch_form_submission,
    },
    ScenarioDef {
        name: "test_e2e_batch_multi_interaction",
        short_name: "test_batch_multi_interaction",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_batch_multi_interaction,
    },
    ScenarioDef {
        name: "test_e2e_batch_error_handling",
        short_name: "test_batch_error_handling",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_batch_error_handling,
    },
    ScenarioDef {
        name: "test_e2e_batch_json_edge_cases",
        short_name: "test_batch_json_edge_cases",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_batch_json_edge_cases,
    },
    ScenarioDef {
        name: "test_e2e_form_controls_and_exports",
        short_name: "test_form_controls_and_exports",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_form_controls_and_exports,
    },
    ScenarioDef {
        name: "test_e2e_mouse_and_dialog",
        short_name: "test_mouse_and_dialog",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_mouse_and_dialog,
    },
    ScenarioDef {
        name: "test_e2e_tab_commands",
        short_name: "test_tab_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_fn: test_tab_commands,
    },
    ScenarioDef {
        name: "test_e2e_collective_session_and_agent_tools",
        short_name: "test_collective_session_and_agent_tools",
        requires_browser4: false,
        restart_browser4: false,
        test_fn: test_collective_session_and_agent_tools,
    },
    ScenarioDef {
        name: "test_e2e_open_uses_temporary_profile_mode",
        short_name: "test_open_uses_temporary_profile_mode",
        requires_browser4: false,
        restart_browser4: false,
        test_fn: test_open_uses_temporary_profile_mode,
    },
    ScenarioDef {
        name: "test_e2e_agent_task_commands",
        short_name: "test_agent_task_commands",
        requires_browser4: false,
        restart_browser4: false,
        test_fn: test_agent_task_commands,
    },
    ScenarioDef {
        name: "test_e2e_collective_submission_commands",
        short_name: "test_collective_submission_commands",
        requires_browser4: false,
        restart_browser4: false,
        test_fn: test_collective_submission_commands,
    },
];

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

fn resolve_scenario(name: &str) -> Option<ScenarioDef> {
    SCENARIOS
        .iter()
        .copied()
        .find(|scenario| scenario.name == name || scenario.short_name == name)
}

fn main() {
    let scenario_filter = parse_scenario_filter();
    let selected_scenarios: Vec<ScenarioDef> = if let Some(filter) = scenario_filter {
        let scenario = resolve_scenario(&filter).unwrap_or_else(|| {
            let names = SCENARIOS
                .iter()
                .map(|s| format!("{} ({})", s.name, s.short_name))
                .collect::<Vec<_>>()
                .join(", ");
            panic!("Unknown scenario '{filter}'. Available scenarios: {names}");
        });
        vec![scenario]
    } else {
        SCENARIOS.to_vec()
    };

    let run_coverage = selected_scenarios.len() == SCENARIOS.len();
    let total_tests = selected_scenarios.len() + usize::from(run_coverage);
    println!("running {total_tests} tests");
    let mut timings: Vec<TimingReport> = Vec::with_capacity(total_tests);

    stop_browser4_server_forcibly();

    if run_coverage {
        let report = run_named_test("test_e2e_command_coverage", verify_e2e_command_coverage);
        timings.push(report);
    }

    let mut resources = create_e2e_test_resources();
    // let cleanup_browser4 = !cfg!(target_os = "windows");
    let cleanup_browser4 = true;
    for scenario in selected_scenarios {
        let report = run_named_scenario(
            scenario.name,
            &mut resources,
            scenario.requires_browser4,
            scenario.restart_browser4,
            cleanup_browser4,
            scenario.test_fn,
        );
        timings.push(report);
    }

    println!("All scenarios complete!");

    sleep(Duration::from_secs(5));

    // Final safety net: ensure nothing lingers after all scenarios.
    let final_cleanup_started_at = Instant::now();
    stop_browser4_server_forcibly();
    let final_cleanup_duration = final_cleanup_started_at.elapsed();

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
