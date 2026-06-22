package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.PromptRequest
import ai.platon.pulsar.rest.api.service.AsyncTaskCache
import ai.platon.pulsar.rest.api.service.ExtractService
import kotlinx.coroutines.*
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

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
    private val taskCache = AsyncTaskCache<String>(applicationScope)

    @PostMapping("")
    suspend fun executeExtraction(@RequestBody request: PromptRequest): String {
        return extractService.extract(request)
    }

    @PostMapping("/async")
    suspend fun executeExtractionAsync(@RequestBody request: PromptRequest): String {
        return taskCache.submit("extraction") { extractService.extract(request) }
    }

    @GetMapping("/{uuid}")
    fun extractionResult(@PathVariable uuid: String): String {
        return taskCache.get(uuid) ?: "Extraction not found"
    }

    @GetMapping("/{uuid}/status")
    fun extractionStatus(@PathVariable uuid: String): String {
        return taskCache.status(uuid)
    }

    @GetMapping("/{uuid}/stream")
    fun extractionStream(@PathVariable uuid: String): SseEmitter {
        return taskCache.stream(uuid, "Extraction not found")
    }
}
