package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.RegexExtractor
import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.common.types.ValueStringJSON
import com.google.common.annotations.Beta
import org.apache.commons.lang3.StringUtils
import org.h2.value.*
import java.sql.ResultSet
import java.text.SimpleDateFormat
import java.util.*

@Suppress("unused")
@UDFGroup
object CommonFunctions {

    @UDFunction(description = "Test if the given string is a number")
    @JvmStatic
    fun isNumeric(str: String): Boolean {
        return StringUtils.isNumeric(str)
    }

    @UDFunction(description = "Get the top private domain of the url")
    @JvmStatic
    fun getTopPrivateDomain(url: String): String {
        return URLUtils.getTopPrivateDomain(url)
    }

    @UDFunction(description = "Extract the first group of the result of java.util.regex.matcher()")
    @JvmStatic
    fun re1(text: String, regex: String): String {
        return RegexExtractor().re1(text, regex)
    }

    @UDFunction(description = "Extract the nth group of the result of java.util.regex.matcher()")
    @JvmStatic
    fun re1(text: String, regex: String, group: Int): String {
        return RegexExtractor().re1(text, regex, group)
    }

    @UDFunction(description = "Extract two groups of the result of java.util.regex.matcher()")
    @JvmStatic
    fun re2(text: String, regex: String): ValueArray {
        val result = RegexExtractor().re2(text, regex)
        val array = arrayOf(ValueString.get(result.key), ValueString.get(result.value))
        return ValueArray.get(array)
    }

    @UDFunction(description = "Extract two groups(key and value) of the result of java.util.regex.matcher()")
    @JvmStatic
    fun re2(text: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray {
        val result = RegexExtractor().re2(text, regex, keyGroup, valueGroup)
        val array = arrayOf(ValueString.get(result.key), ValueString.get(result.value))
        return ValueArray.get(array)
    }

    @UDFunction(description = "Create a ValueArray from vararg values")
    @JvmStatic
    fun makeArray(vararg values: Value): ValueArray {
        return ValueArray.get(values)
    }

    @UDFunction(description = "Create a ValueArray by repeating a given value n times")
    @JvmStatic
    fun makeArrayN(value: Value, n: Int): ValueArray {
        val values = Array(n) { value }
        return ValueArray.get(values)
    }

    /**
     * The first column is treated as the key while the second one is treated as the value
     * */
    @UDFunction(description = "Convert the first two columns of a ResultSet into a JSON object (column 1 = key, column 2 = value)")
    @JvmStatic
    fun toJson(rs: ResultSet): String {
        if (rs.metaData.columnCount < 2) {
            return "{}"
        }

        val map = mutableMapOf<String, String>()
        rs.beforeFirst()
        while (rs.next()) {
            val k = rs.getString(1).removeSurrounding("'")
            val v = rs.getString(2).removeSurrounding("'")
            map[k] = v
        }

        return Pson.toJson(map)
    }

    @Beta
    @UDFunction(description = "Create an empty ValueStringJSON initialized with '{}'")
    @JvmStatic
    fun makeValueStringJSON(): ValueStringJSON {
        return ValueStringJSON.get("{}")
    }

    @Beta
    @UDFunction(description = "Create a ValueStringJSON from a JSON text string and a Java class name for deserialization")
    @JvmStatic
    fun makeValueStringJSON(jsonText: String, javaClassName: String): ValueStringJSON {
        return ValueStringJSON.get(jsonText, javaClassName)
    }

    /**
     * For all ValueInts in the values, find out the minimal value, ignore non-integer values
     * */
    @UDFunction(description = "Find the minimum integer value in the array, ignoring non-integer entries")
    @JvmStatic
    fun intArrayMin(values: ValueArray): Value {
        return values.list.filterIsInstance<ValueInt>().minByOrNull { it.int } ?: ValueNull.INSTANCE
    }

    /**
     * For all ValueInts in the values, find out the maximal value, ignore non-integer values
     * */
    @UDFunction(description = "Find the maximum integer value in the array, ignoring non-integer entries")
    @JvmStatic
    fun intArrayMax(values: ValueArray): Value {
        return values.list.filterIsInstance<ValueInt>().maxByOrNull { it.int } ?: ValueNull.INSTANCE
    }

    /**
     * For all ValueFloats in the values, find out the minimal value, ignore non-float values
     * */
    @UDFunction(description = "Find the minimum float value in the array, ignoring non-float entries")
    @JvmStatic
    fun floatArrayMin(values: ValueArray): Value {
        return values.list.filterIsInstance<ValueFloat>().minByOrNull { it.float } ?: ValueNull.INSTANCE
    }

    /**
     * For all ValueFloats in the values, find out the maximal value, ignore non-float values
     * */
    @UDFunction(description = "Find the maximum float value in the array, ignoring non-float entries")
    @JvmStatic
    fun floatArrayMax(values: ValueArray): Value {
        return values.list.filterIsInstance<ValueFloat>().maxByOrNull { it.float } ?: ValueNull.INSTANCE
    }

    @UDFunction(description = "Return the string representation of a Value")
    @JvmStatic
    fun getString(value: Value): String {
        return value.string
    }

    @UDFunction(description = "Check if a ValueArray is empty")
    @JvmStatic
    fun isEmpty(array: ValueArray): Boolean {
        return array.list.isEmpty()
    }

    @UDFunction(description = "Check if a ValueArray is not empty")
    @JvmStatic
    fun isNotEmpty(array: ValueArray): Boolean {
        return array.list.isNotEmpty()
    }

    @UDFunction(description = "Format a timestamp string (milliseconds since epoch) using the given date format pattern")
    @JvmStatic
    @JvmOverloads
    fun formatTimestamp(timestamp: String, fmt: String = "yyyy-MM-dd HH:mm:ss"): String {
        val time = timestamp.toLongOrNull() ?: 0
        return formatTimestamp(time, fmt)
    }

    private fun formatTimestamp(timestamp: Long, fmt: String): String {
        return SimpleDateFormat(fmt).format(Date(timestamp))
    }
}
