package ai.platon.pulsar.agentic.inference

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ChatMessages blank-content guards")
class ChatMessagesTest {

    @Test
    @DisplayName("addUser and addSystem skip blank content")
    fun addUserAndAddSystemSkipBlankContent() {
        val messages = AgentMessageList()
        messages.addUser("")
        messages.addUser("   ")
        messages.addSystem("")
        messages.addUser("real instruction")
        messages.addSystem("real system prompt")

        assertEquals(2, messages.messages.size)
    }

    @Test
    @DisplayName("toChatMessages drops blank user/system messages without throwing")
    fun toChatMessagesDropsBlankUserAndSystemMessages() {
        val messages = AgentMessageList()
        // addLast bypasses the addUser/addSystem blank filter — the conversion
        // layer must still survive a blank user/system message.
        messages.addLast("system", "system prompt")
        messages.addLast("user", "")
        messages.addLast("user", "instruction")

        val chatMessages = messages.toChatMessages()

        assertEquals(2, chatMessages.size)
        assertTrue(chatMessages[0] is SystemMessage)
        assertTrue(chatMessages[1] is UserMessage)
        assertEquals("instruction", (chatMessages[1] as UserMessage).singleText())
    }

    @Test
    @DisplayName("toChatMessages keeps non-blank tool and assistant messages")
    fun toChatMessagesKeepsNonBlankMessages() {
        val messages = AgentMessageList()
        messages.addLast("system", "system")
        messages.addLast("assistant", "assistant says hi")
        messages.addLast("user", "question")

        val chatMessages = messages.toChatMessages()

        assertEquals(3, chatMessages.size)
        assertFalse(chatMessages.any { it is SystemMessage && it.text().isBlank() })
    }

    @Test
    @DisplayName("summary prompt with empty text content converts cleanly")
    fun summaryPromptWithEmptyTextContentConvertsCleanly() {
        // S1 regression: an empty textContent used to produce a blank user
        // message inside buildSummaryPrompt — it must be dropped by the
        // addUser guard instead of crashing the LangChain4j conversion.
        val messages = InferencePromptBuilder.buildSummaryPrompt(null, "")

        assertFalse(messages.messages.any { it.role == "user" && it.content.isBlank() })
        assertTrue(messages.toChatMessages().isNotEmpty())
    }
}
