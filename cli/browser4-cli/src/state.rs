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
}

impl Default for CliState {
    fn default() -> Self {
        Self {
            session_id: None,
            base_url: "http://localhost:8182".to_string(),
            active_selector: None,
            session_name: None,
            last_mouse_position: None,
        }
    }
}

/// Resolve the default state directory, honouring `BROWSER4_CLI_STATE_DIR`.
pub fn resolve_default_state_dir() -> PathBuf {
    if let Ok(override_dir) = std::env::var("BROWSER4_CLI_STATE_DIR") {
        let trimmed = override_dir.trim().to_string();
        if !trimmed.is_empty() {
            return PathBuf::from(&trimmed)
                .canonicalize()
                .unwrap_or(PathBuf::from(trimmed));
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

/// Format a list of tracked async tasks for CLI display.
pub fn format_async_task_list(list: &AsyncTaskList) -> String {
    if list.tasks.is_empty() {
        return "No tracked async tasks.".to_string();
    }

    let mut out = Vec::new();
    out.push(format!("{} tracked task(s):\n", list.tasks.len()));

    // Column widths
    let id_w = list.tasks.iter().map(|t| t.task_id.len()).max().unwrap_or(8).max(8);
    let cmd_w = 8;
    let desc_w = 40;

    out.push(format!(
        "  {:<id_w$}  {:<cmd_w$}  {:<desc_w$}  {}",
        "TASK ID", "COMMAND", "DESCRIPTION", "STATUS",
        id_w = id_w,
        cmd_w = cmd_w,
        desc_w = desc_w,
    ));
    out.push(format!(
        "  {:-<id_w$}  {:-<cmd_w$}  {:-<desc_w$}  {}",
        "", "", "", "------",
        id_w = id_w,
        cmd_w = cmd_w,
        desc_w = desc_w,
    ));

    for entry in &list.tasks {
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
        out.push(format!(
            "  {:<id_w$}  {:<cmd_w$}  {:<desc_w$}  {}",
            entry.task_id,
            entry.command,
            desc,
            status,
            id_w = id_w,
            cmd_w = cmd_w,
            desc_w = desc_w,
        ));
    }
    out.join("\n")
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
        };
        let state_public = CliState {
            session_id: Some("public456".to_string()),
            base_url: "http://localhost:8182".to_string(),
            active_selector: None,
            session_name: Some("public".to_string()),
            last_mouse_position: Some(MousePosition { x: 30.0, y: 40.0 }),
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
}
