package ai.platon.pulsar.examples.advanced

import ai.platon.pulsar.browser.common.BlockRule
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.config.CapabilityTypes.PROXY_ROTATION_URL
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink

class HighPerformanceCrawler {
    private val session = PulsarContexts.createSession()

    fun crawl() {
        // Crawl arguments:
        // -refresh: always re-fetch the page
        // -dropContent: do not persist page content
        // -interactLevel fastest: prioritize speed over data completeness
        val args = "-refresh -dropContent -interactLevel fastest"

        // Block non-essential resources to improve load speed.
        // ⚠️ Be careful — blocking critical resources may break rendering or script execution.
        val blockingUrls = BlockRule().blockingUrls

        val resource = "seeds/amazon/best-sellers/leaf-categories.txt"
        val links =
            LinkExtractors.fromResource(resource).asSequence().map { ListenableHyperlink(it, "", args = args) }.onEach {
                it.eventHandlers.browseEventHandlers.onWillNavigate.addLast { page, driver ->
                    driver.addBlockedURLs(blockingUrls)
                }
            }.toList()

        session.submitAll(links)
    }
}

fun main() {
    // Highly recommended to enable proxies, or you will be blocked by Amazon
    val proxyHubURL = "http://localhost:8192/api/proxies"
    if (NetUtil.testHttpNetwork(proxyHubURL)) {
        System.setProperty(PROXY_ROTATION_URL, proxyHubURL)
    }

    PulsarSettings.maxBrowserContexts(2).maxOpenTabs(8).withSequentialBrowsers()

    HighPerformanceCrawler().crawl()
    PulsarContexts.await()
}
