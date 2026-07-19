package ai.platon.pulsar.test.server.ec

data class CatalogMeta(
    val version: Int,
    val generatedAt: String,
    val seed: Long?
)

data class Category(
    val id: String,
    val name: String,
    val slug: String
)

data class Product(
    val id: String,
    val name: String,
    val categoryId: String,
    val price: Double,
    val currency: String = "USD",
    val image: String? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val badges: List<String>? = emptyList(),
    val features: List<String>? = emptyList(),
    val description: String? = null,
    val specs: Map<String, String>? = emptyMap(),
    val inventory: Map<String, Any>? = emptyMap(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class Catalog(
    val meta: CatalogMeta,
    val categories: List<Category>,
    val products: List<Product>
)
