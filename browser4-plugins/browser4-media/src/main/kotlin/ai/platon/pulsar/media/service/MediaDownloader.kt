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

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.media.config.MediaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/**
 * Downloads media files using a dual strategy:
 *
 * 1. **Primary: Direct OkHttp download** — independent HTTP client, bypasses all browser [BlockRule] settings.
 *    Handles redirects, Content-Disposition filename extraction, progress tracking, and size validation.
 *
 * 2. **Fallback: CDP network interception** — for session-authenticated content that requires
 *    browser cookies/headers. Requires the browser session to have request interception enabled.
 */
open class MediaDownloader(
    private val config: MediaConfig,
    private val client: OkHttpClient,
) {
    private val logger = getLogger(MediaDownloader::class)

    /** Default browser-like User-Agent to avoid being blocked by servers */
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /**
     * Result of a download operation.
     */
    data class DownloadResult(
        /** The URL that was downloaded */
        val url: String,
        /** Absolute path to the saved file */
        val filePath: String,
        /** Number of bytes downloaded */
        val bytesDownloaded: Long,
        /** Content-Type from the response header */
        val contentType: String? = null,
        /** Total duration in milliseconds */
        val durationMs: Long,
        /** Whether the download completed successfully */
        val success: Boolean,
        /** Error message if the download failed */
        val error: String? = null,
    )

    /**
     * Download a media file directly via OkHttp.
     *
     * @param url        the media URL to download
     * @param outputDir  directory to save the file in (created if not exists)
     * @param filename   optional filename override; if null, derived from URL/headers
     * @param headers    additional HTTP headers to send
     * @return [DownloadResult] describing the outcome
     */
    open suspend fun download(
        url: String,
        outputDir: Path = Path.of(config.downloadDir),
        filename: String? = null,
        headers: Map<String, String> = defaultHeaders(),
    ): DownloadResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val validatedUrl = try {
            MediaUtils.validateUrl(url)
        } catch (e: IllegalArgumentException) {
            return@withContext DownloadResult(
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
                return@withContext DownloadResult(
                    url = validatedUrl,
                    filePath = "",
                    bytesDownloaded = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "HTTP ${response.code}: ${response.message}",
                )
            }

            val body = response.body
                ?: return@withContext DownloadResult(
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
                return@withContext DownloadResult(
                    url = validatedUrl,
                    filePath = "",
                    bytesDownloaded = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "Content too large: ${MediaUtils.formatFileSize(contentLength)} (max: ${MediaUtils.formatFileSize(config.maxDownloadSize)})",
                )
            }

            // Determine filename
            val contentType = body.contentType()?.toString()
            val finalName = filename ?: MediaUtils.suggestFilename(validatedUrl, contentType)
            val safeName = MediaUtils.sanitizeFilename(finalName)
            val outputPath = outputDir.resolve(safeName)

            // Prevent path traversal
            MediaUtils.requirePathWithinBase(outputDir, outputPath)

            // Stream to disk
            body.byteStream().use { input ->
                Files.newOutputStream(outputPath).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var bytesRead: Int
                    var lastLogTime = startTime

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalRead > config.maxDownloadSize) {
                            body.close()
                            Files.deleteIfExists(outputPath)
                            return@withContext DownloadResult(
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
                                MediaUtils.formatFileSize(totalRead),
                                if (contentLength > 0) MediaUtils.formatFileSize(contentLength) else "?"
                            )
                            lastLogTime = now
                        }
                    }
                    body.close()
                    response.close()

                    val durationMs = System.currentTimeMillis() - startTime
                    logger.info("Downloaded {} ({}) in {}ms",
                        safeName, MediaUtils.formatFileSize(totalRead), durationMs)

                    DownloadResult(
                        url = validatedUrl,
                        filePath = outputPath.toAbsolutePath().toString(),
                        bytesDownloaded = totalRead,
                        contentType = contentType,
                        durationMs = durationMs,
                        success = true,
                    )
                }
            }
        } catch (e: IOException) {
            logger.warn("Download failed for {}: {}", validatedUrl, e.message)
            DownloadResult(
                url = validatedUrl,
                filePath = "",
                bytesDownloaded = 0,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                error = "IO error: ${e.message}",
            )
        } catch (e: Exception) {
            logger.warn("Download failed for {}: {}", validatedUrl, e.message)
            DownloadResult(
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
     * Build default request headers to mimic a browser.
     */
    private fun defaultHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
        )
    }
}
