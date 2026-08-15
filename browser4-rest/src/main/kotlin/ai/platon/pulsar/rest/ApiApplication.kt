package ai.platon.pulsar.rest

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
    runApplication<ApiApplication>(*args) {
        // Buffer startup steps so /actuator/startup can report per-phase
        // timing (bean init, auto-configuration evaluation, etc.).
        setApplicationStartup(BufferingApplicationStartup(4096))
        setAdditionalProfiles("rest", "private", "advanced")
        setLogStartupInfo(true)
    }
}
