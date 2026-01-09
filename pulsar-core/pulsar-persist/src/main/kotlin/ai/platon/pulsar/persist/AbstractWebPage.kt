package ai.platon.pulsar.persist

import ai.platon.pulsar.common.InProcessIdGenerator
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.common.urls.URLUtils.mergeUrlArgs
import ai.platon.pulsar.common.urls.URLUtils.reverseUrlOrEmpty
import ai.platon.pulsar.common.urls.URLUtils.unreverseUrl
import ai.platon.pulsar.persist.metadata.Name
import ai.platon.pulsar.persist.model.WebPageFormatter
import org.apache.gora.util.ByteUtils
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.util.*
import kotlin.concurrent.Volatile

/**
 * The core web page structure
 */
abstract class AbstractWebPage(
    /**
     * The url is the permanent internal address, while the location is the last working address.
     */
    override val url: String,
    /**
     * A webpage scope configuration, any modifications made to it will exclusively impact this particular webpage.
     */
    override var conf: VolatileConfig
) : WebPage {
    companion object {
        // The ID_SEQUENCER is an AtomicInteger initialized to 10 to avoid conflicts with the default ID of 0.
        private val ID_SEQUENCER = InProcessIdGenerator()

        /**
         * Returns the URL based on whether it should be reversed or not.
         *
         * @param urlOrKey The original URL or key.
         * @param urlReversed A flag indicating if the URL should be reversed.
         * @return The processed URL.
         */
        protected fun getUrl(urlOrKey: String, urlReversed: Boolean): String {
            return if (urlReversed) unreverseUrl(urlOrKey) else urlOrKey
        }
    }

    /**
     * A process scope page id.
     */
    /**
     * The page id which is unique in process scope.
     */
    override var id: Long = ID_SEQUENCER.nextId()
        protected set

    val reversedUrl get() = reverseUrlOrEmpty(url)

    /**
     * The reversed url of the web page, it's also the key of the underlying storage of this webpage.
     * It's faster to retrieve the page by the reversed url.
     */
    override val key: String get() = reversedUrl

    /**
     * Web page scope variables
     */
    val variables: Variables = Variables()

    /**
     * Store arbitrary data associated with the webpage.
     */
    private val data = Variables()

    /**
     * The page datum for update.
     * Page datum is collected during the fetch phrase and is used to update the page in the update phase.
     */
    var pageDatum: PageDatum? = null

    /**
     * If this page is fetched from Internet
     */
    override var isCached: Boolean = false

    /**
     * If this page is loaded from database or is created and fetched from the web
     */
    override var isLoaded: Boolean = false

    /**
     * If this page is fetched from Internet
     */
    override var isFetched: Boolean = false

    /**
     * Check if the page is canceled.
     *
     *
     * If a page is canceled, it should not be updated.
     */
    /**
     * Check if the page is canceled.
     *
     *
     * If a page is canceled, it should not be updated.
     */
    /**
     * If this page is canceled
     */
    override var isCanceled: Boolean = false

    /**
     * If this page is fetched and updated
     */
    @Volatile
    override var isContentUpdated: Boolean = false
        protected set

    /**
     * Get the cached content
     */
    /**
     * Set the cached content, keep the persisted page content unmodified
     */
    /**
     * The cached content.
     * TODO: use a loading cache for all cached page contents.
     */
    @Volatile
    override var tmpContent: ByteBuffer? = null

    /**
     * The delay time to retry if a retry is needed
     */
    override var retryDelay: Duration = Duration.ZERO

    override var href: String?
        /**
         * Get The hypertext reference of this page.
         * It defines the address of the document, which this time is linked from
         *
         *
         * TODO: use a separate field for href
         *
         * @return The hypertext reference
         */
        get() = metadata[Name.HREF]
        /**
         * Set The hypertext reference of this page.
         * It defines the address of the document, which this time is linked from
         *
         * @param href The hypertext reference
         */
        set(href) {
            metadata[Name.HREF] = href
        }

    override val isNotNil: Boolean
        get() = !isNil

    override val isInternal: Boolean
        get() = URLUtils.isInternal(url)

    override val isNotInternal: Boolean
        get() = !isInternal

    /**
     * Check if the page scope temporary variable with `name` exists
     *
     * @param name The variable name to check
     * @return true if the variable exist
     */
    fun hasVar(name: String): Boolean {
        return variables.contains(name)
    }

    /**
     * Returns the page scope temporary variable to which the specified name is mapped,
     * or `null` if the local variable map contains no mapping for the name.
     *
     * @param name the name whose associated value is to be returned
     * @return the value to which the specified name is mapped, or
     * `null` if the local variable map contains no mapping for the key
     */
    fun getVar(name: String): Any? {
        return variables[name]
    }

    /**
     * Retrieves and removes the local variable with the given name.
     */
    fun removeVar(name: String): Any? {
        return variables.remove(name)
    }

    /**
     * Set a page scope temporary variable.
     *
     * @param name  The variable name.
     * @param value The variable value.
     */
    fun setVar(name: String, value: Any) {
        variables[name] = value
    }

    /**
     * Returns the bean to which the specified class is mapped,
     * or `null` if the local bean map contains no mapping for the class.
     *
     * @param clazz the class of the variable
     */
    override fun getBean(clazz: Class<*>): Any {
        val bean = getBeanOrNull(clazz) ?: throw NoSuchElementException("No bean found for class $clazz in WebPage")
        return bean
    }

    /**
     * Returns the data to which the specified class is mapped,
     * or `null` if the local bean map contains no mapping for the class.
     *
     * @param clazz the class of the variable
     */
    override fun getBeanOrNull(clazz: Class<*>): Any? {
        return variables[clazz.name]
    }

    /**
     * Set a page scope temporary java bean.
     */
    override fun <T> putBean(bean: T) {
        variables.set(bean!!::class.java.name, bean)
    }

    /**
     * Returns the data to which the specified name is mapped,
     * or `null` if the data map contains no mapping for the name.
     *
     * @param name the name whose associated value is to be returned
     * @return the value to which the specified name is mapped, or
     * `null` if the local variable map contains no mapping for the key
     */
    override fun data(name: String): Any? {
        return data[name]
    }

    /**
     * Store arbitrary data associated with the webpage.
     *
     * @param name  A string naming the piece of data to set.
     * @param value The new data value.
     */
    override fun data(name: String, value: Any?) {
        if (value == null) {
            data.remove(name)
        } else {
            data[name] = value
        }
    }

    override val configuredUrl: String
        get() = mergeUrlArgs(url, args)

    override val contentAsBytes get() = getContentAsBytes0()

    override val contentAsString get() = getContentAsString0()

    override val contentAsInputStream get() = getContentAsInputStream0()

    override val contentAsSaxInputSource get() = getContentAsSaxInputSource0()

    override fun hashCode(): Int {
        return url.hashCode()
    }

    override fun compareTo(other: PageSnapshot): Int {
        return url.compareTo(other.url)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        return other is WebPage && other.url == url
    }

    override fun toString(): String {
        return WebPageFormatter(this).format()
    }

    private fun getContentAsBytes0(): ByteArray {
        val content = content ?: return ByteUtils.toBytes('\u0000')
        return ByteUtils.toBytes(content)
    }

    private fun getContentAsString0(): String {
        val buffer = content
        if (buffer == null || buffer.remaining() == 0) {
            return ""
        }

        return String(buffer.array(), buffer.arrayOffset(), buffer.limit())
    }

    private fun getContentAsInputStream0(): ByteArrayInputStream {
        val contentInOctets = content ?: return ByteArrayInputStream(ByteUtils.toBytes('\u0000'))

        return ByteArrayInputStream(
            content!!.array(),
            contentInOctets.arrayOffset() + contentInOctets.position(),
            contentInOctets.remaining()
        )
    }

    private fun getContentAsSaxInputSource0(): InputSource {
        val inputSource = InputSource(contentAsInputStream)
        val encoding = encoding
        if (encoding != null) {
            inputSource.encoding = encoding
        }
        return inputSource
    }
}
