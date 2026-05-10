package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.PromptRequest
import ai.platon.pulsar.rest.api.service.ConversationService
import kotlinx.coroutines.*
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.ConcurrentSkipListMap

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
    private val conversationsCache = ConcurrentSkipListMap<String, String>()

    @GetMapping("")
    suspend fun conversations(@RequestParam(value = "prompt") prompt: String): String {
        return conversationService.chat(prompt)
    }

    @GetMapping("/async")
    fun conversationsAsync(@RequestParam(value = "prompt") prompt: String): String {
        val id = UUID.randomUUID().toString()
        applicationScope.launch {
            conversationsCache[id] = conversationService.chat(prompt)
        }
        return id
    }

    @PostMapping("")
    suspend fun conversationsPost(@RequestBody prompt: String): String {
        return conversationService.chat(prompt)
    }

    // async version
    @PostMapping("/async")
    fun conversationsPostAsync(@RequestBody prompt: String): String {
        val id = UUID.randomUUID().toString()
        applicationScope.launch {
            conversationsCache[id] = conversationService.chat(prompt)
        }
        return id
    }

    @PostMapping("/about")
    suspend fun conversationsAbout(@RequestBody request: PromptRequest): String {
        return conversationService.chat(request)
    }

    @PostMapping("/about/async")
    fun conversationsAboutAsync(@RequestBody request: PromptRequest): String {
        val id = UUID.randomUUID().toString()
        applicationScope.launch {
            conversationsCache[id] = conversationService.chat(request)
        }
        return id
    }

    @GetMapping("/{id}")
    fun conversationResult(@PathVariable id: String): String {
        return conversationsCache[id] ?: "No result found for id: $id"
    }

    @GetMapping("/{id}/status")
    fun conversationStatus(@PathVariable id: String): String {
        return if (conversationsCache.containsKey(id)) {
            "Processing"
        } else {
            "Completed"
        }
    }

    @GetMapping("/{id}/stream")
    fun conversationStream(@PathVariable id: String): SseEmitter {
        val emitter = SseEmitter()
        applicationScope.launch {
            try {
                val result = conversationsCache[id]
                if (result != null) {
                    emitter.send(result)
                } else {
                    emitter.send("No result found for id: $id")
                }
            } catch (e: Exception) {
                emitter.completeWithError(e)
            } finally {
                emitter.complete()
            }
        }
        return emitter
    }
}
