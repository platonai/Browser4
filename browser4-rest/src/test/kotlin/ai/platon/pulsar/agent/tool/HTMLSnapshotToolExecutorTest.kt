package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HTMLSnapshotToolExecutorTest {
    private val h2MissingColumn = "Column \"A\" not found; SQL statement"
    private val h2HexError = "Hexadecimal string contains non-hex character: \"899.99\" (SQL 90004-197)"

    private val mapper = pulsarObjectMapper()

    // =========================================================================
    // Live-document capture (html snapshot family)
    // =========================================================================

    @Test
    fun `live document JS reads url title contentType and html in one evaluation`() {
        val js = buildLiveDocumentJs()

        assertTrue(js.contains("document.URL"), "JS should read the live document URL: $js")
        assertTrue(js.contains("document.title"), "JS should read the live title: $js")
        assertTrue(js.contains("document.contentType"), "JS should read the live content type: $js")
        // The serializer prefers the page-helper annotated HTML and falls back
        // to plain outerHTML when __pulsar_utils__ is missing (stale sessions)
        assertTrue(js.contains("getAnnotatedHTML"), "JS should use the annotated serializer when available: $js")
        assertTrue(js.contains("documentElement.outerHTML"), "JS should fall back to outerHTML: $js")
    }

    @Test
    fun `live document bundle parses url title contentType and html`() {
        val html = "<html><head><title>Probe</title></head><body><p>live state</p></body></html>"
        val raw = "https://example.com/form" + "\u0001" + "Probe" + "\u0001" + "text/html" + "\u0001" + html

        val snapshot = parseLiveDocumentBundle(raw)

        assertNotNull(snapshot, "Bundle should parse")
        assertEquals("https://example.com/form", snapshot!!.url)
        assertEquals("Probe", snapshot.title)
        assertEquals("text/html", snapshot.contentType)
        assertEquals(html, snapshot.html)
    }

    @Test
    fun `live document bundle html may contain separator-like text of title and url`() {
        // The four fields are joined by a control separator that cannot occur
        // inside normal URLs/titles; HTML content itself is the last field and
        // may contain anything except the control character.
        val html = "<div>url = https://example.com/x title = X</div>"
        val raw = "https://example.com/x" + "\u0001" + "X" + "\u0001" + "text/html" + "\u0001" + html

        val snapshot = parseLiveDocumentBundle(raw)

        assertNotNull(snapshot, "Bundle should parse")
        assertEquals(html, snapshot!!.html)
    }

    @Test
    fun `live document bundle rejects non-navigable documents`() {
        val raw = "about:blank" + "\u0001" + "" + "\u0001" + "" + "\u0001" + "<html><body></body></html>"
        assertNull(parseLiveDocumentBundle(raw), "about:blank is not a capturable live document")
    }

    @Test
    fun `live document bundle rejects empty or truncated results`() {
        assertNull(parseLiveDocumentBundle(""), "Empty evaluation result is not a live document")
        val truncated = "https://example.com/x" + "\u0001" + "T" + "\u0001" + "text/html" + "\u0001"
        assertNull(parseLiveDocumentBundle(truncated), "Missing HTML content is not a live document")
        assertNull(parseLiveDocumentBundle("https://example.com/x"), "Truncated bundle is not a live document")
    }

    @Test
    fun `capture metadata reflects the live document title and interactive elements`() {
        // Mirrors a post-interaction live document: the state-log text and the
        // <title> only exist in the live DOM (a PROBE-TITLE capture proves the
        // serializer reads the live document, not an independent page load).
        val html = """
            <html><head><title>PROBE-TITLE</title></head><body>
            <div id="state-log">submit-success:email; submitCount: 1</div>
            <form id="registration-form" vi="100 20 240 200">
              <input type="text" id="first-name" value="" vi="110 40 220 30">
              <button id="submit-btn" class="primary-button" type="submit" vi="110 90 220 36">Submit form</button>
              <a href="/ec/dp/B0E000001" class="product-link" vi="110 140 220 30">Product link</a>
            </form>
            <img src="/img/a.jpg" alt="A" vi="10 10 60 40">
            </body></html>
        """.trimIndent()
        val document = FeaturedDocument(org.jsoup.Jsoup.parse(html, "https://example.com/form"))
        val json = mapper.readTree(
            htmlSnapshotMetadataJson(
                document = document,
                url = "https://example.com/form",
                href = "https://example.com/form#probe",
                sizeBytes = html.length.toLong(),
                capturedAt = "2026-09-03T10:00:00Z",
                contentType = "text/html",
            )
        )

        // The captured title is the LIVE title (the independent-load capture
        // would report the server's original title instead)
        assertEquals("PROBE-TITLE", json["title"].asText(), "Capture must serialize the live document title")
        assertEquals("https://example.com/form", json["url"].asText())
        assertEquals("https://example.com/form#probe", json["href"].asText())
        assertEquals(1, json["imageCount"].asInt())
        assertEquals(1, json["linkCount"].asInt())

        val elements = json["interactiveElements"]
        assertNotNull(elements, "Metadata must carry interactive elements")
        assertTrue(elements.size() > 0, "Interactive elements should be detected from vi boxes")

        val submit = elements.firstOrNull { it["ref"].asText().contains("submit-btn") }
        assertNotNull(submit, "The submit button must be listed, got: $elements")
        assertEquals("primary", submit!!["tier"].asText())
        assertTrue(submit["weight"].asInt() > 0, "Weight must be computed from the vi box")
        assertTrue(submit["text"].asText().contains("Submit form"), "Button text should be sampled")

        // Every interactive element keeps the CLI-compatible metadata fields
        for (el in elements) {
            assertTrue(el.has("ref"), "Element must carry a ref")
            assertTrue(el.has("tier"), "Element must carry a tier")
            assertTrue(el.has("weight"), "Element must carry a weight")
        }
    }

    @Test
    fun `capture metadata works for documents without vi attributes`() {
        // A live capture on a page whose helper is missing serializes plain
        // outerHTML; without vi boxes the weighted interactive list degrades
        // gracefully (may be empty) but the metadata must still assemble.
        val html = """
            <html><head><title>Static</title></head><body>
            <h1>Static page</h1>
            <a href="/next">Next</a>
            </body></html>
        """.trimIndent()
        val document = FeaturedDocument(org.jsoup.Jsoup.parse(html, "https://example.com/static"))
        val json = mapper.readTree(
            htmlSnapshotMetadataJson(document, "https://example.com/static", "https://example.com/static",
                html.length.toLong(), "2026-09-03T10:00:00Z", "text/html")
        )

        assertEquals("Static", json["title"].asText())
        assertEquals(1, json["linkCount"].asInt())
        assertTrue(json.has("interactiveElements"))
    }

    @Test
    fun `double quoted DOM selector receives the single quote hint`() {
        assertTrue(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT DOM_TEXT(DOM) FROM DOM_LOAD_AND_SELECT('https://example.com', \"a\")"
            )
        )
    }

    @Test
    fun `unrelated missing quoted column does not receive the selector hint`() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT \"missing_column\" FROM pages"
            )
        )
    }

    @Test
    fun `single quoted DOM selector does not receive the hint`() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT DOM_TEXT(DOM) FROM DOM_LOAD_AND_SELECT('https://example.com', 'a')"
            )
        )
    }

    @Test
    fun `hex error on DOM_FIRST_FLOAT in WHERE receives the cast hint`() {
        assertTrue(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2HexError,
                "SELECT DOM_BASE_URI(DOM) FROM DOM_LOAD_AND_SELECT(@url, 'body') " +
                    "WHERE DOM_FIRST_FLOAT(DOM, '.price') >= 25.0"
            )
        )
    }

    @Test
    fun `hex error on DOM_FIRST_INTEGER in WHERE receives the cast hint`() {
        assertTrue(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2HexError,
                "SELECT DOM_BASE_URI(DOM) FROM DOM_LOAD_AND_SELECT(@url, 'body') " +
                    "WHERE DOM_FIRST_INTEGER(DOM, '.stock') < 10"
            )
        )
    }

    @Test
    fun `hex error without a DOM_FIRST FLOAT or INTEGER call does not receive the cast hint`() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2HexError,
                "SELECT DOM_FIRST_TEXT(DOM, '.price') FROM DOM_LOAD_AND_SELECT(@url, 'body') " +
                    "WHERE DOM_FIRST_TEXT(DOM, '.price') >= '25.0'"
            )
        )
    }

    @Test
    fun `unrelated error with DOM_FIRST_FLOAT does not receive the cast hint`() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2MissingColumn,
                "SELECT DOM_FIRST_FLOAT(DOM, '.price') FROM DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    fun `DOM_FIRST_IMG with an expr selector is detected`() {
        assertTrue(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img:expr(src^=https://cdn)')) FROM " +
                    "DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    fun `DOM_NTH_IMG and DOM_ALL_IMGS with expr selectors are detected`() {
        assertTrue(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_ABS_SRC(DOM_NTH_IMG(DOM, 'img:expr(data-price > 10)', 2)), " +
                    "DOM_SRCS(DOM_ALL_IMGS(DOM, 'img:expr(alt)')) FROM DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    fun `img selectors without expr are not flagged`() {
        assertFalse(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img.hero')) FROM " +
                    "DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    fun `expr selectors on non-img functions are not flagged`() {
        assertFalse(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_FIRST_ATTR(DOM, 'img:expr(src^=https://cdn)', 'src') FROM " +
                    "DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    // =========================================================================
    // Field extraction: text vs textcontent, raw vs absolute attributes
    // =========================================================================

    private fun booksDoc(): FeaturedDocument {
        // Mirrors books.toscrape.com: a server-side truncated anchor whose full
        // title only lives in the title attribute, plus relative href/src.
        val html = """
            <html><body>
            <article class="product_pod">
              <h3><a href="catalogue/a-light-in-the-attic_1000/index.html"
                     title="A Light in the Attic">A Light in the ...</a></h3>
              <div class="raw">
                Line one
                   Line two
              </div>
              <img src="media/cache/x.jpg" alt="A Light in the Attic">
              <span data-price="51.77">£51.77</span>
            </article>
            </body></html>
        """.trimIndent()
        return FeaturedDocument(org.jsoup.Jsoup.parse(html, "https://books.toscrape.com/"))
    }

    @Test
    fun `text normalizes whitespace while textcontent keeps the raw text nodes`() {
        val doc = booksDoc()
        // text(): jsoup collapses the internal newline/space run.
        assertEquals("Line one Line two", extractSnapshotField(doc, "text", ".raw", null, false))
        // textcontent (wholeText): the raw textContent incl. the newline and
        // indentation, with only the outer margin trimmed.
        val raw = extractSnapshotField(doc, "textcontent", ".raw", null, false)
        assertTrue(raw.contains("Line one\n") && raw.contains("Line two"), "raw textContent should keep the newline: '$raw'")
        assertNotEquals(
            extractSnapshotField(doc, "text", ".raw", null, false),
            raw,
            "text and textcontent must not be the same extraction"
        )
    }

    @Test
    fun `neither text nor textcontent recovers server-truncated text but attr title does`() {
        val doc = booksDoc()
        // The site truncates the title in the HTML source itself, so NO DOM text
        // API can recover it — docs must stop promising otherwise.
        assertEquals("A Light in the ...", extractSnapshotField(doc, "text", "h3 a", null, false))
        assertEquals(
            "A Light in the ...",
            extractSnapshotField(doc, "textcontent", "h3 a", null, false)
        )
        // The working recovery path.
        assertEquals(
            "A Light in the Attic",
            extractSnapshotField(doc, "attr", "h3 a", "title", false)
        )
    }

    @Test
    fun `attr returns the raw relative value by default and absolute when requested`() {
        val doc = booksDoc()
        val raw = extractSnapshotField(doc, "attr", "h3 a", "href", false)
        assertEquals("catalogue/a-light-in-the-attic_1000/index.html", raw)

        val absolute = extractSnapshotField(doc, "attr", "h3 a", "href", true)
        assertEquals("https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html", absolute)

        // img src is resolved the same way.
        assertEquals("media/cache/x.jpg", extractSnapshotField(doc, "attr", "img", "src", false))
        assertEquals(
            "https://books.toscrape.com/media/cache/x.jpg",
            extractSnapshotField(doc, "attr", "img", "src", true)
        )
    }

    @Test
    fun `absolute flag does not blank out non-url attributes`() {
        val doc = booksDoc()
        assertEquals(
            "51.77",
            extractSnapshotField(doc, "attr", "span", "data-price", true),
            "a non-URL attribute must fall back to its raw value under absoluteUrls=true"
        )
        assertEquals(
            "A Light in the Attic",
            extractSnapshotField(doc, "attr", "h3 a", "title", true)
        )
    }

    @Test
    fun `scrape-all honours pagination and absolute urls together`() {
        // Two anchors with relative hrefs on a second doc for list semantics.
        val html = """
            <html><body>
            <a href="a/1.html">one</a>
            <a href="a/2.html">two</a>
            <a href="a/3.html">three</a>
            </body></html>
        """.trimIndent()
        val doc = FeaturedDocument(org.jsoup.Jsoup.parse(html, "https://example.org/list/"))

        assertEquals(listOf("a/1.html", "a/2.html", "a/3.html"),
            extractSnapshotFields(doc, "attr", "a", "href", false, 0, -1))
        assertEquals(listOf("https://example.org/list/a/2.html", "https://example.org/list/a/3.html"),
            extractSnapshotFields(doc, "attr", "a", "href", true, 1, 2))
    }

    @Test
    fun `runtime lz and tp text markers are stripped but vi boxes and page links survive`() {
        // The runtime compute pass writes lz/tp onto the LIVE DOM; serialized
        // reads must not expose them, while the wanted vi annotation and the
        // normalizedURI head link must survive.
        val html = """
            <html><head><title>P</title>
            <link rel="normalizedURI" href="https://books.toscrape.com/">
            </head><body>
            <h3><a href="catalogue/x/index.html" lz="1" tp="st" vi="12,8,200,16">Title</a></h3>
            <p class="price" tp="nm" vi="0,40,60,12">£51.77</p>
            </body></html>
        """.trimIndent()
        val document = org.jsoup.Jsoup.parse(html, "https://books.toscrape.com/")

        stripRuntimeTextMarkers(document)

        assertEquals(0, document.select("[lz]").size, "lz markers must be removed")
        assertEquals(0, document.select("[tp]").size, "tp markers must be removed")
        val anchor = document.selectFirst("h3 a")!!
        assertFalse(anchor.hasAttr("lz"))
        assertFalse(anchor.hasAttr("tp"))
        assertTrue(anchor.hasAttr("vi"), "vi boxes are wanted annotations and must stay")
        assertEquals("Title", anchor.text())
        assertEquals("catalogue/x/index.html", anchor.attr("href"))
        assertNotNull(
            document.selectFirst("link[rel=normalizedURI]"),
            "the normalizedURI page link must survive marker stripping"
        )

        // Extraction output derived from the cleaned tree is free of markers.
        val featured = FeaturedDocument(document)
        val innerHtml = extractSnapshotField(featured, "html", "h3", null, false)
        assertFalse(innerHtml.contains("lz="), "get html output must not leak lz: $innerHtml")
        assertFalse(innerHtml.contains("tp="), "get html output must not leak tp: $innerHtml")
        assertTrue(innerHtml.contains("vi="), "get html output must keep vi boxes: $innerHtml")
    }
}
