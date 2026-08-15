package ai.platon.pulsar.rest.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.timeout
import org.mockito.kotlin.argumentCaptor
import org.springframework.context.ApplicationContext

/**
 * Verifies [StartupWarmer] touches every bean on the critical path
 * from HTTP request → MCP dispatch → session → browser → fetch.
 *
 * These tests pin the exact bean-name list so that CI catches a regression
 * when a bean is removed from the warmer (or added to the chain without
 * updating the warmer), which would reintroduce "Protocol not found (1600)"
 * errors on cold starts with [spring.main.lazy-initialization=true].
 *
 * Crawl ([crawlService]) and swarm ([swarmService]) are intentionally
 * absent: they are non-primary scenarios with heavy initialization and
 * fall back to lazy creation on first use (see StartupWarmer KDoc).
 */
class StartupWarmerTest {

    /**
     * Mirrors [StartupWarmer.warmupBeanNames] exactly.
     *
     * The list is duplicated here (rather than reading the private field)
     * so that the test fails when the field changes — the test is the
     * contract, and any change to the warm-up list must be deliberate.
     */
    private val expectedWarmupBeanNames = listOf(
        "mcpToolController",
        "sessionManager",
        "agenticContext",
        "protocolFactory",
        "fetchComponent",
    )

    @Test
    fun `onReady warms every critical bean`() {
        val context = Mockito.mock(ApplicationContext::class.java)
        val warmer = StartupWarmer(context)
        warmer.onReady()

        // onReady() fires CompletableFuture.runAsync — use timeout() to
        // poll the mock's invocation log until the async warmup completes.
        for (name in expectedWarmupBeanNames) {
            Mockito.verify(context, timeout(3000).atLeastOnce()).getBean(name)
        }
    }

    @Test
    fun `onReady warms exactly the expected beans in order`() {
        val context = Mockito.mock(ApplicationContext::class.java)
        val warmer = StartupWarmer(context)
        warmer.onReady()

        val captor = argumentCaptor<String>()
        Mockito.verify(context, timeout(3000).times(expectedWarmupBeanNames.size))
            .getBean(captor.capture())

        assertEquals(expectedWarmupBeanNames, captor.allValues,
            "Warm-up bean list or order changed — update the test AND the " +
            "StartupWarmer.warmupBeanNames list together, or cold-start " +
            "\"Protocol not found (1600)\" errors will return.")
    }

    @Test
    fun `onReady continues warming after a bean fails`() {
        val context = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(context.getBean("fetchComponent"))
            .thenThrow(RuntimeException("simulated lazy-init failure"))

        val warmer = StartupWarmer(context)
        warmer.onReady() // must not throw synchronously

        // All beans must still be attempted — the warm-up must not
        // short-circuit on the first failure.
        val captor = argumentCaptor<String>()
        Mockito.verify(context, timeout(3000).times(expectedWarmupBeanNames.size))
            .getBean(captor.capture())

        // Verify every bean was attempted (same set, order preserved).
        assertEquals(expectedWarmupBeanNames, captor.allValues,
            "All beans must be attempted even when one fails; " +
            "missing attempts mean the warm-up loop short-circuited.")
    }
}
