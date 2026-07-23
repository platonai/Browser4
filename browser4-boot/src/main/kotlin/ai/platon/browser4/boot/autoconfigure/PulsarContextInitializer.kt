package ai.platon.browser4.boot.autoconfigure

import ai.platon.pulsar.common.Systems
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.AbstractApplicationContext

class PulsarContextInitializer : ApplicationContextInitializer<AbstractApplicationContext> {
    override fun initialize(applicationContext: AbstractApplicationContext) {
        Systems.setPropertyIfAbsent("app.name", "browser4")
    }
}
