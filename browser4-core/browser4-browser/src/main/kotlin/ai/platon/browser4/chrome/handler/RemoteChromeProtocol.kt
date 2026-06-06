package ai.platon.browser4.chrome.handler

import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.cdt.kt.protocol.events.console.MessageAdded
import ai.platon.cdt.kt.protocol.events.fetch.AuthRequired
import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.events.input.DragIntercepted
import ai.platon.cdt.kt.protocol.events.network.*
import ai.platon.cdt.kt.protocol.events.page.DocumentOpened
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.events.page.WindowOpen
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.cdt.kt.protocol.types.accessibility.AXNode
import ai.platon.cdt.kt.protocol.types.css.CSSComputedStyleProperty
import ai.platon.cdt.kt.protocol.types.dom.BoxModel
import ai.platon.cdt.kt.protocol.types.dom.Node
import ai.platon.cdt.kt.protocol.types.dom.PerformSearch
import ai.platon.cdt.kt.protocol.types.dom.Rect
import ai.platon.cdt.kt.protocol.types.domsnapshot.CaptureSnapshot
import ai.platon.cdt.kt.protocol.types.fetch.AuthChallengeResponse
import ai.platon.cdt.kt.protocol.types.fetch.HeaderEntry
import ai.platon.cdt.kt.protocol.types.fetch.RequestPattern
import ai.platon.cdt.kt.protocol.types.fetch.ResponseBody
import ai.platon.cdt.kt.protocol.types.input.*
import ai.platon.cdt.kt.protocol.types.network.Cookie
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourceOptions
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourcePageResult
import ai.platon.cdt.kt.protocol.types.page.*
import ai.platon.cdt.kt.protocol.types.runtime.CallArgument
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.cdt.kt.protocol.types.runtime.RemoteObject
import ai.platon.pulsar.browser.impl.BrowserProtocol

/**
 * The single implementation of [BrowserProtocol] that translates typed method
 * calls into string-keyed CDP commands dispatched through [RemoteDevTools.execute].
 *
 * No dynamic proxies, no javassist bytecode generation, no reflection-based
 * method dispatch — every method body is a straightforward
 * `devTools.execute("Domain.method", params, ReturnType::class)` call.
 */
class RemoteChromeProtocol(
    val devTools: RemoteDevTools
) : BrowserProtocol {

    private data class EmptyResult(val ignored: String? = null)

    override val isOpen: Boolean get() = devTools.isOpen

    // ---- Browser / Target liveness ----

    override suspend fun isBrowserAlive(): Boolean {
        return runCatching { devTools.execute("Browser.getVersion", null, EmptyResult::class) }.isSuccess
    }

    override suspend fun isTargetAlive(): Boolean {
        return runCatching { devTools.execute("Target.getTargets", null, EmptyResult::class) }.isSuccess
    }

    override suspend fun isV8Alive(): Boolean {
        return runCatching { devTools.execute("Runtime.evaluate", mapOf("expression" to "1+1"), Evaluate::class) }.isSuccess
    }

    // ---- Domain enable ----

    override suspend fun pageEnable() { devTools.execute("Page.enable", null, EmptyResult::class) }
    override suspend fun domEnable() { devTools.execute("DOM.enable", null, EmptyResult::class) }
    override suspend fun accessibilityEnable() { devTools.execute("Accessibility.enable", null, EmptyResult::class) }
    override suspend fun runtimeEnable() { devTools.execute("Runtime.enable", null, EmptyResult::class) }
    override suspend fun networkEnable() { devTools.execute("Network.enable", null, EmptyResult::class) }
    override suspend fun cssEnable() { devTools.execute("CSS.enable", null, EmptyResult::class) }
    override suspend fun fetchEnable() { devTools.execute("Fetch.enable", null, EmptyResult::class) }

    override suspend fun fetchEnable(patterns: List<RequestPattern>, handleAuthRequests: Boolean) {
        devTools.execute(
            "Fetch.enable",
            mapOf("patterns" to patterns, "handleAuthRequests" to handleAuthRequests),
            EmptyResult::class
        )
    }

    override suspend fun securityEnable() { devTools.execute("Security.enable", null, EmptyResult::class) }

    // ---- Page ----

    override suspend fun mainFrame(): Frame =
        devTools.execute("Page.getFrameTree", null, Frame::class, "frame")!!

    override suspend fun getFrameTree(): FrameTree =
        devTools.execute("Page.getFrameTree", null, FrameTree::class)!!

    override suspend fun reload() { devTools.execute("Page.reload", null, EmptyResult::class) }

    override suspend fun navigateToHistoryEntry(entryId: Int) {
        devTools.execute("Page.navigateToHistoryEntry", mapOf("entryId" to entryId), EmptyResult::class)
    }

    override suspend fun handleJavaScriptDialog(accept: Boolean, promptText: String?) {
        devTools.execute(
            "Page.handleJavaScriptDialog",
            mapOf("accept" to accept, "promptText" to promptText),
            EmptyResult::class
        )
    }

    override suspend fun bringToFront() { devTools.execute("Page.bringToFront", null, EmptyResult::class) }
    override suspend fun stopLoading() { devTools.execute("Page.stopLoading", null, EmptyResult::class) }

    override suspend fun addScriptToEvaluateOnNewDocument(script: String): String =
        devTools.execute("Page.addScriptToEvaluateOnNewDocument", mapOf("source" to script), String::class, "identifier")!!

    // ---- Page events ----

    override fun onDocumentOpened(handler: suspend (DocumentOpened) -> Unit): EventListener =
        devTools.addEventListener("Page", "documentOpened", EventHandler { handler(it as DocumentOpened) }, DocumentOpened::class.java)

    override fun onFrameNavigated(handler: suspend (FrameNavigated) -> Unit): EventListener =
        devTools.addEventListener("Page", "frameNavigated", EventHandler { handler(it as FrameNavigated) }, FrameNavigated::class.java)

    override fun onWindowOpen(handler: suspend (WindowOpen) -> Unit): EventListener =
        devTools.addEventListener("Page", "windowOpen", EventHandler { handler(it as WindowOpen) }, WindowOpen::class.java)

    // ---- Navigation ----

    override suspend fun navigate(url: String): Navigate =
        devTools.execute("Page.navigate", mapOf("url" to url), Navigate::class)!!

    override suspend fun navigate(
        url: String, referrer: String?, transitionType: TransitionType?,
        frameId: String?, referrerPolicy: ReferrerPolicy?
    ): Navigate = devTools.execute(
        "Page.navigate",
        mapOf(
            "url" to url, "referrer" to referrer, "transitionType" to transitionType,
            "frameId" to frameId, "referrerPolicy" to referrerPolicy
        ),
        Navigate::class
    )!!

    // ---- Runtime ----

    override suspend fun evaluate(
        expression: String, contextId: Int?, returnByValue: Boolean?, awaitPromise: Boolean?
    ): Evaluate = devTools.execute(
        "Runtime.evaluate",
        mapOf(
            "expression" to expression, "contextId" to contextId,
            "returnByValue" to returnByValue, "awaitPromise" to awaitPromise
        ),
        Evaluate::class
    )!!

    override suspend fun callFunctionOn(
        functionDeclaration: String, objectId: String?, arguments: List<CallArgument>?,
        silent: Boolean?, returnByValue: Boolean?, generatePreview: Boolean?,
        userGesture: Boolean?, awaitPromise: Boolean?, executionContextId: Int?,
        objectGroup: String?
    ): CallFunctionOn = devTools.execute(
        "Runtime.callFunctionOn",
        mapOf(
            "functionDeclaration" to functionDeclaration, "objectId" to objectId,
            "arguments" to arguments, "silent" to silent, "returnByValue" to returnByValue,
            "generatePreview" to generatePreview, "userGesture" to userGesture,
            "awaitPromise" to awaitPromise, "executionContextId" to executionContextId,
            "objectGroup" to objectGroup
        ),
        CallFunctionOn::class
    )!!

    override suspend fun releaseObject(objectId: String) {
        devTools.execute("Runtime.releaseObject", mapOf("objectId" to objectId), EmptyResult::class)
    }

    // ---- Page layout / history ----

    override suspend fun getLayoutMetrics(): LayoutMetrics =
        devTools.execute("Page.getLayoutMetrics", null, LayoutMetrics::class)!!

    override suspend fun getNavigationHistory(): NavigationHistory =
        devTools.execute("Page.getNavigationHistory", null, NavigationHistory::class)!!

    override suspend fun createIsolatedWorld(frameId: String, worldName: String, grantUniveralAccess: Boolean): Int =
        devTools.execute(
            "Page.createIsolatedWorld",
            mapOf("frameId" to frameId, "worldName" to worldName, "grantUniveralAccess" to grantUniveralAccess),
            Int::class,
            "executionContextId"
        )!!

    override suspend fun captureScreenshot(
        format: CaptureScreenshotFormat?, quality: Int?, clip: Viewport?,
        fromSurface: Boolean?, captureBeyondViewport: Boolean?
    ): String = devTools.execute(
        "Page.captureScreenshot",
        mapOf(
            "format" to format, "quality" to quality, "clip" to clip,
            "fromSurface" to fromSurface, "captureBeyondViewport" to captureBeyondViewport
        ),
        String::class,
        "data"
    )!!

    // ---- Emulation ----

    override suspend fun setDeviceMetricsOverride(
        mobile: Boolean, width: Int, height: Int, deviceScaleFactor: Double,
        screenWidth: Int?, screenHeight: Int?
    ) {
        devTools.execute(
            "Emulation.setDeviceMetricsOverride",
            mapOf(
                "mobile" to mobile, "width" to width, "height" to height,
                "deviceScaleFactor" to deviceScaleFactor,
                "screenWidth" to screenWidth, "screenHeight" to screenHeight
            ),
            EmptyResult::class
        )
    }

    override suspend fun clearDeviceMetricsOverride() {
        devTools.execute("Emulation.clearDeviceMetricsOverride", null, EmptyResult::class)
    }

    // ---- DOM ----

    override suspend fun getDocument(depth: Int?, pierce: Boolean?): Node =
        devTools.execute("DOM.getDocument", mapOf("depth" to depth, "pierce" to pierce), Node::class, "root")!!

    @Suppress("UNCHECKED_CAST")
    override suspend fun getContentQuads(nodeId: Int): List<List<Double>> =
        devTools.execute("DOM.getContentQuads", mapOf("nodeId" to nodeId), List::class, "quads", arrayOf(List::class.java, Double::class.javaObjectType)) as List<List<Double>>

    override suspend fun getBoxModel(nodeId: Int): BoxModel =
        devTools.execute("DOM.getBoxModel", mapOf("nodeId" to nodeId), BoxModel::class, "model")!!

    override suspend fun querySelector(nodeId: Int, selector: String): Int =
        devTools.execute("DOM.querySelector", mapOf("nodeId" to nodeId, "selector" to selector), Int::class, "nodeId")!!

    @Suppress("UNCHECKED_CAST")
    override suspend fun querySelectorAll(nodeId: Int, selector: String): List<Int> =
        devTools.execute("DOM.querySelectorAll", mapOf("nodeId" to nodeId, "selector" to selector), List::class, "nodeIds", arrayOf(Int::class.javaObjectType)) as List<Int>

    override suspend fun performSearch(query: String, includeUserAgentShadowDOM: Boolean?): PerformSearch =
        devTools.execute("DOM.performSearch", mapOf("query" to query, "includeUserAgentShadowDOM" to includeUserAgentShadowDOM), PerformSearch::class)!!

    @Suppress("UNCHECKED_CAST")
    override suspend fun getSearchResults(searchId: String, fromIndex: Int, toIndex: Int): List<Int> =
        devTools.execute("DOM.getSearchResults", mapOf("searchId" to searchId, "fromIndex" to fromIndex, "toIndex" to toIndex), List::class, "nodeIds", arrayOf(Int::class.javaObjectType)) as List<Int>

    override suspend fun discardSearchResults(searchId: String) {
        devTools.execute("DOM.discardSearchResults", mapOf("searchId" to searchId), EmptyResult::class)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getAttributes(nodeId: Int): List<String> =
        devTools.execute("DOM.getAttributes", mapOf("nodeId" to nodeId), List::class, "attributes", arrayOf(String::class.java)) as List<String>

    override suspend fun focus(nodeId: Int) {
        devTools.execute("DOM.focus", mapOf("nodeId" to nodeId), EmptyResult::class)
    }

    override suspend fun describeNode(
        nodeId: Int?, backendNodeId: Int?, objectId: String?, depth: Int?, pierce: Boolean?
    ): Node = devTools.execute(
        "DOM.describeNode",
        mapOf(
            "nodeId" to nodeId, "backendNodeId" to backendNodeId,
            "objectId" to objectId, "depth" to depth, "pierce" to pierce
        ),
        Node::class,
        "node"
    )!!

    override suspend fun scrollIntoViewIfNeeded(
        nodeId: Int, backendNodeId: Int?, objectId: String?, rect: Rect?
    ) {
        devTools.execute(
            "DOM.scrollIntoViewIfNeeded",
            mapOf("nodeId" to nodeId, "backendNodeId" to backendNodeId, "objectId" to objectId, "rect" to rect),
            EmptyResult::class
        )
    }

    override suspend fun resolveNodeByNodeId(nodeId: Int): RemoteObject =
        devTools.execute("DOM.resolveNode", mapOf("nodeId" to nodeId), RemoteObject::class, "object")!!

    override suspend fun resolveNodeByBackendNodeId(backendNodeId: Int): RemoteObject =
        devTools.execute("DOM.resolveNode", mapOf("backendNodeId" to backendNodeId), RemoteObject::class, "object")!!

    override suspend fun requestNode(objectId: String): Int =
        devTools.execute("DOM.requestNode", mapOf("objectId" to objectId), Int::class, "nodeId")!!

    // ---- CSS ----

    @Suppress("UNCHECKED_CAST")
    override suspend fun getComputedStyleForNode(nodeId: Int): List<CSSComputedStyleProperty> =
        devTools.execute("CSS.getComputedStyleForNode", mapOf("nodeId" to nodeId), List::class, "computedStyle", arrayOf(CSSComputedStyleProperty::class.java)) as List<CSSComputedStyleProperty>

    // ---- Accessibility ----

    @Suppress("UNCHECKED_CAST")
    override suspend fun getFullAXTree(depth: Int?): List<AXNode> =
        devTools.execute("Accessibility.getFullAXTree", mapOf("depth" to depth), List::class, "nodes", arrayOf(AXNode::class.java)) as List<AXNode>

    // ---- Network ----

    override suspend fun clearBrowserCookies() { devTools.execute("Network.clearBrowserCookies", null, EmptyResult::class) }
    override suspend fun setBlockedURLs(urls: List<String>) {
        devTools.execute("Network.setBlockedURLs", mapOf("urls" to urls), EmptyResult::class)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getCookies(): List<Cookie> =
        devTools.execute("Network.getCookies", null, List::class, "cookies", arrayOf(Cookie::class.java)) as List<Cookie>

    override suspend fun deleteCookies(name: String, url: String?, domain: String?, path: String?) {
        devTools.execute(
            "Network.deleteCookies",
            mapOf("name" to name, "url" to url, "domain" to domain, "path" to path),
            EmptyResult::class
        )
    }

    override suspend fun loadNetworkResource(
        frameId: String, url: String, options: LoadNetworkResourceOptions
    ): LoadNetworkResourcePageResult =
        devTools.execute("Network.loadNetworkResource", mapOf("frameId" to frameId, "url" to url, "options" to options), LoadNetworkResourcePageResult::class, "resource")!!

    // ---- Fetch ----

    override suspend fun failRequest(requestId: String, errorReason: ErrorReason) {
        devTools.execute("Fetch.failRequest", mapOf("requestId" to requestId, "errorReason" to errorReason), EmptyResult::class)
    }

    override suspend fun getResponseBody(requestId: String): ResponseBody =
        devTools.execute("Fetch.getResponseBody", mapOf("requestId" to requestId), ResponseBody::class)!!

    override suspend fun setFileInputFiles(files: List<String>, nodeId: Int) {
        devTools.execute("DOM.setFileInputFiles", mapOf("files" to files, "nodeId" to nodeId), EmptyResult::class)
    }

    override suspend fun getOuterHTML(nodeId: Int, backendNodeId: Int, objectId: String?): String =
        devTools.execute("DOM.getOuterHTML", mapOf("nodeId" to nodeId, "backendNodeId" to backendNodeId, "objectId" to objectId), String::class, "outerHTML")!!

    // ---- Input ----

    override fun onDragIntercepted(handler: (DragIntercepted) -> Unit): EventListener =
        devTools.addEventListener("Input", "dragIntercepted", EventHandler { handler(it as DragIntercepted) }, DragIntercepted::class.java)

    override suspend fun dispatchMouseMoved(x: Double, y: Double, buttons: Int?) {
        devTools.execute(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to DispatchMouseEventType.MOUSE_MOVED, "x" to x, "y" to y,
                "buttons" to buttons
            ),
            EmptyResult::class
        )
    }

    override suspend fun dispatchMousePressed(
        x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int
    ) {
        devTools.execute(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to DispatchMouseEventType.MOUSE_PRESSED, "x" to x, "y" to y,
                "button" to MouseButton.LEFT, "modifiers" to modifiers,
                "buttons" to buttons, "clickCount" to clickCount,
                "force" to 0.5
            ),
            EmptyResult::class
        )
    }

    override suspend fun dispatchMouseReleased(
        x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int
    ) {
        devTools.execute(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to DispatchMouseEventType.MOUSE_RELEASED, "x" to x, "y" to y,
                "button" to MouseButton.LEFT, "clickCount" to clickCount,
                "modifiers" to modifiers, "buttons" to buttons
            ),
            EmptyResult::class
        )
    }

    override suspend fun dispatchMouseWheel(x: Double, y: Double, deltaX: Double, deltaY: Double) {
        devTools.execute(
            "Input.dispatchMouseEvent",
            mapOf(
                "type" to DispatchMouseEventType.MOUSE_WHEEL, "x" to x, "y" to y,
                "deltaX" to deltaX, "deltaY" to deltaY
            ),
            EmptyResult::class
        )
    }

    override suspend fun setInterceptDrags(enabled: Boolean) {
        devTools.execute("Input.setInterceptDrags", mapOf("enabled" to enabled), EmptyResult::class)
    }

    override suspend fun dispatchDragEvent(type: DispatchDragEventType, x: Double, y: Double, data: DragData) {
        devTools.execute("Input.dispatchDragEvent", mapOf("type" to type, "x" to x, "y" to y, "data" to data), EmptyResult::class)
    }

    override suspend fun insertText(text: String) {
        devTools.execute("Input.insertText", mapOf("text" to text), EmptyResult::class)
    }

    override suspend fun dispatchKeyEvent(
        type: DispatchKeyEventType, modifiers: Int?, windowsVirtualKeyCode: Int?,
        code: String?, commands: List<String>?, key: String?, text: String?,
        unmodifiedText: String?, location: Int?, isKeypad: Boolean?, autoRepeat: Boolean?
    ) {
        devTools.execute(
            "Input.dispatchKeyEvent",
            mapOf(
                "type" to type, "modifiers" to modifiers,
                "windowsVirtualKeyCode" to windowsVirtualKeyCode, "code" to code,
                "commands" to commands, "key" to key, "text" to text,
                "unmodifiedText" to unmodifiedText, "location" to location,
                "isKeypad" to isKeypad, "autoRepeat" to autoRepeat
            ),
            EmptyResult::class
        )
    }

    // ---- DOMSnapshot ----

    override suspend fun domSnapshotCaptureSnapshot(
        computedStyles: List<String>, includePaintOrder: Boolean?,
        includeDOMRects: Boolean?, includeBlendedBackgroundColors: Boolean?,
        includeTextColorOpacities: Boolean?
    ): CaptureSnapshot = devTools.execute(
        "DOMSnapshot.captureSnapshot",
        mapOf(
            "computedStyles" to computedStyles, "includePaintOrder" to includePaintOrder,
            "includeDOMRects" to includeDOMRects,
            "includeBlendedBackgroundColors" to includeBlendedBackgroundColors,
            "includeTextColorOpacities" to includeTextColorOpacities
        ),
        CaptureSnapshot::class
    )!!

    // ---- Page misc ----

    override suspend fun reloadPage(ignoreCache: Boolean?, scriptToEvaluateOnLoad: String?) {
        devTools.execute(
            "Page.reload",
            mapOf("ignoreCache" to ignoreCache, "scriptToEvaluateOnLoad" to scriptToEvaluateOnLoad),
            EmptyResult::class
        )
    }

    override suspend fun setCookies(cookies: List<Map<String, Any?>>) {
        devTools.execute("Network.setCookies", mapOf("cookies" to cookies), EmptyResult::class)
    }

    override suspend fun setExtraHTTPHeaders(headers: Map<String, Any>) {
        devTools.execute("Network.setExtraHTTPHeaders", mapOf("headers" to headers), EmptyResult::class)
    }

    override suspend fun setCacheDisabled(cacheDisabled: Boolean) {
        devTools.execute("Network.setCacheDisabled", mapOf("cacheDisabled" to cacheDisabled), EmptyResult::class)
    }

    // ---- Network events ----

    override fun onRequestWillBeSent(handler: suspend (RequestWillBeSent) -> Unit): EventListener =
        devTools.addEventListener("Network", "requestWillBeSent", EventHandler { handler(it as RequestWillBeSent) }, RequestWillBeSent::class.java)

    override fun onRequestWillBeSentExtraInfo(handler: suspend (RequestWillBeSentExtraInfo) -> Unit): EventListener =
        devTools.addEventListener("Network", "requestWillBeSentExtraInfo", EventHandler { handler(it as RequestWillBeSentExtraInfo) }, RequestWillBeSentExtraInfo::class.java)

    override fun onRequestServedFromCache(handler: suspend (RequestServedFromCache) -> Unit): EventListener =
        devTools.addEventListener("Network", "requestServedFromCache", EventHandler { handler(it as RequestServedFromCache) }, RequestServedFromCache::class.java)

    override fun onResponseReceived(handler: suspend (ResponseReceived) -> Unit): EventListener =
        devTools.addEventListener("Network", "responseReceived", EventHandler { handler(it as ResponseReceived) }, ResponseReceived::class.java)

    override fun onResponseReceivedExtraInfo(handler: suspend (ResponseReceivedExtraInfo) -> Unit): EventListener =
        devTools.addEventListener("Network", "responseReceivedExtraInfo", EventHandler { handler(it as ResponseReceivedExtraInfo) }, ResponseReceivedExtraInfo::class.java)

    override fun onLoadingFinished(handler: suspend (LoadingFinished) -> Unit): EventListener =
        devTools.addEventListener("Network", "loadingFinished", EventHandler { handler(it as LoadingFinished) }, LoadingFinished::class.java)

    override fun onLoadingFailed(handler: suspend (LoadingFailed) -> Unit): EventListener =
        devTools.addEventListener("Network", "loadingFailed", EventHandler { handler(it as LoadingFailed) }, LoadingFailed::class.java)

    // ---- Fetch domain ----

    override suspend fun continueRequest(
        requestId: String, url: String?, method: String?,
        postData: String?, headers: List<HeaderEntry>?
    ) {
        devTools.execute(
            "Fetch.continueRequest",
            mapOf("requestId" to requestId, "url" to url, "method" to method,
                "postData" to postData, "headers" to headers),
            EmptyResult::class
        )
    }

    override suspend fun continueWithAuth(requestId: String, authChallengeResponse: AuthChallengeResponse) {
        devTools.execute(
            "Fetch.continueWithAuth",
            mapOf("requestId" to requestId, "authChallengeResponse" to authChallengeResponse),
            EmptyResult::class
        )
    }

    override suspend fun fulfillRequest(
        requestId: String, responseCode: Int, responseHeaders: List<HeaderEntry>?,
        binaryResponseHeaders: String?, body: String?, responsePhrase: String?
    ) {
        devTools.execute(
            "Fetch.fulfillRequest",
            mapOf(
                "requestId" to requestId, "responseCode" to responseCode,
                "responseHeaders" to responseHeaders,
                "binaryResponseHeaders" to binaryResponseHeaders,
                "body" to body, "responsePhrase" to responsePhrase
            ),
            EmptyResult::class
        )
    }

    override fun onRequestPaused(handler: suspend (RequestPaused) -> Unit): EventListener =
        devTools.addEventListener("Fetch", "requestPaused", EventHandler { handler(it as RequestPaused) }, RequestPaused::class.java)

    override fun onAuthRequired(handler: suspend (AuthRequired) -> Unit): EventListener =
        devTools.addEventListener("Fetch", "authRequired", EventHandler { handler(it as AuthRequired) }, AuthRequired::class.java)

    // ---- Security ----

    override suspend fun setIgnoreCertificateErrors(ignore: Boolean) {
        devTools.execute("Security.setIgnoreCertificateErrors", mapOf("ignore" to ignore), EmptyResult::class)
    }

    // ---- Console events ----

    override fun onConsoleMessageAdded(handler: suspend (MessageAdded) -> Unit): EventListener =
        devTools.addEventListener("Console", "messageAdded", EventHandler { handler(it as MessageAdded) }, MessageAdded::class.java)

    // ---- Lifecycle ----

    override fun awaitTermination() = devTools.awaitTermination()
    override fun close() = devTools.close()
}
