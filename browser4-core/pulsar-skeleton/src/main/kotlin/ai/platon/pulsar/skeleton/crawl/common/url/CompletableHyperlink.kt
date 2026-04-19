package ai.platon.pulsar.skeleton.crawl.common.url

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.options.OptionUtils
import ai.platon.pulsar.common.urls.StatefulUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.common.urls.URLUtils
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.concurrent.CompletableFuture

open class CompletableHyperlink<T>(
    /**
     * The url specification of the hyperlink, it is usually normalized, and can contain load arguments.
     * */
    override var url: String,
    /**
     * The anchor text
     * */
    override var text: String = "",
    /**
     * The order of this hyperlink in it referrer page
     * */
    override var order: Int = 0,
    /**
     * The url of the referrer page
     * */
    override var referrer: String? = null,
    /**
     * The additional url arguments
     * */
    override var args: String? = null,
    /**
     * The hypertext reference, It defines the address of the document, which this time is linked from
     * */
    override var href: String? = null,
    /**
     * The priority of this hyperlink
     * */
    override var priority: Int = 0,
    /**
     * The language of this hyperlink
     * */
    override var lang: String = "*",
    /**
     * The country of this hyperlink
     * */
    override var country: String = "*",
    /**
     * The district of this hyperlink
     * */
    override var district: String = "*",
    /**
     * The maximum number of retries
     * */
    override var nMaxRetry: Int = 3,
    /**
     * The depth of this hyperlink
     * */
    override var depth: Int = 0
): UrlAware, Comparable<UrlAware>, StatefulUrl, CompletableFuture<T>() {

    override val configuredUrl get() = URLUtils.mergeUrlArgs(url, args)

    override val isStandard get() = URLUtils.isStandard(url)

    @get: Throws(MalformedURLException::class)
    override val toURL get() = URI.create(url).toURL()

    override val toURLOrNull get() = URLUtils.getURLOrNull(url)

    override val isNil: Boolean get() = url == AppConstants.NIL_PAGE_URL

    /**
     * If this link is persistable
     * */
    override val isPersistable: Boolean = false

    override val label: String get() = OptionUtils.findOption(args, listOf("-l", "-label", "--label")) ?: ""

    override val deadline: Instant
        get() {
            val deadTime = OptionUtils.findOption(args, listOf("-deadTime", "--dead-time")) ?: ""
            return DateTimes.parseBestInstantOrNull(deadTime) ?: DateTimes.doomsday
        }

    override var authToken: String? = null
    override var remoteAddr: String? = null
    override var status: Int = ResourceStatus.SC_CREATED
    override var modifiedAt: Instant = Instant.now()
    override val createdAt: Instant = Instant.now()

    /**
     * An abstract url can compare to one of the following types:
     * 1. a [String]
     * 2. a [URL]
     * 3. a [UrlAware]
     * */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        return when (other) {
            is String -> url == other
            is URL -> url == other.toString()
            is UrlAware -> url == other.url
            else -> false
        }
    }

    override fun hashCode() = url.hashCode()

    override fun compareTo(other: UrlAware): Int {
        return url.compareTo(other.url)
    }

    override fun toString() = url
}
