//! `agent trace <task-id>`: read the full audit trail a run wrote, by the id a caller knows.
//!
//! Issue #652's last gap was asymmetry: the step log (`agent events`) is the *summary* of a run —
//! bounded, previewed, redacted for a live view — while the actual audit trail (the exact prompt
//! the model saw, every tool's complete arguments and complete output, provider usage per request,
//! the page timeline) was written to disk under a directory keyed by the agent's own uuid. A
//! reviewer holding the task id could not find it, and a remote client could not read it at all.
//!
//! The backend now maps `taskId -> run dir` and serves the artifacts (redacted when written, and
//! again when read); this command is the terminal view over that endpoint:
//!
//! - `agent trace <id>` lists what the run left behind (artifact, size, last write);
//! - `agent trace <id> --file cli-tool-trace.jsonl` pages one artifact by line;
//! - `--json` prints the served envelope unchanged, so a script never parses a table.
//!
//! A missing trace is an answer, not a failure: tracing may have been off
//! (`AgentConfig.logInferenceToFile=false`) or the retention policy
//! (`browser4.agent.trace.retentionDays`) may have aged it out. Both are reported as such.

use serde_json::{json, Value};

/// Default page size when reading one artifact (`--limit`).
///
/// Smaller than the server's cap (2000): a terminal shows a few hundred lines before the reader
/// loses the thread, and `--offset` is how they continue.
pub(crate) const TRACE_PAGE_DEFAULT: usize = 200;

/// One artifact of a run's trace directory, as served by `GET /api/commands/{id}/trace`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct TraceFile {
    /// Name relative to the run dir (`cli-tool-trace.jsonl`, `cli-prompt/1.request.json`).
    pub(crate) name: String,
    pub(crate) bytes: u64,
    pub(crate) modified_at: Option<String>,
}

/// The served listing: which run dir, which artifacts.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct TraceListing {
    pub(crate) task_id: String,
    pub(crate) run_dir: String,
    pub(crate) files: Vec<TraceFile>,
}

/// One page of one artifact.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct TracePage {
    pub(crate) task_id: String,
    pub(crate) file: String,
    pub(crate) offset: u64,
    pub(crate) limit: u64,
    pub(crate) total_lines: u64,
    pub(crate) lines: Vec<String>,
}

/// Read a numeric tool parameter that may arrive as a JSON number or as a string.
///
/// `--limit`/`--offset` are parsed by the CLI into numbers, but a caller that builds the tool
/// call by hand (or an older prompt template) may send `"5"`; accepting both keeps the command
/// usable from either side.
pub(crate) fn number_param(params: &Value, key: &str) -> Option<u64> {
    match params.get(key)? {
        Value::Number(number) => number.as_u64(),
        Value::String(text) => text.trim().parse().ok(),
        _ => None,
    }
}
/// True when the backend answered "no trace indexed for this task".
///
/// The endpoint answers a bare 404 (no body), so the caller checks the HTTP status; this helper
/// exists for the case where a JSON error envelope is served instead (an older gateway).
pub(crate) fn is_not_found(status: u16, body: &str) -> bool {
    if status == 404 {
        return true;
    }
    serde_json::from_str::<Value>(body.trim())
        .ok()
        .and_then(|value| value.get("statusCode").and_then(|v| v.as_i64()))
        == Some(404)
}

/// Parse the listing response.
pub(crate) fn parse_listing(body: &str) -> Result<TraceListing, String> {
    let value: Value = serde_json::from_str(body.trim())
        .map_err(|e| format!("could not parse the trace listing as JSON: {e}"))?;
    let files = value
        .get("files")
        .and_then(|v| v.as_array())
        .map(|files| {
            files
                .iter()
                .map(|file| TraceFile {
                    name: file
                        .get("name")
                        .and_then(|v| v.as_str())
                        .unwrap_or_default()
                        .to_string(),
                    bytes: file.get("bytes").and_then(|v| v.as_u64()).unwrap_or(0),
                    modified_at: file
                        .get("modifiedAt")
                        .and_then(|v| v.as_str())
                        .map(str::to_string),
                })
                .filter(|file| !file.name.is_empty())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    Ok(TraceListing {
        task_id: value
            .get("taskId")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        run_dir: value
            .get("runDir")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        files,
    })
}

/// Parse one page response.
pub(crate) fn parse_page(body: &str) -> Result<TracePage, String> {
    let value: Value = serde_json::from_str(body.trim())
        .map_err(|e| format!("could not parse the trace page as JSON: {e}"))?;
    Ok(TracePage {
        task_id: value
            .get("taskId")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        file: value
            .get("file")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        offset: value.get("offset").and_then(|v| v.as_u64()).unwrap_or(0),
        limit: value.get("limit").and_then(|v| v.as_u64()).unwrap_or(0),
        total_lines: value
            .get("totalLines")
            .and_then(|v| v.as_u64())
            .unwrap_or(0),
        lines: value
            .get("lines")
            .and_then(|v| v.as_array())
            .map(|lines| {
                lines
                    .iter()
                    .filter_map(|line| line.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default(),
    })
}

/// Human size of an artifact (`12.4 KB`), the same scale a log reader expects.
pub(crate) fn format_bytes(bytes: u64) -> String {
    const KB: f64 = 1024.0;
    let value = bytes as f64;
    if value < KB {
        format!("{bytes} B")
    } else if value < KB * KB {
        format!("{:.1} KB", value / KB)
    } else if value < KB * KB * KB {
        format!("{:.1} MB", value / (KB * KB))
    } else {
        format!("{:.1} GB", value / (KB * KB * KB))
    }
}

/// The panel `agent trace <id>` prints without `--file`.
///
/// The run dir is part of the answer: a local operator opens the files directly, and the path is
/// what the retention policy ages out, so "why is this trace gone" has a concrete address.
pub(crate) fn render_listing(listing: &TraceListing) -> Vec<String> {
    let mut lines = Vec::new();
    lines.push(format!(
        "Task      {}",
        if listing.task_id.is_empty() {
            "unknown"
        } else {
            listing.task_id.as_str()
        }
    ));
    if !listing.run_dir.is_empty() {
        lines.push(format!("Run dir   {}", listing.run_dir));
    }
    if listing.files.is_empty() {
        lines.push("Artifacts none — tracing was off for this run, or retention already removed it".to_string());
        return lines;
    }
    lines.push(format!("Artifacts {} (read one with --file <name>)", listing.files.len()));
    lines.push(String::new());
    let width = listing
        .files
        .iter()
        .map(|file| file.name.chars().count())
        .max()
        .unwrap_or(0);
    for file in &listing.files {
        let modified = file
            .modified_at
            .as_deref()
            .map(short_timestamp)
            .unwrap_or_else(|| "-".to_string());
        lines.push(format!(
            "  {:<width$}  {:>9}  {}",
            file.name,
            format_bytes(file.bytes),
            modified,
            width = width
        ));
    }
    lines
}

/// The panel `agent trace <id> --file <name>` prints: a page of the artifact, plus where it is.
///
/// The lines are printed **verbatim** (the server already redacted them): a trace is a forensic
/// artifact, and reformatting it would make "the file on disk" and "what the CLI showed" differ.
pub(crate) fn render_page(page: &TracePage) -> Vec<String> {
    let mut lines = Vec::new();
    let first = if page.lines.is_empty() {
        page.offset
    } else {
        page.offset + 1
    };
    let last = page.offset + page.lines.len() as u64;
    lines.push(format!(
        "{}  lines {}-{} of {}",
        page.file, first, last, page.total_lines
    ));
    lines.extend(page.lines.iter().cloned());
    if last < page.total_lines {
        lines.push(format!(
            "(continue with --offset {} --limit {})",
            last,
            if page.limit == 0 {
                TRACE_PAGE_DEFAULT as u64
            } else {
                page.limit
            }
        ));
    }
    lines
}

/// The `--json` shape of a listing: the served envelope, unchanged.
pub(crate) fn listing_json(listing: &TraceListing) -> Value {
    json!({
        "taskId": listing.task_id,
        "runDir": listing.run_dir,
        "files": listing.files.iter().map(|file| json!({
            "name": file.name,
            "bytes": file.bytes,
            "modifiedAt": file.modified_at,
            "size": format_bytes(file.bytes),
        })).collect::<Vec<_>>(),
    })
}

/// The `--json` shape of a page: the served envelope, unchanged (plus nothing).
pub(crate) fn page_json(page: &TracePage) -> Value {
    json!({
        "taskId": page.task_id,
        "file": page.file,
        "offset": page.offset,
        "limit": page.limit,
        "totalLines": page.total_lines,
        "lines": page.lines,
    })
}

/// `2026-11-22T04:12:31.123Z` → `2026-11-22 04:12:31` — the ISO form without the noise a table
/// column cannot afford.
fn short_timestamp(raw: &str) -> String {
    match raw.find('T') {
        Some(index) => {
            let date = &raw[..index];
            let rest = &raw[index + 1..];
            let time = rest.split(['.', 'Z', '+']).next().unwrap_or(rest);
            format!("{date} {time}")
        }
        None => raw.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_listing_renders_the_run_dir_and_every_artifact() {
        let listing = TraceListing {
            task_id: "agent-1".to_string(),
            run_dir: "/data/logs/agent/2026-11-22/aaaa".to_string(),
            files: vec![
                TraceFile {
                    name: "cli-tool-trace.jsonl".to_string(),
                    bytes: 12_697,
                    modified_at: Some("2026-11-22T04:12:31.123Z".to_string()),
                },
                TraceFile {
                    name: "cli-prompt/1.request.json".to_string(),
                    bytes: 512,
                    modified_at: None,
                },
            ],
        };

        let text = render_listing(&listing).join("\n");

        assert!(text.contains("agent-1"), "{text}");
        assert!(text.contains("/data/logs/agent/2026-11-22/aaaa"), "{text}");
        assert!(text.contains("cli-tool-trace.jsonl"), "{text}");
        assert!(text.contains("12.4 KB"), "{text}");
        assert!(text.contains("2026-11-22 04:12:31"), "{text}");
        assert!(text.contains("cli-prompt/1.request.json"), "{text}");
        assert!(text.contains("--file"), "the panel says how to read one: {text}");
    }

    #[test]
    fn an_empty_listing_explains_why_instead_of_showing_a_bare_header() {
        let listing = TraceListing {
            task_id: "agent-1".to_string(),
            run_dir: "/data/logs/agent/x".to_string(),
            files: Vec::new(),
        };

        let text = render_listing(&listing).join("\n");

        assert!(text.contains("tracing was off"), "{text}");
        assert!(text.contains("retention"), "{text}");
    }

    #[test]
    fn a_page_renders_its_lines_verbatim_and_says_how_to_continue() {
        let page = TracePage {
            task_id: "agent-1".to_string(),
            file: "cli-tool-trace.jsonl".to_string(),
            offset: 0,
            limit: 2,
            total_lines: 5,
            lines: vec![
                r#"{"seq":1,"tool":"coding.read"}"#.to_string(),
                r#"{"seq":2,"tool":"coding.write"}"#.to_string(),
            ],
        };

        let lines = render_page(&page);

        assert_eq!(
            lines[0], "cli-tool-trace.jsonl  lines 1-2 of 5",
            "the page says where it sits: {lines:?}"
        );
        assert_eq!(lines[1], page.lines[0], "the payload is not reformatted");
        assert_eq!(lines[2], page.lines[1]);
        assert!(
            lines[3].contains("--offset 2"),
            "a partial page says how to continue: {lines:?}"
        );
    }

    #[test]
    fn a_last_page_does_not_suggest_continuing() {
        let page = TracePage {
            task_id: "agent-1".to_string(),
            file: "cli-usage.jsonl".to_string(),
            offset: 3,
            limit: 200,
            total_lines: 5,
            lines: vec!["{}".to_string(), "{}".to_string()],
        };

        let lines = render_page(&page);

        assert!(!lines.iter().any(|line| line.contains("--offset")), "{lines:?}");
    }

    #[test]
    fn an_empty_page_is_rendered_as_such() {
        let page = TracePage {
            task_id: "agent-1".to_string(),
            file: "cli-usage.jsonl".to_string(),
            offset: 10,
            limit: 200,
            total_lines: 10,
            lines: Vec::new(),
        };

        let lines = render_page(&page);

        assert_eq!(lines.len(), 1, "nothing to print but the position: {lines:?}");
        assert!(lines[0].contains("lines 10-10 of 10"), "{lines:?}");
    }

    #[test]
    fn sizes_are_rendered_at_a_readable_scale() {
        assert_eq!(format_bytes(512), "512 B");
        assert_eq!(format_bytes(12_697), "12.4 KB");
        assert_eq!(format_bytes(5 * 1024 * 1024), "5.0 MB");
    }

    #[test]
    fn parsing_tolerates_a_missing_field_instead_of_failing_the_view() {
        let listing = parse_listing(r#"{"taskId":"agent-1","runDir":"/x"}"#).unwrap();
        assert_eq!(listing.task_id, "agent-1");
        assert!(listing.files.is_empty());

        let page = parse_page(r#"{"file":"a.jsonl","lines":["{}"]}"#).unwrap();
        assert_eq!(page.file, "a.jsonl");
        assert_eq!(page.lines, vec!["{}".to_string()]);
        assert_eq!(page.total_lines, 0);
    }

    #[test]
    fn a_malformed_body_is_a_loud_error() {
        assert!(parse_listing("not json").is_err());
        assert!(parse_page("<html>").is_err());
    }

    #[test]
    fn only_a_404_means_the_trace_is_absent() {
        assert!(is_not_found(404, ""));
        assert!(is_not_found(200, r#"{"statusCode":404}"#));
        assert!(!is_not_found(200, r#"{"taskId":"agent-1"}"#));
        assert!(!is_not_found(500, ""));
    }

    #[test]
    fn the_json_shapes_keep_the_served_field_names() {
        let listing = listing_json(&TraceListing {
            task_id: "agent-1".to_string(),
            run_dir: "/x".to_string(),
            files: vec![TraceFile {
                name: "cli-usage.jsonl".to_string(),
                bytes: 2_048,
                modified_at: Some("2026-11-22T00:00:00Z".to_string()),
            }],
        });
        assert_eq!(listing["taskId"], "agent-1");
        assert_eq!(listing["files"][0]["name"], "cli-usage.jsonl");
        assert_eq!(listing["files"][0]["size"], "2.0 KB");

        let page = page_json(&TracePage {
            task_id: "agent-1".to_string(),
            file: "cli-usage.jsonl".to_string(),
            offset: 0,
            limit: 200,
            total_lines: 1,
            lines: vec!["{}".to_string()],
        });
        assert_eq!(page["totalLines"], 1);
        assert_eq!(page["lines"][0], "{}");
    }
}
