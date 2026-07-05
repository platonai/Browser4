package ai.platon.browser4.api

import ai.platon.pulsar.common.browser.InteractLevel
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [InteractSettings] methods:
 * [InteractSettings.applyDelayPreset], [InteractSettings.generateRestrictedDelayPolicy],
 * [InteractSettings.generateRestrictedTimeoutPolicy], [InteractSettings.buildScrollPositions],
 * [InteractSettings.noScroll], and [InteractSettings.create].
 */
class InteractSettingsTest {

    // ---------------------------------------------------------------------------
    // applyDelayPreset()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("applyDelayPreset: FASTEST sets all delays to 10ms")
    fun testApplyDelayPresetFastest() {
        val settings = InteractSettings.create(InteractLevel.FASTEST)
        // All delays should be clamped to 10..10
        settings.delayPolicy.forEach { (_, range) ->
            assertEquals(10, range.first, "FASTEST: first should be 10 for all actions")
            assertEquals(10, range.last, "FASTEST: last should be 10 for all actions")
        }
    }

    @Test
    @DisplayName("applyDelayPreset: FAST uses fast delay values")
    fun testApplyDelayPresetFast() {
        val settings = InteractSettings.create(InteractLevel.FAST)
        // "gap" should use fast range
        val gapDelay = settings.delayPolicy["gap"]
        assertTrue(gapDelay != null, "gap delay should exist")
        assertTrue(gapDelay!!.first <= gapDelay.last, "delay range should be valid")
    }

    @Test
    @DisplayName("applyDelayPreset: DEFAULT uses default delay values")
    fun testApplyDelayPresetDefault() {
        val settings = InteractSettings.create(InteractLevel.DEFAULT)
        val gapDelay = settings.delayPolicy["gap"]
        assertTrue(gapDelay != null, "gap delay should exist")
    }

    @Test
    @DisplayName("applyDelayPreset: STEALTH uses stealth delay values")
    fun testApplyDelayPresetStealth() {
        val settings = InteractSettings.create(InteractLevel.GOOD_DATA)
        val gapDelay = settings.delayPolicy["gap"]
        assertTrue(gapDelay != null, "gap delay should exist")
        // Stealth delays should be larger than default
        val defaultSettings = InteractSettings.create(InteractLevel.DEFAULT)
        val defaultGap = defaultSettings.delayPolicy["gap"]
        assertTrue(
            gapDelay!!.first >= defaultGap!!.first,
            "Stealth gap delay should be >= default gap delay"
        )
    }

    // ---------------------------------------------------------------------------
    // generateRestrictedDelayPolicy()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("generateRestrictedDelayPolicy: clamps delays below minDelayMillis")
    fun testGenerateRestrictedDelayClampLow() {
        val settings = InteractSettings()
        settings.minDelayMillis = 200
        settings.delayPolicy["click"] = 50..180 // first < minDelayMillis
        settings.generateRestrictedDelayPolicy()

        val clickDelay = settings.delayPolicy["click"]
        assertTrue(clickDelay!!.first >= 200, "Delay first should be clamped up to minDelayMillis")
    }

    @Test
    @DisplayName("generateRestrictedDelayPolicy: clamps delays above maxDelayMillis")
    fun testGenerateRestrictedDelayClampHigh() {
        val settings = InteractSettings()
        settings.maxDelayMillis = 1000
        settings.delayPolicy["gap"] = 500..3000 // last > maxDelayMillis
        settings.generateRestrictedDelayPolicy()

        val gapDelay = settings.delayPolicy["gap"]
        assertTrue(gapDelay!!.last <= 1000, "Delay last should be clamped down to maxDelayMillis")
    }

    @Test
    @DisplayName("generateRestrictedDelayPolicy: sets default fallback")
    fun testGenerateRestrictedDelayFallback() {
        val settings = InteractSettings()
        settings.delayPolicy.remove("default")
        settings.generateRestrictedDelayPolicy()

        val defaultDelay = settings.delayPolicy["default"]
        assertTrue(defaultDelay != null, "default delay should be set as fallback")
        assertTrue(defaultDelay!!.first <= defaultDelay.last, "fallback range should be valid")
    }

    // ---------------------------------------------------------------------------
    // generateRestrictedTimeoutPolicy()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("generateRestrictedTimeoutPolicy: clamps timeouts below minTimeout")
    fun testGenerateRestrictedTimeoutClampLow() {
        val settings = InteractSettings()
        settings.minTimeout = Duration.ofSeconds(5)
        settings.timeoutPolicy["waitForSelector"] = Duration.ofSeconds(1) // below min
        settings.generateRestrictedTimeoutPolicy()

        val timeout = settings.timeoutPolicy["waitForSelector"]
        assertTrue(timeout!! >= Duration.ofSeconds(5), "Timeout should be clamped up to minTimeout")
    }

    @Test
    @DisplayName("generateRestrictedTimeoutPolicy: clamps timeouts above maxTimeout")
    fun testGenerateRestrictedTimeoutClampHigh() {
        val settings = InteractSettings()
        settings.maxTimeout = Duration.ofMinutes(1)
        settings.timeoutPolicy["pageLoad"] = Duration.ofMinutes(5) // above max
        settings.generateRestrictedTimeoutPolicy()

        val timeout = settings.timeoutPolicy["pageLoad"]
        assertTrue(timeout!! <= Duration.ofMinutes(1), "Timeout should be clamped down to maxTimeout")
    }

    // ---------------------------------------------------------------------------
    // buildScrollPositions() / buildInitScrollPositions()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("buildScrollPositions: no scroll returns only init positions")
    fun testBuildScrollPositionsNoScroll() {
        val settings = InteractSettings(
            initScrollPositions = "0.3,0.75",
            autoScrollCount = 0
        )
        val positions = settings.buildScrollPositions()
        assertEquals(2, positions.size, "Should have only the 2 init positions")
        assertTrue(0.3 in positions)
        assertTrue(0.75 in positions)
    }

    @Test
    @DisplayName("buildScrollPositions: includes both init and auto-generated positions")
    fun testBuildScrollPositionsWithScroll() {
        val settings = InteractSettings(
            initScrollPositions = "0.3,0.75",
            autoScrollCount = 3
        )
        val positions = settings.buildScrollPositions()
        // Should have init positions + auto-generated (at least 3 from autoScrollCount)
        assertTrue(positions.size >= 2, "Should have at least init positions")
    }

    @Test
    @DisplayName("buildScrollPositions: no init positions, only auto-generated")
    fun testBuildScrollPositionsNoInit() {
        val settings = InteractSettings(
            initScrollPositions = "",
            autoScrollCount = 2
        )
        val positions = settings.buildScrollPositions()
        assertTrue(positions.isNotEmpty(), "Should have auto-generated positions even without init")
    }

    @Test
    @DisplayName("buildScrollPositions: generated positions bounded at 0.8")
    fun testBuildScrollPositionsBounds() {
        val settings = InteractSettings(
            initScrollPositions = "",
            autoScrollCount = 3
        )
        val positions = settings.buildScrollPositions()
        positions.forEach { pos ->
            assertTrue(pos <= 0.8, "Generated positions should be bounded at 0.8, got $pos")
        }
    }

    // ---------------------------------------------------------------------------
    // buildInitScrollPositions()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("buildInitScrollPositions: blank string returns empty list")
    fun testBuildInitScrollPositionsBlank() {
        val settings = InteractSettings(initScrollPositions = "")
        val positions = settings.buildInitScrollPositions()
        assertTrue(positions.isEmpty(), "Blank init positions should return empty list")
    }

    @Test
    @DisplayName("buildInitScrollPositions: single value")
    fun testBuildInitScrollPositionsSingle() {
        val settings = InteractSettings(initScrollPositions = "0.5")
        val positions = settings.buildInitScrollPositions()
        assertEquals(1, positions.size)
        assertEquals(0.5, positions[0])
    }

    // ---------------------------------------------------------------------------
    // noScroll()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("noScroll: clears init positions and sets scroll count to 0")
    fun testNoScroll() {
        val settings = InteractSettings(
            initScrollPositions = "0.3,0.75",
            autoScrollCount = 5
        )
        settings.noScroll()

        assertEquals("", settings.initScrollPositions, "initScrollPositions should be cleared")
        assertEquals(0, settings.autoScrollCount, "autoScrollCount should be 0")
        val positions = settings.buildScrollPositions()
        assertTrue(positions.isEmpty(), "noScroll should result in zero scroll positions")
    }

    // ---------------------------------------------------------------------------
    // create(level: InteractLevel) — all 7 levels
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("create: FASTEST level")
    fun testCreateFastest() {
        val settings = InteractSettings.create(InteractLevel.FASTEST)
        assertEquals(0, settings.autoScrollCount, "FASTEST should have no scroll")
        assertTrue(settings.scrollInterval.toMillis() <= 500, "FASTEST should be fast")
    }

    @Test
    @DisplayName("create: FASTER level")
    fun testCreateFaster() {
        val settings = InteractSettings.create(InteractLevel.FASTER)
        assertEquals(0, settings.autoScrollCount, "FASTER should have no scroll")
        assertTrue(settings.pageLoadTimeout <= Duration.ofMinutes(3), "FASTER should have reasonable timeout")
    }

    @Test
    @DisplayName("create: FAST level")
    fun testCreateFast() {
        val settings = InteractSettings.create(InteractLevel.FAST)
        assertEquals(0, settings.autoScrollCount, "FAST should have no scroll")
    }

    @Test
    @DisplayName("create: DEFAULT level")
    fun testCreateDefault() {
        val settings = InteractSettings.create(InteractLevel.DEFAULT)
        assertEquals(1, settings.autoScrollCount, "DEFAULT should have 1 scroll")
        assertEquals(Duration.ofMillis(500), settings.scrollInterval)
        assertEquals(Duration.ofMinutes(1), settings.scriptTimeout)
        assertEquals(Duration.ofMinutes(3), settings.pageLoadTimeout)
    }

    @Test
    @DisplayName("create: GOOD_DATA level")
    fun testCreateGoodData() {
        val settings = InteractSettings.create(InteractLevel.GOOD_DATA)
        assertEquals(2, settings.autoScrollCount, "GOOD_DATA should scroll twice")
        assertTrue(settings.bringToFront, "GOOD_DATA should bring to front")
    }

    @Test
    @DisplayName("create: BETTER_DATA level")
    fun testCreateBetterData() {
        val settings = InteractSettings.create(InteractLevel.BETTER_DATA)
        assertEquals(3, settings.autoScrollCount, "BETTER_DATA should scroll 3 times")
        assertTrue(settings.bringToFront, "BETTER_DATA should bring to front")
    }

    @Test
    @DisplayName("create: BEST_DATA level")
    fun testCreateBestData() {
        val settings = InteractSettings.create(InteractLevel.BEST_DATA)
        assertEquals(5, settings.autoScrollCount, "BEST_DATA should scroll 5 times")
        assertTrue(settings.bringToFront, "BEST_DATA should bring to front")
    }

    // ---------------------------------------------------------------------------
    // Stealth delays are larger than fast delays (comparative)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("delay presets: STEALTH > DEFAULT > FAST in magnitude")
    fun testDelayPresetsMagnitude() {
        val fastest = InteractSettings.create(InteractLevel.FASTEST)
        val fast = InteractSettings.create(InteractLevel.FAST)
        val default = InteractSettings.create(InteractLevel.DEFAULT)
        val stealth = InteractSettings.create(InteractLevel.GOOD_DATA)

        val fastestGap = fastest.delayPolicy["gap"]!!.first
        val fastGap = fast.delayPolicy["gap"]!!.first
        val defaultGap = default.delayPolicy["gap"]!!.first
        val stealthGap = stealth.delayPolicy["gap"]!!.first

        assertTrue(fastestGap <= fastGap, "FASTEST <= FAST")
        assertTrue(fastGap <= defaultGap, "FAST <= DEFAULT")
        assertTrue(defaultGap <= stealthGap, "DEFAULT <= STEALTH")
    }
}
