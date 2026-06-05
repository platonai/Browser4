package ai.platon.pulsar.agentic.context.sql

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.IllegalApplicationStateException
import ai.platon.pulsar.common.Systems
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.sql.SQLUtils
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.core.api.LoadOptions
import ai.platon.pulsar.core.api.PulsarSettings
import ai.platon.pulsar.ql.AbstractSQLSession
import ai.platon.pulsar.ql.SQLSession
import ai.platon.pulsar.ql.SessionDelegate
import ai.platon.pulsar.ql.context.SQLContext
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import org.h2.api.ErrorCode
import org.h2.engine.Session
import org.h2.engine.SessionInterface
import org.h2.message.DbException
import org.slf4j.LoggerFactory
import org.springframework.context.support.AbstractApplicationContext
import java.sql.Connection
import java.sql.ResultSet
import java.text.MessageFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The abstract SQL context, every X-SQL staff should be within the SQL context
 */
abstract class AbstractBrowser4SQLContext(
    applicationContext: AbstractApplicationContext
) : AbstractPulsarContext(applicationContext), SQLContext {

    private val logger = LoggerFactory.getLogger(AbstractBrowser4SQLContext::class.java)

    enum class Status { NOT_READY, INITIALIZING, RUNNING, CLOSING, CLOSED }

    var status: Status = Status.NOT_READY

    abstract val randomConnection: Connection

    init {
        // Required by H2 database engine
        System.setProperty("h2.sessionFactory", AppConstants.H2_SESSION_FACTORY)
    }

    val randomConnectionOrNull: Connection? get() = kotlin.runCatching { randomConnection }
        .onFailure { warnInterruptible(this, it) }
        .getOrNull()

    val connectionPool = ArrayBlockingQueue<Connection>(1000)
    private val resultSetType = ResultSet.TYPE_SCROLL_SENSITIVE
    private val resultSetConcurrency = ResultSet.CONCUR_READ_ONLY

    /**
     * The sql session container.
     */
    val sqlSessions = ConcurrentHashMap<Int, AbstractSQLSession>()

    private val closed = AtomicBoolean()

    init {
        Systems.setPropertyIfAbsent(CapabilityTypes.SCENT_EXTRACT_TABULATE_CELL_TYPE, "DATABASE")

        status = Status.INITIALIZING

        logger.info("SQLContext is created | {}/{} | {}", id, sessions.size, this::class.java.simpleName)

        status = Status.RUNNING
    }

    override fun normalize(url: String, options: LoadOptions, toItemOption: Boolean): NormURL {
        val normURL = super.normalize(url, options, toItemOption)
        return NormURL(SQLUtils.unsanitizeUrl(normURL.urlString), normURL.options, hrefSpec = normURL.hrefSpec)
    }

    @Throws(Exception::class)
    override fun execute(sql: String) {
        val conn = connectionPool.poll() ?: randomConnection
        try {
            conn.createStatement(resultSetType, resultSetConcurrency).execute(sql)
        } catch (e: Exception) {
            throw e
        } finally {
            conn.takeUnless { it.isClosed }?.let { connectionPool.add(conn) }
        }
    }

    @Throws(Exception::class)
    override fun executeQuery(sql: String): ResultSet {
        val conn = connectionPool.poll() ?: randomConnection
        return try {
            conn.createStatement(resultSetType, resultSetConcurrency).executeQuery(sql)
        } catch (e: Exception) {
            throw e
        } finally {
            conn.takeUnless { it.isClosed }?.let { connectionPool.add(conn) }
        }
    }

    @Throws(Exception::class)
    override fun run(block: (Connection) -> Unit) {
        var conn = connectionPool.poll() ?: randomConnection
        while (conn.isClosed) {
            conn = connectionPool.poll() ?: randomConnection
        }

        try {
            block(conn)
        } finally {
            conn.takeUnless { it.isClosed }?.let { connectionPool.add(conn) }
        }
    }

    @Throws(Exception::class)
    override fun runQuery(block: (Connection) -> ResultSet): ResultSet {
        var conn = connectionPool.poll() ?: randomConnection
        while (conn.isClosed) {
            conn = connectionPool.poll() ?: randomConnection
        }

        try {
            return block(conn)
        } finally {
            conn.takeUnless { it.isClosed }?.let { connectionPool.add(conn) }
        }
    }

    @Throws(Exception::class)
    abstract override fun createSession(sessionDelegate: SessionDelegate): SQLSession

    override fun createSession(settings: PulsarSettings) = createSession().also { settings.overrideConfiguration(it.sessionConfig)}

    override fun sessionCount(): Int {
        ensureRunning()
        return sqlSessions.size
    }

    @Throws(Exception::class)
    override fun getSession(sessionInterface: SessionInterface): SQLSession {
        val h2session = sessionInterface as Session
        return getSession(h2session.serialId)
    }

    @Throws(Exception::class)
    override fun getSession(sessionId: Int): SQLSession {
        ensureRunning()
        val session = sqlSessions[sessionId]
        if (session == null) {
            val message = MessageFormat.format(
                "Session is already closed | #{0}/{1}",
                sessionId, id
            )
            logger.warn(message)
            throw DbException.get(ErrorCode.OBJECT_CLOSED, message)
        }
        return session
    }

    override fun closeSession(sessionId: Int) {
        ensureRunning()
        sqlSessions.remove(sessionId)?.close()
        logger.info("SQLSession is closed | #{}/{}/{}", id, sessionId, sqlSessions.size)
    }

    override fun close() {
        logger.info("Closing SQLContext #{}, sql sessions: {}", id, sqlSessions.keys.joinToString { "$it" })
        AppContext.terminate()

        if (closed.compareAndSet(false, true)) {
            runCatching { doClose1() }.onFailure { warnForClose(this, it) }
        }

        super.close()

        AppContext.endTermination()
    }

    private fun doClose1() {
        if (closed.compareAndSet(false, true)) {
            status = Status.CLOSING

            // database engine will close the sessions
            sqlSessions.values.forEach { it.close() }
            sqlSessions.clear()
            connectionPool.forEach { it.close() }
            connectionPool.clear()

            status = Status.CLOSED

            // H2SessionFactory.shutdown()
        }
    }

    private fun ensureRunning() {
        if (!isActive) {
            throw IllegalApplicationStateException("SQLContext is closed | #$id")
        }
    }
}
