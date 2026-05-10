package ai.platon.pulsar.skeleton.crawl.fetch.driver

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.skeleton.workflow.fetch.driver.NavigateHistory
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriverException
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId

/**
 * The Browser defines methods and events to manipulate a real browser.
 */
interface Browser: AutoCloseable {
    /**
     * The unique browser id
     * */
    val id: BrowserId
    /**
     * The browser instance id
     * */
    val instanceId: Int

    val host: String

    val port: Int

    /**
     * The user agent. A user agent is a string that a browser sends to each website you visit.
     * It's created when the browser first connected to the remote browser.
     * */
    val userAgent: String
    /**
     * The navigation history.
     * */
    val navigateHistory: NavigateHistory
    /**
     * The created drivers by this browser.
     * */
    val drivers: Map<String, WebDriver>
    /**
     * The front driver who has called bringToFront()
     * */
    val frontDriver: WebDriver?
    /**
     * The associated data.
     * */
    val data: MutableMap<String, Any?>
    /**
     * Check if this browser is active.
     * */
    val isActive: Boolean
    /**
     * Check if this browser is closed.
     * */
    val isClosed: Boolean
    /**
     * Check if this browser is open.
     * */
    val isConnected: Boolean
    /**
     * The browser settings.
     * */
    val settings: BrowserSettings
    /**
     * Check if this browser is idle.
     * */
    val isIdle: Boolean
    /**
     * Check if this browser is permanent.
     *
     * If a browser is permanent:
     * - it will not be closed when the browser is idle
     * - the user data will be kept after the browser is closed
     * */
    val isPermanent: Boolean
    /**
     * The status of this browser.
     * */
    val readableState: String
    /**
     * Create a new driver.
     * */
    @Throws(WebDriverException::class)
    fun newDriver(): WebDriver
    /**
     * Create a new driver.
     * */
    @Throws(WebDriverException::class)
    fun newDriver(url: String): WebDriver
    /**
     * List all drivers, each driver is associated with a Chrome tab.
     * */
    @Throws(WebDriverException::class)
    suspend fun listDrivers(): List<WebDriver>
    /**
     * Find the first driver by url.
     * */
    @Throws(WebDriverException::class)
    suspend fun findDriver(url: String): WebDriver?
    /**
     * Find the first driver by url regex.
     * */
    @Throws(WebDriverException::class)
    suspend fun findDriver(urlRegex: Regex): WebDriver?
    /**
     * Find drivers by url regex.
     * */
    @Throws(WebDriverException::class)
    suspend fun findDrivers(urlRegex: Regex): List<WebDriver>
    /**
     * Destroy the web driver, close the associated browser tabs.
     * */
    @Throws(WebDriverException::class)
    fun destroyDriver(driver: WebDriver)
    /**
     * Destroy the browser forcibly, kill the associated browser processes, release all allocated resources,
     * regardless of whether the browser is closed or not.
     * */
    @Throws(WebDriverException::class)
    fun destroyForcibly()
    /**
     * Clear all cookies.
     * Notice: even if we clear all cookies, the website still has some technology to track a session.
     * */
    @Throws(WebDriverException::class)
    suspend fun clearCookies()
}
