package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
