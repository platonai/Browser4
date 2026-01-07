package ai.platon.pulsar.crawl.api.controller

import ai.platon.pulsar.crawl.api.entities.PromptRequest
import ai.platon.pulsar.crawl.api.service.ExtractService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@RestController
@CrossOrigin
@RequestMapping(
    "api/extractions",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ExtractionController(
    val extractService: ExtractService,
) {
    private val extractionsCache = ConcurrentSkipListMap<String, String>()
    // Track tasks that are still being processed
    private val inProgress = ConcurrentHashMap.newKeySet<String>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val coroutineDispatcher = executor.asCoroutineDispatcher()
    private val coroutineScope = CoroutineScope(coroutineDispatcher)

    @PostMapping("")
    suspend fun executeExtraction(@RequestBody request: PromptRequest): String {
        return extractService.extract(request)
    }

    @PostMapping("/async")
    suspend fun executeExtractionAsync(@RequestBody request: PromptRequest): String {
        val uuid = UUID.randomUUID().toString()
        inProgress.add(uuid)
        coroutineScope.launch {
            try {
                val result = extractService.extract(request)
                extractionsCache[uuid] = result
            } catch (e: Exception) {
                extractionsCache[uuid] = "Error: ${e.message}"
            } finally {
                inProgress.remove(uuid)
            }
        }
        return uuid
    }

    @GetMapping("/{uuid}")
    fun extractionResult(@PathVariable uuid: String): String {
        return extractionsCache[uuid] ?: "Extraction not found"
    }

    @GetMapping("/{uuid}/status")
    fun extractionStatus(@PathVariable uuid: String): String {
        return when {
            inProgress.contains(uuid) -> "Processing"
            extractionsCache.containsKey(uuid) -> "Completed"
            else -> "Extraction not found"
        }
    }

    @GetMapping("/{uuid}/stream")
    fun extractionStream(@PathVariable uuid: String): SseEmitter {
        // 0L -> no timeout; consider a sensible timeout in production
        val emitter = SseEmitter(0L)
        executor.submit {
            try {
                while (true) {
                    val result = extractionsCache[uuid]
                    if (result != null) {
                        emitter.send(result)
                        break
                    }

                    if (!inProgress.contains(uuid)) {
                        emitter.send("Extraction not found")
                        break
                    }

                    // Send a heartbeat/status update while processing
                    emitter.send(SseEmitter.event().name("status").data("Processing").reconnectTime(1000))
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                try {
                    emitter.send("Error: ${e.message}")
                } catch (_: Exception) {
                }
                emitter.completeWithError(e)
                return@submit
            } finally {
                emitter.complete()
            }
        }
        return emitter
    }
}
