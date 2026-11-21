package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The permission matrix, asserted instead of described.
 *
 * `docs/agent-permissions-redesign.md` states the goals; this test is what stops them from drifting
 * into prose that no backend follows. Each case names one goal from the design:
 *
 *  - browse: minimal reach around browser4 commands, scripts allowed, file access confined;
 *  - coding: arbitrary projects developable — read like the user, write only the workspace + caches.
 */
@DisplayName("isolation profile")
class IsolationProfileTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("browse reads and writes only the workspace, and only the browser reaches the net")
    fun browseIsMinimal() {
        val profile = IsolationProfile.forMode(AgentMode.BROWSE)

        assertEquals(AgentMode.BROWSE, profile.mode)
        assertEquals(ReadScope.WorkspaceOnly, profile.read, "browse must not read outside the workspace")
        assertEquals(WriteScope.Workspace, profile.write)
        assertEquals(NetPolicy.BrowserOnly, profile.net, "scripts get no egress; the browser does")
        assertEquals(ExecPolicy.ALLOWLIST, profile.exec, "scripts are allowed, from an allowlist")
    }

    @Test
    @DisplayName("coding reads like the user, writes only the workspace plus build caches")
    fun codingRelaxesReadOnly() {
        val caches = listOf(tempDir.resolve(".m2"), tempDir.resolve(".gradle"))
        val profile = IsolationProfile.forMode(AgentMode.CODING, caches = caches)

        assertEquals(ReadScope.CurrentUser, profile.read)
        assertEquals(ExecPolicy.ANY, profile.exec, "coding means arbitrary code execution")
        assertEquals(NetPolicy.Off, profile.net, "egress is granted per host, not by mode")
        val write = profile.write
        assertTrue(write is WriteScope.WorkspacePlus, "a coding task writes its build caches: $write")
        assertEquals(caches.map { it.toAbsolutePath().normalize() }, (write as WriteScope.WorkspacePlus).caches)
    }

    @Test
    @DisplayName("coding keeps the credential stores out even though it inherits the user's reads")
    fun codingStillRefusesCredentials() {
        val profile = IsolationProfile.forMode(AgentMode.CODING)

        assertEquals(ReadScope.CurrentUser, profile.read)
        // Inheriting the user's rights must not mean inheriting the keys: the rail is checked by the
        // backends, and it is independent of the profile.
        val home = Path.of(System.getProperty("user.home"))
        listOf(".ssh", ".aws", ".gnupg").forEach { name ->
            assertTrue(
                ReadablePaths.isAlwaysRefused(home.resolve(name)),
                "$name must stay refused whatever the profile says",
            )
        }
    }

    @Test
    @DisplayName("the default caches are the writable toolchain entries, never the JDK or the OS root")
    fun defaultCachesExcludeReadOnlyToolchains() {
        val caches = IsolationProfile.defaultCaches().map { it.fileName.toString().lowercase() }

        assertFalse(caches.any { it.contains("jdk") }, "the JDK is read-only: $caches")
        assertFalse(
            IsolationProfile.defaultCaches().any { it.toString().replace('\\', '/').endsWith("/windows", true) },
            "the OS directory is read-only",
        )
    }

    @Test
    @DisplayName("--add-dir widens reads in both modes and never the write scope")
    fun addedDirsAreReadOnly() {
        val extra = tempDir.resolve("shared-libs")

        val browse = IsolationProfile.forMode(AgentMode.BROWSE, extraReadRoots = listOf(extra))
        val coding = IsolationProfile.forMode(AgentMode.CODING, extraReadRoots = listOf(extra))

        assertEquals(listOf(extra.toAbsolutePath().normalize()), browse.extraReadRoots)
        assertEquals(listOf(extra.toAbsolutePath().normalize()), coding.extraReadRoots)
        assertEquals(WriteScope.Workspace, browse.write, "an added folder must not become writable")
        assertTrue(coding.write is WriteScope.WorkspacePlus)
        assertFalse(
            (coding.write as WriteScope.WorkspacePlus).caches.contains(extra.toAbsolutePath().normalize()),
            "the added folder is a read root, not a cache",
        )
    }

    @Test
    @DisplayName("--unrestricted is an explicit profile, not the absence of one")
    fun unrestrictedIsExplicit() {
        val profile = IsolationProfile.unrestricted()

        assertEquals(ReadScope.CurrentUser, profile.read)
        assertEquals(WriteScope.CurrentUser, profile.write)
        assertTrue(profile.net is NetPolicy.Allow, "unrestricted includes egress")
        assertEquals(ExecPolicy.ANY, profile.exec)
    }

    @Test
    @DisplayName("browse and coding pick different backends; the account backend is only the fallback")
    fun backendChainFollowsTheMode() {
        val browse = IsolationProfile.backendChain(AgentMode.BROWSE, "Windows 11")
        val coding = IsolationProfile.backendChain(AgentMode.CODING, "Windows 11")

        assertEquals(IsolationProfile.BROWSE_BACKEND, browse.first())
        assertEquals(IsolationProfile.CODING_BACKEND, coding.first())
        assertEquals("windows-acl", browse.last(), "the account backend needs an administrator: fallback only")
        assertEquals("windows-acl", coding.last())
        assertTrue(IsolationProfile.backendChain(AgentMode.CODING, "Linux").contains("landlock"))
        assertTrue(IsolationProfile.backendChain(AgentMode.BROWSE, "Mac OS X").contains("seatbelt"))
        assertTrue(
            IsolationProfile.backendChain(AgentMode.CODING, "SunOS").isEmpty(),
            "an unknown platform has no OS layer, and the caller must say so",
        )
    }

    @Test
    @DisplayName("the profile prints one line that names every axis")
    fun describeNamesEveryAxis() {
        val line = IsolationProfile.forMode(AgentMode.CODING).describe()

        assertTrue(line.contains("mode=coding"), line)
        assertTrue(line.contains("read=user"), line)
        assertTrue(line.contains("write=workspace+"), line)
        assertTrue(line.contains("net=off"), line)
        assertTrue(line.contains("exec=any"), line)
    }
}
