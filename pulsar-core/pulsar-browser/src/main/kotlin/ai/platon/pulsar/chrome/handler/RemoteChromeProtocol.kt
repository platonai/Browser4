package ai.platon.pulsar.chrome.handler

import ai.platon.pulsar.chrome.RemoteDevTools
import ai.platon.cdt.kt.protocol.ChromeDevTools
import ai.platon.cdt.kt.protocol.events.console.MessageAdded
import ai.platon.cdt.kt.protocol.events.fetch.AuthRequired
import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.events.input.DragIntercepted
import ai.platon.cdt.kt.protocol.events.network.*
import ai.platon.cdt.kt.protocol.events.page.DocumentOpened
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.events.page.WindowOpen
import ai.platon.cdt.kt.protocol.types.dom.Rect
import ai.platon.cdt.kt.protocol.types.domsnapshot.CaptureSnapshot
import ai.platon.cdt.kt.protocol.types.fetch.AuthChallengeResponse
import ai.platon.cdt.kt.protocol.types.fetch.HeaderEntry
import ai.platon.cdt.kt.protocol.types.fetch.RequestPattern
import ai.platon.cdt.kt.protocol.types.input.*
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.LoadNetworkResourceOptions
import ai.platon.cdt.kt.protocol.types.page.*
import ai.platon.cdt.kt.protocol.types.runtime.CallArgument
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.pulsar.browser.impl.BrowserProtocol

/**
 * CDP is the single access point for all Chrome DevTools Protocol (CDP) domain APIs.
 *
 * All direct usage of [ai.platon.cdt.kt.protocol.ChromeDevTools] should go through this class to improve
 * maintainability and provide a consistent, centralized interface.
 */
class RemoteChromeProtocol(
    val devTools: ChromeDevTools
) : BrowserProtocol {
    private data class EmptyResult(val ignored: String? = null)

    val remoteDevTools: RemoteDevTools =
        (devTools as? RemoteDevTools) ?: error("CDP requires RemoteDevTools for this runtime")

    val remoteDevToolsOrNull: RemoteDevTools? get() = devTools as? RemoteDevTools
    override val isOpen: Boolean get() = remoteDevToolsOrNull?.isOpen ?: false

    override suspend fun isBrowserAlive(): Boolean {
        return runCatching { browser.getVersion() }.isSuccess
    }

    override suspend fun isTargetAlive(): Boolean {
        return runCatching { target.getTargets() }.isSuccess
    }

    override suspend fun isV8Alive(): Boolean {
        return runCatching { runtime.evaluate("1+1") }.isSuccess
    }

    val browser get() = devTools.browser
    val page get() = devTools.page
    val target get() = devTools.target
    val dom get() = devTools.dom
    val css get() = devTools.css
    val input get() = devTools.input
    val network get() = devTools.network
    val fetch get() = devTools.fetch
    val runtime get() = devTools.runtime
    val emulation get() = devTools.emulation
    val accessibility get() = devTools.accessibility
    val domSnapshot get() = devTools.domSnapshot
    val security get() = devTools.security
    val console get() = devTools.console

    override suspend fun mainFrame() = page.getFrameTree().frame

    override suspend fun pageEnable() = page.enable()
    override suspend fun domEnable() = dom.enable()
    override suspend fun accessibilityEnable() = accessibility.enable()
    override suspend fun runtimeEnable() = runtime.enable()
    override suspend fun networkEnable() = network.enable()
    override suspend fun cssEnable() = css.enable()
    override suspend fun fetchEnable() = fetch.enable()
    override suspend fun fetchEnable(patterns: List<RequestPattern>, handleAuthRequests: Boolean) =
        fetch.enable(patterns, handleAuthRequests)

    override suspend fun securityEnable() {
        security.enable()
    }

    override suspend fun getFrameTree() = page.getFrameTree()

    override suspend fun reload() = page.reload()
    override suspend fun navigateToHistoryEntry(entryId: Int) = page.navigateToHistoryEntry(entryId)
    override suspend fun handleJavaScriptDialog(accept: Boolean, promptText: String?) =
        page.handleJavaScriptDialog(accept, promptText)

    override suspend fun bringToFront() = page.bringToFront()
    override suspend fun stopLoading() = page.stopLoading()
    override suspend fun addScriptToEvaluateOnNewDocument(script: String) =
        page.addScriptToEvaluateOnNewDocument(script)

    override fun onDocumentOpened(handler: suspend (DocumentOpened) -> Unit) = page.onDocumentOpened(handler)
    override fun onFrameNavigated(handler: suspend (FrameNavigated) -> Unit) = page.onFrameNavigated(handler)
    override fun onWindowOpen(handler: suspend (WindowOpen) -> Unit) = page.onWindowOpen(handler)

    override suspend fun navigate(url: String): Navigate = page.navigate(url)

    override suspend fun navigate(
        url: String,
        referrer: String?,
        transitionType: TransitionType?,
        frameId: String?,
        referrerPolicy: ReferrerPolicy?,
    ): Navigate = page.navigate(url, referrer, transitionType, frameId, referrerPolicy)

    override suspend fun evaluate(
        expression: String,
        contextId: Int?,
        returnByValue: Boolean?,
        awaitPromise: Boolean?,
    ): Evaluate {
        return runtime.evaluate(
            expression = expression,
            contextId = contextId,
            returnByValue = returnByValue,
            awaitPromise = awaitPromise,
        )
    }

    override suspend fun callFunctionOn(
        functionDeclaration: String,
        objectId: String?,
        arguments: List<CallArgument>?,
        silent: Boolean?,
        returnByValue: Boolean?,
        generatePreview: Boolean?,
        userGesture: Boolean?,
        awaitPromise: Boolean?,
        executionContextId: Int?,
        objectGroup: String?,
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

    override suspend fun releaseObject(objectId: String) = runtime.releaseObject(objectId)

    override suspend fun getLayoutMetrics() = page.getLayoutMetrics()

    override suspend fun getNavigationHistory() = page.getNavigationHistory()

    override suspend fun createIsolatedWorld(frameId: String, worldName: String, grantUniveralAccess: Boolean): Int {
        return page.createIsolatedWorld(
            frameId = frameId,
            worldName = worldName,
            grantUniveralAccess = grantUniveralAccess,
        )
    }

    override suspend fun captureScreenshot(
        format: CaptureScreenshotFormat?,
        quality: Int?,
        clip: Viewport?,
        fromSurface: Boolean?,
        captureBeyondViewport: Boolean?,
    ) = page.captureScreenshot(
        format = format,
        quality = quality,
        clip = clip,
        fromSurface = fromSurface,
        captureBeyondViewport = captureBeyondViewport,
    )

    override suspend fun setDeviceMetricsOverride(
        mobile: Boolean,
        width: Int,
        height: Int,
        deviceScaleFactor: Double,
        screenWidth: Int?,
        screenHeight: Int?,
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

    override suspend fun clearDeviceMetricsOverride() = emulation.clearDeviceMetricsOverride()

    override suspend fun getDocument(depth: Int?, pierce: Boolean?) = dom.getDocument(depth, pierce)

    override suspend fun getContentQuads(nodeId: Int) = dom.getContentQuads(nodeId)

    override suspend fun getBoxModel(nodeId: Int) = dom.getBoxModel(nodeId, null, null)

    override suspend fun querySelector(nodeId: Int, selector: String) = dom.querySelector(nodeId, selector)

    override suspend fun querySelectorAll(nodeId: Int, selector: String) = dom.querySelectorAll(nodeId, selector)

    override suspend fun performSearch(query: String, includeUserAgentShadowDOM: Boolean?) =
        dom.performSearch(query, includeUserAgentShadowDOM)

    override suspend fun getSearchResults(searchId: String, fromIndex: Int, toIndex: Int) =
        dom.getSearchResults(searchId, fromIndex, toIndex)

    override suspend fun discardSearchResults(searchId: String) = dom.discardSearchResults(searchId)

    override suspend fun getAttributes(nodeId: Int) = dom.getAttributes(nodeId)

    override suspend fun focus(nodeId: Int) = dom.focus(nodeId)

    override suspend fun describeNode(
        nodeId: Int?,
        backendNodeId: Int?,
        objectId: String?,
        depth: Int?,
        pierce: Boolean?,
    ) = dom.describeNode(nodeId, backendNodeId, objectId, depth, pierce)

    override suspend fun scrollIntoViewIfNeeded(nodeId: Int, backendNodeId: Int?, objectId: String?, rect: Rect?) =
        dom.scrollIntoViewIfNeeded(nodeId, backendNodeId, objectId, rect)

    override suspend fun resolveNodeByNodeId(nodeId: Int) = dom.resolveNode(nodeId = nodeId)

    override suspend fun resolveNodeByBackendNodeId(backendNodeId: Int) = dom.resolveNode(backendNodeId = backendNodeId)

    override suspend fun requestNode(objectId: String) = dom.requestNode(objectId)

    override suspend fun getComputedStyleForNode(nodeId: Int) = css.getComputedStyleForNode(nodeId)

    override suspend fun getFullAXTree(depth: Int?) = accessibility.getFullAXTree(depth)

    override suspend fun clearBrowserCookies() = network.clearBrowserCookies()
    override suspend fun setBlockedURLs(urls: List<String>) = network.setBlockedURLs(urls)
    override suspend fun getCookies() = network.getCookies()
    override suspend fun deleteCookies(name: String, url: String?, domain: String?, path: String?) =
        network.deleteCookies(name, url, domain, path)

    override suspend fun loadNetworkResource(frameId: String, url: String, options: LoadNetworkResourceOptions) =
        network.loadNetworkResource(frameId, url, options)

    override suspend fun failRequest(requestId: String, errorReason: ErrorReason) =
        fetch.failRequest(requestId, errorReason)

    override suspend fun getResponseBody(requestId: String) = fetch.getResponseBody(requestId)

    override suspend fun setFileInputFiles(files: List<String>, nodeId: Int) = dom.setFileInputFiles(files, nodeId)
    override suspend fun getOuterHTML(nodeId: Int, backendNodeId: Int, objectId: String?) =
        dom.getOuterHTML(nodeId, backendNodeId, objectId)

    override fun onDragIntercepted(handler: (DragIntercepted) -> Unit) = input.onDragIntercepted(handler)

    override suspend fun dispatchMouseMoved(x: Double, y: Double, buttons: Int?) {
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

    override suspend fun dispatchMousePressed(x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int) {
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

    override suspend fun dispatchMouseReleased(x: Double, y: Double, clickCount: Int, modifiers: Int?, buttons: Int) {
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

    override suspend fun dispatchMouseWheel(x: Double, y: Double, deltaX: Double, deltaY: Double) {
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

    override suspend fun setInterceptDrags(enabled: Boolean) = input.setInterceptDrags(enabled)

    override suspend fun dispatchDragEvent(type: DispatchDragEventType, x: Double, y: Double, data: DragData) {
        input.dispatchDragEvent(type, x, y, data)
    }

    override suspend fun insertText(text: String) = input.insertText(text)

    override suspend fun dispatchKeyEvent(
        type: DispatchKeyEventType,
        modifiers: Int?,
        windowsVirtualKeyCode: Int?,
        code: String?,
        commands: List<String>?,
        key: String?,
        text: String?,
        unmodifiedText: String?,
        location: Int?,
        isKeypad: Boolean?,
        autoRepeat: Boolean?,
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

    override suspend fun domSnapshotCaptureSnapshot(
        computedStyles: List<String>,
        includePaintOrder: Boolean?,
        includeDOMRects: Boolean?,
        includeBlendedBackgroundColors: Boolean?,
        includeTextColorOpacities: Boolean?,
    ): CaptureSnapshot {
        return domSnapshot.captureSnapshot(
            computedStyles,
            includePaintOrder = includePaintOrder,
            includeDOMRects = includeDOMRects,
            includeBlendedBackgroundColors = includeBlendedBackgroundColors,
            includeTextColorOpacities = includeTextColorOpacities,
        )
    }

    override suspend fun reloadPage(ignoreCache: Boolean?, scriptToEvaluateOnLoad: String?) =
        page.reload(ignoreCache, scriptToEvaluateOnLoad)


    override suspend fun setCookies(cookies: List<Map<String, Any?>>) {
        val remoteDevTools = remoteDevToolsOrNull
            ?: throw IllegalStateException("Remote DevTools is not available")
        remoteDevTools.invoke("Network.setCookies", mapOf("cookies" to cookies), EmptyResult::class)
    }

    override suspend fun setExtraHTTPHeaders(headers: Map<String, Any>) = network.setExtraHTTPHeaders(headers)

    override suspend fun setCacheDisabled(cacheDisabled: Boolean) = network.setCacheDisabled(cacheDisabled)

    override fun onRequestWillBeSent(handler: suspend (RequestWillBeSent) -> Unit) =
        network.onRequestWillBeSent(handler)

    override fun onRequestWillBeSentExtraInfo(handler: suspend (RequestWillBeSentExtraInfo) -> Unit) =
        network.onRequestWillBeSentExtraInfo(handler)

    override fun onRequestServedFromCache(handler: suspend (RequestServedFromCache) -> Unit) =
        network.onRequestServedFromCache(handler)

    override fun onResponseReceived(handler: suspend (ResponseReceived) -> Unit) = network.onResponseReceived(handler)

    override fun onResponseReceivedExtraInfo(handler: suspend (ResponseReceivedExtraInfo) -> Unit) =
        network.onResponseReceivedExtraInfo(handler)

    override fun onLoadingFinished(handler: suspend (LoadingFinished) -> Unit) = network.onLoadingFinished(handler)

    override fun onLoadingFailed(handler: suspend (LoadingFailed) -> Unit) = network.onLoadingFailed(handler)

    override suspend fun continueRequest(
        requestId: String,
        url: String?,
        method: String?,
        postData: String?,
        headers: List<HeaderEntry>?,
    ) = fetch.continueRequest(requestId, url, method, postData, headers)

    override suspend fun continueWithAuth(requestId: String, authChallengeResponse: AuthChallengeResponse) =
        fetch.continueWithAuth(requestId, authChallengeResponse)

    override suspend fun fulfillRequest(
        requestId: String,
        responseCode: Int,
        responseHeaders: List<HeaderEntry>?,
        binaryResponseHeaders: String?,
        body: String?,
        responsePhrase: String?,
    ) = fetch.fulfillRequest(
        requestId,
        responseCode,
        responseHeaders,
        binaryResponseHeaders,
        body,
        responsePhrase,
    )

    override fun onRequestPaused(handler: suspend (RequestPaused) -> Unit) = fetch.onRequestPaused(handler)

    override fun onAuthRequired(handler: suspend (AuthRequired) -> Unit) = fetch.onAuthRequired(handler)

    override suspend fun setIgnoreCertificateErrors(ignore: Boolean) = security.setIgnoreCertificateErrors(ignore)

    override fun onConsoleMessageAdded(handler: suspend (MessageAdded) -> Unit) = console.onMessageAdded(handler)

    override fun awaitTermination() {
        remoteDevToolsOrNull?.awaitTermination()
    }

    override fun close() {
        remoteDevToolsOrNull?.close()
    }
}
