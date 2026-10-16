package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.rest.mcp.controller.inspectDocument
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * The enrichments `htmlsnapshot inspect` adds on top of `inspectDocument`:
 *
 *  - `originalMatchCount` — how many elements the **caller's** selector matched, before the analysis
 *    substituted another element set.  Without it a renderer narrated a selector that matched
 *    nothing as "repeats as a sibling group";
 *  - a truncation hint whose `childSelector` really resolves **inside the repeating container**.
 *    The hint used to be a standalone element reference (`a`), which both consumers evaluate
 *    row-scoped: `DOM_FIRST_ATTR(DOM, 'a', 'title')` and `htmlsnapshot get all attr "a" title`
 *    returned the empty string for every row while the tool printed the full value beside them.
 *
 * Pure tests: a plain jsoup document and the payload `inspectDocument` builds from it — no browser,
 * no session.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("html_snapshot inspect enrichment")
class HTMLSnapshotInspectHintsTest {

    private val mapper = pulsarObjectMapper()

    /** Three books.toscrape.com-shaped cards: the label is clipped, the full title is in `title`. */
    private val cardsHtml = """
        <html><body><ol class="row">
        <li><article class="product_pod">
          <div class="image_container"><a href="catalogue/a_1/index.html"><img src="media/a.jpg" alt=""></a></div>
          <h3><a href="catalogue/a_1/index.html" title="A Light in the Attic">A Light in the ...</a></h3>
          <p class="price_color">£51.77</p>
        </article></li>
        <li><article class="product_pod">
          <div class="image_container"><a href="catalogue/b_2/index.html"><img src="media/b.jpg" alt=""></a></div>
          <h3><a href="catalogue/b_2/index.html" title="Tipping the Velvet">Tipping the V...</a></h3>
          <p class="price_color">£53.74</p>
        </article></li>
        <li><article class="product_pod">
          <div class="image_container"><a href="catalogue/c_3/index.html"><img src="media/c.jpg" alt=""></a></div>
          <h3><a href="catalogue/c_3/index.html" title="Soumission">Soumissi...</a></h3>
          <p class="price_color">£50.10</p>
        </article></li>
        </ol></body></html>
    """.trimIndent()

    private fun doc(html: String = cardsHtml): FeaturedDocument =
        FeaturedDocument(Jsoup.parse(html, "https://books.toscrape.com/"))

    private fun inspect(document: FeaturedDocument, selector: String): String =
        inspectDocument(document, selector, 20, 5)

    @Test
    @DisplayName("the truncation hint becomes a container-relative path that really carries the title")
    fun truncationHintBecomesAContainerRelativePath() {
        val document = doc()

        val annotated = mapper.readTree(annotateInspectPayload(document, inspect(document, "article.product_pod")))

        val hints = annotated["samples"][0]["truncationHints"]
        assertNotNull(hints, "the clipped label must produce a truncation hint: $annotated")
        val hint = hints[0]
        assertEquals("title", hint["attribute"].asText())

        val childSelector = hint["childSelector"].asText()
        assertEquals(
            "h3 a",
            childSelector,
            "the hint must name the container-relative path that carries the title, not the standalone 'a'"
        )
        // The pinned property: evaluated inside EVERY card the hint reaches the full title.
        val titles = document.select("article.product_pod").map { card ->
            card.selectFirst(childSelector)?.attr("title").orEmpty()
        }
        assertEquals(listOf("A Light in the Attic", "Tipping the Velvet", "Soumission"), titles)
    }

    @Test
    @DisplayName("a hint the container-relative path cannot support is dropped, never printed")
    fun hintWithoutAUsablePathIsDropped() {
        // Both cards keep the clipped label, but only the first carries the full title — no path can
        // return a non-empty title for every row, so the hint must not survive.
        val html = """
            <html><body>
            <article class="product_pod">
              <div class="image_container"><a href="a.html"><img src="a.jpg" alt=""></a></div>
              <h3><a title="A Light in the Attic">A Light in the ...</a></h3>
            </article>
            <article class="product_pod">
              <div class="image_container"><a href="b.html"><img src="b.jpg" alt=""></a></div>
              <h3><a>Tipping the V...</a></h3>
            </article>
            </body></html>
        """.trimIndent()
        val document = doc(html)

        val annotated = mapper.readTree(annotateInspectPayload(document, inspect(document, "article.product_pod")))

        val hints = annotated["samples"][0]["truncationHints"]
        assertTrue(
            hints == null || hints.isEmpty,
            "a hint whose attribute is empty for another row must be dropped: $annotated"
        )
    }

    @Test
    @DisplayName("originalMatchCount tells a non-matching selector apart from a repeating one")
    fun originalMatchCountReportsTheCallersOwnSelector() {
        val document = doc()

        val missing = mapper.readTree(annotateInspectPayload(document, inspect(document, ".no-such-thing")))
        assertEquals(0, missing["originalMatchCount"].asInt(), "the caller's selector matched nothing")
        assertTrue(missing["autoDiscovered"].asBoolean())
        assertEquals(".no-such-thing", missing["originalSelector"].asText())

        val matching = mapper.readTree(annotateInspectPayload(document, inspect(document, "article.product_pod")))
        assertEquals(3, matching["originalMatchCount"].asInt())
        assertEquals(3, matching["matchCount"].asInt())
    }

    @Test
    @DisplayName("originalMatchCount is 1 for a container whose repeating children were analysed")
    fun originalMatchCountIsOneForAContainer() {
        val document = doc()

        val annotated = mapper.readTree(annotateInspectPayload(document, inspect(document, "ol.row")))

        assertEquals(
            1,
            annotated["originalMatchCount"].asInt(),
            "the caller's container matched exactly once, whatever pattern was analysed instead: $annotated"
        )
    }

    @Test
    @DisplayName("the enriched payload still satisfies the inspect result contract")
    fun enrichedPayloadKeepsTheSchema() {
        val document = doc()

        val payload = annotateInspectPayload(document, inspect(document, "article.product_pod"))
        val spec = HTMLSnapshotToolExecutor(mock<PulsarSessionManager>()).getToolSpecs().getValue("inspect")

        assertEquals(
            emptyList<String>(),
            ToolResultValidator.validate(spec, ToolResultValidator.parse(payload)).map { "${it.path} ${it.message}" },
            "the enrichment must not break the declared contract: $payload"
        )
    }

    @Test
    @DisplayName("a payload the enrichment does not understand is returned untouched")
    fun unexpectedPayloadIsReturnedUntouched() {
        assertEquals("not json", annotateInspectPayload(doc(), "not json"))
        assertEquals("[]", annotateInspectPayload(doc(), "[]"))
        assertNull(mapper.readTree(annotateInspectPayload(doc(), """{"matchCount":0}"""))["originalMatchCount"])
    }
}
