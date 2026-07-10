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
import java.nio.file.Path

/**
 * Tests for [ImageUtils] pure utility functions.
 */
class ImageUtilsTest {

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
}
