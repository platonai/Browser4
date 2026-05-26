//! All CLI command definitions, mapping command names to MCP tool names and parameters.

use serde_json::{json, Value};
use std::collections::HashMap;

/// Command category used for grouping in help output.
#[derive(Debug, Clone, PartialEq)]
#[allow(dead_code)]
pub enum Category {
    Core,
    Navigation,
    Keyboard,
    Mouse,
    Export,
    Tabs,
    Storage,
    Network,
    DevTools,
    Browsers,
    Config,
    Install,
    Agent,
    Swarm,
}

impl Category {
    pub fn as_str(&self) -> &'static str {
        match self {
            Category::Core => "core",
            Category::Navigation => "navigation",
            Category::Keyboard => "keyboard",
            Category::Mouse => "mouse",
            Category::Export => "export",
            Category::Tabs => "tabs",
            Category::Storage => "storage",
            Category::Network => "network",
            Category::DevTools => "devtools",
            Category::Browsers => "browsers",
            Category::Config => "config",
            Category::Install => "install",
            Category::Agent => "agent",
            Category::Swarm => "swarm",
        }
    }
}

/// Describes a single positional argument for a command.
#[derive(Debug, Clone)]
pub struct ArgDef {
    pub name: &'static str,
    pub description: &'static str,
    pub optional: bool,
}

/// Describes a named option (`--key=value`) for a command.
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct OptionDef {
    pub name: &'static str,
    pub description: &'static str,
    pub is_bool: bool,
}

/// A single CLI command definition.
#[derive(Debug, Clone)]
pub struct CommandDef {
    pub name: &'static str,
    pub description: &'static str,
    pub category: Category,
    pub hidden: bool,
    /// Whether this command can be used in batch mode.
    /// Only commands in Core, Navigation, Keyboard, Export, and Tabs categories are supported.
    pub batch_supported: bool,
    /// Ordered list of positional argument definitions.
    pub args: &'static [ArgDef],
    /// Named option definitions.
    pub options: &'static [OptionDef],
    /// Function that resolves the MCP tool name given parsed args+options.
    pub tool_name_fn: fn(&HashMap<String, Value>) -> String,
    /// Function that builds the JSON parameters for the MCP call.
    pub tool_params_fn: fn(&HashMap<String, Value>) -> Value,
}

// ---------------------------------------------------------------------------
// Helper macros and builders
// ---------------------------------------------------------------------------

fn get_str<'a>(map: &'a HashMap<String, Value>, key: &str) -> Option<&'a str> {
    map.get(key).and_then(|v| v.as_str())
}

fn get_opt_str<'a>(map: &'a HashMap<String, Value>, key: &str) -> Option<&'a str> {
    map.get(key).and_then(|v| v.as_str())
}

fn get_bool(map: &HashMap<String, Value>, key: &str) -> Option<bool> {
    map.get(key).and_then(|v| v.as_bool())
}

fn get_number_value(map: &HashMap<String, Value>, key: &str) -> Option<Value> {
    map.get(key).filter(|v| v.is_number()).cloned()
}

fn raw_positionals(map: &HashMap<String, Value>) -> Vec<String> {
    match map.get("_") {
        Some(Value::Array(values)) => values
            .iter()
            .skip(1)
            .filter_map(|value| value.as_str().map(ToOwned::to_owned))
            .collect(),
        _ => Vec::new(),
    }
}

fn looks_like_selector_or_ref(value: &str) -> bool {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return false;
    }

    trimmed.starts_with('#')
        || trimmed.starts_with('.')
        || trimmed.starts_with('[')
        || trimmed.starts_with("//")
        || trimmed.starts_with("xpath:")
        || trimmed.starts_with("css:")
        || trimmed.starts_with("backend:")
        || trimmed.starts_with("text=")
        || (trimmed.starts_with('e') && trimmed[1..].chars().all(|ch| ch.is_ascii_digit()))
}

fn resolve_key_and_ref(map: &HashMap<String, Value>) -> (String, Option<String>) {
    let positionals = raw_positionals(map);
    match positionals.as_slice() {
        [single] => (
            single.clone(),
            get_opt_str(map, "ref").map(ToOwned::to_owned),
        ),
        [first, second, ..] => {
            if looks_like_selector_or_ref(first) && !looks_like_selector_or_ref(second) {
                (second.clone(), Some(first.clone()))
            } else {
                (first.clone(), Some(second.clone()))
            }
        }
        _ => (
            get_str(map, "key").unwrap_or_default().to_string(),
            get_opt_str(map, "ref").map(ToOwned::to_owned),
        ),
    }
}

fn resolve_text_and_ref(map: &HashMap<String, Value>) -> (String, Option<String>) {
    let positionals = raw_positionals(map);
    match positionals.as_slice() {
        [single] => (
            single.clone(),
            get_opt_str(map, "ref").map(ToOwned::to_owned),
        ),
        [first, second, ..] => {
            if looks_like_selector_or_ref(first) && !looks_like_selector_or_ref(second) {
                (second.clone(), Some(first.clone()))
            } else {
                (first.clone(), Some(second.clone()))
            }
        }
        _ => (
            get_str(map, "text").unwrap_or_default().to_string(),
            get_opt_str(map, "ref").map(ToOwned::to_owned),
        ),
    }
}

// ---------------------------------------------------------------------------
// Command definitions (static)
// ---------------------------------------------------------------------------

pub fn all_commands() -> Vec<CommandDef> {
    vec![
        // ---- Core ----
        CommandDef {
            name: "open",
            description: "Open a browser session or refresh the saved one if it is no longer active",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef { name: "url", description: "The URL to navigate to", optional: true }],
            options: &[
                OptionDef { name: "headed", description: "Run browser in headed mode", is_bool: true },
                OptionDef { name: "persistent", description: "Use persistent browser profile", is_bool: true },
                OptionDef { name: "profile", description: "Path to browser profile directory", is_bool: false },
                OptionDef { name: "profile-mode", description: "Browser profile mode (temporary, default, system_default, prototype)", is_bool: false },
                OptionDef { name: "interact-level", description: "Interaction level for the new session (for example FASTEST, FAST, DEFAULT)", is_bool: false },
            ],
            tool_name_fn: |args| {
                if args.get("url").and_then(|v| v.as_str()).map(|u| !u.is_empty()).unwrap_or(false) {
                    "browser_navigate".to_string()
                } else {
                    "browser_snapshot".to_string()
                }
            },
            tool_params_fn: |args| {
                let url = get_opt_str(args, "url").unwrap_or("about:blank");
                let mut params = json!({ "url": url });
                if let Some(h) = get_bool(args, "headed") {
                    params["headed"] = json!(h);
                }
                if let Some(p) = get_bool(args, "persistent") {
                    params["persistent"] = json!(p);
                }
                if let Some(pf) = get_opt_str(args, "profile") {
                    params["profilePath"] = json!(pf);
                }
                if let Some(pm) = get_opt_str(args, "profile-mode") {
                    params["profileMode"] = json!(pm);
                }
                if let Some(interact_level) = get_opt_str(args, "interact-level") {
                    params["interactLevel"] = json!(interact_level);
                }
                params
            },
        },
        CommandDef {
            name: "close",
            description: "Close the browser",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "install",
            description: "Install the self-contained Browser4 runtime bundle (Browser4.jar + bundled JRE)",
            category: Category::Install,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "tag",
                    description: "Release tag to install, for example v4.9.3 or 4.9.3 (defaults to latest release)",
                    is_bool: false,
                },
                OptionDef {
                    name: "force",
                    description: "Force re-download even when the requested tagged runtime is already installed",
                    is_bool: true,
                },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |args| {
                let mut params = json!({});
                if let Some(tag) = get_opt_str(args, "tag") {
                    params["tag"] = json!(tag);
                }
                if let Some(force) = get_bool(args, "force") {
                    params["force"] = json!(force);
                }
                params
            },
        },
        CommandDef {
            name: "batch",
            description: "Execute multiple commands in one invocation",
            category: Category::Core,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef {
                name: "command...",
                description: "Quoted command strings to execute sequentially",
                optional: true,
            }],
            options: &[
                OptionDef {
                    name: "bail",
                    description: "Stop on the first command failure",
                    is_bool: true,
                },
                OptionDef {
                    name: "json",
                    description: "Read commands as JSON from stdin",
                    is_bool: true,
                },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "goto",
            description: "Navigate to a URL, auto-opening or refreshing the session when needed",
            category: Category::Navigation,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "url", description: "The URL to navigate to", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_navigate".to_string(),
            tool_params_fn: |args| {
                let url = get_str(args, "url").unwrap_or_default();
                json!({ "url": url })
            },
        },
        CommandDef {
            name: "go-back",
            description: "Go back to the previous page",
            category: Category::Navigation,
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_navigate_back".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "go-forward",
            description: "Go forward to the next page",
            category: Category::Navigation,
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_navigate_forward".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "reload",
            description: "Reload the current page",
            category: Category::Navigation,
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_reload".to_string(),
            tool_params_fn: |_| json!({}),
        },
        // ---- Keyboard ----
        CommandDef {
            name: "press",
            description: "Press a key on the focused element or an optional target ref, `a`, `ArrowLeft`",
            category: Category::Keyboard,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "key", description: "Name of the key to press or a character to generate, such as `ArrowLeft` or `a`", optional: false },
                ArgDef { name: "ref", description: "Optional CSS selector or element reference to receive the key press", optional: true },
            ],
            options: &[],
            tool_name_fn: |_| "browser_press_key".to_string(),
            tool_params_fn: |args| {
                let (key, reference) = resolve_key_and_ref(args);
                let mut params = json!({ "key": key });
                if let Some(reference) = reference {
                    params["ref"] = json!(reference);
                }
                params
            },
        },
        CommandDef {
            name: "type",
            description: "Type text into the focused element or an optional target ref",
            category: Category::Keyboard,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "text", description: "Text to type into the element", optional: false },
                ArgDef { name: "ref", description: "Optional CSS selector or element reference to type into", optional: true },
            ],
            options: &[
                OptionDef { name: "submit", description: "Whether to submit entered text (press Enter after)", is_bool: true },
            ],
            tool_name_fn: |_| "browser_press_sequentially".to_string(),
            tool_params_fn: |args| {
                let (text, reference) = resolve_text_and_ref(args);
                let mut p = json!({ "text": text });
                if let Some(reference) = reference {
                    p["ref"] = json!(reference);
                }
                if let Some(submit) = get_bool(args, "submit") {
                    p["submit"] = json!(submit);
                }
                p
            },
        },
        CommandDef {
            name: "keydown",
            description: "Press a key down on the keyboard",
            category: Category::Keyboard,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "key", description: "Name of the key to press", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_keydown".to_string(),
            tool_params_fn: |args| json!({ "key": get_str(args, "key").unwrap_or_default() }),
        },
        CommandDef {
            name: "keyup",
            description: "Press a key up on the keyboard",
            category: Category::Keyboard,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "key", description: "Name of the key to press", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_keyup".to_string(),
            tool_params_fn: |args| json!({ "key": get_str(args, "key").unwrap_or_default() }),
        },
        // ---- Mouse ----
        CommandDef {
            name: "mousemove",
            description: "Move mouse to a given position",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "x", description: "X coordinate", optional: false },
                ArgDef { name: "y", description: "Y coordinate", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_mouse_move_xy".to_string(),
            tool_params_fn: |args| {
                json!({
                    "x": get_number_value(args, "x").unwrap_or_else(|| json!(0)),
                    "y": get_number_value(args, "y").unwrap_or_else(|| json!(0)),
                })
            },
        },
        CommandDef {
            name: "mousedown",
            description: "Press mouse down",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "button", description: "Button to press, defaults to left", optional: true }],
            options: &[],
            tool_name_fn: |_| "browser_mouse_down".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(b) = get_opt_str(args, "button") { p["button"] = json!(b); }
                p
            },
        },
        CommandDef {
            name: "mouseup",
            description: "Press mouse up",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "button", description: "Button to press, defaults to left", optional: true }],
            options: &[],
            tool_name_fn: |_| "browser_mouse_up".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(b) = get_opt_str(args, "button") { p["button"] = json!(b); }
                p
            },
        },
        CommandDef {
            name: "mousewheel",
            description: "Scroll mouse wheel",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[
                // Note: the argument names and server parameter names are intentionally
                // cross-mapped to match the behaviour of the TypeScript browser4-cli:
                // the first positional arg (dx) is mapped to `deltaY` and the second (dy)
                // to `deltaX`.
                ArgDef { name: "dx", description: "Y delta", optional: false },
                ArgDef { name: "dy", description: "X delta", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_mouse_wheel".to_string(),
            tool_params_fn: |args| {
                json!({
                    "deltaY": get_number_value(args, "dx").unwrap_or_else(|| json!(0)),
                    "deltaX": get_number_value(args, "dy").unwrap_or_else(|| json!(0)),
                })
            },
        },
        // ---- Core interactions ----
        CommandDef {
            name: "click",
            description: "Perform click on a web page",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: false },
                ArgDef { name: "button", description: "Button to click, defaults to left", optional: true },
            ],
            options: &[
                OptionDef { name: "modifiers", description: "Modifier keys to press", is_bool: false },
            ],
            tool_name_fn: |_| "browser_click".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "ref": get_str(args, "ref").unwrap_or_default() });
                if let Some(b) = get_opt_str(args, "button") { p["button"] = json!(b); }
                if let Some(m) = args.get("modifiers") { p["modifiers"] = m.clone(); }
                p
            },
        },
        CommandDef {
            name: "dblclick",
            description: "Perform double click on a web page",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: false },
                ArgDef { name: "button", description: "Button to click, defaults to left", optional: true },
            ],
            options: &[
                OptionDef { name: "modifiers", description: "Modifier keys to press", is_bool: false },
            ],
            tool_name_fn: |_| "browser_click".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({
                    "ref": get_str(args, "ref").unwrap_or_default(),
                    "doubleClick": true,
                });
                if let Some(b) = get_opt_str(args, "button") { p["button"] = json!(b); }
                if let Some(m) = args.get("modifiers") { p["modifiers"] = m.clone(); }
                p
            },
        },
        CommandDef {
            name: "drag",
            description: "Perform drag and drop between two elements",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "startRef", description: "Exact source element reference from the page snapshot", optional: false },
                ArgDef { name: "endRef", description: "Exact target element reference from the page snapshot", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_drag".to_string(),
            tool_params_fn: |args| {
                json!({
                    "startRef": get_str(args, "startRef").unwrap_or_default(),
                    "endRef": get_str(args, "endRef").unwrap_or_default(),
                })
            },
        },
        CommandDef {
            name: "fill",
            description: "Fill text into editable element",
            category: Category::Keyboard,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: false },
                ArgDef { name: "text", description: "Text to fill into the element", optional: false },
            ],
            options: &[
                OptionDef { name: "submit", description: "Whether to submit entered text (press Enter after)", is_bool: true },
            ],
            tool_name_fn: |_| "browser_type".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({
                    "ref": get_str(args, "ref").unwrap_or_default(),
                    "text": get_str(args, "text").unwrap_or_default(),
                });
                if let Some(submit) = get_bool(args, "submit") {
                    p["submit"] = json!(submit);
                }
                p
            },
        },
        CommandDef {
            name: "hover",
            description: "Hover over element on page",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_hover".to_string(),
            tool_params_fn: |args| json!({ "ref": get_str(args, "ref").unwrap_or_default() }),
        },
        CommandDef {
            name: "select",
            description: "Select an option in a dropdown",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: false },
                ArgDef { name: "val", description: "Value to select in the dropdown", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_select_option".to_string(),
            tool_params_fn: |args| {
                let value = get_str(args, "val").unwrap_or_default();
                json!({ "ref": get_str(args, "ref").unwrap_or_default(), "values": [value] })
            },
        },
        CommandDef {
            name: "upload",
            description: "Upload one or multiple files",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "ref", description: "CSS selector or element reference for the file input", optional: false },
                ArgDef { name: "file", description: "The absolute paths to the files to upload", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_file_upload".to_string(),
            tool_params_fn: |args| {
                let file = get_str(args, "file").unwrap_or_default();
                json!({ "ref": get_str(args, "ref").unwrap_or_default(), "paths": [file] })
            },
        },
        CommandDef {
            name: "check",
            description: "Check a checkbox or radio button",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_check".to_string(),
            tool_params_fn: |args| json!({ "ref": get_str(args, "ref").unwrap_or_default() }),
        },
        CommandDef {
            name: "uncheck",
            description: "Uncheck a checkbox or radio button",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_uncheck".to_string(),
            tool_params_fn: |args| json!({ "ref": get_str(args, "ref").unwrap_or_default() }),
        },
        CommandDef {
            name: "snapshot",
            description: "Capture page snapshot to obtain element ref",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[
                OptionDef { name: "filename", description: "Save snapshot to file instead of returning it in the response", is_bool: false },
            ],
            tool_name_fn: |_| "browser_snapshot".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(f) = get_opt_str(args, "filename") { p["filename"] = json!(f); }
                p
            },
        },
        CommandDef {
            name: "eval",
            description: "Evaluate JavaScript expression on page or element",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "expression", description: "JavaScript expression or function to evaluate", optional: false },
                ArgDef { name: "ref", description: "Optional CSS selector or snapshot ref (for example e5)", optional: true },
            ],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "expression": get_str(args, "expression").unwrap_or_default() });
                if let Some(r) = get_opt_str(args, "ref") { p["ref"] = json!(r); }
                p
            },
        },
        CommandDef {
            name: "console",
            description: "List console messages",
            category: Category::DevTools,
            hidden: true,
            batch_supported: false,
            args: &[
                ArgDef { name: "min-level", description: "Level of the console messages to return. Defaults to \"info\"", optional: true },
            ],
            options: &[
                OptionDef { name: "clear", description: "Whether to clear the console list", is_bool: true },
            ],
            tool_name_fn: |args| {
                if get_bool(args, "clear").unwrap_or(false) {
                    "browser_console_clear".to_string()
                } else {
                    "browser_console_messages".to_string()
                }
            },
            tool_params_fn: |args| {
                if get_bool(args, "clear").unwrap_or(false) {
                    json!({})
                } else {
                    let mut p = json!({});
                    if let Some(l) = get_opt_str(args, "min-level") { p["level"] = json!(l); }
                    p
                }
            },
        },
        CommandDef {
            name: "dialog-accept",
            description: "Accept a dialog",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "prompt", description: "The text of the prompt in case of a prompt dialog", optional: true }],
            options: &[],
            tool_name_fn: |_| "browser_handle_dialog".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "accept": true });
                if let Some(t) = get_opt_str(args, "prompt") { p["promptText"] = json!(t); }
                p
            },
        },
        CommandDef {
            name: "dialog-dismiss",
            description: "Dismiss a dialog",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_handle_dialog".to_string(),
            tool_params_fn: |_| json!({ "accept": false }),
        },
        CommandDef {
            name: "resize",
            description: "Resize the browser window",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "w", description: "Width of the browser window", optional: false },
                ArgDef { name: "h", description: "Height of the browser window", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_resize".to_string(),
            tool_params_fn: |args| {
                json!({
                    "width": get_number_value(args, "w").unwrap_or_else(|| json!(0)),
                    "height": get_number_value(args, "h").unwrap_or_else(|| json!(0)),
                })
            },
        },
        CommandDef {
            name: "delete-data",
            description: "Delete session data",
            category: Category::Core,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        // ---- Export ----
        CommandDef {
            name: "screenshot",
            description: "Screenshot of the current page or element",
            category: Category::Export,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: true }],
            options: &[
                OptionDef { name: "filename", description: "File name to save the screenshot to", is_bool: false },
                OptionDef { name: "full-page", description: "When true, takes a screenshot of the full scrollable page", is_bool: true },
            ],
            tool_name_fn: |_| "browser_take_screenshot".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(r) = get_opt_str(args, "ref") { p["ref"] = json!(r); }
                if let Some(f) = get_opt_str(args, "filename") { p["filename"] = json!(f); }
                if let Some(fp) = get_bool(args, "full-page") { p["fullPage"] = json!(fp); }
                p
            },
        },
        CommandDef {
            name: "pdf",
            description: "Save page as PDF",
            category: Category::Export,
            hidden: true,
            batch_supported: true,
            args: &[],
            options: &[
                OptionDef { name: "filename", description: "File name to save the pdf to", is_bool: false },
            ],
            tool_name_fn: |_| "browser_pdf_save".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(f) = get_opt_str(args, "filename") { p["filename"] = json!(f); }
                p
            },
        },
        // ---- Tabs ----
        CommandDef {
            name: "tab-list",
            description: "List all tabs",
            category: Category::Tabs,
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_tabs".to_string(),
            tool_params_fn: |_| json!({ "action": "list" }),
        },
        CommandDef {
            name: "tab-new",
            description: "Create a new tab",
            category: Category::Tabs,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "url", description: "The URL to navigate to in the new tab", optional: true }],
            options: &[],
            tool_name_fn: |_| "browser_tabs".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "action": "new" });
                if let Some(u) = get_opt_str(args, "url") { p["url"] = json!(u); }
                p
            },
        },
        CommandDef {
            name: "tab-close",
            description: "Close a browser tab",
            category: Category::Tabs,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "index", description: "Zero-based tab index. If omitted, current tab is closed.", optional: true }],
            options: &[],
            tool_name_fn: |_| "browser_tabs".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "action": "close" });
                if let Some(index) = args.get("index") { p["index"] = index.clone(); }
                p
            },
        },
        CommandDef {
            name: "tab-select",
            description: "Select a browser tab",
            category: Category::Tabs,
            hidden: false,
            batch_supported: true,
            args: &[ArgDef { name: "index", description: "Zero-based tab index", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_tabs".to_string(),
            tool_params_fn: |args| {
                json!({
                    "action": "select",
                    "index": args.get("index").cloned().unwrap_or_default(),
                })
            },
        },
        // ---- Browsers / Sessions ----
        CommandDef {
            name: "list",
            description: "List browser sessions with their status and next-open behavior",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef { name: "all", description: "List all browser sessions across all workspaces", is_bool: true },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "close-all",
            description: "Close all browser sessions without stopping the Browser4 backend",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "kill-all",
            description: "Forcefully stop the Browser4 backend and kill Browser4 browser processes",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        // ---- Agent ----
        CommandDef {
            name: "extract",
            description: "Extract structured data from the current page",
            category: Category::Agent,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "instruction", description: "What data to extract, e.g. 'product name, price, ratings'", optional: false }],
            options: &[
                OptionDef { name: "schema", description: "JSON schema to constrain the extracted data structure", is_bool: false },
            ],
            tool_name_fn: |_| "agent_extract".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "instruction": get_str(args, "instruction").unwrap_or_default() });
                if let Some(s) = get_opt_str(args, "schema") { p["schema"] = json!(s); }
                p
            },
        },
        CommandDef {
            name: "summarize",
            description: "Summarize page content using AI",
            category: Category::Agent,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "instruction", description: "Summarization instruction, e.g. 'summarize the product reviews'", optional: true }],
            options: &[
                OptionDef { name: "selector", description: "CSS selector to limit the scope of summarization", is_bool: false },
            ],
            tool_name_fn: |_| "agent_summarize".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(i) = get_opt_str(args, "instruction") { p["instruction"] = json!(i); }
                if let Some(s) = get_opt_str(args, "selector") { p["selector"] = json!(s); }
                p
            },
        },
        CommandDef {
            name: "agent-run",
            description: "Run an autonomous agent task (async, returns task ID)",
            category: Category::Agent,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "task", description: "Natural language task for the agent to execute", optional: false }],
            options: &[],
            tool_name_fn: |_| "command_run".to_string(),
            tool_params_fn: |args| {
                json!({ "task": get_str(args, "task").unwrap_or_default() })
            },
        },
        CommandDef {
            name: "agent-status",
            description: "Check the status of a running agent task",
            category: Category::Agent,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "id", description: "Task ID returned by agent run", optional: false }],
            options: &[],
            tool_name_fn: |_| "command_status".to_string(),
            tool_params_fn: |args| {
                json!({ "id": get_str(args, "id").unwrap_or_default() })
            },
        },
        CommandDef {
            name: "agent-result",
            description: "Get the result of a completed agent task",
            category: Category::Agent,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "id", description: "Task ID returned by agent run", optional: false }],
            options: &[],
            tool_name_fn: |_| "command_result".to_string(),
            tool_params_fn: |args| {
                json!({ "id": get_str(args, "id").unwrap_or_default() })
            },
        },
        // ---- Swarm ----
        CommandDef {
            name: "swarm-create",
            description: "Create a swarm scrape session with parallel browser contexts",
            category: Category::Swarm,
            hidden: true,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef { name: "profile-mode", description: "Browser profile mode (default: SEQUENTIAL; supported: SEQUENTIAL or TEMPORARY)", is_bool: false },
                OptionDef { name: "max-open-tabs", description: "Maximum open tabs per browser context (default: 8)", is_bool: false },
                OptionDef { name: "max-browser-contexts", description: "Number of isolated browser environments (default: 2)", is_bool: false },
                OptionDef { name: "display-mode", description: "Display mode: GUI, HEADLESS, SUPERVISED", is_bool: false },
            ],
            tool_name_fn: |_| "open_session".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                let profile_mode = get_opt_str(args, "profile-mode")
                    .map(|value| value.trim().to_ascii_uppercase())
                    .filter(|value| !value.is_empty())
                    .unwrap_or_else(|| "SEQUENTIAL".to_string());
                p["profileMode"] = json!(profile_mode);
                if let Some(v) = get_opt_str(args, "max-open-tabs") { p["maxOpenTabs"] = json!(v); }
                if let Some(v) = get_opt_str(args, "max-browser-contexts") { p["maxBrowserContexts"] = json!(v); }
                if let Some(v) = get_opt_str(args, "display-mode") { p["displayMode"] = json!(v); }
                p
            },
        },
        CommandDef {
            name: "swarm-submit",
            description: "Submit URL(s) or X-SQL payloads as scrape jobs",
            category: Category::Swarm,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "url", description: "URL or X-SQL payload to submit", optional: true }],
            options: &[
                OptionDef { name: "seed-file", description: "File containing URLs to submit, one per line", is_bool: false },
                OptionDef { name: "deadline", description: "Deadline for task completion (ISO 8601, e.g. 2026-02-24T23:59:59Z)", is_bool: false },
                OptionDef { name: "expires", description: "Cache expiration duration (e.g. 1d, 1h)", is_bool: false },
                OptionDef { name: "refresh", description: "Force a fresh fetch, ignoring cache", is_bool: true },
                OptionDef { name: "parse", description: "Parse page immediately after fetching", is_bool: true },
                OptionDef { name: "store-content", description: "Persist page content to storage", is_bool: true },
            ],
            tool_name_fn: |_| "command_run".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(v) = get_opt_str(args, "url") { p["url"] = json!(v); }
                if let Some(v) = get_opt_str(args, "seed-file") { p["seedFile"] = json!(v); }
                if let Some(v) = get_opt_str(args, "deadline") { p["deadline"] = json!(v); }
                if let Some(v) = get_opt_str(args, "expires") { p["expires"] = json!(v); }
                if let Some(b) = get_bool(args, "refresh") { p["refresh"] = json!(b); }
                if let Some(b) = get_bool(args, "parse") { p["parse"] = json!(b); }
                if let Some(b) = get_bool(args, "store-content") { p["storeContent"] = json!(b); }
                p
            },
        },
        CommandDef {
            name: "swarm-status",
            description: "Check the status of a scrape job",
            category: Category::Swarm,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "id", description: "Task ID returned by swarm submit", optional: false }],
            options: &[],
            tool_name_fn: |_| "command_status".to_string(),
            tool_params_fn: |args| {
                json!({ "id": get_str(args, "id").unwrap_or_default() })
            },
        },
        CommandDef {
            name: "swarm-result",
            description: "Get the result of a completed scrape job",
            category: Category::Swarm,
            hidden: true,
            batch_supported: false,
            args: &[ArgDef { name: "id", description: "Task ID returned by swarm submit", optional: false }],
            options: &[],
            tool_name_fn: |_| "command_result".to_string(),
            tool_params_fn: |args| {
                json!({ "id": get_str(args, "id").unwrap_or_default() })
            },
        },
    ]
}

/// Build a lookup map from command name to command definition.
pub fn commands_map() -> HashMap<String, CommandDef> {
    all_commands()
        .into_iter()
        .map(|cmd| (cmd.name.to_string(), cmd))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_all_commands_unique_names() {
        let cmds = all_commands();
        let mut names = std::collections::HashSet::new();
        for cmd in &cmds {
            assert!(
                names.insert(cmd.name),
                "Duplicate command name: {}",
                cmd.name
            );
        }
    }

    #[test]
    fn test_commands_map_contains_expected() {
        let map = commands_map();
        for expected in &[
            "open",
            "close",
            "install",
            "batch",
            "goto",
            "click",
            "type",
            "fill",
            "snapshot",
            "screenshot",
            "extract",
            "summarize",
            "agent-run",
            "agent-status",
            "agent-result",
            "swarm-create",
            "swarm-submit",
            "swarm-status",
            "swarm-result",
        ] {
            assert!(map.contains_key(*expected), "Missing command: {}", expected);
        }
    }

    #[test]
    fn test_open_tool_name_with_url() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_navigate");
    }

    #[test]
    fn test_open_tool_name_without_url() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let args = HashMap::new();
        assert_eq!((cmd.tool_name_fn)(&args), "browser_snapshot");
    }

    #[test]
    fn test_batch_command_is_help_only() {
        let map = commands_map();
        let cmd = map.get("batch").unwrap();
        let args = HashMap::new();
        assert!((cmd.tool_name_fn)(&args).is_empty());
        assert_eq!((cmd.tool_params_fn)(&args), json!({}));
    }

    #[test]
    fn test_install_params_capture_tag_and_force() {
        let map = commands_map();
        let cmd = map.get("install").unwrap();
        let mut args = HashMap::new();
        args.insert("tag".to_string(), json!("4.9.3"));
        args.insert("force".to_string(), json!(true));

        let params = (cmd.tool_params_fn)(&args);
        assert!(params.get("tag").is_some());
        assert_eq!(params["tag"], "4.9.3");
        assert_eq!(params["force"], true);
        assert!((cmd.tool_name_fn)(&args).is_empty());
    }

    #[test]
    fn test_press_params_use_key_first_order() {
        let map = commands_map();
        let cmd = map.get("press").unwrap();
        let mut args = HashMap::new();
        args.insert("_".to_string(), json!(["press", "Enter", "#search"]));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["key"], "Enter");
        assert_eq!(params["ref"], "#search");
    }

    #[test]
    fn test_press_params_keep_legacy_ref_first_order() {
        let map = commands_map();
        let cmd = map.get("press").unwrap();
        let mut args = HashMap::new();
        args.insert("_".to_string(), json!(["press", "#search", "Enter"]));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["key"], "Enter");
        assert_eq!(params["ref"], "#search");
    }

    #[test]
    fn test_type_params_use_text_first_order() {
        let map = commands_map();
        let cmd = map.get("type").unwrap();
        let mut args = HashMap::new();
        args.insert("_".to_string(), json!(["type", "hello world", "#search"]));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["text"], "hello world");
        assert_eq!(params["ref"], "#search");
    }

    #[test]
    fn test_type_params_keep_legacy_ref_first_order() {
        let map = commands_map();
        let cmd = map.get("type").unwrap();
        let mut args = HashMap::new();
        args.insert("_".to_string(), json!(["type", "#search", "hello world"]));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["text"], "hello world");
        assert_eq!(params["ref"], "#search");
    }

    #[test]
    fn test_extract_tool_name_and_params() {
        let map = commands_map();
        let cmd = map.get("extract").unwrap();
        let mut args = HashMap::new();
        args.insert("instruction".to_string(), json!("product name, price"));
        assert_eq!((cmd.tool_name_fn)(&args), "agent_extract");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["instruction"], "product name, price");
    }

    #[test]
    fn test_extract_with_schema() {
        let map = commands_map();
        let cmd = map.get("extract").unwrap();
        let mut args = HashMap::new();
        args.insert("instruction".to_string(), json!("product info"));
        args.insert("schema".to_string(), json!(r#"{"fields":[]}"#));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["instruction"], "product info");
        assert_eq!(params["schema"], r#"{"fields":[]}"#);
    }

    #[test]
    fn test_summarize_tool_name_and_params() {
        let map = commands_map();
        let cmd = map.get("summarize").unwrap();
        let mut args = HashMap::new();
        args.insert("instruction".to_string(), json!("summarize the reviews"));
        assert_eq!((cmd.tool_name_fn)(&args), "agent_summarize");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["instruction"], "summarize the reviews");
    }

    #[test]
    fn test_summarize_with_selector() {
        let map = commands_map();
        let cmd = map.get("summarize").unwrap();
        let mut args = HashMap::new();
        args.insert("selector".to_string(), json!("#content"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "#content");
        assert!(params.get("instruction").is_none());
    }

    #[test]
    fn test_agent_run_tool_name() {
        let map = commands_map();
        let cmd = map.get("agent-run").unwrap();
        let mut args = HashMap::new();
        args.insert("task".to_string(), json!("go to amazon.com"));
        assert_eq!((cmd.tool_name_fn)(&args), "command_run");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["task"], "go to amazon.com");
    }

    #[test]
    fn test_agent_status_tool_name() {
        let map = commands_map();
        let cmd = map.get("agent-status").unwrap();
        let mut args = HashMap::new();
        args.insert("id".to_string(), json!("abc-123"));
        assert_eq!((cmd.tool_name_fn)(&args), "command_status");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["id"], "abc-123");
    }

    #[test]
    fn test_agent_result_tool_name() {
        let map = commands_map();
        let cmd = map.get("agent-result").unwrap();
        let mut args = HashMap::new();
        args.insert("id".to_string(), json!("abc-123"));
        assert_eq!((cmd.tool_name_fn)(&args), "command_result");
    }

    #[test]
    fn test_swarm_create_tool_name() {
        let map = commands_map();
        let cmd = map.get("swarm-create").unwrap();
        let args = HashMap::new();
        assert_eq!((cmd.tool_name_fn)(&args), "open_session");
    }

    #[test]
    fn test_open_params_with_profile_mode() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let mut args = HashMap::new();
        args.insert("profile-mode".to_string(), json!("TEMPORARY"));

        let params = (cmd.tool_params_fn)(&args);

        assert_eq!(params["profileMode"], "TEMPORARY");
    }

    #[test]
    fn test_open_params_with_interact_level() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let mut args = HashMap::new();
        args.insert("interact-level".to_string(), json!("FASTEST"));

        let params = (cmd.tool_params_fn)(&args);

        assert_eq!(params["interactLevel"], "FASTEST");
    }

    #[test]
    fn test_swarm_create_params_with_options() {
        let map = commands_map();
        let cmd = map.get("swarm-create").unwrap();
        let mut args = HashMap::new();
        args.insert("profile-mode".to_string(), json!("temporary"));
        args.insert("max-open-tabs".to_string(), json!("8"));
        args.insert("max-browser-contexts".to_string(), json!("2"));
        args.insert("display-mode".to_string(), json!("GUI"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["profileMode"], "TEMPORARY");
        assert_eq!(params["maxOpenTabs"], "8");
        assert_eq!(params["maxBrowserContexts"], "2");
        assert_eq!(params["displayMode"], "GUI");
    }

    #[test]
    fn test_swarm_create_params_default_profile_mode_to_sequential() {
        let map = commands_map();
        let cmd = map.get("swarm-create").unwrap();
        let args = HashMap::new();

        let params = (cmd.tool_params_fn)(&args);

        assert_eq!(params["profileMode"], "SEQUENTIAL");
    }

    #[test]
    fn test_swarm_submit_tool_name_and_params() {
        let map = commands_map();
        let cmd = map.get("swarm-submit").unwrap();
        let mut args = HashMap::new();
        args.insert(
            "url".to_string(),
            json!("https://www.amazon.com/dp/B08PP5MSVB"),
        );
        args.insert("deadline".to_string(), json!("2026-02-24T23:59:59Z"));
        assert_eq!((cmd.tool_name_fn)(&args), "command_run");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["url"], "https://www.amazon.com/dp/B08PP5MSVB");
        assert_eq!(params["deadline"], "2026-02-24T23:59:59Z");
    }

    #[test]
    fn test_swarm_submit_with_seed_file() {
        let map = commands_map();
        let cmd = map.get("swarm-submit").unwrap();
        let mut args = HashMap::new();
        args.insert("seed-file".to_string(), json!("seeds.txt"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["seedFile"], "seeds.txt");
    }

    #[test]
    fn test_swarm_submit_with_load_options() {
        let map = commands_map();
        let cmd = map.get("swarm-submit").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        args.insert("refresh".to_string(), json!(true));
        args.insert("parse".to_string(), json!(true));
        args.insert("store-content".to_string(), json!(true));
        args.insert("expires".to_string(), json!("1d"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["refresh"], true);
        assert_eq!(params["parse"], true);
        assert_eq!(params["storeContent"], true);
        assert_eq!(params["expires"], "1d");
    }

    #[test]
    fn test_swarm_status_tool_name() {
        let map = commands_map();
        let cmd = map.get("swarm-status").unwrap();
        let mut args = HashMap::new();
        args.insert("id".to_string(), json!("abc-123"));
        assert_eq!((cmd.tool_name_fn)(&args), "command_status");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["id"], "abc-123");
    }

    #[test]
    fn test_swarm_result_tool_name() {
        let map = commands_map();
        let cmd = map.get("swarm-result").unwrap();
        let mut args = HashMap::new();
        args.insert("id".to_string(), json!("abc-123"));
        assert_eq!((cmd.tool_name_fn)(&args), "command_result");
    }

    #[test]
    fn test_resize_params_preserve_integer_numbers() {
        let map = commands_map();
        let cmd = map.get("resize").unwrap();
        let mut args = HashMap::new();
        args.insert("w".to_string(), json!(1280));
        args.insert("h".to_string(), json!(900));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["width"], json!(1280));
        assert_eq!(params["height"], json!(900));
    }

    #[test]
    fn test_eval_params_without_ref() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        let mut args = HashMap::new();
        args.insert("expression".to_string(), json!("document.title"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!((cmd.tool_name_fn)(&args), "browser_evaluate");
        assert_eq!(params["expression"], json!("document.title"));
        assert!(params.get("ref").is_none());
    }

    #[test]
    fn test_eval_params_with_ref() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        let mut args = HashMap::new();
        args.insert(
            "expression".to_string(),
            json!("element => element.textContent"),
        );
        args.insert("ref".to_string(), json!("e5"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(
            params["expression"],
            json!("element => element.textContent")
        );
        assert_eq!(params["ref"], json!("e5"));
    }

    #[test]
    fn test_mousewheel_params_preserve_decimal_numbers() {
        let map = commands_map();
        let cmd = map.get("mousewheel").unwrap();
        let mut args = HashMap::new();
        args.insert("dx".to_string(), json!(1.5));
        args.insert("dy".to_string(), json!(-2.25));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["deltaY"], json!(1.5));
        assert_eq!(params["deltaX"], json!(-2.25));
    }

    #[test]
    fn test_tab_select_uses_index_parameter() {
        let map = commands_map();
        let cmd = map.get("tab-select").unwrap();
        let mut args = HashMap::new();
        args.insert("index".to_string(), json!(1));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["action"], json!("select"));
        assert_eq!(params["index"], json!(1));
        assert!(params.get("tabId").is_none());
    }

    #[test]
    fn test_tab_close_uses_optional_index_parameter() {
        let map = commands_map();
        let cmd = map.get("tab-close").unwrap();
        let mut args = HashMap::new();
        args.insert("index".to_string(), json!(1));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["action"], json!("close"));
        assert_eq!(params["index"], json!(1));
        assert!(params.get("tabId").is_none());
    }

    #[test]
    fn test_swarm_commands_in_swarm_category() {
        let cmds = all_commands();
        let swarm_cmds: Vec<&str> = cmds
            .iter()
            .filter(|c| c.category == Category::Swarm)
            .map(|c| c.name)
            .collect();
        assert!(swarm_cmds.contains(&"swarm-create"));
        assert!(swarm_cmds.contains(&"swarm-submit"));
        assert!(swarm_cmds.contains(&"swarm-status"));
        assert!(swarm_cmds.contains(&"swarm-result"));
    }

    #[test]
    fn test_advanced_commands_are_hidden_from_global_help() {
        let map = commands_map();
        for name in [
            "console",
            "extract",
            "summarize",
            "agent-run",
            "agent-status",
            "agent-result",
            "swarm-create",
            "swarm-submit",
            "swarm-status",
            "swarm-result",
        ] {
            assert!(map.get(name).unwrap().hidden, "{name} should stay hidden");
        }
    }
}
