package ai.platon.pulsar.test.server.ec

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
class HtmlRenderer(private val catalogService: CatalogService) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val homeTemplate = loadTemplate("/static/generated/mock-amazon/ec-home.html")
    private val categoryTemplate = loadTemplate("/static/generated/mock-amazon/ec-category.html")
    private val productTemplate = loadTemplate("/static/generated/mock-amazon/ec-product.html")

    private fun loadTemplate(path: String): String {
        val res = javaClass.getResource(path) ?: error("Template not found: $path")
        return res.readText(StandardCharsets.UTF_8)
    }

    private fun esc(s: String?): String {
        if (s == null) return ""
        return buildString(s.length) {
            for (c in s) when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    fun renderHome(featuredProducts: List<Product> = emptyList()): String {
        val links = catalogService.allCategories().joinToString("\n") { c ->
            """
            <li class="category-item" data-category-id="${c.id}">
              <a id="cat-link-${c.id}" href="/ec/b?node=${c.id}">${esc(c.name)}</a>
            </li>
            """.trimIndent()
        }
        val featured = if (featuredProducts.isNotEmpty()) {
            featuredProducts.joinToString("\n") { productCard(it) }
        } else {
            "<p>No featured products available.</p>"
        }
        val trendingSearches = featuredProducts.take(6).joinToString("\n") { p ->
            """<li><a href="/ec/dp/${p.id}">${esc(p.name)}</a></li>"""
        }.ifBlank {
            "<li><a href=\"/ec/\">Explore the latest arrivals</a></li>"
        }
        val categorySpotlights = catalogService.allCategories().take(4).joinToString("\n") { c ->
            """
            <article class="spotlight-card">
              <p class="eyebrow">Buying guide</p>
              <h3><a href="/ec/b?node=${c.id}">${esc(c.name)}</a></h3>
              <p>Compare top-rated picks, value alternatives, and quick-ship favorites in ${esc(c.name.lowercase())}.</p>
            </article>
            """.trimIndent()
        }
        return homeTemplate
            .replace("<!--CATEGORY_LINKS-->", links)
            .replace("<!--FEATURED_PRODUCTS-->", featured)
            .replace("<!--TRENDING_SEARCHES-->", trendingSearches)
            .replace("<!--CATEGORY_SPOTLIGHTS-->", categorySpotlights)
            .replace("{{TITLE}}", "Mock EC Home")
    }

    private fun productBadges(p: Product): String {
        val badges = p.badges.orEmpty()
        if (badges.isEmpty()) return ""
        return badges.joinToString("", prefix = "<div class=\"product-badges\">", postfix = "</div>") {
            "<span class=\"badge\">${esc(it)}</span>"
        }
    }

    private fun ratingSpan(p: Product): String {
        val r = p.rating
        val rc = p.ratingCount
        return if (r != null && rc != null) {
            "<span class=\"product-rating\" id=\"product-rating-${p.id}\" data-rating=\"$r\">${String.format("%.1f", r)} ($rc)</span>"
        } else ""
    }

    private fun productImage(p: Product): String {
        val img = p.image?.takeIf { it.isNotBlank() && !it.endsWith("placeholder.png") }
            ?: "https://picsum.photos/seed/${p.id.hashCode()}/200/140"
        return esc(img)
    }

    private fun productDescription(p: Product): String {
        return esc(
            p.description?.takeIf { it.isNotBlank() }
                ?: "${p.name} is one of the most frequently viewed items in this mock retail catalog."
        )
    }

    private fun featurePills(p: Product): String {
        val features = p.features.orEmpty().take(3)
        if (features.isEmpty()) return ""
        return features.joinToString(
            separator = "",
            prefix = "<div class=\"feature-pills\">",
            postfix = "</div>"
        ) { """<span class="feature-pill">${esc(it)}</span>""" }
    }

    private fun inventoryQty(p: Product): Int? {
        val qty = p.inventory?.get("qty")
        return when (qty) {
            is Number -> qty.toInt()
            is String -> qty.toIntOrNull()
            else -> null
        }
    }

    private fun inStock(p: Product): Boolean {
        val raw = p.inventory?.get("inStock")
        return when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> inventoryQty(p)?.let { it > 0 } ?: true
        }
    }

    private fun stockMessage(p: Product): String {
        val qty = inventoryQty(p)
        return when {
            !inStock(p) -> "Currently unavailable"
            qty == null -> "In stock"
            qty < 15 -> "Only $qty left in stock"
            qty < 60 -> "In stock ($qty available)"
            else -> "Ready to ship"
        }
    }

    private fun sectionRoll(product: Product, salt: String): Int {
        val value = "${product.id}:${product.categoryId}:$salt".hashCode().toLong() and 0x7fffffff
        return (value % 100).toInt()
    }

    private fun hasBadge(product: Product, badge: String): Boolean =
        product.badges.orEmpty().any { it.equals(badge, ignoreCase = true) }

    private data class ProductSectionProfile(
        val showVisitSignal: Boolean,
        val showInfoPills: Boolean,
        val showFeatureSection: Boolean,
        val showSpecsSection: Boolean,
        val showBuyboxMeta: Boolean,
        val showFaqSection: Boolean,
        val showSellerNotes: Boolean,
        val showRecommendations: Boolean,
        val showComments: Boolean
    )

    private fun sectionProfile(product: Product, relatedProducts: List<Product>): ProductSectionProfile {
        val ratingCount = product.ratingCount ?: 0
        val specsCount = product.specs.orEmpty().size
        val featureCount = product.features.orEmpty().size
        val popular = ratingCount >= 150 || hasBadge(product, "Bestseller")
        val premium = product.price >= 80.0
        val lowStock = inventoryQty(product)?.let { it in 1..20 } == true

        return ProductSectionProfile(
            showVisitSignal = popular || sectionRoll(product, "visit-signal") < 42,
            showInfoPills = premium || sectionRoll(product, "info-pills") < 74,
            showFeatureSection = featureCount > 0 && (product.price >= 20.0 || sectionRoll(product, "features") < 30),
            showSpecsSection = specsCount > 0 && (premium || sectionRoll(product, "specs") < 58),
            showBuyboxMeta = lowStock || premium || sectionRoll(product, "buybox-meta") < 86,
            showFaqSection = specsCount > 0 && (popular || sectionRoll(product, "faq") < 44),
            showSellerNotes = lowStock || premium || sectionRoll(product, "seller-notes") < 78,
            showRecommendations = relatedProducts.isNotEmpty() && (popular || sectionRoll(product, "recommendations") < 68),
            showComments = popular || premium || sectionRoll(product, "comments") < 57
        )
    }

    private fun recommendationCard(product: Product, label: String): String = """
        <article class="recommendation-card" data-product-id="${product.id}">
          <p class="eyebrow">${esc(label)}</p>
          <h3><a href="/ec/dp/${product.id}">${esc(product.name)}</a></h3>
          <p class="recommendation-copy">${productDescription(product)}</p>
          <div class="recommendation-meta">
            <span class="recommendation-price">${formatPrice(product)}</span>
            <span class="recommendation-stock">${esc(stockMessage(product))}</span>
          </div>
        </article>
    """.trimIndent()

    private fun commentCards(product: Product): String {
        val title = esc(product.name)
        val features = product.features.orEmpty()
        val headline = features.firstOrNull()?.let { "Loved the $it on $title" } ?: "Solid value for everyday use"
        val secondHeadline = product.specs.orEmpty().keys.firstOrNull()?.let { "Useful ${esc(it)} details before checkout" }
            ?: "Shipping and setup notes were easy to follow"
        val thirdHeadline = "Worth reading the fine print before ordering"
        return listOf(
            Triple(
                headline,
                "The page felt crowded with delivery promises and bundle suggestions, but the core product details still matched what arrived for testing.",
                "Ava M. • Verified buyer"
            ),
            Triple(
                secondHeadline,
                "I had to scroll past FAQs, seller notes, and recommended items before finishing the comparison. That is exactly how a real product page usually feels.",
                "Jordan K. • Top contributor"
            ),
            Triple(
                thirdHeadline,
                "Comments, returns guidance, and accessory suggestions add noise around the main title and price, which makes this a better scraping fixture.",
                "Casey R. • Recent reviewer"
            )
        ).joinToString("\n") { (h, body, meta) ->
            """
            <article class="comment-card">
              <h3>$h</h3>
              <p>$body</p>
              <span class="comment-meta">${esc(meta)}</span>
            </article>
            """.trimIndent()
        }
    }

    private fun visitSignal(product: Product): String {
        val base = 700 + ((product.ratingCount ?: 0) * 3)
        val adjustment = sectionRoll(product, "visit-count") * 11
        val visits = base + adjustment
        return "<span>${String.format("%,d", visits)} recent visits</span>"
    }

    private fun infoPills(product: Product): String {
        val pills = mutableListOf<String>()
        if (inStock(product)) pills += "Free delivery options"
        if (sectionRoll(product, "returns-pill") < 72) pills += "30-day returns"
        if (sectionRoll(product, "seller-pill") < 45) pills += "Marketplace seller"
        if (sectionRoll(product, "warranty-pill") < 35) pills += "Optional protection plan"
        pills += "Last updated ${esc(product.updatedAt ?: product.createdAt ?: "2025-01-01T00:00:00Z")}"
        return pills.distinct().joinToString(
            separator = "",
            prefix = "<div class=\"info-pills\">",
            postfix = "</div>"
        ) { """<span class="info-pill">${esc(it)}</span>""" }
    }

    private fun detailsSection(featureBlock: String): String =
        """
        <section id="details">
          <h2>About this item</h2>
          $featureBlock
        </section>
        """.trimIndent()

    private fun specsSection(specsTable: String): String =
        """
        <section>
          <h2>Technical details</h2>
          $specsTable
        </section>
        """.trimIndent()

    private fun buyboxMeta(product: Product): String {
        val lines = buildList {
            add("Ships from Mock EC fulfillment.")
            if (sectionRoll(product, "seller-copy") < 65) add("Sold by a marketplace seller.")
            if (sectionRoll(product, "delivery-copy") < 70) add("Delivery windows vary by destination and cutoff time.")
            if (sectionRoll(product, "financing-copy") < 38) add("Installment and bundle offers may appear for eligible orders.")
            if (sectionRoll(product, "returns-copy") < 52) add("Return messaging can differ across sellers and seasonal promotions.")
        }
        return """<p class="meta">${esc(lines.joinToString(" "))}</p>"""
    }

    private fun sellerNotesSection(product: Product): String {
        val notes = mutableListOf(
            "Estimated delivery windows vary by destination and fulfillment center."
        )
        if (sectionRoll(product, "holiday-returns") < 66) {
            notes += "Extended holiday returns may apply to eligible orders."
        }
        if (sectionRoll(product, "gift-options") < 53) {
            notes += "Gift wrap, protection plans, and seller messages may appear alongside the main purchase flow."
        }
        if (sectionRoll(product, "import-fees") < 24) {
            notes += "Some sellers may surface import-fee or packaging notices before checkout."
        }
        return """
        <section class="secondary-section">
          <h2>Shipping, returns, and seller notes</h2>
          <ul>
            ${notes.joinToString("\n") { "<li>${esc(it)}</li>" }}
          </ul>
        </section>
        """.trimIndent()
    }

    private fun faqSection(faqItems: String): String =
        """
        <section class="secondary-section">
          <h2>Frequently asked questions</h2>
          <ul>
            $faqItems
          </ul>
        </section>
        """.trimIndent()

    private fun secondaryGrid(sections: List<String>): String {
        if (sections.isEmpty()) return ""
        return """
        <section class="secondary-grid">
          ${sections.joinToString("\n")}
        </section>
        """.trimIndent()
    }

    private fun recommendationsSection(relatedProducts: String): String =
        """
        <section class="recommendations secondary-section">
          <h2>Customers also viewed</h2>
          <div class="recommendation-grid">
            $relatedProducts
          </div>
        </section>
        """.trimIndent()

    private fun commentsSection(commentCards: String): String =
        """
        <section class="comments secondary-section">
          <h2>Customer comments</h2>
          <div class="comment-grid">
            $commentCards
          </div>
        </section>
        """.trimIndent()

    private fun productCard(p: Product): String = """
        <article class="product-card" id="product-${p.id}" data-category-id="${p.categoryId}" data-product-id="${p.id}">
          <a class="product-link" href="/ec/dp/${p.id}">
            <img class="product-image" src="${productImage(p)}" alt="${esc(p.name)}" />
            <h2 class="product-title">${esc(p.name)}</h2>
          </a>
          <div class="product-meta">
            <span class="product-price" id="product-price-${p.id}" data-product-id="${p.id}">${formatPrice(p)}</span>
            ${ratingSpan(p)}
          </div>
          <p class="product-copy">${productDescription(p)}</p>
          ${featurePills(p)}
          <div class="product-ops">
            <span class="stock-note">${esc(stockMessage(p))}</span>
            <a class="secondary-link" href="/ec/dp/${p.id}#details">See details</a>
          </div>
          ${productBadges(p)}
        </article>
    """.trimIndent()

    private fun formatPrice(p: Product): String = "${'$'}" + String.format("%.2f", p.price)

    fun renderCategory(category: Category, products: List<Product>): String {
        val cards = products.joinToString("\n") { productCard(it) }
        val filterChips = buildList {
            add("Top rated")
            add("Ready to ship")
            products.minOfOrNull { it.price }?.let { add("From ${'$'}${String.format("%.2f", it)}") }
            products.maxOfOrNull { it.price }?.let { add("Under ${'$'}${String.format("%.2f", it)}") }
        }.distinct().joinToString("\n") { """<button type="button" class="filter-chip">$it</button>""" }
        val siblingCategories = catalogService.allCategories()
            .filter { it.id != category.id }
            .take(6)
            .joinToString("\n") { c ->
                """<li><a href="/ec/b?node=${c.id}">${esc(c.name)}</a></li>"""
            }
        val sponsoredProducts = catalogService.getBestsellers(12)
            .filter { it.categoryId != category.id }
            .take(3)
            .joinToString("\n") { recommendationCard(it, "Sponsored") }
        val categorySummary = "Showing ${products.size} products in ${category.name} plus supporting buying guides, merchandising, and storefront navigation."
        return categoryTemplate
            .replace("{{CATEGORY_ID}}", esc(category.id))
            .replace("{{CATEGORY_NAME}}", esc(category.name))
            .replace("{{RESULT_COUNT}}", products.size.toString())
            .replace("{{CATEGORY_SUMMARY}}", esc(categorySummary))
            .replace("<!--FILTER_CHIPS-->", filterChips)
            .replace("<!--RELATED_CATEGORY_LINKS-->", siblingCategories)
            .replace("<!--SPONSORED_PRODUCTS-->", sponsoredProducts)
            .replace("<!--PRODUCT_LIST-->", cards)
            .replace("{{TITLE}}", "Category: ${esc(category.name)}")
    }

    fun renderProduct(product: Product, category: Category): String {
        val featuresList = product.features.orEmpty()
        val featureItems = featuresList.joinToString("\n") { "<li>${esc(it)}</li>" }
        val featureBlock = if (featureItems.isEmpty()) "" else "<ul id=\"product-features\">$featureItems</ul>"

        val specsMap = product.specs.orEmpty()
        val specsRows = specsMap.entries.joinToString("\n") { "<tr><th>${esc(it.key)}</th><td>${esc(it.value)}</td></tr>" }
        val specsTable = if (specsRows.isEmpty()) "" else "<table id=\"product-specs\">$specsRows</table>"

        val ratingText = product.rating?.let { String.format("%.1f", it) } ?: ""
        val ratingCountText = product.ratingCount?.toString() ?: ""
        val relatedProductList = catalogService.getProductsByCategory(category.id)
            .filter { it.id != product.id }
        val profile = sectionProfile(product, relatedProductList)
        val relatedProducts = relatedProductList.take(4)
            .joinToString("\n") { recommendationCard(it, "Customers also viewed") }
        val faqItems = buildList {
            add("Is this item in stock? ${stockMessage(product)}.")
            product.features.orEmpty().take(2).forEach { feature ->
                add("Does it include $feature? Yes, the current listing highlights $feature.")
            }
            product.specs.orEmpty().entries.take(2).forEach { spec ->
                add("What about ${spec.key}? The current spec sheet lists ${spec.key} as ${spec.value}.")
            }
        }.joinToString("\n") { "<li>$it</li>" }
        val detailsSectionHtml = if (profile.showFeatureSection && featureBlock.isNotBlank()) detailsSection(featureBlock) else ""
        val specsSectionHtml = if (profile.showSpecsSection && specsTable.isNotBlank()) specsSection(specsTable) else ""
        val secondarySections = buildList {
            if (profile.showFaqSection) add(faqSection(faqItems))
            if (profile.showSellerNotes) add(sellerNotesSection(product))
        }
        val commentsHtml = if (profile.showComments) commentsSection(commentCards(product)) else ""
        val recommendationsHtml = if (profile.showRecommendations) recommendationsSection(relatedProducts) else ""
        val buyboxMetaHtml = if (profile.showBuyboxMeta) buyboxMeta(product) else ""
        val infoPillsHtml = if (profile.showInfoPills) infoPills(product) else ""
        val visitSignalHtml = if (profile.showVisitSignal) visitSignal(product) else ""

        return productTemplate
            .replace("{{PRODUCT_ID}}", esc(product.id))
            .replace("{{PRODUCT_NAME}}", esc(product.name))
            .replace("{{PRODUCT_PRICE}}", formatPrice(product))
            .replace("{{PRODUCT_RATING}}", ratingText)
            .replace("{{PRODUCT_RATING_COUNT}}", ratingCountText)
            .replace("{{PRODUCT_IMAGE}}", productImage(product))
            .replace("{{PRODUCT_CATEGORY_ID}}", esc(category.id))
            .replace("{{PRODUCT_CATEGORY_NAME}}", esc(category.name))
            .replace("{{PRODUCT_DESCRIPTION}}", productDescription(product))
            .replace("{{PRODUCT_STOCK_STATUS}}", esc(stockMessage(product)))
            .replace("<!--VISIT_SIGNAL-->", visitSignalHtml)
            .replace("<!--INFO_PILLS-->", infoPillsHtml)
            .replace("<!--DETAILS_SECTION-->", detailsSectionHtml)
            .replace("<!--SPECS_SECTION-->", specsSectionHtml)
            .replace("<!--BUYBOX_META-->", buyboxMetaHtml)
            .replace("<!--BADGES-->", productBadges(product))
            .replace("<!--SECONDARY_GRID-->", secondaryGrid(secondarySections))
            .replace("<!--RECOMMENDATIONS_SECTION-->", recommendationsHtml)
            .replace("<!--COMMENTS_SECTION-->", commentsHtml)
            .replace("{{TITLE}}", "Product: ${esc(product.name)}")
    }

    fun renderError(status: Int, message: String): String {
        return """
            <html lang="en">
              <head><meta charset="UTF-8"><title>Error $status</title></head>
              <body>
                <div id="error-page" class="error-code-$status"><h1>Error $status</h1><p>${esc(message)}</p></div>
              </body>
            </html>
        """.trimIndent()
    }
}
