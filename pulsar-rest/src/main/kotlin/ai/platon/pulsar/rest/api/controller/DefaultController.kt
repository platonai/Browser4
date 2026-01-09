package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.skeleton.session.PulsarSession
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@CrossOrigin
@RequestMapping("/")
class DefaultController(
    val session: PulsarSession
) {
    @GetMapping("health")
    fun health(): Map<String, String> {
        return if (session.context.isActive) {
            mapOf(
                "status" to "healthy"
            )
        } else {
            mapOf(
                "status" to "unhealthy"
            )
        }
    }
}
