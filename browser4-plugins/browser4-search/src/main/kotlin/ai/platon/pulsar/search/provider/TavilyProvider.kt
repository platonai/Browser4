/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.search.provider

import ai.platon.pulsar.search.service.SearchProvider
import ai.platon.pulsar.search.service.SearchRequest
import ai.platon.pulsar.search.service.SearchResult
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Tavily-based [SearchProvider].
 *
 * Calls `POST {base}/search` with a JSON body and `Authorization: Bearer <key>`.
 *
 * Requires `search.tavily.api.key` (or the `TAVILY_API_KEY` env var).
 *
 * This class is intentionally free of Spring annotations —
 * [ai.platon.pulsar.search.config.SearchAutoConfiguration] instantiates it
 * when [ai.platon.pulsar.search.SearchConfig.primaryProvider] is
 * [ai.platon.pulsar.search.SearchServiceProvider.TAVILY].
 */
class TavilyProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.tavily.com",
) : SearchProvider {

    override val name: String = "tavily"

    private val logger = LoggerFactory.getLogger(TavilyProvider::class.java)
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
