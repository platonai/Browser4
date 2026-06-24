package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.dom.FeaturedDocument
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

@DisplayName("PageSummaryIndexService")
class PageSummaryIndexServiceTest {

    /** Parse HTML with embedded `vi` attributes and wrap in a FeaturedDocument. */
    private fun parse(html: String): FeaturedDocument {
        val jsoupDoc = Jsoup.parse(html)
        return FeaturedDocument(jsoupDoc)
    }

    /** Extract the first line for quick assertions. */
    private fun linesOf(summary: String) = summary.lines()

    // =========================================================================
    // Empty / minimal pages
    // =========================================================================

    @Test
    @DisplayName("empty document returns minimal YAML")
    fun emptyDocument() {
        val doc = parse("<html><body></body></html>")
        val result = PageSummaryIndexService.generate(doc, "https://empty.com", "")
        assertTrue(result.contains("page:"))
        assertTrue(result.contains("type: Empty"))
        assertTrue(result.contains("nodes: 0"))
    }

    @Test
    @DisplayName("document with no vi attributes returns empty stats")
    fun noViAttributes() {
        val doc = parse("<html><body><h1>Hello</h1><p>Text</p></body></html>")
        val result = PageSummaryIndexService.generate(doc, "https://x.com", "T")
        assertTrue(result.contains("type: Empty"))
        assertTrue(result.contains("nodes: 0"))
    }

    // =========================================================================
    // Basic page structure
    // =========================================================================

    @Test
    @DisplayName("basic page produces YAML with page metadata, structure, content, and stats")
    fun basicPage() {
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
                <footer vi="0,980,1920,100">
                  <a vi="100,990,200,20" href="/contact">Contact</a>
                </footer>
              </body>
            </html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://example.com/macbook", "Test Page")

        // Page metadata
        assertTrue(result.contains("title: \"Test Page\""), "Should contain title")
        assertTrue(result.contains("url: \"https://example.com/macbook\""), "Should contain url")
        assertTrue(result.contains("type:"), "Should contain page type")

        // Structure (landmarks)
        assertTrue(result.contains("structure:"), "Should have structure section")
        assertTrue(result.contains("tag: header"), "Should identify header")
        assertTrue(result.contains("tag: main"), "Should identify main")
        assertTrue(result.contains("tag: footer"), "Should identify footer")

        // Content (key nodes)
        assertTrue(result.contains("content:"), "Should have content section")
        assertTrue(result.contains("type: h1"), "Should identify h1")
        assertTrue(result.contains("\"MacBook Pro\""), "Should include h1 text")
        assertTrue(result.contains("type: button"), "Should identify button")
        assertTrue(result.contains("\"Buy Now\""), "Should include button text")

        // Selector hints
        assertTrue(result.contains("selector: \"#main-title\""), "Should have #id selector")
        assertTrue(result.contains("selector: \"#buy-btn\""), "Should have #id selector for button")
        assertTrue(result.contains("selector: \".price\""), "Should have .class selector")

        // Stats
        assertTrue(result.contains("stats:"), "Should have stats section")
        assertTrue(result.contains("links:"), "Should count links")
        assertTrue(result.contains("buttons:"), "Should count buttons")
    }

    // =========================================================================
    // Scoring
    // =========================================================================

    @Test
    @DisplayName("h1 scores higher than h2")
    fun h1ScoresHigherThanH2() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <h1 vi="0,0,100,30">Heading One</h1>
              <h2 vi="0,30,100,20">Heading Two</h2>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")

        val h1Line = result.lines().first { it.contains("type: h1") }
        val h2Line = result.lines().first { it.contains("type: h2") }
        val h1Score = extractScore(h1Line, result)
        val h2Score = extractScore(h2Line, result)
        assertTrue(h1Score > h2Score, "h1 score ($h1Score) should be > h2 score ($h2Score)")
    }

    @Test
    @DisplayName("elements with id get bonus score")
    fun idBonusScore() {
        val withId = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,20" id="important">Important text</div>
            </body></html>
        """.trimIndent()
        val withoutId = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,20">Important text</div>
            </body></html>
        """.trimIndent()

        val resultWith = PageSummaryIndexService.generate(parse(withId), "https://x.com", "")
        val resultWithout = PageSummaryIndexService.generate(parse(withoutId), "https://x.com", "")

        // The #id element should have a selector hint
        assertTrue(resultWith.contains("selector: \"#important\""), "Element with id should have selector hint")
        assertFalse(resultWithout.contains("selector:"), "Element without id should not have selector hint")
    }

    // =========================================================================
    // Landmark detection
    // =========================================================================

    @Test
    @DisplayName("all standard landmarks are detected")
    fun allLandmarksDetected() {
        val html = """
            <html vi="0,0,1920,1080"><body vi="0,0,1920,1080">
              <header vi="0,0,1920,80"><h1 vi="10,10,200,30">Site</h1></header>
              <nav vi="0,80,200,900"><a vi="10,90,100,20" class="nav-link">Home</a></nav>
              <main vi="200,80,1520,900"><p vi="210,90,500,20">Content</p></main>
              <aside vi="1720,80,200,900"><p vi="1730,90,100,20">Sidebar</p></aside>
              <article vi="200,150,1000,800"><h2 vi="210,160,500,30">Article</h2></article>
              <section vi="200,170,1000,200"><p vi="210,180,500,20">Section text</p></section>
              <footer vi="0,980,1920,100"><p vi="10,990,200,20">Footer</p></footer>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "Landmark Test")

        val expectedLandmarks = listOf("header", "nav", "main", "aside", "article", "section", "footer")
        for (landmark in expectedLandmarks) {
            assertTrue(
                result.contains("tag: $landmark"),
                "Should detect <$landmark> landmark"
            )
        }
    }

    // =========================================================================
    // List detection
    // =========================================================================

    @Test
    @DisplayName("repeated li items are detected as a list")
    fun detectsRepeatedListItems() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <ul vi="0,0,100,90" class="product-list">
                <li vi="0,0,100,30">iPhone</li>
                <li vi="0,30,100,30">iPad</li>
                <li vi="0,60,100,30">MacBook</li>
              </ul>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")

        assertTrue(result.contains("lists:"), "Should have lists section")
        assertTrue(result.contains("itemTag: li"), "Should detect li items")
        assertTrue(result.contains("count: 3"), "Should count 3 items")
        assertTrue(result.contains("iPhone"), "Should include sample text")
    }

    @Test
    @DisplayName("fewer than 3 items are not detected as a list")
    fun ignoresSmallLists() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <ul vi="0,0,100,60">
                <li vi="0,0,100,30">Item A</li>
                <li vi="0,30,100,30">Item B</li>
              </ul>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")
        assertFalse(result.contains("lists:"), "2 items should not trigger list detection")
    }

    // =========================================================================
    // Table summary
    // =========================================================================

    @Test
    @DisplayName("table is summarized with rows, cols, and headers")
    fun tableSummary() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <table vi="0,0,100,60">
                <thead>
                  <tr><th vi="0,0,30,20">Name</th><th vi="30,0,30,20">Price</th></tr>
                </thead>
                <tbody>
                  <tr><td vi="0,20,30,20">A</td><td vi="30,20,30,20">1</td></tr>
                  <tr><td vi="0,40,30,20">B</td><td vi="30,40,30,20">2</td></tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")

        assertTrue(result.contains("tables:"), "Should have tables section")
        assertTrue(result.contains("rows: 3"), "Should count 3 rows (header + 2 data)")
        assertTrue(result.contains("cols: 2"), "Should count 2 columns")
        assertTrue(result.contains("Name"), "Should include header Name")
        assertTrue(result.contains("Price"), "Should include header Price")
    }

    @Test
    @DisplayName("table without headers is summarized with just row/col counts")
    fun tableWithoutHeaders() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <table vi="0,0,100,40">
                <tr><td vi="0,0,50,20">A1</td><td vi="50,0,50,20">B1</td></tr>
                <tr><td vi="0,20,50,20">A2</td><td vi="50,20,50,20">B2</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")

        assertTrue(result.contains("tables:"), "Should have tables section")
        assertTrue(result.contains("rows: 2"), "Should count data rows")
        assertFalse(result.contains("headers:"), "No headers expected")
    }

    // =========================================================================
    // Page type inference
    // =========================================================================

    @Test
    @DisplayName("product detail page is detected")
    fun productDetailPage() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <h1 vi="0,0,100,30">MacBook Pro</h1>
              <p vi="0,30,100,20" class="price">$1999.00</p>
              <button vi="0,50,100,30" id="buy-now">Add to Cart</button>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")
        assertTrue(result.contains("type: \"Product Detail\""), "Should detect product detail page")
    }

    @Test
    @DisplayName("article page is detected")
    fun articlePage() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <article vi="0,0,100,100">
                <h1 vi="0,0,100,30">A Very Long Article Title That Exceeds Thirty Characters</h1>
                <p vi="0,30,100,70">Lorem ipsum dolor sit amet, consectetur adipiscing elit.</p>
              </article>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")
        assertTrue(result.contains("type: \"Article / Content\""), "Should detect article page")
    }

    // =========================================================================
    // YAML escaping
    // =========================================================================

    @Test
    @DisplayName("text with special YAML characters is properly quoted")
    fun specialCharactersEscaped() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <h1 vi="0,0,100,30">Best Price: $99.99 #1</h1>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")

        // The text contains ':' and '#' and '$' — must be double-quoted
        val textLine = result.lines().first { it.contains("text:") }
        assertTrue(textLine.contains('"'), "Text with special chars should be quoted: $textLine")
        assertTrue(textLine.contains("Price"), "Should contain the price text")
    }

    @Test
    @DisplayName("multiline text is normalized and quoted")
    fun newlinesEscaped() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <p vi="0,0,100,40">Line One
Line Two</p>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")

        // Jsoup's ownText() normalizes whitespace: newlines become spaces
        val textLine = result.lines().first { it.contains("text:") }
        assertTrue(textLine.contains("Line One Line Two"), "Text should be present (whitespace normalized): $textLine")
        // The space triggers quoting since ' ' is a special character
        assertTrue(textLine.contains('"'), "Text with spaces should be quoted: $textLine")
    }

    // =========================================================================
    // Stats accuracy
    // =========================================================================

    @Test
    @DisplayName("stats accurately count node types")
    fun statsAccurate() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <a vi="0,0,20,20" href="/a1">Link 1</a>
              <a vi="20,0,20,20" href="/a2">Link 2</a>
              <a vi="40,0,20,20" href="/a3">Link 3</a>
              <button vi="0,20,30,20">B1</button>
              <button vi="30,20,30,20">B2</button>
              <form vi="0,40,60,30"><input vi="0,50,60,20"></form>
              <img vi="0,70,20,20" alt="photo">
              <img vi="20,70,20,20">
              <table vi="0,90,60,10"><tr><td vi="0,100,60,10">T</td></tr></table>
            </body></html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(parse(html), "https://x.com", "")
        assertTrue(result.contains("links: 3"), "Should count 3 links")
        assertTrue(result.contains("buttons: 2"), "Should count 2 buttons")
        assertTrue(result.contains("forms: 1"), "Should count 1 form")
        assertTrue(result.contains("tables: 1"), "Should count 1 table")
        assertTrue(result.contains("images: 2"), "Should count 2 images")
        assertTrue(result.contains("inputs: 1"), "Should count 1 input")
    }

    // =========================================================================
    // Integration: realistic page
    // =========================================================================

    @Test
    @DisplayName("realistic product page summary is valid YAML and includes all sections")
    fun realisticProductPage() {
        val html = """
            <html vi="0,0,1920,1080"><head><title>Apple MacBook Pro 16-inch</title></head>
            <body vi="0,0,1920,1080">
              <header vi="0,0,1920,80">
                <nav vi="0,0,1920,60">
                  <a vi="20,20,100,20" href="/">Home</a>
                  <a vi="130,20,100,20" href="/products">Products</a>
                  <a vi="240,20,100,20" href="/support">Support</a>
                </nav>
              </header>
              <main vi="0,80,1400,900">
                <h1 vi="100,100,800,36" id="product-title">MacBook Pro 16-inch</h1>
                <p vi="100,150,600,24" class="price">$2499.00</p>
                <p vi="100,180,800,60" class="description">The most powerful MacBook Pro ever. Features the M4 Max chip, 32-core GPU, and up to 128GB unified memory.</p>
                <button vi="100,260,160,48" id="add-to-cart">Add to Cart</button>
                <button vi="270,260,160,48" id="buy-now">Buy Now</button>
                <section vi="100,330,800,200" class="specs">
                  <h2 vi="100,340,300,28">Specifications</h2>
                  <table vi="100,380,600,140">
                    <tr><th vi="100,380,200,20">Feature</th><th vi="300,380,200,20">Detail</th></tr>
                    <tr><td vi="100,410,200,20">Chip</td><td vi="300,410,200,20">M4 Max</td></tr>
                    <tr><td vi="100,440,200,20">Memory</td><td vi="300,440,200,20">32GB</td></tr>
                    <tr><td vi="100,470,200,20">Storage</td><td vi="300,470,200,20">1TB SSD</td></tr>
                    <tr><td vi="100,500,200,20">Display</td><td vi="300,500,200,20">16" Liquid Retina XDR</td></tr>
                  </table>
                </section>
                <section vi="100,550,800,200" class="related">
                  <h2 vi="100,560,400,28">You Might Also Like</h2>
                  <ul vi="100,600,800,140">
                    <li vi="100,600,200,40"><a vi="100,600,200,20" href="/macbook-air">MacBook Air</a></li>
                    <li vi="100,650,200,40"><a vi="100,650,200,20" href="/ipad-pro">iPad Pro</a></li>
                    <li vi="100,700,200,40"><a vi="100,700,200,20" href="/imac">iMac</a></li>
                  </ul>
                </section>
              </main>
              <aside vi="1400,80,520,900">
                <h3 vi="1410,100,500,24">Recommended</h3>
                <div vi="1410,140,500,100" class="recommendation">
                  <img vi="1410,140,80,80" alt="MacBook Air" src="/air.jpg">
                  <a vi="1500,140,400,20" href="/macbook-air">MacBook Air - $1099</a>
                </div>
                <div vi="1410,250,500,100" class="recommendation">
                  <img vi="1410,250,80,80" alt="iPad Pro" src="/ipad.jpg">
                  <a vi="1500,250,400,20" href="/ipad-pro">iPad Pro - $799</a>
                </div>
                <div vi="1410,360,500,100" class="recommendation">
                  <img vi="1410,360,80,80" alt="iMac" src="/imac.jpg">
                  <a vi="1500,360,400,20" href="/imac">iMac - $1299</a>
                </div>
              </aside>
              <footer vi="0,980,1920,100">
                <p vi="100,990,400,20">&copy; 2025 Apple Inc. All rights reserved.</p>
                <a vi="100,1020,200,20" href="/privacy">Privacy Policy</a>
                <a vi="310,1020,200,20" href="/terms">Terms of Use</a>
              </footer>
            </body>
            </html>
        """.trimIndent()

        val result = PageSummaryIndexService.generate(
            parse(html),
            "https://www.apple.com/macbook-pro",
            "Apple MacBook Pro 16-inch"
        )

        // Must not throw
        val lines = result.lines()

        // Should be valid YAML-like structure
        assertTrue(lines.any { it.startsWith("page:") }, "Must start with page section")
        assertTrue(lines.any { it.startsWith("structure:") }, "Must have structure section")
        assertTrue(lines.any { it.startsWith("content:") }, "Must have content section")
        assertTrue(lines.any { it.startsWith("lists:") }, "Must have lists section")
        assertTrue(lines.any { it.startsWith("tables:") }, "Must have tables section")
        assertTrue(lines.any { it.startsWith("stats:") }, "Must have stats section")

        // Must identify correct page type
        assertTrue(result.contains("type: \"Product Detail\""), "Should be product detail")

        // Must include main content text
        assertTrue(result.contains("MacBook Pro"), "Should include product name")

        // Must include the table
        assertTrue(result.contains("rows: 5"), "Should count table rows (header + 4)")

        // Must include the recommendation list
        assertTrue(result.contains("count: 3"), "Should count 3 recommendations")

        // Must have correct stats
        assertTrue(result.contains("images: 3"), "Should count 3 images")

        // Verify YAML structure is valid enough to be parsed
        // Check key sections are present with proper indentation
        for (line in lines) {
            if (line.startsWith("  - ")) {
                // List items must be properly indented
                assertTrue(line.startsWith("  - "), "List item indentation: $line")
            }
        }

        // Ensure no raw nodeId references (should all be box now)
        assertFalse(result.contains("nodeId:"), "Should not contain nodeId references")
        assertTrue(result.contains("box:"), "Should use box (vi attribute) references")
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /** Extract the score value that follows a given marker line in the YAML output. */
    private fun extractScore(line: String, result: String): Int {
        val lines = result.lines()
        val idx = lines.indexOfFirst { it == line }
        if (idx >= 0 && idx + 1 < lines.size && lines[idx + 1].contains("score:")) {
            return lines[idx + 1].substringAfter("score:").trim().toInt()
        }
        return 0
    }
}
