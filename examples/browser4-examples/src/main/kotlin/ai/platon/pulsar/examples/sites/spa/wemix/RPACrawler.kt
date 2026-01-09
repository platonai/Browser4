package ai.platon.pulsar.examples.sites.spa.wemix

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.event.WebPageWebDriverEventHandler
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.delay
import kotlin.random.Random

private class RPAPaginateHandler(val initPageNumber: Int) : WebPageWebDriverEventHandler() {

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        driver.waitForSelector("#tab-transactions")
        driver.click("#tab-transactions")

        pageDownTo(driver)

        IntRange(1, 100).forEach { i ->
            roundGap(i)

            var text = driver.selectFirstTextOrNull(".table__list table.table__list-set tr:nth-child(25)") ?: ""
            println(text)
            if (text.length < 100) {
                delay(3000)
            }

            text = driver.outerHTML(".table__list table.table__list-set") ?: ""

            val nthChild = if (initPageNumber == 1 || i <= 6) i else 6
            val nextPageSelector = "ul.el-pager li:nth-child($nthChild)"
            driver.click(nextPageSelector)
        }

        return null
    }

    private suspend fun pageDownTo(driver: WebDriver) {
        repeat(initPageNumber / 6) { i ->
            driver.click(".btn-quicknext")
            roundGap(i)
        }
    }

    private suspend fun roundGap(i: Int) {
        val delaySeconds = when {
            i % 20 == 0 -> 20 + Random.nextInt(10)
            i % 100 == 0 -> 60 + Random.nextInt(10)
            else -> 3
        }
        delay(1000 * (1L + delaySeconds))
    }
}

private class RPACrawler {
    private val logger = getLogger(this)
    private val session = PulsarContexts.createSession()
    private val url = "https://scope.wemixnetwork.com/1003/token/0xcb7615cb4322cddc518f670b4da042dbefc69500"

    /**
     * Crawl a single page application
     * */
    fun crawlSPA() {
        BrowserSettings.withSPA()

        val paginateHandler = RPAPaginateHandler(1)
        val options = session.options("-refresh")
        options.eventHandlers.browseEventHandlers.onDocumentFullyLoaded.addLast(paginateHandler)
        try {
            session.load(url, options)
        } catch (e: Exception) {
            logger.warn("Unexpected exception", e)
        }
    }
}

fun main() = RPACrawler().crawlSPA()
