package ai.platon.pulsar.basic.session

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import ai.platon.pulsar.ql.SQLSession
import ai.platon.pulsar.skeleton.common.persist.ext.loadEventHandlers
import ai.platon.pulsar.skeleton.session.BasicPulsarSession
import ai.platon.pulsar.basic.TestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class SessionLoadTests: TestBase() {
    private val url = "https://www.amazon.com/Best-Sellers-Beauty/zgbs/beauty"
    private val urls = LinkExtractors.fromResource("categories.txt")

    @BeforeEach
    fun clearResources() {
        session.globalCache.resetCaches()

        session.delete(url)
        urls.forEach { session.delete(it) }

        assertTrue("Page should not exists | $url") { !session.exists(url) }
        urls.forEach {
            assertTrue("Page should not exists | $it") { !session.exists(it) }
        }
    }

    @Test
    fun ensureSessionCreatedBySQLContextIsNotSQLSession() {
        assertFalse { session is SQLSession }
        assertTrue { session is BasicPulsarSession }
    }

    @Test
    fun `When visit a page then it's fetched`() {
        val options = session.options("-refresh")
        options.eventHandlers.browseEventHandlers.onDidScroll.addLast { page, driver ->
            require(driver is PulsarWebDriver)
        }
        val page = session.load(url, options)
        assertTrue { page.isFetched }
        assertTrue { page.originalContentLength > 0 }
    }

    @Test
    fun `When loaded a HTML page then the navigate state are correct`() {
        val options = session.options("-refresh")
        options.eventHandlers.browseEventHandlers.onDidScroll.addLast { page, driver ->
            require(driver is PulsarWebDriver)
            val navigateEntry = driver.navigateEntry
            assertTrue { navigateEntry.documentTransferred }
            assertTrue { navigateEntry.networkRequestCount.get() > 0 }
            assertTrue { navigateEntry.networkResponseCount.get() > 0 }

            assertEquals(200, driver.mainResponseStatus)
            assertTrue { driver.mainResponseStatus == 200 }
            assertTrue { driver.mainResponseHeaders.isNotEmpty() }
            assertEquals(200, navigateEntry.mainResponseStatus)
            assertTrue { navigateEntry.mainResponseStatus == 200 }
            assertTrue { navigateEntry.mainResponseHeaders.isNotEmpty() }
        }
        session.load(url, options)
    }

    /**
     * Test event handlers.
     *
     * The test cases are passed when run separately, but are failed when running in batch mode in linux
     * using the following command:
     *
     * ```kotlin
     * mvn -X -pl pulsar-tests
     * ```
     *
     * It seems that await() never returns, and the test cases are blocked.
     * TODO: Investigate the root cause of the issue.
     *
     * Environment:
     * Ubuntu 13.3.0-6ubuntu2~24.04
     * openjdk version "21.0.6" 2025-01-21     */
    @Tag("BatchTestFailed")
    @Ignore("BatchTestFailed, run this test separately and investigate the root cause of the issue")
    @Test
    fun whenLoadAllAsyncTwiceWithRefresh_thenPagesAreFetchedInBothTime() {
        logger.info("Testing - whenLoadAllAsyncTwiceWithRefresh_thenPagesAreFetchedInBothTime")

        val normUrls = urls.take(5).map { session.normalize(it, "-refresh") }
        val futures = session.loadAllAsync(normUrls)

        val future1 = CompletableFuture.allOf(*futures.toTypedArray())
        future1.join()

        logger.info("Twice Fetch - 1 - all ${futures.size} futures are done, should be fetched from the internet")

        val normUrls2 = urls.take(5).map { session.normalize(it, "-refresh") }
        val futures2 = session.loadAllAsync(normUrls2)
        val future2 = CompletableFuture.allOf(*futures2.toTypedArray())
        future2.join()

        logger.info("Twice Fetch - 2 - all ${futures2.size} futures are done, should be fetched from the internet")

        assertEquals(futures.size, futures2.size)

        val pages = futures.map { it.get() }
        val pages2 = futures2.map { it.get() }

        logger.info("All pages are loaded")

        pages.forEach { assertTrue { it.isFetched } }
        pages2.forEach { assertTrue { it.isFetched } }
        pages2.forEach { assertTrue { it.loadEventHandlers != null } }
        assertEquals(pages.size, pages2.size)

        logger.info("Tested - whenLoadAllAsyncTwiceWithRefresh_thenPagesAreFetchedInBothTime")
    }

    /**
     * Test event handlers.
     *
     * The test cases are passed when run separately, but are failed when running in batch mode in linux
     * using the following command:
     *
     * ```kotlin
     * mvn -X -pl pulsar-tests
     * ```
     *
     * It seems that await() never returns, and the test cases are blocked.
     * TODO: Investigate the root cause of the issue.
     *
     * Environment:
     * Ubuntu 13.3.0-6ubuntu2~24.04
     * openjdk version "21.0.6" 2025-01-21     */
    @Tag("BatchTestFailed")
    @Ignore("BatchTestFailed, run this test separately and investigate the root cause of the issue")
    @Test
    fun whenLoadAllAsyncSecondlyWithoutExpiry_thenPagesAreLoadedFromCache() {
        logger.info("Testing - whenLoadAllAsyncSecondlyWithoutExpiry_thenPagesAreLoadedFromCache")

        val normUrls = urls.take(5).map { session.normalize(it, "-refresh") }
        val futures = session.loadAllAsync(normUrls)

        val future1 = CompletableFuture.allOf(*futures.toTypedArray())
        future1.join()

        logger.info("Caching - 1 - all ${futures.size} futures are promised, they should be fetched from the internet")

        val normUrls2 = urls.take(5).map { session.normalize(it) }
        val futures2 = session.loadAllAsync(normUrls2)
        val future2 = CompletableFuture.allOf(*futures2.toTypedArray())
        future2.join()

        logger.info("Cached - 2 - all ${futures2.size} futures are promised, they should be loaded from PDCache")

        assertEquals(futures.size, futures2.size)

        val pages = futures.map { it.get() }
        val pages2 = futures2.map { it.get() }

        pages.forEach { assertTrue("Should be fetched from internet | $it") { it.isFetched } }
        pages2.forEach { assertTrue("Should be loaded from PDCache | $it") { it.isCached } }
        pages2.forEach { assertTrue { it.loadEventHandlers != null } }
        assertEquals(pages.size, pages2.size)

        logger.info("Tested - whenLoadAllAsyncSecondlyWithoutExpiry_thenPagesAreLoadedFromCache")
    }
}
