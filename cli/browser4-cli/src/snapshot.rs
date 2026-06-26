//! Snapshot and screenshot file helpers for the Browser4 CLI.

use std::fs;
use std::path::{Path, PathBuf};

use chrono::{Datelike, Utc};

/// Default path segments for snapshot and screenshot outputs.
pub const SNAPSHOT_DIR: [&str; 2] = [".browser4-cli", "snapshot"];

/// Maximum number of snapshot files to keep in the main snapshot directory
/// before older ones are moved to dated archive subdirectories.
const MAX_SNAPSHOTS: usize = 100;

/// Build the default snapshot output directory using OS-native separators.
pub fn snapshot_dir() -> PathBuf {
    PathBuf::from(SNAPSHOT_DIR[0]).join(SNAPSHOT_DIR[1])
}

/// Build the archive root directory path.
pub fn archive_dir() -> PathBuf {
    snapshot_dir().join("archive")
}

/// Ensure a directory exists, creating it recursively if needed.
pub fn ensure_dir(dir: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dir)
}

/// Generate a timestamped filename (e.g., `snapshot-2026-01-15T10-30-00-123Z.yml`).
pub fn timestamped_filename(prefix: &str, ext: &str) -> String {
    let now = Utc::now();
    let base = now.format("%Y-%m-%dT%H-%M-%S").to_string();
    let ms = now.timestamp_subsec_millis();
    format!("{}-{}-{:03}Z.{}", prefix, base, ms, ext)
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

/// Save a text snapshot to disk, then rotate old snapshots into the archive.
pub fn save_snapshot(path: &Path, content: &str) -> std::io::Result<()> {
    ensure_dir(path.parent().unwrap_or(Path::new(".")))?;
    fs::write(path, content)?;
    rotate_snapshots();
    Ok(())
}

/// Save binary data (e.g., screenshot PNG) to disk.
pub fn save_binary(path: &Path, data: &[u8]) -> std::io::Result<()> {
    ensure_dir(path.parent().unwrap_or(Path::new(".")))?;
    fs::write(path, data)
}

// ---------------------------------------------------------------------------
// Snapshot rotation — move older snapshots into dated archive directories
// ---------------------------------------------------------------------------

/// Move snapshot files exceeding `MAX_SNAPSHOTS` from the main snapshot
/// directory into `archive/YYYY-MM-DD/` subdirectories based on each file's
/// modification time.
pub fn rotate_snapshots() {
    let snap_dir = snapshot_dir();
    if !snap_dir.is_dir() {
        return;
    }

    let mut entries: Vec<(PathBuf, std::time::SystemTime)> = match fs::read_dir(&snap_dir) {
        Ok(iter) => iter
            .filter_map(|e| e.ok())
            .filter(|e| e.path().extension().map_or(false, |ext| ext == "yml"))
            .filter_map(|e| {
                let modified = e.metadata().ok()?.modified().ok()?;
                Some((e.path(), modified))
            })
            .collect(),
        Err(_) => return,
    };

    // Keep the most recent MAX_SNAPSHOTS; move the rest.
    if entries.len() <= MAX_SNAPSHOTS {
        return;
    }

    // Sort by modification time, newest first.
    entries.sort_by(|a, b| b.1.cmp(&a.1));

    let to_move = entries.split_off(MAX_SNAPSHOTS);

    for (path, modified) in to_move {
        let date_dir = archive_date_dir(modified);
        if let Err(e) = ensure_dir(&date_dir) {
            eprintln!(
                "Warning: failed to create archive dir {}: {e}",
                date_dir.display()
            );
            continue;
        }
        let dest = date_dir.join(path.file_name().unwrap_or_default());
        if let Err(e) = fs::rename(&path, &dest) {
            eprintln!("Warning: failed to archive {}: {e}", path.display());
        }
    }
}

/// Build the archive subdirectory path for a given system time, e.g.
/// `archive/2026-06-02/`.
fn archive_date_dir(modified: std::time::SystemTime) -> PathBuf {
    let datetime: chrono::DateTime<Utc> = modified.into();
    archive_dir().join(format!(
        "{:04}-{:02}-{:02}",
        datetime.year(),
        datetime.month(),
        datetime.day()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn test_temp_dir() -> TempDir {
        let root = std::env::temp_dir()
            .join("browser4")
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
