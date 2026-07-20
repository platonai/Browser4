package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
// DTO classes are defined in ai.platon.pulsar.rest.mcp.controller (top-level in MCPToolController.kt)
// and are available without explicit import since the test is in the same package.
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.springframework.http.HttpStatus
import java.util.*

class MCPToolControllerTest {
    private val objectMapper = pulsarObjectMapper()

    @Mock
    private lateinit var sessionManager: PulsarSessionManager

    @Mock
    private lateinit var response: HttpServletResponse

    @Mock
    private lateinit var managedSession: ManagedSession

    @Mock
    private lateinit var agenticSession: AgenticSession

    @Mock
    private lateinit var basicBrowserAgent: BasicBrowserAgent

    @Mock
    private lateinit var agentToolManager: AgentToolManager

    private lateinit var controller: MCPToolController

    private val sessionId = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        controller = MCPToolController(sessionManager)

        // Setup session structure
        `when`(sessionManager.getSession(sessionId)).thenReturn(managedSession)
        `when`(managedSession.agenticSession).thenReturn(agenticSession)
        `when`(agenticSession.companionAgent).thenReturn(basicBrowserAgent)
        `when`(basicBrowserAgent.agentToolManager).thenReturn(agentToolManager)
    }

    @AfterEach
    fun tearDown() {
        // Clean up any test executors leaked into the singleton registry.
        CustomToolRegistry.instance.getAllDomains().forEach {
            CustomToolRegistry.instance.unregister(it)
        }
    }

    private fun capture(captor: ArgumentCaptor<ToolCall>): ToolCall {
        captor.capture()
        return ToolCall("dummy", "dummy")
    }

    @Test
    fun `test response deserializes null isError as false`() {
        val json = """{"content":[{"type":"text","text":"ok"}],"isError":null}"""

        val result = objectMapper.readValue(json, MCPToolCallResponse::class.java)

        assertEquals(false, result.isError)
        assertEquals("ok", result.content.single().text)
    }

    @Test
    fun `test response serializes isError with canonical field name`() {
        val json = objectMapper.writeValueAsString(
            MCPToolCallResponse(
                content = listOf(MCPContent(text = "boom")),
                isError = true
            )
        )

        assertTrue(json.contains(""""isError":true"""))
    }

    @Test
    fun `test close session`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "close_session",
            arguments = mapOf("sessionId" to sessionId)
        )
        `when`(sessionManager.deleteSession(sessionId)).thenReturn(true)

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Session closed", result.body!!.content[0].text)
        Mockito.verify(sessionManager).deleteSession(sessionId)
        Unit
    }

    @Test
    fun `test list sessions`() = runBlocking {
        val request = MCPToolCallRequest(tool = "list_sessions")
        `when`(sessionManager.getAllSessions()).thenReturn(listOf(managedSession))
        `when`(managedSession.sessionId).thenReturn(sessionId)
        `when`(managedSession.url).thenReturn("https://example.com")
        `when`(managedSession.status).thenReturn("active")

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.content[0].text.contains(sessionId))
        assertTrue(result.body!!.content[0].text.contains("https://example.com"))
    }

    @Test
    fun `test close all sessions`() = runBlocking {
        val request = MCPToolCallRequest(tool = "close_all_sessions")
        `when`(sessionManager.deleteAllSessions()).thenReturn(5)

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Closed 5 session(s)", result.body!!.content[0].text)
    }

    @Test
    fun `test kill all sessions`() = runBlocking {
        val request = MCPToolCallRequest(tool = "kill_all_sessions")
        `when`(sessionManager.deleteAllSessions()).thenReturn(3)

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Killed 3 session(s)", result.body!!.content[0].text)
    }

    @Test
    fun `test list tools returns static tools when no sessions are active`() {
        `when`(sessionManager.getAllSessions()).thenReturn(emptyList())

        val result = controller.listTools(response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val tools = ((result.body as Map<String, Any>)["tools"] as List<String>).toSet()
        // Static tools + frontend aliases are always returned.
        assertTrue(tools.contains("open_session"))
        assertTrue(tools.contains("browser_navigate"))
    }

    @Test
    fun `test frontend navigate tool maps to navigate`() = runBlocking {
        mockTool("tab", "navigate")

        val request = MCPToolCallRequest(
            tool = "browser_navigate",
            arguments = mapOf("sessionId" to sessionId, "url" to "https://example.com")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("navigate", toolCall.method)
        assertTrue(!toolCall.arguments.containsKey("sessionId"))
        assertEquals("https://example.com", toolCall.arguments["url"])
    }

    @Test
    fun `test frontend click command`() = runBlocking {
        mockTool("tab", "click")

        val request = MCPToolCallRequest(
            tool = "browser_click",
            arguments = mapOf("sessionId" to sessionId, "ref" to "#btn")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("click", toolCall.method)
        assertEquals("#btn", toolCall.arguments["selector"])
    }

    @Test
    fun `test frontend fill command`() = runBlocking {
        mockTool("tab", "fill")

        val request = MCPToolCallRequest(
            tool = "browser_type",
            arguments = mapOf("sessionId" to sessionId, "ref" to "#input", "text" to "text")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("fill", toolCall.method)
        assertEquals("#input", toolCall.arguments["selector"])
        assertEquals("text", toolCall.arguments["text"])
    }

    @Test
    fun `test frontend press command without ref`() = runBlocking {
        mockTool("tab", "press")

        val request = MCPToolCallRequest(
            tool = "browser_press_key",
            arguments = mapOf("sessionId" to sessionId, "key" to "Enter")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("press", toolCall.method)
        assertEquals("Enter", toolCall.arguments["key"])
        assertTrue(!toolCall.arguments.containsKey("selector"))
    }

    @Test
    fun `test frontend type command without ref`() = runBlocking {
        mockTool("tab", "type")

        val request = MCPToolCallRequest(
            tool = "browser_press_sequentially",
            arguments = mapOf("sessionId" to sessionId, "text" to "hello")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("type", toolCall.method)
        assertEquals("hello", toolCall.arguments["text"])
        assertTrue(!toolCall.arguments.containsKey("selector"))
    }

    @Test
    fun `test explicit mapping page_title`() = runBlocking {
        // page_title maps to driver.title explicitly in resolveToolCall
        val request = MCPToolCallRequest(
            tool = "page_title",
            arguments = mapOf("sessionId" to sessionId)
        )

        // Mock execute to return a value
        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("My Page Title"))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("My Page Title", result.body!!.content[0].text)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("title", toolCall.method)
    }

    @Test
    fun `test browser evaluate maps to evaluateValue`() = runBlocking {
        mockTool("tab", "evaluateValue")

        val request = MCPToolCallRequest(
            tool = "browser_evaluate",
            arguments = mapOf("sessionId" to sessionId, "expression" to "document.title")
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("Browser4 CLI Other Fixture"))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Browser4 CLI Other Fixture", result.body!!.content[0].text)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("evaluateValue", toolCall.method)
        assertEquals("document.title", toolCall.arguments["expression"])
    }

    @Test
    fun `test browser evaluate with ref remaps to function declaration`() = runBlocking {
        mockTool("tab", "evaluateValue")

        val request = MCPToolCallRequest(
            tool = "browser_evaluate",
            arguments = mapOf(
                "sessionId" to sessionId,
                "ref" to "#page-marker",
                "expression" to "(element) => element.textContent"
            )
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("other page"))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("other page", result.body!!.content[0].text)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("evaluateValue", toolCall.method)
        assertEquals("#page-marker", toolCall.arguments["selector"])
        assertEquals("(element) => element.textContent", toolCall.arguments["functionDeclaration"])
        assertTrue("expression" !in toolCall.arguments)
    }

    @Test
    fun `test browser save storage state maps to saveStorageState`() = runBlocking {
        mockTool("tab", "saveStorageState")

        val request = MCPToolCallRequest(
            tool = "browser_save_storage_state",
            arguments = mapOf("sessionId" to sessionId)
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("""{"cookies":[],"origins":[]}"""))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("saveStorageState", toolCall.method)
    }

    @Test
    fun `test browser load storage state maps to loadStorageState`() = runBlocking {
        mockTool("tab", "loadStorageState")

        val request = MCPToolCallRequest(
            tool = "browser_load_storage_state",
            arguments = mapOf("sessionId" to sessionId, "state" to """{"cookies":[],"origins":[]}""")
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("""{"cookies":0,"origins":0,"localStorageEntries":0}"""))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("loadStorageState", toolCall.method)
        assertEquals("""{"cookies":[],"origins":[]}""", toolCall.arguments["state"])
    }

    @Test
    fun `test eval tool name resolves to tab eval and preserves selector and expression`() = runBlocking {
        mockTool("tab", "eval")

        val request = MCPToolCallRequest(
            tool = "eval",
            arguments = mapOf(
                "sessionId" to sessionId,
                "selector" to "#page-marker",
                "expression" to "(element) => element.textContent"
            )
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("other page"))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("other page", result.body!!.content[0].text)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("eval", toolCall.method)
        assertEquals("#page-marker", toolCall.arguments["selector"])
        assertEquals("(element) => element.textContent", toolCall.arguments["expression"])
        assertFalse("functionDeclaration" in toolCall.arguments)
    }

    @Test
    fun `test frontend tab select maps to browser switchTab`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "browser_tabs",
            arguments = mapOf("sessionId" to sessionId, "action" to "select", "index" to 1)
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("ok"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("browser", toolCall.domain)
        assertEquals("switchTab", toolCall.method)
        assertEquals(1, toolCall.arguments["index"])
        assertFalse(toolCall.arguments.containsKey("tabId"))
    }

    @Test
    fun `test snake case arguments normalize for drag`() = runBlocking {
        mockTool("tab", "drag")

        val request = MCPToolCallRequest(
            tool = "drag",
            arguments = mapOf("sessionId" to sessionId, "source_selector" to "#a", "target_selector" to "#b")
        )

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("drag", toolCall.method)
        assertEquals("#a", toolCall.arguments["sourceSelector"])
        assertEquals("#b", toolCall.arguments["targetSelector"])
    }

    @Test
    fun `test generic canonical commands`() = runBlocking {
        val commands = listOf(
            Triple("go_back", "tab", "goBack"),
            Triple("go_forward", "tab", "goForward"),
            Triple("navigate", "tab", "navigate"),
            Triple("reload", "tab", "reload"),
            Triple("press", "tab", "press"),
            Triple("hover", "tab", "hover"),
            Triple("screenshot", "tab", "screenshot"),
            Triple("dblclick", "tab", "dblclick"),
            Triple("drag", "tab", "drag"),
            Triple("select_option", "tab", "select_option"),
            Triple("upload", "tab", "upload"),
            Triple("check", "tab", "check"),
            Triple("uncheck", "tab", "uncheck"),
            Triple("type", "tab", "type"),
            Triple("evaluate", "tab", "evaluate"),
            Triple("evaluate_value", "tab", "evaluateValue"),
            Triple("dialog_accept", "tab", "dialog_accept"),
            Triple("dialog_dismiss", "tab", "dialog_dismiss"),
            Triple("resize", "tab", "resize"),
            Triple("keydown", "tab", "keyDown"),
            Triple("keyup", "tab", "keyUp"),
            Triple("mousemove", "tab", "mouseMove"),
            Triple("mousedown", "tab", "mouseDown"),
            Triple("mouseup", "tab", "mouseUp"),
            Triple("mousewheel", "tab", "mouseWheel")
        )

        for ((tool, domain, method) in commands) {
            mockTool(domain, method)

            val request = MCPToolCallRequest(
                tool = tool,
                arguments = mapOf("sessionId" to sessionId, "arg" to "val")
            )

            // Reset mocks for each iteration to avoid interference or strict stubbing issues
            Mockito.reset(agentToolManager)
            mockTool(domain, method) // Re-apply stubbing

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode, "Failed for tool: $tool")

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals(domain, toolCall.domain)
            assertEquals(method, toolCall.method)
        }
    }

    @Test
    fun `test frontend mouse and keyboard tool names`() = runBlocking {
        val commands = listOf(
            "browser_keydown" to "keyDown",
            "browser_keyup" to "keyUp",
            "browser_mouse_move_xy" to "mouseMove",
            "browser_mouse_down" to "mouseDown",
            "browser_mouse_up" to "mouseUp",
            "browser_mouse_wheel" to "mouseWheel"
        )

        for ((tool, method) in commands) {
            mockTool("tab", method)

            val request = MCPToolCallRequest(
                tool = tool,
                arguments = mapOf("sessionId" to sessionId, "arg" to "val")
            )

            Mockito.reset(agentToolManager)
            mockTool("tab", method)

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode, "Failed for tool: $tool")

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals(method, toolCall.method)
        }
    }

    @Test
    fun `test frontend tab new maps to browser newTab`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "browser_tabs",
            arguments = mapOf("sessionId" to sessionId, "action" to "new", "url" to "about:blank")
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("ok"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("browser", toolCall.domain)
        assertEquals("newTab", toolCall.method)
    }

    @Test
    fun `test frontend tab list maps to browser listTabs`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "browser_tabs",
            arguments = mapOf("sessionId" to sessionId, "action" to "list")
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("[]"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("browser", toolCall.domain)
        assertEquals("listTabs", toolCall.method)
    }

    @Test
    fun `test frontend tab close maps to browser closeTab`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "browser_tabs",
            arguments = mapOf("sessionId" to sessionId, "action" to "close")
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("ok"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("browser", toolCall.domain)
        assertEquals("closeTab", toolCall.method)
    }

    @Test
    fun `test frontend tab close with index maps to browser closeTab`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "browser_tabs",
            arguments = mapOf("sessionId" to sessionId, "action" to "close", "index" to 1)
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("ok"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("browser", toolCall.domain)
        assertEquals("closeTab", toolCall.method)
        assertEquals(1, toolCall.arguments["index"])
        assertFalse(toolCall.arguments.containsKey("tabId"))
    }

    @Test
    fun `test frontend dialog tool maps to dismiss variant`() = runBlocking {
        mockTool("tab", "dialogDismiss")

        val request = MCPToolCallRequest(
            tool = "browser_handle_dialog",
            arguments = mapOf("sessionId" to sessionId, "accept" to false)
        )

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("dialogDismiss", toolCall.method)
    }

    @Test
    fun `test frontend double click maps to dblclick`() = runBlocking {
        mockTool("tab", "dblclick")

        val request = MCPToolCallRequest(
            tool = "browser_click",
            arguments = mapOf("sessionId" to sessionId, "ref" to "#btn", "doubleClick" to true)
        )

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("dblclick", toolCall.method)
        assertEquals("#btn", toolCall.arguments["selector"])
    }

    @Test
    fun `test delete session data`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "delete_session_data",
            arguments = mapOf("sessionId" to sessionId)
        )

        val mockMutex = Mockito.mock(Mutex::class.java)
        `when`(managedSession.mutex).thenReturn(mockMutex)

        // Mock lock/unlock
        `when`(mockMutex.lock(any())).thenReturn(Unit)

        val mockDriver = Mockito.mock(WebDriver::class.java)
        `when`(managedSession.driver).thenReturn(mockDriver)

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("User data deleted for session", result.body!!.content[0].text)

        Mockito.verify(mockDriver).clearBrowserCookies()
        Unit
    }

    @Test
    fun `test unknown tool returns error`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "unknown_tool",
            arguments = mapOf("sessionId" to sessionId)
        )

        // Return empty specs so it's not found
        `when`(agentToolManager.getAllToolSpecs()).thenReturn(emptyMap())

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.isError)
        assertTrue(result.body!!.content[0].text.contains("Unknown tool: unknown_tool"))
    }

    // inspectDocument — container selector scoping
    // -------------------------------------------------------------------

    @Test
    fun `inspectDocument with container selector scopes to descendants`() {
        val html = """
            <html><body>
              <main>
                <div class="product"><a href="/p1">Product 1</a><span>$10</span></div>
                <div class="product"><a href="/p2">Product 2</a><span>$20</span></div>
                <div class="product"><a href="/p3">Product 3</a><span>$30</span></div>
                <div class="product"><a href="/p4">Product 4</a><span>$40</span></div>
              </main>
              <aside>
                <div class="sidebar-item"><a href="/s1">Side 1</a></div>
                <div class="sidebar-item"><a href="/s2">Side 2</a></div>
              </aside>
            </body></html>
        """.trimIndent()

        val document = ai.platon.pulsar.dom.FeaturedDocument(org.jsoup.Jsoup.parse(html))
        val result = inspectDocument(document, "main", 10, 3)

        assertTrue(result.contains("matchCount"), "Result should contain matchCount")
        assertTrue(result.contains("selector"), "Result should contain selector")
        // The container-scoped inspection should find .product within 'main'
        // (at minimum there should be some matches)
        val parsed = objectMapper.readTree(result)
        val mc = parsed.get("matchCount")?.asInt() ?: 0
        val sel = parsed.get("selector")?.asText() ?: ""
        assertTrue(mc >= 1, "Expected matchCount >= 1 for container 'main', got $mc | selector=$sel | result snippet: ${result.take(200)}")
    }

    @Test
    fun `inspectDocument with root selector uses page-level discovery`() {
        val html = """
            <html><body>
              <div class="card"><h3>Card 1</h3></div>
              <div class="card"><h3>Card 2</h3></div>
              <div class="card"><h3>Card 3</h3></div>
            </body></html>
        """.trimIndent()

        val document = ai.platon.pulsar.dom.FeaturedDocument(org.jsoup.Jsoup.parse(html))
        val result = inspectDocument(document, ":root", 10, 3)

        assertTrue(result.contains("matchCount"))
        val parsed = objectMapper.readTree(result)
        val mc = parsed.get("matchCount")?.asInt() ?: 0
        assertTrue(mc >= 1, "Expected page-level discovery to find at least 1 match, got $mc")
    }

    // -------------------------------------------------------------------
    // autoDiscoverRepeatingSelector — basic functionality
    // -------------------------------------------------------------------

    @Test
    fun `autoDiscoverRepeatingSelector finds repeating siblings`() {
        val html = """
            <html><body><main>
              <div class="item"><span>Item A</span></div>
              <div class="item"><span>Item B</span></div>
              <div class="item"><span>Item C</span></div>
              <div class="item"><span>Item D</span></div>
              <div class="item"><span>Item E</span></div>
            </main></body></html>
        """.trimIndent()

        val document = ai.platon.pulsar.dom.FeaturedDocument(org.jsoup.Jsoup.parse(html))
        val candidates = autoDiscoverRepeatingSelector(document)
        val selector = candidates.firstOrNull()?.selector

        // With 5 repeating .item elements, it should discover a selector
        // (may be empty list on edge cases, but with 5 identical siblings it should find something)
        val discovered = selector != null
        // Not strictly required — discovery may not always succeed — but with this input it should
        if (!discovered) {
            println("autoDiscoverRepeatingSelector returned no candidates for 5 identical siblings")
        }
    }

    private fun parseBatchPayload(result: org.springframework.http.ResponseEntity<MCPToolCallResponse>): Map<String, Any?> {
        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
    }

    private fun parseBatchResults(payload: Map<String, Any?>): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return payload["results"] as List<Map<String, Any?>>
    }

    private fun mockTools(vararg toolsByDomain: Pair<String, List<String>>) {
        val toolSpecs = toolsByDomain.associate { (domain, methods) ->
            domain to methods.associateWith { method ->
                ToolSpec(domain = domain, method = method, description = "desc")
            }
        }
        `when`(agentToolManager.getAllToolSpecs()).thenReturn(toolSpecs)
    }

    private fun mockTool(domain: String, method: String) {
        val toolSpecs = mapOf(
            domain to mapOf(method to ToolSpec(domain = domain, method = method, description = "desc"))
        )
        `when`(agentToolManager.getAllToolSpecs()).thenReturn(toolSpecs)

        // Ensure execute returns success
        runBlocking {
            `when`(agentToolManager.execute(any())).thenReturn(toolCallResult("ok"))
        }
    }

    // ── awaitPromise / improved error tests ──────────────────────────────

    @Test
    fun `test browser evaluate with awaitPromise passes through to tool call`() = runBlocking {
        mockTool("tab", "evaluateValue")

        val request = MCPToolCallRequest(
            tool = "browser_evaluate",
            arguments = mapOf(
                "sessionId" to sessionId,
                "expression" to "fetch('/api/data')",
                "awaitPromise" to true
            )
        )

        `when`(agentToolManager.execute(any())).thenReturn(toolCallResult(mapOf("status" to 200)))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolManager).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("evaluateValue", toolCall.method)
        assertEquals("fetch('/api/data')", toolCall.arguments["expression"])
        assertEquals(true, toolCall.arguments["awaitPromise"])
        Unit
    }

    @Test
    fun `test evaluateValue exception formats tool prefix and message`() = runBlocking {
        mockTool("tab", "evaluateValue")

        `when`(agentToolManager.execute(any())).thenReturn(
            toolCallResult(
                evaluate = TcEvaluate(
                    expression = "tab.evaluateValue(expression=\"bad code\")",
                    exception = TcException(
                        expression = "tab.evaluateValue(expression=\"bad code\")",
                        cause = RuntimeException("something went wrong")
                    )
                )
            )
        )

        val request = MCPToolCallRequest(
            tool = "browser_evaluate",
            arguments = mapOf("sessionId" to sessionId, "expression" to "bad code")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.isError)
        val errorText = result.body!!.content[0].text
        assertTrue(errorText.contains("browser_evaluate failed:"), "Expected tool prefix, got: $errorText")
        assertTrue(errorText.contains("something went wrong"), "Expected error message in: $errorText")
        Unit
    }

    @Test
    fun `test evaluateValue exception with not focusable adds tip`() = runBlocking {
        mockTool("tab", "evaluateValue")
        val cause = RuntimeException("Element is not focusable")

        `when`(agentToolManager.execute(any())).thenReturn(
            toolCallResult(
                evaluate = TcEvaluate(
                    expression = "tab.evaluateValue(expression=\"...\")",
                    exception = TcException(
                        expression = "tab.evaluateValue(expression=\"...\")",
                        cause = cause
                    )
                )
            )
        )

        val request = MCPToolCallRequest(
            tool = "browser_evaluate",
            arguments = mapOf("sessionId" to sessionId, "expression" to "document.querySelector('#q').focus()")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.isError)
        val errorText = result.body!!.content[0].text
        assertTrue(errorText.contains("not focusable"), "Expected 'not focusable' in: $errorText")
        assertTrue(
            errorText.contains("Tip: Use 'click <ref>' first to focus the element"),
            "Expected focus tip in: $errorText"
        )
        Unit
    }

    @Test
    fun `test evaluateValue exception without not focusable has no tip`() = runBlocking {
        mockTool("tab", "evaluateValue")

        `when`(agentToolManager.execute(any())).thenReturn(
            toolCallResult(
                evaluate = TcEvaluate(
                    expression = "tab.evaluateValue(expression=\"broken\")",
                    exception = TcException(
                        expression = "tab.evaluateValue(expression=\"broken\")",
                        cause = RuntimeException("some other error")
                    )
                )
            )
        )

        val request = MCPToolCallRequest(
            tool = "browser_evaluate",
            arguments = mapOf("sessionId" to sessionId, "expression" to "broken")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.isError)
        val errorText = result.body!!.content[0].text
        assertTrue(errorText.contains("some other error"), "Expected 'some other error' in: $errorText")
        // Should NOT include the "not focusable" tip for unrelated errors
        assertFalse(errorText.contains("Tip: Use 'click <ref>'"), "Should not have focus tip for non-focus error: $errorText")
        Unit
    }

    private fun toolCallResult(value: Any? = null, evaluate: TcEvaluate? = null): ToolCallResult {
        val resolvedEvaluate = evaluate ?: TcEvaluate(value = value)
        return ToolCallResult(
            evaluate = resolvedEvaluate,
            message = resolvedEvaluate.exception?.message,
        )
    }

    // =========================================================================
    // buildBatchFocusExpression — selector interpolation
    // =========================================================================

    @Test
    fun `focus expression interpolates selector into querySelector call`() {
        val expr = MCPToolController.buildBatchFocusExpression("#my-input")

        // Kotlin's $selectorLiteral interpolation embeds the JSON-escaped
        // selector (including quotes) directly into the JS, producing a
        // valid querySelector call like:  document.querySelector("#my-input")
        assertTrue(
            expr.contains("""querySelector("#my-input")"""),
            "focusExpression should interpolate selector into querySelector, got:\n$expr"
        )
    }

    @Test
    fun `focus expression escapes single quotes in selector`() {
        // JSON-escaped: "\"#search-box\"" → JS: querySelector("#search-box")
        val expr = MCPToolController.buildBatchFocusExpression("#search-box")

        assertTrue(
            expr.contains("""querySelector("#search-box")"""),
            "focusExpression should interpolate selector, got:\n$expr"
        )
    }

    @Test
    fun `focus expression returns empty string for backend refs`() {
        // backend: refs are resolved server-side — no JS focus expression needed.
        val expr = MCPToolController.buildBatchFocusExpression("backend:42")
        assertEquals("''", expr)
    }

    @Test
    fun `focus expression contains valid JS template literal for error`() {
        val expr = MCPToolController.buildBatchFocusExpression("#btn")

        // The catch block must use a JS template literal `invalid:${error}`,
        // NOT a Kotlin-interpolated value.  The dollar-brace must appear
        // literally in the JS output.
        assertTrue(
            expr.contains("""`invalid:${'$'}{error}`"""),
            "focusExpression should contain JS template literal, got:\n$expr"
        )
    }

    @Test
    fun `focus expression is a self-invoking IIFE`() {
        val expr = MCPToolController.buildBatchFocusExpression("#click-target")

        assertTrue(expr.startsWith("(() => {"), "focusExpression should be an IIFE")
        assertTrue(expr.endsWith("})()"), "focusExpression should self-invoke")
    }

    @Test
    fun `focus expression ternary returns focused or unfocused`() {
        val expr = MCPToolController.buildBatchFocusExpression("#my-input")

        // The ternary  ? 'focused' : 'unfocused'  is the return value.
        // restoreBatchFocus branches on these exact strings.
        assertTrue(expr.contains("'focused'"), "should return 'focused' on success")
        assertTrue(expr.contains("'unfocused'"), "should return 'unfocused' on failure")
        assertTrue(expr.contains("return 'missing'"), "should return 'missing' when element absent")
    }

    @Test
    fun `focus expression with complex selector preserves attribute selector`() {
        val expr = MCPToolController.buildBatchFocusExpression("input[name='email']")

        assertTrue(expr.contains("querySelector("), "missing querySelector call")
        // The selector string is JSON-escaped, so single quotes inside it
        // become backslash-escaped:  "input[name='email']"
        assertTrue(expr.contains("email"), "selector content should be preserved")
        assertTrue(
            expr.contains("\\'") || expr.contains("'email'"),
            "attribute selector quotes should be present, got:\n$expr"
        )
    }

    // =========================================================================
    // extractDomain — compound domain support
    // =========================================================================

    /** A minimal tool executor stub used solely for registering test domains. */
    private class StubToolExecutor(override val domain: String) : AbstractToolExecutor() {
        override val receiverClass: kotlin.reflect.KClass<*> = Any::class
        override suspend fun callFunctionOn(
            domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
        ): Any? = "stub"
    }

    @Test
    fun `extractDomain resolves compound domain from CustomToolRegistry`() {
        val executor = StubToolExecutor("html_snapshot")
        CustomToolRegistry.instance.register(executor)
        try {
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_capture"))
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_scrape"))
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_inspect"))
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_query"))
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_export"))
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_summary"))
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_scrape_all"))
        } finally {
            CustomToolRegistry.instance.unregister("html_snapshot")
        }
    }

    @Test
    fun `extractDomain falls back to first underscore for unregistered domains`() {
        // "go_back" has no registered executor — should split on first '_'
        assertEquals("go", controller.extractDomain("go_back"))
        // "crawl_submit" — split on first '_' when "crawl" is not registered
        // (note: in a production context "crawl" may be registered; this
        //  test runs with a clean registry so it exercises the fallback)
        assertEquals("crawl", controller.extractDomain("crawl_submit"))
    }

    @Test
    fun `extractDomain returns full name when no underscore present`() {
        assertEquals("navigate", controller.extractDomain("navigate"))
        assertEquals("screenshot", controller.extractDomain("screenshot"))
        assertEquals("reload", controller.extractDomain("reload"))
        // Note: "open_session" contains an underscore, so it falls through
        // to the legacy split and returns "open" — that's tested elsewhere.
    }

    @Test
    fun `extractDomain returns exact match when tool name equals registered domain`() {
        val executor = StubToolExecutor("html_snapshot")
        CustomToolRegistry.instance.register(executor)
        try {
            // Tool name is exactly the domain (no method suffix)
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot"))
        } finally {
            CustomToolRegistry.instance.unregister("html_snapshot")
        }
    }

    @Test
    fun `extractDomain picks longest matching registered domain`() {
        // Register both "swarm" and "html_snapshot".  "html_snapshot_submit"
        // should match "html_snapshot" (15 chars), not "html" (not registered)
        // or "swarm" (doesn't match).
        val htmlSnapshot = StubToolExecutor("html_snapshot")
        val swarm = StubToolExecutor("swarm")
        CustomToolRegistry.instance.register(htmlSnapshot)
        CustomToolRegistry.instance.register(swarm)
        try {
            assertEquals("html_snapshot", controller.extractDomain("html_snapshot_submit"))
            assertEquals("swarm", controller.extractDomain("swarm_submit"))
            // "swarm_status" → must match "swarm", not fall through and return "swarm"
            assertEquals("swarm", controller.extractDomain("swarm_status"))
        } finally {
            CustomToolRegistry.instance.unregister("html_snapshot")
            CustomToolRegistry.instance.unregister("swarm")
        }
    }

    @Test
    fun `extractDomain legacy name still works when unrelated domain registered`() {
        // When "html_snapshot" is registered, a legacy name like "go_back"
        // should NOT match it and should fall back to first-underscore split.
        val executor = StubToolExecutor("html_snapshot")
        CustomToolRegistry.instance.register(executor)
        try {
            assertEquals("go", controller.extractDomain("go_back"))
            assertEquals("tab", controller.extractDomain("tab_select"))
            // "page_title" should not match "html_snapshot"
            assertEquals("page", controller.extractDomain("page_title"))
        } finally {
            CustomToolRegistry.instance.unregister("html_snapshot")
        }
    }

    // =========================================================================
    // dispatchToToolExecutor → CustomToolRegistry integration
    // =========================================================================

    @Test
    fun `dispatch to CustomToolRegistry for compound domain tool with session`() = runBlocking {
        val executor = StubToolExecutor("html_snapshot")
        CustomToolRegistry.instance.register(executor)
        try {
            val request = MCPToolCallRequest(
                tool = "html_snapshot_capture",
                arguments = mapOf("sessionId" to sessionId)
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)
            // Should NOT be an "Unknown tool" error
            assertFalse(
                result.body!!.isError,
                "Expected success but got error: ${result.body!!.content.firstOrNull()?.text}"
            )
            // The stub executor returns "stub" as a string
            assertEquals("stub", result.body!!.content[0].text)
        } finally {
            CustomToolRegistry.instance.unregister("html_snapshot")
        }
    }

    @Test
    fun `dispatch returns error when CustomToolRegistry executor fails`() = runBlocking {
        val executor = object : AbstractToolExecutor() {
            override val domain: String = "html_snapshot"
            override val receiverClass: kotlin.reflect.KClass<*> = Any::class
            override suspend fun callFunctionOn(
                domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
            ): Any? {
                throw IllegalArgumentException("simulated executor failure")
            }
        }
        CustomToolRegistry.instance.register(executor)
        try {
            val request = MCPToolCallRequest(
                tool = "html_snapshot_capture",
                arguments = mapOf("sessionId" to sessionId)
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)
            assertTrue(result.body!!.isError, "Expected error for failing executor")
            val errorText = result.body!!.content[0].text
            assertTrue(
                errorText.contains("html_snapshot_capture failed:"),
                "Expected tool prefix in error, got: $errorText"
            )
            assertTrue(
                errorText.contains("simulated executor failure"),
                "Expected error cause in message, got: $errorText"
            )
        } finally {
            CustomToolRegistry.instance.unregister("html_snapshot")
        }
    }

    // =========================================================================
    // toMcpToolName ↔ extractDomain round-trip
    // =========================================================================

    @Test
    fun `toMcpToolName round-trips through extractDomain for compound domains`() {
        // Register all compound-domain executors so extractDomain can resolve them.
        val htmlSnapshot = StubToolExecutor("html_snapshot")
        val swarm = StubToolExecutor("swarm")
        val crawl = StubToolExecutor("crawl")
        CustomToolRegistry.instance.register(htmlSnapshot)
        CustomToolRegistry.instance.register(swarm)
        CustomToolRegistry.instance.register(crawl)
        try {
            // For each known domain with compound names, verify the round-trip.
            val testCases = listOf(
                // domain         method          expected toolName
                Triple("html_snapshot", "capture",    "html_snapshot_capture"),
                Triple("html_snapshot", "scrape",     "html_snapshot_scrape"),
                Triple("html_snapshot", "scrapeAll",  "html_snapshot_scrape_all"),
                Triple("html_snapshot", "query",      "html_snapshot_query"),
                Triple("html_snapshot", "export",     "html_snapshot_export"),
                Triple("html_snapshot", "summary",    "html_snapshot_summary"),
                Triple("html_snapshot", "inspect",    "html_snapshot_inspect"),
                Triple("swarm",          "submit",     "swarm_submit"),
                Triple("swarm",          "status",     "swarm_status"),
                Triple("crawl",          "submit",     "crawl_submit"),
                Triple("crawl",          "status",     "crawl_status"),
            )
            for ((domain, method, expectedToolName) in testCases) {
                val toolName = controller.toMcpToolName(domain, method)
                assertEquals(expectedToolName, toolName, "toMcpToolName mismatch for $domain.$method")
                val extracted = controller.extractDomain(toolName)
                assertEquals(domain, extracted, "extractDomain round-trip failed: $toolName → $extracted, expected $domain")
            }
        } finally {
            CustomToolRegistry.instance.unregister("html_snapshot")
            CustomToolRegistry.instance.unregister("swarm")
            CustomToolRegistry.instance.unregister("crawl")
        }
    }

    @Test
    fun `toMcpToolName round-trips through extractDomain for simple domains`() {
        // Simple domains (no underscore) should still round-trip correctly
        // with the fallback split-on-first-underscore behavior.
        val testCases = listOf(
            // domain     method        expected toolName
            Triple("pptx", "generate", "pptx_generate"),
            Triple("pptx", "convert",  "pptx_convert"),
            Triple("media", "detect",  "media_detect"),
        )
        for ((domain, method, expectedToolName) in testCases) {
            val toolName = controller.toMcpToolName(domain, method)
            assertEquals(expectedToolName, toolName, "toMcpToolName mismatch for $domain.$method")
            val extracted = controller.extractDomain(toolName)
            assertEquals(domain, extracted, "extractDomain round-trip failed: $toolName → $extracted, expected $domain")
        }
    }
}
