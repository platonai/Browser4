package ai.platon.pulsar.tools

import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory
import kotlinx.coroutines.runBlocking

fun main() {
    val browser = DefaultBrowserFactory().launchPrototypeBrowser()
    val driver = browser.newDriver()

    runBlocking {
        driver.navigate("about:blank")
        driver.navigate("https://www.amazon.com")
    }

    readlnOrNull()
}
