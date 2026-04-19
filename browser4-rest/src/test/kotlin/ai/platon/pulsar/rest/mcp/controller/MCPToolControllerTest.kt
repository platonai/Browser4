package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import ai.platon.pulsar.agentic.model.TcException
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.high.command.CommandService
import ai.platon.pulsar.agentic.tools.high.command.CommandStatus
import ai.platon.pulsar.rest.mcp.service.SessionManager
import ai.platon.pulsar.rest.mcp.service.SessionManager.ManagedSession
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.springframework.http.HttpStatus
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

class MCPToolControllerTest {
    private val objectMapper = jacksonObjectMapper()

    @Mock
    private lateinit var sessionManager: SessionManager

    @Mock
    private lateinit var commandService: CommandService

    @Mock
    private lateinit var commandAgenticSession: AgenticSession

    @Mock
    private lateinit var commandAgent: BasicBrowserAgent

    @Mock
    private lateinit var commandAgentToolExecutor: AgentToolExecutor

    @Mock
    private lateinit var response: HttpServletResponse

    @Mock
    private lateinit var managedSession: ManagedSession

    @Mock
    private lateinit var agenticSession: AgenticSession

    @Mock
    private lateinit var basicBrowserAgent: BasicBrowserAgent

    @Mock
    private lateinit var agentToolExecutor: AgentToolExecutor

    private lateinit var controller: MCPToolController

    private val sessionId = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        controller = MCPToolController(sessionManager, commandService)

        // Setup session structure
        `when`(sessionManager.getSession(sessionId)).thenReturn(managedSession)
        `when`(managedSession.agenticSession).thenReturn(agenticSession)
        `when`(agenticSession.companionAgent).thenReturn(basicBrowserAgent)
        `when`(basicBrowserAgent.toolExtractor).thenReturn(agentToolExecutor)
        `when`(commandService.session).thenReturn(commandAgenticSession)
        `when`(commandAgenticSession.companionAgent).thenReturn(commandAgent)
        `when`(commandAgent.toolExtractor).thenReturn(commandAgentToolExecutor)
    }

    private fun <T> any(): T {
        Mockito.any<T>()
        return null as T
    }

    private fun anyToolCall(): ToolCall {
        Mockito.any(ToolCall::class.java)
        return ToolCall("dummy", "dummy")
    }

    private fun capture(captor: ArgumentCaptor<ToolCall>): ToolCall {
        captor.capture()
        return ToolCall("dummy", "dummy")
    }

    @Test
    fun `test open session`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "open_session",
            arguments = mapOf("url" to "https://example.com")
        )

        val newSessionId = "new-session-id"
        val newManagedSession = Mockito.mock(ManagedSession::class.java)
        `when`(newManagedSession.sessionId).thenReturn(newSessionId)
        `when`(sessionManager.createSession(any())).thenReturn(newManagedSession)

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.content[0].text.contains(newSessionId))
        Mockito.verify(sessionManager).createSession(null)
        Unit
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
    fun `test list tools returns registered tools with supported aliases only`() {
        `when`(sessionManager.getAllSessions()).thenReturn(listOf(managedSession))
        `when`(agentToolExecutor.getAllToolSpecs()).thenReturn(
            mapOf(
                "tab" to mapOf(
                    "navigate" to ToolSpec(domain = "tab", method = "navigate", description = "desc"),
                    "title" to ToolSpec(domain = "tab", method = "title", description = "desc"),
                    "currentUrl" to ToolSpec(domain = "tab", method = "currentUrl", description = "desc"),
                    "keyDown" to ToolSpec(domain = "tab", method = "keyDown", description = "desc"),
                    "mouseMove" to ToolSpec(domain = "tab", method = "mouseMove", description = "desc"),
                    "click" to ToolSpec(domain = "tab", method = "click", description = "desc"),
                    "dblclick" to ToolSpec(domain = "tab", method = "dblclick", description = "desc"),
                    "dialog_accept" to ToolSpec(domain = "tab", method = "dialog_accept", description = "desc"),
                    "dialog_dismiss" to ToolSpec(domain = "tab", method = "dialog_dismiss", description = "desc"),
                ),
                "browser" to mapOf(
                    "switchTab" to ToolSpec(domain = "browser", method = "switchTab", description = "desc"),
                    "newTab" to ToolSpec(domain = "browser", method = "newTab", description = "desc"),
                    "closeTab" to ToolSpec(domain = "browser", method = "closeTab", description = "desc"),
                    "listTabs" to ToolSpec(domain = "browser", method = "listTabs", description = "desc"),
                ),
            )
        )

        val result = controller.listTools(response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val tools = ((result.body as Map<String, Any>)["tools"] as List<String>).toSet()

        assertTrue(tools.contains("open_session"))
        assertTrue(tools.contains("command_batch"))
        assertTrue(tools.contains("navigate"))
        assertTrue(tools.contains("browser_navigate"))
        assertTrue(tools.contains("browser_click"))
        assertTrue(tools.contains("browser_handle_dialog"))
        assertTrue(tools.contains("browser_keydown"))
        assertTrue(tools.contains("browser_mouse_move_xy"))
        assertTrue(tools.contains("browser_tabs"))
        assertTrue(tools.contains("page_title"))
        assertTrue(tools.contains("page_url"))
        assertTrue(tools.contains("keydown"))
        assertTrue(tools.contains("mousemove"))
        assertTrue(tools.contains("tab_select"))
        assertTrue(tools.contains("tab_new"))
        assertTrue(tools.contains("tab_close"))
        assertTrue(tools.contains("tab_list"))
        assertFalse(tools.contains("browser_file_upload"))
    }

    @Test
    fun `test list tools creates temporary session when no sessions are active`() {
        val temporarySession = Mockito.mock(ManagedSession::class.java)
        val temporaryAgenticSession = Mockito.mock(AgenticSession::class.java)
        val temporaryAgent = Mockito.mock(BasicBrowserAgent::class.java)
        val temporaryToolExecutor = Mockito.mock(AgentToolExecutor::class.java)

        `when`(sessionManager.getAllSessions()).thenReturn(emptyList())
        `when`(sessionManager.createSession(null)).thenReturn(temporarySession)
        `when`(temporarySession.sessionId).thenReturn("temporary-session")
        `when`(temporarySession.agenticSession).thenReturn(temporaryAgenticSession)
        `when`(temporaryAgenticSession.companionAgent).thenReturn(temporaryAgent)
        `when`(temporaryAgent.toolExtractor).thenReturn(temporaryToolExecutor)
        `when`(temporaryToolExecutor.getAllToolSpecs()).thenReturn(
            mapOf(
                "tab" to mapOf(
                    "navigate" to ToolSpec(domain = "tab", method = "navigate", description = "desc"),
                )
            )
        )

        val result = controller.listTools(response)

        assertEquals(HttpStatus.OK, result.statusCode)
        Mockito.verify(sessionManager).createSession(null)
        Mockito.verify(sessionManager).deleteSession("temporary-session")
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
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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
        Mockito.verify(agentToolExecutor).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("fill", toolCall.method)
        assertEquals("#input", toolCall.arguments["selector"])
        assertEquals("text", toolCall.arguments["text"])
    }

    @Test
    fun `test explicit mapping page_title`() = runBlocking {
        // page_title maps to driver.title explicitly in resolveToolCall
        val request = MCPToolCallRequest(
            tool = "page_title",
            arguments = mapOf("sessionId" to sessionId)
        )

        // Mock execute to return a value
        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("My Page Title"))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("My Page Title", result.body!!.content[0].text)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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

        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("Browser4 CLI Other Fixture"))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Browser4 CLI Other Fixture", result.body!!.content[0].text)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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

        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("other page"))

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("other page", result.body!!.content[0].text)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolExecutor).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("evaluateValue", toolCall.method)
        assertEquals("#page-marker", toolCall.arguments["selector"])
        assertEquals("(element) => element.textContent", toolCall.arguments["functionDeclaration"])
        assertTrue("expression" !in toolCall.arguments)
    }

    @Test
    fun `test frontend tab select maps to browser switchTab`() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "browser_tabs",
            arguments = mapOf("sessionId" to sessionId, "action" to "select", "index" to 1)
        )

        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("ok"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolExecutor).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("browser", toolCall.domain)
        assertEquals("switchTab", toolCall.method)
        assertEquals("1", toolCall.arguments["tabId"])
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
        Mockito.verify(agentToolExecutor).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("tab", toolCall.domain)
        assertEquals("drag", toolCall.method)
        assertEquals("#a", toolCall.arguments["sourceSelector"])
        assertEquals("#b", toolCall.arguments["targetSelector"])
    }

    @Test
    fun `test generic canonical commands`() = runBlocking {
        val commands = listOf(
            Triple("go_back", "tab", "go_back"),
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
            Mockito.reset(agentToolExecutor)
            mockTool(domain, method) // Re-apply stubbing

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode, "Failed for tool: $tool")

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolExecutor).execute(capture(captor))
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

            Mockito.reset(agentToolExecutor)
            mockTool("tab", method)

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode, "Failed for tool: $tool")

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolExecutor).execute(capture(captor))
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

        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("ok"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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

        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("[]"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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

        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("ok"))

        val result = controller.callTool(request, response)
        assertEquals(HttpStatus.OK, result.statusCode)

        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(agentToolExecutor).execute(capture(captor))
        val toolCall = captor.value

        assertEquals("browser", toolCall.domain)
        assertEquals("closeTab", toolCall.method)
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
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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
        Mockito.verify(agentToolExecutor).execute(capture(captor))
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
        `when`(agentToolExecutor.getAllToolSpecs()).thenReturn(emptyMap())

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.isError)
        assertTrue(result.body!!.content[0].text.contains("Unknown tool: unknown_tool"))
    }

    @Test
    fun testCommandRunAsync() = runBlocking {
        val taskId = "task-abc-123"
        `when`(commandAgentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult(taskId))

        val request = MCPToolCallRequest(
            tool = "command_run",
            arguments = mapOf("command" to "https://example.com", "async" to true)
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(taskId, result.body!!.content[0].text)
        Mockito.verify(commandAgentToolExecutor).registerCustomTarget("command", commandService)
        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(commandAgentToolExecutor).execute(capture(captor))
        assertEquals("command", captor.value.domain)
        assertEquals("run", captor.value.method)
        assertEquals("https://example.com", captor.value.arguments["command"])
        assertEquals(true, captor.value.arguments["async"])
        Unit
    }

    @Test
    fun testCommandRunAsyncIsDefault() = runBlocking {
        val taskId = "task-default-async"
        `when`(commandAgentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult(taskId))

        val request = MCPToolCallRequest(
            tool = "command_run",
            arguments = mapOf("command" to "do something")
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(taskId, result.body!!.content[0].text)
        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(commandAgentToolExecutor).execute(capture(captor))
        assertEquals("command", captor.value.domain)
        assertEquals("run", captor.value.method)
        assertEquals("do something", captor.value.arguments["command"])
        Unit
    }

    @Test
    fun testCommandRunSync() = runBlocking {
        val status = CommandStatus(id = "sync-id", processState = "done")
        `when`(commandAgentToolExecutor.execute(anyToolCall())).thenReturn(
            toolCallResult(objectMapper.writeValueAsString(status))
        )

        val request = MCPToolCallRequest(
            tool = "command_run",
            arguments = mapOf("command" to "do something", "async" to false)
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.content[0].text.contains("sync-id"))
        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(commandAgentToolExecutor).execute(capture(captor))
        assertEquals("command", captor.value.domain)
        assertEquals("run", captor.value.method)
        assertEquals(false, captor.value.arguments["async"])
        Unit
    }

    @Test
    fun testCommandBatchOpenAndTool() = runBlocking {
        `when`(managedSession.sessionId).thenReturn("batch-session")
        `when`(sessionManager.createSession(any())).thenReturn(managedSession)
        `when`(sessionManager.getSession("batch-session")).thenReturn(managedSession)
        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("Batch Title"))

        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "steps" to listOf(
                    mapOf("op" to "open"),
                    mapOf(
                        "op" to "tool",
                        "tool" to "page_title",
                        "arguments" to emptyMap<String, Any?>(),
                    ),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        assertEquals("batch-session", payload["sessionId"])
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(true, results[0]["ok"])
        assertEquals("Session opened: batch-session", results[0]["text"])
        assertEquals(true, results[1]["ok"])
        assertEquals("Batch Title", results[1]["text"])
    }

    // -----------------------------------------------------------------------
    // Batch execution — additional scenarios
    // -----------------------------------------------------------------------

    @Test
    fun testCommandBatchMissingStepsReturnsError() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf("bail" to true)
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.isError)
        assertTrue(
            result.body!!.content[0].text.contains("steps"),
            "Expected error about missing 'steps': ${result.body!!.content[0].text}"
        )
        Unit
    }

    @Test
    fun testCommandBatchEmptyStepsReturnsEmptyResults() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf("steps" to emptyList<Any>())
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertTrue(results.isEmpty(), "Expected empty results for empty steps")
        assertEquals(false, payload["stoppedOnError"])
        assertEquals(0, payload["failureCount"])
    }

    @Test
    fun testCommandBatchBailStopsOnFirstError() = runBlocking {
        `when`(managedSession.sessionId).thenReturn("bail-session")
        `when`(sessionManager.createSession(any())).thenReturn(managedSession)
        `when`(sessionManager.getSession("bail-session")).thenReturn(managedSession)
        `when`(agentToolExecutor.execute(anyToolCall())).thenThrow(
            RuntimeException("Tool execution failed")
        )

        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "bail" to true,
                "steps" to listOf(
                    mapOf("op" to "open"),
                    mapOf(
                        "op" to "tool",
                        "tool" to "page_title",
                        "arguments" to emptyMap<String, Any?>(),
                    ),
                    mapOf(
                        "op" to "tool",
                        "tool" to "page_url",
                        "arguments" to emptyMap<String, Any?>(),
                    ),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        assertEquals(true, payload["stoppedOnError"])
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        // Should have open (ok) + page_title (error), but NOT page_url
        assertEquals(2, results.size, "Expected bail to stop after first error, got: $results")
        assertEquals(true, results[0]["ok"])
        assertEquals(false, results[1]["ok"])
    }

    @Test
    fun testCommandBatchContinuesOnErrorWithoutBail() = runBlocking {
        `when`(managedSession.sessionId).thenReturn("continue-session")
        `when`(sessionManager.createSession(any())).thenReturn(managedSession)
        `when`(sessionManager.getSession("continue-session")).thenReturn(managedSession)
        `when`(agentToolExecutor.execute(anyToolCall()))
            .thenThrow(RuntimeException("First tool failed"))
            .thenReturn(toolCallResult("Second succeeded"))

        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "bail" to false,
                "steps" to listOf(
                    mapOf("op" to "open"),
                    mapOf(
                        "op" to "tool",
                        "tool" to "page_title",
                        "arguments" to emptyMap<String, Any?>(),
                    ),
                    mapOf(
                        "op" to "tool",
                        "tool" to "page_url",
                        "arguments" to emptyMap<String, Any?>(),
                    ),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        assertEquals(false, payload["stoppedOnError"])
        assertEquals(1, payload["failureCount"])
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(3, results.size, "Expected all 3 steps to execute without bail")
        assertEquals(true, results[0]["ok"])
        assertEquals(false, results[1]["ok"])
        assertEquals(true, results[2]["ok"])
    }

    @Test
    fun testCommandBatchOpenAndClose() = runBlocking {
        `when`(managedSession.sessionId).thenReturn("close-session")
        `when`(sessionManager.createSession(any())).thenReturn(managedSession)
        `when`(sessionManager.deleteSession("close-session")).thenReturn(true)

        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "steps" to listOf(
                    mapOf("op" to "open"),
                    mapOf("op" to "close"),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        // After close, sessionId should be null
        assertEquals(null, payload["sessionId"])
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(2, results.size)
        assertEquals(true, results[0]["ok"])
        assertTrue(results[0]["text"].toString().contains("Session opened"))
        assertEquals(true, results[1]["ok"])
        assertTrue(results[1]["text"].toString().contains("Session closed"))
    }

    @Test
    fun testCommandBatchToolWithoutSessionReturnsError() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "steps" to listOf(
                    mapOf(
                        "op" to "tool",
                        "tool" to "page_title",
                        "arguments" to emptyMap<String, Any?>(),
                    ),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(1, results.size)
        assertEquals(false, results[0]["ok"])
        assertTrue(
            results[0]["error"].toString().contains("No active session"),
            "Expected 'No active session' error: ${results[0]["error"]}"
        )
    }

    @Test
    fun testCommandBatchCloseWithoutSessionReturnsError() = runBlocking {
        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "steps" to listOf(
                    mapOf("op" to "close"),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(1, results.size)
        assertEquals(false, results[0]["ok"])
        assertTrue(
            results[0]["error"].toString().contains("No active session"),
            "Expected 'No active session' error: ${results[0]["error"]}"
        )
    }

    @Test
    fun testCommandBatchMultipleToolCalls() = runBlocking {
        `when`(managedSession.sessionId).thenReturn("multi-session")
        `when`(sessionManager.createSession(any())).thenReturn(managedSession)
        `when`(sessionManager.getSession("multi-session")).thenReturn(managedSession)
        `when`(agentToolExecutor.execute(anyToolCall()))
            .thenReturn(toolCallResult("Result 1"))
            .thenReturn(toolCallResult("Result 2"))
            .thenReturn(toolCallResult("Result 3"))

        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "steps" to listOf(
                    mapOf("op" to "open"),
                    mapOf("op" to "tool", "tool" to "page_title", "arguments" to emptyMap<String, Any?>()),
                    mapOf("op" to "tool", "tool" to "page_url", "arguments" to emptyMap<String, Any?>()),
                    mapOf("op" to "tool", "tool" to "page_title", "arguments" to emptyMap<String, Any?>()),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        assertEquals(0, payload["failureCount"])
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(4, results.size)
        assertTrue(results.all { it["ok"] == true }, "Expected all steps to succeed")
        assertEquals("Result 1", results[1]["text"])
        assertEquals("Result 2", results[2]["text"])
        assertEquals("Result 3", results[3]["text"])
    }

    @Test
    fun testCommandBatchWithExistingSessionId() = runBlocking {
        `when`(sessionManager.getSession("existing-session")).thenReturn(managedSession)
        `when`(managedSession.agenticSession).thenReturn(agenticSession)
        `when`(agenticSession.companionAgent).thenReturn(basicBrowserAgent)
        `when`(basicBrowserAgent.toolExtractor).thenReturn(agentToolExecutor)
        `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("Existing session result"))

        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "sessionId" to "existing-session",
                "steps" to listOf(
                    mapOf("op" to "tool", "tool" to "page_title", "arguments" to emptyMap<String, Any?>()),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        assertEquals("existing-session", payload["sessionId"])
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(1, results.size)
        assertEquals(true, results[0]["ok"])
        assertEquals("Existing session result", results[0]["text"])
    }

    @Test
    fun testCommandBatchToolStepMissingToolName() = runBlocking {
        `when`(managedSession.sessionId).thenReturn("missing-tool-session")
        `when`(sessionManager.createSession(any())).thenReturn(managedSession)
        `when`(sessionManager.getSession("missing-tool-session")).thenReturn(managedSession)

        val request = MCPToolCallRequest(
            tool = "command_batch",
            arguments = mapOf(
                "steps" to listOf(
                    mapOf("op" to "open"),
                    mapOf("op" to "tool", "arguments" to emptyMap<String, Any?>()),
                )
            )
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(result.body!!.content[0].text, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val results = payload["results"] as List<Map<String, Any?>>
        assertEquals(2, results.size)
        assertEquals(true, results[0]["ok"])
        assertEquals(false, results[1]["ok"])
        assertTrue(
            results[1]["error"].toString().contains("tool"),
            "Expected error about missing tool name: ${results[1]["error"]}"
        )
    }

    @Test
    fun testCommandStatus() = runBlocking {
        val taskId = "task-xyz"
        val status = CommandStatus(id = taskId, processState = "done")
        `when`(commandAgentToolExecutor.execute(anyToolCall())).thenReturn(
            toolCallResult(objectMapper.writeValueAsString(status))
        )

        val request = MCPToolCallRequest(
            tool = "command_status",
            arguments = mapOf("id" to taskId)
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.content[0].text.contains(taskId))
        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(commandAgentToolExecutor).execute(capture(captor))
        assertEquals("command", captor.value.domain)
        assertEquals("status", captor.value.method)
        assertEquals(taskId, captor.value.arguments["id"])
        Unit
    }

    @Test
    fun testCommandResult() = runBlocking {
        val taskId = "task-xyz"
        val commandResult = ai.platon.pulsar.agentic.tools.high.command.CommandResult(summary = "done")
        `when`(commandAgentToolExecutor.execute(anyToolCall())).thenReturn(
            toolCallResult(objectMapper.writeValueAsString(commandResult))
        )

        val request = MCPToolCallRequest(
            tool = "command_result",
            arguments = mapOf("id" to taskId)
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.content[0].text.contains("done"))
        val captor = ArgumentCaptor.forClass(ToolCall::class.java)
        Mockito.verify(commandAgentToolExecutor).execute(capture(captor))
        assertEquals("command", captor.value.domain)
        assertEquals("result", captor.value.method)
        assertEquals(taskId, captor.value.arguments["id"])
        Unit
    }

    @Test
    fun testCommandRunMissingCommandReturnsError() = runBlocking {
        `when`(commandAgentToolExecutor.execute(anyToolCall())).thenReturn(
            toolCallResult(
                evaluate = TcEvaluate(
                    expression = "command.run(async=\"true\")",
                    exception = TcException(
                        expression = "command.run(async=\"true\")",
                        cause = IllegalArgumentException("Missing required parameter 'command' for run")
                    )
                )
            )
        )

        val request = MCPToolCallRequest(
            tool = "command_run",
            arguments = mapOf("async" to true)
        )

        val result = controller.callTool(request, response)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertTrue(result.body!!.isError)
        assertTrue(result.body!!.content[0].text.contains("Missing required parameter 'command' for run"))
        Unit
    }

    private fun mockTool(domain: String, method: String) {
        val toolSpecs = mapOf(
            domain to mapOf(method to ToolSpec(domain = domain, method = method, description = "desc"))
        )
        `when`(agentToolExecutor.getAllToolSpecs()).thenReturn(toolSpecs)

        // Ensure execute returns success
        runBlocking {
            `when`(agentToolExecutor.execute(anyToolCall())).thenReturn(toolCallResult("ok"))
        }
    }

    private fun toolCallResult(value: Any? = null, evaluate: TcEvaluate? = null): ToolCallResult {
        val resolvedEvaluate = evaluate ?: TcEvaluate(value = value)
        return ToolCallResult(
            evaluate = resolvedEvaluate,
            message = resolvedEvaluate.exception?.message,
        )
    }
}
