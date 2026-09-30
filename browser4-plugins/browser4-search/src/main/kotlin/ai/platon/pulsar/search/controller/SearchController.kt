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
package ai.platon.pulsar.search.controller

import ai.platon.pulsar.search.service.SearchRequest
import ai.platon.pulsar.search.service.SearchResponse
import ai.platon.pulsar.search.service.SearchService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for the search API.
 *
 * Mirrors [ai.platon.pulsar.rest.api.controller.CrawlController]'s shape: POST
 * submits a task and returns the UUID for polling; GET `/{id}/status` and
 * `/{id}/result` read it back; POST `/{id}/cancel` stops a running task.
 * Terminal tasks can be dropped with `/clear` or `/clear-all` (cancels running
 * tasks too).
 *
 * The `@RestController` annotation is recognised by Spring MVC's
 * `RequestMappingHandlerMapping` once the bean is registered by
 * [ai.platon.pulsar.search.config.SearchAutoConfiguration] — that is the
 * standard plugin pattern used when the controller lives outside the host's
 * `@ComponentScan` base packages.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "api/search",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SearchController(
    val searchService: SearchService
) {
    private val logger = LoggerFactory.getLogger(SearchController::class.java)

    /**
     * Submit a search task. Returns the task UUID immediately; poll
     * [getStatus]/[getResult] to read the outcome.
     */
    @PostMapping
    fun submitSearch(@RequestBody request: SearchRequest): String {
        if (request.query.isBlank()) {
            throw IllegalArgumentException("query must not be blank")
        }
        if (request.maxResults <= 0) {
            throw IllegalArgumentException("maxResults must be > 0, got ${request.maxResults}")
        }
        logger.info(
            "Search request: query='{}' maxResults={} scrape={} topic={} timeRange={} timeout={}ms",
            request.query, request.maxResults, request.scrape, request.topic,
            request.timeRange, request.taskTimeoutMillis
        )
        return searchService.submit(request)
    }

    /**
     * Get the status or partial result of a search task.
     */
    @GetMapping("/{id}/status", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(@PathVariable(value = "id") taskId: String): SearchResponse {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        return searchService.getResult(taskId)
    }

    /**
     * Get the final result of a search task. Same as [getStatus] but semantically
     * indicates the caller expects the task to be finished.
     */
    @GetMapping("/{id}/result", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getResult(@PathVariable(value = "id") taskId: String): SearchResponse {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        return searchService.getResult(taskId)
    }

    /**
     * Cancel a running search task.
     *
     * @return `cancelled=true` if the task was found and cancelled; `false` otherwise.
     */
    @PostMapping("/{id}/cancel", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun cancelSearch(@PathVariable(value = "id") taskId: String): Map<String, Any> {
        if (taskId.isBlank()) {
            throw IllegalArgumentException("id must not be blank")
        }
        val cancelled = searchService.cancel(taskId)
        return mapOf("taskId" to taskId, "cancelled" to cancelled)
    }

    /**
     * Remove all terminal-state tasks from the search task store.
     */
    @PostMapping("/clear", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearSearches(): Map<String, Any> {
        val count = searchService.clearTerminal()
        return mapOf("cleared" to count)
    }

    /**
     * Remove ALL tasks from the search task store, including actively-running ones.
     * Cancels running jobs before clearing.  Use with caution.
     */
    @PostMapping("/clear-all", consumes = [MediaType.ALL_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearAllSearches(): Map<String, Any> {
        val count = searchService.clearAll()
        return mapOf("cleared" to count)
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): Map<String, Any> {
        logger.warn("Bad search request: {}", e.message)
        return mapOf("error" to "Bad Request", "message" to (e.message ?: ""))
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(e: IllegalStateException): Map<String, Any> {
        logger.warn("Search request conflict: {}", e.message)
        return mapOf("error" to "Conflict", "message" to (e.message ?: ""))
    }
}
