package ai.platon.pulsar.skeleton.workflow.fetch.driver

import ai.platon.pulsar.skeleton.workflow.privacy.BrowserId

interface BrowserManager : AutoCloseable {
    val browsers: Map<BrowserId, Browser>

    fun findBrowserOrNull(browserId: BrowserId): Browser?

    fun closeBrowser(browserId: BrowserId)

    fun closeBrowser(browser: Browser)

    fun closeDriver(driver: WebDriver)
}
