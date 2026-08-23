package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.llm.FileBackedChatModel
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

@DisplayName("FileBackedChatModel driving AgentToolCallLoop")
class FileBackedChatModelToolLoopTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("outer test prepares scripted replies; loop executes tool then completes")
    fun scriptedRepliesDriveTheToolLoop() = runBlocking {
        val responseDir = tempDir.resolve("responses")
        Files.createDirectory(responseDir)
        Files.writeString(
            responseDir.resolve("000.json"),
            """
            {
              "text": "calling cli",
              "toolCalls": [
                {"id": "t1", "name": "cli.run", "arguments": {"args": "snapshot --stdout"}}
              ]
            }
            """.trimIndent()
        )
        Files.writeString(responseDir.resolve("001.json"), "task finished")

        val model = FileBackedChatModel(ImmutableConfig(), responseDir)
        val coordinator = mockk<ToolExecutionCoordinator>()
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("t1", "cli.run", "mock snapshot")

        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 2,
        )

        val response = loop.generate(listOf(UserMessage.from("do the task")))
        assertEquals("task finished", response.content)
    }
}
