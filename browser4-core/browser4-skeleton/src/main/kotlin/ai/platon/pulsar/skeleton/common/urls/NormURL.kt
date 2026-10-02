package ai.platon.pulsar.skeleton.common.urls

import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import java.net.MalformedURLException
import java.net.URI
import java.net.URL

/**
 * [NormURL] stands for `normal url`, which means the url is final and will be used to locate the resource.
 *
 * Every normal url contains two urls, a `url` and a `href`, and the split between them is the rule the
 * whole pipeline is built on: **the normalized `url` is an identity, the raw `href` is an address.**
 *
 *  * `url` keys the page store, the page cache and every other url-keyed lookup.  It is what
 *    `PulsarSession.normalize` produces, so two spellings of one page produce one key.
 *  * `href` is the url as the document carried it, unmodified, and it is the first choice when
 *    something has to be *opened*: normalization has thrown the fragment away (and a same-document
 *    jump such as `…#section` is a perfectly good request), and it may have thrown the query or a
 *    trailing argument list away as well.
 *
 * A caller that has no `href` still navigates with `url` — `page.href ?: page.url`, which is exactly
 * how `InteractiveBrowserEmulator` resolves a task's address.
 * */
open class NormURL constructor(
    /**
     * The url is final, and it is the identity the resource is stored and looked up by.
     *
     * It is not the address to navigate to — see [href].
     * */
    val url: URL,
    /**
     * The load options to be used to load the resource.
     * */
    val options: LoadOptions,
    /**
     * The href is the raw url in the html without normalization, for example, a url with a timestamp
     * query parameter added.
     *
     * It is the address, and the first choice whenever the resource has to be *opened*, because it is
     * extracted from the HTML document without modification, while [url] is normalized and only
     * identifies the page.
     * */
    var href: URL? = null,
    /**
     * A url aware object that contains the url and its related information.
     * */
    var detail: UrlAware? = null
): Comparable<NormURL> {

    /**
     * Construct a NormURL from a string url and a LoadOptions object.
     * */
    @Throws(MalformedURLException::class)
    constructor(spec: String, options: LoadOptions, hrefSpec: String? = null, detail: UrlAware? = null):
            this(URI.create(spec).toURL(), options, hrefSpec?.let { URI.create(hrefSpec).toURL() }, detail)

    /**
     * The url specification in string format.
     */
    val urlString get() = url.toString()
    /**
     * The href specification in string format.
     */
    val hrefSpec get() = href?.toString()
    /**
     * The load options specification in string format.
     */
    val args get() = options.toString()
    /**
     * The String to parse as a NormURL.
     * */
    val urlSpec get() = "$urlString $args".trim()
    /**
     * The String to parse as a NormURL, the same as [urlSpec].
     */
    val configuredUrl get() = urlSpec
    /**
     * The referrer url specification in string format.
     */
    val referrer get() = options.referrer ?: (detail?.referrer)
    /**
     * Whether the url is nil.
     */
    val isNil get() = urlString == AppConstants.NIL_PAGE_URL
    /**
     * Whether the url is not nil.
     */
    val isNotNil get() = !isNil
    /**
     * The 1st-component, which is the url specification.
     */
    operator fun component1() = url
    /**
     * The 2nd-component, which is the load options.
     */
    operator fun component2() = options
    /**
     * The hash code of the object.
     *
     * @return the hash code of the object
     */
    override fun hashCode(): Int {
        return configuredUrl.hashCode()
    }
    /**
     * Whether the object is equal to this object.
     *
     * @param other the object to be compared
     * @return whether the object is equal to this object
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        return other is NormURL && configuredUrl == other.configuredUrl
    }
    /**
     * Compare this object to another object.
     *
     * @param other the object to be compared
     * @return the comparison result
     */
    override fun compareTo(other: NormURL) = configuredUrl.compareTo(other.configuredUrl)
    /**
     * The string representation of the object.
     *
     * @return the string representation of the object
     */
    override fun toString() = configuredUrl

    companion object {
        /**
         * Create a nil NormURL object.
         */
        fun createNil(detail: UrlAware? = null) = NormURL(AppConstants.NIL_PAGE_URL, LoadOptions.DEFAULT, detail = detail)
        /**
         * Parse a configured url to a NormURL object.
         *
         * @param configuredUrl the configured url to be parsed
         * @param volatileConfig the volatile configuration
         * @return the parsed NormURL object
         */
        @JvmStatic
        fun parse(configuredUrl: String, volatileConfig: VolatileConfig): NormURL {
            val (url, args) = URLUtils.splitUrlArgs(configuredUrl)
            val options = LoadOptions.parse(args, volatileConfig)
            return NormURL(url, options)
        }
    }
}
