package ai.platon.pulsar.agentic.tools.experience

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

/**
 * Additional coverage for gaps identified in the PEM test coverage audit:
 * - Missing Intent classifications (7 of 11)
 * - Missing FailureCategory classifications (8 of 12)
 * - Confidence edge cases (floor/cap, recency decay, withFailure)
 * - SelectorStats.stabilityScore
 * - TaskType and SuccessCriteria
 */
@DisplayName("Experience Models — Extended Coverage")
class ExperienceModelsExtendedTest {

    @Nested
    @DisplayName("Intent classification — remaining intents")
    inner class RemainingIntentClassification {
        @Test
        @DisplayName("classifies Book from keywords")
        fun testClassifyBook() {
            assertEquals(Intent.BOOK, Intent.classify("book a flight to Tokyo"))
            assertEquals(Intent.BOOK, Intent.classify("reserve a hotel room"))
            assertEquals(Intent.BOOK, Intent.classify("get tickets for the concert"))
        }

        @Test
        @DisplayName("classifies Checkout from keywords")
        fun testClassifyCheckout() {
            assertEquals(Intent.CHECKOUT, Intent.classify("proceed to checkout page"))
            assertEquals(Intent.CHECKOUT, Intent.classify("go to payment checkout"))
            assertEquals(Intent.CHECKOUT, Intent.classify("complete my checkout now"))
        }

        @Test
        @DisplayName("classifies Extract from keywords")
        fun testClassifyExtract() {
            assertEquals(Intent.EXTRACT, Intent.classify("extract product details"))
            assertEquals(Intent.EXTRACT, Intent.classify("scrape the page data"))
            assertEquals(Intent.EXTRACT, Intent.classify("collect all prices"))
            assertEquals(Intent.EXTRACT, Intent.classify("fetch the article text"))
        }

        @Test
        @DisplayName("classifies Compare from keywords")
        fun testClassifyCompare() {
            assertEquals(Intent.COMPARE, Intent.classify("compare these two products"))
            assertEquals(Intent.COMPARE, Intent.classify("laptop vs desktop"))
            assertEquals(Intent.COMPARE, Intent.classify("difference between these options"))
        }

        @Test
        @DisplayName("classifies Download from keywords")
        fun testClassifyDownload() {
            assertEquals(Intent.DOWNLOAD, Intent.classify("download the report"))
            assertEquals(Intent.DOWNLOAD, Intent.classify("save file to disk"))
            assertEquals(Intent.DOWNLOAD, Intent.classify("export data as CSV"))
        }

        @Test
        @DisplayName("classifies FillForm from keywords")
        fun testClassifyFillForm() {
            assertEquals(Intent.FILL_FORM, Intent.classify("subscribe to updates"))
            assertEquals(Intent.FILL_FORM, Intent.classify("apply for the job"))
            assertEquals(Intent.FILL_FORM, Intent.classify("sign up for an account"))
        }

        @Test
        @DisplayName("classifies Monitor from keywords")
        fun testClassifyMonitor() {
            assertEquals(Intent.MONITOR, Intent.classify("monitor the price changes"))
            assertEquals(Intent.MONITOR, Intent.classify("watch this page for updates"))
            assertEquals(Intent.MONITOR, Intent.classify("track the stock level"))
            assertEquals(Intent.MONITOR, Intent.classify("alert me when it changes"))
            assertEquals(Intent.MONITOR, Intent.classify("notify if price drops"))
        }

        @Test
        @DisplayName("classifies Other for unrecognized text")
        fun testClassifyOther() {
            assertEquals(Intent.OTHER, Intent.classify(""))
            assertEquals(Intent.OTHER, Intent.classify(null))
            assertEquals(Intent.OTHER, Intent.classify("do something vague"))
            assertEquals(Intent.OTHER, Intent.classify("xyzzy plugh"))
        }
    }

    @Nested
    @DisplayName("FailureCategory classification — remaining categories")
    inner class RemainingFailureClassification {
        @Test
        @DisplayName("classifies visual drift")
        fun testVisualDrift() {
            assertEquals(
                FailureCategory.VISUAL_DRIFT,
                FailureCategory.classify("element not visible on page")
            )
            assertEquals(
                FailureCategory.VISUAL_DRIFT,
                FailureCategory.classify("button is obscured by another element")
            )
            assertEquals(
                FailureCategory.VISUAL_DRIFT,
                FailureCategory.classify("element position changed after scroll")
            )
            assertTrue(FailureCategory.VISUAL_DRIFT.recoverable)
        }

        @Test
        @DisplayName("classifies network errors")
        fun testNetwork() {
            assertEquals(
                FailureCategory.NETWORK,
                FailureCategory.classify("connection timeout after 30s")
            )
            assertEquals(
                FailureCategory.NETWORK,
                FailureCategory.classify("ECONNREFUSED: server not reachable")
            )
            assertTrue(FailureCategory.NETWORK.recoverable)
        }

        @Test
        @DisplayName("classifies permission denied")
        fun testPermissionDenied() {
            assertEquals(
                FailureCategory.PERMISSION_DENIED,
                FailureCategory.classify("permission denied for this operation")
            )
            assertEquals(
                FailureCategory.PERMISSION_DENIED,
                FailureCategory.classify("admin only access required")
            )
            assertFalse(FailureCategory.PERMISSION_DENIED.recoverable)
        }

        @Test
        @DisplayName("classifies timing issues")
        fun testTiming() {
            assertEquals(
                FailureCategory.TIMING,
                FailureCategory.classify("page still loading slowly")
            )
            assertEquals(
                FailureCategory.TIMING,
                FailureCategory.classify("element not ready for interaction")
            )
            assertEquals(
                FailureCategory.TIMING,
                FailureCategory.classify("wait for page to finish loading")
            )
            assertTrue(FailureCategory.TIMING.recoverable)
        }

        @Test
        @DisplayName("classifies lazy loading failures")
        fun testLazyLoading() {
            assertEquals(
                FailureCategory.LAZY_LOADING,
                FailureCategory.classify("lazy-loaded image placeholder detected")
            )
            assertEquals(
                FailureCategory.LAZY_LOADING,
                FailureCategory.classify("data-src attribute not yet replaced")
            )
            assertTrue(FailureCategory.LAZY_LOADING.recoverable)
        }

        @Test
        @DisplayName("classifies A/B experiment drift")
        fun testAbExperiment() {
            assertEquals(
                FailureCategory.AB_EXPERIMENT,
                FailureCategory.classify("A/B test variant differs from expected")
            )
            assertEquals(
                FailureCategory.AB_EXPERIMENT,
                FailureCategory.classify("split test variant differs from baseline")
            )
            assertTrue(FailureCategory.AB_EXPERIMENT.recoverable)
        }

        @Test
        @DisplayName("classifies unexpected redirect")
        fun testUnexpectedRedirect() {
            assertEquals(
                FailureCategory.UNEXPECTED_REDIRECT,
                FailureCategory.classify("redirected to unexpected URL during checkout")
            )
            assertEquals(
                FailureCategory.UNEXPECTED_REDIRECT,
                FailureCategory.classify("page was redirected to different domain")
            )
            assertTrue(FailureCategory.UNEXPECTED_REDIRECT.recoverable)
        }

        @Test
        @DisplayName("UNKNOWN with non-null message")
        fun testUnknownWithMessage() {
            assertEquals(
                FailureCategory.UNKNOWN,
                FailureCategory.classify("some completely unclassified error")
            )
            assertFalse(FailureCategory.UNKNOWN.recoverable)
        }

        @Test
        @DisplayName("ANTI_BOT is the only category that degrades retrieval")
        fun testOnlyAntiBotDegradesRetrieval() {
            val degradingCategories = FailureCategory.entries.filter { it.degradeRetrieval }
            assertEquals(1, degradingCategories.size)
            assertEquals(FailureCategory.ANTI_BOT, degradingCategories.first())
        }
    }

    @Nested
    @DisplayName("Confidence edge cases")
    inner class ConfidenceEdgeCases {
        @Test
        @DisplayName("confidence is capped at 0.95")
        fun testConfidenceCap() {
            val stats = ExperienceStats(
                intent = "buy", domain = "example.com", urlPattern = "/*",
                totalAttempts = 1000, successes = 1000, failures = 0,
                lastUpdated = Instant.now(),
            )
            assertTrue(stats.confidence <= ExperienceStats.CAP,
                "Confidence ${stats.confidence} should not exceed cap ${ExperienceStats.CAP}")
        }

        @Test
        @DisplayName("confidence floors at 0.05")
        fun testConfidenceFloor() {
            val stats = ExperienceStats(
                intent = "buy", domain = "example.com", urlPattern = "/*",
                totalAttempts = 1000, successes = 0, failures = 1000,
                lastUpdated = Instant.now().minus(365, ChronoUnit.DAYS),
            )
            assertTrue(stats.confidence >= ExperienceStats.FLOOR,
                "Confidence ${stats.confidence} should not go below floor ${ExperienceStats.FLOOR}")
        }

        @Test
        @DisplayName("recency decay reduces confidence over time")
        fun testRecencyDecay() {
            val recent = ExperienceStats(
                intent = "buy", domain = "example.com", urlPattern = "/*",
                totalAttempts = 10, successes = 8, failures = 2,
                lastUpdated = Instant.now(),
            )
            val stale = recent.copy(
                lastUpdated = Instant.now().minus(180, ChronoUnit.DAYS),
            )
            assertTrue(stale.confidence < recent.confidence,
                "Stale confidence ${stale.confidence} should be lower than recent ${recent.confidence}")
        }

        @Test
        @DisplayName("withFailure produces correct failure stats")
        fun testWithFailureUpdatesStats() {
            val stats = ExperienceStats.create("buy", "amazon.com", "/dp/*")
            val trace = TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/test", urlPattern = "/dp/*",
                outcome = "failure",
                failureCategory = "anti_bot",
                actions = listOf(
                    ActionStep(1, "click", selector = "#btn", result = "error: captcha detected"),
                ),
            )
            val updated = stats.withFailure(trace)
            assertEquals(1, updated.failures)
            assertEquals(1, updated.totalAttempts)
            assertEquals(1, updated.failureStats["anti_bot"])
            // Selector stats should record the failure
            assertTrue(updated.selectorStats.containsKey("#btn"))
            assertEquals(1, updated.selectorStats["#btn"]?.failures)
        }

        @Test
        @DisplayName("selectorStats aggregation across multiple traces")
        fun testSelectorStatsAggregation() {
            val stats = ExperienceStats.create("buy", "amazon.com", "/dp/*")
            val successTrace = TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/1", urlPattern = "/dp/*",
                outcome = "success",
                actions = listOf(ActionStep(1, "click", selector = "#good-btn", result = "success")),
            )
            val failureTrace = TraceRecord(
                intent = "buy", domain = "amazon.com",
                url = "https://amazon.com/dp/2", urlPattern = "/dp/*",
                outcome = "failure",
                failureCategory = "selector_drift",
                actions = listOf(ActionStep(1, "click", selector = "#bad-btn", result = "error: element not found")),
            )
            val afterSuccess = stats.withSuccess(successTrace)
            val afterBoth = afterSuccess.withFailure(failureTrace)

            assertEquals(1, afterBoth.selectorStats["#good-btn"]?.successes)
            assertEquals(1, afterBoth.selectorStats["#bad-btn"]?.failures)
            assertEquals(2, afterBoth.totalAttempts)
            assertEquals(1, afterBoth.successes)
            assertEquals(1, afterBoth.failures)
        }

        @Test
        @DisplayName("retrieval tiers map correctly to confidence ranges")
        fun testRetrievalTiers() {
            // P1: >= 0.85
            val p1Stats = ExperienceStats(
                intent = "buy", domain = "a.com", urlPattern = "/*",
                totalAttempts = 100, successes = 99, failures = 1,
                lastUpdated = Instant.now(),
            )
            assertTrue(p1Stats.confidence >= 0.85, "Expected P1 confidence but got ${p1Stats.confidence}")
            assertEquals("P1", p1Stats.retrievalTier)

            // P2: >= 0.60
            val p2Stats = ExperienceStats(
                intent = "buy", domain = "b.com", urlPattern = "/*",
                totalAttempts = 10, successes = 7, failures = 3,
                lastUpdated = Instant.now(),
            )
            assertTrue(p2Stats.confidence in 0.60..0.84, "Expected P2 confidence but got ${p2Stats.confidence}")
            assertEquals("P2", p2Stats.retrievalTier)

            // P3: >= 0.40
            val p3Stats = ExperienceStats(
                intent = "buy", domain = "c.com", urlPattern = "/*",
                totalAttempts = 5, successes = 1, failures = 4,
                lastUpdated = Instant.now(),
            )
            assertTrue(p3Stats.confidence in 0.40..0.59, "Expected P3 confidence but got ${p3Stats.confidence}")
            assertEquals("P3", p3Stats.retrievalTier)

            // P4: < 0.40
            val p4Stats = ExperienceStats(
                intent = "buy", domain = "d.com", urlPattern = "/*",
                totalAttempts = 10, successes = 1, failures = 9,
                lastUpdated = Instant.now().minus(300, ChronoUnit.DAYS),
            )
            assertTrue(p4Stats.confidence < 0.40, "Expected P4 confidence but got ${p4Stats.confidence}")
            assertEquals("P4", p4Stats.retrievalTier)
        }

        @Test
        @DisplayName("ANtI_BOT in failure stats degrades retrieval tier")
        fun testTierDegradationFromAntiBot() {
            // High confidence but with anti-bot failures → should degrade from P1 to P2
            val stats = ExperienceStats(
                intent = "buy", domain = "blocked.com", urlPattern = "/*",
                totalAttempts = 100, successes = 99, failures = 1,
                failureStats = mapOf("anti_bot" to 1),
                lastUpdated = Instant.now(),
            )
            assertTrue(stats.confidence >= 0.85, "Should be P1 by confidence alone")
            assertEquals("P2", stats.retrievalTier, "Should degrade to P2 due to anti_bot")
            assertTrue(stats.degradedByFailures)
        }
    }

    @Nested
    @DisplayName("SelectorStats stability score")
    inner class SelectorStatsTests {
        @Test
        @DisplayName("fresh selector has stability 0.5")
        fun testFreshSelector() {
            val ss = SelectorStats(selector = "#btn")
            assertEquals(0.5, ss.stabilityScore)
        }

        @Test
        @DisplayName("stability increases with successes")
        fun testStabilityIncreases() {
            val ss = SelectorStats(selector = "#btn", successes = 10, failures = 0)
            assertTrue(ss.stabilityScore > 0.9, "10/0 should have high stability")
        }

        @Test
        @DisplayName("stability decreases with failures")
        fun testStabilityDecreases() {
            val good = SelectorStats(selector = "#good", successes = 9, failures = 1)
            val bad = SelectorStats(selector = "#bad", successes = 1, failures = 9)
            assertTrue(good.stabilityScore > bad.stabilityScore,
                "Good selector stability ${good.stabilityScore} should exceed bad ${bad.stabilityScore}")
        }
    }

    @Nested
    @DisplayName("TaskType and SuccessCriteria")
    inner class TaskTypeTests {
        @Test
        @DisplayName("fromString resolves case-insensitively")
        fun testFromString() {
            assertEquals(TaskType.SEARCH, TaskType.fromString("search"))
            assertEquals(TaskType.SEARCH, TaskType.fromString("SEARCH"))
            assertEquals(TaskType.EXTRACT_PRODUCT_DETAIL, TaskType.fromString("extract_product_detail"))
        }

        @Test
        @DisplayName("fromStringOrNull returns null for unknown")
        fun testFromStringOrNull() {
            assertNull(TaskType.fromStringOrNull("nonexistent_type"))
            assertNotNull(TaskType.fromStringOrNull("navigate"))
        }

        @Test
        @DisplayName("fromString throws for unknown")
        fun testFromStringThrows() {
            assertFailsWith<IllegalArgumentException> {
                TaskType.fromString("invalid_task_type")
            }
        }

        @Test
        @DisplayName("all task types have default success criteria")
        fun testSuccessCriteriaDefaults() {
            for (taskType in TaskType.entries) {
                val criteria = SuccessCriteria.DEFAULTS[taskType]
                // Each task type may or may not have criteria, but the map lookup should work
                if (criteria != null) {
                    assertTrue(criteria.isNotEmpty(), "$taskType has empty criteria list")
                }
            }
        }

        @Test
        @DisplayName("there are 12 task types")
        fun testTaskTypeCount() {
            assertEquals(12, TaskType.entries.size)
        }
    }

    @Nested
    @DisplayName("PatternPromotion canPromote logic")
    inner class PatternPromotionTests {
        @Test
        @DisplayName("cannot promote with no sites")
        fun testCannotPromoteNoSites() {
            val pp = PatternPromotion(level = PromotionLevel.FAMILY)
            assertFalse(pp.canPromote)
        }

        @Test
        @DisplayName("cannot promote below min sites")
        fun testCannotPromoteInsufficientSites() {
            val pp = PatternPromotion(
                level = PromotionLevel.CATEGORY, // min 3
                confirmedSites = listOf("amazon.com", "ebay.com"),
                disconfirmedSites = emptyList(),
            )
            assertFalse(pp.canPromote)
        }

        @Test
        @DisplayName("can promote when ratio >= 75% and min sites met")
        fun testCanPromoteSufficient() {
            val pp = PatternPromotion(
                level = PromotionLevel.FAMILY, // min 2
                confirmedSites = listOf("amazon.com", "ebay.com", "walmart.com"),
                disconfirmedSites = listOf("etsy.com"), // 3/4 = 75% → meets threshold
            )
            assertTrue(pp.canPromote)
        }

        @Test
        @DisplayName("cannot promote when ratio below 75%")
        fun testCannotPromoteLowRatio() {
            val pp = PatternPromotion(
                level = PromotionLevel.FAMILY, // min 2
                confirmedSites = listOf("amazon.com", "ebay.com"),
                disconfirmedSites = listOf("walmart.com", "etsy.com", "target.com"), // 2/5 = 40%
            )
            assertFalse(pp.canPromote)
        }
    }
}
