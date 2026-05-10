package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.common.config.AppConstants.PULSAR_DOCUMENT_NORMALIZED_URI
import ai.platon.pulsar.common.config.AppConstants.PULSAR_META_INFORMATION_SELECTOR
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.dom.select.selectFirstOrNull
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.persist.ext.options
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by Vincent on 16-9-14.
 */
class JsoupParser(
    private val page: WebPage,
    private val conf: ImmutableConfig
) {
    companion object {
        val numJsoupParses = AtomicInteger()
        val numJsoupParsed = AtomicInteger()
    }

    val LOG = LoggerFactory.getLogger(JsoupParser::class.java)

    var document = FeaturedDocument.NIL
        private set

    fun parse(): FeaturedDocument {
        numJsoupParses.incrementAndGet()

        try {
            val mutableDocument = Jsoup.parse(page.contentAsInputStream, page.encoding, page.baseURI)
            updateMetaInfos(page, mutableDocument)

            // Calculate features for each node in the constructor
            document = FeaturedDocument(mutableDocument)
            return document
        } catch (e: IOException) {
            LOG.warn("Failed to parse page {}", page.url)
            LOG.warn(e.toString())
        }

        numJsoupParsed.incrementAndGet()

        return document
    }

    private fun updateMetaInfos(page: WebPage, document: org.jsoup.nodes.Document) {
        // the node is created by injected javascript
        val selector = PULSAR_META_INFORMATION_SELECTOR
        val metadata = document.selectFirstOrNull(selector) ?: return

        val urls = mutableMapOf(PULSAR_DOCUMENT_NORMALIZED_URI to page.url)

        page.href?.takeIf { URLUtils.isStandard(it) }?.let {
            urls.put("href", it)
        }
        page.referrer.takeIf { URLUtils.isStandard(it) }?.let {
            urls.put("referrer", it)
        }

        val head = document.head()
        urls.forEach { (rel, href) ->
            head.append("<link rel='$rel' href='$href' />")
            metadata.attr(rel, href)
        }

        // deprecated, use body head link[rel=normalizedURI] instead
        metadata.attr("normURL", page.url)
        // deprecated, use body head link[rel=normalizedURI] instead
        metadata.attr("normalizedUrl", page.url)

        val options = page.options
        metadata.attr("label", options.label)
        metadata.attr("taskId", options.taskId)
        metadata.attr("taskTime", options.taskTime.toString())
    }
}
