package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.workflow.fetch.driver.*
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

open class BasicBrowserManager(
    override val browserFactory: BrowserFactory,
    val conf: ImmutableConfig
) : BrowserManager {
    private val logger = getLogger(this)
    private val closed = AtomicBoolean()
    private val _browsers = ConcurrentHashMap<BrowserId, Browser>()
    private val historicalBrowsers = ConcurrentLinkedDeque<Browser>()
    private val closedBrowsers = ConcurrentLinkedDeque<Browser>()

    override val settings: BrowserSettings get() = browserFactory.settings

    /**
     * The active browsers
     * */
    override val browsers: Map<BrowserId, Browser> get() = _browsers

    /**
     * Check if the browser is active.
     * */
    fun isActive(browserId: BrowserId): Boolean {
        val browser = findBrowserOrNull(browserId) as? AbstractBrowser
        return browser != null && browser.isActive
    }

    @Throws(BrowserLaunchException::class)
    override fun launch(profileMode: BrowserProfileMode): Browser = browserFactory.launch(profileMode).also { browser ->
        _browsers[browser.id] = browser
    }

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launch(browserId: BrowserId, settings: BrowserSettings): Browser =
        browserFactory.launch(browserId, settings).also { browser ->
            _browsers[browser.id] = browser
        }

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchSystemDefaultBrowser(): Browser = browserFactory.launchSystemDefaultBrowser().also { browser ->
        _browsers[browser.id] = browser
    }

    /**
     * Launch the default browser, notice, the default browser is not the one you used daily.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchDefaultBrowser(): Browser = browserFactory.launchDefaultBrowser().also { browser ->
        _browsers[browser.id] = browser
    }

    /**
     * Launch the prototype browser, the prototype browser is a browser instance with default settings.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchPrototypeBrowser(): Browser = browserFactory.launchPrototypeBrowser().also { browser ->
        _browsers[browser.id] = browser
    }

    /**
     * Launch a random temporary browser, the browser's user data dir is a random temporary dir.
     * */
    @Throws(BrowserLaunchException::class)
    override fun launchRandomTempBrowser(): Browser = browserFactory.launchRandomTempBrowser().also { browser ->
        _browsers[browser.id] = browser
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
        browser?.let { closeBrowser(browser) }
    }

    @Synchronized
    override fun closeBrowser(browser: Browser) {
        _browsers.remove(browser.id)
        kotlin.runCatching { browser.close() }.onFailure { warnForClose(this, it) }
        closedBrowsers.add(browser)
    }

    @Synchronized
    fun destroyBrowserForcibly(browserId: BrowserId) {
        historicalBrowsers.filter { browserId == it.id }.forEach { browser ->
            kotlin.runCatching { browser.destroyForcibly() }.onFailure { warnInterruptible(this, it) }
            closedBrowsers.add(browser)
        }
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
