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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Tests for [MediaUtils] pure utility functions.
 */
class MediaUtilsTest {

    // ---- validateUrl ----

    @Test
    @DisplayName("validateUrl accepts valid https URL")
    fun testValidateUrlAcceptsValidHttpsUrl() {
        val url = MediaUtils.validateUrl("https://example.com/video.mp4")
        assertEquals("https://example.com/video.mp4", url)
    }

    @Test
    @DisplayName("validateUrl accepts valid http URL")
    fun testValidateUrlAcceptsValidHttpUrl() {
        val url = MediaUtils.validateUrl("http://cdn.example.com/media/stream.m3u8")
        assertEquals("http://cdn.example.com/media/stream.m3u8", url)
    }

    @Test
    @DisplayName("validateUrl rejects blank URL")
    fun testValidateUrlRejectsBlankUrl() {
        assertThrows<IllegalArgumentException> {
            MediaUtils.validateUrl("   ")
        }
    }

    @Test
    @DisplayName("validateUrl rejects non-http protocol")
    fun testValidateUrlRejectsNonHttpProtocol() {
        assertThrows<IllegalArgumentException> {
            MediaUtils.validateUrl("ftp://files.example.com/video.mp4")
        }
    }

    @Test
    @DisplayName("validateUrl rejects empty string")
    fun testValidateUrlRejectsEmptyString() {
        assertThrows<IllegalArgumentException> {
            MediaUtils.validateUrl("")
        }
    }

    @Test
    @DisplayName("validateUrl trims whitespace")
    fun testValidateUrlTrimsWhitespace() {
        val url = MediaUtils.validateUrl("  https://example.com/video.mp4  ")
        assertEquals("https://example.com/video.mp4", url)
    }

    // ---- isMediaUrl ----

    @Test
    @DisplayName("isMediaUrl detects mp4")
    fun testIsMediaUrlDetectsMp4() {
        assertTrue(MediaUtils.isMediaUrl("https://example.com/video.mp4"))
    }

    @Test
    @DisplayName("isMediaUrl detects webm")
    fun testIsMediaUrlDetectsWebm() {
        assertTrue(MediaUtils.isMediaUrl("https://example.com/clip.webm"))
    }

    @Test
    @DisplayName("isMediaUrl detects m3u8")
    fun testIsMediaUrlDetectsM3u8() {
        assertTrue(MediaUtils.isMediaUrl("https://cdn.com/stream.m3u8"))
    }

    @Test
    @DisplayName("isMediaUrl detects audio mp3")
    fun testIsMediaUrlDetectsAudioMp3() {
        assertTrue(MediaUtils.isMediaUrl("https://example.com/podcast.mp3"))
    }

    @Test
    @DisplayName("isMediaUrl returns false for html")
    fun testIsMediaUrlReturnsFalseForHtml() {
        assertFalse(MediaUtils.isMediaUrl("https://example.com/page.html"))
    }

    @Test
    @DisplayName("isMediaUrl ignores query parameters")
    fun testIsMediaUrlIgnoresQueryParameters() {
        assertTrue(MediaUtils.isMediaUrl("https://example.com/video.mp4?token=abc&expires=123"))
    }

    @Test
    @DisplayName("isMediaUrl ignores fragment")
    fun testIsMediaUrlIgnoresFragment() {
        assertTrue(MediaUtils.isMediaUrl("https://example.com/video.mp4#t=10"))
    }

    // ---- isVideoUrl ----

    @Test
    @DisplayName("isVideoUrl detects mp4 but not mp3")
    fun testIsVideoUrlDetectsMp4ButNotMp3() {
        assertTrue(MediaUtils.isVideoUrl("https://example.com/video.mp4"))
        assertFalse(MediaUtils.isVideoUrl("https://example.com/audio.mp3"))
    }

    // ---- isVideoMimeType ----

    @Test
    @DisplayName("isVideoMimeType detects video/mp4")
    fun testIsVideoMimeTypeDetectsVideoMp4() {
        assertTrue(MediaUtils.isVideoMimeType("video/mp4"))
    }

    @Test
    @DisplayName("isVideoMimeType detects video/webm")
    fun testIsVideoMimeTypeDetectsVideoWebm() {
        assertTrue(MediaUtils.isVideoMimeType("video/webm"))
    }

    @Test
    @DisplayName("isVideoMimeType ignores charset parameter")
    fun testIsVideoMimeTypeIgnoresCharsetParameter() {
        assertTrue(MediaUtils.isVideoMimeType("video/mp4; charset=utf-8"))
    }

    @Test
    @DisplayName("isVideoMimeType returns false for null")
    fun testIsVideoMimeTypeReturnsFalseForNull() {
        assertFalse(MediaUtils.isVideoMimeType(null))
    }

    @Test
    @DisplayName("isVideoMimeType returns false for audio types")
    fun testIsVideoMimeTypeReturnsFalseForAudioTypes() {
        assertFalse(MediaUtils.isVideoMimeType("audio/mpeg"))
    }

    // ---- suggestFilename ----

    @Test
    @DisplayName("suggestFilename extracts from URL path")
    fun testSuggestFilenameExtractsFromUrlPath() {
        val name = MediaUtils.suggestFilename("https://example.com/videos/my-clip.mp4")
        assertEquals("my-clip.mp4", name)
    }

    @Test
    @DisplayName("suggestFilename ignores query string")
    fun testSuggestFilenameIgnoresQueryString() {
        val name = MediaUtils.suggestFilename("https://example.com/video.mp4?token=xyz")
        assertEquals("video.mp4", name)
    }

    @Test
    @DisplayName("suggestFilename falls back to generated name")
    fun testSuggestFilenameFallsBackToGeneratedName() {
        val name = MediaUtils.suggestFilename("https://example.com/stream")
        assertTrue(name.startsWith("video_"))
        assertTrue(name.endsWith(".mp4"))
    }

    // ---- guessExtension ----

    @Test
    @DisplayName("guessExtension returns mp4 for video/mp4")
    fun testGuessExtensionReturnsMp4ForVideoMp4() {
        assertEquals("mp4", MediaUtils.guessExtension("video/mp4"))
    }

    @Test
    @DisplayName("guessExtension returns webm for video/webm")
    fun testGuessExtensionReturnsWebmForVideoWebm() {
        assertEquals("webm", MediaUtils.guessExtension("video/webm"))
    }

    @Test
    @DisplayName("guessExtension returns m3u8 for HLS")
    fun testGuessExtensionReturnsM3u8ForHls() {
        assertEquals("m3u8", MediaUtils.guessExtension("application/vnd.apple.mpegurl"))
    }

    @Test
    @DisplayName("guessExtension returns mp4 for null")
    fun testGuessExtensionReturnsMp4ForNull() {
        assertEquals("mp4", MediaUtils.guessExtension(null))
    }

    // ---- sanitizeFilename ----

    @Test
    @DisplayName("sanitizeFilename removes illegal characters")
    fun testSanitizeFilenameRemovesIllegalCharacters() {
        val result = MediaUtils.sanitizeFilename("my:video<file>.mp4")
        assertFalse(result.contains(":"))
        assertFalse(result.contains("<"))
        assertFalse(result.contains(">"))
    }

    @Test
    @DisplayName("sanitizeFilename replaces double dots")
    fun testSanitizeFilenameReplacesDoubleDots() {
        val result = MediaUtils.sanitizeFilename("../etc/passwd")
        assertFalse(result.contains(".."))
    }

    @Test
    @DisplayName("sanitizeFilename preserves valid filename")
    fun testSanitizeFilenamePreservesValidFilename() {
        val result = MediaUtils.sanitizeFilename("my-video_clip.mp4")
        assertEquals("my-video_clip.mp4", result)
    }

    // ---- requirePathWithinBase ----

    @Test
    @DisplayName("requirePathWithinBase allows valid path")
    fun testRequirePathWithinBaseAllowsValidPath() {
        val baseDir = Path.of("/tmp/media")
        MediaUtils.requirePathWithinBase(baseDir, baseDir.resolve("video.mp4"))
    }

    @Test
    @DisplayName("requirePathWithinBase rejects path traversal")
    fun testRequirePathWithinBaseRejectsPathTraversal() {
        val baseDir = Path.of("/tmp/media")
        assertThrows<IllegalArgumentException> {
            MediaUtils.requirePathWithinBase(baseDir, Path.of("/etc/passwd"))
        }
    }

    // ---- formatFileSize ----

    @Test
    @DisplayName("formatFileSize formats bytes")
    fun testFormatFileSizeFormatsBytes() {
        assertTrue(MediaUtils.formatFileSize(500).contains("B"))
    }

    @Test
    @DisplayName("formatFileSize formats KB")
    fun testFormatFileSizeFormatsKb() {
        assertTrue(MediaUtils.formatFileSize(2048).contains("KB"))
    }

    @Test
    @DisplayName("formatFileSize formats MB")
    fun testFormatFileSizeFormatsMb() {
        assertTrue(MediaUtils.formatFileSize(50 * 1024 * 1024).contains("MB"))
    }

    @Test
    @DisplayName("formatFileSize formats GB")
    fun testFormatFileSizeFormatsGb() {
        assertTrue(MediaUtils.formatFileSize(2L * 1024 * 1024 * 1024).contains("GB"))
    }
}
