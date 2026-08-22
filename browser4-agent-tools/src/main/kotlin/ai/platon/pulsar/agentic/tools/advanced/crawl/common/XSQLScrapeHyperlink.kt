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
                val p = page ?: GoraWebPage.NIL
                when {
                    hyperlink.isDone -> {
                        // Already completed (defensive: a duplicate loaded event).
                        response.refresh(isDone = true)
                    }

                    // Transient failure or cancellation: the task has been
                    // re-queued for another attempt. Keep it in a non-terminal
                    // state so the CLI keeps polling and a later attempt can
                    // complete the task. This also prevents the task from being
                    // reported "done" with an empty result mid-retry.
                    // NOTE: checked before isFailed — both the canceled and the
                    // retry protocol statuses also report isFailed.
                    p.isCanceled || p.protocolStatus.isRetry -> {
                        response.emitEvent("retry")
                        response.message = "Page fetch is being retried: ${p.protocolStatus.reason ?: "transient failure"}"
                        response.refresh(ResourceStatus.SC_ACCEPTED, p.protocolStatus.minorCode, false)
                    }

                    // Terminal failure: the fetch failed. This includes a
                    // canceled task whose retry budget was exhausted — the
                    // runner clears the canceled flag and marks its status as
                    // failed. Completing here with an empty result set would
                    // report a "successful" task whose page was never fetched —
                    // instead mark it as failed with the real reason.
                    // NOTE: `!isFetched` alone is NOT a failure. isFetched is
                    // only set by FetchComponent when a protocol actually
                    // fetched the page over the network; a page served from the
                    // WebDB cache (a load without -refresh that reuses a
                    // previously fetched record) legitimately completes with
                    // content and a success status while isFetched stays false.
                    // Failing those tasks turned repeated swarm queries on
                    // cached URLs into 417 "never fetched" errors.
                    p.isNil || p.protocolStatus.isFailed ||
                        (!p.isFetched && !p.protocolStatus.isSuccess) -> {
                        hyperlink.fail(p, page == null)
                    }

                    else -> hyperlink.complete(p)
                }
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
            } else if (page.protocolStatus.isFailed) {
                response.message = buildString {
                    append("Page fetch failed with status ")
                    append(page.protocolStatus.minorCode)
                    append(". Re-fetch with -refresh or -ignoreFailure to retry. ")
                    append("Example: swarm submit \"${page.url} -refresh\"")
                }
            }

            response
        } catch (t: Throwable) {
            warnInterruptible(this, t, "Error extracting data from page: ${page.url}")
        }
    }

    /**
     * Mark the task as failed with a clear diagnostic instead of completing it
     * with an empty result. Used when the page was never fetched, was dropped,
     * or the fetch failed/canceled — so a completed task either contains data
     * or reports the real failure reason.
     * */
    open fun fail(page: WebPage, pageWasNull: Boolean = false) {
        val status = page.protocolStatus
        val reason = status.reason?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
        response.pageContentBytes = page.contentLength.toInt().coerceAtLeast(0)
        response.pageStatusCode = status.minorCode
        response.statusCode = ResourceStatus.SC_EXPECTATION_FAILED
        response.message = when {
            page.isNil || pageWasNull || !page.isFetched ->
                "The page was never fetched. The task may have been dropped or evicted. Re-run the query with -refresh to retry."

            page.isCanceled ->
                "The page fetch was canceled ($reason). Re-fetch with -refresh or -ignoreFailure to retry."

            status.isFailed ->
                "Page fetch failed with status ${status.minorCode} ($reason). Re-fetch with -refresh or -ignoreFailure to retry."

            else ->
                "The page was not fetched (protocol status ${status.minorCode}: $reason)."
        }
        complete(page)
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
        // Use lowercase column labels so they match the keys produced by
        // ResultSetUtils.getTextEntitiesFromResultSet (which lowercases
        // via ResultSetMetaData.getColumnName + toLowerCase).  Without
        // this the reorder pass produces duplicate columns: the original-
        // case lookup returns null, and the "append extras" loop re-adds
        // the lowercased key — giving each column twice.
        val sqlColumnOrder = (1..metaData.columnCount).map { metaData.getColumnLabel(it).lowercase() }

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

        // Always ensure resultSet is non-null before transitioning to a
        // terminal state.  A null resultSet combined with isDone=true creates
        // a race where the CLI fetches an empty resultSet for a "completed"
        // task.  By guaranteeing at least an empty list here, the CLI always
        // sees a consistent (empty or populated) resultSet.
        if (response.resultSet == null) {
            response.resultSet = emptyList()
        }

        response.refresh(response.statusCode, page.protocolStatus.minorCode, false)
    }
}

typealias XSQLScrapeHyperlink = XSQLHyperlink
