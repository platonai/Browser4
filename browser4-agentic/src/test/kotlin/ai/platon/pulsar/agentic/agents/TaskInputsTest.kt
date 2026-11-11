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
    @DisplayName("a quoted reference keeps the spaces in its path")
    fun extractCandidatesKeepsQuotedSpaces() {
        val path = """D:\My Docs\asin field description.xlsx"""
        listOf("'$path'", "\"$path\"", "“$path”", "‘$path’").forEach { quoted ->
            val candidates = TaskInputs.extractCandidates("字段在 $quoted 里")
            assertTrue(candidates.contains(path), "$quoted → $candidates")
        }
    }

    @Test
    @DisplayName("a local file: URL is reduced to the path it names")
    fun extractCandidatesHandlesFileUrls() {
        assertTrue(
            TaskInputs.extractCandidates("字段在 file:///D:/Backup/字段表.xlsx").contains("D:/Backup/字段表.xlsx"),
            "a Windows file URL must yield the drive path",
        )
        assertTrue(
            TaskInputs.extractCandidates("spec: file:///home/me/fields.xlsx").contains("/home/me/fields.xlsx"),
            "a POSIX file URL must yield the absolute path",
        )
        assertTrue(
            TaskInputs.extractCandidates("see file://server/share/x.xlsx").isEmpty(),
            "a remote host is not a local file",
        )
    }

    @Test
    @DisplayName("~/ is expanded to the home directory; ~user is left alone")
    fun expandHomeHandlesHomeReferences() {
        val home = System.getProperty("user.home")

        assertEquals("$home/fields.xlsx", TaskInputs.expandHome("~/fields.xlsx"))
        assertEquals(home, TaskInputs.expandHome("~"))
        assertEquals("~someone/fields.xlsx", TaskInputs.expandHome("~someone/fields.xlsx"))
        assertEquals("/abs/fields.xlsx", TaskInputs.expandHome("/abs/fields.xlsx"))
    }

    @Test
    @DisplayName("a relative path survives both trailing prose and punctuation inside the name")
    fun extractCandidatesKeepsRelativePaths() {
        // No space between the path and the following Chinese prose: the sentence split ends it.
        assertTrue(
            TaskInputs.extractCandidates("读取 docs/config.md，然后总结要点").contains("docs/config.md"),
            "trailing CJK prose must not be glued to the path",
        )
        // A full-width comma *inside* the name: the path-level split keeps it whole.
        assertTrue(
            TaskInputs.extractCandidates("读取 docs/a，b.md 然后总结").contains("docs/a，b.md"),
            "a full-width comma inside a name must not end the path",
        )
        // A UNC share is a path reference even though it has no drive letter.
        assertTrue(
            TaskInputs.extractCandidates("""spec \\fileserver\specs\fields.xlsx""")
                .contains("""\\fileserver\specs\fields.xlsx"""),
            "a UNC share must be a candidate",
        )
    }

    @Test
    @DisplayName("a quoted path containing spaces is copied")
    fun copiesQuotedPathWithSpaces(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val external = writeFile(tmp, "My Docs/fields.xlsx", "BINARY")

        val result = TaskInputs.materialize("字段表在 '$external' 里", workspace, emptyList())

        assertEquals(1, result.inputs.size, result.inputs.toString())
        assertEquals(".b4inputs/fields.xlsx", result.inputs.single().workspacePath)
        assertTrue(Files.isRegularFile(workspace.resolve(".b4inputs/fields.xlsx")))
    }

    @Test
    @DisplayName("a path glued to CJK punctuation or an ideographic space is still a candidate")
    fun extractCandidatesHandlesCjkPunctuation() {
        val windows = """D:\Backup\Data\20260719\asin_field_description.xlsx"""
        val instructions = listOf(
            // The 2026-11-09 shape: no space after the full-width colon.
            "需要提取以下字段：$windows",
            "需要提取以下字段（$windows）",
            "字段表：“$windows”",
            "需要提取以下字段：\u3000$windows",
            "字段：$windows。",
        )

        instructions.forEach { instruction ->
            val candidates = TaskInputs.extractCandidates(instruction)
            assertTrue(candidates.contains(windows), "$instruction → $candidates")
        }
    }

    @Test
    @DisplayName("non-ASCII segments and an embedded absolute path are recovered")
    fun extractCandidatesHandlesNonAsciiAndEmbeddedPaths() {
        // Non-ASCII path segments survive the whole pipeline.
        assertTrue(
            TaskInputs.extractCandidates("""字段表：D:\资料\字段表.xlsx""").contains("""D:\资料\字段表.xlsx"""),
            "non-ASCII segments must not be split",
        )
        // A separator nobody listed: the fallback pass still finds the absolute path.
        assertTrue(
            TaskInputs.extractCandidates("""见§D:\Backup\a.xlsx""").contains("""D:\Backup\a.xlsx"""),
            "an embedded absolute path must be recovered",
        )
        // A full-width mark *inside* a file name is not a sentence boundary: the broad splitter
        // cuts it, the narrow embedded scan puts the path back together.
        assertTrue(
            TaskInputs.extractCandidates("""表格在 D:\资料\a，b.xlsx 里""").contains("""D:\资料\a，b.xlsx"""),
            "a full-width comma inside a file name must not end the path",
        )
        // …while the conservative rules still hold for what the fallback finds.
        assertTrue(TaskInputs.extractCandidates("""见§D:\Backup\*.xlsx""").isEmpty())
        assertTrue(TaskInputs.extractCandidates("""见§https://example.com/a/b.csv""").isEmpty())
    }

    @Test
    @DisplayName("a CJK-punctuated instruction copies the named file")
    fun copiesInputGluedToCjkPunctuation(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val external = writeFile(tmp, "外部/asin_field_description.xlsx", "BINARY")

        val result = TaskInputs.materialize("需要提取以下字段：$external", workspace, emptyList())

        assertEquals(1, result.inputs.size, result.inputs.toString())
        assertEquals(".b4inputs/asin_field_description.xlsx", result.inputs.single().workspacePath)
        assertTrue(Files.isRegularFile(workspace.resolve(".b4inputs/asin_field_description.xlsx")))
        assertTrue(
            result.instruction.contains("`.b4inputs/asin_field_description.xlsx`"),
            result.instruction,
        )
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
    @DisplayName("the same file named twice is copied once")
    fun copiesARepeatedReferenceOnce(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val repo = Files.createDirectories(tmp.resolve("repo"))
        val relative = "data/fields.xlsx"
        writeFile(repo, relative, "BINARY")
        val absolute = repo.resolve(relative)

        val result = TaskInputs.materialize("fields in $relative and also in $absolute", workspace, listOf(repo))

        assertEquals(1, result.inputs.size, result.inputs.toString())
        assertEquals(".b4inputs/fields.xlsx", result.inputs.single().workspacePath)
        assertFalse(
            Files.exists(workspace.resolve(".b4inputs/fields-2.xlsx")),
            "a second spelling must not become a second copy",
        )
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
