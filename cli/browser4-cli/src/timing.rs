//! Where one CLI invocation spends its wall clock (evaluation §12.5, E-3/E-4).
//!
//! A `b4.run` costs three things: the CLI process itself, the HTTP round trip to
//! the backend, and the page action the backend performs inside that round trip.
//! Only the sum was observable before (`CliProcessManager` measures the child
//! process), so a 2–4 s `eval` could be "the CLI starting", "the network", or
//! "the page" and nobody could say which.
//!
//! This module splits the invocation:
//!
//! - **startup** — process start → the first *tool* request. Everything the CLI
//!   does before it talks to the backend: arg parsing, config/state resolution,
//!   development-mode port allocation and the readiness probes.
//! - **http** — total time inside tool-call HTTP requests (send → body read).
//! - **server** — the part of `http` the backend reports for itself
//!   (`X-Browser4-Duration-Ms`), i.e. the page/tool action.
//! - **net** — `http - server`: dispatch, socket and serialization overhead.
//!
//! Off by default. `BROWSER4_CLI_TIMING=1` turns the one-line footer on
//! (`stderr`, so `--json` output stays parseable);
//! `BROWSER4_CLI_TRACE_STARTUP=1` additionally prints one line per startup
//! phase, which is what locating the ~256 ms development-mode overhead needed.
//!
//! The footer is machine-readable on purpose: `CliProcessManager` parses it out
//! of the child's stderr, strips it from the model-visible text and records it,
//! so `b4.run` reports the split without the payload changing shape.
//!
//! The collector is a plain value ([`Timing`]) so tests can drive their own
//! instance; the process-wide one is [init]'d once from the environment.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::Instant;

/// Marker the footer line starts with. The backend parses this prefix.
pub const FOOTER_PREFIX: &str = "[timing]";

/// Marker the per-phase trace lines start with (startup investigation only).
pub const TRACE_PREFIX: &str = "[startup]";

/// Env var that turns the footer on.
pub const TIMING_ENV: &str = "BROWSER4_CLI_TIMING";

/// Env var that turns the per-phase startup trace on (implies [TIMING_ENV]).
pub const TRACE_ENV: &str = "BROWSER4_CLI_TRACE_STARTUP";

/// One invocation's clock and counters.
///
/// Deliberately not a singleton: the process-wide collector is [PROCESS], but a
/// test (or a future second invocation in one process) owns its own instance.
pub struct Timing {
    start: Instant,
    enabled: bool,
    tracing: bool,
    startup_ms: AtomicU64,
    http_ms: AtomicU64,
    server_ms: AtomicU64,
    calls: AtomicU64,
    /// Exactly one footer per instance, whatever path ends the process.
    emitted: AtomicBool,
}

/// `startup_ms` before the first tool request. `0` cannot be the sentinel: a
/// request can legitimately go out in the same millisecond the process started.
const UNSET_STARTUP: u64 = u64::MAX;

impl Timing {
    /// A collector that starts measuring now.
    pub fn new(enabled: bool, tracing: bool) -> Self {
        Timing {
            start: Instant::now(),
            enabled: enabled || tracing,
            tracing,
            startup_ms: AtomicU64::new(UNSET_STARTUP),
            http_ms: AtomicU64::new(0),
            server_ms: AtomicU64::new(0),
            calls: AtomicU64::new(0),
            emitted: AtomicBool::new(false),
        }
    }

    /// A collector that starts measuring now, with the switches read from the
    /// environment.
    pub fn from_env() -> Self {
        let tracing = env_flag(TRACE_ENV);
        Timing::new(env_flag(TIMING_ENV), tracing)
    }

    pub fn tracing(&self) -> bool {
        self.tracing
    }

    /// Milliseconds since this collector was created.
    pub fn elapsed_ms(&self) -> u64 {
        self.start.elapsed().as_millis() as u64
    }

    /// Print one startup phase boundary (`BROWSER4_CLI_TRACE_STARTUP=1` only).
    ///
    /// Cheap enough to leave in the hot path: formatting only happens while the
    /// trace is on.
    pub fn mark(&self, label: &str) {
        if self.tracing {
            eprintln!("{TRACE_PREFIX} {label}={}ms", self.elapsed_ms());
        }
    }

    /// Note that a tool request is about to be sent: pins `startup` to the time of
    /// the FIRST one and leaves it alone afterwards.
    pub fn note_tool_request(&self) {
        let _ = self.startup_ms.compare_exchange(
            UNSET_STARTUP,
            self.elapsed_ms(),
            Ordering::Relaxed,
            Ordering::Relaxed,
        );
    }

    /// Record one finished tool-call HTTP request.
    ///
    /// @param http_ms   wall time of the request (send → response body read)
    /// @param server_ms server-reported handler time, when the backend sends it
    pub fn record_http(&self, http_ms: u64, server_ms: Option<u64>) {
        if !self.enabled {
            return;
        }
        self.http_ms.fetch_add(http_ms, Ordering::Relaxed);
        self.calls.fetch_add(1, Ordering::Relaxed);
        if let Some(server_ms) = server_ms {
            // Clamp per request, not per snapshot: a backend that over-claims once
            // must not eat into the network time of a *later* request.
            self.server_ms
                .fetch_add(server_ms.min(http_ms), Ordering::Relaxed);
        }
    }

    /// The three segments, in milliseconds. `total` is the instance's wall clock
    /// at the moment the snapshot was taken.
    pub fn snapshot(&self) -> TimingSnapshot {
        let total_ms = self.elapsed_ms();
        let startup_ms = self.startup_ms.load(Ordering::Relaxed);
        let http_ms = self.http_ms.load(Ordering::Relaxed);
        let server_ms = self.server_ms.load(Ordering::Relaxed).min(http_ms);
        TimingSnapshot {
            startup_ms: if startup_ms == UNSET_STARTUP {
                total_ms
            } else {
                startup_ms
            },
            http_ms,
            server_ms,
            net_ms: http_ms.saturating_sub(server_ms),
            calls: self.calls.load(Ordering::Relaxed),
            total_ms,
        }
    }

    /// Print the footer once, when timing is enabled.
    pub fn emit_footer(&self) {
        if !self.enabled {
            return;
        }
        if self.emitted.swap(true, Ordering::Relaxed) {
            return;
        }
        eprintln!("{}", format_footer(&self.snapshot()));
    }
}

/// The three segments in milliseconds, as one machine-readable line describes them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimingSnapshot {
    pub startup_ms: u64,
    pub http_ms: u64,
    pub server_ms: u64,
    pub net_ms: u64,
    pub calls: u64,
    pub total_ms: u64,
}

/// Render the machine-readable footer (see [Timing::snapshot] for the segments).
pub fn format_footer(snapshot: &TimingSnapshot) -> String {
    format!(
        "{FOOTER_PREFIX} startup={}ms http={}ms server={}ms net={}ms calls={} total={}ms",
        snapshot.startup_ms,
        snapshot.http_ms,
        snapshot.server_ms,
        snapshot.net_ms,
        snapshot.calls,
        snapshot.total_ms
    )
}

fn env_flag(name: &str) -> bool {
    match std::env::var(name) {
        Ok(value) => {
            let value = value.trim().to_ascii_lowercase();
            !(value.is_empty() || value == "0" || value == "false" || value == "off" || value == "no")
        }
        Err(_) => false,
    }
}

// ---------------------------------------------------------------------------
// The process-wide collector
// ---------------------------------------------------------------------------
//
// Set once by `main`; every other module talks to it through these free
// functions. Before [init] they are no-ops, so a library build or a test that
// never calls it costs nothing and cannot print.

static PROCESS: OnceLock<Timing> = OnceLock::new();

/// Start the process clock and read the two switches. Must be the first thing
/// `main` does: `startup` is measured from here.
pub fn init() {
    let _ = PROCESS.get_or_init(Timing::from_env);
}

pub fn tracing() -> bool {
    PROCESS.get().is_some_and(Timing::tracing)
}

/// Print one startup phase boundary (`BROWSER4_CLI_TRACE_STARTUP=1` only).
pub fn mark(label: &str) {
    if let Some(timing) = PROCESS.get() {
        timing.mark(label);
    }
}

/// Note that a tool request is about to be sent (pins `startup`).
pub fn note_tool_request() {
    if let Some(timing) = PROCESS.get() {
        timing.note_tool_request();
    }
}

/// Record one finished tool-call HTTP request.
pub fn record_http(http_ms: u64, server_ms: Option<u64>) {
    if let Some(timing) = PROCESS.get() {
        timing.record_http(http_ms, server_ms);
    }
}

/// Print the footer once, when timing is enabled.
pub fn emit_footer() {
    if let Some(timing) = PROCESS.get() {
        timing.emit_footer();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collector() -> Timing {
        Timing::new(true, false)
    }

    #[test]
    fn footer_is_machine_readable() {
        let line = format_footer(&TimingSnapshot {
            startup_ms: 282,
            http_ms: 2310,
            server_ms: 2240,
            net_ms: 70,
            calls: 2,
            total_ms: 2600,
        });
        assert_eq!(
            line,
            "[timing] startup=282ms http=2310ms server=2240ms net=70ms calls=2 total=2600ms"
        );
        // The backend's parser keys on the prefix and the `key=value(ms)` shape.
        assert!(line.starts_with(FOOTER_PREFIX));
        assert!(line.contains(" server=2240ms "), "server is a separate segment: {line}");
    }

    #[test]
    fn server_time_is_clamped_to_http_time() {
        let timing = collector();

        // A backend claiming more time than the round trip (clock skew) must not
        // produce `net=-40ms`, which no parser would accept.
        timing.record_http(100, Some(140));
        let clamped = timing.snapshot();
        assert_eq!(clamped.server_ms, 100);
        assert_eq!(clamped.net_ms, 0);
        assert_eq!(clamped.calls, 1);

        // No server header at all: the whole round trip counts as network.
        timing.record_http(50, None);
        let withoutHeader = timing.snapshot();
        assert_eq!(withoutHeader.http_ms, 150);
        assert_eq!(withoutHeader.server_ms, 100);
        assert_eq!(withoutHeader.net_ms, 50);
        assert_eq!(withoutHeader.calls, 2);
    }

    #[test]
    fn startup_is_pinned_to_the_first_request() {
        let timing = collector();

        timing.note_tool_request();
        let first = timing.snapshot().startup_ms;
        std::thread::sleep(std::time::Duration::from_millis(15));
        timing.note_tool_request();
        assert_eq!(timing.snapshot().startup_ms, first, "a later request must not move startup");
    }

    #[test]
    fn a_command_with_no_request_reports_its_whole_life_as_startup() {
        let timing = collector();
        let snapshot = timing.snapshot();
        assert_eq!(snapshot.startup_ms, snapshot.total_ms);
        assert_eq!(snapshot.http_ms, 0);
        assert_eq!(snapshot.calls, 0);
    }

    #[test]
    fn collection_is_off_by_default() {
        let timing = Timing::new(false, false);
        assert!(!timing.tracing());
        // `record_http` is a no-op while disabled, so the counters cannot drift in
        // a production run that never asked for them.
        timing.record_http(10, Some(5));
        let snapshot = timing.snapshot();
        assert_eq!(snapshot.calls, 0);
        assert_eq!(snapshot.http_ms, 0);
        assert_eq!(snapshot.server_ms, 0);
    }

    #[test]
    fn the_trace_switch_implies_collection() {
        // `BROWSER4_CLI_TRACE_STARTUP=1` is the startup investigation switch: it
        // prints the per-phase lines and must also produce the footer that those
        // lines are read against.
        let timing = Timing::new(false, true);
        timing.record_http(10, Some(4));
        assert_eq!(timing.snapshot().calls, 1);
        assert_eq!(timing.snapshot().server_ms, 4);
    }

    #[test]
    fn footer_is_emitted_at_most_once() {
        let timing = collector();
        assert!(!timing.emitted.swap(true, Ordering::Relaxed));
        assert!(timing.emitted.load(Ordering::Relaxed), "the first call claims the footer");
        // A second call must not print again (the process has several exit paths).
        timing.emit_footer();
        assert!(timing.emitted.load(Ordering::Relaxed));
    }

    #[test]
    fn the_process_collector_is_inert_until_init() {
        // The free functions are what `main` and `http` call; without `init` they
        // must not panic (PROCESS is empty) and must not print anything.
        if PROCESS.get().is_none() {
            mark("noop");
            note_tool_request();
            record_http(1, None);
            emit_footer();
        }
    }
}
