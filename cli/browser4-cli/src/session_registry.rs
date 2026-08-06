//! In-memory session registry for the Browser4 CLI.
//!
//! The registry aggregates all sessions the CLI knows about — both from disk
//! (persisted `CliState` files) and from the backend (`list_sessions` MCP
//! response).  It replaces the previous pattern of reading a single `CliState`
//! per invocation and scanning the sessions directory ad-hoc for `list`.
//!
//! The registry is loaded once at startup and flushed back to disk when
//! sessions are created, updated, or removed.  It is **not** shared between
//! concurrent CLI processes — each invocation builds its own registry from disk.

use std::collections::HashMap;
use std::path::PathBuf;

use crate::state::{
    self, clear_state, read_state, write_state, CliState, SessionKind,
    resolve_default_state_dir,
};

// ---------------------------------------------------------------------------
// SessionEntry
// ---------------------------------------------------------------------------

/// A session tracked by the CLI registry.
#[derive(Debug, Clone)]
pub struct SessionEntry {
    /// User-facing name: `None` for the default/unnamed session, `Some(name)` for named.
    pub name: Option<String>,
    /// Backend session ID (UUID, "SWARM", or user-provided name).
    pub session_id: String,
    /// The persisted CLI state for this session.
    pub state: CliState,
}

impl SessionEntry {
    /// Display name for use in tables and messages.
    pub fn display_name(&self) -> &str {
        self.name.as_deref().unwrap_or("(default)")
    }

    /// The session kind, derived from the state.
    pub fn kind(&self) -> SessionKind {
        self.state.kind
    }

    /// Whether this session owns its browser.
    pub fn owns_browser(&self) -> bool {
        self.kind().owns_browser()
    }

    /// Human-readable connection label for table display.
    pub fn connection_label(&self) -> String {
        match self.state.kind {
            SessionKind::CdpAttached => {
                if let Some(ref endpoint) = self.state.cdp_endpoint {
                    format!("CDP: {}", endpoint)
                } else {
                    "CDP".to_string()
                }
            }
            SessionKind::ExtensionAttached => {
                if let Some(ref channel) = self.state.browser_channel {
                    format!("Extension ({})", channel)
                } else {
                    "Extension".to_string()
                }
            }
            SessionKind::Swarm => "Swarm".to_string(),
            SessionKind::Browser4Launched => "Browser4".to_string(),
        }
    }
}

// ---------------------------------------------------------------------------
// SessionRegistry
// ---------------------------------------------------------------------------

/// In-memory registry of all CLI-tracked sessions.
///
/// Loaded at startup by scanning the state directory.  Writes through to disk
/// on mutation so that concurrent CLI invocations pick up changes.
#[derive(Debug, Clone)]
pub struct SessionRegistry {
    /// Default/unnamed session, if one exists.
    default_session: Option<SessionEntry>,
    /// Named sessions keyed by name.
    named_sessions: HashMap<String, SessionEntry>,
    /// State directory for disk persistence.
    state_dir: PathBuf,
    /// The session name selected by `-s <name>` (or `None` for default).
    active_name: Option<String>,
}

impl SessionRegistry {
    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /// Load all sessions from the default state directory into memory.
    pub fn load() -> Self {
        Self::load_from(resolve_default_state_dir())
    }

    /// Load all sessions from a specific state directory.
    pub fn load_from(state_dir: PathBuf) -> Self {
        let mut registry = Self {
            default_session: None,
            named_sessions: HashMap::new(),
            state_dir,
            active_name: None,
        };
        registry.reload();
        registry
    }

    /// Reload all sessions from disk.  Clears and re-populates the in-memory
    /// cache.  Call this to pick up changes made by other CLI processes.
    pub fn reload(&mut self) {
        self.default_session = None;
        self.named_sessions.clear();

        // Load the unnamed/default session.
        let default_state = read_state(Some(&self.state_dir), None);
        if default_state.session_id.is_some() {
            self.default_session = Some(SessionEntry {
                name: None,
                session_id: default_state.session_id.clone().unwrap(),
                state: default_state,
            });
        }

        // Load named sessions from sessions/ subdirectory.
        let sessions_dir = self.state_dir.join("sessions");
        if sessions_dir.exists() {
            if let Ok(entries) = std::fs::read_dir(&sessions_dir) {
                for entry in entries.flatten() {
                    let path = entry.path();
                    if path.extension().map_or(false, |ext| ext == "json") {
                        let name = path.file_stem().unwrap().to_string_lossy().to_string();
                        let state = read_state(Some(&self.state_dir), Some(&name));
                        if let Some(ref sid) = state.session_id {
                            self.named_sessions.insert(
                                name.clone(),
                                SessionEntry {
                                    name: Some(name),
                                    session_id: sid.clone(),
                                    state,
                                },
                            );
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Access
    // ------------------------------------------------------------------

    /// Get the currently active session entry (selected by `-s` or default).
    pub fn get_active(&self) -> Option<&SessionEntry> {
        match &self.active_name {
            Some(name) => self.get_named(name),
            None => self.default(),
        }
    }

    /// Get the active session entry, mutably.
    pub fn get_active_mut(&mut self) -> Option<&mut SessionEntry> {
        match self.active_name.clone() {
            Some(name) => self.get_named_mut(&name),
            None => self.default_mut(),
        }
    }

    /// Get the default/unnamed session.
    pub fn default(&self) -> Option<&SessionEntry> {
        self.default_session.as_ref()
    }

    /// Get the default/unnamed session, mutably.
    pub fn default_mut(&mut self) -> Option<&mut SessionEntry> {
        self.default_session.as_mut()
    }

    /// Get a named session by name.
    pub fn get_named(&self, name: &str) -> Option<&SessionEntry> {
        self.named_sessions.get(name)
    }

    /// Get a named session by name, mutably.
    pub fn get_named_mut(&mut self, name: &str) -> Option<&mut SessionEntry> {
        self.named_sessions.get_mut(name)
    }

    /// Get a session by optional name (`None` = default).
    pub fn get(&self, name: Option<&str>) -> Option<&SessionEntry> {
        match name {
            Some(n) => self.get_named(n),
            None => self.default(),
        }
    }

    /// Get a session by optional name, mutably.
    pub fn get_mut(&mut self, name: Option<&str>) -> Option<&mut SessionEntry> {
        match name {
            Some(n) => self.get_named_mut(n),
            None => self.default_mut(),
        }
    }

    /// The session name currently selected as active.
    pub fn active_name(&self) -> Option<&str> {
        self.active_name.as_deref()
    }

    // ------------------------------------------------------------------
    // Mutation
    // ------------------------------------------------------------------

    /// Set the active session name (from `-s <name>` flag or `switch` command).
    pub fn set_active_name(&mut self, name: Option<String>) {
        self.active_name = name;
    }

    /// Register or update a session and persist to disk.
    ///
    /// - `name`: `None` for the default/unnamed session, `Some(name)` for named.
    /// - `state`: the `CliState` to store.
    pub fn upsert(&mut self, name: Option<&str>, mut state: CliState) -> std::io::Result<()> {
        state.session_name = name.map(|s| s.to_string());

        // Persist to disk.
        write_state(&state, Some(&self.state_dir), name)?;

        let session_id = state.session_id.clone().unwrap_or_default();
        let entry = SessionEntry {
            name: name.map(|s| s.to_string()),
            session_id,
            state,
        };

        match name {
            Some(n) => {
                self.named_sessions.insert(n.to_string(), entry);
            }
            None => {
                self.default_session = Some(entry);
            }
        }

        Ok(())
    }

    /// Remove a session from the registry and delete its persisted file.
    ///
    /// Returns the removed entry, or `None` if not found.
    pub fn remove(&mut self, name: Option<&str>) -> Option<SessionEntry> {
        clear_state(Some(&self.state_dir), name);
        match name {
            Some(n) => self.named_sessions.remove(n),
            None => self.default_session.take(),
        }
    }

    /// Remove all sessions and clear all persisted state.
    pub fn clear_all(&mut self) {
        self.default_session = None;
        self.named_sessions.clear();
        state::clear_all_state(Some(&self.state_dir));
    }

    // ------------------------------------------------------------------
    // Iteration
    // ------------------------------------------------------------------

    /// Total number of tracked sessions.
    pub fn count(&self) -> usize {
        let n = if self.default_session.is_some() { 1 } else { 0 };
        n + self.named_sessions.len()
    }

    /// Iterate over all sessions (default first, then named alphabetically).
    pub fn iter(&self) -> impl Iterator<Item = &SessionEntry> {
        let mut all: Vec<&SessionEntry> = Vec::new();
        if let Some(ref default_entry) = self.default_session {
            all.push(default_entry);
        }
        let mut named: Vec<&SessionEntry> = self.named_sessions.values().collect();
        named.sort_by_key(|e| e.name.clone());
        all.extend(named);
        all.into_iter()
    }
}

impl Default for SessionRegistry {
    fn default() -> Self {
        Self::load()
    }
}
