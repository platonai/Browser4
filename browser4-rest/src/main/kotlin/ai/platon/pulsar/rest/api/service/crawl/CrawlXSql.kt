package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.XSqlExecutor
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.session.PulsarSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger("ai.platon.pulsar.rest.api.service.crawl.CrawlXSql")

/**
 * How long a page that is *not* local may take to download before the query is refused. The
 * download happens immediately before the query and only when neither the crawl's own page nor
 * local storage can answer, so a page that needs longer than this is a page the crawl could not
 * have fetched either.
 * */
private val PAGE_DOWNLOAD_TIMEOUT = 30_000L.milliseconds

/**
 * Execute an X-SQL query against the page at [pageUrl].
 * Uses [SQLTemplate] to substitute @url placeholder, then runs the query
 * via the session's SQL context.  Returns a pair of (extracted rows, error message).
 * On success the error is null; on failure the rows are null and the error
 * describes what went wrong.
 *
 * The query is *not* executed by plain SQL: the h2 engine resolves the url in its FROM clause
 * itself, through the `load_and_select()` UDF and `PulsarSession.load()`. The statement therefore
 * runs in two halves, and the local copy of the page is the contract between them:
 *
 *  1. **before** — the page must be downloaded, or already be in local storage, immediately before
 *     the query runs. [page] is the page this round fetched for that url, so the round itself
 *     normally answers; a page that is not local at all is downloaded here, at the last moment,
 *     so that nothing can evict it in between;
 *  2. **during** — the url inside the SQL carries `-readonly` and no option that forces a fetch,
 *     so the UDF load serves that local copy read-only: no network round trip, no store write, no
 *     cache write while the query executes.  `-refresh` is the option that would break this (it
 *     makes every local copy look expired), which is why the seal *erases* it instead of relying on
 *     any precedence between the two flags — and why the crawl's own loads follow the same rule
 *     ([resolveRoundArgs]: `-readonly` wins over `-refresh` in the round's args too).
 *
 * When (1) cannot be satisfied the query is refused instead of run: `-readonly` is a cache-hit
 * guarantee, not a fetch prohibition, and an unguarded run would silently re-fetch and rewrite the
 * page in the middle of the query.
 *
 * The statement itself is run by [XSqlExecutor], the same executor the hyperlink driven path uses;
 * this function is the crawl's half of the contract — it knows which page to make local, and it
 * reports a missing row set as a reason instead of an exception.
 *
 * Lives outside [CrawlService] because nothing here is crawl state: a crawl
 * page, a query string and a session are all it needs.
 *
 * @param page the page the caller loaded for [pageUrl], when it still has it — the freshest local
 *   copy there is, and the one that makes the read-only load a guaranteed cache hit
 * @param document the document parsed from [page], when available; caching it saves the engine a
 *   re-parse, and lets a page whose body was dropped still be extracted
 */
internal fun executeCrawlSqlQuery(
    session: PulsarSession,
    pageUrl: String,
    sql: String,
    page: WebPage? = null,
    document: FeaturedDocument? = null,
): Pair<List<Map<String, Any?>>?, String?> {
    return try {
        val processedSql = SQLTemplate(sql).createSQL(pageUrl)
        // Seal the statement before anything else: the url inside it is what the engine resolves,
        // and the seal is what makes that inner load read-only.
        val normSQL = ScrapeAPIUtils.normalizeForReadOnlyQuery(processedSql)
        val queryUrl = ScrapeAPIUtils.resolveQueryUrl(session, normSQL.url)

        val unavailable = ensurePageIsLocal(session, pageUrl, queryUrl, page, document)
        if (unavailable != null) {
            logger.warn("Crawl X-SQL: refusing to run the query on '{}': {}", pageUrl, unavailable)
            return Pair(null, unavailable)
        }

        logger.info("Crawl X-SQL: executing query on '{}': {}", pageUrl, normSQL.sql.take(300))

        // Running the statement and shaping its rows belongs to the executor, which the hyperlink
        // driven path uses as well; what stays here is the crawl's own contract — rows plus the
        // reason they are missing.
        val result = XSqlExecutor.execute(session, normSQL)
        if (!result.isSuccess) {
            logger.error("Failed to execute X-SQL on '{}': {} (SQL: {})", pageUrl, result.error, sql.take(300))
            return Pair(null, result.error)
        }

        val rows = result.rows ?: emptyList()
        if (rows.isEmpty()) {
            logger.info("Crawl X-SQL: query returned 0 rows for '{}'", pageUrl)
        } else {
            logger.info("Crawl X-SQL: extracted {} row(s) from '{}'", rows.size, pageUrl)

            // Detect silent failures: rows exist but ALL extracted data
            // fields (excluding URL/URI columns) are empty.  The page was
            // served from local storage, so this is the query matching
            // nothing rather than a cold cache — report it instead of
            // letting the caller read the empty fields as real data.
            val nonUrlColumnsHaveContent = rows.any { row ->
                row.any { (key, value) ->
                    !key.contains("url", ignoreCase = true)
                        && !key.contains("uri", ignoreCase = true)
                        && !key.contains("base", ignoreCase = true)
                        && value != null && value.toString().isNotBlank()
                }
            }
            if (!nonUrlColumnsHaveContent) {
                val msg = "The X-SQL extracted no data: every field is empty for '${pageUrl}'."
                logger.warn("Crawl X-SQL: {}", msg)
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

/**
 * Make sure the page the X-SQL will resolve is local, and report why the query must not run when
 * it cannot be.
 *
 * The order is the order of freshness and cost: the round's own page first, then local storage,
 * and a download only as the last resort — right here, so the page cannot be evicted between the
 * download and the query.
 *
 * @return null when a read-only load of [queryUrl] is a cache hit, otherwise the diagnostic that
 *   explains what is missing
 * */
private fun ensurePageIsLocal(
    session: PulsarSession,
    pageUrl: String,
    queryUrl: String,
    page: WebPage?,
    document: FeaturedDocument?,
): String? {
    // (a) The page this round fetched moments ago.  It is only the page of `queryUrl` while the
    // statement names the url the round fetched: a statement that hard-codes a different url asks
    // about a different page, and freezing this one under that key would serve its content for a
    // url it does not belong to.
    val sameTarget = ScrapeAPIUtils.resolveQueryUrl(session, pageUrl) == queryUrl
    if (page != null && sameTarget) {
        if (ScrapeAPIUtils.freezePageForQuery(session, queryUrl, page, document)) {
            logger.debug(
                "Crawl X-SQL: froze the round's page for '{}' ({} bytes, document={})",
                queryUrl, page.contentLength, document != null
            )
            return null
        }
    }

    // (b) Already local: an earlier round, an earlier crawl, or this round under another key (a
    // redirect keeps the page, not the key).  A copy that only exists in the store is promoted
    // into the page cache, so the engine answers from there instead of walking its own fetch
    // state; the fetch time goes into the log because a stored copy can be older than this crawl.
    if (ScrapeAPIUtils.isPageLocalForQuery(session, queryUrl)) {
        logger.debug("Crawl X-SQL: '{}' is already in the local page cache", queryUrl)
        return null
    }
    val stored = ScrapeAPIUtils.promoteStoredPage(session, queryUrl)
    if (stored != null && ScrapeAPIUtils.isPageLocalForQuery(session, queryUrl)) {
        logger.debug(
            "Crawl X-SQL: serving '{}' from local storage (fetched at {})", queryUrl, stored.prevFetchTime
        )
        return null
    }

    // (c) Nothing local at all: download now, at the last moment before the query.
    return try {
        val downloaded = runBlocking {
            withTimeout(PAGE_DOWNLOAD_TIMEOUT) { session.load(queryUrl, "-refresh") }
        }
        when {
            downloaded.isNil || !downloaded.protocolStatus.isSuccess ->
                "The page '$queryUrl' could not be downloaded (status " +
                    "${downloaded.protocolStatus.minorCode}), so the X-SQL was not executed. " +
                    "Re-fetch the page and retry."

            downloaded.contentLength == 0L ->
                "The page '$queryUrl' was downloaded empty (status " +
                    "${downloaded.protocolStatus.minorCode}, 0 bytes), so the X-SQL was not executed."

            !ScrapeAPIUtils.freezePageForQuery(session, queryUrl, downloaded) ->
                "The page '$queryUrl' was downloaded but is not served from the local cache " +
                    "(it expired before the query), so the X-SQL was not executed."

            else -> {
                logger.debug(
                    "Crawl X-SQL: downloaded '{}' ({} bytes) immediately before the query",
                    queryUrl, downloaded.contentLength
                )
                null
            }
        }
    } catch (e: Exception) {
        "The page '$queryUrl' is not local and could not be downloaded within " +
            "${PAGE_DOWNLOAD_TIMEOUT.inWholeSeconds}s (${e.javaClass.simpleName}: ${e.message}), " +
            "so the X-SQL was not executed. Re-fetch the page and retry."
    }
}
