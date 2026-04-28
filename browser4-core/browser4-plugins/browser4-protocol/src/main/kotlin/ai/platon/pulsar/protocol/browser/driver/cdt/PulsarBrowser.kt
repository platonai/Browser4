package ai.platon.pulsar.protocol.browser.driver.cdt

import ai.platon.browser4.driver.chrome.*
import ai.platon.browser4.driver.chrome.experimental.CDP
import ai.platon.browser4.driver.chrome.impl.ChromeImpl
import ai.platon.browser4.driver.chrome.impl.ChromeImpl.Companion.ABOUT_BLANK_PAGE
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.browser4.driver.chrome.util.ChromeIOException
import ai.platon.browser4.driver.chrome.util.ChromeServiceException
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_REUSE_RECOVERED_DRIVERS
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.workflow.fetch.driver.*
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class PulsarBrowser(
    id: BrowserId,
    val chrome: RemoteChrome,
    settings: BrowserSettings,
    private val launcher: ChromeLauncher?
) : AbstractBrowser(id, settings) {

    private val logger = LoggerFactory.getLogger(PulsarBrowser::class.java)

    private val closed = AtomicBoolean()

    private val toolsConfig = DevToolsConfig()

    private val conf get() = settings.config

    private val reuseRecoveredDriver get() = conf.getBoolean(BROWSER_REUSE_RECOVERED_DRIVERS, false)

    override val host: String get() = chrome.host

    override val port: Int get() = chrome.port

    override val isConnected: Boolean get() = isActive && chrome.canConnect()

    override val isActive get() = super.isActive && chrome.isActive

    override val userAgent get() = chrome.version.userAgent ?: DEFAULT_USER_AGENT

    init {
        // It's safe to register multiple times, the manager will be closed only once, and the browsers
        // will be closed in the manager's close function.
        launcher?.let { PulsarContexts.registerClosable(it, Int.MIN_VALUE) }
    }

    constructor(port: Int, settings: BrowserSettings = BrowserSettings()) :
            this(BrowserId.RANDOM_TEMP, ChromeImpl(port = port), settings, null)

    @Synchronized
    @Throws(WebDriverException::class)
    fun createTab() = createTab(ABOUT_BLANK_PAGE)

    @Synchronized
    @Throws(WebDriverException::class)
    fun createTab(url: String): ChromeTab {
        lastActiveTime = Instant.now()
        try {
            return chrome.createTab(url)
        } catch (e: ChromeIOException) {
            throw BrowserUnavailableException("createTab", e)
        } catch (e: ChromeServiceException) {
            throw WebDriverException("createTab", e)
        }
    }

    @Synchronized
    @Throws(WebDriverException::class)
    fun listTabs(): Array<ChromeTab> {
        try {
            return chrome.listTabs()
        } catch (e: ChromeIOException) {
            throw BrowserUnavailableException("listTabs", e)
        } catch (e: ChromeServiceException) {
            if (!isActive) {
                return arrayOf()
            }
            throw WebDriverException("listTabs", e)
        }
    }

    @Synchronized
    @Throws(WebDriverException::class)
    fun closeTab(tab: ChromeTab) {
        logger.debug("Closing tab | {}", tab.url)
        try {
            if (!isActive) {
                return
            }

            chrome.closeTab(tab)
        } catch (e: ChromeIOException) {
            logger.info("Failed to close tab | {}", e.message)
            // throw IllegalWebDriverStateException("closeTab", e)
        } catch (e: ChromeServiceException) {
            throw WebDriverException("closeTab", e)
        }
    }

    @Synchronized
    @Throws(WebDriverException::class)
    override fun newDriver() = newDriver(ABOUT_BLANK_PAGE)

    @Synchronized
    @Throws(WebDriverException::class)
    override fun newDriver(url: String): PulsarWebDriver {
        try {
            // In chrome every tab is a separate process
            val chromeTab = createTab(url)
            val driver = newDriverIfAbsent(chromeTab, false)
            // driver is newly created
            return driver
        } catch (e: ChromeIOException) {
            throw BrowserUnavailableException("newDriver", e)
        } catch (e: ChromeDriverException) {
            logger.warn("Failed to create new driver, rethrow | {}", e.message)
            throw WebDriverException("Failed to create chrome devtools driver | " + e.message)
        }
    }

    override fun destroyDriver(driver: WebDriver) {
        if (driver is PulsarWebDriver) {
            val chromeTab = driver.chromeTab
            val chromeTabId = chromeTab.id

            mutableRecoveredDrivers.remove(chromeTabId)
            mutableReusedDrivers.remove(chromeTabId)
            mutableDrivers.remove(chromeTabId)

            runCatching { driver.doClose() }.onFailure { warnForClose(this, it) }

            try {
                closeTab(driver.chromeTab)
            } catch (e: WebDriverException) {
                if (isActive) {
                    throw e
                }
            } catch (e: Exception) {
                warnInterruptible(this, e, "Failed to close tab")
            }
        }
    }

    override fun maintain() {
        recoverUnmanagedPages()
        closeRecoveredIdleDrivers()
    }

    /**
     * Destroy the browser and its associated resources.
     * */
    @Synchronized
    override fun destroyForcibly() {
        runCatching {
            close()
            launcher?.destroyForcibly()
        }.onFailure { warnForClose(this, it) }
    }

    /**
     * Closing call stack:
     *
     * PrivacyContextManager.close -> PrivacyContext.close -> WebDriverContext.close -> WebDriverPoolManager.close
     * -> BrowserManager.close -> Browser.close -> WebDriver.close
     * |-> LoadingWebDriverPool.close()
     *
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            kotlin.runCatching { doClose() }.onFailure { warnForClose(this, it) }
        }
    }

    /**
     * Create a new driver and add it to the driver tree.
     * */
    private fun newDriverIfAbsent(chromeTab: ChromeTab, recovered: Boolean): PulsarWebDriver {
        // a Chrome tab id is like 'AE740895CB3F63220C3A3C751EF1F6E4'
        val uniqueID = chromeTab.id
        var driver = mutableDrivers[uniqueID]
        if (driver is PulsarWebDriver) {
            return driver
        }

        driver = doNewDriver(chromeTab, recovered)

        addToDriverTree(driver)

        return driver
    }

    private fun doNewDriver(chromeTab: ChromeTab, recovered: Boolean): PulsarWebDriver {
        if (!recovered && reuseRecoveredDriver) {
            val driver = mutableRecoveredDrivers.values.firstOrNull { it is PulsarWebDriver && !it.isReused }
            if (driver is PulsarWebDriver) {
                driver.isReused = true
                mutableReusedDrivers[driver.chromeTab.id] = driver
                logger.info("Reuse recovered driver | {}", chromeTab.url)
                return driver
            }
        }

        val uniqueID = chromeTab.id
        val devTools = createDevTools(chromeTab, toolsConfig)
        val cdp = CDP(devTools)
        val driver = PulsarWebDriver(uniqueID, chromeTab, cdp, this)
        mutableDrivers[chromeTab.id] = driver

        if (recovered) {
            driver.isRecovered = true
            mutableRecoveredDrivers[chromeTab.id] = driver
        }

        return driver
    }

    private fun addToDriverTree(driver: WebDriver) {
        if (driver !is PulsarWebDriver) {
            return
        }

        val parentId = driver.chromeTab.parentId ?: return
        val parent = drivers[parentId]
        if (parent !is PulsarWebDriver) {
            return
        }

        driver.opener = parent
        parent.outgoingPages.add(driver)

        logger.info(
            "Add driver to tree | parent: {}, child: {} | {}",
            parent.chromeTab.url,
            driver.chromeTab.url,
            driver.chromeTab.id
        )
    }

    /**
     * Pages can be open in the browser, for example, by a click. We should recover the page
     * and create a web driver to manage it.
     *
     * TODO: capture events that open new pages
     * */
    override fun recoverUnmanagedPages() {
        try {
            recoverUnmanagedPages0()
        } catch (e: WebDriverException) {
            if (isActive) {
                logger.warn("Failed to recover unmanaged pages | {}", e.message)
            } else {
                logger.info("No page recovering, browser is closed.")
            }
        }
    }

    @Throws(WebDriverException::class)
    private fun recoverUnmanagedPages0() {
        val tabs = listTabs()
        // the tab id is the key of the driver in drivers
        tabs.filter { it.id !in drivers.keys } // it is not created yet
            .filter { it.isPageType() } // handler HTML document only
            .filter { URLUtils.isStandard(it.url) } // make sure the url is correct
            .forEach { tab ->
                // create a new driver and associate it with the tab
                val driver = newDriverIfAbsent(tab, true)
                reportNewDriver(tab, driver)
            }
    }

    private fun reportNewDriver(tab: ChromeTab, driver: WebDriver) {
        val parentId = tab.parentId
        if (parentId != null) {
            logger.info(
                "Recover tab {} with parent: {} | driver: {}, opener: {}, siblings: {} | {}",
                tab.id, tab.parentId,
                driver.id, driver.opener?.id, driver.opener?.outgoingPages?.size ?: 0,
                tab.url
            )
        } else {
            logger.info("Recover tab {} with no parent | driver: {} | {}", tab.id, driver.id, tab.url)
        }
    }

    private fun closeRecoveredIdleDrivers() {
        val chromeDrivers = drivers.values.filterIsInstance<PulsarWebDriver>()

        val pageLoadTimeout = settings.interactSettings.pageLoadTimeout
        val seconds = if (AppSystemInfo.isSystemOverCriticalLoad) 15L else pageLoadTimeout.seconds
        val unmanagedTabTimeout = Duration.ofSeconds(seconds)
        val isIdle = { driver: AbstractWebDriver ->
            Duration.between(driver.lastActiveTime, Instant.now()) > unmanagedTabTimeout }
        val unmanagedTimeoutDrivers = chromeDrivers.filter { it.isRecovered && !it.isReused && isIdle(it) }
        if (unmanagedTimeoutDrivers.isNotEmpty()) {
            logger.debug("Closing {} unmanaged drivers", unmanagedTimeoutDrivers.size)
            val hasHistory = unmanagedTimeoutDrivers.any { it.navigateHistory.isEmpty() }
            if (hasHistory) {
                logger.warn("Unmanaged driver should has no history, this indicates a bug")
            }
//            require(unmanagedTimeoutDrivers.all { it.navigateHistory.isEmpty() }) {
//                "Unmanaged driver should have no history"
//            }
            unmanagedTimeoutDrivers.forEach { destroyDriver(it) }
        }
    }

    private fun doClose() {
        closeDrivers()

        // if all drivers are closed, it means that all the tabs are closed and so the browser is closed.
        // but, we may not hold all the open tabs, so we still need close the chrome explicitly.
        // it's safe to close the browser multiple times and even if the remote browser is already closed.
        chrome.close()

        // if the browser is closed, it means the launcher is also closed.
        // it's safe to close the browser multiple times and even if the remote browser is already closed.
        launcher?.close()

        logger.info(
            "Browser is closed successfully | #{} | history: {} | {} | {} | {}",
            instanceId, navigateHistory.size, readableState, id.contextDir.last(), id.contextDir
        )
    }

    private fun closeDrivers() {
        val dyingDrivers = drivers.toList().ifEmpty { return@closeDrivers }

        mutableRecoveredDrivers.clear()
        mutableReusedDrivers.clear()
        mutableDrivers.clear()

        logger.info("Closing browser with {} drivers/devtools ... | {}", dyingDrivers.size, id.contextDir.toUri())

        dyingDrivers.forEach { (id, driver) ->
            kotlin.runCatching { driver.close() }.onFailure { warnForClose(this, it) }
        }
    }

    @Synchronized
    @Throws(WebDriverException::class)
    private fun createDevTools(tab: ChromeTab, config: DevToolsConfig): RemoteDevTools {
        return kotlin.runCatching { chrome.createDevTools(tab, config) }
            .getOrElse { throw WebDriverException("createDevTools", it) }
    }
}
