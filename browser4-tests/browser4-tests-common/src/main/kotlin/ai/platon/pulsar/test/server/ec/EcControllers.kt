package ai.platon.pulsar.test.server.ec

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

@Controller
class EcCategoryController(
    private val catalogLoader: CatalogLoader,
    private val listPageRenderer: ListPageRenderer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/ec/b", produces = [MediaType.TEXT_HTML_VALUE])
    fun categoryPage(@RequestParam("node", required = false) node: String?): ResponseEntity<String> {
        if (node.isNullOrBlank()) {
            log.warn("/ec/b 400 missing node")
            return htmlError(400, "Missing category parameter")
        }
        val category = catalogLoader.getCategory(node)
            ?: return htmlError(404, "Category not found").also { log.warn("/ec/b 404 unknown category id={}", node) }
        val products = catalogLoader.getProductsByCategory(node)
        val html = listPageRenderer.renderCategory(category, products)
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
    }

    private fun htmlError(status: Int, message: String): ResponseEntity<String> {
        val body = """
            <!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>Error $status</title></head>
            <body><div id=\"error-page\" class=\"error-code-$status\"><h1>$status</h1><p>${escapeHtml(message)}</p></div></body></html>
        """.trimIndent()
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body)
    }
}

@Component
class ListPageRenderer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val template: String by lazy { loadTemplate() }

    fun renderCategory(category: Category, products: List<Product>): String {
        val productCards = products.joinToString("\n") { buildProductCard(it) }
        var htmlWorking = template
        // Fix relative asset paths so CSS/JS load correctly under /ec/b
        htmlWorking = htmlWorking.replace("href=\"style.css\"", "href=\"/generated/mock-amazon/list/style.css\"")
            .replace("src=\"main.js\"", "src=\"/generated/mock-amazon/list/main.js\"")

        // Replace the product list content - use simple string replacement instead of regex
        val productListStart = htmlWorking.indexOf("""<div id="product-list" class="product-list">""")
        val productListEnd = htmlWorking.indexOf("""</div>""", productListStart + 1)

        if (productListStart != -1 && productListEnd != -1) {
            val beforeProductList = htmlWorking.substring(0, productListStart)
            val afterProductList = htmlWorking.substring(productListEnd + 6) // 6 = length of "</div>"
            val newProductList = """<div id="product-list" class="product-list" data-server-rendered="true">$productCards</div>"""
            htmlWorking = beforeProductList + newProductList + afterProductList
        }

        // Replace title
        val titleStart = htmlWorking.indexOf("""<title>""")
        val titleEnd = htmlWorking.indexOf("""</title>""", titleStart + 1)
        if (titleStart != -1 && titleEnd != -1) {
            val beforeTitle = htmlWorking.substring(0, titleStart)
            val afterTitle = htmlWorking.substring(titleEnd + 8) // 8 = length of "</title>"
            htmlWorking = beforeTitle + """<title>Category: ${escapeHtml(category.name)}</title>""" + afterTitle
        }

        return htmlWorking
    }

    private fun buildProductCard(p: Product): String {
        val price = "${'$'}${String.format("%.2f", p.price)}"
        val badges = p.badges.orEmpty().joinToString("") { "<span class=\"badge\">${escapeHtml(it)}</span>" }
        val badgesDiv = if (badges.isNotEmpty()) "<div class=\"product-badges\">$badges</div>" else ""
        val rating = if (p.rating != null && p.ratingCount != null) {
            "<div class=\"product-rating\" id=\"product-rating-${p.id}\" data-rating=\"${p.rating}\">${p.rating} (${p.ratingCount})</div>"
        } else ""
        val imageUrl = resolveImage(p)
        return """
            <div class=\"product-card\" id=\"product-${p.id}\" data-category-id=\"${p.categoryId}\">
              <a class=\"product-link\" href=\"/ec/dp/${p.id}\" style=\"text-decoration:none;color:inherit;display:block;\">
                <img src=\"$imageUrl\" alt=\"${escapeHtml(p.name)}\" class=\"product-img\" />
                <div class=\"product-title\">${escapeHtml(p.name)}</div>
              </a>
              <div class=\"product-price\" id=\"product-price-${p.id}\">$price</div>
              $rating
              $badgesDiv
            </div>
        """.trimIndent()
    }

    private fun resolveImage(p: Product): String {
        val img = p.image?.takeIf { it.isNotBlank() && !it.endsWith("placeholder.png") }
        return escapeHtml(img ?: "https://picsum.photos/seed/${p.id.hashCode()}/200/140")
    }

    private fun loadTemplate(): String {
        val path = "/static/generated/mock-amazon/list/index.html"
        val stream = javaClass.getResourceAsStream(path) ?: error("List template not found at $path")
        val html = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        log.info("Loaded EC list template length={}", html.length)
        return html
    }
}

private fun escapeHtml(s: String?): String {
    if (s == null) return ""
    return buildString(s.length) {
        for (c in s) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '&' -> append("&amp;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
