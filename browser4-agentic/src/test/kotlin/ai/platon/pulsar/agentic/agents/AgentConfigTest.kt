package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AgentConfig RunEngine")
class AgentConfigTest {

    @Test
    @DisplayName("runEngine parse maps legacy and cli values")
    fun runEngineParsing() {
        assertEquals(RunEngine.OBSERVE_ACT, RunEngine.parse(null))
        assertEquals(RunEngine.OBSERVE_ACT, RunEngine.parse("observe-act"))
        assertEquals(RunEngine.OBSERVE_ACT, RunEngine.parse("v1"))
        assertEquals(RunEngine.CLI_TOOL_LOOP, RunEngine.parse("cli"))
        assertEquals(RunEngine.CLI_TOOL_LOOP, RunEngine.parse("cli-tool-loop"))
        assertEquals(RunEngine.CLI_TOOL_LOOP, RunEngine.parse("CLI_TOOL_LOOP"))
        assertEquals(RunEngine.OBSERVE_ACT, RunEngine.parse("unknown-value"))
    }
}
