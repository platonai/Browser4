package ai.platon.pulsar.agentic.tools.high.crawl.common

import org.apache.commons.lang3.StringUtils
import java.util.*

object APISQLUtils {
    private val forbiddenStatements = arrayOf("delete", "insert", "truncate", "drop")

    fun sanitize(sql: String?): String {
        if (sql == null) {
            throw IllegalArgumentException("Sql is required")
        }

        var sql0 = sql.trim().lowercase(Locale.getDefault())
        if (!sql0.startsWith("select")) {
            throw IllegalArgumentException("Only select statements are supported")
        }

        sql0 = sql0.removeSuffix(";")
        val quoted = StringUtils.substringsBetween(sql0 , "'", "'")
        quoted?.forEach { sql0 = sql0.replace(it, "") }
        if (sql0.contains(";")) {
            throw IllegalArgumentException("Only one statement is supported")
        }

        if (forbiddenStatements.any { sql0.contains("$it ") }) {
            throw IllegalArgumentException("Statement is forbidden")
        }

        return sql.split("\n")
                .filterNot { it.trim().startsWith("--") }
                .joinToString("\n")

    }
}
