package ai.platon.pulsar.browser.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Minimal HTTP MCP server backed by JDK's built-in [HttpServer].
 *
 * Exposes two endpoints matching [ai.platon.pulsar.rest.mcp.controller.MCPToolController]:
 * - `POST /mcp/call-tool` — execute an MCP tool
 * - `GET  /mcp/tools`   — list available tool names
 */
class MCPBrowserServer(
    private val port: Int = DEFAULT_PORT,
    private val dispatcher: MCPToolDispatcher
) {
    companion object {
        const val DEFAULT_PORT = 8182
        const val PORT_PROPERTY = "browser4.mcp.server.port"
        const val PORT_ENV = "BROWSER4_MCP_PORT"

        fun resolvePort(): Int {
            val fromProp = System.getProperty(PORT_PROPERTY)?.toIntOrNull()
            val fromEnv = System.getenv(PORT_ENV)?.toIntOrNull()
            return fromProp ?: fromEnv ?: DEFAULT_PORT
        }

        /** The complete set of tool names this server exposes. */
        val ALL_TOOLS: List<String> = listOf(
            // Session management
            "open_session", "close_session", "list_sessions",
            "close_all_sessions", "kill_all_sessions", "delete_session_data",
            // Navigation (frontend names)
            "browser_navigate", "browser_navigate_back", "browser_navigate_forward", "browser_reload",
            // Interaction (frontend names)
            "browser_click", "browser_hover", "browser_press_key", "browser_press_sequentially",
            "browser_type", "browser_drag",
            // Mouse (frontend names)
            "browser_mouse_move_xy", "browser_mouse_down", "browser_mouse_up", "browser_mouse_wheel",
            // Form (frontend names)
            "browser_select_option", "browser_check", "browser_uncheck", "browser_file_upload",
            // Content (frontend names)
            "browser_snapshot", "browser_evaluate", "browser_take_screenshot",
            // Viewport / Dialog (frontend names)
            "browser_resize", "browser_handle_dialog",
            // Tabs (frontend names)
            "browser_tabs",
            // Storage (frontend names)
            "browser_save_storage_state", "browser_load_storage_state",
            // Cookies / Storage (direct names)
            "delete_cookies", "clear_browser_cookies",
            // Page info (direct names)
            "page_title", "page_url",
            // Internal tool names (for clients that bypass frontend aliases)
            "navigate", "go_back", "go_forward", "reload",
            "click", "dblclick", "hover", "type", "press", "fill",
            "keydown", "keyup", "mousemove", "mousedown", "mouseup", "mousewheel",
            "screenshot", "aria_snapshot", "evaluate_value", "evaluate",
            "resize", "dialog_accept", "dialog_dismiss",
            "list_tabs", "new_tab", "close_tab", "switch_tab",
            "save_storage_state", "load_storage_state", "get_cookies",
            "currentUrl", "title", "pageSource",
        ).sorted()
    }

    private val mapper = jacksonObjectMapper()
    private var httpServer: HttpServer? = null

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)

        // POST /mcp/call-tool
        server.createContext("/mcp/call-tool") { exchange ->
            addCorsHeaders(exchange)
            try {
                if (exchange.requestMethod != "POST") {
                    sendJson(exchange, 405, """{"error":"Method not allowed"}""")
                    return@createContext
                }
                val body = exchange.requestBody.readBytes().decodeToString()
                val request = mapper.readValue(body, MCPToolCallRequest::class.java)
                val response = dispatcher.dispatchSync(request.tool, request.arguments ?: emptyMap())
                sendJson(exchange, 200, mapper.writeValueAsString(response))
            } catch (e: Exception) {
                val error = MCPToolCallResponse(
                    content = listOf(MCPContent(text = "ERROR: ${e.message}")),
                    isError = true
                )
                sendJson(exchange, 200, mapper.writeValueAsString(error))
            }
        }

        // GET /mcp/tools
        server.createContext("/mcp/tools") { exchange ->
            addCorsHeaders(exchange)
            try {
                if (exchange.requestMethod != "GET") {
                    sendJson(exchange, 405, """{"error":"Method not allowed"}""")
                    return@createContext
                }
                val json = mapper.writeValueAsString(mapOf("tools" to ALL_TOOLS))
                sendJson(exchange, 200, json)
            } catch (e: Exception) {
                sendJson(exchange, 500, """{"error":"${e.message}"}""")
            }
        }

        server.executor = Executors.newCachedThreadPool()
        server.start()
        httpServer = server
    }

    fun stop(delaySeconds: Int = 1) {
        httpServer?.stop(delaySeconds)
        httpServer = null
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private fun sendJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun addCorsHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("X-Request-Id", UUID.randomUUID().toString())
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
    }
}
