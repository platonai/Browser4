package ai.platon.pulsar.agentic.tools.high.crawl.common

import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeRequest
import ai.platon.pulsar.common.urls.DegenerateUrl
import ai.platon.pulsar.common.warnUnexpected
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Never go to load phase, `LoadComponent.load()` is never used for a degenerate link
 * */
open class DegenerateXSQLScrapeHyperlink(
    request: ScrapeRequest,
    session: PulsarSession,
    uuid: String = UUID.randomUUID().toString(),
) : AbstractScrapeHyperlink(request, DegenerateXSQL(uuid, sql = request.sql), session, uuid), DegenerateUrl {

    private val logger = LoggerFactory.getLogger(DegenerateXSQLScrapeHyperlink::class.java)
    override var args: String? = "-taskId $uuid ${sql.args}"
    override var eventHandlers: PageEventHandlers = createPageEventHandlers()

    override fun complete(page: WebPage) {
        try {
            require(page.isNil)
            response.pageContentBytes = 1
            response.pageStatusCode = 200
        } catch (t: Throwable) {
            logger.warn("Unexpected exception", t)
            throw t
        } finally {
            response.isDone = true
            super.complete(page)
        }
    }

    private fun createPageEventHandlers(): PageEventHandlers {
        return DefaultPageEventHandlers().also {
            it.crawlEventHandlers.onLoaded.addLast { url, page ->
                executeQueryAndComplete(page)
            }
        }
    }

    private fun executeQueryAndComplete(page: WebPage?) {
        try {
            val rs = executeQuery()
            response.resultSet = ResultSetUtils.getTextEntitiesFromResultSet(rs)
        } catch (t: Throwable) {
            // Log the exception and throw it
            warnUnexpected(this, t, "Failed to execute query")
            throw t
        } finally {
            this.complete(page ?: GoraWebPage.NIL)
        }
    }
}
