package ai.platon.pulsar.agentic.tools.experience

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ten concurrent `saveTrace` calls must leave ten traces on disk.
 *
 * `KnowledgeStoreConcurrencyTest.testConcurrentTraceSaves` asserts exactly that and
 * observes two, which is either real trace loss (the learning substrate quietly
 * dropping most of what it is told) or a stale expectation somewhere. This test
 * prints what actually landed so the difference is visible instead of guessed.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@OptIn(ExperimentalPathApi::class)
@DisplayName("KnowledgeStore — concurrent trace saves")
class KnowledgeStoreTraceConcurrencyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: KnowledgeStore

    @BeforeEach
    fun setUp() {
        store = KnowledgeStore(tempDir)
        store.initializeStore()
    }

    @AfterEach
    fun tearDown() {
        try { tempDir.deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    @DisplayName("ten saved traces are ten files, and every one loads back")
    fun concurrentTraceSavesKeepEveryTrace() = runBlocking {
        val count = 10
        val saved = mutableListOf<Path>()
        val jobs = List(count) { i ->
            launch(Dispatchers.Default) {
                val trace = TraceRecord(
                    intent = "search",
                    domain = "amazon.com",
                    url = "https://amazon.com/s?k=test$i",
                    urlPattern = "/s?k=*",
                    outcome = "success",
                )
                val path = store.saveTrace(trace)
                synchronized(saved) { saved.add(path) }
            }
        }
        jobs.joinAll()

        val dir = tempDir.resolve("traces").resolve("amazon.com")
        val onDisk = dir.listDirectoryEntries("*.yaml").map { it.fileName.toString() }.sorted()
        val distinctSaved = saved.map { it.fileName.toString() }.distinct().sorted()

        assertEquals(
            count, distinctSaved.size,
            "the writers produced ${distinctSaved.size} distinct file names for $count traces:\n" +
                distinctSaved.joinToString("\n"),
        )
        assertEquals(
            count, onDisk.size,
            "expected $count trace files on disk, found ${onDisk.size}:\n" + onDisk.joinToString("\n"),
        )
        assertTrue(
            onDisk.all { store.loadTrace(dir.resolve(it)) != null },
            "every trace on disk must load back: " + onDisk.joinToString(", "),
        )
    }
}
