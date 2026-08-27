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
import ai.platon.pulsar.skeleton.workflow.parse.html.ReadabilityExtractor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Options for [ReaderService.read].
 *
 * @property requireMd fail instead of falling back to heuristic extraction
 *   when the site provides no markdown source (llms.txt / content negotiation).
 * @property llms discover `llms.txt` / `llms-full.txt` on the site root first.
 * @property outline include a heading outline of the extracted article.
 * @property filter keep only article sections whose heading contains this
 *   substring (case-insensitive).
 * @property allowedDomains domain whitelist for SSRF protection; empty allows
 *   any http(s) host. Each entry may be a bare domain or a full URL; subdomains
 *   of an allowed domain are allowed.
 */
data class ReadOptions(
    val requireMd: Boolean = false,
    val llms: Boolean = false,
    val outline: Boolean = false,
    val filter: String? = null,
    val allowedDomains: List<String> = emptyList(),
)

/**
 * Result of [ReaderService.read].
 *
 * @param markdown the readable article as markdown
 * @param title article title (may come from llms.txt headings or meta)
 * @param url the URL the content actually came from (llms.txt URL when used)
 * @param source how the markdown was produced:
 *   `llms` (llms.txt/llms-full.txt), `negotiation` (Accept: text/markdown), or
 *   `extractor` (heuristic article extraction + markdown conversion)
 * @param charCount markdown character count
 * @param outline heading outline when requested
 */
data class ReadResult(
    val markdown: String,
    val title: String,
    val byline: String = "",
    val siteName: String = "",
    val url: String,
    val source: String,
    val charCount: Int,
    val outline: List<String> = emptyList(),
)

/**
 * Zero-token article reading pipeline (the plugin-side equivalent of
 * agent-browser's `read`):
 *
 * 1. **llms.txt discovery** — when requested, fetch `llms.txt` then
 *    `llms-full.txt` from the site root and return the content directly.
 * 2. **Content negotiation** — request with `Accept: text/markdown`; when the
 *    server replies with a markdown content type, return the body directly.
 * 3. **Heuristic extraction** — parse the HTML and run
 *    [ReadabilityExtractor] to isolate the article, then convert the cleaned
 *    article region to markdown via [SiteCrawler.htmlToMarkdown].
 *
 * No browser session and no LLM are involved; the result is deterministic for
 * a given URL.
 */
open class ReaderService(
    private val httpClient: OkHttpClient,
    private val siteCrawler: SiteCrawler,
    private val extractor: ReadabilityExtractor = ReadabilityExtractor(),
) {
    private val logger = getLogger(ReaderService::class)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val MARKDOWN_CONTENT_TYPES = setOf(
            "text/markdown", "text/x-markdown", "application/x-llms",
            "text/plain; charset=utf-8", "text/plain",
        )
    }

    /**
     * Read the article at [url] as markdown.
     *
     * @throws IllegalArgumentException for invalid URLs, disallowed domains,
     *   fetch failures, pages without readable content, or `--require-md`
     *   violations.
     */
    open fun read(url: String, options: ReadOptions = ReadOptions()): ReadResult {
        val validatedUrl = MarkdownUtils.validateUrl(url)
        checkAllowedDomain(validatedUrl, options.allowedDomains)

        // 1. llms.txt / llms-full.txt discovery (zero parsing)
        if (options.llms) {
            llmsContent(validatedUrl)?.let { (content, sourceUrl) ->
                return ReadResult(
                    markdown = content,
                    title = firstHeadingOrUrl(content, validatedUrl),
                    url = sourceUrl,
                    source = "llms",
                    charCount = content.length,
                )
            }
        }

        // 2. Content negotiation + 3. HTML extraction (one fetch)
        val (contentType, body) = fetchPage(validatedUrl)
        if (isMarkdownContentType(contentType)) {
            return ReadResult(
                markdown = body,
                title = firstHeadingOrUrl(body, validatedUrl),
                url = validatedUrl,
                source = "negotiation",
                charCount = body.length,
            )
        }

        if (options.requireMd) {
            throw IllegalArgumentException(
                "No markdown source found for $validatedUrl (server did not negotiate markdown and no llms.txt was used), " +
                    "but requireMd is set. Use markdown.fetch for whole-page conversion or drop requireMd."
            )
        }

        return extractFromHtml(body, validatedUrl, options)
    }

    // =========================================================================
    // Pipeline stages
    // =========================================================================

    /**
     * Discover `llms.txt` then `llms-full.txt` on the site root.
     *
     * @return the file content paired with its URL, or null when neither file exists.
     */
    private fun llmsContent(url: String): Pair<String, String>? {
        val origin = try {
            val uri = URI(url)
            val defaultPort = if (uri.scheme == "https") 443 else 80
            val portPart = if (uri.port > 0 && uri.port != defaultPort) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$portPart"
        } catch (e: Exception) {
            return null
        }

        for (path in listOf("/llms.txt", "/llms-full.txt")) {
            val candidate = origin + path
            val body = try {
                val request = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", USER_AGENT)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string()?.trim() else null
                }
            } catch (e: Exception) {
                logger.debug("llms.txt fetch failed for {}: {}", candidate, e.message)
                null
            }
            if (!body.isNullOrBlank()) {
                return body to candidate
            }
        }
        return null
    }

    /**
     * Fetch a URL with markdown-friendly content negotiation.
     *
     * @return the effective content type (lowercased) and body.
     * @throws IllegalArgumentException when the fetch fails.
     */
    private fun fetchPage(url: String): Pair<String, String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/markdown, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.5")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalArgumentException("HTTP ${response.code} ${response.message} for $url")
                }
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() ?: ""
                val body = response.body?.string() ?: ""
                if (body.isBlank()) {
                    throw IllegalArgumentException("Empty response body from $url")
                }
                contentType to body
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to fetch $url: ${e.message}")
        }
    }

    private fun isMarkdownContentType(contentType: String): Boolean {
        if (contentType in MARKDOWN_CONTENT_TYPES) return true
        // text/markdown with charset etc. is already stripped; also accept
        // anything explicitly markdown-ish.
        return contentType.contains("markdown") || contentType == "application/x-llms"
    }

    /**
     * Extract the article from HTML and convert it to markdown.
     */
    private fun extractFromHtml(html: String, url: String, options: ReadOptions): ReadResult {
        val doc = Jsoup.parse(html, url)
        val result = extractor.extract(doc)
            ?: throw IllegalArgumentException(
                "No readable article content found at $url (text below threshold or no article-like structure). " +
                    "Use markdown.fetch for whole-page conversion, or a browser-based page (SPA) via markdown.convert."
            )

        val articleHtml = if (options.filter != null) {
            filterSections(result.content, options.filter)
        } else {
            result.content
        }

        val markdown = siteCrawler.htmlToMarkdown(articleHtml, url)
        val outline = if (options.outline) buildOutline(articleHtml) else emptyList()

        return ReadResult(
            markdown = markdown,
            title = result.title,
            byline = result.byline,
            siteName = result.siteName,
            url = url,
            source = "extractor",
            charCount = markdown.length,
            outline = outline,
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun checkAllowedDomain(url: String, allowed: List<String>) {
        if (allowed.isEmpty()) return
        val host = try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: throw IllegalArgumentException("Invalid URL host: $url")

        val ok = allowed.any { entry ->
            val domain = entry.trim().lowercase()
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
            domain.isNotEmpty() && (host == domain || host.endsWith(".$domain"))
        }
        if (!ok) {
            throw IllegalArgumentException(
                "Domain '$host' is not allowed. allowedDomains: ${allowed.joinToString(", ")}"
            )
        }
    }

    /**
     * Keep only article sections whose heading contains [filter]
     * (case-insensitive). A section is a heading plus its following siblings
     * up to the next heading of the same or higher level.
     *
     * @throws IllegalArgumentException when no heading matches the filter.
     */
    internal fun filterSections(articleHtml: String, filter: String): String {
        val doc = Jsoup.parse(articleHtml)
        val headings = doc.select("h1, h2, h3, h4, h5, h6")
        if (headings.isEmpty()) {
            throw IllegalArgumentException("No headings found in the article to filter by '$filter'")
        }
        val matching = headings.filter { it.text().contains(filter, ignoreCase = true) }
        if (matching.isEmpty()) {
            throw IllegalArgumentException("No section matches filter '$filter'")
        }

        val keep = mutableSetOf<Element>()
        for (heading in matching) {
            keep.add(heading)
            var sibling = heading.nextElementSibling()
            while (sibling != null) {
                if (isHeading(sibling) && headingLevel(sibling) <= headingLevel(heading)) break
                keep.add(sibling)
                sibling = sibling.nextElementSibling()
            }
        }

        val body = doc.body()
        for (el in body.getAllElements().toList()) {
            if (el === body) continue
            // el is kept when it IS a kept node or is an ancestor of one
            // (parents() walks the ancestor chain; avoids fork-specific APIs).
            val containsKept = keep.any { el === it || it.parents().any { p -> p === el } }
            if (!containsKept) {
                el.remove()
            }
        }
        return body.html()
    }

    /**
     * Build a markdown heading outline of the article HTML.
     */
    internal fun buildOutline(articleHtml: String): List<String> {
        val doc = Jsoup.parse(articleHtml)
        val outline = mutableListOf<String>()
        for (heading in doc.select("h1, h2, h3, h4, h5, h6")) {
            val level = headingLevel(heading)
            val text = heading.text().trim().replace(Regex("\\s+"), " ")
            if (text.isNotBlank()) {
                outline.add("  ".repeat((level - 1).coerceAtLeast(0)) + "- " + text)
            }
        }
        return outline
    }

    private fun isHeading(el: Element): Boolean = el.tagName().length == 2 &&
        el.tagName()[0] == 'h' && el.tagName()[1] in '1'..'6'

    private fun headingLevel(el: Element): Int = el.tagName()[1].digitToInt()

    /**
     * First markdown heading of a document, falling back to the URL.
     */
    private fun firstHeadingOrUrl(markdown: String, url: String): String {
        val first = markdown.lineSequence().firstOrNull { it.startsWith("#") }?.trimStart('#')?.trim()
        return first?.takeIf { it.isNotEmpty() } ?: url
    }
}
