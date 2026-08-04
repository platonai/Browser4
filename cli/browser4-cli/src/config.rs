//! Persistent CLI configuration for Browser4.
//!
//! Unlike session state (`cli-state.json`), the config file stores global
//! defaults — server URL, timeout, proxy, default session name — that apply
//! across all sessions.  It lives at `~/.browser4/config.json`.

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

use crate::state::resolve_default_state_dir;

/// Allowed keys for `config set` / `config get` / `config delete`.
pub const VALID_CONFIG_KEYS: &[&str] = &["server", "timeout", "proxy", "session"];

/// Persistent CLI configuration stored in `~/.browser4/config.json`.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ConfigStore {
    /// Default Browser4 server URL (e.g. `http://localhost:8182`).
    /// Overrides the hardcoded default; still overridden by `--server`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub server: Option<String>,
    /// Default HTTP request timeout in seconds.
    /// Overridden by `--timeout`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timeout: Option<u64>,
    /// Default proxy URL for downloads.
    /// Overridden by `--proxy`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub proxy: Option<String>,
    /// Default session name.
    /// Overridden by `-s` / `--session` / `BROWSER4_CLI_SESSION`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session: Option<String>,
}

/// Resolve the path to the config file.
pub fn config_path() -> PathBuf {
    resolve_default_state_dir().join("config.json")
}

/// Read the persisted config from disk, falling back to an empty default.
pub fn read_config() -> ConfigStore {
    let path = config_path();
    match fs::read_to_string(&path) {
        Ok(raw) => serde_json::from_str::<ConfigStore>(&raw).unwrap_or_default(),
        Err(_) => ConfigStore::default(),
    }
}

/// Write the config to disk, creating the parent directory if necessary.
pub fn write_config(config: &ConfigStore) -> std::io::Result<()> {
    let path = config_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string_pretty(config).expect("config serialization should not fail");
    fs::write(path, json)
}

/// Look up a config key and return its string value, or `None` if not set.
pub fn config_value(config: &ConfigStore, key: &str) -> Option<String> {
    match key {
        "server" => config.server.clone(),
        "timeout" => config.timeout.map(|t| t.to_string()),
        "proxy" => config.proxy.clone(),
        "session" => config.session.clone(),
        _ => None,
    }
}

/// Set a config key to a string value. Returns `Err` if the key is unknown.
pub fn config_set_value(config: &mut ConfigStore, key: &str, value: &str) -> Result<(), String> {
    match key {
        "server" => config.server = Some(value.to_string()),
        "timeout" => {
            let n: u64 = value
                .parse()
                .map_err(|_| format!("Invalid timeout value '{}': expected a positive integer (seconds)", value))?;
            config.timeout = Some(n);
        }
        "proxy" => config.proxy = Some(value.to_string()),
        "session" => config.session = Some(value.to_string()),
        other => {
            let valid = VALID_CONFIG_KEYS
                .iter()
                .map(|k| format!("'{}'", k))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(format!(
                "Unknown config key '{}'. Valid keys are: {}",
                other, valid
            ));
        }
    }
    Ok(())
}

/// Remove a config key, resetting it to its (absent) default.
/// Returns `Err` if the key is unknown.
pub fn config_delete_value(config: &mut ConfigStore, key: &str) -> Result<(), String> {
    match key {
        "server" => config.server = None,
        "timeout" => config.timeout = None,
        "proxy" => config.proxy = None,
        "session" => config.session = None,
        other => {
            let valid = VALID_CONFIG_KEYS
                .iter()
                .map(|k| format!("'{}'", k))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(format!(
                "Unknown config key '{}'. Valid keys are: {}",
                other, valid
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config_is_empty() {
        let c = ConfigStore::default();
        assert!(c.server.is_none());
        assert!(c.timeout.is_none());
        assert!(c.proxy.is_none());
        assert!(c.session.is_none());
    }

    #[test]
    fn test_config_set_and_get() {
        let mut c = ConfigStore::default();
        config_set_value(&mut c, "server", "http://localhost:9090").unwrap();
        assert_eq!(config_value(&c, "server"), Some("http://localhost:9090".to_string()));
    }

    #[test]
    fn test_config_set_timeout_rejects_non_numeric() {
        let mut c = ConfigStore::default();
        let err = config_set_value(&mut c, "timeout", "abc").unwrap_err();
        assert!(err.contains("Invalid timeout"), "Expected 'Invalid timeout' in: {err}");
    }

    #[test]
    fn test_config_set_timeout_accepts_numeric() {
        let mut c = ConfigStore::default();
        config_set_value(&mut c, "timeout", "30").unwrap();
        assert_eq!(c.timeout, Some(30));
    }

    #[test]
    fn test_config_set_rejects_unknown_key() {
        let mut c = ConfigStore::default();
        let err = config_set_value(&mut c, "unknown", "value").unwrap_err();
        assert!(err.contains("Unknown config key"), "Expected 'Unknown config key' in: {err}");
    }

    #[test]
    fn test_config_delete_clears_value() {
        let mut c = ConfigStore::default();
        config_set_value(&mut c, "proxy", "http://proxy:8080").unwrap();
        assert!(c.proxy.is_some());
        config_delete_value(&mut c, "proxy").unwrap();
        assert!(c.proxy.is_none());
    }

    #[test]
    fn test_config_delete_rejects_unknown_key() {
        let mut c = ConfigStore::default();
        let err = config_delete_value(&mut c, "nope").unwrap_err();
        assert!(err.contains("Unknown config key"), "Expected 'Unknown config key' in: {err}");
    }

    #[test]
    fn test_valid_config_keys_are_all_handled() {
        // Verify that every key in VALID_CONFIG_KEYS is actually handled
        // by config_value, config_set_value, and config_delete_value.
        let mut c = ConfigStore::default();
        for key in VALID_CONFIG_KEYS {
            // Set
            config_set_value(&mut c, key, if *key == "timeout" { "10" } else { "testval" }).unwrap();
            // Get
            let val = config_value(&c, key);
            assert!(val.is_some(), "Key '{}' should have a value after set", key);
            // Delete
            config_delete_value(&mut c, key).unwrap();
            assert!(config_value(&c, key).is_none(), "Key '{}' should be None after delete", key);
        }
    }

    #[test]
    fn test_config_value_returns_none_for_unset_keys() {
        let c = ConfigStore::default();
        assert_eq!(config_value(&c, "server"), None);
        assert_eq!(config_value(&c, "timeout"), None);
    }
}
