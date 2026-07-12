package ai.platon.pulsar.skeleton.event.impl

import ai.platon.pulsar.browser.rpa.BrowseRPA
import ai.platon.pulsar.browser.rpa.DefaultBrowseRPA
import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.event.*
import org.slf4j.LoggerFactory

/**
 * The default load event handler.
 */
open class DefaultLoadEventHandlers(
    val rpa: BrowseRPA = DefaultBrowseRPA()
) : AbstractLoadEventHandlers()

/**
 * The default browser event handler.
 */
open class DefaultCrawlEventHandlers : AbstractCrawlEventHandlers()

/**
 * The perfect trace browse event handler.
 */
class PerfectTraceBrowseEventHandlers(
    /**
     * The RPA to use.
     * */
    val rpa: BrowseRPA = DefaultBrowseRPA()
) : AbstractBrowseEventHandlers() {
    /**
     * Fire when a browser is launched.
     * */
    override val onBrowserLaunched = WebPageWebDriverEventHandler().also {
        it.addLast { page, driver -> rpa.warnUpBrowser(page, driver) }
    }

    /**
     * Fire when a page is about to fetch.
     * */
    override val onWillFetch = WebPageWebDriverEventHandler().also {
        it.addLast { page, driver ->
            rpa.waitForReferrer(page, driver)
            rpa.waitForPreviousPage(page, driver)
        }
    }
}

/**
 * The empty browse event handler.
 */
class EmptyBrowseEventHandlers(
    /**
     * The RPA to use.
     * */
    val rpa: BrowseRPA = DefaultBrowseRPA()
) : AbstractBrowseEventHandlers() {

}

/**
 * The default browse event handlers.
 */
typealias DefaultBrowseEventHandlers = PerfectTraceBrowseEventHandlers

/**
 * The default page event handlers.
 */
open class DefaultPageEventHandlers(
    loadEventHandlers: LoadEventHandlers = DefaultLoadEventHandlers(),
    browseEventHandlers: BrowseEventHandlers = DefaultBrowseEventHandlers(),
    crawlEventHandlers: CrawlEventHandlers = DefaultCrawlEventHandlers()
) : AbstractPageEventHandlers(loadEventHandlers, browseEventHandlers, crawlEventHandlers)

/**
 * The factory to create page event handler.
 */
class PageEventHandlersFactory(val conf: ImmutableConfig = ImmutableConfig()) {
    private val logger = LoggerFactory.getLogger(PageEventHandlersFactory::class.java)

    companion object {
        fun create(): PageEventHandlers = PageEventHandlersFactory().create()

        fun create(className: String): PageEventHandlers = PageEventHandlersFactory().create(className)

        fun create(
            loadEventHandlers: LoadEventHandlers = DefaultLoadEventHandlers(),
            browseEventHandlers: BrowseEventHandlers = DefaultBrowseEventHandlers(),
            crawlEventHandlers: CrawlEventHandlers = DefaultCrawlEventHandlers()
        ): PageEventHandlers =
            PageEventHandlersFactory().create(loadEventHandlers, browseEventHandlers, crawlEventHandlers)
    }

    /**
     * Create a page event handler.
     * */
    @Synchronized
    fun create(): PageEventHandlers = createUsingGlobalConfig(conf)

    /**
     * Create a page event handler with [className].
     * */
    @Synchronized
    fun create(className: String): PageEventHandlers {
        val gen = when (className) {
            DefaultPageEventHandlers::class.java.name -> DefaultPageEventHandlers()
            else -> createUsingGlobalConfig(conf)
        }

        return gen
    }

    /**
     * Create a page event handler with [loadEventHandlers], [browseEventHandlers] and [crawlEventHandlers].
     * */
    @Synchronized
    fun create(
        loadEventHandlers: LoadEventHandlers = DefaultLoadEventHandlers(),
        browseEventHandlers: BrowseEventHandlers = DefaultBrowseEventHandlers(),
        crawlEventHandlers: CrawlEventHandlers = DefaultCrawlEventHandlers()
    ): PageEventHandlers = DefaultPageEventHandlers(loadEventHandlers, browseEventHandlers, crawlEventHandlers)

    private fun createUsingGlobalConfig(conf: ImmutableConfig): PageEventHandlers {
        return createUsingGlobalConfig(conf, CapabilityTypes.PAGE_EVENT_CLASS)
    }

    /**
     * Get the value of the `name` property as a `Class`.
     * If the property is not set, or the class is not found, use the default class.
     * The default class is `DefaultPageEvent`.
     *
     * Set the class:
     * `System.setProperty(CapabilityTypes.PAGE_EVENT_CLASS, "ai.platon.pulsar.skeleton.event.impl.DefaultPageEvent")`
     * */
    private fun createUsingGlobalConfig(conf: ImmutableConfig, className: String): PageEventHandlers {
        val defaultClazz = DefaultPageEventHandlers::class.java
        val clazz = try {
            // Get the value of the `name` property as a `Class`.
            // If the property is not set, or the class is not found, use the default class.
            runCatching { ResourceLoader.loadUserClass<PageEventHandlers>(className) }.getOrNull()
                ?: defaultClazz
//             conf.getClass(className, defaultClazz)
        } catch (e: Exception) {
            logger.warn(
                "Configured PageEvent generator {}({}) is not found, use default ({})",
                className, conf.get(className), defaultClazz.simpleName
            )
            defaultClazz
        }

        return clazz.constructors.first { it.parameters.isEmpty() }.newInstance() as PageEventHandlers
    }
}
