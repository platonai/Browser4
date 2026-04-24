package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.workflow.FetchResult
import ai.platon.pulsar.skeleton.workflow.driver.WebDriver
import ai.platon.pulsar.skeleton.workflow.privacy.BrowserId
import java.util.concurrent.atomic.AtomicInteger

class WebDriverTask(
    val browserId: BrowserId,
    val page: WebPage,
    val priority: Int = 0,
    val driverFun: suspend (driver: WebDriver) -> FetchResult?,
) {
    companion object {
        private val sequencer = AtomicInteger()
    }

    val id = sequencer.incrementAndGet()
    val volatileConfig get() = page.conf
}
