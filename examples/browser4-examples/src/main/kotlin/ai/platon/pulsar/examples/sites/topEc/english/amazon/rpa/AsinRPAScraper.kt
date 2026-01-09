package ai.platon.pulsar.examples.sites.topEc.english.amazon.rpa

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.sql.ResultSetFormatter
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.context.SQLContexts

class AsinRPAScraper {
    companion object {
        private const val JS_EXTRACT_SELLER_URLS = "JS_EXTRACT_SELLER_URLS"
    }

    private val logger = getLogger(AsinSellerScraper::class)

    private val context = SQLContexts.create()
    private val session = context.createSession()

    private val asinSQLTemplate = SQLTemplate.load("amazon/x-asin.sql")

    private val asinLoadArgs = "-parse -requireSize 800000 -refresh"
    private val districtSelector = "#glow-ingress-block, .nav-global-location-slot"
    private val sellerListCandidateSelectors = listOf(
        "#olpLinkWidget_feature_div a",
        "div.olp-text-box a",
        "div[data-feature-name=olpLinkWidget] a",
        "a[href~=offer-listing]"
    )
    private val sellerOptionSelector = "#aod-offer a[href*=seller]"

    /**
     * Given ASIN url list, scrape all the ASIN pages and relative seller pages.
     * After clicking `New seller` button, the seller url will be displayed.
     * */
    fun crawl() {
        val url = "https://www.amazon.com/dp/B09V3KXJPB"
        val domain = "amazon.com"
        val link = createASINHyperlink(domain, url)
        session.load(link)
    }

    private fun createASINHyperlink(domain: String, asinUrl: String): ListenableHyperlink {
        val hyperlink = ListenableHyperlink(asinUrl, "", args = asinLoadArgs)
        val be = hyperlink.eventHandlers.browseEventHandlers

        be.onDocumentSteady.addLast { page, driver ->
            val district = driver.selectFirstTextOrNull(districtSelector)
            logger.info("District: {}", district)
            null
        }

        val le = hyperlink.eventHandlers.loadEventHandlers
        le.onHTMLDocumentParsed.addLast { page, document ->
            scrapeAsin(page, document)
        }

        return hyperlink
    }

    private suspend fun clickAndCollectSellerLinks(page: WebPage, driver: WebDriver) {
        val sellerListSelector = sellerListCandidateSelectors.firstOrNull { driver.exists(it) } ?: return
        clickAndCollectSellerLinks(sellerListSelector, page, driver)
    }

    private suspend fun clickAndCollectSellerLinks(
        sellerListOpenerSelector: String, page: WebPage, driver: WebDriver
    ): Boolean {
        val asinUrl = page.url

        logger.info("Clicking $sellerListOpenerSelector | $asinUrl")
        driver.click(sellerListOpenerSelector, 1)

        val requiredElement = "#pinned-de-id"
        logger.info("Waiting for $requiredElement | $asinUrl")

        val remainder = driver.waitForSelector(requiredElement)
        val seen = !remainder.isNegative
        if (seen) {
            logger.info("Seen selector $requiredElement")
            val sellerUrls = extractSellerUrlsFromPopupLayer(page, driver)
//            logger.info("Extracted seller links: \n{}", sellerUrls)
            require(page is AbstractWebPage)
            page.setVar(JS_EXTRACT_SELLER_URLS, sellerUrls)
        } else {
            logger.info("Timeout to wait for selector $requiredElement")
        }

        return seen
    }

    private suspend fun extractSellerUrlsFromPopupLayer(page: WebPage, driver: WebDriver): List<String> {
        logger.info("Extracting seller urls from popup layer")
        // driver.evaluate("document.querySelectorAll('#aod-offer a')")
        val expression = "__pulsar_utils__.allLinks('$sellerOptionSelector')"
        return driver.evaluate(expression)?.toString()?.split("\n")?.toList()?.distinct() ?: listOf()
    }

    private fun scrapeAsin(page: WebPage, document: FeaturedDocument) {
        if (!context.isActive) {
            return
        }

        kotlin.runCatching { scrapeAsin0(page, document) }.onFailure { warnInterruptible(this, it) }
    }

    private fun scrapeAsin0(page: WebPage, document: FeaturedDocument) {
        val asinUrl = page.url
        val sql = asinSQLTemplate.createSQL(asinUrl)
        val rs = context.executeQuery(sql)

        val formatter = ResultSetFormatter(rs, asList = true, maxColumnLength = 120)
        println(formatter.toString())

        val sellerUrls = document.selectHyperlinks(sellerOptionSelector).distinct()
        sellerUrls.forEach { println(it) }

        rs.close()
    }
}

fun main() {
    val crawler = AsinRPAScraper()
    crawler.crawl()

    readlnOrNull()
}
