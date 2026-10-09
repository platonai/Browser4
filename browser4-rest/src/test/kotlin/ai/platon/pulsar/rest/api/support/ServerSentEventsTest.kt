package ai.platon.pulsar.rest.api.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bridge both SSE endpoints share — and the two properties a client depends on: an
 * event per value with the flow's end completing the stream, and disposal cancelling the
 * polling instead of leaving a coroutine behind on the owner's scope.
 *
 * The bridge is collected on a scope of its own, and read from the test thread: an SSE
 * endpoint's polling does not run on the thread that subscribed.
 * */
@Tag("Unit")
@Tag("Fast")
@DisplayName("the Flow → SSE bridge")
class ServerSentEventsTest {

    private val logger = LoggerFactory.getLogger(ServerSentEventsTest::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    @DisplayName("every value is forwarded in order, and the flow's end completes the stream")
    fun valuesAreForwardedInOrder() {
        val events = flowOf(1, 2, 3)
            .toServerSentEvents(scope, logger)
            .collectList()
            .block(Duration.ofSeconds(5))
            ?: error("the flow completed, so the stream must complete with its events")

        assertEquals(listOf(1, 2, 3), events.map { it.data() })
    }

    @Test
    @DisplayName("the event shape belongs to the caller, so the two endpoints keep their own")
    fun theEventShapeStaysWithTheCaller() {
        // The command stream: data only, because its JavaScript client parses nothing else.
        val command = flowOf(1).toServerSentEvents(scope, logger).blockLast(Duration.ofSeconds(5))!!
        assertNull(command.id(), "no event id on the command stream")
        assertNull(command.event(), "no event name on the command stream")

        // The scrape stream: the response's own id and event name travel with it.
        val scrape = flowOf(1)
            .toServerSentEvents(scope, logger) {
                ServerSentEvent.builder(it).id("response-$it").event("progress").build()
            }
            .blockLast(Duration.ofSeconds(5))!!
        assertEquals("response-1", scrape.id())
        assertEquals("progress", scrape.event())
        assertEquals(1, scrape.data())
    }

    @Test
    @DisplayName("a failing flow fails the stream instead of ending it quietly")
    fun aFailingFlowFailsTheStream() {
        // This is the point of the bridge owning the ordering: `/api/commands/{id}/stream`
        // used to complete the sink first and report the failure second, so a broken status
        // flow reached the client as a clean end and the error only reached the log.
        val failure = assertThrows(IllegalStateException::class.java) {
            flow<Int> {
                emit(1)
                throw IllegalStateException("the status cache is gone")
            }.toServerSentEvents(scope, logger).blockLast(Duration.ofSeconds(5))
        }

        // The message is asserted, not just the type: a blocking read that times out throws
        // IllegalStateException too, and a timeout is the failure this test exists to catch.
        assertEquals("the status cache is gone", failure.message)
    }

    @Test
    @DisplayName("a client that goes away cancels the polling instead of leaking it")
    fun disposingCancelsThePolling() {
        val cancelled = AtomicBoolean(false)
        val stream = flow {
            try {
                emit(1)
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }.toServerSentEvents(scope, logger)

        val received = AtomicBoolean(false)
        val subscription = stream.subscribe { received.set(true) }

        try {
            assertTrue(waitUntil { received.get() }, "the first event must arrive before dispose")
        } finally {
            subscription.dispose()
        }

        // The polling belongs to the subscription: once the client is gone, the coroutine
        // collecting the flow must be cancelled, or every dropped SSE client leaks one.
        assertTrue(waitUntil { cancelled.get() }, "dispose must cancel the collecting coroutine")
    }

    /** Poll until [condition] holds, so a slow CI runner is not a flaky assertion. */
    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
