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
package ai.platon.pulsar.markdown.service

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.markdown.config.MarkdownConfig
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.*

/**
 * Summary of a site crawl operation.
 */
data class CrawlSummary(
    /** Total pages crawled (including the starting page) */
    val pagesCrawled: Int,

    /** Number of pages that failed to convert or save */
    val pagesFailed: Int,

    /** Total internal links discovered across all pages */
    val totalLinksDiscovered: Int,

    /** Total images discovered across all pages */
    val totalImages: Int,

    /** Total duration in milliseconds */
    val durationMs: Long,

    /** Per-page results (for successful conversions) */
    val pageResults: List<CrawlPageResult>,
)

/**
 * Result for a single crawled page.
 */
data class CrawlPageResult(
    /** The page URL */
    val url: String,

    /** Page title */
    val title: String,

    /** Path to the saved markdown file */
    val filePath: String,

    /** Number of internal links discovered on this page */
    val linkCount: Int,

    /** Whether conversion succeeded */
    val success: Boolean,

    /** Error message if conversion failed */
    val error: String? = null,
)

/**
 * Crawls pages within a site and saves them as Markdown files.
 *
 * Supports two modes:
 * 1. **Browser mode** — navigates via [WebDriver] for full JS-rendered content
 * 2. **HTTP mode** — fetches raw HTML via [OkHttpClient], parses with Jsoup
 *
 * Crawling proceeds via BFS with configurable depth and page count limits.
 * Same-domain filtering is enabled by default; same-path-prefix filtering is optional.
 */
open class SiteCrawler(
    private val config: MarkdownConfig,
    private val converter: MarkdownConverter,
    val httpClient: OkHttpClient,
) {
    private val logger = getLogger(SiteCrawler::class)

    /**
     * Crawl a site starting from the current page in the browser.
     *
     * Uses [WebDriver] navigation for full JS-rendered content extraction.
     * This is the primary crawl method for browser-based crawling.
     *
     * @param driver the WebDriver connected to the browser (must be on or navigate to the start page)
     * @param startUrl optional override URL to navigate to first; defaults to the current page URL
     * @param outputDir optional override output directory
     * @return [CrawlSummary] with crawl statistics and per-page results
     */
    open suspend fun crawl(
        driver: WebDriver,
        startUrl: String? = null,
        outputDir: Path? = null,
    ): CrawlSummary {
        val startTime = System.currentTimeMillis()
        val dir = outputDir ?: Path.of(config.outputDir)
        Files.createDirectories(dir)

        // Navigate to start URL if provided
        val url = if (!startUrl.isNullOrBlank()) {
            logger.info("Navigating to start URL: $startUrl")
            driver.navigate(startUrl)
            driver.currentUrl()
        } else {
            driver.currentUrl()
        }

        logger.info("Starting site crawl from: $url (maxDepth=${config.maxDepth}, maxPages=${config.maxPages})")

        // BFS crawl state
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>() // url -> depth
        val results = mutableListOf<CrawlPageResult>()
        var totalLinksDiscovered = 0
        var totalImages = 0
        var failed = 0

        queue.addLast(url to 0)
        visited.add(url)

        while (queue.isNotEmpty() && visited.size <= config.maxPages) {
            val (currentUrl, depth) = queue.removeFirst()

            // Navigate to the page
            if (currentUrl != driver.currentUrl()) {
                try {
                    driver.navigate(currentUrl)
                    if (config.crawlDelayMs > 0) {
                        delay(config.crawlDelayMs)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to navigate to {}: {}", currentUrl, e.message)
                    results.add(
                        CrawlPageResult(
                            url = currentUrl,
                            title = "",
                            filePath = "",
                            linkCount = 0,
                            success = false,
                            error = "Navigation failed: ${e.message}",
                        )
                    )
                    failed++
                    continue
                }
            }

            // Convert page to markdown
            val result: MarkdownResult
            try {
                result = converter.convert(driver)
            } catch (e: Exception) {
                logger.warn("Failed to convert page {}: {}", currentUrl, e.message)
                results.add(
                    CrawlPageResult(
                        url = currentUrl,
                        title = "",
                        filePath = "",
                        linkCount = 0,
                        success = false,
                        error = "Conversion failed: ${e.message}",
                    )
                )
                failed++
                continue
            }

            if (result.markdown.isBlank()) {
                logger.debug("Empty markdown for {}, skipping save", currentUrl)
                results.add(
                    CrawlPageResult(
                        url = currentUrl,
                        title = result.title,
                        filePath = "",
                        linkCount = result.internalLinkCount,
                        success = false,
                        error = "No content extracted",
                    )
                )
                failed++
                continue
            }

            // Save markdown to file
            val filename = MarkdownUtils.generateFilename(
                result.title.ifBlank {
                    currentUrl.removePrefix("https://")
                        .removePrefix("http://")
                        .trimEnd('/')
                },
                config.maxTitleLength
            ) + ".md"

            val safeName = MarkdownUtils.sanitizeFilename(filename)
            val filePath = dir.resolve(safeName)
            val uniquePath = if (Files.exists(filePath)) {
                MarkdownUtils.uniquePath(dir, safeName)
            } else {
                filePath
            }

            try {
                MarkdownUtils.requirePathWithinBase(dir, uniquePath)
                Files.writeString(uniquePath, result.markdown)
                logger.info("Saved markdown: {} ({} chars)", uniquePath.fileName, result.markdown.length)

                results.add(
                    CrawlPageResult(
                        url = currentUrl,
                        title = result.title,
                        filePath = uniquePath.toAbsolutePath().toString(),
                        linkCount = result.internalLinkCount,
                        success = true,
                    )
                )
            } catch (e: Exception) {
                logger.warn("Failed to save markdown for {}: {}", currentUrl, e.message)
                results.add(
                    CrawlPageResult(
                        url = currentUrl,
                        title = result.title,
                        filePath = "",
                        linkCount = result.internalLinkCount,
                        success = false,
                        error = "Save failed: ${e.message}",
                    )
                )
                failed++
            }

            totalLinksDiscovered += result.internalLinkCount
            totalImages += result.imageCount

            // Enqueue internal links if within depth limit
            if (depth < config.maxDepth) {
                for (linkUrl in result.internalLinks) {
                    if (visited.size >= config.maxPages) break
                    if (linkUrl in visited) continue

                    // Apply same-domain filter
                    if (config.sameDomainOnly && !MarkdownUtils.isSameDomain(url, linkUrl)) {
                        continue
                    }

                    // Apply same-path-prefix filter
                    if (config.samePathPrefix != null &&
                        !MarkdownUtils.isSamePathPrefix(url, linkUrl, config.samePathPrefix!!)
                    ) {
                        continue
                    }

                    visited.add(linkUrl)
                    queue.addLast(linkUrl to depth + 1)
                }
            }
        }

        val durationMs = System.currentTimeMillis() - startTime
        logger.info(
            "Crawl complete: {} pages crawled, {} failed, {} links discovered, {} images, {}ms",
            results.size, failed, totalLinksDiscovered, totalImages, durationMs
        )

        return CrawlSummary(
            pagesCrawled = results.size,
            pagesFailed = failed,
            totalLinksDiscovered = totalLinksDiscovered,
            totalImages = totalImages,
            durationMs = durationMs,
            pageResults = results,
        )
    }

    /**
     * Fetch and convert a single page via direct HTTP (no browser required).
     *
     * Uses Jsoup to parse the HTML and produce markdown. This is a lightweight
     * alternative to browser-based conversion for static sites where JS rendering
     * is not needed.
     *
     * @param url the page URL to fetch
     * @param outputDir optional override output directory
     * @return [CrawlPageResult] for the converted page
     */
    open fun fetchAndConvert(
        url: String,
        outputDir: Path? = null,
    ): CrawlPageResult {
        val validatedUrl = try {
            MarkdownUtils.validateUrl(url)
        } catch (e: IllegalArgumentException) {
            return CrawlPageResult(
                url = url, title = "", filePath = "",
                linkCount = 0, success = false, error = e.message,
            )
        }

        val dir = outputDir ?: Path.of(config.outputDir)
        Files.createDirectories(dir)

        return try {
            val request = Request.Builder()
                .url(validatedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return CrawlPageResult(
                    url = validatedUrl, title = "", filePath = "",
                    linkCount = 0, success = false,
                    error = "HTTP ${response.code}: ${response.message}",
                )
            }

            val html = response.body?.string() ?: ""
            response.close()

            if (html.isBlank()) {
                return CrawlPageResult(
                    url = validatedUrl, title = "", filePath = "",
                    linkCount = 0, success = false, error = "Empty response body",
                )
            }

            val markdown = htmlToMarkdown(html, validatedUrl)
            val title = Jsoup.parse(html).title().trim()

            val filename = MarkdownUtils.generateFilename(
                title.ifBlank { validatedUrl.removePrefix("https://").removePrefix("http://") },
                config.maxTitleLength
            ) + ".md"

            val safeName = MarkdownUtils.sanitizeFilename(filename)
            val filePath = dir.resolve(safeName)
            val uniquePath = if (Files.exists(filePath)) {
                MarkdownUtils.uniquePath(dir, safeName)
            } else {
                filePath
            }

            MarkdownUtils.requirePathWithinBase(dir, uniquePath)
            Files.writeString(uniquePath, markdown)

            CrawlPageResult(
                url = validatedUrl,
                title = title,
                filePath = uniquePath.toAbsolutePath().toString(),
                linkCount = 0,
                success = true,
            )
        } catch (e: Exception) {
            logger.warn("HTTP fetch failed for {}: {}", validatedUrl, e.message)
            CrawlPageResult(
                url = validatedUrl, title = "", filePath = "",
                linkCount = 0, success = false, error = e.message,
            )
        }
    }

    /**
     * Convert raw HTML to Markdown using Jsoup.
     *
     * This is a simplified converter for static HTML. For full JS-rendered content,
     * use [MarkdownConverter.convert] with a WebDriver instead.
     */
    private fun htmlToMarkdown(html: String, pageUrl: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        val sb = StringBuilder()

        // Front matter
        if (config.includeFrontMatter) {
            val title = doc.title().trim()
            if (title.isNotBlank()) {
                sb.appendLine("---")
                sb.appendLine("title: \"${title.replace("\"", "\\\"")}\"")
                sb.appendLine("url: $pageUrl")
                sb.appendLine("---")
                sb.appendLine()
            }
        } else if (config.includeSourceUrl) {
            sb.appendLine("<!-- Source: $pageUrl -->")
            sb.appendLine()
        }

        // Remove excluded elements
        config.excludeSelectors.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { selector ->
            doc.select(selector).remove()
        }

        // Title
        val title = doc.title().trim()
        if (title.isNotBlank()) {
            sb.appendLine("# $title")
            sb.appendLine()
        }

        // Walk the body
        val body = doc.body()
        if (body != null) {
            convertElement(body, sb, pageUrl)
        }

        return sb.toString().trim()
    }

    /**
     * Recursively convert a Jsoup element and its children to markdown.
     */
    private fun convertElement(element: org.jsoup.nodes.Element, sb: StringBuilder, baseUrl: String) {
        for (child in element.children()) {
            when (child.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = child.tagName()[1].digitToInt()
                    val text = child.wholeText().trim().replace(Regex("\\s+"), " ")
                    if (text.isNotBlank()) {
                        sb.appendLine("${"#".repeat(level)} $text")
                        sb.appendLine()
                    }
                }
                "p" -> {
                    val text = convertInline(child).trim()
                    if (text.isNotBlank() && text.length > 2) {
                        sb.appendLine(text)
                        sb.appendLine()
                    }
                }
                "img" -> {
                    val src = child.absUrl("src").ifBlank { child.attr("src") }
                    val alt = child.attr("alt").ifBlank { "image" }
                    if (src.isNotBlank() && !src.startsWith("data:")) {
                        sb.appendLine("![$alt]($src)")
                        sb.appendLine()
                    }
                }
                "table" -> {
                    convertTable(child, sb)
                    sb.appendLine()
                }
                "ul", "ol" -> {
                    val ordered = child.tagName().lowercase() == "ol"
                    var idx = 0
                    for (li in child.select(":scope > li")) {
                        val text = convertInline(li).trim()
                        if (text.isNotBlank()) {
                            val prefix = if (ordered) "${++idx}. " else "- "
                            sb.appendLine("$prefix$text")
                        }
                    }
                    if (idx > 0) sb.appendLine()
                }
                "pre" -> {
                    val code = child.selectFirst("code")
                    val codeText = (code ?: child).wholeText().trim()
                    if (codeText.isNotBlank()) {
                        val lang = code?.className()?.let { cls ->
                            Regex("language-(\\w+)").find(cls)?.groupValues?.get(1)
                        } ?: ""
                        sb.appendLine("```$lang")
                        sb.appendLine(codeText)
                        sb.appendLine("```")
                        sb.appendLine()
                    }
                }
                "blockquote" -> {
                    val text = child.wholeText().trim().replace(Regex("\\s+"), " ")
                    if (text.isNotBlank()) {
                        text.lines().forEach { line ->
                            if (line.isNotBlank()) sb.appendLine("> $line")
                        }
                        sb.appendLine()
                    }
                }
                "hr" -> {
                    sb.appendLine("---")
                    sb.appendLine()
                }
                "figure" -> {
                    val img = child.selectFirst("img")
                    val caption = child.selectFirst("figcaption")
                    if (img != null) {
                        val src = img.absUrl("src").ifBlank { img.attr("src") }
                        val alt = img.attr("alt").ifBlank { caption?.wholeText()?.trim() ?: "image" }
                        if (src.isNotBlank() && !src.startsWith("data:")) {
                            sb.appendLine("![$alt]($src)")
                            if (caption != null) {
                                sb.appendLine("*${caption.wholeText().trim()}*")
                            }
                            sb.appendLine()
                        }
                    }
                }
                else -> {
                    // Recurse into unknown container elements
                    convertElement(child, sb, baseUrl)
                }
            }
        }
    }

    /**
     * Convert inline formatting within an element for Jsoup-based conversion.
     */
    private fun convertInline(element: org.jsoup.nodes.Element): String {
        val sb = StringBuilder()
        for (node in element.childNodes()) {
            when {
                node is org.jsoup.nodes.TextNode -> {
                    sb.append(node.wholeText)
                }
                node is org.jsoup.nodes.Element -> {
                    val inner = convertInline(node)
                    when (node.tagName().lowercase()) {
                        "strong", "b" -> sb.append("**$inner**")
                        "em", "i" -> sb.append("*$inner*")
                        "code" -> sb.append("`$inner`")
                        "a" -> {
                            val href = node.absUrl("href").ifBlank { node.attr("href") }
                            if (href.isNotBlank() && !href.startsWith("javascript:")) {
                                sb.append("[$inner]($href)")
                            } else {
                                sb.append(inner)
                            }
                        }
                        "br" -> sb.append("\n")
                        else -> sb.append(inner)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Convert a Jsoup table element to a markdown table.
     */
    private fun convertTable(table: org.jsoup.nodes.Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return

        val matrix = rows.map { row ->
            row.select("td, th").map { cell ->
                convertInline(cell).trim().replace(Regex("\\s+"), " ")
            }
        }

        if (matrix.isEmpty() || matrix[0].isEmpty()) return

        val colCount = matrix[0].size
        for ((rowIdx, row) in matrix.withIndex()) {
            sb.append("| ")
            for (col in 0 until colCount) {
                sb.append(if (col < row.size) row[col] else "")
                sb.append(" | ")
            }
            sb.appendLine()
            if (rowIdx == 0) {
                sb.append("| ")
                repeat(colCount) { sb.append("--- | ") }
                sb.appendLine()
            }
        }
    }

    companion object {
        private fun timestamp(): String {
            return DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
        }
    }
}
