package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The file tools under [ReadablePaths]: Browser4's own folders are readable, and they are read-only.
 *
 * The scenario is the real layout — a per-task workspace somewhere and the Browser4 data root
 * outside it, with the data root also on the sensitive list (it is `~/.browser4` in production).
 * Reading its logs, config and temp files must succeed; writing them must still be refused, because
 * the workspace is the only writable place. The OS credential stores stay refused even when the
 * readable root is their ancestor.
 */
class CodingAgentFileSystemReadableRootsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspace: Path
    private lateinit var dataRoot: Path

    @BeforeEach
    fun setUp() {
        workspace = Files.createDirectories(tempDir.resolve("workspace"))
        dataRoot = Files.createDirectories(tempDir.resolve("appdata"))
        Files.createDirectories(dataRoot.resolve("logs"))
        Files.writeString(dataRoot.resolve("logs/browser4.log"), "line one\nline two\n")
        Files.createDirectories(dataRoot.resolve("config"))
        Files.writeString(dataRoot.resolve("config/providers.json"), "{\"providers\":[]}\n")
        Files.createDirectories(dataRoot.resolve("tmp"))
        Files.writeString(dataRoot.resolve("tmp/out.txt"), "temp artifact\n")
    }

    /**
     * A file system with [dataRoot] readable and also on the sensitive list — the production shape
     * (`~/.browser4` is both a readable root and a default sensitive entry).
     */
    private fun fs(
        readable: Set<Path> = setOf(dataRoot),
        sensitive: Set<String> = setOf(dataRoot.toString()),
    ) = CodingAgentFileSystem(
        workspaceRoot = workspace,
        sensitivePaths = sensitive,
        readableRoots = readable,
    )

    @Test
    @DisplayName("reading a file in the Browser4 data root works without external access")
    fun testReadInsideReadableRootIsAllowed() = runBlocking {
        val content = fs().readFile(dataRoot.resolve("logs/browser4.log").toString())

        assertTrue(content.contains("line one"), content)
    }

    @Test
    @DisplayName("the data root's config and temp files are readable too")
    fun testConfigAndTempAreReadable() = runBlocking {
        val f = fs()

        assertEquals("{\"providers\":[]}", f.readFile(dataRoot.resolve("config/providers.json").toString()).trim())
        assertEquals("temp artifact", f.readFile(dataRoot.resolve("tmp/out.txt").toString()).trim())
    }

    @Test
    @DisplayName("grep and listDir reach into a readable root")
    fun testSearchToolsReachReadableRoot() = runBlocking {
        val f = fs()

        val grep = f.grep("line two", path = dataRoot.resolve("logs").toString())
        assertTrue(grep.contains("browser4.log"), grep)

        val listing = f.listDir(dataRoot.toString())
        assertTrue(listing.contains("logs"), listing)
        assertTrue(listing.contains("config"), listing)
    }

    @Test
    @DisplayName("writing into a readable root is refused, and the file is not created")
    fun testWriteInsideReadableRootIsRefused() = runBlocking {
        val target = dataRoot.resolve("logs/injected.log")

        val result = fs().writeFile(target.toString(), "nope\n")

        assertTrue(result.contains(PermissionAudit.DENIED), result)
        assertFalse(Files.exists(target), "a read-only root must not be writable: $result")
    }

    @Test
    @DisplayName("a write into a readable root is counted as READ_ONLY, not as a path outside the workspace")
    fun testWriteInsideReadableRootRecordsReadOnly() = runBlocking {
        val before = PermissionAudit.snapshot()["coding.fs:${PermissionAudit.CODE_READ_ONLY}"] ?: 0

        fs().writeFile(dataRoot.resolve("logs/injected.log").toString(), "nope\n")

        val after = PermissionAudit.snapshot()["coding.fs:${PermissionAudit.CODE_READ_ONLY}"] ?: 0
        assertTrue(after > before, "expected a READ_ONLY audit record, counter stayed at $after")
    }

    @Test
    @DisplayName("delete inside a readable root is refused too")
    fun testDeleteInsideReadableRootIsRefused() = runBlocking {
        val victim = dataRoot.resolve("logs/browser4.log")

        val result = fs().delete(victim.toString())

        assertTrue(result.contains(PermissionAudit.DENIED), result)
        assertTrue(Files.exists(victim), "the readable root is read-only: $result")
    }

    @Test
    @DisplayName("an ordinary folder outside the workspace and outside the readable roots stays refused")
    fun testUnreadableOutsideFolderStaysRefused() = runBlocking {
        val other = Files.createDirectories(tempDir.resolve("elsewhere"))
        Files.writeString(other.resolve("data.txt"), "secret\n")

        val result = fs().readFile(other.resolve("data.txt").toString())

        assertTrue(result.contains(PermissionAudit.DENIED), result)
    }

    @Test
    @DisplayName("a credential store stays refused even when the readable root is the whole home")
    fun testCredentialStoreStaysRefused() = runBlocking {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val key = home.resolve(".ssh/id_rsa")

        val result = CodingAgentFileSystem(
            workspaceRoot = workspace,
            sensitivePaths = emptySet(),
            readableRoots = setOf(home),
        ).readFile(key.toString())

        assertTrue(result.contains(PermissionAudit.DENIED), result)
        assertTrue(result.contains("sensitive location"), result)
    }
}
