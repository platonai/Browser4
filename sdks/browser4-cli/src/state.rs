//! Persistent state management for the Browser4 CLI.
//!
//! State is stored in `~/.browser4/cli-state.json` and shared across all
//! `browser4-cli` invocations in the same session.

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
