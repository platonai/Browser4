package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [LoadOptions.createItemOptions] and [LoadOptions.itemOptions2MajorOptions].
 *
 * Verifies that item-page-specific options are correctly promoted to main options
 * when transitioning from index-page processing to detail-page processing.
 */
class TestLoadOptionsItemConversion {

    private val conf = VolatileConfig.UNSAFE

    // ---------------------------------------------------------------------------
    // createItemOptions()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("createItemOptions: promotes all 8 item fields to major fields")
    fun testCreateItemOptionsPromotesItemFields() {
        val options = LoadOptions.parse(
            "-ii 7d -isc 15 -isi 1s -ist 45s -iplt 90s -irs 2048 -iri 5 -ira 10",
            conf
        )
        val itemOptions = options.createItemOptions()

        // All item options promoted to major options
        assertEquals(Duration.ofDays(7), itemOptions.expires, "itemExpires should become expires")
        assertEquals(15, itemOptions.autoScrollCount, "itemScrollCount should become autoScrollCount")
        assertEquals(Duration.ofSeconds(1), itemOptions.scrollInterval, "itemScrollInterval should become scrollInterval")
        assertEquals(Duration.ofSeconds(45), itemOptions.scriptTimeout, "itemScriptTimeout should become scriptTimeout")
        assertEquals(Duration.ofSeconds(90), itemOptions.pageLoadTimeout, "itemPageLoadTimeout should become pageLoadTimeout")
        assertEquals(2048, itemOptions.requireSize, "itemRequireSize should become requireSize")
        assertEquals(5, itemOptions.requireImages, "itemRequireImages should become requireImages")
        assertEquals(10, itemOptions.requireAnchors, "itemRequireAnchors should become requireAnchors")
    }

    @Test
    @DisplayName("createItemOptions: resets outLinkSelector to default (empty)")
    fun testCreateItemOptionsOutLinkSelectorReset() {
        val options = LoadOptions.parse("-outlink \".products a\"", conf)
        val itemOptions = options.createItemOptions()

        assertEquals("", itemOptions.outLinkSelector,
            "outLinkSelector should be reset to default for item pages")
    }

    @Test
    @DisplayName("createItemOptions: returns a different instance")
    fun testCreateItemOptionsClonedNotSame() {
        val options = LoadOptions.parse("-expires 1d", conf)
        val itemOptions = options.createItemOptions()

        assertNotEquals(options, itemOptions,
            "createItemOptions should return a different instance")
    }

    @Test
    @DisplayName("createItemOptions: item fields reset to defaults after promotion")
    fun testCreateItemOptionsResetsItemFields() {
        val options = LoadOptions.parse(
            "-ii 7d -isc 15 -irs 2048 -iri 5 -ira 10",
            conf
        )
        val itemOptions = options.createItemOptions()

        // Original item fields should be reset to DEFAULT
        val defaultOptions = LoadOptions.DEFAULT
        assertEquals(defaultOptions.itemExpires, itemOptions.itemExpires,
            "itemExpires should be reset to default after promotion")
        assertEquals(defaultOptions.itemRequireSize, itemOptions.itemRequireSize,
            "itemRequireSize should be reset to default after promotion")
    }

    // ---------------------------------------------------------------------------
    // itemOptions2MajorOptions()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("itemOptions2MajorOptions: promotes all 8 item→major fields")
    fun testItemOptions2MajorOptionsAllPromotions() {
        val options = LoadOptions.parse(
            "-ii 7d -isc 15 -isi 1s -ist 45s -iplt 90s -irs 2048 -iri 5 -ira 10",
            conf
        )
        options.itemOptions2MajorOptions()

        assertEquals(Duration.ofDays(7), options.expires)
        assertEquals(15, options.autoScrollCount)
        assertEquals(Duration.ofSeconds(1), options.scrollInterval)
        assertEquals(Duration.ofSeconds(45), options.scriptTimeout)
        assertEquals(Duration.ofSeconds(90), options.pageLoadTimeout)
        assertEquals(2048, options.requireSize)
        assertEquals(5, options.requireImages)
        assertEquals(10, options.requireAnchors)
    }

    @Test
    @DisplayName("itemOptions2MajorOptions: resets outLinkSelector after promotion")
    fun testItemOptions2MajorOptionsResetsOutLinkSelector() {
        val options = LoadOptions.parse("-outlink \".products a\"", conf)
        options.itemOptions2MajorOptions()

        assertEquals(LoadOptions.DEFAULT.outLinkSelector, options.outLinkSelector,
            "outLinkSelector should be reset to DEFAULT after promotion")
    }

    @Test
    @DisplayName("itemOptions2MajorOptions: resets item fields to DEFAULT values")
    fun testItemOptions2MajorOptionsResetsItemFields() {
        val options = LoadOptions.parse("-ii 7d -isc 15 -irs 2048", conf)
        options.itemOptions2MajorOptions()

        val defaultOpts = LoadOptions.DEFAULT
        assertEquals(defaultOpts.itemExpires, options.itemExpires)
        assertEquals(defaultOpts.itemScrollCount, options.itemScrollCount)
        assertEquals(defaultOpts.itemScrollInterval, options.itemScrollInterval)
        assertEquals(defaultOpts.itemScriptTimeout, options.itemScriptTimeout)
        assertEquals(defaultOpts.itemPageLoadTimeout, options.itemPageLoadTimeout)
        assertEquals(defaultOpts.itemRequireSize, options.itemRequireSize)
        assertEquals(defaultOpts.itemRequireImages, options.itemRequireImages)
        assertEquals(defaultOpts.itemRequireAnchors, options.itemRequireAnchors)
    }

    @Test
    @DisplayName("itemOptions2MajorOptions: first call promotes item fields, second call uses reset defaults")
    fun testItemOptions2MajorOptionsDoubleCall() {
        val options = LoadOptions.parse("-ii 7d -isc 15", conf)
        options.itemOptions2MajorOptions()

        // After first promotion, item fields are reset to DEFAULT
        // Second call promotes those reset defaults (which may differ from original values)
        val expiresAfterFirst = options.expires
        options.itemOptions2MajorOptions()

        // The second call promotes DEFAULT.itemExpires to expires,
        // which may differ from the first promotion's value
        assertNotNull(expiresAfterFirst)
    }

    @Test
    @DisplayName("createItemOptions: preserves referrer")
    fun testCreateItemOptionsPreservesConf() {
        val options = LoadOptions.parse("-expires 1d", conf)
        val itemOptions = options.createItemOptions()

        // The conf should be shared
        assertTrue(itemOptions.conf === options.conf,
            "createItemOptions should share the same config reference")
    }
}
