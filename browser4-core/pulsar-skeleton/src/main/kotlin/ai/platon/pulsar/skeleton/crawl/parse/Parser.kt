
package ai.platon.pulsar.skeleton.crawl.parse

import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.persist.WebPage
import java.time.Duration

/**
 * A parser for content of Webpages. Multiple media types are supported.
 */
interface Parser : Parameterized {
    val timeout: Duration

    /**
     * This method parses content in WebPage instance
     * @param page
     */
    fun parse(page: WebPage): ParseResult
}
