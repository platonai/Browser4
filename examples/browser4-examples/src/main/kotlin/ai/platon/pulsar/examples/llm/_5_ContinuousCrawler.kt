package ai.platon.pulsar.examples.llm

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates continuous crawls.
 * */
fun main() {
    // For continuous crawls, you'd better use sequential browsers or temporary browsers
    PulsarSettings.withSequentialBrowsers().maxOpenTabs(8)

    val session = AgenticContexts.createSession()

    val parseHandler = { _: WebPage, document: FeaturedDocument ->
        // do something wonderful with the document
        println(document.title + "\t|\t" + document.baseURI)

        val response = runBlocking { session.chat("extract title, product name and price", document) }
        println(response)

        // extract more links from the document
        session.submitAll(document.selectHyperlinks("a[href~=/dp/]"))
    }

    // change to seeds100.txt to crawl more
    val urls = LinkExtractors.fromResource("seeds100.txt")
        .map { ParsableHyperlink("$it -refresh", parseHandler) }
    session.submitAll(urls)

    AgenticContexts.await()
}
