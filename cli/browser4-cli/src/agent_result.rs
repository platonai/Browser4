//! Human rendering of a finished agent task's payload.
//!
//! `command_result` answers with the shared task envelope plus a `result` object
//! (the backend's `CommandResult`). Printing that text verbatim — what
//! `agent run -i` and `agent run --follow` used to do — is one 2–5 KB line of
//! JSON: the summary and the findings are in there, but a reader has to parse
//! JSON to find them, and the envelope's plumbing (`statusTool`, `resultTool`,
//! `processed`, …) is pure noise to a human.
//!
//! This module renders the same payload as sections instead. Everything here is
//! a pure function of (payload text, width, line cap): no terminal probing and no
//! clock, so the same body always renders byte-identically — which is what keeps
//! a test assertion (and a CI log) stable.
//!
//! The raw text stays available for machines: `agent result --json`,
//! `/result --json`, and `--ndjson` are untouched.

use serde_json::Value;

/// Width used when `COLUMNS` is unset or unusable.
pub(crate) const DEFAULT_WIDTH: usize = 100;
const MIN_WIDTH: usize = 40;
const MAX_WIDTH: usize = 400;

/// Lines a rendered payload may occupy before it is folded.
pub(crate) const DEFAULT_MAX_LINES: usize = 40;

/// Per-section caps. Every cap is followed by a "… and N more" line, so a
/// folded section never looks like the whole payload.
const FINDINGS_SHOWN: usize = 10;
const FILES_SHOWN: usize = 10;
const PROBLEMS_SHOWN: usize = 5;
const LINKS_SHOWN: usize = 5;
const ROWS_SHOWN: usize = 20;

/// Cap on one table cell, so a long value cannot blow up the layout.
const CELL_PREVIEW: usize = 80;

/// Cap on the unknown-fields block, in characters (it is JSON, not prose).
const OTHERS_PREVIEW_CHARS: usize = 1_200;
/// Cap on the unknown-fields block, in lines.
const OTHERS_LINES_SHOWN: usize = 12;

/// Keys of the shared task envelope: transport plumbing, never content.
const ENVELOPE_KEYS: &[&str] = &[
    "taskId",
    "status",
    "progress",
    "processed",
    "total",
    "elapsedMs",
    "statusCode",
    "message",
    "error",
    "statusTool",
    "resultTool",
    "cancelTool",
    "errorCode",
    "resumable",
    "effectiveTokenBudget",
    "cancelled",
    "reason",
];

/// Keys of the backend's `CommandResult` this renderer knows how to present.
const RESULT_KEYS: &[&str] = &[
    "summary",
    "pageSummary",
    "fields",
    "links",
    "xsqlResultSet",
    "keyFindings",
    "filesChanged",
    "problems",
    "workspace",
    "errorCode",
    "resumable",
    "effectiveTokenBudget",
];

/// How a payload is rendered.
pub(crate) struct RenderOptions {
    /// Maximum width of a rendered line.
    width: usize,
    /// Lines before the payload is folded.
    max_lines: usize,
    /// Emit ANSI bold around section titles.
    color: bool,
    /// The command the fold line tells the reader to run for the whole
    /// envelope. It differs per entry point: `/result --json` inside `-i`,
    /// `agent result <id> --json` everywhere else.
    json_hint: String,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            width: width_from_columns(std::env::var("COLUMNS").ok().as_deref()),
            max_lines: DEFAULT_MAX_LINES,
            color: false,
            json_hint: "/result --json".to_string(),
        }
    }
}

impl RenderOptions {
    pub(crate) fn with_color(mut self, color: bool) -> Self {
        self.color = color;
        self
    }

    /// `--pretty`: keep every line instead of folding the payload.
    ///
    /// Folding is a convenience of the default human path; `--pretty` is the
    /// explicit "show me everything" switch, so it must not hide anything.
    pub(crate) fn with_full(mut self, full: bool) -> Self {
        if full {
            self.max_lines = usize::MAX;
        }
        self
    }

    /// Point the fold line at a command that exists for this entry point.
    pub(crate) fn with_json_hint(mut self, hint: impl Into<String>) -> Self {
        self.json_hint = hint.into();
        self
    }
}

/// The render width for a `COLUMNS` value.
///
/// Not a terminal probe on purpose: the width must be the same in a pipe, in CI
/// and in a test, and `COLUMNS` is the one portable way to say it.
pub(crate) fn width_from_columns(raw: Option<&str>) -> usize {
    raw.and_then(|value| value.trim().parse::<usize>().ok())
        .map(|width| width.clamp(MIN_WIDTH, MAX_WIDTH))
        .unwrap_or(DEFAULT_WIDTH)
}

/// Render a `command_result` body as human lines.
///
/// The body is whatever the MCP tool answered: normally a JSON envelope with a
/// `result` object, but also a bare payload object, a JSON string, plain text
/// (a mock or a backend that predates the envelope), or `null`.
pub(crate) fn render_result_text(
    task_id: &str,
    body: &str,
    options: &RenderOptions,
) -> Vec<String> {
    let text = body.trim();
    if text.is_empty() {
        return missing_payload(task_id);
    }

    let parsed = match serde_json::from_str::<Value>(text) {
        Ok(value) => value,
        // Plain text: it IS the result, so show it as text instead of wrapping
        // it in a section that would claim a structure it does not have.
        Err(_) => return wrap(text, options.width),
    };

    let mut lines = render_value(task_id, &parsed, options);
    fold(&mut lines, options.max_lines, &options.json_hint);
    // No trailing whitespace: it is invisible, survives a copy/paste, and the
    // table formatter pads its last column.
    for line in &mut lines {
        if line.ends_with(' ') || line.ends_with('\t') {
            *line = line.trim_end().to_string();
        }
    }
    lines
}

fn render_value(task_id: &str, value: &Value, options: &RenderOptions) -> Vec<String> {
    match value {
        Value::Null => missing_payload(task_id),
        // The quotes are JSON syntax, not content.
        Value::String(text) => wrap(text, options.width),
        Value::Object(map) => {
            // Envelope → its `result`; a bare payload object is the result.
            let result = map
                .get("result")
                .filter(|value| !value.is_null())
                .unwrap_or(value);
            match result {
                Value::Object(result_map) => render_sections(task_id, result_map, options),
                Value::String(text) => wrap(text, options.width),
                Value::Null => missing_payload(task_id),
                other => pretty(other, options),
            }
        }
        Value::Array(items) => render_array(items, options),
        other => vec![other.to_string()],
    }
}

fn render_sections(
    task_id: &str,
    result: &serde_json::Map<String, Value>,
    options: &RenderOptions,
) -> Vec<String> {
    let mut lines = Vec::new();

    // `summary` is the agent's own prose; `pageSummary` is the fallback lead.
    let summary = string_of(result.get("summary"));
    let page_summary = string_of(result.get("pageSummary"));
    if let Some(text) = summary.as_deref().or(page_summary.as_deref()) {
        section(
            &mut lines,
            "Summary",
            wrap_indented(text, 2, options.width),
            options,
        );
    }

    let findings = strings_of(result.get("keyFindings"));
    if !findings.is_empty() {
        let mut body = Vec::new();
        for finding in findings.iter().take(FINDINGS_SHOWN) {
            body.extend(icon_bullet("•", finding, options.width));
        }
        if findings.len() > FINDINGS_SHOWN {
            body.push(format!("  … and {} more", findings.len() - FINDINGS_SHOWN));
        }
        section(
            &mut lines,
            &format!("Key findings ({})", findings.len()),
            body,
            options,
        );
    }

    let files = strings_of(result.get("filesChanged"));
    let workspace = string_of(result.get("workspace"));
    if !files.is_empty() {
        let title = match workspace.as_deref() {
            Some(workspace) => format!(
                "Files written ({}) · workspace: {workspace}",
                files.len()
            ),
            None => format!("Files written ({})", files.len()),
        };
        let mut body = Vec::new();
        for file in files.iter().take(FILES_SHOWN) {
            body.push(format!("  {}", clip(file, options.width.saturating_sub(2))));
        }
        if files.len() > FILES_SHOWN {
            body.push(format!("  … and {} more", files.len() - FILES_SHOWN));
        }
        section(&mut lines, &title, body, options);
    } else if let Some(workspace) = workspace {
        section(
            &mut lines,
            "Workspace",
            vec![format!("  {}", clip(&workspace, options.width.saturating_sub(2)))],
            options,
        );
    }

    let problems = strings_of(result.get("problems"));
    if !problems.is_empty() {
        let mut body = Vec::new();
        for problem in problems.iter().take(PROBLEMS_SHOWN) {
            body.extend(icon_bullet("⚠", problem, options.width));
        }
        if problems.len() > PROBLEMS_SHOWN {
            body.push(format!("  … and {} more", problems.len() - PROBLEMS_SHOWN));
        }
        section(
            &mut lines,
            &format!("Problems ({})", problems.len()),
            body,
            options,
        );
    }

    if let Some(Value::Object(fields)) = result.get("fields") {
        if !fields.is_empty() {
            let rows: Vec<Value> = fields
                .iter()
                .map(|(key, value)| {
                    let mut row = serde_json::Map::new();
                    row.insert("field".to_string(), Value::String(key.clone()));
                    row.insert("value".to_string(), value.clone());
                    Value::Object(row)
                })
                .collect();
            section(
                &mut lines,
                &format!("Data ({} field(s))", fields.len()),
                table(&rows, options),
                options,
            );
        }
    }

    if let Some(Value::Array(rows)) = result.get("xsqlResultSet") {
        let objects = rows.iter().filter(|row| row.is_object()).count();
        if objects > 0 {
            let columns = rows
                .iter()
                .find_map(|row| row.as_object().map(|object| object.len()))
                .unwrap_or(0);
            let shown: Vec<Value> = rows.iter().take(ROWS_SHOWN).cloned().collect();
            let mut body = table(&shown, options);
            if rows.len() > ROWS_SHOWN {
                body.push(format!("  … {} more row(s)", rows.len() - ROWS_SHOWN));
            }
            section(
                &mut lines,
                &format!("Data ({} rows × {} columns)", rows.len(), columns),
                body,
                options,
            );
        }
    }

    let links = strings_of(result.get("links"));
    if !links.is_empty() {
        let mut body: Vec<String> = links
            .iter()
            .take(LINKS_SHOWN)
            .map(|link| format!("  {}", clip(link, options.width.saturating_sub(2))))
            .collect();
        if links.len() > LINKS_SHOWN {
            body.push(format!("  … and {} more", links.len() - LINKS_SHOWN));
        }
        section(&mut lines, &format!("Links ({})", links.len()), body, options);
    }

    // Anything the backend adds that this renderer does not know yet stays
    // visible, but folded: a new field must never disappear silently.
    let mut others = serde_json::Map::new();
    for (key, value) in result {
        if value.is_null() || is_known_key(key) {
            continue;
        }
        others.insert(key.clone(), value.clone());
    }
    if !others.is_empty() {
        let mut body = pretty(&Value::Object(others.clone()), options);
        if body.len() > OTHERS_LINES_SHOWN {
            let total = body.len();
            body.truncate(OTHERS_LINES_SHOWN);
            body.push(format!("  … {} more line(s)", total - OTHERS_LINES_SHOWN));
        }
        section(
            &mut lines,
            &format!("Other ({} field(s))", others.len()),
            body,
            options,
        );
    }

    if lines.is_empty() {
        lines.extend(missing_payload(task_id));
    }
    lines
}

fn render_array(items: &[Value], options: &RenderOptions) -> Vec<String> {
    let all_objects = !items.is_empty() && items.iter().all(|item| item.is_object());
    if !all_objects {
        return pretty(&Value::Array(items.to_vec()), options);
    }

    let columns = items
        .iter()
        .find_map(|item| item.as_object().map(|object| object.len()))
        .unwrap_or(0);
    let shown: Vec<Value> = items.iter().take(ROWS_SHOWN).cloned().collect();
    let mut lines = vec![format!(
        "Data ({} rows × {} columns)",
        items.len(),
        columns
    )];
    lines.extend(table(&shown, options));
    if items.len() > ROWS_SHOWN {
        lines.push(format!("  … {} more row(s)", items.len() - ROWS_SHOWN));
    }
    lines
}

fn is_known_key(key: &str) -> bool {
    RESULT_KEYS.iter().any(|known| *known == key) || ENVELOPE_KEYS.iter().any(|known| *known == key)
}

fn section(lines: &mut Vec<String>, title: &str, body: Vec<String>, options: &RenderOptions) {
    if !lines.is_empty() {
        lines.push(String::new());
    }
    lines.push(paint(title, options.color));
    lines.extend(body);
}

/// Fold a payload that would flood the terminal, and say how to see the rest.
fn fold(lines: &mut Vec<String>, max_lines: usize, json_hint: &str) {
    let max_lines = max_lines.max(4);
    if lines.len() <= max_lines {
        return;
    }
    let total = lines.len();
    let keep = max_lines - 1;
    lines.truncate(keep);
    lines.push(format!(
        "  … {} more line(s) — {json_hint} prints the whole envelope",
        total - keep
    ));
}

fn missing_payload(task_id: &str) -> Vec<String> {
    vec![
        "(no result payload)".to_string(),
        format!("  run `agent status {task_id}` to see the task envelope"),
    ]
}

fn paint(text: &str, color: bool) -> String {
    if !color {
        return text.to_string();
    }
    format!("\x1b[1m{text}\x1b[0m")
}

fn string_of(value: Option<&Value>) -> Option<String> {
    value
        .and_then(|value| value.as_str())
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_string)
}

fn strings_of(value: Option<&Value>) -> Vec<String> {
    value
        .and_then(|value| value.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.as_str())
                .map(str::trim)
                .filter(|text| !text.is_empty())
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

/// A table row set rendered by the CLI's existing aligned-table formatter, with
/// cells capped so a long value cannot push the table off a narrow terminal.
fn table(rows: &[Value], options: &RenderOptions) -> Vec<String> {
    let columns = rows
        .iter()
        .find_map(|row| row.as_object().map(|object| object.len()))
        .unwrap_or(0)
        .max(1);
    let max_cell = (options.width.saturating_sub(4) / columns).clamp(8, CELL_PREVIEW);
    let capped: Vec<Value> = rows.iter().map(|row| cap_cells(row, max_cell)).collect();
    crate::format_table(&capped)
        .lines()
        .map(|line| clip(line, options.width))
        .collect()
}

fn cap_cells(row: &Value, max: usize) -> Value {
    match row {
        Value::Object(map) => {
            let mut out = serde_json::Map::new();
            for (key, value) in map {
                out.insert(key.clone(), Value::String(cell_text(value, max)));
            }
            Value::Object(out)
        }
        other => Value::String(cell_text(other, max)),
    }
}

fn cell_text(value: &Value, max: usize) -> String {
    match value {
        Value::String(text) => clip(text, max),
        Value::Null => String::new(),
        Value::Bool(flag) => flag.to_string(),
        Value::Number(number) => number.to_string(),
        other => clip(&other.to_string(), max),
    }
}

/// A JSON block for payloads this renderer cannot present as sections.
fn pretty(value: &Value, options: &RenderOptions) -> Vec<String> {
    let text = serde_json::to_string_pretty(value).unwrap_or_else(|_| value.to_string());
    let text = clip(&text, OTHERS_PREVIEW_CHARS);
    text.lines()
        .map(|line| clip(line, options.width))
        .collect()
}

/// Truncate on a char boundary (the shared helper, so the ellipsis is uniform).
fn clip(text: &str, max_chars: usize) -> String {
    crate::agent_interactive::truncate(text, max_chars.max(8))
}

/// Wrap text to [width], keeping the author's own line breaks.
///
/// Shared with the live step renderer (`agent_interactive`), so a wrapped
/// payload section and a wrapped model line break the same way.
pub(crate) fn wrap(text: &str, width: usize) -> Vec<String> {
    let width = width.max(10);
    let mut out = Vec::new();
    for raw in text.split('\n') {
        wrap_one(raw, width, &mut out);
    }
    out
}

fn wrap_indented(text: &str, indent: usize, width: usize) -> Vec<String> {
    let pad = " ".repeat(indent);
    let available = width.saturating_sub(indent).max(10);
    wrap(text, available)
        .into_iter()
        .map(|line| {
            if line.is_empty() {
                String::new()
            } else {
                format!("{pad}{line}")
            }
        })
        .collect()
}

fn icon_bullet(icon: &str, text: &str, width: usize) -> Vec<String> {
    let prefix = format!("  {icon} ");
    let indent = prefix.chars().count();
    let available = width.saturating_sub(indent).max(10);
    let mut lines = Vec::new();
    for (index, line) in wrap(text, available).into_iter().enumerate() {
        if index == 0 {
            lines.push(format!("{prefix}{line}"));
        } else if line.is_empty() {
            lines.push(String::new());
        } else {
            lines.push(format!("{}{line}", " ".repeat(indent)));
        }
    }
    lines
}

fn wrap_one(line: &str, width: usize, out: &mut Vec<String>) {
    let line = line.trim_end();
    if line.trim().is_empty() {
        out.push(String::new());
        return;
    }

    let mut current = String::new();
    for word in line.split(' ') {
        let word_width = word.chars().count();
        // A single word wider than the line is broken, never silently overflowed.
        if word_width > width {
            if !current.is_empty() {
                out.push(std::mem::take(&mut current));
            }
            for chunk in chunk_chars(word, width) {
                out.push(chunk);
            }
            continue;
        }
        let projected = if current.is_empty() {
            word_width
        } else {
            current.chars().count() + 1 + word_width
        };
        if projected > width && !current.is_empty() {
            out.push(std::mem::take(&mut current));
        }
        if !current.is_empty() {
            current.push(' ');
        }
        current.push_str(word);
    }
    if !current.is_empty() {
        out.push(current);
    }
}

fn chunk_chars(word: &str, width: usize) -> Vec<String> {
    let mut chunks = Vec::new();
    let mut current = String::new();
    for ch in word.chars() {
        if current.chars().count() >= width {
            chunks.push(std::mem::take(&mut current));
        }
        current.push(ch);
    }
    if !current.is_empty() {
        chunks.push(current);
    }
    chunks
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn options(width: usize) -> RenderOptions {
        RenderOptions {
            width,
            ..RenderOptions::default()
        }
    }

    fn render(body: String, width: usize) -> Vec<String> {
        render_result_text("task-1", &body, &options(width))
    }

    fn rendered(body: String, width: usize) -> String {
        render(body, width).join("\n")
    }

    #[test]
    fn renders_a_completion_report_as_sections() {
        let body = json!({
            "taskId": "task-1",
            "status": "done",
            "processed": 12,
            "statusCode": 200,
            "statusTool": "command_status",
            "resultTool": "command_result",
            "cancelTool": "command_cancel",
            "result": {
                "summary": "Fixed the module build.",
                "keyFindings": ["The BOM pinned an older zip4j", "No source change was needed"],
                "filesChanged": ["browser4-dependencies/pom.xml"],
                "problems": ["The CI gate was not run"],
                "workspace": "~/.browser4/agent/workspace.task-1"
            }
        })
        .to_string();

        let text = rendered(body, 100);

        assert!(text.starts_with("Summary"), "{text}");
        assert!(text.contains("Fixed the module build."), "{text}");
        assert!(text.contains("Key findings (2)"), "{text}");
        assert!(text.contains("• The BOM pinned an older zip4j"), "{text}");
        assert!(
            text.contains("Files written (1) · workspace: ~/.browser4/agent/workspace.task-1"),
            "{text}"
        );
        assert!(text.contains("browser4-dependencies/pom.xml"), "{text}");
        assert!(text.contains("Problems (1)"), "{text}");
        assert!(text.contains("⚠ The CI gate was not run"), "{text}");
    }

    /// The whole point: the envelope's transport plumbing never reaches a human.
    #[test]
    fn never_prints_the_envelope_plumbing() {
        let body = json!({
            "taskId": "task-1",
            "status": "done",
            "processed": 12,
            "statusCode": 200,
            "message": "Agent run completed, finalizing",
            "statusTool": "command_status",
            "resultTool": "command_result",
            "cancelTool": "command_cancel",
            "result": {"summary": "done"}
        })
        .to_string();

        let text = rendered(body, 100);

        for plumbing in [
            "statusTool",
            "resultTool",
            "cancelTool",
            "processed",
            "statusCode",
            "\"taskId\"",
        ] {
            assert!(!text.contains(plumbing), "{plumbing} leaked into {text}");
        }
    }

    #[test]
    fn summary_wraps_without_breaking_multibyte() {
        let summary = "这是一段很长的中文摘要，用来验证换行不会破坏多字节字符。".repeat(4);
        let body = json!({ "result": { "summary": summary.clone() } }).to_string();

        let lines = render(body, 40);
        for line in &lines {
            assert!(line.chars().count() <= 40, "line too wide: {line:?}");
            assert!(line.is_char_boundary(line.len()));
        }
        assert!(lines.len() > 3, "the summary should wrap: {lines:?}");
        // Wrapping must not lose a character of a word it had to break.
        let rebuilt: String = lines.iter().skip(1).map(|line| line.trim_start()).collect();
        assert_eq!(rebuilt, summary);
    }

    #[test]
    fn page_summary_leads_when_there_is_no_summary() {
        let body = json!({"result": {"pageSummary": "The pricing page."}}).to_string();
        assert_eq!(
            render(body, 100),
            vec!["Summary".to_string(), "  The pricing page.".to_string()]
        );
    }

    #[test]
    fn renders_fields_as_a_table() {
        let body = json!({"result": {"fields": {"title": "Pricing", "price": "19"}}}).to_string();
        let text = rendered(body, 100);

        assert!(text.contains("Data (2 field(s))"), "{text}");
        assert!(text.contains("field"), "{text}");
        assert!(text.contains("Pricing"), "{text}");
        assert!(text.contains("19"), "{text}");
    }

    #[test]
    fn renders_xsql_rows_as_a_table_and_caps_them() {
        let rows: Vec<Value> = (0..25)
            .map(|index| json!({"plan": format!("plan-{index}"), "price": index}))
            .collect();
        let body = json!({"result": {"xsqlResultSet": rows}}).to_string();
        let text = rendered(body, 100);

        assert!(text.contains("Data (25 rows × 2 columns)"), "{text}");
        assert!(text.contains("plan-0"), "{text}");
        assert!(text.contains("5 more row(s)"), "{text}");
    }

    #[test]
    fn renders_a_plain_string_payload_without_json_quotes() {
        assert_eq!(
            render(r#""interactive result""#.to_string(), 100),
            vec!["interactive result".to_string()]
        );
    }

    #[test]
    fn renders_a_non_json_body_verbatim() {
        assert_eq!(
            render("result for agent-task-1".to_string(), 100),
            vec!["result for agent-task-1".to_string()]
        );
    }

    /// A mock (and an older backend) answers with the payload itself, not the
    /// envelope.
    #[test]
    fn renders_a_bare_payload_object() {
        assert_eq!(
            render(r#"{"summary":"Fixed"}"#.to_string(), 100),
            vec!["Summary".to_string(), "  Fixed".to_string()]
        );
    }

    #[test]
    fn reports_a_missing_payload_with_a_hint() {
        for body in [
            r#"{"taskId":"task-1","status":"done","result":null}"#,
            "null",
            "{}",
        ] {
            let lines = render(body.to_string(), 100);
            assert!(lines[0].contains("(no result payload)"), "{body} → {lines:?}");
            assert!(lines[1].contains("agent status task-1"), "{body} → {lines:?}");
        }
    }

    #[test]
    fn unknown_fields_are_kept_visible() {
        let body = json!({"result": {"summary": "done", "rows": [1, 2]}}).to_string();
        let text = rendered(body, 100);

        assert!(text.contains("Other (1 field(s))"), "{text}");
        assert!(text.contains("rows"), "{text}");
    }

    #[test]
    fn folds_beyond_the_line_cap_and_points_at_the_machine_output() {
        let findings: Vec<String> = (0..10).map(|index| format!("finding {index}")).collect();
        let body = json!({"result": {"summary": "s", "keyFindings": findings}}).to_string();
        let lines = render_result_text(
            "task-1",
            &body,
            &RenderOptions {
                width: 100,
                max_lines: 6,
                color: false,
                json_hint: "/result --json".to_string(),
            },
        );

        assert_eq!(lines.len(), 6, "{lines:?}");
        let last = lines.last().expect("a folded payload has a tail line");
        assert!(last.contains("more line(s)"), "{last}");
        assert!(last.contains("/result --json"), "{last}");
    }

    /// The fold line must name the command the reader can actually run: only
    /// `-i` has `/result`, every other entry point wants `agent result <id>`.
    #[test]
    fn the_fold_line_names_the_json_command_of_its_entry_point() {
        let findings: Vec<String> = (0..10).map(|index| format!("finding {index}")).collect();
        let body = json!({"result": {"summary": "s", "keyFindings": findings}}).to_string();
        let lines = render_result_text(
            "task-1",
            &body,
            &RenderOptions {
                width: 100,
                max_lines: 6,
                color: false,
                json_hint: "agent result task-1 --json".to_string(),
            },
        );

        let last = lines.last().expect("a folded payload has a tail line");
        assert!(last.contains("agent result task-1 --json"), "{last}");
        assert!(!last.contains("/result --json"), "{last}");
    }

    /// `--pretty` is the explicit "show me everything" switch: the same payload
    /// the default cap folds must come out whole.
    #[test]
    fn full_rendering_does_not_fold_the_payload() {
        let findings: Vec<String> = (0..10).map(|index| format!("finding {index}")).collect();
        let body = json!({"result": {"summary": "s", "keyFindings": findings}}).to_string();
        let options = RenderOptions {
            width: 100,
            max_lines: 6,
            color: false,
            json_hint: "/result --json".to_string(),
        }
        .with_full(true);
        let lines = render_result_text("task-1", &body, &options);

        assert!(lines.len() > 6, "{lines:?}");
        assert!(
            !lines.iter().any(|line| line.contains("more line(s)")),
            "a full render must not claim there is more: {lines:?}"
        );
    }

    #[test]
    fn wide_cells_are_capped_to_the_width() {
        let body = json!({"result": {"fields": {"title": "x".repeat(500)}}}).to_string();
        for line in render(body, 60) {
            assert!(line.chars().count() <= 60, "line too wide: {line:?}");
        }
    }

    #[test]
    fn color_paints_only_section_titles() {
        let body = json!({"result": {"summary": "done", "problems": ["p"]}}).to_string();
        let lines = render_result_text(
            "task-1",
            &body,
            &RenderOptions {
                width: 100,
                max_lines: DEFAULT_MAX_LINES,
                color: true,
                json_hint: "/result --json".to_string(),
            },
        );

        assert!(lines[0].starts_with("\x1b[1m"), "{lines:?}");
        // Only section titles are painted; a body line never is.
        for line in lines.iter().skip(1) {
            if line.contains('\x1b') {
                assert!(line.starts_with("\x1b[1m"), "painted body line: {line:?}");
                assert!(line.ends_with("\x1b[0m"), "unterminated paint: {line:?}");
            }
        }
    }

    #[test]
    fn plain_output_carries_no_ansi() {
        let body = json!({"result": {"summary": "done"}}).to_string();
        for line in render(body, 100) {
            assert!(!line.contains('\x1b'), "{line:?}");
        }
    }

    #[test]
    fn rendered_lines_carry_no_trailing_whitespace() {
        let body = json!({"result": {"fields": {"a": "1", "bb": "22"}}}).to_string();
        for line in render(body, 100) {
            assert_eq!(line.trim_end(), line, "trailing whitespace in {line:?}");
        }
    }

    #[test]
    fn width_from_columns_defaults_and_clamps() {
        assert_eq!(width_from_columns(None), DEFAULT_WIDTH);
        assert_eq!(width_from_columns(Some("120")), 120);
        assert_eq!(width_from_columns(Some(" 88 ")), 88);
        assert_eq!(width_from_columns(Some("10")), MIN_WIDTH);
        assert_eq!(width_from_columns(Some("9999")), MAX_WIDTH);
        assert_eq!(width_from_columns(Some("wide")), DEFAULT_WIDTH);
    }
}
