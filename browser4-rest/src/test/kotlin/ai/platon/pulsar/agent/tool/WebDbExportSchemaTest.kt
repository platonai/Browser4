package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.rest.mcp.contract.ToolRegistryFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * `webdb.export` promises a result shape, so the shape it builds must match.
 *
 * The export itself needs a live session, so the payload construction was lifted out
 * of the `withLock` block into [WebDbToolExecutor.exportSummary] — a pure function of
 * the per-URL results, which is what the declared `outputSchema` describes.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("webdb export result contract")
class WebDbExportSchemaTest {

    private fun exportSpec() = ToolRegistryFixture.executors()
        .filterIsInstance<WebDbToolExecutor>()
        .single()
        .getToolSpecs()
        .getValue("export")

    @Test
    @DisplayName("the advertised spec declares a result schema")
    fun exportDeclaresItsResultSchema() {
        val schema = exportSpec().outputSchema
        assertNotNull(schema, "webdb.export returns JSON, so it must declare its result contract")
    }

    @Test
    @DisplayName("a mixed export satisfies the schema, and the schema rejects a truncated one")
    fun summaryMatchesTheSchema() {
        val spec = exportSpec()
        val executor = ToolRegistryFixture.executors().filterIsInstance<WebDbToolExecutor>().single()

        val results = listOf(
            mapOf<String, Any?>("url" to "https://example.com", "status" to "ok", "contentLength" to 4096L),
            mapOf<String, Any?>(
                "url" to "https://example.org", "status" to "empty", "contentLength" to 0L,
                "error" to "Stored page content is empty (0 bytes); re-fetch the URL with -refresh",
            ),
            mapOf<String, Any?>("url" to "https://example.net", "status" to "error", "error" to "unknown host"),
        )
        val summary = executor.exportSummary(results)

        // Three entries, three different outcomes: the 0-byte page is `empty`,
        // so it is neither a success nor a failure.
        assertEquals(3, summary["total"])
        assertEquals(1, summary["succeeded"])
        assertEquals(1, summary["empty"])
        assertEquals(1, summary["failed"])

        val json = ai.platon.pulsar.common.serialize.json.pulsarObjectMapper().writeValueAsString(summary)
        assertEquals(
            emptyList<String>(),
            ToolResultValidator.validate(spec, ToolResultValidator.parse(json)).map { "${it.path} ${it.message}" },
            "the export summary must satisfy its own schema: $json",
        )

        // The schema has teeth: an entry without its url is not a valid export result.
        val truncated = """{"total":1,"succeeded":1,"failed":0,"results":[{"status":"ok"}]}"""
        assertEquals(
            true,
            ToolResultValidator.validate(spec, ToolResultValidator.parse(truncated)).isNotEmpty(),
            "an entry missing 'url' must be reported as a violation",
        )
    }
}
