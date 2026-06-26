package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests for [AgenticCliRunner] — validates CLI command parsing, MCP tool name
 * resolution, argument normalization, and AgentToolManager dispatch.
 */
@DisplayName("AgenticCliRunner")
class AgenticCliRunnerTest {

    private lateinit var toolManager: AgentToolManager
    private lateinit var runner: AgenticCliRunner

    companion object {
        @JvmStatic
        fun supportedCommandsProvider(): Stream<Arguments> = Stream.of(
            Arguments.of("goto", mapOf("url" to "https://example.com"), "browser_navigate"),
            Arguments.of("go-back", emptyMap<String, Any?>(), "browser_navigate_back"),
            Arguments.of("go-forward", emptyMap<String, Any?>(), "browser_navigate_forward"),
            Arguments.of("reload", emptyMap<String, Any?>(), "browser_reload"),
            Arguments.of("press", mapOf("key" to "Enter"), "browser_press_key"),
            Arguments.of("type", mapOf("text" to "hello"), "browser_press_sequentially"),
            Arguments.of("keydown", mapOf("key" to "a"), "browser_keydown"),
            Arguments.of("keyup", mapOf("key" to "a"), "browser_keyup"),
            Arguments.of("mousemove", mapOf("x" to 100, "y" to 200), "browser_mouse_move_xy"),
            Arguments.of("click", mapOf("ref" to "e15"), "browser_click"),
            Arguments.of("dblclick", mapOf("ref" to "e15"), "browser_click"),
            Arguments.of("drag", mapOf("startRef" to "e10", "endRef" to "e20"), "browser_drag"),
            Arguments.of("fill", mapOf("ref" to "e15", "text" to "hello"), "browser_type"),
            Arguments.of("hover", mapOf("ref" to "e15"), "browser_hover"),
            Arguments.of("select", mapOf("ref" to "e15", "val" to "opt1"), "browser_select_option"),
            Arguments.of("upload", mapOf("ref" to "e15", "file" to "/tmp/f.txt"), "browser_file_upload"),
            Arguments.of("check", mapOf("ref" to "e15"), "browser_check"),
            Arguments.of("uncheck", mapOf("ref" to "e15"), "browser_uncheck"),
            Arguments.of("snapshot", emptyMap<String, Any?>(), "browser_snapshot"),
            Arguments.of("screenshot", emptyMap<String, Any?>(), "browser_take_screenshot"),
            Arguments.of("eval", mapOf("expression" to "document.title"), "browser_evaluate"),
            Arguments.of("resize", mapOf("w" to 1280, "h" to 900), "browser_resize"),
            Arguments.of("dialog-accept", emptyMap<String, Any?>(), "browser_handle_dialog"),
            Arguments.of("dialog-dismiss", emptyMap<String, Any?>(), "browser_handle_dialog"),
            Arguments.of("tab-list", emptyMap<String, Any?>(), "browser_tabs"),
            Arguments.of("tab-new", emptyMap<String, Any?>(), "browser_tabs"),
            Arguments.of("tab-close", emptyMap<String, Any?>(), "browser_tabs"),
            Arguments.of("tab-select", mapOf("index" to 0), "browser_tabs"),
            Arguments.of("extract", mapOf("instruction" to "get info"), "agent_extract"),
            Arguments.of("summarize", mapOf("instruction" to "summarize"), "agent_summarize"),
            Arguments.of("agent-run", mapOf("task" to "do something"), "command_run"),
            Arguments.of("agent-status", mapOf("id" to "abc"), "command_status"),
            Arguments.of("agent-result", mapOf("id" to "abc"), "command_result"),
        )
    }

    @BeforeEach
    fun setUp() {
        // Create a minimal AgentToolManager with representative tool specs
        val tabExecutor: ToolExecutor = mockk(relaxed = true)
        every { tabExecutor.domain } returns "tab"
        every { tabExecutor.getToolSpecs() } returns mapOf(
            "navigate" to ToolSpec("tab", "navigate", listOf(ToolSpec.Arg("url", "String")), description = "Navigate"),
            "click" to ToolSpec("tab", "click", listOf(ToolSpec.Arg("selector", "String")), description = "Click"),
            "dblclick" to ToolSpec("tab", "dblclick", listOf(ToolSpec.Arg("selector", "String")), description = "Double-click"),
            "type" to ToolSpec("tab", "type", listOf(ToolSpec.Arg("text", "String")), description = "Type text"),
            "press" to ToolSpec("tab", "press", listOf(ToolSpec.Arg("key", "String")), description = "Press key"),
            "keyDown" to ToolSpec("tab", "keyDown", listOf(ToolSpec.Arg("key", "String")), description = "Key down"),
            "keyUp" to ToolSpec("tab", "keyUp", listOf(ToolSpec.Arg("key", "String")), description = "Key up"),
            "hover" to ToolSpec("tab", "hover", listOf(ToolSpec.Arg("selector", "String")), description = "Hover"),
            "fill" to ToolSpec("tab", "fill", listOf(ToolSpec.Arg("selector", "String"), ToolSpec.Arg("text", "String")), description = "Fill"),
            "selectOption" to ToolSpec("tab", "selectOption", listOf(ToolSpec.Arg("values", "List")), description = "Select option"),
            "check" to ToolSpec("tab", "check", listOf(ToolSpec.Arg("selector", "String")), description = "Check"),
            "uncheck" to ToolSpec("tab", "uncheck", listOf(ToolSpec.Arg("selector", "String")), description = "Uncheck"),
            "upload" to ToolSpec("tab", "upload", listOf(ToolSpec.Arg("paths", "List")), description = "Upload"),
            "drag" to ToolSpec("tab", "drag", listOf(ToolSpec.Arg("sourceSelector", "String"), ToolSpec.Arg("targetSelector", "String")), description = "Drag"),
            "ariaSnapshot" to ToolSpec("tab", "ariaSnapshot", emptyList(), description = "Snapshot"),
            "screenshot" to ToolSpec("tab", "screenshot", emptyList(), description = "Screenshot"),
            "evaluateValue" to ToolSpec("tab", "evaluateValue", listOf(ToolSpec.Arg("expression", "String")), description = "Evaluate"),
            "resize" to ToolSpec("tab", "resize", listOf(ToolSpec.Arg("width", "Int"), ToolSpec.Arg("height", "Int")), description = "Resize"),
            "mouseMove" to ToolSpec("tab", "mouseMove", listOf(ToolSpec.Arg("x", "Double"), ToolSpec.Arg("y", "Double")), description = "Mouse move"),
            "mouseDown" to ToolSpec("tab", "mouseDown", listOf(ToolSpec.Arg("button", "String")), description = "Mouse down"),
            "mouseUp" to ToolSpec("tab", "mouseUp", listOf(ToolSpec.Arg("button", "String")), description = "Mouse up"),
            "mouseWheel" to ToolSpec("tab", "mouseWheel", listOf(ToolSpec.Arg("deltaX", "Double"), ToolSpec.Arg("deltaY", "Double")), description = "Mouse wheel"),
            "goBack" to ToolSpec("tab", "goBack", emptyList(), description = "Go back"),
            "goForward" to ToolSpec("tab", "goForward", emptyList(), description = "Go forward"),
            "reload" to ToolSpec("tab", "reload", emptyList(), description = "Reload"),
            "dialogAccept" to ToolSpec("tab", "dialogAccept", listOf(ToolSpec.Arg("promptText", "String")), description = "Accept dialog"),
            "dialogDismiss" to ToolSpec("tab", "dialogDismiss", emptyList(), description = "Dismiss dialog"),
            "title" to ToolSpec("tab", "title", emptyList(), description = "Page title"),
            "currentUrl" to ToolSpec("tab", "currentUrl", emptyList(), description = "Page URL"),
        )

        val browserExecutor: ToolExecutor = mockk(relaxed = true)
        every { browserExecutor.domain } returns "browser"
        every { browserExecutor.getToolSpecs() } returns mapOf(
            "switchTab" to ToolSpec("browser", "switchTab", listOf(ToolSpec.Arg("tabId", "String")), description = "Switch tab"),
            "newTab" to ToolSpec("browser", "newTab", listOf(ToolSpec.Arg("url", "String")), description = "New tab"),
            "closeTab" to ToolSpec("browser", "closeTab", listOf(ToolSpec.Arg("tabId", "String")), description = "Close tab"),
            "listTabs" to ToolSpec("browser", "listTabs", emptyList(), description = "List tabs"),
        )

        val agentExecutor: ToolExecutor = mockk(relaxed = true)
        every { agentExecutor.domain } returns "agent"
        every { agentExecutor.getToolSpecs() } returns mapOf(
            "extract" to ToolSpec("agent", "extract", listOf(ToolSpec.Arg("instruction", "String")), description = "Extract"),
            "summarize" to ToolSpec("agent", "summarize", listOf(ToolSpec.Arg("instruction", "String")), description = "Summarize"),
        )

        toolManager = mockk(relaxed = true)
        every { toolManager.registeredExecutors } returns mapOf(
            "tab" to tabExecutor,
            "browser" to browserExecutor,
            "agent" to agentExecutor
        )
        every { toolManager.getAllToolSpecs() } returns mapOf(
            "tab" to tabExecutor.getToolSpecs(),
            "browser" to browserExecutor.getToolSpecs(),
            "agent" to agentExecutor.getToolSpecs()
        )

        runner = AgenticCliRunner(toolManager)
    }

    // =========================================================================
    // Command parsing
    // =========================================================================

    @Nested
    @DisplayName("parseCommand")
    inner class ParseCommandTests {

        @Test
        @DisplayName("parses simple command with no arguments")
        fun parsesSimpleCommand() {
            val (name, args) = runner.parseCommand("snapshot")
            assertEquals("snapshot", name)
            assertTrue(args.isEmpty())
        }

        @Test
        @DisplayName("parses command with positional arguments")
        fun parsesPositionalArgs() {
            val (name, args) = runner.parseCommand("goto https://example.com")
            assertEquals("goto", name)
            assertEquals("https://example.com", args["url"])
        }

        @Test
        @DisplayName("parses command with --key=value options")
        fun parsesKeyValueOptions() {
            val (name, args) = runner.parseCommand("snapshot --filename=out.txt")
            assertEquals("snapshot", name)
            assertEquals("out.txt", args["filename"])
        }

        @Test
        @DisplayName("parses boolean --flag options")
        fun parsesBooleanFlag() {
            val (name, args) = runner.parseCommand("type hello --submit")
            assertEquals("type", name)
            assertEquals("hello", args["text"])
            assertEquals(true, args["submit"])
        }

        @Test
        @DisplayName("parses quoted arguments")
        fun parsesQuotedArgs() {
            val (name, args) = runner.parseCommand("""eval "document.title" e15""")
            assertEquals("eval", name)
            assertEquals("document.title", args["expression"])
            assertEquals("e15", args["ref"])
        }

        @Test
        @DisplayName("parses single-quoted arguments")
        fun parsesSingleQuotedArgs() {
            val (name, args) = runner.parseCommand("""type 'hello world' '#search'""")
            assertEquals("type", name)
            assertEquals("hello world", args["text"])
            assertEquals("#search", args["ref"])
        }

        @Test
        @DisplayName("parses click with ref and button")
        fun parsesClickWithRefAndButton() {
            val (name, args) = runner.parseCommand("click e15 left")
            assertEquals("click", name)
            assertEquals("e15", args["ref"])
            assertEquals("left", args["button"])
        }

        @Test
        @DisplayName("parses click with only ref")
        fun parsesClickWithOnlyRef() {
            val (name, args) = runner.parseCommand("click e15")
            assertEquals("click", name)
            assertEquals("e15", args["ref"])
        }

        @Test
        @DisplayName("parses fill command")
        fun parsesFill() {
            val (name, args) = runner.parseCommand("fill e15 'hello world'")
            assertEquals("fill", name)
            assertEquals("e15", args["ref"])
            assertEquals("hello world", args["text"])
        }

        @Test
        @DisplayName("parses press command with key and ref")
        fun parsesPressWithKeyAndRef() {
            val (name, args) = runner.parseCommand("press Enter '#search'")
            assertEquals("press", name)
            // #search looks like a selector, so it becomes ref; Enter becomes key
            assertEquals("Enter", args["key"])
            assertEquals("#search", args["ref"])
        }

        @Test
        @DisplayName("parses press command - ref first, key second (legacy order)")
        fun parsesPressLegacyOrder() {
            val (name, args) = runner.parseCommand("press '#search' Enter")
            assertEquals("press", name)
            // #search looks like a selector, so it becomes ref; Enter becomes key
            assertEquals("Enter", args["key"])
            assertEquals("#search", args["ref"])
        }

        @Test
        @DisplayName("parses type command - text first, ref second")
        fun parsesTypeTextFirst() {
            val (name, args) = runner.parseCommand("type 'hello world' '#search'")
            assertEquals("type", name)
            assertEquals("hello world", args["text"])
            assertEquals("#search", args["ref"])
        }

        @Test
        @DisplayName("parses type command - ref first, text second (legacy)")
        fun parsesTypeLegacyOrder() {
            val (name, args) = runner.parseCommand("type '#search' 'hello world'")
            assertEquals("type", name)
            assertEquals("hello world", args["text"])
            assertEquals("#search", args["ref"])
        }

        @Test
        @DisplayName("parses drag command")
        fun parsesDrag() {
            val (name, args) = runner.parseCommand("drag e10 e20")
            assertEquals("drag", name)
            assertEquals("e10", args["startRef"])
            assertEquals("e20", args["endRef"])
        }

        @Test
        @DisplayName("parses mousemove command")
        fun parsesMousemove() {
            val (name, args) = runner.parseCommand("mousemove 100 200")
            assertEquals("mousemove", name)
            assertEquals(100, args["x"])
            assertEquals(200, args["y"])
        }

        @Test
        @DisplayName("parses resize command")
        fun parsesResize() {
            val (name, args) = runner.parseCommand("resize 1280 900")
            assertEquals("resize", name)
            assertEquals(1280, args["w"])
            assertEquals(900, args["h"])
        }

        @Test
        @DisplayName("parses agent-run command")
        fun parsesAgentRun() {
            val (name, args) = runner.parseCommand("agent-run 'go to amazon.com and search for laptops'")
            assertEquals("agent-run", name)
            assertEquals("go to amazon.com and search for laptops", args["task"])
        }

        @Test
        @DisplayName("parses agent-status command")
        fun parsesAgentStatus() {
            val (name, args) = runner.parseCommand("agent-status abc-123")
            assertEquals("agent-status", name)
            assertEquals("abc-123", args["id"])
        }
    }

    // =========================================================================
    // canHandle / supportedCommands
    // =========================================================================

    @Nested
    @DisplayName("canHandle and supportedCommands")
    inner class CanHandleTests {

        @Test
        @DisplayName("returns true for supported commands")
        fun returnsTrueForSupported() {
            assertTrue(runner.canHandle("goto"))
            assertTrue(runner.canHandle("click"))
            assertTrue(runner.canHandle("snapshot"))
            assertTrue(runner.canHandle("extract"))
            assertTrue(runner.canHandle("agent-run"))
        }

        @Test
        @DisplayName("returns false for unsupported commands")
        fun returnsFalseForUnsupported() {
            assertFalse(runner.canHandle("close"))
            assertFalse(runner.canHandle("list"))
            assertFalse(runner.canHandle("install"))
            assertFalse(runner.canHandle("status"))
            assertFalse(runner.canHandle("cookie-list"))
            assertFalse(runner.canHandle("swarm-create"))
            assertFalse(runner.canHandle("nonexistent"))
        }

        @Test
        @DisplayName("supportedCommands contains expected entries")
        fun supportedCommandsContainsExpected() {
            val commands = runner.supportedCommands()
            assertTrue(commands.contains("goto"))
            assertTrue(commands.contains("click"))
            assertTrue(commands.contains("dblclick"))
            assertTrue(commands.contains("type"))
            assertTrue(commands.contains("press"))
            assertTrue(commands.contains("snapshot"))
            assertTrue(commands.contains("screenshot"))
            assertTrue(commands.contains("extract"))
            assertTrue(commands.contains("summarize"))
            assertTrue(commands.contains("agent-run"))
            assertTrue(commands.contains("agent-status"))
            assertTrue(commands.contains("agent-result"))
            assertTrue(commands.contains("tab-list"))
            assertTrue(commands.contains("tab-new"))
            assertTrue(commands.contains("tab-close"))
            assertTrue(commands.contains("tab-select"))
            assertTrue(commands.contains("open"))
        }

        @Test
        @DisplayName("supportedCommands does NOT contain unsupported commands")
        fun supportedCommandsExcludesUnsupported() {
            val commands = runner.supportedCommands()
            assertFalse(commands.contains("close"))
            assertFalse(commands.contains("install"))
            assertFalse(commands.contains("batch"))
            assertFalse(commands.contains("cookie-list"))
            assertFalse(commands.contains("localstorage-set"))
            assertFalse(commands.contains("swarm-create"))
        }
    }

    // =========================================================================
    // Frontend tool call normalization
    // =========================================================================

    @Nested
    @DisplayName("normalizeFrontendToolCall")
    inner class NormalizeFrontendToolCallTests {

        @Test
        @DisplayName("resolves browser_tabs composite: list → tab_list")
        fun resolvesBrowserTabsList() {
            val result = runner.normalizeFrontendToolCall("browser_tabs", mapOf("action" to "list"))
            assertEquals("tab_list", result.tool)
            assertFalse(result.arguments.containsKey("action"))
        }

        @Test
        @DisplayName("resolves browser_tabs composite: new → tab_new")
        fun resolvesBrowserTabsNew() {
            val result = runner.normalizeFrontendToolCall("browser_tabs", mapOf("action" to "new", "url" to "https://example.com"))
            assertEquals("tab_new", result.tool)
            assertEquals("https://example.com", result.arguments["url"])
        }

        @Test
        @DisplayName("resolves browser_tabs composite: close → tab_close")
        fun resolvesBrowserTabsClose() {
            val result = runner.normalizeFrontendToolCall("browser_tabs", mapOf("action" to "close", "index" to 1))
            assertEquals("tab_close", result.tool)
            assertEquals(1, result.arguments["index"])
        }

        @Test
        @DisplayName("resolves browser_tabs composite: select → tab_select")
        fun resolvesBrowserTabsSelect() {
            val result = runner.normalizeFrontendToolCall("browser_tabs", mapOf("action" to "select", "index" to 0))
            assertEquals("tab_select", result.tool)
            assertEquals(0, result.arguments["index"])
        }

        @Test
        @DisplayName("resolves browser_handle_dialog: accept → dialog_accept")
        fun resolvesDialogAccept() {
            val result = runner.normalizeFrontendToolCall("browser_handle_dialog", mapOf("accept" to true))
            assertEquals("dialog_accept", result.tool)
            assertFalse(result.arguments.containsKey("accept"))
        }

        @Test
        @DisplayName("resolves browser_handle_dialog: dismiss → dialog_dismiss")
        fun resolvesDialogDismiss() {
            val result = runner.normalizeFrontendToolCall("browser_handle_dialog", mapOf("accept" to false))
            assertEquals("dialog_dismiss", result.tool)
            assertFalse(result.arguments.containsKey("accept"))
        }

        @Test
        @DisplayName("resolves browser_click without doubleClick → click")
        fun resolvesBrowserClick() {
            val result = runner.normalizeFrontendToolCall("browser_click", mapOf("ref" to "e15"))
            assertEquals("click", result.tool)
        }

        @Test
        @DisplayName("resolves browser_click with doubleClick=true → dblclick")
        fun resolvesBrowserDblClick() {
            val result = runner.normalizeFrontendToolCall("browser_click", mapOf("ref" to "e15", "doubleClick" to true))
            assertEquals("dblclick", result.tool)
            assertFalse(result.arguments.containsKey("doubleClick"))
        }

        @Test
        @DisplayName("applies frontend tool name aliases")
        fun appliesAliases() {
            // browser_navigate → navigate
            val result = runner.normalizeFrontendToolCall("browser_navigate", mapOf("url" to "https://example.com"))
            assertEquals("navigate", result.tool)
        }

        @Test
        @DisplayName("passes through unknown tool names unchanged")
        fun passesThroughUnknown() {
            val result = runner.normalizeFrontendToolCall("unknown_tool", mapOf("key" to "value"))
            assertEquals("unknown_tool", result.tool)
        }
    }

    // =========================================================================
    // Argument normalization
    // =========================================================================

    @Nested
    @DisplayName("normalizeToolArguments")
    inner class NormalizeToolArgumentsTests {

        @Test
        @DisplayName("converts snake_case keys to camelCase")
        fun convertsSnakeToCamel() {
            val result = runner.normalizeToolArguments("navigate", mapOf("full_page" to true, "start_url" to "https://example.com"))
            assertEquals(true, result["fullPage"])
            assertEquals("https://example.com", result["startUrl"])
            assertFalse(result.containsKey("full_page"))
            assertFalse(result.containsKey("start_url"))
        }

        @Test
        @DisplayName("maps ref to selector")
        fun mapsRefToSelector() {
            val result = runner.normalizeToolArguments("click", mapOf("ref" to "e15"))
            assertEquals("e15", result["selector"])
            assertFalse(result.containsKey("ref"))
        }

        @Test
        @DisplayName("does not override existing selector with ref")
        fun doesNotOverrideExistingSelector() {
            val result = runner.normalizeToolArguments("click", mapOf("ref" to "e15", "selector" to "e20"))
            assertEquals("e20", result["selector"])
        }

        @Test
        @DisplayName("maps startRef to sourceSelector")
        fun mapsStartRefToSourceSelector() {
            val result = runner.normalizeToolArguments("drag", mapOf("startRef" to "e10", "endRef" to "e20"))
            assertEquals("e10", result["sourceSelector"])
            assertEquals("e20", result["targetSelector"])
            assertFalse(result.containsKey("startRef"))
            assertFalse(result.containsKey("endRef"))
        }

        @Test
        @DisplayName("maps modifiers list to modifier string")
        fun mapsModifiersList() {
            val result = runner.normalizeToolArguments("click", mapOf("modifiers" to listOf("Shift")))
            assertEquals("Shift", result["modifier"])
            assertFalse(result.containsKey("modifiers"))
        }

        @Test
        @DisplayName("removes sessionId")
        fun removesSessionId() {
            val result = runner.normalizeToolArguments("click", mapOf("ref" to "e15", "sessionId" to "abc"))
            assertFalse(result.containsKey("sessionId"))
            assertEquals("e15", result["selector"])
        }

        @Test
        @DisplayName("tab tools: maps id to tabId")
        fun mapsIdToTabId() {
            val result = runner.normalizeToolArguments("tab_select", mapOf("id" to 1))
            assertEquals("1", result["tabId"])
            assertFalse(result.containsKey("id"))
        }

        @Test
        @DisplayName("select_option: maps value to values list")
        fun mapsValueToValuesList() {
            val result = runner.normalizeToolArguments("select_option", mapOf("ref" to "e15", "value" to "Option1"))
            assertEquals(listOf("Option1"), result["values"])
            assertFalse(result.containsKey("value"))
        }

        @Test
        @DisplayName("evaluate_value: promotes expression to functionDeclaration when selector present")
        fun promotesExpressionToFunctionDeclaration() {
            val result = runner.normalizeToolArguments(
                "evaluate_value",
                mapOf("expression" to "el => el.textContent", "selector" to "e15")
            )
            assertEquals("el => el.textContent", result["functionDeclaration"])
            assertFalse(result.containsKey("expression"))
        }
    }

    // =========================================================================
    // Tool call resolution
    // =========================================================================

    @Nested
    @DisplayName("resolveToolCall")
    inner class ResolveToolCallTests {

        @Test
        @DisplayName("resolves navigate → tab.navigate")
        fun resolvesNavigate() {
            val tc = runner.resolveToolCall("navigate", mapOf("url" to "https://example.com"))
            assertNotNull(tc)
            assertEquals("tab", tc!!.domain)
            assertEquals("navigate", tc.method)
            assertEquals("https://example.com", tc.arguments["url"])
        }

        @Test
        @DisplayName("resolves tab_list → browser.listTabs via legacy mapping")
        fun resolvesTabList() {
            val tc = runner.resolveToolCall("tab_list", emptyMap())
            assertNotNull(tc)
            assertEquals("browser", tc!!.domain)
            assertEquals("listTabs", tc.method)
        }

        @Test
        @DisplayName("resolves tab_new → browser.newTab via legacy mapping")
        fun resolvesTabNew() {
            val tc = runner.resolveToolCall("tab_new", mapOf("url" to "https://example.com"))
            assertNotNull(tc)
            assertEquals("browser", tc!!.domain)
            assertEquals("newTab", tc.method)
        }

        @Test
        @DisplayName("resolves keydown → tab.keyDown via legacy mapping")
        fun resolvesKeydown() {
            val tc = runner.resolveToolCall("keydown", mapOf("key" to "Enter"))
            assertNotNull(tc)
            assertEquals("tab", tc!!.domain)
            assertEquals("keyDown", tc.method)
        }

        @Test
        @DisplayName("resolves agent_extract → agent.extract via generic lookup")
        fun resolvesAgentExtract() {
            val tc = runner.resolveToolCall("agent_extract", mapOf("instruction" to "get product info"))
            assertNotNull(tc)
            assertEquals("agent", tc!!.domain)
            assertEquals("extract", tc.method)
        }

        @Test
        @DisplayName("returns null for unknown tool")
        fun returnsNullForUnknown() {
            val tc = runner.resolveToolCall("nonexistent_tool", emptyMap())
            assertNull(tc)
        }
    }

    // =========================================================================
    // execute flow
    // =========================================================================

    @Nested
    @DisplayName("execute")
    inner class ExecuteTests {

        @Test
        @DisplayName("executes a simple goto command")
        fun executesGoto() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult("navigated to https://example.com")

            val result = runner.execute("goto https://example.com")

            assertTrue(result.success)
            assertEquals("navigated to https://example.com", result.value)
            assertNotNull(result.toolCall)
            assertEquals("tab", result.toolCall!!.domain)
            assertEquals("navigate", result.toolCall!!.method)

            coVerify(exactly = 1) {
                toolManager.execute(match { tc ->
                    tc.domain == "tab" && tc.method == "navigate" &&
                        tc.arguments["url"] == "https://example.com"
                })
            }
        }

        @Test
        @DisplayName("executes a click command")
        fun executesClick() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult(null)

            val result = runner.execute("click e15")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("tab", result.toolCall!!.domain)
            assertEquals("click", result.toolCall!!.method)
            assertEquals("e15", result.toolCall!!.arguments["selector"])

            coVerify(exactly = 1) {
                toolManager.execute(match { tc ->
                    tc.domain == "tab" && tc.method == "click" &&
                        tc.arguments["selector"] == "e15"
                })
            }
        }

        @Test
        @DisplayName("executes a dblclick command")
        fun executesDblclick() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult(null)

            val result = runner.execute("dblclick e15")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("tab", result.toolCall!!.domain)
            assertEquals("dblclick", result.toolCall!!.method)
            assertEquals("e15", result.toolCall!!.arguments["selector"])
        }

        @Test
        @DisplayName("executes a type command")
        fun executesType() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult(null)

            val result = runner.execute("type 'hello world' '#search'")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("tab", result.toolCall!!.domain)
            assertEquals("type", result.toolCall!!.method)
            assertEquals("hello world", result.toolCall!!.arguments["text"])
            assertEquals("#search", result.toolCall!!.arguments["selector"])
        }

        @Test
        @DisplayName("executes a snapshot command")
        fun executesSnapshot() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult("<aria-snapshot>...</aria-snapshot>")

            val result = runner.execute("snapshot")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("tab", result.toolCall!!.domain)
            assertEquals("ariaSnapshot", result.toolCall!!.method)
        }

        @Test
        @DisplayName("executes a snapshot command with --boxes flag")
        fun executesSnapshotWithBoxes() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult("<aria-snapshot>...</aria-snapshot>")

            val result = runner.execute("snapshot --boxes")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("tab", result.toolCall!!.domain)
            assertEquals("ariaSnapshot", result.toolCall!!.method)
            assertEquals(true, result.toolCall!!.arguments["boxes"])
        }

        @Test
        @DisplayName("executes a tab-list command")
        fun executesTabList() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult("[tab1, tab2]")

            val result = runner.execute("tab-list")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("browser", result.toolCall!!.domain)
            assertEquals("listTabs", result.toolCall!!.method)
        }

        @Test
        @DisplayName("executes an extract command")
        fun executesExtract() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult("""{"name":"Product"}""")

            val result = runner.execute("extract 'product name and price'")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("agent", result.toolCall!!.domain)
            assertEquals("extract", result.toolCall!!.method)
            assertEquals("product name and price", result.toolCall!!.arguments["instruction"])
        }

        @Test
        @DisplayName("executes a go-back command")
        fun executesGoBack() = runBlocking {
            coEvery { toolManager.execute(any()) } returns toolCallResult(null)

            val result = runner.execute("go-back")

            assertTrue(result.success)
            assertNotNull(result.toolCall)
            assertEquals("tab", result.toolCall!!.domain)
            assertEquals("goBack", result.toolCall!!.method)
        }

        @Test
        @DisplayName("returns error for unsupported command")
        fun returnsErrorForUnsupported() = runBlocking {
            val result = runner.execute("close")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("Unknown or unsupported"))
        }

        @Test
        @DisplayName("returns ignored for open without URL")
        fun returnsIgnoredForOpenWithoutUrl() = runBlocking {
            val result = runner.execute("open")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("cannot be handled"))
        }

        @Test
        @DisplayName("returns error when AgentToolManager throws")
        fun returnsErrorOnManagerException() = runBlocking {
            coEvery { toolManager.execute(any()) } throws RuntimeException("driver crashed")

            val result = runner.execute("goto https://example.com")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("driver crashed"))
        }

        @Test
        @DisplayName("returns error when TcEvaluate has exception")
        fun returnsErrorOnEvaluateException() = runBlocking {
            val evaluate = TcEvaluate(
                expression = "tab.navigate(url=\"bad\")",
                cause = RuntimeException("navigation failed"),
            )
            coEvery { toolManager.execute(any()) } returns ToolCallResult(evaluate = evaluate)

            val result = runner.execute("goto https://bad.url")

            assertFalse(result.success)
            assertTrue(result.error!!.contains("navigation failed"))
        }
    }

    // =========================================================================
    // Parameterized: all supported commands resolve a tool name
    // =========================================================================

    @Nested
    @DisplayName("command → MCP tool name mapping")
    inner class CommandToolNameMappingTests {

        @ParameterizedTest
        @MethodSource("ai.platon.pulsar.agentic.tools.AgenticCliRunnerTest#supportedCommandsProvider")
        @DisplayName("command resolves to expected MCP tool name")
        fun resolvesToExpectedToolName(commandName: String, args: Map<String, Any?>, expectedToolName: String) = runBlocking {
            // Execute and verify the tool call is resolved (not ignored)
            coEvery { toolManager.execute(any()) } returns toolCallResult("ok")

            val result = runner.execute(commandName, args)

            // Should not be "ignored" or "unsupported"
            assertFalse(
                result.error?.contains("cannot be handled") == true ||
                    result.error?.contains("Unknown or unsupported") == true,
                "Command '$commandName' was unexpectedly ignored/unsupported: ${result.error}"
            )
            assertNotNull(result.toolCall, "Command '$commandName' did not produce a ToolCall")
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private fun toolCallResult(value: Any?): ToolCallResult {
        return ToolCallResult(evaluate = TcEvaluate(value = value))
    }
}
