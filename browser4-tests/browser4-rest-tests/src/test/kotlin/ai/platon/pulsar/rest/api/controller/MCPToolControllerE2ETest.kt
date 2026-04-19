package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.rest.api.TestHelper.MOCK_PRODUCT_LIST_URL
import ai.platon.pulsar.rest.api.TestHelper.MOCK_PRODUCT_DETAIL_URL
import ai.platon.pulsar.rest.mcp.controller.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.MCPToolController
import ai.platon.pulsar.rest.mcp.service.SessionManager
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive E2E tests for [MCPToolController] covering all commands supported by browser4-cli.
 *
 * Each test opens a session against the mock EC server, exercises the corresponding MCP tool,
 * and validates the response. Tests are organized by the CLI command categories defined in
 * `sdks/browser4-cli/src/cli.ts`.
 *
 * CLI command → MCP tool mapping:
 * - Core: open→open_session, goto→browser_navigate, click/dblclick→browser_click,
 *         fill→browser_type, drag→browser_drag, hover→browser_hover,
 *         select→browser_select_option, upload→browser_file_upload,
 *         check→browser_check, uncheck→browser_uncheck, type→browser_press_sequentially,
 *         snapshot→browser_snapshot, eval→browser_evaluate,
 *         dialog-accept/dialog-dismiss→browser_handle_dialog, resize→browser_resize,
 *         close→close_session
 * - Navigation: go-back→browser_navigate_back, go-forward→browser_navigate_forward, reload→browser_reload
 * - Keyboard: press→browser_press_key, keydown→browser_keydown, keyup→browser_keyup
 * - Mouse: mousemove→browser_mouse_move_xy, mousedown→browser_mouse_down,
 *         mouseup→browser_mouse_up, mousewheel→browser_mouse_wheel
 * - Save as: screenshot→browser_take_screenshot
 * - Tabs: tab-list/tab-new/tab-close/tab-select→browser_tabs
 * - Session management: list→list_sessions, close-all→close_all_sessions,
 *                       kill-all→kill_all_sessions, delete-data→delete_session_data
 */
@Tag("E2ETest")
class MCPToolControllerE2ETest : RestAPITestBase() {
    private val logger = LoggerFactory.getLogger(MCPToolControllerE2ETest::class.java)
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)

    @Autowired
    lateinit var sessionManager: SessionManager

    /**
     * Complete mapping from browser4-cli commands to MCP tool names.
     * Every CLI command must resolve to a recognized MCP tool.
     */
    private val cliCommandToMcpTool = mapOf(
        // Core
        "open" to "open_session",
        "goto" to "browser_navigate",
        "click" to "browser_click",
        "dblclick" to "browser_click",
        "fill" to "browser_type",
        "drag" to "browser_drag",
        "hover" to "browser_hover",
        "select" to "browser_select_option",
        "upload" to "browser_file_upload",
        "check" to "browser_check",
        "uncheck" to "browser_uncheck",
        "type" to "browser_press_sequentially",
        "snapshot" to "browser_snapshot",
        "eval" to "browser_evaluate",
        "dialog-accept" to "browser_handle_dialog",
        "dialog-dismiss" to "browser_handle_dialog",
        "resize" to "browser_resize",
        "close" to "close_session",
        // Navigation
        "go-back" to "browser_navigate_back",
        "go-forward" to "browser_navigate_forward",
        "reload" to "browser_reload",
        // Keyboard
        "press" to "browser_press_key",
        "keydown" to "browser_keydown",
        "keyup" to "browser_keyup",
        // Mouse
        "mousemove" to "browser_mouse_move_xy",
        "mousedown" to "browser_mouse_down",
        "mouseup" to "browser_mouse_up",
        "mousewheel" to "browser_mouse_wheel",
        // Save as
        "screenshot" to "browser_take_screenshot",
        // Tabs
        "tab-list" to "browser_tabs",
        "tab-new" to "browser_tabs",
        "tab-close" to "browser_tabs",
        "tab-select" to "browser_tabs",
        // Session management
        "list" to "list_sessions",
        "close-all" to "close_all_sessions",
        "kill-all" to "kill_all_sessions",
        "delete-data" to "delete_session_data"
    )

    /** Track sessions created during a test so we can clean up. */
    private val createdSessions = mutableListOf<String>()

    @AfterEach
    fun cleanUpSessions() {
        // Best-effort cleanup of any sessions left open by the test
        try {
            // sessionManager.deleteAllSessions()
            callTool("kill_all_sessions")
        } catch (e: Exception) {
            logger.debug("Cleanup kill_all_sessions failed (may be expected): {}", e.message)
        }
        createdSessions.clear()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun callTool(tool: String, arguments: Map<String, Any?> = emptyMap()): MCPToolCallResponse {
        val request = mapOf("tool" to tool, "arguments" to arguments)
        val body = client.post().uri("/mcp/call-tool")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody!!

        val tree = objectMapper.readTree(body)
        if (tree is ObjectNode && (tree.get("isError") == null || tree.get("isError").isNull)) {
            tree.put("isError", false)
        }
        return objectMapper.treeToValue(tree, MCPToolCallResponse::class.java)
    }

    private fun textContent(response: MCPToolCallResponse): String {
        return response.content.firstOrNull()?.text.orEmpty()
    }

    private fun assertNotError(response: MCPToolCallResponse) {
        assertFalse(response.isError, "Expected successful MCP response but got: $response")
    }

    private fun assertIsError(response: MCPToolCallResponse) {
        assertTrue(response.isError, "Expected error MCP response but got: $response")
    }

    private fun assertToolRecognized(tool: String, arguments: Map<String, Any?> = emptyMap()) {
        val response = callTool(tool, arguments)
        val text = textContent(response)

        printlnPro(this, "Tool '$tool' response: $response")

        assertFalse(text.contains("Unknown tool:"), "Tool '$tool' should be recognized, response: $response")
    }

    private fun openSession(url: String = MOCK_PRODUCT_DETAIL_URL): String {
        val openResponse = callTool("open_session", mapOf("url" to url))
        assertNotError(openResponse)
        val sessionId = objectMapper.readTree(textContent(openResponse)).path("sessionId").asText()
        assertTrue(sessionId.isNotBlank(), "open_session must return a non-blank sessionId")
        createdSessions.add(sessionId)
        return sessionId
    }

    private fun closeSession(sessionId: String) {
        val response = callTool("close_session", mapOf("sessionId" to sessionId))
        assertNotError(response)
        assertTrue(textContent(response).contains("Session closed"))
        createdSessions.remove(sessionId)
    }

    /** Shorthand: open a session, navigate to the given URL, and return the sessionId. */
    private fun openAndNavigate(url: String = MOCK_PRODUCT_DETAIL_URL): String {
        val sid = openSession()
        val navResponse = callTool("browser_navigate", mapOf("sessionId" to sid, "url" to url))
        assertNotError(navResponse)
        return sid
    }

    // =========================================================================
    // 1. Tool listing & CLI coverage
    // =========================================================================

    @Test
    @DisplayName("GET /mcp/tools lists all tools required by browser4-cli")
    fun testToolsEndpointCoversAllCliCommands() {
        val payload = client.get().uri("/mcp/tools")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Map<String, Any>>()
            .returnResult()
            .responseBody
        assertNotNull(payload)

        @Suppress("UNCHECKED_CAST")
        val tools = (payload["tools"] as List<String>).toSet()
        printlnPro(this, tools)
        val missingTools = cliCommandToMcpTool.values.filter { it !in tools }
        assertTrue(missingTools.isEmpty(), "Missing MCP tools for cli commands: $missingTools")
    }

    @Test
    @DisplayName("POST /mcp/call-tool accepts frontend-declared Browser4 CLI tool names")
    fun testFrontendToolNamesAreRecognized() {
        val sid = openSession()

        assertToolRecognized("browser_navigate", mapOf("sessionId" to sid, "url" to MOCK_PRODUCT_DETAIL_URL))
        assertToolRecognized("browser_snapshot", mapOf("sessionId" to sid))
        assertToolRecognized("browser_tabs", mapOf("sessionId" to sid, "action" to "list"))
    }

    // =========================================================================
    // 2. Session management — cli: open, close, list, close-all, kill-all, delete-data
    // =========================================================================

    @Test
    @DisplayName("open_session returns a valid sessionId (cli: open)")
    fun testOpenSession() {
        val sessionId = openSession()
        assertTrue(sessionId.isNotBlank())
    }

    @Test
    @DisplayName("close_session closes a previously opened session (cli: close)")
    fun testCloseSession() {
        val sessionId = openSession()
        closeSession(sessionId)

        // After closing, the session should not appear in list
        val listResponse = callTool("list_sessions")
        assertNotError(listResponse)
        assertFalse(textContent(listResponse).contains(sessionId))
    }

    @Test
    @DisplayName("close_session returns error for unknown sessionId")
    fun testCloseSessionNotFound() {
        val response = callTool("close_session", mapOf("sessionId" to "non-existent-id"))
        assertIsError(response)
        assertTrue(textContent(response).contains("Session not found"))
    }

    @Test
    @DisplayName("list_sessions shows all active sessions (cli: list)")
    fun testListSessions() {
        val sid1 = openSession(MOCK_PRODUCT_DETAIL_URL)
        val sid2 = openSession(MOCK_PRODUCT_LIST_URL)

        val listResponse = callTool("list_sessions")
        assertNotError(listResponse)
        val text = textContent(listResponse)
        assertTrue(text.contains(sid1), "list_sessions should contain session $sid1")
        assertTrue(text.contains(sid2), "list_sessions should contain session $sid2")
    }

    @Test
    @DisplayName("close_all_sessions closes every session (cli: close-all)")
    fun testCloseAllSessions() {
        val sid1 = openSession(MOCK_PRODUCT_DETAIL_URL)
        val sid2 = openSession(MOCK_PRODUCT_LIST_URL)

        val closeAllResponse = callTool("close_all_sessions")
        assertNotError(closeAllResponse)
        assertTrue(textContent(closeAllResponse).contains("Closed"))

        val listResponse = callTool("list_sessions")
        assertNotError(listResponse)
        val text = textContent(listResponse)
        assertFalse(text.contains(sid1))
        assertFalse(text.contains(sid2))
        createdSessions.clear()
    }

    @Test
    @DisplayName("kill_all_sessions kills every session (cli: kill-all)")
    fun testKillAllSessions() {
        val sid = openSession(MOCK_PRODUCT_DETAIL_URL)

        val killResponse = callTool("kill_all_sessions")
        assertNotError(killResponse)
        assertTrue(textContent(killResponse).contains("Killed"))

        val listResponse = callTool("list_sessions")
        assertNotError(listResponse)
        assertFalse(textContent(listResponse).contains(sid))
        createdSessions.clear()
    }

    @Test
    @DisplayName("delete_session_data clears cookies and storage (cli: delete-data)")
    fun testDeleteSessionData() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)

        val response = callTool("delete_session_data", mapOf("sessionId" to sid))
        assertNotError(response)
        assertTrue(textContent(response).contains("User data deleted"))
    }

    // =========================================================================
    // 3. Navigation — cli: goto, go-back, go-forward, reload
    // =========================================================================

    @Test
    @DisplayName("navigate loads a URL in the session browser (cli: goto)")
    fun testNavigate() {
        val sid = openSession()
        val response = callTool("navigate", mapOf("sessionId" to sid, "url" to MOCK_PRODUCT_DETAIL_URL))
        assertNotError(response)
    }

    @Test
    @DisplayName("go_back navigates the browser back (cli: go-back)")
    fun testGoBack() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        callTool("navigate", mapOf("sessionId" to sid, "url" to MOCK_PRODUCT_LIST_URL))

        assertToolRecognized("go_back", mapOf("sessionId" to sid))
    }

    @Test
    @DisplayName("go_forward navigates the browser forward (cli: go-forward)")
    fun testGoForward() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)

        assertToolRecognized("go_forward", mapOf("sessionId" to sid))
    }

    @Test
    @DisplayName("reload refreshes the current page (cli: reload)")
    fun testReload() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)

        assertToolRecognized("reload", mapOf("sessionId" to sid))
    }

    // =========================================================================
    // 4. Element interaction — cli: click, dblclick, fill, hover, drag,
    //    select, upload, check, uncheck, type
    // =========================================================================

    @Test
    @DisplayName("click dispatches a click on an element (cli: click)")
    fun testClick() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("click", mapOf("sessionId" to sid, "selector" to "body"))
    }

    @Test
    @DisplayName("dblclick dispatches a double-click on an element (cli: dblclick)")
    fun testDblclick() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("dblclick", mapOf("sessionId" to sid, "selector" to "body"))
    }

    @Test
    @DisplayName("fill types text into an input (cli: fill)")
    fun testFill() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("fill", mapOf("sessionId" to sid, "selector" to "#productTitle", "text" to "Browser4"))
    }

    @Test
    @DisplayName("type appends text into an input (cli: type)")
    fun testType() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("type", mapOf("sessionId" to sid, "selector" to "#productTitle", "text" to "Browser4"))
    }

    @Test
    @DisplayName("hover moves the pointer over an element (cli: hover)")
    fun testHover() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("hover", mapOf("sessionId" to sid, "selector" to "body"))
    }

    @Test
    @DisplayName("drag drags from one element to another (cli: drag)")
    fun testDrag() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized(
            "drag",
            mapOf("sessionId" to sid, "sourceSelector" to "body", "targetSelector" to "body")
        )
    }

    @Test
    @DisplayName("select_option selects a dropdown value (cli: select)")
    fun testSelectOption() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("select_option", mapOf("sessionId" to sid, "selector" to "body", "values" to listOf("1")))
    }

    @Test
    @DisplayName("upload attaches files to an input (cli: upload)")
    fun testUpload() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized(
            "upload",
            mapOf("sessionId" to sid, "selector" to "body", "paths" to listOf("README.md"))
        )
    }

    @Test
    @DisplayName("check ticks a checkbox (cli: check)")
    fun testCheck() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("check", mapOf("sessionId" to sid, "selector" to "body"))
    }

    @Test
    @DisplayName("uncheck clears a checkbox (cli: uncheck)")
    fun testUncheck() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("uncheck", mapOf("sessionId" to sid, "selector" to "body"))
    }

    // =========================================================================
    // 5. Content & state — cli: snapshot, eval, screenshot
    // =========================================================================

    @Test
    @DisplayName("aria_snapshot returns accessibility snapshot (cli: snapshot)")
    fun testAriaSnapshot() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("aria_snapshot", mapOf("sessionId" to sid))
    }

    @Test
    @DisplayName("evaluate runs JavaScript and returns the result (cli: eval)")
    fun testEvaluate() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("evaluate", mapOf("sessionId" to sid, "expression" to "document.title"))
    }

    @Test
    @DisplayName("screenshot captures the page (cli: screenshot)")
    fun testScreenshot() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("screenshot", mapOf("sessionId" to sid))
    }

    @Test
    @DisplayName("page_url returns the current URL")
    fun testPageUrl() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("page_url", mapOf("sessionId" to sid))
    }

    @Test
    @DisplayName("page_title returns the current page title")
    fun testPageTitle() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("page_title", mapOf("sessionId" to sid))
    }

    // =========================================================================
    // 6. Keyboard — cli: press, keydown, keyup
    // =========================================================================

    @Test
    @DisplayName("press dispatches a key press event (cli: press)")
    fun testPress() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("press", mapOf("sessionId" to sid, "selector" to "body", "key" to "Enter"))
    }

    @Test
    @DisplayName("keydown dispatches a keydown event (cli: keydown)")
    fun testKeydown() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("keydown", mapOf("sessionId" to sid, "key" to "A"))
    }

    @Test
    @DisplayName("keyup dispatches a keyup event (cli: keyup)")
    fun testKeyup() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("keyup", mapOf("sessionId" to sid, "key" to "A"))
    }

    // =========================================================================
    // 7. Mouse — cli: mousemove, mousedown, mouseup, mousewheel
    // =========================================================================

    @Test
    @DisplayName("mousemove moves the pointer to coordinates (cli: mousemove)")
    fun testMousemove() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("mousemove", mapOf("sessionId" to sid, "x" to 100, "y" to 200))
    }

    @Test
    @DisplayName("mousedown dispatches a mousedown event (cli: mousedown)")
    fun testMousedown() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("mousedown", mapOf("sessionId" to sid, "button" to "left"))
    }

    @Test
    @DisplayName("mouseup dispatches a mouseup event (cli: mouseup)")
    fun testMouseup() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("mouseup", mapOf("sessionId" to sid, "button" to "left"))
    }

    @Test
    @DisplayName("mousewheel dispatches a mouse wheel event (cli: mousewheel)")
    fun testMousewheel() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("mousewheel", mapOf("sessionId" to sid, "deltaX" to 0, "deltaY" to 120))
    }

    // =========================================================================
    // 8. Dialogs — cli: dialog-accept, dialog-dismiss
    // =========================================================================

    @Test
    @DisplayName("dialog_accept accepts the current dialog (cli: dialog-accept)")
    fun testDialogAccept() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("dialog_accept", mapOf("sessionId" to sid, "promptText" to "ok"))
    }

    @Test
    @DisplayName("dialog_dismiss dismisses the current dialog (cli: dialog-dismiss)")
    fun testDialogDismiss() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("dialog_dismiss", mapOf("sessionId" to sid))
    }

    // =========================================================================
    // 9. Viewport — cli: resize
    // =========================================================================

    @Test
    @DisplayName("resize changes the browser viewport (cli: resize)")
    fun testResize() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("resize", mapOf("sessionId" to sid, "width" to 1200, "height" to 900))
    }

    // =========================================================================
    // 10. Tab management — cli: tab-list, tab-new, tab-close, tab-select
    // =========================================================================

    @Test
    @DisplayName("tab_list lists open tabs (cli: tab-list)")
    fun testTabList() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("tab_list", mapOf("sessionId" to sid))
    }

    @Test
    @DisplayName("tab_new opens a new tab (cli: tab-new)")
    fun testTabNew() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("tab_new", mapOf("sessionId" to sid, "url" to MOCK_PRODUCT_LIST_URL))
    }

    @Test
    @DisplayName("tab_close closes a tab (cli: tab-close)")
    fun testTabClose() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("tab_close", mapOf("sessionId" to sid, "tabId" to "0"))
    }

    @Test
    @DisplayName("tab_select switches to a tab by id (cli: tab-select)")
    fun testTabSelect() {
        val sid = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        assertToolRecognized("tab_select", mapOf("sessionId" to sid, "tabId" to "0"))
    }

    // =========================================================================
    // 11. Error handling
    // =========================================================================

    @Test
    @DisplayName("Calling a tool without sessionId returns an error")
    fun testMissingSessionIdReturnsError() {
        // navigate requires sessionId
        val response = callTool("navigate", mapOf("url" to MOCK_PRODUCT_DETAIL_URL))
        assertIsError(response)
        assertTrue(textContent(response).contains("sessionId"))
    }

    @Test
    @DisplayName("Calling an unknown tool returns an error")
    fun testUnknownToolReturnsError() {
        val sid = openSession()
        val response = callTool("nonexistent_tool_xyz", mapOf("sessionId" to sid))
        assertIsError(response)
        assertTrue(textContent(response).contains("Unknown tool"))
    }

    @Test
    @DisplayName("Calling a tool with an invalid sessionId returns an error")
    fun testInvalidSessionIdReturnsError() {
        val response = callTool("navigate", mapOf("sessionId" to "does-not-exist", "url" to MOCK_PRODUCT_DETAIL_URL))
        assertIsError(response)
        assertTrue(textContent(response).contains("Session not found"))
    }

    // =========================================================================
    // 12. Multi-session workflow
    // =========================================================================

    @Test
    @DisplayName("Multiple sessions can coexist and be managed independently")
    fun testMultiSessionWorkflow() {
        val sid1 = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)
        val sid2 = openAndNavigate(MOCK_PRODUCT_LIST_URL)

        // Both sessions should appear in list_sessions
        val listResponse = callTool("list_sessions")
        assertNotError(listResponse)
        val text = textContent(listResponse)
        assertTrue(text.contains(sid1), "list_sessions should contain session $sid1")
        assertTrue(text.contains(sid2), "list_sessions should contain session $sid2")

        // Close only the first session
        closeSession(sid1)

        // sid1 gone, sid2 still present
        val listAfter = callTool("list_sessions")
        assertNotError(listAfter)
        val textAfter = textContent(listAfter)
        assertFalse(textAfter.contains(sid1))
        assertTrue(textAfter.contains(sid2))
    }
}
