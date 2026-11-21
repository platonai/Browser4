package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.h2.api.ErrorCode
import org.h2.jdbc.JdbcSQLException
import java.sql.Connection
import java.sql.ResultSet

/**
 * The outcome of one X-SQL statement.
 *
 * @property rows the rows, ordered by the SELECT column order, or null when the statement failed
 * @property error null on success, otherwise the message to report to the caller
 * @property statusCode the status the caller should publish: [ResourceStatus.SC_OK], or the failure
 *   code the engine produced ([ResourceStatus.SC_BAD_REQUEST] for a syntax error, otherwise
 *   [ResourceStatus.SC_EXPECTATION_FAILED])
 */
data class XSqlResult(
    val rows: List<Map<String, Any?>>?,
    val error: String? = null,
    val statusCode: Int = ResourceStatus.SC_OK,
) {
    val isSuccess get() = error == null
}

/**
 * The one place that runs an X-SQL statement on the session's SQL context.
 *
 * Two callers reach this point from opposite directions — a submitted hyperlink, which is driven by
 * the load event of the page it asked for, and a crawl round, which already holds the page — but
 * what happens once the statement is in hand is the same, and only the statement itself reaches h2:
 * the page is resolved by the `load_and_select()` UDF from inside the engine. So this is where the
 * statement is executed, where its columns are ordered, and where a failure becomes a message
 * instead of an exception.
 *
 * Sealing the statement ([ScrapeAPIUtils.normalizeForReadOnlyQuery]) and freezing the page the UDF
 * will resolve ([ScrapeAPIUtils.freezePageForQuery]) stay with the callers: they know how the page
 * was obtained, and that is exactly what differs between the two.
 * */
object XSqlExecutor {

    /** How many dead pooled connections one borrow may discard before falling back to a new one. */
    private const val MAX_STALE_CONNECTIONS = 8

    private val logger = getLogger(this)

    /**
     * Execute the sealed [normSQL] and return its rows in a deterministic shape.
     *
     * @param normSQL a statement produced by [ScrapeAPIUtils.normalizeForReadOnlyQuery], so that
     *   the url inside it cannot make the UDF load go to the web while the query runs
     */
    fun execute(session: PulsarSession, normSQL: NormXSQL): XSqlResult {
        val sqlContext = session.context as? AbstractBrowser4SQLContext
            ?: return XSqlResult(
                null,
                "Session context is not an SQL context; cannot execute X-SQL",
                ResourceStatus.SC_EXPECTATION_FAILED
            )

        return try {
            XSqlResult(select(sqlContext, normSQL.sql))
        } catch (e: Throwable) {
            if (!e.isClosedObject()) {
                describe(e)
            } else {
                // The connection was closed under the statement. A pooled connection is returned
                // while the session that owns it may still be closing, so one that was open when it
                // was offered can be closed when it is used — measured on a slow fixture page, where
                // the visit (10 s) outlives the session that fetched it, and the whole result set was
                // lost to `The object is already closed` (90007). The statement is sealed read-only,
                // so a retry on a freshly created connection cannot double-apply anything.
                logger.info("Retrying X-SQL on a fresh connection after: {}", e.message)
                try {
                    XSqlResult(select(sqlContext, normSQL.sql, freshConnection = true))
                } catch (retry: Throwable) {
                    describe(retry)
                }
            }
        }
    }

    /** Map an engine failure onto the outcome its caller should publish. */
    private fun describe(e: Throwable): XSqlResult = when (e) {
        is JdbcSQLException -> {
            val message = e.toString()
            if (message.contains("Syntax error in SQL statement")) {
                logger.warn("Syntax error in X-SQL statement>>>\n{}\n<<<", e.sql)
                XSqlResult(null, "X-SQL syntax error: ${e.message}", ResourceStatus.SC_BAD_REQUEST)
            } else {
                logger.warn("Failed to execute X-SQL\n{}", e.brief())
                XSqlResult(null, e.message ?: message, ResourceStatus.SC_EXPECTATION_FAILED)
            }
        }

        else -> {
            logger.warn("[Unexpected] Failed to execute X-SQL\n{}", e.brief())
            XSqlResult(
                null,
                "${e.javaClass.simpleName}: ${e.message}",
                ResourceStatus.SC_EXPECTATION_FAILED
            )
        }
    }

    /** H2's `The object is already closed` (90007): the session behind the connection ended. */
    private fun Throwable.isClosedObject(): Boolean =
        this is JdbcSQLException &&
            (errorCode == ErrorCode.OBJECT_CLOSED || message?.contains("already closed") == true)

    /**
     * Run [sql] on a pooled connection and read the result while that connection is still held: the
     * connection goes back to the pool in the `finally`, and a result set that outlived it would be
     * read through a connection somebody else is already using.
     *
     * @param freshConnection create the connection instead of borrowing one — the single retry after
     *   a borrowed connection turned out to be dead inside
     * */
    private fun select(
        sqlContext: AbstractBrowser4SQLContext,
        sql: String,
        freshConnection: Boolean = false,
    ): List<Map<String, Any?>> {
        val connection = if (freshConnection) sqlContext.randomConnection else borrow(sqlContext)
        return try {
            connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)?.use { st ->
                st.executeQuery(sql)?.use { rs ->
                    shape(ResultSetUtils.copyResultSet(rs))
                }
            } ?: emptyList()
        } finally {
            // A closed connection must never go back: the next poll would hand a dead one to a query
            // that cannot tell.
            connection.takeUnless { it.isClosed }?.let { sqlContext.connectionPool.offer(it) }
        }
    }

    /**
     * Take a pooled connection that can still run a statement.
     *
     * `run`/`runQuery` on the context already skip closed connections; this is the same rule for the
     * one consumer that used to trust the pool outright. A connection is returned to the pool while
     * the session that owns it may still be closing, so open-at-offer does not imply open-at-use.
     * */
    private fun borrow(sqlContext: AbstractBrowser4SQLContext): Connection {
        repeat(MAX_STALE_CONNECTIONS) {
            val connection = sqlContext.connectionPool.poll() ?: return sqlContext.randomConnection
            if (!connection.isClosed) return connection
            runCatching { connection.close() }
        }
        return sqlContext.randomConnection
    }

    /**
     * Order every row by the SELECT column order and make sure all columns are present in all of
     * them.
     *
     * JDBC metadata preserves the SELECT column order and is the deterministic source of truth for
     * CSV headers, JSON keys and table columns; the iteration order of a row map is not. Lowercased
     * labels are used because that is how [ResultSetUtils.getTextEntitiesFromResultSet] keys the
     * rows — otherwise the ordering pass would look up a column under a name that is not there and
     * re-append it, giving every column twice.
     *
     * Filling every row from the union of the column keys is what keeps a selector that matched
     * nothing visible: the column is present and null rather than silently absent.
     * */
    private fun shape(rs: ResultSet): List<Map<String, Any?>> {
        val metaData = rs.metaData
        val sqlColumnOrder = (1..metaData.columnCount).map { metaData.getColumnLabel(it).lowercase() }

        val rawRows = ResultSetUtils.getTextEntitiesFromResultSet(rs)

        val allKeys = linkedSetOf<String>()
        allKeys.addAll(sqlColumnOrder)
        for (row in rawRows) {
            allKeys.addAll(row.keys)
        }

        return rawRows.map { row ->
            val filled = linkedMapOf<String, Any?>()
            for (key in allKeys) {
                filled[key] = row[key]
            }
            filled
        }
    }
}
