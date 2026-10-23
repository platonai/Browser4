package ai.platon.pulsar.agentic.llm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LlmConfigNormalizerTest {

    @TempDir
    lateinit var tempDir: Path

    // System properties set by the normalizer must not leak into other tests.
    private val knownKeys = listOf(
        "deepseek.api.key",
        "deepseek.model.name",
        "deepseek.base.url",
        "openrouter.api.key",
        "volcengine.api.key",
        "openai.api.key",
        "llm.api.key",
        "browser4.cli.proxy",
        "some.random.key",
    )

    @AfterEach
    fun cleanup() {
        knownKeys.forEach { System.clearProperty(it) }
    }

    private fun writeConf(fileName: String, content: String): Path {
        val path = tempDir.resolve(fileName)
        Files.writeString(path, content)
        return path
    }

    private fun normalize() {
        LlmConfigNormalizer.normalizeDir(tempDir)
    }

    @Test
    fun testRewritesEnvStyleKeysToDottedForm() {
        val file = writeConf(
            "application-private.properties",
            "spring.main.allow-bean-definition-overriding=true\n" +
                "DEEPSEEK_API_KEY=sk-test-123\n" +
                "DEEPSEEK_MODEL_NAME=deepseek-v4-flash\n" +
                "OPENROUTER_API_KEY=sk-or-456\n"
        )

        normalize()

        val rewritten = Files.readString(file)
        assertTrue(rewritten.contains("deepseek.api.key=sk-test-123"), rewritten)
        assertTrue(rewritten.contains("deepseek.model.name=deepseek-v4-flash"), rewritten)
        assertTrue(rewritten.contains("openrouter.api.key=sk-or-456"), rewritten)
        assertTrue(!rewritten.contains("DEEPSEEK_API_KEY="), rewritten)
        // Non-LLM keys are untouched.
        assertTrue(rewritten.contains("spring.main.allow-bean-definition-overriding=true"), rewritten)
    }

    @Test
    fun testMirrorsKeysIntoSystemProperties() {
        writeConf("app.properties", "DEEPSEEK_API_KEY=sk-sys-1\n")

        normalize()

        assertEquals("sk-sys-1", System.getProperty("deepseek.api.key"))
    }

    @Test
    @DisplayName("an already-dotted config file still binds through system properties")
    fun testMirrorsDottedKeysIntoSystemProperties() {
        // A rebuilt runtime bundle failed every `agent run` with "No LLM configured"
        // while doctor read this very file and reported "LLM is configured": the
        // documented dotted form was only ever written to the file, never to the
        // system properties the engine resolves first.
        writeConf(
            "application-private.properties",
            "spring.main.allow-bean-definition-overriding=true\n" +
                "deepseek.api.key=sk-dotted-key\n" +
                "deepseek.model.name=deepseek-v4-flash\n" +
                "deepseek.base.url=https://api.deepseek.com\n"
        )

        normalize()

        assertEquals("sk-dotted-key", System.getProperty("deepseek.api.key"))
        assertEquals("deepseek-v4-flash", System.getProperty("deepseek.model.name"))
        assertEquals("https://api.deepseek.com", System.getProperty("deepseek.base.url"))
    }

    @Test
    @DisplayName("non-provider dotted keys are not mirrored")
    fun testNonProviderDottedKeysAreNotMirrored() {
        writeConf("app.properties", "spring.datasource.url=jdbc:h2:mem:x\nbrowser4.cli.proxy=\n")

        normalize()

        assertNull(System.getProperty("spring.datasource.url"))
        assertNull(System.getProperty("browser4.cli.proxy"))
    }

    @Test
    fun testDottedKeysWinOverEnvStyleDuplicates() {
        val file = writeConf(
            "app.properties",
            "deepseek.api.key=sk-dotted\n" +
                "DEEPSEEK_API_KEY=sk-env\n"
        )

        normalize()

        // Explicit dotted key already present: the env-style line must not
        // overwrite it, and the mirrored system property must carry the dotted
        // (file) value — not the env-style duplicate.
        val content = Files.readString(file)
        assertTrue(content.contains("deepseek.api.key=sk-dotted"), content)
        assertTrue(content.contains("DEEPSEEK_API_KEY=sk-env"), content)
        assertEquals("sk-dotted", System.getProperty("deepseek.api.key"))
    }

    @Test
    fun testIdempotentSecondRun() {
        val file = writeConf("app.properties", "DEEPSEEK_API_KEY=sk-idem\n")

        normalize()
        val first = Files.readString(file)
        normalize()
        val second = Files.readString(file)

        assertEquals(first, second)
        assertTrue(second.contains("deepseek.api.key=sk-idem"), second)
        assertEquals("sk-idem", System.getProperty("deepseek.api.key"))
    }

    @Test
    fun testNonLlmEnvStyleKeysAreIgnored() {
        val file = writeConf(
            "app.properties",
            "BROWSER4_CLI_PROXY=http://proxy:8080\n" +
                "SOME_RANDOM_KEY=value\n"
        )

        normalize()

        val content = Files.readString(file)
        assertTrue(content.contains("BROWSER4_CLI_PROXY=http://proxy:8080"), content)
        assertTrue(content.contains("SOME_RANDOM_KEY=value"), content)
        assertNull(System.getProperty("browser4.cli.proxy"))
        assertNull(System.getProperty("some.random.key"))
    }

    @Test
    fun testCommentsAndBlankLinesArePreserved() {
        val file = writeConf(
            "app.properties",
            "# DeepSeek provider\n" +
                "\n" +
                "# deepseek.api.key=sk-commented\n" +
                "DEEPSEEK_API_KEY=sk-active\n"
        )

        normalize()

        val content = Files.readString(file)
        assertTrue(content.contains("# DeepSeek provider"), content)
        assertTrue(content.contains("# deepseek.api.key=sk-commented"), content)
        assertTrue(content.contains("deepseek.api.key=sk-active"), content)
        assertEquals("sk-active", System.getProperty("deepseek.api.key"))
    }

    @Test
    fun testMissingDirectoryIsNoOp() {
        val missing = tempDir.resolve("does-not-exist")
        LlmConfigNormalizer.normalizeDir(missing)  // must not throw
    }
}
