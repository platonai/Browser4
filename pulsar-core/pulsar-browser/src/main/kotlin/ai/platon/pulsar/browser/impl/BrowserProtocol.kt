package ai.platon.pulsar.browser.impl

import ai.platon.cdt.kt.protocol.events.console.MessageAdded
import ai.platon.cdt.kt.protocol.events.fetch.AuthRequired
import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.events.input.DragIntercepted
import ai.platon.cdt.kt.protocol.events.network.*
import ai.platon.cdt.kt.protocol.events.page.DocumentOpened
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.events.page.WindowOpen
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
import ai.platon.cdt.kt.protocol.types.input.DispatchDragEventType
import ai.platon.cdt.kt.protocol.types.input.DispatchKeyEventType
import ai.platon.cdt.kt.protocol.types.input.DragData
import ai.platon.cdt.kt.protocol.types.network.Cookie
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourceOptions
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourcePageResult
import ai.platon.cdt.kt.protocol.types.page.*
import ai.platon.cdt.kt.protocol.types.runtime.CallArgument
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.cdt.kt.protocol.types.runtime.RemoteObject

interface BrowserProtocol {
    val isOpen: Boolean

    suspend fun isBrowserAlive(): Boolean
    suspend fun isTargetAlive(): Boolean
    suspend fun isV8Alive(): Boolean

    /** Returns the main frame, suspending until the frame tree is available. */
    suspend fun mainFrame(): Frame

    suspend fun pageEnable(): Unit
    suspend fun domEnable(): Unit
    suspend fun accessibilityEnable(): Unit
    suspend fun runtimeEnable(): Unit
    suspend fun networkEnable(): Unit
    suspend fun cssEnable(): Unit
    suspend fun fetchEnable(): Unit
    suspend fun fetchEnable(patterns: List<RequestPattern>, handleAuthRequests: Boolean): Unit
    suspend fun securityEnable()

    suspend fun getFrameTree(): FrameTree

    suspend fun reload(): Unit
    suspend fun navigateToHistoryEntry(entryId: Int): Unit
    suspend fun handleJavaScriptDialog(accept: Boolean, promptText: String? = null): Unit
    suspend fun bringToFront(): Unit
    suspend fun stopLoading(): Unit
    suspend fun addScriptToEvaluateOnNewDocument(script: String): String

    fun onDocumentOpened(handler: suspend (DocumentOpened) -> Unit): EventListener
    fun onFrameNavigated(handler: suspend (FrameNavigated) -> Unit): EventListener
    fun onWindowOpen(handler: suspend (WindowOpen) -> Unit): EventListener

    suspend fun navigate(url: String): Navigate

    suspend fun navigate(
        url: String,
        referrer: String? = null,
        transitionType: TransitionType? = null,
        frameId: String? = null,
        referrerPolicy: ReferrerPolicy? = null,
    ): Navigate

    suspend fun evaluate(
        expression: String,
        contextId: Int? = null,
        returnByValue: Boolean? = null,
        awaitPromise: Boolean? = null,
    ): Evaluate

    suspend fun callFunctionOn(
        functionDeclaration: String,
        objectId: String? = null,
        arguments: List<CallArgument>? = null,
        silent: Boolean? = null,
        returnByValue: Boolean? = null,
        generatePreview: Boolean? = null,
        userGesture: Boolean? = null,
        awaitPromise: Boolean? = null,
        executionContextId: Int? = null,
        objectGroup: String? = null,
    ): CallFunctionOn

    suspend fun releaseObject(objectId: String): Unit

    suspend fun getLayoutMetrics(): LayoutMetrics

    suspend fun getNavigationHistory(): NavigationHistory

    suspend fun createIsolatedWorld(frameId: String, worldName: String, grantUniveralAccess: Boolean = true): Int

    suspend fun captureScreenshot(
        format: CaptureScreenshotFormat? = null,
        quality: Int? = null,
        clip: Viewport? = null,
        fromSurface: Boolean? = null,
        captureBeyondViewport: Boolean? = null,
    ): String

    suspend fun setDeviceMetricsOverride(
        mobile: Boolean,
        width: Int,
        height: Int,
        deviceScaleFactor: Double,
        screenWidth: Int? = null,
        screenHeight: Int? = null,
    ): Unit

    suspend fun clearDeviceMetricsOverride(): Unit

    suspend fun getDocument(depth: Int? = null, pierce: Boolean? = null): Node

    suspend fun getContentQuads(nodeId: Int): List<List<Double>>

    suspend fun getBoxModel(nodeId: Int): BoxModel

    suspend fun querySelector(nodeId: Int, selector: String): Int

    suspend fun querySelectorAll(nodeId: Int, selector: String): List<Int>

    suspend fun performSearch(query: String, includeUserAgentShadowDOM: Boolean? = null): PerformSearch

    suspend fun getSearchResults(searchId: String, fromIndex: Int, toIndex: Int): List<Int>

    suspend fun discardSearchResults(searchId: String): Unit

    suspend fun getAttributes(nodeId: Int): List<String>

    suspend fun focus(nodeId: Int): Unit

    suspend fun describeNode(
        nodeId: Int? = null,
        backendNodeId: Int? = null,
        objectId: String? = null,
        depth: Int? = null,
        pierce: Boolean? = null,
    ): Node

    suspend fun scrollIntoViewIfNeeded(nodeId: Int, backendNodeId: Int? = null, objectId: String? = null, rect: Rect? = null): Unit

    suspend fun resolveNodeByNodeId(nodeId: Int): RemoteObject

    suspend fun resolveNodeByBackendNodeId(backendNodeId: Int): RemoteObject

    suspend fun requestNode(objectId: String): Int

    suspend fun getComputedStyleForNode(nodeId: Int): List<CSSComputedStyleProperty>

    suspend fun getFullAXTree(depth: Int? = null): List<AXNode>

    suspend fun clearBrowserCookies(): Unit
    suspend fun setBlockedURLs(urls: List<String>): Unit
    suspend fun getCookies(): List<Cookie>
    suspend fun deleteCookies(name: String, url: String? = null, domain: String? = null, path: String? = null): Unit
    suspend fun loadNetworkResource(frameId: String, url: String, options: LoadNetworkResourceOptions): LoadNetworkResourcePageResult

    suspend fun failRequest(requestId: String, errorReason: ErrorReason): Unit
    suspend fun getResponseBody(requestId: String): ResponseBody

    suspend fun setFileInputFiles(files: List<String>, nodeId: Int): Unit
    suspend fun getOuterHTML(nodeId: Int, backendNodeId: Int, objectId: String? = null): String

    fun onDragIntercepted(handler: (DragIntercepted) -> Unit): EventListener

    suspend fun dispatchMouseMoved(x: Double, y: Double, buttons: Int?): Unit
    suspend fun dispatchMousePressed(x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int): Unit
    suspend fun dispatchMouseReleased(x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int): Unit
    suspend fun dispatchMouseWheel(x: Double, y: Double, deltaX: Double, deltaY: Double): Unit

    suspend fun setInterceptDrags(enabled: Boolean): Unit

    suspend fun dispatchDragEvent(type: DispatchDragEventType, x: Double, y: Double, data: DragData): Unit

    suspend fun insertText(text: String): Unit

    suspend fun dispatchKeyEvent(
        type: DispatchKeyEventType,
        modifiers: Int? = null,
        windowsVirtualKeyCode: Int? = null,
        code: String? = null,
        commands: List<String>? = null,
        key: String? = null,
        text: String? = null,
        unmodifiedText: String? = null,
        location: Int? = null,
        isKeypad: Boolean? = null,
        autoRepeat: Boolean? = null,
    ): Unit

    suspend fun domSnapshotCaptureSnapshot(
        computedStyles: List<String>,
        includePaintOrder: Boolean? = null,
        includeDOMRects: Boolean? = null,
        includeBlendedBackgroundColors: Boolean? = null,
        includeTextColorOpacities: Boolean? = null,
    ): CaptureSnapshot

    suspend fun reloadPage(ignoreCache: Boolean? = null, scriptToEvaluateOnLoad: String? = null)

    suspend fun setCookies(cookies: List<Map<String, Any?>>)

    suspend fun setExtraHTTPHeaders(headers: Map<String, Any>)

    suspend fun setCacheDisabled(cacheDisabled: Boolean)

    fun onRequestWillBeSent(handler: suspend (RequestWillBeSent) -> Unit): EventListener

    fun onRequestWillBeSentExtraInfo(handler: suspend (RequestWillBeSentExtraInfo) -> Unit): EventListener

    fun onRequestServedFromCache(handler: suspend (RequestServedFromCache) -> Unit): EventListener

    fun onResponseReceived(handler: suspend (ResponseReceived) -> Unit): EventListener

    fun onResponseReceivedExtraInfo(handler: suspend (ResponseReceivedExtraInfo) -> Unit): EventListener

    fun onLoadingFinished(handler: suspend (LoadingFinished) -> Unit): EventListener

    fun onLoadingFailed(handler: suspend (LoadingFailed) -> Unit): EventListener

    suspend fun continueRequest(
        requestId: String,
        url: String? = null,
        method: String? = null,
        postData: String? = null,
        headers: List<HeaderEntry>? = null,
    )

    suspend fun continueWithAuth(requestId: String, authChallengeResponse: AuthChallengeResponse)

    suspend fun fulfillRequest(
        requestId: String,
        responseCode: Int,
        responseHeaders: List<HeaderEntry>? = null,
        binaryResponseHeaders: String? = null,
        body: String? = null,
        responsePhrase: String? = null,
    )

    fun onRequestPaused(handler: suspend (RequestPaused) -> Unit): EventListener

    fun onAuthRequired(handler: suspend (AuthRequired) -> Unit): EventListener

    suspend fun setIgnoreCertificateErrors(ignore: Boolean)

    fun onConsoleMessageAdded(handler: suspend (MessageAdded) -> Unit): EventListener

    fun awaitTermination(): Unit

    fun close(): Unit
}
