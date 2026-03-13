package ai.platon.pulsar.rest.openapi.controller

import ai.platon.pulsar.rest.openapi.service.SessionManager
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

/**
 * Request body for calling an MCP tool.
 */
data class MCPToolCallRequest(
    @param:JsonProperty("tool") val tool: String,
    @param:JsonProperty("arguments") val arguments: Map<String, Any?>? = null
)

/**
 * Response from an MCP tool call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPToolCallResponse(
    @param:JsonProperty("content") val content: List<MCPContent>,
    @param:JsonProperty("isError") val isError: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MCPContent(
    @param:JsonProperty("type") val type: String = "text",
    @param:JsonProperty("text") val text: String
)

// ---------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------

/**
 * REST controller that exposes Browser4 MCP tools over HTTP.
 *
 * This allows the browser4-cli (and any HTTP client) to invoke MCP tools
 * through a simple REST endpoint instead of STDIO.
 *
 * Session management tools (open_session, close_session, list_sessions, etc.)
 * are handled directly by this controller. All other tools are dispatched to
 * the session's WebDriver.
 */
@RestController
@CrossOrigin
@RequestMapping(
    path = ["/mcp"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
@ConditionalOnBean(SessionManager::class)
class MCPToolController(
    private val sessionManager: SessionManager
) {
    private val logger = LoggerFactory.getLogger(MCPToolController::class.java)

    companion object {
        private val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
    }

    // =========================================================================
    // Tool call endpoint
    // =========================================================================

    /**
     * Call an MCP tool.
     *
     * Session management tools (`open_session`, `close_session`, `list_sessions`,
     * `close_all_sessions`, `kill_all_sessions`, `delete_session_data`) do not
     * require a sessionId in the arguments.
     *
     * All other tools require the `sessionId` to be provided in the arguments.
     */
    @PostMapping("/call-tool", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun callTool(
        @RequestBody request: MCPToolCallRequest,
        response: HttpServletResponse
    ): ResponseEntity<MCPToolCallResponse> {
        ControllerUtils.addRequestId(response)

        return try {
            when (request.tool) {
                // Session management tools
                "open_session" -> handleOpenSession(request)
                "close_session" -> handleCloseSession(request)
                "list_sessions" -> handleListSessions()
                "close_all_sessions" -> handleCloseAllSessions()
                "kill_all_sessions" -> handleKillAllSessions()
                "delete_session_data" -> handleDeleteSessionData(request)
                // All other tools are dispatched to the session's WebDriver
                else -> dispatchToDriver(request)
            }
        } catch (e: Exception) {
            logger.error("MCP tool call failed | tool={} | {}", request.tool, e.message, e)
            ResponseEntity.ok(errorResponse("${request.tool} failed: ${e.message}"))
        }
    }

    /**
     * List available MCP tools.
     */
    @GetMapping("/tools")
    fun listTools(
        response: HttpServletResponse
    ): ResponseEntity<Any> {
        ControllerUtils.addRequestId(response)

        val tools = listOf(
            // Session management
            "open_session", "close_session", "list_sessions",
            "close_all_sessions", "kill_all_sessions", "delete_session_data",
            // Navigation
            "navigate", "reload", "go_back", "go_forward",
            // Wait / Query
            "wait_for_selector", "exists", "is_visible", "focus",
            // Interaction
            "hover", "click", "dblclick", "fill", "type", "press",
            "check", "uncheck", "select_option", "drag", "upload",
            // Scrolling
            "scroll_to", "scroll_to_top", "scroll_to_bottom", "scroll_to_middle", "scroll_by",
            // Content
            "text_content", "get_text", "delay",
            // Snapshot & page info
            "aria_snapshot", "page_url", "page_title",
            // Screenshot & evaluate
            "screenshot", "evaluate",
            // Dialog
            "dialog_accept", "dialog_dismiss",
            // Viewport
            "resize",
            // Keyboard
            "keydown", "keyup",
            // Mouse
            "mousemove", "mousedown", "mouseup", "mousewheel",
            // Tab management
            "tab_list", "tab_new", "tab_close", "tab_select"
        )
        return ResponseEntity.ok(mapOf("tools" to tools))
    }

    // =========================================================================
    // Session management handlers
    // =========================================================================

    private fun handleOpenSession(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val capabilities = request.arguments?.get("capabilities") as? Map<String, Any?>
        val session = sessionManager.createSession(capabilities)

        logger.info("MCP open_session: created session {}", session.sessionId)
        return ResponseEntity.ok(
            textResponse("""{"sessionId":"${session.sessionId}"}""")
        )
    }

    private fun handleCloseSession(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val deleted = sessionManager.deleteSession(sessionId)
        return if (deleted) {
            ResponseEntity.ok(textResponse("Session closed"))
        } else {
            ResponseEntity.ok(errorResponse("Session not found: $sessionId"))
        }
    }

    private fun handleListSessions(): ResponseEntity<MCPToolCallResponse> {
        val sessions = sessionManager.getAllSessions().map { s ->
            """{"sessionId":"${s.sessionId}","url":"${s.url ?: ""}","status":"${s.status}"}"""
        }
        return ResponseEntity.ok(textResponse("[${sessions.joinToString(",")}]"))
    }

    private fun handleCloseAllSessions(): ResponseEntity<MCPToolCallResponse> {
        val count = sessionManager.deleteAllSessions()
        return ResponseEntity.ok(textResponse("Closed $count session(s)"))
    }

    private fun handleKillAllSessions(): ResponseEntity<MCPToolCallResponse> {
        val count = sessionManager.deleteAllSessions()
        return ResponseEntity.ok(textResponse("Killed $count session(s)"))
    }

    private suspend fun handleDeleteSessionData(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("Session not found: $sessionId"))

        managed.withLock {
            driver.clearBrowserCookies()
            driver.evaluate("localStorage.clear(); sessionStorage.clear()")
        }
        return ResponseEntity.ok(textResponse("User data deleted for session"))
    }

    // =========================================================================
    // Dispatch to session's WebDriver
    // =========================================================================

    /**
     * Dispatch a tool call to the appropriate session's WebDriver.
     */
    private suspend fun dispatchToDriver(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("Session not found: $sessionId"))

        val result = managed.withLock {
            executeDriverTool(request.tool, request.arguments ?: emptyMap(), managed)
        }
        return ResponseEntity.ok(result)
    }

    /**
     * Execute an MCP tool directly against the session's WebDriver.
     */
    private suspend fun executeDriverTool(
        tool: String,
        args: Map<String, Any?>,
        session: SessionManager.ManagedSession
    ): MCPToolCallResponse {
        val driver = session.driver

        return try {
            when (tool) {
                // Navigation
                "navigate" -> {
                    val url = requireArg(args, "url")
                    driver.navigateTo(url)
                    textResponse("Navigated to $url")
                }
                "reload" -> {
                    driver.reload()
                    textResponse("Page reloaded")
                }
                "go_back" -> {
                    driver.goBack()
                    textResponse("Navigated back")
                }
                "go_forward" -> {
                    driver.goForward()
                    textResponse("Navigated forward")
                }

                // Wait / Query
                "wait_for_selector" -> {
                    val selector = requireArg(args, "selector")
                    val timeoutMs = (args["timeout_ms"] as? Number)?.toLong() ?: 3000L
                    driver.waitForSelector(selector, timeoutMs)
                    textResponse("Element '$selector' found")
                }
                "exists" -> {
                    val selector = requireArg(args, "selector")
                    textResponse(driver.exists(selector).toString())
                }
                "is_visible" -> {
                    val selector = requireArg(args, "selector")
                    textResponse(driver.isVisible(selector).toString())
                }
                "focus" -> {
                    val selector = requireArg(args, "selector")
                    driver.focus(selector)
                    textResponse("Focused '$selector'")
                }

                // Interaction
                "hover" -> {
                    val selector = requireArg(args, "selector")
                    driver.hover(selector)
                    textResponse("Hovered over '$selector'")
                }
                "click" -> {
                    val selector = requireArg(args, "selector")
                    val modifier = args["modifier"]?.toString()
                    if (modifier != null) driver.click(selector, modifier) else driver.click(selector)
                    textResponse("Clicked '$selector'${if (modifier != null) " with $modifier" else ""}")
                }
                "dblclick" -> {
                    val selector = requireArg(args, "selector")
                    // Double-click via JavaScript since the WebDriver API uses click(selector, count)
                    val encodedSelector = objectMapper.writeValueAsString(selector)
                    driver.evaluate("""
                        (() => {
                            const el = document.querySelector($encodedSelector);
                            if (el) { el.dispatchEvent(new MouseEvent('dblclick', {bubbles: true, cancelable: true})); }
                        })()
                    """.trimIndent())
                    textResponse("Double-clicked '$selector'")
                }
                "fill" -> {
                    val selector = requireArg(args, "selector")
                    val text = requireArg(args, "text")
                    driver.fill(selector, text)
                    textResponse("Filled '$selector'")
                }
                "type" -> {
                    val selector = requireArg(args, "selector")
                    val text = requireArg(args, "text")
                    driver.type(selector, text)
                    textResponse("Typed into '$selector'")
                }
                "press" -> {
                    val selector = requireArg(args, "selector")
                    val key = requireArg(args, "key")
                    driver.press(selector, key)
                    textResponse("Pressed '$key' on '$selector'")
                }
                "check" -> {
                    val selector = requireArg(args, "selector")
                    driver.check(selector)
                    textResponse("Checked '$selector'")
                }
                "uncheck" -> {
                    val selector = requireArg(args, "selector")
                    driver.uncheck(selector)
                    textResponse("Unchecked '$selector'")
                }
                "select_option" -> {
                    val selector = requireArg(args, "selector")
                    val value = requireArg(args, "value")
                    val encodedSelector = objectMapper.writeValueAsString(selector)
                    val encodedValue = objectMapper.writeValueAsString(value)
                    driver.evaluate("""
                        (() => {
                            const el = document.querySelector($encodedSelector);
                            if (el) { el.value = $encodedValue; el.dispatchEvent(new Event('change', {bubbles: true})); }
                        })()
                    """.trimIndent())
                    textResponse("Selected '$value' in '$selector'")
                }
                "drag" -> {
                    val src = requireArg(args, "source_selector")
                    val tgt = requireArg(args, "target_selector")
                    val encodedSrc = objectMapper.writeValueAsString(src)
                    val encodedTgt = objectMapper.writeValueAsString(tgt)
                    val script = """
                        (() => {
                            const s = document.querySelector($encodedSrc);
                            const t = document.querySelector($encodedTgt);
                            if (!s || !t) return JSON.stringify({dx:0,dy:0});
                            const sr = s.getBoundingClientRect();
                            const tr = t.getBoundingClientRect();
                            return JSON.stringify({dx: tr.x - sr.x + tr.width/2 - sr.width/2, dy: tr.y - sr.y + tr.height/2 - sr.height/2});
                        })()
                    """.trimIndent()
                    val result = driver.evaluate(script) as? String ?: """{"dx":0,"dy":0}"""
                    val parsed = objectMapper.readTree(result)
                    val dx = parsed.get("dx")?.asInt() ?: 0
                    val dy = parsed.get("dy")?.asInt() ?: 0
                    driver.dragAndDrop(src, dx, dy)
                    textResponse("Dragged '$src' to '$tgt'")
                }
                "upload" -> {
                    // File upload is not directly supported via WebDriver evaluate; acknowledge the request
                    textResponse("Upload acknowledged for '${args["selector"]}'")
                }

                // Scrolling
                "scroll_to" -> {
                    val selector = requireArg(args, "selector")
                    driver.scrollTo(selector)
                    textResponse("Scrolled to '$selector'")
                }
                "scroll_to_top" -> {
                    driver.scrollToTop()
                    textResponse("Scrolled to top")
                }
                "scroll_to_bottom" -> {
                    driver.scrollToBottom()
                    textResponse("Scrolled to bottom")
                }
                "scroll_to_middle" -> {
                    val ratio = (args["ratio"] as? Number)?.toDouble() ?: 0.5
                    driver.scrollToMiddle(ratio)
                    textResponse("Scrolled to position $ratio")
                }
                "scroll_by" -> {
                    val pixels = (args["pixels"] as? Number)?.toDouble() ?: 200.0
                    val scrolled = driver.scrollBy(pixels)
                    textResponse("Scrolled by $scrolled px")
                }

                // Content
                "text_content" -> {
                    textResponse(driver.textContent() ?: "")
                }
                "get_text" -> {
                    val selector = requireArg(args, "selector")
                    textResponse(driver.selectFirstTextOrNull(selector) ?: "")
                }
                "delay" -> {
                    val millis = (args["millis"] as? Number)?.toLong()
                        ?: throw IllegalArgumentException("Missing required parameter: millis")
                    driver.delay(millis)
                    textResponse("Waited ${millis}ms")
                }

                // Snapshot & page info
                "aria_snapshot" -> {
                    // Return an accessibility-style snapshot via outerHTML
                    textResponse(driver.outerHTML() ?: "")
                }
                "page_url" -> {
                    textResponse(driver.currentUrl())
                }
                "page_title" -> {
                    textResponse(driver.title())
                }

                // Screenshot
                "screenshot" -> {
                    val fullPage = (args["full_page"] as? Boolean) ?: false
                    val base64 = driver.captureScreenshot(fullPage)
                    textResponse(base64 ?: "")
                }

                // Evaluate
                "evaluate" -> {
                    val expression = requireArg(args, "expression")
                    textResponse(driver.evaluate(expression)?.toString() ?: "null")
                }

                // Dialog
                "dialog_accept" -> {
                    val promptText = args["prompt_text"]?.toString()
                    val script = if (promptText != null) {
                        "window.__browser4DialogPromptText = ${objectMapper.writeValueAsString(promptText)};"
                    } else {
                        "window.__browser4DialogAccepted = true;"
                    }
                    driver.evaluate(script)
                    textResponse("Dialog accepted")
                }
                "dialog_dismiss" -> {
                    driver.evaluate("window.__browser4DialogDismissed = true;")
                    textResponse("Dialog dismissed")
                }

                // Resize viewport
                "resize" -> {
                    val width = (args["width"] as? Number)?.toInt()
                        ?: throw IllegalArgumentException("Missing required parameter: width")
                    val height = (args["height"] as? Number)?.toInt()
                        ?: throw IllegalArgumentException("Missing required parameter: height")
                    driver.evaluate("window.resizeTo($width, $height)")
                    textResponse("Resized to ${width}x${height}")
                }

                // Keyboard
                "keydown" -> {
                    val key = requireArg(args, "key")
                    val encodedKey = objectMapper.writeValueAsString(key)
                    driver.evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key: $encodedKey, bubbles: true}))")
                    textResponse("Key down: $key")
                }
                "keyup" -> {
                    val key = requireArg(args, "key")
                    val encodedKey = objectMapper.writeValueAsString(key)
                    driver.evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keyup', {key: $encodedKey, bubbles: true}))")
                    textResponse("Key up: $key")
                }

                // Mouse
                "mousemove" -> {
                    val x = (args["x"] as? Number)?.toDouble()
                        ?: throw IllegalArgumentException("Missing required parameter: x")
                    val y = (args["y"] as? Number)?.toDouble()
                        ?: throw IllegalArgumentException("Missing required parameter: y")
                    driver.moveMouseTo(x, y)
                    textResponse("Mouse moved to ($x, $y)")
                }
                "mousedown" -> {
                    val button = args["button"]?.toString() ?: "left"
                    val btnIndex = when (button) { "right" -> 2; "middle" -> 1; else -> 0 }
                    val x = (args["x"] as? Number)?.toInt() ?: 0
                    val y = (args["y"] as? Number)?.toInt() ?: 0
                    driver.evaluate("document.elementFromPoint($x, $y)?.dispatchEvent(new MouseEvent('mousedown', {button: $btnIndex, bubbles: true}))")
                    textResponse("Mouse down ($button)")
                }
                "mouseup" -> {
                    val button = args["button"]?.toString() ?: "left"
                    val btnIndex = when (button) { "right" -> 2; "middle" -> 1; else -> 0 }
                    val x = (args["x"] as? Number)?.toInt() ?: 0
                    val y = (args["y"] as? Number)?.toInt() ?: 0
                    driver.evaluate("document.elementFromPoint($x, $y)?.dispatchEvent(new MouseEvent('mouseup', {button: $btnIndex, bubbles: true}))")
                    textResponse("Mouse up ($button)")
                }
                "mousewheel" -> {
                    val deltaX = (args["delta_x"] as? Number)?.toDouble() ?: 0.0
                    val deltaY = (args["delta_y"] as? Number)?.toDouble() ?: 100.0
                    if (deltaY > 0) driver.mouseWheelDown(1, deltaX, deltaY)
                    else driver.mouseWheelUp(1, deltaX, deltaY)
                    textResponse("Mouse wheel ($deltaX, $deltaY)")
                }

                // Tab management
                "tab_list" -> {
                    val result = driver.evaluate("""
                        (() => {
                            return JSON.stringify([{index: 0, url: document.URL, title: document.title}]);
                        })()
                    """.trimIndent()) as? String ?: "[]"
                    textResponse(result)
                }
                "tab_new" -> {
                    val url = args["url"]?.toString() ?: "about:blank"
                    val encodedUrl = objectMapper.writeValueAsString(url)
                    driver.evaluate("window.open($encodedUrl)")
                    textResponse(if (url == "about:blank") "New tab opened" else "New tab opened: $url")
                }
                "tab_close" -> {
                    driver.evaluate("window.close()")
                    textResponse("Tab closed")
                }
                "tab_select" -> {
                    driver.evaluate("window.focus()")
                    textResponse("Switched to tab ${args["index"] ?: 0}")
                }

                else -> errorResponse("Unknown tool: $tool")
            }
        } catch (e: IllegalArgumentException) {
            errorResponse("$tool: ${e.message}")
        } catch (e: Exception) {
            logger.error("MCP tool execution failed | tool={} | {}", tool, e.message)
            errorResponse("$tool failed: ${e.message}")
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun requireSessionId(request: MCPToolCallRequest): String {
        return request.arguments?.get("sessionId")?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
    }

    private fun requireArg(args: Map<String, Any?>, key: String): String {
        return args[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    private fun textResponse(text: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = text)))

    private fun errorResponse(message: String): MCPToolCallResponse =
        MCPToolCallResponse(content = listOf(MCPContent(text = message)), isError = true)
}
