package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.rest.api.TestHelper.MOCK_PRODUCT_DETAIL_URL
import ai.platon.pulsar.rest.api.entities.CommandRequest
import ai.platon.pulsar.rest.api.entities.CommandStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("E2ETest")
class CommandControllerE2ETest : RestAPITestBase() {

    /**
     * Test [CommandController.submitCommand]
     * */
    @Test
    @DisplayName("Test submitCommand with pageSummaryPrompt + sync mode")
    fun testSubmitCommandWithPageSummaryPromptSyncMode() {
        val pageType = "productDetailPage"
        val url = requireNotNull(urls[pageType])

        val request = CommandRequest(
            url,
            "",
            pageSummaryPrompt = "Summarize the product.",
            async = false
        )

        val status = client.post().uri("/api/commands")
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<CommandStatus>()
            .returnResult()
            .responseBody
        assertNotNull(status)

        assertEquals(200, status.pageStatusCode)
        assertTrue(status.isDone)
        assertEquals(200, status.statusCode)

        assertNotNull(status.commandResult)
        assertNotNull(status.commandResult?.pageSummary)

        assertNull(status.commandResult?.fields)
        assertNull(status.commandResult?.links)
        assertNull(status.commandResult?.xsqlResultSet)
    }

    /**
     * Test [CommandController.submitCommand]
     * Test [CommandController.streamEvents]
     * */
    @Test
    @DisplayName("Test submitCommand with pageSummaryPrompt, dataExtractionRules + sync mode")
    fun testSubmitCommandWithPageSummaryPromptDataExtractionRulesSyncMode() {
        val pageType = "productDetailPage"
        val url = requireNotNull(urls[pageType])

        val request = CommandRequest(
            url,
            "",
            pageSummaryPrompt = "Summarize the product.",
            dataExtractionRules = "product name, ratings, price",
            async = false
        )

        val status = client.post().uri("/api/commands")
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<CommandStatus>()
            .returnResult()
            .responseBody
        assertNotNull(status)

        assertEquals(200, status.pageStatusCode)
        assertTrue(status.isDone)
        assertEquals(200, status.statusCode)

        assertNotNull(status.commandResult)
        assertNotNull(status.commandResult?.pageSummary)
        assertNotNull(status.commandResult?.fields)

        assertNull(status.commandResult?.links)
        assertNull(status.commandResult?.xsqlResultSet)
    }

    @Test
    @DisplayName("test executeCommand with X-SQL + sync mode")
    fun testExecuteCommandWithXSqlSyncMode() {
        val sqlTemplate = sqlTemplates["productDetailPage"]!!.template
        val request = CommandRequest(
            MOCK_PRODUCT_DETAIL_URL,
            xsql = sqlTemplate,
            async = false
        )

        val status = client.post().uri("/api/commands")
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<CommandStatus>()
            .returnResult()
            .responseBody
        assertNotNull(status)

        printlnPro(prettyPulsarObjectMapper().writeValueAsString(status))
        val result = status.commandResult

        assertEquals(200, status.pageStatusCode)
        assertTrue(status.isDone)
        assertEquals(200, status.statusCode)

        assertNotNull(result)
        assertTrue { status.isDone }

        assertNull(result.pageSummary)
        assertNotNull(result.xsqlResultSet)
    }

    /**
     * Test [CommandController.submitCommand].
     *
     * Example CommandStatus:
     *
     * {
     *   "id": "5eb69939-f42f-431d-a485-d47a10901871",
     *   "statusCode": 200,
     *   "event": "PerceptiveAgent.onDidRun",
     *   "processState": "done",
     *   "pageStatusCode": 201,
     *   "pageContentBytes": 0,
     *   "message": "The browser has been successfully opened and is currently displaying the Microsoft Bing (cn.bing.com) homepage.",
     *   "commandResult": {
     *     "summary": "The browser has been successfully opened and is currently displaying the Microsoft Bing (cn.bing.com) homepage."
     *   },
     *   "lastModifiedTime": "2026-04-08T14:26:24.866049300Z",
     *   "finishTime": "2026-04-08T14:26:24.866049300Z",
     *   "agentHistory": {
     *     "states": [
     *       {
     *         "step": 1,
     *         "instruction": "Open the browser",
     *         "summary": "The browser has been successfully opened and is currently displaying the Microsoft Bing (cn.bing.com) homepage.",
     *         "isComplete": true
     *       }
     *     ]
     *   },
     *   "isDone": true,
     *   "status": "OK",
     *   "agentState": {
     *     "step": 1,
     *     "instruction": "Open the browser",
     *     "summary": "The browser has been successfully opened and is currently displaying the Microsoft Bing (cn.bing.com) homepage.",
     *     "isComplete": true
     *   }
     * }
     * */
    @Test
    @Tag("Slow")
    @Tag("ManualOnly")
    @DisplayName("test statefulAgentRunner.execute() sets agentHistory on status")
    fun testExecuteAgentCommandSetsAgentHistoryOnStatus() {
        // A very, very simple task
        val request = "Open the browser"
        val commandId = submitPlainCommandAsync(request)
        val status = waitForAgentHistory(commandId)

        assertNotNull(status)

        printlnPro(Pson.toJson(status))

        assertNotNull(status)

        // The async status endpoint should expose agent execution history as soon as the run starts progressing.
        assertNotNull(status.agentState)
    }

    private fun submitPlainCommandAsync(request: String): String {
        val rawBody = client.post().uri("/api/commands/plain?async=true")
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody

        val body = rawBody?.trim()
        check(!body.isNullOrBlank()) { "Expected non-blank async command id body" }

        return body.removeSurrounding("\"").trim().also {
            check(it.isNotBlank()) { "Expected non-blank command id but got: $body" }
        }
    }

    private fun waitForAgentHistory(commandId: String): CommandStatus? {
        val deadline = Instant.now().plus(Duration.ofMinutes(3))
        var lastStatus: CommandStatus? = null

        while (Instant.now().isBefore(deadline)) {
            lastStatus = client.get().uri("/api/commands/$commandId/status")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody<CommandStatus>()
                .returnResult()
                .responseBody

            if (lastStatus?.isDone == true) {
                return lastStatus
            }

            Thread.sleep(Duration.ofSeconds(2).toMillis())
        }

        return lastStatus
    }
}
