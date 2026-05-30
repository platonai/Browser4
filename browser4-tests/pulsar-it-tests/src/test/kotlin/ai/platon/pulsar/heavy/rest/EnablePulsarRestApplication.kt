package ai.platon.pulsar.heavy.rest

import ai.platon.browser4.boot.autoconfigure.test.PulsarTestContextInitializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.ContextConfiguration

@SpringBootApplication
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@ComponentScan("ai.platon.pulsar.rest")
class EnablePulsarRestApplication
