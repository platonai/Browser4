package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.session.PulsarSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.sql.ResultSet

private val logger = LoggerFactory.getLogger("ai.platon.pulsar.rest.api.service.crawl.CrawlXSql")

/**
 * Execute an X-SQL query against the page at [pageUrl].
 * Uses [SQLTemplate] to substitute @url placeholder, then runs the query
 * via the session's SQL context.  Returns a pair of (extracted rows, error message).
 * On success the error is null; on failure the rows are null and the error
 * describes what went wrong.
 *
 * Lives outside [CrawlService] because nothing here is crawl state: a crawl
 * page, a query string and a session are all it needs.
 */
internal fun executeCrawlSqlQuery(
    session: PulsarSession,
    pageUrl: String,
    sql: String
): Pair<List<Map<String, Any?>>?, String?> {
    return try {
        // Pre-load the page into the session's WebDB so load_and_select
        // UDFs find the page in the local cache.  Without this the X-SQL
        // engine may silently return 0 rows even when the page was loaded
        // earlier in a different session lifecycle stage.
        val preloadSucceeded = try {
            runBlocking {
                withTimeout(10_000L) {
                    session.load(pageUrl, "-refresh")
                }
            }
            logger.debug("Crawl X-SQL: pre-loaded '{}' into session cache", pageUrl)
            true
        } catch (preloadError: Exception) {
            // Pre-load failure means the WebDB cache may be empty for this
            // page, and DOM_LOAD_AND_SELECT / DOM UDFs will not find the
            // page content.  This is a warning (not debug) because it
            // directly causes empty extraction results for the user.
            logger.warn(
                "Crawl X-SQL: pre-load of '{}' failed ({}). " +
                "X-SQL extraction may return 0 rows or empty fields. " +
                "This can happen when the session's WebDB cache was cleared " +
                "between page load and query execution.",
                pageUrl, preloadError.message
            )
            false
        }

        val processedSql = SQLTemplate(sql).createSQL(pageUrl)
        logger.info("Crawl X-SQL: executing query on '{}': {}", pageUrl, processedSql.take(300))

        val sqlContext = session.context as? AbstractBrowser4SQLContext
            ?: run {
                val msg = "Session context is not an SQL context; cannot execute X-SQL"
                logger.warn(msg)
                return Pair(null, msg)
            }
        val rs: ResultSet = sqlContext.executeQuery(processedSql)
        val copied = ResultSetUtils.copyResultSet(rs)

        // Read column names from ResultSetMetaData BEFORE converting to
        // text entities.  JDBC metadata preserves the SQL SELECT column
        // order, which is the deterministic source of truth for column
        // ordering in CSV/JSON/table output.  Without this, column order
        // depends on Map iteration order which varies across implementations.
        val metaData = copied.metaData
        // Use lowercase column labels so they match the keys produced by
        // ResultSetUtils.getTextEntitiesFromResultSet (which lowercases
        // via ResultSetMetaData.getColumnName + toLowerCase).  Without
        // this the reorder pass produces duplicate columns: the original-
        // case lookup returns null, and the "append extras" loop re-adds
        // the lowercased key — giving each column twice.
        val sqlColumnOrder = (1..metaData.columnCount).map { metaData.getColumnLabel(it).lowercase() }

        val rawRows = ResultSetUtils.getTextEntitiesFromResultSet(copied)
        // Reorder each row to match the SQL SELECT column order so that
        // CSV headers, JSON keys, and table columns are deterministic.
        val rows = if (sqlColumnOrder.isNotEmpty()) {
            rawRows.map { row ->
                val ordered = linkedMapOf<String, Any?>()
                for (col in sqlColumnOrder) {
                    ordered[col] = row[col]
                }
                // Append any columns not in metadata (computed/dynamic names)
                for ((key, value) in row) {
                    if (key !in sqlColumnOrder) {
                        ordered[key] = value
                    }
                }
                ordered
            }
        } else {
            rawRows
        }
        if (rows.isEmpty()) {
            logger.info("Crawl X-SQL: query returned 0 rows for '{}'", pageUrl)
        } else {
            logger.info("Crawl X-SQL: extracted {} row(s) from '{}'", rows.size, pageUrl)

            // Detect silent failures: rows exist but ALL extracted data
            // fields (excluding URL/URI columns) are empty.  This almost
            // always means the WebDB cache was empty when the UDFs ran,
            // which happens when the pre-load above fails or the cache
            // layer used by session.load() differs from the one the UDFs
            // read.  Surface this as a warning so the user knows something
            // is wrong.
            val nonUrlColumnsHaveContent = rows.any { row ->
                row.any { (key, value) ->
                    !key.contains("url", ignoreCase = true)
                        && !key.contains("uri", ignoreCase = true)
                        && !key.contains("base", ignoreCase = true)
                        && value != null && value.toString().isNotBlank()
                }
            }
            if (!nonUrlColumnsHaveContent && !preloadSucceeded) {
                val msg = "All extracted fields are empty — the WebDB cache may have been " +
                    "empty when the X-SQL UDFs ran. This is likely a cache-coherence issue " +
                    "between session.load() and the DOM UDF layer."
                logger.warn("Crawl X-SQL: {} for '{}'", msg, pageUrl)
                return Pair(rows, msg)
            }
        }
        Pair(rows, null)
    } catch (e: Exception) {
        val msg = "${e.javaClass.simpleName}: ${e.message}"
        logger.error("Failed to execute X-SQL on '{}': {} (SQL: {})", pageUrl, msg, sql.take(300))
        Pair(null, msg)
    }
}
