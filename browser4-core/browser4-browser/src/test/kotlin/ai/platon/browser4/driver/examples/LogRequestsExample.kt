package ai.platon.browser4.driver.examples

class LogRequestsExample: BrowserExampleBase() {
    override val testUrl: String = "https://www.stbchina.cn/"

    override suspend fun run() {
        devTools.networkEnable()
        devTools.pageEnable()

        devTools.onRequestWillBeSent { event ->
            println(String.format("request: [%s] %s\n", event.request.method, event.request.url))
        }

        devTools.onResponseReceived { event ->
            if ("application/json" == event.response.mimeType) {
                println(String.format("response: [%s] %s", event.response.mimeType, event.response.url))
                if ("listChildrenCategoryWithNologin.do" in event.response.url) {
                    println(event.response.serviceWorkerResponseSource)
                }
            }
        }

        devTools.onLoadingFinished {
            // Close the tab and close the browser when loading finishes.
            chrome.closeTab(tab)
            launcher.close()
        }

        devTools.navigate(testUrl)
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
