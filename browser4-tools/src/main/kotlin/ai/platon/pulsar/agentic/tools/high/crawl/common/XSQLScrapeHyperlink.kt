package ai.platon.pulsar.agentic.tools.high.crawl.common

import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.high.crawl.refresh
import ai.platon.pulsar.agentic.tools.high.crawl.emitEvent
import ai.platon.pulsar.common.PulsarParams.VAR_IS_SCRAPE
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultCrawlEventHandlers
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultLoadEventHandlers
import ai.platon.pulsar.skeleton.crawl.event.impl.PageEventHandlersFactory
import ai.platon.pulsar.skeleton.session.PulsarSession
import java.util.*

open class XSQLScrapeHyperlink(
    request: ScrapeRequest,
    sql: NormXSQL,
    session: PulsarSession,
    uuid: String = UUID.randomUUID().toString()
) : AbstractScrapeHyperlink(request, sql, session, uuid) {

    class CrawlEventHandlers(
        val hyperlink: XSQLScrapeHyperlink,
        val response: ScrapeResponse,
    ) : DefaultCrawlEventHandlers() {
        init {
            onWillLoad.addLast {
                response.emitEvent("onWillLoad")
                it
            }
            onLoaded.addLast { url, page ->
                if (!hyperlink.isDone) {
                    hyperlink.complete(page ?: GoraWebPage.NIL)
                }
                response.refresh(isDone = true)
            }
        }
    }

    class LoadEventHandlers(
        val hyperlink: XSQLScrapeHyperlink,
        val response: ScrapeResponse,
    ) : DefaultLoadEventHandlers() {
        init {
            onWillLoad.addLast {
                null
            }
            onWillParseHTMLDocument.addLast { page ->
                require(page is AbstractWebPage)
                page.variables[VAR_IS_SCRAPE] = true
                null
            }
            onWillParseHTMLDocument.addLast { page ->
            }
            onHTMLDocumentParsed.addLast { page, document ->
                require(page is AbstractWebPage)
                require(page.hasVar(VAR_IS_SCRAPE))
                hyperlink.extract(page, document)
            }
            onLoaded.addLast { page ->
                response.emitEvent("onLoaded")
            }
        }
    }

    private val logger = getLogger(XSQLScrapeHyperlink::class)

    override var args: String? = "-parse ${sql.args}"
    override var eventHandlers = PageEventHandlersFactory.create(
        loadEventHandlers = LoadEventHandlers(this, response),
        crawlEventHandlers = CrawlEventHandlers(this, response),
    )

    open fun extract(page: WebPage, document: FeaturedDocument) {
        try {
            response.emitEvent("extract")
            response.pageContentBytes = page.contentLength.toInt()
            response.pageStatusCode = page.protocolStatus.minorCode

            if (page.protocolStatus.isSuccess) {
                doExtract(page, document)
            }

            response
        } catch (t: Throwable) {
            warnInterruptible(this, t, "Error extracting data from page: ${page.url}")
        }
    }

    protected open fun doExtract(page: WebPage, document: FeaturedDocument) {
        if (!page.protocolStatus.isSuccess || page.contentLength == 0L || page.content == null) {
            logger.info("No content | {}", page.url)
            response.statusCode = ResourceStatus.SC_NO_CONTENT
            response.refresh(ResourceStatus.SC_NO_CONTENT, ResourceStatus.SC_NO_CONTENT, false)
        }

        val rs = executeQuery(request, response)
        response.resultSet = ResultSetUtils.getTextEntitiesFromResultSet(rs)
        response.refresh(ResourceStatus.SC_OK, page.protocolStatus.minorCode, false)
    }
}
