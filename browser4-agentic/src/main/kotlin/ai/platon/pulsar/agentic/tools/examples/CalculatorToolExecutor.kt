package ai.platon.pulsar.agentic.tools.examples

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import kotlin.reflect.KClass

/**
 * Example custom tool executor that provides basic calculator functionality.
 *
 * This serves as a template for creating custom tool executors. Users can follow
 * this pattern to implement their own domain-specific tools.
 *
 * ## Usage Example:
 *
 * ```kotlin
 * // 1. Create a calculator instance
 * val calculator = Calculator()
 *
 * // 2. Register the tool executor
 * CustomToolRegistry.instance.register(CalculatorToolExecutor())
 *
 * // 3. Register the target object with AgentToolManager
 * agentToolManager.registerCustomTarget("calc", calculator)
 *
 * // 4. Now the agent can use calculator commands:
 * // calc.add(a: Double, b: Double): Double
 * // calc.subtract(a: Double, b: Double): Double
 * // calc.multiply(a: Double, b: Double): Double
 * // calc.divide(a: Double, b: Double): Double
 * ```
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class CalculatorToolExecutor : AbstractToolExecutor() {

    override val domain = "calc"

    override val receiverClass: KClass<*> = Calculator::class

    init {
        toolSpec["add"] = ToolSpec(
            domain = domain,
            method = "add",
            arguments = listOf(
                ToolSpec.Arg("a", "Double", null),
                ToolSpec.Arg("b", "Double", null)
            ),
            returnType = "Double",
            description = "Add two numbers"
        )

        toolSpec["subtract"] = ToolSpec(
            domain = domain,
            method = "subtract",
            arguments = listOf(
                ToolSpec.Arg("a", "Double", null),
                ToolSpec.Arg("b", "Double", null)
            ),
            returnType = "Double",
            description = "Subtract b from a"
        )

        toolSpec["multiply"] = ToolSpec(
            domain = domain,
            method = "multiply",
            arguments = listOf(
                ToolSpec.Arg("a", "Double", null),
                ToolSpec.Arg("b", "Double", null)
            ),
            returnType = "Double",
            description = "Multiply two numbers"
        )

        toolSpec["divide"] = ToolSpec(
            domain = domain,
            method = "divide",
            arguments = listOf(
                ToolSpec.Arg("a", "Double", null),
                ToolSpec.Arg("b", "Double", null)
            ),
            returnType = "Double",
            description = "Divide a by b (b must not be zero)"
        )
    }

    /**
     * Execute calculator operations using named arguments.
     *
     * Supported operations:
     * - add(a: Double, b: Double): Double
     * - subtract(a: Double, b: Double): Double
     * - multiply(a: Double, b: Double): Double
     * - divide(a: Double, b: Double): Double
     */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }
        require(receiver is Calculator) { "Target must be a Calculator instance" }

        val calc = receiver

        return when (functionName) {
            "add" -> {
                validateArgs(args, allowed = setOf("a", "b"), required = setOf("a", "b"), functionName)
                calc.add(
                    paramDouble(args, "a", functionName)!!,
                    paramDouble(args, "b", functionName)!!
                )
            }
            "subtract" -> {
                validateArgs(args, allowed = setOf("a", "b"), required = setOf("a", "b"), functionName)
                calc.subtract(
                    paramDouble(args, "a", functionName)!!,
                    paramDouble(args, "b", functionName)!!
                )
            }
            "multiply" -> {
                validateArgs(args, allowed = setOf("a", "b"), required = setOf("a", "b"), functionName)
                calc.multiply(
                    paramDouble(args, "a", functionName)!!,
                    paramDouble(args, "b", functionName)!!
                )
            }
            "divide" -> {
                validateArgs(args, allowed = setOf("a", "b"), required = setOf("a", "b"), functionName)
                calc.divide(
                    paramDouble(args, "a", functionName)!!,
                    paramDouble(args, "b", functionName)!!
                )
            }
            else -> throw IllegalArgumentException("Unsupported calc method: $functionName(${args.keys})")
        }
    }
}

/**
 * Simple calculator class that serves as the target for the CalculatorToolExecutor.
 *
 * This is the actual implementation of the calculator operations.
 */
class Calculator {
    fun add(a: Double, b: Double): Double = a + b

    fun subtract(a: Double, b: Double): Double = a - b

    fun multiply(a: Double, b: Double): Double = a * b

    fun divide(a: Double, b: Double): Double {
        require(b != 0.0) { "Division by zero is not allowed" }
        return a / b
    }
}
