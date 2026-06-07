package ai.platon.browser4.driver.examples.site

import ai.platon.browser4.driver.examples.BrowserExampleBase

class Crawler: BrowserExampleBase() {

    override val testUrl = "https://ly.simuwang.com/"

    override suspend fun run() {
        devTools.setBlockedURLs(listOf("*fireyejs*"))
        devTools.networkEnable()

        devTools.addScriptToEvaluateOnNewDocument(preloadJs)
        devTools.pageEnable()

        devTools.navigate(testUrl)
    }
}

suspend fun main() {
    Crawler().use { it.run() }
}
