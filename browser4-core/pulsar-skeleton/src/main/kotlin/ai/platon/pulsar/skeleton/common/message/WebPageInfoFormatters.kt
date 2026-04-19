package ai.platon.pulsar.skeleton.common.message

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.PulsarParams.*
import ai.platon.pulsar.common.config.Params
import ai.platon.pulsar.common.emoji.PopularEmoji
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.Name
import ai.platon.pulsar.persist.model.ActiveDOMStat
import ai.platon.pulsar.skeleton.common.persist.ext.options
import ai.platon.pulsar.skeleton.crawl.common.FetchState
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.time.DurationFormatUtils
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant

class FetchStatusFormatter(val page: WebPage) {
    companion object {
        private val df = DecimalFormat("0.0")
    }
    private val prevFetchTime get() = page.prevFetchTime
    private val fetchTime get() = page.fetchTime
    private val fetchInterval get() = page.fetchInterval
    private val distance get() = page.distance
    private val fetchCount get() = page.fetchCount
    private val pageCategory get() = page.pageCategory
    private val url get() = page.url

    override fun toString(): String {
        val pattern = "yyyy-MM-dd HH:mm:ss"
        val fetchTimeString = (DateTimes.format(prevFetchTime, pattern) + "->" + DateTimes.format(fetchTime, pattern)
                + "," + DurationFormatUtils.formatDuration(fetchInterval.toMillis(), "DdTH:mm:ss"))

        val params = Params.of(
                "T", fetchTimeString,
                "DC", "$distance,$fetchCount",
                pageCategory.toPageCategory().symbol(), StringUtils.substring(url, 0, 80)
        ).withKVDelimiter(":")

        return params.formatAsLine()
    }
}

class PageLoadStatusFormatter(
        private val page: WebPage,
        private val prefix: String = "",
        private val withOptions: Boolean = false,
        private val withNormUrl: Boolean = false,
        private val withReferer: Boolean = false,
        private val withSymbolicLink: Boolean = false
) {
    var verboseCount = 200
    private val url get() = page.url
    private val href get() = page.href
    private val location get() = page.location
    private val responseTime get() = page.metadata[Name.RESPONSE_TIME]?:""
    private val proxy get() = page.proxy
    private val protocolStatus get() = page.protocolStatus
    private val activeDOMStatTrace = page.activeDOMStatTrace
    private val m = page.pageModel

    private val taskStatusSymbol: String get() = when {
        prefix.isNotBlank() -> ""
        page.isCanceled -> "${PopularEmoji.CANCELLATION_X} "
        protocolStatus.isFailed -> "${PopularEmoji.BROKEN_HEART} "
        protocolStatus.isSuccess -> "${PopularEmoji.HUNDRED_POINTS} "
        else -> "${PopularEmoji.SKULL_CROSSBONES} "
    }
    private val pageStatusSymbol get() = when {
        page.isCanceled -> PopularEmoji.CANCELLATION_X // canceled
        page.isFetched && page.fetchCount == 1 -> PopularEmoji.LIGHTNING // fetched new
        page.isFetched -> PopularEmoji.CIRCLE_ARROW_1 // fetched, reload
        page.isCached -> PopularEmoji.HOT_BEVERAGE // cached
        page.isLoaded -> PopularEmoji.OPTICAL_DISC   // load from db
        else -> PopularEmoji.BUG  // BUG symbol
    }
    private val pageStatusText get() = when {
        page.isCanceled -> "Canceled"
        page.isFetched && page.fetchCount == 1 -> "New"
        page.isFetched -> "Updated"
        page.isCached -> "Cached"
        page.isLoaded -> "Loaded"
        else -> "Unknown"
    }
    private val pageStatus: String get() = when {
        page.id < verboseCount && page.id % 10 == 0L -> "$pageStatusText $pageStatusSymbol"
        page.id > verboseCount && page.id % verboseCount == 0L -> "$pageStatusText $pageStatusSymbol"
        else -> pageStatusSymbol.toString()
    }
    private val loadMessagePrefix get() = prefix.takeIf { it.isNotEmpty() } ?: pageStatus
    private val category get() = page.pageCategory.toPageCategory().symbol()
    private val fetchReason get() = buildFetchReason()
    private val label = StringUtils.abbreviateMiddle(page.options.label, "..", 20)
    private val formattedLabel get() = if (label.isBlank()) "" else " | $label"
    private val prevFetchTimeBeforeUpdate = checkWebPage(page).getVar(VAR_PREV_FETCH_TIME_BEFORE_UPDATE) as? Instant ?: page.prevFetchTime
    private val prevFetchTimeDuration: Duration get() = Duration.between(prevFetchTimeBeforeUpdate, Instant.now())
    private val prevFetchTimeReport: String get() = when {
        prevFetchTimeDuration.toDays() > 20 * 360 -> ""
        else -> " last fetched ${prevFetchTimeDuration.readable()} ago,"
    }
    private val jsSate: String
        get() {
            val (ni, na, nnm, nst, w, h) = activeDOMStatTrace["lastStat"]?: ActiveDOMStat()
            val divisor = if (page.id < verboseCount) 10 else verboseCount
            val prefix = if (page.id % divisor == 0L) {
                "i/a/nm/st/h:"
            } else ""
            return if (ni + na + nnm + nst + h != 0) {
                String.format("$prefix%d/%d/%d/%d/%d", ni, na, nnm, nst, h)
            } else ""
        }
    private val fieldCount: String get() = when {
        m == null -> ""
        m.numFields == 0 -> ""
        else -> String.format("%d/%d/%d", m.numNonBlankFields, m.numNonNullFields, m.numFields)
    }
    private val proxyFmt get() = if (proxy.isNullOrBlank()) "%s" else " | %s"
    private val jsFmt get() = if (jsSate.isBlank()) "%s" else " | %s"
    private val fetchCount get() = when {
        page.fetchRetries > 0 -> String.format("%d/%d", page.fetchRetries, page.fetchCount)
        else -> String.format("%d", page.fetchCount)
    }
    private val fieldCountFmt get() = if (m == null || m.numFields == 0) "%s" else " | nf:%-10s"
    private val failure get() = when {
        page.isCanceled -> String.format(" %s", page.protocolStatus.reason)
        protocolStatus.isFailed -> String.format(" %s", page.protocolStatus.toString())
        else -> ""
    }
    private val contextName get() = checkWebPage(page).variables[VAR_PRIVACY_CONTEXT_DISPLAY]?.let { " | $it" } ?: ""
    private val additionalStatus: String get() = checkWebPage(page).getVar(VAR_ADD_LOAD_STATUS)?.toString()?.let { " | $it" } ?: ""
    private val symbolicLink get() = AppPaths.uniqueSymbolicLinkForUri(page.url)

    private val formattedMessage = "$prevFetchTimeReport fc:$fetchCount$failure" +
            "$jsFmt$fieldCountFmt$additionalStatus$proxyFmt$contextName$formattedLabel"
    private val fmt get() = "%3d. $taskStatusSymbol$loadMessagePrefix %s $fetchReason got %d %s in %s, $formattedMessage | %s"

    override fun toString(): String {
        return String.format(fmt,
                page.id,
                category,
                page.protocolStatus.minorCode,
                buildContentBytes(),
                DateTimes.readableDuration(responseTime),
                jsSate,
                fieldCount,
                proxy?:"",
                buildLocation()
        )
    }

    private fun checkWebPage(page: WebPage): AbstractWebPage {
        require(page is AbstractWebPage)
        return page
    }

    private fun buildFetchReason(): String {
        require(page is AbstractWebPage)
        val state = page.getVar(VAR_FETCH_STATE) as? CheckState
        val code = state?.code ?: FetchState.DO_NOT_FETCH
        return FetchState.toSymbol(code).takeIf { it.isNotBlank() }?.let { "for $it" } ?: ""
    }

    private fun buildContentBytes(): String {
        var contentLength = if (page.lastContentLength == 0L || page.lastContentLength == page.contentLength) {
            compactFormat(page.contentLength).trim()
        } else {
            compactFormat(page.contentLength).trim() + " <- " + compactFormat(page.lastContentLength).trim()
        }

        if (page.content == null) {
            contentLength = "0 <- $contentLength"
        }

        return if (page.persistedContentLength > 0) {
            contentLength + " [" + PopularEmoji.OPTICAL_DISC + compactFormat(page.persistedContentLength).trim() + "]"
        } else {
            contentLength
        }
    }

    private fun compactFormat(bytes: Long): String {
        return if (bytes == 0L) "0" else Strings.compactFormat(bytes, 7, false)
    }

    private fun buildLocation(): String {
        val expectedLocation = href ?: url
        val redirected = href != null && href != location
        val normalized = href != null && href != url
        var location = if (redirected) location else expectedLocation
        if (withOptions) location += " ${page.args}"
        val readableLocation0 = if (redirected) "[R] $location <- $expectedLocation" else location
        var readableLocation = if (normalized) "[N] $readableLocation0" else readableLocation0
        if (withNormUrl) readableLocation = "$readableLocation <- $url"
        if (withReferer) readableLocation = "$readableLocation <- ${page.referrer}"
        val doWithSymbolicLink = page.isFetched && page.id < verboseCount && withSymbolicLink
        return if (doWithSymbolicLink) "file://$symbolicLink | $readableLocation" else readableLocation
    }
}
