package ai.platon.pulsar.test.server.ec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
class CatalogLoader {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val catalog: Catalog = load()
    private val categoriesById = catalog.categories.associateBy { it.id }
    private val productsById = catalog.products.associateBy { it.id }
    private val productsByCategory = catalog.products.groupBy { it.categoryId }

    fun getCategory(id: String) = categoriesById[id]
    fun getProduct(id: String) = productsById[id]
    fun getProductsByCategory(id: String) = productsByCategory[id].orEmpty()
    fun allCategories() = catalog.categories

    private fun load(): Catalog {
        val resourcePath = "/static/generated/mock-amazon/data/products.json"
        val stream = javaClass.getResourceAsStream(resourcePath)
            ?: error("Catalog data file not found at $resourcePath")
        val json = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val c: Catalog = objectMapper.readValue(json)
        log.info("Loaded EC catalog: categories={}, products={}, seed={}", c.categories.size, c.products.size, c.meta.seed)
        return c
    }
}
