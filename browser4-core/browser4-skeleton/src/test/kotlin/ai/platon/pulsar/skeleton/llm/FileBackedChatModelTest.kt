package ai.platon.pulsar.skeleton.llm

import ai.platon.pulsar.common.config.ImmutableConfig
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("FileBackedChatModel (test-only mock LLM)")
class FileBackedChatModelTest {

    @TempDir
    lateinit var tempDir: Path

    private val conf = ImmutableConfig()

    @AfterEach
    fun clearFactoryProperties() {
        System.clearProperty(TestChatModelFactory.RESPONSE_FILE_PROPERTY)
        System.clearProperty(TestChatModelFactory.RESPONSE_DIR_PROPERTY)
    }

    @Test
    @DisplayName("plain text file replaces both langChainChat and call replies")
    fun plainTextFileReplacesLlmReplies() {
        val replyFile = tempDir.resolve("reply.txt")
        Files.writeString(replyFile, "Mock assistant reply")
        val model = FileBackedChatModel(conf, replyFile)

        val chatResponse = runBlocking {
            model.langChainChat(
                ChatRequest.builder()
                    .messages(listOf(UserMessage.from("hello")))
                    .build()
            )
        }
        assertEquals("Mock assistant reply", chatResponse.aiMessage().text())
        assertTrue(!chatResponse.aiMessage().hasToolExecutionRequests())

        val modelResponse = runBlocking { model.call("hello") }
        assertEquals("Mock assistant reply", modelResponse.content)
        assertNull(modelResponse.modelError)
    }

    @Test
    @DisplayName("JSON toolCalls are converted into LangChain4j tool requests")
    fun jsonToolCallsAreConverted() {
        val replyFile = tempDir.resolve("tool-call.json")
        Files.writeString(
            replyFile,
            """
            {
              "text": "calling browser",
              "toolCalls": [
                {
                  "id": "t1",
                  "name": "cli.run",
                  "arguments": {"cmd": "browser4-cli snapshot --stdout"}
                }
              ],
              "inputTokens": 12,
              "outputTokens": 4
            }
            """.trimIndent()
        )
        val model = FileBackedChatModel(conf, replyFile)

        val response = runBlocking {
            model.langChainChat(listOf(UserMessage.from("open the page")))
        }
        val aiMessage = response.aiMessage()
        assertTrue(aiMessage.hasToolExecutionRequests())
        val tool = aiMessage.toolExecutionRequests().single()
        assertEquals("t1", tool.id())
        assertEquals("cli.run", tool.name())
        assertEquals(
            """{"cmd":"browser4-cli snapshot --stdout"}""",
            tool.arguments()
        )
    }

    @Test
    @DisplayName("directory responses are consumed in lexicographic order")
    fun directoryResponsesAreConsumedInOrder() {
        val responseDir = tempDir.resolve("responses")
        Files.createDirectory(responseDir)
        Files.writeString(responseDir.resolve("002.json"), "second")
        Files.writeString(responseDir.resolve("001.json"), "first")
        val model = FileBackedChatModel(conf, responseDir)

        val first = runBlocking { model.call("turn 1") }
        val second = runBlocking { model.call("turn 2") }
        assertEquals("first", first.content)
        assertEquals("second", second.content)
    }

    @Test
    @DisplayName("directory marker resets the sequence for the next scenario")
    fun directoryMarkerResetsSequenceForNextScenario() {
        val responseDir = tempDir.resolve("shared-responses")
        Files.createDirectory(responseDir)
        Files.writeString(responseDir.resolve("scenario.txt"), "scenario-a")
        Files.writeString(responseDir.resolve("000.json"), "first-a")
        Files.writeString(responseDir.resolve("001.json"), "second-a")
        val model = FileBackedChatModel(conf, responseDir)

        assertEquals("first-a", runBlocking { model.call("a1") }.content)
        assertEquals("second-a", runBlocking { model.call("a2") }.content)

        // Next e2e scenario reuses the same directory but writes a new marker.
        Files.writeString(responseDir.resolve("scenario.txt"), "scenario-b")
        Files.delete(responseDir.resolve("000.json"))
        Files.delete(responseDir.resolve("001.json"))
        Files.writeString(responseDir.resolve("000.json"), "first-b")

        assertEquals("first-b", runBlocking { model.call("b1") }.content)
    }

    @Test
    @DisplayName("outer test enables the factory and prepares the mock reply file")
    fun factoryReadsPreparedReplyFileWhenEnabled() {
        val replyFile = tempDir.resolve("prepared-reply.json")
        Files.writeString(replyFile, """{"text":"prepared by the outer test"}""")
        System.setProperty(TestChatModelFactory.RESPONSE_FILE_PROPERTY, replyFile.toString())

        assertTrue(TestChatModelFactory.isEnabled())
        assertNotNull(TestChatModelFactory.getOrCreate(conf))

        val response = runBlocking {
            requireNotNull(TestChatModelFactory.getOrCreate(conf)).call("task")
        }
        assertEquals("prepared by the outer test", response.content)
    }
}
