//! Live following (`agent run --follow`) and the interactive session
//! (`agent run -i`).
//!
//! Both modes render the same data source: the backend's per-task step log
//! (`GET /api/commands/{id}/events`), polled with a sequence cursor
//! (`?after=<nextSeq>`). The cursor model is deliberate — it reconnects
//! naturally, survives a proxy that buffers responses, needs no stream parsing
//! on the client, and degrades cleanly: a backend that predates the endpoint
//! answers 404, and the follower falls back to status polling (the old
//! `agent run --wait` behaviour) instead of failing.
//!
//! The interactive session keeps the SAME task running while the user types:
//! a line entered while a task is in flight is sent to
//! `POST /api/commands/{id}/input` and injected into the agent at its next
//! model round-trip, rather than queued as a second task.
//!
//! Nothing here is allowed to change the default `agent run` behaviour: adding
//! events must be invisible unless `--follow` / `--ndjson` / `-i` is passed.

use std::io::{self, BufRead, IsTerminal, Write};
use std::time::{Duration, Instant};

use reqwest::Client;
use serde_json::{json, Value};

use crate::http::{
    cancel_command_task, get_command_events, get_command_result, get_command_stats,
    get_command_status, send_command_input,
};

/// How often the follower asks for new events while a task is running.
const EVENT_POLL_INTERVAL: Duration = Duration::from_millis(400);

/// How often the status fallback polls (an events-less backend).
const STATUS_POLL_INTERVAL: Duration = Duration::from_millis(1000);

/// Page size requested from the events endpoint.
const EVENTS_PAGE_LIMIT: usize = 200;

/// How long a follower tolerates consecutive transport failures before giving
/// up.
///
/// A time window, not an attempt count: the case that matters is a backend
/// restart (JVM + Spring take 10–30 s). A 30-attempt counter at the 400 ms poll
/// interval gave up after ~12 s and aborted a `--follow` the user explicitly
/// asked for; five minutes covers a restart with room to spare while still
/// terminating a genuinely dead backend.
const TRANSIENT_FAILURE_WINDOW: Duration = Duration::from_secs(300);

/// Upper bound on a rendered tool-call argument preview.
const TOOL_ARGS_PREVIEW: usize = 120;

/// Upper bound on a rendered model-text line.
const MODEL_TEXT_PREVIEW: usize = 200;

/// Upper bound on a rendered tool-result preview (failures only).
const RESULT_PREVIEW: usize = 240;

/// A tool slower than this gets a completion line even when it succeeded.
const SLOW_TOOL_MS: i64 = 2_000;

/// How many per-tool call counts `/stats` lists.
const TOP_TOOLS: usize = 5;

// ---------------------------------------------------------------------------
// Event payloads
// ---------------------------------------------------------------------------

/// One step of a task as served by `GET /api/commands/{id}/events`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct TaskEvent {
    seq: u64,
    kind: String,
    epoch_millis: i64,
    tool: Option<String>,
    arguments: Option<String>,
    ok: Option<bool>,
    duration_ms: Option<i64>,
    text: Option<String>,
    turn: Option<i64>,
    /// Provider-reported tokens of one model request (`run.usage` steps).
    input_tokens: Option<u64>,
    output_tokens: Option<u64>,
    total_tokens: Option<u64>,
}

impl TaskEvent {
    /// The NDJSON form of this event (`--ndjson`, and the interactive session's
    /// machine-readable lines).
    fn to_json(&self, task_id: &str) -> Value {
        let mut obj = json!({
            "taskId": task_id,
            "seq": self.seq,
            "type": self.kind,
            "epochMillis": self.epoch_millis,
        });
        if let Some(tool) = &self.tool {
            obj["tool"] = json!(tool);
        }
        if let Some(arguments) = &self.arguments {
            obj["arguments"] = json!(arguments);
        }
        if let Some(ok) = self.ok {
            obj["ok"] = json!(ok);
        }
        if let Some(duration_ms) = self.duration_ms {
            obj["durationMs"] = json!(duration_ms);
        }
        if let Some(text) = &self.text {
            obj["text"] = json!(text);
        }
        if let Some(turn) = self.turn {
            obj["turn"] = json!(turn);
        }
        if let Some(tokens) = self.input_tokens {
            obj["inputTokens"] = json!(tokens);
        }
        if let Some(tokens) = self.output_tokens {
            obj["outputTokens"] = json!(tokens);
        }
        if let Some(tokens) = self.total_tokens {
            obj["totalTokens"] = json!(tokens);
        }
        obj
    }
}

/// One page of the step log.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct EventsPage {
    status: String,
    done: bool,
    next_seq: u64,
    events: Vec<TaskEvent>,
}

/// Parse a `GET /api/commands/{id}/events` body.
///
/// Every field is optional on purpose: a follower must keep working against a
/// backend that adds or drops fields, and a malformed page is retried (the
/// cursor is unchanged) instead of killing the session.
fn parse_events_page(body: &str) -> Result<EventsPage, String> {
    let value: Value = serde_json::from_str(body)
        .map_err(|e| format!("could not parse the events response as JSON: {e}"))?;

    let mut page = EventsPage {
        status: value
            .get("status")
            .and_then(|v| v.as_str())
            .unwrap_or("running")
            .to_string(),
        done: value.get("done").and_then(|v| v.as_bool()).unwrap_or(false),
        next_seq: value.get("nextSeq").and_then(|v| v.as_u64()).unwrap_or(0),
        events: Vec::new(),
    };

    for item in value
        .get("events")
        .and_then(|v| v.as_array())
        .map(Vec::as_slice)
        .unwrap_or_default()
    {
        let Some(kind) = item.get("type").and_then(|v| v.as_str()) else {
            continue;
        };
        page.events.push(TaskEvent {
            seq: item.get("seq").and_then(|v| v.as_u64()).unwrap_or(0),
            kind: kind.to_string(),
            epoch_millis: item
                .get("epochMillis")
                .and_then(|v| v.as_i64())
                .unwrap_or(0),
            tool: string_field(item, "tool"),
            arguments: string_field(item, "arguments"),
            ok: item.get("ok").and_then(|v| v.as_bool()),
            duration_ms: item.get("durationMs").and_then(|v| v.as_i64()),
            text: string_field(item, "text"),
            turn: item.get("turn").and_then(|v| v.as_i64()),
            input_tokens: item.get("inputTokens").and_then(|v| v.as_u64()),
            output_tokens: item.get("outputTokens").and_then(|v| v.as_u64()),
            total_tokens: item.get("totalTokens").and_then(|v| v.as_u64()),
        });
    }

    // An empty page must not rewind the caller's cursor.
    if page.next_seq == 0 {
        page.next_seq = page.events.last().map(|e| e.seq).unwrap_or(0);
    }

    Ok(page)
}

fn string_field(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .filter(|s| !s.is_empty())
}

/// Progress of a task as read from the `command_status` envelope — the fallback
/// used when the backend has no `/events` endpoint.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct StatusProgress {
    done: bool,
    /// True when the envelope is the backend's "no such task" answer.
    ///
    /// An unknown id and a missing `/events` route both answer 404, so the
    /// follower must tell them apart before it claims the backend is old: a
    /// mistyped id would otherwise be reported as "no events endpoint, falling
    /// back" and then fail with a confusing reason.
    not_found: bool,
    word: String,
    message: Option<String>,
    tools: i64,
}

fn parse_status_progress(body: &str) -> StatusProgress {
    let Ok(value) = serde_json::from_str::<Value>(&body) else {
        return StatusProgress {
            done: false,
            not_found: false,
            word: "running".to_string(),
            message: None,
            tools: 0,
        };
    };

    let word = value
        .get("status")
        .and_then(|v| v.as_str())
        .map(|s| s.to_lowercase())
        .or_else(|| {
            let process_state = value.get("processState").and_then(|v| v.as_str())?;
            Some(match process_state {
                "created" => "queued",
                "done" | "completed" => "done",
                "failed" | "error" => "failed",
                _ => "running",
            }
            .to_string())
        })
        .unwrap_or_else(|| "running".to_string());

    let done = matches!(word.as_str(), "done" | "failed" | "cancelled" | "completed")
        || value
            .get("isDone")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);

    let message = value
        .get("error")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .or_else(|| value.get("message").and_then(|v| v.as_str()))
        .or_else(|| value.get("failureReason").and_then(|v| v.as_str()))
        .map(str::to_string);

    let tools = value
        .get("processed")
        .and_then(|v| v.as_i64())
        .or_else(|| value.get("toolExecutions").and_then(|v| v.as_i64()))
        .unwrap_or(0);

    // The backend answers an unknown id with `statusCode: 404` and a message
    // naming the id; a missing ROUTE (an older backend) has no such envelope.
    let status_code = value.get("statusCode").and_then(|v| v.as_i64()).unwrap_or(0);
    let not_found = status_code == 404
        || message.as_deref().is_some_and(|text| {
            let lower = text.to_lowercase();
            lower.contains("no task found") || lower.contains("no agent task found")
        });

    StatusProgress {
        done,
        not_found,
        word,
        message,
        tools,
    }
}

// ---------------------------------------------------------------------------
// Run statistics (`/stats`)
// ---------------------------------------------------------------------------

/// Aggregate counters of one run — how many LLM calls, tool calls and tokens it
/// took.
///
/// Two sources feed this: the backend's authoritative counters
/// (`GET /api/commands/{id}/stats`, which counts every step even after it fell
/// out of the bounded event log) and, against an older backend, the steps this
/// session observed itself ([RunStats::observe]). The verdict whether a report is
/// local is carried by [TaskStatsReport::local], never inferred from the numbers.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct RunStats {
    /// Outer agent rounds (`round.start` steps).
    rounds: u64,
    /// Successful model requests (`run.usage` steps): one per LLM API call.
    model_requests: u64,
    tool_calls: u64,
    tool_failures: u64,
    steer_messages: u64,
    input_tokens: u64,
    output_tokens: u64,
    total_tokens: u64,
    /// Every step the run emitted (retained plus evicted).
    events: u64,
    /// Steps evicted from the backend's bounded buffer.
    dropped_events: u64,
    /// Per-tool call counts, unordered until rendered.
    tool_counts: Vec<(String, u64)>,
}

impl RunStats {
    /// Count one observed step, mirroring the backend's aggregation so a local
    /// fallback report means the same thing as a served one.
    fn observe(&mut self, event: &TaskEvent) {
        self.events += 1;
        match event.kind.as_str() {
            "round.start" => self.rounds += 1,
            "tool.call" => {
                self.tool_calls += 1;
                let name = event
                    .tool
                    .as_deref()
                    .filter(|name| !name.is_empty())
                    .unwrap_or("unknown");
                match self.tool_counts.iter_mut().find(|(tool, _)| tool == name) {
                    Some(entry) => entry.1 += 1,
                    None => self.tool_counts.push((name.to_string(), 1)),
                }
            }
            "tool.result" => {
                if event.ok == Some(false) {
                    self.tool_failures += 1;
                }
            }
            "steer" => self.steer_messages += 1,
            "run.usage" => {
                self.model_requests += 1;
                let input = event.input_tokens.unwrap_or(0);
                let output = event.output_tokens.unwrap_or(0);
                self.input_tokens += input;
                self.output_tokens += output;
                self.total_tokens += event.total_tokens.unwrap_or(input + output);
            }
            _ => {}
        }
    }

    /// True when the provider reported token counts at all.
    ///
    /// A run whose provider omits usage made [Self::model_requests] calls but its
    /// token numbers are *unknown* — a report must not claim "0 tokens".
    fn tokens_reported(&self) -> bool {
        self.input_tokens > 0 || self.output_tokens > 0 || self.total_tokens > 0
    }

    /// The most-called tools, highest count first (at most [TOP_TOOLS]).
    fn top_tools(&self, limit: usize) -> Vec<(String, u64)> {
        let mut tools = self.tool_counts.clone();
        tools.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        tools.truncate(limit);
        tools
    }

    /// The same counters as a JSON object (for `--json` in the interactive
    /// session).
    fn to_json(&self, local: bool) -> Value {
        let counts: serde_json::Map<String, Value> = self
            .tool_counts
            .iter()
            .map(|(tool, count)| (tool.clone(), json!(count)))
            .collect();
        json!({
            "rounds": self.rounds,
            "modelRequests": self.model_requests,
            "toolCalls": self.tool_calls,
            "toolFailures": self.tool_failures,
            "steerMessages": self.steer_messages,
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
            "totalTokens": self.total_tokens,
            "tokensReported": self.tokens_reported(),
            "events": self.events,
            "droppedEvents": self.dropped_events,
            "toolCounts": counts,
            "countedLocally": local,
        })
    }
}

/// One task's statistics as reported to the user.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct TaskStatsReport {
    task_id: String,
    /// The task's status word (`running|done|failed|cancelled|unknown`).
    status: String,
    is_done: bool,
    elapsed_ms: Option<u64>,
    stats: RunStats,
    /// True when [Self::stats] was counted by this session instead of served by
    /// the backend.
    local: bool,
}

/// Parse a `GET /api/commands/{id}/stats` body.
///
/// Everything is optional for the same reason as [parse_events_page]: a report
/// must not die on a field the backend stopped sending. A body with no `stats`
/// object at all is an error (there is nothing to report), which the caller
/// surfaces as "could not read stats".
fn parse_task_stats(body: &str) -> Result<TaskStatsReport, String> {
    let value: Value = serde_json::from_str(body)
        .map_err(|e| format!("could not parse the stats response as JSON: {e}"))?;
    let stats = value
        .get("stats")
        .filter(|stats| stats.is_object())
        .ok_or_else(|| "the stats response carries no counters".to_string())?;

    let tool_counts = stats
        .get("toolCounts")
        .and_then(|v| v.as_object())
        .map(|counts| {
            counts
                .iter()
                .map(|(tool, count)| (tool.clone(), count.as_u64().unwrap_or(0)))
                .collect()
        })
        .unwrap_or_default();

    Ok(TaskStatsReport {
        task_id: string_field(&value, "taskId").unwrap_or_default(),
        status: string_field(&value, "status").unwrap_or_else(|| "unknown".to_string()),
        is_done: value.get("isDone").and_then(|v| v.as_bool()).unwrap_or(false),
        elapsed_ms: value.get("elapsedMs").and_then(|v| v.as_u64()),
        stats: RunStats {
            rounds: u64_field(stats, "rounds"),
            model_requests: u64_field(stats, "modelRequests"),
            tool_calls: u64_field(stats, "toolCalls"),
            tool_failures: u64_field(stats, "toolFailures"),
            steer_messages: u64_field(stats, "steerMessages"),
            input_tokens: u64_field(stats, "inputTokens"),
            output_tokens: u64_field(stats, "outputTokens"),
            total_tokens: u64_field(stats, "totalTokens"),
            events: u64_field(stats, "events"),
            dropped_events: u64_field(stats, "droppedEvents"),
            tool_counts,
        },
        local: false,
    })
}

fn u64_field(value: &Value, key: &str) -> u64 {
    value.get(key).and_then(|v| v.as_u64()).unwrap_or(0)
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

/// Turns step events into the lines `agent run --follow` prints.
///
/// Colors are only emitted on a terminal: piped output (tests, CI, `| grep`)
/// stays byte-identical to the plain form.
struct Renderer {
    started: Instant,
    color: bool,
    quiet: bool,
    tool_index: u64,
}

impl Renderer {
    fn new(started: Instant, color: bool, quiet: bool) -> Self {
        Self {
            started,
            color,
            quiet,
            tool_index: 0,
        }
    }

    fn elapsed(&self) -> String {
        format_elapsed(self.started.elapsed())
    }

    /// Render one event, if it is worth a line.
    fn render(&mut self, event: &TaskEvent) {
        if let Some(line) = self.format(event) {
            self.print(&line);
        }
    }

    /// The human-readable line for [event], or `None` for events that would
    /// only add noise (`round.start`, successful fast tools, terminal events —
    /// the follower prints its own completion line).
    fn format(&mut self, event: &TaskEvent) -> Option<String> {
        match event.kind.as_str() {
            "task.started" => {
                let text = event.text.as_deref().unwrap_or("task started");
                Some(format!("{} ▶ {}", self.time_prefix(), truncate(text, 120)))
            }
            "tool.call" => {
                self.tool_index += 1;
                let tool = event.tool.as_deref().unwrap_or("tool");
                let args = event
                    .arguments
                    .as_deref()
                    .map(format_tool_arguments)
                    .unwrap_or_default();
                Some(format!(
                    "{} {} {}{}",
                    self.time_prefix(),
                    self.paint(&format!("#{}", self.tool_index), Style::Dim),
                    self.paint(tool, Style::Tool),
                    args
                ))
            }
            "tool.result" => {
                let failed = event.ok == Some(false);
                let slow = event.duration_ms.unwrap_or(0) >= SLOW_TOOL_MS;
                if !failed && !slow {
                    return None;
                }
                let duration = event
                    .duration_ms
                    .map(format_duration_ms)
                    .unwrap_or_else(|| "?".to_string());
                let tool = event.tool.as_deref().unwrap_or("tool");
                if failed {
                    let detail = event
                        .text
                        .as_deref()
                        .map(|text| truncate(text, RESULT_PREVIEW))
                        .unwrap_or_default();
                    Some(format!(
                        "{}   ↳ {} failed after {}: {}",
                        self.time_prefix(),
                        tool,
                        duration,
                        self.paint(&detail, Style::Error)
                    ))
                } else {
                    Some(format!(
                        "{}   ↳ {} ok in {}",
                        self.time_prefix(),
                        tool,
                        duration
                    ))
                }
            }
            "model.text" => {
                let text = event.text.as_deref().unwrap_or_default();
                if text.is_empty() {
                    return None;
                }
                Some(format!(
                    "{}   💭 {}",
                    self.time_prefix(),
                    truncate(text, MODEL_TEXT_PREVIEW)
                ))
            }
            "steer" => {
                let text = event.text.as_deref().unwrap_or_default();
                Some(format!(
                    "{}   ↪ steering delivered: {}",
                    self.time_prefix(),
                    truncate(text, MODEL_TEXT_PREVIEW)
                ))
            }
            _ => None,
        }
    }

    /// Print a human-readable line.
    ///
    /// Suppressed under `--quiet` and under the global `--json`: that flag
    /// promises exactly one JSON envelope on stdout, so a live step line would
    /// corrupt it (the same reason `cli_println!` is silent there).
    fn print(&self, line: &str) {
        if !human_output_enabled(self.quiet) {
            return;
        }
        crate::print_stdout_line(line);
    }

    fn time_prefix(&self) -> String {
        self.paint(&format!("[{}]", self.elapsed()), Style::Dim)
    }

    fn paint(&self, text: &str, style: Style) -> String {
        if !self.color {
            return text.to_string();
        }
        format!("\x1b[{}m{}\x1b[0m", style.code(), text)
    }
}

#[derive(Clone, Copy)]
enum Style {
    Dim,
    Tool,
    Error,
}

impl Style {
    fn code(self) -> &'static str {
        match self {
            Style::Dim => "2",
            Style::Tool => "36",
            Style::Error => "31",
        }
    }
}

fn format_elapsed(elapsed: Duration) -> String {
    let secs = elapsed.as_secs();
    format!("{:02}:{:02}", secs / 60, secs % 60)
}

/// True when human-readable lines may be written to stdout.
fn human_output_enabled(quiet: bool) -> bool {
    !quiet && !crate::json_active()
}

/// True when a follower may print an out-of-band notice (retry, fallback).
///
/// NDJSON is machine output end to end: a prose line would make the stream
/// unparseable, so notices are dropped there — the eventual failure still
/// reaches the terminal through the non-zero exit and stderr.
fn notice_enabled(ndjson: bool, quiet: bool) -> bool {
    !ndjson && human_output_enabled(quiet)
}

fn format_duration_ms(duration_ms: i64) -> String {
    if duration_ms >= 1_000 {
        format!("{:.1}s", duration_ms as f64 / 1000.0)
    } else {
        format!("{duration_ms}ms")
    }
}

/// A run's duration: `18.4s` under a minute, `3m12s` under an hour, `1h04m`
/// above — a coding run can last hours, and `7200.0s` is unreadable.
fn format_run_duration(duration_ms: u64) -> String {
    if duration_ms < 60_000 {
        return format!("{:.1}s", duration_ms as f64 / 1000.0);
    }
    let secs = duration_ms / 1_000;
    if secs < 3_600 {
        return format!("{}m{:02}s", secs / 60, secs % 60);
    }
    let minutes = (secs / 60) % 60;
    format!("{}h{:02}m", secs / 3_600, minutes)
}

/// Group digits (`48231` → `48,231`) so a token total is readable at a glance.
fn format_thousands(value: u64) -> String {
    let digits = value.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (index, ch) in digits.chars().enumerate() {
        if index > 0 && (digits.len() - index) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out
}

/// The human-readable `/stats` block.
///
/// A few aligned lines rather than one long sentence: the point of the command is
/// comparing counters, and terminals wrap at 80–120 columns. Every line is
/// self-explanatory when copied out of context (a CI log keeps the numbers, not
/// the layout).
fn format_stats_report(report: &TaskStatsReport) -> Vec<String> {
    let header = match report.elapsed_ms {
        Some(duration_ms) => format!(
            "{} · {} · {}",
            report.task_id,
            report.status,
            format_run_duration(duration_ms)
        ),
        None => format!("{} · {}", report.task_id, report.status),
    };
    let stats = &report.stats;
    let mut lines = vec![header];

    lines.push(format!(
        "  llm     {} round(s) · {} request(s)",
        stats.rounds, stats.model_requests
    ));
    if stats.tokens_reported() {
        lines.push(format!(
            "  tokens  in {} · out {} · total {}",
            format_thousands(stats.input_tokens),
            format_thousands(stats.output_tokens),
            format_thousands(stats.total_tokens)
        ));
    } else if stats.model_requests > 0 {
        // The provider sends no usage block (a local model, a gateway that drops
        // it): the calls happened, the token numbers are unknown, not zero.
        lines.push("  tokens  not reported by the provider".to_string());
    } else {
        lines.push("  tokens  no model request yet".to_string());
    }

    let mut tools = format!(
        "  tools   {} call(s) · {} failed",
        stats.tool_calls, stats.tool_failures
    );
    let top = stats.top_tools(TOP_TOOLS);
    if !top.is_empty() {
        let rendered = top
            .iter()
            .map(|(tool, count)| format!("{tool} {count}"))
            .collect::<Vec<_>>()
            .join(", ");
        tools.push_str(&format!(" ({rendered})"));
    }
    lines.push(tools);

    let mut steps = format!("  steps   {}", stats.events);
    if stats.dropped_events > 0 {
        steps.push_str(&format!(
            " ({} dropped from the buffer)",
            stats.dropped_events
        ));
    }
    if stats.steer_messages > 0 {
        steps.push_str(&format!(" · {} steering message(s)", stats.steer_messages));
    }
    lines.push(steps);

    if report.local {
        lines.push(
            "  ⚠ counted by this session from the steps it observed — the backend has no \
             statistics for this task, so a long run may undercount"
                .to_string(),
        );
    }
    lines
}

/// `(arguments)` for a tool-call line; empty for `{}` so plumbing stays short.
fn format_tool_arguments(arguments: &str) -> String {
    let trimmed = arguments.trim();
    if trimmed.is_empty() || trimmed == "{}" {
        return String::new();
    }
    format!("({})", truncate(trimmed, TOOL_ARGS_PREVIEW))
}

/// Truncate on a char boundary, appending an ellipsis when anything was cut.
fn truncate(text: &str, max_chars: usize) -> String {
    let mut out = String::new();
    for (index, ch) in text.chars().enumerate() {
        if index >= max_chars {
            out.push('…');
            break;
        }
        out.push(ch);
    }
    out
}

// ---------------------------------------------------------------------------
// Follower
// ---------------------------------------------------------------------------

/// What one poll of a running task produced.
enum PollOutcome {
    Running,
    Done { status: String },
}

/// Follows one task: cursor, fallback mode, failure window and rendering.
struct TaskFollower {
    task_id: String,
    after: u64,
    events_supported: bool,
    /// When the current run of consecutive transport failures started.
    first_failure: Option<Instant>,
    /// Whether the "no events endpoint, falling back" notice was printed.
    fallback_noted: bool,
    last_status_line: Option<String>,
    renderer: Renderer,
    ndjson: bool,
    started: Instant,
    last_poll: Option<Instant>,
    /// Steps this follower observed, counted locally.
    ///
    /// The fallback behind `/stats`: a backend without the stats endpoint (or one
    /// whose run log expired) still gets a report, and this session's own tally is
    /// the only one available. It can undercount — steps evicted before this
    /// follower reached them are lost — which the report says out loud.
    observed: RunStats,
}

impl TaskFollower {
    fn new(task_id: &str, ndjson: bool, color: bool, quiet: bool) -> Self {
        let started = Instant::now();
        Self {
            task_id: task_id.to_string(),
            after: 0,
            events_supported: true,
            first_failure: None,
            fallback_noted: false,
            last_status_line: None,
            renderer: Renderer::new(started, color, quiet),
            ndjson,
            started,
            last_poll: None,
            observed: RunStats::default(),
        }
    }

    /// Spacing between polls: the events endpoint is a cheap cursor read; the
    /// status fallback is a heavier MCP call and is polled less often.
    fn poll_interval(&self) -> Duration {
        if self.events_supported {
            EVENT_POLL_INTERVAL
        } else {
            STATUS_POLL_INTERVAL
        }
    }

    fn due(&self) -> bool {
        match self.last_poll {
            None => true,
            Some(at) => at.elapsed() >= self.poll_interval(),
        }
    }

    /// Fetch and render everything new. Returns the task's terminal state when
    /// it has one.
    async fn poll(&mut self, client: &Client, base_url: &str) -> Result<PollOutcome, String> {
        // The interactive loop ticks at one rate; the follower decides whether a
        // poll is actually due (the status fallback must not hammer the backend).
        if !self.due() {
            return Ok(PollOutcome::Running);
        }
        self.last_poll = Some(Instant::now());

        if !self.events_supported {
            return self.poll_status(client, base_url).await;
        }

        let response = match get_command_events(
            client,
            base_url,
            &self.task_id,
            self.after,
            EVENTS_PAGE_LIMIT,
        )
        .await
        {
            Ok(response) => response,
            Err(error) => return self.tolerate_transient(error),
        };

        if response.is_missing_endpoint() {
            // A 404 is also how an unknown task id answers, so do not claim the
            // backend is old yet — the status probe below tells them apart (and
            // prints the fallback notice only for a real task).
            self.events_supported = false;
            return self.poll_status(client, base_url).await;
        }
        if !response.is_success() {
            return self.tolerate_transient(format!(
                "the events endpoint answered HTTP {}: {}",
                response.status,
                truncate(response.body.trim(), 200)
            ));
        }

        let page = match parse_events_page(&response.body) {
            Ok(page) => page,
            // A 200 with a non-JSON body (proxy error page, truncated body) is a
            // transport problem, not the end of the task: retry with the cursor
            // unchanged, exactly as `parse_events_page` promises.
            Err(error) => return self.tolerate_transient(error),
        };
        self.first_failure = None;
        for event in &page.events {
            self.emit(event);
        }
        self.after = page.next_seq.max(self.after);

        if page.done {
            return Ok(PollOutcome::Done {
                status: page.status,
            });
        }
        Ok(PollOutcome::Running)
    }

    /// Status-only progress: what `agent run --wait` always did, kept so an
    /// older backend still shows *something* live.
    async fn poll_status(
        &mut self,
        client: &Client,
        base_url: &str,
    ) -> Result<PollOutcome, String> {
        let body = match get_command_status(client, base_url, &self.task_id).await {
            Ok(body) => body,
            Err(error) => return self.tolerate_transient(error),
        };
        let progress = parse_status_progress(&body);
        self.first_failure = None;

        // The backend has no record of the id: stop instead of polling forever,
        // and do NOT claim the events endpoint is missing (a typo'd id and an old
        // backend both answer 404).
        if progress.not_found {
            return Err(format!(
                "No agent task found with id '{}'. It may have expired, been cleared, \
                 or the backend may have restarted.",
                self.task_id
            ));
        }

        if !self.events_supported && !self.fallback_noted {
            self.fallback_noted = true;
            self.note_fallback();
        }

        // NDJSON is machine output end to end: a prose progress line would make
        // the stream unparseable, and in fallback mode there are no step events
        // to emit instead.
        let line = if self.ndjson {
            None
        } else {
            progress
                .message
                .clone()
                .map(|message| {
                    let tools = if progress.tools > 0 {
                        format!(" ({} step(s))", progress.tools)
                    } else {
                        String::new()
                    };
                    format!(
                        "{} {} {}",
                        self.renderer.time_prefix(),
                        truncate(&message, MODEL_TEXT_PREVIEW),
                        tools
                    )
                })
                .filter(|line| self.last_status_line.as_deref() != Some(line.as_str()))
        };
        if let Some(line) = line {
            self.last_status_line = Some(line.clone());
            self.renderer.print(&line);
        }

        if progress.done {
            return Ok(PollOutcome::Done {
                status: progress.word,
            });
        }
        Ok(PollOutcome::Running)
    }

    fn emit(&mut self, event: &TaskEvent) {
        // Local tally for `/stats` against a backend that serves no counters.
        // Counted in both output modes: `/stats` must answer the same whether
        // the steps were rendered or streamed as NDJSON.
        self.observed.observe(event);
        if self.ndjson {
            let line = event.to_json(&self.task_id).to_string();
            crate::print_stdout_line(&line);
        } else {
            self.renderer.render(event);
        }
    }

    fn note_fallback(&self) {
        if !notice_enabled(self.ndjson, self.renderer.quiet) {
            return;
        }
        self.renderer.print(&format!(
            "{} this backend has no events endpoint — falling back to status polling",
            self.renderer.time_prefix()
        ));
    }

    fn tolerate_transient(&mut self, error: String) -> Result<PollOutcome, String> {
        let now = Instant::now();
        let first_failure = self.first_failure.is_none();
        let since = *self.first_failure.get_or_insert(now);
        let failing_for = now.duration_since(since);
        if failing_for >= TRANSIENT_FAILURE_WINDOW {
            return Err(format!(
                "giving up on task {} after {}s without a usable response: {}",
                self.task_id,
                failing_for.as_secs(),
                error
            ));
        }
        // One notice per outage, and only on a human-facing stream: NDJSON must
        // stay parseable line by line.
        if first_failure && notice_enabled(self.ndjson, self.renderer.quiet) {
            self.renderer.print(&format!(
                "{}   … {error} (retrying for up to {}s)",
                self.renderer.time_prefix(),
                TRANSIENT_FAILURE_WINDOW.as_secs()
            ));
        }
        Ok(PollOutcome::Running)
    }

    fn elapsed(&self) -> Duration {
        self.started.elapsed()
    }
}

/// Options of the one-shot `agent run --follow` mode.
pub struct FollowOptions {
    /// Emit one JSON object per step instead of human-readable lines.
    pub ndjson: bool,
    /// Optional cap on the follow window (seconds); `None` follows until the
    /// task ends.
    pub wait_timeout: Option<u64>,
}

/// Follow a submitted task until it reaches a terminal state.
///
/// Prints the steps as they happen, then the result (or the failure reason) the
/// same way `agent run --wait` does. Ctrl+C cancels the task instead of leaving
/// it running in the background — the interactive counterpart of
/// `agent cancel <id>`.
pub async fn follow_task(
    client: &Client,
    base_url: &str,
    task_id: &str,
    options: FollowOptions,
) -> Result<(), String> {
    let mut follower = TaskFollower::new(task_id, options.ndjson, color_enabled(), quiet_enabled());
    let timeout = options.wait_timeout.map(Duration::from_secs);
    let mut ctrl_c = Box::pin(tokio::signal::ctrl_c());

    loop {
        if let Some(timeout) = timeout {
            if follower.elapsed() > timeout {
                // The task is still alive server-side: keep it tracked as
                // processing, exactly like the `--wait` timeout path, so a later
                // `agent list` / `agent status` can pick it up.
                let _ = crate::state::update_async_task_status(task_id, "processing", None);
                return Err(format!(
                    "Agent task {} timed out after {}s. Use 'agent status {}' to check later.",
                    task_id,
                    follower.elapsed().as_secs(),
                    task_id
                ));
            }
        }

        match follower.poll(client, base_url).await? {
            PollOutcome::Running => {}
            PollOutcome::Done { status } => {
                return print_completion(client, base_url, &mut follower, &status, options.ndjson)
                    .await;
            }
        }

        tokio::select! {
            _ = tokio::time::sleep(follower.poll_interval()) => {}
            _ = ctrl_c.as_mut() => {
                let outcome = cancel_task(client, base_url, task_id).await;
                let _ = crate::state::update_async_task_status(task_id, "cancelled", None);
                if options.ndjson {
                    crate::print_stdout_line(
                        &json!({ "taskId": task_id, "type": "task.cancelled", "message": outcome })
                            .to_string(),
                    );
                } else {
                    follower.renderer.print(&format!(
                        "{} ⏹ {outcome}",
                        follower.renderer.time_prefix()
                    ));
                }
                return Ok(());
            }
        }
    }
}

/// Keep the local task list in sync with what the follower just saw.
///
/// `agent run --wait` has always done this; a follower that skipped it left a
/// finished task looking `processing` to every local reader until the next
/// backend refresh.
fn track_terminal(task_id: &str, status: &str) {
    let word = match status {
        "cancelled" => "cancelled",
        "done" => "completed",
        _ => "failed",
    };
    let _ = crate::state::update_async_task_status(task_id, word, None);
}

/// Print the terminal line and the task's result; non-zero exit on failure.
async fn print_completion(
    client: &Client,
    base_url: &str,
    follower: &mut TaskFollower,
    status: &str,
    ndjson: bool,
) -> Result<(), String> {
    let elapsed = follower.elapsed();
    track_terminal(&follower.task_id, status);
    match status {
        "done" => {
            let result = get_command_result(client, base_url, &follower.task_id).await?;
            // Parity with `--wait`: a `--json` caller gets the payload in the
            // envelope, not only the human lines (which are suppressed there).
            crate::json_field("raw", crate::json_text_or_value(&result));
            if ndjson {
                crate::print_stdout_line(
                    &json!({
                        "taskId": follower.task_id,
                        "type": "task.result",
                        "status": "done",
                        "elapsedMs": elapsed.as_millis() as u64,
                        "result": crate::json_text_or_value(&result),
                    })
                    .to_string(),
                );
            } else {
                follower.renderer.print(&format!(
                    "✔ Agent completed in {:.1}s:",
                    elapsed.as_secs_f64()
                ));
                follower.renderer.print(&result);
            }
            Ok(())
        }
        "cancelled" => {
            if !ndjson {
                follower
                    .renderer
                    .print(&format!("{} ⏹ cancelled", follower.renderer.time_prefix()));
            }
            Err(format!(
                "Agent task {} was cancelled. Use 'agent status {}' to see its final state.",
                follower.task_id, follower.task_id
            ))
        }
        _ => {
            let reason = status_failure_reason(client, base_url, &follower.task_id)
                .await
                .unwrap_or_else(|| "the task reported failure".to_string());
            if ndjson {
                crate::print_stdout_line(
                    &json!({
                        "taskId": follower.task_id,
                        "type": "task.failed",
                        "status": status,
                        "message": reason,
                    })
                    .to_string(),
                );
            } else {
                follower.renderer.print(&format!(
                    "{} ✖ failed after {:.1}s: {}",
                    follower.renderer.time_prefix(),
                    elapsed.as_secs_f64(),
                    truncate(&reason, RESULT_PREVIEW)
                ));
            }
            Err(format!("Agent task failed: {reason}"))
        }
    }
}

/// The human-readable failure reason of a task, from its status envelope.
async fn status_failure_reason(client: &Client, base_url: &str, task_id: &str) -> Option<String> {
    let body = get_command_status(client, base_url, task_id).await.ok()?;
    let progress = parse_status_progress(&body);
    progress.message
}

/// Cancel a task and describe the outcome in one line.
async fn cancel_task(client: &Client, base_url: &str, task_id: &str) -> String {
    match cancel_command_task(client, base_url, task_id).await {
        Ok(response) if response.is_success() => {
            let parsed: Value = serde_json::from_str(&response.body).unwrap_or(Value::Null);
            if parsed
                .get("cancelled")
                .and_then(|v| v.as_bool())
                .unwrap_or(false)
            {
                format!("Agent task {task_id} cancelled.")
            } else {
                let reason = parsed
                    .get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("the task was not running");
                format!("Agent task {task_id} was not cancelled: {reason}")
            }
        }
        Ok(response) => format!(
            "Could not cancel agent task {task_id}: HTTP {}",
            response.status
        ),
        Err(error) => format!("Could not cancel agent task {task_id}: {error}"),
    }
}

fn color_enabled() -> bool {
    io::stdout().is_terminal() && !crate::quiet_active() && !crate::json_active()
}

fn quiet_enabled() -> bool {
    crate::quiet_active()
}

// ---------------------------------------------------------------------------
// Interactive session
// ---------------------------------------------------------------------------

/// The interactive session state: the task in flight (if any) plus the
/// per-session knobs `/coding` and `/noop-limit` change.
struct Repl {
    client: Client,
    base_url: String,
    session_name: Option<String>,
    generation: u32,
    engine: String,
    noop_limit: Option<i64>,
    coding: bool,
    fresh_workspace: bool,
    tty: bool,
    quiet: bool,
    current: Option<TaskFollower>,
    last_task: Option<String>,
    /// Cursor of the last finished task, so `/events` can show its newest steps
    /// instead of the oldest page.
    last_task_seq: u64,
    /// Locally counted statistics of the last finished task, kept so `/stats`
    /// can answer after its follower is gone (the fallback for a backend without
    /// the stats endpoint).
    last_local_stats: Option<TaskStatsReport>,
    prompt_pending: bool,
}

impl Repl {
    fn new(
        client: &Client,
        base_url: &str,
        params: &Value,
        session_name: Option<&str>,
    ) -> Self {
        let engine = params
            .get("engine")
            .and_then(|v| v.as_str())
            .unwrap_or("cli")
            .to_string();
        let noop_limit = params.get("noopLimit").and_then(|v| v.as_i64());
        let coding = params
            .get("coding")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let fresh_workspace = params
            .get("freshWorkspace")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);

        Self {
            client: client.clone(),
            base_url: base_url.to_string(),
            session_name: session_name.map(str::to_string),
            generation: 0,
            engine,
            noop_limit,
            coding,
            fresh_workspace,
            tty: io::stdin().is_terminal(),
            quiet: quiet_enabled(),
            current: None,
            last_task: None,
            last_task_seq: 0,
            last_local_stats: None,
            prompt_pending: true,
        }
    }

    /// The session this turn submits to: the user's `-s` name, or a derived one
    /// after `/new`.
    fn session_id(&self) -> Option<String> {
        match &self.session_name {
            Some(name) if self.generation == 0 => Some(name.clone()),
            Some(name) => Some(format!("{name}-{}", self.generation)),
            None if self.generation == 0 => None,
            None => Some(format!("interactive-{}", self.generation)),
        }
    }

    fn banner(&self) {
        if !self.tty || !human_output_enabled(self.quiet) {
            return;
        }
        let session = self.session_id().unwrap_or_else(|| "default".to_string());
        crate::print_stdout_line(&format!(
            "Browser4 agent · session={session} · engine={} · type /help for commands",
            self.engine
        ));
    }

    fn prompt(&mut self) {
        if !self.tty || !human_output_enabled(self.quiet) {
            self.prompt_pending = false;
            return;
        }
        let mut stdout = io::stdout();
        let _ = write!(stdout, "agent> ");
        let _ = stdout.flush();
        self.prompt_pending = false;
    }

    fn out(&self, line: &str) {
        if !human_output_enabled(self.quiet) {
            return;
        }
        crate::print_stdout_line(line);
    }

    /// Run until the user quits (or stdin reaches EOF).
    ///
    /// A line typed while a task is running is a steering message; a line typed
    /// while idle starts a task. Both paths are the interactive counterpart of
    /// the one-shot form, not a different execution path.
    async fn run(mut self, initial_task: Option<String>) -> Result<(), String> {
        self.banner();

        if let Some(task) = initial_task.filter(|task| !task.trim().is_empty()) {
            // A bad initial task (a missing LLM key, a backend hiccup) must not
            // throw the user out of an interactive session they just opened.
            if let Err(error) = self.start_task(task.trim().to_string()).await {
                self.out(&format!("✖ {error}"));
            }
        }

        let mut lines = spawn_stdin_reader();
        let mut ctrl_c = Box::pin(tokio::signal::ctrl_c());
        let mut ticker = tokio::time::interval(EVENT_POLL_INTERVAL);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

        loop {
            if self.prompt_pending && self.current.is_none() {
                self.prompt();
            }

            tokio::select! {
                line = lines.recv() => {
                    let Some(line) = line else { break };
                    if !self.handle_line(line).await? {
                        break;
                    }
                }
                _ = ticker.tick() => {
                    if self.current.is_some() {
                        if let Err(error) = self.poll_current().await {
                            // The follower gave up (transport failures for the whole
                            // window, unknown task id, …): report it and keep the
                            // session alive instead of tearing down the REPL.
                            self.out(&format!("⚠ {error}"));
                            if let Some(follower) = self.current.take() {
                                self.release_current(follower, "unknown", false);
                            }
                            self.prompt_pending = true;
                        }
                    }
                }
                _ = ctrl_c.as_mut() => {
                    match self.current.take() {
                        Some(follower) => {
                            let outcome = cancel_task(&self.client, &self.base_url, &follower.task_id).await;
                            self.out(&format!("⏹ {outcome}"));
                            self.release_current(follower, "cancelled", true);
                            self.prompt_pending = true;
                        }
                        None => break,
                    }
                    ctrl_c = Box::pin(tokio::signal::ctrl_c());
                }
            }
        }

        self.out("");
        Ok(())
    }

    /// Handle one input line. Returns `false` when the session should end.
    async fn handle_line(&mut self, line: String) -> Result<bool, String> {
        let line = line.trim().to_string();
        if line.is_empty() {
            self.prompt_pending = true;
            return Ok(true);
        }

        if let Some(command) = line.strip_prefix('/') {
            return self.handle_slash_command(command).await;
        }

        match self.current.as_ref().map(|follower| follower.task_id.clone()) {
            Some(task_id) => {
                self.steer(&task_id, &line).await;
                self.prompt_pending = self.current.is_none();
            }
            None => {
                // Submission is a one-off failure (LLM key, backend error): report
                // it and stay in the session.
                if let Err(error) = self.start_task(line).await {
                    self.out(&format!("✖ {error}"));
                    self.prompt_pending = true;
                }
            }
        }
        Ok(true)
    }

    async fn steer(&mut self, task_id: &str, text: &str) {
        match send_command_input(&self.client, &self.base_url, task_id, text).await {
            Ok(response) if response.is_success() => {
                let parsed: Value = serde_json::from_str(&response.body).unwrap_or(Value::Null);
                if parsed.get("accepted").and_then(|v| v.as_bool()) == Some(true) {
                    self.out("↪ steering queued — the agent sees it at its next model round");
                } else {
                    let reason = parsed
                        .get("message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("the task is no longer accepting input");
                    self.out(&format!("⚠ not delivered: {reason}"));
                }
            }
            Ok(response) if response.is_missing_endpoint() => self.out(
                "⚠ this backend cannot accept steering input; the line was ignored \
                 (upgrade the backend to inject messages into a running task)",
            ),
            Ok(response) => self.out(&format!(
                "⚠ steering failed: HTTP {} {}",
                response.status,
                truncate(response.body.trim(), 200)
            )),
            Err(error) => self.out(&format!("⚠ steering failed: {error}")),
        }
    }

    async fn start_task(&mut self, task: String) -> Result<(), String> {
        let extra = self.payload();
        let task_id = crate::submit_agent_task(&self.client, &self.base_url, &task, extra).await?;
        self.out(&format!("▶ task {task_id}"));
        crate::json_field("task_id", json!(&task_id));
        let _ = crate::state::track_async_task(&task_id, "agent", &task, None);
        self.current = Some(TaskFollower::new(
            &task_id,
            false,
            color_enabled(),
            self.quiet,
        ));
        self.prompt_pending = false;
        Ok(())
    }

    /// The `command_run` payload of this turn, honoring the live `/coding` and
    /// `/noop-limit` knobs.
    ///
    /// The payload carries ONLY arguments the backend's `command.run` declares
    /// (`sessionId` is the documented transport argument); the interactive mode
    /// itself is a CLI-side concept and must not leak into the tool call.
    fn payload(&self) -> Value {
        crate::build_agent_run_extra(
            &self.engine,
            self.noop_limit,
            self.coding,
            self.fresh_workspace,
            self.session_id().as_deref(),
        )
    }

    async fn poll_current(&mut self) -> Result<(), String> {
        let Some(follower) = self.current.as_mut() else {
            return Ok(());
        };
        match follower.poll(&self.client, &self.base_url).await? {
            PollOutcome::Running => Ok(()),
            PollOutcome::Done { status } => self.complete_current(status).await,
        }
    }

    /// Remember a task whose follower is going away: its id, its cursor, and the
    /// steps this session counted itself.
    ///
    /// The saved tally is what `/stats` answers with when the backend serves no
    /// counters for the task — without it, a finished task's numbers died with
    /// its follower.
    fn release_current(&mut self, follower: TaskFollower, status: &str, is_done: bool) {
        let elapsed_ms = follower.elapsed().as_millis() as u64;
        let observed = follower.observed.clone();
        self.last_task = Some(follower.task_id.clone());
        self.last_task_seq = follower.after;
        self.last_local_stats = Some(TaskStatsReport {
            task_id: follower.task_id,
            status: status.to_string(),
            is_done,
            elapsed_ms: Some(elapsed_ms),
            stats: observed,
            local: true,
        });
    }

    async fn complete_current(&mut self, status: String) -> Result<(), String> {
        let Some(follower) = self.current.take() else {
            return Ok(());
        };
        let task_id = follower.task_id.clone();
        let elapsed = follower.elapsed();
        self.release_current(follower, &status, true);
        self.prompt_pending = true;

        match status.as_str() {
            "done" => {
                let result = get_command_result(&self.client, &self.base_url, &task_id).await?;
                self.out(&format!(
                    "✔ done in {:.1}s · {}",
                    elapsed.as_secs_f64(),
                    task_id
                ));
                self.out(&result);
                crate::json_field("raw", crate::json_text_or_value(&result));
                let _ = crate::state::update_async_task_status(&task_id, "completed", None);
            }
            "cancelled" => {
                self.out(&format!("⏹ cancelled · {task_id}"));
                let _ = crate::state::update_async_task_status(&task_id, "cancelled", None);
            }
            _ => {
                let reason = status_failure_reason(&self.client, &self.base_url, &task_id)
                    .await
                    .unwrap_or_else(|| "the task reported failure".to_string());
                self.out(&format!("✖ failed · {task_id} — {reason}"));
                let _ = crate::state::update_async_task_status(&task_id, "failed", None);
            }
        }

        Ok(())
    }

    async fn handle_slash_command(&mut self, command: &str) -> Result<bool, String> {
        let mut parts = command.splitn(2, char::is_whitespace);
        let name = parts.next().unwrap_or_default();
        let argument = parts.next().unwrap_or_default().trim();

        match name {
            "quit" | "exit" | "q" => return Ok(false),
            "help" | "h" | "?" => self.print_help(),
            "status" => self.print_status().await,
            "result" => self.print_result().await,
            "cancel" | "stop" => self.cancel_current().await,
            "new" => self.new_session(),
            "coding" => self.set_coding(argument),
            "noop-limit" => self.set_noop_limit(argument),
            "session" => {
                let session = self.session_id().unwrap_or_else(|| "default".to_string());
                self.out(&format!("session: {session}"));
            }
            "events" => self.print_events(argument).await,
            // `/stats [task-id]`: no argument means the running (or last) task.
            "stats" => self.print_stats(argument).await,
            other => self.out(&format!(
                "unknown command /{other} — type /help for the list"
            )),
        }
        self.prompt_pending = self.current.is_none();
        Ok(true)
    }

    fn print_help(&self) {
        self.out("Commands:");
        self.out("  /help              this list");
        self.out("  /status            status of the running (or last) task");
        self.out("  /result            print the last finished task's result");
        self.out("  /cancel            cancel the running task (same as Ctrl+C)");
        self.out("  /new               start a new session (fresh agent context)");
        self.out("  /session           print the current session id");
        self.out("  /coding [on|off]   treat following tasks as coding tasks");
        self.out("  /noop-limit <n>    consecutive no-op abort threshold (blank resets)");
        self.out("  /events [n]        raw JSON of the last n steps (default 20)");
        self.out("  /stats [task-id]   run statistics: LLM rounds/requests, tokens, tool calls");
        self.out("  /quit              leave the session");
        self.out("");
        self.out("Anything else is a task. Type a line while a task runs to steer it.");
    }

    async fn print_status(&mut self) {
        let task_id = self
            .current
            .as_ref()
            .map(|follower| follower.task_id.clone())
            .or_else(|| self.last_task.clone());
        let Some(task_id) = task_id else {
            self.out("no task yet — type a task to start one");
            return;
        };

        match get_command_status(&self.client, &self.base_url, &task_id).await {
            Ok(body) => {
                let progress = parse_status_progress(&body);
                let steps = if progress.tools > 0 {
                    format!(" · {} step(s)", progress.tools)
                } else {
                    String::new()
                };
                let message = progress.message.unwrap_or_default();
                self.out(&format!(
                    "{task_id} · {}{steps} {message}",
                    progress.word
                ));
            }
            Err(error) => self.out(&format!("could not read status of {task_id}: {error}")),
        }
    }

    /// `/stats [task-id]`: what this run cost — LLM rounds and requests, tokens,
    /// tool calls.
    ///
    /// The numbers are the backend's: its counters keep counting steps that have
    /// already fallen out of the bounded event log, which a client-side tally
    /// cannot. Against a backend with no stats endpoint the session falls back to
    /// what it observed itself and labels the report as such.
    async fn print_stats(&mut self, argument: &str) {
        let task_id = match argument.trim() {
            "" => self
                .current
                .as_ref()
                .map(|follower| follower.task_id.clone())
                .or_else(|| self.last_task.clone()),
            named => Some(named.to_string()),
        };
        let Some(task_id) = task_id else {
            self.out("no task yet — type a task to start one");
            return;
        };

        match get_command_stats(&self.client, &self.base_url, &task_id).await {
            Ok(response) if response.is_success() => match parse_task_stats(&response.body) {
                Ok(report) => self.render_stats(report),
                Err(error) => self.out(&format!("could not read stats of {task_id}: {error}")),
            },
            // 404/405/501: an older backend (no stats endpoint) or a run log that
            // expired. Both leave this session's own tally as the only number.
            Ok(response) if response.is_missing_endpoint() => self.print_local_stats(&task_id),
            Ok(response) => self.out(&format!(
                "could not read stats of {task_id}: HTTP {} {}",
                response.status,
                truncate(response.body.trim(), 200)
            )),
            Err(error) => self.out(&format!("could not read stats of {task_id}: {error}")),
        }
    }

    /// The fallback report: counters this session kept while following the task.
    ///
    /// Honest about its limits — steps the session never saw (a task followed from
    /// its middle, a coding round that outran the poll interval, a backend that
    /// serves no step log at all) are missing, so the numbers can only undercount.
    fn print_local_stats(&self, task_id: &str) {
        let live = self
            .current
            .as_ref()
            .filter(|follower| follower.task_id == task_id)
            .map(|follower| TaskStatsReport {
                task_id: follower.task_id.clone(),
                status: "running".to_string(),
                is_done: false,
                elapsed_ms: Some(follower.elapsed().as_millis() as u64),
                stats: follower.observed.clone(),
                local: true,
            });
        // The saved tally belongs to the LAST task: naming another id must not
        // report one task's numbers under another task's name.
        let report = live.or_else(|| {
            self.last_local_stats
                .clone()
                .filter(|report| report.task_id == task_id)
        });

        match report {
            Some(report) if report.stats.events > 0 => self.render_stats(report),
            Some(_) => self.out(&format!(
                "no run statistics for {task_id}: this backend reports neither counters nor a \
                 step log, so nothing was observed"
            )),
            None => self.out(&format!(
                "no run statistics for {task_id}: this backend reports none (an older backend, \
                 a restarted one, or an expired run log)"
            )),
        }
    }

    /// Print a statistics report (human lines, plus the `--json` envelope field).
    fn render_stats(&self, report: TaskStatsReport) {
        let mut payload = report.stats.to_json(report.local);
        payload["taskId"] = json!(report.task_id);
        payload["status"] = json!(report.status);
        payload["isDone"] = json!(report.is_done);
        payload["elapsedMs"] = json!(report.elapsed_ms);
        for line in format_stats_report(&report) {
            self.out(&line);
        }
        crate::json_field("stats", payload);
    }

    async fn print_result(&mut self) {
        let Some(task_id) = self.last_task.clone() else {
            self.out("no finished task yet");
            return;
        };
        match get_command_result(&self.client, &self.base_url, &task_id).await {
            Ok(result) => self.out(&result),
            Err(error) => self.out(&format!("could not read result of {task_id}: {error}")),
        }
    }

    async fn cancel_current(&mut self) {
        let Some(follower) = self.current.take() else {
            self.out("no task is running");
            return;
        };
        let outcome = cancel_task(&self.client, &self.base_url, &follower.task_id).await;
        self.out(&format!("⏹ {outcome}"));
        // Keep the tally: `/stats` on a cancelled task still answers (from the
        // backend when it has counters, from this session otherwise).
        self.release_current(follower, "cancelled", true);
        self.prompt_pending = true;
    }

    fn new_session(&mut self) {
        self.generation += 1;
        let session = self.session_id().unwrap_or_else(|| "default".to_string());
        self.out(&format!(
            "new session: {session} (the next task starts a fresh agent context)"
        ));
    }

    fn set_coding(&mut self, argument: &str) {
        match argument {
            "on" | "true" | "1" => self.coding = true,
            "off" | "false" | "0" => self.coding = false,
            "" => self.coding = !self.coding,
            other => {
                self.out(&format!("usage: /coding [on|off] (got '{other}')"));
                return;
            }
        }
        self.out(&format!(
            "coding mode {}",
            if self.coding { "on" } else { "off" }
        ));
    }

    fn set_noop_limit(&mut self, argument: &str) {
        if argument.is_empty() {
            self.noop_limit = None;
            self.out("noop limit reset to the server default");
            return;
        }
        match argument.parse::<i64>() {
            Ok(limit) if limit > 0 => {
                self.noop_limit = Some(limit);
                self.out(&format!("noop limit set to {limit}"));
            }
            _ => self.out(&format!(
                "usage: /noop-limit <positive integer> (got '{argument}')"
            )),
        }
    }

    /// `/events [n]`: the newest [n] raw step events (default 20).
    async fn print_events(&mut self, argument: &str) {
        let count = match argument.trim() {
            "" => 20usize,
            value => match value.parse::<usize>() {
                Ok(parsed) if parsed > 0 => parsed.min(EVENTS_PAGE_LIMIT),
                _ => {
                    self.out(&format!(
                        "usage: /events [positive integer] (got '{value}')"
                    ));
                    return;
                }
            },
        };

        let (task_id, cursor) = match self.current.as_ref() {
            Some(follower) => (Some(follower.task_id.clone()), follower.after),
            None => (self.last_task.clone(), self.last_task_seq),
        };
        let Some(task_id) = task_id else {
            self.out("no task yet");
            return;
        };

        // Fetch the page that ENDS at the newest step: asking from cursor 0 would
        // show the oldest page of a long run instead of what just happened.
        let after = cursor.saturating_sub(count as u64);
        match get_command_events(&self.client, &self.base_url, &task_id, after, count).await {
            Ok(response) if response.is_success() => {
                match parse_events_page(&response.body) {
                    Ok(page) => {
                        let start = page.events.len().saturating_sub(count);
                        for event in &page.events[start..] {
                            self.out(&event.to_json(&task_id).to_string());
                        }
                    }
                    Err(error) => self.out(&format!("could not read events: {error}")),
                }
            }
            Ok(response) if response.is_missing_endpoint() => {
                self.out("this backend has no events endpoint")
            }
            Ok(response) => self.out(&format!(
                "could not read events: HTTP {}",
                response.status
            )),
            Err(error) => self.out(&format!("could not read events: {error}")),
        }
    }
}

/// Read stdin lines on a dedicated thread and forward them to the async loop.
///
/// A plain thread (not `spawn_blocking`) because the read is blocking for the
/// whole session: the thread exits at EOF, and the process exits when the
/// session ends, so a pending read can never leak past the CLI.
fn spawn_stdin_reader() -> tokio::sync::mpsc::UnboundedReceiver<String> {
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    std::thread::spawn(move || {
        let stdin = io::stdin();
        for line in stdin.lock().lines() {
            match line {
                Ok(line) => {
                    if tx.send(line).is_err() {
                        return;
                    }
                }
                Err(_) => return,
            }
        }
    });
    rx
}

/// Run the interactive session (`agent run -i`).
///
/// The optional `task` runs immediately; the session then waits for input.
/// Non-TTY stdin is supported (no banner, no prompt), which is what keeps this
/// testable from a piped harness.
pub async fn run_interactive(
    client: &Client,
    base_url: &str,
    params: &Value,
    session_name: Option<&str>,
) -> Result<(), String> {
    let initial_task = params
        .get("task")
        .and_then(|v| v.as_str())
        .map(str::to_string);
    Repl::new(client, base_url, params, session_name)
        .run(initial_task)
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event(kind: &str) -> TaskEvent {
        TaskEvent {
            seq: 1,
            kind: kind.to_string(),
            ..Default::default()
        }
    }

    #[test]
    fn parse_events_page_reads_the_full_payload() {
        let body = r#"{
            "taskId": "t1",
            "status": "running",
            "done": false,
            "nextSeq": 7,
            "events": [
                {"seq": 6, "type": "tool.call", "epochMillis": 1700000000000, "tool": "coding.read", "arguments": "{\"file\":\"a.kt\"}", "turn": 2},
                {"seq": 7, "type": "tool.result", "epochMillis": 1700000000001, "tool": "coding.read", "ok": true, "durationMs": 12, "text": "ok"}
            ]
        }"#;

        let page = parse_events_page(body).expect("page must parse");
        assert_eq!(page.status, "running");
        assert!(!page.done);
        assert_eq!(page.next_seq, 7);
        assert_eq!(page.events.len(), 2);
        assert_eq!(page.events[0].tool.as_deref(), Some("coding.read"));
        assert_eq!(page.events[0].turn, Some(2));
        assert_eq!(page.events[1].ok, Some(true));
        assert_eq!(page.events[1].duration_ms, Some(12));
    }

    #[test]
    fn parse_events_page_ignores_malformed_entries() {
        let body = r#"{"status":"running","done":false,"nextSeq":2,
            "events":[{"noType":true},{"seq":2,"type":"model.text","text":"hi"}]}"#;
        let page = parse_events_page(body).expect("page must parse");
        assert_eq!(page.events.len(), 1);
        assert_eq!(page.events[0].kind, "model.text");
    }

    #[test]
    fn parse_events_page_keeps_a_zero_cursor_when_empty() {
        let page = parse_events_page(r#"{"status":"running","done":false,"nextSeq":5}"#)
            .expect("page must parse");
        assert_eq!(page.next_seq, 5);
        assert!(page.events.is_empty());
    }

    #[test]
    fn parse_events_page_rejects_non_json() {
        assert!(parse_events_page("<html>oops</html>").is_err());
    }

    #[test]
    fn parse_status_progress_prefers_the_shared_envelope() {
        let progress = parse_status_progress(
            r#"{"status":"done","statusCode":200,"message":"finished","processed":12}"#,
        );
        assert!(progress.done);
        assert_eq!(progress.word, "done");
        assert_eq!(progress.message.as_deref(), Some("finished"));
        assert_eq!(progress.tools, 12);
    }

    #[test]
    fn parse_status_progress_maps_legacy_process_state() {
        let progress = parse_status_progress(r#"{"processState":"in_progress","message":"working"}"#);
        assert!(!progress.done);
        assert_eq!(progress.word, "running");
        assert_eq!(progress.message.as_deref(), Some("working"));
    }

    #[test]
    fn parse_status_progress_reports_error_as_the_message() {
        let progress =
            parse_status_progress(r#"{"status":"failed","error":"no LLM configured","isDone":true}"#);
        assert!(progress.done);
        assert_eq!(progress.word, "failed");
        assert_eq!(progress.message.as_deref(), Some("no LLM configured"));
    }

    #[test]
    fn renderer_formats_tool_calls_with_index_and_arguments() {
        let mut renderer = Renderer::new(Instant::now(), false, false);
        let mut call = event("tool.call");
        call.tool = Some("coding.read".to_string());
        call.arguments = Some(r#"{"file":"a.kt"}"#.to_string());

        let line = renderer.format(&call).expect("tool.call renders");
        assert!(line.contains("#1"), "{line}");
        assert!(line.contains("coding.read"), "{line}");
        assert!(line.contains(r#"({"file":"a.kt"})"#), "{line}");
    }

    #[test]
    fn renderer_skips_round_markers_and_fast_successes() {
        let mut renderer = Renderer::new(Instant::now(), false, false);
        assert!(renderer.format(&event("round.start")).is_none());

        let mut result = event("tool.result");
        result.tool = Some("coding.read".to_string());
        result.ok = Some(true);
        result.duration_ms = Some(50);
        assert!(renderer.format(&result).is_none());
    }

    #[test]
    fn renderer_reports_slow_and_failed_tools() {
        let mut renderer = Renderer::new(Instant::now(), false, false);

        let mut slow = event("tool.result");
        slow.tool = Some("b4.run".to_string());
        slow.ok = Some(true);
        slow.duration_ms = Some(3_500);
        let line = renderer.format(&slow).expect("slow tool renders");
        assert!(line.contains("ok in 3.5s"), "{line}");

        let mut failed = event("tool.result");
        failed.tool = Some("coding.shell".to_string());
        failed.ok = Some(false);
        failed.duration_ms = Some(120);
        failed.text = Some("[fail] exit code 1".to_string());
        let line = renderer.format(&failed).expect("failure renders");
        assert!(line.contains("failed after 120ms"), "{line}");
        assert!(line.contains("exit code 1"), "{line}");
    }

    #[test]
    fn renderer_never_emits_ansi_escape_codes_without_a_terminal() {
        let mut renderer = Renderer::new(Instant::now(), false, false);
        let mut call = event("tool.call");
        call.tool = Some("coding.read".to_string());
        let line = renderer.format(&call).expect("tool.call renders");
        assert!(!line.contains('\x1b'), "unexpected ANSI in {line:?}");
    }

    #[test]
    fn truncate_keeps_multibyte_characters_intact() {
        assert_eq!(truncate("abcdef", 3), "abc…");
        assert_eq!(truncate("中文测试", 2), "中文…");
        assert_eq!(truncate("abc", 10), "abc");
    }

    #[test]
    fn format_elapsed_is_minutes_and_seconds() {
        assert_eq!(format_elapsed(Duration::from_secs(7)), "00:07");
        assert_eq!(format_elapsed(Duration::from_secs(75)), "01:15");
        assert_eq!(format_elapsed(Duration::from_secs(3_600)), "60:00");
    }

    #[test]
    fn task_event_to_json_omits_absent_fields() {
        let mut call = event("tool.call");
        call.tool = Some("coding.read".to_string());
        call.seq = 9;
        let json = call.to_json("t1");
        assert_eq!(json["taskId"], "t1");
        assert_eq!(json["seq"], 9);
        assert_eq!(json["tool"], "coding.read");
        assert!(json.get("ok").is_none());
        assert!(json.get("durationMs").is_none());
    }

    #[test]
    fn repl_session_rotates_only_after_new() {
        let client = Client::builder().build().expect("client");
        let params = json!({});
        let mut repl = Repl::new(&client, "http://localhost:1", &params, Some("work"));
        assert_eq!(repl.session_id().as_deref(), Some("work"));

        repl.new_session();
        assert_eq!(repl.session_id().as_deref(), Some("work-1"));

        let mut unnamed = Repl::new(&client, "http://localhost:1", &params, None);
        assert_eq!(unnamed.session_id(), None);
        unnamed.new_session();
        assert_eq!(unnamed.session_id().as_deref(), Some("interactive-1"));
    }

    #[test]
    fn repl_payload_honors_the_live_knobs() {
        let client = Client::builder().build().expect("client");
        let params = json!({"engine": "cli", "noopLimit": 5, "coding": true});
        let mut repl = Repl::new(&client, "http://localhost:1", &params, Some("work"));

        let payload = repl.payload();
        assert_eq!(payload["engine"], "cli");
        assert_eq!(payload["noopLimit"], 5);
        assert_eq!(payload["coding"], true);
        assert_eq!(payload["sessionId"], "work");
        assert!(
            payload.get("interactive").is_none(),
            "the interactive flag is CLI-side and must not leak into command_run"
        );

        repl.set_coding("off");
        repl.set_noop_limit("");
        let payload = repl.payload();
        assert!(payload.get("coding").is_none());
        assert!(payload.get("noopLimit").is_none());
    }

    #[test]
    fn repl_slash_commands_that_change_state_do_not_touch_the_backend() {
        let client = Client::builder().build().expect("client");
        let params = json!({});
        let mut repl = Repl::new(&client, "http://localhost:1", &params, None);

        repl.set_noop_limit("8");
        assert_eq!(repl.noop_limit, Some(8));
        repl.set_noop_limit("nope");
        assert_eq!(
            repl.noop_limit,
            Some(8),
            "an invalid value must not reset the limit"
        );
        repl.set_noop_limit("");
        assert_eq!(repl.noop_limit, None);
    }

    #[test]
    fn format_tool_arguments_collapses_empty_objects() {
        assert_eq!(format_tool_arguments("{}"), "");
        assert_eq!(format_tool_arguments("  "), "");
        assert_eq!(format_tool_arguments(r#"{"a":1}"#), r#"({"a":1})"#);
    }

    // ---- run statistics (`/stats`) -----------------------------------------

    fn stats_event(kind: &str, tool: Option<&str>, ok: Option<bool>) -> TaskEvent {
        TaskEvent {
            seq: 1,
            kind: kind.to_string(),
            tool: tool.map(str::to_string),
            ok,
            ..Default::default()
        }
    }

    fn a_served_stats_report() -> TaskStatsReport {
        TaskStatsReport {
            task_id: "t1".to_string(),
            status: "done".to_string(),
            is_done: true,
            elapsed_ms: Some(41_300),
            stats: RunStats {
                rounds: 3,
                model_requests: 11,
                tool_calls: 39,
                tool_failures: 2,
                steer_messages: 1,
                input_tokens: 48_231,
                output_tokens: 6_120,
                total_tokens: 54_351,
                events: 132,
                dropped_events: 0,
                tool_counts: vec![
                    ("b4.run".to_string(), 12),
                    ("browser_snapshot".to_string(), 8),
                ],
            },
            local: false,
        }
    }

    #[test]
    fn parse_task_stats_reads_counters_and_the_tool_breakdown() {
        let body = r#"{
            "taskId": "t1",
            "status": "done",
            "processState": "completed",
            "isDone": true,
            "elapsedMs": 41300,
            "stats": {
                "rounds": 3, "modelRequests": 11, "toolCalls": 39, "toolFailures": 2,
                "steerMessages": 1, "inputTokens": 48231, "outputTokens": 6120,
                "totalTokens": 54351, "events": 132, "droppedEvents": 4,
                "toolCounts": {"b4.run": 12, "browser_snapshot": 8}
            }
        }"#;

        let report = parse_task_stats(body).expect("stats must parse");
        assert_eq!(report.task_id, "t1");
        assert_eq!(report.status, "done");
        assert!(report.is_done);
        assert_eq!(report.elapsed_ms, Some(41_300));
        assert!(!report.local, "a served report is not a local tally");

        let stats = &report.stats;
        assert_eq!(stats.rounds, 3);
        assert_eq!(stats.model_requests, 11);
        assert_eq!(stats.tool_calls, 39);
        assert_eq!(stats.tool_failures, 2);
        assert_eq!(stats.steer_messages, 1);
        assert_eq!(stats.input_tokens, 48_231);
        assert_eq!(stats.output_tokens, 6_120);
        assert_eq!(stats.total_tokens, 54_351);
        assert_eq!(stats.events, 132);
        assert_eq!(stats.dropped_events, 4);
        assert!(stats.tokens_reported());
        assert_eq!(
            stats.top_tools(2),
            vec![("b4.run".to_string(), 12), ("browser_snapshot".to_string(), 8)]
        );
    }

    #[test]
    fn parse_task_stats_tolerates_missing_fields_but_not_a_counter_less_body() {
        let report = parse_task_stats(r#"{"taskId":"t2","stats":{}}"#)
            .expect("an empty counter set is still a report");
        assert_eq!(report.status, "unknown");
        assert_eq!(report.stats.rounds, 0);
        assert!(!report.stats.tokens_reported());

        assert!(
            parse_task_stats(r#"{"taskId":"t2"}"#).is_err(),
            "a body with no counters has nothing to report"
        );
        assert!(parse_task_stats("<html>oops</html>").is_err());
    }

    #[test]
    fn run_stats_observe_mirrors_the_backend_aggregation() {
        let mut stats = RunStats::default();
        stats.observe(&stats_event("task.started", None, None));
        stats.observe(&stats_event("round.start", None, None));
        stats.observe(&stats_event("tool.call", Some("b4.run"), None));
        stats.observe(&stats_event("tool.call", Some("b4.run"), None));
        stats.observe(&stats_event("tool.result", Some("b4.run"), Some(false)));
        stats.observe(&stats_event("tool.result", Some("b4.run"), Some(true)));
        stats.observe(&stats_event("steer", None, None));
        let mut usage = stats_event("run.usage", None, None);
        usage.input_tokens = Some(100);
        usage.output_tokens = Some(20);
        stats.observe(&usage);

        assert_eq!(stats.events, 8);
        assert_eq!(stats.rounds, 1);
        assert_eq!(stats.tool_calls, 2);
        assert_eq!(stats.tool_failures, 1);
        assert_eq!(stats.steer_messages, 1);
        assert_eq!(stats.model_requests, 1);
        assert_eq!(stats.input_tokens, 100);
        assert_eq!(stats.output_tokens, 20);
        assert_eq!(
            stats.total_tokens, 120,
            "a usage step without a provider total is input + output"
        );
        assert_eq!(stats.top_tools(3), vec![("b4.run".to_string(), 2)]);
    }

    #[test]
    fn format_stats_report_lists_llm_tokens_and_tools() {
        let lines = format_stats_report(&a_served_stats_report()).join("\n");

        assert!(lines.contains("t1 · done · 41.3s"), "{lines}");
        assert!(lines.contains("3 round(s) · 11 request(s)"), "{lines}");
        assert!(
            lines.contains("in 48,231 · out 6,120 · total 54,351"),
            "{lines}"
        );
        assert!(
            lines.contains("39 call(s) · 2 failed (b4.run 12, browser_snapshot 8)"),
            "{lines}"
        );
        assert!(lines.contains("132"), "{lines}");
        assert!(lines.contains("1 steering message(s)"), "{lines}");
        assert!(
            !lines.contains('⚠'),
            "a served report must never be labelled local:\n{lines}"
        );
    }

    #[test]
    fn format_stats_report_is_honest_about_unknown_tokens_and_local_counts() {
        let report = TaskStatsReport {
            task_id: "t1".to_string(),
            status: "running".to_string(),
            is_done: false,
            elapsed_ms: None,
            stats: RunStats {
                model_requests: 2,
                ..Default::default()
            },
            local: true,
        };
        let lines = format_stats_report(&report).join("\n");
        assert!(
            lines.contains("not reported by the provider"),
            "2 requests with no token counters is 'unknown', not '0':\n{lines}"
        );
        assert!(lines.contains("counted by this session"), "{lines}");
        assert!(lines.contains("t1 · running"), "no duration must not print one:\n{lines}");

        // A queued (or never-inferred) task has made no request at all.
        let mut queued = report.clone();
        queued.stats.model_requests = 0;
        let lines = format_stats_report(&queued).join("\n");
        assert!(lines.contains("no model request yet"), "{lines}");
    }

    #[test]
    fn format_run_duration_scales_to_minutes_and_hours() {
        assert_eq!(format_run_duration(850), "0.8s");
        assert_eq!(format_run_duration(18_400), "18.4s");
        assert_eq!(format_run_duration(192_000), "3m12s");
        assert_eq!(format_run_duration(3_840_000), "1h04m");
    }

    #[test]
    fn format_thousands_groups_digits() {
        assert_eq!(format_thousands(0), "0");
        assert_eq!(format_thousands(999), "999");
        assert_eq!(format_thousands(1_000), "1,000");
        assert_eq!(format_thousands(54_351), "54,351");
        assert_eq!(format_thousands(1_234_567), "1,234,567");
    }

    #[test]
    fn task_event_to_json_carries_token_usage() {
        let mut usage = event("run.usage");
        usage.input_tokens = Some(120);
        usage.total_tokens = Some(150);

        let json = usage.to_json("t1");
        assert_eq!(json["inputTokens"], 120);
        assert_eq!(json["totalTokens"], 150);
        assert!(json.get("outputTokens").is_none());
    }

    // ---- follower resilience and output modes ------------------------------

    #[test]
    fn output_gates_keep_machine_streams_clean() {
        // NDJSON: a prose notice would make the stream unparseable.
        assert!(!notice_enabled(true, false), "ndjson must stay JSON-only");
        assert!(notice_enabled(false, false));
        assert!(!notice_enabled(false, true), "--quiet suppresses notices");
        assert!(!notice_enabled(true, true));

        // Human output: on by default, off under --quiet. (The `--json` half is
        // exercised by the e2e scenarios — it is process-wide state a unit test
        // must not toggle for its neighbours.)
        assert!(human_output_enabled(false));
        assert!(!human_output_enabled(true));
    }

    #[test]
    fn parse_status_progress_flags_an_unknown_task() {
        let not_found = parse_status_progress(
            r#"{"taskId":"x","status":"failed","statusCode":404,"isDone":true,
                "message":"No task found with id 'x'."}"#,
        );
        assert!(not_found.not_found, "a 404 envelope is not a task failure");
        assert!(not_found.done);

        let running = parse_status_progress(r#"{"status":"running","statusCode":102}"#);
        assert!(!running.not_found);
        assert!(!running.done);

        // The message alone is enough (older envelopes carry no statusCode).
        let message_only = parse_status_progress(
            r#"{"status":"failed","isDone":true,"message":"No agent task found with id 'y'"}"#,
        );
        assert!(message_only.not_found);
    }

    /// A one-connection-per-entry HTTP stub, enough for the follower's GETs.
    fn spawn_http_stub(
        responses: Vec<(u16, &'static str)>,
    ) -> (String, std::thread::JoinHandle<()>) {
        use std::io::{Read, Write};

        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("stub bind");
        let port = listener.local_addr().expect("stub addr").port();
        let handle = std::thread::spawn(move || {
            for (status, body) in responses {
                let Ok((mut stream, _)) = listener.accept() else {
                    return;
                };
                let mut buffer = [0u8; 4096];
                let _ = stream.read(&mut buffer);
                let response = format!(
                    "HTTP/1.1 {status} {}\r\nContent-Type: text/html\r\nContent-Length: {}\r\n\
                     Connection: close\r\n\r\n{body}",
                    if status == 200 { "OK" } else { "Not Found" },
                    body.len()
                );
                let _ = stream.write_all(response.as_bytes());
            }
        });
        (format!("http://127.0.0.1:{port}"), handle)
    }

    #[tokio::test]
    async fn malformed_events_page_is_retried_without_moving_the_cursor() {
        let (base_url, handle) = spawn_http_stub(vec![
            (200, "<html>proxy error</html>"),
            (
                200,
                r#"{"status":"running","done":false,"nextSeq":2,
                    "events":[{"seq":1,"type":"model.text","text":"hi"},
                              {"seq":2,"type":"tool.call","tool":"coding.read"}]}"#,
            ),
        ]);
        let client = Client::builder().build().expect("client");
        // quiet: the retry notice is noise in a unit test's output
        let mut follower = TaskFollower::new("t1", false, false, true);

        let first = follower
            .poll(&client, &base_url)
            .await
            .expect("a malformed page must be retried, not propagated");
        assert!(matches!(first, PollOutcome::Running));
        assert_eq!(follower.after, 0, "the cursor must not move on a bad page");
        assert!(
            follower.first_failure.is_some(),
            "the failure window must start on a bad page"
        );

        // The poll throttle is wall-clock based; a retry must not be dropped.
        follower.last_poll = None;
        let second = follower
            .poll(&client, &base_url)
            .await
            .expect("a valid page must be accepted after a bad one");
        assert!(matches!(second, PollOutcome::Running));
        assert_eq!(follower.after, 2, "the recovered page advances the cursor");
        assert!(
            follower.first_failure.is_none(),
            "a good response resets the failure window"
        );

        handle.join().expect("stub thread");
    }
}
