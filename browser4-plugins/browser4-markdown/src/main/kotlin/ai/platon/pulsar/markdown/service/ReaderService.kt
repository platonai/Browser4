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
import okhttp3.ResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * llms.txt access mode for [ReaderService.read].
 *
 * @property DISCOVER legacy `llms=true` behavior: return the raw content of
 *   the nearest `llms.txt`, falling back to `llms-full.txt`.
 * @property INDEX read `llms.txt` as a formatted, filterable link index.
 * @property FULL read `llms-full.txt` (optionally filtered by section).
 */
enum class LlmsMode { DISCOVER, INDEX, FULL }

/**
 * Options for [ReaderService.read].
 *
 * @property requireMd fail instead of falling back to heuristic extraction
 *   when the site provides no markdown source (llms.txt / content negotiation /
 *   `{path}.md` sibling / llms.txt link).
 * @property llms llms.txt access mode; null disables explicit discovery.
 * @property outline include a heading outline of the selected content.
 * @property filter keep only sections/links whose heading or title contains
 *   this substring (case-insensitive).
 * @property allowedDomains domain whitelist for SSRF protection; empty allows
 *   any http(s) host. Each entry may be a bare domain or a full URL; subdomains
 *   of an allowed domain are allowed. Every redirect hop is checked too.
 * @property timeoutMs per-request timeout in milliseconds; null uses the
 *   client default.
 */
data class ReadOptions(
    val requireMd: Boolean = false,
    val llms: LlmsMode? = null,
    val outline: Boolean = false,
    val filter: String? = null,
    val allowedDomains: List<String> = emptyList(),
    val timeoutMs: Long? = null,
)

/**
 * Result of [ReaderService.read].
 *
 * @param markdown the readable article as markdown
 * @param title article title (may come from llms.txt headings or meta)
 * @param url the URL the content actually came from (llms.txt URL when used)
 * @param source how the markdown was produced:
 *   `llms` / `llms-index` / `llms-full` (llms.txt family), `negotiation`
 *   (Accept: text/markdown), `path-markdown` (`{path}.md` sibling),
 *   `llms-link` (routed through llms.txt), `text` (text/plain response), or
 *   `extractor` (heuristic article extraction + markdown conversion)
 * @param charCount markdown character count
 * @param outline heading outline when requested
 * @param finalUrl effective URL after redirects
 * @param truncated whether the response body hit the 2 MB cap
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
    val finalUrl: String = url,
    val truncated: Boolean = false,
)

/**
 * Zero-token article reading pipeline (the plugin-side equivalent of
 * agent-browser's `read`):
 *
 * 1. **llms.txt discovery** — `llms=true` returns the nearest `llms.txt`
 *    (then `llms-full.txt`) walking the URL path up to the origin root;
 *    `index` formats `llms.txt` as a link index; `full` reads
 *    `llms-full.txt`. When no explicit mode is set, llms.txt is still used
 *    as a routing fallback (see 3).
 * 2. **Content negotiation** — request with `Accept: text/markdown`; when the
 *    server replies with a markdown content type, return the body directly.
 * 3. **Markdown fallbacks** — a `{path}.md` sibling of the requested URL,
 *    then routing through the site's `llms.txt` to the document's markdown
 *    link (exact path match, then origin + last-segment/slug heuristics).
 * 4. **Heuristic extraction** — parse the HTML and run
 *    [ReadabilityExtractor] to isolate the article, then convert the cleaned
 *    article region to markdown via [SiteCrawler.htmlToMarkdown].
 *
 * Every fetch validates redirect hops against [ReadOptions.allowedDomains]
 * (SSRF protection) and caps bodies at 2 MB with a `truncated` flag.
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

        /** Response bodies larger than this are truncated and flagged. */
        private const val BODY_LIMIT = 2L * 1024 * 1024

        private const val MAX_REDIRECTS = 10

        private const val ACCEPT_HEADER =
            "text/markdown, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.5"

        private val MARKDOWN_CONTENT_TYPES = setOf(
            "text/markdown", "text/x-markdown", "application/markdown", "application/x-llms",
        )
    }

    /** Client with manual redirect handling so every hop can be domain-checked. */
    private val redirectClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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
        when (options.llms) {
            LlmsMode.DISCOVER -> return discoverLlms(validatedUrl, options)
            LlmsMode.INDEX -> return llmsIndex(validatedUrl, options)
            LlmsMode.FULL -> return llmsFull(validatedUrl, options)
            null -> {}
        }

        // 2. Content negotiation + 4. HTML extraction (one fetch)
        val primary = fetch(validatedUrl, options)
        if (isMarkdownContentType(primary.contentType)) {
            return markdownResult(
                primary.body, validatedUrl, primary.finalUrl, "negotiation", options, primary.truncated,
            )
        }

        // 3. Markdown fallbacks (can also satisfy requireMd)
        markdownFallbackUrl(validatedUrl)?.let { mdUrl ->
            val md = tryFetch(mdUrl, options)
            if (md != null && md.success && usableMarkdownContentType(md.contentType, options)) {
                return markdownResult(
                    md.body, validatedUrl, md.finalUrl, "path-markdown", options, md.truncated,
                )
            }
        }

        tryLlmsLink(validatedUrl, options)?.let { return it }

        if (primary.success && !options.requireMd && isPlainTextContentType(primary.contentType)) {
            return markdownResult(
                primary.body, validatedUrl, primary.finalUrl, "text", options, primary.truncated,
            )
        }

        if (options.requireMd) {
            throw IllegalArgumentException(
                "No markdown source found for $validatedUrl (server did not negotiate markdown, " +
                    "no {path}.md sibling, and no llms.txt link), but requireMd is set. " +
                    "Use markdown.fetch for whole-page conversion or drop requireMd."
            )
        }

        if (!primary.success) {
            throw IllegalArgumentException("Read failed with HTTP ${primary.status} for $validatedUrl")
        }

        return extractFromHtml(primary.body, primary.finalUrl, validatedUrl, options)
    }

    // =========================================================================
    // Pipeline stages
    // =========================================================================

    /**
     * Return the raw content of the nearest `llms.txt`, falling back to
     * `llms-full.txt`, walking the URL path up to the origin root.
     */
    private fun discoverLlms(url: String, options: ReadOptions): ReadResult {
        val found = fetchLlmsFile(url, options, "llms.txt")
            ?: fetchLlmsFile(url, options, "llms-full.txt")
            ?: throw IllegalArgumentException("No llms.txt / llms-full.txt found for $url")
        return markdownResult(found.body, url, found.finalUrl, "llms", options, found.truncated)
    }

    /**
     * Read `llms.txt` as a formatted link index, optionally filtered.
     */
    private fun llmsIndex(url: String, options: ReadOptions): ReadResult {
        val found = fetchLlmsFile(url, options, "llms.txt")
            ?: throw IllegalArgumentException("No llms.txt found for $url")
        val content = formatLlmsIndex(found.body, found.finalUrl, options.filter)
        return ReadResult(
            markdown = content,
            title = firstHeadingOrUrl(found.body, url),
            url = url,
            finalUrl = found.finalUrl,
            source = "llms-index",
            charCount = content.length,
            outline = if (options.outline) buildMarkdownOutline(content) else emptyList(),
            truncated = found.truncated,
        )
    }

    /**
     * Read `llms-full.txt`, optionally filtered by section.
     */
    private fun llmsFull(url: String, options: ReadOptions): ReadResult {
        val found = fetchLlmsFile(url, options, "llms-full.txt")
            ?: throw IllegalArgumentException("No llms-full.txt found for $url")
        val content = options.filter?.let {
            filterMarkdownSections(found.body, it, "No matching llms-full.txt sections")
        } ?: found.body
        return ReadResult(
            markdown = content,
            title = firstHeadingOrUrl(found.body, url),
            url = url,
            finalUrl = found.finalUrl,
            source = "llms-full",
            charCount = content.length,
            outline = if (options.outline) buildMarkdownOutline(content) else emptyList(),
            truncated = found.truncated,
        )
    }

    /**
     * Discover `llms.txt` / `llms-full.txt` walking the URL path from the
     * deepest directory up to the origin root.
     *
     * @return the first successful non-HTML response, or null when none exists.
     */
    private fun fetchLlmsFile(url: String, options: ReadOptions, filename: String): FetchResult? {
        for (candidate in llmsCandidates(url, filename)) {
            val fetch = tryFetch(candidate, options) ?: continue
            if (fetch.success && !isHtmlContentType(fetch.contentType)) {
                return fetch
            }
        }
        return null
    }

    /**
     * Candidate URLs for `filename`, walking the path of [url] from deepest
     * directory up to the origin root (agent-browser parity).
     */
    private fun llmsCandidates(url: String, filename: String): List<String> {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return emptyList()
        }
        val origin = "${uri.scheme}://${uri.authority}"
        val segments = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
        val prefixes = mutableListOf<String>()
        for (len in segments.size downTo 1) {
            prefixes += "/${segments.take(len).joinToString("/")}"
        }
        prefixes += ""
        return prefixes.map { prefix ->
            val path = if (prefix.isEmpty()) "/$filename" else "${prefix.trimEnd('/')}/$filename"
            origin + path
        }.distinct()
    }

    /**
     * Route through the site's `llms.txt` to the markdown source of the target
     * document (agent-browser parity): exact path match first, then
     * origin + last-segment / slugified-title heuristics when unambiguous.
     */
    private fun tryLlmsLink(url: String, options: ReadOptions): ReadResult? {
        for (candidate in llmsCandidates(url, "llms.txt")) {
            val llmsFetch = tryFetch(candidate, options) ?: continue
            if (!llmsFetch.success || isHtmlContentType(llmsFetch.contentType)) continue

            val link = findLlmsLinkForTarget(parseLlmsLinks(llmsFetch.body, llmsFetch.finalUrl), url)
                ?: continue
            val linkFetch = tryFetch(link.url, options) ?: continue
            if (!linkFetch.success) continue
            if (options.requireMd && !isMarkdownContentType(linkFetch.contentType)) continue

            val content = if (isHtmlContentType(linkFetch.contentType)) {
                siteCrawler.htmlToMarkdown(linkFetch.body, linkFetch.finalUrl)
            } else {
                linkFetch.body
            }
            return markdownResult(content, url, linkFetch.finalUrl, "llms-link", options, linkFetch.truncated)
        }
        return null
    }

    /**
     * Fetch [url] with markdown-friendly content negotiation and manual
     * redirect handling.
     *
     * @throws IllegalArgumentException when the fetch fails or a redirect hop
     *   violates the domain whitelist.
     */
    private fun fetch(url: String, options: ReadOptions): FetchResult {
        checkAllowedDomain(url, options.allowedDomains)
        var current = url
        var hops = 0
        while (true) {
            val client = if (options.timeoutMs != null) {
                redirectClient.newBuilder().callTimeout(options.timeoutMs, TimeUnit.MILLISECONDS).build()
            } else {
                redirectClient
            }
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HEADER)
                .build()
            client.newCall(request).execute().use { response ->
                val status = response.code
                val location = response.header("Location")
                if (status in 300..399 && location != null) {
                    val next = try {
                        URI(current).resolve(location).toString()
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Invalid redirect location '$location' from $current")
                    }
                    hops += 1
                    if (hops > MAX_REDIRECTS) {
                        throw IllegalArgumentException("Too many redirects for $url")
                    }
                    if (next == current) {
                        throw IllegalArgumentException("Redirect loop at $current")
                    }
                    current = next
                    checkAllowedDomain(current, options.allowedDomains)
                } else {
                    val contentType = response.header("Content-Type")
                        ?.substringBefore(';')?.trim()?.lowercase() ?: ""
                    val body = response.body ?: ResponseBody.create(null, "")
                    val (text, truncated) = readLimitedBody(body, BODY_LIMIT)
                    return FetchResult(current, status, contentType, text, truncated, status in 200..299)
                }
            }
        }
    }

    private fun tryFetch(url: String, options: ReadOptions): FetchResult? {
        return try {
            fetch(url, options)
        } catch (e: Exception) {
            logger.debug("read fetch failed for {}: {}", url, e.message)
            null
        }
    }

    /**
     * Read at most [limit] bytes from [body], reporting whether more remain.
     */
    private fun readLimitedBody(body: ResponseBody, limit: Long): Pair<String, Boolean> {
        val source = body.source()
        val buffer = Buffer()
        val read = source.read(buffer, limit)
        val truncated = read != -1L && source.request(1)
        return buffer.readUtf8() to truncated
    }

    /**
     * Extract the article from HTML and convert it to markdown.
     */
    private fun extractFromHtml(html: String, finalUrl: String, url: String, options: ReadOptions): ReadResult {
        val doc = Jsoup.parse(html, finalUrl)
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

        val markdown = siteCrawler.htmlToMarkdown(articleHtml, finalUrl)
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
            finalUrl = finalUrl,
        )
    }

    /**
     * Build a result for a plain markdown source, applying filter/outline.
     */
    private fun markdownResult(
        body: String,
        url: String,
        finalUrl: String,
        source: String,
        options: ReadOptions,
        truncated: Boolean,
    ): ReadResult {
        val filtered = if (options.filter != null) {
            filterMarkdownSections(body, options.filter, "No matching page sections")
        } else {
            body
        }
        val outline = if (options.outline) buildMarkdownOutline(filtered) else emptyList()
        return ReadResult(
            markdown = filtered,
            title = firstHeadingOrUrl(body, url),
            url = url,
            finalUrl = finalUrl,
            source = source,
            charCount = filtered.length,
            outline = outline,
            truncated = truncated,
        )
    }

    /**
     * Format a raw llms.txt body as a deduplicated, optionally filtered link
     * index (agent-browser parity).
     */
    private fun formatLlmsIndex(body: String, finalUrl: String, filter: String?): String {
        val links = parseLlmsLinks(body, finalUrl)
            .distinctBy { "${it.title.lowercase()}\u0000${it.url}" }
        val filtered = if (filter != null) {
            val needle = filter.lowercase()
            links.filter {
                it.title.lowercase().contains(needle) || it.url.lowercase().contains(needle)
            }
        } else {
            links
        }
        if (filtered.isEmpty()) {
            return if (filter != null) "No matching llms.txt links" else body.trim()
        }
        val sb = StringBuilder("# llms.txt\n\nSource: $finalUrl\n")
        for (link in filtered) {
            sb.append("\n- [${link.title}](${link.url})")
        }
        return sb.toString()
    }

    // =========================================================================
    // llms.txt link parsing & matching (agent-browser parity)
    // =========================================================================

    private data class LlmsLink(val title: String, val url: String)

    private fun parseLlmsLinks(body: String, baseUrl: String): List<LlmsLink> {
        val base = try {
            URI(baseUrl)
        } catch (e: Exception) {
            return emptyList()
        }
        val links = mutableListOf<LlmsLink>()
        for (line in body.lineSequence()) {
            val text = markdownListItemText(line) ?: continue
            var cursor = 0
            while (true) {
                val labelStartRel = text.indexOf('[', cursor)
                if (labelStartRel < 0) break
                val labelStart = cursor + labelStartRel
                if (labelStart > 0 && text.getOrNull(labelStart - 1) == '!') {
                    cursor = labelStart + 1
                    continue
                }
                val labelEndRel = text.indexOf("](", labelStart + 1)
                if (labelEndRel < 0) break
                val labelEnd = labelEndRel
                val hrefStart = labelEnd + 2
                val hrefEndRel = text.indexOf(')', hrefStart)
                if (hrefEndRel < 0) break
                val hrefEnd = hrefEndRel

                val title = text.substring(labelStart + 1, labelEnd).trim()
                val href = text.substring(hrefStart, hrefEnd)
                    .split(Regex("\\s+")).firstOrNull().orEmpty()
                    .trim('<').trim('>')
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    try {
                        val resolved = base.resolve(href).toString()
                        if (resolved.startsWith("http")) {
                            links.add(LlmsLink(title, resolved))
                        }
                    } catch (e: Exception) {
                        // skip malformed links
                    }
                }
                cursor = hrefEnd + 1
            }
        }
        return links
    }

    /** Markdown list item text: `- `, `* `, `+ ` or `1. ` prefixes. */
    private fun markdownListItemText(line: String): String? {
        val trimmed = line.trimStart()
        for (marker in listOf("- ", "* ", "+ ")) {
            if (trimmed.startsWith(marker)) return trimmed.removePrefix(marker)
        }
        val markerEnd = trimmed.indexOfFirst { it == '.' || it == ')' }
        if (markerEnd <= 0) return null
        if (!trimmed.substring(0, markerEnd).all { it.isDigit() }) return null
        return trimmed.substring(markerEnd + 1).removePrefix(" ")
    }

    /**
     * Find the llms.txt link pointing at [targetUrl]: exact doc-path match
     * first, then origin + last-segment / slugified-title heuristics when
     * exactly one candidate matches.
     */
    private fun findLlmsLinkForTarget(links: List<LlmsLink>, targetUrl: String): LlmsLink? {
        val targetKey = docMatchKey(targetUrl) ?: return null
        val deduped = links.distinctBy { "${it.title.lowercase()}\u0000${it.url}" }

        deduped.firstOrNull { docMatchKey(it.url) == targetKey }?.let { return it }

        val targetOrigin = originKey(targetUrl) ?: return null
        val targetSegment = lastDocSegment(targetUrl) ?: return null
        val candidates = deduped.filter { link ->
            originKey(link.url) == targetOrigin &&
                (lastDocSegment(link.url) == targetSegment || slugify(link.title) == targetSegment)
        }
        return candidates.singleOrNull()
    }

    /** Origin + normalized doc path, e.g. `https://example.com/docs/guide`. */
    private fun docMatchKey(rawUrl: String): String? {
        val origin = originKey(rawUrl) ?: return null
        val path = try {
            URI(rawUrl).path
        } catch (e: Exception) {
            return null
        }
        return origin + normalizedDocPath(path)
    }

    private fun normalizedDocPath(path: String): String {
        var p = path.trimEnd('/')
        if (p.isEmpty()) p = "/"
        if (p.endsWith(".md")) p = p.removeSuffix(".md")
        if (p.endsWith("/index")) {
            p = p.removeSuffix("/index")
            if (p.isEmpty()) p = "/"
        }
        return p
    }

    private fun originKey(rawUrl: String): String? {
        return try {
            val uri = URI(rawUrl)
            "${uri.scheme}://${uri.authority}"
        } catch (e: Exception) {
            null
        }
    }

    /** Last path segment of the normalized doc path, lowercased. */
    private fun lastDocSegment(rawUrl: String): String? {
        return try {
            normalizedDocPath(URI(rawUrl).path)
                .trim('/')
                .substringAfterLast('/', "")
                .lowercase()
                .ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Slugify a link label the way doc URLs are sluget: lowercase alnum + dashes. */
    private fun slugify(label: String): String {
        val sb = StringBuilder()
        for (ch in label.lowercase()) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else if (!sb.endsWith("-")) {
                sb.append('-')
            }
        }
        return sb.toString().trim('-')
    }

    // =========================================================================
    // Content-type helpers
    // =========================================================================

    private fun contentTypeBase(contentType: String): String =
        contentType.substringBefore(';').trim().lowercase()

    private fun isMarkdownContentType(contentType: String): Boolean {
        val base = contentTypeBase(contentType)
        return base in MARKDOWN_CONTENT_TYPES || base.contains("markdown")
    }

    private fun isPlainTextContentType(contentType: String): Boolean =
        contentTypeBase(contentType) == "text/plain"

    private fun isHtmlContentType(contentType: String): Boolean {
        val base = contentTypeBase(contentType)
        return base == "text/html" || base == "application/xhtml+xml"
    }

    private fun usableMarkdownContentType(contentType: String, options: ReadOptions): Boolean {
        return if (options.requireMd) {
            isMarkdownContentType(contentType)
        } else {
            isMarkdownContentType(contentType) || isPlainTextContentType(contentType)
        }
    }

    // =========================================================================
    // Markdown post-processing
    // =========================================================================

    /** Build a markdown heading outline of a markdown document. */
    private fun buildMarkdownOutline(markdown: String): List<String> {
        val outline = mutableListOf<String>()
        for (line in markdown.lineSequence()) {
            val trimmed = line.trimStart()
            var level = 0
            while (level < trimmed.length && trimmed[level] == '#') level++
            if (level in 1..6 && trimmed.getOrNull(level)?.isWhitespace() == true) {
                val title = trimmed.substring(level).trim()
                if (title.isNotEmpty()) {
                    outline.add("  ".repeat(level - 1) + "- " + title)
                }
            }
        }
        return outline
    }

    /**
     * Keep only markdown sections whose heading region contains [filter]
     * (case-insensitive); falls back to matching lines.
     */
    private fun filterMarkdownSections(markdown: String, filter: String, noMatchMessage: String): String {
        val needle = filter.lowercase()
        val sections = mutableListOf<String>()
        var current = StringBuilder()
        for (line in markdown.lineSequence()) {
            if (line.trimStart().startsWith("#") && current.isNotBlank()) {
                if (current.toString().lowercase().contains(needle)) {
                    sections.add(current.toString().trim())
                }
                current = StringBuilder()
            }
            current.append(line).append('\n')
        }
        if (current.isNotBlank() && current.toString().lowercase().contains(needle)) {
            sections.add(current.toString().trim())
        }
        if (sections.isNotEmpty()) {
            return sections.joinToString("\n\n")
        }
        val matching = markdown.lineSequence()
            .filter { it.lowercase().contains(needle) }
            .toList()
        return if (matching.isEmpty()) noMatchMessage else matching.joinToString("\n")
    }

    /** `{path}.md` sibling of [url]; null when the path already ends in `.md`. */
    private fun markdownFallbackUrl(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        if (uri.path.endsWith(".md")) return null
        val nextPath = if (uri.path.isEmpty() || uri.path == "/") {
            "/index.md"
        } else {
            "${uri.path.trimEnd('/')}.md"
        }
        val query = if (uri.query != null) "?${uri.query}" else ""
        return "${uri.scheme}://${uri.authority}$nextPath$query"
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

    private data class FetchResult(
        val finalUrl: String,
        val status: Int,
        val contentType: String,
        val body: String,
        val truncated: Boolean,
        val success: Boolean,
    )
}
