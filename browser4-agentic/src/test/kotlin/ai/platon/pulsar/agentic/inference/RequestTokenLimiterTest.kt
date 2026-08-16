package ai.platon.pulsar.agentic.inference

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RequestTokenLimiter] — the per-LLM-request token cap that
 * drops older messages from the middle when the estimated token count
 * exceeds the configured maximum.
 */
class RequestTokenLimiterTest {

    @Test
    @DisplayName("under-limit messages are returned unchanged")
    fun underLimitReturnsUnchanged() {
        val limiter = RequestTokenLimiter(maxTokens = 10_000)
        val messages = AgentMessageList().apply {
            addSystem("You are a helpful assistant.")
            addUser("Hello")
            addUser("What is 2+2?")
        }
        val result = limiter.truncate(messages)
        assertSame(messages, result, "should return same instance when under limit")
    }

    @Test
    @DisplayName("disabled limiter (maxTokens=0) always returns unchanged")
    fun disabledReturnsUnchanged() {
        val limiter = RequestTokenLimiter(maxTokens = 0)
        val messages = AgentMessageList().apply {
            addSystem("x".repeat(100_000))
            addUser("y".repeat(100_000))
        }
        val result = limiter.truncate(messages)
        assertSame(messages, result)
    }

    @Test
    @DisplayName("over-limit drops older user messages, keeps system + last user")
    fun overLimitDropsOlderMessages() {
        // Each "a".repeat(1000) ≈ 200 estimated tokens.
        // 5 such messages = ~1000 tokens, well over the 500 cap.
        val limiter = RequestTokenLimiter(maxTokens = 500)
        val messages = AgentMessageList().apply {
            addSystem("System instruction")
            addUser("a".repeat(1000))
            addUser("b".repeat(1000))
            addUser("c".repeat(1000))
            addUser("d".repeat(1000))
            addUser("CURRENT_INSTRUCTION")
        }
        val result = limiter.truncate(messages)

        // System message + last user message must always be present
        assertTrue(result.messages.any { it.role == "system" })
        assertTrue(result.messages.any { it.content == "CURRENT_INSTRUCTION" })
        // Should have dropped at least some older messages
        assertTrue(result.messages.size < messages.messages.size,
            "expected fewer messages after truncation, got ${result.messages.size}")
    }

    @Test
    @DisplayName("last user message exceeding budget has content truncated")
    fun lastMessageExceedingBudgetTruncated() {
        val limiter = RequestTokenLimiter(maxTokens = 100)
        val messages = AgentMessageList().apply {
            addSystem("sys")
            addUser("x".repeat(2_000)) // ~400 tokens — far exceeds remaining budget
        }
        val result = limiter.truncate(messages)

        assertEquals(2, result.messages.size, "system + last user should be kept")
        assertEquals("system", result.messages[0].role)
        assertEquals("user", result.messages[1].role)
        assertTrue(result.messages[1].content.length < 2_000,
            "content should be truncated, got ${result.messages[1].content.length}")
        assertTrue(result.messages[1].content.contains("omitted"),
            "truncated content should have omission marker")
    }

    @Test
    @DisplayName("empty messages return unchanged")
    fun emptyMessagesReturnUnchanged() {
        val limiter = RequestTokenLimiter(maxTokens = 100)
        val messages = AgentMessageList()
        val result = limiter.truncate(messages)
        assertSame(messages, result)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    @DisplayName("ChatMessage list truncation keeps system + recent messages")
    fun chatMessageListTruncation() {
        // Each repeat(1000) ≈ 200 estimated tokens.
        // 6 such messages = ~1200 tokens, well over the 300 cap.
        val limiter = RequestTokenLimiter(maxTokens = 300)
        val messages: List<dev.langchain4j.data.message.ChatMessage> = listOf(
            SystemMessage.from("System prompt"),
            UserMessage.from("a".repeat(1000)),
            AiMessage.from("b".repeat(1000)),
            ToolExecutionResultMessage.from("id1", "tool1", "c".repeat(1000)),
            AiMessage.from("d".repeat(1000)),
            ToolExecutionResultMessage.from("id2", "tool2", "e".repeat(1000)),
            UserMessage.from("RECENT"),
        )
        val result = limiter.truncate(messages)

        // Should keep system message and most recent messages
        assertTrue(result.any { it is SystemMessage })
        assertTrue(result.any { it is UserMessage && (it.singleText() ?: "") == "RECENT" })
        assertTrue(result.size < messages.size,
            "expected fewer messages, got ${result.size}")
    }

    @Test
    @DisplayName("ChatMessage list under limit returns unchanged")
    fun chatMessageListUnderLimit() {
        val limiter = RequestTokenLimiter(maxTokens = 10_000)
        val messages: List<dev.langchain4j.data.message.ChatMessage> = listOf(
            SystemMessage.from("System"),
            UserMessage.from("Hello"),
        )
        val result = limiter.truncate(messages)
        assertSame(messages, result)
    }

    @Test
    @DisplayName("parseMaxTokens default is 50000")
    fun parseDefault() {
        assertEquals(RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS, RequestTokenLimiter.parseMaxTokens(null))
        assertEquals(RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS, RequestTokenLimiter.parseMaxTokens(""))
        assertEquals(RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS, RequestTokenLimiter.parseMaxTokens("   "))
    }

    @Test
    @DisplayName("parseMaxTokens reads custom value")
    fun parseCustom() {
        assertEquals(30_000, RequestTokenLimiter.parseMaxTokens("30000"))
        assertEquals(30_000, RequestTokenLimiter.parseMaxTokens("  30000  "))
    }

    @Test
    @DisplayName("parseMaxTokens treats 0, -1, unlimited as disabled")
    fun parseDisabled() {
        for (value in listOf("0", "-1", "unlimited", "UNLIMITED")) {
            assertEquals(0, RequestTokenLimiter.parseMaxTokens(value),
                "value '$value' should be disabled (0)")
        }
    }

    @Test
    @DisplayName("only system messages exceeding budget keeps best effort")
    fun systemOnlyExceedingBudget() {
        val limiter = RequestTokenLimiter(maxTokens = 50)
        val messages = AgentMessageList().apply {
            addSystem("x".repeat(500))  // ~100 tokens, exceeds 50
        }
        val result = limiter.truncate(messages)
        assertTrue(result.messages.isNotEmpty(), "must keep at least one message")
        assertTrue(result.messages[0].content.length < 500,
            "content should be truncated, got ${result.messages[0].content.length}")
    }
}
