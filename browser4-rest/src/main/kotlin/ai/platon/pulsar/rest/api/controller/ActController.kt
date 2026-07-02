package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.rest.api.service.ActService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class ActRequest(val description: String)

data class ActResponse(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
)

@RestController
@CrossOrigin
@RequestMapping("api", produces = [MediaType.APPLICATION_JSON_VALUE])
class ActController(
    private val actService: ActService,
) {
    private val logger = getLogger(ActController::class)

    @PostMapping("/act", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun act(@RequestBody request: ActRequest): ResponseEntity<ActResponse> {
        val description = request.description.trim()
        if (description.isBlank()) {
            return ResponseEntity.badRequest().body(
                ActResponse(success = false, error = "Description must not be blank")
            )
        }

        return try {
            val output = actService.translateAndExecute(description)
            ResponseEntity.ok(ActResponse(success = true, output = output))
        } catch (e: Exception) {
            logger.error("Act command failed: {}", e.message, e)
            ResponseEntity.ok(
                ActResponse(success = false, error = e.message ?: "Act command failed")
            )
        }
    }
}
