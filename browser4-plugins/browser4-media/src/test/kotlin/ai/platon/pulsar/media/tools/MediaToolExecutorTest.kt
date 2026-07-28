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
package ai.platon.pulsar.media.tools

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.media.config.MediaConfig
import ai.platon.pulsar.media.service.FFmpegProcessManager
import ai.platon.pulsar.media.service.MediaDownloader
import ai.platon.pulsar.media.service.VideoDetector
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Path

/**
 * Tests for [MediaToolExecutor] — verifies all 7 tool method dispatches.
 *
 * Uses anonymous subclass overrides (services are open) and Java dynamic proxy
 * stubs (WebDriver). No MockK dependency required.
 */
class MediaToolExecutorTest {

    private lateinit var executor: MediaToolExecutor
    private lateinit var config: MediaConfig
    private lateinit var driver: WebDriver

    // Call trackers
    private var detectCallCount = 0
    private var downloadCallCount = 0
    private var transcodeCallCount = 0
    private var extractAudioCallCount = 0
    private var trimCallCount = 0
    private var compressCallCount = 0
    private var probeCallCount = 0

    @BeforeEach
    fun setUp() {
        config = MediaConfig(downloadDir = "/tmp/test-media")
        driver = webDriverProxy()

        detectCallCount = 0; downloadCallCount = 0; transcodeCallCount = 0
        extractAudioCallCount = 0; trimCallCount = 0; compressCallCount = 0; probeCallCount = 0

        val videoDetector = object : VideoDetector() {
            override suspend fun detect(d: WebDriver): List<VideoDetector.VideoSource> {
                detectCallCount++
                return listOf(VideoDetector.VideoSource(tagName = "video", resolvedUrl = "https://x.com/v.mp4"))
            }
        }
        val mediaDownloader = object : MediaDownloader(config, OkHttpClient()) {
            override suspend fun download(
                url: String, outputDir: Path, filename: String?, headers: Map<String, String>
            ): MediaDownloader.DownloadResult {
                downloadCallCount++
                return MediaDownloader.DownloadResult(
                    url = url,
                    filePath = outputDir.resolve(filename ?: "video.mp4").toString(),
                    bytesDownloaded = 1024000, contentType = "video/mp4", durationMs = 1500, success = true,
                )
            }
        }
        val ffmpegProcessor = object : FFmpegProcessManager(config) {
            override suspend fun transcode(
                inputPath: String, outputPath: String, extraArgs: List<String>
            ): FFmpegProcessManager.ProcessResult {
                transcodeCallCount++
                return okResult("transcode")
            }
            override suspend fun extractAudio(
                inputPath: String, outputPath: String, format: String
            ): FFmpegProcessManager.ProcessResult {
                extractAudioCallCount++
                return okResult("extractAudio")
            }
            override suspend fun trim(
                inputPath: String, startTime: String, duration: String, outputPath: String
            ): FFmpegProcessManager.ProcessResult {
                trimCallCount++
                return okResult("trim")
            }
            override suspend fun compress(
                inputPath: String, outputPath: String, crf: Int
            ): FFmpegProcessManager.ProcessResult {
                compressCallCount++
                return okResult("compress")
            }
            override suspend fun probe(filePath: String): FFmpegProcessManager.ProbeResult {
                probeCallCount++
                return FFmpegProcessManager.ProbeResult(
                    format = "mov,mp4,m4a", duration = 120.5,
                    width = 1920, height = 1080, codec = "h264", bitrate = 5000000,
                )
            }
            private fun okResult(label: String) = FFmpegProcessManager.ProcessResult(
                command = "ffmpeg-$label", exitCode = 0, durationMs = 1000,
            )
        }

        executor = MediaToolExecutor(videoDetector, mediaDownloader, ffmpegProcessor, config)
    }

    // ---- detectVideos ----

    @Test
    @DisplayName("detectVideos requires WebDriver receiver")
    fun testDetectVideosRequiresWebDriver() = runBlocking {
        val tc = ToolCall("media", "detectVideos")
        val result = executor.callFunctionOn(tc, "not a driver")
        assertNotNull(result.exception)
        assertTrue(result.exception?.message?.contains("requires a WebDriver") ?: false)
        assertEquals(0, detectCallCount)
    }

    @Test
    @DisplayName("detectVideos returns list")
    fun testDetectVideosReturnsList() = runBlocking {
        val tc = ToolCall("media", "detectVideos")
        val result = executor.callFunctionOn(tc, driver)
        assertNotNull(result.value)
        assertEquals(1, (result.value as List<*>).size)
        assertEquals(1, detectCallCount)
    }

    // ---- download ----

    @Test
    @DisplayName("download throws when url missing")
    fun testDownloadThrowsWhenUrlMissing() = runBlocking {
        val tc = ToolCall("media", "download")
        val result = executor.callFunctionOn(tc, driver)
        assertNotNull(result.exception)
        assertTrue(
            result.exception?.message?.contains("url") ?: false,
            "Expected error to mention 'url', got: ${result.exception?.message}"
        )
    }

    @Test
    @DisplayName("download with url succeeds")
    fun testDownloadWithUrlSucceeds() = runBlocking {
        val tc = ToolCall("media", "download", mutableMapOf("url" to "https://example.com/v.mp4"))
        val result = executor.callFunctionOn(tc, driver)
        assertNotNull(result.value)
        assertTrue((result.value as MediaDownloader.DownloadResult).success)
        assertEquals(1, downloadCallCount)
    }

    @Test
    @DisplayName("download with custom path and filename")
    fun testDownloadWithCustomPath() = runBlocking {
        val tc = ToolCall("media", "download", mutableMapOf(
            "url" to "https://example.com/v.mp4", "outputPath" to "/custom", "filename" to "c.mp4",
        ))
        val result = executor.callFunctionOn(tc, driver)
        assertTrue((result.value as MediaDownloader.DownloadResult).success)
        assertEquals(1, downloadCallCount)
    }

    // ---- process ----

    @Test
    @DisplayName("process splits ffmpegArgs and delegates")
    fun testProcessSplitsArgs() = runBlocking {
        val tc = ToolCall("media", "process", mutableMapOf(
            "inputPath" to "/tmp/in.mp4", "ffmpegArgs" to "-vf scale=640:360", "outputPath" to "/tmp/out.mp4",
        ))
        val result = executor.callFunctionOn(tc, driver)
        assertTrue((result.value as FFmpegProcessManager.ProcessResult).success)
        assertEquals(1, transcodeCallCount)
    }

    @Test
    @DisplayName("process works without WebDriver")
    fun testProcessNoWebDriver() = runBlocking {
        val tc = ToolCall("media", "process", mutableMapOf(
            "inputPath" to "/tmp/in.mp4", "ffmpegArgs" to "", "outputPath" to "/tmp/out.mp4",
        ))
        val result = executor.callFunctionOn(tc, "anyReceiver")
        assertTrue((result.value as FFmpegProcessManager.ProcessResult).success)
    }

    // ---- extractAudio ----

    @Test
    @DisplayName("extractAudio default format")
    fun testExtractAudioDefaultFormat() = runBlocking {
        val tc = ToolCall("media", "extractAudio", mutableMapOf(
            "inputPath" to "/tmp/in.mp4", "outputPath" to "/tmp/out.mp3",
        ))
        executor.callFunctionOn(tc, driver)
        assertEquals(1, extractAudioCallCount)
    }

    // ---- trim ----

    @Test
    @DisplayName("trim delegates args")
    fun testTrimDelegates() = runBlocking {
        val tc = ToolCall("media", "trim", mutableMapOf(
            "inputPath" to "/tmp/in.mp4", "startTime" to "00:01:00",
            "duration" to "00:00:30", "outputPath" to "/tmp/clip.mp4",
        ))
        val result = executor.callFunctionOn(tc, driver)
        assertTrue((result.value as FFmpegProcessManager.ProcessResult).success)
        assertEquals(1, trimCallCount)
    }

    // ---- compress ----

    @Test
    @DisplayName("compress default CRF 23")
    fun testCompressDefaultCrf23() = runBlocking {
        val tc = ToolCall("media", "compress", mutableMapOf(
            "inputPath" to "/tmp/in.mp4", "outputPath" to "/tmp/out.mp4",
        ))
        executor.callFunctionOn(tc, driver)
        assertEquals(1, compressCallCount)
    }

    @Test
    @DisplayName("compress custom CRF")
    fun testCompressCustomCrf() = runBlocking {
        val tc = ToolCall("media", "compress", mutableMapOf(
            "inputPath" to "/tmp/in.mp4", "outputPath" to "/tmp/out.mp4", "crf" to "18",
        ))
        executor.callFunctionOn(tc, driver)
        assertEquals(1, compressCallCount)
    }

    // ---- getInfo ----

    @Test
    @DisplayName("getInfo returns probe data")
    fun testGetInfoReturnsProbeData() = runBlocking {
        val tc = ToolCall("media", "getInfo", mutableMapOf("filePath" to "/tmp/video.mp4"))
        val result = executor.callFunctionOn(tc, driver)
        val info = result.value as FFmpegProcessManager.ProbeResult
        assertEquals("mov,mp4,m4a", info.format)
        assertEquals(120.5, info.duration)
        assertEquals(1920, info.width)
        assertEquals("h264", info.codec)
        assertEquals(1, probeCallCount)
    }

    // ---- Error handling ----

    @Test
    @DisplayName("unknown method returns error")
    fun testUnknownMethod() = runBlocking {
        val tc = ToolCall("media", "unknownMethod")
        val result = executor.callFunctionOn(tc, driver)
        assertNotNull(result.exception)
        assertTrue(result.exception?.message?.contains("Unsupported media method") ?: false)
    }

    // ---- ToolSpec metadata ----

    @Test
    @DisplayName("domain is media")
    fun testDomain() = assertEquals("media", executor.domain)

    @Test
    @DisplayName("receiverClass is WebDriver")
    fun testReceiverClass() = assertEquals(WebDriver::class, executor.receiverClass)

    @Test
    @DisplayName("all 7 tool specs registered")
    fun testAll7ToolSpecs() {
        val specs = executor.getToolSpecs()
        assertEquals(7, specs.size)
        listOf("detectVideos", "download", "process", "extractAudio", "trim", "compress", "getInfo").forEach {
            assertTrue(specs.containsKey(it), "Missing: $it")
        }
    }

    @Test
    @DisplayName("help returns non-blank")
    fun testHelp() {
        val help = executor.help()
        assertTrue(help.isNotBlank())
        assertTrue(help.contains("detect") || help.contains("video") || help.contains("Download") || help.contains("download"))
    }

    @Test
    @DisplayName("help per method is non-blank")
    fun testHelpPerMethod() {
        listOf("detectVideos", "download", "process", "extractAudio", "trim", "compress", "getInfo").forEach {
            assertTrue(executor.help(it).isNotBlank(), "Help for '$it' should not be blank")
        }
    }

    @Test
    @DisplayName("help for unknown method is empty")
    fun testHelpUnknown() = assertEquals("", executor.help("nonexistent"))

    // ---- helpers ----

    @Suppress("UNCHECKED_CAST")
    private fun webDriverProxy(): WebDriver {
        return Proxy.newProxyInstance(
            WebDriver::class.java.classLoader,
            arrayOf(WebDriver::class.java)
        ) { _, _, _ -> null } as WebDriver
    }
}
