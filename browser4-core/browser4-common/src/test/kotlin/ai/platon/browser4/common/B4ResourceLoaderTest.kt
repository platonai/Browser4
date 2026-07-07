package ai.platon.browser4.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [B4ResourceLoader]. The loader supports reading from an in-memory string
 * resource, the file system, or the classpath. These tests exercise the pure string-resource
 * branch (fully deterministic, no I/O) and the classpath-backed reading using a bundled test
 * resource, plus the default line filter semantics.
 */
class B4ResourceLoaderTest {

    @Test
    @DisplayName("readAllLines from a string resource applies the default line filter")
    fun readAllLinesFromStringResourceAppliesFilter() {
        val content = """
            # this is a comment
            valid line 1
            -- also a comment

            valid line 2
            #nospacekept
        """.trimIndent()

        val lines = B4ResourceLoader.readAllLines(content, "ignored-resource-name")
        assertEquals(listOf("valid line 1", "valid line 2", "#nospacekept"), lines)
    }

    @Test
    @DisplayName("readAllLines from a string resource keeps everything when filter is disabled")
    fun readAllLinesFromStringResourceWithoutFilterKeepsAll() {
        val content = "# c\nkeep me\n\nsecond"
        val lines = B4ResourceLoader.readAllLines(content, "ignored", filter = false)
        assertEquals(listOf("# c", "keep me", "", "second"), lines)
    }

    @Test
    @DisplayName("readAllLines with a custom predicate filters by it")
    fun readAllLinesWithCustomPredicate() {
        val lines = B4ResourceLoader.readAllLines("loader-test.txt") { it.startsWith("valid") }
        assertEquals(listOf("valid line 1", "valid line 2"), lines)
    }

    @Test
    @DisplayName("readAllLines returns an empty list for a missing classpath resource")
    fun readAllLinesMissingResourceReturnsEmpty() {
        val lines = B4ResourceLoader.readAllLines("this-resource-does-not-exist-anywhere.txt")
        assertTrue(lines.isEmpty())
    }

    @Test
    @DisplayName("readAllLines reads and filters a bundled classpath resource")
    fun readAllLinesReadsClasspathResource() {
        val lines = B4ResourceLoader.readAllLines("loader-test.txt")
        assertEquals(listOf("valid line 1", "valid line 2", "#nospacekept"), lines)
    }

    @Test
    @DisplayName("readAllLines without filter keeps comments and blank lines from classpath resource")
    fun readAllLinesClasspathResourceWithoutFilter() {
        val lines = B4ResourceLoader.readAllLines("loader-test.txt", filter = false)
        assertEquals(
            listOf(
                "# This is a comment",
                "valid line 1",
                "-- also a comment",
                "",
                "valid line 2",
                "#nospacekept"
            ),
            lines
        )
    }

    @Test
    @DisplayName("readString concatenates the resource content with line separators")
    fun readStringConcatenatesContent() {
        val content = B4ResourceLoader.readString("loader-test.txt")
        assertFalse(content.isBlank())
        assertTrue(content.contains("valid line 1"))
        assertTrue(content.contains("valid line 2"))
    }

    @Test
    @DisplayName("exists returns false for an unknown resource name")
    fun existsReturnsFalseForUnknownResource() {
        assertTrue(!B4ResourceLoader.exists("no-such-resource-xyz.txt"))
    }
}
