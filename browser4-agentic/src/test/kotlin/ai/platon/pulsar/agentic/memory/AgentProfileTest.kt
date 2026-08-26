package ai.platon.pulsar.agentic.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("AgentProfile (user preference memory)")
class AgentProfileTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    private fun profile(principal: String = "a1") = AgentProfile(tempDir, principal)

    @Test
    @DisplayName("set/get round-trip and persist to YAML")
    fun testSetGetPersist() {
        val p = profile()
        p.set("language", "zh")
        assertEquals("zh", p.get("language"))

        // A fresh instance reads the same file.
        val reloaded = profile()
        assertEquals("zh", reloaded.get("language"))
        assertTrue(tempDir.resolve("profiles").resolve("a1.yaml").exists())
    }

    @Test
    @DisplayName("increment counts and persists")
    fun testIncrement() {
        val p = profile()
        assertEquals(1, p.increment("domain_count:amazon.com"))
        assertEquals(2, p.increment("domain_count:amazon.com"))
        assertEquals(3, p.increment("domain_count:amazon.com"))
        // A fresh instance reads the counters back and renders them.
        val reloaded = profile()
        val rendered = reloaded.render()
        assertTrue(rendered!!.contains("常访问 amazon.com×3"))
    }

    @Test
    @DisplayName("applyExplicitPrefs extracts explicit language switches only")
    fun testExplicitPrefs() {
        val p = profile()
        p.applyExplicitPrefs("任务完成，以后用中文输出报告。")
        assertEquals("zh", p.get("language"))

        val p2 = profile("a2")
        p2.applyExplicitPrefs("Please summarize in English next time.")
        assertEquals("en", p2.get("language"))

        // No explicit statement → nothing recorded.
        val p3 = profile("a3")
        p3.applyExplicitPrefs("已提取全部字段。")
        assertNull(p3.get("language"))
        assertNull(p3.render())
    }

    @Test
    @DisplayName("render is bounded and includes preferences + top domains")
    fun testRender() {
        val p = profile()
        p.set("language", "zh")
        p.increment("domain_count:amazon.com")
        p.increment("domain_count:amazon.com")
        p.increment("domain_count:zhihu.com")

        val rendered = p.render(maxChars = 200)!!
        assertTrue(rendered.contains("语言偏好") || rendered.contains("language=zh"))
        assertTrue(rendered.contains("amazon.com"))
        assertTrue(rendered.length <= 200)
    }

    @Test
    @DisplayName("extractDomain best-effort parsing")
    fun testExtractDomain() {
        assertEquals("example.com", AgentProfile.extractDomain("https://example.com/p/1"))
        // www. is normalized away (consistent with PEM's UrlNormalizer).
        assertEquals("example.com", AgentProfile.extractDomain("https://www.example.com/x"))
        assertEquals(null, AgentProfile.extractDomain("no url"))
        assertEquals(null, AgentProfile.extractDomain(null))
    }

    @Test
    @DisplayName("rejects invalid keys")
    fun testInvalidKey() {
        val p = profile()
        kotlin.test.assertFailsWith<IllegalArgumentException> { p.set("bad key!", "v") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { p.increment("x".repeat(60)) }
    }

    @Test
    @DisplayName("render empty when nothing recorded")
    fun testRenderEmpty() {
        assertEquals(null, profile("nobody").render())
    }

    @Test
    @DisplayName("files are plain YAML text")
    fun testFileFormat() {
        val p = profile()
        p.set("language", "zh")
        val content = Files.readString(tempDir.resolve("profiles").resolve("a1.yaml"))
        assertTrue(content.contains("language:"))
        assertTrue(content.contains("zh"))
    }
}
