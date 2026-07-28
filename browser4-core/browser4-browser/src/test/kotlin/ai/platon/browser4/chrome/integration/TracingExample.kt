package ai.platon.browser4.chrome.integration

import ai.platon.cdt.kt.protocol.events.tracing.DataCollected
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

class TracingExample: BrowserExampleBase() {

    override val testUrl: String = "https://www.stbchina.cn/"

    private data class EmptyResult(val ignored: String? = null)

    override suspend fun run() {
        val dataCollectedList = mutableListOf<Any>()

        // Add tracing data to dataCollectedList
        remoteDevTools.addEventListener("Tracing", "dataCollected",
            EventHandler { event ->
                dataCollectedList.addAll((event as DataCollected).value)
            }, DataCollected::class.java)

        // When tracing is complete, dump dataCollectedList to JSON file.
        remoteDevTools.addEventListener("Tracing", "tracingComplete",
            EventHandler {
                val path = Paths.get("/tmp/tracing.json")
                println("Tracing completed! Dumping to $path")
                dump(path, dataCollectedList)
                remoteDevTools.close()
            }, Any::class.java)

        remoteDevTools.addEventListener("Page", "loadEventFired",
            EventHandler { remoteDevTools.execute("Tracing.end", null, EmptyResult::class) },
            Any::class.java)

        devTools.pageEnable()
        remoteDevTools.execute("Tracing.start", null, EmptyResult::class)
        devTools.navigate(testUrl)
    }

    private fun dump(path: Path, data: List<Any>) {
        val om = ObjectMapper()
        try {
            om.writeValue(path.toFile(), data)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}

suspend fun main() {
    TracingExample().use { it.run() }
}
