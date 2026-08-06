package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.agentic.model.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ToolCallPermissionAnalyzer")
class ToolCallPermissionAnalyzerTest {

    private val analyzer = ToolCallPermissionAnalyzer()

    @Nested
    @DisplayName("command extraction")
    inner class CommandExtraction {

        @Test
        @DisplayName("extracts command from coding.shell")
        fun codingShell() {
            val tc = ToolCall("coding", "shell", mutableMapOf("command" to "git push"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertEquals("git push", req.command)
            assertEquals(ActionClass.GIT, req.actionClass)
        }

        @Test
        @DisplayName("extracts command from cli.run")
        fun cliRun() {
            val tc = ToolCall("cli", "run", mutableMapOf("command" to "browser4-cli navigate --url https://example.com"))
            val req = analyzer.analyze(tc, "cli", "agent-1", "session-1")
            assertEquals("browser4-cli navigate --url https://example.com", req.command)
        }

        @Test
        @DisplayName("no command for non-shell coding methods")
        fun codingReadNoCommand() {
            val tc = ToolCall("coding", "read", mutableMapOf("path" to "src/main.kt"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertNull(req.command)
            assertEquals(ActionClass.READ, req.actionClass)
        }
    }

    @Nested
    @DisplayName("path extraction")
    inner class PathExtraction {

        @Test
        @DisplayName("extracts path from coding.read")
        fun codingRead() {
            val tc = ToolCall("coding", "read", mutableMapOf("path" to "src/main.kt"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertEquals("src/main.kt", req.path)
        }

        @Test
        @DisplayName("extracts path from coding.write")
        fun codingWrite() {
            val tc = ToolCall("coding", "write", mutableMapOf("path" to "output.txt"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertEquals("output.txt", req.path)
            assertEquals(ActionClass.WRITE, req.actionClass)
        }

        @Test
        @DisplayName("extracts fullFileName from fs.writeString")
        fun fsWriteString() {
            val tc = ToolCall("fs", "writeString", mutableMapOf("fullFileName" to "/tmp/test.txt"))
            val req = analyzer.analyze(tc, "fs", "agent-1", "session-1")
            assertEquals("/tmp/test.txt", req.path)
            assertEquals(ActionClass.WRITE, req.actionClass)
        }

        @Test
        @DisplayName("external path flagged as EXTERNAL_ACCESS for read")
        fun externalPath() {
            val tc = ToolCall("coding", "read", mutableMapOf("path" to "/etc/passwd"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertEquals("/etc/passwd", req.path)
            assertEquals(ActionClass.EXTERNAL_ACCESS, req.actionClass)
        }

        @Test
        @DisplayName("parent-dir traversal flagged as external")
        fun parentDirTraversal() {
            val tc = ToolCall("coding", "read", mutableMapOf("path" to "../outside/file.txt"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertEquals(ActionClass.EXTERNAL_ACCESS, req.actionClass)
        }

        @Test
        @DisplayName("workspace-relative path is NOT external")
        fun workspaceRelative() {
            val tc = ToolCall("coding", "read", mutableMapOf("path" to "src/main.kt"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertEquals("src/main.kt", req.path)
            assertEquals(ActionClass.READ, req.actionClass)   // not overridden to EXTERNAL_ACCESS
        }
    }

    @Nested
    @DisplayName("url extraction")
    inner class UrlExtraction {

        @Test
        @DisplayName("extracts url from tab.navigate")
        fun tabNavigate() {
            val tc = ToolCall("tab", "navigate", mutableMapOf("url" to "https://example.com"))
            val req = analyzer.analyze(tc, "tab", "agent-1", "session-1")
            assertEquals("https://example.com", req.url)
            assertEquals(ActionClass.NAVIGATE, req.actionClass)
        }

        @Test
        @DisplayName("extracts rawUrl as fallback")
        fun tabNavigateRawUrl() {
            val tc = ToolCall("tab", "open", mutableMapOf("rawUrl" to "https://other.com"))
            val req = analyzer.analyze(tc, "tab", "agent-1", "session-1")
            assertEquals("https://other.com", req.url)
        }

        @Test
        @DisplayName("no url for non-navigation tab methods")
        fun tabClickNoUrl() {
            val tc = ToolCall("tab", "click", mutableMapOf("selector" to "#btn"))
            val req = analyzer.analyze(tc, "tab", "agent-1", "session-1")
            assertNull(req.url)
        }
    }

    @Nested
    @DisplayName("script extraction")
    inner class ScriptExtraction {

        @Test
        @DisplayName("extracts script from tab.eval")
        fun tabEval() {
            val tc = ToolCall("tab", "eval", mutableMapOf("expression" to "document.title"))
            val req = analyzer.analyze(tc, "tab", "agent-1", "session-1")
            assertEquals("document.title", req.script)
            assertEquals(ActionClass.WRITE, req.actionClass)
        }

        @Test
        @DisplayName("falls back to functionDeclaration")
        fun tabEvalFallback() {
            val tc = ToolCall("tab", "evaluateValue", mutableMapOf("functionDeclaration" to "() => 42"))
            val req = analyzer.analyze(tc, "tab", "agent-1", "session-1")
            assertEquals("() => 42", req.script)
        }
    }

    @Nested
    @DisplayName("message building")
    inner class MessageBuilding {

        @Test
        @DisplayName("message includes domain.method")
        fun basicMessage() {
            val tc = ToolCall("tab", "title", mutableMapOf())
            val req = analyzer.analyze(tc, "tab", "agent-1", "session-1")
            assertTrue(req.message.contains("tab.title"))
        }

        @Test
        @DisplayName("message includes action class suffix")
        fun messageWithActionClass() {
            val tc = ToolCall("tab", "type", mutableMapOf("text" to "hello"))
            val req = analyzer.analyze(tc, "tab", "agent-1", "session-1")
            assertTrue(req.message.contains("[write]"))
        }

        @Test
        @DisplayName("message includes command snippet")
        fun messageWithCommand() {
            val tc = ToolCall("coding", "shell", mutableMapOf("command" to "git push origin main"))
            val req = analyzer.analyze(tc, "coding", "agent-1", "session-1")
            assertTrue(req.message.contains("git push"))
        }
    }
}
