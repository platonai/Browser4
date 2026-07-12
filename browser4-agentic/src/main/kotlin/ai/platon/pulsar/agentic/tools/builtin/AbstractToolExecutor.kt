package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.DirectValue
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.browser4.api.common.JsEvaluation
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import kotlin.reflect.KClass

interface ToolExecutor {

    val domain: String
    val receiverClass: KClass<*>

    suspend fun callFunctionOn(tc: ToolCall, receiver: Any = Any()): TcEvaluate

    fun help(): String
    fun help(method: String): String

    /**
     * Returns all tool specifications registered in this executor, keyed by method name.
     */
    fun getToolSpecs(): Map<String, ToolSpec>
}

abstract class AbstractToolExecutor : ToolExecutor {

    private val logger = getLogger(AbstractToolExecutor::class)

    protected val toolSpec = mutableMapOf<String, ToolSpec>()

    override fun getToolSpecs(): Map<String, ToolSpec> = toolSpec.toMap()

    override fun help(): String {
        return toolSpec.values.mapNotNull { it.description }.joinToString("\n")
    }

    override fun help(method: String): String {
        val spec = toolSpec[method] ?: return ""
        return """
            ${spec.description}
            ${spec.expression}
        """.trimIndent()
    }

    override suspend fun callFunctionOn(tc: ToolCall, receiver: Any): TcEvaluate {
        val domain = tc.domain
        val functionName = tc.method
        val args = tc.arguments
        val pseudoExpression = tc.pseudoExpression

        return try {
            val r = callFunctionOn(domain, functionName, args, receiver)

            val (value, className) = when {
                r is JsEvaluation -> {
                    val c = when {
                        r.cdpType == "undefined" -> "undefined"
                        r.cdpType == "object" && r.cdpSubtype == "null" -> "null"
                        !r.className.isNullOrBlank() -> r.className
                        r.value != null -> r.value!!::class.qualifiedName
                        else -> "null"
                    }
                    r.value to c
                }
                r == null -> null to "null"
                r == Unit -> null to null
                else -> {
                    val qualifiedName = r::class.qualifiedName
                    if (r is String || r is Number || r is Boolean
                        || r is Map<*, *> || r is Collection<*> || r is Array<*>
                        || r is DirectValue
                    ) {
                        r to qualifiedName
                    } else {
                        // Wrap non-serializable domain objects in a description map
                        // to prevent Jackson from walking into internal object graphs
                        // (e.g. PulsarWebDriver → browser → settings → Spring Environment → ...)
                        mapOf(
                            "type" to qualifiedName,
                            "description" to r.toString()
                        ) to qualifiedName
                    }
                }
            }
            TcEvaluate(value = value, className = className, expression = pseudoExpression)
        } catch (e: Exception) {
            logger.warn("Error executing expression: {} - {}", pseudoExpression, e.brief())
            val h = help(functionName)
            TcEvaluate(pseudoExpression, e, help = h)
        }
    }

    @Throws(IllegalArgumentException::class)
    abstract suspend fun callFunctionOn(domain: String, functionName: String, args: Map<String, Any?>, receiver: Any): Any?

    // ---------------- Shared helpers for named parameter executors ----------------
    protected fun validateArgs(
        args: Map<String, Any?>,
        allowed: Set<String>,
        required: Set<String> = allowed,
        functionName: String
    ) {
        required.forEach {
            if (!args.containsKey(it)) throw IllegalArgumentException("Missing required parameter '$it' for $functionName")
        }
        args.keys.forEach {
            if (it !in allowed) throw IllegalArgumentException("Extraneous parameter '$it' for $functionName. Allowed=$allowed")
        }
    }

    protected fun paramString(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: String? = null
    ): String? {
        val v = args[name]
        return when (v) {
            null if required && default == null -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            null -> default
            else -> v.toString()
        }
    }

    protected fun paramInt(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Int? = null
    ): Int? {
        val v = args[name] ?: when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> return default
        }
        return v.toString().toIntOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be Int for $functionName | actual='${v}'")
    }

    protected fun paramLong(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Long? = null
    ): Long? {
        val v = args[name] ?: when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> return default
        }
        return v.toString().toLongOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be Long for $functionName | actual='${v}'")
    }

    protected fun paramBool(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Boolean? = null
    ): Boolean? {
        val v = args[name] ?: return when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> default
        }
        return when (v.toString().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Parameter '$name' must be Boolean for $functionName | actual='${v}'")
        }
    }

    protected fun paramStringList(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true
    ): List<String> {
        val v = args[name] ?: when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> return emptyList()
        }
        return when (v) {
            is List<*> -> v.filterIsInstance<String>()
            is Array<*> -> v.filterIsInstance<String>()
            is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw IllegalArgumentException("Parameter '$name' must be a list[string] or comma separated string for $functionName | actual='${v}'")
        }
    }

    protected fun paramDouble(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Double? = null
    ): Double? {
        val v = args[name] ?: return when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> default
        }

        return v.toString().toDoubleOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be Double for $functionName | actual='${v}'")
    }
}
