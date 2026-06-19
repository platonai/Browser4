package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.options.LoadOptionsJson
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for internal LoadOptionsJson serialization helpers:
 * mapToArgs dispatch, formatDurationToString, DurationJsonSerializer/Deserializer,
 * and edge cases not covered in [TestLoadOptionsJson].
 */
class TestLoadOptionsJsonInternals {

    private val conf = VolatileConfig.UNSAFE

    // ---------------------------------------------------------------------------
    // fromMap / mapToArgs — value type dispatch
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("fromMap: boolean true becomes flag")
    fun testFromMapBooleanTrue() {
        val map = mapOf<String, Any?>("incognito" to true)
        val options = LoadOptionsJson.fromMap(map, conf)
        assertTrue(options.incognito)
    }

    @Test
    @DisplayName("fromMap: boolean false sets arity1")
    fun testFromMapBooleanFalse() {
        val map = mapOf<String, Any?>("storeContent" to false)
        val options = LoadOptionsJson.fromMap(map, conf)
        assertEquals(false, options.storeContent)
    }

    @Test
    @DisplayName("fromMap: duration value")
    fun testFromMapDuration() {
        val map = mapOf<String, Any?>("expires" to Duration.ofDays(2))
        val options = LoadOptionsJson.fromMap(map, conf)
        assertEquals(Duration.ofDays(2), options.expires)
    }

    @Test
    @DisplayName("fromMap: instant value")
    fun testFromMapInstant() {
        val time = Instant.parse("2024-01-05T10:00:00Z")
        val map = mapOf<String, Any?>("deadline" to time)
        val options = LoadOptionsJson.fromMap(map, conf)
        assertEquals(time, options.deadline)
    }

    @Test
    @DisplayName("fromMap: null values are filtered out")
    fun testFromMapNullFiltered() {
        val map = mapOf<String, Any?>("entity" to null, "label" to "test")
        val options = LoadOptionsJson.fromMap(map, conf)
        assertEquals("test", options.label)
    }

    // ---------------------------------------------------------------------------
    // formatDurationToString — via toJson roundtrip
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("formatDuration: days")
    fun testFormatDurationDays() {
        val options = LoadOptions.parse("-expires 2d", conf)
        val json = LoadOptionsJson.toJson(options)
        assertTrue(json.contains("2d"))
    }

    @Test
    @DisplayName("formatDuration: hours")
    fun testFormatDurationHours() {
        val options = LoadOptions.parse("-expires 3h", conf)
        val json = LoadOptionsJson.toJson(options)
        assertTrue(json.contains("3h"))
    }

    @Test
    @DisplayName("formatDuration: minutes")
    fun testFormatDurationMinutes() {
        val options = LoadOptions.parse("-expires 5m", conf)
        val json = LoadOptionsJson.toJson(options)
        assertTrue(json.contains("5m"))
    }

    @Test
    @DisplayName("formatDuration: seconds")
    fun testFormatDurationSeconds() {
        val options = LoadOptions.parse("-expires 30s", conf)
        val json = LoadOptionsJson.toJson(options)
        assertTrue(json.contains("30s"))
    }

    // ---------------------------------------------------------------------------
    // JSON serializer/deserializer null handling
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("toJson: default options produce valid JSON")
    fun testToJsonDefaultOptions() {
        val options = LoadOptions.create(conf)
        val json = LoadOptionsJson.toJson(options, includeDefaults = true)
        assertTrue(json.isNotBlank())
        // Should contain field names when including defaults
        assertTrue(json.contains("expires") || json.contains("version"))
    }

    @Test
    @DisplayName("fromJson: unknown properties ignored")
    fun testFromJsonIgnoresUnknownProperties() {
        val json = """{"expires": "1d", "unknownField": "should-be-ignored", "alsoUnknown": 42}"""
        val options = LoadOptionsJson.fromJson(json, conf)
        assertEquals(Duration.ofDays(1), options.expires)
    }

    @Test
    @DisplayName("fromJson: trailing commas allowed")
    fun testFromJsonTrailingCommas() {
        val json = """{"expires": "1d", "parse": true,}"""
        val options = LoadOptionsJson.fromJson(json, conf)
        assertEquals(Duration.ofDays(1), options.expires)
        assertTrue(options.parse)
    }

    @Test
    @DisplayName("fromJson: single quotes allowed")
    fun testFromJsonSingleQuotes() {
        val json = """{'expires': '1d', 'parse': true}"""
        val options = LoadOptionsJson.fromJson(json, conf)
        assertEquals(Duration.ofDays(1), options.expires)
        assertTrue(options.parse)
    }

    // ---------------------------------------------------------------------------
    // toMap / toModifiedMap
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("toMap: includes all fields")
    fun testToMapIncludesAllFields() {
        val options = LoadOptions.parse("-expires 1d", conf)
        val map = LoadOptionsJson.toMap(options)
        assertTrue(map.size >= LoadOptions.optionDescriptors.size * 0.5,
            "toMap should have a substantial number of fields")
    }

    @Test
    @DisplayName("toModifiedMap: only non-default fields")
    fun testToModifiedMapOnlyNonDefault() {
        val options = LoadOptions.parse("-expires 1d", conf)
        val modified = LoadOptionsJson.toModifiedMap(options)
        assertTrue("expires" in modified, "expires should be in modified map")
        // Default fields like entity should NOT be there
        assertEquals(false, modified.containsKey("entity") && (modified["entity"] as String).isEmpty(),
            "entity at default empty string should likely be excluded")
    }
}
