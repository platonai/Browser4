package ai.platon.pulsar.swarm.service

import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmSessionProvider
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Persistence tests for [SwarmService], moved from browser4-rest together
 * with the implementation. Session access is never exercised — the provider
 * throws if the code under test accidentally touches it.
 */
class SwarmServicePersistenceTest {

    private val objectMapper = pulsarObjectMapper()

    // -----------------------------------------------------------------
    // Persistence: write and restore
    // -----------------------------------------------------------------

    @Test
    fun restoreFromDiskLoadsTasksFromJsonlFile(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("swarm-tasks.jsonl")
        val task1 = ScrapeResponse(id = "t1", statusCode = 201, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null }
        val task2 = ScrapeResponse(id = "t2", statusCode = 200, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null; isDone = true }
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(task1) + "\n" +
            objectMapper.writeValueAsString(task2) + "\n"
        )

        val service = newService()
        invokeRestore(service, tempDir)

        assertEquals(2, service.responseCache.estimatedSize(), "should have 2 restored entries")
        val restored2 = service.responseCache.getIfPresent("t2")
        assertNotNull(restored2)
        assertEquals("t2", restored2!!.id)
        assertEquals(200, restored2.statusCode)
        assertTrue(restored2.isDone)
    }

    @Test
    fun `restoreFromDisk marks non-terminal tasks as failed`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("swarm-tasks.jsonl")
        val queuedTask = ScrapeResponse(id = "queued-1", statusCode = 201, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null }
        Files.createDirectories(tempDir)
        Files.writeString(jsonlPath, objectMapper.writeValueAsString(queuedTask) + "\n")

        val service = newService()
        invokeRestore(service, tempDir)

        val restored = service.responseCache.getIfPresent("queued-1")
        assertNotNull(restored, "The task should be restored")
        // A queued task can never resume after a restart (its worker state is
        // gone) — it must not be revived as "queued" forever.
        assertTrue(restored!!.isDone, "Non-terminal restored tasks must become terminal")
        assertEquals(ResourceStatus.SC_GONE, restored.statusCode)
        assertNotNull(restored.message)
        assertTrue(restored.message!!.contains("restart"), "Message: ${restored.message}")
    }

    @Test
    fun restoreFromDiskHandlesMissingFileGracefully(@TempDir tempDir: Path) {
        val service = newService()
        assertDoesNotThrow { invokeRestore(service, tempDir) }
        assertEquals(0, service.responseCache.estimatedSize())
    }

    @Test
    fun restoreFromDiskSkipsCorruptLines(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("swarm-tasks.jsonl")
        val task = ScrapeResponse(id = "good", statusCode = 200, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null; isDone = true }
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            "{this is not json}\n" +
            "\n" +  // blank line
            objectMapper.writeValueAsString(task) + "\n"
        )

        val service = newService()
        invokeRestore(service, tempDir)

        assertEquals(1, service.responseCache.estimatedSize())
        assertNotNull(service.responseCache.getIfPresent("good"))
    }

    @Test
    fun restoreFromDiskEmptyFileReturnsZeroTasks(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("swarm-tasks.jsonl")
        Files.createDirectories(tempDir)
        Files.writeString(jsonlPath, "")

        val service = newService()
        invokeRestore(service, tempDir)

        assertEquals(0, service.responseCache.estimatedSize())
    }

    // -----------------------------------------------------------------
    // Status index is restored
    // -----------------------------------------------------------------

    @Test
    fun restoreFromDiskRebuildsStatusIndex(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("swarm-tasks.jsonl")
        val task1 = ScrapeResponse(id = "i1", statusCode = 201, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null }
        val task2 = ScrapeResponse(id = "i2", statusCode = 200, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null; isDone = true }
        val task3 = ScrapeResponse(id = "i3", statusCode = 200, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null; isDone = true }
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(task1) + "\n" +
            objectMapper.writeValueAsString(task2) + "\n" +
            objectMapper.writeValueAsString(task3) + "\n"
        )

        val service = newService()
        invokeRestore(service, tempDir)

        // The queued task (201) becomes failed (GONE) during restore.
        assertEquals(1, getStatusIndexCount(service, ResourceStatus.SC_GONE), "should have 1 task with status GONE")
        assertEquals(2, getStatusIndexCount(service, 200), "should have 2 tasks with status 200")
    }

    // -----------------------------------------------------------------
    // Pending task abort (swarm close cleanup)
    // -----------------------------------------------------------------

    @Test
    fun `abortPendingTasks aborts only non-terminal tasks`(@TempDir tempDir: Path) {
        val service = newService()

        val queued = ScrapeResponse(id = "p1", statusCode = 201, pageStatusCode = 200)
        service.responseCache.put("p1", queued)
        val processing = ScrapeResponse(id = "p2", statusCode = 202, pageStatusCode = 200)
        service.responseCache.put("p2", processing)
        val done = ScrapeResponse(id = "p3", statusCode = 200, pageStatusCode = 200)
            .apply { isDone = true }
        service.responseCache.put("p3", done)

        val aborted = service.abortPendingTasks("Swarm session was closed; task dropped")

        assertEquals(2, aborted, "Only the two non-terminal tasks should be aborted")
        assertTrue(queued.isDone)
        assertEquals(ResourceStatus.SC_GONE, queued.statusCode)
        assertNotNull(queued.message)
        assertTrue(processing.isDone)
        assertEquals(ResourceStatus.SC_GONE, processing.statusCode)
        assertTrue(done.isDone)
        assertEquals(200, done.statusCode, "Terminal tasks must not be touched")
    }

    // -----------------------------------------------------------------
    // Session replacement aborts pending tasks (issue #577)
    // -----------------------------------------------------------------

    @Test
    fun `session replacement aborts pending tasks of the old session`(@TempDir tempDir: Path) {
        val session1 = Mockito.mock(GenericAgenticSession::class.java)
        val session2 = Mockito.mock(GenericAgenticSession::class.java)
        Mockito.`when`(session1.id).thenReturn(1L)
        Mockito.`when`(session2.id).thenReturn(2L)
        val sessions = listOf(session1, session2)
        var calls = 0
        val service = SwarmService(
            SwarmSessionProvider {
                sessions[calls++]
            }
        )
        redirectPersistenceFile(service, tempDir)

        assertSame(session1, service.session, "First access resolves the current swarm session")

        // A task submitted under session 1 ...
        val queued = ScrapeResponse(id = "r1", statusCode = 201, pageStatusCode = 200)
        service.responseCache.put("r1", queued)

        // ... is aborted when the session is replaced (closed and recreated).
        assertSame(session2, service.session, "Second access resolves the replacement session")
        assertTrue(queued.isDone, "Pending tasks of the old session must become terminal")
        assertEquals(ResourceStatus.SC_GONE, queued.statusCode)
        assertNotNull(queued.message)
    }

    // -----------------------------------------------------------------
    // Stale task transition (worker hung / never picked up)
    // -----------------------------------------------------------------

    @Test
    fun `transitionStaleTasks times out tasks with no progress`(@TempDir tempDir: Path) {
        val service = newService()
        redirectPersistenceFile(service, tempDir)
        service.staleTaskTimeoutSeconds = 120
        service.queueStallTimeoutSeconds = 600
        val now = Instant.now()

        // Never picked up by a worker (createdTime only) while a sibling task is
        // still completing — the batch is draining, so this task must survive.
        val neverPicked = ScrapeResponse(id = "s1", statusCode = 201, pageStatusCode = 200)
            .apply { createdTime = now.minusSeconds(1800); lastModifiedTime = null; startedTime = null }
        // Picked up by a worker but hung mid-fetch (startedTime set, no updates).
        val hungWorker = ScrapeResponse(id = "s2", statusCode = 201, pageStatusCode = 200)
            .apply { createdTime = now.minusSeconds(1800); lastModifiedTime = now.minusSeconds(200); startedTime = now.minusSeconds(200) }
        // Actively progressing (recent updates) — must NOT be transitioned.
        val active = ScrapeResponse(id = "s3", statusCode = 201, pageStatusCode = 200)
            .apply { createdTime = now.minusSeconds(300); lastModifiedTime = now; startedTime = now.minusSeconds(200) }
        // Terminal tasks are not touched.
        val done = ScrapeResponse(id = "s4", statusCode = 200, pageStatusCode = 200)
            .apply { isDone = true; lastModifiedTime = null; createdTime = now.minusSeconds(400) }

        service.responseCache.put("s1", neverPicked)
        service.responseCache.put("s2", hungWorker)
        service.responseCache.put("s3", active)
        service.responseCache.put("s4", done)

        invokeTransitionStaleTasks(service)

        assertTrue(hungWorker.isDone, "Hung-worker tasks must be transitioned")
        assertEquals(ResourceStatus.SC_REQUEST_TIMEOUT, hungWorker.statusCode)
        assertFalse(neverPicked.isDone, "Queued tasks must survive while siblings still progress")
        assertEquals(201, neverPicked.statusCode)
        assertFalse(active.isDone, "Progressing tasks must not be transitioned")
        assertEquals(201, active.statusCode)
        assertTrue(done.isDone)
        assertEquals(200, done.statusCode, "Terminal tasks must not be touched")
    }

    @Test
    fun `transitionStaleTasks keeps a large queued batch alive while the pool drains it`(@TempDir tempDir: Path) {
        // Regression: a one-shot 100-URL submit used to have 98 of its queued
        // tasks killed at the 120s mark while the pipeline was still fetching
        // earlier pages of the very same batch.
        val service = TestableSwarmService(tempDir)
        redirectPersistenceFile(service, tempDir)
        service.staleTaskTimeoutSeconds = 120
        service.queueStallTimeoutSeconds = 600
        val now = Instant.now()

        val queued = (0 until 98).map { i ->
            ScrapeResponse(id = "q$i", statusCode = 201, pageStatusCode = 200)
                .apply { createdTime = now.minusSeconds(1200); lastModifiedTime = null; startedTime = null }
        }
        queued.forEach { service.responseCache.put(it.id!!, it) }
        // One task completed a moment ago: the pipeline is demonstrably alive.
        val justFinished = ScrapeResponse(id = "done1", statusCode = 200, pageStatusCode = 200)
            .apply { isDone = true; createdTime = now.minusSeconds(1200); lastModifiedTime = now }
        service.responseCache.put("done1", justFinished)

        invokeTransitionStaleTasks(service)

        assertTrue(queued.none { it.isDone }, "No queued task may be failed while the pool is progressing")
        assertTrue(queued.all { it.statusCode == 201 })
    }

    @Test
    fun `transitionStaleTasks reaps queued tasks once the whole pipeline stalls`(@TempDir tempDir: Path) {
        val service = TestableSwarmService(tempDir)
        redirectPersistenceFile(service, tempDir)
        service.staleTaskTimeoutSeconds = 120
        service.queueStallTimeoutSeconds = 600
        val now = Instant.now()

        // Nothing has moved in this swarm for 20 minutes: the pool cannot
        // consume the queue, so the task can never run.
        val queued = ScrapeResponse(id = "stuck1", statusCode = 201, pageStatusCode = 200)
            .apply { createdTime = now.minusSeconds(1200); lastModifiedTime = null; startedTime = null }
        service.responseCache.put("stuck1", queued)

        invokeTransitionStaleTasks(service)

        assertTrue(queued.isDone, "A queued task belonging to a stalled swarm must be reaped")
        assertEquals(ResourceStatus.SC_REQUEST_TIMEOUT, queued.statusCode)
        assertTrue(
            queued.message?.contains("never picked up") == true,
            "The failure message must explain that the task was never picked up: ${queued.message}"
        )
    }

    // -----------------------------------------------------------------
    // Batch aggregate status
    // -----------------------------------------------------------------

    @Test
    fun `batchStatus aggregates tasks by batch id`(@TempDir tempDir: Path) {
        val service = TestableSwarmService(tempDir)
        val now = Instant.now()
        val start = now.minusSeconds(120)

        val ok = ScrapeResponse(id = "b1-ok", statusCode = 200, pageStatusCode = 200)
            .apply {
                batchId = "batch-A"; isDone = true; createdTime = start
                startedTime = start; finishTime = start.plusSeconds(30)
            }
        val slow = ScrapeResponse(id = "b1-slow", statusCode = 200, pageStatusCode = 200)
            .apply {
                batchId = "batch-A"; isDone = true; createdTime = start
                startedTime = start.plusSeconds(10); finishTime = now
            }
        val failed = ScrapeResponse(id = "b1-fail", statusCode = 408, pageStatusCode = 408)
            .apply {
                batchId = "batch-A"; isDone = true; createdTime = start
                startedTime = start.plusSeconds(5); finishTime = start.plusSeconds(15)
                message = "Task timed out"
            }
        val queued = ScrapeResponse(id = "b1-queued", statusCode = 201, pageStatusCode = 200)
            .apply { batchId = "batch-A"; createdTime = now }
        // A task of another batch must never leak into this one's aggregate.
        val other = ScrapeResponse(id = "b2-ok", statusCode = 200, pageStatusCode = 200)
            .apply { batchId = "batch-B"; isDone = true }

        listOf(ok, slow, failed, queued, other).forEach { service.responseCache.put(it.id!!, it) }

        @Suppress("UNCHECKED_CAST")
        val tasks = service.batchStatus("batch-A")["tasks"] as List<Map<String, Any?>>

        val status = service.batchStatus("batch-A")
        assertEquals("batch-A", status["batchId"])
        assertEquals(4, status["total"])
        assertEquals(2, status["completed"], "only status 200 counts as completed")
        assertEquals(1, status["failed"])
        assertEquals(1, status["pending"], "a task without isDone is still pending")
        assertEquals(4, tasks.size)

        // The batch window is only final once every task settled.
        assertEquals(null, status["finishedAt"])
        assertEquals(null, status["durationMillis"])

        val okRow = tasks.first { it["id"] == "b1-ok" }
        assertEquals(30_000L, okRow["durationMillis"], "duration is started -> finished")
        val failedRow = tasks.first { it["id"] == "b1-fail" }
        assertEquals(10_000L, failedRow["durationMillis"])
        val queuedRow = tasks.first { it["id"] == "b1-queued" }
        assertEquals(null, queuedRow["durationMillis"], "a running task has no duration yet")
    }

    @Test
    fun `batchStatus reports the finished window once the batch settles`(@TempDir tempDir: Path) {
        val service = TestableSwarmService(tempDir)
        val start = Instant.now().minusSeconds(60)

        val a = ScrapeResponse(id = "c1", statusCode = 200, pageStatusCode = 200)
            .apply {
                batchId = "batch-C"; isDone = true; createdTime = start
                startedTime = start; finishTime = start.plusSeconds(20)
            }
        val b = ScrapeResponse(id = "c2", statusCode = 200, pageStatusCode = 200)
            .apply {
                batchId = "batch-C"; isDone = true; createdTime = start.plusSeconds(40)
                startedTime = start.plusSeconds(40); finishTime = start.plusSeconds(55)
            }
        listOf(a, b).forEach { service.responseCache.put(it.id!!, it) }

        val status = service.batchStatus("batch-C")

        assertEquals(2, status["total"])
        assertEquals(0, status["pending"])
        assertNotNull(status["finishedAt"])
        // Window: earliest start (t0) -> latest finish (t0 + 55s)
        assertEquals(55_000L, status["durationMillis"])
    }

    @Test
    fun `batchStatus of an unknown batch is empty`(@TempDir tempDir: Path) {
        val service = TestableSwarmService(tempDir)
        service.responseCache.put(
            "x1",
            ScrapeResponse(id = "x1", statusCode = 200, pageStatusCode = 200).apply { isDone = true }
        )

        val status = service.batchStatus("nope")

        assertEquals(0, status["total"])
        assertEquals(0, status["completed"])
        assertEquals(0, status["failed"])
        assertEquals(0, status["pending"])
    }

    // -----------------------------------------------------------------
    // Batch id persists with the task
    // -----------------------------------------------------------------

    @Test
    fun `batchId survives a serialization round trip`(@TempDir tempDir: Path) {
        // durationMillis is a derived property; make sure writing a response
        // through the object mapper and restoring it does not break.
        val jsonlPath = tempDir.resolve("swarm-tasks.jsonl")
        val task = ScrapeResponse(id = "r1", statusCode = 200, pageStatusCode = 200)
            .apply {
                batchId = "batch-R"
                isDone = true
                createdTime = Instant.now().minusSeconds(30)
                startedTime = Instant.now().minusSeconds(30)
                finishTime = Instant.now()
            }
        Files.createDirectories(tempDir)
        Files.writeString(jsonlPath, objectMapper.writeValueAsString(task) + "\n")

        val service = TestableSwarmService(tempDir)
        invokeRestore(service, tempDir)

        val restored = service.responseCache.getIfPresent("r1")
        assertNotNull(restored)
        assertEquals("batch-R", restored!!.batchId)
        assertTrue(restored.durationMillis != null && restored.durationMillis!! >= 29_000)
    }

    // -----------------------------------------------------------------
    // Wire format of the task status payload
    // -----------------------------------------------------------------

    @Test
    fun `task status serializes isDone under its documented name`(@TempDir tempDir: Path) {
        // Every client (CLI, MCP tools, tests) reads `isDone`, and the docs name
        // it that way; the Java Bean convention would strip the `is` and emit
        // `done`, which silently turns the flag into a no-op for those clients.
        val response = ScrapeResponse(id = "w1", statusCode = 200, pageStatusCode = 200)
            .apply { isDone = true }

        val json = objectMapper.writeValueAsString(response)

        assertTrue(json.contains("\"isDone\""), "expected an isDone field, got: $json")
        val restored = objectMapper.readValue(json, ScrapeResponse::class.java)
        assertTrue(restored.isDone, "isDone must survive a round trip: $json")
    }

    @Test
    fun `task status emits isDone false explicitly`(@TempDir tempDir: Path) {
        val response = ScrapeResponse(id = "w2", statusCode = 201, pageStatusCode = 201)

        val json = objectMapper.writeValueAsString(response)

        assertTrue(
            json.contains("\"isDone\":false"),
            "a queued task must still carry isDone=false (JsonInclude.ALWAYS): $json"
        )
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** A service whose session provider must never be called in these tests. */
    private fun newService(): SwarmService {
        return SwarmService(
            SwarmSessionProvider {
                throw UnsupportedOperationException("Session access is not expected in persistence tests")
            }
        )
    }

    /** Points the service at a temp directory then calls restoreFromDisk. */
    private fun invokeRestore(service: SwarmService, tempDir: Path) {
        redirectPersistenceFile(service, tempDir)

        // restoreFromDisk is @PostConstruct, call it via reflection
        val method = SwarmService::class.java.getDeclaredMethod("restoreFromDisk")
        method.isAccessible = true
        method.invoke(service)
    }

    /** Redirects the service's JsonlPersistence to a temp directory. */
    private fun redirectPersistenceFile(service: SwarmService, tempDir: Path) {
        // Redirect JsonlPersistence to temp dir
        val fileField = JsonlPersistence::class.java.getDeclaredField("file")
        fileField.isAccessible = true
        fileField.set(service.persistence, tempDir.resolve("swarm-tasks.jsonl"))
    }

    /** Invokes the private transitionStaleTasks via reflection. */
    private fun invokeTransitionStaleTasks(service: SwarmService) {
        val method = SwarmService::class.java.getDeclaredMethod("transitionStaleTasks")
        method.isAccessible = true
        method.invoke(service)
    }

    /** Reads the status index count via reflection. */
    @Suppress("UNCHECKED_CAST")
    private fun getStatusIndexCount(service: SwarmService, statusCode: Int): Int {
        val indexField = SwarmService::class.java.getDeclaredField("responseStatusIndex")
        indexField.isAccessible = true
        val index = indexField.get(service)
        // MultiValuedMap from Apache Commons Collections 4
        val mm = index as org.apache.commons.collections4.MultiValuedMap<Int, String>
        return mm[statusCode]?.size ?: 0
    }
}
