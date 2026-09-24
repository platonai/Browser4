package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.session.PulsarSessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class CrawlServicePersistenceTest {

    private val objectMapper = pulsarObjectMapper()

    // -----------------------------------------------------------------
    // Persistence restore
    // -----------------------------------------------------------------

    @Test
    fun `restoreFromDisk loads tasks from JSONL file`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("crawl-tasks.jsonl")
        val task1 = CrawlResponse(taskId = "c1", status = CrawlStatus.PROCESSING)
        val task2 = CrawlResponse(taskId = "c2", status = CrawlStatus.OK, pagesFound = 5)
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(task1) + "\n" +
            objectMapper.writeValueAsString(task2) + "\n"
        )

        val service = TestableCrawlService(tempDir)
        service.restoreFromDisk()

        // c1 was still running when the process died, so it comes back as
        // interrupted — never as the phantom running state it was persisted with
        // (issue #606).  c2 had finished and is restored exactly as it was.
        assertEquals("c1", service.getResult("c1").taskId)
        assertEquals(CrawlStatus.INTERRUPTED, service.getResult("c1").status)
        assertEquals("c2", service.getResult("c2").taskId)
        assertEquals(CrawlStatus.OK, service.getResult("c2").status)
        assertEquals(5, service.getResult("c2").pagesFound)
    }

    @Test
    fun `restoreFromDisk handles missing file gracefully`(@TempDir tempDir: Path) {
        val service = TestableCrawlService(tempDir)
        assertDoesNotThrow { service.restoreFromDisk() }
        assertEquals("Task not found: missing", service.getResult("missing").error)
    }

    @Test
    fun `restoreFromDisk skips corrupt lines`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("crawl-tasks.jsonl")
        val task = CrawlResponse(taskId = "good", status = "OK")
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            "{this is not json}\n" +
            "\n" +
            objectMapper.writeValueAsString(task) + "\n"
        )

        val service = TestableCrawlService(tempDir)
        service.restoreFromDisk()

        assertEquals("good", service.getResult("good").taskId)
        assertEquals(CrawlStatus.OK, service.getResult("good").status)
    }

    @Test
    fun `restoreFromDisk empty file returns zero tasks`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("crawl-tasks.jsonl")
        Files.createDirectories(tempDir)
        Files.writeString(jsonlPath, "")

        val service = TestableCrawlService(tempDir)
        service.restoreFromDisk()

        assertEquals("Task not found: any", service.getResult("any").error)
    }

    @Test
    fun `restoreFromDisk ignores blank taskId`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("crawl-tasks.jsonl")
        val blank = CrawlResponse(taskId = "", status = CrawlStatus.CREATED)
        val valid = CrawlResponse(taskId = "valid", status = CrawlStatus.OK)
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(blank) + "\n" +
            objectMapper.writeValueAsString(valid) + "\n"
        )

        val service = TestableCrawlService(tempDir)
        service.restoreFromDisk()

        // Blank taskId should be skipped
        assertNotNull(service.getResult("valid"))
        assertEquals("Task not found: ", service.getResult("").error)
    }

    // -----------------------------------------------------------------
    // Interrupted tasks (issue #606): a restored task that was running when
    // the process died must not report a phantom running state.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a task that was still running at shutdown is restored as interrupted, never as processing")
    fun runningTaskIsRestoredAsInterrupted(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("crawl-tasks.jsonl")
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(
                CrawlResponse(taskId = "interrupted-1", status = CrawlStatus.PROCESSING, pagesFound = 3)
            ) + "\n"
        )

        val service = TestableCrawlService(tempDir)
        service.restoreFromDisk()

        val restored = service.getResult("interrupted-1")
        assertEquals(CrawlStatus.INTERRUPTED, restored.status)
        assertTrue(CrawlStatus.isTerminal(restored.status), "a phantom running state would make every poll hang")
        assertFalse(CrawlStatus.isRunning(restored.status))
        assertNotNull(restored.finishTime, "the interruption is a terminal transition and says when it happened")
        assertFalse(restored.resumable, "nothing was checkpointed, so there is nothing to continue from")
        assertTrue(
            restored.error?.contains("cannot be resumed") == true,
            "the record must say what it is and that it has to be submitted again, got: ${restored.error}"
        )
        assertTrue(
            restored.diagnostic?.contains(CrawlService.REASON_NO_CHECKPOINT) == true,
            "the diagnostic names the missing checkpoint, got: ${restored.diagnostic}"
        )
    }

    @Test
    @DisplayName("an interrupted task reports the work its checkpoint holds: what is skipped and what is left")
    fun interruptedTaskReportsItsCheckpoint(@TempDir tempDir: Path) {
        val service = TestableCrawlService(tempDir)
        val taskId = "interrupted-2"
        service.checkpointStore.save(
            interruptedCheckpoint(
                taskId = taskId,
                seedUrls = listOf("https://example.com/portal"),
                seeds = listOf(
                    CrawlSeedCheckpoint(
                        url = "https://example.com/portal",
                        status = CrawlSeedCheckpoint.STATUS_INTERRUPTED,
                        pages = listOf(
                            CrawlPageResult(url = "https://example.com/1", depth = 1, contentLength = 100)
                        ),
                        outstanding = listOf(
                            CrawlFailedPage("https://example.com/2", 1, 0, CrawlLedger.REASON_ROUND_ENDED)
                        ),
                        pagesExpected = 2
                    )
                )
            )
        )
        Files.writeString(
            tempDir.resolve("crawl-tasks.jsonl"),
            objectMapper.writeValueAsString(CrawlResponse(taskId = taskId, status = CrawlStatus.CREATED)) + "\n"
        )

        service.restoreFromDisk()

        val restored = service.getResult(taskId)
        assertEquals(CrawlStatus.INTERRUPTED, restored.status)
        assertTrue(restored.resumable)
        assertEquals(1, restored.skippedAlreadyFetched, "the row the first run fetched is not fetched again")
        assertEquals(1, restored.remaining)
        assertEquals(1, restored.pagesFound)
        assertEquals(
            restored.pagesExpected, restored.pagesFound + (restored.failedPages?.size ?: 0),
            "the loss accounting must hold for an interrupted task too"
        )
        assertTrue(
            restored.diagnostic?.contains(CrawlService.REASON_INTERRUPTED) == true,
            "the record explains why the URL is still outstanding, got: ${restored.diagnostic}"
        )
    }

    @Test
    @DisplayName("interrupted tasks survive a terminal-task clear, and clear-all is what discards them")
    fun interruptedTasksAreExemptFromClearTerminal(@TempDir tempDir: Path) {
        val service = TestableCrawlService(tempDir)
        val taskId = "interrupted-3"
        service.checkpointStore.save(
            interruptedCheckpoint(
                taskId = taskId,
                seedUrls = listOf("https://example.com/a"),
                seeds = listOf(
                    CrawlSeedCheckpoint(
                        url = "https://example.com/a",
                        outstanding = listOf(CrawlFailedPage("https://example.com/b", 1, 0, "in flight"))
                    )
                )
            )
        )
        Files.writeString(
            tempDir.resolve("crawl-tasks.jsonl"),
            objectMapper.writeValueAsString(CrawlResponse(taskId = taskId, status = CrawlStatus.PROCESSING)) + "\n"
        )
        service.restoreFromDisk()

        // `crawl clear` removes *finished* tasks; an interrupted task is not one of
        // them — it is the one task a caller may still want to resume.
        service.clearTerminal()
        assertEquals(CrawlStatus.INTERRUPTED, service.getResult(taskId).status)
        assertNotNull(service.checkpointStore.load(taskId), "its checkpoint is still there")

        service.clearAll()
        assertEquals(CrawlStatus.NOT_FOUND, service.getResult(taskId).status)
        assertNull(service.checkpointStore.load(taskId), "clear-all is the explicit way to discard resumable state")
    }

    // -----------------------------------------------------------------
    // Resume (issue #606)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("resuming a task whose checkpoint is gone is refused, with the reason")
    fun resumeWithoutCheckpointIsRefused(@TempDir tempDir: Path) {
        val service = TestableCrawlService(tempDir)
        Files.writeString(
            tempDir.resolve("crawl-tasks.jsonl"),
            objectMapper.writeValueAsString(CrawlResponse(taskId = "gone", status = CrawlStatus.PROCESSING)) + "\n"
        )
        service.restoreFromDisk()

        val result = service.resume("gone")

        assertFalse(result.resumed)
        assertTrue(result.message.contains("no checkpoint"), result.message)
        assertEquals(CrawlStatus.INTERRUPTED, result.status, "the task is still reported as interrupted")
    }

    @Test
    @DisplayName("resuming a task with nothing left to fetch is a no-op, not a re-crawl")
    fun resumeWithNothingToDoIsANoOp(@TempDir tempDir: Path) {
        val service = TestableCrawlService(tempDir)
        val taskId = "done-but-interrupted"
        service.checkpointStore.save(
            interruptedCheckpoint(
                taskId = taskId,
                seedUrls = listOf("https://example.com/a"),
                seeds = listOf(
                    CrawlSeedCheckpoint(
                        url = "https://example.com/a",
                        completed = true,
                        status = "fetched",
                        pages = listOf(CrawlPageResult("https://example.com/1", depth = 1, contentLength = 10)),
                        pagesExpected = 1
                    )
                )
            )
        )
        Files.writeString(
            tempDir.resolve("crawl-tasks.jsonl"),
            objectMapper.writeValueAsString(CrawlResponse(taskId = taskId, status = CrawlStatus.PROCESSING)) + "\n"
        )
        service.restoreFromDisk()

        val result = service.resume(taskId)

        assertFalse(result.resumed)
        assertTrue(result.message.contains("nothing left"), result.message)
        assertEquals(1, result.skippedAlreadyFetched)
        assertEquals(0, result.remaining)
    }

    @Test
    @DisplayName("resuming a task that already has a live worker is refused as a conflict")
    fun resumeWithARunningWorkerIsRefused(@TempDir tempDir: Path) = runBlocking {
        val service = TestableCrawlService(tempDir)
        service.checkpointStore.save(
            interruptedCheckpoint(
                taskId = "busy",
                seedUrls = listOf("https://example.com/a"),
                seeds = listOf(
                    CrawlSeedCheckpoint(
                        url = "https://example.com/a",
                        outstanding = listOf(CrawlFailedPage("https://example.com/b", 1, 0, "in flight"))
                    )
                )
            )
        )
        // Two workers on one checkpoint would fetch everything twice, so a live job
        // is a conflict — the one resume outcome that is an error rather than an
        // answer.
        val jobStoreField = CrawlService::class.java.getDeclaredField("jobStore")
        jobStoreField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val jobStore = jobStoreField.get(service) as MutableMap<String, Job>
        val job = Job()
        jobStore["busy"] = job

        try {
            val failure = assertThrows(IllegalStateException::class.java) { service.resume("busy") }
            assertTrue(failure.message?.contains("still running") == true, failure.message)
        } finally {
            job.cancel()
        }
    }

    @Test
    @DisplayName("a task the server-side --timeout budget cut off can be resumed")
    fun aTimedOutTaskCanBeResumed(@TempDir tempDir: Path) = runBlocking {
        val service = TestableCrawlService(tempDir)
        val taskId = "cut-off-by-timeout"
        // A timed-out task is *terminal*, not interrupted: its record is in the task
        // store (and its worker wrote it itself).  Its checkpoint is what a resume
        // continues from.
        service.checkpointStore.save(
            interruptedCheckpoint(
                taskId = taskId,
                seedUrls = listOf("https://example.com/portal"),
                seeds = listOf(
                    CrawlSeedCheckpoint(
                        url = "https://example.com/portal",
                        status = CrawlSeedCheckpoint.STATUS_INTERRUPTED,
                        pages = listOf(
                            CrawlPageResult("https://example.com/fetched", depth = 1, contentLength = 128)
                        ),
                        outstanding = listOf(
                            CrawlFailedPage("https://example.com/left-behind", 1, 0, REASON_TASK_LIMIT)
                        ),
                        pagesExpected = 2
                    )
                ),
                taskTimeoutMillis = 60_000
            )
        )
        Files.writeString(
            tempDir.resolve("crawl-tasks.jsonl"),
            objectMapper.writeValueAsString(
                CrawlResponse(
                    taskId = taskId,
                    status = CrawlStatus.REQUEST_TIMEOUT,
                    pagesFound = 1,
                    pagesExpected = 2,
                    failedPages = listOf(
                        CrawlFailedPage("https://example.com/left-behind", 1, 0, REASON_TASK_LIMIT)
                    )
                )
            ) + "\n"
        )
        service.restoreFromDisk()

        val restored = service.getResult(taskId)
        assertEquals(CrawlStatus.REQUEST_TIMEOUT, restored.status, "a timed-out task keeps its own status")

        val started = service.resume(taskId)

        assertTrue(started.resumed, started.message)
        assertEquals(1, started.remaining, "only the URL the timeout left behind is re-submitted")
        assertEquals(1, started.skippedAlreadyFetched)

        val finished = awaitTerminal(service, taskId)
        assertEquals(
            finished.pagesExpected, finished.pagesFound + (finished.failedPages?.size ?: 0),
            "the merged record still obeys the loss accounting"
        )
        assertTrue(
            finished.pages?.any { it.url == "https://example.com/fetched" && it.restoredFromCheckpoint } == true,
            "the row fetched before the timeout is restored, not fetched again: ${finished.pages?.map { it.url }}"
        )
    }

    @Test
    @DisplayName("a restart keeps the resumable state of a task that was still running, and it can be continued")
    fun aRestartKeepsResumableState(@TempDir tempDir: Path) = runBlocking {
        val taskId = "survives-a-restart"
        val restoredRow = CrawlPageResult(
            url = "https://example.com/already-fetched",
            title = "already fetched",
            contentLength = 512,
            depth = 1,
            fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
            run = 1
        )
        // The process that died: it had written its checkpoint and its task record,
        // and nothing else survives (this instance is simply abandoned — no
        // shutdown, no final flush, exactly like a SIGKILL).
        val dying = TestableCrawlService(tempDir)
        dying.checkpointStore.save(
            interruptedCheckpoint(
                taskId = taskId,
                seedUrls = listOf("https://example.com/portal"),
                seeds = listOf(
                    CrawlSeedCheckpoint(
                        url = "https://example.com/portal",
                        completed = true,
                        status = "fetched",
                        pages = listOf(restoredRow),
                        outstanding = listOf(
                            CrawlFailedPage("https://example.com/in-flight", 1, 0, CrawlLedger.REASON_ROUND_ENDED)
                        ),
                        pagesExpected = 2
                    )
                )
            )
        )
        Files.writeString(
            tempDir.resolve("crawl-tasks.jsonl"),
            objectMapper.writeValueAsString(CrawlResponse(taskId = taskId, status = CrawlStatus.PROCESSING)) + "\n"
        )

        // The process that starts afterwards.
        val restarted = TestableCrawlService(tempDir)
        restarted.restoreFromDisk()

        val interrupted = restarted.getResult(taskId)
        assertEquals(CrawlStatus.INTERRUPTED, interrupted.status, "a dead worker must not report Processing")
        assertTrue(interrupted.resumable)
        assertEquals(1, interrupted.skippedAlreadyFetched)
        assertEquals(1, interrupted.remaining, "the URL that was in flight is still to fetch")
        assertEquals(
            interrupted.pagesExpected, interrupted.pagesFound + (interrupted.failedPages?.size ?: 0),
            "the accounting holds across the restart"
        )

        // Continuing it fetches only what was left; the URL that already had a row is
        // never requested again.
        assertTrue(restarted.resume(taskId).resumed)
        val finished = awaitTerminal(restarted, taskId)

        assertEquals(CrawlStatus.OK, finished.status, finished.error ?: "")
        assertEquals(1, finished.skippedAlreadyFetched)
        assertEquals(2, finished.pagesFound, "the restored row and the row this run produced")
        assertTrue(
            finished.pages?.any { it.restoredFromCheckpoint } == true,
            "the restored row is marked as such: ${finished.pages?.map { it.url to it.restoredFromCheckpoint }}"
        )
        assertEquals(finished.pagesExpected, finished.pagesFound + (finished.failedPages?.size ?: 0))
    }

    @Test
    @DisplayName("a resume keeps the task id, skips what was fetched, and merges both runs into one record")
    fun resumeContinuesTheTaskAndMergesTheRuns(@TempDir tempDir: Path) = runBlocking {
        val service = TestableCrawlService(tempDir)
        val taskId = "resume-me"
        val restoredRow = CrawlPageResult(
            url = "https://example.com/already-fetched",
            title = "already fetched",
            contentLength = 512,
            depth = 1,
            fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
            run = 1
        )
        service.checkpointStore.save(
            interruptedCheckpoint(
                taskId = taskId,
                seedUrls = listOf("https://example.com/portal", "https://example.com/other"),
                taskTimeoutMillis = 60_000,
                seeds = listOf(
                    CrawlSeedCheckpoint(
                        url = "https://example.com/portal",
                        completed = true,
                        status = "fetched",
                        pages = listOf(restoredRow),
                        pagesExpected = 1
                    ),
                    CrawlSeedCheckpoint(url = "https://example.com/other")
                )
            )
        )
        Files.writeString(
            tempDir.resolve("crawl-tasks.jsonl"),
            objectMapper.writeValueAsString(
                CrawlResponse(taskId = taskId, status = CrawlStatus.PROCESSING, pagesFound = 1)
            ) + "\n"
        )
        service.restoreFromDisk()

        val started = service.resume(taskId)
        assertTrue(started.resumed, started.message)
        assertEquals(taskId, started.taskId, "the same task continues; the id is not a new one")
        assertEquals(1, started.skippedAlreadyFetched)
        assertEquals(1, started.remaining, "the seed that was never picked up is fetched from scratch")
        assertEquals(1, started.resumeCount)

        val finished = awaitTerminal(service, taskId)

        assertEquals(CrawlStatus.OK, finished.status, "no round timed out, so the resumed crawl completed")
        assertEquals(1, finished.resumeCount)
        assertNotNull(finished.resumedFrom, "the result says which interruption it continued from")
        assertEquals(1, finished.skippedAlreadyFetched)
        assertEquals(
            finished.pagesExpected, finished.pagesFound + (finished.failedPages?.size ?: 0),
            "the merged record still obeys the loss accounting"
        )
        val pages = requireNotNull(finished.pages)
        assertEquals(2, pages.size, "the restored row and the row the resumed run produced")
        val restored = pages.single { it.url == "https://example.com/already-fetched" }
        assertTrue(restored.restoredFromCheckpoint, "the row that was not re-fetched is marked as restored")
        assertEquals(1, restored.run)
        val fetchedNow = pages.single { it.url != "https://example.com/already-fetched" }
        assertEquals(2, fetchedNow.run, "the new row carries the run that produced it")
        assertEquals(0, finished.remaining)
        assertFalse(finished.resumable, "a finished crawl leaves no checkpoint behind")
        assertNull(service.checkpointStore.load(taskId))
    }

    /** Poll a task until it reaches a terminal state, or fail with what it was doing. */
    private suspend fun awaitTerminal(service: CrawlService, taskId: String, timeoutMs: Long = 30_000): CrawlResponse {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = service.getResult(taskId)
        while (CrawlStatus.isRunning(result.status)) {
            if (System.currentTimeMillis() > deadline) {
                fail<Unit>("task $taskId never reached a terminal state (still ${result.status})")
            }
            delay(25)
            result = service.getResult(taskId)
        }
        return result
    }

    // -----------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------

    /**
     * A checkpoint of an interrupted task, as one would have been left on disk: the
     * input contract plus the work state a caller wants to exercise.
     */
    private fun interruptedCheckpoint(
        taskId: String,
        seedUrls: List<String>,
        seeds: List<CrawlSeedCheckpoint>,
        depth: Int = 1,
        taskTimeoutMillis: Long = 0,
    ): CrawlCheckpoint = CrawlCheckpoint.ofRequest(
        taskId = taskId,
        request = CrawlRequest(url = seedUrls.first(), depth = depth),
        seedUrls = seedUrls,
        parallelTabs = 2,
        taskTimeoutMillis = taskTimeoutMillis
    ).copy(seeds = seeds)

    private class TestableCrawlService(tempDir: Path) : CrawlService(
        Mockito.mock(PulsarSessionManager::class.java)
    ) {
        init {
            // Point persistence to temp dir.
            val fileField = persistence.javaClass.getDeclaredField("file")
            fileField.isAccessible = true
            fileField.set(persistence, tempDir.resolve("crawl-tasks.jsonl"))
            // And the resumable checkpoints, which have a store of their own.
            checkpointStore = CrawlCheckpointStore(tempDir.resolve("checkpoints"))
        }
    }
}
