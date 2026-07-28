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

import ai.platon.pulsar.media.config.MediaConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Tests for [FFmpegProcessManager].
 *
 * Tests that require ffmpeg/ffprobe binaries are conditional on [ffmpegAvailable]
 * and [ffprobeAvailable] companion functions. Logic-only tests run unconditionally.
 */
class FFmpegProcessManagerTest {

    private lateinit var processor: FFmpegProcessManager

    @BeforeEach
    fun setUp() {
        processor = FFmpegProcessManager(MediaConfig(
            ffmpegPath = "ffmpeg",
            ffprobePath = "ffprobe",
            ffmpegTimeoutSeconds = 60,
        ))
    }

    // ---- ProbeResult ----

    @Test
    @DisplayName("ProbeResult defaults are null/empty")
    fun testProbeResultDefaults() {
        val result = FFmpegProcessManager.ProbeResult()
        assertNull(result.format)
        assertNull(result.duration)
        assertNull(result.width)
        assertNull(result.height)
        assertNull(result.codec)
        assertNull(result.bitrate)
        assertTrue(result.streams.isEmpty())
    }

    // ---- ProcessResult ----

    @Test
    @DisplayName("ProcessResult.success=false when exitCode!=0")
    fun testProcessResultSuccessFalseOnError() {
        val result = FFmpegProcessManager.ProcessResult(
            command = "ffmpeg", exitCode = 1, stdout = "", stderr = "error", durationMs = 100,
        )
        assertFalse(result.success)
    }

    @Test
    @DisplayName("ProcessResult.success=false when timedOut")
    fun testProcessResultSuccessFalseOnTimeout() {
        val result = FFmpegProcessManager.ProcessResult(
            command = "ffmpeg", exitCode = 0, stdout = "", stderr = "", durationMs = 60000, timedOut = true,
        )
        assertFalse(result.success)
    }

    @Test
    @DisplayName("ProcessResult.success=true only when exitCode=0 and not timedOut")
    fun testProcessResultSuccessTrueWhenClean() {
        val result = FFmpegProcessManager.ProcessResult(
            command = "ffmpeg", exitCode = 0, stdout = "done", stderr = "", durationMs = 1000,
        )
        assertTrue(result.success)
    }

    // ---- compress parameter validation ----

    @Test
    @DisplayName("compress rejects CRF below 0")
    fun testCompressRejectsCrfBelow0() {
        try {
            runBlocking { processor.compress("/tmp/in.mp4", "/tmp/out.mp4", crf = -1) }
            fail("Expected IllegalArgumentException for CRF=-1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("CRF") ?: false)
        }
    }

    @Test
    @DisplayName("compress rejects CRF above 51")
    fun testCompressRejectsCrfAbove51() {
        try {
            runBlocking { processor.compress("/tmp/in.mp4", "/tmp/out.mp4", crf = 52) }
            fail("Expected IllegalArgumentException for CRF=52")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("CRF") ?: false)
        }
    }

    @Test
    @DisplayName("compress accepts valid CRF values (does not throw on CRF check)")
    fun testCompressAcceptsValidCrf() {
        // CRF validation passes; the call will fail later because input doesn't exist,
        // but the require() check should not throw.
        try {
            runBlocking { processor.compress("/tmp/nonexistent.mp4", "/tmp/out.mp4", crf = 23) }
            // If we get here without the require() throwing, CRF validation passed
        } catch (e: IllegalArgumentException) {
            fail("CRF=23 should be valid, got: ${e.message}")
        }
    }

    // ---- runFFmpeg with invalid binary ----

    @Test
    @DisplayName("runFFmpeg returns error when binary not found")
    fun testRunFfmpegBinaryNotFound() = runBlocking {
        val badProcessor = FFmpegProcessManager(
            MediaConfig(ffmpegPath = "/nonexistent/path/ffmpeg_does_not_exist")
        )
        val result = badProcessor.runFFmpeg(listOf("-version"))
        assertFalse(result.success)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("not found"), "Expected 'not found', got: ${result.stderr}")
    }

    // ---- probe with invalid ffprobe binary ----

    @Test
    @DisplayName("probe returns empty result when ffprobe binary not found")
    fun testProbeFfprobeNotFound() = runBlocking {
        val badProcessor = FFmpegProcessManager(
            MediaConfig(ffprobePath = "/nonexistent/path/ffprobe_does_not_exist")
        )
        // Create a temp file so the "file not found" check passes
        val tempFile = Files.createTempFile("test-media", ".mp4")
        try {
            val result = badProcessor.probe(tempFile.toString())
            assertNull(result.format)
            assertNull(result.duration)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    @DisplayName("probe throws when file does not exist")
    fun testProbeFileNotFound() {
        try {
            runBlocking { processor.probe("/nonexistent/video.mp4") }
            fail("Expected IllegalArgumentException for nonexistent file")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("File not found") ?: false)
        }
    }

    // ---- transcode with nonexistent input ----

    @Test
    @DisplayName("transcode returns failure for nonexistent input")
    fun testTranscodeNonexistentInput() = runBlocking {
        val result = processor.transcode("/nonexistent/input.mp4", "/tmp/out.mp4")
        assertFalse(result.success)
    }

    // ---- Conditional tests requiring ffmpeg ----

    @Test
    @DisplayName("ffmpeg -version runs successfully")
    @EnabledIf("ffmpegAvailable")
    fun testFfmpegVersion() = runBlocking {
        val result = processor.runFFmpeg(listOf("-version"))
        assertTrue(result.success, "ffmpeg -version should succeed, got: ${result.stderr}")
        assertTrue(
            result.stdout.contains("ffmpeg") || result.stderr.contains("ffmpeg"),
            "Output should mention ffmpeg"
        )
    }

    @Test
    @DisplayName("ffprobe probes a generated mp4 file")
    @EnabledIf("ffprobeAvailable")
    fun testFfprobeGeneratedMp4() = runBlocking {
        val tempDir = Files.createTempDirectory("media-test-")
        try {
            val testFile = tempDir.resolve("test.mp4")
            val createResult = processor.runFFmpeg(
                listOf(
                    "-f", "lavfi", "-i", "testsrc=duration=1:size=320:240:rate=25",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                    "-c:v", "libx264", "-c:a", "aac",
                    "-shortest", testFile.toString()
                ),
                timeoutSeconds = 30
            )
            assumeTrue(createResult.success, "Could not create test video: ${createResult.stderr}")

            val probe = processor.probe(testFile.toString())
            assertEquals("mov,mp4,m4a", probe.format)
            assertNotNull(probe.duration)
            assertTrue(probe.duration!! > 0)
            assertEquals(320, probe.width)
            assertEquals(240, probe.height)
            assertTrue(probe.streams.any { it.codecType == "video" })
            assertTrue(probe.streams.any { it.codecType == "audio" })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("ffmpeg extract audio from generated video")
    @EnabledIf("ffmpegAvailable")
    fun testExtractAudio() = runBlocking {
        val tempDir = Files.createTempDirectory("media-test-")
        try {
            val testVideo = tempDir.resolve("test.mp4")
            val createResult = processor.runFFmpeg(
                listOf(
                    "-f", "lavfi", "-i", "testsrc=duration=2:size=160:120:rate=10",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                    "-shortest", "-c:v", "libx264", "-c:a", "aac",
                    testVideo.toString()
                ),
                timeoutSeconds = 30
            )
            assumeTrue(createResult.success, "Could not create test video: ${createResult.stderr}")

            val audioOutput = tempDir.resolve("audio.aac")
            val extractResult = processor.extractAudio(testVideo.toString(), audioOutput.toString(), "aac")
            assertTrue(extractResult.success, "Extract should succeed: ${extractResult.stderr}")
            assertTrue(Files.exists(audioOutput))
            assertTrue(Files.size(audioOutput) > 0)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("ffmpeg trim extracts a segment")
    @EnabledIf("ffmpegAvailable")
    fun testTrimSegment() = runBlocking {
        val tempDir = Files.createTempDirectory("media-test-")
        try {
            val testVideo = tempDir.resolve("test.mp4")
            val createResult = processor.runFFmpeg(
                listOf(
                    "-f", "lavfi", "-i", "testsrc=duration=3:size=160:120:rate=10",
                    "-c:v", "libx264", testVideo.toString()
                ),
                timeoutSeconds = 30
            )
            assumeTrue(createResult.success, "Could not create test video: ${createResult.stderr}")

            val trimOutput = tempDir.resolve("trimmed.mp4")
            val trimResult = processor.trim(testVideo.toString(), "1", "1.5", trimOutput.toString())
            assertTrue(trimResult.success, "Trim should succeed: ${trimResult.stderr}")
            assertTrue(Files.exists(trimOutput))
            assertTrue(Files.size(trimOutput) > 0)

            val probe = processor.probe(trimOutput.toString())
            assertNotNull(probe.duration)
            assertTrue(probe.duration!! in 1.0..2.0,
                "Trimmed duration should be ~1.5s, got ${probe.duration}")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    // ---- helpers ----

    companion object {
        @JvmStatic
        fun ffmpegAvailable(): Boolean {
            return try {
                val pb = ProcessBuilder("ffmpeg", "-version")
                pb.redirectErrorStream(true)
                pb.start().waitFor() == 0
            } catch (_: Exception) { false }
        }

        @JvmStatic
        fun ffprobeAvailable(): Boolean {
            return try {
                val pb = ProcessBuilder("ffprobe", "-version")
                pb.redirectErrorStream(true)
                pb.start().waitFor() == 0
            } catch (_: Exception) { false }
        }
    }
}
