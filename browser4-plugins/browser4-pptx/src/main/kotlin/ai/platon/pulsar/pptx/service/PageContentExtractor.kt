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
package ai.platon.pulsar.pptx.service

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.pptx.config.PptxConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

/**
 * A content block extracted from a web page, ready to be placed on a PPTX slide.
 */
data class ContentBlock(
    /** Block type: "title", "heading", "paragraph", "image", "table", "list", "code", "blockquote" */
    @SerializedName("type")
    val type: String = "",

    /** Text content for text-based blocks */
    @SerializedName("text")
    val text: String? = null,

    /** Heading level (1-6), or 0 for page title */
    @SerializedName("level")
    val level: Int? = null,

    /** Image source URL */
    @SerializedName("src")
    val src: String? = null,

    /** Image alt text / caption */
    @SerializedName("alt")
    val alt: String? = null,

    /** Image natural width */
    @SerializedName("width")
    val width: Int? = null,

    /** Image natural height */
    @SerializedName("height")
    val height: Int? = null,

    /** Table rows: each row is a list of cell text values */
    @SerializedName("rows")
    val rows: List<List<String>>? = null,

    /** List items for ordered/unordered lists */
    @SerializedName("items")
    val items: List<String>? = null,

    /** Whether the list is ordered (ol) vs unordered (ul) */
    @SerializedName("ordered")
    val ordered: Boolean? = null,
)

/**
 * Extracts structured content from a web page via CDP JavaScript evaluation.
 *
 * Runs a JS probe in the page context that walks the DOM in document order,
 * collecting headings, paragraphs, images, tables, lists, code blocks, and
 * blockquotes as structured [ContentBlock] objects.
 */
open class PageContentExtractor(
    private val config: PptxConfig = PptxConfig(),
) {
    private val logger = getLogger(PageContentExtractor::class)
    private val gson = Gson()

    /**
     * Extract structured content from the current page.
     *
     * @param driver the WebDriver connected to the target page
     * @return list of [ContentBlock] objects in document order
     */
    open suspend fun extract(driver: WebDriver): List<ContentBlock> {
        val result = try {
            driver.evaluate(CONTENT_EXTRACTION_SCRIPT)
        } catch (e: Exception) {
            logger.warn("Failed to extract page content: {}", e.message)
            null
        }
        return parseResult(result)
    }

    /**
     * Parse the JSON result from the CDP JS evaluation.
     * Public for testability.
     */
    open fun parseResult(result: Any?): List<ContentBlock> {
        if (result == null) return emptyList()
        val json = result.toString()
        if (json.isBlank() || json == "null" || json == "[]") return emptyList()

        return try {
            val type = object : TypeToken<List<ContentBlock>>() {}.type
            val blocks: List<ContentBlock> = gson.fromJson(json, type)
            blocks
        } catch (e: Exception) {
            logger.warn("Failed to parse content extraction result: {}", e.message, e)
            emptyList()
        }
    }

    companion object {
        /**
         * CDP JavaScript probe that extracts structured content from the page DOM.
         *
         * Walks the document body in document order, collecting:
         * - Page title from <title>
         * - Headings (h1-h6) with their level
         * - Paragraphs (<p>)
         * - Images (<img>) with src, alt, dimensions
         * - Tables (<table>) with row/cell data
         * - Lists (<ul>, <ol>) with items
         * - Code blocks (<pre><code>)
         * - Blockquotes (<blockquote>)
         *
         * Content is ordered as it appears in the DOM. Heading hierarchy is preserved
         * so the PPTX generator can group content into sections.
         */
        val CONTENT_EXTRACTION_SCRIPT = """
(function() {
    var blocks = [];
    function add(b) { blocks.push(b); }

    // 1. Page title
    var title = document.title;
    if (title) {
        add({ type: 'title', text: title.trim(), level: 0 });
    }

    // 2. Walk body children in document order
    var body = document.body;
    if (!body) { return JSON.stringify(blocks); }

    // TreeWalker to traverse all elements in document order
    var walker = document.createTreeWalker(
        body,
        NodeFilter.SHOW_ELEMENT,
        {
            acceptNode: function(node) {
                var tag = node.tagName.toUpperCase();
                var skip = ['SCRIPT', 'STYLE', 'NOSCRIPT', 'IFRAME', 'SVG', 'NAV', 'HEADER', 'FOOTER',
                    'META', 'LINK', 'BR', 'HR', 'INPUT', 'TEXTAREA', 'SELECT', 'BUTTON', 'FORM'];
                if (skip.indexOf(tag) !== -1) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
            }
        },
        false
    );

    var seen = {};  // track visited nodes to avoid duplicates

    function isVisible(elem) {
        if (!elem) return false;
        var style = window.getComputedStyle(elem);
        if (style.display === 'none' || style.visibility === 'hidden') return false;
        if (style.opacity === '0') return false;
        var rect = elem.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) return false;
        return true;
    }

    function getText(elem) {
        var text = (elem.textContent || '').trim();
        // Normalize whitespace
        return text.replace(/\s+/g, ' ').trim();
    }

    function shouldSkipText(text) {
        if (!text || text.length < 2) return true;
        // Skip navigation/UI boilerplate
        var boilerplate = /^(menu|search|login|sign up|sign in|subscribe|share|follow|next|previous|prev|back|top|scroll|close|accept|cancel|submit|reset|loading|home|about|contact|privacy|terms|copyright|all rights reserved)$/i;
        return boilerplate.test(text);
    }

    var node = walker.nextNode();
    while (node) {
        var tag = node.tagName.toUpperCase();

        // Headings
        if (/^H[1-6]$/.test(tag) && isVisible(node)) {
            var headingText = getText(node);
            if (!shouldSkipText(headingText)) {
                add({
                    type: 'heading',
                    text: headingText,
                    level: parseInt(tag.charAt(1))
                });
            }
            node = walker.nextNode();
            continue;
        }

        // Paragraphs - only top-level <p> elements (not inside headings, lists, etc.)
        if (tag === 'P' && isVisible(node)) {
            // Check parent is not LI, TD, TH, BLOCKQUOTE
            var parent = node.parentElement;
            var parentTag = parent ? parent.tagName.toUpperCase() : '';
            if (parentTag !== 'LI' && parentTag !== 'TD' && parentTag !== 'TH' && parentTag !== 'BLOCKQUOTE') {
                var pText = getText(node);
                if (!shouldSkipText(pText) && pText.length > 5) {
                    add({ type: 'paragraph', text: pText });
                }
            }
            node = walker.nextNode();
            continue;
        }

        // Images
        if (tag === 'IMG' && isVisible(node)) {
            var src = node.src || node.getAttribute('src') || node.getAttribute('data-src') || '';
            var alt = (node.alt || '').trim();
            if (src && !/^data:/i.test(src)) {
                add({
                    type: 'image',
                    src: src,
                    alt: alt,
                    width: node.naturalWidth || 0,
                    height: node.naturalHeight || 0
                });
            }
            node = walker.nextNode();
            continue;
        }

        // Tables
        if (tag === 'TABLE' && isVisible(node)) {
            var rows = [];
            var trs = node.querySelectorAll('tr');
            for (var i = 0; i < trs.length; i++) {
                var cells = [];
                var tds = trs[i].querySelectorAll('td, th');
                for (var j = 0; j < tds.length; j++) {
                    cells.push(getText(tds[j]));
                }
                if (cells.length > 0) rows.push(cells);
            }
            if (rows.length > 0) {
                add({ type: 'table', rows: rows });
            }
            // Skip children since we processed the whole table
            while (node && node !== walker.root) {
                var next = walker.nextSibling();
                if (next) {
                    node = next;
                    break;
                }
                walker.parentNode();
                if (!walker.currentNode) break;
                var parentTag2 = walker.currentNode.tagName.toUpperCase();
                if (parentTag2 === 'TABLE') {
                    node = walker.nextSibling();
                    break;
                }
            }
            continue;
        }

        // Lists
        if ((tag === 'UL' || tag === 'OL') && isVisible(node)) {
            var items = [];
            var lis = node.querySelectorAll('li');
            for (var k = 0; k < lis.length; k++) {
                var itemText = getText(lis[k]);
                if (itemText && !shouldSkipText(itemText)) {
                    items.push(itemText);
                }
            }
            if (items.length > 0) {
                add({
                    type: 'list',
                    items: items,
                    ordered: tag === 'OL'
                });
            }
            node = walker.nextNode();
            continue;
        }

        // Code blocks
        if (tag === 'PRE' && isVisible(node)) {
            var code = node.querySelector('code');
            var codeText = (code || node).textContent || '';
            codeText = codeText.trim();
            if (codeText) {
                add({ type: 'code', text: codeText });
            }
            node = walker.nextNode();
            continue;
        }

        // Blockquotes
        if (tag === 'BLOCKQUOTE' && isVisible(node)) {
            var quoteText = getText(node);
            if (!shouldSkipText(quoteText) && quoteText.length > 2) {
                add({ type: 'blockquote', text: quoteText });
            }
            node = walker.nextNode();
            continue;
        }

        node = walker.nextNode();
    }

    return JSON.stringify(blocks);
})();
""".trimIndent()
    }
}
