//! Help text generation for the Browser4 CLI.

use crate::commands::{all_commands, CommandDef};

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
    ("agent", "Agent"),
    ("collective", "Collective"),
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
        format!("browser4-cli {} {}", cmd.name, args_text)
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
            let label = format!("  --{}", opt.name);
            lines.push(format_with_gap(&label, opt.description, 30));
        }
    }

    if cmd.name == "batch" {
        lines.push(String::new());
        lines.push("Notes:".to_string());
        lines.push("  - Quote each subcommand so it is parsed as one batch item.".to_string());
        lines.push("  - Use --bail to stop execution on the first failed subcommand.".to_string());
        lines.push("  - Use --json to read command arrays from stdin JSON payload.".to_string());
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli batch \"open https://playwright.dev\" \"snapshot\"".to_string());
        lines.push(
            "  browser4-cli batch --bail \"open https://playwright.dev\" \"click e1\" \"screenshot\""
                .to_string(),
        );
        lines.push(
            "  echo '[ [\"open\", \"https://playwright.dev\"], [\"snapshot\"] ]' | browser4-cli batch --json"
                .to_string(),
        );
    }

    if cmd.name == "eval" {
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli eval \"document.title\"".to_string());
        lines.push(
            "  browser4-cli eval \"element => element.textContent\" \"#click-target\"".to_string(),
        );
        lines.push("  browser4-cli eval \"element => element.textContent\" e5".to_string());
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
        lines.push("  browser4-cli open".to_string());
        lines.push("  browser4-cli open https://browser4.io/".to_string());
    }

    if cmd.name == "list" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Status shows whether the backend currently reports the saved session as Active, Stale, or Unknown."
                .to_string(),
        );
        lines.push(
            "  - Next open shows whether `browser4-cli open` will Reuse the saved session or Refresh it."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli list".to_string());
    }

    if cmd.name == "goto" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Uses the current active session only; it does not open a new session automatically."
                .to_string(),
        );
        lines.push(
            "  - If the saved session is missing or no longer active, run `browser4-cli open` to create or refresh it."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli open".to_string());
        lines.push("  browser4-cli goto https://browser4.io/".to_string());
    }

    if cmd.name == "co-create" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Creates a collective scrape session and stores the returned session ID in the current CLI slot."
                .to_string(),
        );
        lines.push(
            "  - You can also invoke it as `browser4-cli co create` using the short collective prefix."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli co-create".to_string());
        lines.push(
            "  browser4-cli co create --profile-mode=prototype --max-open-tabs=12 --max-browser-contexts=3 --display-mode=SUPERVISED"
                .to_string(),
        );
    }

    if cmd.name == "co-submit" {
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
            "  - URLs plus load options are forwarded as a raw payload string to `ScrapeController.submit(payload)`."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli co-submit https://example.com/direct".to_string());
        lines.push(
            "  browser4-cli co submit https://example.com/direct --seed-file=./collective-seeds.txt --deadline=2026-03-30T00:00:00Z --expires=1d --refresh --parse --store-content"
                .to_string(),
        );
    }

    if cmd.name == "co-status" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Reads the scrape job status from `ScrapeController.getStatus(id)` and prints the returned JSON payload."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli co-status co-task-4".to_string());
        lines.push("  browser4-cli co status co-task-4".to_string());
    }

    if cmd.name == "co-result" {
        lines.push("Notes:".to_string());
        lines.push(
            "  - Reads the scrape job result from `ScrapeController.getResult(id)` and prints the returned payload."
                .to_string(),
        );
        lines.push(String::new());
        lines.push("Examples:".to_string());
        lines.push("  browser4-cli co-result co-task-4".to_string());
        lines.push("  browser4-cli co result co-task-4".to_string());
    }

    lines.join("\n")
}

fn generate_help_entry(cmd: &CommandDef) -> String {
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

    let prefix = format!("  {} {}", cmd.name, args_text);
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
        assert!(help.contains("ArrowLeft"));
        assert!(help.contains("Evaluate JavaScript expression on page or element"));
        assert!(help.contains("Core:"));
        assert!(!help.contains("  batch "));
        assert!(!help.contains("  console"));
        assert!(!help.contains("  extract"));
        assert!(!help.contains("  agent-run"));
        assert!(!help.contains("  co-create"));
    }

    #[test]
    fn test_generate_command_help_goto() {
        let cmds = all_commands();
        let goto = cmds.iter().find(|c| c.name == "goto").unwrap();
        let help = generate_command_help(goto);
        assert!(help.contains("browser4-cli goto <url>"));
        assert!(help.contains("Navigate to a URL using the current active session"));
        assert!(help.contains("does not open a new session automatically"));
        assert!(help.contains("browser4-cli open"));
    }

    #[test]
    fn test_generate_command_help_open() {
        let cmds = all_commands();
        let open = cmds.iter().find(|c| c.name == "open").unwrap();
        let help = generate_command_help(open);
        assert!(help.contains("browser4-cli open [url]"));
        assert!(help.contains(
            "Open a browser session or refresh the saved one if it is no longer active"
        ));
        assert!(help.contains("backend still reports it as active"));
        assert!(help.contains("creating a new session"));
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
        assert!(help.contains("browser4-cli batch \"open https://playwright.dev\" \"snapshot\""));
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
        assert!(help.contains("browser4-cli eval <expression> [ref]"));
        assert!(help.contains("Evaluate JavaScript expression on page or element"));
        assert!(help.contains("browser4-cli eval \"document.title\""));
        assert!(help.contains("browser4-cli eval \"element => element.textContent\" e5"));
    }

    #[test]
    fn test_generate_command_help_agent_run() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "agent-run").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli agent-run <task>"));
        assert!(help.contains("autonomous agent task"));
    }

    #[test]
    fn test_generate_command_help_co_create() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "co-create").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli co-create"));
        assert!(help.contains("collective scrape session"));
        assert!(help.contains("--profile-mode"));
        assert!(help.contains("--max-open-tabs"));
        assert!(help.contains("--max-browser-contexts"));
        assert!(help.contains("--display-mode"));
        assert!(help.contains("Display mode: GUI, HEADLESS, SUPERVISED"));
        assert!(help.contains("browser4-cli co create"));
    }

    #[test]
    fn test_generate_command_help_co_submit() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "co-submit").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli co-submit"));
        assert!(help.contains("--seed-file"));
        assert!(help.contains("--deadline"));
        assert!(help.contains("--expires"));
        assert!(help.contains("blank lines and lines beginning with `#` are ignored"));
        assert!(help.contains("submits each entry as a scrape job"));
        assert!(help.contains("ScrapeController.submit(payload)"));
        assert!(help.contains("browser4-cli co submit https://example.com/direct"));
    }


    #[test]
    fn test_generate_command_help_co_status_and_result() {
        let cmds = all_commands();

        let status = cmds.iter().find(|c| c.name == "co-status").unwrap();
        let status_help = generate_command_help(status);
        assert!(status_help.contains("browser4-cli co-status <id>"));
        assert!(status_help.contains("scrape job status"));
        assert!(status_help.contains("ScrapeController.getStatus(id)"));
        assert!(status_help.contains("browser4-cli co status co-task-4"));

        let result = cmds.iter().find(|c| c.name == "co-result").unwrap();
        let result_help = generate_command_help(result);
        assert!(result_help.contains("browser4-cli co-result <id>"));
        assert!(result_help.contains("scrape job result"));
        assert!(result_help.contains("ScrapeController.getResult(id)"));
        assert!(result_help.contains("browser4-cli co result co-task-4"));
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
