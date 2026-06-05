//! Managed server process registry for the Browser4 CLI.
//!
//! Tracks Browser4 server processes started by this CLI so they can be shut down
//! later via `close-all` or `kill-all`.

use crate::state::{read_state, resolve_default_state_dir};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::thread::sleep;

const DEFAULT_REGISTRY_NAME: &str = "cli-managed-processes.json";
const BROWSER_PID_MARKER_FILE_NAME: &str = "launcher.pid";

/// Information about a managed Browser4 server process.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ManagedServerProcess {
    pub pid: u32,
    #[serde(rename = "baseUrl")]
    pub base_url: String,
    pub port: u16,
    #[serde(rename = "jarPath")]
    pub jar_path: String,
    #[serde(rename = "startedAt")]
    pub started_at: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct ManagedServerProcessRegistry {
    processes: Vec<ManagedServerProcess>,
}

/// Resolve the path to the managed process registry file.
pub fn managed_server_registry_path(state_dir: Option<&Path>) -> PathBuf {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    dir.join(DEFAULT_REGISTRY_NAME)
}

/// Read all registered managed server processes from the registry.
pub fn read_managed_server_processes(registry_path: Option<&Path>) -> Vec<ManagedServerProcess> {
    let path = registry_path
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| managed_server_registry_path(None));

    match fs::read_to_string(&path) {
        Ok(raw) => serde_json::from_str::<ManagedServerProcessRegistry>(&raw)
            .map(|r| r.processes)
            .unwrap_or_default(),
        Err(_) => vec![],
    }
}

/// Register a new managed server process in the registry.
pub fn register_managed_server_process(
    process_info: ManagedServerProcess,
    registry_path: Option<&Path>,
) {
    let path = registry_path
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| managed_server_registry_path(None));

    let mut existing: Vec<ManagedServerProcess> = read_managed_server_processes(Some(&path))
        .into_iter()
        .filter(|e| e.pid != process_info.pid)
        .collect();
    existing.push(process_info);
    write_managed_server_processes(&existing, &path);
}

/// Remove a managed server process from the registry by PID.
#[allow(dead_code)]
pub fn remove_managed_server_process(pid: u32, registry_path: Option<&Path>) {
    let path = registry_path
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| managed_server_registry_path(None));

    let remaining: Vec<ManagedServerProcess> = read_managed_server_processes(Some(&path))
        .into_iter()
        .filter(|e| e.pid != pid)
        .collect();
    write_managed_server_processes(&remaining, &path);
}

/// Clear all managed server processes from the registry.
#[allow(dead_code)]
pub fn clear_managed_server_processes(registry_path: Option<&Path>) {
    let path = registry_path
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| managed_server_registry_path(None));
    let _ = fs::remove_file(path);
}

fn write_managed_server_processes(processes: &[ManagedServerProcess], registry_path: &Path) {
    if let Some(dir) = registry_path.parent() {
        let _ = fs::create_dir_all(dir);
    }

    if processes.is_empty() {
        let _ = fs::remove_file(registry_path);
        return;
    }

    let payload = ManagedServerProcessRegistry {
        processes: processes.to_vec(),
    };
    let json = serde_json::to_string_pretty(&payload).expect("serialisation should not fail");
    let _ = fs::write(registry_path, json);
}

/// Result of a shutdown operation.
#[derive(Debug, Default)]
pub struct ShutdownResult {
    pub stopped_pids: Vec<u32>,
    pub missing_pids: Vec<u32>,
    pub forced_pids: Vec<u32>,
    pub remaining_pids: Vec<u32>,
    pub fallback_killed_server_pids: Vec<u32>,
}

/// Result of force-killing Browser4 Chrome processes.
#[derive(Debug, Default)]
pub struct BrowserKillResult {
    pub killed_pids: Vec<u32>,
    pub remaining_pids: Vec<u32>,
}

/// Result of force-stopping Browser4 server processes and their related Chrome processes.
#[derive(Debug, Default)]
pub struct ForceStopBrowser4ServerResult {
    pub shutdown: ShutdownResult,
    pub browser_kill: BrowserKillResult,
}

#[derive(Debug, Default)]
struct ServerKillResult {
    killed_pids: Vec<u32>,
    remaining_pids: Vec<u32>,
}

fn stop_browser4_server(force: bool) -> ShutdownResult {
    shutdown_managed_server_processes(force, None, 5_000, 250)
}

/// Shut down all managed server processes, optionally forcing them.
pub fn shutdown_managed_server_processes(
    force: bool,
    registry_path: Option<&Path>,
    timeout_ms: u64,
    poll_interval_ms: u64,
) -> ShutdownResult {
    let path = registry_path
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| managed_server_registry_path(None));

    let tracked = read_managed_server_processes(Some(&path));
    let mut result = ShutdownResult::default();
    let mut remaining: Vec<ManagedServerProcess> = Vec::new();

    for proc in &tracked {
        let pid = proc.pid;
        if !is_process_running(pid) {
            result.missing_pids.push(pid);
            continue;
        }

        if !is_browser4_server_process(pid) {
            // PID may have been reused by a non-Browser4 process; treat as stale.
            result.missing_pids.push(pid);
            continue;
        }

        if force {
            force_stop(pid);
            result.forced_pids.push(pid);
            if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
                result.stopped_pids.push(pid);
            } else {
                remaining.push(proc.clone());
            }
            continue;
        }

        graceful_stop(pid);
        if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
            result.stopped_pids.push(pid);
            continue;
        }

        force_stop(pid);
        result.forced_pids.push(pid);
        if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
            result.stopped_pids.push(pid);
        } else {
            remaining.push(proc.clone());
        }
    }

    write_managed_server_processes(&remaining, &path);
    result.remaining_pids = remaining.iter().map(|p| p.pid).collect();
    result
}

/// Gracefully stop all managed Browser4 server processes.
#[allow(dead_code)]
pub fn stop_browser4_server_gracefully() -> ShutdownResult {
    stop_browser4_server(false)
}

/// Force-stop all managed Browser4 server processes, then kill all related browser processes.
pub fn stop_browser4_server_forcibly() -> ForceStopBrowser4ServerResult {
    let result = stop_browser4_server_forcibly_with_steps(
        || notify_close_all_sessions_before_force_stop(None, None),
        kill_all_browsers,
        || stop_browser4_server_with_fallback_force_kill(),
        || sleep(std::time::Duration::from_secs(5)),
    );

    result
}

fn stop_browser4_server_with_fallback_force_kill() -> ShutdownResult {
    let mut shutdown = stop_browser4_server(true);
    let fallback_result = force_kill_all_browser4_server_processes();
    merge_shutdown_with_fallback_server_kill(&mut shutdown, &fallback_result);
    shutdown.fallback_killed_server_pids = fallback_result.killed_pids.clone();
    shutdown
}

fn merge_shutdown_with_fallback_server_kill(
    shutdown: &mut ShutdownResult,
    fallback: &ServerKillResult,
) {
    shutdown
        .stopped_pids
        .extend(fallback.killed_pids.iter().copied());
    shutdown.forced_pids.extend(
        fallback
            .killed_pids
            .iter()
            .chain(fallback.remaining_pids.iter())
            .copied(),
    );

    shutdown
        .remaining_pids
        .extend(fallback.remaining_pids.iter().copied());
    shutdown
        .remaining_pids
        .retain(|pid| !fallback.killed_pids.contains(pid));

    dedup_sort_u32(&mut shutdown.stopped_pids);
    dedup_sort_u32(&mut shutdown.forced_pids);
    dedup_sort_u32(&mut shutdown.remaining_pids);
}

fn dedup_sort_u32(values: &mut Vec<u32>) {
    values.sort_unstable();
    values.dedup();
}

fn stop_browser4_server_forcibly_with_steps<NotifyCloseAll, StopServer, KillBrowsers, SleepAfter>(
    notify_close_all: NotifyCloseAll,
    kill_browsers: KillBrowsers,
    stop_server: StopServer,
    sleep_after: SleepAfter,
) -> ForceStopBrowser4ServerResult
where
    NotifyCloseAll: FnOnce(),
    StopServer: FnOnce() -> ShutdownResult,
    KillBrowsers: Fn() -> BrowserKillResult,
    SleepAfter: FnOnce(),
{
    // Force-kill path should still attempt browser cleanup even if earlier steps panic.
    eprintln!("  [1/5] Notifying reachable servers to close all sessions ...");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(notify_close_all));

    // Pre-sweep catches already-orphaned browser processes before server shutdown.
    eprintln!("  [2/5] Scanning for orphaned browser processes (pre-sweep) ...");
    let pre_browser_kill = std::panic::catch_unwind(std::panic::AssertUnwindSafe(&kill_browsers))
        .unwrap_or_else(|_| BrowserKillResult::default());
    eprintln!(
        "        Found {} browser process(es), killed {}",
        pre_browser_kill.killed_pids.len() + pre_browser_kill.remaining_pids.len(),
        pre_browser_kill.killed_pids.len()
    );

    eprintln!("  [3/5] Stopping Browser4 server processes ...");
    let shutdown = std::panic::catch_unwind(std::panic::AssertUnwindSafe(stop_server))
        .unwrap_or_else(|_| ShutdownResult::default());
    eprintln!(
        "        Stopped {}, already stopped {}, forced {}, still running {}",
        shutdown.stopped_pids.len(),
        shutdown.missing_pids.len(),
        shutdown.forced_pids.len(),
        shutdown.remaining_pids.len()
    );

    // Post-sweep catches browsers that spawn late during shutdown.
    eprintln!("  [4/5] Scanning for remaining browser processes (post-sweep) ...");
    let post_browser_kill = std::panic::catch_unwind(std::panic::AssertUnwindSafe(kill_browsers))
        .unwrap_or_else(|_| BrowserKillResult::default());
    eprintln!(
        "        Found {} browser process(es), killed {}",
        post_browser_kill.killed_pids.len() + post_browser_kill.remaining_pids.len(),
        post_browser_kill.killed_pids.len()
    );
    let browser_kill = merge_browser_kill_results(pre_browser_kill, post_browser_kill);

    eprintln!("  [5/5] Waiting for processes to exit ...");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(sleep_after));

    ForceStopBrowser4ServerResult {
        shutdown,
        browser_kill,
    }
}

fn merge_browser_kill_results(
    mut first: BrowserKillResult,
    mut second: BrowserKillResult,
) -> BrowserKillResult {
    first.killed_pids.append(&mut second.killed_pids);
    first.remaining_pids.append(&mut second.remaining_pids);
    dedup_sort_u32(&mut first.killed_pids);
    dedup_sort_u32(&mut first.remaining_pids);
    first
}

fn notify_close_all_sessions_before_force_stop(
    _registry_path: Option<&Path>,
    _state_dir: Option<&Path>,
) {
    // do not notify
}

#[allow(dead_code)]
fn close_all_base_urls_for_force_stop(
    registry_path: Option<&Path>,
    state_dir: Option<&Path>,
) -> Vec<String> {
    let mut base_urls: HashSet<String> = read_managed_server_processes(registry_path)
        .into_iter()
        .map(|proc| proc.base_url.trim_end_matches('/').to_string())
        .filter(|url| !url.is_empty())
        .collect();

    let resolved_state_dir = state_dir
        .map(|path| path.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    if resolved_state_dir.join("cli-state.json").exists() {
        let base_url = read_state(Some(&resolved_state_dir), None)
            .base_url
            .trim_end_matches('/')
            .to_string();
        if !base_url.is_empty() {
            base_urls.insert(base_url);
        }
    }

    let mut base_urls: Vec<String> = base_urls.into_iter().collect();
    base_urls.sort();
    base_urls
}

#[allow(dead_code)]
fn call_close_all_sessions(
    client: &reqwest::blocking::Client,
    base_url: &str,
) -> Result<String, String> {
    let response = client
        .post(format!("{}/mcp/call-tool", base_url.trim_end_matches('/')))
        .header("Content-Type", "application/json")
        .json(&json!({ "tool": "close_all_sessions", "arguments": {} }))
        .send()
        .map_err(|error| format!("HTTP request failed: {error}"))?;

    let data: Value = response
        .json()
        .map_err(|error| format!("Failed to parse response JSON: {error}"))?;

    if data
        .get("isError")
        .and_then(|value| value.as_bool())
        .unwrap_or(false)
    {
        let message = data
            .get("content")
            .and_then(|content| content.as_array())
            .and_then(|items| items.first())
            .and_then(|item| item.get("text"))
            .and_then(|text| text.as_str())
            .unwrap_or("Unknown MCP error");
        return Err(message.to_string());
    }

    Ok(data
        .get("content")
        .and_then(|content| content.as_array())
        .and_then(|items| items.first())
        .and_then(|item| item.get("text"))
        .and_then(|text| text.as_str())
        .unwrap_or("")
        .to_string())
}

/// Kill all found Browser4 browser processes (marked with PULSAR_CHROME or marker files).
pub fn kill_all_browsers() -> BrowserKillResult {
    const KILL_TIMEOUT_MS: u64 = 30_000;
    const WAIT_BETWEEN_SWEEPS_MS: u64 = 250;
    const WAIT_AFTER_KILL_MS: u64 = 2_000;

    let start = std::time::Instant::now();
    let timeout = std::time::Duration::from_millis(KILL_TIMEOUT_MS);
    let sweep_delay = std::time::Duration::from_millis(WAIT_BETWEEN_SWEEPS_MS);
    let mut result = BrowserKillResult::default();

    loop {
        let pids = find_unique_pulsar_browser_processes();
        if pids.is_empty() {
            // println!("No more Browser4 Chrome processes found.");
            break;
        }

        result.killed_pids.extend(pids.iter().copied());
        for pid in &pids {
            force_stop_browser_process(*pid);
        }
        for pid in &pids {
            let _ = wait_for_exit(*pid, WAIT_AFTER_KILL_MS, 100);
        }

        if start.elapsed() >= timeout {
            break;
        }

        std::thread::sleep(sweep_delay);
    }

    result.killed_pids.sort_unstable();
    result.killed_pids.dedup();
    result.remaining_pids = find_unique_pulsar_browser_processes();

    result
}

fn find_unique_pulsar_browser_processes() -> Vec<u32> {
    let mut pids = find_pulsar_browser_processes();
    pids.extend(find_browser_processes_from_markers());
    pids.sort_unstable();
    pids.dedup();
    pids
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct BrowserMarkerPid {
    pid: u32,
    marker_dir: PathBuf,
}

fn find_browser_processes_from_markers() -> Vec<u32> {
    collect_browser_marker_pid_entries(&browser_marker_search_roots())
        .into_iter()
        .filter(|entry| marker_pid_matches_browser(entry))
        .map(|entry| entry.pid)
        .collect()
}

fn browser_marker_search_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();

    if let Some(home) = dirs::home_dir() {
        roots.push(home.join("browser4").join("browser").join("chrome"));
    }

    let temp_dir = std::env::temp_dir();
    if let Ok(entries) = fs::read_dir(&temp_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }

            let Some(name) = path.file_name().and_then(|value| value.to_str()) else {
                continue;
            };
            if !name.starts_with("browser4-apps") {
                continue;
            }

            roots.push(path.join("context"));
            roots.push(path.join("browser").join("chrome"));
        }
    }

    roots.retain(|path| path.is_dir());
    roots.sort();
    roots.dedup();
    roots
}

fn collect_browser_marker_pid_entries(roots: &[PathBuf]) -> Vec<BrowserMarkerPid> {
    let mut entries = Vec::new();
    for root in roots {
        collect_browser_marker_pid_entries_under(root, 0, &mut entries);
    }
    entries.sort_by(|left, right| {
        left.pid
            .cmp(&right.pid)
            .then_with(|| left.marker_dir.cmp(&right.marker_dir))
    });
    entries.dedup();
    entries
}

fn collect_browser_marker_pid_entries_under(
    dir: &Path,
    depth: usize,
    entries: &mut Vec<BrowserMarkerPid>,
) {
    const MAX_SEARCH_DEPTH: usize = 6;

    if depth > MAX_SEARCH_DEPTH {
        return;
    }

    let Ok(children) = fs::read_dir(dir) else {
        return;
    };

    let mut subdirs = Vec::new();
    let mut marker_found_in_dir = false;

    for child in children.flatten() {
        let path = child.path();
        if path.is_dir() {
            subdirs.push(path);
            continue;
        }

        if path.file_name().and_then(|value| value.to_str()) == Some(BROWSER_PID_MARKER_FILE_NAME) {
            if let Some(pid) = read_marker_pid(&path) {
                entries.push(BrowserMarkerPid {
                    pid,
                    marker_dir: dir.to_path_buf(),
                });
            }
            marker_found_in_dir = true;
        }
    }

    if marker_found_in_dir {
        return;
    }

    for subdir in subdirs {
        collect_browser_marker_pid_entries_under(&subdir, depth + 1, entries);
    }
}

fn read_marker_pid(path: &Path) -> Option<u32> {
    fs::read_to_string(path).ok()?.trim().parse::<u32>().ok()
}

fn marker_pid_matches_browser(entry: &BrowserMarkerPid) -> bool {
    is_process_running(entry.pid)
        && is_browser_process(entry.pid)
        && process_command_line(entry.pid)
            .map(|command_line| command_line_matches_marker_dir(&command_line, &entry.marker_dir))
            .unwrap_or(false)
}

fn command_line_matches_marker_dir(command_line: &str, marker_dir: &Path) -> bool {
    let marker_dir = normalize_process_text(&marker_dir.to_string_lossy());
    if marker_dir.is_empty() {
        return false;
    }

    normalize_process_text(command_line).contains(&marker_dir)
}

fn normalize_process_text(value: &str) -> String {
    value.replace('\\', "/").to_ascii_lowercase()
}

fn command_line_matches_browser4_server(command_line: &str) -> bool {
    let normalized = normalize_process_text(command_line);
    normalized.contains("browser4.jar")
        || normalized.contains("browser4launcherkt")
        || normalized.contains("browser4bundleapplicationkt")
}

fn find_browser4_server_processes() -> Vec<u32> {
    let mut pids = Vec::new();

    #[cfg(unix)]
    {
        use std::process::Command;
        if let Ok(output) = Command::new("pgrep")
            .args([
                "-f",
                r"browser4\.jar|browser4launcherkt|browser4bundleapplicationkt",
            ])
            .output()
        {
            pids.extend(parse_pid_list(&output.stdout));
        }
    }

    #[cfg(windows)]
    {
        use std::process::Command;
        let ps_command = r#"
            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.Name -match '^(java|javaw)\.exe$' -and
                    -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
                    $_.CommandLine -match '(?i)(Browser4\.jar|\bBrowser4LauncherKt\b|\bBrowser4BundleApplicationKt\b)'
                } |
                Select-Object -ExpandProperty ProcessId
        "#;
        if let Ok(output) = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", ps_command])
            .output()
        {
            pids.extend(parse_pid_list(&output.stdout));
        }
    }

    pids.sort_unstable();
    pids.dedup();
    pids
}

fn force_kill_all_browser4_server_processes() -> ServerKillResult {
    const WAIT_AFTER_KILL_MS: u64 = 2_000;
    const WAIT_POLL_MS: u64 = 100;

    let pids = find_browser4_server_processes();
    if pids.is_empty() {
        return ServerKillResult::default();
    }

    let mut result = ServerKillResult::default();
    for pid in &pids {
        force_stop(*pid);
    }

    for pid in &pids {
        if wait_for_exit(*pid, WAIT_AFTER_KILL_MS, WAIT_POLL_MS) {
            result.killed_pids.push(*pid);
        } else {
            result.remaining_pids.push(*pid);
        }
    }

    dedup_sort_u32(&mut result.killed_pids);
    dedup_sort_u32(&mut result.remaining_pids);
    result
}

fn is_browser4_server_process(pid: u32) -> bool {
    process_name(pid)
        .map(|name| {
            let normalized = normalize_process_text(&name);
            normalized == "java"
                || normalized == "java.exe"
                || normalized == "javaw"
                || normalized == "javaw.exe"
        })
        .unwrap_or(false)
        && process_command_line(pid)
            .map(|command_line| command_line_matches_browser4_server(&command_line))
            .unwrap_or(false)
}

fn find_pulsar_browser_processes() -> Vec<u32> {
    let mut pids = Vec::new();

    #[cfg(unix)]
    {
        use std::process::Command;
        if let Ok(output) = Command::new("pgrep").args(["-f", "PULSAR_CHROME"]).output() {
            pids.extend(parse_pid_list(&output.stdout));
        }
    }

    #[cfg(windows)]
    {
        use std::process::Command;
        let ps_command = r#"
            Get-CimInstance Win32_Process -Filter "Name = 'chrome.exe'" -ErrorAction SilentlyContinue |
                Where-Object {
                    -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
                    $_.CommandLine -match 'PULSAR_CHROME'
                } |
                Select-Object -ExpandProperty ProcessId
        "#;
        if let Ok(output) = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", ps_command])
            .output()
        {
            pids.extend(parse_pid_list(&output.stdout));
        }
    }

    pids
}

fn is_browser_process(pid: u32) -> bool {
    process_name(pid)
        .map(|name| matches_browser_name(&name))
        .unwrap_or(false)
}

fn matches_browser_name(name: &str) -> bool {
    matches!(
        normalize_process_text(name).as_str(),
        "chrome" | "chrome.exe" | "chromium" | "chromium-browser" | "msedge" | "msedge.exe"
    )
}

fn process_name(pid: u32) -> Option<String> {
    #[cfg(unix)]
    {
        use std::process::Command;
        let output = Command::new("ps")
            .args(["-o", "comm=", "-p", &pid.to_string()])
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }
        let name = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if name.is_empty() {
            None
        } else {
            Some(name)
        }
    }

    #[cfg(windows)]
    {
        use std::process::Command;
        let output = Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &format!(
                    "(Get-CimInstance Win32_Process -Filter \"ProcessId = {}\" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)",
                    pid
                ),
            ])
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }
        let name = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if name.is_empty() {
            None
        } else {
            Some(name)
        }
    }
}

fn process_command_line(pid: u32) -> Option<String> {
    #[cfg(unix)]
    {
        use std::process::Command;
        let output = Command::new("ps")
            .args(["-o", "args=", "-p", &pid.to_string()])
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }
        let command_line = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if command_line.is_empty() {
            None
        } else {
            Some(command_line)
        }
    }

    #[cfg(windows)]
    {
        use std::process::Command;
        let output = Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &format!(
                    "(Get-CimInstance Win32_Process -Filter \"ProcessId = {}\" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty CommandLine)",
                    pid
                ),
            ])
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }
        let command_line = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if command_line.is_empty() {
            None
        } else {
            Some(command_line)
        }
    }
}

fn parse_pid_list(stdout: &[u8]) -> Vec<u32> {
    String::from_utf8_lossy(stdout)
        .lines()
        .filter_map(|line| line.trim().parse::<u32>().ok())
        .collect()
}

// ---------------------------------------------------------------------------
// Platform-specific process control
// ---------------------------------------------------------------------------

fn is_process_running(pid: u32) -> bool {
    #[cfg(unix)]
    {
        use std::process::Command;
        // SIGCHECK (signal 0) returns success if process exists.
        // Suppress stderr to avoid "kill: No such process" noise when the
        // process has already exited.
        let status = Command::new("kill")
            .args(["-0", &pid.to_string()])
            .stderr(std::process::Stdio::null())
            .status();
        status.map(|s| s.success()).unwrap_or(false)
    }
    #[cfg(windows)]
    {
        use std::process::Command;
        let output = Command::new("tasklist")
            .args(["/FI", &format!("PID eq {}", pid), "/FO", "CSV", "/NH"])
            .output();
        match output {
            Ok(out) => {
                let stdout = String::from_utf8_lossy(&out.stdout);
                stdout.contains(&pid.to_string())
            }
            Err(_) => false,
        }
    }
}

fn graceful_stop(pid: u32) {
    #[cfg(unix)]
    {
        let _ = std::process::Command::new("kill")
            .args(["-TERM", &pid.to_string()])
            .status();
    }
    #[cfg(windows)]
    {
        let stop_status = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &format!("Stop-Process -Id {} -ErrorAction SilentlyContinue", pid),
            ])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
        if stop_status.is_err() || stop_status.map(|s| !s.success()).unwrap_or(true) {
            let _ = std::process::Command::new("powershell")
                .args([
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    &format!(
                        "Stop-Process -Id {} -Force -ErrorAction SilentlyContinue",
                        pid
                    ),
                ])
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status();
        }
    }
}

fn force_stop(pid: u32) {
    #[cfg(unix)]
    {
        let _ = std::process::Command::new("kill")
            .args(["-KILL", &pid.to_string()])
            .stderr(std::process::Stdio::null())
            .status();
    }
    #[cfg(windows)]
    {
        let _ = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &format!(
                    "Stop-Process -Id {} -Force -ErrorAction SilentlyContinue",
                    pid
                ),
            ])
            .status();
    }
}

fn force_stop_browser_process(pid: u32) {
    #[cfg(unix)]
    {
        force_stop(pid);
    }

    #[cfg(windows)]
    {
        let taskkill_failed = match std::process::Command::new("taskkill")
            .args(["/PID", &pid.to_string(), "/T", "/F"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
        {
            Ok(status) => !status.success(),
            Err(_) => true,
        };

        if taskkill_failed {
            force_stop(pid);
        }
    }
}

fn wait_for_exit(pid: u32, timeout_ms: u64, poll_interval_ms: u64) -> bool {
    let start = std::time::Instant::now();
    let timeout = std::time::Duration::from_millis(timeout_ms);
    let poll = std::time::Duration::from_millis(poll_interval_ms);
    while start.elapsed() < timeout {
        if !is_process_running(pid) {
            return true;
        }
        std::thread::sleep(poll);
    }
    !is_process_running(pid)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn test_temp_dir() -> TempDir {
        let root = std::env::temp_dir()
            .join("browser4")
            .join("browser4-cli")
            .join("managed-processes-tests");
        fs::create_dir_all(&root).unwrap();
        tempfile::Builder::new()
            .prefix("managed-")
            .tempdir_in(&root)
            .unwrap()
    }

    #[test]
    fn test_register_and_remove() {
        let tmp = test_temp_dir();
        let reg_path = tmp.path().join("reg.json");

        let proc = ManagedServerProcess {
            pid: 12345,
            base_url: "http://localhost:8182".to_string(),
            port: 8182,
            jar_path: "/path/to/Browser4.jar".to_string(),
            started_at: "2026-01-01T00:00:00Z".to_string(),
        };

        register_managed_server_process(proc.clone(), Some(&reg_path));
        let procs = read_managed_server_processes(Some(&reg_path));
        assert_eq!(procs.len(), 1);
        assert_eq!(procs[0].pid, 12345);

        remove_managed_server_process(12345, Some(&reg_path));
        let procs = read_managed_server_processes(Some(&reg_path));
        assert!(procs.is_empty());
    }

    #[test]
    fn test_read_missing_registry() {
        let tmp = test_temp_dir();
        let reg_path = tmp.path().join("missing.json");
        let procs = read_managed_server_processes(Some(&reg_path));
        assert!(procs.is_empty());
    }

    #[test]
    fn test_clear_registry() {
        let tmp = test_temp_dir();
        let reg_path = tmp.path().join("reg.json");

        let proc = ManagedServerProcess {
            pid: 99999,
            base_url: "http://localhost:8182".to_string(),
            port: 8182,
            jar_path: "/tmp/Browser4.jar".to_string(),
            started_at: "2026-01-01T00:00:00Z".to_string(),
        };
        register_managed_server_process(proc, Some(&reg_path));
        assert!(reg_path.exists());

        clear_managed_server_processes(Some(&reg_path));
        assert!(!reg_path.exists());
    }

    #[test]
    fn test_parse_pid_list_ignores_noise() {
        let stdout = b"123\nwarning\n\n456 \nnot-a-pid\n789\r\n";
        assert_eq!(parse_pid_list(stdout), vec![123, 456, 789]);
    }

    #[test]
    fn test_collect_browser_marker_pid_entries_discovers_nested_markers() {
        let tmp = test_temp_dir();
        let root = tmp
            .path()
            .join("browser4-user")
            .join("context")
            .join("tmp")
            .join("groups")
            .join("default")
            .join("cx.1");
        fs::create_dir_all(root.join("PULSAR_CHROME")).unwrap();
        fs::write(root.join(BROWSER_PID_MARKER_FILE_NAME), "4321\n").unwrap();
        fs::write(root.join("ignored.txt"), "noise").unwrap();

        let entries =
            collect_browser_marker_pid_entries(&[tmp.path().join("browser4-user").join("context")]);

        assert_eq!(
            entries,
            vec![BrowserMarkerPid {
                pid: 4321,
                marker_dir: root,
            }]
        );
    }

    #[test]
    fn test_collect_browser_marker_pid_entries_ignores_invalid_marker_contents() {
        let tmp = test_temp_dir();
        let root = tmp.path().join("browser").join("chrome").join("default");
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join(BROWSER_PID_MARKER_FILE_NAME), "not-a-pid").unwrap();

        let entries = collect_browser_marker_pid_entries(&[tmp.path().join("browser")]);

        assert!(entries.is_empty());
    }

    #[test]
    fn test_command_line_matches_marker_dir_normalizes_windows_paths() {
        let marker_dir = PathBuf::from(r"C:\Users\tester\.browser4\browser\chrome\default");
        let command_line = r#""C:/Program Files/Google/Chrome/Application/chrome.exe" --user-data-dir=C:/Users/tester/.browser4/browser/chrome/default/chrome --remote-debugging-port=0"#;

        assert!(command_line_matches_marker_dir(command_line, &marker_dir));
    }

    #[test]
    fn test_command_line_matches_browser4_server_for_jar_launcher_and_bundle() {
        let jar = r#""C:/Java/bin/java.exe" -jar D:/browser4/Browser4.jar --server.port=8182"#;
        let launcher = r#""C:/Java/bin/java.exe" -cp @C:/Temp/spring-boot.argfile Browser4LauncherKt --server.port=8182"#;
        let bundle = r#""D:/runtime/bin/java.exe" -cp D:/lib/* ai.platon.pulsar.apps.Browser4BundleApplicationKt --server.port=8182"#;

        assert!(command_line_matches_browser4_server(jar));
        assert!(command_line_matches_browser4_server(launcher));
        assert!(command_line_matches_browser4_server(bundle));
    }

    #[test]
    fn test_command_line_matches_browser4_server_rejects_non_browser4_java() {
        let non_browser4 =
            r#""C:/Java/bin/java.exe" -jar D:/apps/another-service.jar --server.port=8080"#;

        assert!(!command_line_matches_browser4_server(non_browser4));
    }

    #[test]
    fn test_close_all_base_urls_for_force_stop_include_registry_and_state() {
        let tmp = test_temp_dir();
        let state_dir = tmp.path().join("state");
        fs::create_dir_all(&state_dir).unwrap();
        let reg_path = state_dir.join("managed.json");

        register_managed_server_process(
            ManagedServerProcess {
                pid: 12345,
                base_url: "http://localhost:8182/".to_string(),
                port: 8182,
                jar_path: "/path/to/Browser4.jar".to_string(),
                started_at: "2026-01-01T00:00:00Z".to_string(),
            },
            Some(&reg_path),
        );

        fs::write(
            state_dir.join("cli-state.json"),
            r#"{"baseUrl":"http://localhost:8183","sessionId":"session-1"}"#,
        )
        .unwrap();

        assert_eq!(
            close_all_base_urls_for_force_stop(Some(&reg_path), Some(&state_dir)),
            vec![
                "http://localhost:8182".to_string(),
                "http://localhost:8183".to_string(),
            ]
        );
    }

    #[test]
    fn test_stop_browser4_server_forcibly_notifies_before_shutdown_and_browser_kill() {
        let events = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));

        let notify_events = std::sync::Arc::clone(&events);
        let stop_events = std::sync::Arc::clone(&events);
        let kill_events = std::sync::Arc::clone(&events);
        let sleep_events = std::sync::Arc::clone(&events);

        let result = stop_browser4_server_forcibly_with_steps(
            move || notify_events.lock().unwrap().push("notify".to_string()),
            move || {
                kill_events.lock().unwrap().push("browser-kill".to_string());
                BrowserKillResult::default()
            },
            move || {
                stop_events.lock().unwrap().push("shutdown".to_string());
                ShutdownResult::default()
            },
            move || sleep_events.lock().unwrap().push("sleep".to_string()),
        );

        assert!(result.shutdown.stopped_pids.is_empty());
        assert!(result.browser_kill.killed_pids.is_empty());
        assert_eq!(
            events.lock().unwrap().as_slice(),
            [
                "notify",
                "browser-kill",
                "shutdown",
                "browser-kill",
                "sleep",
            ]
        );
    }

    #[test]
    fn test_stop_browser4_server_forcibly_still_kills_browsers_when_shutdown_panics() {
        let events = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));

        let notify_events = std::sync::Arc::clone(&events);
        let stop_events = std::sync::Arc::clone(&events);
        let kill_events = std::sync::Arc::clone(&events);
        let sleep_events = std::sync::Arc::clone(&events);

        let result = stop_browser4_server_forcibly_with_steps(
            move || notify_events.lock().unwrap().push("notify".to_string()),
            move || {
                kill_events.lock().unwrap().push("browser-kill".to_string());
                BrowserKillResult {
                    killed_pids: vec![9101],
                    remaining_pids: vec![],
                }
            },
            move || {
                stop_events
                    .lock()
                    .unwrap()
                    .push("shutdown-panic".to_string());
                panic!("shutdown failed");
            },
            move || sleep_events.lock().unwrap().push("sleep".to_string()),
        );

        assert!(result.shutdown.stopped_pids.is_empty());
        assert_eq!(result.browser_kill.killed_pids, vec![9101]);
        assert_eq!(
            events.lock().unwrap().as_slice(),
            [
                "notify",
                "browser-kill",
                "shutdown-panic",
                "browser-kill",
                "sleep",
            ]
        );
    }

    #[test]
    fn test_merge_browser_kill_results_merges_and_deduplicates() {
        let merged = merge_browser_kill_results(
            BrowserKillResult {
                killed_pids: vec![1001, 1002],
                remaining_pids: vec![2001],
            },
            BrowserKillResult {
                killed_pids: vec![1002, 1003],
                remaining_pids: vec![2001, 2002],
            },
        );

        assert_eq!(merged.killed_pids, vec![1001, 1002, 1003]);
        assert_eq!(merged.remaining_pids, vec![2001, 2002]);
    }

    #[test]
    fn test_merge_shutdown_with_fallback_server_kill_merges_and_deduplicates() {
        let mut shutdown = ShutdownResult {
            stopped_pids: vec![1001],
            missing_pids: vec![],
            forced_pids: vec![1001],
            remaining_pids: vec![2001],
            fallback_killed_server_pids: vec![],
        };
        let fallback = ServerKillResult {
            killed_pids: vec![2001, 3001],
            remaining_pids: vec![4001],
        };

        merge_shutdown_with_fallback_server_kill(&mut shutdown, &fallback);

        assert_eq!(shutdown.stopped_pids, vec![1001, 2001, 3001]);
        assert_eq!(shutdown.forced_pids, vec![1001, 2001, 3001, 4001]);
        assert_eq!(shutdown.remaining_pids, vec![4001]);
    }

    #[test]
    fn test_merge_shutdown_with_fallback_server_kill_removes_killed_from_remaining() {
        let mut shutdown = ShutdownResult {
            stopped_pids: vec![],
            missing_pids: vec![],
            forced_pids: vec![],
            remaining_pids: vec![5001, 5002],
            fallback_killed_server_pids: vec![],
        };
        let fallback = ServerKillResult {
            killed_pids: vec![5002],
            remaining_pids: vec![5003],
        };

        merge_shutdown_with_fallback_server_kill(&mut shutdown, &fallback);

        assert_eq!(shutdown.remaining_pids, vec![5001, 5003]);
    }
}
