package ai.platon.pulsar.rest.api.service.search

/**
 * Pluggable web search provider.
 *
 * Each implementation calls an external search API (Tavily, Bocha, etc.) and
 * returns raw results — no scraping.  [SearchService] orchestrates scraping so
 * providers stay single-responsibility.
 *
 * Implementations are Spring beans selected by `search.provider` via
 * `@ConditionalOnProperty`.
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
