package ai.platon.browser4.chrome.integration

import ai.platon.cdt.kt.protocol.events.overlay.ScreenshotRequested
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.types.dom.RGBA
import ai.platon.cdt.kt.protocol.types.overlay.HighlightConfig
import ai.platon.pulsar.common.serialize.json.Pson

class OverlayExample : BrowserExampleBase() {

    override val testUrl: String = "https://www.amazon.com/dp/B08PP5MSVB"

    private data class EmptyResult(val ignored: String? = null)

    override suspend fun run() {
        devTools.pageEnable()
        devTools.domEnable()
        remoteDevTools.invoke("Overlay.enable", null, EmptyResult::class)

        remoteDevTools.addEventListener("Overlay", "screenshotRequested",
            EventHandler { event ->
                val screenshot = event as ScreenshotRequested
                val v = screenshot.viewport
                remoteDevTools.execute("Overlay.highlightRect",
                    mapOf("x" to v.x.toInt(), "y" to v.y.toInt(),
                        "width" to v.width.toInt(), "height" to v.height.toInt()),
                    EmptyResult::class)
            }, ScreenshotRequested::class.java)

        // page.onFrameAttached not available on BrowserProtocol — use raw CDP
        remoteDevTools.addEventListener("Page", "frameAttached",
            EventHandler {
                // captureSnapshot not on BrowserProtocol
                // highlight("#productTitle")
            }, Any::class.java)

        devTools.navigate(testUrl)
    }

    private suspend fun highlight(selector: String) {
        val documentId = devTools.getDocument().nodeId
        val nodeId = devTools.querySelector(documentId, selector)
        val highlightConfig = HighlightConfig(
            showInfo = true,
            showRulers = true,
            showStyles = true,
            showExtensionLines = true,
            shapeColor = RGBA(255, 0, 0, 1.0)
        )

        remoteDevTools.execute("Overlay.highlightRect",
            mapOf("x" to 300, "y" to 400, "width" to 500, "height" to 500),
            EmptyResult::class)
        remoteDevTools.execute("Overlay.highlightNode",
            mapOf("highlightConfig" to highlightConfig, "nodeId" to nodeId,
                "backendNodeId" to null, "objectId" to null, "selector" to selector),
            EmptyResult::class)
        val obj = remoteDevTools.execute("Overlay.getHighlightObjectForTest",
            mapOf("nodeId" to nodeId), Any::class) ?: "null"
        println(Pson.toJson(obj))
    }
}

suspend fun main() {
    OverlayExample().use { it.run() }
}
