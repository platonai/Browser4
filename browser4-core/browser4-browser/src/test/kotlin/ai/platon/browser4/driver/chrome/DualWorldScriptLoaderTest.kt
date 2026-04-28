package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.browser4.driver.common.DualWorldScriptLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.DisplayName

/**
 * Tests for the dual-world script injection mechanism.
 */
class DualWorldScriptLoaderTest {

    private val config = ImmutableConfig(loadDefaults = true)
    private val settings = BrowserSettings(config)
    private val loader = settings.dualWorldScriptLoader

    @Test
        @DisplayName("test page world scripts contain only stealth patches")
    fun testPageWorldScriptsContainOnlyStealthPatches() {
        val pageWorldJs = loader.getPageWorldJs()

        // Page world should contain stealth.js
        assertNotNull(pageWorldJs)
        assertTrue(pageWorldJs.isNotBlank(), "Page world scripts should not be empty")

        // Page world should NOT contain runtime bridge or pulsar utils
        assertFalse(pageWorldJs.contains("__pulsar_utils__"),
            "Page world should not contain pulsar utils")
        assertFalse(pageWorldJs.contains("__browser4_runtime__") && pageWorldJs.contains("Browser4 Runtime Bridge"),
            "Page world should not contain runtime bridge")

        println("Page World JS size: ${pageWorldJs.length} characters")
    }

    @Test
        @DisplayName("test isolated world scripts contain runtime")
    fun testIsolatedWorldScriptsContainRuntime() {
        val isolatedWorldJs = loader.getIsolatedWorldJs()

        // Isolated world should contain runtime components
        assertNotNull(isolatedWorldJs)
        assertTrue(isolatedWorldJs.isNotBlank(), "Isolated world scripts should not be empty")

        // Isolated world should contain runtime bridge and pulsar utils
        assertTrue(isolatedWorldJs.contains("__browser4_runtime__") ||
            isolatedWorldJs.contains("Browser4") ||
            isolatedWorldJs.contains("__pulsar"),
            "Isolated world should contain runtime components")

        // Isolated world should contain configs
        assertTrue(isolatedWorldJs.contains("__pulsar_CONFIGS") ||
            isolatedWorldJs.contains("CONFIGS"),
            "Isolated world should contain configuration")

        println("Isolated World JS size: ${isolatedWorldJs.length} characters")
    }

    @Test
        @DisplayName("test scripts are separated correctly")
    fun testScriptsAreSeparatedCorrectly() {
        val pageWorldJs = loader.getPageWorldJs()
        val isolatedWorldJs = loader.getIsolatedWorldJs()

        // They should be different
        assertNotEquals(pageWorldJs, isolatedWorldJs,
            "Page world and isolated world scripts should be different")

        // Both should have reasonable sizes
        assertTrue(pageWorldJs.length > 0, "Page world should not be empty")
        assertTrue(isolatedWorldJs.length > 0, "Isolated world should not be empty")

        // Note: stealth.js is actually quite large (minified stealth library)
        // so page world may be larger than isolated world
        println("Page World: ${pageWorldJs.length} chars (stealth patches)")
        println("Isolated World: ${isolatedWorldJs.length} chars (full runtime)")
    }

    @Test
        @DisplayName("test reload functionality")
    fun testReloadFunctionality() {
        val initial = loader.getPageWorldJs()
        loader.reload()
        val reloaded = loader.getPageWorldJs()

        // After reload, content should be the same
        assertEquals(initial, reloaded, "Reloaded scripts should match initial scripts")
    }

    @Test
        @DisplayName("test page world resources list")
    fun testPageWorldResourcesList() {
        val resources = DualWorldScriptLoader.PAGE_WORLD_RESOURCES

        assertTrue(resources.contains("js/stealth.js"),
            "Page world should include stealth.js")
        assertFalse(resources.contains("js/__pulsar_utils__.js"),
            "Page world should not include __pulsar_utils__.js")
    }

    @Test
        @DisplayName("test isolated world resources list")
    fun testIsolatedWorldResourcesList() {
        val resources = DualWorldScriptLoader.ISOLATED_WORLD_RESOURCES

        assertTrue(resources.contains("js/__pulsar_utils__.js"),
            "Isolated world should include __pulsar_utils__.js")
//        assertTrue(resources.contains("js/runtime_bridge.js"),
//            "Isolated world should include runtime_bridge.js")
        assertFalse(resources.contains("js/stealth.js"),
            "Isolated world should not include stealth.js")
    }
}
