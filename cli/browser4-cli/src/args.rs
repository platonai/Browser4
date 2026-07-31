//! Argument parsing helpers for the Browser4 CLI.
//!
//! Parses raw command-line arguments into:
//! - Global flags (`-s <session>`, `--session <session>`, `--server <url>`)
//! - Positional arguments (stored in `_`)
//! - Named options (`--key value`, `--flag`)

use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

/// Parsed global flags that appear before the command name.
#[derive(Debug, Default, Clone)]
pub struct GlobalFlags {
    /// `-s` / `--session` requested session identifier
    pub session_name: Option<String>,
    /// `--server <url>` or `--server=<url>` server override
    pub server_url: Option<String>,
    /// `--json` — emit machine-parseable JSON to stdout
    pub json: bool,
    /// `-q` / `--quiet` — suppress normal output, only show errors
    pub quiet: bool,
    /// `--proxy <url>` or `--proxy=<url>` — manual HTTP proxy for downloads
    pub proxy_url: Option<String>,
    /// `--show-tip` / `-tip` — show relevant tips on stderr after commands
    pub show_tip: bool,
    /// `--pretty` — pretty-print JSON output
    pub pretty: bool,
    /// `--help-json` — emit command reference as machine-readable JSON
    pub help_json: bool,
    /// `--timeout <seconds>` — override the default HTTP timeout for tool calls
    pub timeout_secs: Option<u64>,
    /// Remaining arguments (command + its args/options)
    pub args: Vec<String>,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct BatchArgs {
    pub bail: bool,
    pub json: bool,
    pub commands: Vec<String>,
}

/// Parse global flags that may appear before the command.
///
/// Recognises:
/// - `-s <name>`, `-s=<name>`, `--session <name>`, `--session=<name>` → session name
/// - `--server <url>` or `--server=<url>` → server URL override
/// - `--proxy <url>` or `--proxy=<url>` → manual HTTP proxy override for downloads
/// - `--json` → emit machine-parseable JSON to stdout
/// - `--show-tip` / `-tip` → show relevant tips on stderr after commands
/// - `--version` / `-v` → version flag (returned in `args`)
/// - Everything else is forwarded unchanged in `args`
pub fn parse_global_flags(argv: &[String]) -> GlobalFlags {
    let mut flags = GlobalFlags::default();

    // Default session name from environment variable
    if let Ok(env_session) = std::env::var("BROWSER4_CLI_SESSION") {
        if !env_session.is_empty() {
            flags.session_name = Some(env_session);
        }
    }

    // `--json` is only a global flag when it appears *before* the command
    // name.  After the command it is passed through so sub-commands like
    // `batch --json` (stdin JSON input) are not shadowed by the global flag.
    let mut seen_command = false;

    let mut i = 0;
    while i < argv.len() {
        let arg = &argv[i];
        if arg.starts_with("-s=") {
            if !seen_command {
                flags.session_name = Some(arg["-s=".len()..].to_string());
            }
        } else if arg.starts_with("--session=") {
            if !seen_command {
                flags.session_name = Some(arg["--session=".len()..].to_string());
            }
        } else if arg == "-s" || arg == "--session" {
            if !seen_command {
                if i + 1 < argv.len() && !argv[i + 1].starts_with('-') {
                    i += 1;
                    flags.session_name = Some(argv[i].clone());
                }
            }
        } else if !seen_command && arg == "--json" {
            flags.json = true;
        } else if !seen_command && (arg == "-q" || arg == "--quiet") {
            flags.quiet = true;
        } else if !seen_command && (arg == "--show-tip" || arg == "-tip") {
            flags.show_tip = true;
        } else if !seen_command && arg == "--pretty" {
            flags.pretty = true;
        } else if !seen_command && arg == "--help-json" {
            flags.help_json = true;
        } else if arg.starts_with("--timeout=") {
            flags.timeout_secs = arg["--timeout=".len()..].parse().ok();
        } else if arg == "--timeout" {
            if i + 1 < argv.len() {
                i += 1;
                flags.timeout_secs = argv[i].parse().ok();
            }
        } else if arg.starts_with("--server=") {
            flags.server_url = Some(arg["--server=".len()..].to_string());
        } else if arg == "--server" {
            if i + 1 < argv.len() && !argv[i + 1].starts_with('-') {
                i += 1;
                flags.server_url = Some(argv[i].clone());
            }
        } else if !seen_command && arg.starts_with("--proxy=") {
            flags.proxy_url = Some(arg["--proxy=".len()..].to_string());
        } else if !seen_command && arg == "--proxy" {
            if i + 1 < argv.len() && !argv[i + 1].starts_with('-') {
                i += 1;
                flags.proxy_url = Some(argv[i].clone());
            }
        } else {
            // First non-flag argument is the command name.
            if !arg.starts_with('-') {
                seen_command = true;
            }
            flags.args.push(arg.clone());
        }
        i += 1;
    }
    flags
}

/// Build a mapping from short option names (e.g. `"y"`) to their long
/// equivalent (e.g. `"yes"`) for a given command's option definitions.
///
/// Returns a tuple of `(short_to_long_map, boolean_option_names)`.
/// The boolean set is used by `parse_raw_args` to avoid consuming the next
/// argument as a value for boolean flags (e.g. `-i` should not consume
/// `"search"` in `snapshot grep -i "search"`).
pub fn build_short_option_map(options: &[crate::commands::OptionDef]) -> (HashMap<String, String>, HashSet<String>) {
    let mut map = HashMap::new();
    let mut bool_opts = HashSet::new();
    for opt in options {
        if opt.is_bool {
            bool_opts.insert(opt.name.to_string());
        }
        if let Some(short) = opt.short {
            map.insert(short.to_string(), opt.name.to_string());
        }
    }
    (map, bool_opts)
}

/// Insert a key-value pair into the result map, collecting repeated non-boolean
/// options into a JSON array so that repeatable flags like `-e PAT1 -e PAT2`
/// accumulate instead of the second overwriting the first.
fn insert_arg(result: &mut HashMap<String, Value>, key: String, value: Value) {
    if value.is_boolean() {
        // Boolean flags: repeated flags don't accumulate (e.g. -i -i is still just true).
        result.insert(key, value);
        return;
    }
    match result.remove(&key) {
        Some(Value::Array(mut arr)) => {
            arr.push(value);
            result.insert(key, Value::Array(arr));
        }
        Some(existing) => {
            result.insert(key, Value::Array(vec![existing, value]));
        }
        None => {
            result.insert(key, value);
        }
    }
}

/// Parse raw CLI arguments into a map suitable for command dispatch.
///
/// - Positional arguments go into `_` as a JSON array.
/// - `--key value` → key: string value
/// - `--key value` (next arg does not start with `--`) → key: string value
/// - `--flag` (followed by another `--` arg or end-of-args) → key: true (boolean)
/// - Short options (`-x` / `-x=value` / `-x value`) are resolved through
///   `short_to_long` when provided.
/// - `bool_opts` contains the long names of boolean options; when a boolean
///   option (short or long) is encountered, the next argument is NOT consumed
///   as its value — it stays positional.
/// - Values `"true"` / `"false"` are coerced to booleans.
/// - Repeated non-boolean options are collected into a JSON array
///   (e.g. `-e foo -e bar` → `"regexp": ["foo", "bar"]`).
pub fn parse_raw_args(
    raw_args: &[String],
    short_to_long: Option<&HashMap<String, String>>,
    bool_opts: Option<&HashSet<String>>,
) -> HashMap<String, Value> {
    let mut result: HashMap<String, Value> = HashMap::new();
    let mut positional: Vec<Value> = Vec::new();

    let mut i = 0;
    while i < raw_args.len() {
        let arg = &raw_args[i];
        if let Some(rest) = arg.strip_prefix("--") {
            if let Some(eq) = rest.find('=') {
                // --key=value
                let key = rest[..eq].to_string();
                let val = &rest[eq + 1..];
                let value = match val {
                    "true" => Value::Bool(true),
                    "false" => Value::Bool(false),
                    other => Value::String(other.to_string()),
                };
                insert_arg(&mut result, key, value);
            } else {
                // Check if this is a known boolean option — if so, don't consume
                // the next argument as a value.
                let is_bool = bool_opts.map_or(false, |b| b.contains(rest));
                // Look ahead: if the next argument does NOT start with `--`,
                // treat it as this option's value rather than a positional.
                if !is_bool && i + 1 < raw_args.len() && !raw_args[i + 1].starts_with("--") {
                    let key = rest.to_string();
                    let val = &raw_args[i + 1];
                    let value = match val.as_str() {
                        "true" => Value::Bool(true),
                        "false" => Value::Bool(false),
                        other => Value::String(other.to_string()),
                    };
                    insert_arg(&mut result, key, value);
                    i += 1; // consume the value
                } else {
                    insert_arg(&mut result, rest.to_string(), Value::Bool(true));
                }
            }
        } else if let Some(rest) = arg.strip_prefix('-') {
            // Short option: -x, -x=value, or -x value
            if let Some(map) = short_to_long {
                if let Some(eq) = rest.find('=') {
                    let short = &rest[..eq];
                    let val = &rest[eq + 1..];
                    if let Some(long) = map.get(short) {
                        let value = match val {
                            "true" => Value::Bool(true),
                            "false" => Value::Bool(false),
                            other => Value::String(other.to_string()),
                        };
                        insert_arg(&mut result, long.clone(), value);
                    } else {
                        positional.push(json!(arg));
                    }
                } else if let Some(long) = map.get(rest) {
                    // Check if this is a boolean option — if so, don't consume
                    // the next argument as a value.
                    let is_bool = bool_opts.map_or(false, |b| b.contains(long));
                    // Look ahead: if the next argument does NOT start with `-`,
                    // treat it as this option's value rather than a positional.
                    if !is_bool && i + 1 < raw_args.len() && !raw_args[i + 1].starts_with('-') {
                        let val = &raw_args[i + 1];
                        let value = match val.as_str() {
                            "true" => Value::Bool(true),
                            "false" => Value::Bool(false),
                            other => Value::String(other.to_string()),
                        };
                        insert_arg(&mut result, long.clone(), value);
                        i += 1; // consume the value
                    } else {
                        insert_arg(&mut result, long.clone(), Value::Bool(true));
                    }
                } else {
                    positional.push(json!(arg));
                }
            } else {
                positional.push(json!(arg));
            }
        } else {
            positional.push(json!(arg));
        }
        i += 1;
    }
    result.insert("_".to_string(), Value::Array(positional));
    result
}

/// Build a flat argument map from parsed raw args for use in command dispatch.
///
/// Positional arguments are mapped to their named positions as defined in
/// `arg_names` (starting from index 1 since index 0 is the command name).
/// Returns an error string if too many positional arguments are supplied.
pub fn build_command_args(
    raw: &HashMap<String, Value>,
    arg_names: &[&str],
) -> Result<HashMap<String, Value>, String> {
    let mut result = raw.clone();

    let positional: Vec<String> = match raw.get("_") {
        Some(Value::Array(arr)) => arr
            .iter()
            .skip(1) // skip command name
            .map(|v| v.as_str().unwrap_or("").to_string())
            .collect(),
        _ => vec![],
    };

    if positional.len() > arg_names.len() {
        return Err(format!(
            "error: too many arguments: expected {}, received {}",
            arg_names.len(),
            positional.len()
        ));
    }

    for (i, name) in arg_names.iter().enumerate() {
        if i < positional.len() {
            if let Ok(n) = positional[i].parse::<i64>() {
                result.insert(name.to_string(), json!(n));
            } else if let Ok(n) = positional[i].parse::<f64>() {
                result.insert(name.to_string(), json!(n));
            } else {
                result.insert(name.to_string(), json!(positional[i]));
            }
        }
    }

    Ok(result)
}

/// Parse `browser4-cli batch` flags and positional command strings.
pub fn parse_batch_args(raw_args: &[String]) -> Result<BatchArgs, String> {
    let mut parsed = BatchArgs::default();
    let mut parsing_options = true;

    for arg in raw_args {
        if parsing_options {
            match arg.as_str() {
                "--" => {
                    parsing_options = false;
                    continue;
                }
                "--bail" => {
                    parsed.bail = true;
                    continue;
                }
                "--json" => {
                    parsed.json = true;
                    continue;
                }
                _ => parsing_options = false,
            }
        }
        parsed.commands.push(arg.clone());
    }

    if parsed.json && !parsed.commands.is_empty() {
        return Err("Batch --json mode does not accept positional command arguments.".to_string());
    }

    if !parsed.json && parsed.commands.is_empty() {
        return Err(
            "Batch requires at least one command argument or JSON input via --json.".to_string(),
        );
    }

    Ok(parsed)
}

/// Split a single batch command string into CLI tokens, honoring simple shell-style
/// single quotes, double quotes, and backslash escaping.
pub fn parse_command_string(command: &str) -> Result<Vec<String>, String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    let mut chars = command.chars().peekable();
    let mut in_single = false;
    let mut in_double = false;
    let mut escaped = false;
    let mut token_started = false;

    while let Some(ch) = chars.next() {
        if escaped {
            current.push(ch);
            escaped = false;
            token_started = true;
            continue;
        }

        match ch {
            '\\' if !in_single => escaped = true,
            '\'' if !in_double => {
                in_single = !in_single;
                token_started = true;
            }
            '"' if !in_single => {
                in_double = !in_double;
                token_started = true;
            }
            c if c.is_whitespace() && !in_single && !in_double => {
                if token_started {
                    tokens.push(std::mem::take(&mut current));
                    token_started = false;
                }
                while let Some(next) = chars.peek() {
                    if next.is_whitespace() {
                        chars.next();
                    } else {
                        break;
                    }
                }
            }
            _ => {
                current.push(ch);
                token_started = true;
            }
        }
    }

    if escaped {
        return Err("Command ends with an unfinished escape sequence.".to_string());
    }
    if in_single || in_double {
        return Err("Command has an unclosed quote.".to_string());
    }
    if token_started {
        tokens.push(current);
    }
    if tokens.is_empty() {
        return Err("Batch command entries cannot be empty.".to_string());
    }

    Ok(tokens)
}

/// Parse JSON stdin for `browser4-cli batch --json`.
///
/// Accepts a JSON array where each entry is either:
/// - a command string, e.g. `"open https://example.com"`
/// - an array of string arguments, e.g. `["open", "https://example.com"]`
pub fn parse_batch_json_commands(input: &str) -> Result<Vec<Vec<String>>, String> {
    let value: Value =
        serde_json::from_str(input).map_err(|e| format!("Invalid batch JSON input: {e}"))?;
    let entries = value
        .as_array()
        .ok_or_else(|| "Batch JSON input must be an array.".to_string())?;

    let mut commands = Vec::with_capacity(entries.len());
    for (index, entry) in entries.iter().enumerate() {
        let tokens = match entry {
            Value::String(command) => parse_command_string(command)
                .map_err(|e| format!("Invalid batch command at index {index}: {e}"))?,
            Value::Array(parts) => {
                let mut tokens = Vec::with_capacity(parts.len());
                for part in parts {
                    let part = part.as_str().ok_or_else(|| {
                        format!(
                            "Batch JSON command at index {index} must contain only string arguments."
                        )
                    })?;
                    tokens.push(part.to_string());
                }
                if tokens.is_empty() {
                    return Err(format!(
                        "Batch JSON command at index {index} must not be empty."
                    ));
                }
                tokens
            }
            _ => {
                return Err(format!(
                    "Batch JSON command at index {index} must be a string or string array."
                ));
            }
        };
        commands.push(tokens);
    }

    if commands.is_empty() {
        return Err("Batch JSON input must contain at least one command.".to_string());
    }

    Ok(commands)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_global_flags_session_name() {
        let argv = vec![
            "-s=mysession".to_string(),
            "goto".to_string(),
            "https://example.com".to_string(),
        ];
        let flags = parse_global_flags(&argv);
        assert_eq!(flags.session_name.as_deref(), Some("mysession"));
        assert_eq!(flags.args, vec!["goto", "https://example.com"]);
    }

    #[test]
    fn test_parse_global_flags_session_name_with_space() {
        let argv = vec![
            "-s".to_string(),
            "mysession".to_string(),
            "goto".to_string(),
        ];

        let flags = parse_global_flags(&argv);

        assert_eq!(flags.session_name.as_deref(), Some("mysession"));
        assert_eq!(flags.args, vec!["goto"]);
    }

    #[test]
    fn test_parse_global_flags_long_session_name_equals() {
        let argv = vec!["--session=mysession".to_string(), "goto".to_string()];

        let flags = parse_global_flags(&argv);

        assert_eq!(flags.session_name.as_deref(), Some("mysession"));
        assert_eq!(flags.args, vec!["goto"]);
    }

    #[test]
    fn test_parse_global_flags_long_session_name_with_space() {
        let argv = vec![
            "--session".to_string(),
            "mysession".to_string(),
            "goto".to_string(),
        ];

        let flags = parse_global_flags(&argv);

        assert_eq!(flags.session_name.as_deref(), Some("mysession"));
        assert_eq!(flags.args, vec!["goto"]);
    }

    #[test]
    fn test_parse_global_flags_server_equals() {
        let argv = vec![
            "--server=http://localhost:9090".to_string(),
            "open".to_string(),
        ];
        let flags = parse_global_flags(&argv);
        assert_eq!(flags.server_url.as_deref(), Some("http://localhost:9090"));
        assert_eq!(flags.args, vec!["open"]);
    }

    #[test]
    fn test_parse_global_flags_server_space() {
        let argv = vec![
            "--server".to_string(),
            "http://localhost:9090".to_string(),
            "open".to_string(),
        ];
        let flags = parse_global_flags(&argv);
        assert_eq!(flags.server_url.as_deref(), Some("http://localhost:9090"));
        assert_eq!(flags.args, vec!["open"]);
    }

    #[test]
    fn test_parse_raw_args_positional() {
        let raw = vec!["goto".to_string(), "https://example.com".to_string()];
        let map = parse_raw_args(&raw, None, None);
        let pos = map["_"].as_array().unwrap();
        assert_eq!(pos[0].as_str(), Some("goto"));
        assert_eq!(pos[1].as_str(), Some("https://example.com"));
    }

    #[test]
    fn test_parse_raw_args_options() {
        let raw = vec![
            "click".to_string(),
            "e15".to_string(),
            "--submit=true".to_string(),
        ];
        let map = parse_raw_args(&raw, None, None);
        assert_eq!(map.get("submit"), Some(&json!(true)));
    }

    #[test]
    fn test_parse_raw_args_bool_flag() {
        let raw = vec!["snapshot".to_string(), "--headed".to_string()];
        let map = parse_raw_args(&raw, None, None);
        assert_eq!(map.get("headed"), Some(&json!(true)));
    }

    #[test]
    fn test_parse_raw_args_key_value_space() {
        let raw = vec![
            "install".to_string(),
            "--tag".to_string(),
            "4.10.0-rc.2".to_string(),
        ];
        let map = parse_raw_args(&raw, None, None);
        // --tag value should be parsed as a key-value pair, not a boolean flag
        // plus a positional argument.
        assert_eq!(map.get("tag"), Some(&json!("4.10.0-rc.2")));
        // The positional list should only contain the command name, not the
        // tag value.
        let pos = map["_"].as_array().unwrap();
        assert_eq!(pos.len(), 1);
        assert_eq!(pos[0].as_str(), Some("install"));
    }

    #[test]
    fn test_parse_raw_args_key_value_equals() {
        // --key value should still work alongside --key=value.
        let raw = vec!["install".to_string(), "--tag=4.10.0-rc.2".to_string()];
        let map = parse_raw_args(&raw, None, None);
        assert_eq!(map.get("tag"), Some(&json!("4.10.0-rc.2")));
    }

    #[test]
    fn test_parse_raw_args_bool_flag_before_positional() {
        // --force (boolean) followed by a positional arg should NOT consume
        // the positional as the flag's value.
        let raw = vec![
            "install".to_string(),
            "--force".to_string(),
            "--tag".to_string(),
            "4.10.0-rc.2".to_string(),
        ];
        let map = parse_raw_args(&raw, None, None);
        assert_eq!(map.get("force"), Some(&json!(true)));
        assert_eq!(map.get("tag"), Some(&json!("4.10.0-rc.2")));
    }

    #[test]
    fn test_parse_raw_args_boolean_short_flag_does_not_consume_next_arg() {
        // Regression test: `snapshot grep -i "search"` — the -i flag should
        // be parsed as --ignore-case=true, and "search" should stay as a
        // positional argument (the pattern), not be consumed as -i's value.
        let short_to_long: HashMap<String, String> = [
            ("i".to_string(), "ignore-case".to_string()),
            ("v".to_string(), "invert-match".to_string()),
            ("c".to_string(), "count".to_string()),
            ("F".to_string(), "fixed-strings".to_string()),
            ("w".to_string(), "word-regexp".to_string()),
            ("l".to_string(), "files-with-matches".to_string()),
            ("A".to_string(), "after-context".to_string()),
            ("B".to_string(), "before-context".to_string()),
            ("C".to_string(), "context".to_string()),
        ]
        .into_iter()
        .collect();
        let bool_opts: HashSet<String> = [
            "ignore-case", "invert-match", "count", "fixed-strings",
            "word-regexp", "files-with-matches",
        ]
        .into_iter()
        .map(String::from)
        .collect();

        // Scenario: snapshot-grep -i "search"
        let raw = vec![
            "snapshot-grep".to_string(),
            "-i".to_string(),
            "search".to_string(),
        ];
        let map = parse_raw_args(&raw, Some(&short_to_long), Some(&bool_opts));
        // -i should be parsed as ignore-case=true (boolean flag)
        assert_eq!(map.get("ignore-case"), Some(&json!(true)));
        // "search" should be kept as a positional argument
        let pos = map["_"].as_array().unwrap();
        assert_eq!(pos.len(), 2, "expected 2 positionals: command + pattern");
        assert_eq!(pos[0].as_str(), Some("snapshot-grep"));
        assert_eq!(pos[1].as_str(), Some("search"), "pattern should be 'search', not consumed by -i");
    }

    #[test]
    fn test_parse_raw_args_non_boolean_short_flag_still_consumes_value() {
        // Non-boolean short flags like -A, -B, -C should still consume
        // their next argument as a value.
        let short_to_long: HashMap<String, String> = [
            ("A".to_string(), "after-context".to_string()),
            ("C".to_string(), "context".to_string()),
        ]
        .into_iter()
        .collect();
        let bool_opts: HashSet<String> = HashSet::new(); // neither is boolean

        let raw = vec![
            "snapshot-grep".to_string(),
            "-A".to_string(),
            "3".to_string(),
            "search".to_string(),
        ];
        let map = parse_raw_args(&raw, Some(&short_to_long), Some(&bool_opts));
        assert_eq!(map.get("after-context"), Some(&json!("3")));
        // "search" should still be positional
        let pos = map["_"].as_array().unwrap();
        assert_eq!(pos.len(), 2);
        assert_eq!(pos[1].as_str(), Some("search"));
    }

    #[test]
    fn test_build_command_args_too_many() {
        let mut raw = HashMap::new();
        raw.insert("_".to_string(), json!(["cmd", "a", "b", "c"]));
        let result = build_command_args(&raw, &["x"]);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("too many arguments"));
    }

    #[test]
    fn test_build_command_args_numeric_coercion() {
        let mut raw = HashMap::new();
        raw.insert("_".to_string(), json!(["mousemove", "100", "200"]));
        let result = build_command_args(&raw, &["x", "y"]).unwrap();
        assert_eq!(result.get("x"), Some(&json!(100)));
        assert_eq!(result.get("y"), Some(&json!(200)));
    }

    #[test]
    fn test_build_command_args_decimal_numeric_coercion() {
        let mut raw = HashMap::new();
        raw.insert("_".to_string(), json!(["mousewheel", "1.5", "-2.25"]));
        let result = build_command_args(&raw, &["dx", "dy"]).unwrap();
        assert_eq!(result.get("dx"), Some(&json!(1.5)));
        assert_eq!(result.get("dy"), Some(&json!(-2.25)));
    }

    #[test]
    fn test_parse_batch_args_argument_mode() {
        let args = vec![
            "--bail".to_string(),
            "open https://example.com".to_string(),
            "snapshot".to_string(),
        ];
        let parsed = parse_batch_args(&args).unwrap();
        assert_eq!(
            parsed,
            BatchArgs {
                bail: true,
                json: false,
                commands: vec![
                    "open https://example.com".to_string(),
                    "snapshot".to_string()
                ],
            }
        );
    }

    #[test]
    fn test_parse_batch_args_rejects_positional_with_json() {
        let args = vec!["--json".to_string(), "snapshot".to_string()];
        let err = parse_batch_args(&args).unwrap_err();
        assert!(err.contains("--json"));
    }

    #[test]
    fn test_parse_batch_args_treats_dash_prefixed_command_as_command() {
        let args = vec![
            "--server=http://example.com open https://example.com".to_string(),
            "snapshot".to_string(),
        ];
        let parsed = parse_batch_args(&args).unwrap();
        assert_eq!(
            parsed.commands,
            vec![
                "--server=http://example.com open https://example.com".to_string(),
                "snapshot".to_string()
            ]
        );
    }

    #[test]
    fn test_parse_command_string_supports_quotes() {
        let parsed = parse_command_string(r##"type "#search-input" "hello world""##).unwrap();
        assert_eq!(parsed, vec!["type", "#search-input", "hello world"]);
    }

    #[test]
    fn test_parse_command_string_supports_single_quotes_and_escapes() {
        let parsed = parse_command_string("type '#search input' it\\ works").unwrap();
        assert_eq!(parsed, vec!["type", "#search input", "it works"]);
    }

    #[test]
    fn test_parse_command_string_rejects_unclosed_quotes() {
        let err = parse_command_string(r##"type "#search"##).unwrap_err();
        assert!(err.contains("unclosed quote"));
    }

    #[test]
    fn test_parse_command_string_preserves_empty_quoted_argument() {
        let parsed = parse_command_string(r#"open "" "#).unwrap();
        assert_eq!(parsed, vec!["open", ""]);
    }

    #[test]
    fn test_parse_batch_json_commands_array_entries() {
        let parsed =
            parse_batch_json_commands(r#"[["goto","https://example.com"],["snapshot"]]"#).unwrap();
        assert_eq!(
            parsed,
            vec![
                vec!["goto".to_string(), "https://example.com".to_string()],
                vec!["snapshot".to_string()],
            ]
        );
    }

    #[test]
    fn test_parse_batch_json_commands_string_entries() {
        let parsed =
            parse_batch_json_commands(r#"["open https://example.com","snapshot"]"#).unwrap();
        assert_eq!(
            parsed,
            vec![
                vec!["open".to_string(), "https://example.com".to_string()],
                vec!["snapshot".to_string()],
            ]
        );
    }

    #[test]
    fn test_parse_batch_json_commands_rejects_non_strings() {
        let err = parse_batch_json_commands(r#"[["open",1]]"#).unwrap_err();
        assert!(err.contains("string arguments"));
    }

    // -----------------------------------------------------------------------
    // Batch argument parsing — edge cases
    // -----------------------------------------------------------------------

    #[test]
    fn test_parse_batch_args_no_args_no_json_rejects() {
        let args: Vec<String> = vec![];
        let err = parse_batch_args(&args).unwrap_err();
        assert!(
            err.contains("at least one command"),
            "Expected 'at least one command' in error: {err}"
        );
    }

    #[test]
    fn test_parse_batch_args_json_mode_only() {
        let args = vec!["--json".to_string()];
        let parsed = parse_batch_args(&args).unwrap();
        assert_eq!(
            parsed,
            BatchArgs {
                bail: false,
                json: true,
                commands: vec![],
            }
        );
    }

    #[test]
    fn test_parse_batch_args_bail_and_json_combined() {
        let args = vec!["--bail".to_string(), "--json".to_string()];
        let parsed = parse_batch_args(&args).unwrap();
        assert!(parsed.bail);
        assert!(parsed.json);
        assert!(parsed.commands.is_empty());
    }

    #[test]
    fn test_parse_batch_args_double_dash_separator() {
        let args = vec![
            "--bail".to_string(),
            "--".to_string(),
            "--looks-like-flag".to_string(),
            "snapshot".to_string(),
        ];
        let parsed = parse_batch_args(&args).unwrap();
        assert!(parsed.bail);
        assert!(!parsed.json);
        assert_eq!(
            parsed.commands,
            vec!["--looks-like-flag".to_string(), "snapshot".to_string()]
        );
    }

    #[test]
    fn test_parse_batch_args_single_command() {
        let args = vec!["open https://example.com".to_string()];
        let parsed = parse_batch_args(&args).unwrap();
        assert!(!parsed.bail);
        assert!(!parsed.json);
        assert_eq!(parsed.commands, vec!["open https://example.com"]);
    }

    #[test]
    fn test_parse_batch_args_many_commands() {
        let args = vec![
            "open https://example.com".to_string(),
            "click #btn".to_string(),
            "type #input 'hello'".to_string(),
            "snapshot".to_string(),
            "screenshot".to_string(),
            "close".to_string(),
        ];
        let parsed = parse_batch_args(&args).unwrap();
        assert_eq!(parsed.commands.len(), 6);
    }

    // -----------------------------------------------------------------------
    // Command string parsing — edge cases
    // -----------------------------------------------------------------------

    #[test]
    fn test_parse_command_string_empty_rejects() {
        let err = parse_command_string("").unwrap_err();
        assert!(err.contains("empty"), "Expected 'empty' in error: {err}");
    }

    #[test]
    fn test_parse_command_string_whitespace_only_rejects() {
        let err = parse_command_string("   \t  ").unwrap_err();
        assert!(err.contains("empty"), "Expected 'empty' in error: {err}");
    }

    #[test]
    fn test_parse_command_string_trailing_backslash_rejects() {
        let err = parse_command_string(r"type hello\").unwrap_err();
        assert!(err.contains("escape"), "Expected 'escape' in error: {err}");
    }

    #[test]
    fn test_parse_command_string_special_characters() {
        let parsed = parse_command_string(r##"fill "#email" "user@example.com""##).unwrap();
        assert_eq!(parsed, vec!["fill", "#email", "user@example.com"]);
    }

    #[test]
    fn test_parse_command_string_url_with_query_params() {
        let parsed =
            parse_command_string(r#"open "https://example.com/search?q=hello+world&lang=en""#)
                .unwrap();
        assert_eq!(
            parsed,
            vec!["open", "https://example.com/search?q=hello+world&lang=en"]
        );
    }

    #[test]
    fn test_parse_command_string_mixed_quotes() {
        let parsed = parse_command_string(r##"type "#input" 'single quoted'"##).unwrap();
        assert_eq!(parsed, vec!["type", "#input", "single quoted"]);
    }

    #[test]
    fn test_parse_command_string_form_filling_regression_quotes() {
        let first_name = parse_command_string(r##"fill \"input#first-name\" Lin"##).unwrap();
        assert_eq!(first_name, vec!["fill", "\"input#first-name\"", "Lin"]);

        let email = parse_command_string("fill input#email 'lin.qiao@example.com'").unwrap();
        assert_eq!(email, vec!["fill", "input#email", "lin.qiao@example.com"]);
    }

    #[test]
    fn test_parse_command_string_escaped_characters() {
        // Backslash removes the special meaning of the next character rather than
        // interpreting C-style escape sequences (e.g. `\n` → `n`, not newline).
        let parsed = parse_command_string(r##"fill "#input" "line1\nline2""##).unwrap();
        assert_eq!(parsed, vec!["fill", "#input", "line1nline2"]);
    }

    #[test]
    fn test_parse_command_string_consecutive_spaces() {
        let parsed = parse_command_string("click   #btn").unwrap();
        assert_eq!(parsed, vec!["click", "#btn"]);
    }

    #[test]
    fn test_parse_command_string_single_token() {
        let parsed = parse_command_string("snapshot").unwrap();
        assert_eq!(parsed, vec!["snapshot"]);
    }

    // -----------------------------------------------------------------------
    // Batch JSON parsing — edge cases
    // -----------------------------------------------------------------------

    #[test]
    fn test_parse_batch_json_commands_empty_array_rejects() {
        let err = parse_batch_json_commands("[]").unwrap_err();
        assert!(
            err.contains("at least one command"),
            "Expected 'at least one command' in error: {err}"
        );
    }

    #[test]
    fn test_parse_batch_json_commands_not_array_rejects() {
        let err = parse_batch_json_commands(r#"{"cmd":"open"}"#).unwrap_err();
        assert!(
            err.contains("must be an array"),
            "Expected 'must be an array' in error: {err}"
        );
    }

    #[test]
    fn test_parse_batch_json_commands_invalid_json_rejects() {
        let err = parse_batch_json_commands("not json at all").unwrap_err();
        assert!(
            err.contains("Invalid batch JSON"),
            "Expected 'Invalid batch JSON' in error: {err}"
        );
    }

    #[test]
    fn test_parse_batch_json_commands_empty_inner_array_rejects() {
        let err = parse_batch_json_commands(r#"[[]]"#).unwrap_err();
        assert!(
            err.contains("must not be empty"),
            "Expected 'must not be empty' in error: {err}"
        );
    }

    #[test]
    fn test_parse_batch_json_commands_numeric_entry_rejects() {
        let err = parse_batch_json_commands(r#"[42]"#).unwrap_err();
        assert!(
            err.contains("string or string array"),
            "Expected 'string or string array' in error: {err}"
        );
    }

    #[test]
    fn test_parse_batch_json_commands_null_entry_rejects() {
        let err = parse_batch_json_commands(r#"[null]"#).unwrap_err();
        assert!(
            err.contains("string or string array"),
            "Expected 'string or string array' in error: {err}"
        );
    }

    #[test]
    fn test_parse_batch_json_commands_mixed_formats() {
        let parsed = parse_batch_json_commands(
            r##"["open https://example.com", ["click", "#btn"], "snapshot"]"##,
        )
        .unwrap();
        assert_eq!(parsed.len(), 3);
        assert_eq!(
            parsed[0],
            vec!["open".to_string(), "https://example.com".to_string()]
        );
        assert_eq!(parsed[1], vec!["click".to_string(), "#btn".to_string()]);
        assert_eq!(parsed[2], vec!["snapshot".to_string()]);
    }

    #[test]
    fn test_parse_batch_json_commands_single_command() {
        let parsed = parse_batch_json_commands(r#"["snapshot"]"#).unwrap();
        assert_eq!(parsed, vec![vec!["snapshot".to_string()]]);
    }

    #[test]
    fn test_parse_batch_json_commands_special_chars_in_values() {
        let parsed =
            parse_batch_json_commands(r##"[["fill", "#email", "user+test@example.com"]]"##)
                .unwrap();
        assert_eq!(
            parsed[0],
            vec![
                "fill".to_string(),
                "#email".to_string(),
                "user+test@example.com".to_string()
            ]
        );
    }

    #[test]
    fn test_parse_batch_json_commands_unicode_values() {
        let parsed = parse_batch_json_commands(r##"[["fill", "#name", "日本語テスト"]]"##).unwrap();
        assert_eq!(parsed[0][2], "日本語テスト");
    }

    #[test]
    fn test_parse_batch_json_commands_many_commands() {
        let input = (0..20)
            .map(|i| format!(r##""click #btn-{i}""##))
            .collect::<Vec<_>>()
            .join(",");
        let json_input = format!("[{input}]");
        let parsed = parse_batch_json_commands(&json_input).unwrap();
        assert_eq!(parsed.len(), 20);
        for (i, cmd) in parsed.iter().enumerate() {
            assert_eq!(cmd, &vec!["click".to_string(), format!("#btn-{i}")]);
        }
    }

    #[test]
    fn test_parse_raw_args_repeatable_non_boolean_option_collected_to_array() {
        // -e price -e rating -e stars should produce regexp: ["price", "rating", "stars"]
        let short_to_long: HashMap<String, String> = [
            ("e".to_string(), "regexp".to_string()),
        ]
        .into_iter()
        .collect();
        // -e is NOT boolean — it takes a value
        let bool_opts: HashSet<String> = HashSet::new();

        let raw = vec![
            "snapshot-grep".to_string(),
            "-e".to_string(),
            "price".to_string(),
            "-e".to_string(),
            "rating".to_string(),
            "-e".to_string(),
            "stars".to_string(),
        ];
        let map = parse_raw_args(&raw, Some(&short_to_long), Some(&bool_opts));
        assert_eq!(
            map.get("regexp"),
            Some(&json!(["price", "rating", "stars"]))
        );
    }

    #[test]
    fn test_parse_raw_args_repeatable_boolean_flag_not_collected() {
        // -i -i should still be ignore-case: true (not [true, true])
        let short_to_long: HashMap<String, String> = [
            ("i".to_string(), "ignore-case".to_string()),
        ]
        .into_iter()
        .collect();
        let bool_opts: HashSet<String> = ["ignore-case".to_string()].into_iter().collect();

        let raw = vec![
            "snapshot-grep".to_string(),
            "-i".to_string(),
            "search".to_string(),
        ];
        let map = parse_raw_args(&raw, Some(&short_to_long), Some(&bool_opts));
        assert_eq!(map.get("ignore-case"), Some(&json!(true)));
    }

    #[test]
    fn test_parse_raw_args_single_non_boolean_option_not_array() {
        // A single -e should still be a string, not an array
        let short_to_long: HashMap<String, String> = [
            ("e".to_string(), "regexp".to_string()),
        ]
        .into_iter()
        .collect();
        let bool_opts: HashSet<String> = HashSet::new();

        let raw = vec![
            "snapshot-grep".to_string(),
            "-e".to_string(),
            "price".to_string(),
        ];
        let map = parse_raw_args(&raw, Some(&short_to_long), Some(&bool_opts));
        assert_eq!(map.get("regexp"), Some(&json!("price")));
    }
}
