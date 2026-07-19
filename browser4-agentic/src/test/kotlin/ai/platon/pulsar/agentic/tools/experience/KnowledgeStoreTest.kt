package ai.platon.pulsar.agentic.tools.experience

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import kotlin.test.*

@OptIn(ExperimentalPathApi::class)
@DisplayName("KnowledgeStore v2")
class KnowledgeStoreTest {

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
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    @Nested
    @DisplayName("directory layout")
    inner class DirectoryLayout {
        @Test
        @DisplayName("creates traces, experience, and facts directories")
        fun testCreatesAllDirs() {
            assertTrue(tempDir.resolve("traces").exists())
            assertTrue(tempDir.resolve("experience").exists())
            assertTrue(tempDir.resolve("facts").exists())
            assertTrue(tempDir.resolve(".archive").exists())
        }

        @Test
        @DisplayName("idempotent initialize")
        fun testIdempotent() {
            store.initializeStore()
            store.initializeStore()
            assertTrue(tempDir.resolve("traces").exists())
        }
    }

    @Nested
    @DisplayName("trace operations")
    inner class TraceOps {
        @Test
        @DisplayName("save and load trace round-trip")
        fun testSaveAndLoad() {
            val trace = TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                outcome = "success",
                actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                durationMs = 1500,
            )
            val path = store.saveTrace(trace)
            assertTrue(path.exists())

            val loaded = store.loadTrace(path)
            assertNotNull(loaded)
            assertEquals("buy", loaded.intent)
            assertEquals("amazon.com", loaded.domain)
            assertEquals(1, loaded.actions.size)
        }
    }

    @Nested
    @DisplayName("experience stats operations")
    inner class StatsOps {
        @Test
        @DisplayName("loadStats returns fresh stats for unknown domain")
        fun testLoadFreshStats() {
            val stats = store.loadStats("amazon.com", "buy")
            assertEquals("buy", stats.intent)
            assertEquals(0, stats.totalAttempts)
            assertEquals(0.50, stats.confidence)
        }

        @Test
        @DisplayName("updateStats increments success count")
        fun testUpdateStatsSuccess() {
            val trace = TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/test", urlPattern = "/s?k=*",
                outcome = "success",
                actions = listOf(ActionStep(1, "click", selector = "#search", result = "success")),
            )
            store.updateStats(trace)

            val stats = store.loadStats("amazon.com", "buy")
            assertEquals(1, stats.successes)
            assertEquals(1, stats.totalAttempts)
            assertTrue(stats.confidence > 0.50)
            assertEquals(1, stats.selectorStats.size)
        }

        @Test
        @DisplayName("updateStats records failure category")
        fun testUpdateStatsFailure() {
            val trace = TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/test", urlPattern = "/s?k=*",
                outcome = "failure",
                failureCategory = "selector_drift",
                actions = listOf(ActionStep(1, "click", selector = "#old-btn", result = "error: not found")),
            )
            store.updateStats(trace)

            val stats = store.loadStats("amazon.com", "buy")
            assertEquals(1, stats.failures)
            assertEquals("selector_drift", stats.failureStats.keys.firstOrNull())
        }
    }

    @Nested
    @DisplayName("knowledge facts operations")
    inner class FactsOps {
        @Test
        @DisplayName("save and load facts round-trip")
        fun testSaveAndLoadFacts() = runBlocking {
            val facts = KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                status = VerificationStatus.HYPOTHESIS,
                siteFacts = SiteFacts(domain = "amazon.com", siteFamily = "amazon-like"),
                selectors = mapOf(
                    "button" to VerifiedSelector(primary = "#add-to-cart", fallbacks = listOf("[aria-label='Cart']")),
                ),
            )
            store.saveFacts(facts)

            val loaded = store.loadFacts("amazon.com", "buy")
            assertNotNull(loaded)
            assertEquals("buy", loaded.intent)
            assertEquals(VerificationStatus.HYPOTHESIS, loaded.status)
            assertEquals("amazon-like", loaded.siteFacts.siteFamily)
            assertEquals(1, loaded.selectors.size)
        }

        @Test
        @DisplayName("loadFacts returns null for unknown domain")
        fun testLoadFactsNull() {
            assertNull(store.loadFacts("unknown.com", "buy"))
        }

        @Test
        @DisplayName("promoteToVerified upgrades status with enough stats")
        fun testPromoteToVerified() = runBlocking {
            // First save facts
            val facts = KnowledgeFacts.createHypothesis("buy", "amazon.com", "/dp/*")
            store.saveFacts(facts)

            // Add enough successful traces
            repeat(6) { i ->
                val trace = TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test$i", urlPattern = "/dp/*",
                    outcome = "success",
                    actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                )
                store.updateStats(trace)
            }

            val promoted = store.promoteToVerified("amazon.com", "buy")
            assertNotNull(promoted)
            assertEquals(VerificationStatus.VERIFIED, promoted.status)
            assertTrue(promoted.promotionHistory.size >= 2)
        }
    }

    @Nested
    @DisplayName("intent-based query")
    inner class IntentQuery {
        @Test
        @DisplayName("cold start for unknown domain")
        fun testColdStart() {
            val result = store.query("https://unknown.com/test", "extract data")
            assertEquals("P5", result.tier)
            assertNotNull(result.summary)
        }

        @Test
        @DisplayName("returns facts when exact match found")
        fun testExactMatch() = runBlocking {
            val facts = KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                status = VerificationStatus.VERIFIED,
                siteFacts = SiteFacts(domain = "amazon.com"),
                selectors = mapOf("title" to VerifiedSelector(primary = "h1#title")),
            )
            store.saveFacts(facts)
            // Add stats so confidence is high
            repeat(10) {
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                    outcome = "success", actions = emptyList(),
                ))
            }

            val result = store.query("https://amazon.com/dp/test", "buy this product")
            assertNotEquals("P5", result.tier)
            assertEquals("amazon.com", result.domain)
            assertEquals("buy", result.intent)
            assertNotNull(result.primarySelectors)
            assertEquals("h1#title", result.primarySelectors?.get("title"))
        }
    }

    @Nested
    @DisplayName("list")
    inner class List {
        @Test
        @DisplayName("empty store returns empty list")
        fun testEmptyList() {
            val result = store.list()
            assertEquals(0, result.total)
        }
    }
}
