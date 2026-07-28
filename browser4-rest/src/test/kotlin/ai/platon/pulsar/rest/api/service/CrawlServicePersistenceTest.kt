package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path

class CrawlServicePersistenceTest {

    private val objectMapper = pulsarObjectMapper()

    // -----------------------------------------------------------------
    // Persistence restore
    // -----------------------------------------------------------------

    @Test
    fun `restoreFromDisk loads tasks from JSONL file`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("crawl-tasks.jsonl")
        val task1 = CrawlResponse(taskId = "c1", status = "CREATED")
        val task2 = CrawlResponse(taskId = "c2", status = "OK", pagesFound = 5)
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(task1) + "\n" +
            objectMapper.writeValueAsString(task2) + "\n"
        )

        val service = TestableCrawlService(tempDir)
        service.restoreFromDisk()

        assertEquals("c1", service.getResult("c1").taskId)
        assertEquals("CREATED", service.getResult("c1").status)
        assertEquals("c2", service.getResult("c2").taskId)
        assertEquals("OK", service.getResult("c2").status)
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
        assertEquals("OK", service.getResult("good").status)
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
        val blank = CrawlResponse(taskId = "", status = "CREATED")
        val valid = CrawlResponse(taskId = "valid", status = "OK")
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
    // Helper
    // -----------------------------------------------------------------

    private class TestableCrawlService(tempDir: Path) : CrawlService(
        Mockito.mock(PulsarSessionManager::class.java)
    ) {
        init {
            // Point persistence to temp dir.
            val fileField = persistence.javaClass.getDeclaredField("file")
            fileField.isAccessible = true
            fileField.set(persistence, tempDir.resolve("crawl-tasks.jsonl"))
        }
    }
}
