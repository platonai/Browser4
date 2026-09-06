package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import org.apache.commons.lang3.StringUtils
import java.util.*

object APISQLUtils {
    private val allowedStatements = arrayOf("select")
    private val forbiddenStatements = arrayOf("delete", "insert", "truncate", "drop")

    fun sanitize(sql: String?): String {
        if (sql == null) {
            throw IllegalArgumentException("Sql is required")
        }

        // Strip full-line '--' comments BEFORE statement-type detection.  A
        // query file that begins with a comment line (a natural way to annotate
        // a .sql file) is valid SQL; previously the lowercase+startsWith guard
        // ran first, so a leading comment failed with a misleading
        // "Only select statements are supported" even though the file contains
        // a single valid SELECT.  Comments can also carry ';' characters that
        // tripped the single-statement check.
        val withoutComments = sql.split("\n")
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
        val cleaned = withoutComments.trim()
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Sql is empty or contains only comments")
        }

        var sql0 = cleaned.lowercase(Locale.getDefault())
        if (!sql0.startsWith("select")) {
            throw IllegalArgumentException("Only select statements are supported")
        }

        sql0 = sql0.removeSuffix(";")
        val quoted = StringUtils.substringsBetween(sql0, "'", "'")
        quoted?.forEach { sql0 = sql0.replace(it, "") }
        if (sql0.contains(";")) {
            throw IllegalArgumentException("Only one statement is supported")
        }

        if (forbiddenStatements.any { sql0.contains("$it ") }) {
            throw IllegalArgumentException("Statement is forbidden")
        }

        return cleaned
    }
}
