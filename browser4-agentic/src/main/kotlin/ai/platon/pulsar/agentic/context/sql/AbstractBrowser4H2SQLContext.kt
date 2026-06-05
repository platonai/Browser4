package ai.platon.pulsar.agentic.context.sql

import ai.platon.pulsar.agentic.AgenticQLSession
import ai.platon.pulsar.agentic.BasicAgenticSession
import ai.platon.pulsar.ql.SQLSession
import ai.platon.pulsar.ql.SessionConfig
import ai.platon.pulsar.ql.SessionDelegate
import ai.platon.pulsar.ql.h2.H2MemoryDb
import ai.platon.pulsar.ql.h2.H2SessionDelegate
import ai.platon.pulsar.skeleton.session.BasicPulsarSession
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.slf4j.LoggerFactory
import org.springframework.context.support.AbstractApplicationContext
import java.sql.Connection

abstract class AbstractBrowser4H2SQLContext(
    applicationContext: AbstractApplicationContext
) : AbstractBrowser4SQLContext(applicationContext) {

    private val logger = LoggerFactory.getLogger(AbstractBrowser4H2SQLContext::class.java)

    private val db = H2MemoryDb()

    override val randomConnection: Connection get() = db.getRandomConnection()

    @Throws(Exception::class)
    override fun createSession(sessionDelegate: SessionDelegate): SQLSession {
        require(sessionDelegate is H2SessionDelegate)
        val session = sqlSessions.computeIfAbsent(sessionDelegate.id) {
            AgenticQLSession(this, sessionDelegate, SessionConfig(sessionDelegate, configuration))
        }
        logger.info("SQLSession is created | #{}/{}/{}", session.id, sessionDelegate.id, id)
        return session as AgenticQLSession
    }

    /**
     * Create a [BasicPulsarSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): PulsarSession {
        val session = BasicAgenticSession(this, configuration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }
}
