package ai.platon.pulsar.rest.api.webdriver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan

/**
 * Test application for WebDriver API integration tests.
 * This is a minimal Spring Boot application that only loads the WebDriver API components.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = [
        "ai.platon.pulsar.rest.api.webdriver",
        "ai.platon.pulsar.rest.api.store",
        "ai.platon.pulsar.rest.api.config"
    ]
)
class WebDriverApiTestApplication
