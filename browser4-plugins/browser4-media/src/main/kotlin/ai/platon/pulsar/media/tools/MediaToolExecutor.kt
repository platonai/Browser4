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

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.media.config.MediaConfig
import ai.platon.pulsar.media.service.FFmpegProcessManager
import ai.platon.pulsar.media.service.MediaDownloader
import ai.platon.pulsar.media.service.VideoDetector
import kotlin.reflect.KClass
import java.nio.file.Path

/**
 * LLM agent tool executor for the `media` domain.
 *
 * Provides AI agents with the ability to:
 * - `media.detectVideos()` — scan the current page for video sources
 * - `media.download(url, outputPath?, filename?)` — download a media file via OkHttp
 * - `media.process(inputPath, ffmpegArgs, outputPath)` — run arbitrary FFmpeg commands
 * - `media.extractAudio(inputPath, outputPath, format?)` — extract audio track
 * - `media.trim(inputPath, startTime, duration, outputPath)` — trim a segment
 * - `media.compress(inputPath, outputPath, crf?)` — compress/re-encode video
 * - `media.getInfo(filePath)` — probe media file metadata
 */
open class MediaToolExecutor(
    private val videoDetector: VideoDetector,
    private val mediaDownloader: MediaDownloader,
    private val ffmpegProcessor: FFmpegProcessManager,
    private val config: MediaConfig,
) : AbstractToolExecutor() {
    private val logger = getLogger(MediaToolExecutor::class)

    override val domain = "media"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["detectVideos"] = ToolSpec(
            domain = domain,
            method = "detectVideos",
            arguments = emptyList(),
            returnType = "List<VideoSource>",
            description = "Detect all video elements on the current page, including <video> tags, <source> children, embedded iframes (YouTube, Vimeo), and direct links to media files.",
            help = """
                media.detectVideos()

                Scans the current page DOM for video sources. Returns a list of detected video sources
                with metadata (URL, type, dimensions, etc.). Does NOT download any videos.
            """.trimIndent()
        )

        toolSpec["download"] = ToolSpec(
            domain = domain,
            method = "download",
            arguments = listOf(
                ToolSpec.Arg("url", "String"),
                ToolSpec.Arg("outputPath", "String?", "null"),
                ToolSpec.Arg("filename", "String?", "null"),
            ),
            returnType = "DownloadResult",
            description = "Download a video/audio file from a URL using direct HTTP (bypasses browser blocking). Supports mp4, webm, m3u8, and more.",
            help = """
                media.download(url: String)
                media.download(url: String, outputPath: String?)
                media.download(url: String, outputPath: String?, filename: String?)

                Downloads a media file to the configured download directory (default: downloads/media/)
                or a custom outputPath. Returns DownloadResult with filePath, bytesDownloaded, etc.
            """.trimIndent()
        )

        toolSpec["process"] = ToolSpec(
            domain = domain,
            method = "process",
            arguments = listOf(
                ToolSpec.Arg("inputPath", "String"),
                ToolSpec.Arg("ffmpegArgs", "String"),
                ToolSpec.Arg("outputPath", "String"),
            ),
            returnType = "ProcessResult",
            description = "Process a media file with custom FFmpeg arguments. The args string is split by whitespace and passed directly to FFmpeg.",
            help = """
                media.process(inputPath: String, ffmpegArgs: String, outputPath: String)

                Runs FFmpeg with the specified arguments. Example:
                media.process("input.mp4", "-vf scale=640:360 -r 30", "output.mp4")

                CAUTION: This runs arbitrary FFmpeg commands. Use the dedicated methods
                (extractAudio, trim, compress) for safer operations.
            """.trimIndent()
        )

        toolSpec["extractAudio"] = ToolSpec(
            domain = domain,
            method = "extractAudio",
            arguments = listOf(
                ToolSpec.Arg("inputPath", "String"),
                ToolSpec.Arg("outputPath", "String"),
                ToolSpec.Arg("format", "String?", "libmp3lame"),
            ),
            returnType = "ProcessResult",
            description = "Extract audio track from a video file into an audio file (default MP3, use 'aac' for AAC).",
            help = """
                media.extractAudio(inputPath: String, outputPath: String)
                media.extractAudio(inputPath: String, outputPath: String, format: String? = "libmp3lame")

                Extracts the audio stream and saves as an audio file. Format codes:
                  libmp3lame = MP3, aac = AAC, libvorbis = OGG Vorbis
            """.trimIndent()
        )

        toolSpec["trim"] = ToolSpec(
            domain = domain,
            method = "trim",
            arguments = listOf(
                ToolSpec.Arg("inputPath", "String"),
                ToolSpec.Arg("startTime", "String"),
                ToolSpec.Arg("duration", "String"),
                ToolSpec.Arg("outputPath", "String"),
            ),
            returnType = "ProcessResult",
            description = "Trim a video/audio segment from a media file. Time format: HH:MM:SS.mmm or seconds.",
            help = """
                media.trim(inputPath: String, startTime: String, duration: String, outputPath: String)

                Extracts a segment from the input media. Examples:
                media.trim("input.mp4", "00:01:30", "00:00:15", "clip.mp4")  -- 15s clip starting at 1:30
                media.trim("input.mp4", "10", "5.5", "clip.mp4")              -- 5.5s clip starting at 10s
            """.trimIndent()
        )

        toolSpec["compress"] = ToolSpec(
            domain = domain,
            method = "compress",
            arguments = listOf(
                ToolSpec.Arg("inputPath", "String"),
                ToolSpec.Arg("outputPath", "String"),
                ToolSpec.Arg("crf", "Int?", "23"),
            ),
            returnType = "ProcessResult",
            description = "Compress/re-encode a video using H.264 with configurable CRF (0-51, lower = better quality, default 23).",
            help = """
                media.compress(inputPath: String, outputPath: String)
                media.compress(inputPath: String, outputPath: String, crf: Int? = 23)

                Re-encodes with H.264. CRF guide: 18=visually lossless, 23=good default, 28=acceptable, 35=low quality.
            """.trimIndent()
        )

        toolSpec["getInfo"] = ToolSpec(
            domain = domain,
            method = "getInfo",
            arguments = listOf(
                ToolSpec.Arg("filePath", "String"),
            ),
            returnType = "ProbeResult",
            description = "Get media file metadata (duration, codec, resolution, bitrate, streams) using ffprobe.",
            help = """
                media.getInfo(filePath: String)

                Returns detailed information about a media file: format, duration, resolution, codec, bitrate, and all streams.
            """.trimIndent()
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        val driver = receiver as? WebDriver

        return when (functionName) {
            "detectVideos" -> {
                requireNotNull(driver) { "detectVideos requires a WebDriver receiver (current page context)" }
                videoDetector.detect(driver)
            }

            "download" -> {
                val url = paramString(args, "url", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val filename = paramString(args, "filename", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.downloadDir)
                mediaDownloader.download(url, dir, filename)
            }

            "process" -> {
                val inputPath = paramString(args, "inputPath", functionName)!!
                val ffmpegArgs = paramString(args, "ffmpegArgs", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName)!!
                val extraArgs = ffmpegArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
                ffmpegProcessor.transcode(inputPath, outputPath, extraArgs)
            }

            "extractAudio" -> {
                val inputPath = paramString(args, "inputPath", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName)!!
                val format = paramString(args, "format", functionName, required = false, default = "libmp3lame")!!
                ffmpegProcessor.extractAudio(inputPath, outputPath, format)
            }

            "trim" -> {
                val inputPath = paramString(args, "inputPath", functionName)!!
                val startTime = paramString(args, "startTime", functionName)!!
                val duration = paramString(args, "duration", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName)!!
                ffmpegProcessor.trim(inputPath, startTime, duration, outputPath)
            }

            "compress" -> {
                val inputPath = paramString(args, "inputPath", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName)!!
                val crf = paramInt(args, "crf", functionName, required = false, default = 23) ?: 23
                ffmpegProcessor.compress(inputPath, outputPath, crf)
            }

            "getInfo" -> {
                val filePath = paramString(args, "filePath", functionName)!!
                ffmpegProcessor.probe(filePath)
            }

            else -> throw IllegalArgumentException(
                "Unsupported media method: $functionName. " +
                    "Supported: detectVideos, download, process, extractAudio, trim, compress, getInfo."
            )
        }
    }
}
