package ai.platon.pulsar.examples.sites.spa.wemix

import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.event.WebPageWebDriverEventHandler
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private class InitBrowserHandler(val initUrl: String): WebPageWebDriverEventHandler() {
    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        driver.navigateTo(initUrl)
        delay(10_000)
        return null
    }
}

private class APICrawler {
    private val mainUrl = "https://scope.wemixnetwork.com/1003/token/0xcb7615cb4322cddc518f670b4da042dbefc69500"
    private val apiTemplate = "https://scopi.wemixnetwork.com/api/v1/chain/1003/account/0xcb7615cb4322cddc518f670b4da042dbefc69500/tx"

    private val session = PulsarContexts.createSession()

    /**
     * Crawl with api with a single page application
     * Note that a proxy might be required in some country
     * */
    suspend fun crawl() {
        val initBrowserHandler = InitBrowserHandler(mainUrl)
        val options = session.options("-refresh")
        options.eventHandlers.browseEventHandlers.onBrowserLaunched.addLast(initBrowserHandler)

        IntRange(1, 100).forEach { pageNo ->
            val url = "$apiTemplate?page=$pageNo&pageSize=20"
            val json = session.loadResourceDeferred(url, mainUrl, options).contentAsString
            println(json)
        }
    }
}

fun main() = runBlocking { APICrawler().crawl() }
