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

    fun renderHome(): String {
        val links = catalogService.allCategories().joinToString("\n") { c ->
            """
            <li class="category-item" data-category-id="${c.id}">
              <a id="cat-link-${c.id}" href="/ec/b?node=${c.id}">${esc(c.name)}</a>
            </li>
            """.trimIndent()
        }
        return homeTemplate
            .replace("<!--CATEGORY_LINKS-->", links)
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
        val img = p.image?.takeIf { it.isNotBlank() } ?: "https://picsum.photos/seed/${p.id.hashCode()}/200/140"
        return esc(img)
    }

    private fun productCard(p: Product): String = """
        <article class="product-card" id="product-${p.id}" data-category-id="${p.categoryId}">
          <a class="product-link" href="/ec/dp/${p.id}">
            <img class="product-image" src="${productImage(p)}" alt="${esc(p.name)}" />
            <h2 class="product-title">${esc(p.name)}</h2>
          </a>
          <div class="product-meta">
            <span class="product-price" id="product-price-${p.id}" data-product-id="${p.id}">${formatPrice(p)}</span>
            ${ratingSpan(p)}
          </div>
          ${productBadges(p)}
        </article>
    """.trimIndent()

    private fun formatPrice(p: Product): String = "${'$'}" + String.format("%.2f", p.price)

    fun renderCategory(category: Category, products: List<Product>): String {
        val cards = products.joinToString("\n") { productCard(it) }
        return categoryTemplate
            .replace("{{CATEGORY_ID}}", esc(category.id))
            .replace("{{CATEGORY_NAME}}", esc(category.name))
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

        return productTemplate
            .replace("{{PRODUCT_ID}}", esc(product.id))
            .replace("{{PRODUCT_NAME}}", esc(product.name))
            .replace("{{PRODUCT_PRICE}}", formatPrice(product))
            .replace("{{PRODUCT_RATING}}", ratingText)
            .replace("{{PRODUCT_RATING_COUNT}}", ratingCountText)
            .replace("{{PRODUCT_IMAGE}}", productImage(product))
            .replace("{{PRODUCT_CATEGORY_ID}}", esc(category.id))
            .replace("{{PRODUCT_CATEGORY_NAME}}", esc(category.name))
            .replace("<!--FEATURE_LIST-->", featureBlock)
            .replace("<!--SPECS_TABLE-->", specsTable)
            .replace("<!--BADGES-->", productBadges(product))
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
