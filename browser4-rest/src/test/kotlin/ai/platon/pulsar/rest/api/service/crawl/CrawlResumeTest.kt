package ai.platon.pulsar.rest.api.service.crawl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for what a resume *decides*, with no browser and no server involved.
 *
 * [planResume] is the contract of issue #606 in one function: already-succeeded
 * URLs are not requested again, terminally failed ones stay failed unless asked
 * for, the URLs that were in flight and the discovered frontier are re-submitted,
 * and a seed that was never picked up is run normally.  Everything else — the
 * request, the CLI, the report — only carries out what this decides.
 */
class CrawlResumeTest {

    private fun row(url: String, depth: Int = 1, restored: Boolean = true) = CrawlPageResult(
        url = url,
        title = "t",
        contentLength = 100,
        depth = depth,
        run = 1,
        restoredFromCheckpoint = restored
    )

    private fun lost(url: String, depth: Int = 1, reason: String? = "gone") =
        CrawlFailedPage(url = url, depth = depth, protocolStatus = 404, reason = reason)

    private fun pending(url: String, depth: Int = 1) =
        CrawlFailedPage(url = url, depth = depth, protocolStatus = 0, reason = CrawlLedger.REASON_ROUND_ENDED)

    private fun frontier(url: String, depth: Int = 2) =
        CrawlFailedPage(url = url, depth = depth, protocolStatus = 0, reason = CrawlLedger.REASON_FRONTIER)

    private fun seedCheckpoint(
        url: String,
        pages: List<CrawlPageResult> = emptyList(),
        failed: List<CrawlFailedPage> = emptyList(),
        outstanding: List<CrawlFailedPage> = emptyList(),
        frontier: List<CrawlFailedPage> = emptyList(),
        completed: Boolean = false,
        links: Int = 0,
    ) = CrawlSeedCheckpoint(
        url = url,
        completed = completed,
        status = if (completed) "fetched" else CrawlSeedCheckpoint.STATUS_INTERRUPTED,
        pages = pages,
        failed = failed,
        outstanding = outstanding,
        frontier = frontier,
        pagesExpected = pages.size + failed.size + outstanding.size,
        linksDiscovered = links
    )

    private fun checkpoint(vararg seeds: CrawlSeedCheckpoint) = CrawlCheckpoint.ofRequest(
        taskId = "t-1",
        request = CrawlRequest(url = seeds.firstOrNull()?.url ?: "", depth = 2),
        seedUrls = seeds.map { it.url },
        parallelTabs = 2,
        taskTimeoutMillis = 600_000L
    ).copy(seeds = seeds.toList())

    // -----------------------------------------------------------------
    // planResume
    // -----------------------------------------------------------------

    @Test
    @DisplayName("without a checkpoint every seed is run from scratch")
    fun noCheckpointMeansAFreshRun() {
        val plan = planResume("t-1", listOf("https://example.com/a", "https://example.com/b"), checkpoint = null)

        assertEquals(2, plan.seeds.size)
        assertTrue(plan.seeds.none { it.started })
        assertTrue(plan.seeds.all { it.needsRun && !it.completed })
        assertEquals(0, plan.skippedAlreadyFetched)
        assertEquals(2, plan.remaining, "each seed is at least one URL to fetch")
        assertFalse(plan.nothingToDo)
    }

    @Test
    @DisplayName("already-succeeded URLs are restored, counted, and never queued again")
    fun succeededUrlsAreNotRefetched() {
        val cp = checkpoint(
            seedCheckpoint(
                "https://example.com/a",
                pages = listOf(row("https://example.com/1"), row("https://example.com/2")),
                completed = true
            )
        )

        val plan = planResume("t-1", listOf("https://example.com/a"), cp)
        val seed = plan.seeds.single()

        assertEquals(2, plan.skippedAlreadyFetched)
        assertTrue(seed.work.isEmpty(), "nothing may be queued for a URL that already produced a row")
        assertTrue(seed.completed)
        assertFalse(seed.needsRun)
        assertTrue(plan.nothingToDo)
        assertEquals(2, seed.restoredExpected, "two rows are two expected pages")
        assertTrue(seed.restoredPages.all { it.restoredFromCheckpoint })
    }

    @Test
    @DisplayName("terminal failures stay failed, and only --retry-failed puts them back in the queue")
    fun failedUrlsStayFailedUnlessAsked() {
        val cp = checkpoint(
            seedCheckpoint(
                "https://example.com/a",
                pages = listOf(row("https://example.com/1")),
                failed = listOf(lost("https://example.com/dead"))
            )
        )

        val keep = planResume("t-1", listOf("https://example.com/a"), cp)
        assertTrue(keep.seeds.single().work.isEmpty(), "a terminally failed URL is not re-fetched by default")
        assertEquals(1, keep.seeds.single().restoredFailures.size)
        assertEquals(0, keep.seeds.single().retriedFailures)
        assertEquals(2, keep.seeds.single().restoredExpected, "the kept failure is still an expected page")
        assertTrue(keep.nothingToDo)

        val retry = planResume("t-1", listOf("https://example.com/a"), cp, retryFailed = true)
        val retried = retry.seeds.single()
        assertEquals(listOf("https://example.com/dead"), retried.work.map { it.url })
        assertEquals(1, retried.retriedFailures)
        assertTrue(retried.restoredFailures.isEmpty(), "a retried failure is not also reported as kept")
        assertEquals(1, retried.restoredExpected, "only the restored row is still accounted for")
        assertTrue(retried.needsRun)
    }

    @Test
    @DisplayName("the URLs that were in flight and the discovered frontier are re-submitted with their depths")
    fun outstandingAndFrontierBecomeWork() {
        val cp = checkpoint(
            seedCheckpoint(
                "https://example.com/a",
                pages = listOf(row("https://example.com/1")),
                outstanding = listOf(pending("https://example.com/2", depth = 1)),
                frontier = listOf(frontier("https://example.com/3", depth = 2))
            )
        )

        val plan = planResume("t-1", listOf("https://example.com/a"), cp)
        val seed = plan.seeds.single()

        assertEquals(
            listOf("https://example.com/2" to 1, "https://example.com/3" to 2),
            seed.work.map { it.url to it.depth },
            "shallowest first, each URL at the depth it was discovered at"
        )
        assertTrue(seed.isContinuation)
        assertEquals(2, plan.remaining)
        assertFalse(plan.nothingToDo)
        // The in-flight and frontier URLs were never counted as losses, so the
        // merge base is only the restored row.
        assertEquals(1, seed.restoredExpected)
    }

    @Test
    @DisplayName("the original URL spelling is preserved, query string and all")
    fun workItemsKeepTheSubmittedSpelling() {
        val withQuery = "https://example.com/product?id=42&color=red"
        val cp = checkpoint(seedCheckpoint("https://example.com/a", outstanding = listOf(pending(withQuery))))

        val plan = planResume("t-1", listOf("https://example.com/a"), cp)

        assertEquals(
            withQuery,
            plan.seeds.single().work.single().url,
            "the dedup key strips the query; re-fetching it would ask for a different page"
        )
    }

    @Test
    @DisplayName("a URL that already produced a row is not queued a second time through the frontier")
    fun frontierCannotResurrectASucceededUrl() {
        val cp = checkpoint(
            seedCheckpoint(
                "https://example.com/a",
                pages = listOf(row("https://example.com/1")),
                frontier = listOf(frontier("https://example.com/1#section", depth = 2))
            )
        )

        val plan = planResume("t-1", listOf("https://example.com/a"), cp)

        assertTrue(plan.seeds.single().work.isEmpty(), "the fragment-only variant is the same page")
    }

    @Test
    @DisplayName("a seed that was never picked up runs from its own URL, and the visited set still remembers the rest")
    fun unstartedSeedRunsFresh() {
        val cp = checkpoint(
            seedCheckpoint("https://example.com/a", pages = listOf(row("https://example.com/1")), completed = true),
            seedCheckpoint("https://example.com/b")
        )

        val plan = planResume("t-1", listOf("https://example.com/a", "https://example.com/b"), cp)

        val fresh = plan.seeds[1]
        assertFalse(fresh.started)
        assertTrue(fresh.work.isEmpty())
        assertTrue(fresh.needsRun, "a seed with no work state is fetched from scratch")
        // The seed of the first run is remembered so a link back to it is not queued.
        assertTrue(plan.seeds[0].visited.contains(normalizeForVisit("https://example.com/1")))
        assertEquals(1, plan.remaining, "only the seed that was never picked up is left")
        assertEquals(1, plan.skippedAlreadyFetched)
    }

    @Test
    @DisplayName("a completed round with a leftover frontier is still work to do")
    fun completedSeedWithFrontierIsNotDone() {
        val cp = checkpoint(
            seedCheckpoint(
                "https://example.com/a",
                pages = listOf(row("https://example.com/1")),
                frontier = listOf(frontier("https://example.com/2", depth = 1)),
                completed = true
            )
        )

        val plan = planResume("t-1", listOf("https://example.com/a"), cp)

        assertFalse(plan.seeds.single().completed, "the frontier was discovered after the round ended")
        assertTrue(plan.seeds.single().needsRun)
        assertFalse(plan.nothingToDo)
    }

    // -----------------------------------------------------------------
    // Merging the resumed run into the record
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the merged round is the union of both runs and the loss accounting still holds")
    fun mergedRoundKeepsTheAccountingLaw() {
        val restored = planResume(
            "t-1",
            listOf("https://example.com/a"),
            checkpoint(
                seedCheckpoint(
                    "https://example.com/a",
                    pages = listOf(row("https://example.com/1")),
                    failed = listOf(lost("https://example.com/dead")),
                    outstanding = listOf(pending("https://example.com/2"))
                )
            )
        ).seeds.single()

        // The resumed run fetched one page and lost one URL.
        val fetched = CrawlRound(
            pages = listOf(CrawlPageResult(url = "https://example.com/2", depth = 1, run = 2)),
            failedPages = listOf(lost("https://example.com/3")),
            pagesExpected = 2
        )

        val merged = mergeRestoredRound(restored, fetched)

        assertEquals(2, merged.pages.size, "the restored row and the newly fetched one")
        assertEquals(listOf("https://example.com/1", "https://example.com/2"), merged.pages.map { it.url })
        assertEquals(2, merged.failedPages.size, "the kept failure and the new one")
        assertEquals(4, merged.pagesExpected)
        assertEquals(merged.pagesExpected, merged.pages.size + merged.failedPages.size)
        assertEquals(2, merged.pages.last().run, "the newly fetched row is provenance-tagged with this run")
    }

    @Test
    @DisplayName("a fresh seed's round is not merged with anything")
    fun freshSeedRoundIsUnchanged() {
        val fetched = CrawlRound(pages = listOf(CrawlPageResult("https://example.com/a")), pagesExpected = 1)

        val untouched = mergeRestoredRound(null, fetched)
        assertSame(fetched, untouched)

        val notStarted = CrawlSeedResume(index = 0, seedUrl = "a", started = false, completed = false, work = emptyList())
        assertSame(fetched, mergeRestoredRound(notStarted, fetched))
    }

    @Test
    @DisplayName("a restored seed with nothing left is reported as a round of its own rows")
    fun restoredRoundReportsWhatTheFirstRunFetched() {
        val restored = planResume(
            "t-1",
            listOf("https://example.com/a"),
            checkpoint(
                seedCheckpoint(
                    "https://example.com/a",
                    pages = listOf(row("https://example.com/1"), row("https://example.com/2")),
                    failed = listOf(lost("https://example.com/dead")),
                    completed = true
                )
            )
        ).seeds.single()

        val round = restoredRound(restored)

        assertEquals(2, round.pages.size)
        assertEquals(1, round.failedPages.size)
        assertEquals(3, round.pagesExpected)
        assertEquals(round.pagesExpected, round.pages.size + round.failedPages.size)
        assertTrue(round.outstanding.isEmpty())
    }

    // -----------------------------------------------------------------
    // A settled round as a checkpoint slice
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a settled round checkpoints its rows, its losses and its frontier")
    fun settledRoundBecomesACheckpoint() {
        val round = CrawlRound(
            pages = listOf(CrawlPageResult("https://example.com/1", contentLength = 10, depth = 1)),
            failedPages = listOf(lost("https://example.com/dead")),
            pagesExpected = 2,
            frontier = listOf(frontier("https://example.com/3", depth = 2))
        )

        val slice = roundToSeedCheckpoint("https://example.com/a", requestDepth = 1, round = round, linksDiscovered = 5)

        assertTrue(slice.completed)
        assertEquals(listOf("https://example.com/1"), slice.pages.map { it.url })
        assertEquals(listOf("https://example.com/dead"), slice.failed.map { it.url })
        assertTrue(slice.outstanding.isEmpty())
        assertEquals(1, slice.frontier.size, "the frontier is work for a resume, not a loss")
        assertEquals(2, slice.pagesExpected)
        assertEquals(5, slice.linksDiscovered)
        assertEquals(1, slice.remaining(), "the discovered-but-unqueued link is the only work left")
    }

    @Test
    @DisplayName("a timed-out round checkpoints its unsettled URLs as outstanding, not as losses")
    fun timedOutRoundCheckpointsOutstandingWork() {
        val outstanding = pending("https://example.com/2")
        val round = CrawlRound(
            pages = listOf(CrawlPageResult("https://example.com/1", contentLength = 10, depth = 1)),
            // The report carries the outstanding URL as a loss so the page count is
            // accounted for; the checkpoint must not read that as "it failed".
            failedPages = listOf(lost("https://example.com/dead"), outstanding),
            pagesExpected = 3,
            timedOut = true,
            timeoutError = "ran out of time",
            outstanding = listOf(outstanding)
        )

        val slice = roundToSeedCheckpoint("https://example.com/a", requestDepth = 1, round = round, linksDiscovered = 0)

        assertFalse(slice.completed)
        assertEquals(listOf("https://example.com/dead"), slice.failed.map { it.url })
        assertEquals(listOf("https://example.com/2"), slice.outstanding.map { it.url })
        assertEquals(3, slice.pagesExpected)
        assertEquals(1, slice.remaining())

        val plan = planResume(
            "t-1", listOf("https://example.com/a"),
            CrawlCheckpoint.ofRequest(
                taskId = "t-1",
                request = CrawlRequest(url = "https://example.com/a", depth = 1),
                seedUrls = listOf("https://example.com/a"),
                parallelTabs = 1,
                taskTimeoutMillis = 1_000L
            ).copy(seeds = listOf(slice))
        )
        assertEquals(listOf("https://example.com/2"), plan.seeds.single().work.map { it.url })
    }

    @Test
    @DisplayName("a depth-0 row that delivered nothing is a failure, not an already-fetched URL")
    fun depthZeroEmptyRowIsAFailure() {
        val round = CrawlRound(
            pages = listOf(
                CrawlPageResult("https://example.com/good", contentLength = 512, depth = 0),
                CrawlPageResult("https://example.com/empty", contentLength = 0, depth = 0, extractionError = "0 bytes"),
                CrawlPageResult("https://example.com/null", contentLength = null, depth = 0)
            ),
            pagesExpected = 3
        )

        val slice = roundToSeedCheckpoint("https://example.com/good", requestDepth = 0, round = round, linksDiscovered = 0)

        assertEquals(listOf("https://example.com/good"), slice.pages.map { it.url })
        assertEquals(
            listOf("https://example.com/empty", "https://example.com/null"),
            slice.failed.map { it.url },
            "a URL the engine never delivered content for must not look fetched"
        )
        assertEquals(3, slice.pagesExpected, "the accounting is unchanged by the reclassification")
        assertEquals(3, slice.pages.size + slice.failed.size)
        assertEquals(0, slice.remaining(), "a bulk fetch has its own retries; there is no frontier to follow")
    }

    @Test
    @DisplayName("a depth>1 row is never reclassified: only a delivered document becomes a row there")
    fun depthOneRowsAreTakenAtFaceValue() {
        val round = CrawlRound(
            pages = listOf(CrawlPageResult("https://example.com/1", contentLength = 0, depth = 1)),
            pagesExpected = 1
        )

        val slice = roundToSeedCheckpoint("https://example.com/a", requestDepth = 1, round = round, linksDiscovered = 0)

        assertEquals(1, slice.pages.size, "the round only records delivered documents, so a row is a page")
    }

    // -----------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the resume note says what was skipped, what is left, and what stayed failed")
    fun resumeNoteExplainsTheRun() {
        val plan = planResume(
            "t-1",
            listOf("https://example.com/a"),
            checkpoint(
                seedCheckpoint(
                    "https://example.com/a",
                    pages = listOf(row("https://example.com/1")),
                    failed = listOf(lost("https://example.com/dead")),
                    outstanding = listOf(pending("https://example.com/2"))
                )
            )
        )

        val note = buildResumeNote(plan, run = 2, interruptedAt = Instant.parse("2026-01-02T03:04:05Z"), remainingBefore = 9)

        assertTrue(note.contains("resumed run 2"), note)
        assertTrue(note.contains("2026-01-02T03:04:05Z"), note)
        assertTrue(note.contains("1 already-fetched URL(s)"), note)
        assertTrue(note.contains("down from 9"), note)
        assertTrue(note.contains("stay terminally failed"), note)
        assertTrue(note.contains("--retry-failed"), note)
    }

    @Test
    @DisplayName("restored seed statuses report the first run's outcome instead of inventing one")
    fun restoredSeedStatusesCarryTheFirstRunOutcome() {
        val plan = planResume(
            "t-1",
            listOf("https://example.com/a", "https://example.com/b"),
            checkpoint(
                seedCheckpoint("https://example.com/a", pages = listOf(row("https://example.com/1")), completed = true),
                seedCheckpoint(
                    "https://example.com/b",
                    pages = listOf(row("https://example.com/2")),
                    outstanding = listOf(pending("https://example.com/3"))
                )
            )
        )

        val statuses = restoredSeedStatuses(plan)

        assertEquals("fetched", statuses[0].status)
        assertEquals(1, statuses[0].pagesReturned)
        assertEquals(CrawlSeedCheckpoint.STATUS_INTERRUPTED, statuses[1].status)
        assertEquals(1, statuses[1].pagesReturned)
    }
}
