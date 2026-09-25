package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import ai.platon.pulsar.rest.api.service.crawl.CrawlStatus
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant

/**
 * The plumbing every crawl acceptance test needs: the poll loop, the terminal
 * vocabulary, and the `/__probe` fixture's counters.
 *
 * These three used to be a private copy in each crawl test class, and the copies
 * drifted: when the probe grew its nested `flakyHits` map only one copy learned to
 * read the new shape, and when the 4-minute wait was shown to be too short for a
 * loaded CI runner (commit `4ad80c4ad4`) only one copy was fixed — so run
 * `36164169973` lost 3 tests to a cast that two copies still made and 4 more to a
 * deadline five copies still carried.  A crawl test now inherits one definition of
 * each, so the next change to either is a change to every consumer
 * (docs-dev/copilot/ci-stabilization-4.13.x.md §31).
 *
 * Tagged subclasses remain `IntegrationTest`: everything here drives the REST API
 * of a running server and a real browser.
 */
open class CrawlTestBase : RestAPITestBase() {

    /** The mock site's `ConcurrencyProbeController` (see that class for the fixture). */
    protected val probeBase: String by lazy {
        "${TestUrls.MOCK_CRAWL_BASE.substringBefore("/generated")}/__probe"
    }

    /**
     * How long a crawl is waited on before a test calls it stuck.
     *
     * Derived from the server's own task budget instead of chosen by feel, because a
     * deadline *below* that budget fails crawls that are behaving exactly as designed:
     * `CrawlService.DEFAULT_TASK_TIMEOUT_MS` is 10 minutes, and a round reserves
     * `ROUND_REPORT_MARGIN_MS` (30 s) inside it to publish its losses, so a healthy
     * crawl can legitimately report its terminal state at ~10 minutes.  A hard-coded
     * 4 minutes can therefore never be right for a loaded runner — and the run that
     * proved it abandoned four crawls that all finished **OK** or timed out on their
     * own terms (4m46s, 5m42s, 6m14s and 9m29s after submission), then reported a
     * stall that had not happened.
     *
     * The slack covers that report window and the poll interval.  It is a literal
     * because `ROUND_REPORT_MARGIN_MS` is `internal` to the `browser4-rest` module.
     */
    protected val crawlTerminalWait: Duration =
        Duration.ofMillis(CrawlService.DEFAULT_TASK_TIMEOUT_MS + TERMINAL_WAIT_SLACK_MS)

    /**
     * Wait for [taskId] to reach a terminal state, or fail with the task's own account
     * of where it stopped.
     *
     * The old failure said only "last status: PROCESSING", which says nothing about how
     * far the crawl got and sends the reader to the CI log to find out (§19.5).  A wait
     * that runs out reports the task's own accounting instead.
     */
    protected fun waitForTerminal(taskId: String, wait: Duration = crawlTerminalWait): CrawlResponse {
        val deadline = Instant.now().plus(wait)
        var last: CrawlResponse? = null
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_INTERVAL_MS)
            val result = readCrawlResult(taskId)
            last = result
            if (result.isTerminalOrSettled()) {
                return result
            }
        }
        error(
            "Crawl $taskId did not reach a terminal state within ${wait.toMinutes()} minutes: " +
                (last?.describeCrawl() ?: "no result was ever returned")
        )
    }

    /**
     * One poll of a crawl's task record.
     *
     * The raw body is deserialized with the Kotlin-aware mapper on purpose:
     * `expectBody<CrawlResponse>()` uses the client-side converter without the Kotlin
     * module, and an all-default Kotlin class has a no-arg constructor, so the client
     * instantiates it and binds *nothing* — `status` would stay at its `CREATED`
     * default forever while the server reports `PROCESSING`/`OK` (commit `cae4042735`).
     */
    protected fun readCrawlResult(taskId: String): CrawlResponse {
        val raw = client.get().uri("/api/crawl/$taskId/result")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val body = requireNotNull(raw) { "empty crawl result for $taskId" }
        return kotlinAwareMapper.readValue(body, CrawlResponse::class.java)
    }

    /**
     * Zero the probe's counters, so the next crawl is measured on its own.
     */
    protected fun resetProbe() {
        client.post().uri("$probeBase/reset")
            .exchange()
            .expectStatus().is2xxSuccessful
    }

    /**
     * The `/__probe` counters, as plain ints.
     *
     * The body is **not** flat: `flakyHits` is a nested per-id map (the delivery-retry
     * fixture's hit counts), so a blanket `(value as Number)` over the entries throws
     * `ClassCastException: LinkedHashMap cannot be cast to Number`.  That is exactly how
     * three tests of run `36164169973` failed *before* reading a single counter, having
     * just driven a real crawl.  Only numeric entries are the counters this reads;
     * anything else is left to the test that knows its shape — `CrawlDeliveryRetryTest`
     * reads `flakyHits` itself.
     */
    protected fun probeStats(): Map<String, Int> {
        val raw = client.get().uri("$probeBase/stats")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val body = requireNotNull(raw) { "empty /__probe/stats body" }
        return jacksonObjectMapper().readValue(body, Map::class.java)
            .entries
            .mapNotNull { (key, value) -> (value as? Number)?.let { key.toString() to it.toInt() } }
            .toMap()
    }

    /**
     * Terminal detection defers to [CrawlStatus] — the one vocabulary definition — so a
     * test cannot drift from the service again.
     *
     * The earlier check compared against `"SC_REQUEST_TIMEOUT"` /
     * `"SC_INTERNAL_SERVER_ERROR"` spellings the service never emitted, so a crawl that
     * had already timed out could not be recognised and the wait ran out its whole cap
     * before blaming a stall.  [CrawlResponse.finishTime] is the model's own terminal
     * marker, and it is also what catches a status the vocabulary does not list.
     */
    private fun CrawlResponse.isTerminalOrSettled(): Boolean =
        finishTime != null || CrawlStatus.isTerminal(status)

    /** One line of the task's own accounting, for a timeout that has to be actionable. */
    private fun CrawlResponse.describeCrawl(): String = buildString {
        append("status=").append(status)
        append(", pages=").append(pagesFound).append('/').append(pagesExpected)
        append(", links=").append(linksDiscovered)
        append(", parallelTabs=").append(parallelTabs)
        append(", waiting=").append(
            Duration.between(startedTime ?: Instant.ofEpochMilli(createdAt), Instant.now()).seconds
        ).append('s')
        error?.let { append(", error=").append(it) }
        diagnostic?.let { append(", diagnostic=").append(it) }
        failedPages?.takeIf { it.isNotEmpty() }?.let { append(", failedPages=").append(it.size) }
        seedStatuses?.takeIf { it.isNotEmpty() }?.let { seeds ->
            append(", seeds=[").append(
                seeds.joinToString("; ") { "${it.url.substringAfterLast('/')}:${it.status}" }
            ).append(']')
        }
    }

    private val kotlinAwareMapper by lazy {
        jacksonObjectMapper().registerModule(JavaTimeModule())
    }

    private companion object {
        /** One poll a second: responsive enough to catch a terminal state promptly. */
        const val POLL_INTERVAL_MS = 1_000L

        /** The round's own report window (30 s) plus room for the publish and the poll. */
        const val TERMINAL_WAIT_SLACK_MS = 120_000L
    }
}
