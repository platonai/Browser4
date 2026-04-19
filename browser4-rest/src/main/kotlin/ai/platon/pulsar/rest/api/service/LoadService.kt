package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.rest.api.entities.CommandRequest
import ai.platon.pulsar.rest.api.entities.PromptRequest
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class LoadService {

    private val logger = LoggerFactory.getLogger(LoadService::class.java)

    @Autowired
    lateinit var session: AgenticSession

    fun load(url: String): WebPage {
        return session.load(url)
    }

    fun loadDocument(url: String, args: String? = null): Pair<WebPage, FeaturedDocument> {
        if (url.contains(":8182/")) {
            logger.warn("Unexpected url, internal url is not allowed | {}", url)
            return GoraWebPage.NIL to FeaturedDocument.NIL
        }

        val page = session.load(url, args ?: "")
        val document = session.parse(page, noCache = true)

        return page to document
    }

    fun loadDocument(request: PromptRequest): Pair<WebPage, FeaturedDocument> {
        val args = request.args ?: ""
        val options = session.options(args)
        val be = options.eventHandlers.browseEventHandlers

        val actions = request.actions
        if (actions != null) {
            be.onDocumentFullyLoaded.addLast { page, driver ->
                actions.forEach { session.act(it) }
            }
        }

        val page = session.load(request.url, options)
        val document = session.parse(page)

        return page to document
    }

    fun loadDocument(request: CommandRequest, eventHandlers: PageEventHandlers): Pair<WebPage, FeaturedDocument> {
        val args = request.enhanceArgs()
        val options = session.options(args, eventHandlers)

        val be = options.eventHandlers.browseEventHandlers

        request.onBrowserLaunchedActions?.let { actions -> be.onBrowserLaunched.addLast { page, driver ->
            actions.forEach { session.act(it) }
        } }

        request.onPageReadyActions?.let { actions -> be.onDocumentFullyLoaded.addLast { page, driver ->
            actions.forEach { session.act(it) }
        } }

//        request.actionsOnDidInteract?.let { actions -> be.onDidInteract.addLast { page, driver ->
//            actions.forEach { driver.instruct(it) }
//        } }

        val page = session.load(request.url, options)
        val document = session.parse(page)

        return page to document
    }
}
