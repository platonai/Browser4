package ai.platon.pulsar.protocol.browser.driver.cdt

import ai.platon.browser4.driver.chrome.*
import ai.platon.browser4.driver.chrome.experimental.CDP
import ai.platon.browser4.driver.chrome.dom.SnapshotService
import ai.platon.browser4.driver.chrome.dom.model.NanoDOMTree
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.browser4.driver.chrome.dom.model.ViewportSpec
import ai.platon.browser4.driver.chrome.impl.ChromeImpl
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.browser4.driver.chrome.util.ChromeIOException
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSent
import ai.platon.cdt.kt.protocol.events.network.ResponseReceived
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.events.page.WindowOpen
import ai.platon.cdt.kt.protocol.types.fetch.RequestPattern
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourceOptions
import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.cdt.kt.protocol.types.runtime.CallArgument
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.math.geometric.OffsetD
import ai.platon.pulsar.common.math.geometric.PointD
import ai.platon.pulsar.common.math.geometric.RectD
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.protocol.browser.driver.cdt.detail.*
import ai.platon.pulsar.skeleton.workflow.common.InternalURLUtil
import ai.platon.pulsar.skeleton.workflow.fetch.driver.*
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.annotations.Beta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.SystemUtils
import org.apache.commons.lang3.StringUtils
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class PulsarWebDriver constructor(
    uniqueID: String,
    val chromeTab: ChromeTab,
    val cdp: CDP,
    override val browser: PulsarBrowser
) : AbstractWebDriver(uniqueID, browser) {

    private val logger = getLogger(this)

    private val tracer get() = logger.takeIf { it.isTraceEnabled }

    override val browserType: BrowserType = BrowserType.PULSAR_CHROME

    @Deprecated("Use CDP facade (cdp) instead of direct devTools access")
    val devTools: RemoteDevTools get() = cdp.remoteDevTools
    private val browserAPI get() = cdp.browser.takeIf { isActive }
    private val pageAPI get() = cdp.page.takeIf { isActive }
    private val targetAPI get() = cdp.target.takeIf { isActive }
    private val domAPI get() = cdp.dom.takeIf { isActive }
    private val cssAPI get() = cdp.css.takeIf { isActive }
    private val inputAPI get() = cdp.input.takeIf { isActive }
    private val mainFrameAPI get() = runBlocking { pageAPI?.getFrameTree()?.frame }
    private val networkAPI get() = cdp.network.takeIf { isActive }
    private val fetchAPI get() = cdp.fetch.takeIf { isActive }
    private val runtimeAPI get() = cdp.runtime.takeIf { isActive }
    private val emulationAPI get() = cdp.emulation.takeIf { isActive }

    private val isolatedWorldManager = IsolatedWorldManager(cdp.remoteDevTools, settings)
    private val page = PageHandler(cdp.remoteDevTools, isolatedWorldManager)
    private val jsHandler get() = page.jsHandler
    private val mouse get() = page.mouse.takeIf { isActive }
    private val keyboard get() = page.keyboard.takeIf { isActive }
    private val screenshot = ScreenshotHandler(page, cdp.remoteDevTools)
    private val emulator get() = EmulationHandler(pageAPI, domAPI, keyboard, mouse, cdp.remoteDevTools)

    private val rpc = RobustRPC(this)
    private val networkManager by lazy { NetworkManager(this, rpc) }
    private val messageWriter = MultiSinkMessageWriter()

    private val driverHelper get() = WebDriverHelper(this, rpc, page, fetchAPI, messageWriter)

    private val closed = AtomicBoolean()

    private val isGone get() = closed.get() || isQuit || !AppContext.isActive || !cdp.isOpen

    var userTypedUrl: String? = null
    var navigateUrl: String? = chromeTab.url
    private var credentials: Credentials? = null

    val isNetworkIdle get() = networkManager.isIdle

    var fingerprintApplier: ((WebDriver) -> Unit)? = null

    /**
     * Expose the underlying implementation, used for diagnosis purpose
     * */
    override val implementation: Any get() = cdp.remoteDevTools

    override val snapshotService: SnapshotService get() = page.snapshotService

    init {
        fingerprintApplier?.invoke(this)
    }

    override suspend fun addBlockedURLs(urlPatterns: List<String>) {
        _blockedURLPatterns.addAll(urlPatterns)
    }

    @Throws(WebDriverException::class)
    override suspend fun navigate(entry: NavigateEntry) {
        userTypedUrl = entry.url

        navigateHistory.add(entry)
        this.navigateEntry = entry

        browser.emit(BrowserEvents.willNavigate, entry)

        driverHelper.invokeOnPage("enableAPIAgents") {
            enableAPIAgents()
        }

        driverHelper.invokeOnPage("navigate") {
            navigateInvaded(entry)
        }
    }

    override suspend fun reload() {
        driverHelper.invokeOnPage("reload") {
            pageAPI?.reload()
        }
    }

    override suspend fun goBack() {
        driverHelper.invokeOnPage("goBack") {
            val history = pageAPI?.getNavigationHistory() ?: return@invokeOnPage
            val currentIndex = history.currentIndex
            val entries = history.entries
            val targetIndex = currentIndex - 1
            if (targetIndex >= 0 && targetIndex < entries.size) {
                val entryId = entries[targetIndex].id
                pageAPI?.navigateToHistoryEntry(entryId)
            }
        }
    }

    override suspend fun goForward() {
        driverHelper.invokeOnPage("goForward") {
            val history = pageAPI?.getNavigationHistory() ?: return@invokeOnPage
            val currentIndex = history.currentIndex
            val entries = history.entries
            val targetIndex = currentIndex + 1
            if (targetIndex >= 0 && targetIndex < entries.size) {
                val entryId = entries[targetIndex].id
                pageAPI?.navigateToHistoryEntry(entryId)
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun getCookies(): List<Map<String, String>> {
        return driverHelper.invokeOnPage("getCookies") { getCookies0() } ?: listOf()
    }

    override suspend fun deleteCookies(name: String, url: String?, domain: String?, path: String?) {
        driverHelper.invokeOnPage("deleteCookies") { cdpDeleteCookies(name, url, domain, path) }
    }

    override suspend fun clearBrowserCookies() {
        driverHelper.invokeOnPage("clearBrowserCookies") { networkAPI?.clearBrowserCookies() }
    }

    // Use the JavaScript version in super class
    override suspend fun selectFirstAttributeOrNull(selector: String, attrName: String): String? {
        val name = "selectFirstAttributeOrNull"
        return driverHelper.invokeOnElement(selector, name) {
            page.getAttribute(it, attrName)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluate(expression: String): Any? {
        return driverHelper.invokeOnPage("evaluate") { jsHandler.evaluate(expression) }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateDetail(expression: String): JsEvaluation? {
        return driverHelper.invokeOnPage("evaluateDetail") {
            driverHelper.createJsEvaluate(
                jsHandler.evaluateDetail(
                    expression
                )
            )
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValue(expression: String): Any? {
        return driverHelper.invokeOnPage("evaluateValue") { jsHandler.evaluateValue(expression) }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValueDetail(expression: String): JsEvaluation? {
        return driverHelper.invokeOnPage("evaluateValueDetail") {
            val evaluate = jsHandler.evaluateValueDetail(expression)
            driverHelper.createJsEvaluate(evaluate)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValue(selector: String, functionDeclaration: String): Any? {
        return evaluateValueDetail(selector, functionDeclaration)?.value
    }

    @Throws(WebDriverException::class)
    override suspend fun evaluateValueDetail(selector: String, functionDeclaration: String): JsEvaluation? {
        return driverHelper.invokeOnPage("evaluateValue") {
            val normalizedFunctionDeclaration = normalizeElementFunctionDeclaration(functionDeclaration)
            val callFunctionOn = jsHandler.callFunctionOn(selector, normalizedFunctionDeclaration)
            driverHelper.createJsEvaluate(callFunctionOn)
        }
    }

    private fun normalizeElementFunctionDeclaration(functionDeclaration: String): String {
        val callable = functionDeclaration.trim().removeSuffix(";").trim()
        return """
            function() {
                const __browser4Element = this;
                return ($callable).call(__browser4Element, __browser4Element);
            }
        """.trimIndent()
    }

    override suspend fun currentUrl(): String {
        val mainFrameUrl = evaluate("document.URL", navigateUrl)
        navigateUrl = mainFrameUrl ?: navigateUrl
        return navigateUrl ?: userTypedUrl ?: ""
    }

    @Throws(WebDriverException::class)
    override suspend fun exists(selector: String): Boolean {
        return page.exists(selector)
//        driverHelper.predicateOnElement(selector) {
//        }
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

    @Throws(WebDriverException::class)
    private suspend fun waitForNavigationExperimental(oldUrl: String, timeout: Duration): Duration {
        val startTime = Instant.now()

        try {
            val channel = Channel<String>()

            pageAPI?.onDocumentOpened {
                // keep oldUrl check for debugging / future use
                @Suppress("UNUSED_VARIABLE")
                val navigated = it.frame.url != oldUrl
                // emit(Navigation)
                channel.trySend("navigated")
            }

            channel.receive()
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "waitForNavigation $timeout")
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
        return page.isVisible(selector)
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
        driverHelper.invokeOnElement(selector, actionName, scrollIntoView = true) { node ->
            withNodeObjectId(cdp, node) { objectId ->
                val result = runtimeAPI?.callFunctionOn(
                    CheckableElementJs.SET_CHECKED_FUNCTION_DECLARATION,
                    objectId = objectId,
                    arguments = listOf(CallArgument(value = shouldCheck)),
                    returnByValue = true,
                    userGesture = true,
                    awaitPromise = true
                )

                if (result?.exceptionDetails != null) {
                    throw WebDriverException("JS Error in $actionName: " + result.exceptionDetails?.exception?.description)
                }

                result?.result?.value as? Boolean ?: false
            } ?: false
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseWheelDown(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {
        try {
            rpc.invokeWithRetry("mouseWheelDown", 1) {
                repeat(count) { i ->
                    if (i > 0) {
                        if (delayMillis > 0) gap(delayMillis) else gap("mouseWheel")
                    }

                    mouse?.wheel(deltaX, deltaY)
                }
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "mouseWheelDown")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseWheelUp(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {
        try {
            rpc.invokeWithRetry("mouseWheelUp", 1) {
                repeat(count) { i ->
                    if (i > 0) {
                        if (delayMillis > 0) gap(delayMillis) else gap("mouseWheel")
                    }

                    mouse?.wheel(deltaX, deltaY)
                }
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "mouseWheelUp")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseWheel(deltaX: Double, deltaY: Double) {
        driverHelper.invokeOnPage("mouseWheel") { mouse?.wheel(deltaX, deltaY) }
    }

    @Throws(WebDriverException::class)
    override suspend fun moveMouseTo(x: Double, y: Double) {
        driverHelper.invokeOnPage("moveMouseTo") { mouse?.moveTo(x, y) }
    }

    @Throws(WebDriverException::class)
    override suspend fun mouseMove(x: Double, y: Double) {
        driverHelper.invokeOnPage("mouseMove") { mouse?.moveTo(x, y) }
    }

    /**
     * TODO: use CDP to implement mouseDown: CDP → Browser Input System → Hit Testing → DOM → JS Event
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
        driverHelper.invokeOnPage("mouseDown") { evaluate(script) }
    }

    /**
     * TODO: use CDP to implement mouseUp: CDP → Browser Input System → Hit Testing → DOM → JS Event
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
        driverHelper.invokeOnPage("mouseUp") { evaluate(script) }
    }

    @Throws(WebDriverException::class)
    override suspend fun moveMouseTo(selector: String, deltaX: Int, deltaY: Int) {
        try {
            val node = rpc.invokeWithRetry("scrollIntoViewIfNeeded") {
                page.scrollIntoViewIfNeeded(selector)
            } ?: return

            val offset = OffsetD(4.0, 4.0)
            if (!isActive) return

            rpc.invokeWithRetry("moveMouseTo") {
                val point = ClickableDOM(cdp, node, offset).clickablePoint().value
                if (point != null) {
                    val point2 = PointD(point.x + deltaX, point.y + deltaY)
                    mouse?.moveTo(point2)
                }
                gap()
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "moveMouseTo")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun hover(selector: String) {
        bringToFront()
        driverHelper.invokeOnElement(selector, "hover", scrollIntoView = true) { node ->
            emulator.hover(node, position = "center")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun click(selector: String, count: Int) {
        driverHelper.invokeOnElement(selector, "click", scrollIntoView = true) { node ->
            waitForScrollSettled(selector)
            val delayMillis = randomDelayMillis("click")
            emulator.click(node, count, position = "center", modifier = null, delayMillis = delayMillis)
            // debugElementOnPoint(node)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun click(selector: String, modifier: String) {
        driverHelper.invokeOnElement(selector, "click", scrollIntoView = true) { node ->
            val delayMillis = randomDelayMillis("click")
            waitForScrollSettled(selector)
            emulator.click(node, 1, position = "center", modifier = modifier, delayMillis = delayMillis)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun selectOption(selector: String, values: List<String>): List<String> {
        val mapper = jacksonObjectMapper()
        val jsonValues = mapper.writeValueAsString(values)

        val functionDeclaration = """
            function(jsonValues) {
                const values = JSON.parse(jsonValues);
                const element = this;
                if (!element || element.tagName !== 'SELECT') {
                    throw new Error('Element is not a <select> element');
                }

                const optionsToSelect = new Set(values);
                const selectedValues = [];
                let hasChanged = false;

                // Handle single select: only select the first match
                if (!element.multiple) {
                    for (let i = 0; i < element.options.length; i++) {
                        const option = element.options[i];
                        if (optionsToSelect.has(option.value) || optionsToSelect.has(option.label) || optionsToSelect.has(option.text)) {
                            if (!option.selected) {
                                option.selected = true;
                                hasChanged = true;
                            }
                            selectedValues.push(option.value);
                            break;
                        }
                    }
                } else {
                    // Handle multiple select
                    // Deselect all, then select specified ones.
                    for (let i = 0; i < element.options.length; i++) {
                        const option = element.options[i];
                        const shouldSelect = optionsToSelect.has(option.value) || optionsToSelect.has(option.label) || optionsToSelect.has(option.text);

                        if (shouldSelect != option.selected) {
                            option.selected = shouldSelect;
                            hasChanged = true;
                        }

                        if (shouldSelect) {
                            selectedValues.push(option.value);
                        }
                    }
                }

                if (hasChanged) {
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                }

                return selectedValues;
            }
        """.trimIndent()

        val result = driverHelper.invokeOnElement(selector, "selectOption") { node ->
            if (runtimeAPI == null) {
                throw WebDriverException("runtimeAPI is null")
            }

            withNodeObjectId(cdp, node) { objectId ->
                val res = runtimeAPI?.callFunctionOn(
                    functionDeclaration,
                    objectId = objectId,
                    arguments = listOf(CallArgument(value = jsonValues)),
                    returnByValue = true
                )

                if (res?.exceptionDetails != null) {
                    throw WebDriverException("JS Error in selectOption: " + res.exceptionDetails?.exception?.description)
                }

                val resultValue = res?.result?.value

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
        driverHelper.invokeOnElement(selector, "dblclick") {
            val node = page.focusOnSelector(selector) ?: return@invokeOnElement
            emulator.click(node, 2)
            gap("dblclick")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun resize(width: Int, height: Int) {
        driverHelper.invokeOnPage("resize") {
            emulationAPI?.setDeviceMetricsOverride(
                width = width,
                height = height,
                deviceScaleFactor = 0.0,
                mobile = false
            )
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun dialogAccept(promptText: String?) {
        driverHelper.invokeOnPage("dialogAccept") {
            pageAPI?.handleJavaScriptDialog(accept = true, promptText = promptText)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun dialogDismiss() {
        driverHelper.invokeOnPage("dialogDismiss") {
            pageAPI?.handleJavaScriptDialog(accept = false)
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
            driverHelper.invokeOnPage("type") {
                keyboard?.type(text, randomDelayMillis("type"))
                gap("type")
            }
            return
        }

        driverHelper.invokeOnElement(selector, "type") {
            val node = page.focusOnSelector(selector) ?: return@invokeOnElement
            emulator.click(node, 1, position = "right")
            keyboard?.type(text, randomDelayMillis("type"))
            gap("type")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun fill(selector: String, text: String) {
        driverHelper.invokeOnElement(selector, "fill", focus = true) { node ->
            // TODO: check if the element is editable

            clear(node)

            emulator.click(node, 1, "right")

            // For fill, there is no delay between key presses, just like paste
            keyboard?.type(text, 0)

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

        return withNodeObjectId(cdp, node) { objectId ->
            runtimeAPI?.callFunctionOn(
                "function() { return this && typeof this.value !== 'undefined' ? this.value : null; }",
                objectId = objectId,
                returnByValue = true
            )?.result?.value?.toString() ?: ""
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
        driverHelper.invokeOnElement(selector, "upload", focus = true) { node ->
            domAPI?.setFileInputFiles(files = paths, nodeId = node.nodeId)
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun press(key: String, selector: String?) {
        if (selector.isNullOrBlank()) {
            driverHelper.invokeOnPage("press") {
                keyboard?.press(key, randomDelayMillis("press"))
                gap("press")
            }
            return
        }

        driverHelper.invokeOnElement(selector, "press", scrollIntoView = true) { node ->
            emulator.click(node, 1, position = "right")
            keyboard?.press(key, randomDelayMillis("press"))
            gap("press")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun keyDown(key: String) {
        driverHelper.invokeOnPage("keyDown") {
            if (alwaysTrue() || SystemUtils.IS_OS_WINDOWS) {
                // TODO: keydown 事件不太可靠，先用 DOM 事件模拟，后续优化
                dispatchDomKeyboardEvent("keydown", key)
            } else {
                keyboard?.down(key)
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun keyUp(key: String) {
        driverHelper.invokeOnPage("keyUp") {
            if (alwaysTrue() || SystemUtils.IS_OS_WINDOWS) {
                // TODO: keyup 事件不太可靠，先用 DOM 事件模拟，后续优化
                dispatchDomKeyboardEvent("keyup", key)
            } else {
                keyboard?.up(key)
            }
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
            val node = rpc.invokeWithRetry("scrollIntoViewIfNeeded") {
                page.scrollIntoViewIfNeeded(selector)
            }

            if (node == null) {
                throw WebDriverException("Failed to scroll element into view: $selector", driver = this)
            }

            // Use randomized offset like in click() for better anti-detection
            val deltaOffsetX = 4.0 + Random.nextInt(4)
            val deltaOffsetY = 4.0 + Random.nextInt(4)  // Add randomization to Y offset
            val offset = OffsetD(deltaOffsetX, deltaOffsetY)

            if (!isActive) throw IllegalWebDriverStateException("CDP is not active", driver = this)
            val m = mouse ?: throw IllegalWebDriverStateException("Mouse not available", driver = this)

            rpc.invokeWithRetry("dragAndDrop") {
                val clickableDOM = ClickableDOM(cdp, node, offset)
                val clickableResult = clickableDOM.clickablePoint()
                val startPoint = clickableResult.value

                if (startPoint == null) {
                    throw WebDriverException(
                        "Element is not clickable/draggable: $selector | ${clickableResult.message}",
                        driver = this
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

                // Use mouse to perform drag-and-drop via CDP drag events
                m.dragAndDrop(startPoint, targetPoint, randomDelayMillis("dragAndDrop"))

                gap()
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "dragAndDrop")
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun outerHTML(selector: String): String? {
        return driverHelper.invokeOnElement(selector, "outerHTML") { node ->
            when {
                node.isNull() -> null
                // TODO: performance issue for large HTML (memory copy), consider accept the raw byte stream and convert to string in native code
                else -> domAPI?.getOuterHTML(node.nodeId, node.backendNodeId, node.objectId)
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun ariaSnapshot(): String {
        return rpc.invokeDeferredSilently("ariaSnapshot") { page.ariaSnapshot() } ?: ""
    }

    @Throws(WebDriverException::class)
    override suspend fun ariaSnapshot(viewports: String): String {
        val viewportIndices = ViewportSpec.parse(viewports)
            ?: return ariaSnapshot()
        return rpc.invokeDeferredSilently("ariaSnapshot") { page.ariaSnapshot(viewportIndices) } ?: ""
    }

    @Beta
    @Throws(WebDriverException::class)
    override suspend fun querySelectorAll(selector: String): List<NodeRef> {
        return driverHelper.invokeOnPage("select") { page.queryLocatorAll(selector) } ?: listOf()
    }

    @Throws(WebDriverException::class)
    override suspend fun selectFirstTextOrNull(selector: String): String? {
        return driverHelper.invokeOnElement(selector, "selectFirstTextOrNull") { node ->
            when {
                node.isNull() -> null
                else -> {
                    val functionDeclaration = """
function() {
  try {
    const el = this;
    const excluded = new Set(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE']);
    let text = '';
    const walker = document.createTreeWalker(
      el,
      NodeFilter.SHOW_TEXT,
      {
        acceptNode(node) {
          const p = node.parentNode;
          return p && !excluded.has(p.nodeName)
            ? NodeFilter.FILTER_ACCEPT
            : NodeFilter.FILTER_REJECT;
        }
      }
    );
    let n;
    while ((n = walker.nextNode())) {
      text += n.nodeValue;
    }
    return text;
  } catch (e) {
    return null;
  }
}
                    """.trimIndent()
                    withNodeObjectId(cdp, node) { objectId ->
                            val remoteObject = runtimeAPI?.callFunctionOn(
                                functionDeclaration,
                                objectId = objectId,
                                returnByValue = true
                            )
                            // TODO: performance issue for large text (memory copy)
                            remoteObject?.result?.value?.toString()
                    }
                }
            }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun selectTextAll(selector: String): List<String> {
        val safeSelector = page.normalizeLocatorForJs(selector)
        val json = evaluate("__pulsar_utils__.selectTextAll('$safeSelector')")?.toString() ?: "[]"
        return jacksonObjectMapper().readValue(json)
    }

    override suspend fun selectAttributes(selector: String): Map<String, String> {
        val safeSelector = page.normalizeLocatorForJs(selector)
        val json = evaluate("__pulsar_utils__.selectAttributes('$safeSelector')")?.toString() ?: return mapOf()
        val attributes: List<String> = jacksonObjectMapper().readValue(json)
        return attributes.zipWithNext().associate { it }
    }

    @Throws(WebDriverException::class)
    override suspend fun selectAttributeAll(selector: String, attrName: String, start: Int, limit: Int): List<String> {
        val end = start + limit
        val safeSelector = page.normalizeLocatorForJs(selector)

        val expression = "__pulsar_utils__.selectAttributeAll('$safeSelector', '$attrName', $start, $end)"
        val json = evaluate(expression)?.toString() ?: return listOf()
        return jacksonObjectMapper().readValue(json)
    }

    @Throws(WebDriverException::class)
    override suspend fun setAttribute(selector: String, attrName: String, attrValue: String) {
        val safeSelector = page.normalizeLocatorForJs(selector)
        evaluate("__pulsar_utils__.setAttribute('$safeSelector', '$attrName', '$attrValue')")
    }

    @Throws(WebDriverException::class)
    override suspend fun setAttributeAll(selector: String, attrName: String, attrValue: String) {
        val safeSelector = page.normalizeLocatorForJs(selector)
        evaluate("__pulsar_utils__.setAttributeAll('$safeSelector', '$attrName', '$attrValue')")
    }

    // --------------------------- Property helpers ---------------------------
    @Throws(WebDriverException::class)
    override suspend fun selectFirstPropertyValueOrNull(selector: String, propName: String): String? {
        val safeSelector = page.normalizeLocatorForJs(selector)
        return evaluateValue("__pulsar_utils__.selectFirstPropertyValue('$safeSelector', '$propName')")?.toString()
    }

    @Throws(WebDriverException::class)
    override suspend fun selectPropertyValueAll(
        selector: String, propName: String, start: Int, limit: Int
    ): List<String> {
        val end = start + limit
        val safeSelector = page.normalizeLocatorForJs(selector)
        val expression = "__pulsar_utils__.selectPropertyValueAll('$safeSelector', '$propName', $start, $end)"
        val json = evaluate(expression)?.toString() ?: return listOf()
        return jacksonObjectMapper().readValue(json)
    }

    @Throws(WebDriverException::class)
    override suspend fun setProperty(selector: String, propName: String, propValue: String) {
        val safeSelector = page.normalizeLocatorForJs(selector)
        evaluate("__pulsar_utils__.setProperty('$safeSelector', '$propName', '$propValue')")
    }

    @Throws(WebDriverException::class)
    override suspend fun setPropertyAll(selector: String, propName: String, propValue: String) {
        val safeSelector = page.normalizeLocatorForJs(selector)
        evaluate("__pulsar_utils__.setPropertyAll('$safeSelector', '$propName', '$propValue')")
    }

    @Throws(WebDriverException::class)
    override suspend fun clickTextMatches(selector: String, pattern: String, count: Int) {
        val safeSelector = page.normalizeLocatorForJs(selector)
        evaluate("__pulsar_utils__.clickTextMatches('$safeSelector', '$pattern')")
    }

    @Throws(WebDriverException::class)
    override suspend fun clickMatches(selector: String, attrName: String, pattern: String, count: Int) {
        val safeSelector = page.normalizeLocatorForJs(selector)
        evaluate("__pulsar_utils__.clickMatches('$safeSelector', '$attrName', '$pattern')")
    }












    @Throws(WebDriverException::class)
    override suspend fun clickablePoint(selector: String): PointD? {
        try {
            return rpc.invokeWithRetry("clickablePoint") {
                val node = page.scrollIntoViewIfNeeded(selector)
                ClickableDOM.create(cdp, node)?.clickablePoint()?.value
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "clickablePoint")
        }

        return null
    }

    @Throws(WebDriverException::class)
    override suspend fun boundingBox(selector: String): RectD? {
        try {
            return rpc.invokeWithRetry("boundingBox") {
                val node = page.scrollIntoViewIfNeeded(selector)
                ClickableDOM.create(cdp, node)?.boundingBox()
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "boundingBox")
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
            rpc.invokeWithRetry("screenshot") {
                screenshot.screenshot(fullPage)
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "screenshot")
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
            rpc.invokeWithRetry("screenshot") { screenshot.screenshot(selector) }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "screenshot")
            null
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun screenshot(rect: RectD): String? {
        return try {
            // Force the page stop all navigations and pending resource fetches.
            rpc.invokeWithRetry("screenshot") { screenshot.screenshot(rect) }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, "screenshot")
            null
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun pageSource(): String? {
        return driverHelper.invokeOnPage("pageSource") {
            // TODO: use pageAPI?.getResourceContent instead 1. semantic consistency 2. performance
            // pageAPI?.getResourceContent(mainFrameAPI?.id, currentUrl())
            val document = domAPI?.getDocument() ?: return@invokeOnPage null
            // TODO: pass only one of nodeId and backendNodeId
            domAPI?.getOuterHTML(document.nodeId, document.backendNodeId)
        }
    }

    override suspend fun nanoDOMTree(): NanoDOMTree? {
        return rpc.invokeWithRetry("nanoDOMTree") {
            val snapshotOptions = SnapshotOptions()
            val domState = snapshotService.getDOMState(snapshotOptions = snapshotOptions)
            domState.serializableTree.toNanoTreeInRange()
        }
    }

    override suspend fun bringToFront() {
        rpc.invokeDeferredSilently("bringToFront") {
            pageAPI?.bringToFront()
            browser.frontDriver = this
        }
    }

    override fun awaitTermination() {
        cdp.awaitTermination()
    }

    override suspend fun loadResource(url: String): NetworkResourceResponse {
        val options = LoadNetworkResourceOptions(
            disableCache = false, includeCredentials = false
        )

        val response = rpc.invokeWithRetry("loadNetworkResource") {
            val frameId = pageAPI?.getFrameTree()?.frame?.id ?: return@invokeWithRetry null
            val resource = networkAPI?.loadNetworkResource(frameId, url, options) ?: return@invokeWithRetry null
            NetworkResourceResponse.from(resource)
        }

        return response ?: NetworkResourceResponse()
    }

    /**
     * Close the tab hold by this driver.
     * */
    override fun close() {
        browser.destroyDriver(this)
        doClose()
    }

    fun doClose() {
        super.close()

        if (closed.compareAndSet(false, true)) {
            runCatching { cdp.close() }.onFailure { warnForClose(this, it) }
        }
    }

    @Throws(WebDriverException::class)
    override suspend fun pause() {
        driverHelper.invokeOnPage("pause") { pageAPI?.stopLoading() }
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
                pageAPI?.stopLoading()
            } else {
                // go to about:blank, so the browser stops the previous page and releases all resources
                navigate(ChromeImpl.ABOUT_BLANK_PAGE)
            }
        } catch (e: ChromeIOException) {
            if (!e.isOpen || !cdp.isOpen) {
                // intentionally ignored: the chrome is closed
            }
        } catch (e: ChromeDriverException) {
            if (cdp.isOpen) {
                try {
                    rpc.handleChromeException(e, "terminate")
                } catch (e: Exception) {
                    logger.error("[Unexpected]", e)
                }
            }
        }
    }

    override fun toString() = "Driver#$id"

    /**
     *
     * */
    @Throws(ChromeIOException::class)
    suspend fun enableAPIAgents() {
        try {
            pageAPI?.enable()
            domAPI?.enable()
            runtimeAPI?.enable()
            networkAPI?.enable()
            cssAPI?.enable()

            if (resourceBlockProbability > 1e-6) {
                fetchAPI?.enable()
            }

            val proxyUsername = browser.id.fingerprint.proxyEntry?.username
            if (!proxyUsername.isNullOrBlank()) {
                // allow all url patterns
                val patterns = listOf(RequestPattern())
                fetchAPI?.enable(patterns, true)
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
        val url = entry.url

        addScriptToEvaluateOnNewDocument()

        if (blockedURLs.isNotEmpty()) {
            // Blocks URLs from loading.
            networkAPI?.setBlockedURLs(blockedURLs)
        }

        networkManager.enable()

        networkManager.on1(NetworkEvents.RequestWillBeSent) { event: RequestWillBeSent ->
            onRequestWillBeSent(entry, event)
        }
        networkManager.on1(NetworkEvents.ResponseReceived) { event: ResponseReceived ->
            onResponseReceived(entry, event)
        }

        pageAPI?.onFrameNavigated { onFrameNavigated(entry, it) }
        pageAPI?.onDocumentOpened { entry.mainRequestCookies = getCookies0() }
        pageAPI?.onWindowOpen { onWindowOpen(it) }

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
        if (!entry.url.startsWith("http")) {
            // This can happen for the following cases:
            // 1. non-http resources, for example, ftp, ws, etc.
            // 2. chrome's internal page, for example, about:blank, chrome://settings/, chrome://settings/system, etc.
            return
        }

        if (!URLUtils.isStandard(entry.url)) {
            logger.warn("Invalid url to sent to the browser | {}", entry.url)
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
            fetchAPI?.failRequest(event.requestId, ErrorReason.ABORTED)
        }

        // handle user-defined events
    }

    private fun isBlocked(url: String): Boolean {
        if (url in blockedURLs) {
            return true
        }

        if (resourceBlockProbability > 1e-6) {
            if (probabilisticBlockedURLs.any { url.matches(it.toRegex()) }) {
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
                val targetFrameId = pageAPI?.getFrameTree()?.frame?.id ?: event.frame.id
                val contextId = isolatedWorldManager.ensureRuntime(targetFrameId, isolatedWorldJs)
                logger.debug(
                    "Ensured Browser4 runtime in isolated world after main-frame navigation | frame={}",
                    targetFrameId
                )
            } else {
                logger.warn("No isolated world JS found to re-inject after frame navigation")
            }
        } catch (e: Exception) {
            logger.warn("Failed to re-inject Browser4 runtime after frame navigation", e)
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
        val host = InternalURLUtil.getHost(pageUrl) ?: "unknown"
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
                fetchAPI?.enable()
                fetchAPI?.getResponseBody(event.requestId)?.body
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
            // browser.addHistory(NavigateEntry(finalUrl))
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
            pageAPI?.addScriptToEvaluateOnNewDocument("\n;;\n$pageWorldJs\n;;\n")
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
                    contextId, StringUtils.abbreviateMiddle(userTypedUrl, "...", 200)
                )
                val evaluate = runtimeAPI?.evaluate("typeof(__pulsar_utils__)", contextId = contextId)
                if (evaluate?.result?.value != "function") {
                    logger.warn(
                        "Failed to verify isolated world injection: typeof(__pulsar_utils__) should be 'function' but got: {}",
                        evaluate?.result?.value
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
        val cookies = networkAPI?.getCookies()?.map { serialize(it) }
        return cookies ?: listOf()
    }

    private fun serialize(cookie: ai.platon.cdt.kt.protocol.types.network.Cookie): Map<String, String> {
        val mapper = jacksonObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        return mapper.readValue(mapper.writeValueAsString(cookie))
    }

    private suspend fun cdpDeleteCookies(
        name: String, url: String? = null, domain: String? = null, path: String? = null
    ) {
        networkAPI?.deleteCookies(name, url, domain, path)
    }

    private suspend fun waitForScrollSettled(selector: String, timeout: Duration = Duration.ofMillis(5_000)) {
        val safeSelector = page.normalizeLocatorForJs(selector)
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
        val safeKey = jacksonObjectMapper().writeValueAsString(key)
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
