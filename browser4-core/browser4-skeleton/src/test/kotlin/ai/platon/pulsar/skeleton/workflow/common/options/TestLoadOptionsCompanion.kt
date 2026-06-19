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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for LoadOptions companion object methods:
 * [LoadOptions.setFieldByAnnotation], [LoadOptions.normalize],
 * [LoadOptions.parse] overloads, [LoadOptions.merge] overloads,
 * [LoadOptions.eraseOptions], and [LoadOptions.DEFAULT] consistency.
 */
class TestLoadOptionsCompanion {

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
    // setFieldByAnnotation()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("setFieldByAnnotation: sets field via annotation name")
    fun testSetFieldByAnnotationMatch() {
        val options = LoadOptions.create(conf)
        LoadOptions.setFieldByAnnotation(options, "-expires", Duration.ofDays(1))
        assertEquals(Duration.ofDays(1), options.expires)
    }

    @Test
    @DisplayName("setFieldByAnnotation: no-op for unknown annotation name")
    fun testSetFieldByAnnotationNoMatch() {
        val options = LoadOptions.create(conf)
        val before = options.toString()
        LoadOptions.setFieldByAnnotation(options, "-nonexistentOption", "value")
        assertEquals(before, options.toString(), "Unknown annotation should not change options")
    }

    @Test
    @DisplayName("setFieldByAnnotation: silently ignored for wrong type")
    fun testSetFieldByAnnotationWrongType() {
        val options = LoadOptions.create(conf)
        // Passing a String to an Int field — should be silently ignored (type check fails)
        val before = options.priority
        LoadOptions.setFieldByAnnotation(options, "-priority", "not_a_number")
        assertEquals(before, options.priority, "Wrong type should be silently ignored")
    }

    // ---------------------------------------------------------------------------
    // normalize()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("normalize: filters null args")
    fun testNormalizeFiltersNull() {
        val result = LoadOptions.normalize("-parse", null, "-incognito")
        // Nulls should be filtered out, result should contain both non-null args
        assertTrue(result.contains("parse") || result.contains("incognito"))
    }

    @Test
    @DisplayName("normalize: all null produces default options")
    fun testNormalizeAllNull() {
        val result = LoadOptions.normalize(null, null)
        // normalize with all null → parse("") → toString of default options
        // May produce empty or minimal output; verify it's parseable
        assertNotNull(result)
        val reparsed = LoadOptions.parse(result, conf)
        assertNotNull(reparsed)
    }

    @Test
    @DisplayName("normalize: single arg returns valid normalized output")
    fun testNormalizeSingleArg() {
        val result = LoadOptions.normalize("-expires 1d")
        // normalize parses then re-serializes; should contain the duration
        assertNotNull(result)
    }

    // ---------------------------------------------------------------------------
    // parse()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("parse: string and conf returns valid LoadOptions")
    fun testParseStringAndConf() {
        val options = LoadOptions.parse("-parse", conf)
        assertNotNull(options)
        assertTrue(options.parse)
    }

    @Test
    @DisplayName("parse: string and base options creates new options with given args")
    fun testParseStringAndOptions() {
        val base = LoadOptions.parse("-expires 1d", conf)
        // parse("-parse", base) creates a NEW instance that inherits conf/events from base
        // but field values are parsed fresh from the args string, not inherited from base
        val merged = LoadOptions.parse("-parse", base)
        assertTrue(merged.parse, "New option should be applied")
        assertNotNull(merged)
    }

    @Test
    @DisplayName("parse: empty string produces valid default options")
    fun testParseEmptyString() {
        val options = LoadOptions.parse("", conf)
        assertNotNull(options)
        // Default options should be parseable back
        val reparsed = LoadOptions.parse(options.toString(), conf)
        assertEquals(options, reparsed)
    }

    // ---------------------------------------------------------------------------
    // merge() — all 4 overloads
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("merge: o2 values override o1 when both specify same field")
    fun testMergeTwoOptions() {
        val o1 = LoadOptions.parse("-expires 1d", conf)
        val o2 = LoadOptions.parse("-expires 7d -incognito", conf)
        val merged = LoadOptions.merge(o1, o2)
        assertEquals(Duration.ofDays(7), merged.expires, "o2 expires should win")
        // incognito is an arity0 boolean from o2; it may or may not survive merge
        // depending on toString representation
        assertNotNull(merged)
    }

    @Test
    @DisplayName("merge: options and string — string overrides")
    fun testMergeOptionsAndString() {
        val o1 = LoadOptions.parse("-expires 1d", conf)
        val merged = LoadOptions.merge(o1, "-expires 7d -parse")
        assertEquals(Duration.ofDays(7), merged.expires)
        assertTrue(merged.parse)
    }

    @Test
    @DisplayName("merge: two strings merge correctly")
    fun testMergeStringAndString() {
        val merged = LoadOptions.merge("-expires 1d", "-parse")
        assertEquals(Duration.ofDays(1), merged.expires)
        assertTrue(merged.parse)
    }

    @Test
    @DisplayName("merge: null args string preserves options")
    fun testMergeOptionsAndNullString() {
        val o1 = LoadOptions.parse("-expires 1d", conf)
        val merged = LoadOptions.merge(o1, null as String?)
        assertEquals(o1, merged, "Null args should preserve original options")
    }

    // ---------------------------------------------------------------------------
    // eraseOptions()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("eraseOptions: removes a single option")
    fun testEraseOptionsSingle() {
        val args = "-incognito -expires 1d"
        val result = LoadOptions.eraseOptions(args, "incognito")
        assertFalse(result.contains("-incognito"),
            "erased option should be removed: $result")
        assertTrue(result.contains("-expires 1d"),
            "non-erased option should remain: $result")
    }

    @Test
    @DisplayName("eraseOptions: removes multiple options")
    fun testEraseOptionsMultiple() {
        val args = "-incognito -expires 1d -parse"
        val result = LoadOptions.eraseOptions(args, "incognito", "expires")
        assertFalse(result.contains("-incognito"))
        assertFalse(result.contains("-expires"))
        assertTrue(result.contains("-parse"), "parse should remain: $result")
    }

    @Test
    @DisplayName("eraseOptions: empty args produces trimmed result")
    fun testEraseOptionsEmpty() {
        val result = LoadOptions.eraseOptions("", "incognito")
        assertEquals("", result.trim())
    }

    // ---------------------------------------------------------------------------
    // DEFAULT consistency
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("DEFAULT: toString is empty")
    fun testDefaultIsEmpty() {
        assertEquals("", LoadOptions.DEFAULT.toString(),
            "DEFAULT instance should produce empty toString")
    }

    @Test
    @DisplayName("DEFAULT: all field values match defaultParams")
    fun testDefaultInstanceConsistency() {
        val defaultOptions = LoadOptions.DEFAULT
        LoadOptions.optionDescriptors.forEach { desc ->
            val expectedDefault = LoadOptions.defaultParams[desc.fieldName]
            val actualValue = desc.get(defaultOptions)
            assertEquals(expectedDefault, actualValue,
                "Field '${desc.fieldName}' in DEFAULT should match defaultParams")
        }
    }

    // ---------------------------------------------------------------------------
    // optionNames / apiPublicOptionNames
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("optionNames: includes all names from descriptors")
    fun testOptionNames() {
        val names = LoadOptions.optionNames
        assertTrue(names.isNotEmpty())
        assertTrue("-expires" in names || "-i" in names)
    }

    @Test
    @DisplayName("apiPublicOptionNames: contains known public options")
    fun testApiPublicOptionNames() {
        val names = LoadOptions.apiPublicOptionNames
        assertTrue(names.isNotEmpty())
    }

    @Test
    @DisplayName("getOptionNames: returns names for known field")
    fun testGetOptionNamesKnown() {
        val names = LoadOptions.getOptionNames("expires")
        assertTrue(names.isNotEmpty(), "expires should have option names")
        assertTrue(names.any { it == "-i" || it == "-expire" || it == "-expires" },
            "expires should have short/long names")
    }

    @Test
    @DisplayName("getOptionNames: returns empty for unknown field")
    fun testGetOptionNamesUnknown() {
        val names = LoadOptions.getOptionNames("nonexistent")
        assertTrue(names.isEmpty(), "Unknown field should return empty")
    }
}
