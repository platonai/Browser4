package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the edit primitives (replaceRegex / editLines / insertAfter / revert)
 * and the upgraded diff (Myers/Patience unified output) in CodingAgentFileSystem.
 */
class CodingAgentFileSystemEditsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fs(): CodingAgentFileSystem = CodingAgentFileSystem(tempDir)

    private fun write(path: String, content: String) {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    private fun read(path: String): String = Files.readString(tempDir.resolve(path))

    // ==================== replaceRegex ====================

    @Test
    @DisplayName("replaceRegex replaces all matches with group references")
    fun replaceRegexBasic() = runBlocking {
        write("a.txt", "x=1\ny=2\nz=3\n")
        val result = fs().replaceRegexInFile("a.txt", "=(\\d+)", "=$1+1")
        assertTrue(result.contains("3 regex match"), "result: $result")
        assertEquals("x=1+1\ny=2+1\nz=3+1\n", read("a.txt"))
    }

    @Test
    @DisplayName("replaceRegex with count limits replacements")
    fun replaceRegexCount() = runBlocking {
        write("a.txt", "a1 b2 c3\n")
        val result = fs().replaceRegexInFile("a.txt", "\\d", "X", count = 2)
        assertTrue(result.contains("up to 2"))
        assertEquals("aX bX c3\n", read("a.txt"))
    }

    @Test
    @DisplayName("replaceRegex no-match reports no change")
    fun replaceRegexNoMatch() = runBlocking {
        write("a.txt", "hello\n")
        val result = fs().replaceRegexInFile("a.txt", "\\d+", "X")
        assertTrue(result.contains("not found"))
        assertEquals("hello\n", read("a.txt"))
    }

    @Test
    @DisplayName("replaceRegex invalid regex reports error")
    fun replaceRegexInvalid() = runBlocking {
        write("a.txt", "hello\n")
        val result = fs().replaceRegexInFile("a.txt", "(unclosed", "X")
        assertTrue(result.contains("Invalid regex"))
    }

    // ==================== editLines ====================

    @Test
    @DisplayName("editLines replaces a 1-based inclusive line range")
    fun editLinesBasic() = runBlocking {
        write("a.txt", "a\nb\nc\nd\ne\n")
        val result = fs().editLinesInFile("a.txt", 2, 4, "X\nY")
        assertTrue(result.contains("Replaced lines 2..4"))
        assertEquals("a\nX\nY\ne\n", read("a.txt"))
    }

    @Test
    @DisplayName("editLines rejects invalid ranges")
    fun editLinesInvalidRange() = runBlocking {
        write("a.txt", "a\nb\n")
        val result = fs().editLinesInFile("a.txt", 3, 2, "X")
        assertTrue(result.contains("Invalid line range"))
    }

    @Test
    @DisplayName("editLines beyond EOF reports error")
    fun editLinesBeyondEof() = runBlocking {
        write("a.txt", "a\n")
        val result = fs().editLinesInFile("a.txt", 5, 6, "X")
        assertTrue(result.contains("beyond file end"))
    }

    // ==================== insertAfter ====================

    @Test
    @DisplayName("insertAfter inserts after anchor line")
    fun insertAfterBasic() = runBlocking {
        write("a.txt", "a\nb\nc\n")
        val result = fs().insertAfterInFile("a.txt", "b", "B2")
        assertTrue(result.contains("Inserted after line 2"))
        assertEquals("a\nb\nB2\nc\n", read("a.txt"))
    }

    @Test
    @DisplayName("insertAfter missing anchor reports no change")
    fun insertAfterMissingAnchor() = runBlocking {
        write("a.txt", "a\n")
        val result = fs().insertAfterInFile("a.txt", "zzz", "X")
        assertTrue(result.contains("not found"))
        assertEquals("a\n", read("a.txt"))
    }

    // ==================== revert ====================

    @Test
    @DisplayName("revert restores a file to its first-write snapshot")
    fun revertRestoresContent() = runBlocking {
        write("a.txt", "original\n")
        val f = fs()
        f.writeFile("a.txt", "changed\n")
        val result = f.revert("a.txt")
        assertTrue(result.contains("Reverted"), "result: $result")
        assertEquals("original\n", read("a.txt"))
    }

    @Test
    @DisplayName("revert with no snapshot reports error")
    fun revertNoSnapshot() = runBlocking {
        write("a.txt", "never-tracked\n")
        val result = fs().revert("a.txt")
        assertTrue(result.contains("No snapshot"), "result: $result")
    }

    // ==================== diff (upgraded) ====================

    @Test
    @DisplayName("diff produces unified output with hunk header")
    fun diffUnified() = runBlocking {
        write("a.txt", "line1\nline2\nline3\nline4\n")
        val f = fs()
        f.writeFile("a.txt", "line1\nINSERTED\nline2\nline3\nline4\n")
        val result = f.diff("a.txt")
        assertTrue(result.contains("diff a.txt (myers)"), "result: $result")
        assertTrue(result.contains("+INSERTED"))
        // Myers keeps the following lines aligned (no spurious -line2/-line3).
        assertFalse(result.contains("-line2"), "naive misalignment leaked: $result")
        assertFalse(result.contains("-line3"), "naive misalignment leaked: $result")
    }

    @Test
    @DisplayName("diff accepts patience algorithm")
    fun diffPatience() = runBlocking {
        write("a.txt", "a\nb\nc\n")
        val f = fs()
        f.writeFile("a.txt", "a\nB\nc\n")
        val result = f.diff("a.txt", algorithm = "patience")
        assertTrue(result.contains("diff a.txt (patience)"))
        assertTrue(result.contains("-b"))
        assertTrue(result.contains("+B"))
    }

    @Test
    @DisplayName("diff reports no changes when identical")
    fun diffNoChanges() = runBlocking {
        write("a.txt", "same\n")
        val f = fs()
        val result = f.diff("a.txt")
        assertTrue(result.contains("No snapshot") || result.contains("No changes"), "result: $result")
    }

    // ==================== grep/glob excluded dirs ====================

    @Test
    @DisplayName("grep skips excluded directories by default")
    fun grepSkipsExcludedDirs() = runBlocking {
        write("src/a.txt", "needle here\n")
        write("node_modules/pkg/index.js", "needle in node_modules\n")
        write("target/generated.js", "needle in target\n")
        val f = fs()
        val result = f.grep("needle", path = ".")
        assertTrue(result.contains("a.txt"), "result: $result")
        assertFalse(result.contains("node_modules"), "excluded dir leaked: $result")
        assertFalse(result.contains("target/"), "excluded dir leaked: $result")
    }

    @Test
    @DisplayName("glob skips excluded directories by default")
    fun globSkipsExcludedDirs() = runBlocking {
        write("src/a.txt", "x\n")
        write("node_modules/deep/file.txt", "x\n")
        val f = fs()
        val result = f.glob("**/*.txt")
        assertTrue(result.contains("a.txt"), "result: $result")
        assertFalse(result.contains("node_modules"), "excluded dir leaked: $result")
    }

    @Test
    @DisplayName("custom exclusion set is honored")
    fun customExclusionSet() = runBlocking {
        write("keep/ok.txt", "x\n")
        write("vendor/skip.txt", "x\n")
        val f = CodingAgentFileSystem(tempDir, searchExcludedDirs = setOf("vendor"))
        val result = f.glob("**/*.txt")
        assertTrue(result.contains("ok.txt"), "result: $result")
        assertFalse(result.contains("vendor"), "custom exclusion leaked: $result")
    }

    @Test
    @DisplayName("grep stops at the match cap and says so")
    fun grepStopsAtMatchCap() = runBlocking {
        for (i in 1..20) write("src/F%02d.txt".format(i), "needle $i\n")

        val result = fs().grep("needle", path = ".", maxResults = 5)

        assertTrue(result.startsWith("Found 5 match(es)"), "result: $result")
        assertEquals(5, result.lineSequence().count { it.startsWith("  src") }, "result: $result")
        assertTrue(result.contains("match cap"), "result: $result")
    }

    @Test
    @DisplayName("grep reports a partial scan instead of pretending the text is absent")
    fun grepReportsPartialScan() = runBlocking {
        // A 1 ms budget cannot page in this many files, so the scan is cut short by definition.
        for (i in 1..400) write("src/deep/part/F%03d.txt".format(i), "needle $i\n")

        val result = fs().grep("needle", path = ".", budgetMillis = 1)

        assertTrue(result.contains("partial:"), "result: $result")
        assertTrue(result.contains("INCOMPLETE"), "result: $result")
    }

    @Test
    @DisplayName("grep results are ordered by path then line")
    fun grepResultsAreOrdered() = runBlocking {
        write("src/z.txt", "x\nneedle z\n")
        write("src/a.txt", "y\nneedle a\n")
        write("src/m.txt", "needle m\nneedle m2\n")

        val result = fs().grep("needle").replace('\\', '/')

        val paths = result.lineSequence()
            .filter { it.startsWith("  src/") }
            .map { it.trim().substringBefore(':') }
            .toList()
        assertEquals(listOf("src/a.txt", "src/m.txt", "src/m.txt", "src/z.txt"), paths, "result: $result")
    }

    @Test
    @DisplayName("grep matches a plain literal and a regex alike")
    fun grepMatchesLiteralAndRegex() = runBlocking {
        write("src/Lit.txt", "class CodingAgentFileSystem\n")

        val literal = fs().grep("CodingAgentFileSystem")
        assertTrue(literal.contains("Lit.txt:1"), "literal path: $literal")

        val regex = fs().grep("CodingAgent\\w+")
        assertTrue(regex.contains("Lit.txt:1"), "regex path: $regex")
    }

    @Test
    @DisplayName("grep still finds ASCII needles inside a non-UTF-8 file")
    fun grepMatchesNonUtf8FileAfterReplacement() = runBlocking {
        write("src/Utf8.txt", "needle in utf8\n")
        // Valid GBK, invalid UTF-8: the file must not vanish from the result.
        val gbk = java.nio.charset.Charset.forName("GBK")
        write("src/Gbk.txt", "")
        Files.write(tempDir.resolve("src/Gbk.txt"), "// 中文\nneedle in gbk\n".toByteArray(gbk))

        val result = fs().grep("needle")

        assertTrue(result.contains("Utf8.txt:1"), "result: $result")
        assertTrue(result.contains("Gbk.txt:2"), "a GBK file must not be dropped: $result")
        assertTrue(result.contains("not valid UTF-8"), "result: $result")
    }

    @Test
    @DisplayName("grep decodes a UTF-16 file with a BOM")
    fun grepMatchesUtf16File() = runBlocking {
        write("src/Utf16.txt", "")
        // Java's "UTF-16" encoder writes a big-endian BOM, which is what detection keys on.
        val text = "// 中文\nneedle in utf16\n"
        Files.write(
            tempDir.resolve("src/Utf16.txt"),
            text.toByteArray(java.nio.charset.Charset.forName("UTF-16")),
        )

        val result = fs().grep("needle")

        assertTrue(result.contains("Utf16.txt:2"), "result: $result")
    }

    @Test
    @DisplayName("grep searches a repo-level bin/ but skips a build-output one")
    fun grepSearchesScriptBinButSkipsBuildBin() = runBlocking {
        write("bin/run.ps1", "needle in scripts\n")
        write("dotnet/bin/Release/app.txt", "needle in build output\n")

        val result = fs().grep("needle").replace('\\', '/')

        assertTrue(result.contains("bin/run.ps1:1"), "repo scripts must stay searchable: $result")
        assertFalse(result.contains("build output"), "bin/Release is a build output: $result")
    }

    @Test
    @DisplayName("grep skips dependency and agent-bookkeeping directories by default")
    fun grepSkipsDependencyAndScratchDirs() = runBlocking {
        write("src/a.js", "needle own code\n")
        write("vendor/lib/index.js", "needle vendor\n")
        write("site-packages/pkg/mod.py", "needle dependency\n")
        write(".b4scratch/probe.js", "needle scratch\n")
        write(".b4inputs/spec.csv", "needle copied input\n")
        write(".b4build/bundle/manifest.txt", "needle old bundle\n")
        write(".test-sessions/run/index.html", "needle run artifact\n")
        write(".build-state/state.json", "needle build state\n")

        val result = fs().grep("needle")

        assertTrue(result.contains("a.js"), "result: $result")
        assertFalse(result.contains("vendor"), "result: $result")
        assertFalse(result.contains("site-packages"), "result: $result")
        assertFalse(result.contains(".b4scratch"), "result: $result")
        assertFalse(result.contains(".b4inputs"), "result: $result")
        assertFalse(result.contains(".b4build"), "result: $result")
        assertFalse(result.contains(".test-sessions"), "result: $result")
        assertFalse(result.contains(".build-state"), "result: $result")
    }

    @Test
    @DisplayName("filePattern with a separator matches the path below the search root")
    fun grepFilePatternWithSeparator() = runBlocking {
        write("src/main/A.kt", "needle kotlin\n")
        write("src/main/A.txt", "needle text\n")
        write("other/B.kt", "needle other\n")

        val byPath = fs().grep("needle", filePattern = "src/**/*.kt").replace('\\', '/')
        assertTrue(byPath.contains("src/main/A.kt"), "result: $byPath")
        assertFalse(byPath.contains("A.txt"), "result: $byPath")
        assertFalse(byPath.contains("other"), "result: $byPath")

        val byName = fs().grep("needle", filePattern = "*.kt").replace('\\', '/')
        assertTrue(byName.contains("src/main/A.kt") && byName.contains("other/B.kt"), "result: $byName")
    }

    @Test
    @DisplayName("grep windows a long line around the match instead of hiding it")
    fun grepWindowsLongLinesAroundTheMatch() = runBlocking {
        write("src/Long.txt", "x".repeat(300) + " NEEDLE_AT_300\n")

        val result = fs().grep("NEEDLE_AT_300")

        assertTrue(result.contains("NEEDLE_AT_300"), "the match itself must be visible: $result")
        assertTrue(result.contains("…"), "a windowed line is marked: $result")
    }

    // ==================== collectTextFiles ====================

    @Test
    @DisplayName("collectTextFiles collects readable text files with relative keys")
    fun collectTextFilesBasic() = runBlocking {
        write("src/main/App.kt", "package a\n")
        write("src/main/AppTest.kt", "package a\n")
        write("src/resources/plugin.json", "{}\n")
        val f = fs()
        val files = f.collectTextFiles("src")
        assertEquals(setOf("main/App.kt", "main/AppTest.kt", "resources/plugin.json"), files.keys)
        assertTrue(files["main/App.kt"]!!.contains("package a"))
    }

    @Test
    @DisplayName("collectTextFiles skips excluded dirs and binary files")
    fun collectTextFilesSkipsNoise() = runBlocking {
        write("src/keep.kt", "x\n")
        write("src/target/gen.kt", "x\n")
        write("src/node_modules/pkg.js", "x\n")
        write("src/logo.png", "\u0000\u0001\u0002".encodeToByteArray().toString(Charsets.ISO_8859_1))
        val f = fs()
        val files = f.collectTextFiles(".")
        assertTrue("src/keep.kt" in files.keys, "keys: ${files.keys}")
        assertFalse(files.keys.any { it.contains("target") || it.contains("node_modules") || it.endsWith(".png") },
            "noise leaked: ${files.keys}")
    }

    @Test
    @DisplayName("collectTextFiles returns empty for a file path or missing dir")
    fun collectTextFilesEdgeCases() = runBlocking {
        write("only.txt", "x\n")
        val f = fs()
        assertTrue(f.collectTextFiles("only.txt").isEmpty(), "file path is not a directory")
        assertTrue(f.collectTextFiles("does-not-exist").isEmpty(), "missing dir yields empty")
    }

    // ==================== delete hard protections ====================

    @Test
    @DisplayName("delete refuses to remove the workspace root")
    fun deleteWorkspaceRootProtected() = runBlocking {
        val f = fs()
        val result = f.delete(".", recursive = true)
        assertTrue(result.contains("workspace root"), "result: $result")
    }

    @Test
    @DisplayName("delete refuses to remove .git recursively")
    fun deleteVcsProtected() = runBlocking {
        write(".git/config", "dummy\n")
        val f = fs()
        val result = f.delete(".git", recursive = true)
        assertTrue(result.contains("version-control"), "result: $result")
        assertTrue(tempDir.resolve(".git/config").toFile().exists(), ".git must survive")
    }

    @Test
    @DisplayName("delete refuses recursive delete of a dir containing .git")
    fun deleteParentContainingVcsProtected() = runBlocking {
        write("repo/.git/config", "dummy\n")
        write("repo/src/main.kt", "x\n")
        val f = fs()
        val result = f.delete("repo", recursive = true)
        assertTrue(result.contains("version-control"), "result: $result")
        assertTrue(tempDir.resolve("repo/.git/config").toFile().exists(), "nested .git must survive")
    }

    @Test
    @DisplayName("delete of ordinary file still works")
    fun deleteOrdinaryFileWorks() = runBlocking {
        write("junk.txt", "bye\n")
        val f = fs()
        val result = f.delete("junk.txt")
        assertTrue(result.contains("Deleted"), "result: $result")
        assertFalse(tempDir.resolve("junk.txt").toFile().exists())
    }

    // ==================== repo-governance protection ====================

    @Test
    @DisplayName("replaceInFile is blocked on VERSION")
    fun replaceVersionProtected() = runBlocking {
        write("VERSION", "4.0.0\n")
        val f = fs()
        val result = f.replaceInFile("VERSION", "4.0.0", "9.9.9")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("4.0.0\n", read("VERSION"), "VERSION must be untouched")
    }

    @Test
    @DisplayName("replaceRegexInFile is blocked on AGENTS.md")
    fun replaceRegexAgentsProtected() = runBlocking {
        write("AGENTS.md", "# guidelines\n")
        val f = fs()
        val result = f.replaceRegexInFile("AGENTS.md", "guidelines", "rules")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("# guidelines\n", read("AGENTS.md"))
    }

    @Test
    @DisplayName("editLinesInFile is blocked on CLAUDE.md")
    fun editLinesClaudeProtected() = runBlocking {
        write("CLAUDE.md", "line1\nline2\n")
        val f = fs()
        val result = f.editLinesInFile("CLAUDE.md", 1, 1, "changed")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("line1\nline2\n", read("CLAUDE.md"))
    }

    @Test
    @DisplayName("insertAfterInFile is blocked on AGENTS.md")
    fun insertAfterAgentsProtected() = runBlocking {
        write("AGENTS.md", "# title\n")
        val f = fs()
        val result = f.insertAfterInFile("AGENTS.md", "title", "hijacked")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("# title\n", read("AGENTS.md"))
    }

    @Test
    @DisplayName("delete is blocked on VERSION")
    fun deleteVersionProtected() = runBlocking {
        write("VERSION", "4.0.0\n")
        val f = fs()
        val result = f.delete("VERSION")
        assertTrue(result.contains("protected"), "result: $result")
        assertTrue(tempDir.resolve("VERSION").toFile().exists(), "VERSION must survive")
    }

    @Test
    @DisplayName("root pom.xml is protected by exact relative path")
    fun rootPomProtected() = runBlocking {
        write("pom.xml", "<project/>\n")
        val f = fs()
        val result = f.replaceInFile("pom.xml", "<project/>", "<project hacked/>")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("<project/>\n", read("pom.xml"))
    }

    @Test
    @DisplayName("module pom.xml stays editable")
    fun modulePomEditable() = runBlocking {
        write("browser4-rest/pom.xml", "<project/>\n")
        val f = fs()
        val result = f.replaceInFile("browser4-rest/pom.xml", "<project/>", "<project hacked/>")
        assertTrue(result.contains("Replaced"), "result: $result")
        assertEquals("<project hacked/>\n", read("browser4-rest/pom.xml"))
    }

    @Test
    @DisplayName("BOM path is protected by exact relative path")
    fun bomProtected() = runBlocking {
        write("browser4-dependencies/pom.xml", "<project/>\n")
        val f = fs()
        val result = f.replaceInFile("browser4-dependencies/pom.xml", "<project/>", "<project hacked/>")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("<project/>\n", read("browser4-dependencies/pom.xml"))
    }

    @Test
    @DisplayName("CI workflow is protected")
    fun ciWorkflowProtected() = runBlocking {
        write(".github/workflows/ci.yml", "jobs: {}\n")
        val f = fs()
        val result = f.replaceInFile(".github/workflows/ci.yml", "jobs", "evil")
        assertTrue(result.contains("protected"), "result: $result")
        assertEquals("jobs: {}\n", read(".github/workflows/ci.yml"))
    }

    @Test
    @DisplayName("custom protectedFiles set is honored")
    fun customProtectedFiles() = runBlocking {
        write("SPEC.txt", "x\n")
        write("other.txt", "x\n")
        val f = CodingAgentFileSystem(tempDir, protectedFiles = setOf("SPEC.txt"))
        val blocked = f.replaceInFile("SPEC.txt", "x", "y")
        assertTrue(blocked.contains("protected"), "result: $blocked")
        val allowed = f.replaceInFile("other.txt", "x", "y")
        assertTrue(allowed.contains("Replaced"), "result: $allowed")
        assertEquals("y\n", read("other.txt"))
    }

    // ==================== dynamic protection (protect) ====================

    @Test
    @DisplayName("protect blocks destructive ops until unprotected")
    fun dynamicProtectRoundTrip() = runBlocking {
        write("secret.txt", "hidden\n")
        val f = fs()
        val protectResult = f.protect("secret.txt", on = true)
        assertTrue(protectResult.contains("Protected"), "result: $protectResult")
        val blocked = f.replaceInFile("secret.txt", "hidden", "leaked")
        assertTrue(blocked.contains("protected"), "result: $blocked")
        assertEquals("hidden\n", read("secret.txt"), "file must be untouched while protected")

        assertTrue(f.protectedList().contains("secret.txt"), f.protectedList())
        f.protect("secret.txt", on = false)
        val allowed = f.replaceInFile("secret.txt", "hidden", "ok")
        assertTrue(allowed.contains("Replaced"), "result: $allowed")
        assertEquals("ok\n", read("secret.txt"))
        assertFalse(f.protectedList().contains("secret.txt"), f.protectedList())
    }

    @Test
    @DisplayName("dynamic protection matches the exact relative path only")
    fun dynamicProtectExactPath() = runBlocking {
        write("src/keep.txt", "a\n")
        write("other/keep.txt", "b\n")
        val f = fs()
        f.protect("src/keep.txt")
        assertTrue(f.replaceInFile("src/keep.txt", "a", "x").contains("protected"))
        // Same basename elsewhere stays editable.
        val allowed = f.replaceInFile("other/keep.txt", "b", "y")
        assertTrue(allowed.contains("Replaced"), "result: $allowed")
    }

    @Test
    @DisplayName("protectedList reports empty when nothing is dynamically protected")
    fun protectedListEmpty() = runBlocking {
        val f = fs()
        assertTrue(f.protectedList().contains("No dynamic protections"), f.protectedList())
    }
}


