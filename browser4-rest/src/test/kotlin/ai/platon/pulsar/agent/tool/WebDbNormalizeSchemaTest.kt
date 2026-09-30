package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.rest.mcp.contract.ToolRegistryFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * `webdb.normalize` answers with the canonical url *and* the storage key the
 * page is filed under — the name `webdb.export` writes it to disk with.  That
 * makes the result an object with a declared contract instead of a bare string.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("webdb normalize result contract")
class WebDbNormalizeSchemaTest {

    private fun executor() = ToolRegistryFixture.executors()
        .filterIsInstance<WebDbToolExecutor>()
        .single()

    private fun normalizeSpec() = executor().getToolSpecs().getValue("normalize")

    @Test
    @DisplayName("the advertised spec declares a result schema")
    fun normalizeDeclaresItsResultSchema() {
        assertNotNull(
            normalizeSpec().outputSchema,
            "webdb.normalize returns JSON, so it must declare its result contract",
        )
    }

    @Test
    @DisplayName("the normalize payload satisfies the schema, and one without the storage key is rejected")
    fun payloadMatchesTheSchema() {
        val spec = normalizeSpec()
        val payload = """{"url":"http://localhost:18080/ec/dp/B0HLT0001",""" +
            """"normalizedUrl":"http://localhost:18080/ec/dp/B0HLT0001",""" +
            """"storageKey":"localhost_18080_ec_dp_B0HLT0001.htm"}"""

        assertEquals(
            emptyList<String>(),
            ToolResultValidator.validate(spec, ToolResultValidator.parse(payload)).map { "${it.path} ${it.message}" },
            "the normalize payload must satisfy its own schema: $payload",
        )

        // The schema has teeth: the storage key is part of the answer, not decoration.
        val withoutKey = """{"url":"http://a","normalizedUrl":"http://a"}"""
        assertEquals(
            true,
            ToolResultValidator.validate(spec, ToolResultValidator.parse(withoutKey)).isNotEmpty(),
            "a payload without the storage key must be reported as a violation",
        )
    }

    @Test
    @DisplayName("the storage key is the file name a webdb export writes the page to")
    fun storageKeyMatchesTheExportFileName() {
        assertEquals(
            "localhost_18080_ec_dp_B0HLT0001.htm",
            executor().sanitizeFilename("http://localhost:18080/ec/dp/B0HLT0001"),
        )
    }
}
