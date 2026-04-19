package ai.platon.pulsar.skeleton.crawl

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class PulsarEventBusContextHandlersTest {

    private class RecordingHandlers(private val id: String) : ServerSideEventHandlers {
        private val events = mutableListOf<String>()

        override val eventFlow: SharedFlow<ServerSideEvent> = MutableSharedFlow()

        override suspend fun onCrawlEvent(
            eventType: String,
            url: String?,
            message: String?,
            metadata: Map<String, Any?>
        ) {
            events.add("$id:$eventType")
        }

        override suspend fun onLoadEvent(
            eventType: String,
            page: ai.platon.pulsar.persist.WebPage,
            message: String?,
            metadata: Map<String, Any?>
        ) {
            events.add("$id:$eventType")
        }

        override suspend fun onBrowseEvent(
            eventType: String,
            page: ai.platon.pulsar.persist.WebPage,
            message: String?,
            metadata: Map<String, Any?>
        ) {
            events.add("$id:$eventType")
        }

        override suspend fun onEvent(
            eventType: String,
            eventPhase: String,
            url: String?,
            message: String?,
            metadata: Map<String, Any?>
        ) {
            events.add("$id:$eventPhase:$eventType")
        }

        fun snapshot(): List<String> = events.toList()
    }

    @Test
        @DisplayName("withServerSideEventHandlers should isolate handlers between concurrent coroutines")
    fun withserversideeventhandlersShouldIsolateHandlersBetweenConcurrentCoroutines() = runBlocking {
        val h1 = RecordingHandlers("h1")
        val h2 = RecordingHandlers("h2")

        val j1 = async {
            PulsarEventBus.withServerSideEventHandlers(h1) {
                PulsarEventBus.emitCrawlEvent("e1")
                delay(50)
                PulsarEventBus.emitCrawlEvent("e2")
                delay(50)
            }
        }

        val j2 = async {
            PulsarEventBus.withServerSideEventHandlers(h2) {
                PulsarEventBus.emitCrawlEvent("e1")
                delay(50)
                PulsarEventBus.emitCrawlEvent("e2")
                delay(50)
            }
        }

        j1.await()
        j2.await()

        // Events are emitted asynchronously; give the EventBus background scope a moment.
        delay(200)

        assertEquals(listOf("h1:e1", "h1:e2"), h1.snapshot())
        assertEquals(listOf("h2:e1", "h2:e2"), h2.snapshot())
    }
}
