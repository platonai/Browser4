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

/**
 * Additional coverage for KnowledgeStore gaps:
 * - list() with data, filters, and pagination
 * - query() URL pattern match (L2 fallback)
 * - promoteToVerified() boundary transitions
 * - saveTrace auto-creates domain directory
 * - loadTrace with invalid file
 * - PromotionEvent history building
 * - Concurrent stats updates across multiple domains
 */
@OptIn(ExperimentalPathApi::class)
@DisplayName("KnowledgeStore — Extended Coverage")
class KnowledgeStoreExtendedTest {

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
    @DisplayName("list() with data, filters, and pagination")
    inner class ListWithData {
        @Test
        @DisplayName("list returns entries after saving facts")
        fun testListWithEntries() = runBlocking {
            // Save facts for two domains
            store.saveFacts(KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                status = VerificationStatus.VERIFIED,
                siteFacts = SiteFacts(domain = "amazon.com", siteFamily = "amazon-like"),
            ))
            store.saveFacts(KnowledgeFacts(
                intent = "extract", domain = "ebay.com", urlPattern = "/itm/*",
                status = VerificationStatus.CANDIDATE,
                siteFacts = SiteFacts(domain = "ebay.com", siteFamily = "ebay-like"),
            ))
            // Add stats for confidence
            repeat(5) {
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                    outcome = "success", actions = emptyList(),
                ))
                store.updateStats(TraceRecord(
                    intent = "extract", domain = "ebay.com",
                    url = "https://ebay.com/itm/test", urlPattern = "/itm/*",
                    outcome = "success", actions = emptyList(),
                ))
            }

            val result = store.list()
            assertTrue(result.total >= 2, "Expected at least 2 entries, got ${result.total}")
            assertEquals(1, result.page)
        }

        @Test
        @DisplayName("list respects domain filter")
        fun testListDomainFilter() = runBlocking {
            store.saveFacts(KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
            ))
            store.saveFacts(KnowledgeFacts(
                intent = "search", domain = "github.com", urlPattern = "/search*",
            ))

            val amazonResult = store.list(domainFilter = "amazon")
            assertEquals(1, amazonResult.total)
            assertEquals("amazon.com", amazonResult.entries.first().domain)

            val githubResult = store.list(domainFilter = "git")
            assertEquals(1, githubResult.total)
            assertEquals("github.com", githubResult.entries.first().domain)

            val noneResult = store.list(domainFilter = "nonexistent")
            assertEquals(0, noneResult.total)
        }

        @Test
        @DisplayName("list respects intent filter")
        fun testListIntentFilter() = runBlocking {
            store.saveFacts(KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
            ))
            store.saveFacts(KnowledgeFacts(
                intent = "search", domain = "amazon.com", urlPattern = "/s?k=*",
            ))

            val buyResult = store.list(intentFilter = "buy")
            assertEquals(1, buyResult.total)

            val searchResult = store.list(intentFilter = "search")
            assertEquals(1, searchResult.total)
        }

        @Test
        @DisplayName("list pagination works correctly")
        fun testListPagination() = runBlocking {
            // Save 5 entries
            for (i in 1..5) {
                store.saveFacts(KnowledgeFacts(
                    intent = "buy", domain = "site${i}.com", urlPattern = "/*",
                ))
            }

            val page1 = store.list(page = 1, pageSize = 2)
            assertEquals(5, page1.total)
            assertEquals(2, page1.entries.size)
            assertEquals(3, page1.totalPages)

            val page2 = store.list(page = 2, pageSize = 2)
            assertEquals(5, page2.total)
            assertEquals(2, page2.entries.size)

            val page3 = store.list(page = 3, pageSize = 2)
            assertEquals(5, page3.total)
            assertEquals(1, page3.entries.size)
        }

        @Test
        @DisplayName("list entries are sorted by confidence descending")
        fun testListSortedByConfidence() = runBlocking {
            // Save facts with different confidence levels
            store.saveFacts(KnowledgeFacts(
                intent = "buy", domain = "low-conf.com", urlPattern = "/*",
            ))
            store.saveFacts(KnowledgeFacts(
                intent = "buy", domain = "high-conf.com", urlPattern = "/*",
            ))
            // Add many successes to high-conf
            repeat(20) {
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "high-conf.com",
                    url = "https://high-conf.com/test", urlPattern = "/*",
                    outcome = "success", actions = emptyList(),
                ))
            }

            val result = store.list()
            if (result.entries.size >= 2) {
                val first = result.entries[0]
                val last = result.entries.last()
                assertTrue(first.confidence >= last.confidence,
                    "First entry confidence ${first.confidence} should be >= last ${last.confidence}")
            }
        }
    }

    @Nested
    @DisplayName("query() fallback levels")
    inner class QueryFallbacks {
        @Test
        @DisplayName("L2: URL pattern match within same domain")
        fun testUrlPatternMatch() = runBlocking {
            // Save facts with a specific URL pattern
            val facts = KnowledgeFacts(
                intent = "extract", domain = "amazon.com", urlPattern = "/dp/*",
                status = VerificationStatus.CANDIDATE,
                siteFacts = SiteFacts(domain = "amazon.com"),
                selectors = mapOf("title" to VerifiedSelector(primary = "h1#title")),
            )
            store.saveFacts(facts)
            repeat(3) {
                store.updateStats(TraceRecord(
                    intent = "extract", domain = "amazon.com",
                    url = "https://amazon.com/dp/test$it", urlPattern = "/dp/*",
                    outcome = "success", actions = emptyList(),
                ))
            }

            // Query with a different intent but same domain+pattern → should match via L2
            val result = store.query("https://amazon.com/dp/B0TEST", "read reviews")
            // L2 URL pattern match should find facts even with different intent
            assertNotEquals("P5", result.tier, "URL pattern match should find knowledge")
            assertEquals("amazon.com", result.domain)
        }

        @Test
        @DisplayName("L6: different domain returns cold start")
        fun testDifferentDomainColdStart() {
            val result = store.query("https://completely-different.com/page", "buy product")
            assertEquals("P5", result.tier)
        }
    }

    @Nested
    @DisplayName("promoteToVerified() boundary transitions")
    inner class PromoteBoundaries {
        @Test
        @DisplayName("promotes to CANDIDATE at 2+ successes, confidence >= 0.60")
        fun testPromoteToCandidate() = runBlocking {
            store.saveFacts(KnowledgeFacts.createHypothesis("buy", "amazon.com", "/dp/*"))

            // Add exactly 2 successful traces (boundary)
            repeat(2) { i ->
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test$i", urlPattern = "/dp/*",
                    outcome = "success",
                    actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                ))
            }

            val promoted = store.promoteToVerified("amazon.com", "buy")
            assertNotNull(promoted)
            // With 2 successes, should become at least CANDIDATE
            assertTrue(promoted!!.status == VerificationStatus.CANDIDATE ||
                promoted.status == VerificationStatus.VERIFIED,
                "Expected CANDIDATE or VERIFIED but got ${promoted.status}")
            assertTrue(promoted.promotionHistory.isNotEmpty())
        }

        @Test
        @DisplayName("promotes to VERIFIED at 5+ successes, confidence >= 0.85")
        fun testPromoteToVerified() = runBlocking {
            store.saveFacts(KnowledgeFacts.createHypothesis("buy", "amazon.com", "/dp/*"))

            // Add 5 successful traces
            repeat(5) { i ->
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test$i", urlPattern = "/dp/*",
                    outcome = "success",
                    actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                ))
            }

            val promoted = store.promoteToVerified("amazon.com", "buy")
            assertNotNull(promoted)
            assertEquals(VerificationStatus.VERIFIED, promoted!!.status)
        }

        @Test
        @DisplayName("no promotion when confidence too low")
        fun testNoPromotionLowConfidence() = runBlocking {
            store.saveFacts(KnowledgeFacts.createHypothesis("buy", "amazon.com", "/dp/*"))

            // Add only failures
            repeat(3) { i ->
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test$i", urlPattern = "/dp/*",
                    outcome = "failure",
                    failureCategory = "selector_drift",
                    actions = emptyList(),
                ))
            }

            val result = store.promoteToVerified("amazon.com", "buy")
            // Should stay HYPOTHESIS or return null (no promotion)
            if (result != null) {
                assertEquals(VerificationStatus.HYPOTHESIS, result.status)
            }
        }

        @Test
        @DisplayName("promotion events accumulate in history")
        fun testPromotionEventsHistory() = runBlocking {
            store.saveFacts(KnowledgeFacts.createHypothesis("buy", "amazon.com", "/dp/*"))

            // First promotion: HYPOTHESIS → CANDIDATE
            repeat(3) { i ->
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/test$i", urlPattern = "/dp/*",
                    outcome = "success",
                    actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                ))
            }
            val first = store.promoteToVerified("amazon.com", "buy")
            assertNotNull(first)
            val eventsAfterFirst = first!!.promotionHistory
            assertTrue(eventsAfterFirst.size >= 2,
                "Should have initial + promotion event, got ${eventsAfterFirst.size}")

            // Second promotion: CANDIDATE → VERIFIED
            repeat(5) { i ->
                store.updateStats(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/more$i", urlPattern = "/dp/*",
                    outcome = "success",
                    actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                ))
            }
            val second = store.promoteToVerified("amazon.com", "buy")
            assertNotNull(second)
            assertTrue(second!!.promotionHistory.size > eventsAfterFirst.size,
                "History should grow: ${second.promotionHistory.size} > $eventsAfterFirst.size")
            // Last event should be to VERIFIED
            val lastEvent = second.promotionHistory.last()
            assertEquals("verified", lastEvent.to.lowercase())
        }
    }

    @Nested
    @DisplayName("Trace operations — edge cases")
    inner class TraceEdgeCases {
        @Test
        @DisplayName("saveTrace auto-creates domain directory")
        fun testSaveTraceCreatesDomainDir() {
            val trace = TraceRecord(
                intent = "search", domain = "new-domain.com",
                url = "https://new-domain.com/search", urlPattern = "/search*",
                outcome = "success",
            )
            val path = store.saveTrace(trace)
            assertTrue(path.exists())
            assertTrue(path.parent.exists()) // Domain directory created
            assertEquals("new-domain.com", path.parent.fileName.toString())
        }

        @Test
        @DisplayName("loadTrace returns null for invalid YAML")
        fun testLoadTraceInvalidFile() {
            val domainDir = tempDir.resolve("traces").resolve("test.com")
            Files.createDirectories(domainDir)
            val badFile = domainDir.resolve("bad.yaml")
            Files.writeString(badFile, "::: this is not valid YAML :::")

            val result = store.loadTrace(badFile)
            assertNull(result)
        }

        @Test
        @DisplayName("listTraces with domain filter")
        fun testListTracesWithDomainFilter() {
            store.saveTrace(TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/1", urlPattern = "/dp/*",
                outcome = "success",
            ))
            store.saveTrace(TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/2", urlPattern = "/dp/*",
                outcome = "success",
            ))
            store.saveTrace(TraceRecord(
                intent = "search", domain = "ebay.com",
                url = "https://ebay.com/sch/1", urlPattern = "/sch/*",
                outcome = "success",
            ))

            val amazonTraces = store.listTraces(domain = "amazon.com")
            assertEquals(2, amazonTraces.size)

            val allTraces = store.listTraces()
            assertTrue(allTraces.size >= 3)
        }

        @Test
        @DisplayName("listTraces pagination")
        fun testListTracesPagination() {
            for (i in 1..5) {
                store.saveTrace(TraceRecord(
                    intent = "buy", domain = "amazon.com",
                    url = "https://amazon.com/dp/$i", urlPattern = "/dp/*",
                    outcome = "success",
                ))
            }

            val page1 = store.listTraces(domain = "amazon.com", page = 1, pageSize = 2)
            assertEquals(2, page1.size)

            val page2 = store.listTraces(domain = "amazon.com", page = 2, pageSize = 2)
            assertEquals(2, page2.size)

            val page3 = store.listTraces(domain = "amazon.com", page = 3, pageSize = 2)
            assertEquals(1, page3.size)
        }
    }

    @Nested
    @DisplayName("Stats operations — edge cases")
    inner class StatsEdgeCases {
        @Test
        @DisplayName("updateStats across multiple domains keeps isolation")
        fun testStatsDomainIsolation() {
            store.updateStats(TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                outcome = "success",
                actions = listOf(ActionStep(1, "click", selector = "#amz-btn", result = "success")),
            ))
            store.updateStats(TraceRecord(
                intent = "buy", domain = "ebay.com",
                url = "https://ebay.com/itm/test", urlPattern = "/itm/*",
                outcome = "success",
                actions = listOf(ActionStep(1, "click", selector = "#ebay-btn", result = "success")),
            ))

            val amzStats = store.loadStats("amazon.com", "buy")
            val ebayStats = store.loadStats("ebay.com", "buy")

            assertEquals(1, amzStats.successes)
            assertEquals("amazon.com", amzStats.domain)
            assertTrue(amzStats.selectorStats.containsKey("#amz-btn"))

            assertEquals(1, ebayStats.successes)
            assertEquals("ebay.com", ebayStats.domain)
            assertTrue(ebayStats.selectorStats.containsKey("#ebay-btn"))
        }

        @Test
        @DisplayName("stats persist across loads")
        fun testStatsPersistence() {
            store.updateStats(TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                outcome = "success",
            ))
            store.updateStats(TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/test2", urlPattern = "/dp/*",
                outcome = "success",
            ))

            val stats = store.loadStats("amazon.com", "buy")
            assertEquals(2, stats.successes)
            assertEquals(2, stats.totalAttempts)
        }
    }

    @Nested
    @DisplayName("Facts operations — edge cases")
    inner class FactsEdgeCases {
        @Test
        @DisplayName("saveFacts overwrites existing facts")
        fun testSaveFactsOverwrite() = runBlocking {
            val v1 = KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                status = VerificationStatus.HYPOTHESIS,
            )
            store.saveFacts(v1)

            val v2 = KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                status = VerificationStatus.CANDIDATE,
                siteFacts = SiteFacts(domain = "amazon.com", siteFamily = "amazon-like"),
            )
            store.saveFacts(v2)

            val loaded = store.loadFacts("amazon.com", "buy")
            assertNotNull(loaded)
            assertEquals(VerificationStatus.CANDIDATE, loaded!!.status)
            assertEquals("amazon-like", loaded.siteFacts.siteFamily)
        }

        @Test
        @DisplayName("loadFacts returns null for non-existent domain dir")
        fun testLoadFactsMissingDir() {
            assertNull(store.loadFacts("nonexistent.com", "any"))
        }
    }
}
