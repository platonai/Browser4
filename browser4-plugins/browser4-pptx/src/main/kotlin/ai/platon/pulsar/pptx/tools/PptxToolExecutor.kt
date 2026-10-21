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
package ai.platon.pulsar.pptx.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.pptx.config.PptxConfig
import ai.platon.pulsar.pptx.service.PageContentExtractor
import ai.platon.pulsar.pptx.service.PptxGenerator
import ai.platon.pulsar.pptx.service.PptxGenerator.PptxGenerationOutput
import kotlin.reflect.KClass
import java.nio.file.Path

/**
 * LLM agent tool executor for the `pptx` domain.
 *
 * Provides AI agents with the ability to:
 * - `pptx.generate()` — extract content from the current page and generate a PPTX file
 */
open class PptxToolExecutor(
    private val contentExtractor: PageContentExtractor,
    private val pptxGenerator: PptxGenerator,
    private val config: PptxConfig,
) : AbstractToolExecutor() {
    private val logger = getLogger(PptxToolExecutor::class)

    override val domain = "pptx"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["generate"] = ToolSpec(
            domain = domain,
            method = "generate",
            arguments = listOf(
                ToolSpec.Arg("outputDir", "String?", "null"),
            ),
            returnType = "PptxGenerationResult",
            description = "Extract structured content (headings, paragraphs, images, tables, lists, code blocks) from the current web page and generate a PowerPoint (PPTX) file. Images are downloaded and embedded in the slides. The PPTX contains a title slide followed by content slides grouped by heading sections. Page chrome (navigation, sidebars, status badges, edit links) is filtered out. The generated filename is <sanitized-title>_<timestamp>.pptx.",
            help = """
                pptx.generate()
                pptx.generate(outputDir: String?)

                Extracts structured content from the current page DOM and generates a PPTX file.
                outputDir is the DIRECTORY where the file is written (NOT a file path); the
                generator always names the file <sanitized-page-title>_<timestamp>.pptx.
                If a .pptx-looking path is passed, its parent directory is used.

                Content is organized into slides:
                - Title slide (page title + URL)
                - One or more slides per heading section; content that does not fit a
                  slide continues on a "(continued N)" slide instead of being dropped
                - Images are downloaded and embedded
                - Tables and lists are preserved

                Image downloads are bounded: pptx.download.timeout.seconds per image and
                pptx.download.total-timeout.seconds for the whole phase. When the phase
                deadline expires the file is still written with the images that were
                fetched, and the result carries the missing-image details. Image hosts that
                need a proxy are reached through pptx.download.proxy, HTTPS_PROXY/HTTP_PROXY,
                the JVM proxy properties, or the OS system proxy.

                Returns a PptxGenerationResult with:
                - filePath: absolute path to the generated PPTX file
                - slideCount: number of slides created
                - blockCount: number of content blocks extracted
                - imageCount: number of images actually embedded on slides
                - downloadedImages: images downloaded successfully
                - failedImages: image downloads that errored (network/timeout/HTTP)
                - skippedImages: image blocks that could not be placed on any slide
                  (not downloaded, unsupported by XSLF, or embed failure)
                - skippedBlocks: content blocks that could not be rendered at all
                - skippedBlockKinds: skippedBlocks broken down by block kind
                - imageDownloadMs: wall clock spent downloading images
                - imageDownloadDeadlineExceeded: true when the download phase hit its deadline
                - warnings: human readable diagnostics, present when the deck is partial
                - durationMs: generation time in milliseconds

                The output file is saved to pptx.output.dir (default: ~/.browser4/downloads/pptx,
                an absolute path under the user's Browser4 state directory — never the backend
                working directory).
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
            "generate" -> {
                requireNotNull(driver) { "pptx.generate requires a WebDriver receiver (current page context)" }

                val startTime = System.currentTimeMillis()
                // outputDir is the canonical name; outputPath is kept as a
                // backward-compatible alias (the argument used to be named that
                // way even though it always meant a directory).
                val outputArg = paramString(args, "outputDir", functionName, required = false)
                    ?: paramString(args, "outputPath", functionName, required = false)
                val dir = resolveOutputDir(outputArg)

                // Extract content blocks from the page
                val extractStart = System.currentTimeMillis()
                val blocks = contentExtractor.extract(driver)
                logger.info(
                    "pptx.generate: extracted {} blocks from {} in {} ms",
                    blocks.size, driver.currentUrl(), System.currentTimeMillis() - extractStart
                )

                if (blocks.isEmpty()) {
                    return buildResultPayload(
                        result = null,
                        blockCount = 0,
                        slideCount = 0,
                        durationMs = System.currentTimeMillis() - startTime,
                        pageUrl = driver.currentUrl(),
                        error = "No content blocks extracted from page",
                    )
                }

                // Get page metadata
                val pageTitle = driver.evaluate("document.title").toString()
                val pageUrl = driver.currentUrl()

                // Generate PPTX
                val generateStart = System.currentTimeMillis()
                val result = pptxGenerator.generate(
                    blocks = blocks,
                    pageUrl = pageUrl,
                    pageTitle = pageTitle,
                    outputDir = dir,
                )

                // Compute statistics
                val slideCount = try {
                    org.apache.poi.xslf.usermodel.XMLSlideShow(
                        java.io.FileInputStream(result.path.toFile())
                    ).use { it.slides.size }
                } catch (e: Exception) {
                    -1
                }

                // imageCount reflects images actually embedded on slides, not
                // the number of image blocks (which can differ due to per-slide
                // caps, slide overflow, or download failures).
                val imageCount = result.embeddedImageCount

                val durationMs = System.currentTimeMillis() - startTime
                logger.info(
                    "pptx.generate complete: {} slides, {} blocks, {} embedded images " +
                        "({} downloaded, {} failed, {} skipped), render+write {} ms " +
                        "(image download {} ms), total {} ms",
                    slideCount, blocks.size, imageCount,
                    result.downloadedImageCount, result.failedImageCount, result.skippedImageCount,
                    System.currentTimeMillis() - generateStart, result.imageDownloadMillis, durationMs
                )

                buildResultPayload(
                    result = result,
                    blockCount = blocks.size,
                    slideCount = slideCount,
                    durationMs = durationMs,
                    pageUrl = pageUrl,
                )
            }

            else -> throw IllegalArgumentException(
                "Unsupported pptx method: $functionName. Supported: generate."
            )
        }
    }

    /**
     * Build the `pptx_generate` result payload.
     *
     * `downloadedImages` is the downloader's own success count. It is deliberately
     * not derived from the slide counters: `embedded + skipped` describes how images
     * landed on slides, so it over-reports downloads whenever the per-slide image cap,
     * the slide splitter, or a failed embed drops an image that was never downloaded.
     *
     * @param result the generation output, or null when nothing was generated
     * @param blockCount number of content blocks extracted from the page
     * @param slideCount number of slides written to the file
     * @param durationMs total tool duration in milliseconds
     * @param pageUrl the page the deck was generated from
     * @param error set when generation did not produce a file
     * @return the MCP tool result payload
     */
    protected fun buildResultPayload(
        result: PptxGenerationOutput?,
        blockCount: Int,
        slideCount: Int,
        durationMs: Long,
        pageUrl: String,
        error: String? = null,
    ): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "filePath" to (result?.path?.toString() ?: ""),
            "slideCount" to slideCount,
            "blockCount" to blockCount,
            "imageCount" to (result?.embeddedImageCount ?: 0),
            "downloadedImages" to (result?.downloadedImageCount ?: 0),
            "failedImages" to (result?.failedImageCount ?: 0),
            "skippedImages" to (result?.skippedImageCount ?: 0),
            "skippedBlocks" to (result?.skippedBlockCount ?: 0),
            "skippedBlockKinds" to (result?.skippedBlockKinds ?: emptyMap<String, Int>()),
            "imageDownloadDeadlineExceeded" to (result?.imageDownloadDeadlineExceeded ?: false),
            "imageDownloadMs" to (result?.imageDownloadMillis ?: 0L),
            "durationMs" to durationMs,
            "pageUrl" to pageUrl,
            "warnings" to (result?.warnings ?: emptyList<String>()),
        )
        if (error != null) {
            payload["error"] = error
        }
        return payload
    }

    /**
     * Resolve the output directory for the generated PPTX.
     *
     * The generator always names the file `<sanitized-title>_<timestamp>.pptx`,
     * so [outputArg] must be a directory. If the user passed a `.pptx`-looking
     * path (the old `outputPath` name suggested a file), its parent directory is
     * used instead of creating an oddly-named directory. Falls back to
     * [PptxConfig.outputDir] when no argument is supplied.
     */
    private fun resolveOutputDir(outputArg: String?): Path {
        if (outputArg.isNullOrBlank()) return Path.of(config.outputDir)
        val p = Path.of(outputArg)
        return if (p.toString().endsWith(".pptx", ignoreCase = true)) {
            p.parent ?: Path.of(".")
        } else {
            p
        }
    }
}
