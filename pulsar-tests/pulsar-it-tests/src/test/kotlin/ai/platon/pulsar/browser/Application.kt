package ai.platon.pulsar.browser

import ai.platon.pulsar.boot.autoconfigure.PulsarAutoConfiguration
import ai.platon.pulsar.test.server.MockSiteApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(PulsarAutoConfiguration::class, MockSiteApplication::class)
class Application
