package ai.platon.pulsar.skeleton.crawl.fetch.driver

import ai.platon.pulsar.browser.driver.chrome.NetworkResourceResponse
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.dom.nodes.GeoAnchor
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.skeleton.ai.PulsarActAgent
import ai.platon.pulsar.skeleton.ai.tta.ActionDescription
import ai.platon.pulsar.skeleton.ai.tta.ActionOptions
import ai.platon.pulsar.skeleton.ai.tta.InstructionResult
import ai.platon.pulsar.skeleton.ai.tta.TextToAction
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

abstract class AbstractWebDriver(
    val guid: String,
    override val browser: AbstractBrowser,
    override val id: Int = ID_SUPPLIER.incrementAndGet()
): Comparable<AbstractWebDriver>, AbstractJvmWebDriver(), WebDriver, JvmWebDriver {
    companion object {
        private val ID_SUPPLIER = AtomicInteger()
    }

    /**
     * Lifecycle-aware base implementation of [WebDriver].
     *
     * Responsibilities:
     * - Maintain a lightweight navigation/session state (no heavy browser objects are stored here).
     * - Provide higher-level convenience operations (attribute/property selection, scrolling helpers, delays).
     * - Coordinate AI-assisted action generation (Text-To-Action) and dispatch via [SimpleCommandDispatcher].
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

    private val chatModel get() = ChatModelFactory.getOrCreateOrNull(config)

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
    val status: String get() {
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
        if (!isInit && !isWorking) {
            // Intentionally left blank: avoiding exception to prevent pool inconsistency.
        }
        state.set(State.READY)
    }

    /**
     * Transition to WORKING (exclusive usage). Resets cancellation/crash flags.
     */
    fun startWork() {
        canceled.set(false)
        crashed.set(false)
        if (!isInit && !isReady) {
            // Intentionally left blank: soft transition without throwing.
        }
        state.set(State.WORKING)
    }

    /** Mark for disposal after current task. */
    fun retire() = state.set(State.RETIRED)
    /** Request cooperative cancellation of current task (best-effort). */
    fun cancel() { canceled.set(true) }

    override fun jvm(): JvmWebDriver = this

    @Suppress("unused")
    val mainRequestHeaders: Map<String, Any> get() = navigateEntry.mainRequestHeaders
    @Suppress("unused")
    val mainRequestCookies: List<Map<String, String>> get() = navigateEntry.mainRequestCookies
    @Suppress("unused")
    val mainResponseStatus: Int get() = navigateEntry.mainResponseStatus
    @Suppress("unused")
    val mainResponseStatusText: String get() = navigateEntry.mainResponseStatusText
    @Suppress("unused")
    val mainResponseHeaders: Map<String, Any> get() = navigateEntry.mainResponseHeaders

    override suspend fun addInitScript(script: String) { initScriptCache.add(script) }

    @Throws(WebDriverException::class)
    override suspend fun navigateTo(url: String) = navigateTo(NavigateEntry(url))

    override suspend fun currentUrl(): String = evaluate("document.URL", navigateEntry.url)
    @Throws(WebDriverException::class)
    override suspend fun url() = evaluate("document.URL", "")
    @Throws(WebDriverException::class)
    override suspend fun documentURI() = evaluate("document.documentURI", "")
    @Throws(WebDriverException::class)
    override suspend fun baseURI() = evaluate("document.baseURI", "")
    @Throws(WebDriverException::class)
    override suspend fun referrer() = evaluate("document.referrer", "")

    @Throws(WebDriverException::class)
    override suspend fun chat(prompt: String, selector: String): ModelResponse {
        val chatModel = chatModel ?: return ModelResponse.LLM_NOT_AVAILABLE
        val textContent = selectFirstTextOrNull(selector) ?: return ModelResponse.EMPTY
        val textContent0 = textContent.take(chatModel.settings.maximumLength)
        val prompt2 = "$prompt\n\n\nThere is the text content of the selected element:\n\n\n$textContent0"
        return chatModel.call(prompt2)
    }

    @Throws(WebDriverException::class)
    override suspend fun act(action: String): PulsarActAgent {
        return act(ActionOptions(action))
    }

    @Throws(WebDriverException::class)
    override suspend fun act(action: ActionOptions): PulsarActAgent {
        val agent = PulsarActAgent(this)
        agent.execute(action) // execute without shadowing
        return agent
    }

    @Throws(WebDriverException::class)
    override suspend fun act(action: ActionDescription): InstructionResult {
        if (action.functionCalls.isEmpty()) {
            return InstructionResult(listOf(), listOf(), action.modelResponse)
        }
        val functionCalls = action.functionCalls.take(1)
        val dispatcher = SimpleCommandDispatcher()
        val functionResults = functionCalls.map { fc -> dispatcher.execute(fc, this) }
        return InstructionResult(action.functionCalls, functionResults, action.modelResponse)
    }

    @Throws(WebDriverException::class)
    override suspend fun instruct(prompt: String): InstructionResult {
        val tta = TextToAction(config)
        val actions = tta.generateWebDriverActionsWithToolCallSpecs(prompt)
        val dispatcher = SimpleCommandDispatcher()
        val functionResults = actions.functionCalls.map { fc -> dispatcher.execute(fc, this) }
        return InstructionResult(actions.functionCalls, functionResults, actions.modelResponse)
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
    override suspend fun isVisible(selector: String): Boolean {
        return evaluate("__pulsar_utils__.isVisible('$selector')") == "true"
    }

    override suspend fun isChecked(selector: String): Boolean {
        return evaluate("__pulsar_utils__.isChecked('$selector')") == "true"
    }

    @Throws(WebDriverException::class)
    override suspend fun scrollDown(count: Int) { repeat(count) { evaluate("__pulsar_utils__.scrollDown()") } }
    @Throws(WebDriverException::class)
    override suspend fun scrollUp(count: Int) { evaluate("__pulsar_utils__.scrollUp()") }
    @Throws(WebDriverException::class)
    override suspend fun scrollToTop() { evaluate("__pulsar_utils__.scrollToTop()") }
    @Throws(WebDriverException::class)
    override suspend fun scrollToBottom() { evaluate("__pulsar_utils__.scrollToBottom()") }
    @Throws(WebDriverException::class)
    override suspend fun scrollToMiddle(ratio: Double) { evaluate("__pulsar_utils__.scrollToMiddle($ratio)") }
    @Throws(WebDriverException::class)
    override suspend fun scrollToScreen(screenNumber: Double) { evaluate("__pulsar_utils__.scrollToScreen($screenNumber)") }

    @Throws(WebDriverException::class)
    override suspend fun clickNthAnchor(n: Int, rootSelector: String): String? {
        val result = evaluate("__pulsar_utils__.clickNthAnchor($n, '$rootSelector')")
        return result?.toString()
    }

    @Throws(WebDriverException::class)
    override suspend fun outerHTML() = outerHTML(":root")
    @Throws(WebDriverException::class)
    override suspend fun outerHTML(selector: String): String? { return evaluate("__pulsar_utils__.outerHTML('$selector')")?.toString() }
    @Throws(WebDriverException::class)
    override suspend fun selectFirstTextOrNull(selector: String): String? { return evaluate("__pulsar_utils__.selectFirstText('$selector')")?.toString() }

    @Throws(WebDriverException::class)
    override suspend fun selectTextAll(selector: String): List<String> {
        val json = evaluate("__pulsar_utils__.selectTextAll('$selector')")?.toString() ?: "[]"
        return jacksonObjectMapper().readValue(json)
    }

    @Throws(WebDriverException::class)
    override suspend fun selectFirstAttributeOrNull(selector: String, attrName: String): String? {
        return evaluate("__pulsar_utils__.selectFirstAttribute('$selector', '$attrName')")?.toString()
    }

    override suspend fun selectAttributes(selector: String): Map<String, String> {
        val json = evaluate("__pulsar_utils__.selectAttributes('$selector')")?.toString() ?: return mapOf()
        val attributes: List<String> = jacksonObjectMapper().readValue(json)
        return attributes.zipWithNext().associate { it }
    }

    @Throws(WebDriverException::class)
    override suspend fun selectAttributeAll(selector: String, attrName: String, start: Int, limit: Int): List<String> {
        val end = start + limit
        val expression = "__pulsar_utils__.selectAttributeAll('$selector', '$attrName', $start, $end)"
        val json = evaluate(expression)?.toString() ?: return listOf()
        return jacksonObjectMapper().readValue(json)
    }

    @Throws(WebDriverException::class)
    override suspend fun setAttribute(selector: String, attrName: String, attrValue: String) { evaluate("__pulsar_utils__.setAttribute('$selector', '$attrName', '$attrValue')") }
    @Throws(WebDriverException::class)
    override suspend fun setAttributeAll(selector: String, attrName: String, attrValue: String) { evaluate("__pulsar_utils__.setAttributeAll('$selector', '$attrName', '$attrValue')") }

    // --------------------------- Property helpers ---------------------------
    @Throws(WebDriverException::class)
    override suspend fun selectFirstPropertyValueOrNull(selector: String, propName: String): String? {
        return evaluate("__pulsar_utils__.selectFirstPropertyValue('$selector', '$propName')")?.toString()
    }

    @Throws(WebDriverException::class)
    override suspend fun selectPropertyValueAll(selector: String, propName: String, start: Int, limit: Int): List<String> {
        val end = start + limit
        val expression = "__pulsar_utils__.selectPropertyValueAll('$selector', '$propName', $start, $end)"
        val json = evaluate(expression)?.toString() ?: return listOf()
        return jacksonObjectMapper().readValue(json)
    }

    @Throws(WebDriverException::class)
    override suspend fun setProperty(selector: String, propName: String, propValue: String) { evaluate("__pulsar_utils__.setProperty('$selector', '$propName', '$propValue')") }
    @Throws(WebDriverException::class)
    override suspend fun setPropertyAll(selector: String, propName: String, propValue: String) { evaluate("__pulsar_utils__.setPropertyAll('$selector', '$propName', '$propValue')") }

    /**
     * Find hyperlinks in elements matching the selector.
     * NOTE: Currently constructs absolute URLs by concatenating baseURI and raw href; TODO: robust resolution & abs: support.
     */
    @Throws(WebDriverException::class)
    override suspend fun selectHyperlinks(selector: String, offset: Int, limit: Int): List<Hyperlink> {
        // TODO: add __pulsar_utils__.selectHyperlinks() and proper relative URL resolution
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
    override suspend fun clickTextMatches(selector: String, pattern: String, count: Int) { evaluate("__pulsar_utils__.clickTextMatches('$selector', '$pattern')") }
    @Throws(WebDriverException::class)
    override suspend fun clickMatches(selector: String, attrName: String, pattern: String, count: Int) { evaluate("__pulsar_utils__.clickMatches('$selector', '$attrName', '$pattern')") }
    @Throws(WebDriverException::class)
    override suspend fun check(selector: String) { evaluate("__pulsar_utils__.check('$selector')") }
    @Throws(WebDriverException::class)
    override suspend fun uncheck(selector: String) { evaluate("__pulsar_utils__.uncheck('$selector')") }

    @Throws(WebDriverException::class)
    override suspend fun waitForSelector(selector: String) = waitForSelector(selector, timeout("waitForSelector"))
    @Throws(WebDriverException::class)
    override suspend fun waitForSelector(selector: String, action: suspend () -> Unit) =
        waitForSelector(selector, timeout("waitForSelector"), action)
    @Throws(WebDriverException::class)
    override suspend fun waitForNavigation(oldUrl: String) = waitForNavigation(oldUrl, timeout("waitForNavigation"))
    @Throws(WebDriverException::class)
    override suspend fun waitForNavigation(oldUrl: String, timeout: Duration): Duration { return waitUntil("waitForNavigation", timeout) { isNavigated(oldUrl) } }
    @Throws(WebDriverException::class)
    override suspend fun waitUntil(predicate: suspend () -> Boolean) = waitUntil(timeout("waitUntil"), predicate)
    override suspend fun waitUntil(timeout: Duration, predicate: suspend () -> Boolean) = waitUntil("waitUtil", timeout, predicate)

    protected suspend fun waitUntil(type: String, timeout: Duration, predicate: suspend () -> Boolean): Duration {
        val startTime = Instant.now()
        var elapsedTime = Duration.ZERO
        while (elapsedTime < timeout && !predicate()) {
            gap(type)
            elapsedTime = DateTimes.elapsedTime(startTime)
        }
        return timeout - elapsedTime
    }

    protected suspend fun <T> waitFor(type: String, timeout: Duration, supplier: suspend () -> T): T? {
        val startTime = Instant.now()
        var elapsedTime = Duration.ZERO
        var result: T? = supplier()
        while (elapsedTime < timeout && result == null) {
            gap(type)
            result = supplier()
            elapsedTime = DateTimes.elapsedTime(startTime)
        }
        return result
    }

    @Throws(WebDriverException::class)
    protected suspend fun isNavigated(oldUrl: String): Boolean { return oldUrl != currentUrl() }

    /**
     * Delays the coroutine for a given time without blocking a thread and resumes it after a specified time.
     *
     * This suspending function is cancellable. If the Job of the current coroutine is cancelled or completed while
     * this suspending function is waiting, this function immediately resumes with CancellationException.
     * */
    protected suspend fun gap() { if (!isActive) return; delay(randomDelayMillis("gap")) }
    /**
     * Delays the coroutine for a given time without blocking a thread and resumes it after a specified time.
     *
     * This suspending function is cancellable. If the Job of the current coroutine is cancelled or completed while
     * this suspending function is waiting, this function immediately resumes with CancellationException.
     * */
    protected suspend fun gap(type: String) { if (!isActive) return; delay(randomDelayMillis(type)) }
    /**
     * Delays the coroutine for a given time without blocking a thread and resumes it after a specified time.
     *
     * This suspending function is cancellable. If the Job of the current coroutine is cancelled or completed while
     * this suspending function is waiting, this function immediately resumes with CancellationException.
     * */
    protected suspend fun gap(millis: Long) { if (!isActive) return; delay(millis) }

    /**
     * Generate a random delay in milliseconds for an action.
     * @param action Named action bucket (fallbacks: specific -> default -> provided fallback range).
     * @param fallback Used if policy values are missing / invalid.
     */
    fun randomDelayMillis(action: String, fallback: IntRange = 500..1000): Long {
        val default = delayPolicy["default"] ?: fallback
        var range = delayPolicy[action] ?: default
        if (range.first <= 0 || range.last > 10000) { range = fallback }
        return Random.nextInt(range).toLong()
    }

    fun timeout(action: String, fallback: Duration = Duration.ofSeconds(60)): Duration {
        return timeoutPolicy[action] ?: timeoutPolicy["default"] ?: timeoutPolicy[""] ?: fallback
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
    override suspend fun loadResource(url: String): NetworkResourceResponse { return NetworkResourceHelper.fromJsoup(loadJsoupResource(url)) }

    @Suppress("unused")
    override fun equals(other: Any?): Boolean = this === other || (other is AbstractWebDriver && other.id == this.id)
    override fun hashCode(): Int = id
    override fun compareTo(other: AbstractWebDriver): Int = id - other.id
    override fun toString(): String = "#$id"

    @Suppress("unused")
    open suspend fun terminate() { stop() }
    @Throws(Exception::class)
    open fun awaitTermination() { /* default no-op */ }

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
        if (cookies.isNotEmpty()) { session.cookies(cookies.first()) }
        val proxy = browser.id.fingerprint.proxyURI?.toString() ?: System.getenv("http_proxy")
        if (proxy != null && URLUtils.isStandard(proxy)) {
            val u = URLUtils.getURLOrNull(proxy)
            if (u != null) { session.proxy(u.host, u.port) }
        }
        return session
    }

    fun checkState(action: String = ""): Boolean {
        if (!isActive) { return false }
        if (isCanceled) { return false }
        if (action.isNotBlank()) {
            lastActiveTime = Instant.now()
            navigateEntry.refresh(action)
        }
        return isActive
    }

    protected fun reportInjectedJs(scripts: String) {
        if (scripts.isBlank()) { return }
        val dir = browser.id.contextDir.resolve("driver.$id/js")
        Files.createDirectories(dir)
        val path = Files.writeString(dir.resolve("preload.all.js"), scripts)
        getTracerOrNull(this)?.trace("All injected js: {}", path.toUri())
    }

    // Default implementations for new Playwright-aligned evaluate API
    // Subclasses should override these with backend-specific implementations
    
    override suspend fun evaluate(expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions): Any? {
        // Default implementation delegates to simple evaluate
        return evaluate(expressionOrFunction)
    }

    override suspend fun <T> evaluateJson(expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions): T {
        val result = evaluate(expressionOrFunction, args = args, options = options)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override suspend fun evaluateHandle(expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions): JsHandle {
        throw UnsupportedOperationException("evaluateHandle not implemented for this driver type")
    }

    override suspend fun evaluateOnSelector(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions): Any? {
        // Default implementation: select element first, then evaluate
        val expression = """
            (function(selector, fn, ...args) {
                const element = document.querySelector(selector);
                if (!element) return null;
                return fn.apply(element, args);
            })('$selector', $expressionOrFunction, ${args.joinToString(",")})
        """.trimIndent()
        return evaluate(expression)
    }

    override suspend fun <T> evaluateOnSelectorJson(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions): T {
        val result = evaluateOnSelector(selector, expressionOrFunction, args = args, options = options)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override suspend fun evaluateOnSelectorAll(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions): Any? {
        val expression = """
            (function(selector, fn, ...args) {
                const elements = Array.from(document.querySelectorAll(selector));
                return elements.map(el => fn.apply(el, args));
            })('$selector', $expressionOrFunction, ${args.joinToString(",")})
        """.trimIndent()
        return evaluate(expression)
    }

    override suspend fun evaluateOnSelectorHandle(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions): JsHandle {
        throw UnsupportedOperationException("evaluateOnSelectorHandle not implemented for this driver type")
    }

    override suspend fun waitForFunction(expressionOrFunction: String, options: WaitForFunctionOptions, vararg args: Any?): Any? {
        // Default implementation: simple polling
        val startTime = System.currentTimeMillis()
        val timeoutMs = options.timeout?.toMillis() ?: 30000L
        val pollingMs = when (val polling = options.polling) {
            is Polling.RAF -> 16L // ~60fps
            is Polling.Interval -> polling.ms
        }

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result = evaluate(expressionOrFunction)
            // Check if the function returns a truthy value
            if (result != null && result != false && result != 0 && result != "") {
                return result
            }
            kotlinx.coroutines.delay(pollingMs)
        }

        throw EvaluationTimeoutException(
            timeout = options.timeout ?: java.time.Duration.ofSeconds(30),
            message = "waitForFunction timed out after ${timeoutMs}ms",
            driver = this,
            expression = expressionOrFunction,
            args = args.toList()
        )
    }
}
