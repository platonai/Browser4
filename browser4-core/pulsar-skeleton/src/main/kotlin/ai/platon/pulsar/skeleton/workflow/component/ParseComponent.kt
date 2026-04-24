package ai.platon.pulsar.skeleton.workflow.component

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import ai.platon.pulsar.skeleton.workflow.parse.PageParser
import ai.platon.pulsar.skeleton.workflow.parse.ParseResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * The parse component.
 */
class ParseComponent(
    val pageParser: PageParser,
    val globalCacheFactory: GlobalCacheFactory,
    val conf: ImmutableConfig
) {
    companion object {
        val numParses = AtomicInteger()
        val numParsed = AtomicInteger()
    }

    constructor(globalCacheFactory: GlobalCacheFactory, conf: ImmutableConfig) : this(
        PageParser(conf),
        globalCacheFactory,
        conf
    )

    fun parse(page: WebPage): ParseResult {
        beforeParse(page)
        return pageParser.parse(page).also { afterParse(page, it) }
    }

    private fun beforeParse(page: WebPage) {
        numParses.incrementAndGet()
    }

    private fun afterParse(page: WebPage, result: ParseResult) {
        require(page is GoraWebPage)

        val document = result.document
        if (document != null) {
            globalCacheFactory.globalCache.documentCache.putDatum(page.url, document)
        }

        numParsed.incrementAndGet()
    }
}
