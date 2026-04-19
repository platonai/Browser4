package ai.platon.pulsar.protocol.browser.impl

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.skeleton.crawl.fetch.driver.*
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

open class BasicBrowserManager(
    val browserFactory: BrowserFactory,
    val conf: ImmutableConfig
) : BrowserManager {
    private val logger = getLogger(this)
    private val closed = AtomicBoolean()
    private val _browsers = ConcurrentHashMap<BrowserId, Browser>()
    private val historicalBrowsers = ConcurrentLinkedDeque<Browser>()
    private val closedBrowsers = ConcurrentLinkedDeque<Browser>()

    /**
     * The active browsers
     * */
    override val browsers: Map<BrowserId, Browser> = _browsers

    /**
     * Check if the browser is active.
     * */
    fun isActive(browserId: BrowserId): Boolean {
        val browser = findBrowserOrNull(browserId) as? AbstractBrowser
        return browser != null && browser.isActive
    }

    /**
     * Find an existing browser by id.
     * If the browser is not found, return null.
     *
     * @param browserId The browser id
     * @return The browser or null if not found
     * */
    @Synchronized
    override fun findBrowserOrNull(browserId: BrowserId): Browser? = browsers[browserId]

    /**
     * Close a browser.
     * */
    @Synchronized
    override fun closeBrowser(browserId: BrowserId) {
        val browser = _browsers.remove(browserId)
        if (browser is AbstractBrowser) {
            kotlin.runCatching { browser.close() }.onFailure { warnForClose(this, it) }
            closedBrowsers.add(browser)
        }
    }

    @Synchronized
    fun destroyBrowserForcibly(browserId: BrowserId) {
        historicalBrowsers.filter { browserId == it.id }.forEach { browser ->
            kotlin.runCatching { browser.destroyForcibly() }.onFailure { warnInterruptible(this, it) }
            closedBrowsers.add(browser)
        }
    }

    @Synchronized
    override fun closeBrowser(browser: Browser) {
        closeBrowser(browser.id)
    }

    @Synchronized
    override fun closeDriver(driver: WebDriver) {
        kotlin.runCatching { driver.close() }.onFailure { warnForClose(this, it) }
    }

    @Synchronized
    fun findLeastValuableDriver(): WebDriver? {
        val drivers = browsers.values.flatMap { it.drivers.values }
        return findLeastValuableDriver(drivers)
    }

    @Synchronized
    fun closeLeastValuableDriver() {
        val driver = findLeastValuableDriver()
        if (driver != null) {
            closeDriver(driver)
        }
    }

    /**
     * Destroy the zombie browsers forcibly, kill the associated browser processes,
     * release all allocated resources, regardless of whether the browser is closed or not.
     * */
    @Synchronized
    fun destroyZombieBrowsersForcibly() {
        val zombieBrowsers = historicalBrowsers - browsers.values.toSet() - closedBrowsers
        if (zombieBrowsers.isNotEmpty()) {
            logger.warn("There are {} zombie browsers, cleaning them ...", zombieBrowsers.size)
            zombieBrowsers.forEach { browser ->
                logger.info("Closing zombie browser | {}", browser.id.contextDir)
                kotlin.runCatching { browser.destroyForcibly() }.onFailure { warnInterruptible(this, it) }
            }
        }
    }

    fun maintain() {
        browsers.values.forEach {
            require(it is AbstractBrowser)
            it.emit(BrowserEvents.willMaintain)
            it.emit(BrowserEvents.maintain)
            it.emit(BrowserEvents.didMaintain)
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            _browsers.values.forEach { browser ->
                require(browser is AbstractBrowser)
                kotlin.runCatching { browser.close() }.onFailure { warnForClose(this, it) }
            }
            _browsers.clear()
        }
    }

    private fun findLeastValuableDriver(drivers: Iterable<WebDriver>): WebDriver? {
        return drivers.filterIsInstance<AbstractWebDriver>()
            .filter { !it.isReady && !it.isWorking }
            .minByOrNull { it.lastActiveTime }
    }
}
