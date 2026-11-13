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
    /// Runtime version tag the server was launched with (e.g. "local" for a
    /// source-built bundle, "v4.13.5" for an installed release).  Absent for
    /// entries written by older CLI versions.
    #[serde(rename = "version", skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    /// Absolute path of the development checkout this backend was started
    /// from (forward slashes).  Absent for production runs and for entries
    /// written by older CLI versions.  Lets a workspace recognise its own
    /// backend on a port another workspace may have taken over since.
    #[serde(rename = "workspaceRoot", skip_serializing_if = "Option::is_none")]
    pub workspace_root: Option<String>,
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

/// Version tag recorded for the most recently registered managed server on
/// [port].  Returns `None` when the port has no registry entry or the entry
/// predates the version field (older CLI).
pub fn recorded_server_version(port: u16, registry_path: Option<&Path>) -> Option<String> {
    read_managed_server_processes(registry_path)
        .into_iter()
        .rev()
        .find(|p| p.port == port)
        .and_then(|p| p.version)
}

/// True when a backend registered for [port] is still running.
///
/// The registry is per-state-dir, and in development mode that state dir is
/// per checkout — so a live entry for a port means *this* checkout's backend
/// holds it, which is what makes a recorded development port safe to reuse.
pub fn managed_port_has_live_server(port: u16) -> bool {
    read_managed_server_processes(None)
        .into_iter()
        .rev()
        .find(|p| p.port == port)
        .is_some_and(|p| is_process_running(p.pid))
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
    /// True when the stop path tried a graceful shutdown before forcing.
    ///
    /// `forced_pids` is populated on both paths — a `force` stop forces every
    /// pid immediately — so the list alone cannot tell "forced after the grace
    /// window expired" from "forced on purpose".  `stop` is the force-by-design
    /// path (see [`stop_workspace_servers_forcibly`]) and only the plugin
    /// warm-restart path asks for a graceful attempt first; without this flag
    /// `stop` reported a graceful timeout that never happened.
    pub graceful_attempted: bool,
}

impl ShutdownResult {
    /// True when at least one process was force-killed *after* a real grace
    /// window expired (not because the caller asked for a force stop).
    pub fn forced_after_graceful_timeout(&self) -> bool {
        self.graceful_attempted && !self.forced_pids.is_empty()
    }
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
    shutdown_managed_server_processes_matching(
        force,
        registry_path,
        timeout_ms,
        poll_interval_ms,
        &|_| true,
    )
}

/// Shut down only the managed server processes bound to [port].
///
/// Processes registered for other ports are left untouched and stay in the
/// registry.  Used by the plugin warm-restart path so restarting the server
/// on one port never disturbs servers started for other sessions.
pub fn shutdown_managed_server_processes_on_port(
    force: bool,
    registry_path: Option<&Path>,
    port: u16,
    timeout_ms: u64,
    poll_interval_ms: u64,
) -> ShutdownResult {
    shutdown_managed_server_processes_matching(
        force,
        registry_path,
        timeout_ms,
        poll_interval_ms,
        &|p| p.port == port,
    )
}

fn shutdown_managed_server_processes_matching(
    force: bool,
    registry_path: Option<&Path>,
    timeout_ms: u64,
    poll_interval_ms: u64,
    should_stop: &dyn Fn(&ManagedServerProcess) -> bool,
) -> ShutdownResult {
    let path = registry_path
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| managed_server_registry_path(None));

    let tracked = read_managed_server_processes(Some(&path));
    let mut result = ShutdownResult::default();
    let mut registry_after: Vec<ManagedServerProcess> = Vec::new();

    for proc in tracked {
        if !should_stop(&proc) {
            registry_after.push(proc);
            continue;
        }

        let pid = proc.pid;
        if !is_process_running(pid) {
            result.missing_pids.push(pid);
            continue;
        }

        if !is_registered_browser4_server_process(pid, proc.port) {
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
                registry_after.push(proc);
                result.remaining_pids.push(pid);
            }
            continue;
        }

        graceful_stop(pid);
        result.graceful_attempted = true;
        if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
            result.stopped_pids.push(pid);
            continue;
        }

        force_stop(pid);
        result.forced_pids.push(pid);
        if wait_for_exit(pid, timeout_ms, poll_interval_ms) {
            result.stopped_pids.push(pid);
        } else {
            registry_after.push(proc);
            result.remaining_pids.push(pid);
        }
    }

    write_managed_server_processes(&registry_after, &path);
    result
}

/// Gracefully stop all managed Browser4 server processes.
#[allow(dead_code)]
pub fn stop_browser4_server_gracefully() -> ShutdownResult {
    stop_browser4_server(false)
}

/// Force-stop all managed Browser4 server processes, then kill all related browser processes.
pub fn stop_browser4_server_forcibly() -> ForceStopBrowser4ServerResult {
    let registry_path = managed_server_registry_path(None);
    let state_dir = resolve_default_state_dir();
    let mut result = stop_browser4_server_forcibly_with_steps(
        || notify_close_all_sessions_before_force_stop(Some(&registry_path), Some(&state_dir)),
        kill_all_browsers,
        || stop_browser4_server_with_fallback_force_kill(),
        || sleep(std::time::Duration::from_secs(2)),
    );

    // Final verification: catch processes that respawned or were slow to
    // materialise during the grace period.  Any stragglers are killed and
    // folded into the result.
    let final_extra = find_unique_pulsar_browser_processes();
    if final_extra.is_empty() {
        // The post-sweep (a moment earlier) already proved there is nothing
        // left and the merged result carries that verdict, so the extra full
        // process scan this used to run bought nothing but latency.
        result.browser_kill.remaining_pids.clear();
    } else {
        bulk_force_stop_browser_processes(&final_extra);
        std::thread::sleep(std::time::Duration::from_millis(1000));
        result.browser_kill.killed_pids.extend(final_extra.iter().copied());
        result.browser_kill.killed_pids.sort_unstable();
        result.browser_kill.killed_pids.dedup();
        result.browser_kill.remaining_pids = find_unique_pulsar_browser_processes();
    }

    result
}

fn stop_browser4_server_with_fallback_force_kill() -> ShutdownResult {
    // Snapshot the sweep ports *before* `stop_browser4_server` rewrites the
    // registry: it drops entries whose pid it cannot recognise, and the
    // fallback must still sweep the ports those entries recorded.  Without the
    // snapshot a live-but-unrecognised backend lost its registry entry and the
    // sweep fell back to the default ports only, leaving the backend running
    // (the Windows smoke retry then reconnected to the same wedged server).
    let ports = managed_server_ports_for_sweep();
    let mut shutdown = stop_browser4_server(true);
    let fallback_result = force_kill_all_browser4_server_processes(&ports);
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
    // Phase timings are printed because `kill-all` is budgeted by callers (the
    // acceptance suite gives it a deadline); without them a slow phase is
    // indistinguishable from a hang in the captured log.
    let total_started = std::time::Instant::now();

    // Force-kill path should still attempt browser cleanup even if earlier steps panic.
    eprintln!("  [1/5] Notifying reachable servers to close all sessions ...");
    let step_started = std::time::Instant::now();
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(notify_close_all));
    eprintln!(
        "        done ({:.1}s)",
        step_started.elapsed().as_secs_f64()
    );

    // Pre-sweep catches already-orphaned browser processes before server shutdown.
    eprintln!("  [2/5] Scanning for orphaned browser processes (pre-sweep) ...");
    let step_started = std::time::Instant::now();
    let pre_browser_kill = std::panic::catch_unwind(std::panic::AssertUnwindSafe(&kill_browsers))
        .unwrap_or_else(|_| BrowserKillResult::default());
    eprintln!(
        "        Found {} browser process(es), killed {} ({:.1}s)",
        pre_browser_kill.killed_pids.len() + pre_browser_kill.remaining_pids.len(),
        pre_browser_kill.killed_pids.len(),
        step_started.elapsed().as_secs_f64()
    );

    eprintln!("  [3/5] Stopping Browser4 server processes ...");
    let step_started = std::time::Instant::now();
    let shutdown = std::panic::catch_unwind(std::panic::AssertUnwindSafe(stop_server))
        .unwrap_or_else(|_| ShutdownResult::default());
    eprintln!(
        "        Stopped {}, already stopped {}, forced {}, still running {} ({:.1}s)",
        shutdown.stopped_pids.len(),
        shutdown.missing_pids.len(),
        shutdown.forced_pids.len(),
        shutdown.remaining_pids.len(),
        step_started.elapsed().as_secs_f64()
    );

    // Post-sweep catches browsers that spawn late during shutdown.
    eprintln!("  [4/5] Scanning for remaining browser processes (post-sweep) ...");
    let step_started = std::time::Instant::now();
    let post_browser_kill = std::panic::catch_unwind(std::panic::AssertUnwindSafe(&kill_browsers))
        .unwrap_or_else(|_| BrowserKillResult::default());
    eprintln!(
        "        Found {} browser process(es), killed {} ({:.1}s)",
        post_browser_kill.killed_pids.len() + post_browser_kill.remaining_pids.len(),
        post_browser_kill.killed_pids.len(),
        step_started.elapsed().as_secs_f64()
    );
    let browser_kill = merge_browser_kill_results(pre_browser_kill, post_browser_kill);

    let has_activity = !shutdown.stopped_pids.is_empty()
        || !shutdown.forced_pids.is_empty()
        || !shutdown.remaining_pids.is_empty()
        || !browser_kill.killed_pids.is_empty()
        || !browser_kill.remaining_pids.is_empty();
    if has_activity {
        eprintln!("  [5/5] Waiting for processes to exit ...");
        let step_started = std::time::Instant::now();
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(sleep_after));
        eprintln!(
            "        done ({:.1}s)",
            step_started.elapsed().as_secs_f64()
        );
    } else {
        eprintln!("  [5/5] No processes to wait for, skipping.");
    }

    eprintln!(
        "        cleanup finished in {:.1}s",
        total_started.elapsed().as_secs_f64()
    );

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
    registry_path: Option<&Path>,
    state_dir: Option<&Path>,
) {
    let base_urls = close_all_base_urls_for_force_stop(registry_path, state_dir);
    if base_urls.is_empty() {
        return;
    }

    // Run blocking HTTP calls on a dedicated OS thread so that
    // reqwest::blocking's internal tokio runtime is created and
    // destroyed outside the main #[tokio::main] async context.
    // Dropping a tokio runtime inside another tokio runtime panics
    // with "Cannot drop a runtime in a context where blocking is
    // not allowed."
    let handle = std::thread::spawn(move || {
        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap_or_else(|_| reqwest::blocking::Client::new());

        for base_url in &base_urls {
            match call_close_all_sessions(&client, base_url) {
                Ok(_) => eprintln!("        Closed sessions on {}", base_url),
                Err(err) => {
                    eprintln!(
                        "        Server {} unreachable ({}), skipping",
                        base_url, err
                    )
                }
            }
        }
    });

    let _ = handle.join();
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
    // `taskkill /F /T` returns immediately and most processes die within a few
    // hundred ms.  Waiting a full 2s per PID made a 8-browser kill spend ~16s
    // just polling; the post-sweep and final verification already catch any
    // stragglers, so a short wait here is sufficient.
    const WAIT_AFTER_KILL_MS: u64 = 500;

    let start = std::time::Instant::now();
    let timeout = std::time::Duration::from_millis(KILL_TIMEOUT_MS);
    let sweep_delay = std::time::Duration::from_millis(WAIT_BETWEEN_SWEEPS_MS);
    let mut result = BrowserKillResult::default();

    loop {
        let pids = find_unique_pulsar_browser_processes();
        if pids.is_empty() {
            break;
        }

        result.killed_pids.extend(pids.iter().copied());

        // Bulk-kill: on Windows, pass all PIDs to taskkill in a single
        // invocation so process-tree teardown is atomic (avoids orphans
        // that a staggered per-PID approach can leave behind).
        bulk_force_stop_browser_processes(&pids);

        wait_for_exit_all(&pids, WAIT_AFTER_KILL_MS, 100);

        if start.elapsed() >= timeout {
            break;
        }

        std::thread::sleep(sweep_delay);
    }

    // Nothing was ever found: the first scan proved the machine is clean, so
    // the unconditional final sweep below would only repeat the same expensive
    // process scan.
    if result.killed_pids.is_empty() {
        return result;
    }

    result.killed_pids.sort_unstable();
    result.killed_pids.dedup();

    // Final exhaustive sweep to catch any stragglers.
    result.remaining_pids = find_unique_pulsar_browser_processes();

    // If anything is left, try one more aggressive kill cycle.
    if !result.remaining_pids.is_empty() {
        let stragglers = std::mem::take(&mut result.remaining_pids);
        bulk_force_stop_browser_processes(&stragglers);
        wait_for_exit_all(&stragglers, 500, 100);
        result.killed_pids.extend(stragglers);
        result.killed_pids.sort_unstable();
        result.killed_pids.dedup();
        result.remaining_pids = find_unique_pulsar_browser_processes();
    }

    result
}

/// Marker shared by every browser Browser4 launches: its profile root lives under the
/// process-wide temp directory (`.../browser4-pereg/...`).
const BROWSER_TEMP_ROOT_MARKER: &str = "browser4-pereg";

/// Close the browsers launched by **this checkout's** backends.
///
/// The ownership-scoped counterpart of [`kill_all_browsers`], which closes every
/// Browser4 browser on the machine — a neighbouring checkout's browser windows
/// included (issue #640).  Attribution is the parent process: a backend starts
/// its browser directly, so the browser root's parent is one of this checkout's
/// backends (see [`find_browser_roots_launched_by`]).
///
/// An installed runtime (no checkout) has exactly one backend, so it keeps the
/// machine-wide behaviour.
#[allow(dead_code)]
pub fn kill_browsers_of_owned_servers() -> BrowserKillResult {
    let Some(workspace_root) = crate::daemon::dev_workspace_root() else {
        return kill_all_browsers();
    };
    let server_pids = find_workspace_browser4_server_processes(&workspace_root);
    kill_browsers_launched_by(&server_pids)
}

/// Reclaim browser processes whose backend died before it could close them.
///
/// Why this exists: `stop` is deliberately **workspace-scoped for servers** and never
/// swept browsers, "kill-all remains the hammer" (see
/// [stop_workspace_servers_forcibly]). The price was a leak: a force-killed backend
/// leaves its Chrome/Edge children orphaned under the shared `browser4-pereg` temp
/// root — 169 processes accumulated in one afternoon. They are not harmless: the
/// launcher reuses a browser by user-data-dir, so a stale browser **newer than the
/// driver** keeps poisoning every later session (`createDevTools` failing on an
/// unknown `/json/version` field, 2026-10-28).
///
/// Only **orphans** are killed. A browser tree whose root's parent is still a live JVM
/// belongs to a running backend — this workspace's (its tree kill failed) or another
/// workspace's — and stays untouched; killing the root with `/T` takes its
/// renderer/GPU children with it.
pub fn kill_orphaned_browser_processes() -> BrowserKillResult {
    let pids = find_orphaned_browser_roots();
    if pids.is_empty() {
        return BrowserKillResult::default();
    }

    let mut result = BrowserKillResult::default();
    result.killed_pids.extend(pids.iter().copied());
    bulk_force_stop_browser_processes(&pids);
    wait_for_exit_all(&pids, 500, 100);
    result.killed_pids.sort_unstable();
    result.killed_pids.dedup();
    result.remaining_pids = find_orphaned_browser_roots();
    result
}

/// Root browser processes under [BROWSER_TEMP_ROOT_MARKER] whose parent is neither a
/// browser (a renderer/GPU child) nor a live JVM (an owning backend).
fn find_orphaned_browser_roots() -> Vec<u32> {
    #[cfg(windows)]
    {
        use std::process::Command;

        let ps_command = orphan_browser_scan_script();
        let mut pids = match Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &ps_command])
            .output()
        {
            Ok(output) => parse_pid_list(&output.stdout),
            Err(_) => Vec::new(),
        };
        pids.sort_unstable();
        pids.dedup();
        pids
    }

    #[cfg(unix)]
    {
        use std::collections::HashMap;
        use std::process::Command;

        // One `ps` call: pid, ppid and the full argument list for every process.
        let output = match Command::new("ps").args(["-eo", "pid=,ppid=,args="]).output() {
            Ok(output) => output,
            Err(_) => return Vec::new(),
        };
        let text = String::from_utf8_lossy(&output.stdout);
        let mut rows: HashMap<u32, (u32, String)> = HashMap::new();
        for line in text.lines() {
            let mut parts = line.trim_start().splitn(3, char::is_whitespace);
            let pid = parts.next().and_then(|s| s.parse::<u32>().ok());
            let ppid = parts.next().and_then(|s| s.parse::<u32>().ok());
            let args = parts.next().unwrap_or("").trim_start().to_string();
            if let (Some(pid), Some(ppid)) = (pid, ppid) {
                rows.insert(pid, (ppid, args));
            }
        }

        let is_browser = |args: &str| {
            matches_browser_name(executable_name(args))
        };
        let is_jvm = |args: &str| matches!(executable_name(args), "java" | "javaw");

        let mut roots = Vec::new();
        for (pid, (ppid, args)) in &rows {
            if !args.contains(BROWSER_TEMP_ROOT_MARKER) || !is_browser(args) {
                continue;
            }
            let parent = rows.get(ppid);
            if parent.map(|(_, a)| is_browser(a)).unwrap_or(false) {
                continue; // a renderer/GPU child; its root carries the decision
            }
            if !parent.map(|(_, a)| is_jvm(a)).unwrap_or(false) {
                roots.push(*pid);
            }
        }
        roots.sort_unstable();
        roots.dedup();
        roots
    }
}

/// Basename of the executable in a command line (`/usr/bin/java -x` → `java`).
#[cfg(unix)]
fn executable_name(args: &str) -> &str {
    let exe = args.split_whitespace().next().unwrap_or("");
    exe.rsplit('/').next().unwrap_or(exe)
}

/// The PowerShell scan that lists orphaned Browser4 browser roots.
///
/// Extracted from [find_orphaned_browser_roots] so a unit test can guard the markers
/// it matches on: dropping `browser4-pereg` would silently stop reclaiming anything,
/// and treating a browser parent as an owner would make the sweep kill renderers it
/// should leave to their root.
#[cfg(windows)]
fn orphan_browser_scan_script() -> String {
    format!(
        r#"
        $procs = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
        $byId = @{{}}
        foreach ($p in $procs) {{ $byId[[int]$p.ProcessId] = $p }}
        $browserNames = @('chrome.exe','chromium.exe','msedge.exe')
        foreach ($p in $procs) {{
            if ($p.Name -notin $browserNames) {{ continue }}
            if ([string]::IsNullOrWhiteSpace($p.CommandLine)) {{ continue }}
            if ($p.CommandLine -notmatch '{marker}') {{ continue }}
            $parent = $byId[[int]$p.ParentProcessId]
            if ($parent -and ($parent.Name -in $browserNames)) {{ continue }}
            if ($parent -and ($parent.Name -in @('java.exe','javaw.exe'))) {{ continue }}
            $p.ProcessId
        }}
        "#,
        marker = BROWSER_TEMP_ROOT_MARKER
    )
}

/// Browser root processes that were launched by one of `server_pids`.
///
/// `stop` is workspace scoped on purpose — it must never sweep a browser that
/// belongs to another checkout (that is what `kill-all` is for).  It must,
/// however, close the browsers *this workspace's* backend launched: a
/// force-killed backend cannot close them itself, and the Chrome root keeps the
/// now-dead backend pid as its parent, so no later component can attribute it.
/// That leak was reproduced on 2026-10-25 (`open --headless` then `stop` left a
/// `PULSAR_CHROME` root whose parent was the stopped backend).
///
/// Attribution is by parent pid: the backend starts Chrome directly, so a
/// session browser's root `PPID` is the backend pid.  Command lines are matched
/// the same way [`find_unique_pulsar_browser_processes`] matches them.
pub fn find_browser_roots_launched_by(server_pids: &[u32]) -> Vec<u32> {
    if server_pids.is_empty() {
        return Vec::new();
    }

    let mut pids = Vec::new();

    #[cfg(unix)]
    {
        use std::process::Command;

        let Ok(output) = Command::new("ps").args(["-eo", "pid=,args="]).output() else {
            return pids;
        };
        let text = String::from_utf8_lossy(&output.stdout);
        for line in text.lines() {
            let trimmed = line.trim_start();
            let Some((pid_text, args)) = trimmed.split_once(char::is_whitespace) else {
                continue;
            };
            let Ok(pid) = pid_text.trim().parse::<u32>() else {
                continue;
            };
            if !args.contains("PULSAR_CHROME") {
                continue;
            }
            if args.contains("--type=") {
                continue;
            }
            // Resolve the parent with a second, cheap query: `ps` cannot print
            // ppid and args on one portable format string.
            let Ok(ppid_output) = Command::new("ps")
                .args(["-o", "ppid=", "-p", &pid.to_string()])
                .output()
            else {
                continue;
            };
            let ppid_text = String::from_utf8_lossy(&ppid_output.stdout);
            let Ok(ppid) = ppid_text.trim().parse::<u32>() else {
                continue;
            };
            if server_pids.contains(&ppid) {
                pids.push(pid);
            }
        }
    }

    #[cfg(windows)]
    {
        use std::process::Command;

        let pid_list = server_pids
            .iter()
            .map(|pid| pid.to_string())
            .collect::<Vec<_>>()
            .join(",");
        // One unfiltered CIM query (ParentProcessId is not an indexable filter
        // for a set membership test) and in-memory matching, matching the
        // single-round-trip style used elsewhere in this module.
        let ps_command = format!(
            r#"
            $serverPids = @({pid_list})
            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
                Where-Object {{
                    $_.Name -in @('chrome.exe','chromium.exe','msedge.exe') -and
                    $serverPids -contains [uint32]$_.ParentProcessId -and
                    -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
                    (
                        $_.CommandLine -match 'PULSAR_CHROME' -or
                        $_.CommandLine -match 'browser4[\\/]browser[\\/]chrome' -or
                        $_.CommandLine -match 'browser4-apps'
                    )
                }} |
                Select-Object -ExpandProperty ProcessId
            "#
        );
        if let Ok(output) = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &ps_command])
            .output()
        {
            pids.extend(parse_pid_list(&output.stdout));
        }
    }

    pids.sort_unstable();
    pids.dedup();
    pids
}

/// Close the browsers the given backends launched, waiting briefly for the
/// process trees to disappear.  See [`find_browser_roots_launched_by`].
pub fn kill_browsers_launched_by(server_pids: &[u32]) -> BrowserKillResult {
    const KILL_TIMEOUT_MS: u64 = 10_000;
    const WAIT_AFTER_KILL_MS: u64 = 500;
    const SWEEP_DELAY_MS: u64 = 250;

    let mut result = BrowserKillResult::default();
    if server_pids.is_empty() {
        return result;
    }

    let start = std::time::Instant::now();
    loop {
        let pids = find_browser_roots_launched_by(server_pids);
        if pids.is_empty() {
            break;
        }
        result.killed_pids.extend(pids.iter().copied());
        bulk_force_stop_browser_processes(&pids);
        wait_for_exit_all(&pids, WAIT_AFTER_KILL_MS, 100);

        if start.elapsed() >= std::time::Duration::from_millis(KILL_TIMEOUT_MS) {
            break;
        }
        sleep(std::time::Duration::from_millis(SWEEP_DELAY_MS));
    }

    result.killed_pids.sort_unstable();
    result.killed_pids.dedup();
    result.remaining_pids = find_browser_roots_launched_by(server_pids);
    result
}

/// Kill multiple browser processes in a single bulk operation.
/// On Windows, uses `taskkill /F /PID ...` with all PIDs so the
/// process trees are torn down together, reducing orphan risk.
fn bulk_force_stop_browser_processes(pids: &[u32]) {    if pids.is_empty() {
        return;
    }

    #[cfg(unix)]
    {
        for pid in pids {
            force_stop(*pid);
        }
    }

    #[cfg(windows)]
    {
        let mut args: Vec<String> = vec!["/F".to_string()];
        for pid in pids {
            args.push("/PID".to_string());
            args.push(pid.to_string());
        }
        args.push("/T".to_string());

        let bulk_succeeded = std::process::Command::new("taskkill")
            .args(&args)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false);

        if !bulk_succeeded {
            // Fall back to individual kills for any that the bulk call missed.
            for pid in pids {
                force_stop_browser_process(*pid);
            }
        }
    }
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

    // Development mode keeps each checkout's browser profiles under its own
    // app data root (`-Dapp.data.dir`), so the workspace's browser directory
    // has to be scanned too — otherwise the launcher markers of this
    // checkout's browsers are invisible to `kill-all` / browser cleanup.
    roots.extend(crate::daemon::workspace_browser_data_roots());

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
        || normalized.contains("browser4standaloneapplicationkt")
        // Bundle servers launch via `java @argfile` (long classpath) and the
        // launcher deletes the argfile once the JVM is up, so the main class
        // never appears in the command line again.  The bundled JRE path is
        // then the only reliable marker: dev worktrees use
        // `...\browser4-apps\browser4-bundle\target\runtime-bundle\_work\...`,
        // installed runtimes use `...\browser4\runtime\vX.Y.Z\runtime\bin\java.exe`.
        || normalized.contains("browser4-apps/browser4-bundle")
        || normalized.contains("runtime-bundle")
        || normalized.contains("browser4/runtime/")
}

/// True when [command_line] mentions [path] as a whole path.
///
/// A plain substring test is not enough: checkouts sit next to each other, so
/// the root `D:/workspace/Browser4/Browser4-4.16` is a **prefix** of the
/// neighbouring checkout `D:/workspace/Browser4/Browser4-4.16-feat` — and that
/// neighbouring backend's command line (`.../Browser4-4.16-feat/browser4-apps/
/// browser4-bundle/target/runtime-bundle/_work/.../runtime/bin/java.exe`) would
/// then read as "owned by us".  That exact confusion is what let one checkout's
/// e2e sweep force-stop another checkout's backend (issue #640), so a match
/// only counts when the character after it cannot continue a path name.
fn command_line_mentions_path(command_line: &str, path: &Path) -> bool {
    let needle = normalize_process_text(&path.to_string_lossy());
    let needle = needle.trim_end_matches('/');
    if needle.is_empty() {
        return false;
    }

    let haystack = normalize_process_text(command_line);
    let mut search_from = 0usize;
    while let Some(offset) = haystack[search_from..].find(needle) {
        let end = search_from + offset + needle.len();
        let continues_name = haystack[end..]
            .chars()
            .next()
            .map(path_name_continuation)
            .unwrap_or(false);
        if !continues_name {
            return true;
        }
        search_from = end;
    }
    false
}

/// True for characters that may continue a file name, i.e. the ones that make a
/// path match a *different* path (`browser4-4.16` → `browser4-4.16-feat`).
/// Both separators pass through, so a checkout root still matches the
/// directories underneath it.
fn path_name_continuation(ch: char) -> bool {
    ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_' | '.' | '~')
}

/// True when [command_line] is a Browser4 backend **launched from
/// [workspace_root]**.
///
/// Recognition ([`command_line_matches_browser4_server`]) is deliberately not
/// enough: every checkout on the machine runs the same backend, so it alone
/// cannot distinguish "my backend" from "the neighbour's backend" — the mistake
/// behind issue #640.  Ownership is the checkout the launcher ran from, and
/// every launch path leaves that root in the command line:
///
/// * the bundled JRE lives inside the checkout in development mode
///   (`<root>/browser4-apps/browser4-bundle/target/runtime-bundle/_work/...`),
///   and it stays visible even when the classpath was moved into a `@argfile`
///   that the launcher has already deleted;
/// * `-Dbrowser4.agent.workspace=<root>` is injected on every launch
///   (`daemon::build_server_launch_spec`) and is visible whenever the command
///   line was not folded into an argfile.
fn is_workspace_owned_browser4_server(command_line: &str, workspace_root: &Path) -> bool {
    command_line_matches_browser4_server(command_line)
        && command_line_mentions_path(command_line, workspace_root)
}

/// One PowerShell round-trip: every `java`/`javaw` pid together with its
/// command line, emitted as JSON.
///
/// The former flow listed the pids first and then queried the name *and* the
/// command line of every java process separately (`is_java_process` +
/// `process_command_line`): `1 + 2N` PowerShell launches per sweep.  On a
/// developer machine with a dozen JVMs (IDE, Gradle, Kotlin daemons) that alone
/// pushed `kill-all` past its acceptance-test deadline.  JSON keeps command
/// lines that contain tabs, quotes or spaces intact.
#[cfg(windows)]
const WINDOWS_JAVA_PROCESS_SNAPSHOT_PS: &str = r#"
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(java|javaw)\.exe$' } |
        Select-Object ProcessId, CommandLine |
        ConvertTo-Json -Compress
"#;

fn find_browser4_server_processes() -> Vec<u32> {
    let mut pids = Vec::new();

    #[cfg(unix)]
    {
        use std::process::Command;
        if let Ok(output) = Command::new("pgrep")
            .args([
                "-f",
                r"browser4\.jar|browser4launcherkt|browser4bundleapplicationkt|browser4standaloneapplicationkt",
            ])
            .output()
        {
            pids.extend(parse_pid_list(&output.stdout));
        }
    }

    #[cfg(windows)]
    {
        use std::process::Command;
        // Single snapshot: pid + command line for every JVM, filtered in Rust.
        // Matching must not rely on the main class appearing in the raw
        // command line — a bundle server is launched via `@argfile`, so the
        // argfile is expanded before the comparison.
        if let Ok(output) = Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                WINDOWS_JAVA_PROCESS_SNAPSHOT_PS,
            ])
            .output()
        {
            pids.extend(parse_java_process_snapshot(&String::from_utf8_lossy(
                &output.stdout,
            )));
        }
    }

    pids.sort_unstable();
    pids.dedup();
    pids
}

/// Keep the pids whose command line matches a Browser4 backend.
///
/// `ConvertTo-Json` emits a bare object for a single process and an array for
/// several, so both shapes are accepted.  Argfile expansion has to happen here:
/// a bundle server is launched as `java @argfile` and the launcher deletes the
/// argfile once the JVM is up, which leaves the main class invisible in the raw
/// command line.
#[cfg_attr(not(windows), allow(dead_code))]
fn parse_java_process_snapshot(stdout: &str) -> Vec<u32> {
    parse_java_process_snapshot_entries(stdout)
        .into_iter()
        .filter(|(_, command_line)| command_line_matches_browser4_server(command_line))
        .map(|(pid, _)| pid)
        .collect()
}

/// Every JVM in a `WINDOWS_JAVA_PROCESS_SNAPSHOT_PS` snapshot as
/// `(pid, expanded command line)`.
///
/// The ownership-scoped sweep needs the command line itself, not just a
/// recognition verdict: deciding whether a backend belongs to *this* checkout
/// requires the paths the launcher was started with.
#[cfg_attr(not(windows), allow(dead_code))]
fn parse_java_process_snapshot_entries(stdout: &str) -> Vec<(u32, String)> {
    let trimmed = stdout.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    let Ok(value) = serde_json::from_str::<Value>(trimmed) else {
        return Vec::new();
    };
    let entries: Vec<&Value> = match &value {
        Value::Array(items) => items.iter().collect(),
        Value::Object(_) => vec![&value],
        _ => return Vec::new(),
    };

    entries
        .into_iter()
        .filter_map(|entry| {
            let pid = u32::try_from(entry.get("ProcessId")?.as_u64()?).ok()?;
            let command_line = entry
                .get("CommandLine")
                .and_then(|value| value.as_str())
                .unwrap_or_default();
            Some((pid, expand_windows_argfile(command_line.to_string())))
        })
        .collect()
}

/// `(pid, command line)` of every JVM on this machine.
///
/// One process-table round-trip per platform: a single WMI query on Windows,
/// one `ps` on unix.  The ownership-scoped sweep compares *all* of them against
/// a checkout root, so it cannot reuse the recognition-only pids.
fn jvm_process_command_lines() -> Vec<(u32, String)> {
    #[cfg(windows)]
    {
        use std::process::Command;
        let Ok(output) = Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                WINDOWS_JAVA_PROCESS_SNAPSHOT_PS,
            ])
            .output()
        else {
            return Vec::new();
        };
        let mut entries =
            parse_java_process_snapshot_entries(&String::from_utf8_lossy(&output.stdout));
        entries.sort_by_key(|(pid, _)| *pid);
        entries.dedup_by_key(|(pid, _)| *pid);
        entries
    }

    #[cfg(unix)]
    {
        use std::process::Command;
        let Ok(output) = Command::new("ps").args(["-eo", "pid=,args="]).output() else {
            return Vec::new();
        };
        let text = String::from_utf8_lossy(&output.stdout);
        let mut entries: Vec<(u32, String)> = text
            .lines()
            .filter_map(|line| {
                let trimmed = line.trim_start();
                let (pid_text, args) = trimmed.split_once(char::is_whitespace)?;
                let pid = pid_text.trim().parse::<u32>().ok()?;
                matches!(executable_name(args), "java" | "javaw").then(|| (pid, args.to_string()))
            })
            .collect();
        entries.sort_by_key(|(pid, _)| *pid);
        entries.dedup_by_key(|(pid, _)| *pid);
        entries
    }
}

/// PIDs of the Browser4 backends launched from [workspace_root].
///
/// The ownership-scoped counterpart of [`find_browser4_server_processes`]: same
/// JVM scan, but a backend only qualifies when its command line carries
/// [workspace_root] (see [`is_workspace_owned_browser4_server`]).  A
/// neighbouring checkout's backend carries *its* root and is therefore never a
/// candidate.
fn find_workspace_browser4_server_processes(workspace_root: &Path) -> Vec<u32> {
    let mut pids: Vec<u32> = jvm_process_command_lines()
        .into_iter()
        .filter(|(_, command_line)| {
            is_workspace_owned_browser4_server(command_line, workspace_root)
        })
        .map(|(pid, _)| pid)
        .collect();
    dedup_sort_u32(&mut pids);
    pids
}

fn force_kill_all_browser4_server_processes(ports: &[u16]) -> ServerKillResult {
    // Command-line pattern matching misses servers launched via @argfile or
    // with a bundle start script. Add a port-based sweep: any java process
    // still LISTENING on a managed port (or the default 8182) IS a Browser4
    // server for the purposes of `stop`.
    let mut pids = find_browser4_server_processes();
    pids.extend(find_java_pids_listening_on_ports(ports));
    force_stop_pids(pids)
}

/// Force-stop Browser4 backends LISTENING on [ports], without the global
/// command-line scan.
///
/// Used by the development-mode `stop`, which must not touch backends owned by
/// neighbouring workspaces (each checkout runs its own port, see
/// `daemon::DEV_SERVER_PORT_START`).
fn force_kill_browser4_server_processes_on_ports(ports: &[u16]) -> ServerKillResult {
    force_stop_pids(jvm_pids_listening_on_ports(ports))
}

/// PIDs of *JVM* processes listening on [ports] — the only kill candidates a
/// port sweep may use.
///
/// [`find_java_pids_listening_on_ports`] reports whatever owns the listening
/// socket, and in development mode the swept ports come from the resolved
/// `--server` URL — which may point at a service that has nothing to do with
/// Browser4.  Killing those PIDs takes down an unrelated process: it took down
/// the e2e harness, whose Rust mock backend listens on exactly such a port.
///
/// Java-ness is the strongest check that works here.  The stricter
/// [`is_browser4_server_process`] cannot be used: a backend is launched as
/// `java @<temp argfile>`, and that argfile is gone by the time `stop` runs, so
/// the live backend's command line carries no Browser4 marker.
fn jvm_pids_listening_on_ports(ports: &[u16]) -> Vec<u32> {
    let mut pids: Vec<u32> = find_java_pids_listening_on_ports(ports)
        .into_iter()
        .filter(|&pid| is_java_process(pid))
        .collect();
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn force_stop_pids(mut pids: Vec<u32>) -> ServerKillResult {
    const WAIT_AFTER_KILL_MS: u64 = 500;
    const WAIT_POLL_MS: u64 = 100;

    pids.sort_unstable();
    pids.dedup();

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

/// Ports of every backend this workspace started, newest entry last.
///
/// The registry lives in the checkout's own state dir in development mode, so
/// this list never contains a neighbouring workspace's backend.
pub fn managed_server_ports(registry_path: Option<&Path>) -> Vec<u16> {
    let mut ports: Vec<u16> = read_managed_server_processes(registry_path)
        .iter()
        .map(|p| p.port)
        .filter(|&p| p > 0)
        .collect();
    ports.sort_unstable();
    ports.dedup();
    ports
}

/// Force-stop every backend this workspace started, wherever it landed, and
/// close the browsers those backends launched.
///
/// Development mode uses this instead of the global `stop`: a workspace may own
/// more than one port over time (an explicit `--server` URL, a re-allocation
/// after another checkout stole the recorded port), and the registry — not the
/// currently resolved URL — is the authoritative list.  [extra_port] covers a
/// backend on the resolved URL that the registry does not know about (started
/// by hand, or by an older CLI version).
///
/// The *global* browser sweep stays out of this path: browsers belong to
/// whichever workspace launched them, and `kill-all` remains the hammer for a
/// full cross-workspace cleanup.  The browsers these backends launched are a
/// different matter — a force-killed backend cannot close them, and leaving
/// them behind strands a `PULSAR_CHROME` root whose parent no longer exists
/// (see [`find_browser_roots_launched_by`]).
pub fn stop_workspace_servers_forcibly(extra_port: Option<u16>) -> ForceStopBrowser4ServerResult {
    let mut ports = managed_server_ports(None);
    if let Some(port) = extra_port.filter(|port| *port > 0) {
        if !ports.contains(&port) {
            ports.push(port);
        }
    }
    if ports.is_empty() {
        return ForceStopBrowser4ServerResult::default();
    }

    let mut shutdown = ShutdownResult::default();
    for port in &ports {
        let one = shutdown_managed_server_processes_on_port(true, None, *port, 5_000, 250);
        merge_shutdown_results(&mut shutdown, one);
    }

    let fallback = force_kill_browser4_server_processes_on_ports(&ports);
    merge_shutdown_with_fallback_server_kill(&mut shutdown, &fallback);
    shutdown.fallback_killed_server_pids = fallback.killed_pids.clone();

    let mut server_pids = shutdown.stopped_pids.clone();
    server_pids.extend(shutdown.forced_pids.iter().copied());
    server_pids.extend(shutdown.fallback_killed_server_pids.iter().copied());
    server_pids.sort_unstable();
    server_pids.dedup();
    let browser_kill = kill_browsers_launched_by(&server_pids);

    ForceStopBrowser4ServerResult {
        shutdown,
        browser_kill,
    }
}

/// Opt-in switch that restores the machine-wide sweep in development mode.
///
/// Off by default: a machine that runs several checkouts (or several agent
/// sessions) is this project's normal working setup, and a sweep that reaches
/// beyond its own checkout kills a neighbour's backend mid-task (issue #640).
pub const SWEEP_ALL_WORKSPACES_ENV: &str = "BROWSER4_SWEEP_ALL_WORKSPACES";

/// Force-stop the Browser4 backends **this checkout** launched, plus the
/// browsers they launched.
///
/// This is the sweep the e2e harness runs before and after a test run, and it
/// is deliberately *ownership*-scoped rather than recognition-scoped:
///
/// * recognition ([`command_line_matches_browser4_server`]) matches every
///   Browser4 backend on the machine, so the pre-sweep of a run in checkout A
///   force-stopped the backend of checkout B while B was in the middle of an
///   agent task (issue #640);
/// * ownership is the checkout root the launcher ran from
///   ([`find_workspace_browser4_server_processes`]), plus the entries this
///   workspace registered itself, plus listeners on *those* ports;
/// * the default ports 8182/18182 are **not** swept: in development mode they
///   belong to no workspace in particular, and the ports a checkout's e2e run
///   uses are ephemeral, so they only ever named a neighbour's backend;
/// * browsers are swept by attribution — parents that are one of the killed
///   backends, and orphans under this workspace's own temp root — instead of by
///   the machine-wide `PULSAR_CHROME` marker.
///
/// Set [`SWEEP_ALL_WORKSPACES_ENV`] to get the old machine-wide behaviour (an
/// installed runtime, which has exactly one backend, always uses it).
// Called through the `browser4_cli` library by the e2e harness (which sweeps
// before/after every run); the `browser4-cli` binary itself has no caller.
#[allow(dead_code)]
pub fn stop_owned_browser4_servers_forcibly() -> ForceStopBrowser4ServerResult {
    let Some(workspace_root) = crate::daemon::dev_workspace_root() else {
        // Not running from a checkout: an installed CLI owns the only backend
        // on the machine, so the global sweep cannot reach anyone else's.
        return stop_browser4_server_forcibly();
    };
    if sweep_all_workspaces_enabled() {
        eprintln!(
            "  [sweep] {SWEEP_ALL_WORKSPACES_ENV} is set: sweeping every workspace's backends."
        );
        return stop_browser4_server_forcibly();
    }
    stop_owned_browser4_servers_forcibly_for(&workspace_root)
}

/// [`SWEEP_ALL_WORKSPACES_ENV`] parsed from the process environment.
fn sweep_all_workspaces_enabled() -> bool {
    sweep_all_workspaces_enabled_from(std::env::var(SWEEP_ALL_WORKSPACES_ENV).ok().as_deref())
}

/// Pure half of [`sweep_all_workspaces_enabled`] so the accepted spellings are
/// unit-testable without touching the process environment.  An unset variable,
/// or a typo, must never widen the sweep.
fn sweep_all_workspaces_enabled_from(raw: Option<&str>) -> bool {
    matches!(
        raw.map(str::trim),
        Some("1" | "true" | "TRUE" | "yes" | "YES" | "on" | "ON")
    )
}

fn stop_owned_browser4_servers_forcibly_for(
    workspace_root: &Path,
) -> ForceStopBrowser4ServerResult {
    let total_started = std::time::Instant::now();
    let registry_path = managed_server_registry_path(None);
    let state_dir = resolve_default_state_dir();
    let registered_ports = managed_server_ports(None);

    eprintln!("  [1/4] Notifying this workspace's servers to close all sessions ...");
    let step_started = std::time::Instant::now();
    notify_owned_servers_before_force_stop(workspace_root, &registry_path, &state_dir);
    eprintln!(
        "        done ({:.1}s)",
        step_started.elapsed().as_secs_f64()
    );

    eprintln!("  [2/4] Stopping this workspace's Browser4 server processes ...");
    let step_started = std::time::Instant::now();
    // Registered backends first: a registry entry is the CLI's own record of a
    // pid it launched, and it is only acted on while that pid still owns the
    // recorded port, so a recycled pid can never be killed.
    let mut shutdown = stop_browser4_server(true);
    let owned = force_stop_owned_browser4_server_processes(workspace_root, &registered_ports);
    merge_shutdown_with_fallback_server_kill(&mut shutdown, &owned);
    shutdown.fallback_killed_server_pids = owned.killed_pids.clone();
    eprintln!(
        "        Stopped {}, forced {}, still running {} ({:.1}s)",
        shutdown.stopped_pids.len(),
        shutdown.forced_pids.len(),
        shutdown.remaining_pids.len(),
        step_started.elapsed().as_secs_f64()
    );

    eprintln!("  [3/4] Closing the browsers those backends launched ...");
    let step_started = std::time::Instant::now();
    let mut server_pids = shutdown.stopped_pids.clone();
    server_pids.extend(shutdown.forced_pids.iter().copied());
    server_pids.sort_unstable();
    server_pids.dedup();
    let browser_kill = merge_browser_kill_results(
        kill_browsers_launched_by(&server_pids),
        kill_owned_orphaned_browser_processes(),
    );
    eprintln!(
        "        Closed {} browser(s), {} still running ({:.1}s)",
        browser_kill.killed_pids.len(),
        browser_kill.remaining_pids.len(),
        step_started.elapsed().as_secs_f64()
    );

    let has_activity = !shutdown.stopped_pids.is_empty()
        || !shutdown.forced_pids.is_empty()
        || !shutdown.remaining_pids.is_empty()
        || !browser_kill.killed_pids.is_empty()
        || !browser_kill.remaining_pids.is_empty();
    if has_activity {
        eprintln!("  [4/4] Waiting for processes to exit ...");
        let step_started = std::time::Instant::now();
        sleep(std::time::Duration::from_secs(2));
        eprintln!(
            "        done ({:.1}s)",
            step_started.elapsed().as_secs_f64()
        );
    } else {
        eprintln!("  [4/4] No processes to wait for, skipping.");
    }

    eprintln!(
        "        cleanup finished in {:.1}s",
        total_started.elapsed().as_secs_f64()
    );

    ForceStopBrowser4ServerResult {
        shutdown,
        browser_kill,
    }
}

/// One attributed kill candidate of the workspace-scoped sweep.
#[derive(Debug, Clone, PartialEq, Eq)]
struct OwnedServerCandidate {
    pid: u32,
    /// Why this pid is ours: `checkout` (its command line carries this
    /// checkout's root) or `registry-port` (it listens on a port this
    /// workspace's registry recorded).
    source: &'static str,
    port: Option<u16>,
    command_line: String,
}

/// Force-stop every backend that belongs to [workspace_root].
///
/// Candidates come from three places, and each one is *ownership* checked, not
/// merely recognized as Browser4:
///
/// 1. JVMs whose command line carries the checkout root
///    ([`find_workspace_browser4_server_processes`]) — covers backends the
///    registry no longer knows about (a registry deleted with its state dir, an
///    interrupted run);
/// 2. JVM listeners on [registered_ports] — ports this workspace's own registry
///    recorded, used only when the listener is also owned (a port can be
///    re-allocated to a neighbour after our backend died);
/// 3. nothing else: the default 8182/18182 are deliberately not swept here.
fn force_stop_owned_browser4_server_processes(
    workspace_root: &Path,
    registered_ports: &[u16],
) -> ServerKillResult {
    let candidates = owned_server_candidates(workspace_root, registered_ports);
    if candidates.is_empty() {
        return ServerKillResult::default();
    }

    // The audit trail the incident could not reconstruct: which pid was killed,
    // why it counted as ours, and the command line it had at the time.
    for candidate in &candidates {
        eprintln!(
            "  [sweep] kill pid={} source={} port={} cmd={}",
            candidate.pid,
            candidate.source,
            candidate
                .port
                .map(|port| port.to_string())
                .unwrap_or_else(|| "-".to_string()),
            truncate_for_log(&candidate.command_line, 200)
        );
    }

    force_stop_pids(
        candidates
            .into_iter()
            .map(|candidate| candidate.pid)
            .collect(),
    )
}

/// [`OwnedServerCandidate`]s for [workspace_root], de-duplicated by pid.
fn owned_server_candidates(
    workspace_root: &Path,
    registered_ports: &[u16],
) -> Vec<OwnedServerCandidate> {
    // One process-table round-trip, plus one netstat round-trip when the
    // registry named ports; the decision itself is pure and unit-tested.
    let port_listeners: Vec<(u32, u16)> = registered_ports
        .iter()
        .flat_map(|&port| {
            find_java_pids_listening_on_ports(&[port])
                .into_iter()
                .map(move |pid| (pid, port))
        })
        .collect();
    owned_server_candidates_from(
        &jvm_process_command_lines(),
        workspace_root,
        &port_listeners,
    )
}

/// Pure core of [`owned_server_candidates`].
///
/// [command_lines] is the machine's JVM table, [port_listeners] the
/// `(pid, port)` pairs found LISTENING on the ports this workspace's registry
/// recorded.  Both are injected so the selection — the part that decides whether
/// another checkout's backend survives — is testable without spawning anything.
fn owned_server_candidates_from(
    command_lines: &[(u32, String)],
    workspace_root: &Path,
    port_listeners: &[(u32, u16)],
) -> Vec<OwnedServerCandidate> {
    let mut candidates: Vec<OwnedServerCandidate> = Vec::new();

    for (pid, command_line) in command_lines {
        if is_workspace_owned_browser4_server(command_line, workspace_root) {
            candidates.push(OwnedServerCandidate {
                pid: *pid,
                source: "checkout",
                port: None,
                command_line: command_line.clone(),
            });
        }
    }

    for (pid, port) in port_listeners {
        if candidates.iter().any(|candidate| candidate.pid == *pid) {
            continue;
        }
        // Attribution is the same predicate as above: a listener on a port we
        // once used is ours only when it is a Browser4 backend launched from
        // this checkout.  A neighbour that took the port over fails this check
        // and is left alone.
        let Some((_, command_line)) = command_lines
            .iter()
            .find(|(candidate_pid, _)| candidate_pid == pid)
        else {
            continue;
        };
        if is_workspace_owned_browser4_server(command_line, workspace_root) {
            candidates.push(OwnedServerCandidate {
                pid: *pid,
                source: "registry-port",
                port: Some(*port),
                command_line: command_line.clone(),
            });
        }
    }

    candidates.sort_by_key(|candidate| candidate.pid);
    candidates.dedup_by_key(|candidate| candidate.pid);
    candidates
}

/// Shorten a command line for a log line, on a char boundary.
fn truncate_for_log(value: &str, max_chars: usize) -> String {
    if value.chars().count() <= max_chars {
        return value.to_string();
    }
    let mut truncated: String = value.chars().take(max_chars).collect();
    truncated.push('…');
    truncated
}

/// Close orphaned browsers that belong to this workspace's own temp tree.
///
/// An orphan is a Browser4 browser root whose parent JVM is gone — this
/// workspace's backend was force-killed before it could close them.  Ownership
/// comes from the profile path: every browser this checkout launches carries the
/// checkout's own app-data tree in its `--user-data-dir`
/// (`daemon::workspace_app_data_path`), so a neighbouring workspace's browser
/// (a different tree) is never a candidate even though it carries the same
/// `browser4-pereg` marker.
fn kill_owned_orphaned_browser_processes() -> BrowserKillResult {
    let Some(temp_root) = crate::daemon::workspace_app_data_path().map(|dir| dir.join("tmp"))
    else {
        return BrowserKillResult::default();
    };

    let pids: Vec<u32> = find_orphaned_browser_roots()
        .into_iter()
        .filter(|&pid| {
            process_command_line(pid)
                .map(|command_line| command_line_mentions_path(&command_line, &temp_root))
                .unwrap_or(false)
        })
        .collect();
    if pids.is_empty() {
        return BrowserKillResult::default();
    }

    for pid in &pids {
        eprintln!(
            "  [sweep] kill pid={} source=orphan-browser dir={}",
            pid,
            temp_root.display()
        );
    }

    let mut result = BrowserKillResult::default();
    result.killed_pids.extend(pids.iter().copied());
    bulk_force_stop_browser_processes(&pids);
    wait_for_exit_all(&pids, 500, 100);
    dedup_sort_u32(&mut result.killed_pids);
    result
}

/// Notify only the servers this workspace owns to close their sessions.
///
/// The machine-wide version posts `close_all_sessions` to every URL in the
/// registry *and* to whatever `cli-state.json` remembers.  In a stale state
/// that URL points at a port a neighbour has since taken over — the incident
/// fired `close_all_sessions` at the neighbour's backend (8282) for exactly that
/// reason — so every URL is checked for ownership first, and a state record the
/// CLI already marked `stale` is not used at all (see
/// [`notify_base_url_candidates`]).
fn notify_owned_servers_before_force_stop(
    workspace_root: &Path,
    registry_path: &Path,
    state_dir: &Path,
) {
    // A state file that does not exist carries no session to close — and its
    // default `base_url` is the production port, not something this workspace
    // ever chose.
    let state_path = state_dir.join("cli-state.json");
    let state = state_path
        .is_file()
        .then(|| read_state(Some(state_dir), None));
    let mut base_urls = notify_base_url_candidates(
        &read_managed_server_processes(Some(registry_path)),
        state.as_ref(),
    );
    base_urls.retain(|base_url| owned_base_url(workspace_root, base_url));
    if base_urls.is_empty() {
        return;
    }

    let handle = std::thread::spawn(move || {
        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap_or_else(|_| reqwest::blocking::Client::new());

        for base_url in &base_urls {
            match call_close_all_sessions(&client, base_url) {
                Ok(_) => eprintln!("        Closed sessions on {}", base_url),
                Err(err) => eprintln!(
                    "        Server {} unreachable ({}), skipping",
                    base_url, err
                ),
            }
        }
    });
    let _ = handle.join();
}

/// Base URLs worth notifying, before the ownership check runs.
///
/// The registry's URLs name backends this workspace started, so they are always
/// candidates (a stale one is filtered by [`owned_base_url`]).  The URL in
/// `cli-state.json` is a *memory*, not a record of ownership, and it is only a
/// candidate when it was actually read from disk:
///
/// * no state file at all (a fresh checkout) — [`crate::state::CliState::default`]
///   carries the production port 18182, which this workspace never chose, so
///   notifying it would reach whatever release backend happens to listen there;
/// * a state `stop` already marked `stale` — the port it remembers may have been
///   re-allocated to a neighbour, and the incident posted `close_all_sessions`
///   to exactly such a URL (8282, the neighbouring checkout's port, issue #640).
fn notify_base_url_candidates(
    registered: &[ManagedServerProcess],
    state: Option<&crate::state::CliState>,
) -> Vec<String> {
    let mut base_urls: Vec<String> = registered
        .iter()
        .map(|proc| proc.base_url.clone())
        .filter(|base_url| !base_url.trim().is_empty())
        .collect();
    if let Some(state) = state {
        if !state.stale && !state.base_url.trim().is_empty() {
            base_urls.push(state.base_url.clone());
        }
    }
    base_urls.sort();
    base_urls.dedup();
    base_urls
}

/// True when [base_url] is served by a backend this checkout launched, or by
/// nobody at all (an unreachable URL is harmless to notify).
fn owned_base_url(workspace_root: &Path, base_url: &str) -> bool {
    let Some(port) = crate::daemon::local_backend_port(base_url) else {
        // Not a loopback URL: nothing local to own.
        return false;
    };
    let listeners = find_java_pids_listening_on_ports(&[port]);
    if listeners.is_empty() {
        // Nothing is listening; the notify call fails harmlessly.  Refusing it
        // here would hide a real backend behind an OS scan hiccup.
        return true;
    }
    listeners.iter().any(|&pid| {
        process_command_line(pid)
            .map(|command_line| is_workspace_owned_browser4_server(&command_line, workspace_root))
            .unwrap_or(false)
    })
}

/// Fold [other] into [target], de-duplicating the PID lists.
fn merge_shutdown_results(target: &mut ShutdownResult, other: ShutdownResult) {
    target.stopped_pids.extend(other.stopped_pids);
    target.missing_pids.extend(other.missing_pids);
    target.forced_pids.extend(other.forced_pids);
    target.remaining_pids.extend(other.remaining_pids);
    target.fallback_killed_server_pids.extend(other.fallback_killed_server_pids);
    target.graceful_attempted = target.graceful_attempted || other.graceful_attempted;

    dedup_sort_u32(&mut target.stopped_pids);
    dedup_sort_u32(&mut target.missing_pids);
    dedup_sort_u32(&mut target.forced_pids);
    dedup_sort_u32(&mut target.remaining_pids);
    dedup_sort_u32(&mut target.fallback_killed_server_pids);
    target
        .remaining_pids
        .retain(|pid| !target.stopped_pids.contains(pid));
}

/// Ports swept by the port-based server cleanup: every port recorded in the
/// managed-process registry plus the default ports 8182 (generic) and 18182
/// (runtime bundle default from `application-bundle.properties`).  Bundle
/// servers listen on 18182 by default, so without it an unregistered server
/// would survive `kill-all` / `stop`.
fn managed_server_ports_for_sweep() -> Vec<u16> {
    let mut ports: Vec<u16> = read_managed_server_processes(None)
        .iter()
        .map(|p| p.port)
        .filter(|&p| p > 0)
        .collect();
    for default_port in [8182u16, 18182] {
        if !ports.contains(&default_port) {
            ports.push(default_port);
        }
    }
    ports
}

/// Java PIDs currently LISTENING on any of [ports], resolved via netstat.
/// Cross-platform: Windows `netstat -ano -p tcp` and unix `netstat -anp`.
fn find_java_pids_listening_on_ports(ports: &[u16]) -> Vec<u32> {
    if ports.is_empty() {
        return Vec::new();
    }

    let mut pids: Vec<u32> = Vec::new();

    #[cfg(windows)]
    {
        use std::process::Command;
        if let Ok(output) = Command::new("netstat").args(["-ano", "-p", "tcp"]).output() {
            let text = String::from_utf8_lossy(&output.stdout);
            for line in text.lines() {
                if !line.to_ascii_uppercase().contains("LISTENING") {
                    continue;
                }
                let tokens: Vec<&str> = line.split_whitespace().collect();
                // Proto Local Foreign State PID
                if tokens.len() >= 5 && local_address_matches_any_port(tokens.get(1).copied(), ports) {
                    if let Some(pid) = tokens.last().and_then(|t| t.parse::<u32>().ok()) {
                        pids.push(pid);
                    }
                }
            }
        }
    }

    #[cfg(unix)]
    {
        use std::process::Command;
        if let Ok(output) = Command::new("netstat").args(["-anp"]).output() {
            let text = String::from_utf8_lossy(&output.stdout);
            for line in text.lines() {
                if !line.to_ascii_uppercase().contains("LISTEN") {
                    continue;
                }
                let tokens: Vec<&str> = line.split_whitespace().collect();
                // Proto Recv-Q Send-Q Local Foreign State PID/Program
                if tokens.len() >= 6 && local_address_matches_any_port(tokens.get(3).copied(), ports) {
                    let pid = tokens
                        .last()
                        .and_then(|t| t.split('/').next())
                        .and_then(|t| t.parse::<u32>().ok());
                    if let Some(pid) = pid {
                        pids.push(pid);
                    }
                }
            }
        }
    }

    pids.sort_unstable();
    pids.dedup();
    pids
}

/// True when a netstat local-address column (`0.0.0.0:8182`, `[::]:18182`,
/// `127.0.0.1:8182`) has a port in [ports].
fn local_address_matches_any_port(local_address: Option<&str>, ports: &[u16]) -> bool {
    local_address
        .and_then(|addr| addr.rsplit(':').next())
        .and_then(|p| p.parse::<u16>().ok())
        .map(|p| ports.contains(&p))
        .unwrap_or(false)
}

fn is_java_process(pid: u32) -> bool {
    process_name(pid)
        .map(|name| {
            let normalized = normalize_process_text(&name);
            normalized == "java"
                || normalized == "java.exe"
                || normalized == "javaw"
                || normalized == "javaw.exe"
        })
        .unwrap_or(false)
}

fn is_browser4_server_process(pid: u32) -> bool {
    is_java_process(pid)
        && process_command_line(pid)
            .map(|command_line| command_line_matches_browser4_server(&command_line))
            .unwrap_or(false)
}

/// True when [pid] is the live backend a registry entry recorded for [port].
///
/// A bundle server is launched as `java @argfile`.  Once the launcher removes
/// that argfile the command line carries no Browser4 marker any more, so
/// [`is_browser4_server_process`] cannot recognise it — by design, not because
/// the pid was reused.  The port the CLI wrote into the registry is the
/// authoritative identity then: a pid that still owns that listening port IS
/// our backend.
fn is_registered_browser4_server_process(pid: u32, port: u16) -> bool {
    is_browser4_server_process(pid) || pid_owns_port(pid, port)
}

/// True when [pid] currently owns a LISTENING socket on [port].
///
/// The port scan is the same cross-platform one the force-kill sweep uses; it
/// is a read-only check and never signals the process.
fn pid_owns_port(pid: u32, port: u16) -> bool {
    port > 0 && find_java_pids_listening_on_ports(&[port]).contains(&pid)
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
        // IMPORTANT: Get-CimInstance -Filter uses WQL syntax — operators are
        // OR / AND / NOT (no dashes).  PowerShell-style -or / -and / -not
        // are silently accepted but ALWAYS return zero results.
        //
        // Match Browser4-managed Chrome/Chromium/Edge processes by:
        //   - PULSAR_CHROME environment marker (set by the Browser4 launcher)
        //   - Command-line paths containing "browser4" (catches renderer, GPU,
        //     and utility subprocesses that inherit the user-data-dir but not
        //     the PULSAR_CHROME variable).
        //
        // A single unfiltered query returns Name AND CommandLine for every
        // process in one WMI round-trip (~1s).  This replaces the former
        // `-Filter "Name = ..."` query PLUS a per-PID exhaustive sweep (which
        // together took tens of seconds) with one call that covers both.
        let ps_command = r#"
            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.Name -in @('chrome.exe','chromium.exe','msedge.exe') -and
                    -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
                    (
                        $_.CommandLine -match 'PULSAR_CHROME' -or
                        $_.CommandLine -match 'browser4[\\/]browser[\\/]chrome' -or
                        $_.CommandLine -match 'browser4-apps'
                    )
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

/// Exhaustive fallback sweep: enumerate every chrome/chromium/msedge process
/// and match their command lines against Browser4 markers.
///
/// A single unfiltered `Get-CimInstance Win32_Process` returns Name AND
/// CommandLine for all processes in one WMI round-trip (~1s for hundreds of
/// processes).  Querying each PID individually (`-Filter "ProcessId = $pid"`)
/// is O(N) WMI round-trips and was measured at ~52s for 55 browser processes,
/// so we avoid that path entirely.
///
/// This is now equivalent to `find_pulsar_browser_processes` (both do a single
/// unfiltered query), and the production paths use that function directly.
/// Kept for tests and as an explicit named fallback.
#[cfg(windows)]
#[allow(dead_code)]
fn find_browser4_chrome_processes_exhaustive() -> Vec<u32> {
    use std::process::Command;

    let ps_command = r#"
        Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -in @('chrome.exe','chromium.exe','msedge.exe') -and
                -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
                (
                    $_.CommandLine -match 'PULSAR_CHROME' -or
                    $_.CommandLine -match 'browser4[\\/]browser[\\/]chrome' -or
                    $_.CommandLine -match 'browser4-apps'
                )
            } |
            Select-Object -ExpandProperty ProcessId
    "#;

    let mut pids: Vec<u32> = match Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", ps_command])
        .output()
    {
        Ok(out) => parse_pid_list(&out.stdout),
        Err(_) => Vec::new(),
    };

    pids.sort_unstable();
    pids.dedup();
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
            Some(expand_windows_argfile(command_line))
        }
    }
}

/// Java launchers can move the whole JVM command line into an `@argfile`
/// (JDK 9+ launcher feature) to stay under the Windows CreateProcess 32,767
/// character limit.  `Win32_Process.CommandLine` then only exposes the
/// `@path` token, so marker-based process matching (e.g. the Browser4 main
/// class) would miss the process entirely.  Append the referenced file's
/// tokens to the command line so downstream matching sees the real arguments.
// Only used in production on Windows (see `process_command_line`), but its
// unit tests run on every platform, so keep it compiled everywhere.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
fn expand_windows_argfile(command_line: String) -> String {
    let mut expanded = String::new();
    for raw_token in command_line.split_whitespace() {
        let token = raw_token.trim_matches('"');
        if let Some(argfile) = token.strip_prefix('@') {
            if let Ok(content) = fs::read_to_string(argfile) {
                for line in content.lines() {
                    let line = line.trim();
                    if !line.is_empty() {
                        expanded.push_str(line);
                        expanded.push(' ');
                    }
                }
                continue;
            }
        }
        expanded.push_str(raw_token);
        expanded.push(' ');
    }
    expanded.trim_end().to_string()
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

#[cfg_attr(not(windows), allow(dead_code))]
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

/// Snapshot all running PIDs in a single `tasklist` invocation.
///
/// `wait_for_exit` launches a fresh `tasklist /FI "PID eq ..."` process per PID
/// per poll, which made an 8-browser kill spend seconds just in `tasklist`
/// startup.  One full snapshot + in-memory lookup collapses that to a single
/// `tasklist` call per poll cycle.
#[cfg(windows)]
fn running_pids_snapshot() -> std::collections::HashSet<u32> {
    use std::process::Command;
    let mut set = std::collections::HashSet::new();
    if let Ok(output) = Command::new("tasklist").args(["/FO", "CSV", "/NH"]).output() {
        let stdout = String::from_utf8_lossy(&output.stdout);
        for line in stdout.lines() {
            // Format: "image.exe","1234","Console","1","123,456 K"
            let mut parts = line.split(',');
            if let Some(pid_part) = parts.nth(1) {
                if let Ok(pid) = pid_part.trim().trim_matches('"').parse::<u32>() {
                    set.insert(pid);
                }
            }
        }
    }
    set
}

/// Wait until all the given PIDs have exited, polling a single full-process
/// snapshot each cycle instead of one `tasklist` per PID.
fn wait_for_exit_all(pids: &[u32], timeout_ms: u64, poll_interval_ms: u64) {
    if pids.is_empty() {
        return;
    }
    #[cfg(windows)]
    {
        let start = std::time::Instant::now();
        let timeout = std::time::Duration::from_millis(timeout_ms);
        let poll = std::time::Duration::from_millis(poll_interval_ms);
        while start.elapsed() < timeout {
            let running = running_pids_snapshot();
            if !pids.iter().any(|p| running.contains(p)) {
                return;
            }
            std::thread::sleep(poll);
        }
    }
    #[cfg(not(windows))]
    {
        for pid in pids {
            let _ = wait_for_exit(*pid, timeout_ms, poll_interval_ms);
        }
    }
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
    fn test_browser_attribution_by_server_pid_is_empty_without_server_pids() {
        // Attribution is the whole safety property of the development `stop`:
        // with no server pid there is nothing this workspace can claim, so the
        // scoped sweep must be a no-op instead of falling back to the global
        // browser scan (which would kill a neighbouring checkout's browser).
        assert!(find_browser_roots_launched_by(&[]).is_empty());

        let kill = kill_browsers_launched_by(&[]);
        assert!(kill.killed_pids.is_empty());
        assert!(kill.remaining_pids.is_empty());
    }

    #[test]
    fn test_expand_windows_argfile_reads_java_argfile() {
        let tmp = test_temp_dir();
        let argfile = tmp.path().join("java-args.txt");
        fs::write(
            &argfile,
            "-cp\nC:\\browser4\\lib\\*\nai.platon.pulsar.chrome.Browser4BundleApplicationKt\n--server.port=15411\n",
        )
        .unwrap();

        let command_line = format!("\"C:\\java\\bin\\java.exe\" @{}", argfile.display());
        let expanded = expand_windows_argfile(command_line);

        assert!(
            expanded.contains("Browser4BundleApplicationKt"),
            "main class must be visible after argfile expansion: {expanded}"
        );
        assert!(expanded.contains("-cp"));
        assert!(expanded.contains("--server.port=15411"));
        assert!(expanded.starts_with("\"C:\\java\\bin\\java.exe\""));
    }

    #[test]
    fn test_expand_windows_argfile_ignores_missing_argfile() {
        let command_line = r#""C:\java\bin\java.exe" @C:\missing\java-args.txt"#.to_string();
        let expanded = expand_windows_argfile(command_line);
        assert!(expanded.contains("@C:\\missing\\java-args.txt"));
        assert!(!expanded.contains("Browser4BundleApplicationKt"));
    }

    /// The port sweep may only ever hand *JVM* processes to the killer.
    ///
    /// Development-mode `stop` sweeps the port of the resolved `--server` URL,
    /// and that port can belong to any local service — it used to be the e2e
    /// harness, whose Rust mock backend listens on exactly such a port, and
    /// `stop` killed the test run instead of a server.
    #[test]
    fn test_port_sweep_skips_non_jvm_listeners() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();

        // This test process owns the listening socket and is not a JVM, so the
        // sweep must not produce a kill target for it.
        let candidates = jvm_pids_listening_on_ports(&[port]);
        assert!(
            candidates.is_empty(),
            "a non-JVM listener on port {port} must never be a kill target: {candidates:?}"
        );
        assert!(!is_java_process(std::process::id()));
        assert!(!is_browser4_server_process(std::process::id()));

        // And the guard is not vacuous: when the OS port scan is available it
        // does see this listener, it is simply filtered out afterwards.
        let unverified = find_java_pids_listening_on_ports(&[port]);
        if !unverified.is_empty() {
            assert!(
                unverified.contains(&std::process::id()),
                "the port scan should attribute port {port} to this process: {unverified:?}"
            );
        }
    }

    /// A backend launched as `java @argfile` loses every Browser4 marker from
    /// its command line when the launcher removes the argfile, so the port the
    /// registry recorded is the only identity left.  A pid that still owns that
    /// port must count as live; treating it as a stale/reused pid made
    /// `kill-all` delete the entry and leave the backend running.
    #[test]
    fn test_registered_server_process_is_live_when_it_owns_the_recorded_port() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let pid = std::process::id();

        // This test process is not a JVM, so the command-line marker check can
        // never be what makes the positive assertion below pass.
        assert!(!is_browser4_server_process(pid));

        // The OS port scan is not available on every image (it needs netstat
        // and, on unix, permission to see other pids).  Only assert the
        // positive case when the scan can attribute the port at all.
        if find_java_pids_listening_on_ports(&[port]).contains(&pid) {
            assert!(pid_owns_port(pid, port));
            assert!(is_registered_browser4_server_process(pid, port));
        }

        // A port this process does not own must not make a foreign pid live.
        let other = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let other_port = other.local_addr().unwrap().port();
        assert!(!is_registered_browser4_server_process(u32::MAX - 1, other_port));
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
            version: Some("v4.13.5".to_string()),
            workspace_root: None,
        };

        register_managed_server_process(proc.clone(), Some(&reg_path));
        let procs = read_managed_server_processes(Some(&reg_path));
        assert_eq!(procs.len(), 1);
        assert_eq!(procs[0].pid, 12345);
        assert_eq!(procs[0].version.as_deref(), Some("v4.13.5"));
        assert_eq!(
            recorded_server_version(8182, Some(&reg_path)).as_deref(),
            Some("v4.13.5")
        );

        remove_managed_server_process(12345, Some(&reg_path));
        let procs = read_managed_server_processes(Some(&reg_path));
        assert!(procs.is_empty());
    }

    #[test]
    fn test_recorded_server_version_roundtrip_and_fallback() {
        let tmp = test_temp_dir();
        let reg_path = tmp.path().join("reg.json");

        // Entries without a version field (older CLI) deserialize as None.
        let legacy = r#"{"processes":[{"pid":111,"baseUrl":"http://localhost:8182","port":8182,"jarPath":"/j","startedAt":"2026-01-01T00:00:00Z"}]}"#;
        std::fs::write(&reg_path, legacy).unwrap();
        assert_eq!(recorded_server_version(8182, Some(&reg_path)), None);
        assert_eq!(recorded_server_version(8282, Some(&reg_path)), None);

        // Re-registering on the same port appends; the newest entry wins.
        for version in ["v4.13.4", "local"] {
            register_managed_server_process(
                ManagedServerProcess {
                    pid: 222,
                    base_url: "http://localhost:8182".to_string(),
                    port: 8182,
                    jar_path: "/j".to_string(),
                    started_at: "2026-01-01T00:00:00Z".to_string(),
                    version: Some(version.to_string()),
                    workspace_root: None,
                },
                Some(&reg_path),
            );
        }
        assert_eq!(
            recorded_server_version(8182, Some(&reg_path)).as_deref(),
            Some("local")
        );
        assert_eq!(recorded_server_version(9999, Some(&reg_path)), None);
    }

    #[test]
    fn test_shutdown_on_port_only_touches_matching_entries() {
        let tmp = test_temp_dir();
        let reg_path = tmp.path().join("reg.json");

        // Two stale (non-running) pids on different ports.
        for (pid, port) in [(99901u32, 8182u16), (99902, 8282)] {
            register_managed_server_process(
                ManagedServerProcess {
                    pid,
                    base_url: format!("http://localhost:{port}"),
                    port,
                    jar_path: "/path/to/Browser4.jar".to_string(),
                    started_at: "2026-01-01T00:00:00Z".to_string(),
                    version: Some("local".to_string()),
                    workspace_root: None,
                },
                Some(&reg_path),
            );
        }

        let result =
            shutdown_managed_server_processes_on_port(false, Some(&reg_path), 8182, 50, 10);

        // The stale pid on port 8182 is reported missing (not running);
        // nothing was actually killed.
        assert_eq!(result.missing_pids, vec![99901]);
        assert!(result.stopped_pids.is_empty());
        assert!(result.remaining_pids.is_empty());

        // The entry for port 8282 stays in the registry untouched.
        let procs = read_managed_server_processes(Some(&reg_path));
        assert_eq!(procs.len(), 1);
        assert_eq!(procs[0].port, 8282);
        assert_eq!(procs[0].pid, 99902);
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
            version: None,
            workspace_root: None,
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

    /// The batched snapshot may only select real Browser4 backends: a plain
    /// `java -cp app.jar Main` must never become a kill target.
    #[test]
    fn test_parse_java_process_snapshot_keeps_only_browser4_servers() {
        let json = r#"[
            {"ProcessId": 10, "CommandLine": "\"C:/Java/bin/java.exe\" -jar D:/browser4/Browser4.jar --server.port=8182"},
            {"ProcessId": 11, "CommandLine": "\"C:/Java/bin/java.exe\" -cp app.jar com.example.Main"},
            {"ProcessId": 12, "CommandLine": null},
            {"ProcessId": 13, "CommandLine": "\"D:/runtime/bin/java.exe\" -cp D:/lib/* ai.platon.pulsar.apps.Browser4BundleApplicationKt"}
        ]"#;

        assert_eq!(parse_java_process_snapshot(json), vec![10, 13]);
    }

    /// `ConvertTo-Json` emits a bare object when exactly one process matches.
    #[test]
    fn test_parse_java_process_snapshot_accepts_single_object() {
        let json = r#"{"ProcessId": 42, "CommandLine": "java -jar C:/apps/Browser4.jar"}"#;
        assert_eq!(parse_java_process_snapshot(json), vec![42]);
    }

    #[test]
    fn test_parse_java_process_snapshot_rejects_garbage() {
        assert!(parse_java_process_snapshot("").is_empty());
        assert!(parse_java_process_snapshot("   \r\n").is_empty());
        assert!(parse_java_process_snapshot("not json").is_empty());
        assert!(parse_java_process_snapshot("7").is_empty());
        assert!(parse_java_process_snapshot("[null]").is_empty());
    }

    /// A bundle server is launched as `java @argfile`; the argfile is deleted
    /// once the JVM is up, so matching has to read it while it still exists.
    #[test]
    fn test_parse_java_process_snapshot_expands_argfile() {
        let tmp = test_temp_dir();
        let argfile = tmp.path().join("java-args.txt");
        fs::write(
            &argfile,
            "-cp\nC:\\browser4\\lib\\*\nai.platon.pulsar.apps.Browser4BundleApplicationKt\n",
        )
        .unwrap();
        let command_line = format!("\"C:\\\\Java\\\\bin\\\\java.exe\" @{}", argfile.display());
        let json = format!(
            r#"[{{"ProcessId": 99, "CommandLine": {}}}]"#,
            serde_json::to_string(&command_line).unwrap()
        );

        assert_eq!(parse_java_process_snapshot(&json), vec![99]);
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
        let standalone = r#""C:/Java/bin/java.exe" -cp D:/workspace/target/classes ai.platon.pulsar.apps.Browser4StandaloneApplicationKt"#;

        assert!(command_line_matches_browser4_server(jar));
        assert!(command_line_matches_browser4_server(launcher));
        assert!(command_line_matches_browser4_server(bundle));
        assert!(command_line_matches_browser4_server(standalone));
    }

    #[test]
    fn test_command_line_matches_browser4_server_via_bundle_jre_path_when_argfile_deleted() {
        // After a successful launch the daemon deletes the java `@argfile`,
        // so the main class is gone from the command line.  Only the bundled
        // JRE path identifies the process as a Browser4 server.
        let dev_bundle = r#""D:/workspace/Browser4/Browser4-4.14-feat/browser4-apps/browser4-bundle/target/runtime-bundle/_work/browser4-bundle-runtime-windows-x64/browser4-bundle-runtime-windows-x64/runtime/bin/java.exe" @D:/Users/tester/AppData/Local/Temp/browser4-argfile-123-456/java-args.txt"#;
        let installed_runtime = r#""C:/Users/tester/AppData/Roaming/browser4/runtime/v4.13.7/runtime/bin/java.exe" @C:/Users/tester/AppData/Local/Temp/browser4-argfile-123-456/java-args.txt"#;

        assert!(command_line_matches_browser4_server(dev_bundle));
        assert!(command_line_matches_browser4_server(installed_runtime));
    }

    #[test]
    fn test_command_line_matches_browser4_server_rejects_non_browser4_java() {
        let non_browser4 =
            r#""C:/Java/bin/java.exe" -jar D:/apps/another-service.jar --server.port=8080"#;
        let maven_daemon =
            r#""D:/Program Files/Java/graalvm-jdk-25.0.3+9.1/bin/java.exe" -cp D:/Users/tester/.m2/repository/org/jetbrains/kotlin/kotlin-build-tools-impl/2.2.21/kotlin-build-tools-impl-2.2.21.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon"#;
        let mock_site = r#""D:/Program Files/Java/graalvm-jdk-25.0.3+9.1/bin/java.exe" -XX:TieredStopAtLevel=1 -Dmock.site.port=18080 -cp @D:/Users/tester/AppData/Local/Temp/spring-boot-123.argfile ai.platon.pulsar.test.server.MockSiteBootKt"#;

        assert!(!command_line_matches_browser4_server(non_browser4));
        assert!(!command_line_matches_browser4_server(maven_daemon));
        assert!(!command_line_matches_browser4_server(mock_site));
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
                version: None,
                workspace_root: None,
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
        // When there is no process activity (all results empty), step 5
        // sleep is skipped — so "sleep" must NOT appear in the event list.
        assert_eq!(
            events.lock().unwrap().as_slice(),
            ["notify", "browser-kill", "shutdown", "browser-kill",]
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
    fn test_local_address_matches_any_port() {
        let ports = [8182u16, 18182];
        assert!(local_address_matches_any_port(Some("0.0.0.0:8182"), &ports));
        assert!(local_address_matches_any_port(Some("127.0.0.1:18182"), &ports));
        assert!(local_address_matches_any_port(Some("[::]:8182"), &ports));
        assert!(!local_address_matches_any_port(Some("0.0.0.0:81820"), &ports));
        assert!(!local_address_matches_any_port(Some("0.0.0.0:80"), &ports));
        assert!(!local_address_matches_any_port(None, &ports));
        assert!(!local_address_matches_any_port(Some("not-an-address"), &ports));
    }

    #[test]
    fn test_managed_server_ports_for_sweep_includes_default() {
        let ports = managed_server_ports_for_sweep();
        assert!(ports.contains(&8182), "default port must always be swept: {ports:?}");
        assert!(
            ports.contains(&18182),
            "bundle default port 18182 must always be swept: {ports:?}"
        );
    }

    #[test]
    fn test_merge_shutdown_with_fallback_server_kill_merges_and_deduplicates() {
        let mut shutdown = ShutdownResult {
            stopped_pids: vec![1001],
            missing_pids: vec![],
            forced_pids: vec![1001],
            remaining_pids: vec![2001],
            fallback_killed_server_pids: vec![],
            graceful_attempted: false,
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
            graceful_attempted: false,
        };
        let fallback = ServerKillResult {
            killed_pids: vec![5002],
            remaining_pids: vec![5003],
        };

        merge_shutdown_with_fallback_server_kill(&mut shutdown, &fallback);

        assert_eq!(shutdown.remaining_pids, vec![5001, 5003]);
    }

    // -----------------------------------------------------------------------
    // forced_after_graceful_timeout — the message must match what happened
    // -----------------------------------------------------------------------

    #[test]
    fn test_forced_stop_without_grace_does_not_claim_a_graceful_timeout() {
        // `stop` and `kill-all` force every pid immediately (force = true), so
        // the pids land in forced_pids without a grace window ever running.
        // Reporting "after graceful timeout" for them was the bug.
        let forced_only = ShutdownResult {
            stopped_pids: vec![2101],
            forced_pids: vec![2101],
            ..Default::default()
        };
        assert!(
            !forced_only.forced_after_graceful_timeout(),
            "a force-by-design stop must not claim a graceful timeout"
        );
    }

    #[test]
    fn test_forced_after_graceful_timeout_is_reported_only_for_escalation() {
        let escalated = ShutdownResult {
            stopped_pids: vec![2201],
            forced_pids: vec![2201],
            graceful_attempted: true,
            ..Default::default()
        };
        assert!(
            escalated.forced_after_graceful_timeout(),
            "a force kill after a real grace window must be reported"
        );

        let graceful_ok = ShutdownResult {
            stopped_pids: vec![2301],
            graceful_attempted: true,
            ..Default::default()
        };
        assert!(
            !graceful_ok.forced_after_graceful_timeout(),
            "a graceful stop that finished inside the window reports nothing"
        );
    }

    // -----------------------------------------------------------------------
    // Tests for bulk_force_stop_browser_processes
    // -----------------------------------------------------------------------

    #[test]
    fn test_bulk_force_stop_empty_vec_is_noop() {
        // Must not panic and must not spawn any subprocess that errors.
        bulk_force_stop_browser_processes(&[]);
    }

    #[test]
    fn test_bulk_force_stop_with_pids_does_not_panic() {
        // Use a PID that almost certainly doesn't exist so taskkill fails
        // gracefully (the function swallows both success and failure).
        let pids = vec![9999999];
        bulk_force_stop_browser_processes(&pids);
    }

    // -----------------------------------------------------------------------
    // Tests for browser-process detection
    // -----------------------------------------------------------------------

    #[test]
    fn test_find_unique_returns_vec_not_panics() {
        // Smoke test: must return a Vec<u32> without panicking, even when
        // Browser4 Chrome processes happen to be running on the machine.
        let pids = find_unique_pulsar_browser_processes();
        // Just verify the type — we can't assert emptiness because real
        // PULSAR_CHROME processes may be present.
        let _: Vec<u32> = pids;
    }

    #[test]
    fn test_find_pulsar_browser_processes_returns_vec_not_panics() {
        let pids = find_pulsar_browser_processes();
        // Verifies it runs without error.  May return PIDs when real
        // Browser4 Chrome processes are present — that's expected.
        let _: Vec<u32> = pids;
    }

    #[test]
    #[cfg(windows)]
    fn test_find_browser4_chrome_exhaustive_returns_vec_not_panics() {
        // Smoke test: must return a Vec<u32> without panicking.
        let pids = find_browser4_chrome_processes_exhaustive();
        let _: Vec<u32> = pids; // real PULSAR_CHROME processes may be present
    }

    /// Helper: verify a PowerShell command string is syntactically valid by
    /// asking PowerShell to parse it into a script block.
    ///
    /// NOTE: this only checks PowerShell syntax, not WQL correctness.
    /// `Get-CimInstance -Filter` uses WQL operators (OR / AND / NOT, no
    /// dashes); PowerShell-style -or / -and / -not silently return 0 rows.
    #[cfg(windows)]
    fn assert_powershell_syntax_valid(label: &str, command: &str) {
        use std::process::Command;
        // Escape single quotes in the command so we can wrap it in ''
        let escaped = command.replace('\'', "''");
        // [ScriptBlock]::Create('...') throws a parse error for invalid syntax.
        let wrapper = format!("$null = [ScriptBlock]::Create('{escaped}')",);
        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &wrapper])
            .output()
            .expect("Failed to spawn PowerShell for syntax check");
        assert!(
            output.status.success(),
            "PowerShell {label} command has syntax errors:\n{stderr}",
            stderr = String::from_utf8_lossy(&output.stderr)
        );
    }

    #[test]
    #[cfg(windows)]
    fn test_powershell_pulsar_detection_command_is_syntactically_valid() {
        let ps_command = r#"
            Get-CimInstance Win32_Process -Filter "Name = 'chrome.exe' OR Name = 'chromium.exe' OR Name = 'msedge.exe'" -ErrorAction SilentlyContinue |
                Where-Object {
                    -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
                    (
                        $_.CommandLine -match 'PULSAR_CHROME' -or
                        $_.CommandLine -match 'browser4[\\/]browser[\\/]chrome' -or
                        $_.CommandLine -match 'browser4-apps'
                    )
                } |
                Select-Object -ExpandProperty ProcessId
        "#;
        assert_powershell_syntax_valid("pulsar-detection", ps_command);
    }

    #[test]
    #[cfg(windows)]
    fn test_powershell_exhaustive_detection_command_is_syntactically_valid() {
        let ps_command = r#"
            $pids = Get-Process -Name chrome, chromium, msedge -ErrorAction SilentlyContinue |
                    Select-Object -ExpandProperty Id
            foreach ($pid in $pids) {
                $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId = $pid" -ErrorAction SilentlyContinue).CommandLine
                if ($cmd -and (
                    $cmd -match 'PULSAR_CHROME' -or
                    $cmd -match 'browser4[\\/]browser[\\/]chrome' -or
                    $cmd -match 'browser4-apps'
                )) {
                    $pid
                }
            }
        "#;
        assert_powershell_syntax_valid("exhaustive-detection", ps_command);
    }

    #[test]
    #[cfg(windows)]
    fn test_powershell_java_process_snapshot_command_is_syntactically_valid() {
        // The JVM sweep now relies on a single snapshot query; a syntax error
        // here would silently kill nothing, so validate the exact command.
        assert_powershell_syntax_valid("java-process-snapshot", WINDOWS_JAVA_PROCESS_SNAPSHOT_PS);
    }

    // -----------------------------------------------------------------------
    // Ownership scoping (issue #640: a sweep killed a neighbouring checkout's
    // backend).  The command lines below are the shapes observed on a developer
    // machine running two checkouts side by side.
    // -----------------------------------------------------------------------

    const OUR_CHECKOUT: &str = r"D:\workspace\Browser4\Browser4-4.16";
    const NEIGHBOUR_CHECKOUT: &str = r"D:\workspace\Browser4\Browser4-4.16-feat";

    fn dev_bundle_backend_command_line(checkout: &str) -> String {
        format!(
            r#""{checkout}\browser4-apps\browser4-bundle\target\runtime-bundle\_work\browser4-bundle-runtime-windows-x64\browser4-bundle-runtime-windows-x64\runtime\bin\java.exe" @C:\Users\pereg\AppData\Local\Temp\browser4-argfile-51512-1794592147164795500\java-args.txt"#
        )
    }

    #[test]
    fn test_command_line_mentions_path_rejects_a_sibling_checkout() {
        // `Browser4-4.16` is a *prefix* of `Browser4-4.16-feat`: the naive
        // substring test reads the neighbour's backend as ours, which is how a
        // pre-sweep in one checkout killed the other checkout's backend.
        let neighbour = dev_bundle_backend_command_line(NEIGHBOUR_CHECKOUT);
        assert!(!command_line_mentions_path(
            &neighbour,
            Path::new(OUR_CHECKOUT)
        ));
        assert!(command_line_mentions_path(
            &neighbour,
            Path::new(NEIGHBOUR_CHECKOUT)
        ));
        // Same trap for a directory that merely starts with the name.
        assert!(!command_line_mentions_path(
            "java -cp D:/workspace/app-extra/lib/a.jar Main",
            Path::new("D:/workspace/app")
        ));
        assert!(!command_line_mentions_path(
            "java -cp D:/workspace/application/lib/a.jar Main",
            Path::new("D:/workspace/app")
        ));
    }

    #[test]
    fn test_command_line_mentions_path_normalizes_separators_and_quotes() {
        let root = Path::new(OUR_CHECKOUT);

        // The launcher writes forward slashes into the JVM options, the JRE
        // path uses backslashes.
        assert!(command_line_mentions_path(
            r#"java -Dbrowser4.agent.workspace=D:\workspace\Browser4\Browser4-4.16 -cp lib Browser4LauncherKt"#,
            root
        ));
        assert!(command_line_mentions_path(
            r#"java "-Dbrowser4.agent.workspace=D:/workspace/Browser4/Browser4-4.16" -cp lib Browser4LauncherKt"#,
            root
        ));
        // Nested paths (the checkout's own runtime bundle) still match.
        assert!(command_line_mentions_path(
            &dev_bundle_backend_command_line(OUR_CHECKOUT),
            root
        ));
        // A trailing separator on the root must not break the match.
        assert!(command_line_mentions_path(
            r#"java -cp D:\workspace\Browser4\Browser4-4.16\target\classes Main"#,
            Path::new(r"D:\workspace\Browser4\Browser4-4.16\")
        ));
        // Unrelated paths do not.
        assert!(!command_line_mentions_path(
            r#"java -jar C:\apps\Browser4.jar"#,
            root
        ));
        assert!(!command_line_mentions_path("", root));
    }

    #[test]
    fn test_is_workspace_owned_browser4_server_distinguishes_the_two_checkouts() {
        let our_root = Path::new(OUR_CHECKOUT);

        // Our own backend (bundle JRE inside the checkout, classpath moved into
        // an already-deleted argfile) still carries the checkout root.
        assert!(is_workspace_owned_browser4_server(
            &dev_bundle_backend_command_line(OUR_CHECKOUT),
            our_root
        ));
        // The neighbouring checkout's backend does not.
        assert!(!is_workspace_owned_browser4_server(
            &dev_bundle_backend_command_line(NEIGHBOUR_CHECKOUT),
            our_root
        ));
        // An installed runtime reports no checkout at all.
        assert!(!is_workspace_owned_browser4_server(
            r#""C:\Users\pereg\.browser4\runtime\v4.16.7\runtime\bin\java.exe" @C:\Temp\args.txt"#,
            our_root
        ));
    }

    #[test]
    fn test_is_workspace_owned_browser4_server_ignores_non_backend_jvms() {
        let our_root = Path::new(OUR_CHECKOUT);

        // A Maven/Surefire fork from this very checkout is *not* a backend: it
        // matches ownership but no Browser4 marker, and killing it would abort
        // a test run in progress.
        let surefire_fork = format!(
            r#""C:\Program Files\Java\bin\java.exe" -cp {OUR_CHECKOUT}\browser4-rest\target\classes;{OUR_CHECKOUT}\browser4-rest\target\test-classes org.junit.runner.JUnitCore"#
        );
        assert!(!is_workspace_owned_browser4_server(
            &surefire_fork,
            our_root
        ));

        // A Kotlin compile daemon started from the checkout's shell carries the
        // same root neither a Browser4 marker nor ownership.
        assert!(!is_workspace_owned_browser4_server(
            r#""C:\Program Files\Java\bin\java" -cp C:\Users\pereg\.m2\repository\kotlin-daemon.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon"#,
            our_root
        ));
    }

    #[test]
    fn test_owned_server_candidates_attributes_both_ownership_paths() {
        let command_lines = vec![
            (100u32, dev_bundle_backend_command_line(OUR_CHECKOUT)),
            (200, dev_bundle_backend_command_line(NEIGHBOUR_CHECKOUT)),
        ];

        let candidates =
            owned_server_candidates_from(&command_lines, Path::new(OUR_CHECKOUT), &[(100, 8282)]);

        assert_eq!(candidates.len(), 1, "candidates: {candidates:?}");
        assert_eq!(candidates[0].pid, 100);
        assert_eq!(candidates[0].source, "checkout");
    }

    #[test]
    fn test_owned_server_candidates_leaves_a_foreign_listener_on_a_reused_port() {
        // Our registry recorded 8282, but our backend died and a neighbouring
        // checkout took the port over (the exact situation the incident
        // reported: the sweep followed a stale port straight into the
        // neighbour's backend).
        let command_lines = vec![(200u32, dev_bundle_backend_command_line(NEIGHBOUR_CHECKOUT))];

        let candidates =
            owned_server_candidates_from(&command_lines, Path::new(OUR_CHECKOUT), &[(200, 8282)]);

        assert!(
            candidates.is_empty(),
            "a port recorded by this workspace must not make another checkout's \
             backend a candidate: {candidates:?}"
        );
    }

    #[test]
    fn test_owned_server_candidates_records_the_port_of_a_ported_backend() {
        // A backend whose command line is only recognized through the port we
        // recorded still has to pass the ownership check, and the audit trail
        // must name the port and the command line.
        let own = dev_bundle_backend_command_line(OUR_CHECKOUT);
        let command_lines = vec![(100u32, own.clone())];

        let candidates =
            owned_server_candidates_from(&command_lines, Path::new(OUR_CHECKOUT), &[(100, 8282)]);

        assert_eq!(candidates.len(), 1, "candidates: {candidates:?}");
        assert_eq!(candidates[0].source, "checkout");
        assert_eq!(candidates[0].command_line, own);

        // A pid that is not in the JVM table cannot be attributed at all.
        let unattributable =
            owned_server_candidates_from(&[], Path::new(OUR_CHECKOUT), &[(300, 8282)]);
        assert!(unattributable.is_empty(), "{unattributable:?}");
    }

    #[test]
    fn test_sweep_all_workspaces_requires_an_explicit_opt_in() {
        for enabled in ["1", "true", "TRUE", "yes", "on", " on "] {
            assert!(
                sweep_all_workspaces_enabled_from(Some(enabled)),
                "{enabled:?} should enable the machine-wide sweep"
            );
        }
        for disabled in [
            None,
            Some(""),
            Some("0"),
            Some("false"),
            Some("y"),
            Some("2"),
        ] {
            assert!(
                !sweep_all_workspaces_enabled_from(disabled),
                "{disabled:?} must keep the sweep scoped to this checkout"
            );
        }
    }

    #[test]
    fn test_notify_base_url_candidates_skips_a_stale_state_url() {
        let registered = vec![ManagedServerProcess {
            pid: 100,
            base_url: "http://localhost:8282/".to_string(),
            port: 8282,
            jar_path: "lib".to_string(),
            started_at: "2026-01-01T00:00:00Z".to_string(),
            version: None,
            workspace_root: None,
        }];

        // No persisted state at all: the default record names the production
        // port, which this workspace never chose — not a notify target.
        assert_eq!(
            notify_base_url_candidates(&registered, None),
            vec!["http://localhost:8282/".to_string()]
        );

        let mut state = crate::state::CliState::default();
        state.base_url = "http://localhost:8283".to_string();
        state.stale = false;
        assert_eq!(
            notify_base_url_candidates(&registered, Some(&state)),
            vec![
                "http://localhost:8282/".to_string(),
                "http://localhost:8283".to_string()
            ]
        );

        // `stop` marked the record stale: the port it remembers may now belong
        // to a neighbouring checkout, so it must not receive close_all_sessions.
        state.stale = true;
        assert_eq!(
            notify_base_url_candidates(&registered, Some(&state)),
            vec!["http://localhost:8282/".to_string()]
        );
    }

    #[test]
    fn test_truncate_for_log_keeps_short_values_and_char_boundaries() {
        assert_eq!(truncate_for_log("short", 10), "short");
        let long = "é".repeat(5);
        let truncated = truncate_for_log(&long, 3);
        assert_eq!(truncated.chars().count(), 4, "{truncated:?}");
        assert!(truncated.starts_with("ééé"), "{truncated:?}");
    }

    #[test]
    #[cfg(windows)]
    fn test_orphan_browser_scan_script_is_syntactically_valid() {
        // A syntax error in the scan would silently reclaim nothing, which is exactly
        // the leak it exists to stop (169 orphaned browsers after one afternoon).
        assert_powershell_syntax_valid("orphan-browser-scan", &orphan_browser_scan_script());
    }

    #[test]
    #[cfg(windows)]
    fn test_orphan_browser_scan_script_keeps_its_markers() {
        let script = orphan_browser_scan_script();

        // The profile root every Browser4 browser carries.
        assert!(
            script.contains(BROWSER_TEMP_ROOT_MARKER),
            "the scan must match Browser4's temp root: {script}"
        );
        // Orphan detection is a parent check; without it the scan would kill a live
        // backend's browsers, and treating a browser parent as the owner would kill
        // renderers individually instead of letting the root's /T take them.
        assert!(script.contains("ParentProcessId"), "{script}");
        assert!(script.contains("java.exe"), "{script}");
    }

    #[test]
    fn test_orphan_browser_roots_scan_does_not_panic() {
        // Smoke test: the scan shells out to the OS and must degrade to an empty
        // list (never a panic) when the query fails or there is nothing to find.
        let _: Vec<u32> = find_orphaned_browser_roots();
    }

    #[test]
    fn test_browser_kill_result_default_is_empty() {
        let result = BrowserKillResult::default();
        assert!(result.killed_pids.is_empty());
        assert!(result.remaining_pids.is_empty());
    }
}
