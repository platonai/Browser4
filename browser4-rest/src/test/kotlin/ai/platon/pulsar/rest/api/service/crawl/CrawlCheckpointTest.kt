package ai.platon.pulsar.rest.api.service.crawl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Tests for the durable half of resume: the checkpoint model, the file it is
 * written to, and the write policy that keeps a large crawl from rewriting it
 * hundreds of times a second.
 *
 * The file-level tests are the ones that matter for issue #606: a `SIGKILL` between
 * two writes must leave a *resumable* state behind, never a corrupt one, and a
 * checkpoint that cannot be parsed must degrade to "no checkpoint" (the task is
 * reported as not resumable) instead of taking the server down.
 */
class CrawlCheckpointTest {

    private fun row(url: String, depth: Int = 1, run: Int = 1, restored: Boolean = false) = CrawlPageResult(
        url = url,
        title = "title of $url",
        contentLength = 1024,
        depth = depth,
        fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
        run = run,
        restoredFromCheckpoint = restored
    )

    private fun failure(url: String, depth: Int = 1, reason: String = "gone") =
        CrawlFailedPage(url = url, depth = depth, protocolStatus = 404, reason = reason)

    private fun seed(
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

    private fun checkpoint(
        taskId: String = "task-1",
        seeds: List<CrawlSeedCheckpoint> = emptyList(),
        seedUrls: List<String> = seeds.map { it.url }.ifEmpty { listOf("https://example.com/a") },
        run: Int = 1,
    ) = CrawlCheckpoint.ofRequest(
        taskId = taskId,
        request = CrawlRequest(url = seedUrls.firstOrNull() ?: "", depth = 1, args = "-outLink \"a\""),
        seedUrls = seedUrls,
        parallelTabs = 4,
        taskTimeoutMillis = 600_000L,
        run = run
    ).copy(seeds = seeds)

    // -----------------------------------------------------------------
    // The model's accounting
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a checkpoint's remaining work is its in-flight and frontier URLs plus one per unstarted seed")
    fun remainingCountsWhatIsLeft() {
        val cp = checkpoint(
            seeds = listOf(
                seed("https://example.com/a", pages = listOf(row("https://example.com/1"))),
                seed(
                    "https://example.com/b",
                    outstanding = listOf(failure("https://example.com/2")),
                    frontier = listOf(failure("https://example.com/3"), failure("https://example.com/4"))
                ),
                seed("https://example.com/c")
            )
        )

        // a: settled, nothing left.  b: one in flight + two discovered but unqueued.
        // c: never picked up, so at least its own URL.
        assertEquals(4, cp.remaining())
        assertEquals(1, cp.skippedAlreadyFetched())
        assertTrue(cp.resumable)
    }

    @Test
    @DisplayName("a completed seed reports no remaining work even when it has a frontier left over")
    fun completedSeedStillContributesItsFrontier() {
        val cp = checkpoint(
            seeds = listOf(
                seed(
                    "https://example.com/a",
                    pages = listOf(row("https://example.com/1")),
                    frontier = listOf(failure("https://example.com/2")),
                    completed = true
                )
            )
        )

        // The round completed, but a link it discovered after that is genuine work:
        // a resume follows it instead of declaring the crawl finished.
        assertEquals(1, cp.remaining())
    }

    @Test
    @DisplayName("an empty-seed checkpoint is not resumable: there is no input contract to continue")
    fun checkpointWithoutSeedsIsNotResumable() {
        val cp = checkpoint(seedUrls = emptyList(), seeds = emptyList())
        assertFalse(cp.resumable)
        assertEquals(0, cp.remaining())
    }

    // -----------------------------------------------------------------
    // Crash-safe storage
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a checkpoint survives a save/load round trip, rows included")
    fun roundTripPreservesEverything(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        val original = checkpoint(
            seeds = listOf(
                seed("https://example.com/a", pages = listOf(row("https://example.com/1")), completed = true, links = 7),
                seed("https://example.com/b", outstanding = listOf(failure("https://example.com/2")))
            ),
            seedUrls = listOf("https://example.com/a", "https://example.com/b")
        )

        assertTrue(store.save(original) > 0)

        val loaded = requireNotNull(store.load(original.taskId))
        assertEquals(original.taskId, loaded.taskId)
        assertEquals(original.seedUrls, loaded.seedUrls)
        assertEquals(1, loaded.seeds[0].pages.size)
        assertEquals("https://example.com/1", loaded.seeds[0].pages.first().url)
        assertEquals(7, loaded.seeds[0].linksDiscovered)
        assertEquals(1, loaded.seeds[1].outstanding.size)
        assertEquals(listOf("task-1"), store.taskIds())
    }

    @Test
    @DisplayName("the input contract travels with the work state, so a resume needs nothing re-typed")
    fun inputContractSurvives(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        val request = CrawlRequest(
            url = "https://example.com/portal",
            args = "-outLink \"a.product\" -topLinks 20 -ignoreUrlQuery",
            depth = 3,
            sql = "select dom_first_text(dom, 'h1') as title",
            parallelTabs = 6,
            taskTimeoutMillis = 1_800_000L
        )

        val saved = CrawlCheckpoint.ofRequest(
            taskId = "task-1",
            request = request,
            seedUrls = listOf("https://example.com/portal"),
            parallelTabs = 6,
            taskTimeoutMillis = 1_800_000L
        )
        assertTrue(store.save(saved) > 0)

        val loaded = requireNotNull(store.load("task-1"))
        // The DTO is rebuilt from the flat fields; every value that shapes the run
        // has to come back, or a resume would silently crawl something else.
        assertEquals(request.url, loaded.request.url)
        assertEquals(request.depth, loaded.request.depth)
        assertEquals(request.args, loaded.request.args)
        assertEquals(request.sql, loaded.request.sql)
        assertEquals(request.parallelTabs, loaded.request.parallelTabs)
        assertEquals(request.taskTimeoutMillis, loaded.request.taskTimeoutMillis)
        assertEquals(request, loaded.request)
    }

    @Test
    @DisplayName("a truncated checkpoint falls back to the last known-good copy instead of losing the work")
    fun truncatedCheckpointFallsBackToThePreviousState(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        // First write: one row fetched.
        val first = checkpoint(seeds = listOf(seed("https://example.com/a", pages = listOf(row("https://example.com/1")))))
        store.save(first)
        // Second write: the row plus an in-flight URL.  This is the state the
        // process died while writing.
        val second = checkpoint(
            seeds = listOf(
                seed(
                    "https://example.com/a",
                    pages = listOf(row("https://example.com/1")),
                    outstanding = listOf(failure("https://example.com/2"))
                )
            )
        )
        store.save(second)

        // Simulate the half-written file: the last line never made it.
        val file = store.fileFor("task-1")
        val text = Files.readString(file)
        Files.writeString(file, text.substring(0, text.length / 2))

        val loaded = requireNotNull(store.load("task-1")) { "a truncated checkpoint must not read as 'no checkpoint'" }
        assertEquals(1, loaded.seeds[0].pages.size, "the rows of the previous good copy are still there")
        assertEquals("https://example.com/1", loaded.seeds[0].pages.first().url)
    }

    @Test
    @DisplayName("an unreadable checkpoint degrades to null, and a missing one is simply null")
    fun unreadableCheckpointIsNotFatal(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        store.save(checkpoint())
        Files.writeString(store.fileFor("task-1"), "{ this is not json")

        assertNull(store.load("task-1"), "a checkpoint that cannot be parsed must not throw")
        assertNull(store.load("never-written"))
        assertNull(store.load(""))
    }

    @Test
    @DisplayName("deleting a checkpoint removes every file it owns, and only its own")
    fun deleteRemovesOnlyItsOwnFiles(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        store.save(checkpoint(taskId = "keep-me"))
        store.save(checkpoint(taskId = "drop-me"))
        Files.writeString(dir.resolve("operator-notes.txt"), "do not delete me")

        store.delete("drop-me")

        assertNull(store.load("drop-me"))
        assertNotNull(store.load("keep-me"))
        assertEquals(listOf("keep-me"), store.taskIds())
        assertTrue(Files.exists(dir.resolve("operator-notes.txt")))
    }

    @Test
    @DisplayName("deleteAll clears the store's own files and leaves anything else alone")
    fun deleteAllClearsTheStore(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        store.save(checkpoint(taskId = "a"))
        store.save(checkpoint(taskId = "b"))
        Files.writeString(dir.resolve("unrelated.jsonl"), "not ours")

        val removed = store.deleteAll()

        assertTrue(removed >= 2, "both checkpoints are removed, got $removed")
        assertTrue(store.taskIds().isEmpty())
        assertTrue(Files.exists(dir.resolve("unrelated.jsonl")))
    }

    @Test
    @DisplayName("saving a blank task id is refused rather than written to a file named '.json'")
    fun blankTaskIdIsRefused(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        assertEquals(-1, store.save(checkpoint(taskId = "")))
        assertTrue(store.taskIds().isEmpty())
    }

    // -----------------------------------------------------------------
    // Row log: a settled row is durable the moment it exists
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a row appended after the last state write is restored, so its URL is never fetched twice")
    fun appendedRowsSurviveASigkill(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        // The state was written with one row and one URL still in flight.
        val state = checkpoint(
            seeds = listOf(
                seed(
                    "https://example.com/a",
                    pages = listOf(row("https://example.com/1")),
                    outstanding = listOf(failure("https://example.com/2"))
                )
            )
        )
        assertTrue(store.save(state) > 0)

        // Then the in-flight URL settles — and the process is killed before the next
        // state write.  This is the measured failure the row log exists for: without
        // it the resumed crawl requested /1 and /2 again.
        assertTrue(store.appendRow("task-1", 0, row("https://example.com/2")))

        val loaded = requireNotNull(store.load("task-1"))
        val seed = loaded.seeds.single()

        assertEquals(
            listOf("https://example.com/1", "https://example.com/2"),
            seed.pages.map { it.url },
            "both rows must be known after the restart"
        )
        assertTrue(seed.outstanding.isEmpty(), "the row settled, so it is no longer in flight")
        assertFalse(loaded.hasWork() || seed.remaining() > 0, "nothing is left to fetch for this seed")
        assertTrue(
            seed.completed,
            "every URL the round was waiting for has a row and no frontier is left"
        )
        assertEquals(
            seed.pagesExpected, seed.pages.size + seed.failed.size + seed.outstanding.size,
            "the accounting law must hold after the merge"
        )
    }

    @Test
    @DisplayName("a row for a URL the state never recorded as submitted is counted as expected")
    fun appendedRowUnknownToTheStateStillBalances(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        store.save(checkpoint(seeds = listOf(seed("https://example.com/a", pages = emptyList()))))

        // The row was appended after a state write that predates the submission too.
        store.appendRow("task-1", 0, row("https://example.com/late"))

        val seed = requireNotNull(store.load("task-1")).seeds.single()

        assertEquals(listOf("https://example.com/late"), seed.pages.map { it.url })
        assertEquals(1, seed.pagesExpected, "the crawl did set out to fetch it, so it is one expected page")
        assertEquals(seed.pagesExpected, seed.pages.size + seed.failed.size + seed.outstanding.size)
        assertTrue(seed.started(), "a seed with a row has been picked up")
        assertEquals(
            CrawlSeedCheckpoint.STATUS_INTERRUPTED,
            seed.status,
            "the state never saw the seed finish, so the honest label is 'interrupted', not 'pending'"
        )
        assertFalse(seed.completed, "a round with an unknown number of outstanding URLs is not complete")
    }

    @Test
    @DisplayName("appended rows are read in order, and a corrupt line costs one row, not the checkpoint")
    fun rowLogSkipsCorruptLines(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        store.save(checkpoint(seeds = listOf(seed("https://example.com/a"))))
        store.appendRow("task-1", 0, row("https://example.com/1"))
        // A partially written line (the classic crash artefact).
        Files.writeString(
            store.rowsFileFor("task-1"),
            "{ this is not a row }\n",
            java.nio.file.StandardOpenOption.APPEND
        )
        store.appendRow("task-1", 0, row("https://example.com/2"))

        val seed = requireNotNull(store.load("task-1")).seeds.single()

        assertEquals(listOf("https://example.com/1", "https://example.com/2"), seed.pages.map { it.url })
        assertEquals(2, seed.pagesExpected, "two rows, two expected pages — the corrupt line cost nothing")
    }

    @Test
    @DisplayName("a row appended twice (a replayed line) is counted once")
    fun duplicateAppendedRowsAreMerged(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        store.save(checkpoint(seeds = listOf(seed("https://example.com/a"))))
        store.appendRow("task-1", 0, row("https://example.com/1"))
        store.appendRow("task-1", 0, row("https://example.com/1"))

        val seed = requireNotNull(store.load("task-1")).seeds.single()

        assertEquals(1, seed.pages.size)
        assertEquals(1, seed.pagesExpected)
    }

    @Test
    @DisplayName("merging appended rows leaves a state that knows nothing of them untouched")
    fun mergingIsANoOpWithoutAppendedRows() {
        val state = checkpoint(seeds = listOf(seed("https://example.com/a", pages = listOf(row("https://example.com/1")))))

        assertSame(state, mergeAppendedRows(state, emptyList()))
        assertSame(
            state,
            mergeAppendedRows(state, listOf(CrawlCheckpointRow(seed = 9, row = CrawlPageResult("https://example.com/x")))),
            "a row for a seed this checkpoint does not have is ignored"
        )
    }

    @Test
    @DisplayName("deleting a task's checkpoint removes its row log too")
    fun deleteRemovesTheRowLog(@TempDir dir: Path) {
        val store = CrawlCheckpointStore(dir)
        store.save(checkpoint())
        store.appendRow("task-1", 0, row("https://example.com/1"))
        assertTrue(Files.exists(store.rowsFileFor("task-1")))

        store.delete("task-1")

        assertFalse(Files.exists(store.rowsFileFor("task-1")))
        assertNull(store.load("task-1"))
    }

    // -----------------------------------------------------------------
    // Write policy
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the first write is always due, and a small state is written as soon as enough changed")
    fun writePolicyWritesEarlyAndOftenForSmallStates() {
        val policy = CheckpointWritePolicy(everySettled = 5, staleAfterMillis = 2_000, maxBytesPerSecond = 64 * 1024)

        assertTrue(policy.due(0, now = 1_000), "nothing has been written yet")
        policy.record(bytes = 4_000, settled = 1, now = 1_000)

        assertFalse(policy.due(2, now = 1_100), "less than the minimum interval has passed")
        assertTrue(policy.due(6, now = 1_500), "five more URLs settled")
        assertTrue(policy.due(2, now = 3_100), "the state went stale")
        assertTrue(policy.due(2, now = 1_100, force = true), "a forced write ignores the policy")
    }

    @Test
    @DisplayName("a large checkpoint is written less often, so the write rate stays bounded")
    fun writePolicyBoundsTheWriteRateForLargeStates() {
        val policy = CheckpointWritePolicy(everySettled = 5, staleAfterMillis = 2_000, maxBytesPerSecond = 64 * 1024)

        // A 1 MB checkpoint: 1 MB / 64 KB per second = ~16 s between writes.
        policy.record(bytes = 1024 * 1024, settled = 100, now = 10_000)
        assertEquals(16_000L, policy.minimalIntervalMillis())

        assertFalse(
            policy.due(1_000, now = 12_000),
            "even a thousand settled URLs do not override the byte-rate limit"
        )
        assertTrue(policy.due(1_000, now = 26_500))
        assertTrue(policy.due(1_000, now = 12_000, force = true), "transitions are never rate-limited")
    }
}
