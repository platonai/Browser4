package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the credential-location guard in [CodingAgentFileSystem]: paths outside the
 * workspace that hold secrets stay unreachable even when external access is enabled
 * (plan P0-3/P0-4).
 */
class SensitivePathGuardTest {

    @TempDir
    lateinit var workspace: Path

    private fun credentialDir(): Path {
        val dir = Files.createTempDirectory("b4-cred")
        Files.writeString(dir.resolve("id_rsa"), "PRIVATE KEY\n")
        return dir
    }

    @Test
    @DisplayName("reading a credential location is refused even with external access enabled")
    fun readSensitiveRefused() = runBlocking {
        val cred = credentialDir()
        try {
            val fs = CodingAgentFileSystem(
                workspace,
                allowExternalAccess = true,
                sensitivePaths = setOf(cred.toString()),
            )
            val result = fs.readFile(cred.resolve("id_rsa").toString())
            assertTrue(result.startsWith("Error:"), "result: $result")
            assertFalse(result.contains("PRIVATE KEY"), "content must never reach the caller")
        } finally {
            cred.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("writing into a credential location is refused even with external access enabled")
    fun writeSensitiveRefused() = runBlocking {
        val cred = credentialDir()
        try {
            val fs = CodingAgentFileSystem(
                workspace,
                allowExternalAccess = true,
                sensitivePaths = setOf(cred.toString()),
            )
            val result = fs.writeFile(cred.resolve("authorized_keys").toString(), "ssh-rsa AAAA\n")
            assertTrue(result.startsWith("Error:"), "result: $result")
            assertFalse(Files.exists(cred.resolve("authorized_keys")))
        } finally {
            cred.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("an ordinary location outside the workspace stays readable with external access")
    fun ordinaryExternalPathAllowed() = runBlocking {
        val other = Files.createTempDirectory("b4-other")
        try {
            Files.writeString(other.resolve("notes.txt"), "hello\n")
            val fs = CodingAgentFileSystem(
                workspace,
                allowExternalAccess = true,
                sensitivePaths = setOf(Path.of(System.getProperty("user.home"), ".ssh").toString()),
            )
            assertEquals("hello", fs.readFile(other.resolve("notes.txt").toString()).trim())
        } finally {
            other.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("a workspace living inside a sensitive root stays fully accessible")
    fun workspaceInsideSensitiveRootExempt() = runBlocking {
        // The --fresh-workspace layout puts the workspace under ~/.browser4; treating the
        // whole tree as sensitive would make every file tool call fail.
        val fs = CodingAgentFileSystem(workspace, sensitivePaths = setOf(workspace.toString()))
        val result = fs.writeFile("a.txt", "hello\n")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("hello", fs.readFile("a.txt").trim())
    }

    @Test
    @DisplayName("the default sensitive set covers the common credential stores")
    fun defaultsCoverCredentials() {
        val defaults = CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS
        assertTrue(defaults.contains("~/.ssh"), "defaults: $defaults")
        assertTrue(defaults.contains("~/.aws"), "defaults: $defaults")
        assertTrue(defaults.contains("~/.gnupg"), "defaults: $defaults")
    }

    @Test
    @DisplayName("home-relative defaults are expanded and refused by the default policy")
    fun homeRelativeDefaultsRefused() = runBlocking {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val key = home.resolve(".ssh/id_rsa")
        // Not created on disk: the guard must refuse on the lexical path, before any I/O.
        val fs = CodingAgentFileSystem(workspace, allowExternalAccess = true)
        val result = fs.readFile(key.toString())
        assertTrue(result.startsWith("Error:"), "result: $result")
        // A "File not found" answer would pass the assertion above but mean the guard never ran.
        assertTrue(result.contains("PERMISSION_DENIED"), "the refusal must be structural: $result")
        assertFalse(result.contains("File not found"), "the path must be refused, not probed: $result")
    }

    @Test
    @DisplayName("a link inside the workspace cannot read a credential location")
    fun workspaceLinkToCredentialRefused() = runBlocking {
        val cred = credentialDir()
        try {
            val link = workspace.resolve("link")
            if (!createLink(link, cred)) return@runBlocking
            val fs = CodingAgentFileSystem(workspace, sensitivePaths = setOf(cred.toString()))
            val result = fs.readFile("link/id_rsa")
            assertTrue(result.startsWith("Error:"), "the link must not expose the key: $result")
            assertFalse(result.contains("PRIVATE KEY"), "content must never reach the caller")
        } finally {
            cred.toFile().deleteRecursively()
        }
    }

    /** Creates a symlink or (on Windows) a directory junction; false when the platform refuses. */
    private fun createLink(link: Path, target: Path): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return true
        return runCatching {
            ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }
}
