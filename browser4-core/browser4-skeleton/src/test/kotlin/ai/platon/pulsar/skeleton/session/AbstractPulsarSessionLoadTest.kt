package ai.platon.pulsar.skeleton.session

import ai.platon.pulsar.common.browser.InteractLevel
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.urls.PlainUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.event.PageEventHandlers
import ai.platon.pulsar.skeleton.workflow.common.CaffeineExpiringCache
import ai.platon.pulsar.skeleton.workflow.common.FetchEntry
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.MethodName
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for [AbstractPulsarSession.load] with variable [LoadOptions].
 *
 * Tests cover all decision gates in the cache/load pipeline:
 * 1. enablePDCache on/off
 * 2. options.readonly
 * 3. options.rawEvent (event handlers)
 * 4. options.refresh
 * 5. options.expires / expireAt (cache expiry)
 * 6. All option pass-through to context.load()
 */
@TestMethodOrder(MethodName::class)
@DisplayName("AbstractPulsarSession.load() with LoadOptions")
class AbstractPulsarSessionLoadTest {

    // ---------------------------------------------------------------------------
    // Test fixture
    // ---------------------------------------------------------------------------

    private val testUrl = "https://example.com/test"
    private val conf = VolatileConfig.UNSAFE

    private lateinit var mockContext: AbstractPulsarContext
    private lateinit var realGlobalCache: GlobalCache
    private lateinit var realGlobalCacheFactory: GlobalCacheFactory
    private lateinit var realPageCache: CaffeineExpiringCache<String, WebPage>
    private lateinit var mockWebPage: WebPage
    private lateinit var session: TestablePulsarSession

    /**
     * Minimal session subclass that bypasses the [normalize] config-check require(),
     * so unit tests can inject arbitrary LoadOptions without wiring a full VolatileConfig chain.
     */
    open class TestablePulsarSession(
        context: AbstractPulsarContext,
        sessionConfig: VolatileConfig,
    ) : BasicPulsarSession(context, sessionConfig) {
        override fun normalize(options: LoadOptions): LoadOptions = options
    }

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockWebPage = mockk(relaxed = true)

        // Create a standalone CaffeineExpiringCache that we fully control.
        realPageCache = CaffeineExpiringCache()

        // Use a GlobalCache subclass that returns OUR pageCache.
        // This guarantees the private pageCacheOrNull chain resolves to our cache.
        realGlobalCache = object : GlobalCache(ImmutableConfig()) {
            override val pageCache = realPageCache
        }

        realGlobalCacheFactory = GlobalCacheFactory(ImmutableConfig())
        GlobalCacheFactory.setGlobalCache(realGlobalCache)

        // Wire the context — stub all properties accessed during load()
        every { mockContext.isActive } returns true
        every { mockContext.globalCacheFactory } returns realGlobalCacheFactory
        every { mockContext.globalCache } returns realGlobalCache

        // Default: context.load() returns a mock page
        coEvery { mockContext.load(any<NormURL>()) } returns mockWebPage

        // For overload tests that call normalize() via the convenience overloads
        every { mockContext.normalize(any<String>(), any<LoadOptions>(), any()) } answers {
            val url = firstArg<String>()
            val options = secondArg<LoadOptions>()
            NormURL(url, options)
        }
        every { mockContext.normalize(any<UrlAware>(), any<LoadOptions>(), any()) } answers {
            val urlAware = firstArg<UrlAware>()
            val options = secondArg<LoadOptions>()
            NormURL(urlAware.url, options)
        }

        session = TestablePulsarSession(mockContext, conf)
    }

    @AfterEach
    fun tearDown() {
        realPageCache.clear()
        clearAllMocks()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Build LoadOptions with non-default values via an apply block. */
    private fun options(block: LoadOptions.() -> Unit = {}): LoadOptions =
        LoadOptions.create(conf).apply(block)

    /**
     * Create a GoraWebPage and put it in the test cache.
     * Returns the page, whose prevFetchTime is used to compute a non-expired expires duration.
     */
    private fun populateCache(): WebPage {
        val goraPage = FetchEntry.createPageShell(testUrl, conf)
        realPageCache.putDatum(testUrl, goraPage)
        return goraPage
    }

    /**
     * Compute an expires duration that ensures [page] is NOT expired.
     * GoraWebPage.prevFetchTime defaults to Instant.EPOCH (1970), so the default
     * DECADES (~10 years) is insufficient: EPOCH + 10 years = 1980 < 2026.
     * We compute the actual gap and add a safety margin.
     */
    private fun expiresForPage(page: WebPage): Duration =
        Duration.between(page.prevFetchTime, Instant.now()).plusDays(365)

    /** Build a NormURL with the given options. */
    private fun normURL(opts: LoadOptions): NormURL =
        NormURL(testUrl, opts)

    /** Execute load synchronously. */
    private fun doLoad(url: NormURL): WebPage = runBlocking { session.load(url) }

    /** Execute load with a string overload. */
    private fun doLoad(url: String): WebPage = runBlocking { session.load(url) }

    /** Verify context.load() was called exactly N times. */
    private fun verifyLoadCalled(times: Int = 1) {
        coVerify(exactly = times) { mockContext.load(any<NormURL>()) }
    }

    /**
     * Capture the NormURL argument passed to context.load() and run assertions on it.
     * Returns the captured NormURL, or fails if context.load() was never called.
     */
    private fun captureLoadArg(): NormURL {
        val slot = slot<NormURL>()
        coVerify { mockContext.load(capture(slot)) }
        return slot.captured
    }

    // ---------------------------------------------------------------------------
    // 1. Overload delegation
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Overload delegation")
    inner class OverloadDelegation {

        @Test
        @DisplayName("load(url: String) delegates with default options")
        fun loadUrlStringDelegatesWithDefaultOptions() {
            doLoad(testUrl)
            val arg = captureLoadArg()
            assertEquals(testUrl, arg.urlString)
        }

        @Test
        @DisplayName("load(url: String, args: String) delegates with parsed options")
        fun loadUrlStringArgsDelegatesWithParsedOptions() {
            val label = "overload-test-2"
            val args = "-label $label -readonly"
            val page = runBlocking { session.load(testUrl, args) }
            val arg = captureLoadArg()
            assertEquals(testUrl, arg.urlString)
            assertEquals(label, arg.options.label)
            assertTrue(arg.options.readonly, "readonly should be set from args")
        }

        @Test
        @DisplayName("load(url: String, options: LoadOptions) delegates directly")
        fun loadUrlStringOptionsDelegatesDirectly() {
            val opts = options {
                label = "overload-test-3"
                readonly = true
            }
            val page = runBlocking { session.load(testUrl, opts) }
            val arg = captureLoadArg()
            assertEquals(testUrl, arg.urlString)
            assertEquals("overload-test-3", arg.options.label)
            assertTrue(arg.options.readonly)
        }

        @Test
        @DisplayName("load(url: UrlAware) delegates with default options")
        fun loadUrlAwareDelegatesWithDefaultOptions() {
            val urlAware = PlainUrl(testUrl)
            val page = runBlocking { session.load(urlAware) }
            val arg = captureLoadArg()
            assertEquals(testUrl, arg.urlString)
        }

        @Test
        @DisplayName("load(url: UrlAware, args: String) delegates with parsed options")
        fun loadUrlAwareArgsDelegatesWithParsedOptions() {
            val urlAware = PlainUrl(testUrl)
            val label = "overload-test-5"
            val page = runBlocking { session.load(urlAware, "-label $label -readonly") }
            val arg = captureLoadArg()
            assertEquals(testUrl, arg.urlString)
            assertEquals(label, arg.options.label)
            assertTrue(arg.options.readonly)
        }

        @Test
        @DisplayName("load(url: UrlAware, options: LoadOptions) delegates directly")
        fun loadUrlAwareOptionsDelegatesDirectly() {
            val urlAware = PlainUrl(testUrl)
            val opts = options {
                label = "overload-test-6"
                readonly = true
            }
            val page = runBlocking { session.load(urlAware, opts) }
            val arg = captureLoadArg()
            assertEquals(testUrl, arg.urlString)
            assertEquals("overload-test-6", arg.options.label)
            assertTrue(arg.options.readonly)
        }
    }

    // ---------------------------------------------------------------------------
    // 2. Cache disabled (enablePDCache = false)
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Cache disabled (enablePDCache = false)")
    inner class CacheDisabled {

        @BeforeEach
        fun disableCache() {
            session.disablePDCache()
        }

        @Test
        @DisplayName("bypasses cache even when readonly=true")
        fun bypassesCacheWhenReadonlyTrue() {
            val opts = options { readonly = true }
            doLoad(normURL(opts))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("bypasses cache even with event handlers")
        fun bypassesCacheWithEventHandlers() {
            val mockEventHandlers = mockk<PageEventHandlers>(relaxed = true)
            val opts = options { readonly = true }
            // Must use the constructor that accepts rawEvent since the property has a custom getter
            val optsWithEvent = LoadOptions(
                arrayOf(),
                conf,
                rawEvent = mockEventHandlers,
            ).apply {
                readonly = true
                parse()
            }
            doLoad(normURL(optsWithEvent))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("bypasses cache even when refresh=true")
        fun bypassesCacheWhenRefreshTrue() {
            val opts = options { readonly = true; refresh = true }
            doLoad(normURL(opts))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("bypasses cache even when page is in cache")
        fun bypassesPopulatedCache() {
            val goraPage = populateCache()

            val opts = options {
                readonly = true
                expires = expiresForPage(goraPage)
            }
            doLoad(normURL(opts))
            verifyLoadCalled()
        }
    }

    // ---------------------------------------------------------------------------
    // 3. Readonly control
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Readonly control")
    inner class ReadonlyControl {

        @Test
        @DisplayName("readonly=false calls context.load (cache bypass)")
        fun readonlyFalseBypassesCache() {
            val opts = options { readonly = false }
            doLoad(normURL(opts))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("readonly=true allows cache lookup (cache miss falls through)")
        fun readonlyTrueAllowsCacheLookup() {
            // Cache is empty, so it will be a cache miss → context.load() called
            val opts = options { readonly = true }
            doLoad(normURL(opts))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("readonly=false bypasses cache even when page is in cache")
        fun readonlyFalseBypassesPopulatedCache() {
            val goraPage = populateCache()

            val opts = options { readonly = false }
            doLoad(normURL(opts))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("readonly=true + cached page → cache hit")
        fun readonlyTrueWithCachedPageCacheHit() {
            val goraPage = populateCache()

            val opts = options {
                readonly = true
                refresh = false
                expires = expiresForPage(goraPage)
            }
            val result = doLoad(normURL(opts))
            assertTrue(result.isCached, "Page should be loaded from cache with readonly=true")
        }
    }

    // ---------------------------------------------------------------------------
    // 4. Event handler bypass
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Event handler bypass")
    inner class EventHandlerBypass {

        @Test
        @DisplayName("rawEvent=null allows cache lookup")
        fun rawEventNullAllowsCache() {
            val opts = options { readonly = true }
            // rawEvent defaults to null
            doLoad(normURL(opts))
            verifyLoadCalled() // cache miss (empty cache)
        }

        @Test
        @DisplayName("rawEvent!=null bypasses cache")
        fun rawEventNonNullBypassesCache() {
            val mockEventHandlers = mockk<PageEventHandlers>(relaxed = true)
            val optsWithEvent = LoadOptions(
                arrayOf(),
                conf,
                rawEvent = mockEventHandlers,
            ).apply {
                readonly = true
                parse()
            }
            doLoad(normURL(optsWithEvent))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("rawEvent!=null bypasses cache even when page is in cache")
        fun rawEventNonNullBypassesPopulatedCache() {
            val goraPage = populateCache()

            val mockEventHandlers = mockk<PageEventHandlers>(relaxed = true)
            val optsWithEvent = LoadOptions(
                arrayOf(),
                conf,
                rawEvent = mockEventHandlers,
            ).apply {
                readonly = true
                // Use an expires that would allow a cache hit — but rawEvent overrides it
                expires = expiresForPage(goraPage)
                parse()
            }
            doLoad(normURL(optsWithEvent))
            verifyLoadCalled()
        }
    }

    // ---------------------------------------------------------------------------
    // 5. Refresh control
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Refresh control")
    inner class RefreshControl {

        @Test
        @DisplayName("refresh=false with cached page → cache hit")
        fun refreshFalseCacheHit() {
            val goraPage = populateCache()

            val opts = options {
                readonly = true
                refresh = false
                expires = expiresForPage(goraPage)
            }
            val result = doLoad(normURL(opts))
            assertTrue(result.isCached, "Page should be loaded from cache when refresh=false and page is cached")
        }

        @Test
        @DisplayName("refresh=true bypasses cache even with valid page in cache")
        fun refreshTrueBypassesCache() {
            val goraPage = populateCache()

            val opts = options {
                readonly = true
                refresh = true
                expires = expiresForPage(goraPage)
            }
            doLoad(normURL(opts))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("refresh=true sets expires=0, expireAt=epoch, ignoreFailure=true")
        fun refreshTrueSideEffects() {
            val opts = options { refresh = true }

            assertEquals(Duration.ZERO, opts.expires, "refresh should set expires to zero")
            assertEquals(Instant.ofEpochSecond(0), opts.expireAt, "refresh should set expireAt to epoch")
            assertEquals(Duration.ZERO, opts.itemExpires, "refresh should set itemExpires to zero")
            assertEquals(Instant.ofEpochSecond(0), opts.itemExpireAt, "refresh should set itemExpireAt to epoch")
            assertTrue(opts.ignoreFailure, "refresh should enable ignoreFailure")
            assertTrue(opts.refresh, "refresh flag should be true")
        }
    }

    // ---------------------------------------------------------------------------
    // 6. Cache expiry (expires / expireAt)
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Cache expiry")
    inner class CacheExpiry {

        @Test
        @DisplayName("default expires is DECADES (far future)")
        fun defaultExpiresIsDecades() {
            val opts = options { readonly = true }
            assertTrue(
                opts.expires >= Duration.ofDays(365 * 5),
                "Default expires should be approximately a decade or more"
            )
        }

        @Test
        @DisplayName("expires is propagated correctly")
        fun expiresPropagatedCorrectly() {
            val opts = options {
                readonly = true
                expires = Duration.ofHours(6)
            }
            assertEquals(Duration.ofHours(6), opts.expires)
        }

        @Test
        @DisplayName("expires=0s forces immediate expiry → cache miss")
        fun expiresZeroForcesImmediateExpiry() {
            val goraPage = populateCache()

            val opts = options {
                readonly = true
                expires = Duration.ZERO
            }
            // expires=0s means any page is immediately expired → cache miss → calls context.load()
            doLoad(normURL(opts))
            verifyLoadCalled()
        }

        @Test
        @DisplayName("expires covering prevFetch window → cache hit")
        fun expiresCoveringPrevFetchCacheHit() {
            val goraPage = populateCache()

            val opts = options {
                readonly = true
                expires = expiresForPage(goraPage)
            }
            val result = doLoad(normURL(opts))
            assertTrue(result.isCached, "Page should be loaded from cache when not expired")
        }

        @Test
        @DisplayName("expireAt in future → not expired")
        fun expireAtFutureNotExpired() {
            val now = Instant.now()
            val opts = options {
                readonly = true
                this.expireAt = now.plusSeconds(3600) // 1 hour in future
                this.expires = Duration.ofDays(365 * 57) // cover EPOCH-based prevFetchTime
            }
            assertFalse(opts.isExpired(Instant.EPOCH), "expireAt in future should not expire EPOCH-based page")
        }

        @Test
        @DisplayName("isExpired full logic: refresh, expireAt range, expires duration")
        fun isExpiredLogicCoverage() {
            val now = Instant.now()
            val recentPrevFetch = now.minusSeconds(60)

            // refresh always true
            val refreshOpts = options { refresh = true }
            assertTrue(refreshOpts.isExpired(recentPrevFetch), "refresh=true should always expire")

            // expireAt in range: expireAt between prevFetchTime and now
            val expireAtInRange = options {
                this.expireAt = now.minusSeconds(30) // 30s ago
                this.expires = Duration.ofDays(365 * 57)
                this.refresh = false
            }
            assertTrue(
                expireAtInRange.isExpired(now.minusSeconds(60)),
                "expireAt(30s ago) in [prevFetch(60s ago), now] → expired"
            )

            // expiry by duration
            val expiresSoon = options {
                expires = Duration.ofSeconds(1)
                refresh = false
            }
            assertTrue(
                expiresSoon.isExpired(now.minusSeconds(10)),
                "now >= prevFetch(10s ago) + expires(1s) → expired"
            )

            // not expired
            val notExpired = options {
                expires = Duration.ofDays(365 * 57)
                refresh = false
            }
            assertFalse(
                notExpired.isExpired(Instant.EPOCH),
                "now < EPOCH + expires(57y) → not expired"
            )
        }
    }

    // ---------------------------------------------------------------------------
    // 7. Options pass-through
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Options pass-through to context.load()")
    inner class OptionsPassThrough {

        @Test
        @DisplayName("Interaction options")
        fun interactionOptions() {
            val opts = options {
                interactLevel = InteractLevel.GOOD_DATA
                autoScrollCount = 10
                scrollInterval = Duration.ofSeconds(2)
                scriptTimeout = Duration.ofSeconds(30)
                pageLoadTimeout = Duration.ofSeconds(60)
            }
            val arg = captureOptionsFromLoad(opts)
            assertEquals(InteractLevel.GOOD_DATA, arg.options.interactLevel)
            assertEquals(10, arg.options.autoScrollCount)
            assertEquals(Duration.ofSeconds(2), arg.options.scrollInterval)
            assertEquals(Duration.ofSeconds(30), arg.options.scriptTimeout)
            assertEquals(Duration.ofSeconds(60), arg.options.pageLoadTimeout)
        }

        @Test
        @DisplayName("Content requirement options")
        fun contentRequirementOptions() {
            val opts = options {
                requireSize = 10000
                requireImages = 3
                requireAnchors = 5
                requireNotBlank = ".content"
            }
            val arg = captureOptionsFromLoad(opts)
            assertEquals(10000, arg.options.requireSize)
            assertEquals(3, arg.options.requireImages)
            assertEquals(5, arg.options.requireAnchors)
            assertEquals(".content", arg.options.requireNotBlank)
        }

        @Test
        @DisplayName("Link extraction options")
        fun linkExtractionOptions() {
            val opts = options {
                outLinkSelector = "a.link"
                outLinkPattern = "/product/.*"
                topLinks = 50
            }
            val arg = captureOptionsFromLoad(opts)
            assertEquals("a.link", arg.options.outLinkSelector)
            assertEquals("/product/.*", arg.options.outLinkPattern)
            assertEquals(50, arg.options.topLinks)
        }

        @Test
        @DisplayName("Metadata options")
        fun metadataOptions() {
            val taskTime = Instant.parse("2024-01-01T00:00:00Z")
            val deadline = Instant.parse("2025-01-01T00:00:00Z")
            val opts = options {
                entity = "product"
                label = "test-label"
                taskId = "task-001"
                authToken = "secret-token"
                this.taskTime = taskTime
                this.deadline = deadline
                priority = -1000
            }
            val arg = captureOptionsFromLoad(opts)
            assertEquals("product", arg.options.entity)
            assertEquals("test-label", arg.options.label)
            assertEquals("task-001", arg.options.taskId)
            assertEquals("secret-token", arg.options.authToken)
            assertEquals(taskTime, arg.options.taskTime)
            assertEquals(deadline, arg.options.deadline)
            assertEquals(-1000, arg.options.priority)
        }

        @Test
        @DisplayName("Feature flag options")
        fun featureFlagOptions() {
            val opts = options {
                parse = true
                isResource = true
                incognito = true
                ignoreUrlQuery = true
                noNorm = true
                test = 2
            }
            val arg = captureOptionsFromLoad(opts)
            assertTrue(arg.options.parse, "parse should be true")
            assertTrue(arg.options.isResource, "isResource should be true")
            assertTrue(arg.options.incognito, "incognito should be true")
            assertTrue(arg.options.ignoreUrlQuery, "ignoreUrlQuery should be true")
            assertTrue(arg.options.noNorm, "noNorm should be true")
            assertEquals(2, arg.options.test)
        }

        @Test
        @DisplayName("Persistence options")
        fun persistenceOptions() {
            val opts = options {
                persist = false
                storeContent = false
                dropContent = true
                lazyFlush = true
            }
            val arg = captureOptionsFromLoad(opts)
            assertFalse(arg.options.persist, "persist should be false")
            assertFalse(arg.options.storeContent, "storeContent should be false")
            assertTrue(arg.options.dropContent, "dropContent should be true")
            assertTrue(arg.options.lazyFlush, "lazyFlush should be true")
        }

        @Test
        @DisplayName("Retry options")
        fun retryOptions() {
            val opts = options {
                nMaxRetry = 5
                nJitRetry = 3
                ignoreFailure = true
            }
            val arg = captureOptionsFromLoad(opts)
            assertEquals(5, arg.options.nMaxRetry)
            assertEquals(3, arg.options.nJitRetry)
            assertTrue(arg.options.ignoreFailure, "ignoreFailure should be true")
        }

        @Test
        @DisplayName("Bulk pass-through: all option categories")
        fun bulkPassThrough() {
            val taskTime = Instant.parse("2024-06-01T00:00:00Z")
            val deadline = Instant.parse("2025-06-01T00:00:00Z")
            val opts = options {
                // Interaction
                interactLevel = InteractLevel.BEST_DATA
                autoScrollCount = 20
                scrollInterval = Duration.ofMillis(1500)
                scriptTimeout = Duration.ofSeconds(45)
                pageLoadTimeout = Duration.ofSeconds(90)
                // Content requirements
                requireSize = 50000
                requireImages = 5
                requireAnchors = 10
                requireNotBlank = ".main-content"
                // Link extraction
                outLinkSelector = ".products a"
                outLinkPattern = "/detail/.*"
                topLinks = 100
                // Metadata
                entity = "article"
                label = "bulk-test"
                taskId = "bulk-task-001"
                authToken = "bulk-token"
                this.taskTime = taskTime
                this.deadline = deadline
                priority = -500
                // Feature flags
                parse = true
                isResource = false
                incognito = true
                ignoreUrlQuery = true
                noNorm = false
                test = 1
                // Persistence
                persist = false
                storeContent = true
                dropContent = false
                lazyFlush = false
                // Retry
                nMaxRetry = 7
                nJitRetry = 2
                ignoreFailure = true
                // Cache
                readonly = true
                // Ensure refresh doesn't override our values
                this.refresh = false
            }
            val arg = captureOptionsFromLoad(opts)

            // Spot-check representative options across all categories
            assertEquals(InteractLevel.BEST_DATA, arg.options.interactLevel)
            assertEquals(20, arg.options.autoScrollCount)
            assertEquals(50000, arg.options.requireSize)
            assertEquals(".products a", arg.options.outLinkSelector)
            assertEquals("article", arg.options.entity)
            assertEquals("bulk-test", arg.options.label)
            assertTrue(arg.options.parse, "parse should be true")
            assertTrue(arg.options.incognito, "incognito should be true")
            assertFalse(arg.options.persist, "persist should be false")
            assertTrue(arg.options.storeContent, "storeContent should be true")
            assertEquals(7, arg.options.nMaxRetry)
            assertEquals(2, arg.options.nJitRetry)
            assertTrue(arg.options.readonly, "readonly should be true")
            assertEquals(-500, arg.options.priority)
        }

        // --- helper ---

        private fun captureOptionsFromLoad(opts: LoadOptions): NormURL {
            doLoad(normURL(opts))
            return captureLoadArg()
        }
    }
}
