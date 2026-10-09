package ai.platon.pulsar.skeleton.workflow.parse.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Options for [HtmlToMarkdown].
 *
 * @property includeFrontMatter prepend a YAML front matter block with the page
 *   title and URL.
 * @property includeSourceUrl prepend an HTML comment with the page URL; ignored
 *   when [includeFrontMatter] is set.
 * @property excludeSelectors CSS selectors removed before conversion (ads,
 *   cookie banners, …).
 * @property baseUrl the URL relative links and images resolve against; also the
 *   URL written into the front matter.
 */
data class MarkdownOptions(
    val includeFrontMatter: Boolean = false,
    val includeSourceUrl: Boolean = false,
    val excludeSelectors: List<String> = emptyList(),
    val baseUrl: String = "",
)

/**
 * Deterministic HTML to Markdown conversion.
 *
 * This is the converter behind the `markdown` output format: the format engine
 * already holds the page's HTML (from one capture) and needs markdown derived
 * from **that** HTML, without a browser round trip. It is deliberately a pure
 * function of the HTML — same input, same output — so a captured snapshot can be
 * re-rendered later and compared.
 *
 * It does not decide what part of the page matters: pass readability-cleaned
 * HTML for article markdown, or the full document for everything. It does strip
 * `<script>`, `<style>`, `<noscript>`, `<template>`, `<svg>`, `<canvas>`,
 * `<iframe>` and embedded objects, because no markdown representation of those
 * is meaningful.
 *
 * Supported constructs: headings, paragraphs, inline links / emphasis / inline
 * code / strikethrough / hard breaks, ordered and unordered (nested) lists,
 * tables (with or without a header row), fenced code blocks with language
 * detection, blockquotes, images, horizontal rules and definition lists.
 * Rendering order follows document order; whitespace is normalized so the output
 * is stable regardless of the source indentation.
 *
 * @property options conversion options; see [MarkdownOptions].
 */
class HtmlToMarkdown(private val options: MarkdownOptions = MarkdownOptions()) {

    /**
     * Convert [html] to markdown.
     *
     * @param html the HTML to convert; blank input yields an empty string.
     * @param baseUrl the URL relative links resolve against; defaults to
     *   [MarkdownOptions.baseUrl].
     * @return the markdown body, or an empty string when the document has no
     *   renderable content.
     */
    fun convert(html: String, baseUrl: String = options.baseUrl): String {
        if (html.isBlank()) return ""

        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.title().trim()

        doc.select(REMOVED_SELECTORS).remove()
        options.excludeSelectors.forEach { selector ->
            if (selector.isNotBlank()) doc.select(selector).remove()
        }

        val body = doc.body() ?: return ""
        val out = StringBuilder()
        renderChildren(body, out, depth = 0)

        // Hard breaks are carried as a sentinel so per-line trimming cannot eat
        // the two trailing spaces that make them hard, then restored here.
        val markdown = normalizeBlankLines(out.toString())
            .trim()
            .replace(HARD_BREAK, "  \n")
        return withPreamble(markdown, title, baseUrl)
    }

    // ---- preamble -----------------------------------------------------------

    private fun withPreamble(markdown: String, title: String, baseUrl: String): String {
        val out = StringBuilder()
        if (options.includeFrontMatter) {
            if (title.isNotBlank()) {
                out.appendLine("---")
                out.appendLine("title: \"${title.replace("\"", "\\\"")}\"")
                if (baseUrl.isNotBlank()) out.appendLine("url: $baseUrl")
                out.appendLine("---")
                out.appendLine()
            }
        } else if (options.includeSourceUrl && baseUrl.isNotBlank()) {
            out.appendLine("<!-- Source: $baseUrl -->")
            out.appendLine()
        }
        out.append(markdown)
        return out.toString().trim()
    }

    // ---- block rendering ----------------------------------------------------

    /**
     * Render the block-level children of [parent].
     *
     * Stray text directly inside a flow container (a `<div>text</div>`) is
     * emitted as a paragraph — without this the text would be dropped, since the
     * walker only understands elements.
     */
    private fun renderChildren(parent: Element, out: StringBuilder, depth: Int) {
        parent.childNodes().forEach { node ->
            when (node) {
                is Element -> renderBlock(node, out, depth)
                is TextNode -> node.text().trim().takeIf { it.isNotEmpty() }
                    ?.let { appendBlock(out, it) }
            }
        }
    }

    private fun renderBlock(element: Element, out: StringBuilder, depth: Int) {
        when (element.tagName().lowercase()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = element.tagName()[1].digitToInt()
                val text = inline(element).trim()
                if (text.isNotEmpty()) appendBlock(out, "#".repeat(level) + " " + text)
            }

            "p" -> inline(element).trim().takeIf { it.isNotEmpty() }?.let { appendBlock(out, it) }

            "ul", "ol" -> renderList(element, out, depth)

            "table" -> renderTable(element, out)

            "pre" -> renderCodeBlock(element, out)

            "blockquote" -> {
                val inner = StringBuilder()
                renderChildren(element, inner, depth)
                val quoted = normalizeBlankLines(inner.toString()).trim()
                if (quoted.isNotEmpty()) {
                    appendBlock(out, quoted.lines().joinToString("\n") { line ->
                        if (line.isBlank()) ">" else "> $line"
                    })
                }
            }

            "hr" -> appendBlock(out, "---")

            "img" -> renderImage(element, out)

            "dl" -> renderDefinitionList(element, out)

            "figure", "figcaption", "div", "section", "article", "main", "aside",
            "header", "footer", "nav", "span", "details", "summary", "form", "fieldset",
            -> {
                // Containers have no markdown counterpart: recurse, but emit
                // element-less leaf content (a `<span>text</span>` block) so text
                // is not silently dropped.
                if (!element.children().isEmpty()) {
                    renderChildren(element, out, depth)
                } else {
                    inline(element).trim().takeIf { it.isNotEmpty() }?.let { appendBlock(out, it) }
                }
            }

            "br" -> Unit

            else -> {
                if (element.children().isEmpty()) {
                    inline(element).trim().takeIf { it.isNotEmpty() }?.let { appendBlock(out, it) }
                } else {
                    renderChildren(element, out, depth)
                }
            }
        }
    }

    // ---- lists --------------------------------------------------------------

    private fun renderList(list: Element, out: StringBuilder, depth: Int) {
        val ordered = list.tagName().equals("ol", ignoreCase = true)
        val start = list.attr("start").trim().toIntOrNull() ?: 1
        val indent = "  ".repeat(depth)
        var index = start

        // Direct children only: the selector engine is not used here because
        // `:scope` is not supported by every jsoup version on the classpath.
        list.children().filter { it.tagName().equals("li", ignoreCase = true) }.forEach { item ->
            // The item's own text excludes any nested list, which is rendered
            // afterwards at depth + 1.
            val own = StringBuilder()
            item.childNodes().forEach { child ->
                when (child) {
                    // A nested list is rendered at depth + 1 afterwards, never inline.
                    is Element -> if (!child.tagName().equals("ul", true) && !child.tagName().equals("ol", true)) {
                        own.append(inline(child))
                    }
                    is TextNode -> own.append(child.text())
                }
            }
            val text = own.toString().replace(WHITESPACE, " ").trim()
            val marker = if (ordered) "${index++}. " else "- "
            if (text.isNotEmpty()) out.appendLine("$indent$marker$text")

            item.children().filter { it.tagName().equals("ul", true) || it.tagName().equals("ol", true) }
                .forEach { nested -> renderList(nested, out, depth + 1) }
        }
        // Only the outermost list closes the block: a nested list must not put a
        // blank line between itself and the next item of its parent.
        if (depth == 0) out.appendLine()
    }

    // ---- tables -------------------------------------------------------------

    private fun renderTable(table: Element, out: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return

        val grid = rows.map { row ->
            row.children()
                .filter { it.tagName().equals("th", true) || it.tagName().equals("td", true) }
                .map { cellText(it) }
        }
        if (grid.all { it.isEmpty() }) return

        val columns = grid.maxOf { it.size }
        val firstRow = rows.firstOrNull() ?: return
        val hasHeader = firstRow.children().any { it.tagName().equals("th", true) }
        val head = if (hasHeader) grid.first() else List(columns) { "" }
        val body = if (hasHeader) grid.drop(1) else grid

        out.appendLine(row(head, columns))
        out.appendLine("| " + List(columns) { "---" }.joinToString(" | ") + " |")
        body.forEach { out.appendLine(row(it, columns)) }
        out.appendLine()
    }

    private fun row(cells: List<String>, columns: Int): String =
        "| " + (0 until columns).joinToString(" | ") { index ->
            cells.getOrElse(index) { "" }.replace("|", "\\|")
        } + " |"

    private fun cellText(cell: Element): String =
        inline(cell).replace(WHITESPACE, " ").trim()

    // ---- code ---------------------------------------------------------------

    private fun renderCodeBlock(pre: Element, out: StringBuilder) {
        val code = pre.selectFirst("code")
        val text = (code ?: pre).wholeText().trim('\n')
        if (text.isBlank()) return

        val language = code?.className()?.let { classes ->
            classes.split(" ").firstNotNullOfOrNull { cls ->
                LANGUAGE_CLASS_PREFIXES.firstNotNullOfOrNull { prefix ->
                    cls.removePrefix(prefix).takeIf { cls.startsWith(prefix) && it.isNotBlank() }
                }
            }
        }.orEmpty()

        // A fence must be longer than any run of backticks inside the content.
        val fence = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))
        out.appendLine("$fence$language")
        out.appendLine(text)
        out.appendLine(fence)
        out.appendLine()
    }

    private fun longestBacktickRun(text: String): Int {
        var longest = 0
        var current = 0
        text.forEach { char ->
            if (char == '`') {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }

    // ---- definition lists ---------------------------------------------------

    private fun renderDefinitionList(list: Element, out: StringBuilder) {
        list.children().filter { it.tagName().equals("dt", ignoreCase = true) }.forEach { term ->
            appendBlock(out, "**${inline(term).trim()}**")
            term.nextElementSibling()
                ?.takeIf { it.tagName().equals("dd", ignoreCase = true) }
                ?.let { definition ->
                    inline(definition).trim().takeIf { it.isNotEmpty() }?.let { appendBlock(out, it) }
                }
        }
    }

    private fun renderImage(image: Element, out: StringBuilder) {
        val src = absoluteUrl(image, "src") ?: return
        if (src.startsWith("data:")) return
        val alt = image.attr("alt").ifBlank { "image" }
        appendBlock(out, "![$alt]($src)")
    }

    // ---- inline rendering ---------------------------------------------------

    private fun inline(element: Element): String {
        val out = StringBuilder()
        element.childNodes().forEach { out.append(renderInlineNode(it)) }
        return out.toString()
    }

    private fun renderInlineNode(node: Node): String = when (node) {
        is TextNode -> node.wholeText.replace(WHITESPACE, " ")

        is Element -> when (node.tagName().lowercase()) {
            "a" -> {
                val text = inline(node).replace(WHITESPACE, " ").trim()
                val href = absoluteUrl(node, "href")
                when {
                    text.isEmpty() -> ""
                    href == null -> text
                    else -> "[$text](${escapeUrl(href)})"
                }
            }

            "strong", "b" -> wrap(inline(node), "**")
            "em", "i" -> wrap(inline(node), "*")
            "del", "s", "strike" -> wrap(inline(node), "~~")
            "code" -> {
                val text = node.wholeText().trim()
                if (text.isEmpty()) "" else "`$text`"
            }
            "br" -> HARD_BREAK
            "img" -> {
                val src = absoluteUrl(node, "src")
                if (src == null || src.startsWith("data:")) ""
                else "![${node.attr("alt").ifBlank { "image" }}](${escapeUrl(src)})"
            }
            "script", "style", "noscript", "template", "svg", "canvas", "iframe" -> ""
            else -> inline(node)
        }

        else -> ""
    }

    private fun wrap(text: String, marker: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        // Preserve the surrounding spacing so "a **b** c" does not become "a**b**c".
        val leading = if (text.startsWith(" ")) " " else ""
        val trailing = if (text.endsWith(" ")) " " else ""
        return "$leading$marker$trimmed$marker$trailing"
    }

    private fun absoluteUrl(element: Element, attribute: String): String? {
        val raw = element.attr(attribute).trim()
        if (raw.isEmpty()) return null
        val absolute = element.absUrl(attribute)
        return absolute.ifBlank { raw }
    }

    private fun escapeUrl(url: String): String = url.replace(" ", "%20")

    // ---- assembly -----------------------------------------------------------

    private fun appendBlock(out: StringBuilder, block: String) {
        if (block.isBlank()) return
        out.appendLine(block)
        out.appendLine()
    }

    /** Collapse runs of blank lines and trim trailing spaces on every line. */
    private fun normalizeBlankLines(text: String): String =
        text.lines()
            .map { it.trimEnd() }
            .fold(StringBuilder()) { acc, line ->
                val blank = line.isBlank()
                val previousBlank = acc.isNotEmpty() && acc.last() == '\n' &&
                    acc.length >= 2 && acc[acc.length - 2] == '\n'
                if (blank && previousBlank) acc else acc.append(line).append('\n')
            }
            .toString()

    private companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * Placeholder for an inline `<br>`, replaced by a trailing-two-spaces
         * line break once the output has been trimmed. A private-use code point
         * cannot occur in page text, so it cannot be confused with content.
         */
        private const val HARD_BREAK = "\uE000"

        private const val REMOVED_SELECTORS =
            "script, style, noscript, template, svg, canvas, iframe, object, embed, applet, head"

        private val LANGUAGE_CLASS_PREFIXES = listOf("language-", "lang-", "highlight-source-")
    }
}
