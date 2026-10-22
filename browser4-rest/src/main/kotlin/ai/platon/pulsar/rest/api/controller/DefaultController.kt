package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.skeleton.session.PulsarSession
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * Root-level endpoints, including the health check probe.
 */
@RestController
@CrossOrigin
@RequestMapping("/")
class DefaultController(
    val session: PulsarSession
) {
    @GetMapping("health")
    fun health(): Map<String, String> {
        return if (session.context.isActive) {
            mapOf("status" to "healthy")
        } else {
            mapOf("status" to "unhealthy")
        }
    }

    @GetMapping("status")
    fun statusPanel(): ResponseEntity<Unit> {
        // @RestController: a String return value is the response body, NOT a view name, and the
        // "redirect:" prefix is never interpreted. Return a real HTTP redirect here so the browser
        // actually follows it to the static status panel.
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create("/status.html"))
            .build()
    }
}
