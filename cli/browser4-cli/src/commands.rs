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
    Snapshot,
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
            Category::Snapshot => "snapshot",
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
    /// Optional short-form alias (e.g. `"y"` for `-y`).
    pub short: Option<&'static str>,
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

fn get_string_value(map: &HashMap<String, Value>, key: &str) -> Option<String> {
    map.get(key).and_then(|value| match value {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        Value::Bool(flag) => Some(flag.to_string()),
        _ => None,
    })
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

/// Returns true if the value is an element reference pattern (e.g. "e5", "backend:15")
/// that should be rejected by commands requiring CSS selectors only.
pub fn is_element_reference(value: &str) -> bool {
    let trimmed = value.trim();
    (trimmed.starts_with('e') && trimmed[1..].chars().all(|ch| ch.is_ascii_digit()))
        || trimmed.starts_with("backend:")
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
// Page-load wait helpers
// ---------------------------------------------------------------------------

/// Build the JavaScript expression for `wait --load=<strategy>`.
///
/// Supported strategies:
/// - `domcontentloaded` — DOM is parsed, scripts executed
/// - `load` — all static resources (images, stylesheets, etc.) have loaded
/// - `networkidle` — static resources loaded AND no XHR/fetch activity for
///   at least 500 ms
///
/// Returns an error for unknown strategy names so the CLI can fail early.
pub fn load_strategy_js(load: &str) -> Result<String, String> {
    match load.to_ascii_lowercase().as_str() {
        "domcontentloaded" => {
            // readyState transitions: loading → interactive (DOMContentLoaded)
            // → complete (load).  Waiting for "not loading" covers both
            // interactive and complete — i.e. DOMContentLoaded has fired.
            Ok("document.readyState !== 'loading'".to_string())
        }
        "load" => {
            // The load event has fired: all stylesheets, images, fonts, and
            // other sub-resources have finished downloading.
            Ok("document.readyState === 'complete'".to_string())
        }
        "networkidle" => Ok(NETWORK_IDLE_JS.to_string()),
        other => Err(format!(
            "invalid --load value: '{other}'. Expected one of: networkidle, domcontentloaded, load"
        )),
    }
}

/// JavaScript expression that monkey-patches `fetch` and `XMLHttpRequest`
/// to track in-flight requests, then waits until all of the following are true:
///
/// 1. `document.readyState === 'complete'` (static resources loaded)
/// 2. No pending XHR / fetch requests
/// 3. At least 500 ms have passed since the last request completed
///
/// The tracking is installed once (via the `window.__b4_ni` sentinel) and
/// reused across polling iterations.
const NETWORK_IDLE_JS: &str = r#"(function(){
var s=window.__b4_ni;
if(!s){
s=window.__b4_ni={p:0,t:Date.now()};
var _f=window.fetch;
window.fetch=function(){
s.p++;s.t=Date.now();
return _f.apply(this,arguments).finally(function(){s.p--;s.t=Date.now()})
};
var XHR=window.XMLHttpRequest;
window.XMLHttpRequest=function(){
var x=new XHR,_s=x.send;
x.send=function(){
s.p++;s.t=Date.now();
x.addEventListener('loadend',function(){s.p--;s.t=Date.now()});
return _s.apply(this,arguments)
};
return x
}
}
return document.readyState==='complete'&&s.p===0&&(Date.now()-s.t)>=500
})()"#;

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
                OptionDef { name: "headed", description: "Run browser in headed mode", is_bool: true, short: None },
                OptionDef { name: "headless", description: "Run browser in headless mode", is_bool: true, short: None },
                OptionDef { name: "profile", description: "Path to browser profile directory", is_bool: false, short: None },
                OptionDef { name: "profile-mode", description: "Browser profile mode (temporary, sequential, default)", is_bool: false, short: None },
                OptionDef { name: "interact-level", description: "Interaction level for the new session (for example FASTEST, FAST, DEFAULT)", is_bool: false, short: None },
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
                // --headless takes priority over --headed when both are passed.
                if let Some(true) = get_bool(args, "headless") {
                    params["headed"] = json!(false);
                } else if let Some(true) = get_bool(args, "headed") {
                    params["headed"] = json!(true);
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
            name: "attach",
            description: "Attach to an existing browser via CDP endpoint or channel name",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "cdp",
                    description: "CDP endpoint URL (e.g. http://localhost:9222) or channel name (chrome, msedge, chrome-canary, ...)",
                    is_bool: false,
                    short: None,
                },
                OptionDef {
                    name: "endpoint",
                    description: "Remote Browser4 server endpoint URL (e.g. http://browser4-server:8182)",
                    is_bool: false,
                    short: None,
                },
            ],
            tool_name_fn: |_| "attach_browser".to_string(),
            tool_params_fn: |args| {
                let mut params = json!({});
                if let Some(cdp) = get_opt_str(args, "cdp") {
                    params["cdp"] = json!(cdp);
                }
                if let Some(ep) = get_opt_str(args, "endpoint") {
                    params["endpoint"] = json!(ep);
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
            description: "Install the self-contained Browser4 runtime bundle (dependency jars + bundled JRE + launcher scripts)",
            category: Category::Install,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "tag",
                    description: "Release tag to install, for example v4.9.3 or 4.9.3 (defaults to latest release)",
                    is_bool: false,
                    short: None,
                },
                OptionDef {
                    name: "force",
                    description: "Force re-download even when the requested tagged runtime is already installed",
                    is_bool: true,
                    short: None,
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
            name: "uninstall",
            description: "Remove all globally installed browser4-cli (npm, cargo) and its runtime data",
            category: Category::Install,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "yes",
                    description: "Skip confirmation prompts (non-interactive mode)",
                    is_bool: true,
                    short: Some("y"),
                },
                OptionDef {
                    name: "dry-run",
                    description: "Show what would be removed without actually removing anything",
                    is_bool: true,
                    short: None,
                },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |args| {
                let mut params = json!({});
                if let Some(yes) = get_bool(args, "yes") {
                    params["yes"] = json!(yes);
                }
                if let Some(dry) = get_bool(args, "dry-run") {
                    params["dry_run"] = json!(dry);
                }
                params
            },
        },
        CommandDef {
            name: "batch",
            description: "Execute multiple commands in one invocation",
            category: Category::Core,
            hidden: false,
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
                    short: None,
                },
                OptionDef {
                    name: "json",
                    description: "Read commands as JSON from stdin",
                    is_bool: true,
                    short: None,
                },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "loop",
            description:
                "Execute a task repeatedly on an interval. Supports plain text commands, \
                 x-sql queries (auto-detected by the server), browser4-cli subcommands \
                 (after --), and shell commands (--shell). Progress is persisted to disk \
                 under a configurable --name and can be resumed after interruption. \
                 Use --pause/--resume to control running loops, --pause-all/--resume-all/--stop-all \
                 for bulk operations, --list to see all loops, --status [name] to inspect, \
                 --stop [name] to clear.",
            category: Category::Core,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "task",
                description: "The task to execute, use -- for a browser4-cli subcommand, \
                              --shell for a shell command, or pass plain text/x-sql directly",
                optional: true,
            }],
            options: &[
                OptionDef {
                    name: "name",
                    description: "Loop name for persistence (default: default). \
                                  Named loops are stored in ~/.browser4/loops/<name>.json",
                    is_bool: false,
                    short: None,
                },
                OptionDef {
                    name: "interval",
                    description: "Seconds between iterations (default: 3600 = 1 hour)",
                    is_bool: false,
                    short: Some("i"),
                },
                OptionDef {
                    name: "count",
                    description: "Number of iterations before stopping (default: infinite)",
                    is_bool: false,
                    short: Some("n"),
                },
                OptionDef {
                    name: "timeout",
                    description: "Maximum total duration in seconds (default: 604800 = 1 week)",
                    is_bool: false,
                    short: Some("t"),
                },
                OptionDef {
                    name: "shell",
                    description: "Execute the task as a shell command (cmd /C or sh -c)",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "list",
                    description: "List all persisted loops",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "pause",
                    description: "Pause a running loop. The loop suspends at the next \
                                  iteration boundary and waits until resumed. \
                                  Optionally specify --name to target a named loop",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "resume",
                    description: "Resume a paused loop. \
                                  Optionally specify --name to target a named loop",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "pause-all",
                    description: "Pause all running loops at once",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "resume-all",
                    description: "Resume all paused loops at once",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "stop",
                    description: "Stop a loop and clear its persisted state. \
                                  Optionally specify --name to target a named loop",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "stop-all",
                    description: "Stop and clear all persisted loops at once",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "status",
                    description: "Show loop state and progress. \
                                  Optionally specify --name to target a named loop",
                    is_bool: true,
                    short: None,
                },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |args| {
                json!({ "task": get_str(args, "task").unwrap_or_default() })
            },
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
            options: &[
                OptionDef { name: "verify", description: "Verify the key press was applied by reading the element value", is_bool: true, short: None },
            ],
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
            description: "Type text into the focused element or an optional target ref. Passing a ref is recommended for reliable targeting; without a ref, text may go nowhere if no element is currently focused.",
            category: Category::Keyboard,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "text", description: "Text to type into the element", optional: false },
                ArgDef { name: "ref", description: "Optional CSS selector or element reference to type into", optional: true },
            ],
            options: &[
                OptionDef { name: "submit", description: "Whether to submit entered text (press Enter after)", is_bool: true, short: None },
                OptionDef { name: "verify", description: "Verify text was correctly typed after completion", is_bool: true, short: None },
                OptionDef { name: "focus", description: "Click the target element to focus it before typing, ensuring the element is in an interactive state", is_bool: true, short: None },
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
                ArgDef { name: "dx", description: "Horizontal scroll delta (deltaX)", optional: false },
                ArgDef { name: "dy", description: "Vertical scroll delta (deltaY)", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_mouse_wheel".to_string(),
            tool_params_fn: |args| {
                json!({
                    "deltaX": get_number_value(args, "dx").unwrap_or_else(|| json!(0)),
                    "deltaY": get_number_value(args, "dy").unwrap_or_else(|| json!(0)),
                })
            },
        },
        CommandDef {
            name: "scroll",
            description: "Scroll the page in a given direction by the specified number of pixels",
            category: Category::Mouse,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "direction", description: "Scroll direction: up, down, left, or right", optional: false },
                ArgDef { name: "pixels", description: "Number of pixels to scroll", optional: false },
            ],
            options: &[],
            tool_name_fn: |args| {
                let direction = get_str(args, "direction").unwrap_or_default().to_ascii_lowercase();
                match direction.as_str() {
                    "left" | "right" => "browser_mouse_wheel".to_string(),
                    _ => "scroll_by".to_string(),
                }
            },
            tool_params_fn: |args| {
                let direction = get_str(args, "direction").unwrap_or_default().to_ascii_lowercase();
                let pixels = get_number_value(args, "pixels")
                    .and_then(|v| v.as_f64())
                    .unwrap_or(0.0);
                match direction.as_str() {
                    "down" => json!({ "pixels": pixels }),
                    "up" => json!({ "pixels": -pixels }),
                    "right" => json!({ "deltaX": pixels, "deltaY": 0.0 }),
                    "left" => json!({ "deltaX": -pixels, "deltaY": 0.0 }),
                    _ => json!({ "pixels": pixels }),
                }
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
                OptionDef { name: "modifiers", description: "Modifier keys to press", is_bool: false, short: None },
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
                OptionDef { name: "modifiers", description: "Modifier keys to press", is_bool: false, short: None },
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
                OptionDef { name: "submit", description: "Whether to submit entered text (press Enter after)", is_bool: true, short: None },
                OptionDef { name: "verify", description: "Verify text was correctly typed after completion", is_bool: true, short: None },
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
            options: &[
                OptionDef { name: "verify", description: "Verify the correct option was selected by reading the element value", is_bool: true, short: None },
            ],
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
            hidden: true,
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
            name: "wait",
            description: "Wait for a condition: element, time, text, URL pattern, page load, or JS expression",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "target", description: "Element selector (e.g. e1) or wait duration in milliseconds", optional: true },
            ],
            options: &[
                OptionDef { name: "text", description: "Wait until this text appears on the page", is_bool: false, short: None },
                OptionDef { name: "url", description: "Wait until the URL matches this glob pattern", is_bool: false, short: None },
                OptionDef { name: "load", description: "Wait for page load state: networkidle, domcontentloaded, or load", is_bool: false, short: None },
                OptionDef { name: "fn", description: "Wait until this JavaScript expression returns true", is_bool: false, short: None },
                OptionDef { name: "timeout", description: "Maximum time to wait in milliseconds (default: 30000)", is_bool: false, short: None },
            ],
            tool_name_fn: |args| {
                if get_opt_str(args, "text").is_some() || get_opt_str(args, "fn").is_some() || get_opt_str(args, "load").is_some() {
                    "wait_for_function".to_string()
                } else if get_opt_str(args, "url").is_some() {
                    "wait_for_page".to_string()
                } else if get_number_value(args, "target").is_some() {
                    "delay".to_string()
                } else {
                    "wait_for_selector".to_string()
                }
            },
            tool_params_fn: |args| {
                // Resolve --timeout, defaulting to 30 000 ms
                let timeout_millis: i64 = get_opt_str(args, "timeout")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(30000);

                if let Some(text) = get_opt_str(args, "text") {
                    let escaped = serde_json::to_string(text)
                        .unwrap_or_else(|_| format!("{:?}", text));
                    let expr = format!("document.body.innerText.includes({})", escaped);
                    json!({ "pageFunction": expr, "timeoutMillis": timeout_millis })
                } else if let Some(fn_expr) = get_opt_str(args, "fn") {
                    json!({ "pageFunction": fn_expr, "timeoutMillis": timeout_millis })
                } else if let Some(load) = get_opt_str(args, "load") {
                    let expr = load_strategy_js(load)
                        .unwrap_or_else(|e| panic!("{e}"));
                    json!({ "pageFunction": expr, "timeoutMillis": timeout_millis })
                } else if let Some(url) = get_opt_str(args, "url") {
                    json!({ "url": url, "timeoutMillis": timeout_millis })
                } else if let Some(millis) = get_number_value(args, "target") {
                    json!({ "millis": millis })
                } else {
                    json!({ "selector": get_str(args, "target").unwrap_or_default() })
                }
            },
        },
        CommandDef {
            name: "get",
            description: "Extract data from a page element: text, html, box, styles, property, or attr",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "mode", description: "What to extract: text, html, box, styles, property, or attr", optional: false },
                ArgDef { name: "selector", description: "CSS selector or element reference (e.g. e5, .price, #main)", optional: false },
                ArgDef { name: "name", description: "Property or attribute name (required for property and attr modes)", optional: true },
            ],
            options: &[],
            tool_name_fn: |args| {
                let mode = get_str(args, "mode").unwrap_or_default().to_ascii_lowercase();
                match mode.as_str() {
                    "html" | "styles" => "browser_evaluate".to_string(),
                    "box" => "bounding_box".to_string(),
                    "property" => "select_first_property_value_or_null".to_string(),
                    "attr" => "select_first_attribute_or_null".to_string(),
                    _ => "select_first_text_or_null".to_string(),
                }
            },
            tool_params_fn: |args| {
                let mode = get_str(args, "mode").unwrap_or_default().to_ascii_lowercase();
                let selector = get_str(args, "selector").unwrap_or_default();
                let name = get_str(args, "name").unwrap_or_default();
                match mode.as_str() {
                    "html" => json!({ "expression": "element => element.innerHTML", "ref": selector }),
                    "styles" => {
                        let expr = "element => { const s = getComputedStyle(element); const o = {}; for (let i = 0; i < s.length; i++) { const k = s[i]; o[k] = s.getPropertyValue(k); } return o; }";
                        json!({ "expression": expr, "ref": selector })
                    },
                    "box" => json!({ "selector": selector }),
                    "property" => json!({ "selector": selector, "propName": name }),
                    "attr" => json!({ "selector": selector, "attrName": name }),
                    _ => json!({ "selector": selector }),
                }
            },
        },
        CommandDef {
            name: "snapshot",
            description: "Capture page snapshot to obtain element ref",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[
                OptionDef { name: "filename", description: "Save snapshot to file instead of returning it in the response", is_bool: false, short: None },
                OptionDef { name: "boxes", description: "Include each element's bounding box as [box=x,y,width,height] (enabled by default)", is_bool: true, short: None },
                OptionDef { name: "no-boxes", description: "Disable bounding boxes in snapshot output", is_bool: true, short: None },
                OptionDef { name: "interactive", description: "Only show interactive elements (buttons, links, inputs)", is_bool: true, short: Some("i") },
                OptionDef { name: "urls", description: "Include href URLs for link elements", is_bool: true, short: Some("u") },
                OptionDef { name: "compact", description: "Remove empty structural elements (enabled by default)", is_bool: true, short: Some("c") },
                OptionDef { name: "no-compact", description: "Disable compact mode; include all structural nodes", is_bool: true, short: None },
                OptionDef { name: "depth", description: "Limit tree depth to n levels", is_bool: false, short: Some("d") },
                OptionDef { name: "selector", description: "Scope snapshot to a CSS selector", is_bool: false, short: Some("s") },
                OptionDef { name: "raw", description: "Strip page info and return only snapshot content (alias for --stdout)", is_bool: true, short: None },
                OptionDef { name: "stdout", description: "Print snapshot content to stdout instead of saving to file", is_bool: true, short: None },
                OptionDef { name: "viewport", description: "Capture only specified viewports: single index (3), comma list (0,2,4), range (1-3), or mixed (0,2-4,7)", is_bool: false, short: Some("v") },
                OptionDef { name: "auto-diff", description: "Diff against the previous snapshot — show only what changed since the last capture", is_bool: true, short: None },
                OptionDef { name: "page", short: None, is_bool: false, description: "Page number for paginated snapshot output (1-based, default: 1)" },
                OptionDef { name: "page-size", short: None, is_bool: false, description: "Lines per page for snapshot output (default: 500)" },
                OptionDef { name: "all", short: None, is_bool: true, description: "Show all output, disabling pagination" },
            ],
            tool_name_fn: |_| "browser_snapshot".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(f) = get_opt_str(args, "filename") { p["filename"] = json!(f); }
                if let Some(true) = get_bool(args, "no-boxes") { p["boxes"] = json!(false); }
                else if let Some(true) = get_bool(args, "boxes") { p["boxes"] = json!(true); }
                if let Some(true) = get_bool(args, "interactive") { p["interactive"] = json!(true); }
                if let Some(true) = get_bool(args, "urls") { p["urls"] = json!(true); }
                if let Some(true) = get_bool(args, "no-compact") { p["compact"] = json!(false); }
                else if let Some(true) = get_bool(args, "compact") { p["compact"] = json!(true); }
                if let Some(d) = get_opt_str(args, "depth") {
                    if let Ok(n) = d.parse::<i32>() { p["depth"] = json!(n); }
                }
                if let Some(s) = get_opt_str(args, "selector") { p["selector"] = json!(s); }
                if let Some(v) = get_opt_str(args, "viewport") { p["viewports"] = json!(v); }
                // Pass through CLI-side flags so the handler can check them
                // (they are stripped from server-bound args in handle_snapshot).
                if let Some(true) = get_bool(args, "raw") { p["raw"] = json!(true); }
                if let Some(true) = get_bool(args, "stdout") { p["stdout"] = json!(true); }
                if let Some(true) = get_bool(args, "auto-diff") { p["auto-diff"] = json!(true); }
                // Pagination flags (CLI-side, not sent to server)
                if let Some(true) = get_bool(args, "all") { p["all"] = json!(true); }
                if let Some(pg) = get_opt_str(args, "page") { p["page"] = json!(pg); }
                if let Some(ps) = get_opt_str(args, "page-size") { p["page-size"] = json!(ps); }
                p
            },
        },
        CommandDef {
            name: "snapshot-grep",
            description: "Search snapshot YAML content using regex patterns with grep-style output",
            category: Category::Core,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "pattern", description: "Regex or literal pattern to search for", optional: false },
            ],
            options: &[
                OptionDef { name: "ignore-case", short: Some("i"), is_bool: true, description: "Case-insensitive matching" },
                OptionDef { name: "no-line-number", short: None, is_bool: true, description: "Suppress line numbers in output" },
                OptionDef { name: "after-context", short: Some("A"), is_bool: false, description: "Show N lines after each match" },
                OptionDef { name: "before-context", short: Some("B"), is_bool: false, description: "Show N lines before each match" },
                OptionDef { name: "context", short: Some("C"), is_bool: false, description: "Show N lines before and after each match" },
                OptionDef { name: "invert-match", short: Some("v"), is_bool: true, description: "Select non-matching lines" },
                OptionDef { name: "count", short: Some("c"), is_bool: true, description: "Print only the count of matching lines" },
                OptionDef { name: "files-with-matches", short: Some("l"), is_bool: true, description: "Print only whether matches exist" },
                OptionDef { name: "fixed-strings", short: Some("F"), is_bool: true, description: "Treat pattern as a literal string, not regex" },
                OptionDef { name: "word-regexp", short: Some("w"), is_bool: true, description: "Match only whole words" },
                OptionDef { name: "selector", short: None, is_bool: false, description: "CSS selector to scope the search to" },
                OptionDef { name: "page", short: None, is_bool: false, description: "Page number (1-based, default: 1)" },
                OptionDef { name: "page-size", short: None, is_bool: false, description: "Lines per page (default: 500)" },
                OptionDef { name: "all", short: None, is_bool: true, description: "Show all output, disabling pagination" },
            ],
            tool_name_fn: |_| "browser_snapshot".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                for (k, v) in args {
                    if k != "_" {
                        p[k] = v.clone();
                    }
                }
                p
            },
        },
        CommandDef {
            name: "eval",
            description: "Evaluate JavaScript expression on page or element. Objects and arrays are serialized as JSON; use --json to JSON-wrap scalar results.",
            category: Category::Core,
            hidden: false,
            batch_supported: true,
            args: &[
                ArgDef { name: "expression", description: "JavaScript expression or function to evaluate", optional: true },
                ArgDef { name: "ref", description: "Optional CSS selector or snapshot ref (for example e5)", optional: true },
            ],
            options: &[
                OptionDef { name: "file", description: "Read JavaScript expression from a file instead of the command line", is_bool: false, short: None },
                OptionDef { name: "stdin", description: "Read JavaScript expression from stdin (useful for piping multi-line scripts without shell quoting)", is_bool: true, short: None },
                OptionDef { name: "json", description: "Serialize the result as JSON (quotes strings, wraps scalars)", is_bool: true, short: None },
            ],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "expression": get_str(args, "expression").unwrap_or_default() });
                if let Some(r) = get_opt_str(args, "ref") { p["ref"] = json!(r); }
                if let Some(f) = get_opt_str(args, "file") { p["file"] = json!(f); }
                if get_bool(args, "stdin").unwrap_or(false) { p["stdin"] = json!(true); }
                if get_bool(args, "json").unwrap_or(false) { p["json"] = json!(true); }
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
                OptionDef { name: "clear", description: "Whether to clear the console list", is_bool: true, short: None },
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
        // ---- Storage ----
        CommandDef {
            name: "state-save",
            description: "Save cookies and localStorage to a JSON file",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "filename",
                description: "Optional file path. Defaults to storage-state-<timestamp>.json in the current directory",
                optional: true,
            }],
            options: &[],
            tool_name_fn: |_| "browser_save_storage_state".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(filename) = get_opt_str(args, "filename") {
                    p["filename"] = json!(filename);
                }
                p
            },
        },
        CommandDef {
            name: "state-load",
            description: "Load cookies and localStorage from a JSON file",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "filename",
                description: "Path to a storage-state JSON file",
                optional: false,
            }],
            options: &[],
            tool_name_fn: |_| "browser_load_storage_state".to_string(),
            tool_params_fn: |args| {
                json!({
                    "filename": get_str(args, "filename").unwrap_or_default()
                })
            },
        },
        CommandDef {
            name: "cookie-list",
            description: "List browser cookies",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef { name: "domain", description: "Only include cookies with the exact domain", is_bool: false, short: None },
                OptionDef { name: "path", description: "Only include cookies with the exact path", is_bool: false, short: None },
            ],
            tool_name_fn: |_| "browser_save_storage_state".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(domain) = get_opt_str(args, "domain") {
                    p["domain"] = json!(domain);
                }
                if let Some(path) = get_opt_str(args, "path") {
                    p["path"] = json!(path);
                }
                p
            },
        },
        CommandDef {
            name: "cookie-get",
            description: "Get a cookie by name",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "name",
                description: "Cookie name",
                optional: false,
            }],
            options: &[],
            tool_name_fn: |_| "browser_save_storage_state".to_string(),
            tool_params_fn: |args| {
                json!({
                    "name": get_string_value(args, "name").unwrap_or_default()
                })
            },
        },
        CommandDef {
            name: "cookie-set",
            description: "Set a browser cookie",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "name", description: "Cookie name", optional: false },
                ArgDef { name: "value", description: "Cookie value", optional: false },
            ],
            options: &[
                OptionDef { name: "domain", description: "Cookie domain", is_bool: false, short: None },
                OptionDef { name: "path", description: "Cookie path", is_bool: false, short: None },
                OptionDef { name: "expires", description: "Cookie expiration Unix timestamp", is_bool: false, short: None },
                OptionDef { name: "httpOnly", description: "Mark the cookie as HttpOnly", is_bool: true, short: None },
                OptionDef { name: "secure", description: "Mark the cookie as Secure", is_bool: true, short: None },
                OptionDef { name: "sameSite", description: "Cookie SameSite policy (Strict, Lax, None)", is_bool: false, short: None },
            ],
            tool_name_fn: |_| "browser_load_storage_state".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({
                    "name": get_string_value(args, "name").unwrap_or_default(),
                    "value": get_string_value(args, "value").unwrap_or_default(),
                });
                if let Some(domain) = get_opt_str(args, "domain") {
                    p["domain"] = json!(domain);
                }
                if let Some(path) = get_opt_str(args, "path") {
                    p["path"] = json!(path);
                }
                if let Some(expires) = get_opt_str(args, "expires") {
                    p["expires"] = json!(expires);
                }
                if let Some(http_only) = get_bool(args, "httpOnly") {
                    p["httpOnly"] = json!(http_only);
                }
                if let Some(secure) = get_bool(args, "secure") {
                    p["secure"] = json!(secure);
                }
                if let Some(same_site) = get_opt_str(args, "sameSite") {
                    p["sameSite"] = json!(same_site);
                }
                p
            },
        },
        CommandDef {
            name: "cookie-delete",
            description: "Delete a browser cookie by name",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "name",
                description: "Cookie name",
                optional: false,
            }],
            options: &[
                OptionDef { name: "domain", description: "Cookie domain override", is_bool: false, short: None },
                OptionDef { name: "path", description: "Cookie path override", is_bool: false, short: None },
            ],
            tool_name_fn: |_| "delete_cookies".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({
                    "name": get_string_value(args, "name").unwrap_or_default()
                });
                if let Some(domain) = get_opt_str(args, "domain") {
                    p["domain"] = json!(domain);
                }
                if let Some(path) = get_opt_str(args, "path") {
                    p["path"] = json!(path);
                }
                p
            },
        },
        CommandDef {
            name: "cookie-clear",
            description: "Clear all browser cookies",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| "clear_browser_cookies".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "localstorage-list",
            description: "List localStorage entries",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "localstorage-get",
            description: "Get a localStorage value by key",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "key",
                description: "localStorage key",
                optional: false,
            }],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                json!({
                    "key": get_string_value(args, "key").unwrap_or_default()
                })
            },
        },
        CommandDef {
            name: "localstorage-set",
            description: "Set a localStorage value",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "key", description: "localStorage key", optional: false },
                ArgDef { name: "value", description: "Value to store", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                json!({
                    "key": get_string_value(args, "key").unwrap_or_default(),
                    "value": get_string_value(args, "value").unwrap_or_default(),
                })
            },
        },
        CommandDef {
            name: "localstorage-delete",
            description: "Delete a localStorage entry",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "key",
                description: "localStorage key",
                optional: false,
            }],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                json!({
                    "key": get_string_value(args, "key").unwrap_or_default()
                })
            },
        },
        CommandDef {
            name: "localstorage-clear",
            description: "Clear localStorage",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "sessionstorage-list",
            description: "List sessionStorage entries",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "sessionstorage-get",
            description: "Get a sessionStorage value by key",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "key",
                description: "sessionStorage key",
                optional: false,
            }],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                json!({
                    "key": get_string_value(args, "key").unwrap_or_default()
                })
            },
        },
        CommandDef {
            name: "sessionstorage-set",
            description: "Set a sessionStorage value",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "key", description: "sessionStorage key", optional: false },
                ArgDef { name: "value", description: "Value to store", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                json!({
                    "key": get_string_value(args, "key").unwrap_or_default(),
                    "value": get_string_value(args, "value").unwrap_or_default(),
                })
            },
        },
        CommandDef {
            name: "sessionstorage-delete",
            description: "Delete a sessionStorage entry",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "key",
                description: "sessionStorage key",
                optional: false,
            }],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                json!({
                    "key": get_string_value(args, "key").unwrap_or_default()
                })
            },
        },
        CommandDef {
            name: "sessionstorage-clear",
            description: "Clear sessionStorage",
            category: Category::Storage,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
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
                OptionDef { name: "filename", description: "File name to save the screenshot to", is_bool: false, short: None },
                OptionDef { name: "full-page", description: "When true, takes a screenshot of the full scrollable page", is_bool: true, short: None },
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
            hidden: false,
            batch_supported: true,
            args: &[],
            options: &[
                OptionDef { name: "filename", description: "File name to save the pdf to", is_bool: false, short: None },
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
            args: &[ArgDef { name: "index", description: "Zero-based tab index from tab-list output. If omitted, current tab is closed.", optional: true }],
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
                OptionDef { name: "all", description: "List all browser sessions across all workspaces", is_bool: true, short: None },
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
        // ---- Server Admin ----
        CommandDef {
            name: "upgrade",
            description: "Upgrade browser4-cli and the Browser4 runtime to the latest version (or a specified release tag). Uses npm when available, otherwise the platform install script",
            category: Category::Install,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "tag",
                    description: "Release tag to upgrade to, e.g. v4.11.0 (defaults to latest release)",
                    is_bool: false,
                    short: None,
                },
                OptionDef {
                    name: "force",
                    description: "Force re-download even when the requested version is already installed",
                    is_bool: true,
                    short: None,
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
            name: "stop",
            description: "Gracefully stop the Browser4 server",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "status",
            description: "Show Browser4 server status (version, port, health)",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "server",
                    description: "Server URL to check (defaults to saved or http://127.0.0.1:8182)",
                    is_bool: false,
                    short: None,
                },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |args| {
                let mut params = json!({});
                if let Some(server) = get_opt_str(args, "server") {
                    params["server"] = json!(server);
                }
                params
            },
        },
        CommandDef {
            name: "doctor",
            description: "Run system diagnostics: build info, logs, and metrics",
            category: Category::Browsers,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "server",
                    description: "Server URL to check (defaults to saved or http://127.0.0.1:8182)",
                    is_bool: false,
                    short: None,
                },
                OptionDef {
                    name: "file",
                    description: "Backend log file to tail (default: pulsar). Available logs: pulsar, pulsar.api, pulsar.s, pulsar.hv, pulsar.bs, pulsar.pg, pulsar.m, pulsar.c, pulsar.sql, pulsar.dc",
                    is_bool: false,
                    short: None,
                },
                OptionDef {
                    name: "lines",
                    description: "Number of recent log lines to show (default: 50, max: 500)",
                    is_bool: false,
                    short: None,
                },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |args| {
                let mut params = json!({});
                if let Some(server) = get_opt_str(args, "server") {
                    params["server"] = json!(server);
                }
                if let Some(file) = get_opt_str(args, "file") {
                    params["file"] = json!(file);
                }
                if let Some(lines) = get_opt_str(args, "lines") {
                    params["lines"] = json!(lines);
                }
                params
            },
        },
        // ---- Agent ----
        CommandDef {
            name: "extract",
            description: "Extract structured data from the current page",
            category: Category::Agent,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef { name: "instruction", description: "What data to extract, e.g. 'product name, price, ratings'", optional: false }],
            options: &[
                OptionDef { name: "schema", description: "JSON schema to constrain the extracted data structure", is_bool: false, short: None },
                OptionDef { name: "filename", description: "Save extracted content to a file instead of printing to stdout", is_bool: false, short: None },
                OptionDef { name: "raw", description: "Print extracted content directly to stdout (by default it is saved to a file)", is_bool: true, short: None },
            ],
            tool_name_fn: |_| "agent_extract".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "instruction": get_str(args, "instruction").unwrap_or_default() });
                if let Some(s) = get_opt_str(args, "schema") { p["schema"] = json!(s); }
                if let Some(f) = get_opt_str(args, "filename") { p["filename"] = json!(f); }
                if let Some(true) = get_bool(args, "raw") { p["raw"] = json!(true); }
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
                OptionDef { name: "selector", description: "CSS selector to limit the scope of summarization", is_bool: false, short: None },
                OptionDef { name: "filename", description: "Save summary to a file instead of printing to stdout", is_bool: false, short: None },
                OptionDef { name: "raw", description: "Print summary directly to stdout (by default it is saved to a file)", is_bool: true, short: None },
            ],
            tool_name_fn: |_| "agent_summarize".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(i) = get_opt_str(args, "instruction") { p["instruction"] = json!(i); }
                if let Some(s) = get_opt_str(args, "selector") { p["selector"] = json!(s); }
                if let Some(f) = get_opt_str(args, "filename") { p["filename"] = json!(f); }
                if let Some(true) = get_bool(args, "raw") { p["raw"] = json!(true); }
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
        CommandDef {
            name: "agent-list",
            description: "List all tracked agent tasks and their status",
            category: Category::Agent,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        // ---- Swarm ----
        CommandDef {
            name: "swarm-create",
            description: "Create a swarm scrape session with parallel browser contexts",
            category: Category::Swarm,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef { name: "profile-mode", description: "Browser profile mode (default: SEQUENTIAL; supported: SEQUENTIAL or TEMPORARY)", is_bool: false, short: None },
                OptionDef { name: "max-open-tabs", description: "Maximum open tabs per browser context (default: 8)", is_bool: false, short: None },
                OptionDef { name: "max-browser-contexts", description: "Number of isolated browser environments (default: 2)", is_bool: false, short: None },
                OptionDef { name: "display-mode", description: "Display mode: GUI, HEADLESS, SUPERVISED", is_bool: false, short: None },
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
            hidden: false,
            batch_supported: false,
            args: &[ArgDef { name: "url", description: "URL or X-SQL payload to submit", optional: true }],
            options: &[
                OptionDef { name: "seed-file", description: "File containing URLs to submit, one per line", is_bool: false, short: None },
                OptionDef { name: "sql", description: "X-SQL query to execute against the page. Use @url as placeholder for the target URL. Prefix with @ to read from file (e.g. --sql @query.sql)", is_bool: false, short: None },
                OptionDef { name: "deadline", description: "Deadline for task completion (ISO 8601, e.g. 2026-02-24T23:59:59Z)", is_bool: false, short: None },
                OptionDef { name: "expires", description: "Cache expiration duration (e.g. 1d, 1h)", is_bool: false, short: None },
                OptionDef { name: "refresh", description: "Force a fresh fetch, ignoring cache", is_bool: true, short: None },
                OptionDef { name: "parse", description: "Parse page immediately after fetching", is_bool: true, short: None },
                OptionDef { name: "store-content", description: "Persist page content to storage", is_bool: true, short: None },
            ],
            tool_name_fn: |_| "command_run".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(v) = get_opt_str(args, "url") { p["url"] = json!(v); }
                if let Some(v) = get_opt_str(args, "seed-file") { p["seedFile"] = json!(v); }
                if let Some(v) = get_opt_str(args, "sql") { p["sql"] = json!(v); }
                if let Some(v) = get_opt_str(args, "deadline") { p["deadline"] = json!(v); }
                if let Some(v) = get_opt_str(args, "expires") { p["expires"] = json!(v); }
                if let Some(b) = get_bool(args, "refresh") { p["refresh"] = json!(b); }
                if let Some(b) = get_bool(args, "parse") { p["parse"] = json!(b); }
                if let Some(b) = get_bool(args, "store-content") { p["storeContent"] = json!(b); }
                p
            },
        },
        CommandDef {
            name: "swarm-query",
            description: "Submit an X-SQL query to extract structured data from a loaded webpage",
            category: Category::Swarm,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef { name: "url", description: "Target page URL to load and run the query against", optional: false }],
            options: &[
                OptionDef { name: "sql", description: "X-SQL query to execute. Use @url as placeholder for the target URL. Prefix with @ to read from file (e.g. --sql @query.sql)", is_bool: false, short: None },
                OptionDef { name: "seed-file", description: "File containing URLs to submit, one per line (direct path, no @ prefix)", is_bool: false, short: None },
                OptionDef { name: "deadline", description: "Deadline for task completion (ISO 8601, e.g. 2026-02-24T23:59:59Z)", is_bool: false, short: None },
                OptionDef { name: "expires", description: "Cache expiration duration (e.g. 1d, 1h)", is_bool: false, short: None },
                OptionDef { name: "refresh", description: "Force a fresh fetch, ignoring cache", is_bool: true, short: None },
            ],
            tool_name_fn: |_| "swarm_query".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(v) = get_opt_str(args, "url") { p["url"] = json!(v); }
                if let Some(v) = get_opt_str(args, "sql") { p["sql"] = json!(v); }
                if let Some(v) = get_opt_str(args, "seed-file") { p["seedFile"] = json!(v); }
                if let Some(v) = get_opt_str(args, "deadline") { p["deadline"] = json!(v); }
                if let Some(v) = get_opt_str(args, "expires") { p["expires"] = json!(v); }
                if let Some(b) = get_bool(args, "refresh") { p["refresh"] = json!(b); }
                p
            },
        },
        CommandDef {
            name: "swarm-status",
            description: "Check the status of a scrape job",
            category: Category::Swarm,
            hidden: false,
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
            hidden: false,
            batch_supported: false,
            args: &[ArgDef { name: "id", description: "Task ID returned by swarm submit", optional: false }],
            options: &[],
            tool_name_fn: |_| "command_result".to_string(),
            tool_params_fn: |args| {
                json!({ "id": get_str(args, "id").unwrap_or_default() })
            },
        },
        CommandDef {
            name: "swarm-list",
            description: "List all tracked swarm tasks and their status",
            category: Category::Swarm,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "crawl",
            description: "Crawl a website starting from a URL, following links up to a configurable depth",
            category: Category::Swarm,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef {
                name: "url",
                description: "The starting URL for the crawl",
                optional: false,
            }],
            options: &[
                OptionDef { name: "depth", description: "Maximum crawl depth (default: 1)", is_bool: false, short: Some("d") },
                OptionDef { name: "out-link-selector", description: "CSS selector to extract links from each page", is_bool: false, short: Some("ol") },
                OptionDef { name: "out-link-pattern", description: "Regex pattern to filter extracted links (default: .+)", is_bool: false, short: Some("olp") },
                OptionDef { name: "top-links", description: "Maximum links to extract per page (default: 20)", is_bool: false, short: Some("tl") },
                OptionDef { name: "args", description: "Additional LoadOptions passthrough (e.g. -a \"-refresh -nMaxRetry 5\")", is_bool: false, short: Some("a") },
                OptionDef { name: "refresh", description: "Force a fresh fetch, ignoring cache", is_bool: true, short: None },
                OptionDef { name: "parse", description: "Parse each page immediately after fetching", is_bool: true, short: None },
                OptionDef { name: "expires", description: "Cache expiration duration (e.g. 1d, 1h, 30m)", is_bool: false, short: None },
                OptionDef { name: "store-content", description: "Persist page content to storage", is_bool: true, short: None },
                OptionDef { name: "priority", description: "Queue priority (lower = higher priority)", is_bool: false, short: Some("p") },
                OptionDef { name: "page-load-timeout", description: "Maximum time to wait for page load", is_bool: false, short: None },
                OptionDef { name: "ignore-url-query", description: "Remove query parameters from URLs during normalization", is_bool: true, short: None },
                OptionDef { name: "no-norm", description: "Disable URL normalization", is_bool: true, short: None },
                OptionDef { name: "readonly", description: "Non-destructive mode (no page modifications)", is_bool: true, short: None },
                OptionDef { name: "background", description: "Submit crawl and return immediately; use 'crawl list' to track progress", is_bool: true, short: Some("bg") },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(v) = get_opt_str(args, "url") { p["url"] = json!(v); }
                if let Some(true) = get_bool(args, "background") { p["background"] = json!(true); }

                // Build the LoadOptions args string from individual flags
                let mut load_opts = Vec::new();
                if let Some(v) = get_opt_str(args, "out-link-selector") {
                    load_opts.push(format!("-outLink \"{}\"", v));
                }
                if let Some(v) = get_opt_str(args, "out-link-pattern") {
                    load_opts.push(format!("-outLinkPattern \"{}\"", v));
                }
                if let Some(v) = get_opt_str(args, "top-links") {
                    load_opts.push(format!("-topLinks {}", v));
                }
                if let Some(v) = get_opt_str(args, "expires") {
                    load_opts.push(format!("-expires {}", v));
                }
                if let Some(v) = get_opt_str(args, "page-load-timeout") {
                    load_opts.push(format!("-pageLoadTimeout {}", v));
                }
                if let Some(v) = get_opt_str(args, "priority") {
                    load_opts.push(format!("-priority {}", v));
                }
                if let Some(true) = get_bool(args, "refresh") {
                    load_opts.push("-refresh".to_string());
                }
                if let Some(true) = get_bool(args, "parse") {
                    load_opts.push("-parse".to_string());
                }
                if let Some(true) = get_bool(args, "store-content") {
                    load_opts.push("-storeContent".to_string());
                }
                if let Some(true) = get_bool(args, "ignore-url-query") {
                    load_opts.push("-ignoreUrlQuery".to_string());
                }
                if let Some(true) = get_bool(args, "no-norm") {
                    load_opts.push("-noNorm".to_string());
                }
                if let Some(true) = get_bool(args, "readonly") {
                    load_opts.push("-readonly".to_string());
                }
                // Append raw args passthrough (allows any LoadOptions field)
                if let Some(v) = get_opt_str(args, "args") {
                    load_opts.push(v.to_string());
                }
                if !load_opts.is_empty() {
                    p["args"] = json!(load_opts.join(" "));
                }

                // Depth
                if let Some(v) = get_opt_str(args, "depth") {
                    p["depth"] = json!(v.parse::<i32>().unwrap_or(1));
                } else {
                    p["depth"] = json!(1);
                }

                p
            },
        },
        CommandDef {
            name: "crawl-list",
            description: "List all tracked crawl tasks and their status",
            category: Category::Swarm,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        // ---- Snapshot ----
        CommandDef {
            name: "domsnapshot",
            description: "Capture a static DOM snapshot, save it in Browser4's page storage, and return metadata (URL, title, timestamps, image/link counts, interactive elements with tag/class/id/aria/bounding-box)",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| "dom_snapshot_capture".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "domsnapshot-get",
            description: "Extract elements from the DOM snapshot stored in Browser4's page storage (text, html, attr)",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "field", description: "What to extract: text, html, or attr", optional: false },
                ArgDef { name: "selector", description: "CSS selector (defaults to :root; required for attr)", optional: true },
                ArgDef { name: "name", description: "Attribute name (required for attr field)", optional: true },
            ],
            options: &[
                OptionDef { name: "page", short: None, is_bool: false, description: "Page number (1-based, default: 1)" },
                OptionDef { name: "page-size", short: None, is_bool: false, description: "Lines per page (default: 500)" },
                OptionDef { name: "all", short: None, is_bool: true, description: "Show all output, disabling pagination" },
            ],
            tool_name_fn: |_| "dom_snapshot_scrape".to_string(),
            tool_params_fn: |args| {
                let field = get_str(args, "field").unwrap_or_default();
                let selector = get_opt_str(args, "selector").unwrap_or(":root");
                let mut p = json!({ "field": field, "selector": selector });
                if let Some(name) = get_opt_str(args, "name") { p["attrName"] = json!(name); }
                p
            },
        },
        CommandDef {
            name: "domsnapshot-get-all",
            description: "Extract ALL matching elements from the DOM snapshot (querySelectorAll semantics); supports --offset and --limit for pagination",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "field", description: "What to extract: text, html, or attr", optional: false },
                ArgDef { name: "selector", description: "CSS selector (defaults to :root; required for attr)", optional: true },
                ArgDef { name: "name", description: "Attribute name (required for attr field)", optional: true },
            ],
            options: &[
                OptionDef { name: "offset", description: "Skip the first n results (0-based)", is_bool: false, short: None },
                OptionDef { name: "limit", description: "Return at most n results", is_bool: false, short: None },
                OptionDef { name: "page", short: None, is_bool: false, description: "Page number for paginated output (default: 1)" },
                OptionDef { name: "page-size", short: None, is_bool: false, description: "Lines per page (default: 500)" },
                OptionDef { name: "all", short: None, is_bool: true, description: "Show all output, disabling pagination" },
            ],
            tool_name_fn: |_| "dom_snapshot_scrape_all".to_string(),
            tool_params_fn: |args| {
                let field = get_str(args, "field").unwrap_or_default();
                let selector = get_opt_str(args, "selector").unwrap_or(":root");
                let mut p = json!({ "field": field, "selector": selector });
                if let Some(name) = get_opt_str(args, "name") { p["attrName"] = json!(name); }
                if let Some(off) = get_opt_str(args, "offset") {
                    if let Ok(n) = off.parse::<i32>() { p["offset"] = json!(n); }
                }
                if let Some(lim) = get_opt_str(args, "limit") {
                    if let Ok(n) = lim.parse::<i32>() { p["limit"] = json!(n); }
                }
                p
            },
        },
        CommandDef {
            name: "domsnapshot-query",
            description: "Run X-SQL against the DOM snapshot stored in Browser4's page storage via the scrape API",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "url", description: "URL to run the query against. Defaults to the current session's page URL", optional: true },
            ],
            options: &[
                OptionDef {
                    name: "sql",
                    description: "X-SQL query. Use @url as placeholder (unquoted — SQLTemplate handles escaping). Prefix with @ to read from file",
                    is_bool: false,
                    short: None,
                },
                OptionDef {
                    name: "result-only",
                    description: "Extract and print only the resultSet object from the response JSON, omitting wrapper metadata",
                    is_bool: true,
                    short: None,
                },
                OptionDef {
                    name: "output-file",
                    description: "Write output to a file instead of stdout",
                    is_bool: false,
                    short: None,
                },
            ],
            tool_name_fn: |_| "dom_snapshot_query".to_string(),
            tool_params_fn: |args| {
                let sql = get_opt_str(args, "sql").unwrap_or_default();
                let url = get_opt_str(args, "url").unwrap_or("");
                let mut p = json!({ "sql": sql, "url": url });
                // Pass through CLI-side flags
                if let Some(true) = get_bool(args, "result-only") { p["resultOnly"] = json!(true); }
                if let Some(f) = get_opt_str(args, "output-file") { p["outputFile"] = json!(f); }
                p
            },
        },
        CommandDef {
            name: "domsnapshot-export",
            description: "Export snapshot HTML from Browser4's page storage to a local file",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[
                OptionDef {
                    name: "file",
                    description: "Path to save the HTML file",
                    is_bool: false,
                    short: None,
                },
            ],
            tool_name_fn: |_| "dom_snapshot_export".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(f) = get_opt_str(args, "file") { p["file"] = json!(f); }
                p
            },
        },
        CommandDef {
            name: "domsnapshot-summary",
            description: "Generate a compressed Web Page Summary Index (WPSI) from the stored DOM snapshot — preserves page structure, key nodes, and stats in <1% of original HTML size",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| "dom_snapshot_summary".to_string(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "domsnapshot-grep",
            description: "Search the DOM snapshot HTML using regex patterns with grep-style output",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "pattern", description: "Regex or literal pattern to search for", optional: false },
            ],
            options: &[
                OptionDef { name: "ignore-case", short: Some("i"), is_bool: true, description: "Case-insensitive matching" },
                OptionDef { name: "no-line-number", short: None, is_bool: true, description: "Suppress line numbers in output" },
                OptionDef { name: "after-context", short: Some("A"), is_bool: false, description: "Show N lines after each match" },
                OptionDef { name: "before-context", short: Some("B"), is_bool: false, description: "Show N lines before each match" },
                OptionDef { name: "context", short: Some("C"), is_bool: false, description: "Show N lines before and after each match" },
                OptionDef { name: "invert-match", short: Some("v"), is_bool: true, description: "Select non-matching lines" },
                OptionDef { name: "count", short: Some("c"), is_bool: true, description: "Print only the count of matching lines" },
                OptionDef { name: "files-with-matches", short: Some("l"), is_bool: true, description: "Print only whether matches exist" },
                OptionDef { name: "fixed-strings", short: Some("F"), is_bool: true, description: "Treat pattern as a literal string, not regex" },
                OptionDef { name: "word-regexp", short: Some("w"), is_bool: true, description: "Match only whole words" },
                OptionDef { name: "selector", short: None, is_bool: false, description: "CSS selector to scope the search to" },
                OptionDef { name: "page", short: None, is_bool: false, description: "Page number (1-based, default: 1)" },
                OptionDef { name: "page-size", short: None, is_bool: false, description: "Lines per page (default: 500)" },
                OptionDef { name: "all", short: None, is_bool: true, description: "Show all output, disabling pagination" },
            ],
            tool_name_fn: |_| "dom_snapshot_export".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                for (k, v) in args {
                    if k != "_" {
                        p[k] = v.clone();
                    }
                }
                p
            },
        },
        CommandDef {
            name: "domsnapshot-inspect",
            description: "Inspect DOM structure and suggest CSS selectors for recurring patterns (product cards, prices, titles)",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[
                ArgDef { name: "selector", description: "CSS selector to scope inspection (default: :root; use e.g. .product-card for recurring pattern detection)", optional: true },
            ],
            options: &[
                OptionDef { name: "max", description: "Max matching elements to analyze (default: 10)", is_bool: false, short: None },
                OptionDef { name: "depth", description: "Max descendant depth for selector suggestions (default: 5)", is_bool: false, short: None },
            ],
            tool_name_fn: |_| "dom_snapshot_inspect".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({});
                if let Some(s) = get_opt_str(args, "selector") { p["selector"] = json!(s); }
                if let Some(m) = get_opt_str(args, "max") {
                    if let Ok(n) = m.parse::<i32>() { p["max"] = json!(n); }
                }
                if let Some(d) = get_opt_str(args, "depth") {
                    if let Ok(n) = d.parse::<i32>() { p["depth"] = json!(n); }
                }
                p
            },
        },
        CommandDef {
            name: "generate-locator",
            description: "Generate a unique CSS selector path for an element identified by a snapshot ref (e5) or CSS selector",
            category: Category::Snapshot,
            hidden: false,
            batch_supported: false,
            args: &[ArgDef { name: "ref", description: "Element reference (e5, backend:15) or CSS selector", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_generate_locator".to_string(),
            tool_params_fn: |args| {
                let r = get_str(args, "ref").unwrap_or_default();
                json!({ "ref": r })
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
            "uninstall",
            "doctor",
            "batch",
            "loop",
            "goto",
            "click",
            "type",
            "fill",
            "state-save",
            "state-load",
            "cookie-list",
            "cookie-get",
            "cookie-set",
            "cookie-delete",
            "cookie-clear",
            "localstorage-list",
            "localstorage-get",
            "localstorage-set",
            "localstorage-delete",
            "localstorage-clear",
            "sessionstorage-list",
            "sessionstorage-get",
            "sessionstorage-set",
            "sessionstorage-delete",
            "sessionstorage-clear",
            "snapshot",
            "screenshot",
            "extract",
            "summarize",
            "agent-run",
            "agent-status",
            "agent-result",
            "swarm-create",
            "swarm-submit",
            "swarm-query",
            "swarm-status",
            "swarm-result",
            "crawl",
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
    fn test_state_load_tool_name_and_params() {
        let map = commands_map();
        let cmd = map.get("state-load").unwrap();
        let mut args = HashMap::new();
        args.insert("filename".to_string(), json!("auth-state.json"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_load_storage_state");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["filename"], "auth-state.json");
    }

    #[test]
    fn test_cookie_set_tool_name_and_params() {
        let map = commands_map();
        let cmd = map.get("cookie-set").unwrap();
        let mut args = HashMap::new();
        args.insert("name".to_string(), json!("session"));
        args.insert("value".to_string(), json!("abc123"));
        args.insert("path".to_string(), json!("/"));
        args.insert("httpOnly".to_string(), json!(true));
        args.insert("sameSite".to_string(), json!("Lax"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_load_storage_state");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["name"], "session");
        assert_eq!(params["value"], "abc123");
        assert_eq!(params["path"], "/");
        assert_eq!(params["httpOnly"], true);
        assert_eq!(params["sameSite"], "Lax");
    }

    #[test]
    fn test_cookie_delete_tool_name_and_params() {
        let map = commands_map();
        let cmd = map.get("cookie-delete").unwrap();
        let mut args = HashMap::new();
        args.insert("name".to_string(), json!("session"));
        args.insert("domain".to_string(), json!("example.com"));
        assert_eq!((cmd.tool_name_fn)(&args), "delete_cookies");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["name"], "session");
        assert_eq!(params["domain"], "example.com");
    }

    #[test]
    fn test_localstorage_set_params() {
        let map = commands_map();
        let cmd = map.get("localstorage-set").unwrap();
        let mut args = HashMap::new();
        args.insert("key".to_string(), json!("theme"));
        args.insert("value".to_string(), json!("dark"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_evaluate");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["key"], "theme");
        assert_eq!(params["value"], "dark");
    }

    #[test]
    fn test_sessionstorage_get_params() {
        let map = commands_map();
        let cmd = map.get("sessionstorage-get").unwrap();
        let mut args = HashMap::new();
        args.insert("key".to_string(), json!("step"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_evaluate");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["key"], "step");
    }

    #[test]
    fn test_sessionstorage_set_preserves_numeric_values_as_strings() {
        let map = commands_map();
        let cmd = map.get("sessionstorage-set").unwrap();
        let mut args = HashMap::new();
        args.insert("key".to_string(), json!("step"));
        args.insert("value".to_string(), json!(3));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["key"], "step");
        assert_eq!(params["value"], "3");
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
    fn test_extract_with_filename() {
        let map = commands_map();
        let cmd = map.get("extract").unwrap();
        let mut args = HashMap::new();
        args.insert("instruction".to_string(), json!("product name, price"));
        args.insert("filename".to_string(), json!("my-extract.txt"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["filename"], "my-extract.txt");
    }

    #[test]
    fn test_extract_with_raw() {
        let map = commands_map();
        let cmd = map.get("extract").unwrap();
        let mut args = HashMap::new();
        args.insert("instruction".to_string(), json!("product name, price"));
        args.insert("raw".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["raw"], true);
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
    fn test_summarize_with_filename() {
        let map = commands_map();
        let cmd = map.get("summarize").unwrap();
        let mut args = HashMap::new();
        args.insert("filename".to_string(), json!("my-summary.txt"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["filename"], "my-summary.txt");
    }

    #[test]
    fn test_summarize_with_raw() {
        let map = commands_map();
        let cmd = map.get("summarize").unwrap();
        let mut args = HashMap::new();
        args.insert("raw".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["raw"], true);
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
    fn test_open_params_with_headed() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let mut args = HashMap::new();
        args.insert("headed".to_string(), json!(true));

        let params = (cmd.tool_params_fn)(&args);

        assert_eq!(params["headed"], true);
    }

    #[test]
    fn test_open_params_with_headless() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let mut args = HashMap::new();
        args.insert("headless".to_string(), json!(true));

        let params = (cmd.tool_params_fn)(&args);

        assert_eq!(params["headed"], false);
    }

    #[test]
    fn test_open_params_headless_takes_priority_over_headed() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let mut args = HashMap::new();
        args.insert("headed".to_string(), json!(true));
        args.insert("headless".to_string(), json!(true));

        let params = (cmd.tool_params_fn)(&args);

        assert_eq!(params["headed"], false);
    }

    #[test]
    fn test_open_params_without_headed_or_headless_does_not_set_key() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let args = HashMap::new();

        let params = (cmd.tool_params_fn)(&args);

        assert!(params.get("headed").is_none());
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
    fn test_eval_params_with_css_selector_ref() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        let mut args = HashMap::new();
        args.insert("expression".to_string(), json!("element => element.value"));
        args.insert("ref".to_string(), json!("#my-button"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["expression"], json!("element => element.value"));
        assert_eq!(params["ref"], json!("#my-button"));
    }

    #[test]
    fn test_eval_params_empty_expression() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        let mut args = HashMap::new();
        args.insert("expression".to_string(), json!(""));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["expression"], json!(""));
        assert!(params.get("ref").is_none());
    }

    #[test]
    fn test_eval_params_expression_with_template_literals() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        let mut args = HashMap::new();
        args.insert(
            "expression".to_string(),
            json!("element => `text: ${element.textContent}`"),
        );
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(
            params["expression"],
            json!("element => `text: ${element.textContent}`")
        );
    }

    #[test]
    fn test_eval_not_hidden_and_batch_supported() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        assert!(!cmd.hidden, "eval should not be hidden from help");
        assert!(cmd.batch_supported, "eval should support batch mode");
    }

    #[test]
    fn test_eval_category_core() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        assert_eq!(cmd.category, Category::Core);
    }

    #[test]
    fn test_eval_has_file_option() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        let file_opt = cmd
            .options
            .iter()
            .find(|o| o.name == "file")
            .expect("eval should have a --file option");
        assert!(!file_opt.is_bool, "--file should not be a boolean flag");
        assert!(
            file_opt
                .description
                .contains("Read JavaScript expression from a file"),
            "--file description should mention reading from a file"
        );
    }

    #[test]
    fn test_eval_expression_arg_is_optional() {
        let map = commands_map();
        let cmd = map.get("eval").unwrap();
        let expr_arg = cmd
            .args
            .iter()
            .find(|a| a.name == "expression")
            .expect("eval should have an expression argument");
        assert!(expr_arg.optional, "expression argument should be optional");
    }

    #[test]
    fn test_mousewheel_params_preserve_decimal_numbers() {
        let map = commands_map();
        let cmd = map.get("mousewheel").unwrap();
        let mut args = HashMap::new();
        args.insert("dx".to_string(), json!(1.5));
        args.insert("dy".to_string(), json!(-2.25));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["deltaX"], json!(1.5));
        assert_eq!(params["deltaY"], json!(-2.25));
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
        assert!(swarm_cmds.contains(&"swarm-query"));
        assert!(swarm_cmds.contains(&"swarm-status"));
        assert!(swarm_cmds.contains(&"swarm-result"));
    }

    #[test]
    fn test_advanced_commands_are_hidden_from_global_help() {
        let map = commands_map();
        for name in [
            "console",
            "agent-run",
            "agent-status",
            "agent-result",
            "summarize",
        ] {
            assert!(map.get(name).unwrap().hidden, "{name} should stay hidden");
        }
    }

    // -----------------------------------------------------------------------
    // Session-lifecycle command definitions
    // -----------------------------------------------------------------------

    #[test]
    fn test_upgrade_params_capture_tag_and_force() {
        let map = commands_map();
        let cmd = map.get("upgrade").expect("upgrade command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.category, Category::Install);

        // With no args → empty params.
        let args: HashMap<String, Value> = HashMap::new();
        let params = (cmd.tool_params_fn)(&args);
        assert!(params.get("tag").is_none());
        assert!(params.get("force").is_none());

        // With tag.
        let mut args = HashMap::new();
        args.insert("tag".to_string(), json!("v4.11.0"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["tag"], "v4.11.0");

        // With --force.
        let mut args = HashMap::new();
        args.insert("force".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["force"], true);

        // With both tag and --force.
        let mut args = HashMap::new();
        args.insert("tag".to_string(), json!("v4.11.0"));
        args.insert("force".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["tag"], "v4.11.0");
        assert_eq!(params["force"], true);

        assert!((cmd.tool_name_fn)(&args).is_empty());
    }

    #[test]
    fn test_stop_command_no_args() {
        let map = commands_map();
        let cmd = map.get("stop").expect("stop command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.args.len(), 0);
        assert_eq!(cmd.options.len(), 0);
        let args: HashMap<String, Value> = HashMap::new();
        assert!((cmd.tool_name_fn)(&args).is_empty());
    }

    #[test]
    fn test_status_command_server_option() {
        let map = commands_map();
        let cmd = map.get("status").expect("status command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.args.len(), 0);
        assert_eq!(cmd.options.len(), 1);
        assert_eq!(cmd.options[0].name, "server");
    }

    #[test]
    fn test_doctor_command_defined() {
        let map = commands_map();
        let cmd = map.get("doctor").expect("doctor command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.category, Category::Browsers);
        assert!(!cmd.batch_supported);
        assert_eq!(cmd.args.len(), 0);
        assert_eq!(cmd.options.len(), 3);
        let option_names: Vec<&str> = cmd.options.iter().map(|o| o.name).collect();
        assert!(option_names.contains(&"server"));
        assert!(option_names.contains(&"file"));
        assert!(option_names.contains(&"lines"));
        let args: HashMap<String, Value> = HashMap::new();
        assert!((cmd.tool_name_fn)(&args).is_empty());
    }

    #[test]
    fn test_close_all_no_args() {
        let map = commands_map();
        let cmd = map.get("close-all").expect("close-all command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.args.len(), 0);
        assert_eq!(cmd.options.len(), 0);
    }

    #[test]
    fn test_kill_all_no_args() {
        let map = commands_map();
        let cmd = map.get("kill-all").expect("kill-all command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.args.len(), 0);
        assert_eq!(cmd.options.len(), 0);
    }

    #[test]
    fn test_list_command_all_option() {
        let map = commands_map();
        let cmd = map.get("list").expect("list command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.args.len(), 0);
        assert_eq!(cmd.options.len(), 1);
        assert_eq!(cmd.options[0].name, "all");
        assert!(cmd.options[0].is_bool);
    }

    // -----------------------------------------------------------------------
    // Scroll command tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_scroll_down_uses_scroll_by_tool() {
        let map = commands_map();
        let cmd = map.get("scroll").expect("scroll command must exist");
        let mut args = HashMap::new();
        args.insert("direction".to_string(), json!("down"));
        args.insert("pixels".to_string(), json!(500));
        assert_eq!((cmd.tool_name_fn)(&args), "scroll_by");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["pixels"], json!(500.0));
    }

    #[test]
    fn test_scroll_up_uses_negative_pixels() {
        let map = commands_map();
        let cmd = map.get("scroll").expect("scroll command must exist");
        let mut args = HashMap::new();
        args.insert("direction".to_string(), json!("up"));
        args.insert("pixels".to_string(), json!(300));
        assert_eq!((cmd.tool_name_fn)(&args), "scroll_by");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["pixels"], json!(-300.0));
    }

    #[test]
    fn test_scroll_right_uses_mouse_wheel_tool() {
        let map = commands_map();
        let cmd = map.get("scroll").expect("scroll command must exist");
        let mut args = HashMap::new();
        args.insert("direction".to_string(), json!("right"));
        args.insert("pixels".to_string(), json!(200));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_mouse_wheel");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["deltaX"], json!(200.0));
        assert_eq!(params["deltaY"], json!(0.0));
    }

    #[test]
    fn test_scroll_left_uses_negative_delta_x() {
        let map = commands_map();
        let cmd = map.get("scroll").expect("scroll command must exist");
        let mut args = HashMap::new();
        args.insert("direction".to_string(), json!("left"));
        args.insert("pixels".to_string(), json!(150));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_mouse_wheel");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["deltaX"], json!(-150.0));
        assert_eq!(params["deltaY"], json!(0.0));
    }

    #[test]
    fn test_scroll_defaults_down_for_unknown_direction() {
        let map = commands_map();
        let cmd = map.get("scroll").expect("scroll command must exist");
        let mut args = HashMap::new();
        args.insert("direction".to_string(), json!("diagonal"));
        args.insert("pixels".to_string(), json!(100));
        assert_eq!((cmd.tool_name_fn)(&args), "scroll_by");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["pixels"], json!(100.0));
    }

    #[test]
    fn test_scroll_handles_case_insensitive_direction() {
        let map = commands_map();
        let cmd = map.get("scroll").expect("scroll command must exist");
        let mut args = HashMap::new();
        args.insert("direction".to_string(), json!("DOWN"));
        args.insert("pixels".to_string(), json!(500));
        assert_eq!((cmd.tool_name_fn)(&args), "scroll_by");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["pixels"], json!(500.0));
    }

    // -----------------------------------------------------------------------
    // Wait command tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_wait_selector_uses_wait_for_selector_tool() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("target".to_string(), json!("e1"));
        assert_eq!((cmd.tool_name_fn)(&args), "wait_for_selector");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "e1");
    }

    #[test]
    fn test_wait_milliseconds_uses_delay_tool() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("target".to_string(), json!(2000));
        assert_eq!((cmd.tool_name_fn)(&args), "delay");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["millis"], json!(2000));
    }

    #[test]
    fn test_wait_text_uses_wait_for_function_tool() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("text".to_string(), json!("Success"));
        assert_eq!((cmd.tool_name_fn)(&args), "wait_for_function");
        let params = (cmd.tool_params_fn)(&args);
        let func = params["pageFunction"].as_str().unwrap();
        assert!(func.contains("Success"));
        assert!(func.contains("document.body.innerText.includes"));
        assert!(!func.starts_with("() =>"));
        assert_eq!(params["timeoutMillis"], json!(30000));
    }

    #[test]
    fn test_wait_text_properly_json_escapes() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("text".to_string(), json!("it's \"done\""));
        assert_eq!((cmd.tool_name_fn)(&args), "wait_for_function");
        let params = (cmd.tool_params_fn)(&args);
        let func = params["pageFunction"].as_str().unwrap();
        // JSON escaping produces double-quoted string literals that are
        // valid JS.  The text should appear inside a JSON double-quoted
        // string inside the JS expression.
        assert!(func.contains(r#""it's \"done\"""#));
        assert!(func.contains("document.body.innerText.includes"));
        assert!(!func.starts_with("() =>"));
    }

    #[test]
    fn test_wait_url_uses_wait_for_page_tool() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("**/dashboard"));
        assert_eq!((cmd.tool_name_fn)(&args), "wait_for_page");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["url"], "**/dashboard");
        assert_eq!(params["timeoutMillis"], json!(30000));
    }

    #[test]
    fn test_wait_load_networkidle_uses_wait_for_function_tool() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("load".to_string(), json!("networkidle"));
        assert_eq!((cmd.tool_name_fn)(&args), "wait_for_function");
        let params = (cmd.tool_params_fn)(&args);
        let func = params["pageFunction"].as_str().unwrap();
        // networkidle now uses a tracking expression, not just readyState
        assert!(func.contains("__b4_ni"));
        assert!(func.contains("readyState"));
        assert_eq!(params["timeoutMillis"], json!(30000));
    }

    #[test]
    fn test_wait_load_domcontentloaded_uses_wait_for_function_tool() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("load".to_string(), json!("domcontentloaded"));
        assert_eq!((cmd.tool_name_fn)(&args), "wait_for_function");
        let params = (cmd.tool_params_fn)(&args);
        let func = params["pageFunction"].as_str().unwrap();
        assert_eq!(func, "document.readyState !== 'loading'");
        assert_eq!(params["timeoutMillis"], json!(30000));
    }

    #[test]
    fn test_wait_fn_uses_wait_for_function_tool() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let mut args = HashMap::new();
        args.insert("fn".to_string(), json!("window.myApp.ready === true"));
        assert_eq!((cmd.tool_name_fn)(&args), "wait_for_function");
        let params = (cmd.tool_params_fn)(&args);
        let func = params["pageFunction"].as_str().unwrap();
        assert_eq!(func, "window.myApp.ready === true");
        assert_eq!(params["timeoutMillis"], json!(30000));
    }

    #[test]
    fn test_wait_command_is_not_hidden() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        assert!(!cmd.hidden);
    }

    #[test]
    fn test_wait_command_has_all_options() {
        let map = commands_map();
        let cmd = map.get("wait").expect("wait command must exist");
        let option_names: Vec<&str> = cmd.options.iter().map(|o| o.name).collect();
        assert!(option_names.contains(&"text"));
        assert!(option_names.contains(&"url"));
        assert!(option_names.contains(&"load"));
        assert!(option_names.contains(&"fn"));
    }

    // -----------------------------------------------------------------------
    // Get command tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_get_text_uses_select_first_text_or_null_tool() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("text"));
        args.insert("selector".to_string(), json!("e5"));
        assert_eq!((cmd.tool_name_fn)(&args), "select_first_text_or_null");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "e5");
    }

    #[test]
    fn test_get_html_uses_browser_evaluate_tool() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("html"));
        args.insert("selector".to_string(), json!("e3"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_evaluate");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["expression"], "element => element.innerHTML");
        assert_eq!(params["ref"], "e3");
    }

    #[test]
    fn test_get_box_uses_bounding_box_tool() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("box"));
        args.insert("selector".to_string(), json!("e2"));
        assert_eq!((cmd.tool_name_fn)(&args), "bounding_box");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "e2");
    }

    #[test]
    fn test_get_styles_uses_browser_evaluate_tool() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("styles"));
        args.insert("selector".to_string(), json!("e9"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_evaluate");
        let params = (cmd.tool_params_fn)(&args);
        let expr = params["expression"].as_str().unwrap();
        assert!(expr.contains("getComputedStyle"));
        assert!(expr.contains("getPropertyValue"));
        assert!(!expr.contains("`"));
        assert_eq!(params["ref"], "e9");
    }

    #[test]
    fn test_get_property_uses_select_first_property_value_or_null_tool() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("property"));
        args.insert("selector".to_string(), json!("e10"));
        args.insert("name".to_string(), json!("value"));
        assert_eq!(
            (cmd.tool_name_fn)(&args),
            "select_first_property_value_or_null"
        );
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "e10");
        assert_eq!(params["propName"], "value");
    }

    #[test]
    fn test_get_attr_uses_select_first_attribute_or_null_tool() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("attr"));
        args.insert("selector".to_string(), json!("e10"));
        args.insert("name".to_string(), json!("href"));
        assert_eq!((cmd.tool_name_fn)(&args), "select_first_attribute_or_null");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "e10");
        assert_eq!(params["attrName"], "href");
    }

    #[test]
    fn test_get_defaults_to_text_for_unknown_mode() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("unknown"));
        args.insert("selector".to_string(), json!("e1"));
        assert_eq!((cmd.tool_name_fn)(&args), "select_first_text_or_null");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "e1");
    }

    #[test]
    fn test_get_handles_case_insensitive_mode() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("TEXT"));
        args.insert("selector".to_string(), json!("e5"));
        assert_eq!((cmd.tool_name_fn)(&args), "select_first_text_or_null");
    }

    #[test]
    fn test_get_text_with_css_selector() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("text"));
        args.insert("selector".to_string(), json!(".price"));
        assert_eq!((cmd.tool_name_fn)(&args), "select_first_text_or_null");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], ".price");
    }

    #[test]
    fn test_get_html_with_id_selector() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("html"));
        args.insert("selector".to_string(), json!("#main"));
        assert_eq!((cmd.tool_name_fn)(&args), "browser_evaluate");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["ref"], "#main");
    }

    #[test]
    fn test_get_command_is_not_hidden() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.category, Category::Core);
        assert!(cmd.batch_supported);
    }

    #[test]
    fn test_get_command_has_three_args() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        assert_eq!(cmd.args.len(), 3);
        assert_eq!(cmd.args[0].name, "mode");
        assert!(!cmd.args[0].optional);
        assert_eq!(cmd.args[1].name, "selector");
        assert!(!cmd.args[1].optional);
        assert_eq!(cmd.args[2].name, "name");
        assert!(cmd.args[2].optional);
    }

    #[test]
    fn test_get_command_has_no_options() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        assert_eq!(cmd.options.len(), 0);
    }

    #[test]
    fn test_get_styles_expr_does_not_contain_template_literals() {
        let map = commands_map();
        let cmd = map.get("get").expect("get command must exist");
        let mut args = HashMap::new();
        args.insert("mode".to_string(), json!("styles"));
        args.insert("selector".to_string(), json!("e1"));
        let params = (cmd.tool_params_fn)(&args);
        let expr = params["expression"].as_str().unwrap();
        assert!(
            !expr.contains('`'),
            "JS expression must not use template literals"
        );
        assert!(
            !expr.contains("${"),
            "JS expression must not use template interpolation"
        );
    }

    #[test]
    fn test_scroll_command_is_not_hidden() {
        let map = commands_map();
        let cmd = map.get("scroll").expect("scroll command must exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.category, Category::Mouse);
    }

    // ---- domsnapshot tests ----

    #[test]
    fn test_commands_map_contains_dom_snapshot_variants() {
        let map = commands_map();
        for expected in &[
            "domsnapshot",
            "domsnapshot-get",
            "domsnapshot-get-all",
            "domsnapshot-query",
            "domsnapshot-export",
            "domsnapshot-summary",
            "domsnapshot-grep",
            "domsnapshot-inspect",
        ] {
            assert!(map.contains_key(*expected), "Missing command: {}", expected);
        }
    }

    #[test]
    fn test_dom_snapshot_capture_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot").unwrap();
        let args = HashMap::new();
        assert_eq!((cmd.tool_name_fn)(&args), "dom_snapshot_capture");
        let params = (cmd.tool_params_fn)(&args);
        assert!(
            params.as_object().unwrap().is_empty(),
            "capture params should be empty"
        );
    }

    #[test]
    fn test_dom_snapshot_capture_empty_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot").unwrap();
        let args = HashMap::new();
        assert_eq!((cmd.tool_name_fn)(&args), "dom_snapshot_capture");
    }

    #[test]
    fn test_dom_snapshot_get_text_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("text"));
        args.insert("selector".to_string(), json!(".product"));
        assert_eq!((cmd.tool_name_fn)(&args), "dom_snapshot_scrape");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["field"], "text");
        assert_eq!(params["selector"], ".product");
    }

    #[test]
    fn test_dom_snapshot_get_html_defaults_selector_to_root() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("html"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["field"], "html");
        assert_eq!(params["selector"], ":root");
    }

    #[test]
    fn test_dom_snapshot_get_attr_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("attr"));
        args.insert("selector".to_string(), json!(".product"));
        args.insert("name".to_string(), json!("data-id"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["field"], "attr");
        assert_eq!(params["selector"], ".product");
        assert_eq!(params["attrName"], "data-id");
    }

    #[test]
    fn test_dom_snapshot_query_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-query").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        args.insert(
            "sql".to_string(),
            json!("SELECT dom_base_uri(dom) AS url FROM load_and_select('@url', ':root')"),
        );
        assert_eq!((cmd.tool_name_fn)(&args), "dom_snapshot_query");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["url"], "https://example.com");
        assert!(params["sql"].as_str().unwrap().contains("@url"));
    }

    #[test]
    fn test_dom_snapshot_export_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-export").unwrap();
        let mut args = HashMap::new();
        args.insert("file".to_string(), json!("snapshot.html"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["file"], "snapshot.html");
    }

    #[test]
    fn test_is_element_reference_matches_e_notation() {
        assert!(is_element_reference("e5"));
        assert!(is_element_reference("e15"));
        assert!(is_element_reference("backend:15"));
        assert!(is_element_reference("backend:7"));
        assert!(!is_element_reference(".my-class"));
        assert!(!is_element_reference("#my-id"));
        assert!(!is_element_reference("div.content"));
        assert!(!is_element_reference(":root"));
        assert!(!is_element_reference(""));
    }

    #[test]
    fn test_dom_snapshot_commands_are_snapshot_category() {
        let map = commands_map();
        for name in &[
            "domsnapshot",
            "domsnapshot-get",
            "domsnapshot-get-all",
            "domsnapshot-query",
            "domsnapshot-export",
            "domsnapshot-summary",
            "domsnapshot-grep",
            "domsnapshot-inspect",
        ] {
            let cmd = map.get(*name).unwrap();
            assert_eq!(cmd.category, Category::Snapshot);
        }
    }

    // -------------------------------------------------------------------
    // domsnapshot-get-all tests
    // -------------------------------------------------------------------

    #[test]
    fn test_dom_snapshot_get_all_text_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get-all").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("text"));
        args.insert("selector".to_string(), json!(".product"));
        assert_eq!((cmd.tool_name_fn)(&args), "dom_snapshot_scrape_all");
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["field"], "text");
        assert_eq!(params["selector"], ".product");
    }

    #[test]
    fn test_dom_snapshot_get_all_html_defaults_selector_to_root() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get-all").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("html"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["field"], "html");
        assert_eq!(params["selector"], ":root");
    }

    #[test]
    fn test_dom_snapshot_get_all_attr_params() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get-all").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("attr"));
        args.insert("selector".to_string(), json!(".product"));
        args.insert("name".to_string(), json!("data-id"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["field"], "attr");
        assert_eq!(params["selector"], ".product");
        assert_eq!(params["attrName"], "data-id");
    }

    #[test]
    fn test_dom_snapshot_get_all_with_offset_and_limit() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get-all").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("text"));
        args.insert("selector".to_string(), json!("h2 a"));
        args.insert("offset".to_string(), json!("10"));
        args.insert("limit".to_string(), json!("5"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["field"], "text");
        assert_eq!(params["offset"], 10);
        assert_eq!(params["limit"], 5);
    }

    #[test]
    fn test_dom_snapshot_get_all_rejects_invalid_offset() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get-all").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("text"));
        args.insert("offset".to_string(), json!("abc"));
        let params = (cmd.tool_params_fn)(&args);
        assert!(params.get("offset").is_none(), "non-numeric offset should be ignored");
    }

    #[test]
    fn test_dom_snapshot_get_all_rejects_invalid_limit() {
        let map = commands_map();
        let cmd = map.get("domsnapshot-get-all").unwrap();
        let mut args = HashMap::new();
        args.insert("field".to_string(), json!("text"));
        args.insert("limit".to_string(), json!("xyz"));
        let params = (cmd.tool_params_fn)(&args);
        assert!(params.get("limit").is_none(), "non-numeric limit should be ignored");
    }

    // -------------------------------------------------------------------
    // snapshot --limit and --no-compact tests
    // -------------------------------------------------------------------

    #[test]
    fn test_snapshot_no_compact_sends_false() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("no-compact".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["compact"], false, "--no-compact should send compact=false");
    }

    #[test]
    fn test_snapshot_no_compact_overrides_compact_flag() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("no-compact".to_string(), json!(true));
        args.insert("compact".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["compact"], false,
            "--no-compact should take precedence over --compact");
    }

    // -------------------------------------------------------------------
    // generate-locator tests
    // -------------------------------------------------------------------

    #[test]
    fn test_generate_locator_exists_and_not_hidden() {
        let map = commands_map();
        let cmd = map.get("generate-locator").unwrap();
        assert!(!cmd.hidden, "generate-locator should not be hidden from help");
        assert!(!cmd.batch_supported, "generate-locator should not support batch mode");
    }

    #[test]
    fn test_generate_locator_category_is_snapshot() {
        let map = commands_map();
        let cmd = map.get("generate-locator").unwrap();
        assert_eq!(cmd.category, Category::Snapshot);
    }

    #[test]
    fn test_generate_locator_args() {
        let map = commands_map();
        let cmd = map.get("generate-locator").unwrap();
        assert_eq!(cmd.args.len(), 1);
        assert_eq!(cmd.args[0].name, "ref");
        assert!(!cmd.args[0].optional, "ref argument should be required");
    }

    #[test]
    fn test_generate_locator_uses_browser_generate_locator_tool() {
        let map = commands_map();
        let cmd = map.get("generate-locator").unwrap();
        assert_eq!((cmd.tool_name_fn)(&HashMap::new()), "browser_generate_locator");
    }

    #[test]
    fn test_generate_locator_params_with_e_ref() {
        let map = commands_map();
        let cmd = map.get("generate-locator").unwrap();
        let mut args = HashMap::new();
        args.insert("ref".to_string(), json!("e5"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["ref"], "e5");
        // No embedded JS expression — the backend handles selector generation
        assert!(params.get("expression").is_none());
    }

    #[test]
    fn test_generate_locator_params_with_backend_ref() {
        let map = commands_map();
        let cmd = map.get("generate-locator").unwrap();
        let mut args = HashMap::new();
        args.insert("ref".to_string(), json!("backend:15"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["ref"], "backend:15");
    }

    #[test]
    fn test_generate_locator_params_with_css_selector() {
        let map = commands_map();
        let cmd = map.get("generate-locator").unwrap();
        let mut args = HashMap::new();
        args.insert("ref".to_string(), json!(".product-card"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["ref"], ".product-card");
    }

    // -------------------------------------------------------------------
    // snapshot filter option tests
    // -------------------------------------------------------------------

    #[test]
    fn test_snapshot_interactive_flag() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("interactive".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["interactive"], true);
    }

    #[test]
    fn test_snapshot_urls_flag() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("urls".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["urls"], true);
    }

    #[test]
    fn test_snapshot_compact_flag() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("compact".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["compact"], true);
    }

    #[test]
    fn test_snapshot_depth_param() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("depth".to_string(), json!("3"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["depth"], 3);
    }

    #[test]
    fn test_snapshot_selector_param() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("selector".to_string(), json!("#main"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["selector"], "#main");
    }

    #[test]
    fn test_snapshot_all_flags_compose() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("boxes".to_string(), json!(true));
        args.insert("interactive".to_string(), json!(true));
        args.insert("urls".to_string(), json!(true));
        args.insert("compact".to_string(), json!(true));
        args.insert("depth".to_string(), json!("5"));
        args.insert("selector".to_string(), json!(".content"));
        args.insert("filename".to_string(), json!("out.yml"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["boxes"], true);
        assert_eq!(params["interactive"], true);
        assert_eq!(params["urls"], true);
        assert_eq!(params["compact"], true);
        assert_eq!(params["depth"], 5);
        assert_eq!(params["selector"], ".content");
        assert_eq!(params["filename"], "out.yml");
    }

    #[test]
    fn test_snapshot_depth_not_set_when_non_numeric() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("depth".to_string(), json!("not-a-number"));
        let params = (cmd.tool_params_fn)(&args);
        assert!(params.get("depth").is_none());
    }

    #[test]
    fn test_snapshot_no_flags_backward_compat() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let args: HashMap<String, Value> = HashMap::new();
        let params = (cmd.tool_params_fn)(&args);
        assert!(params.get("interactive").is_none());
        assert!(params.get("urls").is_none());
        assert!(params.get("compact").is_none());
        assert!(params.get("depth").is_none());
        assert!(params.get("selector").is_none());
    }

    #[test]
    fn test_snapshot_viewport_renamed_to_viewports_in_params() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("viewport".to_string(), json!("0,2,4"));
        let params = (cmd.tool_params_fn)(&args);
        // Key should be renamed from "viewport" to "viewports"
        assert_eq!(
            params.get("viewports").and_then(|v| v.as_str()),
            Some("0,2,4"),
            "viewport key should be renamed to viewports"
        );
        assert!(
            params.get("viewport").is_none(),
            "viewport key should not be present"
        );
    }

    #[test]
    fn test_snapshot_viewport_range_in_params() {
        let map = commands_map();
        let cmd = map.get("snapshot").unwrap();
        let mut args = HashMap::new();
        args.insert("viewport".to_string(), json!("1-3"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(
            params.get("viewports").and_then(|v| v.as_str()),
            Some("1-3"),
            "viewport range should be passed through"
        );
    }

    // ---- crawl command tests ----

    #[test]
    fn test_crawl_command_exists() {
        let map = commands_map();
        let cmd = map.get("crawl").expect("crawl command should exist");
        assert!(!cmd.hidden);
        assert_eq!(cmd.args.len(), 1);
        assert_eq!(cmd.args[0].name, "url");
        assert!(!cmd.args[0].optional);
        assert_eq!(cmd.category, Category::Swarm);
    }

    #[test]
    fn test_crawl_params_basic() {
        let map = commands_map();
        let cmd = map.get("crawl").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["url"], "https://example.com");
        assert_eq!(params["depth"], 1);
    }

    #[test]
    fn test_crawl_params_with_depth_and_selector() {
        let map = commands_map();
        let cmd = map.get("crawl").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        args.insert("depth".to_string(), json!("3"));
        args.insert("out-link-selector".to_string(), json!("a.product"));
        args.insert("out-link-pattern".to_string(), json!("/product/"));
        args.insert("top-links".to_string(), json!("10"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["url"], "https://example.com");
        assert_eq!(params["depth"], 3);
        let args_str = params["args"].as_str().unwrap_or("");
        assert!(args_str.contains("-outLink \"a.product\""));
        assert!(args_str.contains("-outLinkPattern \"/product/\""));
        assert!(args_str.contains("-topLinks 10"));
    }

    #[test]
    fn test_crawl_params_boolean_flags() {
        let map = commands_map();
        let cmd = map.get("crawl").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        args.insert("refresh".to_string(), json!(true));
        args.insert("parse".to_string(), json!(true));
        args.insert("store-content".to_string(), json!(true));
        args.insert("ignore-url-query".to_string(), json!(true));
        args.insert("readonly".to_string(), json!(true));
        let params = (cmd.tool_params_fn)(&args);
        let args_str = params["args"].as_str().unwrap_or("");
        assert!(args_str.contains("-refresh"));
        assert!(args_str.contains("-parse"));
        assert!(args_str.contains("-storeContent"));
        assert!(args_str.contains("-ignoreUrlQuery"));
        assert!(args_str.contains("-readonly"));
    }

    #[test]
    fn test_crawl_params_args_passthrough() {
        let map = commands_map();
        let cmd = map.get("crawl").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        args.insert("args".to_string(), json!("-nMaxRetry 5 -lazyFlush -interactLevel FAST"));
        let params = (cmd.tool_params_fn)(&args);
        let args_str = params["args"].as_str().unwrap_or("");
        assert!(args_str.contains("-nMaxRetry 5 -lazyFlush -interactLevel FAST"));
    }

    #[test]
    fn test_crawl_params_no_args_when_no_options() {
        let map = commands_map();
        let cmd = map.get("crawl").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        let params = (cmd.tool_params_fn)(&args);
        // No args string should be present since no options were set
        assert!(params.get("args").is_none());
    }

    #[test]
    fn test_crawl_tool_name_empty() {
        let map = commands_map();
        let cmd = map.get("crawl").unwrap();
        let args = HashMap::new();
        assert_eq!((cmd.tool_name_fn)(&args), "");
    }

    #[test]
    fn test_crawl_params_expires_and_priority() {
        let map = commands_map();
        let cmd = map.get("crawl").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com"));
        args.insert("expires".to_string(), json!("1h"));
        args.insert("priority".to_string(), json!("5"));
        args.insert("page-load-timeout".to_string(), json!("30s"));
        let params = (cmd.tool_params_fn)(&args);
        let args_str = params["args"].as_str().unwrap_or("");
        assert!(args_str.contains("-expires 1h"));
        assert!(args_str.contains("-priority 5"));
        assert!(args_str.contains("-pageLoadTimeout 30s"));
    }

    // -------------------------------------------------------------------
    // load_strategy_js tests
    // -------------------------------------------------------------------

    #[test]
    fn test_load_strategy_domcontentloaded() {
        let expr = load_strategy_js("domcontentloaded").unwrap();
        assert_eq!(expr, "document.readyState !== 'loading'");
    }

    #[test]
    fn test_load_strategy_load() {
        let expr = load_strategy_js("load").unwrap();
        assert_eq!(expr, "document.readyState === 'complete'");
    }

    #[test]
    fn test_load_strategy_networkidle() {
        let expr = load_strategy_js("networkidle").unwrap();
        // Should be a non-empty expression containing the sentinel key
        assert!(!expr.is_empty());
        assert!(expr.contains("__b4_ni"));
        assert!(expr.contains("readyState"));
    }

    #[test]
    fn test_load_strategy_case_insensitive() {
        // All strategies should work regardless of case
        assert!(load_strategy_js("DOMContentLoaded").is_ok());
        assert!(load_strategy_js("Load").is_ok());
        assert!(load_strategy_js("NetworkIdle").is_ok());
    }

    #[test]
    fn test_load_strategy_rejects_invalid() {
        let err = load_strategy_js("unknown").unwrap_err();
        assert!(err.contains("invalid --load value"));
        assert!(err.contains("unknown"));
        assert!(err.contains("networkidle"));
        assert!(err.contains("domcontentloaded"));
        assert!(err.contains("load"));
    }

    #[test]
    fn test_load_strategy_rejects_typo() {
        let err = load_strategy_js("networkidleee").unwrap_err();
        assert!(err.contains("invalid --load value"));
    }

    #[test]
    fn test_load_strategy_rejects_empty() {
        let err = load_strategy_js("").unwrap_err();
        assert!(err.contains("invalid --load value"));
    }

    // -------------------------------------------------------------------
    // wait --load command integration tests
    // -------------------------------------------------------------------

    #[test]
    fn test_wait_load_networkidle_params() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("load".to_string(), json!("networkidle"));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["timeoutMillis"], 30000);
        let expr = params["pageFunction"].as_str().unwrap();
        assert!(expr.contains("__b4_ni"));
    }

    #[test]
    fn test_wait_load_domcontentloaded_params() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("load".to_string(), json!("domcontentloaded"));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["timeoutMillis"], 30000);
        assert_eq!(params["pageFunction"], "document.readyState !== 'loading'");
    }

    #[test]
    fn test_wait_load_with_custom_timeout() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("load".to_string(), json!("load"));
        args.insert("timeout".to_string(), json!("60000"));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["timeoutMillis"], 60000);
        assert_eq!(params["pageFunction"], "document.readyState === 'complete'");
    }

    #[test]
    fn test_wait_load_timeout_defaults_when_not_provided() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("load".to_string(), json!("domcontentloaded"));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["timeoutMillis"], 30000);
    }

    #[test]
    fn test_wait_text_respects_timeout() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("text".to_string(), json!("Hello"));
        args.insert("timeout".to_string(), json!("10000"));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["timeoutMillis"], 10000);
        assert!(params["pageFunction"].as_str().unwrap().contains("Hello"));
    }

    #[test]
    fn test_wait_url_respects_timeout() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("url".to_string(), json!("https://example.com/*"));
        args.insert("timeout".to_string(), json!("15000"));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["timeoutMillis"], 15000);
        assert_eq!(params["url"], "https://example.com/*");
    }

    #[test]
    #[should_panic(expected = "invalid --load value")]
    fn test_wait_load_invalid_value_panics() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("load".to_string(), json!("invalid_strategy"));

        (cmd.tool_params_fn)(&args);
    }

    #[test]
    fn test_wait_fn_respects_timeout() {
        let map = commands_map();
        let cmd = map.get("wait").unwrap();
        let mut args = HashMap::new();
        args.insert("fn".to_string(), json!("document.querySelector('.loaded') !== null"));
        args.insert("timeout".to_string(), json!("20000"));

        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params["timeoutMillis"], 20000);
        assert_eq!(
            params["pageFunction"],
            "document.querySelector('.loaded') !== null"
        );
    }
}
