package ai.platon.pulsar.skeleton.workflow.format

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScrapedDocument")
class ScrapedDocumentTest {

    private fun everything() = ScrapedDocument(
        title = "Title",
        description = "Description",
        url = "https://example.com/p",
        markdown = "# body",
        html = "<html>clean</html>",
        rawHtml = "<html>raw</html>",
        rawBase64 = "aGk=",
        links = listOf("https://example.com/a"),
        images = listOf("https://example.com/i.png"),
        screenshot = "/tmp/media/screenshot-1.png",
        audio = "/tmp/media/audio-1.mp3",
        video = "/tmp/media/video-1.mp4",
        videos = listOf(mapOf("url" to "https://example.com/v.mp4")),
        pdf = "/tmp/media/page-1.pdf",
        json = mapOf("k" to "v"),
        summary = "summary",
        answer = "answer",
        highlights = "highlights",
        attributes = listOf(AttributeValue(".price", "data-amount", listOf("10"))),
        changeTracking = mapOf("changeStatus" to "changed"),
        branding = mapOf("logo" to "x"),
        product = mapOf("title" to "p"),
        menu = mapOf("merchant" to "m"),
        warning = "existing warning",
        metadata = ScrapeMetadata(url = "https://example.com/p", scrapeId = "s1"),
    )

    @Test
    @DisplayName("retainRequested drops every field whose format was not requested")
    fun retainRequestedDropsUnrequested() {
        val retained = everything().retainRequested(listOf("markdown"))

        assertEquals("# body", retained.markdown)
        assertNull(retained.html)
        assertNull(retained.rawHtml)
        assertNull(retained.links)
        assertNull(retained.images)
        assertNull(retained.screenshot)
        assertNull(retained.json)
        assertNull(retained.summary)
        assertNull(retained.answer)
        assertNull(retained.highlights)
        assertNull(retained.attributes)
        assertNull(retained.changeTracking)
        assertNull(retained.branding)
        assertNull(retained.audio)
        assertNull(retained.video)
        assertNull(retained.videos)
        assertNull(retained.pdf)
        assertNull(retained.rawBase64)
    }

    @Test
    @DisplayName("the document envelope always survives retention")
    fun envelopeAlwaysSurvives() {
        val retained = everything().retainRequested(listOf("markdown"))

        assertEquals("Title", retained.title)
        assertEquals("Description", retained.description)
        assertEquals("https://example.com/p", retained.url)
        assertEquals("existing warning", retained.warning)
        assertEquals("s1", retained.metadata.scrapeId)
    }

    @Test
    @DisplayName("formats that share a field keep it: deterministicJson keeps json")
    fun sharedFieldKeptForEitherFormat() {
        assertEquals(mapOf("k" to "v"), everything().retainRequested(listOf("deterministicJson")).json)
        assertEquals(mapOf("k" to "v"), everything().retainRequested(listOf("json")).json)
    }

    @Test
    @DisplayName("question keeps answer, query keeps answer and highlights")
    fun answerAndHighlightsMapping() {
        val byQuestion = everything().retainRequested(listOf("question"))
        assertEquals("answer", byQuestion.answer)
        assertNull(byQuestion.highlights)

        val byQuery = everything().retainRequested(listOf("query"))
        assertEquals("answer", byQuery.answer)
        assertEquals("highlights", byQuery.highlights)

        val byHighlights = everything().retainRequested(listOf("highlights"))
        assertNull(byHighlights.answer)
        assertEquals("highlights", byHighlights.highlights)
    }

    @Test
    @DisplayName("video keeps both the downloaded file and the discovered list")
    fun videoKeepsBothFields() {
        val retained = everything().retainRequested(listOf("video"))
        assertEquals("/tmp/media/video-1.mp4", retained.video)
        assertEquals(1, retained.videos?.size)
    }

    @Test
    @DisplayName("an unknown format keeps no field at all")
    fun unknownFormatKeepsNothing() {
        val retained = everything().retainRequested(listOf("markdwon"))
        assertNull(retained.markdown)
        assertNull(retained.links)
        assertEquals("Title", retained.title)
    }

    @Test
    @DisplayName("deliveredFormats reports the requested formats that produced output")
    fun deliveredFormats() {
        val document = ScrapedDocument(
            markdown = "# body",
            links = emptyList(),
            screenshot = "/tmp/s.png",
        )
        val requested = listOf("markdown", "links", "screenshot", "images")

        // `images` was requested and produced nothing, so it is not delivered.
        assertEquals(listOf("markdown", "links", "screenshot"), document.deliveredFormats(requested))
        assertTrue(ScrapedDocument().deliveredFormats(listOf("markdown")).isEmpty())
        // A format that was not requested is never reported, even when its field
        // happens to carry a value.
        assertTrue(document.deliveredFormats(listOf("links")) == listOf("links"))
    }

    @Test
    @DisplayName("deliveredFormats resolves formats that share one document field")
    fun deliveredFormatsResolvesSharedFields() {
        val asJson = ScrapedDocument(json = mapOf("a" to 1))
        val asText = ScrapedDocument(highlights = "quoted")

        // `json` and `deterministicJson` write the same field; only the one that
        // was asked for is reported.
        assertEquals(listOf("json"), asJson.deliveredFormats(listOf("json")))
        assertEquals(listOf("deterministicJson"), asJson.deliveredFormats(listOf("deterministicJson")))
        // The deprecated `query` shares `highlights` with its replacement, so it
        // must not be reported even when it was explicitly requested alongside it.
        assertEquals(listOf("highlights"), asText.deliveredFormats(listOf("highlights")))
        assertTrue(asText.deliveredFormats(listOf("query")).isEmpty())
        assertTrue(asText.deliveredFormats(listOf("query", "highlights")) == listOf("highlights"))
    }

    @Test
    @DisplayName("withDeliveredFormats stamps the metadata so degradation is observable")
    fun withDeliveredFormatsStampsMetadata() {
        val stamped = ScrapedDocument(markdown = "# body").withDeliveredFormats(listOf("markdown", "links"))

        assertEquals(listOf("markdown"), stamped.metadata.formatsDelivered)
    }

    @Test
    @DisplayName("withWarning appends notes and ignores blanks")
    fun withWarningAppends() {
        val one = ScrapedDocument().withWarning("first")
        assertEquals("first", one.warning)

        val two = one.withWarning("second")
        assertEquals("first second", two.warning)

        assertEquals("first", one.withWarning("  ").warning)
        assertEquals("first", one.withWarning(null).warning)
    }

    @Test
    @DisplayName("null fields are omitted from JSON: an unrequested format is absent")
    fun nullFieldsAreOmittedFromJson() {
        val json = jacksonObjectMapper().writeValueAsString(ScrapedDocument(markdown = "# body"))

        assertTrue(json.contains("\"markdown\""), json)
        assertFalse(json.contains("\"links\""), json)
        assertFalse(json.contains("\"html\""), json)
        assertFalse(json.contains("\"warning\""), json)
        assertTrue(json.contains("\"metadata\""), json)
    }

    @Test
    @DisplayName("withContributedField writes the fields a contributor may own")
    fun withContributedFieldWritesContributorFields() {
        val profile = mapOf("logo" to "l.svg")
        val document = ScrapedDocument()
            .withContributedField("branding", profile)
            .withContributedField("product", profile)
            .withContributedField("menu", profile)
            .withContributedField("highlights", "quoted text")

        assertEquals(profile, document.branding)
        assertEquals(profile, document.product)
        assertEquals(profile, document.menu)
        assertEquals("quoted text", document.highlights)
        // The contributed fields are exactly what PageFormats reserves for them.
        assertEquals(
            listOf("branding", "highlights", "menu", "product"),
            document.deliveredFormats(listOf("branding", "product", "menu", "highlights")).sorted(),
        )
    }

    @Test
    @DisplayName("withContributedField refuses a core field and a null value")
    fun withContributedFieldRefusesForeignFields() {
        val document = ScrapedDocument(markdown = "# body")

        // `markdown` belongs to its own provider: a contributor writing it would
        // silently replace a core format's output.
        assertSame(document, document.withContributedField("markdown", "hijacked"))
        assertSame(document, document.withContributedField("summary", "hijacked"))
        // Nothing to write is not a write.
        assertSame(document, document.withContributedField("branding", null))
        assertEquals("# body", document.markdown)
        assertNull(document.branding)
    }

    @Test
    @DisplayName("a contributed field is rendered when the contributor returns another type")
    fun withContributedFieldRendersNonStringHighlights() {
        val document = ScrapedDocument().withContributedField("highlights", listOf("a", "b"))

        assertEquals("[a, b]", document.highlights)
    }
}
