package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.ExtractResult
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The renderer is what makes a tool result portable between the standard MCP
 * server and the private REST dispatcher: both must produce the same text for
 * the same evaluation.
 */
@DisplayName("ToolResultTextRenderer")
class ToolResultTextRendererTest {

    @Test
    @DisplayName("strings pass through unchanged")
    fun stringsPassThroughUnchanged() {
        assertEquals("hello", ToolResultTextRenderer.render("hello"))
    }

    @Test
    @DisplayName("numbers and booleans render as plain text")
    fun numbersAndBooleansRenderAsPlainText() {
        assertEquals("42", ToolResultTextRenderer.render(42))
        assertEquals("1.5", ToolResultTextRenderer.render(1.5))
        assertEquals("true", ToolResultTextRenderer.render(true))
    }

    @Test
    @DisplayName("maps and collections render as JSON, never as JVM toString")
    fun mapsAndCollectionsRenderAsJson() {
        val text = ToolResultTextRenderer.render(
            listOf(mapOf("index" to 0, "guid" to "abc", "url" to "https://example.com"))
        )

        assertTrue(text.startsWith("["), "Expected a JSON array, got: $text")
        assertTrue(text.contains("\"guid\":\"abc\""), "Expected JSON fields, got: $text")
        assertFalse(text.contains("index=0"), "Must not leak the JVM toString form: $text")
    }

    @Test
    @DisplayName("JS null renders as null while undefined and Unit render as empty")
    fun nullAndUndefinedAreDistinguished() {
        assertEquals("null", ToolResultTextRenderer.render(null, "null"))
        assertEquals("", ToolResultTextRenderer.render(null, "undefined"))
        assertEquals("", ToolResultTextRenderer.render(null, null))
    }

    @Test
    @DisplayName("an evaluation envelope renders its value with its own class name")
    fun evaluationEnvelopeIsRendered() {
        assertEquals("null", ToolResultTextRenderer.render(TcEvaluate(value = null, className = "null")))
        assertEquals("", ToolResultTextRenderer.render(TcEvaluate(value = null, className = "undefined")))
        assertEquals("Welcome", ToolResultTextRenderer.render(TcEvaluate(value = "Welcome", className = "String")))
    }

    @Test
    @DisplayName("ExtractResult renders as a success/message/data envelope")
    fun extractResultRendersAsEnvelope() {
        val data = pulsarObjectMapper().readTree("""{"title":"Example Domain"}""")
        val result = ExtractResult(success = true, message = "extracted", data = data)

        val text = ToolResultTextRenderer.render(result)

        assertTrue(text.contains("\"success\":true"), text)
        assertTrue(text.contains("\"message\":\"extracted\""), text)
        assertTrue(text.contains("\"title\":\"Example Domain\""), text)
    }

    @Test
    @DisplayName("non-serializable objects are wrapped in a description envelope")
    fun unserializableObjectsAreWrapped() {
        val text = ToolResultTextRenderer.render(Any(), "java.lang.Object")

        assertTrue(text.contains("\"type\":\"java.lang.Object\""), text)
        assertTrue(text.contains("\"description\""), text)
    }
}
