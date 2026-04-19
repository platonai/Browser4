package ai.platon.pulsar.skeleton.crawl

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import org.junit.jupiter.api.DisplayName

class ServerSideEventHandlersTest {
    private val conf = ImmutableConfig()

    @Test
        @DisplayName("test ServerSideEvent creation")
    fun testServersideeventCreation() {
        val event = ServerSideEvent(
            eventType = "onWillLoad",
            eventPhase = "crawl",
            url = "https://example.com",
            message = "Loading page"
        )

        assertEquals("onWillLoad", event.eventType)
        assertEquals("crawl", event.eventPhase)
        assertEquals("https://example.com", event.url)
        assertEquals("Loading page", event.message)
        assertTrue(event.timestamp <= Instant.now())
    }

    @Test
        @DisplayName("test DefaultServerSideEventHandlers emits crawl events")
    fun testDefaultserversideeventhandlersEmitsCrawlEvents() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        val events = mutableListOf<ServerSideEvent>()
        val readyLatch = CompletableDeferred<Unit>()
        val allEventsReceived = CompletableDeferred<Unit>()

        // Collect events in the background
        val job = launch {
            readyLatch.complete(Unit) // Signal that collector is ready
            handlers.eventFlow.take(2).toList().let { events.addAll(it) }
            allEventsReceived.complete(Unit)
        }

        // Wait for collector to be ready
        readyLatch.await()
        delay(50) // Give collector time to subscribe

        // Emit events from within async to ensure proper suspension
        val emitJob = launch {
            handlers.onCrawlEvent("onWillLoad", "https://example.com")
            handlers.onCrawlEvent("onLoaded", "https://example.com", "Page loaded successfully")
        }

        emitJob.join()
        allEventsReceived.await()
        job.join()

        assertEquals(2, events.size)
        assertEquals("onWillLoad", events[0].eventType)
        assertEquals("crawl", events[0].eventPhase)
        assertEquals("https://example.com", events[0].url)

        assertEquals("onLoaded", events[1].eventType)
        assertEquals("crawl", events[1].eventPhase)
        assertEquals("Page loaded successfully", events[1].message)
    }

    @Test
        @DisplayName("test DefaultServerSideEventHandlers emits load events")
    fun testDefaultserversideeventhandlersEmitsLoadEvents() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        val events = mutableListOf<ServerSideEvent>()
        val page = GoraWebPage.newWebPage("https://example.com", conf.toVolatileConfig())
        val allEventsReceived = CompletableDeferred<Unit>()

        // Collect events in the background
        val job = launch {
            handlers.eventFlow.take(2).toList().let { events.addAll(it) }
            allEventsReceived.complete(Unit)
        }

        delay(50) // Give collector time to subscribe

        // Emit events
        val emitJob = launch {
            handlers.onLoadEvent("onWillFetch", page)
            handlers.onLoadEvent("onFetched", page, "Fetch completed")
        }

        emitJob.join()
        allEventsReceived.await()
        job.join()

        assertEquals(2, events.size)
        assertEquals("onWillFetch", events[0].eventType)
        assertEquals("load", events[0].eventPhase)
        assertEquals("https://example.com", events[0].url)

        assertEquals("onFetched", events[1].eventType)
        assertEquals("load", events[1].eventPhase)
        assertEquals("Fetch completed", events[1].message)
    }

    @Test
        @DisplayName("test DefaultServerSideEventHandlers emits browse events")
    fun testDefaultserversideeventhandlersEmitsBrowseEvents() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        val events = mutableListOf<ServerSideEvent>()
        val page = GoraWebPage.newWebPage("https://example.com", conf.toVolatileConfig())
        val allEventsReceived = CompletableDeferred<Unit>()

        // Collect events in the background
        val job = launch {
            handlers.eventFlow.take(2).toList().let { events.addAll(it) }
            allEventsReceived.complete(Unit)
        }

        delay(50) // Give collector time to subscribe

        // Emit events
        val emitJob = launch {
            handlers.onBrowseEvent("onBrowserLaunched", page)
            handlers.onBrowseEvent("onNavigated", page, "Navigation completed")
        }

        emitJob.join()
        allEventsReceived.await()
        job.join()

        assertEquals(2, events.size)
        assertEquals("onBrowserLaunched", events[0].eventType)
        assertEquals("browse", events[0].eventPhase)
        assertEquals("https://example.com", events[0].url)

        assertEquals("onNavigated", events[1].eventType)
        assertEquals("browse", events[1].eventPhase)
        assertEquals("Navigation completed", events[1].message)
    }

    @Test
        @DisplayName("test DefaultServerSideEventHandlers emits generic events")
    fun testDefaultserversideeventhandlersEmitsGenericEvents() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        val events = mutableListOf<ServerSideEvent>()
        val allEventsReceived = CompletableDeferred<Unit>()

        // Collect events in the background
        val job = launch {
            handlers.eventFlow.take(1).toList().let { events.addAll(it) }
            allEventsReceived.complete(Unit)
        }

        delay(50) // Give collector time to subscribe

        // Emit a generic event
        val emitJob = launch {
            handlers.onEvent(
                eventType = "customEvent",
                eventPhase = "custom",
                url = "https://example.com",
                message = "Custom event message",
                metadata = mapOf("key" to "value")
            )
        }

        emitJob.join()
        allEventsReceived.await()
        job.join()

        assertEquals(1, events.size)
        assertEquals("customEvent", events[0].eventType)
        assertEquals("custom", events[0].eventPhase)
        assertEquals("https://example.com", events[0].url)
        assertEquals("Custom event message", events[0].message)
        assertEquals("value", events[0].metadata["key"])
    }

    @Test
        @DisplayName("test event flow buffering with replay 0")
    fun testEventFlowBufferingWithReplay0() = runBlocking {
        val handlers = DefaultServerSideEventHandlers(replay = 0)

        // Emit events before any collector is attached
        handlers.onCrawlEvent("event1", "https://example.com")
        handlers.onCrawlEvent("event2", "https://example.com")

        delay(50) // Ensure events are processed

        // Start collecting - with replay = 0, should not receive old events
        val events = mutableListOf<ServerSideEvent>()
        val allEventsReceived = CompletableDeferred<Unit>()
        val job = launch {
            handlers.eventFlow.take(2).toList().let { events.addAll(it) }
            allEventsReceived.complete(Unit)
        }

        delay(50) // Give collector time to subscribe

        // Emit new events
        val emitJob = launch {
            handlers.onCrawlEvent("event3", "https://example.com")
            handlers.onCrawlEvent("event4", "https://example.com")
        }

        emitJob.join()
        allEventsReceived.await()
        job.join()

        // With replay = 0, should only get the new events emitted after subscription
        assertEquals(2, events.size)
        assertEquals("event3", events[0].eventType)
        assertEquals("event4", events[1].eventType)
    }

    @Test
        @DisplayName("test concurrent event emission")
    fun testConcurrentEventEmission() = runBlocking {
        val handlers = DefaultServerSideEventHandlers()
        val eventCount = 10
        val events = mutableListOf<ServerSideEvent>()
        val allEventsReceived = CompletableDeferred<Unit>()

        // Start collecting
        val collectJob = launch {
            handlers.eventFlow.take(eventCount).toList().let { events.addAll(it) }
            allEventsReceived.complete(Unit)
        }

        delay(50) // Give collector time to subscribe

        // Emit events concurrently
        val emitJobs = (1..eventCount).map { i ->
            launch {
                handlers.onCrawlEvent("event$i", "https://example.com/$i")
            }
        }

        emitJobs.joinAll()
        allEventsReceived.await()
        collectJob.join()

        assertEquals(eventCount, events.size)
        events.forEachIndexed { index, event ->
            assertEquals("event${index + 1}", event.eventType)
        }
    }
}
