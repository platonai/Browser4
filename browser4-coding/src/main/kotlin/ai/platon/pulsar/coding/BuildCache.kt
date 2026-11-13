package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Content fingerprint of one `mvn -pl <module> -am <goals>` invocation.
 *
 * The fingerprint is what makes a build **short-circuit** sound: it identifies the exact
 * inputs a previous build already compiled, so a second call with identical inputs can
 * return the recorded outcome instead of starting Maven (plan P0-1).
 *
 * Why content digests and not `CodingAgentFileSystem.mutationCount`:
 * - the counter is reset by `beginTask` (`CodingAgentFileSystem.clearMutations`), so it
 *   cannot name "the state a build ran against";
 * - writes that bypass the file system (`coding.shell` running `sed`/`git apply`, an
 *   external formatter) never reach `recordMutation` at all.
 *
 * A wrong fingerprint direction is asymmetric: over-invalidating costs a rebuild,
 * under-invalidating reports a **stale success** — exactly the "false green" the
 * verification authority layer exists to prevent. Hence the digest is over file content,
 * recomputed on every call.
 *
 * @property module the resolved Maven selector (`browser4-coding`, `browser4-core/browser4-browser`)
 * @property goals the normalized goal list (`compile`, `test`, …)
 * @property skipTests whether `-DskipTests` is appended
 * @property sourceDigest SHA-256 over every build-relevant file in the workspace (see [SourceDigest])
 * @property launcher the Maven launcher the build would use (`mvnw.cmd`, an absolute path, or `mvn`)
 * @property toolchain JVM + OS identity; a different toolchain may compile the same sources differently
 */
data class BuildFingerprint(
    val module: String,
    val goals: String,
    val skipTests: Boolean,
    val sourceDigest: String,
    val launcher: String,
    val toolchain: String,
) {
    /** Short, human-facing form — what `cached@<id>` in a verdict means. */
    val id: String get() = sourceDigest.take(16)

    /**
     * Cache bucket: everything except the content digest.
     *
     * One bucket per (root, module, goals, skipTests, launcher, toolchain) keeps the cache
     * bounded by the shapes a task actually calls, and makes "the source changed" replace
     * the entry rather than accumulate one per digest.
     */
    internal val bucket: String
        get() = listOf(module, goals, skipTests.toString(), launcher, toolchain).joinToString("|")
}

/**
 * Process-wide short-circuit cache for `MavenBuildSupport.build` (plan P0-1).
 *
 * Semantics, deliberately narrow:
 * - only **successful, non-timed-out** builds are stored: a transient failure (OOM, a
 *   locked jar) must never become a sticky verdict, and a timeout says nothing about the
 *   sources;
 * - a lookup compares the **content digest**, so a stale entry is dropped instead of served;
 * - only `compile`-class goals are cacheable (see `MavenBuildSupport.isCacheableBuild`);
 *   `test` and `verification` never are.
 *
 * Escape hatch: `-Dbrowser4.agent.buildCache.enabled=false` disables lookups *and* stores,
 * returning the pre-P0-1 behaviour exactly.
 *
 * @param maxEntries upper bound on distinct buckets kept (oldest evicted first)
 * @param enabledProvider read on every access, so a test or a JVM property change takes effect
 * @param clock injected for deterministic tests
 */
class BuildCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val enabledProvider: () -> Boolean = Companion::enabled,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val builtAt: Long, val fingerprint: BuildFingerprint, val result: BuildResult)

    private val entries = ConcurrentHashMap<String, Entry>()

    val enabled: Boolean get() = enabledProvider()

    /** Number of stored buckets (tests and `/stats` style diagnostics). */
    val size: Int get() = entries.size

    /**
     * The outcome a previous build of the same inputs produced, or `null` when Maven must run.
     *
     * A bucket whose digest differs from [fingerprint] is dropped: the inputs changed, and
     * the old answer describes a different tree.
     */
    fun lookup(root: Path?, fingerprint: BuildFingerprint): BuildResult? {
        if (!enabled) return null
        val key = key(root, fingerprint)
        val entry = entries[key] ?: return null
        if (entry.fingerprint.sourceDigest != fingerprint.sourceDigest) {
            entries.remove(key)
            return null
        }
        logger.info(
            "build cache hit: {} {} (fingerprint {}, built {})",
            fingerprint.module, fingerprint.goals, fingerprint.id, entry.builtAt,
        )
        return entry.result.copy(
            cached = true,
            fingerprint = fingerprint.id,
            builtAt = entry.builtAt,
            output = cachedOutput(entry.builtAt),
        )
    }

    /** Record a successful build. Failures and timeouts are never stored. */
    fun put(root: Path?, fingerprint: BuildFingerprint, result: BuildResult) {
        if (!enabled || !result.success) return
        entries[key(root, fingerprint)] = Entry(clock(), fingerprint, result)
        evictIfCrowded()
    }

    /** Drop every bucket of one workspace (a task boundary — see `CodingAgentFileSystem.beginTask`). */
    fun invalidate(root: Path?) {
        val prefix = "${rootKey(root)}|"
        entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) }
    }

    /** Drop everything (tests, or a deployment-wide setting changed). */
    fun clear() = entries.clear()

    private fun key(root: Path?, fingerprint: BuildFingerprint): String =
        "${rootKey(root)}|${fingerprint.bucket}"

    private fun evictIfCrowded() {
        if (entries.size <= maxEntries.coerceAtLeast(1)) return
        val excess = entries.size - maxEntries
        entries.entries
            .sortedBy { it.value.builtAt }
            .take(excess)
            .forEach { entries.remove(it.key) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BuildCache::class.java)

        /** Distinct buckets kept in one JVM (a coding task uses a handful). */
        const val MAX_ENTRIES = 64

        /** The instance every `MavenBuildSupport` shares inside one JVM. */
        val shared: BuildCache by lazy { BuildCache() }

        /** `-Dbrowser4.agent.buildCache.enabled` — `false` restores the pre-P0-1 behaviour. */
        fun enabled(): Boolean =
            System.getProperty("browser4.agent.buildCache.enabled", "true").toBoolean()

        /** `cached (no source change since …)` is the only output a cached build can have. */
        fun cachedOutput(builtAt: Long): String =
            "cached (no source change since ${BuildResult.stamp(builtAt)})"

        /**
         * Canonical bucket root. Windows paths are case-insensitive, so the key is
         * lower-cased there to keep one bucket per directory.
         */
        fun rootKey(root: Path?): String {
            val raw = root?.toAbsolutePath()?.normalize()?.toString() ?: "-"
            val windows = System.getProperty("os.name").lowercase().contains("win")
            return if (windows) raw.lowercase() else raw
        }
    }
}

/**
 * Content digest of every file a Maven compile of this workspace depends on.
 *
 * Build-relevant means `*.kt`, `*.kts`, `*.java`, `*.xml` (poms, checkstyle/spotless style
 * configs) anywhere in the tree, plus `.mvn/wrapper/maven-wrapper.properties`. Resources,
 * docs, `target/` output and agent scratch directories are excluded: they do not change
 * what the compiler sees, and including them would evict the cache on every build (Maven
 * writes into `target/` while it runs).
 *
 * The walk follows links (a mirrored workspace links modules back to the checkout, and the
 * old `isolation=off` mode is still reachable) with a visited-directory guard so a junction
 * cycle cannot loop forever.
 */
internal object SourceDigest {
    private val logger = LoggerFactory.getLogger(SourceDigest::class.java)

    private val BUILD_RELEVANT_EXTENSIONS = setOf("kt", "kts", "java", "xml")

    /** Directories whose contents never affect a compile. */
    private val SKIPPED_DIRS = setOf(
        "target", "build", "out", "bin", "node_modules",
        ".git", ".gradle", ".idea", ".vscode", ".mvn",
        ".browser4", ".b4scratch", ".b4inputs", ".claude", ".worktrees",
    )

    /** A digest over more files than this is not worth computing on the tool path. */
    const val MAX_FILES = 20_000

    /** Explicit non-`*.xml` build inputs outside the walked extension set. */
    private val EXTRA_BUILD_FILES = listOf(".mvn/wrapper/maven-wrapper.properties")

    /**
     * @return `sha256:<hex>` over every build-relevant file, or `null` when the root is
     *   unknown/unreadable or the tree is too large to hash per call (no fingerprint → the
     *   cache is bypassed rather than guessed)
     */
    fun digest(root: Path?): String? {
        if (root == null || !runCatching { Files.isDirectory(root) }.getOrDefault(false)) return null

        val files = mutableListOf<Path>()
        val visited = HashSet<Path>()
        try {
            Files.walkFileTree(root, setOf(FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root) {
                        if (dir.fileName?.toString() in SKIPPED_DIRS) return FileVisitResult.SKIP_SUBTREE
                        // A junction cycle (or a hard-linked tree) must not loop.
                        val real = runCatching { dir.toRealPath() }.getOrNull()
                        if (real != null && !visited.add(real)) return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && isBuildRelevant(file)) files.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                    // An unreadable entry is a reason not to trust a cache hit, not a reason
                    // to fail the build: record it by name and keep hashing.
                    FileVisitResult.CONTINUE
            })
        } catch (e: Exception) {
            logger.warn("build fingerprint: cannot walk {} ({})", root, e.message)
            return null
        }

        EXTRA_BUILD_FILES
            .map { root.resolve(it) }
            .filter { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
            .forEach { files.add(it) }

        if (files.size > MAX_FILES) {
            logger.info("build fingerprint: {} has {} relevant files (> {}), cache bypassed", root, files.size, MAX_FILES)
            return null
        }

        val relative = files
            .map { file -> root.relativize(file).toString().replace('\\', '/') to file }
            .sortedBy { it.first }

        val md = MessageDigest.getInstance("SHA-256")
        for ((name, file) in relative) {
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(0.toByte())
            val bytes = runCatching { Files.readAllBytes(file) }.getOrNull()
            if (bytes == null) {
                // Fail-safe: an unreadable file must not look like "unchanged".
                md.update("unreadable".toByteArray(Charsets.UTF_8))
                continue
            }
            md.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            md.update(bytes)
        }
        return "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isBuildRelevant(file: Path): Boolean {
        val name = file.fileName?.toString() ?: return false
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in BUILD_RELEVANT_EXTENSIONS
    }
}
