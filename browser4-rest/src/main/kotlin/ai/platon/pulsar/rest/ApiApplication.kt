package ai.platon.pulsar.rest

import ai.platon.pulsar.agentic.llm.LlmConfigNormalizer
import ai.platon.pulsar.boot.autoconfigure.Browser4AutoConfiguration
import ai.platon.pulsar.boot.plugin.PluginClasspathEnhancer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import java.nio.file.Path

@SpringBootApplication
@Import(Browser4AutoConfiguration::class)
class ApiApplication

fun main(args: Array<String>) {
    PluginClasspathEnhancer.enhance(Path.of("plugins"))
    // Rewrite env-style LLM keys (DEEPSEEK_API_KEY=...) in conf-enabled
    // properties files to dotted keys before any session is created, so the
    // engine's `deepseek.api.key` lookups actually bind.
    LlmConfigNormalizer.normalize()
    runApplication<ApiApplication>(*args) {
        // Buffer startup steps so /actuator/startup can report per-phase
        // timing (bean init, auto-configuration evaluation, etc.).
        setApplicationStartup(BufferingApplicationStartup(4096))
        setAdditionalProfiles("rest", "private", "advanced")
        setLogStartupInfo(true)
    }
}
