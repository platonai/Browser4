package ai.platon.browser4.cli

/**
 * Describes a single positional argument for a command.
 */
data class ArgDef(
    val name: String,
    val description: String,
    val optional: Boolean = false,
)

/**
 * Describes a named option (`--key=value`) for a command.
 */
data class OptionDef(
    val name: String,
    val description: String,
    val isBool: Boolean = false,
    /** Optional short-form alias (e.g. `"y"` for `-y`). */
    val short: String? = null,
)

/**
 * Command category used for grouping in help output.
 */
enum class Category(val label: String) {
    Core("core"),
    Navigation("navigation"),
    Keyboard("keyboard"),
    Mouse("mouse"),
    Export("export"),
    Tabs("tabs"),
    Storage("storage"),
    Network("network"),
    DevTools("devtools"),
    Browsers("browsers"),
    Config("config"),
    Install("install"),
    Agent("agent"),
    Swarm("swarm"),
}

/**
 * A single CLI command definition.
 *
 * @param name          CLI command name (kebab-case, e.g. `"agent-run"`).
 * @param description   One-line summary for help output.
 * @param category      Grouping category.
 * @param hidden        Exclude from global help when `true`.
 * @param batchSupported Whether this command can be used in batch mode.
 * @param args          Ordered list of positional argument definitions.
 * @param options       Named option definitions.
 * @param toolNameFn    Resolves the MCP tool name from parsed arguments.
 * @param toolParamsFn  Builds the JSON parameters map from parsed arguments.
 */
data class CommandDef(
    val name: String,
    val description: String,
    val category: Category,
    val hidden: Boolean = false,
    val batchSupported: Boolean = false,
    val args: List<ArgDef> = emptyList(),
    val options: List<OptionDef> = emptyList(),
    val toolNameFn: (Map<String, String>) -> String,
    val toolParamsFn: (Map<String, String>) -> Map<String, Any>,
)

// ---------------------------------------------------------------------------
// Command registry
// ---------------------------------------------------------------------------

fun allCommands(): List<CommandDef> = listOf(
    // -- Core --
    CommandDef(
        name = "help",
        description = "Print help information",
        category = Category.Core,
        args = listOf(ArgDef("command", "Command name to show help for", optional = true)),
        toolNameFn = { "" },
        toolParamsFn = { emptyMap() },
    ),
    CommandDef(
        name = "batch",
        description = "Execute multiple commands in one invocation",
        category = Category.Core,
        args = listOf(ArgDef("command...", "Quoted command strings", optional = true)),
        options = listOf(
            OptionDef("bail", "Stop on first command failure", isBool = true),
            OptionDef("json", "Read commands as JSON from stdin", isBool = true),
        ),
        toolNameFn = { "" },
        toolParamsFn = { emptyMap() },
    ),
    CommandDef(
        name = "snapshot",
        description = "Capture page snapshot to obtain element ref",
        category = Category.Core,
        batchSupported = true,
        options = listOf(OptionDef("filename", "Save snapshot to file")),
        toolNameFn = { "browser_snapshot" },
        toolParamsFn = { args ->
            buildMap { args["filename"]?.let { put("filename", it) } }
        },
    ),

    // -- Navigation --
    CommandDef(
        name = "goto",
        description = "Navigate to a URL",
        category = Category.Navigation,
        batchSupported = true,
        args = listOf(ArgDef("url", "The URL to navigate to")),
        toolNameFn = { "browser_navigate" },
        toolParamsFn = { args -> mapOf("url" to (args["url"] ?: "")) },
    ),
    CommandDef(
        name = "go-back",
        description = "Go back to the previous page",
        category = Category.Navigation,
        batchSupported = true,
        toolNameFn = { "browser_navigate_back" },
        toolParamsFn = { emptyMap() },
    ),
    CommandDef(
        name = "go-forward",
        description = "Go forward to the next page",
        category = Category.Navigation,
        batchSupported = true,
        toolNameFn = { "browser_navigate_forward" },
        toolParamsFn = { emptyMap() },
    ),
    CommandDef(
        name = "reload",
        description = "Reload the current page",
        category = Category.Navigation,
        batchSupported = true,
        toolNameFn = { "browser_reload" },
        toolParamsFn = { emptyMap() },
    ),

    // -- Keyboard (skeleton entries) --
    CommandDef(
        name = "press",
        description = "Press a key or key combination",
        category = Category.Keyboard,
        batchSupported = true,
        args = listOf(
            ArgDef("key", "Key or combination (e.g. Enter, Control+a)"),
            ArgDef("ref", "Target element reference", optional = true),
        ),
        toolNameFn = { "browser_press_key" },
        toolParamsFn = { args ->
            buildMap {
                put("key", args["key"] ?: "")
                args["ref"]?.let { put("ref", it) }
            }
        },
    ),
    CommandDef(
        name = "type",
        description = "Type text into an element",
        category = Category.Keyboard,
        batchSupported = true,
        args = listOf(
            ArgDef("text", "Text to type"),
            ArgDef("ref", "Target element reference", optional = true),
        ),
        toolNameFn = { "browser_press_sequentially" },
        toolParamsFn = { args ->
            buildMap {
                put("text", args["text"] ?: "")
                args["ref"]?.let { put("ref", it) }
            }
        },
    ),

    // -- Mouse --
    CommandDef(
        name = "click",
        description = "Click on an element",
        category = Category.Mouse,
        batchSupported = true,
        args = listOf(ArgDef("ref", "Element reference")),
        toolNameFn = { "browser_click" },
        toolParamsFn = { args -> mapOf("ref" to (args["ref"] ?: "")) },
    ),

    // -- Tabs --
    CommandDef(
        name = "tab-list",
        description = "List all tabs",
        category = Category.Tabs,
        batchSupported = true,
        toolNameFn = { "browser_tabs" },
        toolParamsFn = { mapOf("action" to "list") },
    ),
    CommandDef(
        name = "tab-new",
        description = "Create a new tab",
        category = Category.Tabs,
        batchSupported = true,
        args = listOf(ArgDef("url", "URL to open", optional = true)),
        toolNameFn = { "browser_tabs" },
        toolParamsFn = { args ->
            buildMap {
                put("action", "new")
                args["url"]?.let { put("url", it) }
            }
        },
    ),
    CommandDef(
        name = "tab-close",
        description = "Close a browser tab",
        category = Category.Tabs,
        batchSupported = true,
        args = listOf(ArgDef("index", "Tab index", optional = true)),
        toolNameFn = { "browser_tabs" },
        toolParamsFn = { args ->
            buildMap {
                put("action", "close")
                args["index"]?.let { put("index", it) }
            }
        },
    ),
    CommandDef(
        name = "tab-select",
        description = "Select a browser tab",
        category = Category.Tabs,
        batchSupported = true,
        args = listOf(ArgDef("index", "Tab index")),
        toolNameFn = { "browser_tabs" },
        toolParamsFn = { args ->
            mapOf("action" to "select", "index" to (args["index"] ?: "0"))
        },
    ),

    // -- Browser sessions --
    CommandDef(
        name = "open",
        description = "Open a browser session or refresh the saved one",
        category = Category.Browsers,
        args = listOf(ArgDef("url", "The URL to navigate to", optional = true)),
        options = listOf(
            OptionDef("headed", "Run browser in headed mode", isBool = true),
            OptionDef("profile", "Path to browser profile directory"),
            OptionDef("profile-mode", "Profile mode (temporary, sequential, default)"),
            OptionDef("interact-level", "Interaction level (FASTEST, FAST, DEFAULT)"),
        ),
        toolNameFn = { args ->
            if (!args["url"].isNullOrBlank()) "browser_navigate" else "browser_snapshot"
        },
        toolParamsFn = { args ->
            buildMap {
                put("url", args["url"] ?: "about:blank")
                args["headed"]?.let { put("headed", "true") }
                args["profile"]?.let { put("profilePath", it) }
                args["profile-mode"]?.let { put("profileMode", it) }
                args["interact-level"]?.let { put("interactLevel", it) }
            }
        },
    ),
    CommandDef(
        name = "close",
        description = "Close the browser",
        category = Category.Browsers,
        toolNameFn = { "" },
        toolParamsFn = { emptyMap() },
    ),
    CommandDef(
        name = "list",
        description = "List browser sessions with their status",
        category = Category.Browsers,
        options = listOf(OptionDef("all", "List all sessions across workspaces", isBool = true)),
        toolNameFn = { "" },
        toolParamsFn = { emptyMap() },
    ),
    CommandDef(
        name = "status",
        description = "Show Browser4 server status",
        category = Category.Browsers,
        options = listOf(OptionDef("server", "Server URL to check")),
        toolNameFn = { "" },
        toolParamsFn = { emptyMap() },
    ),

    // -- Export --
    CommandDef(
        name = "screenshot",
        description = "Screenshot of the current page or element",
        category = Category.Export,
        batchSupported = true,
        args = listOf(ArgDef("ref", "Element reference", optional = true)),
        options = listOf(
            OptionDef("filename", "File name to save to"),
            OptionDef("full-page", "Full scrollable page screenshot", isBool = true),
        ),
        toolNameFn = { "browser_take_screenshot" },
        toolParamsFn = { args ->
            buildMap {
                args["ref"]?.let { put("ref", it) }
                args["filename"]?.let { put("filename", it) }
                args["full-page"]?.let { put("fullPage", it) }
            }
        },
    ),

    // -- Agent (stubs) --
    CommandDef(
        name = "extract",
        description = "Extract structured data from the current page",
        category = Category.Agent,
        args = listOf(ArgDef("instruction", "What data to extract")),
        options = listOf(OptionDef("schema", "JSON schema to constrain output")),
        toolNameFn = { "agent_extract" },
        toolParamsFn = { args ->
            buildMap {
                put("instruction", args["instruction"] ?: "")
                args["schema"]?.let { put("schema", it) }
            }
        },
    ),
    CommandDef(
        name = "summarize",
        description = "Summarize page content using AI",
        category = Category.Agent,
        args = listOf(ArgDef("instruction", "Summarization instruction", optional = true)),
        options = listOf(OptionDef("selector", "CSS selector to limit scope")),
        toolNameFn = { "agent_summarize" },
        toolParamsFn = { args ->
            buildMap {
                args["instruction"]?.let { put("instruction", it) }
                args["selector"]?.let { put("selector", it) }
            }
        },
    ),
)

/** Returns a lookup map from command name to [CommandDef]. */
fun commandsMap(): Map<String, CommandDef> =
    allCommands().associateBy { it.name }
