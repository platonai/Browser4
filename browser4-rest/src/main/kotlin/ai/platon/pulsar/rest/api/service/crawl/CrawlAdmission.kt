package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.agentic.tools.CodedFailure
import ai.platon.pulsar.agentic.tools.ToolErrorCode

/**
 * Admission control for crawl work — the asynchronous queue's bound (requirement 9.3).
 *
 * The rate limiter bounds the *rate* of submissions (0.2/s, burst 2), but nothing
 * bounded how many tasks could be running at once: every accepted submission starts
 * its own coroutine and keeps a browser budget, a checkpoint and a row log until it
 * finishes, so a client that submits steadily grows the backlog without limit
 * (`CrawlService.jobStore` has no size bound). Once the queue reaches
 * [DEFAULT_MAX_RUNNING_TASKS] the next submission is refused **before** anything is
 * created — no task record, no checkpoint — so a retry is idempotent.
 *
 * The bound is configurable (`crawl.maxRunningTasks`, also usable as
 * `-Dcrawl.maxRunningTasks`); **0 or a negative value disables it**. The default sits
 * far above a normal crawl fleet, so it only binds when the backend is genuinely
 * behind rather than throttling ordinary work.
 */
object CrawlAdmission {

    /** Concurrent crawl tasks admitted by default. */
    const val DEFAULT_MAX_RUNNING_TASKS = 64

    /** How long a refused submitter is told to wait before retrying. */
    const val RETRY_AFTER_MS = 5_000L

    /**
     * The refusal for a submission, or `null` when the queue has room.
     *
     * @param toolName the caller's name for the operation (`crawl.submit`), so the
     *   MCP clients see the tool they actually called in the message
     * @param runningTasks tasks executing right now
     *   ([CrawlService.runningTaskCount])
     * @param maxRunningTasks the configured bound; `<= 0` means unlimited
     */
    fun refusal(toolName: String, runningTasks: Int, maxRunningTasks: Int): String? {
        if (maxRunningTasks <= 0 || runningTasks < maxRunningTasks) return null
        return "crawl queue is full for $toolName: $runningTasks task(s) running " +
            "(limit $maxRunningTasks); wait for a task to finish or cancel one " +
            "(retry after $RETRY_AFTER_MS ms)"
    }
}

/**
 * A crawl submission refused because the queue is saturated.
 *
 * Carries [ToolErrorCode.RATE_LIMITED] — the same code the rate limiter rejects with —
 * so both MCP channels answer a saturated queue exactly like a throttled call, and the
 * REST endpoint maps it to `429`. `retryAfterMs` travels in the message because both
 * channels derive the code from [CodedFailure], not from the text.
 */
class CrawlQueueFullException(message: String) : RuntimeException(message), CodedFailure {
    override val code: ToolErrorCode get() = ToolErrorCode.RATE_LIMITED
}
