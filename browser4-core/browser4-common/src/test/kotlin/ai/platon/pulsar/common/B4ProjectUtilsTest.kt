package ai.platon.pulsar.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [B4ProjectUtils]. The file/directory walking logic is fully deterministic when
 * driven by an explicit base directory, so these tests build small temporary trees and assert on
 * the walking/filtering behaviour without depending on the host project layout.
 */
class B4ProjectUtilsTest {

    @Test
    @DisplayName("walkToFindFiles finds a file by name anywhere in the subtree")
    fun walkToFindFilesFindsNestedFile(@TempDir root: Path) {
        val target = root.resolve("deep/nested/dir/build.gradle.kts")
        Files.createDirectories(target.parent)
        Files.writeString(target, "content")

        val results = B4ProjectUtils.walkToFindFiles("build.gradle.kts", root)
        assertEquals(1, results.size)
        assertTrue(results.contains(target))
    }

    @Test
    @DisplayName("walkToFindFiles excludes target and build directories")
    fun walkToFindFilesExcludesBuildArtifacts(@TempDir root: Path) {
        val kept = root.resolve("src/main/App.kt")
        val inTarget = root.resolve("target/App.kt")
        val inBuild = root.resolve("module/build/App.kt")
        Files.createDirectories(kept.parent)
        Files.createDirectories(inTarget.parent)
        Files.createDirectories(inBuild.parent)
        Files.writeString(kept, "a")
        Files.writeString(inTarget, "b")
        Files.writeString(inBuild, "c")

        val results = B4ProjectUtils.walkToFindFiles("App.kt", root)
        assertEquals(1, results.size)
        assertEquals(kept, results.first())
    }

    @Test
    @DisplayName("walkToFindFiles returns an empty list when the file does not exist")
    fun walkToFindFilesReturnsEmptyWhenMissing(@TempDir root: Path) {
        assertTrue(B4ProjectUtils.walkToFindFiles("missing.txt", root).isEmpty())
    }

    @Test
    @DisplayName("findProjectRootDir locates the directory containing the VERSION file")
    fun findProjectRootDirFindsVersionDirectory(@TempDir root: Path) {
        Files.writeString(root.resolve("VERSION"), "4.12.0")
        val child = root.resolve("a/b/c")
        Files.createDirectories(child)

        assertEquals(root.normalize(), B4ProjectUtils.findProjectRootDir(child)?.normalize())
    }

    @Test
    @DisplayName("findProjectRootDir returns null when no VERSION file is found")
    fun findProjectRootDirReturnsNullWithoutVersion(@TempDir root: Path) {
        // No VERSION file anywhere in this isolated tree.
        assertNull(B4ProjectUtils.findProjectRootDir(root))
    }

    @Test
    @DisplayName("parseJarVersionFromLocation extracts the version from a plain classpath jar")
    fun parseJarVersionFromPlainClasspathJar() {
        val location = "file:/home/user/.m2/repository/ai/platon/pulsar/pulsar-browser/4.11.6/pulsar-browser-4.11.6.jar"
        assertEquals("4.11.6", B4ProjectUtils.parseJarVersionFromLocation("pulsar-browser", location))
    }

    @Test
    @DisplayName("parseJarVersionFromLocation extracts the version from a jar nested in an uber jar")
    fun parseJarVersionFromNestedUberJar() {
        val location = "jar:file:/opt/browser4/Browser4.jar!/BOOT-INF/lib/pulsar-browser-4.11.6-SNAPSHOT.jar!/"
        assertEquals("4.11.6-SNAPSHOT", B4ProjectUtils.parseJarVersionFromLocation("pulsar-browser", location))
    }

    @Test
    @DisplayName("parseJarVersionFromLocation returns null for non-jar locations")
    fun parseJarVersionFromNonJarLocation() {
        val location = "file:/workspace/browser4-agentic/target/classes/"
        assertNull(B4ProjectUtils.parseJarVersionFromLocation("pulsar-browser", location))
    }

    @Test
    @DisplayName("parseJarVersionFromLocation returns null when the artifact id is absent")
    fun parseJarVersionFromUnrelatedJar() {
        val location = "file:/home/user/.m2/repository/org/jsoup/jsoup/1.17.2/jsoup-1.17.2.jar"
        assertNull(B4ProjectUtils.parseJarVersionFromLocation("pulsar-browser", location))
    }
}
