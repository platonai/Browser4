package ai.platon.pulsar.examples.sites.topEc.english.amazon.rpa

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.examples.sites.topEc.english.amazon.AmazonUrls
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.context.SQLContexts
import ai.platon.pulsar.skeleton.PulsarSettings
import java.sql.ResultSet

class AsinSellerScraper {
    private val logger = getLogger(AsinSellerScraper::class)

    private val context = SQLContexts.create()
    private val session = context.createSession()

    private val asinUrls = LinkExtractors.fromResource("amazon/amazon.global.urls.txt")
    private val scrapedAsinUrls = mutableSetOf<String>()
    private val collectedSellerUrls = mutableSetOf<String>()
    private val scrapedSellerUrls = mutableSetOf<String>()
    private var totalOtherSellerCount = 0
    private val asinSQLResource = "amazon/x-asin.sql"
    private val sellerSQLResource = "amazon/x-sellers.sql"

    private val asinSQLTemplate = SQLTemplate.load(asinSQLResource)
    private val sellerSQLTemplate = SQLTemplate.load(sellerSQLResource)
    private val districtZipCodes = mapOf(
        "amazon.co.uk" to "S99 3AD"
    )
    private val districtTexts = mapOf(
        "amazon.co.uk" to "Sheffield S99 3"
    )
    val enabledDomains = listOf("amazon.co.uk")

    /**
     * Given ASIN url list, scrape all the ASIN pages and relative seller pages.
     * After clicking `New seller` button, the seller url will be displayed.
     * */
    fun crawl() {
        val groupedUrls = asinUrls.groupBy { URLUtils.getTopPrivateDomain(it)!! }
        val groupedUrls0 = mutableMapOf<String, List<String>>()
        groupedUrls.forEach { (group, urls0) ->
            logger.info(group + "\t" + urls0.size)
            groupedUrls0[group] = urls0.shuffled().take(100)
        }

        val groupedUrls1 = groupedUrls0.filter { it.key in enabledDomains }

        groupedUrls1.forEach { (domain, urls) ->
            val asinLinks = urls.map { createASINHyperlink(domain, it) }
            session.context.submitAll(asinLinks)
        }

        session.context.await()
    }

    fun createASINHyperlink(domain: String, asinUrl: String): ListenableHyperlink {
        val hyperlink = ListenableHyperlink(asinUrl, "", args = "-i 5s -parse -requireSize 800000")
        val be = hyperlink.eventHandlers.browseEventHandlers

        be.onWillComputeFeature.addLast { page, driver ->
            val district = driver.selectFirstTextOrNull("#glow-ingress-block, .nav-global-location-slot") ?: ""
            val expectedDistrict = districtTexts[domain] ?: "not-a-district"
            if (district.contains(expectedDistrict)) {
                clickAndCollectSellerLinks(page, driver)
            } else {
                logger.warn("District is not expected {} but actually {}", expectedDistrict, district)
            }
        }

        val le = hyperlink.eventHandlers.loadEventHandlers
        le.onHTMLDocumentParsed.addLast { page, document ->
            scrapeAsin(page, document)
        }

        return hyperlink
    }

    private suspend fun clickAndCollectSellerLinks(page: WebPage, driver: WebDriver) {
        val asinUrl = page.url
        val sellerListSelectorCandidates = listOf(
            "#olpLinkWidget_feature_div a",
            "div.olp-text-box a",
            "div[data-feature-name=olpLinkWidget] a",
            "a[href~=offer-listing]"
        )
        val sellerListSelector = sellerListSelectorCandidates.firstOrNull { driver.exists(it) }
        if (sellerListSelector != null) {
            logger.info("Clicking $sellerListSelector | $asinUrl")
            driver.click(sellerListSelector, 1)
            val requiredElement = "#pinned-de-id"
            logger.info("Waiting for $requiredElement | $asinUrl")
            val remainder = driver.waitForSelector(requiredElement)
            if (!remainder.isNegative) {
                logger.info("Seen selector $requiredElement")

                extractSellerUrls(page, driver)

                // submitSellerLinks()
            } else {
                logger.info("Timeout to wait for selector $requiredElement")
            }
        }
    }

    suspend fun extractSellerUrls(page: WebPage, driver: WebDriver) {
        logger.info("Extracting seller urls from page layer")
        // driver.evaluate("document.querySelectorAll('#aod-offer a')")
        var expression = "__pulsar_utils__.allAttrs('#aod-offer a', 'href')"
        val links = driver.evaluate(expression)
        println(links.toString())
//                logger.info("extracted seller links: \n{}", links.joinToString("\n"))
//                expression = "__pulsar_utils__.allAttrs('#aod-offer a', 'abs:href')"
//                val absLinks = driver.evaluate(expression) as List<String>
//                logger.info("extracted seller absolute links: \n{}", absLinks.joinToString("\n"))
    }

    fun scrapeAsin(page: WebPage, document: FeaturedDocument) {
        if (!context.isActive) {
            return
        }

        kotlin.runCatching { scrapeAsin0(page, document) }.onFailure { warnInterruptible(this, it) }
    }

    private fun scrapeAsin0(page: WebPage, document: FeaturedDocument) {
        val asinUrl = page.url
        val sql = asinSQLTemplate.createSQL(asinUrl)
        val rs = context.executeQuery(sql)

        scrapedAsinUrls.add(asinUrl)

        while (rs.next()) {
            val extractedSellerLinks = collectSellerLinks(asinUrl, rs)
            // submitSellerLinks(extractedSellerLinks)
        }

        rs.close()
    }

    fun collectSellerLinks(asinUrl: String, rs: ResultSet): List<Hyperlink> {
        val extractedUrl = rs.getString("url")
        val otherSellerCount = rs.getString("other_seller_num")?.toIntOrNull() ?: 0
        totalOtherSellerCount += otherSellerCount

        val extractedSellerUrls = rs.getArray("buy_box_seller_id").array as Array<Object>
        val sellerLinks = extractedSellerUrls.map { it.toString() }.filter { URLUtils.isStandard(it) }
            .mapNotNull { href ->
                AmazonUrls.normalizeSellerUrl(href)?.let { Hyperlink(it, "", href = href, referrer = asinUrl) }
            }
        sellerLinks.mapTo(collectedSellerUrls) { it.url }

        logger.info(
            "Extracted {}/{}/{} seller urls, {} Sell/Asin, total {}/{}(collected seller/scraped asin) | {}",
            extractedSellerUrls.size, otherSellerCount, totalOtherSellerCount,
            String.format("%.2f", totalOtherSellerCount.toFloat() / scrapedAsinUrls.size),
            collectedSellerUrls.size, scrapedAsinUrls.size, asinUrl
        )

        return sellerLinks
    }

    fun submitSellerLinks(sellerLinks: List<Hyperlink>) {
        val createParsableHyperlink = { link: Hyperlink ->
            ParsableHyperlink(link.url) { page, _ -> scrapeSeller(page) }
                .apply {
                    args = "-parse -expires 1000d -requireSize 200000"
                    priority = Priority13.HIGHER2.value
                    href = link.href
                }
                .apply {
                    eventHandlers.browseEventHandlers.onWillNavigate.addLast { page, _ ->
                        page.referrer = null
                        null
                    }
                    eventHandlers.loadEventHandlers.onFetched.addLast { page ->
                        link.referrer?.let { page.referrer = it }
                    }
                }
        }

        val parsableSellerLinks = sellerLinks.map { createParsableHyperlink(it) }
        session.submitAll(parsableSellerLinks)
    }

    fun scrapeSeller(sellerPage: WebPage) {
        if (!context.isActive) {
            return
        }

        kotlin.runCatching { scrapeSeller0(sellerPage) }.onFailure { warnInterruptible(this, it) }
    }

    private fun scrapeSeller0(sellerPage: WebPage) {
        val url = sellerPage.url
        val sql = sellerSQLTemplate.createSQL(url)
        val rs = context.executeQuery(sql)

        scrapedSellerUrls.add(url)

        while (rs.next()) {
            val url0 = rs.getString("url")
            val sellerID = rs.getString("sellerID")
            val sellerName = rs.getString("seller_name")
            logger.info("$sellerID $sellerName | $url0")

            // val formatter = ResultSetFormatter(rs, asList = true, withHeader = true)
            // logger.info("\n" + formatter.toString())
        }

        rs.close()
    }
}

fun main() {
    PulsarSettings.maxBrowserContexts(2).maxOpenTabs(4)

    val crawler = AsinSellerScraper()
    crawler.crawl()
}
