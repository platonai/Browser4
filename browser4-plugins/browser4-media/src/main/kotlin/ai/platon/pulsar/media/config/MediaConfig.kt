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
package ai.platon.pulsar.media.config

import ai.platon.pulsar.common.config.ImmutableConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration holder for the browser4-media plugin.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class MediaConfig(
    /** Path to the FFmpeg binary. Default: "ffmpeg" (system PATH lookup) */
    val ffmpegPath: String = "ffmpeg",

    /** Path to the ffprobe binary. Default: "ffprobe" */
    val ffprobePath: String = "ffprobe",

    /** Base directory for downloaded media files */
    val downloadDir: String = "downloads/media",

    /** Maximum allowed download size in bytes (default: 500 MB) */
    val maxDownloadSize: Long = 500 * 1024 * 1024L,

    /** Per-download timeout */
    val downloadTimeoutSeconds: Long = 300,

    /** Per-FFmpeg-process timeout */
    val ffmpegTimeoutSeconds: Long = 600,

    /** Whether to auto-detect videos on onDocumentSteady */
    val autoDetectEnabled: Boolean = false,

    /** Maximum concurrent downloads */
    val concurrentDownloads: Int = 3,
) {
    companion object {
        private const val PREFIX = "media."

        /**
         * Build a [MediaConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): MediaConfig {
            return MediaConfig(
                ffmpegPath = conf.get("${PREFIX}ffmpeg.path", "ffmpeg"),
                ffprobePath = conf.get("${PREFIX}ffprobe.path", "ffprobe"),
                downloadDir = conf.get("${PREFIX}download.dir", "downloads/media"),
                maxDownloadSize = conf.getLong("${PREFIX}download.max-size", 500 * 1024 * 1024L),
                downloadTimeoutSeconds = conf.getLong("${PREFIX}download.timeout.seconds", 300),
                ffmpegTimeoutSeconds = conf.getLong("${PREFIX}ffmpeg.timeout.seconds", 600),
                autoDetectEnabled = conf.getBoolean("${PREFIX}auto-detect.enabled", false),
                concurrentDownloads = conf.getInt("${PREFIX}download.concurrent", 3),
            )
        }
    }
}
