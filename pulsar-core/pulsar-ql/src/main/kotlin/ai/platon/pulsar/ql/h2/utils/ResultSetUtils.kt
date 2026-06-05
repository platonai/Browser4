package ai.platon.pulsar.ql.h2.utils

import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.ql.common.ResultSets
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.ql.h2.addColumn
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils.getTextEntityFromCurrentRecord
import org.h2.tools.SimpleResultSet
import org.h2.value.DataType
import org.h2.value.Value
import java.sql.*

/**
 * Only for small result sets
 * */
object ResultSetUtils {
    private val logger = getLogger(this)
    val sqlLog = getLogger(ResultSetUtils::class.java.packageName, ".Jdbc")

    private val a = AppConstants.SHORTEST_VALID_URL_LENGTH
    private const val b = 2048
    val QUOTED_URL_REGEX = "'https?://.{$a,$b}?'".toRegex()

    fun count(rs: ResultSet): Int {
        var count = 0
        rs.beforeFirst()
        while (rs.next()) ++count
        return count
    }

    fun count(rs: ResultSet, predicate: (ResultSet) -> Boolean): Int {
        var count = 0
        rs.beforeFirst()
        while (rs.next()) {
            if (predicate(rs)) ++count
        }
        return count
    }

    fun countRemaining(rs: ResultSet): Int {
        var count = 0
        while (rs.next()) ++count
        return count
    }

    /**
     * Transpose a result set if the result set has only one row and every cell is an array
     * */
    fun transpose(rs: ResultSet): ResultSet {
        val newRs = ResultSets.newSimpleResultSet()

        rs.beforeFirst()
        if (!rs.next() || rs.wasNull()) {
            return newRs
        }

        val columnCount = rs.metaData.columnCount
        IntRange(1, columnCount).map { i -> newRs.addColumn(rs.metaData.getColumnName(i)) }

        val array = rs.getArray(1)
        val nativeArray = (array.array as? Array<*>) ?: return newRs
        val newRowCount = nativeArray.size

        if (columnCount == 0) {
            return newRs
        }

        val rows = Array<Array<String?>>(newRowCount) { arrayOfNulls(columnCount) }

        IntRange(0, newRowCount - 1).forEach outer@{ i ->
            IntRange(0, columnCount - 1).forEach inner@{ j ->
                val a = rs.getArray(j + 1)
                if (a.array !is Array<*>) {
                    logger.warn("The {}th column is expected to be an array, actual {}", j + 1, a.array.javaClass.name)
                    rows[i][j] = null
                    return@inner
                }

                val na = a.array as Array<*>
                if (i < na.size) {
                    rows[i][j] = na[i].toString()
                } else {
                    rows[i][j] = null
                }
            }
        }

        rows.forEach { row -> newRs.addRow(*row) }
        return newRs
    }

    /**
     * A very simple and limited result set creator
     * @see [org.springframework.jdbc.core.StatementCreatorUtils]
     * */
    fun copyResultSet(sourceRs: ResultSet, maxRows: Int = 10000): ResultSet {
        val sinkRs = SimpleResultSet().apply { autoClose = false }

        val metaData = sourceRs.metaData
        val columnCount = metaData.columnCount

        IntRange(1, columnCount).forEach { i ->
            val name = metaData.getColumnName(i)
            val type = metaData.getColumnType(i)
            val precision = metaData.getPrecision(i)
            val scale = metaData.getScale(i)

            sinkRs.addColumn(name, type, precision, scale)
        }

        var numRows = 0
        sourceRs.beforeFirst()
        while (sourceRs.next() && numRows++ < maxRows) {
            val row = arrayOfNulls<Any?>(columnCount)
            IntRange(1, columnCount).forEach { i ->
                val type = sourceRs.metaData.getColumnType(i)
                /**
                 * TODO: handle other types
                 * */
                row[i - 1] = when (type) {
                    Types.BOOLEAN -> sourceRs.getBoolean(i)
                    Types.FLOAT -> sourceRs.getFloat(i)
                    Types.INTEGER -> sourceRs.getInt(i)
                    else -> sourceRs.getString(i)
                }
            }

            sinkRs.addRow(*row)
        }

        return sinkRs
    }

    fun joinSimpleTables(tableNames: List<String>, stat: Statement): ResultSet {
        if (tableNames.isEmpty()) {
            return SimpleResultSet().apply { autoClose = true }
        }

        // make a join
        val rows = mutableMapOf<String, ArrayList<String?>>()
        tableNames.forEachIndexed { i, tableName ->
            val rs = stat.executeQuery("SELECT * FROM $tableName")
            val columnCount = rs.metaData.columnCount
            while (rs.next()) {
                val u = rs.getString("URL")
                val row = rows.computeIfAbsent(u) { ArrayList() }
                IntRange(1, columnCount).mapTo(row) { rs.getString(it) }
            }
        }

        val rs2 = SimpleResultSet()
        // remove row if all values are null
        val rows2 = rows.filterNot { it.value.all { it == null || it == "null" } }
        if (rows2.values.isNotEmpty()) {
            rows2.values.first().forEachIndexed { i, _ ->
                rs2.addColumn("C$i", DataType.convertTypeToSQLType(Value.STRING), 0, 0)
            }
            rows2.values.forEach { rs2.addRow(*it.toTypedArray()) }
        }
        return rs2
    }

    @Throws(SQLException::class)
    fun getColumnNames(resultSet: ResultSet): List<String> {
        val names = mutableListOf<String>()

        val columnCount = resultSet.metaData.columnCount
        IntRange(1, columnCount).forEach { i ->
            val name = resultSet.metaData.getColumnName(i)
            names.add(name)
        }

        return names
    }

    @Throws(SQLException::class)
    fun getEntitiesFromResultSet(resultSet: ResultSet): List<Map<String, Any?>> {
        val entities = arrayListOf<Map<String, Any?>>()
        resultSet.beforeFirst()
        while (resultSet.next()) {
            entities.add(getEntityFromCurrentRecord(resultSet))
        }
        return entities
    }

    @Throws(SQLException::class)
    fun getEntitiesFromResultSetTo(
        resultSet: ResultSet,
        destination: MutableList<Map<String, Any?>>,
    ): MutableList<Map<String, Any?>> {
        resultSet.beforeFirst()
        while (resultSet.next()) {
            destination.add(getEntityFromCurrentRecord(resultSet))
        }
        return destination
    }

    @Throws(SQLException::class)
    fun getEntitiesFromResultSets(resultSets: Iterable<ResultSet>): List<Map<String, Any?>> {
        return resultSets.map<ResultSet, Map<String, Any>> {
            mapOf(
                "result" to getEntitiesFromResultSet(it),
                "statement" to it.statement,
                "columnCount" to it.metaData.columnCount
            )
        }
    }

    /**
     * TODO: this function is the same with [getTextEntityFromCurrentRecord]
     * */
    @Throws(SQLException::class)
    private fun getEntityFromCurrentRecord(resultSet: ResultSet): Map<String, Any?> {
        val metaData = resultSet.metaData
        val columnCount: Int = metaData.columnCount
        val mapRecord = mutableMapOf<String, Any?>()
        for (i in 1..columnCount) {
            val columnName = metaData.getColumnName(i).lowercase()
            val columnType = metaData.getColumnType(i)
            // remove ValueDom from the result
            if (columnType != ValueDom.type && columnName !in arrayOf("DOC", "DOM")) {
                toJacksonCompatibleMapRecord(i, metaData, mapRecord, resultSet)
            }
        }
        return mapRecord
    }

    @Throws(SQLException::class)
    fun getTextEntitiesFromResultSet(resultSet: ResultSet): List<Map<String, Any?>> {
        val entities = arrayListOf<Map<String, Any?>>()
        resultSet.beforeFirst()
        while (resultSet.next()) {
            entities.add(getTextEntityFromCurrentRecord(resultSet))
        }
        return entities
    }

    @Throws(SQLException::class)
    private fun getTextEntityFromCurrentRecord(resultSet: ResultSet): Map<String, Any?> {
        val metaData = resultSet.metaData
        val columnCount: Int = metaData.columnCount
        val mapRecord = mutableMapOf<String, Any?>()
        for (i in 1..columnCount) {
            val columnName = metaData.getColumnName(i).lowercase()
            val columnType = metaData.getColumnType(i)
            // remove ValueDom from the result

            if (columnType != ValueDom.type && columnName !in arrayOf("DOC", "DOM")) {
                toJacksonCompatibleMapRecord(i, metaData, mapRecord, resultSet)
            }
        }

        return mapRecord
    }

    @Throws(SQLException::class)
    fun toJson(resultSet: ResultSet, prettyPrinting: Boolean = false): String {
        val entities = getTextEntitiesFromResultSet(resultSet)
        return if (prettyPrinting) {
            prettyPulsarObjectMapper().writeValueAsString(entities)
        } else {
            pulsarObjectMapper().writeValueAsString(entities)
        }
    }

    @Throws(SQLException::class)
    fun toJson(resultSets: List<ResultSet>, prettyPrinting: Boolean = false): String {
        val entities = resultSets.map {
            mapOf(
                "result" to getTextEntitiesFromResultSet(it),
                "statement" to it.statement,
                "columnCount" to it.metaData.columnCount
            )
        }

        return if (prettyPrinting) {
            prettyPulsarObjectMapper().writeValueAsString(entities)
        } else {
            pulsarObjectMapper().writeValueAsString(entities)
        }
    }

    /**
     * Convert a record to a JSON compatible map, where each map value can be a [com.fasterxml.jackson.databind.JsonNode]
     * */
    private fun toJacksonCompatibleMapRecord(
        i: Int, metaData: ResultSetMetaData,
        record: MutableMap<String, Any?>, resultSet: ResultSet
    ) {
        val columnName = metaData.getColumnName(i).lowercase()
        val columnType = metaData.getColumnType(i)
        val columnClassName = metaData.getColumnClassName(i)

        when (columnType) {
            Types.BOOLEAN -> record[columnName] = resultSet.getBoolean(i)
            Types.FLOAT -> record[columnName] = resultSet.getFloat(i)
            Types.INTEGER -> record[columnName] = resultSet.getInt(i)
            Types.JAVA_OBJECT -> {
                val obj = resultSet.getObject(i)
                if (obj != null) {
                    record[columnName] = obj
                }
            }
            Types.OTHER -> {
                val obj = resultSet.getObject(i)

                if (obj != null) {
                    record[columnName] = obj
                }
            }
            else -> record[columnName] = resultSet.getString(i)
        }
    }
    /**
     * A simple method to extract url from a sql's from clause, for example,
     * extract `https://jd.com/` from the following sql:
     *
     * > select dom_first_text(dom, '#container'), dom_first_text(dom, '.price')
     * > from load_and_select('https://jd.com/', ':root body');
     *
     * Supports both single and double quotes, handles basic escaping.
     *
     * @param sql The sql to extract a url from
     * @return The url extracted from the sql, null if no such url
     */
    fun extractUrlFromFromClause(sql: String): String? {
        val len = sql.length
        var i = 0

        // Find the last 'from' keyword
        i = sql.lastIndexOf("from", ignoreCase = true)
        if (i <= 0 || i >= len) {
            return null
        }

        // Find opening parenthesis in from clause
        while (i < len && sql[i] != '(') {
            ++i
        }
        if (i == len) {
            return null
        }

        // Find the first quote (single or double)
        while (i < len && sql[i] != '\'' && sql[i] != '"') {
            ++i
        }
        if (i >= len) {
            return null
        }

        val quoteChar = sql[i]  // Remember which quote type we found
        ++i  // Move past opening quote
        if (i >= len) {
            return null
        }

        // Find matching closing quote, handling basic escaping
        val urlBuilder = StringBuilder()
        while (i < len) {
            val char = sql[i]

            when {
                char == '\\' && i + 1 < len -> {
                    // Handle escaped characters
                    urlBuilder.append(sql[i + 1])
                    i += 2
                }
                char == quoteChar -> {
                    // Found closing quote
                    val url = urlBuilder.toString()
                    return if (isValidUrl(url)) url else null
                }
                else -> {
                    urlBuilder.append(char)
                    ++i
                }
            }
        }

        return null  // No closing quote found
    }

    /**
     * Basic URL validation - checks if string looks like a URL
     */
    private fun isValidUrl(url: String): Boolean {
        return url.matches(Regex("^https?://.*"))
    }
}
