package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the opportunistic ripgrep narrowing used by [CodingAgentFileSystem.grep].
 *
 * The real binary is never invoked: [RipgrepSupport.commandRunner] is the seam (the same
 * pattern `WorkspaceMirror.processRunner` uses), so the narrowing, fallback and timeout paths
 * are deterministic on a machine without `rg`.
 */
class RipgrepSupportTest {

    @TempDir
    lateinit var tempDir: Path

    private var previousRunner: RipgrepSupport.CommandRunner? = null
    private var previousEnabled = true

    @BeforeEach
    fun captureGlobals() {
        previousRunner = RipgrepSupport.commandRunner
        previousEnabled = RipgrepSupport.enabled
        // Force the probe window shut so these fixtures (a handful of files) take the
        // ripgrep branch instead of finishing the walk first.
        System.setProperty(CodingAgentFileSystem.PROBE_MS_PROPERTY, "0")
    }

    @AfterEach
    fun restoreGlobals() {
        RipgrepSupport.commandRunner = previousRunner
        RipgrepSupport.enabled = previousEnabled
        System.clearProperty(CodingAgentFileSystem.PROBE_MS_PROPERTY)
    }

    private fun write(path: String, content: String) {
        val file = tempDir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun fs(): CodingAgentFileSystem = CodingAgentFileSystem(tempDir)

    /** A runner that reports exactly [files] (NUL-separated, as `--null` emits them). */
    private fun listing(vararg files: Path, timedOut: Boolean = false): RipgrepSupport.CommandRunner =
        RipgrepSupport.CommandRunner { _, _, _ ->
            val payload = files.joinToString("") { "$it\u0000" }
            RipgrepSupport.RunResult(0, payload.toByteArray(), timedOut)
        }

    // ==================== dialect gate ====================

    @Test
    @DisplayName("the delegated dialect accepts the patterns agents actually write")
    fun dialectAcceptsCommonPatterns() {
        listOf(
            "TODO",
            "class \\w+",
            "foo|bar",
            "error\\s*:",
            "^import ",
            "a{2,3}b",
            "[A-Z_]+",
            "\\bfix\\b",
            "import\\s+java\\.util\\.\\*",
        ).forEach { pattern ->
            assertTrue(RipgrepSupport.isSupportedPattern(pattern), "should delegate to ripgrep: $pattern")
        }
    }

    @Test
    @DisplayName("dialect-specific constructs are refused instead of risking a silent miss")
    fun dialectRejectsDialectSpecificConstructs() {
        listOf(
            "(?i)todo",      // inline flags / lookarounds
            "(?<=a)b",
            "a\\1",          // backreference
            "\\p{L}",        // Unicode property class
            "\\u0041",       // Java-only escape
            "\\A x",         // \A/\z mean start/end of INPUT in Java, of the FILE in ripgrep
            "\\Qa.b\\E",
            "[[:alpha:]]",   // POSIX class
        ).forEach { pattern ->
            assertFalse(RipgrepSupport.isSupportedPattern(pattern), "must not delegate: $pattern")
        }
    }

    // ==================== narrowing ====================

    @Test
    @DisplayName("narrowing scans only the files ripgrep lists")
    fun narrowingLimitsTheScan() = runBlocking {
        write("src/a.txt", "needle in a\n")
        write("src/b.txt", "needle in b\n")
        RipgrepSupport.commandRunner = listing(tempDir.resolve("src/a.txt"))

        val result = fs().grep("needle")

        assertTrue(result.contains("a.txt"), "result: $result")
        assertFalse(result.contains("b.txt"), "narrowing must be honoured: $result")
    }

    @Test
    @DisplayName("the argv carries the multiline prefix, NUL separators and exclusions")
    fun narrowingCommandShape() {
        val captured = mutableListOf<List<String>>()
        RipgrepSupport.commandRunner = RipgrepSupport.CommandRunner { command, _, _ ->
            captured += command
            RipgrepSupport.RunResult(1, ByteArray(0), false)
        }

        val narrowing = RipgrepSupport.narrow(
            tempDir, "class \\w+", ignoreCase = true,
            excludedDirNames = setOf("node_modules", "target"),
            budgetMillis = 5_000,
        )

        assertNotNull(narrowing)
        val command = captured.single()
        assertTrue(command.contains("--files-with-matches"), "command: $command")
        assertTrue(command.contains("--null"), "command: $command")
        assertTrue(command.contains("--text"), "command: $command")
        assertTrue(command.contains("--ignore-case"), "command: $command")
        assertTrue(command.contains("!**/node_modules/**"), "command: $command")
        assertTrue(command.contains("!**/target/**"), "command: $command")
        val pattern = command[command.indexOf("--regexp") + 1]
        assertEquals("(?m)class \\w+", pattern, "the matcher is line-oriented, so ripgrep must be too")
    }

    @Test
    @DisplayName("a failing ripgrep falls back to the built-in scan")
    fun failingRipgrepFallsBack() = runBlocking {
        write("src/a.txt", "needle in a\n")
        write("src/b.txt", "needle in b\n")
        RipgrepSupport.commandRunner = RipgrepSupport.CommandRunner { _, _, _ ->
            RipgrepSupport.RunResult(2, ByteArray(0), false)
        }

        val result = fs().grep("needle")

        assertTrue(result.contains("a.txt"), "result: $result")
        assertTrue(result.contains("b.txt"), "a ripgrep error must not narrow: $result")
    }

    @Test
    @DisplayName("a timed-out narrowing still matches its files and marks the result partial")
    fun timedOutNarrowingIsPartialButUseful() = runBlocking {
        write("src/a.txt", "needle in a\n")
        write("src/b.txt", "needle in b\n")
        RipgrepSupport.commandRunner = listing(tempDir.resolve("src/a.txt"), timedOut = true)

        val result = fs().grep("needle")

        assertTrue(result.contains("a.txt"), "listed files are still matched: $result")
        assertTrue(result.contains("partial:"), "result: $result")
        assertTrue(result.contains("ripgrep"), "the partial source is named: $result")
    }

    @Test
    @DisplayName("an unsupported pattern never reaches ripgrep")
    fun unsupportedPatternSkipsNarrowing() = runBlocking {
        write("src/a.txt", "TODO in a\n")
        var invoked = false
        RipgrepSupport.commandRunner = RipgrepSupport.CommandRunner { _, _, _ ->
            invoked = true
            RipgrepSupport.RunResult(0, ByteArray(0), false)
        }

        val result = fs().grep("(?i)todo")

        assertFalse(invoked, "a lookaround/literal-flag pattern must stay in the JVM")
        assertTrue(result.contains("a.txt"), "result: $result")
    }

    @Test
    @DisplayName("the accelerator can be switched off")
    fun disabledAcceleratorIsIgnored() = runBlocking {
        write("src/a.txt", "needle in a\n")
        write("src/b.txt", "needle in b\n")
        RipgrepSupport.enabled = false
        RipgrepSupport.commandRunner = listing(tempDir.resolve("src/a.txt"))

        val result = fs().grep("needle")

        assertTrue(result.contains("a.txt"), "result: $result")
        assertTrue(result.contains("b.txt"), "a disabled accelerator must not narrow: $result")
    }

    @Test
    @DisplayName("with a real ripgrep the result is byte-identical to the built-in scan")
    fun realRipgrepAgreesWithBuiltInScan() = runBlocking {
        RipgrepSupport.commandRunner = null
        assumeTrue(RipgrepSupport.isAvailable(), "ripgrep is not installed on this machine")

        write("src/a.kt", "class Alpha\nval needle = 1\n")
        write("src/b.kt", "class Beta\n")
        write("src/c.txt", "needle in text\n")
        write("node_modules/x/needle.js", "needle dependency\n")
        write("vendor/lib/needle.js", "needle vendor\n")
        write("bin/tool.ps1", "needle in script\n")
        write("dotnet/bin/Release/out.txt", "needle build output\n")
        // Invalid UTF-8 on purpose: both engines must still report the ASCII needle line.
        Files.write(
            tempDir.resolve("src/gbk.txt"),
            byteArrayOf(0x2F, 0x2F, 0x20, 0xD6.toByte(), 0xD0.toByte(), 0x0A) +
                "needle besides gbk\n".toByteArray(),
        )

        val withRipgrep = fs().grep("needle")
        RipgrepSupport.enabled = false
        val withoutRipgrep = fs().grep("needle")

        assertEquals(withoutRipgrep, withRipgrep, "ripgrep narrowing must not change the result")
        val normalized = withRipgrep.replace('\\', '/')
        assertTrue(normalized.contains("bin/tool.ps1"), "repo scripts stay searchable: $withRipgrep")
        assertFalse(normalized.contains("out.txt"), "bin/Release stays excluded: $withRipgrep")
        assertTrue(normalized.contains("gbk.txt:2"), "a non-UTF-8 file is still searched: $withRipgrep")
    }
}
