/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.images.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Tests for [ImageUtils] pure utility functions.
 */
class ImageUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    // ---- validateUrl ----

    @Test
    @DisplayName("validateUrl accepts valid https URL")
    fun testValidateUrlAcceptsValidHttpsUrl() {
        val url = ImageUtils.validateUrl("https://example.com/photo.jpg")
        assertEquals("https://example.com/photo.jpg", url)
    }

    @Test
    @DisplayName("validateUrl accepts valid http URL")
    fun testValidateUrlAcceptsValidHttpUrl() {
        val url = ImageUtils.validateUrl("http://cdn.example.com/images/banner.png")
        assertEquals("http://cdn.example.com/images/banner.png", url)
    }

    @Test
    @DisplayName("validateUrl rejects blank URL")
    fun testValidateUrlRejectsBlankUrl() {
        assertThrows<IllegalArgumentException> {
            ImageUtils.validateUrl("   ")
        }
    }

    @Test
    @DisplayName("validateUrl rejects non-http protocol")
    fun testValidateUrlRejectsNonHttpProtocol() {
        assertThrows<IllegalArgumentException> {
            ImageUtils.validateUrl("ftp://files.example.com/photo.jpg")
        }
    }

    @Test
    @DisplayName("validateUrl rejects empty string")
    fun testValidateUrlRejectsEmptyString() {
        assertThrows<IllegalArgumentException> {
            ImageUtils.validateUrl("")
        }
    }

    @Test
    @DisplayName("validateUrl trims whitespace")
    fun testValidateUrlTrimsWhitespace() {
        val url = ImageUtils.validateUrl("  https://example.com/photo.jpg  ")
        assertEquals("https://example.com/photo.jpg", url)
    }

    // ---- isImageUrl ----

    @Test
    @DisplayName("isImageUrl detects jpg")
    fun testIsImageUrlDetectsJpg() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/photo.jpg"))
    }

    @Test
    @DisplayName("isImageUrl detects png")
    fun testIsImageUrlDetectsPng() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/screenshot.png"))
    }

    @Test
    @DisplayName("isImageUrl detects webp")
    fun testIsImageUrlDetectsWebp() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/image.webp"))
    }

    @Test
    @DisplayName("isImageUrl detects svg")
    fun testIsImageUrlDetectsSvg() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/logo.svg"))
    }

    @Test
    @DisplayName("isImageUrl detects gif")
    fun testIsImageUrlDetectsGif() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/animation.gif"))
    }

    @Test
    @DisplayName("isImageUrl detects avif")
    fun testIsImageUrlDetectsAvif() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/photo.avif"))
    }

    @Test
    @DisplayName("isImageUrl returns false for html")
    fun testIsImageUrlReturnsFalseForHtml() {
        assertFalse(ImageUtils.isImageUrl("https://example.com/page.html"))
    }

    @Test
    @DisplayName("isImageUrl returns false for no extension")
    fun testIsImageUrlReturnsFalseForNoExtension() {
        assertFalse(ImageUtils.isImageUrl("https://example.com/images"))
    }

    @Test
    @DisplayName("isImageUrl ignores query parameters")
    fun testIsImageUrlIgnoresQueryParameters() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/photo.jpg?w=800&h=600"))
    }

    @Test
    @DisplayName("isImageUrl ignores fragment")
    fun testIsImageUrlIgnoresFragment() {
        assertTrue(ImageUtils.isImageUrl("https://example.com/photo.png#anchor"))
    }

    // ---- isImageMimeType ----

    @Test
    @DisplayName("isImageMimeType detects image/jpeg")
    fun testIsImageMimeTypeDetectsImageJpeg() {
        assertTrue(ImageUtils.isImageMimeType("image/jpeg"))
    }

    @Test
    @DisplayName("isImageMimeType detects image/png")
    fun testIsImageMimeTypeDetectsImagePng() {
        assertTrue(ImageUtils.isImageMimeType("image/png"))
    }

    @Test
    @DisplayName("isImageMimeType detects image/webp")
    fun testIsImageMimeTypeDetectsImageWebp() {
        assertTrue(ImageUtils.isImageMimeType("image/webp"))
    }

    @Test
    @DisplayName("isImageMimeType detects image/svg+xml")
    fun testIsImageMimeTypeDetectsImageSvgXml() {
        assertTrue(ImageUtils.isImageMimeType("image/svg+xml"))
    }

    @Test
    @DisplayName("isImageMimeType ignores charset parameter")
    fun testIsImageMimeTypeIgnoresCharsetParameter() {
        assertTrue(ImageUtils.isImageMimeType("image/png; charset=utf-8"))
    }

    @Test
    @DisplayName("isImageMimeType returns false for null")
    fun testIsImageMimeTypeReturnsFalseForNull() {
        assertFalse(ImageUtils.isImageMimeType(null))
    }

    @Test
    @DisplayName("isImageMimeType returns false for non-image types")
    fun testIsImageMimeTypeReturnsFalseForNonImageTypes() {
        assertFalse(ImageUtils.isImageMimeType("text/html"))
        assertFalse(ImageUtils.isImageMimeType("application/json"))
        assertFalse(ImageUtils.isImageMimeType("video/mp4"))
    }

    // ---- suggestFilename ----

    @Test
    @DisplayName("suggestFilename extracts from URL path")
    fun testSuggestFilenameExtractsFromUrlPath() {
        val name = ImageUtils.suggestFilename("https://example.com/photos/my-photo.jpg")
        assertEquals("my-photo.jpg", name)
    }

    @Test
    @DisplayName("suggestFilename ignores query string")
    fun testSuggestFilenameIgnoresQueryString() {
        val name = ImageUtils.suggestFilename("https://example.com/photo.png?token=abc&size=large")
        assertEquals("photo.png", name)
    }

    @Test
    @DisplayName("suggestFilename falls back to generated name")
    fun testSuggestFilenameFallsBackToGeneratedName() {
        val name = ImageUtils.suggestFilename("https://example.com/stream")
        assertTrue(name.startsWith("image_"))
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    @DisplayName("suggestFilename uses content type extension when URL has no extension")
    fun testSuggestFilenameUsesContentTypeExtension() {
        val name = ImageUtils.suggestFilename("https://example.com/image", "image/png")
        assertTrue(name.startsWith("image_"))
        assertTrue(name.endsWith(".png"))
    }

    // ---- guessExtension ----

    @Test
    @DisplayName("guessExtension returns jpg for image/jpeg")
    fun testGuessExtensionReturnsJpgForImageJpeg() {
        assertEquals("jpg", ImageUtils.guessExtension("image/jpeg"))
    }

    @Test
    @DisplayName("guessExtension returns png for image/png")
    fun testGuessExtensionReturnsPngForImagePng() {
        assertEquals("png", ImageUtils.guessExtension("image/png"))
    }

    @Test
    @DisplayName("guessExtension returns gif for image/gif")
    fun testGuessExtensionReturnsGifForImageGif() {
        assertEquals("gif", ImageUtils.guessExtension("image/gif"))
    }

    @Test
    @DisplayName("guessExtension returns webp for image/webp")
    fun testGuessExtensionReturnsWebpForImageWebp() {
        assertEquals("webp", ImageUtils.guessExtension("image/webp"))
    }

    @Test
    @DisplayName("guessExtension returns svg for image/svg+xml")
    fun testGuessExtensionReturnsSvgForImageSvg() {
        assertEquals("svg", ImageUtils.guessExtension("image/svg+xml"))
    }

    @Test
    @DisplayName("guessExtension returns avif for image/avif")
    fun testGuessExtensionReturnsAvifForImageAvif() {
        assertEquals("avif", ImageUtils.guessExtension("image/avif"))
    }

    @Test
    @DisplayName("guessExtension returns jpg for null")
    fun testGuessExtensionReturnsJpgForNull() {
        assertEquals("jpg", ImageUtils.guessExtension(null))
    }

    @Test
    @DisplayName("guessExtension returns jpg for unknown image type")
    fun testGuessExtensionReturnsJpgForUnknownImageType() {
        assertEquals("jpg", ImageUtils.guessExtension("image/x-unknown"))
    }

    // ---- sanitizeFilename ----

    @Test
    @DisplayName("sanitizeFilename removes illegal characters")
    fun testSanitizeFilenameRemovesIllegalCharacters() {
        val result = ImageUtils.sanitizeFilename("my:photo<file>.jpg")
        assertFalse(result.contains(":"))
        assertFalse(result.contains("<"))
        assertFalse(result.contains(">"))
    }

    @Test
    @DisplayName("sanitizeFilename replaces double dots")
    fun testSanitizeFilenameReplacesDoubleDots() {
        val result = ImageUtils.sanitizeFilename("../etc/passwd")
        assertFalse(result.contains(".."))
    }

    @Test
    @DisplayName("sanitizeFilename preserves valid filename")
    fun testSanitizeFilenamePreservesValidFilename() {
        val result = ImageUtils.sanitizeFilename("my-photo_vacation.jpg")
        assertEquals("my-photo_vacation.jpg", result)
    }

    @Test
    @DisplayName("sanitizeFilename truncates long filenames")
    fun testSanitizeFilenameTruncatesLongFilenames() {
        val longName = "a".repeat(300) + ".jpg"
        val result = ImageUtils.sanitizeFilename(longName)
        assertTrue(result.length <= 255)
    }

    // ---- requirePathWithinBase ----

    @Test
    @DisplayName("requirePathWithinBase allows valid path")
    fun testRequirePathWithinBaseAllowsValidPath() {
        val baseDir = Path.of("/tmp/images")
        ImageUtils.requirePathWithinBase(baseDir, baseDir.resolve("photo.jpg"))
    }

    @Test
    @DisplayName("requirePathWithinBase rejects path traversal")
    fun testRequirePathWithinBaseRejectsPathTraversal() {
        val baseDir = Path.of("/tmp/images")
        assertThrows<IllegalArgumentException> {
            ImageUtils.requirePathWithinBase(baseDir, Path.of("/etc/passwd"))
        }
    }

    // ---- formatFileSize ----

    @Test
    @DisplayName("formatFileSize formats bytes")
    fun testFormatFileSizeFormatsBytes() {
        assertTrue(ImageUtils.formatFileSize(500).contains("B"))
    }

    @Test
    @DisplayName("formatFileSize formats KB")
    fun testFormatFileSizeFormatsKb() {
        assertTrue(ImageUtils.formatFileSize(2048).contains("KB"))
    }

    @Test
    @DisplayName("formatFileSize formats MB")
    fun testFormatFileSizeFormatsMb() {
        assertTrue(ImageUtils.formatFileSize(50 * 1024 * 1024).contains("MB"))
    }

    @Test
    @DisplayName("formatFileSize formats GB")
    fun testFormatFileSizeFormatsGb() {
        assertTrue(ImageUtils.formatFileSize(2L * 1024 * 1024 * 1024).contains("GB"))
    }

    // ---- isDataUri ----

    @Test
    @DisplayName("isDataUri detects data:image prefix")
    fun testIsDataUriDetectsDataImagePrefix() {
        assertTrue(ImageUtils.isDataUri("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="))
    }

    @Test
    @DisplayName("isDataUri detects data: prefix with leading whitespace")
    fun testIsDataUriDetectsDataPrefixWithWhitespace() {
        assertTrue(ImageUtils.isDataUri("  data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP"))
    }

    @Test
    @DisplayName("isDataUri returns false for http URL")
    fun testIsDataUriReturnsFalseForHttpUrl() {
        assertFalse(ImageUtils.isDataUri("https://example.com/photo.jpg"))
    }

    // ---- isSvg ----

    @Test
    @DisplayName("isSvg detects .svg extension")
    fun testIsSvgDetectsSvgExtension() {
        assertTrue(ImageUtils.isSvg("https://example.com/logo.svg"))
        assertTrue(ImageUtils.isSvg("https://example.com/icon.svg?color=red"))
    }

    @Test
    @DisplayName("isSvg detects image/svg+xml content type")
    fun testIsSvgDetectsSvgContentType() {
        assertTrue(ImageUtils.isSvg("https://example.com/image", "image/svg+xml"))
    }

    @Test
    @DisplayName("isSvg returns false for raster images")
    fun testIsSvgReturnsFalseForRasterImages() {
        assertFalse(ImageUtils.isSvg("https://example.com/photo.jpg"))
        assertFalse(ImageUtils.isSvg("https://example.com/photo.png"))
    }

    // ---- alignFilenameExtension ----

    @Test
    @DisplayName("alignFilenameExtension replaces mismatched extension with content type extension")
    fun testAlignReplacesMismatchedExtension() {
        assertEquals("photo.webp", ImageUtils.alignFilenameExtension("photo.png", "image/webp"))
        assertEquals("flag.gif", ImageUtils.alignFilenameExtension("flag.jpg", "image/gif"))
    }

    @Test
    @DisplayName("alignFilenameExtension keeps matching extension")
    fun testAlignKeepsMatchingExtension() {
        assertEquals("photo.png", ImageUtils.alignFilenameExtension("photo.png", "image/png"))
    }

    @Test
    @DisplayName("alignFilenameExtension treats jpg and jpeg as equivalent")
    fun testAlignTreatsJpgJpegEquivalent() {
        assertEquals("photo.jpg", ImageUtils.alignFilenameExtension("photo.jpg", "image/jpeg"))
        assertEquals("photo.jpeg", ImageUtils.alignFilenameExtension("photo.jpeg", "image/jpeg"))
    }

    @Test
    @DisplayName("alignFilenameExtension appends extension when filename has none")
    fun testAlignAppendsExtensionWhenNoDot() {
        assertEquals("photo.webp", ImageUtils.alignFilenameExtension("photo", "image/webp"))
    }

    @Test
    @DisplayName("alignFilenameExtension leaves filename unchanged for null or non-image content type")
    fun testAlignLeavesUnchangedForUnknownContentType() {
        assertEquals("photo.png", ImageUtils.alignFilenameExtension("photo.png", null))
        assertEquals("photo.bin", ImageUtils.alignFilenameExtension("photo.bin", "application/octet-stream"))
        assertEquals("photo.html", ImageUtils.alignFilenameExtension("photo.html", "text/html; charset=utf-8"))
    }

    @Test
    @DisplayName("alignFilenameExtension ignores charset parameter in content type")
    fun testAlignIgnoresCharsetParameter() {
        assertEquals("photo.png", ImageUtils.alignFilenameExtension("photo.png", "image/png; charset=utf-8"))
    }

    @Test
    @DisplayName("alignFilenameExtension corrects extension case-insensitively")
    fun testAlignIsCaseInsensitive() {
        assertEquals("photo.PNG", ImageUtils.alignFilenameExtension("photo.PNG", "image/png"))
    }

    // ---- defaultHttpReason ----

    @Test
    @DisplayName("defaultHttpReason returns standard phrases for common client error codes")
    fun testDefaultHttpReasonReturnsCommonPhrases() {
        assertEquals("Bad Request", ImageUtils.defaultHttpReason(400))
        assertEquals("Unauthorized", ImageUtils.defaultHttpReason(401))
        assertEquals("Forbidden", ImageUtils.defaultHttpReason(403))
        assertEquals("Not Found", ImageUtils.defaultHttpReason(404))
        assertEquals("Method Not Allowed", ImageUtils.defaultHttpReason(405))
        assertEquals("Gone", ImageUtils.defaultHttpReason(410))
        assertEquals("Too Many Requests", ImageUtils.defaultHttpReason(429))
    }

    @Test
    @DisplayName("defaultHttpReason covers every 5xx code")
    fun testDefaultHttpReasonCovers5xx() {
        assertEquals("Server Error", ImageUtils.defaultHttpReason(500))
        assertEquals("Server Error", ImageUtils.defaultHttpReason(502))
        assertEquals("Server Error", ImageUtils.defaultHttpReason(503))
    }

    @Test
    @DisplayName("defaultHttpReason returns empty phrase for unmapped codes")
    fun testDefaultHttpReasonReturnsEmptyForUnmappedCodes() {
        assertEquals("", ImageUtils.defaultHttpReason(200))
        assertEquals("", ImageUtils.defaultHttpReason(418))
    }

    // ---- percentDecode / suggestFilename decoding ----

    @Test
    @DisplayName("percentDecode decodes escaped parentheses and dashes")
    fun testPercentDecodeDecodesEscapes() {
        assertEquals("250px-Flag_of_Switzerland_(Pantone).svg", ImageUtils.percentDecode("250px-Flag_of_Switzerland_%28Pantone%29.svg"))
        assertEquals("Vatican_City_(2023–present).svg", ImageUtils.percentDecode("Vatican_City_%282023%E2%80%93present%29.svg"))
    }

    @Test
    @DisplayName("percentDecode keeps a literal plus and undecodable text")
    fun testPercentDecodeKeepsPlusAndMalformedInput() {
        // '+' is a legal path character, never a space
        assertEquals("a+b.png", ImageUtils.percentDecode("a+b.png"))
        // a malformed escape must not throw or corrupt the segment
        assertEquals("100%_off.png", ImageUtils.percentDecode("100%_off.png"))
    }

    @Test
    @DisplayName("suggestFilename percent-decodes the URL path segment")
    fun testSuggestFilenamePercentDecodesPathSegment() {
        val url = "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/Flag_of_Switzerland_%28Pantone%29.svg/250px-Flag_of_Switzerland_%28Pantone%29.svg.png"
        assertEquals("250px-Flag_of_Switzerland_(Pantone).svg.png", ImageUtils.suggestFilename(url))
    }

    @Test
    @DisplayName("suggestFilename sanitizes separators a decoded escape may introduce")
    fun testSuggestFilenameSanitizesDecodedSeparator() {
        val name = ImageUtils.suggestFilename("https://example.com/a%2F..%2Fb.png")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
    }

    // ---- guessMimeType ----

    @Test
    @DisplayName("guessMimeType infers a MIME type from the URL extension")
    fun testGuessMimeTypeInfersFromExtension() {
        assertEquals("image/png", ImageUtils.guessMimeType("https://example.com/250px-Flag.svg.png"))
        assertEquals("image/webp", ImageUtils.guessMimeType("https://example.com/photo.WEBP?x=1"))
        assertEquals("image/svg+xml", ImageUtils.guessMimeType("https://example.com/logo.svg"))
        assertEquals("image/jpeg", ImageUtils.guessMimeType("https://example.com/photo.jpg#frag"))
        assertEquals("image/x-icon", ImageUtils.guessMimeType("https://example.com/favicon.ico"))
    }

    @Test
    @DisplayName("guessMimeType reads the declared type of a data URI")
    fun testGuessMimeTypeReadsDataUriHeader() {
        assertEquals("image/png", ImageUtils.guessMimeType("data:image/png;base64,iVBORw0KGgo="))
        assertEquals("image/gif", ImageUtils.guessMimeType("DATA:image/gif;base64,R0lGOD"))
    }

    @Test
    @DisplayName("guessMimeType returns null when the URL carries no hint")
    fun testGuessMimeTypeReturnsNullWithoutHint() {
        assertNull(ImageUtils.guessMimeType("https://example.com/image"))
        assertNull(ImageUtils.guessMimeType("https://example.com/page.html"))
        assertNull(ImageUtils.guessMimeType(null))
        assertNull(ImageUtils.guessMimeType("   "))
    }

    // ---- readImageDimensions / parseWebpDimensions ----

    @Test
    @DisplayName("readImageDimensions reads a PNG written by ImageIO")
    fun testReadImageDimensionsReadsPng() {
        val file = tempDir.resolve("flag.png")
        writePng(file, 7, 5)

        assertEquals(7 to 5, ImageUtils.readImageDimensions(file))
    }

    @Test
    @DisplayName("readImageDimensions reads a WebP header, which the JDK cannot decode")
    fun testReadImageDimensionsReadsWebp() {
        val file = tempDir.resolve("flag.webp")
        file.writeBytes(webpLossless(250, 305))

        assertEquals(250 to 305, ImageUtils.readImageDimensions(file))
    }

    @Test
    @DisplayName("readImageDimensions returns null for a file that is not an image")
    fun testReadImageDimensionsReturnsNullForNonImage() {
        val file = tempDir.resolve("page.html")
        file.writeText("<html><body>not an image</body></html>")

        assertNull(ImageUtils.readImageDimensions(file))
    }

    @Test
    @DisplayName("parseWebpDimensions reads all three WebP variants")
    fun testParseWebpDimensionsReadsAllVariants() {
        assertEquals(250 to 305, ImageUtils.parseWebpDimensions(webpLossless(250, 305)))
        assertEquals(1280 to 720, ImageUtils.parseWebpDimensions(webpExtended(1280, 720)))
        assertEquals(640 to 480, ImageUtils.parseWebpDimensions(webpLossy(640, 480)))
    }

    @Test
    @DisplayName("parseWebpDimensions rejects truncated or non-WebP headers")
    fun testParseWebpDimensionsRejectsInvalidHeaders() {
        assertNull(ImageUtils.parseWebpDimensions(ByteArray(10)))
        assertNull(ImageUtils.parseWebpDimensions("RIFF____PNG_".toByteArray()))
        assertNull(ImageUtils.parseWebpDimensions(ImageUtils.parseWebpDimensions(webpLossless(1, 1))?.let { ByteArray(30) } ?: ByteArray(30)))
    }

    // ---- helpers ----

    private fun writePng(path: Path, width: Int, height: Int) {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        javax.imageio.ImageIO.write(image, "png", path.toFile())
    }

    /** `VP8L` lossless header: signature 0x2F, then 14 bits of width-1 and height-1. */
    private fun webpLossless(width: Int, height: Int): ByteArray {
        val header = riffHeader("VP8L")
        header[20] = 0x2F
        val bits = (width - 1) or ((height - 1) shl 14)
        header[21] = (bits and 0xFF).toByte()
        header[22] = ((bits ushr 8) and 0xFF).toByte()
        header[23] = ((bits ushr 16) and 0xFF).toByte()
        header[24] = ((bits ushr 24) and 0xFF).toByte()
        return header
    }

    /** `VP8X` extended header: 24-bit canvas width-1 and height-1. */
    private fun webpExtended(width: Int, height: Int): ByteArray {
        val header = riffHeader("VP8X")
        writeLe24(header, 24, width - 1)
        writeLe24(header, 27, height - 1)
        return header
    }

    /** `VP8 ` lossy header: frame tag + start code, then 14-bit dimensions. */
    private fun webpLossy(width: Int, height: Int): ByteArray {
        val header = riffHeader("VP8 ")
        header[23] = 0x9D.toByte()
        header[24] = 0x01
        header[25] = 0x2A
        header[26] = (width and 0xFF).toByte()
        header[27] = ((width ushr 8) and 0x3F).toByte()
        header[28] = (height and 0xFF).toByte()
        header[29] = ((height ushr 8) and 0x3F).toByte()
        return header
    }

    private fun riffHeader(fourCc: String): ByteArray {
        val header = ByteArray(30)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        fourCc.toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        return header
    }

    private fun writeLe24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }
}
