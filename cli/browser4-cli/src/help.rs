//! Help text generation for the Browser4 CLI.

use crate::commands::{all_commands, CommandDef};

/// Maximum characters per line in help output.
const MAX_LINE_WIDTH: usize = 120;

pub fn public_command_name(name: &str) -> &str {
    match name {
        "agent-run" => "agent run",
        "agent-status" => "agent status",
        "agent-result" => "agent result",
        "agent-list" => "agent list",
        "swarm-create" => "swarm create",
        "swarm-submit" => "swarm submit",
        "swarm-query" => "swarm query",
        "swarm-status" => "swarm status",
        "swarm-result" => "swarm result",
        "swarm-list" => "swarm list",
        "swarm-close" => "swarm close",
        "crawl-status" => "crawl status",
        "crawl-result" => "crawl result",
        "crawl-cancel" => "crawl cancel",
        "crawl-clear" => "crawl clear",
        "crawl-list" => "crawl list",
        "htmlsnapshot-capture" => "htmlsnapshot capture",
        "htmlsnapshot-get" => "htmlsnapshot get",
        "htmlsnapshot-get-all" => "htmlsnapshot get all",
        "htmlsnapshot-query" => "htmlsnapshot query",
        "htmlsnapshot-export" => "htmlsnapshot export",
        "htmlsnapshot-summary" => "htmlsnapshot summary",
        "htmlsnapshot-grep" => "htmlsnapshot grep",
        "htmlsnapshot-inspect" => "htmlsnapshot inspect",
        "snapshot-grep" => "snapshot grep",
        "webdb-export" => "webdb export",
        "webdb-normalize" => "webdb normalize",
        "doctor-log" => "doctor log",
        "doctor-metrics" => "doctor metrics",
        "plugin-list" => "plugin list",
        "plugin-info" => "plugin info",
        "plugin-install" => "plugin install",
        "plugin-remove" => "plugin remove",
        _ => name,
    }
}

/// Categories in display order with their titles.
/// Public so that `print_help` in main.rs can look up titles for category-filtered help.
pub const CATEGORY_TITLES: &[(&str, &str)] = &[
    ("core", "Core"),
    ("navigation", "Navigation"),
    ("keyboard", "Keyboard"),
    ("mouse", "Mouse"),
    ("export", "Capture"),
    ("tabs", "Tabs"),
    ("storage", "Storage"),
    ("devtools", "DevTools"),
    ("snapshot", "HTML Snapshot (htmlsnapshot)"),
    ("agent", "Agent"),
    ("act", "Act"),
    ("swarm", "Swarm"),
    ("install", "Install"),
    ("browsers", "Browser sessions"),
    ("skills", "Skills"),
    ("plugins", "Plugins"),
];

/// Short aliases for category-based help filtering.
/// e.g. `browser4-cli --help nav` shows Navigation commands.
const CATEGORY_ALIASES: &[(&str, &str)] = &[
    ("nav", "navigation"),
    ("kb", "keyboard"),
    ("input", "keyboard"),
    ("extract", "snapshot"),
    ("extraction", "snapshot"),
    ("data", "snapshot"),
    ("session", "browsers"),
    ("sessions", "browsers"),
    ("cap", "export"),
    ("capture", "export"),
    ("ss", "snapshot"),
    ("state", "storage"),
    ("skill", "skills"),
    ("plugin", "plugins"),
    ("swarm", "swarm"),
    ("crawl", "swarm"),
];

/// Resolve a category alias to its canonical category name, or return the
/// input unchanged if it's already a canonical name or not recognized.
pub fn resolve_category_alias(alias: &str) -> Option<&'static str> {
    let lower = alias.to_lowercase();
    // Check aliases first
    for (a, canonical) in CATEGORY_ALIASES {
        if *a == lower.as_str() {
            return Some(canonical);
        }
    }
    // Check if it's already a canonical name
    for (canonical, _title) in CATEGORY_TITLES {
        if *canonical == lower.as_str() {
            return Some(canonical);
        }
    }
    None
}

/// Return all non-hidden commands belonging to a category.
pub fn commands_in_category(category_name: &str) -> Vec<CommandDef> {
    let cmds = all_commands();
    cmds.into_iter()
        .filter(|c| !c.hidden && c.category.as_str() == category_name)
        .collect()
}

/// Generate global help text listing all available commands by category.
pub fn generate_help() -> String {
    let cmds = all_commands();
    let mut lines: Vec<String> = vec![
        "Usage: browser4-cli <command> [args] [options]".to_string(),
        "Usage: browser4-cli -s <session> <command> [args] [options]".to_string(),
    ];

    // Common workflows — show the 5 most common patterns
    lines.push("\nCommon workflows:".to_string());
    lines.push("  Navigate & inspect:".to_string());
    lines.push("    goto <url>  →  snapshot -v 0  →  click <ref>  →  snapshot -v 0    # -v 0 = top-of-page chunk".to_string());
    lines.push("  Extract data:".to_string());
    lines.push("    htmlsnapshot get text \"<css>\"           # single field".to_string());
    lines.push("    htmlsnapshot query --sql @query.sql       # structured extraction".to_string());
    lines.push("  Form interaction:".to_string());
    lines.push("    fill <ref> \"<text>\" --submit              # fill + press Enter".to_string());
    lines.push("  Run JavaScript:".to_string());
    lines.push("    eval --file script.js                     # read JS from file (no quoting issues)".to_string());
    lines.push("  Bulk crawl:".to_string());
    lines.push("    crawl <url> --out-link-selector \"...\" --depth 1 --sql @query.sql".to_string());
    lines.push("\nFilter help by category:  --help nav | --help extract | --help session | --help kb | --help swarm | --help crawl".to_string());

    let mut first_category = true;
    for (cat_name, cat_title) in CATEGORY_TITLES {
        let cat_cmds: Vec<&CommandDef> = cmds
            .iter()
            .filter(|c| !c.hidden && c.category.as_str() == *cat_name)
            .collect();
        if cat_cmds.is_empty() {
            continue;
        }
        if !first_category {
            lines.push("\n  ---".to_string());
        }
        first_category = false;
        lines.push(format!("\n{}:", cat_title));
        for cmd in cat_cmds {
            lines.push(generate_help_entry(cmd));
        }
    }

    lines.push("\nGlobal options:".to_string());
    lines.push(format_with_gap(
        "  --help [cmd|category]",
        "print help (try: nav, extract, session, kb)",
        30,
    ));
    lines.push(format_with_gap("  --version", "print version", 30));
    lines.push(format_with_gap(
        "  --json",
        "emit JSON to stdout only (suppresses tips, hints, and human-readable text)",
        30,
    ));
    lines.push(format_with_gap(
        "  -q, --quiet",
        "suppress normal output, only show errors",
        30,
    ));
    lines.push(format_with_gap(
        "  -tip, --show-tip",
        "show a relevant tip on stderr after each command",
        30,
    ));
    lines.push(format_with_gap("  -s <name>", "named session label", 30));
    lines.push(format_with_gap(
        "  --server <url>",
        "override Browser4 server URL",
        30,
    ));

    // for developer only
    // lines.push(format_with_gap(
    //     "  --use-maven-startup",
    //     "opt in to local maven spring-boot:run startup",
    //     30,
    // ));

    lines.push(String::new());
    lines.push(
        "Run `browser4-cli help <command>` or `<command> --help` for detailed options and examples."
            .to_string(),
    );

    lines.join("\n")
}

/// Generate per-command help text.
pub fn generate_command_help(cmd: &CommandDef) -> String {
    let args_text = cmd
        .args
        .iter()
        .map(|a| {
            if a.optional {
                format!("[{}]", a.name)
            } else {
                format!("<{}>", a.name)
            }
        })
        .collect::<Vec<_>>()
        .join(" ");

    let mut lines: Vec<String> = vec![
        format!(
            "browser4-cli {} {}",
            public_command_name(cmd.name),
            args_text
        )
        .trim()
        .to_string(),
        String::new(),
        wrap_text(cmd.description, "", 0),
        String::new(),
    ];

    if !cmd.args.is_empty() {
        lines.push("Arguments:".to_string());
        for arg in cmd.args {
            let label = if arg.optional {
                format!("  [{}]", arg.name)
            } else {
                format!("  <{}>", arg.name)
            };
            lines.push(format_with_gap(&label, arg.description, 30));
        }
    }

    if !cmd.options.is_empty() {
        lines.push("Options:".to_string());
        for opt in cmd.options {
            let label = match opt.short {
                Some(s) => format!("  -{}, --{}", s, opt.name),
                None => format!("  --{}", opt.name),
            };
            lines.push(format_with_gap(&label, opt.description, 30));
        }
    }

    if cmd.name == "batch" {
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push("  - Quote each subcommand so it is parsed as one batch item.".to_string());
        lines.push("  - Use --bail to stop execution on the first failed subcommand.".to_string());
        lines.push("  - Use --json to read command arrays from stdin JSON payload.".to_string());
        lines.push(
            "  - Batch mode only supports DOM operations (navigation, keyboard, mouse,".to_string(),
        );
        lines.push(
            "    core interactions, export, and tabs). Session lifecycle commands".to_string(),
        );
        lines.push("    (open, close) must be executed separately.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli batch \"goto https://browser4.io\" \"snapshot\"".to_string());
        lines.push(
            "  browser4-cli batch --bail \"goto https://browser4.io\" \"click e1\" \"screenshot\""
                .to_string(),
        );
        lines.push(
            "  echo '[ [\"goto\", \"https://browser4.io\"], [\"snapshot\"] ]' | browser4-cli batch --json"
                .to_string(),
        );
    }

    if cmd.name == "eval" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Prefer --file or --stdin for complex expressions — they avoid shell quoting"
                .to_string(),
        );
        lines.push(
            "    issues on Windows (multiple layers of Bash → cargo → CLI → JS escaping)."
                .to_string(),
        );
        lines.push(
            "  - Use --base64 for short inline expressions that contain quotes."
                .to_string(),
        );
        lines.push(
            "  - Use --await for async JavaScript (fetch, Promises)."
                .to_string(),
        );
        lines.push(
            "  - Return values are always printed: `null` for JS null/undefined, `\"\"` for empty string,"
                .to_string(),
        );
        lines.push(
            "    or the value itself otherwise. JS exceptions are surfaced as errors."
                .to_string(),
        );
        lines.push(
            "  - Objects and arrays are serialized as valid JSON. Use --json to JSON-wrap"
                .to_string(),
        );
        lines.push(
            "    scalar results (strings get quoted, numbers/booleans/null pass through)."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  # Primary (recommended): read JavaScript from a file — no shell quoting needed".to_string());
        lines.push("  browser4-cli eval --file script.js".to_string());
        lines.push("  browser4-cli eval --file script.js e5".to_string());
        lines.push(
            "  # Pipe from stdin — ideal for heredocs and one-liners with complex quoting:".to_string(),
        );
        lines.push("  browser4-cli eval --stdin << 'EOF'".to_string());
        lines.push("  document.querySelector('a[href*=\"jobs\"]')?.textContent".to_string());
        lines.push("  EOF".to_string());
        lines.push("  echo 'document.title' | browser4-cli eval --stdin".to_string());
        lines.push("  # Base64 (inline, avoids quoting):".to_string());
        lines.push("  browser4-cli eval --base64 ZG9jdW1lbnQudGl0bGU=".to_string());
        lines.push("  # Inline (simple expressions only — avoid on Windows with complex JS):".to_string());
        lines.push("  browser4-cli eval \"document.title\"".to_string());
        lines.push("  browser4-cli eval --json \"document.title\"".to_string());
    }

    if cmd.name == "htmlsnapshot-query" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Use --format table for human-readable output (json, csv, or table).".to_string(),
        );
        lines.push(
            "  - Use --result-only to extract just the resultSet array, omitting wrapper metadata."
                .to_string(),
        );
        lines.push(
            "  - Use --sql @file.sql to avoid shell quoting issues on Windows.".to_string(),
        );
        lines.push(
            "  - Use --sql-stdin or --sql-base64 to avoid shell quoting issues with inline SQL."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push(wrap_text(
            "browser4-cli htmlsnapshot query --sql \"SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, ':root')\"",
            "  ",
            2,
        ));
        lines.push("  browser4-cli htmlsnapshot query --sql @query.sql".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql-stdin < query.sql".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql-base64 \"$(base64 -w0 query.sql)\"".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql @query.sql --result-only".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql @query.sql --format table".to_string());
    }

    if cmd.name == "wait" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Five wait modes are supported, selected by which option or positional argument is provided:"
                .to_string(),
        );
        lines.push(
            "    • selector — wait for an element matching a CSS selector or snapshot ref (e.g. e1) to appear"
                .to_string(),
        );
        lines.push(
            "    • time — wait a fixed number of milliseconds (numeric target argument)"
                .to_string(),
        );
        lines.push(
            "    • text — wait until the given text appears anywhere in the page body"
                .to_string(),
        );
        lines.push(
            "    • url — wait until the page URL matches a glob pattern"
                .to_string(),
        );
        lines.push(
            "    • load — wait for a page-load state: domcontentloaded, load, or networkidle"
                .to_string(),
        );
        lines.push(
            "    • fn — wait until a custom JavaScript expression returns true"
                .to_string(),
        );
        lines.push(
            "  - --timeout sets the maximum wait time in milliseconds (default: 30000)."
                .to_string(),
        );
        lines.push(wrap_text(
            "The target positional argument is interpreted as milliseconds when numeric, otherwise as a CSS selector or element ref.",
            "  - ",
            4,
        ));
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli wait e1".to_string());
        lines.push("  browser4-cli wait 2000".to_string());
        lines.push("  browser4-cli wait --text \"Success\"".to_string());
        lines.push("  browser4-cli wait --url \"**/dashboard\"".to_string());
        lines.push("  browser4-cli wait --load networkidle".to_string());
        lines.push("  browser4-cli wait --fn \"document.querySelector('.loaded') !== null\"".to_string());
        lines.push("  browser4-cli wait --text \"Ready\" --timeout 60000".to_string());
    }

    if cmd.name == "get" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Extracts data from a page element in one of six modes: text, html, box, styles, property, or attr."
                .to_string(),
        );
        lines.push(
            "  - text — returns the visible inner text of the first matching element."
                .to_string(),
        );
        lines.push(
            "  - html — returns the inner HTML of the first matching element."
                .to_string(),
        );
        lines.push(
            "  - box — returns the bounding box {x, y, width, height} of the first matching element."
                .to_string(),
        );
        lines.push(
            "  - styles — returns all computed CSS styles as a key-value object."
                .to_string(),
        );
        lines.push(
            "  - property — returns a DOM property value (requires the name argument)."
                .to_string(),
        );
        lines.push(
            "  - attr — returns an HTML attribute value (requires the name argument)."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli get text \".price\"".to_string());
        lines.push("  browser4-cli get html e3".to_string());
        lines.push("  browser4-cli get box \"#header\"".to_string());
        lines.push("  browser4-cli get styles e9".to_string());
        lines.push("  browser4-cli get property \"input[name=email]\" value".to_string());
        lines.push("  browser4-cli get attr \"a.link\" href".to_string());
    }

    if cmd.name == "extract" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Uses AI to extract structured data from the current page based on a natural-language instruction."
                .to_string(),
        );
        lines.push(
            "  - --schema accepts a JSON schema to constrain the extracted data structure."
                .to_string(),
        );
        lines.push(wrap_text(
            "Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead, or --filename to specify a custom path.",
            "  - ",
            4,
        ));
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli extract \"product name, price, ratings\"".to_string());
        lines.push(wrap_text(
            "browser4-cli extract \"all contact info\" --schema '{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"email\":{\"type\":\"string\"}}}'",
            "  ",
            2,
        ));
        lines.push("  browser4-cli extract \"article titles and dates\" --stdout".to_string());
        lines.push("  browser4-cli extract \"page metadata\" --filename meta.json".to_string());
    }

    if cmd.name == "summarize" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Uses AI to generate a natural-language summary of the current page content."
                .to_string(),
        );
        lines.push(wrap_text(
            "An optional instruction tells the model what to focus on, e.g. 'summarize the product reviews' or 'give me the key takeaways'.",
            "  - ",
            4,
        ));
        lines.push(
            "  - --selector limits summarization to a specific CSS selector (e.g. '#main-content', '.article-body')."
                .to_string(),
        );
        lines.push(wrap_text(
            "Output is saved to a timestamped file by default. Use --stdout (or --raw) to print to stdout instead, or --filename to specify a custom path.",
            "  - ",
            4,
        ));
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli summarize".to_string());
        lines.push("  browser4-cli summarize \"key takeaways from this article\"".to_string());
        lines.push("  browser4-cli summarize \"summarize the product reviews\" --selector \"#reviews\"".to_string());
        lines.push("  browser4-cli summarize --stdout".to_string());
        lines.push("  browser4-cli summarize --filename summary.txt".to_string());
    }

    if cmd.name == "agent-run" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Agent tasks are available only via the spaced `agent <subcommand>` form."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push(
            "  browser4-cli agent run \"Open browser4.io and summarize the hero section\""
                .to_string(),
        );
    }

    if cmd.name == "agent-status" {
        lines.push("Notes:".to_string());
        lines.push("  - Accepts the task ID returned by `agent run`.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli agent status agent-task-1".to_string());
    }

    if cmd.name == "agent-result" {
        lines.push("Notes:".to_string());
        lines.push("  - Accepts the task ID returned by `agent run`.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli agent result agent-task-1".to_string());
    }

    if cmd.name == "agent-list" {
        lines.push("Notes:".to_string());
        lines.push("  - Lists all tracked agent tasks and their status.".to_string());
        lines.push("  - Tasks are ordered by submission time (latest first).".to_string());
        lines.push("  - Use `--limit N` to show at most N tasks and `--offset N` to skip the first N.".to_string());
        lines.push("  - Use `--clear` to remove all tracked agent tasks from the list.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli agent list".to_string());
        lines.push("  browser4-cli agent list --limit 20".to_string());
        lines.push("  browser4-cli agent list --clear".to_string());
    }

    if cmd.name == "act" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Accepts natural language descriptions of browser actions."
                .to_string(),
        );
        lines.push(
            "  - The description is automatically translated to a browser4-cli command using AI."
                .to_string(),
        );
        lines.push(
            "  - Executes the command immediately against your current browser session."
                .to_string(),
        );
        lines.push(
            "  - Requires an LLM provider to be configured on the Browser4 server."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli act \"scroll by 200px\"".to_string());
        lines.push("  browser4-cli act \"go to https://example.com\"".to_string());
        lines.push("  browser4-cli act \"click the search button and type hello\"".to_string());
        lines.push("  browser4-cli act \"take a screenshot and save it as my-screen.png\"".to_string());
    }

    if cmd.name == "attach" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Attach to an already-running browser via CDP (Chrome DevTools Protocol) endpoint or channel name."
                .to_string(),
        );
        lines.push(
            "  - --cdp accepts a CDP endpoint URL (e.g. http://localhost:9222) or a browser channel name."
                .to_string(),
        );
        lines.push(
            "    Supported channels: chrome, chrome-beta, chrome-dev, chrome-canary,"
                .to_string(),
        );
        lines.push(
            "                        msedge, msedge-beta, msedge-dev, msedge-canary"
                .to_string(),
        );
        lines.push(
            "  - --endpoint accepts a remote Browser4 server URL (e.g. http://browser4-server:8182) for distributed setups."
                .to_string(),
        );
        lines.push(
            "  - When attaching, the current session slot is associated with the external browser."
                .to_string(),
        );
        lines.push(
            "  - --extension connects via the Browser4 Chrome Extension (browser must have the extension installed)."
                .to_string(),
        );
        lines.push(
            "    Supported channels: chrome (default), chrome-canary, msedge, msedge-dev."
                .to_string(),
        );
        lines.push(
            "    Note: Navigating to chrome:// internal pages (chrome://version, chrome://settings, etc.)"
                .to_string(),
        );
        lines.push(
            "    may disconnect the extension. Re-attach with `attach --extension` if this occurs."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli attach --cdp http://localhost:9222".to_string());
        lines.push("  browser4-cli attach --cdp chrome".to_string());
        lines.push("  browser4-cli attach --cdp msedge".to_string());
        lines.push("  browser4-cli attach --extension".to_string());
        lines.push("  browser4-cli attach --extension chrome-canary".to_string());
        lines.push("  browser4-cli attach --extension msedge".to_string());
        lines.push("  browser4-cli attach --endpoint http://browser4-server:8182".to_string());
        lines.push("  browser4-cli attach --endpoint http://remote:8182 --cdp chrome".to_string());
    }

    if cmd.name == "open" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - `open` reconnects to the saved session for the current slot when the backend still reports it as active."
                .to_string(),
        );
        lines.push(
            "    The command shows \"Using existing session\" and the current page URL when reconnecting."
                .to_string(),
        );
        lines.push(
            "  - If the saved session is missing or stale, `open` creates a new browser session."
                .to_string(),
        );
        lines.push(
            "  - `open` without a URL reconnects or creates a session at about:blank without navigating."
                .to_string(),
        );
        lines.push(
            "  - Use `-s <name>` to create a named session that can be targeted by subsequent commands."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli open https://browser4.io".to_string());
        lines.push("  browser4-cli open --headed https://browser4.io".to_string());
        lines.push("  browser4-cli open --headless https://browser4.io".to_string());
    }

    if cmd.name == "install" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Downloads the self-contained Browser4 runtime bundle for the current OS/architecture."
                .to_string(),
        );
        lines.push(
            "  - The bundle contains all dependency jars, a minimal `jlink`-built JRE, and platform launcher scripts."
                .to_string(),
        );
        lines.push(
            "  - Probes configured download mirrors in order and uses the first reachable one."
                .to_string(),
        );
        lines.push(
            "  - When no --tag is given the latest release is resolved automatically.".to_string(),
        );
        lines.push(
            "  - When the requested version is already installed, the download is skipped."
                .to_string(),
        );
        lines.push(
            "  - Use --force to re-download even when the requested version is already installed."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli install".to_string());
        lines.push("  browser4-cli install --tag v4.9.3".to_string());
        lines.push("  browser4-cli install --tag 4.9.3 --force".to_string());
    }

    if cmd.name == "upgrade" {
        lines.push("Notes:".to_string());
        lines.push(wrap_text(
            "Like `install`, but also upgrades the browser4-cli binary itself, and skips the download when the requested version is already installed.",
            "  - ",
            4,
        ));
        lines.push(
            "  - When the requested version is already installed, the download is skipped."
                .to_string(),
        );
        lines.push(
            "  - After upgrading, restart the server: browser4-cli stop && browser4-cli open <url>"
                .to_string(),
        );
        lines.push(
            "  - Supports the same mirror selection and proxy configuration as `install`."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli upgrade".to_string());
        lines.push("  browser4-cli upgrade --tag v4.11.0".to_string());
        lines.push("  browser4-cli upgrade --force".to_string());
    }

    if cmd.name == "uninstall" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Attempts to remove browser4-cli from npm global packages and cargo installs."
                .to_string(),
        );
        lines.push(
            "  - Removes the Browser4 runtime data and runtime cache directories.".to_string(),
        );
        lines.push(
            "  - Prompts for confirmation before removing data directories unless --yes is passed."
                .to_string(),
        );
        lines.push(
            "  - Pass --dry-run to preview what would be removed without making changes."
                .to_string(),
        );
        lines.push("  - Does not require a running server.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli uninstall".to_string());
        lines.push("  browser4-cli uninstall -y".to_string());
        lines.push("  browser4-cli uninstall --dry-run".to_string());
    }

    if cmd.name == "list" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Status shows whether the backend currently reports the saved session as Active, Stale, or Unknown."
                .to_string(),
        );
        lines.push(wrap_text(
            "Next open shows whether `browser4-cli open <url>` will Reuse the saved session or Refresh it (a new session is created only when a URL is provided).",
            "  - ",
            4,
        ));
        lines.push(
            "  - Use --verbose to show full session IDs without truncation (UUIDs are 36 chars, truncated to 40 by default)."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli list".to_string());
        lines.push("  browser4-cli list --verbose".to_string());
    }

    if cmd.name == "goto" {
        lines.push("Notes:".to_string());
        lines.push(wrap_text(
            "Reuses the current active session when possible and auto-opens a fresh one when the saved session is missing or stale.",
            "  - ",
            4,
        ));
        lines.push(
            "  - If the backend had been stopped, `goto` starts or reconnects through the current slot before navigating."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli goto https://browser4.io".to_string());
        lines.push("  browser4-cli -s mysession goto https://browser4.io".to_string());
    }

    if cmd.name == "swarm-create" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Creates a swarm scrape session using the fixed session ID `SWARM` and stores it in the current CLI slot."
                .to_string(),
        );
        lines.push(
            "  - `--profile-mode` defaults to `SEQUENTIAL` and only supports `SEQUENTIAL` or `TEMPORARY`."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli swarm create".to_string());
        lines.push(
            "  browser4-cli swarm create --profile-mode TEMPORARY --max-open-tabs 12 --max-browser-contexts 3 --display-mode HEADLESS"
                .to_string(),
        );
    }

    if cmd.name == "swarm-submit" {
        lines.push("Notes:".to_string());
        lines.push(wrap_text(
            "Accepts a direct URL, a `--seed-file`, or both, and submits each entry as a scrape job through the scrape submit API.",
            "  - ",
            4,
        ));
        lines.push(
            "  - Seed files are plain text with one URL per line; blank lines and lines beginning with `#` are ignored."
                .to_string(),
        );
        lines.push(
            "  - URLs plus load options are forwarded as a raw payload string to `SwarmController.submit(payload)`."
                .to_string(),
        );
        lines.push(
            "  - When `--sql` is provided, the CLI sends a structured JSON body to `SwarmController.query(query)`"
                .to_string(),
        );
        lines.push("    instead of a raw string to `SwarmController.submit(payload)`.".to_string());
        lines.push(
            "  - `--sql` accepts inline X-SQL or a file path prefixed with `@` (e.g. `--sql @query.sql`)."
                .to_string(),
        );
        lines.push(
            "  - Use `@url` in the X-SQL as a placeholder for the target page URL.".to_string(),
        );
        lines.push(
            "  - Pass `--wait` to block until all submitted jobs complete instead of returning immediately."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push(
            "  browser4-cli swarm submit https://example.com/direct --seed-file ./swarm-seeds.txt --deadline 2026-03-30T00:00:00Z --expires 1d --refresh"
                .to_string(),
        );
        lines.push(String::new());
        lines.push("  # Submit with an inline X-SQL query:".to_string());
        lines.push(
            r##"  browser4-cli swarm submit "https://www.amazon.com/dp/B08PP5MSVB" --sql ""##
                .to_string()
                + r#""SELECT dom_base_uri(dom) AS url, dom_first_text(dom, '#productTitle') AS title ""#
                + r#""FROM load_and_select(@url, 'body')""#
                + r#"""#
        );
        lines.push(String::new());
        lines.push("  # Submit with a query file:".to_string());
        lines.push(
            r##"  browser4-cli swarm submit "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql"##
                .to_string(),
        );
    }

    if cmd.name == "swarm-query" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Submits an X-SQL query against a loaded webpage and returns structured data."
                .to_string(),
        );
        lines.push(
            "  - `--sql` is required. Accepts inline X-SQL or a file path prefixed with `@` (e.g. `--sql @query.sql`)."
                .to_string(),
        );
        lines.push(
            "  - Use `@url` in the X-SQL as a placeholder for the target page URL.".to_string(),
        );
        lines.push(
            "  - The CLI sends a structured JSON body to `SwarmController.query(query)`."
                .to_string(),
        );
        lines.push(
            "  - Accepts a direct URL, a `--seed-file`, or both, and runs the same query against each."
                .to_string(),
        );
        lines.push(
            "  - Seed files are plain text with one URL per line; blank lines and lines beginning with `#` are ignored."
                .to_string(),
        );
        lines.push(wrap_text(
            "`--seed-file` takes a direct file path (no `@` prefix); only `--sql` uses `@` to disambiguate inline X-SQL from file paths.",
            "  - ",
            4,
        ));
        lines.push(
            "  - Pass `--wait` to block until all submitted jobs complete instead of returning immediately."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  # Inline X-SQL:".to_string());
        lines.push(
            r##"  browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql ""##
                .to_string()
                + r#""SELECT dom_base_uri(dom) AS url, dom_first_text(dom, '#productTitle') AS title ""#
                + r#""FROM load_and_select(@url, 'body')""#
                + r#"""#
        );
        lines.push(String::new());
        lines.push("  # From a query file:".to_string());
        lines.push(
            r##"  browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql"##
                .to_string(),
        );
        lines.push(String::new());
        lines.push("  # With seed file:".to_string());
        lines.push(
            "  browser4-cli swarm query --sql @query.sql --seed-file ./swarm-seeds.txt --refresh"
                .to_string(),
        );
    }

    if cmd.name == "swarm-status" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Reads the scrape job status from `SwarmController.getStatus(id)` and prints the returned JSON payload."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli swarm status <task-id>".to_string());
    }

    if cmd.name == "swarm-result" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Reads the scrape job result from `SwarmController.getResult(id)` and prints the returned payload."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli swarm result <task-id>".to_string());
    }

    if cmd.name == "swarm-list" {
        lines.push("Notes:".to_string());
        lines.push("  - Lists all tracked swarm tasks and their current status.".to_string());
        lines.push("  - Tasks are ordered by submission time (latest first).".to_string());
        lines.push("  - Use `--limit N` to show at most N tasks and `--offset N` to skip the first N.".to_string());
        lines.push("  - Use `--clear` to remove all tracked swarm tasks from the list.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli swarm list".to_string());
        lines.push("  browser4-cli swarm list --limit 20".to_string());
        lines.push("  browser4-cli swarm list --limit 20 --offset 20".to_string());
        lines.push("  browser4-cli swarm list --clear".to_string());
    }

    if cmd.name == "swarm-close" {
        lines.push("Notes:".to_string());
        lines.push("  - Closes the active swarm session and releases browser resources.".to_string());
        lines.push("  - Equivalent to `close` when a swarm session is active.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli swarm close".to_string());
    }

    if cmd.name == "crawl-status" {
        lines.push("Notes:".to_string());
        lines.push("  - Accepts the task ID returned by `crawl`.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli crawl status <task-id>".to_string());
    }

    if cmd.name == "crawl-result" {
        lines.push("Notes:".to_string());
        lines.push("  - Accepts the task ID returned by `crawl`.".to_string());
        lines.push("  - Only returns results for completed tasks.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli crawl result <task-id>".to_string());
    }

    if cmd.name == "crawl-cancel" {
        lines.push("Notes:".to_string());
        lines.push("  - Accepts the task ID returned by `crawl`.".to_string());
        lines.push("  - Only cancels tasks that are still running or queued.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli crawl cancel <task-id>".to_string());
    }

    if cmd.name == "crawl-clear" {
        lines.push("Notes:".to_string());
        lines.push("  - Removes all completed, failed, or cancelled crawl tasks from the task store.".to_string());
        lines.push("  - Running tasks are not affected.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli crawl clear".to_string());
    }

    if cmd.name == "crawl-list" {
        lines.push("Notes:".to_string());
        lines.push("  - Lists all tracked crawl tasks and their current status.".to_string());
        lines.push("  - Tasks are ordered by submission time (latest first).".to_string());
        lines.push("  - Use `--limit N` to show at most N tasks and `--offset N` to skip the first N.".to_string());
        lines.push("  - Use `--clear` to remove all tracked crawl tasks from the list.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli crawl list".to_string());
        lines.push("  browser4-cli crawl list --limit 20".to_string());
        lines.push("  browser4-cli crawl list --clear".to_string());
    }

    if cmd.name == "crawl" {
        lines.push("Modes:".to_string());
        lines.push(
            "  Link discovery (depth >= 1): start from a seed URL, follow links up to N levels."
                .to_string(),
        );
        lines.push(
            "  Bulk fetch (depth 0): load each URL from --seed-file directly, no link discovery."
                .to_string(),
        );
        lines.push(
            "  X-SQL extraction (--sql): run a query against each crawled page and format results."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(
            "  - Provide a positional URL or --seed-file (or both) — at least one is required."
                .to_string(),
        );
        lines.push(
            "  - --depth (-d) controls how many levels of links to follow (default: 1). Use 0 to skip link discovery."
                .to_string(),
        );
        lines.push(
            "  - --out-link-selector (-ol) specifies a CSS selector to extract links (required for depth >= 1)."
                .to_string(),
        );
        lines.push(
            "  - --out-link-pattern (-olp) filters extracted links with a regex (default: .+)."
                .to_string(),
        );
        lines.push(
            "  - --top-links (-tl) limits the number of links extracted per page (default: 20)."
                .to_string(),
        );
        lines.push(
            "  - --sql accepts inline X-SQL, a file path with @ prefix (e.g. --sql @query.sql), or stdin via --sql-stdin."
                .to_string(),
        );
        lines.push(
            "  - --format controls output: 'table' (default, aligned columns), 'csv', or 'json'."
                .to_string(),
        );
        lines.push(
            "  - --output (-o) writes results to a file instead of stdout."
                .to_string(),
        );
        lines.push(
            "  - Boolean flags --refresh, --parse, --ignore-url-query, --no-norm, --readonly control fetch behavior."
                .to_string(),
        );
        lines.push(
            "  - --args (-a) passes additional LoadOptions through as a raw string (e.g. -a \"-nMaxRetry 5\")."
                .to_string(),
        );
        lines.push(
            "  - --expires sets cache expiration (e.g. 1d, 1h, 30m), --priority sets queue priority (lower = higher priority)."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli crawl https://example.com".to_string());
        lines.push("  browser4-cli crawl https://example.com -d 2 -ol \"a.product\" -olp \"/product/\"".to_string());
        lines.push("  browser4-cli crawl https://example.com --depth 3 --refresh".to_string());
        lines.push("  browser4-cli crawl --seed-file urls.txt --depth 0 --refresh".to_string());
        lines.push("  browser4-cli crawl --seed-file urls.txt --sql @extract.sql --format csv -o results.csv".to_string());
        lines.push("  browser4-cli crawl --seed-file urls.txt --sql-stdin --format table < query.sql".to_string());
    }

    if cmd.name == "htmlsnapshot" {
        lines.push("Subcommands:".to_string());
        lines.push(format_with_gap(
            "  htmlsnapshot get <field> [selector] [name] [--page N] [--page-size N] [--all]",
            "Extract elements from the HTML snapshot stored in Browser4's page storage (text, html, attr)",
            50,
        ));
        lines.push(format_with_gap(
            "  htmlsnapshot get all <field> [selector] [name] [--offset N] [--limit N] [--page N] [--page-size N] [--all]",
            "Extract ALL matching elements from the HTML snapshot (querySelectorAll semantics)",
            50,
        ));
        lines.push(format_with_gap(
            "  htmlsnapshot query [url]",
            "Run X-SQL against the HTML snapshot stored in Browser4's page storage via the scrape API. Use --format table for human-readable output.",
            50,
        ));
        lines.push(format_with_gap(
            "  htmlsnapshot export",
            "Export snapshot HTML from Browser4's page storage to a local file",
            50,
        ));
        lines.push(format_with_gap(
            "  htmlsnapshot summary",
            "Summarize: read the stored HTML snapshot and produce a compressed Web Page Summary Index (WPSI)",
            50,
        ));
        lines.push(format_with_gap(
            "  htmlsnapshot grep [OPTIONS] <pattern>",
            "Search snapshot HTML with regex patterns and grep-style output. Use | for alternation or -e for multiple patterns.",
            50,
        ));
        lines.push(format_with_gap(
            "  htmlsnapshot inspect [selector] [--max N] [--depth D]",
            "Analyze DOM structure and suggest CSS selectors for recurring patterns",
            50,
        ));
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(wrap_text(
            "The base `htmlsnapshot` command captures a static HTML snapshot, stores it in Browser4's page storage, and returns enriched metadata (URL, title, timestamps, image/link counts, interactive elements with tag/class/id/aria/bounding-box).",
            "  - ",
            4,
        ));
        lines.push(wrap_text(
            "After capturing, extract elements from the stored snapshot by CSS selector with `htmlsnapshot get <field> [selector]`.",
            "  - ",
            4,
        ));
        lines.push(
            "  - The `get` subcommand supports three fields: `text` (inner text), `html` (inner HTML), and `attr` (attribute value)."
                .to_string(),
        );
        lines.push(wrap_text(
            "Argument order: `get <field:text|html|attr> <css-selector> [attribute-name]`.  When using `attr`, the third argument is the attribute name (e.g. `href`, `src`, `class`).",
            "  - ",
            4,
        ));
        lines.push(wrap_text(
            "Tip: `htmlsnapshot get attr \"div\" class` → extracts the class attribute from the first matching div.  Use `get all attr \"img\" src` for every image's src.",
            "  - ",
            4,
        ));
        lines.push(wrap_text(
            "Each `get all` call runs independently against the whole document. To extract correlated fields (e.g. title + price + URL per product), use `htmlsnapshot query` with X-SQL's `DOM_LOAD_AND_SELECT` — it scopes each row to a parent container so fields stay aligned.",
            "  - ",
            4,
        ));
        lines.push(
            "  - Element references (`e5`, `backend:15`) are NOT supported by `htmlsnapshot get` — use CSS selectors only."
                .to_string(),
        );
        lines.push(wrap_text(
            "X-SQL queries via `htmlsnapshot query --sql` use `@url` as a placeholder for the target page URL (unquoted — SQLTemplate handles escaping).",
            "  - ",
            4,
        ));
        lines.push(
            "  - `htmlsnapshot query --sql` also supports reading from a file with `--sql @file.sql`."
                .to_string(),
        );
        lines.push(
            "  - Use `--sql-stdin` to pipe X-SQL queries directly from stdin, avoiding shell quoting issues on Windows."
                .to_string(),
        );
        lines.push(
            "  - Use `--sql-base64` to decode the `--sql` value (or stdin input) as base64 before execution."
                .to_string(),
        );
        lines.push(wrap_text(
            "Use `--result-only` to extract and print only the `resultSet` array from the response JSON, omitting wrapper metadata.",
            "  - ",
            4,
        ));
        lines.push(
            "  - Export the full HTML snapshot from Browser4's page storage to a local file with `htmlsnapshot export --file <path>`. Add `--clean` to strip scripts, styles, and non-standard attributes."
                .to_string(),
        );
        lines.push(wrap_text(
            "Generate a compressed page summary (WPSI) from the stored HTML snapshot with `htmlsnapshot summary`. The summary identifies page type, structure, key content nodes, repeated lists, tables, and stats — typically <1% of the original HTML size.",
            "  - ",
            4,
        ));
        lines.push(wrap_text(
            "Search the HTML snapshot HTML with regex patterns using `htmlsnapshot grep <pattern>`. Supports standard grep flags: -e (repeatable), -i, -A, -B, -C, -v, -c, -l, -F, -w, --no-line-number. Use --selector to scope to the first matching CSS element (querySelector), or --selector-all to search across ALL matching elements (querySelectorAll) with element-index annotations. Uses Rust regex syntax where | is alternation (not \\|). Line numbers are shown by default (unlike GNU grep's -n opt-in).",
            "  - ",
            4,
        ));
        lines.push(wrap_text(
            "Analyze DOM structure and discover CSS selectors for recurring patterns with `htmlsnapshot inspect [selector]`. When the selector matches multiple elements (e.g. `.product-card`), it compares child structures across matches and suggests selectors ranked by recurrence. Use --max to control sample size and --depth to limit descendant traversal.",
            "  - ",
            4,
        ));
        lines.push(wrap_text(
            "Output from `get html`, `get all html`, and `grep` is paginated by default (2000 lines per page). `get text` and `get all text` are not paginated by default (text extraction rarely exceeds practical limits). Use --page N for subsequent pages, --page-size N to change the page size, or --all to disable pagination and show all content. Pagination is automatically skipped in --json and --quiet modes.",
            "  - ",
            4,
        ));
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  # Capture a HTML snapshot and display metadata".to_string());
        lines.push("  browser4-cli htmlsnapshot".to_string());
        lines.push(String::new());
        lines.push("  # Get the text content of the whole page (:root)".to_string());
        lines.push("  browser4-cli htmlsnapshot get text".to_string());
        lines.push(String::new());
        lines.push("  # Get the HTML of a specific element by CSS selector".to_string());
        lines.push("  browser4-cli htmlsnapshot get html \"#main-content\"".to_string());
        lines.push(String::new());
        lines.push("  # Get an attribute value (requires attribute name)".to_string());
        lines.push("  browser4-cli htmlsnapshot get attr \"a.product-link\" href".to_string());
        lines.push(String::new());
        lines.push("  # Get all matching elements by CSS selector (returns a JSON array)".to_string());
        lines.push("  browser4-cli htmlsnapshot get all text \"h2 a\"".to_string());
        lines.push(String::new());
        lines.push("  # Correlated multi-field extraction: title, price, and link per product".to_string());
        lines.push(wrap_text(
            "browser4-cli htmlsnapshot query --sql \"SELECT dom_first_text(dom, '.title') AS title, dom_first_text(dom, '.price') AS price, dom_first_href(dom, 'a') AS link FROM load_and_select(@url, '.product')\"",
            "  ",
            2,
        ));
        lines.push(String::new());
        lines.push("  # Get all matching elements with element-level pagination".to_string());
        lines.push("  browser4-cli htmlsnapshot get all text \".result\" --limit 5 --offset 10".to_string());
        lines.push(String::new());
        lines.push("  # Get HTML with line-level pagination (default 2000 lines, page 2)".to_string());
        lines.push("  browser4-cli htmlsnapshot get html \"body\" --page 2".to_string());
        lines.push(String::new());
        lines.push("  # Get all text with custom page size".to_string());
        lines.push("  browser4-cli htmlsnapshot get all text \"p\" --page-size 200".to_string());
        lines.push(String::new());
        lines.push("  # Get full HTML (disable pagination)".to_string());
        lines.push("  browser4-cli htmlsnapshot get html --all".to_string());
        lines.push(String::new());
        lines.push("  # Run an X-SQL query against the current page URL".to_string());
        lines.push(wrap_text(
            "browser4-cli htmlsnapshot query --sql \"SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, ':root')\"",
            "  ",
            2,
        ));
        lines.push(String::new());
        lines.push("  # Run an X-SQL query from a file".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql @query.sql".to_string());
        lines.push(String::new());
        lines.push("  # Pipe an X-SQL query from stdin (avoids shell quoting issues)".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql-stdin < query.sql".to_string());
        lines.push(String::new());
        lines.push("  # Decode a base64-encoded X-SQL query (avoids shell quoting issues)".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql-base64 \"$(base64 -w0 query.sql)\"".to_string());
        lines.push(String::new());
        lines.push("  # Return only the resultSet array, omitting wrapper metadata".to_string());
        lines.push("  browser4-cli htmlsnapshot query --sql @query.sql --result-only".to_string());
        lines.push(String::new());
        lines.push("  # Export the full snapshot HTML to a file (add --clean for LLM consumption)".to_string());
        lines.push("  browser4-cli htmlsnapshot export --file snapshot.html".to_string());
        lines.push("  browser4-cli htmlsnapshot export --file clean.html --clean".to_string());
        lines.push(String::new());
        lines.push("  # Generate a compressed page summary from the stored HTML snapshot".to_string());
        lines.push("  browser4-cli htmlsnapshot summary".to_string());
        lines.push(String::new());
        lines.push("  # Search for 'error' case-insensitively".to_string());
        lines.push("  browser4-cli htmlsnapshot grep -i error".to_string());
        lines.push(String::new());
        lines.push("  # Match any of multiple words with alternation (| is alternation in Rust regex)".to_string());
        lines.push("  browser4-cli htmlsnapshot grep -i \"price|rating|stars\"".to_string());
        lines.push(String::new());
        lines.push("  # Same search using -e repeatable flags (avoids shell escaping)".to_string());
        lines.push("  browser4-cli htmlsnapshot grep -i -e price -e rating -e stars".to_string());
        lines.push(String::new());
        lines.push("  # Literal string match with 2 lines of context".to_string());
        lines.push("  browser4-cli htmlsnapshot grep -F -C 2 \"404 Not Found\"".to_string());
        lines.push(String::new());
        lines.push("  # Search only within <main> element (first match)".to_string());
        lines.push("  browser4-cli htmlsnapshot grep --selector main \"Submit\"".to_string());
        lines.push(String::new());
        lines.push("  # Search across all .product_pod elements (querySelectorAll)".to_string());
        lines.push("  browser4-cli htmlsnapshot grep --selector-all \".product_pod\" \"price_color\"".to_string());
        lines.push(String::new());
        lines.push("  # Search with pagination (page 2, custom page size)".to_string());
        lines.push("  browser4-cli htmlsnapshot grep -i error --page 2 --page-size 200".to_string());
        lines.push(String::new());
        lines.push("  # Search and show all matches (disable pagination)".to_string());
        lines.push("  browser4-cli htmlsnapshot grep --all \"TODOs\"".to_string());
        lines.push(String::new());
        lines.push("  # Discover CSS selectors for recurring product cards".to_string());
        lines.push("  browser4-cli htmlsnapshot inspect \".product_pod\"".to_string());
        lines.push(String::new());
        lines.push("  # Inspect with deeper analysis and larger sample".to_string());
        lines.push("  browser4-cli htmlsnapshot inspect \".s-result-item\" --depth 6 --max 20".to_string());
        lines.push(String::new());
        lines.push("  # Read selector from file (avoids shell escaping on Windows)".to_string());
        lines.push("  browser4-cli htmlsnapshot inspect @selector.txt".to_string());
        lines.push(String::new());
        lines.push("  # Pipe selector via stdin (avoids shell quoting issues)".to_string());
        lines.push("  echo '[data-component-type=\"s-search-result\"]' | browser4-cli htmlsnapshot inspect --stdin".to_string());
        lines.push(String::new());
        lines.push("  # Base64-encoded selector (avoids Windows shell escaping)".to_string());
        lines.push("  browser4-cli htmlsnapshot inspect --selector-base64 W2RhdGEtY29tcG9uZW50LXR5cGU9InMtc2VhcmNoLXJlc3VsdCJd".to_string());
    }

    if cmd.name == "snapshot" {
        lines.push("Subcommands:".to_string());
        lines.push(format_with_gap(
            "  snapshot grep [OPTIONS] <pattern>",
            "Search snapshot YAML content with regex patterns and grep-style output. Use | for alternation (e.g. 'price|rating|stars') or -e for multiple patterns. For large pages, capture a specific viewport first with -v <N> before grepping. Supports --page N, --page-size N, and --all for output pagination (2000 lines per page default).",
            50,
        ));
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(
            "  - For large pages, read the page viewport by viewport — just like a human scrolls. Important"
                .to_string(),
        );
        lines.push(
            "    content usually appears at the top of the page first. Use --viewport / -v as the first choice"
                .to_string(),
        );
        lines.push(
            "    to keep output manageable: -v 0 (top), -v 1 (next), -v 0-2 (first three), -v all (entire page)."
                .to_string(),
        );
        lines.push(
            "  - --viewport accepts single indices (3), comma-separated lists (0,2,4), ranges (1-3),"
                .to_string(),
        );
        lines.push(
            "    or mixed (0,2-4,7).  Passed directly to the server's ViewportSpec parser."
                .to_string(),
        );
        lines.push(
            "  - Use --stdout (or --raw) to print snapshot content directly to stdout for piping."
                .to_string(),
        );
        lines.push(
            "  - Use --depth / -d <n> to limit the accessibility tree depth (e.g. -d 4)."
                .to_string(),
        );
        lines.push(
            "    A warning is printed on stderr when content has been truncated by the depth limit."
                .to_string(),
        );
        lines.push(
            "  - snapshot grep searches the accessibility-tree YAML, not the DOM HTML."
                .to_string(),
        );
        lines.push(
            "  - Uses Rust regex syntax (bare `|` for alternation, not `\\|`). Use `-F` for literal matching."
                .to_string(),
        );
        lines.push(
            "    The `-E` (extended regex) flag is accepted for grep compatibility but is a no-op: Rust regex is always ERE-like."
                .to_string(),
        );
        lines.push(wrap_text(
            "snapshot grep supports the same grep options as htmlsnapshot grep: -e (repeatable), -i, -A, -B, -C, -v, -c, -l, -F, -w, --no-line-number, --selector, --selector-all, --page N, --page-size N, and --all.",
            "  - ",
            4,
        ));
        lines.push(wrap_text(
            "Output is paginated by default (2000 lines per page). Use --page N for subsequent pages, --page-size N to change the page size, or --all to show all content.",
            "  - ",
            4,
        ));
        lines.push(String::new());
        lines.push("Viewports (page chunks):".to_string());
        lines.push("  A viewport is one screen-height chunk of the page (~viewport height px).".to_string());
        lines.push("  Long pages are split into multiple viewports. -v N captures chunk N (0-indexed).".to_string());
        lines.push("  The snapshot filters the accessibility tree by Y-range — the page is not scrolled.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  # Read the page viewport by viewport (start from the top)".to_string());
        lines.push("  browser4-cli snapshot -v 0".to_string());
        lines.push(String::new());
        lines.push("  # Capture a range of viewports".to_string());
        lines.push("  browser4-cli snapshot --viewport 1-3".to_string());
        lines.push(String::new());
        lines.push("  # Capture specific viewports using ViewportSpec format".to_string());
        lines.push("  browser4-cli snapshot --viewport 0,2,4".to_string());
        lines.push(String::new());
        lines.push("  # Capture snapshot and print to stdout for piping".to_string());
        lines.push("  browser4-cli snapshot --stdout | head -20".to_string());
        lines.push(String::new());
        lines.push("  # Print raw snapshot content (no page info, no header)".to_string());
        lines.push("  browser4-cli snapshot --raw | grep \"button\"".to_string());
        lines.push(String::new());
        lines.push("  # Limit tree depth to reduce output size".to_string());
        lines.push("  browser4-cli snapshot --depth 4".to_string());
        lines.push(String::new());
        lines.push("  # Search for 'error' in snapshot YAML".to_string());
        lines.push("  browser4-cli snapshot grep -i error".to_string());
        lines.push(String::new());
        lines.push("  # Match any of multiple words with alternation".to_string());
        lines.push("  browser4-cli snapshot grep -i \"price|rating|stars\"".to_string());
        lines.push(String::new());
        lines.push("  # Same search using -e repeatable flags (avoids shell escaping)".to_string());
        lines.push("  browser4-cli snapshot grep -i -e price -e rating -e stars".to_string());
        lines.push(String::new());
        lines.push("  # Search with context lines".to_string());
        lines.push("  browser4-cli snapshot grep -C 2 \"timeout\"".to_string());
        lines.push(String::new());
        lines.push("  # Search with pagination (page 2, custom page size)".to_string());
        lines.push("  browser4-cli snapshot grep error --page 2 --page-size 200".to_string());
    }

    if cmd.name == "skills" {
        lines.push("Subcommands:".to_string());
        lines.push(format_with_gap(
            "  skills",
            "List all bundled skill names with file counts",
            50,
        ));
        lines.push(format_with_gap(
            "  skills list",
            "Same as `skills` — list all bundled skill names",
            50,
        ));
        lines.push(format_with_gap(
            "  skills get <name>",
            "Output a skill's SKILL.md content",
            50,
        ));
        lines.push(format_with_gap(
            "  skills get <name> --full",
            "Include all reference files and extra documentation",
            50,
        ));
        lines.push(format_with_gap(
            "  skills get --all",
            "Output every bundled skill concatenated",
            50,
        ));
        lines.push(format_with_gap(
            "  skills path [name]",
            "Print the skills directory path (or path to a specific skill)",
            50,
        ));
        lines.push(format_with_gap(
            "  skills unpack [dest]",
            "Unpack bundled skill files to a directory",
            50,
        ));
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(
            "  - Skills are AI agent instruction files bundled into the browser4-cli binary"
                .to_string(),
        );
        lines.push(
            "    at compile time."
                .to_string(),
        );
        lines.push(
            "  - `skills` (or `skills list`) lists all bundled skill names with file counts."
                .to_string(),
        );
        lines.push(
            "  - `skills get <name>` outputs a skill's SKILL.md. Use --full to include all"
                .to_string(),
        );
        lines.push(
            "    reference files and templates, or --all to output every skill concatenated."
                .to_string(),
        );
        lines.push(
            "  - `skills path [name]` prints the skills directory (or a specific skill's path)."
                .to_string(),
        );
        lines.push(
            "  - `skills unpack [dest]` writes bundled skill files to disk."
                .to_string(),
        );
        lines.push(
            "  - AI agents use these commands to fetch current instructions that always match"
                .to_string(),
        );
        lines.push(
            "    the installed CLI version, rather than relying on cached copies."
                .to_string(),
        );
        lines.push(
            "  - Skill files are unpacked to the versioned installation directory during"
                .to_string(),
        );
        lines.push(
            "    `browser4-cli install`. Use `skills path` to locate them on disk."
                .to_string(),
        );
        lines.push(
            "  - Set BROWSER4_SKILLS_DIR to override the skills directory path."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli skills".to_string());
        lines.push("  browser4-cli skills list".to_string());
        lines.push("  browser4-cli skills get browser4-cli".to_string());
        lines.push("  browser4-cli skills get browser4-cli --full".to_string());
        lines.push("  browser4-cli skills get --all".to_string());
        lines.push("  browser4-cli skills path".to_string());
        lines.push("  browser4-cli skills path browser4-cli".to_string());
        lines.push("  browser4-cli skills unpack".to_string());
        lines.push("  browser4-cli skills unpack /custom/path".to_string());
        lines.push("  BROWSER4_SKILLS_DIR=/custom/path browser4-cli skills path".to_string());
    }

    if cmd.name == "generate-locator" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Generates a unique CSS selector path for an element identified by a snapshot ref (e5, backend:15) or a CSS selector."
                .to_string(),
        );
        lines.push(
            "  - Useful for creating stable selectors that survive page reloads and DOM changes."
                .to_string(),
        );
        lines.push(
            "  - The generated selector path is based on the element's tag, id, classes, and structural position."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli generate-locator e5".to_string());
        lines.push("  browser4-cli generate-locator backend:15".to_string());
        lines.push("  browser4-cli generate-locator \".product-card\"".to_string());
    }

    if cmd.name == "loop" {
        lines.push(String::new());
        lines.push("Modes:".to_string());
        lines.push("  - Plain text: the task string is submitted to the Browser4 server as-is.".to_string());
        lines.push("    X-SQL queries are auto-detected by the server.".to_string());
        lines.push("  - --shell: the task is executed via the OS shell (cmd /C or sh -c).".to_string());
        lines.push(
            "  - -- (double dash): everything after -- is executed as a browser4-cli subcommand."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Persistence & Control:".to_string());
        lines.push(
            "  - Progress is saved to ~/.browser4/loop-state.json (default) or"
                .to_string(),
        );
        lines.push(
            "    ~/.browser4/loops/<name>.json (named loops) after each iteration."
                .to_string(),
        );
        lines.push(
            "  - If the process is interrupted (Ctrl+C), the loop can be resumed by running"
                .to_string(),
        );
        lines.push("    the same command again. State is auto-cleared on normal completion.".to_string());
        lines.push("  - Use --stop to clear persisted state, --status to inspect it.".to_string());
        lines.push("  - Use --name <n> to run multiple independent loops (name: only letters,".to_string());
        lines.push("    digits, dots, hyphens, underscores). Use --list to see all loops.".to_string());
        lines.push(
            "  - Use --pause to suspend a running loop (control op) or combine --pause with"
                .to_string(),
        );
        lines.push(
            "    a task to start in paused state. Use --resume to continue a paused loop."
                .to_string(),
        );
        lines.push(
            "  - Use --pause-all / --resume-all to control all loops at once."
                .to_string(),
        );
        lines.push("  - Use --stop-all to stop and clear all persisted loops.".to_string());
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(
            "  - The interval is measured from the start of each iteration.".to_string(),
        );
        lines.push(
            "  - If an iteration takes longer than the interval, the next starts immediately."
                .to_string(),
        );
        lines.push(
            "  - Errors during an iteration are logged but the loop continues.".to_string(),
        );
        lines.push(
            "  - The timeout is checked at the start of each iteration; a long-running"
                .to_string(),
        );
        lines.push("    iteration may exceed the timeout.".to_string());
        lines.push(
            "  - Defaults: 1 hour interval, 1 week timeout. Use --count, --interval,"
                .to_string(),
        );
        lines.push("    or --timeout to adjust.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push(
            "  browser4-cli loop \"load https://example.com and extract the page title\""
                .to_string(),
        );
        lines.push(
            "  browser4-cli loop --shell \"curl -s https://api.example.com/health\" -i 60 -n 10"
                .to_string(),
        );
        lines.push(
            "  browser4-cli loop -- eval \"document.title\" -i 300"
                .to_string(),
        );
        lines.push(
            "  browser4-cli loop \"select dom.title from load_and_select('https://example.com')\" --count 5"
                .to_string(),
        );
        lines.push("  browser4-cli loop --list".to_string());
        lines.push("  browser4-cli loop --status".to_string());
        lines.push("  browser4-cli loop --status --name my-loop".to_string());
        lines.push("  browser4-cli loop --pause --name my-loop".to_string());
        lines.push("  browser4-cli loop --pause --shell \"echo hi\" -i 60   (start paused)".to_string());
        lines.push("  browser4-cli loop --resume --name my-loop".to_string());
        lines.push("  browser4-cli loop --stop --name my-loop".to_string());
    }

    if cmd.name == "skills-get" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Without a name argument, lists all available skills (same as `skills list`)."
                .to_string(),
        );
        lines.push(
            "  - With a name, outputs that skill's SKILL.md content."
                .to_string(),
        );
        lines.push(
            "  - --full includes all bundled reference files and templates, concatenated"
                .to_string(),
        );
        lines.push(
            "    with file-path headers."
                .to_string(),
        );
        lines.push(
            "  - --all outputs every bundled skill concatenated together."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli skills get browser4-cli".to_string());
        lines.push("  browser4-cli skills get browser4-cli --full".to_string());
        lines.push("  browser4-cli skills get --all".to_string());
    }

    if cmd.name == "skills-path" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Without a name argument, prints the skills root directory path."
                .to_string(),
        );
        lines.push(
            "  - With a skill name, prints the path to that skill's subdirectory."
                .to_string(),
        );
        lines.push(
            "  - The path honours BROWSER4_SKILLS_DIR as an override."
                .to_string(),
        );
        lines.push(
            "  - When no override is set, the path points to the versioned installation"
                .to_string(),
        );
        lines.push(
            "    directory (e.g. <runtime>/versions/<tag>/skills/)."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli skills path".to_string());
        lines.push("  browser4-cli skills path browser4-cli".to_string());
    }

    if cmd.name == "skills-unpack" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Writes all bundled skill files from the binary to the destination directory."
                .to_string(),
        );
        lines.push(
            "  - Without a dest argument, unpacks to the default skills directory,"
                .to_string(),
        );
        lines.push(
            "    which honours BROWSER4_SKILLS_DIR as an override."
                .to_string(),
        );
        lines.push(
            "  - The destination directory is created if it does not exist."
                .to_string(),
        );
        lines.push(
            "  - Existing files are overwritten."
                .to_string(),
        );
        lines.push(
            "  - `browser4-cli install` runs unpack automatically — use `skills unpack`"
                .to_string(),
        );
        lines.push(
            "    only when you need to refresh or relocate skill files."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli skills unpack".to_string());
        lines.push("  browser4-cli skills unpack /custom/path".to_string());
    }

    if cmd.name == "tab-new" {
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(
            "  - When no URL is given, the new tab opens with about:blank."
                .to_string(),
        );
        lines.push(
            "  - Tab insertion position is determined by Chrome (not Browser4). In most"
                .to_string(),
        );
        lines.push(
            "    cases the new tab appears immediately after the currently active tab."
                .to_string(),
        );
        lines.push(
            "  - After creation, the CLI auto-selects the new tab and prints its index."
                .to_string(),
        );
        lines.push(
            "  - Use the printed index or GUID from tab-list to reference the tab in"
                .to_string(),
        );
        lines.push(
            "    subsequent commands (tab-select, tab-close)."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli tab-new".to_string());
        lines.push("  browser4-cli tab-new https://example.com".to_string());
        lines.push("  browser4-cli -s <session> tab-new https://httpbin.org/get".to_string());
    }

    if cmd.name == "tab-list" {
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(
            "  - Outputs a table with Index, GUID, Title, and URL columns by default."
                .to_string(),
        );
        lines.push(
            "  - Use --json as a global flag (BEFORE the command) for machine-readable"
                .to_string(),
        );
        lines.push(
            "    output: `browser4-cli --json tab-list` returns { tabs: [...], count: N }."
                .to_string(),
        );
        lines.push(
            "  - GUIDs from extension sessions are prefixed with `chrome:` to distinguish"
                .to_string(),
        );
        lines.push(
            "    them from regular Browser4 session GUIDs (32-char hex)."
                .to_string(),
        );
        lines.push(
            "  - After a tab-list, the current tab's page metadata is NOT captured —"
                .to_string(),
        );
        lines.push(
            "    tab-list is read-only and does not trigger a snapshot. Run `snapshot`"
                .to_string(),
        );
        lines.push(
            "    explicitly if you need element refs after a tab switch."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli tab-list".to_string());
        lines.push("  browser4-cli --json tab-list".to_string());
        lines.push("  browser4-cli -s <session> tab-list".to_string());
    }

    lines.join("\n")
}

pub fn generate_help_entry(cmd: &CommandDef) -> String {
    let mut args_text = cmd
        .args
        .iter()
        .map(|a| {
            if a.optional {
                format!("[{}]", a.name)
            } else {
                format!("<{}>", a.name)
            }
        })
        .collect::<Vec<_>>()
        .join(" ");

    // Surface the --ref flag alongside the positional [ref] arg for discoverability
    if cmd.name == "eval" {
        args_text = args_text.replace(" [ref]", "");
        args_text = format!("{} [--ref <ref>]", args_text.trim_end());
    }

    // Surface the --guid option for tab commands so users discover the stable identifier
    if cmd.name == "tab-close" || cmd.name == "tab-select" {
        args_text = format!("{} [--guid <guid>]", args_text.trim_end());
    }

    // Surface key required options for swarm-query in the help summary
    if cmd.name == "swarm-query" {
        args_text = format!("{} --sql <query> [--seed-file <file>] [--wait]", args_text.trim_end());
    }

    // Surface key options for swarm-submit in the help summary
    if cmd.name == "swarm-submit" {
        args_text = format!("{} [--sql <query>] [--seed-file <file>] [--wait]", args_text.trim_end());
    }

    let prefix = format!("  {} {}", public_command_name(cmd.name), args_text);
    let prefix = prefix.trim_end();
    format_with_gap(prefix, cmd.description, 30)
}

/// Word-wrap prose text so no output line exceeds `max_width`.
///
/// `first_line_prefix` is prepended to the first line only (e.g. `"  - "`).
/// `indent` is the column at which continuation lines start.
///
/// Existing newlines in `text` are preserved as paragraph breaks. Splitting
/// occurs at word boundaries only (spaces).
fn wrap_text(text: &str, first_line_prefix: &str, indent: usize) -> String {
    if text.is_empty() {
        return first_line_prefix.to_string();
    }
    let prefix_len = first_line_prefix.len();
    let first_avail = MAX_LINE_WIDTH.saturating_sub(prefix_len);
    let cont_avail = MAX_LINE_WIDTH.saturating_sub(indent);
    let cont_pad = " ".repeat(indent);

    let mut output = String::new();
    let mut para_idx = 0;

    for paragraph in text.split('\n') {
        if para_idx > 0 {
            output.push('\n');
        }
        para_idx += 1;

        let words: Vec<&str> = paragraph.split(' ').collect();
        let mut para_lines: Vec<String> = Vec::new();
        let mut current_line = String::new();

        for word in words {
            let is_first = para_lines.is_empty();
            let limit = if is_first { first_avail } else { cont_avail };

            if current_line.is_empty() {
                current_line = word.to_string();
            } else if current_line.len() + 1 + word.len() <= limit {
                current_line.push(' ');
                current_line.push_str(word);
            } else {
                para_lines.push(current_line);
                current_line = word.to_string();
            }
        }

        if !current_line.is_empty() || para_lines.is_empty() {
            para_lines.push(current_line);
        }

        for (i, line) in para_lines.iter().enumerate() {
            if i == 0 {
                output.push_str(first_line_prefix);
            } else {
                output.push_str(&cont_pad);
            }
            output.push_str(line);
            output.push('\n');
        }
    }

    // Trim trailing newline
    while output.ends_with('\n') {
        output.pop();
    }

    output
}

fn format_with_gap(prefix: &str, text: &str, threshold: usize) -> String {
    let gap = if prefix.len() < threshold {
        threshold - prefix.len()
    } else {
        2
    };

    // Fast path: everything fits on one line
    let full_line = format!("{}{}{}", prefix, " ".repeat(gap), text);
    if full_line.len() <= MAX_LINE_WIDTH {
        return full_line;
    }


    // Word-wrap the description text with continuation indented to `threshold`
    let prefix_total = prefix.len() + gap;
    let first_avail = MAX_LINE_WIDTH.saturating_sub(prefix_total);
    let cont_avail = MAX_LINE_WIDTH.saturating_sub(threshold);
    let cont_indent = " ".repeat(threshold);

    let words: Vec<&str> = text.split(' ').collect();
    let mut lines: Vec<String> = Vec::new();
    let mut current_line = String::new();

    for word in words {
        let is_first = lines.is_empty();
        let limit = if is_first { first_avail } else { cont_avail };

        if current_line.is_empty() {
            current_line = word.to_string();
        } else if current_line.len() + 1 + word.len() <= limit {
            current_line.push(' ');
            current_line.push_str(word);
        } else {
            lines.push(current_line);
            current_line = word.to_string();
        }
    }
    if !current_line.is_empty() || lines.is_empty() {
        lines.push(current_line);
    }

    let mut result = String::new();
    for (i, line) in lines.iter().enumerate() {
        if i == 0 {
            result.push_str(&format!("{}{}{}", prefix, " ".repeat(gap), line));
        } else {
            result.push_str(&format!("\n{}{}", cont_indent, line));
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_help_contains_commands() {
        let help = generate_help();
        assert!(help.contains("goto"));
        assert!(help.contains("click"));
        assert!(help.contains("snapshot"));
        assert!(help.contains("install"));
        assert!(help.contains("uninstall"));
        assert!(help.contains("upgrade"));
        assert!(help.contains("ArrowLeft"));
        assert!(help.contains("Evaluate JavaScript expression on page or element"));
        assert!(help.contains("Core:"));
        assert!(help.contains("batch"));
        assert!(help.contains("console"));
        assert!(help.contains("extract"));
        assert!(help.contains("summarize"));
        assert!(help.contains("agent run"));
        assert!(help.contains("agent status"));
        assert!(help.contains("agent result"));
        assert!(help.contains("pdf"));
        assert!(!help.contains("  act "));
        assert!(help.contains("swarm create"));
        assert!(help.contains("--json"));
        assert!(help.contains("suppresses tips, hints, and human-readable text"));
        assert!(help.contains("-q, --quiet"));
        assert!(help.contains("suppress normal output"));
    }

    #[test]
    fn test_generate_command_help_goto() {
        let cmds = all_commands();
        let goto = cmds.iter().find(|c| c.name == "goto").unwrap();
        let help = generate_command_help(goto);
        assert!(help.contains("browser4-cli goto <url>"));
        assert!(
            help.contains("Navigate to a URL, auto-opening or refreshing the session when needed")
        );
        assert!(help.contains("auto-opens a fresh one"));
        assert!(help.contains("backend had been stopped"));
    }

    #[test]
    fn test_generate_command_help_open() {
        let cmds = all_commands();
        let open = cmds.iter().find(|c| c.name == "open").unwrap();
        let help = generate_command_help(open);
        assert!(help.contains("browser4-cli open [url]"));
        assert!(help
            .contains("Open a browser session or reconnect to an existing one"));
        assert!(help.contains("backend still reports it as active"));
        assert!(help.contains("creates a new browser session"));
    }

    #[test]
    fn test_generate_command_help_install() {
        let cmds = all_commands();
        let install = cmds.iter().find(|c| c.name == "install").unwrap();
        let help = generate_command_help(install);
        assert!(help.contains("browser4-cli install"));
        assert!(help.contains("self-contained Browser4 runtime bundle"));
        assert!(help.contains("dependency jars, a minimal `jlink`-built JRE"));
        assert!(help.contains("browser4-cli install --tag v4.9.3"));
        assert!(help.contains("--force"));
        assert!(help.contains("configured download mirrors"));
        assert!(help.contains("download is skipped"));
        assert!(help.contains("re-download even when"));
    }

    #[test]
    fn test_generate_command_help_upgrade() {
        let cmds = all_commands();
        let upgrade = cmds.iter().find(|c| c.name == "upgrade").unwrap();
        let help = generate_command_help(upgrade);
        assert!(help.contains("browser4-cli upgrade"));
        assert!(help.contains("also upgrades the browser4-cli binary itself"));
        assert!(help.contains("restart the server"));
        assert!(help.contains("--tag"));
        assert!(help.contains("--force"));
        assert!(help.contains("mirror selection"));
    }

    #[test]
    fn test_generate_command_help_list() {
        let cmds = all_commands();
        let list = cmds.iter().find(|c| c.name == "list").unwrap();
        let help = generate_command_help(list);
        assert!(help.contains("browser4-cli list"));
        assert!(help.contains("status and next-open behavior"));
        assert!(help.contains("Active, Stale, or Unknown"));
        assert!(help.contains("Reuse the saved session or Refresh it"));
    }

    #[test]
    fn test_generate_command_help_batch() {
        let cmds = all_commands();
        let batch = cmds.iter().find(|c| c.name == "batch").unwrap();
        let help = generate_command_help(batch);
        assert!(help.contains("browser4-cli batch [command...]"));
        assert!(help.contains("Execute multiple commands in one invocation"));
        assert!(help.contains("--bail"));
        assert!(help.contains("--json"));
        assert!(help.contains("Quote each subcommand"));
        assert!(help.contains("DOM operations"));
        assert!(help.contains("Session lifecycle commands"));
        assert!(help.contains("executed separately"));
        assert!(help.contains("browser4-cli batch \"goto https://browser4.io\" \"snapshot\""));
        assert!(help.contains("batch --json"));
    }

    #[test]
    fn test_generate_command_help_extract() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "extract").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli extract <instruction>"));
        assert!(help.contains("Extract structured data"));
        assert!(help.contains("--schema"));
        assert!(help.contains("natural-language instruction"));
        assert!(help.contains("--stdout (or --raw) to print to stdout"));
        assert!(help.contains("browser4-cli extract \"product name, price, ratings\""));
    }

    #[test]
    fn test_generate_command_help_summarize() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "summarize").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli summarize"));
        assert!(help.contains("Summarize page content"));
        assert!(help.contains("--selector"));
    }

    #[test]
    fn test_generate_command_help_eval() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "eval").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli eval [expression] [ref]"));
        assert!(help.contains("Evaluate JavaScript expression on page or element"));
        assert!(help.contains("browser4-cli eval \"document.title\""));
        assert!(help.contains("browser4-cli eval --file script.js e5"));
        assert!(help.contains("--file"));
        assert!(help.contains("Read JavaScript expression from a file"));
        assert!(help.contains("browser4-cli eval --file script.js"));
        assert!(help.contains("--base64"));
        assert!(help.contains("Use --base64 for short inline expressions"));
        assert!(help.contains("browser4-cli eval --base64 ZG9jdW1lbnQudGl0bGU="));
        assert!(help.contains("Objects and arrays are serialized as valid JSON"));
        assert!(help.contains("--json to JSON-wrap"));
        assert!(help.contains("browser4-cli eval --json \"document.title\""));
    }

    #[test]
    fn test_generate_command_help_agent_run() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "agent-run").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli agent run <task>"));
        assert!(help.contains("autonomous agent task"));
        assert!(help.contains("browser4-cli agent run"));
        assert!(!help.contains("browser4-cli agent-run"));
    }

    #[test]
    fn test_generate_command_help_agent_status_and_result() {
        let cmds = all_commands();

        let status = cmds.iter().find(|c| c.name == "agent-status").unwrap();
        let status_help = generate_command_help(status);
        assert!(status_help.contains("browser4-cli agent status <id>"));
        assert!(status_help.contains("browser4-cli agent status agent-task-1"));
        assert!(!status_help.contains("browser4-cli agent-status"));

        let result = cmds.iter().find(|c| c.name == "agent-result").unwrap();
        let result_help = generate_command_help(result);
        assert!(result_help.contains("browser4-cli agent result <id>"));
        assert!(result_help.contains("browser4-cli agent result agent-task-1"));
        assert!(!result_help.contains("browser4-cli agent-result"));
    }

    #[test]
    fn test_generate_command_help_act() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "act").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli act <description>"));
        assert!(help.contains("natural language descriptions"));
        assert!(help.contains("browser4-cli act \"scroll by 200px\""));
        assert!(help.contains("browser4-cli act \"go to https://example.com\""));
        assert!(help.contains("LLM provider"));
    }

    #[test]
    fn test_generate_command_help_agent_list() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "agent-list").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli agent list"));
        assert!(help.contains("ordered by submission time"));
        assert!(help.contains("--limit N"));
        assert!(help.contains("--offset N"));
        assert!(help.contains("--clear"));
        assert!(!help.contains("browser4-cli agent-list"));
    }

    #[test]
    fn test_generate_command_help_crawl_list() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "crawl-list").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli crawl list"));
        assert!(help.contains("ordered by submission time"));
        assert!(help.contains("--limit N"));
        assert!(help.contains("--offset N"));
        assert!(help.contains("--clear"));
        assert!(!help.contains("browser4-cli crawl-list"));
    }

    #[test]
    fn test_generate_command_help_swarm_create() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "swarm-create").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli swarm create"));
        assert!(help.contains("swarm scrape session"));
        assert!(help.contains("--profile-mode"));
        assert!(help.contains("--max-open-tabs"));
        assert!(help.contains("--max-browser-contexts"));
        assert!(help.contains("--display-mode"));
        assert!(help.contains("fixed session ID `SWARM`"));
        assert!(help.contains("defaults to `SEQUENTIAL`"));
        assert!(help.contains("only supports `SEQUENTIAL` or `TEMPORARY`"));
        assert!(help.contains("browser4-cli swarm create\n"));
        assert!(help.contains("Display mode: GUI, HEADLESS, SUPERVISED"));
        assert!(!help.contains("browser4-cli swarm-create"));
    }

    #[test]
    fn test_generate_command_help_swarm_submit() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "swarm-submit").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli swarm submit"));
        assert!(help.contains("--seed-file"));
        assert!(help.contains("--deadline"));
        assert!(help.contains("--expires"));
        assert!(help.contains("blank lines and lines beginning with `#` are ignored"));
        assert!(help.contains("submits each entry as a scrape job"));
        assert!(help.contains("SwarmController.submit(payload)"));
        assert!(help.contains("browser4-cli swarm submit https://example.com/direct"));
        assert!(!help.contains("browser4-cli swarm-submit"));
        // --sql flag documentation
        assert!(help.contains("SwarmController.query(query)"));
        assert!(help.contains("--sql"));
        assert!(help.contains("@url"));
        assert!(help.contains("load_and_select"));
        assert!(help.contains("query.sql"));
    }

    #[test]
    fn test_generate_command_help_swarm_query() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "swarm-query").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli swarm query"));
        assert!(help.contains("--sql"));
        assert!(help.contains("@url"));
        assert!(help.contains("SwarmController.query(query)"));
        assert!(help.contains("load_and_select"));
        assert!(help.contains("query.sql"));
        assert!(help.contains("seed file"));
        assert!(help.contains("inline X-SQL"));
    }

    #[test]
    fn test_generate_command_help_swarm_status_and_result() {
        let cmds = all_commands();

        let status = cmds.iter().find(|c| c.name == "swarm-status").unwrap();
        let status_help = generate_command_help(status);
        assert!(status_help.contains("browser4-cli swarm status <id>"));
        assert!(status_help.contains("scrape job status"));
        assert!(status_help.contains("SwarmController.getStatus(id)"));
        assert!(status_help.contains("browser4-cli swarm status <task-id>"));
        assert!(!status_help.contains("browser4-cli swarm-status"));

        let result = cmds.iter().find(|c| c.name == "swarm-result").unwrap();
        let result_help = generate_command_help(result);
        assert!(result_help.contains("browser4-cli swarm result <id>"));
        assert!(result_help.contains("scrape job result"));
        assert!(result_help.contains("SwarmController.getResult(id)"));
        assert!(result_help.contains("browser4-cli swarm result <task-id>"));
        assert!(!result_help.contains("browser4-cli swarm-result"));
    }

    #[test]
    fn test_generate_command_help_htmlsnapshot() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "htmlsnapshot").unwrap();
        let help = generate_command_help(cmd);
        // Header
        assert!(help.contains("browser4-cli htmlsnapshot"));
        assert!(help.contains("Capture: take a static HTML snapshot of the current page and store it"));
        // Subcommands listing
        assert!(help.contains("Subcommands:"));
        assert!(help.contains("htmlsnapshot get <field> [selector] [name] [--page N] [--page-size N] [--all]"));
        // Help text is wrapped — search for individual line fragments
        assert!(help.contains("Extract elements from"));
        assert!(help.contains("page storage (text, html, attr)"));
        assert!(help.contains("htmlsnapshot get all <field> [selector] [name] [--offset N] [--limit N] [--page N] [--page-size N] [--all]"));
        assert!(help.contains("ALL matching elements from the HTML snapshot"));
        assert!(help.contains("querySelectorAll"));
        assert!(help.contains("htmlsnapshot query [url]"));
        assert!(help.contains("Run X-SQL against the HTML snapshot"));
        assert!(help.contains("via the scrape API"));
        assert!(help.contains("htmlsnapshot export"));
        assert!(help.contains("Export snapshot HTML from Browser4's page storage to a local file"));
        assert!(help.contains("htmlsnapshot summary"));
        assert!(help.contains("Summarize: read the stored HTML snapshot and produce a compressed Web"));
        // Notes
        assert!(help.contains("static HTML snapshot, stores it in Browser4's page storage, and returns"));
        assert!(help.contains("enriched metadata"));
        assert!(help.contains("CSS selectors only"));
        assert!(help.contains("@url"));
        assert!(help.contains("SQLTemplate handles escaping"));
        assert!(help.contains("@file.sql"));
        assert!(help.contains("--sql-stdin"));
        assert!(help.contains("pipe X-SQL queries directly from stdin"));
        assert!(help.contains("--sql-base64"));
        assert!(help.contains("decode the `--sql` value"));
        assert!(help.contains("--result-only"));
        assert!(help.contains("extract and print only the `resultSet`"));
        // Examples
        assert!(help.contains("browser4-cli htmlsnapshot"));
        assert!(help.contains("browser4-cli htmlsnapshot get text"));
        assert!(help.contains("browser4-cli htmlsnapshot get html \"#main-content\""));
        assert!(help.contains("browser4-cli htmlsnapshot get attr \"a.product-link\" href"));
        assert!(help.contains("browser4-cli htmlsnapshot get all text \"h2 a\""));
        assert!(help.contains("browser4-cli htmlsnapshot get all text \".result\" --limit 5 --offset 10"));
        assert!(help.contains("browser4-cli htmlsnapshot query --sql"));
        assert!(help.contains("browser4-cli htmlsnapshot query --sql @query.sql"));
        assert!(help.contains("browser4-cli htmlsnapshot query --sql-stdin < query.sql"));
        assert!(help.contains("browser4-cli htmlsnapshot query --sql-base64"));
        assert!(help.contains("browser4-cli htmlsnapshot query --sql @query.sql --result-only"));
        assert!(help.contains("browser4-cli htmlsnapshot export --file snapshot.html"));
        assert!(help.contains("browser4-cli htmlsnapshot export --file clean.html --clean"));
        assert!(help.contains("browser4-cli htmlsnapshot summary"));
        // grep and inspect
        assert!(help.contains("htmlsnapshot grep [OPTIONS] <pattern>"));
        assert!(help.contains("browser4-cli htmlsnapshot grep --selector main \"Submit\""));
        assert!(help.contains("browser4-cli htmlsnapshot grep --selector-all \".product_pod\" \"price_color\""));
        assert!(help.contains("htmlsnapshot inspect [selector] [--max N] [--depth D]"));
        assert!(help.contains("Analyze DOM structure and suggest CSS selectors for recurring"));
        assert!(help.contains("browser4-cli htmlsnapshot inspect \".product_pod\""));
        // enriched metadata
        assert!(help.contains("image/link counts"));
        assert!(help.contains("interactive elements with"));
        assert!(help.contains("tag/class/id/aria/bounding-box"));
        // pagination
        assert!(help.contains("Output from `get html`, `get all html`, and `grep` is paginated"));
        assert!(help.contains("Use --page N for"));
        assert!(help.contains("subsequent pages"));
        assert!(help.contains("browser4-cli htmlsnapshot get html \"body\" --page 2"));
        assert!(help.contains("browser4-cli htmlsnapshot get all text \"p\" --page-size 200"));
        assert!(help.contains("browser4-cli htmlsnapshot get html --all"));
        assert!(help.contains("browser4-cli htmlsnapshot grep -i error --page 2 --page-size 200"));
        assert!(help.contains("browser4-cli htmlsnapshot grep --all \"TODOs\""));
        // get all independence note — steer users to X-SQL for correlated multi-field extraction
        assert!(help.contains("Each `get all` call runs independently against the whole document"));
        assert!(help.contains("use `htmlsnapshot query` with X-SQL's `DOM_LOAD_AND_SELECT`"));
        // correlated multi-field example
        assert!(help.contains("Correlated multi-field extraction: title, price, and link per product"));
        assert!(help.contains("dom_first_text(dom, '.title') AS title, dom_first_text(dom, '.price') AS"));
        assert!(help.contains("price, dom_first_href"));
        assert!(help.contains("FROM load_and_select(@url, '.product')"));
    }

    #[test]
    fn test_generate_command_help_htmlsnapshot_get() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "htmlsnapshot-get").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli htmlsnapshot get <field> [selector] [name]"));
        assert!(help.contains("Extract elements from the HTML snapshot stored in Browser4's page storage (text, html, attr)"));
        assert!(help.contains("What to extract: text, html, or attr"));
        assert!(help.contains("Attribute name (required for attr field)"));
        assert!(!help.contains("browser4-cli htmlsnapshot-get"));
    }

    #[test]
    fn test_generate_command_help_htmlsnapshot_query() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "htmlsnapshot-query").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli htmlsnapshot query [url]"));
        assert!(help.contains("Run X-SQL against the HTML snapshot stored in Browser4's page storage via the scrape API"));
        assert!(help.contains("--sql"));
        assert!(help.contains("--sql-stdin"));
        assert!(help.contains("--sql-base64"));
        assert!(help.contains("--result-only"));
        assert!(help.contains("--format"));
        assert!(!help.contains("browser4-cli htmlsnapshot-query"));
        // Examples section
        assert!(help.contains("Examples:"));
        assert!(help.contains("--sql @query.sql"));
        assert!(help.contains("--sql-stdin < query.sql"));
        assert!(help.contains("--sql-base64 \"$(base64"));
        assert!(help.contains("--result-only"));
        assert!(help.contains("--format table"));
    }

    #[test]
    fn test_generate_command_help_htmlsnapshot_export() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "htmlsnapshot-export").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli htmlsnapshot export"));
        assert!(help.contains("Export snapshot HTML from Browser4's page storage to a local file"));
        assert!(help.contains("--file"));
        assert!(help.contains("--clean"));
        assert!(!help.contains("browser4-cli htmlsnapshot-export"));
    }

    #[test]
    fn test_generate_command_help_htmlsnapshot_summary() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "htmlsnapshot-summary").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli htmlsnapshot summary"));
        assert!(help.contains("Summarize: read the stored HTML snapshot and produce a compressed Web Page Summary Index (WPSI)"));
        assert!(!help.contains("browser4-cli htmlsnapshot-summary"));
    }

    #[test]
    fn test_generate_command_help_htmlsnapshot_get_all() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "htmlsnapshot-get-all").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli htmlsnapshot get all <field> [selector] [name]"));
        assert!(help.contains("Extract ALL matching elements from the HTML snapshot (querySelectorAll semantics)"));
        assert!(help.contains("What to extract: text, html, or attr"));
        assert!(help.contains("Attribute name (required for attr field)"));
        assert!(help.contains("--offset"));
        assert!(help.contains("--limit"));
        assert!(!help.contains("browser4-cli htmlsnapshot-get-all"));
    }

    #[test]
    fn test_generate_command_help_preserves_argument_casing() {
        let cmds = all_commands();

        let press = cmds.iter().find(|c| c.name == "press").unwrap();
        let press_help = generate_command_help(press);
        assert!(press_help.contains("`ArrowLeft`"));

        let eval = cmds.iter().find(|c| c.name == "eval").unwrap();
        let eval_help = generate_command_help(eval);
        assert!(eval_help.contains("JavaScript expression or function to evaluate"));
    }

    #[test]
    fn test_generate_command_help_attach() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "attach").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli attach"));
        assert!(help.contains("CDP (Chrome DevTools Protocol)"));
        assert!(help.contains("--cdp"));
        assert!(help.contains("--endpoint"));
        assert!(help.contains("browser4-cli attach --cdp http://localhost:9222"));
        assert!(help.contains("browser4-cli attach --cdp chrome"));
        assert!(help.contains("browser4-cli attach --extension"));
        assert!(help.contains("browser4-cli attach --extension chrome-canary"));
        assert!(help.contains("browser4-cli attach --extension msedge"));
    }

    #[test]
    fn test_generate_command_help_crawl() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "crawl").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli crawl [url]"));
        assert!(help.contains("Crawl a website starting from a URL or seed file"));
        // Modes section
        assert!(help.contains("Link discovery"));
        assert!(help.contains("Bulk fetch"));
        assert!(help.contains("X-SQL extraction"));
        // Core flags
        assert!(help.contains("--depth (-d)"));
        assert!(help.contains("--out-link-selector (-ol)"));
        assert!(help.contains("--out-link-pattern (-olp)"));
        assert!(help.contains("--top-links (-tl)"));
        // New features
        assert!(help.contains("--sql"));
        assert!(help.contains("--sql-stdin"));
        assert!(help.contains("--format"));
        assert!(help.contains("--output (-o)"));
        assert!(help.contains("--seed-file"));
        // Legacy flags
        assert!(help.contains("--expires"));
        assert!(help.contains("--priority"));
        // Examples
        assert!(help.contains("browser4-cli crawl https://example.com"));
        assert!(help.contains("--depth 3 --refresh"));
        assert!(help.contains("--seed-file urls.txt --depth 0"));
        assert!(help.contains("--sql @extract.sql --format csv -o results.csv"));
        assert!(help.contains("--sql-stdin --format table"));
    }

    #[test]
    fn test_generate_command_help_wait() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "wait").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli wait [target]"));
        assert!(help.contains("Five wait modes"));
        assert!(help.contains("selector — wait for an element"));
        assert!(help.contains("time — wait a fixed number"));
        assert!(help.contains("text — wait until the given text"));
        assert!(help.contains("url — wait until the page URL"));
        assert!(help.contains("load — wait for a page-load state"));
        assert!(help.contains("fn — wait until a custom JavaScript"));
        assert!(help.contains("--timeout"));
        assert!(help.contains("browser4-cli wait --load networkidle"));
        assert!(help.contains("browser4-cli wait --fn"));
    }

    #[test]
    fn test_generate_command_help_get() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "get").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli get <mode> <selector> [name]"));
        assert!(help.contains("six modes"));
        assert!(help.contains("text — returns the visible inner text"));
        assert!(help.contains("html — returns the inner HTML"));
        assert!(help.contains("box — returns the bounding box"));
        assert!(help.contains("styles — returns all computed CSS styles"));
        assert!(help.contains("property — returns a DOM property"));
        assert!(help.contains("attr — returns an HTML attribute"));
        assert!(help.contains("browser4-cli get text \".price\""));
        assert!(help.contains("browser4-cli get property \"input[name=email]\" value"));
    }

    #[test]
    fn test_generate_command_help_generate_locator() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "generate-locator").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli generate-locator <ref>"));
        assert!(help.contains("unique CSS selector path"));
        assert!(help.contains("survive page reloads"));
        assert!(help.contains("browser4-cli generate-locator e5"));
        assert!(help.contains("browser4-cli generate-locator backend:15"));
    }

    #[test]
    fn test_generate_command_help_skills() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "skills").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli skills"));
        assert!(help.contains("List bundled skill names"));
        // Subcommands
        assert!(help.contains("Subcommands:"));
        assert!(help.contains("skills list"));
        assert!(help.contains("skills get <name>"));
        assert!(help.contains("skills get <name> --full"));
        assert!(help.contains("skills get --all"));
        assert!(help.contains("skills path [name]"));
        assert!(help.contains("skills unpack [dest]"));
        // Notes
        assert!(help.contains("BROWSER4_SKILLS_DIR"));
        assert!(help.contains("bundled into the browser4-cli binary"));
        // Examples
        assert!(help.contains("browser4-cli skills get browser4-cli"));
        assert!(help.contains("browser4-cli skills get browser4-cli --full"));
        assert!(help.contains("browser4-cli skills get --all"));
        assert!(help.contains("browser4-cli skills path"));
        assert!(help.contains("browser4-cli skills path browser4-cli"));
        assert!(help.contains("browser4-cli skills unpack"));
        assert!(help.contains("browser4-cli skills unpack /custom/path"));
        assert!(help.contains("BROWSER4_SKILLS_DIR=/custom/path"));
    }

    #[test]
    fn test_generate_help_no_empty_categories() {
        let help = generate_help();
        // These categories should NOT appear since no commands use them
        assert!(!help.contains("\nNetwork:"));
        assert!(!help.contains("\nConfiguration:"));
    }
}
