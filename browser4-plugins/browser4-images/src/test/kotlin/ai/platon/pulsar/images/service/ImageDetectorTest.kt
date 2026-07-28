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

import ai.platon.pulsar.images.config.ImageConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ImageDetector], focusing on the [ImageDetector.parseResult] parser
 * and the [ImageDetector.detect] filter logic.
 */
class ImageDetectorTest {

    private val detector = ImageDetector(ImageConfig())

    // ---- parseResult ----

    @Test
    @DisplayName("parseResult returns empty list for null input")
    fun testParseResultNullInput() {
        val result = detector.parseResult(null)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult returns empty list for blank string")
    fun testParseResultBlankString() {
        val result = detector.parseResult("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult returns empty list for empty JSON array")
    fun testParseResultEmptyJsonArray() {
        val result = detector.parseResult("[]")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult parses valid image source JSON")
    fun testParseResultValidJson() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "photo.jpg",
                "resolvedUrl": "https://example.com/photo.jpg",
                "type": null,
                "width": 800,
                "height": 600,
                "naturalWidth": 1600,
                "naturalHeight": 1200,
                "alt": "A beautiful photo",
                "isDataUri": false,
                "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        assertEquals(1, result.size)
        val source = result[0]
        assertEquals("img", source.tagName)
        assertEquals("photo.jpg", source.srcUrl)
        assertEquals("https://example.com/photo.jpg", source.resolvedUrl)
        assertEquals(800, source.width)
        assertEquals(600, source.height)
        assertEquals(1600, source.naturalWidth)
        assertEquals(1200, source.naturalHeight)
        assertEquals("A beautiful photo", source.alt)
        assertFalse(source.isDataUri)
        assertFalse(source.isSvg)
    }

    @Test
    @DisplayName("parseResult parses multiple image sources")
    fun testParseResultMultipleSources() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "img1.jpg",
                "resolvedUrl": "https://example.com/img1.jpg",
                "type": null,
                "width": 100,
                "height": 100,
                "naturalWidth": 200,
                "naturalHeight": 200,
                "alt": "Image 1",
                "isDataUri": false,
                "isSvg": false
            },
            {
                "tagName": "img",
                "srcUrl": "img2.png",
                "resolvedUrl": "https://example.com/img2.png",
                "type": null,
                "width": 300,
                "height": 200,
                "naturalWidth": 600,
                "naturalHeight": 400,
                "alt": "Image 2",
                "isDataUri": false,
                "isSvg": false
            },
            {
                "tagName": "link",
                "srcUrl": "favicon.ico",
                "resolvedUrl": "https://example.com/favicon.ico",
                "type": "image/x-icon",
                "width": 32,
                "height": 32,
                "naturalWidth": null,
                "naturalHeight": null,
                "alt": "icon",
                "isDataUri": false,
                "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        assertEquals(3, result.size)
        assertEquals("Image 1", result[0].alt)
        assertEquals("Image 2", result[1].alt)
        assertEquals("icon", result[2].alt)
    }

    @Test
    @DisplayName("parseResult deduplicates by resolvedUrl")
    fun testParseResultDeduplicatesByResolvedUrl() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "img.jpg",
                "resolvedUrl": "https://example.com/img.jpg",
                "type": null,
                "width": 100, "height": 100,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "First", "isDataUri": false, "isSvg": false
            },
            {
                "tagName": "a",
                "srcUrl": "img.jpg",
                "resolvedUrl": "https://example.com/img.jpg",
                "type": null,
                "width": null, "height": null,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "Duplicate", "isDataUri": false, "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        // Should deduplicate: only one entry for the same resolvedUrl
        assertEquals(1, result.size)
        assertEquals("First", result[0].alt) // first occurrence wins
    }

    @Test
    @DisplayName("parseResult handles malformed JSON gracefully")
    fun testParseResultMalformedJson() {
        val result = detector.parseResult("{not valid json}")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult handles non-JSON input gracefully")
    fun testParseResultNonJsonInput() {
        val result = detector.parseResult("some random string")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult detects SVG images")
    fun testParseResultDetectsSvgImages() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "icon.svg",
                "resolvedUrl": "https://example.com/icon.svg",
                "type": "image/svg+xml",
                "width": 64, "height": 64,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "SVG Icon", "isDataUri": false, "isSvg": true
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        assertEquals(1, result.size)
        assertTrue(result[0].isSvg)
    }

    @Test
    @DisplayName("parseResult detects data URIs")
    fun testParseResultDetectsDataUris() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "data:image/png;base64,iVBORw0KGgo==",
                "resolvedUrl": "data:image/png;base64,iVBORw0KGgo==",
                "type": null,
                "width": 50, "height": 50,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "Data URI", "isDataUri": true, "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        assertEquals(1, result.size)
        assertTrue(result[0].isDataUri)
    }

    @Test
    @DisplayName("parseResult handles OG image meta tag")
    fun testParseResultHandlesOgImageMetaTag() {
        val json = """
        [
            {
                "tagName": "meta",
                "srcUrl": "https://example.com/og-image.jpg",
                "resolvedUrl": "https://example.com/og-image.jpg",
                "type": null,
                "width": null, "height": null,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "og:image", "isDataUri": false, "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("meta", result[0].tagName)
        assertEquals("og:image", result[0].alt)
    }

    // ---- filter-related parsing (filters are applied in detect(), parseResult is pure) ----

    @Test
    @DisplayName("parseResult correctly identifies data URIs for downstream filtering")
    fun testParseResultIdentifiesDataUris() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "data:image/png;base64,xxx",
                "resolvedUrl": "data:image/png;base64,xxx",
                "type": null,
                "width": 100, "height": 100,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "Data", "isDataUri": true, "isSvg": false
            },
            {
                "tagName": "img",
                "srcUrl": "photo.jpg",
                "resolvedUrl": "https://example.com/photo.jpg",
                "type": null,
                "width": 100, "height": 100,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "Photo", "isDataUri": false, "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        // parseResult is a pure parser; filtering is deferred to detect()
        assertEquals(2, result.size)
        assertTrue(result[0].isDataUri)
        assertFalse(result[1].isDataUri)
    }

    @Test
    @DisplayName("parseResult correctly identifies SVG images for downstream filtering")
    fun testParseResultIdentifiesSvgs() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "icon.svg",
                "resolvedUrl": "https://example.com/icon.svg",
                "type": "image/svg+xml",
                "width": 64, "height": 64,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "SVG", "isDataUri": false, "isSvg": true
            },
            {
                "tagName": "img",
                "srcUrl": "photo.jpg",
                "resolvedUrl": "https://example.com/photo.jpg",
                "type": null,
                "width": 100, "height": 100,
                "naturalWidth": null, "naturalHeight": null,
                "alt": "Photo", "isDataUri": false, "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        // parseResult is a pure parser; filtering is applied by detect() later
        assertEquals(2, result.size)
        assertTrue(result[0].isSvg)
        assertFalse(result[1].isSvg)
    }

    @Test
    @DisplayName("parseResult correctly preserves dimension metadata for downstream filtering")
    fun testParseResultPreservesDimensions() {
        val json = """
        [
            {
                "tagName": "img",
                "srcUrl": "small.jpg",
                "resolvedUrl": "https://example.com/small.jpg",
                "type": null,
                "width": 150, "height": 150,
                "naturalWidth": 150, "naturalHeight": 150,
                "alt": "Small", "isDataUri": false, "isSvg": false
            },
            {
                "tagName": "img",
                "srcUrl": "large.jpg",
                "resolvedUrl": "https://example.com/large.jpg",
                "type": null,
                "width": 800, "height": 600,
                "naturalWidth": 1600, "naturalHeight": 1200,
                "alt": "Large", "isDataUri": false, "isSvg": false
            }
        ]
        """.trimIndent()

        val result = detector.parseResult(json)

        // parseResult returns all parsed entries; dimension filtering is done in detect()
        assertEquals(2, result.size)
        assertEquals(150, result[0].naturalWidth)
        assertEquals(150, result[0].naturalHeight)
        assertEquals(1600, result[1].naturalWidth)
        assertEquals(1200, result[1].naturalHeight)
    }
}
