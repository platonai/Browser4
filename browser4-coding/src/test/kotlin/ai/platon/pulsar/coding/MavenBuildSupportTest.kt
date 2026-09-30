package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [MavenBuildSupport] — the Kotlin/Java compiler-error parser and
 * the structured build result formatting.
 */
class MavenBuildSupportTest {

    @Test
    @DisplayName("parseDiagnostics parses kotlin-maven-plugin error format")
    fun parseKotlinMavenError() {
        val output = """
            [ERROR] file:///D:/workspace/Browser4/Browser4-4.14/browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/CodingToolExecutor.kt:73:58 None of the following candidates is applicable:
            [ERROR]   static fun of(p0: String!, vararg p1: String!): Path!:
            [ERROR]     Argument type mismatch: actual type is 'Path', but 'String!' was expected.
            [ERROR] Compilation failure
        """.trimIndent()
        val diags = MavenBuildSupport.parseDiagnostics(output)
        assertEquals(1, diags.size, "expected exactly one diagnostic, got $diags")
        val d = diags[0]
        assertEquals("CodingToolExecutor.kt", d.file)
        assertEquals(73, d.line)
        assertEquals(58, d.column)
        assertEquals("error", d.severity)
        assertTrue(d.message.contains("candidates is applicable"), "message: ${d.message}")
    }

    @Test
    @DisplayName("parseDiagnostics parses Windows backslash path format")
    fun parseWindowsPathError() {
        val output = """
            e: file:///D:/workspace/Browser4/Browser4-4.14/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/ArtifactScaffolds.kt:271:102 Unresolved reference 'path'.
        """.trimIndent()
        val diags = MavenBuildSupport.parseDiagnostics(output)
        assertEquals(1, diags.size)
        assertEquals("ArtifactScaffolds.kt", diags[0].file)
        assertEquals(271, diags[0].line)
        assertTrue(diags[0].message.contains("Unresolved reference 'path'"))
    }

    @Test
    @DisplayName("parseDiagnostics parses reverse format (error: file:line:col message)")
    fun parseReverseFormat() {
        val output = """
            [ERROR] error: file:///D:/workspace/Foo.kt:12:5 Unresolved reference 'bar'
        """.trimIndent()
        val diags = MavenBuildSupport.parseDiagnostics(output)
        assertEquals(1, diags.size)
        assertEquals("Foo.kt", diags[0].file)
        assertEquals(12, diags[0].line)
        assertEquals(5, diags[0].column)
    }

    @Test
    @DisplayName("parseDiagnostics returns empty for clean build output")
    fun parseCleanBuild() {
        val output = "[INFO] BUILD SUCCESS\n[INFO] Total time: 3.2 s"
        assertTrue(MavenBuildSupport.parseDiagnostics(output).isEmpty())
    }

    @Test
    @DisplayName("parseDiagnostics captures multiple errors")
    fun parseMultiple() {
        val output = """
            [ERROR] file:///D:/a/A.kt:1:1 error: Unresolved reference 'x'
            [ERROR] file:///D:/a/B.kt:2:2 error: Unresolved reference 'y'
            [WARNING] file:///D:/a/C.kt:3:3 'unused' is never used
        """.trimIndent()
        val diags = MavenBuildSupport.parseDiagnostics(output)
        assertEquals(3, diags.size)
        assertEquals(listOf("A.kt", "B.kt", "C.kt"), diags.map { it.file })
        assertEquals("warning", diags[2].severity)
    }

    @Test
    @DisplayName("format shows diagnostics for failed build")
    fun formatFailedBuild() {
        val result = BuildResult(
            module = "browser4-rest",
            goals = "compile",
            exitCode = 1,
            output = "",
            diagnostics = listOf(
                BuildDiagnostic("A.kt", 1, 1, "error", "boom"),
                BuildDiagnostic("B.kt", 2, 2, "error", "bam"),
            ),
        )
        val formatted = MavenBuildSupport().format(result)
        assertTrue(formatted.contains("browser4-rest"))
        assertTrue(formatted.contains("[error] A.kt:1:1 — boom"))
        assertTrue(formatted.contains("Diagnostics (2)"))
    }

    @Test
    @DisplayName("format shows success for clean build")
    fun formatSuccess() {
        val result = BuildResult(
            module = "browser4-coding", goals = "compile", exitCode = 0, output = "", diagnostics = emptyList(),
        )
        val formatted = MavenBuildSupport().format(result)
        assertTrue(formatted.contains("✓ Build succeeded"))
    }

    @Test
    @DisplayName("format shows output tail when failed but no diagnostics parseable")
    fun formatNoDiagnosticsFallback() {
        val result = BuildResult(
            module = "browser4-rest", goals = "compile", exitCode = 1,
            output = "some opaque error output", diagnostics = emptyList(),
        )
        val formatted = MavenBuildSupport().format(result)
        assertTrue(formatted.contains("no parseable diagnostics"))
        assertTrue(formatted.contains("some opaque error output"))
    }
}



