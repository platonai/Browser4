package ai.platon.pulsar.driver.examples

import ai.platon.cdt.kt.protocol.events.page.*
import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.serialize.json.Pson
import java.util.concurrent.TimeUnit

class BlockUrlsExample: BrowserExampleBase() {

    override val testUrl = "https://www.stbchina.cn/"

    override suspend fun run() {
        page.enable()
        network.enable()
        runtime.enable()

        page.addScriptToEvaluateOnNewDocument(preloadJs)

        page.onDomContentEventFired { event: DomContentEventFired ->
            // The page's main html content is ready, but css/js are not ready, document.readyState === 'interactive'
            runtime.evaluate("__pulsar_utils__.checkStatus()")
        }

        page.onLoadEventFired { event: LoadEventFired ->
            // The page is completely loaded, document.readyState === 'complete'

            runtime.evaluate("__pulsar_utils__.scrollDownN();")
//            runtime.evaluate("__pulsar_utils__.emulate();")

            val source = pageSource
            val path = AppPaths.WEB_CACHE_DIR.resolve(AppPaths.fromUri(testUrl, "", ".htm"))
            AppFiles.saveTo(source, path, true)
            logger.debug("Page is saved to {}", path.toUri())

            TimeUnit.SECONDS.sleep(5)
            devTools.close()
        }

        page.onFrameAttached { event: FrameAttached ->
            if (isMainFrame(event.frameId)) {
                debugDocumentState(event)
            }
            println("onFrameAttached - ${event.frameId}")
        }

        page.onFrameDetached { event: FrameDetached ->
            if (isMainFrame(event.frameId)) {
                debugDocumentState(event)
            }
            println("onFrameDetached - " + event.frameId)
        }

        page.onFrameNavigated { event: FrameNavigated ->
            if (isMainFrame(event.frame.id)) {
                debugDocumentState(event)
                println(event.javaClass.simpleName + " - " + event.frame.id)
            }
        }

        page.onFrameStartedLoading { event: FrameStartedLoading ->
            if (isMainFrame(event.frameId)) {
                debugDocumentState(event)
                // emulation.canEmulate() //true
            }
        }

        page.onFrameStoppedLoading { event: FrameStoppedLoading ->
            if (isMainFrame(event.frameId)) {
                println("[main] onFrameStoppedLoading - ${event.frameId} - ${pageSource.length}")
            } else {
                println("onFrameStoppedLoading - ${event.frameId}")
            }
            println()
        }

        page.navigate(testUrl)

        println(Pson.toJson(chrome.version))

//        readLine()
    }

    private fun isMainFrame(frameId: String): Boolean {
        return mainFrame.id == frameId
    }

    private suspend fun debugDocumentState(event: Any, message: String = "") {
        val evaluate = runtime.evaluate("document.readyState")
        println("${event.javaClass.simpleName} ${evaluate.result.value} | message")
    }
}

suspend fun main() {
    BlockUrlsExample().use { it.run() }
}
