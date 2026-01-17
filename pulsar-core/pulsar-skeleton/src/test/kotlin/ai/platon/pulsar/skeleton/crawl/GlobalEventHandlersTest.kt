package ai.platon.pulsar.skeleton.crawl

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GlobalEventHandlersTest {
    private val conf = ImmutableConfig()

    @BeforeEach
    fun setup() {
        // Clean up before each test
        GlobalEventHandlers.pageEventHandlers = null
        GlobalEventHandlers.serverSideEventHandlers = null
        GlobalEventHandlers.eventFilter = null
        GlobalEventHandlers.clearEventHistory()
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = false))
    }

    @AfterEach
    fun tearDown() {
        // Clean up after each test
        GlobalEventHandlers.pageEventHandlers = null
        GlobalEventHandlers.serverSideEventHandlers = null
        GlobalEventHandlers.eventFilter = null
        GlobalEventHandlers.clearEventHistory()
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = false))
    }

    @Test
    fun `test thread-safe handler access`() {
        val handlers = DefaultPageEventHandlers()
        GlobalEventHandlers.pageEventHandlers = handlers

        assertSame(handlers, GlobalEventHandlers.pageEventHandlers)

        GlobalEventHandlers.pageEventHandlers = null
        assertNull(GlobalEventHandlers.pageEventHandlers)
    }

    @Test
    fun `test withHandlers scoped execution`() {
        val originalHandlers = DefaultPageEventHandlers()
        val tempHandlers = DefaultPageEventHandlers()

        GlobalEventHandlers.pageEventHandlers = originalHandlers

        var insideBlock = false
        GlobalEventHandlers.withHandlers(tempHandlers) {
            insideBlock = true
            assertSame(tempHandlers, GlobalEventHandlers.pageEventHandlers)
        }

        assertTrue(insideBlock)
        assertSame(originalHandlers, GlobalEventHandlers.pageEventHandlers)
    }

    @Test
    fun `test withHandlers restores on exception`() {
        val originalHandlers = DefaultPageEventHandlers()
        val tempHandlers = DefaultPageEventHandlers()

        GlobalEventHandlers.pageEventHandlers = originalHandlers

        assertThrows(RuntimeException::class.java) {
            GlobalEventHandlers.withHandlers(tempHandlers) {
                assertSame(tempHandlers, GlobalEventHandlers.pageEventHandlers)
                throw RuntimeException("Test exception")
            }
        }

        // Should restore original handlers even after exception
        assertSame(originalHandlers, GlobalEventHandlers.pageEventHandlers)
    }

    @Test
    fun `test withServerSideHandlers scoped execution`() {
        val originalHandlers = DefaultServerSideEventHandlers()
        val tempHandlers = DefaultServerSideEventHandlers()

        GlobalEventHandlers.serverSideEventHandlers = originalHandlers

        GlobalEventHandlers.withServerSideHandlers(tempHandlers) {
            assertSame(tempHandlers, GlobalEventHandlers.serverSideEventHandlers)
        }

        assertSame(originalHandlers, GlobalEventHandlers.serverSideEventHandlers)
    }

    @Test
    fun `test withBothHandlers scoped execution`() {
        val originalPageHandlers = DefaultPageEventHandlers()
        val originalServerHandlers = DefaultServerSideEventHandlers()
        val tempPageHandlers = DefaultPageEventHandlers()
        val tempServerHandlers = DefaultServerSideEventHandlers()

        GlobalEventHandlers.pageEventHandlers = originalPageHandlers
        GlobalEventHandlers.serverSideEventHandlers = originalServerHandlers

        GlobalEventHandlers.withBothHandlers(tempPageHandlers, tempServerHandlers) {
            assertSame(tempPageHandlers, GlobalEventHandlers.pageEventHandlers)
            assertSame(tempServerHandlers, GlobalEventHandlers.serverSideEventHandlers)
        }

        assertSame(originalPageHandlers, GlobalEventHandlers.pageEventHandlers)
        assertSame(originalServerHandlers, GlobalEventHandlers.serverSideEventHandlers)
    }

    @Test
    fun `test event filtering by event type`() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        GlobalEventHandlers.serverSideEventHandlers = handlers

        // Filter to only allow "onWillLoad" events
        GlobalEventHandlers.eventFilter = { eventType, _, _ ->
            eventType == "onWillLoad"
        }

        val events = mutableListOf<ServerSideEvent>()
        val job = launch {
            handlers.eventFlow.take(1).collect { events.add(it) }
        }

        delay(50) // Give collector time to subscribe

        // Emit multiple events, only onWillLoad should pass
        val emitJob = launch {
            GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com")
            GlobalEventHandlers.emitCrawlEvent("onLoaded", "https://example.com")
            GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example2.com")
        }

        emitJob.join()
        delay(100) // Wait for events to be processed
        job.cancelAndJoin()

        // Should only receive the onWillLoad events
        assertTrue(events.size >= 1)
        assertTrue(events.all { it.eventType == "onWillLoad" })
    }

    @Test
    fun `test event filtering by phase`() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        GlobalEventHandlers.serverSideEventHandlers = handlers
        val page = GoraWebPage.newWebPage("https://example.com", conf.toVolatileConfig())

        // Filter to only allow "load" phase events
        GlobalEventHandlers.eventFilter = { _, phase, _ ->
            phase == "load"
        }

        val events = mutableListOf<ServerSideEvent>()
        val job = launch {
            handlers.eventFlow.take(2).collect { events.add(it) }
        }

        delay(50)

        val emitJob = launch {
            GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com") // Should be filtered
            GlobalEventHandlers.emitLoadEvent("onWillFetch", page) // Should pass
            GlobalEventHandlers.emitLoadEvent("onFetched", page) // Should pass
            GlobalEventHandlers.emitBrowseEvent("onNavigated", page) // Should be filtered
        }

        emitJob.join()
        delay(100)
        job.cancelAndJoin()

        assertEquals(2, events.size)
        assertTrue(events.all { it.eventPhase == "load" })
    }

    @Test
    fun `test event filtering by URL`() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        GlobalEventHandlers.serverSideEventHandlers = handlers

        // Filter to only allow example.com URLs
        GlobalEventHandlers.eventFilter = { _, _, url ->
            url?.contains("example.com") == true
        }

        val events = mutableListOf<ServerSideEvent>()
        val job = launch {
            handlers.eventFlow.take(1).collect { events.add(it) }
        }

        delay(50)

        val emitJob = launch {
            GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://other.com") // Filtered
            GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com") // Pass
        }

        emitJob.join()
        delay(100)
        job.cancelAndJoin()

        assertEquals(1, events.size)
        assertTrue(events[0].url?.contains("example.com") == true)
    }

    @Test
    fun `test event history recording`() = runBlocking {
        GlobalEventHandlers.configureHistory(
            EventHistoryConfig(
                enabled = true,
                maxSize = 100
            )
        )

        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com", "Test message")
        GlobalEventHandlers.emitCrawlEvent("onLoaded", "https://example.com")

        delay(100) // Give time for async events to be recorded

        val history = GlobalEventHandlers.getEventHistory()
        assertTrue(history.size >= 2)
        assertEquals("onLoaded", history[0].eventType) // Most recent first
        assertEquals("onWillLoad", history[1].eventType)
    }

    @Test
    fun `test event history with max size`() = runBlocking {
        GlobalEventHandlers.configureHistory(
            EventHistoryConfig(
                enabled = true,
                maxSize = 3
            )
        )

        repeat(5) { i ->
            GlobalEventHandlers.emitCrawlEvent("event$i", "https://example.com")
        }

        delay(100)

        val history = GlobalEventHandlers.getEventHistory()
        assertTrue(history.size <= 3)
    }

    @Test
    fun `test event history filtering`() = runBlocking {
        GlobalEventHandlers.configureHistory(
            EventHistoryConfig(
                enabled = true,
                maxSize = 100,
                filter = { eventType, _, _ ->
                    eventType.startsWith("onWill")
                }
            )
        )

        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com")
        GlobalEventHandlers.emitCrawlEvent("onLoaded", "https://example.com")
        GlobalEventHandlers.emitCrawlEvent("onWillFetch", "https://example.com")

        delay(100)

        val history = GlobalEventHandlers.getEventHistory()
        assertTrue(history.all { it.eventType.startsWith("onWill") })
    }

    @Test
    fun `test event history query by event type`() = runBlocking {
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = true, maxSize = 100))

        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example1.com")
        GlobalEventHandlers.emitCrawlEvent("onLoaded", "https://example2.com")
        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example3.com")

        delay(100)

        val willLoadEvents = GlobalEventHandlers.getEventHistory(eventType = "onWillLoad")
        assertEquals(2, willLoadEvents.size)
        assertTrue(willLoadEvents.all { it.eventType == "onWillLoad" })
    }

    @Test
    fun `test event history query by phase`() = runBlocking {
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = true, maxSize = 100))
        val page = GoraWebPage.newWebPage("https://example.com", conf.toVolatileConfig())

        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com")
        GlobalEventHandlers.emitLoadEvent("onFetched", page)
        GlobalEventHandlers.emitBrowseEvent("onNavigated", page)

        delay(100)

        val loadEvents = GlobalEventHandlers.getEventHistory(eventPhase = "load")
        assertEquals(1, loadEvents.size)
        assertEquals("load", loadEvents[0].eventPhase)
    }

    @Test
    fun `test event history query with limit`() = runBlocking {
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = true, maxSize = 100))

        repeat(10) { i ->
            GlobalEventHandlers.emitCrawlEvent("event$i", "https://example.com")
        }

        delay(100)

        val limitedHistory = GlobalEventHandlers.getEventHistory(limit = 5)
        assertTrue(limitedHistory.size <= 5)
    }

    @Test
    fun `test clear event history`() = runBlocking {
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = true, maxSize = 100))

        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com")
        GlobalEventHandlers.emitCrawlEvent("onLoaded", "https://example.com")

        delay(100)

        assertTrue(GlobalEventHandlers.getEventHistory().isNotEmpty())

        GlobalEventHandlers.clearEventHistory()
        assertTrue(GlobalEventHandlers.getEventHistory().isEmpty())
    }

    @Test
    fun `test history disabled by default`() = runBlocking {
        // Default config has history disabled
        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com")

        delay(100)

        val history = GlobalEventHandlers.getEventHistory()
        assertTrue(history.isEmpty())
    }

    @Test
    fun `test disabling history clears existing records`() = runBlocking {
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = true, maxSize = 100))

        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com")

        delay(100)
        assertTrue(GlobalEventHandlers.getEventHistory().isNotEmpty())

        // Disable history should clear it
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = false))
        assertTrue(GlobalEventHandlers.getEventHistory().isEmpty())
    }

    @Test
    fun `test event filter and history work together`() = runBlocking {
        // Configure filter to only allow crawl events
        GlobalEventHandlers.eventFilter = { _, phase, _ -> phase == "crawl" }

        // Configure history to record all events (but filter will prevent non-crawl)
        GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = true, maxSize = 100))

        val page = GoraWebPage.newWebPage("https://example.com", conf.toVolatileConfig())

        GlobalEventHandlers.emitCrawlEvent("onWillLoad", "https://example.com")
        GlobalEventHandlers.emitLoadEvent("onFetched", page) // Should be filtered
        GlobalEventHandlers.emitCrawlEvent("onLoaded", "https://example.com")

        delay(100)

        val history = GlobalEventHandlers.getEventHistory()
        assertTrue(history.all { it.eventPhase == "crawl" })
    }

    @Test
    fun `test EventHistoryRecord creation`() {
        val record = EventHistoryRecord(
            eventType = "onWillLoad",
            eventPhase = "crawl",
            url = "https://example.com",
            message = "Test message",
            metadata = mapOf("key" to "value")
        )

        assertEquals("onWillLoad", record.eventType)
        assertEquals("crawl", record.eventPhase)
        assertEquals("https://example.com", record.url)
        assertEquals("Test message", record.message)
        assertEquals("value", record.metadata["key"])
        assertNotNull(record.timestamp)
    }

    @Test
    fun `test EventHistoryConfig defaults`() {
        val config = EventHistoryConfig()

        assertFalse(config.enabled)
        assertEquals(1000, config.maxSize)
        assertNull(config.filter)
    }
}
