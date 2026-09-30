package ai.platon.pulsar.skeleton.workflow.parse.html

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ReadabilityExtractor] — pure jsoup, no browser required.
 */
@DisplayName("ReadabilityExtractor — heuristic article extraction")
class ReadabilityExtractorTest {

    private val extractor = ReadabilityExtractor()

    private fun parse(html: String) = Jsoup.parse(html)

    /** A realistic noisy article page: nav + sidebar + footer around an article. */
    private fun noisyArticlePage(): String = """
        <html><head>
          <title>How Rust Conquered the Kernel</title>
          <meta name="author" content="Ada Lovelace">
          <meta property="og:site_name" content="Systems Weekly">
          <meta name="description" content="A deep dive into Rust in the Linux kernel.">
        </head><body>
          <nav id="main-nav">
            <a href="/">Home</a> <a href="/news">News</a> <a href="/about">About</a>
            <a href="/contact">Contact</a> <a href="/jobs">Jobs</a>
          </nav>
          <div id="content">
            <article class="post">
              <h1>How Rust Conquered the Kernel</h1>
              <p>Rust brings memory safety to the Linux kernel without sacrificing performance.
                 This article explores the history, the technical design, and the community effort
                 behind the largest incremental rewrite in kernel history.</p>
              <p>The first Rust code landed in Linux 6.1, guarded by a strict configuration flag.
                 Since then, device drivers, filesystems, and networking components have all begun
                 migrating to safe abstractions that eliminate entire classes of vulnerabilities.</p>
              <p>Critics point to the learning curve and the difficulty of auditing unsafe blocks.
                 Proponents counter that the ecosystem tooling — cargo, clippy, and the borrow
                 checker — catches whole bug families at compile time rather than at runtime.</p>
              <p>Whatever the outcome, the experiment has already changed how the industry talks
                 about memory safety and has pushed every major operating system to revisit its
                 approach to systems programming.</p>
            </article>
            <aside class="sidebar">
              <p>Subscribe to our newsletter for weekly updates on systems programming and
                 kernel development. We publish every Tuesday and Thursday.</p>
              <p>Check our sponsors page for companies hiring Rust engineers across Europe
                 and North America with competitive salaries and remote options.</p>
            </aside>
          </div>
          <footer class="footer">
            <p>Copyright 2026 Systems Weekly. All rights reserved worldwide for all content
               published on this website and its affiliated properties.</p>
          </footer>
        </body></html>
    """.trimIndent()

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    @DisplayName("extracts the article from a noisy page")
    fun extractsArticleFromNoisyPage() {
        val result = extractor.extract(parse(noisyArticlePage()))!!

        assertEquals("How Rust Conquered the Kernel", result.title)
        assertEquals("Ada Lovelace", result.byline)
        assertEquals("Systems Weekly", result.siteName)
        assertEquals("A deep dive into Rust in the Linux kernel.", result.excerpt)
        assertTrue(result.content.contains("Rust brings memory safety"))
        assertTrue(result.content.contains("Critics point to the learning curve"))
        assertTrue(result.textContent.contains("whatever the outcome", ignoreCase = true))
        assertTrue(result.length > 500)
        // Not the whole page: sidebar and footer should be excluded.
        assertFalse(result.content.contains("Subscribe to our newsletter"))
        assertFalse(result.content.contains("Copyright 2026"))
        assertTrue(result.confidence in 0.0..1.0)
    }

    @Test
    @DisplayName("prefers the article container over a link farm navigation")
    fun linkFarmDoesNotWin() {
        val html = """
            <html><body>
              <nav>
                <p><a href="/a">Category Alpha — long link list entry number one</a></p>
                <p><a href="/b">Category Beta — long link list entry number two</a></p>
                <p><a href="/c">Category Gamma — long link list entry number three</a></p>
                <p><a href="/d">Category Delta — long link list entry number four</a></p>
              </nav>
              <article>
                <h1>Real Story</h1>
                <p>This is the actual article body with substantial text about a topic that
                   deserves a full-length treatment and careful analysis of every detail
                   presented in an engaging narrative style for the reader.</p>
                <p>The second paragraph continues the story with more context and background
                   information that readers will find genuinely useful and interesting,
                   including interviews with the people involved in the events described.</p>
                <p>The third paragraph wraps up the narrative with conclusions and lessons
                   learned from the entire experience described above in detail, and looks
                   ahead to what the future might hold for the protagonists of the story.</p>
                <p>A final paragraph adds depth by revisiting the key arguments and offering
                   a balanced perspective on the controversy that surrounded the whole affair
                   from its very beginning to the eventual resolution we all witnessed.</p>
              </article>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(parse(html))!!
        assertTrue(result.content.contains("Real Story"))
        assertTrue(result.content.contains("actual article body"))
        assertFalse(result.content.contains("Category Alpha"))
    }

    // =========================================================================
    // Negative cases
    // =========================================================================

    @Test
    @DisplayName("returns null for pages with too little text")
    fun rejectsTooShortPage() {
        val html = """
            <html><body>
              <p>Short page with only a few words and not much else here.</p>
            </body></html>
        """.trimIndent()
        assertNull(extractor.extract(parse(html)))
    }

    @Test
    @DisplayName("returns null for pages without any paragraphs")
    fun rejectsEmptyPage() {
        val html = "<html><body><div>just a header</div></body></html>"
        assertNull(extractor.extract(parse(html)))
    }

    // =========================================================================
    // Sanitization
    // =========================================================================

    @Test
    @DisplayName("removes small noise widgets inside the article")
    fun removesNoiseInsideArticle() {
        val html = """
            <html><body>
              <article>
                <h1>Main Story</h1>
                <p>The main story contains enough text to be clearly readable and worth
                   extracting as the primary content of this particular web page, with
                   several paragraphs that describe what happened in proper detail.</p>
                <div class="share-widget">
                  <a href="/share/facebook">Share on Facebook</a>
                  <a href="/share/twitter">Share on Twitter</a>
                </div>
                <p>The story continues with additional paragraphs that describe the events
                   and the people involved in enough detail to satisfy any curious reader,
                   and it includes quotes from witnesses and official statements alike.</p>
                <div class="related-articles">
                  <p>Related articles are usually just link lists with short titles that
                     should never be part of the main article extraction result.</p>
                </div>
                <p>The final paragraphs wrap up the narrative arc and draw the lessons from
                   the whole episode, offering the reader a clear takeaway and a sense of
                   closure after the long investigation that preceded this publication.</p>
              </article>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(parse(html))!!
        assertFalse(result.content.contains("share-widget"))
        assertFalse(result.content.contains("Share on Facebook"))
        assertFalse(result.content.contains("related-articles"))
        assertTrue(result.content.contains("Main Story"))
    }

    @Test
    @DisplayName("strips classes by default and keeps them with keepClasses")
    fun classStripping() {
        val html = """
            <html><body>
              <article class="post-content">
                <h1 class="entry-title">Styled Article</h1>
                <p class="paragraph-body">This paragraph carries a class attribute that the
                   extractor should strip by default to produce clean semantic HTML without
                   any styling hooks or framework-specific class names left behind.</p>
                <p>This second paragraph is long enough to make the article clearly readable
                   and definitely above the minimum character threshold for extraction, so
                   that the whole article container is selected as the main content region.</p>
                <p>A third paragraph rounds out the article and adds further substance to the
                   page so that the text density of the container comfortably exceeds the
                   default threshold used by the extractor for candidate selection.</p>
              </article>
            </body></html>
        """.trimIndent()

        val stripped = extractor.extract(parse(html))!!
        assertFalse(stripped.content.contains("post-content"))
        assertFalse(stripped.content.contains("entry-title"))
        assertFalse(stripped.content.contains("paragraph-body"))

        val kept = ReadabilityExtractor(ReadabilityOptions(keepClasses = true)).extract(parse(html))!!
        assertTrue(kept.content.contains("post-content"))
        assertTrue(kept.content.contains("entry-title"))
    }

    @Test
    @DisplayName("classesToPreserve survives class stripping")
    fun preservesConfiguredClasses() {
        val html = """
            <html><body>
              <article>
                <h1>Code Heavy Article</h1>
                <pre class="language-rust"><code>fn main() { println!(); }</code></pre>
                <p>This article contains code samples and needs its highlighting class
                   preserved so that the markdown conversion keeps language hints, and
                   the surrounding prose describes each snippet in enough detail that
                   the page reads like a proper technical tutorial for beginners.</p>
                <p>Additional paragraphs explain the compiler errors, the borrow checker,
                   and the ownership model, giving the article enough overall length to
                   be selected confidently as the main content of this tutorial page.</p>
              </article>
            </body></html>
        """.trimIndent()

        val result = ReadabilityExtractor(
            ReadabilityOptions(classesToPreserve = setOf("language-rust")),
        ).extract(parse(html))!!
        assertTrue(result.content.contains("language-rust"))
    }

    // =========================================================================
    // Metadata fallbacks
    // =========================================================================

    @Test
    @DisplayName("falls back to the article H1 when the title tag is missing")
    fun titleFallsBackToH1() {
        val html = """
            <html><body>
              <article>
                <h1>Untitled Page Title</h1>
                <p>Enough prose here to make this page clearly readable and comfortably
                   above the minimum threshold required for article extraction, with a
                   generous amount of detail and context spread across several sections.</p>
                <p>More prose follows to ensure the candidate has a solid text density
                   score and would be selected as the primary content container, including
                   further elaboration of the topic and some historical background notes.</p>
                <p>The final paragraph brings the discussion to a natural conclusion with
                   a summary of the arguments and a brief outlook on future developments
                   that the reader may want to follow up on after finishing this article.</p>
              </article>
            </body></html>
        """.trimIndent()
        val result = extractor.extract(parse(html))!!
        assertEquals("Untitled Page Title", result.title)
    }

    // =========================================================================
    // Quick check
    // =========================================================================

    @Test
    @DisplayName("isProbablyReaderable distinguishes text-rich from text-poor pages")
    fun probablyReaderable() {
        val longBody = buildString {
            repeat(30) { i -> append("<p>Paragraph number $i with a decent amount of text to read.</p>") }
        }
        assertTrue(extractor.isProbablyReaderable(parse("<html><body>$longBody</body></html>")))
        assertFalse(extractor.isProbablyReaderable(parse("<html><body><p>tiny</p></body></html>")))
    }
}
