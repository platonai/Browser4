//! Per-command performance timing (`--timing` global flag).
//!
//! When `--timing` is active, `http.rs` records one [`TimingSample`] per HTTP
//! tool call (wall-clock duration + optional backend compute time from the
//! `_timing` response envelope).  After the command dispatch completes,
//! `main.rs` renders a breakdown (total / per-call / network / backend).
//!
//! # Threading
//!
//! State is thread-local (`RefCell`), matching the `JSON_OUTPUT` / `QUIET`
//! pattern in `main.rs`.  No synchronisation is needed because the CLI is
//! single-threaded: `tokio::join!` futures run on the same task thread.

use serde_json::{json, Value};
use std::cell::RefCell;

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

/// One measured HTTP round-trip.
#[derive(Debug, Clone)]
pub struct TimingSample {
    /// MCP tool name (e.g. `"html_snapshot_summary"`, `"page_url"`).
    pub tool: String,
    /// Client-side wall-clock: from just before `request.send()` to after the
    /// full response body is read.
    pub http_ms: u64,
    /// Server-reported compute time from the `_timing.totalMillis` envelope
    /// field.  `None` when the server doesn't support timing or the call
    /// failed before a valid JSON body was received.
    pub backend_ms: Option<u64>,
}

// ---------------------------------------------------------------------------
// Thread-local state
// ---------------------------------------------------------------------------

thread_local! {
    static TIMING_ENABLED: RefCell<bool> = RefCell::new(false);
    static TIMING_SAMPLES: RefCell<Vec<TimingSample>> = RefCell::new(Vec::new());
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/// Initialise timing mode for the current command.
///
/// When `enabled` is `false` (the default), `record_tool_call` is a no-op
/// and `timing_active()` returns `false` — existing behaviour is unchanged.
pub fn timing_init(enabled: bool) {
    TIMING_ENABLED.with(|cell| *cell.borrow_mut() = enabled);
    TIMING_SAMPLES.with(|cell| cell.borrow_mut().clear());
}

/// Whether `--timing` is active for the current command.
///
/// Checked by `http.rs` to decide whether to send the `X-Timing: 1` request
/// header and record samples.
pub fn timing_active() -> bool {
    TIMING_ENABLED.with(|cell| *cell.borrow())
}

/// Record one HTTP round-trip measurement.
///
/// No-op when timing is not enabled (the common case).
pub fn record_tool_call(tool: &str, http_ms: u64, backend_ms: Option<u64>) {
    TIMING_SAMPLES.with(|cell| {
        let enabled = TIMING_ENABLED.with(|e| *e.borrow());
        if enabled {
            cell.borrow_mut().push(TimingSample {
                tool: tool.to_string(),
                http_ms,
                backend_ms,
            });
        }
    });
}

/// Drain all recorded samples.
pub fn take_timing_samples() -> Vec<TimingSample> {
    TIMING_SAMPLES.with(|cell| cell.borrow_mut().drain(..).collect())
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/// Human-readable duration in milliseconds or seconds.
///
/// - 0..999 ms  → `"512ms"`
/// - 1 000+ ms  → `"1.24s"`
pub fn format_millis(ms: u64) -> String {
    if ms < 1000 {
        format!("{}ms", ms)
    } else {
        format!("{:.2}s", ms as f64 / 1000.0)
    }
}

/// Estimate pure network time: sum of (http_ms - backend_ms) across all
/// samples that have a backend measurement.
///
/// Returns `None` when no backend timing data is available (old server).
pub fn network_ms(samples: &[TimingSample]) -> Option<u64> {
    let mut total: u64 = 0;
    let mut any = false;
    for s in samples {
        if let Some(backend) = s.backend_ms {
            total += s.http_ms.saturating_sub(backend);
            any = true;
        }
    }
    if any { Some(total) } else { None }
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

/// Build a compact human-readable timing block.
pub fn render_human(samples: &[TimingSample], total_ms: u64) -> String {
    let mut lines = Vec::new();
    lines.push("⏱  Timing".to_string());
    lines.push(format!("  total:                    {}", format_millis(total_ms)));

    // Sort descending by http_ms so the dominant cost is first.
    let mut sorted: Vec<&TimingSample> = samples.iter().collect();
    sorted.sort_by(|a, b| b.http_ms.cmp(&a.http_ms));

    let max_label = sorted
        .iter()
        .map(|s| s.tool.len())
        .max()
        .unwrap_or(4);

    for s in &sorted {
        let label = format!("{:<width$}", s.tool, width = max_label);
        match s.backend_ms {
            Some(backend) => {
                lines.push(format!(
                    "  {}:  {}  (backend {})",
                    label,
                    format_millis(s.http_ms),
                    format_millis(backend)
                ));
            }
            None => {
                lines.push(format!(
                    "  {}:  {}  (backend n/a)",
                    label,
                    format_millis(s.http_ms)
                ));
            }
        }
    }

    if let Some(net) = network_ms(samples) {
        lines.push(format!("  network:                  {}", format_millis(net)));
    }

    lines.push(String::new());
    lines.join("\n")
}

/// Build the JSON representation for `--json` output.
///
/// All values are integer milliseconds.
pub fn to_json(samples: &[TimingSample], total_ms: u64) -> Value {
    let mut calls: Vec<Value> = Vec::with_capacity(samples.len());
    for s in samples {
        let mut obj = serde_json::Map::new();
        obj.insert("tool".to_string(), json!(s.tool));
        obj.insert("httpMs".to_string(), json!(s.http_ms));
        if let Some(backend) = s.backend_ms {
            obj.insert("backendMs".to_string(), json!(backend));
        }
        calls.push(Value::Object(obj));
    }

    // Sort descending by httpMs so consumers see the bottleneck first.
    calls.sort_by(|a, b| {
        let a_ms = a.get("httpMs").and_then(|v| v.as_u64()).unwrap_or(0);
        let b_ms = b.get("httpMs").and_then(|v| v.as_u64()).unwrap_or(0);
        b_ms.cmp(&a_ms)
    });

    let mut obj = serde_json::Map::new();
    obj.insert("totalMs".to_string(), json!(total_ms));
    if let Some(net) = network_ms(samples) {
        obj.insert("networkMs".to_string(), json!(net));
    }
    obj.insert("calls".to_string(), Value::Array(calls));
    Value::Object(obj)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_format_millis_sub_second() {
        assert_eq!(format_millis(0), "0ms");
        assert_eq!(format_millis(1), "1ms");
        assert_eq!(format_millis(512), "512ms");
        assert_eq!(format_millis(999), "999ms");
    }

    #[test]
    fn test_format_millis_seconds() {
        assert_eq!(format_millis(1000), "1.00s");
        assert_eq!(format_millis(1234), "1.23s");
        assert_eq!(format_millis(1500), "1.50s");
        assert_eq!(format_millis(90600), "90.60s");
    }

    #[test]
    fn test_network_ms_all_available() {
        let samples = vec![
            TimingSample { tool: "a".into(), http_ms: 100, backend_ms: Some(60) },
            TimingSample { tool: "b".into(), http_ms: 200, backend_ms: Some(150) },
        ];
        assert_eq!(network_ms(&samples), Some(90)); // (100-60)+(200-150)
    }

    #[test]
    fn test_network_ms_partial() {
        let samples = vec![
            TimingSample { tool: "a".into(), http_ms: 100, backend_ms: Some(60) },
            TimingSample { tool: "b".into(), http_ms: 200, backend_ms: None },
        ];
        assert_eq!(network_ms(&samples), Some(40)); // only (100-60)
    }

    #[test]
    fn test_network_ms_none() {
        let samples = vec![
            TimingSample { tool: "a".into(), http_ms: 100, backend_ms: None },
        ];
        assert_eq!(network_ms(&samples), None);
    }

    #[test]
    fn test_to_json_shape() {
        let samples = vec![
            TimingSample { tool: "slow".into(), http_ms: 500, backend_ms: Some(300) },
            TimingSample { tool: "fast".into(), http_ms: 10, backend_ms: None },
        ];
        let v = to_json(&samples, 520);
        assert_eq!(v["totalMs"], json!(520));
        assert_eq!(v["networkMs"], json!(200));
        assert_eq!(v["calls"].as_array().unwrap().len(), 2);
    }

    #[test]
    fn test_record_tool_call_disabled() {
        // Default state: timing disabled, samples empty.
        assert!(!timing_active());
        record_tool_call("test", 42, Some(10));
        let samples = take_timing_samples();
        assert!(samples.is_empty());
    }
}
