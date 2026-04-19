package ai.platon.pulsar.util.server

import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ImportResource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

@SpringBootApplication(
    scanBasePackages = [
        "ai.platon.pulsar.boot.autoconfigure",
        "ai.platon.pulsar.test.server"
    ]
)
@ImportResource("classpath:test-beans/app-context.xml")
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
class EnableMockServerApplication
