package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.BrowserManager
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolExhaustedException
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
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
import org.slf4j.LoggerFactory
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
    private lateinit var browserManager: BrowserManager
    private lateinit var poolLogger: LogbackLogger
    private lateinit var capturedLog: ListAppender<ILoggingEvent>
    private var savedLevel: Level? = null

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
        this.browserManager = browserManager

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
    @DisplayName("poll reuses a standby driver instead of creating another one")
    fun testPollReusesAStandbyDriver() {
        AppSystemInfo.CRITICAL_MEMORY_THRESHOLD_MIB = 1.0

        val first = pool.poll(0, VolatileConfig.UNSAFE, 5, TimeUnit.SECONDS)
        assertSame(driver, first, "The first poll should hand out the created driver")
        assertEquals(1, pool.numCreated, "The first poll should create exactly one driver")

        // Send it back, as a finished fetch does
        whenever(driver.isWorking).thenReturn(true)
        pool.put(first)
        assertEquals(1, pool.numStandby, "The returned driver should wait in the pool")

        // A standby driver costs nothing to reuse, while creating one launches a tab that the pool
        // keeps for the rest of its life - creating first filled the pool with idle tabs and left
        // the callers waiting for a launch (see pollDriverInSlices)
        val second = pool.poll(0, VolatileConfig.UNSAFE, 5, TimeUnit.SECONDS)
        assertSame(first, second, "The standby driver should be handed to the next waiter")
        assertEquals(1, pool.numCreated, "A standby driver must be reused, not replaced by a new one")
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

    // ------------------------------------------------------------------
    // Wait diagnostics (§19.5: who waits, how long, who holds the drivers)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a task that waits for a driver is named, with the reason and who holds the drivers")
    fun testWaitDiagnosticsNameTheWaiterTheReasonAndTheHolder() {
        // The guard refuses driver creation, exactly as it does during a load spike, so a caller can
        // only wait - and the pool has to say so instead of leaving the caller to infer it.
        AppSystemInfo.CRITICAL_MEMORY_THRESHOLD_MIB = 1.0
        AppSystemInfo.CRITICAL_CPU_THRESHOLD = -1.0
        val settings = mock<BrowserSettings>()
        whenever(settings.pollingDriverTimeout).thenReturn(Duration.ofMillis(700))
        whenever(browserManager.settings).thenReturn(settings)

        val page = mock<WebPage>()
        whenever(page.url).thenReturn(STALLED_PAGE)

        val savedDebugThreshold = LoadingWebDriverPool.WAIT_DEBUG_THRESHOLD
        val savedWarnThreshold = LoadingWebDriverPool.WAIT_WARN_THRESHOLD
        LoadingWebDriverPool.WAIT_DEBUG_THRESHOLD = Duration.ofMillis(100)
        LoadingWebDriverPool.WAIT_WARN_THRESHOLD = Duration.ofMillis(200)
        startCapturingPoolLog()
        try {
            val e = assertThrows(WebDriverPoolExhaustedException::class.java) {
                runBlocking { pool.poll(0, VolatileConfig.UNSAFE, null, page) }
            }
            val message = e.message.orEmpty()
            assertTrue(
                message.contains("driver creation is refused: the system is over the critical load"),
                "the failure must name the reason: $message"
            )
            assertTrue(message.contains("no driver is working"), "the failure must name the holders: $message")

            val warn = logLines().filter { it.startsWith("A task waited more than") }
            assertTrue(warn.isNotEmpty(), "a wait past the warn threshold must be reported: ${logLines()}")
            assertTrue(warn.all { it.contains("#${pool.id}") }, "the warn must name the pool: $warn")
            assertTrue(
                warn.all { it.contains("driver creation is refused") },
                "the warn must name the reason: $warn"
            )
            // ThrottlingLogger throttles the rendered message, so the warn is the throttle key: it
            // may not carry the waiter (or a duration), or nothing would ever be throttled.
            assertTrue(
                warn.none { it.contains(STALLED_PAGE) },
                "the throttled warn must not carry the waiter: $warn"
            )

            val debug = logLines().filter { it.startsWith("Waited ") }
            assertTrue(debug.isNotEmpty(), "the exact wait belongs to the debug log: ${logLines()}")
            assertTrue(
                debug.any { it.contains("waiting task: $STALLED_PAGE") },
                "the debug line must name the waiter: $debug"
            )
            assertTrue(
                debug.any { it.contains("no driver is working") },
                "the debug line must name the holders: $debug"
            )
        } finally {
            stopCapturingPoolLog()
            LoadingWebDriverPool.WAIT_DEBUG_THRESHOLD = savedDebugThreshold
            LoadingWebDriverPool.WAIT_WARN_THRESHOLD = savedWarnThreshold
        }
    }

    @Test
    @DisplayName("the wait reason tells saturation and a refused creation apart")
    fun testDriverWaitReason() {
        assertEquals("the pool is closed", driverWaitReason(true, false, 4, 1, false, false))
        assertEquals("the pool is retired", driverWaitReason(false, true, 4, 0, false, false))
        // Saturation: the action is to add capacity, not to wait for the load to settle.
        assertEquals("every driver slot is taken", driverWaitReason(false, false, 0, 4, false, false))
        // A refusal: the action is to wait, the guard can settle at any moment.
        assertEquals(
            "driver creation is refused: critical memory",
            driverWaitReason(false, false, 4, 0, true, true)
        )
        assertEquals(
            "driver creation is refused: the system is over the critical load",
            driverWaitReason(false, false, 4, 0, false, true)
        )
        assertEquals("no driver has been created yet", driverWaitReason(false, false, 4, 0, false, false))
        assertEquals("no driver became available", driverWaitReason(false, false, 4, 1, false, false))
    }

    @Test
    @DisplayName("the wait bucket is a small set of labels, so the throttled message repeats")
    fun testDriverWaitBucket() {
        val threshold = Duration.ofSeconds(10)

        assertEquals("1x", driverWaitBucket(Duration.ofSeconds(10), threshold))
        assertEquals("1x", driverWaitBucket(Duration.ofSeconds(29), threshold))
        assertEquals("3x", driverWaitBucket(Duration.ofSeconds(30), threshold))
        assertEquals("10x", driverWaitBucket(Duration.ofSeconds(100), threshold))
        assertEquals("30x", driverWaitBucket(Duration.ofSeconds(300), threshold))
        assertEquals("1x", driverWaitBucket(Duration.ofSeconds(600), Duration.ZERO))
    }

    @Test
    @DisplayName("the drivers holding the pool are named with the page they are on, in one line")
    fun testDescribeDriverHolders() {
        assertEquals("#7 Active", driverHolderLabel(7, "Active", null))
        assertEquals("#7 Active", driverHolderLabel(7, "Active", "   "))
        assertEquals("#7 Active https://example.com/a", driverHolderLabel(7, "Active", "https://example.com/a"))

        assertEquals("no driver is working", describeDriverHolders(emptyList(), 0))
        assertEquals(
            "working drivers: #3 Active https://example.com/a, #4 Active https://example.com/b",
            describeDriverHolders(
                listOf("#3 Active https://example.com/a", "#4 Active https://example.com/b"), 2
            )
        )
        // The line stays one line: the drivers that did not fit are counted, not listed.
        assertEquals(
            "working drivers: #3 Active https://example.com/a (+4 more)",
            describeDriverHolders(listOf("#3 Active https://example.com/a"), 5)
        )
    }

    // ------------------------------------------------------------------
    // Log capture
    // ------------------------------------------------------------------

    private fun startCapturingPoolLog() {
        poolLogger = LoggerFactory.getLogger(LoadingWebDriverPool::class.java) as LogbackLogger
        savedLevel = poolLogger.level
        poolLogger.level = Level.DEBUG
        capturedLog = ListAppender<ILoggingEvent>().apply { start() }
        poolLogger.addAppender(capturedLog)
    }

    private fun stopCapturingPoolLog() {
        poolLogger.detachAppender(capturedLog)
        capturedLog.stop()
        poolLogger.level = savedLevel
    }

    private fun logLines(): List<String> = capturedLog.list.map { it.formattedMessage }

    private companion object {
        const val STALLED_PAGE = "https://example.com/stalled"
    }
}
