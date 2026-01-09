package ai.platon.pulsar.rest.api

import ai.platon.pulsar.boot.autoconfigure.PulsarContextInitializer
import ai.platon.pulsar.skeleton.crawl.TaskLoops
import ai.platon.pulsar.skeleton.crawl.common.GlobalCache
import ai.platon.pulsar.skeleton.crawl.common.GlobalCacheFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.ImportResource

@SpringBootApplication
@ImportResource("classpath:rest-beans/app-context.xml")
@ComponentScan(
    "ai.platon.pulsar.boot.autoconfigure",
    "ai.platon.pulsar.rest.api"
)
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
