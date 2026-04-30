//! Snapshot and screenshot file helpers for the Browser4 CLI.

use std::fs;
use std::path::{Path, PathBuf};

use chrono::Utc;

/// Default path segments for snapshot and screenshot outputs.
pub const SNAPSHOT_DIR: [&str; 2] = [".browser4-cli", "snapshot"];

/// Build the default snapshot output directory using OS-native separators.
pub fn snapshot_dir() -> PathBuf {
    PathBuf::from(SNAPSHOT_DIR[0]).join(SNAPSHOT_DIR[1])
}

/// Ensure a directory exists, creating it recursively if needed.
pub fn ensure_dir(dir: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dir)
}

/// Generate a timestamped filename (e.g., `page-2026-01-15T10-30-00.yml`).
pub fn timestamped_filename(prefix: &str, ext: &str) -> String {
    let now = Utc::now().format("%Y-%m-%dT%H-%M-%S").to_string();
    format!("{}-{}.{}", prefix, now, ext)
}

/// Resolve the output path for a snapshot or screenshot, creating the directory
/// if necessary. Returns the absolute path as a string.
pub fn resolve_output_path(filename: Option<&str>, prefix: &str, ext: &str) -> PathBuf {
    let name = filename
        .map(|f| f.to_string())
        .unwrap_or_else(|| timestamped_filename(prefix, ext));

    let out = snapshot_dir().join(&name);
    let canonical = std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join(&out);
    canonical
}

/// Save a text snapshot to disk.
pub fn save_snapshot(path: &Path, content: &str) -> std::io::Result<()> {
    ensure_dir(path.parent().unwrap_or(Path::new(".")))?;
    fs::write(path, content)
}

/// Save binary data (e.g., screenshot PNG) to disk.
pub fn save_binary(path: &Path, data: &[u8]) -> std::io::Result<()> {
    ensure_dir(path.parent().unwrap_or(Path::new(".")))?;
    fs::write(path, data)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn test_temp_dir() -> TempDir {
        let root = std::env::temp_dir()
            .join(".browser4")
            .join("browser4-cli")
            .join("snapshot-tests");
        fs::create_dir_all(&root).unwrap();
        tempfile::Builder::new()
            .prefix("snapshot-")
            .tempdir_in(&root)
            .unwrap()
    }

    #[test]
    fn test_timestamped_filename_format() {
        let name = timestamped_filename("page", "yml");
        assert!(name.starts_with("page-"));
        assert!(name.ends_with(".yml"));
    }

    #[test]
    fn test_snapshot_dir_uses_os_native_segments() {
        assert_eq!(snapshot_dir(), Path::new(".browser4-cli").join("snapshot"));
    }

    #[test]
    fn test_resolve_output_path_uses_snapshot_dir() {
        let path = resolve_output_path(Some("snap.yml"), "snapshot", "yml");

        assert!(path.ends_with(snapshot_dir().join("snap.yml")));
    }

    #[test]
    fn test_save_snapshot() {
        let tmp = test_temp_dir();
        let path = tmp.path().join("sub").join("snap.yml");
        save_snapshot(&path, "content: here").unwrap();
        let content = fs::read_to_string(&path).unwrap();
        assert_eq!(content, "content: here");
    }
}
