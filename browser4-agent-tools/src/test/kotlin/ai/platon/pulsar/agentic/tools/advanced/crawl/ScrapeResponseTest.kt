package ai.platon.pulsar.agentic.tools.advanced.crawl

import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ScrapeResponseTest {

    private val objectMapper = pulsarObjectMapper()

    // -----------------------------------------------------------------
    // Timing: pickup vs queue wait
    // -----------------------------------------------------------------

    @Test
    fun `the first event stamps startedTime at pickup`() {
        val response = ScrapeResponse(id = "t-timing", statusCode = ResourceStatus.SC_CREATED)

        // A task that sat in the queue: submitted, then picked up later.
        val submitted = Instant.now().minusSeconds(30)
        response.createdTime = submitted
        assertNull(response.startedTime, "a queued task has not started")

        response.emitEvent("onWillLoad")

        assertNotNull(response.startedTime, "the first event marks the pickup instant")
        assertTrue(
            response.startedTime!!.isAfter(submitted),
            "pickup must be later than submission, got startedTime=${response.startedTime}"
        )
        assertTrue(response.queuedMillis!! >= 29_000, "the queue wait must be visible: ${response.queuedMillis}")
    }

    @Test
    fun `durationMillis measures processing, not queue wait`() {
        val response = ScrapeResponse(id = "t-duration", statusCode = ResourceStatus.SC_OK)
        response.createdTime = Instant.now().minusSeconds(60)
        response.startedTime = Instant.now().minusSeconds(2)
        response.finishTime = Instant.now()

        val duration = response.durationMillis!!
        assertTrue(duration in 1_500..3_000, "processing time should be ~2s, got $duration")
        val queued = response.queuedMillis!!
        assertTrue(queued >= 57_000, "queue wait should be ~58s, got $queued")
        val elapsed = response.elapsedMillis!!
        assertTrue(elapsed >= 59_000, "total time to result should be ~60s, got $elapsed")
        assertTrue(elapsed >= duration + queued - 10, "elapsed must cover queue + processing")
    }

    @Test
    fun `emitEvent does not move an already stamped startedTime`() {
        val response = ScrapeResponse(id = "t-stable", statusCode = ResourceStatus.SC_OK)
        val pickup = Instant.now().minusSeconds(10)
        response.startedTime = pickup

        response.emitEvent("onLoaded")
        response.emitEvent("extract")

        assertEquals(pickup, response.startedTime, "later events must not re-stamp the pickup instant")
    }

    // -----------------------------------------------------------------
    // isDone serialization
    // -----------------------------------------------------------------

    @Test
    fun `isDone false is serialized in JSON output`() {
        val response = ScrapeResponse(
            id = "task-1",
            statusCode = ResourceStatus.SC_CREATED,
            pageStatusCode = ProtocolStatusCodes.SC_CREATED
        )
        val json = objectMapper.writeValueAsString(response)

        assertTrue(json.contains("\"isDone\""), "isDone should always be present, even when false")
        assertTrue(json.contains("\"isDone\":false"), "isDone=false should be serialized")
    }

    @Test
    fun `isDone true is serialized in JSON output`() {
        val response = ScrapeResponse(
            id = "task-2",
            statusCode = ResourceStatus.SC_OK,
            pageStatusCode = ProtocolStatusCodes.SC_OK
        )
        response.isDone = true
        val json = objectMapper.writeValueAsString(response)

        assertTrue(json.contains("\"isDone\""), "isDone should be present when true")
        assertTrue(json.contains("\"isDone\":true"), "isDone=true should be serialized")
    }

    @Test
    fun `isDone survives roundtrip through JSON`() {
        val response = ScrapeResponse(
            id = "task-3",
            statusCode = ResourceStatus.SC_OK,
            pageStatusCode = ProtocolStatusCodes.SC_OK
        )
        response.isDone = true

        val json = objectMapper.writeValueAsString(response)
        val deserialized = objectMapper.readValue(json, ScrapeResponse::class.java)

        assertTrue(deserialized.isDone, "isDone should survive roundtrip")
        assertEquals("task-3", deserialized.id)
    }

    // -----------------------------------------------------------------
    // Timestamps
    // -----------------------------------------------------------------

    @Test
    fun `createdTime is set automatically on construction`() {
        val before = Instant.now()
        val response = ScrapeResponse(id = "task-4")
        val after = Instant.now()

        assertNotNull(response.createdTime, "createdTime should be set by default")
        assertFalse(response.createdTime!!.isBefore(before), "createdTime should be >= before")
        assertFalse(response.createdTime!!.isAfter(after), "createdTime should be <= after")
    }

    @Test
    fun `notFound response has null createdTime`() {
        val response = ScrapeResponse.notFound("nf-1")

        assertNull(response.createdTime, "notFound tasks should have null createdTime")
        assertEquals(ResourceStatus.SC_NOT_FOUND, response.statusCode)
    }

    @Test
    fun `startedTime is null before first refresh`() {
        val response = ScrapeResponse(id = "task-5")

        assertNull(response.startedTime, "startedTime should be null before any refresh")
    }

    @Test
    fun `startedTime is set on first refresh`() {
        val response = ScrapeResponse(id = "task-6")
        response.refresh(ResourceStatus.SC_PROCESSING)

        assertNotNull(response.startedTime, "startedTime should be set after first refresh")
    }

    @Test
    fun `startedTime is preserved across multiple refreshes`() {
        val response = ScrapeResponse(id = "task-7")
        response.refresh(ResourceStatus.SC_PROCESSING)
        val first = response.startedTime

        Thread.sleep(5) // ensure time difference
        response.refresh(ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK, true)

        assertEquals(first, response.startedTime, "startedTime should not change on subsequent refreshes")
    }

    @Test
    fun `lastModifiedTime is updated on each refresh`() {
        val response = ScrapeResponse(id = "task-8")

        assertNull(response.lastModifiedTime, "lastModifiedTime should be null before refresh")

        response.refresh(ResourceStatus.SC_PROCESSING)
        val firstModified = response.lastModifiedTime
        assertNotNull(firstModified)

        Thread.sleep(5)
        response.refresh(ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK, true)
        val secondModified = response.lastModifiedTime
        assertNotNull(secondModified)
        assertTrue(secondModified!!.isAfter(firstModified), "lastModifiedTime should advance on each refresh")
    }

    @Test
    fun `finishTime is null until explicitly set`() {
        val response = ScrapeResponse(id = "task-9")

        assertNull(response.finishTime, "finishTime should be null before completion")
    }

    @Test
    fun `refresh with isDone does not set finishTime`() {
        val response = ScrapeResponse(id = "task-10")
        response.refresh(isDone = true)

        assertNull(response.finishTime, "finishTime is not set by refresh(); it is set by complete()")
        assertTrue(response.isDone)
    }
}
