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
import java.nio.file.Path

/**
 * Configuration holder for the browser4-pptx plugin.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class PptxConfig(
    /** Base directory for generated PPTX files (absolute by default, anchored under ~/.browser4) */
    val outputDir: String = defaultOutputDir(),

    /** Maximum allowed download size in bytes for a single image (default: 10 MB) */
    val maxDownloadSize: Long = 10 * 1024 * 1024L,

    /** Per-download timeout in seconds */
    val downloadTimeoutSeconds: Long = 30,

    /** Overall wall-clock deadline for the whole image download phase, in seconds.
     * When it expires the phase stops and the PPTX is written with the images that
     * were fetched so far, so a blocked image host can never stall generation past
     * the server's async request budget. */
    val downloadTotalTimeoutSeconds: Long = 60,

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

    /** HTTP proxy for image downloads, e.g. "127.0.0.1:10808" or "http://127.0.0.1:10808".
     * Null means no explicit proxy (the download client then falls back to the
     * HTTPS_PROXY / HTTP_PROXY environment variables, the JVM proxy properties, and
     * the OS system proxy — see [PptxProxyResolver]). */
    val proxy: String? = null,

    /** Honour the OS system proxy (WinINET/macOS platform settings) for image
     * downloads when no explicit proxy is configured. */
    val useSystemProxy: Boolean = true,

    /** Extra CSS selectors whose subtrees are dropped before they become slide
     * content, on top of the built-in page-chrome selectors. */
    val excludeSelectors: List<String> = emptyList(),
) {
    companion object {
        private const val PREFIX = "pptx."

        /**
         * Default output directory, anchored at the user's Browser4 state dir
         * (~/.browser4/downloads/pptx) instead of the backend process CWD — a
         * relative default resolves inside whatever directory the backend happens to
         * run from (for a dev runtime, the runtime-bundle build tree), where users
         * cannot find the file and a bundle rebuild can wipe it.
         */
        fun defaultOutputDir(): String =
            Path.of(System.getProperty("user.home"), ".browser4", "downloads", "pptx").toString()

        /**
         * Build a [PptxConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): PptxConfig {
            return PptxConfig(
                outputDir = conf.get("${PREFIX}output.dir", defaultOutputDir()),
                maxDownloadSize = conf.getLong("${PREFIX}download.max-size", 10 * 1024 * 1024L),
                downloadTimeoutSeconds = conf.getLong("${PREFIX}download.timeout.seconds", 30),
                downloadTotalTimeoutSeconds = conf.getLong("${PREFIX}download.total-timeout.seconds", 60),
                concurrentDownloads = conf.getInt("${PREFIX}download.concurrent", 3),
                autoGenerateEnabled = conf.getBoolean("${PREFIX}auto-generate.enabled", false),
                maxTitleLength = conf.getInt("${PREFIX}title.max-length", 120),
                slideWidth = conf.getInt("${PREFIX}slide.width", 720),
                slideHeight = conf.getInt("${PREFIX}slide.height", 540),
                maxImagesPerSlide = conf.getInt("${PREFIX}slide.max-images", 2),
                maxContentBlocksPerSlide = conf.getInt("${PREFIX}slide.max-content-blocks", 6),
                skipSvg = conf.getBoolean("${PREFIX}detect.skip-svg", true),
                skipDataUris = conf.getBoolean("${PREFIX}detect.skip-data-uris", true),
                proxy = conf.get("${PREFIX}download.proxy", "")?.takeIf { it.isNotBlank() },
                useSystemProxy = conf.getBoolean("${PREFIX}download.use-system-proxy", true),
                excludeSelectors = conf.get("${PREFIX}detect.exclude-selectors", "")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }
    }
}
