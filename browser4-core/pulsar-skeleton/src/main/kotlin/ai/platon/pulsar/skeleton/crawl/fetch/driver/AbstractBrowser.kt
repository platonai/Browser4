package ai.platon.pulsar.skeleton.crawl.fetch.driver

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.event.AbstractEventEmitter
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractBrowser(
    override val id: BrowserId,
    override val settings: BrowserSettings
): Browser, AutoCloseable, AbstractEventEmitter<BrowserEvents>() {
    companion object {
        protected val SEQUENCER = AtomicInteger()
        val DEFAULT_USER_AGENT = "Browser4 Agent/1.0"
    }

    private val logger = getLogger(this)

    /**
     * All drivers, including the recovered drivers and the reused drivers.
     * */
    protected val mutableDrivers = ConcurrentHashMap<String, WebDriver>()
    protected val mutableRecoveredDrivers = ConcurrentHashMap<String, WebDriver>()
    protected val mutableReusedDrivers = ConcurrentHashMap<String, WebDriver>()

    protected val initialized = AtomicBoolean()
    private val closed = AtomicBoolean()
    protected var lastActiveTime = Instant.now()

    override val instanceId: Int = SEQUENCER.incrementAndGet()

    override val host = "localhost"

    override val port = 0

    override val userAgent get() = DEFAULT_USER_AGENT

    var userAgentOverride = getRandomUserAgentOrNull()

    override val navigateHistory = NavigateHistory()

    override val drivers: Map<String, WebDriver> get() = mutableDrivers

    override var frontDriver: WebDriver? = null

    /**
     * The associated data.
     * */
    override val data: MutableMap<String, Any?> = mutableMapOf()

    override val isConnected: Boolean get() = isActive

    override val isIdle get() = Duration.between(lastActiveTime, Instant.now()) > idleTimeout

    override val isPermanent: Boolean get() = id.profile.isPermanent

    override val isActive get() = AppContext.isActive && !isClosed // && initialized.get()

    override val isClosed get() = closed.get()

    override val readableState: String get() = buildReadableState()

    val isGUI get() = settings.isGUI
    val idleTimeout = Duration.ofMinutes(10)

    init {
        attach()
    }

    abstract fun recoverUnmanagedPages()

    //    @Synchronized
    @Throws(WebDriverException::class)
    override suspend fun listDrivers(): List<WebDriver> {
        recoverUnmanagedPages()
        return drivers.values.toList()
    }

    //    @Synchronized
    @Throws(WebDriverException::class)
    override suspend fun findDriver(url: String): AbstractWebDriver? {
        recoverUnmanagedPages()
        return drivers.values.filterIsInstance<AbstractWebDriver>().firstOrNull { currentUrl(it) == url }
    }

    override suspend fun findDriver(urlRegex: Regex): WebDriver? {
        recoverUnmanagedPages()
        return drivers.values.filterIsInstance<AbstractWebDriver>().firstOrNull { currentUrl(it).matches(urlRegex) }
    }

    override suspend fun findDrivers(urlRegex: Regex): List<WebDriver> {
        recoverUnmanagedPages()
        return drivers.values.filterIsInstance<AbstractWebDriver>().filter { currentUrl(it).matches(urlRegex) }
    }

    @Throws(WebDriverException::class)
    fun findDriverById(id: Int): AbstractWebDriver? {
        recoverUnmanagedPages()
        return drivers.values.filterIsInstance<AbstractWebDriver>().firstOrNull { it.id == id }
    }

    @Throws(WebDriverException::class)
    fun findDriverByGUID(guid: String): AbstractWebDriver? {
        recoverUnmanagedPages()
        return drivers[guid] as? AbstractWebDriver
    }

    protected suspend fun currentUrl(driver: WebDriver) = driver.currentUrl()

    override fun destroyDriver(driver: WebDriver) {
        // Nothing to do
    }

    override fun destroyForcibly() {

    }

    override suspend fun clearCookies() {
        val driver = drivers.values.firstOrNull() ?: newDriver()
        driver.clearBrowserCookies()
    }

    fun onInitialize() {
        initialized.set(true)
    }

    fun onWillNavigate(entry: NavigateEntry) {
        navigateHistory.add(entry)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            detach()
            mutableRecoveredDrivers.clear()
            mutableDrivers.values.filterIsInstance<AbstractWebDriver>().forEach {
                // tasks are return as soon as possible and should be cancelled
                it.cancel()
                // the driver should be retired
                it.retire()
            }
            mutableDrivers.values.forEach { runCatching { it.close() }.onFailure { warnForClose(this, it) } }
            mutableDrivers.clear()
        }
    }

    open fun maintain() {
        // Nothing to do
    }

    private fun getRandomUserAgentOrNull() = if (settings.isUserAgentOverridingEnabled) {
        settings.userAgent.getRandomUserAgent()
    } else null

    /**
     * Attach default event handlers
     * */
    protected fun attach() {
        on(BrowserEvents.initialize) { onInitialize() }
        on(BrowserEvents.willNavigate) { entry: NavigateEntry -> onWillNavigate(entry) }
        on(BrowserEvents.maintain) { maintain() }
    }

    /**
     * Detach default event handlers
     * */
    protected fun detach() {
        off(BrowserEvents.initialize)
        off(BrowserEvents.willNavigate)
        off(BrowserEvents.maintain)
    }

    private fun buildReadableState(): String {
        val sb = StringBuilder()
        if (isActive) {
            sb.append("Active")
        } else {
            sb.append("Inactive")
        }
        if (isClosed) {
            sb.append(",Closed")
        }
        if (isPermanent) {
            sb.append(",Permanent")
        }
        if (isIdle) {
            sb.append(",Idle")
        }
        if (isConnected) {
            sb.append(",Connected")
        } else {
            sb.append(",Disconnected")
        }
        return sb.toString()
    }
}
