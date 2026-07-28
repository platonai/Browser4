package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.tools.advanced.crawl.PGInstructResult
import ai.platon.pulsar.common.ResourceStatus
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class CommandAgentState(
    var step: Int = 0,
    var instruction: String? = null,
    var description: String? = null,
    var event: String? = null,
    var summary: String? = null,
    var isComplete: Boolean? = null,
)

data class CommandAgentHistory(
    var states: MutableList<CommandAgentState> = mutableListOf(),
) {
    fun lastOrNull(): CommandAgentState? = states.lastOrNull()
}

/**
 * Instruct result
 *
 * @property name The name of the instruction.
 * @property statusCode The status code of the instruction result.
 * @property result The result of the instruction.
 * @property resultType The json type of the result, e.g. "string", "number", "boolean", "array", "object".
 * @property instruct The instruction text.
 * */
data class InstructResult @JsonCreator constructor(
    @param:JsonProperty("name") var name: String,
    @param:JsonProperty("statusCode") var statusCode: Int = ResourceStatus.SC_CREATED,
    @param:JsonProperty("result") var result: Any? = null,
    @param:JsonProperty("resultType") var resultType: String? = null,
    @param:JsonProperty("instruct") var instruct: String? = null,
) {
    companion object {

        fun ok(name: String, result: Any, resultType: String = "string"): InstructResult {
            return InstructResult(name, ResourceStatus.SC_OK, result = result, resultType = resultType)
        }

        fun failed(name: String, statusCode: Int = ResourceStatus.SC_EXPECTATION_FAILED): InstructResult {
            return InstructResult(name, statusCode)
        }
    }
}

fun PGInstructResult.toInstructResult(): InstructResult {
    return InstructResult(name, statusCode, result, resultType, instruct)
}

internal fun AgentHistory.toCommandAgentHistory(): CommandAgentHistory {
    return CommandAgentHistory(states.map { it.toCommandAgentState() }.toMutableList())
}

internal fun AgentState.toCommandAgentState(): CommandAgentState {
    return CommandAgentState(
        step = step,
        instruction = instruction,
        description = description,
        event = event,
        summary = summary,
        isComplete = isComplete,
    )
}
