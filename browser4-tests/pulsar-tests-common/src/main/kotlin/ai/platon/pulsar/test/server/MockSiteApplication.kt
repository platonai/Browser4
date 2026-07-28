package ai.platon.pulsar.test.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MockSiteApplication

fun main() {
    runApplication<MockSiteApplication> {
        setAdditionalProfiles("test")
    }
}
