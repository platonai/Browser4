//! Auto-update policy, state persistence, and decision logic.
//!
//! The CLI keeps itself up to date in three escalating stages, selected by the
//! `BROWSER4_AUTO_UPGRADE` environment variable:
//!
//! - `off` — never check, never notify.
//! - `notify` (default) — once per TTL, resolve the latest release tag and
//!   print a one-line hint on stderr when a newer version exists.
//! - `runtime` — additionally pre-download the newer runtime bundle in a
//!   detached background process; the download is activated the next time the
//!   backend starts.
//! - `full` — additionally self-upgrade the CLI binary in the same background
//!   pass.
//!
//! State lives in `<runtime-data-dir>/update-check.json`.  All network and
//! process-spawning side effects stay in `main.rs`; this module is pure
//! policy/state so it can be unit-tested without a backend.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

/// Environment variable selecting the auto-update policy.
pub const AUTO_UPGRADE_ENV: &str = "BROWSER4_AUTO_UPGRADE";

/// State file name under the runtime data dir.
const STATE_FILE_NAME: &str = "update-check.json";

/// How long a resolved latest tag is reused before re-checking (24 h).
pub const CHECK_TTL_SECS: i64 = 24 * 3600;

/// Minimum interval between two prefetch attempts for the same tag (1 h).
pub const PREFETCH_RETRY_INTERVAL_SECS: i64 = 3600;

/// A tag is blacklisted after this many consecutive download failures.
pub const FAILURE_BLACKLIST_THRESHOLD: u32 = 3;

/// How long a blacklisted tag stays blacklisted (7 days).
pub const FAILURE_BLACKLIST_SECS: i64 = 7 * 24 * 3600;

/// Budget for resolving the latest tag from the network, in seconds.
pub const RESOLVE_TIMEOUT_SECS: u64 = 3;

/// How aggressively the CLI keeps itself up to date.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AutoUpgradePolicy {
    /// Never check, never notify.
    Off,
    /// Check and print a hint (default).
    Notify,
    /// Notify + background runtime pre-download, activated on next launch.
    Runtime,
    /// Runtime + background CLI self-upgrade.
    Full,
}

impl AutoUpgradePolicy {
    /// Parse a policy string; `None` for unrecognised values.
    pub fn parse(raw: &str) -> Option<Self> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "off" | "0" | "false" | "disabled" => Some(Self::Off),
            "notify" => Some(Self::Notify),
            "runtime" => Some(Self::Runtime),
            "full" => Some(Self::Full),
            _ => None,
        }
    }

    /// Resolve the effective policy: the env var wins; default is `notify`.
    /// Unrecognised values fall back to `notify` (never silently to `off`).
    pub fn from_env() -> Self {
        std::env::var(AUTO_UPGRADE_ENV)
            .ok()
            .and_then(|v| Self::parse(&v))
            .unwrap_or(Self::Notify)
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Notify => "notify",
            Self::Runtime => "runtime",
            Self::Full => "full",
        }
    }
}

/// Record of one background prefetch attempt (rate-limiting).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrefetchAttempt {
    pub tag: String,
    pub at: i64,
}

/// Per-tag download failure record (blacklisting).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct FailureRecord {
    pub count: u32,
    pub last_failed_at: i64,
}

/// Persisted auto-update state (`<runtime-data-dir>/update-check.json`).
///
/// All timestamps are epoch seconds — trivially comparable and immune to
/// RFC3339 parsing edge cases.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct UpdateCheckState {
    /// Epoch seconds of the last latest-tag resolution attempt.
    #[serde(default)]
    pub last_check_at: Option<i64>,
    /// Latest release tag observed at the last successful resolution.
    #[serde(default)]
    pub latest_tag: Option<String>,
    /// The last tag a notification was printed for (dedup).
    #[serde(default)]
    pub last_notified_tag: Option<String>,
    /// A fully downloaded runtime waiting to be activated on next launch.
    #[serde(default)]
    pub pending_tag: Option<String>,
    /// The tag that was active before the pending one was committed.
    #[serde(default)]
    pub previous_tag: Option<String>,
    /// Last prefetch attempt (rate-limited per tag).
    #[serde(default)]
    pub last_prefetch_attempt: Option<PrefetchAttempt>,
    /// Per-tag download failures; ≥ threshold blacklists the tag for 7 days.
    #[serde(default)]
    pub failures: HashMap<String, FailureRecord>,
}

/// Path of the state file.
pub fn state_file_path() -> PathBuf {
    crate::state::resolve_runtime_data_dir().join(STATE_FILE_NAME)
}

/// Load the state file; missing or corrupt files yield a default state.
pub fn load_state() -> UpdateCheckState {
    let path = state_file_path();
    fs::read_to_string(&path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

/// Persist the state file (best-effort — a read-only data dir must never
/// break the command the user actually ran).
pub fn save_state(state: &UpdateCheckState) {
    let path = state_file_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(json) = serde_json::to_string_pretty(state) {
        let _ = fs::write(&path, json);
    }
}

/// Whether this pass should spend a network round-trip resolving the latest
/// tag.
///
/// A *failed* attempt also stamps `last_check_at`, so an offline machine
/// retries at most once per TTL instead of paying the resolve timeout on
/// every single command.
pub fn should_attempt_check(state: &UpdateCheckState, now: i64) -> bool {
    state.last_check_at.map_or(true, |at| now - at >= CHECK_TTL_SECS)
}

/// Extract the major version number from a tag (`v4.16.0-rc.1` → `Some(4)`).
pub fn major_of(tag: &str) -> Option<u64> {
    tag.trim_start_matches('v')
        .split('-')
        .next()?
        .split('.')
        .next()?
        .parse::<u64>()
        .ok()
}

/// What the background updater should download.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrefetchPlan {
    pub tag: String,
    /// `full` policy: also self-upgrade the CLI binary.
    pub self_upgrade: bool,
}

/// The outcome of one auto-update decision pass.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct UpdateDecision {
    /// Tag to notify the user about (`None` = stay silent).
    pub notify: Option<String>,
    /// Background prefetch to launch (`None` = nothing to download).
    pub prefetch: Option<PrefetchPlan>,
}

/// Decide what to do about `latest_tag`, given the policy and the state.
///
/// Pure function — the caller supplies `now` (epoch seconds) and whether the
/// latest tag is already fully installed on disk, so the matrix is unit-test
/// friendly.
///
/// Rules:
/// - `off`, or latest not strictly newer → nothing.
/// - Notification is deduplicated per tag (`last_notified_tag`).
/// - A major-version jump is only ever *notified*, never prefetched — crossing
///   a major boundary unattended is how broken installs happen.
/// - Prefetch (runtime/full only) is skipped when the tag is already pending,
///   already on disk, blacklisted (≥3 failures within 7 days), or when the
///   same tag was attempted within the last hour.
pub fn decide(
    policy: AutoUpgradePolicy,
    current_tag: &str,
    latest_tag: &str,
    state: &UpdateCheckState,
    now: i64,
    latest_already_on_disk: bool,
) -> UpdateDecision {
    let mut decision = UpdateDecision::default();
    if policy == AutoUpgradePolicy::Off {
        return decision;
    }
    if crate::daemon::compare_semver_tags(latest_tag, current_tag) != std::cmp::Ordering::Greater {
        return decision;
    }

    if state.last_notified_tag.as_deref() != Some(latest_tag) {
        decision.notify = Some(latest_tag.to_string());
    }

    // Major-version jumps are never prefetched — notify only.
    if major_of(latest_tag) != major_of(current_tag) {
        return decision;
    }

    if matches!(policy, AutoUpgradePolicy::Runtime | AutoUpgradePolicy::Full) {
        let blacklisted = state.failures.get(latest_tag).map_or(false, |f| {
            f.count >= FAILURE_BLACKLIST_THRESHOLD && now - f.last_failed_at < FAILURE_BLACKLIST_SECS
        });
        let rate_limited = state.last_prefetch_attempt.as_ref().map_or(false, |a| {
            a.tag == latest_tag && now - a.at < PREFETCH_RETRY_INTERVAL_SECS
        });
        let already_pending = state.pending_tag.as_deref() == Some(latest_tag);
        if !blacklisted && !rate_limited && !already_pending && !latest_already_on_disk {
            decision.prefetch = Some(PrefetchPlan {
                tag: latest_tag.to_string(),
                self_upgrade: policy == AutoUpgradePolicy::Full,
            });
        }
    }

    decision
}

/// Record a failed prefetch so the tag backs off (and eventually blacklists).
pub fn record_prefetch_failure(state: &mut UpdateCheckState, tag: &str, now: i64) {
    let record = state.failures.entry(tag.to_string()).or_default();
    record.count = record.count.saturating_add(1);
    record.last_failed_at = now;
}

/// Activate a runtime the background updater finished downloading.
///
/// Called from `daemon::find_or_install_runtime` right before the
/// installed-runtime fast path, so the very next backend launch picks up the
/// pre-downloaded version.  No-op when nothing is pending, when the pending
/// install is incomplete (marker cleared so prefetch retries), or when the
/// pending tag is not newer than the active one (e.g. the user manually
/// installed a newer or different-major runtime meanwhile — activating would
/// downgrade).
pub fn commit_pending_tag_if_ready() {
    let mut state = load_state();
    let Some(pending) = state.pending_tag.clone() else {
        return;
    };
    let current = crate::daemon::read_current_tag();
    if current.as_deref() == Some(pending.as_str()) {
        // Already active — just clear the marker.
        state.pending_tag = None;
        save_state(&state);
        return;
    }
    // Never downgrade: a manual install may have moved `current.tag` past the
    // prefetched version since the download finished.
    let not_newer = current
        .as_deref()
        .map_or(false, |c| {
            crate::daemon::compare_semver_tags(&pending, c) != std::cmp::Ordering::Greater
        });
    if not_newer {
        state.pending_tag = None;
        save_state(&state);
        return;
    }
    let dir = crate::daemon::versioned_install_dir(&pending);
    if !crate::daemon::install_dir_contains_runtime(&dir) {
        // Broken/incomplete download — drop the marker so prefetch retries.
        state.pending_tag = None;
        save_state(&state);
        return;
    }
    if crate::daemon::write_current_tag(&pending).is_err() {
        // Keep the marker — the next launch retries the commit.
        return;
    }
    state.previous_tag = current;
    state.pending_tag = None;
    save_state(&state);
    eprintln!("🔄 Activated Browser4 runtime {pending} (downloaded in the background).");
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn state_with_notified(tag: &str) -> UpdateCheckState {
        UpdateCheckState {
            last_notified_tag: Some(tag.to_string()),
            ..Default::default()
        }
    }

    #[test]
    fn policy_parse_accepts_known_values_case_insensitively() {
        assert_eq!(AutoUpgradePolicy::parse("off"), Some(AutoUpgradePolicy::Off));
        assert_eq!(AutoUpgradePolicy::parse("OFF"), Some(AutoUpgradePolicy::Off));
        assert_eq!(AutoUpgradePolicy::parse("false"), Some(AutoUpgradePolicy::Off));
        assert_eq!(AutoUpgradePolicy::parse("notify"), Some(AutoUpgradePolicy::Notify));
        assert_eq!(AutoUpgradePolicy::parse(" Runtime "), Some(AutoUpgradePolicy::Runtime));
        assert_eq!(AutoUpgradePolicy::parse("FULL"), Some(AutoUpgradePolicy::Full));
        assert_eq!(AutoUpgradePolicy::parse("bogus"), None);
        assert_eq!(AutoUpgradePolicy::parse(""), None);
    }

    #[test]
    fn policy_from_env_defaults_to_notify_and_ignores_garbage() {
        let _lock = crate::test_env::lock();
        unsafe {
            std::env::remove_var(AUTO_UPGRADE_ENV);
        }
        assert_eq!(AutoUpgradePolicy::from_env(), AutoUpgradePolicy::Notify);
        unsafe {
            std::env::set_var(AUTO_UPGRADE_ENV, "full");
        }
        assert_eq!(AutoUpgradePolicy::from_env(), AutoUpgradePolicy::Full);
        unsafe {
            std::env::set_var(AUTO_UPGRADE_ENV, "not-a-policy");
        }
        // Unknown values fall back to notify — never silently to off.
        assert_eq!(AutoUpgradePolicy::from_env(), AutoUpgradePolicy::Notify);
        unsafe {
            std::env::remove_var(AUTO_UPGRADE_ENV);
        }
    }

    #[test]
    fn major_of_parses_stable_and_prerelease_tags() {
        assert_eq!(major_of("v4.16.0"), Some(4));
        assert_eq!(major_of("4.16.0"), Some(4));
        assert_eq!(major_of("v4.16.0-rc.1"), Some(4));
        assert_eq!(major_of("v10.0.0"), Some(10));
        assert_eq!(major_of("garbage"), None);
        assert_eq!(major_of(""), None);
    }

    #[test]
    fn decide_is_silent_when_off_or_not_newer() {
        let state = UpdateCheckState::default();
        let now = 1_000_000;
        let d = decide(AutoUpgradePolicy::Off, "v4.15.0", "v4.16.0", &state, now, false);
        assert_eq!(d, UpdateDecision::default());
        // Same version and older "latest" never produce output.
        for policy in [AutoUpgradePolicy::Notify, AutoUpgradePolicy::Runtime, AutoUpgradePolicy::Full] {
            let d = decide(policy, "v4.16.0", "v4.16.0", &state, now, false);
            assert_eq!(d, UpdateDecision::default(), "same tag must be silent");
            let d = decide(policy, "v4.16.0", "v4.15.9", &state, now, false);
            assert_eq!(d, UpdateDecision::default(), "older latest must be silent");
        }
    }

    #[test]
    fn decide_notify_policy_only_notifies_once_per_tag() {
        let now = 1_000_000;
        let fresh = UpdateCheckState::default();
        let d = decide(AutoUpgradePolicy::Notify, "v4.15.0", "v4.16.0", &fresh, now, false);
        assert_eq!(d.notify.as_deref(), Some("v4.16.0"));
        assert_eq!(d.prefetch, None, "notify policy never prefetches");

        let seen = state_with_notified("v4.16.0");
        let d = decide(AutoUpgradePolicy::Notify, "v4.15.0", "v4.16.0", &seen, now, false);
        assert_eq!(d.notify, None, "already-notified tag stays silent");
    }

    #[test]
    fn decide_runtime_policy_prefetches_and_notifies() {
        let now = 1_000_000;
        let fresh = UpdateCheckState::default();
        let d = decide(AutoUpgradePolicy::Runtime, "v4.15.0", "v4.16.0", &fresh, now, false);
        assert_eq!(d.notify.as_deref(), Some("v4.16.0"));
        assert_eq!(
            d.prefetch,
            Some(PrefetchPlan { tag: "v4.16.0".to_string(), self_upgrade: false })
        );

        // Already notified → prefetch still fires (notification is independent).
        let seen = state_with_notified("v4.16.0");
        let d = decide(AutoUpgradePolicy::Runtime, "v4.15.0", "v4.16.0", &seen, now, false);
        assert_eq!(d.notify, None);
        assert!(d.prefetch.is_some());
    }

    #[test]
    fn decide_full_policy_marks_self_upgrade() {
        let now = 1_000_000;
        let fresh = UpdateCheckState::default();
        let d = decide(AutoUpgradePolicy::Full, "v4.15.0", "v4.16.0", &fresh, now, false);
        assert_eq!(
            d.prefetch,
            Some(PrefetchPlan { tag: "v4.16.0".to_string(), self_upgrade: true })
        );
    }

    #[test]
    fn decide_never_prefetches_across_a_major_boundary() {
        let now = 1_000_000;
        let fresh = UpdateCheckState::default();
        let d = decide(AutoUpgradePolicy::Full, "v4.16.0", "v5.0.0", &fresh, now, false);
        assert_eq!(d.notify.as_deref(), Some("v5.0.0"));
        assert_eq!(d.prefetch, None, "major jumps are notify-only");
    }

    #[test]
    fn decide_skips_prefetch_when_pending_on_disk_or_rate_limited() {
        let now = 1_000_000;
        // Already pending.
        let pending = UpdateCheckState {
            pending_tag: Some("v4.16.0".to_string()),
            ..Default::default()
        };
        let d = decide(AutoUpgradePolicy::Runtime, "v4.15.0", "v4.16.0", &pending, now, false);
        assert_eq!(d.prefetch, None);

        // Already fully installed on disk.
        let d = decide(
            AutoUpgradePolicy::Runtime,
            "v4.15.0",
            "v4.16.0",
            &UpdateCheckState::default(),
            now,
            true,
        );
        assert_eq!(d.prefetch, None);

        // Same tag attempted 30 min ago → rate limited.
        let attempted = UpdateCheckState {
            last_prefetch_attempt: Some(PrefetchAttempt {
                tag: "v4.16.0".to_string(),
                at: now - 1800,
            }),
            ..Default::default()
        };
        let d = decide(AutoUpgradePolicy::Runtime, "v4.15.0", "v4.16.0", &attempted, now, false);
        assert_eq!(d.prefetch, None);

        // A *different* tag attempted 30 min ago does not rate-limit this one.
        let attempted_other = UpdateCheckState {
            last_prefetch_attempt: Some(PrefetchAttempt {
                tag: "v4.15.9".to_string(),
                at: now - 1800,
            }),
            ..Default::default()
        };
        let d = decide(
            AutoUpgradePolicy::Runtime,
            "v4.15.0",
            "v4.16.0",
            &attempted_other,
            now,
            false,
        );
        assert!(d.prefetch.is_some());
    }

    #[test]
    fn decide_blacklists_after_repeated_failures_then_forgives() {
        let now = 1_000_000;
        let blacklisted = UpdateCheckState {
            failures: HashMap::from([(
                "v4.16.0".to_string(),
                FailureRecord { count: FAILURE_BLACKLIST_THRESHOLD, last_failed_at: now - 3600 },
            )]),
            ..Default::default()
        };
        let d = decide(AutoUpgradePolicy::Runtime, "v4.15.0", "v4.16.0", &blacklisted, now, false);
        assert_eq!(d.prefetch, None, "recent ≥3 failures blacklist the tag");
        // The user is still told about the release.
        assert_eq!(d.notify.as_deref(), Some("v4.16.0"));

        // Blacklist expires after the grace window.
        let expired = UpdateCheckState {
            failures: HashMap::from([(
                "v4.16.0".to_string(),
                FailureRecord {
                    count: FAILURE_BLACKLIST_THRESHOLD,
                    last_failed_at: now - FAILURE_BLACKLIST_SECS - 1,
                },
            )]),
            ..Default::default()
        };
        let d = decide(AutoUpgradePolicy::Runtime, "v4.15.0", "v4.16.0", &expired, now, false);
        assert!(d.prefetch.is_some(), "expired blacklist allows a retry");

        // Below the threshold there is no blacklist at all.
        let some_failures = UpdateCheckState {
            failures: HashMap::from([(
                "v4.16.0".to_string(),
                FailureRecord { count: FAILURE_BLACKLIST_THRESHOLD - 1, last_failed_at: now - 60 },
            )]),
            ..Default::default()
        };
        let d = decide(
            AutoUpgradePolicy::Runtime,
            "v4.15.0",
            "v4.16.0",
            &some_failures,
            now,
            false,
        );
        assert!(d.prefetch.is_some());
    }

    #[test]
    fn record_prefetch_failure_counts_up_per_tag() {
        let mut state = UpdateCheckState::default();
        record_prefetch_failure(&mut state, "v4.16.0", 100);
        record_prefetch_failure(&mut state, "v4.16.0", 200);
        record_prefetch_failure(&mut state, "v4.15.9", 150);
        assert_eq!(state.failures["v4.16.0"].count, 2);
        assert_eq!(state.failures["v4.16.0"].last_failed_at, 200);
        assert_eq!(state.failures["v4.15.9"].count, 1);
    }

    #[test]
    fn attempt_backoff_respects_ttl_even_without_a_cached_tag() {
        let now = 1_000_000;
        let recent = UpdateCheckState {
            latest_tag: Some("v4.16.0".to_string()),
            last_check_at: Some(now - CHECK_TTL_SECS + 1),
            ..Default::default()
        };
        assert!(!should_attempt_check(&recent, now));

        let stale = UpdateCheckState {
            latest_tag: Some("v4.16.0".to_string()),
            last_check_at: Some(now - CHECK_TTL_SECS),
            ..Default::default()
        };
        assert!(should_attempt_check(&stale, now));

        // A recent *failed* attempt (no cached tag) still backs off — this is
        // what keeps an offline machine from paying the resolve timeout on
        // every command.
        let recent_failure = UpdateCheckState {
            last_check_at: Some(now - 60),
            ..Default::default()
        };
        assert!(!should_attempt_check(&recent_failure, now));

        let never_checked = UpdateCheckState::default();
        assert!(should_attempt_check(&never_checked, now), "no attempt yet");
    }

    #[test]
    fn state_roundtrips_through_the_state_file() {
        let _lock = crate::test_env::lock();
        let temp = tempfile::tempdir().unwrap();
        unsafe {
            std::env::set_var("BROWSER4_RUNTIME_DIR", temp.path());
        }

        let mut state = UpdateCheckState {
            last_check_at: Some(123),
            latest_tag: Some("v4.16.0".to_string()),
            last_notified_tag: Some("v4.16.0".to_string()),
            pending_tag: Some("v4.16.1".to_string()),
            previous_tag: Some("v4.15.0".to_string()),
            last_prefetch_attempt: Some(PrefetchAttempt { tag: "v4.16.1".to_string(), at: 42 }),
            ..Default::default()
        };
        record_prefetch_failure(&mut state, "v4.16.1", 99);
        save_state(&state);

        let loaded = load_state();
        assert_eq!(loaded.last_check_at, Some(123));
        assert_eq!(loaded.latest_tag.as_deref(), Some("v4.16.0"));
        assert_eq!(loaded.last_notified_tag.as_deref(), Some("v4.16.0"));
        assert_eq!(loaded.pending_tag.as_deref(), Some("v4.16.1"));
        assert_eq!(loaded.previous_tag.as_deref(), Some("v4.15.0"));
        assert_eq!(loaded.last_prefetch_attempt.as_ref().unwrap().at, 42);
        assert_eq!(loaded.failures["v4.16.1"].count, 1);

        unsafe {
            std::env::remove_var("BROWSER4_RUNTIME_DIR");
        }
    }

    #[test]
    fn commit_without_pending_marker_is_a_noop() {
        let _lock = crate::test_env::lock();
        let temp = tempfile::tempdir().unwrap();
        unsafe {
            std::env::set_var("BROWSER4_RUNTIME_DIR", temp.path());
        }
        // No state file at all → must not panic, must not create a marker.
        commit_pending_tag_if_ready();
        assert!(!state_file_path().exists());
        unsafe {
            std::env::remove_var("BROWSER4_RUNTIME_DIR");
        }
    }

    #[test]
    fn commit_clears_marker_when_download_is_incomplete() {
        let _lock = crate::test_env::lock();
        let temp = tempfile::tempdir().unwrap();
        unsafe {
            std::env::set_var("BROWSER4_RUNTIME_DIR", temp.path());
        }
        let state = UpdateCheckState {
            pending_tag: Some("v4.16.0".to_string()),
            ..Default::default()
        };
        save_state(&state);
        // The versioned install dir does not exist → marker must be cleared.
        commit_pending_tag_if_ready();
        assert_eq!(load_state().pending_tag, None);
        unsafe {
            std::env::remove_var("BROWSER4_RUNTIME_DIR");
        }
    }
}
