package ai.platon.pulsar.agentic.permission

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ToolActionClassifier")
class ToolActionClassifierTest {

    private val classifier = ToolActionClassifier()

    @Nested
    @DisplayName("tab domain")
    inner class TabDomain {

        @Test
        @DisplayName("read actions classified as READ")
        fun readActions() {
            assertEquals(ActionClass.READ, classifier.classify("tab", "title"))
            assertEquals(ActionClass.READ, classifier.classify("tab", "currentUrl"))
            assertEquals(ActionClass.READ, classifier.classify("tab", "textContent"))
            assertEquals(ActionClass.READ, classifier.classify("tab", "screenshot"))
            assertEquals(ActionClass.READ, classifier.classify("tab", "exists"))
        }

        @Test
        @DisplayName("write actions classified as WRITE")
        fun writeActions() {
            assertEquals(ActionClass.WRITE, classifier.classify("tab", "type"))
            assertEquals(ActionClass.WRITE, classifier.classify("tab", "fill"))
            assertEquals(ActionClass.WRITE, classifier.classify("tab", "click"))
            assertEquals(ActionClass.WRITE, classifier.classify("tab", "press"))
        }

        @Test
        @DisplayName("navigate actions classified as NAVIGATE")
        fun navigateActions() {
            assertEquals(ActionClass.NAVIGATE, classifier.classify("tab", "open"))
            assertEquals(ActionClass.NAVIGATE, classifier.classify("tab", "navigate"))
            assertEquals(ActionClass.NAVIGATE, classifier.classify("tab", "goBack"))
            assertEquals(ActionClass.NAVIGATE, classifier.classify("tab", "goForward"))
        }

        @Test
        @DisplayName("eval actions classified as WRITE")
        fun evalActions() {
            assertEquals(ActionClass.WRITE, classifier.classify("tab", "eval"))
            assertEquals(ActionClass.WRITE, classifier.classify("tab", "evaluateValue"))
        }

        @Test
        @DisplayName("destructive actions classified as DESTRUCTIVE")
        fun destructiveActions() {
            assertEquals(ActionClass.DESTRUCTIVE, classifier.classify("tab", "clearBrowserCookies"))
        }

        @Test
        @DisplayName("unknown tab method classified as ANY")
        fun unknownMethod() {
            assertEquals(ActionClass.ANY, classifier.classify("tab", "someNewMethod"))
        }
    }

    @Nested
    @DisplayName("coding domain")
    inner class CodingDomain {

        @Test
        @DisplayName("read file methods classified as READ")
        fun readMethods() {
            assertEquals(ActionClass.READ, classifier.classify("coding", "read"))
            assertEquals(ActionClass.READ, classifier.classify("coding", "readLines"))
            assertEquals(ActionClass.READ, classifier.classify("coding", "glob"))
            assertEquals(ActionClass.READ, classifier.classify("coding", "grep"))
        }

        @Test
        @DisplayName("write file methods classified as WRITE")
        fun writeMethods() {
            assertEquals(ActionClass.WRITE, classifier.classify("coding", "write"))
            assertEquals(ActionClass.WRITE, classifier.classify("coding", "append"))
            assertEquals(ActionClass.WRITE, classifier.classify("coding", "replace"))
            assertEquals(ActionClass.WRITE, classifier.classify("coding", "mkdir"))
        }

        @Test
        @DisplayName("delete classified as DESTRUCTIVE")
        fun deleteMethod() {
            assertEquals(ActionClass.DESTRUCTIVE, classifier.classify("coding", "delete"))
        }

        @Test
        @DisplayName("shell methods classified as ANY (refined by command)")
        fun shellMethods() {
            assertEquals(ActionClass.ANY, classifier.classify("coding", "shell"))
            assertEquals(ActionClass.ANY, classifier.classify("coding", "shellOutput"))
        }
    }

    @Nested
    @DisplayName("fs domain")
    inner class FsDomain {

        @Test
        @DisplayName("read classified as READ")
        fun readMethods() {
            assertEquals(ActionClass.READ, classifier.classify("fs", "readString"))
            assertEquals(ActionClass.READ, classifier.classify("fs", "fileExists"))
        }

        @Test
        @DisplayName("write classified as WRITE")
        fun writeMethods() {
            assertEquals(ActionClass.WRITE, classifier.classify("fs", "writeString"))
            assertEquals(ActionClass.WRITE, classifier.classify("fs", "copyFile"))
        }

        @Test
        @DisplayName("delete classified as DESTRUCTIVE")
        fun deleteFile() {
            assertEquals(ActionClass.DESTRUCTIVE, classifier.classify("fs", "deleteFile"))
        }
    }

    @Nested
    @DisplayName("agent domain")
    inner class AgentDomain {

        @Test
        @DisplayName("observe and extract are READ")
        fun readActions() {
            assertEquals(ActionClass.READ, classifier.classify("agent", "observe"))
            assertEquals(ActionClass.READ, classifier.classify("agent", "extract"))
        }

        @Test
        @DisplayName("act and run are WRITE")
        fun writeActions() {
            assertEquals(ActionClass.WRITE, classifier.classify("agent", "act"))
            assertEquals(ActionClass.WRITE, classifier.classify("agent", "run"))
        }
    }

    @Nested
    @DisplayName("classifyCommand refinement")
    inner class ClassifyCommand {

        @Test
        @DisplayName("git commands classified as GIT")
        fun gitCommands() {
            assertEquals(ActionClass.GIT,
                classifier.classifyCommand("git status", ActionClass.ANY))
            assertEquals(ActionClass.GIT,
                classifier.classifyCommand("git push origin main", ActionClass.ANY))
        }

        @Test
        @DisplayName("npm/mvn commands classified as DEV_TOOL")
        fun devToolCommands() {
            assertEquals(ActionClass.DEV_TOOL,
                classifier.classifyCommand("npm install", ActionClass.ANY))
            assertEquals(ActionClass.DEV_TOOL,
                classifier.classifyCommand("mvn clean compile", ActionClass.ANY))
        }

        @Test
        @DisplayName("rm/chmod commands classified as DESTRUCTIVE")
        fun destructiveCommands() {
            assertEquals(ActionClass.DESTRUCTIVE,
                classifier.classifyCommand("rm -rf build/", ActionClass.ANY))
            assertEquals(ActionClass.DESTRUCTIVE,
                classifier.classifyCommand("chmod 777 file.sh", ActionClass.ANY))
        }

        @Test
        @DisplayName("curl/wget commands classified as WRITE (network)")
        fun networkCommands() {
            assertEquals(ActionClass.WRITE,
                classifier.classifyCommand("curl https://example.com", ActionClass.ANY))
        }

        @Test
        @DisplayName("null or blank command returns baseAction unchanged")
        fun nullCommand() {
            assertEquals(ActionClass.READ,
                classifier.classifyCommand(null, ActionClass.READ))
            assertEquals(ActionClass.ANY,
                classifier.classifyCommand("   ", ActionClass.ANY))
        }
    }

    @Nested
    @DisplayName("unknown domain")
    inner class UnknownDomain {
        @Test
        @DisplayName("returns ANY")
        fun unknownDomain() {
            assertEquals(ActionClass.ANY, classifier.classify("captcha", "solve"))
        }
    }
}
