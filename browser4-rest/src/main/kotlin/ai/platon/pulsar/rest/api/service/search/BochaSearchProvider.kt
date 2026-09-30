package ai.platon.pulsar.rest.api.service.search

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Bocha (博查) based [SearchProvider].
 *
 * Calls `POST {base}` with a JSON body and `Authorization: Bearer <key>`.
 * Enabled when `search.provider=bocha`.
 *
 * Requires `BOCHA_API_KEY` (env var) or `search.bocha.api.key` (properties).
 */
@Component("bochaSearchProvider")
@ConditionalOnProperty(name = ["search.provider"], havingValue = "bocha")
class BochaSearchProvider(
    @Value("\${search.bocha.api.key:\${BOCHA_API_KEY:}}") private val apiKey: String = "",
    @Value("\${search.bocha.base.url:https://api.bochaai.com/v1/web-search}") private val baseUrl: String = "https://api.bochaai.com/v1/web-search",
) : SearchProvider {

    override val name: String = "bocha"

    private val logger = LoggerFactory.getLogger(BochaSearchProvider::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    override suspend fun search(request: SearchRequest): List<SearchResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Bocha API key not configured (set BOCHA_API_KEY or search.bocha.api.key)")
        }

        val body = buildRequestBody(request)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Bocha API returned ${response.statusCode()}: ${response.body().take(500)}")
        }

        parseResults(response.body()).also {
            logger.debug("Bocha search '{}' returned {} results", request.query, it.size)
        }
    }

    private fun buildRequestBody(request: SearchRequest): String {
        val map = mutableMapOf<String, Any>(
            "query" to request.query,
            "count" to request.maxResults.coerceIn(1, 50),
        )
        request.timeRange?.let { map["freshness"] = it }
        request.includeDomains?.let { map["siteSearch"] = it }
        return objectMapper.writeValueAsString(map)
    }

    private fun parseResults(json: String): List<SearchResult> {
        val root = objectMapper.readValue<Map<String, Any?>>(json)
        // Bocha returns data.webPages.value[] — handle both nested and flat shapes.
        @Suppress("UNCHECKED_CAST")
        val data = root["data"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val webPages = data?.get("webPages") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val results = (webPages?.get("value") as? List<Map<String, Any?>>)
            ?: (root["results"] as? List<Map<String, Any?>>)
            ?: emptyList()
        return results.map { r ->
            SearchResult(
                url = r["url"] as? String ?: r["link"] as? String ?: "",
                title = r["name"] as? String ?: r["title"] as? String ?: "",
                content = r["snippet"] as? String ?: r["content"] as? String,
                score = (r["score"] as? Number)?.toDouble(),
            )
        }
    }
}
