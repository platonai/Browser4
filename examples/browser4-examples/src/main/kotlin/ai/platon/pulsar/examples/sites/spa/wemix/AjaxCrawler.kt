package ai.platon.pulsar.examples.sites.spa.wemix

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.event.WebPageWebDriverEventHandler
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.session.PulsarSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private class AjaxFetchHandler(
    val initPageNumber: Int,
    val exportDirectory: Path
): WebPageWebDriverEventHandler() {
    private val logger = getLogger(this)

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        val session = driver.newJsoupSession()

        val u = "https://scopi.wemixnetwork.com/api/v1/chain/1003/account/0xcb7615cb4322cddc518f670b4da042dbefc69500/tx"
        IntRange(1, 100).forEach { i ->
            try {
                val pageNo = initPageNumber + i
                val json = session.newRequest()
                    .url(u)
                    .data("page", "$pageNo")
                    .data("pagesize", "20")
                    .execute()
                    .body()

                println(json)
                export(pageNo, json, true)
            } catch (e: Exception) {
                logger.warn(e.stringify("$i.\t"))
            }
        }

        return null
    }

    private fun prepareFiles(path: Path) {
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.createFile(path)
        }
    }

    private fun export(i: Int, text: String, isJson: Boolean = false) {
        val postfix = if (isJson) ".json" else ".html"
        val timestamp = System.currentTimeMillis()
        val fileName = "transaction.b${initPageNumber}.t$timestamp.p$i$postfix"
        val file = exportDirectory.resolve(fileName)
        prepareFiles(file)
        Files.writeString(file, text, StandardOpenOption.APPEND)
    }
}

private class AjaxCrawler(
    var initPageNumber: Int = 1,
    val session: PulsarSession
) {
    private val logger = getLogger(this)

    private val url = "https://scope.wemixnetwork.com/1003/token/0xcb7615cb4322cddc518f670b4da042dbefc69500"

    val reportDirectory = AppPaths.REPORT_DIR
        .resolve("wemix")
        .resolve("b$initPageNumber")

    /**
     * Crawl with api with a single page application
     * */
    fun crawl() {
        if (Files.exists(reportDirectory)) {
            return
        }

        val apiFetcherHandler = AjaxFetchHandler(initPageNumber, reportDirectory)
        val options = session.options("-refresh")
        options.eventHandlers.browseEventHandlers.onWillComputeFeature.addLast(apiFetcherHandler)

        try {
            session.load(url, options)
        } catch (e: Exception) {
            logger.warn("Unexpected exception", e)
        }
    }
}

fun main() {
    val session = PulsarContexts.createSession()

    IntRange(1, 80).forEach { i ->
        val crawler = AjaxCrawler(100 * i, session)
        crawler.crawl()
    }
}
