package ai.platon.pulsar.test.server.ec

import org.springframework.stereotype.Service

@Service
class CatalogService(private val catalogLoader: CatalogLoader) {
    fun allCategories(): List<Category> = catalogLoader.allCategories()
    fun getCategory(id: String): Category? = catalogLoader.getCategory(id)
    fun getProductsByCategory(id: String): List<Product> = catalogLoader.getProductsByCategory(id).sortedBy { it.id }
    fun getProduct(id: String): Product? = catalogLoader.getProduct(id)
    fun allProducts(): List<Product> = catalogLoader.allProducts()
    /** Products with a "Bestseller" badge, up to [limit]. */
    fun getBestsellers(limit: Int = 6): List<Product> =
        catalogLoader.allProducts().filter { p ->
            p.badges.orEmpty().any { it.equals("Bestseller", ignoreCase = true) }
        }.take(limit)
}
