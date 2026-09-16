package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkType
import java.util.*


object ScrapeAPIUtils {

    private val logger = getLogger(this)

    private val allowedArgs = LoadOptions.apiPublicOptionNames
    private val allowedScrapeUDFs = arrayOf("load", "loadandselect", "loadoutpages", "loadallpages")

    /**
     * The load options that make the `load` / `load_and_select` UDFs go back to the web, and
     * therefore break the read-only contract of a statement running *inside* the h2 engine.
     *
     * `refresh` is the decisive one: `-refresh` expands to `-ignoreFailure -i 0s` and clears the
     * fetch retry counters, so `LoadOptions.isExpired()` answers true for *any* local copy of the
     * page. `PulsarSession.load()` then misses its read-only shortcut
     * (`AbstractPulsarSession.createPageWithCachedCoreOrNull`, which requires both `readonly` and
     * a page that has not expired) and falls through to a real web load — network I/O, store
     * writes and a re-rendered page while the query is still executing.
     * */
    val webLoadOptionNames = arrayOf("refresh", "expires", "expireAt", "itemExpires", "itemExpireAt")

    /**
     * The option that makes an inner UDF load serve the local copy without modifying it.
     * */
    const val READ_ONLY_OPTION = "-readonly"

    @Throws(IllegalArgumentException::class)
    fun normalize(rawSql: String?,
                     eraseArgs: String? = null,
                     addArgs: String? = null,
                     noExpire: Boolean = true
    ): NormXSQL {
        if (rawSql == null) {
            throw IllegalArgumentException("SQL is required")
        }

        val configuredUrl = extractConfiguredUrl(rawSql) ?: throw IllegalArgumentException("Illegal sql, no url found>>>\n$rawSql\n<<<")

        val (url, args) = URLUtils.splitUrlArgs(configuredUrl)

        if (!URLUtils.isStandard(url)) {
            throw IllegalArgumentException("Malformed url: <$url>")
        }

        var newArgs = if (!eraseArgs.isNullOrBlank()) {
            val eraseArgList = eraseArgs.split(" ").map { it.trim('-') }.toTypedArray()
            LoadOptions.eraseOptions(args, *eraseArgList)
        } else args

        if (noExpire) {
            newArgs = eraseExpireOptions(newArgs)
        }
        if (!addArgs.isNullOrBlank()) {
            newArgs += " $addArgs"
        }
        newArgs = newArgs.trim()

        val newConfiguredUrl = "$url $newArgs".trim()
        val newSQL = rawSql.replace(configuredUrl, newConfiguredUrl)

        return NormXSQL(url, args, newSQL)
    }

    /**
     * Seal [rawSql] for execution *inside* the h2 engine, where the url in the FROM clause is
     * resolved by the `load` / `load_and_select` UDFs — `PulsarSession.load()` — and not by the
     * caller.
     *
     * `-readonly` is what makes that inner load read-only: `AbstractPulsarSession` serves a
     * read-only load from the global page cache, and `LoadComponent` then neither writes the page
     * back to the cache nor persists it to the WebDB. It is a *cache-hit* guarantee and not a
     * fetch prohibition, so every option that would force a web load ([webLoadOptionNames]) is
     * erased from the url inside the SQL first: with `-refresh` left in place the UDF re-fetches
     * the page over the network while the query runs, which is exactly what the contract forbids.
     *
     * The other half of the contract belongs to the caller: the page must be downloaded, or
     * already be in local storage, *immediately* before this statement runs — see
     * [freezePageForQuery].
     *
     * @throws IllegalArgumentException when [rawSql] has no url in its FROM clause
     * @throws IllegalStateException when the sealed statement could still load from the web
     * */
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun normalizeForReadOnlyQuery(rawSql: String?): NormXSQL {
        // `-parse` means nothing to the engine (the UDF parses the page itself), and the web-load
        // options are erased *by name* so that no option can survive as a partial token.
        val eraseArgs = (listOf("parse") + webLoadOptionNames).joinToString(" ") { "-$it" }
        val addArgs = READ_ONLY_OPTION.takeUnless { hasReadOnlyOption(rawSql) }
        // The expiry options are covered by `eraseArgs`; the regex based erasure is deliberately
        // skipped so a sealed statement can never be rewritten a second time.
        val normSQL = normalize(rawSql, eraseArgs = eraseArgs, addArgs = addArgs, noExpire = false)
        return checkReadOnlyQuery(normSQL)
    }

    /**
     * Verify that [normSQL] cannot make an X-SQL `load` UDF fetch the page from the web.
     *
     * @return [normSQL], so the check chains onto a normalization
     * @throws IllegalStateException when the url inside the statement is missing `-readonly`, or
     *         still carries a web-load option. Running such a statement mutates the page and
     *         repeats a fetch while the query is executing.
     * */
    @Throws(IllegalStateException::class)
    fun checkReadOnlyQuery(normSQL: NormXSQL): NormXSQL {
        val offenders = findWebLoadOptions(normSQL.sql)
        check(offenders.isEmpty()) {
            "X-SQL would load the page from the web while the query runs: " +
                    offenders.joinToString(", ") { "-$it" } +
                    " in the FROM clause of >>>${normSQL.sql.take(300)}<<<"
        }
        check(hasReadOnlyOption(normSQL.sql)) {
            "X-SQL is not sealed with $READ_ONLY_OPTION, so the url inside the statement can be " +
                    "re-fetched and modified while the query runs: >>>${normSQL.sql.take(300)}<<<"
        }
        return normSQL
    }

    /**
     * True when the url inside [sql] is sealed: read-only, and unable to force a web load.
     * */
    fun isReadOnlyQuery(sql: String?): Boolean =
        findWebLoadOptions(sql).isEmpty() && hasReadOnlyOption(sql)

    /**
     * The web-load options still present on the url inside [sql], empty when the url is sealed.
     * */
    fun findWebLoadOptions(sql: String?): List<String> {
        val optionTokens = optionTokensIn(sql)
        return webLoadOptionNames.filter { name ->
            LoadOptions.getOptionNames(name).any { it in optionTokens }
        }
    }

    private fun hasReadOnlyOption(sql: String?): Boolean {
        val optionTokens = optionTokensIn(sql)
        return LoadOptions.getOptionNames("readonly").any { it in optionTokens }
    }

    /**
     * The option tokens of the url in the FROM clause of [sql]. Empty when there is no url, so a
     * caller that cannot parse the statement gets `false` from every option check.
     * */
    private fun optionTokensIn(sql: String?): Set<String> {
        val configuredUrl = runCatching { extractConfiguredUrl(sql) }.getOrNull() ?: return emptySet()
        val args = URLUtils.splitUrlArgs(configuredUrl).second
        return args.split("\\s+".toRegex()).filter { it.startsWith("-") }.toSet()
    }

    /**
     * The url the X-SQL engine resolves the page by.
     *
     * The engine normalizes the url it reads from the FROM clause before it looks it up
     * (`AbstractPulsarSession.load(NormURL)` keys the global page cache by `normURL.urlString`),
     * so the cache keys used here must come from the same normalization. Returns [url] unchanged
     * when it cannot be normalized, which keeps an exotic url diagnosable instead of fatal.
     * */
    fun resolveQueryUrl(session: PulsarSession, url: String): String =
        runCatching { session.normalize(url, READ_ONLY_OPTION).urlString }.getOrDefault(url)

    /**
     * Freeze [page] — and its [document] — in the session's local caches under [queryUrl], the url
     * the h2 engine will resolve the page by, and report whether a read-only load of that url is
     * now a guaranteed cache hit.
     *
     * The read-only guarantee only holds while the page is *already local*: on a cache miss
     * `PulsarSession.load()` silently falls through to a full web load, which both modifies the
     * page and repeats the fetch the caller has just performed. Registering the page under
     * [queryUrl] as well as under its own url is what closes that window — a page that a redirect
     * moved keeps its content but not its key — and the frozen copy is verified at the last
     * moment, so the window the UDF looks through is the microseconds before the statement runs
     * instead of the seconds since the fetch.
     *
     * [page] must be the page the caller loaded *for* [queryUrl]: freezing an unrelated page would
     * serve that page's content for the url in the SQL.
     *
     * @return true when a read-only load of [queryUrl] is a cache hit with content
     * */
    fun freezePageForQuery(
        session: PulsarSession,
        queryUrl: String,
        page: WebPage,
        document: FeaturedDocument? = null,
    ): Boolean {
        // A NIL page carries no content, and caching it under the query url would hide the very
        // miss this method exists to prevent.
        if (page.isNil) {
            return false
        }

        val pageCache = session.globalCache.pageCache
        val documentCache = session.globalCache.documentCache

        for (key in linkedSetOf(page.url, queryUrl)) {
            if (key.isBlank()) {
                continue
            }
            pageCache.putDatum(key, page)
            document?.let { documentCache.putDatum(key, it) }
        }

        return isPageLocalForQuery(session, queryUrl)
    }

    /**
     * True when the engine's own cache-hit test passes for [queryUrl]: the page is in the global
     * page cache and the read-only options do not consider it expired.
     *
     * This mirrors `AbstractPulsarSession.createPageWithCachedCoreOrNull` together with
     * `getCachedPageOrNull`, which is what decides between serving the page from local storage and
     * loading it from the web. It deliberately says nothing about the *content* of the cached copy:
     * a cached page without a body still answers the load locally (and yields an empty result set),
     * while an uncached one reaches the web even when its body is missing.
     * */
    fun isPageLocalForQuery(session: PulsarSession, queryUrl: String): Boolean {
        val page = session.globalCache.pageCache.getDatum(queryUrl) ?: return false
        val options = session.options(READ_ONLY_OPTION)
        return !options.isExpired(page.prevFetchTime)
    }

    /**
     * Promote a page that is already in local storage into the global page cache, so the engine
     * serves it without walking its own fetch state. Returns the promoted page, or null when the
     * local storage holds nothing usable for [queryUrl].
     * */
    fun promoteStoredPage(session: PulsarSession, queryUrl: String): WebPage? {
        val stored = session.getOrNull(queryUrl)?.takeIf { !it.isNil && it.contentLength > 0L } ?: return null
        session.globalCache.pageCache.putDatum(queryUrl, stored)
        logger.debug(
            "Promoted the stored page for '{}' into the page cache | fetched at {}, {} bytes",
            queryUrl, stored.prevFetchTime, stored.contentLength
        )
        return stored
    }

    @Throws(IllegalArgumentException::class)
    fun checkArgs(args: String?) {
        args?.split("\\s+".toRegex())?.filter { it.startsWith("-") }?.forEach { arg ->
            if (arg !in allowedArgs) {
                throw IllegalArgumentException("Argument is not allowed: <$arg>")
            }
        }
    }

    fun isScrapeUDF(sql: String?): Boolean {
        if (sql.isNullOrBlank()) {
            return false
        }

        val s = sql.replace("_", "").lowercase(Locale.getDefault())
        return allowedScrapeUDFs.any { it in s }
    }

    @Throws(IllegalArgumentException::class)
    fun checkSql(sql: String): String {
        return try {
            APISQLUtils.sanitize(sql)
        } catch (e: Exception) {
            throw IllegalArgumentException(e.message)
        }
    }

    fun eraseUrlOptions(sql: String, vararg fields: String): String {
        // do not forget the blank
        val separator = " | "
        val optionNames = fields.flatMap { LoadOptions.getOptionNames(it) }.joinToString(separator)
        return sql.replace(optionNames.toRegex(), " -erased ")
    }

    fun eraseExpireOptions(sql: String): String {
        return eraseUrlOptions(sql, "expires", "expireAt", "itemExpires", "itemExpireAt")
    }

    @Throws(IllegalArgumentException::class)
    fun sanitizeSQL(sql: String?): String {
        if (sql == null) {
            throw IllegalArgumentException("Sql is required")
        }

        return try {
            APISQLUtils.sanitize(sql)
        } catch (e: Exception) {
            getLogger(this).warn(sql)
            throw IllegalArgumentException(e.message)
        }
    }

    /**
     * Extract the url from the SQL, the url might be configured
     * */
    fun extractConfiguredUrl(sql: String?): String? {
        if (sql == null) {
            return null
        }

        val sql0 = sanitizeSQL(sql).replace("\\s+".toRegex(), " ")
        return if (sql0.contains(" from ", ignoreCase = true)) {
            ResultSetUtils.extractUrlFromFromClause(sql0)
        } else {
            // TODO: this branch is deprecated
            val input = sql0
            val linkExtractor = LinkExtractor.builder()
                .linkTypes(EnumSet.of(LinkType.URL))
                .build()
            val links = linkExtractor.extractLinks(input).iterator()

            if (links.hasNext()) {
                val link = links.next()
                input.substring(link.beginIndex, link.endIndex)
            } else {
                null
            }
        }
    }

    /**
     * Extract the url from the SQL, the url might be configured
     * */
    fun extractUrlFromFromClause(sql: String?): String? {
        if (sql == null) {
            return null
        }

        val sql0 = checkSql(sql).replace("\\s+".toRegex(), " ")
        return if (sql0.contains(" from ", ignoreCase = true)) {
            ResultSetUtils.extractUrlFromFromClause(sql0)
        } else {
            null
        }
    }
}
