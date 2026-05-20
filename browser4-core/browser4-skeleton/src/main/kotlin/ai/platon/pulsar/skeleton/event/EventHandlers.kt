@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "unused")

package ai.platon.pulsar.skeleton.event

import ai.platon.pulsar.common.lang.*
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.browser.driver.WebDriver
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.PrivacyContext

abstract class VoidHandler : PFunction0<Unit>, AbstractPHandler() {
    abstract override operator fun invoke()
}

abstract class UrlAwareHandler : (UrlAware) -> UrlAware?, AbstractPHandler() {
    abstract override operator fun invoke(url: UrlAware): UrlAware?
}

abstract class UrlAwareFilter : (UrlAware) -> UrlAware?, AbstractPHandler() {
    abstract override operator fun invoke(url: UrlAware): UrlAware?
}

abstract class UrlHandler : (String) -> String?, AbstractPHandler() {
    abstract override operator fun invoke(url: String): String?
}

abstract class UrlFilter : (String) -> String?, AbstractPHandler() {
    abstract override operator fun invoke(url: String): String?
}

abstract class WebPageHandler : (WebPage) -> Any?, AbstractPHandler() {
    abstract override operator fun invoke(page: WebPage): Any?
}

abstract class UrlAwareWebPageHandler : (UrlAware, WebPage?) -> Any?, AbstractPHandler() {
    abstract override operator fun invoke(url: UrlAware, page: WebPage?): Any?
}

abstract class HTMLDocumentHandler : (WebPage, FeaturedDocument) -> Any?, AbstractPHandler() {
    abstract override operator fun invoke(page: WebPage, document: FeaturedDocument): Any?
}

abstract class PrivacyContextHandler : (PrivacyContext) -> Any?, AbstractPHandler() {
    abstract override operator fun invoke(privacyContext: PrivacyContext): Any?
}

abstract class WebPageWebDriverHandler : (WebPage, WebDriver) -> Any?, AbstractPHandler() {
    abstract override operator fun invoke(page: WebPage, driver: WebDriver): Any?
}

abstract class PageDatumHandler : (String, PageDatum) -> Any?, AbstractPHandler() {
    abstract override operator fun invoke(pageSource: String, pageDatum: PageDatum): Any?
}

open class VoidEventHandler : AbstractChainedFunction0<Unit>()

open class UrlAwareEventHandler : AbstractChainedFunction1<UrlAware, UrlAware>() {
    override fun invoke(url: UrlAware): UrlAware? {
        return if (isEmpty) url else super.invoke(url)
    }
}

open class UrlAwareEventFilter : AbstractChainedFunction1<UrlAware, UrlAware>() {
    override fun invoke(url: UrlAware): UrlAware? {
        return if (isEmpty) url else super.invoke(url)
    }
}

open class UrlFilterEventHandler : AbstractChainedFunction1<String, String?>() {
    override fun invoke(url: String): String? {
        return if (isEmpty) url else super.invoke(url)
    }
}

open class UrlEventHandler : AbstractChainedFunction1<String, String?>() {
    override fun invoke(url: String): String? {
        return if (isEmpty) url else super.invoke(url)
    }
}

open class WebPageEventHandler : AbstractChainedFunction1<WebPage, Any?>() {
    override fun invoke(page: WebPage): Any? {
        return super.invoke(param = page)
    }
}

/**
 * An event handler that accepts a [UrlAware] and a [WebPage], anything can be returned.
 *
 * The Web asset might be changed by the handler since [WebPage] has setters.
 *
 * Another possible way to write more robust code is to remove all setters in [WebPage],
 * and add a MutableWebPage subclass.
 * */
open class UrlAwareWebPageEventHandler : AbstractChainedFunction2<UrlAware, WebPage?, Any?>() {
    override fun invoke(url: UrlAware, page: WebPage?): Any? {
        return super.invoke(param = url, param2 = page)
    }
}

/**
 * An event handler that accepts a [WebPage] and a [FeaturedDocument], anything can be returned.
 *
 * The event handler should work with the passed document, such as extracting fields, persisting
 * extraction results, collecting more links, and so on.
 *
 * The Web asset might be changed by the handler since [WebPage] has setters.
 *
 * Another possible way to write more robust code is to remove all setters in [WebPage],
 * and add a MutableWebPage subclass.
 * */
open class WebPageHTMLDocumentEventHandler : AbstractChainedFunction2<WebPage, FeaturedDocument, Any?>() {
    override fun invoke(page: WebPage, document: FeaturedDocument): Any? {
        return super.invoke(param = page, param2 = document)
    }
}

typealias HTMLDocumentEventHandler = WebPageHTMLDocumentEventHandler

open class PageDatumEventHandler : AbstractChainedFunction2<String, PageDatum, Any?>() {
    override fun invoke(url: String, pageDatum: PageDatum): Any? {
        return super.invoke(param = url, param2 = pageDatum)
    }
}

/**
 * An event handler that accepts a [WebPage] and a [WebDriver], anything can be returned.
 * The event handler is supposed to use the Web driver to interact with the active remote web page.
 *
 * The Web asset might be changed by the handler since [WebPage] has setters.
 *
 * Another possible way to write more robust code is to remove all setters in [WebPage],
 * and add a MutableWebPage subclass.
 * */
open class WebPageWebDriverEventHandler : AbstractChainedPDFunction2<WebPage, WebDriver, Any?>() {
    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        return super.invoke(param = page, param2 = driver)
    }
}
