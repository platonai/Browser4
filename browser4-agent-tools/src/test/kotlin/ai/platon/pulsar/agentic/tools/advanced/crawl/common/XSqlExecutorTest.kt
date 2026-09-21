package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.h2.jdbc.JdbcSQLException
import org.h2.tools.SimpleResultSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.concurrent.ArrayBlockingQueue

/**
 * The executor is the one place that runs a sealed X-SQL on the h2 context, so its failure surface
 * is what both callers — the hyperlink driven path and the crawl — end up reporting. These tests
 * pin that surface, and the connection it borrows: a pooled connection that never comes back
 * starves every later query, and the pool is the only place that would notice.
 */
class XSqlExecutorTest {

    private val conf = MutableConfig(true).toVolatileConfig()
    private val url = "https://example.com/p"
    private val normSQL = NormXSQL(
        url,
        ScrapeAPIUtils.READ_ONLY_OPTION,
        "select dom_text(dom) as t from load_and_select('$url -readonly', ':root')"
    )

    @Test
    @DisplayName("a syntax error is reported as a bad request, with the engine's message")
    fun aSyntaxErrorIsReportedAsABadRequest() {
        val fixture = fixtureThatThrows(
            JdbcSQLException(
                "Syntax error in SQL statement \"select from\"; expected \"FROM\"",
                "select from", "42001", 42001, null, null
            )
        )

        val result = XSqlExecutor.execute(fixture.session, normSQL)

        assertFalse(result.isSuccess)
        assertNull(result.rows)
        assertEquals(ResourceStatus.SC_BAD_REQUEST, result.statusCode)
        assertTrue(
            result.error!!.startsWith("X-SQL syntax error:"),
            "a syntax error must be named as one: ${result.error}"
        )
    }

    @Test
    @DisplayName("any other SQL failure is reported as an expectation failure")
    fun anyOtherSqlFailureIsReportedAsAnExpectationFailure() {
        val fixture = fixtureThatThrows(
            JdbcSQLException("Table \"MISSING\" not found", "select * from MISSING", "42S02", 42102, null, null)
        )

        val result = XSqlExecutor.execute(fixture.session, normSQL)

        assertFalse(result.isSuccess)
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, result.statusCode)
        assertTrue(
            result.error!!.contains("not found"),
            "the engine's message must reach the caller: ${result.error}"
        )
    }

    @Test
    @DisplayName("an unexpected failure is reported with its own type instead of escaping")
    fun anUnexpectedFailureIsReportedWithItsOwnType() {
        val fixture = fixtureThatThrows(RuntimeException("the driver fell over"))

        val result = XSqlExecutor.execute(fixture.session, normSQL)

        assertFalse(result.isSuccess)
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, result.statusCode)
        assertEquals("RuntimeException: the driver fell over", result.error)
    }

    @Test
    @DisplayName("a session that is not backed by an SQL context is reported, not crashed on")
    fun aSessionWithoutASqlContextIsReported() {
        // A session whose context is not an SQL context: no statement can be run on it at all.
        val session = mock<PulsarSession>()

        val result = XSqlExecutor.execute(session, normSQL)

        assertFalse(result.isSuccess)
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, result.statusCode)
        assertEquals("Session context is not an SQL context; cannot execute X-SQL", result.error)
    }

    @Test
    @DisplayName("an empty result set is an empty row list, not a failure")
    fun anEmptyResultSetIsAnEmptyRowList() {
        val fixture = fixtureReturning(emptyResultSet())

        val result = XSqlExecutor.execute(fixture.session, normSQL)

        assertTrue(result.isSuccess, "error: ${result.error}")
        assertEquals(ResourceStatus.SC_OK, result.statusCode)
        assertEquals(emptyList<Map<String, Any?>>(), result.rows)
    }

    @Test
    @DisplayName("the connection goes back to the pool, and a closed one does not")
    fun theConnectionGoesBackToThePool() {
        val open = fixtureReturning(emptyResultSet())

        XSqlExecutor.execute(open.session, normSQL)

        assertEquals(1, open.pool.size, "the borrowed connection must be returned")
        assertSame(open.connection, open.pool.peek())

        // A connection the driver closed must not be offered back: the next poll would hand a
        // dead connection to a query that cannot tell.
        val closed = fixtureReturning(emptyResultSet(), closedConnection = true)

        XSqlExecutor.execute(closed.session, normSQL)

        assertTrue(closed.pool.isEmpty(), "a closed connection must not be pooled again")
    }

    // -----------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------

    /**
     * A session whose SQL context hands out one connection, whose statement either returns
     * [resultSet] or throws [failure].
     * */
    private class Fixture(
        val session: PulsarSession,
        val connection: Connection,
        val pool: ArrayBlockingQueue<Connection>,
    )

    private fun fixtureReturning(resultSet: ResultSet, closedConnection: Boolean = false): Fixture =
        fixture(resultSet, null, closedConnection)

    private fun fixtureThatThrows(failure: Throwable): Fixture = fixture(null, failure, false)

    private fun fixture(resultSet: ResultSet?, failure: Throwable?, closedConnection: Boolean): Fixture {
        val connection = mock<Connection>()
        val statement = mock<Statement>()
        val pool = ArrayBlockingQueue<Connection>(1).apply { add(connection) }
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        val session = mock<PulsarSession>()

        whenever(connection.isClosed).thenReturn(closedConnection)
        whenever(connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY))
            .thenReturn(statement)
        if (failure != null) {
            whenever(statement.executeQuery(any<String>())).thenThrow(failure)
        } else {
            whenever(statement.executeQuery(any<String>())).thenReturn(resultSet)
        }
        whenever(sqlContext.connectionPool).thenReturn(pool)
        whenever(session.context).thenReturn(sqlContext)

        return Fixture(session, connection, pool)
    }

    private fun emptyResultSet(): SimpleResultSet = SimpleResultSet().apply { autoClose = false }
}
