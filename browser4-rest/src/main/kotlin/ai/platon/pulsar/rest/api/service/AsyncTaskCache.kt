package ai.platon.pulsar.rest.api.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Generic async task cache that encapsulates submission, status tracking,
 * result retrieval, and SSE streaming for background tasks.
 *
 * Eliminates the duplicated async pattern previously spread across
 * [ai.platon.pulsar.rest.api.controller.ConversationController] and
 * [ai.platon.pulsar.rest.api.controller.ExtractionController].
 */
class AsyncTaskCache<T>(
    private val scope: CoroutineScope,
    private val pollingIntervalMs: Long = 1000L,
) {
    private val cache = ConcurrentSkipListMap<String, T>()
    private val inProgress = ConcurrentHashMap.newKeySet<String>()

    /**
     * Submit a task for async execution and return its UUID immediately.
     */
    fun submit(taskName: String = "task", block: suspend () -> T): String {
        val id = UUID.randomUUID().toString()
        inProgress.add(id)
        scope.launch {
            try {
                cache[id] = block()
            } catch (e: Exception) {
                @Suppress("UNCHECKED_CAST")
                cache[id] = "Error: ${e.message}" as T
            } finally {
                inProgress.remove(id)
            }
        }
        return id
    }

    /**
     * Get the cached result, or null if not found.
     */
    fun get(id: String): T? = cache[id]

    /**
     * Get a human-readable status for the task.
     */
    fun status(id: String): String = when {
        inProgress.contains(id) -> "Processing"
        cache.containsKey(id) -> "Completed"
        else -> "Not found"
    }

    /**
     * Create an SSE stream that polls for the task result.
     * Emits the result when available, with heartbeat status updates in between.
     */
    fun stream(id: String, notFoundMessage: String = "Not found"): SseEmitter {
        val emitter = SseEmitter(0L)
        scope.launch {
            try {
                while (isActive) {
                    val result = cache[id]
                    if (result != null) {
                        emitter.send(result.toString())
                        break
                    }

                    if (!inProgress.contains(id)) {
                        emitter.send(notFoundMessage)
                        break
                    }

                    emitter.send(
                        SseEmitter.event()
                            .name("status")
                            .data("Processing")
                            .reconnectTime(1000)
                    )
                    delay(pollingIntervalMs.milliseconds)
                }
            } catch (e: Exception) {
                try {
                    emitter.send("Error: ${e.message}")
                } catch (_: Exception) {
                }
                emitter.completeWithError(e)
                return@launch
            } finally {
                emitter.complete()
            }
        }
        return emitter
    }
}
