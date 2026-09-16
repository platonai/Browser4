package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.h2.jdbc.JdbcSQLException
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
        } catch (e: JdbcSQLException) {
            val message = e.toString()
            if (message.contains("Syntax error in SQL statement")) {
                logger.warn("Syntax error in X-SQL statement>>>\n{}\n<<<", e.sql)
                XSqlResult(null, "X-SQL syntax error: ${e.message}", ResourceStatus.SC_BAD_REQUEST)
            } else {
                logger.warn("Failed to execute X-SQL\n{}", e.brief())
                XSqlResult(null, e.message ?: message, ResourceStatus.SC_EXPECTATION_FAILED)
            }
        } catch (e: Throwable) {
            logger.warn("[Unexpected] Failed to execute X-SQL\n{}", e.brief())
            XSqlResult(
                null,
                "${e.javaClass.simpleName}: ${e.message}",
                ResourceStatus.SC_EXPECTATION_FAILED
            )
        }
    }

    /**
     * Run [sql] on a pooled connection and read the result while that connection is still held: the
     * connection goes back to the pool in the `finally`, and a result set that outlived it would be
     * read through a connection somebody else is already using.
     * */
    private fun select(sqlContext: AbstractBrowser4SQLContext, sql: String): List<Map<String, Any?>> {
        val connection = sqlContext.connectionPool.poll() ?: sqlContext.randomConnection
        return try {
            connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)?.use { st ->
                st.executeQuery(sql)?.use { rs ->
                    shape(ResultSetUtils.copyResultSet(rs))
                }
            } ?: emptyList()
        } finally {
            connection.takeUnless { it.isClosed }?.let { sqlContext.connectionPool.offer(it) }
        }
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
