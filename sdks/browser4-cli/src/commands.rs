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

fn get_num(map: &HashMap<String, Value>, key: &str) -> Option<f64> {
    map.get(key).and_then(|v| v.as_f64())
}

// ---------------------------------------------------------------------------
// Command definitions (static)
// ---------------------------------------------------------------------------

pub fn all_commands() -> Vec<CommandDef> {
    vec![
        // ---- Core ----
        CommandDef {
            name: "open",
            description: "Open the browser",
            category: Category::Core,
            hidden: false,
            args: &[ArgDef { name: "url", description: "The URL to navigate to", optional: true }],
            options: &[
                OptionDef { name: "headed", description: "Run browser in headed mode", is_bool: true },
                OptionDef { name: "persistent", description: "Use persistent browser profile", is_bool: true },
                OptionDef { name: "profile", description: "Path to browser profile directory", is_bool: false },
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
                json!({ "url": url })
            },
        },
        CommandDef {
            name: "close",
            description: "Close the browser",
            category: Category::Core,
            hidden: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "goto",
            description: "Navigate to a URL",
            category: Category::Core,
            hidden: false,
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
            args: &[],
            options: &[],
            tool_name_fn: |_| "browser_reload".to_string(),
            tool_params_fn: |_| json!({}),
        },
        // ---- Keyboard ----
        CommandDef {
            name: "press",
            description: "Press a key on the keyboard, `a`, `ArrowLeft`",
            category: Category::Keyboard,
            hidden: false,
            args: &[
                ArgDef { name: "ref_or_key", description: "Key name (e.g. `Enter`), or element reference when followed by a key", optional: false },
                ArgDef { name: "key", description: "Key name when first argument is an element reference", optional: true },
            ],
            options: &[],
            tool_name_fn: |_| "browser_press_key".to_string(),
            tool_params_fn: |args| {
                // 1-arg form: `press Enter`  → ref_or_key is the key
                // 2-arg form: `press e5 Enter` → ref_or_key is ref, key is the key
                if let Some(key) = get_opt_str(args, "key") {
                    json!({
                        "ref": get_str(args, "ref_or_key").unwrap_or_default(),
                        "key": key,
                    })
                } else {
                    json!({
                        "key": get_str(args, "ref_or_key").unwrap_or_default(),
                    })
                }
            },
        },
        CommandDef {
            name: "type",
            description: "Type text into editable element",
            category: Category::Core,
            hidden: false,
            args: &[
                ArgDef { name: "ref_or_text", description: "Text to type, or element reference when followed by text", optional: false },
                ArgDef { name: "text", description: "Text to type when first argument is an element reference", optional: true },
            ],
            options: &[
                OptionDef { name: "submit", description: "Whether to submit entered text (press Enter after)", is_bool: true },
            ],
            tool_name_fn: |_| "browser_press_sequentially".to_string(),
            tool_params_fn: |args| {
                // 1-arg form: `type "hello"` → ref_or_text is the text
                // 2-arg form: `type e5 "hello"` → ref_or_text is ref, text is the text
                let mut p = if let Some(text) = get_opt_str(args, "text") {
                    json!({
                        "ref": get_str(args, "ref_or_text").unwrap_or_default(),
                        "text": text,
                    })
                } else {
                    json!({
                        "text": get_str(args, "ref_or_text").unwrap_or_default(),
                    })
                };
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
            args: &[
                ArgDef { name: "x", description: "X coordinate", optional: false },
                ArgDef { name: "y", description: "Y coordinate", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_mouse_move_xy".to_string(),
            tool_params_fn: |args| {
                json!({
                    "x": get_num(args, "x").unwrap_or(0.0),
                    "y": get_num(args, "y").unwrap_or(0.0),
                })
            },
        },
        CommandDef {
            name: "mousedown",
            description: "Press mouse down",
            category: Category::Mouse,
            hidden: false,
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
                    "deltaY": get_num(args, "dx").unwrap_or(0.0),
                    "deltaX": get_num(args, "dy").unwrap_or(0.0),
                })
            },
        },
        // ---- Core interactions ----
        CommandDef {
            name: "click",
            description: "Perform click on a web page",
            category: Category::Core,
            hidden: false,
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
            category: Category::Core,
            hidden: false,
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
            category: Category::Core,
            hidden: false,
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
            category: Category::Core,
            hidden: false,
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
            category: Category::Core,
            hidden: false,
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
            args: &[
                ArgDef { name: "ref_or_file", description: "File path, or element reference when followed by a file path", optional: false },
                ArgDef { name: "file", description: "File path when first argument is an element reference", optional: true },
            ],
            options: &[],
            tool_name_fn: |_| "browser_file_upload".to_string(),
            tool_params_fn: |args| {
                // 1-arg form: `upload ./doc.pdf` → ref_or_file is the file
                // 2-arg form: `upload e5 ./doc.pdf` → ref_or_file is ref, file is the file
                if let Some(file) = get_opt_str(args, "file") {
                    json!({ "ref": get_str(args, "ref_or_file").unwrap_or_default(), "paths": [file] })
                } else {
                    json!({ "paths": [get_str(args, "ref_or_file").unwrap_or_default()] })
                }
            },
        },
        CommandDef {
            name: "check",
            description: "Check a checkbox or radio button",
            category: Category::Core,
            hidden: false,
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
            args: &[
                ArgDef { name: "func", description: "JavaScript expression to evaluate on the page", optional: false },
                ArgDef { name: "ref", description: "Exact target element reference from the page snapshot", optional: true },
            ],
            options: &[],
            tool_name_fn: |_| "browser_evaluate".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "expression": get_str(args, "func").unwrap_or_default() });
                if let Some(r) = get_opt_str(args, "ref") { p["ref"] = json!(r); }
                p
            },
        },
        CommandDef {
            name: "console",
            description: "List console messages",
            category: Category::DevTools,
            hidden: false,
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
            args: &[
                ArgDef { name: "w", description: "Width of the browser window", optional: false },
                ArgDef { name: "h", description: "Height of the browser window", optional: false },
            ],
            options: &[],
            tool_name_fn: |_| "browser_resize".to_string(),
            tool_params_fn: |args| {
                json!({
                    "width": get_num(args, "w").unwrap_or(0.0),
                    "height": get_num(args, "h").unwrap_or(0.0),
                })
            },
        },
        CommandDef {
            name: "delete-data",
            description: "Delete session data",
            category: Category::Core,
            hidden: false,
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
            hidden: false,
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
            args: &[ArgDef { name: "index", description: "Tab index. If omitted, current tab is closed.", optional: true }],
            options: &[],
            tool_name_fn: |_| "browser_tabs".to_string(),
            tool_params_fn: |args| {
                let mut p = json!({ "action": "close" });
                if let Some(i) = get_num(args, "index") { p["index"] = json!(i as i64); }
                p
            },
        },
        CommandDef {
            name: "tab-select",
            description: "Select a browser tab",
            category: Category::Tabs,
            hidden: false,
            args: &[ArgDef { name: "index", description: "Tab index", optional: false }],
            options: &[],
            tool_name_fn: |_| "browser_tabs".to_string(),
            tool_params_fn: |args| {
                json!({
                    "action": "select",
                    "index": get_num(args, "index").unwrap_or(0.0) as i64,
                })
            },
        },
        // ---- Browsers / Sessions ----
        CommandDef {
            name: "list",
            description: "List browser sessions",
            category: Category::Browsers,
            hidden: false,
            args: &[],
            options: &[
                OptionDef { name: "all", description: "List all browser sessions across all workspaces", is_bool: true },
            ],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "close-all",
            description: "Close all browser sessions",
            category: Category::Browsers,
            hidden: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
        },
        CommandDef {
            name: "kill-all",
            description: "Forcefully kill all browser sessions (for stale/zombie processes)",
            category: Category::Browsers,
            hidden: false,
            args: &[],
            options: &[],
            tool_name_fn: |_| String::new(),
            tool_params_fn: |_| json!({}),
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
            assert!(names.insert(cmd.name), "Duplicate command name: {}", cmd.name);
        }
    }

    #[test]
    fn test_commands_map_contains_expected() {
        let map = commands_map();
        for expected in &["open", "close", "goto", "click", "type", "fill", "snapshot", "screenshot"] {
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
    fn test_open_has_profile_option() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let has_profile = cmd.options.iter().any(|o| o.name == "profile" && !o.is_bool);
        assert!(has_profile, "open command should have a --profile option");
    }

    #[test]
    fn test_open_has_persistent_option() {
        let map = commands_map();
        let cmd = map.get("open").unwrap();
        let has_persistent = cmd.options.iter().any(|o| o.name == "persistent" && o.is_bool);
        assert!(has_persistent, "open command should have a --persistent option");
    }

    #[test]
    fn test_press_single_arg_key_only() {
        let map = commands_map();
        let cmd = map.get("press").unwrap();
        // Simulate: `press Enter` → ref_or_key=Enter, key=absent
        let mut args = HashMap::new();
        args.insert("ref_or_key".to_string(), json!("Enter"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params.get("key").and_then(|v| v.as_str()), Some("Enter"));
        assert!(params.get("ref").is_none(), "ref should not be present when only key is given");
    }

    #[test]
    fn test_press_two_args_ref_and_key() {
        let map = commands_map();
        let cmd = map.get("press").unwrap();
        // Simulate: `press e5 Enter` → ref_or_key=e5, key=Enter
        let mut args = HashMap::new();
        args.insert("ref_or_key".to_string(), json!("e5"));
        args.insert("key".to_string(), json!("Enter"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params.get("key").and_then(|v| v.as_str()), Some("Enter"));
        assert_eq!(params.get("ref").and_then(|v| v.as_str()), Some("e5"));
    }

    #[test]
    fn test_type_single_arg_text_only() {
        let map = commands_map();
        let cmd = map.get("type").unwrap();
        // Simulate: `type "hello"` → ref_or_text=hello, text=absent
        let mut args = HashMap::new();
        args.insert("ref_or_text".to_string(), json!("hello"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params.get("text").and_then(|v| v.as_str()), Some("hello"));
        assert!(params.get("ref").is_none(), "ref should not be present when only text is given");
    }

    #[test]
    fn test_type_two_args_ref_and_text() {
        let map = commands_map();
        let cmd = map.get("type").unwrap();
        // Simulate: `type e5 "hello"` → ref_or_text=e5, text=hello
        let mut args = HashMap::new();
        args.insert("ref_or_text".to_string(), json!("e5"));
        args.insert("text".to_string(), json!("hello"));
        let params = (cmd.tool_params_fn)(&args);
        assert_eq!(params.get("text").and_then(|v| v.as_str()), Some("hello"));
        assert_eq!(params.get("ref").and_then(|v| v.as_str()), Some("e5"));
    }

    #[test]
    fn test_upload_single_arg_file_only() {
        let map = commands_map();
        let cmd = map.get("upload").unwrap();
        // Simulate: `upload ./doc.pdf` → ref_or_file=./doc.pdf, file=absent
        let mut args = HashMap::new();
        args.insert("ref_or_file".to_string(), json!("./doc.pdf"));
        let params = (cmd.tool_params_fn)(&args);
        let paths = params.get("paths").and_then(|v| v.as_array()).unwrap();
        assert_eq!(paths[0].as_str(), Some("./doc.pdf"));
        assert!(params.get("ref").is_none(), "ref should not be present when only file is given");
    }

    #[test]
    fn test_upload_two_args_ref_and_file() {
        let map = commands_map();
        let cmd = map.get("upload").unwrap();
        // Simulate: `upload e5 ./doc.pdf` → ref_or_file=e5, file=./doc.pdf
        let mut args = HashMap::new();
        args.insert("ref_or_file".to_string(), json!("e5"));
        args.insert("file".to_string(), json!("./doc.pdf"));
        let params = (cmd.tool_params_fn)(&args);
        let paths = params.get("paths").and_then(|v| v.as_array()).unwrap();
        assert_eq!(paths[0].as_str(), Some("./doc.pdf"));
        assert_eq!(params.get("ref").and_then(|v| v.as_str()), Some("e5"));
    }
}
