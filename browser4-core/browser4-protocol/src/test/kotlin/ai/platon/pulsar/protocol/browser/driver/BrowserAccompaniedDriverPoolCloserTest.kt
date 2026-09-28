package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.BrowserManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * [BrowserAccompaniedDriverPoolCloser] exists to keep the driver pool pool and the browsers the
 * pools accompany consistent: a driver pool is only ever created for one browser, so closing one
 * has to close the other, and a pool whose browser is already gone must still be closed.
 *
 * The pools and the browser manager are mocks, so no browser is launched and every case here runs
 * in the fast gate.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("BrowserAccompaniedDriverPoolCloser pool/browser consistency")
class BrowserAccompaniedDriverPoolCloserTest {

    private lateinit var poolPool: ConcurrentStatefulDriverPoolPool
    private lateinit var poolManager: WebDriverPoolManager
    private lateinit var browserManager: BrowserManager
    private lateinit var closer: BrowserAccompaniedDriverPoolCloser

    @BeforeEach
    fun setUp() {
        poolPool = ConcurrentStatefulDriverPoolPool()
        browserManager = mock()
        poolManager = mock()
        whenever(poolManager.browserManager).thenReturn(browserManager)
        closer = BrowserAccompaniedDriverPoolCloser(poolPool, poolManager)
    }

    private fun mockPool(
        browserId: BrowserId,
        idle: Boolean = false,
        permanent: Boolean = false
    ): LoadingWebDriverPool {
        val pool = mock<LoadingWebDriverPool>()
        whenever(pool.browserId).thenReturn(browserId)
        whenever(pool.isIdle).thenReturn(idle)
        whenever(pool.isPermanent).thenReturn(permanent)
        whenever(pool.numCreated).thenReturn(1)
        return pool
    }

    /**
     * Deep stubs: `browser.settings` is an API type owned outside this module and only its
     * `isGUI` / `displayMode` flags are read, for one branch and one log line.
     * */
    private fun mockBrowser(browserId: BrowserId): AbstractBrowser {
        val browser = mock<AbstractBrowser>(defaultAnswer = Answers.RETURNS_DEEP_STUBS)
        whenever(browser.id).thenReturn(browserId)
        return browser
    }

    private fun registerWorkingPool(pool: LoadingWebDriverPool, browserId: BrowserId) {
        poolPool.computeIfAbsent(browserId) { pool }
    }

    @Test
    @DisplayName("closing a working pool retires and closes it, and closes its browser")
    fun closesTheRetiredPoolAndItsBrowserGracefully() {
        val browserId = BrowserId.createRandomTemp()
        val pool = mockPool(browserId)
        registerWorkingPool(pool, browserId)
        val browser = mockBrowser(browserId)
        whenever(browserManager.findBrowserOrNull(browserId)).thenReturn(browser)

        closer.closeGracefully(browserId)

        verify(pool).retire()
        verify(pool).close()
        verify(browserManager).closeBrowser(browser)
        assertTrue(poolPool.closedDriverPools.contains(browserId), "the pool should be in the closed list")
        assertFalse(poolPool.workingDriverPools.containsKey(browserId), "the pool should not stay working")
        assertFalse(poolPool.retiredDriverPools.containsKey(browserId), "the pool should not stay retired")
    }

    @Test
    @DisplayName("closeForcibly closes through the same pool-and-browser path")
    fun closeForciblyUsesTheSameClosingPath() {
        val browserId = BrowserId.createRandomTemp()
        val pool = mockPool(browserId)
        registerWorkingPool(pool, browserId)
        val browser = mockBrowser(browserId)
        whenever(browserManager.findBrowserOrNull(browserId)).thenReturn(browser)

        closer.closeForcibly(browserId)

        verify(pool).close()
        verify(browserManager).closeBrowser(browser)
        assertTrue(poolPool.closedDriverPools.contains(browserId))
    }

    @Test
    @DisplayName("closing an unknown browser id changes nothing")
    fun isANoOpForAnUnknownBrowserId() {
        val browserId = BrowserId.createRandomTemp()

        closer.closeGracefully(browserId)

        assertTrue(poolPool.workingDriverPools.isEmpty(), "no pool should be created")
        assertTrue(poolPool.retiredDriverPools.isEmpty(), "no pool should be retired")
        assertTrue(poolPool.closedDriverPools.isEmpty(), "no pool should be closed")
        verify(browserManager, never()).closeBrowser(any<AbstractBrowser>())
    }

    @Test
    @DisplayName("closes only the driver pool when its browser is already gone")
    fun closesOnlyThePoolWhenTheBrowserIsGone() {
        val browserId = BrowserId.createRandomTemp()
        val pool = mockPool(browserId)
        registerWorkingPool(pool, browserId)
        whenever(browserManager.findBrowserOrNull(browserId)).thenReturn(null)

        closer.closeGracefully(browserId)

        verify(pool).close()
        verify(browserManager, never()).closeBrowser(any<AbstractBrowser>())
        assertTrue(poolPool.closedDriverPools.contains(browserId))
    }

    @Test
    @DisplayName("closing the oldest retired pool does nothing when nothing is retired")
    fun doesNothingWhenNoPoolIsRetired() {
        whenever(poolManager.retiredDriverPools).thenReturn(emptyMap())

        closer.closeOldestRetiredDriverPoolSafely()

        assertTrue(poolPool.closedDriverPools.isEmpty(), "an empty retired pool set closes nothing")
    }

    @Test
    @DisplayName("closes the oldest retired pool once dying drivers exceed the allowance")
    fun closesTheOldestRetiredPoolWhenItIsDying() {
        val olderId = BrowserId.createRandomTemp()
        val newerId = BrowserId.createRandomTemp()
        val older = mockPool(olderId)
        val newer = mockPool(newerId)
        whenever(older.lastActiveTime).thenReturn(Instant.parse("2026-01-01T00:00:00Z"))
        whenever(newer.lastActiveTime).thenReturn(Instant.parse("2026-06-01T00:00:00Z"))
        whenever(poolManager.retiredDriverPools).thenReturn(linkedMapOf(olderId to older, newerId to newer))
        whenever(browserManager.findBrowserOrNull(olderId)).thenReturn(null)
        whenever(browserManager.findBrowserOrNull(newerId)).thenReturn(null)

        closer.closeOldestRetiredDriverPoolSafely()

        assertTrue(poolPool.closedDriverPools.contains(olderId), "the least recently active pool should die")
        assertFalse(poolPool.closedDriverPools.contains(newerId), "the newer pool should be kept")
    }

    @Test
    @DisplayName("closes only idle, non-permanent pools")
    fun closesOnlyIdleNonPermanentPools() {
        val idleId = BrowserId.createRandomTemp()
        val busyId = BrowserId.createRandomTemp()
        val permanentId = BrowserId.createRandomTemp()
        val idle = mockPool(idleId, idle = true)
        val busy = mockPool(busyId, idle = false)
        val permanent = mockPool(permanentId, idle = true, permanent = true)
        registerWorkingPool(idle, idleId)
        registerWorkingPool(busy, busyId)
        registerWorkingPool(permanent, permanentId)
        val snapshot = mock<LoadingWebDriverPool.Snapshot>()
        whenever(snapshot.format(true)).thenReturn("snapshot")
        whenever(idle.takeSnapshot()).thenReturn(snapshot)
        whenever(browserManager.findBrowserOrNull(idleId)).thenReturn(null)

        closer.closeIdleDriverPoolsSafely()

        assertTrue(poolPool.closedDriverPools.contains(idleId), "an idle, non-permanent pool should be closed")
        assertFalse(poolPool.closedDriverPools.contains(busyId), "a busy pool should be kept")
        assertFalse(poolPool.closedDriverPools.contains(permanentId), "a permanent pool should be kept")
        verify(busy, never()).takeSnapshot()
    }

    @Test
    @DisplayName("closes a browser that is still active after its pool was closed")
    fun closesBrowsersThatAreStillInTheClosedList() {
        val browserId = BrowserId.createRandomTemp()
        val pool = mockPool(browserId)
        registerWorkingPool(pool, browserId)
        poolPool.close(pool)
        val browser = mockBrowser(browserId)
        whenever(browserManager.findBrowserOrNull(browserId)).thenReturn(browser)

        closer.closeUnexpectedActiveBrowsers()

        verify(browserManager).closeBrowser(browserId)
    }

    @Test
    @DisplayName("ignores a closed pool whose browser is already gone")
    fun ignoresClosedPoolIdsWhoseBrowserIsGone() {
        val browserId = BrowserId.createRandomTemp()
        val pool = mockPool(browserId)
        registerWorkingPool(pool, browserId)
        poolPool.close(pool)
        whenever(browserManager.findBrowserOrNull(browserId)).thenReturn(null)

        closer.closeUnexpectedActiveBrowsers()

        verify(browserManager, never()).closeBrowser(any<AbstractBrowser>())
    }
}
