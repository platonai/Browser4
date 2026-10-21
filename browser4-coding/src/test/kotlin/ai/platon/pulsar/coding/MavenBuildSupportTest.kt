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
        // The full path is kept (plan P1.4): a bare basename cannot be opened.
        assertTrue(
            d.file.endsWith("ai/platon/pulsar/agentic/tools/builtin/CodingToolExecutor.kt"),
            "file: ${d.file}"
        )
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
        assertTrue(diags[0].file.endsWith("ArtifactScaffolds.kt"), "file: ${diags[0].file}")
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
        assertTrue(diags[0].file.endsWith("Foo.kt"), "file: ${diags[0].file}")
        assertEquals(12, diags[0].line)
        assertEquals(5, diags[0].column)
    }

    @Test
    @DisplayName("parseDiagnostics parses the javac bracket form (path:[line,col])")
    fun parseJavacBracketFormat() {
        val output = """
            [ERROR] /home/dev/Browser4/browser4-core/src/main/java/ai/platon/pulsar/Foo.java:[34,2] cannot find symbol
        """.trimIndent()
        val diags = MavenBuildSupport.parseDiagnostics(output)
        assertEquals(1, diags.size, "diags: $diags")
        assertEquals(34, diags[0].line)
        assertEquals(2, diags[0].column)
        assertEquals("error", diags[0].severity)
        assertEquals("cannot find symbol", diags[0].message)
    }

    @Test
    @DisplayName("parseDiagnostics distinguishes e: errors from w: warnings")
    fun parseKotlinSeverityPrefixes() {
        val output = """
            e: file:///D:/repo/A.kt:1:1 Unresolved reference 'x'.
            w: file:///D:/repo/A.kt:2:1 Parameter 'y' is never used.
        """.trimIndent()
        val diags = MavenBuildSupport.parseDiagnostics(output)
        assertEquals(2, diags.size, "diags: $diags")
        assertEquals("error", diags[0].severity)
        assertEquals("warning", diags[1].severity)
    }

    @Test
    @DisplayName("parseDiagnostics makes paths workspace-relative when a root is given")
    fun parseWorkspaceRelativePaths() {
        val root = java.nio.file.Files.createTempDirectory("b4-diag")
        try {
            val file = root.resolve("browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/Foo.kt")
            java.nio.file.Files.createDirectories(file.parent)
            java.nio.file.Files.writeString(file, "class Foo")
            val output = "[ERROR] $file:12:5: error: Unresolved reference 'bar'"
            val diags = MavenBuildSupport.parseDiagnostics(output, root)
            assertEquals(1, diags.size, "diags: $diags")
            assertEquals("browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/Foo.kt", diags[0].file)
        } finally {
            root.toFile().deleteRecursively()
        }
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
        // Full paths are kept (plan P1.4) — a bare basename cannot be opened.
        assertEquals(
            listOf("D:/a/A.kt", "D:/a/B.kt", "D:/a/C.kt"),
            diags.map { it.file },
        )
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

    // ==================== module selector fallback ====================

    @Test
    @DisplayName("Maven's path-selector diagnostic is recognized")
    fun selectorFailureDetected() {
        val failure = """
            [ERROR] Could not find the selected project in the reactor: browser4-coding @
            [ERROR] -> [Help 1]
        """.trimIndent()
        assertTrue(isMavenModuleSelectorFailure(failure))
        assertTrue(isMavenModuleSelectorFailure("could not find the selected project in the reactor: x"))
        assertFalse(isMavenModuleSelectorFailure("[ERROR] Unresolved reference 'x'"))
        assertFalse(isMavenModuleSelectorFailure("BUILD SUCCESS"))
    }

    @Test
    @DisplayName("command shapes stay stable (goal first for test, artifact-id fallback selectable)")
    fun commandShapes() {
        // The goal comes first for tests — every log/document/test assumes it.
        assertEquals(
            "mvn test -pl browser4-rest -am -Dtest=XControllerTest -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -q",
            mavenTestCommand("mvn", "browser4-rest", listOf("XControllerTest")),
        )
        assertEquals(
            "mvn test -pl browser4-rest -am -DskipTests=false -q",
            mavenTestCommand("mvn", "browser4-rest", emptyList()),
        )
        // The fallback replaces the selector only.
        assertEquals(
            "mvnw -pl :browser4-coding -am compile -DskipTests -q",
            mavenBuildCommand("mvnw", ":browser4-coding", "compile", skipTests = true),
        )
        assertEquals(
            "mvnw -pl browser4-coding -am compile -q",
            mavenBuildCommand("mvnw", "browser4-coding", "compile", skipTests = false),
        )
    }

    @Test
    @DisplayName("the module artifactId is read from its pom (parent excluded, ':' prefix tolerated)")
    fun artifactIdResolvedFromPom() {
        val root = java.nio.file.Files.createTempDirectory("b4-mvn-module")
        try {
            val moduleDir = java.nio.file.Files.createDirectories(root.resolve("browser4-demo"))
            java.nio.file.Files.writeString(
                moduleDir.resolve("pom.xml"),
                """
                <project>
                  <parent>
                    <artifactId>browser4</artifactId>
                    <version>4.14.2-SNAPSHOT</version>
                  </parent>
                  <artifactId>browser4-demo</artifactId>
                </project>
                """.trimIndent()
            )
            val shell = CodingAgentShell(baseDir = root)
            assertEquals("browser4-demo", resolveModuleArtifactId(shell, "browser4-demo", root.toString()))
            assertEquals("browser4-demo", resolveModuleArtifactId(shell, ":browser4-demo", root.toString()))
            assertNull(resolveModuleArtifactId(shell, "no-such-module", root.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}



