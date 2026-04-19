package ai.platon.pulsar.agentic.tools.high.crawl.common

import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import java.util.*

object ScrapeAPIUtils {

    private val allowedArgs = LoadOptions.apiPublicOptionNames
    private val allowedScrapeUDFs = arrayOf("loadandselect", "loadoutpages")

    @Throws(IllegalArgumentException::class)
    fun normalize(rawSql: String?): NormXSQL {
        if (rawSql == null) {
            throw IllegalArgumentException("SQL is required")
        }
        val configuredUrl = extractUrlFromFromClause(rawSql) ?: throw IllegalArgumentException("No url found in sql: >>>$rawSql<<<")

        val (url, args) = URLUtils.splitUrlArgs(configuredUrl)
        val sql = eraseExpireOptions(rawSql)

        return NormXSQL(url, args, sql)
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
