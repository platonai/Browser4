//! Persistent state management for the Browser4 CLI.
//!
//! CLI session state is stored under `~/.browser4` by default. The unnamed
//! session uses `cli-state.json`, while named sessions use `sessions/<name>.json`.
//!
//! The Browser4 runtime bundle (JRE, JARs, launchers) lives separately in a
//! platform-conventional data directory (`~/.local/share/browser4/` on Linux,
//! `~/Library/Application Support/browser4/` on macOS, `%APPDATA%/browser4/`
//! on Windows).  See [`resolve_runtime_data_dir`].

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MousePosition {
    pub x: f64,
    pub y: f64,
}

/// Persistent CLI state stored on disk.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CliState {
    /// Active session ID returned by the server on `open`.
    #[serde(rename = "sessionId", skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    /// Base URL of the Browser4 REST server.
    #[serde(rename = "baseUrl")]
    pub base_url: String,
    /// Reserved selector slot for future CLI workflows.
    #[serde(rename = "activeSelector", skip_serializing_if = "Option::is_none")]
    pub active_selector: Option<String>,
    /// Named session label for the `-s=<name>` flag.
    #[serde(rename = "sessionName", skip_serializing_if = "Option::is_none")]
    pub session_name: Option<String>,
    /// Last known mouse position used to restore pointer state across CLI invocations.
    #[serde(rename = "lastMousePosition", skip_serializing_if = "Option::is_none")]
    pub last_mouse_position: Option<MousePosition>,
    /// Whether this session was created via `attach` (external browser) rather
    /// than `open` (Browser4-launched).  Attached sessions leave the browser
    /// running after `close`.
    #[serde(rename = "isAttached", default, skip_serializing_if = "is_false")]
    pub is_attached: bool,
}

fn is_false(b: &bool) -> bool {
    !b
}

impl Default for CliState {
    fn default() -> Self {
        Self {
            session_id: None,
            base_url: "http://localhost:8182".to_string(),
            active_selector: None,
            session_name: None,
            last_mouse_position: None,
            is_attached: false,
        }
    }
}

/// Resolve the default state directory, honouring `BROWSER4_CLI_STATE_DIR`.
pub fn resolve_default_state_dir() -> PathBuf {
    if let Ok(override_dir) = std::env::var("BROWSER4_CLI_STATE_DIR") {
        let trimmed = override_dir.trim().to_string();
        if !trimmed.is_empty() {
            // Reject values that look like CLI flags (e.g. "--help", "-v").
            // These are almost certainly misconfigurations and would create
            // confusing directory names on disk.
            if trimmed.starts_with('-') {
                eprintln!(
                    "browser4-cli: ignoring BROWSER4_CLI_STATE_DIR=\"{}\" — \
                    directory names that start with '-' are not allowed. \
                    Using default state directory (~/.browser4) instead.",
                    trimmed
                );
            } else {
                return PathBuf::from(&trimmed)
                    .canonicalize()
                    .unwrap_or(PathBuf::from(trimmed));
            }
        }
    }
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".browser4")
}

/// Resolve the directory for Browser4 runtime data (JRE, JARs, launchers).
///
/// Uses a platform-conventional location separate from CLI session state:
/// - Linux:   `$XDG_DATA_HOME/browser4/`  (typically `~/.local/share/browser4/`)
/// - macOS:   `~/Library/Application Support/browser4/`
/// - Windows: `%APPDATA%/browser4/`
///
/// Honours `BROWSER4_RUNTIME_DIR` as an override.  When set and non-empty,
/// that path is used directly.
pub fn resolve_runtime_data_dir() -> PathBuf {
    if let Ok(override_dir) = std::env::var("BROWSER4_RUNTIME_DIR") {
        let trimmed = override_dir.trim().to_string();
        if !trimmed.is_empty() {
            return PathBuf::from(&trimmed)
                .canonicalize()
                .unwrap_or(PathBuf::from(trimmed));
        }
    }
    dirs::data_dir()
        .unwrap_or_else(|| dirs::home_dir().unwrap_or_else(|| PathBuf::from(".")))
        .join("browser4")
}

/// Resolve the directory for Browser4 download cache.
///
/// Uses the platform cache directory:
/// - Linux:   `$XDG_CACHE_HOME/browser4/`  (typically `~/.cache/browser4/`)
/// - macOS:   `~/Library/Caches/browser4/`
/// - Windows: `%LOCALAPPDATA%/browser4/`
///
/// Honours `BROWSER4_RUNTIME_DIR` as an override — when set, the cache lives
/// under `{BROWSER4_RUNTIME_DIR}/cache/` so everything stays together.
pub fn resolve_runtime_cache_dir() -> PathBuf {
    if let Ok(override_dir) = std::env::var("BROWSER4_RUNTIME_DIR") {
        let trimmed = override_dir.trim().to_string();
        if !trimmed.is_empty() {
            let base = PathBuf::from(&trimmed)
                .canonicalize()
                .unwrap_or(PathBuf::from(trimmed));
            return base.join("cache");
        }
    }
    dirs::cache_dir()
        .unwrap_or_else(|| {
            dirs::data_dir()
                .unwrap_or_else(|| dirs::home_dir().unwrap_or_else(|| PathBuf::from(".")))
        })
        .join("browser4")
}

fn state_file(state_dir: &Path, session_name: Option<&str>) -> PathBuf {
    match session_name {
        Some(name) => state_dir.join("sessions").join(format!("{}.json", name)),
        None => state_dir.join("cli-state.json"),
    }
}

/// Read the persisted CLI state from disk, falling back to defaults.
pub fn read_state(state_dir: Option<&Path>, session_name: Option<&str>) -> CliState {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    let path = state_file(&dir, session_name);
    match fs::read_to_string(&path) {
        Ok(raw) => serde_json::from_str::<CliState>(&raw).unwrap_or_default(),
        Err(_) => CliState::default(),
    }
}

/// Write the CLI state to disk, creating the directory if necessary.
pub fn write_state(
    state: &CliState,
    state_dir: Option<&Path>,
    session_name: Option<&str>,
) -> std::io::Result<()> {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);

    let path = state_file(&dir, session_name);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }

    let json = serde_json::to_string_pretty(state).expect("state serialization should not fail");
    fs::write(path, json)
}

/// Clear all persisted CLI state (called on `close`).
pub fn clear_state(state_dir: Option<&Path>, session_name: Option<&str>) {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    let path = state_file(&dir, session_name);
    let _ = fs::remove_file(path);
}

/// Clear the default CLI state plus all named session state files.
pub fn clear_all_state(state_dir: Option<&Path>) {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);

    clear_state(Some(&dir), None);

    let sessions_dir = dir.join("sessions");
    if !sessions_dir.exists() {
        return;
    }

    if let Ok(entries) = fs::read_dir(sessions_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().map_or(false, |ext| ext == "json") {
                let _ = fs::remove_file(path);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Loop state persistence
// ---------------------------------------------------------------------------

/// Persistent state for a running or paused `loop` command.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoopState {
    /// The task tokens (the command to execute each iteration).
    #[serde(rename = "taskTokens")]
    pub task_tokens: Vec<String>,
    /// Execution mode: "plain", "shell", or "subcommand".
    pub mode: String,
    /// Seconds between iterations.
    #[serde(rename = "intervalSecs")]
    pub interval_secs: u64,
    /// Maximum number of iterations (null = infinite).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub count: Option<u64>,
    /// Maximum total duration in seconds (null = no limit).
    #[serde(rename = "timeoutSecs", skip_serializing_if = "Option::is_none")]
    pub timeout_secs: Option<u64>,
    /// Iterations completed so far.
    #[serde(rename = "iterationsCompleted")]
    pub iterations_completed: u64,
    /// ISO-8601 timestamp when the loop was first started.
    #[serde(rename = "startedAt")]
    pub started_at: String,
    /// ISO-8601 timestamp of the last update.
    #[serde(rename = "updatedAt")]
    pub updated_at: String,
    /// Loop status: "running" or "stopped".
    pub status: String,
}

fn loop_state_file(state_dir: &Path, name: Option<&str>) -> PathBuf {
    match name {
        Some(n) if !n.is_empty() => {
            // Defense-in-depth: sanitize the name so path traversal via --name
            // is impossible even if the CLI-layer validation is bypassed.
            let safe: String = n
                .chars()
                .filter(|c| c.is_ascii_alphanumeric() || *c == '.' || *c == '-' || *c == '_')
                .take(64)
                .collect();
            state_dir.join("loops").join(format!("{}.json", safe))
        }
        _ => state_dir.join("loop-state.json"),
    }
}

/// Read the persisted loop state, if any.
pub fn read_loop_state(state_dir: Option<&Path>, name: Option<&str>) -> Option<LoopState> {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    let path = loop_state_file(&dir, name);
    match fs::read_to_string(&path) {
        Ok(raw) => serde_json::from_str::<LoopState>(&raw).ok(),
        Err(_) => None,
    }
}

/// Write the loop state to disk.
pub fn write_loop_state(
    state: &LoopState,
    state_dir: Option<&Path>,
    name: Option<&str>,
) -> std::io::Result<()> {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    fs::create_dir_all(&dir)?;
    let path = loop_state_file(&dir, name);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string_pretty(state).expect("loop state serialization should not fail");
    fs::write(path, json)
}

/// Return the full path to the loop state file (for display).
pub fn loop_state_path(state_dir: Option<&Path>, name: Option<&str>) -> PathBuf {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    loop_state_file(&dir, name)
}

/// Clear the persisted loop state.
pub fn clear_loop_state(state_dir: Option<&Path>, name: Option<&str>) {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    let _ = fs::remove_file(loop_state_file(&dir, name));
}

/// Update the status field of a specific loop's persisted state.
/// Returns the previous status if the loop existed.
pub fn set_loop_status(
    state_dir: Option<&Path>,
    name: Option<&str>,
    new_status: &str,
) -> Option<String> {
    read_loop_state(state_dir, name).map(|mut ls| {
        let prev = ls.status.clone();
        ls.status = new_status.to_string();
        ls.updated_at = chrono::Utc::now().to_rfc3339();
        let _ = write_loop_state(&ls, state_dir, name);
        prev
    })
}

/// Apply a status to all persisted loops whose current status matches
/// `from_status_filter`. Returns the count of updated loops.
pub fn set_all_loop_statuses_filtered(
    state_dir: Option<&Path>,
    from_status_filter: Option<&str>,
    new_status: &str,
) -> usize {
    let mut updated = 0usize;
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);

    // Default loop
    if let Some(mut ls) = read_loop_state(state_dir, None) {
        let match_filter = from_status_filter.map_or(true, |f| ls.status == f);
        if match_filter && ls.status != new_status {
            ls.status = new_status.to_string();
            ls.updated_at = chrono::Utc::now().to_rfc3339();
            let _ = write_loop_state(&ls, state_dir, None);
            updated += 1;
        }
    }

    // Named loops in loops/ subdirectory
    let loops_dir = dir.join("loops");
    if let Ok(dir_entries) = fs::read_dir(&loops_dir) {
        for entry in dir_entries.flatten() {
            let path = entry.path();
            if path.extension().map_or(false, |ext| ext == "json") {
                let name = path
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .unwrap_or("")
                    .to_string();
                if let Some(mut ls) = read_loop_state(state_dir, Some(&name)) {
                    let match_filter = from_status_filter.map_or(true, |f| ls.status == f);
                    if match_filter && ls.status != new_status {
                        ls.status = new_status.to_string();
                        ls.updated_at = chrono::Utc::now().to_rfc3339();
                        let _ = write_loop_state(&ls, state_dir, Some(&name));
                        updated += 1;
                    }
                }
            }
        }
    }
    updated
}

/// Set the status of all loops (regardless of current status).
#[allow(dead_code)]
pub fn set_all_loop_statuses(state_dir: Option<&Path>, new_status: &str) -> usize {
    set_all_loop_statuses_filtered(state_dir, None, new_status)
}

/// Clear all persisted loop states (for stop-all).
pub fn clear_all_loop_states(state_dir: Option<&Path>) -> usize {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    let mut cleared = 0usize;

    // Default loop
    let default_path = loop_state_file(&dir, None);
    if default_path.exists() {
        let _ = fs::remove_file(&default_path);
        cleared += 1;
    }

    // Named loops
    let loops_dir = dir.join("loops");
    if let Ok(dir_entries) = fs::read_dir(&loops_dir) {
        for entry in dir_entries.flatten() {
            let path = entry.path();
            if path.extension().map_or(false, |ext| ext == "json") {
                let _ = fs::remove_file(&path);
                cleared += 1;
            }
        }
    }
    cleared
}

/// Entry in a loop listing.
#[derive(Debug, Clone, Serialize)]
pub struct LoopListEntry {
    pub name: String,
    pub task: String,
    pub mode: String,
    pub status: String,
    #[serde(rename = "iterationsCompleted")]
    pub iterations_completed: u64,
    #[serde(rename = "updatedAt")]
    pub updated_at: String,
    #[serde(rename = "intervalSecs")]
    pub interval_secs: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub count: Option<u64>,
    #[serde(rename = "timeoutSecs", skip_serializing_if = "Option::is_none")]
    pub timeout_secs: Option<u64>,
}

/// List all persisted loops. Returns entries sorted by name (default first).
pub fn list_loop_states(state_dir: Option<&Path>) -> Vec<LoopListEntry> {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    let mut entries: Vec<LoopListEntry> = Vec::new();

    // Default loop
    let default_path = loop_state_file(&dir, None);
    if let Ok(raw) = fs::read_to_string(&default_path) {
        if let Ok(ls) = serde_json::from_str::<LoopState>(&raw) {
            entries.push(LoopListEntry {
                name: "default".to_string(),
                task: ls.task_tokens.join(" "),
                mode: ls.mode,
                status: ls.status,
                iterations_completed: ls.iterations_completed,
                updated_at: ls.updated_at,
                interval_secs: ls.interval_secs,
                count: ls.count,
                timeout_secs: ls.timeout_secs,
            });
        }
    }

    // Named loops in loops/ subdirectory
    let loops_dir = dir.join("loops");
    if let Ok(dir_entries) = fs::read_dir(&loops_dir) {
        for entry in dir_entries.flatten() {
            let path = entry.path();
            if path.extension().map_or(false, |ext| ext == "json") {
                let name = path
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .unwrap_or("")
                    .to_string();
                if let Ok(raw) = fs::read_to_string(&path) {
                    if let Ok(ls) = serde_json::from_str::<LoopState>(&raw) {
                        entries.push(LoopListEntry {
                            name,
                            task: ls.task_tokens.join(" "),
                            mode: ls.mode,
                            status: ls.status,
                            iterations_completed: ls.iterations_completed,
                            updated_at: ls.updated_at,
                            interval_secs: ls.interval_secs,
                            count: ls.count,
                            timeout_secs: ls.timeout_secs,
                        });
                    }
                }
            }
        }
    }

    // Sort: default first, then alphabetical
    entries.sort_by(|a, b| {
        if a.name == "default" {
            std::cmp::Ordering::Less
        } else if b.name == "default" {
            std::cmp::Ordering::Greater
        } else {
            a.name.cmp(&b.name)
        }
    });

    entries
}

/// Entry in the loop completion history.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoopHistoryEntry {
    /// Loop name ("default" or the user-provided name).
    pub name: String,
    /// The task tokens that were executed.
    #[serde(rename = "taskTokens")]
    pub task_tokens: Vec<String>,
    /// Execution mode: "plain", "shell", or "subcommand".
    pub mode: String,
    /// Iterations completed before exit.
    #[serde(rename = "iterationsCompleted")]
    pub iterations_completed: u64,
    /// Why the loop ended: "count-reached", "timeout", "stopped", "interrupted".
    #[serde(rename = "exitReason")]
    pub exit_reason: String,
    /// ISO-8601 timestamp when the loop completed.
    #[serde(rename = "completedAt")]
    pub completed_at: String,
}

/// Maximum number of history entries to retain (prevents unbounded growth).
pub const MAX_HISTORY_ENTRIES: usize = 200;

/// Path to the loop history JSONL file.
fn loop_history_path(state_dir: &Path) -> PathBuf {
    state_dir.join("loop-history.jsonl")
}

/// Append a completion event to the loop history log.
/// Keeps at most `MAX_HISTORY_ENTRIES` entries (trims oldest).
pub fn write_loop_history(
    entry: &LoopHistoryEntry,
    state_dir: Option<&Path>,
) -> std::io::Result<()> {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    fs::create_dir_all(&dir)?;
    let path = loop_history_path(&dir);

    // Read existing entries
    let mut entries: Vec<LoopHistoryEntry> = if path.exists() {
        let raw = fs::read_to_string(&path).unwrap_or_default();
        raw.lines()
            .filter(|l| !l.trim().is_empty())
            .filter_map(|l| serde_json::from_str::<LoopHistoryEntry>(l).ok())
            .collect()
    } else {
        Vec::new()
    };

    entries.push(entry.clone());

    // Trim oldest entries if exceeding max
    if entries.len() > MAX_HISTORY_ENTRIES {
        let excess = entries.len() - MAX_HISTORY_ENTRIES;
        entries.drain(0..excess);
    }

    // Write back as JSONL
    let content: String = entries
        .iter()
        .map(|e| {
            serde_json::to_string(e).expect("LoopHistoryEntry serialization should not fail")
        })
        .collect::<Vec<_>>()
        .join("\n");
    fs::write(path, content)
}

/// Read the loop completion history. Returns entries in chronological order
/// (oldest first).
pub fn read_loop_history(state_dir: Option<&Path>) -> Vec<LoopHistoryEntry> {
    let dir = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    let path = loop_history_path(&dir);
    match fs::read_to_string(&path) {
        Ok(raw) => raw
            .lines()
            .filter(|l| !l.trim().is_empty())
            .filter_map(|l| serde_json::from_str::<LoopHistoryEntry>(l).ok())
            .collect(),
        Err(_) => Vec::new(),
    }
}

// ---------------------------------------------------------------------------
// Async task tracking (crawl, agent, swarm)
// ---------------------------------------------------------------------------

/// Represents a single async task tracked by the CLI.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AsyncTaskEntry {
    /// Task ID returned by the server.
    #[serde(rename = "taskId")]
    pub task_id: String,
    /// Command type: "agent", "crawl", or "swarm".
    #[serde(rename = "command")]
    pub command: String,
    /// URL or task description submitted.
    #[serde(rename = "description")]
    pub description: String,
    /// ISO-8601 timestamp when the task was submitted.
    #[serde(rename = "submittedAt")]
    pub submitted_at: String,
    /// Last known status (empty until first poll).
    #[serde(rename = "lastStatus", skip_serializing_if = "String::is_empty", default)]
    pub last_status: String,
    /// ISO-8601 timestamp when the task was first observed as completed (None until done).
    #[serde(rename = "completedAt", skip_serializing_if = "Option::is_none", default)]
    pub completed_at: Option<String>,
}

/// Persisted list of tracked async tasks.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AsyncTaskList {
    #[serde(rename = "tasks")]
    pub tasks: Vec<AsyncTaskEntry>,
}

/// File name for async task persistence.
const ASYNC_TASKS_FILE: &str = "async-tasks.json";

fn async_tasks_path(state_dir: Option<&std::path::Path>) -> std::path::PathBuf {
    let base = state_dir
        .map(|p| p.to_path_buf())
        .unwrap_or_else(resolve_default_state_dir);
    base.join(ASYNC_TASKS_FILE)
}

/// Load the persisted async task list.
pub fn read_async_tasks(state_dir: Option<&std::path::Path>) -> AsyncTaskList {
    let path = async_tasks_path(state_dir);
    match std::fs::read_to_string(&path) {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(_) => AsyncTaskList::default(),
    }
}

/// Save an async task list to disk.
pub fn write_async_tasks(list: &AsyncTaskList, state_dir: Option<&std::path::Path>) -> std::io::Result<()> {
    let path = async_tasks_path(state_dir);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let content = serde_json::to_string_pretty(list)?;
    std::fs::write(&path, content)
}

/// Add a task to the tracked list and persist.
pub fn track_async_task(
    task_id: &str,
    command: &str,
    description: &str,
    state_dir: Option<&std::path::Path>,
) -> std::io::Result<()> {
    let mut list = read_async_tasks(state_dir);
    list.tasks.push(AsyncTaskEntry {
        task_id: task_id.to_string(),
        command: command.to_string(),
        description: description.to_string(),
        submitted_at: chrono::Utc::now().to_rfc3339(),
        last_status: String::new(),
        completed_at: None,
    });
    write_async_tasks(&list, state_dir)
}

/// Remove completed/failed tasks from the tracked list.
pub fn prune_async_tasks(
    state_dir: Option<&std::path::Path>,
) -> std::io::Result<usize> {
    let mut list = read_async_tasks(state_dir);
    let before = list.tasks.len();
    list.tasks.retain(|entry| {
        !entry.last_status.contains("done")
            && !entry.last_status.contains("error")
            && !entry.last_status.contains("SC_OK")
            && !entry.last_status.contains("OK")
    });
    let removed = before - list.tasks.len();
    if removed > 0 {
        write_async_tasks(&list, state_dir)?;
    }
    Ok(removed)
}

/// Update the last_status field for a tracked async task.
pub fn update_async_task_status(
    task_id: &str,
    status: &str,
    state_dir: Option<&std::path::Path>,
) -> std::io::Result<()> {
    let mut list = read_async_tasks(state_dir);
    if let Some(entry) = list.tasks.iter_mut().find(|t| t.task_id == task_id) {
        entry.last_status = status.to_string();
        write_async_tasks(&list, state_dir)?;
    }
    Ok(())
}

/// Format a list of tracked async tasks for CLI display.
///
/// Tasks are sorted by `submitted_at` descending (latest first).
/// When `limit` is Some, only that many entries are shown (after offset),
/// and a hint is appended if more entries exist.
pub fn format_async_task_list(
    list: &AsyncTaskList,
    limit: Option<usize>,
    offset: Option<usize>,
) -> String {
    if list.tasks.is_empty() {
        return "No tracked async tasks.".to_string();
    }

    let total = list.tasks.len();

    // Sort by submitted_at descending (latest first)
    let mut sorted: Vec<&AsyncTaskEntry> = list.tasks.iter().collect();
    sorted.sort_by(|a, b| b.submitted_at.cmp(&a.submitted_at));

    let offset = offset.unwrap_or(0);
    let limit = limit.unwrap_or(usize::MAX);
    let page: Vec<&&AsyncTaskEntry> = sorted.iter().skip(offset).take(limit).collect();

    let mut out = Vec::new();
    let showing = (offset + page.len()).min(total);
    let from = if total > 0 { offset + 1 } else { 0 };
    out.push(format!(
        "{} tracked task(s) (showing {}-{}):\n",
        total, from, showing
    ));
    let paginated = limit < total || offset > 0;

    // Column widths (capped for readability)
    let id_w = page.iter().map(|t| t.task_id.len()).max().unwrap_or(8).max(8).min(12);
    let cmd_w = page.iter().map(|t| t.command.len()).max().unwrap_or(7).max(7);
    let desc_w = page.iter().map(|t| t.description.len()).max().unwrap_or(11).min(40);
    let desc_w = desc_w.max(11);
    let status_w = page
        .iter()
        .map(|t| {
            if t.last_status.is_empty() {
                7 // "pending"
            } else {
                t.last_status.len()
            }
        })
        .max()
        .unwrap_or(6)
        .max(6);
    let time_w = 19; // "2026-07-22 15:04:05"

    out.push(format!(
        "  {:<id_w$}  {:<cmd_w$}  {:<desc_w$}  {:<time_w$}  {:<time_w$}  {:<status_w$}",
        "TASK ID", "COMMAND", "DESCRIPTION", "STARTED", "FINISHED", "STATUS",
        id_w = id_w,
        cmd_w = cmd_w,
        desc_w = desc_w,
        time_w = time_w,
        status_w = status_w,
    ));
    out.push(format!(
        "  {:-<id_w$}  {:-<cmd_w$}  {:-<desc_w$}  {:-<time_w$}  {:-<time_w$}  {:-<status_w$}",
        "", "", "", "", "", "",
        id_w = id_w,
        cmd_w = cmd_w,
        desc_w = desc_w,
        time_w = time_w,
        status_w = status_w,
    ));

    for entry in &page {
        let desc = if entry.description.len() > desc_w {
            format!("{}…", &entry.description[..desc_w - 1])
        } else {
            entry.description.clone()
        };
        let status = if entry.last_status.is_empty() {
            "pending".to_string()
        } else {
            entry.last_status.clone()
        };
        let started = format_timestamp_display(&entry.submitted_at);
        let finished = entry
            .completed_at
            .as_ref()
            .map(|s| format_timestamp_display(s))
            .unwrap_or_else(|| "-".to_string());
        out.push(format!(
            "  {:<id_w$}  {:<cmd_w$}  {:<desc_w$}  {:<time_w$}  {:<time_w$}  {:<status_w$}",
            entry.task_id,
            entry.command,
            desc,
            started,
            finished,
            status,
            id_w = id_w,
            cmd_w = cmd_w,
            desc_w = desc_w,
            time_w = time_w,
            status_w = status_w,
        ));
    }

    if paginated {
        let remaining = total.saturating_sub(showing);
        if remaining > 0 {
            out.push(format!(
                "\n  ... {} more task(s). Use --offset {} to see the next page.",
                remaining,
                showing
            ));
        }
    } else if total > 20 {
        out.push(
            "\n  Hint: Use --limit N to paginate large lists.".to_string(),
        );
    }

    out.join("\n")
}

/// Summarize the status distribution of a list of async tasks.
///
/// Uses the standard lifecycle labels (queued, processing, completed, failed)
/// that are now shared across agent, crawl, and swarm.
pub fn summarize_async_tasks(tasks: &[AsyncTaskEntry]) -> String {
    let total = tasks.len();
    if total == 0 {
        return String::new();
    }

    let mut queued = 0usize;
    let mut processing = 0usize;
    let mut completed = 0usize;
    let mut failed = 0usize;
    let mut other = 0usize;

    for t in tasks {
        match t.last_status.as_str() {
            "" => queued += 1, // empty = not yet checked
            "queued" => queued += 1,
            "processing" => processing += 1,
            "completed" => completed += 1,
            s if s.starts_with("failed") => failed += 1,
            _ => other += 1,
        }
    }

    let mut parts = Vec::new();
    parts.push(format!("{} total", total));
    if completed > 0 {
        parts.push(format!("{} completed", completed));
    }
    if failed > 0 {
        parts.push(format!("{} failed", failed));
    }
    if processing > 0 {
        parts.push(format!("{} processing", processing));
    }
    if queued > 0 {
        parts.push(format!("{} queued", queued));
    }
    if other > 0 {
        parts.push(format!("{} other", other));
    }
    format!("Status: {}", parts.join(", "))
}

/// Format an ISO-8601 timestamp for display as local time "YYYY-MM-DD HH:MM:SS".
///
/// Timestamps are stored in UTC. They are converted to the system's local
/// timezone for display.
pub fn format_timestamp_display(iso: &str) -> String {
    chrono::DateTime::parse_from_rfc3339(iso)
        .or_else(|_| chrono::DateTime::parse_from_rfc3339(&format!("{}Z", iso)))
        .map(|dt| dt.with_timezone(&chrono::Local).format("%Y-%m-%d %H:%M:%S").to_string())
        .unwrap_or_else(|_| iso.chars().take(19).collect())
}

/// Convert a CLI element ref into the selector format expected by Browser4.
///
/// Supported forms:
/// - `e15`       → `backend:15`
/// - `backend:15` → `backend:15` (pass-through)
/// - CSS/XPath selectors are passed through unchanged
pub fn resolve_ref(raw_ref: &str) -> String {
    let trimmed = raw_ref.trim();
    // Match e<digits> (case-insensitive)
    let re = regex::Regex::new(r"(?i)^e(\d+)$").unwrap();
    if let Some(caps) = re.captures(trimmed) {
        return format!("backend:{}", &caps[1]);
    }
    trimmed.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    fn test_temp_dir() -> TempDir {
        let root = std::env::temp_dir()
            .join("browser4")
            .join("browser4-cli")
            .join("state-tests");
        fs::create_dir_all(&root).unwrap();
        tempfile::Builder::new()
            .prefix("state-")
            .tempdir_in(&root)
            .unwrap()
    }

    #[test]
    fn test_resolve_ref_e_notation() {
        assert_eq!(resolve_ref("e15"), "backend:15");
        assert_eq!(resolve_ref("E42"), "backend:42");
        assert_eq!(resolve_ref("  e7  "), "backend:7");
    }

    #[test]
    fn test_resolve_ref_passthrough() {
        assert_eq!(resolve_ref("backend:15"), "backend:15");
        assert_eq!(resolve_ref(".my-class"), ".my-class");
        assert_eq!(resolve_ref("#some-id"), "#some-id");
    }

    #[test]
    fn test_read_write_state() {
        let tmp = test_temp_dir();
        let state = CliState {
            session_id: Some("abc123".to_string()),
            base_url: "http://localhost:8182".to_string(),
            active_selector: None,
            session_name: None,
            last_mouse_position: Some(MousePosition { x: 120.0, y: 240.0 }),
            is_attached: false,
        };
        write_state(&state, Some(tmp.path()), None).unwrap();
        let read = read_state(Some(tmp.path()), None);
        assert_eq!(read.session_id.as_deref(), Some("abc123"));
        assert_eq!(read.base_url, "http://localhost:8182");
        assert_eq!(
            read.last_mouse_position,
            Some(MousePosition { x: 120.0, y: 240.0 })
        );
    }

    #[test]
    fn test_read_state_missing_file() {
        let tmp = test_temp_dir();
        let state = read_state(Some(tmp.path()), None);
        assert_eq!(state.base_url, "http://localhost:8182");
        assert!(state.session_id.is_none());
    }

    #[test]
    fn test_clear_state() {
        let tmp = test_temp_dir();
        let state = CliState::default();
        write_state(&state, Some(tmp.path()), None).unwrap();
        assert!(state_file(tmp.path(), None).exists());
        clear_state(Some(tmp.path()), None);
        assert!(!state_file(tmp.path(), None).exists());
    }

    #[test]
    fn test_named_session_state() {
        let tmp = test_temp_dir();
        let state_auth = CliState {
            session_id: Some("auth123".to_string()),
            base_url: "http://localhost:8182".to_string(),
            active_selector: None,
            session_name: Some("auth".to_string()),
            last_mouse_position: Some(MousePosition { x: 10.0, y: 20.0 }),
            is_attached: false,
        };
        let state_public = CliState {
            session_id: Some("public456".to_string()),
            base_url: "http://localhost:8182".to_string(),
            active_selector: None,
            session_name: Some("public".to_string()),
            last_mouse_position: Some(MousePosition { x: 30.0, y: 40.0 }),
            is_attached: false,
        };

        write_state(&state_auth, Some(tmp.path()), Some("auth")).unwrap();
        write_state(&state_public, Some(tmp.path()), Some("public")).unwrap();

        let read_auth = read_state(Some(tmp.path()), Some("auth"));
        let read_public = read_state(Some(tmp.path()), Some("public"));
        let read_default = read_state(Some(tmp.path()), None);

        assert_eq!(read_auth.session_id.as_deref(), Some("auth123"));
        assert_eq!(read_public.session_id.as_deref(), Some("public456"));
        assert!(read_default.session_id.is_none());
        assert_eq!(
            read_auth.last_mouse_position,
            Some(MousePosition { x: 10.0, y: 20.0 })
        );
        assert_eq!(
            read_public.last_mouse_position,
            Some(MousePosition { x: 30.0, y: 40.0 })
        );

        // Verify files exist
        assert!(state_file(tmp.path(), Some("auth")).exists());
        assert!(state_file(tmp.path(), Some("public")).exists());
        assert!(!state_file(tmp.path(), None).exists());
    }

    #[test]
    fn test_clear_all_state_removes_default_and_named_sessions() {
        let tmp = test_temp_dir();
        let default_state = CliState {
            session_id: Some("default123".to_string()),
            ..CliState::default()
        };
        let named_state = CliState {
            session_id: Some("named123".to_string()),
            session_name: Some("named".to_string()),
            ..CliState::default()
        };

        write_state(&default_state, Some(tmp.path()), None).unwrap();
        write_state(&named_state, Some(tmp.path()), Some("named")).unwrap();
        fs::write(tmp.path().join("sessions").join("notes.txt"), "keep").unwrap();

        clear_all_state(Some(tmp.path()));

        assert!(!state_file(tmp.path(), None).exists());
        assert!(!state_file(tmp.path(), Some("named")).exists());
        assert!(tmp.path().join("sessions").join("notes.txt").exists());
    }

    // -------------------------------------------------------------------
    // Async task tracking tests (crawl, agent, swarm)
    // -------------------------------------------------------------------

    #[test]
    fn test_read_async_tasks_empty_when_no_file() {
        let tmp = test_temp_dir();
        let list = read_async_tasks(Some(tmp.path()));
        assert!(list.tasks.is_empty());
    }

    #[test]
    fn test_track_async_task_adds_entry() {
        let tmp = test_temp_dir();
        track_async_task("crawl-job-1", "crawl", "https://example.com", Some(tmp.path()))
            .unwrap();

        let list = read_async_tasks(Some(tmp.path()));
        assert_eq!(list.tasks.len(), 1);
        assert_eq!(list.tasks[0].task_id, "crawl-job-1");
        assert_eq!(list.tasks[0].command, "crawl");
        assert_eq!(list.tasks[0].description, "https://example.com");
        assert!(!list.tasks[0].submitted_at.is_empty());
        assert!(list.tasks[0].last_status.is_empty());
    }

    #[test]
    fn test_track_multiple_async_tasks() {
        let tmp = test_temp_dir();
        track_async_task("task-1", "crawl", "https://a.com", Some(tmp.path())).unwrap();
        track_async_task("task-2", "crawl", "https://b.com", Some(tmp.path())).unwrap();

        let list = read_async_tasks(Some(tmp.path()));
        assert_eq!(list.tasks.len(), 2);
        assert_eq!(list.tasks[0].task_id, "task-1");
        assert_eq!(list.tasks[1].task_id, "task-2");
    }

    #[test]
    fn test_update_async_task_status() {
        let tmp = test_temp_dir();
        track_async_task("task-1", "crawl", "https://a.com", Some(tmp.path())).unwrap();

        update_async_task_status("task-1", "OK (3 pages)", Some(tmp.path())).unwrap();

        let list = read_async_tasks(Some(tmp.path()));
        assert_eq!(list.tasks[0].last_status, "OK (3 pages)");
    }

    #[test]
    fn test_update_async_task_status_non_existent_is_no_op() {
        let tmp = test_temp_dir();
        // Should not panic or create entries for missing task IDs
        let result = update_async_task_status("nonexistent", "done", Some(tmp.path()));
        assert!(result.is_ok());
        let list = read_async_tasks(Some(tmp.path()));
        assert!(list.tasks.is_empty());
    }

    #[test]
    fn test_prune_async_tasks_removes_completed() {
        let tmp = test_temp_dir();
        track_async_task("t1", "crawl", "url1", Some(tmp.path())).unwrap();
        track_async_task("t2", "crawl", "url2", Some(tmp.path())).unwrap();
        track_async_task("t3", "crawl", "url3", Some(tmp.path())).unwrap();

        // Mark t1 and t3 as completed
        update_async_task_status("t1", "done", Some(tmp.path())).unwrap();
        update_async_task_status("t3", "SC_OK (5 pages)", Some(tmp.path())).unwrap();

        let removed = prune_async_tasks(Some(tmp.path())).unwrap();
        assert_eq!(removed, 2, "should remove t1 and t3");

        let list = read_async_tasks(Some(tmp.path()));
        assert_eq!(list.tasks.len(), 1);
        assert_eq!(list.tasks[0].task_id, "t2");
    }

    #[test]
    fn test_prune_async_tasks_removes_error_tasks() {
        let tmp = test_temp_dir();
        track_async_task("t1", "crawl", "url1", Some(tmp.path())).unwrap();
        track_async_task("t2", "crawl", "url2", Some(tmp.path())).unwrap();

        update_async_task_status("t1", "error: timeout after 600s", Some(tmp.path())).unwrap();

        let removed = prune_async_tasks(Some(tmp.path())).unwrap();
        assert_eq!(removed, 1);

        let list = read_async_tasks(Some(tmp.path()));
        assert_eq!(list.tasks.len(), 1);
        assert_eq!(list.tasks[0].task_id, "t2");
    }

    #[test]
    fn test_prune_async_tasks_no_change_when_all_pending() {
        let tmp = test_temp_dir();
        track_async_task("t1", "crawl", "url1", Some(tmp.path())).unwrap();
        track_async_task("t2", "swarm", "url2", Some(tmp.path())).unwrap();

        let removed = prune_async_tasks(Some(tmp.path())).unwrap();
        assert_eq!(removed, 0);

        let list = read_async_tasks(Some(tmp.path()));
        assert_eq!(list.tasks.len(), 2);
    }

    #[test]
    fn test_prune_async_tasks_empty_list_returns_zero() {
        let tmp = test_temp_dir();
        let removed = prune_async_tasks(Some(tmp.path())).unwrap();
        assert_eq!(removed, 0);
    }

    #[test]
    fn test_format_async_task_list_empty() {
        let list = AsyncTaskList { tasks: vec![] };
        let output = format_async_task_list(&list, None, None);
        assert!(output.contains("No tracked async tasks"));
    }

    #[test]
    fn test_format_async_task_list_with_entries() {
        let list = AsyncTaskList {
            tasks: vec![AsyncTaskEntry {
                task_id: "crawl-job-1".to_string(),
                command: "crawl".to_string(),
                description: "https://example.com".to_string(),
                submitted_at: "2026-01-01T00:00:00+00:00".to_string(),
                last_status: "running".to_string(),
                completed_at: None,
            }],
        };
        let output = format_async_task_list(&list, None, None);
        assert!(output.contains("1 tracked task(s) (showing 1-1)"));
        assert!(output.contains("crawl-job-1"));
        assert!(output.contains("https://example.com"));
        assert!(output.contains("STARTED"));
        assert!(output.contains("FINISHED"));
    }

    #[test]
    fn test_format_async_task_list_sorts_by_started_desc() {
        // Latest submission should come first
        let list = AsyncTaskList {
            tasks: vec![
                AsyncTaskEntry {
                    task_id: "old".to_string(),
                    command: "swarm-submit".to_string(),
                    description: "older".to_string(),
                    submitted_at: "2026-01-01T00:00:00+00:00".to_string(),
                    last_status: "completed".to_string(),
                    completed_at: Some("2026-01-01T00:01:00+00:00".to_string()),
                },
                AsyncTaskEntry {
                    task_id: "new".to_string(),
                    command: "swarm-query".to_string(),
                    description: "newer".to_string(),
                    submitted_at: "2026-07-22T15:00:00+00:00".to_string(),
                    last_status: "pending".to_string(),
                    completed_at: None,
                },
            ],
        };
        let output = format_async_task_list(&list, None, None);
        // "new" must appear before "old" in the output
        let new_pos = output.find("new").unwrap();
        let old_pos = output.find("old").unwrap();
        assert!(new_pos < old_pos, "latest task should appear first, but 'new' at {new_pos} is after 'old' at {old_pos}");
    }

    /// Helper: format a UTC RFC 3339 timestamp as it would appear in local time display.
    fn local_display(utc_rfc3339: &str) -> String {
        chrono::DateTime::parse_from_rfc3339(utc_rfc3339)
            .map(|dt| dt.with_timezone(&chrono::Local).format("%Y-%m-%d %H:%M:%S").to_string())
            .unwrap_or_else(|_| utc_rfc3339.chars().take(19).collect())
    }

    #[test]
    fn test_format_async_task_list_shows_finish_time_when_completed() {
        let list = AsyncTaskList {
            tasks: vec![AsyncTaskEntry {
                task_id: "done-1".to_string(),
                command: "swarm-submit".to_string(),
                description: "https://a.com".to_string(),
                submitted_at: "2026-07-22T14:00:00+00:00".to_string(),
                last_status: "completed".to_string(),
                completed_at: Some("2026-07-22T14:05:30+00:00".to_string()),
            }],
        };
        let output = format_async_task_list(&list, None, None);
        // Started column — should show local time
        let expected_started = local_display("2026-07-22T14:00:00+00:00");
        assert!(output.contains(&expected_started), "expected started time '{}' in output:\n{}", expected_started, output);
        // Finished column — should show the timestamp, not "-"
        let expected_finished = local_display("2026-07-22T14:05:30+00:00");
        assert!(output.contains(&expected_finished), "expected finished time '{}' in output:\n{}", expected_finished, output);
    }

    #[test]
    fn test_format_async_task_list_shows_dash_for_unfinished() {
        let list = AsyncTaskList {
            tasks: vec![AsyncTaskEntry {
                task_id: "pending-1".to_string(),
                command: "swarm-query".to_string(),
                description: "https://b.com".to_string(),
                submitted_at: "2026-07-22T16:00:00+00:00".to_string(),
                last_status: "queued".to_string(),
                completed_at: None,
            }],
        };
        let output = format_async_task_list(&list, None, None);
        // Started should show local time
        let expected_started = local_display("2026-07-22T16:00:00+00:00");
        assert!(output.contains(&expected_started), "expected started time '{}' in output:\n{}", expected_started, output);
        // Finished should show "-" for unfinished tasks
        let needle = &expected_started;
        let after_started = &output[output.find(needle).unwrap() + needle.len()..];
        assert!(after_started.trim().starts_with("-") || after_started.contains("  -  "),
                "unfinished task should show '-' in FINISHED column");
    }

    #[test]
    fn test_format_async_task_list_limit() {
        let mut tasks = Vec::new();
        for i in 0..5 {
            tasks.push(AsyncTaskEntry {
                task_id: format!("task-{}", i),
                command: "swarm-submit".to_string(),
                description: format!("url-{}", i),
                submitted_at: format!("2026-07-22T1{}:00:00+00:00", i),
                last_status: "pending".to_string(),
                completed_at: None,
            });
        }
        let list = AsyncTaskList { tasks };
        let output = format_async_task_list(&list, Some(3), None);
        // Should show range hint
        assert!(output.contains("showing 1-3"));
        // Should show a "more" hint
        assert!(output.contains("more task(s)"));
        assert!(output.contains("--offset 3"));
        // Should contain only 3 entries (not all 5)
        assert!(output.contains("task-4")); // latest first: 4, 3, 2
        assert!(output.contains("task-3"));
        assert!(output.contains("task-2"));
        assert!(!output.contains("task-1"));
        assert!(!output.contains("task-0"));
    }

    #[test]
    fn test_format_async_task_list_offset() {
        let mut tasks = Vec::new();
        for i in 0..5 {
            tasks.push(AsyncTaskEntry {
                task_id: format!("task-{}", i),
                command: "swarm-submit".to_string(),
                description: format!("url-{}", i),
                submitted_at: format!("2026-07-22T1{}:00:00+00:00", i),
                last_status: "pending".to_string(),
                completed_at: None,
            });
        }
        let list = AsyncTaskList { tasks };
        let output = format_async_task_list(&list, Some(2), Some(2));
        // Sorted desc: task-4, task-3, task-2, task-1, task-0
        // Offset 2 skips task-4 and task-3
        // Limit 2 shows task-2 and task-1
        assert!(output.contains("showing 3-4"));
        assert!(output.contains("task-2"));
        assert!(output.contains("task-1"));
        assert!(!output.contains("task-4"));
        assert!(!output.contains("task-3"));
        assert!(!output.contains("task-0"));
        assert!(output.contains("more task(s)"));
        assert!(output.contains("--offset 4"));
    }

    #[test]
    fn test_format_async_task_list_no_pagination_hint_when_all_shown() {
        let list = AsyncTaskList {
            tasks: vec![AsyncTaskEntry {
                task_id: "only".to_string(),
                command: "swarm-submit".to_string(),
                description: "url".to_string(),
                submitted_at: "2026-07-22T12:00:00+00:00".to_string(),
                last_status: "completed".to_string(),
                completed_at: None,
            }],
        };
        let output = format_async_task_list(&list, Some(10), None);
        // Only 1 task, limit 10 — all shown, no "more" hint
        assert!(output.contains("showing 1-1"));
        assert!(!output.contains("more task"));
    }

    #[test]
    fn test_format_async_task_list_suggests_limit_for_large_lists() {
        let mut tasks = Vec::new();
        for i in 0..25 {
            tasks.push(AsyncTaskEntry {
                task_id: format!("task-{:02}", i),
                command: "swarm-submit".to_string(),
                description: format!("url-{}", i),
                submitted_at: format!("2026-07-22T{:02}:00:00+00:00", i),
                last_status: "pending".to_string(),
                completed_at: None,
            });
        }
        let list = AsyncTaskList { tasks };
        let output = format_async_task_list(&list, None, None);
        assert!(output.contains("showing 1-25"));
        assert!(output.contains("Hint: Use --limit N to paginate"));
    }

    #[test]
    fn test_format_timestamp_display_handles_missing_tz() {
        // Should handle ISO-8601 with just "Z" suffix, converting to local time
        let output = format_async_task_list(
            &AsyncTaskList {
                tasks: vec![AsyncTaskEntry {
                    task_id: "t1".to_string(),
                    command: "swarm-submit".to_string(),
                    description: "url".to_string(),
                    submitted_at: "2026-07-22T12:00:00Z".to_string(),
                    last_status: "pending".to_string(),
                    completed_at: None,
                }],
            },
            None,
            None,
        );
        let expected = local_display("2026-07-22T12:00:00Z");
        assert!(output.contains(&expected), "expected local time '{}' in output:\n{}", expected, output);
    }

    // -----------------------------------------------------------------------
    // summarize_async_tasks tests
    // -----------------------------------------------------------------------

    fn entry(command: &str, status: &str) -> AsyncTaskEntry {
        AsyncTaskEntry {
            task_id: format!("id-{}", command),
            command: command.to_string(),
            description: "test".to_string(),
            submitted_at: "2026-07-22T00:00:00+00:00".to_string(),
            last_status: status.to_string(),
            completed_at: None,
        }
    }

    #[test]
    fn test_summarize_empty_returns_empty_string() {
        let result = summarize_async_tasks(&[]);
        assert_eq!(result, "");
    }

    #[test]
    fn test_summarize_all_queued() {
        let tasks = vec![entry("swarm-submit", ""), entry("swarm-query", "queued")];
        let result = summarize_async_tasks(&tasks);
        assert!(result.contains("2 total"));
        assert!(result.contains("2 queued"));
    }

    #[test]
    fn test_summarize_all_completed() {
        let tasks = vec![entry("agent", "completed"), entry("crawl", "completed"), entry("swarm", "completed")];
        let result = summarize_async_tasks(&tasks);
        assert!(result.contains("3 total"));
        assert!(result.contains("3 completed"));
        assert!(!result.contains("queued"));
        assert!(!result.contains("processing"));
    }

    #[test]
    fn test_summarize_mixed_distribution() {
        let tasks = vec![
            entry("swarm-submit", "completed"),
            entry("swarm-submit", "completed"),
            entry("swarm-query", "completed"),
            entry("swarm-query", "completed"),
            entry("swarm-query", "failed (timeout)"),
            entry("swarm-query", "failed (timeout)"),
            entry("swarm-submit", "processing"),
            entry("swarm-submit", "processing"),
            entry("swarm-submit", "processing"),
            entry("swarm-query", "queued"),
        ];
        let result = summarize_async_tasks(&tasks);
        assert!(result.contains("10 total"));
        assert!(result.contains("4 completed"));
        assert!(result.contains("2 failed"));
        assert!(result.contains("3 processing"));
        assert!(result.contains("1 queued"));
    }

    #[test]
    fn test_summarize_unknown_labels_grouped_as_other() {
        let tasks = vec![entry("crawl", "CREATED"), entry("crawl", "OK")];
        let result = summarize_async_tasks(&tasks);
        assert!(result.contains("2 total"));
        assert!(result.contains("2 other"));
    }

    #[test]
    fn test_summarize_failed_prefix_variations() {
        let tasks = vec![
            entry("agent", "failed (timeout)"),
            entry("agent", "failed (error)"),
            entry("agent", "failed (not found)"),
        ];
        let result = summarize_async_tasks(&tasks);
        assert!(result.contains("3 total"));
        assert!(result.contains("3 failed"));
    }
}
