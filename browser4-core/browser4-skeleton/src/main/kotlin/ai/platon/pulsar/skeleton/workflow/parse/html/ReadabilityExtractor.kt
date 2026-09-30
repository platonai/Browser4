package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.dom.select.selectFirstOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory

/**
 * Options controlling the [ReadabilityExtractor] heuristic.
 *
 * @property charThreshold minimum plain-text length (chars) of the extracted
 *   article before it is considered readable; shorter results return null.
 * @property keepClasses keep all class attributes in the cleaned article HTML;
 *   when false, class and id attributes are stripped unless listed in
 *   [classesToPreserve].
 * @property classesToPreserve class names (or element ids) that must be kept
 *   even when [keepClasses] is false (e.g. code-highlighting classes).
 * @property maxElemsToParse maximum number of elements to consider; 0 means
 *   unlimited. Large pages can be rejected early to bound CPU usage.
 */
data class ReadabilityOptions(
    val charThreshold: Int = 500,
    val keepClasses: Boolean = false,
    val classesToPreserve: Set<String> = emptySet(),
    val maxElemsToParse: Int = 0,
)

/**
 * The extracted article content.
 *
 * @param title page title (title tag, falling back to the article H1)
 * @param byline author name when discoverable (meta author / rel=author)
 * @param siteName site name when discoverable (og:site_name)
 * @param excerpt meta description when available, otherwise empty
 * @param content cleaned article HTML (semantic tags; classes stripped unless requested)
 * @param textContent plain text of the article
 * @param length character count of [textContent]
 * @param url resolved page URL
 * @param confidence coverage ratio — the fraction of the page's visible text
 *   captured by the article region, in 0..1. Values close to 1 mean the whole
 *   page was article-like; lower values mean the extractor narrowed in on a
 *   content region inside a noisy page.
 */
data class ReadabilityResult(
    val title: String,
    val byline: String,
    val siteName: String,
    val excerpt: String,
    val content: String,
    val textContent: String,
    val length: Int,
    val url: String,
    val confidence: Double,
)

/**
 * Deterministic, heuristic article extraction — a jsoup port of the ideas
 * behind Mozilla's Readability (the Firefox Reader View engine).
 *
 * The algorithm:
 * 1. Pre-clean: drop scripts/styles/forms/navigation and hidden elements.
 * 2. Score candidate containers by accumulated paragraph text density
 *    (link text is penalized, so link farms score near zero).
 * 3. Pick the best container, then sanitize it (noise classes, empty
 *    elements, class/id stripping).
 * 4. Extract metadata (title, byline, site name, excerpt).
 *
 * The document passed to [extract] is never mutated — the algorithm works on
 * a clone.
 *
 * No AI model is involved; the result is fully deterministic for a given
 * input.
 */
class ReadabilityExtractor(
    private val options: ReadabilityOptions = ReadabilityOptions(),
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(ReadabilityExtractor::class.java)

        /** Tags whose content is never article text. */
        private const val REMOVE_TAGS =
            "script, style, noscript, template, iframe, svg, canvas, object, embed, applet"

        /** Landmark/noise containers removed up front. */
        private const val REMOVE_SEMANTIC =
            "nav, aside, form, [role=navigation], [role=banner], [role=contentinfo], [role=complementary]"

        /** Container tags that accumulate paragraph scores. */
        private val CONTAINER_TAGS = setOf("div", "article", "main", "section", "td", "body")

        /** Tags whose text is counted as paragraph contribution. */
        private const val PARA_SELECTOR = "p, td, pre, blockquote, li"

        /** Paragraphs shorter than this are treated as noise (nav items etc.). */
        private const val MIN_PARA_TEXT = 25

        /** How many ancestor levels a paragraph contributes to. */
        private const val MAX_ANCESTOR_LEVELS = 4

        /** Score bonus for containers whose id/class suggests real content. */
        private const val CONTENT_HINT_BONUS = 30.0

        private val CONTENT_HINT = Regex("""(?i)(article|post|entry|content|main|story|blog|body|text|reading)""")

        /** Class/id fragments that usually mark noise (matched case-insensitively). */
        private val NOISE_CLASS = Regex(
            """(?i)(^|[-_])(nav|navbar|menu|sidebar|footer|header|banner|advert|ad|ads|promo|sponsor|comment|social|share|related|cookie|popup|modal|widget|breadcrumb|pagination|tags?|copyright|legalese|newsletter|subscribe)([-_]|$)""",
        )
    }

    /**
     * Quick readability check: does the page contain enough plain text to be
     * worth extracting? Runs on the original document without mutation.
     */
    fun isProbablyReaderable(document: Document): Boolean {
        val body = document.body() ?: return false
        val textLen = body.text().trim().length
        return textLen >= options.charThreshold
    }

    /**
     * Extract the main article from [document].
     *
     * @return the extracted article, or null when the page has no readable
     *   article (text below [ReadabilityOptions.charThreshold] or no
     *   article-like structure).
     */
    fun extract(document: Document): ReadabilityResult? {
        val doc = document.clone()
        if (options.maxElemsToParse > 0 && doc.getAllElements().size > options.maxElemsToParse) {
            return null
        }

        preClean(doc)

        val scores = scoreCandidates(doc)
        if (scores.isEmpty()) {
            return null
        }

        val ranked = scores.entries.sortedWith(
            compareByDescending<Map.Entry<Element, Double>> { it.value }
                .thenByDescending { it.key.text().trim().length },
        )
        val body = doc.body()
        // <body> accumulates every paragraph on the page, so it almost always
        // scores highest. Prefer the best non-body container when it captures
        // most of the body's score (a real article region inside a noisy page).
        val bestEntry = ranked.first()
        val article = if (bestEntry.key === body) {
            val nonBody = ranked.firstOrNull { it.key !== body && it.value >= bestEntry.value * 0.6 }
            nonBody?.key ?: body
        } else {
            bestEntry.key
        }

        val textLen = article.text().trim().length
        if (textLen < options.charThreshold) {
            LOG.debug("Article candidate too short ({} chars < {}): {}", textLen, options.charThreshold, doc.baseUri())
            return null
        }

        cleanArticle(article)

        val textContent = article.text().trim()
        val bodyTextLen = (doc.body()?.text()?.trim()?.length ?: 0).coerceAtLeast(1)
        val confidence = (textLen.toDouble() / bodyTextLen).coerceIn(0.0, 1.0)

        return ReadabilityResult(
            title = extractTitle(doc, article),
            byline = extractByline(doc),
            siteName = extractSiteName(doc),
            excerpt = extractExcerpt(doc),
            content = article.outerHtml(),
            textContent = textContent,
            length = textContent.length,
            url = doc.location().ifBlank { doc.baseUri() },
            confidence = confidence,
        )
    }

    // =========================================================================
    // Scoring
    // =========================================================================

    /**
     * Remove scripts/styles/forms/navigation and hidden elements from [doc].
     */
    private fun preClean(doc: Document) {
        doc.select(REMOVE_TAGS).remove()
        doc.select(REMOVE_SEMANTIC).remove()

        // Hidden elements: [hidden], aria-hidden, and inline display/visibility styles.
        // jsoup selector attribute matching on styles is fragile, so walk manually.
        for (el in doc.select("[hidden], [aria-hidden=true]")) {
            el.remove()
        }
        for (el in doc.getAllElements()) {
            val style = el.attr("style").lowercase().replace(" ", "")
            if (style.contains("display:none") || style.contains("visibility:hidden")) {
                el.remove()
            }
        }
    }

    /**
     * Score candidate containers by paragraph text density.
     *
     * Each substantial paragraph (p/td/pre/blockquote/li) contributes its
     * non-link text length to up to [MAX_ANCESTOR_LEVELS] container ancestors.
     * Containers whose id/class hints at content get a small bonus.
     *
     * @return candidate containers in document order with their scores.
     */
    private fun scoreCandidates(doc: Document): LinkedHashMap<Element, Double> {
        val scores = LinkedHashMap<Element, Double>()

        for (para in doc.select(PARA_SELECTOR)) {
            val textLen = para.text().trim().length
            if (textLen < MIN_PARA_TEXT) continue

            val linkLen = para.select("a").text().trim().length
            val contribution = (textLen - linkLen).coerceAtLeast(0)
            if (contribution <= 0) continue

            var el: Element? = para.parent()
            var level = 0
            while (el != null && level < MAX_ANCESTOR_LEVELS) {
                val tag = el.tagName()
                if (tag in CONTAINER_TAGS) {
                    scores[el] = (scores[el] ?: 0.0) + contribution
                }
                if (tag == "body") break
                el = el.parent()
                level++
            }
        }

        // Content-hint bonus on containers that look like real content.
        for ((el, score) in scores.toList()) {
            val hint = "${el.id()} ${el.className()}"
            if (hint.isNotEmpty() && CONTENT_HINT.containsMatchIn(hint)) {
                scores[el] = score + CONTENT_HINT_BONUS
            }
        }

        return scores
    }

    // =========================================================================
    // Sanitization
    // =========================================================================

    /**
     * Strip leftover noise from the chosen article container [article].
     */
    private fun cleanArticle(article: Element) {
        article.select(REMOVE_TAGS).remove()
        article.select("nav, aside, form, footer, [role=navigation], [role=contentinfo], [role=complementary]").remove()

        // Remove small elements whose id/class marks them as noise (ads, share
        // widgets, tags, breadcrumbs...). Elements carrying images survive.
        for (child in article.getAllElements().toList()) {
            if (child === article) continue
            val classAndId = "${child.className()} ${child.id()}"
            if (classAndId.isBlank()) continue
            if (NOISE_CLASS.containsMatchIn(classAndId)) {
                val hasImage = !child.select("img, picture, video, iframe").isEmpty()
                if (child.text().trim().length < 200 && !hasImage) {
                    child.remove()
                }
            }
        }

        // Remove empty structural elements (spacer divs, empty paragraphs...).
        for (child in article.select("p, div, span, h1, h2, h3, h4, h5, h6, li, td, th, blockquote, pre, section, article")) {
            if (child.text().isBlank() && child.select("img, picture, table, ul, ol, pre, iframe, video, embed, object, canvas").isEmpty()) {
                child.remove()
            }
        }

        if (!options.keepClasses) {
            for (el in article.getAllElements()) {
                val id = el.id()
                if (id.isNotEmpty() && id !in options.classesToPreserve) {
                    el.removeAttr("id")
                }
                val classes = el.className().split(" ").filter { it.isNotBlank() }
                if (classes.isNotEmpty() && classes.none { it in options.classesToPreserve }) {
                    el.removeAttr("class")
                }
            }
        }
    }

    // =========================================================================
    // Metadata
    // =========================================================================

    private fun extractTitle(doc: Document, article: Element): String {
        val title = doc.title().trim()
        if (title.isNotEmpty()) return title
        return article.selectFirstOrNull("h1")?.text()?.trim() ?: ""
    }

    private fun extractByline(doc: Document): String {
        return doc.selectFirstOrNull("meta[name=author]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirstOrNull("meta[property=article:author]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirstOrNull("a[rel=author]")?.text()?.trim() ?: ""
    }

    private fun extractSiteName(doc: Document): String {
        return doc.selectFirstOrNull("meta[property=og:site_name]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirstOrNull("meta[name=application-name]")?.attr("content")?.trim() ?: ""
    }

    private fun extractExcerpt(doc: Document): String {
        return doc.selectFirstOrNull("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirstOrNull("meta[property=og:description]")?.attr("content")?.trim() ?: ""
    }
}
