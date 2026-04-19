package ai.platon.pulsar.boot.autoconfigure

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.context.QLAgenticContext
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportResource
import org.springframework.context.annotation.Scope

@Configuration
@ImportResource("classpath:pulsar-beans/app-context.xml")
class PulsarContextConfiguration(
    val applicationContext: ApplicationContext
) {
    @Bean
    fun pulsarContext(): AgenticContext {
        val context = AgenticContexts.create(applicationContext)
        require(context is QLAgenticContext)
        require(context.applicationContext == applicationContext)
        return context
    }

    @Bean
    @Scope("prototype")
    fun getPulsarSession(pulsarContext: AgenticContext): AgenticSession {
        require(pulsarContext is QLAgenticContext)
        return pulsarContext.createSession()
    }
}
