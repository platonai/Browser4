//! ⭐ Star guidance — take the user to the repository in the browser they are
//! **already logged into** and wait for *their* click.
//!
//! The CLI never stars the repository itself: no `POST /star`, no
//! `gh api -X PUT user/starred/…`, nothing that would produce an inauthentic
//! interaction (GitHub's acceptable-use policy forbids star manipulation, and a
//! star from an account that never used the tool is worth nothing anyway).  This
//! module only *navigates*, *reads the page state*, and *waits*.
//!
//! Three paths, in order of preference:
//!
//! 1. **Attached session** (`attach --extension` / `attach --cdp`) — the user's
//!    own browser, with their logins.  A new tab is opened (never the active
//!    tab), the page is inspected, and the CLI polls until the user clicks.
//! 2. **Ask, then attach once** — when no session exists but a local browser is
//!    running with `--remote-debugging-port`, the user is asked before the
//!    browser is taken over.
//! 3. **System browser** — otherwise the URL is handed to the default browser
//!    (`open_url_in_browser`) and the CLI cannot verify the click, so it says so.
//!
//! The rotating CTA that points here is frequency-capped and opt-out
//! (`BROWSER4_NO_PROMO=1`), and never printed into machine-readable output or a
//! non-terminal stream: this CLI is largely driven by AI agents, and a marketing
//! line in their context is pure noise.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{IsTerminal, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::http::{call_tool, make_client};
use crate::state::{read_state, resolve_runtime_data_dir, SessionKind};
use crate::{CliError, ExitCode};

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/// The one repository this command targets.
pub const REPO_URL: &str = "https://github.com/platonai/Browser4";

/// `?return_to=` value for the login round-trip (`/platonai/Browser4`).
const REPO_PATH: &str = "/platonai/Browser4";

const STATE_FILE_NAME: &str = "star-guide.json";

/// How many times the CTA may ever be printed, per installation.
pub const MAX_CTA_SHOWS: u32 = 3;

/// Minimum gap between two CTA impressions (7 days).
pub const CTA_MIN_INTERVAL_SECS: i64 = 7 * 24 * 60 * 60;

/// Default poll window for the user's click, in seconds.
const DEFAULT_WAIT_SECS: u64 = 60;

/// Upper bound for `--wait`.
const MAX_WAIT_SECS: u64 = 600;

/// How often the opened page is re-read while waiting.
const POLL_INTERVAL: Duration = Duration::from_millis(1000);

/// How long a freshly opened tab gets to finish loading before the first read.
const LOAD_BUDGET: Duration = Duration::from_secs(15);

/// How long to wait before the freshly opened tab is read for the first time.
const LOAD_POLL_INTERVAL: Duration = Duration::from_millis(500);

/// Fixture/testing hook: point the command at a local page instead of github.com.
/// Undocumented on purpose — the product targets exactly one repository.
const TARGET_URL_ENV: &str = "BROWSER4_STAR_URL";

/// Set to `1`/`true` to suppress the CTA permanently.
const NO_PROMO_ENV: &str = "BROWSER4_NO_PROMO";

// ---------------------------------------------------------------------------
// Local state
// ---------------------------------------------------------------------------

/// What the CLI remembers about the star prompt between invocations.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct StarGuideState {
    /// Times the CTA was actually printed.
    #[serde(default)]
    pub shows: u32,
    /// Unix seconds of the last impression.
    #[serde(default)]
    pub last_shown_at: Option<i64>,
    /// Unix seconds when the Star was observed on the page (`None` = not yet).
    #[serde(default)]
    pub starred_at: Option<i64>,
    /// The user opted out with `star --dismiss`.
    #[serde(default)]
    pub dismissed: bool,
}

/// `<runtime data dir>/star-guide.json` — the same directory that holds the
/// update-check state, so one `BROWSER4_RUNTIME_DIR` override moves both.
pub fn state_path() -> PathBuf {
    resolve_runtime_data_dir().join(STATE_FILE_NAME)
}

/// Read the state, treating a missing or unreadable file as "brand new".
///
/// A corrupt file must never break a command (or the CTA) — it just means the
/// user has not been shown the prompt yet.
pub fn load_state_from(path: &Path) -> StarGuideState {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str::<StarGuideState>(&raw).ok())
        .unwrap_or_default()
}

/// Write the state, creating the directory when needed.
pub fn save_state_to(path: &Path, state: &StarGuideState) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let mut body = serde_json::to_string_pretty(state).unwrap_or_else(|_| "{}".to_string());
    body.push('\n');
    std::fs::write(path, body)
}

pub fn load_state() -> StarGuideState {
    load_state_from(&state_path())
}

/// Best-effort persist: a read-only disk must not fail the command.
fn save_state(state: &StarGuideState) {
    let _ = save_state_to(&state_path(), state);
}

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

// ---------------------------------------------------------------------------
// CTA gating
// ---------------------------------------------------------------------------

/// Everything the CTA decision depends on, captured once.
///
/// Kept as plain data so the whole matrix is unit-testable without touching the
/// process environment, a terminal, or the clock.
#[derive(Debug, Clone, Copy, Default)]
pub struct CtaEnv {
    /// The stream the CTA is written to is a terminal.
    pub stderr_tty: bool,
    pub quiet: bool,
    pub json: bool,
    pub raw: bool,
    /// Running under CI.
    pub ci: bool,
    /// `BROWSER4_NO_PROMO` is set.
    pub no_promo: bool,
}

impl CtaEnv {
    fn from_process() -> Self {
        CtaEnv {
            stderr_tty: std::io::stderr().is_terminal(),
            quiet: crate::quiet_active(),
            json: crate::json_active(),
            raw: crate::raw_active(),
            ci: env_truthy("CI"),
            no_promo: env_truthy(NO_PROMO_ENV),
        }
    }
}

/// `NAME=1|true|yes` counts as set; `NAME=` and `NAME=0|false|no` do not.
fn env_truthy(name: &str) -> bool {
    match std::env::var(name) {
        Ok(value) => {
            let value = value.trim().to_ascii_lowercase();
            !value.is_empty() && value != "0" && value != "false" && value != "no"
        }
        Err(_) => false,
    }
}

/// Commands after which the user has just gotten something out of the tool.
///
/// A CTA after `list` or `version` would be noise; after a finished scrape, a
/// crawl, or a diagnosis it is the moment the value is actually on screen.
const CTA_COMMANDS: &[&str] = &[
    "doctor",
    "upgrade",
    "scrape",
    "crawl",
    "crawl-result",
    "htmlsnapshot-query",
    "snapshot",
    "agent-run",
    "agent-result",
    "summarize",
    "extract",
    "code-verify",
];

pub fn cta_allowed_for_command(command: &str) -> bool {
    CTA_COMMANDS.contains(&command)
}

/// Whether the CTA may be printed right now.
///
/// Order matters only for readability: every gate is an independent veto.
pub fn should_show_cta(now: i64, state: &StarGuideState, env: &CtaEnv) -> bool {
    if !env.stderr_tty || env.quiet || env.json || env.raw || env.ci || env.no_promo {
        return false;
    }
    if state.dismissed || state.starred_at.is_some() {
        return false;
    }
    if state.shows >= MAX_CTA_SHOWS {
        return false;
    }
    if let Some(last) = state.last_shown_at {
        // A clock that jumped backwards must not silence the CTA forever.
        if now >= last && now - last < CTA_MIN_INTERVAL_SECS {
            return false;
        }
    }
    true
}

/// Print the one-line CTA after a value-producing command, at most
/// [MAX_CTA_SHOWS] times, at least [CTA_MIN_INTERVAL_SECS] apart.
pub fn maybe_show_cta(command: &str) {
    if !cta_allowed_for_command(command) {
        return;
    }

    let env = CtaEnv::from_process();
    let mut state = load_state();
    let now = now_secs();
    if !should_show_cta(now, &state, &env) {
        return;
    }

    let bin = crate::cli_binary_name();
    eprintln!();
    eprintln!("⭐ Finding Browser4 useful? Run `{bin} star`.");
    eprintln!("   It opens {REPO_URL} in the browser you are already logged into and waits for");
    eprintln!("   your click — the CLI never clicks Star for you. Turn this off: {NO_PROMO_ENV}=1");
    state.shows += 1;
    state.last_shown_at = Some(now);
    save_state(&state);
}

// ---------------------------------------------------------------------------
// Target URL
// ---------------------------------------------------------------------------

/// Resolve the page to open.  The environment override exists for the fixture
/// page the e2e harness serves locally; anything that is not an HTTP(S) URL is
/// ignored rather than trusted.
pub fn resolve_target_url(override_value: Option<&str>) -> String {
    match override_value.map(str::trim) {
        Some(value) if value.starts_with("http://") || value.starts_with("https://") => {
            value.to_string()
        }
        _ => REPO_URL.to_string(),
    }
}

fn target_url() -> String {
    let override_value = std::env::var(TARGET_URL_ENV).ok();
    resolve_target_url(override_value.as_deref())
}

/// Lowercased host of an absolute URL, without port or userinfo.
fn host_of(url: &str) -> Option<String> {
    let rest = url.split_once("://")?.1;
    let authority = rest.split(['/', '?', '#']).next()?;
    let host = authority.rsplit('@').next()?;
    let host = host.split(':').next().unwrap_or(host);
    if host.is_empty() {
        None
    } else {
        Some(host.to_ascii_lowercase())
    }
}

/// `https://github.com/login?return_to=%2Fplatonai%2FBrowser4`, or the repo URL
/// itself when the target is not github.com (the fixture page has no login).
pub fn login_url_for(repo_url: &str) -> String {
    let is_github = host_of(repo_url).as_deref() == Some("github.com");
    if is_github {
        format!(
            "https://github.com/login?return_to={}",
            urlencoding::encode(REPO_PATH)
        )
    } else {
        repo_url.to_string()
    }
}

// ---------------------------------------------------------------------------
// Page state
// ---------------------------------------------------------------------------

/// What the opened GitHub page says about the user.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PageState {
    /// The page did not answer, is still loading, or is not the target page.
    Unknown,
    /// GitHub reports no logged-in user.
    NotLoggedIn,
    /// Signed in; `starred` reflects the page's own star control.
    LoggedIn { login: String, starred: bool },
}

/// One JavaScript expression that reports everything the CLI needs.
///
/// Signals, strongest first:
/// * `<meta name="user-login">` — present on authenticated GitHub pages;
/// * `form[action$="/unstar"]` — GitHub's own markup for the starred state;
/// * `aria-pressed="true"` on the star button — the current React control;
/// * the button label (`Unstar` / `Starred`).
///
/// Anything unrecognised stays `false` for `starred` and the caller keeps the
/// page as `Unknown` at worst, so a GitHub redesign degrades to "please click
/// it yourself" rather than a wrong claim.
fn detection_js() -> &'static str {
    r#"(() => {
  try {
    const meta = document.querySelector('meta[name="user-login"]');
    const login = ((meta && meta.getAttribute('content')) || '').trim();
    const actionOf = el => (el.getAttribute('action') || '').toLowerCase();
    const forms = Array.from(document.querySelectorAll('form'));
    const unstarForm = forms.find(f => actionOf(f).endsWith('/unstar'));
    const starForm = forms.find(f => actionOf(f).endsWith('/star'));
    const label = el => ((el.innerText || el.textContent || '') || '').trim().toLowerCase();
    const buttons = Array.from(document.querySelectorAll('button'));
    const btn = buttons.find(b => /^(star|starred|unstar)$/.test(label(b))) ||
                buttons.find(b => /^(star|unstar)\b/.test(label(b)));
    const pressed = btn ? btn.getAttribute('aria-pressed') : null;
    const text = btn ? label(btn) : null;
    const starred = !!unstarForm || pressed === 'true' || text === 'unstar' || text === 'starred';
    return JSON.stringify({
      href: location.href,
      login: login,
      starred: starred,
      hasStarUi: !!(starForm || unstarForm || btn)
    });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})()"#
}

/// Parse the detection payload.  `expected_host` guards against reading a
/// different page (the user switched tabs while the CLI was waiting).
///
/// The MCP result is normally the JSON string the script returned, but some
/// paths wrap a string result in JSON quotes — both forms are accepted.
pub fn parse_detection(raw: &str, expected_host: &str) -> PageState {
    let Ok(mut value) = serde_json::from_str::<Value>(raw.trim()) else {
        return PageState::Unknown;
    };
    if let Some(inner) = value.as_str() {
        match serde_json::from_str::<Value>(inner.trim()) {
            Ok(parsed) => value = parsed,
            Err(_) => return PageState::Unknown,
        }
    }
    if value.get("error").is_some() {
        return PageState::Unknown;
    }
    let href = value
        .get("href")
        .and_then(Value::as_str)
        .unwrap_or_default();
    match host_of(href) {
        Some(host) if host == expected_host => {}
        _ => return PageState::Unknown,
    }
    let login = value
        .get("login")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim()
        .to_string();
    if login.is_empty() {
        return PageState::NotLoggedIn;
    }
    let starred = value
        .get("starred")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    PageState::LoggedIn { login, starred }
}

// ---------------------------------------------------------------------------
// Command options
// ---------------------------------------------------------------------------

/// Parsed `star` options.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StarOptions {
    /// Read the current page instead of opening a new one.
    pub check: bool,
    /// Never touch a browser; print the URL and the commands that would open it.
    pub no_open: bool,
    /// Seconds to wait for the click (0 = do not wait).
    pub wait_secs: u64,
    /// Turn the CTA off permanently.
    pub dismiss: bool,
}

impl Default for StarOptions {
    fn default() -> Self {
        StarOptions {
            check: false,
            no_open: false,
            wait_secs: DEFAULT_WAIT_SECS,
            dismiss: false,
        }
    }
}

impl StarOptions {
    pub fn from_parsed(parsed: &HashMap<String, Value>) -> Self {
        let mut options = StarOptions::default();
        options.check = flag(parsed, "check");
        options.no_open = flag(parsed, "no-open");
        options.dismiss = flag(parsed, "dismiss");
        if let Some(raw) = parsed.get("wait") {
            let parsed_wait = raw
                .as_u64()
                .or_else(|| raw.as_str().and_then(|s| s.trim().parse::<u64>().ok()));
            if let Some(seconds) = parsed_wait {
                options.wait_secs = seconds.min(MAX_WAIT_SECS);
            }
        }
        options
    }
}

/// Options arrive as `Bool(true)` or as strings depending on the parser path.
fn flag(parsed: &HashMap<String, Value>, key: &str) -> bool {
    match parsed.get(key) {
        Some(Value::Bool(value)) => *value,
        Some(Value::String(value)) => {
            let value = value.trim().to_ascii_lowercase();
            value == "true" || value == "1" || value == "yes"
        }
        _ => false,
    }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

pub async fn run(
    base_url: &str,
    session_name: Option<&str>,
    parsed: &HashMap<String, Value>,
) -> Result<(), CliError> {
    let options = StarOptions::from_parsed(parsed);
    let url = target_url();
    crate::json_field("url", json!(url));

    if options.dismiss {
        let mut state = load_state();
        state.dismissed = true;
        save_state(&state);
        say("Star reminders are off. You can still run `browser4-cli star` whenever you like.");
        crate::json_field("dismissed", json!(true));
        return Ok(());
    }

    let mut guide = load_state();
    if guide.starred_at.is_some() && !options.check {
        say(&format!(
            "⭐ You already starred {url} — thank you! (`star --check` re-reads the live page.)"
        ));
        crate::json_field("page", json!("starred"));
        return Ok(());
    }

    if options.check {
        let Some(session_id) = attached_session(session_name) else {
            let bin = crate::cli_binary_name();
            return Err(CliError(
                ExitCode::Session,
                format!(
                    "`star --check` reads the page an attached browser is showing, and no attached \
                     browser is known.\nAttach the browser you are logged into, then check again:\n  \
                     {bin} attach --extension chrome\n  {bin} star --check"
                ),
            ));
        };
        ensure_server(base_url).await?;
        let client = make_client();
        // `--check` never opens a tab.  It re-reads a tab that already shows the
        // repository — after a reload, because a tab opened before the click
        // keeps showing the old button ("Star") until it is reloaded, and
        // reading that stale copy reported a star as missing.
        let page = match focus_repo_tab(&client, base_url, &session_id, &url).await {
            Ok(true) => {
                let _ = call_tool(
                    &client,
                    base_url,
                    "browser_navigate",
                    json!({ "sessionId": session_id, "url": url }),
                )
                .await;
                poll_detection(&client, base_url, &session_id, &url).await
            }
            Ok(false) | Err(_) => {
                say(&format!(
                    "No tab is showing {url}. Run `{} star` to open one.",
                    crate::cli_binary_name()
                ));
                crate::json_field("page", json!("no-tab"));
                return Ok(());
            }
        };
        record_and_report(page, &mut guide, &url);
        return Ok(());
    }

    if options.no_open {
        print_dry_run(&url);
        return Ok(());
    }

    // Path 1/2: drive a browser, if one can be reached without taking anything
    // over silently.
    if attached_session(session_name).is_none() {
        ask_then_attach(base_url, session_name).await?;
    }

    if let Some(session_id) = attached_session(session_name) {
        ensure_server(base_url).await?;
        let client = make_client();
        let page = match open_repo_in_new_tab(&client, base_url, &session_id, &url).await {
            PageState::NotLoggedIn => {
                // Signing in is the one thing the user has to do; point the tab
                // at GitHub's login page with the repo as the return target.
                let login = login_url_for(&url);
                if login != url {
                    let _ = call_tool(
                        &client,
                        base_url,
                        "browser_navigate",
                        json!({ "sessionId": session_id, "url": login }),
                    )
                    .await;
                }
                PageState::NotLoggedIn
            }
            PageState::LoggedIn {
                login,
                starred: false,
            } => wait_for_click(&client, base_url, &session_id, &url, options.wait_secs)
                .await
                .unwrap_or(PageState::LoggedIn {
                    login,
                    starred: false,
                }),
            other => other,
        };
        record_and_report(page, &mut guide, &url);
        return Ok(());
    }

    // Path 3: no browser to drive — hand it to the default browser and say so.
    open_in_system_browser(&url);
    Ok(())
}

/// Print the outcome of an inspection and remember a confirmed star.
fn record_and_report(page: PageState, guide: &mut StarGuideState, url: &str) {
    match page {
        PageState::LoggedIn { login, starred } => {
            if starred {
                guide.starred_at = Some(now_secs());
                guide.shows = MAX_CTA_SHOWS;
                guide.last_shown_at = Some(now_secs());
                save_state(guide);
                let who = if login.is_empty() {
                    String::new()
                } else {
                    format!(" as @{login}")
                };
                say(&format!(
                    "⭐ Thanks{who} — {url} is starred. That genuinely helps the project."
                ));
                crate::json_field("page", json!("starred"));
                crate::json_field("login", json!(login));
            } else {
                // A stale local record (user un-starred) is corrected here.
                if guide.starred_at.is_some() {
                    guide.starred_at = None;
                    save_state(guide);
                }
                let who = if login.is_empty() {
                    String::new()
                } else {
                    format!(" (@{login})")
                };
                say(&format!(
                    "Signed in{who} and the repo is not starred yet. Click ⭐ Star in that tab — \
                     Browser4 never clicks it for you."
                ));
                crate::json_field("page", json!("not-starred"));
                crate::json_field("login", json!(login));
            }
        }
        PageState::NotLoggedIn => {
            say(&format!(
                "That tab is not signed in to GitHub. Log in there, then run `{} star` again — \
                 nothing was clicked on your behalf.",
                crate::cli_binary_name()
            ));
            crate::json_field("page", json!("logged-out"));
        }
        PageState::Unknown => {
            say(&format!(
                "Could not read the page state at {url} (the tab may still be loading, the active \
                 tab may have moved, or GitHub's markup changed). Click ⭐ Star yourself, then \
                 re-run `{} star --check`.",
                crate::cli_binary_name()
            ));
            crate::json_field("page", json!("unknown"));
        }
    }
}

/// The session id of an attached (extension / CDP) browser, if any.
fn attached_session(session_name: Option<&str>) -> Option<String> {
    let state = read_state(None, session_name);
    let attached = matches!(
        state.kind,
        SessionKind::CdpAttached | SessionKind::ExtensionAttached
    );
    if attached {
        state.session_id
    } else {
        None
    }
}

async fn ensure_server(base_url: &str) -> Result<(), CliError> {
    crate::daemon::ensure_server_running(base_url, false)
        .await
        .map_err(|err| CliError(ExitCode::Server, err))
}

// ---------------------------------------------------------------------------
// Attaching a running browser (asked first, never silently)
// ---------------------------------------------------------------------------

/// Offer to connect the browser the user already has open.
///
/// Only reachable when no session exists: attaching would otherwise evict the
/// session the user is working in, and stealing that is worse than not being
/// able to verify the click.
async fn ask_then_attach(base_url: &str, session_name: Option<&str>) -> Result<(), CliError> {
    if read_state(None, session_name).session_id.is_some() {
        return Ok(());
    }
    let browsers = crate::daemon::discover_debuggable_browsers();
    let Some(browser) = browsers.iter().find(|b| b.reachable) else {
        return Ok(());
    };
    let label = browser
        .browser
        .clone()
        .unwrap_or_else(|| browser.family.to_string());
    let profile = browser
        .user_data_dir
        .clone()
        .unwrap_or_else(|| "(default profile)".to_string());
    let question = format!(
        "Browser4 can use {label} on port {} (profile: {profile}) — it carries your GitHub login.\n\
         It opens the repo in a NEW tab and leaves your other tabs alone. Connect it? [y/N] ",
        browser.port
    );
    if !confirm(&question) {
        return Ok(());
    }

    ensure_server(base_url).await?;
    let client = make_client();
    let mut attach_args: HashMap<String, Value> = HashMap::new();
    attach_args.insert("cdp".to_string(), json!(browser.port.to_string()));
    match crate::handle_attach(&client, base_url, &json!({}), session_name, &attach_args).await {
        Ok(()) => Ok(()),
        Err(err) => {
            eprintln!("Could not attach: {err}");
            eprintln!("Falling back to your default browser.");
            Ok(())
        }
    }
}

/// Ask a yes/no question on stderr.  Never blocks when stdin is not a terminal.
fn confirm(question: &str) -> bool {
    if !std::io::stdin().is_terminal() {
        return false;
    }
    eprint!("{question}");
    let _ = std::io::stderr().flush();
    let mut answer = String::new();
    if std::io::stdin().read_line(&mut answer).is_err() {
        return false;
    }
    matches!(answer.trim().to_ascii_lowercase().as_str(), "y" | "yes")
}

// ---------------------------------------------------------------------------
// Driving the attached browser
// ---------------------------------------------------------------------------

/// Open the repository in a new tab, select it, and read its state.
async fn open_repo_in_new_tab(
    client: &reqwest::Client,
    base_url: &str,
    session_id: &str,
    url: &str,
) -> PageState {
    let created = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "new", "url": url }),
    )
    .await;
    if let Ok(response) = &created {
        let guid = serde_json::from_str::<Value>(response.trim())
            .ok()
            .and_then(|v| v.get("guid").and_then(|g| g.as_str()).map(String::from));
        if let Some(guid) = guid.as_deref() {
            let _ = activate_tab(client, base_url, session_id, guid).await;
        }
    }

    // The tab exists but its navigation may still be in flight: poll until the
    // page reports something, without pretending a blank page is a verdict.
    poll_detection(client, base_url, session_id, url).await
}

/// Read the page until it answers (or [LOAD_BUDGET] runs out).
async fn poll_detection(
    client: &reqwest::Client,
    base_url: &str,
    session_id: &str,
    url: &str,
) -> PageState {
    let deadline = Instant::now() + LOAD_BUDGET;
    let mut page = PageState::Unknown;
    while Instant::now() < deadline {
        page = detect_once(client, base_url, session_id, url)
            .await
            .unwrap_or(PageState::Unknown);
        if page != PageState::Unknown {
            break;
        }
        tokio::time::sleep(LOAD_POLL_INTERVAL).await;
    }
    page
}

/// Select an already-open tab that shows the repository, if there is one.
///
/// `star --check` must not open anything, so this is how it finds the page the
/// user is looking at after a previous `star` run.  The **active** tab wins when
/// it already shows the repository: two repo tabs are normal (one per `star`
/// run), and reading the one the user is not looking at yielded a stale
/// "not starred" verdict during the end-to-end run.
async fn focus_repo_tab(
    client: &reqwest::Client,
    base_url: &str,
    session_id: &str,
    url: &str,
) -> Result<bool, String> {
    let listing = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "list" }),
    )
    .await?;
    let tabs = crate::parse_tab_list(&listing);
    if tabs
        .iter()
        .any(|tab| tab.active && tab_matches_url(&tab.url, url))
    {
        return Ok(true);
    }
    let Some(index) = tabs
        .iter()
        .find(|tab| tab_matches_url(&tab.url, url))
        .map(|tab| tab.index)
    else {
        return Ok(false);
    };
    call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "select", "index": index }),
    )
    .await?;
    Ok(true)
}

/// True when a tab shows exactly this page (trailing slash and case ignored).
pub fn tab_matches_url(tab_url: &str, target_url: &str) -> bool {
    fn normalize(url: &str) -> String {
        url.trim().trim_end_matches('/').to_ascii_lowercase()
    }
    !tab_url.trim().is_empty() && normalize(tab_url) == normalize(target_url)
}

/// Re-read the page while the user is expected to click ⭐.
async fn wait_for_click(
    client: &reqwest::Client,
    base_url: &str,
    session_id: &str,
    url: &str,
    wait_secs: u64,
) -> Option<PageState> {
    if wait_secs == 0 {
        return None;
    }
    say(&format!(
        "Waiting up to {wait_secs}s for your click in that tab (Ctrl-C stops waiting)."
    ));
    let deadline = Instant::now() + Duration::from_secs(wait_secs);
    let mut last = None;
    while Instant::now() < deadline {
        tokio::time::sleep(POLL_INTERVAL).await;
        let page = detect_once(client, base_url, session_id, url)
            .await
            .unwrap_or(PageState::Unknown);
        match &page {
            PageState::LoggedIn { starred: true, .. } => return Some(page),
            PageState::LoggedIn { .. } => last = Some(page),
            // The active tab moved away: stop rather than read an unrelated page
            // for the rest of the window.
            PageState::NotLoggedIn => return Some(page),
            PageState::Unknown => {}
        }
    }
    last
}

/// Evaluate the detection script against the active tab.
async fn detect_once(
    client: &reqwest::Client,
    base_url: &str,
    session_id: &str,
    url: &str,
) -> Result<PageState, String> {
    let response = call_tool(
        client,
        base_url,
        "browser_evaluate",
        json!({ "sessionId": session_id, "expression": detection_js() }),
    )
    .await?;
    let expected_host = host_of(url).unwrap_or_default();
    Ok(parse_detection(&response, &expected_host))
}

/// Make the tab we just created the active one, so `browser_evaluate` reads it.
async fn activate_tab(
    client: &reqwest::Client,
    base_url: &str,
    session_id: &str,
    guid: &str,
) -> Result<(), String> {
    let listing = call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "list" }),
    )
    .await?;
    let tabs = crate::parse_tab_list(&listing);
    let Some(tab) = tabs.iter().find(|tab| tab.guid.as_deref() == Some(guid)) else {
        return Ok(());
    };
    call_tool(
        client,
        base_url,
        "browser_tabs",
        json!({ "sessionId": session_id, "action": "select", "index": tab.index }),
    )
    .await
    .map(|_| ())
}

// ---------------------------------------------------------------------------
// Fallbacks
// ---------------------------------------------------------------------------

/// `--no-open`: say what to do, touch nothing.
fn print_dry_run(url: &str) {
    let bin = crate::cli_binary_name();
    say(&format!(
        "Open {url} and click ⭐ Star — that is the whole ask."
    ));
    say(&format!(
        "Nothing was opened and nothing was clicked. To have the CLI navigate for you: `{bin} \
         star`; to have it read the result afterwards: `{bin} star --check` (needs `{bin} attach \
         --extension chrome`)."
    ));
    crate::json_field("opened", json!("none"));
    crate::json_field("page", json!("unverified"));
}

/// Hand the URL to the platform's default browser.  Nothing can be verified
/// afterwards, so the message says exactly that.
fn open_in_system_browser(url: &str) {
    let bin = crate::cli_binary_name();
    if crate::open_url_in_browser(url, None) {
        say(&format!(
            "Opened {url} in your default browser. Click ⭐ Star there — Browser4 never clicks it \
             for you."
        ));
        say(&format!(
            "To let the CLI confirm it next time, attach the browser you are logged into: \
             `{bin} attach --extension chrome`, then `{bin} star --check`."
        ));
        crate::json_field("opened", json!("system-browser"));
    } else {
        say(&format!(
            "Open {url} and click ⭐ Star — no browser could be launched from here."
        ));
        say(&format!(
            "On a headless host, run this where you are signed in: `gh repo star \
             platonai/Browser4`."
        ));
        crate::json_field("opened", json!("none"));
    }
    crate::json_field("page", json!("unverified"));
}

/// Print a line unless the caller asked for machine-readable output.
fn say(line: &str) {
    if !crate::quiet_active() && !crate::json_active() {
        crate::print_stdout_line(line);
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    const NOW: i64 = 1_800_000_000;

    fn env() -> CtaEnv {
        CtaEnv {
            stderr_tty: true,
            ..CtaEnv::default()
        }
    }

    fn fresh() -> StarGuideState {
        StarGuideState::default()
    }

    #[test]
    fn cta_needs_a_terminal() {
        let mut env = env();
        env.stderr_tty = false;
        assert!(!should_show_cta(NOW, &fresh(), &env));
    }

    #[test]
    fn cta_is_silenced_by_machine_readable_output() {
        for mutate in [
            |env: &mut CtaEnv| env.json = true,
            |env: &mut CtaEnv| env.quiet = true,
            |env: &mut CtaEnv| env.raw = true,
            |env: &mut CtaEnv| env.ci = true,
            |env: &mut CtaEnv| env.no_promo = true,
        ] {
            let mut env = env();
            mutate(&mut env);
            assert!(
                !should_show_cta(NOW, &fresh(), &env),
                "gate {env:?} must suppress the CTA"
            );
        }
    }

    #[test]
    fn cta_stops_once_starred_or_dismissed() {
        let starred = StarGuideState {
            starred_at: Some(NOW),
            ..fresh()
        };
        assert!(!should_show_cta(NOW, &starred, &env()));

        let dismissed = StarGuideState {
            dismissed: true,
            ..fresh()
        };
        assert!(!should_show_cta(NOW, &dismissed, &env()));
    }

    #[test]
    fn cta_respects_the_show_cap() {
        let capped = StarGuideState {
            shows: MAX_CTA_SHOWS,
            last_shown_at: Some(NOW - CTA_MIN_INTERVAL_SECS - 1),
            ..fresh()
        };
        assert!(!should_show_cta(NOW, &capped, &env()));
    }

    #[test]
    fn cta_respects_the_interval() {
        let recent = StarGuideState {
            shows: 1,
            last_shown_at: Some(NOW - 60),
            ..fresh()
        };
        assert!(!should_show_cta(NOW, &recent, &env()));

        let old = StarGuideState {
            shows: 1,
            last_shown_at: Some(NOW - CTA_MIN_INTERVAL_SECS - 1),
            ..fresh()
        };
        assert!(should_show_cta(NOW, &old, &env()));
    }

    #[test]
    fn cta_tolerates_a_clock_that_moved_backwards() {
        let future = StarGuideState {
            shows: 1,
            last_shown_at: Some(NOW + 90 * 24 * 3600),
            ..fresh()
        };
        // `now >= last` is false, so the interval check is skipped on purpose.
        assert!(should_show_cta(NOW, &future, &env()));
    }

    #[test]
    fn cta_is_limited_to_value_commands() {
        for command in [
            "doctor",
            "upgrade",
            "scrape",
            "crawl",
            "snapshot",
            "agent-run",
        ] {
            assert!(
                cta_allowed_for_command(command),
                "{command} should allow the CTA"
            );
        }
        for command in ["star", "install", "version", "list", "goto", "help", ""] {
            assert!(
                !cta_allowed_for_command(command),
                "{command} must not trigger the CTA"
            );
        }
    }

    #[test]
    fn state_roundtrips_through_the_file() {
        let dir = TempDir::new().expect("temp dir");
        let path = dir.path().join("star-guide.json");
        let state = StarGuideState {
            shows: 2,
            last_shown_at: Some(NOW),
            starred_at: None,
            dismissed: false,
        };
        save_state_to(&path, &state).expect("write");
        assert_eq!(load_state_from(&path), state);
    }

    #[test]
    fn missing_or_corrupt_state_is_treated_as_new() {
        let dir = TempDir::new().expect("temp dir");
        let missing = dir.path().join("nope.json");
        assert_eq!(load_state_from(&missing), StarGuideState::default());

        let corrupt = dir.path().join("corrupt.json");
        std::fs::write(&corrupt, "{not json").expect("write");
        assert_eq!(load_state_from(&corrupt), StarGuideState::default());
    }

    #[test]
    fn detection_reads_the_logged_out_page() {
        let raw = r#"{"href":"https://github.com/platonai/Browser4","login":"","starred":false,"hasStarUi":true}"#;
        assert_eq!(parse_detection(raw, "github.com"), PageState::NotLoggedIn);
    }

    #[test]
    fn detection_reads_a_signed_in_unstarred_page() {
        let raw = r#"{"href":"https://github.com/platonai/Browser4","login":"octocat","starred":false,"hasStarUi":true}"#;
        assert_eq!(
            parse_detection(raw, "github.com"),
            PageState::LoggedIn {
                login: "octocat".to_string(),
                starred: false
            }
        );
    }

    #[test]
    fn detection_reads_a_starred_page() {
        let raw = r#"{"href":"https://github.com/platonai/Browser4","login":"octocat","starred":true,"hasStarUi":true}"#;
        assert_eq!(
            parse_detection(raw, "github.com"),
            PageState::LoggedIn {
                login: "octocat".to_string(),
                starred: true
            }
        );
    }

    #[test]
    fn detection_ignores_another_page() {
        // The user switched tabs while the CLI was waiting: reading that page
        // must never be reported as the repository's state.
        let raw = r#"{"href":"https://mail.example.com/inbox","login":"octocat","starred":true}"#;
        assert_eq!(parse_detection(raw, "github.com"), PageState::Unknown);
    }

    #[test]
    fn detection_garbage_is_unknown_not_starred() {
        for raw in [
            "",
            "null",
            "\"quoted nonsense\"",
            "{\"error\":\"boom\"}",
            "{}",
        ] {
            assert_eq!(
                parse_detection(raw, "github.com"),
                PageState::Unknown,
                "raw payload {raw:?}"
            );
        }
    }

    #[test]
    fn detection_accepts_a_json_quoted_payload() {
        // MCP wraps a string result in JSON quotes on some paths.
        let raw = "\"{\\\"href\\\":\\\"https://github.com/platonai/Browser4\\\",\\\"login\\\":\\\"me\\\",\\\"starred\\\":true}\"";
        assert_eq!(
            parse_detection(raw, "github.com"),
            PageState::LoggedIn {
                login: "me".to_string(),
                starred: true
            }
        );
    }

    #[test]
    fn host_comparison_ignores_port_and_case() {
        assert_eq!(
            host_of("https://GitHub.com/platonai/Browser4").as_deref(),
            Some("github.com")
        );
        assert_eq!(
            host_of("http://127.0.0.1:38123/b4/star.html").as_deref(),
            Some("127.0.0.1")
        );
        assert_eq!(host_of("not a url"), None);
    }

    #[test]
    fn tab_url_match_ignores_case_and_a_trailing_slash() {
        assert!(tab_matches_url(REPO_URL, REPO_URL));
        assert!(tab_matches_url(
            "https://github.com/platonai/Browser4/",
            REPO_URL
        ));
        assert!(tab_matches_url(
            "  HTTPS://GitHub.com/platonai/Browser4  ",
            REPO_URL
        ));
        // A sub-page of the repository is not the repository page.
        assert!(!tab_matches_url(
            "https://github.com/platonai/Browser4/issues",
            REPO_URL
        ));
        assert!(!tab_matches_url("", REPO_URL));
        assert!(!tab_matches_url("about:blank", REPO_URL));
    }

    #[test]
    fn login_url_only_applies_to_github() {
        assert_eq!(
            login_url_for(REPO_URL),
            "https://github.com/login?return_to=%2Fplatonai%2FBrowser4"
        );
        // The fixture page has no login flow of its own.
        assert_eq!(
            login_url_for("http://127.0.0.1:9/b4/star.html"),
            "http://127.0.0.1:9/b4/star.html"
        );
    }

    #[test]
    fn target_url_ignores_a_non_http_override() {
        assert_eq!(resolve_target_url(None), REPO_URL);
        assert_eq!(resolve_target_url(Some("")), REPO_URL);
        assert_eq!(resolve_target_url(Some("platonai/Browser4")), REPO_URL);
        assert_eq!(
            resolve_target_url(Some("http://127.0.0.1:9/b4/star.html")),
            "http://127.0.0.1:9/b4/star.html"
        );
    }

    #[test]
    fn options_default_to_waiting_once() {
        let options = StarOptions::from_parsed(&HashMap::new());
        assert_eq!(options, StarOptions::default());
        assert_eq!(options.wait_secs, DEFAULT_WAIT_SECS);
        assert!(!options.check && !options.no_open && !options.dismiss);
    }

    #[test]
    fn options_accept_bools_and_a_clamped_wait() {
        let mut parsed = HashMap::new();
        parsed.insert("check".to_string(), json!(true));
        parsed.insert("no-open".to_string(), json!("yes"));
        parsed.insert("wait".to_string(), json!("30"));
        let options = StarOptions::from_parsed(&parsed);
        assert!(options.check);
        assert!(options.no_open);
        assert_eq!(options.wait_secs, 30);

        let mut parsed = HashMap::new();
        parsed.insert("wait".to_string(), json!(u64::MAX));
        assert_eq!(StarOptions::from_parsed(&parsed).wait_secs, MAX_WAIT_SECS);

        let mut parsed = HashMap::new();
        parsed.insert("check".to_string(), json!(false));
        assert!(!StarOptions::from_parsed(&parsed).check);
    }
}
