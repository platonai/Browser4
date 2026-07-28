package ai.platon.browser4.chrome.integration

import ai.platon.browser4.chrome.ChromeLauncher
import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.api.BrowserProtocol
import ai.platon.cdt.kt.protocol.events.tracing.DataCollected
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Paths

private data class EmptyResult(val ignored: String? = null)

suspend fun main() {
    val launcher = ChromeLauncher()
    val chromeService = launcher.launch(false)
    val tab = chromeService.createTab()
    val devTools: RemoteDevTools = chromeService.createDevToolsService(tab)
    val bp: BrowserProtocol = BrowserProtocol.create(devTools)

    val dataCollectedList = mutableListOf<Any>()

    devTools.addEventListener("Tracing", "dataCollected",
        EventHandler { event ->
            dataCollectedList.addAll((event as DataCollected).value)
        }, DataCollected::class.java)

    devTools.addEventListener("Tracing", "tracingComplete",
        EventHandler {
            val path = Paths.get("/tmp/tracing.json")
            println("Tracing completed! Dumping to $path")

            val om = ObjectMapper()
            om.writeValue(path.toFile(), dataCollectedList)
            devTools.close()
        }, Any::class.java)

    devTools.addEventListener("Page", "loadEventFired",
        EventHandler {
            devTools.execute("Tracing.end", null, EmptyResult::class)
        }, Any::class.java)

    bp.pageEnable()
    devTools.execute("Tracing.start", null, EmptyResult::class)
    bp.navigate("https://github.com")
}
