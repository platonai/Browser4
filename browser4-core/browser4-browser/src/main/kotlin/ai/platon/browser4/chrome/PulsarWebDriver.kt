package ai.platon.browser4.chrome

import ai.platon.browser4.chrome.dom.model.AriaSnapshotOptions
import ai.platon.browser4.chrome.network.*
import ai.platon.browser4.api.snapshot.ViewportSpec
import ai.platon.browser4.chrome.protocol.ClickableDOM
import ai.platon.browser4.chrome.protocol.EmulationHandler
import ai.platon.browser4.chrome.protocol.PageHandler
import ai.platon.browser4.chrome.protocol.ScreenshotHandler
import ai.platon.browser4.chrome.protocol.transport.ChromeImpl
import ai.platon.browser4.chrome.protocol.util.CheckableElementJs
import ai.platon.browser4.chrome.protocol.util.withNodeObjectId
import ai.platon.browser4.chrome.util.ChromeDriverException
import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.Credentials
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSent
import ai.platon.cdt.kt.protocol.events.network.ResponseReceived
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.events.page.WindowOpen
import ai.platon.cdt.kt.protocol.types.fetch.RequestPattern
import ai.platon.cdt.kt.protocol.types.page.PrintToPDFTransferMode
import ai.platon.cdt.kt.protocol.types.network.Cookie
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourceOptions
import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.cdt.kt.protocol.types.runtime.CallArgument
import ai.platon.browser4.api.AbstractWebDriver
import ai.platon.browser4.api.WebDriver
import ai.platon.browser4.api.model.*
import ai.platon.browser4.api.BrowserProtocol
import ai.platon.browser4.api.model.BrowserTab
import ai.platon.browser4.api.model.NetworkResourceResponse
import ai.platon.browser4.api.model.NodeRef
import ai.platon.browser4.api.snapshot.SnapshotService
import ai.platon.browser4.api.model.BrowserUseState
import ai.platon.browser4.api.model.NanoDOMTree
import ai.platon.browser4.api.model.PageTarget
import ai.platon.browser4.api.model.SnapshotOptions
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.math.geometric.OffsetD
import ai.platon.pulsar.common.math.geometric.PointD
import ai.platon.pulsar.common.math.geometric.RectD
import ai.platon.pulsar.common.urls.URLUtils
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.commons.lang3.StringUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

open class PulsarWebDriver constructor(
    uniqueID: String,
    val chromeTab: BrowserTab,
    val browserProtocol: BrowserProtocol,
    override val browser: PulsarBrowser
) : AbstractWebDriver(uniqueID, browser) {
    companion object {
        private val jsonMapper: ObjectMapper = jacksonObjectMapper()
        private val nonNullJsonMapper: ObjectMapper = jacksonObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

        // -----------------------------------------------------------------------
        // Injected JavaScript — extracted from inline string literals for
        // testability, syntax highlighting, and to eliminate Kotlin ${'$'} escaping.
        // -----------------------------------------------------------------------

        /** Used by [selectOption] to manipulate <select> elements via CDP callFunctionOn. */
        val SELECT_OPTION_JS = """function(jsonValues){const values=JSON.parse(jsonValues);const element=this;if(!element||element.tagName!=='SELECT'){throw new Error('Element is not a <select> element');}const optionsToSelect=new Set(values);const selectedValues=[];let hasChanged=false;if(!element.multiple){for(let i=0;i<element.options.length;i++){const option=element.options[i];if(optionsToSelect.has(option.value)||optionsToSelect.has(option.label)||optionsToSelect.has(option.text)){if(!option.selected){option.selected=true;hasChanged=true;}selectedValues.push(option.value);break;}}}else{for(let i=0;i<element.options.length;i++){const option=element.options[i];const shouldSelect=optionsToSelect.has(option.value)||optionsToSelect.has(option.label)||optionsToSelect.has(option.text);if(shouldSelect!=option.selected){option.selected=shouldSelect;hasChanged=true;}if(shouldSelect){selectedValues.push(option.value);}}}if(hasChanged){element.dispatchEvent(new Event('input',{bubbles:true}));element.dispatchEvent(new Event('change',{bubbles:true}));}return selectedValues;}"""

        /** Used by [generateLocator] to build a unique CSS selector for an element. */
        val GENERATE_LOCATOR_JS = """element=>{if(!element||element.nodeType!==1)return null;function cssEscape(v){if(typeof CSS!=='undefined'&&CSS.escape)return CSS.escape(v);return v.replace(/[!"#$%&'()*+,./:;<=>?@[\]^`{|}~]/g,'\\$&');}function segmentFor(el){var tag=el.tagName.toLowerCase();if(el.id)return '#'+cssEscape(el.id);if(el.classList&&el.classList.length>0){var classes=Array.from(el.classList).filter(function(c){return!/[A-Z]/.test(c)&&!/^[a-z]+-[a-z0-9]{6,}$/.test(c)&&c.indexOf('_')===-1&&c.length>1;});if(classes.length>0)return tag+'.'+classes.map(cssEscape).join('.');}if(el.parentNode){var siblings=Array.from(el.parentNode.children);var sameTag=siblings.filter(function(s){return s.tagName===el.tagName;});if(sameTag.length>1){return tag+':nth-of-type('+(sameTag.indexOf(el)+1)+')';}}return tag;}var parts=[];var cur=element;while(cur&&cur.nodeType===1){parts.unshift(segmentFor(cur));if(cur.id)break;if(cur.tagName.toLowerCase()==='body')break;cur=cur.parentNode;}return parts.join(' > ');}"""

        /** Used by [selectFirstTextOrNull] to walk a DOM subtree collecting text. */
        val SELECT_FIRST_TEXT_JS = """function(){try{const el=this;const excluded=new Set(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE']);let text='';const walker=document.createTreeWalker(el,NodeFilter.SHOW_TEXT,{acceptNode(node){const p=node.parentNode;return p&&!excluded.has(p.nodeName)?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_REJECT;}});let n;while((n=walker.nextNode())){text+=n.nodeValue;}return text;}catch(e){return null;}}"""

        /** Used by [trySubmitFormOnEnter] as a safety net for CDP-dispatched Enter key. */
        val TRY_SUBMIT_FORM_ON_ENTER_JS = """(()=>{const el=document.activeElement;if(!el)return false;const tag=el.tagName;if(tag==='TEXTAREA')return false;if(tag!=='INPUT'&&tag!=='SELECT')return false;if(tag==='INPUT'){const t=(el.type||'text').toLowerCase();if(t==='radio'||t==='checkbox'||t==='file'||t==='button'||t==='reset'||t==='submit'||t==='image'||t==='hidden'){return false;}}const form=el.closest('form');if(!form)return false;if(typeof form.requestSubmit==='function'){try{form.requestSubmit();return true;}catch(e){}}form.submit();return true;})()"""
    }

    private data class StorageStatePayload(
        val cookies: List<Map<String, Any?>> = emptyList(),
        val origins: List<StorageStateOriginPayload> = emptyList(),
    )

    private data class StorageStateOriginPayload(
        val origin: String = "",
        val localStorage: List<StorageStateEntryPayload> = emptyList(),
    )

    private data class StorageStateEntryPayload(
        val name: String = "",
        val value: String = "",
    )

    private data class StorageStateLoadSummary(
        val cookies: Int,
        val origins: Int,
        val localStorageEntries: Int,
    )

    private val logger = getLogger(this)

    private val tracer get() = logger.takeIf { it.isTraceEnabled }

    override val browserType: BrowserType = BrowserType.PULSAR_CHROME

    val page = PageHandler(browserProtocol, settings)

    private val isolatedWorldManager get() = page.isolatedWorldManager
    private val js get() = page.js
    private val mouse get() = page.mouse.takeIf { isActive }
    private val keyboard get() = page.keyboard.takeIf { isActive }
    private val screenshot = ScreenshotHandler(page, browserProtocol)
    private val emulator get() = EmulationHandler(browserProtocol, keyboard, mouse)

    private val rpc = RobustRPC(this)
    private val networkManager by lazy { NetworkManager(rpc, browserProtocol) }
    private val messageWriter = MultiSinkMessageWriter()

    private val driverHelper get() = WebDriverHelper(this, rpc, page, browserProtocol)

    private val closed = AtomicBoolean()

    /**
     * The last ChromeDriverException that was caught and swallowed (returning null to the caller).
     * Callers can check this field to distinguish "no result" from "error occurred".
     * */
    @Volatile
    var lastError: ChromeDriverException? = null

    var navigateUrl: String? = chromeTab.url
    private var credentials: Credentials? = null

    /** Cached pre-compiled regex patterns for [probabilisticBlockedURLs] to avoid recompilation on every network request. */
    @Volatile
    private var cachedProbabilisticBlockedRegexes: List<Regex>? = null

    val isNetworkIdle get() = networkManager.isIdle

    var fingerprintApplier: ((WebDriver) -> Unit)? = null

    /**
     * Shared helper: suppress ChromeIOException when the tab is already closed (normal operational state).
     * Re-throws if the tab is still open — the exception then indicates a real problem.
     */
    private fun propagateIfOpen(e: ChromeIOException) {
        if (e.isOpen && browserProtocol.isOpen) throw e
    }

    /**
     * Expose the underlying implementation, used for diagnosis purpose
     * */
    override val implementation: Any get() = browserProtocol

    override val snapshotService: SnapshotService get() = page.snapshot

    init {
        fingerprintApplier?.invoke(this)
    }

    override val isOpen get() = browserProtocol.isOpen

    override suspend fun healthy(): CheckState {
        val state = quickCheckHealthy()
        if (!state.isOK) {
            return state
        }

        if (!browserProtocol.isTargetAlive()) {
            return CheckState(
                ResourceStatus.SC_SERVICE_UNAVAILABLE, "WebDriver service unavailable - the target page is not alive"
            )
        }

        return CheckState(0, "WebDriver is healthy")
    }

    override suspend fun addBlockedURLs(urlPatterns: List<String>) {
        _blockedURLPatterns.addAll(urlPatterns)
    }

    @Throws(WebDriverException::class)
    override suspend fun navigate(entry: NavigateEntry) {
        navigateUrl = entry.userTypedUrl

        navigateHistory.add(entry)
        this.navigateEntry = entry
        // Keep navigateUrl in sync so currentUrl() fallback returns the latest navigation
        // target instead of a stale value from tab creation.
        this.navigateUrl = entry.userTypedUrl

        browser.emit(BrowserEvents.willNavigate, entry)

        rpc.invokeOnPage("enableAPIAgents") {
            enableAPIAgents()
        }

        rpc.invokeOnPage("navigate") {
            navigateInvaded(entry)
        }
    }

    override suspend fun reload() {
        rpc.invokeOnPage("reload") {
            browserProtocol.reloadPage()
        }
    }

    override suspend fun goBack() {
        // Fetch navigation history once before the retry-able invokeOnPage block.
        // This prevents a race condition where a retry re-fetches history after
        // the first navigateToHistoryEntry call has already shifted currentIndex.
        val history = browserProtocol.getNavigationHistory()
        val currentIndex = history.currentIndex
        val entries = history.entries
        val targetIndex = currentIndex - 1
        if (targetIndex < 0 || targetIndex >= entries.size) {
            logger.warn(
                "goBack: cannot navigate backward, no previous entry exists. " +
                        "currentIndex={}, entries.size={}", currentIndex, entries.size
            )
            return
        }
        val entryId = entries[targetIndex].id

        rpc.invokeOnPage("goBack") {
            browserProtocol.navigateToHistoryEntry(entryId)
        }
    }

    override fun userTypedUrl(): String = navigateEntry.userTypedUrl

    override suspend fun goForward() {
        // Fetch navigation history once before the retry-able invokeOnPage block.
        // This prevents a race condition where a retry re-fetches history after
        // the first navigateToHistoryEntry call has already shifted currentIndex.
        val history = browserProtocol.getNavigationHistory()
        val currentIndex = history.currentIndex
        val entries = history.entries
        val targetIndex = currentIndex + 1
        if (targetIndex < 0 || targetIndex >= entries.size) {
            logger.warn(
                "goForward: cannot navigate forward, no next entry exists. " +
                        "currentIndex={}, entries.size={}", currentIndex, entries.size
            )
            return
        }
        val entryId = entries[targetIndex].id

        rpc.invokeOnPage("goForward") {
            browserProtocol.navigateToHistoryEntry(entryId)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun getCookies(): List<Map<String, String>> {
        return rpc.invokeOnPage("getCookies") { getCookies0() } ?: listOf()
    }

    override suspend fun deleteCookies(name: String, url: String?, domain: String?, path: String?) {
        rpc.invokeOnPage("deleteCookies") { cdpDeleteCookies(name, url, domain, path) }
    }

    override suspend fun clearBrowserCookies() {
        rpc.invokeOnPage("clearBrowserCookies") { browserProtocol.clearBrowserCookies() }
    }

    override suspend fun saveStorageState(): String {
        val mapper = nonNullJsonMapper
        val cookies = getCookies().map { toStorageStateCookie(it) }
        val origins = listOfNotNull(captureCurrentOriginLocalStorage())
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf(
                "cookies" to cookies,
                "origins" to origins,
            )
        )
    }

    override suspend fun loadStorageState(state: String): String {
        val mapper = nonNullJsonMapper
        val payload = mapper.readValue<StorageStatePayload>(state)
        val cookies = payload.cookies.map(::normalizeCookieForSet)
        if (cookies.isNotEmpty()) {
            browserProtocol.setCookies(cookies)
        }

        val originalUrl = currentUrl()
        var restoredOrigins = 0
        var restoredLocalStorageEntries = 0

        payload.origins.forEach { originState ->
            val origin = originState.origin.trim()
            require(origin.isNotEmpty()) { "Storage state origin must not be blank" }
            require(URLUtils.isStandard(origin)) { "Storage state origin must be a standard URL: $origin" }

            open(origin)
            restoreLocalStorage(originState.localStorage, mapper)
            restoredOrigins += 1
            restoredLocalStorageEntries += originState.localStorage.size
        }

        if (payload.origins.isNotEmpty() && originalUrl.isNotBlank() && currentUrl() != originalUrl) {
            open(originalUrl)
        }

        return mapper.writeValueAsString(
            StorageStateLoadSummary(
                cookies = cookies.size,
                origins = restoredOrigins,
                localStorageEntries = restoredLocalStorageEntries,
            )
        )
    }

    // Use the JavaScript version in super class
    override suspend fun selectFirstAttributeOrNull(selector: String, attrName: String): String? {
        val name = "selectFirstAttributeOrNull"
        return rpc.invokeOnElement(selector, name) {
            page.getAttribute(it, attrName)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluate(expression: String): Any? {
        return rpc.invokeOnPage("evaluate") { js.evaluate(expression) }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateDetail(expression: String): JsEvaluation? {
        return rpc.invokeOnPage("evaluateDetail") {
            driverHelper.createJsEvaluate(
                js.evaluateDetail(expression)
            )
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValue(expression: String): Any? {
        return rpc.invokeOnPage("evaluateValue") {
            js.evaluateValue(expression)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValueDetail(expression: String): JsEvaluation? {
        return evaluateValueDetail(expression, awaitPromise = false)
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValueDetail(expression: String, awaitPromise: Boolean): JsEvaluation? {
        return rpc.invokeOnPage("evaluateValueDetail") {
            val evaluate = js.evaluateValueDetail(expression, awaitPromise)
            driverHelper.createJsEvaluate(evaluate)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValue(selector: String, functionDeclaration: String): Any? {
        return evaluateValueDetail(selector, functionDeclaration)?.value
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValueDetail(selector: String, functionDeclaration: String): JsEvaluation? {
        return rpc.invokeOnPage("evaluateValue") {
            val normalizedFunctionDeclaration = normalizeElementFunctionDeclaration(functionDeclaration)
            val callFunctionOn = js.callFunctionOn(selector, normalizedFunctionDeclaration)
            driverHelper.createJsEvaluate(callFunctionOn)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun executeCdpCommand(method: String, params: Map<String, Any?>?): Any? {
        return rpc.invokeOnPage("executeCdpCommand") {
            browserProtocol.executeCdpCommand(method, params)
        }
    }

    private fun normalizeElementFunctionDeclaration(functionDeclaration: String): String {
        val callable = functionDeclaration.trim().removeSuffix(";").trim()
        return """
            function() {
                return ($callable).call(this, this);
            }
        """.trimIndent()
    }

    @Throws(WebDriverException::class)
    override suspend fun generateLocator(selector: String): String? {
        val result = evaluateValue(selector, GENERATE_LOCATOR_JS)
        return result?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    override suspend fun currentUrl(): String {
        return evaluate("document.URL", navigateUrl ?: "")
    }

    @Throws(WebDriverException::class)
    override suspend fun exists(selector: String): Boolean {
        return page.exists(selector)
    }

    /**
     * Wait until [selector] for [timeout] at most
     * */
    @Throws(WebDriverException::class)
    override suspend fun waitForSelector(selector: String, timeout: Duration, action: suspend () -> Unit): Duration {
        return waitUntil("waitForSelector", timeout) {
            val elementExists = exists(selector)
            if (!elementExists) {
                action()
            }
            elementExists
        }
    }

    @Suppress("unused") // Retained as an experimental alternative to AbstractWebDriver.waitForNavigation
    @Throws(WebDriverException::class)
    private suspend fun waitForNavigationExperimental(oldUrl: String, timeout: Duration): Duration {
        val startTime = Instant.now()

        try {
            val channel = Channel<String>()

            browserProtocol.onDocumentOpened {
                // keep oldUrl check for debugging / future use
                @Suppress("UNUSED_VARIABLE") val navigated = it.frame.url != oldUrl
                // emit(Navigation)
                channel.trySend("navigated")
            }

            channel.receive()
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "waitForNavigation $timeout")
        }

        return timeout - DateTimes.elapsedTime(startTime)
    }

    @Throws(WebDriverException::class)
    override suspend fun waitForPage(url: String, timeout: Duration): WebDriver? {
        return waitFor("waitForPage", timeout) { browser.findDriver(url) }
    }

    @Throws(WebDriverException::class)
    override suspend fun waitForFunction(pageFunction: String, timeout: Duration): WebDriver? {
        return waitFor("waitForFunction", timeout) {
            val res = evaluate(pageFunction)
            val isTruthy = res != null && res != false && res != "" && res != 0 && res != 0.0
            if (isTruthy) this else null
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun isVisible(selector: String): Boolean {
        return rpc.predicateOnPage("isVisible") {
            page.isVisible(selector)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun check(selector: String) {
        setChecked(selector, true)
    }

    @Throws(WebDriverException::class)
    override suspend fun uncheck(selector: String) {
        setChecked(selector, false)
    }

    override suspend fun isChecked(selector: String): Boolean {
        return page.isChecked(selector)
    }

    @Throws(WebDriverException::class)
    private suspend fun setChecked(selector: String, shouldCheck: Boolean) {
        val actionName = if (shouldCheck) "check" else "uncheck"
        rpc.invokeOnElement(selector, actionName, scrollIntoView = true) { node ->
            withNodeObjectId(browserProtocol, node) { objectId ->
                val result = browserProtocol.callFunctionOn(
                    CheckableElementJs.SET_CHECKED_FUNCTION_DECLARATION,
                    objectId = objectId,
                    arguments = listOf(CallArgument(value = shouldCheck)),
                    returnByValue = true,
                    userGesture = true,
                    awaitPromise = true
                )

                if (result.exceptionDetails != null) {
                    throw WebDriverException("JS Error in $actionName: " + result.exceptionDetails?.exception?.description)
                }

                result.result.value as? Boolean ?: false
            } ?: false
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseWheelDown(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {
        val m = mouse ?: throw IllegalWebDriverStateException("Mouse not available", driver = this)
        try {
            // Pace before the first wheel tick (consistent with click/hover/type)
            gap("mouseWheel")
            repeat(count) { i ->
                if (i > 0) {
                    // delayMillis: > 0 = explicit delay, == 0 = no delay, < 0 = use policy default
                    when {
                        delayMillis > 0 -> gap(delayMillis)
                        delayMillis < 0 -> gap("mouseWheel")
                    }
                }

                rpc.invokeOnPage("mouseWheelDown") {
                    m.wheel(deltaX, deltaY)
                }
            }
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "mouseWheelDown")
        } catch (e: ChromeIOException) {
            propagateIfOpen(e)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseWheelUp(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {
        val m = mouse ?: throw IllegalWebDriverStateException("Mouse not available", driver = this)
        try {
            // Pace before the first wheel tick (consistent with click/hover/type)
            gap("mouseWheel")
            repeat(count) { i ->
                if (i > 0) {
                    // delayMillis: > 0 = explicit delay, == 0 = no delay, < 0 = use policy default
                    when {
                        delayMillis > 0 -> gap(delayMillis)
                        delayMillis < 0 -> gap("mouseWheel")
                    }
                }

                rpc.invokeOnPage("mouseWheelUp") {
                    m.wheel(deltaX, deltaY)
                }
            }
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "mouseWheelUp")
        } catch (e: ChromeIOException) {
            propagateIfOpen(e)
        }
    }

    /**
     * Compute a timeout for mouse-wheel operations proportional to the scroll
     * distance.  The base 5 s covers the CDP round-trip; each additional
     * 1 000 px of combined delta adds 1 s, capped at 30 s.
     */
    private fun wheelTimeout(deltaX: Double, deltaY: Double): Long {
        val distance = max(abs(deltaX), abs(deltaY))
        val extra = (distance / 1000.0).roundToLong() * 1000L
        return maxOf(5_000L, minOf(30_000L, 5_000L + extra))
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseWheel(deltaX: Double, deltaY: Double) {
        val m = mouse ?: throw IllegalWebDriverStateException("Mouse not available", driver = this)
        rpc.invokeOnPage("mouseWheel") {
            // Primary: CDP mouse wheel — dispatches trusted wheel DOM events that
            // page listeners can observe (required for interactive fixtures).
            // Fallback: JS window.scrollBy() when CDP fails (bypasses the
            // Input.dispatchMouseEvent wheel race condition crbug.com/444929150).
            try {
                withTimeout(wheelTimeout(deltaX, deltaY)) {
                    m.wheel(deltaX, deltaY)
                }
            } catch (_: Exception) {
                js.evaluate("window.scrollBy($deltaX, $deltaY)")
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseWheel(selector: String, deltaX: Double, deltaY: Double) {
        try {
            rpc.invokeOnElement(selector, "mouseWheel", scrollIntoView = true) { node ->
                gap("mouseWheel")
                val point = emulator.getInteractPoint(node, "center", useRandomOffset = true)
                    ?: return@invokeOnElement
                // Primary: CDP mouse wheel — dispatches trusted wheel DOM events.
                // Fallback: JS element.scrollBy() when CDP fails (bypasses the
                // Input.dispatchMouseEvent wheel race condition crbug.com/444929150).
                try {
                    val m = mouse ?: return@invokeOnElement
                    withTimeout(wheelTimeout(deltaX, deltaY)) {
                        m.moveTo(point, steps = 1)
                        m.wheel(deltaX, deltaY)
                    }
                } catch (_: Exception) {
                    js.evaluate("document.querySelector('${selector.replace("'", "\\'")}').scrollBy($deltaX, $deltaY)")
                }
            }
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "mouseWheel")
        } catch (e: ChromeIOException) {
            propagateIfOpen(e)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseMove(x: Double, y: Double) {
        rpc.invokeOnPage("mouseMove") { mouse?.moveTo(x, y) }
    }

    /**
     * TODO: use BrowserProtocol to implement mouseDown: BrowserProtocol → Browser Input System → Hit Testing → DOM → JS Event
     * */
    @Throws(WebDriverException::class)
    override suspend fun mouseDown(button: String, clickCount: Int) {
        val btnIndex = when (button) {
            "right" -> 2
            "middle" -> 1
            else -> 0
        }
        val currentX = mouse?.currentX ?: 0.0
        val currentY = mouse?.currentY ?: 0.0
        val script = """
            (() => {
                const x = $currentX;
                const y = $currentY;
                const target = document.elementFromPoint(x, y);
                if (!target) return;
                target.dispatchEvent(new MouseEvent('mousedown', { button: $btnIndex, buttons: 1, bubbles: true, clientX: x, clientY: y, detail: $clickCount }));
            })()
        """.trimIndent()
        rpc.invokeOnPage("mouseDown") { evaluate(script) }
    }

    /**
     * TODO: use BrowserProtocol to implement mouseUp: BrowserProtocol → Browser Input System → Hit Testing → DOM → JS Event
     * */
    @Throws(WebDriverException::class)
    override suspend fun mouseUp(button: String, clickCount: Int) {
        val btnIndex = when (button) {
            "right" -> 2
            "middle" -> 1
            else -> 0
        }
        val currentX = mouse?.currentX ?: 0.0
        val currentY = mouse?.currentY ?: 0.0
        val script = """
            (() => {
                const x = $currentX;
                const y = $currentY;
                const target = document.elementFromPoint(x, y);
                if (!target) return;
                target.dispatchEvent(new MouseEvent('mouseup', { button: $btnIndex, bubbles: true, clientX: x, clientY: y, detail: $clickCount }));
            })()
        """.trimIndent()
        rpc.invokeOnPage("mouseUp") { evaluate(script) }
    }

    @Throws(WebDriverException::class)
    override suspend fun moveMouseTo(selector: String, deltaX: Int, deltaY: Int) {
        try {
            val node = rpc.invokeOnPage("scrollIntoViewIfNeeded") {
                page.scrollIntoViewIfNeeded(selector)
            } ?: return

            val offset = OffsetD(4.0, 4.0)
            if (!isActive) return

            rpc.invokeOnPage("moveMouseTo") {
                val point = ClickableDOM(browserProtocol, node, offset).clickablePoint().value
                if (point != null) {
                    val point2 = PointD(point.x + deltaX, point.y + deltaY)
                    mouse?.moveTo(point2)
                }
                gap()
            }
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "moveMouseTo")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun hover(selector: String) {
        bringToFront()
        rpc.invokeOnElement(selector, "hover", scrollIntoView = true) { node ->
            waitForScrollSettled(selector)
            emulator.hover(node, position = "center")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun click(selector: String, count: Int) {
        rpc.invokeOnElement(selector, "click", scrollIntoView = true) { node ->
            waitForScrollSettled(selector)
            val isWindows = org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS
            // On Windows, CDP Input.dispatchMouseEvent (mousePressed/mouseReleased)
            // does not reliably trigger DOM click events in headless Chrome.
            // Use a DOM click as the sole mechanism (skip CDP mouse events to
            // avoid double-firing).  On Linux/macOS the CDP path works reliably.
            if (isWindows) {
                emulator.click(
                    node, count, position = "center", modifier = null,
                    delayMillis = 0, dispatchCdpMouseEvents = false,
                )
                dispatchDomClick(node, count)
            } else {
                val delayMillis = randomDelayMillis("click")
                emulator.click(node, count, position = "center", modifier = null, delayMillis = delayMillis)
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun click(selector: String, modifier: String) {
        rpc.invokeOnElement(selector, "click", scrollIntoView = true) { node ->
            val delayMillis = randomDelayMillis("click")
            waitForScrollSettled(selector)
            emulator.click(node, 1, position = "center", modifier = modifier, delayMillis = delayMillis)
            // No DOM fallback for modifier clicks: the CDP modifier bitmask
            // (Ctrl/Shift/Alt/Meta) has no equivalent in HTMLElement.click()
            // or dispatchEvent, and adding a DOM fallback on top of CDP would
            // double-fire on Windows where both paths work.
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun selectOption(selector: String, values: List<String>): List<String> {
        val jsonValues = jsonMapper.writeValueAsString(values)

        val result = rpc.invokeOnElement(selector, "selectOption") { node ->
            withNodeObjectId(browserProtocol, node) { objectId ->
                val res = browserProtocol.callFunctionOn(
                    SELECT_OPTION_JS,
                    objectId = objectId,
                    arguments = listOf(CallArgument(value = jsonValues)),
                    returnByValue = true
                )

                if (res.exceptionDetails != null) {
                    throw WebDriverException("JS Error in selectOption: " + res.exceptionDetails?.exception?.description)
                }

                val resultValue = res.result.value

                if (resultValue is List<*>) {
                    resultValue.filterIsInstance<String>()
                } else {
                    listOf()
                }
            } ?: listOf()
        }

        return result ?: listOf()
    }

    override suspend fun dblclick(selector: String) {
        dblclick(selector, "")
    }

    /**
     * focus on an element with [selector] and dblclick it with [modifier] pressed
     * */
    @Throws(WebDriverException::class)
    override suspend fun dblclick(selector: String, modifier: String) {
        rpc.invokeOnElement(selector, "dblclick") {
            val node = page.focusOnSelector(selector) ?: return@invokeOnElement
            val isWindows = org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS
            val hasModifier = modifier.isNotBlank()
            if (isWindows && !hasModifier) {
                // Windows, no modifier: use DOM click as sole mechanism to
                // avoid double-firing from CDP mouse events + synthetic DOM.
                emulator.click(
                    node, 2, position = "center", modifier = null,
                    delayMillis = 0, dispatchCdpMouseEvents = false,
                )
                dispatchDomClick(node, 2)
            } else if (hasModifier) {
                // Any platform with a modifier (Shift/Ctrl/Alt/Meta): use CDP
                // path — the modifier bitmask has no equivalent in
                // HTMLElement.click() or MouseEvent dispatch.
                emulator.click(node, 2, position = "center", modifier = modifier)
            } else {
                // Non-Windows, no modifier: standard CDP double-click.
                emulator.click(node, 2)
            }
            gap("dblclick")
        }
    }

    /**
     * Dispatch DOM click(s) on the given element matching the W3C UI Events
     * standard event sequence.
     *
     * On Windows, CDP `Input.dispatchMouseEvent` does not reliably trigger
     * DOM click events in headless Chrome.  This method produces the full
     * event sequence as the sole click mechanism:
     *
     *   `pointerdown → mousedown → pointerup → mouseup → click`
     *
     * where `click` uses [HTMLElement.click] to trigger default actions
     * (navigation, form submission).  For count = 2 the sequence repeats
     * twice and ends with `dblclick`.
     *
     * [HTMLElement.click] per spec only fires `click` — it does not fire
     * `pointerdown`, `pointerup`, `mousedown`, `mouseup`, or `dblclick`,
     * so those are dispatched explicitly with correct property values
     * (`buttons` = 1 during press, 0 on release; `detail` = click count).
     * Coordinates are taken from the element's bounding rect center.
     *
     * @param node  The element to click.
     * @param count Number of consecutive clicks (1 = single, 2 = double).
     */
    private suspend fun dispatchDomClick(node: NodeRef, count: Int) {
        withNodeObjectId(browserProtocol, node) { objectId ->
            when (count) {
                1 -> {
                    browserProtocol.callFunctionOn(
                        """function() {
                             if (this instanceof HTMLElement) {
                               var r = this.getBoundingClientRect();
                               var cx = r.left + r.width / 2;
                               var cy = r.top + r.height / 2;
                               var detail = 1;
                               emitClick(this, cx, cy, detail);
                             }
                             function emitClick(el, cx, cy, d) {
                               var ptr = new PointerEvent('pointerdown', {bubbles:true,cancelable:true,view:window,pointerId:1,pointerType:'mouse',isPrimary:true,clientX:cx,clientY:cy,buttons:1,button:0,detail:d});
                               var md  = new MouseEvent('mousedown', {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,buttons:1,button:0,detail:d});
                               el.dispatchEvent(ptr);
                               el.dispatchEvent(md);
                               ptr = new PointerEvent('pointerup', {bubbles:true,cancelable:true,view:window,pointerId:1,pointerType:'mouse',isPrimary:true,clientX:cx,clientY:cy,buttons:0,button:0,detail:d});
                               var mu  = new MouseEvent('mouseup', {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,buttons:0,button:0,detail:d});
                               el.dispatchEvent(ptr);
                               el.dispatchEvent(mu);
                               el.click();
                             }
                           }""",
                        objectId = objectId,
                        returnByValue = false,
                        userGesture = true,
                    )
                }
                2 -> {
                    browserProtocol.callFunctionOn(
                        """function() {
                             if (this instanceof HTMLElement) {
                               var r = this.getBoundingClientRect();
                               var cx = r.left + r.width / 2;
                               var cy = r.top + r.height / 2;
                               emitClick(this, cx, cy, 1);
                               emitClick(this, cx, cy, 2);
                               this.dispatchEvent(new MouseEvent('dblclick', {bubbles:true,cancelable:true,view:window,detail:2}));
                             }
                             function emitClick(el, cx, cy, d) {
                               var ptr = new PointerEvent('pointerdown', {bubbles:true,cancelable:true,view:window,pointerId:1,pointerType:'mouse',isPrimary:true,clientX:cx,clientY:cy,buttons:1,button:0,detail:d});
                               var md  = new MouseEvent('mousedown', {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,buttons:1,button:0,detail:d});
                               el.dispatchEvent(ptr);
                               el.dispatchEvent(md);
                               ptr = new PointerEvent('pointerup', {bubbles:true,cancelable:true,view:window,pointerId:1,pointerType:'mouse',isPrimary:true,clientX:cx,clientY:cy,buttons:0,button:0,detail:d});
                               var mu  = new MouseEvent('mouseup', {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,buttons:0,button:0,detail:d});
                               el.dispatchEvent(ptr);
                               el.dispatchEvent(mu);
                               el.click();
                             }
                           }""",
                        objectId = objectId,
                        returnByValue = false,
                        userGesture = true,
                    )
                }
                else -> {
                    repeat(count) { i ->
                        browserProtocol.callFunctionOn(
                            """function(detail) {
                                 if (this instanceof HTMLElement) {
                                   var r = this.getBoundingClientRect();
                                   var cx = r.left + r.width / 2;
                                   var cy = r.top + r.height / 2;
                                   var ptr = new PointerEvent('pointerdown', {bubbles:true,cancelable:true,view:window,pointerId:1,pointerType:'mouse',isPrimary:true,clientX:cx,clientY:cy,buttons:1,button:0,detail:detail});
                                   var md  = new MouseEvent('mousedown', {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,buttons:1,button:0,detail:detail});
                                   this.dispatchEvent(ptr);
                                   this.dispatchEvent(md);
                                   ptr = new PointerEvent('pointerup', {bubbles:true,cancelable:true,view:window,pointerId:1,pointerType:'mouse',isPrimary:true,clientX:cx,clientY:cy,buttons:0,button:0,detail:detail});
                                   var mu  = new MouseEvent('mouseup', {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,buttons:0,button:0,detail:detail});
                                   this.dispatchEvent(ptr);
                                   this.dispatchEvent(mu);
                                   this.click();
                                 }
                               }""",
                            objectId = objectId,
                            returnByValue = false,
                            userGesture = true,
                            arguments = listOf(CallArgument(value = i + 1)),
                        )
                    }
                }
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun resize(width: Int, height: Int) {
        rpc.invokeOnPage("resize") {
            browserProtocol.setDeviceMetricsOverride(
                width = width, height = height, deviceScaleFactor = 0.0, mobile = false
            )
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun dialogAccept(promptText: String?) {
        rpc.invokeOnPage("dialogAccept") {
            browserProtocol.handleJavaScriptDialog(accept = true, promptText = promptText)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun dialogDismiss() {
        rpc.invokeOnPage("dialogDismiss") {
            browserProtocol.handleJavaScriptDialog(accept = false)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun focus(selector: String) {
        // we can return false if the element is not focusable
        rpc.invokeDeferredSilently("focus") { page.focusOnSelector(selector) }
    }

    @Throws(WebDriverException::class)
    override suspend fun type(text: String, selector: String?) {
        if (selector.isNullOrBlank()) {
            rpc.invokeOnPage("type") {
                keyboard?.type(text, randomDelayMillis("type"))
                gap("type")
            }
            return
        }

        rpc.invokeOnElement(selector, "type") {
            val node = page.focusOnSelector(selector) ?: return@invokeOnElement
            emulator.click(node, 1, position = "right")
            // Ensure the cursor is at the end of any existing text so that
            // typed characters append rather than prepend.
            try {
                js.evaluate(
                    "document.querySelector('${selector.replace("'", "\\'")}')" +
                    ".setSelectionRange(99999, 99999)"
                )
            } catch (_: Exception) {
                // Non-text elements don't support setSelectionRange — ignore.
            }
            keyboard?.type(text, randomDelayMillis("type"))
            gap("type")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun fill(selector: String, text: String) {
        // Match the pattern used by type(): resolve the element first, then
        // explicitly focus inside the lambda with an early-return guard.
        // Using focus=true on invokeOnElement causes focusOnSelector to run
        // before the retry wrapper, so a transient focus failure drops the
        // entire lambda — the fill silently does nothing.  With this pattern
        // a focus failure is retried by invokeWithRetry, making fills more
        // resilient in Docker headless Chrome where CDP focus can be flaky.
        rpc.invokeOnElement(selector, "fill") {
            val node = page.focusOnSelector(selector) ?: return@invokeOnElement

            clear(node)

            emulator.click(node, 1, "right")

            // Ensure cursor is at the end of any remaining text after clear
            try {
                js.evaluate(
                    "document.querySelector('${selector.replace("'", "\\'")}')" +
                    ".setSelectionRange(99999, 99999)"
                )
            } catch (_: Exception) {
                // Non-text elements don't support setSelectionRange — ignore.
            }

            // Inter-character delay for Input.insertText CDP calls.
            //
            // Keyboard.type() dispatches each character via Input.insertText,
            // which sends a CDP command to Chrome.  Chrome processes the command
            // synchronously (the CDP response confirms the text was delivered),
            // but the resulting DOM events (input, keypress) are dispatched
            // asynchronously on the page's event loop.  If the next insertText
            // arrives before the prior one's DOM events have fully propagated,
            // Chrome may coalesce or drop input events — the page never sees
            // the missing characters and syncState() is never called.
            //
            // This is especially acute in Docker headless Chrome where the
            // event loop runs under constrained CPU/Memory, so DOM event
            // processing is slower than on a developer workstation.
            //
            // A hardcoded 10ms was tried (commit d904be750) but proved
            // insufficient in CI; a single-digit-ms gap is not enough headroom
            // for Chrome's async event pipeline under load.
            //
            // Using randomDelayMillis("type") — the same 90-240ms bucket that
            // type() uses (InteractSettings.DEFAULT_DELAY_POLICY) — gives each
            // insertText → DOM-event cycle enough time to complete before the
            // next character arrives, matching type()'s proven reliability.
            keyboard?.type(text, randomDelayMillis("type"))

            gap("fill")
        }
    }

    @Throws(WebDriverException::class)
    private suspend fun getLiveValueOrEmpty(node: NodeRef): String {
        // value exists both as an HTML attribute and a JavaScript property, but the property represents the
        // current state, which may differ from the attribute.
        // | 类型        | 含义        | 是否随运行时变化                |
        //| --------- | --------- | ----------------------- |
        //| attribute | HTML 初始声明 | ❌ 不变（除非手动 setAttribute） |
        //| property  | DOM 当前状态  | ✅ 会变（用户交互 / JS 修改）      |

        return withNodeObjectId(browserProtocol, node) { objectId ->
            browserProtocol.callFunctionOn(
                "function() { return this && typeof this.value !== 'undefined' ? this.value : null; }",
                objectId = objectId,
                returnByValue = true
            ).result.value?.toString() ?: ""
        } ?: ""
    }

    @Throws(WebDriverException::class)
    private suspend fun clear(node: NodeRef) {
        // value exists both as an HTML attribute and a JavaScript property, but the property represents the
        // current state, which may differ from the attribute.
        // | 类型        | 含义        | 是否随运行时变化                |
        //| --------- | --------- | ----------------------- |
        //| attribute | HTML 初始声明 | ❌ 不变（除非手动 setAttribute） |
        //| property  | DOM 当前状态  | ✅ 会变（用户交互 / JS 修改）      |

        var liveValue = getLiveValueOrEmpty(node)
        var n = 3
        while (n-- > 0 && liveValue.isNotEmpty()) {
            // it's an input element, we should click on the right side of the element,
            // so the cursor appears at the tail of the text
            emulator.click(node, 1, "right")

            if (liveValue.length > 5) {
                // select all text and delete
                //press('Control+A'); // macOS 用 Meta+A, normalized in `keyboard?.press`
                //press('Delete');
                keyboard?.press("Control+A", randomDelayMillis("delete"))
                keyboard?.press("Delete", randomDelayMillis("delete"))
            } else {
                keyboard?.delete(liveValue.length, randomDelayMillis("delete"))
            }

            liveValue = getLiveValueOrEmpty(node)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun upload(selector: String, paths: List<String>) {
        rpc.invokeOnElement(selector, "upload", focus = true) { node ->
            browserProtocol.setFileInputFiles(files = paths, nodeId = node.nodeId)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun press(key: String, selector: String?) {
        if (selector.isNullOrBlank()) {
            rpc.invokeOnPage("press") {
                keyboard?.press(key, randomDelayMillis("press"))
                // CDP-dispatched Enter may not trigger implicit form submission (HTML spec §4.10.2.2).
                // Explicitly submit the nearest form as a safety net. See trySubmitFormOnEnter().
                if (key == "Enter") {
                    trySubmitFormOnEnter()
                }
                gap("press")
            }
            return
        }

        rpc.invokeOnElement(selector, "press") {
            val node = page.focusOnSelector(selector) ?: return@invokeOnElement
            emulator.click(node, 1, position = "right")
            // Ensure the cursor is at the end of any existing text so that the
            // pressed key appends rather than prepends.  CDP focus + click may
            // leave the cursor at position 0 on some platforms.
            try {
                js.evaluate(
                    "document.querySelector('${selector.replace("'", "\\'")}')" +
                    ".setSelectionRange(99999, 99999)"
                )
            } catch (_: Exception) {
                // Non-text elements (buttons, divs) don't support setSelectionRange.
                // Silently ignore — the press will still work for non-text targets.
            }
            keyboard?.press(key, randomDelayMillis("press"))
            // CDP-dispatched Enter may not trigger implicit form submission (HTML spec §4.10.2.2).
            // Explicitly submit the nearest form as a safety net. See trySubmitFormOnEnter().
            if (key == "Enter") {
                trySubmitFormOnEnter()
            }
            gap("press")
        }
    }

    /**
     * Triggers form submission when the active element is a form-control inside a `<form>`.
     *
     * **Why this exists:**
     *
     * CDP `Input.dispatchKeyEvent` (used by `keyboard?.press("Enter")`) sends trusted
     * `keydown` / `keypress` DOM events, but Chromium does not reliably fire the browser's
     * *implicit form submission* default action (HTML spec §4.10.2.2) for synthesized
     * input — even when the events are marked trusted. The result is that pressing Enter
     * on a search box inside a `<form>` dispatches the correct DOM events, yet the form
     * never submits and the page never navigates.
     *
     * This method is a safety net: after the CDP key events land, it checks whether the
     * active element is a form-control eligible for implicit submission, and if so
     * explicitly calls `form.requestSubmit()` (with fallback to `form.submit()`).
     *
     * **Elements excluded (Enter does *not* implicitly submit for these):**
     * - `<textarea>` — Enter inserts a newline
     * - `<input type="radio|checkbox|file|button|reset|submit|image|hidden">`
     * - Any element not inside a `<form>`
     */
    private suspend fun trySubmitFormOnEnter() {
        runCatching {
            browserProtocol.evaluate(
                expression = TRY_SUBMIT_FORM_ON_ENTER_JS,
                returnByValue = true,
            )
        }.onFailure {
            logger.debug("Safety-net form submission after Enter key failed: {}", it.brief())
        }
    }

    /**
     * Dispatches a `keydown` event via DOM API (JavaScript dispatchEvent).
     *
     * NOTE: CDP `Input.dispatchKeyEvent` (used by [keyboard?.down]) is unreliable for
     * keydown/keyup on some platforms — the events may not reach page listeners.
     * We use DOM event dispatch instead, which produces `isTrusted: false` events but
     * reliably triggers page-side handlers. Tracked as TODO: revisit once CDP key event
     * reliability is addressed upstream.
     */
    @Throws(WebDriverException::class)
    override suspend fun keyDown(key: String) {
        rpc.invokeOnPage("keyDown") {
            dispatchDomKeyboardEvent("keydown", key)
        }
    }

    /**
     * Dispatches a `keyup` event via DOM API (JavaScript dispatchEvent).
     * See [keyDown] for rationale on using DOM events instead of CDP.
     */
    @Throws(WebDriverException::class)
    override suspend fun keyUp(key: String) {
        rpc.invokeOnPage("keyUp") {
            dispatchDomKeyboardEvent("keyup", key)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun scrollTo(selector: String): Double {
        rpc.invokeDeferredSilently("scrollTo") { page.scrollIntoViewIfNeeded(selector) }
        return (evaluate("window.scrollY") as? Number)?.toDouble() ?: 0.0
    }

    @Throws(WebDriverException::class)
    override suspend fun dragAndDrop(selector: String, deltaX: Int, deltaY: Int) {
        try {
            val node = rpc.invokeOnPage("scrollIntoViewIfNeeded") {
                page.scrollIntoViewIfNeeded(selector)
            }

            if (node == null) {
                throw WebDriverException("Failed to scroll element into view: $selector", driver = this)
            }

            // Use randomized offset like in click() for better anti-detection
            val deltaOffsetX = 4.0 + Random.nextInt(4)
            val deltaOffsetY = 4.0 + Random.nextInt(4)  // Add randomization to Y offset
            val offset = OffsetD(deltaOffsetX, deltaOffsetY)

            if (!isActive) throw IllegalWebDriverStateException("BrowserProtocol is not active", driver = this)
            val m = mouse ?: throw IllegalWebDriverStateException("Mouse not available", driver = this)

            rpc.invokeOnPage("dragAndDrop") {
                val clickableDOM = ClickableDOM(browserProtocol, node, offset)
                val clickableResult = clickableDOM.clickablePoint()
                val startPoint = clickableResult.value

                if (startPoint == null) {
                    throw WebDriverException(
                        "Element is not clickable/draggable: $selector | ${clickableResult.message}", driver = this
                    )
                }

                // Calculate target point relative to start point
                val targetPoint = PointD(startPoint.x + deltaX, startPoint.y + deltaY)

                // Validate target point coordinates
                if (targetPoint.x < 0 || targetPoint.y < 0) {
                    throw WebDriverException(
                        "Target point has negative coordinates: $targetPoint (from: $startPoint, delta: $deltaX, $deltaY)",
                        driver = this
                    )
                }

                tracer?.trace("dragAndDrop | from: {} to: {} | delta: {}, {}", startPoint, targetPoint, deltaX, deltaY)

                // Use mouse to perform drag-and-drop via BrowserProtocol drag events
                m.dragAndDrop(startPoint, targetPoint, randomDelayMillis("dragAndDrop"))

                gap()
            }
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "dragAndDrop")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun outerHTML(selector: String): String? {
        return rpc.invokeOnElement(selector, "outerHTML") { node ->
            when {
                node.isNull() -> null
                // Use the JS serializer to inject vi attributes from the WeakMap
                // during serialization, without mutating the live DOM.
                // Falls back to native outerHTML when no vi data has been computed.
                else -> {
                    val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
                    val expression = "__pulsar_utils__.getAnnotatedOuterHTML('$escapedSelector')"
                    js.evaluateValue(expression) as? String
                }
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun ariaSnapshot(boxes: Boolean): String {
        return rpc.invokeDeferredSilently("ariaSnapshot") { page.ariaSnapshot() } ?: ""
    }

    @Throws(WebDriverException::class)
    override suspend fun ariaSnapshot(viewports: String, boxes: Boolean): String {
        val viewportIndices = ViewportSpec.parse(viewports) ?: return ariaSnapshot()
        return rpc.invokeDeferredSilently("ariaSnapshot") { page.ariaSnapshot(viewportIndices) } ?: ""
    }

    @Throws(WebDriverException::class)
    override suspend fun ariaSnapshot(options: AriaSnapshotOptions): String {
        return rpc.invokeDeferredSilently("ariaSnapshot") { page.ariaSnapshot(options) } ?: ""
    }

    @Throws(WebDriverException::class)
    override suspend fun querySelectorAll(selector: String): List<NodeRef> {
        return rpc.invokeOnPage("select") { page.dom.queryLocatorAll(selector) } ?: listOf()
    }

    @Throws(WebDriverException::class)
    override suspend fun selectFirstTextOrNull(selector: String): String? {
        return rpc.invokeOnElement(selector, "selectFirstTextOrNull") { node ->
            when {
                node.isNull() -> null
                else -> {
                    withNodeObjectId(browserProtocol, node) { objectId ->
                        val remoteObject = browserProtocol.callFunctionOn(
                            SELECT_FIRST_TEXT_JS, objectId = objectId, returnByValue = true
                        )
                        // TODO: performance issue for large text (memory copy)
                        remoteObject.result.value?.toString()
                    }
                }
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun selectTextAll(selector: String): List<String> {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val json = evaluate("__pulsar_utils__.selectTextAll('$safeSelector')")?.toString() ?: "[]"
        return jsonMapper.readValue(json)
    }

    override suspend fun selectAttributes(selector: String): Map<String, String> {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val json = evaluate("__pulsar_utils__.selectAttributes('$safeSelector')")?.toString() ?: return mapOf()
        val attributes: List<String> = jsonMapper.readValue(json)
        return attributes.zipWithNext().associate { it }
    }

    @Throws(WebDriverException::class)
    override suspend fun selectAttributeAll(selector: String, attrName: String, start: Int, limit: Int): List<String> {
        val end = start + limit
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedAttrName = jsonMapper.writeValueAsString(attrName)

        val expression = "__pulsar_utils__.selectAttributeAll('$safeSelector', $encodedAttrName, $start, $end)"
        val json = evaluate(expression)?.toString() ?: return listOf()
        return jsonMapper.readValue(json)
    }

    @Throws(WebDriverException::class)
    override suspend fun setAttribute(selector: String, attrName: String, attrValue: String) {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedName = jsonMapper.writeValueAsString(attrName)
        val encodedValue = jsonMapper.writeValueAsString(attrValue)
        evaluate("__pulsar_utils__.setAttribute('$safeSelector', $encodedName, $encodedValue)")
    }

    @Throws(WebDriverException::class)
    override suspend fun setAttributeAll(selector: String, attrName: String, attrValue: String) {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedName = jsonMapper.writeValueAsString(attrName)
        val encodedValue = jsonMapper.writeValueAsString(attrValue)
        evaluate("__pulsar_utils__.setAttributeAll('$safeSelector', $encodedName, $encodedValue)")
    }

    // --------------------------- Property helpers ---------------------------
    @Throws(WebDriverException::class)
    override suspend fun selectFirstPropertyValueOrNull(selector: String, propName: String): String? {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedPropName = jsonMapper.writeValueAsString(propName)
        return evaluateValue("__pulsar_utils__.selectFirstPropertyValue('$safeSelector', $encodedPropName)")?.toString()
    }

    @Throws(WebDriverException::class)
    override suspend fun selectPropertyValueAll(
        selector: String, propName: String, start: Int, limit: Int
    ): List<String> {
        val end = start + limit
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedPropName = jsonMapper.writeValueAsString(propName)
        val expression = "__pulsar_utils__.selectPropertyValueAll('$safeSelector', $encodedPropName, $start, $end)"
        val json = evaluate(expression)?.toString() ?: return listOf()
        return jsonMapper.readValue(json)
    }

    @Throws(WebDriverException::class)
    override suspend fun setProperty(selector: String, propName: String, propValue: String) {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedName = jsonMapper.writeValueAsString(propName)
        val encodedValue = jsonMapper.writeValueAsString(propValue)
        evaluate("__pulsar_utils__.setProperty('$safeSelector', $encodedName, $encodedValue)")
    }

    @Throws(WebDriverException::class)
    override suspend fun setPropertyAll(selector: String, propName: String, propValue: String) {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedName = jsonMapper.writeValueAsString(propName)
        val encodedValue = jsonMapper.writeValueAsString(propValue)
        evaluate("__pulsar_utils__.setPropertyAll('$safeSelector', $encodedName, $encodedValue)")
    }

    @Throws(WebDriverException::class)
    override suspend fun clickTextMatches(selector: String, pattern: String, count: Int) {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedPattern = jsonMapper.writeValueAsString(pattern)
        evaluate("__pulsar_utils__.clickTextMatches('$safeSelector', $encodedPattern)")
    }

    @Throws(WebDriverException::class)
    override suspend fun clickMatches(selector: String, attrName: String, pattern: String, count: Int) {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val encodedAttrName = jsonMapper.writeValueAsString(attrName)
        val encodedPattern = jsonMapper.writeValueAsString(pattern)
        evaluate("__pulsar_utils__.clickMatches('$safeSelector', $encodedAttrName, $encodedPattern)")
    }

    @Throws(WebDriverException::class)
    override suspend fun clickablePoint(selector: String): PointD? {
        try {
            return rpc.invokeOnPage("clickablePoint") {
                val node = page.scrollIntoViewIfNeeded(selector)
                ClickableDOM.create(browserProtocol, node)?.clickablePoint()?.value
            }
        } catch (e: ChromeDriverException) {
            lastError = e
            logger.warn("Failed to get clickablePoint for [{}] | {}", selector, e.message)
            rpc.interceptChromeException(e, "clickablePoint")
        }

        return null
    }

    @Throws(WebDriverException::class)
    override suspend fun boundingBox(selector: String): RectD? {
        try {
            return rpc.invokeOnPage("boundingBox") {
                val node = page.scrollIntoViewIfNeeded(selector)
                ClickableDOM.create(browserProtocol, node)?.boundingBox()
            }
        } catch (e: ChromeDriverException) {
            lastError = e
            logger.warn("Failed to get boundingBox for [{}] | {}", selector, e.message)
            rpc.interceptChromeException(e, "boundingBox")
        }

        return null
    }

    /**
     * This method scrolls element into view if needed, and then uses
     * {@link screenshot.screenshot} to take a screenshot of the element.
     * If the element is detached from DOM, the method throws an error.
     */
    @Throws(WebDriverException::class)
    override suspend fun screenshot(fullPage: Boolean): String? {
        return try {
            rpc.invokeOnPage("screenshot") {
                screenshot.screenshot(fullPage)
            }
        } catch (e: ChromeDriverException) {
            lastError = e
            logger.warn("Failed to take screenshot (fullPage=$fullPage) | {}", e.message)
            rpc.interceptChromeException(e, "screenshot")
            null
        }
    }

    /**
     * This method scrolls element into view if needed, and then uses
     * {@link page.screenshot} to take a screenshot of the element.
     * If the element is detached from DOM, the method throws an error.
     */
    @Throws(WebDriverException::class)
    override suspend fun screenshot(selector: String): String? {
        return try {
            page.scrollIntoViewIfNeeded(selector) ?: return null
            // Force the page stop all navigations and pending resource fetches.
            rpc.invokeOnPage("screenshot") { screenshot.screenshot(selector) }
        } catch (e: ChromeDriverException) {
            lastError = e
            logger.warn("Failed to take screenshot for [{}] | {}", selector, e.message)
            rpc.interceptChromeException(e, "screenshot")
            null
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun screenshot(rect: RectD): String? {
        return try {
            // Force the page stop all navigations and pending resource fetches.
            rpc.invokeOnPage("screenshot") { screenshot.screenshot(rect) }
        } catch (e: ChromeDriverException) {
            lastError = e
            logger.warn("Failed to take screenshot for rect {} | {}", rect, e.message)
            rpc.interceptChromeException(e, "screenshot")
            null
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun pdf(): String? {
        return try {
            rpc.invokeOnPage("pdf") {
                val result = browserProtocol.printToPDF(
                    printBackground = true,
                    transferMode = PrintToPDFTransferMode.RETURN_AS_BASE_64,
                )
                result.data
            }
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "pdf")
            null
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun pageSource(): String? {
        return rpc.invokeOnPage("pageSource") {
            // Use the JS serializer that injects vi attributes from a WeakMap
            // during serialization, without mutating the live DOM.
            // Falls back to outerHTML when no vi data has been computed
            // (e.g., content-length estimation before compute() is called).
            val expression = "__pulsar_utils__.getAnnotatedHTML()"
            js.evaluateValue(expression) as? String
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun nanoDOMTree(): NanoDOMTree? {
        return rpc.invokeOnPage("nanoDOMTree") {
            val snapshotOptions = SnapshotOptions()
            val domState = page.snapshot.getDOMState(snapshotOptions = snapshotOptions)
            domState.serializableTree.toNanoTreeInRange()
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun browserUseState(target: PageTarget, snapshotOptions: SnapshotOptions): BrowserUseState {
        return rpc.invokeOnPage("browserUseState") {
            page.snapshot.getBrowserUseState(target, snapshotOptions)
        }!!
    }

    override suspend fun bringToFront() {
        rpc.invokeDeferredSilently("bringToFront") {
            browserProtocol.bringToFront()
            browser.frontDriver = this
        }
    }

    override fun awaitTermination() {
        browserProtocol.awaitTermination()
    }

    override suspend fun loadResource(url: String): NetworkResourceResponse {
        val options = LoadNetworkResourceOptions(
            disableCache = false, includeCredentials = false
        )

        val response = rpc.invokeOnPage("loadNetworkResource") {
            val frameId = browserProtocol.getFrameTree().frame.id
            val resource = browserProtocol.loadNetworkResource(frameId, url, options)
            NetworkResourceResponse.from(resource)
        }

        return response ?: NetworkResourceResponse()
    }

    /**
     * Close the tab hold by this driver.
     * */
    override fun close() {
        browser.destroyDriver(this)
        closeMe()
    }

    fun closeMe() {
        super.close()

        if (closed.compareAndSet(false, true)) {
            runCatching { browserProtocol.close() }.onFailure { warnForClose(this, it) }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun pause() {
        rpc.invokeOnPage("pause") { browserProtocol.stopLoading() }
    }

    @Throws(WebDriverException::class)
    override suspend fun stop() {
        navigateEntry.stopped = true
        if (!isActive) {
            return
        }

        try {
            handleRedirect()

            if (browser.isGUI) {
                // in gui mode, just stop the loading, so we can diagnose
                browserProtocol.stopLoading()
            } else {
                // go to about:blank, so the browser stops the previous page and releases all resources
                navigate(ChromeImpl.ABOUT_BLANK_PAGE)
            }
        } catch (e: ChromeIOException) {
            if (!e.isOpen || !browserProtocol.isOpen) {
                // intentionally ignored: the chrome is closed
            }
        } catch (e: ChromeDriverException) {
            try {
                rpc.interceptChromeException(e, "terminate")
            } catch (e: Exception) {
                logger.error("[Unexpected]", e)
            }
        }
    }

    override fun toString() = "Driver#$id"

    @Throws(ChromeIOException::class)
    suspend fun enableAPIAgents() {
        rpc.invokeOnPage("enableAPIAgents") {
            enableAPIAgents0()
        }
    }

    @Throws(ChromeIOException::class)
    private suspend fun enableAPIAgents0() {
        try {
            browserProtocol.pageEnable()
            browserProtocol.domEnable()
            browserProtocol.runtimeEnable()
            browserProtocol.networkEnable()
            browserProtocol.cssEnable()

            if (resourceBlockProbability > 1e-6) {
                browserProtocol.fetchEnable()
            }

            val proxyUsername = browser.id.fingerprint.proxyEntry?.username
            if (!proxyUsername.isNullOrBlank()) {
                // allow all url patterns
                val patterns = listOf(RequestPattern())
                browserProtocol.fetchEnable(patterns, true)
            }
        } catch (e: Exception) {
            logger.warn("Failed to enable CDT agents", e)
            throw ChromeIOException("Failed to enable CDT agents", e)
        }
    }

    /**
     * Navigate to the page and inject scripts.
     * */
    private suspend fun navigateInvaded(entry: NavigateEntry) {
        val url = entry.userTypedUrl

        addScriptToEvaluateOnNewDocument()

        if (blockedURLs.isNotEmpty()) {
            // Blocks URLs from loading.
            browserProtocol.setBlockedURLs(blockedURLs)
        }

        networkManager.enable()

        networkManager.on1(NetworkEvents.RequestWillBeSent) { event: RequestWillBeSent ->
            rpc.invoke0("onRequestWillBeSent") { onRequestWillBeSent(entry, event) }
        }
        networkManager.on1(NetworkEvents.ResponseReceived) { event: ResponseReceived ->
            rpc.invoke0("onResponseReceived") { onResponseReceived(entry, event) }
        }
        browserProtocol.onFrameNavigated {
            rpc.invoke("onFrameNavigated") { onFrameNavigated(entry, it) }
        }
        browserProtocol.onDocumentOpened {
            rpc.invoke("onDocumentOpened") { entry.mainRequestCookies = getCookies0() }
        }
        browserProtocol.onWindowOpen {
            rpc.invoke("onWindowOpen") { onWindowOpen(it) }
        }

        val proxyEntry = browser.id.fingerprint.proxyEntry
        if (proxyEntry?.username != null) {
            credentials = Credentials(proxyEntry.username!!, proxyEntry.password)
            credentials?.let { networkManager.authenticate(it) }
        }

        if (URLUtils.isLocalFile(url)) {
            // serve local file, for example:
            // local file path:
            // C:\Users\pereg\AppData\Local\Temp\pulsar\test.txt
            // converted to:
            // http://localfile.org?path=QzpcVXNlcnNccGVyZWdcQXBwRGF0YVxMb2NhbFxUZW1wXHB1bHNhclx0ZXN0LnR4dA==
            //
            // DISCUSS: support URI format in the system, for example: file:///C:/Users/pereg/AppData/Local/Temp/pulsar/test.txt
            openLocalFile(url)
        } else {
            page.navigate(url, referrer = navigateEntry.pageReferrer)
        }
    }

    private suspend fun openLocalFile(url: String) {
        val path = URLUtils.localURLToPath(url)
        val uri = path.toUri()
        page.navigate(uri.toString())
    }

    private fun onWindowOpen(event: WindowOpen) {
        logger.debug("Window opened | {} | {}", event.url, outgoingPages.size)

        val driver = browser.runCatching { newDriver(event.url) }.onFailure { warnInterruptible(this, it) }.getOrNull()
        if (driver != null) {
            driver.opener = this
            this.outgoingPages.add(driver)
        }
    }

    private suspend fun onRequestWillBeSent(entry: NavigateEntry, event: RequestWillBeSent) {
        if (!entry.userTypedUrl.startsWith("http")) {
            // This can happen for the following cases:
            // 1. non-http resources, for example, ftp, ws, etc.
            // 2. chrome's internal page, for example, about:blank, chrome://settings/, chrome://settings/system, etc.
            return
        }

        if (!URLUtils.isStandard(entry.userTypedUrl)) {
            logger.warn("Invalid url to sent to the browser | {}", entry.userTypedUrl)
            return
        }

        tracer?.trace("onRequestWillBeSent | driver | requestId: {}", event.requestId)

        // Try to get the RequestWillBeSentExtraInfo which contains cookies
        val extraInfo = networkManager.getRequestWillBeSentExtraInfo(event.requestId)

        val chromeNavigateEntry = ChromeNavigateEntry(navigateEntry)
        chromeNavigateEntry.updateStateBeforeRequestSent(event, extraInfo)

        // simulate blocking logic
        val isMinor = chromeNavigateEntry.isMinorResource(event)
        if (isMinor && isBlocked(event.request.url)) {
            browserProtocol.failRequest(event.requestId, ErrorReason.ABORTED)
        }

        // handle user-defined events
    }

    private fun isBlocked(url: String): Boolean {
        if (url in blockedURLs) {
            return true
        }

        if (resourceBlockProbability > 1e-6) {
            // Pre-compile regex patterns once per call; the underlying list rarely changes.
            val regexes = cachedProbabilisticBlockedRegexes
            val patterns = if (regexes != null && regexes.size == probabilisticBlockedURLs.size) {
                regexes
            } else {
                probabilisticBlockedURLs.map { it.toRegex() }.also {
                    cachedProbabilisticBlockedRegexes = it
                }
            }
            if (patterns.any { url.matches(it) }) {
                return Random.nextInt(100) / 100.0f < resourceBlockProbability
            }
        }

        return false
    }

    private suspend fun onResponseReceived(entry: NavigateEntry, event: ResponseReceived) {
        val chromeNavigateEntry = ChromeNavigateEntry(entry)

        tracer?.trace("onResponseReceived | driver | {}", event.requestId)

        chromeNavigateEntry.updateStateAfterResponseReceived(event)

        if (logger.isDebugEnabled) {
            reportInterestingResources(entry, event)
        }

        // handle user-defined events
    }

    private suspend fun onFrameNavigated(entry: NavigateEntry, event: FrameNavigated) {
        try {
            rpc.invoke("onFrameNavigated") { onFrameNavigated0(entry, event) }
        } catch (e: ChromeDriverException) {
            rpc.interceptChromeException(e, "terminate")
        }
    }

    private suspend fun onFrameNavigated0(entry: NavigateEntry, event: FrameNavigated) {
        val chromeNavigateEntry = ChromeNavigateEntry(entry)

        chromeNavigateEntry.updateStateAfterFrameNavigated(event)

        // Only recover isolated world on main-frame navigation.
        // Subframes can navigate/detach frequently; clearing/reinjecting on each one is racy and may use stale frame ids.
        val isMainFrame = event.frame.parentId == null
        if (!isMainFrame) {
            return
        }

        // Clear isolated world contexts on top-level navigation
        isolatedWorldManager.clearContexts()

        // Recreate isolated world and reinject runtime for the main frame
        try {
            val isolatedWorldJs = settings.dualWorldScriptLoader.getIsolatedWorldJs(false)
            if (isolatedWorldJs.isNotBlank()) {
                check(browserProtocol.isOpen) { "Underlying browser (BrowserProtocol) is closed" }
                val targetFrameId = browserProtocol.getFrameTree().frame.id
                val contextId = isolatedWorldManager.ensureRuntime(targetFrameId, isolatedWorldJs)
                logger.debug(
                    "Ensured Browser4 runtime in isolated world after main-frame navigation | frame={}", targetFrameId
                )
            } else {
                logger.warn("No isolated world JS found to re-inject after frame navigation")
            }
        } catch (e: Exception) {
            if (quickCheckHealthy().isOK) {
                logger.warn("Failed to re-inject Browser4 runtime after frame navigation", e)
            } else {
                logger.debug("Underlying browser (BrowserProtocol) is closed")
            }
        }
    }

    private suspend fun reportInterestingResources(entry: NavigateEntry, event: ResponseReceived) {
        runCatching { traceInterestingResources0(entry, event) }.onFailure { warnInterruptible(this, it) }
    }

    private suspend fun traceInterestingResources0(entry: NavigateEntry, event: ResponseReceived) {
        val mimeType = event.response.mimeType
        val mimeTypes = listOf("application/json")
        if (mimeType !in mimeTypes) {
            return
        }

        val resourceTypes = listOf(
            ResourceType.FETCH,
            ResourceType.XHR,
            ResourceType.SCRIPT,
        )
        if (event.type !in resourceTypes) {
            // intentionally keep non-return for now (was used as filter in the past)
        }

        // page url is normalized
        val pageUrl = entry.pageUrl
        val resourceUrl = event.response.url
        val host = URLUtils.getHostNameOrNull(pageUrl) ?: "unknown"
        val reportDir = messageWriter.baseDir.resolve("trace").resolve(host)

        if (!Files.exists(reportDir)) {
            withContext(Dispatchers.IO) {
                Files.createDirectories(reportDir)
            }
        }

        val count = withContext(Dispatchers.IO) {
            Files.list(reportDir)
        }.count()
        if (count > 2_000) {
            // TOO MANY tracing
            return
        }

        var suffix = "-" + event.type.name.lowercase() + "-urls.txt"
        var filename = AppPaths.md5Hex(pageUrl) + suffix
        var path = reportDir.resolve(filename)

        val message = String.format("%s\t%s", mimeType, event.response.url)
        messageWriter.writeTo(message, path)

        // configurable
        val saveResourceBody =
            mimeType == "application/json" && event.response.encodedDataLength < 1_000_000 && alwaysFalse()
        if (saveResourceBody) {
            val body = rpc.invokeSilently("getResponseBody") {
                browserProtocol.fetchEnable()
                browserProtocol.getResponseBody(event.requestId).body
            }
            if (!body.isNullOrBlank()) {
                suffix = "-" + event.type.name.lowercase() + "-body.txt"
                filename = AppPaths.fromUri(resourceUrl, suffix = suffix)
                path = reportDir.resolve(filename)
                messageWriter.writeTo(body, path)
            }
        }
    }

    private suspend fun handleRedirect() {
        val finalUrl = currentUrl()
        // redirect
        if (finalUrl.isNotBlank() && finalUrl != navigateUrl) {
            // Update navigateUrl so that currentUrl() can fall back to the
            // correct final URL after a redirect chain, even if document.URL
            // returns blank in edge cases (tab state race, interstitial pages).
            // Without this, htmlsnapshot and other capture-based commands fail
            // with "Nil url is not allowed" after complex redirects.
            navigateUrl = finalUrl
        }
    }

    private suspend fun addScriptToEvaluateOnNewDocument() {
        // Use dual-world script loader (always available in BrowserSettings)
        addDualWorldScripts()
    }

    /**
     * Injects scripts using the dual-world architecture.
     * Page World: stealth patches only
     * Isolated World: full Browser4 runtime
     */
    private suspend fun addDualWorldScripts() {
        val loader = settings.dualWorldScriptLoader

        // 1. Inject Page World scripts (stealth patches)
        val pageWorldJs = loader.getPageWorldJs(false)
        if (pageWorldJs.isNotBlank()) {
            browserProtocol.addScriptToEvaluateOnNewDocument("\n;;\n$pageWorldJs\n;;\n")
            logger.debug("Injected Page World scripts (stealth patches)")
        }

        // 2. Create isolated world and inject runtime
        try {
            // Create isolated world for the main frame
            val contextId = isolatedWorldManager.createIsolatedWorld()

            // Inject Browser4 runtime into isolated world
            val isolatedWorldJs = loader.getIsolatedWorldJs(false)
            if (isolatedWorldJs.isNotBlank()) {
                isolatedWorldManager.injectRuntime(isolatedWorldJs, contextId)
                logger.debug(
                    "Injected Browser4 runtime into Isolated World (context: {}) | {}",
                    contextId,
                    StringUtils.abbreviateMiddle(navigateUrl, "...", 200)
                )
                val evaluate = browserProtocol.evaluate("typeof(__pulsar_utils__)", contextId = contextId)
                if (evaluate.result.value != "function") {
                    logger.warn(
                        "Failed to verify isolated world injection: typeof(__pulsar_utils__) should be 'function' but got: {}",
                        evaluate.result.value
                    )
                }
            }

            if (logger.isTraceEnabled) {
                reportDualWorldJs(pageWorldJs, isolatedWorldJs)
            }
        } catch (e: Throwable) {
            logger.warn("Failed to inject scripts into isolated world, falling back to page world", e)
        }
    }

    private fun reportDualWorldJs(pageWorldJs: String, isolatedWorldJs: String) {
        val dir = AppPaths.REPORT_DIR.resolve("browser/js/injected")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("page-world-injected.js"), pageWorldJs)
        Files.writeString(dir.resolve("isolated-world-injected.js"), isolatedWorldJs)
        logger.trace("Dual-world injection report: file://{}", dir)
    }

    @Throws(WebDriverException::class)
    private suspend fun getCookies0(): List<Map<String, String>> {
        val cookies = browserProtocol.getCookies().map { serialize(it) }
        return cookies
    }

    private suspend fun captureCurrentOriginLocalStorage(): Map<String, Any>? {
        val result = evaluateValue(
            """
            (() => {
              try {
                const origin = window.location.origin;
                if (!origin || origin === "null") {
                  return { origin: null, localStorage: [] };
                }
                const localStorageEntries = [];
                for (let index = 0; index < window.localStorage.length; index += 1) {
                  const name = window.localStorage.key(index);
                  if (name == null) continue;
                  localStorageEntries.push({
                    name,
                    value: window.localStorage.getItem(name) ?? ""
                  });
                }
                return { origin, localStorage: localStorageEntries };
              } catch (error) {
                return { origin: null, localStorage: [], error: String(error) };
              }
            })()
            """.trimIndent()
        ) as? Map<*, *> ?: return null

        val error = result["error"]?.toString()?.trim().orEmpty()
        require(error.isEmpty()) { "Failed to capture localStorage: $error" }

        val origin = result["origin"]?.toString()?.trim().orEmpty()
        if (origin.isEmpty() || origin == "null") {
            return null
        }

        val localStorageEntries = (result["localStorage"] as? List<*>).orEmpty().map { entry ->
            val item = entry as? Map<*, *> ?: throw IllegalArgumentException("localStorage entry must be an object")
            val name = item["name"]?.toString()?.trim().orEmpty()
            require(name.isNotEmpty()) { "localStorage entry name must not be blank" }
            mapOf(
                "name" to name,
                "value" to (item["value"]?.toString() ?: ""),
            )
        }

        return mapOf(
            "origin" to origin,
            "localStorage" to localStorageEntries,
        )
    }

    private suspend fun restoreLocalStorage(
        localStorage: List<StorageStateEntryPayload>,
        mapper: ObjectMapper,
    ) {
        val normalizedEntries = localStorage.map { entry ->
            val name = entry.name.trim()
            require(name.isNotEmpty()) { "localStorage entry name must not be blank" }
            mapOf(
                "name" to name,
                "value" to entry.value,
            )
        }
        val entriesJson = mapper.writeValueAsString(normalizedEntries)
        val restoredCount = evaluateValue(
            """
            (() => {
              const entries = $entriesJson;
              window.localStorage.clear();
              for (const entry of entries) {
                window.localStorage.setItem(entry.name, entry.value ?? "");
              }
              return entries.length;
            })()
            """.trimIndent()
        )
        val restored = (restoredCount as? Number)?.toInt()
        require(restored == normalizedEntries.size) {
            "Expected to restore ${normalizedEntries.size} localStorage entries but restored ${restored ?: "none"}"
        }
    }

    private fun serialize(cookie: Cookie): Map<String, String> {
        val mapper = nonNullJsonMapper
        return mapper.readValue(mapper.writeValueAsString(cookie))
    }

    private fun toStorageStateCookie(cookie: Map<String, String>): Map<String, Any> {
        val name = cookie["name"]?.trim().orEmpty()
        require(name.isNotEmpty()) { "Cookie name must not be blank" }

        val normalized = linkedMapOf<String, Any>(
            "name" to name,
            "value" to (cookie["value"] ?: ""),
        )

        cookie["domain"]?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["domain"] = it }
        cookie["path"]?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["path"] = it }
        cookie["expires"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { normalized["expires"] = it }
        cookie["httpOnly"]?.toBooleanStrictOrNull()?.let { normalized["httpOnly"] = it }
        cookie["secure"]?.toBooleanStrictOrNull()?.let { normalized["secure"] = it }
        cookie["sameSite"]?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["sameSite"] = it }
        return normalized
    }

    private fun normalizeCookieForSet(cookie: Map<String, Any?>): Map<String, Any?> {
        val name = cookie["name"]?.toString()?.trim().orEmpty()
        require(name.isNotEmpty()) { "Storage state cookie name must not be blank" }

        val normalized = linkedMapOf<String, Any?>(
            "name" to name,
            "value" to (cookie["value"]?.toString() ?: ""),
        )

        cookie["url"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["url"] = it }
        cookie["domain"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["domain"] = it }
        cookie["path"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["path"] = it }
        cookie["expires"]?.toString()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { normalized["expires"] = it }
        cookie["httpOnly"]?.toString()?.toBooleanStrictOrNull()?.let { normalized["httpOnly"] = it }
        cookie["secure"]?.toString()?.toBooleanStrictOrNull()?.let { normalized["secure"] = it }
        cookie["sameSite"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["sameSite"] = it }

        require("url" in normalized || "domain" in normalized) {
            "Storage state cookie '$name' must include either url or domain"
        }
        return normalized
    }

    private suspend fun cdpDeleteCookies(
        name: String, url: String? = null, domain: String? = null, path: String? = null
    ) {
        browserProtocol.deleteCookies(name, url, domain, path)
    }

    private suspend fun waitForScrollSettled(selector: String, timeout: Duration = Duration.ofMillis(5_000)) {
        val safeSelector = page.dom.normalizeSelector(selector, true) ?: selector
        val stateKey = "__ps_scroll_${Random.nextLong(Long.MAX_VALUE).toString(16)}"
        val expression = """
(() => {
  const sel = "$safeSelector";
  const key = "$stateKey";
  const el = document.querySelector(sel);
  if (!el) return true;
  const r = el.getBoundingClientRect();
  const s = document.scrollingElement || document.documentElement;
  const map = window[key] || (window[key] = new WeakMap());
  const curr = {t:r.top,l:r.left,st:s.scrollTop,sl:s.scrollLeft};
  const isFirst = !map.has(el);
  const prev = map.get(el) || curr;
  map.set(el, curr);
  return !isFirst &&
         Math.abs(prev.t - r.top) < 1 &&
         Math.abs(prev.l - r.left) < 1 &&
         Math.abs(prev.st - s.scrollTop) < 1 &&
         Math.abs(prev.sl - s.scrollLeft) < 1;
})()
"""

        try {
            waitUntil(200, timeout) {
                val settled = evaluateDetail(expression)
                settled?.value as? Boolean ?: false
            }
        } finally {
            runCatching {
                evaluateDetail(
                    """
(() => {
  const sel = "$safeSelector";
  const key = "$stateKey";
  const el = document.querySelector(sel);
  if (el && window[key]) {
    window[key].delete(el);
  }
  delete window[key];
  return true;
})()
                    """
                )
            }
        }
    }

    private suspend fun dispatchDomKeyboardEvent(type: String, key: String) {
        val safeKey = jsonMapper.writeValueAsString(key)
        evaluate(
            """
                (() => {
                  const target = document.activeElement || document.body || document.documentElement;
                  if (!target) return false;
                  const event = new KeyboardEvent('$type', {
                    key: $safeKey,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                  });
                  target.dispatchEvent(event);
                  return true;
                })()
            """.trimIndent()
        )
    }
}
