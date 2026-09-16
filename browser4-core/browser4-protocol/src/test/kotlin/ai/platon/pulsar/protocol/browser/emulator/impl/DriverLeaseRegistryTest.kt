package ai.platon.pulsar.protocol.browser.emulator.impl

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The per-driver lease that keeps two fetches from driving one browser tab.
 *
 * Issue #592: a crawl's fetches all used the session's bound driver, which no
 * pool leased, so concurrent fetches advanced the same tab under each other and
 * the snapshot-origin guard refused the captures — the "expected 10 pages, got
 * 8" run.
 */
@DisplayName("driver leases")
class DriverLeaseRegistryTest {

    @Test
    @DisplayName("the same driver cannot be leased twice at once")
    fun theSameDriverIsExclusive() = runBlocking {
        val leases = DriverLeaseRegistry()

        assertTrue(leases.tryAcquire(7, 1000), "the first fetch gets the tab")
        assertFalse(leases.tryAcquire(7, 50), "a concurrent fetch must not get the same tab")

        leases.release(7)
        assertTrue(leases.tryAcquire(7, 1000), "the tab is available again after the fetch")
        leases.release(7)
    }

    @Test
    @DisplayName("different drivers never block each other")
    fun differentDriversAreIndependent() = runBlocking {
        val leases = DriverLeaseRegistry()

        assertTrue(leases.tryAcquire(1, 1000))
        assertTrue(leases.tryAcquire(2, 1000), "another tab must not be blocked")
        assertTrue(leases.tryAcquire(3, 1000))

        leases.release(1)
        leases.release(2)
        leases.release(3)
        assertEquals(3, leases.leaseCount)
    }

    @Test
    @DisplayName("a waiting fetch takes the lease as soon as the holder releases it")
    fun aWaiterTakesOverAfterTheRelease() = runBlocking {
        val leases = DriverLeaseRegistry()
        val order = CopyOnWriteArrayList<String>()

        assertTrue(leases.tryAcquire(9, 1000))
        val waiter = launch {
            assertTrue(leases.tryAcquire(9, 5000), "the waiter must eventually get the lease")
            order.add("second")
            leases.release(9)
        }

        delay(100)
        order.add("first")
        leases.release(9)

        withTimeout(5000) { waiter.join() }
        assertEquals(listOf("first", "second"), order, "the second fetch must not overtake the first")
    }

    /**
     * The regression this whole class exists for: fetches that share a driver must
     * never be inside a fetch at the same time.
     */
    @Test
    @Timeout(30)
    @DisplayName("concurrent fetches of one driver never overlap")
    fun concurrentFetchesOfOneDriverNeverOverlap() = runBlocking {
        val leases = DriverLeaseRegistry()
        val driverId = 2
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()

        val fetches = (1..8).map { index ->
            async {
                val leased = leases.tryAcquire(driverId, 10_000)
                assertTrue(leased, "fetch $index never got the lease")
                try {
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, now) }
                    delay(20) // a fetch drives the tab for a while
                    concurrent.decrementAndGet()
                } finally {
                    leases.release(driverId)
                }
            }
        }
        fetches.awaitAll()

        assertEquals(
            1, maxConcurrent.get(),
            "at most one fetch may drive a tab at a time, saw ${maxConcurrent.get()} at once"
        )
        assertEquals(0, concurrent.get())
    }

    @Test
    @DisplayName("a lease is reusable after many sequential fetches")
    fun theLeaseIsReusable() = runBlocking {
        val leases = DriverLeaseRegistry()

        repeat(50) { index ->
            assertTrue(leases.tryAcquire(4, 1000), "fetch $index could not lease the tab")
            leases.release(4)
        }

        assertEquals(1, leases.leaseCount, "one driver, one lease entry")
    }
}
