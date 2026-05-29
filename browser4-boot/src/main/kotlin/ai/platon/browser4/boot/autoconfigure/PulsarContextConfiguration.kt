package ai.platon.browser4.boot.autoconfigure

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.context.GenericAgenticContext
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
        require(context is GenericAgenticContext)
        require(context.applicationContext == applicationContext)
        return context
    }

    @Bean
    @Scope("prototype")
    fun getPulsarSession(pulsarContext: AgenticContext): AgenticSession {
        require(pulsarContext is GenericAgenticContext)
        return pulsarContext.createSession()
    }
}
