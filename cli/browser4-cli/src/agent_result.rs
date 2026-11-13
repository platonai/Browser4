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
/// The model's proposed follow-ups are a menu hint, not a report.
const SUGGESTIONS_SHOWN: usize = 3;

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
    "nextSuggestions",
    "filesChanged",
    "problems",
    "workspace",
    "isolatedWorkspace",
    "isolation",
    "merge",
    "patch",
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
    /// payload. It differs per entry point: `/result --full` inside `-i`,
    /// `agent result <id> --pretty` everywhere else.
    json_hint: String,
    /// `--full`: no folding and no per-section caps.
    ///
    /// A separate flag from [`Self::max_lines`] because the caps are a second,
    /// independent way of hiding content: a payload with 40 findings fits in 60
    /// lines and was still cut to 10 unless this is set.
    full: bool,
    /// Render the model's own `nextSuggestions` as a trailing section.
    ///
    /// Off inside `agent run -i`: the session prints the same list as its numbered
    /// follow-up menu immediately after, and showing it twice reads as two
    /// different offers.
    show_suggestions: bool,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            width: width_from_columns(std::env::var("COLUMNS").ok().as_deref()),
            max_lines: DEFAULT_MAX_LINES,
            color: false,
            json_hint: "/result --full".to_string(),
            full: false,
            show_suggestions: true,
        }
    }
}

impl RenderOptions {
    pub(crate) fn with_color(mut self, color: bool) -> Self {
        self.color = color;
        self
    }

    /// `--full` (or the global `--pretty`): keep every line *and* every entry
    /// instead of folding the payload.
    ///
    /// Folding is a convenience of the default human path; this is the explicit
    /// "show me everything" switch, so it must not hide anything — neither the
    /// tail of the payload nor the 11th finding.
    pub(crate) fn with_full(mut self, full: bool) -> Self {
        if full {
            self.full = true;
            self.max_lines = usize::MAX;
        }
        self
    }

    /// Point the fold line at a command that exists for this entry point.
    pub(crate) fn with_json_hint(mut self, hint: impl Into<String>) -> Self {
        self.json_hint = hint.into();
        self
    }

    /// Render (or suppress) the model's own suggested next steps.
    pub(crate) fn with_suggestions(mut self, show: bool) -> Self {
        self.show_suggestions = show;
        self
    }

    /// How many entries of a section to show: [cap] by default, all in full mode.
    fn limit(&self, cap: usize) -> usize {
        if self.full {
            usize::MAX
        } else {
            cap
        }
    }

    /// Truncate one rendered value to [max_chars], or keep it whole in full mode.
    fn clip(&self, text: &str, max_chars: usize) -> String {
        if self.full {
            text.to_string()
        } else {
            clip(text, max_chars)
        }
    }
}

/// The pretty-printed form of a JSON payload, or the input unchanged when it is
/// not JSON.
///
/// `/result --json` is the machine escape hatch of the interactive session, but a
/// human reads it too: a 5 KB `command_result` envelope printed as one line was
/// unusable — no wrapping, no structure, and re-formatting it meant piping through
/// an external tool. The text stays valid JSON, so a script's `jq`/`serde` parse is
/// unchanged; only the whitespace differs.
pub(crate) fn pretty_json(text: &str) -> String {
    match serde_json::from_str::<Value>(text) {
        Ok(value) => serde_json::to_string_pretty(&value).unwrap_or_else(|_| text.to_string()),
        // A plain-text payload (a mock, an older backend) is already human text.
        Err(_) => text.to_string(),
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

/// The payload facts a session uses to predict the next step.
///
/// The interactive session's follow-up menu has to be about the work that just
/// happened — the files it changed, the problems it left open, the next steps the
/// model itself proposed — not about the CLI's own commands. Reading the same
/// envelope the renderer reads keeps one definition of "where the payload lives".
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct ResultDigest {
    pub(crate) summary: Option<String>,
    pub(crate) findings: Vec<String>,
    pub(crate) files: Vec<String>,
    pub(crate) problems: Vec<String>,
    /// The model's own follow-ups from its completion report (`system.taskComplete`).
    pub(crate) next_suggestions: Vec<String>,
}

impl ResultDigest {
    /// True when the payload said nothing usable (no summary, findings, files,
    /// problems or suggestions).
    #[cfg(test)]
    pub(crate) fn is_empty(&self) -> bool {
        *self == ResultDigest::default()
    }
}

/// Read the follow-up facts out of a `command_result` body.
///
/// Tolerant by design: an unparseable body, a bare payload object and the usual
/// envelope all yield a digest, and an empty body yields the empty one.
pub(crate) fn digest(body: &str) -> ResultDigest {
    let Ok(value) = serde_json::from_str::<Value>(body.trim()) else {
        return ResultDigest::default();
    };
    let Some(payload) = payload_object(&value) else {
        return ResultDigest::default();
    };
    ResultDigest {
        summary: string_of(payload.get("summary")).or_else(|| string_of(payload.get("pageSummary"))),
        findings: strings_of(payload.get("keyFindings")),
        files: strings_of(payload.get("filesChanged")),
        problems: strings_of(payload.get("problems")),
        next_suggestions: strings_of(payload.get("nextSuggestions")),
    }
}

/// The object that holds the agent's payload: `result` of the envelope, or the
/// body itself when the caller passed a bare payload.
fn payload_object(value: &Value) -> Option<&serde_json::Map<String, Value>> {
    let map = value.as_object()?;
    match map.get("result") {
        Some(Value::Object(result)) => Some(result),
        _ => Some(map),
    }
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
        let shown = options.limit(FINDINGS_SHOWN);
        let mut body = Vec::new();
        for finding in findings.iter().take(shown) {
            body.extend(icon_bullet("•", finding, options.width));
        }
        if findings.len() > shown {
            body.push(format!("  … and {} more", findings.len() - shown));
        }
        section(
            &mut lines,
            &format!("Key findings ({})", findings.len()),
            body,
            options,
        );
    }

    // A coding task that did not merge must say so BEFORE "Files written": the
    // workspace names the checkout, and a reader who sees files listed under it
    // assumes the checkout contains them (self-development isolation plan §D10).
    let merge = result.get("merge").and_then(|v| v.as_str());
    if let Some(merge) = merge {
        if merge != "applied" {
            let isolated = string_of(result.get("isolatedWorkspace"));
            let line = match isolated.as_deref() {
                Some(tree) => format!("  Changes NOT applied — isolated tree: {tree}"),
                None => "  Changes NOT applied to the checkout".to_string(),
            };
            section(
                &mut lines,
                "Merge",
                vec![options.clip(&line, options.width)],
                options,
            );
        }
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
        let shown = options.limit(FILES_SHOWN);
        let mut body = Vec::new();
        for file in files.iter().take(shown) {
            body.push(format!("  {}", options.clip(file, options.width.saturating_sub(2))));
        }
        if files.len() > shown {
            body.push(format!("  … and {} more", files.len() - shown));
        }
        section(&mut lines, &title, body, options);
    } else if let Some(workspace) = workspace {
        section(
            &mut lines,
            "Workspace",
            vec![format!(
                "  {}",
                options.clip(&workspace, options.width.saturating_sub(2))
            )],
            options,
        );
    }

    let problems = strings_of(result.get("problems"));
    if !problems.is_empty() {
        let shown = options.limit(PROBLEMS_SHOWN);
        let mut body = Vec::new();
        for problem in problems.iter().take(shown) {
            body.extend(icon_bullet("⚠", problem, options.width));
        }
        if problems.len() > shown {
            body.push(format!("  … and {} more", problems.len() - shown));
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
            let shown: Vec<Value> = rows.iter().take(options.limit(ROWS_SHOWN)).cloned().collect();
            let mut body = table(&shown, options);
            if rows.len() > shown.len() {
                body.push(format!("  … {} more row(s)", rows.len() - shown.len()));
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
        let shown = options.limit(LINKS_SHOWN);
        let mut body: Vec<String> = links
            .iter()
            .take(shown)
            .map(|link| format!("  {}", options.clip(link, options.width.saturating_sub(2))))
            .collect();
        if links.len() > shown {
            body.push(format!("  … and {} more", links.len() - shown));
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
        let shown = options.limit(OTHERS_LINES_SHOWN);
        if body.len() > shown {
            let total = body.len();
            body.truncate(shown);
            body.push(format!("  … {} more line(s)", total - shown));
        }
        section(
            &mut lines,
            &format!("Other ({} field(s))", others.len()),
            body,
            options,
        );
    }

    // The completion report's own forward-looking field. It is a suggestion, not a
    // result, so it goes last — and the interactive session suppresses it (its
    // numbered menu carries the same list).
    let suggestions = strings_of(result.get("nextSuggestions"));
    if options.show_suggestions && !suggestions.is_empty() {
        let shown = options.limit(SUGGESTIONS_SHOWN);
        let mut body = Vec::new();
        for suggestion in suggestions.iter().take(shown) {
            body.extend(icon_bullet("→", suggestion, options.width));
        }
        if suggestions.len() > shown {
            body.push(format!("  … and {} more", suggestions.len() - shown));
        }
        section(&mut lines, "Suggested next steps", body, options);
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
    let shown: Vec<Value> = items.iter().take(options.limit(ROWS_SHOWN)).cloned().collect();
    let mut lines = vec![format!(
        "Data ({} rows × {} columns)",
        items.len(),
        columns
    )];
    lines.extend(table(&shown, options));
    if items.len() > shown.len() {
        lines.push(format!("  … {} more row(s)", items.len() - shown.len()));
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
fn fold(lines: &mut Vec<String>, max_lines: usize, hint: &str) {
    let max_lines = max_lines.max(4);
    if lines.len() <= max_lines {
        return;
    }
    let total = lines.len();
    let keep = max_lines - 1;
    lines.truncate(keep);
    lines.push(format!(
        "  … {} more line(s) — {hint} shows everything",
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
///
/// Full mode caps nothing: `--full` is the explicit "show me everything" switch,
/// so a value wider than its column widens the table instead of losing its tail.
/// The lines are then left unclipped for the same reason — a table is a layout,
/// not a summary.
fn table(rows: &[Value], options: &RenderOptions) -> Vec<String> {
    let columns = rows
        .iter()
        .find_map(|row| row.as_object().map(|object| object.len()))
        .unwrap_or(0)
        .max(1);
    let per_column = options.width.saturating_sub(4) / columns;
    let capped: Vec<Value> = if options.full {
        rows.to_vec()
    } else {
        let max_cell = per_column.clamp(8, CELL_PREVIEW);
        rows.iter().map(|row| cap_cells(row, max_cell)).collect()
    };
    crate::format_table(&capped)
        .lines()
        .map(|line| options.clip(line, options.width))
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
    let text = options.clip(&text, OTHERS_PREVIEW_CHARS);
    text.lines()
        .map(|line| options.clip(line, options.width))
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

    /// A coding task whose patch was refused must not let "workspace: <repo>" imply the
    /// change is in the checkout (isolation plan §D10).
    #[test]
    fn refused_merge_is_stated_before_files() {
        let body = json!({
            "taskId": "task-2",
            "status": "failed",
            "statusCode": 417,
            "statusTool": "command_status",
            "resultTool": "command_result",
            "cancelTool": "command_cancel",
            "result": {
                "summary": "Changes were NOT applied to /repo: no coding.verify verdict",
                "filesChanged": ["browser4-coding/src/main/kotlin/X.kt"],
                "workspace": "/repo",
                "isolatedWorkspace": "/repo/.browser4/worktrees/task-2",
                "isolation": "worktree",
                "merge": "refused"
            }
        })
        .to_string();

        let text = rendered(body, 100);

        let merge_at = text.find("Merge").expect("a Merge section is rendered");
        let files_at = text.find("Files written").expect("files are still listed");
        assert!(merge_at < files_at, "the refusal must come first:\n{text}");
        assert!(
            text.contains("Changes NOT applied — isolated tree: /repo/.browser4/worktrees/task-2"),
            "{text}"
        );
        assert!(text.contains("workspace: /repo"), "{text}");
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
    fn folds_beyond_the_line_cap_and_points_at_the_full_output() {
        let findings: Vec<String> = (0..10).map(|index| format!("finding {index}")).collect();
        let body = json!({"result": {"summary": "s", "keyFindings": findings}}).to_string();
        let lines = render_result_text(
            "task-1",
            &body,
            &RenderOptions {
                width: 100,
                max_lines: 6,
                color: false,
                json_hint: "/result --full".to_string(),
                ..RenderOptions::default()
            },
        );

        assert_eq!(lines.len(), 6, "{lines:?}");
        let last = lines.last().expect("a folded payload has a tail line");
        assert!(last.contains("more line(s)"), "{last}");
        assert!(last.contains("/result --full"), "{last}");
    }

    /// The fold line must name the command the reader can actually run: only
    /// `-i` has `/result`, every other entry point wants `agent result <id>`.
    #[test]
    fn the_fold_line_names_the_command_of_its_entry_point() {
        let findings: Vec<String> = (0..10).map(|index| format!("finding {index}")).collect();
        let body = json!({"result": {"summary": "s", "keyFindings": findings}}).to_string();
        let lines = render_result_text(
            "task-1",
            &body,
            &RenderOptions {
                width: 100,
                max_lines: 6,
                color: false,
                json_hint: "agent result task-1 --pretty".to_string(),
                ..RenderOptions::default()
            },
        );

        let last = lines.last().expect("a folded payload has a tail line");
        assert!(last.contains("agent result task-1 --pretty"), "{last}");
        assert!(!last.contains("/result --full"), "{last}");
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
            ..RenderOptions::default()
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

    /// `--full` must not cap a table cell either: a long field value is a
    /// deliverable, so its tail is content, not layout.
    #[test]
    fn full_rendering_keeps_long_table_cells_whole() {
        let long = "x".repeat(300);
        let body = json!({"result": {"fields": {"title": long}}}).to_string();

        let default = rendered(body.clone(), 60);
        assert!(
            default.contains('…'),
            "the default path caps a wide cell: {default}"
        );

        let full = render_result_text("task-1", &body, &options(60).with_full(true)).join("\n");
        assert!(
            !full.contains('…'),
            "full rendering must not truncate a cell: {full}"
        );
        assert!(
            full.contains(&long),
            "full rendering must carry the whole value: {full}"
        );
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
                ..RenderOptions::default()
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

    // ---- `--full`, pretty JSON and the follow-up digest ---------------------

    /// `--full` must lift the *section* caps too: a payload that fits the line
    /// budget was still cut to 10 findings, so "show me everything" hid 15 of 25.
    #[test]
    fn full_rendering_lifts_the_section_caps() {
        let findings: Vec<String> = (0..25).map(|index| format!("finding {index}")).collect();
        let body = json!({"result": {"summary": "s", "keyFindings": findings}}).to_string();

        let default = render_result_text("task-1", &body, &options(100));
        assert!(
            default.iter().any(|line| line.contains("and 15 more")),
            "the default path folds the tail of a long section: {default:?}"
        );
        assert!(
            !default.iter().any(|line| line.contains("finding 24")),
            "the folded entry must not be listed: {default:?}"
        );

        let full = render_result_text(
            "task-1",
            &body,
            &options(100).with_full(true),
        );
        for index in 0..25 {
            assert!(
                full.iter().any(|line| line.contains(&format!("finding {index}"))),
                "full rendering must list finding {index}: {full:?}"
            );
        }
        assert!(
            !full.iter().any(|line| line.contains("more line(s)") || line.contains("and 15 more")),
            "a full render must not claim there is more: {full:?}"
        );
    }

    /// The completion report's own follow-ups are a section — and the interactive
    /// session switches them off because its numbered menu carries the same list.
    #[test]
    fn next_suggestions_are_rendered_last_and_can_be_suppressed() {
        let body = json!({
            "result": {
                "summary": "Fixed the build.",
                "nextSuggestions": ["run the module tests", "commit the change"]
            }
        })
        .to_string();

        let lines = render_result_text("task-1", &body, &options(100));
        let title = lines
            .iter()
            .position(|line| line == "Suggested next steps")
            .unwrap_or_else(|| panic!("no suggestions section: {lines:?}"));
        assert!(
            lines[title + 1].contains("run the module tests"),
            "{lines:?}"
        );
        assert_eq!(
            lines.last().map(String::as_str),
            Some("  → commit the change"),
            "the suggestions must be the last section: {lines:?}"
        );

        let suppressed = render_result_text(
            "task-1",
            &body,
            &options(100).with_suggestions(false),
        );
        assert!(
            !suppressed.iter().any(|line| line.contains("Suggested next steps")),
            "{suppressed:?}"
        );
        // Suppressing the section must not hide the payload from the "Other" block
        // either — `nextSuggestions` is a known key.
        assert!(
            !suppressed.iter().any(|line| line.contains("Other (")),
            "{suppressed:?}"
        );
    }

    #[test]
    fn the_digest_reads_the_envelope_and_a_bare_payload() {
        let envelope = json!({
            "taskId": "task-1",
            "status": "done",
            "result": {
                "summary": "Fixed the module build.",
                "keyFindings": ["BOM pinned an old zip4j"],
                "filesChanged": ["browser4-dependencies/pom.xml"],
                "problems": ["CI gate not run"],
                "nextSuggestions": ["run the tests"]
            }
        })
        .to_string();

        let facts = digest(&envelope);
        assert_eq!(facts.summary.as_deref(), Some("Fixed the module build."));
        assert_eq!(facts.findings, vec!["BOM pinned an old zip4j"]);
        assert_eq!(facts.files, vec!["browser4-dependencies/pom.xml"]);
        assert_eq!(facts.problems, vec!["CI gate not run"]);
        assert_eq!(facts.next_suggestions, vec!["run the tests"]);
        assert!(!facts.is_empty());

        // A backend (or a mock) that answers with the payload itself.
        let bare = digest(r#"{"summary":"done","filesChanged":["a.kt"]}"#);
        assert_eq!(bare.summary.as_deref(), Some("done"));
        assert_eq!(bare.files, vec!["a.kt"]);

        // Nothing usable: an unparseable body, a null payload, an empty one.
        for body in ["", "not json", "null", r#"{"result":null}"#, "{}"] {
            assert!(digest(body).is_empty(), "{body} → {:?}", digest(body));
        }
    }

    #[test]
    fn pretty_json_indents_and_leaves_non_json_alone() {
        let pretty = pretty_json(r#"{"a":1,"b":[1,2]}"#);
        assert_eq!(pretty, "{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ]\n}");

        // A plain-text payload is already human text.
        assert_eq!(pretty_json("result for agent-task-1"), "result for agent-task-1");
        // Whitespace-only or broken JSON stays as it came.
        assert_eq!(pretty_json("{broken"), "{broken");
    }
}
