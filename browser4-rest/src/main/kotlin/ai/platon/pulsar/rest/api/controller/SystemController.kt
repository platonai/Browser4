package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.inference.RequestTokenLimiter
import ai.platon.pulsar.browser.privacy.PrivacyManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.springframework.boot.info.GitProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Properties

/**
 * System-level endpoints — health checks, diagnostics, and greetings.
 */
@RestController
@CrossOrigin
@RequestMapping("api/system")
class SystemController(
    val session: PulsarSession,
    val driverPoolManager: WebDriverPoolManager,
    val privacyManager: PrivacyManager,
    val agenticContext: AgenticContext,
    val gitProperties: GitProperties? = null
) {
    @GetMapping("health")
    fun health(): Map<String, String> {
        return if (session.context.isActive) {
            mapOf("status" to "healthy")
        } else {
            mapOf("status" to "unhealthy")
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

    /**
     * Report the per-request LLM token limit: the configured baseline, any
     * runtime override, and the effective value actually enforced.
     */
    @GetMapping("token-limit")
    fun getTokenLimit(): Map<String, Any?> {
        val configured = RequestTokenLimiter.from(agenticContext.configuration).maxTokens
        val override = RequestTokenLimiter.currentOverride()
        val effective = override ?: configured
        return mapOf(
            "configKey" to RequestTokenLimiter.CONFIG_KEY,
            "default" to RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS,
            "configured" to configured,
            "override" to override,
            "effective" to effective,
            "unlimited" to (effective <= 0)
        )
    }

    /**
     * Set a runtime override for the per-request LLM token limit — the way
     * for the operator to allow a halted task to continue. Takes effect
     * immediately for all agent runs; not persisted across restarts.
     *
     * Accepted values: a positive token count (e.g. `800000`), `0` or
     * `unlimited` (disable enforcement).
     */
    @PutMapping("token-limit/{value}")
    fun setTokenLimit(@PathVariable value: String): ResponseEntity<Map<String, Any?>> {
        val v = value.trim().lowercase()
        val parsed = when {
            v == "unlimited" -> 0
            else -> v.toIntOrNull()?.takeIf { it >= 0 }
        } ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            mapOf(
                "error" to "Invalid token limit '$value'",
                "hint" to "Use a non-negative integer (e.g. 800000), 0, or 'unlimited'"
            )
        )
        RequestTokenLimiter.setOverride(parsed)
        return ResponseEntity.ok(getTokenLimit() + ("message" to "Runtime override set; effective immediately"))
    }

    /**
     * Clear the runtime override, falling back to configuration values.
     */
    @DeleteMapping("token-limit")
    fun resetTokenLimit(): Map<String, Any?> {
        RequestTokenLimiter.clearOverride()
        return getTokenLimit() + ("message" to "Runtime override cleared; using configuration values")
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
