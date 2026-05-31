package ai.platon.pulsar.test.server

import ai.platon.pulsar.boot.autoconfigure.PulsarAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(PulsarAutoConfiguration::class)
class MockSiteApplication

fun main() {
    runApplication<MockSiteApplication> {
        setAdditionalProfiles("test")
    }
}
