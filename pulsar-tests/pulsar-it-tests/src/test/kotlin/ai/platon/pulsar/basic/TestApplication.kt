package ai.platon.pulsar.basic

import ai.platon.pulsar.boot.autoconfigure.PulsarAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(PulsarAutoConfiguration::class)
class TestApplication
