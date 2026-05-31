package ai.platon.pulsar.heavy.rest

import ai.platon.pulsar.boot.autoconfigure.PulsarAutoConfiguration
import ai.platon.pulsar.rest.ApiApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(PulsarAutoConfiguration::class, ApiApplication::class)
class EnablePulsarRestApplication
