package ai.platon.pulsar.rest

import ai.platon.pulsar.boot.autoconfigure.PulsarAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(PulsarAutoConfiguration::class)
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args) {
        addInitializers(PulsarContextInitializer())
        setAdditionalProfiles("rest", "private", "advanced")
        setLogStartupInfo(true)
    }
}
