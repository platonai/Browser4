package ai.platon.pulsar.skeleton.crawl.common.url

import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers

/**
 * A url that contains a [PageEventHandlers] to handle page events.
 * */
interface ListenableUrl: UrlAware {
    val eventHandlers: PageEventHandlers
}
