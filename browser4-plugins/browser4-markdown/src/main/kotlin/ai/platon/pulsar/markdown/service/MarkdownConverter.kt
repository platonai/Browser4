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
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Result of converting a web page to markdown.
 */
data class MarkdownResult(
    /** The generated markdown content */
    val markdown: String,

    /** Page title extracted from <title> or <h1> */
    val title: String,

    /** The page URL */
    val url: String,

    /** Number of internal links discovered on the page */
    val internalLinkCount: Int,

    /** Same-site links discovered on the page (for crawling) */
    val internalLinks: List<String>,

    /** Number of images referenced in the markdown */
    val imageCount: Int,
)

/**
 * A link extracted from the page DOM.
 */
data class PageLink(
    @JsonProperty("href")
    val href: String = "",

    @JsonProperty("text")
    val text: String? = null,

    @JsonProperty("resolvedUrl")
    val resolvedUrl: String? = null,

    @JsonProperty("isInternal")
    val isInternal: Boolean = false,
)

/**
 * Converts web page content to Markdown format via CDP JavaScript evaluation.
 *
 * The primary conversion runs a JS probe in the page context that walks the DOM
 * and produces a markdown string directly. This is a single CDP round-trip,
 * making it efficient for crawling.
 *
 * The companion link-discovery script identifies same-site URLs for crawling.
 */
open class MarkdownConverter(
    private val config: MarkdownConfig = MarkdownConfig(),
) {
    private val logger = getLogger(MarkdownConverter::class)

    /**
     * Convert the current page to a [MarkdownResult] containing the markdown content,
     * page metadata, and discovered internal links.
     *
     * @param driver the WebDriver connected to the target page
     * @return [MarkdownResult] with markdown, title, url, and links
     */
    open suspend fun convert(driver: WebDriver): MarkdownResult {
        val pageUrl = driver.currentUrl()

        // 1. Extract markdown content from the DOM
        val rawMarkdown = try {
            val result = driver.evaluate(MARKDOWN_EXTRACTION_SCRIPT)
            result?.toString() ?: ""
        } catch (e: Exception) {
            logger.warn("Markdown extraction failed for {}: {}", pageUrl, e.message)
            ""
        }

        if (rawMarkdown.isBlank()) {
            return MarkdownResult(
                markdown = "",
                title = "",
                url = pageUrl,
                internalLinkCount = 0,
                internalLinks = emptyList(),
                imageCount = 0,
            )
        }

        // 2. Extract page title
        val title = try {
            driver.evaluate("document.title").toString().trim()
        } catch (e: Exception) {
            ""
        }

        // 3. Discover internal links
        val pageLinks = try {
            val linksJson = driver.evaluate(LINK_DISCOVERY_SCRIPT)
            parseLinks(linksJson).filter { it.isInternal }
        } catch (e: Exception) {
            logger.debug("Link discovery failed for {}: {}", pageUrl, e.message)
            emptyList()
        }

        val internalUrls = pageLinks.mapNotNull { it.resolvedUrl }.distinct()

        // 4. Count images
        val imageCount = try {
            val count = driver.evaluate("document.querySelectorAll('img').length")
            count.toString().toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }

        // 5. Assemble final markdown with optional front matter and source URL
        val finalMarkdown = buildString {
            if (config.includeFrontMatter && title.isNotBlank()) {
                appendLine("---")
                appendLine("title: \"${title.replace("\"", "\\\"")}\"")
                appendLine("url: $pageUrl")
                appendLine("---")
                appendLine()
            }
            if (config.includeSourceUrl && !config.includeFrontMatter) {
                appendLine("<!-- Source: $pageUrl -->")
                appendLine()
            }
            append(rawMarkdown.trim())
            appendLine()
        }

        return MarkdownResult(
            markdown = finalMarkdown,
            title = title,
            url = pageUrl,
            internalLinkCount = internalUrls.size,
            internalLinks = internalUrls,
            imageCount = imageCount,
        )
    }

    internal fun parseLinks(result: Any?): List<PageLink> {
        if (result == null) return emptyList()
        return try {
            val json = result.toString()
            if (json.isBlank() || json == "[]") return emptyList()
            pulsarObjectMapper().readValue<List<PageLink>>(json)
        } catch (e: Exception) {
            logger.debug("Failed to parse link discovery result: {}", e.message)
            emptyList()
        }
    }

    companion object {
        /**
         * JavaScript probe that converts the current page DOM to Markdown.
         *
         * Walks the document body in document order, converting:
         * - Page title → `# Title`
         * - Headings (h1-h6) → `## Heading` etc.
         * - Paragraphs → text blocks
         * - Images → `![alt](url)`
         * - Tables → markdown tables with header separator
         * - Lists (ul/ol) → bulleted/numbered markdown lists
         * - Code blocks (`<pre><code>`) → fenced code blocks
         * - Blockquotes → `> text`
         * - Links → `[text](url)` inline
         * - Bold/strong → `**text**`
         * - Italic/em → `*text*`
         * - Horizontal rules → `---`
         *
         * Excludes elements matching the configured selectors (scripts, styles, nav, footer, etc.)
         * as well as hidden elements (display:none, visibility:hidden, zero dimensions).
         */
        val MARKDOWN_EXTRACTION_SCRIPT = """
(function() {
    var output = [];
    var IMG_EXT = /\.(jpg|jpeg|png|gif|webp|svg|bmp|ico|tiff?|avif)([?#].*)?$/i;

    // ---- Excluded selectors ----
    var EXCLUDE_SELECTORS = 'script,style,noscript,iframe,svg,nav,header,footer,meta,link,br,hr,input,textarea,select,button,form,[role="navigation"],[role="banner"],[role="contentinfo"],.nav,.navbar,.footer,.sidebar,.menu,.advertisement,.ad,.ads,.cookie-banner,.popup,.modal';

    function isExcluded(el) {
        if (!el || !el.tagName) return true;
        // Check if element matches any excluded selector
        try {
            if (el.matches && el.matches(EXCLUDE_SELECTORS)) return true;
        } catch(e) {}
        // Check if any ancestor is excluded
        var parent = el.parentElement;
        while (parent) {
            try {
                if (parent.matches && parent.matches(EXCLUDE_SELECTORS)) return true;
            } catch(e) {}
            parent = parent.parentElement;
        }
        return false;
    }

    function isVisible(el) {
        if (!el) return false;
        try {
            var style = window.getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden') return false;
            if (style.opacity === '0') return false;
            var rect = el.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) return false;
            return true;
        } catch(e) {
            return false;
        }
    }

    function getText(el) {
        var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
        return text;
    }

    function isEmptyText(text) {
        return !text || text.length < 2 && !/[^\s]/.test(text);
    }

    function inlineFormatting(el) {
        // Preserve inline formatting: bold, italic, code, links
        var result = '';
        for (var i = 0; i < el.childNodes.length; i++) {
            var child = el.childNodes[i];
            if (child.nodeType === Node.TEXT_NODE) {
                result += child.textContent || '';
            } else if (child.nodeType === Node.ELEMENT_NODE) {
                var tag = child.tagName.toUpperCase();
                var innerText = inlineFormatting(child);
                if (tag === 'STRONG' || tag === 'B') {
                    result += '**' + innerText + '**';
                } else if (tag === 'EM' || tag === 'I') {
                    result += '*' + innerText + '*';
                } else if (tag === 'CODE') {
                    result += '`' + innerText + '`';
                } else if (tag === 'A') {
                    var href = child.href || child.getAttribute('href') || '';
                    if (href && !href.startsWith('javascript:')) {
                        result += '[' + innerText + '](' + href + ')';
                    } else {
                        result += innerText;
                    }
                } else if (tag === 'BR') {
                    result += '\n';
                } else {
                    result += inlineFormatting(child);
                }
            }
        }
        return result;
    }

    // ---- Main extraction ----

    // 1. Page title as H1
    var title = document.title;
    if (title) {
        title = title.trim();
        output.push('# ' + title);
        output.push('');
    }

    // 2. Walk body using TreeWalker
    var body = document.body;
    if (!body) return JSON.stringify({ markdown: output.join('\n') });

    var walker = document.createTreeWalker(
        body,
        NodeFilter.SHOW_ELEMENT,
        { acceptNode: function(node) {
            if (isExcluded(node)) return NodeFilter.FILTER_REJECT;
            return NodeFilter.FILTER_ACCEPT;
        }},
        false
    );

    var node = walker.nextNode();
    while (node) {
        var tag = node.tagName.toUpperCase();

        // Headings (h1-h6) — skip h1 as it duplicates the page title
        if (/^H[2-6]$/.test(tag) && isVisible(node)) {
            var headingText = getText(node);
            if (!isEmptyText(headingText)) {
                var level = parseInt(tag.charAt(1));
                output.push('##'.repeat(level) + ' ' + headingText);
                output.push('');
            }
            node = walker.nextNode(); continue;
        }

        // Paragraphs
        if (tag === 'P' && isVisible(node)) {
            var parent = node.parentElement;
            var parentTag = parent ? parent.tagName.toUpperCase() : '';
            // Skip paragraphs inside list items, table cells, blockquotes
            if (parentTag !== 'LI' && parentTag !== 'TD' && parentTag !== 'TH' && parentTag !== 'BLOCKQUOTE' && parentTag !== 'FIGCAPTION') {
                var pText = inlineFormatting(node).trim();
                pText = pText.replace(/\s+/g, ' ').trim();
                if (pText && pText.length > 2) {
                    output.push(pText);
                    output.push('');
                }
            }
            node = walker.nextNode(); continue;
        }

        // Images
        if (tag === 'IMG' && isVisible(node)) {
            var src = node.src || node.getAttribute('src') || node.getAttribute('data-src') || '';
            var alt = (node.alt || node.title || '').trim();
            if (src && !/^data:/i.test(src) && !/^blob:/i.test(src)) {
                var imgText = '![' + (alt || 'image') + '](' + src + ')';
                // Add dimensions as HTML comment for context
                if (node.naturalWidth && node.naturalHeight) {
                    imgText += '\n<!-- ' + node.naturalWidth + 'x' + node.naturalHeight + ' -->';
                }
                output.push(imgText);
                output.push('');
            }
            node = walker.nextNode(); continue;
        }

        // Tables
        if (tag === 'TABLE' && isVisible(node)) {
            var rows = [];
            var trs = node.querySelectorAll('tr');
            var hasHeader = node.querySelector('thead, th') !== null;
            for (var i = 0; i < trs.length; i++) {
                var cells = [];
                var tds = trs[i].querySelectorAll('td, th');
                for (var j = 0; j < tds.length; j++) {
                    cells.push(inlineFormatting(tds[j]).replace(/\s+/g, ' ').trim());
                }
                if (cells.length > 0) rows.push(cells);
            }
            if (rows.length > 0) {
                var colCount = rows[0].length;
                for (var r = 0; r < rows.length; r++) {
                    output.push('| ' + rows[r].join(' | ') + ' |');
                    if (r === 0) {
                        var sep = [];
                        for (var s = 0; s < colCount; s++) sep.push('---');
                        output.push('| ' + sep.join(' | ') + ' |');
                    }
                }
                output.push('');
            }
            // Skip children of table (already processed)
            while (node && node !== walker.root) {
                var next = walker.nextSibling();
                if (next) { node = next; break; }
                walker.parentNode();
                if (!walker.currentNode) break;
                if (walker.currentNode.tagName.toUpperCase() === 'TABLE') {
                    node = walker.nextSibling(); break;
                }
            }
            continue;
        }

        // Lists (ul/ol)
        if ((tag === 'UL' || tag === 'OL') && isVisible(node)) {
            var lis = node.querySelectorAll('li');
            var idx = 0;
            for (var k = 0; k < lis.length; k++) {
                var itemText = inlineFormatting(lis[k]).replace(/\s+/g, ' ').trim();
                if (itemText && itemText.length > 1) {
                    var prefix = tag === 'OL' ? (++idx) + '. ' : '- ';
                    output.push(prefix + itemText);
                }
            }
            if (idx > 0) output.push('');
            node = walker.nextNode(); continue;
        }

        // Code blocks
        if (tag === 'PRE' && isVisible(node)) {
            var code = node.querySelector('code');
            var codeText = (code || node).textContent || '';
            codeText = codeText.replace(/\t/g, '    ');
            // Detect language from class
            var lang = '';
            if (code) {
                var classes = code.className || '';
                var match = classes.match(/language-(\w+)/i);
                if (match) lang = match[1];
            }
            if (codeText.trim()) {
                output.push('```' + lang);
                output.push(codeText.trimRight());
                output.push('```');
                output.push('');
            }
            node = walker.nextNode(); continue;
        }

        // Blockquotes
        if (tag === 'BLOCKQUOTE' && isVisible(node)) {
            var quoteText = inlineFormatting(node).replace(/\s+/g, ' ').trim();
            if (!isEmptyText(quoteText) && quoteText.length > 3) {
                var lines = quoteText.split('\n');
                for (var l = 0; l < lines.length; l++) {
                    output.push('> ' + lines[l]);
                }
                output.push('');
            }
            node = walker.nextNode(); continue;
        }

        // Horizontal rules
        if (tag === 'HR') {
            output.push('---');
            output.push('');
            node = walker.nextNode(); continue;
        }

        // Figure with figcaption
        if (tag === 'FIGURE' && isVisible(node)) {
            var img = node.querySelector('img');
            var figcaption = node.querySelector('figcaption');
            if (img) {
                var imgSrc = img.src || img.getAttribute('src') || '';
                var imgAlt = (img.alt || '').trim();
                var captionText = figcaption ? getText(figcaption) : '';
                if (imgSrc && !/^data:/i.test(imgSrc)) {
                    output.push('![' + (imgAlt || captionText || 'image') + '](' + imgSrc + ')');
                    if (captionText) output.push('*' + captionText + '*');
                    output.push('');
                }
            }
            node = walker.nextNode(); continue;
        }

        node = walker.nextNode();
    }

    return output.join('\n');
})()
""".trimIndent()

        /**
         * JavaScript probe that discovers all links on the current page.
         *
         * Extracts `<a href>` elements, resolves relative URLs, and flags
         * internal same-host links for crawling.
         */
        val LINK_DISCOVERY_SCRIPT = """
(function() {
    var links = [];
    var seen = {};
    var pageHost = window.location.hostname;

    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
        var a = anchors[i];
        var href = a.getAttribute('href') || '';
        var text = (a.textContent || '').trim().substring(0, 200);

        // Skip empty, javascript:, mailto:, tel:, # only anchors
        if (!href) continue;
        href = href.trim();
        if (href.startsWith('javascript:') || href.startsWith('mailto:') ||
            href.startsWith('tel:') || href === '#') continue;

        // Resolve URL
        var resolvedUrl;
        try {
            resolvedUrl = new URL(href, window.location.href).href;
        } catch(e) {
            continue;
        }

        // Skip non-http(s) URLs
        if (!resolvedUrl.startsWith('http://') && !resolvedUrl.startsWith('https://')) continue;

        // Remove fragment for deduplication
        var cleanUrl = resolvedUrl.split('#')[0];
        if (seen[cleanUrl]) continue;
        seen[cleanUrl] = true;

        var resolvedHost;
        try {
            resolvedHost = new URL(resolvedUrl).hostname;
        } catch(e) {
            resolvedHost = '';
        }

        var isInternal = resolvedHost === pageHost;

        links.push({
            href: href,
            text: text || null,
            resolvedUrl: cleanUrl,
            isInternal: isInternal
        });
    }

    return JSON.stringify(links);
})()
""".trimIndent()
    }
}
