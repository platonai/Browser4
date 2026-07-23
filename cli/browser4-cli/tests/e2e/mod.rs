#![allow(dead_code)]

//! End-to-end tests for the `browser4-cli` Rust binary.
//!
//! The scenarios run sequentially in a custom `harness = false` test target so
//! they can reuse the proven ordering without libtest starting multiple
//! Browser4 backends concurrently. A dedicated coverage check cross-references
//! the `e2e_coverage` field on each `CommandDef` against the tested-commands
//! list, and the custom runner prints per-test timings to make slow cases easy
//! to spot.
//!
//! # Running
//!
//! ```bash
//! cargo test --test e2e -- --nocapture
//! cargo test --test e2e -- --nocapture --enable-batch-scenario
//! cargo test --test e2e -- --nocapture --batch-only
//! cargo test --test e2e -- --nocapture --scenario=*open*
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_*
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_swarm_*
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_agent_*
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_agent_task_commands
//! cargo test --test e2e -- --nocapture --scenario-from=test_e2e_mouse_and_dialog
//! cargo test --test e2e -- --nocapture --scenario-limit=5
//! cargo test --test e2e -- --nocapture --scenario-from=test_e2e_navigation_and_storage --scenario-limit=5
//! cargo test --test e2e -- --nocapture --failed
//! cargo test --test e2e -- --nocapture --scenario=test_e2e_eval_command --fail-fast
//! cargo test --test e2e -- --nocapture --force-remote-bundle
//! ```
//!
//! The `--failed` selector reruns scenario names stored by the previous run in
//! `%TEMP%/browser4/browser4-cli/e2e/last-failed-scenarios.json`.
//! By default, the full e2e run skips batch-command scenarios; pass
//! `--enable-batch-scenario` to include them, or `--batch-only` to run only
//! batch-command scenarios.
//!
//! The Browser4 service is resolved in this order:
//! 1. `BROWSER4_E2E_SERVICE_URL` environment variable – connect to an already-running
//!    service (Docker-friendly; no JAR is needed).
//! 2. `BROWSER4_E2E_SERVER_URL` environment variable – alias for the above.
//! 3. Otherwise, each local run lets `browser4-cli` auto-start the backend.
//!    The browser4-bundle runtime bundle is used for local backend startup.
//!
//! When running against an external Docker service, also set:
//! - `BROWSER4_E2E_FIXTURE_HOST` – hostname/IP the Browser4 container uses to
//!   reach the fixture HTTP server on the host (e.g. `host.docker.internal` or
//!   the Docker bridge gateway IP such as `172.17.0.1`). Defaults to `127.0.0.1`.

use base64::Engine as _;
use browser4_cli::commands::{all_commands, E2eCoverage};
use browser4_cli::managed_processes::stop_browser4_server_forcibly;
use chrono::Local;
use std::collections::{BTreeSet, HashMap, HashSet};
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

pub mod scenarios;
pub mod constants;
pub use constants::*;

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Environment helpers
// ---------------------------------------------------------------------------

fn cli_binary() -> PathBuf {
    let path = PathBuf::from(env!("CARGO_BIN_EXE_browser4-cli"));
    if path.exists() {
        return path;
    }
    // The target directory may have been copied from another checkout
    // (e.g. when reusing a build cache across worktrees or directory
    // renames).  Fall back to a path derived from the running test
    // binary's location.
    let exe = std::env::current_exe().expect("failed to determine current executable path");
    // Test binary is at:  target/{profile}/deps/e2e-<hash>
    // Main binary is at:   target/{profile}/browser4-cli
    let target_dir = exe
        .parent() // deps/
        .and_then(|p| p.parent()) // debug/ or release/
        .expect("failed to find target directory from test binary path");
    target_dir.join("browser4-cli")
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

fn force_remote_bundle_for_local_server() -> bool {
    std::env::var(FORCE_REMOTE_BUNDLE_ENV)
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

fn load_html_fixture(file_name: &str) -> String {
    let path = fixture_path(env!("CARGO_MANIFEST_DIR"), file_name);
    if path.exists() {
        return fs::read_to_string(&path).unwrap_or_else(|error| {
            panic!(
                "failed to load HTML fixture {file_name} from {}: {error}",
                path.display()
            )
        });
    }
    // The manifest directory baked in at compile time may point to a
    // different checkout when the target directory was copied as a build
    // cache.  Fall back to the current working directory.
    let cwd = std::env::current_dir().expect("failed to get current directory");
    let fallback = fixture_path(&cwd.to_string_lossy(), file_name);
    fs::read_to_string(&fallback).unwrap_or_else(|error| {
        panic!(
            "failed to load HTML fixture {file_name} from {} (also tried {}): {error}",
            fallback.display(),
            path.display()
        )
    })
}

fn fixture_path(base: &str, file_name: &str) -> PathBuf {
    PathBuf::from(base)
        .join("..")
        .join("..")
        .join("browser4-tests")
        .join("pulsar-tests-common")
        .join("src")
        .join("main")
        .join("resources")
        .join("static")
        .join("b4")
        .join(file_name)
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
            listener
                .set_nonblocking(true)
                .expect("fixture server set_nonblocking failed");
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
                    Err(e) => {
                        eprintln!("[fixture server] accept error (listener continues): {e}");
                        thread::sleep(Duration::from_millis(5));
                    }
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
// Fake runtime bundle builder (for install / upgrade e2e tests)
// ---------------------------------------------------------------------------

/// Build a minimal fake runtime bundle archive that satisfies
/// `install_dir_contains_runtime` checks: a `lib/` dir with a jar file and
/// a `runtime/bin/` dir with a platform-appropriate java executable.
///
/// On Windows the archive is a ZIP file; on all other platforms it is a
/// tar.gz file — matching the real runtime bundle formats.
///
/// Returns the archive bytes and the bundle root directory name inside the
/// archive (e.g. `browser4-bundle-runtime-windows-x64`).
fn build_fake_runtime_bundle(tag: &str) -> (Vec<u8>, String) {
    let platform = if cfg!(windows) {
        "windows-x64"
    } else if cfg!(target_os = "macos") {
        if cfg!(target_arch = "aarch64") {
            "darwin-arm64"
        } else {
            "darwin-x64"
        }
    } else {
        "linux-x64"
    };
    let dir_name = format!("browser4-bundle-runtime-{platform}");
    let java_name = if cfg!(windows) { "java.exe" } else { "java" };

    if cfg!(windows) {
        build_fake_zip_bundle(tag, &dir_name, &java_name)
    } else {
        build_fake_tar_gz_bundle(tag, &dir_name, &java_name)
    }
}

fn build_fake_zip_bundle(tag: &str, dir_name: &str, java_name: &str) -> (Vec<u8>, String) {
    let mut buffer = Vec::new();
    {
        let mut zip_writer = zip::ZipWriter::new(std::io::Cursor::new(&mut buffer));
        let options = zip::write::SimpleFileOptions::default();

        // lib/sample.jar (a minimal non-empty file)
        zip_writer
            .start_file(format!("{dir_name}/lib/browser4-core.jar"), options)
            .unwrap();
        zip_writer.write_all(b"fake-jar-content").unwrap();

        // runtime/bin/java
        zip_writer
            .start_file(format!("{dir_name}/runtime/bin/{java_name}"), options)
            .unwrap();
        zip_writer.write_all(b"fake-java-binary").unwrap();

        // bin/ launcher script (needed for bundle validation)
        zip_writer
            .start_file(format!("{dir_name}/bin/launcher"), options)
            .unwrap();
        zip_writer.write_all(b"#!/bin/sh\necho fake").unwrap();

        // Write the tag into a VERSION file so we can verify correct extraction.
        zip_writer
            .start_file(format!("{dir_name}/VERSION"), options)
            .unwrap();
        zip_writer.write_all(tag.as_bytes()).unwrap();

        zip_writer.finish().unwrap();
    }
    (buffer, dir_name.to_string())
}

fn build_fake_tar_gz_bundle(tag: &str, dir_name: &str, java_name: &str) -> (Vec<u8>, String) {
    let mut buffer = Vec::new();
    {
        let gz_encoder = flate2::write::GzEncoder::new(&mut buffer, flate2::Compression::default());
        let mut tar_builder = tar::Builder::new(gz_encoder);

        fn add_tar_entry(builder: &mut tar::Builder<impl std::io::Write>, path: &str, data: &[u8]) {
            let mut header = tar::Header::new_gnu();
            header.set_path(path).unwrap();
            header.set_size(data.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append_data(&mut header, path, data).unwrap();
        }

        add_tar_entry(
            &mut tar_builder,
            &format!("{dir_name}/lib/browser4-core.jar"),
            b"fake-jar-content",
        );
        add_tar_entry(
            &mut tar_builder,
            &format!("{dir_name}/runtime/bin/{java_name}"),
            b"fake-java-binary",
        );
        add_tar_entry(
            &mut tar_builder,
            &format!("{dir_name}/bin/launcher"),
            b"#!/bin/sh\necho fake",
        );
        add_tar_entry(
            &mut tar_builder,
            &format!("{dir_name}/VERSION"),
            tag.as_bytes(),
        );

        let gz_encoder = tar_builder.into_inner().unwrap();
        gz_encoder.finish().unwrap();
    }
    (buffer, dir_name.to_string())
}

// ---------------------------------------------------------------------------
// Minimal HTTP server that serves fake runtime bundles for install/upgrade e2e
// ---------------------------------------------------------------------------

struct FixtureDownloadServer {
    port: u16,
    shutdown: Arc<AtomicBool>,
    /// Recorded request paths.
    requests: Arc<Mutex<Vec<String>>>,
    /// Release tag served by this fixture (reported in /latest-release.json).
    tag: String,
    /// Artificial latency applied before serving each request (for speed-test
    /// scenarios that need one mirror to appear slower than another).
    latency: Duration,
}

impl FixtureDownloadServer {
    fn start(bundle_bytes: Vec<u8>, tag: &str) -> Self {
        Self::start_with_latency(bundle_bytes, tag, Duration::ZERO)
    }

    fn start_with_latency(bundle_bytes: Vec<u8>, tag: &str, latency: Duration) -> Self {
        let listener =
            TcpListener::bind("127.0.0.1:0").expect("fixture download server bind failed");
        let port = listener.local_addr().unwrap().port();
        let shutdown = Arc::new(AtomicBool::new(false));
        let requests = Arc::new(Mutex::new(Vec::new()));
        let flag = shutdown.clone();
        let reqs = requests.clone();
        let bytes = Arc::new(bundle_bytes);
        let tag_owned = tag.to_string();

        let tag_for_thread = tag_owned.clone();
        thread::spawn(move || {
            listener
                .set_nonblocking(true)
                .expect("fixture download server set_nonblocking failed");
            loop {
                if flag.load(Ordering::Relaxed) {
                    break;
                }
                match listener.accept() {
                    Ok((stream, _)) => {
                        let b = bytes.clone();
                        let r = reqs.clone();
                        let t = tag_for_thread.clone();
                        thread::spawn(move || serve_download_request(stream, b, r, t, latency));
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(e) => {
                        eprintln!("[fixture download server] accept error (continuing): {e}");
                        thread::sleep(Duration::from_millis(5));
                    }
                }
            }
        });

        Self {
            port,
            shutdown,
            requests,
            tag: tag_owned,
            latency,
        }
    }

    fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}/releases", self.port)
    }

    fn snapshot_requests(&self) -> Vec<String> {
        self.requests
            .lock()
            .expect("download requests mutex poisoned")
            .clone()
    }
}

impl Drop for FixtureDownloadServer {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
    }
}

fn serve_download_request(
    mut stream: TcpStream,
    bundle_bytes: Arc<Vec<u8>>,
    requests: Arc<Mutex<Vec<String>>>,
    tag: String,
    latency: Duration,
) {
    if !latency.is_zero() {
        thread::sleep(latency);
    }
    let mut buf = vec![0u8; 8192];
    let n = match stream.read(&mut buf) {
        Ok(n) => n,
        Err(_) => return,
    };

    let request = std::str::from_utf8(&buf[..n]).unwrap_or("");
    let first_line = request.lines().next().unwrap_or("");
    let path = first_line.split_whitespace().nth(1).unwrap_or("/");

    requests
        .lock()
        .expect("download requests mutex poisoned")
        .push(path.to_string());

    // Serve 404 for HEAD requests or non-GET methods (simulating GitHub).
    let method = first_line.split_whitespace().next().unwrap_or("");
    if method != "GET" {
        write_http_response(&mut stream, "405 Method Not Allowed", "text/plain", "");
        return;
    }

    // Serve /latest-release.json metadata endpoint (simulating OSS metadata).
    if path == "/releases/latest-release.json" {
        let body = format!(
            r#"{{"tag":"{}","version":"{}","published_at":"2026-01-01T00:00:00Z","release_url":"https://github.com/platonai/Browser4/releases/tag/{}","assets":[]}}"#,
            tag,
            tag.trim_start_matches('v'),
            tag
        );
        let content_type = "application/json";
        write_http_response(&mut stream, "200 OK", content_type, &body);
        return;
    }

    // GitHub-style paths:
    //   /releases/latest/download/{asset}
    //   /releases/download/{tag}/{asset}
    // Serve the same fake bundle for all paths that match the pattern.
    if path.contains("/download/") {
        let body_len = bundle_bytes.len();
        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body_len
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.write_all(&bundle_bytes);
    } else {
        write_http_response(&mut stream, "404 Not Found", "text/plain", "not found");
    }
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
    swarm_queries: Vec<serde_json::Value>,
    status_queries: Vec<String>,
    result_queries: Vec<String>,
    next_agent_task_id: usize,
    next_swarm_task_id: usize,
    listed_sessions: Vec<MockListedSession>,
    queued_open_session_ids: Vec<String>,
    queued_tool_failures: Vec<MockToolFailure>,
    health_status: Option<(u16, String, String)>, // (status_code, content_type, body)
    close_session_calls: Vec<String>,
    close_all_sessions_calls: Vec<serde_json::Value>,
    crawl_submissions: Vec<serde_json::Value>,
    crawl_cancel_calls: Vec<String>,
    crawl_clear_calls: u32,
    /// Custom command_status responses keyed by task ID. When set, these override
    /// the default response for `command_status`.
    custom_command_statuses: HashMap<String, serde_json::Value>,
    /// Custom command_result responses keyed by task ID. When set, these override
    /// the default response for `command_result`.
    custom_command_results: HashMap<String, String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct MockToolFailure {
    tool: String,
    session_id: Option<String>,
    url: Option<String>,
    message: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct MockListedSession {
    session_id: String,
    status: String,
    url: String,
}

impl MockListedSession {
    fn active(session_id: &str) -> Self {
        Self {
            session_id: session_id.to_string(),
            status: "active".to_string(),
            url: "https://mock.browser4.local/current".to_string(),
        }
    }

    fn stopped(session_id: &str) -> Self {
        Self {
            session_id: session_id.to_string(),
            status: "stopped".to_string(),
            url: "https://mock.browser4.local/current".to_string(),
        }
    }
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
            listener
                .set_nonblocking(true)
                .expect("mock Browser4 server set_nonblocking failed");
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
                    Err(e) => {
                        eprintln!("[mock Browser4 server] accept error (listener continues): {e}");
                        thread::sleep(Duration::from_millis(5));
                    }
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

    fn set_listed_sessions(&self, listed_sessions: Vec<MockListedSession>) {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .listed_sessions = listed_sessions;
    }

    fn queue_open_session_ids(&self, session_ids: Vec<&str>) {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .queued_open_session_ids = session_ids.into_iter().map(str::to_string).collect();
    }

    fn queue_tool_failure(
        &self,
        tool: &str,
        session_id: Option<&str>,
        url: Option<&str>,
        message: &str,
    ) {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .queued_tool_failures
            .push(MockToolFailure {
                tool: tool.to_string(),
                session_id: session_id.map(str::to_string),
                url: url.map(str::to_string),
                message: message.to_string(),
            });
    }

    /// Configure a custom response for `GET /actuator/health`.
    fn set_health_response(&self, status_code: u16, content_type: &str, body: &str) {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .health_status = Some((status_code, content_type.to_string(), body.to_string()));
    }

    /// Remove the health response override so the mock falls back to the
    /// default `{"status":"UP"}` behaviour.
    fn clear_health_response(&self) {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .health_status = None;
    }

    /// Register a custom JSON response for `command_status` for a specific task ID.
    /// When `command_status` is queried for this task, the custom response is returned
    /// instead of the default `{"id": ..., "status": "RUNNING"}`.
    fn set_command_status_response(&self, task_id: &str, response: serde_json::Value) {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .custom_command_statuses
            .insert(task_id.to_string(), response);
    }

    /// Register a custom text response for `command_result` for a specific task ID.
    /// When `command_result` is queried for this task, the custom response is returned
    /// instead of the default `"result for {task_id}"`.
    fn set_command_result_response(&self, task_id: &str, response: &str) {
        self.state
            .lock()
            .expect("mock Browser4 state mutex poisoned")
            .custom_command_results
            .insert(task_id.to_string(), response.to_string());
    }

    /// Shut down the mock server's listener thread without dropping the recorded
    /// state. After calling this, further requests will fail with a connection
    /// error (simulating an unreachable backend).
    fn shutdown(&self) {
        self.shutdown.store(true, Ordering::Relaxed);
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
        ("GET", "/actuator/health") => {
            let guard = state.lock().expect("mock Browser4 state mutex poisoned");
            if let Some((status_code, ref content_type, ref body)) = guard.health_status {
                let status_text = match status_code {
                    200 => "200 OK",
                    503 => "503 Service Unavailable",
                    other => {
                        eprintln!(
                            "[mock Browser4 server] unsupported health status {other}, using 200 OK"
                        );
                        "200 OK"
                    }
                };
                write_http_response(&mut stream, status_text, content_type, body);
            } else {
                write_http_response(
                    &mut stream,
                    "200 OK",
                    "application/json",
                    r#"{"status":"UP"}"#,
                );
            }
        }
        ("GET", "/mcp/tools") => write_http_response(
            &mut stream,
            "200 OK",
            "application/json",
            r#"["open_session","list_sessions","browser_navigate","agent_extract","agent_summarize","crawl_submit"]"#,
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

            // Record session-close operations before the failure check so
            // tests can verify they were attempted even when the mock returns
            // an error response.
            let mut guard = state.lock().expect("mock Browser4 state mutex poisoned");
            match tool.as_str() {
                "close_session" => {
                    if let Some(sid) = arguments.get("sessionId").and_then(|v| v.as_str()) {
                        guard.close_session_calls.push(sid.to_string());
                    }
                }
                "close_all_sessions" => {
                    guard.close_all_sessions_calls.push(arguments.clone());
                }
                _ => {}
            }
            drop(guard);

            if let Some(message) = {
                let mut guard = state.lock().expect("mock Browser4 state mutex poisoned");
                let failure_index = guard.queued_tool_failures.iter().position(|failure| {
                    failure.tool == tool
                        && failure
                            .session_id
                            .as_deref()
                            .map(|expected| {
                                arguments.get("sessionId").and_then(|value| value.as_str())
                                    == Some(expected)
                            })
                            .unwrap_or(true)
                        && failure
                            .url
                            .as_deref()
                            .map(|expected| {
                                arguments.get("url").and_then(|value| value.as_str())
                                    == Some(expected)
                            })
                            .unwrap_or(true)
                });
                failure_index.map(|index| guard.queued_tool_failures.remove(index).message)
            } {
                let response = serde_json::json!({
                    "isError": true,
                    "content": [
                        {
                            "type": "text",
                            "text": message,
                        }
                    ]
                })
                .to_string();
                write_http_response(&mut stream, "200 OK", "application/json", &response);
                return;
            }

            let text = match tool.as_str() {
                "open_session" => {
                    let session_id = {
                        let mut guard = state.lock().expect("mock Browser4 state mutex poisoned");
                        let session_id = if guard.queued_open_session_ids.is_empty() {
                            // SWARM sessions must return the fixed session ID "SWARM".
                            let requested_session_id = arguments
                                .get("capabilities")
                                .and_then(|caps| caps.get("sessionId"))
                                .and_then(|v| v.as_str())
                                .unwrap_or_default();
                            if requested_session_id.eq_ignore_ascii_case("SWARM") {
                                "SWARM".to_string()
                            } else {
                                "swarm-session-1".to_string()
                            }
                        } else {
                            guard.queued_open_session_ids.remove(0)
                        };
                        guard.listed_sessions = vec![MockListedSession::active(&session_id)];
                        session_id
                    };
                    serde_json::json!({ "sessionId": session_id }).to_string()
                }
                "list_sessions" => {
                    let listed_sessions = state
                        .lock()
                        .expect("mock Browser4 state mutex poisoned")
                        .listed_sessions
                        .clone();
                    serde_json::Value::Array(
                        listed_sessions
                            .into_iter()
                            .map(|session| {
                                serde_json::json!({
                                    "sessionId": session.session_id,
                                    "url": session.url,
                                    "status": session.status,
                                })
                            })
                            .collect(),
                    )
                    .to_string()
                }
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
                            guard.next_swarm_task_id += 1;
                            format!("swarm-task-{}", guard.next_swarm_task_id)
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
                    let mut guard = state
                        .lock()
                        .expect("mock Browser4 state mutex poisoned");
                    guard
                        .status_queries
                        .push(task_id.clone());
                    // Check for test-registered custom response first
                    if let Some(custom) = guard.custom_command_statuses.get(&task_id) {
                        custom.to_string()
                    } else if task_id == "agent-task-missing-llm" {
                        serde_json::json!({
                            "id": task_id,
                            "status": "EXPECTATION_FAILED",
                            "statusCode": 417,
                            "processState": "done",
                            "message": "The LLM is not configured. Set an API key environment variable such as DEEPSEEK_API_KEY, OPENROUTER_API_KEY, or OPENAI_API_KEY.",
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
                        .unwrap_or_default()
                        .to_string();
                    let mut guard = state
                        .lock()
                        .expect("mock Browser4 state mutex poisoned");
                    guard
                        .result_queries
                        .push(task_id.clone());
                    // Check for test-registered custom response first
                    if let Some(custom) = guard.custom_command_results.get(&task_id) {
                        custom.clone()
                    } else {
                        format!("result for {task_id}")
                    }
                }
                "command_batch" => mock_command_batch_response(&arguments),
                "close_session" => "Session closed.".to_string(),
                "close_all_sessions" => "All sessions closed.".to_string(),
                "agent_extract" => {
                    r#"{"items":[{"title":"Mock Product","price":"$19.99"}]}"#.to_string()
                }
                "agent_summarize" => "Mock summary for #page-marker".to_string(),
                "crawl_submit" => {
                    state
                        .lock()
                        .expect("mock Browser4 state mutex poisoned")
                        .crawl_submissions
                        .push(arguments.clone());
                    r#""crawl-job-42""#.to_string()
                }
                "html_snapshot_capture" => {
                    r#"{"url":"https://mock.browser4.local","title":"Mock Page","sizeBytes":"12345","capturedAt":"2026-07-20T00:00:00Z","contentType":"text/html","imageCount":3,"linkCount":10}"#.to_string()
                }
                "html_snapshot_inspect" => {
                    r#"{"selector":".product","matchCount":5,"analyzed":5,"autoDiscovered":false,"suggestedSelectors":[{"selector":".product h2","count":5,"score":100,"type":"text"}]}"#.to_string()
                }
                "execute_cdp_command" => {
                    let method = arguments
                        .get("method")
                        .and_then(|v| v.as_str())
                        .unwrap_or_default();
                    let params = arguments.get("params");
                    let params_str = params
                        .map(|p| format!(r#","params":{}"#, serde_json::to_string(p).unwrap_or_default()))
                        .unwrap_or_default();
                    format!(
                        r#"{{"method":"{method}"{params_str},"result":"mock-cdp-result"}}"#,
                    )
                }
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
                    guard.next_swarm_task_id += 1;
                    format!("swarm-task-{}", guard.next_swarm_task_id)
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
        _ if method == "POST"
            && (route == "/api/x/submit"
                || route == "/api/x/s"
                || route == "/api/swarm/submit") =>
        {
            let payload = String::from_utf8_lossy(&body).trim().to_string();
            let task_id = {
                let mut guard = state.lock().expect("mock Browser4 state mutex poisoned");
                guard.plain_commands.push(payload);
                guard.next_swarm_task_id += 1;
                format!("swarm-task-{}", guard.next_swarm_task_id)
            };

            write_http_response(
                &mut stream,
                "200 OK",
                "application/json",
                &serde_json::json!(task_id).to_string(),
            );
        }
        _ if method == "POST" && route == "/api/swarm/query" => {
            let query: serde_json::Value = serde_json::from_slice(&body).unwrap_or_default();
            let task_id = {
                let mut guard = state.lock().expect("mock Browser4 state mutex poisoned");
                guard.swarm_queries.push(query);
                guard.next_swarm_task_id += 1;
                format!("swarm-task-{}", guard.next_swarm_task_id)
            };

            write_http_response(
                &mut stream,
                "200 OK",
                "application/json",
                &serde_json::json!(task_id).to_string(),
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
            && (route.starts_with("/api/x/") || route.starts_with("/api/swarm/"))
            && route.ends_with("/status") =>
        {
            let Some(task_id) = extract_swarm_task_id(route, "/status") else {
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
                "statusCode": 102,
                "pageStatusCode": 102,
                "isDone": false,
                "resultSet": null,
                "status": "Processing",
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
        _ if method == "GET"
            && (route.starts_with("/api/x/") || route.starts_with("/api/swarm/"))
            && route.ends_with("/result") =>
        {
            let Some(task_id) = extract_swarm_task_id(route, "/result") else {
                write_http_response(&mut stream, "404 Not Found", "text/plain", "not found");
                return;
            };

            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .result_queries
                .push(task_id.to_string());

            let response = serde_json::json!({
                "id": task_id,
                "statusCode": 200,
                "pageStatusCode": 200,
                "isDone": true,
                "resultSet": [
                    {
                        "url": format!("https://mock.browser4.local/result/{task_id}"),
                    }
                ],
                "status": "OK",
            })
            .to_string();
            write_http_response(&mut stream, "200 OK", "application/json", &response);
        }
        // ---- crawl REST endpoints ----
        _ if method == "POST" && route.starts_with("/api/crawl/") && route.ends_with("/cancel") => {
            let task_id = route
                .strip_prefix("/api/crawl/")
                .and_then(|rest| rest.strip_suffix("/cancel"))
                .unwrap_or_default()
                .to_string();
            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .crawl_cancel_calls
                .push(task_id.clone());
            write_http_response(
                &mut stream,
                "200 OK",
                "text/plain; charset=utf-8",
                &format!("Task {} cancelled.", task_id),
            );
        }
        _ if method == "POST" && route == "/api/crawl/clear" => {
            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .crawl_clear_calls += 1;
            write_http_response(
                &mut stream,
                "200 OK",
                "text/plain; charset=utf-8",
                "Cleared 2 terminal crawl tasks.",
            );
        }
        _ if method == "GET" && route.starts_with("/api/crawl/") && route.ends_with("/status") => {
            let task_id = route
                .strip_prefix("/api/crawl/")
                .and_then(|rest| rest.strip_suffix("/status"))
                .unwrap_or_default()
                .to_string();
            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .status_queries
                .push(task_id.clone());
            let response = serde_json::json!({
                "id": task_id,
                "statusCode": 102,
                "pageStatusCode": 102,
                "isDone": false,
                "pagesFound": 0,
                "status": "Processing",
                "error": null,
            })
            .to_string();
            write_http_response(&mut stream, "200 OK", "application/json", &response);
        }
        _ if method == "GET" && route.starts_with("/api/crawl/") && route.ends_with("/result") => {
            let task_id = route
                .strip_prefix("/api/crawl/")
                .and_then(|rest| rest.strip_suffix("/result"))
                .unwrap_or_default()
                .to_string();
            state
                .lock()
                .expect("mock Browser4 state mutex poisoned")
                .result_queries
                .push(task_id.clone());
            let response = serde_json::json!({
                "id": task_id,
                "statusCode": 200,
                "pageStatusCode": 200,
                "isDone": true,
                "status": "OK",
                "pagesFound": 1,
                "pages": [
                    {
                        "url": "https://mock.browser4.local/result/page",
                        "title": "Mock Crawled Page",
                        "depth": 0,
                    }
                ],
                "error": null,
            })
            .to_string();
            write_http_response(&mut stream, "200 OK", "application/json", &response);
        }
        _ => write_http_response(
            &mut stream,
            "404 Not Found",
            "text/plain; charset=utf-8",
            "not found",
        ),
    }
}

fn extract_swarm_task_id(route: &str, suffix: &str) -> Option<String> {
    for prefix in ["/api/x/", "/api/swarm/"] {
        if let Some(rest) = route.strip_prefix(prefix) {
            return rest
                .strip_suffix(suffix)
                .map(|value| value.trim_end_matches('/').to_string())
                .filter(|value| !value.is_empty());
        }
    }
    None
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
                    .unwrap_or_else(|| "swarm-session-1".to_string());
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
    let mut empty_read_attempts = 0u32;
    const MAX_EMPTY_READ_ATTEMPTS: u32 = 200; // 2 s with 10 ms sleep per attempt

    loop {
        let mut chunk = [0u8; 4096];
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                empty_read_attempts = 0;
                buffer.extend_from_slice(&chunk[..n]);
            }
            Err(ref e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                if buffer.is_empty() {
                    empty_read_attempts += 1;
                    if empty_read_attempts >= MAX_EMPTY_READ_ATTEMPTS {
                        eprintln!(
                            "[read_http_request] giving up after {MAX_EMPTY_READ_ATTEMPTS} empty reads"
                        );
                        return None;
                    }
                    thread::sleep(Duration::from_millis(10));
                    continue;
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
    if let Err(e) = stream.write_all(response.as_bytes()) {
        eprintln!("[write_http_response] failed to write response (status={status}): {e}");
    }
    // Best-effort flush: ignore errors since the connection is about to close.
    let _ = stream.flush();
}

// ---------------------------------------------------------------------------
// Unit tests for internal HTTP helpers
// ---------------------------------------------------------------------------

/// Validate `read_http_request` and `write_http_response` core behaviours.
///
/// These tests use real TCP sockets to exercise the read/write helpers under
/// conditions that the mock-server scenarios depend on.  They run once at
/// startup (after coverage checks) and panic on failure so regressions are
/// caught before any scenario runs.
fn verify_internal_http_helpers() {
    // ── round-trip: write a request, read it back ──────────────────────
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind for http helper test");
    let port = listener.local_addr().unwrap().port();

    let client_thread = thread::spawn(move || {
        let mut stream =
            TcpStream::connect(format!("127.0.0.1:{port}")).expect("connect for http helper test");
        // Write a minimal POST request with a JSON body.
        let body = r#"{"tool":"open_session","arguments":{"url":"https://example.com"}}"#;
        let request = format!(
            "POST /mcp/call-tool HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
            body.len(),
            body
        );
        stream.write_all(request.as_bytes()).expect("write request");
        // Read back the response to drain it.
        let mut buf = vec![0u8; 4096];
        let _ = stream.read(&mut buf);
    });

    let (mut server_stream, _) = listener.accept().expect("accept for http helper test");
    let parsed = read_http_request(&mut server_stream)
        .expect("read_http_request should parse a well-formed request");
    assert_eq!(parsed.0, "POST", "method mismatch");
    assert_eq!(parsed.1, "/mcp/call-tool", "path mismatch");
    let parsed_body = String::from_utf8(parsed.2).expect("body is valid UTF-8");
    assert!(
        parsed_body.contains("open_session"),
        "body should contain open_session, got: {parsed_body}"
    );

    // Send a response back so the client thread can finish.
    write_http_response(
        &mut server_stream,
        "200 OK",
        "application/json",
        r#"{"ok":true}"#,
    );
    drop(server_stream);
    client_thread.join().expect("client thread should finish");

    // ── empty stream returns None ─────────────────────────────────────
    let listener2 = TcpListener::bind("127.0.0.1:0").expect("bind for empty-stream test");
    let port2 = listener2.local_addr().unwrap().port();

    let client_thread2 = thread::spawn(move || {
        let _stream = TcpStream::connect(format!("127.0.0.1:{port2}"))
            .expect("connect for empty-stream test");
        // Immediately drop the stream — server sees EOF / WouldBlock then EOF.
    });

    let (mut server_stream2, _) = listener2.accept().expect("accept for empty-stream test");
    // Wait a bit for the client to close.
    thread::sleep(Duration::from_millis(50));
    let result = read_http_request(&mut server_stream2);
    assert!(
        result.is_none(),
        "read_http_request should return None for an empty/closed stream, got: {result:?}"
    );
    // Should not hang — we hit the max-empty-read-attempts path.

    drop(server_stream2);
    client_thread2
        .join()
        .expect("client thread 2 should finish");
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
    runtime_dir: PathBuf,
    upload_file_path: PathBuf,
    step_timings: Vec<TimedStep>,
    /// Extra environment variables to set for every CLI child process.
    extra_env: Vec<(String, String)>,
}

impl E2ECtx {
    fn set_env(&mut self, key: &str, value: &str) {
        self.extra_env.retain(|(k, _)| k != key);
        self.extra_env.push((key.to_string(), value.to_string()));
    }
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
        let startup_result = run_cli_process_with_live_output(
            &self.ctx,
            &[
                "open",
                &self.ctx.interactive_url(),
                OPEN_PROFILE_MODE_ARG,
                OPEN_INTERACT_LEVEL_ARG,
            ],
        );
        let startup_log_hint = format_browser4_startup_log_hint(&startup_result.stderr);
        let started_via_maven = startup_result
            .stderr
            .contains("Starting server via Maven spring-boot:run");
        let expect_maven_startup = self.ctx.use_maven_startup;
        assert_eq!(
            startup_result.exit_code, 0,
            "Expected CLI-managed Browser4 startup to succeed.{}\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
            startup_log_hint, startup_result.stdout, startup_result.stderr,
        );
        if !self.local_browser4_started {
            if !was_healthy_before {
                if expect_maven_startup {
                    assert!(
                        started_via_maven,
                        "Expected local e2e startup to use Maven spring-boot:run when {USE_MAVEN_STARTUP_ENV}=true.{}\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
                        startup_log_hint,
                        startup_result.stdout,
                        startup_result.stderr,
                    );
                } else {
                    assert!(
                        !started_via_maven,
                        "Expected local e2e startup to default to jar fallback (set {USE_MAVEN_STARTUP_ENV}=true to opt in to Maven).{}\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
                        startup_log_hint,
                        startup_result.stdout,
                        startup_result.stderr,
                    );
                    // When --force-remote-bundle is active the CLI skips the
                    // local Browser4 root search and downloads a pre-built
                    // runtime bundle instead — root-search diagnostics are
                    // never emitted.
                    if !force_remote_bundle_for_local_server() {
                        assert_root_search_log_contains_invocation_dir(
                            &startup_result.stderr,
                            &self.ctx.invocation_dir,
                        );
                    }
                }
                assert!(
                    startup_result.stderr.contains("Browser4 startup log:"),
                    "Expected startup diagnostics to include the Browser4 startup log path.{}\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
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
                    "Expected startup diagnostics to include the Browser4 startup log path when Browser4 restarts.{}\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
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
        // stale BrowserProtocol browser contexts, leading to intermittent
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
    let invocation_dir_suffix = "cli/browser4-cli";

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
        .env("BROWSER4_RUNTIME_DIR", &ctx.runtime_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    // Always anchor Browser4.jar root search to the CLI launch directory,
    // not the isolated temporary workspace used for test artifacts.
    command.env(ROOT_SEARCH_START_DIR_ENV, &ctx.invocation_dir);

    // Apply scenario-specific extra env vars (e.g. BROWSER4_RELEASES_BASE_URL).
    for (key, value) in &ctx.extra_env {
        command.env(key, value);
    }

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
        "Command {:?} failed (exit={}):\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
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
        "Command {:?} failed (exit={}):\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
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
        "Expected command {:?} to fail, but it exited with 0.\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
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
        || combined.contains("http request timed out")
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

/// Strip the auto-appended `### Page` snapshot block from CLI stdout and
/// filter out CLI-formatted success confirmation lines (e.g. "✓ Pressed ...").
fn strip_snapshot_output(stdout: &str) -> String {
    let marker = "\n### Page";
    let without = match stdout.find(marker) {
        Some(idx) => &stdout[..idx],
        None => stdout,
    };
    without
        .lines()
        .map(str::trim)
        .filter(|l| {
            !l.is_empty()
                && *l != "ensuring server..."
                && !l.starts_with("✓ ")
        })
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
///
/// The output is a JSON array of `{index, guid, title, url}` objects.  We
/// parse it as JSON first so we are robust against formatting variations
/// (pretty-print, line-wrapping, etc.).  If that fails we fall back to the
/// legacy regex-based extraction for backward compatibility.
fn extract_tab_index(output: &str, url: &str) -> usize {
    // Primary path: parse as JSON array of {index, url} objects.
    if let Ok(tabs) = serde_json::from_str::<Vec<serde_json::Value>>(output) {
        for tab in &tabs {
            if let Some(tab_url) = tab.get("url").and_then(|v| v.as_str()) {
                if tab_url == url {
                    if let Some(idx) = tab
                        .get("index")
                        .and_then(|v| v.as_str())
                        .and_then(|s| s.parse::<usize>().ok())
                    {
                        return idx;
                    }
                    panic!(
                        "Found URL '{}' in tab-list but index field is missing or not parseable:\n{}",
                        url, output
                    );
                }
            }
        }
        panic!("Could not find tab index for '{}' in:\n{}", url, output);
    }

    // Fallback: legacy regex extraction for non-JSON or unusual formats.
    static RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let re = RE.get_or_init(|| {
        regex::Regex::new(r#"url\s*[:=]\s*"?([^",}\s]+)"?"#).expect("tab url regex compile")
    });

    let urls: Vec<String> = re
        .captures_iter(output)
        .map(|cap| cap[1].to_string())
        .collect();

    if !urls.is_empty() {
        return urls
            .iter()
            .position(|candidate| candidate == url)
            .unwrap_or_else(|| panic!("Could not find tab index for '{}' in:\n{}", url, output));
    }

    // Second fallback: parse human-readable table format (Index | Title | URL).
    // Lines look like: "  0      My Title  http://example.com/path"
    static TABLE_RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let table_re = TABLE_RE.get_or_init(|| {
        regex::Regex::new(r#"^\s*(\d+)\s+.*\s+(https?://\S+)\s*$"#)
            .expect("tab table regex compile")
    });

    for line in output.lines() {
        if let Some(caps) = table_re.captures(line) {
            let line_url = caps[2].to_string();
            if line_url == url {
                return caps[1]
                    .parse::<usize>()
                    .unwrap_or_else(|_| panic!("Could not parse tab index from line: {}", line));
            }
        }
    }

    panic!("Could not find tab index for '{}' in:\n{}", url, output)
}

// ---------------------------------------------------------------------------
// Swarm / agent helpers
// ---------------------------------------------------------------------------

fn extract_swarm_submissions(output: &str) -> Vec<(String, String)> {
    output
        .lines()
        .filter_map(|line| {
            let line = line.trim();
            let rest = line.strip_prefix("Task Submitted: ")?;
            let (url, task_id) = rest.split_once(" -> Task ID: ")?;
            let url = url.trim();
            let task_id = task_id.trim();
            if url.is_empty() || task_id.is_empty() {
                return None;
            }
            Some((url.to_string(), task_id.to_string()))
        })
        .collect()
}

fn parse_json_output(stdout: &str, command_name: &str) -> serde_json::Value {
    let payload = strip_snapshot_output(stdout);
    serde_json::from_str(&payload).unwrap_or_else(|error| {
        panic!("Expected JSON payload from {command_name}, got:\n{payload}\nparse error: {error}")
    })
}

fn swarm_done_flag(payload: &serde_json::Value) -> Option<bool> {
    // Primary: check isDone/done (present in swarm status output).
    if let Some(v) = payload
        .get("isDone")
        .and_then(|value| value.as_bool())
        .or_else(|| payload.get("done").and_then(|value| value.as_bool()))
    {
        return Some(v);
    }
    // Fallback: swarm result output omits isDone. Detect completion by the
    // presence of a non-null resultSet with entries, or a non-null error.
    if let Some(result_set) = payload.get("resultSet") {
        if result_set.is_array() && !result_set.as_array().map(|a| a.is_empty()).unwrap_or(true) {
            return Some(true);
        }
    }
    if payload
        .get("error")
        .and_then(|v| v.as_str())
        .map(|e| !e.is_empty())
        .unwrap_or(false)
    {
        return Some(true);
    }
    // pageContentBytes being non-null also indicates a completed result.
    if payload
        .get("pageContentBytes")
        .and_then(|v| v.as_str())
        .is_some()
    {
        return Some(true);
    }
    // Terminal status codes (404, 500, etc.) indicate the task won't complete
    // successfully — treat as done so tests can handle the error gracefully.
    if payload["statusCode"]
        .as_i64()
        .map(|s| !(200..400).contains(&s))
        .unwrap_or(false)
    {
        return Some(true);
    }
    None
}

fn wait_for_swarm_result(ctx: &mut E2ECtx, task_id: &str, timeout_ms: u64) -> serde_json::Value {
    wait_for_swarm_result_with_error(ctx, task_id, timeout_ms)
        .unwrap_or_else(|last_payload| {
            panic!(
                "Timed out after {timeout_ms}ms waiting for swarm result '{task_id}' to complete. Last payload:\n{last_payload}"
            )
        })
}

/// Like [`wait_for_swarm_result`] but returns `Err(last_payload)` instead of
/// panicking on timeout. Also considers a task done when it reports an error
/// status (e.g. 404 Not Found) even if `done` is `false`, so tests can handle
/// server-side unavailability gracefully.
fn wait_for_swarm_result_with_error(
    ctx: &mut E2ECtx,
    task_id: &str,
    timeout_ms: u64,
) -> Result<serde_json::Value, String> {
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_payload = String::new();

    while Instant::now() < deadline {
        let result = run_checked_cli_process(ctx, &["swarm", "result", task_id]);
        let payload = strip_snapshot_output(&result.stdout);
        last_payload = payload.clone();
        let parsed = parse_json_output(&result.stdout, "swarm result");
        if parsed["id"].as_str() == Some(task_id) && swarm_done_flag(&parsed) == Some(true) {
            ctx.record_step(
                format!(
                    "wait for swarm result {task_id} done (timeout={}ms)",
                    timeout_ms
                ),
                started_at.elapsed(),
            );
            return Ok(parsed);
        }
        // Also consider the task finished when the server reports a terminal
        // error status (e.g. 4xx / 5xx), even if `done` is still false.
        // statusCode may be stripped from result output, so also check error field.
        let has_terminal_error = parsed["statusCode"]
            .as_i64()
            .map(|s| !(200..400).contains(&s))
            .unwrap_or(false)
            || parsed
                .get("error")
                .and_then(|v| v.as_str())
                .map(|e| !e.is_empty())
                .unwrap_or(false);
        if has_terminal_error {
            ctx.record_step(
                format!(
                    "wait for swarm result {task_id} terminal error (timeout={}ms)",
                    timeout_ms
                ),
                started_at.elapsed(),
            );
            return Ok(parsed);
        }
        thread::sleep(Duration::from_millis(500));
    }

    Err(last_payload)
}

/// Like [`wait_for_swarm_result`] but polls `crawl result <task-id>`.
fn wait_for_crawl_result(
    ctx: &mut E2ECtx,
    task_id: &str,
    timeout_ms: u64,
) -> Result<serde_json::Value, String> {
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_payload = String::new();

    while Instant::now() < deadline {
        let result = run_checked_cli_process(ctx, &["crawl", "result", task_id]);
        let payload = strip_snapshot_output(&result.stdout);
        last_payload = payload.clone();
        let parsed = parse_json_output(&result.stdout, "crawl result");
        // Crawl is done when status is "OK" / "SC_OK" or has terminal error
        let status = parsed["status"].as_str().unwrap_or("");
        let is_done = matches!(status, "OK" | "SC_OK");
        let has_terminal = matches!(
            status,
            "SC_REQUEST_TIMEOUT" | "SC_INTERNAL_SERVER_ERROR"
        );
        let has_error_status = parsed["statusCode"]
            .as_i64()
            .map(|s| !(200..400).contains(&s))
            .unwrap_or(false);
        if parsed["id"].as_str() == Some(task_id) && (is_done || has_terminal || has_error_status)
        {
            ctx.record_step(
                format!(
                    "wait for crawl result {task_id} done (timeout={}ms)",
                    timeout_ms
                ),
                started_at.elapsed(),
            );
            return Ok(parsed);
        }
        thread::sleep(Duration::from_millis(500));
    }

    Err(last_payload)
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

// ---------------------------------------------------------------------------
// Direct DOM / JS-state read helpers
// ---------------------------------------------------------------------------
// These bypass the #state-log <pre> element (which is a JS-maintained copy)
// and instead read form-control values from the DOM or JS-state fields from
// window.__browser4State directly.  This is more reliable when CDP event
// delivery is racy — the browser's native form-control state stays current
// even when JS event handlers are delayed or dropped.

/// Read an element's `.value` property directly from the DOM.
fn read_dom_value(ctx: &mut E2ECtx, selector: &str) -> String {
    eval_text(
        ctx,
        &format!(
            "(document.querySelector('{}') || {{}}).value || ''",
            selector
        ),
    )
}

/// Read `window.__browser4State.keyEvents` directly via JSON.stringify.
fn read_key_events(ctx: &mut E2ECtx) -> Vec<String> {
    let text = eval_text(
        ctx,
        "JSON.stringify((window.__browser4State || {}).keyEvents || [])",
    );
    serde_json::from_str::<Vec<String>>(text.trim()).unwrap_or_default()
}

/// Read `window.__browser4State.lastWheel` directly via JSON.stringify.
fn read_last_wheel(ctx: &mut E2ECtx) -> Option<(i64, i64)> {
    let text = eval_text(
        ctx,
        "JSON.stringify((window.__browser4State || {}).lastWheel)",
    );
    let arr: Vec<i64> = serde_json::from_str(text.trim()).unwrap_or_default();
    if arr.len() == 2 {
        Some((arr[0], arr[1]))
    } else {
        None
    }
}

/// Read `window.scrollX` — the native horizontal scroll offset.
fn read_scroll_x(ctx: &mut E2ECtx) -> i64 {
    let text = eval_text(ctx, "window.scrollX.toString()");
    text.trim().parse().unwrap_or(0)
}

/// Read `window.scrollY` — the native vertical scroll offset.
fn read_scroll_y(ctx: &mut E2ECtx) -> i64 {
    let text = eval_text(ctx, "window.scrollY.toString()");
    text.trim().parse().unwrap_or(0)
}

/// Poll until `window.scrollY` reaches at least `min_expected`.  Panics on timeout.
fn wait_for_scroll_y_or_abort(
    ctx: &mut E2ECtx,
    min_expected: i64,
    timeout_ms: u64,
    failure_message: &str,
) {
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_value = 0i64;
    while Instant::now() < deadline {
        last_value = read_scroll_y(ctx);
        if last_value >= min_expected {
            ctx.record_step(
                format!(
                    "wait for scrollY >= {} (timeout={}ms)",
                    min_expected, timeout_ms
                ),
                started_at.elapsed(),
            );
            return;
        }
        thread::sleep(Duration::from_millis(300));
    }
    panic!(
        "{failure_message}. Timed out after {timeout_ms}ms.\n\
         Expected scrollY >= {min_expected}, got {last_value}"
    );
}

/// Poll until a DOM element's `.value` matches `expected`.  Panics on timeout.
fn wait_for_dom_value_or_abort(
    ctx: &mut E2ECtx,
    selector: &str,
    expected: &str,
    timeout_ms: u64,
    failure_message: &str,
) {
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_value = String::new();
    while Instant::now() < deadline {
        last_value = read_dom_value(ctx, selector);
        if last_value == expected {
            ctx.record_step(
                format!(
                    "wait for dom value {} == {} (timeout={}ms)",
                    truncate_timing_label(selector, 32),
                    truncate_timing_label(expected, 32),
                    timeout_ms
                ),
                started_at.elapsed(),
            );
            return;
        }
        thread::sleep(Duration::from_millis(300));
    }
    panic!(
        "{failure_message}. Timed out after {timeout_ms}ms.\nExpected '{expected}', got '{last_value}'"
    );
}

/// Poll until `window.__browser4State.lastWheel` satisfies `predicate`.
/// Panics on timeout.
fn wait_for_last_wheel_or_abort<F>(
    ctx: &mut E2ECtx,
    predicate: F,
    timeout_ms: u64,
    failure_message: &str,
) where
    F: Fn(i64, i64) -> bool,
{
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_value = None;
    while Instant::now() < deadline {
        last_value = read_last_wheel(ctx);
        if let Some((x, y)) = last_value {
            if predicate(x, y) {
                ctx.record_step(
                    format!("wait for lastWheel (timeout={}ms)", timeout_ms),
                    started_at.elapsed(),
                );
                return;
            }
        }
        thread::sleep(Duration::from_millis(300));
    }
    panic!(
        "{failure_message}. Timed out after {timeout_ms}ms.\nLast lastWheel: {last_value:?}"
    );
}

/// Poll until `window.__browser4State.keyEvents` has at least
/// `before_count + 2` entries and the tail includes the down/up pair for
/// `key`.  Panics on timeout.
fn wait_for_press_key_events_or_abort(
    ctx: &mut E2ECtx,
    key: &str,
    before_count: usize,
    timeout_ms: u64,
    failure_message: &str,
) {
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_events = Vec::new();
    while Instant::now() < deadline {
        last_events = read_key_events(ctx);
        if last_events.len() >= before_count + 2 {
            let down = format!("down:{key}");
            let up = format!("up:{key}");
            let new_events: Vec<&str> = last_events
                .iter()
                .skip(before_count)
                .map(String::as_str)
                .collect();
            if new_events.iter().any(|e| *e == down)
                && new_events.iter().any(|e| *e == up)
            {
                ctx.record_step(
                    format!(
                        "wait for press key events for '{}' (timeout={}ms)",
                        key, timeout_ms
                    ),
                    started_at.elapsed(),
                );
                return;
            }
        }
        thread::sleep(Duration::from_millis(300));
    }
    panic!(
        "{failure_message}. Timed out after {timeout_ms}ms.\n\
         Last keyEvents ({} total, {before_count} before): {last_events:?}",
        last_events.len(),
    );
}

/// Poll until `window.__browser4State.keyEvents` grows beyond
/// `before_count` and the last entry equals `expected_event`.
/// Panics on timeout.
fn wait_for_key_event_or_abort(
    ctx: &mut E2ECtx,
    expected_event: &str,
    before_count: usize,
    timeout_ms: u64,
    failure_message: &str,
) {
    let started_at = Instant::now();
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_events = Vec::new();
    while Instant::now() < deadline {
        last_events = read_key_events(ctx);
        if last_events.len() > before_count
            && last_events.last().map(String::as_str) == Some(expected_event)
        {
            ctx.record_step(
                format!(
                    "wait for key event '{}' (timeout={}ms)",
                    expected_event, timeout_ms
                ),
                started_at.elapsed(),
            );
            return;
        }
        thread::sleep(Duration::from_millis(300));
    }
    panic!(
        "{failure_message}. Timed out after {timeout_ms}ms.\n\
         Last keyEvents ({} total, {before_count} before): {last_events:?}",
        last_events.len(),
    );
}

/// Non-fatal variant of `wait_for_key_event_or_abort` — prints a warning
/// on timeout instead of panicking.  Key events via CDP Input.dispatchKeyEvent
/// may not reliably trigger JS DOM listeners in headless Chrome.
fn assume_wait_for_key_event(
    ctx: &mut E2ECtx,
    expected_event: &str,
    before_count: usize,
    timeout_ms: u64,
    failure_message: &str,
) {
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut last_events = Vec::new();
    while Instant::now() < deadline {
        last_events = read_key_events(ctx);
        if last_events.len() > before_count
            && last_events.last().map(String::as_str) == Some(expected_event)
        {
            return;
        }
        thread::sleep(Duration::from_millis(300));
    }
    eprintln!(
        "[assumption] {failure_message}. Timed out after {timeout_ms}ms.\n\
         Last keyEvents ({} total, {before_count} before): {last_events:?}",
        last_events.len(),
    );
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
    // Clean the runtime dir too so that tests that set up an installed
    // runtime (e.g. test_status_installed_runtime) don't leak state into
    // subsequent scenarios that expect a clean slate.
    let _ = fs::remove_dir_all(&ctx.runtime_dir);
    fs::create_dir_all(&ctx.runtime_dir).ok();
    // Clear scenario-specific env vars that persist across tests so each
    // test starts with a predictable environment.  Tests that need these
    // vars must set them explicitly after calling reset_cli_artifacts.
    ctx.set_env("BROWSER4_RELEASES_BASE_URL", "");
    ctx.set_env("BROWSER4_MIRRORS_CONFIG", "");
    ctx.set_env("BROWSER4_CLI_DISABLE_MIRROR_SPEED_TEST", "");
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

// ---------------------------------------------------------------------------
// Pre-build the Browser4 runtime bundle (Maven JAR + jlink assembly)
// ---------------------------------------------------------------------------

/// Walk up from the current directory looking for the Browser4 repository
/// root (a directory that contains both `ROOT.md` and `pom.xml`).
fn find_browser4_root_from_cwd() -> Option<PathBuf> {
    let mut current = std::env::current_dir().ok()?;
    loop {
        if current.join("ROOT.md").is_file() && current.join("pom.xml").is_file() {
            return Some(current);
        }
        if !current.pop() {
            return None;
        }
    }
}

/// Resolve the Maven launcher (`mvnw` / `mvnw.cmd` / `mvn`) relative to the
/// Browser4 repository root. Prefers the checked-in wrapper when available.
fn resolve_maven_program_for_root(root: &Path) -> PathBuf {
    let wrapper_name = if cfg!(windows) { "mvnw.cmd" } else { "mvnw" };
    let wrapper = root.join(wrapper_name);
    if wrapper.exists() {
        return wrapper;
    }
    if cfg!(windows) {
        PathBuf::from("mvn.cmd")
    } else {
        PathBuf::from("mvn")
    }
}

/// Ensure the Browser4 runtime bundle is pre-built before the first scenario
/// runs.  Without this step the CLI daemon builds everything inside the first
/// `browser4-cli` child process, which can take minutes and exceeds the
/// per-command timeout (default 120 s).
///
/// The function checks two artifacts independently, skipping each when its
/// output already exists:
/// 1. `Browser4Bundle.jar` (Maven `package`).
/// 2. The full runtime bundle (PowerShell `build-runtime-bundle.ps1`).
fn ensure_browser4_runtime_bundle_prebuilt() {
    // When the user opted into a remote bundle or an external service, no
    // local build is needed — the CLI daemon will skip it as well.
    if force_remote_bundle_for_local_server() {
        eprintln!("[e2e pre-build] --force-remote-bundle is active; skipping local build.");
        return;
    }
    if external_service_url().is_some() {
        eprintln!("[e2e pre-build] External Browser4 service configured; skipping local build.");
        return;
    }

    let root = match find_browser4_root_from_cwd() {
        Some(r) => r,
        None => {
            eprintln!(
                "[e2e pre-build] Browser4 repository root not found; \
                 skipping pre-build (the CLI daemon will handle it)."
            );
            return;
        }
    };

    let bundle_dir = root.join("browser4-apps").join("browser4-bundle");
    if !bundle_dir.is_dir() {
        eprintln!(
            "[e2e pre-build] browser4-bundle module not found at {}; skipping pre-build.",
            bundle_dir.display()
        );
        return;
    }

    // ---- Step 1: Maven package (Browser4Bundle.jar) -----------------------

    let jar_path = bundle_dir.join("target").join("Browser4Bundle.jar");
    let jar_valid = jar_path.is_file()
        && jar_path
            .metadata()
            .map(|m| m.len() > 4_096)
            .unwrap_or(false);

    if jar_valid {
        eprintln!(
            "[e2e pre-build] Browser4Bundle.jar found at {}; skipping Maven.",
            jar_path.display()
        );
    } else {
        eprintln!(
            "[e2e pre-build] Browser4Bundle.jar not found. Running Maven package \
             (this may take a while on the first run)..."
        );
        let mvn = resolve_maven_program_for_root(&root);
        let started = Instant::now();
        let status = Command::new(&mvn)
            .args([
                "install",
                "-Pall-main-modules,asset-bundle",
                "-DskipTests",
                "-q",
            ])
            .current_dir(&root)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .status();

        match status {
            Ok(s) if s.success() => {
                eprintln!(
                    "[e2e pre-build] Maven package completed in {:.1}s.",
                    started.elapsed().as_secs_f64()
                );
            }
            Ok(s) => {
                eprintln!(
                    "[e2e pre-build] Maven package exited with {}; \
                     falling back to daemon-side build.",
                    s.code()
                        .map_or_else(|| "signal".to_string(), |c| c.to_string())
                );
                return;
            }
            Err(error) => {
                eprintln!(
                    "[e2e pre-build] Failed to run Maven: {error}; \
                     falling back to daemon-side build."
                );
                return;
            }
        }
    }

    // ---- Step 2: Platform runtime bundle (jlink + assembly) ----------------

    // Detect the platform directory name that the build script produces.
    // This mirrors `detect_current_runtime_bundle_platform` in daemon.rs.
    let platform_dir_name = match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "x86_64") => "browser4-bundle-runtime-windows-x64",
        ("linux", "x86_64") => "browser4-bundle-runtime-linux-x64",
        ("macos", "x86_64") => "browser4-bundle-runtime-darwin-x64",
        ("macos", "aarch64") => "browser4-bundle-runtime-darwin-arm64",
        _ => {
            eprintln!("[e2e pre-build] Unsupported platform; skipping runtime bundle assembly.");
            return;
        }
    };

    let work_dir = bundle_dir
        .join("target")
        .join("runtime-bundle")
        .join("_work")
        .join(platform_dir_name)
        .join(platform_dir_name);

    let lib_dir = work_dir.join("lib");
    let java_exe = if cfg!(windows) { "java.exe" } else { "java" };
    let java_path = work_dir.join("runtime").join("bin").join(java_exe);

    let has_bundle = lib_dir.is_dir()
        && fs::read_dir(&lib_dir)
            .map(|mut entries| {
                entries.any(|e| {
                    e.ok()
                        .and_then(|entry| entry.path().extension().map(|ext| ext == "jar"))
                        .unwrap_or(false)
                })
            })
            .unwrap_or(false)
        && java_path.is_file();

    if has_bundle {
        eprintln!(
            "[e2e pre-build] Runtime bundle already assembled at {}.",
            work_dir.display()
        );
        return;
    }

    let build_script = bundle_dir.join("build-runtime-bundle.ps1");
    if !build_script.is_file() {
        eprintln!(
            "[e2e pre-build] Build script not found at {}; skipping runtime bundle assembly.",
            build_script.display()
        );
        return;
    }

    eprintln!(
        "[e2e pre-build] Assembling runtime bundle (jdeps + jlink; \
         may take ~30–60 s)..."
    );
    let started = Instant::now();

    // Use the same PowerShell invocation as the CLI daemon:
    // powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "..."
    let shell = if cfg!(windows) {
        "powershell.exe"
    } else {
        "pwsh"
    };

    let script_path_escaped = build_script.to_string_lossy().replace('\'', "''");

    let ps_command = format!(
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; \
         [Console]::ErrorEncoding = [System.Text.Encoding]::UTF8; \
         & '{}' -SkipMavenInstall",
        script_path_escaped
    );

    let status = Command::new(shell)
        .args([
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &ps_command,
        ])
        .current_dir(&bundle_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .status();

    match status {
        Ok(s) if s.success() => {
            eprintln!(
                "[e2e pre-build] Runtime bundle assembled in {:.1}s.",
                started.elapsed().as_secs_f64()
            );
        }
        Ok(s) => {
            eprintln!(
                "[e2e pre-build] Build script exited with {}; \
                 falling back to daemon-side build.",
                s.code()
                    .map_or_else(|| "signal".to_string(), |c| c.to_string())
            );
        }
        Err(error) => {
            eprintln!(
                "[e2e pre-build] Failed to run build script: {error}; \
                 falling back to daemon-side build."
            );
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
    let runtime_dir = temp_dir.path().join("runtime-data");
    fs::create_dir_all(&workspace_dir).unwrap();
    fs::create_dir_all(&state_dir).unwrap();
    fs::create_dir_all(&runtime_dir).unwrap();

    let invocation_dir = std::env::current_dir().expect("failed to read e2e invocation directory");

    let upload_file_path = temp_dir.path().join("upload.txt");
    fs::write(&upload_file_path, b"browser4-cli e2e upload payload")
        .expect("write upload file failed");

    // When --force-remote-bundle is active, forward the corresponding env
    // var to every CLI child process so it skips the local Maven/jlink build.
    let mut extra_env = Vec::new();
    if force_remote_bundle_for_local_server() {
        extra_env.push((FORCE_REMOTE_BUNDLE_CLI_ENV.to_string(), "1".to_string()));
    }
    // Tag the backend JVM so process-management tooling can distinguish
    // test-server instances from production ones.
    extra_env.push((
        "BROWSER4_SERVER_OPTS".to_string(),
        "-Dapp.name=browser4-test".to_string(),
    ));

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
            runtime_dir,
            upload_file_path,
            step_timings: Vec::new(),
            extra_env,
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
    let result = run_command(
        ctx,
        &[
            "open",
            &ctx.interactive_url(),
            OPEN_PROFILE_MODE_ARG,
            OPEN_INTERACT_LEVEL_ARG,
        ],
    );
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
        resize_result.stdout.contains("✓ Resized to"),
        "Expected '✓ Resized to' in resize output:\n{}",
        resize_result.stdout
    );

    let vw: u64 = eval_text(ctx, "window.innerWidth.toString()")
        .parse()
        .unwrap_or(0);
    let vh: u64 = eval_text(ctx, "window.innerHeight.toString()")
        .parse()
        .unwrap_or(0);
    assert_eq!(vw, 1280, "Expected viewport width 1280, got {vw}");
    assert_eq!(vh, 900, "Expected viewport height 900, got {vh}");
}

fn start_mock_swarm_session(ctx: &mut E2ECtx) -> MockBrowser4Server {
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    let swarm_create_result = run_command(
        ctx,
        &[
            "swarm",
            "create",
            "--profile-mode=TEMPORARY",
            "--max-open-tabs=12",
            "--max-browser-contexts=3",
            "--display-mode=HEADLESS",
        ],
    );
    assert!(
        swarm_create_result
            .stdout
            .contains("Swarm session created: SWARM"),
        "Expected swarm session creation output in:\n{}",
        swarm_create_result.stdout
    );
    assert_eq!(read_persisted_session_id(&ctx.state_dir), "SWARM");

    mock_server
}

fn assert_swarm_session_call(mock_server: &MockBrowser4Server) {
    let tool_calls = mock_server.snapshot().tool_calls;
    let open_session_call = tool_calls
        .iter()
        .find(|call| call.tool == "open_session")
        .expect("expected open_session call");
    assert_eq!(
        open_session_call.arguments["capabilities"]["profileMode"],
        "TEMPORARY"
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
        "HEADLESS"
    );
}

fn start_mock_crawl_session(ctx: &mut E2ECtx) -> MockBrowser4Server {
    let started_at = Instant::now();
    let mock_server = MockBrowser4Server::start();
    ctx.record_step("mock Browser4 server start (crawl)", started_at.elapsed());
    ctx.browser4_base_url = mock_server.base_url();

    // Write a minimal state file so the CLI can read and write session state.
    let state = serde_json::json!({
        "sessionId": "crawl-session-1",
        "baseUrl": mock_server.base_url(),
    });
    fs::write(state_file_path(&ctx.state_dir, None), state.to_string())
        .expect("write state fixture for crawl");

    mock_server
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
            "browser4-cli close-all should not emit JVM diagnostic errors.\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
            result.stdout,
            result.stderr
        );
        let health_started_at = Instant::now();
        wait_for_health(&ctx.browser4_base_url, 15_000).map_err(|error| {
            format!(
                "browser4-cli close-all should keep the Browser4 backend alive for subsequent commands:\n{}\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
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
        "browser4-cli close-all failed (exit={}):\nstdout:--->\n{}\n<---\nstderr:--->\n{}\n<---",
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
//
// Exclusion status is now encoded directly in each CommandDef via the
// `e2e_coverage: E2eCoverage::Excluded` field in `commands.rs`.  The compiler
// guarantees every command declares its status, so there is no separate
// excluded list to maintain here.

/// The set of commands that the e2e scenario functions exercise via
/// [`run_command`] / [`run_command_expecting_failure`].  This must be kept in
/// sync with what the test functions actually call.
///
/// Commands marked `E2eCoverage::Tested` in `commands.rs` must appear here;
/// commands marked `E2eCoverage::Excluded` must not (except `batch`, which
/// is conditionally promoted when `--enable-batch-scenario` is active).
fn tested_commands(include_batch_command: bool) -> HashSet<&'static str> {
    let mut commands: HashSet<&'static str> = [
        // test_session_lifecycle
        "open",
        "list",
        "close",
        // test_close_*, test_close_all_*
        "close-all",
        // test_kill_all_*
        "kill-all",
        // test_stop_*
        "stop",
        // test_status_*
        "status",
        // test_install_*, test_upgrade_*
        "install",
        "upgrade",
        // test_navigation_and_storage
        "goto",
        "go-back",
        "go-forward",
        "reload",
        "delete-data",
        // test_storage_state_commands
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
        // test_interaction_commands
        "resize",
        "type",
        "fill",
        "press",
        "keydown",
        "keyup",
        // test_pointer_commands
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
        "snapshot-grep",
        "screenshot",
        "pdf",
        // test_swarm_session_and_agent_tools
        "extract",
        "summarize",
        // test_agent_task_commands
        "agent-run",
        "agent-status",
        "agent-result",
        // test_agent_list_*, test_agent_full_lifecycle_with_mock
        "agent-list",
        // test_swarm_submission_commands
        "swarm-create",
        "swarm-submit",
        "swarm-status",
        "swarm-result",
        // test_mouse_and_dialog
        "mousemove",
        "mousedown",
        "mouseup",
        "dialog-accept",
        "dialog-dismiss",
        // test_mousewheel
        "mousewheel",
        // test_tab_commands
        "tab-list",
        "tab-new",
        "tab-select",
        "tab-close",
        // eval is exercised directly by dedicated scenarios and shared helpers
        "eval",
        // test_cdp_command
        "cdp",
        // test_htmlsnapshot_*
        "htmlsnapshot",
        "htmlsnapshot-capture",
        "htmlsnapshot-get",
        "htmlsnapshot-get-all",
        "htmlsnapshot-query",
        "htmlsnapshot-export",
        "htmlsnapshot-summary",
        "htmlsnapshot-grep",
        "htmlsnapshot-inspect",
        // crawl commands
        "crawl",
        "crawl-cancel",
        "crawl-clear",
        "crawl-list",
        "crawl-result",
        "crawl-status",
        // webdb commands
        "webdb-export",
        "webdb-normalize",
    ]
    .into();

    if include_batch_command {
        commands.insert("batch");
    }

    commands
}

// ---------------------------------------------------------------------------
// Entry point — custom sequential harness
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Coverage assertion — runs without a server
// ---------------------------------------------------------------------------

/// Verify that every command defined in `all_commands()` has a consistent
/// e2e coverage status.
///
/// Commands carry their exclusion status in the `e2e_coverage` field of
/// [`CommandDef`].  This function cross-references those fields with
/// [`tested_commands`] to catch mismatches:
///
/// - A command marked `E2eCoverage::Tested` must have a corresponding entry
///   in `tested_commands()` (otherwise the scenario is missing).
/// - A command marked `E2eCoverage::Excluded` must *not* appear in
///   `tested_commands()` (otherwise the field is stale).
/// - Every name in `tested_commands()` must resolve to a command whose field
///   is `Tested` (or `batch`, which is promoted at runtime).
///
/// This check does **not** require a running server.
fn verify_e2e_command_coverage(include_batch_command: bool) {
    let commands = all_commands();
    let tested = tested_commands(include_batch_command);

    // 1. Every Tested command must appear in tested_commands().
    let mut untested: Vec<&str> = commands
        .iter()
        .filter(|c| {
            let effective = if c.name == "batch" && include_batch_command {
                E2eCoverage::Tested
            } else {
                c.e2e_coverage
            };
            effective == E2eCoverage::Tested && !tested.contains(c.name)
        })
        .map(|c| c.name)
        .collect();
    untested.sort();
    assert!(
        untested.is_empty(),
        "Commands marked E2eCoverage::Tested but missing from tested_commands(): \
         {untested:?}. Add a scenario and include the command in tested_commands()."
    );

    // 2. Every Excluded command (except batch) must NOT appear in tested_commands().
    let mut overincluded: Vec<&str> = commands
        .iter()
        .filter(|c| {
            c.e2e_coverage == E2eCoverage::Excluded
                && c.name != "batch"
                && tested.contains(c.name)
        })
        .map(|c| c.name)
        .collect();
    overincluded.sort();
    assert!(
        overincluded.is_empty(),
        "Commands marked E2eCoverage::Excluded but present in tested_commands(): \
         {overincluded:?}. Either write an e2e test and change the status to \
         Tested, or remove it from tested_commands()."
    );

    // 3. No stale entries: every name in tested_commands() must exist.
    let all_names: HashSet<&str> = commands.iter().map(|c| c.name).collect();
    let mut stale: Vec<&str> = tested
        .iter()
        .copied()
        .filter(|name| !all_names.contains(name))
        .collect();
    stale.sort();
    assert!(
        stale.is_empty(),
        "Stale command names in tested_commands() that no longer exist in \
         commands.rs: {stale:?}"
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

fn run_named_test<F>(name: &str, test_fn: F) -> TimingReport
where
    F: FnOnce(),
{
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
    list_groups: bool,
    batch_only: bool,
    enable_batch_scenario: bool,
    enable_install_scenario: bool,
    force_remote_bundle: bool,
    /// When non-empty, only scenarios matching at least one of these group
    /// names are selected.  An empty Vec means no group filter is applied.
    groups: Vec<String>,
    /// Maximum scenario level to run.  Defaults to `Basic` so the suite
    /// finishes faster.  Pass `--level=EXTENDED` (or `--level=all`) to
    /// include longer-running / edge-case tests.
    max_level: scenarios::ScenarioLevel,
    /// Suppress per-test timing output; only show pass/fail summary.
    quiet: bool,
    /// Stream CLI child-process output to the terminal for debugging.
    verbose: bool,
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

/// Parse a value from `--flag=value` or `--flag value` syntax.
/// Returns `Some(parsed_value)` if the current arg matches the flag,
/// or `None` if it doesn't.
fn parse_value_flag<T>(
    arg: &str,
    flag: &str,
    args: &mut impl Iterator<Item = String>,
    parse: impl FnOnce(String) -> T,
) -> Option<T> {
    if let Some(value) = arg.strip_prefix(&format!("--{flag}=")) {
        return Some(parse(value.to_string()));
    }
    if arg == format!("--{flag}") {
        let value = args.next().unwrap_or_else(|| {
            panic!("Missing value for --{flag}. Use --{flag}=<value> or --{flag} <value>")
        });
        return Some(parse(value));
    }
    None
}

/// Check if `arg` matches a boolean flag `--name` or its short alias `-X`.
fn match_bool_flag(arg: &str, long: &str, short: &str) -> bool {
    arg == format!("--{long}") || arg == short
}

/// Check if `arg` starts a value flag (either `--name=...` or `--name` or `-X`).
/// Returns the value if the next-arg form (`--name value` or `-X value`) is used,
/// otherwise returns the =value or short-alias next-arg.
fn match_value_flag_start(arg: &str, long: &str, short: &str) -> Option<String> {
    if let Some(value) = arg.strip_prefix(&format!("--{long}=")) {
        return Some(value.to_string());
    }
    if arg == format!("--{long}") || arg == short {
        return Some(String::new()); // caller reads next arg
    }
    None
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
    let mut list_groups = false;
    let mut batch_only = false;
    let mut enable_batch_scenario = false;
    let mut enable_install_scenario = false;
    let mut force_remote_bundle = false;
    let mut quiet = false;
    let mut verbose = false;
    let mut groups: Vec<String> = Vec::new();
    let mut max_level = scenarios::ScenarioLevel::Basic;

    while let Some(arg) = args.next() {
        // --help / -h
        if match_bool_flag(&arg, "help", "-h") {
            print!("{HELP_TEXT}");
            std::process::exit(0);
        }

        // --list / -l
        if match_bool_flag(&arg, "list", "-l") {
            list_only = true;
            continue;
        }

        // --list-groups / -G
        if match_bool_flag(&arg, "list-groups", "-G") {
            list_groups = true;
            continue;
        }

        // --fail-fast / -F
        if match_bool_flag(&arg, "fail-fast", "-F") {
            fail_fast = true;
            continue;
        }

        // --failed
        if arg == "--failed" {
            rerun_failed = true;
            continue;
        }

        // --batch-only
        if arg == "--batch-only" {
            batch_only = true;
            continue;
        }

        // --enable-batch-scenario / -b
        if match_bool_flag(&arg, "enable-batch-scenario", "-b") {
            enable_batch_scenario = true;
            continue;
        }

        // --enable-install-scenario / -i
        if match_bool_flag(&arg, "enable-install-scenario", "-i") {
            enable_install_scenario = true;
            continue;
        }

        // --force-remote-bundle / -R
        if match_bool_flag(&arg, "force-remote-bundle", "-R") {
            force_remote_bundle = true;
            continue;
        }

        // --quiet / -q
        if match_bool_flag(&arg, "quiet", "-q") {
            quiet = true;
            continue;
        }

        // --verbose / -v
        if match_bool_flag(&arg, "verbose", "-v") {
            verbose = true;
            continue;
        }

        // --level / -L
        if let Some(value) = match_value_flag_start(&arg, "level", "-L") {
            let val = if value.is_empty() { args.next().unwrap_or_else(|| {
                panic!("Missing value for --level. Use --level=<BASIC|EXTENDED|ALL> or --level <BASIC|EXTENDED|all>")
            })} else { value };
            max_level = scenarios::ScenarioLevel::from_arg(&val).unwrap_or_else(|error| {
                panic!("{error}");
            });
            continue;
        }

        // --scenario / -s
        if let Some(value) = match_value_flag_start(&arg, "scenario", "-s") {
            let val = if value.is_empty() { args.next().unwrap_or_else(|| {
                panic!("Missing value for --scenario. Use --scenario=<name|pattern> or --scenario <name|pattern>")
            })} else { value };
            scenario = Some(val);
            continue;
        }

        // --scenario-from / -f
        if let Some(value) = match_value_flag_start(&arg, "scenario-from", "-f") {
            let val = if value.is_empty() { args.next().unwrap_or_else(|| {
                panic!("Missing value for --scenario-from. Use --scenario-from=<name> or --scenario-from <name>")
            })} else { value };
            scenario_from = Some(val);
            continue;
        }

        // --scenario-limit / -n
        if let Some(value) = match_value_flag_start(&arg, "scenario-limit", "-n") {
            let val = if value.is_empty() { args.next().unwrap_or_else(|| {
                panic!("Missing value for --scenario-limit. Use --scenario-limit=<count> or --scenario-limit <count>")
            })} else { value };
            scenario_limit = Some(parse_scenario_limit(&val));
            continue;
        }

        // --group / -g
        if let Some(value) = match_value_flag_start(&arg, "group", "-g") {
            let val = if value.is_empty() { args.next().unwrap_or_else(|| {
                panic!("Missing value for --group. Use --group=<name> or --group <name>")
            })} else { value };
            groups.push(val);
            continue;
        }

        // --scenario-range (deprecated)
        if arg == "--scenario-range" || arg.starts_with("--scenario-range=") {
            eprintln!(
                "--scenario-range has been removed. Use --scenario-limit=<count> together with \
                 existing selectors (for example --scenario-from=... --scenario-limit=5)."
            );
            std::process::exit(1);
        }

        // Warn about unrecognized --flags
        if arg.starts_with("--") {
            eprintln!("[e2e] warning: unrecognized flag '{}', ignoring", arg);
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

    if !quiet {
        println!("[e2e] max scenario level: {}", max_level);
    }

    RunOptions {
        scenario_filter,
        scenario_limit,
        has_positional_filter,
        fail_fast,
        list_only,
        list_groups,
        batch_only,
        enable_batch_scenario,
        enable_install_scenario,
        force_remote_bundle,
        groups,
        max_level,
        quiet,
        verbose,
    }
}

fn select_batch_scenarios(
    selected_scenarios: Vec<scenarios::ScenarioDef>,
) -> Vec<scenarios::ScenarioDef> {
    selected_scenarios
        .into_iter()
        .filter(|scenario| scenario.is_batch_command_scenario())
        .collect()
}

fn exclude_batch_scenarios(
    selected_scenarios: Vec<scenarios::ScenarioDef>,
) -> Vec<scenarios::ScenarioDef> {
    selected_scenarios
        .into_iter()
        .filter(|scenario| !scenario.is_batch_command_scenario())
        .collect()
}

fn exclude_install_scenarios(
    selected_scenarios: Vec<scenarios::ScenarioDef>,
) -> Vec<scenarios::ScenarioDef> {
    selected_scenarios
        .into_iter()
        .filter(|scenario| !scenario.is_install_scenario())
        .collect()
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

    if run_options.list_groups {
        let mut group_set: std::collections::BTreeMap<&str, usize> =
            std::collections::BTreeMap::new();
        for scenario in all_scenarios {
            let key = scenario.group.unwrap_or("<none>");
            *group_set.entry(key).or_insert(0) += 1;
        }
        println!("Available groups (scenario count):");
        for (group, count) in &group_set {
            println!("  {}: {}", group, count);
        }
        println!("Use --group=<name> to filter by group (repeatable).");
        return;
    }

    let mut selected_scenarios: Vec<scenarios::ScenarioDef> = match run_options.scenario_filter {
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
            selected
        }
        Some(ScenarioFilter::From(filter)) => {
            let start_index = resolve_scenario_index(&filter).unwrap_or_else(|| {
                panic!("Unknown scenario '{filter}'. Available scenarios: {available_names}");
            });
            all_scenarios[start_index..].to_vec()
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
            selected
        }
        None => all_scenarios.to_vec(),
    };

    if run_options.batch_only {
        selected_scenarios = select_batch_scenarios(selected_scenarios);
        assert!(
            !selected_scenarios.is_empty(),
            "No batch scenarios are registered. Available scenarios: {available_names}"
        );
        println!(
            "selected {} batch scenario(s) via --batch-only: {}",
            selected_scenarios.len(),
            selected_scenarios
                .iter()
                .map(|scenario| scenario.name)
                .collect::<Vec<_>>()
                .join(", ")
        );
    } else if !has_explicit_scenario_filter
        && run_options.groups.is_empty()
        && !run_options.enable_batch_scenario
    {
        let batch_scenarios = selected_scenarios
            .iter()
            .copied()
            .filter(|scenario| scenario.is_batch_command_scenario())
            .collect::<Vec<_>>();
        selected_scenarios = exclude_batch_scenarios(selected_scenarios);

        if !batch_scenarios.is_empty() {
            println!(
                "default e2e run skips {} batch scenario(s); pass --enable-batch-scenario or --batch-only to include them",
                batch_scenarios.len()
            );
        }
    }

    // Install / upgrade scenarios are disabled by default (they download and
    // extract archives).  Use --enable-install-scenario to include them.
    if !has_explicit_scenario_filter
        && run_options.groups.is_empty()
        && !run_options.enable_install_scenario
    {
        let install_scenarios = selected_scenarios
            .iter()
            .copied()
            .filter(|scenario| scenario.is_install_scenario())
            .collect::<Vec<_>>();
        selected_scenarios = exclude_install_scenarios(selected_scenarios);

        if !install_scenarios.is_empty() {
            println!(
                "default e2e run skips {} install/upgrade scenario(s); pass --enable-install-scenario to include them",
                install_scenarios.len()
            );
        }
    }

    // --group filtering: when one or more groups are specified, keep only
    // scenarios that belong to at least one of the requested groups.
    if !run_options.groups.is_empty() {
        let group_set: std::collections::HashSet<&str> =
            run_options.groups.iter().map(String::as_str).collect();
        let before = selected_scenarios.len();
        selected_scenarios
            .retain(|scenario| scenario.group.map_or(false, |g| group_set.contains(g)));
        println!(
            "selected {} scenario(s) via --group={}: {} (filtered out {})",
            selected_scenarios.len(),
            run_options.groups.join(","),
            selected_scenarios
                .iter()
                .map(|s| s.name)
                .collect::<Vec<_>>()
                .join(", "),
            before.saturating_sub(selected_scenarios.len()),
        );
        assert!(
            !selected_scenarios.is_empty(),
            "No scenarios match the requested group(s) '{}'. Use --list-groups to see available groups.",
            run_options.groups.join(",")
        );
    }

    // --level filtering: by default only scenarios at or below Basic level
    // are run.  Pass --level=EXTENDED to include edge-case / longer-running
    // tests.
    {
        let before = selected_scenarios.len();
        let max_level = run_options.max_level;
        selected_scenarios.retain(|scenario| scenario.at_or_below_level(max_level));
        let filtered_out = before.saturating_sub(selected_scenarios.len());
        if filtered_out > 0 {
            println!(
                "level filter (--level={}): excluded {} scenario(s) above {} level",
                max_level, filtered_out, max_level
            );
        }
    }

    let run_coverage =
        !has_explicit_scenario_filter && run_options.groups.is_empty() && !run_options.batch_only;

    let selected_scenarios =
        apply_scenario_limit_filter(selected_scenarios, run_options.scenario_limit);

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
        println!(
            "\n{} tests, 0 benchmarks",
            planned_runs.len() + usize::from(run_coverage)
        );
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

    // Validate internal HTTP helpers before any scenario touches the mock
    // server.  Panics early so regressions are obvious.
    verify_internal_http_helpers();

    // When --force-remote-bundle is passed, set the env var before
    // create_e2e_test_resources() reads it.
    if run_options.force_remote_bundle {
        std::env::set_var(FORCE_REMOTE_BUNDLE_ENV, "1");
    }

    // Pre-build the Browser4 runtime bundle if it is missing so the first
    // CLI command doesn't time out while the daemon runs Maven + jlink.
    ensure_browser4_runtime_bundle_prebuilt();

    let mut resources = create_e2e_test_resources();

    // Sweep orphaned Browser4 processes from previous runs before starting.
    // This is a safety net: even if the final cleanup of a prior run was
    // skipped (e.g. Ctrl+C), the next run starts from a clean slate.
    let pre_sweep_started_at = Instant::now();
    let pre_sweep_result = stop_browser4_server_forcibly();
    if pre_sweep_result.shutdown.stopped_pids.is_empty()
        && pre_sweep_result.browser_kill.killed_pids.is_empty()
    {
        // nothing to clean up — don't add a timing entry
    } else {
        eprintln!(
            "[pre-sweep] cleaned up {} server(s) and {} browser(s) from previous runs ({:.2}s)",
            pre_sweep_result.shutdown.stopped_pids.len(),
            pre_sweep_result.browser_kill.killed_pids.len(),
            pre_sweep_started_at.elapsed().as_secs_f64()
        );
    }

    let run_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut timings: Vec<TimingReport> = Vec::with_capacity(total_tests);
        let mut scenario_failures: Vec<(String, FailureDetail)> = Vec::new();
        let mut failed_scenario_names: HashSet<String> = HashSet::new();

        if run_coverage {
            let report = run_named_test(COVERAGE_TEST_NAME, || {
                verify_e2e_command_coverage(run_options.enable_batch_scenario)
            });
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

            if run_options.verbose {
                if let Some(group) = planned_run.scenario.group {
                    println!("  [verbose] running scenario: {} (group: {})", planned_run.scenario.name, group);
                } else {
                    println!("  [verbose] running scenario: {}", planned_run.scenario.name);
                }
            }
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
                    total_tests, filtered_out
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
            if !run_options.quiet {
                println!("per-test timing:");
                for report in timings {
                    println!("  {}: {}", report.name, format_duration(report.total));
                    print_timing_steps(&report.steps);
                }
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
            if !run_options.quiet {
                println!("final cleanup:");
                print_timing_steps(&final_cleanup_steps);
            }
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
