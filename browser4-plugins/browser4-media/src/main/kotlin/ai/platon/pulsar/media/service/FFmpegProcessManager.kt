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

import ai.platon.pulsar.agentic.model.DirectValue
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.media.config.MediaConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/**
 * Manages FFmpeg/ffprobe subprocesses for media processing.
 *
 * All process execution follows the same pattern as [AgentShell.runCommand]:
 * [ProcessBuilder] with concurrent stdout/stderr reading and configurable timeout.
 *
 * Error handling: process failures return [ProcessResult] with `success = false` —
 * they never throw, allowing LLM agents to inspect and retry.
 */
open class FFmpegProcessManager(
    private val config: MediaConfig,
) {
    private val logger = getLogger(FFmpegProcessManager::class)
    private val mapper = jacksonObjectMapper()

    /**
     * Result of running an ffprobe on a media file.
     */
    data class ProbeResult(
        /** Container format (e.g., "mov,mp4,m4a") */
        val format: String? = null,
        /** Duration in seconds */
        val duration: Double? = null,
        /** Video width in pixels */
        val width: Int? = null,
        /** Video height in pixels */
        val height: Int? = null,
        /** Video codec name */
        val codec: String? = null,
        /** Overall bitrate in bits/second */
        val bitrate: Long? = null,
        /** List of all streams in the file */
        val streams: List<StreamInfo> = emptyList(),
    ) : DirectValue

    /**
     * Information about a single media stream.
     */
    data class StreamInfo(
        val index: Int = 0,
        val codecType: String = "",
        val codecName: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val language: String? = null,
    )

    /**
     * Result of an FFmpeg process execution.
     */
    data class ProcessResult(
        /** The full command that was executed */
        val command: String,
        /** Process exit code (0 = success, -1 = timed out / not found) */
        val exitCode: Int,
        /** Captured stdout */
        val stdout: String = "",
        /** Captured stderr */
        val stderr: String = "",
        /** Total duration in milliseconds */
        val durationMs: Long,
        /** Whether the process timed out */
        val timedOut: Boolean = false,
    ) : DirectValue {
        val success: Boolean get() = exitCode == 0 && !timedOut
    }

    // ---- Probe ----

    /**
     * Probe a media file using ffprobe and return structured metadata.
     *
     * @param filePath path to an existing media file
     * @return [ProbeResult] with format info, streams, duration, etc.
     */
    open suspend fun probe(filePath: String): ProbeResult = withContext(Dispatchers.IO) {
        val path = Path.of(filePath)
        require(Files.exists(path)) { "File not found: $filePath" }

        val result = runCommand(
            listOf(config.ffprobePath, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", filePath),
            timeoutSeconds = 30
        )

        if (!result.success) {
            logger.warn("ffprobe failed: {}", result.stderr)
            return@withContext ProbeResult()
        }

        try {
            val json = result.stdout
            val root = mapper.readTree(json)

            // Parse format
            val formatNode = root.get("format")
            val format = formatNode?.get("format_name")?.asText()
            val duration = formatNode?.get("duration")?.asDouble()
            val bitrate = formatNode?.get("bit_rate")?.asLong()

            // Parse streams
            val streamsNode = root.get("streams")
            val streams = mutableListOf<StreamInfo>()
            var width: Int? = null
            var height: Int? = null
            var codec: String? = null

            if (streamsNode != null && streamsNode.isArray) {
                for (stream in streamsNode) {
                    val codecType = stream.get("codec_type")?.asText() ?: ""
                    val streamInfo = StreamInfo(
                        index = stream.get("index")?.asInt() ?: 0,
                        codecType = codecType,
                        codecName = stream.get("codec_name")?.asText(),
                        width = stream.get("width")?.asInt(),
                        height = stream.get("height")?.asInt(),
                        language = stream.get("tags")?.get("language")?.asText(),
                    )
                    streams.add(streamInfo)

                    // Capture first video stream dimensions and codec
                    if (codecType == "video" && width == null) {
                        width = streamInfo.width
                        height = streamInfo.height
                        codec = streamInfo.codecName
                    }
                }
            }

            ProbeResult(
                format = format,
                duration = duration,
                width = width,
                height = height,
                codec = codec,
                bitrate = bitrate,
                streams = streams,
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse ffprobe output: {}", e.message)
            ProbeResult()
        }
    }

    // ---- Structured FFmpeg Operations ----

    /**
     * Transcode (or just remux) a media file with optional extra FFmpeg arguments.
     */
    open suspend fun transcode(
        inputPath: String,
        outputPath: String,
        extraArgs: List<String> = emptyList(),
    ): ProcessResult {
        val args = buildList {
            addAll(listOf("-i", inputPath))
            addAll(extraArgs)
            add(outputPath)
        }
        return runFFmpeg(args)
    }

    /**
     * Extract the audio track from a video file.
     *
     * @param format audio codec (default: "libmp3lame", pass "aac" for AAC)
     */
    open suspend fun extractAudio(
        inputPath: String,
        outputPath: String,
        format: String = "libmp3lame",
    ): ProcessResult {
        val args = listOf(
            "-i", inputPath,
            "-vn", // no video
            "-acodec", format,
            outputPath
        )
        return runFFmpeg(args)
    }

    /**
     * Trim a segment from a media file.
     *
     * @param startTime start time in HH:MM:SS.mmm or seconds
     * @param duration  duration in HH:MM:SS.mmm or seconds
     */
    open suspend fun trim(
        inputPath: String,
        startTime: String,
        duration: String,
        outputPath: String,
    ): ProcessResult {
        val args = listOf(
            "-i", inputPath,
            "-ss", startTime,
            "-t", duration,
            "-c", "copy",
            outputPath
        )
        return runFFmpeg(args)
    }

    /**
     * Compress/re-encode a video using H.264 with configurable CRF.
     *
     * @param crf Constant Rate Factor (0-51, lower = better quality, default 23)
     */
    open suspend fun compress(
        inputPath: String,
        outputPath: String,
        crf: Int = 23,
    ): ProcessResult {
        require(crf in 0..51) { "CRF must be between 0 and 51, got $crf" }
        val args = listOf(
            "-i", inputPath,
            "-c:v", "libx264",
            "-crf", crf.toString(),
            "-preset", "medium",
            "-c:a", "aac",
            "-b:a", "128k",
            outputPath
        )
        return runFFmpeg(args)
    }

    // ---- Core FFmpeg Execution ----

    /**
     * Run FFmpeg with custom arguments.
     *
     * Follows the same pattern as [AgentShell.runCommand]: ProcessBuilder with
     * concurrent stdout/stderr reading via CompletableFuture, configurable timeout,
     * and destroyForcibly() on timeout.
     */
    open suspend fun runFFmpeg(
        args: List<String>,
        timeoutSeconds: Long = config.ffmpegTimeoutSeconds,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val command = listOf(config.ffmpegPath) + args
        val commandStr = command.joinToString(" ")
        val startTime = System.currentTimeMillis()

        logger.debug("Running: {}", commandStr)

        try {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(false)

            val process = pb.start()

            // Read stdout and stderr concurrently to avoid deadlock
            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            if (!completed) {
                process.destroyForcibly()
                logger.warn("FFmpeg timed out after {}s: {}", timeoutSeconds, commandStr)
                return@withContext ProcessResult(
                    command = commandStr,
                    exitCode = -1,
                    stdout = stdoutFuture.getNow(""),
                    stderr = stderrFuture.getNow(""),
                    durationMs = durationMs,
                    timedOut = true,
                )
            }

            val result = ProcessResult(
                command = commandStr,
                exitCode = process.exitValue(),
                stdout = stdoutFuture.get(),
                stderr = stderrFuture.get(),
                durationMs = durationMs,
            )

            if (result.exitCode != 0) {
                logger.warn("FFmpeg exited with {}: {}\nstderr: {}",
                    result.exitCode, commandStr, result.stderr.take(500))
            } else {
                logger.info("FFmpeg completed in {}ms: {}", durationMs, commandStr)
            }

            result
        } catch (e: IOException) {
            val durationMs = System.currentTimeMillis() - startTime
            val errorMsg = if (e.message?.contains("No such file") == true || e.message?.contains("Cannot run") == true) {
                "FFmpeg binary not found at '${config.ffmpegPath}'. Set media.ffmpeg.path in configuration."
            } else {
                "FFmpeg IO error: ${e.message}"
            }
            logger.warn(errorMsg)
            ProcessResult(
                command = commandStr,
                exitCode = -1,
                stdout = "",
                stderr = errorMsg,
                durationMs = durationMs,
                timedOut = false,
            )
        }
    }

    /**
     * Run an arbitrary command and capture its output (used internally for ffprobe).
     */
    private suspend fun runCommand(
        command: List<String>,
        timeoutSeconds: Long = 30,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val commandStr = command.joinToString(" ")

        try {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)

            val process = pb.start()
            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            if (!completed) {
                process.destroyForcibly()
                return@withContext ProcessResult(
                    command = commandStr,
                    exitCode = -1,
                    stdout = stdoutFuture.getNow(""),
                    durationMs = durationMs,
                    timedOut = true,
                )
            }

            ProcessResult(
                command = commandStr,
                exitCode = process.exitValue(),
                stdout = stdoutFuture.get(),
                durationMs = durationMs,
            )
        } catch (e: IOException) {
            val durationMs = System.currentTimeMillis() - startTime
            ProcessResult(
                command = commandStr,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "IO error",
                durationMs = durationMs,
            )
        }
    }
}
