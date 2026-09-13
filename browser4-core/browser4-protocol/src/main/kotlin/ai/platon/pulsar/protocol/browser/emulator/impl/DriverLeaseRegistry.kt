package ai.platon.pulsar.protocol.browser.emulator.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-driver leases: one web driver (that is, one browser tab) is driven by one
 * fetch at a time.
 *
 * A driver handed to a fetcher *outside* the driver pool — the driver a session
 * is bound to, or a random driver of a specified browser — is not leased by
 * anybody, so two concurrent fetches can drive the same tab: the second
 * navigation advances the tab while the first is still capturing, and the first
 * then reads a document another fetch produced.  The snapshot-origin guard turns
 * that into a refused fetch (before the guard existed, the page was recorded
 * under the wrong URL), which is why a whole crawl used to run on one tab.
 *
 * The lease is keyed by driver identity, so unrelated tabs never block each
 * other.  A caller that cannot get the lease within its timeout is expected to
 * take a different driver rather than share the tab: waiting forever would stall
 * the fetch, and sharing corrupts it.
 */
internal class DriverLeaseRegistry {

    private val leases = ConcurrentHashMap<Int, Mutex>()

    /**
     * Try to lease the driver.
     *
     * @return true when the lease was acquired within [timeoutMillis]; the caller
     *   must then [release] it.  False means somebody else holds it and the
     *   caller must not use this driver.
     */
    suspend fun tryAcquire(driverId: Int, timeoutMillis: Long): Boolean {
        val lease = leases.computeIfAbsent(driverId) { Mutex() }
        return withTimeoutOrNull(timeoutMillis) { lease.lock() } != null
    }

    /** Release a lease acquired by [tryAcquire]. */
    fun release(driverId: Int) {
        leases[driverId]?.unlock()
    }

    /** How many drivers currently have a lease registered (diagnostics/tests). */
    val leaseCount: Int get() = leases.size
}
