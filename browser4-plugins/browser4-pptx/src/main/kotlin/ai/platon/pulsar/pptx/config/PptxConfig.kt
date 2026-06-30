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
package ai.platon.pulsar.pptx.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration holder for the browser4-pptx plugin.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class PptxConfig(
    /** Base directory for generated PPTX files */
    val outputDir: String = "downloads/pptx",

    /** Maximum allowed download size in bytes for a single image (default: 10 MB) */
    val maxDownloadSize: Long = 10 * 1024 * 1024L,

    /** Per-download timeout in seconds */
    val downloadTimeoutSeconds: Long = 30,

    /** Maximum concurrent image downloads */
    val concurrentDownloads: Int = 3,

    /** Whether to auto-generate PPTX on onDocumentSteady */
    val autoGenerateEnabled: Boolean = false,

    /** Maximum title length before truncation */
    val maxTitleLength: Int = 120,

    /** Slide width in POI points (default: 720 = 10 inches, widescreen) */
    val slideWidth: Int = 720,

    /** Slide height in POI points (default: 540 = 7.5 inches, widescreen) */
    val slideHeight: Int = 540,

    /** Maximum images per content slide */
    val maxImagesPerSlide: Int = 2,

    /** Maximum content blocks per slide before splitting */
    val maxContentBlocksPerSlide: Int = 6,

    /** Skip SVG images (they cannot be embedded in XSLF) */
    val skipSvg: Boolean = true,

    /** Skip data URI images */
    val skipDataUris: Boolean = true,
) {
    companion object {
        private const val PREFIX = "pptx."

        /**
         * Build a [PptxConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): PptxConfig {
            return PptxConfig(
                outputDir = conf.get("${PREFIX}output.dir", "downloads/pptx"),
                maxDownloadSize = conf.getLong("${PREFIX}download.max-size", 10 * 1024 * 1024L),
                downloadTimeoutSeconds = conf.getLong("${PREFIX}download.timeout.seconds", 30),
                concurrentDownloads = conf.getInt("${PREFIX}download.concurrent", 3),
                autoGenerateEnabled = conf.getBoolean("${PREFIX}auto-generate.enabled", false),
                maxTitleLength = conf.getInt("${PREFIX}title.max-length", 120),
                slideWidth = conf.getInt("${PREFIX}slide.width", 720),
                slideHeight = conf.getInt("${PREFIX}slide.height", 540),
                maxImagesPerSlide = conf.getInt("${PREFIX}slide.max-images", 2),
                maxContentBlocksPerSlide = conf.getInt("${PREFIX}slide.max-content-blocks", 6),
                skipSvg = conf.getBoolean("${PREFIX}detect.skip-svg", true),
                skipDataUris = conf.getBoolean("${PREFIX}detect.skip-data-uris", true),
            )
        }
    }
}
