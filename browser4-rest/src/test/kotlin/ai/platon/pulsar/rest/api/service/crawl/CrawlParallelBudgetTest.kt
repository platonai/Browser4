package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/**
 * Tests for the parallelism budget a crawl runs under: how a request's
 * `parallelTabs` is resolved and clamped, that it survives the per-seed request
 * copy, and that the reported budget/peak travel over the wire.
 *
 * The budget is what decides whether a crawl collects one unit at a time or
 * several at once, so its contract is pinned here rather than inferred from a
 * browser test.
 */
class CrawlParallelBudgetTest {

    @Mock
    private lateinit var sessionManager: PulsarSessionManager

    private lateinit var crawlService: CrawlService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        crawlService = CrawlService(sessionManager)
    }

    @AfterEach
    fun tearDown() {
        runCatching { crawlService.shutdown() }
    }

    // ------------------------------------------------------------------
    // resolveParallelTabs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an omitted budget falls back to the server default")
    fun testOmittedBudgetUsesServerDefault() {
        assertEquals(
            CrawlService.DEFAULT_PARALLEL_TABS,
            crawlService.resolveParallelTabs(CrawlRequest(url = "https://example.com", depth = 0))
        )
    }

    @Test
    @DisplayName("an explicit budget is honored as long as it is within the ceiling")
    fun testExplicitBudgetIsHonored() {
        assertEquals(
            2,
            crawlService.resolveParallelTabs(
                CrawlRequest(url = "https://example.com", depth = 0, parallelTabs = 2)
            )
        )
        assertEquals(
            CrawlService.MAX_PARALLEL_TABS,
            crawlService.resolveParallelTabs(
                CrawlRequest(
                    url = "https://example.com",
                    depth = 0,
                    parallelTabs = CrawlService.MAX_PARALLEL_TABS
                )
            )
        )
    }

    @Test
    @DisplayName("a budget above the ceiling is clamped, never handed through")
    fun testBudgetAboveCeilingIsClamped() {
        // The ceiling is what stops a typo from asking the server to open
        // hundreds of browser tabs; the reported budget is the clamped one, so
        // the caller can see what it actually got.
        assertEquals(
            CrawlService.MAX_PARALLEL_TABS,
            crawlService.resolveParallelTabs(
                CrawlRequest(url = "https://example.com", depth = 0, parallelTabs = 500)
            )
        )
    }

    @Test
    @DisplayName("a non-positive budget falls back to the default instead of serializing the crawl")
    fun testNonPositiveBudgetFallsBackToDefault() {
        for (requested in listOf(0, -1, -100)) {
            assertEquals(
                CrawlService.DEFAULT_PARALLEL_TABS,
                crawlService.resolveParallelTabs(
                    CrawlRequest(url = "https://example.com", depth = 0, parallelTabs = requested)
                ),
                "parallelTabs=$requested must fall back to the default, not serialize the crawl"
            )
        }
    }

    @Test
    @DisplayName("a configured server default applies to crawls that do not ask for a budget")
    fun testConfiguredServerDefaultApplies() {
        crawlService.defaultParallelTabs = 7

        assertEquals(
            7,
            crawlService.resolveParallelTabs(CrawlRequest(url = "https://example.com", depth = 0))
        )
        // An explicit request still wins over the configured default.
        assertEquals(
            2,
            crawlService.resolveParallelTabs(
                CrawlRequest(url = "https://example.com", depth = 0, parallelTabs = 2)
            )
        )
    }

    @Test
    @DisplayName("the default budget and the ceiling cannot be inverted by configuration")
    fun testDefaultBudgetCannotExceedTheCeiling() {
        // A server default above the ceiling must still be clamped, or the
        // ceiling would only apply to callers that ask explicitly.
        crawlService.defaultParallelTabs = CrawlService.MAX_PARALLEL_TABS * 10

        assertEquals(
            CrawlService.MAX_PARALLEL_TABS,
            crawlService.resolveParallelTabs(CrawlRequest(url = "https://example.com", depth = 0))
        )
    }

    // ------------------------------------------------------------------
    // CrawlRequest.parallelTabs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("parallelTabs is absent by default so the server default applies")
    fun testParallelTabsDefaultsToNull() {
        assertNull(CrawlRequest(url = "https://example.com", depth = 0).parallelTabs)
    }

    @Test
    @DisplayName("parallelTabs survives the per-seed request copy")
    fun testParallelTabsSurvivesPerSeedCopy() {
        // submit() derives one request per seed with `request.copy(url = seedUrl,
        // urls = null)`; losing the budget there would silently serialize every
        // multi-seed crawl while the response still reported the budget.
        val request = CrawlRequest(url = "https://example.com", depth = 1, parallelTabs = 6)

        val perSeed = request.copy(url = "https://example.com/product/1", urls = null)

        assertEquals(6, perSeed.parallelTabs)
        assertEquals(1, perSeed.depth)
    }

    // The `parallelTabs` *wire name* is deliberately not asserted here.
    // CrawlRequest declares a primary-constructor @JsonCreator, and Kotlin copies
    // that annotation onto the synthetic no-arg constructor it emits for an
    // all-default parameter list; a Jackson mapper without Spring's Kotlin setup
    // therefore refuses the class ("Conflicting property-based creators") for
    // reading *and* writing.  That is a pre-existing property of the DTO (the
    // no-arg constructor exists for any all-default parameter list), and the name
    // is pinned on both ends that actually matter instead:
    //   * the CLI side, by build_crawl_server_params_translates_parallel_to_parallel_tabs;
    //   * the server side, by CrawlParallelTabsTest, which POSTs the literal
    //     `"parallelTabs"` field over HTTP and reads the applied budget back.

    // ------------------------------------------------------------------
    // CrawlResponse — the reported parallelism
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a response without parallelism fields defaults to 0, not to a fake budget")
    fun testResponseWithoutParallelismFieldsDefaultsToZero() {
        // 0 means "unknown / not reported" so an older persisted task record can
        // never be mistaken for a crawl that ran with a budget of zero tabs.
        val response = CrawlResponse(taskId = "t-legacy")

        assertEquals(0, response.parallelTabs)
        assertEquals(0, response.maxConcurrentFetches)
    }

    @Test
    @DisplayName("the reported parallelism round-trips through the wire format")
    fun testResponseParallelismRoundTrips() {
        val mapper = pulsarObjectMapper()
        val response = CrawlResponse(
            taskId = "t-parallel",
            status = "OK",
            pagesFound = 12,
            parallelTabs = 4,
            maxConcurrentFetches = 4
        )

        val restored = mapper.readValue(mapper.writeValueAsString(response), CrawlResponse::class.java)

        assertEquals(4, restored.parallelTabs)
        assertEquals(4, restored.maxConcurrentFetches)
        assertEquals(12, restored.pagesFound)
    }

    @Test
    @DisplayName("a peak below the budget is preserved, so a starved crawl is visible")
    fun testPeakBelowBudgetIsPreserved() {
        // The driver pool can hand out fewer tabs than the budget asked for; the
        // gap between budget and peak is the only evidence of that, so both
        // numbers have to survive serialization.
        val mapper = pulsarObjectMapper()
        val response = CrawlResponse(taskId = "t-starved", parallelTabs = 8, maxConcurrentFetches = 2)

        val restored = mapper.readValue(mapper.writeValueAsString(response), CrawlResponse::class.java)

        assertEquals(8, restored.parallelTabs)
        assertEquals(2, restored.maxConcurrentFetches)
    }

    @Test
    @DisplayName("a partial (timed-out) response still carries the parallelism it ran under")
    fun testTimedOutResponseCarriesParallelism() {
        val mapper = pulsarObjectMapper()
        val response = CrawlResponse(
            taskId = "t-timeout",
            status = CrawlStatus.REQUEST_TIMEOUT,
            error = "Crawl timed out while processing seeds",
            pagesFound = 3,
            parallelTabs = 4,
            maxConcurrentFetches = 3
        )

        val restored = mapper.readValue(mapper.writeValueAsString(response), CrawlResponse::class.java)

        assertEquals(CrawlStatus.REQUEST_TIMEOUT, restored.status)
        assertEquals(4, restored.parallelTabs)
        assertEquals(3, restored.maxConcurrentFetches)
        assertNotNull(restored.error)
    }
}
