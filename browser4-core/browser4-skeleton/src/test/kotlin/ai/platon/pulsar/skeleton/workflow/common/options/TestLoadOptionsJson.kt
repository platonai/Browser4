package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.options.LoadOptionsJson
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LoadOptionsJson] - JSON serialization and deserialization of LoadOptions.
 */
class TestLoadOptionsJson {

    private val conf = VolatileConfig.UNSAFE

    @Test
    @DisplayName("test toJson with default options")
    fun testToJsonWithDefaultOptions() {
        val options = LoadOptions.create(conf)
        val json = LoadOptionsJson.toJson(options)

        // Default options should produce minimal JSON (empty or nearly empty)
        assertTrue(json.isNotBlank())
        println("Default options JSON: $json")
    }

    @Test
    @DisplayName("test toJson with modified options")
    fun testToJsonWithModifiedOptions() {
        val options = LoadOptions.parse("-expires 1d -ignoreFailure -parse", conf)
        val json = LoadOptionsJson.toJson(options)

        println("Modified options JSON: $json")
        assertTrue(json.contains("expires"))
        assertTrue(json.contains("ignoreFailure"))
        assertTrue(json.contains("parse"))
        assertTrue(json.contains("1d"))
        assertTrue(json.contains("true"))
    }

    @Test
    @DisplayName("test toJson with includeDefaults")
    fun testToJsonWithIncludeDefaults() {
        val options = LoadOptions.create(conf)
        val json = LoadOptionsJson.toJson(options, includeDefaults = true)

        println("Full options JSON: $json")
        // Should contain all fields
        assertTrue(json.contains("expires"))
        assertTrue(json.contains("persist"))
        assertTrue(json.contains("storeContent"))
    }

    @Test
    @DisplayName("test fromJson basic")
    fun testFromJsonBasic() {
        val json = """
            {
                "expires": "1d",
                "ignoreFailure": true,
                "parse": true
            }
        """.trimIndent()

        val options = LoadOptionsJson.fromJson(json, conf)

        assertEquals(Duration.ofDays(1), options.expires)
        assertTrue(options.ignoreFailure)
        assertTrue(options.parse)
    }

    @Test
    @DisplayName("test fromJson with various duration formats")
    fun testFromJsonWithVariousDurationFormats() {
        val json = """
            {
                "expires": "2h",
                "scrollInterval": "500ms",
                "scriptTimeout": "30s",
                "pageLoadTimeout": "5m"
            }
        """.trimIndent()

        val options = LoadOptionsJson.fromJson(json, conf)

        assertEquals(Duration.ofHours(2), options.expires)
        assertEquals(Duration.ofMillis(500), options.scrollInterval)
        assertEquals(Duration.ofSeconds(30), options.scriptTimeout)
        assertEquals(Duration.ofMinutes(5), options.pageLoadTimeout)
    }

    @Test
    @DisplayName("test roundTrip conversion")
    fun testRoundTripConversion() {
        val originalOptions = LoadOptions.parse(
            "-expires 1d -ignoreFailure -parse -topLinks 50 -autoScrollCount 10 -browser PULSAR_CHROME",
            conf
        )

        val json = LoadOptionsJson.toJson(originalOptions)
        println("RoundTrip JSON: $json")

        val restoredOptions = LoadOptionsJson.fromJson(json, conf)

        assertEquals(originalOptions.expires, restoredOptions.expires)
        assertEquals(originalOptions.ignoreFailure, restoredOptions.ignoreFailure)
        assertEquals(originalOptions.parse, restoredOptions.parse)
        assertEquals(originalOptions.topLinks, restoredOptions.topLinks)
        assertEquals(originalOptions.autoScrollCount, restoredOptions.autoScrollCount)
    }

    @Test
    @DisplayName("test toMap")
    fun testToMap() {
        val options = LoadOptions.parse("-expires 1d -ignoreFailure -topLinks 50", conf)
        val map = LoadOptionsJson.toMap(options)

        println("Options map: $map")
        assertTrue(map.isNotEmpty())
        assertTrue(map.containsKey("expires"))
        assertTrue(map.containsKey("ignoreFailure"))
        assertTrue(map.containsKey("topLinks"))
    }

    @Test
    @DisplayName("test toModifiedMap")
    fun testTomodifiedmap() {
        val options = LoadOptions.parse("-expires 1d -ignoreFailure", conf)
        val map = LoadOptionsJson.toModifiedMap(options)

        println("Modified map: $map")
        // Should only contain modified values
        assertTrue(map.containsKey("expires"))
        assertTrue(map.containsKey("ignoreFailure"))
        // Should not contain default values
        assertFalse(map.containsKey("persist"))
    }

    @Test
    @DisplayName("test fromMap")
    fun testFromMap() {
        val map = mapOf(
            "expires" to "1d",
            "ignoreFailure" to true,
            "topLinks" to 50
        )

        val options = LoadOptionsJson.fromMap(map, conf)

        assertEquals(Duration.ofDays(1), options.expires)
        assertTrue(options.ignoreFailure)
        assertEquals(50, options.topLinks)
    }

    @Test
    @DisplayName("test generateJsonTemplate")
    fun testGenerateJsonTemplate() {
        val template = LoadOptionsJson.generateJsonTemplate()

        println("JSON Template:\n$template")
        assertTrue(template.isNotBlank())
        assertTrue(template.contains("expires"))
        assertTrue(template.contains("persist"))
    }

    @Test
    @DisplayName("test fromJson ignores unknown properties")
    fun testFromJsonIgnoresUnknownProperties() {
        val json = """
            {
                "expires": "1d",
                "unknownProperty": "someValue",
                "anotherUnknown": 123
            }
        """.trimIndent()

        // Should not throw exception
        val options = LoadOptionsJson.fromJson(json, conf)
        assertEquals(Duration.ofDays(1), options.expires)
    }

    @Test
    @DisplayName("test fromJson with string values")
    fun testFromJsonWithStringValues() {
        val json = """
            {
                "label": "test-label",
                "entity": "product",
                "outLinkSelector": ".product-link a"
            }
        """.trimIndent()

        val options = LoadOptionsJson.fromJson(json, conf)

        assertEquals("test-label", options.label)
        assertEquals("product", options.entity)
        assertEquals(".product-link a", options.outLinkSelector)
    }

    @Test
    @DisplayName("test fromJson with integer values")
    fun testFromJsonWithIntegerValues() {
        val json = """
            {
                "topLinks": 100,
                "requireSize": 1024,
                "requireImages": 5,
                "priority": -1000
            }
        """.trimIndent()

        val options = LoadOptionsJson.fromJson(json, conf)

        assertEquals(100, options.topLinks)
        assertEquals(1024, options.requireSize)
        assertEquals(5, options.requireImages)
        assertEquals(-1000, options.priority)
    }

    @Test
    @DisplayName("test fromJson with boolean values")
    fun testFromJsonWithBooleanValues() {
        val json = """
            {
                "refresh": true,
                "storeContent": false,
                "dropContent": true,
                "readonly": true
            }
        """.trimIndent()

        val options = LoadOptionsJson.fromJson(json, conf)

        assertTrue(options.refresh)
        assertFalse(options.storeContent)
        assertTrue(options.dropContent)
        assertTrue(options.readonly)
    }

    @Test
    @DisplayName("test item options in JSON")
    fun testItemOptionsInJson() {
        val json = """
            {
                "itemExpires": "7d",
                "itemScrollCount": 15,
                "itemScrollInterval": "1s",
                "itemRequireSize": 2048
            }
        """.trimIndent()

        val options = LoadOptionsJson.fromJson(json, conf)

        assertEquals(Duration.ofDays(7), options.itemExpires)
        assertEquals(15, options.itemScrollCount)
        assertEquals(Duration.ofSeconds(1), options.itemScrollInterval)
        assertEquals(2048, options.itemRequireSize)
    }

    @Test
    @DisplayName("test complex roundtrip")
    fun testComplexRoundtrip() {
        val originalArgs = """
            -expires 2d
            -itemExpires 7d
            -ignoreFailure
            -parse
            -topLinks 100
            -outLinkSelector ".products a"
            -autoScrollCount 15
            -scrollInterval 1s
            -browser PULSAR_CHROME
            -interactLevel GOOD_DATA
            -label test-label
            -entity product
        """.trimIndent().replace("\n", " ")

        val originalOptions = LoadOptions.parse(originalArgs, conf)
        val json = LoadOptionsJson.toJson(originalOptions)
        println("Complex roundtrip JSON:\n$json")

        val restoredOptions = LoadOptionsJson.fromJson(json, conf)

        assertEquals(originalOptions.expires, restoredOptions.expires)
        assertEquals(originalOptions.itemExpires, restoredOptions.itemExpires)
        assertEquals(originalOptions.ignoreFailure, restoredOptions.ignoreFailure)
        assertEquals(originalOptions.parse, restoredOptions.parse)
        assertEquals(originalOptions.topLinks, restoredOptions.topLinks)
        assertEquals(originalOptions.outLinkSelector, restoredOptions.outLinkSelector)
        assertEquals(originalOptions.autoScrollCount, restoredOptions.autoScrollCount)
        assertEquals(originalOptions.scrollInterval, restoredOptions.scrollInterval)
        assertEquals(originalOptions.interactLevel, restoredOptions.interactLevel)
        assertEquals(originalOptions.label, restoredOptions.label)
        assertEquals(originalOptions.entity, restoredOptions.entity)
    }
}
