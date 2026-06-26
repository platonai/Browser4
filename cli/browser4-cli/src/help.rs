//! Help text generation for the Browser4 CLI.

use crate::commands::{all_commands, CommandDef};

pub fn public_command_name(name: &str) -> &str {
    match name {
        "agent-run" => "agent run",
        "agent-status" => "agent status",
        "agent-result" => "agent result",
        "swarm-create" => "swarm create",
        "swarm-submit" => "swarm submit",
        "swarm-query" => "swarm query",
        "swarm-status" => "swarm status",
        "swarm-result" => "swarm result",
        "domsnapshot-get" => "domsnapshot get",
        "domsnapshot-query" => "domsnapshot query",
        "domsnapshot-export" => "domsnapshot export",
        "domsnapshot-summary" => "domsnapshot summary",
        "domsnapshot-grep" => "domsnapshot grep",
        _ => name,
    }
}

/// Categories in display order with their titles.
const CATEGORIES: &[(&str, &str)] = &[
    ("core", "Core"),
    ("navigation", "Navigation"),
    ("keyboard", "Keyboard"),
    ("mouse", "Mouse"),
    ("export", "Save as"),
    ("tabs", "Tabs"),
    ("storage", "Storage"),
    ("network", "Network"),
    ("devtools", "DevTools"),
    ("snapshot", "Snapshot"),
    ("agent", "Agent"),
    ("swarm", "Swarm"),
    ("install", "Install"),
    ("config", "Configuration"),
    ("browsers", "Browser sessions"),
];

/// Generate global help text listing all available commands by category.
pub fn generate_help() -> String {
    let cmds = all_commands();
    let mut lines: Vec<String> = vec![
        "Usage: browser4-cli <command> [args] [options]".to_string(),
        "Usage: browser4-cli -s=<session> <command> [args] [options]".to_string(),
    ];

    for (cat_name, cat_title) in CATEGORIES {
        let cat_cmds: Vec<&CommandDef> = cmds
            .iter()
            .filter(|c| !c.hidden && c.category.as_str() == *cat_name)
            .collect();
        if cat_cmds.is_empty() {
            continue;
        }
        lines.push(format!("\n{}:", cat_title));
        for cmd in cat_cmds {
            lines.push(generate_help_entry(cmd));
        }
    }

    lines.push("\nGlobal options:".to_string());
    lines.push(format_with_gap("  --help [command]", "print help", 30));
    lines.push(format_with_gap("  --version", "print version", 30));
    lines.push(format_with_gap(
        "  --json",
        "emit machine-parseable JSON to stdout",
        30,
    ));
    lines.push(format_with_gap(
        "  -q, --quiet",
        "suppress normal output, only show errors",
        30,
    ));
    lines.push(format_with_gap("  -s=<name>", "named session label", 30));
    lines.push(format_with_gap(
        "  --server=<url>",
        "override Browser4 server URL",
        30,
    ));

    // for developer only
    // lines.push(format_with_gap(
    //     "  --use-maven-startup",
    //     "opt in to local maven spring-boot:run startup",
    //     30,
    // ));

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
        cmd.description.to_string(),
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
            "  - Use --file to read the JavaScript expression from a file, avoiding shell quoting issues."
                .to_string(),
        );
        lines.push(
            "  - When --file is used, the expression positional argument is optional.".to_string(),
        );
        lines.push(
            "  - Return values are always printed: `null` for JS null/undefined, `\"\"` for empty string,"
                .to_string(),
        );
        lines.push(
            "    or the value itself otherwise. JS exceptions are surfaced as errors."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli eval \"document.title\"".to_string());
        lines.push(
            "  browser4-cli eval \"element => element.textContent\" \"#click-target\"".to_string(),
        );
        lines.push("  browser4-cli eval \"element => element.textContent\" e5".to_string());
        lines.push("  browser4-cli eval --file script.js".to_string());
        lines.push("  browser4-cli eval --file script.js e5".to_string());
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

    if cmd.name == "open" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Reuses the saved session for the current slot only when the backend still reports it as active."
                .to_string(),
        );
        lines.push(
            "  - If the saved session is missing or stale, `open` refreshes it by creating a new session."
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
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli install".to_string());
        lines.push("  browser4-cli install --tag=v4.9.3".to_string());
        lines.push("  browser4-cli install --tag=4.9.3 --force".to_string());
    }

    if cmd.name == "upgrade" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Convenience wrapper around `install` that upgrades the runtime to a newer version."
                .to_string(),
        );
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
        lines.push("  browser4-cli upgrade v4.11.0".to_string());
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
        lines.push(
            "  - Next open shows whether `browser4-cli open <url>` will Reuse the saved session or Refresh it (a new session is created only when a URL is provided)."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli list".to_string());
    }

    if cmd.name == "goto" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Reuses the current active session when possible and auto-opens a fresh one when the saved session is missing or stale."
                .to_string(),
        );
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
            "  browser4-cli swarm create --profile-mode=TEMPORARY --max-open-tabs=12 --max-browser-contexts=3 --display-mode=HEADLESS"
                .to_string(),
        );
    }

    if cmd.name == "swarm-submit" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Accepts a direct URL, a `--seed-file`, or both, and submits each entry as a scrape job through the scrape submit API."
                .to_string(),
        );
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
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push(
            "  browser4-cli swarm submit https://example.com/direct --seed-file=./swarm-seeds.txt --deadline=2026-03-30T00:00:00Z --expires=1d --refresh --store-content"
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
        lines.push(
            "  - `--seed-file` takes a direct file path (no `@` prefix); only `--sql` uses `@` to disambiguate inline X-SQL from file paths."
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
            "  browser4-cli swarm query --sql @query.sql --seed-file=./swarm-seeds.txt --refresh"
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
        lines.push("  browser4-cli swarm status scrape-task-4".to_string());
    }

    if cmd.name == "swarm-result" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Reads the scrape job result from `SwarmController.getResult(id)` and prints the returned payload."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli swarm result scrape-task-4".to_string());
    }

    if cmd.name == "domsnapshot" {
        lines.push("Subcommands:".to_string());
        lines.push(format_with_gap(
            "  domsnapshot get <field> [selector] [name]",
            "Extract elements from the DOM snapshot stored in Browser4's page storage (text, html, attr)",
            50,
        ));
        lines.push(format_with_gap(
            "  domsnapshot query [url]",
            "Run X-SQL against the DOM snapshot stored in Browser4's page storage via the scrape API",
            50,
        ));
        lines.push(format_with_gap(
            "  domsnapshot export",
            "Export snapshot HTML from Browser4's page storage to a local file",
            50,
        ));
        lines.push(format_with_gap(
            "  domsnapshot summary",
            "Generate a compressed Web Page Summary Index (WPSI) from the stored DOM snapshot",
            50,
        ));
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push(
            "  - The base `domsnapshot` command captures a static DOM snapshot, saves it in Browser4's page storage, and returns metadata (URL, title, timestamp)."
                .to_string(),
        );
        lines.push(
            "  - After capturing, extract elements from the stored snapshot by CSS selector with `domsnapshot get <field> [selector]`."
                .to_string(),
        );
        lines.push(
            "  - The `get` subcommand supports three fields: `text` (inner text), `html` (inner HTML), and `attr` (attribute value)."
                .to_string(),
        );
        lines.push(
            "  - Element references (`e5`, `backend:15`) are NOT supported by `domsnapshot get` — use CSS selectors only."
                .to_string(),
        );
        lines.push(
            "  - X-SQL queries via `domsnapshot query --sql` use `@url` as a placeholder for the target page URL (unquoted — SQLTemplate handles escaping)."
                .to_string(),
        );
        lines.push(
            "  - `domsnapshot query --sql` also supports reading from a file with `--sql @file.sql`."
                .to_string(),
        );
        lines.push(
            "  - Export the full HTML snapshot from Browser4's page storage to a local file with `domsnapshot export --file <path>`."
                .to_string(),
        );
        lines.push(
            "  - Generate a compressed page summary (WPSI) from the stored DOM snapshot with `domsnapshot summary`. The summary identifies page type, structure, key content nodes, repeated lists, tables, and stats — typically <1% of the original HTML size."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  # Capture a DOM snapshot and display metadata".to_string());
        lines.push("  browser4-cli domsnapshot".to_string());
        lines.push(String::new());
        lines.push("  # Get the text content of the whole page (:root)".to_string());
        lines.push("  browser4-cli domsnapshot get text".to_string());
        lines.push(String::new());
        lines.push("  # Get the HTML of a specific element by CSS selector".to_string());
        lines.push("  browser4-cli domsnapshot get html \"#main-content\"".to_string());
        lines.push(String::new());
        lines.push("  # Get an attribute value (requires attribute name)".to_string());
        lines.push("  browser4-cli domsnapshot get attr \"a.product-link\" href".to_string());
        lines.push(String::new());
        lines.push("  # Run an X-SQL query against the current page URL".to_string());
        lines.push(
            "  browser4-cli domsnapshot query --sql \"SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, ':root')\""
                .to_string(),
        );
        lines.push(String::new());
        lines.push("  # Run an X-SQL query from a file".to_string());
        lines.push("  browser4-cli domsnapshot query --sql @query.sql".to_string());
        lines.push(String::new());
        lines.push("  # Export the full snapshot HTML to a file".to_string());
        lines.push("  browser4-cli domsnapshot export --file snapshot.html".to_string());
        lines.push(String::new());
        lines.push("  # Generate a compressed page summary from the stored DOM snapshot".to_string());
        lines.push("  browser4-cli domsnapshot summary".to_string());
    }

    lines.join("\n")
}

pub fn generate_help_entry(cmd: &CommandDef) -> String {
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

    let prefix = format!("  {} {}", public_command_name(cmd.name), args_text);
    let prefix = prefix.trim_end();
    format_with_gap(prefix, cmd.description, 30)
}

fn format_with_gap(prefix: &str, text: &str, threshold: usize) -> String {
    let gap = if prefix.len() < threshold {
        threshold - prefix.len()
    } else {
        1
    };
    format!("{}{}{}", prefix, " ".repeat(gap), text)
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
        assert!(!help.contains("  console "));
        assert!(help.contains("extract"));
        assert!(!help.contains("agent run"));
        assert!(help.contains("swarm create"));
        assert!(help.contains("--json"));
        assert!(help.contains("machine-parseable JSON"));
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
            .contains("Open a browser session or refresh the saved one if it is no longer active"));
        assert!(help.contains("backend still reports it as active"));
        assert!(help.contains("creating a new session"));
    }

    #[test]
    fn test_generate_command_help_install() {
        let cmds = all_commands();
        let install = cmds.iter().find(|c| c.name == "install").unwrap();
        let help = generate_command_help(install);
        assert!(help.contains("browser4-cli install"));
        assert!(help.contains("self-contained Browser4 runtime bundle"));
        assert!(help.contains("dependency jars, a minimal `jlink`-built JRE"));
        assert!(help.contains("browser4-cli install --tag=v4.9.3"));
        assert!(help.contains("--force"));
        assert!(help.contains("configured download mirrors"));
    }

    #[test]
    fn test_generate_command_help_upgrade() {
        let cmds = all_commands();
        let upgrade = cmds.iter().find(|c| c.name == "upgrade").unwrap();
        let help = generate_command_help(upgrade);
        assert!(help.contains("browser4-cli upgrade"));
        assert!(help.contains("Convenience wrapper around `install`"));
        assert!(help.contains("restart the server"));
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
        assert!(help.contains("browser4-cli eval \"element => element.textContent\" e5"));
        assert!(help.contains("--file"));
        assert!(help.contains("Read JavaScript expression from a file"));
        assert!(help.contains("browser4-cli eval --file script.js"));
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
        assert!(status_help.contains("browser4-cli swarm status scrape-task-4"));
        assert!(!status_help.contains("browser4-cli swarm-status"));

        let result = cmds.iter().find(|c| c.name == "swarm-result").unwrap();
        let result_help = generate_command_help(result);
        assert!(result_help.contains("browser4-cli swarm result <id>"));
        assert!(result_help.contains("scrape job result"));
        assert!(result_help.contains("SwarmController.getResult(id)"));
        assert!(result_help.contains("browser4-cli swarm result scrape-task-4"));
        assert!(!result_help.contains("browser4-cli swarm-result"));
    }

    #[test]
    fn test_generate_command_help_domsnapshot() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "domsnapshot").unwrap();
        let help = generate_command_help(cmd);
        // Header
        assert!(help.contains("browser4-cli domsnapshot"));
        assert!(help.contains("Capture a static DOM snapshot, save it in Browser4's page storage"));
        // Subcommands listing
        assert!(help.contains("Subcommands:"));
        assert!(help.contains("domsnapshot get <field> [selector] [name]"));
        assert!(help.contains("Extract elements from the DOM snapshot stored in Browser4's page storage (text, html, attr)"));
        assert!(help.contains("domsnapshot query [url]"));
        assert!(help.contains("Run X-SQL against the DOM snapshot stored in Browser4's page storage via the scrape API"));
        assert!(help.contains("domsnapshot export"));
        assert!(help.contains("Export snapshot HTML from Browser4's page storage to a local file"));
        assert!(help.contains("domsnapshot summary"));
        assert!(help.contains("Generate a compressed Web Page Summary Index (WPSI) from the stored DOM snapshot"));
        // Notes
        assert!(help.contains("static DOM snapshot, saves it in Browser4's page storage, and returns metadata"));
        assert!(help.contains("CSS selectors only"));
        assert!(help.contains("@url"));
        assert!(help.contains("SQLTemplate handles escaping"));
        assert!(help.contains("@file.sql"));
        // Examples
        assert!(help.contains("browser4-cli domsnapshot"));
        assert!(help.contains("browser4-cli domsnapshot get text"));
        assert!(help.contains("browser4-cli domsnapshot get html \"#main-content\""));
        assert!(help.contains("browser4-cli domsnapshot get attr \"a.product-link\" href"));
        assert!(help.contains("browser4-cli domsnapshot query --sql"));
        assert!(help.contains("browser4-cli domsnapshot query --sql @query.sql"));
        assert!(help.contains("browser4-cli domsnapshot export --file snapshot.html"));
        assert!(help.contains("browser4-cli domsnapshot summary"));
    }

    #[test]
    fn test_generate_command_help_domsnapshot_get() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "domsnapshot-get").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli domsnapshot get <field> [selector] [name]"));
        assert!(help.contains("Extract elements from the DOM snapshot stored in Browser4's page storage (text, html, attr)"));
        assert!(help.contains("What to extract: text, html, or attr"));
        assert!(help.contains("Attribute name (required for attr field)"));
        assert!(!help.contains("browser4-cli domsnapshot-get"));
    }

    #[test]
    fn test_generate_command_help_domsnapshot_query() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "domsnapshot-query").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli domsnapshot query [url]"));
        assert!(help.contains("Run X-SQL against the DOM snapshot stored in Browser4's page storage via the scrape API"));
        assert!(help.contains("--sql"));
        assert!(!help.contains("browser4-cli domsnapshot-query"));
    }

    #[test]
    fn test_generate_command_help_domsnapshot_export() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "domsnapshot-export").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli domsnapshot export"));
        assert!(help.contains("Export snapshot HTML from Browser4's page storage to a local file"));
        assert!(help.contains("--file"));
        assert!(!help.contains("browser4-cli domsnapshot-export"));
    }

    #[test]
    fn test_generate_command_help_domsnapshot_summary() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "domsnapshot-summary").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli domsnapshot summary"));
        assert!(help.contains("Generate a compressed Web Page Summary Index (WPSI) from the stored DOM snapshot"));
        assert!(!help.contains("browser4-cli domsnapshot-summary"));
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
}
