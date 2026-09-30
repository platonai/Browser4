package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [LanguageServerManager] using a small in-test fake LSP server
 * (a JVM process speaking JSON-RPC over stdio). No real language servers
 * required — the protocol, framing, and lifecycle are what we verify.
 */
class LanguageServerManagerTest {

    @TempDir
    lateinit var tempDir: Path

    /** Path to the fake-server main class, reusing the test JVM's classpath. */
    private fun javaBin(): String {
        val javaHome = System.getProperty("java.home")
        return Path.of(javaHome, "bin", if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java").toString()
    }

    private fun classpath(): String = System.getProperty("java.class.path")

    private fun fakeServerCommand(script: String): List<String> {
        // The fake server is driven by a script name; we generate a tiny Kotlin
        // runner at test time? Simpler: use `java -cp <cp> FakeLspServerMain <script>`
        // and rely on the class being on the test classpath.
        return listOf(javaBin(), "-cp", classpath(), "ai.platon.pulsar.coding.FakeLspServerMain", script)
    }

    private fun writeSample(path: String, content: String) {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    @Test
    @DisplayName("detectLanguage maps common extensions")
    fun detectLanguage() {
        // exercised indirectly: a .ts file should trigger the typescript command
        writeSample("src/a.ts", "const x = 1;")
        // We can't reach private detectLanguage; verify via diagnostics with a fake
        // server configured for typescript.
        val manager = LanguageServerManager(
            workspaceRoot = tempDir,
            serverCommands = mapOf("typescript" to fakeServerCommand("diag")),
        )
        val diags = runBlocking { manager.diagnostics("src/a.ts") }
        // Fake server publishes one error at line 1.
        assertTrue(diags.isNotEmpty(), "expected diagnostics from fake server")
        assertEquals("error", diags[0].severity)
        assertEquals(1, diags[0].line)
        manager.close()
    }

    @Test
    @DisplayName("unsupported language returns empty diagnostics without starting a server")
    fun unsupportedLanguage() {
        writeSample("data.csv", "a,b,c")
        val manager = LanguageServerManager(tempDir, serverCommands = mapOf("typescript" to fakeServerCommand("diag")))
        val diags = runBlocking { manager.diagnostics("data.csv") }
        assertTrue(diags.isEmpty())
        manager.close()
    }

    @Test
    @DisplayName("missing server binary degrades gracefully")
    fun missingServer() {
        writeSample("src/a.ts", "const x = 1;")
        val manager = LanguageServerManager(
            workspaceRoot = tempDir,
            serverCommands = mapOf("typescript" to listOf("definitely-not-a-real-server-xyz", "--stdio")),
        )
        val diags = runBlocking { manager.diagnostics("src/a.ts") }
        assertTrue(diags.isEmpty(), "must degrade to empty, not throw")
        val available = manager.availableServers()
        assertEquals(false, available["typescript"])
        manager.close()
    }

    @Test
    @DisplayName("availableServers reports installed commands")
    fun availableServers() {
        val manager = LanguageServerManager(tempDir, serverCommands = mapOf("typescript" to fakeServerCommand("diag")))
        val available = manager.availableServers()
        assertEquals(true, available["typescript"], "fake server should be on the test classpath")
        manager.close()
    }
}

