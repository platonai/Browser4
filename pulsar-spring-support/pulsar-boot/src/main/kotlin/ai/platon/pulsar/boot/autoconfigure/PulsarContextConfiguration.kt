package ai.platon.pulsar.boot.autoconfigure

import ai.platon.pulsar.core.api.PulsarSession
import ai.platon.pulsar.ql.context.SQLContext
import ai.platon.pulsar.ql.context.SQLContexts
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope

@Configuration
class PulsarContextConfiguration(
    val applicationContext: ApplicationContext
) {
    @Bean
    fun pulsarContext(): SQLContext {
        val context = SQLContexts.create(applicationContext)
        require(context is AbstractPulsarContext)
        require(context.applicationContext == applicationContext)
        return context
    }

    @Bean
    @Scope("prototype")
    fun getPulsarSession(pulsarContext: SQLContext): PulsarSession {
        return pulsarContext.createSession()
    }
}
