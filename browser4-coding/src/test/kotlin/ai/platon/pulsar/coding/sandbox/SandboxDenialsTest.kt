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

    /**
     * `permission-e2e` A06 on `windows-low-il` (2026-11-20): PowerShell wraps the refusal in
     * `UnauthorizedAccessException`, so the only evidence is
     * `Access to the path 'C:\…\escape-A06.txt' is denied.` — none of the older ASCII markers matched
     * (`access` is not adjacent to `is denied`), and the raw English sentence reached the model.
     */
    @Test
    @DisplayName("the .NET UnauthorizedAccessException text is translated")
    fun dotNetDenialIsTranslated() {
        val evidence = """
            PROBE_DENIED: Exception calling "WriteAllText" with "2" argument(s):
            "Access to the path 'C:\Users\pereg\b4-perm-e2e-canary\run\escape-A06.txt' is denied."
        """.trimIndent()

        assertTrue(SandboxDenials.isDenial(evidence), "the .NET phrasing must be a denial marker")

        val envelope = SandboxDenials.envelope(
            tool = "coding.shell",
            command = "pwsh -NoProfile -File C:\\…\\escape-A06.ps1",
            evidence = evidence,
            sandboxId = "windows-low-il",
        )
        assertTrue(envelope != null, "a .NET denial under an active sandbox must be translated")
        assertTrue(envelope!!.startsWith("PERMISSION_DENIED"), envelope)
        assertTrue(envelope.contains("code=SANDBOX_DENIED"), envelope)
        assertFalse(envelope.contains("is denied"), "the raw .NET text stays in the log: $envelope")
    }

    @Test
    @DisplayName("the Win32 HRESULT form of the same refusal is translated")
    fun hresultDenialIsTranslated() {
        val envelope = SandboxDenials.envelope(
            tool = "coding.shell",
            command = "cmd /c echo x",
            evidence = "Exception 0x80070005 (E_ACCESSDENIED) while opening the file",
            sandboxId = "windows-acl",
        )
        assertTrue(envelope != null, "0x80070005 is an access denial")
        assertTrue(envelope!!.contains("code=SANDBOX_DENIED"), envelope)
    }

    /**
     * Run `4125acaa` (2026-11-19): a Chinese Windows child reported `拒绝访问。` — the ASCII-only
     * marker list matched nothing, so the raw text reached the model and the sandbox looked like a
     * broken tool instead of a boundary.
     */
    @Test
    @DisplayName("the localized (zh-CN) denial is translated as well")
    fun localizedDenialIsTranslated() {
        val envelope = SandboxDenials.envelope(
            tool = "coding.shell",
            command = "browser4-cli --version",
            evidence = "拒绝访问。",
            sandboxId = "windows-low-il",
        )

        assertTrue(envelope != null, "a localized denial under a task backend must be translated")
        assertTrue(envelope!!.contains("code=SANDBOX_DENIED"), envelope)
        assertFalse(envelope.contains("拒绝访问"), "the raw OS text stays out of the model-facing text: $envelope")
        assertTrue(
            envelope.contains("S-1-15-2"),
            "the low-integrity refusal must name the stale container ACE as a cause: $envelope",
        )
    }

    @Test
    @DisplayName("the task's own backend is enough: the process-wide property may still read off")
    fun taskBackendEnablesTranslation() = withSandbox("off") {
        val envelope = SandboxDenials.envelope("coding.shell", "npm -v", "Access is denied.", "windows-acl")

        assertTrue(envelope != null, "the caller's backend decides, not the property")
        assertTrue(envelope!!.contains("windows-acl"), envelope)
        assertFalse(envelope.contains("S-1-15-2"), "the container-ACE note belongs to windows-low-il only: $envelope")
    }

    @Test
    @DisplayName("an explicit off backend stays untranslated, even under a configured property")
    fun explicitOffIsNotTranslated() = withSandbox("windows-acl") {
        assertNull(
            SandboxDenials.envelope("coding.shell", "echo x", "Access is denied.", "off"),
            "when the task has no boundary, an access denial is an ordinary OS error",
        )
    }
}
