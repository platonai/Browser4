package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.dom.FeaturedDocument
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WpsiPageSummaryAlgorithm")
class WpsiPageSummaryAlgorithmTest {

    private fun parse(html: String): FeaturedDocument = FeaturedDocument(Jsoup.parse(html))

    @Test
    @DisplayName("metadata exposes the stable wpsi id and builtin flag")
    fun metadata() {
        assertEquals("wpsi", WpsiPageSummaryAlgorithm.id)
        assertEquals(WpsiPageSummaryAlgorithm.ID, WpsiPageSummaryAlgorithm.id)
        assertTrue(WpsiPageSummaryAlgorithm.builtin)
        assertTrue(WpsiPageSummaryAlgorithm.displayName.isNotBlank())
        assertTrue(WpsiPageSummaryAlgorithm.description.isNotBlank())
    }

    @Test
    @DisplayName("generate delegates to PageSummaryIndexService (empty document)")
    fun delegatesOnEmptyDocument() {
        val doc = parse("<html><body></body></html>")
        val expected = PageSummaryIndexService.generate(doc, "https://empty.com", "")
        val actual = WpsiPageSummaryAlgorithm.generate(PageSummaryInput(doc, "https://empty.com", ""))
        assertEquals(expected, actual)
        assertTrue(actual.contains("type: Empty"))
    }

    @Test
    @DisplayName("generate produces the same WPSI YAML as the service for a structured page")
    fun delegatesOnStructuredPage() {
        val html = """
            <html vi="0,0,1920,1080">
              <head><title>Test Page</title></head>
              <body vi="0,0,1920,1080">
                <header vi="0,0,1920,80">
                  <h1 vi="100,20,800,36" id="main-title">MacBook Pro</h1>
                </header>
                <main vi="0,80,1920,900">
                  <p vi="100,120,600,24" class="price">$1999</p>
                  <button vi="200,200,120,40" id="buy-btn">Buy Now</button>
                </main>
              </body>
            </html>
        """.trimIndent()

        val doc = parse(html)
        val expected = PageSummaryIndexService.generate(doc, "https://example.com/p", "Test Page")
        val actual = WpsiPageSummaryAlgorithm.generate(
            PageSummaryInput(doc, "https://example.com/p", "Test Page")
        )
        assertEquals(expected, actual)
        assertTrue(actual.contains("title: \"Test Page\""))
        assertTrue(actual.contains("tag: header"))
        assertTrue(actual.contains("\"Buy Now\""))
    }
}
