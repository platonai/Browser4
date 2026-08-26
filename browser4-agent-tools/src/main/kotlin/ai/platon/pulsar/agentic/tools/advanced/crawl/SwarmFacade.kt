package ai.platon.pulsar.agentic.tools.advanced.crawl

/**
 * SPI for the swarm task backend.
 *
 * The REST layer keeps a thin `SwarmController` that validates requests and
 * delegates to the implementation registered in [SwarmFacadeRegistry]. The
 * implementation lives in the `browser4-swarm` plugin, keeping the swarm task
 * store, X-SQL scraping pipeline and JSONL persistence out of the core REST
 * module.
 *
 * When no facade is registered, the controller responds with a clear
 * "swarm not installed" error instead of a partial/broken implementation.
 */
interface SwarmFacade {
    /**
     * Submit a URL or X-SQL scrape task and return its task ID.
     */
    fun submit(request: ScrapeRequest): String

    /**
     * Submit a query-based scrape task and return its task ID.
     */
    fun submit(query: QueryRequest): String

    /**
     * Get the status/result of a task by ID.
     */
    fun getStatus(request: ScrapeStatusRequest): ScrapeResponse

    /**
     * Count tasks, optionally filtered by status code (0 = all).
     */
    fun count(statusCode: Int): Int

    /**
     * Abort all pending (non-terminal) tasks with a clear failure reason.
     *
     * Pending tasks belong to a live swarm session: once that session is closed
     * they can never be consumed again, so they must not stay "queued" forever
     * (which also leaks them across sessions and restarts). This marks them as
     * failed with SC_GONE and persists the transition.
     *
     * @param reason the human-readable reason to record in the response message
     * @return the number of aborted tasks
     */
    fun abortPendingTasks(reason: String): Int
}
