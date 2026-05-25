package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.rest.api.TestHelper.MOCK_PRODUCT_DETAIL_URL
import ai.platon.pulsar.rest.api.service.SessionManager
import ai.platon.pulsar.rest.mcp.controller.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.MCPToolController
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scenario-level E2E tests for [MCPToolController] that mirror the browser
 * behaviors covered by `cli/browser4-cli/tests/e2e.rs`.
 */
@Tag("E2ETest")
class MCPToolControllerE2ETest : RestAPITestBase() {
    companion object {
        const val OPEN_PROFILE_MODE = "SEQUENTIAL"
    }

    private val logger = LoggerFactory.getLogger(MCPToolControllerE2ETest::class.java)
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)

    @Autowired
    private lateinit var conf: ImmutableConfig

    @Autowired
    lateinit var sessionManager: SessionManager

    private lateinit var fixtureServer: FixtureServer
    private lateinit var tempDir: Path
    private lateinit var uploadFile: Path

    private val cliCommandToMcpTool = mapOf(
        "open" to "open_session",
        "goto" to "browser_navigate",
        "close" to "close_session",
        "list" to "list_sessions",
        "close-all" to "close_all_sessions",
        "kill-all" to "kill_all_sessions",
        "delete-data" to "delete_session_data",
        "click" to "browser_click",
        "dblclick" to "browser_click",
        "fill" to "browser_type",
        "type" to "browser_press_sequentially",
        "hover" to "browser_hover",
        "drag" to "browser_drag",
        "select" to "browser_select_option",
        "upload" to "browser_file_upload",
        "check" to "browser_check",
        "uncheck" to "browser_uncheck",
        "snapshot" to "browser_snapshot",
        "eval" to "browser_evaluate",
        "press" to "browser_press_key",
        "keydown" to "browser_keydown",
        "keyup" to "browser_keyup",
        "mousemove" to "browser_mouse_move_xy",
        "mousedown" to "browser_mouse_down",
        "mouseup" to "browser_mouse_up",
        "mousewheel" to "browser_mouse_wheel",
        "dialog-accept" to "browser_handle_dialog",
        "dialog-dismiss" to "browser_handle_dialog",
        "resize" to "browser_resize",
        "screenshot" to "browser_take_screenshot",
        "tab-list" to "browser_tabs",
        "tab-new" to "browser_tabs",
        "tab-close" to "browser_tabs",
        "tab-select" to "browser_tabs",
        "agent-run" to "command_run",
        "agent-status" to "command_status",
        "agent-result" to "command_result"
    )

    private val createdSessions = mutableListOf<String>()

    @BeforeEach
    fun setUpFixture() {
        tempDir = Files.createTempDirectory("mcp-tool-controller-e2e")
        uploadFile = tempDir.resolve("upload.txt")
        uploadFile.writeText("browser4 rest mcp e2e upload payload", StandardCharsets.UTF_8)
        fixtureServer = FixtureServer.start()
    }

    @AfterEach
    fun cleanUp() {
        try {
            callTool("kill_all_sessions")
        } catch (e: Exception) {
            logger.debug("Cleanup kill_all_sessions failed: {}", e.message)
        }
        createdSessions.clear()
        if (::fixtureServer.isInitialized) {
            fixtureServer.close()
        }
        if (::tempDir.isInitialized) {
            tempDir.toFile().deleteRecursively()
        }
    }

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
        val missingTools = cliCommandToMcpTool.values.filter { it !in tools }
        assertTrue(missingTools.isEmpty(), "Missing MCP tools for browser4-cli commands: $missingTools")
    }

    @Test
    @DisplayName("open_session preserves requested TEMPORARY profile mode")
    fun testOpenUsesTemporaryProfileMode() {
        val sessionId = openSession(capabilities = mapOf("profileMode" to OPEN_PROFILE_MODE))
        assertEquals(
            OPEN_PROFILE_MODE,
            sessionManager.getSession(sessionId)?.capabilities?.get("profileMode")?.toString()
        )
    }

    @Test
    @DisplayName("session lifecycle matches browser4-cli open/list/close flow")
    fun testSessionLifecycle() {
        val sessionId = openTemporarySession()

        val listResponse = callTool("list_sessions")
        assertNotError(listResponse)
        assertTrue(textContent(listResponse).contains(sessionId))

        val closeResponse = callTool("close_session", mapOf("sessionId" to sessionId))
        assertNotError(closeResponse)
        assertTrue(textContent(closeResponse).contains("Session closed"))
        createdSessions.remove(sessionId)

        val listAfterClose = callTool("list_sessions")
        assertNotError(listAfterClose)
        assertFalse(textContent(listAfterClose).contains(sessionId))
    }

    @Test
    @DisplayName("navigation and storage tools match the CLI navigation scenario")
    fun testNavigationAndStorage() {
        val sessionId = openAndNavigate(fixtureServer.interactiveUrl())
        waitForEvalText(
            sessionId,
            "window.location.pathname",
            "/interactive",
            "Expected to be on the interactive fixture"
        )

        navigate(sessionId, fixtureServer.otherUrl())
        waitForEvalText(
            sessionId,
            "document.title",
            FixtureServer.OTHER_TITLE,
            "Expected the other fixture title after navigate"
        )

        assertNotError(callTool("browser_navigate_back", mapOf("sessionId" to sessionId)))
        waitForEvalText(
            sessionId,
            "window.location.pathname",
            "/interactive",
            "Expected go-back to return to the interactive fixture"
        )

        assertNotError(callTool("browser_navigate_forward", mapOf("sessionId" to sessionId)))
        waitForEvalText(
            sessionId,
            "window.location.pathname",
            "/other",
            "Expected go-forward to return to the other fixture"
        )

        assertNotError(callTool("browser_reload", mapOf("sessionId" to sessionId)))
        waitForEvalText(
            sessionId,
            "document.title",
            FixtureServer.OTHER_TITLE,
            "Expected reload to keep the other fixture loaded"
        )

        val deleteResponse = callTool("delete_session_data", mapOf("sessionId" to sessionId))
        assertNotError(deleteResponse)
        assertTrue(textContent(deleteResponse).contains("User data deleted"))
    }

    @Test
    @DisplayName("interaction tools update the interactive fixture state like the CLI scenario")
    fun testInteractionCommands() {
        val sessionId = openResizedInteractiveSession()

        assertNotError(callTool("type", mapOf("sessionId" to sessionId, "selector" to "#type-target", "text" to "hello world")))
        waitForState(sessionId, "Expected type to update typeValue") {
            it["typeValue"].asText() == "hello world"
        }

        assertNotError(callTool("fill", mapOf("sessionId" to sessionId, "selector" to "#fill-target", "text" to "filled text")))
        waitForState(sessionId, "Expected fill to update fillValue") {
            it["fillValue"].asText() == "filled text"
        }

        listOf(
            "!" to "hello world!",
            "?" to "hello world!?",
            ":" to "hello world!?:",
            "+" to "hello world!?:+",
            ")" to "hello world!?:+)"
        ).forEach { (key, expectedValue) ->
            val pressBeforeEvents = keyEventCount(readState(sessionId))
            assertNotError(callTool("press", mapOf("sessionId" to sessionId, "selector" to "#type-target", "key" to key)))
            waitForState(sessionId, "Expected press to append $key and emit down/up key events") {
                val newEvents = keyEventsSince(it, pressBeforeEvents)
                it["typeValue"].asText() == expectedValue && "down:$key" in newEvents && "up:$key" in newEvents
            }
        }

        assertNotError(callTool("click", mapOf("sessionId" to sessionId, "selector" to "#type-target")))
        val keydownBefore = keyEventCount(readState(sessionId))
        assertNotError(callTool("keydown", mapOf("sessionId" to sessionId, "key" to "Shift")))
        waitForState(sessionId, "Expected keydown to record down:Shift") {
            keyEventCount(it) > keydownBefore && lastKeyEvent(it) == "down:Shift"
        }

        val keyupBefore = keyEventCount(readState(sessionId))
        assertNotError(callTool("keyup", mapOf("sessionId" to sessionId, "key" to "Shift")))
        waitForState(sessionId, "Expected keyup to record up:Shift") {
            keyEventCount(it) > keyupBefore && lastKeyEvent(it) == "up:Shift"
        }

        assertNotError(callTool("browser_click", mapOf("sessionId" to sessionId, "selector" to "#click-target")))
        waitForState(sessionId, "Expected click to increment clickCount") {
            it["clickCount"].asInt() == 1
        }

        assertNotError(callTool("browser_click", mapOf("sessionId" to sessionId, "selector" to "#dblclick-target", "doubleClick" to true)))
        waitForState(sessionId, "Expected dblclick to increment doubleClickCount") {
            it["doubleClickCount"].asInt() == 1
        }

        assertNotError(callTool("hover", mapOf("sessionId" to sessionId, "selector" to "#hover-target")))
        waitForState(sessionId, "Expected hover to set hovered=true") {
            it["hovered"].asBoolean()
        }

        assertNotError(
            callTool(
                "drag",
                mapOf(
                    "sessionId" to sessionId,
                    "sourceSelector" to "#drag-source",
                    "targetSelector" to "#drag-target"
                )
            )
        )
        waitForState(sessionId, "Expected drag to set dragStarted and dragDropped") {
            it["dragStarted"].asBoolean() && it["dragDropped"].asText() == "drag-source"
        }
    }

    @Test
    @DisplayName("form controls and export tools match the CLI fixture scenario")
    fun testFormControlsAndExports() {
        val sessionId = openAndNavigate(fixtureServer.interactiveUrl())

        assertNotError(callTool("select_option", mapOf("sessionId" to sessionId, "selector" to "#select-target", "values" to listOf("green"))))
        waitForState(sessionId, "Expected select to update selectValue") {
            it["selectValue"].asText() == "green"
        }

        assertNotError(callTool("check", mapOf("sessionId" to sessionId, "selector" to "#check-target")))
        waitForState(sessionId, "Expected check to tick the checkbox") {
            it["checkbox"].asBoolean()
        }

        assertNotError(callTool("uncheck", mapOf("sessionId" to sessionId, "selector" to "#check-target")))
        waitForState(sessionId, "Expected uncheck to clear the checkbox") {
            !it["checkbox"].asBoolean()
        }

        assertNotError(
            callTool(
                "upload",
                mapOf(
                    "sessionId" to sessionId,
                    "selector" to "#file-input",
                    "paths" to listOf(uploadFile.toAbsolutePath().toString())
                )
            )
        )
        waitForState(sessionId, "Expected upload to set uploadName") {
            it["uploadName"].asText() == "upload.txt" && it["uploadCount"].asInt() == 1
        }

        val snapshotResponse = callTool("browser_snapshot", mapOf("sessionId" to sessionId))
        assertNotError(snapshotResponse)
        assertTrue(textContent(snapshotResponse).isNotBlank(), "Expected browser_snapshot to return snapshot text")

        val screenshotResponse = callTool("browser_take_screenshot", mapOf("sessionId" to sessionId))
        assertNotError(screenshotResponse)
        assertTrue(textContent(screenshotResponse).length > 100, "Expected screenshot payload to be non-trivial")

        val pageUrl = callTool("page_url", mapOf("sessionId" to sessionId))
        assertNotError(pageUrl)
        assertEquals(fixtureServer.interactiveUrl(), textContent(pageUrl))

        val pageTitle = callTool("page_title", mapOf("sessionId" to sessionId))
        assertNotError(pageTitle)
        assertEquals(FixtureServer.INTERACTIVE_TITLE, textContent(pageTitle))
    }

    @Test
    @DisplayName("command_batch executes interactive and export flows like compiled CLI batches")
    fun testCommandBatchInteractiveFlow() {
        val sessionId = openTemporarySession()

        val batchResponse = callCommandBatch(
            listOf(
                batchToolStep("browser_navigate", mapOf("url" to fixtureServer.interactiveUrl())),
                batchToolStep("browser_press_sequentially", mapOf("ref" to "#type-target", "text" to "hello batch")),
                batchToolStep("browser_type", mapOf("ref" to "#fill-target", "text" to "from batch")),
                batchToolStep("browser_click", mapOf("ref" to "#click-target")),
                batchSnapshotStep("browser_snapshot"),
                batchScreenshotStep("browser_take_screenshot")
            ),
            sessionId = sessionId,
            batchLabel = "interactive flow"
        )

        assertEquals(0, batchResponse.failureCount)
        assertTrue(batchResponse.sessionId == null || batchResponse.sessionId == sessionId)

        waitForState(sessionId, "Expected batch interaction flow to update the fixture state") {
            it["typeValue"].asText() == "hello batch" &&
                    it["fillValue"].asText() == "from batch" &&
                    it["clickCount"].asInt() == 1
        }

        listOf(
            "!" to "hello batch!",
            "?" to "hello batch!?",
            ":" to "hello batch!?:",
            "+" to "hello batch!?:+",
            ")" to "hello batch!?:+)"
        ).forEach { (key, expectedValue) ->
            val pressBeforeEvents = keyEventCount(readState(sessionId))
            val pressBatchResponse = callCommandBatch(
                listOf(
                    batchToolStep("browser_press_key", mapOf("ref" to "#type-target", "key" to key))
                ),
                sessionId = sessionId,
                batchLabel = "interactive flow press $key"
            )
            assertEquals(0, pressBatchResponse.failureCount)
            assertTrue(pressBatchResponse.sessionId == null || pressBatchResponse.sessionId == sessionId)
            waitForState(sessionId, "Expected batch press to append $key and emit down/up key events") {
                val newEvents = keyEventsSince(it, pressBeforeEvents)
                it["typeValue"].asText() == expectedValue && "down:$key" in newEvents && "up:$key" in newEvents
            }
        }

        assertTrue(batchResponse.results.any { it.snapshot?.isNotBlank() == true }, "Expected a snapshot result")
        assertTrue(batchResponse.results.any { (it.screenshot?.length ?: 0) > 100 }, "Expected a screenshot result")

        val specialCharsResponse = callCommandBatch(
            listOf(
                batchToolStep("browser_type", mapOf("ref" to "#fill-target", "text" to "special: @#$%&*"))
            ),
            sessionId = sessionId,
            batchLabel = "interactive flow special chars"
        )
        assertEquals(0, specialCharsResponse.failureCount)
        waitForState(sessionId, "Expected batch fill to preserve special characters") {
            it["fillValue"].asText() == "special: @#$%&*"
        }
    }

    @Test
    @DisplayName("command_batch submits and resets the form fixture like the CLI batch scenarios")
    fun testCommandBatchFormSubmission() {
        val sessionId = openTemporarySession()

        val firstBatch = callCommandBatch(
            listOf(
                batchToolStep("browser_navigate", mapOf("url" to fixtureServer.formUrl())),
                batchToolStep("browser_type", mapOf("ref" to "#first-name", "text" to "Alice")),
                batchToolStep("browser_type", mapOf("ref" to "#last-name", "text" to "Johnson")),
                batchToolStep("browser_type", mapOf("ref" to "#email", "text" to "alice@example.com")),
                batchToolStep("browser_select_option", mapOf("ref" to "#country", "values" to listOf("us"))),
                batchToolStep("browser_check", mapOf("ref" to "#agree-terms")),
                batchToolStep("browser_type", mapOf("ref" to "#comments", "text" to "batch test comment")),
                batchToolStep("browser_click", mapOf("ref" to "#submit-btn"))
            ),
            sessionId = sessionId,
            batchLabel = "form submission 1"
        )

        assertTrue(firstBatch.sessionId == null || firstBatch.sessionId == sessionId)
        waitForState(sessionId, "Expected first batch submission to populate the form") {
            it["submitCount"].asInt() == 1 &&
                    it["firstName"].asText() == "Alice" &&
                    it["lastName"].asText() == "Johnson" &&
                    it["email"].asText() == "alice@example.com" &&
                    it["country"].asText() == "us" &&
                    it["agreeTerms"].asBoolean() &&
                    it["comments"].asText() == "batch test comment" &&
                    it["validationError"].asText().isEmpty()
        }

        val secondBatch = callCommandBatch(
            listOf(
                batchToolStep("browser_click", mapOf("ref" to "#reset-btn")),
                batchToolStep("browser_type", mapOf("ref" to "#first-name", "text" to "Bob")),
                batchToolStep("browser_type", mapOf("ref" to "#last-name", "text" to "Smith")),
                batchToolStep("browser_type", mapOf("ref" to "#email", "text" to "bob@example.com")),
                batchToolStep("browser_select_option", mapOf("ref" to "#country", "values" to listOf("uk"))),
                batchToolStep("browser_check", mapOf("ref" to "#agree-terms")),
                batchToolStep("browser_click", mapOf("ref" to "#submit-btn"))
            ),
            sessionId = sessionId,
            batchLabel = "form submission 2"
        )

        assertTrue(secondBatch.sessionId == null || secondBatch.sessionId == sessionId)
        waitForState(sessionId, "Expected second batch submission to reset and submit Bob") {
            it["submitCount"].asInt() == 2 &&
                    it["resetCount"].asInt() == 1 &&
                    it["firstName"].asText() == "Bob" &&
                    it["lastName"].asText() == "Smith" &&
                    it["country"].asText() == "uk"
        }
    }

    @Test
    @DisplayName("command_batch restores focus and mouse position like CLI compiled stateful batches")
    fun testCommandBatchStatefulFocusAndMouseFlow() {
        val sessionId = openResizedInteractiveSession()
        val stateBefore = readState(sessionId)
        val keyEventsBefore = keyEventCount(stateBefore)
        val mouseDownBefore = stateBefore["mouseDownCount"].asInt()
        val mouseUpBefore = stateBefore["mouseUpCount"].asInt()

        val batchResponse = callCommandBatch(
            listOf(
                batchToolStep("browser_press_sequentially", mapOf("ref" to "#type-target", "text" to "focus batch")),
                batchToolStep("browser_keydown", mapOf("key" to "Shift"), preFocusSelector = "#type-target"),
                batchToolStep("browser_keyup", mapOf("key" to "Shift"), preFocusSelector = "#type-target"),
                batchToolStep("browser_mouse_move_xy", mapOf("x" to 120, "y" to 120)),
                batchToolStep("browser_mouse_down", mapOf("button" to "left"), preMousePosition = batchMousePosition(120, 120)),
                batchToolStep("browser_mouse_up", mapOf("button" to "left"), preMousePosition = batchMousePosition(120, 120))
            ),
            sessionId = sessionId,
            batchLabel = "stateful focus and mouse flow"
        )

        assertTrue(batchResponse.sessionId == null || batchResponse.sessionId == sessionId)
        assertEquals(0, batchResponse.failureCount)
        waitForState(sessionId, "Expected compiled batch flow to restore focus and mouse state") {
            val newEvents = keyEventsSince(it, keyEventsBefore)
            it["typeValue"].asText() == "focus batch" &&
                    "down:Shift" in newEvents &&
                    "up:Shift" in newEvents &&
                    it["lastMouse"][0].asInt() == 120 &&
                    it["lastMouse"][1].asInt() == 120 &&
                    it["mouseDownCount"].asInt() >= mouseDownBefore + 1 &&
                    it["mouseUpCount"].asInt() >= mouseUpBefore + 1
        }
    }

    @Test
    @DisplayName("command_batch continue and bail behavior matches CLI error handling")
    fun testCommandBatchErrorHandling() {
        val sessionId = openAndNavigate(fixtureServer.interactiveUrl())

        val continueResponse = callCommandBatch(
            listOf(
                batchToolStep("browser_press_sequentially", mapOf("ref" to "#type-target", "text" to "before error")),
                batchToolStep("not_a_real_tool"),
                batchToolStep("browser_type", mapOf("ref" to "#fill-target", "text" to "after error"))
            ),
            sessionId = sessionId,
            batchLabel = "error handling continue"
        )
        assertEquals(1, continueResponse.failureCount)
        assertFalse(continueResponse.stoppedOnError)
        assertEquals(listOf(true, false, true), continueResponse.results.map { it.ok })
        waitForState(sessionId, "Expected non-bailing batch to keep running after an error") {
            it.path("fillValue").asText() == "after error"
        }

        val bailResponse = callCommandBatch(
            listOf(
                batchToolStep("browser_press_sequentially", mapOf("ref" to "#type-target", "text" to " bail test")),
                batchToolStep("still_not_real"),
                batchToolStep("browser_type", mapOf("ref" to "#fill-target", "text" to "should not execute"))
            ),
            bail = true,
            sessionId = sessionId,
            batchLabel = "error handling bail"
        )
        assertEquals(1, bailResponse.failureCount)
        assertTrue(bailResponse.stoppedOnError)
        assertEquals(listOf(true, false), bailResponse.results.map { it.ok })
        waitForState(sessionId, "Expected batch text to contain the pre-error typed value") {
            it.path("typeValue").asText().contains("bail test")
        }
        assertEquals("after error", readState(sessionId).path("fillValue").asText())

        val multiErrorResponse = callCommandBatch(
            listOf(
                batchToolStep("bad_cmd_1"),
                batchToolStep("bad_cmd_2"),
                batchToolStep("browser_press_sequentially", mapOf("ref" to "#type-target", "text" to " still works"))
            ),
            sessionId = sessionId,
            batchLabel = "error handling multiple failures"
        )
        assertEquals(2, multiErrorResponse.failureCount)
        assertFalse(multiErrorResponse.stoppedOnError)
        assertEquals(listOf(false, false, true), multiErrorResponse.results.map { it.ok })
        waitForState(sessionId, "Expected later batch command to run after multiple earlier errors") {
            it.path("typeValue").asText().contains("still works")
        }
    }

    @Test
    @DisplayName("mouse, dialog, and tab tools match the interactive CLI scenarios")
    fun testMouseDialogAndTabCommands() {
        val sessionId = openResizedInteractiveSession()

        assertNotError(callTool("mousemove", mapOf("sessionId" to sessionId, "x" to 120, "y" to 120)))
        waitForState(sessionId, "Expected mousemove to update lastMouse") {
            it["lastMouse"][0].asInt() == 120 && it["lastMouse"][1].asInt() == 120
        }

        val mouseDownBefore = readState(sessionId)["mouseDownCount"].asInt()
        runToolAndWaitForState(
            sessionId = sessionId,
            toolName = "mousedown",
            arguments = mapOf("sessionId" to sessionId, "button" to "left"),
            failureMessage = "Expected mousedown to increment mouseDownCount",
            predicate = { it["mouseDownCount"].asInt() >= mouseDownBefore + 1 }
        )

        val mouseUpBefore = readState(sessionId)["mouseUpCount"].asInt()
        runToolAndWaitForState(
            sessionId = sessionId,
            toolName = "mouseup",
            arguments = mapOf("sessionId" to sessionId, "button" to "left"),
            failureMessage = "Expected mouseup to increment mouseUpCount",
            predicate = { it["mouseUpCount"].asInt() >= mouseUpBefore + 1 }
        )

        runToolAndWaitForState(
            sessionId = sessionId,
            toolName = "mousewheel",
            arguments = mapOf("sessionId" to sessionId, "deltaX" to 0, "deltaY" to 160),
            failureMessage = "Expected mousewheel to update lastWheel",
            predicate = { it["lastWheel"][0].asInt() == 160 && it["lastWheel"][1].asInt() == 0 }
        )

        evalText(
            sessionId,
            "(() => { setTimeout(() => document.getElementById('prompt-target').click(), 100); return 'scheduled'; })()"
        )
        Thread.sleep(500)
        assertNotError(
            callTool(
                "browser_handle_dialog",
                mapOf("sessionId" to sessionId, "accept" to true, "promptText" to "accepted by mcp")
            )
        )
        waitForState(sessionId, "Expected dialog accept to set promptResult") {
            it["promptResult"].asText() == "accepted by mcp"
        }

        evalText(
            sessionId,
            "(() => { setTimeout(() => document.getElementById('confirm-target').click(), 100); return 'scheduled'; })()"
        )
        Thread.sleep(500)
        assertNotError(callTool("browser_handle_dialog", mapOf("sessionId" to sessionId, "accept" to false)))
        waitForState(sessionId, "Expected dialog dismiss to set confirmResult") {
            it["confirmResult"].asText() == "dismissed"
        }

        val tabList = callTool("browser_tabs", mapOf("sessionId" to sessionId, "action" to "list"))
        assertNotError(tabList)
        val initialTabOutput = textContent(tabList)
        assertTrue(initialTabOutput.contains(fixtureServer.interactiveUrl()))

        assertNotError(
            callTool(
                "browser_tabs",
                mapOf("sessionId" to sessionId, "action" to "new", "url" to fixtureServer.otherUrl())
            )
        )
        val updatedTabs = callTool("browser_tabs", mapOf("sessionId" to sessionId, "action" to "list"))
        assertNotError(updatedTabs)
        val updatedOutput = textContent(updatedTabs)
        assertTrue(updatedOutput.contains(fixtureServer.interactiveUrl()))
        assertTrue(updatedOutput.contains(fixtureServer.otherUrl()))

        val otherTabId = extractTabId(updatedOutput, fixtureServer.otherUrl())
        assertNotError(callTool("browser_tabs", mapOf("sessionId" to sessionId, "action" to "select", "tabId" to otherTabId)))
        assertNotError(callTool("browser_tabs", mapOf("sessionId" to sessionId, "action" to "close", "tabId" to otherTabId)))
    }

    @Test
    @Tag("RequiresAI")
    @DisplayName("agent extract and summarize work through MCP")
    fun testAgentTools() {
        Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(conf))

        val sessionId = openAndNavigate(MOCK_PRODUCT_DETAIL_URL)

        val summarizeResponse = callTool(
            "agent_summarize",
            mapOf("sessionId" to sessionId, "instruction" to "Summarize the product page", "selector" to "#productTitle")
        )
        assertNotError(summarizeResponse)
        assertTrue(textContent(summarizeResponse).isNotBlank(), "Expected agent_summarize to return text")

        val extractResponse = callTool(
            "agent_extract",
            mapOf(
                "sessionId" to sessionId,
                "instruction" to "product name, price",
                "schema" to """{"type":"object"}"""
            )
        )
        assertNotError(extractResponse)
        assertTrue(textContent(extractResponse).isNotBlank(), "Expected agent_extract to return extracted content")
    }

    @Test
    @DisplayName("agent-run agent-status and agent-result aliases work through MCP command tools")
    fun testAgentRunStatusAndResultTools() {
        val commandRun = callTool(
            "command_run",
            mapOf("command" to fixtureServer.interactiveUrl(), "async" to true)
        )
        assertNotError(commandRun)

        val taskId = textContent(commandRun).removePrefix("\"").removeSuffix("\"").trim()
        assertTrue(taskId.isNotBlank(), "Expected command_run to return a task id")

        val status = waitForCommandDone(taskId)
        assertEquals(taskId, status["id"].asText())
        assertTrue(status["processState"].asText() == "done" || status["isDone"].asBoolean())

        val result = callTool("command_result", mapOf("id" to taskId))
        assertNotError(result)
        val resultPayload = textContent(result)
        assertTrue(resultPayload.isNotBlank(), "Expected command_result to return the completed result")
    }

    @Test
    @DisplayName("invalid requests still surface meaningful MCP errors")
    fun testErrorHandling() {
        val missingSession = callTool("browser_navigate", mapOf("url" to fixtureServer.interactiveUrl()))
        assertIsError(missingSession)
        assertTrue(textContent(missingSession).contains("sessionId"))

        val sessionId = openTemporarySession()
        val unknownTool = callTool("nonexistent_tool_xyz", mapOf("sessionId" to sessionId))
        assertIsError(unknownTool)
        assertTrue(textContent(unknownTool).contains("Unknown tool"))

        val invalidSession = callTool(
            "browser_navigate",
            mapOf("sessionId" to "does-not-exist", "url" to fixtureServer.interactiveUrl())
        )
        assertIsError(invalidSession)
        assertTrue(textContent(invalidSession).contains("Session not found"))
    }

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

    private fun openSession(capabilities: Map<String, Any?>? = null): String {
        val arguments = buildMap<String, Any?> {
            if (capabilities != null) {
                put("capabilities", capabilities)
            }
        }
        val response = callTool("open_session", arguments)
        assertNotError(response)
        val sessionId = objectMapper.readTree(textContent(response)).path("sessionId").asText()
        assertTrue(sessionId.isNotBlank(), "open_session must return a non-blank sessionId")
        createdSessions.add(sessionId)
        return sessionId
    }

    private fun openTemporarySession(): String = openSession(mapOf("profileMode" to OPEN_PROFILE_MODE))

    private fun navigate(sessionId: String, url: String) {
        val response = callTool("browser_navigate", mapOf("sessionId" to sessionId, "url" to url))
        assertNotError(response)
    }

    private fun openAndNavigate(url: String): String {
        val sessionId = openTemporarySession()
        val response = callTool("browser_navigate", mapOf("sessionId" to sessionId, "url" to url))
        if (!response.isError) {
            return sessionId
        }
        if (!textContent(response).contains("Cannot find context with specified id")) {
            assertNotError(response)
        }

        logger.info("Retrying initial browser_navigate with a fresh session after missing browser context")
        callTool("close_session", mapOf("sessionId" to sessionId))
        createdSessions.remove(sessionId)

        val retrySessionId = openTemporarySession()
        val retryResponse = callTool("browser_navigate", mapOf("sessionId" to retrySessionId, "url" to url))
        assertNotError(retryResponse)
        return retrySessionId
    }

    private fun openResizedInteractiveSession(): String {
        val sessionId = openAndNavigate(fixtureServer.interactiveUrl())
        val resize = callTool("resize", mapOf("sessionId" to sessionId, "width" to 1280, "height" to 900))
        assertNotError(resize)
        waitForCondition("Expected resize to make innerWidth at least 1000") {
            evalText(sessionId, "window.innerWidth.toString()").toIntOrNull()?.let { it >= 1000 } == true
        }
        return sessionId
    }

    private fun evalText(sessionId: String, expression: String): String {
        val response = callTool("browser_evaluate", mapOf("sessionId" to sessionId, "expression" to expression))
        assertNotError(response)
        return textContent(response)
    }

    private fun readState(sessionId: String): JsonNode {
        val raw = evalText(sessionId, "document.getElementById('state-log').textContent")
        return objectMapper.readTree(raw.ifBlank { "null" })
    }

    private fun keyEventCount(state: JsonNode): Int = (state["keyEvents"] as? ArrayNode)?.size() ?: 0

    private fun keyEventsSince(state: JsonNode, startIndex: Int): List<String> {
        val events = state["keyEvents"] as? ArrayNode ?: return emptyList()
        return (startIndex until events.size()).map { index -> events.get(index).asText() }
    }

    private fun lastKeyEvent(state: JsonNode): String? {
        val events = state["keyEvents"] as? ArrayNode ?: return null
        return if (events.size() == 0) null else events.get(events.size() - 1).asText()
    }

    private fun waitForState(
        sessionId: String,
        failureMessage: String,
        timeout: Duration = Duration.ofSeconds(20),
        predicate: (JsonNode) -> Boolean,
    ): JsonNode {
        val deadline = Instant.now().plus(timeout)
        var lastState: JsonNode = objectMapper.readTree("null")
        while (Instant.now().isBefore(deadline)) {
            lastState = readState(sessionId)
            if (predicate(lastState)) {
                return lastState
            }
            Thread.sleep(300)
        }
        throw AssertionError(
            "$failureMessage\n" +
                    "Expected: predicate(state) == true\n" +
                    "Actual: predicate(state) == false\n" +
                    "Last state: $lastState"
        )
    }

    private fun runToolAndWaitForState(
        sessionId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        failureMessage: String,
        timeout: Duration = Duration.ofSeconds(5),
        predicate: (JsonNode) -> Boolean,
    ): JsonNode {
        val response = callTool(toolName, arguments)
        assertNotError(response)
        return waitForState(sessionId, failureMessage, timeout, predicate)
    }

    private fun waitForEvalText(
        sessionId: String,
        expression: String,
        expected: String,
        failureMessage: String,
        timeout: Duration = Duration.ofSeconds(20),
    ) {
        val deadline = Instant.now().plus(timeout)
        var lastValue = ""
        while (Instant.now().isBefore(deadline)) {
            lastValue = evalText(sessionId, expression)
            if (lastValue == expected) {
                return
            }
            Thread.sleep(300)
        }
        throw AssertionError("$failureMessage. Expected <$expected> but got <$lastValue>")
    }

    private fun waitForCondition(
        failureMessage: String,
        timeout: Duration = Duration.ofSeconds(20),
        predicate: () -> Boolean,
    ) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            if (predicate()) {
                return
            }
            Thread.sleep(300)
        }
        throw AssertionError(failureMessage)
    }

    private fun callCommandBatch(
        steps: List<Map<String, Any?>>,
        bail: Boolean = false,
        sessionId: String? = null,
        batchLabel: String = "command_batch",
    ): BatchExecutionResponse {
        val arguments = linkedMapOf<String, Any?>(
            "steps" to steps,
            "bail" to bail,
        )
        if (sessionId != null) {
            arguments["sessionId"] = sessionId
        }
        val startedAt = System.nanoTime()
        val response = callTool("command_batch", arguments)
        val wallDurationMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertNotError(response)
        return objectMapper.readValue(textContent(response), BatchExecutionResponse::class.java).also {
            logBatchCommandTimings(batchLabel, steps, it, wallDurationMillis)
        }
    }

    private fun batchToolStep(
        tool: String,
        arguments: Map<String, Any?> = emptyMap(),
        preFocusSelector: String? = null,
        preMousePosition: Map<String, Int>? = null,
    ): Map<String, Any?> {
        return buildMap {
            put("op", "tool")
            put("tool", tool)
            put("arguments", arguments)
            if (preFocusSelector != null) {
                put("preFocusSelector", preFocusSelector)
            }
            if (preMousePosition != null) {
                put("preMousePosition", preMousePosition)
            }
        }
    }

    private fun batchSnapshotStep(tool: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        return mapOf(
            "op" to "snapshot",
            "tool" to tool,
            "arguments" to arguments,
        )
    }

    private fun batchScreenshotStep(tool: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        return mapOf(
            "op" to "screenshot",
            "tool" to tool,
            "arguments" to arguments,
        )
    }

    private fun batchMousePosition(x: Int, y: Int): Map<String, Int> {
        return mapOf("x" to x, "y" to y)
    }

    private fun logBatchCommandTimings(
        batchLabel: String,
        steps: List<Map<String, Any?>>,
        response: BatchExecutionResponse,
        wallDurationMillis: Long,
    ) {
        if (response.results.isEmpty()) {
            logger.info("Batch command timings [{}] wall={} ms | no steps executed", batchLabel, wallDurationMillis)
            return
        }

        val timings = response.results.map { result ->
            val stepDescription = describeBatchStep(steps.getOrNull(result.index))
            BatchStepTiming(
                index = result.index,
                description = stepDescription,
                durationMillis = result.durationMillis,
                ok = result.ok,
                error = result.error,
            )
        }
        val totalStepDurationMillis = timings.sumOf { it.durationMillis }
        val summary = timings.joinToString(" | ") { timing ->
            buildString {
                append("#")
                append(timing.index)
                append(" ")
                append(timing.description)
                append("=")
                append(timing.durationMillis)
                append("ms")
                if (!timing.ok) {
                    append(" ERROR")
                }
            }
        }
        val slowest = timings.maxByOrNull { it.durationMillis }

        logger.info(
            "Batch command timings [{}] wall={} ms stepSum={} ms | {}",
            batchLabel,
            wallDurationMillis,
            totalStepDurationMillis,
            summary
        )
        if (slowest != null) {
            logger.info(
                "Slowest batch command [{}] #{} {} took {} ms{}",
                batchLabel,
                slowest.index,
                slowest.description,
                slowest.durationMillis,
                slowest.error?.let { " | error=$it" }.orEmpty()
            )
        }
    }

    private fun describeBatchStep(step: Map<String, Any?>?): String {
        val op = step?.get("op")?.toString() ?: return "unknown"
        return when (op) {
            "tool", "snapshot", "screenshot" -> step["tool"]?.toString() ?: op
            "press" -> "press:${step["key"]}"
            else -> op
        }
    }

    private fun waitForCommandDone(taskId: String, timeout: Duration = Duration.ofMinutes(3)): JsonNode {
        val deadline = Instant.now().plus(timeout)
        var lastStatus: JsonNode = objectMapper.readTree("null")
        while (Instant.now().isBefore(deadline)) {
            val response = callTool("command_status", mapOf("id" to taskId))
            assertNotError(response)
            lastStatus = objectMapper.readTree(textContent(response))
            if (lastStatus["processState"]?.asText() == "done" || lastStatus["isDone"]?.asBoolean() == true) {
                return lastStatus
            }
            Thread.sleep(1000)
        }
        throw AssertionError("Timed out waiting for command $taskId to finish. Last status: $lastStatus")
    }

    private fun extractTabId(output: String, url: String): String {
        val regex = Regex("""id[:=]"?([^",}\s]+)"?""")
        val ids = regex.findAll(output)
            .map { it.groupValues[1] to it.range.first }
            .toList()
        val urlPos = output.indexOf(url)
        check(urlPos >= 0) { "URL '$url' not found in tab output:\n$output" }
        return ids.lastOrNull { it.second < urlPos }?.first
            ?: error("Could not find tab id for '$url' in:\n$output")
    }

    private data class BatchExecutionResponse(
        val sessionId: String?,
        val failureCount: Int,
        val stoppedOnError: Boolean,
        val results: List<BatchExecutionResult>,
    )

    private data class BatchExecutionResult(
        val index: Int,
        val ok: Boolean,
        val durationMillis: Long = 0,
        val sessionId: String? = null,
        val text: String? = null,
        val error: String? = null,
        val pageUrl: String? = null,
        val pageTitle: String? = null,
        val snapshot: String? = null,
        val screenshot: String? = null,
    )

    private data class BatchStepTiming(
        val index: Int,
        val description: String,
        val durationMillis: Long,
        val ok: Boolean,
        val error: String?,
    )

    private class FixtureServer private constructor(
        private val server: HttpServer,
        private val executor: java.util.concurrent.ExecutorService,
    ) : AutoCloseable {
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        fun interactiveUrl(): String = "$baseUrl/interactive"
        fun otherUrl(): String = "$baseUrl/other"
        fun formUrl(): String = "$baseUrl/form"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        companion object {
            const val INTERACTIVE_TITLE = "Browser4 CLI Interactive Fixture"
            const val OTHER_TITLE = "Browser4 CLI Other Fixture"
            private const val INTERACTIVE_FIXTURE_RESOURCE = "static/b4/mcp-tool-controller-interactive-fixture.html"
            private const val OTHER_FIXTURE_RESOURCE = "static/b4/mcp-tool-controller-other-fixture.html"
            private const val FORM_FIXTURE_RESOURCE = "static/b4/mcp-tool-controller-form-fixture.html"

            fun start(): FixtureServer {
                val executor = Executors.newCachedThreadPool()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val interactiveHtml = loadFixture(INTERACTIVE_FIXTURE_RESOURCE)
                val otherHtml = loadFixture(OTHER_FIXTURE_RESOURCE)
                val formHtml = loadFixture(FORM_FIXTURE_RESOURCE)
                server.executor = executor
                server.createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val (status, contentType, body) = when (path) {
                        "/", "/interactive" -> Triple(200, "text/html; charset=utf-8", interactiveHtml)
                        "/other" -> Triple(200, "text/html; charset=utf-8", otherHtml)
                        "/form" -> Triple(200, "text/html; charset=utf-8", formHtml)
                        else -> Triple(404, "text/plain; charset=utf-8", "not found")
                    }
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", contentType)
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start()
                return FixtureServer(server, executor)
            }

            private fun loadFixture(resourcePath: String): String {
                return FixtureServer::class.java.classLoader.getResourceAsStream(resourcePath)?.use { input ->
                    String(input.readAllBytes(), StandardCharsets.UTF_8)
                } ?: error("Fixture resource not found: $resourcePath")
            }
        }
    }

}
