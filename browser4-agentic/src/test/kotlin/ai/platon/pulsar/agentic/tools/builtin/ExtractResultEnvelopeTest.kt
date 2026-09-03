package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.ExtractResult
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the extract tool-result envelope contract:
 * - the serialized result is a single envelope `{type, description, completed}`
 *   whose `description` carries the CLEAN schema payload (extraction fields
 *   only — task bookkeeping such as token counts, timing and
 *   `metadata{progress,completed}` must never be merged into it), so consumers
 *   can parse the payload directly without a second parse;
 * - `completed` is truthful: `true` whenever usable content is present
 *   (a successful extraction never reports `completed: false`);
 * - failures keep the plain-text "Extract failed: …" description.
 */
class ExtractResultEnvelopeTest {

    /**
     * Minimal executor that returns a canned value — exercises the shared
     * [AbstractToolExecutor.callFunctionOn] result wrapping without any
     * driver or LLM dependencies.
     */
    private class StubExecutor : AbstractToolExecutor() {
        override val domain = "agent"
        override val receiverClass: kotlin.reflect.KClass<*> = PerceptiveAgent::class

        var returned: Any? = null

        override suspend fun callFunctionOn(
            domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
        ): Any? = returned
    }

    private val mapper = pulsarObjectMapper()

    @Test
    fun `extract envelope carries the clean payload with completed true`() {
        runBlocking {
            val executor = StubExecutor()
            executor.returned = ExtractResult(
                success = true,
                message = "OK",
                data = mapper.readTree(
                    """{"title":"4K OLED TV 55","price":"$899.99","features":["55 inch","HDR10+"]}"""
                ),
                completed = true
            )

            val tc = executor.callFunctionOn(
                ToolCall("agent", "extract", mutableMapOf("instruction" to "product info")),
                Any()
            )

            assertTrue(tc.success, "extract must succeed")
            @Suppress("UNCHECKED_CAST")
            val envelope = tc.value as Map<String, Any?>
            assertEquals("ai.platon.pulsar.agentic.ExtractResult", envelope["type"])
            assertEquals(true, envelope["completed"])

            // description holds the payload as a JSON *string*; it must parse to
            // the schema fields at the top level with no bookkeeping merged in.
            val description = envelope["description"] as String
            val payload = mapper.readTree(description)
            assertEquals("4K OLED TV 55", payload["title"].asText())
            assertEquals("$899.99", payload["price"].asText())
            assertEquals("55 inch", payload["features"][0].asText())
            assertFalse(payload.has("metadata"), "evaluation metadata must not leak into the payload")
            assertFalse(payload.has("inputToken"), "token counts must not leak into the payload")
            assertFalse(payload.has("outputToken"), "token counts must not leak into the payload")
            assertFalse(payload.has("totalToken"), "token counts must not leak into the payload")
            assertFalse(payload.has("inferenceTimeMillis"), "timing must not leak into the payload")
            assertFalse(description.contains("completed"), "completion flag must stay at the envelope level")
        }
    }

    @Test
    fun `extract envelope reports completed false only for a failed extraction`() {
        runBlocking {
            val executor = StubExecutor()
            executor.returned = ExtractResult(
                success = false,
                message = "LLM timed out",
                data = mapper.createObjectNode(),
                completed = false
            )

            val tc = executor.callFunctionOn(
                ToolCall("agent", "extract", mutableMapOf("instruction" to "product info")),
                Any()
            )

            assertTrue(tc.success, "pipeline failures are reported in-band, not as tool exceptions")
            @Suppress("UNCHECKED_CAST")
            val envelope = tc.value as Map<String, Any?>
            assertEquals(false, envelope["completed"])
            assertEquals("Extract failed: LLM timed out", envelope["description"])
        }
    }

    @Test
    fun `successful extraction defaults completed to true even without an explicit flag`() {
        runBlocking {
            val executor = StubExecutor()
            executor.returned = ExtractResult(
                success = true,
                message = "OK",
                data = mapper.readTree("""{"title":"T"}""")
            )

            val tc = executor.callFunctionOn(
                ToolCall("agent", "extract", mutableMapOf("instruction" to "info")),
                Any()
            )

            @Suppress("UNCHECKED_CAST")
            val envelope = tc.value as Map<String, Any?>
            assertEquals(true, envelope["completed"], "default completed must be true for a successful result")
            assertEquals("""{"title":"T"}""", envelope["description"])
        }
    }

    @Test
    fun `non-extract domain objects keep the plain two-field envelope`() {
        runBlocking {
            val executor = StubExecutor()
            executor.returned = Any()

            val tc = executor.callFunctionOn(
                ToolCall("browser", "anything", mutableMapOf()),
                Any()
            )

            @Suppress("UNCHECKED_CAST")
            val envelope = tc.value as Map<String, Any?>
            assertTrue((envelope["type"] as String).isNotBlank())
            assertFalse(envelope.containsKey("completed"), "only ExtractResult envelopes carry the completion flag")
        }
    }

    @Test
    fun `extract toString on success is exactly the clean payload json`() {
        val result = ExtractResult(
            success = true,
            message = "OK",
            data = mapper.readTree("""{"title":"4K OLED TV 55"}"""),
            completed = true
        )
        assertEquals("""{"title":"4K OLED TV 55"}""", result.toString())
        assertEquals(true, result.completed)
    }
}
