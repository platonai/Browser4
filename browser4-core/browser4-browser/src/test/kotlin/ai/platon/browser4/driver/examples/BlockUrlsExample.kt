package ai.platon.browser4.driver.examples

import ai.platon.cdt.kt.protocol.events.page.DomContentEventFired
import ai.platon.cdt.kt.protocol.events.page.FrameAttached
import ai.platon.cdt.kt.protocol.events.page.FrameDetached
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.events.page.FrameStartedLoading
import ai.platon.cdt.kt.protocol.events.page.FrameStoppedLoading
import ai.platon.cdt.kt.protocol.events.page.LoadEventFired
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.serialize.json.Pson
import java.util.concurrent.TimeUnit

class BlockUrlsExample: BrowserExampleBase() {

    override val testUrl = "https://www.stbchina.cn/"

    private data class EmptyResult(val ignored: String? = null)

    override suspend fun run() {
        devTools.pageEnable()
        devTools.networkEnable()
        devTools.runtimeEnable()

        devTools.addScriptToEvaluateOnNewDocument(preloadJs)

        remoteDevTools.addEventListener("Page", "domContentEventFired",
            EventHandler { event ->
                val e = event as DomContentEventFired
                devTools.evaluate("__pulsar_utils__.checkStatus()")
            },
            DomContentEventFired::class.java)

        remoteDevTools.addEventListener("Page", "loadEventFired",
            EventHandler { event ->
                val e = event as LoadEventFired
                devTools.evaluate("__pulsar_utils__.scrollDownN();")

                val source = pageSource
                val path = AppPaths.WEB_CACHE_DIR.resolve(AppPaths.fromUri(testUrl, "", ".htm"))
                AppFiles.saveTo(source, path, true)
                logger.debug("Page is saved to {}", path.toUri())

                TimeUnit.SECONDS.sleep(5)
                remoteDevTools.close()
            },
            LoadEventFired::class.java)

        remoteDevTools.addEventListener("Page", "frameAttached",
            EventHandler { event ->
                val e = event as FrameAttached
                if (isMainFrame(e.frameId)) {
                    debugDocumentState(e)
                }
                println("onFrameAttached - ${e.frameId}")
            },
            FrameAttached::class.java)

        remoteDevTools.addEventListener("Page", "frameDetached",
            EventHandler { event ->
                val e = event as FrameDetached
                if (isMainFrame(e.frameId)) {
                    debugDocumentState(e)
                }
                println("onFrameDetached - " + e.frameId)
            },
            FrameDetached::class.java)

        devTools.onFrameNavigated { event ->
            if (isMainFrame(event.frame.id)) {
                debugDocumentState(event)
                println(event.javaClass.simpleName + " - " + event.frame.id)
            }
        }

        remoteDevTools.addEventListener("Page", "frameStartedLoading",
            EventHandler { event ->
                val e = event as FrameStartedLoading
                if (isMainFrame(e.frameId)) {
                    debugDocumentState(e)
                }
            },
            FrameStartedLoading::class.java)

        remoteDevTools.addEventListener("Page", "frameStoppedLoading",
            EventHandler { event ->
                val e = event as FrameStoppedLoading
                if (isMainFrame(e.frameId)) {
                    println("[main] onFrameStoppedLoading - ${e.frameId} - ${pageSource.length}")
                } else {
                    println("onFrameStoppedLoading - ${e.frameId}")
                }
                println()
            },
            FrameStoppedLoading::class.java)

        devTools.navigate(testUrl)

        println(Pson.toJson(chrome.version))
    }

    private fun isMainFrame(frameId: String): Boolean {
        return mainFrame.id == frameId
    }

    private suspend fun debugDocumentState(event: Any, message: String = "") {
        val evaluate = devTools.evaluate("document.readyState")
        println("${event.javaClass.simpleName} ${evaluate.result.value} | message")
    }
}

suspend fun main() {
    BlockUrlsExample().use { it.run() }
}
