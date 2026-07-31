package ai.platon.browser4.api.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for [ClientInfo] default values.
 *
 * Verifies that default viewport/screen dimensions are 0 ("not measured yet")
 * rather than VIEWPORT compile-time constants that silently diverge from
 * the actual browser viewport after resize().
 */
@DisplayName("ClientInfo defaults")
class ClientInfoDefaultsTest {

    @Nested
    @DisplayName("Default constructor produces 'not measured' sentinels")
    inner class DefaultConstructor {

        @Test
        @DisplayName("viewportWidth defaults to 0")
        fun viewportWidthDefaultsToZero() {
            val info = ClientInfo()
            assertEquals(0, info.viewportWidth,
                "viewportWidth should default to 0 (not measured) rather than a VIEWPORT constant")
        }

        @Test
        @DisplayName("viewportHeight defaults to 0")
        fun viewportHeightDefaultsToZero() {
            val info = ClientInfo()
            assertEquals(0, info.viewportHeight,
                "viewportHeight should default to 0 (not measured) rather than a VIEWPORT constant")
        }

        @Test
        @DisplayName("screenWidth defaults to 0")
        fun screenWidthDefaultsToZero() {
            val info = ClientInfo()
            assertEquals(0, info.screenWidth)
        }

        @Test
        @DisplayName("screenHeight defaults to 0")
        fun screenHeightDefaultsToZero() {
            val info = ClientInfo()
            assertEquals(0, info.screenHeight)
        }
    }

    @Nested
    @DisplayName("Explicit construction still works")
    inner class ExplicitConstruction {

        @Test
        @DisplayName("all viewport fields can be set explicitly")
        fun explicitViewportValues() {
            val info = ClientInfo(
                viewportWidth = 1024,
                viewportHeight = 768,
                screenWidth = 1920,
                screenHeight = 1080,
            )
            assertEquals(1024, info.viewportWidth)
            assertEquals(768, info.viewportHeight)
            assertEquals(1920, info.screenWidth)
            assertEquals(1080, info.screenHeight)
        }
    }
}
