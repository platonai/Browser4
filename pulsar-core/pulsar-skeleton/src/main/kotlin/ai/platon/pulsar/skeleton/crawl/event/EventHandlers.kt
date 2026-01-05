package ai.platon.pulsar.skeleton.crawl.event

import ai.platon.pulsar.common.lang.*
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.crawl.fetch.driver.JvmWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.PrivacyContext

/**
 * A void handler that takes no parameters and returns Unit.
 * Base class for simple event handlers that perform side effects.
 */
abstract class VoidHandler : PFunction0<Unit>, AbstractPHandler() {
    abstract override operator fun invoke()
}

/**
 * A handler that processes UrlAware objects.
 * Can return a modified UrlAware or null to filter out the URL.
 *
 * @see UrlAware
 */
abstract class UrlAwareHandler : (UrlAware) -> UrlAware?, AbstractPHandler() {
    /**
     * Process the URL-aware object.
     *
     * @param url The UrlAware object to process
     * @return Modified UrlAware, or null to skip this URL
     */
    abstract override operator fun invoke(url: UrlAware): UrlAware?
}

/**
 * A filter that processes UrlAware objects.
 * Similar to UrlAwareHandler but semantically indicates filtering behavior.
 *
 * @see UrlAwareHandler
 */
abstract class UrlAwareFilter : (UrlAware) -> UrlAware?, AbstractPHandler() {
    /**
     * Filter the URL-aware object.
     *
     * @param url The UrlAware object to filter
     * @return The UrlAware if it passes the filter, or null to reject
     */
    abstract override operator fun invoke(url: UrlAware): UrlAware?
}

/**
 * A handler that processes URL strings.
 * Can return a modified URL string or null to filter out the URL.
 */
abstract class UrlHandler : (String) -> String?, AbstractPHandler() {
    /**
     * Process the URL string.
     *
     * @param url The URL string to process
     * @return Modified URL, or null to skip this URL
     */
    abstract override operator fun invoke(url: String): String?
}

/**
 * A filter that processes URL strings.
 * Similar to UrlHandler but semantically indicates filtering behavior.
 *
 * @see UrlHandler
 */
abstract class UrlFilter : (String) -> String?, AbstractPHandler() {
    /**
     * Filter the URL string.
     *
     * @param url The URL string to filter
     * @return The URL if it passes the filter, or null to reject
     */
    abstract override operator fun invoke(url: String): String?
}

/**
 * A handler that processes WebPage objects.
 * The WebPage contains metadata and content about a web page.
 */
abstract class WebPageHandler : (WebPage) -> Any?, AbstractPHandler() {
    /**
     * Process the WebPage.
     *
     * @param page The WebPage to process
     * @return Any value (typically ignored, but can return extracted data)
     */
    abstract override operator fun invoke(page: WebPage): Any?
}

/**
 * A handler that processes both a UrlAware and its corresponding WebPage.
 * Useful for crawl-level event handling where you need both the URL and page.
 */
abstract class UrlAwareWebPageHandler : (UrlAware, WebPage?) -> Any?, AbstractPHandler() {
    /**
     * Process the URL and its corresponding page.
     *
     * @param url The UrlAware object that was loaded
     * @param page The loaded WebPage, or null if loading failed
     * @return Any value (typically ignored, but can return extracted data)
     */
    abstract override operator fun invoke(url: UrlAware, page: WebPage?): Any?
}

/**
 * A handler that processes a WebPage and its parsed HTML document.
 * Provides access to the DOM for data extraction and manipulation.
 *
 * This is the primary handler for extracting structured data from parsed HTML.
 */
abstract class HTMLDocumentHandler : (WebPage, FeaturedDocument) -> Any?, AbstractPHandler() {
    /**
     * Process the WebPage and its parsed HTML document.
     *
     * @param page The WebPage containing metadata
     * @param document The parsed FeaturedDocument (DOM)
     * @return Any value (can return extracted data, often used for data collection)
     */
    abstract override operator fun invoke(page: WebPage, document: FeaturedDocument): Any?
}

/**
 * A handler that processes privacy contexts.
 * Used for managing browser privacy contexts and isolation.
 */
abstract class PrivacyContextHandler : (PrivacyContext) -> Any?, AbstractPHandler() {
    /**
     * Process the privacy context.
     *
     * @param privacyContext The privacy context to process
     * @return Any value (typically ignored)
     */
    abstract override operator fun invoke(privacyContext: PrivacyContext): Any?
}

/**
 * A handler that processes a WebPage with access to the WebDriver.
 * Enables browser automation and interaction with the live web page.
 *
 * This is the primary handler for performing browser automation tasks such as:
 * - Clicking elements
 * - Filling forms
 * - Executing JavaScript
 * - Waiting for dynamic content
 * - Taking screenshots
 *
 * Note: This is a suspend function for async operations.
 */
abstract class WebPageWebDriverHandler : (WebPage, WebDriver) -> Any?, AbstractPHandler() {
    /**
     * Process the WebPage using the WebDriver.
     *
     * @param page The WebPage being processed
     * @param driver The WebDriver for browser automation
     * @return Any value (typically ignored, but can return extracted data)
     */
    abstract override operator fun invoke(page: WebPage, driver: WebDriver): Any?
}

/**
 * A handler that processes raw page source and extracted page data.
 * Used for low-level page data processing.
 */
abstract class PageDatumHandler : (String, PageDatum) -> Any?, AbstractPHandler() {
    /**
     * Process the page source and page datum.
     *
     * @param pageSource The raw HTML source of the page
     * @param pageDatum The extracted page data
     * @return Any value (typically ignored)
     */
    abstract override operator fun invoke(pageSource: String, pageDatum: PageDatum): Any?
}

/**
 * A chained event handler for void (no-parameter) events.
 * Allows multiple handlers to be registered and executed in sequence.
 */
open class VoidEventHandler : AbstractChainedFunction0<Unit>()

/**
 * A chained event handler for UrlAware objects.
 * Multiple handlers can be registered and will execute in sequence.
 * Each handler can transform the UrlAware or return null to filter it out.
 *
 * If the handler chain is empty, the original URL is passed through unchanged.
 */
open class UrlAwareEventHandler : AbstractChainedFunction1<UrlAware, UrlAware>() {
    override fun invoke(url: UrlAware): UrlAware? {
        return if (isEmpty) url else super.invoke(url)
    }
}

/**
 * A chained filter for UrlAware objects.
 * Similar to UrlAwareEventHandler but semantically represents filtering logic.
 *
 * If the handler chain is empty, the original URL is passed through unchanged.
 */
open class UrlAwareEventFilter : AbstractChainedFunction1<UrlAware, UrlAware>() {
    override fun invoke(url: UrlAware): UrlAware? {
        return if (isEmpty) url else super.invoke(url)
    }
}

/**
 * A chained filter for URL strings.
 * Multiple URL filters can be registered and will execute in sequence.
 *
 * If the handler chain is empty, the original URL is passed through unchanged.
 */
open class UrlFilterEventHandler : AbstractChainedFunction1<String, String?>() {
    override fun invoke(url: String): String? {
        return if (isEmpty) url else super.invoke(url)
    }
}

/**
 * A chained event handler for URL strings.
 * Multiple handlers can be registered and will execute in sequence.
 *
 * If the handler chain is empty, the original URL is passed through unchanged.
 */
open class UrlEventHandler : AbstractChainedFunction1<String, String?>() {
    override fun invoke(url: String): String? {
        return if (isEmpty) url else super.invoke(url)
    }
}

/**
 * A chained event handler for WebPage objects.
 * Multiple handlers can be registered and will execute in sequence.
 */
open class WebPageEventHandler : AbstractChainedFunction1<WebPage, Any?>()

/**
 * A chained event handler that accepts a [UrlAware] and a [WebPage], anything can be returned.
 *
 * Multiple handlers can be registered and will execute in sequence.
 * Useful for crawl-level event handling where you need both the URL and the page.
 *
 * The Web asset might be changed by the handler since [WebPage] has setters.
 *
 * Another possible way to write more robust code is to remove all setters in [WebPage],
 * and add a MutableWebPage subclass.
 * */
open class UrlAwareWebPageEventHandler : AbstractChainedFunction2<UrlAware, WebPage?, Any?>()

/**
 * A chained event handler that accepts a [WebPage] and a [FeaturedDocument], anything can be returned.
 *
 * Multiple handlers can be registered and will execute in sequence.
 * This is the primary handler for extracting structured data from parsed HTML.
 *
 * The event handler should work with the passed document, such as:
 * - Extracting fields using CSS selectors
 * - Persisting extraction results
 * - Collecting more links for crawling
 * - Analyzing page structure
 *
 * The Web asset might be changed by the handler since [WebPage] has setters.
 *
 * Another possible way to write more robust code is to remove all setters in [WebPage],
 * and add a MutableWebPage subclass.
 *
 * @see FeaturedDocument for DOM manipulation methods
 * */
open class WebPageHTMLDocumentEventHandler : AbstractChainedFunction2<WebPage, FeaturedDocument, Any?>()

/**
 * Type alias for [WebPageHTMLDocumentEventHandler].
 * Provides a shorter, more convenient name for HTML document event handlers.
 */
typealias HTMLDocumentEventHandler = WebPageHTMLDocumentEventHandler

/**
 * A chained event handler for page datum objects.
 * Multiple handlers can be registered and will execute in sequence.
 */
open class PageDatumEventHandler : AbstractChainedFunction2<String, PageDatum, Any?>()

/**
 * A chained event handler that accepts a [WebPage] and a [WebDriver], anything can be returned.
 *
 * Multiple handlers can be registered and will execute in sequence.
 * This is the primary handler for browser automation and RPA tasks.
 *
 * The event handler is supposed to use the WebDriver to interact with the active remote web page.
 * Common operations include:
 * - Clicking elements: `driver.click(selector)`
 * - Filling forms: `driver.type(selector, text)`
 * - Executing JavaScript: `driver.evaluate(script)`
 * - Waiting for elements: `driver.waitForSelector(selector)`
 * - Taking screenshots: `driver.captureScreenshot()`
 * - Scrolling: `driver.scrollDown()` or `driver.scrollToBottom()`
 *
 * The Web asset might be changed by the handler since [WebPage] has setters.
 *
 * Another possible way to write more robust code is to remove all setters in [WebPage],
 * and add a MutableWebPage subclass.
 *
 * @see WebDriver for available automation methods
 * */
open class WebPageWebDriverEventHandler : AbstractChainedPDFunction2<WebPage, WebDriver, Any?>()

/**
 * A specialized WebPageWebDriverEventHandler that works with JvmWebDriver.
 * Automatically converts the WebDriver to a JvmWebDriver for convenience.
 *
 * Extend this class when you need JVM-specific WebDriver functionality.
 */
abstract class JvmWebPageWebDriverEventHandler : WebPageWebDriverEventHandler() {
    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        return invoke(page, driver.jvm())
    }

    /**
     * Handle the event with JvmWebDriver.
     *
     * @param page The WebPage being processed
     * @param driver The JvmWebDriver for browser automation
     * @return Any value (typically ignored, but can return extracted data)
     */
    abstract suspend fun invoke(page: WebPage, driver: JvmWebDriver): Any?
}
