package ai.platon.browser4.chrome.protocol

import ai.platon.browser4.api.BrowserProtocol
import ai.platon.cdt.kt.protocol.types.page.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Tests for [ScreenshotHandler] viewport save/restore behavior during
 * full-page screenshots.
 *
 * Verifies that after a full-page capture the original viewport dimensions
 * are restored via Emulation.setDeviceMetricsOverride rather than just
 * cleared via clearDeviceMetricsOverride.
 */
@DisplayName("ScreenshotHandler viewport save/restore")
class ScreenshotHandlerViewportTest {

    @Mock
    private lateinit var bp: BrowserProtocol

    @Mock
    private lateinit var page: PageHandler

    private lateinit var handler: ScreenshotHandler

    // Default original viewport dimensions (simulating 1920x1080)
    private val originalClientWidth = 1920.0
    private val originalClientHeight = 1080.0
    private val originalScale = 1.0

    // Full-page content dimensions
    private val contentWidth = 1920.0
    private val contentHeight = 5000.0

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        // Wire up mock BrowserProtocol.isOpen for the active check
        whenever(bp.isOpen).thenReturn(true)

        // Wire up mock layout metrics with both viewport and content size
        val metrics = mock<LayoutMetrics>()
        val cssViewport = mock<VisualViewport>()
        val contentSize = mock<ai.platon.cdt.kt.protocol.types.dom.Rect>()

        whenever(cssViewport.clientWidth).thenReturn(originalClientWidth)
        whenever(cssViewport.clientHeight).thenReturn(originalClientHeight)
        whenever(cssViewport.scale).thenReturn(originalScale)

        whenever(contentSize.width).thenReturn(contentWidth)
        whenever(contentSize.height).thenReturn(contentHeight)

        whenever(metrics.cssVisualViewport).thenReturn(cssViewport)
        whenever(metrics.contentSize).thenReturn(contentSize)

        wheneverBlocking { bp.getLayoutMetrics() }.thenReturn(metrics)

        // Wire up captureScreenshot to return a mock base64 string
        wheneverBlocking {
            bp.captureScreenshot(
                format = anyOrNull(),
                quality = anyOrNull(),
                clip = anyOrNull(),
                fromSurface = anyOrNull(),
                captureBeyondViewport = anyOrNull(),
            )
        }.thenReturn("base64mockdata")

        handler = ScreenshotHandler(page, bp)
    }

    @Nested
    @DisplayName("Full-page screenshot viewport lifecycle")
    inner class FullPageScreenshot {

        @Test
        @DisplayName("saves original viewport before expanding for full-page capture")
        fun savesOriginalViewportBeforeExpanding() = runBlocking {
            handler.screenshot(fullPage = true)

            // Should have read original metrics
            verify(bp).getLayoutMetrics()
            Unit  // suppress non-Unit return from verify().getLayoutMetrics()
        }

        @Test
        @DisplayName("expands viewport to content size for full-page capture")
        fun expandsViewportToContentSize() = runBlocking {
            handler.screenshot(fullPage = true)

            // First setDeviceMetricsOverride: expand to content size
            verify(bp).setDeviceMetricsOverride(
                mobile = eq(false),
                width = eq(contentWidth.toInt()),
                height = eq(contentHeight.toInt()),
                deviceScaleFactor = eq(1.0),
                screenWidth = eq(contentWidth.toInt()),
                screenHeight = eq(contentHeight.toInt()),
            )
        }

        @Test
        @DisplayName("restores original viewport dimensions after full-page capture")
        fun restoresOriginalViewportAfterCapture() = runBlocking {
            handler.screenshot(fullPage = true)

            // Second setDeviceMetricsOverride: restore original viewport
            verify(bp).setDeviceMetricsOverride(
                mobile = eq(false),
                width = eq(originalClientWidth.toInt()),
                height = eq(originalClientHeight.toInt()),
                deviceScaleFactor = eq(originalScale),
                screenWidth = eq(originalClientWidth.toInt()),
                screenHeight = eq(originalClientHeight.toInt()),
            )
        }

        @Test
        @DisplayName("does NOT call clearDeviceMetricsOverride (explicit restore instead)")
        fun doesNotCallClearDeviceMetricsOverride() = runBlocking {
            handler.screenshot(fullPage = true)

            // The old code used clearDeviceMetricsOverride() — we should
            // never call it in the new restore path
            verify(bp, never()).clearDeviceMetricsOverride()
        }

        @Test
        @DisplayName("calls setDeviceMetricsOverride exactly twice: expand + restore")
        fun callsSetDeviceMetricsOverrideExactlyTwice() = runBlocking {
            handler.screenshot(fullPage = true)

            verify(bp, times(2)).setDeviceMetricsOverride(
                mobile = any(), width = any(), height = any(),
                deviceScaleFactor = any(), screenWidth = anyOrNull(), screenHeight = anyOrNull(),
            )
        }

        @Test
        @DisplayName("restore dimensions match the original cssVisualViewport from getLayoutMetrics")
        fun restoreDimensionsMatchOriginalViewport() = runBlocking {
            handler.screenshot(fullPage = true)

            val inOrder = inOrder(bp)

            // Phase 1: expand
            inOrder.verify(bp).setDeviceMetricsOverride(
                mobile = eq(false),
                width = eq(contentWidth.toInt()),
                height = eq(contentHeight.toInt()),
                deviceScaleFactor = eq(1.0),
                screenWidth = eq(contentWidth.toInt()),
                screenHeight = eq(contentHeight.toInt()),
            )

            // Phase 2: restore — width/height must match original cssVisualViewport
            inOrder.verify(bp).setDeviceMetricsOverride(
                mobile = eq(false),
                width = eq(originalClientWidth.toInt()),
                height = eq(originalClientHeight.toInt()),
                deviceScaleFactor = eq(originalScale),
                screenWidth = eq(originalClientWidth.toInt()),
                screenHeight = eq(originalClientHeight.toInt()),
            )
        }
    }

    @Nested
    @DisplayName("Non-full-page screenshot does not touch viewport")
    inner class NonFullPageScreenshot {

        @Test
        @DisplayName("standard screenshot does not call setDeviceMetricsOverride")
        fun standardScreenshotDoesNotCallSetDeviceMetricsOverride() = runBlocking {
            handler.screenshot(fullPage = false)

            verify(bp, never()).setDeviceMetricsOverride(any(), any(), any(), any(), anyOrNull(), anyOrNull())
        }

        @Test
        @DisplayName("standard screenshot returns capture result directly")
        fun standardScreenshotReturnsCaptureResult() = runBlocking {
            val result = handler.screenshot(fullPage = false)
            assert(result == "base64mockdata")
        }
    }
}
