package ai.platon.pulsar.boot.autoconfigure

import ai.platon.pulsar.agentic.context.AgenticContexts
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.AbstractApplicationContext

class PulsarContextInitializer : ApplicationContextInitializer<AbstractApplicationContext> {
    override fun initialize(applicationContext: AbstractApplicationContext) {
        AgenticContexts.create(applicationContext)
    }
}
