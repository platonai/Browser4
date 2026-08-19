package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression tests for the P0 `code glob` / `code list` bugs:
 *
 * 1. glob base-dir split used `indexOfLast` on the LAST non-wildcard segment and then
 *    pulled wildcard segments into `Path.resolve()` → "Illegal char <*>" on Windows
 *    (e.g. `glob "dir/*/pom.xml"`).
 * 2. JDK glob semantics require at least one directory level for `**/*`, so root-level
 *    files were silently missed by `glob "dir/**/*"`.
 * 3. `listDir` silently capped `maxDepth` at 5 — a requested depth of 10 walked only 5.
 */
class CodingAgentFileSystemGlobListTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fs(): CodingAgentFileSystem = CodingAgentFileSystem(tempDir)

    /** Output paths use the platform separator (backslash on Windows); normalize for assertions. */
    private fun norm(s: String): String = s.replace('\\', '/')

    private fun write(path: String, content: String = "x") {
        val f = tempDir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    // ==================== glob base-dir split ====================

    @Test
    @DisplayName("glob with wildcard in the middle splits base at the FIRST wildcard segment")
    fun globWildcardInMiddle() = runBlocking {
        write("a/b/pom.xml")
        write("a/c/pom.xml")
        write("a/b/readme.md")
        write("other/pom.xml")

        // Used to throw "Illegal char <*>" on Windows: the base dir was built from
        // ["a", "*", "pom.xml"] instead of ["a"].
        val result = fs().glob("a/*/pom.xml")
        assertFalse(norm(result).contains("Illegal char"), "must not throw InvalidPathException: $result")
        assertTrue(norm(result).contains("a/b/pom.xml"), "result: $result")
        assertTrue(norm(result).contains("a/c/pom.xml"), "result: $result")
        assertFalse(norm(result).contains("other"), "base dir must be 'a', not the whole repo: $result")
    }

    @Test
    @DisplayName("glob with deep wildcard pattern keeps the correct base dir")
    fun globDeepWildcardBase() = runBlocking {
        write("src/main/kotlin/A.kt")
        write("src/main/kotlin/B.kt")
        write("src/test/kotlin/ATest.kt")

        val result = fs().glob("src/**/*.kt")
        assertTrue(norm(result).contains("src/main/kotlin/A.kt"), "result: $result")
        assertTrue(norm(result).contains("src/test/kotlin/ATest.kt"), "result: $result")
        assertFalse(norm(result).contains("Illegal char"), "must not throw: $result")
    }

    @Test
    @DisplayName("glob with wildcard only in the filename searches from the workspace root")
    fun globFilenameOnly() = runBlocking {
        write("x.txt")
        write("sub/y.txt")
        val result = fs().glob("*.txt")
        assertTrue(norm(result).contains("x.txt"), "root-level file: $result")
        assertTrue(norm(result).contains("sub/y.txt"), "nested file (loose basename matching): $result")
    }

    // ==================== glob **/* root-level files ====================

    @Test
    @DisplayName("glob **/* matches root-level files (JDK glob requires a directory level)")
    fun globDoubleStarMatchesRootFiles() = runBlocking {
        write("pom.xml")
        write("README.md")
        write("sub/deep/file.txt")

        // JDK's glob "**/*" matches "sub/deep/file.txt" but NOT "pom.xml" — the fix adds a
        // root-level matcher so files directly under the base dir are included.
        val result = fs().glob("**/*")
        assertTrue(norm(result).contains("pom.xml"), "root-level file must be matched: $result")
        assertTrue(norm(result).contains("README.md"), "root-level file must be matched: $result")
        assertTrue(norm(result).contains("sub/deep/file.txt"), "nested file must be matched: $result")
    }

    @Test
    @DisplayName("glob **/*.kt matches root-level and nested kotlin files")
    fun globDoubleStarKtRootFiles() = runBlocking {
        write("Root.kt")
        write("sub/Inner.kt")
        write("sub/deep/Deep.kt")
        write("sub/note.txt")

        val result = fs().glob("**/*.kt")
        assertTrue(norm(result).contains("Root.kt"), "root-level .kt must be matched: $result")
        assertTrue(norm(result).contains("sub/Inner.kt"), "result: $result")
        assertTrue(norm(result).contains("sub/deep/Deep.kt"), "result: $result")
        assertFalse(norm(result).contains("note.txt"), "non-matching extension leaked: $result")
    }

    @Test
    @DisplayName("glob dir/**/* matches files directly under dir")
    fun globDirDoubleStarRootFiles() = runBlocking {
        write("plugins/pom.xml")
        write("plugins/sub/pom.xml")
        write("plugins/sub/deep/pom.xml")

        val result = fs().glob("plugins/**/*")
        assertTrue(norm(result).contains("plugins/pom.xml"), "file directly under base dir: $result")
        assertTrue(norm(result).contains("plugins/sub/pom.xml"), "result: $result")
        assertTrue(norm(result).contains("plugins/sub/deep/pom.xml"), "result: $result")
    }

    // ==================== listDir depth ====================

    @Test
    @DisplayName("listDir honors a requested depth deeper than 5")
    fun listDirDeepDepth() = runBlocking {
        write("d1/d2/d3/d4/d5/d6/d7/d8/leaf.txt")

        // Old behavior: Files.walk(resolved, maxDepth.coerceIn(1, 5)) silently walked 5 levels.
        val result = fs().listDir(".", maxDepth = 10)
        assertTrue(norm(result).contains("d1/d2/d3/d4/d5/d6/d7/d8/leaf.txt"),
            "depth-8 entry must be listed: $result")
    }

    @Test
    @DisplayName("listDir maxDepth=1 still lists only direct children")
    fun listDirShallowDepth() = runBlocking {
        write("top.txt")
        write("d1/inner.txt")
        val result = fs().listDir(".", maxDepth = 1)
        assertTrue(norm(result).contains("top.txt"), "result: $result")
        assertFalse(norm(result).contains("d1/inner.txt"), "depth-2 entry must not appear: $result")
    }

    @Test
    @DisplayName("listDir caps excessive depth with an explicit notice instead of silence")
    fun listDirDepthCappedWithNotice() = runBlocking {
        write("x.txt")
        val result = fs().listDir(".", maxDepth = 100)
        assertTrue(norm(result).contains("depth capped at 32"), "capping must be explicit: $result")
    }
}
