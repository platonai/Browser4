package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import org.slf4j.LoggerFactory

/**
 * Result of executing a CLI command through [AgenticCliRunner].
 */
data class AgenticCliResult(
    val success: Boolean,
    val value: Any? = null,
    val error: String? = null,
    val toolCall: ToolCall? = null,
) {
    companion object {
        fun ignored(commandName: String): AgenticCliResult = AgenticCliResult(
            success = false,
            error = "Command '$commandName' cannot be handled by AgentToolManager (session/server/storage/swarm lifecycle command)"
        )

        fun unsupported(commandName: String): AgenticCliResult = AgenticCliResult(
            success = false,
            error = "Unknown or unsupported command: $commandName"
        )

        fun failed(toolCall: ToolCall, message: String): AgenticCliResult = AgenticCliResult(
            success = false,
            error = message,
            toolCall = toolCall
        )

        fun ok(value: Any?, toolCall: ToolCall): AgenticCliResult = AgenticCliResult(
            success = true,
            value = value,
            toolCall = toolCall
        )
    }
}

/**
 * An internal CLI runner that accepts [browser4-cli] command strings and dispatches
 * them to [AgentToolManager], following the same dispatch pattern as
 * [ai.platon.pulsar.rest.mcp.controller.MCPToolController#callTool].
 *
 * Commands that require external services (session management, server lifecycle,
 * storage state, swarm) are silently ignored — only commands that map to a
 * registered tool executor in [AgentToolManager] are executed.
 *
 * ## Usage
 *
 * ```kotlin
 * val runner = AgenticCliRunner(agent.agentToolManager)
 * val result = runner.execute("goto https://example.com")
 * if (result.success) {
 *     println("Navigated: ${result.value}")
 * }
 * ```
 *
 * ## Supported commands
 *
 * Navigation: goto, go-back, go-forward, reload
 * Interaction: click, dblclick, type, press, fill, hover, select, check, uncheck, upload, drag
 * Mouse: mousemove, mousedown, mouseup, mousewheel
 * Keyboard: keydown, keyup
 * Export: snapshot, screenshot, eval
 * Window: resize
 * Dialog: dialog-accept, dialog-dismiss
 * Tabs: tab-list, tab-new, tab-close, tab-select
 * Agent: extract, summarize, agent-run, agent-status, agent-result
 *
 * @property agentToolManager The tool manager to dispatch commands to.
 */
class AgenticCliRunner(
    private val agentToolManager: AgentToolManager
) {
    private val logger = LoggerFactory.getLogger(AgenticCliRunner::class.java)

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parse a CLI command string and execute it via [AgentToolManager].
     *
     * @param command A browser4-cli command string, e.g. "goto https://example.com".
     * @return [AgenticCliResult] with the execution outcome.
     */
    suspend fun execute(command: String): AgenticCliResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return AgenticCliResult.unsupported("(empty)")
        }

        val (commandName, args) = parseCommand(trimmed)
        return execute(commandName, args)
    }

    /**
     * Execute a pre-parsed command by name and arguments.
     *
     * @param commandName The CLI command name (e.g. "goto", "click").
     * @param args Parsed arguments map (positional args keyed by name, options as --key value).
     * @return [AgenticCliResult] with the execution outcome.
     */
    suspend fun execute(commandName: String, args: Map<String, Any?>): AgenticCliResult {
        // 1. Check if this command can be handled at all
        val resolver = COMMAND_RESOLVERS[commandName]
            ?: return AgenticCliResult.unsupported(commandName)

        // 2. Resolve the MCP frontend tool name (may depend on arguments, e.g. open with/without URL)
        val frontendToolName = resolver.resolveToolName(args)
        if (frontendToolName.isNullOrBlank()) {
            return AgenticCliResult.ignored(commandName)
        }

        // 3. Build the MCP tool parameters
        val frontendArgs = resolver.resolveParams(args)

        // 4. Normalize the frontend tool call (composite tools, aliases)
        val normalized = normalizeFrontendToolCall(frontendToolName, frontendArgs)

        // 5. Resolve to AgentToolManager domain+method
        val toolCall = resolveToolCall(normalized.tool, normalized.arguments)
            ?: return AgenticCliResult.ignored(commandName)

        // 6. Execute via AgentToolManager
        return try {
            val result = agentToolManager.execute(toolCall)
            val evaluate = result.evaluate
            if (evaluate.exception != null) {
                AgenticCliResult.failed(toolCall, evaluate.exception!!.message ?: "Tool execution failed")
            } else {
                AgenticCliResult.ok(evaluate.value, toolCall)
            }
        } catch (e: Exception) {
            logger.warn("CLI command '{}' execution failed: {}", commandName, e.message)
            AgenticCliResult.failed(toolCall, e.message ?: "Execution failed")
        }
    }

    /**
     * Check whether a command name can be handled by this runner.
     */
    fun canHandle(commandName: String): Boolean {
        return commandName in COMMAND_RESOLVERS
    }

    /**
     * Return the set of CLI command names this runner can handle.
     */
    fun supportedCommands(): Set<String> = COMMAND_RESOLVERS.keys

    // =========================================================================
    // Command parsing
    // =========================================================================

    /**
     * Parse a raw CLI command string into a command name and arguments map.
     *
     * Format: `<command> [pos-arg-1] [pos-arg-2] ... [--key value] ...`
     *
     * Positional arguments are mapped by index (0, 1, 2, ...) AND by their
     * resolved parameter name (see [resolvePositionalArgs]).
     * Named options (--key value) are stored directly by key.
     */
    internal fun parseCommand(raw: String): Pair<String, Map<String, Any?>> {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) {
            return "" to emptyMap()
        }

        val commandName = normalizeCommandName(tokens.first())
        val rest = tokens.drop(1)

        val positional = mutableListOf<String>()
        val options = mutableMapOf<String, Any?>()

        var i = 0
        while (i < rest.size) {
            val token = rest[i]
            when {
                token.startsWith("--") -> {
                    val eqIndex = token.indexOf('=')
                    if (eqIndex >= 0) {
                        // --key=value
                        val key = token.substring(2, eqIndex)
                        val value = token.substring(eqIndex + 1)
                        options[key] = parseOptionValue(value)
                    } else {
                        val key = token.substring(2)
                        // Look ahead: --key value (space-separated)
                        if (i + 1 < rest.size && !rest[i + 1].startsWith("--")) {
                            options[key] = parseOptionValue(rest[i + 1])
                            i++ // consume the value token
                        } else {
                            // --flag (boolean true)
                            options[key] = true
                        }
                    }
                }
                else -> positional.add(token)
            }
            i++
        }

        // Merge positional args with their resolved names
        val resolvedArgs = resolvePositionalArgs(commandName, positional, options)

        return commandName to resolvedArgs
    }

    /**
     * Tokenize a command string, respecting single/double-quoted segments.
     */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val current = StringBuilder()

        while (i < input.length) {
            val ch = input[i]
            when {
                ch == '"' || ch == '\'' -> {
                    val quote = ch
                    i++ // skip opening quote
                    while (i < input.length && input[i] != quote) {
                        current.append(input[i])
                        i++
                    }
                    i++ // skip closing quote
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                ch == ' ' || ch == '\t' -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                    i++
                }
                ch == '\\' && i + 1 < input.length -> {
                    // Escape next character
                    i++
                    current.append(input[i])
                    i++
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

    /**
     * Normalize command name: handle hyphenated/aliased forms.
     */
    private fun normalizeCommandName(name: String): String {
        // Handle "agent run X" → "agent-run X" style rewrites
        return when (name.lowercase()) {
            "agent" -> "agent-run" // "agent" alone defaults to agent-run
            else -> name.lowercase()
        }
    }

    /**
     * Map positional arguments to their parameter names based on the command definition.
     * This replicates the Rust CLI's positional→named arg resolution.
     */
    private fun resolvePositionalArgs(
        commandName: String,
        positional: List<String>,
        options: Map<String, Any?>
    ): Map<String, Any?> {
        val commandDef = COMMAND_DEFS[commandName]
            ?: return if (positional.isNotEmpty()) options + mapOf("_" to positional) else options
        val result = mutableMapOf<String, Any?>()
        result.putAll(options)

        val argDefs = commandDef.positionalArgs

        // Special handling for commands with ref+key/text resolution logic
        when (commandName) {
            "press" -> {
                val (key, ref) = resolveKeyAndRef(positional, result)
                if (key != null) result["key"] = key
                if (ref != null) result["ref"] = ref
                return result
            }
            "type" -> {
                val (text, ref) = resolveTextAndRef(positional, result)
                if (text != null) result["text"] = text
                if (ref != null) result["ref"] = ref
                return result
            }
            "click", "dblclick" -> {
                // First positional is ref, second is button
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("ref", value)
                        1 -> result.putIfAbsent("button", value)
                    }
                }
                return result
            }
            "fill" -> {
                // First is ref, second is text
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("ref", value)
                        1 -> result.putIfAbsent("text", value)
                    }
                }
                return result
            }
            "select" -> {
                // First is ref, second is val
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("ref", value)
                        1 -> result.putIfAbsent("val", value)
                    }
                }
                return result
            }
            "upload" -> {
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("ref", value)
                        1 -> result.putIfAbsent("file", value)
                    }
                }
                return result
            }
            "drag" -> {
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("startRef", value)
                        1 -> result.putIfAbsent("endRef", value)
                    }
                }
                return result
            }
            "eval" -> {
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("expression", value)
                        1 -> result.putIfAbsent("ref", value)
                    }
                }
                return result
            }
            "mousemove" -> {
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("x", parseNumber(value))
                        1 -> result.putIfAbsent("y", parseNumber(value))
                    }
                }
                return result
            }
            "mousewheel" -> {
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("dx", parseNumber(value))
                        1 -> result.putIfAbsent("dy", parseNumber(value))
                    }
                }
                return result
            }
            "resize" -> {
                positional.forEachIndexed { index, value ->
                    when (index) {
                        0 -> result.putIfAbsent("w", parseNumber(value))
                        1 -> result.putIfAbsent("h", parseNumber(value))
                    }
                }
                return result
            }
            "dialog-accept" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("prompt", positional.first())
                }
                return result
            }
            "tab-new" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("url", positional.first())
                }
                return result
            }
            "tab-select" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("index", parseNumber(positional.first()))
                }
                return result
            }
            "tab-close" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("index", parseNumber(positional.first()))
                }
                return result
            }
            "screenshot" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("ref", positional.first())
                }
                return result
            }
            "goto", "open" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("url", positional.first())
                }
                return result
            }
            "extract" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("instruction", positional.first())
                }
                return result
            }
            "summarize" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("instruction", positional.first())
                }
                return result
            }
            "agent-run" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("task", positional.first())
                }
                return result
            }
            "agent-status", "agent-result" -> {
                if (positional.isNotEmpty()) {
                    result.putIfAbsent("id", positional.first())
                }
                return result
            }
            else -> {
                // Generic: map positional args by order of arg definitions
                argDefs.forEachIndexed { index, argDef ->
                    if (index < positional.size) {
                        result.putIfAbsent(argDef, positional[index])
                    }
                }
                return result
            }
        }
    }

    /**
     * Resolve key and ref from positional args for keyboard commands (press).
     * Replicates the Rust CLI's `resolve_key_and_ref` function:
     * - If one arg: it's the key, optional ref from --ref
     * - If two args: auto-detect which is key vs ref based on selector patterns
     */
    private fun resolveKeyAndRef(positional: List<String>, options: Map<String, Any?>): Pair<String?, String?> {
        return when {
            positional.size == 1 -> positional.first() to options["ref"]?.toString()
            positional.size >= 2 -> {
                val first = positional[0]
                val second = positional[1]
                if (looksLikeSelectorOrRef(first) && !looksLikeSelectorOrRef(second)) {
                    second to first
                } else {
                    first to second
                }
            }
            else -> options["key"]?.toString() to options["ref"]?.toString()
        }
    }

    /**
     * Resolve text and ref from positional args for type commands.
     * Replicates the Rust CLI's `resolve_text_and_ref` function.
     */
    private fun resolveTextAndRef(positional: List<String>, options: Map<String, Any?>): Pair<String?, String?> {
        return when {
            positional.size == 1 -> positional.first() to options["ref"]?.toString()
            positional.size >= 2 -> {
                val first = positional[0]
                val second = positional[1]
                if (looksLikeSelectorOrRef(first) && !looksLikeSelectorOrRef(second)) {
                    second to first
                } else {
                    first to second
                }
            }
            else -> options["text"]?.toString() to options["ref"]?.toString()
        }
    }

    private fun looksLikeSelectorOrRef(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("#") ||
            trimmed.startsWith(".") ||
            trimmed.startsWith("[") ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("xpath:") ||
            trimmed.startsWith("css:") ||
            trimmed.startsWith("backend:") ||
            trimmed.startsWith("text=") ||
            (trimmed.startsWith("e") && trimmed.drop(1).all { it.isDigit() })
    }

    private fun parseOptionValue(raw: String): Any? {
        return when {
            raw.equals("true", ignoreCase = true) -> true
            raw.equals("false", ignoreCase = true) -> false
            raw.equals("null", ignoreCase = true) -> null
            raw.toIntOrNull() != null -> raw.toInt()
            raw.toDoubleOrNull() != null -> raw.toDouble()
            else -> raw
        }
    }

    private fun parseNumber(raw: String): Number {
        return raw.toIntOrNull() ?: raw.toDoubleOrNull() ?: 0
    }

    // =========================================================================
    // Command → MCP tool name resolution
    // =========================================================================

    /**
     * Resolves a CLI command name and arguments to an MCP frontend tool name and parameters.
     */
    private interface CommandResolver {
        fun resolveToolName(args: Map<String, Any?>): String?
        fun resolveParams(args: Map<String, Any?>): Map<String, Any?>
    }

    /**
     * Simple command resolver for commands with a fixed MCP tool name.
     */
    private class FixedCommandResolver(
        private val toolName: String,
        private val paramMapper: ((Map<String, Any?>) -> Map<String, Any?>)? = null
    ) : CommandResolver {
        override fun resolveToolName(args: Map<String, Any?>): String = toolName
        override fun resolveParams(args: Map<String, Any?>): Map<String, Any?> {
            return paramMapper?.invoke(args) ?: args
        }
    }

    /**
     * Command definitions — describes positional argument names for each command.
     */
    private data class CommandDef(val positionalArgs: List<String>)

    // =========================================================================
    // Static command registry
    // =========================================================================

    companion object {
        /**
         * Frontend MCP tool name aliases — maps browser4-cli MCP tool names to internal
         * tool names. Mirrors [ai.platon.pulsar.rest.mcp.controller.MCPToolController.FRONTEND_TOOL_NAME_ALIASES].
         */
        private val FRONTEND_TOOL_NAME_ALIASES: Map<String, String> = mapOf(
            "browser_navigate" to "navigate",
            "browser_snapshot" to "aria_snapshot",
            "browser_navigate_back" to "go_back",
            "browser_navigate_forward" to "go_forward",
            "browser_reload" to "reload",
            "browser_press_key" to "press",
            "browser_press_sequentially" to "type",
            "browser_keydown" to "keydown",
            "browser_keyup" to "keyup",
            "browser_mouse_move_xy" to "mousemove",
            "browser_mouse_down" to "mousedown",
            "browser_mouse_up" to "mouseup",
            "browser_mouse_wheel" to "mousewheel",
            "browser_drag" to "drag",
            "browser_type" to "fill",
            "browser_hover" to "hover",
            "browser_select_option" to "select_option",
            "browser_file_upload" to "upload",
            "browser_check" to "check",
            "browser_uncheck" to "uncheck",
            "browser_evaluate" to "evaluate_value",
            "browser_resize" to "resize",
            "browser_take_screenshot" to "screenshot",
            "browser_save_storage_state" to "save_storage_state",
            "browser_load_storage_state" to "load_storage_state",
        )

        /**
         * Legacy / explicit tool name → domain+method mappings.
         * Mirrors [ai.platon.pulsar.rest.mcp.controller.MCPToolController.resolveMcpToolCall].
         */
        private val LEGACY_TOOL_MAPPINGS: Map<String, ToolCall> = mapOf(
            "page_title" to ToolCall("tab", "title", mutableMapOf()),
            "page_url" to ToolCall("tab", "currentUrl", mutableMapOf()),
            "switch_tab" to ToolCall("browser", "switchTab", mutableMapOf()),
            "tab_select" to ToolCall("browser", "switchTab", mutableMapOf()),
            "tab_new" to ToolCall("browser", "newTab", mutableMapOf()),
            "tab_list" to ToolCall("browser", "listTabs", mutableMapOf()),
            "tab_close" to ToolCall("browser", "closeTab", mutableMapOf()),
            "close_tab" to ToolCall("browser", "closeTab", mutableMapOf()),
            "keydown" to ToolCall("tab", "keyDown", mutableMapOf()),
            "keyup" to ToolCall("tab", "keyUp", mutableMapOf()),
            "mousemove" to ToolCall("tab", "mouseMove", mutableMapOf()),
            "mousedown" to ToolCall("tab", "mouseDown", mutableMapOf()),
            "mouseup" to ToolCall("tab", "mouseUp", mutableMapOf()),
            "mousewheel" to ToolCall("tab", "mouseWheel", mutableMapOf()),
        )

        /**
         * Command resolvers: maps CLI command name → MCP tool resolution.
         */
        private val COMMAND_RESOLVERS: Map<String, CommandResolver> = mapOf(
            // ---- Navigation ----
            "goto" to FixedCommandResolver("browser_navigate") { args ->
                mapOf("url" to (args["url"]?.toString() ?: "about:blank"))
            },
            "go-back" to FixedCommandResolver("browser_navigate_back"),
            "go-forward" to FixedCommandResolver("browser_navigate_forward"),
            "reload" to FixedCommandResolver("browser_reload"),

            // ---- Keyboard ----
            "press" to FixedCommandResolver("browser_press_key") { args ->
                val params = mutableMapOf<String, Any?>()
                args["key"]?.let { params["key"] = it }
                args["ref"]?.let { params["ref"] = it }
                params
            },
            "type" to FixedCommandResolver("browser_press_sequentially") { args ->
                val params = mutableMapOf<String, Any?>()
                args["text"]?.let { params["text"] = it }
                args["ref"]?.let { params["ref"] = it }
                args["submit"]?.let { params["submit"] = it }
                params
            },
            "keydown" to FixedCommandResolver("browser_keydown") { args ->
                mapOf("key" to (args["key"]?.toString() ?: ""))
            },
            "keyup" to FixedCommandResolver("browser_keyup") { args ->
                mapOf("key" to (args["key"]?.toString() ?: ""))
            },

            // ---- Mouse ----
            "click" to FixedCommandResolver("browser_click") { args ->
                val params = mutableMapOf<String, Any?>()
                args["ref"]?.let { params["ref"] = it }
                args["button"]?.let { params["button"] = it }
                args["modifiers"]?.let { params["modifiers"] = it }
                params
            },
            "dblclick" to FixedCommandResolver("browser_click") { args ->
                val params = mutableMapOf<String, Any?>()
                args["ref"]?.let { params["ref"] = it }
                args["button"]?.let { params["button"] = it }
                args["modifiers"]?.let { params["modifiers"] = it }
                params["doubleClick"] = true
                params
            },
            "mousemove" to FixedCommandResolver("browser_mouse_move_xy") { args ->
                mapOf("x" to (args["x"] ?: 0), "y" to (args["y"] ?: 0))
            },
            "mousedown" to FixedCommandResolver("browser_mouse_down") { args ->
                val params = mutableMapOf<String, Any?>()
                args["button"]?.let { params["button"] = it }
                params
            },
            "mouseup" to FixedCommandResolver("browser_mouse_up") { args ->
                val params = mutableMapOf<String, Any?>()
                args["button"]?.let { params["button"] = it }
                params
            },
            "mousewheel" to FixedCommandResolver("browser_mouse_wheel") { args ->
                mapOf("deltaX" to (args["dx"] ?: 0), "deltaY" to (args["dy"] ?: 0))
            },

            // ---- Core interactions ----
            "fill" to FixedCommandResolver("browser_type") { args ->
                val params = mutableMapOf<String, Any?>()
                args["ref"]?.let { params["ref"] = it }
                args["text"]?.let { params["text"] = it }
                args["submit"]?.let { params["submit"] = it }
                params
            },
            "hover" to FixedCommandResolver("browser_hover") { args ->
                mapOf("ref" to (args["ref"]?.toString() ?: ""))
            },
            "select" to FixedCommandResolver("browser_select_option") { args ->
                val params = mutableMapOf<String, Any?>()
                args["ref"]?.let { params["ref"] = it }
                val values = args["val"]?.let { listOf(it.toString()) } ?: args["values"]
                params["values"] = values
                params
            },
            "upload" to FixedCommandResolver("browser_file_upload") { args ->
                val params = mutableMapOf<String, Any?>()
                args["ref"]?.let { params["ref"] = it }
                val paths = args["file"]?.let { listOf(it.toString()) } ?: args["paths"]
                params["paths"] = paths
                params
            },
            "check" to FixedCommandResolver("browser_check") { args ->
                mapOf("ref" to (args["ref"]?.toString() ?: ""))
            },
            "uncheck" to FixedCommandResolver("browser_uncheck") { args ->
                mapOf("ref" to (args["ref"]?.toString() ?: ""))
            },
            "drag" to FixedCommandResolver("browser_drag") { args ->
                mapOf(
                    "startRef" to (args["startRef"]?.toString() ?: ""),
                    "endRef" to (args["endRef"]?.toString() ?: "")
                )
            },

            // ---- Export ----
            "snapshot" to FixedCommandResolver("browser_snapshot") { args ->
                val params = mutableMapOf<String, Any?>()
                args["filename"]?.let { params["filename"] = it }
                args["boxes"]?.let { params["boxes"] = it }
                args["interactive"]?.let { params["interactive"] = it }
                args["urls"]?.let { params["urls"] = it }
                args["compact"]?.let { params["compact"] = it }
                args["depth"]?.let { params["depth"] = it }
                args["selector"]?.let { params["selector"] = it }
                params
            },
            "screenshot" to FixedCommandResolver("browser_take_screenshot") { args ->
                val params = mutableMapOf<String, Any?>()
                args["ref"]?.let { params["ref"] = it }
                args["filename"]?.let { params["filename"] = it }
                args["full-page"]?.let { params["fullPage"] = it }
                params
            },
            "eval" to FixedCommandResolver("browser_evaluate") { args ->
                val params = mutableMapOf<String, Any?>()
                args["expression"]?.let { params["expression"] = it }
                args["ref"]?.let { params["ref"] = it }
                params
            },

            // ---- Window ----
            "resize" to FixedCommandResolver("browser_resize") { args ->
                mapOf("width" to (args["w"] ?: 0), "height" to (args["h"] ?: 0))
            },

            // ---- Dialogs ----
            "dialog-accept" to FixedCommandResolver("browser_handle_dialog") { args ->
                val params = mutableMapOf<String, Any?>("accept" to true)
                args["prompt"]?.let { params["promptText"] = it }
                params
            },
            "dialog-dismiss" to FixedCommandResolver("browser_handle_dialog") { args ->
                mapOf("accept" to false)
            },

            // ---- Tabs (composite → resolved by normalizeFrontendToolCall) ----
            "tab-list" to FixedCommandResolver("browser_tabs") { _ ->
                mapOf("action" to "list")
            },
            "tab-new" to FixedCommandResolver("browser_tabs") { args ->
                val params = mutableMapOf<String, Any?>("action" to "new")
                args["url"]?.let { params["url"] = it }
                params
            },
            "tab-close" to FixedCommandResolver("browser_tabs") { args ->
                val params = mutableMapOf<String, Any?>("action" to "close")
                args["index"]?.let { params["index"] = it }
                params
            },
            "tab-select" to FixedCommandResolver("browser_tabs") { args ->
                val params = mutableMapOf<String, Any?>("action" to "select")
                args["index"]?.let { params["index"] = it }
                params
            },

            // ---- Agent ----
            "extract" to FixedCommandResolver("agent_extract") { args ->
                val params = mutableMapOf<String, Any?>()
                args["instruction"]?.let { params["instruction"] = it }
                args["schema"]?.let { params["schema"] = it }
                params
            },
            "summarize" to FixedCommandResolver("agent_summarize") { args ->
                val params = mutableMapOf<String, Any?>()
                args["instruction"]?.let { params["instruction"] = it }
                args["selector"]?.let { params["selector"] = it }
                params
            },

            // ---- Agent tasks (command domain) ----
            "agent-run" to FixedCommandResolver("command_run") { args ->
                mapOf("command" to (args["task"]?.toString() ?: ""))
            },
            "agent-status" to FixedCommandResolver("command_status") { args ->
                mapOf("id" to (args["id"]?.toString() ?: ""))
            },
            "agent-result" to FixedCommandResolver("command_result") { args ->
                mapOf("id" to (args["id"]?.toString() ?: ""))
            },

            // ---- Open (with URL) ----
            "open" to object : CommandResolver {
                override fun resolveToolName(args: Map<String, Any?>): String? {
                    val url = args["url"]?.toString()
                    return if (!url.isNullOrBlank()) "browser_navigate" else null
                }

                override fun resolveParams(args: Map<String, Any?>): Map<String, Any?> {
                    return mapOf("url" to (args["url"]?.toString() ?: "about:blank"))
                }
            },
        )

        /**
         * Command definitions specifying positional argument names.
         */
        private val COMMAND_DEFS: Map<String, CommandDef> = mapOf(
            "goto" to CommandDef(listOf("url")),
            "open" to CommandDef(listOf("url")),
            "press" to CommandDef(listOf("key", "ref")),
            "type" to CommandDef(listOf("text", "ref")),
            "keydown" to CommandDef(listOf("key")),
            "keyup" to CommandDef(listOf("key")),
            "mousemove" to CommandDef(listOf("x", "y")),
            "mousedown" to CommandDef(listOf("button")),
            "mouseup" to CommandDef(listOf("button")),
            "mousewheel" to CommandDef(listOf("dx", "dy")),
            "click" to CommandDef(listOf("ref", "button")),
            "dblclick" to CommandDef(listOf("ref", "button")),
            "drag" to CommandDef(listOf("startRef", "endRef")),
            "fill" to CommandDef(listOf("ref", "text")),
            "hover" to CommandDef(listOf("ref")),
            "select" to CommandDef(listOf("ref", "val")),
            "upload" to CommandDef(listOf("ref", "file")),
            "check" to CommandDef(listOf("ref")),
            "uncheck" to CommandDef(listOf("ref")),
            "screenshot" to CommandDef(listOf("ref")),
            "eval" to CommandDef(listOf("expression", "ref")),
            "resize" to CommandDef(listOf("w", "h")),
            "dialog-accept" to CommandDef(listOf("prompt")),
            "dialog-dismiss" to CommandDef(emptyList()),
            "extract" to CommandDef(listOf("instruction")),
            "summarize" to CommandDef(listOf("instruction")),
            "agent-run" to CommandDef(listOf("task")),
            "agent-status" to CommandDef(listOf("id")),
            "agent-result" to CommandDef(listOf("id")),
            "tab-list" to CommandDef(emptyList()),
            "tab-new" to CommandDef(listOf("url")),
            "tab-close" to CommandDef(listOf("index")),
            "tab-select" to CommandDef(listOf("index")),
        )

        /**
         * Returns the set of all CLI command names that can be executed.
         * Used by the `act` command to build a prompt reference for the LLM.
         */
        fun getAvailableCommands(): Set<String> = COMMAND_RESOLVERS.keys

        /**
         * Returns the positional argument names for a given CLI command,
         * or an empty list if the command is unknown.
         */
        fun getCommandArgs(name: String): List<String> =
            COMMAND_DEFS[name]?.positionalArgs ?: emptyList()
    }

    // =========================================================================
    // Frontend tool call normalization
    // =========================================================================

    /**
     * Normalized frontend tool call — before resolution to domain+method.
     */
    internal data class NormalizedToolCall(
        val tool: String,
        val arguments: Map<String, Any?>
    )

    /**
     * Normalize a frontend MCP tool call: handle composite tools and aliases.
     * Mirrors [ai.platon.pulsar.rest.mcp.controller.MCPToolController.normalizeFrontendToolCall].
     */
    internal fun normalizeFrontendToolCall(toolName: String, args: Map<String, Any?>): NormalizedToolCall {
        // Composite: browser_tabs → action-based resolution
        if (toolName == "browser_tabs") {
            val action = args["action"]?.toString()
            val resolvedTool = when (action) {
                "list" -> "tab_list"
                "new" -> "tab_new"
                "close" -> "tab_close"
                "select" -> "tab_select"
                else -> toolName
            }
            val cleanedArgs = args.toMutableMap().apply { remove("action") }
            return NormalizedToolCall(resolvedTool, cleanedArgs)
        }

        // Composite: browser_handle_dialog → accept/dismiss resolution
        if (toolName == "browser_handle_dialog") {
            val accept = args["accept"].toBoolean()
            val resolvedTool = if (!accept) "dialog_dismiss" else "dialog_accept"
            val cleanedArgs = args.toMutableMap().apply { remove("accept") }
            return NormalizedToolCall(resolvedTool, cleanedArgs)
        }

        // Composite: browser_click → click/dblclick resolution
        if (toolName == "browser_click") {
            val doubleClick = args["doubleClick"].toBoolean()
            val resolvedTool = if (doubleClick) "dblclick" else "click"
            val cleanedArgs = args.toMutableMap().apply { remove("doubleClick") }
            return NormalizedToolCall(resolvedTool, cleanedArgs)
        }

        // Apply frontend tool name aliases
        val aliasedTool = FRONTEND_TOOL_NAME_ALIASES[toolName] ?: toolName

        return NormalizedToolCall(aliasedTool, args)
    }

    // =========================================================================
    // Tool call resolution
    // =========================================================================

    /**
     * Resolve a normalized frontend tool name to a [ToolCall] with the correct domain and method.
     * Mirrors [ai.platon.pulsar.rest.mcp.controller.MCPToolController.resolveMcpToolCall].
     */
    internal fun resolveToolCall(toolName: String, args: Map<String, Any?>): ToolCall? {
        val normalizedArgs = normalizeToolArguments(toolName, args).toMutableMap()

        // 1. Explicit legacy mappings
        LEGACY_TOOL_MAPPINGS[toolName]?.let { base ->
            return base.copy(arguments = normalizedArgs)
        }

        // 2. Command domain tools — dispatched via a custom executor (CommandToolExecutor)
        //    that is registered at runtime by callers (e.g. MCPToolController, UserCommandExecutor).
        //    These are NOT in the built-in executor list, so we handle them explicitly.
        when (toolName) {
            "command_run" -> return ToolCall("command", "run", normalizedArgs)
            "command_status" -> return ToolCall("command", "status", normalizedArgs)
            "command_result" -> return ToolCall("command", "result", normalizedArgs)
        }

        // 3. Generic lookup across all registered tool specs
        val specs = agentToolManager.getAllToolSpecs()
        for ((domain, methods) in specs) {
            for ((method, _) in methods) {
                val mcpName = toMcpToolName(domain, method)
                if (mcpName == toolName) {
                    return ToolCall(domain, method, normalizedArgs)
                }
            }
        }

        return null
    }

    /**
     * Convert domain+method to snake_case MCP tool name.
     * Must match [ai.platon.pulsar.rest.mcp.controller.MCPToolController.toMcpToolName]
     * and Browser4MCPServer logic.
     */
    private fun toMcpToolName(domain: String, method: String): String {
        val snake = method.replace(Regex("([A-Z])")) { "_${it.groupValues[1].lowercase()}" }
        return when (domain) {
            "tab", "system" -> snake
            else -> "${domain}_$snake"
        }
    }

    // =========================================================================
    // Argument normalization
    // =========================================================================

    /**
     * Normalize tool arguments: snake_case→camelCase, ref→selector, etc.
     * Mirrors the logic in [ai.platon.pulsar.rest.mcp.controller.ArgumentNormalizerFactory].
     */
    internal fun normalizeToolArguments(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        val mutableArgs = args.toMutableMap()

        // 1. Default normalization (applies to all tools)
        applyDefaultArgumentNormalization(mutableArgs)

        // 2. Tool-specific normalization
        applyToolSpecificArgumentNormalization(toolName, mutableArgs)

        return mutableArgs
    }

    /**
     * Default argument normalization applied to all tools.
     */
    private fun applyDefaultArgumentNormalization(args: MutableMap<String, Any?>) {
        // snake_case → camelCase for all keys
        val keys = args.keys.toList()
        keys.forEach { key ->
            val camelKey = snakeToCamel(key)
            if (camelKey != key) {
                val value = args.remove(key)
                if (value != null) {
                    args[camelKey] = value
                }
            }
        }

        // Remove sessionId (caller manages sessions externally)
        args.remove("sessionId")

        // ref → selector
        val ref = args.remove("ref")
        if (!args.containsKey("selector") && ref != null) {
            args["selector"] = ref
        }

        // startRef → sourceSelector
        val startRef = args.remove("startRef")
        if (!args.containsKey("sourceSelector") && startRef != null) {
            args["sourceSelector"] = startRef
        }

        // endRef → targetSelector
        val endRef = args.remove("endRef")
        if (!args.containsKey("targetSelector") && endRef != null) {
            args["targetSelector"] = endRef
        }

        // modifiers (list) → modifier (first element as string)
        val modifiers = args.remove("modifiers")
        if (!args.containsKey("modifier") && modifiers is List<*> && modifiers.isNotEmpty()) {
            args["modifier"] = modifiers.first()?.toString()
        }
    }

    /**
     * Tool-specific argument normalization for legacy/transitional tool names.
     */
    private fun applyToolSpecificArgumentNormalization(toolName: String, args: MutableMap<String, Any?>) {
        // Tab normalization: id → tabId
        if (toolName in setOf("switch_tab", "tab_select", "close_tab", "tab_close")) {
            val legacyTabId = args.remove("id")
            if (!args.containsKey("tabId") && legacyTabId != null) {
                args["tabId"] = legacyTabId.toString()
            }
        }

        // Select option: value → values list
        if (toolName == "select_option") {
            val legacyValue = args.remove("value")
            if (!args.containsKey("values") && legacyValue != null) {
                args["values"] = listOf(legacyValue.toString())
            }
        }

        // Evaluate: expression → functionDeclaration when selector present
        if (toolName in setOf("evaluate_value", "evaluate_value_detail")) {
            val selector = args["selector"]?.toString()?.takeIf { it.isNotBlank() }
            val expression = args["expression"]?.toString()?.takeIf { it.isNotBlank() }
            if (selector != null && expression != null && !args.containsKey("functionDeclaration")) {
                args.remove("expression")
                args["functionDeclaration"] = expression
            }
        }
    }

    private fun snakeToCamel(key: String): String {
        if (!key.contains("_")) return key
        val parts = key.split("_").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return key
        return buildString {
            append(parts.first())
            parts.drop(1).forEach { append(it.replaceFirstChar { c -> c.uppercase() }) }
        }
    }

    /**
     * Convert a nullable value to boolean.
     */
    private fun Any?.toBoolean(): Boolean = when (this) {
        is Boolean -> this
        is String -> this.toBooleanStrictOrNull() ?: false
        else -> false
    }
}
