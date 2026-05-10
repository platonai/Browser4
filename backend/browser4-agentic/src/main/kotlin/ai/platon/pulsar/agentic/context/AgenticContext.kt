package ai.platon.pulsar.agentic.context

import ai.platon.pulsar.agentic.AgenticQLSession
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.BasicAgenticSession
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.ql.SQLSession
import ai.platon.pulsar.ql.SessionConfig
import ai.platon.pulsar.ql.SessionDelegate
import ai.platon.pulsar.ql.context.AbstractH2SQLContext
import ai.platon.pulsar.ql.context.SQLContext
import ai.platon.pulsar.ql.h2.H2SessionDelegate
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.session.BasicPulsarSession
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext

interface AgenticContext : SQLContext {
    override fun createSession(): AgenticSession
    override fun getOrCreateSession(): AgenticSession
    override fun createSession(settings: PulsarSettings): AgenticSession
    override fun getOrCreateSession(settings: PulsarSettings): AgenticSession
    override fun createSession(sessionDelegate: SessionDelegate): SQLSession
}

abstract class AbstractAgenticContext(
    applicationContext: AbstractApplicationContext
) : AbstractH2SQLContext(applicationContext), AgenticContext {
    private val logger = getLogger(this)

    /**
     * Create a [BasicPulsarSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): AgenticSession {
        val session = BasicAgenticSession(this, configuration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    override fun createSession(settings: PulsarSettings): AgenticSession {
        return createSession().also { settings.overrideConfiguration(it.sessionConfig) }
    }

    override fun getOrCreateSession(): AgenticSession = sessions.values.filterIsInstance<AgenticSession>().firstOrNull() ?: createSession()

    override fun getOrCreateSession(settings: PulsarSettings): AgenticSession = sessions.values.filterIsInstance<AgenticSession>().firstOrNull() ?: createSession()

    @Throws(Exception::class)
    override fun createSession(sessionDelegate: SessionDelegate): SQLSession {
        require(sessionDelegate is H2SessionDelegate)
        val session = sqlSessions.computeIfAbsent(sessionDelegate.id) {
            AgenticQLSession(this, sessionDelegate, SessionConfig(sessionDelegate, configuration))
        }
        logger.info("AgenticQLSession is created | #{}/{}/{}", session.id, sessionDelegate.id, id)
        return session as AgenticQLSession
    }
}

open class QLAgenticContext(
    applicationContext: AbstractApplicationContext
) : AbstractAgenticContext(applicationContext) {
}

open class ClassPathXmlAgenticContext(configLocation: String) :
    AbstractAgenticContext(ClassPathXmlApplicationContext(configLocation)) {
}

open class DefaultClassPathXmlAgenticContext() : ClassPathXmlAgenticContext(
    System.getProperty(
        CapabilityTypes.APPLICATION_CONTEXT_CONFIG_LOCATION,
        AppConstants.AGENTIC_CONTEXT_CONFIG_LOCATION
    )
)
