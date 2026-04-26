package ai.platon.browser4.driver.chrome.experimental

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.cdt.kt.protocol.ChromeDevTools
import ai.platon.cdt.kt.protocol.types.domsnapshot.CaptureSnapshot
import ai.platon.cdt.kt.protocol.types.dom.Rect
import ai.platon.cdt.kt.protocol.types.input.DispatchDragEventType
import ai.platon.cdt.kt.protocol.types.input.DispatchKeyEventType
import ai.platon.cdt.kt.protocol.types.input.DispatchMouseEventType
import ai.platon.cdt.kt.protocol.types.input.DragData
import ai.platon.cdt.kt.protocol.types.page.CaptureScreenshotFormat
import ai.platon.cdt.kt.protocol.types.page.Viewport
import ai.platon.cdt.kt.protocol.types.runtime.CallArgument
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.cdt.kt.protocol.types.page.Navigate
import ai.platon.cdt.kt.protocol.types.page.ReferrerPolicy
import ai.platon.cdt.kt.protocol.types.page.TransitionType

/**
 * CDP is the single access point for all Chrome DevTools Protocol (CDP) domain APIs.
 *
 * All direct usage of [ChromeDevTools] should go through this class to improve
 * maintainability and provide a consistent, centralized interface.
 */
class CDP(
    private val devTools: ChromeDevTools
) {
    val remoteDevTools: RemoteDevTools =
        (devTools as? RemoteDevTools) ?: error("CDP requires RemoteDevTools for this runtime")

    val remoteDevToolsOrNull: RemoteDevTools? get() = devTools as? RemoteDevTools
    val isOpen: Boolean get() = remoteDevToolsOrNull?.isOpen ?: true

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

    /** Returns the main frame, suspending until the frame tree is available. */
    suspend fun mainFrame() = page.getFrameTree().frame

    suspend fun pageEnable() = page.enable()
    suspend fun domEnable() = dom.enable()
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

    suspend fun focus(nodeId: Int) = dom.focus(nodeId)

    suspend fun describeNode(
        nodeId: Int? = null,
        backendNodeId: Int? = null,
        objectId: String? = null,
        depth: Int? = null,
        pierce: Boolean? = null,
    ) = dom.describeNode(nodeId, backendNodeId, objectId, depth, pierce)

    suspend fun scrollIntoViewIfNeeded(nodeId: Int, rect: Rect? = null) = dom.scrollIntoViewIfNeeded(nodeId, rect = rect)

    suspend fun resolveNodeByNodeId(nodeId: Int) = dom.resolveNode(nodeId = nodeId)

    suspend fun resolveNodeByBackendNodeId(backendNodeId: Int) = dom.resolveNode(backendNodeId = backendNodeId)

    suspend fun requestNode(objectId: String) = dom.requestNode(objectId)

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
            button = ai.platon.cdt.kt.protocol.types.input.MouseButton.LEFT,
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
            button = ai.platon.cdt.kt.protocol.types.input.MouseButton.LEFT,
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

    fun awaitTermination() {
        remoteDevToolsOrNull?.awaitTermination()
    }

    fun close() {
        remoteDevToolsOrNull?.close()
    }
}
