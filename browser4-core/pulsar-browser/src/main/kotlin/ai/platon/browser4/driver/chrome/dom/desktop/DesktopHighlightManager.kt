package ai.platon.browser4.driver.chrome.dom.desktop

import ai.platon.browser4.driver.chrome.dom.model.InteractiveDOMTreeNodeList
import ai.platon.pulsar.common.getLogger
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Desktop GUI highlighter that draws rectangles directly on screen using a transparent overlay window.
 *
 * Notes/Assumptions:
 * - Coordinates in [InteractiveDOMTreeNodeList] are treated as absolute screen coordinates.
 *   If they are viewport/page coordinates, provide offsets via [offsetX]/[offsetY] when creating this manager.
 * - The overlay is always-on-top and non-focusable, but it may still intercept mouse events on some platforms
 *   (true click-through would require platform-specific native flags and is out-of-scope here).
 */
class DesktopHighlightManager(
    private val offsetX: Double = 0.0,
    private val offsetY: Double = 0.0,
    private val scale: Double = 1.0,
) {
    private val logger = getLogger(this)

    // Singleton overlay shared by the JVM. Lazily created on first use.
    private val overlay: OverlayWindow by lazy { OverlayWindow() }

    suspend fun addHighlights(elements: InteractiveDOMTreeNodeList) {
        try {
            val nodes = elements.nodes
            if (nodes.isEmpty()) {
                removeHighlights(elements)
                return
            }

            // Build rectangles
            val rects = nodes.mapNotNull { n ->
                val r = n.absoluteBounds ?: n.bounds ?: return@mapNotNull null
                val w = (r.width ?: 0.0) * scale
                val h = (r.height ?: 0.0) * scale
                if (w <= 0.0 || h <= 0.0) return@mapNotNull null
                val x = (r.x ?: 0.0) * scale + offsetX
                val y = (r.y ?: 0.0) * scale + offsetY
                HighlightRect(
                    x.roundToInt(),
                    y.roundToInt(),
                    w.roundToInt(),
                    h.roundToInt(),
                    // Try to show backend_node_id if present in locator "frameIndex,backendNodeId"
                    label = runCatching {
                        val loc = n.backendNodeId
                        if (!loc.isNullOrBlank()) {
                            val parts = loc.split(',')
                            if (parts.size >= 2) parts[1].trim() else null
                        } else null
                    }.getOrNull()
                )
            }

            if (rects.isEmpty()) {
                removeHighlights(elements)
                return
            }

            // Push to overlay on EDT
            runOnEdt {
                overlay.showRects(rects)
            }
        } catch (e: Exception) {
            logger.warn("Desktop addHighlights failed | err={}", e.toString())
        }
    }

    suspend fun removeHighlights(@Suppress("UNUSED_PARAMETER") elements: InteractiveDOMTreeNodeList) {
        runOnEdt {
            overlay.clear()
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }

    // --- Overlay implementation ---

    private data class HighlightRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val label: String? = null,
        val color: Color = Color(0x4A, 0x90, 0xE2)
    )

    private class OverlayWindow : JWindow() {
        private val layer = OverlayLayer()

        init {
            isAlwaysOnTop = true
            isFocusable = false
            try {
                // Keep the window from becoming focusable (Window API)
                this.setFocusableWindowState(false)
            } catch (_: Throwable) {
                // ignore
            }
            try {
                background = Color(0, 0, 0, 0)
            } catch (_: Throwable) {
                // Fallback if per-pixel translucency isn't supported
                background = Color(0, 0, 0, 1)
            }
            contentPane = layer
            packAndPlaceOverVirtualBounds()

            addWindowListener(object : WindowAdapter() {
                override fun windowDeactivated(e: WindowEvent?) {
                    // Keep always-on-top behavior stable
                    this@OverlayWindow.isAlwaysOnTop = true
                }
            })
        }

        fun showRects(rects: List<HighlightRect>) {
            if (!isVisible) {
                packAndPlaceOverVirtualBounds()
                isVisible = true
            }
            layer.setRects(rects)
        }

        fun clear() {
            layer.setRects(emptyList())
            isVisible = false
        }

        private fun packAndPlaceOverVirtualBounds() {
            // Cover the union of all screens (virtual desktop bounds)
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val union = ge.screenDevices.fold(Rectangle()) { acc, dev ->
                val b = dev.defaultConfiguration.bounds
                if (acc.isEmpty) Rectangle(b) else acc.apply { add(b) }
            }
            setBounds(union)
        }
    }

    private class OverlayLayer : JComponent() {
        @Volatile
        private var rects: List<HighlightRect> = emptyList()

        private val labelFont = Font("Monospaced", Font.BOLD, 12)
        private val dashed = BasicStroke(
            2f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER,
            10f,
            floatArrayOf(6f, 6f),
            0f
        )

        fun setRects(r: List<HighlightRect>) {
            rects = r
            revalidate()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver

            for ((x, y, w, h, label, color) in rects) {
                g2.color = color
                g2.stroke = dashed
                // Outline rectangle
                g2.drawRect(x, y, w, h)

                // Label background
                if (!label.isNullOrBlank()) {
                    val fm = g2.getFontMetrics(labelFont)
                    val text = label
                    val padX = 6
                    val padY = 2
                    val textW = fm.stringWidth(text)
                    val textH = fm.height
                    val bx = x
                    val by = (y - textH - 4).coerceAtLeast(0)

                    // Box background
                    val bg = Color(color.red, color.green, color.blue, 220)
                    g2.color = bg
                    g2.fillRect(bx, by, textW + padX * 2, textH + padY)

                    // Text
                    g2.color = Color.WHITE
                    g2.font = labelFont
                    g2.drawString(text, bx + padX, by + fm.ascent)
                }
            }
        }
    }
}
