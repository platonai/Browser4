package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for the shell argument-path guard (plan P0-3): the working directory was already
 * contained, but an argument could still name a credential location.
 *
 * Nothing here spawns a process — [CodingAgentShell.isAllowed] runs the same validation the
 * executor does, so the assertions stay fast and cross-platform.
 */
class CodingAgentShellPathGuardTest {

    @TempDir
    lateinit var workspace: Path

    private fun shell(pathGuardEnabled: Boolean = true): CodingAgentShell =
        CodingAgentShell(baseDir = workspace, pathGuardEnabled = pathGuardEnabled)

    private fun explain(shell: CodingAgentShell, command: String): String = shell.explain(command)

    // ==================== refused ====================

    @Test
    @DisplayName("a tilde-relative credential path is refused")
    fun tildeCredentialRefused() {
        val shell = shell()
        assertFalse(shell.isAllowed("cat ~/.ssh/id_rsa"), explain(shell, "cat ~/.ssh/id_rsa"))
        assertTrue(explain(shell, "cat ~/.ssh/id_rsa").contains("credential"))
    }

    @Test
    @DisplayName("Windows %VAR% credential paths are refused")
    fun windowsVariableRefused() {
        val shell = shell()
        assertFalse(shell.isAllowed("""type %USERPROFILE%\.ssh\id_rsa"""), "%%VAR%% form escaped the guard")
    }

    @Test
    @DisplayName("PowerShell env-prefixed credential paths are refused")
    fun powershellVariableRefused() {
        val shell = shell()
        assertFalse(
            shell.isAllowed("""cat ${'$'}env:USERPROFILE\.ssh\id_rsa"""),
            "PowerShell env-prefixed form escaped the guard",
        )
    }

    @Test
    @DisplayName("POSIX variable credential paths are refused")
    fun posixVariablesRefused() {
        val shell = shell()
        assertFalse(shell.isAllowed("cat \$HOME/.ssh/id_rsa"), "\$VAR form escaped the guard")
        assertFalse(shell.isAllowed("cat \${HOME}/.ssh/id_rsa"), "\${VAR} form escaped the guard")
    }

    @Test
    @DisplayName("an undefined variable naming a credential artefact is still refused")
    fun undefinedVariableWithCredentialKeywordRefused() {
        val shell = shell()
        assertFalse(shell.isAllowed("""cat %UNSET_B4_DIR%\id_rsa"""), "keyword check must catch this")
    }

    @Test
    @DisplayName("a path hidden behind an option value is inspected")
    fun optionValueRefused() {
        val shell = shell()
        assertFalse(shell.isAllowed("echo -Dkey=~/.ssh/id_rsa"), "`--key=value` form escaped the guard")
    }

    // ==================== allowed ====================

    @Test
    @DisplayName("an undefined variable that names no credential still works")
    fun undefinedVariableWithoutKeywordAllowed() {
        // `%JAVA_HOME%\bin\mvn` must not be rejected merely because the variable is unset.
        val shell = shell()
        assertTrue(shell.isAllowed("""echo %UNSET_B4_JAVA%\bin"""), explain(shell, """echo %UNSET_B4_JAVA%\bin"""))
    }

    @Test
    @DisplayName("ordinary external tool state such as ~/.m2 stays usable")
    fun mavenRepositoryAllowed() {
        val shell = shell()
        assertTrue(shell.isAllowed("cat ~/.m2/settings.xml"), explain(shell, "cat ~/.m2/settings.xml"))
    }

    @Test
    @DisplayName("a path that only looks sensitive inside the workspace stays allowed")
    fun workspaceRelativeAllowed() {
        val shell = shell()
        assertTrue(shell.isAllowed("cat .ssh/notes.txt"), explain(shell, "cat .ssh/notes.txt"))
    }

    @Test
    @DisplayName("the guard can be disabled for troubleshooting")
    fun guardCanBeDisabled() {
        val shell = shell(pathGuardEnabled = false)
        assertTrue(shell.isAllowed("cat ~/.ssh/id_rsa"), explain(shell, "cat ~/.ssh/id_rsa"))
    }

    // ==================== command substitution ====================

    @Test
    @DisplayName("a credential path inside a quoted command substitution is still refused")
    fun quotedSubstitutionRefused() {
        val shell = shell()
        // Unquoted substitutions were already segmented; the quoted form used to skip validation
        // entirely, and `sh -c` executes it.
        listOf(
            "echo \"\$(cat ~/.ssh/id_rsa)\"",
            "echo \"`cat ~/.ssh/id_rsa`\"",
        ).forEach { command ->
            assertFalse(shell.isAllowed(command), "quoted substitution escaped the guard: $command")
            assertTrue(explain(shell, command).contains("substitution"), explain(shell, command))
        }
        // The refusal keeps the credential cause, so the caller sees COMMAND_PATH_DENIED rather than
        // a generic "not allowed".
        val refusal = kotlinx.coroutines.runBlocking { shell.execute("echo \"\$(cat ~/.ssh/id_rsa)\"") }
        assertTrue(refusal.contains("code=${PermissionAudit.CODE_COMMAND_PATH_DENIED}"), refusal)
    }

    @Test
    @DisplayName("a quoted substitution of an allowed command still works")
    fun quotedSubstitutionOfSafeCommandAllowed() {
        val shell = shell()
        assertTrue(shell.isAllowed("echo \"\$(date +%Y)\""), explain(shell, "echo \"\$(date +%Y)\""))
    }

    @Test
    @DisplayName("single-quoted substitution text is literal, not a command")
    fun singleQuotedSubstitutionIsLiteral() {
        val shell = shell()
        assertTrue(shell.isAllowed("echo '\$(not a command)'"), explain(shell, "echo '\$(not a command)'"))
    }

    @Test
    @DisplayName("arithmetic expansion is not treated as a command")
    fun arithmeticExpansionAllowed() {
        val shell = shell()
        assertTrue(shell.isAllowed("echo \$((1 + 2))"), explain(shell, "echo \$((1 + 2))"))
    }

    // ==================== protected directories ====================

    @Test
    @DisplayName("the shell cannot write into the agent's own bookkeeping directory")
    fun browser4WriteRefused() {
        val shell = shell()
        listOf(
            "echo forged > .browser4/data/agent/agent-tasks.jsonl",
            "echo forged >> .browser4/data/agent/agent-tasks.jsonl",
            "echo forged >.browser4/data/agent/agent-tasks.jsonl",
            "cp pom.xml .browser4/coding-snapshots/x.json",
            "rm -rf .browser4",
            "rm -rf .browser4/*",
            "cd .browser4 && echo forged > agent-tasks.jsonl",
        ).forEach { command ->
            assertFalse(shell.isAllowed(command), "protected path escaped the shell guard: $command")
            assertTrue(explain(shell, command).contains("protected"), explain(shell, command))
        }
    }

    @Test
    @DisplayName("the shell cannot modify CI definitions")
    fun workflowsWriteRefused() {
        val shell = shell()
        assertFalse(shell.isAllowed("cp pom.xml .github/workflows/ci.yml"), "ci.yml write escaped the guard")
        assertFalse(shell.isAllowed("echo 'on: push' > .github/workflows/x.yml"))
    }

    @Test
    @DisplayName("the protected-subtree guard is not disabled by the deprecated credential switch")
    fun protectedGuardSurvivesPathGuardOff() {
        // `shell.pathGuard=off` (deprecated, advisory) exists to unblock credential heuristics for
        // local debugging; the protected subtrees are a rail, not a capability, so they stay
        // enforced.
        val shell = shell(pathGuardEnabled = false)
        assertFalse(shell.isAllowed("echo forged > .browser4/x.json"))
    }

    @Test
    @DisplayName("refusals describe the check as advisory and no longer advertise a bypass")
    fun refusalsAreAdvisory() {
        val shell = shell()
        val text = explain(shell, "cat ~/.ssh/id_rsa")
        assertTrue(text.contains("advisory"), "the refusal must say the check is advisory: $text")
        assertFalse(
            text.contains("pathGuard=off"),
            "a refusal must not advertise the deprecated bypass: $text",
        )
    }

    @Test
    @DisplayName("ordinary workspace paths are untouched by the protected guard")
    fun ordinaryPathsAllowed() {
        val shell = shell()
        listOf(
            "echo hi > notes.txt",
            "cat pom.xml > out/pom.txt",
            "cp pom.xml target/pom.xml",
            "cat .github/CODEOWNERS",
        ).forEach { command ->
            assertTrue(shell.isAllowed(command), "false positive on $command: ${explain(shell, command)}")
        }
    }

    @Test
    @DisplayName("a protected working directory is refused")
    fun protectedWorkingDirRefused() {
        val shell = shell()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                shell.execute("echo x > y", workingDir = ".browser4/coding-snapshots")
            }
        }
        assertTrue(ex.message!!.contains("PERMISSION_DENIED"), ex.message)
        assertFalse(
            java.nio.file.Files.exists(workspace.resolve(".browser4/coding-snapshots")),
            "the refused working directory must not be created",
        )
    }
}
