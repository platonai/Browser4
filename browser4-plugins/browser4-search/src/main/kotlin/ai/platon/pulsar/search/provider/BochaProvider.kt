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
 * Bocha (博查) based [SearchProvider].
 *
 * Calls `POST {base}` with a JSON body and `Authorization: Bearer <key>`.
 *
 * Requires `search.bocha.api.key` (or the `BOCHA_API_KEY` env var).
 *
 * This class is intentionally free of Spring annotations —
 * [ai.platon.pulsar.search.config.SearchAutoConfiguration] instantiates it
 * when [ai.platon.pulsar.search.SearchConfig.primaryProvider] is
 * [ai.platon.pulsar.search.SearchServiceProvider.BOCHA].
 */
class BochaProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.bochaai.com/v1/web-search",
) : SearchProvider {

    override val name: String = "bocha"

    private val logger = LoggerFactory.getLogger(BochaProvider::class.java)
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
