package ai.platon.pulsar.skeleton.common.urls

import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.workflow.common.url.ListenableUrl
import ai.platon.pulsar.skeleton.workflow.filter.ChainedUrlNormalizer
import org.slf4j.LoggerFactory

class CombinedUrlNormalizer(private val urlNormalizers: ChainedUrlNormalizer? = null) {

    private val logger = LoggerFactory.getLogger(CombinedUrlNormalizer::class.java)

    /**
     * Normalize a url.
     *
     * If both url arguments and [LoadOptions] are present, the url arguments overrides the [LoadOptions].
     *
     * @param url the url to be normalized
     * @param options the options to be used
     * @param toItemOption whether to create item options
     * @return the normalized url, or a NIL NormURL if the url is invalid
     * */
    fun normalize(url: UrlAware, options: LoadOptions, toItemOption: Boolean): NormURL {
        val (spec, args1) = URLUtils.splitUrlArgs(url.url)
        val args2 = url.args ?: ""
        val args3 = options.toString()
        // args1 has the #1 priority, and then args2, and at last args3.
        // the later args overwrite the earlier ones.
        val finalArgs = "$args3 $args2 $args1".trim()
        val finalOptions = createLoadOptions(url, LoadOptions.parse(finalArgs, options), toItemOption)

        if (!finalOptions.isDefault("priority")) {
            url.priority = finalOptions.priority
        }
        val href = url.href?.let { URLUtils.splitUrlArgs(it).first }?.takeIf { URLUtils.isStandard(it) }

        var normURL: String? = spec
        // Both flags are read from [finalOptions], never from the incoming [options]: the argument
        // list merged above has the documented priority over them, and reading the caller's copy
        // silently ignored `-noNorm` / `-ignoreUrlQuery` written next to the url itself
        // (`"$url -noNorm"`, `UrlAware.args`, the args `buildLinkArgs` puts on a discovered link).
        if (!finalOptions.noNorm) {
            val rawEvent = finalOptions.rawEvent
            normURL = if (rawEvent?.loadEventHandlers?.onNormalize?.isNotEmpty == true) {
                // 1. normalizer in event listener has the #1 priority.
                val spec1 = PulsarEventBus.pageEventHandlers?.loadEventHandlers?.onNormalize?.invoke(spec) ?: spec
                // The more specific handlers has the opportunity to override the result of more general handlers.
                rawEvent.loadEventHandlers.onNormalize(spec1)
                    ?: return nil(url, spec, "rejected by the load event handlers of this url")
            } else {
                // 2. global normalizers has the #2 priority
                val normalizers = urlNormalizers
                if (normalizers != null) {
                    normalizers.normalize(spec)
                        ?: return nil(url, spec, "rejected by a registered url normalizer")
                } else spec
            }

            // 3. UrlUtils.normalize comes at last to remove the fragment from the url token.
            //
            // It is deliberately NOT given `-ignoreUrlQuery`: that flag is an option for
            // *discovered* links (see AbstractPulsarSession.parseNormalizedLink,
            // CrawlSupport.selectDiscoveredLinks and buildLinkArgs), while this call shapes the url
            // the caller asked to *load*. Stripping the query here made a crawl fetch
            // `.../search` where the seed said `.../search?q=x&page=3` while the result row still
            // reported the seed — the wrong document under the right URL. It also collapsed every
            // `http://localfile.internal?path=<base64>` url (and every `browser.internal?url=...`)
            // onto a single page-store and page-cache key.
            normURL = URLUtils.normalizeOrNull(normURL)
        }

        val normalized = normURL ?: return nil(url, spec, "the url has no normal form")
        return runCatching { NormURL(normalized, finalOptions, href, detail = url) }
            .getOrElse { nil(url, spec, "the url cannot be parsed: ${it.message}") }
    }

    /**
     * The NIL a url normalizes to, logged together with the reason and the url that caused it.
     *
     * Everything that consumes a [NormURL] turns NIL into an empty page, so the cause has to be
     * said out loud here — a caller cannot tell "the server returned nothing" from "this url was
     * never loadable". A browser-internal url (`about:blank`, `chrome://...`) is expected to have
     * no normal form and stays at debug so the warning keeps meaning something.
     */
    private fun nil(url: UrlAware, spec: String, reason: String): NormURL {
        if (isBrowserInternalUrl(spec)) {
            logger.debug("Url is normalized to NIL | {} | {}", spec, reason)
        } else {
            logger.warn("Url is normalized to NIL and will not be loaded, {} | {} | {}", reason, spec, url.url)
        }
        return NormURL.createNil(url)
    }

    /**
     * True for the urls that identify a browser resource rather than a fetchable document; they
     * have no normal form by construction, not by mistake.
     */
    private fun isBrowserInternalUrl(spec: String): Boolean {
        return URLUtils.isBrowserURL(spec) || NON_HIERARCHICAL_SCHEMES.any { spec.startsWith(it) }
    }

    internal fun createLoadOptions(url: UrlAware, options: LoadOptions, toItemOption: Boolean = false): LoadOptions {
        val options2 = if (toItemOption) options.createItemOptions() else options
        val options3 = createLoadOptions0(url, options2)

        options3.overrideConfiguration()

        return options3
    }

    internal fun createLoadOptions0(url: UrlAware, options: LoadOptions): LoadOptions {
        val clone = options.clone()

        require(options.rawEvent == clone.rawEvent)
        require(options.rawItemEvent == clone.rawItemEvent)
        // require(options.toString() == clone.toString())

        clone.conf.name = clone.label
        clone.nMaxRetry = url.nMaxRetry

        if (url is ListenableUrl) {
            clone.eventHandlers.chain(url.eventHandlers)
        }

        return clone
    }

    companion object {
        /**
         * The schemes that carry no hierarchical `://` authority, and therefore have no normal
         * form. Kept in sync with `AbstractPulsarContext.isNonHierarchicalScheme`.
         */
        private val NON_HIERARCHICAL_SCHEMES =
            listOf("about:", "data:", "blob:", "javascript:", "chrome:", "edge:")
    }
}
