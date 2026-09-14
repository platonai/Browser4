package ai.platon.pulsar.rest.api.service.crawl

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the seed-level scheduler that turns "crawl these N independent units"
 * into overlapping work.
 *
 * Pure coroutine tests — no browser and no server — so the scheduler's contract
 * (ceiling honored, input order preserved) is pinned without depending on the
 * driver pool, which is what the integration tests cover.
 */
class CrawlSeedSchedulerTest {

    @Test
    @DisplayName("the concurrency ceiling is never exceeded, and units really do overlap")
    fun testConcurrencyCeilingIsHonoredAndOverlapHappens() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        // 9 units, 3 permits: enough work that the ceiling is the only thing
        // bounding the overlap.
        val urls = (1..9).map { "https://example.com/$it" }

        mapCrawlSeedsConcurrently(urls, 3) { _, _ ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            // A suspending body is what lets the other permits be taken: without
            // a suspension point a single-threaded scheduler could not overlap
            // anything and the ceiling test would pass vacuously.
            delay(20)
            inFlight.decrementAndGet()
        }

        assertTrue(peak.get() <= 3, "at most 3 units may be in flight, peak was ${peak.get()}")
        assertTrue(peak.get() >= 2, "units must overlap, but the peak in flight was only ${peak.get()}")
        assertEquals(0, inFlight.get(), "every unit must release its slot")
    }

    @Test
    @DisplayName("a concurrency of 1 keeps the strictly sequential behavior")
    fun testConcurrencyOfOneIsSequential() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val urls = (1..4).map { "https://example.com/$it" }

        mapCrawlSeedsConcurrently(urls, 1) { _, _ ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(10)
            inFlight.decrementAndGet()
        }

        assertEquals(1, peak.get(), "a budget of 1 must never overlap two units")
    }

    @Test
    @DisplayName("results keep the input order even when the units finish out of order")
    fun testResultsKeepInputOrder() = runBlocking {
        val urls = listOf("first", "second", "third", "fourth")

        val results = mapCrawlSeedsConcurrently(urls, 4) { index, url ->
            // Reverse completion order: the later a unit is, the sooner it ends.
            delay((urls.size - index) * 10L)
            url
        }

        assertEquals(urls, results, "the crawl listing must stay deterministic")
    }

    @Test
    @DisplayName("a non-positive concurrency still runs every unit exactly once")
    fun testNonPositiveConcurrencyStillRunsEverything() = runBlocking {
        val seen = AtomicInteger()

        val results = mapCrawlSeedsConcurrently(listOf("a", "b", "c"), 0) { _, url ->
            seen.incrementAndGet()
            url.uppercase()
        }

        assertEquals(3, seen.get())
        assertEquals(listOf("A", "B", "C"), results)
    }
}
