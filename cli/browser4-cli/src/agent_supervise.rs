//! `agent supervise <task-id>`: one view that answers "is this run approaching the goal,
//! and is it about to be judged failed?" — the question `agent status` and `agent result`
//! cannot answer alone.
//!
//! Issue #652 split a supervisor's needs four ways: is it alive, what is it doing, is it
//! converging, and will it be judged done. The backend now exports the engine's own
//! decisions (`gate.evaluated`, `verify.verdict`, `repair.*`, `nudge`, `access.*`), the
//! progress gauges (`mutations` / `readOnlyRounds`) and the cursor facts
//! (`resynced` / `dropped`); this command is the out-of-the-box view over them, so an
//! operator does not have to read three endpoints and a JSON payload by hand.
//!
//! It is a read-only aggregation of what the CLI already knows how to call:
//! `command_status` (envelope), `GET /stats`, `GET /events` and `GET /api/agent/{id}/access`.
//! Every source is optional: an older backend, an expired run log or a finished task still
//! produce a useful panel, because a missing fact is reported as unknown instead of as zero.

use super::agent_interactive::{
    format_run_duration, format_token_amount, format_thousands, parse_events_page,
    parse_task_stats, EventsPage, RunStats, TaskEvent, TaskStatsReport,
};
use serde_json::{json, Value};

/// How many retained steps `agent supervise` reads to find the latest decisions.
///
/// The whole retained window: the decision steps of a finished run sit at the very end,
/// but `gate.evaluated` may have been emitted hundreds of steps before the terminal one
/// (a long final round), so a smaller window could miss the rejection that explains the
/// failure.
const SUPERVISE_EVENTS_LIMIT: usize = 1000;

/// The status words after which nothing else will be produced.
const TERMINAL_STATUSES: [&str; 3] = ["done", "failed", "cancelled"];

/// One pending `coding.requestAccess`, as the supervision panel lists it.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct PendingAccess {
    pub(crate) request_id: String,
    pub(crate) effect: String,
    pub(crate) target: String,
}

/// The finish gate's latest verdict (`gate.evaluated`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct GateView {
    pub(crate) passed: bool,
    pub(crate) violations: Vec<String>,
    pub(crate) unlisted_files: Vec<String>,
}

/// One check of a `coding.verify` verdict.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct CheckView {
    pub(crate) name: String,
    pub(crate) target: Option<String>,
    pub(crate) exit_code: Option<i64>,
    pub(crate) ok: bool,
}

/// The latest `coding.verify` verdict (`verify.verdict`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct VerdictView {
    pub(crate) passed: bool,
    pub(crate) module: Option<String>,
    pub(crate) failure_reason: Option<String>,
    pub(crate) failing_checks: Vec<CheckView>,
}

/// The repair loop's latest decision (`repair.round` / `repair.exhausted`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct RepairView {
    pub(crate) round: i64,
    pub(crate) max_rounds: i64,
    pub(crate) reason: Option<String>,
    /// True when the budget ran out (`repair.exhausted`) rather than one round being fed back.
    pub(crate) exhausted: bool,
}

/// What the step log says about the cursor the reader is on.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct WindowView {
    pub(crate) retained: usize,
    pub(crate) first_retained_seq: u64,
    pub(crate) next_seq: u64,
    pub(crate) dropped: u64,
    pub(crate) resynced: bool,
}

/// Everything the supervision panel shows.
#[derive(Debug, Clone, Default, PartialEq)]
pub(crate) struct Supervision {
    pub(crate) task_id: String,
    pub(crate) status: String,
    pub(crate) is_done: bool,
    pub(crate) elapsed_ms: Option<u64>,
    pub(crate) message: Option<String>,
    pub(crate) error_code: Option<String>,
    pub(crate) resumable: Option<bool>,
    /// The status envelope's `failureDetail` (present once the task failed) — the same
    /// evidence `agent result` carries, so the panel works after a backend restart.
    pub(crate) failure_detail: Option<Value>,
    /// Served counters, or `None` when this backend has no run log for the task.
    pub(crate) stats: Option<TaskStatsReport>,
    pub(crate) window: WindowView,
    pub(crate) gate: Option<GateView>,
    pub(crate) verdict: Option<VerdictView>,
    pub(crate) repair: Option<RepairView>,
    /// How many corrective instructions the engine injected (`nudge`).
    pub(crate) nudges: u64,
    pub(crate) pending_access: Vec<PendingAccess>,
    /// The newest decision steps, oldest first (`type`, one-line summary).
    pub(crate) recent_decisions: Vec<(String, String)>,
}

impl Supervision {
    /// True for a task that will not produce anything else.
    pub(crate) fn is_terminal(&self) -> bool {
        self.is_done || TERMINAL_STATUSES.contains(&self.status.as_str())
    }
}

/// Parse the `command_status` envelope (the MCP answer, not the REST `CommandStatus`).
///
/// Errors only when the body is not JSON at all: an envelope that says `failed` with a
/// not-found statusCode is still a valid answer the caller reports as "unknown task".
pub(crate) fn parse_status_envelope(body: &str) -> Result<Supervision, String> {
    let value: Value = serde_json::from_str(body.trim())
        .map_err(|e| format!("could not parse the status response as JSON: {e}"))?;
    let task_id = value
        .get("taskId")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();
    let status = value
        .get("status")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown")
        .to_string();
    let status_code = value.get("statusCode").and_then(|v| v.as_i64());
    let message = value
        .get("message")
        .and_then(|v| v.as_str())
        .or_else(|| value.get("error").and_then(|v| v.as_str()))
        .map(str::to_string);

    Ok(Supervision {
        task_id,
        status: status.clone(),
        is_done: TERMINAL_STATUSES.contains(&status.as_str()),
        elapsed_ms: value.get("elapsedMs").and_then(|v| v.as_u64()),
        message: message.filter(|message| {
            // An unknown id is reported by the caller, not as the run's own message.
            status_code != Some(404) && !message.is_empty()
        }),
        error_code: value
            .get("errorCode")
            .and_then(|v| v.as_str())
            .map(str::to_string),
        resumable: value.get("resumable").and_then(|v| v.as_bool()),
        failure_detail: value.get("failureDetail").filter(|v| v.is_object()).cloned(),
        ..Default::default()
    })
}

/// True when the envelope names an id this backend does not know.
pub(crate) fn status_is_not_found(body: &str) -> bool {
    serde_json::from_str::<Value>(body.trim())
        .ok()
        .and_then(|value| value.get("statusCode").and_then(|v| v.as_i64()))
        == Some(404)
}

/// Fold the served counters into the panel.
pub(crate) fn apply_stats(supervision: &mut Supervision, stats_body: &str) {
    if let Ok(report) = parse_task_stats(stats_body) {
        supervision.stats = Some(report);
    }
}

/// Fold one page of the step log into the panel: cursor facts plus the newest decisions.
///
/// A decision is taken from the LAST event of its type: the engine can evaluate the gate
/// more than once (a repair round, then the accepted claim), and only the last one explains
/// where the run stands.
pub(crate) fn apply_events(supervision: &mut Supervision, page: &EventsPage) {
    supervision.window = WindowView {
        retained: page.events.len(),
        first_retained_seq: page.first_retained_seq,
        next_seq: page.next_seq,
        dropped: page.dropped,
        resynced: page.resynced,
    };
    supervision.nudges = page
        .events
        .iter()
        .filter(|event| event.kind == "nudge")
        .count() as u64;

    if let Some(detail) = last_detail(&page.events, "gate.evaluated") {
        supervision.gate = Some(gate_view(detail));
    }
    if let Some(detail) = last_detail(&page.events, "verify.verdict") {
        supervision.verdict = Some(verdict_view(detail));
    }
    // `repair.exhausted` is the terminal decision; a trailing `repair.round` is what a run
    // that is still repairing (or failed for another reason after one repair) has.
    if let Some(detail) = last_detail(&page.events, "repair.exhausted") {
        supervision.repair = Some(repair_view(detail, true));
    } else if let Some(detail) = last_detail(&page.events, "repair.round") {
        supervision.repair = Some(repair_view(detail, false));
    }
    supervision.recent_decisions = page
        .events
        .iter()
        .filter(|event| is_decision(event.kind.as_str()))
        .map(|event| (event.kind.clone(), decision_summary(event)))
        .collect();
}

/// Fold the access view (`GET /api/agent/{id}/access`) into the panel.
pub(crate) fn apply_access(supervision: &mut Supervision, access_body: &str) {
    let Ok(value) = serde_json::from_str::<Value>(access_body.trim()) else {
        return;
    };
    // The endpoint answers `{taskId, pending:[{requestId, effect, target, reason}], grants:[…]}`.
    let Some(pending) = value.get("pending").and_then(|v| v.as_array()) else {
        return;
    };
    supervision.pending_access = pending
        .iter()
        .map(|item| PendingAccess {
            request_id: string_of(item, "requestId").unwrap_or_default(),
            effect: string_of(item, "effect").unwrap_or_default(),
            target: string_of(item, "target").unwrap_or_default(),
        })
        .collect();
}

/// True for the event types the panel summarizes.
fn is_decision(kind: &str) -> bool {
    matches!(
        kind,
        "gate.evaluated"
            | "verify.verdict"
            | "repair.round"
            | "repair.exhausted"
            | "finish.repair"
            | "finish.amend"
            | "nudge"
            | "isolation.merge"
            | "access.requested"
            | "access.resolved"
    )
}

fn last_detail<'a>(events: &'a [TaskEvent], kind: &str) -> Option<&'a Value> {
    events
        .iter()
        .rev()
        .find(|event| event.kind == kind)
        .and_then(|event| event.detail.as_ref())
}

fn gate_view(detail: &Value) -> GateView {
    GateView {
        passed: detail.get("passed").and_then(|v| v.as_bool()).unwrap_or(false),
        violations: string_list(detail, "violations"),
        unlisted_files: string_list(detail, "unlistedFiles"),
    }
}

fn verdict_view(detail: &Value) -> VerdictView {
    let failing_checks = detail
        .get("checks")
        .and_then(|v| v.as_array())
        .map(|checks| {
            checks
                .iter()
                .filter(|check| check.get("ok").and_then(|v| v.as_bool()) == Some(false))
                .map(|check| CheckView {
                    name: string_of(check, "name").unwrap_or_else(|| "check".to_string()),
                    target: string_of(check, "target"),
                    exit_code: check.get("exitCode").and_then(|v| v.as_i64()),
                    ok: false,
                })
                .collect()
        })
        .unwrap_or_default();
    VerdictView {
        passed: detail.get("passed").and_then(|v| v.as_bool()).unwrap_or(false),
        module: string_of(detail, "module"),
        failure_reason: string_of(detail, "failureReason"),
        failing_checks,
    }
}

fn repair_view(detail: &Value, exhausted: bool) -> RepairView {
    RepairView {
        round: detail
            .get("round")
            .or_else(|| detail.get("roundsUsed"))
            .and_then(|v| v.as_i64())
            .unwrap_or(0),
        max_rounds: detail.get("maxRounds").and_then(|v| v.as_i64()).unwrap_or(0),
        reason: string_of(detail, "reason").or_else(|| string_of(detail, "failureReason")),
        exhausted,
    }
}

/// One line for a decision step: what a human needs from its `detail`, or its text.
fn decision_summary(event: &TaskEvent) -> String {
    let detail = event.detail.as_ref();
    let field = |key: &str| detail.and_then(|d| string_of(d, key));
    let number = |key: &str| detail.and_then(|d| d.get(key).and_then(|v| v.as_i64()));
    match event.kind.as_str() {
        "verify.verdict" => {
            let passed = detail.and_then(|d| d.get("passed").and_then(|v| v.as_bool())).unwrap_or(false);
            let module = field("module").unwrap_or_else(|| "repo".to_string());
            if passed {
                format!("PASSED {module}")
            } else {
                format!(
                    "FAILED {module} — {}",
                    field("failureReason").unwrap_or_else(|| "a check did not pass".to_string())
                )
            }
        }
        "gate.evaluated" => {
            let passed = detail.and_then(|d| d.get("passed").and_then(|v| v.as_bool())).unwrap_or(false);
            if passed {
                "passed".to_string()
            } else {
                let violations = string_list(detail.unwrap_or(&Value::Null), "violations");
                format!(
                    "rejected: {} violation(s) — {}",
                    violations.len(),
                    violations.first().cloned().unwrap_or_default()
                )
            }
        }
        "repair.round" => format!(
            "round {}/{} — {}",
            number("round").unwrap_or(0),
            number("maxRounds").unwrap_or(0),
            field("reason").unwrap_or_else(|| "verification did not pass".to_string())
        ),
        "repair.exhausted" => format!(
            "exhausted after {} round(s) — {}",
            number("roundsUsed").unwrap_or(0),
            field("failureReason").unwrap_or_default()
        ),
        "finish.repair" => format!(
            "round {} — {} missing file(s)",
            number("round").unwrap_or(0),
            detail
                .and_then(|d| d.get("missing").and_then(|v| v.as_array()).map(Vec::len))
                .unwrap_or(0)
        ),
        "finish.amend" => format!(
            "+{} file(s) from the mutation trace",
            detail
                .and_then(|d| d.get("added").and_then(|v| v.as_array()).map(Vec::len))
                .unwrap_or(0)
        ),
        "nudge" => {
            let kind = field("kind").unwrap_or_else(|| "engine".to_string());
            if kind == "budget" {
                format!(
                    "budget: {}% consumed ({}/{})",
                    number("percent").unwrap_or(0),
                    number("consumed").unwrap_or(0),
                    number("limit").unwrap_or(0)
                )
            } else {
                format!(
                    "{kind}: {} read-only round(s), {} mutation(s)",
                    number("readOnlyRounds").unwrap_or(0),
                    number("mutations").unwrap_or(0)
                )
            }
        }
        "isolation.merge" => format!(
            "{} ({} file(s))",
            field("decision").unwrap_or_else(|| "decided".to_string()),
            number("changedFiles").unwrap_or(0)
        ),
        "access.requested" => format!(
            "{} {} ({})",
            field("effect").unwrap_or_default(),
            field("target").unwrap_or_default(),
            field("requestId").unwrap_or_default()
        ),
        "access.resolved" => format!(
            "{} {} ({})",
            field("decision").unwrap_or_default(),
            field("target").unwrap_or_default(),
            field("requestId").unwrap_or_default()
        ),
        _ => event.text.clone().unwrap_or_default(),
    }
}

fn string_of(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn string_list(value: &Value, key: &str) -> Vec<String> {
    value
        .get(key)
        .and_then(|v| v.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default()
}

/// Parse a `GET /api/commands/{id}/events` body (a thin alias so the caller does not have
/// to reach into the follower module for the same contract).
pub(crate) fn decode_events(body: &str) -> Option<EventsPage> {
    parse_events_page(body).ok()
}

/// The number of steps requested from `GET /events` by the caller.
pub(crate) fn events_limit() -> usize {
    SUPERVISE_EVENTS_LIMIT
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

/// The human panel: aligned label/value lines, in the same style as `/stats`.
///
/// Every line is self-explanatory when copied into a ticket or a CI log, and a fact that
/// was not reported is omitted (never rendered as zero).
pub(crate) fn render(supervision: &Supervision) -> Vec<String> {
    let elapsed = supervision
        .elapsed_ms
        .map(format_run_duration)
        .unwrap_or_else(|| "unknown".to_string());
    let mut lines = vec![format!("{} · {} · {}", supervision.task_id, supervision.status, elapsed)];

    if let Some(stats) = &supervision.stats {
        lines.push(llm_line(&stats.stats));
        lines.push(tools_line(&stats.stats));
        if let Some(files) = files_line(stats) {
            lines.push(files);
        }
    } else {
        lines.push("  counters  unavailable — this backend has no run log for the task".to_string());
    }

    lines.push(window_line(&supervision.window));

    if let Some(gate) = &supervision.gate {
        let mut line = if gate.passed {
            "  gate     ✔ passed".to_string()
        } else {
            format!("  gate     ✗ rejected — {} violation(s)", gate.violations.len())
        };
        if let Some(first) = gate.violations.first() {
            line.push_str(&format!(" — {}", truncate(first, 160)));
        }
        lines.push(line);
        if !gate.unlisted_files.is_empty() {
            lines.push(format!(
                "             unlisted file(s): {}",
                gate.unlisted_files.iter().take(5).cloned().collect::<Vec<_>>().join(", ")
            ));
        }
    }
    if let Some(verdict) = &supervision.verdict {
        let status = if verdict.passed { "✔ passed" } else { "✗ failed" };
        let module = verdict.module.clone().unwrap_or_else(|| "repo".to_string());
        lines.push(format!("  verify   {status} {module}"));
        for check in verdict.failing_checks.iter().take(5) {
            let mut detail = check.name.clone();
            if let Some(target) = &check.target {
                detail.push(' ');
                detail.push_str(target);
            }
            if let Some(exit_code) = check.exit_code {
                detail.push_str(&format!(" (exit {exit_code})"));
            }
            lines.push(format!("             ✗ {detail}"));
        }
        if verdict.failing_checks.is_empty() {
            if let Some(reason) = &verdict.failure_reason {
                lines.push(format!("             {}", truncate(reason, 160)));
            }
        }
    }
    if let Some(repair) = &supervision.repair {
        let head = if repair.exhausted {
            format!("  repair   ✗ exhausted after {} round(s)", repair.round)
        } else if repair.max_rounds > 0 {
            format!("  repair   round {}/{}", repair.round, repair.max_rounds)
        } else {
            format!("  repair   round {}", repair.round)
        };
        let mut line = head;
        if let Some(reason) = &repair.reason {
            line.push_str(&format!(" — {}", truncate(reason, 160)));
        }
        lines.push(line);
    }
    if supervision.nudges > 0 {
        lines.push(format!(
            "  nudge    {} corrective instruction(s) injected by the engine",
            supervision.nudges
        ));
    }
    if !supervision.pending_access.is_empty() {
        let first = &supervision.pending_access[0];
        let mut line = format!(
            "  access   ⏸ {} pending · {} {} ({})",
            supervision.pending_access.len(),
            first.effect,
            first.target,
            first.request_id
        );
        if let Some(id) = Some(first.request_id.as_str()).filter(|id| !id.is_empty()) {
            line.push_str(&format!(" — /approve {id} in `agent run -i`"));
        }
        lines.push(line);
    }
    if let Some(detail) = &supervision.failure_detail {
        lines.push(format!("  failure  {}", failure_detail_line(detail)));
    }
    if let Some(message) = &supervision.message {
        if supervision.is_terminal() {
            lines.push(format!("  message  {}", truncate(message, 200)));
        }
    }
    if !supervision.recent_decisions.is_empty() {
        // The two newest decisions are the context a rejection needs; the full history is
        // one `--follow --ndjson` away.
        let shown = supervision
            .recent_decisions
            .iter()
            .rev()
            .take(2)
            .collect::<Vec<_>>();
        for (kind, summary) in shown.into_iter().rev() {
            lines.push(format!("  last     {kind} · {}", truncate(summary, 160)));
        }
    }

    lines.push(format!("  next     {}", next_line(supervision)));
    lines
}

fn llm_line(stats: &RunStats) -> String {
    let mut line = format!(
        "  llm      {} round(s) · {} request(s)",
        stats.rounds, stats.model_requests
    );
    if stats.tokens_reported() {
        line.push_str(&format!(
            " · in {} · out {} · total {}",
            format_thousands(stats.input_tokens),
            format_thousands(stats.output_tokens),
            format_token_amount(stats.total_tokens)
        ));
        if let Some(ratio) = stats.cache_hit_ratio() {
            line.push_str(&format!(" · cache {:.0}%", ratio * 100.0));
        }
    } else if stats.model_requests > 0 {
        line.push_str(" · tokens not reported by the provider");
    }
    line
}

fn tools_line(stats: &RunStats) -> String {
    let mut line = format!(
        "  tools    {} call(s) · {} failed",
        stats.tool_calls, stats.tool_failures
    );
    let top = stats.top_tools(5);
    if !top.is_empty() {
        line.push_str(&format!(
            " ({})",
            top.iter()
                .map(|(tool, count)| format!("{tool} {count}"))
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    line
}

/// The convergence line: tool calls next to files changed (issue #652's core signal).
fn files_line(report: &TaskStatsReport) -> Option<String> {
    let mutations = report.mutations?;
    let mut line = format!("  files    {mutations} changed");
    if let Some(rounds) = report.read_only_rounds.filter(|rounds| *rounds > 0) {
        line.push_str(&format!(" · {rounds} round(s) without a change"));
    }
    if mutations == 0 && report.stats.tool_calls > 0 {
        line.push_str(" — ⚠ tools ran but nothing was written: this run is not converging");
    }
    Some(line)
}

fn window_line(window: &WindowView) -> String {
    let mut line = format!(
        "  steps    {} retained · window {}..{}",
        window.retained, window.first_retained_seq, window.next_seq
    );
    if window.dropped > 0 {
        line.push_str(&format!(" · {} dropped", window.dropped));
    }
    if window.resynced {
        line.push_str(" · ⚠ you fell behind the retained window");
    }
    line
}

/// One line of the structured failure evidence, for the panel.
fn failure_detail_line(detail: &Value) -> String {
    let mut parts = Vec::new();
    if let Some(code) = string_of(detail, "errorCode") {
        parts.push(code);
    }
    if let Some(reason) = string_of(detail, "reason") {
        parts.push(truncate(&reason, 160));
    }
    if let Some(gate) = detail.get("finishGate").filter(|v| v.is_object()) {
        let violations = string_list(gate, "violations");
        if let Some(first) = violations.first() {
            parts.push(format!("gate: {}", truncate(first, 140)));
        }
    }
    if let Some(verdict) = detail.get("verdict").filter(|v| v.is_object()) {
        let module = string_of(verdict, "module").unwrap_or_else(|| "repo".to_string());
        let reason = string_of(verdict, "failureReason").unwrap_or_else(|| "a check did not pass".to_string());
        parts.push(format!("verify {module}: {}", truncate(&reason, 120)));
    }
    if detail.get("resumable").and_then(|v| v.as_bool()) == Some(true) {
        parts.push("resumable".to_string());
    }
    if parts.is_empty() {
        "see `agent result`".to_string()
    } else {
        parts.join(" · ")
    }
}

/// What the operator can do next, from the task's own state.
fn next_line(supervision: &Supervision) -> String {
    let id = &supervision.task_id;
    let mut actions = vec![format!("agent result {id}")];
    if supervision.is_terminal() {
        if supervision.resumable == Some(true) {
            actions.push(format!("agent run --resume {id}"));
        }
    } else {
        if !supervision.pending_access.is_empty() {
            if let Some(first) = supervision.pending_access.first() {
                actions.push(format!("/approve {} (in agent run -i)", first.request_id));
            }
        }
        actions.push(format!("agent run --follow  # live steps for {id}"));
        actions.push(format!("agent cancel {id}"));
    }
    actions.join(" · ")
}

fn truncate(text: &str, max: usize) -> String {
    let single = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if single.chars().count() <= max {
        return single;
    }
    let mut out: String = single.chars().take(max.saturating_sub(1)).collect();
    out.push('…');
    out
}

/// The same facts as one JSON object (`--json`).
pub(crate) fn to_json(supervision: &Supervision) -> Value {
    let stats = supervision.stats.as_ref().map(|report| {
        json!({
            "rounds": report.stats.rounds,
            "modelRequests": report.stats.model_requests,
            "toolCalls": report.stats.tool_calls,
            "toolFailures": report.stats.tool_failures,
            "inputTokens": report.stats.input_tokens,
            "outputTokens": report.stats.output_tokens,
            "totalTokens": report.stats.total_tokens,
            "cachedInputTokens": report.stats.cached_input_tokens,
            "cacheReported": report.stats.cache_reported,
            "events": report.stats.events,
            "droppedEvents": report.stats.dropped_events,
            "mutations": report.mutations,
            "readOnlyRounds": report.read_only_rounds,
        })
    });

    json!({
        "taskId": supervision.task_id,
        "status": supervision.status,
        "isDone": supervision.is_terminal(),
        "elapsedMs": supervision.elapsed_ms,
        "message": supervision.message,
        "errorCode": supervision.error_code,
        "resumable": supervision.resumable,
        "stats": stats,
        "window": {
            "retained": supervision.window.retained,
            "firstRetainedSeq": supervision.window.first_retained_seq,
            "nextSeq": supervision.window.next_seq,
            "dropped": supervision.window.dropped,
            "resynced": supervision.window.resynced,
        },
        "gate": supervision.gate.as_ref().map(|gate| json!({
            "passed": gate.passed,
            "violations": gate.violations,
            "unlistedFiles": gate.unlisted_files,
        })),
        "verdict": supervision.verdict.as_ref().map(|verdict| json!({
            "passed": verdict.passed,
            "module": verdict.module,
            "failureReason": verdict.failure_reason,
            "failingChecks": verdict.failing_checks.iter().map(|check| json!({
                "name": check.name,
                "target": check.target,
                "exitCode": check.exit_code,
                "ok": check.ok,
            })).collect::<Vec<_>>(),
        })),
        "repair": supervision.repair.as_ref().map(|repair| json!({
            "round": repair.round,
            "maxRounds": repair.max_rounds,
            "reason": repair.reason,
            "exhausted": repair.exhausted,
        })),
        "nudges": supervision.nudges,
        "access": {
            "pending": supervision.pending_access.iter().map(|pending| json!({
                "requestId": pending.request_id,
                "effect": pending.effect,
                "target": pending.target,
            })).collect::<Vec<_>>(),
        },
        "failureDetail": supervision.failure_detail,
        "recentDecisions": supervision.recent_decisions.iter()
            .map(|(kind, summary)| json!({"type": kind, "summary": summary}))
            .collect::<Vec<_>>(),
        "next": next_line(supervision),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn page(body: &str) -> EventsPage {
        decode_events(body).expect("the page must parse")
    }

    fn status(body: &str) -> Supervision {
        parse_status_envelope(body).expect("the envelope must parse")
    }

    #[test]
    fn a_running_task_reports_its_progress_and_what_is_missing() {
        let mut supervision = status(
            r#"{"taskId":"t1","status":"running","statusCode":200,"elapsedMs":252000,
                "mutations":0,"readOnlyRounds":6}"#,
        );
        apply_stats(
            &mut supervision,
            r#"{"taskId":"t1","status":"running","processState":"in_progress","isDone":false,
                "stats":{"rounds":9,"modelRequests":41,"toolCalls":175,"toolFailures":3,
                         "inputTokens":48231,"outputTokens":6120,"totalTokens":54351,
                         "cacheReported":true,"cachedInputTokens":38585,"events":412,
                         "droppedEvents":0,"mutations":0,"readOnlyRounds":6,
                         "toolCounts":{"b4.run":120,"coding.read":40}}}"#,
        );
        apply_events(
            &mut supervision,
            &page(
                r#"{"status":"running","done":false,"nextSeq":412,"firstRetainedSeq":1,"dropped":0,
                    "events":[
                      {"seq":300,"type":"gate.evaluated","detail":{"passed":false,
                         "violations":["filesChanged lists 1 file(s) neither git nor this run's file trace shows: ev.js"],
                         "unlistedFiles":["ev.js"]}},
                      {"seq":340,"type":"verify.verdict","detail":{"passed":false,"module":"browser4-browser",
                         "failureReason":"compile browser4-browser failed",
                         "checks":[{"name":"compile","target":"browser4-browser","ok":false,"exitCode":1},
                                   {"name":"test","target":"browser4-browser","ok":true,"exitCode":0}]}},
                      {"seq":360,"type":"repair.round","detail":{"round":1,"maxRounds":3,"reason":"compile browser4-browser failed (exit 1)"}},
                      {"seq":400,"type":"nudge","detail":{"kind":"coding","readOnlyRounds":6}},
                      {"seq":412,"type":"round.start","turn":15,"mutations":0,"readOnlyRounds":6}
                    ]}"#,
            ),
        );

        assert!(!supervision.is_terminal());
        let lines = render(&supervision).join("\n");
        assert!(lines.contains("t1 · running · 4m12s"), "{lines}");
        assert!(lines.contains("9 round(s) · 41 request(s)"), "{lines}");
        assert!(lines.contains("175 call(s) · 3 failed (b4.run 120, coding.read 40)"), "{lines}");
        assert!(lines.contains("files    0 changed"), "{lines}");
        assert!(lines.contains("not converging"), "{lines}");
        assert!(lines.contains("gate     ✗ rejected — 1 violation(s)"), "{lines}");
        assert!(lines.contains("verify   ✗ failed browser4-browser"), "{lines}");
        assert!(lines.contains("✗ compile browser4-browser (exit 1)"), "{lines}");
        assert!(lines.contains("repair   round 1/3"), "{lines}");
        assert!(lines.contains("1 corrective instruction(s)"), "{lines}");
        assert!(lines.contains("steps    5 retained · window 1..412"), "{lines}");
        // A healthy running task still says the way in.
        assert!(lines.contains("next     agent result t1"), "{lines}");
        assert!(!lines.contains("⚠ you fell behind"), "{lines}");
    }

    #[test]
    fn a_failed_task_answers_which_rule_and_which_check_without_the_log() {
        // /result's failureDetail rides the status envelope: after a backend restart the
        // events are gone but the evidence survives (issue #652, acceptance criterion 1).
        let supervision = status(
            r#"{"taskId":"t2","status":"failed","statusCode":417,"elapsedMs":61000,
                "errorCode":"TOOL_ERROR","resumable":true,
                "failureDetail":{"errorCode":"TOOL_ERROR","resumable":true,
                  "reason":"Finish report rejected: …",
                  "finishGate":{"passed":false,"violations":["filesChanged lists a file git does not show"]},
                  "verdict":{"passed":false,"module":"browser4-browser","failureReason":"compile failed"}}}"#,
        );

        let lines = render(&supervision).join("\n");
        assert!(lines.contains("failure  TOOL_ERROR"), "{lines}");
        assert!(lines.contains("gate: filesChanged lists a file git does not show"), "{lines}");
        assert!(lines.contains("verify browser4-browser: compile failed"), "{lines}");
        assert!(lines.contains("resumable"), "{lines}");
        assert!(lines.contains("agent run --resume t2"), "{lines}");
        // No counters and no page: the panel says so instead of printing zeros.
        assert!(lines.contains("counters  unavailable"), "{lines}");
    }

    #[test]
    fn a_cursor_behind_the_window_is_called_out() {
        let mut supervision = status(r#"{"taskId":"t3","status":"running","statusCode":200}"#);
        apply_events(
            &mut supervision,
            &page(r#"{"status":"running","done":false,"nextSeq":900,"firstRetainedSeq":500,
                      "dropped":499,"resynced":true,"events":[{"seq":900,"type":"model.text","text":"hi"}]}"#),
        );

        let lines = render(&supervision).join("\n");
        assert!(lines.contains("499 dropped"), "{lines}");
        assert!(lines.contains("fell behind the retained window"), "{lines}");
    }

    #[test]
    fn pending_approvals_name_the_way_out() {
        let mut supervision = status(r#"{"taskId":"t4","status":"running","statusCode":200}"#);
        apply_access(
            &mut supervision,
            r#"{"taskId":"t4","pending":[{"requestId":"req-1","effect":"fs.write","target":"/etc/hosts"}],"grants":[]}"#,
        );

        let lines = render(&supervision).join("\n");
        assert!(lines.contains("access   ⏸ 1 pending · fs.write /etc/hosts (req-1)"), "{lines}");
        assert!(lines.contains("/approve req-1"), "{lines}");
        assert!(lines.contains("/approve req-1 (in agent run -i)"), "{lines}");
    }

    #[test]
    fn the_json_view_carries_the_machine_readable_verdict() {
        let mut supervision = status(
            r#"{"taskId":"t5","status":"failed","statusCode":417,"elapsedMs":1000,"errorCode":"TOOL_ERROR"}"#,
        );
        apply_events(
            &mut supervision,
            &page(
                r#"{"status":"failed","done":true,"nextSeq":9,
                    "events":[{"seq":9,"type":"gate.evaluated","detail":{"passed":false,
                      "violations":["v1","v2"],"unlistedFiles":["a.js"]}}]}"#,
            ),
        );

        let json = to_json(&supervision);
        assert_eq!(json["taskId"], "t5");
        assert_eq!(json["isDone"], true);
        assert_eq!(json["gate"]["passed"], false);
        assert_eq!(json["gate"]["violations"][1], "v2");
        assert_eq!(json["gate"]["unlistedFiles"][0], "a.js");
        assert_eq!(json["window"]["nextSeq"], 9);
        assert!(json["stats"].is_null(), "an absent report is null, not zeros");
        assert!(json["recentDecisions"][0]["type"] == "gate.evaluated");
    }

    #[test]
    fn an_unknown_id_is_recognisable_from_the_envelope() {
        let body = r#"{"taskId":"ghost","status":"failed","statusCode":404,"done":true,
                      "message":"No task found with id 'ghost'"}"#;

        assert!(status_is_not_found(body));
        assert_eq!(status(body).message, None, "the not-found text is the caller's line, not the panel's");
        assert!(!status_is_not_found(r#"{"taskId":"t1","status":"running","statusCode":200}"#));
    }

    #[test]
    fn the_panel_survives_a_backend_without_the_optional_sources() {
        // Only the status envelope answered: no counters, no steps, no access view.
        let supervision = status(r#"{"taskId":"t6","status":"queued","statusCode":201}"#);

        let lines = render(&supervision).join("\n");
        assert!(lines.contains("t6 · queued · unknown"), "{lines}");
        assert!(lines.contains("counters  unavailable"), "{lines}");
        assert!(lines.contains("steps    0 retained"), "{lines}");
        assert!(lines.contains("next     agent result t6"), "{lines}");
    }
}
