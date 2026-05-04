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
    // ("storage", "Storage"),
    // ("network", "Network"),
    // ("devtools", "DevTools"),
    // ("agent", "Agent"),
    // ("collective", "Collective"),
    // ("install", "Install"),
    // ("config", "Configuration"),
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
            lines.push(format_with_gap(&label, &arg.description.to_lowercase(), 30));
        }
    }

    if !cmd.options.is_empty() {
        lines.push("Options:".to_string());
        for opt in cmd.options {
            let label = format!("  --{}", opt.name);
            lines.push(format_with_gap(&label, &opt.description.to_lowercase(), 30));
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
    format_with_gap(prefix, &cmd.description.to_lowercase(), 30)
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
        assert!(help.contains("batch"));
        assert!(help.contains("click"));
        assert!(help.contains("snapshot"));
        assert!(help.contains("Core:"));
        // assert!(help.contains("Agent:"));
        // assert!(help.contains("Collective:"));
        // assert!(help.contains("extract"));
        // assert!(help.contains("summarize"));
        // assert!(help.contains("agent-run"));
        // assert!(help.contains("co-create"));
        // assert!(help.contains("co-submit"));
        // assert!(help.contains("co-scrape"));
    }

    #[test]
    fn test_generate_command_help_goto() {
        let cmds = all_commands();
        let goto = cmds.iter().find(|c| c.name == "goto").unwrap();
        let help = generate_command_help(goto);
        assert!(help.contains("browser4-cli goto <url>"));
        assert!(help.contains("Navigate to a URL"));
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
        assert!(help.contains("collective session"));
        assert!(help.contains("--profile-mode"));
        assert!(help.contains("--max-open-tabs"));
        assert!(help.contains("--max-browser-contexts"));
        assert!(help.contains("--display-mode"));
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
    }

    #[test]
    fn test_generate_command_help_co_scrape() {
        let cmds = all_commands();
        let cmd = cmds.iter().find(|c| c.name == "co-scrape").unwrap();
        let help = generate_command_help(cmd);
        assert!(help.contains("browser4-cli co-scrape <url>"));
        assert!(help.contains("--selector"));
        assert!(help.contains("--attribute"));
        assert!(help.contains("--output"));
    }
}
