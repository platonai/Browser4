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
///
/// When `filename` contains path separators (`/` or `\`) or is an absolute path,
/// it is treated as a user-specified path relative to the current working directory.
/// Bare filenames (no path separators) are saved to the default snapshot directory.
pub fn resolve_output_path(filename: Option<&str>, prefix: &str, ext: &str) -> PathBuf {
    let name = filename
        .map(|f| f.to_string())
        .unwrap_or_else(|| timestamped_filename(prefix, ext));

    let looks_like_path = name.contains('/') || name.contains('\\');
    let is_absolute = Path::new(&name).is_absolute();

    if is_absolute {
        return PathBuf::from(&name);
    }

    let out = if looks_like_path {
        PathBuf::from(&name)
    } else {
        snapshot_dir().join(&name)
    };

    std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join(&out)
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
// Screenshot image formats — the requested format comes from the output file
// extension, and the *written* path always follows the bytes that came back.
// ---------------------------------------------------------------------------

/// Image formats the screenshot command can ask the backend for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImageFormat {
    Png,
    Jpeg,
}

impl ImageFormat {
    /// Value of the MCP `format` argument for this image format.
    pub fn param_value(self) -> &'static str {
        match self {
            ImageFormat::Png => "png",
            ImageFormat::Jpeg => "jpeg",
        }
    }

    /// Canonical file extension for this image format.
    pub fn extension(self) -> &'static str {
        match self {
            ImageFormat::Png => "png",
            ImageFormat::Jpeg => "jpg",
        }
    }

    /// Image format named by a file extension, if it is one the screenshot
    /// command understands (`.` is optional, case-insensitive).
    pub fn from_extension(ext: &str) -> Option<Self> {
        match ext.trim_start_matches('.').to_ascii_lowercase().as_str() {
            "png" => Some(ImageFormat::Png),
            "jpg" | "jpeg" => Some(ImageFormat::Jpeg),
            _ => None,
        }
    }

    /// Image format encoded in `bytes`, detected from the file signature.
    ///
    /// Returns `None` for payloads that are not a PNG/JPEG image (for example
    /// the mock-server payload used by the e2e harness): guessing a format
    /// there would rename a perfectly good file.
    pub fn from_magic(bytes: &[u8]) -> Option<Self> {
        const PNG_MAGIC: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        if bytes.starts_with(&PNG_MAGIC) {
            Some(ImageFormat::Png)
        } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
            Some(ImageFormat::Jpeg)
        } else {
            None
        }
    }
}

/// The image format the user asked for, taken from the screenshot output file
/// extension, defaulting to PNG when the name carries no (or an unrecognised)
/// extension.
///
/// `screenshot` has no `--format` flag — the extension *is* the request, so a
/// file named `*.png` must end up holding PNG bytes.
pub fn requested_screenshot_format(filename: Option<&str>) -> ImageFormat {
    filename
        .and_then(|name| Path::new(name).extension())
        .and_then(|ext| ext.to_str())
        .and_then(ImageFormat::from_extension)
        .unwrap_or(ImageFormat::Png)
}

/// Rewrite `path`'s extension when the captured bytes do not match it, so the
/// printed `[Screenshot](...)` link never names a PNG file that holds JPEG
/// data (the driver's element-capture path is JPEG-only).
///
/// Paths with no extension or a non-image extension are left alone — they make
/// no promise about the payload — as are payloads whose format is unknown.
pub fn reconcile_screenshot_path(path: &Path, bytes: &[u8]) -> PathBuf {
    let Some(actual) = ImageFormat::from_magic(bytes) else {
        return path.to_path_buf();
    };
    let requested = path
        .extension()
        .and_then(|ext| ext.to_str())
        .and_then(ImageFormat::from_extension);
    match requested {
        Some(requested) if requested != actual => path.with_extension(actual.extension()),
        _ => path.to_path_buf(),
    }
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
    fn test_resolve_output_path_with_relative_path() {
        let path = resolve_output_path(Some("subdir/my-shot.png"), "screenshot", "png");

        // Should use the user-supplied path relative to cwd, NOT the snapshot dir
        assert!(
            !path.ends_with(snapshot_dir().join("subdir").join("my-shot.png")),
            "path with separator should not be placed in snapshot dir"
        );
        assert!(path.ends_with(Path::new("subdir").join("my-shot.png")));
    }

    #[test]
    fn test_resolve_output_path_with_absolute_path() {
        // Use an absolute path that works on the current platform
        let abs_path = if cfg!(windows) {
            "C:\\tmp\\absolute-shot.png".to_string()
        } else {
            "/tmp/absolute-shot.png".to_string()
        };
        let path = resolve_output_path(Some(&abs_path), "screenshot", "png");

        assert_eq!(path, PathBuf::from(&abs_path));
    }

    #[test]
    fn test_resolve_output_path_bare_filename_still_uses_snapshot_dir() {
        let path = resolve_output_path(Some("bare-file.png"), "screenshot", "png");

        assert!(path.ends_with(snapshot_dir().join("bare-file.png")));
    }

    #[test]
    fn test_save_snapshot() {
        let tmp = test_temp_dir();
        let path = tmp.path().join("sub").join("snap.yml");
        save_snapshot(&path, "content: here").unwrap();
        let content = fs::read_to_string(&path).unwrap();
        assert_eq!(content, "content: here");
    }

    // -----------------------------------------------------------------------
    // Screenshot format / extension reconciliation
    // -----------------------------------------------------------------------

    /// PNG signature + a minimal body; only the magic bytes matter here.
    const PNG_BYTES: [u8; 12] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03];
    /// JFIF header start (`ff d8 ff e0`) followed by padding.
    const JPEG_BYTES: [u8; 8] = [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46];

    #[test]
    fn test_requested_screenshot_format_from_extension() {
        assert_eq!(
            requested_screenshot_format(Some("out.png")),
            ImageFormat::Png
        );
        assert_eq!(
            requested_screenshot_format(Some("dir\\out.PNG")),
            ImageFormat::Png
        );
        assert_eq!(
            requested_screenshot_format(Some("out.jpg")),
            ImageFormat::Jpeg
        );
        assert_eq!(
            requested_screenshot_format(Some("out.jpeg")),
            ImageFormat::Jpeg
        );
        // No name (timestamped default) and unknown extensions both promise
        // nothing, so the lossless default is used.
        assert_eq!(requested_screenshot_format(None), ImageFormat::Png);
        assert_eq!(
            requested_screenshot_format(Some("out.webp")),
            ImageFormat::Png
        );
        assert_eq!(requested_screenshot_format(Some("out")), ImageFormat::Png);
    }

    #[test]
    fn test_image_format_from_magic() {
        assert_eq!(ImageFormat::from_magic(&PNG_BYTES), Some(ImageFormat::Png));
        assert_eq!(ImageFormat::from_magic(&JPEG_BYTES), Some(ImageFormat::Jpeg));
        assert_eq!(ImageFormat::from_magic(b"mock screenshot"), None);
        assert_eq!(ImageFormat::from_magic(&[]), None);
    }

    #[test]
    fn test_reconcile_screenshot_path_keeps_matching_extension() {
        let png = Path::new("/tmp/out.png");
        assert_eq!(reconcile_screenshot_path(png, &PNG_BYTES), png);

        // `.jpeg` and `.jpg` both describe JPEG bytes, so neither is rewritten.
        assert_eq!(
            reconcile_screenshot_path(Path::new("/tmp/out.jpeg"), &JPEG_BYTES),
            PathBuf::from("/tmp/out.jpeg")
        );
        assert_eq!(
            reconcile_screenshot_path(Path::new("/tmp/out.jpg"), &JPEG_BYTES),
            PathBuf::from("/tmp/out.jpg")
        );
    }

    #[test]
    fn test_reconcile_screenshot_path_rewrites_mismatched_extension() {
        // The reported defect: a `.png` name holding JPEG bytes (full-page and
        // element captures used to be JPEG) must not keep the `.png` extension.
        assert_eq!(
            reconcile_screenshot_path(Path::new("/tmp/out-fullpage.png"), &JPEG_BYTES),
            PathBuf::from("/tmp/out-fullpage.jpg")
        );
        assert_eq!(
            reconcile_screenshot_path(Path::new("/tmp/out.jpg"), &PNG_BYTES),
            PathBuf::from("/tmp/out.png")
        );
    }

    #[test]
    fn test_reconcile_screenshot_path_keeps_unpromising_paths() {
        // A payload we cannot identify (e.g. the e2e mock server) must not
        // trigger a rename.
        assert_eq!(
            reconcile_screenshot_path(Path::new("/tmp/out.png"), b"mock screenshot"),
            PathBuf::from("/tmp/out.png")
        );
        // Neither must a name that never promised an image format.
        assert_eq!(
            reconcile_screenshot_path(Path::new("/tmp/out"), &JPEG_BYTES),
            PathBuf::from("/tmp/out")
        );
        assert_eq!(
            reconcile_screenshot_path(Path::new("/tmp/out.bin"), &JPEG_BYTES),
            PathBuf::from("/tmp/out.bin")
        );
    }
}
