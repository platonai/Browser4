package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.mcp.controller.FRONTEND_TOOL_NAME_ALIASES

class ToolListHandler(
    private val sessionManager: PulsarSessionManager,
) {
    companion object {
        /**
         * Convert domain+method to snake_case MCP tool name.
         * Must match logic in Browser4MCPServer.
         */
        fun toMcpToolName(domain: String, method: String): String {
            val snake = method.replace(Regex("([A-Z])")) { "_${it.groupValues[1].lowercase()}" }
            return when (domain) {
                "tab", "system" -> snake
                else -> "${domain}_$snake"
            }
        }
    }

    /**
     * Cached tool names for the /tools endpoint.
     */
    @Volatile
    private var cachedToolNames: List<String>? = null

    fun listToolNames(): List<String> {
        // Fast path: return cached tool names if already computed
        cachedToolNames?.let { return it }

        // Slow path: compute tool names under a lock
        synchronized(this) {
            cachedToolNames?.let { return it }

            val tools = linkedSetOf(
                // Session management
                "open_session", "close_session", "list_sessions",
                "close_all_sessions", "kill_all_sessions", "delete_session_data",
                "attach_browser", "check_session_ready",
                // Command tools (no session required)
                "command_run", "command_batch", "command_status", "command_result"
            )

            // Include every frontend tool alias
            tools.addAll(FRONTEND_TOOL_NAME_ALIASES.keys)

            // Composite / convenience tools
            tools.addAll(
                listOf(
                    "browser_click",
                    "browser_handle_dialog",
                    "browser_tabs",
                    "dom_snapshot_capture",
                    "dom_snapshot_scrape",
                    "dom_snapshot_scrape_all",
                    "dom_snapshot_query",
                    "dom_snapshot_export",
                    "dom_snapshot_summary",
                    // Swarm tools
                    "swarm_submit",
                    "swarm_query",
                    "swarm_status",
                    "swarm_result",
                    // Crawl tools
                    "crawl_submit",
                    "crawl_status",
                    "crawl_result",
                    // Skill management tools
                    "skill_list",
                    "skill_info",
                    "skill_install",
                    "skill_uninstall",
                    "skill_reload",
                )
            )

            val activeSession = sessionManager.getAllSessions().firstOrNull()
            if (activeSession != null) {
                try {
                    val agent = activeSession.agenticSession.companionAgent as? BasicBrowserAgent
                    if (agent != null) {
                        tools.addAll(collectAdvertisedToolNames(agent.agentToolManager.getAllToolSpecs()))
                    }
                } catch (_: Exception) {
                    // Session may be mid-initialisation; the static set is sufficient.
                }
            }

            val result = tools.toList()
            cachedToolNames = result
            return result
        }
    }

    private fun collectAdvertisedToolNames(toolSpecs: Map<String, Map<String, ToolSpec>>): Set<String> {
        val tools = linkedSetOf<String>()

        for ((domain, methods) in toolSpecs) {
            for (method in methods.keys) {
                tools.add(toMcpToolName(domain, method))
            }
        }

        val tabMethods = toolSpecs["tab"].orEmpty().keys
        val browserMethods = toolSpecs["browser"].orEmpty().keys

        val legacyTabMappings = mapOf(
            "keyDown" to "keydown",
            "keyUp" to "keyup",
            "mouseMove" to "mousemove",
            "mouseDown" to "mousedown",
            "mouseUp" to "mouseup",
            "mouseWheel" to "mousewheel",
        )
        legacyTabMappings.forEach { (method, advertisedName) ->
            if (method in tabMethods) {
                tools.add(advertisedName)
            }
        }

        if ("title" in tabMethods) {
            tools.add("page_title")
        }
        if ("currentUrl" in tabMethods) {
            tools.add("page_url")
        }

        val browserTabAliases = mapOf(
            "switchTab" to listOf("switch_tab", "tab_select"),
            "newTab" to listOf("tab_new"),
            "closeTab" to listOf("close_tab", "tab_close"),
            "listTabs" to listOf("tab_list"),
        )
        browserTabAliases.forEach { (method, aliases) ->
            if (method in browserMethods) {
                tools.addAll(aliases)
            }
        }

        FRONTEND_TOOL_NAME_ALIASES.forEach { (frontendTool, internalTool) ->
            if (internalTool in tools) {
                tools.add(frontendTool)
            }
        }

        val tabMcpNames = tabMethods.map { toMcpToolName("tab", it) }.toSet()
        if ("click" in tabMcpNames || "dblclick" in tabMcpNames) {
            tools.add("browser_click")
        }
        if ("dialog_accept" in tabMcpNames || "dialog_dismiss" in tabMcpNames) {
            tools.add("browser_handle_dialog")
        }
        if (browserMethods.any { it in browserTabAliases.keys }) {
            tools.add("browser_tabs")
        }

        return tools
    }
}
