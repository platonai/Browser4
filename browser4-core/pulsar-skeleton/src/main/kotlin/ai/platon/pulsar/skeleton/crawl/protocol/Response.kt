
package ai.platon.pulsar.skeleton.crawl.protocol

import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.WebPage

/**
 * A response interface. Makes all protocols model HTTP
 */
abstract class Response(
        val page: WebPage,
        val pageDatum: PageDatum
) {
    /** The permanent internal address */
    val url get() = page.url
    /**
     * The protocol status without translation
     * */
    val protocolStatus get() = pageDatum.protocolStatus
    val headers get() = pageDatum.headers
    /** The protocol's response code, it must be compatible with standard http response code */
    val httpCode get() = protocolStatus.minorCode
    val length get() = pageDatum.contentLength

    /** The value of a named header. */
    fun getHeader(name: String): String? = pageDatum.headers[name]
}
