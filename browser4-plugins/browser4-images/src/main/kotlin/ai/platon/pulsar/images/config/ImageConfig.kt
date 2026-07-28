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
package ai.platon.pulsar.images.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration holder for the browser4-images plugin.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class ImageConfig(
    /** Base directory for downloaded images */
    val downloadDir: String = "downloads/images",

    /** Maximum allowed download size in bytes (default: 50 MB) */
    val maxDownloadSize: Long = 50 * 1024 * 1024L,

    /** Per-download timeout in seconds */
    val downloadTimeoutSeconds: Long = 60,

    /** Whether to auto-detect images on onDocumentSteady */
    val autoDetectEnabled: Boolean = false,

    /** Whether to auto-download detected images (requires autoDetectEnabled) */
    val autoDownloadEnabled: Boolean = false,

    /** Maximum concurrent downloads */
    val concurrentDownloads: Int = 5,

    /** Minimum image width in pixels for detection (0 = no filter) */
    val minWidth: Int = 0,

    /** Minimum image height in pixels for detection (0 = no filter) */
    val minHeight: Int = 0,

    /** Skip SVG images (they are XML, not raster images) */
    val skipSvg: Boolean = false,

    /** Skip data URI images */
    val skipDataUris: Boolean = true,
) {
    companion object {
        private const val PREFIX = "image."

        /**
         * Build an [ImageConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): ImageConfig {
            return ImageConfig(
                downloadDir = conf.get("${PREFIX}download.dir", "downloads/images"),
                maxDownloadSize = conf.getLong("${PREFIX}download.max-size", 50 * 1024 * 1024L),
                downloadTimeoutSeconds = conf.getLong("${PREFIX}download.timeout.seconds", 60),
                autoDetectEnabled = conf.getBoolean("${PREFIX}auto-detect.enabled", false),
                autoDownloadEnabled = conf.getBoolean("${PREFIX}auto-download.enabled", false),
                concurrentDownloads = conf.getInt("${PREFIX}download.concurrent", 5),
                minWidth = conf.getInt("${PREFIX}detect.min-width", 0),
                minHeight = conf.getInt("${PREFIX}detect.min-height", 0),
                skipSvg = conf.getBoolean("${PREFIX}detect.skip-svg", false),
                skipDataUris = conf.getBoolean("${PREFIX}detect.skip-data-uris", true),
            )
        }
    }
}
