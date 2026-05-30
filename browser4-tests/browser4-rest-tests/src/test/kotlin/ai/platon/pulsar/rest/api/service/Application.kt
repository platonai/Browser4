package ai.platon.pulsar.rest.api.service

import ai.platon.browser4.boot.autoconfigure.test.PulsarTestContextInitializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.ContextConfiguration

@SpringBootApplication
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@ComponentScan(basePackages = [
    "ai.platon.pulsar.rest.api",
    "ai.platon.pulsar.rest.mcp",
])
class ServiceApplication
