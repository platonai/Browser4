package ai.platon.pulsar.coding

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The boundary [Authorizer] and the scope manifest carry the readable roots as **read-only**.
 *
 * `browser4.agent.readPaths` (and the built-in Browser4 data root) widen `readPaths` on both grants
 * and leave `writePaths` alone. That split is what makes the option useful — the agent can read its
 * own data and checkout — without turning it into a workspace-power widening that `--access read`
 * would have to defend against.
 *
 * The readable root lives under the user's home, not under the temp dir: the shell's toolchain scope
 * already includes `java.io.tmpdir`, so a temp-based root would be writable for reasons that have
 * nothing to do with this change.
 */
class ReadableRootsBoundaryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspace: Path
    private lateinit var dataRoot: Path
    private var savedDataDir: String? = null

    @BeforeEach
    fun setUp() {
        savedDataDir = System.getProperty(ReadablePaths.PROP_APP_DATA_DIR)
        workspace = Files.createDirectories(tempDir.resolve("ws"))
        val home = Path.of(System.getProperty("user.home"))
        dataRoot = Files.createDirectories(home.resolve(".b4-boundary-${UUID.randomUUID().toString().take(8)}"))
        Files.createDirectories(dataRoot.resolve("logs"))
        Files.writeString(dataRoot.resolve("logs/browser4.log"), "line one\n")
        // The built-in readable root: the data root is discovered from this property.
        System.setProperty(ReadablePaths.PROP_APP_DATA_DIR, dataRoot.toString())
    }

    @AfterEach
    fun tearDown() {
        if (savedDataDir == null) System.clearProperty(ReadablePaths.PROP_APP_DATA_DIR)
        else System.setProperty(ReadablePaths.PROP_APP_DATA_DIR, savedDataDir!!)
        dataRoot.toFile().deleteRecursively()
    }

    private fun decide(effect: Effect, path: Path, grant: CapabilityId): Decision =
        CodingComponents.boundaryAuthorizer(workspace).decide(
            Request(
                tool = "test.readable",
                effects = setOf(effect),
                targets = listOf(Target.PathTarget(path)),
                capabilityId = grant,
            ),
        )

    @Test
    @DisplayName("both grants allow a read under the configured data root")
    fun testReadsAreAllowed() {
        val log = dataRoot.resolve("logs/browser4.log")

        for (grant in listOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY)) {
            assertEquals(Decision.Allow, decide(Effect.FS_READ, log, grant), "read must be allowed by $grant")
        }
    }

    @Test
    @DisplayName("neither grant allows a write under the configured data root")
    fun testWritesAreRefused() {
        val target = dataRoot.resolve("logs/injected.log")

        for (grant in listOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY)) {
            val decision = decide(Effect.FS_WRITE, target, grant)
            assertTrue(decision is Decision.Deny, "write must be refused by $grant, got $decision")
        }
    }

    @Test
    @DisplayName("the manifest declares the readable root read-only, for both grants")
    fun testManifestEntryIsReadOnly() {
        val realDataRoot = dataRoot.toRealPath()
        val entry = CodingComponents.scopeManifest(workspace)
            .singleOrNull { it.kind == ScopeKind.READABLE && it.path == realDataRoot }

        assertNotNull(entry, "the configured data root must appear as a READABLE entry")
        assertEquals(setOf(Effect.FS_READ), entry!!.effects)
        assertEquals(
            setOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY),
            entry.grants,
        )
    }

    @Test
    @DisplayName("a readable root is not a narrowing candidate: observation data cannot drop it")
    fun testReadableRootsAreNotNarrowed() {
        val entry = CodingComponents.scopeManifest(workspace).first { it.kind == ScopeKind.READABLE }

        val suggestions = ScopeNarrowing.suggestions(
            CodingComponents.scopeManifest(workspace),
            mapOf(entry.id to ScopeNarrowing.DEFAULT_UNUSED_TASKS * 3),
        )

        assertTrue(suggestions.none { it.entry.kind == ScopeKind.READABLE }, "suggestions: $suggestions")
    }
}
