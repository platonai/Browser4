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
package ai.platon.pulsar.images.service

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.images.config.ImageConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/**
 * Downloads image files using a dual strategy:
 *
 * 1. **Primary: Direct OkHttp download** — independent HTTP client, bypasses all browser [BlockRule] settings.
 *    Handles redirects, Content-Disposition filename extraction, progress tracking, and size validation.
 *
 * 2. **Fallback: CDP network interception** — for session-authenticated content that requires
 *    browser cookies/headers. (Reserved for future use.)
 */
open class ImageDownloader(
    private val config: ImageConfig,
    private val client: OkHttpClient,
) {
    private val logger = getLogger(ImageDownloader::class)

    /** Default browser-like User-Agent to avoid being blocked by servers */
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /**
     * Result of a single image download operation.
     */
    data class ImageDownloadResult(
        /** The URL that was downloaded */
        val url: String,
        /** Absolute path to the saved file */
        val filePath: String,
        /** Number of bytes downloaded */
        val bytesDownloaded: Long,
        /** Content-Type from the response header */
        val contentType: String? = null,
        /** Image width from detection (if available) */
        val width: Int? = null,
        /** Image height from detection (if available) */
        val height: Int? = null,
        /** Total duration in milliseconds */
        val durationMs: Long,
        /** Whether the download completed successfully */
        val success: Boolean,
        /** Error message if the download failed */
        val error: String? = null,
    )

    /**
     * Summary of a bulk download operation.
     */
    data class BulkDownloadSummary(
        /** Total number of images attempted */
        val totalAttempted: Int,
        /** Number of successful downloads */
        val successful: Int,
        /** Number of failed downloads */
        val failed: Int,
        /** Total bytes downloaded across all images */
        val totalBytesDownloaded: Long,
        /** Per-image results */
        val results: List<ImageDownloadResult>,
    )

    /**
     * Download a single image file directly via OkHttp.
     *
     * @param url        the image URL to download
     * @param outputDir  directory to save the file in (created if not exists)
     * @param filename   optional filename override; if null, derived from URL/headers
     * @param headers    additional HTTP headers to send
     * @return [ImageDownloadResult] describing the outcome
     */
    open suspend fun download(
        url: String,
        outputDir: Path = Path.of(config.downloadDir),
        filename: String? = null,
        headers: Map<String, String> = defaultHeaders(),
    ): ImageDownloadResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Skip data URIs
        if (ImageUtils.isDataUri(url)) {
            return@withContext ImageDownloadResult(
                url = url,
                filePath = "",
                bytesDownloaded = 0,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                error = "Cannot download data URI via HTTP",
            )
        }

        val validatedUrl = try {
            ImageUtils.validateUrl(url)
        } catch (e: IllegalArgumentException) {
            return@withContext ImageDownloadResult(
                url = url,
                filePath = "",
                bytesDownloaded = 0,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message,
            )
        }

        try {
            Files.createDirectories(outputDir)

            val requestBuilder = Request.Builder().url(validatedUrl)
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                response.close()
                return@withContext ImageDownloadResult(
                    url = validatedUrl,
                    filePath = "",
                    bytesDownloaded = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "HTTP ${response.code}: ${response.message}",
                )
            }

            val body = response.body
                ?: return@withContext ImageDownloadResult(
                    url = validatedUrl,
                    filePath = "",
                    bytesDownloaded = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "Empty response body",
                )

            // Check content length
            val contentLength = body.contentLength()
            if (contentLength > config.maxDownloadSize) {
                body.close()
                return@withContext ImageDownloadResult(
                    url = validatedUrl,
                    filePath = "",
                    bytesDownloaded = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "Content too large: ${ImageUtils.formatFileSize(contentLength)} (max: ${ImageUtils.formatFileSize(config.maxDownloadSize)})",
                )
            }

            // Determine filename
            val contentType = body.contentType()?.toString()
            val finalName = filename ?: ImageUtils.suggestFilename(validatedUrl, contentType)
            val safeName = ImageUtils.sanitizeFilename(finalName)
            val outputPath = outputDir.resolve(safeName)

            // Prevent path traversal
            ImageUtils.requirePathWithinBase(outputDir, outputPath)

            // Avoid overwriting — append a counter if file exists
            val uniquePath = if (Files.exists(outputPath)) {
                generateUniquePath(outputDir, safeName)
            } else {
                outputPath
            }

            // Stream to disk
            body.byteStream().use { input ->
                Files.newOutputStream(uniquePath).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var bytesRead: Int
                    var lastLogTime = startTime

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalRead > config.maxDownloadSize) {
                            body.close()
                            Files.deleteIfExists(uniquePath)
                            return@withContext ImageDownloadResult(
                                url = validatedUrl,
                                filePath = "",
                                bytesDownloaded = totalRead,
                                durationMs = System.currentTimeMillis() - startTime,
                                success = false,
                                error = "Download exceeded max size during transfer",
                            )
                        }

                        // Log progress every 10 seconds
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime > 10_000) {
                            logger.debug("Downloading {}: {} / {}",
                                safeName,
                                ImageUtils.formatFileSize(totalRead),
                                if (contentLength > 0) ImageUtils.formatFileSize(contentLength) else "?"
                            )
                            lastLogTime = now
                        }
                    }
                    body.close()
                    response.close()

                    val durationMs = System.currentTimeMillis() - startTime
                    logger.info("Downloaded {} ({}) in {}ms",
                        uniquePath.fileName, ImageUtils.formatFileSize(totalRead), durationMs)

                    ImageDownloadResult(
                        url = validatedUrl,
                        filePath = uniquePath.toAbsolutePath().toString(),
                        bytesDownloaded = totalRead,
                        contentType = contentType,
                        durationMs = durationMs,
                        success = true,
                    )
                }
            }
        } catch (e: IOException) {
            logger.warn("Download failed for {}: {}", validatedUrl, e.message)
            ImageDownloadResult(
                url = validatedUrl,
                filePath = "",
                bytesDownloaded = 0,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                error = "IO error: ${e.message}",
            )
        } catch (e: Exception) {
            logger.warn("Download failed for {}: {}", validatedUrl, e.message)
            ImageDownloadResult(
                url = validatedUrl,
                filePath = "",
                bytesDownloaded = 0,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message,
            )
        }
    }

    /**
     * Bulk download all detected images with concurrency control.
     *
     * @param images      the detected image sources to download
     * @param outputDir   directory to save files in
     * @param concurrency maximum concurrent downloads (default: config.concurrentDownloads)
     * @return [BulkDownloadSummary] with per-image results
     */
    open suspend fun downloadAll(
        images: List<ImageDetector.ImageSource>,
        outputDir: Path = Path.of(config.downloadDir),
        concurrency: Int = config.concurrentDownloads,
    ): BulkDownloadSummary = coroutineScope {
        val startTime = System.currentTimeMillis()
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        logger.info("Starting bulk download of {} images (concurrency={})", images.size, concurrency)

        val downloadTasks = images
            .filter { img ->
                // Skip data URIs if configured
                if (config.skipDataUris && img.isDataUri) {
                    logger.debug("Skipping data URI: {}", img.srcUrl?.take(80))
                    false
                } else {
                    true
                }
            }
            .map { image ->
                val url = image.resolvedUrl ?: image.srcUrl ?: return@map null
                async {
                    semaphore.withPermit {
                        val result = download(
                            url = url,
                            outputDir = outputDir,
                            headers = defaultHeaders()
                        )
                        result.copy(width = image.naturalWidth ?: image.width, height = image.naturalHeight ?: image.height)
                    }
                }
            }
            .filterNotNull()

        val results = downloadTasks.map { it.await() }
        val totalTime = System.currentTimeMillis() - startTime

        val successful = results.count { it.success }
        val failed = results.size - successful
        val totalBytes = results.filter { it.success }.sumOf { it.bytesDownloaded }

        logger.info(
            "Bulk download complete: {}/{} successful, {} total, in {}ms",
            successful, results.size, ImageUtils.formatFileSize(totalBytes), totalTime
        )

        BulkDownloadSummary(
            totalAttempted = images.size,
            successful = successful,
            failed = failed,
            totalBytesDownloaded = totalBytes,
            results = results,
        )
    }

    /**
     * Download a batch of raw URLs (no prior detection).
     */
    open suspend fun downloadBatch(
        urls: List<String>,
        outputDir: Path = Path.of(config.downloadDir),
        concurrency: Int = config.concurrentDownloads,
    ): BulkDownloadSummary = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        logger.info("Starting batch download of {} URLs (concurrency={})", urls.size, concurrency)

        val downloadTasks = urls.map { url ->
            async {
                semaphore.withPermit {
                    download(url = url, outputDir = outputDir, headers = defaultHeaders())
                }
            }
        }

        val results = downloadTasks.map { it.await() }
        val successful = results.count { it.success }
        val failed = results.size - successful
        val totalBytes = results.filter { it.success }.sumOf { it.bytesDownloaded }

        BulkDownloadSummary(
            totalAttempted = urls.size,
            successful = successful,
            failed = failed,
            totalBytesDownloaded = totalBytes,
            results = results,
        )
    }

    /**
     * Generate a unique file path by appending a counter before the extension.
     */
    private fun generateUniquePath(dir: Path, filename: String): Path {
        val dotIndex = filename.lastIndexOf('.')
        val (base, ext) = if (dotIndex > 0) {
            filename.substring(0, dotIndex) to filename.substring(dotIndex)
        } else {
            filename to ""
        }

        var counter = 1
        var candidate: Path
        do {
            candidate = dir.resolve("${base}_${counter}${ext}")
            counter++
        } while (Files.exists(candidate))

        return candidate
    }

    /**
     * Build default request headers to mimic a browser.
     */
    private fun defaultHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Encoding" to "identity",
        )
    }
}
