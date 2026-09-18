//! CLI tip system — shows a relevant, rotating tip on stderr after each
//! successful command to remind AI agents of advanced Browser4 capabilities.
//!
//! Tips are **suppressed by default**.  They are only shown when the user
//! passes the `--show-tip` / `-tip` global flag.
//!
//! Tips are additionally suppressed when `--json` or `--quiet` is active,
//! and for infrastructure commands (help, version, install, etc.).

use std::cell::Cell;

// ---------------------------------------------------------------------------
// Tip data
// ---------------------------------------------------------------------------

struct Tip {
    /// The tip text (must be a single line for clean stderr output).
    text: &'static str,
}

// ---------------------------------------------------------------------------
// Command-specific tips
// ---------------------------------------------------------------------------

const TIPS_SNAPSHOT: &[Tip] = &[
    Tip {
        text: "Use `snapshot --auto-diff` after interactions to see only what changed — the fastest way to verify actions",
    },
    Tip {
        text: "Use `snapshot -v 0` for the current visible screen — avoids large snapshots exceeding context limits",
    },
    Tip {
        text: "Use `snapshot grep <pattern>` to search a saved snapshot without re-reading the entire file",
    },
    Tip {
        text: "Use `snapshot -i` to merge inner text into element names so ref lines read as self-contained targets (not a strict filter — pair with `-v 0` to bound the output size)",
    },
    Tip {
        text: "Use `snapshot --stdout --page 1` to read snapshots directly in stdout without file access",
    },
    Tip {
        text: "Use `snapshot --filename result.yaml` for named output — great for workflow artifact tracking",
    },
];

const TIPS_HTMLSNAPSHOT_GET: &[Tip] = &[
    Tip {
        text: "Use PowerCSS `:expr()` selectors: `htmlsnapshot get all attr \"img:expr(width>400)\" src` to get large images only",
    },
    Tip {
        text: "Use `htmlsnapshot get all` (note the `all` keyword) to extract ALL matching elements, not just the first",
    },
    Tip {
        text: "To correlate multiple fields (titles + prices + URLs) from a list page, use `htmlsnapshot query` with X-SQL — `get all` arrays can't be aligned across independent calls. See skills/browser4-cli/references/x-sql-dom-load-select.md",
    },
    Tip {
        text: "Use `htmlsnapshot inspect <selector>` to analyze DOM structure and discover CSS selectors before extracting",
    },
    Tip {
        text: "Use `get attr <ref> id` or `get attr <ref> class` to bridge a snapshot ref to a CSS selector",
    },
    Tip {
        text: "Use `--page N --page-size 500` for paginated results when extracting many elements",
    },
    Tip {
        text: "Use `htmlsnapshot export --clean` to strip scripts, styles, and non-standard attributes — produces minimal HTML ideal for LLM consumption",
    },
];

const TIPS_READABILITY: &[Tip] = &[
    Tip {
        text: "Use `htmlsnapshot readability` to extract the main article in one step — no CSS selectors, no LLM tokens. Deterministic Readability-style heuristic on the stored snapshot",
    },
    Tip {
        text: "`htmlsnapshot readability <url>` fetches a specific page independently, like `htmlsnapshot query`'s @url mode",
    },
];

const TIPS_HTMLSNAPSHOT_QUERY: &[Tip] = &[
    Tip {
        text: "X-SQL has ~200 functions across DOM_*, STR_*, and ARRAY_* namespaces — see skills/browser4-cli/references/x-sql.md",
    },
    Tip {
        text: "Use `STR_DEFAULT_IF_BLANK(value, 'N/A')` to handle missing values gracefully in X-SQL results",
    },
    Tip {
        text: "X-SQL supports `ORDER BY`, `WHERE`, `LIMIT` — full SQL power directly on DOM data",
    },
    Tip {
        text: "Use `DOM_LOAD_AND_SELECT(url, css, offset, limit)` with pagination params to handle large result sets",
    },
    Tip {
        text: "Use `:expr()` pseudo-selectors in X-SQL CSS queries to filter by size, position, and content density",
    },
];

const TIPS_INTERACTION: &[Tip] = &[
    Tip {
        text: "Refs are single-use! Always re-snapshot after any interaction before using refs again",
    },
    Tip {
        text: "Use `snapshot --auto-diff` after this interaction to verify the page changed as expected",
    },
    Tip {
        text: "Use `fill` instead of `click` + `type` — it clears the field first, avoiding appended text",
    },
    Tip {
        text: "If 'fill' fails with 'not focusable', try 'click <ref>' first then 'type <text>' — some elements need a click to gain focus",
    },
    Tip {
        text: "Use `press Enter` after `fill` to submit forms — `fill` alone does not trigger submission",
    },
    Tip {
        text: "Use `generate-locator <ref>` to get a robust CSS selector from a snapshot ref for repeated use",
    },
];

const TIPS_NAVIGATION: &[Tip] = &[
    Tip {
        text: "After navigation, capture with `snapshot -v 0` to get the current viewport with element refs",
    },
    Tip {
        text: "Use load options in the URL: `goto \"url -expires 1d -refresh\"` for caching control",
    },
    Tip {
        text: "Browsers open headless by default. Use `--headed` to see the browser window for debugging",
    },
    Tip {
        text: "Use named sessions (`-s <name>`) with `goto` to maintain separate browser state per task",
    },
];

const TIPS_EVAL: &[Tip] = &[
    Tip {
        text: "Use `eval --file script.js` or `eval --stdin` to avoid shell quoting pain on Windows",
    },
    Tip {
        text: "Use `eval --json` for machine-parseable JSON output — perfect for piping to other tools",
    },
    Tip {
        text: "Use `eval --await` for async JavaScript (fetch, setTimeout, etc.) — waits for the Promise to resolve",
    },
    Tip {
        text: "For structured data extraction without quoting pain, consider X-SQL: `htmlsnapshot query --sql \"...\"`",
    },
    Tip {
        text: "Use `eval` with a `[ref]` argument to scope JS execution to a specific element: the expression MUST be an arrow function, e.g. `eval \"element => element.textContent\" e5`",
    },
    Tip {
        text: "When using `eval --ref`, write `element => element.property` — the element DOM node is passed as the first argument to your arrow function",
    },
    Tip {
        text: "Use `eval --wait-selector <css>` to wait for async-rendered content (React/SPA) before querying the DOM",
    },
    Tip {
        text: "If `eval` returns empty while `htmlsnapshot` finds elements, the page likely loads content asynchronously — use `--wait-selector` or run `wait --selector <css>` first",
    },
];

const TIPS_AI_EXTRACTION: &[Tip] = &[
    Tip {
        text: "Use `--schema` with `extract` for structured JSON output matching your schema definition",
    },
    Tip {
        text: "For high-volume extraction, X-SQL is faster, cheaper, and more predictable than LLM-based extraction",
    },
    Tip {
        text: "Use `summarize --selector <css>` to focus AI summarization on a specific page section",
    },
];

const TIPS_SCROLL: &[Tip] = &[
    Tip {
        text: "Use `wait --load networkidle` after scrolling to ensure infinite-scroll content has loaded",
    },
    Tip {
        text: "Use `scroll down <px>` with incremental values (e.g. 300px) to trigger lazy-loading without overshooting",
    },
];

const TIPS_WAIT: &[Tip] = &[
    Tip {
        text: "Use `wait --load networkidle` instead of fixed `wait <ms>` for reliable page readiness",
    },
    Tip {
        text: "Chain wait conditions: `wait <ref> && wait --load networkidle` for dynamic content that loads after interaction",
    },
    Tip {
        text: "Use `wait --fn \"document.readyState === 'complete'\"` for custom page-ready checks",
    },
    Tip {
        text: "Use `wait --url \"<glob>\"` to wait for SPA route changes after clicking navigation links",
    },
];

const TIPS_SCREENSHOT: &[Tip] = &[
    Tip {
        text: "Use `screenshot [ref]` to capture a specific element — much smaller than full-page captures",
    },
    Tip {
        text: "Use `--filename result.png` for workflow-friendly named output files",
    },
    Tip {
        text: "Combine with `pdf` command for document-ready page captures",
    },
];

const TIPS_NETWORK: &[Tip] = &[
    Tip {
        text: "Use `network requests --status 4xx,5xx` to surface failed API calls after an interaction",
    },
    Tip {
        text: "Use `network request <id>` to inspect the full request/response headers and body of a single request",
    },
    Tip {
        text: "Record a session with `network har start --content text`, then `network har stop ./capture.har` and import it in Chrome DevTools (Network → import)",
    },
    Tip {
        text: "Use `network requests --clear` before an interaction to watch only the requests it triggers",
    },
    Tip {
        text: "Mock API responses with `network route \"**/api/*\" --body '{\"ok\":true}' --content-type application/json`, and restore with `network unroute`",
    },
    Tip {
        text: "Block noisy requests (ads, analytics) with `network route \"**/analytics*\" --abort --resource-type xhr,fetch`",
    },
];

const TIPS_CRAWL: &[Tip] = &[
    Tip {
        text: "Use `--seed-file urls.txt --depth 0` for bulk fetching known URLs without link discovery",
    },
    Tip {
        text: "Use `--sql` (or `--sql @query.sql`) with crawl to extract structured data from every crawled page. Use @url as the page URL placeholder",
    },
    Tip {
        text: "Use `--format csv -o results.csv` with `--sql` to save extracted crawl data directly to a spreadsheet-ready file",
    },
    Tip {
        text: "Use `--out-link-pattern <regex>` to filter which links to follow during crawling",
    },
    Tip {
        text: "Combine `crawl` with `swarm`: crawl discovers URLs, swarm scrapes them in parallel",
    },
    Tip {
        text: "Use `--depth 1` for single-level crawling — discover links from one page only",
    },
    Tip {
        text: "Use `--parallel 8` to collect up to 8 pages at once, each on its own browser tab; the crawl reports the peak overlap it actually achieved",
    },
    Tip {
        text: "Use `--parallel 1` when a site rate-limits you — it restores the strictly sequential crawl",
    },
];

const TIPS_SWARM: &[Tip] = &[
    Tip {
        text: "Use `--max-browser-contexts 3` to control parallelism in swarm operations",
    },
    Tip {
        text: "Use `--seed-file urls.txt` with `swarm submit` for bulk URL processing",
    },
    Tip {
        text: "Use `swarm query` with X-SQL to run the same structured extraction across all swarm results",
    },
    Tip {
        text: "Every `swarm submit` gets a batch id — check that submission later with `swarm list --batch <id>` (add `--status failed` for just the failures)",
    },
    Tip {
        text: "Use `--display-mode HEADLESS` for swarm operations to reduce resource usage",
    },
];

const TIPS_AGENT: &[Tip] = &[
    Tip {
        text: "Use `extract` or X-SQL for data extraction instead of `agent run` — faster and more predictable",
    },
    Tip {
        text: "Use `agent status` and `agent result` to poll async agent tasks",
    },
    Tip {
        text: "Use `agent run --wait --wait-timeout <seconds>` to block for a result — long browsing tasks may need more than the 600s default",
    },
    Tip {
        text: "`agent run` tasks keep a progressive memory — repeated tasks reuse past selectors and blockers (see the `## Memory` section in the task prompt)",
    },
];

const TIPS_WEBMINER: &[Tip] = &[
    Tip {
        text: "webminer clusters downloaded HTML pages into interactive views — start with `webminer install`, then `webminer run-example`",
    },
    Tip {
        text: "Use `webminer all <html-dir>` to run the full pipeline (encode → cluster → views) with cluster counts auto-detected",
    },
    Tip {
        text: "Export pages to HTML first (e.g. `htmlsnapshot export`) — webminer works offline on local HTML files",
    },
    Tip {
        text: "Rebuild views next to your clustered results with `webminer views <html-dir>-ml-output/kmeans-result/p<timestamp>`",
    },
];

const TIPS_ATTACH: &[Tip] = &[
    Tip {
        text: "Use `attach --cdp chrome` to connect to your regular Chrome with all your logins intact",
    },
    Tip {
        text: "Use `attach --cdp msedge` to connect to Microsoft Edge via CDP",
    },
];

const TIPS_COOKIE: &[Tip] = &[
    Tip {
        text: "Use `state-save` and `state-load` to persist/restore cookies and localStorage across sessions",
    },
    Tip {
        text: "Use `cookie-list` to inspect all cookies set by the current page",
    },
];

const TIPS_SELECT: &[Tip] = &[
    Tip {
        text: "Use `select <ref> \"<value>\"` for dropdowns — value matches option text or value attribute",
    },
    Tip {
        text: "Use `--verify` with `select` to confirm the selected value actually changed",
    },
];

const TIPS_INSPECT: &[Tip] = &[
    Tip {
        text: "Use `htmlsnapshot inspect [selector] --max 20` to analyze DOM patterns across multiple elements",
    },
    Tip {
        text: "Use `htmlsnapshot inspect` without arguments to get a structural overview of the entire page",
    },
    Tip {
        text: "After inspect, use `htmlsnapshot get attr \"img[src]\" src` to extract image URLs, or `\"a[href]\" href` for links",
    },
    Tip {
        text: "Use `:expr(width > 200 && height > 200)` in selectors to filter elements by size — perfect for finding large hero images",
    },
];

const TIPS_TABS: &[Tip] = &[
    Tip {
        text: "Use `tab-select <index>` or `tab-select --guid <guid>` to switch between tabs — then re-snapshot to get fresh refs for the new tab",
    },
    Tip {
        text: "Use `tab-new [url]` to open a page in a new tab without losing the current page state",
    },
    Tip {
        text: "Use `tab-close [index]` or `tab-close --guid <guid>` to close a tab by its index or stable GUID from tab-list output",
    },
    Tip {
        text: "Use `tab-list` to see all tabs with their index, GUID, title, and URL — the GUID is stable even when tabs are reordered",
    },
];

const TIPS_FRAME: &[Tip] = &[
    Tip {
        text: "Use `frame <iframe-selector>` to switch into an iframe — click/fill/type/isVisible then resolve inside it (e.g. `frame \"#pay-frame\"` then `fill \"#card-number\" ...`)",
    },
    Tip {
        text: "Use `frames` to list the page frame tree (names + URLs) and find the frame you need to switch into",
    },
    Tip {
        text: "After `frame`, use `frame main` to return to the main document — navigation (goto/open) resets the frame scope automatically",
    },
    Tip {
        text: "`frame` also accepts a frame name, frame id, or URL fragment from `frames` output — not just CSS selectors",
    },
];

const TIPS_STORAGE: &[Tip] = &[
    Tip {
        text: "Use `localstorage-get` and `sessionstorage-get` to extract client-side stored data",
    },
    Tip {
        text: "Use `state-save` before and `state-load` after to checkpoint browser state across sessions",
    },
];

const TIPS_HTMLSNAPSHOT_GREP: &[Tip] = &[
    Tip {
        text: "Use `htmlsnapshot grep -i <pattern>` for case-insensitive search across the DOM HTML",
    },
    Tip {
        text: "Use `htmlsnapshot grep -C 3 <pattern>` for context lines around matches",
    },
    Tip {
        text: "Use `htmlsnapshot grep --selector main <pattern>` to search within the first matching CSS element (querySelector). Use --selector-all to search across ALL matching elements (querySelectorAll).",
    },
    Tip {
        text: "Use `htmlsnapshot grep --selector-all \".product-card\" <pattern>` to search within every matching element, each annotated with its index",
    },
];

// ---------------------------------------------------------------------------
// General tips (shown after commands without specific tips, or mixed in)
// ---------------------------------------------------------------------------

const TIPS_GENERAL: &[Tip] = &[
    Tip {
        text: "Use named sessions (`-s <name>`) to isolate different tasks with separate browser state",
    },
    Tip {
        text: "Use `batch` for multi-step workflows — executes commands sequentially with state sharing",
    },
    Tip {
        text: "Use `loop --times 10 --interval 5s <command>` for repeated task execution with pacing",
    },
    Tip {
        text: "Use `htmlsnapshot query --sql` (X-SQL) for structured data extraction — no JavaScript quoting pain",
    },
    Tip {
        text: "Combine `snapshot -v 0`, `htmlsnapshot get`, and `eval --json` for a complete extraction pipeline",
    },
    Tip {
        text: "Use `--server <url>` to target a remote Browser4 server for distributed scraping",
    },
    Tip {
        text: "Use `list` to see all active sessions and their current page URLs at a glance",
    },
    Tip {
        text: "Use `doctor` to diagnose Chrome, Java, and network issues when things aren't working",
    },
    Tip {
        text: "Set BROWSER4_CLI_STATE_DIR to a writable path in sandboxed environments — the CLI falls back to ./.browser4-cli-state when ~/.browser4 is not writable",
    },
];

// ---------------------------------------------------------------------------
// Counter for tip rotation (per-process, not persisted)
// ---------------------------------------------------------------------------

thread_local! {
    static TIP_COUNTER: Cell<usize> = Cell::new(0);
}

// ---------------------------------------------------------------------------
// Map command name to its tip set
// ---------------------------------------------------------------------------

fn tips_for_command(command: &str) -> &'static [Tip] {
    match command {
        "snapshot" | "snapshot-grep" => TIPS_SNAPSHOT,
        "htmlsnapshot" | "htmlsnapshot-export" => TIPS_HTMLSNAPSHOT_GET,
        "htmlsnapshot-get" => TIPS_HTMLSNAPSHOT_GET,
        "htmlsnapshot-query" => TIPS_HTMLSNAPSHOT_QUERY,
        "htmlsnapshot-grep" => TIPS_HTMLSNAPSHOT_GREP,
        "htmlsnapshot-inspect" => TIPS_INSPECT,
        "htmlsnapshot-summary" => TIPS_INSPECT,
        "htmlsnapshot-readability" => TIPS_READABILITY,
        "click" | "dblclick" | "hover" | "fill" | "type" | "press" | "check" | "uncheck"
        | "drag" | "keydown" | "keyup" => TIPS_INTERACTION,
        "goto" | "open" | "go-back" | "go-forward" | "reload" => TIPS_NAVIGATION,
        "eval" => TIPS_EVAL,
        "extract" | "summarize" => TIPS_AI_EXTRACTION,
        "scroll" | "scroll-to" => TIPS_SCROLL,
        "wait" => TIPS_WAIT,
        "screenshot" | "pdf" => TIPS_SCREENSHOT,
        "crawl" | "crawl-status" | "crawl-result" | "crawl-cancel" | "crawl-clear"
        | "crawl-list" => TIPS_CRAWL,
        "swarm-create" | "swarm-submit" | "swarm-query" | "swarm-status" | "swarm-result"
        | "swarm-list" | "swarm-close" => TIPS_SWARM,
        "agent-run" | "agent-status" | "agent-result" | "agent-list" => TIPS_AGENT,
        "webminer" | "webminer-install" | "webminer-update" | "webminer-version"
        | "webminer-uninstall" | "webminer-run-example" | "webminer-all"
        | "webminer-views" => TIPS_WEBMINER,
        "attach" => TIPS_ATTACH,
        "network-requests" | "network-request" | "network-route" | "network-unroute"
        | "har-start" | "har-stop" => TIPS_NETWORK,
        "cookie-list" | "cookie-get" | "cookie-set" | "cookie-delete" | "cookie-clear" => {
            TIPS_COOKIE
        }
        "select" => TIPS_SELECT,
        "tab-list" | "tab-new" | "tab-close" | "tab-select" => TIPS_TABS,
        "frame" | "frames" => TIPS_FRAME,
        "localstorage-list"
        | "localstorage-get"
        | "localstorage-set"
        | "localstorage-delete"
        | "localstorage-clear"
        | "sessionstorage-list"
        | "sessionstorage-get"
        | "sessionstorage-set"
        | "sessionstorage-delete"
        | "sessionstorage-clear" => TIPS_STORAGE,
        "state-save" | "state-load" => TIPS_STORAGE,
        "mousemove" | "mousedown" | "mouseup" | "mousewheel" => TIPS_INTERACTION,
        "get" => TIPS_HTMLSNAPSHOT_GET,
        "generate-locator" => TIPS_INTERACTION,
        _ => TIPS_GENERAL,
    }
}

/// Commands for which tips should never be shown (infrastructure / meta commands).
fn is_suppressed_command(command: &str) -> bool {
    matches!(
        command,
        "help"
            | "version"
            | "batch"
            | "loop"
            | "install"
            | "uninstall"
            | "upgrade"
            | "doctor"
            | "doctor-log"
            | "doctor-metrics"
            | "doctor-status"
            | "stop"
            | "status"
            | "kill-all"
            | "close-all"
            | "close"
            | "delete-data"
            | "list"
    )
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/// Show a relevant tip on stderr for the given command.
///
/// Tips are **suppressed by default**.  They are only shown when the user
/// explicitly passes the `--show-tip` / `-tip` global flag.
///
/// Additionally suppressed when:
/// - The command is an infrastructure/meta command
/// - `--json` output mode is active
/// - `--raw` / `--stdout` output mode is active
/// - `--quiet` mode is active
///
/// The function is a no-op in all of those cases.
pub fn show_tip(command: &str) {
    // Tips are suppressed by default — only show when --show-tip / -tip is active.
    if !crate::show_tip_active() {
        return;
    }

    // Suppress for infrastructure commands
    if is_suppressed_command(command) {
        return;
    }

    // Suppress in machine-readable output modes (--json, --raw, --stdout)
    // or quiet mode.  These checks must match the `quiet_active()` /
    // `json_active()` / `raw_active()` functions in main.rs.  We duplicate
    // the check here to keep the tips module self-contained.
    if crate::quiet_active() || crate::json_active() || crate::raw_active() {
        return;
    }

    let tips = tips_for_command(command);
    if tips.is_empty() {
        return;
    }

    // Rotate through tips using an incrementing counter
    let index = TIP_COUNTER.with(|counter| {
        let val = counter.get();
        counter.set(val.wrapping_add(1));
        val % tips.len()
    });

    eprintln!("\n💡 Tip: {}", tips[index].text);
}

// ---------------------------------------------------------------------------
// Failure tips — actionable remediation for a rejected tool call
// ---------------------------------------------------------------------------
//
// The rotating tips above are discovery hints and stay opt-in (`--show-tip`).
// A failure tip is different: the call was rejected and the user has to act
// before anything else works, so it is shown whenever the command fails with a
// code the CLI can actually advise on — and never for a successful call.

/// Read the stable `[CODE]` token the backend writes into a failure message
/// (`ERROR: [RATE_LIMITED] …`).
///
/// Only SCREAMING_SNAKE_CASE is accepted, so brackets used for prose in an
/// ordinary message are not mistaken for a code.
fn error_code_from_message(message: &str) -> Option<&str> {
    let rest = message.get(message.find('[')? + 1..)?;
    let code = rest.get(..rest.find(']')?)?.trim();
    (!code.is_empty() && code.chars().all(|c| c.is_ascii_uppercase() || c == '_')).then_some(code)
}

/// Parse the retry delay out of a rejection message
/// (`… ; retry after 45 ms`), for backends that report it in prose only.
fn parse_retry_after_ms(message: &str) -> Option<u64> {
    let lower = message.to_ascii_lowercase();
    let rest = lower.get(lower.find("retry after")? + "retry after".len()..)?;
    rest.trim_start()
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>()
        .parse()
        .ok()
}

/// The actionable tip for a failed command, or `None` when the failure has
/// none — a successful result, or a code the CLI cannot remedy (`INTERNAL`,
/// `INVALID_ARGUMENT`, …).
///
/// `error_code` / `retry_after_ms` are the structured fields the backend
/// attached to the rejected call (`http::ToolErrorMeta`); both are `None` when
/// the failure is only visible as text.
fn failure_tip_for(
    command: &str,
    error_message: &str,
    error_code: Option<&str>,
    retry_after_ms: Option<u64>,
) -> Option<String> {
    // The message is authoritative — the backend embeds the code in the text
    // (`ERROR: [RATE_LIMITED] …`); the structured field covers a response whose
    // text carries no token.
    let code = error_code_from_message(error_message).or(error_code)?;
    let name = crate::help::public_command_name(command);

    let tip = match code {
        "RATE_LIMITED" => {
            let wait = retry_after_ms
                .or_else(|| parse_retry_after_ms(error_message))
                .map(|ms| format!("retry after {ms} ms"))
                .unwrap_or_else(|| "retry after a short wait".to_string());
            format!("rate limit exceeded for '{name}' — {wait}, or lower batch concurrency.")
        }
        "SESSION_UNHEALTHY" => format!(
            "the session for '{name}' is unhealthy — re-open the session (the browser may have exited) and retry."
        ),
        "SESSION_NOT_FOUND" => format!(
            "no live session for '{name}' — open one first (e.g. `browser4-cli open <url>`)."
        ),
        // Everything else (INTERNAL, INVALID_ARGUMENT, TIMEOUT, …) has no
        // CLI-side remediation worth a line of noise.
        _ => return None,
    };

    Some(format!("Tip: {tip}"))
}

/// Print the actionable tip for a failed command, when there is one.
///
/// Unlike [show_tip] this is not opt-in — a rejection is remediation the user
/// needs now, not a rotating discovery hint.  It still respects the output-mode
/// suppressions (`--json`, `--quiet`, `--raw`/`--stdout`) and stays silent for
/// infrastructure commands.
pub fn show_failure_tip(
    command: &str,
    error_message: &str,
    error_code: Option<&str>,
    retry_after_ms: Option<u64>,
) {
    if crate::quiet_active() || crate::json_active() || crate::raw_active() {
        return;
    }
    if is_suppressed_command(command) {
        return;
    }
    if let Some(tip) = failure_tip_for(command, error_message, error_code, retry_after_ms) {
        eprintln!("{tip}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tips_for_known_commands() {
        // Snapshot
        assert!(!tips_for_command("snapshot").is_empty());
        // Navigation
        assert!(!tips_for_command("goto").is_empty());
        assert!(!tips_for_command("open").is_empty());
        // Interaction
        assert!(!tips_for_command("click").is_empty());
        assert!(!tips_for_command("fill").is_empty());
        assert!(!tips_for_command("type").is_empty());
        assert!(!tips_for_command("press").is_empty());
        // htmlsnapshot variants
        assert!(!tips_for_command("htmlsnapshot-get").is_empty());
        assert!(!tips_for_command("htmlsnapshot-query").is_empty());
        assert!(!tips_for_command("htmlsnapshot-grep").is_empty());
        assert!(!tips_for_command("htmlsnapshot-export").is_empty());
        assert!(!tips_for_command("htmlsnapshot-inspect").is_empty());
        assert!(!tips_for_command("htmlsnapshot-readability").is_empty());
        // Eval
        assert!(!tips_for_command("eval").is_empty());
        // Frame switching
        assert!(!tips_for_command("frame").is_empty());
        assert!(!tips_for_command("frames").is_empty());
        // AI
        assert!(!tips_for_command("extract").is_empty());
        assert!(!tips_for_command("summarize").is_empty());
        // Other
        assert!(!tips_for_command("screenshot").is_empty());
        assert!(!tips_for_command("crawl").is_empty());
        assert!(!tips_for_command("swarm-submit").is_empty());
        assert!(!tips_for_command("agent-run").is_empty());
        assert!(!tips_for_command("attach").is_empty());
        assert!(!tips_for_command("wait").is_empty());
        assert!(!tips_for_command("scroll").is_empty());
        assert!(!tips_for_command("select").is_empty());
        // General fallback
        assert!(!tips_for_command("some-unknown-command").is_empty());
    }

    #[test]
    fn test_tips_htmlsnapshot_get_includes_xsql_correlation_hint() {
        let has_xsql_tip = TIPS_HTMLSNAPSHOT_GET.iter().any(|t| {
            t.text.contains("correlate multiple fields")
                && t.text.contains("htmlsnapshot query")
                && t.text.contains("x-sql-dom-load-select.md")
        });
        assert!(
            has_xsql_tip,
            "TIPS_HTMLSNAPSHOT_GET should include a tip steering users to X-SQL for correlated multi-field extraction"
        );
    }

    #[test]
    fn test_suppressed_commands() {
        assert!(is_suppressed_command("help"));
        assert!(is_suppressed_command("version"));
        assert!(is_suppressed_command("batch"));
        assert!(is_suppressed_command("loop"));
        assert!(is_suppressed_command("install"));
        assert!(is_suppressed_command("uninstall"));
        assert!(is_suppressed_command("upgrade"));
        assert!(is_suppressed_command("doctor"));
        assert!(is_suppressed_command("stop"));
        assert!(is_suppressed_command("status"));
        assert!(is_suppressed_command("kill-all"));
        assert!(is_suppressed_command("close-all"));
        assert!(is_suppressed_command("list"));
        assert!(is_suppressed_command("close"));
        assert!(is_suppressed_command("delete-data"));
    }

    #[test]
    fn test_not_suppressed_commands() {
        assert!(!is_suppressed_command("goto"));
        assert!(!is_suppressed_command("click"));
        assert!(!is_suppressed_command("snapshot"));
        assert!(!is_suppressed_command("eval"));
        assert!(!is_suppressed_command("htmlsnapshot"));
        assert!(!is_suppressed_command("screenshot"));
    }

    // -----------------------------------------------------------------------
    // Failure tips
    // -----------------------------------------------------------------------

    /// The rejection text the private dispatcher sends: the code in brackets
    /// followed by the rate limiter's message, which repeats the delay.
    const RATE_LIMITED_MESSAGE: &str = "ERROR: [RATE_LIMITED] rate limit exceeded for browser_click \
         (limit 10); retry after 45 ms";

    #[test]
    fn test_error_code_from_message_reads_the_bracketed_code() {
        assert_eq!(error_code_from_message(RATE_LIMITED_MESSAGE), Some("RATE_LIMITED"));
        // The defensive path in http.rs strips the `ERROR: ` prefix but keeps
        // the code token.
        assert_eq!(
            error_code_from_message("[SESSION_NOT_FOUND] No active session"),
            Some("SESSION_NOT_FOUND")
        );
        assert_eq!(error_code_from_message("Clicked element e5"), None);
        assert_eq!(error_code_from_message("failed to parse [see docs]"), None);
    }

    #[test]
    fn test_parse_retry_after_ms() {
        assert_eq!(parse_retry_after_ms(RATE_LIMITED_MESSAGE), Some(45));
        assert_eq!(parse_retry_after_ms("retry after 1200 ms"), Some(1200));
        assert_eq!(parse_retry_after_ms("Retry After 7 ms"), Some(7));
        assert_eq!(parse_retry_after_ms("retry after a while"), None);
        assert_eq!(parse_retry_after_ms("Session not found"), None);
    }

    #[test]
    fn test_failure_tip_rate_limited_with_structured_retry_after() {
        let tip = failure_tip_for(
            "click",
            "ERROR: [RATE_LIMITED] rate limit exceeded for browser_click",
            Some("RATE_LIMITED"),
            Some(45),
        )
        .expect("a RATE_LIMITED failure must produce a tip");
        assert_eq!(
            tip,
            "Tip: rate limit exceeded for 'click' — retry after 45 ms, or lower batch concurrency."
        );
    }

    #[test]
    fn test_failure_tip_rate_limited_without_structured_retry_after() {
        // No structured field: the delay embedded in the message is used.
        let text_only = failure_tip_for("click", RATE_LIMITED_MESSAGE, None, None)
            .expect("a RATE_LIMITED failure must produce a tip");
        assert!(text_only.contains("retry after 45 ms"), "got: {text_only}");

        // Neither source reports a delay: the tip still names the problem.
        let no_delay = failure_tip_for(
            "click",
            "ERROR: [RATE_LIMITED] rate limit exceeded for browser_click (limit 10)",
            None,
            None,
        )
        .expect("a RATE_LIMITED failure must produce a tip");
        assert!(no_delay.contains("rate limit exceeded for 'click'"), "got: {no_delay}");
        assert!(no_delay.contains("retry after a short wait"), "got: {no_delay}");
        assert!(
            !no_delay.contains("retry after 0 ms"),
            "a missing delay must not be rendered as 0 ms: {no_delay}"
        );
    }

    #[test]
    fn test_failure_tip_uses_the_structured_code_when_the_text_has_none() {
        let tip = failure_tip_for("click", "rate limit exceeded", Some("RATE_LIMITED"), Some(300))
            .expect("the structured errorCode alone must be enough");
        assert!(tip.contains("retry after 300 ms"), "got: {tip}");
    }

    #[test]
    fn test_failure_tip_session_codes() {
        let unhealthy = failure_tip_for(
            "goto",
            "ERROR: [SESSION_UNHEALTHY] the browser of session s-1 exited",
            None,
            None,
        )
        .expect("SESSION_UNHEALTHY is actionable");
        assert!(unhealthy.contains("unhealthy") && unhealthy.contains("'goto'"), "got: {unhealthy}");

        let not_found = failure_tip_for(
            "goto",
            "[SESSION_NOT_FOUND] No active session",
            None,
            None,
        )
        .expect("SESSION_NOT_FOUND is actionable");
        assert!(
            not_found.contains("no live session for 'goto'"),
            "got: {not_found}"
        );
    }

    #[test]
    fn test_failure_tip_emits_nothing_for_success_or_unactionable_failures() {
        // A successful call never produces a tip, even when its output happens
        // to mention a failure code in prose.
        assert_eq!(failure_tip_for("click", "Clicked element e5", None, None), None);
        assert_eq!(
            failure_tip_for("click", "Clicked element e5", None, Some(45)),
            None
        );
        // INTERNAL and argument errors have no CLI-side remediation.
        assert_eq!(
            failure_tip_for("click", "ERROR: [INTERNAL] unexpected failure", Some("INTERNAL"), None),
            None
        );
        assert_eq!(
            failure_tip_for(
                "click",
                "ERROR: [INVALID_ARGUMENT] selector must be a string",
                None,
                None
            ),
            None
        );
    }
}
