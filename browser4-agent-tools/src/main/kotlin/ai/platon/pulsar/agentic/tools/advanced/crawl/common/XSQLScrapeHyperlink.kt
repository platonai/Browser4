package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.common.B4Constants.VAR_IS_SCRAPE
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.emitEvent
import ai.platon.pulsar.agentic.tools.advanced.crawl.refresh
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.event.impl.DefaultCrawlEventHandlers
import ai.platon.pulsar.skeleton.event.impl.DefaultLoadEventHandlers
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import ai.platon.pulsar.skeleton.session.PulsarSession
import java.util.*

open class XSQLHyperlink(
    request: ScrapeRequest,
    sql: NormXSQL,
    session: PulsarSession,
    uuid: String = UUID.randomUUID().toString()
) : AbstractScrapeHyperlink(request, sql, session, uuid) {

    class CrawlEventHandlers(
        val hyperlink: XSQLHyperlink,
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
        val hyperlink: XSQLHyperlink,
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

    private val logger = getLogger(XSQLHyperlink::class)

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
            logger.info("No content | Protocol Status: {} | Page URL: {} | Document Base URI: {}", page.protocolStatus, page.url, document.baseURI)
            response.statusCode = ResourceStatus.SC_NO_CONTENT
            response.refresh(ResourceStatus.SC_NO_CONTENT, ResourceStatus.SC_NO_CONTENT, false)
        }

        val rs = executeQuery(request, response)

        // Read column names from ResultSetMetaData BEFORE converting to text
        // entities.  JDBC metadata preserves the SQL SELECT column order, which
        // is the deterministic source of truth for column ordering.  Without
        // this, column order depends on the iteration order of row-key maps,
        // which varies across Map implementations and causes inconsistent CSV
        // headers, JSON field order, and table columns.
        val metaData = rs.metaData
        val sqlColumnOrder = (1..metaData.columnCount).map { metaData.getColumnName(it) }

        val rawResultSet = ResultSetUtils.getTextEntitiesFromResultSet(rs)

        // Ensure all column keys are present in every row. When a selector
        // finds no match, the corresponding column should contain null rather
        // than being silently absent — missing data must be visible.
        // Use SQL column order first, then append any extra keys discovered
        // during row iteration (edge case: computed columns with dynamic names).
        val allKeys = linkedSetOf<String>()
        allKeys.addAll(sqlColumnOrder)
        for (row in rawResultSet) {
            allKeys.addAll(row.keys)
        }
        response.resultSet = rawResultSet.map { row ->
            val filled = linkedMapOf<String, Any?>()
            for (key in allKeys) {
                filled[key] = row[key]
            }
            filled
        }

        response.refresh(response.statusCode, page.protocolStatus.minorCode, false)
    }
}

typealias XSQLScrapeHyperlink = XSQLHyperlink
