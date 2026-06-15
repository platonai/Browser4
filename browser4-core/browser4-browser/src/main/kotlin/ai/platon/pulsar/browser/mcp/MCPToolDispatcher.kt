package ai.platon.pulsar.browser.mcp

import ai.platon.pulsar.browser.Browser
import ai.platon.pulsar.browser.WebDriver
import ai.platon.pulsar.browser.mcp.MCPSessionManager.SessionEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking

/**
 * Resolves frontend MCP tool names, normalizes arguments, and dispatches directly to
 * [WebDriver] (tab-domain) or [Browser] (browser-domain) methods.
 *
 * Mirrors the tool-name resolution and argument normalization logic from
 * [ai.platon.pulsar.rest.mcp.controller.MCPToolController] without depending on
 * the agentic or rest modules.
 */
class MCPToolDispatcher(
    private val sessionManager: MCPSessionManager
) {
    companion object {
        /** Frontend-to-internal tool name aliases, verbatim from MCPToolController. */
        private val FRONTEND_TOOL_NAME_ALIASES: Map<String, String> = mapOf(
            "browser_navigate" to "navigate",
            "browser_snapshot" to "aria_snapshot",
            "browser_navigate_back" to "go_back",
            "browser_navigate_forward" to "go_forward",
            "browser_reload" to "reload",
            "browser_press_key" to "press",
            "browser_press_sequentially" to "type",
            "browser_keydown" to "keyDown",
            "browser_keyup" to "keyUp",
            "browser_mouse_move_xy" to "mouseMove",
            "browser_mouse_down" to "mouseDown",
            "browser_mouse_up" to "mouseUp",
            "browser_mouse_wheel" to "mouseWheel",
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
         * Legacy/intermediate tool names that MCPToolController resolves via [resolveMcpToolCall].
         * These are the names that appear between the frontend alias and the final domain+method.
         */
        private val LEGACY_NAME_MAPPINGS: Map<String, String> = mapOf(
            "page_title" to "title",
            "page_url" to "currentUrl",
            "keydown" to "keyDown",
            "keyup" to "keyUp",
            "mousemove" to "mouseMove",
            "mousedown" to "mouseDown",
            "mouseup" to "mouseUp",
            "mousewheel" to "mouseWheel",
            "tab_select" to "switch_tab",
        )

        /** Tools that operate on [Browser] rather than [WebDriver]. */
        private val BROWSER_DOMAIN_TOOLS: Set<String> = setOf(
            "list_tabs", "new_tab", "close_tab", "switch_tab"
        )

        /** All known tab-domain tools. */
        private val TAB_DOMAIN_TOOLS: Set<String> = setOf(
            "navigate", "go_back", "go_forward", "reload",
            "click", "dblclick", "hover", "fill", "type", "press",
            "keyDown", "keyUp",
            "mouseMove", "mouseDown", "mouseUp", "mouseWheel", "drag",
            "select_option", "check", "uncheck", "upload",
            "evaluate_value", "evaluate",
            "resize", "screenshot",
            "currentUrl", "title", "pageSource",
            "aria_snapshot",
            "get_cookies", "delete_cookies", "clear_browser_cookies",
            "save_storage_state", "load_storage_state",
            "dialog_accept", "dialog_dismiss",
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    // =========================================================================
    // Public entry point — synchronous bridge for HttpServer threads
    // =========================================================================

    /** Called from the JDK HttpServer handler thread. Uses [runBlocking] to bridge to coroutines. */
    fun dispatchSync(toolName: String, rawArgs: Map<String, Any?>): MCPToolCallResponse =
        runBlocking { dispatch(toolName, rawArgs) }

    // =========================================================================
    // Top-level dispatch
    // =========================================================================

    private suspend fun dispatch(toolName: String, rawArgs: Map<String, Any?>): MCPToolCallResponse {
        return try {
            when (toolName) {
                "open_session" -> handleOpenSession()
                "close_session" -> handleCloseSession(rawArgs)
                "list_sessions" -> handleListSessions()
                "close_all_sessions", "kill_all_sessions" -> handleCloseAllSessions()
                "delete_session_data" -> handleDeleteSessionData(rawArgs)
                else -> {
                    val sessionId = rawArgs["sessionId"]?.toString()
                        ?: return errorResponse("Missing required parameter: sessionId")
                    val session = sessionManager.requireSession(sessionId)
                    dispatchToolToSession(session, toolName, rawArgs)
                }
            }
        } catch (e: Exception) {
            errorResponse("$toolName failed: ${e.message}")
        }
    }

    // =========================================================================
    // Per-session tool dispatch
    // =========================================================================

    private suspend fun dispatchToolToSession(
        session: SessionEntry,
        toolName: String,
        rawArgs: Map<String, Any?>
    ): MCPToolCallResponse {
        // Step 1: Normalize composite/compound tool names
        val (normalizedTool, normalizedArgs) = normalizeFrontendToolCall(toolName, rawArgs)

        // Step 2: Normalize arguments
        val args = normalizeArguments(normalizedTool, normalizedArgs)

        // Step 3: Resolve legacy intermediate names (mimics MCPToolController.resolveMcpToolCall)
        val resolvedTool = LEGACY_NAME_MAPPINGS[normalizedTool] ?: normalizedTool

        // Step 4: Dispatch
        return when {
            resolvedTool in BROWSER_DOMAIN_TOOLS -> dispatchBrowserTool(session.browser, resolvedTool, args)
            resolvedTool in TAB_DOMAIN_TOOLS -> dispatchTabTool(session.requireFrontDriver(), resolvedTool, args)
            else -> errorResponse("Unknown tool: $toolName")
        }
    }

    // =========================================================================
    // Session management handlers
    // =========================================================================

    private fun handleOpenSession(): MCPToolCallResponse {
        val entry = sessionManager.openSession()
        return textResponse("""{"sessionId":"${entry.sessionId}"}""")
    }

    private fun handleCloseSession(rawArgs: Map<String, Any?>): MCPToolCallResponse {
        val sessionId = requireSessionId(rawArgs)
        return if (sessionManager.closeSession(sessionId)) textResponse("Session closed")
        else errorResponse("Session not found: $sessionId")
    }

    private suspend fun handleListSessions(): MCPToolCallResponse {
        val items = mutableListOf<String>()
        for (s in sessionManager.listSessions()) {
            val url = s.browser.frontDriver?.currentUrl() ?: ""
            items += """{"sessionId":"${s.sessionId}","url":"$url","status":"active"}"""
        }
        return textResponse("[${items.joinToString(",")}]")
    }

    private fun handleCloseAllSessions(): MCPToolCallResponse {
        val count = sessionManager.closeAllSessions()
        return textResponse("Closed $count session(s)")
    }

    private suspend fun handleDeleteSessionData(rawArgs: Map<String, Any?>): MCPToolCallResponse {
        val sessionId = requireSessionId(rawArgs)
        val session = sessionManager.requireSession(sessionId)
        val driver = session.requireFrontDriver()
        driver.clearBrowserCookies()
        driver.evaluate("""
            (() => {
                try { window.localStorage.clear(); } catch(e) {}
                try { window.sessionStorage.clear(); } catch(e) {}
                return "ok";
            })()
        """.trimIndent())
        return textResponse("User data deleted for session $sessionId")
    }

    // =========================================================================
    // Tab-domain dispatch  (suspend WebDriver method calls)
    // =========================================================================

    private suspend fun dispatchTabTool(
        driver: WebDriver,
        tool: String,
        args: Map<String, Any?>
    ): MCPToolCallResponse {
        return try {
            val result: String? = when (tool) {
                // Navigation
                "navigate" -> { driver.navigate(args.requireString("url")); "" }
                "go_back" -> { driver.goBack(); "" }
                "go_forward" -> { driver.goForward(); "" }
                "reload" -> { driver.reload(); "" }

                // Interaction
                "click" -> {
                    driver.click(args.requireString("selector"), (args["count"] as? Number)?.toInt() ?: 1)
                    ""
                }
                "dblclick" -> { driver.dblclick(args.requireString("selector")); "" }
                "hover" -> { driver.hover(args.requireString("selector")); "" }
                "fill" -> { driver.fill(args.requireString("selector"), args.requireString("text")); "" }
                "type" -> { driver.type(args.requireString("text"), args["selector"]?.toString()); "" }
                "press" -> { driver.press(args.requireString("key"), args["selector"]?.toString()); "" }
                "keyDown" -> { driver.keyDown(args.requireString("key")); "" }
                "keyUp" -> { driver.keyUp(args.requireString("key")); "" }

                // Mouse
                "mouseMove" -> {
                    val x = (args["x"] as? Number)?.toDouble() ?: error("Missing 'x'")
                    val y = (args["y"] as? Number)?.toDouble() ?: error("Missing 'y'")
                    driver.mouseMove(x, y); ""
                }
                "mouseDown" -> { driver.mouseDown(args["button"]?.toString() ?: "left"); "" }
                "mouseUp" -> { driver.mouseUp(args["button"]?.toString() ?: "left"); "" }
                "mouseWheel" -> {
                    val dx = (args["deltaX"] as? Number)?.toDouble() ?: 0.0
                    val dy = (args["deltaY"] as? Number)?.toDouble() ?: 150.0
                    driver.mouseWheel(dx, dy); ""
                }
                "drag" -> {
                    val source = args["sourceSelector"]?.toString()
                        ?: args["selector"]?.toString()
                        ?: error("Missing sourceSelector")
                    val target = args["targetSelector"]?.toString()
                        ?: args["endRef"]?.toString()
                        ?: error("Missing targetSelector")
                    driver.drag(source, target); ""
                }

                // Form
                "select_option" -> {
                    val selector = args.requireString("selector")
                    val values: List<String> = when (val v = args["values"]) {
                        is List<*> -> v.map { it.toString() }
                        else -> listOf(args.requireString("value"))
                    }
                    driver.selectOption(selector, values).toString()
                }
                "check" -> { driver.check(args.requireString("selector")); "" }
                "uncheck" -> { driver.uncheck(args.requireString("selector")); "" }
                "upload" -> {
                    val paths: List<String> = (args["paths"] as? List<*>)?.map { it.toString() }
                        ?: listOf(args.requireString("path"))
                    driver.upload(args.requireString("selector"), paths); ""
                }

                // JavaScript evaluation
                "evaluate_value", "evaluate" -> {
                    driver.evaluate(args.requireString("expression"))?.toString() ?: ""
                }

                // Viewport
                "resize" -> {
                    val w = (args["width"] as? Number)?.toInt() ?: error("Missing 'width'")
                    val h = (args["height"] as? Number)?.toInt() ?: error("Missing 'height'")
                    driver.resize(w, h); ""
                }

                // Screenshot
                "screenshot" -> {
                    val selector = args["selector"]?.toString()
                    when {
                        selector != null -> driver.screenshot(selector) ?: ""
                        else -> driver.screenshot(args["fullPage"] as? Boolean ?: false) ?: ""
                    }
                }

                // Page info
                "currentUrl" -> driver.currentUrl()
                "title" -> driver.title()
                "pageSource" -> driver.pageSource() ?: ""

                // ARIA snapshot
                "aria_snapshot" -> {
                    val viewports = args["viewports"]?.toString()
                    if (viewports != null) driver.ariaSnapshot(viewports) else driver.ariaSnapshot()
                }

                // Cookies
                "get_cookies" -> json.encodeToString(driver.getCookies())
                "delete_cookies" -> {
                    driver.deleteCookies(
                        name = args.requireString("name"),
                        url = args["url"]?.toString(),
                        domain = args["domain"]?.toString(),
                        path = args["path"]?.toString()
                    ); ""
                }
                "clear_browser_cookies" -> { driver.clearBrowserCookies(); "" }

                // Storage state
                "save_storage_state" -> driver.saveStorageState()
                "load_storage_state" -> driver.loadStorageState(args.requireString("state"))

                // Dialogs
                "dialog_accept" -> { driver.dialogAccept(args["promptText"]?.toString()); "" }
                "dialog_dismiss" -> { driver.dialogDismiss(); "" }

                else -> null
            }
            if (result == null) return errorResponse("Unknown tab tool: $tool")
            textResponse(result)
        } catch (e: Exception) {
            errorResponse("$tool failed: ${e.message}")
        }
    }

    // =========================================================================
    // Browser-domain dispatch  (suspend Browser method calls)
    // =========================================================================

    private suspend fun dispatchBrowserTool(
        browser: Browser,
        tool: String,
        args: Map<String, Any?>
    ): MCPToolCallResponse {
        return try {
            val result: String? = when (tool) {
                "list_tabs" -> {
                    val drivers = browser.listDrivers()
                    val items = mutableListOf<String>()
                    for (d in drivers) {
                        items += """{"id":"${d.id}","url":"${d.currentUrl()}","title":"${d.title()}"}"""
                    }
                    items.joinToString("\n")
                }
                "new_tab" -> {
                    val url = args["url"]?.toString() ?: "about:blank"
                    val driver = browser.newDriver(url)
                    """{"id":"${driver.id}","url":"${driver.currentUrl()}"}"""
                }
                "close_tab" -> {
                    val drivers = browser.listDrivers()
                    val index = (args["index"] as? Number)?.toInt()
                    val tabId = args["tabId"]?.toString()
                    val target: WebDriver? = when {
                        index != null -> drivers.getOrNull(index)
                            ?: error("Tab index $index out of range (${drivers.size} tabs)")
                        tabId != null -> drivers.find {
                            it.id.toString() == tabId || it.guid == tabId
                        } ?: error("Tab '$tabId' not found")
                        else -> browser.frontDriver
                    }
                    if (target != null) browser.destroyDriver(target)
                    ""
                }
                "switch_tab" -> {
                    val drivers = browser.listDrivers()
                    val index = (args["index"] as? Number)?.toInt()
                    val tabId = args["tabId"]?.toString()
                    val target: WebDriver = when {
                        index != null -> drivers.getOrNull(index)
                            ?: error("Tab index $index out of range")
                        tabId != null -> drivers.find {
                            it.id.toString() == tabId || it.guid == tabId
                        } ?: error("Tab '$tabId' not found")
                        else -> error("Missing 'index' or 'tabId'")
                    }
                    target.bringToFront()
                    """{"id":"${target.id}","url":"${target.currentUrl()}","title":"${target.title()}"}"""
                }
                else -> null
            }
            if (result == null) return errorResponse("Unknown browser tool: $tool")
            textResponse(result)
        } catch (e: Exception) {
            errorResponse("$tool failed: ${e.message}")
        }
    }

    // =========================================================================
    // Tool name normalization  (mirrors MCPToolController)
    // =========================================================================

    private data class NormalizedToolCall(val tool: String, val arguments: Map<String, Any?>)

    private fun normalizeFrontendToolCall(toolName: String, args: Map<String, Any?>): NormalizedToolCall {
        // Composite: browser_tabs + action
        if (toolName == "browser_tabs") {
            val action = args["action"]?.toString()
            val resolved = when (action) {
                "list" -> "list_tabs"
                "new" -> "new_tab"
                "close" -> "close_tab"
                "select" -> "switch_tab"
                else -> toolName
            }
            return NormalizedToolCall(resolved, args.toMutableMap().apply { remove("action") })
        }

        // Composite: browser_handle_dialog + accept
        if (toolName == "browser_handle_dialog") {
            val accept = args["accept"].toBoolOrNull()
            return NormalizedToolCall(
                tool = if (accept == false) "dialog_dismiss" else "dialog_accept",
                arguments = args.toMutableMap().apply { remove("accept") }
            )
        }

        // Composite: browser_click + doubleClick
        if (toolName == "browser_click") {
            val doubleClick = args["doubleClick"].toBoolOrNull()
            return NormalizedToolCall(
                tool = if (doubleClick == true) "dblclick" else "click",
                arguments = args.toMutableMap().apply { remove("doubleClick") }
            )
        }

        // Alias-based tools
        val aliased = FRONTEND_TOOL_NAME_ALIASES[toolName]
        if (aliased != null) {
            return NormalizedToolCall(aliased, args)
        }

        // Direct pass-through
        return NormalizedToolCall(toolName, args)
    }

    // =========================================================================
    // Argument normalization  (mirrors ArgumentNormalizers from browser4-rest)
    // =========================================================================

    private fun normalizeArguments(toolName: String, rawArgs: Map<String, Any?>): Map<String, Any?> {
        val args = rawArgs.toMutableMap()

        // Remove sessionId (internal routing only)
        args.remove("sessionId")

        // Snake_case keys -> camelCase
        for (key in args.keys.toList()) {
            val camel = snakeToCamel(key)
            if (camel != key) {
                args[camel] = args.remove(key)
            }
        }

        // ref -> selector
        val ref = args.remove("ref")
        if (!args.containsKey("selector") && ref != null) {
            args["selector"] = ref
        }

        // startRef -> sourceSelector, endRef -> targetSelector
        val startRef = args.remove("startRef")
        if (!args.containsKey("sourceSelector") && startRef != null) {
            args["sourceSelector"] = startRef
        }
        val endRef = args.remove("endRef")
        if (!args.containsKey("targetSelector") && endRef != null) {
            args["targetSelector"] = endRef
        }

        // modifiers[0] -> modifier
        val modifiers = args.remove("modifiers")
        if (!args.containsKey("modifier") && modifiers is List<*> && modifiers.isNotEmpty()) {
            args["modifier"] = modifiers.first()?.toString()
        }

        // Tab tools: id -> tabId
        if (toolName in setOf("switch_tab", "list_tabs", "close_tab", "new_tab")) {
            val legacyId = args.remove("id")
            if (!args.containsKey("tabId") && legacyId != null) {
                args["tabId"] = legacyId.toString()
            }
        }

        // Select option: value -> values
        if (toolName == "select_option") {
            val legacyValue = args.remove("value")
            if (!args.containsKey("values") && legacyValue != null) {
                args["values"] = listOf(legacyValue.toString())
            }
        }

        return args
    }

    private fun snakeToCamel(key: String): String {
        if (!key.contains("_")) return key
        val parts = key.split("_").filter { it.isNotEmpty() }
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun textResponse(text: String) = MCPToolCallResponse(
        content = listOf(MCPContent(text = text))
    )

    private fun errorResponse(message: String) = MCPToolCallResponse(
        content = listOf(MCPContent(text = "ERROR: $message")),
        isError = true
    )

    private fun requireSessionId(args: Map<String, Any?>): String =
        args["sessionId"]?.toString() ?: throw IllegalArgumentException("Missing required parameter: sessionId")

    private fun Map<String, Any?>.requireString(key: String): String =
        this[key]?.toString() ?: error("Missing required parameter: $key")

    private fun Any?.toBoolOrNull(): Boolean? = when (this) {
        is Boolean -> this
        is String -> toBooleanStrictOrNull()
        else -> null
    }
}
