package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.agentic.model.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PermissionManager")
class PermissionManagerTest {

    // ---- Disabled manager ----

    @Nested
    @DisplayName("disabled manager")
    inner class DisabledManager {

        @Test
        @DisplayName("always returns Allowed")
        fun alwaysAllowed() = runBlocking {
            val manager = PermissionManager.disabled("agent-1", "session-1")
            val tc = ToolCall("coding", "delete", mutableMapOf("path" to "/etc/passwd"))
            val decision = manager.check(tc, "coding")
            assertTrue(decision is PermissionDecision.Allowed)
            assertFalse(manager.isEnabled)
        }

        @Test
        @DisplayName("explain shows disabled state")
        fun explainDisabled() {
            val manager = PermissionManager.disabled("agent-1", "session-1")
            val tc = ToolCall("tab", "click")
            val explanation = manager.explain(tc, "tab")
            assertTrue(explanation.contains("disabled"))
        }
    }

    // ---- Enabled manager ----

    @Nested
    @DisplayName("enabled manager")
    inner class EnabledManager {

        @Test
        @DisplayName("DENY rule throws PermissionDeniedException")
        fun denyThrows() = runBlocking {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("no-delete", "coding", "delete", PermissionMode.DENY)
            ))
            val manager = PermissionManager.create(policy, "agent-1", "session-1")
            val tc = ToolCall("coding", "delete", mutableMapOf("path" to "file.txt"))

            try {
                manager.check(tc, "coding")
                fail("Expected PermissionDeniedException")
            } catch (e: PermissionDeniedException) {
                assertTrue(e.decision.reason.contains("no-delete"))
            }
        }

        @Test
        @DisplayName("ALLOW rule returns Allowed")
        fun allowReturns() = runBlocking {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("allow-read", "tab", "title", PermissionMode.ALLOW)
            ))
            val manager = PermissionManager.create(policy, "agent-1", "session-1")
            val tc = ToolCall("tab", "title")
            val decision = manager.check(tc, "tab")
            assertTrue(decision is PermissionDecision.Allowed)
        }

        @Test
        @DisplayName("ASK without handler throws PermissionRequestedException")
        fun askWithoutHandler() = runBlocking {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("ask-navigate", "tab", "navigate", PermissionMode.ASK)
            ))
            val manager = PermissionManager.create(policy, "agent-1", "session-1")
            val tc = ToolCall("tab", "navigate", mutableMapOf("url" to "https://example.com"))

            try {
                manager.check(tc, "tab")
                fail("Expected PermissionRequestedException")
            } catch (e: PermissionRequestedException) {
                assertEquals("tab", e.request.domain)
                assertEquals("navigate", e.request.method)
            }
        }
    }

    // ---- Ask handler ----

    @Nested
    @DisplayName("ask handler")
    inner class AskHandler {

        @Test
        @DisplayName("handler granting returns Allowed")
        fun handlerGrants() = runBlocking {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("ask-write", "fs", "writeString", PermissionMode.ASK)
            ))
            val handler = PermissionAskHandler { PermissionResponse(true, "ok") }
            val manager = PermissionManager.create(policy, "agent-1", "session-1", handler)
            val tc = ToolCall("fs", "writeString", mutableMapOf("fullFileName" to "test.txt"))

            val decision = manager.check(tc, "fs")
            assertTrue(decision is PermissionDecision.Allowed)
            assertTrue((decision as PermissionDecision.Allowed).reason.contains("granted"))
        }

        @Test
        @DisplayName("handler denying throws PermissionDeniedException")
        fun handlerDenies() = runBlocking {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("ask-write", "fs", "writeString", PermissionMode.ASK)
            ))
            val handler = PermissionAskHandler { PermissionResponse(false, "not now") }
            val manager = PermissionManager.create(policy, "agent-1", "session-1", handler)
            val tc = ToolCall("fs", "writeString", mutableMapOf("fullFileName" to "test.txt"))

            try {
                manager.check(tc, "fs")
                fail("Expected PermissionDeniedException")
            } catch (e: PermissionDeniedException) {
                assertTrue(e.decision.reason.contains("denied by user"))
                assertTrue(e.decision.reason.contains("not now"))
            }
        }

        @Test
        @DisplayName("withAskHandler creates copy with new handler")
        fun withAskHandler() = runBlocking {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("ask-write", "fs", "writeString", PermissionMode.ASK)
            ))
            val manager = PermissionManager.create(policy, "agent-1", "session-1")
            val withHandler = manager.withAskHandler { PermissionResponse(true) }
            val tc = ToolCall("fs", "writeString", mutableMapOf("fullFileName" to "test.txt"))

            // Original should throw (no handler)
            try {
                manager.check(tc, "fs")
                fail("Expected PermissionRequestedException")
            } catch (_: PermissionRequestedException) { }

            // Copy with handler should succeed
            val decision = withHandler.check(tc, "fs")
            assertTrue(decision is PermissionDecision.Allowed)
        }
    }

    // ---- Policy management ----

    @Nested
    @DisplayName("policy lifecycle")
    inner class PolicyLifecycle {

        @Test
        @DisplayName("installPolicy enables a disabled manager")
        fun installEnables() = runBlocking {
            val manager = PermissionManager.disabled("agent-1", "session-1")
            assertFalse(manager.isEnabled)

            manager.installPolicy(PermissionPolicy(rules = listOf(
                PermissionRule("no-read", "coding", "read", PermissionMode.DENY)
            )))
            assertTrue(manager.isEnabled)

            val tc = ToolCall("coding", "read", mutableMapOf("path" to "secret.txt"))
            try {
                manager.check(tc, "coding")
                fail("Expected exception")
            } catch (_: PermissionDeniedException) { }
        }

        @Test
        @DisplayName("uninstallPolicy returns to disabled state")
        fun uninstallDisabled() = runBlocking {
            val manager = PermissionManager.disabled("agent-1", "session-1")
            manager.installPolicy(PermissionPolicy(rules = listOf(
                PermissionRule("no-read", "coding", "read", PermissionMode.DENY)
            )))
            manager.uninstallPolicy()
            assertFalse(manager.isEnabled)

            val tc = ToolCall("coding", "read", mutableMapOf("path" to "secret.txt"))
            assertTrue(manager.check(tc, "coding") is PermissionDecision.Allowed)
        }
    }
}
