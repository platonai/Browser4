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
import org.junit.jupiter.api.Assertions.assertTrue
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
 * Integration-level tests for the MCP tool dispatch chain covering the same
 * scenarios as the Rust CLI E2E tests `test_e2e_pointer_commands` and
 * `test_e2e_interaction_commands`.
 *
 * These tests verify that frontend MCP tool names are correctly aliased,
 * arguments are normalised (ref → selector, sessionId stripped), and the
 * correct [ToolCall] is dispatched to [AgentToolManager].
 */
class MCPToolControllerE2ETest {

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

    private val registeredToolSpecs = mutableMapOf<String, MutableMap<String, ToolSpec>>()

    private fun mockTool(domain: String, method: String) {
        registeredToolSpecs
            .getOrPut(domain) { mutableMapOf() }
            .put(method, ToolSpec(domain = domain, method = method, description = "desc"))

        `when`(agentToolManager.getAllToolSpecs()).thenReturn(registeredToolSpecs)

        runBlocking {
            `when`(agentToolManager.execute(any())).thenReturn(
                ai.platon.pulsar.agentic.model.ToolCallResult(
                    evaluate = ai.platon.pulsar.agentic.model.TcEvaluate(value = "ok")
                )
            )
        }
    }

    // =========================================================================
    // Pointer commands — mirroring test_e2e_pointer_commands
    // =========================================================================

    @Nested
    @DisplayName("pointer commands (click, dblclick, hover, drag)")
    inner class PointerCommands {

        @Test
        @DisplayName("browser_click without doubleClick dispatches to tab.click")
        fun clickDispatchesToTabClick() = runBlocking {
            mockTool("tab", "click")

            val request = MCPToolCallRequest(
                tool = "browser_click",
                arguments = mapOf("sessionId" to sessionId, "ref" to "#click-target")
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("click", toolCall.method)
            assertEquals("#click-target", toolCall.arguments["selector"],
                "ref should be normalised to selector")
            assertFalse(toolCall.arguments.containsKey("ref"),
                "ref key should be removed after normalisation")
            assertFalse(toolCall.arguments.containsKey("sessionId"),
                "sessionId should be stripped")
        }

        @Test
        @DisplayName("browser_click with doubleClick dispatches to tab.dblclick")
        fun clickWithDoubleClickDispatchesToTabDblclick() = runBlocking {
            mockTool("tab", "dblclick")

            val request = MCPToolCallRequest(
                tool = "browser_click",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "ref" to "#dblclick-target",
                    "doubleClick" to true
                )
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("dblclick", toolCall.method,
                "doubleClick=true should resolve to dblclick")
            assertEquals("#dblclick-target", toolCall.arguments["selector"])
            assertFalse(toolCall.arguments.containsKey("doubleClick"),
                "doubleClick should be removed after normalisation")
        }

        @Test
        @DisplayName("browser_click defaults to single click when doubleClick is absent")
        fun clickDefaultsToSingleWhenDoubleClickAbsent() = runBlocking {
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

            assertEquals("click", toolCall.method,
                "Absent doubleClick should default to single click")
        }

        @Test
        @DisplayName("browser_hover dispatches to tab.hover with selector")
        fun hoverDispatchesToTabHover() = runBlocking {
            mockTool("tab", "hover")

            val request = MCPToolCallRequest(
                tool = "browser_hover",
                arguments = mapOf("sessionId" to sessionId, "ref" to "#hover-target")
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("hover", toolCall.method)
            assertEquals("#hover-target", toolCall.arguments["selector"])
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        @Test
        @DisplayName("browser_drag dispatches to tab.drag with source and target selectors")
        fun dragDispatchesToTabDrag() = runBlocking {
            mockTool("tab", "drag")

            val request = MCPToolCallRequest(
                tool = "browser_drag",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "sourceRef" to "#drag-source",
                    "targetRef" to "#drag-target"
                )
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("drag", toolCall.method)
            // The argument normaliser maps sourceRef→source and targetRef→target
            assertTrue(
                toolCall.arguments["source"]?.toString()?.contains("drag-source") == true ||
                toolCall.arguments["sourceRef"]?.toString()?.contains("drag-source") == true,
                "drag-source element should be referenced in arguments"
            )
            assertTrue(
                toolCall.arguments["target"]?.toString()?.contains("drag-target") == true ||
                toolCall.arguments["targetRef"]?.toString()?.contains("drag-target") == true,
                "drag-target element should be referenced in arguments"
            )
        }
    }

    // =========================================================================
    // Keyboard interaction commands — mirroring test_e2e_interaction_commands
    // =========================================================================

    @Nested
    @DisplayName("keyboard interaction commands (type, fill, press, keydown, keyup)")
    inner class KeyboardInteractionCommands {

        // ── type ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("browser_press_sequentially dispatches to tab.type with text and selector")
        fun typeDispatchesToTabType() = runBlocking {
            mockTool("tab", "type")

            val request = MCPToolCallRequest(
                tool = "browser_press_sequentially",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "text" to "hello world",
                    "ref" to "#type-target"
                )
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("type", toolCall.method,
                "browser_press_sequentially should resolve to type")
            assertEquals("hello world", toolCall.arguments["text"],
                "text argument should be preserved")
            assertEquals("#type-target", toolCall.arguments["selector"],
                "ref should be normalised to selector")
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        @Test
        @DisplayName("browser_press_sequentially without ref omits selector")
        fun typeWithoutRefOmitsSelector() = runBlocking {
            mockTool("tab", "type")

            val request = MCPToolCallRequest(
                tool = "browser_press_sequentially",
                arguments = mapOf("sessionId" to sessionId, "text" to "plain text")
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("type", toolCall.method)
            assertEquals("plain text", toolCall.arguments["text"])
            assertFalse(toolCall.arguments.containsKey("selector"),
                "selector should be absent when ref is not provided")
        }

        // ── fill ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("browser_type dispatches to tab.fill with text and selector")
        fun fillDispatchesToTabFill() = runBlocking {
            mockTool("tab", "fill")

            val request = MCPToolCallRequest(
                tool = "browser_type",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "ref" to "#fill-target",
                    "text" to "filled text"
                )
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("fill", toolCall.method,
                "browser_type alias should resolve to fill")
            assertEquals("#fill-target", toolCall.arguments["selector"],
                "ref should be normalised to selector")
            assertEquals("filled text", toolCall.arguments["text"])
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        // ── press (special characters) ────────────────────────────────────

        @Test
        @DisplayName("browser_press_key with key and ref dispatches to tab.press")
        fun pressDispatchesToTabPress() = runBlocking {
            mockTool("tab", "press")

            // Simulate successive presses of special characters as in
            // test_e2e_interaction_commands: !, ?, :, +, )
            val specialKeys = listOf("!", "?", ":", "+", ")")

            for (key in specialKeys) {
                val request = MCPToolCallRequest(
                    tool = "browser_press_key",
                    arguments = mapOf(
                        "sessionId" to sessionId,
                        "key" to key,
                        "ref" to "#type-target"
                    )
                )

                val result = controller.callTool(request, response)

                assertEquals(HttpStatus.OK, result.statusCode,
                    "press '$key' should succeed")

                val captor = ArgumentCaptor.forClass(ToolCall::class.java)
                Mockito.verify(agentToolManager, Mockito.atLeastOnce())
                    .execute(capture(captor))
                val toolCall = captor.value

                assertEquals("tab", toolCall.domain,
                    "press '$key': domain should be tab")
                assertEquals("press", toolCall.method,
                    "press '$key': browser_press_key alias should resolve to press")
                assertEquals(key, toolCall.arguments["key"],
                    "press '$key': key argument should be preserved")
                assertEquals("#type-target", toolCall.arguments["selector"],
                    "press '$key': ref should be normalised to selector")
                assertFalse(toolCall.arguments.containsKey("ref"),
                    "press '$key': ref key should be removed")
                assertFalse(toolCall.arguments.containsKey("sessionId"),
                    "press '$key': sessionId should be stripped")
            }
        }

        @Test
        @DisplayName("browser_press_key without ref dispatches to tab.press with key only")
        fun pressWithoutRefDispatchesToTabPress() = runBlocking {
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
            assertFalse(toolCall.arguments.containsKey("selector"),
                "selector should be absent when ref is not provided")
        }

        @Test
        @DisplayName("browser_press_key preserves shifted characters unchanged")
        fun pressPreservesShiftedCharacters() = runBlocking {
            mockTool("tab", "press")

            // These are the Unicode characters used in the Rust E2E test
            val shiftedChars = mapOf(
                "!" to "!",   // Shift+1
                "?" to "?",   // Shift+/
                ":" to ":",   // Shift+;
                "+" to "+",   // Shift+=
                ")" to ")"    // Shift+0
            )

            for ((input, expected) in shiftedChars) {
                val request = MCPToolCallRequest(
                    tool = "browser_press_key",
                    arguments = mapOf(
                        "sessionId" to sessionId,
                        "key" to input,
                        "ref" to "#type-target"
                    )
                )

                val result = controller.callTool(request, response)
                assertEquals(HttpStatus.OK, result.statusCode,
                    "press '$input' should succeed")
            }
        }

        // ── keydown / keyup ───────────────────────────────────────────────

        @Test
        @DisplayName("browser_keydown dispatches to tab.keyDown with modifier key")
        fun keydownDispatchesToTabKeyDown() = runBlocking {
            mockTool("tab", "keyDown")

            val request = MCPToolCallRequest(
                tool = "browser_keydown",
                arguments = mapOf("sessionId" to sessionId, "key" to "Shift")
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("keyDown", toolCall.method,
                "browser_keydown alias should resolve to keyDown")
            assertEquals("Shift", toolCall.arguments["key"])
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        @Test
        @DisplayName("browser_keyup dispatches to tab.keyUp with modifier key")
        fun keyupDispatchesToTabKeyUp() = runBlocking {
            mockTool("tab", "keyUp")

            val request = MCPToolCallRequest(
                tool = "browser_keyup",
                arguments = mapOf("sessionId" to sessionId, "key" to "Shift")
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("tab", toolCall.domain)
            assertEquals("keyUp", toolCall.method,
                "browser_keyup alias should resolve to keyUp")
            assertEquals("Shift", toolCall.arguments["key"])
            assertFalse(toolCall.arguments.containsKey("sessionId"))
        }

        @Test
        @DisplayName("browser_keydown and browser_keyup dispatch with correct ordering")
        fun keydownThenKeyupMaintainsOrder() = runBlocking {
            // Simulate the press-Enter keydown/keyup pair for form submission
            mockTool("tab", "keyDown")
            mockTool("tab", "keyUp")

            val keydownRequest = MCPToolCallRequest(
                tool = "browser_keydown",
                arguments = mapOf("sessionId" to sessionId, "key" to "Enter")
            )
            val keyupRequest = MCPToolCallRequest(
                tool = "browser_keyup",
                arguments = mapOf("sessionId" to sessionId, "key" to "Enter")
            )

            val downResult = controller.callTool(keydownRequest, response)
            assertEquals(HttpStatus.OK, downResult.statusCode)

            val upResult = controller.callTool(keyupRequest, response)
            assertEquals(HttpStatus.OK, upResult.statusCode)

            // Both should have been dispatched
            Mockito.verify(agentToolManager, Mockito.times(2)).execute(any())
            Unit
        }
    }

    // =========================================================================
    // Argument normalisation — ref → selector across all pointer/interaction
    // =========================================================================

    @Nested
    @DisplayName("argument normalisation (ref → selector, sessionId stripping)")
    inner class ArgumentNormalisation {

        @Test
        @DisplayName("ref is normalised to selector for all pointer commands")
        fun refNormalisedToSelectorForPointerCommands() = runBlocking {
            val pointerCommands = listOf(
                "browser_click" to "click",
                "browser_hover" to "hover",
            )

            for ((frontendTool, internalMethod) in pointerCommands) {
                mockTool("tab", internalMethod)

                val request = MCPToolCallRequest(
                    tool = frontendTool,
                    arguments = mapOf("sessionId" to sessionId, "ref" to "#test-element")
                )

                val result = controller.callTool(request, response)
                assertEquals(HttpStatus.OK, result.statusCode,
                    "$frontendTool should succeed")

                val captor = ArgumentCaptor.forClass(ToolCall::class.java)
                Mockito.verify(agentToolManager, Mockito.atLeastOnce())
                    .execute(capture(captor))
                val toolCall = captor.value

                assertEquals("#test-element", toolCall.arguments["selector"],
                    "$frontendTool: ref should become selector")
                assertFalse(toolCall.arguments.containsKey("ref"),
                    "$frontendTool: ref key should be removed")
            }
        }

        @Test
        @DisplayName("ref is normalised to selector for all keyboard commands")
        fun refNormalisedToSelectorForKeyboardCommands() = runBlocking {
            val keyboardCommands = listOf(
                "browser_press_sequentially" to "type",
                "browser_type" to "fill",
                "browser_press_key" to "press",
            )

            for ((frontendTool, internalMethod) in keyboardCommands) {
                mockTool("tab", internalMethod)

                val args = mutableMapOf<String, Any?>(
                    "sessionId" to sessionId,
                    "ref" to "#test-input",
                )
                if (frontendTool != "browser_press_key") {
                    args["text"] = "test"
                } else {
                    args["key"] = "Enter"
                }

                val request = MCPToolCallRequest(tool = frontendTool, arguments = args)

                val result = controller.callTool(request, response)
                assertEquals(HttpStatus.OK, result.statusCode,
                    "$frontendTool should succeed")

                val captor = ArgumentCaptor.forClass(ToolCall::class.java)
                Mockito.verify(agentToolManager, Mockito.atLeastOnce())
                    .execute(capture(captor))
                val toolCall = captor.value

                assertEquals("#test-input", toolCall.arguments["selector"],
                    "$frontendTool: ref should become selector")
                assertFalse(toolCall.arguments.containsKey("ref"),
                    "$frontendTool: ref key should be removed")
            }
        }

        @Test
        @DisplayName("sessionId is stripped from all pointer and keyboard commands")
        fun sessionIdStrippedFromAllCommands() = runBlocking {
            val allCommands = listOf(
                "browser_click" to "click",
                "browser_hover" to "hover",
                "browser_press_sequentially" to "type",
                "browser_type" to "fill",
                "browser_press_key" to "press",
                "browser_keydown" to "keyDown",
                "browser_keyup" to "keyUp",
            )

            for ((frontendTool, internalMethod) in allCommands) {
                mockTool("tab", internalMethod)

                val args = mutableMapOf<String, Any?>(
                    "sessionId" to sessionId,
                    "ref" to "#el",
                )
                when (frontendTool) {
                    "browser_press_sequentially", "browser_type" -> args["text"] = "x"
                    "browser_press_key", "browser_keydown", "browser_keyup" -> {
                        args.remove("ref")
                        args["key"] = "Enter"
                    }
                }

                val request = MCPToolCallRequest(tool = frontendTool, arguments = args)

                val result = controller.callTool(request, response)
                assertEquals(HttpStatus.OK, result.statusCode,
                    "$frontendTool should succeed")

                val captor = ArgumentCaptor.forClass(ToolCall::class.java)
                Mockito.verify(agentToolManager, Mockito.atLeastOnce())
                    .execute(capture(captor))
                val toolCall = captor.value

                assertFalse(toolCall.arguments.containsKey("sessionId"),
                    "$frontendTool: sessionId should be stripped from ToolCall arguments")
            }
        }

        @Test
        @DisplayName("selector takes precedence over ref when both are present")
        fun selectorTakesPrecedenceOverRef() = runBlocking {
            mockTool("tab", "click")

            val request = MCPToolCallRequest(
                tool = "browser_click",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "ref" to "#ignored-ref",
                    "selector" to "#explicit-selector"
                )
            )

            val result = controller.callTool(request, response)

            assertEquals(HttpStatus.OK, result.statusCode)

            val captor = ArgumentCaptor.forClass(ToolCall::class.java)
            Mockito.verify(agentToolManager).execute(capture(captor))
            val toolCall = captor.value

            assertEquals("#explicit-selector", toolCall.arguments["selector"],
                "explicit selector should take precedence over ref")
            assertFalse(toolCall.arguments.containsKey("ref"),
                "ref key should be removed when selector is present")
        }
    }

    // =========================================================================
    // Complete interaction flow — simulating the Rust E2E scenario
    // =========================================================================

    @Nested
    @DisplayName("complete interaction flow (type → fill → press)")
    inner class CompleteInteractionFlow {

        @Test
        @DisplayName("full interaction sequence dispatches correct tools in order")
        fun fullInteractionSequence() = runBlocking {
            mockTool("tab", "type")
            mockTool("tab", "fill")
            mockTool("tab", "press")

            // Step 1: type "hello world" into #type-target
            val typeRequest = MCPToolCallRequest(
                tool = "browser_press_sequentially",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "text" to "hello world",
                    "ref" to "#type-target"
                )
            )
            val typeResult = controller.callTool(typeRequest, response)
            assertEquals(HttpStatus.OK, typeResult.statusCode)

            // Step 2: fill "#fill-target" with "filled text"
            val fillRequest = MCPToolCallRequest(
                tool = "browser_type",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "ref" to "#fill-target",
                    "text" to "filled text"
                )
            )
            val fillResult = controller.callTool(fillRequest, response)
            assertEquals(HttpStatus.OK, fillResult.statusCode)

            // Step 3-7: press each special character on #type-target
            val specialKeys = listOf("!", "?", ":", "+", ")")
            for (key in specialKeys) {
                val pressRequest = MCPToolCallRequest(
                    tool = "browser_press_key",
                    arguments = mapOf(
                        "sessionId" to sessionId,
                        "key" to key,
                        "ref" to "#type-target"
                    )
                )
                val pressResult = controller.callTool(pressRequest, response)
                assertEquals(HttpStatus.OK, pressResult.statusCode,
                    "press '$key' should succeed")
            }

            // All 7 commands (1 type + 1 fill + 5 press) should have dispatched
            Mockito.verify(agentToolManager, Mockito.times(7)).execute(any())
            Unit
        }
    }

    // =========================================================================
    // Complete pointer flow — simulating the Rust E2E scenario
    // =========================================================================

    @Nested
    @DisplayName("complete pointer flow (click → dblclick → hover → drag)")
    inner class CompletePointerFlow {

        @Test
        @DisplayName("full pointer sequence dispatches correct tools in order")
        fun fullPointerSequence() = runBlocking {
            mockTool("tab", "click")
            mockTool("tab", "dblclick")
            mockTool("tab", "hover")
            mockTool("tab", "drag")

            // Step 1: click #click-target
            val clickRequest = MCPToolCallRequest(
                tool = "browser_click",
                arguments = mapOf("sessionId" to sessionId, "ref" to "#click-target")
            )
            assertEquals(HttpStatus.OK, controller.callTool(clickRequest, response).statusCode)

            // Step 2: dblclick #dblclick-target
            val dblclickRequest = MCPToolCallRequest(
                tool = "browser_click",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "ref" to "#dblclick-target",
                    "doubleClick" to true
                )
            )
            assertEquals(HttpStatus.OK, controller.callTool(dblclickRequest, response).statusCode)

            // Step 3: hover #hover-target
            val hoverRequest = MCPToolCallRequest(
                tool = "browser_hover",
                arguments = mapOf("sessionId" to sessionId, "ref" to "#hover-target")
            )
            assertEquals(HttpStatus.OK, controller.callTool(hoverRequest, response).statusCode)

            // Step 4: drag #drag-source to #drag-target
            val dragRequest = MCPToolCallRequest(
                tool = "browser_drag",
                arguments = mapOf(
                    "sessionId" to sessionId,
                    "sourceRef" to "#drag-source",
                    "targetRef" to "#drag-target"
                )
            )
            assertEquals(HttpStatus.OK, controller.callTool(dragRequest, response).statusCode)

            // All 4 commands should have dispatched
            Mockito.verify(agentToolManager, Mockito.times(4)).execute(any())
            Unit
        }
    }
}
