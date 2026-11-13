package ai.platon.pulsar.osinput.geometry

import ai.platon.pulsar.osinput.input.MouseButton
import ai.platon.pulsar.osinput.input.SendInputCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * The arithmetic that links the two worlds, pinned down without a browser and without a
 * desktop: every case is pure math, so it runs on any OS and any JDK.
 */
class ViewportScreenMapperTest {

    // A 1200x900 CSS window at 150% display scale, placed at (100, 50) in DIP.
    private val frame = WindowFrame(left = 100.0, top = 50.0, width = 1200.0, height = 900.0, dpiScale = 1.5)

    // The page sees a 1200x800 viewport (100 CSS px of chrome at the top).
    private val metrics = ViewportMetrics(
        innerWidth = 1200.0,
        innerHeight = 800.0,
        outerWidth = 1216.0,
        outerHeight = 900.0,
        devicePixelRatio = 1.5,
    )

    @Test
    fun `window frame dip is converted to physical pixels`() {
        assertEquals(150.0, frame.physicalLeft)
        assertEquals(75.0, frame.physicalTop)
        assertEquals(1800.0, frame.physicalWidth)
        assertEquals(1350.0, frame.physicalHeight)
    }

    @Test
    fun `device scale folds page zoom and display scale together`() {
        assertEquals(1.5, ViewportScreenMapper.derived(frame, metrics).deviceScale)
    }

    @Test
    fun `viewport origin sits at the window origin when there is no chrome`() {
        val mapper = ViewportScreenMapper(frame, metrics, ChromeInsets.EMPTY)

        assertEquals(ScreenPoint(150.0, 75.0), mapper.viewportOrigin)
        assertEquals(1800.0, mapper.viewportRect.width)
        assertEquals(1200.0, mapper.viewportRect.height)
    }

    @Test
    fun `css point is shifted by the origin and scaled by the device ratio`() {
        val mapper = ViewportScreenMapper(frame, metrics, ChromeInsets.EMPTY)

        // (100, 50) CSS below the viewport corner -> (150, 75) device px offset
        assertEquals(ScreenPoint(300.0, 150.0), mapper.viewportToScreen(100.0, 50.0))
        assertEquals(ScreenPoint(300.0, 150.0), mapper.viewportToScreen(ScreenPoint(100.0, 50.0)))
    }

    @Test
    fun `chrome insets shift the whole viewport`() {
        // 150 device px of browser chrome between the window top and the page viewport
        val mapper = ViewportScreenMapper(frame, metrics, ChromeInsets(left = 0.0, top = 150.0))

        assertEquals(ScreenPoint(150.0, 225.0), mapper.viewportOrigin)
        assertEquals(ScreenPoint(300.0, 300.0), mapper.viewportToScreen(100.0, 50.0))
    }

    @Test
    fun `screen point maps back to the same css point`() {
        val mapper = ViewportScreenMapper(frame, metrics, ChromeInsets(left = 0.0, top = 150.0))

        val screen = mapper.viewportToScreen(437.0, 219.0)
        val back = mapper.screenToViewport(screen)

        assertEquals(437.0, back.x, 1e-9)
        assertEquals(219.0, back.y, 1e-9)
    }

    @Test
    fun `awt user space is the physical point divided by the display scale`() {
        val mapper = ViewportScreenMapper(frame, metrics, ChromeInsets.EMPTY)

        val screen = mapper.viewportToScreen(100.0, 50.0)
        assertEquals(ScreenPoint(200.0, 100.0), mapper.screenToAwt(screen, awtScale = 1.5))

        val roundTrip = mapper.awtToScreen(ScreenPoint(200.0, 100.0), awtScale = 1.5)
        assertEquals(screen, roundTrip)
    }

    @Test
    fun `derived insets make the viewport fit a plausible window`() {
        val mapper = ViewportScreenMapper.derived(frame, metrics)

        // 1350 - 1200 device px of vertical chrome, no horizontal chrome because
        // innerWidth == outerWidth in this fixture.
        assertEquals(150.0, mapper.insets.top, 1e-9)
        assertEquals(0.0, mapper.insets.left, 1e-9)
        assertTrue(mapper.isConsistent, "derived insets must produce a viewport inside the frame")
    }

    @Test
    fun `derived insets are rejected when the viewport is larger than the window`() {
        // outerWidth 1216 CSS < innerWidth ... impossible, but a bad probe must be visible.
        val bogus = metrics.copy(innerWidth = 2000.0)
        val mapper = ViewportScreenMapper.derived(frame, bogus)

        assertFalse(mapper.isConsistent, "a viewport wider than the window must not report as consistent")
    }

    @Test
    fun `one known reference point calibrates the insets exactly`() {
        // A click that landed on (310, 320) device px for a (100, 50) CSS element.
        val insets = ViewportScreenMapper.calibrate(
            frame,
            metrics,
            referenceCss = ScreenPoint(100.0, 50.0),
            referenceScreen = ScreenPoint(310.0, 320.0),
        )
        val mapper = ViewportScreenMapper(frame, metrics, insets)

        val mapped = mapper.viewportToScreen(100.0, 50.0)
        assertTrue(abs(mapped.x - 310.0) < 1e-9 && abs(mapped.y - 320.0) < 1e-9) {
            "calibration did not reproduce the reference: $mapped"
        }
    }

    @Test
    fun `spec parsers accept the strings the plugin tools pass around`() {
        assertEquals(frame, WindowFrame.parse("100,50,1200,900,1.5"))
        assertEquals(metrics, ViewportMetrics.parse("1200,800,1216,900,1.5"))
        assertEquals(ChromeInsets(left = 1.0, top = 2.0), ChromeInsets.parse("1,2,0,0"))
    }

    @Test
    fun `send input normalises device pixels to the win32 absolute range`() {
        val desktop = ScreenRect(0.0, 0.0, 1920.0, 1080.0)

        assertEquals(0, SendInputCoordinates.horizontal(0.0, desktop))
        assertEquals(SendInputCoordinates.MAX, SendInputCoordinates.horizontal(1919.0, desktop))
        // The centre of the desktop is close to the middle of 0..65535.
        assertTrue(abs(SendInputCoordinates.horizontal(959.5, desktop) - 32768) < 32)

        // A second monitor to the left of the primary one: negative coordinates are legal.
        val dual = ScreenRect(-1920.0, 0.0, 3840.0, 1080.0)
        assertEquals(0, SendInputCoordinates.horizontal(-1920.0, dual))
        assertEquals(SendInputCoordinates.MAX, SendInputCoordinates.horizontal(1919.0, dual))

        // Out-of-desktop points are clamped instead of wrapping to the other edge.
        assertEquals(0, SendInputCoordinates.horizontal(-5000.0, desktop))
        assertEquals(SendInputCoordinates.MAX, SendInputCoordinates.horizontal(5000.0, desktop))
    }

    @Test
    fun `mouse button parsing is case insensitive and rejects typos`() {
        assertEquals(MouseButton.LEFT, MouseButton.parse(null))
        assertEquals(MouseButton.LEFT, MouseButton.parse(""))
        assertEquals(MouseButton.LEFT, MouseButton.parse("L"))
        assertEquals(MouseButton.RIGHT, MouseButton.parse("Right"))
        assertEquals(MouseButton.MIDDLE, MouseButton.parse("middle"))

        assertThrows<IllegalArgumentException> { MouseButton.parse("scroll") }
    }
}
