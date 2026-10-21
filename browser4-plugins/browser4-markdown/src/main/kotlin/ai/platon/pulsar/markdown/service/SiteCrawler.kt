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
    /** Number of pages attempted: [pagesCrawled] + [pagesFailed] */
    val pagesAttempted: Int,

    /** Number of pages successfully converted and saved */
    val pagesCrawled: Int,

    /** Number of pages that failed to convert or save */
    val pagesFailed: Int,

    /** Total internal links discovered across all pages */
    val totalLinksDiscovered: Int,

    /** Total images discovered across all pages */
    val totalImages: Int,

    /** Total duration in milliseconds */
    val durationMs: Long,

    /** Per-page results, successful and failed, in crawl order */
    val pageResults: List<CrawlPageResult>,

    /** URL the session's active tab was on before the crawl (restored afterwards) */
    val entryUrl: String = "",

    /** Whether the session's active tab was navigated back to [entryUrl] */
    val tabRestored: Boolean = false,
)

/**
 * Result for a single crawled page.
 */
data class CrawlPageResult(
    /** The page URL */
    val url: String,

    /** Page title */
    val title: String,

    /** Path to the saved markdown file (empty when the page failed) */
    val filePath: String,

    /** Number of markdown links in the saved file (0 when nothing was saved) */
    val linkCount: Int = 0,

    /** Number of internal (same-domain) links discovered on the page */
    val internalLinkCount: Int = 0,

    /** Number of characters in the saved markdown file */
    val charCount: Int = 0,

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
     * The crawl drives the session's active tab page by page, then navigates
     * that tab back to the page it was on when the crawl started, so the
     * caller's page (and its element refs) survives the crawl. The entry URL
     * and whether the tab could be restored are reported in the summary.
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
        val entryUrl = driver.currentUrl()
        var tabRestored = false

        val summary = try {
            crawlPages(driver, startUrl, outputDir, entryUrl)
        } finally {
            tabRestored = restoreEntryPage(driver, entryUrl)
        }

        return summary.copy(entryUrl = entryUrl, tabRestored = tabRestored)
    }

    /**
     * Navigate the session's active tab back to [entryUrl].
     *
     * @param driver the WebDriver whose tab was navigated by the crawl
     * @param entryUrl the URL to restore; blank when the entry page is unknown
     * @return true when the tab is on the entry page again
     */
    private suspend fun restoreEntryPage(driver: WebDriver, entryUrl: String): Boolean {
        if (entryUrl.isBlank()) return false

        return try {
            if (driver.currentUrl() == entryUrl) {
                true
            } else {
                logger.info("Restoring the session tab to {} after the crawl", entryUrl)
                driver.navigate(entryUrl)
                true
            }
        } catch (e: Exception) {
            logger.warn("Failed to restore the session tab to {}: {}", entryUrl, e.message)
            false
        }
    }

    /**
     * Run the BFS crawl loop, saving every crawled page as markdown under [outputDir].
     *
     * @param entryUrl the URL the session tab was on before the crawl; used as the
     *   starting point when [startUrl] is blank
     */
    private suspend fun crawlPages(
        driver: WebDriver,
        startUrl: String?,
        outputDir: Path?,
        entryUrl: String,
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
            entryUrl
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
                        internalLinkCount = result.internalLinkCount,
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
                        linkCount = result.markdownLinkCount,
                        internalLinkCount = result.internalLinkCount,
                        charCount = result.markdown.length,
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
                        internalLinkCount = result.internalLinkCount,
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
        val pagesCrawled = results.count { it.success }
        logger.info(
            "Crawl complete: {} pages attempted, {} succeeded, {} failed, {} links discovered, {} images, {}ms",
            results.size, pagesCrawled, failed, totalLinksDiscovered, totalImages, durationMs
        )

        return CrawlSummary(
            pagesAttempted = results.size,
            pagesCrawled = pagesCrawled,
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
                url = url, title = "", filePath = "", success = false, error = e.message,
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
                    url = validatedUrl, title = "", filePath = "", success = false,
                    error = "HTTP ${response.code}: ${response.message}",
                )
            }

            val html = response.body?.string() ?: ""
            response.close()

            if (html.isBlank()) {
                return CrawlPageResult(
                    url = validatedUrl, title = "", filePath = "", success = false,
                    error = "Empty response body",
                )
            }

            val markdown = htmlToMarkdown(html, validatedUrl)
            val title = resolveDocumentTitle(Jsoup.parse(html, validatedUrl))
            val linkCount = converter.countMarkdownLinks(markdown)

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
                linkCount = linkCount,
                charCount = markdown.length,
                success = true,
            )
        } catch (e: Exception) {
            logger.warn("HTTP fetch failed for {}: {}", validatedUrl, e.message)
            CrawlPageResult(
                url = validatedUrl, title = "", filePath = "", success = false, error = e.message,
            )
        }
    }

    /**
     * Convert raw HTML to Markdown using Jsoup.
     *
     * This is a simplified converter for static HTML. For full JS-rendered content,
     * use [MarkdownConverter.convert] with a WebDriver instead.
     */
    open fun htmlToMarkdown(html: String, pageUrl: String): String =
        htmlToMarkdown(Jsoup.parse(html, pageUrl), pageUrl)

    /**
     * Convert an already parsed document to Markdown: front matter, the document
     * title as H1, then the body.
     */
    private fun htmlToMarkdown(doc: Document, pageUrl: String): String {
        val sb = StringBuilder()

        // The title comes from <title>, the first <h1>, or og:title, so pages
        // without a <title> (httpbin.org/html) still get front matter and a
        // filename derived from their heading.
        val title = resolveDocumentTitle(doc)

        // Front matter
        if (config.includeFrontMatter) {
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
     * Resolve the document title: `<title>`, then the first `<h1>`, then
     * `og:title`. Jsoup's `title()` is blank for pages without a `<title>`,
     * which used to drop both the front matter and the reported title.
     */
    private fun resolveDocumentTitle(doc: Document): String {
        val htmlTitle = doc.title().trim()
        if (htmlTitle.isNotBlank()) return htmlTitle

        val heading = doc.selectFirst("h1")?.text()?.trim()
        if (!heading.isNullOrBlank()) return heading

        return doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: ""
    }

    /**
     * Recursively convert a Jsoup element and its children to markdown.
     */
    private fun convertElement(element: org.jsoup.nodes.Element, sb: StringBuilder, baseUrl: String) {
        for (child in element.children()) {
            when (child.tagName().lowercase()) {
                "h1" -> {
                    // Skipped: [htmlToMarkdown] already emits the resolved document
                    // title as the H1 (same rule as the CDP converter).
                }
                "h2", "h3", "h4", "h5", "h6" -> {
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
