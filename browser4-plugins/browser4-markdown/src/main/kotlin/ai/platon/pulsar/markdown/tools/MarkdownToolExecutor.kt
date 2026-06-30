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
package ai.platon.pulsar.markdown.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.markdown.config.MarkdownConfig
import ai.platon.pulsar.markdown.service.MarkdownConverter
import ai.platon.pulsar.markdown.service.MarkdownUtils
import ai.platon.pulsar.markdown.service.SiteCrawler
import kotlin.reflect.KClass
import java.nio.file.Path

/**
 * LLM agent tool executor for the `markdown` domain.
 *
 * Provides AI agents with the ability to:
 * - `markdown.convert()` — convert the current page to markdown and save it
 * - `markdown.crawl()` — crawl a site starting from the current page, saving each page as markdown
 * - `markdown.crawlFrom(url)` — crawl a site starting from a specific URL
 * - `markdown.fetch(url)` — fetch a single URL via HTTP and convert to markdown (no browser)
 */
open class MarkdownToolExecutor(
    private val config: MarkdownConfig,
    private val converter: MarkdownConverter,
    private val siteCrawler: SiteCrawler,
) : AbstractToolExecutor() {
    private val logger = getLogger(MarkdownToolExecutor::class)

    override val domain = "markdown"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["convert"] = ToolSpec(
            domain = domain,
            method = "convert",
            arguments = listOf(
                ToolSpec.Arg("outputPath", "String?", "null"),
            ),
            returnType = "MarkdownConversionResult",
            description = "Convert the current web page to a Markdown (.md) file. Extracts headings, paragraphs, images, tables, lists, code blocks, and blockquotes from the DOM. Saves the result to the markdown output directory.",
            help = """
                markdown.convert()
                markdown.convert(outputPath: String?)

                Extracts structured content from the current page DOM and saves it as a Markdown file.
                The output includes:
                - YAML front matter (title, URL)
                - Page title as H1
                - Headings preserved with proper hierarchy
                - Images as ![alt](src)
                - Tables as markdown tables
                - Lists as markdown lists
                - Code blocks with language detection
                - Blockquotes

                Returns a result with:
                - filePath: absolute path to the saved .md file
                - title: page title
                - charCount: number of characters in the markdown
                - linkCount: number of internal links discovered
                - imageCount: number of images on the page
            """.trimIndent()
        )

        toolSpec["crawl"] = ToolSpec(
            domain = domain,
            method = "crawl",
            arguments = listOf(
                ToolSpec.Arg("maxDepth", "Int?", "null"),
                ToolSpec.Arg("maxPages", "Int?", "null"),
                ToolSpec.Arg("outputPath", "String?", "null"),
            ),
            returnType = "CrawlSummary",
            description = "Crawl the current site starting from the current page. Follows internal links (same domain) up to the configured depth and page limits. Each page is converted to a Markdown file. Uses BFS crawl order.",
            help = """
                markdown.crawl()
                markdown.crawl(maxDepth: Int?)
                markdown.crawl(maxDepth: Int?, maxPages: Int?)
                markdown.crawl(maxDepth: Int?, maxPages: Int?, outputPath: String?)

                Crawls the site starting from the current browser page.
                Follows internal same-domain links using BFS order.
                Each visited page is converted to markdown and saved as a .md file.

                Configuration defaults (override with properties or arguments):
                - maxDepth: ${config.maxDepth} (0 = single page only)
                - maxPages: ${config.maxPages}
                - sameDomainOnly: ${config.sameDomainOnly}
                - crawlDelayMs: ${config.crawlDelayMs}ms between pages

                Returns a CrawlSummary with:
                - pagesCrawled: total pages successfully crawled
                - pagesFailed: pages that failed
                - totalLinksDiscovered: total internal links found
                - totalImages: total images found across pages
                - durationMs: total crawl time
                - pageResults: per-page results with filePath, title, linkCount
            """.trimIndent()
        )

        toolSpec["crawlFrom"] = ToolSpec(
            domain = domain,
            method = "crawlFrom",
            arguments = listOf(
                ToolSpec.Arg("startUrl", "String"),
                ToolSpec.Arg("maxDepth", "Int?", "null"),
                ToolSpec.Arg("maxPages", "Int?", "null"),
                ToolSpec.Arg("outputPath", "String?", "null"),
            ),
            returnType = "CrawlSummary",
            description = "Crawl a site starting from a specific URL. Navigates to the start URL first, then follows internal links (same domain) up to the configured limits. Each page is converted to a Markdown file.",
            help = """
                markdown.crawlFrom(startUrl: String)
                markdown.crawlFrom(startUrl: String, maxDepth: Int?, maxPages: Int?, outputPath: String?)

                Navigates to startUrl and begins crawling from there.
                Same behavior as markdown.crawl() but explicitly sets the starting point.

                Example:
                markdown.crawlFrom("https://example.com/docs", maxDepth: 3, maxPages: 100)
            """.trimIndent()
        )

        toolSpec["fetch"] = ToolSpec(
            domain = domain,
            method = "fetch",
            arguments = listOf(
                ToolSpec.Arg("url", "String"),
                ToolSpec.Arg("outputPath", "String?", "null"),
            ),
            returnType = "MarkdownConversionResult",
            description = "Fetch a single URL via direct HTTP and convert its HTML to Markdown. Does NOT use the browser — suitable for static pages. For JavaScript-rendered pages, use convert() instead.",
            help = """
                markdown.fetch(url: String)
                markdown.fetch(url: String, outputPath: String?)

                Fetches a URL via direct HTTP (OkHttp) and converts the HTML to Markdown
                using Jsoup parsing. This is a lightweight alternative that does not require
                a browser. Does NOT execute JavaScript.

                Returns the same result type as markdown.convert().
                For JavaScript-rendered content, use markdown.convert() or markdown.crawl() instead.
            """.trimIndent()
        )

        toolSpec["discoverLinks"] = ToolSpec(
            domain = domain,
            method = "discoverLinks",
            arguments = emptyList(),
            returnType = "List<PageLink>",
            description = "Discover all links on the current page. Returns internal (same-domain) and external links with their resolved URLs and link text. Useful for exploring site structure before crawling.",
            help = """
                markdown.discoverLinks()

                Scans the current page DOM for all <a href> elements.
                Returns a list of PageLink objects with:
                - href: the raw href attribute value
                - text: link text (truncated at 200 chars)
                - resolvedUrl: absolute resolved URL
                - isInternal: whether the link points to the same domain

                Use this to explore a site's link structure before deciding what to crawl.
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
            "convert" -> {
                requireNotNull(driver) { "markdown.convert requires a WebDriver receiver (current page context)" }

                val startTime = System.currentTimeMillis()
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.outputDir)

                val result = converter.convert(driver)

                if (result.markdown.isBlank()) {
                    return mapOf(
                        "filePath" to "",
                        "title" to result.title,
                        "url" to result.url,
                        "charCount" to 0,
                        "linkCount" to result.internalLinkCount,
                        "imageCount" to result.imageCount,
                        "durationMs" to (System.currentTimeMillis() - startTime),
                        "error" to "No content extracted from page",
                    )
                }

                // Save to file
                val filename = MarkdownUtils.generateFilename(
                    result.title.ifBlank { result.url.removePrefix("https://").removePrefix("http://") },
                    config.maxTitleLength
                ) + ".md"

                java.nio.file.Files.createDirectories(dir)
                val safeName = MarkdownUtils.sanitizeFilename(filename)
                val filePath = dir.resolve(safeName)
                val uniquePath = if (java.nio.file.Files.exists(filePath)) {
                    MarkdownUtils.uniquePath(dir, safeName)
                } else {
                    filePath
                }

                MarkdownUtils.requirePathWithinBase(dir, uniquePath)
                java.nio.file.Files.writeString(uniquePath, result.markdown)

                val durationMs = System.currentTimeMillis() - startTime
                logger.info(
                    "Converted {} to markdown: {} ({} chars, {}ms)",
                    result.url, uniquePath.fileName, result.markdown.length, durationMs
                )

                mapOf(
                    "filePath" to uniquePath.toAbsolutePath().toString(),
                    "title" to result.title,
                    "url" to result.url,
                    "charCount" to result.markdown.length,
                    "linkCount" to result.internalLinkCount,
                    "imageCount" to result.imageCount,
                    "durationMs" to durationMs,
                )
            }

            "crawl" -> {
                requireNotNull(driver) { "markdown.crawl requires a WebDriver receiver (current page context)" }

                val maxDepth = paramInt(args, "maxDepth", functionName, required = false) ?: config.maxDepth
                val maxPages = paramInt(args, "maxPages", functionName, required = false) ?: config.maxPages
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.outputDir)

                // Temporarily create a config override for this crawl
                val crawlConfig = config.copy(maxDepth = maxDepth, maxPages = maxPages)
                val crawler = SiteCrawler(crawlConfig, converter, siteCrawler.httpClient)

                val summary = crawler.crawl(driver = driver, outputDir = dir)

                mapOf(
                    "pagesCrawled" to summary.pagesCrawled,
                    "pagesFailed" to summary.pagesFailed,
                    "totalLinksDiscovered" to summary.totalLinksDiscovered,
                    "totalImages" to summary.totalImages,
                    "durationMs" to summary.durationMs,
                    "pageResults" to summary.pageResults.map { pr ->
                        mapOf(
                            "url" to pr.url,
                            "title" to pr.title,
                            "filePath" to pr.filePath,
                            "linkCount" to pr.linkCount,
                            "success" to pr.success,
                            "error" to (pr.error ?: ""),
                        )
                    },
                )
            }

            "crawlFrom" -> {
                requireNotNull(driver) { "markdown.crawlFrom requires a WebDriver receiver" }

                val startUrl = paramString(args, "startUrl", functionName)!!
                val maxDepth = paramInt(args, "maxDepth", functionName, required = false) ?: config.maxDepth
                val maxPages = paramInt(args, "maxPages", functionName, required = false) ?: config.maxPages
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.outputDir)

                val crawlConfig = config.copy(maxDepth = maxDepth, maxPages = maxPages)
                val crawler = SiteCrawler(crawlConfig, converter, siteCrawler.httpClient)

                val summary = crawler.crawl(driver = driver, startUrl = startUrl, outputDir = dir)

                mapOf(
                    "pagesCrawled" to summary.pagesCrawled,
                    "pagesFailed" to summary.pagesFailed,
                    "totalLinksDiscovered" to summary.totalLinksDiscovered,
                    "totalImages" to summary.totalImages,
                    "durationMs" to summary.durationMs,
                    "pageResults" to summary.pageResults.map { pr ->
                        mapOf(
                            "url" to pr.url,
                            "title" to pr.title,
                            "filePath" to pr.filePath,
                            "linkCount" to pr.linkCount,
                            "success" to pr.success,
                            "error" to (pr.error ?: ""),
                        )
                    },
                )
            }

            "fetch" -> {
                val url = paramString(args, "url", functionName)!!
                val outputPath = paramString(args, "outputPath", functionName, required = false)
                val dir = if (!outputPath.isNullOrBlank()) Path.of(outputPath) else Path.of(config.outputDir)

                val result = siteCrawler.fetchAndConvert(url, dir)

                mapOf(
                    "filePath" to result.filePath,
                    "title" to result.title,
                    "url" to result.url,
                    "linkCount" to result.linkCount,
                    "success" to result.success,
                    "error" to (result.error ?: ""),
                )
            }

            "discoverLinks" -> {
                requireNotNull(driver) { "markdown.discoverLinks requires a WebDriver receiver (current page context)" }

                val result = driver.evaluate(MarkdownConverter.LINK_DISCOVERY_SCRIPT)
                val links = converter.parseLinks(result)

                val internal = links.filter { it.isInternal }
                val external = links.filter { !it.isInternal }

                mapOf(
                    "totalLinks" to links.size,
                    "internalLinks" to internal.size,
                    "externalLinks" to external.size,
                    "internalUrls" to internal.mapNotNull { it.resolvedUrl }.distinct(),
                    "externalUrls" to external.mapNotNull { it.resolvedUrl }.distinct(),
                    "links" to links.map { link ->
                        mapOf(
                            "href" to link.href,
                            "text" to (link.text ?: ""),
                            "resolvedUrl" to (link.resolvedUrl ?: ""),
                            "isInternal" to link.isInternal,
                        )
                    },
                )
            }

            else -> throw IllegalArgumentException(
                "Unsupported markdown method: $functionName. " +
                    "Supported: convert, crawl, crawlFrom, fetch, discoverLinks."
            )
        }
    }

}
