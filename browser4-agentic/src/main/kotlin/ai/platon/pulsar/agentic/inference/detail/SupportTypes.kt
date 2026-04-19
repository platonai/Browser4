package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.DetailedActResult
import ai.platon.pulsar.common.Strings

/**
 * A helper class to help ActResult keeping small.
 * */
object ActResultHelper {

    fun toString(actResult: ActResult): String {
        val eval = Strings.compactInline(actResult.tcEvalValue?.toString(), 50)
        return "[${actResult.action}] expr: ${actResult.weakTypeExpression} eval: $eval message: ${actResult.message}"
    }

    fun failed(exception: Exception, action: String) = ActResult(action = action, exception = exception)

    fun complete(actionDescription: ActionDescription): ActResult {
        val detailedActResult = DetailedActResult(actionDescription, description =  actionDescription.summary)
        // val toolCall = ToolCall("agent", "done")
        return ActResult(
            "completed",
            actionDescription.instruction,
            detail = detailedActResult
        )
    }
}

/**
 * Enhanced error classification for better retry strategies
 */
sealed class PerceptiveAgentError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class TransientError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    open class PermanentError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    class TimeoutError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    class ResourceExhaustedError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    class ValidationError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
}
