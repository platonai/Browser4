package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * A synchronous page visit is a browser operation, so two things must hold: it must not
 * occupy the thread that asked for it (under Spring MVC that is a servlet worker, needed
 * back immediately), and it must not be startable an unbounded number of times — the page
 * load blocks a `Dispatchers.Default` thread, which is sized to the CPU count.
 *
 * The session is mocked and the visit is stopped at its first step: these are the
 * executor's scheduling decisions, not a browser test.
 * */
@Tag("Unit")
@Tag("Fast")
@DisplayName("sync page visit scheduling")
class UserCommandExecutorTest {

    private val sessionManager: PulsarSessionManager = Mockito.mock(PulsarSessionManager::class.java)

    private fun request() = PageVisitRequest(url = "https://example.com/product/1")

    private fun handlers() = PageEventHandlersFactory.create()

    @Test
    @DisplayName("a sync visit runs on the command dispatcher, not on the caller's thread")
    fun syncVisitLeavesTheCallersThread() {
        val threads = CopyOnWriteArrayList<String>()
        whenever(sessionManager.getOrCreateSession(any<String>(), anyOrNull())).thenAnswer {
            threads += Thread.currentThread().name
            // Stop the visit at its first step — a real one would need a browser.
            throw IllegalStateException("stop the visit here")
        }

        val executor = UserCommandExecutor(sessionManager)
        try {
            val caller = Thread.currentThread().name

            assertFailsWith<IllegalStateException> {
                runBlocking { executor.executePageVisitCommand("s1", request(), handlers()) }
            }

            assertEquals(1, threads.size, "the visit must have been attempted exactly once")
            assertNotEquals(caller, threads.single(), "the visit must not occupy the requesting thread")
        } finally {
            executor.close()
        }
    }

    @Test
    @DisplayName("at most MAX_CONCURRENT_COMMANDS sync visits run at the same time")
    fun syncVisitsAreBounded() {
        val parallelism = UserCommandExecutor.MAX_CONCURRENT_COMMANDS
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val release = CountDownLatch(1)
        whenever(sessionManager.getOrCreateSession(any<String>(), anyOrNull())).thenAnswer {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { current -> max(current, now) }
            try {
                release.await(10, TimeUnit.SECONDS)
            } finally {
                inFlight.decrementAndGet()
            }
            throw IllegalStateException("stop the visit here")
        }

        val executor = UserCommandExecutor(sessionManager)
        try {
            runBlocking {
                val visits = (1..(parallelism + 2)).map { i ->
                    async(Dispatchers.Default) {
                        runCatching { executor.executePageVisitCommand("s$i", request(), handlers()) }
                    }
                }

                // Every coroutine reaches the pool and takes a slot; the extra two wait for one.
                delay(1_000)
                assertEquals(parallelism, inFlight.get(), "the pool must be full, never over-subscribed")

                release.countDown()
                visits.awaitAll()
            }

            assertEquals(parallelism, peak.get(), "more visits ran at once than the bound allows")
        } finally {
            release.countDown()
            executor.close()
        }
    }
}
