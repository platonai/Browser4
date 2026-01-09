package ai.platon.pulsar.examples.sites.tools

import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.ProxyPool
import ai.platon.pulsar.common.urls.DegenerateHyperlink
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.context.SQLContexts
import ai.platon.pulsar.skeleton.context.PulsarContexts
import org.apache.hc.core5.net.URIBuilder
import java.util.concurrent.atomic.AtomicInteger

class SearchAgent {
    private val logger = getLogger(this)
    private val args = "-i 7s -parse -refresh"
    private val googleBaseUrl = "https://www.google.com"
    private val bingBaseUrl = "https://www.bing.com"
    private val baiduBaseUrl = "https://www.bing.com"
    private val baseUrl get() = bingBaseUrl
    private val submittedDegeneratedLinks = AtomicInteger()
    private val submittedSearchTasks = AtomicInteger()

    private val context = SQLContexts.create()
    private val session = context.createSession()
    private val proxyPool get() = context.getBean(ProxyPool::class)

    fun search() {
//        val proxyLoader = TemporaryProxyLoader(proxyPool)
//        proxyLoader.loadProxies()

        val async = false
        val limit = 4
        val businessNames = ResourceLoader.readAllLines("entity/business.names.com.txt").shuffled().take(limit)
        val contactNames = listOf("Email", "Phone", "Facebook")
        businessNames.forEach { businessName ->
            contactNames.forEach { contactName ->
                val keyword = "$businessName $contactName"
                if (async) {
                    val degeneratedHyperlink = DegenerateHyperlink(bingBaseUrl, "bing.com") { bing(keyword) }
                    session.submit(degeneratedHyperlink)
                    submittedDegeneratedLinks.incrementAndGet()
                } else {
                    bing(keyword, async = async)
                }
            }
        }

        PulsarContexts.await()
    }

    fun bing(keyword: String, async: Boolean = true) {
        val url = URIBuilder("$bingBaseUrl/search").addParameter("q", keyword).build().toURL()

        val options = session.options(args)
        val be = options.eventHandlers.browseEventHandlers
        val le = options.eventHandlers.loadEventHandlers

        be.onDocumentFullyLoaded.addLast { page, driver ->
            driver.scrollTo("ol#b_results li:nth-child(3) h2")
            driver.scrollTo("ol#b_results li:nth-child(5) h2")
            driver.scrollTo("ol#b_results li:nth-child(8) h2")

            driver.click("input#sb_form_q")
            driver.scrollToTop()

            println(String.format("%d.\t%s", page.id, page.url))
            val resultStats = driver.selectFirstTextOrNull("#b_tween")
            println(resultStats)

            val texts = driver.selectTextAll("ol#b_results li h2")
            println(texts)
        }

        le.onHTMLDocumentParsed.addLast { page, document ->
            extract(page, document)
        }

        if (async) {
            session.submit(url.toString(), options)
        } else {
            session.load(url.toString(), options)
        }
        submittedSearchTasks.incrementAndGet()
    }

    fun google(keyword: String, async: Boolean = true) {
        val builder = URIBuilder("$googleBaseUrl/search")
        builder.addParameter("q", keyword)
        val url = builder.build().toURL().toString()
        val options = session.options(args)
        val be = options.eventHandlers.browseEventHandlers
        val le = options.eventHandlers.loadEventHandlers

        be.onDocumentFullyLoaded.addLast { page, driver ->
            driver.scrollTo("h3:nth-child(3)")
            driver.scrollTo("h3:nth-child(5)")
            driver.scrollTo("h3:nth-child(8)")

            driver.click("textarea[name=q]")
            driver.scrollToTop()

            println(String.format("%d.\t%s", page.id, page.url))
            val resultStats = driver.selectFirstTextOrNull("#result-stats")
            println(resultStats)
            val texts = driver.selectTextAll("h3")
            println(texts)
        }

        le.onHTMLDocumentParsed.addLast { page, document ->
            extract(page, document)
        }

//        BrowserSettings.disableProxy()
        if (async) {
            session.submit(url, options)
        } else {
            session.load(url, options)
        }
        submittedSearchTasks.incrementAndGet()
    }

    private fun extract(page: WebPage, document: FeaturedDocument) {
        logger.info("Extract | {} | {}", page.protocolStatus, page.url)
    }

    private fun test(proxy: ProxyEntry): Boolean {
        return if (!NetUtil.testTcpNetwork(proxy.host, proxy.port)) {
            logger.info("Proxy not available: {}", proxy.toURI())
            false
        } else true
    }
}

fun main() {
    val agent = SearchAgent()
    agent.search()

    readlnOrNull()
}
