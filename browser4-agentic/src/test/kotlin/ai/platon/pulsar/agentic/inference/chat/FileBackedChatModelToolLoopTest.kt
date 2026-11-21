package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.llm.FileBackedChatModel
import dev.langchain4j.data.message.AiMessage
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
import kotlin.test.assertTrue

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
                {"id": "t1", "name": "b4.run", "arguments": {"args": "snapshot --stdout"}}
              ]
            }
            """.trimIndent()
        )
        Files.writeString(responseDir.resolve("001.json"), "task finished")

        val model = FileBackedChatModel(ImmutableConfig(), responseDir)
        val coordinator = mockk<ToolExecutionCoordinator>()
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("t1", "b4.run", "mock snapshot")

        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 2,
        )

        val response = loop.generate(listOf(UserMessage.from("do the task")))
        assertEquals("task finished", response.content)

        // The round's REAL conversation is exposed for the caller to carry into the
        // next round (and, with a session carrier, into the next task): the assistant
        // tool call and its result stay paired, so nothing has to be re-executed and
        // the compressor can fold this history at a round boundary.
        val carried = loop.finalMessages
        assertEquals(4, carried.size, carried.joinToString { it.javaClass.simpleName })
        assertTrue(carried[0] is UserMessage)
        assertTrue((carried[1] as AiMessage).toolExecutionRequests().isNotEmpty())
        assertEquals("mock snapshot", (carried[2] as ToolExecutionResultMessage).text())
        assertEquals("task finished", (carried[3] as AiMessage).text())
    }

    @Test
    @DisplayName("a queued follow-up continues the same run at the stop point")
    fun aFollowUpContinuesTheRunAtTheStopPoint() = runBlocking {
        // 000: a tool call; 001: the model answers in text (the stop point, where the
        // follow-up is delivered); 002: the answer after the follow-up.
        val responseDir = tempDir.resolve("responses-follow-up")
        Files.createDirectory(responseDir)
        Files.writeString(
            responseDir.resolve("000.json"),
            """
            {
              "text": "calling cli",
              "toolCalls": [
                {"id": "t1", "name": "b4.run", "arguments": {"args": "snapshot --stdout"}}
              ]
            }
            """.trimIndent()
        )
        Files.writeString(responseDir.resolve("001.json"), "first answer")
        Files.writeString(responseDir.resolve("002.json"), "second answer")

        val model = FileBackedChatModel(ImmutableConfig(), responseDir)
        val coordinator = mockk<ToolExecutionCoordinator>()
        every { coordinator.execute(any()) } returns
            ToolExecutionResultMessage.from("t1", "b4.run", "mock snapshot")

        val delivered = mutableListOf<String>()
        var pending: String? = "one more thing"
        val loop = AgentToolCallLoop(
            model = model,
            toolSpecifications = emptyList(),
            coordinator = coordinator,
            maxIterations = 3,
            onStop = { messages ->
                val text = pending ?: return@AgentToolCallLoop false
                pending = null
                delivered += text
                messages.add(UserMessage.from(text))
                true
            },
        )

        val response = loop.generate(listOf(UserMessage.from("do the task")))

        assertEquals("second answer", response.content, "the run must continue after the follow-up")
        assertEquals(listOf("one more thing"), delivered)
        assertTrue(
            loop.finalMessages.any { it is UserMessage && it.singleText() == "one more thing" },
            "the follow-up must be part of the continued conversation: " +
                loop.finalMessages.joinToString { it.javaClass.simpleName },
        )
    }
}
