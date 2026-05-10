package ai.platon.pulsar.skeleton.workflow.fetch.driver

import ai.platon.browser4.driver.chrome.NetworkResourceResponse
import ai.platon.browser4.driver.chrome.dom.Locator
import ai.platon.browser4.driver.chrome.dom.SnapshotService
import ai.platon.browser4.driver.chrome.dom.model.ElementRefCriteria
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.dom.nodes.GeoAnchor
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ModelResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.annotations.Beta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.Duration.Companion.milliseconds

@Suppress("unused")
abstract class AbstractWebDriver(
    override val guid: String,
    override val browser: AbstractBrowser,
    override val id: Int = ID_SUPPLIER.incrementAndGet()
) : Comparable<AbstractWebDriver>, WebDriver {
    companion object {
        private val ID_SUPPLIER = AtomicInteger()
    }

    /**
     * Lifecycle-aware base implementation of [WebDriver].
     *
     * Responsibilities:
     * - Maintain a lightweight navigation/session state (no heavy browser objects are stored here).
     * - Provide higher-level convenience operations (attribute/property selection, scrolling helpers, delays).
     * - Coordinate AI-assisted action generation (Text-To-Action) and dispatch via AI tool executors.
     * - Bridge between low-level browser protocol implementations and higher-level crawling / task logic.
     *
     * Threading model:
     * - Instances are NOT intended to be shared concurrently across multiple coroutines performing conflicting
     *   navigation or interaction tasks. External pool managers ensure exclusive assignment while in WORKING state.
     * - Atomic flags/refs (state, canceled, crashed) allow safe status inspection.
     *
     * Reuse model:
     * - A driver may be marked recyclable; once a task completes it transitions to READY and can be reused.
     * - Non‑recyclable drivers (isRecyclable = false) are kept until explicitly closed / retired.
     *
     * State transitions (expected typical flow):
     *   INIT -> READY -> WORKING -> READY (reuse) -> RETIRED -> QUIT
     *   INIT -> WORKING (direct fast start) -> ...
     *   Any -> RETIRED (external decision) -> QUIT (eventual disposal)
     *
     * Cancellation vs retirement:
     * - cancel(): attempt to abort current activity but keep resources for reuse.
     * - retire(): schedule the driver for disposal; it should not accept new work.
     *
     * Crash semantics:
     * - A crashed flag indicates browser level failure; pools should treat the instance as non‑reusable and close it.
     */
    /**
     * The state of the driver.
     * * [State.INIT]: The driver is initialized.
     * * [State.READY]: The driver is ready to work.
     * * [State.WORKING]: The driver is working.
     * * [State.RETIRED]: The driver is retired and should be quit as soon as possible.
     * * [State.QUIT]: The driver is quit.
     * */
    enum class State {
        /**
         * Driver object created, underlying browser context may still be initializing.
         */
        INIT,

        /**
         * Ready to accept a task. No navigation or interaction is currently in progress.
         */
        READY,

        /**
         * A task (navigation / interaction pipeline) is in progress.
         */
        WORKING,

        /**
         * Marked for disposal. Should not receive new tasks. Will be closed ASAP.
         */
        RETIRED,

        /**
         * Fully closed: resources released and no further operations are valid.
         */
        QUIT;

        /** Whether the driver is initialized. */
        val isInit get() = this == INIT

        /** Whether the driver is ready to work. */
        val isReady get() = this == READY

        /** Whether the driver is working. */
        val isWorking get() = this == WORKING

        /** Whether the driver is quit. */
        val isQuit get() = this == QUIT

        /** Whether the driver is retired and will be closed. */
        val isRetired get() = this == RETIRED
    }

    override val parentSid: Int = -1

    /** Atomic lifecycle state */
    private val state = AtomicReference(State.INIT)

    private val canceled = AtomicBoolean()
    private val crashed = AtomicBoolean()

    val settings get() = browser.settings

    /** Probability applied to entries in [probabilisticBlockedURLs]. Value range: [0,1]. */
    val resourceBlockProbability get() = settings.resourceBlockProbability

    // URL pattern lists (simple wildcard / regex semantics are enforced externally in the browser layer)
    protected val _blockedURLPatterns = mutableListOf<String>()
    protected val _probabilityBlockedURLPatterns = mutableListOf<String>()
    val blockedURLs: List<String> get() = _blockedURLPatterns
    val probabilisticBlockedURLs: List<String> get() = _probabilityBlockedURLPatterns

    /** Whether browser connection / session channel is still live */
    val isConnectable get() = browser.isConnected

    /** Accumulates init scripts (preload scripts) until first navigation. */
    protected val initScriptCache = mutableListOf<String>()

    private val jsoupCreateDestroyMonitor = Any()
    private var jsoupSession: Connection? = null

    private val config get() = browser.settings.config

    open val chatModel get() = ChatModelFactory.getOrCreateOrNull(config)
    open val implementation: Any = this

    // TODO: Will move to WebDriver
    @Beta
    open val snapshotService: SnapshotService? = null

    /** Idle timeout before a READY driver is considered stale and eligible for recycling/retirement. */
    var idleTimeout: Duration = Duration.ofMinutes(10)
    var lastActiveTime: Instant = Instant.now()

    /** Driver considered idle if [idleTimeout] elapsed since [lastActiveTime]. */
    val isIdle get() = Duration.between(lastActiveTime, Instant.now()) > idleTimeout

    val isActive get() = AppContext.isActive

    val isInit get() = state.get().isInit
    val isReady get() = state.get().isReady
    val isWorking get() = state.get().isWorking
    val isRetired get() = state.get().isRetired
    val isQuit get() = state.get().isQuit

    /** True if a cooperative cancellation has been requested for current task. */
    val isCanceled get() = canceled.get()

    /** True if underlying browser / protocol crashed. */
    val isCrashed get() = crashed.get()

    /** Human-readable composite status string (e.g. WORKING,IDLE or READY,REUSED). */
    val status: String
        get() {
            val sb = StringBuilder()
            val st = state.get() ?: return ""
            val s = when (st) {
                State.INIT -> "INIT"
                State.READY -> "READY"
                State.WORKING -> "WORKING"
                State.RETIRED -> "RETIRED"
                State.QUIT -> "QUIT"
            }
            sb.append(s)
            if (isCrashed) sb.append(",CRASHED")
            if (isCanceled) sb.append(",CANCELED")
            if (isIdle) sb.append(",IDLE")
            if (isRecovered) sb.append(",RECOVERED")
            if (isReused) sb.append(",REUSED")
            return sb.toString()
        }

    /** True if pageSource is a mocked/fake value (e.g. offline / synthetic content). */
    open val isMockedPageSource: Boolean = false

    /** Driver participated in a recovery workflow (e.g. auto relaunch) after crash. */
    var isRecovered: Boolean = false

    /** Driver reused from pool after previous task. */
    var isReused: Boolean = false

    /**
     * If recyclable the driver returns to a standby pool post-task; else it remains dedicated until retired.
     */
    var isRecyclable: Boolean = true

    /** Skip DOM feature computation if true (performance optimization for pure interaction tasks). */
    var ignoreDOMFeatures: Boolean = false

    /** Human friendly logical name (class simple name + id). */
    open val name get() = javaClass.simpleName + "-" + id

    /** Navigate entry for the current page (URL + metadata). */
    override var navigateEntry: NavigateEntry = NavigateEntry("")

    /** History of navigation entries. */
    override val navigateHistory = NavigateHistory()

    /** Delay policy (per action random delay ranges) derived from browser settings. */
    override val delayPolicy by lazy { browser.settings.interactSettings.generateRestrictedDelayPolicy() }

    /** Timeout policy (per action max durations) derived from browser settings. */
    override val timeoutPolicy by lazy { browser.settings.interactSettings.generateRestrictedTimeoutPolicy() }

    /** Child frame drivers (if any) */
    override val frames: MutableList<WebDriver> = mutableListOf()

    /** Page opener driver (if window opened by script / click) */
    override var opener: WebDriver? = null

    /** Pages opened from this page (window.open, target=_blank, etc.) */
    override val outgoingPages: MutableSet<WebDriver> = mutableSetOf()

    /** Arbitrary contextual data store. */
    override val data: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Transition to READY (available for assignment). Resets cancellation/crash flags.
     * Must only be called after INIT or WORKING states.
     */
    fun free() {
        canceled.set(false)
        crashed.set(false)
        @Suppress("UNUSED_EXPRESSION")
        if (!isInit && !isWorking) {
            // Intentional no-op
            Unit
        }
        state.set(State.READY)
    }

    /**
     * Transition to WORKING (exclusive usage). Resets cancellation/crash flags.
     */
    fun startWork() {
        canceled.set(false)
        crashed.set(false)
        @Suppress("UNUSED_EXPRESSION")
        if (!isInit && !isReady) {
            // Intentional no-op
            Unit
        }
        state.set(State.WORKING)
    }

    /** Mark for disposal after current task. */
    fun retire() = state.set(State.RETIRED)

    /** Request cooperative cancellation of current task (best-effort). */
    fun cancel() {
        canceled.set(true)
    }

    val mainRequestHeaders: Map<String, Any> get() = navigateEntry.mainRequestHeaders

    val mainRequestCookies: List<Map<String, String>> get() = navigateEntry.mainRequestCookies

    val mainResponseStatus: Int get() = navigateEntry.mainResponseStatus

    val mainResponseStatusText: String get() = navigateEntry.mainResponseStatusText

    val mainResponseHeaders: Map<String, Any> get() = navigateEntry.mainResponseHeaders

    override suspend fun addInitScript(script: String) {
        initScriptCache.add(script)
    }

    @Throws(WebDriverException::class)
    override suspend fun navigate(url: String) = navigate(NavigateEntry(url))

    @Throws(WebDriverException::class)
    override suspend fun reload() {
        evaluate("location.reload(true)")
    }

    override suspend fun currentUrl(): String {
        return evaluate("document.URL", navigateEntry.url)
    }

    @Throws(WebDriverException::class)
    override suspend fun url() = evaluate("document.URL", "")

    @Throws(WebDriverException::class)
    override suspend fun documentURI() = evaluate("document.documentURI", "")

    @Throws(WebDriverException::class)
    override suspend fun baseURI() = evaluate("document.baseURI", "")

    override suspend fun title(): String {
        return evaluate("document.title")?.toString() ?: ""
    }

    @Throws(WebDriverException::class)
    override suspend fun referrer() = evaluate("document.referrer", "")

    @Throws(WebDriverException::class)
    override suspend fun chat(prompt: String, selector: String): ModelResponse {
        val chatModel = chatModel ?: return ModelResponse.LLM_NOT_AVAILABLE
        val textContent = selectFirstTextOrNull(selector) ?: return ModelResponse.EMPTY
        val textContent0 = textContent.take(chatModel.settings.maximumInputTokenLength)
        val prompt2 = "$prompt\n\n\nThe text content of the selected element:\n\n\n$textContent0"
        return chatModel.call(prompt2)
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(WebDriverException::class)
    override suspend fun <T> evaluate(expression: String, defaultValue: T): T {
        return evaluate(expression) as? T ?: defaultValue
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(WebDriverException::class)
    override suspend fun <T> evaluateValue(expression: String, defaultValue: T): T {
        return evaluateValue(expression) as? T ?: defaultValue
    }

    // --------------------------- Attribute helpers ---------------------------
    // The following group relies on injected __pulsar_utils__ helper functions inside the page context.

    @Throws(WebDriverException::class)
    override suspend fun scrollDown(count: Int): Double {
        repeat(count) { evaluate("window.scrollBy(0, 500);") }
        return (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0
    }

    @Throws(WebDriverException::class)
    override suspend fun scrollUp(count: Int): Double {
        evaluate("window.scrollBy(0, -500);")
        return (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0
    }

    @Throws(WebDriverException::class)
    override suspend fun scrollBy(pixels: Double, smooth: Boolean): Double {
        // Gather current viewport and document information
        val totalHeight =
            (evaluate("Math.min(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), 15000)") as? Number)?.toDouble()
                ?: 0.0
        val viewportHeight = (evaluate("window.innerHeight") as? Number)?.toDouble() ?: 800.0
        val currentY = (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0

        // Compute target position and clamp to valid range
        val maxScrollY = (totalHeight - viewportHeight).coerceAtLeast(0.0)
        val targetY = (currentY + pixels).coerceIn(0.0, maxScrollY)

        if (!smooth) {
            evaluate("window.scrollTo(0, $targetY)")
            return (evaluate("window.scrollY") as? Number)?.toDouble() ?: targetY
        }

        // Smooth scrolling in discrete steps: avoid async Promise issues causing evaluate() to return early
        val distance = targetY - currentY
        val absDistance = kotlin.math.abs(distance)
        // Step size based on viewport size, limit max steps to balance speed and smooth feel
        val stepSize = kotlin.math.max(64.0, kotlin.math.min(viewportHeight * 0.6, 600.0))
        var steps = kotlin.math.ceil(absDistance / stepSize).toInt().coerceIn(1, 25)
        // If distance is very small, jump directly in a single step
        if (absDistance < 10) steps = 1

        for (i in 1..steps) {
            if (!isActive || isCanceled) break
            val y = currentY + distance * i / steps
            evaluate("window.scrollTo(0, $y)")
            // Wait for current step to stabilize: poll until scrollY ~ target y (error <=2px) or timeout
            val stepStart = Instant.now()
            while (kotlin.math.abs(((evaluate("window.scrollY") as? Number)?.toDouble() ?: y) - y) > 2 &&
                Duration.between(stepStart, Instant.now()).toMillis() < 400 && isActive && !isCanceled
            ) {
                kotlinx.coroutines.delay(25.milliseconds)
            }
            // Minor buffer to allow lazy loading or layout jitter to settle
            kotlinx.coroutines.delay(10.milliseconds)
        }

        // Final alignment to targetY, ensure precise last position
        evaluate("window.scrollTo(0, $targetY)")
        val finalStart = Instant.now()
        while (kotlin.math.abs(((evaluate("window.scrollY") as? Number)?.toDouble() ?: targetY) - targetY) > 1 &&
            Duration.between(finalStart, Instant.now()).toMillis() < 1000 && isActive && !isCanceled
        ) {
            kotlinx.coroutines.delay(30.milliseconds)
        }

        // Return final scroll position
        return (evaluate("window.scrollY") as? Number)?.toDouble() ?: targetY
    }

    @Throws(WebDriverException::class)
    override suspend fun scrollToTop(): Double {
        // Use scrollBy with negative currentY to reach top ensuring consistent logic
        val currentY = (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0
        return scrollBy(-currentY, false)
    }

    @Throws(WebDriverException::class)
    override suspend fun scrollToBottom(): Double {
        // Compute delta to bottom using same clamping logic as scrollBy
        val totalHeight =
            (evaluate("Math.min(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), 15000)") as? Number)?.toDouble()
                ?: 0.0
        val viewportHeight = (evaluate("window.innerHeight") as? Number)?.toDouble() ?: 0.0
        val currentY = (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0
        val maxScrollY = (totalHeight - viewportHeight).coerceAtLeast(0.0)
        val delta = maxScrollY - currentY
        return scrollBy(delta, false)
    }

    @Throws(WebDriverException::class)
    override suspend fun scrollToMiddle(ratio: Double): Double {
        // Ratio defines relative position between top(0.0) and bottom(1.0)
        val r = ratio.coerceIn(0.0, 1.0)
        val totalHeight =
            (evaluate("Math.min(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), 15000)") as? Number)?.toDouble()
                ?: 0.0
        val viewportHeight = (evaluate("window.innerHeight") as? Number)?.toDouble() ?: 0.0
        val currentY = (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0
        val maxScrollY = (totalHeight - viewportHeight).coerceAtLeast(0.0)
        val targetY = maxScrollY * r
        val delta = targetY - currentY
        return scrollBy(delta, false)
    }

    /**
     * Scrolls to the position of the n-th viewport.
     *
     * @param n Viewport index (1 represents the first screen)
     * @param smooth Whether to use smooth scrolling
     * @return Actual scrollY pixel value after scrolling
     */
    override suspend fun scrollToViewport(n: Double, smooth: Boolean): Double {
        // Reuse scrollBy logic: compute delta to target viewport
        val viewportHeight = (evaluate("window.innerHeight") as? Number)?.toDouble() ?: 0.0
        val totalHeight =
            (evaluate("Math.min(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), 15000)") as? Number)?.toDouble()
                ?: 0.0
        val currentY = (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0
        val maxScrollY = (totalHeight - viewportHeight).coerceAtLeast(0.0)
        val targetY = ((n - 1.0) * viewportHeight).coerceIn(0.0, maxScrollY)
        val delta = targetY - currentY
        return scrollBy(delta, smooth)
    }

    @Throws(WebDriverException::class)
    override suspend fun outerHTML() = outerHTML(":root")

    @Throws(WebDriverException::class)
    override suspend fun textContent(selector: String?): String? {
        if (selector != null) {
            return selectFirstTextOrNull(selector)
        }
        return evaluateValue("document.body.textContent")?.toString()
    }

    @Throws(WebDriverException::class)
    override suspend fun extract(fields: Map<String, String>): Map<String, String?> {
        return fields.entries.associate { it.key to selectFirstTextOrNull(it.value) }
    }

    /**
     * Find hyperlinks in elements matching the selector.
     * NOTE: Currently constructs absolute URLs by concatenating baseURI and raw href; TODO: robust resolution & abs: support.
     */
    @Throws(WebDriverException::class)
    override suspend fun selectHyperlinks(selector: String, offset: Int, limit: Int): List<Hyperlink> {
        val baseURI = baseURI().trimEnd('/')
        return selectAttributeAll(selector, "href", offset, limit).map { Hyperlink("$baseURI/$it") }
    }

    /** Select anchors (link + coordinates) – placeholder implementation until util binding available. */
    @Throws(WebDriverException::class)
    override suspend fun selectAnchors(selector: String, offset: Int, limit: Int): List<GeoAnchor> {
        return selectAttributeAll(selector, "abs:href").drop(offset).take(limit).map { GeoAnchor(it, "") }
    }

    /** Select image URLs (abs:src) – placeholder implementation. */
    @Throws(WebDriverException::class)
    override suspend fun selectImages(selector: String, offset: Int, limit: Int): List<String> {
        return selectAttributeAll(selector, "abs:src").drop(offset).take(limit)
    }

    @Throws(WebDriverException::class)
    override suspend fun waitForSelector(selector: String) = waitForSelector(selector, timeout("waitForSelector"))

    @Throws(WebDriverException::class)
    override suspend fun waitForSelector(selector: String, action: suspend () -> Unit) =
        waitForSelector(selector, timeout("waitForSelector"), action)

    @Throws(WebDriverException::class)
    override suspend fun waitForNavigation(oldUrl: String) = waitForNavigation(oldUrl, timeout("waitForNavigation"))

    @Throws(WebDriverException::class)
    override suspend fun waitForNavigation(oldUrl: String, timeout: Duration): Duration {
        return waitUntil("waitForNavigation", timeout) { isNavigated(oldUrl) }
    }

    @Throws(WebDriverException::class)
    override suspend fun waitUntil(predicate: suspend () -> Boolean) = waitUntil(timeout("waitUntil"), predicate)
    override suspend fun waitUntil(timeout: Duration, predicate: suspend () -> Boolean) =
        waitUntil("waitUtil", timeout, predicate)

    /**
     * Generate a random delay in milliseconds for an action.
     * @param action Named action bucket (fallbacks: specific -> default -> provided fallback range).
     * @param fallback Used if policy values are missing / invalid.
     */
    fun randomDelayMillis(action: String, fallback: IntRange = 500..1000): Long {
        val default = delayPolicy["default"] ?: fallback
        var range = delayPolicy[action] ?: default
        if (range.first <= 0 || range.last > 10000) {
            range = fallback
        }
        return Random.nextInt(range).toLong()
    }

    fun timeout(action: String, fallback: Duration = Duration.ofSeconds(60)): Duration {
        return timeoutPolicy[action] ?: timeoutPolicy["default"] ?: timeoutPolicy[""] ?: fallback
    }

    protected suspend fun waitUntil(type: String, timeout: Duration, predicate: suspend () -> Boolean): Duration {
        return waitUntil(randomDelayMillis(type), timeout, predicate)
    }

    protected suspend fun waitUntil(timeMillis: Long, timeout: Duration, predicate: suspend () -> Boolean): Duration {
        val startTime = Instant.now()
        withTimeoutOrNull(timeout.toMillis().milliseconds) {
            while (!predicate()) {
                delay(timeMillis)
            }
        }
        return timeout - DateTimes.elapsedTime(startTime)
    }

    protected suspend fun <T> waitFor(type: String, timeout: Duration, supplier: suspend () -> T): T? {
        return withTimeoutOrNull(timeout.toMillis().milliseconds) {
            var result: T? = supplier()
            while (result == null) {
                gap(type)
                result = supplier()
            }
            result
        }
    }

    @Throws(WebDriverException::class)
    protected suspend fun isNavigated(oldUrl: String): Boolean {
        return oldUrl != currentUrl()
    }

    /**
     * Delays the coroutine for a given time without blocking a thread and resumes it after a specified time.
     *
     * This suspending function is cancellable. If the Job of the current coroutine is cancelled or completed while
     * this suspending function is waiting, this function immediately resumes with CancellationException.
     * */
    protected suspend fun gap() {
        if (!isActive) {
            return
        }

        delay(randomDelayMillis("gap"))
    }

    /**
     * Delays the coroutine for a given time without blocking a thread and resumes it after a specified time.
     *
     * This suspending function is cancellable. If the Job of the current coroutine is cancelled or completed while
     * this suspending function is waiting, this function immediately resumes with CancellationException.
     * */
    protected suspend fun gap(type: String) {
        if (!isActive) {
            return
        }

        delay(randomDelayMillis(type))
    }

    /**
     * Delays the coroutine for a given time without blocking a thread and resumes it after a specified time.
     *
     * This suspending function is cancellable. If the Job of the current coroutine is cancelled or completed while
     * this suspending function is waiting, this function immediately resumes with CancellationException.
     * */
    protected suspend fun gap(millis: Long) {
        if (!isActive) {
            return
        }

        delay(millis)
    }

    private fun getHeadersAndCookies(): Pair<Map<String, String>, List<Map<String, String>>> = runBlocking {
        val headers = mainRequestHeaders.entries.associate { it.key to it.value.toString() }
        val cookies = getCookies()
        headers to cookies
    }

    override suspend fun newJsoupSession(): Connection {
        val headers = mainRequestHeaders.entries.associate { it.key to it.value.toString() }
        val cookies = getCookies()
        return newSession(headers, cookies)
    }

    @Throws(IOException::class)
    override suspend fun loadJsoupResource(url: String): Connection.Response {
        val jsession: Connection = synchronized(jsoupCreateDestroyMonitor) { jsoupSession ?: createJsoupSession() }
        jsoupSession = jsession
        return withContext(Dispatchers.IO) { jsession.newRequest().url(url).execute() }
    }

    private fun createJsoupSession(): Connection {
        val (headers, cookies) = getHeadersAndCookies()
        return newSession(headers, cookies)
    }

    @Throws(IOException::class)
    override suspend fun loadResource(url: String): NetworkResourceResponse {
        return NetworkResourceHelper.fromJsoup(loadJsoupResource(url))
    }


    override fun equals(other: Any?): Boolean = this === other || (other is AbstractWebDriver && other.id == this.id)
    override fun hashCode(): Int = id
    override fun compareTo(other: AbstractWebDriver): Int = id - other.id
    override fun toString(): String = "#$id"


    open suspend fun terminate() {
        stop()
    }

    @Throws(Exception::class)
    open fun awaitTermination() { /* default no-op */
    }

    override fun close() {
        if (isQuit) return
        state.set(State.QUIT)
        runCatching { runBlocking { stop() } }.onFailure { warnForClose(this, it) }
    }

    private fun newSession(headers: Map<String, String>, cookies: List<Map<String, String>>): Connection {
        val httpTimeout = Duration.ofSeconds(20)
        val session = Jsoup.newSession()
            .timeout(httpTimeout.toMillis().toInt())
            .headers(headers)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
        session.userAgent(browser.userAgent)
        if (cookies.isNotEmpty()) {
            session.cookies(cookies.first())
        }
        val proxy = browser.id.fingerprint.proxyURI?.toString() ?: System.getenv("http_proxy")
        if (proxy != null && URLUtils.isStandard(proxy)) {
            val u = URLUtils.getURLOrNull2(proxy)
            if (u != null) {
                session.proxy(u.host, u.port)
            }
        }
        return session
    }

    fun checkState(action: String = ""): Boolean {
        if (!isActive) {
            return false
        }
        if (isCanceled) {
            return false
        }
        if (action.isNotBlank()) {
            lastActiveTime = Instant.now()
            navigateEntry.refresh(action)
        }
        return isActive
    }

    protected fun reportInjectedJs(scripts: String) {
        if (scripts.isBlank()) {
            return
        }
        val dir = browser.id.contextDir.resolve("driver.$id/js")
        Files.createDirectories(dir)
        val path = Files.writeString(dir.resolve("preload.all.js"), scripts)
        getTracerOrNull(this)?.trace("All injected js: {}", path.toUri())
    }
}
