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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads images for embedding in PPTX slides.
 *
 * Uses OkHttp for direct HTTP download, bypassing browser network restrictions.
 * Images are returned as raw byte arrays for embedding via POI XSLF.
 * SVG images and data URIs are skipped (XSLF only supports raster formats).
 */
open class PptxImageDownloader(
    private val config: PptxConfig,
    private val client: OkHttpClient,
) {
    private val logger = getLogger(PptxImageDownloader::class)

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /**
     * Download a single image and return its bytes.
     *
     * @param url the image URL to download
     * @return image bytes, or null if the download failed or was skipped
     */
    open suspend fun downloadImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        // Skip data URIs
        if (isDataUri(url)) {
            if (!config.skipDataUris) {
                logger.debug("Skipping data URI (config allows): {}", url.take(80))
            }
            return@withContext null
        }

        // Skip SVG
        if (isSvgUrl(url)) {
            if (config.skipSvg) {
                logger.debug("Skipping SVG image: {}", url.take(120))
                return@withContext null
            }
        }

        // Validate URL
        val validatedUrl = try {
            if (url.startsWith("//")) "https:$url" else url
        } catch (e: Exception) {
            logger.warn("Invalid image URL: {}", url.take(120))
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url(validatedUrl)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                logger.debug("Image download failed HTTP {} for: {}", response.code, url.take(120))
                response.close()
                return@withContext null
            }

            val body = response.body
            if (body == null) {
                response.close()
                return@withContext null
            }

            val bytes = body.bytes()
            response.close()

            if (bytes.size > config.maxDownloadSize) {
                logger.debug("Image too large ({} bytes), skipping: {}", bytes.size, url.take(120))
                return@withContext null
            }

            if (bytes.isEmpty()) {
                return@withContext null
            }

            logger.debug("Downloaded image: {} bytes from {}", bytes.size, url.take(120))
            bytes
        } catch (e: IOException) {
            logger.debug("Image download IO error for {}: {}", url.take(120), e.message)
            null
        } catch (e: Exception) {
            logger.warn("Image download error for {}: {}", url.take(120), e.message)
            null
        }
    }

    /**
     * Download all images referenced in a list of content blocks.
     *
     * Downloads are performed concurrently with a semaphore to limit parallel connections.
     *
     * @param blocks the content blocks containing image URLs
     * @return map of image URL to byte array (only successful downloads)
     */
    open suspend fun downloadImages(blocks: List<ContentBlock>): Map<String, ByteArray> {
        val imageUrls = blocks
            .filter { it.type == "image" && !it.src.isNullOrBlank() }
            .mapNotNull { it.src }
            .distinct()

        if (imageUrls.isEmpty()) return emptyMap()

        val semaphore = Semaphore(config.concurrentDownloads.coerceIn(1, 10))
        val results = mutableMapOf<String, ByteArray>()

        coroutineScope {
            imageUrls.map { url ->
                async {
                    semaphore.withPermit {
                        val bytes = downloadImage(url)
                        if (bytes != null) {
                            synchronized(results) {
                                results[url] = bytes
                            }
                        }
                    }
                }
            }.forEach { it.await() }
        }

        logger.info("Downloaded {}/{} images for PPTX embedding", results.size, imageUrls.size)
        return results
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
