package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.CommandRequest
import ai.platon.pulsar.rest.api.entities.CommandStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.expectBody

/**
 * A page visit that asks for structured data, on a real browser and a real h2 engine.
 *
 * The X-SQL of a page visit is executed by the same hyperlink the swarm and the html snapshot
 * queries use — the engine resolves the url in its FROM clause through `load_and_select()` and
 * `PulsarSession.load()` — so this is the third consumer of the read-only contract.  It had no
 * automated coverage: `CommandControllerE2ETest` asserts the same shape, but it is tagged
 * `E2ETest`, which both CI gates exclude, and `CommandRunnerTest` needs a model.
 *
 * The assertions are carried under `IntegrationTest`, which the ci.yml gate runs, and the visit
 * asks for nothing but an X-SQL: with no page summary prompt and no extraction rules the visitor
 * never reaches its instruction path, so no model is needed.
 */
@Tag("IntegrationTest")
class CommandXSqlTest : RestAPITestBase() {

    @Test
    @DisplayName("a page visit with an X-SQL returns the extracted rows, with no model configured")
    fun testPageVisitWithXSqlReturnsRows() {
        val url = requireNotNull(urls["productDetailPage"]) { "the product detail fixture url" }
        val request = CommandRequest(
            url,
            "-refresh",
            xsql = "select dom_first_text(dom, '#productTitle') as title from load_and_select(@url, ':root')",
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
        assertTrue(status!!.isDone, "a synchronous command must come back terminal")
        assertEquals(200, status.pageStatusCode, "the page must have been fetched")
        assertEquals(200, status.statusCode, "the visit must report the query's outcome")
        assertEquals(null, status.commandResult?.pageSummary, "no model was asked for a summary")

        val rows = status.commandResult?.xsqlResultSet
        assertNotNull(rows, "the X-SQL result set must reach the command result")
        assertEquals(1, rows!!.size, "rows: $rows")
        assertTrue(
            rows.single()["title"].toString().contains("4K OLED TV"),
            "the query must read the page the visitor loaded: $rows"
        )
    }
}
