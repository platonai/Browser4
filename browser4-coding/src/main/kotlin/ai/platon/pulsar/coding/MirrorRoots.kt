package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Derives the real paths of a workspace's mirrored module roots **from the mirror's own manifest**.
 *
 * [WorkspaceMirror] links `<workspace>/<module>` to `<sourceRepo>/<module>` via a Windows junction or
 * a Unix symbolic link. When the file tools judge a path by its **real** location (see
 * [canonicalPath]), a write to `<workspace>/browser4-coding/src/...` resolves to the real checkout
 * outside the workspace. Without knowing those real targets, every module write would be refused as
 * out of scope — the whole point of `--fresh-workspace` is to keep the checkout writable through the
 * mirror.
 *
 * ## Why a manifest and not a scan
 *
 * The result is a **grant**: the boundary authorizer treats every returned root as writable, and
 * `windows-low-il` lowers its mandatory label so the sandboxed child can write there. Deriving it by
 * scanning the workspace for links let the *agent* decide what was granted — a task that created a
 * junction to an unrelated checkout turned that checkout into a writable root (and, through
 * [sourceRoot], potentially into a whole-checkout grant). 2026-11-20 review, issue S1.
 *
 * The manifest is written by the backend itself at mirror time, next to the workspace and therefore
 * **outside** it (`<parent>/<workspaceName>.mirror-manifest`), where neither the string layer nor the
 * sandbox account can reach it. It records the exact `link → target` pairs that were created, so
 * [derive] can require that a link still resolves to **the target the backend linked**, and that the
 * target still lives inside the recorded checkout. Anything else — a stale entry, a re-pointed
 * junction, a link the agent added — is ignored, so the grant follows what the backend did rather
 * than the workspace's current topology.
 *
 * ## Bounded on purpose
 *
 * A manifest is a handful of lines; the walk this object used to perform (bounded by depth and entry
 * budget) is gone, which also removes its cost from the first tool call of a session. A recorded
 * link deeper than [MAX_MODULE_DEPTH] is ignored: a mirror can never live there.
 */
internal object MirrorRoots {

    private val logger = LoggerFactory.getLogger(MirrorRoots::class.java)

    /** Suffix of the manifest that sits next to (never inside) the workspace. */
    const val MANIFEST_SUFFIX = ".mirror-manifest"

    /** First line of a manifest written by this schema. */
    private const val MANIFEST_HEADER = "browser4-mirror-manifest v1"

    private const val SOURCE_PREFIX = "source="
    private const val LINK_PREFIX = "link="
    private const val TARGET_PREFIX = "target="

    /**
     * How deep below the workspace root a mirrored module can sit.
     *
     * `WorkspaceMirror` links `<workspace>/<module>` and nested reactor modules such as
     * `<workspace>/browser4-plugins/browser4-testprobe`; one extra level is kept as margin.
     */
    const val MAX_MODULE_DEPTH = 4

    /** Depth limit for walking a nested module up to its reactor root. */
    private const val MAX_SOURCE_ROOT_HOPS = 4

    /** Files/dirs that make a directory a checkout root rather than a module or an accident. */
    private val CHECKOUT_MARKERS = listOf(
        "pom.xml", "Cargo.toml", "package.json", "pyproject.toml", "setup.py", "go.mod",
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "Gemfile",
    )

    /**
     * A checkout and the links [WorkspaceMirror] created into it.
     *
     * @property source the checkout the workspace mirrors
     * @property entries link path relative to the workspace → absolute target it was linked to
     */
    data class Manifest(val source: Path, val entries: Map<String, Path>)

    /**
     * The manifest path for [workspaceRoot].
     *
     * The workspace is canonicalised first, so the same workspace reached through a symlink and
     * through its real path resolve to one file. The file is a **sibling** of the workspace: a path
     * inside it would be writable by the agent, which is exactly what this manifest must not be.
     */
    fun manifestFile(workspaceRoot: Path): Path {
        val root = canonical(workspaceRoot)
        val name = root.fileName?.toString() ?: return root.resolve(MANIFEST_SUFFIX)
        return root.resolveSibling(name + MANIFEST_SUFFIX)
    }

    /**
     * Records the `link → target` pairs [WorkspaceMirror] created for [workspaceRoot].
     *
     * Written atomically (temp file + rename) so a crash never leaves a half manifest that would be
     * read as a shorter grant list. A failure is logged, not thrown: the mirror itself already
     * worked, and the consequence — mirrored writes refused — is a loud, recoverable refusal.
     */
    fun writeManifest(workspaceRoot: Path, source: Path, entries: Map<String, Path>) {
        val root = canonical(workspaceRoot)
        val checkout = canonical(source)
        val file = manifestFile(root)
        val accepted = entries.asSequence()
            .filter { (link, _) -> isSafeLinkPath(link) }
            .sortedBy { it.key }
            .toList()
        val text = buildString {
            appendLine(MANIFEST_HEADER)
            append(SOURCE_PREFIX).appendLine(checkout)
            for ((link, target) in accepted) {
                append(LINK_PREFIX).appendLine(link)
                append(TARGET_PREFIX).appendLine(canonical(target))
            }
        }
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = Files.createTempFile(file.parent, "mirror-", ".tmp")
            try {
                Files.writeString(tmp, text)
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                runCatching { Files.deleteIfExists(tmp) }
            }
            logger.debug("mirror manifest written: {} ({} link(s))", file, accepted.size)
        }.onFailure { logger.warn("could not record the mirror manifest {}: {}", file, it.message) }
    }

    /** The manifest of [workspaceRoot], or `null` when there is none or it is unreadable. */
    fun readManifest(workspaceRoot: Path): Manifest? {
        val file = manifestFile(workspaceRoot)
        if (!Files.isRegularFile(file)) return null
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return null
        if (lines.firstOrNull()?.trim() != MANIFEST_HEADER) return null
        var source: Path? = null
        var pendingLink: String? = null
        val entries = LinkedHashMap<String, Path>()
        for (line in lines.drop(1)) {
            val text = line.trim()
            when {
                text.startsWith(SOURCE_PREFIX) ->
                    source = runCatching { Path.of(text.removePrefix(SOURCE_PREFIX)) }.getOrNull()
                text.startsWith(LINK_PREFIX) ->
                    pendingLink = text.removePrefix(LINK_PREFIX).takeIf { isSafeLinkPath(it) }
                text.startsWith(TARGET_PREFIX) -> {
                    val link = pendingLink ?: continue
                    val target = runCatching { Path.of(text.removePrefix(TARGET_PREFIX)) }.getOrNull() ?: continue
                    entries[link] = target
                    pendingLink = null
                }
            }
        }
        val checkout = source ?: return null
        return Manifest(checkout, entries)
    }

    /**
     * Real paths of the mirrored roots under [workspaceRoot].
     *
     * @return the canonical real paths the manifest's links were created to, each re-verified against
     *   the recorded link and checkout; empty when there is no manifest, no link, or a link moved.
     */
    fun derive(workspaceRoot: Path): Set<Path> {
        val root = canonical(workspaceRoot)
        if (!Files.isDirectory(root)) return emptySet()
        val manifest = readManifest(root) ?: return emptySet()
        if (!Files.isDirectory(manifest.source)) return emptySet()
        val checkout = canonical(manifest.source)

        val roots = linkedSetOf<Path>()
        for ((linkName, recorded) in manifest.entries) {
            val link = runCatching { root.resolve(linkName).toAbsolutePath().normalize() }.getOrNull() ?: continue
            if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) continue
            val real = runCatching { link.toRealPath() }.getOrNull() ?: continue
            // A real directory resolves to itself: whatever sits there now is not the mirror's link.
            if (samePath(real, link)) continue
            // A link that resolves back inside the workspace grants nothing new.
            if (real.startsWith(root)) continue
            // The grant is for the target the backend linked — never for whatever a link happens to
            // point at today (a re-pointed junction must not widen the boundary).
            if (!samePath(real, recorded)) continue
            // …and that target must still be inside the checkout the mirror recorded, so a manifest
            // line cannot be reused to name a directory the workspace was never given.
            if (!real.startsWith(checkout)) continue
            if (!isCheckoutLinkedModule(real)) continue
            roots.add(real.normalize())
        }
        return roots
    }

    /**
     * The checkout [workspaceRoot] mirrors, or `null` when the workspace is not a mirror.
     *
     * The checkout comes from the manifest, so it is the one the backend chose for this task (the
     * repository the backend runs from), not a common ancestor inferred from the links the workspace
     * currently holds. It is accepted only while at least one manifest link still resolves to it,
     * and only when it really looks like a checkout root ([CHECKOUT_MARKERS]) — a workspace whose
     * links were replaced gains no source grant.
     *
     * Its write scope is a boundary grant ([CodingComponents.scopeManifest]'s `SOURCE` entry); the
     * protected files/dirs and the credential rails stay in force.
     *
     * @param workspaceRoot the agent workspace (`<app data>/agent/<date>/<uuid>/workspace.<task>`)
     */
    fun sourceRoot(workspaceRoot: Path): Path? {
        val root = canonical(workspaceRoot)
        val manifest = readManifest(root) ?: return null
        // No valid link ⇒ no mirror in force ⇒ no source grant.
        if (derive(root).isEmpty()) return null
        val source = canonical(manifest.source)
        if (source.startsWith(root)) return null
        return source.takeIf { isCheckoutRoot(it) }
    }

    /**
     * Whether a link target is a module of a checkout, i.e. a real mirror rather than an arbitrary
     * folder a workspace link points at.
     *
     * Kept as defense in depth on top of the manifest: even a recorded target is only a grant when it
     * still carries the markers of a module that belongs to some checkout.
     */
    private fun isCheckoutLinkedModule(real: Path): Boolean {
        if (!runCatching { Files.isDirectory(real) }.getOrDefault(false)) return false
        var candidate: Path? = runCatching { real.toAbsolutePath().normalize() }.getOrNull() ?: return false
        var hops = 0
        while (candidate != null && hops <= MAX_SOURCE_ROOT_HOPS) {
            if (isCheckoutRoot(candidate)) return true
            candidate = candidate.parent
            hops++
        }
        return false
    }

    private fun isCheckoutRoot(dir: Path): Boolean =
        Files.isDirectory(dir.resolve(".git")) ||
            Files.isRegularFile(dir.resolve(".git")) ||
            CHECKOUT_MARKERS.any { Files.isRegularFile(dir.resolve(it)) }

    /** A link path must stay relative and shallow; anything else cannot be a mirror entry. */
    private fun isSafeLinkPath(link: String): Boolean {
        val normalized = link.trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains(':')) return false
        val parts = normalized.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return parts.size <= MAX_MODULE_DEPTH
    }

    /** The workspace as the OS knows it, so two spellings of one directory share one manifest. */
    private fun canonical(path: Path): Path =
        runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    private fun samePath(a: Path, b: Path): Boolean {
        val win = System.getProperty("os.name").lowercase().contains("win")
        return if (win) a.toString().equals(b.toString(), ignoreCase = true) else a == b
    }
}
