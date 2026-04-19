//! Argument parsing helpers for the Browser4 CLI.
//!
//! Parses raw command-line arguments into:
//! - Global flags (`-s=<session>`, `--server=<url>`)
//! - Positional arguments (stored in `_`)
//! - Named options (`--key=value`, `--flag`)

use serde_json::{json, Value};
use std::collections::HashMap;

/// Parsed global flags that appear before the command name.
#[derive(Debug, Default, Clone)]
pub struct GlobalFlags {
    /// `-s=<name>` session name
    pub session_name: Option<String>,
    /// `--server=<url>` or `--server <url>` server override
    pub server_url: Option<String>,
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
/// - `-s=<name>` → session name
/// - `--server=<url>` or `--server <url>` → server URL override
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

    let mut i = 0;
    while i < argv.len() {
        let arg = &argv[i];
        if arg.starts_with("-s=") {
            flags.session_name = Some(arg["-s=".len()..].to_string());
        } else if arg.starts_with("--server=") {
            flags.server_url = Some(arg["--server=".len()..].to_string());
        } else if arg == "--server" {
            if i + 1 < argv.len() && !argv[i + 1].starts_with('-') {
                i += 1;
                flags.server_url = Some(argv[i].clone());
            }
        } else {
            flags.args.push(arg.clone());
        }
        i += 1;
    }
    flags
}

/// Parse raw CLI arguments into a map suitable for command dispatch.
///
/// - Positional arguments go into `_` as a JSON array.
/// - `--key=value` → key: string value
/// - `--flag` (no value) → key: true (boolean)
/// - Values `"true"` / `"false"` are coerced to booleans.
pub fn parse_raw_args(raw_args: &[String]) -> HashMap<String, Value> {
    let mut result: HashMap<String, Value> = HashMap::new();
    let mut positional: Vec<Value> = Vec::new();

    for arg in raw_args {
        if let Some(rest) = arg.strip_prefix("--") {
            if let Some(eq) = rest.find('=') {
                let key = rest[..eq].to_string();
                let val = &rest[eq + 1..];
                let value = match val {
                    "true" => Value::Bool(true),
                    "false" => Value::Bool(false),
                    other => Value::String(other.to_string()),
                };
                result.insert(key, value);
            } else {
                result.insert(rest.to_string(), Value::Bool(true));
            }
        } else {
            positional.push(json!(arg));
        }
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
        let map = parse_raw_args(&raw);
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
        let map = parse_raw_args(&raw);
        assert_eq!(map.get("submit"), Some(&json!(true)));
    }

    #[test]
    fn test_parse_raw_args_bool_flag() {
        let raw = vec!["snapshot".to_string(), "--headed".to_string()];
        let map = parse_raw_args(&raw);
        assert_eq!(map.get("headed"), Some(&json!(true)));
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
            parse_batch_json_commands(r#"[["open","https://example.com"],["snapshot"]]"#).unwrap();
        assert_eq!(
            parsed,
            vec![
                vec!["open".to_string(), "https://example.com".to_string()],
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
        assert!(
            err.contains("escape"),
            "Expected 'escape' in error: {err}"
        );
    }

    #[test]
    fn test_parse_command_string_special_characters() {
        let parsed = parse_command_string(r##"fill "#email" "user@example.com""##).unwrap();
        assert_eq!(parsed, vec!["fill", "#email", "user@example.com"]);
    }

    #[test]
    fn test_parse_command_string_url_with_query_params() {
        let parsed = parse_command_string(
            r#"open "https://example.com/search?q=hello+world&lang=en""#,
        )
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
        assert_eq!(
            parsed[1],
            vec!["click".to_string(), "#btn".to_string()]
        );
        assert_eq!(parsed[2], vec!["snapshot".to_string()]);
    }

    #[test]
    fn test_parse_batch_json_commands_single_command() {
        let parsed = parse_batch_json_commands(r#"["snapshot"]"#).unwrap();
        assert_eq!(parsed, vec![vec!["snapshot".to_string()]]);
    }

    #[test]
    fn test_parse_batch_json_commands_special_chars_in_values() {
        let parsed = parse_batch_json_commands(
            r##"[["fill", "#email", "user+test@example.com"]]"##,
        )
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
        let parsed = parse_batch_json_commands(
            r##"[["fill", "#name", "日本語テスト"]]"##,
        )
        .unwrap();
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
}
