package ai.platon.pulsar.agentic.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Logs must be greppable and must never leak what a client sent: a tool call can
 * carry cookies, tokens or file contents.
 */
@DisplayName("Tool invocation logging")
class ToolInvocationLoggerTest {

    @Test
    @DisplayName("ordinary values stay readable, long ones degrade to length + hash")
    fun valuesAreRenderedSafely() {
        assertEquals("https://example.com", ToolInvocationLogger.renderValue("url", "https://example.com"))
        assertEquals("3", ToolInvocationLogger.renderValue("depth", 3))

        val long = "x".repeat(500)
        val rendered = ToolInvocationLogger.renderValue("selector", long)
        assertTrue(rendered.startsWith("len=500 sha256="), rendered)
        assertFalse(rendered.contains(long), "the payload must not be logged")
    }

    @Test
    @DisplayName("sensitive names are redacted whatever they carry")
    fun sensitiveNamesAreRedacted() {
        listOf(
            "token", "authToken", "password", "apiKey", "api_key", "authorization",
            "cookie", "storageState", "credentials", "sessionId", "filePath", "content",
        ).forEach { name ->
            assertEquals(
                "***", ToolInvocationLogger.renderValue(name, "super-secret-value"),
                "'$name' must be redacted",
            )
        }
    }

    @Test
    @DisplayName("a rendered argument list keeps names and hides secrets")
    fun argumentListIsSafe() {
        val rendered = ToolInvocationLogger.renderArgs(
            mapOf(
                "url" to "https://example.com",
                "depth" to 1,
                "token" to "abc123",
                "storageState" to """{"cookies":[…] }""",
            )
        )

        assertTrue(rendered.contains("url=https://example.com"), rendered)
        assertTrue(rendered.contains("depth=1"), rendered)
        assertTrue(rendered.contains("token=***"), rendered)
        assertTrue(rendered.contains("storageState=***"), rendered)
        assertFalse(rendered.contains("abc123"), rendered)
    }

    @Test
    @DisplayName("collections are summarised, not dumped")
    fun collectionsAreSummarised() {
        assertEquals(
            "list(size=3)",
            ToolInvocationLogger.renderValue("urls", listOf("a", "b", "c"))
        )
        assertEquals(
            "map(keys=url,depth)",
            ToolInvocationLogger.renderValue("params", mapOf("url" to "x", "depth" to 1))
        )
    }

    @Test
    @DisplayName("each call gets a distinct id carrying its channel")
    fun requestIdsAreDistinctAndLabelled() {
        val a = ToolInvocationLogger.newRequestId("A")
        val b = ToolInvocationLogger.newRequestId("B")

        assertTrue(a.startsWith("A-"), a)
        assertTrue(b.startsWith("B-"), b)
        assertFalse(a == b)
    }
}
