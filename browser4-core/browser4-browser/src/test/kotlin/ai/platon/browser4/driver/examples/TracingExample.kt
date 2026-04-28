/*-
 * #%L
 * cdt-kotlin-client
 * %%
 * Copyright (C) 2025 platon.ai
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ai.platon.browser4.driver.examples

import ai.platon.cdt.kt.protocol.events.tracing.DataCollected
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

class TracingExample: BrowserExampleBase() {

    override val testUrl: String = "https://www.stbchina.cn/"

    override suspend fun run() {
        val page = devTools.page
        val tracing = devTools.tracing

        val dataCollectedList = mutableListOf<Any>()

        // Add tracing data to dataCollectedList
        tracing.onDataCollected { event: DataCollected ->
            dataCollectedList.addAll(event.value)
        }

        // When tracing is complete, dump dataCollectedList to JSON file.
        tracing.onTracingComplete {
            // Dump tracing to file.
            val path = Paths.get("/tmp/tracing.json")
            println("Tracing completed! Dumping to $path")
            dump(path, dataCollectedList)
            devTools.close()
        }

        page.onLoadEventFired { tracing.end() }

        page.enable()
        tracing.start()
        page.navigate(testUrl)
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
