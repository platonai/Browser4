package ai.platon.pulsar.agentic.mcp

/**
 * Canonical MCP tool naming, shared by both MCP surfaces.
 *
 * Browser4 exposes the same tools through two channels:
 *
 * - the standard MCP server — `Browser4MCPServer` (stdio / HTTP+SSE on 8088)
 * - the private MCP dispatcher — `MCPToolController` (`POST /mcp/call-tool` on 8182)
 *
 * Both must spell a tool the same way, otherwise a model that learned a name on
 * one channel gets "unknown tool" on the other. This object is the single source
 * of truth for that spelling.
 */
object McpToolNames {

    /**
     * Convert a domain + camelCase method name to the snake_case MCP tool name.
     *
     * The `tab` and `system` domains are the primary surface of a browsing agent,
     * so their methods are advertised without a domain prefix (`navigate`,
     * `click`, `help`); every other domain is prefixed to keep names unique
     * (`fs_write_string`, `html_snapshot_capture`, `agent_extract`).
     *
     * Examples:
     * - `tab.navigate` -> `navigate`
     * - `tab.goBack` -> `go_back`
     * - `browser.switchTab` -> `browser_switch_tab`
     * - `html_snapshot.capture` -> `html_snapshot_capture`
     */
    fun toMcpToolName(domain: String, method: String): String {
        val snake = method.replace(Regex("([A-Z])")) { "_${it.groupValues[1].lowercase()}" }
        return when (domain) {
            "tab", "system" -> snake
            else -> "${domain}_$snake"
        }
    }

    /**
     * Playwright-MCP style frontend aliases: the names an agent trained on other
     * browser-automation MCP servers reaches for first (`browser_click`,
     * `browser_type`, `browser_snapshot`, ...).
     *
     * Each alias resolves to the canonical `(domain, method)` it stands for, so a
     * channel can register it as a second spelling of an existing tool rather
     * than reimplementing it. Aliases whose canonical tool is not registered are
     * skipped by the caller.
     *
     * The list is kept in sync with the private dispatcher's alias table; a test
     * in `browser4-rest` asserts the two disagree on nothing.
     */
    val frontendAliases: List<McpToolAlias> = listOf(
        McpToolAlias("browser_navigate", "tab", "navigate"),
        McpToolAlias("browser_snapshot", "tab", "ariaSnapshot"),
        McpToolAlias("browser_navigate_back", "tab", "goBack"),
        McpToolAlias("browser_navigate_forward", "tab", "goForward"),
        McpToolAlias("browser_reload", "tab", "reload"),
        McpToolAlias("browser_press_key", "tab", "press"),
        McpToolAlias("browser_press_sequentially", "tab", "type"),
        McpToolAlias("browser_keydown", "tab", "keyDown"),
        McpToolAlias("browser_keyup", "tab", "keyUp"),
        McpToolAlias("browser_mouse_move_xy", "tab", "mouseMove"),
        McpToolAlias("browser_mouse_down", "tab", "mouseDown"),
        McpToolAlias("browser_mouse_up", "tab", "mouseUp"),
        McpToolAlias("browser_mouse_wheel", "tab", "mouseWheel"),
        McpToolAlias("browser_drag", "tab", "drag"),
        McpToolAlias("browser_type", "tab", "fill"),
        McpToolAlias("browser_hover", "tab", "hover"),
        McpToolAlias("browser_select_option", "tab", "selectOption"),
        McpToolAlias("browser_file_upload", "tab", "upload"),
        McpToolAlias("browser_check", "tab", "check"),
        McpToolAlias("browser_uncheck", "tab", "uncheck"),
        McpToolAlias("browser_evaluate", "tab", "evaluateValue"),
        McpToolAlias("browser_generate_locator", "tab", "generateLocator"),
        McpToolAlias("browser_resize", "tab", "resize"),
        McpToolAlias("browser_take_screenshot", "tab", "screenshot"),
        McpToolAlias("browser_pdf_save", "tab", "pdf"),
        McpToolAlias("browser_save_storage_state", "tab", "saveStorageState"),
        McpToolAlias("browser_load_storage_state", "tab", "loadStorageState"),
        McpToolAlias("browser_console_messages", "tab", "consoleMessages"),
        McpToolAlias("browser_console_clear", "tab", "consoleClear"),
        McpToolAlias("browser_focus", "tab", "focus"),
        McpToolAlias("browser_is_visible", "tab", "isVisible"),
        McpToolAlias("browser_is_enabled", "tab", "isEnabled"),
        McpToolAlias("browser_is_checked", "tab", "isChecked"),
        McpToolAlias("browser_dialog_status", "tab", "dialogStatus"),
        McpToolAlias("browser_network_requests", "tab", "networkRequests"),
        McpToolAlias("browser_network_request", "tab", "networkRequestDetail"),
        McpToolAlias("browser_network_route", "tab", "networkRoute"),
        McpToolAlias("browser_network_unroute", "tab", "networkUnroute"),
        McpToolAlias("browser_har_start", "tab", "harStart"),
        McpToolAlias("browser_har_stop", "tab", "harStop"),
        McpToolAlias("browser_frame_list", "tab", "frameList"),
        McpToolAlias("browser_frame_switch", "tab", "frameSwitch"),
        McpToolAlias("browser_frame_main", "tab", "frameMain"),
    )

    /** Alias names indexed by their frontend spelling. */
    val frontendAliasNames: Set<String> = frontendAliases.mapTo(linkedSetOf()) { it.frontendName }
}

/**
 * A frontend alias and the canonical tool it resolves to.
 *
 * @property frontendName the alias a client calls (`browser_click`)
 * @property domain the canonical tool domain (`tab`)
 * @property method the canonical tool method (`click`)
 */
data class McpToolAlias(
    val frontendName: String,
    val domain: String,
    val method: String,
) {
    /** The canonical MCP tool name this alias points at (`click`). */
    val canonicalName: String get() = McpToolNames.toMcpToolName(domain, method)
}
