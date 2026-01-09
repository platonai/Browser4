package ai.platon.pulsar.persist

import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.persist.model.ActiveDOMMetadata
import ai.platon.pulsar.persist.model.ActiveDOMStat
import ai.platon.pulsar.persist.model.ActiveDOMStatus
import java.nio.ByteBuffer
import java.time.Instant

/**
 * Represents a static web page in the Browser4 system. This interface provides methods to access and manipulate
 * various properties and metadata associated with a web page, such as its content, headers, fetch status,
 * and more. It also includes methods for managing beans, and other page-related data.
 *
 * This interface extends `Comparable<PageSnapshot>` to allow for comparison between web pages.
 */
interface PageSnapshot : Comparable<PageSnapshot> {
    /**
     * The unique, in-process identifier of the web page.
     */
    val id: Long

    /**
     * The url is the permanent internal address, and it's also the storage key (reserved).
     * The url can differ from the original url passed by the user, because the original url might be normalized,
     * and the url also can differ from the final location of the page, because the page can be redirected in the browser.
     */
    val url: String

    /**
     * The href (hypertext reference) of the web page, the href should not be normalized and kept the original form
     * where it extracted from.
     * For example, the href can be extracted from an HTML page:
     * ```html
     * <a href='https://www.amazon.com/dp/B0E000001?th=1'>Huawei P60 ...</a>
     * ```
     */
    var href: String?

    /**
     * A baseUrl is used to resolve relative URLs.
     *
     * The base URL is determined as follows:
     * 1. By default, the base URL is the location of the document
     *    (as determined by window.location).
     * 2. If the document has an `<base>` element, its href attribute is used.
     * */
    var baseURI: String
    /**
     * Returns the document location as a string.
     *
     * [location] is the last working address,
     * it might redirect from the original url, or it might have additional query parameters.
     * [location] can differ from [url].
     *
     * In javascript, the documentURI property can be used on any document types. The document.URL
     * property can only be used on HTML documents.
     *
     * @see <a href='https://www.w3schools.com/jsref/prop_document_documenturi.asp'>
     *     HTML DOM Document documentURI</a>
     * */
    var location: String
    /**
     * The URL of the page that linked to this page.
     */
    var referrer: String?

    /**
     * The load arguments which can be parsed into a `LoadOptions` object.
     * It's usually used by `session.load()` method series.
     */
    var args: String

    /**
     * The configured URL of the web page, which is always a combination of `url` and `args`.
     */
    val configuredUrl: String

    /**
     * Indicates whether the web page is nil (i.e., not initialized or empty).
     */
    val isNil: Boolean

    /**
     * Indicates whether the web page is not nil (i.e., initialized and contains data).
     */
    val isNotNil: Boolean

    /**
     * Indicates whether the web page is internal (i.e., part of the same domain or system).
     */
    val isInternal: Boolean

    /**
     * Indicates whether the web page is not internal (i.e., external to the domain or system).
     */
    val isNotInternal: Boolean

    /**
     * The last browser used to fetch the web page.
     */
    var lastBrowser: BrowserType

    /**
     * Indicates whether the web page is a resource, which can be fetched using a single request.
     */
    var isResource: Boolean

    /**
     * The time when the web page was created.
     */
    var createTime: Instant

    /**
     * The protocol status of the web page, used to check if the page was fetched successfully.
     */
    var protocolStatus: ProtocolStatus

    /**
     * The metadata of the web page, which will be persisted to storage.
     */
    val metadata: Metadata

    /**
     * The headers of the web page, which will be persisted to storage.
     */
    val headers: ProtocolHeaders

    /**
     * The encoding of the web page, which is UTF-8 by default.
     */
    var encoding: String?

    /**
     * The content type of the web page, which is `text/html` by default.
     */
    var contentType: String

    /**
     * The content of the web page, stored as a `ByteBuffer`.
     */
    val content: ByteBuffer?

    /**
     * The content of the web page, stored as a `ByteArray`.
     */
    val contentAsBytes: ByteArray

    /**
     * The content of the web page, stored as a `String`.
     */
    val contentAsString: String

    /**
     * The length of the content of the web page.
     */
    val contentLength: Long

    /**
     * The original length of the content of the web page.
     */
    var originalContentLength: Long

    /**
     * The proxy used to fetch the web page.
     */
    var proxy: String?

    /**
     * The active DOM status of the web page.
     */
    var activeDOMStatus: ActiveDOMStatus?

    /**
     * The trace of active DOM status changes for the web page.
     */
    var activeDOMStatTrace: Map<String, ActiveDOMStat?>

    /**
     * The metadata of the active DOM for the web page.
     */
    var activeDOMMetadata: ActiveDOMMetadata?
}
