package ai.platon.pulsar.coding

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The shell's string guards under [ReadablePaths].
 *
 * Two things are pinned here:
 *
 * - the credential guard stops refusing a path merely because the folder is on the sensitive list:
 *   the Browser4 data root is on that list *and* readable, and `type <data>/logs/…` used to be
 *   refused as "resolves to a credential location";
 * - the boundary guard still refuses an ordinary folder outside the workspace, and starts accepting
 *   the readable roots.
 *
 * The readable root lives under the user's home rather than the temp dir on purpose: the shell's
 * toolchain scope already includes `java.io.tmpdir`, so a temp-based root would prove nothing.
 * Nothing here spawns a process — `isAllowed`/`explain` run the same validation the executor does.
 */
class CodingAgentShellReadableRootsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspace: Path
    private lateinit var dataRoot: Path

    @BeforeEach
    fun setUp() {
        workspace = Files.createDirectories(tempDir.resolve("workspace"))
        val home = Path.of(System.getProperty("user.home"))
        dataRoot = Files.createDirectories(home.resolve(".b4-readable-${UUID.randomUUID().toString().take(8)}"))
        Files.createDirectories(dataRoot.resolve("logs"))
        Files.writeString(dataRoot.resolve("logs/browser4.log"), "line one\n")
    }

    @AfterEach
    fun tearDown() {
        dataRoot.toFile().deleteRecursively()
    }

    private fun shell(
        readable: Set<Path> = setOf(dataRoot),
        sensitive: Set<String> = setOf(dataRoot.toString()),
    ) = CodingAgentShell(
        baseDir = workspace,
        pathGuardEnabled = true,
        workspaceBoundaryEnabled = true,
        sensitivePaths = sensitive,
        readableRoots = readable,
    )

    @Test
    @DisplayName("a folder on the sensitive list is unreadable until it is also a readable root")
    fun testSensitiveFolderIsRelaxedByAReadableRoot() {
        val command = "type \"${dataRoot.resolve("logs/browser4.log")}\""

        val withoutRoot = CodingAgentShell(
            baseDir = workspace,
            sensitivePaths = setOf(dataRoot.toString()),
        )
        assertFalse(withoutRoot.isAllowed(command), withoutRoot.explain(command))
        // The credential guard is the one that refused it: that is the branch the readable root relaxes.
        assertTrue(
            withoutRoot.explain(command).contains("credential location"),
            withoutRoot.explain(command),
        )

        val withRoot = shell()
        assertTrue(withRoot.isAllowed(command), withRoot.explain(command))
    }

    @Test
    @DisplayName("a readable root is accepted by the boundary guard while an ordinary outside folder is not")
    fun testBoundaryAcceptsOnlyReadableRoots() {
        val readable = shell()
        assertTrue(
            readable.isAllowed("type \"${dataRoot.resolve("logs/browser4.log")}\""),
            readable.explain("type \"${dataRoot.resolve("logs/browser4.log")}\""),
        )

        // The same command with no readable roots: outside the workspace, not a toolchain location.
        val plain = CodingAgentShell(baseDir = workspace, sensitivePaths = emptySet())
        val outside = "type \"${dataRoot.resolve("logs/browser4.log")}\""
        assertFalse(plain.isAllowed(outside), plain.explain(outside))
    }

    @Test
    @DisplayName("the OS credential stores stay refused even when the readable root is the whole home")
    fun testCredentialStoreStaysRefused() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val key = home.resolve(".ssh/id_rsa")
        val readableHome = CodingAgentShell(
            baseDir = workspace,
            sensitivePaths = emptySet(),
            readableRoots = setOf(home),
        )

        val command = "type \"$key\""
        assertFalse(readableHome.isAllowed(command), readableHome.explain(command))
        assertTrue(readableHome.explain(command).contains("credential location"), readableHome.explain(command))
    }
}
