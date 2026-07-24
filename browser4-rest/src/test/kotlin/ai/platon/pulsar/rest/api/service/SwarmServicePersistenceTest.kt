package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path

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
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null }
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(task1) + "\n" +
            objectMapper.writeValueAsString(task2) + "\n"
        )

        val service = TestableSwarmService(tempDir)
        invokeRestore(service, tempDir)

        assertEquals(2, service.responseCache.estimatedSize(), "should have 2 restored entries")
        val restored1 = service.responseCache.getIfPresent("t1")
        assertNotNull(restored1)
        assertEquals("t1", restored1!!.id)
        assertEquals(201, restored1.statusCode)
        val restored2 = service.responseCache.getIfPresent("t2")
        assertNotNull(restored2)
        assertEquals("t2", restored2!!.id)
        assertEquals(200, restored2.statusCode)
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
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null }
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
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null }
        val task3 = ScrapeResponse(id = "i3", statusCode = 200, pageStatusCode = 200)
            .apply { lastModifiedTime = null; startedTime = null; finishTime = null }
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(task1) + "\n" +
            objectMapper.writeValueAsString(task2) + "\n" +
            objectMapper.writeValueAsString(task3) + "\n"
        )

        val service = TestableSwarmService(tempDir)
        invokeRestore(service, tempDir)

        assertEquals(1, getStatusIndexCount(service, 201), "should have 1 task with status 201")
        assertEquals(2, getStatusIndexCount(service, 200), "should have 2 tasks with status 200")
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** Points the service at a temp directory then calls restoreFromDisk. */
    private fun invokeRestore(service: SwarmService, tempDir: Path) {
        // Redirect JsonlPersistence to temp dir
        val fileField = JsonlPersistence::class.java.getDeclaredField("file")
        fileField.isAccessible = true
        fileField.set(service.persistence, tempDir.resolve("swarm-tasks.jsonl"))

        // restoreFromDisk is @PostConstruct, call it via reflection
        val method = SwarmService::class.java.getDeclaredMethod("restoreFromDisk")
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
    private class TestableSwarmService(tempDir: Path) : SwarmService(
        Mockito.mock(PulsarSessionManager::class.java)
    )
}
