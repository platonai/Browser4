package ai.platon.pulsar.integration.rest

import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.ImportResource
import org.springframework.test.context.ContextConfiguration

@SpringBootApplication
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@ComponentScan("ai.platon.pulsar.rest.api")
@ImportResource("classpath:rest-beans/app-context.xml")
class EnablePulsarRestApplication
