package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.DegenerateXSQLScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.XSQLScrapeHyperlink

/**
 * Creates [ScrapeHyperlink] instances with the shared logic for X-SQL vs degenerate
 * hyperlink selection and onLoaded event registration.
 *
 * Extracted from [ScrapeService] and [SwarmService] to eliminate duplication.
 */
object ScrapeHyperlinkFactory {

    /**
     * Create a [ScrapeHyperlink] for the given request and session.
     *
     * @param request The scrape request containing the SQL/X-SQL.
     * @param session The agentic session (must be a [GenericAgenticSession]).
     * @param onLoaded Callback invoked when the hyperlink's page is loaded.
     *        Receives the hyperlink's UUID and response for caching/indexing.
     * @return The created hyperlink, ready for submission.
     */
    fun create(
        request: ScrapeRequest,
        session: AgenticSession,
        onLoaded: (ScrapeHyperlink) -> Unit = {},
    ): ScrapeHyperlink {
        require(session is GenericAgenticSession) {
            "Session must be a GenericAgenticSession, but was ${session::class.simpleName} (uuid=${session.uuid})"
        }

        val sql = request.sql
        val link = if (ScrapeAPIUtils.isScrapeUDF(sql)) {
            val xSQL = ScrapeAPIUtils.normalize(sql)
            XSQLScrapeHyperlink(request, xSQL, session)
        } else {
            DegenerateXSQLScrapeHyperlink(request, session)
        }

        link.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, _ ->
            onLoaded(link)
            null
        }

        return link
    }
}
