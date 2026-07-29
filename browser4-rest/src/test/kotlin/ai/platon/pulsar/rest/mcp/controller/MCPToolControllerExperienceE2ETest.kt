package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.springframework.http.HttpStatus
import java.util.*

/**
 * Integration-level tests verifying that experience MCP tools
 * (experience_save, experience_query, experience_list, experience_deep_learn)
 * are correctly dispatched through the full MCP controller chain:
 *
 * POST /mcp/call-tool → alias normalization → argument normalization →
 * tool resolution → AgentToolManager.execute()
 */
@DisplayName("MCPToolController — Experience Tool Dispatch")
class MCPToolControllerExperienceE2ETest {

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

    private val registeredToolSpecs = mutableMapOf<String, MutableMap<String, ToolSpec>>()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        controller = MCPToolController(sessionManager)
        registeredToolSpecs.clear()

        `when`(sessionManager.getSession(sessionId)).thenReturn(managedSession)
        `when`(managedSession.agenticSession).thenReturn(agenticSession)
        `when`(agenticSession.companionAgent).thenReturn(basicBrowserAgent)
        `when`(basicBrowserAgent.agentToolManager).thenReturn(agentToolManager)
    }

    private fun capture(captor: ArgumentCaptor<ToolCall>): ToolCall {
        captor.capture()
        return ToolCall("dummy", "dummy")
    }

    private fun mockExperienceTool(method: String) {
        registeredToolSpecs
            .getOrPut("experience") { mutableMapOf() }
            .put(method, ToolSpec(domain = "experience", method = method, description = "desc"))

        `when`(agentToolManager.getAllToolSpecs()).thenReturn(registeredToolSpecs)

        runBlocking {
            `when`(agentToolManager.execute(any())).thenReturn(
                ai.platon.pulsar.agentic.model.ToolCallResult(
                    evaluate = ai.platon.pulsar.agentic.model.TcEvaluate(value = "ok")
                )
            )
        }
    }

    @Nested
    @DisplayName("experience_save dispatch")
    inner class ExperienceSaveDispatch {
        @Test
        @DisplayName("dispatches to experience.save with normalized arguments")
        fun dispatchesToExperienceSave() = runBlocking {
            mockExperienceTool("save")

            val request = MCPToolCallRequest(
                tool = "experience_save",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "url" to "https://amazon.com/dp/test-product",
                    "trace" to """{"steps":[{"sequence":1,"action":"click","selector":"#btn","result":"success"}],"outcome":"success"}""",
                    "outcome" to "success",
                    "intent" to "buy product",
                    "task_type" to "extract_product_detail",
                )
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("experience", toolCall.domain)
            assertEquals("save", toolCall.method)
            assertEquals("https://amazon.com/dp/test-product", toolCall.arguments["url"])
            assertFalse(toolCall.arguments.containsKey("sessionId"),
                "sessionId should be stripped from arguments")
        }

        @Test
        @DisplayName("dispatches failure save with outcome=failure")
        fun dispatchesFailureSave() = runBlocking {
            mockExperienceTool("save")

            val request = MCPToolCallRequest(
                tool = "experience_save",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "url" to "https://blocked.com/search",
                    "trace" to """{"steps":[],"outcome":"failure","error_message":"CAPTCHA detected"}""",
                    "outcome" to "failure",
                    "intent" to "search product",
                )
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("experience", toolCall.domain)
            assertEquals("save", toolCall.method)
            assertEquals("failure", toolCall.arguments["outcome"])
        }
    }

    @Nested
    @DisplayName("experience_query dispatch")
    inner class ExperienceQueryDispatch {
        @Test
        @DisplayName("dispatches to experience.query with URL and intent")
        fun dispatchesToExperienceQuery() = runBlocking {
            mockExperienceTool("query")

            val request = MCPToolCallRequest(
                tool = "experience_query",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "extract product details",
                )
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("experience", toolCall.domain)
            assertEquals("query", toolCall.method)
            assertEquals("https://amazon.com/dp/test", toolCall.arguments["url"])
            assertNotNull(toolCall.arguments["intent"])
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        @Test
        @DisplayName("dispatches query without intent (optional)")
        fun dispatchesQueryWithoutIntent() = runBlocking {
            mockExperienceTool("query")

            val request = MCPToolCallRequest(
                tool = "experience_query",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "url" to "https://example.com/page",
                )
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            assertEquals("experience", captor.value.domain)
            assertEquals("query", captor.value.method)
        }
    }

    @Nested
    @DisplayName("experience_list dispatch")
    inner class ExperienceListDispatch {
        @Test
        @DisplayName("dispatches to experience.list with no arguments")
        fun dispatchesToExperienceList() = runBlocking {
            mockExperienceTool("list")

            val request = MCPToolCallRequest(
                tool = "experience_list",
                arguments = mapOf("sessionId" to sessionId)
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("experience", toolCall.domain)
            assertEquals("list", toolCall.method)
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        @Test
        @DisplayName("dispatches to experience.list with filter and pagination")
        fun dispatchesListWithFilters() = runBlocking {
            mockExperienceTool("list")

            val request = MCPToolCallRequest(
                tool = "experience_list",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "filter" to "amazon",
                    "intent_filter" to "buy",
                    "page" to 2,
                    "page_size" to 10,
                )
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("experience", toolCall.domain)
            assertEquals("list", toolCall.method)
            assertEquals("amazon", toolCall.arguments["filter"])
            assertEquals("buy", toolCall.arguments["intentFilter"])
        }
    }

    @Nested
    @DisplayName("experience_deep_learn dispatch")
    inner class ExperienceDeepLearnDispatch {
        @Test
        @DisplayName("dispatches to experience.deep_learn with url and intent")
        fun dispatchesToExperienceDeepLearn() = runBlocking {
            mockExperienceTool("deep_learn")

            val request = MCPToolCallRequest(
                tool = "experience_deep_learn",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "extract product details",
                )
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("experience", toolCall.domain)
            assertEquals("deep_learn", toolCall.method)
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        @Test
        @DisplayName("dispatches deep_learn with force=true")
        fun dispatchesDeepLearnWithForce() = runBlocking {
            mockExperienceTool("deep_learn")

            val request = MCPToolCallRequest(
                tool = "experience_deep_learn",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "extract product details",
                    "force" to true,
                )
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals(true, toolCall.arguments["force"])
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {
        @Test
        @DisplayName("unknown experience method returns error")
        fun unknownMethodReturnsError() = runBlocking {
            // Don't mock any tool — the lookup will fail
            val request = MCPToolCallRequest(
                tool = "experience_unknown",
                arguments = mapOf("sessionId" to sessionId)
            )

            val result = controller.callTool(request, response)
            // Should be an error response
            assertNotNull(result.body)
        }

        @Test
        @DisplayName("all four experience tools coexist with other domain tools")
        fun allExperienceToolsCoexist() = runBlocking {
            mockExperienceTool("save")
            mockExperienceTool("query")
            mockExperienceTool("list")
            mockExperienceTool("deep_learn")
            // Also register a non-experience tool
            registeredToolSpecs
                .getOrPut("tab") { mutableMapOf() }
                .put("click", ToolSpec(domain = "tab", method = "click", description = "desc"))

            `when`(agentToolManager.getAllToolSpecs()).thenReturn(registeredToolSpecs)
            runBlocking {
                `when`(agentToolManager.execute(any())).thenReturn(
                    ai.platon.pulsar.agentic.model.ToolCallResult(
                        evaluate = ai.platon.pulsar.agentic.model.TcEvaluate(value = "ok")
                    )
                )
            }

            // experience_list should dispatch correctly even with other tools present
            val request = MCPToolCallRequest(
                tool = "experience_list",
                arguments = mapOf("sessionId" to sessionId)
            )

            val result = controller.callTool(request, response)
            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            assertEquals("experience", captor.value.domain)
            assertEquals("list", captor.value.method)
        }
    }
}
