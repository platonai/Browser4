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

class LogRequestsExample: BrowserExampleBase() {
    override val testUrl: String = "https://www.stbchina.cn/"

    override suspend fun run() {

        network.enable()
        page.enable()

        network.onRequestWillBeSent { event ->
            println(String.format("request: [%s] %s\n", event.request.method, event.request.url))
        }

        network.onResponseReceived { event ->
            if ("application/json" == event.response.mimeType) {
                println(String.format("response: [%s] %s", event.response.mimeType, event.response.url))
                if ("listChildrenCategoryWithNologin.do" in event.response.url) {
                    println(event.response.serviceWorkerResponseSource)
                }
            }
        }

        network.onLoadingFinished {
            // Close the tab and close the browser when loading finishes.
            chrome.closeTab(tab)
            launcher.close()
        }

        page.navigate(testUrl)
    }

    private fun isBlocked(url: String): Boolean {
        return url.endsWith(".png") || url.endsWith(".css")
    }
}

suspend fun main() {
    LogRequestsExample().use {
        try {
            it.run()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
