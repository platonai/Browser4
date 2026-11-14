package ai.platon.pulsar.coding

import java.nio.file.Path

/**
 * Plan P0-2 — the explicit capability scope manifest.
 *
 * Today the boundary scope is computed inline ([CodingComponents.buildBoundaryAuthorizer]) and
 * nobody can answer "what exactly is in scope, and which part of it did any task actually use?"
 * without reading the code. This turns the computed roots into named entries, so the observation
 * data collected in P0-1 has a place to attach to and narrowing advice has something to name.
 *
 * Entries are *roots*, not globs: an entry covers itself and every descendant, matching [Scope].
 * Every entry states which boundary grants include it, because the file system and the shell
 * deliberately see different scopes ([CodingComponents.CAP_FS_BOUNDARY] canonical, workspace +
 * mirrors only; [CodingComponents.CAP_SHELL_BOUNDARY] lexical, plus toolchain roots).
 */
enum class ScopeKind {
    /** The workspace root itself — the boundary's purpose, never a narrowing candidate. */
    WORKSPACE,

    /** A mirrored module's real checkout root ([MirrorRoots]). */
    MIRROR,

    /**
     * The checkout the workspace mirrors ([MirrorRoots.sourceRoot]) — the code this task develops.
     *
     * Carries read **and** write in both boundary grants. The mirror links the reactor modules, so
     * a module path was already writable through the junction; the checkout root adds what the
     * mirror cannot provide: `.git`, the non-module folders (`bin/`, `cli/`, `docs/`) and the
     * absolute spelling of a module path. Without it a self-development task could read the
     * repository but had to rewrite sources from a throwaway python script, because every
     * `coding.write`/`replace` on the checkout came back
     * `PERMISSION_DENIED | code=PATH_DENIED … readable roots are read-only` (2026-11-14 run).
     *
     * The write grant is not a licence: protected files and directories ([ScopeKind.WORKSPACE]'s
     * rails) still refuse `pom.xml`, `VERSION`, `bin/build.ps1`, `.github/workflows` and the like,
     * and the credential rails never depended on the boundary. Not a narrowing candidate — it is
     * what the task is for.
     */
    SOURCE,

    /** A toolchain location outside the workspace (JDK, package caches, temp, OS dir). */
    TOOLCHAIN,

    /**
     * An operator-readable root outside the workspace ([ReadablePaths]): Browser4's data root, the
     * source checkout, or a `browser4.agent.readPaths` entry.
     *
     * Carries [Effect.FS_READ] only — these roots are in the grants' read scope, never their write
     * scope — and is not a narrowing candidate: it comes from the environment, not from a derived
     * boundary ([ScopeNarrowing]).
     */
    READABLE,

    /**
     * A folder granted to this task besides its workspace (`agent run --add-dir`).
     *
     * Carries both effects (read and write): the flag grants access to the folder, while the
     * access preset still decides whether anything may be written there. Not a narrowing
     * candidate — it was requested explicitly for one task, so "it went unused" is not evidence
     * that it should be dropped.
     */
    ADDED,
}

/**
 * One named root in the effective boundary scope.
 *
 * @property id stable key used by observation data and narrowing advice; never a display string
 * @property path the root, already absolute and (for canonical grants) resolved to its real path
 * @property kind why this root is in scope; [ScopeKind.WORKSPACE] is not a narrowing candidate
 * @property effects the effects this root is in scope for
 * @property grants the boundary grants that include it
 */
data class ScopeEntry(
    val id: String,
    val path: Path,
    val kind: ScopeKind,
    val effects: Set<Effect>,
    val grants: Set<CapabilityId>,
)

/**
 * A narrowing recommendation for one [ScopeEntry], produced by [ScopeNarrowing].
 *
 * @property entry the entry that stayed unused
 * @property unusedTasks how many consecutive observed tasks did not touch it
 * @property advice what to do about it, phrased so it can be pasted into a config decision
 */
data class ScopeNarrowingSuggestion(
    val entry: ScopeEntry,
    val unusedTasks: Int,
    val advice: String,
)

/**
 * Turns observation data into narrowing advice (design §12).
 *
 * This is the skeleton of P0-2: the input is produced by P0-1's daily snapshots, and the *absence*
 * of data yields *no* advice — an unobserved entry is unknown, not unused. Only an entry the data
 * says went untouched for [threshold] consecutive tasks is recommended for narrowing.
 */
object ScopeNarrowing {

    /** Consecutive untouched tasks required before recommending a narrowing. */
    const val DEFAULT_UNUSED_TASKS = 7

    /**
     * @param manifest the entries to consider, normally [CodingComponents.scopeManifest]
     * @param consecutiveUnusedTasks observed untouched-task count per entry id; entries absent from
     *   this map are treated as unobserved and are not suggested
     * @param threshold untouched tasks required before suggesting (default [DEFAULT_UNUSED_TASKS])
     */
    fun suggestions(
        manifest: List<ScopeEntry>,
        consecutiveUnusedTasks: Map<String, Int>,
        threshold: Int = DEFAULT_UNUSED_TASKS,
    ): List<ScopeNarrowingSuggestion> = manifest
        // The workspace root is what the boundary is *for*; dropping it is not a narrowing. A
        // readable root is operator/environment configuration too — "it went unused" says nothing
        // about whether reading it is legitimate, so it is never a narrowing candidate either.
        // An added folder (`--add-dir`) was requested for one task, so the same holds for it, and a
        // source checkout is the code the task exists to change.
        .filter {
            it.kind != ScopeKind.WORKSPACE && it.kind != ScopeKind.READABLE &&
                it.kind != ScopeKind.ADDED && it.kind != ScopeKind.SOURCE
        }
        .mapNotNull { entry ->
            val unused = consecutiveUnusedTasks[entry.id] ?: return@mapNotNull null
            if (unused < threshold) return@mapNotNull null
            ScopeNarrowingSuggestion(
                entry = entry,
                unusedTasks = unused,
                advice = buildString {
                    append("'").append(entry.id).append("' was not used by any task in the last ")
                    append(unused).append(" runs; replace the derived root with an explicit declared root, ")
                    append("or drop it (")
                    append(if (entry.grants.contains(CodingComponents.CAP_FS_BOUNDARY)) "file tools and shell" else "shell")
                    append(" scope).")
                },
            )
        }
}
