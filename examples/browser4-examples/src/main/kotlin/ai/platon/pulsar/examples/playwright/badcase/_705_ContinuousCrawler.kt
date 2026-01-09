package ai.platon.pulsar.examples.playwright.badcase

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink

/**
 * Demonstrates continuous crawls.
 * */
fun main() {
    println("This is a bad case. Playwright is not threadsafe nor coroutine safe")

    PulsarSettings.withBrowser(BrowserType.PLAYWRIGHT_CHROME)
    val context = PulsarContexts.create()

    val parseHandler = { _: WebPage, document: FeaturedDocument ->
        // do something wonderful with the document
        println(document.title + "\t|\t" + document.baseURI)

        // extract more links from the document
        val links = document.selectHyperlinks("a[href~=/dp/]").onEach { it.args = "-refresh" }
        context.submitAll(links.take(10))
    }

    // change to seeds100.txt to crawl more
    val urls = LinkExtractors.fromResource("seeds10.txt")
        .map { ParsableHyperlink("$it -refresh", parseHandler) }
    context.submitAll(urls).await()
}
