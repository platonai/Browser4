package ai.platon.pulsar.agentic.inference

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [RequestTokenLimiter] — the per-LLM-request token cap.
 *
 * Semantics: halt-only. When the estimated token count of a request exceeds
 * the configured maximum, [RequestTokenLimiter.enforce] throws
 * [RequestTokenLimitExceededException]; the task must stop, report status,
 * and wait for the user to raise the limit. No silent truncation.
 */
class RequestTokenLimiterTest {

    @Test
    @DisplayName("under-limit messages pass through without exception")
    fun underLimitPasses() {
        val limiter = RequestTokenLimiter(maxTokens = 10_000)
        val messages = AgentMessageList().apply {
            addSystem("You are a helpful assistant.")
            addUser("Hello")
            addUser("What is 2+2?")
        }
        assertDoesNotThrow { limiter.enforce(messages) }
    }

    @Test
    @DisplayName("disabled limiter (maxTokens=0) never throws")
    fun disabledNeverThrows() {
        val limiter = RequestTokenLimiter(maxTokens = 0)
        val messages = AgentMessageList().apply {
            addSystem("x".repeat(100_000))
            addUser("y".repeat(100_000))
        }
        assertFalse(limiter.enabled)
        assertDoesNotThrow { limiter.enforce(messages) }
    }

    @Test
    @DisplayName("over-limit AgentMessageList halts with status report")
    fun overLimitHalts() {
        // Each repeat(1000) ≈ 200 estimated tokens.
        // 5 such messages = ~1000 tokens, well over the 500 cap.
        val limiter = RequestTokenLimiter(maxTokens = 500)
        val messages = AgentMessageList().apply {
            addSystem("System instruction")
            addUser("a".repeat(1000))
            addUser("b".repeat(1000))
            addUser("c".repeat(1000))
            addUser("d".repeat(1000))
        }
        val ex = assertThrows<RequestTokenLimitExceededException> { limiter.enforce(messages) }
        assertEquals(500, ex.maxTokens)
        assertTrue(ex.estimatedTokens > 500, "estimated tokens should exceed the cap")
        assertTrue(ex.estimatedTokens <= 10_000, "estimate should be sane")
    }

    @Test
    @DisplayName("exactly at limit does not throw")
    fun atLimitPasses() {
        val limiter = RequestTokenLimiter(maxTokens = 250)
        val messages = AgentMessageList().apply {
            addUser("a".repeat(1000)) // ~200 estimated tokens ≤ 250
        }
        assertDoesNotThrow { limiter.enforce(messages) }
    }

    @Test
    @DisplayName("empty messages never throw")
    fun emptyMessagesPass() {
        val limiter = RequestTokenLimiter(maxTokens = 100)
        assertDoesNotThrow { limiter.enforce(AgentMessageList()) }
        assertDoesNotThrow { limiter.enforce(emptyList<dev.langchain4j.data.message.ChatMessage>()) }
    }

    @Test
    @DisplayName("over-limit ChatMessage list halts")
    fun chatMessageListHalts() {
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
        val ex = assertThrows<RequestTokenLimitExceededException> { limiter.enforce(messages) }
        assertTrue(ex.estimatedTokens > 300)
    }

    @Test
    @DisplayName("ChatMessage list under limit passes")
    fun chatMessageListUnderLimit() {
        val limiter = RequestTokenLimiter(maxTokens = 10_000)
        val messages: List<dev.langchain4j.data.message.ChatMessage> = listOf(
            SystemMessage.from("System"),
            UserMessage.from("Hello"),
        )
        assertDoesNotThrow { limiter.enforce(messages) }
    }

    @Test
    @DisplayName("parseMaxTokens default is 500000")
    fun parseDefault() {
        assertEquals(500_000, RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS)
        assertEquals(RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS, RequestTokenLimiter.parseMaxTokens(null))
        assertEquals(RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS, RequestTokenLimiter.parseMaxTokens(""))
        assertEquals(RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS, RequestTokenLimiter.parseMaxTokens("   "))
    }

    @Test
    @DisplayName("parseMaxTokens reads custom value")
    fun parseCustom() {
        assertEquals(800_000, RequestTokenLimiter.parseMaxTokens("800000"))
        assertEquals(800_000, RequestTokenLimiter.parseMaxTokens("  800000  "))
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
    @DisplayName("exception carries a status report guiding the user to continue")
    fun exceptionStatusReport() {
        val ex = RequestTokenLimitExceededException(estimatedTokens = 612_345, maxTokens = 500_000)
        assertThrows<IllegalStateException> { throw ex }
        assertEquals(612_345L, ex.estimatedTokens)
        assertEquals(500_000, ex.maxTokens)
        val msg = ex.message!!
        assertTrue(msg.contains("612,345"), "report should contain comma-formatted estimate")
        assertTrue(msg.contains("500,000"), "report should contain comma-formatted limit")
        assertTrue(msg.contains(RequestTokenLimiter.CONFIG_KEY), "report should name the config key")
        assertTrue(msg.contains("halted", ignoreCase = true), "report should state the task was halted")
    }

    @Test
    @DisplayName("runtime override takes precedence and affects existing instances")
    fun runtimeOverridePrecedence() {
        val limiter = RequestTokenLimiter(maxTokens = 100)
        try {
            RequestTokenLimiter.setOverride(10_000)
            assertEquals(10_000, limiter.effectiveMaxTokens)
            assertEquals(10_000, RequestTokenLimiter.currentOverride())
            // ~200 estimated tokens: over the configured 100, under the override 10_000
            val big = AgentMessageList().apply { addUser("x".repeat(1000)) }
            assertDoesNotThrow { limiter.enforce(big) }

            RequestTokenLimiter.setOverride(100)
            val ex = assertThrows<RequestTokenLimitExceededException> { limiter.enforce(big) }
            assertEquals(100, ex.maxTokens, "override should be the enforced limit")
        } finally {
            RequestTokenLimiter.clearOverride()
        }
        assertEquals(100, limiter.effectiveMaxTokens, "clearOverride falls back to configured value")
        assertNull(RequestTokenLimiter.currentOverride())
    }
}
