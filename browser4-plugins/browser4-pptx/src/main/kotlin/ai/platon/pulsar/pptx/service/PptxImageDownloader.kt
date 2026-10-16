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
package ai.platon.pulsar.pptx.service

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.pptx.config.PptxConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/**
 * Downloads images for embedding in PPTX slides.
 *
 * Uses OkHttp for direct HTTP download, bypassing browser network restrictions.
 * Images are returned as raw byte arrays for embedding via POI XSLF.
 * SVG images and data URIs are skipped (XSLF only supports raster formats).
 *
 * The bulk pass is bounded twice over so that an unreachable image host can never
 * stall `pptx_generate` past the server's async request budget:
 * - per image, by [PptxConfig.downloadTimeoutSeconds] (wall clock, on top of the
 *   OkHttp connect/read timeouts);
 * - for the phase as a whole, by [PptxConfig.downloadTotalTimeoutSeconds]. When
 *   the phase deadline expires the images already fetched are returned and the
 *   rest are reported as [DownloadResult.aborted].
 *
 * Requests are issued through OkHttp's asynchronous API so that a deadline (or a
 * cancelled request) actually cancels the in-flight call instead of waiting for a
 * blocking `execute()` to time out.
 */
open class PptxImageDownloader(
    private val config: PptxConfig,
    private val client: OkHttpClient,
) {
    private val logger = getLogger(PptxImageDownloader::class)

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** Failure reason used when a download exceeds the per-image wall clock budget. */
        const val REASON_TIMEOUT = "download timed out"

        /** Failure reason used when a download could not be started before the phase deadline. */
        const val REASON_DEADLINE_EXCEEDED = "phase deadline reached"
    }

    /**
     * Outcome of a single image download attempt.
     *
     * @property bytes the image bytes, or null when the attempt produced nothing
     * @property failureReason why the image is missing, or null on success
     * @property skipped true when the image was intentionally not downloaded
     *           (data URI, SVG, oversized) rather than failing
     */
    data class ImageDownloadAttempt(
        val bytes: ByteArray?,
        val failureReason: String? = null,
        val skipped: Boolean = false,
    ) {
        /** Whether the attempt produced image bytes. */
        val succeeded: Boolean get() = bytes != null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageDownloadAttempt) return false
            return bytes.contentEquals(other.bytes) &&
                    failureReason == other.failureReason &&
                    skipped == other.skipped
        }

        override fun hashCode(): Int {
            var result = bytes?.contentHashCode() ?: 0
            result = 31 * result + (failureReason?.hashCode() ?: 0)
            result = 31 * result + skipped.hashCode()
            return result
        }
    }

    /**
     * Download a single image and return its bytes.
     *
     * @param url the image URL to download
     * @return image bytes, or null if the download failed or was skipped
     */
    open suspend fun downloadImage(url: String): ByteArray? = attemptDownload(url).bytes

    /**
     * Download a single image and report why it is missing when it is.
     *
     * @param url the image URL to download
     * @return the attempt outcome; [ImageDownloadAttempt.failureReason] carries the
     *         cause so callers can surface an actionable error instead of a timeout
     */
    open suspend fun attemptDownload(url: String): ImageDownloadAttempt = withContext(Dispatchers.IO) {
        if (isDataUri(url)) {
            return@withContext ImageDownloadAttempt(null, "data URI is not an embeddable image", skipped = true)
        }

        if (isSvgUrl(url) && config.skipSvg) {
            return@withContext ImageDownloadAttempt(null, "SVG cannot be embedded (pptx.detect.skip-svg)", skipped = true)
        }

        val validatedUrl = if (url.startsWith("//")) "https:$url" else url

        val request = try {
            Request.Builder()
                .url(validatedUrl)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()
        } catch (e: Exception) {
            logger.warn("Invalid image URL: {}", url.take(120))
            return@withContext ImageDownloadAttempt(null, "invalid URL")
        }

        try {
            val response = execute(request)

            if (!response.isSuccessful) {
                response.close()
                logger.debug("Image download failed HTTP {} for: {}", response.code, url.take(120))
                return@withContext ImageDownloadAttempt(null, "HTTP ${response.code}")
            }

            val body = response.body
            if (body == null) {
                response.close()
                return@withContext ImageDownloadAttempt(null, "empty response body")
            }

            val bytes = body.bytes()
            response.close()

            if (bytes.size > config.maxDownloadSize) {
                logger.debug("Image too large ({} bytes), skipping: {}", bytes.size, url.take(120))
                return@withContext ImageDownloadAttempt(
                    null, "larger than pptx.download.max-size (${bytes.size} bytes)", skipped = true
                )
            }

            if (bytes.isEmpty()) {
                return@withContext ImageDownloadAttempt(null, "empty image body", skipped = true)
            }

            logger.debug("Downloaded image: {} bytes from {}", bytes.size, url.take(120))
            ImageDownloadAttempt(bytes)
        } catch (e: IOException) {
            logger.debug("Image download IO error for {}: {}", url.take(120), e.message)
            ImageDownloadAttempt(null, describeFailure(e))
        } catch (e: CancellationException) {
            // Cancellation (phase/per-image deadline) must propagate: swallowing it
            // would keep the coroutine running after the scope was cancelled.
            throw e
        } catch (e: Exception) {
            logger.warn("Image download error for {}: {}", url.take(120), e.message)
            ImageDownloadAttempt(null, describeFailure(e))
        }
    }

    /**
     * Outcome of a bulk image download pass.
     *
     * @property bytes           successfully downloaded images, keyed by URL
     * @property total           distinct image URLs considered
     * @property failed          URLs that were attempted and failed (network error,
     *                           timeout, HTTP error)
     * @property skipped         URLs intentionally not downloaded (data URI, SVG,
     *                           oversized, empty body)
     * @property aborted         URLs that were never attempted because the overall
     *                           download deadline expired
     * @property failureReasons  failure reason to count, so callers can name the
     *                           dominant cause instead of reporting a bare timeout
     * @property durationMillis  wall clock spent in the download phase
     */
    data class DownloadResult(
        val bytes: Map<String, ByteArray>,
        val total: Int,
        val failed: Int,
        val skipped: Int = 0,
        val aborted: Int = 0,
        val failureReasons: Map<String, Int> = emptyMap(),
        val durationMillis: Long = 0,
    ) {
        /** Number of images downloaded successfully. */
        val downloaded: Int get() = bytes.size

        /** Whether the overall download deadline expired before every image was tried. */
        val deadlineExceeded: Boolean get() = aborted > 0

        /** Whether some images are missing for any reason. */
        val partial: Boolean get() = downloaded < total
    }

    /**
     * Download all images referenced in a list of content blocks.
     *
     * Downloads are performed concurrently with a semaphore to limit parallel
     * connections. The phase is bounded by [PptxConfig.downloadTotalTimeoutSeconds]:
     * when the deadline expires, the images fetched so far are returned together
     * with the reason the remaining ones are missing, and the PPTX is still written.
     *
     * @param blocks the content blocks containing image URLs
     * @return [DownloadResult] with the downloaded bytes plus failed/skipped/aborted
     *         counts so callers can surface a truthful partial-result report
     */
    open suspend fun downloadImages(blocks: List<ContentBlock>): DownloadResult {
        val imageUrls = blocks
            .filter { it.type == "image" && !it.src.isNullOrBlank() }
            .mapNotNull { it.src }
            .distinct()

        if (imageUrls.isEmpty()) return DownloadResult(emptyMap(), 0, 0)

        val startedAt = System.currentTimeMillis()
        val totalTimeoutMillis = config.downloadTotalTimeoutSeconds.coerceAtLeast(1) * 1000
        val perImageTimeoutMillis = config.downloadTimeoutSeconds.coerceAtLeast(1) * 1000
        val concurrency = config.concurrentDownloads.coerceIn(1, 10)

        val attempts = ConcurrentHashMap<String, ImageDownloadAttempt>()
        // Null result means the phase deadline expired; the scope is cancelled and
        // whatever finished before the deadline is reported as a partial result.
        withTimeoutOrNull(totalTimeoutMillis) {
            coroutineScope {
                val semaphore = Semaphore(concurrency)
                imageUrls.map { url ->
                    async {
                        val attempt = semaphore.withPermit {
                            withTimeoutOrNull(perImageTimeoutMillis) { attemptDownload(url) }
                                ?: ImageDownloadAttempt(null, REASON_TIMEOUT)
                        }
                        attempts[url] = attempt
                    }
                }.forEach { it.join() }
            }
        }

        val aborted = imageUrls.size - attempts.size
        val downloaded = attempts.values.count { it.succeeded }
        val skipped = attempts.values.count { it.skipped }
        val failed = attempts.values.count { !it.succeeded && !it.skipped }
        val reasons = attempts.values
            .filter { !it.succeeded && !it.skipped }
            .groupingBy { it.failureReason ?: "unknown" }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .toMap()
        val durationMillis = System.currentTimeMillis() - startedAt

        if (aborted > 0 || failed > 0) {
            logger.warn(
                "PPTX image downloads: {}/{} succeeded, {} failed, {} aborted after {} ms{}",
                downloaded, imageUrls.size, failed, aborted, durationMillis,
                if (reasons.isEmpty()) "" else " (${describeReasons(reasons)})"
            )
        } else {
            logger.info(
                "Downloaded {}/{} images for PPTX embedding in {} ms",
                downloaded, imageUrls.size, durationMillis
            )
        }

        return DownloadResult(
            bytes = attempts.filterValues { it.succeeded }.mapValues { it.value.bytes!! },
            total = imageUrls.size,
            failed = failed,
            skipped = skipped,
            aborted = aborted,
            failureReasons = reasons,
            durationMillis = durationMillis,
        )
    }

    /**
     * Build a human readable, actionable image-download diagnostic.
     *
     * @param result the bulk download outcome
     * @return warning lines naming the dominant failure cause, or an empty list
     *         when every image was downloaded
     */
    fun describeShortfall(result: DownloadResult): List<String> {
        if (result.total == 0 || !result.partial) return emptyList()

        val warnings = mutableListOf<String>()
        val cause = if (result.failureReasons.isEmpty()) {
            "no reason reported"
        } else {
            describeReasons(result.failureReasons)
        }

        if (result.aborted > 0) {
            warnings += "Image download deadline (${config.downloadTotalTimeoutSeconds}s) reached: " +
                    "${result.downloaded}/${result.total} images downloaded, ${result.aborted} not attempted. " +
                    "Raise pptx.download.total-timeout.seconds for slow image hosts."
        }

        if (result.downloaded == 0) {
            warnings += "No image could be downloaded (${result.failed} failed, ${result.skipped} skipped: $cause). " +
                    "The PPTX was generated without images; the page content is unaffected. " +
                    "If the image hosts need a proxy, set pptx.download.proxy or HTTPS_PROXY " +
                    "(the browser's OS system proxy is not always inherited by the JVM)."
        } else if (result.failed > 0) {
            warnings += "Some images could not be downloaded (${result.failed}/${result.total} failed: $cause). " +
                    "The PPTX was generated without them."
        }

        if (result.skipped > 0) {
            warnings += "${result.skipped} image(s) were skipped by configuration (SVG/data URI/oversized)."
        }
        return warnings
    }

    private fun describeReasons(reasons: Map<String, Int>): String =
        reasons.entries.joinToString(", ") { "${it.key}: ${it.value}" }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // `onCancellation` runs instead of the resume when the phase or the
                    // per-image deadline already cancelled this continuation, so a late
                    // response closes its body and cannot leak the connection.
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }

    private fun describeFailure(e: Exception): String {
        val message = e.message?.take(120)
        val type = e.javaClass.simpleName
        return if (message.isNullOrBlank()) type else "$type: $message"
    }

    private fun isDataUri(url: String): Boolean {
        return url.startsWith("data:", ignoreCase = true)
    }

    private fun isSvgUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".svg") || lower.contains(".svg?") || lower.contains(".svg#")
                || lower.contains("image/svg+xml")
    }
}
