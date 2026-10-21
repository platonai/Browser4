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
import ai.platon.pulsar.images.service.DimensionBasis
import ai.platon.pulsar.images.service.ImageDetector
import ai.platon.pulsar.images.service.ImageDimensionFilter
import ai.platon.pulsar.images.service.ImageDownloader
import kotlin.reflect.KClass
import java.nio.file.Path

/**
 * LLM agent tool executor for the `image` domain.
 *
 * Provides AI agents with the ability to:
 * - `image.detectImages(minWidth?, minHeight?, dimensionBasis?, limit?, offset?)` — scan the current page for image sources
 * - `image.download(url, outputPath?, filename?)` — download a single image
 * - `image.downloadAll(outputPath?, minWidth?, minHeight?, dimensionBasis?)` — detect + bulk download all images
 * - `image.downloadBatch(urls, outputPath?)` — download a specific list of image URLs
 *
 * Every method returns a JSON-friendly result: a list of image sources, or a
 * map for the single-object results.
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
                ToolSpec.Arg("minWidth", "Int?", "null", "Minimum width in pixels; sources whose width is unknown are kept"),
                ToolSpec.Arg("minHeight", "Int?", "null", "Minimum height in pixels; sources whose height is unknown are kept"),
                ToolSpec.Arg(
                    "dimensionBasis", "String?", "\"rendered\"",
                    "Size the filters compare: \"rendered\" (layout size, default) or \"natural\" (intrinsic source size)"
                ),
                ToolSpec.Arg("limit", "Int?", "null", "Maximum number of entries to return"),
                ToolSpec.Arg("offset", "Int?", "null", "Number of entries to skip (paging)"),
            ),
            returnType = "List<ImageSource>",
            description = "Detect distinct image sources on the current page (deduplicated by resolved URL — NOT one entry per DOM element): <img> tags, <picture>/<source> elements, CSS background images (bounded computed-style scan), <a> links to image files, favicons, and OG/Twitter meta images. Can be large on image-heavy pages — bound with limit/offset.",
            help = """
                image.detectImages()
                image.detectImages(minWidth: Int?)
                image.detectImages(minWidth: Int?, minHeight: Int?)
                image.detectImages(minWidth: Int?, minHeight: Int?, dimensionBasis: String?, limit: Int?, offset: Int?)

                Scans the current page DOM for image sources. Returns DISTINCT image sources
                (deduplicated by resolved URL — fewer entries than document.images.length when
                the same image repeats). Does NOT download any images.

                Every entry carries a MIME `type`: a declared one (`type` attribute on
                <source>/<link>, or a data: URI header) or one inferred from the URL
                extension. Only the response Content-Type at download time is authoritative.

                Dimension filters (minWidth/minHeight) compare ONE size basis per call and are
                applied consistently to every entry:
                  - dimensionBasis="rendered" (default): the rendered (layout) size. An image
                    displayed at 50x50 is dropped by minWidth=100 even if its source file is
                    larger, so a filter matches what the page shows and is available for
                    lazy images that have not loaded yet.
                  - dimensionBasis="natural": the intrinsic (source) size. Images that have
                    not loaded report an unknown natural size.
                A source whose size is UNKNOWN on a filtered axis is KEPT (an OG/Twitter meta
                image, a <source> candidate, an unloaded image without a layout box): unknown
                is not the same as too small. Use limit/offset to bound the payload.

                Example: image.detectImages(minWidth: 100, minHeight: 60, limit: 50)
            """.trimIndent()
        )

        toolSpec["download"] = ToolSpec(
            domain = domain,
            method = "download",
            arguments = listOf(
                ToolSpec.Arg("url", "String", null, "Image URL to download"),
                ToolSpec.Arg("outputPath", "String?", "null", "Output directory (default image.download.dir)"),
                ToolSpec.Arg("filename", "String?", "null", "Custom filename"),
            ),
            returnType = "ImageDownloadResult",
            description = "Download a single image from a URL using direct HTTP. Supports jpg, png, gif, webp, svg, bmp, and more. Non-image responses (e.g. text/html) are rejected; the saved extension is corrected to match the actual Content-Type. Returns a JSON object with the same fields as a bulk-download entry.",
            help = """
                image.download(url: String)
                image.download(url: String, outputPath: String?)
                image.download(url: String, outputPath: String?, filename: String?)

                Downloads an image file to the configured download directory
                (default: ~/.browser4/downloads/images, configurable via image.download.dir)
                or a custom outputPath. Returns a JSON object with filePath, bytesDownloaded,
                contentType, width, height, durationMs, success and error — width/height are
                decoded from the downloaded bytes.

                The URL's percent-escapes are decoded for the saved filename
                (%28Pantone%29 -> (Pantone)).

                The HTTP client honours the OS proxy (Windows WinINET), HTTPS_PROXY/HTTP_PROXY,
                -Dhttps.proxyHost and image.download.proxy; per-download timeouts come from
                image.download.timeout.seconds and stay below a caller's 30s tool-call timeout,
                so an unreachable host fails with an actionable network/proxy error instead of
                a bare client timeout.

                Responses whose Content-Type is clearly not an image (e.g. text/html) fail
                instead of being saved. When the server negotiates a different format than
                the filename suggests (e.g. WebP bytes for a .png URL), the extension is
                corrected to match the actual content (the result's filePath reflects it).
            """.trimIndent()
        )

        toolSpec["downloadAll"] = ToolSpec(
            domain = domain,
            method = "downloadAll",
            arguments = listOf(
                ToolSpec.Arg("outputPath", "String?", "null", "Output directory (default image.download.dir)"),
                ToolSpec.Arg("minWidth", "Int?", "null", "Minimum width in pixels; sources whose width is unknown are kept"),
                ToolSpec.Arg("minHeight", "Int?", "null", "Minimum height in pixels; sources whose height is unknown are kept"),
                ToolSpec.Arg(
                    "dimensionBasis", "String?", "\"rendered\"",
                    "Size the filters compare: \"rendered\" (layout size, default) or \"natural\" (intrinsic source size)"
                ),
            ),
            returnType = "BulkDownloadSummary",
            description = "Detect all images on the current page and download them in bulk with concurrent downloads (default 5). Automatically skips data URIs. Detection is deduplicated by resolved URL. The summary reports totalDetected/totalAttempted/filteredOut so a filtered count is explainable.",
            help = """
                image.downloadAll()
                image.downloadAll(outputPath: String?)
                image.downloadAll(outputPath: String?, minWidth: Int?)
                image.downloadAll(outputPath: String?, minWidth: Int?, minHeight: Int?)

                Scans the current page for all images (distinct sources, deduplicated by
                resolved URL) and downloads them concurrently.
                Returns a BulkDownloadSummary — totalDetected, totalAttempted, successful,
                failed, filteredOut, totalBytesDownloaded, results — as JSON.

                minWidth/minHeight skip small images (e.g., tracking pixels, icons < 100px).
                The same consistent, documented rule as image.detectImages applies: one size
                basis per call (dimensionBasis="rendered" by default, "natural" for intrinsic
                sizes) and sources whose size is unknown on a filtered axis are KEPT rather
                than treated as too small — so a page's 1280px og:image is still downloaded
                and filteredOut never counts unknown-size sources.
            """.trimIndent()
        )

        toolSpec["downloadBatch"] = ToolSpec(
            domain = domain,
            method = "downloadBatch",
            arguments = listOf(
                ToolSpec.Arg("urls", "List<String>", null, "Image URLs to download"),
                ToolSpec.Arg("outputPath", "String?", "null", "Output directory (default image.download.dir)"),
            ),
            returnType = "BulkDownloadSummary",
            description = "Download a specific list of image URLs in bulk. Use this when you already have a list of image URLs and don't need page detection.",
            help = """
                image.downloadBatch(urls: List<String>)
                image.downloadBatch(urls: List<String>, outputPath: String?)

                Downloads a batch of image URLs concurrently (default 5 at a time).
                Returns a BulkDownloadSummary as JSON with per-url results.

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
                val basis = paramDimensionBasis(args, functionName)

                val filtered = ImageDimensionFilter.filter(images, minW, minH, basis)

                // Bound the payload: results are deduplicated by resolved URL, so
                // counts reconcile with unique sources, not DOM elements.
                val offset = (paramInt(args, "offset", functionName, required = false) ?: 0).coerceAtLeast(0)
                val limit = paramInt(args, "limit", functionName, required = false)?.takeIf { it >= 0 }
                val bounded = filtered.drop(offset)
                if (limit != null) bounded.take(limit) else bounded
            }

            "download" -> {
                val url = paramString(args, "url", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val filename = paramString(args, "filename", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.downloadDir)
                imageDownloader.download(url, dir, filename).toResultMap()
            }

            "downloadAll" -> {
                requireNotNull(driver) { "downloadAll requires a WebDriver receiver (current page context)" }
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.downloadDir)

                // Temporarily override config filters if provided
                val minW = paramInt(args, "minWidth", functionName, required = false)
                val minH = paramInt(args, "minHeight", functionName, required = false)
                val basis = paramDimensionBasis(args, functionName)

                val images = imageDetector.detect(driver)
                val filtered = ImageDimensionFilter.filter(images, minW, minH, basis)

                logger.info(
                    "downloadAll: detected {} images, {} after filtering (basis={}, filteredOut={})",
                    images.size, filtered.size, basis.id, images.size - filtered.size
                )

                // Report detection and filter counts so a surprising attempt count
                // is explainable instead of silent.
                imageDownloader.downloadAll(
                    images = filtered,
                    outputDir = dir,
                    totalDetected = images.size,
                    filteredOut = images.size - filtered.size,
                ).toResultMap()
            }

            "downloadBatch" -> {
                val urls = paramStringList(args, "urls", functionName)
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.downloadDir)
                imageDownloader.downloadBatch(urls, dir).toResultMap()
            }

            else -> throw IllegalArgumentException(
                "Unsupported image method: $functionName. " +
                    "Supported: detectImages, download, downloadAll, downloadBatch."
            )
        }
    }

    /**
     * The size basis for dimension filters: `rendered` (default) or `natural`.
     * An unknown value fails loudly instead of silently falling back, so a typo
     * cannot change which images a filter keeps.
     */
    private fun paramDimensionBasis(args: Map<String, Any?>, functionName: String): DimensionBasis {
        val raw = paramString(args, "dimensionBasis", functionName, required = false)
            ?: return DimensionBasis.RENDERED
        return DimensionBasis.fromId(raw) ?: throw IllegalArgumentException(
            "Parameter 'dimensionBasis' must be one of ${DimensionBasis.ids} for $functionName | actual='$raw'"
        )
    }

    /**
     * A download result as a plain map.
     *
     * The shared tool-result renderer JSON-serializes Map/Collection results but
     * wraps anything else as `{"type": …, "description": toString()}`, which is
     * what made a single download an unparseable Java `toString()` while a
     * detection list came back as structured JSON.
     */
    private fun ImageDownloader.ImageDownloadResult.toResultMap(): Map<String, Any?> = linkedMapOf(
        "url" to url,
        "filePath" to filePath,
        "bytesDownloaded" to bytesDownloaded,
        "contentType" to contentType,
        "width" to width,
        "height" to height,
        "durationMs" to durationMs,
        "success" to success,
        "error" to error,
    )

    /** A bulk summary as a plain map, so it serializes as JSON like the two above. */
    private fun ImageDownloader.BulkDownloadSummary.toResultMap(): Map<String, Any?> = linkedMapOf(
        "totalDetected" to totalDetected,
        "totalAttempted" to totalAttempted,
        "successful" to successful,
        "failed" to failed,
        "filteredOut" to filteredOut,
        "totalBytesDownloaded" to totalBytesDownloaded,
        "results" to results.map { it.toResultMap() },
    )
}
