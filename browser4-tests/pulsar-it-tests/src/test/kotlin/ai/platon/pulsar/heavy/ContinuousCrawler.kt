package ai.platon.pulsar.heavy

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink

/**
 * Demonstrates continuous crawls.
 * */
fun main() {
    BrowserSettings.withBrowserContextMode(BrowserProfileMode.SEQUENTIAL)

    val topN = 10
    val topN2 = 10

    val context = PulsarContexts.create()

    val parseHandler = { _: WebPage, document: FeaturedDocument ->
        // do something wonderful with the document
        printlnPro(document.title + "\t|\t" + document.baseURI)

        // extract more links from the document
        context.submitAll(document.selectHyperlinks("a[href~=/dp/]").take(topN2))
    }

    // change to seeds100.txt to crawl more
    val urls = LinkExtractors.fromResource("seeds100.txt")
        .take(topN)
        .map { ParsableHyperlink("$it -refresh", parseHandler) }
    context.submitAll(urls).await()
}

