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
        return redirectTo("/status.html")
    }

    /**
     * `/agent` → the agent console page.
     *
     * The console is a single static page (`agent.html`) that drives the same
     * `agent run -i` session over the REST API: submit, live steps, steering,
     * cancel, result. The short route exists so a terminal user who only saw the
     * `agent` prefix does not have to remember the `.html` suffix.
     */
    @GetMapping("agent")
    fun agentConsole(): ResponseEntity<Unit> {
        return redirectTo("/agent.html")
    }

    private fun redirectTo(location: String): ResponseEntity<Unit> {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(location))
            .build()
    }
}
