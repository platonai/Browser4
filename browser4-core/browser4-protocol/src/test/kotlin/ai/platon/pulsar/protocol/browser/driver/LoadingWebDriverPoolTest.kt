package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.BrowserManager
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolExhaustedException
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The resource guard refuses to create a driver while the system is over the critical load, and the
 * refusal is transient, so a waiting task must keep polling instead of turning the refusal into a
 * [WebDriverPoolExhaustedException] long after the load has settled.
 * */
@Tag("Unit")
@Tag("Fast")
@Timeout(60)
@DisplayName("LoadingWebDriverPool polling")
class LoadingWebDriverPoolTest {

    private lateinit var driver: AbstractWebDriver
    private lateinit var pool: LoadingWebDriverPool

    private var savedCpuThreshold = 0.0
    private var savedMemoryThresholdMiB = 0.0

    @BeforeEach
    fun setUp() {
        savedCpuThreshold = AppSystemInfo.CRITICAL_CPU_THRESHOLD
        savedMemoryThresholdMiB = AppSystemInfo.CRITICAL_MEMORY_THRESHOLD_MIB

        driver = mock()
        whenever(driver.isRecyclable).thenReturn(true)

        val browser = mock<AbstractBrowser>()
        whenever(browser.isActive).thenReturn(true)
        whenever(browser.settings).thenReturn(mock())
        whenever(browser.newDriver()).thenReturn(driver)

        val browserManager = mock<BrowserManager>()
        whenever(browserManager.settings).thenReturn(mock())
        whenever(browserManager.launch(any(), any())).thenReturn(browser)

        pool = LoadingWebDriverPool(BrowserId.createRandomTemp(), browserManager, ImmutableConfig())
    }

    @AfterEach
    fun tearDown() {
        AppSystemInfo.CRITICAL_CPU_THRESHOLD = savedCpuThreshold
        AppSystemInfo.CRITICAL_MEMORY_THRESHOLD_MIB = savedMemoryThresholdMiB
        pool.close()
    }

    @Test
    @DisplayName("poll creates a driver as soon as the resource guard allows it again")
    fun testPollCreatesDriverWhenTheResourceGuardAllowsItAgain() {
        // A 1 MiB memory reserve makes the memory check always pass, and a CPU threshold above 1.0
        // can never be reached, so the guard is controlled by the CPU threshold only
        AppSystemInfo.CRITICAL_MEMORY_THRESHOLD_MIB = 1.0
        AppSystemInfo.CRITICAL_CPU_THRESHOLD = 1.01
        assumeTrue(!AppSystemInfo.isSystemOverCriticalLoad, "The machine is genuinely over the critical load")

        // Make the guard refuse driver creation, as it does during a transient CPU spike
        AppSystemInfo.CRITICAL_CPU_THRESHOLD = -1.0

        // Let the load settle one second later
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.schedule({ AppSystemInfo.CRITICAL_CPU_THRESHOLD = 1.01 }, 1, TimeUnit.SECONDS)

        val elapsed = try {
            val start = System.nanoTime()
            val actual = pool.poll(0, VolatileConfig.UNSAFE, 30, TimeUnit.SECONDS)
            Duration.ofNanos(System.nanoTime() - start).toMillis().also {
                assertSame(driver, actual, "The driver created after the load settled should be returned")
                assertEquals(1, pool.numCreated, "Exactly one driver should be created")
            }
        } finally {
            scheduler.shutdownNow()
        }

        assertTrue(elapsed >= 900) { "The guard should refuse driver creation until the load settles, elapsed: $elapsed ms" }
        assertTrue(elapsed < 15_000) { "poll should not wait for the whole timeout, elapsed: $elapsed ms" }
    }

    @Test
    @DisplayName("poll fails fast when the pool is retired")
    fun testPollFailsFastWhenThePoolIsRetired() {
        pool.retire()

        val start = System.nanoTime()
        assertThrows(WebDriverPoolExhaustedException::class.java) {
            pool.poll(0, VolatileConfig.UNSAFE, 30, TimeUnit.SECONDS)
        }
        val elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis()

        assertTrue(elapsed < 5_000) { "poll should not wait for the whole timeout, elapsed: $elapsed ms" }
    }
}
