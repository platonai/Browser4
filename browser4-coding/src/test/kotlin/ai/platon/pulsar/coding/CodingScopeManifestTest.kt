package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan P0-2 — the scope manifest is the single source of truth for the boundary grants.
 *
 * The skeleton has to answer three questions before P0-1 data exists: which roots are in scope,
 * which grant sees which root, and what narrowing advice looks like once usage data arrives. These
 * tests pin those answers against the parts that already exist ([CodingComponents.toolchainRoots],
 * [CodingComponents.boundaryAuthorizer]) so the manifest cannot silently diverge from enforcement.
 */
class CodingScopeManifestTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path get() = tempDir.resolve("ws")

    @Test
    @DisplayName("the manifest covers the workspace and every toolchain root exactly once")
    fun testManifestComposition() {
        Files.createDirectories(workspace)
        val manifest = CodingComponents.scopeManifest(workspace)

        val workspaceEntry = manifest.single { it.kind == ScopeKind.WORKSPACE }
        assertEquals(workspace.toRealPath(), workspaceEntry.path, "the workspace entry must use the real root")
        assertEquals(setOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY), workspaceEntry.grants)

        val toolchain = manifest.filter { it.kind == ScopeKind.TOOLCHAIN }
        assertEquals(
            CodingComponents.toolchainRoots().toSet(),
            toolchain.map { it.path }.toSet(),
            "toolchainEntries and toolchainRoots drifted apart",
        )
        assertTrue(
            toolchain.all { it.grants == setOf(CodingComponents.CAP_SHELL_BOUNDARY) },
            "toolchain roots are shell-only by design: ${toolchain.map { it.grants }}",
        )

        val ids = manifest.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "scope entry ids must be unique: $ids")
    }

    @Test
    @DisplayName("the boundary authorizer allows exactly what the manifest declares")
    fun testAuthorizerMatchesManifest() {
        Files.createDirectories(workspace)
        val manifest = CodingComponents.scopeManifest(workspace)
        val authorizer = CodingComponents.boundaryAuthorizer(workspace)

        for (entry in manifest) {
            for (grant in entry.grants) {
                val decision = authorizer.decide(
                    Request(
                        tool = "test.scope",
                        effects = entry.effects,
                        targets = listOf(Target.PathTarget(entry.path)),
                        capabilityId = grant,
                    ),
                )
                assertEquals(Decision.Allow, decision, "manifest entry ${entry.id} must be allowed under $grant")
            }
        }

        // A path under no entry stays refused in both grants — the manifest claims nothing extra.
        val outside = tempDir.root.resolve("b4-scope-outside-probe")
        for (grant in listOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY)) {
            val decision = authorizer.decide(
                Request(
                    tool = "test.scope",
                    effects = setOf(Effect.FS_WRITE),
                    targets = listOf(Target.PathTarget(outside)),
                    capabilityId = grant,
                ),
            )
            assertTrue(decision is Decision.Deny, "$grant must refuse $outside, got $decision")
        }
    }

    @Test
    @DisplayName("narrowing advice needs observation data and never targets the workspace")
    fun testNarrowingSuggestions() {
        Files.createDirectories(workspace)
        val manifest = CodingComponents.scopeManifest(workspace)
        val toolchain = manifest.first { it.kind == ScopeKind.TOOLCHAIN }

        // No data means no advice: an unobserved entry is unknown, not unused.
        assertTrue(ScopeNarrowing.suggestions(manifest, emptyMap()).isEmpty())

        val suggestions = ScopeNarrowing.suggestions(
            manifest,
            mapOf(toolchain.id to ScopeNarrowing.DEFAULT_UNUSED_TASKS, "workspace" to 99),
        )
        assertEquals(1, suggestions.size, "suggestions: $suggestions")
        assertEquals(toolchain.id, suggestions.single().entry.id)
        assertEquals(ScopeNarrowing.DEFAULT_UNUSED_TASKS, suggestions.single().unusedTasks)

        // Just below the threshold is not a suggestion yet.
        assertTrue(
            ScopeNarrowing.suggestions(
                manifest,
                mapOf(toolchain.id to ScopeNarrowing.DEFAULT_UNUSED_TASKS - 1),
            ).isEmpty(),
        )
    }
}
