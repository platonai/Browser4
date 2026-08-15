package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.ShellResult
import ai.platon.pulsar.agentic.model.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CodingToolExecutorTest {

    private lateinit var shell: CodingAgentShell
    private lateinit var fs: CodingAgentFileSystem
    private lateinit var target: CodingToolExecutor.Target
    private lateinit var executor: CodingToolExecutor

    @BeforeEach
    fun setUp() {
        shell = mockk(relaxed = true)
        fs = mockk(relaxed = true)
        target = CodingToolExecutor.Target(shell, fs)
        executor = CodingToolExecutor()
    }

    // ==================== Shell methods ====================

    @Nested
    @DisplayName("Shell methods")
    inner class ShellMethods {

        @Test
        @DisplayName("shell calls shell.execute with correct args")
        fun testShellExecute() = runBlocking {
            coEvery { shell.execute(any(), any(), any()) } returns "command output"

            val tc = ToolCall(
                domain = "coding",
                method = "shell",
                arguments = mutableMapOf("command" to "mvn test", "timeoutSeconds" to "60", "workingDir" to "/project")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { shell.execute("mvn test", 60L, "/project") }
            assertEquals("command output", result.value)
        }

        @Test
        @DisplayName("shell uses default timeoutSeconds=120 and workingDir=null")
        fun testShellExecuteDefaults() = runBlocking {
            coEvery { shell.execute(any(), any(), any()) } returns "output"

            val tc = ToolCall(
                domain = "coding",
                method = "shell",
                arguments = mutableMapOf("command" to "cargo build")
            )

            executor.callFunctionOn(tc, target)
            coVerify { shell.execute("cargo build", 120L, null) }
        }

        @Test
        @DisplayName("shellOutput calls shell.readOutput with sessionId")
        fun testShellOutput() = runBlocking {
            every { shell.readOutput(any()) } returns "session output"

            val tc = ToolCall(
                domain = "coding",
                method = "shellOutput",
                arguments = mutableMapOf("sessionId" to "shell-42")
            )

            val result = executor.callFunctionOn(tc, target)
            verify { shell.readOutput("shell-42") }
            assertEquals("session output", result.value)
        }

        @Test
        @DisplayName("shellStatus calls shell.getStatus with sessionId")
        fun testShellStatus() = runBlocking {
            every { shell.getStatus(any()) } returns "running"

            val tc = ToolCall(
                domain = "coding",
                method = "shellStatus",
                arguments = mutableMapOf("sessionId" to "shell-7")
            )

            val result = executor.callFunctionOn(tc, target)
            verify { shell.getStatus("shell-7") }
            assertEquals("running", result.value)
        }

        @Test
        @DisplayName("shellList calls shell.listSessions")
        fun testShellList() = runBlocking {
            every { shell.listSessions() } returns "2 active sessions"

            val tc = ToolCall(
                domain = "coding",
                method = "shellList",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            verify { shell.listSessions() }
            assertEquals("2 active sessions", result.value)
        }

        @Test
        @DisplayName("shellSetEnv calls shell.setEnv and returns confirmation")
        fun testShellSetEnv() = runBlocking {
            every { shell.setEnv(any(), any()) } returns Unit

            val tc = ToolCall(
                domain = "coding",
                method = "shellSetEnv",
                arguments = mutableMapOf("name" to "JAVA_HOME", "value" to "/usr/lib/jvm/java-17")
            )

            val result = executor.callFunctionOn(tc, target)
            verify { shell.setEnv("JAVA_HOME", "/usr/lib/jvm/java-17") }
            assertTrue((result.value as String).contains("Environment variable set"))
        }

        @Test
        @DisplayName("toolsDetect returns available tools when found")
        fun testToolsDetect() = runBlocking {
            every { shell.detectAvailableTools() } returns setOf("git", "mvn", "node", "cargo")

            val tc = ToolCall(
                domain = "coding",
                method = "toolsDetect",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            verify { shell.detectAvailableTools() }
            assertEquals("Available tools: cargo, git, mvn, node", result.value)
        }

        @Test
        @DisplayName("toolsDetect returns message when no tools found")
        fun testToolsDetectEmpty() = runBlocking {
            every { shell.detectAvailableTools() } returns emptySet()

            val tc = ToolCall(
                domain = "coding",
                method = "toolsDetect",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            assertEquals("No dev tools detected on PATH", result.value)
        }

        @Test
        @DisplayName("projectType calls shell.detectProjectType")
        fun testProjectType() = runBlocking {
            every { shell.detectProjectType() } returns "maven"

            val tc = ToolCall(
                domain = "coding",
                method = "projectType",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            verify { shell.detectProjectType() }
            assertEquals("Project type: maven", result.value)
        }
    }

    // ==================== File system methods ====================

    @Nested
    @DisplayName("File system methods")
    inner class FileSystemMethods {

        // --- suspend FS methods use coEvery / coVerify ---

        @Test
        @DisplayName("read calls fs.readFile with path")
        fun testRead() = runBlocking {
            coEvery { fs.readFile(any()) } returns "file content"

            val tc = ToolCall(
                domain = "coding",
                method = "read",
                arguments = mutableMapOf("path" to "src/main.kt")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.readFile("src/main.kt") }
            assertEquals("file content", result.value)
        }

        @Test
        @DisplayName("readLines calls fs.readFileLines with defaults")
        fun testReadLinesDefaults() = runBlocking {
            coEvery { fs.readFileLines(any(), any(), any()) } returns "lines 1-10"

            val tc = ToolCall(
                domain = "coding",
                method = "readLines",
                arguments = mutableMapOf("path" to "log.txt")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.readFileLines("log.txt", 1, -1) }
            assertEquals("lines 1-10", result.value)
        }

        @Test
        @DisplayName("readLines calls fs.readFileLines with explicit line range")
        fun testReadLinesWithRange() = runBlocking {
            coEvery { fs.readFileLines(any(), any(), any()) } returns "lines 5-15"

            val tc = ToolCall(
                domain = "coding",
                method = "readLines",
                arguments = mutableMapOf("path" to "log.txt", "startLine" to "5", "endLine" to "15")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.readFileLines("log.txt", 5, 15) }
            assertEquals("lines 5-15", result.value)
        }

        @Test
        @DisplayName("write calls fs.writeFile with path and content")
        fun testWrite() = runBlocking {
            coEvery { fs.writeFile(any(), any()) } returns "written"

            val tc = ToolCall(
                domain = "coding",
                method = "write",
                arguments = mutableMapOf("path" to "out.txt", "content" to "hello world")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.writeFile("out.txt", "hello world") }
            assertEquals("written", result.value)
        }

        @Test
        @DisplayName("append calls fs.appendFile with path and content")
        fun testAppend() = runBlocking {
            coEvery { fs.appendFile(any(), any()) } returns "appended"

            val tc = ToolCall(
                domain = "coding",
                method = "append",
                arguments = mutableMapOf("path" to "log.txt", "content" to "new entry\n")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.appendFile("log.txt", "new entry\n") }
            assertEquals("appended", result.value)
        }

        @Test
        @DisplayName("replace calls fs.replaceInFile with all args")
        fun testReplace() = runBlocking {
            coEvery { fs.replaceInFile(any(), any(), any(), any()) } returns "3 replacements"

            val tc = ToolCall(
                domain = "coding",
                method = "replace",
                arguments = mutableMapOf(
                    "path" to "config.yaml",
                    "oldStr" to "localhost",
                    "newStr" to "prod.example.com",
                    "count" to "1"
                )
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.replaceInFile("config.yaml", "localhost", "prod.example.com", 1) }
            assertEquals("3 replacements", result.value)
        }

        @Test
        @DisplayName("replace defaults count to -1 (replace all)")
        fun testReplaceDefaultCount() = runBlocking {
            coEvery { fs.replaceInFile(any(), any(), any(), any()) } returns "all replaced"

            val tc = ToolCall(
                domain = "coding",
                method = "replace",
                arguments = mutableMapOf(
                    "path" to "file.txt",
                    "oldStr" to "foo",
                    "newStr" to "bar"
                )
            )

            executor.callFunctionOn(tc, target)
            coVerify { fs.replaceInFile("file.txt", "foo", "bar", -1) }
        }

        @Test
        @DisplayName("delete calls fs.delete with path, recursive defaults to false")
        fun testDeleteDefaults() = runBlocking {
            coEvery { fs.delete(any(), any()) } returns "deleted"

            val tc = ToolCall(
                domain = "coding",
                method = "delete",
                arguments = mutableMapOf("path" to "temp.txt")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.delete("temp.txt", false) }
            assertEquals("deleted", result.value)
        }

        @Test
        @DisplayName("delete calls fs.delete with recursive=true")
        fun testDeleteRecursive() = runBlocking {
            coEvery { fs.delete(any(), any()) } returns "deleted recursively"

            val tc = ToolCall(
                domain = "coding",
                method = "delete",
                arguments = mutableMapOf("path" to "build/", "recursive" to "true")
            )

            executor.callFunctionOn(tc, target)
            coVerify { fs.delete("build/", true) }
        }

        @Test
        @DisplayName("mkdir calls fs.mkdir with path")
        fun testMkdir() = runBlocking {
            coEvery { fs.mkdir(any()) } returns "created"

            val tc = ToolCall(
                domain = "coding",
                method = "mkdir",
                arguments = mutableMapOf("path" to "src/main/kotlin")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.mkdir("src/main/kotlin") }
            assertEquals("created", result.value)
        }

        @Test
        @DisplayName("copy calls fs.copy with source and dest")
        fun testCopy() = runBlocking {
            coEvery { fs.copy(any(), any()) } returns "copied"

            val tc = ToolCall(
                domain = "coding",
                method = "copy",
                arguments = mutableMapOf("source" to "a.txt", "dest" to "b.txt")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.copy("a.txt", "b.txt") }
            assertEquals("copied", result.value)
        }

        @Test
        @DisplayName("move calls fs.move with source and dest")
        fun testMove() = runBlocking {
            coEvery { fs.move(any(), any()) } returns "moved"

            val tc = ToolCall(
                domain = "coding",
                method = "move",
                arguments = mutableMapOf("source" to "old.txt", "dest" to "new.txt")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.move("old.txt", "new.txt") }
            assertEquals("moved", result.value)
        }

        @Test
        @DisplayName("listDir calls fs.listDir with defaults")
        fun testListDirDefaults() = runBlocking {
            coEvery { fs.listDir(any(), any()) } returns "4 items"

            val tc = ToolCall(
                domain = "coding",
                method = "listDir",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.listDir(".", 1) }
            assertEquals("4 items", result.value)
        }

        @Test
        @DisplayName("listDir calls fs.listDir with explicit path and maxDepth")
        fun testListDirWithArgs() = runBlocking {
            coEvery { fs.listDir(any(), any()) } returns "12 items"

            val tc = ToolCall(
                domain = "coding",
                method = "listDir",
                arguments = mutableMapOf("path" to "src/", "maxDepth" to "3")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.listDir("src/", 3) }
            assertEquals("12 items", result.value)
        }

        @Test
        @DisplayName("glob calls fs.glob with pattern")
        fun testGlob() = runBlocking {
            coEvery { fs.glob(any()) } returns "5 matches"

            val tc = ToolCall(
                domain = "coding",
                method = "glob",
                arguments = mutableMapOf("pattern" to "src/**/*.kt")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.glob("src/**/*.kt") }
            assertEquals("5 matches", result.value)
        }

        @Test
        @DisplayName("grep calls fs.grep with defaults")
        fun testGrepDefaults() = runBlocking {
            coEvery { fs.grep(any(), any(), any(), any()) } returns "3 matches"

            val tc = ToolCall(
                domain = "coding",
                method = "grep",
                arguments = mutableMapOf("pattern" to "TODO")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.grep("TODO", ".", "*", false) }
            assertEquals("3 matches", result.value)
        }

        @Test
        @DisplayName("grep calls fs.grep with all args")
        fun testGrepWithArgs() = runBlocking {
            coEvery { fs.grep(any(), any(), any(), any()) } returns "1 match"

            val tc = ToolCall(
                domain = "coding",
                method = "grep",
                arguments = mutableMapOf(
                    "pattern" to "class\\s+\\w+",
                    "path" to "src/",
                    "filePattern" to "*.kt",
                    "ignoreCase" to "true"
                )
            )

            executor.callFunctionOn(tc, target)
            coVerify { fs.grep("class\\s+\\w+", "src/", "*.kt", true) }
        }

        @Test
        @DisplayName("diff calls fs.diff with path")
        fun testDiff() = runBlocking {
            coEvery { fs.diff(any()) } returns "--- a/file.txt\n+++ b/file.txt"

            val tc = ToolCall(
                domain = "coding",
                method = "diff",
                arguments = mutableMapOf("path" to "file.txt")
            )

            val result = executor.callFunctionOn(tc, target)
            coVerify { fs.diff("file.txt") }
            assertEquals("--- a/file.txt\n+++ b/file.txt", result.value)
        }

        // --- non-suspend FS methods use every / verify ---

        @Test
        @DisplayName("stat calls fs.fileInfo with path")
        fun testStat() = runBlocking {
            every { fs.fileInfo(any()) } returns "size: 1024 bytes"

            val tc = ToolCall(
                domain = "coding",
                method = "stat",
                arguments = mutableMapOf("path" to "pom.xml")
            )

            val result = executor.callFunctionOn(tc, target)
            verify { fs.fileInfo("pom.xml") }
            assertEquals("size: 1024 bytes", result.value)
        }

        @Test
        @DisplayName("changeSummary calls fs.changeSummary")
        fun testChangeSummary() = runBlocking {
            every { fs.changeSummary() } returns "2 files changed"

            val tc = ToolCall(
                domain = "coding",
                method = "changeSummary",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            verify { fs.changeSummary() }
            assertEquals("2 files changed", result.value)
        }

        @Test
        @DisplayName("languages calls fs.detectLanguages and formats output")
        fun testLanguages() = runBlocking {
            every { fs.detectLanguages() } returns mapOf("Kotlin" to 42, "Rust" to 15, "Java" to 8)

            val tc = ToolCall(
                domain = "coding",
                method = "languages",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            verify { fs.detectLanguages() }
            val value = result.value as String
            assertTrue(value.contains("Kotlin: 42 files"))
            assertTrue(value.contains("Rust: 15 files"))
            assertTrue(value.contains("Java: 8 files"))
        }

        @Test
        @DisplayName("languages returns message when no source files")
        fun testLanguagesEmpty() = runBlocking {
            every { fs.detectLanguages() } returns emptyMap()

            val tc = ToolCall(
                domain = "coding",
                method = "languages",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            assertEquals("No source files detected", result.value)
        }

        @Test
        @DisplayName("workspaceRoot calls fs.getWorkspaceRoot")
        fun testWorkspaceRoot() = runBlocking {
            every { fs.getWorkspaceRoot() } answers { "/home/user/project" }

            val tc = ToolCall(
                domain = "coding",
                method = "workspaceRoot",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            verify { fs.getWorkspaceRoot() }
            assertEquals("/home/user/project", result.value)
        }
    }

    // ==================== Error handling ====================

    @Nested
    @DisplayName("Error handling")
    inner class ErrorHandling {

        @Test
        @DisplayName("wrong domain throws IllegalArgumentException")
        fun testWrongDomain() = runBlocking {
            val tc = ToolCall(
                domain = "wrong",
                method = "shell",
                arguments = mutableMapOf("command" to "echo hello")
            )

            val result = executor.callFunctionOn(tc, target)
            assertNotNull(result.exception)
        }

        @Test
        @DisplayName("blank function name throws IllegalArgumentException")
        fun testBlankFunctionName() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            assertNotNull(result.exception)
        }

        @Test
        @DisplayName("wrong receiver type throws IllegalArgumentException")
        fun testWrongReceiverType() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "shell",
                arguments = mutableMapOf("command" to "echo hello")
            )

            val result = executor.callFunctionOn(tc, "wrong receiver type")
            assertNotNull(result.exception)
        }

        @Test
        @DisplayName("unsupported method returns exception with help")
        fun testUnsupportedMethod() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "nonExistentMethod",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, target)
            assertNotNull(result.exception)
            assertTrue(result.exception?.cause?.message?.contains("Unsupported") == true)
        }

        @Test
        @DisplayName("missing required parameter for shell returns exception")
        fun testMissingRequiredShellParam() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "shell",
                arguments = mutableMapOf() // missing "command"
            )

            val result = executor.callFunctionOn(tc, target)
            assertNotNull(result.exception)
            assertTrue(result.exception?.cause?.message?.contains("command") == true)
        }

        @Test
        @DisplayName("missing required parameter for read returns exception")
        fun testMissingRequiredReadParam() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "read",
                arguments = mutableMapOf() // missing "path"
            )

            val result = executor.callFunctionOn(tc, target)
            assertNotNull(result.exception)
            assertTrue(result.exception?.cause?.message?.contains("path") == true)
        }

        @Test
        @DisplayName("missing required parameter for write returns exception")
        fun testMissingRequiredWriteParam() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "write",
                arguments = mutableMapOf("path" to "out.txt") // missing "content"
            )

            val result = executor.callFunctionOn(tc, target)
            assertNotNull(result.exception)
            assertTrue(result.exception?.cause?.message?.contains("content") == true)
        }

        @Test
        @DisplayName("extraneous parameter returns exception")
        fun testExtraneousParameter() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "read",
                arguments = mutableMapOf("path" to "f.txt", "unknown" to "value")
            )

            val result = executor.callFunctionOn(tc, target)
            assertNotNull(result.exception)
            assertTrue(result.exception?.cause?.message?.contains("Extraneous") == true)
        }
    }

    // ==================== Help methods ====================

    @Nested
    @DisplayName("Help methods")
    inner class HelpMethods {

        @Test
        @DisplayName("help() returns all tool descriptions")
        fun testHelp() {
            val help = executor.help()

            assertNotNull(help)
            assertTrue(help.isNotBlank())
            assertTrue(help.contains("Execute a shell command"))
            assertTrue(help.contains("Read a file's content"))
        }

        @Test
        @DisplayName("help(shell) returns shell tool help")
        fun testHelpShell() {
            val help = executor.help("shell")

            assertNotNull(help)
            assertTrue(help.contains("Execute a shell command"))
        }

        @Test
        @DisplayName("help(read) returns read tool help")
        fun testHelpRead() {
            val help = executor.help("read")

            assertNotNull(help)
            assertTrue(help.contains("Read a file's content"))
        }

        @Test
        @DisplayName("help(write) returns write tool help")
        fun testHelpWrite() {
            val help = executor.help("write")

            assertNotNull(help)
            assertTrue(help.contains("Write content to a file"))
        }

        @Test
        @DisplayName("help for all registered methods is non-blank")
        fun testHelpForAllMethods() {
            val methods = listOf(
                "shell", "shellOutput", "shellStatus", "shellList", "shellSetEnv",
                "toolsDetect", "projectType",
                "read", "readLines", "write", "append", "replace", "delete", "mkdir",
                "copy", "move", "listDir", "glob", "grep", "stat", "diff",
                "changeSummary", "languages", "workspaceRoot"
            )

            methods.forEach { method ->
                val help = executor.help(method)
                assertNotNull(help, "Help for '$method' should not be null")
                assertTrue(help.isNotBlank(), "Help for '$method' should not be blank")
            }
        }

        @Test
        @DisplayName("help(unknownMethod) returns empty string")
        fun testHelpUnknown() {
            assertEquals("", executor.help("unknownMethod"))
        }
    }

    // ==================== Properties ====================

    @Nested
    @DisplayName("Properties")
    inner class Properties {

        @Test
        @DisplayName("domain is 'coding'")
        fun testDomain() {
            assertEquals("coding", executor.domain)
        }

        @Test
        @DisplayName("receiverClass is CodingToolExecutor.Target")
        fun testReceiverClass() {
            assertEquals(CodingToolExecutor.Target::class, executor.receiverClass)
        }

        @Test
        @DisplayName("getToolSpecs returns 45 registered specs")
        fun testToolSpecsCount() {
            val specs = executor.getToolSpecs()
            assertEquals(45, specs.size)
        }

        @Test
        @DisplayName("getToolSpecs contains all expected methods")
        fun testToolSpecsKeys() {
            val specs = executor.getToolSpecs()
            val expectedMethods = setOf(
                "shell", "shellOutput", "shellStatus", "shellList", "shellSetEnv",
                "toolsDetect", "projectType",
                "read", "readLines", "write", "append", "replace", "delete", "mkdir",
                "copy", "move", "listDir", "glob", "grep", "stat", "diff",
                "changeSummary", "languages", "workspaceRoot",
                "scaffold", "validate",
                "replaceRegex", "editLines", "insertAfter", "revert",
                "diagnostics", "symbols", "references", "lspServers",
                "runCode", "runCodeLanguages",
                "mvnBuild", "scaffoldFromExample", "scaffoldFlow", "ktSymbols", "ktReferences", "impact",
                "trapCheck", "devTask", "moduleGraph"
            )
            assertEquals(expectedMethods, specs.keys)
        }

        @Test
        @DisplayName("each toolSpec has non-blank description")
        fun testToolSpecsDescriptions() {
            executor.getToolSpecs().forEach { (method, spec) ->
                assertNotNull(spec.description, "Description for '$method' should not be null")
                assertTrue(spec.description?.isNotBlank() == true, "Description for '$method' should not be blank")
            }
        }
    }

    // ==================== Governance tools (trapCheck, repo-consistency) ====================

    @Nested
    @DisplayName("Governance tools")
    inner class GovernanceTools {

        @TempDir
        lateinit var tempDir: java.nio.file.Path

        @Test
        @DisplayName("trapCheck flags CDP pitfalls in driver code")
        fun testTrapCheckFlags() = runBlocking {
            coEvery { fs.readFile(any()) } returns
                "session.send(Input.dispatchMouseEvent, mapOf(\"type\" to \"mouseWheel\"))"

            val tc = ToolCall(
                domain = "coding",
                method = "trapCheck",
                arguments = mutableMapOf("path" to "PulsarWebDriver.kt")
            )

            val result = executor.callFunctionOn(tc, target)
            val value = result.value as String
            assertTrue(value.contains("[crbug-444929150]"), value)
            coVerify { fs.readFile("PulsarWebDriver.kt") }
        }

        @Test
        @DisplayName("trapCheck passes through file read errors")
        fun testTrapCheckReadError() = runBlocking {
            coEvery { fs.readFile(any()) } returns "Error: File not found: missing.kt"

            val tc = ToolCall(
                domain = "coding",
                method = "trapCheck",
                arguments = mutableMapOf("path" to "missing.kt")
            )

            val result = executor.callFunctionOn(tc, target)
            assertEquals("Error: File not found: missing.kt", result.value)
        }

        @Test
        @DisplayName("validate repo-consistency passes for a consistent workspace")
        fun testValidateRepoConsistencyPass() = runBlocking {
            val root = tempDir
            java.nio.file.Files.writeString(root.resolve("VERSION"), "4.13.4-SNAPSHOT\n")
            java.nio.file.Files.writeString(root.resolve("pom.xml"), rootPomXml)
            java.nio.file.Files.createDirectories(root.resolve("browser4-dependencies"))
            java.nio.file.Files.writeString(root.resolve("browser4-dependencies/pom.xml"), bomPomXml)
            for (m in listOf("browser4-core", "browser4-rest")) {
                java.nio.file.Files.createDirectories(root.resolve(m))
                java.nio.file.Files.writeString(root.resolve("$m/pom.xml"), "<project/>\n")
            }

            val realFs = CodingAgentFileSystem(root)
            val realTarget = CodingToolExecutor.Target(shell, realFs)
            val tc = ToolCall(
                domain = "coding",
                method = "validate",
                arguments = mutableMapOf("type" to "repo-consistency")
            )

            val result = executor.callFunctionOn(tc, realTarget)
            val value = result.value as String
            assertTrue(value.contains("All checks passed"), value)
        }

        @Test
        @DisplayName("validate repo-consistency reports version drift")
        fun testValidateRepoConsistencyDrift() = runBlocking {
            val root = tempDir
            java.nio.file.Files.writeString(root.resolve("VERSION"), "4.13.4-SNAPSHOT\n")
            java.nio.file.Files.writeString(
                root.resolve("pom.xml"), rootPomXml.replace("4.13.4-SNAPSHOT", "4.99.0"))
            java.nio.file.Files.createDirectories(root.resolve("browser4-dependencies"))
            java.nio.file.Files.writeString(root.resolve("browser4-dependencies/pom.xml"), bomPomXml)
            for (m in listOf("browser4-core", "browser4-rest")) {
                java.nio.file.Files.createDirectories(root.resolve(m))
                java.nio.file.Files.writeString(root.resolve("$m/pom.xml"), "<project/>\n")
            }

            val realFs = CodingAgentFileSystem(root)
            val realTarget = CodingToolExecutor.Target(shell, realFs)
            val tc = ToolCall(
                domain = "coding",
                method = "validate",
                arguments = mutableMapOf("type" to "repo-consistency")
            )

            val result = executor.callFunctionOn(tc, realTarget)
            val value = result.value as String
            assertTrue(value.contains("Root pom version"), value)
            assertTrue(value.contains("does not match VERSION"), value)
        }

        @Test
        @DisplayName("devTask renders an executable plan from a task description")
        fun testDevTaskPlan() = runBlocking {
            val tc = ToolCall(
                domain = "coding",
                method = "devTask",
                arguments = mutableMapOf(
                    "task" to "fix the mouseWheel race in PulsarWebDriver.kt " +
                        "under browser4-core/browser4-browser/src/main/kotlin")
            )

            val result = executor.callFunctionOn(tc, target)
            val value = result.value as String
            assertTrue(value.contains("Dev task plan"), value)
            assertTrue(value.contains("coding.trapCheck"), value)
            assertTrue(value.contains("coding.mvnBuild"), value)
            assertTrue(value.contains("repo-consistency"), value)
            assertFalse(value.contains("Verification"), "verify=false must not run checks")
        }

        @Test
        @DisplayName("devTask with verify runs compile and repo-consistency checks")
        fun testDevTaskVerify() = runBlocking {
            val root = tempDir
            java.nio.file.Files.writeString(root.resolve("VERSION"), "4.13.4-SNAPSHOT\n")
            java.nio.file.Files.writeString(root.resolve("pom.xml"), rootPomXml)
            java.nio.file.Files.createDirectories(root.resolve("browser4-dependencies"))
            java.nio.file.Files.writeString(root.resolve("browser4-dependencies/pom.xml"), bomPomXml)
            for (m in listOf("browser4-core", "browser4-rest")) {
                java.nio.file.Files.createDirectories(root.resolve(m))
                java.nio.file.Files.writeString(root.resolve("$m/pom.xml"), "<project/>\n")
            }
            coEvery { shell.executeRaw(any(), any(), any()) } returns
                ShellResult("s1", "mvn -pl browser4-rest -am compile -DskipTests -q", 0, "compiled", "", 100)

            val realFs = CodingAgentFileSystem(root)
            val realTarget = CodingToolExecutor.Target(shell, realFs)
            val tc = ToolCall(
                domain = "coding",
                method = "devTask",
                arguments = mutableMapOf(
                    "task" to "change the controller in browser4-rest/src/main/kotlin/XController.kt",
                    "verify" to "true")
            )

            val result = executor.callFunctionOn(tc, realTarget)
            val value = result.value as String
            assertTrue(value.contains("Verification"), value)
            assertTrue(value.contains("mvnBuild compile of browser4-rest"), value)
            assertTrue(value.contains("All checks passed"), value)
            assertFalse(value.contains("tests on"), "runTests=false must not run tests")
        }

        @Test
        @DisplayName("devTask with runTests executes the module test suite")
        fun testDevTaskRunTests() = runBlocking {
            val root = tempDir
            java.nio.file.Files.writeString(root.resolve("VERSION"), "4.13.4-SNAPSHOT\n")
            java.nio.file.Files.writeString(root.resolve("pom.xml"), rootPomXml)
            java.nio.file.Files.createDirectories(root.resolve("browser4-dependencies"))
            java.nio.file.Files.writeString(root.resolve("browser4-dependencies/pom.xml"), bomPomXml)
            for (m in listOf("browser4-core", "browser4-rest")) {
                java.nio.file.Files.createDirectories(root.resolve(m))
                java.nio.file.Files.writeString(root.resolve("$m/pom.xml"), "<project/>\n")
            }
            coEvery { shell.executeRaw(any(), any(), any()) } answers {
                val cmd = firstArg<String>()
                ShellResult("s1", cmd, 0,
                    if (cmd.contains("mvn test")) "Tests run: 12, Failures: 0" else "compiled", "", 100)
            }

            val realFs = CodingAgentFileSystem(root)
            val realTarget = CodingToolExecutor.Target(shell, realFs)
            val tc = ToolCall(
                domain = "coding",
                method = "devTask",
                arguments = mutableMapOf(
                    "task" to "change the controller in browser4-rest/src/main/kotlin/XController.kt",
                    "verify" to "true",
                    "runTests" to "true")
            )

            val result = executor.callFunctionOn(tc, realTarget)
            val value = result.value as String
            assertTrue(value.contains("tests on browser4-rest"), value)
            assertTrue(value.contains("Tests run: 12"), value)
        }

        @Test
        @DisplayName("scaffoldFromExample with a directory extracts a multi-file skeleton")
        fun testScaffoldFromExampleDir() = runBlocking {
            val root = tempDir
            val pluginDir = root.resolve("browser4-plugins/browser4-seo")
            java.nio.file.Files.createDirectories(pluginDir.resolve("src/main/kotlin/a/b/tools"))
            java.nio.file.Files.createDirectories(pluginDir.resolve("src/main/kotlin/a/b/config"))
            java.nio.file.Files.writeString(pluginDir.resolve("pom.xml"),
                "<project>\n  <artifactId>browser4-seo</artifactId>\n</project>\n")
            java.nio.file.Files.writeString(
                pluginDir.resolve("src/main/kotlin/a/b/tools/SeoToolExecutor.kt"),
                "package a.b.tools\n\nopen class SeoToolExecutor {\n    override val domain = \"seo\"\n}\n")
            java.nio.file.Files.writeString(
                pluginDir.resolve("src/main/kotlin/a/b/config/SeoAutoConfiguration.kt"),
                "package a.b.config\n\nimport a.b.tools.SeoToolExecutor\n\n" +
                    "open class SeoAutoConfiguration {\n    fun executors() = listOf(SeoToolExecutor())\n}\n")

            val realFs = CodingAgentFileSystem(root)
            val realTarget = CodingToolExecutor.Target(shell, realFs)
            val tc = ToolCall(
                domain = "coding",
                method = "scaffoldFromExample",
                arguments = mutableMapOf(
                    "path" to "browser4-plugins/browser4-seo",
                    "className" to "WeatherToolExecutor",
                    "basePackage" to "a.b")
            )

            val result = executor.callFunctionOn(tc, realTarget)
            val value = result.value as String
            assertTrue(value.contains("Multi-file skeleton generated"), value)
            assertTrue(value.contains("=== File: pom.xml ==="), value)
            assertTrue(value.contains("<artifactId>"), value)
            assertTrue(value.contains("open class WeatherToolExecutor"), value)
            assertTrue(value.contains("listOf(WeatherToolExecutor())"),
                "cross-file reference must follow the rename: $value")
        }

        @Test
        @DisplayName("moduleGraph reports the live pom graph and drift")
        fun testModuleGraph() = runBlocking {
            val root = tempDir
            java.nio.file.Files.writeString(root.resolve("pom.xml"),
                "<project>\n  <artifactId>browser4</artifactId>\n  <packaging>pom</packaging>\n</project>\n")
            java.nio.file.Files.createDirectories(root.resolve("browser4-coding"))
            java.nio.file.Files.writeString(root.resolve("browser4-coding/pom.xml"),
                "<project>\n  <artifactId>browser4-coding</artifactId>\n</project>\n")
            java.nio.file.Files.createDirectories(root.resolve("browser4-agentic"))
            java.nio.file.Files.writeString(root.resolve("browser4-agentic/pom.xml"),
                "<project>\n  <artifactId>browser4-agentic</artifactId>\n  <dependencies>\n" +
                    "    <dependency>\n      <groupId>ai.platon.pulsar</groupId>\n      <artifactId>browser4-coding</artifactId>\n" +
                    "    </dependency>\n  </dependencies>\n</project>\n")

            val realFs = CodingAgentFileSystem(root)
            val realTarget = CodingToolExecutor.Target(shell, realFs)
            val tc = ToolCall(
                domain = "coding",
                method = "moduleGraph",
                arguments = mutableMapOf("module" to "browser4-coding")
            )

            val result = executor.callFunctionOn(tc, realTarget)
            val value = result.value as String
            assertTrue(value.contains("Module: browser4-coding"), value)
            assertTrue(value.contains("browser4-agentic"), "dependent must be reported: $value")
            assertTrue(value.contains("affects (transitively)"), value)
        }

        @Test
        @DisplayName("moduleGraph without module lists the whole graph")
        fun testModuleGraphAll() = runBlocking {
            val root = tempDir
            java.nio.file.Files.createDirectories(root.resolve("browser4-coding"))
            java.nio.file.Files.writeString(root.resolve("browser4-coding/pom.xml"),
                "<project>\n  <artifactId>browser4-coding</artifactId>\n</project>\n")

            val realFs = CodingAgentFileSystem(root)
            val realTarget = CodingToolExecutor.Target(shell, realFs)
            val tc = ToolCall(
                domain = "coding",
                method = "moduleGraph",
                arguments = mutableMapOf()
            )

            val result = executor.callFunctionOn(tc, realTarget)
            val value = result.value as String
            assertTrue(value.contains("Module graph: 1 modules"), value)
            assertTrue(value.contains("browser4-coding"), value)
        }

        private val rootPomXml = """
            <project>
                <artifactId>browser4</artifactId>
                <version>4.13.4-SNAPSHOT</version>
                <packaging>pom</packaging>
                <modules>
                    <module>browser4-dependencies</module>
                    <module>browser4-core</module>
                    <module>browser4-rest</module>
                </modules>
            </project>
        """.trimIndent()

        private val bomPomXml = """
            <project>
                <artifactId>browser4-dependencies</artifactId>
                <version>4.13.4-SNAPSHOT</version>
                <packaging>pom</packaging>
            </project>
        """.trimIndent()
    }
}



