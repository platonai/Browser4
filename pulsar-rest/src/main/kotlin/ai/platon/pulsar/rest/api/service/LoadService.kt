package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
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
}
