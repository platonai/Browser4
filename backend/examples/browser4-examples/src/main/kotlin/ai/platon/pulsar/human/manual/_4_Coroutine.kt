package ai.platon.pulsar.human.manual

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

/**
 * # Kotlin Coroutines Integration - Native Async Support
 *
 * This example demonstrates how to use Browser4 with Kotlin coroutines,
 * which provide a more natural way to write async code compared to callbacks.
 *
 * ## Coroutine Patterns Covered:
 * 1. **Concurrent Launches** - Running multiple loads in parallel with launch()
 * 2. **Deferred Results** - Using async() to get results from coroutines
 *
 * ## Key Concepts:
 * - **CoroutineScope**: Defines the lifecycle of coroutines
 * - **Dispatchers.Default**: Optimized for CPU-intensive work
 * - **launch()**: Fire-and-forget coroutine (no return value)
 * - **async()**: Coroutine that returns a Deferred result
 * - **joinAll()**: Wait for all jobs to complete
 * - **awaitAll()**: Collect results from all deferred operations
 *
 * ## Benefits over Java Async:
 * - More readable sequential-looking code
 * - Better structured concurrency
 * - Easier error handling
 * - Natural cancellation support
 *
 * ## AI Integration Notes:
 * - Use coroutines for complex async workflows
 * - Suspend functions can contain AI analysis steps
 * - CoroutineScope controls the lifecycle of background tasks
 *
 * @see loadDeferred The suspend function for loading pages
 * @see CoroutineScope Defines coroutine lifecycle
 * @see Deferred Represents a deferred computation result
 */
internal object CrawlCoroutines {
    // Create a session at object initialization
    private val session = PulsarContexts.createSession()

    // Create a coroutine scope with a descriptive name
    // AI Note: CoroutineName helps with debugging and logging
    private val scope = CoroutineScope(Dispatchers.Default) + CoroutineName("demo")

    /**
     * Pattern 1: Concurrent Launches with Fire-and-Forget
     *
     * Uses launch() to start multiple page loads concurrently.
     * Each launch creates a new coroutine that runs independently.
     *
     * AI Note: launch() is best when you don't need the return value,
     * or when you process results inside the coroutine.
     */
    suspend fun loadAllInCoroutines() {
        // Create a job for each URL
        val jobs = LinkExtractors.fromResource("seeds10.txt")
            .map { scope.launch {
                // loadDeferred is a suspend function that loads the page
                // AI Note: .also {} allows us to perform side effects (printing) on the result
                session.loadDeferred("$it -expires 1s").also { println(it.url) }
            } }
        // Wait for all jobs to complete
        // AI Note: joinAll() suspends until all jobs finish (success or failure)
        jobs.joinAll()
    }

    /**
     * Pattern 2: Async with Collected Results
     *
     * Uses async() inside launch() to collect Deferred results.
     * Results are gathered in a thread-safe queue for later processing.
     *
     * AI Note: This pattern is useful when you need to collect all results
     * after concurrent processing, for example for batch database insertion.
     */
    suspend fun loadAllInCoroutines2() {
        // Thread-safe queue to collect deferred results
        val deferredPages = LinkedBlockingQueue<Deferred<WebPage>>()

        // Launch coroutines that create async operations
        val jobs = LinkExtractors.fromResource("seeds10.txt")
            .map { "$it -expires 1s -ignoreFailure" }
            .map { scope.launch {
                // async() returns a Deferred that we can await later
                // AI Note: We store the Deferred, not the result, allowing parallel execution
                async { session.loadDeferred(it) }.also(deferredPages::add)
            } }

        // suspends current coroutine until all given jobs are complete.
        jobs.joinAll()

        // Now await all deferred results and process them
        // AI Note: awaitAll() collects results from all Deferred operations
        deferredPages.awaitAll().forEach { println(it.url) }
    }
}

/**
 * Main entry point demonstrating Kotlin coroutine patterns.
 *
 * AI Note: runBlocking bridges regular code to suspend functions.
 * In production, you typically use proper coroutine scopes instead.
 */
fun main() {
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    println("== loadAllInCoroutines ==")
    // Pattern 1: Fire-and-forget with immediate processing
    runBlocking { CrawlCoroutines.loadAllInCoroutines() }

    println("== loadAllInCoroutines2 ==")
    // Pattern 2: Collected results for batch processing
    runBlocking { CrawlCoroutines.loadAllInCoroutines2() }
}
