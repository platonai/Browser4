package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import ai.platon.pulsar.common.ResourceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The console's task panel is named from the status envelope: a view that attaches after the
 * `task.titled` step was emitted (a `?task=` link, a refresh) reads it here.
 */
@DisplayName("Command status task title")
class CommandStatusTitleTest {

    @Test
    @DisplayName("the model-written title survives the conversion")
    fun titleIsCarriedIntoTheCommandStatus() {
        val agentStatus = AgentTaskStatus(statusCode = ResourceStatus.SC_OK, processState = "done")
        agentStatus.submittedTask = "open example.com and tell me the title and the first paragraph"
        agentStatus.title = "总结 example.com 首页"

        assertEquals("总结 example.com 首页", agentStatus.toCommandStatus().title)
    }

    @Test
    @DisplayName("an untitled task reports null, not an empty heading")
    fun untitledTaskReportsNull() {
        assertNull(AgentTaskStatus(statusCode = ResourceStatus.SC_OK).toCommandStatus().title)
        assertNull(CommandStatus().title)
    }
}
