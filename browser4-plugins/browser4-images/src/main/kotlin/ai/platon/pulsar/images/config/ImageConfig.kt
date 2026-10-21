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
import java.nio.file.Path

/**
 * Configuration holder for the browser4-images plugin.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class ImageConfig(
    /** Base directory for downloaded images (absolute by default, anchored under ~/.browser4) */
    val downloadDir: String = defaultDownloadDir(),

    /** Maximum allowed download size in bytes (default: 50 MB) */
    val maxDownloadSize: Long = 50 * 1024 * 1024L,

    /**
     * Per-download HTTP budget in seconds: the whole call, and each of the
     * connect/read/write phases. The default stays below the CLI's 30 s
     * default tool-call timeout so a blocked or unreachable origin produces
     * the plugin's own actionable error instead of a bare CLI timeout.
     * Raise it for unusually large images (and raise the CLI `--timeout` too).
     */
    val downloadTimeoutSeconds: Long = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,

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

    /** HTTP proxy for image downloads, e.g. "127.0.0.1:10808" or "http://127.0.0.1:10808".
     * Null means no explicit proxy: the download client then falls back to the
     * HTTPS_PROXY / HTTP_PROXY environment variables, the standard JVM proxy
     * properties, and the operating-system proxy (WinINET on Windows); see
     * [ImageProxyResolver]. */
    val proxy: String? = null,
) {
    /**
     * Connect timeout in seconds: bounded so a host that cannot be reached
     * (no route, blocked direct egress, wrong proxy) fails fast — and with the
     * plugin's actionable proxy hint — instead of hanging until the caller's
     * HTTP timeout fires first.
     */
    val connectTimeoutSeconds: Long
        get() = downloadTimeoutSeconds.coerceIn(1L, MAX_CONNECT_TIMEOUT_SECONDS)

    companion object {
        private const val PREFIX = "image."

        /**
         * Default per-download HTTP budget, deliberately below the CLI's 30 s
         * default tool-call timeout (cli/browser4-cli/src/http.rs) so the
         * plugin's actionable error is what the user sees.
         */
        const val DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 25L

        /** Upper bound for the connect phase — a reachable host connects far sooner. */
        const val MAX_CONNECT_TIMEOUT_SECONDS = 10L

        /**
         * Default download directory, anchored at the user's Browser4 state dir
         * (~/.browser4/downloads/images) instead of the backend process CWD —
         * a relative default would resolve inside the runtime bundle build tree,
         * where users cannot find it and a bundle rebuild can wipe it.
         */
        fun defaultDownloadDir(): String =
            Path.of(System.getProperty("user.home"), ".browser4", "downloads", "images").toString()

        /**
         * Build an [ImageConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): ImageConfig {
            return ImageConfig(
                downloadDir = conf.get("${PREFIX}download.dir", defaultDownloadDir()),
                maxDownloadSize = conf.getLong("${PREFIX}download.max-size", 50 * 1024 * 1024L),
                downloadTimeoutSeconds = conf.getLong(
                    "${PREFIX}download.timeout.seconds", DEFAULT_DOWNLOAD_TIMEOUT_SECONDS
                ),
                autoDetectEnabled = conf.getBoolean("${PREFIX}auto-detect.enabled", false),
                autoDownloadEnabled = conf.getBoolean("${PREFIX}auto-download.enabled", false),
                concurrentDownloads = conf.getInt("${PREFIX}download.concurrent", 5),
                minWidth = conf.getInt("${PREFIX}detect.min-width", 0),
                minHeight = conf.getInt("${PREFIX}detect.min-height", 0),
                skipSvg = conf.getBoolean("${PREFIX}detect.skip-svg", false),
                skipDataUris = conf.getBoolean("${PREFIX}detect.skip-data-uris", true),
                proxy = conf.get("${PREFIX}download.proxy", "")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
