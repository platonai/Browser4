package ai.platon.browser4.driver.chrome.dom.util

import ai.platon.browser4.driver.chrome.dom.model.DOMRect
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin-based highlighting utility for drawing bounding boxes on screenshots.
 * Mirrors the behavior of python_highlights.py.
 */
object HighlightUtils {
    // Color scheme for different element types
    private val ELEMENT_COLORS = mapOf(
        "button" to Color(0xFF, 0x6B, 0x6B),     // Red for buttons
        "input" to Color(0x4E, 0xCD, 0xC4),      // Teal for inputs
        "select" to Color(0x45, 0xB7, 0xD1),     // Blue for dropdowns
        "a" to Color(0x96, 0xCE, 0xB4),          // Green for links
        "textarea" to Color(0xFF, 0x8C, 0x42),   // Orange for text areas
        "default" to Color(0xDD, 0xA0, 0xDD)     // Light purple for others
    )

    /**
     * Create a highlighted screenshot with bounding boxes around interactive elements.
     *
     * @param screenshotB64 Base64 encoded PNG screenshot.
     * @param selectorMap Map of element keys (usually backendNodeId) to DOM nodes containing absolute positions.
     * @param devicePixelRatio Device pixel ratio used to scale CSS pixels to device pixels.
     * @param filterHighlightIds Whether to filter index label display based on meaningful text length.
     * @return Base64 encoded PNG with overlays. On error, returns the original screenshotB64.
     */
    fun createHighlightedScreenshot(
        screenshotB64: String,
        selectorMap: Map<String, MergedDOMTreeNode>,
        devicePixelRatio: Double,
        filterHighlightIds: Boolean,
    ): String {
        return try {
            val image = decodeImage(screenshotB64)
            if (image == null) return screenshotB64

            val argb = ensureARGB(image)
            val g = argb.createGraphics()
            try {
                enableQuality(g)
                val imgWidth = argb.width
                val imgHeight = argb.height

                val font = selectFont(g, 12)

                // Process sequentially (Graphics2D is not thread-safe)
                selectorMap.forEach { (key, node) ->
                    processElementHighlight(
                        elementKey = key,
                        element = node,
                        g = g,
                        devicePixelRatio = devicePixelRatio,
                        font = font,
                        filterHighlightIds = filterHighlightIds,
                        imageSize = Dimension(imgWidth, imgHeight)
                    )
                }
            } finally {
                g.dispose()
            }

            encodeImage(argb) ?: screenshotB64
        } catch (e: Exception) {
            // Fallback to original image on error
            screenshotB64
        }
    }

    private fun processElementHighlight(
        elementKey: String,
        element: MergedDOMTreeNode,
        g: Graphics2D,
        devicePixelRatio: Double,
        font: Font,
        filterHighlightIds: Boolean,
        imageSize: Dimension,
    ) {
        val bounds: DOMRect = element.absolutePosition ?: return

        // Scale CSS pixels to device pixels
        var x1 = (bounds.x * devicePixelRatio).toInt()
        var y1 = (bounds.y * devicePixelRatio).toInt()
        var x2 = ((bounds.x + bounds.width) * devicePixelRatio).toInt()
        var y2 = ((bounds.y + bounds.height) * devicePixelRatio).toInt()

        // Clamp to image bounds
        val imgW = imageSize.width
        val imgH = imageSize.height
        x1 = max(0, min(x1, imgW))
        y1 = max(0, min(y1, imgH))
        x2 = max(x1, min(x2, imgW))
        y2 = max(y1, min(y2, imgH))

        // Skip too small or invalid boxes
        if (x2 - x1 < 2 || y2 - y1 < 2) return

        val tagName = element.nodeName.ifBlank { "DIV" }.lowercase()
        val elementType = element.attributes["type"]
        val color = getElementColor(tagName, elementType)

        val backendNodeId = element.backendNodeId
        val indexText = if (backendNodeId != null) {
            if (filterHighlightIds) {
                val meaningful = runCatching { element.textContent() }.getOrNull() ?: ""
                if (meaningful.trim().length < 3) backendNodeId.toString() else null
            } else backendNodeId.toString()
        } else null

        drawEnhancedBoundingBoxWithText(
            g = g,
            rect = Rectangle(x1, y1, x2 - x1, y2 - y1),
            color = color,
            text = indexText,
            baseFont = font,
            elementTag = tagName,
            imageSize = imageSize,
            devicePixelRatio = devicePixelRatio
        )
    }

    private fun getElementColor(tagName: String, elementType: String?): Color {
        if (tagName == "input" && elementType != null) {
            if (elementType.equals("button", true) || elementType.equals("submit", true)) {
                return ELEMENT_COLORS["button"] ?: ELEMENT_COLORS.getValue("default")
            }
        }
        return ELEMENT_COLORS[tagName] ?: ELEMENT_COLORS.getValue("default")
    }

    private fun drawEnhancedBoundingBoxWithText(
        g: Graphics2D,
        rect: Rectangle,
        color: Color,
        text: String?,
        baseFont: Font,
        elementTag: String,
        imageSize: Dimension,
        devicePixelRatio: Double,
    ) {
        val (x, y, w, h) = arrayOf(rect.x, rect.y, rect.width, rect.height)

        // Dashed border parameters (approximate Python behavior)
        val dashLength = 4
        val gapLength = 8
        val lineWidth = 2

        g.color = color
        g.stroke = BasicStroke(lineWidth.toFloat())

        // Helper to draw dashed line
        fun drawDashedLine(x1: Int, y1: Int, x2: Int, y2: Int) {
            if (x1 == x2) {
                var yy = y1
                while (yy < y2) {
                    val end = min(yy + dashLength, y2)
                    g.drawLine(x1, yy, x1, end)
                    yy += dashLength + gapLength
                }
            } else {
                var xx = x1
                while (xx < x2) {
                    val end = min(xx + dashLength, x2)
                    g.drawLine(xx, y1, end, y1)
                    xx += dashLength + gapLength
                }
            }
        }

        // Top, Right, Bottom, Left
        drawDashedLine(x, y, x + w, y)
        drawDashedLine(x + w, y, x + w, y + h)
        drawDashedLine(x + w, y + h, x, y + h)
        drawDashedLine(x, y + h, x, y)

        if (!text.isNullOrBlank()) {
            try {
                val cssWidth = imageSize.width
                val baseFontSize = max(10, min(20, (cssWidth * 0.01).toInt()))
                val font = baseFont.deriveFont(Font.BOLD, baseFontSize.toFloat())
                g.font = font
                val fm = g.fontMetrics

                val textWidth = fm.stringWidth(text)
                val textHeight = fm.ascent // approximate height

                val padding = max(4, min(10, (cssWidth * 0.005).toInt()))
                val containerWidth = textWidth + padding * 2
                val containerHeight = textHeight + padding * 2

                val bgX1 = x + (w - containerWidth) / 2
                val bgY1 = if (w < 60 || h < 30) max(0, y - containerHeight - 5) else y + 2
                var boxX1 = bgX1
                var boxY1 = bgY1
                var boxX2 = boxX1 + containerWidth
                var boxY2 = boxY1 + containerHeight

                var textX = boxX1 + (containerWidth - textWidth) / 2
                var textY = boxY1 + padding + fm.ascent - (fm.ascent - fm.descent) / 2

                // Clamp within image bounds
                if (boxX1 < 0) {
                    val off = -boxX1
                    boxX1 += off; boxX2 += off; textX += off
                }
                if (boxY1 < 0) {
                    val off = -boxY1
                    boxY1 += off; boxY2 += off; textY += off
                }
                if (boxX2 > imageSize.width) {
                    val off = boxX2 - imageSize.width
                    boxX1 -= off; boxX2 -= off; textX -= off
                }
                if (boxY2 > imageSize.height) {
                    val off = boxY2 - imageSize.height
                    boxY1 -= off; boxY2 -= off; textY -= off
                }

                // Draw filled rectangle and border
                g.color = color
                g.fillRect(boxX1, boxY1, boxX2 - boxX1, boxY2 - boxY1)
                g.color = Color.WHITE
                g.stroke = BasicStroke(2f)
                g.drawRect(boxX1, boxY1, boxX2 - boxX1, boxY2 - boxY1)

                // Draw white text
                g.color = Color.WHITE
                g.drawString(text, textX, textY)
            } catch (_: Exception) {
                // Ignore overlay text failures to keep robust
            }
        }
    }

    // --- Helpers ---

    private fun decodeImage(b64: String): BufferedImage? = try {
        val bytes = Base64.getDecoder().decode(b64)
        ImageIO.read(ByteArrayInputStream(bytes))
    } catch (e: Exception) { null }

    private fun ensureARGB(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val dst = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        try {
            enableQuality(g)
            g.drawImage(src, 0, 0, null)
        } finally { g.dispose() }
        return dst
    }

    private fun encodeImage(img: BufferedImage): String? = try {
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        baos.flush()
        val b64 = Base64.getEncoder().encodeToString(baos.toByteArray())
        baos.close()
        b64
    } catch (e: Exception) { null }

    private fun selectFont(g: Graphics2D, size: Int): Font {
        // Try a few common fonts; fall back to default
        val candidates = listOf("DejaVu Sans", "Arial", "Liberation Sans", "SansSerif")
        for (name in candidates) {
            try { return Font(name, Font.BOLD, size) } catch (_: Exception) {}
        }
        return g.font.deriveFont(Font.BOLD, size.toFloat())
    }

    private fun enableQuality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
}
