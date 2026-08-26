package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DisplayName("MemoryEvent JSON round-trip")
class MemoryEventTest {

    private val mapper = pulsarObjectMapper()

    @Test
    @DisplayName("round-trips every event type with its type discriminator")
    fun testRoundTripAllTypes() {
        val events = listOf(
            TaskStarted(1, 1000, "a1", "t1", "extract product", "cli", "https://example.com/dp/1"),
            ToolExecuted(2, 1001, "a1", "t1", "b4.run", """{"args":["snapshot"]}""", true, "ok", 123, "call-1"),
            PageViewed(3, 1002, "a1", "t1", "https://example.com/dp/1", "Product", "full", "abc123"),
            TextEmitted(4, 1003, "a1", "t1", "report", "done"),
            NoteWritten(5, 1004, "a1", "t1", "assumption", "page uses shadows"),
            Completed(6, 1005, "a1", "t1", "extracted", listOf("title"), listOf("f.txt"), listOf("none"), "success", 9000),
            Failed(7, 1006, "a1", "t1", "selector not found", "SELECTOR_DRIFT", 3),
        )

        events.forEach { event ->
            val json = mapper.writeValueAsString(event)
            val back = mapper.readValue(json, MemoryEvent::class.java)
            assertEquals(event, back, "round-trip failed for ${event::class.simpleName}: $json")
        }
    }

    @Test
    @DisplayName("type discriminator is the class name constant")
    fun testTypeDiscriminator() {
        val json = mapper.writeValueAsString(TaskStarted(1, 0, "a", "t", "x", "cli", null))
        assertNotNull(json.contains("\"type\":\"task_started\"").takeIf { it })
        val failedJson = mapper.writeValueAsString(Failed(2, 0, "a", "t", "err", null, 0))
        assertEquals(true, failedJson.contains("\"type\":\"failed\""))
    }

    @Test
    @DisplayName("fromJson helpers accept the serialized form")
    fun testHelpers() {
        val event = NoteWritten(5, 1004, "a1", "t1", "k", "v")
        val json = event.toJson(mapper)
        val back = json.toMemoryEvent(mapper)
        assertNotNull(back)
        assertEquals(event, back)
        assertNull("garbage".toMemoryEvent(mapper))
    }
}
