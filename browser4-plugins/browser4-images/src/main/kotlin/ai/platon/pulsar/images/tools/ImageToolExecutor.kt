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
package ai.platon.pulsar.images.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.images.config.ImageConfig
import ai.platon.pulsar.images.service.ImageDetector
import ai.platon.pulsar.images.service.ImageDownloader
import kotlin.reflect.KClass
import java.nio.file.Path

/**
 * LLM agent tool executor for the `image` domain.
 *
 * Provides AI agents with the ability to:
 * - `image.detectImages()` — scan the current page for image sources
 * - `image.download(url, outputPath?, filename?)` — download a single image
 * - `image.downloadAll(outputPath?, minWidth?, minHeight?)` — detect + bulk download all images
 * - `image.downloadBatch(urls, outputPath?)` — download a specific list of image URLs
 */
open class ImageToolExecutor(
    private val imageDetector: ImageDetector,
    private val imageDownloader: ImageDownloader,
    private val config: ImageConfig,
) : AbstractToolExecutor() {
    private val logger = getLogger(ImageToolExecutor::class)

    override val domain = "image"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["detectImages"] = ToolSpec(
            domain = domain,
            method = "detectImages",
            arguments = listOf(
                ToolSpec.Arg("minWidth", "Int?", "null"),
                ToolSpec.Arg("minHeight", "Int?", "null"),
            ),
            returnType = "List<ImageSource>",
            description = "Detect all image elements on the current page, including <img> tags, <picture>/<source> elements, CSS background images, <a> links to image files, favicons, and OG/Twitter meta images.",
            help = """
                image.detectImages()
                image.detectImages(minWidth: Int?)
                image.detectImages(minWidth: Int?, minHeight: Int?)

                Scans the current page DOM for image sources. Returns a list of detected image sources
                with metadata (URL, dimensions, alt text, etc.). Does NOT download any images.

                Use minWidth/minHeight to filter out small images (e.g., tracking pixels, icons).
            """.trimIndent()
        )

        toolSpec["download"] = ToolSpec(
            domain = domain,
            method = "download",
            arguments = listOf(
                ToolSpec.Arg("url", "String"),
                ToolSpec.Arg("outputPath", "String?", "null"),
                ToolSpec.Arg("filename", "String?", "null"),
            ),
            returnType = "ImageDownloadResult",
            description = "Download a single image from a URL using direct HTTP. Supports jpg, png, gif, webp, svg, bmp, and more.",
            help = """
                image.download(url: String)
                image.download(url: String, outputPath: String?)
                image.download(url: String, outputPath: String?, filename: String?)

                Downloads an image file to the configured download directory (default: downloads/images/)
                or a custom outputPath. Returns ImageDownloadResult with filePath, bytesDownloaded, etc.
            """.trimIndent()
        )

        toolSpec["downloadAll"] = ToolSpec(
            domain = domain,
            method = "downloadAll",
            arguments = listOf(
                ToolSpec.Arg("outputPath", "String?", "null"),
                ToolSpec.Arg("minWidth", "Int?", "null"),
                ToolSpec.Arg("minHeight", "Int?", "null"),
            ),
            returnType = "BulkDownloadSummary",
            description = "Detect all images on the current page and download them in bulk with concurrent downloads (default 5). Automatically skips data URIs.",
            help = """
                image.downloadAll()
                image.downloadAll(outputPath: String?)
                image.downloadAll(outputPath: String?, minWidth: Int?)
                image.downloadAll(outputPath: String?, minWidth: Int?, minHeight: Int?)

                Scans the current page for all images and downloads them concurrently.
                Returns a BulkDownloadSummary with counts, total bytes, and per-image results.

                Use minWidth/minHeight to skip small images (e.g., tracking pixels, icons < 100px).
            """.trimIndent()
        )

        toolSpec["downloadBatch"] = ToolSpec(
            domain = domain,
            method = "downloadBatch",
            arguments = listOf(
                ToolSpec.Arg("urls", "List<String>"),
                ToolSpec.Arg("outputPath", "String?", "null"),
            ),
            returnType = "BulkDownloadSummary",
            description = "Download a specific list of image URLs in bulk. Use this when you already have a list of image URLs and don't need page detection.",
            help = """
                image.downloadBatch(urls: List<String>)
                image.downloadBatch(urls: List<String>, outputPath: String?)

                Downloads a batch of image URLs concurrently (default 5 at a time).
                Returns a BulkDownloadSummary with per-url results.

                Example:
                image.downloadBatch(["https://example.com/img1.jpg", "https://example.com/img2.png"])
            """.trimIndent()
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        val driver = receiver as? WebDriver

        return when (functionName) {
            "detectImages" -> {
                requireNotNull(driver) { "detectImages requires a WebDriver receiver (current page context)" }
                val images = imageDetector.detect(driver)
                val minW = paramInt(args, "minWidth", functionName, required = false)
                val minH = paramInt(args, "minHeight", functionName, required = false)

                images.filter { img ->
                    val passesWidth = minW == null || (img.naturalWidth ?: img.width ?: 0) >= minW
                    val passesHeight = minH == null || (img.naturalHeight ?: img.height ?: 0) >= minH
                    passesWidth && passesHeight
                }
            }

            "download" -> {
                val url = paramString(args, "url", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val filename = paramString(args, "filename", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.downloadDir)
                imageDownloader.download(url, dir, filename)
            }

            "downloadAll" -> {
                requireNotNull(driver) { "downloadAll requires a WebDriver receiver (current page context)" }
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.downloadDir)

                // Temporarily override config filters if provided
                val minW = paramInt(args, "minWidth", functionName, required = false)
                val minH = paramInt(args, "minHeight", functionName, required = false)

                val images = imageDetector.detect(driver)
                val filtered = images.filter { img ->
                    val passesWidth = minW == null || (img.naturalWidth ?: img.width ?: 0) >= minW
                    val passesHeight = minH == null || (img.naturalHeight ?: img.height ?: 0) >= minH
                    passesWidth && passesHeight
                }

                logger.info("downloadAll: detected {} images, {} after filtering", images.size, filtered.size)
                imageDownloader.downloadAll(filtered, dir)
            }

            "downloadBatch" -> {
                val urls = paramStringList(args, "urls", functionName)
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.downloadDir)
                imageDownloader.downloadBatch(urls, dir)
            }

            else -> throw IllegalArgumentException(
                "Unsupported image method: $functionName. " +
                    "Supported: detectImages, download, downloadAll, downloadBatch."
            )
        }
    }
}
