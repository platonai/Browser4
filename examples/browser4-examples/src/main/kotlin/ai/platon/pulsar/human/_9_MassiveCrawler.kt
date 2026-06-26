package ai.platon.pulsar.examples

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.DelayUrl
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink
import java.time.Duration

/**
 * Demonstrates how to crawl a massive collection of urls with various requirements.
 * */
fun main() {
    PulsarSettings().withSequentialBrowsers().maxBrowserContexts(4).maxOpenTabs(8).headless()

    val session = PulsarContexts.createSession()
    val urlPool = session.context.globalCache.urlPool

    val parseHandler = { _: WebPage, document: FeaturedDocument ->
        // do something wonderful with the document
        println(document.title + "\t|\t" + document.baseURI)
    }
    val urls = LinkExtractors.fromResource("seeds100.txt").map { ParsableHyperlink(it, parseHandler) }

    val (url1, url2, url3, url4, url5) = urls.shuffled()
    session.submit(url1)
    session.submit(url2, "-refresh")
    session.submit(url3, "-i 30s")

    session.submitAll(urls)
    // feel free to submit millions of urls here
    session.submitAll(urls, "-i 7d")

    urlPool.add(url4)
    urlPool.add(url5.apply { priority = Priority13.HIGHER4.value })

    urlPool.highestCache.reentrantQueue.add(url1)
    urlPool.higher2Cache.nonReentrantQueue.add(url2)
    urlPool.lower2Cache.nReentrantQueue.add(url3)
    // highest priority
    urlPool.realTimeCache.reentrantQueue.add(url4)
    // will start 2 hours later
    urlPool.delayCache.add(DelayUrl(url5, Duration.ofHours(2)))

    // wait for all tasks to be finished.
    PulsarContexts.await()
}
