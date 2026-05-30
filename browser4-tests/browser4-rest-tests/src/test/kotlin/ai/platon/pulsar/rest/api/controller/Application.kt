package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.boot.autoconfigure.test.PulsarTestContextInitializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.ContextConfiguration

@SpringBootApplication
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@ComponentScan(
    "ai.platon.browser4.boot.autoconfigure",
    "ai.platon.pulsar.rest",
)
class Application
