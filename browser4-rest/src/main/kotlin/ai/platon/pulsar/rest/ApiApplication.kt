package ai.platon.pulsar.rest

import ai.platon.browser4.boot.autoconfigure.Browser4AutoConfiguration
import ai.platon.browser4.boot.autoconfigure.PulsarContextInitializer
import ai.platon.pulsar.loop.TaskLoops
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(Browser4AutoConfiguration::class)
class ApiApplication(
    val globalCache: GlobalCache,
    val globalCacheFactory: GlobalCacheFactory,
    val taskLoops: TaskLoops
)

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args) {
        addInitializers(PulsarContextInitializer())
        setAdditionalProfiles("rest", "private", "advanced")
        setLogStartupInfo(true)
    }
}
