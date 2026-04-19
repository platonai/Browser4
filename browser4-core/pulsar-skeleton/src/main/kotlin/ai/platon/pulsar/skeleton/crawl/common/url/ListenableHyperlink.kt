package ai.platon.pulsar.skeleton.crawl.common.url

import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import ai.platon.pulsar.skeleton.crawl.event.impl.PageEventHandlersFactory

/**
 * A hyperlink that contains a [PageEventHandlers] to handle page events.
 * */
open class ListenableHyperlink(
    /**
     * The url specification of the hyperlink, it is usually normalized, and can contain load arguments.
     * */
    url: String,
    /**
     * The anchor text is not always available and is not a required field.
     * It can easily be filled by args by mistake, so we require you to fill this field in the current version.
     * We plan to move this field to a later position in future versions.
     * */
    text: String,
    /**
     * The order of this hyperlink in it referrer page
     * */
    order: Int = 0,
    /**
     * The url of the referrer page
     * */
    referrer: String? = null,
    /**
     * The additional url arguments
     * */
    args: String? = null,
    /**
     * The hypertext reference, It defines the address of the document, which this time is linked from
     * */
    href: String? = null,
    /**
     * The priority of this hyperlink
     * */
    priority: Int = 0,
    /**
     * The language of this hyperlink, reserved
     * */
    lang: String = "*",
    /**
     * The country of this hyperlink, reserved
     * */
    country: String = "*",
    /**
     * The district of this hyperlink, reserved
     * */
    district: String = "*",
    /**
     * The maximum number of retries
     * */
    nMaxRetry: Int = 3,
    /**
     * The depth of this hyperlink
     * */
    depth: Int = 0,
    /**
     * The event handler
     * */
    override var eventHandlers: PageEventHandlers = PageEventHandlersFactory().create(),
): Hyperlink(url, text, order, referrer, args, href, priority, lang, country, district, nMaxRetry, depth),
    ListenableUrl {
    /**
     * A listenable url is not a persistence object because the event handler is not persistent
     * */
    override val isPersistable: Boolean = false
    
    constructor(link: Hyperlink): this(link.url, link.text, link.order, link.referrer, link.args, link.href)

    companion object {

        @JvmStatic
        fun create(url: String): ListenableHyperlink {
            return ListenableHyperlink(url, "")
        }
    }
}
