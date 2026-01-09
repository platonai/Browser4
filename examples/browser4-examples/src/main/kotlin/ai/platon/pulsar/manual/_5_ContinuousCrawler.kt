package ai.platon.pulsar.manual

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink

/**
 * Demonstrates continuous crawls.
 * */
fun main() {
    // For continuous crawls, you'd better use sequential browsers or temporary browsers
    PulsarSettings.withSequentialBrowsers().maxOpenTabs(8)

    val context = PulsarContexts.create()

    val parseHandler = { _: WebPage, document: FeaturedDocument ->
        // do something wonderful with the document
        println(document.title + "\t|\t" + document.baseURI)

        // extract more links from the document
        context.submitAll(document.selectHyperlinks("a[href~=/dp/]"))
    }

    // change to seeds100.txt to crawl more
    val urls = LinkExtractors.fromResource("seeds100.txt")
        .map { ParsableHyperlink("$it -refresh", parseHandler) }
    context.submitAll(urls).await()
}
