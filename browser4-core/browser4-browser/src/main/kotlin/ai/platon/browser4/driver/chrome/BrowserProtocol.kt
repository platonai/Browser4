package ai.platon.browser4.driver.chrome

import ai.platon.cdt.kt.protocol.ChromeDevTools
import ai.platon.cdt.kt.protocol.events.console.MessageAdded
import ai.platon.cdt.kt.protocol.events.dom.AttributeModified
import ai.platon.cdt.kt.protocol.events.fetch.AuthRequired
import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.events.input.DragIntercepted
import ai.platon.cdt.kt.protocol.events.network.*
import ai.platon.cdt.kt.protocol.events.page.DocumentOpened
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.events.page.WindowOpen
import ai.platon.cdt.kt.protocol.support.annotations.ParamName
import ai.platon.cdt.kt.protocol.types.dom.Rect
import ai.platon.cdt.kt.protocol.types.domsnapshot.CaptureSnapshot
import ai.platon.cdt.kt.protocol.types.fetch.AuthChallengeResponse
import ai.platon.cdt.kt.protocol.types.fetch.HeaderEntry
import ai.platon.cdt.kt.protocol.types.fetch.RequestPattern
import ai.platon.cdt.kt.protocol.types.input.*
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourceOptions
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourcePageResult
import ai.platon.cdt.kt.protocol.types.page.*
import ai.platon.cdt.kt.protocol.types.runtime.CallArgument
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate

/**
 * BrowserProtocol is the single access point for all Chrome DevTools Protocol (BrowserProtocol) domain APIs.
 *
 * All direct usage of [ai.platon.cdt.kt.protocol.ChromeDevTools] should go through this class to improve
 * maintainability and provide a consistent, centralized interface.
 */
class BrowserProtocol(
    private val devTools: ChromeDevTools
) {
    private data class EmptyResult(val ignored: String? = null)

    val remoteDevToolsOrNull: RemoteDevTools? get() = devTools as? RemoteDevTools
    val isOpen: Boolean get() = remoteDevToolsOrNull?.isOpen ?: false

    val browser get() = devTools.browser
    val target get() = devTools.target
    val page get() = devTools.page
    val runtime get() = devTools.runtime
    private val dom get() = devTools.dom
    private val console get() = devTools.console
    private val css get() = devTools.css
    private val input get() = devTools.input
    private val network get() = devTools.network
    private val fetch get() = devTools.fetch
    private val security get() = devTools.security
    private val emulation get() = devTools.emulation
    private val accessibility get() = devTools.accessibility
    private val domSnapshot get() = devTools.domSnapshot

    suspend fun isBrowserAlive(): Boolean {
        return runCatching { browser.getVersion() }.isSuccess
    }

    suspend fun isTargetAlive(): Boolean {
        return runCatching { target.getTargets() }.isSuccess
    }

    suspend fun isV8Alive(): Boolean {
        return runCatching { runtime.evaluate("1+1") }.isSuccess
    }

    /** Returns the main frame, suspending until the frame tree is available. */
    suspend fun mainFrame() = page.getFrameTree().frame

    suspend fun pageEnable() = page.enable()
    suspend fun domEnable() = dom.enable()
    suspend fun runtimeEnable() = runtime.enable()
    suspend fun networkEnable() = network.enable()
    suspend fun cssEnable() = css.enable()
    suspend fun consoleEnable() = console.enable()
    suspend fun fetchEnable(patterns: List<RequestPattern>? = null, handleAuthRequests: Boolean? = null) =
        fetch.enable(patterns, handleAuthRequests)
    suspend fun securityEnable() = security.enable()
    suspend fun accessibilityEnable() = accessibility.enable()
    suspend fun getFrameTree() = page.getFrameTree()

    suspend fun navigate(url: String): Navigate = page.navigate(url)

    suspend fun navigate(
        url: String,
        referrer: String? = null,
        transitionType: TransitionType? = null,
        frameId: String? = null,
        referrerPolicy: ReferrerPolicy? = null,
    ): Navigate = page.navigate(url, referrer, transitionType, frameId, referrerPolicy)

    suspend fun evaluate(
        expression: String,
        contextId: Int? = null,
        returnByValue: Boolean? = null,
        awaitPromise: Boolean? = null,
    ): Evaluate {
        return runtime.evaluate(
            expression = expression,
            contextId = contextId,
            returnByValue = returnByValue,
            awaitPromise = awaitPromise,
        )
    }

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
    ): CallFunctionOn {
        return runtime.callFunctionOn(
            functionDeclaration = functionDeclaration,
            objectId = objectId,
            arguments = arguments,
            silent = silent,
            returnByValue = returnByValue,
            generatePreview = generatePreview,
            userGesture = userGesture,
            awaitPromise = awaitPromise,
            executionContextId = executionContextId,
            objectGroup = objectGroup
        )
    }

    suspend fun releaseObject(objectId: String) = runtime.releaseObject(objectId)

    suspend fun getLayoutMetrics() = page.getLayoutMetrics()

    suspend fun getNavigationHistory() = page.getNavigationHistory()

    suspend fun reloadPage(ignoreCache: Boolean? = null, scriptToEvaluateOnLoad: String? = null) =
        page.reload(ignoreCache, scriptToEvaluateOnLoad)

    suspend fun navigateToHistoryEntry(entryId: Int) = page.navigateToHistoryEntry(entryId)

    suspend fun bringToFront() = page.bringToFront()

    suspend fun stopLoading() = page.stopLoading()

    suspend fun handleJavaScriptDialog(accept: Boolean, promptText: String? = null) =
        page.handleJavaScriptDialog(accept = accept, promptText = promptText)

    suspend fun addScriptToEvaluateOnNewDocument(source: String) =
        page.addScriptToEvaluateOnNewDocument(source)

    fun onFrameNavigated(handler: suspend (FrameNavigated) -> Unit) = page.onFrameNavigated(handler)

    fun onDocumentOpened(handler: suspend (DocumentOpened) -> Unit) = page.onDocumentOpened(handler)

    fun onWindowOpen(handler: suspend (WindowOpen) -> Unit) = page.onWindowOpen(handler)

    suspend fun createIsolatedWorld(frameId: String, worldName: String, grantUniveralAccess: Boolean = true): Int {
        return page.createIsolatedWorld(
            frameId = frameId,
            worldName = worldName,
            grantUniveralAccess = grantUniveralAccess,
        )
    }

    suspend fun captureScreenshot(
        format: CaptureScreenshotFormat? = null,
        quality: Int? = null,
        clip: Viewport? = null,
        fromSurface: Boolean? = null,
        captureBeyondViewport: Boolean? = null,
    ) = page.captureScreenshot(
        format = format,
        quality = quality,
        clip = clip,
        fromSurface = fromSurface,
        captureBeyondViewport = captureBeyondViewport,
    )

    suspend fun setDeviceMetricsOverride(
        mobile: Boolean,
        width: Int,
        height: Int,
        deviceScaleFactor: Double,
        screenWidth: Int? = null,
        screenHeight: Int? = null,
    ) {
        emulation.setDeviceMetricsOverride(
            mobile = mobile,
            width = width,
            height = height,
            deviceScaleFactor = deviceScaleFactor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
    }

    suspend fun clearDeviceMetricsOverride() = emulation.clearDeviceMetricsOverride()

    suspend fun getDocument(depth: Int? = null, pierce: Boolean? = null) = dom.getDocument(depth, pierce)

    suspend fun getOuterHTML(
        nodeId: Int? = null,
        backendNodeId: Int? = null,
        objectId: String? = null,
    ): String = dom.getOuterHTML(nodeId, backendNodeId, objectId)

    suspend fun getContentQuads(nodeId: Int) = dom.getContentQuads(nodeId)

    suspend fun getBoxModel(nodeId: Int) = dom.getBoxModel(nodeId, null, null)

    suspend fun querySelector(nodeId: Int, selector: String) = dom.querySelector(nodeId, selector)

    suspend fun querySelectorAll(nodeId: Int, selector: String) = dom.querySelectorAll(nodeId, selector)

    suspend fun performSearch(query: String, includeUserAgentShadowDOM: Boolean? = null) =
        dom.performSearch(query, includeUserAgentShadowDOM)

    suspend fun getSearchResults(searchId: String, fromIndex: Int, toIndex: Int) =
        dom.getSearchResults(searchId, fromIndex, toIndex)

    suspend fun discardSearchResults(searchId: String) = dom.discardSearchResults(searchId)

    suspend fun getAttributes(nodeId: Int) = dom.getAttributes(nodeId)

    fun onAttributeModified(handler: suspend (AttributeModified) -> Unit) = dom.onAttributeModified(handler)

    suspend fun focus(nodeId: Int) = dom.focus(nodeId)

    suspend fun describeNode(
        nodeId: Int? = null,
        backendNodeId: Int? = null,
        objectId: String? = null,
        depth: Int? = null,
        pierce: Boolean? = null,
    ) = dom.describeNode(nodeId, backendNodeId, objectId, depth, pierce)

    suspend fun scrollIntoViewIfNeeded(nodeId: Int, rect: Rect? = null) =
        dom.scrollIntoViewIfNeeded(nodeId, rect = rect)

    suspend fun resolveNodeByNodeId(nodeId: Int) = dom.resolveNode(nodeId = nodeId)

    suspend fun resolveNodeByBackendNodeId(backendNodeId: Int) = dom.resolveNode(backendNodeId = backendNodeId)

    suspend fun requestNode(objectId: String) = dom.requestNode(objectId)

    suspend fun setFileInputFiles(
        files: List<String>,
        nodeId: Int? = null,
        backendNodeId: Int? = null,
        objectId: String? = null,
    ) = dom.setFileInputFiles(files, nodeId, backendNodeId, objectId)

    suspend fun getComputedStyleForNode(nodeId: Int) = css.getComputedStyleForNode(nodeId)

    suspend fun getFullAXTree(depth: Int? = null) = accessibility.getFullAXTree(depth)

    suspend fun dispatchMouseMoved(x: Double, y: Double, buttons: Int?) {
        input.dispatchMouseEvent(
            type = DispatchMouseEventType.MOUSE_MOVED,
            x = x,
            y = y,
            modifiers = null,
            timestamp = null,
            button = null,
            buttons = buttons,
            clickCount = null,
            force = null,
            tangentialPressure = null,
            tiltX = null,
            tiltY = null,
            twist = null,
            deltaX = null,
            deltaY = null,
            pointerType = null,
        )
    }

    suspend fun dispatchMousePressed(x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int) {
        input.dispatchMouseEvent(
            type = DispatchMouseEventType.MOUSE_PRESSED,
            x = x,
            y = y,
            button = MouseButton.LEFT,
            modifiers = modifiers,
            timestamp = null,
            buttons = buttons,
            clickCount = clickCount,
            force = 0.5,
            tangentialPressure = null,
            tiltX = null,
            tiltY = null,
            twist = null,
            deltaX = null,
            deltaY = null,
            pointerType = null,
        )
    }

    suspend fun dispatchMouseReleased(x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int) {
        input.dispatchMouseEvent(
            type = DispatchMouseEventType.MOUSE_RELEASED,
            x = x,
            y = y,
            button = MouseButton.LEFT,
            clickCount = clickCount,
            modifiers = modifiers,
            buttons = buttons,
        )
    }

    suspend fun dispatchMouseWheel(x: Double, y: Double, deltaX: Double, deltaY: Double) {
        input.dispatchMouseEvent(
            type = DispatchMouseEventType.MOUSE_WHEEL,
            x = x,
            y = y,
            modifiers = null,
            timestamp = null,
            button = null,
            buttons = null,
            clickCount = null,
            force = null,
            tangentialPressure = null,
            tiltX = null,
            tiltY = null,
            twist = null,
            deltaX = deltaX,
            deltaY = deltaY,
            pointerType = null,
        )
    }

    suspend fun setInterceptDrags(enabled: Boolean) = input.setInterceptDrags(enabled)

    fun onDragIntercepted(handler: suspend (DragIntercepted) -> Unit) = input.onDragIntercepted(handler)

    suspend fun dispatchDragEvent(type: DispatchDragEventType, x: Double, y: Double, data: DragData) {
        input.dispatchDragEvent(type, x, y, data)
    }

    suspend fun insertText(text: String) = input.insertText(text)

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
    ) {
        input.dispatchKeyEvent(
            type = type,
            modifiers = modifiers,
            windowsVirtualKeyCode = windowsVirtualKeyCode,
            code = code,
            commands = commands,
            key = key,
            text = text,
            unmodifiedText = unmodifiedText,
            location = location,
            isKeypad = isKeypad,
            autoRepeat = autoRepeat,
        )
    }

    suspend fun domSnapshotCaptureSnapshot(
        computedStyles: List<String>,
        includePaintOrder: Boolean? = null,
        includeDOMRects: Boolean? = null,
        includeBlendedBackgroundColors: Boolean? = null,
        includeTextColorOpacities: Boolean? = null,
    ): CaptureSnapshot {
        return domSnapshot.captureSnapshot(
            computedStyles,
            includePaintOrder = includePaintOrder,
            includeDOMRects = includeDOMRects,
            includeBlendedBackgroundColors = includeBlendedBackgroundColors,
            includeTextColorOpacities = includeTextColorOpacities,
        )
    }

    suspend fun clearBrowserCookies() = network.clearBrowserCookies()

    suspend fun getCookies() = network.getCookies()

    suspend fun deleteCookies(
        name: String,
        url: String? = null,
        domain: String? = null,
        path: String? = null,
    ) = network.deleteCookies(name, url, domain, path)

    suspend fun setCookies(cookies: List<Map<String, Any?>>) {
        val remoteDevTools = remoteDevToolsOrNull
            ?: throw IllegalStateException("Remote DevTools is not available")
        remoteDevTools.invoke("Network.setCookies", mapOf("cookies" to cookies), EmptyResult::class)
    }

    suspend fun setExtraHTTPHeaders(headers: Map<String, Any>) = network.setExtraHTTPHeaders(headers)

    suspend fun setBlockedURLs(urls: List<String>) = network.setBlockedURLs(urls)

    suspend fun setCacheDisabled(cacheDisabled: Boolean) = network.setCacheDisabled(cacheDisabled)

    fun onRequestWillBeSent(handler: suspend (RequestWillBeSent) -> Unit) = network.onRequestWillBeSent(handler)

    fun onRequestWillBeSentExtraInfo(handler: suspend (RequestWillBeSentExtraInfo) -> Unit) =
        network.onRequestWillBeSentExtraInfo(handler)

    fun onRequestServedFromCache(handler: suspend (RequestServedFromCache) -> Unit) =
        network.onRequestServedFromCache(handler)

    fun onResponseReceived(handler: suspend (ResponseReceived) -> Unit) = network.onResponseReceived(handler)

    fun onResponseReceivedExtraInfo(handler: suspend (ResponseReceivedExtraInfo) -> Unit) =
        network.onResponseReceivedExtraInfo(handler)

    fun onLoadingFinished(handler: suspend (LoadingFinished) -> Unit) = network.onLoadingFinished(handler)

    fun onLoadingFailed(handler: suspend (LoadingFailed) -> Unit) = network.onLoadingFailed(handler)

    suspend fun continueRequest(
        requestId: String,
        url: String? = null,
        method: String? = null,
        postData: String? = null,
        headers: List<HeaderEntry>? = null,
    ) = fetch.continueRequest(requestId, url, method, postData, headers)

    suspend fun continueWithAuth(requestId: String, authChallengeResponse: AuthChallengeResponse) =
        fetch.continueWithAuth(requestId, authChallengeResponse)

    suspend fun fulfillRequest(
        requestId: String,
        responseCode: Int,
        responseHeaders: List<HeaderEntry>? = null,
        binaryResponseHeaders: String? = null,
        body: String? = null,
        responsePhrase: String? = null,
    ) = fetch.fulfillRequest(
        requestId,
        responseCode,
        responseHeaders,
        binaryResponseHeaders,
        body,
        responsePhrase,
    )

    suspend fun failRequest(requestId: String, errorReason: ErrorReason) = fetch.failRequest(requestId, errorReason)

    suspend fun getResponseBody(requestId: String) = fetch.getResponseBody(requestId)

    fun onRequestPaused(handler: suspend (RequestPaused) -> Unit) = fetch.onRequestPaused(handler)

    fun onAuthRequired(handler: suspend (AuthRequired) -> Unit) = fetch.onAuthRequired(handler)

    suspend fun setIgnoreCertificateErrors(ignore: Boolean) = security.setIgnoreCertificateErrors(ignore)

    fun onConsoleMessageAdded(handler: suspend (MessageAdded) -> Unit) = console.onMessageAdded(handler)

    suspend fun loadNetworkResource(
        @ParamName("frameId") frameId: String,
        @ParamName("url") url: String,
        @ParamName("options") options: LoadNetworkResourceOptions,
    ): LoadNetworkResourcePageResult {
        return network.loadNetworkResource(frameId, url, options)
    }

    fun awaitTermination() {
        remoteDevToolsOrNull?.awaitTermination()
    }

    fun close() {
        remoteDevToolsOrNull?.close()
    }
}
