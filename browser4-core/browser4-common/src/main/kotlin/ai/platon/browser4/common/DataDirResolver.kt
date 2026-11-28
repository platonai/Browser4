package ai.platon.browser4.common

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Unified resolver for Browser4 data directory paths.
 *
 * Prior to 4.17 the codebase had two parallel path-resolution schemes with
 * subtly different semantics:
 *
 *  - `app.data.dir` → AppContext.APP_DATA_DIR (root, defaults to `~/.browser4`)
 *  - `browser4.data.dir` → user.home (or explicit) + `.browser4/data/...`
 *
 * Code that chose the wrong scheme would silently place data on the wrong disk
 * or under a different user. This class introduces one new property —
 * [PROPERTY_ROOT] — that names the root directly, derives every sub-directory
 * from it, and keeps both old properties as a compatibility fallback chain.
 *
 * ## Property priority
 *
 * 1. `browser4.data.root` (new — explicit root)
 * 2. `app.data.dir`      (AppContext — also a root)
 * 3. `browser4.data.dir` (old scheme — treated as root of `~`, so we append
 *    `.browser4`; this preserves the historical behaviour where code said
 *    `System.getProperty("browser4.data.dir", user.home)` + `.browser4/data/...`)
 * 4. Default: `~/.browser4`
 *
 * If a user sets **only** `browser4.data.dir=D:\b4root` the resolved root
 * becomes `D:\b4root\.browser4`. If they set **only** `browser4.data.root=D:\b4root`
 * the resolved root is exactly `D:\b4root`.
 *
 * ## Sub-directory overrides
 *
 * Each large sub-system can relocate independently via its own property:
 *
 *   - `browser4.data.crawl.dir`   → Crawl tasks + checkpoints
 *   - `browser4.data.agent.dir`   → Agent tasks + event logs
 *   - `browser4.data.swarm.dir`   → Swarm task responses
 *   - `browser4.data.knowledge.dir` → Knowledge store (PEM)
 *
 * When absent they are derived from the resolved root, preserving the
 * historical layout. Setting one of them to an absolute path is the simplest
 * way to move only a single sub-system onto a faster/larger disk.
 */
object DataDirResolver {

    // ------------------------------------------------------------------
    // Property names
    // ------------------------------------------------------------------

    /** Canonical data-root property — introduced in 4.17. */
    const val PROPERTY_ROOT = "browser4.data.root"

    /** Legacy AppContext property (root). */
    const val PROPERTY_APP_DATA_DIR = "app.data.dir"

    /** Legacy property that named the parent of `.browser4/` (not the root). */
    const val PROPERTY_LEGACY_PARENT = "browser4.data.dir"

    /** Legacy knowledge-store property — also honoured by [KnowledgeStore]. */
    const val PROPERTY_KNOWLEDGE_DIR = "knowledge.dir"

    // Sub-directory overrides ------------------------------------------------

    const val PROPERTY_CRAWL_DIR = "browser4.data.crawl.dir"
    const val PROPERTY_AGENT_DIR = "browser4.data.agent.dir"
    const val PROPERTY_SWARM_DIR = "browser4.data.swarm.dir"
    const val PROPERTY_LOGS_DIR = "browser4.data.logs.dir"

    // Defaults ---------------------------------------------------------------

    /** Top-level directory name under the resolved parent. */
    private const val BROWSER4_DIR = ".browser4"

    /** Default name for the data sub-tree under the root. */
    private const val DATA_SUBDIR = "data"

    // ------------------------------------------------------------------
    // Root resolution
    // ------------------------------------------------------------------

    /**
     * Resolve the Browser4 data root by walking the compatibility chain.
     *
     * @return the absolute root path (always non-null and non-empty)
     */
    fun resolveRoot(): Path =
        firstNonBlank(
            System.getProperty(PROPERTY_ROOT),
            System.getProperty(PROPERTY_APP_DATA_DIR),
            legacyAsRoot(System.getProperty(PROPERTY_LEGACY_PARENT)),
        )?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home", "."), BROWSER4_DIR)

    // ------------------------------------------------------------------
    // Sub-directory resolution
    // ------------------------------------------------------------------

    /** The Crawl data directory — tasks JSONL + checkpoints. */
    fun crawlDir(): Path =
        absoluteOrResolve(PROPERTY_CRAWL_DIR) { root().resolve(DATA_SUBDIR).resolve("crawl") }

    /** The Agent data directory — tasks JSONL + event logs. */
    fun agentDir(): Path =
        absoluteOrResolve(PROPERTY_AGENT_DIR) { root().resolve(DATA_SUBDIR).resolve("agent") }

    /** The Swarm data directory — responses JSONL. */
    fun swarmDir(): Path =
        absoluteOrResolve(PROPERTY_SWARM_DIR) { root().resolve(DATA_SUBDIR).resolve("swarm") }

    /** The Knowledge store (PEM) directory. */
    fun knowledgeDir(): Path =
        absoluteOrResolve(PROPERTY_KNOWLEDGE_DIR) { root().resolve("knowledge") }

    /** The log directory — default layout mirrors the existing `logging.dir` relative default. */
    fun logsDir(): Path =
        absoluteOrResolve(PROPERTY_LOGS_DIR) { root().resolve("logs") }

    /** Browser profile root (Chrome `--user-data-dir`). */
    fun browserDir(): Path = root().resolve("browser")

    /** Agent memory root — sqlite index + event log. */
    fun memoryDir(): Path = root().resolve("memory")

    /** Coworker file-queue root. */
    fun coworkerDir(): Path = root().resolve("coworker")

    /** Configuration tree (linked to user-global in dev mode). */
    fun configDir(): Path = root().resolve("config")

    /** Agent skill drafts directory. */
    fun skillDraftsDir(): Path = root().resolve("agent").resolve("skills-drafts")

    // ------------------------------------------------------------------
    // Low-level helpers (package-private for tests)
    // ------------------------------------------------------------------

    /** Current root — memoised per call; the JVM is long-lived so we avoid caching. */
    internal fun root(): Path = resolveRoot()

    /**
     * If [prop] is set and non-blank, return it as an absolute path (or relative, as given).
     * Otherwise run [fallback] against the resolved root.
     */
    private fun absoluteOrResolve(prop: String, fallback: () -> Path): Path {
        val configured = System.getProperty(prop)?.trim()
        if (!configured.isNullOrEmpty()) {
            return Path.of(configured)
        }
        return fallback()
    }

    /**
     * Legacy [PROPERTY_LEGACY_PARENT] held the **parent** of `.browser4/`, not the root.
     * If it was set we append the `.browser4` suffix to preserve historical behaviour.
     */
    private fun legacyAsRoot(parent: String?): String? {
        val p = parent?.takeIf { it.isNotBlank() } ?: return null
        return Paths.get(p, BROWSER4_DIR).toString()
    }

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }
}
