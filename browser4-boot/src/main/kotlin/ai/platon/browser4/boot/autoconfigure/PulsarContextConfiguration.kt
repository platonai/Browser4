package ai.platon.browser4.boot.autoconfigure

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AbstractAgenticContext
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.context.AgenticContexts
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope

@Configuration
class PulsarContextConfiguration(
    val applicationContext: ApplicationContext
) {
    @Bean
    fun pulsarContext(): AgenticContext {
        val context = AgenticContexts.create(applicationContext)
        require(context is AbstractAgenticContext)
        require(context.applicationContext == applicationContext)
        return context
    }

    @Bean
    @Scope("prototype")
    fun getPulsarSession(pulsarContext: AgenticContext): AgenticSession {
        require(pulsarContext is AbstractAgenticContext)
        return pulsarContext.createSession()
    }
}
