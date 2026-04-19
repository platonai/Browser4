package ai.platon.pulsar.agentic.common

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AgentShellTest {

    private lateinit var tempDir: Path
    private lateinit var shell: AgentShell

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("agent-shell-test")
        shell = AgentShell(tempDir)
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // Whitelist validation tests

    @Test
    @DisplayName("test allowed command ls executes successfully")
    fun testAllowedCommandLsExecutes() = runBlocking {
        val result = shell.execute("ls")
        assertFalse(result.contains("not in the whitelist"), "ls should be allowed")
        assertFalse(result.contains("blocked"), "ls should not be blocked")
    }

    @Test
    @DisplayName("test allowed command pwd executes successfully")
    fun testAllowedCommandPwdExecutes() = runBlocking {
        val result = shell.execute("pwd")
        assertFalse(result.contains("not in the whitelist"), "pwd should be allowed")
        assertFalse(result.contains("blocked"), "pwd should not be blocked")
    }

    @Test
    @DisplayName("test allowed command with arguments ls -la executes")
    fun testAllowedCommandWithArgsExecutes() = runBlocking {
        val result = shell.execute("ls -la")
        assertFalse(result.contains("not in the whitelist"), "ls -la should be allowed")
        assertFalse(result.contains("blocked"), "ls -la should not be blocked")
    }

    @Test
    @DisplayName("test allowed command cat executes")
    fun testAllowedCommandCatExecutes() = runBlocking {
        val result = shell.execute("cat /etc/hosts")
        assertFalse(result.contains("not in the whitelist"), "cat should be allowed")
    }

    @Test
    @DisplayName("test allowed command grep executes")
    fun testAllowedCommandGrepExecutes() = runBlocking {
        val result = shell.execute("grep root /etc/passwd")
        assertFalse(result.contains("not in the whitelist"), "grep should be allowed")
    }

    @Test
    @DisplayName("test allowed command awk executes")
    fun testAllowedCommandAwkExecutes() = runBlocking {
        val result = shell.execute("awk '{print \$1}' /etc/hosts")
        assertFalse(result.contains("not in the whitelist"), "awk should be allowed")
    }

    @Test
    @DisplayName("test allowed command sed executes")
    fun testAllowedCommandSedExecutes() = runBlocking {
        val result = shell.execute("sed -n '1p' /etc/hosts")
        assertFalse(result.contains("not in the whitelist"), "sed should be allowed")
    }

    @Test
    @DisplayName("test allowed command wc executes")
    fun testAllowedCommandWcExecutes() = runBlocking {
        val result = shell.execute("wc -l /etc/hosts")
        assertFalse(result.contains("not in the whitelist"), "wc should be allowed")
    }

    @Test
    @DisplayName("test allowed command uname executes")
    fun testAllowedCommandUnameExecutes() = runBlocking {
        val result = shell.execute("uname -a")
        assertFalse(result.contains("not in the whitelist"), "uname should be allowed")
    }

    @Test
    @DisplayName("test allowed command hostname executes")
    fun testAllowedCommandHostnameExecutes() = runBlocking {
        val result = shell.execute("hostname")
        assertFalse(result.contains("not in the whitelist"), "hostname should be allowed")
    }

    @Test
    @DisplayName("test allowed command uptime executes")
    fun testAllowedCommandUptimeExecutes() = runBlocking {
        val result = shell.execute("uptime")
        assertFalse(result.contains("not in the whitelist"), "uptime should be allowed")
    }

    @Test
    @DisplayName("test allowed command whoami executes")
    fun testAllowedCommandWhoamiExecutes() = runBlocking {
        val result = shell.execute("whoami")
        assertFalse(result.contains("not in the whitelist"), "whoami should be allowed")
    }

    @Test
    @DisplayName("test allowed command id executes")
    fun testAllowedCommandIdExecutes() = runBlocking {
        val result = shell.execute("id")
        assertFalse(result.contains("not in the whitelist"), "id should be allowed")
    }

    @Test
    @DisplayName("test allowed command free executes")
    fun testAllowedCommandFreeExecutes() = runBlocking {
        val result = shell.execute("free -h")
        assertFalse(result.contains("not in the whitelist"), "free should be allowed")
    }

    @Test
    @DisplayName("test allowed command df executes")
    fun testAllowedCommandDfExecutes() = runBlocking {
        val result = shell.execute("df -h")
        assertFalse(result.contains("not in the whitelist"), "df should be allowed")
    }

    @Test
    @DisplayName("test allowed command du executes")
    fun testAllowedCommandDuExecutes() = runBlocking {
        val result = shell.execute("du -sh .")
        assertFalse(result.contains("not in the whitelist"), "du should be allowed")
    }

    @Test
    @DisplayName("test allowed command ps executes")
    fun testAllowedCommandPsExecutes() = runBlocking {
        val result = shell.execute("ps aux")
        assertFalse(result.contains("not in the whitelist"), "ps should be allowed")
    }

    @Test
    @DisplayName("test allowed command top executes")
    fun testAllowedCommandTopExecutes() = runBlocking {
        val result = shell.execute("top -bn1")
        assertFalse(result.contains("not in the whitelist"), "top should be allowed")
    }

    @Test
    @DisplayName("test allowed command pgrep executes")
    fun testAllowedCommandPgrepExecutes() = runBlocking {
        val result = shell.execute("pgrep -l bash")
        assertFalse(result.contains("not in the whitelist"), "pgrep should be allowed")
    }

    @Test
    @DisplayName("test allowed command ip addr executes")
    fun testAllowedCommandIpAddrExecutes() = runBlocking {
        val result = shell.execute("ip addr")
        assertFalse(result.contains("not in the whitelist"), "ip addr should be allowed")
    }

    @Test
    @DisplayName("test allowed command ip route executes")
    fun testAllowedCommandIpRouteExecutes() = runBlocking {
        val result = shell.execute("ip route")
        assertFalse(result.contains("not in the whitelist"), "ip route should be allowed")
    }

    @Test
    @DisplayName("test allowed command ss executes")
    fun testAllowedCommandSsExecutes() = runBlocking {
        val result = shell.execute("ss -tuln")
        assertFalse(result.contains("not in the whitelist"), "ss should be allowed")
    }

    @Test
    @DisplayName("test allowed command env executes")
    fun testAllowedCommandEnvExecutes() = runBlocking {
        val result = shell.execute("env")
        assertFalse(result.contains("not in the whitelist"), "env should be allowed")
    }

    @Test
    @DisplayName("test allowed command printenv executes")
    fun testAllowedCommandPrintenvExecutes() = runBlocking {
        val result = shell.execute("printenv PATH")
        assertFalse(result.contains("not in the whitelist"), "printenv should be allowed")
    }

    @Test
    @DisplayName("test allowed command which executes")
    fun testAllowedCommandWhichExecutes() = runBlocking {
        val result = shell.execute("which bash")
        assertFalse(result.contains("not in the whitelist"), "which should be allowed")
    }

    @Test
    @DisplayName("test allowed command type executes")
    fun testAllowedCommandTypeExecutes() = runBlocking {
        val result = shell.execute("type ls")
        assertFalse(result.contains("not in the whitelist"), "type should be allowed")
    }

    // IP command specific tests

    @Test
    @DisplayName("test ip link command is blocked")
    fun testIpLinkCommandIsBlocked() = runBlocking {
        val result = shell.execute("ip link")
        assertTrue(result.contains("not in the whitelist"), "ip link should be blocked - only ip addr and ip route allowed")
    }

    @Test
    @DisplayName("test ip neigh command is blocked")
    fun testIpNeighCommandIsBlocked() = runBlocking {
        val result = shell.execute("ip neigh")
        assertTrue(result.contains("not in the whitelist"), "ip neigh should be blocked - only ip addr and ip route allowed")
    }

    // Sed specific tests

    @Test
    @DisplayName("test sed in-place editing is blocked")
    fun testSedInPlaceEditingIsBlocked() = runBlocking {
        val result = shell.execute("sed -i 's/foo/bar/g' file.txt")
        assertTrue(result.contains("sed in-place editing"), "sed -i should be blocked")
    }

    @Test
    @DisplayName("test sed with -i flag is blocked")
    fun testSedWithInlineFlagIsBlocked() = runBlocking {
        val result = shell.execute("sed -ie 's/foo/bar/g' file.txt")
        assertTrue(result.contains("sed in-place editing"), "sed with -i flag should be blocked")
    }

    @Test
    @DisplayName("test sed with --in-place is blocked")
    fun testSedWithLongFormInPlaceIsBlocked() = runBlocking {
        val result = shell.execute("sed --in-place 's/foo/bar/g' file.txt")
        assertTrue(result.contains("sed in-place editing"), "sed --in-place should be blocked")
    }

    @Test
    @DisplayName("test sed with -n and -i is blocked")
    fun testSedWithNAndIIsBlocked() = runBlocking {
        val result = shell.execute("sed -n -i '1p' file.txt")
        assertTrue(result.contains("sed in-place editing"), "sed -n -i should be blocked")
    }

    @Test
    @DisplayName("test sed read-only with -n is allowed")
    fun testSedReadOnlyIsAllowed() = runBlocking {
        val result = shell.execute("sed -n '1p' /etc/hosts")
        assertFalse(result.contains("not in the whitelist"), "sed -n should be allowed")
        assertFalse(result.contains("sed in-place editing"), "sed -n should not be blocked")
    }

    // Blocked command tests

    @Test
    @DisplayName("test disallowed command rm is blocked")
    fun testDisallowedCommandRmIsBlocked() = runBlocking {
        val result = shell.execute("rm file.txt")
        assertTrue(result.contains("not in the whitelist"), "rm should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command curl is blocked")
    fun testDisallowedCommandCurlIsBlocked() = runBlocking {
        val result = shell.execute("curl http://example.com")
        assertTrue(result.contains("not in the whitelist"), "curl should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command wget is blocked")
    fun testDisallowedCommandWgetIsBlocked() = runBlocking {
        val result = shell.execute("wget http://example.com")
        assertTrue(result.contains("not in the whitelist"), "wget should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command chmod is blocked")
    fun testDisallowedCommandChmodIsBlocked() = runBlocking {
        val result = shell.execute("chmod 777 file.txt")
        assertTrue(result.contains("not in the whitelist"), "chmod should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command chown is blocked")
    fun testDisallowedCommandChownIsBlocked() = runBlocking {
        val result = shell.execute("chown root file.txt")
        assertTrue(result.contains("not in the whitelist"), "chown should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command mv is blocked")
    fun testDisallowedCommandMvIsBlocked() = runBlocking {
        val result = shell.execute("mv file1.txt file2.txt")
        assertTrue(result.contains("not in the whitelist"), "mv should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command cp is blocked")
    fun testDisallowedCommandCpIsBlocked() = runBlocking {
        val result = shell.execute("cp file1.txt file2.txt")
        assertTrue(result.contains("not in the whitelist"), "cp should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command touch is blocked")
    fun testDisallowedCommandTouchIsBlocked() = runBlocking {
        val result = shell.execute("touch newfile.txt")
        assertTrue(result.contains("not in the whitelist"), "touch should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command mkdir is blocked")
    fun testDisallowedCommandMkdirIsBlocked() = runBlocking {
        val result = shell.execute("mkdir newdir")
        assertTrue(result.contains("not in the whitelist"), "mkdir should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command python is blocked")
    fun testDisallowedCommandPythonIsBlocked() = runBlocking {
        val result = shell.execute("python script.py")
        assertTrue(result.contains("not in the whitelist"), "python should be blocked by whitelist")
    }

    @Test
    @DisplayName("test disallowed command bash is blocked")
    fun testDisallowedCommandBashIsBlocked() = runBlocking {
        val result = shell.execute("bash script.sh")
        assertTrue(result.contains("not in the whitelist"), "bash should be blocked by whitelist")
    }

    // Edge case tests

    @Test
    @DisplayName("test empty command is rejected")
    fun testEmptyCommandIsRejected() = runBlocking {
        val result = shell.execute("")
        assertTrue(result.contains("Error"), "Empty command should return error")
    }

    @Test
    @DisplayName("test blank command is rejected")
    fun testBlankCommandIsRejected() = runBlocking {
        val result = shell.execute("   ")
        assertTrue(result.contains("Error"), "Blank command should return error")
    }

    @Test
    @DisplayName("test command with leading spaces is handled")
    fun testCommandWithLeadingSpacesIsHandled() = runBlocking {
        val result = shell.execute("  ls -la")
        assertFalse(result.contains("not in the whitelist"), "Command with leading spaces should work")
    }

    @Test
    @DisplayName("test command with trailing spaces is handled")
    fun testCommandWithTrailingSpacesIsHandled() = runBlocking {
        val result = shell.execute("ls -la  ")
        assertFalse(result.contains("not in the whitelist"), "Command with trailing spaces should work")
    }

    @Test
    @DisplayName("test tree command if available")
    fun testTreeCommandIfAvailable() = runBlocking {
        val result = shell.execute("tree -L 1")
        // tree might not be installed, so we just check it's not blocked by whitelist
        assertFalse(result.contains("not in the whitelist"), "tree should be in whitelist")
    }

    @Test
    @DisplayName("test head command executes")
    fun testHeadCommandExecutes() = runBlocking {
        val result = shell.execute("head -n 5 /etc/passwd")
        assertFalse(result.contains("not in the whitelist"), "head should be allowed")
    }

    @Test
    @DisplayName("test tail command executes")
    fun testTailCommandExecutes() = runBlocking {
        val result = shell.execute("tail -n 5 /etc/passwd")
        assertFalse(result.contains("not in the whitelist"), "tail should be allowed")
    }

    @Test
    @DisplayName("test less command is in whitelist")
    fun testLessCommandInWhitelist() = runBlocking {
        // less is interactive and will timeout without input, but we're only verifying
        // it's not rejected by the whitelist check (it should pass whitelist but timeout)
        val result = shell.execute("less", timeoutSeconds = 1)
        assertFalse(result.contains("not in the whitelist"), "less should be in whitelist")
        // Note: The command will likely timeout or fail due to no input file,
        // but the important thing is it's not blocked by whitelist validation
    }
}
