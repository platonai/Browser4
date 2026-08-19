package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CommandStatusJacksonSerializationTest {

    private val mapper = pulsarObjectMapper()

    @Test
    @DisplayName("command status jackson serialization includes agent history and current agent state")
    fun commandStatusJacksonSerializationIncludesAgentHistory() {
        val status = CommandStatus().apply {
            agentHistory = CommandAgentHistory(
                mutableListOf(
                    CommandAgentState(
                        step = 1,
                        instruction = "Search for a joke about programmers",
                    )
                )
            )
        }

        val node = mapper.readTree(mapper.writeValueAsString(status))

        assertTrue(node.has("agentHistory"))
        assertEquals(1, node.path("agentHistory").path("states").size())
        assertTrue(node.has("agentState"))
        assertEquals(1, node.path("agentState").path("step").asInt())
    }

    @Test
    @DisplayName("command agent state projection includes tool and AI fields")
    fun commandAgentStateIncludesToolAndAiFields() {
        val status = CommandStatus().apply {
            agentHistory = CommandAgentHistory(
                mutableListOf(
                    CommandAgentState(
                        step = 2,
                        instruction = "Fill the search box",
                        description = "typed hello",
                        event = "act",
                        domain = "tab",
                        method = "type",
                        thinking = "the input matches the goal",
                        nextGoal = "submit the form",
                        evaluationPreviousGoal = "success",
                        summary = "typed",
                        isComplete = false,
                        timestamp = "2026-01-01T00:00:00Z",
                    )
                )
            )
        }

        val node = mapper.readTree(mapper.writeValueAsString(status))

        val state = node.path("agentState")
        assertEquals(2, state.path("step").asInt())
        assertEquals("tab", state.path("domain").asText())
        assertEquals("type", state.path("method").asText())
        assertEquals("the input matches the goal", state.path("thinking").asText())
        assertEquals("submit the form", state.path("nextGoal").asText())
        assertEquals("success", state.path("evaluationPreviousGoal").asText())
        assertEquals("2026-01-01T00:00:00Z", state.path("timestamp").asText())
    }
}
