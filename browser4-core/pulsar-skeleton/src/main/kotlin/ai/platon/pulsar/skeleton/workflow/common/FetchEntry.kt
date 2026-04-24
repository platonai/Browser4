package ai.platon.pulsar.skeleton.workflow.common

import ai.platon.pulsar.common.PulsarParams
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL

class FetchEntry(val page: WebPage, val options: LoadOptions) {

    constructor(url: String, options: LoadOptions, href: String? = null) :
            this(createPageShell(url, options, href), options)

    companion object {

        fun createPageShell(normURL: NormURL): WebPage {
            val page = createPageShell(normURL.urlString, normURL.options, normURL.hrefSpec, normURL.referrer)
            val depth = normURL.detail?.depth ?: 0
            if (depth > 0) {
                page.distance = depth
            }
            return page
        }

        fun createPageShell(url: String, conf: VolatileConfig, href: String? = null, referrer: String? = null): WebPage {
            val page = GoraWebPage.newWebPage(url, conf, href)
            initWebPage(page, null, href, referrer)
            return page
        }

        fun createPageShell(url: String, options: LoadOptions, href: String? = null, referrer: String? = null): WebPage {
            val page = GoraWebPage.newWebPage(url, options.conf, href)
            initWebPage(page, options, href, referrer)
            return page
        }

        fun initWebPage(page: WebPage, options: LoadOptions?, href: String? = null, referrer: String? = null) {
            page.href = href
            if (referrer != null) {
                page.referrer = referrer
            }
            if (options != null) {
                page.args = options.toString()
                page.fetchMode = options.fetchMode
                page.conf = options.conf
                page.maxRetries = options.nMaxRetry
                page.isResource = options.isResource

                // since LoadOptions is not visible by WebPage, we use an unsafe method to pass the load options
                if (page is AbstractWebPage) {
                    page.setVar(PulsarParams.VAR_LOAD_OPTIONS, options)
                }
            }
        }
    }
}
