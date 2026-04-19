package ai.platon.pulsar.agentic.tools.high.crawl.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest
import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.high.crawl.common.DegenerateXSQLScrapeHyperlink
import ai.platon.pulsar.agentic.tools.high.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.high.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.high.crawl.common.XSQLScrapeHyperlink
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import org.apache.commons.collections4.MultiMapUtils
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ScrapeService(
    val session: AgenticSession
) {
    private val logger = LoggerFactory.getLogger(ScrapeService::class.java)

    /**
     * The response cache, the key is the id, the value is the response
     * */
    private val responseCache = ConcurrentSkipListMap<String, ScrapeResponse>()

    /**
     * The response status map, the key is the status code, the value is the response's id
     * */
    private val responseStatusIndex = MultiMapUtils.newListValuedHashMap<Int, String>()

    fun loadDocument(request: PageVisitRequest, eventHandlers: PageEventHandlers): Pair<WebPage, FeaturedDocument> {
        val args = request.enhanceArgs()
        val options = session.options(args, eventHandlers)

        val be = options.eventHandlers.browseEventHandlers

        request.onBrowserLaunchedActions?.let { actions ->
            be.onBrowserLaunched.addLast { page, driver ->
                actions.forEach { session.act(it) }
            }
        }

        request.onPageReadyActions?.let { actions ->
            be.onDocumentFullyLoaded.addLast { page, driver ->
                actions.forEach { session.act(it) }
            }
        }

        val page = session.load(request.url, options)
        val document = session.parse(page)

        return page to document
    }

    /**
     * Execute a scrape task and wait until the execution is done,
     * for test purpose only, no customer should access this api
     * */
    fun executeQuery(request: ScrapeRequest): ScrapeResponse {
        try {
            val hyperlink = createScrapeHyperlink(request)
            session.submit(hyperlink)
            val response = hyperlink.get(120, TimeUnit.SECONDS)
            return response
        } catch (e: TimeoutException) {
            logger.warn("Timeout executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse("", ResourceStatus.SC_REQUEST_TIMEOUT, ProtocolStatusCodes.REQUEST_TIMEOUT)
        } catch (e: Exception) {
            logger.error("Unexpected error executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse("", ResourceStatus.SC_INTERNAL_SERVER_ERROR, ProtocolStatusCodes.EXCEPTION)
        }
    }

    private fun createScrapeHyperlink(request: ScrapeRequest): ScrapeHyperlink {
        val sql = request.sql
        val link = if (ScrapeAPIUtils.isScrapeUDF(sql)) {
            val xSQL = ScrapeAPIUtils.normalize(sql)
            XSQLScrapeHyperlink(request, xSQL, session)
        } else {
            DegenerateXSQLScrapeHyperlink(request, session)
        }

        link.eventHandlers.crawlEventHandlers.onLoaded.addLast { url, page ->
            responseCache[link.uuid] = link.response
            responseStatusIndex[link.response.statusCode].add(link.uuid)
            null
        }

        return link
    }
}
