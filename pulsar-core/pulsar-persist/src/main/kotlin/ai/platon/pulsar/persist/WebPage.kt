package ai.platon.pulsar.persist

import ai.platon.pulsar.common.HtmlIntegrity
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.persist.metadata.FetchMode
import ai.platon.pulsar.persist.metadata.OpenPageCategory
import ai.platon.pulsar.persist.model.ActiveDOMMetadata
import ai.platon.pulsar.persist.model.ActiveDOMStat
import ai.platon.pulsar.persist.model.ActiveDOMStatus
import ai.platon.pulsar.persist.model.PageModel
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Represents a static web page in the Browser4 system. This interface provides methods to access and manipulate
 * various properties and metadata associated with a web page, such as its content, headers, fetch status,
 * and more. It also includes methods for managing beans, and other page-related data.
 */
interface WebPage : PageSnapshot {
    /**
     * The key associated with the web page, typically used for indexing or identification purposes.
     * The key is defined as the reserved url, for example, the key for page TestResourceUtil.PRODUCT_DETAIL_URL
     * is "uk.co.amazon.www:https/dp/B0E000001".
     */
    val key: String

    /**
     * The page scope configuration, which is expected to be modified frequently.
     */
    var conf: VolatileConfig

    /**
     * Indicates whether the web page is stored in the in-memory cache.
     */
    var isCached: Boolean

    /**
     * Indicates whether the web page is loaded from persistent storage.
     */
    var isLoaded: Boolean

    /**
     * Indicates whether the web page is fetched from the Internet.
     */
    var isFetched: Boolean

    /**
     * Indicates whether the web page is canceled by the user.
     */
    var isCanceled: Boolean

    /**
     * Indicates whether the content of the web page has been updated.
     */
    val isContentUpdated: Boolean

    /**
     * The delay time before the next retry to load the web page.
     */
    var retryDelay: Duration

    /**
     * The maximum number of retries to load the web page.
     */
    var maxRetries: Int

    /**
     * The time zone of the web page.
     */
    var zoneId: ZoneId

    /**
     * The distance of the web page from the root page.
     */
    var distance: Int

    /**
     * The fetch mode of the web page.
     */
    var fetchMode: FetchMode

    /**
     * The integrity of the web page, used to check if the page is valid.
     */
    var htmlIntegrity: HtmlIntegrity

    /**
     * The number of times the web page has been fetched.
     */
    var fetchCount: Int

    /**
     * The time the page to be fetched.
     */
    var fetchTime: Instant

    /**
     * The number of times the web page has been retried to fetch.
     */
    var fetchRetries: Int

    /**
     * The previous time when the web page was fetched.
     */
    var prevFetchTime: Instant

    /**
     * The interval between the previous fetch and the current fetch.
     */
    var fetchInterval: Duration

    /**
     * The time when the web page was last modified.
     */
    var modifiedTime: Instant

    /**
     * The previous time when the web page was modified.
     */
    var prevModifiedTime: Instant

    /**
     * The content category of the web page.
     */
    var pageCategory: OpenPageCategory

    /**
     * The temporary content of the web page, stored as a `ByteBuffer`.
     */
    var tmpContent: ByteBuffer?

    /**
     * The content of the web page, stored as a `ByteBuffer` for persistence.
     */
    val persistContent: ByteBuffer?

    /**
     * The content of the web page, stored as a `ByteArrayInputStream`.
     */
    val contentAsInputStream: ByteArrayInputStream

    /**
     * The content of the web page, stored as an `InputSource` for SAX parsing.
     */
    val contentAsSaxInputSource: InputSource?

    /**
     * The length of the persisted content of the web page.
     */
    var persistedContentLength: Long

    /**
     * The length of the last version of the content of the web page.
     */
    val lastContentLength: Long

    /**
     * The average length of all versions of the content of the web page.
     */
    val aveContentLength: Long

    /**
     * The previous signature of the web page, stored as a `ByteBuffer`.
     */
    var prevSignature: ByteBuffer?

    /**
     * The previous signature of the web page, stored as a `String`.
     */
    val prevSignatureAsString: String

    /**
     * The current signature of the web page, stored as a `ByteBuffer`.
     */
    val signature: ByteBuffer?

    /**
     * The current signature of the web page, stored as a `String`.
     */
    val signatureAsString: String

    /**
     * The title of the web page.
     */
    var pageTitle: String?

    /**
     * The title of the content of the web page.
     */
    var contentTitle: String?

    /**
     * The text of the web page, typically extracted by combining the texts within all HTML tags.
     */
    var pageText: String?

    /**
     * The text of the content of the web page, typically extracted using some algorithm.
     */
    val contentText: String?

    /**
     * The parse status of the web page.
     */
    var parseStatus: ParseStatus

    /**
     * The links contained within the web page.
     */
    var links: MutableList<CharSequence>

    /**
     * The vivid links contained within the web page, typically change frequently.
     */
    val vividLinks: MutableMap<CharSequence, CharSequence>

    /**
     * The links that point to this web page.
     */
    val inlinks: MutableMap<CharSequence, CharSequence>

    var anchor: CharSequence?

    var pageModelUpdateTime: Instant?

    var pageModel: PageModel?

    /**
     * Retrieves the bean of the specified class type associated with the web page.
     *
     * @param clazz The class type of the bean to retrieve.
     * @return The bean instance.
     */
    fun getBean(clazz: Class<*>): Any

    /**
     * Retrieves the bean of the specified class type associated with the web page, or `null` if it does not exist.
     *
     * @param clazz The class type of the bean to retrieve.
     * @return The bean instance, or `null` if it does not exist.
     */
    fun getBeanOrNull(clazz: Class<*>): Any?

    /**
     * Associates the given bean with the web page.
     *
     * @param bean The bean to associate with the web page.
     */
    fun <T> putBean(bean: T)

    /**
     * Retrieves the data associated with the given name.
     *
     * @param name The name of the data to retrieve.
     * @return The data value, or `null` if it does not exist.
     */
    fun data(name: String): Any?

    /**
     * Sets the data associated with the given name.
     *
     * @param name The name of the data to set.
     * @param value The value to assign to the data.
     */
    fun data(name: String, value: Any?)

    /**
     * Sets the content of the web page using a `String`.
     *
     * @param value The content to set.
     */
    fun setStringContent(value: String?)

    /**
     * Sets the content of the web page using a `ByteArray`.
     *
     * @param value The content to set.
     */
    fun setByteArrayContent(value: ByteArray?)

    /**
     * Sets the content of the web page using a `ByteBuffer`.
     *
     * @param value The content to set.
     */
    fun setByteBufferContent(value: ByteBuffer?)

    /**
     * Clears the persisted content of the web page.
     */
    fun clearPersistContent()

    fun ensurePageModel(): PageModel
}
