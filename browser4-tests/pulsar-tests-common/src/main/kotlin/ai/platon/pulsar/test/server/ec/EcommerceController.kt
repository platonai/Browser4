package ai.platon.pulsar.test.server.ec

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest

@Controller
@RequestMapping("/ec")
class EcommerceController(
    private val catalogService: CatalogService,
    private val htmlRenderer: HtmlRenderer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/")
    @ResponseBody
    fun home(): ResponseEntity<String> {
        val bestsellers = catalogService.getBestsellers(6)
        val html = htmlRenderer.renderHome(featuredProducts = bestsellers)
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
    }

    @GetMapping("/b")
    @ResponseBody
    fun category(@RequestParam(name = "node", required = false) node: String?): ResponseEntity<String> {
        if (node == null) {
            log.warn("/ec/b 400 missing category parameter")
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlRenderer.renderError(400, "Missing category parameter"))
        }
        val category = catalogService.getCategory(node)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlRenderer.renderError(404, "Category not found"))
        val products = catalogService.getProductsByCategory(node)
        val html = htmlRenderer.renderCategory(category, products)
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
    }

    /**
     * Sitemap listing all available product URLs on the mock e-commerce site.
     * Useful for crawl/swarm test scenarios that need to discover URLs without
     * guessing product IDs.  Returns an XML sitemap compatible with standard
     * sitemap protocol.
     */
    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    @ResponseBody
    fun sitemap(request: HttpServletRequest): ResponseEntity<String> {
        val baseUrl = "${request.scheme}://${request.serverName}:${request.serverPort}"
        val products = catalogService.allProducts().sortedBy { it.id }
        val categories = catalogService.allCategories().sortedBy { it.id }

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
            // Home page
            appendLine("""  <url><loc>$baseUrl/ec/</loc></url>""")
            // Product pages
            for (p in products) {
                appendLine("""  <url><loc>$baseUrl/ec/dp/${p.id}</loc></url>""")
            }
            // Category pages
            for (c in categories) {
                appendLine("""  <url><loc>$baseUrl/ec/b?node=${c.id}</loc></url>""")
            }
            appendLine("""</urlset>""")
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml)
    }

    @GetMapping("/dp/{productId}")
    @ResponseBody
    fun product(@PathVariable productId: String): ResponseEntity<String> {
        val product = catalogService.getProduct(productId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlRenderer.renderError(404, "Product not found"))
        val category = catalogService.getCategory(product.categoryId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlRenderer.renderError(404, "Product not found"))
        val html = htmlRenderer.renderProduct(product, category)
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
    }

    @GetMapping("/static/**")
    fun staticAsset(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val path = request.requestURI.removePrefix("/ec/static/")
        val resource = ClassPathResource("static/ec/static/" + path)
        return if (resource.exists()) {
            val bytes = resource.inputStream.use { it.readAllBytes() }
            val headers = HttpHeaders()
            if (path.endsWith(".png")) headers.contentType = MediaType.IMAGE_PNG else headers.contentType = MediaType.APPLICATION_OCTET_STREAM
            ResponseEntity(bytes, headers, HttpStatus.OK)
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlRenderer.renderError(404, "Asset not found").toByteArray())
        }
    }

    @RequestMapping("/**")
    @ResponseBody
    fun fallback(request: HttpServletRequest): ResponseEntity<String> {
        val path = request.requestURI
        if (path == "/ec" || path == "/ec/") return home()
        // Known prefixes already handled; everything else is 404
        log.warn("$path 404 not found")
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.TEXT_HTML)
            .body(htmlRenderer.renderError(404, "Not found"))
    }
}
