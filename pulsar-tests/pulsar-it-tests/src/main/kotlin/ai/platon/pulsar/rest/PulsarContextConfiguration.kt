package ai.platon.pulsar.rest

import ai.platon.pulsar.core.api.PulsarContext
import ai.platon.pulsar.core.api.PulsarContexts
import ai.platon.pulsar.core.api.PulsarSession
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope

@Configuration
class PulsarContextConfiguration(
    val applicationContext: ApplicationContext
) {
    @Bean
    fun pulsarContext(): PulsarContext {
        val context = PulsarContexts.create(applicationContext)
        return context
    }

    @Bean
    @Scope("prototype")
    fun getPulsarSession(pulsarContext: PulsarContext): PulsarSession {
        return pulsarContext.createSession()
    }
}
