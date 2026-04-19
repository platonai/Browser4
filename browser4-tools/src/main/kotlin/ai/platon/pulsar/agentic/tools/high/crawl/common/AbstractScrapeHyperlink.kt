package ai.platon.pulsar.agentic.tools.high.crawl.common

import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeResponse
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.common.ResultSets
import ai.platon.pulsar.ql.context.AbstractSQLContext
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import ai.platon.pulsar.skeleton.crawl.common.url.CompletableListenableHyperlink
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.h2.jdbc.JdbcSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

open class ScrapeHyperlink(
    url: String,
    val uuid: String = UUID.randomUUID().toString()
): CompletableListenableHyperlink<ScrapeResponse>(url) {
    val response = ScrapeResponse()
}

abstract class AbstractScrapeHyperlink(
    val request: ScrapeRequest,
    val sql: NormXSQL,
    val session: PulsarSession,
    uuid: String
) : ScrapeHyperlink(sql.url, uuid) {

    private val logger = getLogger(XSQLScrapeHyperlink::class)

    protected val sqlContext get() = session.context as AbstractSQLContext
    protected val connectionPool get() = sqlContext.connectionPool
    protected val randomConnection get() = sqlContext.randomConnection
    private val isCompleted = AtomicBoolean()

    abstract override var eventHandlers: PageEventHandlers

    open fun executeQuery(): ResultSet = executeQuery(request, response)

    open fun complete(page: WebPage) {
        response.id = uuid
        response.isDone = true
        response.finishTime = Instant.now()

        if (isCompleted.compareAndSet(false, true)) {
            super.complete(response)
        }

        // logger.info("Completed | {}", page.url)
    }

    protected open fun executeQuery(request: ScrapeRequest, response: ScrapeResponse): ResultSet {
        var rs: ResultSet = ResultSets.newSimpleResultSet()

        try {
            response.statusCode = ResourceStatus.SC_OK
            rs = executeQuery(sql.sql)
        } catch (e: JdbcSQLException) {
            response.statusCode = ResourceStatus.SC_EXPECTATION_FAILED
            logger.warn("Failed to execute sql #${response.id}{}", e.brief())
        } catch (e: Throwable) {
            response.statusCode = ResourceStatus.SC_EXPECTATION_FAILED
            logger.warn("Failed to execute sql #${response.id}\n{}", e.brief())
        }

        return rs
    }

    private fun executeQuery(sql: String): ResultSet {
        val connection = connectionPool.poll() ?: randomConnection
        return executeQuery(sql, connection).also { connectionPool.offer(connection) }
    }

    private fun executeQuery(sql: String, conn: Connection): ResultSet {
        var result: ResultSet? = null
        val millis = measureTimeMillis {
            conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)?.use { st ->
                try {
                    st.executeQuery(sql)?.use { rs ->
                        result = ResultSetUtils.copyResultSet(rs)
                    }
                } catch (e: JdbcSQLException) {
                    val message = e.toString()
                    if (message.contains("Syntax error in SQL statement")) {
                        response.statusCode = ResourceStatus.SC_BAD_REQUEST
                        logger.warn("Syntax error in SQL statement #${response.id}>>>\n{}\n<<<", e.sql)
                    } else {
                        response.statusCode = ResourceStatus.SC_EXPECTATION_FAILED
                        if (AppContext.isActive) {
                            logger.warn("Failed to execute scrape task #${response.id}", e)
                        }
                    }
                }
            }
        }

        return result ?: ResultSets.newResultSet()
    }
}
