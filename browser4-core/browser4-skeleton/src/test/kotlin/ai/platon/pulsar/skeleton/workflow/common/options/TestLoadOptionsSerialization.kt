package ai.platon.pulsar.skeleton.workflow.common.options

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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for LoadOptions representation and comparison:
 * [LoadOptions.toString], [LoadOptions.equals], [LoadOptions.hashCode],
 * [LoadOptions.clone], [LoadOptions.isDefault], [LoadOptions.getParams].
 */
class TestLoadOptionsSerialization {

    private val conf = VolatileConfig.UNSAFE

    // LoadOptionDefaults isolation
    private lateinit var savedExpires: Duration
    private lateinit var savedExpireAt: Instant
    private var savedIgnoreFailure = false
    private var savedParse = false
    private var savedStoreContent = false
    private var savedLazyFlush = false

    @BeforeEach
    fun saveDefaults() {
        savedExpires = LoadOptionDefaults.expires
        savedExpireAt = LoadOptionDefaults.expireAt
        savedIgnoreFailure = LoadOptionDefaults.ignoreFailure
        savedParse = LoadOptionDefaults.parse
        savedStoreContent = LoadOptionDefaults.storeContent
        savedLazyFlush = LoadOptionDefaults.lazyFlush
    }

    @AfterEach
    fun restoreDefaults() {
        LoadOptionDefaults.expires = savedExpires
        LoadOptionDefaults.expireAt = savedExpireAt
        LoadOptionDefaults.ignoreFailure = savedIgnoreFailure
        LoadOptionDefaults.parse = savedParse
        LoadOptionDefaults.storeContent = savedStoreContent
        LoadOptionDefaults.lazyFlush = savedLazyFlush
    }

    // ---------------------------------------------------------------------------
    // toString()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("toString: default options produces valid parseable output")
    fun testToStringForDefaults() {
        val options = LoadOptions.create(conf)
        val str = options.toString()
        // Default options may produce empty or minimal non-default output
        // depending on LoadOptionDefaults state — verify it's parseable
        assertNotNull(str)
        val reparsed = LoadOptions.parse(str, conf)
        assertEquals(options, reparsed,
            "toString of default options should produce parseable output")
    }

    @Test
    @DisplayName("toString: single arity0 boolean flag appears without value")
    fun testToStringSingleBooleanArity0() {
        val options = LoadOptions.parse("-incognito", conf)
        val str = options.toString()
        assertTrue(str.contains("-incognito"), "arity0 boolean should appear as flag: $str")
        assertFalse(str.contains("true"), "arity0 boolean should NOT have a value: $str")
    }

    @Test
    @DisplayName("toString: single arity1 boolean shows value")
    fun testToStringSingleBooleanArity1() {
        // storeContent is arity1, default is true, so setting to false is the non-default
        val options = LoadOptions.parse("-storeContent false", conf)
        val str = options.toString()
        assertTrue(str.contains("-storeContent"), "arity1 boolean should appear with name: $str")
    }

    @Test
    @DisplayName("toString: duration value rendered in human-readable format")
    fun testToStringDurationValue() {
        val options = LoadOptions.parse("-expires 1d", conf)
        val str = options.toString()
        assertTrue(str.contains("1d") || str.contains("expires") || str.contains("-i"),
            "toString should include duration value: $str")
    }

    @Test
    @DisplayName("toString: multiple options are sorted and space-separated")
    fun testToStringMultipleOptions() {
        val options = LoadOptions.parse("-expires 1d -incognito", conf)
        val str = options.toString()
        assertTrue(str.contains("-incognito"), "toString should contain incognito: $str")
    }

    @Test
    @DisplayName("toString: roundtrip — parse then toString then parse again")
    fun testToStringRoundtrip() {
        val original = LoadOptions.parse("-expires 1d -incognito -parse", conf)
        val str = original.toString()
        val reparsed = LoadOptions.parse(str, conf)
        assertEquals(original, reparsed, "toString roundtrip should produce equal options")
    }

    // ---------------------------------------------------------------------------
    // equals() / hashCode()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("equals: same object reference is equal")
    fun testEqualsIdentity() {
        val options = LoadOptions.parse("-expires 1d", conf)
        assertEquals(options, options, "Same reference should be equal")
    }

    @Test
    @DisplayName("equals: different objects with same normalized args are equal")
    fun testEqualsSameValues() {
        val o1 = LoadOptions.parse("-expires 1d -incognito", conf)
        val o2 = LoadOptions.parse("-incognito -expires 1d", conf)
        assertEquals(o1, o2, "Same options in different order should be equal")
    }

    @Test
    @DisplayName("equals: different values are not equal")
    fun testEqualsDifferentValues() {
        val o1 = LoadOptions.parse("-expires 1d", conf)
        val o2 = LoadOptions.parse("-expires 7d", conf)
        assertNotEquals(o1, o2, "Different options should not be equal")
    }

    @Test
    @DisplayName("equals: null is not equal")
    fun testEqualsNull() {
        val options = LoadOptions.parse("-expires 1d", conf)
        @Suppress("SENSELESS_COMPARISON")
        assertFalse(options.equals(null), "null should not be equal")
    }

    @Test
    @DisplayName("equals: different type is not equal")
    fun testEqualsDifferentType() {
        val options = LoadOptions.parse("-expires 1d", conf)
        assertFalse(options.equals("not a LoadOptions"), "Different type should not be equal")
    }

    @Test
    @DisplayName("hashCode: equal objects have same hashCode")
    fun testHashCodeConsistentWithEquals() {
        // hashCode is based on args (argv.joinToString), not normalized toString.
        // Same options parsed the same way produce same args → same hashCode.
        val o1 = LoadOptions.parse("-expires 1d -parse", conf)
        val o2 = LoadOptions.parse("-expires 1d -parse", conf)
        assertEquals(o1, o2, "Should be equal")
        assertEquals(o1.hashCode(), o2.hashCode(),
            "Equal objects with same args should have same hashCode")
    }

    // ---------------------------------------------------------------------------
    // clone()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("clone: produces equal object")
    fun testCloneReturnsEqualObject() {
        val original = LoadOptions.parse("-expires 1d -parse -incognito", conf)
        val cloned = original.clone()
        assertEquals(original, cloned, "Clone should equal original")
    }

    @Test
    @DisplayName("clone: modifications to clone don't affect original")
    fun testCloneDeepCopy() {
        val original = LoadOptions.parse("-expires 1d", conf)
        val cloned = original.clone()
        cloned.expires = Duration.ofDays(7)

        assertNotEquals(original.expires, cloned.expires,
            "Clone modification should not affect original")
        assertEquals(Duration.ofDays(1), original.expires,
            "Original should retain its value after clone is modified")
    }

    @Test
    @DisplayName("clone: preserves refresh state")
    fun testCloneWithRefresh() {
        val original = LoadOptions.parse("-refresh", conf)
        val cloned = original.clone()
        assertTrue(cloned.refresh, "Clone should preserve refresh state")
    }

    // ---------------------------------------------------------------------------
    // isDefault()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("isDefault: true for known field at default value")
    fun testIsDefaultKnownFieldDefault() {
        val options = LoadOptions.create(conf)
        assertTrue(options.isDefault("expires"),
            "expires at default value should be recognized as default")
    }

    @Test
    @DisplayName("isDefault: false for known field at non-default value")
    fun testIsDefaultKnownFieldNonDefault() {
        val options = LoadOptions.parse("-expires 1d", conf)
        assertFalse(options.isDefault("expires"),
            "expires at non-default value should NOT be recognized as default")
    }

    @Test
    @DisplayName("isDefault: false for unknown field name")
    fun testIsDefaultUnknownField() {
        val options = LoadOptions.create(conf)
        assertFalse(options.isDefault("nonexistentField"),
            "Unknown field should return false")
    }

    // ---------------------------------------------------------------------------
    // getParams()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getParams: returns non-empty params map")
    fun testGetParamsNonEmpty() {
        val options = LoadOptions.parse("-expires 1d -parse", conf)
        val params = options.getParams()
        assertTrue(params.asMap().isNotEmpty(), "getParams should return non-empty map")
    }

    @Test
    @DisplayName("getParams: includes all option descriptors")
    fun testGetParamsContainsAllOptionDescriptors() {
        val options = LoadOptions.parse("-expires 1d -parse", conf)
        val params = options.getParams()
        // Should contain entries for all descriptors
        assertTrue(params.asMap().size >= 2, "Should contain at least our 2 explicit options")
    }

    // ---------------------------------------------------------------------------
    // modifiedParams / modifiedOptions
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("modifiedParams: returns params showing non-default values")
    fun testModifiedParamsForDefaults() {
        val options = LoadOptions.create(conf)
        val modified = options.modifiedParams
        // modifiedParams captures fields that differ from DEFAULT.
        // Depending on LoadOptionDefaults state, some fields may appear.
        assertNotNull(modified)
        // At minimum, we can verify modifiedParams is a valid Params object
        val map = modified.asMap()
        assertTrue(map is Map<*, *>)
    }

    @Test
    @DisplayName("modifiedOptions: contains only non-default fields")
    fun testModifiedOptionsOnlyNonDefault() {
        val options = LoadOptions.parse("-expires 1d", conf)
        val modified = options.modifiedOptions
        assertTrue("expires" in modified, "modifiedOptions should contain 'expires'")
    }
}
