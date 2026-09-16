package ai.platon.pulsar.agentic.tools.experience

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.assertTrue

/**
 * A reader must never observe a knowledge file that is missing or half-written.
 *
 * This is the failure mode that had [KnowledgeStoreConcurrencyTest] disabled: the
 * atomic write used a **fixed** `${file}.tmp` name (two writers truncated each
 * other's bytes, and the loser's `move` failed) and it **deleted the target before
 * moving** (leaving readers with a `NoSuchFileException` for the duration). Both
 * surfaced in real runs as `Failed to load facts/stats …` warnings.
 *
 * The store swallows read failures into a `null` result, so the assertion is on the
 * *value*: once a file has been written it never becomes unreadable again.
 *
 * [Timeout] is deliberate: this test hung a CI job for 35 minutes once, and a
 * concurrency test that can stall the pipeline is worse than one that fails.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@OptIn(ExperimentalPathApi::class)
@DisplayName("KnowledgeStore — atomic write")
class KnowledgeStoreAtomicWriteTest {

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
    @DisplayName("concurrent writers and readers never expose a partial file")
    fun concurrentReadersNeverSeePartialFiles() = runBlocking {
        val domain = "example.com"
        val intent = "extract"

        fun facts(tag: String) = KnowledgeFacts(
            intent = intent,
            domain = domain,
            urlPattern = "/p/*",
            status = VerificationStatus.HYPOTHESIS,
            siteFacts = SiteFacts(domain = domain),
            pageFacts = PageFacts(pageType = tag),
        )

        // The first write is not concurrent: it establishes the files the readers
        // then expect to keep observing.
        store.saveFacts(facts("seed"))

        val failures = ConcurrentLinkedQueue<String>()
        val reads = AtomicInteger()
        val writing = AtomicBoolean(true)

        // Readers are **bounded and cooperative** on purpose. An earlier version spun in
        // `while (writing.get())` on `Dispatchers.Default` with blocking file reads and
        // no suspension point: on a small CI runner that saturated the Default pool, the
        // writer coroutines never got a thread, `writers.joinAll()` never returned, and
        // the whole job died on its 35-minute timeout (the log simply stopped after
        // "[INFO] Running KnowledgeStore — atomic write"). A test must not be able to
        // hang the pipeline, so the readers iterate a fixed number of times, run on the
        // IO pool (blocking I/O belongs there), and yield so writers always progress.
        val readers = List(4) {
            launch(Dispatchers.IO) {
                for (round in 0 until READER_ROUNDS) {
                    if (!writing.get()) break
                    reads.incrementAndGet()
                    if (store.loadFacts(domain, intent) == null) {
                        failures += "a reader could not parse the facts file"
                    }
                    // `loadStats` answers a default when the file is absent, so a
                    // failure here means the file disappeared under the reader.
                    runCatching { store.loadStats(domain, intent) }
                        .onFailure { failures += "stats read failed: ${it.message}" }
                    yield()
                }
            }
        }

        val writers = List(6) { i ->
            launch(Dispatchers.IO) {
                repeat(15) { round ->
                    runCatching { store.saveFacts(facts("w$i-r$round")) }
                        .onFailure { failures += "writer $i failed: ${it::class.simpleName}: ${it.message}" }
                }
            }
        }

        writers.joinAll()
        writing.set(false)
        readers.joinAll()

        assertTrue(reads.get() > 0, "the readers must have observed the files")
        assertTrue(
            failures.isEmpty(),
            "a concurrent write exposed a partial file:\n${failures.joinToString("\n")}",
        )
        assertTrue(store.loadFacts(domain, intent) != null, "the facts file must survive the hammering")
    }

    private companion object {
        /**
         * Reader iterations. Fixed, not "until the writers stop": see the note in the
         * test — an unbounded reader loop is what hung the CI job.
         */
        const val READER_ROUNDS = 300
    }
}
