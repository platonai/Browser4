package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.workflow.fetch.driver.AbstractBrowser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import kotlinx.coroutines.runBlocking

/**
 * An auxiliary class can securely close the browser driver pool and associated browser,
 * and keep the browser drive pool and related browser status consistent.
 * */
internal class BrowserAccompaniedDriverPoolCloser(
    private val driverPoolPool: ConcurrentStatefulDriverPoolPool,
    private val driverPoolManager: WebDriverPoolManager,
) {
    private val browserManager get() = driverPoolManager.browserManager

    private val workingDriverPools get() = driverPoolPool.workingDriverPools

    private val retiredDriverPools get() = driverPoolPool.retiredDriverPools

    private val logger = getLogger(this)

    /**
     * Mark the browser as retired, remove it from the working driver pool, so it can no longer be used for new tasks.
     * All drivers in the browser are marked as retired, any task should be canceled who is using an inactive driver.
     *
     * All drivers in a driver pool are created by the same browser. The associated browser has to be closed if
     * we close a driver pool, and vice versa.
     * */
    @Synchronized
    fun closeGracefully(browserId: BrowserId) {
        kotlin.runCatching { doClose(browserId) }.onFailure { warnInterruptible(this, it) }
    }

    @Synchronized
    fun closeOldestRetiredDriverPoolSafely() {
        val dyingDriverPool = findOldestRetiredDriverPoolOrNull()

        if (dyingDriverPool != null) {
            closeBrowserAccompaniedDriverPool(dyingDriverPool)
        }
    }

    /**
     * Close the driver pool and the associated browser if it is not permanent and is idle.
     * */
    @Synchronized
    fun closeIdleDriverPoolsSafely() {
        // TODO: just mark them to be retired
//        workingDriverPools.values.filter { it.isIdle }.forEach {
//            it.retire()
//        }

        workingDriverPools.values.asSequence()
            .filter { !it.isPermanent }
            .filter { it.isIdle }
            .forEach { driverPool ->
                logger.info("Driver pool is idle, closing it ... | {}", driverPool.browserId)
                logger.info(driverPool.takeSnapshot().format(true))
                runCatching { closeBrowserAccompaniedDriverPool(driverPool) }.onFailure { warnInterruptible(this, it) }
            }
    }

    /**
     * Close unexpected active browsers.
     *
     * If the browser is in the closed list, it means the browser is not active, and we can close it.
     * */
    @Synchronized
    fun closeUnexpectedActiveBrowsers() {
        driverPoolPool.closedDriverPools.forEach { browserId ->
            val browser = browserManager.findBrowserOrNull(browserId)
            if (browser != null) {
                logger.warn("Browser should be closed, but still in active list, closing them ... | {}", browserId)
                runCatching { browserManager.closeBrowser(browserId) }.onFailure { warnInterruptible(this, it) }
            }
        }
    }

    private fun doClose(browserId: BrowserId) {
        // Force the page to stop all navigations and RELEASE all resources.
        // mark the driver pool be retired, but not closed yet
        val retiredDriverPool = driverPoolPool.retire(browserId)

        if (retiredDriverPool != null) {
            closeBrowserAccompaniedDriverPool(retiredDriverPool)
        } else {

        }
    }

    private fun doCloseWithDiagnosis(browserId: BrowserId) {
        // Force the page to stop all navigations and RELEASE all resources.
        // mark the driver pool be retired, but not closed yet
        val retiredDriverPool = driverPoolPool.retire(browserId)

        if (retiredDriverPool != null) {
            openInformationPages(browserId)
        }

        closeLeastValuableDriverPool(browserId, retiredDriverPool)
    }

    private fun openInformationPages(browserId: BrowserId) {
        if (!driverPoolManager.isActive) {
            // do not say anything to a browser when it's dying
            return
        }

        val browser = browserManager.findBrowserOrNull(browserId) ?: return
        if (browser.settings.isGUI) {
            // open for diagnosis
            val urls = listOf("chrome://version/", "chrome://history/")
            runBlocking { urls.forEach { openInformationPage(it, browser) } }
        }
    }

    private suspend fun openInformationPage(url: String, browser: Browser) {
        runCatching { browser.newDriver().navigate(url) }.onFailure { warnInterruptible(this, it) }
    }

    private fun closeLeastValuableDriverPool(browserId: BrowserId, retiredDriverPool: LoadingWebDriverPool?) {
        val browser = browserManager.findBrowserOrNull(browserId) ?: return

        val isGUI = browser.settings.isGUI
        val displayMode = browser.settings.displayMode

        // Keep some web drivers in GUI mode open for diagnostic purposes.
        val dyingDriverPool = when {
            !isGUI -> retiredDriverPool
            // The drivers are in GUI mode and there is many open drivers.
            else -> findOldestRetiredDriverPoolOrNull()
        }

        if (dyingDriverPool != null) {
            closeBrowserAccompaniedDriverPool(dyingDriverPool)
        } else {
            logger.info("Web drivers are in {} mode, please close them manually | {} ", displayMode, browserId)
        }
    }

    private fun closeBrowserAccompaniedDriverPool(driverPool: LoadingWebDriverPool) {
        val browser = browserManager.findBrowserOrNull(driverPool.browserId)
        if (browser != null) {
            closeBrowserAccompaniedDriverPool(browser, driverPool)
        } else {
            kotlin.runCatching { driverPoolPool.close(driverPool) }.onFailure { warnInterruptible(this, it) }
        }
    }

    private fun closeBrowserAccompaniedDriverPool(browser: Browser, driverPool: LoadingWebDriverPool) {
        require(browser.id == driverPool.browserId) { "Browser id not match \n${browser.id}\n${driverPool.browserId}" }
        require(browser is AbstractBrowser)

        val browserId = browser.id
        val isGUI = browser.settings.isGUI
        val displayMode = browser.settings.displayMode

        logger.info("Closing browser & driver pool with {} mode | #{} | {} | {} | {}",
            displayMode, browser.instanceId, browser.readableState, browserId.contextDir.last(), browserId.contextDir)

        kotlin.runCatching { driverPoolPool.close(driverPool) }.onFailure { warnInterruptible(this, it) }
        kotlin.runCatching { browserManager.closeBrowser(browser) }.onFailure { warnInterruptible(this, it) }
    }

    private fun findOldestRetiredDriverPoolOrNull(): LoadingWebDriverPool? {
        // Find out the oldest retired driver pool
        val oldestRetiredDriverPool = driverPoolManager.retiredDriverPools.values
            .minByOrNull { it.lastActiveTime } ?: return null
        // Issue #17: when counting dying drivers, all drivers in all pools should be counted.
        val totalDyingDrivers = driverPoolManager.retiredDriverPools.values.sumOf { it.numCreated }

        if (logger.isTraceEnabled) {
            logger.trace(
                "There are {} dying drivers in {} retired driver pools",
                totalDyingDrivers, driverPoolManager.retiredDriverPools.size
            )
        }

        val maxAllowedDyingDrivers = driverPoolManager.maxAllowedDyingDrivers
        return when {
            // low memory
            AppSystemInfo.isSystemOverCriticalLoad -> oldestRetiredDriverPool
            // The drivers are in GUI mode and there are many open drivers.
            totalDyingDrivers > maxAllowedDyingDrivers -> oldestRetiredDriverPool
            else -> null
        }
    }
}
