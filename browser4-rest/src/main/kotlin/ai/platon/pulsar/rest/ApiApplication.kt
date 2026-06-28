package ai.platon.pulsar.rest

import ai.platon.browser4.boot.autoconfigure.Browser4AutoConfiguration
import ai.platon.browser4.boot.plugin.PluginClasspathEnhancer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import java.nio.file.Path

@SpringBootApplication
@Import(Browser4AutoConfiguration::class)
class ApiApplication

fun main(args: Array<String>) {
    PluginClasspathEnhancer.enhance(Path.of("plugins"))
    runApplication<ApiApplication>(*args) {
        setAdditionalProfiles("rest", "private", "advanced")
        setLogStartupInfo(true)
    }
}
