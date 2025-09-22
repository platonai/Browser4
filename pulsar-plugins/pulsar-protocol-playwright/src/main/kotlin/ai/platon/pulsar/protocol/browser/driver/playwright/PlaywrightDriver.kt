package ai.platon.pulsar.protocol.browser.driver.playwright

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.driver.chrome.NetworkResourceResponse
import ai.platon.pulsar.browser.driver.chrome.impl.ChromeImpl
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.math.geometric.PointD
import ai.platon.pulsar.common.math.geometric.RectD
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.skeleton.crawl.fetch.driver.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kklisura.cdt.protocol.v2023.types.runtime.Evaluate
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import org.jsoup.Connection
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

data class Credentials(
    val username: String,
    val password: String?
)

/**
 * A Playwright-based implementation of the WebDriver interface.
 * This driver provides browser automation capabilities using the Playwright library.
 *
 * @property browser The PlaywrightBrowser instance
 * @property page The Playwright Page instance
 * @property settings Browser configuration settings
 */
class PlaywrightDriver(
    uniqueID: String,
    override val browser: PlaywrightBrowser,
    private val page: Page,
) : AbstractWebDriver(uniqueID, browser) {

    private val logger = getLogger(this)
    private val rpc = RobustRPC(this)

    override val browserType: BrowserType = BrowserType.PLAYWRIGHT_CHROME

    val implementation get() = page

    private var credentials: Credentials? = null

    private var navigateUrl = ""

    // Frame management state -----------------------------------------------
    private val frameIdSeq = AtomicInteger(1)
    private val frameIds = Collections.synchronizedMap(IdentityHashMap<com.microsoft.playwright.Frame, String>())
    private var currentFrame: com.microsoft.playwright.Frame? = null

    private fun ensureFrameId(frame: com.microsoft.playwright.Frame): String {
        return frameIds.getOrPut(frame) { "frame_${frameIdSeq.getAndIncrement()}" }
    }

    private fun resolveFrameById(frameId: String): com.microsoft.playwright.Frame? {
        return page.frames().firstOrNull { ensureFrameId(it) == frameId }
    }

    // ----------------------------------------------------------------------

    override suspend fun addBlockedURLs(urlPatterns: List<String>) {
        try {
            rpc.invokeDeferred("addBlockedURLs") {
                page.route(urlPatterns.joinToString("|")) { route -> route.abort() }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "addBlockedURLs")
        }
    }

    override suspend fun addProbabilityBlockedURLs(urlPatterns: List<String>) {
        // Implement probability-based blocking logic here
    }

    override suspend fun setTimeouts(browserSettings: BrowserSettings) {
        // Implement timeout settings logic here
    }

    /**
     * Navigates to a URL without waiting for navigation to complete.
     * @throws RuntimeException if navigation fails
     */
    override suspend fun navigateTo(entry: NavigateEntry) {
        navigateHistory.add(entry)
        this.navigateEntry = entry

        browser.emit(BrowserEvents.willNavigate, entry)

        try {
            rpc.invokeDeferred("navigateTo") {
                doNavigateTo(entry)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "navigateTo", entry.url)
        }
    }

    /**
     * Navigate to the page and inject scripts.
     * */
    private fun doNavigateTo(entry: NavigateEntry) {
        val url = entry.url

        addScriptToEvaluateOnNewDocument()

        if (blockedURLs.isNotEmpty()) {
            // Blocks URLs from loading.
            // TODO: networkAPI?.setBlockedURLs(blockedURLs)
        }

        // TODO: add events
//        networkManager.on(NetworkEvents.RequestWillBeSent) { event: RequestWillBeSent ->
//            onRequestWillBeSent(entry, event)
//        }
//        networkManager.on(NetworkEvents.ResponseReceived) { event: ResponseReceived ->
//            onResponseReceived(entry, event)
//        }

        page.onResponse {
            // entry.mainRequestCookies = it.request()
        }

        // pageAPI?.onDocumentOpened { entry.mainRequestCookies = getCookies0() }
        // TODO: not working
        // pageAPI?.onWindowOpen { onWindowOpen(it) }
        // pageAPI?.onFrameAttached {  }
//        pageAPI?.onDomContentEventFired {  }

        val proxyEntry = browser.id.fingerprint.proxyEntry
        if (proxyEntry?.username != null) {
            credentials = Credentials(proxyEntry.username!!, proxyEntry.password)

            // credentials?.let { networkManager.authenticate(it) }
        }

        navigateUrl = url
        if (URLUtils.isLocalFile(url)) {
            // serve local file, for example:
            // local file path:
            // C:\Users\pereg\AppData\Local\Temp\pulsar\test.txt
            // converted to:
            // http://localfile.org?path=QzpcVXNlcnNccGVyZWdcQXBwRGF0YVxMb2NhbFxUZW1wXHB1bHNhclx0ZXN0LnR4dA==
            openLocalFile(url)
        } else {
            val options = Page.NavigateOptions()
                .setReferer(navigateEntry.pageReferrer)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            check(page, url)
            page.navigate(url, options)
        }
    }

    /**
     *
     * */
    @Throws(WebDriverException::class)
    private fun addScriptToEvaluateOnNewDocument() {
        rpc.invoke("addScriptToEvaluateOnNewDocument") {
            val js = settings.scriptLoader.getPreloadJs(false)
            if (js !in initScriptCache) {
                initScriptCache.add(0, js)
            }

            if (initScriptCache.isEmpty()) {
                logger.warn("No initScriptCache found")
                return@invoke
            }

            val scripts = initScriptCache.joinToString("\n;\n\n\n;\n")
            page.addInitScript("\n;;\n$scripts\n;;\n")

            if (logger.isTraceEnabled) {
                reportInjectedJs(scripts)
            }

            initScriptCache.clear()
        }
    }

    private fun openLocalFile(url: String) {
        try {
            rpc.invoke("openLocalFile") {
                val path = URLUtils.localURLToPath(url)
                val uri = path.toUri()
                page.navigate(uri.toString())
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "openLocalFile", url)
        }
    }

    override suspend fun currentUrl(): String {
        return try {
            rpc.invokeDeferred("currentUrl") {
                page.url()
            } ?: ""
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "currentUrl")
            ""
        }
    }

    override suspend fun url(): String {
        return try {
            rpc.invokeDeferred("url") {
                page.evaluate("document.URL") as String
            } ?: ""
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "url")
            ""
        }
    }

    override suspend fun documentURI(): String {
        return try {
            rpc.invokeDeferred("documentURI") {
                page.evaluate("document.documentURI") as String
            } ?: ""
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "documentURI")
            ""
        }
    }

    override suspend fun baseURI(): String {
        return try {
            rpc.invokeDeferred("baseURI") {
                page.evaluate("document.baseURI") as String
            } ?: ""
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "baseURI")
            ""
        }
    }

    override suspend fun referrer(): String {
        return try {
            rpc.invokeDeferred("referrer") {
                page.evaluate("document.referrer") as String
            } ?: ""
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "referrer")
            ""
        }
    }

    override suspend fun pageSource(): String? {
        return try {
            rpc.invokeDeferred("pageSource") {
                page.content()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "pageSource")
            null
        }
    }

    override suspend fun getCookies(): List<Map<String, String>> {
        return try {
            rpc.invokeDeferred("getCookies") {
                val cookies = page.context().cookies()
                val mapper = jacksonObjectMapper()
                cookies.map { cookie ->
                    val json = mapper.writeValueAsString(cookie)
                    val map: Map<String, String?> = mapper.readValue(json)
                    map.filterValues { it != null }.mapValues { it.toString() }
                }
            } ?: listOf()
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "getCookies")
            listOf()
        }
    }

    override suspend fun deleteCookies(name: String) {
        try {
            rpc.invokeDeferred("deleteCookies") {
                page.context().clearCookies()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "deleteCookies", name)
        }
    }

    override suspend fun deleteCookies(name: String, url: String?, domain: String?, path: String?) {
        try {
            rpc.invokeDeferred("deleteCookies") {
                page.context().clearCookies()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "deleteCookies", "name: $name, url: $url, domain: $domain, path: $path")
        }
    }

    override suspend fun clearBrowserCookies() {
        try {
            rpc.invokeDeferred("clearBrowserCookies") {
                page.context().clearCookies()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "clearBrowserCookies")
        }
    }

    override suspend fun waitForSelector(selector: String): Duration {
        try {
            rpc.invokeDeferred("waitForSelector") {
                page.waitForSelector(selector)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "waitForSelector", selector)
        }
        return Duration.ZERO
    }

    override suspend fun waitForSelector(selector: String, timeout: Duration, action: suspend () -> Unit): Duration {
        try {
            rpc.invokeDeferred("waitForSelector") {
                page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(timeout.toMillis().toDouble()))
                action()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "waitForSelector", "selector: $selector, timeout: $timeout")
        }
        return timeout
    }

    override suspend fun waitForPage(url: String, timeout: Duration): WebDriver? {
        try {
            rpc.invokeDeferred("waitForPage") {
                page.waitForURL(url, Page.WaitForURLOptions().setTimeout(timeout.toMillis().toDouble()))
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "waitForPage", "url: $url, timeout: $timeout")
        }
        return this
    }

    /**
     * Checks if an element exists in the DOM.
     * @param selector The CSS selector to check
     * @return true if the element exists, false otherwise
     */
    override suspend fun exists(selector: String): Boolean {
        return try {
            rpc.invokeDeferred("exists") {
                try {
                    page.querySelector(selector) != null
                } catch (e: Exception) {
                    false
                }
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "exists", selector)
            false
        }
    }

    override suspend fun isHidden(selector: String): Boolean {
        return try {
            rpc.invokeDeferred("isHidden") {
                !page.querySelector(selector).isVisible
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "isHidden", selector)
            false
        }
    }

    /**
     * Checks if an element is visible on the page.
     * @param selector The CSS selector to check
     * @return true if the element is visible, false otherwise
     */
    override suspend fun isVisible(selector: String): Boolean {
        return try {
            rpc.invokeDeferred("isVisible") {
                try {
                    page.querySelector(selector).isVisible
                } catch (e: Exception) {
                    false
                }
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "isVisible", selector)
            false
        }
    }

    override suspend fun isChecked(selector: String): Boolean {
        return try {
            rpc.invokeDeferred("isChecked") {
                page.querySelector(selector).isChecked
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "isChecked", selector)
            false
        }
    }

    override suspend fun bringToFront() {
        try {
            rpc.invokeDeferred("bringToFront") {
                page.bringToFront()
            }
        } catch (e: Exception) {
            logger.warn("Failed to bring to front: ${e.message}")
        }
    }

    override suspend fun focus(selector: String) {
        try {
            rpc.invokeDeferred("focus") {
                page.querySelector(selector).focus()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "focus", selector)
        }
    }

    /**
     * Types text into an element.
     * @param selector The CSS selector of the element
     * @param text The text to type
     * @throws RuntimeException if typing fails
     */
    override suspend fun type(selector: String, text: String) {
        try {
            rpc.invokeDeferred("type") {
                page.querySelector(selector).type(text)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "type", "selector: $selector, text: $text")
        }
    }

    /**
     * Fills an input element with text.
     * @param selector The CSS selector of the element
     * @param text The text to fill
     * @throws RuntimeException if filling fails
     */
    override suspend fun fill(selector: String, text: String) {
        try {
            rpc.invokeDeferred("fill") {
                page.querySelector(selector).fill(text)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "fill", "selector: $selector, text: $text")
        }
    }

    override suspend fun press(selector: String, key: String) {
        try {
            rpc.invokeDeferred("press") {
                page.querySelector(selector).press(key)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "press", "selector: $selector, key: $key")
        }
    }

    /**
     * Clicks on an element matching the selector.
     * @param selector The CSS selector of the element to click
     * @param count The number of times to click
     * @throws RuntimeException if clicking fails
     */
    override suspend fun click(selector: String, count: Int) {
        try {
            rpc.invokeDeferred("click") {
                repeat(count) {
                    page.querySelector(selector).click()
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "click", "selector: $selector, count: $count")
        }
    }

    override suspend fun clickTextMatches(selector: String, pattern: String, count: Int) {
        try {
            rpc.invokeDeferred("clickTextMatches") {
                val elements = page.querySelectorAll(selector)
                elements.forEach { element ->
                    if (element.textContent().contains(pattern)) {
                        repeat(count) {
                            element.click()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "clickTextMatches", "selector: $selector, pattern: $pattern, count: $count")
        }
    }

    override suspend fun clickMatches(selector: String, attrName: String, pattern: String, count: Int) {
        try {
            rpc.invokeDeferred("clickMatches") {
                val elements = page.querySelectorAll(selector)
                elements.forEach { element ->
                    if (element.getAttribute(attrName)?.contains(pattern) == true) {
                        repeat(count) {
                            element.click()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(
                e,
                "clickMatches",
                "selector: $selector, attrName: $attrName, pattern: $pattern, count: $count"
            )
        }
    }

    override suspend fun clickNthAnchor(n: Int, rootSelector: String): String? {
        return try {
            rpc.invokeDeferred("clickNthAnchor") {
                val anchors = page.querySelectorAll("$rootSelector a")
                if (n < anchors.size) {
                    anchors[n].click()
                    anchors[n].getAttribute("href")
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "clickNthAnchor", "n: $n, rootSelector: $rootSelector")
            null
        }
    }

    override suspend fun check(selector: String) {
        try {
            rpc.invokeDeferred("check") {
                page.querySelector(selector).check()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "check", selector)
        }
    }

    override suspend fun uncheck(selector: String) {
        try {
            rpc.invokeDeferred("uncheck") {
                page.querySelector(selector).uncheck()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "uncheck", selector)
        }
    }

    override suspend fun scrollTo(selector: String) {
        try {
            rpc.invokeDeferred("scrollTo") {
                page.querySelector(selector).scrollIntoViewIfNeeded()
            }
        } catch (e: Exception) {
            logger.warn("Failed to scroll to element: ${e.message}")
        }
    }

    override suspend fun scrollDown(count: Int) {
        try {
            rpc.invokeDeferred("scrollDown") {
                repeat(count) {
                    page.evaluate("window.scrollBy(0, window.innerHeight)")
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "scrollDown", "count: $count")
        }
    }

    override suspend fun scrollUp(count: Int) {
        try {
            rpc.invokeDeferred("scrollUp") {
                repeat(count) {
                    page.evaluate("window.scrollBy(0, -window.innerHeight)")
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "scrollUp", "count: $count")
        }
    }

    override suspend fun scrollToTop() {
        try {
            rpc.invokeDeferred("scrollToTop") {
                page.evaluate("window.scrollTo(0, 0)")
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "scrollToTop")
        }
    }

    override suspend fun scrollToBottom() {
        try {
            rpc.invokeDeferred("scrollToBottom") {
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "scrollToBottom")
        }
    }

    override suspend fun scrollToMiddle(ratio: Double) {
        try {
            rpc.invokeDeferred("scrollToMiddle") {
                page.evaluate("window.scrollTo(0, document.body.scrollHeight * $ratio)")
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "scrollToMiddle", "ratio: $ratio")
        }
    }

    override suspend fun mouseWheelDown(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {
        try {
            rpc.invokeDeferred("mouseWheelDown") {
                repeat(count) {
                    page.mouse().wheel(deltaX, deltaY)
                    delay(delayMillis)
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(
                e,
                "mouseWheelDown",
                "count: $count, deltaX: $deltaX, deltaY: $deltaY, delayMillis: $delayMillis"
            )
        }
    }

    override suspend fun mouseWheelUp(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {
        try {
            rpc.invokeDeferred("mouseWheelUp") {
                repeat(count) {
                    page.mouse().wheel(deltaX, deltaY)
                    delay(delayMillis)
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(
                e,
                "mouseWheelUp",
                "count: $count, deltaX: $deltaX, deltaY: $deltaY, delayMillis: $delayMillis"
            )
        }
    }

    override suspend fun moveMouseTo(x: Double, y: Double) {
        try {
            rpc.invokeDeferred("moveMouseTo") {
                page.mouse().move(x, y)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "moveMouseTo", "x: $x, y: $y")
        }
    }

    override suspend fun moveMouseTo(selector: String, deltaX: Int, deltaY: Int) {
        try {
            rpc.invokeDeferred("moveMouseTo") {
                val element = page.querySelector(selector)
                val boundingBox = element.boundingBox()
                if (boundingBox != null) {
                    page.mouse().move(boundingBox.x + deltaX, boundingBox.y + deltaY)
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "moveMouseTo", "selector: $selector, deltaX: $deltaX, deltaY: $deltaY")
        }
    }

    override suspend fun dragAndDrop(selector: String, deltaX: Int, deltaY: Int) {
        try {
            rpc.invokeDeferred("dragAndDrop") {
                val element = page.querySelector(selector)
                val boundingBox = element.boundingBox()
                if (boundingBox != null) {
                    page.mouse().move(boundingBox.x, boundingBox.y)
                    page.mouse().down()
                    page.mouse().move(boundingBox.x + deltaX, boundingBox.y + deltaY)
                    page.mouse().up()
                }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "dragAndDrop", "selector: $selector, deltaX: $deltaX, deltaY: $deltaY")
        }
    }

    override suspend fun outerHTML(): String? {
        return try {
            rpc.invokeDeferred("outerHTML") {
                page.content()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "outerHTML")
            null
        }
    }

    override suspend fun outerHTML(selector: String): String? {
        return try {
            rpc.invokeDeferred("outerHTML") {
                page.querySelector(selector).innerHTML()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "outerHTML", selector)
            null
        }
    }

    override suspend fun selectFirstTextOrNull(selector: String): String? {
        return try {
            rpc.invokeDeferred("selectFirstTextOrNull") {
                page.querySelector(selector).textContent()
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "selectFirstTextOrNull", selector)
            null
        }
    }

    override suspend fun selectTextAll(selector: String): List<String> {
        return try {
            rpc.invokeDeferred("selectTextAll") {
                page.querySelectorAll(selector).map { it.textContent() }
            } ?: listOf()
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "selectTextAll", selector)
            listOf()
        }
    }

    override suspend fun selectFirstAttributeOrNull(selector: String, attrName: String): String? {
        return try {
            rpc.invokeDeferred("selectFirstAttributeOrNull") {
                page.querySelector(selector).getAttribute(attrName)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "selectFirstAttributeOrNull", "selector: $selector, attrName: $attrName")
            null
        }
    }

    override suspend fun evaluate(expression: String): Any? {
        return evaluateDetail(expression)?.value
    }

    override suspend fun evaluateDetail(expression: String): JsEvaluation? {
        return try {
            rpc.invokeDeferred("evaluateDetail") {
                val result = page.evaluate(settings.confuser.confuse(expression))
                JsEvaluation(result)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "evaluateDetail", expression)
            null
        }
    }

    override suspend fun evaluateValue(expression: String): Any? {
        return evaluateValueDetail(expression)?.value
    }

    override suspend fun evaluateValueDetail(expression: String): JsEvaluation? {
        return try {
            rpc.invokeDeferred("evaluateDetail") {
                val result = page.evaluate(settings.confuser.confuse(expression))
                JsEvaluation(result)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "evaluateDetail", expression)
            null
        }
    }

    /**
     * Captures a screenshot of the current page.
     * @return The screenshot as a base64 encoded string
     * @throws RuntimeException if screenshot capture fails
     */
    override suspend fun captureScreenshot(): String? {
        return try {
            rpc.invokeDeferred("captureScreenshot") {
                Base64.getEncoder().encodeToString(page.screenshot())
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "captureScreenshot")
            null
        }
    }

    /**
     * Captures a screenshot of a specific element.
     * @param selector The CSS selector of the element
     * @return The screenshot as a base64 encoded string
     * @throws RuntimeException if screenshot capture fails
     */
    override suspend fun captureScreenshot(selector: String): String? {
        return try {
            rpc.invokeDeferred("captureScreenshot") {
                val sc = page.querySelector(selector).screenshot()
                Base64.getEncoder().encodeToString(sc)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "captureScreenshot", selector)
            null
        }
    }

    override suspend fun captureScreenshot(rect: RectD): String? {
        return try {
            rpc.invokeDeferred("captureScreenshot") {
                val sc = page.screenshot(Page.ScreenshotOptions().setClip(rect.x, rect.y, rect.width, rect.height))
                Base64.getEncoder().encodeToString(sc)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "captureScreenshot", "rect: $rect")
            null
        }
    }

    override suspend fun clickablePoint(selector: String): PointD? {
        return try {
            rpc.invokeDeferred("clickablePoint") {
                val boundingBox = page.querySelector(selector).boundingBox()
                boundingBox?.let { PointD(it.x + it.width / 2, it.y + it.height / 2) }
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "clickablePoint", selector)
            null
        }
    }

    override suspend fun boundingBox(selector: String): RectD? {
        return try {
            rpc.invokeDeferred("boundingBox") {
                val box = page.querySelector(selector).boundingBox() ?: return@invokeDeferred null
                RectD(box.x, box.y, box.width, box.height)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "boundingBox", selector)
            null
        }
    }

    /**
     * Creates a new Jsoup session for making HTTP requests.
     * @return A new Jsoup Connection instance
     */
    override suspend fun newJsoupSession(): Connection {
        return try {
            rpc.invokeDeferred("newJsoupSession") {
                org.jsoup.Jsoup.newSession()
            } ?: throw WebDriverException("Failed to create Jsoup session")
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "newJsoupSession")
            throw WebDriverException("Failed to create Jsoup session", e)
        }
    }

    /**
     * Loads a resource using Jsoup.
     * @param url The URL of the resource to load
     * @return The response from the resource
     * @throws RuntimeException if loading fails
     */
    override suspend fun loadJsoupResource(url: String): Connection.Response {
        return try {
            rpc.invokeDeferred("loadJsoupResource") {
                org.jsoup.Jsoup.connect(url).execute()
            } ?: throw WebDriverException("Failed to load Jsoup resource: $url")
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "loadJsoupResource", url)
            throw WebDriverException("Failed to load Jsoup resource: $url", e)
        }
    }

    /**
     * Loads a network resource.
     * @param url The URL of the resource to load
     * @return The network resource response
     * @throws RuntimeException if loading fails
     */
    override suspend fun loadResource(url: String): NetworkResourceResponse {
        return try {
            rpc.invokeDeferred("loadResource") {
                val response = page.context().request().get(url)
                NetworkResourceResponse(
                    success = response.ok(),
                    httpStatusCode = response.status(),
                    stream = response.body()?.let { String(it) },
                    headers = response.headers()
                )
            } ?: throw WebDriverException("Failed to load resource: $url")
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "loadResource", url)
            throw WebDriverException("Failed to load resource: $url", e)
        }
    }

    override suspend fun pause() {
        try {
            rpc.invokeDeferred("pause") {
                page.pause()
            }
        } catch (e: Exception) {
            logger.warn("Failed to pause: ${e.message}")
        }
    }

    override suspend fun stop() {
        try {
            rpc.invokeDeferred("stop") {
                navigateTo(ChromeImpl.ABOUT_BLANK_PAGE)
            }
        } catch (e: Exception) {
            logger.warn("Failed to stop: ${e.message}")
        }
    }

    /**
     * Closes the browser page and releases associated resources.
     */
    override fun close() {
        try {
            page.close()
        } catch (e: Exception) {
            logger.warn("Error during close: ${e.message}")
        }
    }

    private fun createJsEvaluate(evaluate: Evaluate?): JsEvaluation? {
        evaluate ?: return null

        val result = evaluate.result
        val exception = evaluate.exceptionDetails
        return if (exception != null) {
            val jsException = JsException(
                text = exception.text,
                lineNumber = exception.lineNumber,
                columnNumber = exception.columnNumber,
                url = exception.url,
            )
            JsEvaluation(exception = jsException)
        } else {
            JsEvaluation(
                value = result.value,
                unserializableValue = result.unserializableValue,
                className = result.className,
                description = result.description
            )
        }
    }

    private fun check(page: Page, url: String) {
        check(!page.isClosed) { "Page is closed | $url" }
    }

    override suspend fun waitForFrameLoad(frameId: String, timeout: Duration): Boolean {
        return try {
            rpc.invokeDeferred("waitForFrameLoad") {
                val deadline = System.nanoTime() + timeout.toNanos()
                while (System.nanoTime() < deadline) {
                    if (page.frames().any { ensureFrameId(it) == frameId }) return@invokeDeferred true
                    delay(50)
                }
                false
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "waitForFrameLoad", frameId)
            false
        }
    }

    override suspend fun switchToFrame(index: Int): Boolean {
        return try {
            rpc.invokeDeferred("switchToFrame") {
                val frames = page.frames()
                if (index in frames.indices) {
                    currentFrame = frames[index]
                    true
                } else false
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "switchToFrame", "index: $index")
            false
        }
    }

    override suspend fun switchToFrame(nameOrId: String): Boolean {
        return try {
            rpc.invokeDeferred("switchToFrame") {
                val frame = page.frames().firstOrNull { f ->
                    f.name() == nameOrId || ensureFrameId(f) == nameOrId
                }
                if (frame != null) {
                    currentFrame = frame
                    true
                } else false
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "switchToFrame", nameOrId)
            false
        }
    }

    override suspend fun switchToParentFrame(): Boolean {
        return try {
            rpc.invokeDeferred("switchToParentFrame") {
                val cf = currentFrame ?: page.mainFrame()
                val parent = cf.parentFrame()
                if (parent != null) {
                    currentFrame = parent
                    true
                } else false
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "switchToParentFrame")
            false
        }
    }

    override suspend fun switchToDefaultContent(): Boolean {
        return try {
            rpc.invokeDeferred("switchToDefaultContent") {
                currentFrame = page.mainFrame()
                true
            } ?: false
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "switchToDefaultContent")
            false
        }
    }

    override suspend fun getFrames(): List<FrameInfo> {
        return try {
            rpc.invokeDeferred("getFrames") {
                page.frames().map { f ->
                    FrameInfo(
                        frameId = ensureFrameId(f),
                        url = f.url(),
                        name = f.name(),
                        parentFrameId = f.parentFrame()?.let { ensureFrameId(it) },
                        isAccessible = true,
                        loadState = "LOADED"
                    )
                }
            } ?: emptyList()
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "getFrames")
            emptyList()
        }
    }

    override suspend fun getCurrentFrame(): FrameInfo? {
        return try {
            rpc.invokeDeferred("getCurrentFrame") {
                val cf = currentFrame ?: page.mainFrame()
                FrameInfo(
                    frameId = ensureFrameId(cf),
                    url = cf.url(),
                    name = cf.name(),
                    parentFrameId = cf.parentFrame()?.let { ensureFrameId(it) },
                    isAccessible = true,
                    loadState = "LOADED"
                )
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "getCurrentFrame")
            null
        }
    }

    override fun isInFrame(): Boolean {
        val cf = currentFrame
        return cf != null && cf != page.mainFrame()
    }

    override suspend fun evaluateInFrame(expression: String): Any? {
        return try {
            rpc.invokeDeferred("evaluateInFrame") {
                val cf = currentFrame ?: page.mainFrame()
                // Playwright Frame has evaluate; fall back to page if needed
                cf.evaluate(expression)
            }
        } catch (e: Exception) {
            rpc.handleWebDriverException(e, "evaluateInFrame", expression)
            null
        }
    }
}
