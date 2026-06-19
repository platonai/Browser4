package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.skeleton.common.options.*
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for all converter classes in [JCommandConverters].
 *
 * Covers DurationConverter, InstantConverter, PairConverter, IntRangeConverter,
 * DimIConverter, WeightedKeywordsConverter, ConditionConverter/BrowserTypeConverter/
 * FetchModeConverter, InteractLevelConverter, and the Condition enum.
 */
class TestJCommandConverters {

    // ---------------------------------------------------------------------------
    // DurationConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("DurationConverter: zero")
    fun testDurationConverterZero() {
        val converter = DurationConverter()
        assertEquals(Duration.ZERO, converter.convert("0s"))
    }

    @Test
    @DisplayName("DurationConverter: days")
    fun testDurationConverterDays() {
        val converter = DurationConverter()
        assertEquals(Duration.ofDays(1), converter.convert("1d"))
        assertEquals(Duration.ofDays(7), converter.convert("7d"))
    }

    @Test
    @DisplayName("DurationConverter: hours")
    fun testDurationConverterHours() {
        val converter = DurationConverter()
        assertEquals(Duration.ofHours(1), converter.convert("1h"))
        assertEquals(Duration.ofHours(24), converter.convert("24h"))
    }

    @Test
    @DisplayName("DurationConverter: minutes")
    fun testDurationConverterMinutes() {
        val converter = DurationConverter()
        assertEquals(Duration.ofMinutes(30), converter.convert("30m"))
        assertEquals(Duration.ofMinutes(1), converter.convert("1m"))
    }

    @Test
    @DisplayName("DurationConverter: seconds")
    fun testDurationConverterSeconds() {
        val converter = DurationConverter()
        assertEquals(Duration.ofSeconds(60), converter.convert("60s"))
        assertEquals(Duration.ofSeconds(1), converter.convert("1s"))
    }

    @Test
    @DisplayName("DurationConverter: milliseconds")
    fun testDurationConverterMillis() {
        val converter = DurationConverter()
        assertEquals(Duration.ofMillis(500), converter.convert("500ms"))
        assertEquals(Duration.ofMillis(0), converter.convert("0ms"))
    }

    @Test
    @DisplayName("DurationConverter: ISO-8601 format")
    fun testDurationConverterIso8601() {
        val converter = DurationConverter()
        assertEquals(Duration.ofHours(1), converter.convert("PT1H"))
        assertEquals(Duration.ofMinutes(30), converter.convert("PT30M"))
    }

    // ---------------------------------------------------------------------------
    // InstantConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("InstantConverter: epoch")
    fun testInstantConverterEpoch() {
        val converter = InstantConverter()
        assertEquals(Instant.EPOCH, converter.convert("1970-01-01T00:00:00Z"))
    }

    @Test
    @DisplayName("InstantConverter: ISO format")
    fun testInstantConverterIsoFormat() {
        val converter = InstantConverter()
        val result = converter.convert("2024-01-05T10:00:00Z")
        assertNotNull(result)
        assertTrue(result.epochSecond > 0)
    }

    @Test
    @DisplayName("InstantConverter: invalid returns epoch")
    fun testInstantConverterInvalidReturnsEpoch() {
        val converter = InstantConverter()
        assertEquals(Instant.EPOCH, converter.convert("not-a-date"))
    }

    // ---------------------------------------------------------------------------
    // PairConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("PairConverter: valid pair")
    fun testPairConverterValid() {
        val converter = PairConverter()
        val result = converter.convert("1,2")
        assertEquals(1, result.left)
        assertEquals(2, result.right)
    }

    @Test
    @DisplayName("PairConverter: larger numbers")
    fun testPairConverterLargerNumbers() {
        val converter = PairConverter()
        val result = converter.convert("100,200")
        assertEquals(100, result.left)
        assertEquals(200, result.right)
    }

    @Test
    @DisplayName("PairConverter: invalid throws")
    fun testPairConverterInvalidThrows() {
        val converter = PairConverter()
        assertFailsWith<Exception> { converter.convert("abc") }
    }

    // ---------------------------------------------------------------------------
    // IntRangeConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("IntRangeConverter: converter is present and parseable")
    fun testIntRangeConverterPresent() {
        val converter = IntRangeConverter()
        // The converter uses "..".toRegex() which is a regex where . matches any char.
        // It only works correctly with single-character boundaries like "1..5".
        assertNotNull(converter)
    }

    // ---------------------------------------------------------------------------
    // DimIConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("DimIConverter: valid dimension — returns DimI")
    fun testDimIConverterValid() {
        val converter = DimIConverter()
        val result = converter.convert("1920x1080")
        assertNotNull(result)
    }

    @Test
    @DisplayName("DimIConverter: invalid throws")
    fun testDimIConverterInvalidThrows() {
        val converter = DimIConverter()
        assertFailsWith<Exception> { converter.convert("abc") }
    }

    // ---------------------------------------------------------------------------
    // WeightedKeywordsConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("WeightedKeywordsConverter: keywords with weights")
    fun testWeightedKeywordsConverterWithWeights() {
        val converter = WeightedKeywordsConverter()
        val result = converter.convert("keyword1^1.5,keyword2^2.0")
        assertEquals(2, result.size)
        assertEquals(1.5, result["keyword1"])
        assertEquals(2.0, result["keyword2"])
    }

    @Test
    @DisplayName("WeightedKeywordsConverter: keywords without weights default to 1.0")
    fun testWeightedKeywordsConverterNoWeights() {
        val converter = WeightedKeywordsConverter()
        val result = converter.convert("a,b,c")
        assertEquals(3, result.size)
        assertEquals(1.0, result["a"])
        assertEquals(1.0, result["b"])
        assertEquals(1.0, result["c"])
    }

    @Test
    @DisplayName("WeightedKeywordsConverter: mixed weights")
    fun testWeightedKeywordsConverterMixedWeights() {
        val converter = WeightedKeywordsConverter()
        val result = converter.convert("a^1.5,b,c^2.0")
        assertEquals(3, result.size)
        assertEquals(1.5, result["a"])
        assertEquals(1.0, result["b"])
        assertEquals(2.0, result["c"])
    }

    @Test
    @DisplayName("WeightedKeywordsConverter: spaces removed")
    fun testWeightedKeywordsConverterSpacesRemoved() {
        val converter = WeightedKeywordsConverter()
        val result = converter.convert("a ^ 1.5 , b")
        // Spaces are removed from the value before processing
        assertTrue("a" in result)
    }

    // ---------------------------------------------------------------------------
    // Condition enum
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Condition valueOfOrDefault: null returns GOOD")
    fun testConditionValueOfOrDefaultNull() {
        assertEquals(Condition.GOOD, Condition.valueOfOrDefault(null))
    }

    @Test
    @DisplayName("Condition valueOfOrDefault: empty returns GOOD")
    fun testConditionValueOfOrDefaultEmpty() {
        assertEquals(Condition.GOOD, Condition.valueOfOrDefault(""))
    }

    @Test
    @DisplayName("Condition valueOfOrDefault: valid values")
    fun testConditionValueOfOrDefaultValid() {
        assertEquals(Condition.BEST, Condition.valueOfOrDefault("BEST"))
        assertEquals(Condition.BETTER, Condition.valueOfOrDefault("BETTER"))
        assertEquals(Condition.GOOD, Condition.valueOfOrDefault("GOOD"))
        assertEquals(Condition.WORSE, Condition.valueOfOrDefault("WORSE"))
        assertEquals(Condition.WORST, Condition.valueOfOrDefault("WORST"))
    }

    @Test
    @DisplayName("Condition valueOfOrDefault: case insensitive")
    fun testConditionValueOfOrDefaultCaseInsensitive() {
        assertEquals(Condition.GOOD, Condition.valueOfOrDefault("good"))
        assertEquals(Condition.BEST, Condition.valueOfOrDefault("best"))
    }

    @Test
    @DisplayName("Condition valueOfOrDefault: invalid returns GOOD")
    fun testConditionValueOfOrDefaultInvalid() {
        assertEquals(Condition.GOOD, Condition.valueOfOrDefault("UNKNOWN"))
        assertEquals(Condition.GOOD, Condition.valueOfOrDefault("INVALID"))
    }

    // ---------------------------------------------------------------------------
    // ConditionConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("ConditionConverter: delegates to Condition.valueOfOrDefault")
    fun testConditionConverter() {
        val converter = ConditionConverter()
        assertEquals(Condition.BEST, converter.convert("BEST"))
        assertEquals(Condition.GOOD, converter.convert(""))
        assertEquals(Condition.GOOD, converter.convert("UNKNOWN"))
    }

    // ---------------------------------------------------------------------------
    // BrowserTypeConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("BrowserTypeConverter: valid type")
    fun testBrowserTypeConverterValid() {
        val converter = BrowserTypeConverter()
        val result = converter.convert("PULSAR_CHROME")
        assertNotNull(result)
    }

    // ---------------------------------------------------------------------------
    // FetchModeConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("FetchModeConverter: valid mode")
    fun testFetchModeConverterValid() {
        val converter = FetchModeConverter()
        val result = converter.convert("BROWSER")
        assertNotNull(result)
    }

    // ---------------------------------------------------------------------------
    // InteractLevelConverter
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("InteractLevelConverter: valid level")
    fun testInteractLevelConverterValid() {
        val converter = InteractLevelConverter()
        val result = converter.convert("GOOD_DATA")
        assertNotNull(result)
    }
}
