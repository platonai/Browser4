package ai.platon.browser4.common.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [Box] / [BoxSpace] / [BoxContract] — the typed coordinate
 * contract that keeps document-space and viewport-space numbers apart.
 *
 * The bug these guard against is silent: a bare rect carries no space, so
 * feeding a document-space box to `mousemove` (viewport space) offsets the
 * pointer by `scrollY` without any error.
 */
@DisplayName("Box coordinate contract")
class BoxTest {

    @Test
    @DisplayName("parses the legacy space-separated vi format")
    fun parsesLegacySpaceSeparatedFormat() {
        val box = Box.parse("123.5 456.7 200.3 50.8", BoxSpace.DOCUMENT)

        assertNotNull(box)
        assertEquals(BoxSpace.DOCUMENT, box!!.space, "the caller declares the space; parsing must not guess it")
        assertEquals(123.5, box.x, 0.001)
        assertEquals(456.7, box.y, 0.001)
        assertEquals(200.3, box.width, 0.001)
        assertEquals(50.8, box.height, 0.001)
    }

    @Test
    @DisplayName("parses the compact comma-separated vi format")
    fun parsesCompactCommaFormat() {
        val box = Box.parse("0,0,1920,1080", BoxSpace.DOCUMENT)

        assertNotNull(box)
        assertEquals(1920.0, box!!.width, 0.001)
        assertEquals(1080.0, box.height, 0.001)
    }

    @Test
    @DisplayName("parses the base-36 vi format")
    fun parsesBase36Format() {
        // 3g = 124, cp = 457, 5k = 200, 1f = 51
        val box = Box.parse("3g,cp,5k,1f", BoxSpace.DOCUMENT)

        assertNotNull(box)
        assertEquals(124.0, box!!.x, 0.001)
        assertEquals(457.0, box.y, 0.001)
        assertEquals(200.0, box.width, 0.001)
        assertEquals(51.0, box.height, 0.001)
    }

    @Test
    @DisplayName("returns null for a box that cannot be parsed")
    fun returnsNullForUnparseableBox() {
        assertNull(Box.parse("", BoxSpace.DOCUMENT))
        assertNull(Box.parse("1 2 3", BoxSpace.DOCUMENT), "three numbers are not a box")
        // "a b c d" would be a *valid* base-36 box (10, 11, 12, 13); an illegal
        // digit is what makes the value unparseable.
        assertNull(Box.parse("0,0,#,3", BoxSpace.DOCUMENT))
    }

    @Test
    @DisplayName("converts between document and viewport space with the scroll offset")
    fun convertsBetweenSpaces() {
        val document = Box(BoxSpace.DOCUMENT, 100.0, 1200.0, 50.0, 40.0)

        val viewport = document.toViewport(scrollX = 0.0, scrollY = 800.0)

        assertEquals(BoxSpace.VIEWPORT, viewport.space)
        assertEquals(100.0, viewport.x, 0.001)
        assertEquals(400.0, viewport.y, 0.001)
        assertEquals(50.0, viewport.width, 0.001)
        assertEquals(document, viewport.toDocument(0.0, 800.0), "a round trip must restore the original box")
    }

    @Test
    @DisplayName("refuses to convert body-relative data instead of guessing an offset")
    fun refusesBodyRelativeConversion() {
        val legacy = Box(BoxSpace.BODY, 0.0, 1200.0, 50.0, 40.0)

        assertThrows(IllegalArgumentException::class.java) { legacy.toViewport(0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { legacy.toDocument(0.0, 0.0) }
    }

    @Test
    @DisplayName("detects whether a box overlaps the viewport")
    fun detectsViewportOverlap() {
        val visible = Box(BoxSpace.VIEWPORT, 10.0, 10.0, 20.0, 20.0)
        val above = Box(BoxSpace.VIEWPORT, 10.0, -100.0, 20.0, 20.0)
        val zeroSize = Box(BoxSpace.VIEWPORT, 10.0, 10.0, 0.0, 20.0)

        assertTrue(visible.overlapsViewport(1280.0, 900.0))
        assertFalse(above.overlapsViewport(1280.0, 900.0), "a box entirely above the viewport is not visible")
        assertFalse(zeroSize.overlapsViewport(1280.0, 900.0), "a zero-width box cannot be observed")
    }

    @Test
    @DisplayName("emits the wire fields a consumer needs to interpret the numbers")
    fun emitsWireFields() {
        val wire = Box(BoxSpace.VIEWPORT, 29.0, 57.0, 93.0, 33.0).toWireMap()

        assertEquals(29.0, wire["x"])
        assertEquals(57.0, wire["y"])
        assertEquals(93.0, wire["width"])
        assertEquals(33.0, wire["height"])
        assertEquals("viewport", wire[BoxContract.FIELD_SPACE])
    }

    @Test
    @DisplayName("a payload without a space field is legacy body-relative data")
    fun missingSpaceMeansLegacyBodySpace() {
        assertEquals(
            BoxSpace.BODY,
            BoxContract.spaceOf(mapOf("box" to "0 1200 100 40")),
            "schema-1 payloads must be read as body-relative, never as the current space"
        )
        assertEquals(
            BoxSpace.DOCUMENT,
            BoxContract.spaceOf(mapOf(BoxContract.FIELD_SPACE to "document"))
        )
        assertEquals(
            BoxSpace.VIEWPORT,
            BoxContract.spaceOf(mapOf(BoxContract.FIELD_SPACE to "VIEWPORT"))
        )
    }

    @Test
    @DisplayName("formats a box the way the runtime writes vi")
    fun formatsLikeTheRuntime() {
        assertEquals("0 1200 100 40", Box(BoxSpace.DOCUMENT, 0.4, 1200.2, 100.0, 40.0).format())
    }
}
