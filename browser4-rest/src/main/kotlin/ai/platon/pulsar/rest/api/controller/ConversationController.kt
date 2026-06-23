package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.PromptRequest
import ai.platon.pulsar.rest.api.service.AsyncTaskCache
import ai.platon.pulsar.rest.api.service.ConversationService
import kotlinx.coroutines.*
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@CrossOrigin
@RequestMapping(
    "api/conversations",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class ConversationController(
    val conversationService: ConversationService,
    val applicationScope: CoroutineScope,
) {
    private val taskCache = AsyncTaskCache<String>(applicationScope)

    @GetMapping("")
    suspend fun conversations(@RequestParam(value = "prompt") prompt: String): String {
        return conversationService.chat(prompt)
    }

    @GetMapping("/async")
    fun conversationsAsync(@RequestParam(value = "prompt") prompt: String): String {
        return taskCache.submit("conversation") { conversationService.chat(prompt) }
    }

    @PostMapping("")
    suspend fun conversationsPost(@RequestBody prompt: String): String {
        return conversationService.chat(prompt)
    }

    @PostMapping("/async")
    fun conversationsPostAsync(@RequestBody prompt: String): String {
        return taskCache.submit("conversation") { conversationService.chat(prompt) }
    }

    @PostMapping("/about")
    suspend fun conversationsAbout(@RequestBody request: PromptRequest): String {
        return conversationService.chat(request)
    }

    @PostMapping("/about/async")
    fun conversationsAboutAsync(@RequestBody request: PromptRequest): String {
        return taskCache.submit("conversation") { conversationService.chat(request) }
    }

    @GetMapping("/{id}")
    fun conversationResult(@PathVariable id: String): String {
        return taskCache.get(id) ?: "No result found for id: $id"
    }

    @GetMapping("/{id}/status")
    fun conversationStatus(@PathVariable id: String): String {
        return taskCache.status(id)
    }

    @GetMapping("/{id}/stream")
    fun conversationStream(@PathVariable id: String): SseEmitter {
        return taskCache.stream(id, "No result found for id: $id")
    }
}
