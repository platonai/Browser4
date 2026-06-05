package ai.platon.pulsar.skeleton.context.support

import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.session.BasicPulsarSession
import org.springframework.context.annotation.AnnotationConfigApplicationContext

open class AnnotationConfigPulsarContext(
    override val applicationContext: AnnotationConfigApplicationContext,
) : BasicPulsarContext(applicationContext) {

    constructor(vararg componentClasses: Class<*>) : this(AnnotationConfigApplicationContext(*componentClasses))

    /**
     * Create a [ai.platon.pulsar.skeleton.session.BasicPulsarSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): BasicPulsarSession {
        val session = BasicPulsarSession(this, configuration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    override fun createSession(settings: PulsarSettings): BasicPulsarSession {
        val session = BasicPulsarSession(this, configuration.toVolatileConfig())
        settings.label?.let { session.label = it }
        settings.overrideConfiguration(session.sessionConfig)
        return session.also { sessions[it.id] = it }
    }
}

class DefaultAnnotationConfigPulsarContext(
    vararg componentClasses: Class<*>
) : AnnotationConfigPulsarContext(*componentClasses)
