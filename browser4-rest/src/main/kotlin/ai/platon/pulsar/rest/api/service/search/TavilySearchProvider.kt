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
 * Tavily-based [SearchProvider] — the default.
 *
 * Calls `POST {base}/search` with a JSON body and `Authorization: Bearer <key>`.
 * Enabled when `search.provider` is `tavily` or missing (default).
 *
 * Requires `TAVILY_API_KEY` (env var) or `search.tavily.api.key` (properties).
 */
@Component("tavilySearchProvider")
@ConditionalOnProperty(name = ["search.provider"], havingValue = "tavily", matchIfMissing = true)
class TavilySearchProvider(
    @Value("\${search.tavily.api.key:\${TAVILY_API_KEY:}}") private val apiKey: String = "",
    @Value("\${search.tavily.base.url:https://api.tavily.com}") private val baseUrl: String = "https://api.tavily.com",
) : SearchProvider {

    override val name: String = "tavily"

    private val logger = LoggerFactory.getLogger(TavilySearchProvider::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    override suspend fun search(request: SearchRequest): List<SearchResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Tavily API key not configured (set TAVILY_API_KEY or search.tavily.api.key)")
        }

        val body = buildRequestBody(request)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/search"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Tavily API returned ${response.statusCode()}: ${response.body().take(500)}")
        }

        parseResults(response.body()).also {
            logger.debug("Tavily search '{}' returned {} results", request.query, it.size)
        }
    }

    private fun buildRequestBody(request: SearchRequest): String {
        val map = mutableMapOf<String, Any>(
            "query" to request.query,
            "max_results" to request.maxResults.coerceIn(1, 50),
            "topic" to request.topic,
            "search_depth" to "advanced",
        )
        request.timeRange?.let { map["time_range"] = it }
        request.includeDomains?.let { map["include_domains"] = it }
        request.excludeDomains?.let { map["exclude_domains"] = it }
        return objectMapper.writeValueAsString(map)
    }

    private fun parseResults(json: String): List<SearchResult> {
        val root = objectMapper.readValue<Map<String, Any?>>(json)
        @Suppress("UNCHECKED_CAST")
        val results = root["results"] as? List<Map<String, Any?>> ?: return emptyList()
        return results.map { r ->
            SearchResult(
                url = r["url"] as? String ?: "",
                title = r["title"] as? String ?: "",
                content = r["content"] as? String,
                score = (r["score"] as? Number)?.toDouble(),
            )
        }
    }
}
