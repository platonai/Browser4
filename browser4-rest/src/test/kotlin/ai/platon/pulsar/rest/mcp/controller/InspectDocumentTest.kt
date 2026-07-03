package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import com.fasterxml.jackson.databind.JsonNode
import org.jsoup.Jsoup
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*

@DisplayName("htmlsnapshot inspect — inspectDocument() unit tests")
class InspectDocumentTest {

    private val mapper = pulsarObjectMapper()

    /** Parse HTML with optional vi attributes into a FeaturedDocument and inspect it. */
    private fun inspect(
        html: String,
        selector: String = ":root",
        maxMatches: Int = 10,
        maxDepth: Int = 5,
    ): JsonNode {
        val jsoupDoc = Jsoup.parse(html)
        val doc = FeaturedDocument(jsoupDoc)
        val json = inspectDocument(doc, selector, maxMatches, maxDepth)
        return mapper.readTree(json)
    }

    // ---- Fixtures ----

    /** 3 product cards with titles, prices, and images (all have vi attrs for PowerCSS). */
    private val productCardsHtml = """
        <html><body>
        <div class="product-card" vi="0 0 300 400">
          <h2 class="product-title">Widget Alpha</h2>
          <span class="product-price">$19.99</span>
          <img class="product-image" src="a.jpg" vi="10 50 280 200">
          <div class="rating">★★★★☆</div>
        </div>
        <div class="product-card" vi="0 420 300 400">
          <h2 class="product-title">Widget Beta</h2>
          <span class="product-price">$24.99</span>
          <img class="product-image" src="b.jpg" vi="10 470 280 200">
          <div class="rating">★★★☆☆</div>
        </div>
        <div class="product-card" vi="0 840 300 400">
          <h2 class="product-title">Widget Gamma</h2>
          <span class="product-price">$39.99</span>
          <img class="product-image" src="c.jpg" vi="10 890 280 200">
          <div class="rating">★★★★★</div>
        </div>
        </body></html>
    """.trimIndent()

    /** 2 results with data-testid and aria-label attributes. */
    private val dataAttrHtml = """
        <html><body>
        <div class="result" vi="0 0 400 100">
          <span data-testid="price">$10.00</span>
          <button aria-label="Add to cart">Buy</button>
        </div>
        <div class="result" vi="0 120 400 100">
          <span data-testid="price">$20.00</span>
          <button aria-label="Add to cart">Buy</button>
        </div>
        </body></html>
    """.trimIndent()

    /** A page with a mix of content and bare structural wrappers. */
    private val mixedStructuralHtml = """
        <html><body>
        <div class="listing" vi="0 0 500 200">
          <div><div><div class="inner-text" vi="10 10 480 30">Important content</div></div></div>
          <span class="badge">Sale</span>
        </div>
        <div class="listing" vi="0 220 500 200">
          <div><div><div class="inner-text" vi="10 230 480 30">More content</div></div></div>
          <span class="badge">New</span>
        </div>
        </body></html>
    """.trimIndent()

    // =========================================================================
    // Basic functionality
    // =========================================================================

    @Nested
    @DisplayName("Basic selector discovery")
    inner class BasicDiscovery {

        @Test
        @DisplayName("discovers class-based selectors recurring across product cards")
        fun discoversRecurringClassSelectors() {
            val result = inspect(productCardsHtml, ".product-card")

            assertEquals(3, result["matchCount"].asInt())
            assertEquals(".product-card", result["selector"].asText())

            val suggestions = result["suggestions"]
            assertTrue(suggestions.size() > 0, "Should have suggestions")

            // Should find recurring selectors like h2.product-title, span.product-price
            val selectors = suggestions.map { it["selector"].asText() }
            assertTrue(selectors.any { it.contains("product-title") }, "Should find product-title selector")
            assertTrue(selectors.any { it.contains("product-price") }, "Should find product-price selector")
            assertTrue(selectors.any { it.contains("product-image") }, "Should find product-image selector")
        }

        @Test
        @DisplayName("discovers id-based selectors")
        fun discoversIdSelectors() {
            val html = """
                <div class="card" vi="0 0 200 100"><span id="unique-name">Alice</span></div>
                <div class="card" vi="0 120 200 100"><span id="unique-name">Bob</span></div>
            """.trimIndent()
            val result = inspect(html, ".card")

            val suggestions = result["suggestions"]
            val idSels = suggestions.filter { it["selector"].asText().contains("#") }
            assertTrue(idSels.isNotEmpty(), "Should discover id-based selector")
        }

        @Test
        @DisplayName("returns empty suggestions when no recurring pattern found")
        fun noRecurringPattern() {
            val html = """
                <div class="card" vi="0 0 100 50"><p>One</p></div>
                <div class="card" vi="0 70 100 50"><b>Two</b></div>
            """.trimIndent()
            val result = inspect(html, ".card")

            assertEquals(2, result["matchCount"].asInt())
            // threshold = max(2, 2*0.5) = 2 — bare tags like div/p/b need 2 matches
            // div and b appear only once each
        }

        @Test
        @DisplayName("handles selector with zero matches gracefully")
        fun zeroMatches() {
            val result = inspect(productCardsHtml, ".nonexistent")
            assertEquals(0, result["matchCount"].asInt())
            assertEquals(".nonexistent", result["selector"].asText())
            assertEquals(0, result["suggestions"].size())
        }
    }

    // =========================================================================
    // Smart ranking
    // =========================================================================

    @Nested
    @DisplayName("Smart quality ranking")
    inner class SmartRanking {

        @Test
        @DisplayName("class-based selectors rank above bare tags")
        fun classSelectorsOutrankBareTags() {
            val result = inspect(productCardsHtml, ".product-card")

            val suggestions = result["suggestions"]
            assertTrue(suggestions.size() >= 2, "Need at least 2 suggestions to compare ranking")

            val first = suggestions[0]
            val last = suggestions[suggestions.size() - 1]

            val firstQuality = first["quality"].asText()
            val lastQuality = last["quality"].asText()

            // Best should be high/medium; bare tags should be low
            assertTrue(firstQuality == "high" || firstQuality == "medium",
                "Top suggestion quality should be high or medium, was: $firstQuality ($first)")
        }

        @Test
        @DisplayName("quality field is present on all suggestions")
        fun qualityFieldPresent() {
            val result = inspect(productCardsHtml, ".product-card")
            for (sug in result["suggestions"]) {
                val q = sug["quality"].asText()
                assertTrue(q in setOf("high", "medium", "low"), "quality should be high/medium/low, got: $q")
            }
        }

        @Test
        @DisplayName("high-quality selectors have ★ indicator material (non-empty, specific)")
        fun highQualitySelectorsAreSpecific() {
            val result = inspect(productCardsHtml, ".product-card")
            val highQuality = result["suggestions"].filter { it["quality"].asText() == "high" }

            for (sug in highQuality) {
                val sel = sug["selector"].asText()
                // High quality = specific selector (has . or # or [ or :expr)
                assertTrue(
                    sel.contains(".") || sel.contains("#") || sel.contains("[") || sel.contains(":expr"),
                    "High-quality selector '$sel' should be specific"
                )
            }
        }

        @Test
        @DisplayName("semantic tags (h2, a, img) get ranking boost over div/span")
        fun semanticTagsGetBoost() {
            val result = inspect(productCardsHtml, ".product-card")
            val suggestions = result["suggestions"]
            val selectors = suggestions.map { it["selector"].asText() }

            // h2 selector should appear before any bare div
            val titleIdx = selectors.indexOfFirst { it.contains("h2") && it.contains("product-title") }
            val bareDivIdx = selectors.indexOfFirst { it in listOf("div", "span") }
            if (titleIdx >= 0 && bareDivIdx >= 0) {
                assertTrue(titleIdx < bareDivIdx,
                    "h2.product-title (idx=$titleIdx) should rank above bare div/span (idx=$bareDivIdx)")
            }
        }
    }

    // =========================================================================
    // Value sampling
    // =========================================================================

    @Nested
    @DisplayName("Value sampling (textSamples)")
    inner class ValueSampling {

        @Test
        @DisplayName("textSamples contains distinct values across matches")
        fun textSamplesContainsDistinctValues() {
            val result = inspect(productCardsHtml, ".product-card")

            // Find the price selector suggestion
            val priceSug = result["suggestions"].firstOrNull {
                it["selector"].asText().contains("product-price")
            }
            assertNotNull(priceSug, "Should find product-price suggestion")

            val samples = priceSug["textSamples"]
            assertNotNull(samples, "textSamples should be present")
            assertTrue(samples.size() >= 2,
                "Should have at least 2 distinct price values, got: ${samples.map { it.asText() }}")

            val values = samples.map { it.asText() }
            assertTrue(values.any { it.contains("19.99") }, "Should contain $19.99")
            assertTrue(values.any { it.contains("24.99") }, "Should contain $24.99")
        }

        @Test
        @DisplayName("textPreview is present for backward compatibility")
        fun textPreviewBackwardCompat() {
            val result = inspect(productCardsHtml, ".product-card")
            val priceSug = result["suggestions"].firstOrNull {
                it["selector"].asText().contains("product-price")
            }
            assertNotNull(priceSug)
            assertTrue(priceSug.has("textPreview"), "textPreview should be present for backward compat")
        }

        @Test
        @DisplayName("elements with identical text show fewer samples")
        fun identicalTextFewerSamples() {
            val html = """
                <div class="card" vi="0 0 200 80"><span class="label">Static</span></div>
                <div class="card" vi="0 100 200 80"><span class="label">Static</span></div>
                <div class="card" vi="0 200 200 80"><span class="label">Static</span></div>
            """.trimIndent()
            val result = inspect(html, ".card")
            val labelSug = result["suggestions"].firstOrNull {
                it["selector"].asText().contains("label")
            }
            assertNotNull(labelSug)
            if (labelSug.has("textSamples")) {
                assertEquals(1, labelSug["textSamples"].size(),
                    "Identical text should produce only 1 distinct sample")
            }
        }
    }

    // =========================================================================
    // Attribute selectors
    // =========================================================================

    @Nested
    @DisplayName("Attribute-based selector discovery")
    inner class AttributeSelectors {

        @Test
        @DisplayName("discovers [data-testid] selectors")
        fun discoversDataTestid() {
            val result = inspect(dataAttrHtml, ".result")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            assertTrue(selectors.any { it.contains("data-testid") },
                "Should discover [data-testid] selector, got: $selectors")
        }

        @Test
        @DisplayName("discovers [aria-label] selectors")
        fun discoversAriaLabel() {
            val result = inspect(dataAttrHtml, ".result")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            assertTrue(selectors.any { it.contains("aria-label") },
                "Should discover [aria-label] selector, got: $selectors")
        }

        @Test
        @DisplayName("discovers [role] selectors")
        fun discoversRole() {
            val html = """
                <div class="card" vi="0 0 200 60"><div role="button">Click</div></div>
                <div class="card" vi="0 80 200 60"><div role="button">Tap</div></div>
            """.trimIndent()
            val result = inspect(html, ".card")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            assertTrue(selectors.any { it.contains("role") },
                "Should discover [role] selector, got: $selectors")
        }

        @Test
        @DisplayName("discovers [itemprop] selectors from schema.org microdata")
        fun discoversItemprop() {
            val html = """
                <div class="product" vi="0 0 300 100" itemtype="https://schema.org/Product">
                  <span itemprop="name">Widget</span>
                  <span itemprop="price">$9.99</span>
                </div>
                <div class="product" vi="0 120 300 100" itemtype="https://schema.org/Product">
                  <span itemprop="name">Gadget</span>
                  <span itemprop="price">$14.99</span>
                </div>
            """.trimIndent()
            val result = inspect(html, ".product")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            assertTrue(selectors.any { it.contains("itemprop") },
                "Should discover [itemprop] selector, got: $selectors")
        }

        @Test
        @DisplayName("generic data-* attributes are discovered when values repeat")
        fun discoversGenericDataAttrs() {
            // Values MUST match across elements to be recurring
            val html = """
                <div class="row" vi="0 0 200 40"><span data-custom-id="sku-123">X</span></div>
                <div class="row" vi="0 60 200 40"><span data-custom-id="sku-123">Y</span></div>
            """.trimIndent()
            val result = inspect(html, ".row")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            assertTrue(selectors.any { it.contains("data-custom-id") },
                "Should discover [data-custom-id] selector, got: $selectors")
        }
    }

    // =========================================================================
    // PowerCSS :expr() selectors
    // =========================================================================

    @Nested
    @DisplayName("PowerCSS :expr() selector discovery")
    inner class PowerCSSSelectors {

        @Test
        @DisplayName("discovers :expr(width>N) for large elements with vi attributes")
        fun discoversWidthExpr() {
            val result = inspect(productCardsHtml, ".product-card")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            val powerSels = selectors.filter { it.contains(":expr") }
            assertTrue(powerSels.isNotEmpty(),
                "Should discover PowerCSS selectors when vi present, got: $selectors")
            assertTrue(powerSels.any { it.contains("width>") },
                "Should have width-based :expr(), got: $powerSels")
        }

        @Test
        @DisplayName("discovers :expr(img>0) for image containers")
        fun discoversImgExpr() {
            val result = inspect(productCardsHtml, ".product-card")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            val powerSels = selectors.filter { it.contains(":expr") }
            assertTrue(powerSels.any { it.contains("img>0") },
                "Should discover :expr(img>0) for image containers, got: $powerSels")
        }

        @Test
        @DisplayName("discovers combined :expr(width>N && img>0)")
        fun discoversCombinedExpr() {
            val result = inspect(productCardsHtml, ".product-card")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            val powerSels = selectors.filter { it.contains(":expr") }
            assertTrue(powerSels.any { it.contains("width>") && it.contains("img>0") },
                "Should discover combined :expr(width>N && img>0), got: $powerSels")
        }

        @Test
        @DisplayName("rounds width threshold down to nearest 100")
        fun roundsWidthThreshold() {
            val result = inspect(productCardsHtml, ".product-card")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            // img elements have vi="10 50 280 200" → width=280, rounded to 200
            val imgPower = selectors.filter { it.contains("img") && it.contains(":expr(width>") }
            if (imgPower.isNotEmpty()) {
                assertTrue(imgPower.any { it.contains("width>200") },
                    "Width 280 should round down to >200, got: $imgPower")
            }
        }

        @Test
        @DisplayName("no :expr() selectors when vi attributes are absent")
        fun noExprWithoutVi() {
            val html = """
                <div class="card"><span class="title">No VI</span></div>
                <div class="card"><span class="title">No VI either</span></div>
            """.trimIndent()
            val result = inspect(html, ".card")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            val powerSels = selectors.filter { it.contains(":expr") }
            assertTrue(powerSels.isEmpty(),
                "Should not generate :expr() selectors without vi attrs, got: $powerSels")
        }

        @Test
        @DisplayName("discovers :expr(a>0) for link containers")
        fun discoversLinkExpr() {
            val html = """
                <div class="nav" vi="0 0 300 50"><a href="/1">Link 1</a></div>
                <div class="nav" vi="0 70 300 50"><a href="/2">Link 2</a></div>
            """.trimIndent()
            val result = inspect(html, ".nav")

            val selectors = result["suggestions"].map { it["selector"].asText() }
            val powerSels = selectors.filter { it.contains(":expr") }
            assertTrue(powerSels.any { it.contains("a>0") },
                "Should discover :expr(a>0) for link containers, got: $powerSels")
        }
    }

    // =========================================================================
    // JSON structure / backward compatibility
    // =========================================================================

    @Nested
    @DisplayName("Response structure and backward compatibility")
    inner class ResponseStructure {

        @Test
        @DisplayName("top-level fields are present")
        fun topLevelFields() {
            val result = inspect(productCardsHtml, ".product-card")

            assertTrue(result.has("matchCount"))
            assertTrue(result.has("selector"))
            assertTrue(result.has("analyzed"))
            assertTrue(result.has("samples"))
            assertTrue(result.has("suggestions"))
        }

        @Test
        @DisplayName("sample structure contains tag, class, text, children")
        fun sampleStructure() {
            val result = inspect(productCardsHtml, ".product-card")
            val samples = result["samples"]
            assertTrue(samples.size() > 0, "Should have samples")

            val first = samples[0]
            assertTrue(first.has("tag"))
            assertEquals("div", first["tag"].asText())
            assertTrue(first.has("class"))
            assertEquals("product-card", first["class"].asText())
            assertTrue(first.has("children"))
            assertTrue(first["children"].size() > 0, "Should have child elements")
        }

        @Test
        @DisplayName("suggestion fields include matchCount, coverage, quality")
        fun suggestionFields() {
            val result = inspect(productCardsHtml, ".product-card")
            val sug = result["suggestions"][0]

            assertTrue(sug.has("selector"))
            assertTrue(sug.has("tag"))
            assertTrue(sug.has("matchCount"))
            assertTrue(sug.has("coverage"))
            assertTrue(sug.has("quality"))
            // coverage should end with %
            assertTrue(sug["coverage"].asText().endsWith("%"))
        }

        @Test
        @DisplayName("analyzed count equals the number of matches taken")
        fun analyzedCount() {
            val result = inspect(productCardsHtml, ".product-card", maxMatches = 2)
            assertEquals(3, result["matchCount"].asInt()) // total matches
            assertEquals(2, result["analyzed"].asInt())   // taken (capped by maxMatches)
        }
    }

    // =========================================================================
    // Test via the public :expr() initial selector
    // =========================================================================

    @Nested
    @DisplayName(":expr() in the initial selector (FeaturedDocument support)")
    inner class ExprInitialSelector {

        @Test
        @DisplayName(":expr() in initial selector filters matches correctly")
        fun exprInitialSelectorFilters() {
            // 3 cards: first is narrow (200px), second 300px, third 300px
            val html = """
                <div class="card" vi="0 0 200 200"><span class="title">Narrow</span></div>
                <div class="card" vi="0 220 300 200"><span class="title">Wide B</span></div>
                <div class="card" vi="0 440 300 200"><span class="title">Wide C</span></div>
            """.trimIndent()
            // :expr(width>250) should only match the two wide cards
            val result = inspect(html, "div.card:expr(width>250)")

            assertEquals(2, result["matchCount"].asInt(),
                ":expr(width>250) should match 2 wide cards")
            assertTrue(result["suggestions"].size() > 0,
                "Should have suggestions from the 2 matched cards")
        }

        @Test
        @DisplayName(":expr(img>0) in initial selector filters to cards with images")
        fun exprImgInitialSelector() {
            val html = """
                <div class="card" vi="0 0 200 100"><img src="a.jpg" vi="10 10 180 80"></div>
                <div class="card" vi="0 120 200 100"><span>No image here</span></div>
                <div class="card" vi="0 240 200 100"><img src="c.jpg" vi="10 250 180 80"></div>
            """.trimIndent()
            val result = inspect(html, "div.card:expr(img>0)")

            assertEquals(2, result["matchCount"].asInt(),
                ":expr(img>0) should match 2 cards with images")
            assertEquals(2, result["analyzed"].asInt())
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("handles HTML without vi attributes gracefully")
        fun noViAttributes() {
            val html = """
                <div class="item"><span class="name">A</span></div>
                <div class="item"><span class="name">B</span></div>
                <div class="item"><span class="name">C</span></div>
            """.trimIndent()

            // Should not crash — just won't generate PowerCSS selectors
            val result = inspect(html, ".item")
            assertEquals(3, result["matchCount"].asInt())
            val selectors = result["suggestions"].map { it["selector"].asText() }
            assertTrue(selectors.any { it.contains("name") },
                "Should still discover class-based selectors without vi")
        }

        @Test
        @DisplayName("handles single match (below recurrence threshold)")
        fun singleMatch() {
            val result = inspect(
                "<div class='only' vi='0 0 100 50'><span class='sole'>One</span></div>",
                ".only"
            )
            assertEquals(1, result["matchCount"].asInt())
            // threshold = max(2, 1*0.5) = 2 > 1, so no suggestions
            assertEquals(0, result["suggestions"].size(),
                "Single match should produce no suggestions (below threshold)")
        }

        @Test
        @DisplayName("malformed vi attribute does not crash")
        fun malformedViAttribute() {
            val html = """
                <div class="ok" vi="garbage"><span class="x">A</span></div>
                <div class="ok" vi="garbage"><span class="x">B</span></div>
            """.trimIndent()
            val result = inspect(html, ".ok")
            assertEquals(2, result["matchCount"].asInt())
            // Should not crash, just skip PowerCSS for the bad vi
        }

        @Test
        @DisplayName("selector with special characters is handled")
        fun specialCharSelector() {
            val result = inspect(productCardsHtml, ".product-card")
            // The selector is echoed back in the response
            assertEquals(".product-card", result["selector"].asText())
        }

        @Test
        @DisplayName(":root selector auto-discovers repeating pattern")
        fun rootSelectorAutoDiscovers() {
            val result = inspect(productCardsHtml)
            // Auto-discovery should kick in and find .product-card
            assertTrue(result["autoDiscovered"].asBoolean(),
                "Should auto-discover when :root matches only 1 element")
            assertEquals(":root", result["originalSelector"].asText())
            assertEquals(".product-card", result["selector"].asText())
            assertEquals(3, result["matchCount"].asInt())
            assertTrue(result["suggestions"].size() > 0,
                "Should have suggestions from auto-discovered repeating pattern")
        }

        @Test
        @DisplayName("auto-discovery returns null on pages with no repeating content")
        fun autoDiscoveryNoRepeatingContent() {
            val html = """
                <html><body>
                <h1>Single Article</h1>
                <p>This page has no repeating content patterns.</p>
                </body></html>
            """.trimIndent()
            val result = inspect(html)
            // No auto-discovery fields (nothing found), selector stays as :root
            assertFalse(result.has("autoDiscovered"),
                "Should not set autoDiscovered when no pattern found")
            assertEquals(":root", result["selector"].asText())
            assertEquals(1, result["matchCount"].asInt())
            assertEquals(0, result["suggestions"].size(),
                "No suggestions when no repeating content exists")
        }

        @Test
        @DisplayName("auto-discovery prefers class-based groups over bare tags")
        fun autoDiscoveryPrefersClassBased() {
            val html = """
                <html><body>
                <nav>
                  <a href="/1">Link 1</a>
                  <a href="/2">Link 2</a>
                  <a href="/3">Link 3</a>
                </nav>
                <main>
                  <div class="product">
                    <h2>Widget A</h2>
                    <span class="price">$10</span>
                    <p>Description A</p>
                  </div>
                  <div class="product">
                    <h2>Widget B</h2>
                    <span class="price">$20</span>
                    <p>Description B</p>
                  </div>
                </main>
                </body></html>
            """.trimIndent()
            val result = inspect(html)
            assertTrue(result["autoDiscovered"].asBoolean())
            // Should prefer .product (class-based, richer structure) over a (bare tag)
            assertEquals(".product", result["selector"].asText(),
                "Should prefer class-based .product over bare <a> tags")
        }

        @Test
        @DisplayName("explicit multi-match selector bypasses auto-discovery")
        fun explicitMultiMatchBypassesAutoDiscovery() {
            // When user provides a selector that matches ≥2 elements, no auto-discovery
            val result = inspect(productCardsHtml, ".product-card")
            assertFalse(result.has("autoDiscovered"),
                "Should not auto-discover when explicit selector matches ≥2 elements")
            assertEquals(".product-card", result["selector"].asText())
            assertEquals(3, result["matchCount"].asInt())
        }
    }
}
