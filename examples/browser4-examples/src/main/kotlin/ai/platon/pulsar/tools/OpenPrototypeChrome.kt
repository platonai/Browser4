package ai.platon.pulsar.tools

import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults.browser
import kotlinx.coroutines.runBlocking

fun main() {
    val browser = DefaultBrowserFactory().launchPrototypeBrowser()
    val driver = browser.newDriver()

    runBlocking {
        driver.navigateTo("about:blank")
        driver.navigateTo("https://www.amazon.com")
    }

    readlnOrNull()
}
