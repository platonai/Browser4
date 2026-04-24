package ai.platon.pulsar.skeleton.context.support

import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.session.BasicPulsarSession
import org.springframework.context.support.AbstractApplicationContext

/**
 * The main entry point for pulsar functionality.
 *
 * A PulsarContext can be used to inject, fetch, load, parse, store Web pages.
 */
open class BasicPulsarContext(
    applicationContext: AbstractApplicationContext
) : AbstractPulsarContext(applicationContext) {

    @Throws(Exception::class)
    override fun createSession(): BasicPulsarSession {
        val session = BasicPulsarSession(this, configuration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    @Throws(Exception::class)
    override fun createSession(settings: PulsarSettings): BasicPulsarSession {
        return createSession().also { settings.overrideConfiguration(it.sessionConfig) }
    }
}
