package ai.platon.pulsar.rest.api.webdriver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan

/**
 * Minimal Spring Boot application for WebDriver API tests.
 * This application only loads the webdriver components without the full Pulsar context.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = [
        "ai.platon.pulsar.rest.api.webdriver"
    ]
)
class WebDriverTestApplication
