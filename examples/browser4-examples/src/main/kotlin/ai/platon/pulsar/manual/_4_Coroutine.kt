package ai.platon.pulsar.manual

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

/**
 * Demonstrates how to load pages with coroutines.
 * */
internal object CrawlCoroutines {
    private val session = PulsarContexts.createSession()
    private val scope = CoroutineScope(Dispatchers.Default) + CoroutineName("demo")

    suspend fun loadAllInCoroutines() {
        val jobs = LinkExtractors.fromResource("seeds10.txt")
            .map { scope.launch { session.loadDeferred("$it -expires 1s").also { println(it.url) } } }
        jobs.joinAll()
    }

    suspend fun loadAllInCoroutines2() {
        val deferredPages = LinkedBlockingQueue<Deferred<WebPage>>()
        val jobs = LinkExtractors.fromResource("seeds10.txt")
            .map { "$it -expires 1s -ignoreFailure" }
            .map { scope.launch { async { session.loadDeferred(it) }.also(deferredPages::add) } }

        // suspends current coroutine until all given jobs are complete.
        jobs.joinAll()
        deferredPages.awaitAll().forEach { println(it.url) }
    }
}

fun main() {
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    println("== loadAllInCoroutines ==")
    runBlocking { CrawlCoroutines.loadAllInCoroutines() }

    println("== loadAllInCoroutines2 ==")
    runBlocking { CrawlCoroutines.loadAllInCoroutines2() }
}
