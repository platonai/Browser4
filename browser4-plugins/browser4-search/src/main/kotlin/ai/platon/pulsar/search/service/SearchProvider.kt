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
package ai.platon.pulsar.search.service

/**
 * Pluggable web search provider.
 *
 * Each implementation calls an external search API (Tavily, Bocha, etc.) and
 * returns raw results — no scraping.  [SearchService] orchestrates scraping so
 * providers stay single-responsibility.
 *
 * Implementations are created by [ai.platon.pulsar.search.config.SearchAutoConfiguration]
 * based on the active [SearchServiceProvider] selected via `search.provider`.
 */
interface SearchProvider {
    /** Short identifier: `tavily`, `bocha`, etc. */
    val name: String

    /**
     * Execute a web search and return the raw results.
     *
     * Throw on configuration errors (e.g. missing API key) so the service
     * surfaces them as [SearchStatus.INTERNAL_SERVER_ERROR] rather than
     * silently returning empty results.
     */
    suspend fun search(request: SearchRequest): List<SearchResult>
}
