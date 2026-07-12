package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.browser.privacy.PrivacyManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.springframework.boot.info.GitProperties
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Properties

/**
 * The controller to handle www resources
 * */
@RestController
@CrossOrigin
@RequestMapping("api/system")
class SystemController(
    val session: PulsarSession,
    val driverPoolManager: WebDriverPoolManager,
    val privacyManager: PrivacyManager,
    val gitProperties: GitProperties? = null
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

    @GetMapping("build")
    fun build(): Map<String, Any?> {
        val version = readVersion()
        return mapOf(
            "version" to version,
            "gitCommitId" to gitProperties?.commitId,
            "gitCommitIdAbbrev" to gitProperties?.shortCommitId,
            "gitBranch" to gitProperties?.branch,
            "gitCommitTime" to gitProperties?.commitTime?.toString(),
            "buildTime" to Instant.now().toString()
        )
    }

    private fun readVersion(): String? {
        return try {
            val properties = Properties()
            val resource = Thread.currentThread().contextClassLoader
                .getResourceAsStream("META-INF/maven/ai.platon.pulsar/browser4-rest/pom.properties")
            resource?.use { properties.load(it) }
            properties.getProperty("version")
        } catch (e: Exception) {
            null
        }
    }
}
