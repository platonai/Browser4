package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.common.proxy.ProxyRetiredException
import ai.platon.pulsar.common.proxy.ProxyVendorUntrustedException
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ProxyContext] owns the per-proxy bookkeeping a crawl depends on: whether a proxy may still be
 * used, when it is retired or expired, how many pages it served, and how many absences the vendor
 * has accumulated.  Every collaborator is a mock, so this runs with no browser and no proxy vendor.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("ProxyContext lifecycle and accounting")
class ProxyContextTest {

    private lateinit var proxyPoolManager: ProxyPoolManager
    private lateinit var driverContext: WebDriverContext
    private lateinit var entry: ProxyEntry

    private val savedNumProxyAbsence = ProxyContext.numProxyAbsence.get()
    private val savedLastProxyAbsentTime = ProxyContext.lastProxyAbsentTime
    private val savedMaxAllowedProxyAbsence = ProxyContext.maxAllowedProxyAbsence

    @BeforeEach
    fun setUp() {
        proxyPoolManager = mock()
        driverContext = mock()
        entry = mock()
        val browserId = mock<BrowserId>()
        whenever(browserId.userDataDir).thenReturn(Paths.get("tmp", "browser-user-data"))
        whenever(driverContext.browserId).thenReturn(browserId)
        whenever(proxyPoolManager.conf).thenReturn(ImmutableConfig())
        whenever(proxyPoolManager.isEnabled).thenReturn(true)
        whenever(proxyPoolManager.isActive).thenReturn(true)
        whenever(proxyPoolManager.activeProxyEntries).thenReturn(ConcurrentSkipListMap())
    }

    @AfterEach
    fun tearDown() {
        // numProxyAbsence / maxAllowedProxyAbsence are process-wide companion state.
        ProxyContext.numProxyAbsence.set(savedNumProxyAbsence)
        ProxyContext.lastProxyAbsentTime = savedLastProxyAbsentTime
        ProxyContext.maxAllowedProxyAbsence = savedMaxAllowedProxyAbsence
    }

    private fun newContext(proxyEntry: ProxyEntry? = entry): ProxyContext =
        ProxyContext(proxyEntry, proxyPoolManager, driverContext)

    @Test
    @DisplayName("a ready, active proxy makes the context ready and enabled")
    fun readyWhenTheProxyIsReadyAndActive() {
        whenever(entry.isExpired).thenReturn(false)
        whenever(entry.isRetired).thenReturn(false)
        whenever(entry.isReady).thenReturn(true)

        val context = newContext()

        assertTrue(context.isEnabled)
        assertTrue(context.isActive)
        assertTrue(context.isReady)
        assertFalse(context.isRetired)
    }

    @Test
    @DisplayName("an expired proxy is retired on read and stops being ready")
    fun retiresAnExpiredProxyOnRead() {
        whenever(entry.isExpired).thenReturn(true)
        whenever(entry.isRetired).thenReturn(true)
        whenever(entry.isReady).thenReturn(true)

        val context = newContext()

        assertTrue(context.isRetired)
        verify(entry).retire()
        assertFalse(context.isReady, "a retired proxy is never ready")
    }

    @Test
    @DisplayName("maintain retires an expired proxy and leaves a live one alone")
    fun maintainRetiresOnlyAnExpiredProxy() {
        whenever(entry.isExpired).thenReturn(true)
        newContext().maintain()
        verify(entry).retire()

        val live = mock<ProxyEntry>()
        whenever(live.isExpired).thenReturn(false)
        newContext(live).maintain()
        verify(live, never()).retire()
    }

    @Test
    @DisplayName("state reports the proxy flags and the counters")
    fun stateReportsTheProxyFlags() {
        whenever(entry.isExpired).thenReturn(false)
        whenever(entry.isRetired).thenReturn(false)
        whenever(entry.isReady).thenReturn(true)

        val state = newContext().state

        assertEquals(true, state["isActive"])
        assertEquals(true, state["isReady"])
        assertEquals(false, state["isRetired"])
        assertEquals(ProxyContext.maxAllowedProxyAbsence, state["maxAllowedProxyAbsence"])
    }

    @Test
    @DisplayName("a context with no proxy is ready when it is active")
    fun readyWithoutAProxyEntry() {
        val context = newContext(null)

        assertTrue(context.isReady)
        assertFalse(context.isRetired)
        assertEquals("<no proxy>", context.state["proxyEntry"])
    }

    @Test
    @DisplayName("an inactive pool makes the context inactive and not ready")
    fun inactiveWhenThePoolIsInactive() {
        whenever(proxyPoolManager.isActive).thenReturn(false)
        whenever(entry.isReady).thenReturn(true)

        val context = newContext()

        assertFalse(context.isActive)
        assertFalse(context.isReady)
    }

    @Test
    @DisplayName("close removes the browser's active proxy entry")
    fun closeRemovesTheActiveProxyEntry() {
        val key = driverContext.browserId.userDataDir
        val entries = ConcurrentSkipListMap<Path, ProxyEntry>()
        entries[key] = entry
        whenever(proxyPoolManager.activeProxyEntries).thenReturn(entries)

        newContext().close()

        assertFalse(entries.containsKey(key), "the active proxy entry should be dropped on close")
    }

    @Test
    @DisplayName("a task that starts on a live proxy records the proxy and the running count")
    fun beforeTaskStartRecordsTheProxy() {
        val task = mock<FetchTask>()
        whenever(task.domain).thenReturn("example.com")
        whenever(entry.willExpireAfter(any())).thenReturn(false)
        whenever(entry.numSuccessPages).thenReturn(AtomicInteger(0))
        val before = ProxyContext.numRunningTasks.get()

        newContext().beforeTaskStart(task)

        verify(entry).willExpireAfter(any())
        assertEquals(before + 1, ProxyContext.numRunningTasks.get(), "the task should be counted as running")
    }

    @Test
    @DisplayName("a proxy that is about to expire retires the context before the task starts")
    fun beforeTaskStartRetiresAnAlmostExpiredProxy() {
        val task = mock<FetchTask>()
        whenever(entry.willExpireAfter(any())).thenReturn(true)

        assertThrows(ProxyRetiredException::class.java) { newContext().beforeTaskStart(task) }
    }

    @Test
    @DisplayName("a proxy that served too many pages retires the context before the task starts")
    fun beforeTaskStartRetiresAnOverworkedProxy() {
        val task = mock<FetchTask>()
        whenever(entry.willExpireAfter(any())).thenReturn(false)
        whenever(entry.numSuccessPages).thenReturn(AtomicInteger(Int.MAX_VALUE / 4))

        assertThrows(ProxyRetiredException::class.java) { newContext().beforeTaskStart(task) }
    }

    @Test
    @DisplayName("a finished task credits the proxy that served it, or charges it on failure")
    fun afterTaskFinishedUpdatesTheProxyAccounting() {
        val task = mock<FetchTask>()
        whenever(task.domain).thenReturn("example.com")
        whenever(task.url).thenReturn("https://example.com/a")
        val servedDomains = ConcurrentHashMap<String, AtomicInteger>()
        val successes = AtomicInteger(0)
        whenever(entry.servedDomains).thenReturn(servedDomains)
        whenever(entry.numSuccessPages).thenReturn(successes)

        val context = newContext()
        context.afterTaskFinished(task, true)

        verify(entry).refresh()
        assertEquals(1, successes.get(), "a served page should be credited")
        assertEquals(1, servedDomains["example.com"]?.get(), "the page's domain should be recorded")

        val failures = AtomicInteger(0)
        whenever(entry.numFailedPages).thenReturn(failures)
        context.afterTaskFinished(task, false)

        assertEquals(1, failures.get(), "a failed page should be charged")
    }

    @Test
    @DisplayName("an inactive context cancels the task instead of running it")
    fun runCancelsTheTaskWhenTheContextIsInactive() {
        whenever(proxyPoolManager.isActive).thenReturn(false)
        val page = mock<WebPage>()
        whenever(page.url).thenReturn("https://example.com/canceled")
        whenever(page.baseURI).thenReturn("https://example.com/canceled")
        whenever(page.location).thenReturn("https://example.com/canceled")
        val task = mock<FetchTask>()
        whenever(task.page).thenReturn(page)
        var browsed = false

        val result = runBlocking {
            newContext().run(task) { _, _ ->
                browsed = true
                throw IllegalStateException("the browse function must not run on an inactive context")
            }
        }

        assertNotNull(result, "an inactive context still answers with a result")
        assertFalse(browsed, "the browse function must not run on an inactive context")
    }

    @Test
    @DisplayName("proxy absence beyond the allowance on the same day is refused")
    fun refusesWhenTheProxyVendorIsUntrusted() {
        ProxyContext.maxAllowedProxyAbsence = 2
        ProxyContext.numProxyAbsence.set(3)
        ProxyContext.lastProxyAbsentTime = Instant.now()

        assertThrows(ProxyVendorUntrustedException::class.java) { ProxyContext.checkProxyAbsence() }
    }

    @Test
    @DisplayName("proxy absence below the allowance is tolerated")
    fun toleratesProxyAbsenceBelowTheAllowance() {
        ProxyContext.maxAllowedProxyAbsence = 5
        ProxyContext.numProxyAbsence.set(1)

        ProxyContext.checkProxyAbsence()

        assertEquals(1, ProxyContext.numProxyAbsence.get(), "a tolerated absence keeps its count")
    }

    @Test
    @DisplayName("the absence counter is cleared once the day changes")
    fun clearsTheAbsenceCounterOnANewDay() {
        ProxyContext.maxAllowedProxyAbsence = 2
        ProxyContext.numProxyAbsence.set(9)
        ProxyContext.lastProxyAbsentTime = Instant.now().minusSeconds(3 * 24 * 3600)

        ProxyContext.checkProxyAbsence()

        assertEquals(0, ProxyContext.numProxyAbsence.get(), "a new day clears the absence counter")
    }
}
