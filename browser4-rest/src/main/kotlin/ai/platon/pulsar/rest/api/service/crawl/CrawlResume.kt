package ai.platon.pulsar.rest.api.service.crawl

/**
 * One URL a (resumed) round must fetch, with the depth it was discovered at.
 *
 * Depth is queue-time bookkeeping and travels with the URL: a resumed crawl that
 * guessed the depth would label its rows wrongly and expand a page that is
 * already at `maxDepth`.
 */
data class CrawlWorkItem(val url: String, val depth: Int) {
    /** The crawl's one dedup key, so two spellings of a URL never become two work items. */
    val key: String get() = normalizeForVisit(url)
}

/**
 * What a resumed run does about **one seed**.
 *
 * Either the seed is run fresh (`started == false`: it was never picked up), or it
 * is continued from the URLs its first round left behind ([work]), with
 * [restoredPages] / [restoredFailures] carried into the merged record.  A seed
 * with nothing left (`needsRun == false`) is not touched at all — that is the
 * "already succeeded URLs are not re-fetched" promise, made visible as
 * [skippedAlreadyFetched].
 */
data class CrawlSeedResume(
    val index: Int,
    val seedUrl: String,
    /** True when the first run had already handed URLs to the session for this seed. */
    val started: Boolean,
    /** True when this seed has nothing left to do. */
    val completed: Boolean,
    /** URLs to hand to the session, in depth order (outstanding first, then the frontier). */
    val work: List<CrawlWorkItem>,
    /** Rows the first run recorded for this seed; restored, never re-fetched. */
    val restoredPages: List<CrawlPageResult> = emptyList(),
    /** Terminal failures the first run reported and this run keeps failed. */
    val restoredFailures: List<CrawlFailedPage> = emptyList(),
    /** Previously failed URLs this run re-submits because `--retry-failed` was given. */
    val retriedFailures: Int = 0,
    /** Identities this crawl already handled — seeds a resumed round's `visited` set. */
    val visited: Set<String> = emptySet(),
    /** URL identity -> queue-time depth, for every URL the resumed round may see again. */
    val depths: Map<String, Int> = emptyMap(),
    val linksDiscovered: Int = 0,
    /** The seed's last reported status, so the merged report does not invent a new one. */
    val status: String = CrawlSeedCheckpoint.STATUS_PENDING,
    val error: String? = null,
) {
    /**
     * The `pagesExpected` this seed already owns before the resumed round starts:
     * one per restored row and one per kept failure.
     *
     * The outstanding URLs are deliberately **not** part of it — they are
     * re-submitted by the resumed round and counted there, and counting them twice
     * is exactly how the loss accounting would break.
     */
    val restoredExpected: Int get() = restoredPages.size + restoredFailures.size

    /** URLs that will not be requested again. */
    val skippedAlreadyFetched: Int get() = restoredPages.size

    /** True when the resumed run has to touch this seed at all. */
    val needsRun: Boolean get() = !completed && (work.isNotEmpty() || !started)

    /**
     * True when this seed is continued from a restored work queue rather than run
     * from its seed URL.  A continued round submits [work] instead of the seed.
     */
    val isContinuation: Boolean get() = started && work.isNotEmpty()
}

/**
 * The work a resume has to do, one entry per seed of the original run.
 *
 * Produced by [planResume] from a [CrawlCheckpoint] alone, so the decision of
 * "what is left, what is skipped, what stays failed" is a pure function of the
 * persisted state — testable without a browser, and identical whatever the run
 * that reads it.
 */
data class CrawlResumePlan(
    val taskId: String,
    val seeds: List<CrawlSeedResume>,
    val retryFailed: Boolean,
) {
    /** URLs this resume will not request again, counted for the report. */
    val skippedAlreadyFetched: Int get() = seeds.sumOf { it.skippedAlreadyFetched }

    /** URLs this resume still has to fetch: the work queues plus the seeds never started. */
    val remaining: Int get() = seeds.sumOf { seed -> seed.work.size + if (seed.started) 0 else 1 }

    /** True when the resume has nothing to do: every seed of the task already settled. */
    val nothingToDo: Boolean get() = seeds.none { it.needsRun }
}

/**
 * The work state one live round publishes so an interrupted crawl has something to
 * resume from.
 *
 * A snapshot, not a reference: the round's ledger is mutated by parse handlers
 * while the publisher is running, and a resumed crawl must never be handed a
 * half-updated view.  The counts obey the crawl's accounting law
 * (`pages + failed + outstanding == pagesExpected`), so a checkpoint written from
 * a snapshot is a state the crawl could have reached.
 */
internal data class CrawlWorkSnapshot(
    val pages: List<CrawlPageResult>,
    val linksDiscovered: Int,
    /** Terminal failures: the URLs that reached a terminal outcome without a row. */
    val failed: List<CrawlFailedPage>,
    /** Submitted URLs that have not settled — in flight, or left behind by a timeout. */
    val outstanding: List<CrawlFailedPage>,
    /** Discovered links that were never submitted. */
    val frontier: List<CrawlFailedPage> = emptyList(),
    val pagesExpected: Int = 0,
    /** How many URLs have settled, so the publisher can throttle its writes. */
    val settled: Int = pages.size + failed.size,
    /** The status the round would report for this seed right now. */
    val status: String = "fetched",
    /**
     * True when the round is done with this seed: every submitted URL settled and
     * there is no work left.  A snapshot with `completed = false` is resumable.
     */
    val completed: Boolean = false,
    val error: String? = null,
) {
    /** The checkpoint slice this snapshot represents, ready to be persisted. */
    fun toSeedCheckpoint(seedUrl: String, depth: Int): CrawlSeedCheckpoint = CrawlSeedCheckpoint(
        url = seedUrl,
        depth = depth,
        completed = completed,
        status = if (completed) status else CrawlSeedCheckpoint.STATUS_INTERRUPTED,
        pages = pages,
        failed = failed,
        outstanding = outstanding,
        frontier = frontier,
        pagesExpected = pagesExpected,
        linksDiscovered = linksDiscovered,
        error = error
    )

    /** The accounting law the checkpoint's fields have to satisfy. */
    fun isConsistent(): Boolean = pages.size + failed.size + outstanding.size == pagesExpected
}

/**
 * Plan what a resume of [checkpoint] has to do.
 *
 * The rules, in one place because they are the contract the issue states:
 *
 *  * **Already-succeeded URLs are not re-fetched.**  Their rows travel in the
 *    checkpoint and are restored into the merged record.
 *  * **Terminally failed URLs stay failed** unless [retryFailed] is set: a third
 *    load of a twice-failed URL buys nothing but time
 *    ([CrawlLedger.DEFAULT_MAX_DELIVERY_ATTEMPTS]).
 *  * **Outstanding URLs are re-submitted** (with a fresh delivery-attempt budget,
 *    which the new round's ledger grants them).
 *  * **The frontier is restored**, so a `--depth >= 1` crawl continues from where
 *    it stopped instead of restarting from the seeds.
 *  * **A seed that was never picked up is run normally** — there is no work state
 *    to continue it from.
 *
 * @param checkpoint the persisted state.  `null` means nothing survived, and every
 *   seed is planned as a fresh run — the caller decides what to do with that
 *   (`CrawlService.resume` refuses such a task rather than re-crawling silently,
 *   because a "resume" that re-fetches from the seeds is not a resume).
 */
internal fun planResume(
    taskId: String,
    seedUrls: List<String>,
    checkpoint: CrawlCheckpoint?,
    retryFailed: Boolean = false,
): CrawlResumePlan {
    val seeds = seedUrls.mapIndexed { index, seedUrl ->
        val slice = checkpoint?.seed(index)?.takeIf { it.url.isNotBlank() }
        if (slice == null || !slice.started()) {
            // Never picked up: nothing to continue from, so the seed runs from its
            // own URL exactly as it did the first time.
            return@mapIndexed CrawlSeedResume(
                index = index,
                seedUrl = seedUrl,
                started = false,
                completed = false,
                work = emptyList(),
                status = CrawlSeedCheckpoint.STATUS_PENDING
            )
        }

        val succeeded = slice.pages.map { normalizeForVisit(it.url) }.toSet()
        val retryable = if (retryFailed) slice.failed else emptyList()
        val restoredFailures = if (retryFailed) emptyList() else slice.failed
        val work = (slice.outstanding + slice.frontier + retryable)
            // A URL that already produced a row must never be requested again, and
            // one identity may only be queued once.
            .filterNot { normalizeForVisit(it.url) in succeeded }
            .distinctBy { normalizeForVisit(it.url) }
            .sortedWith(compareBy({ it.depth }, { it.url }))
            .map { CrawlWorkItem(it.url, it.depth) }
        val depths = work.associate { it.key to it.depth }
        val retriedInWork = if (retryable.isEmpty()) 0 else {
            val retriedKeys = retryable.map { normalizeForVisit(it.url) }.toSet()
            work.count { it.key in retriedKeys }
        }

        CrawlSeedResume(
            index = index,
            seedUrl = seedUrl,
            started = true,
            // A round that settled everything *and* left no frontier behind is done.
            completed = slice.completed && work.isEmpty(),
            work = work,
            restoredPages = slice.pages.map { it.copy(restoredFromCheckpoint = true) },
            restoredFailures = restoredFailures,
            retriedFailures = retriedInWork,
            visited = slice.knownUrls(),
            depths = depths,
            linksDiscovered = slice.linksDiscovered,
            status = slice.status,
            error = slice.error
        )
    }

    return CrawlResumePlan(taskId = taskId, seeds = seeds, retryFailed = retryFailed)
}

/**
 * What a `resume` request did, by `POST /api/crawl/{id}/resume` and
 * `browser4-cli crawl resume`.
 *
 * A rejection is an answer, not an error: `resumed = false` with a [message] that
 * says why (already completed, nothing left, no checkpoint) is what the caller
 * needs to decide what to do next.  Only a task with a live worker is refused as
 * an HTTP error, because that one is a conflict with a running operation.
 */
data class CrawlResumeResult(
    val taskId: String,
    /** True when a worker was started to continue the task. */
    val resumed: Boolean,
    /** The status the task reports after the call. */
    val status: String,
    /** Human-readable explanation, for both outcomes. */
    val message: String,
    /** URLs the resumed run still has to fetch. */
    val remaining: Int = 0,
    /** URLs that came from the checkpoint and were not requested again. */
    val skippedAlreadyFetched: Int = 0,
    /** How many times this task has been resumed, after this call. */
    val resumeCount: Int = 0,
)

/**
 * The round of a seed the resume plan has nothing left for: what the first run
 * fetched, reported as a round so the terminal write does not have to special-case
 * it.
 *
 * Its `pagesExpected` is the restored base (`rows + kept failures`) and it reports
 * no outstanding work — that is exactly what "nothing left for this seed" means.
 */
internal fun restoredRound(seed: CrawlSeedResume): CrawlRound = CrawlRound(
    pages = seed.restoredPages,
    failedPages = seed.restoredFailures,
    pagesExpected = seed.restoredExpected
)

/**
 * Merge what an interrupted run left for a seed into the round its resume executed.
 *
 * A continued round reports **only what this run fetched** — the ledger it counts
 * with starts empty — so the union is made here, once, instead of in every consumer
 * of a round (the terminal write, the progress publishes and the checkpoint all
 * read the same merged value).
 *
 * The accounting stays exact by construction: the restored base is
 * `restored rows + kept failures`, and the round's own law
 * (`pages + failedPages == pagesExpected`) is preserved by adding it to both sides.
 */
internal fun mergeRestoredRound(restored: CrawlSeedResume?, fetched: CrawlRound): CrawlRound {
    if (restored == null || !restored.started) return fetched
    if (restored.restoredPages.isEmpty() && restored.restoredFailures.isEmpty()) return fetched
    return CrawlRound(
        pages = restored.restoredPages + fetched.pages,
        failedPages = restored.restoredFailures + fetched.failedPages,
        pagesExpected = restored.restoredExpected + fetched.pagesExpected,
        timedOut = fetched.timedOut,
        timeoutError = fetched.timeoutError,
        // The outstanding and frontier URLs are this run's — the restored ones were
        // re-submitted by it, so they are already part of these lists.
        outstanding = fetched.outstanding,
        frontier = fetched.frontier
    )
}

/**
 * A settled round as the checkpoint slice of its seed, enforcing the checkpoint's
 * accounting law in the process.
 *
 * The one place a row becomes a *failure* rather than a success is a depth-0 bulk
 * fetch: that strategy reports a row even when the load delivered nothing (after
 * its own retries), because the caller asked for "try this URL" rather than "crawl
 * this site".  A resume must not read such a row as "already fetched" — nothing was
 * — so it is moved to `failed` (the counts are unchanged: the URL was submitted
 * once and is still worth exactly one outcome), and only `--retry-failed` puts it
 * back in the queue.
 *
 * @param requestDepth the crawl's `--depth`, which is what decides whether the
 *   bulk-fetch reclassification applies.  The slice's own [CrawlSeedCheckpoint.depth]
 *   is always 0: a seed is the page a round starts from.
 */
internal fun roundToSeedCheckpoint(
    seedUrl: String,
    requestDepth: Int,
    round: CrawlRound,
    linksDiscovered: Int,
): CrawlSeedCheckpoint {
    val outstandingKeys = round.outstanding.map { normalizeForVisit(it.url) }.toSet()
    val (delivered, undelivered) = if (requestDepth == 0) {
        round.pages.partition { (it.contentLength ?: 0L) > 0L }
    } else {
        round.pages to emptyList()
    }
    val failed = round.failedPages.filterNot { normalizeForVisit(it.url) in outstandingKeys } +
        undelivered.map {
            CrawlFailedPage(
                url = it.url,
                depth = it.depth,
                protocolStatus = 0,
                reason = it.extractionError ?: REASON_NOT_DELIVERED_DEPTH0
            )
        }
    return CrawlSeedCheckpoint(
        url = seedUrl,
        depth = 0,
        completed = !round.timedOut,
        status = if (round.timedOut) CrawlSeedCheckpoint.STATUS_INTERRUPTED else "fetched",
        pages = delivered,
        failed = failed,
        outstanding = round.outstanding,
        frontier = round.frontier,
        pagesExpected = round.pagesExpected,
        linksDiscovered = linksDiscovered,
        error = round.timeoutError
    )
}

/** Why a depth-0 bulk fetch left a URL without content. */
internal const val REASON_NOT_DELIVERED_DEPTH0 =
    "the bulk fetch returned no content for this URL after its retries"

/** The seed statuses the restored state carries, for a run that has not reported its own yet. */
internal fun restoredSeedStatuses(plan: CrawlResumePlan): List<CrawlSeedStatus> =
    plan.seeds.map { seed ->
        CrawlSeedStatus(
            url = seed.seedUrl,
            status = if (seed.completed) "fetched" else seed.status,
            pagesReturned = seed.restoredPages.size,
            error = seed.error
        )
    }

/**
 * What a resume did, in one line for the terminal record's diagnostic.
 *
 * A resumed crawl's result is a merge, and a merge that does not say so reads as a
 * single run that happened to produce more rows than the site has pages.
 */
internal fun buildResumeNote(
    plan: CrawlResumePlan,
    run: Int,
    interruptedAt: java.time.Instant?,
    remainingBefore: Int,
): String {
    val skipped = plan.skippedAlreadyFetched
    val keptFailures = plan.seeds.sumOf { it.restoredFailures.size }
    val retried = plan.seeds.sumOf { it.retriedFailures }
    return buildString {
        append("resumed run ").append(run)
        if (interruptedAt != null) {
            append(" (continuing a run interrupted at ").append(interruptedAt).append(")")
        }
        append(": ").append(skipped).append(" already-fetched URL(s) restored from the checkpoint, ")
        append(plan.remaining).append(" URL(s) left to fetch")
        if (remainingBefore > plan.remaining) {
            append(" (down from ").append(remainingBefore).append(")")
        }
        if (keptFailures > 0) {
            append("; ").append(keptFailures).append(" URL(s) stay terminally failed")
            append(" (pass --retry-failed to retry them)")
        }
        if (retried > 0) {
            append("; ").append(retried).append(" previously failed URL(s) are being retried")
        }
    }
}
