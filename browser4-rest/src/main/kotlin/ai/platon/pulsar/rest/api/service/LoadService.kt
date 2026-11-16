package ai.platon.pulsar.rest.api.service

import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.rest.api.entities.PromptRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Loads pages for the REST layer, optionally applying a prompt request's
 * natural-language page actions.
 *
 * Those actions ([PromptRequest.actions]) run through the session's companion
 * agent — the CLI tool loop. They used to go through the removed one-shot
 * `session.act(...)` path, which ran a single LLM round trip outside the tool
 * loop's timeouts/error handling and reported a failed tool call as success.
 *
 * A page-visit request whose actions belong to a *task* (the `CommandRequest` /
 * `PageVisitRequest` payload submitted to `POST /api/commands/json`) is executed
 * by [ai.platon.pulsar.agentic.tools.advanced.crawl.StatefulPageVisitor] →
 * [ai.platon.pulsar.agentic.tools.advanced.crawl.service.ScrapeService.loadDocument],
 * which owns the same `onBrowserLaunchedActions` / `onPageReadyActions` handling.
 * This class used to carry a duplicate of it; nothing called that copy, so it was
 * removed (2026-11-12) rather than kept as a second, silently diverging engine.
 */
@Service
class LoadService(
    private val sessionManager: PulsarSessionManager
) {

    private val logger = LoggerFactory.getLogger(LoadService::class.java)

    val session get() = sessionManager.getOrCreateSession(SWARM_SESSION_ID).agenticSession

    suspend fun load(url: String): WebPage {
        return session.load(url)
    }

    suspend fun loadDocument(url: String, args: String? = null): Pair<WebPage, FeaturedDocument> {
        if (url.contains(":8182/") || url.contains(":18182/")) {
            logger.warn("Unexpected url, internal url is not allowed | {}", url)
            return GoraWebPage.NIL to FeaturedDocument.NIL
        }

        val page = session.load(url, args ?: "")
        val document = session.parse(page, noCache = true)

        return page to document
    }

    suspend fun loadDocument(request: PromptRequest): Pair<WebPage, FeaturedDocument> {
        val args = request.args ?: ""
        val options = session.options(args)
        val be = options.eventHandlers.browseEventHandlers

        val actions = request.actions
        if (actions != null) {
            be.onDocumentFullyLoaded.addLast { _, _ ->
                actions.forEach { session.companionAgent.run(it) }
            }
        }

        val page = session.load(request.url, options)
        val document = session.parse(page)

        return page to document
    }
}
