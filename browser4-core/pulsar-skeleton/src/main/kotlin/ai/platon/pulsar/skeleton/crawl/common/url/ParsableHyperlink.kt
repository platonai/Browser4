package ai.platon.pulsar.skeleton.crawl.common.url

import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import java.util.function.BiConsumer

/**
 * A hyperlink that contains a `onParse` event handler. The `onParse` event handler
 * will be called when the page is parsed.
 * */
open class ParsableHyperlink(
    /**
     * The url of this hyperlink
     * */
    url: String,
    /**
     * A event handler that will be called when the page is parsed.
     * */
    val onParse: (WebPage, FeaturedDocument) -> Any?
): Hyperlink(url, "", args = "-parse"), ListenableUrl {

    /**
     * Java compatible constructor
     * */
    constructor(url: String, onParse: BiConsumer<WebPage, FeaturedDocument>):
            this(url, { page, document -> onParse.accept(page, document) })

    /**
     * The PageEvent handlers of this hyperlink.
     * */
    override var eventHandlers: PageEventHandlers = DefaultPageEventHandlers().also {
        it.loadEventHandlers.onHTMLDocumentParsed.addLast { page, document ->
            onParse(page, document)
        }
    }
}
