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
package ai.platon.pulsar.media.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [VideoDetector] JSON parsing and result deduplication.
 *
 * Creates a test subclass that exposes [parseResult] for direct testing,
 * avoiding the need to mock WebDriver/suspend functions.
 */
class VideoDetectorTest {

    private lateinit var detector: TestVideoDetector

    @BeforeEach
    fun setUp() {
        detector = TestVideoDetector()
    }

    @Test
    @DisplayName("parse empty JSON returns empty list")
    fun testParseEmptyJson() {
        assertEquals(0, detector.parse("[]").size)
    }

    @Test
    @DisplayName("parse null returns empty list")
    fun testParseNull() {
        assertEquals(0, detector.parse(null).size)
    }

    @Test
    @DisplayName("parse blank string returns empty list")
    fun testParseBlank() {
        assertEquals(0, detector.parse("   ").size)
    }

    @Test
    @DisplayName("parse malformed JSON returns empty list")
    fun testParseMalformedJson() {
        assertEquals(0, detector.parse("not valid json {{{").size)
    }

    @Test
    @DisplayName("parse video tag JSON returns correct VideoSource")
    fun testParseVideoTag() {
        val result = detector.parse("""[{"tagName":"video","srcUrl":"https://example.com/video.mp4","resolvedUrl":"https://example.com/video.mp4","type":null,"width":640,"height":360,"hasControls":true,"posterUrl":"https://example.com/poster.jpg","isHls":false,"isDash":false,"isIframe":false}]""")

        assertEquals(1, result.size)
        val video = result[0]
        assertEquals("video", video.tagName)
        assertEquals("https://example.com/video.mp4", video.resolvedUrl)
        assertEquals(640, video.width)
        assertEquals(360, video.height)
        assertTrue(video.hasControls)
        assertEquals("https://example.com/poster.jpg", video.posterUrl)
        assertFalse(video.isHls)
        assertFalse(video.isIframe)
    }

    @Test
    @DisplayName("parse HLS source returns isHls=true")
    fun testParseHlsSource() {
        val result = detector.parse("""[{"tagName":"source","srcUrl":"https://cdn.com/video.m3u8","resolvedUrl":"https://cdn.com/video.m3u8","type":"application/vnd.apple.mpegurl","width":1920,"height":1080,"hasControls":true,"posterUrl":null,"isHls":true,"isDash":false,"isIframe":false}]""")

        assertEquals(1, result.size)
        assertTrue(result[0].isHls)
        assertEquals("application/vnd.apple.mpegurl", result[0].type)
    }

    @Test
    @DisplayName("parse iframe embed returns isIframe=true")
    fun testParseIframeEmbed() {
        val result = detector.parse("""[{"tagName":"iframe","srcUrl":"https://www.youtube.com/embed/dQw4w9WgXcQ","resolvedUrl":"https://www.youtube.com/embed/dQw4w9WgXcQ","type":null,"width":560,"height":315,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":true}]""")

        assertEquals(1, result.size)
        assertTrue(result[0].isIframe)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ", result[0].resolvedUrl)
    }

    @Test
    @DisplayName("parse deduplicates by resolved URL")
    fun testParseDeduplicates() {
        val result = detector.parse("""[{"tagName":"video","srcUrl":"https://x.com/v.mp4","resolvedUrl":"https://x.com/v.mp4","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false},{"tagName":"a","srcUrl":"https://x.com/v.mp4","resolvedUrl":"https://x.com/v.mp4","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false}]""")

        assertEquals(1, result.size)
    }

    @Test
    @DisplayName("parse multiple distinct videos")
    fun testParseMultipleVideos() {
        val result = detector.parse("""[{"tagName":"video","srcUrl":null,"resolvedUrl":"https://a.com/1.mp4","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false},{"tagName":"video","srcUrl":null,"resolvedUrl":"https://a.com/2.webm","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false},{"tagName":"iframe","srcUrl":"https://vimeo.com/123","resolvedUrl":"https://vimeo.com/123","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":true}]""")

        assertEquals(3, result.size)
    }

    @Test
    @DisplayName("parse HLS and DASH flags correctly")
    fun testParseHlsAndDash() {
        val result = detector.parse("""[{"tagName":"source","srcUrl":"https://cdn.com/stream.m3u8","resolvedUrl":"https://cdn.com/stream.m3u8","type":"application/vnd.apple.mpegurl","width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":true,"isDash":false,"isIframe":false},{"tagName":"source","srcUrl":"https://cdn.com/manifest.mpd","resolvedUrl":"https://cdn.com/manifest.mpd","type":"application/dash+xml","width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":true,"isIframe":false}]""")

        assertEquals(2, result.size)
        assertTrue(result.any { it.isHls })
        assertTrue(result.any { it.isDash })
    }

    @Test
    @DisplayName("parse detects isIframe flag correctly")
    fun testParseIsIframeFlag() {
        val result = detector.parse("""[{"tagName":"iframe","srcUrl":"https://player.vimeo.com/video/123","resolvedUrl":"https://player.vimeo.com/video/123","type":null,"width":640,"height":360,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":true}]""")

        assertEquals(1, result.size)
        assertTrue(result[0].isIframe)
        assertEquals(640, result[0].width)
    }
}

/**
 * Test subclass of VideoDetector that exposes [parseResult] for direct testing.
 */
private open class TestVideoDetector : VideoDetector() {
    fun parse(json: String?): List<VideoDetector.VideoSource> = parseResult(json)
}
