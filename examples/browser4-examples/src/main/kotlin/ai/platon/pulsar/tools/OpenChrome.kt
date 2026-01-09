package ai.platon.pulsar.tools

import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory

suspend fun main() {
    val browser = DefaultBrowserFactory().launchDefaultBrowser()
    val driver = browser.newDriver()

    driver.navigateTo("about:blank")
    driver.navigateTo("https://www.amazon.in")

    readlnOrNull()

    browser.close()
}
