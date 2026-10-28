package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [TaskInputs] copies a task's referenced files into the agent workspace so an
 * isolated browsing run can read them, and tells the model the workspace path.
 *
 * Origin: the 2026-10-28 supervision run spent its first ten minutes hunting a
 * fixture referenced by the instruction (`browser4-tests/…/asin_field_description.xlsx`)
 * that the file tools refuse to read from outside the workspace (`PATH_DENIED`).
 */
@DisplayName("TaskInputs")
class TaskInputsTest {

    private fun writeFile(dir: Path, relative: String, content: String = "x"): Path {
        val file = dir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    @Test
    @DisplayName("extractCandidates keeps path-like tokens and drops URLs, flags and globs")
    fun extractCandidatesKeepsOnlyPaths() {
        val instruction = """
            Collect the products. The fields are listed in
            browser4-tests\pulsar-tests-common\src\test\resources\asin_field_description.xlsx
            and also in docs/config.md. See https://example.com/a/b.csv for context,
            pass @query.sql instead of inline SQL, avoid data\*.csv globs, and compare
            with D:\workspace\Browser4\README.md.
        """.trimIndent()

        val candidates = TaskInputs.extractCandidates(instruction)

        assertTrue(
            candidates.contains(
                "browser4-tests\\pulsar-tests-common\\src\\test\\resources\\asin_field_description.xlsx"
            ),
            candidates.toString(),
        )
        assertTrue(candidates.contains("docs/config.md"), candidates.toString())
        assertTrue(candidates.contains("D:\\workspace\\Browser4\\README.md"), candidates.toString())
        assertFalse(candidates.any { it.startsWith("https") }, candidates.toString())
        assertFalse(candidates.any { it.startsWith("@") }, candidates.toString())
        assertFalse(candidates.any { it.contains("*") }, candidates.toString())
    }

    @Test
    @DisplayName("a repository-relative reference is copied and the note names the workspace path")
    fun copiesRepoRelativeInput(@TempDir tmp: Path) {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        writeFile(repo, "browser4-tests/pulsar-tests-common/src/test/resources/fields.xlsx", "BINARY")

        val instruction = "Use the fields in browser4-tests/pulsar-tests-common/src/test/resources/fields.xlsx"
        val result = TaskInputs.materialize(instruction, workspace, listOf(repo))

        assertEquals(1, result.inputs.size)
        assertEquals(".b4inputs/fields.xlsx", result.inputs.single().workspacePath)
        assertTrue(Files.isRegularFile(workspace.resolve(".b4inputs/fields.xlsx")))
        assertTrue(result.instruction.startsWith(instruction), result.instruction)
        assertTrue(result.instruction.contains("## Task inputs copied into your workspace"), result.instruction)
        assertTrue(result.instruction.contains(".b4inputs/fields.xlsx"), result.instruction)
        // The model must not be told to retry the original path.
        assertTrue(result.instruction.contains("Do not retry the original paths"), result.instruction)
    }

    @Test
    @DisplayName("an absolute reference outside the workspace is copied")
    fun copiesAbsoluteInput(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val external = writeFile(tmp, "external/data.csv", "a,b")

        val result = TaskInputs.materialize("merge $external", workspace, emptyList())

        assertEquals(1, result.inputs.size)
        assertEquals(".b4inputs/data.csv", result.inputs.single().workspacePath)
        assertEquals("a,b", Files.readString(workspace.resolve(".b4inputs/data.csv")))
    }

    @Test
    @DisplayName("a reference already readable from the workspace is left alone")
    fun skipsWorkspaceFile(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        writeFile(workspace, "docs/notes.md", "hi")

        val instruction = "read docs/notes.md"
        val result = TaskInputs.materialize(instruction, workspace, emptyList())

        assertTrue(result.isEmpty, result.inputs.toString())
        assertEquals(instruction, result.instruction)
    }

    @Test
    @DisplayName("a missing reference changes nothing")
    fun missingReferenceIsIgnored(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val repo = Files.createDirectories(tmp.resolve("repo"))

        val instruction = "fields in browser4-tests/missing/fields.xlsx"
        val result = TaskInputs.materialize(instruction, workspace, listOf(repo))

        assertTrue(result.isEmpty)
        assertEquals(instruction, result.instruction)
    }

    @Test
    @DisplayName("a credential location is never copied")
    fun sensitiveLocationIsSkipped(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val secrets = Files.createDirectories(tmp.resolve("secrets"))
        val key = writeFile(secrets, "id_rsa", "PRIVATE")

        val result = TaskInputs.materialize(
            "use $key", workspace, emptyList(), sensitiveRoots = listOf(secrets),
        )

        assertTrue(result.isEmpty, result.inputs.toString())
        assertFalse(Files.exists(workspace.resolve(".b4inputs/id_rsa")))
    }

    @Test
    @DisplayName("two references that share a file name do not overwrite each other")
    fun duplicateNamesGetASuffix(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val first = writeFile(tmp, "a/data.csv", "first")
        val second = writeFile(tmp, "b/data.csv", "second")

        val result = TaskInputs.materialize("merge $first and $second", workspace, emptyList())

        assertEquals(2, result.inputs.size)
        assertEquals(
            listOf(".b4inputs/data.csv", ".b4inputs/data-2.csv"),
            result.inputs.map { it.workspacePath },
        )
        assertEquals("first", Files.readString(workspace.resolve(".b4inputs/data.csv")))
        assertEquals("second", Files.readString(workspace.resolve(".b4inputs/data-2.csv")))
    }

    @Test
    @DisplayName("at most MAX_INPUTS files are copied")
    fun capsTheNumberOfInputs(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val instruction = (1..(TaskInputs.MAX_INPUTS + 5)).joinToString(" ") { index ->
            writeFile(tmp, "files/f$index.csv", "v$index").toString()
        }

        val result = TaskInputs.materialize(instruction, workspace, emptyList())

        assertEquals(TaskInputs.MAX_INPUTS, result.inputs.size)
    }
}
