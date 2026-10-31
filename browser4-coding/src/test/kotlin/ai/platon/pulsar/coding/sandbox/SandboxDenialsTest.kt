package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.PermissionAudit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Plan P3-3 — the raw OS denial must not reach the model.
 *
 * The envelope is the same structured shape the string layer uses (`PERMISSION_DENIED | code=… |
 * needed=…`); the platform's own text stays in the log for the audit trail.
 */
@DisplayName("sandbox denial translation")
class SandboxDenialsTest {

    private fun <T> withSandbox(id: String, block: () -> T): T {
        val previous = System.getProperty(ExecutionSandboxes.PROP_SANDBOX)
        System.setProperty(ExecutionSandboxes.PROP_SANDBOX, id)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(ExecutionSandboxes.PROP_SANDBOX)
            else System.setProperty(ExecutionSandboxes.PROP_SANDBOX, previous)
        }
    }

    @Test
    @DisplayName("an OS denial under an active sandbox becomes a structured refusal")
    fun denialBecomesStructured() = withSandbox("windows-acl") {
        val envelope = SandboxDenials.envelope("coding.shell", "echo x > C:\\outside.txt", "Access is denied.")

        assertTrue(envelope != null, "a denial under an active sandbox must be translated")
        assertTrue(envelope!!.startsWith("PERMISSION_DENIED"), envelope)
        assertTrue(envelope.contains("code=SANDBOX_DENIED"), envelope)
        assertTrue(envelope.contains("tool=coding.shell"), envelope)
        assertTrue(envelope.contains("needed="), envelope)
        assertFalse(envelope.contains("Access is denied"), "the raw OS text must stay out of the model-facing text: $envelope")
    }

    @Test
    @DisplayName("off leaves the OS text alone: there is no boundary to attribute it to")
    fun offIsNotTranslated() = withSandbox("off") {
        assertNull(SandboxDenials.envelope("coding.shell", "echo x", "Access is denied."))
    }

    @Test
    @DisplayName("a normal build failure under an active sandbox is not rewritten")
    fun ordinaryFailureIsNotTranslated() = withSandbox("windows-acl") {
        assertNull(SandboxDenials.envelope("coding.shell", "mvn test", "error: cannot find symbol"))
    }

    @Test
    @DisplayName("the denial is counted as SANDBOX_DENIED in the audit snapshot")
    fun denialIsAudited() = withSandbox("windows-acl") {
        val before = PermissionAudit.snapshot()
        SandboxDenials.envelope("coding.shell", "echo x", "Permission denied")
        val after = PermissionAudit.snapshot()

        val key = "coding.shell:${PermissionAudit.CODE_SANDBOX_DENIED}"
        val events = (after[key] ?: 0) - (before[key] ?: 0)
        assertEquals(1, events, "one audit event per translated denial")
    }

    @Test
    @DisplayName("POSIX and Windows denial markers are recognized")
    fun markersAreRecognized() {
        for (text in listOf("Access is denied.", "Permission denied", "Operation not permitted", "EACCES: …")) {
            assertTrue(SandboxDenials.isDenial(text), "must recognize: $text")
        }
        assertFalse(SandboxDenials.isDenial("BUILD SUCCESS"), "a clean build is not a denial")
        assertFalse(SandboxDenials.isDenial("this module manages permissions"), "an ordinary word is not a denial")
    }
}
