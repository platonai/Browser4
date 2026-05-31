package ai.platon.pulsar.rest

import ai.platon.browser4.boot.autoconfigure.AgenticContextInitializer
import ai.platon.browser4.boot.autoconfigure.Browser4AutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(Browser4AutoConfiguration::class)
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args) {
        addInitializers(AgenticContextInitializer())
        setAdditionalProfiles("rest", "private", "advanced")
        setLogStartupInfo(true)
    }
}
