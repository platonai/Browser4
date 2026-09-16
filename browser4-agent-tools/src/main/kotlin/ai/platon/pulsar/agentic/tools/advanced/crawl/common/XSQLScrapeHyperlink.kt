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
                extractWithCache(page, document)
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

    /**
     * Run the X-SQL against [page], which is local by construction: this handler is invoked from
     * the load of that very page, while the page is still hot in the session's caches.
     *
     * The statement is executed by the h2 engine, which resolves the url in its FROM clause itself
     * — `load_and_select()` calls `PulsarSession.load()`. Two things must hold for that inner load
     * to be read-only, and both are established here, in the same breath as the query:
     *
     *  1. the statement is sealed with `-readonly` and stripped of every option that could force a
     *     web load ([ScrapeAPIUtils.normalizeForReadOnlyQuery]);
     *  2. the page and its document are frozen in the global page cache — the cache the engine's
     *     own session shares, since both live in the same context — under the url the engine
     *     resolves the page by, and the frozen copy is verified immediately before the statement
     *     runs.
     *
     * Without (2) `-readonly` is only a promise about a page that happens to be cached: on a cache
     * miss `PulsarSession.load()` quietly falls through to a full web load, which modifies the
     * page and repeats the fetch in the middle of the query.
     *
     * @return the extracted rows, or null when the statement was refused or failed
     * */
    protected open fun extractWithCache(page: WebPage, document: FeaturedDocument): List<Map<String, Any?>>? {
        val normSQL = try {
            ScrapeAPIUtils.normalizeForReadOnlyQuery(sql.sql)
        } catch (e: Exception) {
            // A statement that could still fetch is a bug, not a query to run: report it instead
            // of letting the engine load the page over the network while the query executes.
            return refuse(page, "The X-SQL could not be sealed read-only: ${e.message}")
        }

        val queryUrl = ScrapeAPIUtils.resolveQueryUrl(session, normSQL.url)

        // Freeze first, verify last: the window the UDF looks through is the gap between the check
        // and the statement, not the time that has passed since the page was fetched.
        val local = ScrapeAPIUtils.freezePageForQuery(session, queryUrl, page, document)
        if (!local) {
            return refuse(
                page,
                "The page '$queryUrl' is not in the local page cache, so the read-only X-SQL would " +
                        "have re-fetched it from the web. The query was not executed; load the page " +
                        "again and retry."
            )
        }

        return executeXSQL(page, document, normSQL)
    }

    /**
     * Fail the extraction loudly instead of running a query that would reach the web.
     *
     * The response is left non-terminal and empty: the crawl event handlers decide how the task
     * ends, and an empty result set keeps the CLI from rendering a "successful" page whose data
     * was never extracted.
     * */
    private fun refuse(page: WebPage, message: String): List<Map<String, Any?>>? {
        logger.warn("{} | page: {} | status: {}", message, page.url, page.protocolStatus)
        response.message = message
        response.resultSet = emptyList()
        response.refresh(ResourceStatus.SC_EXPECTATION_FAILED, page.protocolStatus.minorCode, false)
        return null
    }

    /**
     * Execute the sealed [normSQL] and record the outcome on the response.
     *
     * The running and the shaping of the statement belong to [XSqlExecutor], which the crawl uses
     * as well; what stays here is what this path alone knows — the page it was driven by, and the
     * response the swarm task is watching.
     * */
    protected open fun executeXSQL(
        page: WebPage,
        document: FeaturedDocument,
        normSQL: NormXSQL,
    ): List<Map<String, Any?>>? {
        if (!page.protocolStatus.isSuccess || page.contentLength == 0L || page.content == null) {
            logger.info("No content | Protocol Status: {} | Page URL: {} | Document Base URI: {}", page.protocolStatus, page.url, document.baseURI)
            response.statusCode = ResourceStatus.SC_NO_CONTENT
            response.refresh(ResourceStatus.SC_NO_CONTENT, ResourceStatus.SC_NO_CONTENT, false)
        }

        val result = XSqlExecutor.execute(session, normSQL)
        // The status is owned by the execution, not by whatever the page status set above: a
        // statement that ran is a success even when the page had nothing to select from.
        response.statusCode = result.statusCode
        if (!result.isSuccess) {
            response.message = result.error
        }

        // Always ensure resultSet is non-null before transitioning to a
        // terminal state.  A null resultSet combined with isDone=true creates
        // a race where the CLI fetches an empty resultSet for a "completed"
        // task.  By guaranteeing at least an empty list here, the CLI always
        // sees a consistent (empty or populated) resultSet.
        response.resultSet = result.rows ?: emptyList()
        response.refresh(response.statusCode, page.protocolStatus.minorCode, false)

        return result.rows
    }
}
