package ai.platon.pulsar.skeleton.workflow.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies [ProtocolFactory] registers its protocols lazily (on first
 * access) rather than in the constructor's `init` block.
 *
 * This behavior is the fix for the "Protocol not found (1600)" race that
 * surfaced under `spring.main.lazy-initialization=true`: the factory bean is
 * created as a CGLIB proxy whose `init` block does not run until the first
 * method call, so a constructor-based registration could race with swarm's
 * concurrent worker pool.  The `AtomicBoolean`-guarded lazy registration
 * makes first access idempotent and thread-safe.
 */
class ProtocolFactoryTest {

    private fun browserProtocol(name: String = "browser"): Protocol {
        val protocol = Mockito.mock(Protocol::class.java)
        Mockito.`when`(protocol.name).thenReturn(name)
        return protocol
    }

    @Test
    fun `getProtocol resolves a registered protocol by url prefix`() {
        val browser = browserProtocol()
        val factory = ProtocolFactory(listOf(browser))

        val resolved = factory.getProtocol("browser:http://example.com/page")

        assertSame(browser, resolved)
    }

    @Test
    fun `getProtocol returns null for an unknown prefix`() {
        val factory = ProtocolFactory(listOf(browserProtocol()))

        assertNull(factory.getProtocol("unknown:http://example.com/"))
    }

    @Test
    fun `getProtocol by fetch mode lowercases the mode name`() {
        val browser = browserProtocol(name = "browser")
        val factory = ProtocolFactory(listOf(browser))

        // FetchMode.BROWSER.name.lowercase() == "browser", so this resolves
        // through getProtocol("browser://").
        assertSame(browser, factory.getProtocol(ai.platon.pulsar.persist.metadata.FetchMode.BROWSER))
    }

    @Test
    fun `first concurrent access registers exactly once and resolves for all threads`() {
        val browser = browserProtocol()
        val factory = ProtocolFactory(listOf(browser))

        val threadCount = 32
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    val resolved = factory.getProtocol("browser:http://example.com/")
                    assertSame(browser, resolved)
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        executor.shutdown()
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "concurrent access timed out" }

        assertEquals(0, errors.size, "concurrent first access must not race: $errors")
    }

    @Test
    fun `close is idempotent`() {
        val browser = browserProtocol()
        val factory = ProtocolFactory(listOf(browser))

        // First access registers the protocol, so close() has something to clear.
        assertSame(browser, factory.getProtocol("browser:http://example.com/"))

        factory.close()
        factory.close() // second close must be a no-op (AtomicBoolean guard)

        // After close the map is cleared, so resolution returns null rather
        // than throwing — the factory is closed for business but stays safe.
        assertNull(factory.getProtocol("browser:http://example.com/"))
    }
}
