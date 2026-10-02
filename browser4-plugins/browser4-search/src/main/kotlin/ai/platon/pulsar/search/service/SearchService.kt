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

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmSessionProvider
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlinkFactory
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Search task lifecycle: submission, provider dispatch, optional scraping,
 * progress reporting and the terminal record of a search.
 *
 * Mirrors [ai.platon.pulsar.rest.api.service.crawl.CrawlService] in shape:
 * [submit] returns a task id immediately and [getResult] is polled for the
 * outcome.  The search itself is fast (seconds), so the CLI usually polls once
 * and gets the result; `--scrape` mode is slower (one browser tab per result)
 * and benefits from the async pattern.
 *
 * This class is intentionally free of Spring annotations —
 * [ai.platon.pulsar.search.config.SearchAutoConfiguration] instantiates it
 * and binds the active [SearchProvider] (selected via `search.provider`).
 *
 * The session is consumed through [SwarmSessionProvider] — the same fun
 * interface used by the swarm plugin — so this plugin does not depend on
 * any REST module class. The host (browser4-rest) provides the bean that
 * implements it.
 *
 * @param provider the active [SearchProvider], selected by `search.provider`.
 * @param defaultTaskTimeoutMillis per-task budget when the request does not
 *   override it via [SearchRequest.taskTimeoutMillis].
 */
class SearchService(
    private val sessionProvider: SwarmSessionProvider,
    private val provider: SearchProvider,
    private val defaultTaskTimeoutMillis: Long = DEFAULT_TASK_TIMEOUT_MS,
) {
    private val logger = LoggerFactory.getLogger(SearchService::class.java)

    companion object {
        const val DEFAULT_TASK_TIMEOUT_MS = 60_000L
        const val MAX_TASK_TIMEOUT_MS = 600_000L
    }

    /** Task store: taskId -> SearchResponse.  Size-bounded at 100 entries. */
    private val taskStore: Cache<String, SearchResponse> = Caffeine.newBuilder()
        .maximumSize(100)
        .recordStats()
        .build()

    /** Active coroutine jobs: taskId -> Job (for cancellation). */
    private val jobStore = ConcurrentHashMap<String, Job>()

    /** Terminal task states. */
    private val terminalStatuses = SearchStatus.TERMINAL

    private val searchScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(8) + SupervisorJob() + CoroutineName("search")
    )

    /**
     * Submit a search task. Returns the task ID immediately; the search runs
     * asynchronously.  Poll [getResult] to retrieve the final response.
     */
    fun submit(request: SearchRequest): String {
        val taskId = UUID.randomUUID().toString()
        val now = Instant.now()
        val response = SearchResponse(
            taskId = taskId,
            status = SearchStatus.CREATED,
            query = request.query,
            createdAt = System.currentTimeMillis(),
            startedTime = now,
        )
        taskStore.put(taskId, response)

        val timeoutMs = resolveTimeoutMillis(request)

        val job = searchScope.launch {
            val working = taskStore.getIfPresent(taskId)?.copy(status = SearchStatus.PROCESSING) ?: return@launch
            taskStore.put(taskId, working)

            try {
                withTimeout(timeoutMs) {
                    val results = provider.search(request)

                    val finalResults = if (request.scrape) {
                        scrapeResults(results, request.scrapeFormat)
                    } else {
                        results
                    }

                    val done = working.copy(
                        status = SearchStatus.OK,
                        results = finalResults,
                        pagesFound = finalResults.size,
                        finishTime = Instant.now(),
                    )
                    taskStore.put(taskId, done)
                    logger.info("Search task {} completed: {} results", taskId, finalResults.size)
                }
            } catch (e: TimeoutCancellationException) {
                val done = working.copy(
                    status = SearchStatus.REQUEST_TIMEOUT,
                    error = "Search timed out after ${timeoutMs}ms",
                    finishTime = Instant.now(),
                )
                taskStore.put(taskId, done)
                logger.warn("Search task {} timed out after {}ms", taskId, timeoutMs)
            } catch (e: Exception) {
                val done = working.copy(
                    status = SearchStatus.INTERNAL_SERVER_ERROR,
                    error = e.message ?: e.toString(),
                    finishTime = Instant.now(),
                )
                taskStore.put(taskId, done)
                logger.error("Search task {} failed: {}", taskId, e.message, e)
            }
        }
        jobStore[taskId] = job

        logger.info(
            "Search task submitted: id={} query='{}' scrape={} timeout={}ms",
            taskId, request.query, request.scrape, timeoutMs
        )

        return taskId
    }

    /**
     * Get the current status/result of a search task.
     */
    fun getResult(taskId: String): SearchResponse {
        taskStore.getIfPresent(taskId)?.let { return it }
        return SearchResponse(
            taskId = taskId,
            status = SearchStatus.NOT_FOUND,
            error = "Task not found: $taskId"
        )
    }

    /**
     * Cancel a running search task by its ID.
     *
     * @return true if the task was found and cancelled, false otherwise.
     */
    fun cancel(taskId: String): Boolean {
        val job = jobStore.remove(taskId) ?: return false
        job.cancel()
        val previous = taskStore.getIfPresent(taskId)
        val cancelled = (previous ?: SearchResponse(taskId = taskId)).copy(
            status = SearchStatus.REQUEST_TIMEOUT,
            error = "Cancelled by user",
            finishTime = Instant.now(),
        )
        taskStore.put(taskId, cancelled)
        logger.info("Search task {} cancelled by user", taskId)
        return true
    }

    /** Number of tasks with active coroutines. */
    fun runningTaskCount(): Int = jobStore.size

    /**
     * Remove all terminal-state tasks from the store.
     *
     * @return the number of tasks removed.
     */
    fun clearTerminal(): Int {
        val toRemove = taskStore.asMap().entries.filter { it.value.status in terminalStatuses }
        toRemove.forEach { taskStore.invalidate(it.key) }
        logger.info("Cleared {} terminal search tasks", toRemove.size)
        return toRemove.size
    }

    /**
     * Remove ALL tasks from the store, including actively-running ones.
     * Cancels running jobs before clearing.
     *
     * @return the number of tasks removed.
     */
    fun clearAll(): Int {
        jobStore.values.forEach { it.cancel() }
        jobStore.clear()
        val size = taskStore.asMap().size
        taskStore.invalidateAll()
        logger.info("Cleared all {} search tasks", size)
        return size
    }

    @PreDestroy
    fun shutdown() {
        searchScope.cancel()
    }

    private fun resolveTimeoutMillis(request: SearchRequest): Long {
        val requested = request.taskTimeoutMillis
        return when {
            requested == null || requested <= 0 -> defaultTaskTimeoutMillis
            requested > MAX_TASK_TIMEOUT_MS -> MAX_TASK_TIMEOUT_MS
            else -> requested
        }
    }

    /**
     * Scrape each result URL via the browser session and populate
     * [SearchResult.scrapedContent].
     *
     * Uses the same X-SQL pattern as ScrapeService: a `load_and_select` query
     * that loads the page and extracts the content.  One bad URL never aborts
     * the whole task — the error is recorded per-result.
     */
    private suspend fun scrapeResults(
        results: List<SearchResult>,
        format: String,
    ): List<SearchResult> = coroutineScope {
        results.map { result ->
            async(Dispatchers.IO) {
                try {
                    val content = scrapeUrl(result.url, format)
                    result.copy(scrapedContent = content)
                } catch (e: Exception) {
                    logger.warn("Scrape failed for {}: {}", result.url, e.message)
                    result.copy(scrapeError = e.message ?: e.toString())
                }
            }
        }.awaitAll()
    }

    private suspend fun scrapeUrl(url: String, format: String): String = withContext(Dispatchers.IO) {
        val session = sessionProvider.session()
        val contentField = if (format == "html") "dom_html(dom)" else "dom_markdown(dom)"
        val sql = "select $contentField as content from load_and_select('$url', ':root')"

        val request = ScrapeRequest(sql = sql)
        val hyperlink = ScrapeHyperlinkFactory.create(request, session) { }
        session.submit(hyperlink)
        val response = hyperlink.get(120, TimeUnit.SECONDS)

        @Suppress("UNCHECKED_CAST")
        val resultSet = response.resultSet
        if (resultSet.isNullOrEmpty()) {
            throw IllegalStateException("Empty result from scrape of $url")
        }
        val content = resultSet.first()["content"] as? String
        return@withContext content ?: throw IllegalStateException("No content field in scrape result for $url")
    }
}
