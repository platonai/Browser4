package ai.platon.pulsar.rest.mcp.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultArgumentNormalizer] covering the argument
 * transformations that batch and direct MCP tool calls rely on.
 */
class ArgumentNormalizersTest {

    private val normalizer = DefaultArgumentNormalizer()

    // =========================================================================
    // ref → selector mapping (used by fill, type, press, click, etc.)
    // =========================================================================

    @Nested
    @DisplayName("ref → selector mapping")
    inner class RefToSelectorMapping {

        @Test
        @DisplayName("ref is renamed to selector when selector is absent")
        fun refIsRenamedToSelectorWhenAbsent() {
            val args = mutableMapOf<String, Any?>(
                "ref" to "#my-input",
                "text" to "hello"
            )
            val result = normalizer.normalize("fill", args)

            assertFalse(result.containsKey("ref"), "ref key should be removed")
            assertEquals("#my-input", result["selector"], "ref value should become selector")
            assertEquals("hello", result["text"], "text should be preserved")
        }

        @Test
        @DisplayName("ref is NOT renamed when selector is already present")
        fun refIsNotRenamedWhenSelectorPresent() {
            val args = mutableMapOf<String, Any?>(
                "ref" to "#ignored-ref",
                "selector" to "#explicit-selector",
                "text" to "hello"
            )
            val result = normalizer.normalize("fill", args)

            assertEquals("#explicit-selector", result["selector"], "existing selector should win")
            assertEquals("hello", result["text"])
        }

        @Test
        @DisplayName("sessionId is stripped from arguments")
        fun sessionIdIsStripped() {
            val args = mutableMapOf<String, Any?>(
                "ref" to "#my-input",
                "text" to "hello",
                "sessionId" to "swarm-session-1"
            )
            val result = normalizer.normalize("fill", args)

            assertFalse(result.containsKey("sessionId"), "sessionId should be removed")
            assertEquals("#my-input", result["selector"])
            assertEquals("hello", result["text"])
        }

        @Test
        @DisplayName("snake_case keys are converted to camelCase")
        fun snakeCaseKeysAreConvertedToCamelCase() {
            val args = mutableMapOf<String, Any?>(
                "start_ref" to "#source",
                "end_ref" to "#target"
            )
            val result = normalizer.normalize("drag", args)

            assertFalse(result.containsKey("start_ref"), "snake_case key 'start_ref' should be removed")
            assertFalse(result.containsKey("end_ref"), "snake_case key 'end_ref' should be removed")
        }
    }

    // =========================================================================
    // startRef / endRef → sourceSelector / targetSelector mapping
    // =========================================================================

    @Nested
    @DisplayName("startRef / endRef mapping")
    inner class StartRefEndRefMapping {

        @Test
        @DisplayName("startRef is renamed to sourceSelector")
        fun startRefIsRenamedToSourceSelector() {
            val args = mutableMapOf<String, Any?>(
                "startRef" to "#drag-source",
                "endRef" to "#drag-target"
            )
            val result = normalizer.normalize("drag", args)

            assertEquals("#drag-source", result["sourceSelector"])
            assertEquals("#drag-target", result["targetSelector"])
        }
    }

    // =========================================================================
    // modifiers → modifier mapping
    // =========================================================================

    @Nested
    @DisplayName("modifiers → modifier mapping")
    inner class ModifiersMapping {

        @Test
        @DisplayName("modifiers list first element becomes modifier string")
        fun modifiersListFirstElementBecomesModifier() {
            val args = mutableMapOf<String, Any?>(
                "modifiers" to listOf("Shift", "Control")
            )
            val result = normalizer.normalize("click", args)

            assertEquals("Shift", result["modifier"], "first modifier should become 'modifier'")
            assertFalse(result.containsKey("modifiers"))
        }

        @Test
        @DisplayName("empty modifiers list is ignored")
        fun emptyModifiersListIsIgnored() {
            val args = mutableMapOf<String, Any?>(
                "modifiers" to emptyList<String>()
            )
            val result = normalizer.normalize("click", args)

            assertFalse(result.containsKey("modifier"))
            assertFalse(result.containsKey("modifiers"))
        }

        @Test
        @DisplayName("modifiers string becomes modifier string")
        fun modifiersStringBecomesModifier() {
            val args = mutableMapOf<String, Any?>(
                "modifiers" to "Shift"
            )
            val result = normalizer.normalize("click", args)

            assertEquals("Shift", result["modifier"], "string modifiers should become 'modifier'")
            assertFalse(result.containsKey("modifiers"))
        }
    }

    // =========================================================================
    // No-op for already-canonical arguments
    // =========================================================================

    @Test
    @DisplayName("already-canonical arguments pass through unchanged")
    fun canonicalArgumentsPassThrough() {
        val args = mutableMapOf<String, Any?>(
            "selector" to "#my-input",
            "text" to "hello",
            "submit" to true
        )
        val result = normalizer.normalize("fill", args)

        assertEquals("#my-input", result["selector"])
        assertEquals("hello", result["text"])
        assertEquals(true, result["submit"])
        assertEquals(3, result.size)
    }

    // =========================================================================
    // TabArgumentNormalizer — id → tabId mapping
    // =========================================================================

    @Nested
    @DisplayName("TabArgumentNormalizer")
    inner class TabArgumentNormalizerTests {

        private val tabNormalizer = TabArgumentNormalizer()

        @Test
        @DisplayName("id is mapped to tabId when tabId is absent")
        fun idIsMappedToTabIdWhenAbsent() {
            val args = mutableMapOf<String, Any?>(
                "id" to "DEADBEEF000000000000000000000000"
            )
            val result = tabNormalizer.normalize("tab_select", args)

            assertFalse(result.containsKey("id"), "id key should be removed")
            assertEquals(
                "DEADBEEF000000000000000000000000",
                result["tabId"],
                "id value should become tabId"
            )
        }

        @Test
        @DisplayName("id is NOT mapped when tabId is already present")
        fun idIsNotMappedWhenTabIdPresent() {
            val args = mutableMapOf<String, Any?>(
                "id" to "old-legacy-id",
                "tabId" to "explicit-tab-id"
            )
            val result = tabNormalizer.normalize("tab_select", args)

            assertFalse(result.containsKey("id"), "id key should be removed")
            assertEquals(
                "explicit-tab-id",
                result["tabId"],
                "existing tabId should be preserved"
            )
        }

        @Test
        @DisplayName("no-op when neither id nor tabId is present")
        fun noOpWhenNeitherIdNorTabIdPresent() {
            val args = mutableMapOf<String, Any?>(
                "index" to 0
            )
            val result = tabNormalizer.normalize("tab_select", args)

            assertEquals(0, result["index"])
            assertFalse(result.containsKey("id"))
            assertFalse(result.containsKey("tabId"))
        }

        @Test
        @DisplayName("tabId is preserved without id present")
        fun tabIdPreservedWithoutId() {
            val args = mutableMapOf<String, Any?>(
                "tabId" to "some-guid-here"
            )
            val result = tabNormalizer.normalize("tab_close", args)

            assertEquals("some-guid-here", result["tabId"])
            assertFalse(result.containsKey("id"))
        }
    }
}
