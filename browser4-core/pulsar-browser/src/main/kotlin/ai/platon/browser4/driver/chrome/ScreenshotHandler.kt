package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.cdt.kt.protocol.types.page.CaptureScreenshotFormat
import ai.platon.cdt.kt.protocol.types.page.Viewport
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.math.geometric.RectD
import com.google.gson.Gson
import kotlin.math.roundToInt

class ScreenshotHandler(
    private val pageHandler: PageHandler,
    private val devTools: RemoteDevTools,
) {
    private val logger = getLogger(this)
    private val isActive get() = AppContext.isActive && devTools.isOpen
    private val page get() = devTools.page.takeIf { isActive }
    private val dom get() = devTools.dom.takeIf { isActive }
    private val debugLevel = System.getProperty("browser.additionalDebugLevel")?.toIntOrNull() ?: 0

    /**
     * Capture page screenshot.
     * */
    suspend fun screenshot(fullPage: Boolean): String? {
        if (!fullPage) {
            return page?.captureScreenshot()
        }

        val metrics = page?.getLayoutMetrics() ?: return null
        val emulation = devTools.emulation.takeIf { isActive }
        val rect = metrics.cssContentSize
        val width = rect.width.toInt()
        val height = rect.height.toInt()

        emulation?.setDeviceMetricsOverride(
            mobile = false,
            width = width,
            height = height,
            deviceScaleFactor = 1.0,
            screenWidth = width,
            screenHeight = height,
        ) ?: return null

        // PNG = Crisp, precise, lossless, and supports transparency (ideal for testing and UI design)
        // JPEG = Compact, softly detailed, lossy, and opaque (suitable for presentation and archiving)
        val format = CaptureScreenshotFormat.JPEG
        val result = page?.captureScreenshot(
            format = format,
            captureBeyondViewport = true,
        )

        emulation.clearDeviceMetricsOverride()

        return result
    }

    suspend fun screenshot(selector: String): String? {
        val node = pageHandler.querySelector(selector)
        if (node == null) {
            logger.info("No such element <{}>", selector)
            return null
        }

        return captureScreenshotWithoutVi(node, selector)
    }

    suspend fun screenshot(clip: RectD) = captureScreenshot0(null, clip)

    suspend fun screenshot(viewport: Viewport) = captureScreenshot0(null, viewport)

    private suspend fun captureScreenshotWithoutVi(node: NodeRef, selector: String): String? {
        val nodeClip = calculateNodeClip(node, selector)
        if (nodeClip == null) {
            logger.info("Can not calculate node clip | {}", selector)
            return null
        }

        val rect = nodeClip.rect
        if (rect == null) {
            logger.info("Can not take clip | {}", selector)
            return null
        }

        return captureScreenshot0(node, rect)
    }

    private suspend fun captureScreenshot0(node: NodeRef?, clip: RectD): String? {
        val viewport = Viewport(
            x = clip.x, y = clip.y,
            width = clip.width, height = clip.height,
            scale = 1.0
        )

        return captureScreenshot0(node, viewport)
    }

    private suspend fun captureScreenshot0(node: NodeRef?, viewport: Viewport): String? {
        // PNG = Crisp, precise, lossless, and supports transparency (ideal for testing and UI design)
        // JPEG = Compact, softly detailed, lossy, and opaque (suitable for presentation and archiving)
        val format = CaptureScreenshotFormat.JPEG

        // Compression quality from range [0..100] (jpeg only).
        val quality = BrowserSettings.SCREENSHOT_QUALITY

        // The viewport has to be visible before screenshot
        if (node != null) {
            // Exactly one of nodeId, backendNodeId, objectId must be provided; use nodeId for stability
            dom?.scrollIntoViewIfNeeded(node.nodeId, null, null, null)
        }

        val visible = ClickableDOM.create(page, dom, node)?.isVisible() ?: false
        if (!visible) {
            return null
        }

        return page?.captureScreenshot(
            format, quality, viewport,
            fromSurface = true,
            captureBeyondViewport = false
        )
    }

    private suspend fun calculateNodeClip(node: NodeRef, selector: String): NodeClip? {
        if (debugLevel > 50) {
            debugNodeClipDebug(node, selector)
        }

        // must scroll to top to calculate the client rect
        pageHandler.jsHandler.evaluate("__pulsar_utils__.scrollToTop()")

        val rect = calculateNodeClip0(node, selector)

        val p = page ?: return null
//        val d = dom ?: return null

        val viewport = p.getLayoutMetrics().cssLayoutViewport
        val pageX = viewport.pageX
        val pageY = viewport.pageY

        if (debugLevel > 50) {
            println(Gson().toJson(viewport))
        }

        return NodeClip(node, pageX, pageY, rect)
    }

    private suspend fun calculateNodeClip0(node: NodeRef, selector: String): RectD? {
        val clickableDOM = ClickableDOM(page!!, dom!!, node)
        return clickableDOM.boundingBox()
    }

    private suspend fun debugNodeClipDebug(node: NodeRef, selector: String) {
        println("\n")
        println("===== $selector ${node.nodeId}")

        var clientRects = pageHandler.jsHandler.evaluate("__pulsar_utils__.queryClientRects('$selector')")
        println(clientRects)
        var contentQuads = dom?.getContentQuads(node.nodeId, null, null)
        println(contentQuads)

        var clientRect = pageHandler.jsHandler.evaluate("__pulsar_utils__.queryClientRect('$selector')")?.toString()

        println("clientRect: ")
        println(clientRect)

        var clickableDOM = ClickableDOM(page!!, dom!!, node)
        println(clickableDOM.boundingBox())
        println(clickableDOM.clickablePoint())

        println("== scrollToTop ==")
        pageHandler.jsHandler.evaluate("__pulsar_utils__.scrollToTop()")

        clientRects = pageHandler.jsHandler.evaluate("__pulsar_utils__.queryClientRects('$selector')")
        println(clientRects)
        contentQuads = dom?.getContentQuads(node.nodeId, null, null)
        println(contentQuads)

        clientRect = pageHandler.jsHandler.evaluate("__pulsar_utils__.queryClientRect('$selector')")?.toString()

        println("clientRect: ")
        println(clientRect)

        clickableDOM = ClickableDOM(page!!, dom!!, node)
        println(clickableDOM.boundingBox())
        println(clickableDOM.clickablePoint())

        val p = page ?: return
//        val d = dom ?: return null

        val viewport = p.getLayoutMetrics().cssLayoutViewport
        val pageX = viewport.pageX
        val pageY = viewport.pageY

        println("pageX, pageY: ")
        println("$pageX, $pageY")
    }

    private fun normalizeClip(clip: RectD): RectD {
        val x = clip.x.roundToInt()
        val y = clip.y.roundToInt()
        val width = (clip.width + clip.x - x).roundToInt()
        val height = (clip.height + clip.y - y).roundToInt()
        return RectD(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }
}
