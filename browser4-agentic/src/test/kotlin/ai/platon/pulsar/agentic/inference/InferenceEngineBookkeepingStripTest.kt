package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the extract payload contract at the assembly site: live model
 * responses can echo engine bookkeeping (token/timing counters and an
 * evaluator-shaped `metadata{progress,completed}` object) back into the data
 * payload.  [InferenceEngine.stripInferenceBookkeeping] must remove exactly
 * those keys recursively so the payload contains only the requested schema
 * fields (the ExtractResultEnvelopeTest asserts the same at the envelope).
 */
class InferenceEngineBookkeepingStripTest {

    private val mapper = pulsarObjectMapper()

    private fun strip(json: String): ObjectNode {
        val node = mapper.readTree(json) as ObjectNode
        InferenceEngine.stripInferenceBookkeeping(node)
        return node
    }

    @Test
    fun `strips token and timing counters from the top level`() {
        val node = strip(
            """
            {"title":"4K OLED TV 55","price":"${'$'}899.99",
             "inputToken":1930,"outputToken":12140,"totalToken":14070,
             "inferenceTimeMillis":90140}
            """.trimIndent()
        )
        assertEquals("4K OLED TV 55", node["title"].asText())
        assertFalse(node.has("inputToken"))
        assertFalse(node.has("outputToken"))
        assertFalse(node.has("totalToken"))
        assertFalse(node.has("inferenceTimeMillis"))
    }

    @Test
    fun `strips evaluator-shaped metadata object`() {
        val node = strip(
            """{"title":"T","metadata":{"progress":"","completed":false}}"""
        )
        assertEquals("T", node["title"].asText())
        assertFalse(node.has("metadata"))
    }

    @Test
    fun `keeps a legitimate user metadata field without bookkeeping shape`() {
        val node = strip(
            """{"title":"T","metadata":{"author":"Ada","pages":3}}"""
        )
        assertTrue(node.has("metadata"), "user metadata without progress/completed must survive")
        assertEquals("Ada", node["metadata"]["author"].asText())
    }

    @Test
    fun `strips bookkeeping nested inside arrays and objects`() {
        val node = strip(
            """
            {"products":[
               {"title":"A","metadata":{"progress":"","completed":false},
                "inputToken":1,"inferenceTimeMillis":2},
               {"title":"B","price":5}
             ],
             "nested":{"totalToken":9,"ok":true}}
            """.trimIndent()
        )
        assertFalse(node["products"][0].has("metadata"))
        assertFalse(node["products"][0].has("inputToken"))
        assertFalse(node["products"][0].has("inferenceTimeMillis"))
        assertEquals("B", node["products"][1]["title"].asText())
        assertFalse(node["nested"].has("totalToken"))
        assertTrue(node["nested"]["ok"].asBoolean())
    }

    @Test
    fun `leaves clean schema payloads untouched`() {
        val source = """{"title":"T","price":"1","features":["a","b"]}"""
        val node = strip(source)
        assertEquals(mapper.readTree(source), node)
    }
}
