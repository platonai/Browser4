package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import org.h2.value.Value
import org.h2.value.ValueArray

@UDFGroup(namespace = "ARRAY")
object ArrayFunctions {

    @UDFunction(description = "Join all elements of a ValueArray into a single string with the given separator")
    @JvmStatic
    fun joinToString(values: ValueArray, separator: String): String {
        return values.list.joinToString(separator) { it.string }
    }

    @UDFunction(description = "Return the first non-blank value from the array, or null if none found")
    @JvmStatic
    fun firstNotBlank(values: ValueArray): Value? {
        return values.list.firstOrNull { it.string.isNotBlank() }
    }

    @UDFunction(description = "Return the first non-empty value from the array, or null if none found")
    @JvmStatic
    fun firstNotEmpty(values: ValueArray): Value? {
        return values.list.firstOrNull { it.string.isNotEmpty() }
    }
}
