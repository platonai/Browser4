package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.rest.api.entities.CommandRequest
import ai.platon.pulsar.rest.api.entities.CommandStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertNotNull

@Tag("Slow")
class CommandControllerSSETest : RestAPITestBase() {

    /**
     * Test [CommandController.submitCommand]
     * Test [CommandController.streamEvents]
     * */
    @Test
    @DisplayName("Test submitCommand with pageSummaryPrompt + SSE")
    fun testSubmitCommandWithPageSummaryPromptSse() {
        val pageType = "productDetailPage"
        val url = requireNotNull(urls[pageType])

        val request = CommandRequest(
            url,
            "",
            pageSummaryPrompt = "Summarize the product.",
            async = true,
        )

        val id = submitAsyncAndGetId(request)
        printlnPro("commandId: $id")

        receiveSSE(id)
    }

    /**
     * Test [CommandController.submitCommand]
     * Test [CommandController.streamEvents]
     * */
    @Test
    @DisplayName("Test submitCommand with pageSummaryPrompt, dataExtractionRules + SSE")
    fun testSubmitCommandWithPageSummaryPromptDataExtractionRulesSse() {
        val pageType = "productDetailPage"
        val url = requireNotNull(urls[pageType])

        val request = CommandRequest(
            url,
            "",
            pageSummaryPrompt = "Summarize the product.",
            dataExtractionRules = "product name, ratings, price",
            async = true,
        )

        val id = submitAsyncAndGetId(request)
        printlnPro("commandId: $id")

        receiveSSE(id)
    }

    private fun submitAsyncAndGetId(request: CommandRequest): String {
        // For async requests, POST /api/commands returns the command id as plain text (JSON string or raw string).
        val rawBody = client.post().uri("/api/commands")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody

        val body = rawBody?.trim()
        check(!body.isNullOrBlank()) { "Expected non-blank async command id body" }

        // It might be returned as a JSON string ("id") or as plain text (id)
        val id = body.removeSurrounding("\"").trim()
        check(id.isNotBlank()) { "Expected non-blank command id but got: $body" }

        // Sanity: it should also be queryable as status.
        client.get().uri("/api/commands/$id/status")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<CommandStatus>()

        return id
    }

    private fun receiveSSE(id: String) {
        // Consume only a limited prefix of the SSE stream to keep the test bounded.
        val result = client.get().uri("/api/commands/$id/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()

        val body = result.responseBody
        assertNotNull(body)

        // Basic sanity: server-sent events should contain at least one "data:" line.
        val lines = body.lineSequence().filter { it.isNotBlank() }.take(200).toList()
        lines.filter { it.startsWith("data:") }.take(20).forEach { printlnPro(it) }

        // Don’t overfit here: different environments may emit different JSON structures.
        // The contract we enforce is just: it’s an SSE stream and contains data frames.
        check(lines.any { it.startsWith("data:") }) {
            "Expected SSE data frames for command $id but got: ${lines.take(20).joinToString("\\n")}"
        }
    }
}
