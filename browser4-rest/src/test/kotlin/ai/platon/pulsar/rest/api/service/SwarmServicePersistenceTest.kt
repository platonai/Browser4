package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SwarmServicePersistenceTest {

    private val objectMapper = pulsarObjectMapper()

    // -----------------------------------------------------------------
    // Persistence: write and restore
    // -----------------------------------------------------------------

    @Test
    fun `restoreFromDisk loads tasks from JSONL file`(@TempDir tempDir: Path) {
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

        val service = TestableSwarmService(tempDir)
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

        val service = TestableSwarmService(tempDir)
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
    fun `restoreFromDisk handles missing file gracefully`(@TempDir tempDir: Path) {
        val service = TestableSwarmService(tempDir)
        assertDoesNotThrow { invokeRestore(service, tempDir) }
        assertEquals(0, service.responseCache.estimatedSize())
    }

    @Test
    fun `restoreFromDisk skips corrupt lines`(@TempDir tempDir: Path) {
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

        val service = TestableSwarmService(tempDir)
        invokeRestore(service, tempDir)

        assertEquals(1, service.responseCache.estimatedSize())
        assertNotNull(service.responseCache.getIfPresent("good"))
    }

    @Test
    fun `restoreFromDisk empty file returns zero tasks`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("swarm-tasks.jsonl")
        Files.createDirectories(tempDir)
        Files.writeString(jsonlPath, "")

        val service = TestableSwarmService(tempDir)
        invokeRestore(service, tempDir)

        assertEquals(0, service.responseCache.estimatedSize())
    }

    // -----------------------------------------------------------------
    // Status index is restored
    // -----------------------------------------------------------------

    @Test
    fun `restoreFromDisk rebuilds status index`(@TempDir tempDir: Path) {
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

        val service = TestableSwarmService(tempDir)
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
        val service = TestableSwarmService(tempDir)

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

    @Test
    fun `closeSession aborts pending tasks and deletes the swarm session`(@TempDir tempDir: Path) {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        Mockito.`when`(sessionManager.deleteSession("SWARM")).thenReturn(true)
        val service = TestableSwarmService(tempDir, sessionManager)

        val queued = ScrapeResponse(id = "c1", statusCode = 201, pageStatusCode = 200)
        service.responseCache.put("c1", queued)

        val aborted = service.closeSession()

        assertEquals(1, aborted)
        assertTrue(queued.isDone)
        assertEquals(ResourceStatus.SC_GONE, queued.statusCode)
        Mockito.verify(sessionManager).deleteSession("SWARM")
    }

    // -----------------------------------------------------------------
    // Session replacement aborts pending tasks (issue #577)
    // -----------------------------------------------------------------

    @Test
    fun `session replacement aborts pending tasks of the old session`(@TempDir tempDir: Path) {
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        val session1 = Mockito.mock(AgenticSession::class.java)
        val session2 = Mockito.mock(AgenticSession::class.java)
        Mockito.`when`(session1.id).thenReturn(1L)
        Mockito.`when`(session2.id).thenReturn(2L)
        val managed1 = ManagedSession(sessionId = "SWARM", agenticSession = session1, capabilities = emptyMap())
        val managed2 = ManagedSession(sessionId = "SWARM", agenticSession = session2, capabilities = emptyMap())
        Mockito.`when`(sessionManager.ensureSwarmSession()).thenReturn(managed1, managed2)

        val service = TestableSwarmService(tempDir, sessionManager)
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
        val service = TestableSwarmService(tempDir)
        redirectPersistenceFile(service, tempDir)
        service.staleTaskTimeoutSeconds = 120
        val now = Instant.now()

        // Never picked up by a worker (createdTime only).
        val neverPicked = ScrapeResponse(id = "s1", statusCode = 201, pageStatusCode = 200)
            .apply { createdTime = now.minusSeconds(300); lastModifiedTime = null; startedTime = null }
        // Picked up by a worker but hung mid-fetch (startedTime set, no updates).
        val hungWorker = ScrapeResponse(id = "s2", statusCode = 201, pageStatusCode = 200)
            .apply { createdTime = now.minusSeconds(300); lastModifiedTime = now.minusSeconds(200); startedTime = now.minusSeconds(200) }
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

        assertTrue(neverPicked.isDone, "Never-picked tasks must be transitioned")
        assertEquals(ResourceStatus.SC_REQUEST_TIMEOUT, neverPicked.statusCode)
        assertTrue(hungWorker.isDone, "Hung-worker tasks must be transitioned")
        assertEquals(ResourceStatus.SC_REQUEST_TIMEOUT, hungWorker.statusCode)
        assertFalse(active.isDone, "Progressing tasks must not be transitioned")
        assertEquals(201, active.statusCode)
        assertTrue(done.isDone)
        assertEquals(200, done.statusCode, "Terminal tasks must not be touched")
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

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

    /** Minimal subclass to provide the mocked session manager. */
    private class TestableSwarmService(
        tempDir: Path,
        sessionManager: PulsarSessionManager = Mockito.mock(PulsarSessionManager::class.java),
    ) : SwarmService(sessionManager)
}
