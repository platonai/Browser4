package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.PromptRequest
import ai.platon.pulsar.rest.api.service.ExtractService
import kotlinx.coroutines.*
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

@RestController
@CrossOrigin
@RequestMapping(
    "api/extractions",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ExtractionController(
    val extractService: ExtractService,
    val applicationScope: CoroutineScope,
) {
    private val extractionsCache = ConcurrentSkipListMap<String, String>()
    // Track tasks that are still being processed
    private val inProgress = ConcurrentHashMap.newKeySet<String>()

    @PostMapping("")
    suspend fun executeExtraction(@RequestBody request: PromptRequest): String {
        return extractService.extract(request)
    }

    @PostMapping("/async")
    suspend fun executeExtractionAsync(@RequestBody request: PromptRequest): String {
        val uuid = UUID.randomUUID().toString()
        inProgress.add(uuid)
        applicationScope.launch {
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
        applicationScope.launch {
            try {
                while (isActive) {
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
                    delay(1000)
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
