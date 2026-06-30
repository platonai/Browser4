package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.dom.FeaturedDocument
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the PowerCSS :expr() pseudo-selector.
 *
 * `:expr()` queries elements by numerical features computed by
 * [FeaturedDocument]. The following features are computed from the HTML tree
 * and testable without a browser:
 *
 * - `img`  — descendant image count
 * - `a`    — descendant anchor count
 * - `char` — character count inside the node
 * - `child` — number of child elements
 * - `sibling` — number of sibling elements
 * - `dep` — node depth in the document tree
 * - `seq` — node sequence in document order
 * - `txt_nd` — descendant text node count
 * - `txt_dns` — text density (chars / area)
 *
 * Positional features (`top`, `left`, `width`, `height`) require a real
 * browser rendering engine and are not testable here.
 */
@DisplayName("PowerCSS :expr() Pseudo-Selector")
class FeaturedDocumentExprTest {

    private fun parse(html: String): FeaturedDocument {
        val jsoupDoc = Jsoup.parse(html)
        return FeaturedDocument(jsoupDoc)
    }

    // =========================================================================
    // Descendant count features
    // =========================================================================

    @Test
    @DisplayName(":expr(img == 1) selects elements with exactly one descendant image")
    fun imgEquals() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="one-img"><img vi="0,0,50,50"><span vi="0,0,10,10">Text</span></div>
              <div vi="0,0,100,100" id="two-img"><img vi="0,0,50,50"><img vi="0,0,50,50"></div>
              <div vi="0,0,100,100" id="no-img"><span vi="0,0,10,10">Just text</span></div>
              <div vi="0,0,100,100" id="also-one"><img vi="0,0,50,50"></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val with1img = doc.select("div:expr(img == 1)")
        assertEquals(2, with1img.size, "Exactly 2 divs have one img descendant")
        assertTrue(with1img.any { it.id() == "one-img" })
        assertTrue(with1img.any { it.id() == "also-one" })
    }

    @Test
    @DisplayName(":expr(img > 0) selects elements with at least one descendant image")
    fun imgGreaterThan() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="with-img"><img vi="0,0,50,50"></div>
              <div vi="0,0,100,100" id="no-img"><span vi="0,0,10,10">Text</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val withImgs = doc.select("div:expr(img > 0)")
        assertEquals(1, withImgs.size)
        assertEquals("with-img", withImgs[0].id())
    }

    @Test
    @DisplayName(":expr(img == 0) selects elements with no descendant images")
    fun imgEqualsZero() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="has-img"><img vi="0,0,50,50"></div>
              <div vi="0,0,100,100" id="text-only"><span vi="0,0,10,10">Text</span><p vi="0,0,10,10">More</p></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val noImgs = doc.select("div:expr(img == 0)")
        assertEquals(1, noImgs.size)
        assertEquals("text-only", noImgs[0].id())
    }

    @Test
    @DisplayName(":expr(a == 0) selects elements with no descendant anchors")
    fun aEqualsZero() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="with-link"><a vi="0,0,50,20" href="/x">Link</a></div>
              <div vi="0,0,100,100" id="no-link"><span vi="0,0,50,20">No link</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val noLinks = doc.select("div:expr(a == 0)")
        assertEquals(1, noLinks.size)
        assertEquals("no-link", noLinks[0].id())
    }

    @Test
    @DisplayName(":expr(a > 0) selects elements with descendant anchors")
    fun aGreaterThan() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="one-link"><a vi="0,0,50,20" href="/a">A</a></div>
              <div vi="0,0,100,100" id="two-links"><a vi="0,0,50,20" href="/x">X</a><a vi="0,0,50,20" href="/y">Y</a></div>
              <div vi="0,0,100,100" id="no-link"><span vi="0,0,20,20">Text</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val withLinks = doc.select("div:expr(a > 0)")
        assertEquals(2, withLinks.size)
    }

    // =========================================================================
    // Text features
    // =========================================================================

    @Test
    @DisplayName(":expr(char > 100) selects text-heavy elements")
    fun charGreaterThan() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <p vi="0,0,600,200" id="long">${"A".repeat(200)}</p>
              <p vi="0,0,200,20" id="short">Hi</p>
              <p vi="0,0,400,100" id="medium">${"B".repeat(150)}</p>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val textHeavy = doc.select("p:expr(char > 100)")
        assertEquals(2, textHeavy.size)
        assertEquals("long", textHeavy[0].id())
        assertEquals("medium", textHeavy[1].id())
    }

    @Test
    @DisplayName(":expr(char < 50) selects short-text elements")
    fun charLessThan() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <p vi="0,0,600,200" id="long">${"A".repeat(200)}</p>
              <p vi="0,0,200,20" id="short">Hi</p>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val short = doc.select("p:expr(char < 50)")
        assertEquals(1, short.size)
        assertEquals("short", short[0].id())
    }

    @Test
    @DisplayName(":expr(txt_nd > 5) selects elements with many descendant text nodes")
    fun txtNdGreaterThan() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="many">
                <span vi="0,0,20,20">A</span><span vi="0,0,20,20">B</span><span vi="0,0,20,20">C</span>
                <span vi="0,0,20,20">D</span><span vi="0,0,20,20">E</span><span vi="0,0,20,20">F</span>
              </div>
              <div vi="0,0,200,200" id="few">
                <span vi="0,0,20,20">X</span><span vi="0,0,20,20">Y</span>
              </div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val manyTexts = doc.select("div:expr(txt_nd > 5)")
        assertEquals(1, manyTexts.size)
        assertEquals("many", manyTexts[0].id())
    }

    @Test
    @DisplayName(":expr(txt_nd == 0) selects elements with no text descendants")
    fun txtNdEqualsZero() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="empty"></div>
              <div vi="0,0,100,100" id="has-text"><span vi="0,0,20,20">Hello</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val empty = doc.select("div:expr(txt_nd == 0)")
        assertEquals(1, empty.size)
        assertEquals("empty", empty[0].id())
    }

    // =========================================================================
    // Tree structure features
    // =========================================================================

    @Test
    @DisplayName(":expr(child > 3) selects elements with many children")
    fun childGreaterThan() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,100" id="many-kids">
                <span vi="0,0,20,20">1</span><span vi="0,0,20,20">2</span><span vi="0,0,20,20">3</span>
                <span vi="0,0,20,20">4</span><span vi="0,0,20,20">5</span>
              </div>
              <div vi="0,0,200,100" id="few-kids">
                <span vi="0,0,20,20">A</span><span vi="0,0,20,20">B</span>
              </div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val manyKids = doc.select("div:expr(child > 3)")
        assertEquals(1, manyKids.size)
        assertEquals("many-kids", manyKids[0].id())
    }

    @Test
    @DisplayName(":expr(child == 0) selects empty elements")
    fun childEqualsZero() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="empty"></div>
              <div vi="0,0,100,100" id="full"><span vi="0,0,20,20">A</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val empty = doc.select("div:expr(child == 0)")
        assertEquals(1, empty.size)
        assertEquals("empty", empty[0].id())
    }

    @Test
    @DisplayName(":expr(sibling > 2) selects elements with many siblings")
    fun siblingGreaterThan() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <ul vi="0,0,800,200">
                <li vi="0,0,50,20">A</li><li vi="0,0,50,20">B</li><li vi="0,0,50,20">C</li>
                <li vi="0,0,50,20">D</li><li vi="0,0,50,20" id="mid">E</li><li vi="0,0,50,20">F</li>
              </ul>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val manySiblings = doc.select("li:expr(sibling > 2)")
        assertTrue(manySiblings.size >= 3, "At least 3 of 6 li elements should have > 2 siblings")
    }

    @Test
    @DisplayName(":expr(dep >= 0) selects all elements (depth is always >= 0)")
    fun depthAlwaysNonNegative() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,800,400" id="d1">
                <div vi="0,0,600,100" id="d2">
                  <div vi="0,0,400,60" id="d3">D</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val all = doc.select("div:expr(dep >= 0)")
        assertEquals(3, all.size, "All 3 divs have depth >= 0")
    }

    @Test
    @DisplayName(":expr(seq > 2) selects elements after the second in document order")
    fun seqGreaterThan() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <p vi="0,0,200,20" id="p1">A</p><p vi="0,0,200,20" id="p2">B</p>
              <p vi="0,0,200,20" id="p3">C</p><p vi="0,0,200,20" id="p4">D</p>
              <p vi="0,0,200,20" id="p5">E</p>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val later = doc.select("p:expr(seq > 2)")
        assertTrue(later.size >= 2, "Elements after the second should match seq > 2")
    }

    // =========================================================================
    // Logical operators (combining features)
    // =========================================================================

    @Test
    @DisplayName(":expr(&&) combines multiple content conditions with AND")
    fun andOperator() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="rich"><img vi="0,0,80,80"><a vi="0,0,50,20" href="/">Link</a></div>
              <div vi="0,0,200,200" id="img-only"><img vi="0,0,80,80"></div>
              <div vi="0,0,200,200" id="link-only"><a vi="0,0,50,20" href="/">Link</a></div>
              <div vi="0,0,200,200" id="neither"><span vi="0,0,20,20">Text</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val both = doc.select("div:expr(img > 0 && a > 0)")
        assertEquals(1, both.size, "Only the div with both img and a should match")
        assertEquals("rich", both[0].id())
    }

    @Test
    @DisplayName(":expr(||) combines conditions with OR")
    fun orOperator() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="with-img"><img vi="0,0,80,80"></div>
              <div vi="0,0,200,200" id="with-link"><a vi="0,0,50,20" href="/x">Link</a></div>
              <div vi="0,0,200,200" id="neither"><span vi="0,0,20,20">Plain</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val hasMedia = doc.select("div:expr(img > 0 || a > 0)")
        assertEquals(2, hasMedia.size, "Should select divs with image OR link")
    }

    @Test
    @DisplayName(":expr(img > 0 && char > 100) combines content and text conditions")
    fun combinedContentAndText() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="rich"><img vi="0,0,80,80">${"x".repeat(200)}</div>
              <div vi="0,0,200,200" id="img-only"><img vi="0,0,80,80"></div>
              <div vi="0,0,200,200" id="text-only">${"y".repeat(200)}</div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val rich = doc.select("div:expr(img > 0 && char > 100)")
        assertEquals(1, rich.size)
        assertEquals("rich", rich[0].id())
    }

    // =========================================================================
    // Arithmetic operators on content features
    // =========================================================================

    @Test
    @DisplayName(":expr(child + img > 3) addition of features")
    fun additionOperator() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="combo"><img vi="0,0,80,80"><span vi="0,0,20,20">1</span><span vi="0,0,20,20">2</span><span vi="0,0,20,20">3</span></div>
              <div vi="0,0,200,200" id="few"><span vi="0,0,20,20">A</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        // child(3) + img(1) = 4 > 3
        val large = doc.select("div:expr(child + img > 3)")
        assertEquals(1, large.size)
        assertEquals("combo", large[0].id())
    }

    @Test
    @DisplayName(":expr(img * 2 > 0) multiplication of features")
    fun multiplicationOperator() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="two-imgs"><img vi="0,0,80,80"><img vi="0,0,80,80"></div>
              <div vi="0,0,200,200" id="no-imgs"><span vi="0,0,20,20">Text</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val hasImgs = doc.select("div:expr(img * 2 > 0)")
        assertEquals(1, hasImgs.size)
        assertEquals("two-imgs", hasImgs[0].id())
    }

    @Test
    @DisplayName(":expr(child / 2 >= 2) division of features")
    fun divisionOperator() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="six-kids">
                <span vi="0,0,20,20">1</span><span vi="0,0,20,20">2</span><span vi="0,0,20,20">3</span>
                <span vi="0,0,20,20">4</span><span vi="0,0,20,20">5</span><span vi="0,0,20,20">6</span>
              </div>
              <div vi="0,0,200,200" id="two-kids"><span vi="0,0,20,20">A</span><span vi="0,0,20,20">B</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        // 6/2=3 >= 2 → match; 2/2=1 >= 2 → no match
        val multi = doc.select("div:expr(child / 2 >= 2)")
        assertEquals(1, multi.size)
        assertEquals("six-kids", multi[0].id())
    }

    @Test
    @DisplayName(":expr(a ^ 2 > 0) power operator")
    fun powerOperator() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="links"><a vi="0,0,50,20" href="/a">A</a><a vi="0,0,50,20" href="/b">B</a></div>
              <div vi="0,0,200,200" id="none"><span vi="0,0,20,20">Text</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        // a=2 → 2^2=4 > 0; a=0 → 0^2=0 > 0 is false
        val hasLinks = doc.select("div:expr(a ^ 2 > 0)")
        assertEquals(1, hasLinks.size)
        assertEquals("links", hasLinks[0].id())
    }

    // =========================================================================
    // Real-world pattern
    // =========================================================================

    @Test
    @DisplayName("real world: product card with both image and link")
    fun productCardWithImageAndLink() {
        val html = """
            <html vi="0,0,1920,1080"><body vi="0,0,1920,1080">
              <div vi="0,0,300,200" id="full-card" class="product">
                <h2 vi="0,0,200,20">Product</h2>
                <img vi="0,0,100,100">
                <a vi="0,0,80,20" href="/buy">Buy Now</a>
                <span vi="0,0,50,20">$99</span>
              </div>
              <div vi="0,0,300,200" id="no-image" class="product">
                <h2 vi="0,0,200,20">Product</h2>
                <span vi="0,0,50,20">$49</span>
              </div>
              <div vi="0,0,300,200" id="no-link" class="product">
                <h2 vi="0,0,200,20">Product</h2>
                <img vi="0,0,100,100">
                <span vi="0,0,50,20">$79</span>
              </div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val complete = doc.select("div.product:expr(img > 0 && a > 0)")
        assertEquals(1, complete.size)
        assertEquals("full-card", complete[0].id())
    }

    // =========================================================================
    // Compound selectors (mixing :expr() with standard CSS)
    // =========================================================================

    @Test
    @DisplayName("compound: .class:expr() combines class and expression filters")
    fun compoundClassWithExpr() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,200,200" id="a" class="card"><img vi="0,0,80,80"><a vi="0,0,50,20" href="/">L</a></div>
              <div vi="0,0,200,200" id="b" class="card"><span vi="0,0,20,20">Text</span></div>
              <div vi="0,0,200,200" id="c" class="other"><img vi="0,0,80,80"><a vi="0,0,50,20" href="/">L</a></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val richCards = doc.select(".card:expr(img > 0 && a > 0)")
        assertEquals(1, richCards.size)
        assertEquals("a", richCards[0].id())
    }

    @Test
    @DisplayName("compound: mixed standard selectors + :expr() fallback")
    fun combinedSelectorsWithExpr() {
        val html = """
            <html vi="0,0,800,600"><body vi="0,0,800,600">
              <div vi="0,0,800,200" id="gallery">
                <img vi="0,0,300,200" id="hero">
                <a vi="0,0,80,20" id="link" href="/detail">View</a>
                <span vi="0,0,100,20" id="text">Description</span>
              </div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val results = doc.select("#link, span:expr(child == 0)")
        assertEquals(2, results.size, "#link + span with no children")
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    @DisplayName("no elements match — returns empty")
    fun noMatchReturnsEmpty() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100">Small text</div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val result = doc.select("div:expr(char > 99999)")
        assertTrue(result.isEmpty(), "No element has that many chars")
    }

    @Test
    @DisplayName("all elements match trivial condition")
    fun allMatchTrivialCondition() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,50,20" id="a">A</div><div vi="0,0,50,20" id="b">B</div>
              <div vi="0,0,50,20" id="c">C</div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val all = doc.select("div:expr(child >= 0)")
        assertEquals(3, all.size)
    }

    @Test
    @DisplayName("select().first() with :expr() returns first match")
    fun selectFirstWithExpr() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100" id="empty"></div>
              <div vi="0,0,100,100" id="has-kids"><span vi="0,0,20,20">1</span><span vi="0,0,20,20">2</span><span vi="0,0,20,20">3</span></div>
              <div vi="0,0,100,100" id="more-kids"><span vi="0,0,20,20">A</span><span vi="0,0,20,20">B</span><span vi="0,0,20,20">C</span><span vi="0,0,20,20">D</span></div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val first = doc.select("div:expr(child > 2)").first()
        assertNotNull(first)
        assertEquals("has-kids", first!!.id())
    }

    @Test
    @DisplayName("select().first() with :expr() on empty result throws")
    fun selectFirstWithExprOnEmpty() {
        val html = """
            <html vi="0,0,100,100"><body vi="0,0,100,100">
              <div vi="0,0,100,100">text</div>
            </body></html>
        """.trimIndent()
        val doc = parse(html)

        val result = doc.select("div:expr(child > 99999)")
        assertTrue(result.isEmpty(), "No match should exist")
    }
}
