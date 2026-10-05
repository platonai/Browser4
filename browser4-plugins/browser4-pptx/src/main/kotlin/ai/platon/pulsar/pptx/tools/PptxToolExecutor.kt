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
            description = "Extract structured content (headings, paragraphs, images, tables, lists, code blocks) from the current web page and generate a PowerPoint (PPTX) file. Images are downloaded and embedded in the slides. The PPTX contains a title slide followed by content slides grouped by heading sections. The generated filename is <sanitized-title>_<timestamp>.pptx.",
            help = """
                pptx.generate()
                pptx.generate(outputDir: String?)

                Extracts structured content from the current page DOM and generates a PPTX file.
                outputDir is the DIRECTORY where the file is written (NOT a file path); the
                generator always names the file <sanitized-page-title>_<timestamp>.pptx.
                If a .pptx-looking path is passed, its parent directory is used.

                Content is organized into slides:
                - Title slide (page title + URL)
                - Section slides grouped by heading hierarchy
                - Images are downloaded and embedded
                - Tables and lists are preserved

                Returns a PptxGenerationResult with:
                - filePath: absolute path to the generated PPTX file
                - slideCount: number of slides created
                - blockCount: number of content blocks extracted
                - imageCount: number of images actually embedded
                - downloadedImages: images successfully downloaded
                - failedImages: images that failed to download
                - skippedImages: images dropped (per-slide cap / slide overflow)
                - durationMs: generation time in milliseconds

                The output file is saved to pptx.output.dir (default: downloads/pptx/).
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
                val blocks = contentExtractor.extract(driver)
                logger.info(
                    "pptx.generate: extracted {} blocks from {}",
                    blocks.size, driver.currentUrl()
                )

                if (blocks.isEmpty()) {
                    return mapOf(
                        "filePath" to "",
                        "slideCount" to 0,
                        "blockCount" to 0,
                        "imageCount" to 0,
                        "downloadedImages" to 0,
                        "failedImages" to 0,
                        "skippedImages" to 0,
                        "durationMs" to (System.currentTimeMillis() - startTime),
                        "pageUrl" to driver.currentUrl(),
                        "error" to "No content blocks extracted from page",
                    )
                }

                // Get page metadata
                val pageTitle = driver.evaluate("document.title").toString()
                val pageUrl = driver.currentUrl()

                // Generate PPTX
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
                        "({} downloaded, {} failed, {} skipped), {}ms",
                    slideCount, blocks.size, imageCount,
                    result.embeddedImageCount + result.skippedImageCount - result.failedImageCount,
                    result.failedImageCount, result.skippedImageCount,
                    durationMs
                )

                mapOf(
                    "filePath" to result.path.toString(),
                    "slideCount" to slideCount,
                    "blockCount" to blocks.size,
                    "imageCount" to imageCount,
                    "downloadedImages" to (result.embeddedImageCount + result.skippedImageCount),
                    "failedImages" to result.failedImageCount,
                    "skippedImages" to result.skippedImageCount,
                    "durationMs" to durationMs,
                    "pageUrl" to pageUrl,
                )
            }

            else -> throw IllegalArgumentException(
                "Unsupported pptx method: $functionName. Supported: generate."
            )
        }
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
