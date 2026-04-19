package ai.platon.browser4.driver.chrome.dom.util

import ai.platon.browser4.driver.chrome.dom.model.DOMRect
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO

class HighlightUtilsTest {

    private fun pngBase64(width: Int = 200, height: Int = 150, color: Color = Color(0xF0, 0xF0, 0xF0)):
        Pair<String, BufferedImage> {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally { g.dispose() }
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        val b64 = Base64.getEncoder().encodeToString(baos.toByteArray())
        return b64 to img
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { String.format("%02x", it) }
    }

    @Test
    fun createHighlightedScreenshot_drawsBoxes() {
        val (b64, originalImg) = pngBase64()

        val node = MergedDOMTreeNode(
            nodeId = 1,
            nodeName = "BUTTON",
            attributes = mapOf("type" to "button"),
            backendNodeId = 123,
            absolutePosition = DOMRect(10.0, 10.0, 80.0, 50.0)
        )
        val selectorMap = mapOf("123" to node)

        val outB64 = HighlightUtils.createHighlightedScreenshot(b64, selectorMap, devicePixelRatio = 1.0, filterHighlightIds = false)
        assertNotNull(outB64)
        assertTrue(outB64.isNotBlank())

        // Decode output and compare size
        val outBytes = Base64.getDecoder().decode(outB64)
        val outImg = ImageIO.read(ByteArrayInputStream(outBytes))
        assertNotNull(outImg)
        assertEquals(originalImg.width, outImg.width)
        assertEquals(originalImg.height, outImg.height)

        // Expect image content to change (hash different)
        val inHash = sha256(Base64.getDecoder().decode(b64))
        val outHash = sha256(outBytes)
        assertNotEquals(inHash, outHash)
    }
}

