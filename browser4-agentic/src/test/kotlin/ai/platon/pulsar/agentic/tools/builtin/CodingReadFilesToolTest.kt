package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `coding.readFiles` (plan N1): one call reads N files.
 *
 * The whole point is to remove rounds (each ~0.6-0.9 s of fixed latency), so the tests pin
 * the properties a round-saving tool must not trade away: every requested file is either
 * shown or *named* as skipped, the reply stays under the tool envelope's 5000-char cap, and
 * a missing file does not swallow the rest of the batch.
 */
@Tag("Fast")
@DisplayName("coding.readFiles: one call, N files, bounded reply")
class CodingReadFilesToolTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var fs: CodingAgentFileSystem
    private lateinit var executor: CodingToolExecutor
    private lateinit var target: CodingToolExecutor.Target

    @BeforeEach
    fun setUp() {
        fs = CodingAgentFileSystem(tmp)
        executor = CodingToolExecutor()
        target = CodingToolExecutor.Target(mockk<CodingAgentShell>(relaxed = true), fs)
    }

    private fun file(name: String, lines: Int, prefix: String = "line"): Path {
        val path = tmp.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, (1..lines).joinToString("\n") { "$prefix $it" } + "\n")
        return path
    }

    /**
     * Run the tool and return what the model would see: the rendered value, or the
     * exception message the executor attached (argument validation failures surface there
     * rather than as a thrown exception).
     */
    private fun read(vararg args: Pair<String, Any?>): String = runBlocking {
        val arguments = mutableMapOf<String, Any?>()
        args.forEach { (name, value) -> arguments[name] = value }
        val result = executor.callFunctionOn(ToolCall("coding", "readFiles", arguments), target)
        result.exception?.message ?: result.value?.toString().orEmpty()
    }

    private fun readFiles(paths: List<String>, extra: Map<String, Any?> = emptyMap()): String =
        read("paths" to paths, *extra.toList().toTypedArray())

    @Test
    @DisplayName("reads every requested file, in order, with a header per file")
    fun readsEveryFileInOrder() {
        file("a.kt", 3, prefix = "alpha")
        file("b.kt", 3, prefix = "beta")
        file("sub/c.kt", 3, prefix = "gamma")

        val text = readFiles(listOf("a.kt", "b.kt", "sub/c.kt"))

        assertTrue(text.indexOf("### a.kt") in 0..<text.indexOf("### b.kt"), text)
        assertTrue(text.indexOf("### b.kt") < text.indexOf("### sub/c.kt"), text)
        assertTrue(text.contains("alpha 1"), text)
        assertTrue(text.contains("beta 3"), text)
        assertTrue(text.contains("gamma 2"), text)
    }

    @Test
    @DisplayName("maxLinesPerFile bounds every file")
    fun maxLinesPerFileBoundsEachFile() {
        file("wide.kt", 100)

        val text = readFiles(listOf("wide.kt"), mapOf("maxLinesPerFile" to 5))

        assertTrue(text.contains("line 1\n"), text)
        assertTrue(text.contains("line 5"), text)
        assertFalse(text.contains("line 6"), "only five lines were requested: $text")
    }

    @Test
    @DisplayName("the reply stays under the tool-envelope cap and names what the budget skipped")
    fun budgetIsRespectedAndSkippedFilesAreNamed() {
        val names = (1..20).map { file("f$it.kt", 40).fileName.toString() }

        val text = readFiles(names, mapOf("maxTotalChars" to 900))

        assertTrue(text.length <= 4_600, "the native loop truncates at 5000 chars, got ${text.length}")
        assertTrue(text.contains("not shown (char budget 900)"), text)
        // Every file is accounted for: either a header, or a mention in the skipped list.
        names.forEach { name ->
            assertTrue(text.contains(name), "$name must be shown or named as skipped: $text")
        }
    }

    @Test
    @DisplayName("a file larger than its share is truncated with a marker, not silently cut")
    fun singleLargeFileIsTruncatedWithAMarker() {
        file("huge.kt", 400)

        val text = readFiles(listOf("huge.kt"), mapOf("maxTotalChars" to 800, "maxLinesPerFile" to -1))

        assertTrue(text.contains("omitted"), "readFileLines' truncation marker must survive: $text")
        assertTrue(text.length <= 4_600, "reply length ${text.length}")
    }

    @Test
    @DisplayName("a missing file does not swallow the rest of the batch")
    fun missingFileIsReportedInline() {
        file("present.kt", 2, prefix = "here")

        val text = readFiles(listOf("nope.kt", "present.kt"))

        assertTrue(text.contains("### nope.kt"), text)
        assertTrue(text.contains("not found", ignoreCase = true), "the failure must be visible: $text")
        assertTrue(text.contains("here 1"), "the readable file is still read: $text")
    }

    @Test
    @DisplayName("duplicate paths are read once and an empty list explains the contract")
    fun duplicatesAndEmptyList() {
        file("once.kt", 2)

        val deduped = readFiles(listOf("once.kt", "once.kt", " once.kt "))
        assertEquals(1, Regex("### once\\.kt").findAll(deduped).count(), deduped)

        val empty = readFiles(listOf("  "))
        assertTrue(empty.contains("No files requested"), empty)
    }

    @Test
    @DisplayName("a comma-separated string is accepted, like other list arguments")
    fun commaSeparatedStringIsAccepted() {
        file("x.kt", 1, prefix = "ex")
        file("y.kt", 1, prefix = "why")

        val text = read("paths" to "x.kt, y.kt")

        assertTrue(text.contains("### x.kt"), text)
        assertTrue(text.contains("### y.kt"), text)
    }

    @Test
    @DisplayName("paths is required and unknown arguments are rejected")
    fun argumentValidation() {
        val missing = read("startLine" to 1)
        assertTrue(missing.contains("paths", ignoreCase = true), "missing paths must be named: $missing")

        val extraneous = readFiles(listOf("x.kt"), mapOf("bogus" to 1))
        assertTrue(extraneous.contains("bogus", ignoreCase = true), "extraneous args must be refused: $extraneous")
    }

    @Test
    @DisplayName("the spec advertises the batch tool with the budget arguments")
    fun specIsRegistered() {
        val specs = executor.getToolSpecs()
        val spec = specs["readFiles"] ?: error("readFiles must be a coding tool: ${specs.keys}")
        assertEquals("coding", spec.domain)
        assertEquals(
            setOf("paths", "startLine", "maxLinesPerFile", "maxTotalChars"),
            spec.arguments.map { it.name }.toSet(),
        )
        assertTrue(spec.description.orEmpty().contains("ONE call"), spec.description.orEmpty())
    }
}
