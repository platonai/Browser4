package ai.platon.pulsar.test.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Profile

@SpringBootApplication(
    scanBasePackages = [
        "ai.platon.pulsar.test.server",
    ]
)
class MockSiteApplication

fun main() {
    runApplication<MockSiteApplication> {
        setAdditionalProfiles("test")
    }
}
