package ai.platon.pulsar.agentic.memory

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Sanitizer")
class SanitizerTest {

    @Test
    @DisplayName("masks sensitive key values")
    fun testSensitiveKeys() {
        assertEquals("****", Sanitizer.sanitizeKeyValue("password", "hunter2"))
        assertEquals("****", Sanitizer.sanitizeKeyValue("apiKey", "sk-123"))
        assertEquals("****", Sanitizer.sanitizeKeyValue("AUTH_TOKEN", "abc"))
        assertEquals("keep", Sanitizer.sanitizeKeyValue("text", "keep"))
        assertEquals("keep", Sanitizer.sanitizeKeyValue("value", "keep"))
    }

    @Test
    @DisplayName("sanitizes nested JSON arguments")
    fun testSanitizeArgsJson() {
        val json = """{"url":"https://x.com","password":"hunter2","nested":{"token":"t","name":"n"},"list":[{"cookie":"c"}]}"""
        val out = Sanitizer.sanitizeArgsJson(json, maxLen = 10_000)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("\"t\""))
        assertFalse(out.contains("\"c\""))
        assertTrue(out.contains("https://x.com"))
        assertTrue(out.contains("nested"))
        assertTrue(out.contains("****"))
    }

    @Test
    @DisplayName("falls back to regex masking on unparseable input")
    fun testSanitizeArgsJsonFallback() {
        val json = """{"password":"secret-value","x":broken"""
        val out = Sanitizer.sanitizeArgsJson(json)
        assertFalse(out.contains("secret-value"))
    }

    @Test
    @DisplayName("brief compacts whitespace and truncates")
    fun testBrief() {
        val long = "  a    b  " + "c".repeat(500)
        val brief = Sanitizer.brief(long, 100)
        assertEquals(100, brief.length)
        assertFalse(brief.contains("  "))
    }

    @Test
    @DisplayName("extracts the first URL")
    fun testExtractUrl() {
        assertEquals("https://example.com/dp/1", Sanitizer.extractUrl("go to https://example.com/dp/1 now"))
        assertEquals("http://a.b/c", Sanitizer.extractUrl("see http://a.b/c."))
        assertEquals(null, Sanitizer.extractUrl("no url here"))
        assertEquals(null, Sanitizer.extractUrl(null))
    }
}
