package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The syntax family must stay distinguishable from the policy family (`PermissionAudit`).
 *
 * Why this matters: a `PERMISSION_DENIED | code=TOOL_DENIED` refusal is a dead end the operator
 * may widen, while `UNSUPPORTED_SYNTAX | code=SYNTAX_NOT_SUPPORTED` is a grammar the shell does
 * not have. A live run (2026-10-28) was told the first when the second was true, and answered by
 * writing a Python script — plus `coding.permissions` counted policy friction that did not exist.
 */
@DisplayName("UNSUPPORTED_SYNTAX: the syntax family is not a policy refusal")
class UnsupportedSyntaxTest {

    @BeforeEach
    fun reset() {
        UnsupportedSyntax.reset()
        PermissionAudit.reset()
    }

    @Test
    @DisplayName("the envelope keeps the shared grammar but never borrows a policy code")
    fun testEnvelopeShape() {
        val text = UnsupportedSyntax.format(
            tool = "coding.shell",
            detail = "unsupported syntax: 'if' is cmd.exe flow control",
        )

        assertTrue(text.startsWith(UnsupportedSyntax.PREFIX), text)
        assertTrue(text.contains("| code=${UnsupportedSyntax.CODE}"), text)
        assertTrue(text.contains("| tool=coding.shell"), text)
        assertTrue(text.contains("| detail=unsupported syntax: 'if'"), text)
        assertFalse(text.contains(PermissionAudit.DENIED), "must not read as a policy refusal: $text")
        assertFalse(
            UnsupportedSyntax.CODE in listOf(
                PermissionAudit.CODE_TOOL_DENIED,
                PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
                PermissionAudit.CODE_EXEC_DISABLED,
            ),
            "a syntax code must never collide with a policy code",
        )
    }

    @Test
    @DisplayName("the default hint names routes that exist, including the script-by-path form")
    fun testDefaultHint() {
        val text = UnsupportedSyntax.format(tool = "coding.shell", detail = "x")

        assertTrue(text.contains("| hint="), text)
        assertTrue(text.contains("coding.stat"), "the tool route: $text")
        assertTrue(text.contains(".\\run.cmd"), "the Windows script route: $text")
        assertTrue(text.contains("./run.sh"), "the POSIX script route: $text")
        // The model once copied a bare `run.cmd` from a hint and was refused again (the validator
        // reads a bare name as a program), so the path form is the one that must be advertised.
        assertFalse(text.contains("cmd /c run.cmd"), "a bare filename is not a working route: $text")
    }

    @Test
    @DisplayName("a refusal is counted apart from the policy counters")
    fun testCountersStaySeparate() {
        UnsupportedSyntax.refused("coding.shell", "unsupported syntax: 'if'")
        UnsupportedSyntax.refused("coding.shell", "unsupported syntax: 'for'")

        assertEquals(2, UnsupportedSyntax.snapshot()["coding.shell"])
        assertTrue(
            PermissionAudit.snapshot().isEmpty(),
            "policy counters must stay untouched: ${PermissionAudit.snapshot()}",
        )
    }
}
