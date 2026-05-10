package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.builtin.BrowserToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.BrowserTabToolExecutor
import kotlin.reflect.full.isSuperclassOf

/**
 * Executes WebDriver commands provided as string expressions.
 *
 * This class serves as a bridge between text-based automation commands and WebDriver actions.
 * It parses string commands and executes the corresponding WebDriver methods, enabling
 * script-based control of browser automation.
 *
 * ## Key Features:
 * - Supports a wide range of WebDriver commands, such as navigation, interaction, and evaluation.
 * - Provides error handling to ensure robust execution of commands.
 * - Includes a companion object for parsing function calls from string inputs.
 *
 * ## Example Usage:
 *
 * ```kotlin
 * val executor = ToolCallExecutor()
 * val result = executor.callFunctionOn("driver.open('https://example.com')", driver)
 * ```
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
open class BasicToolCallExecutor(
    val toolExecutors: List<ToolExecutor> = listOf(BrowserTabToolExecutor(), BrowserToolExecutor())
) {
    @Throws(UnsupportedOperationException::class)
    suspend fun callFunctionOn(tc: ToolCall, receiver: Any): TcEvaluate {
        return toolExecutors
            .firstOrNull { it.receiverClass.isSuperclassOf(receiver::class) }
            ?.callFunctionOn(tc, receiver)
            ?: throw UnsupportedOperationException("❓ Unsupported receiver ${receiver::class}")
    }
}
