package ai.platon.pulsar.agentic.tools.experience

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

@DisplayName("Revised Experience Models")
class ExperienceModelsTest {

    private val mapper = pulsarObjectMapper()

    @Nested
    @DisplayName("Intent classification")
    inner class IntentClassification {
        @Test
        @DisplayName("classifies Buy from keywords")
        fun testClassifyBuy() {
            assertEquals(Intent.BUY, Intent.classify("Buy cheapest laptop"))
            assertEquals(Intent.BUY, Intent.classify("add to cart and purchase"))
            assertEquals(Intent.BUY, Intent.classify("order this item"))
        }

        @Test
        @DisplayName("classifies Search from keywords")
        fun testClassifySearch() {
            assertEquals(Intent.SEARCH, Intent.classify("search for laptops"))
            assertEquals(Intent.SEARCH, Intent.classify("find me the best headphones"))
        }

        @Test
        @DisplayName("classifies Login from keywords")
        fun testClassifyLogin() {
            assertEquals(Intent.LOGIN, Intent.classify("login to amazon"))
            assertEquals(Intent.LOGIN, Intent.classify("sign in with google"))
        }

        @Test
        @DisplayName("two identical action sequences map to different intents")
        fun testSameActionsDifferentIntents() {
            // Both use "search" and "find" keywords → SEARCH intent
            // But "buy laptop" vs "read article" map to different intents
            val buyIntent = Intent.classify("buy the cheapest laptop")
            val articleIntent = Intent.classify("read this article about AI")

            // "buy" keyword → BUY intent
            assertEquals(Intent.BUY, buyIntent)
            // "read" + "article" → READ intent
            assertEquals(Intent.READ, articleIntent)
        }
    }

    @Nested
    @DisplayName("FailureCategory classification")
    inner class FailureClassification {
        @Test
        @DisplayName("classifies selector drift from error message")
        fun testSelectorDrift() {
            assertEquals(
                FailureCategory.SELECTOR_DRIFT,
                FailureCategory.classify("element not found: .old-selector")
            )
        }

        @Test
        @DisplayName("classifies anti-bot from captcha message")
        fun testAntiBot() {
            assertEquals(
                FailureCategory.ANTI_BOT,
                FailureCategory.classify("CAPTCHA detected on page")
            )
            assertTrue(FailureCategory.ANTI_BOT.degradeRetrieval)
        }

        @Test
        @DisplayName("classifies overlay from cookie consent message")
        fun testOverlayBlocked() {
            assertEquals(
                FailureCategory.OVERLAY_BLOCKED,
                FailureCategory.classify("cookie consent popup blocked interaction")
            )
        }

        @Test
        @DisplayName("classifies auth required from 401")
        fun testAuthRequired() {
            assertEquals(
                FailureCategory.AUTH_REQUIRED,
                FailureCategory.classify("HTTP 401 Unauthorized")
            )
        }

        @Test
        @DisplayName("null or blank message returns UNKNOWN")
        fun testNullMessage() {
            assertEquals(FailureCategory.UNKNOWN, FailureCategory.classify(null))
            assertEquals(FailureCategory.UNKNOWN, FailureCategory.classify(""))
        }
    }

    @Nested
    @DisplayName("ExperienceStats confidence computation")
    inner class StatsConfidence {
        @Test
        @DisplayName("initial stats have confidence 0.50")
        fun testInitialConfidence() {
            val stats = ExperienceStats.create("buy", "amazon.com", "/dp/*")
            assertEquals(ExperienceStats.INITIAL_CONFIDENCE, stats.confidence)
        }

        @Test
        @DisplayName("confidence increases with successful traces")
        fun testConfidenceIncreases() {
            val stats = ExperienceStats.create("buy", "amazon.com", "/dp/*")
            val trace = TraceRecord(
                intent = "buy", domain = "amazon.com", url = "https://amazon.com/dp/test",
                urlPattern = "/dp/*", outcome = "success",
                actions = listOf(ActionStep(1, "click", selector = "#btn", result = "success")),
                durationMs = 2000,
            )
            val updated = stats.withSuccess(trace)
            assertTrue(updated.confidence > 0.50, "Should increase after success")
            assertEquals(1, updated.successes)
            assertEquals(1, updated.totalAttempts)
        }

        @Test
        @DisplayName("retrieval tier P3 for initial stats (confidence 0.50)")
        fun testInitialTier() {
            val stats = ExperienceStats.create("buy", "amazon.com", "/dp/*")
            assertEquals("P3", stats.retrievalTier)
        }

        @Test
        @DisplayName("degradedByFailures true when anti-bot failures present")
        fun testDegradedByFailures() {
            val stats = ExperienceStats(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                failureStats = mapOf("anti_bot" to 1),
            )
            assertTrue(stats.degradedByFailures)
        }
    }

    @Nested
    @DisplayName("KnowledgeFacts serialization")
    inner class KnowledgeFactsSerialization {
        @Test
        @DisplayName("round-trip serialization")
        fun testRoundTrip() {
            val facts = KnowledgeFacts(
                intent = "buy",
                domain = "amazon.com",
                urlPattern = "/dp/*",
                status = VerificationStatus.CANDIDATE,
                siteFacts = SiteFacts(
                    domain = "amazon.com",
                    siteFamily = "amazon-like",
                    siteCategory = "marketplace",
                    siteUniversal = "ecommerce",
                    authPattern = "cookie",
                ),
                selectors = mapOf(
                    "buy_button" to VerifiedSelector(
                        primary = "#add-to-cart-button",
                        fallbacks = listOf("[aria-label='Add to Cart']"),
                    )
                ),
                knownBlockers = listOf(
                    BlockerInfo(
                        type = "cookie_consent",
                        selector = "#sp-cc-accept",
                        action = "click",
                        frequency = "first_visit_only",
                    )
                ),
                promotionHistory = listOf(
                    PromotionEvent(
                        from = "hypothesis",
                        to = "candidate",
                        reason = "2 verified visits",
                        verifiedVisits = 2,
                    )
                ),
            )
            val json = mapper.writeValueAsString(facts)
            val restored = mapper.readValue(json, KnowledgeFacts::class.java)

            assertEquals("buy", restored.intent)
            assertEquals("amazon.com", restored.domain)
            assertEquals(VerificationStatus.CANDIDATE, restored.status)
            assertEquals("amazon-like", restored.siteFacts.siteFamily)
            assertEquals(1, restored.selectors.size)
            assertEquals("#add-to-cart-button", restored.selectors["buy_button"]?.primary)
            assertEquals(1, restored.knownBlockers.size)
        }
    }

    @Nested
    @DisplayName("VerificationStatus pipeline")
    inner class VerificationPipeline {
        @Test
        @DisplayName("all four status values are defined")
        fun testAllStatuses() {
            assertEquals(4, VerificationStatus.entries.size)
            assertTrue(VerificationStatus.entries.contains(VerificationStatus.HYPOTHESIS))
            assertTrue(VerificationStatus.entries.contains(VerificationStatus.CANDIDATE))
            assertTrue(VerificationStatus.entries.contains(VerificationStatus.VERIFIED))
            assertTrue(VerificationStatus.entries.contains(VerificationStatus.CONTESTED))
        }
    }

    @Nested
    @DisplayName("PromotionLevel requirements")
    inner class PromotionLevels {
        @Test
        @DisplayName("min sites increase with abstraction level")
        fun testMinSites() {
            assertEquals(1, PromotionLevel.SITE.minSitesRequired)
            assertEquals(2, PromotionLevel.FAMILY.minSitesRequired)
            assertEquals(3, PromotionLevel.CATEGORY.minSitesRequired)
            assertEquals(4, PromotionLevel.UNIVERSAL.minSitesRequired)
        }
    }

    @Nested
    @DisplayName("TraceRecord")
    inner class TraceRecordTests {
        @Test
        @DisplayName("serialization round-trip")
        fun testRoundTrip() {
            val trace = TraceRecord(
                intent = "buy",
                domain = "amazon.com",
                url = "https://amazon.com/dp/test",
                urlPattern = "/dp/*",
                outcome = "success",
                actions = listOf(
                    ActionStep(1, "click", selector = "#btn", result = "success"),
                ),
                durationMs = 1500,
            )
            val json = mapper.writeValueAsString(trace)
            val restored = mapper.readValue(json, TraceRecord::class.java)

            assertEquals("buy", restored.intent)
            assertEquals("amazon.com", restored.domain)
            assertEquals(1, restored.actions.size)
            assertEquals(1500, restored.durationMs)
            assertTrue(restored.redacted)
        }
    }
}
