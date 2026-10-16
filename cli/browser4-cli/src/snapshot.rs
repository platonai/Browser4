//! Snapshot and screenshot file helpers for the Browser4 CLI.
//!
//! Beyond path/save/rotation helpers this module owns the line-oriented repair
//! of raw backend accessibility-tree bodies ([`normalize_capture_body`]) and the
//! persisted auto-diff reports ([`save_diff_report`]).

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
///
/// The returned path is both written to and echoed as the artifact link, so its
/// separators are normalised before returning: joining the working directory
/// (native `\` on Windows) with a `--filename`/`-o` argument written with `/`
/// used to print e.g. `D:\ws\.test-sessions/2026…/out.json`.
pub fn resolve_output_path(filename: Option<&str>, prefix: &str, ext: &str) -> PathBuf {
    let name = filename
        .map(|f| f.to_string())
        .unwrap_or_else(|| timestamped_filename(prefix, ext));

    let looks_like_path = name.contains('/') || name.contains('\\');
    let is_absolute = Path::new(&name).is_absolute();

    if is_absolute {
        return crate::normalize_path_display(Path::new(&name));
    }

    let out = if looks_like_path {
        PathBuf::from(&name)
    } else {
        snapshot_dir().join(&name)
    };

    let joined = std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join(&out);
    crate::normalize_path_display(&joined)
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

// ---------------------------------------------------------------------------
// Accessibility-tree body normalization
// ---------------------------------------------------------------------------
//
// The AX renderer runs in the backend (`PageHandler` + `AriaSnapshotFormatting`
// in browser4-base), and three of its rendering choices are wrong for a file the
// CLI persists, paginates and greps (snapshot-mastery issues #3, #7 and #8):
//
//  * the `# Viewport State` header always reports `processingViewport: 0` plus
//    `hiddenTopHeight` / `hiddenBottomHeight` measured against the *unfiltered*
//    page, even when the capture was not viewport-scoped, so a whole-page tree
//    claims to be one screenful;
//  * the trailing viewport-navigation footer is concatenated onto the last node
//    line, because the rendered tree does not end with a newline;
//  * a node whose accessible name makes the key unsafe as a YAML plain scalar
//    (a name containing `": "` is the common case) is emitted as one whole-line
//    double-quoted scalar — `- "generic \"Foo: bar\" [ref=e7]"` — instead of the
//    canonical `- generic "Foo: bar" [ref=e7]` shape.
//
// `normalize_capture_body` repairs all three on the CLI side, so they do not
// wait for a backend release.  Every helper is line-oriented: the CLI never
// re-serializes the tree, it only repairs the rendering accidents above.
//
// `handle_snapshot` calls `normalize_capture_body` for every capture, and
// `handle_snapshot_clean` prunes stale diff reports.

/// Role word that may start a canonical tree line — `button`, `listitem`,
/// `textbox`, …  Used to tell a whole-line-quoted *node* line from any other
/// double-quoted scalar when re-flowing the line.
fn is_aria_role_word(token: &str) -> bool {
    let mut chars = token.chars();
    match chars.next() {
        Some(first) if first.is_ascii_lowercase() => {}
        _ => return false,
    }
    chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_')
}

/// Split a JSON-style double-quoted scalar that starts at the beginning of
/// `input`.  Returns the raw (still escaped) body and the text following the
/// closing quote, or `None` when `input` does not start one or it is
/// unterminated.
fn split_json_scalar(input: &str) -> Option<(&str, &str)> {
    if !input.starts_with('"') {
        return None;
    }
    let mut escaped = false;
    for (index, ch) in input.char_indices().skip(1) {
        if escaped {
            escaped = false;
            continue;
        }
        match ch {
            '\\' => escaped = true,
            '"' => return Some((&input[1..index], &input[index + 1..])),
            _ => {}
        }
    }
    None
}

/// Unescape the JSON string body the backend renderer produces (`\"`, `\\`,
/// `\/`, `\b`, `\f`, `\n`, `\r`, `\t`).
///
/// Returns `None` for an unknown escape sequence, so a double-quoted scalar the
/// CLI does not fully understand is never rewritten.
fn unescape_json_scalar(raw: &str) -> Option<String> {
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(ch) = chars.next() {
        if ch != '\\' {
            out.push(ch);
            continue;
        }
        match chars.next()? {
            '"' => out.push('"'),
            '\\' => out.push('\\'),
            '/' => out.push('/'),
            'b' => out.push('\u{0008}'),
            'f' => out.push('\u{000C}'),
            'n' => out.push('\n'),
            'r' => out.push('\r'),
            't' => out.push('\t'),
            _ => return None,
        }
    }
    Some(out)
}

/// Canonical replacement for a whole-line-quoted node line, or `None` when
/// `line` is not one.
///
/// The backend quotes the *whole* key (`- "generic \"Foo: bar\" [ref=e7]"`)
/// whenever the accessible name would break a YAML plain scalar, so one node in
/// six changes shape.  This re-emits it as `- generic "Foo: bar" [ref=e7]` — the
/// shape every other node already has, and the shape `snapshot grep` and the
/// diff parser expect (#8).
///
/// The line is only rewritten when the unescaped scalar really is a node key: a
/// leading lower-case role word, followed by a name and/or `[attrs]`.  Comment
/// lines, `- /url:` property lines, `- text:` lines and already-canonical nodes
/// all return `None`, so the caller keeps them verbatim.
pub fn canonicalize_node_line(line: &str) -> Option<String> {
    let indent_len = line.len() - line.trim_start().len();
    let (indent, rest) = line.split_at(indent_len);
    let body = rest.strip_prefix("- ")?;
    let (raw, suffix) = split_json_scalar(body)?;
    // Only the inlined-text separator may follow the key (`- key: text`).
    if !suffix.is_empty() && !suffix.starts_with(':') {
        return None;
    }
    let key = unescape_json_scalar(raw)?;
    let role = key.split_whitespace().next()?;
    if !is_aria_role_word(role) {
        return None;
    }
    Some(format!("{indent}- {key}{suffix}"))
}

/// Rewrite every whole-line-quoted node line of `body` into the canonical
/// `- <role> "<name>" [ref=…] [box=…]` shape (#8).  All other lines — comments,
/// property lines, already-canonical nodes — are copied verbatim.
///
/// Line endings are normalized to `\n`; the trailing-newline state of `body` is
/// preserved.
pub fn canonicalize_body(body: &str) -> String {
    let lines: Vec<String> = body
        .lines()
        .map(|line| canonicalize_node_line(line).unwrap_or_else(|| line.to_string()))
        .collect();
    join_preserving_trailing_newline(&lines, body)
}

/// Split a line on a YAML comment marker that was concatenated onto content,
/// returning `(content, comment)`.
///
/// A marker only counts outside double-quoted scalars, directly after a
/// non-whitespace character, and when it is followed by whitespace, another `#`
/// or the end of the line — exactly the shape the backend's glued footer
/// produces (`… [box=1897,1072,0,0]# ---`), never a `#` inside a node name or an
/// already-separated comment.
fn split_glued_comment(line: &str) -> Option<(&str, &str)> {
    let mut in_quotes = false;
    let mut escaped = false;
    let mut previous: Option<char> = None;
    for (index, ch) in line.char_indices() {
        if escaped {
            escaped = false;
            previous = Some(ch);
            continue;
        }
        match ch {
            '\\' if in_quotes => escaped = true,
            '"' => in_quotes = !in_quotes,
            '#' if !in_quotes && previous.is_some_and(|p| !p.is_whitespace()) => {
                let comment = &line[index..];
                let next = comment[1..].chars().next();
                if next.is_none() || next == Some(' ') || next == Some('#') {
                    return Some((&line[..index], comment));
                }
            }
            _ => {}
        }
        previous = Some(ch);
    }
    None
}

/// Give a comment block concatenated onto the preceding line its own line (#7).
///
/// The backend appends its `# ---` viewport-navigation footer directly to the
/// rendered tree, producing
/// `- list [ref=e8185] [box=1897,1072,0,0]# ---` and corrupting the last YAML
/// node for every line-based reader.  Line endings are normalized to `\n`; the
/// trailing-newline state of `body` is preserved.
pub fn repair_glued_comment_blocks(body: &str) -> String {
    let mut lines: Vec<String> = Vec::with_capacity(body.lines().count() + 8);
    for line in body.lines() {
        match split_glued_comment(line) {
            Some((content, comment)) => {
                lines.push(content.to_string());
                lines.push(comment.to_string());
            }
            None => lines.push(line.to_string()),
        }
    }
    join_preserving_trailing_newline(&lines, body)
}

/// True for the backend viewport-navigation footer's signature lines, e.g.
/// `# This page has 10 viewports (page chunks split by viewport height). …` or
/// `# You are currently viewing viewport 0 (absolute).`
fn is_viewport_footer_line(line: &str) -> bool {
    let Some(comment) = line.trim_start().strip_prefix('#') else {
        return false;
    };
    let text = comment.trim_start();
    (text.starts_with("This page has") && text.contains("viewport"))
        || text.starts_with("You are currently viewing viewport")
}

/// Drop the backend's trailing viewport-navigation footer (#3).
///
/// The footer tells the reader to navigate with `snapshot -v N` and states which
/// viewport is on screen.  For a capture that was *not* viewport-scoped that is
/// false — the tree holds the whole page — so the block is removed.  It is only
/// removed when it carries the backend's signature, so an unknown trailing
/// comment block is left untouched.
pub fn strip_viewport_nav_footer(body: &str) -> String {
    let lines: Vec<&str> = body.lines().collect();
    let mut end = lines.len();
    while end > 0 && lines[end - 1].trim().is_empty() {
        end -= 1;
    }
    let mut start = end;
    while start > 0 && lines[start - 1].trim_start().starts_with('#') {
        start -= 1;
    }
    if start == end
        || !lines[start..end]
            .iter()
            .any(|line| is_viewport_footer_line(line))
    {
        return body.to_string();
    }
    let kept: Vec<String> = lines[..start].iter().map(|line| line.to_string()).collect();
    join_preserving_trailing_newline(&kept, body)
}

/// Marker line of the backend's viewport-state comment block.
const VIEWPORT_STATE_MARKER: &str = "# Viewport State";

/// Value of a `# - <field>: <value>` line inside a comment block.
fn viewport_state_field<'a>(block: &[&'a str], field: &str) -> Option<&'a str> {
    let prefix = format!("# - {field}:");
    block.iter().find_map(|line| {
        line.trim()
            .strip_prefix(&prefix)
            .map(|value| value.trim())
            .filter(|value| !value.is_empty())
    })
}

/// Rewrite the backend's `# Viewport State` comment block so it describes the
/// capture that was actually requested (#3).
///
/// For a whole-page capture (`viewports == None`) the block's
/// `processingViewport`, `hiddenTopHeight` and `hiddenBottomHeight` fields
/// describe a single viewport of a tree that contains the whole page — the false
/// "You are viewing viewport 0" premise.  They are replaced by one honest
/// `# - scope: whole page …` line, while `viewportHeight` and `viewportsTotal`
/// (real page geometry, also parsed back by the CLI for its viewport hint) stay.
///
/// With an explicit `-v` spec the block is left alone: the backend already
/// reports the *requested* spec there, which is what the reader needs.
pub fn rewrite_viewport_state_header(body: &str, viewports: Option<&str>) -> String {
    if viewports.is_some() {
        return body.to_string();
    }
    let lines: Vec<&str> = body.lines().collect();
    let Some(marker) = lines
        .iter()
        .position(|line| line.trim() == VIEWPORT_STATE_MARKER)
    else {
        return body.to_string();
    };
    let mut end = marker + 1;
    while end < lines.len() && lines[end].trim_start().starts_with('#') {
        end += 1;
    }
    let block = &lines[marker..end];
    let total = viewport_state_field(block, "viewportsTotal");
    let scope = match total {
        Some(total) => {
            format!("# - scope: whole page (all {total} viewports; no --viewport filter)")
        }
        None => "# - scope: whole page (no --viewport filter)".to_string(),
    };

    let mut rewritten: Vec<String> = lines[..marker].iter().map(|l| l.to_string()).collect();
    rewritten.push(VIEWPORT_STATE_MARKER.to_string());
    rewritten.push(scope);
    for field in ["viewportHeight", "viewportsTotal"] {
        if let Some(value) = viewport_state_field(block, field) {
            rewritten.push(format!("# - {field}: {value}"));
        }
    }
    rewritten.push("#".to_string());
    rewritten.extend(lines[end..].iter().map(|l| l.to_string()));
    join_preserving_trailing_newline(&rewritten, body)
}

/// Normalize a raw backend accessibility-tree body for display and persistence.
///
/// `viewports` is the `-v` / `--viewport` spec the capture was requested with;
/// `None` means no viewport filter, i.e. a whole-page capture.
///
/// The order matters: the footer is unglued from the last node first (#7), then
/// dropped entirely for a whole-page capture (#3), then the scope header is
/// corrected (#3), and finally every node line is put back into the canonical
/// shape (#8).
///
/// The result is idempotent, and the CLI-side scope header the caller prepends
/// to it (`# Snapshot — full page tree …`) stays true.
pub fn normalize_capture_body(body: &str, viewports: Option<&str>) -> String {
    let unglued = repair_glued_comment_blocks(body);
    let scoped = match viewports {
        Some(_) => unglued,
        None => strip_viewport_nav_footer(&unglued),
    };
    canonicalize_body(&rewrite_viewport_state_header(&scoped, viewports))
}

/// Join normalized `lines`, preserving whether `original` ended with a newline.
fn join_preserving_trailing_newline(lines: &[String], original: &str) -> String {
    let mut out = lines.join("\n");
    if original.ends_with('\n') && !out.is_empty() {
        out.push('\n');
    }
    out
}

// ---------------------------------------------------------------------------
// Auto-diff reports — the greppable half of `snapshot --auto-diff` (#5)
// ---------------------------------------------------------------------------
//
// The diff appendix exists only in the `--auto-diff` response text, so
// `snapshot grep` (which always re-captures the live tree) can never see its
// `## Added` / `+ …` lines.  The CLI therefore persists the appendix next to the
// capture that produced it, and can search the newest report instead.

/// File-name suffix of the persisted auto-diff report
/// (`snapshot-2026-01-15T10-30-00-123Z.diff.txt`).
///
/// The `.txt` extension keeps the report out of every snapshot-file scan — the
/// `snapshot list`, rotation and the auto-diff baseline picker all look for
/// `*.yml`.
pub const DIFF_REPORT_SUFFIX: &str = ".diff.txt";

/// Companion report path for a capture file: `snapshot-….yml` becomes
/// `snapshot-….diff.txt` in the same directory.
pub fn diff_report_path(snapshot_path: &Path) -> PathBuf {
    snapshot_path.with_extension(DIFF_REPORT_SUFFIX.trim_start_matches('.'))
}

/// Capture path a persisted auto-diff report describes: the inverse of
/// [`diff_report_path`] (`snapshot-….diff.txt` → `snapshot-….yml`).
///
/// Returns `None` for a file that does not carry [`DIFF_REPORT_SUFFIX`], so a
/// caller cannot mistake an ordinary snapshot for a report.  `snapshot clean`
/// uses it to delete a report together with the capture it was derived from.
pub fn diff_report_capture_path(report_path: &Path) -> Option<PathBuf> {
    let name = report_path.file_name()?.to_string_lossy();
    let stem = name.strip_suffix(DIFF_REPORT_SUFFIX)?;
    Some(report_path.with_file_name(format!("{stem}.yml")))
}

/// Persist the `--auto-diff` appendix so it can be searched after the fact (#5).
///
/// The parent directory is created when missing (a `--stdout` capture writes no
/// snapshot file, but its diff must still be greppable).  Returns the path that
/// was written.
pub fn save_diff_report(snapshot_path: &Path, report: &str) -> std::io::Result<PathBuf> {
    let path = diff_report_path(snapshot_path);
    ensure_dir(path.parent().unwrap_or(Path::new(".")))?;
    fs::write(&path, report)?;
    Ok(path)
}

/// Newest persisted auto-diff report in `dir`, if the directory holds one.
pub fn latest_diff_report(dir: &Path) -> Option<PathBuf> {
    let mut reports: Vec<(PathBuf, std::time::SystemTime)> = fs::read_dir(dir)
        .ok()?
        .filter_map(|entry| entry.ok())
        .filter(|entry| {
            entry
                .file_name()
                .to_string_lossy()
                .ends_with(DIFF_REPORT_SUFFIX)
        })
        .filter_map(|entry| {
            let modified = entry.metadata().ok()?.modified().ok()?;
            Some((entry.path(), modified))
        })
        .collect();

    reports.sort_by(|a, b| b.1.cmp(&a.1));
    reports.into_iter().next().map(|(path, _)| path)
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
    fn resolve_output_path_normalizes_mixed_separators() {
        // Regression: `extract … --filename .test-sessions/x.json` (and
        // `screenshot -o .test-sessions/x.png`) printed the artifact link as
        // `D:\ws\.test-sessions/2026…/x.json` — separators from the working
        // directory joined with the forward slashes of the argument, so the
        // path was copy-pasteable into neither shell.
        let path = resolve_output_path(Some(".test-sessions/sub/shot.png"), "screenshot", "png");
        let display = path.display().to_string();
        if cfg!(windows) {
            assert!(!display.contains('/'), "mixed separators remain: {display}");
            assert!(
                display.contains(r".test-sessions\sub\shot.png"),
                "got: {display}"
            );
        } else {
            assert!(
                display.ends_with(".test-sessions/sub/shot.png"),
                "got: {display}"
            );
        }
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

    // -----------------------------------------------------------------------
    // Accessibility-tree body normalization (#3, #7, #8)
    // -----------------------------------------------------------------------

    /// A raw backend body carrying all three rendering defects at once: the
    /// viewport-0 header of a whole-page capture (#3), a whole-line-quoted node
    /// whose name contains `": "` (#8) and the viewport footer glued to the last
    /// node line (#7).
    const RAW_BACKEND_BODY: &str = r#"# Viewport State
# - processingViewport: 0
# - viewportHeight: 1080px
# - viewportsTotal: 10
# - hiddenTopHeight: 0px
# - hiddenBottomHeight: 9571px
#
- generic [ref=e8184]:
  - heading "Christopher Alexander" [ref=e8185] [box=10,10,300,40]:
    - /url: https://en.wikipedia.org/wiki/Christopher_Alexander
  - "generic \"A Pattern Language: Towns, Buildings, Construction\" [ref=e7306] [box=1116,216,309,49]"
  - list [ref=e8185] [box=1897,1072,0,0]# ---
# This page has 10 viewports (page chunks split by viewport height). You are currently viewing viewport 0 (absolute).
# To read the page viewport by viewport (like a human scrolling):
#   snapshot -v 0          # current visible area
#   snapshot -v 1          # scroll down one viewport
#
"#;

    #[test]
    fn canonicalize_node_line_rewrites_whole_line_quoted_node() {
        // Regression (#8): the name contains ": ", so the backend quotes the
        // whole key instead of only the name.
        let line = r#"  - "generic \"A Pattern Language: Towns, Buildings, Construction\" [ref=e7306] [box=1116,216,309,49]""#;

        assert_eq!(
            canonicalize_node_line(line).as_deref(),
            Some(
                r#"  - generic "A Pattern Language: Towns, Buildings, Construction" [ref=e7306] [box=1116,216,309,49]"#
            )
        );
    }

    #[test]
    fn canonicalize_node_line_handles_quoted_nodes_without_name_or_attrs() {
        // A childless, ref-less node whose name still forced whole-line quoting.
        assert_eq!(
            canonicalize_node_line(r#"- "heading \"Issue: 3\"""#).as_deref(),
            Some(r#"- heading "Issue: 3""#)
        );
        // The inlined-text form keeps its `: text` separator.
        assert_eq!(
            canonicalize_node_line(r#"- "button \"Save: draft\"": Save draft"#).as_deref(),
            Some(r#"- button "Save: draft": Save draft"#)
        );
    }

    #[test]
    fn canonicalize_node_line_keeps_every_other_line_verbatim() {
        // Already canonical, a property line, a text child, a comment, a name
        // that merely contains a '#'.
        for line in [
            r#"- generic [ref=e7]:"#,
            r#"- button "Search" [ref=e25] [box=1,2,3,4]"#,
            r#"  - /url: https://example.com"#,
            r#"  - text: "hello""#,
            r#"# Viewport State"#,
            r#"- generic "C# in Depth" [ref=e9]"#,
            "",
        ] {
            assert_eq!(canonicalize_node_line(line), None, "line: {line}");
        }
    }

    #[test]
    fn canonicalize_node_line_rejects_quoted_non_node_scalars() {
        // A quoted scalar that is not a node key must never be re-flowed: the
        // role-looking head is not a role word, or the suffix is not `: …`.
        for line in [
            r#"- "/url: https://example.com""#,
            r#"- "A Pattern Language""#,
            r#"- "generic \"Foo: bar\" [ref=e1]" trailing"#,
            r#"- "generic \"Foo"#,
        ] {
            assert_eq!(canonicalize_node_line(line), None, "line: {line}");
        }
    }

    #[test]
    fn canonicalize_body_rewrites_nodes_and_preserves_trailing_newline() {
        let body = "- \"heading \\\"Issue: 3\\\" [ref=e1]\"\n- link \"Home\" [ref=e2]\n";

        assert_eq!(
            canonicalize_body(body),
            "- heading \"Issue: 3\" [ref=e1]\n- link \"Home\" [ref=e2]\n"
        );
        // No trailing newline in, none out.
        assert_eq!(
            canonicalize_body("- \"heading \\\"Issue: 3\\\" [ref=e1]\""),
            "- heading \"Issue: 3\" [ref=e1]"
        );
    }

    #[test]
    fn repair_glued_comment_blocks_splits_footer_from_last_node() {
        // Regression (#7): `- list [ref=e8185] [box=1897,1072,0,0]# ---` shares
        // one line, so the last node is unparseable for a line-based reader.
        let glued = "  - list [ref=e8185] [box=1897,1072,0,0]# ---\n#   snapshot -v 0\n";

        assert_eq!(
            repair_glued_comment_blocks(glued),
            "  - list [ref=e8185] [box=1897,1072,0,0]\n# ---\n#   snapshot -v 0\n"
        );
    }

    #[test]
    fn repair_glued_comment_blocks_leaves_hashes_in_names_and_comments_alone() {
        // A '#' inside a quoted name, at the start of a line, or already behind
        // whitespace is not a glued comment.
        let body = "- generic \"C# in Depth\" [ref=e9]\n# ---\n- text \"a # b\" [ref=e8]\n";

        assert_eq!(repair_glued_comment_blocks(body), body);
    }

    #[test]
    fn strip_viewport_nav_footer_drops_the_backend_footer() {
        let body = "- list [ref=e1] [box=0,0,0,0]\n# ---\n# This page has 10 viewports (page chunks split by viewport height). You are currently viewing viewport 0 (absolute).\n#   snapshot -v 0          # current visible area\n#\n";

        assert_eq!(
            strip_viewport_nav_footer(body),
            "- list [ref=e1] [box=0,0,0,0]\n"
        );
    }

    #[test]
    fn strip_viewport_nav_footer_keeps_unrelated_trailing_comments() {
        // A trailing comment block without the footer signature is not ours to
        // delete.
        let body = "- list [ref=e1]\n# ---\n# captured by browser4-cli\n#\n";

        assert_eq!(strip_viewport_nav_footer(body), body);
    }

    #[test]
    fn rewrite_viewport_state_header_reports_whole_page_scope() {
        // Regression (#3): processingViewport/hiddenBottomHeight describe one
        // viewport of a whole-page tree and must go; the page geometry stays.
        let body = "# Viewport State\n# - processingViewport: 0\n# - viewportHeight: 1080px\n# - viewportsTotal: 10\n# - hiddenTopHeight: 0px\n# - hiddenBottomHeight: 9571px\n#\n- generic [ref=e1]\n";

        assert_eq!(
            rewrite_viewport_state_header(body, None),
            "# Viewport State\n# - scope: whole page (all 10 viewports; no --viewport filter)\n# - viewportHeight: 1080px\n# - viewportsTotal: 10\n#\n- generic [ref=e1]\n"
        );
    }

    #[test]
    fn rewrite_viewport_state_header_survives_a_missing_viewport_count() {
        let body = "# Viewport State\n# - processingViewport: 0\n#\n- generic [ref=e1]\n";

        assert_eq!(
            rewrite_viewport_state_header(body, None),
            "# Viewport State\n# - scope: whole page (no --viewport filter)\n#\n- generic [ref=e1]\n"
        );
    }

    #[test]
    fn rewrite_viewport_state_header_leaves_viewport_captures_alone() {
        let body = "# Viewport State\n# - processingViewport: 0\n# - hiddenBottomHeight: 9571px\n#\n- generic [ref=e1]\n";

        assert_eq!(rewrite_viewport_state_header(body, Some("0")), body);
        // ... and a body without a viewport-state block is untouched.
        assert_eq!(
            rewrite_viewport_state_header("- generic [ref=e1]\n", None),
            "- generic [ref=e1]\n"
        );
    }

    #[test]
    fn rewrite_viewport_state_header_is_idempotent() {
        let once = rewrite_viewport_state_header(RAW_BACKEND_BODY, None);

        assert_eq!(rewrite_viewport_state_header(&once, None), once);
    }

    #[test]
    fn normalize_capture_body_repairs_a_whole_page_capture() {
        // #3 + #7 + #8 in one capture: honest scope header, no viewport
        // navigation footer, no glued node line, uniform node shape.
        assert_eq!(
            normalize_capture_body(RAW_BACKEND_BODY, None),
            concat!(
                "# Viewport State\n",
                "# - scope: whole page (all 10 viewports; no --viewport filter)\n",
                "# - viewportHeight: 1080px\n",
                "# - viewportsTotal: 10\n",
                "#\n",
                "- generic [ref=e8184]:\n",
                "  - heading \"Christopher Alexander\" [ref=e8185] [box=10,10,300,40]:\n",
                "    - /url: https://en.wikipedia.org/wiki/Christopher_Alexander\n",
                "  - generic \"A Pattern Language: Towns, Buildings, Construction\" [ref=e7306] [box=1116,216,309,49]\n",
                "  - list [ref=e8185] [box=1897,1072,0,0]\n",
            )
        );
    }

    #[test]
    fn normalize_capture_body_keeps_the_footer_for_a_viewport_capture() {
        // With `-v 0` the backend header and the navigation footer are true, so
        // only the glued line and the quoted node are repaired.
        assert_eq!(
            normalize_capture_body(RAW_BACKEND_BODY, Some("0")),
            concat!(
                "# Viewport State\n",
                "# - processingViewport: 0\n",
                "# - viewportHeight: 1080px\n",
                "# - viewportsTotal: 10\n",
                "# - hiddenTopHeight: 0px\n",
                "# - hiddenBottomHeight: 9571px\n",
                "#\n",
                "- generic [ref=e8184]:\n",
                "  - heading \"Christopher Alexander\" [ref=e8185] [box=10,10,300,40]:\n",
                "    - /url: https://en.wikipedia.org/wiki/Christopher_Alexander\n",
                "  - generic \"A Pattern Language: Towns, Buildings, Construction\" [ref=e7306] [box=1116,216,309,49]\n",
                "  - list [ref=e8185] [box=1897,1072,0,0]\n",
                "# ---\n",
                "# This page has 10 viewports (page chunks split by viewport height). You are currently viewing viewport 0 (absolute).\n",
                "# To read the page viewport by viewport (like a human scrolling):\n",
                "#   snapshot -v 0          # current visible area\n",
                "#   snapshot -v 1          # scroll down one viewport\n",
                "#\n",
            )
        );
    }

    #[test]
    fn normalize_capture_body_is_idempotent() {
        let once = normalize_capture_body(RAW_BACKEND_BODY, None);

        assert_eq!(normalize_capture_body(&once, None), once);
    }

    #[test]
    fn normalize_capture_body_keeps_the_viewport_total_readable() {
        // main.rs parses `# - viewportsTotal:` back out of the body for its
        // multi-viewport hint, so the rewritten header must keep it.
        let normalized = normalize_capture_body(RAW_BACKEND_BODY, None);

        assert!(
            normalized
                .lines()
                .any(|line| line.starts_with("# - viewportsTotal: 10")),
            "viewport total lost:\n{normalized}"
        );
        // The false single-viewport fields are gone.
        for field in [
            "processingViewport",
            "hiddenTopHeight",
            "hiddenBottomHeight",
        ] {
            assert!(
                !normalized.contains(field),
                "misleading `{field}` survived:\n{normalized}"
            );
        }
    }

    // -----------------------------------------------------------------------
    // Auto-diff reports (#5)
    // -----------------------------------------------------------------------

    #[test]
    fn diff_report_path_sits_next_to_the_capture() {
        assert_eq!(
            diff_report_path(Path::new(
                "/tmp/.browser4-cli/snapshot/snapshot-2026-01-15T10-30-00-123Z.yml"
            )),
            PathBuf::from("/tmp/.browser4-cli/snapshot/snapshot-2026-01-15T10-30-00-123Z.diff.txt")
        );
    }

    #[test]
    fn diff_report_capture_path_inverts_diff_report_path() {
        let capture = Path::new("/tmp/.browser4-cli/snapshot/snapshot-2026-01-15T10-30-00-123Z.yml");

        assert_eq!(
            diff_report_capture_path(&diff_report_path(capture)).as_deref(),
            Some(capture)
        );
        // A file that is not a diff report names no capture.
        assert_eq!(
            diff_report_capture_path(Path::new("/tmp/snapshot-2026.yml")),
            None
        );
    }

    #[test]
    fn save_diff_report_creates_the_directory_and_latest_finds_it() {
        let tmp = test_temp_dir();
        let capture = tmp.path().join("nested").join("snapshot-a.yml");

        let path = save_diff_report(&capture, "## Added\n\n+ button \"Save\" [ref=e7]\n")
            .expect("save diff report");
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            "## Added\n\n+ button \"Save\" [ref=e7]\n"
        );
        assert_eq!(
            latest_diff_report(tmp.path().join("nested").as_path()),
            Some(path.clone())
        );

        // Snapshot files are never mistaken for diff reports.
        assert_eq!(latest_diff_report(&tmp.path().join("empty")), None);
    }

    #[test]
    fn latest_diff_report_returns_the_newest_report() {
        let tmp = test_temp_dir();
        let first = save_diff_report(&tmp.path().join("snapshot-a.yml"), "# 0 added\n")
            .expect("save first report");
        std::thread::sleep(std::time::Duration::from_millis(20));
        let second = save_diff_report(&tmp.path().join("snapshot-b.yml"), "# 1 added\n")
            .expect("save second report");

        assert_ne!(first, second);
        assert_eq!(latest_diff_report(tmp.path()), Some(second));
    }
}
