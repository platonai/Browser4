//! CLI tip system — shows a relevant, rotating tip on stderr after each
//! successful command to remind AI agents of advanced Browser4 capabilities.
//!
//! Tips are suppressed when `--json` or `--quiet` is active, and for
//! infrastructure commands (help, version, install, etc.).

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
        text: "Use `snapshot -v 0` for viewport 0 (top of page) — avoids large snapshots exceeding context limits",
    },
    Tip {
        text: "Use `snapshot grep <pattern>` to search a saved snapshot without re-reading the entire file",
    },
    Tip {
        text: "Use `snapshot -i -d 5` to capture only interactive elements at depth 5",
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
        text: "To correlate multiple fields (titles + prices + URLs) from a list page, use `htmlsnapshot query` with X-SQL — `get all` arrays can't be aligned across independent calls. See skill/references/x-sql-dom-load-select.md",
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
];

const TIPS_HTMLSNAPSHOT_QUERY: &[Tip] = &[
    Tip {
        text: "X-SQL has ~200 functions across DOM_*, STR_*, and ARRAY_* namespaces — see skill/references/x-sql.md",
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
        text: "Use `press Enter` after `fill` to submit forms — `fill` alone does not trigger submission",
    },
    Tip {
        text: "Use `generate-locator <ref>` to get a robust CSS selector from a snapshot ref for repeated use",
    },
];

const TIPS_NAVIGATION: &[Tip] = &[
    Tip {
        text: "After navigation, capture with `snapshot -v 0` to get the top viewport with element refs",
    },
    Tip {
        text: "Use load options in the URL: `goto \"url -expires 1d -refresh\"` for caching control",
    },
    Tip {
        text: "Use `--headed` flag to see the browser window — invaluable for debugging automation issues",
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
        text: "For structured data extraction without quoting pain, consider X-SQL: `htmlsnapshot query --sql \"...\"`",
    },
    Tip {
        text: "Use `eval` with a `[ref]` argument to scope JS execution to a specific element: `eval \"this.textContent\" e5`",
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
];

const TIPS_TABS: &[Tip] = &[
    Tip {
        text: "Use `tab-select <index>` to switch between tabs — then re-snapshot to get fresh refs for the new tab",
    },
    Tip {
        text: "Use `tab-new [url]` to open a page in a new tab without losing the current page state",
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
        text: "Use `htmlsnapshot grep --selector main <pattern>` to search only within a CSS selector subtree",
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
        "htmlsnapshot" => TIPS_HTMLSNAPSHOT_GET,
        "htmlsnapshot-get" => TIPS_HTMLSNAPSHOT_GET,
        "htmlsnapshot-query" => TIPS_HTMLSNAPSHOT_QUERY,
        "htmlsnapshot-grep" => TIPS_HTMLSNAPSHOT_GREP,
        "htmlsnapshot-inspect" => TIPS_INSPECT,
        "htmlsnapshot-summary" => TIPS_INSPECT,
        "click" | "dblclick" | "hover" | "fill" | "type" | "press" | "check" | "uncheck"
        | "drag" | "keydown" | "keyup" => TIPS_INTERACTION,
        "goto" | "open" | "go-back" | "go-forward" | "reload" => TIPS_NAVIGATION,
        "eval" => TIPS_EVAL,
        "extract" | "summarize" => TIPS_AI_EXTRACTION,
        "scroll" | "scroll-to" => TIPS_SCROLL,
        "wait" => TIPS_WAIT,
        "screenshot" | "pdf" => TIPS_SCREENSHOT,
        "crawl" | "crawl-list" => TIPS_CRAWL,
        "swarm-create" | "swarm-submit" | "swarm-query" | "swarm-status" | "swarm-result"
        | "swarm-list" => TIPS_SWARM,
        "agent-run" | "agent-status" | "agent-result" | "agent-list" => TIPS_AGENT,
        "attach" => TIPS_ATTACH,
        "cookie-list" | "cookie-get" | "cookie-set" | "cookie-delete" | "cookie-clear" => {
            TIPS_COOKIE
        }
        "select" => TIPS_SELECT,
        "tab-list" | "tab-new" | "tab-close" | "tab-select" => TIPS_TABS,
        "localstorage-list" | "localstorage-get" | "localstorage-set" | "localstorage-delete"
        | "localstorage-clear" | "sessionstorage-list" | "sessionstorage-get"
        | "sessionstorage-set" | "sessionstorage-delete" | "sessionstorage-clear" => TIPS_STORAGE,
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
/// Suppressed when:
/// - The command is an infrastructure/meta command
/// - `--json` output mode is active
/// - `--raw` / `--stdout` output mode is active
/// - `--quiet` mode is active
///
/// The function is a no-op in all of those cases.
pub fn show_tip(command: &str) {
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
        assert!(!tips_for_command("htmlsnapshot-inspect").is_empty());
        // Eval
        assert!(!tips_for_command("eval").is_empty());
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
}
