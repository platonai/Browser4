package ai.platon.pulsar.agentic.tools.executors

import ai.platon.pulsar.agentic.common.SimpleKotlinParser
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.ai.TcEvaluate
import ai.platon.pulsar.skeleton.ai.ToolCall
import kotlin.reflect.KClass

interface ToolExecutor {

    val domain: String
    val targetClass: KClass<*>

    suspend fun execute(tc: ToolCall, target: Any): TcEvaluate
    suspend fun execute(expression: String, target: Any): TcEvaluate

    fun help(): String
    fun help(method: String): String
}

abstract class AbstractToolExecutor : ToolExecutor {

    private val logger = getLogger(this)
    private val simpleParser = SimpleKotlinParser()

    override suspend fun execute(tc: ToolCall, target: Any): TcEvaluate {
        val objectName = tc.domain
        val functionName = tc.method
        val args = tc.arguments
        val pseudoExpression = tc.pseudoExpression

        return try {
            val r = execute(objectName, functionName, args, target)

            val className = if (r == null) "null" else r::class.qualifiedName
            val value = if (r == Unit) null else r
            TcEvaluate(value = value, className = className, expression = pseudoExpression)
        } catch (e: Exception) {
            logger.warn("Error executing expression: {} - {}", pseudoExpression, e.brief())
            val h = help(functionName)
            TcEvaluate(pseudoExpression, e, help = h)
        }
    }

    override suspend fun execute(expression: String, target: Any): TcEvaluate {
        val (objectName, functionName, args) = simpleParser.parseFunctionExpression(expression)
            ?: return TcEvaluate(expression, IllegalArgumentException("Illegal expression"), "Illegal expression")

        val tc = ToolCall(objectName, functionName, args)
        return execute(tc, target)
    }

    @Throws(IllegalArgumentException::class)
    abstract suspend fun execute(objectName: String, functionName: String, args: Map<String, Any?>, target: Any): Any?

    override fun help(): String {
        return "system.help(domain: String, method: String): String"
    }

    override fun help(method: String): String {
        return "system.help(domain: String, method: String): String"
    }

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
        return when {
            v == null && required && default == null -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            v == null -> default
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
