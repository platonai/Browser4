package ai.platon.pulsar.rest.util.server

import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.test.context.ContextConfiguration

@SpringBootApplication(
    scanBasePackages = [
        "ai.platon.pulsar.boot.autoconfigure",
        "ai.platon.pulsar.test.server"
    ]
)
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
class EnableMockServerApplication
