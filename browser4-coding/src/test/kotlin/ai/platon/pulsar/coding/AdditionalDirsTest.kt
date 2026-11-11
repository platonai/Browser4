package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `agent run --add-dir`: the folders granted to one task besides its workspace.
 *
 * Two properties matter and are pinned here: the grant is **scope** (the file tools and the shell
 * reach the folder, and it can be a shell working directory), and it is **not power** (the access
 * preset still decides whether anything may be written, and a folder that was not granted stays
 * refused). The submission-time rules are pinned too, because a grant that silently means nothing
 * is worse than a rejected call.
 */
@DisplayName("agent run --add-dir: granted folders")
class AdditionalDirsTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path get() = Files.createDirectories(tempDir.resolve("ws"))

    private val corpus: Path get() = Files.createDirectories(tempDir.resolve("corpus"))

    @Test
    @DisplayName("resolve accepts an existing folder and rejects everything a grant cannot mean")
    fun resolveRules() {
        assertEquals(
            corpus.toAbsolutePath().normalize(),
            AdditionalDirs.resolve(corpus.toString()),
        )

        // A quoted `~` is not shell-expanded by the caller; the resolver expands it.
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (home != null) {
            assertEquals(
                home.toAbsolutePath().normalize(),
                AdditionalDirs.resolve("~"),
                "a leading ~ must expand to the home directory",
            )
        }

        // Blank is not a folder.
        assertThrows<IllegalArgumentException> { AdditionalDirs.resolve("   ") }
        // A relative path has no meaning on the backend, whose CWD is the runtime bundle.
        val relative = assertThrows<IllegalArgumentException> { AdditionalDirs.resolve("relative/folder") }
        assertTrue(relative.message!!.contains("absolute path"), relative.message)
        // A missing folder is never created implicitly.
        val missing = assertThrows<IllegalArgumentException> {
            AdditionalDirs.resolve(tempDir.resolve("missing").toString())
        }
        assertTrue(missing.message!!.contains("not an existing directory"), missing.message)
        // A file is not a folder.
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")
        assertThrows<IllegalArgumentException> { AdditionalDirs.resolve(file.toString()) }
        // A credential store is refused before existence is even considered.
        if (home != null) {
            val credential = assertThrows<IllegalArgumentException> {
                AdditionalDirs.resolve(home.resolve(".ssh").resolve("id_rsa").toString())
            }
            assertTrue(credential.message!!.contains("credential store"), credential.message)
        }
    }

    @Test
    @DisplayName("resolveAll deduplicates and bounds the number of granted folders")
    fun resolveAllRules() {
        assertEquals(emptyList<Path>(), AdditionalDirs.resolveAll(emptyList()))

        val resolved = AdditionalDirs.resolveAll(listOf(corpus.toString(), "${corpus}${java.io.File.separator}"))
        assertEquals(1, resolved.size, "two spellings of one folder are one grant: $resolved")

        val tooMany = List(AdditionalDirs.MAX_DIRS + 1) { corpus.toString() }
        val ex = assertThrows<IllegalArgumentException> { AdditionalDirs.resolveAll(tooMany) }
        assertTrue(ex.message!!.contains("at most"), ex.message)
    }

    @Test
    @DisplayName("an added folder joins the manifest and both boundary grants; a sibling stays out")
    fun addedFolderIsInScope() {
        val ws = workspace
        val added = corpus
        val manifest = CodingComponents.scopeManifest(ws, additionalDirs = listOf(added))

        val entry = manifest.single { it.kind == ScopeKind.ADDED }
        assertEquals(added.toRealPath(), entry.path, "the entry must name the real folder")
        assertEquals(setOf(Effect.FS_READ, Effect.FS_WRITE), entry.effects, "read and write scope")
        assertEquals(
            setOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY),
            entry.grants,
            "the file tools and the shell must agree on an added folder",
        )
        // An added folder is not a narrowing candidate: it was requested for this task.
        assertTrue(
            ScopeNarrowing.suggestions(manifest, mapOf(entry.id to 99)).isEmpty(),
            "an added folder must never be suggested for narrowing",
        )

        val authorizer = CodingComponents.boundaryAuthorizer(ws, listOf(added))
        // A path that is in no entry's scope for either grant. The temp directory is NOT usable
        // here: it is a shell toolchain root (read+write in the shell grant), so a probe there
        // would be allowed for a reason that has nothing to do with the added folder.
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val outside = home.resolve("b4-added-dirs-probe-${System.nanoTime()}")
        for (grant in listOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY)) {
            val allowed = authorizer.decide(
                Request(
                    tool = "test.added",
                    effects = setOf(Effect.FS_READ, Effect.FS_WRITE),
                    targets = listOf(Target.PathTarget(added)),
                    capabilityId = grant,
                ),
            )
            assertTrue(allowed is Decision.Allow, "$grant must allow the added folder, got $allowed")

            val denied = authorizer.decide(
                Request(
                    tool = "test.added",
                    effects = setOf(Effect.FS_READ, Effect.FS_WRITE),
                    targets = listOf(Target.PathTarget(outside)),
                    capabilityId = grant,
                ),
            )
            assertTrue(denied is Decision.Deny, "$grant must refuse $outside, got $denied")
        }
    }

    @Test
    @DisplayName("the file tools write inside an added folder and still refuse an ungranted one")
    fun fileSystemReachesThroughTheGrant() = runBlocking {
        val ws = workspace
        val policy = AgentAccessPolicy().withAdditionalDirs(listOf(corpus))
        val fs = CodingComponents.fs(ws, policy)

        val target = corpus.resolve("notes").resolve("out.md")
        val written = fs.writeFile(target.toString(), "hello corpus")
        assertTrue(written.startsWith("✓"), written)
        assertEquals("hello corpus", Files.readString(target))

        // Outside every grant (the temp dir is a shell toolchain root, the file tools do not see it).
        val outside = Files.createDirectories(tempDir.resolve("elsewhere")).resolve("x.txt")
        val refused = fs.writeFile(outside.toString(), "nope")
        assertTrue(refused.contains("PERMISSION_DENIED"), refused)
        assertFalse(Files.exists(outside), "a refused write must not create the file")
    }

    @Test
    @DisplayName("the preset still bounds the grant: access read keeps an added folder read-only")
    fun presetStillBoundsTheGrant() = runBlocking {
        val ws = workspace
        val input = Files.writeString(corpus.resolve("input.txt"), "data")
        val policy = AgentAccessPolicy(preset = AccessPreset.READ).withAdditionalDirs(listOf(corpus))
        val fs = CodingComponents.fs(ws, policy)

        assertTrue(fs.readFile(input.toString()).contains("data"), "reading an added folder is scope, not power")

        val refused = fs.writeFile(corpus.resolve("notes.md").toString(), "x")
        assertTrue(refused.contains("PERMISSION_DENIED") || refused.contains("READ_ONLY"), refused)
        assertFalse(Files.exists(corpus.resolve("notes.md")))
    }

    @Test
    @DisplayName("the shell may use an added folder as its working directory, and still refuses others")
    fun shellReachesTheAddedFolder() = runBlocking {
        val ws = workspace
        val granted = CodingComponents.shell(ws, AgentAccessPolicy().withAdditionalDirs(listOf(corpus)))

        // The grant is an execution grant too: the folder is a valid working directory.
        val ran = granted.execute("echo ok", workingDir = corpus.toString())
        assertTrue(ran.contains("ok"), ran)

        // Without the grant the same working directory is an escape. (The command-argument guard
        // consults the same boundary authorizer `addedFolderIsInScope` pins, and any path under the
        // system temp directory is a shell toolchain root, so a temp probe could not tell the two
        // shells apart.)
        val walled = CodingComponents.shell(ws, AgentAccessPolicy())
        assertThrows<WorkspaceEscapeException> {
            runBlocking { walled.execute("echo ok", workingDir = corpus.toString()) }
        }

        // An ungranted absolute argument is still refused by the shell's own path guard.
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val outsideArg = home.resolve("b4-added-dirs-probe-${System.nanoTime()}").resolve("note.txt")
        assertFalse(walled.isAllowed("echo $outsideArg"), walled.explain("echo $outsideArg"))

        // The last expression is the assertion's value; a JUnit @Test method must return nothing.
        Unit
    }
}
