package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for LoadOptions instance lifecycle methods:
 * [LoadOptions.parserEngaged], [LoadOptions.isExpired], [LoadOptions.isDead],
 * [LoadOptions.doRefresh] side effects, [LoadOptions.correctOutLinkSelector].
 */
class TestLoadOptionsLifecycle {

    private val conf = VolatileConfig.UNSAFE

    // LoadOptionDefaults isolation
    private lateinit var savedExpires: Duration
    private lateinit var savedExpireAt: Instant
    private var savedIgnoreFailure = false

    @BeforeEach
    fun saveDefaults() {
        savedExpires = LoadOptionDefaults.expires
        savedExpireAt = LoadOptionDefaults.expireAt
        savedIgnoreFailure = LoadOptionDefaults.ignoreFailure
    }

    @AfterEach
    fun restoreDefaults() {
        LoadOptionDefaults.expires = savedExpires
        LoadOptionDefaults.expireAt = savedExpireAt
        LoadOptionDefaults.ignoreFailure = savedIgnoreFailure
    }

    // ---------------------------------------------------------------------------
    // parserEngaged()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("parserEngaged: false when neither parse nor requireNotBlank set")
    fun testParserEngagedNeither() {
        val options = LoadOptions.create(conf)
        assertFalse(options.parserEngaged(), "parserEngaged should be false when neither parse nor requireNotBlank is set")
    }

    @Test
    @DisplayName("parserEngaged: true when parse is enabled")
    fun testParserEngagedWithParse() {
        val options = LoadOptions.parse("-parse", conf)
        assertTrue(options.parserEngaged(), "parserEngaged should be true when parse is set")
    }

    @Test
    @DisplayName("parserEngaged: true when requireNotBlank is set")
    fun testParserEngagedWithRequireNotBlank() {
        val options = LoadOptions.parse("-requireNotBlank .content", conf)
        assertTrue(options.parserEngaged(), "parserEngaged should be true when requireNotBlank is set")
    }

    @Test
    @DisplayName("parserEngaged: true when both parse and requireNotBlank are set")
    fun testParserEngagedWithBoth() {
        val options = LoadOptions.parse("-parse -requireNotBlank .content", conf)
        assertTrue(options.parserEngaged(), "parserEngaged should be true when both are set")
    }

    // ---------------------------------------------------------------------------
    // isExpired()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("isExpired: true when refresh is set regardless of prevFetchTime")
    fun testIsExpiredWithRefresh() {
        val options = LoadOptions.parse("-refresh", conf)
        // refresh=true means always expired regardless of prevFetchTime
        assertTrue(options.isExpired(Instant.EPOCH), "Should be expired when refresh is set")
        assertTrue(options.isExpired(Instant.now()), "Should be expired when refresh is set even if just fetched")
    }

    @Test
    @DisplayName("isExpired: true when expireAt is between prevFetchTime and now")
    fun testIsExpiredExpireAtInRange() {
        val options = LoadOptions.parse("-expireAt ${Instant.now().plusSeconds(60)}", conf)
        // prevFetchTime is epoch (long ago), expireAt is in future but past now
        // But here expireAt is in the future (> now), so this should be false
        // Let's test with expireAt in past
        val pastExpireAt = Instant.now().minusSeconds(3600)
        val options2 = LoadOptions.parse("-expireAt $pastExpireAt", conf)
        assertTrue(options2.isExpired(Instant.now().minusSeconds(7200)),
            "Should be expired when expireAt is between prevFetch and now")
    }

    @Test
    @DisplayName("isExpired: true when expires duration has passed")
    fun testIsExpiredExpiresDuration() {
        val options = LoadOptions.parse("-expires 1s", conf)
        val prevFetch = Instant.now().minusSeconds(10)
        assertTrue(options.isExpired(prevFetch), "Should be expired when duration has passed")
    }

    @Test
    @DisplayName("isExpired: false when cached page is still fresh")
    fun testIsExpiredNotExpired() {
        val options = LoadOptions.parse("-expires 1d", conf)
        val prevFetch = Instant.now().minusSeconds(60) // just 1 minute ago
        assertFalse(options.isExpired(prevFetch), "Should NOT be expired when within expiry window")
    }

    @Test
    @DisplayName("isExpired: false when expireAt is before prevFetchTime (already fetched after expireAt)")
    fun testIsExpiredExpireAtBeforePrevFetch() {
        val pastExpireAt = Instant.now().minusSeconds(7200)
        val options = LoadOptions.parse("-expireAt $pastExpireAt", conf)
        val prevFetch = Instant.now().minusSeconds(3600) // fetched after expireAt
        assertFalse(options.isExpired(prevFetch),
            "Not expired: page was fetched after expireAt")
    }

    // ---------------------------------------------------------------------------
    // isDead()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("isDead: false when deadline is in the future")
    fun testIsDeadFuture() {
        val futureDeadline = Instant.now().plusSeconds(3600)
        val options = LoadOptions.parse("-deadline $futureDeadline", conf)
        assertFalse(options.isDead(), "Should NOT be dead when deadline is in future")
    }

    @Test
    @DisplayName("isDead: true when deadline is in the past")
    fun testIsDeadPast() {
        val pastDeadline = Instant.now().minusSeconds(3600)
        val options = LoadOptions.parse("-deadline $pastDeadline", conf)
        assertTrue(options.isDead(), "Should be dead when deadline is in past")
    }

    @Test
    @DisplayName("isDead: false at doomsday (default)")
    fun testIsDeadAtDoomsday() {
        val options = LoadOptions.create(conf)
        // Default deadline is DateTimes.doomsday — far in the future
        assertFalse(options.isDead(), "Should NOT be dead at default doomsday deadline")
    }

    // ---------------------------------------------------------------------------
    // doRefresh() — tested via refresh setter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("refresh=true: sets expires=0s, expireAt=epoch, ignoreFailure=true")
    fun testRefreshTrueSideEffects() {
        val options = LoadOptions.create(conf)
        options.refresh = true

        assertEquals(Duration.ZERO, options.expires, "refresh should set expires to zero")
        assertEquals(Instant.ofEpochSecond(0), options.expireAt, "refresh should set expireAt to epoch")
        assertEquals(Duration.ZERO, options.itemExpires, "refresh should set itemExpires to zero")
        assertEquals(Instant.ofEpochSecond(0), options.itemExpireAt, "refresh should set itemExpireAt to epoch")
        assertTrue(options.ignoreFailure, "refresh should enable ignoreFailure")
        assertTrue(options.refresh, "refresh flag should be true")
    }

    @Test
    @DisplayName("refresh=false: does not modify other fields")
    fun testRefreshFalseKeepsValues() {
        val options = LoadOptions.parse("-expires 7d -ignoreFailure", conf)
        val originalExpires = options.expires
        val originalIgnoreFailure = options.ignoreFailure

        options.refresh = false

        assertEquals(originalExpires, options.expires, "refresh=false should not change expires")
        assertEquals(originalIgnoreFailure, options.ignoreFailure, "refresh=false should not change ignoreFailure")
        assertFalse(options.refresh, "refresh flag should be false")
    }

    // ---------------------------------------------------------------------------
    // correctOutLinkSelector() — tested via parse which calls it
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("correctOutLinkSelector: strips surrounding quotes")
    fun testCorrectOutLinkSelectorTrimsQuotes() {
        val options = LoadOptions.parse("-outlink \".products a\"", conf)
        assertEquals(".products a", options.outLinkSelector,
            "Outer quotes should be stripped")
    }

    @Test
    @DisplayName("correctOutLinkSelector: appends 'a' tag when missing")
    fun testCorrectOutLinkSelectorAppendsA() {
        val options = LoadOptions.parse("-outlink \".products\"", conf)
        assertTrue(options.outLinkSelector.contains("a"),
            "Selector should have 'a' tag appended")
    }

    @Test
    @DisplayName("correctOutLinkSelector: blank produces empty string")
    fun testCorrectOutLinkSelectorBlank() {
        val options = LoadOptions.parse("-outlink \"\"", conf)
        assertEquals("", options.outLinkSelector,
            "Blank selector after trim should result in empty string")
    }

    @Test
    @DisplayName("correctOutLinkSelector: already has 'a' tag — unchanged")
    fun testCorrectOutLinkSelectorAlreadyHasA() {
        val options = LoadOptions.parse("-outlink \"div a\"", conf)
        assertTrue(options.outLinkSelector.contains("div"),
            "Selector with 'a' should keep original structure")
    }

    @Test
    @DisplayName("outLinkSelectorOrNull: null when empty")
    fun testOutLinkSelectorOrNullWhenEmpty() {
        val options = LoadOptions.create(conf)
        assertEquals(null, options.outLinkSelectorOrNull,
            "outLinkSelectorOrNull should be null when outLinkSelector is blank")
    }

    @Test
    @DisplayName("outLinkSelectorOrNull: non-null when set")
    fun testOutLinkSelectorOrNullWhenSet() {
        val options = LoadOptions.parse("-outlink \".products a\"", conf)
        assertTrue(options.outLinkSelectorOrNull != null,
            "outLinkSelectorOrNull should not be null when outLinkSelector is set")
    }
}
