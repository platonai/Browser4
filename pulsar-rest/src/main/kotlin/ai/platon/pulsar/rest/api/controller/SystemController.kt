package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.AbstractPrivacyManager
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The controller to handle www resources
 * */
@RestController
@CrossOrigin
@RequestMapping("api/system")
class SystemController(
    val session: PulsarSession,
    val driverPoolManager: WebDriverPoolManager,
    val privacyManager: AbstractPrivacyManager
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

    @GetMapping("hello")
    fun hello(): String {
        return "hello"
    }

    @GetMapping("report")
    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("Pulsar System Report")
        sb.appendLine(driverPoolManager.buildStatusString(true))
        sb.appendLine().appendLine()
        sb.appendLine(privacyManager.buildStatusString())
        return sb.toString()
    }
}
