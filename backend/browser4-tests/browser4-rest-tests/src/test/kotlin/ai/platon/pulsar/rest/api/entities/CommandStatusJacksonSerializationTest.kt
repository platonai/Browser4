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
}
