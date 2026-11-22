//! The goal of a task, as the backend serves it, and how the CLI prints it.
//!
//! Every agent task runs under a goal (`docs-dev/copilot/agent-goal-mode.md`): the
//! instruction it was submitted with, pinned by the engine into every model request so
//! compaction and elapsed time cannot make the run forget it. The plan the run committed
//! to rides next to it (`goal.plan`), which is what turns "the run drifted" from a feeling
//! into a fact a supervisor can read: a plan with pending steps and no mutations is a run
//! that is not doing what it said.
//!
//! Two surfaces serve the same JSON object — `GET /api/commands/{id}/goal` and the `goal`
//! field of every status envelope — so [`parse_goal`] takes either one's `goal` member and
//! both `/goal` and `agent supervise` render from the same [`GoalView`].

use serde_json::Value;

/// Marker per step status, matching the block the model itself sees.
const MARKER_DONE: &str = "✔";
const MARKER_PENDING: &str = "▢";
const MARKER_DROPPED: &str = "✖";

/// One step of the plan the run committed to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct GoalStep {
    pub(crate) text: String,
    /// `pending`, `done` or `dropped`; anything else is reported verbatim.
    pub(crate) status: String,
    /// Why a step was dropped — required for it to count as closed.
    pub(crate) reason: Option<String>,
}

impl GoalStep {
    /// Whether the run still owes an outcome for this step.
    pub(crate) fn is_open(&self) -> bool {
        match self.status.as_str() {
            "done" => false,
            "dropped" => self
                .reason
                .as_deref()
                .map(str::trim)
                .filter(|reason| !reason.is_empty())
                .is_none(),
            _ => true,
        }
    }

    fn marker(&self) -> &'static str {
        match self.status.as_str() {
            "done" => MARKER_DONE,
            "dropped" => MARKER_DROPPED,
            _ => MARKER_PENDING,
        }
    }
}

/// The goal and plan of one task.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct GoalView {
    pub(crate) text: String,
    /// `instruction` (the submitted task) or `operator` (replaced mid-run).
    pub(crate) source: String,
    /// 1 until an operator replaced the goal; every change increments it.
    pub(crate) revision: u64,
    /// Why the goal was replaced, when the operator gave a reason.
    pub(crate) changed_reason: Option<String>,
    pub(crate) steps: Vec<GoalStep>,
    pub(crate) done_when: Vec<String>,
    /// Steps still owed (pending, or dropped without a reason).
    pub(crate) pending: usize,
    pub(crate) done: usize,
    pub(crate) dropped: usize,
    /// The round this snapshot was taken at.
    pub(crate) round: Option<u64>,
}

impl GoalView {
    /// True when something the run promised is still unfinished.
    pub(crate) fn has_open_steps(&self) -> bool {
        self.pending > 0
    }

    /// `2/5 done, 1 dropped, 2 pending` — or `no plan yet`.
    pub(crate) fn progress(&self) -> String {
        if self.steps.is_empty() {
            return "no plan yet".to_string();
        }
        let mut parts = vec![format!("{}/{} done", self.done, self.steps.len())];
        if self.dropped > 0 {
            parts.push(format!("{} dropped", self.dropped));
        }
        if self.pending > 0 {
            parts.push(format!("{} pending", self.pending));
        }
        parts.join(", ")
    }

    /// One line for a panel: the goal (flattened, truncated) plus its plan's progress.
    pub(crate) fn summary(&self, max_chars: usize) -> String {
        let single = self
            .text
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");
        let short: String = if single.chars().count() <= max_chars {
            single
        } else {
            let mut cut: String = single.chars().take(max_chars.saturating_sub(1)).collect();
            cut.push('…');
            cut
        };
        let changed = if self.revision > 1 {
            format!(" · goal changed r{}", self.revision)
        } else {
            String::new()
        };
        format!("{short} · plan {}{changed}", self.progress())
    }
}

/// Parse the `goal` member of a status envelope or of `GET /api/commands/{id}/goal`.
///
/// `None` when the member is absent or not an object: a page visit, a task that never ran
/// under goal mode, or a backend that predates it. Callers say that out loud rather than
/// printing an empty goal.
pub(crate) fn parse_goal(value: &Value) -> Option<GoalView> {
    let object = value.as_object()?;
    let text = object
        .get("text")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .trim()
        .to_string();
    // A goal without text is not a goal: an empty object in the payload (or a null member)
    // must read as "no goal recorded", never as a blank target worth printing.
    if text.is_empty() {
        return None;
    }
    let steps = object
        .get("steps")
        .and_then(|v| v.as_array())
        .map(|steps| steps.iter().filter_map(parse_step).collect::<Vec<_>>())
        .unwrap_or_default();
    // The counts the backend computed are authoritative (they know the rule for a
    // `dropped` step without a reason); the local tally is the fallback when a field is
    // missing, so an older payload still renders.
    let counted = |key: &str, fallback: usize| {
        object
            .get(key)
            .and_then(|v| v.as_u64())
            .map(|n| n as usize)
            .unwrap_or(fallback)
    };
    let pending_fallback = steps.iter().filter(|step| step.is_open()).count();
    let done_fallback = steps.iter().filter(|step| step.status == "done").count();
    let dropped_fallback = steps.iter().filter(|step| step.status == "dropped").count();

    Some(GoalView {
        text,
        source: object
            .get("source")
            .and_then(|v| v.as_str())
            .unwrap_or("instruction")
            .to_string(),
        revision: object.get("revision").and_then(|v| v.as_u64()).unwrap_or(1),
        changed_reason: object
            .get("changedReason")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .filter(|reason| !reason.trim().is_empty()),
        steps,
        done_when: object
            .get("doneWhen")
            .and_then(|v| v.as_array())
            .map(|items| {
                items
                    .iter()
                    .filter_map(|item| item.as_str())
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default(),
        pending: counted("pending", pending_fallback),
        done: counted("done", done_fallback),
        dropped: counted("dropped", dropped_fallback),
        round: object.get("round").and_then(|v| v.as_u64()),
    })
}

fn parse_step(value: &Value) -> Option<GoalStep> {
    let object = value.as_object()?;
    let text = object.get("text")?.as_str()?.to_string();
    Some(GoalStep {
        text,
        status: object
            .get("status")
            .and_then(|v| v.as_str())
            .unwrap_or("pending")
            .to_string(),
        reason: object
            .get("reason")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .filter(|reason| !reason.trim().is_empty()),
    })
}

/// The human block of `/goal`: what the run is for, its plan, and how to move it.
pub(crate) fn format_goal(task_id: &str, goal: &GoalView, is_done: bool) -> Vec<String> {
    let mut lines = Vec::with_capacity(4 + goal.steps.len() + goal.done_when.len());
    let origin = match goal.source.as_str() {
        "operator" => " · replaced by the operator".to_string(),
        _ => String::new(),
    };
    let round = goal
        .round
        .map(|round| format!(" · round {round}"))
        .unwrap_or_default();
    lines.push(format!(
        "task {task_id} · goal r{}{origin}{round}",
        goal.revision
    ));
    for line in goal.text.lines() {
        lines.push(format!("  {line}"));
    }
    if let Some(reason) = goal.changed_reason.as_deref() {
        if !reason.trim().is_empty() {
            lines.push(format!("  reason: {reason}"));
        }
    }

    lines.push(format!("  plan {}", goal.progress()));
    for (index, step) in goal.steps.iter().enumerate() {
        let reason = step
            .reason
            .as_deref()
            .map(|reason| format!(" — {reason}"))
            .unwrap_or_default();
        lines.push(format!("    {} {}. {}{reason}", step.marker(), index + 1, step.text));
    }
    if !goal.done_when.is_empty() {
        lines.push("  done when:".to_string());
        for item in &goal.done_when {
            lines.push(format!("    - {item}"));
        }
    }

    if goal.has_open_steps() {
        lines.push(format!(
            "  ⚠ {} step(s) pending — the engine rejects a completion that leaves them open",
            goal.pending
        ));
    }
    if is_done {
        lines.push("  (task finished: the goal is history)".to_string());
    } else {
        lines.push("  /goal <text> replaces it (the only way to move a running task's goal)".to_string());
    }
    lines
}

/// The answer of `POST /api/commands/{id}/goal`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct GoalChange {
    pub(crate) accepted: bool,
    pub(crate) message: Option<String>,
    pub(crate) amendment_id: Option<String>,
    pub(crate) revision: Option<u64>,
}

/// Parse the change answer. Errors only when the body is not JSON at all.
pub(crate) fn parse_goal_change(body: &str) -> Result<GoalChange, String> {
    let value: Value = serde_json::from_str(body.trim())
        .map_err(|e| format!("could not parse the goal response as JSON: {e}"))?;
    Ok(GoalChange {
        accepted: value
            .get("accepted")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        message: value
            .get("message")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .filter(|message| !message.trim().is_empty()),
        amendment_id: value
            .get("amendmentId")
            .and_then(|v| v.as_str())
            .map(str::to_string),
        revision: value.get("revision").and_then(|v| v.as_u64()),
    })
}

/// One line for `/goal <text>`: accepted or not, and which conversation it landed in.
pub(crate) fn format_goal_change(task_id: &str, change: &GoalChange) -> String {
    let verb = if change.accepted {
        "goal changed"
    } else {
        "goal change NOT accepted"
    };
    let mut line = format!("task {task_id} · {verb}");
    if let Some(revision) = change.revision {
        line.push_str(&format!(" · now r{revision}"));
    }
    if let Some(reason) = change.message.as_deref() {
        line.push_str(&format!(" — {reason}"));
    }
    line
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn served() -> Value {
        json!({
            "text": "only the three cheapest offers",
            "source": "operator",
            "revision": 2,
            "changedReason": "operator: /goal",
            "steps": [
                {"text": "open the offers page", "status": "done"},
                {"text": "read the prices", "status": "done"},
                {"text": "export csv", "status": "dropped", "reason": "no csv writer"},
                {"text": "answer with the list", "status": "pending"}
            ],
            "doneWhen": ["the three prices are in the answer"],
            "pending": 1,
            "done": 2,
            "dropped": 1,
            "round": 7
        })
    }

    #[test]
    fn parses_the_served_goal() {
        let goal = parse_goal(&served()).expect("the goal must parse");
        assert_eq!(goal.text, "only the three cheapest offers");
        assert_eq!(goal.source, "operator");
        assert_eq!(goal.revision, 2);
        assert_eq!(goal.steps.len(), 4);
        assert_eq!(goal.pending, 1);
        assert_eq!(goal.done, 2);
        assert_eq!(goal.dropped, 1);
        assert_eq!(goal.round, Some(7));
        assert_eq!(goal.done_when, vec!["the three prices are in the answer"]);
        assert_eq!(goal.changed_reason.as_deref(), Some("operator: /goal"));
    }

    #[test]
    fn a_dropped_step_without_a_reason_counts_as_open() {
        let goal = parse_goal(&json!({
            "text": "t",
            "steps": [
                {"text": "a", "status": "dropped"},
                {"text": "b", "status": "dropped", "reason": "404"},
                {"text": "c", "status": "pending"}
            ]
        }))
        .unwrap();
        assert_eq!(goal.steps[0].is_open(), true, "no reason means the run owes an outcome");
        assert_eq!(goal.steps[1].is_open(), false, "a reason closes the step");
        assert_eq!(goal.steps[2].is_open(), true);
        // Counts come from the payload when it carries them, from the local rule when not.
        assert_eq!(goal.pending, 2);
    }

    #[test]
    fn a_missing_goal_is_none_not_an_empty_goal() {
        assert!(parse_goal(&Value::Null).is_none());
        assert!(parse_goal(&json!({})).is_none());
        assert!(parse_goal(&json!("nope")).is_none());
    }

    #[test]
    fn progress_and_summary_read_like_the_step_log() {
        let goal = parse_goal(&served()).unwrap();
        assert_eq!(goal.progress(), "2/4 done, 1 dropped, 1 pending");
        let summary = goal.summary(120);
        assert!(summary.contains("only the three cheapest offers"), "{summary}");
        assert!(summary.contains("2/4 done"), "{summary}");
        assert!(summary.contains("goal changed r2"), "{summary}");
    }

    #[test]
    fn summary_truncates_a_long_goal_on_a_character_boundary() {
        let goal = parse_goal(&json!({"text": "价".repeat(40), "steps": []})).unwrap();
        let summary = goal.summary(10);
        let head = summary.split(" · plan").next().unwrap();
        assert_eq!(head.chars().count(), 10);
        assert!(head.ends_with('…'), "{head}");
    }

    #[test]
    fn the_human_block_names_the_open_steps_and_the_way_out() {
        let goal = parse_goal(&served()).unwrap();
        let lines = format_goal("5c3a1f2e", &goal, false);
        let text = lines.join("\n");
        assert!(text.contains("goal r2"), "{text}");
        assert!(text.contains("replaced by the operator"), "{text}");
        assert!(text.contains("✔ 1. open the offers page"), "{text}");
        assert!(text.contains("✖ 3. export csv — no csv writer"), "{text}");
        assert!(text.contains("▢ 4. answer with the list"), "{text}");
        assert!(text.contains("done when:"), "{text}");
        assert!(text.contains("1 step(s) pending"), "{text}");
        assert!(text.contains("/goal <text> replaces it"), "{text}");
    }

    #[test]
    fn a_finished_task_says_the_goal_is_history() {
        let goal = parse_goal(&served()).unwrap();
        let text = format_goal("t", &goal, true).join("\n");
        assert!(text.contains("the goal is history"), "{text}");
        assert!(!text.contains("/goal <text> replaces it"), "{text}");
    }

    #[test]
    fn the_change_answer_reports_acceptance_and_revision() {
        let change = parse_goal_change(
            r#"{"taskId":"t","accepted":true,"message":"queued","amendmentId":"a1","revision":3}"#,
        )
        .unwrap();
        assert!(change.accepted);
        assert_eq!(change.revision, Some(3));
        let line = format_goal_change("t", &change);
        assert!(line.contains("goal changed"), "{line}");
        assert!(line.contains("now r3"), "{line}");

        let refused = parse_goal_change(r#"{"taskId":"t","accepted":false,"message":"not running"}"#)
            .unwrap();
        let line = format_goal_change("t", &refused);
        assert!(line.contains("NOT accepted"), "{line}");
        assert!(line.contains("not running"), "{line}");
    }

    #[test]
    fn a_non_json_change_answer_is_an_error() {
        assert!(parse_goal_change("<html>proxy error</html>").is_err());
    }
}
