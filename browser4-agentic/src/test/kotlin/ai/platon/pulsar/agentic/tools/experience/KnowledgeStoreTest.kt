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
import java.time.temporal.ChronoUnit
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

        @Test
        @DisplayName("round-trip preserves trace/action timestamps and the redacted flag")
        fun testTimestampsSurviveRoundTrip() {
            // Regression: mapToTrace/mapToAction ignored their own `timestamp`
            // column, so a reloaded trace claimed to have happened "now" — and
            // `redacted` was never read back either, so every loaded trace
            // reported the default `true` whatever had been stored.
            val traceAt = Instant.parse("2026-02-03T04:05:06Z")
            val actionAt = Instant.parse("2026-02-03T04:05:07Z")
            val trace = TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                outcome = "success",
                actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success", timestamp = actionAt)),
                timestamp = traceAt,
                redacted = false,
            )
            val path = store.saveTrace(trace)

            val loaded = store.loadTrace(path)
            assertNotNull(loaded)
            assertEquals(traceAt, loaded.timestamp)
            assertEquals(actionAt, loaded.actions.single().timestamp)
            assertFalse(loaded.redacted, "a trace saved unredacted must not reload as redacted")
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

        @Test
        @DisplayName("reload keeps each selector's last_success timestamp")
        fun testSelectorTimestampsSurviveRoundTrip() {
            val at = Instant.now().minus(3, ChronoUnit.DAYS)
            store.updateStats(
                TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                    outcome = "success",
                    actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                    timestamp = at,
                )
            )

            val stats = store.loadStats("amazon.com", "buy")
            assertEquals(at, stats.selectorStats.getValue("#btn").lastSuccess)
        }

        @Test
        @DisplayName("reload keeps last_updated, so recency decay actually applies")
        fun testRecencyDecaySurvivesRoundTrip() {
            // Fresh evidence, written through the store: last_updated = now.
            store.updateStats(successTrace("fresh.com"))
            val fresh = store.loadStats("fresh.com", "buy")

            // The very same single success, dated 240 days ago (4 half-lives →
            // recency 0.0625). mapToStats used to drop `last_updated`, so the
            // reloaded stats looked brand new and confidence never decayed.
            writeStatsFile("stale.com", "buy", lastUpdated = Instant.now().minus(240, ChronoUnit.DAYS))
            val stale = store.loadStats("stale.com", "buy")

            assertTrue(
                stale.confidence < fresh.confidence,
                "stale confidence ${stale.confidence} must be below fresh ${fresh.confidence}"
            )
            assertEquals("P2", fresh.retrievalTier)
            assertEquals("P3", stale.retrievalTier, "decay must downgrade the retrieval tier")
        }

        private fun successTrace(domain: String): TraceRecord = TraceRecord(
            intent = "buy", domain = domain,
            url = "https://$domain/dp/test", urlPattern = "/dp/*",
            outcome = "success", actions = emptyList(),
        )

        /** Hand-write a stats file the way an older run would have left it on disk. */
        private fun writeStatsFile(domain: String, intent: String, lastUpdated: Instant) {
            val dir = tempDir.resolve("experience").resolve(domain)
            Files.createDirectories(dir)
            Files.writeString(
                dir.resolve("$intent.yaml"),
                """
                intent: $intent
                domain: $domain
                url_pattern: /dp/*
                total_attempts: 1
                successes: 1
                failures: 0
                last_updated: '$lastUpdated'
                """.trimIndent() + "\n",
            )
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
        @DisplayName("round-trip preserves created_at, updated_at and promotion dates")
        fun testTimestampsSurviveRoundTrip() = runBlocking {
            val created = Instant.parse("2026-01-02T03:04:05Z")
            val updated = Instant.parse("2026-02-03T04:05:06Z")
            val promoted = Instant.parse("2026-03-04T05:06:07Z")
            val facts = KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                promotionHistory = listOf(
                    PromotionEvent(from = "hypothesis", to = "candidate", date = promoted, reason = "seeded"),
                ),
                createdAt = created,
                updatedAt = updated,
            )
            store.saveFacts(facts)

            val loaded = store.loadFacts("amazon.com", "buy")
            assertNotNull(loaded)
            assertEquals(created, loaded.createdAt)
            assertEquals(updated, loaded.updatedAt)
            assertEquals(promoted, loaded.promotionHistory.single().date)
        }

        @Test
        @DisplayName("a load-modify-save cycle leaves created_at alone")
        fun testCreatedAtIsImmutableAcrossRewrites() = runBlocking {
            // The deep_learn write path is load → copy(updatedAt = now) → save.
            // created_at used to be dropped on load, so it was silently rewritten
            // to "now" on every pass; the audit trail said the entry was created
            // by the most recent command.
            val created = Instant.parse("2026-01-02T03:04:05Z")
            store.saveFacts(
                KnowledgeFacts(
                    intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                    createdAt = created, updatedAt = created,
                )
            )

            val existing = store.loadFacts("amazon.com", "buy")
            assertNotNull(existing)
            store.saveFacts(existing.copy(updatedAt = Instant.now()))

            val reloaded = store.loadFacts("amazon.com", "buy")
            assertNotNull(reloaded)
            assertEquals(created, reloaded.createdAt, "created_at must survive the rewrite")
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

        @Test
        @DisplayName("entries report the page type stored in page_facts")
        fun testListReportsTaskTypes() = runBlocking {
            // `task_types` was hardcoded to an empty list, so a fact that did
            // carry `page_facts.page_type` showed nothing in `experience list`.
            store.saveFacts(
                KnowledgeFacts(
                    intent = "extract", domain = "zhuanlan.zhihu.com", urlPattern = "/p/*",
                    pageFacts = PageFacts(pageType = "article-detail"),
                )
            )

            val entry = store.list().entries.single()
            assertEquals(listOf("article-detail"), entry.taskTypes)
        }
    }

    @Nested
    @DisplayName("configured base dir")
    inner class ConfiguredBaseDir {
        @Test
        @DisplayName("resolution falls back to the cwd-relative knowledge dir")
        fun testDefaultResolution() {
            withKnowledgeDir(null) {
                assertEquals(KnowledgeStore.DEFAULT_BASE_DIR, KnowledgeStore.resolveBaseDir())
            }
        }

        @Test
        @DisplayName("knowledge.dir relocates a no-argument store")
        fun testPropertyWins() {
            val configured = tempDir.resolve("configured-store")
            withKnowledgeDir(configured.toString()) {
                assertEquals(configured, KnowledgeStore.resolveBaseDir())

                // The no-argument constructor is what the executor default and the
                // REST bean use: it must follow the property, otherwise the
                // experience_* tools and the engine's automatic deposits fork into
                // two different stores.
                val store = KnowledgeStore()
                store.initializeStore()
                assertTrue(configured.resolve("traces").exists(), "traces/ must land under the configured dir")
                assertTrue(configured.resolve("facts").exists(), "facts/ must land under the configured dir")
            }
        }

        @Test
        @DisplayName("a blank knowledge.dir counts as unset")
        fun testBlankIsUnset() {
            withKnowledgeDir("   ") {
                assertEquals(KnowledgeStore.DEFAULT_BASE_DIR, KnowledgeStore.resolveBaseDir())
            }
        }

        private fun withKnowledgeDir(value: String?, block: () -> Unit) {
            val previous = System.getProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY)
            try {
                if (value == null) {
                    System.clearProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY)
                } else {
                    System.setProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY, value)
                }
                block()
            } finally {
                if (previous == null) {
                    System.clearProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY)
                } else {
                    System.setProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY, previous)
                }
            }
        }
    }
}
