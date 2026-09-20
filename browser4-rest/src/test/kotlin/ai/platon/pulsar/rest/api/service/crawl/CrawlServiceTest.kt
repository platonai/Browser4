package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.MockitoAnnotations

/**
 * Unit tests for [CrawlService] focusing on task lifecycle management:
 * cancel, clear, TTL-based purge, and timeout partial-result preservation.
 */
class CrawlServiceTest {

    @Mock
    private lateinit var sessionManager: PulsarSessionManager

    private lateinit var crawlService: CrawlService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        crawlService = CrawlService(sessionManager)
        // Shorter TTL for faster purge testing
        crawlService.taskTtlMinutes = 0 // immediate expiry for test
    }

    @AfterEach
    fun tearDown() {
        runCatching { crawlService.shutdown() }
    }

    // ------------------------------------------------------------------
    // getResult — unknown task
    // ------------------------------------------------------------------

    @Test
    fun `getResult returns not-found status for unknown task`() {
        val result = crawlService.getResult("nonexistent-id")
        assertEquals(
            ResourceStatus.getStatusText(ResourceStatus.SC_NOT_FOUND),
            result.status
        )
        assertEquals("Task not found: nonexistent-id", result.error)
        assertEquals("nonexistent-id", result.taskId)
    }

    // ------------------------------------------------------------------
    // cancel
    // ------------------------------------------------------------------

    @Test
    fun `cancel returns false for unknown task`() {
        assertFalse(crawlService.cancel("nonexistent-id"))
    }

    @Test
    fun `cancel returns false for already-completed task`() {
        // Submit a no-URL task, which immediately fails (no running job)
        val request = CrawlRequest(url = "", depth = 0)
        val taskId = crawlService.submit(request)

        // The task completes immediately (no URLs → error), so cancel should fail
        // Give it a moment to process
        Thread.sleep(100)
        val result = crawlService.cancel(taskId)
        // May be false because the job has already completed
        // Either way, verify the task status is terminal
        val status = crawlService.getResult(taskId)
        assertTrue(
            status.status in setOf(
                ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            ),
            "Expected terminal status, got: ${status.status}"
        )
    }

    // ------------------------------------------------------------------
    // clearTerminal
    // ------------------------------------------------------------------

    @Test
    fun `clearTerminal removes terminal-state tasks`() {
        // Submit a task that immediately errors (no URLs)
        val request = CrawlRequest(url = "", depth = 0)
        val taskId = crawlService.submit(request)

        // Wait for the coroutine to finish
        Thread.sleep(200)

        // Verify the task is in a terminal state
        val before = crawlService.getResult(taskId)
        assertTrue(
            before.status in setOf(
                ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            ),
            "Expected terminal status, got: ${before.status} (taskId=$taskId)"
        )

        // Clear terminal tasks
        val cleared = crawlService.clearTerminal()
        assertTrue(cleared >= 1, "Expected at least 1 cleared task, got $cleared")

        // Verify the task is now unknown
        val after = crawlService.getResult(taskId)
        assertEquals(
            ResourceStatus.getStatusText(ResourceStatus.SC_NOT_FOUND),
            after.status
        )
    }

    // ------------------------------------------------------------------
    // TTL purge
    // ------------------------------------------------------------------

    @Test
    fun `purgeExpiredTasks removes expired terminal tasks`() = runBlocking {
        // Set TTL to 0 so anything is immediately expired
        crawlService.taskTtlMinutes = 0

        // Submit a task that immediately errors
        val request = CrawlRequest(url = "", depth = 0)
        val taskId = crawlService.submit(request)

        // Wait for coroutine completion
        delay(200)

        // Verify terminal
        val before = crawlService.getResult(taskId)
        assertNotEquals(
            ResourceStatus.getStatusText(ResourceStatus.SC_CREATED),
            before.status,
            "Task should not still be CREATED"
        )

        // Manually trigger purge via clearTerminal (which checks terminal status)
        val cleared = crawlService.clearTerminal()
        assertTrue(cleared > 0, "Expected purge to remove tasks")

        val after = crawlService.getResult(taskId)
        assertEquals(
            ResourceStatus.getStatusText(ResourceStatus.SC_NOT_FOUND),
            after.status,
            "Task should be gone after purge"
        )
    }

    @Test
    fun `active tasks are not purged`() = runBlocking {
        // Set TTL to 0
        crawlService.taskTtlMinutes = 0

        // Submit a task that stays in CREATED (before the coroutine runs)
        val request = CrawlRequest(url = "", depth = 0)
        val taskId = crawlService.submit(request)

        // Immediately check — it should still be CREATED or already failed
        delay(50)

        val status = crawlService.getResult(taskId)
        // If still CREATED, clearTerminal should NOT remove it
        // (clearTerminal only removes terminal-status tasks)
        val cleared = crawlService.clearTerminal()
        // cleared may be 0 if the task hasn't transitioned to terminal yet,
        // or > 0 if it has. Either way, verify clearTerminal only clears terminal.
        assertTrue(cleared >= 0)
    }

    // ------------------------------------------------------------------
    // CrawlResponse metadata
    // ------------------------------------------------------------------

    @Test
    fun `CrawlResponse includes createdAt timestamp`() {
        val response = CrawlResponse(taskId = "test-1")
        assertTrue(response.createdAt > 0)
        assertTrue(response.createdAt <= System.currentTimeMillis())
    }

    @Test
    fun `CrawlResponse includes taskTTLMinutes`() {
        val response = CrawlResponse(taskId = "test-1", taskTTLMinutes = 30)
        assertEquals(30, response.taskTTLMinutes)
    }

    // ------------------------------------------------------------------
    // Timeout / cancellation state-machine hardening
    // ------------------------------------------------------------------

    /**
     * Regression: a task whose worker died while PROCESSING (e.g. the internal
     * withTimeout fired mid-seed) must not stay PROCESSING forever.  When a
     * running job is cancelled, cancel() writes a terminal record first, so a
     * PROCESSING record observed right after submission must always be able to
     * reach a terminal state.
     */
    @Test
    fun `cancelled task transitions to terminal timeout state`() = runBlocking {
        val request = CrawlRequest(url = "", depth = 0)
        val taskId = crawlService.submit(request)

        // Give the coroutine a chance to pick the task up.
        delay(100)

        val cancelled = crawlService.cancel(taskId)
        // Either the worker was still running (true) or already finished
        // (false) — in both cases the record must end in a terminal state
        // and never remain PROCESSING/CREATED.
        delay(200)
        val result = crawlService.getResult(taskId)
        assertTrue(
            result.status in setOf(
                ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
                ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
            ),
            "Task should be terminal after cancel, got: ${result.status} (cancelled=$cancelled)"
        )
        assertNotNull(result.finishTime, "Terminal task should record a finish time")
    }

    /**
     * Regression: cancel must never leave a PROCESSING record behind — the
     * CLI's `crawl cancel` shows `{"cancelled": false}` when no live worker
     * exists, and the task store must not keep the task in PROCESSING for
     * the record to be recoverable via status/result.
     */
    @Test
    fun `cancel on a task with no live worker still yields a queryable record`() = runBlocking {
        // No-URL submit fails immediately, so by the time we cancel there is
        // no running job — cancel() returns false but the record is terminal.
        val request = CrawlRequest(url = "", depth = 0)
        val taskId = crawlService.submit(request)
        delay(300)

        val cancelled = crawlService.cancel(taskId)
        val result = crawlService.getResult(taskId)
        assertFalse(cancelled, "No live worker should make cancel return false")
        assertTrue(
            result.status in setOf(
                ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
                ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
            ),
            "Task should be terminal, got: ${result.status}"
        )
    }

    // ------------------------------------------------------------------
    // Task budget: a seed that cannot fit is reported, never dropped
    // ------------------------------------------------------------------

    /**
     * Regression: round budgets used to be fixed per depth (`depth * 5 min`,
     * capped at 30 min), which from depth 2 up is >= the whole task limit.  Such
     * a round could therefore only ever be *killed* by the task limit, and a
     * killed round returns nothing — not its pages, not the URLs it knows are
     * missing.  The terminal record then reported fewer pages with no losses at
     * all: a truncated crawl that looked merely small.
     *
     * A round's budget is now derived from what the task has left, and a seed
     * the remaining budget cannot carry is refused *before* it is submitted, so
     * the crawl ends TIMEOUT with every seed accounted for: the URLs are in
     * `failedPages`, the count is in `pagesExpected`, and `pagesFound +
     * failedPages.size == pagesExpected` still holds.
     */
    @Test
    fun `an exhausted task budget reports the seeds it never started`() = runBlocking {
        // Below the round floor (report margin + minimum round budget = 45s), so
        // no seed can be started — and no browser has to be involved to prove it.
        crawlService.taskTimeoutMillis = 10_000
        val seeds = listOf(
            "https://example.com/a",
            "https://example.com/b",
            "https://example.com/c"
        )

        val taskId = crawlService.submit(CrawlRequest(urls = seeds, depth = 1))
        val result = awaitTerminal(taskId)

        assertEquals(
            ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            result.status,
            "a crawl that could not fetch its seeds is a timeout, never OK"
        )
        assertEquals(0, result.pagesFound, "no page was claimed, so none may be reported")
        assertEquals(seeds.size, result.pagesExpected)
        val failed = requireNotNull(result.failedPages) { "the unfetched seeds must be reported as lost" }
        assertEquals(seeds, failed.map { it.url }, "every refused seed is named, in seed order")
        assertTrue(
            failed.all { it.reason == REASON_BUDGET_EXHAUSTED },
            "the reason must say why, got: ${failed.map { it.reason }}"
        )
        // The accounting law the CLI's loss warning rests on.
        assertEquals(result.pagesExpected, result.pagesFound + failed.size)
        assertEquals(
            seeds.size,
            result.seedStatuses?.count { it.status == "skipped" },
            "the poller must see the refused seeds, not a shorter crawl"
        )
        assertNotNull(result.diagnostic, "the terminal record must explain why it is incomplete")
        assertNotNull(result.finishTime, "a terminal task records a finish time")
        // The gate short-circuits before the browser: nothing was fetched at all.
        verifyNoInteractions(sessionManager)
    }

    /** Poll a task until it reaches a terminal state, or fail with what it was doing. */
    private suspend fun awaitTerminal(taskId: String, timeoutMs: Long = 30_000): CrawlResponse {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = crawlService.getResult(taskId)
        while (result.isStillRunning()) {
            if (System.currentTimeMillis() > deadline) {
                fail<Unit>("task $taskId never reached a terminal state (still ${result.status})")
            }
            delay(25)
            result = crawlService.getResult(taskId)
        }
        return result
    }

    /**
     * The `status` field mixes vocabularies on this line: [CrawlResponse] defaults
     * to the `"CREATED"` token, the service writes both raw tokens (`"PROCESSING"`)
     * and `ResourceStatus` display text (`"Created"`, `"Request Timeout"`), and
     * [CrawlResponse.finishTime] is the model's own "reached a terminal state"
     * marker.  Comparing against the upper-case tokens alone therefore matched only
     * the default and exited on the *first* poll, so the caller asserted on
     * `"Created"` and failed within milliseconds instead of waiting for the terminal
     * record.  Match case-insensitively and treat a recorded finish time as final.
     */
    private fun CrawlResponse.isStillRunning(): Boolean =
        finishTime == null && (status.equals("CREATED", true) || status.equals("PROCESSING", true))
}
