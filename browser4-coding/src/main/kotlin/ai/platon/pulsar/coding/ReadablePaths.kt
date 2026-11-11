package ai.platon.pulsar.coding

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Roots the coding tools may **read** although they live outside the task workspace.
 *
 * The workspace boundary exists so a task cannot wander the machine. It used to be absolute,
 * though, and Browser4's own files are outside every task workspace: logs under
 * `<app data>/logs`, task data under `<app data>/data`, per-task workspaces under
 * `<app data>/agent/...` and `<app data>/workspaces`, plus the checkout the backend was started
 * from. A run asked to "look at the previous task's log" therefore failed with
 * `PERMISSION_DENIED | code=PATH_DENIED` — for the agent's own bookkeeping, which is the one
 * place it should be able to look. The default roots close that gap:
 *
 * - the Browser4 data root — `app.data.dir` when set, else `~/.browser4` (this includes the
 *   app's own `tmp/`, so "the Browser4 temp folder" is covered);
 * - the source checkout the backend runs from, when there is one
 *   ([WorkspaceMirror.defaultSourceRepo]); a packaged runtime has none.
 *
 * Operators add folders with the JVM property `browser4.agent.readPaths`
 * (entries separated by the platform path separator, `~` expanded, blanks ignored):
 *
 * ```
 * BROWSER4_SERVER_OPTS="-Dbrowser4.agent.readPaths=D:/corpus;%TEMP%" browser4-cli open
 * ```
 *
 * ## Contract
 *
 * - **Read-only.** `write`/`append`/`replace`/`delete`/`copy`/`move` stay confined to the
 *   workspace and its mirrored module roots; a write into a readable root is refused (and counted
 *   as [PermissionAudit.CODE_READ_ONLY]). The deliberate, audited escape hatch remains
 *   `browser4.agent.allowExternalAccess=true`.
 * - **Independent of the access preset.** `--access read` narrows workspace *power*; reading these
 *   roots is not workspace power, so every preset keeps it. A read-only task can still read logs.
 * - **Credentials stay refused.** [ALWAYS_REFUSED] (the OS credential stores) is checked before
 *   the readable roots, so adding `$HOME` as a readable root cannot expose `~/.ssh`. Every other
 *   Browser4 folder — including its configuration — is readable by design (the deployer opted into
 *   that by running a coding agent with `full` access in the first place).
 *
 * The roots are process-wide, like the toolchain roots in [CodingComponents.scopeManifest]: they
 * come from the environment, not from a per-task policy, which is why the cached boundary
 * [Authorizer] can keep one instance per workspace root (its key also includes these roots).
 */
object ReadablePaths {

    /** JVM property naming extra readable roots, separated by the platform path separator. */
    const val PROP_READ_PATHS = "browser4.agent.readPaths"

    /** JVM property overriding the Browser4 data root (see `AppContext.APP_DATA_DIR`). */
    const val PROP_APP_DATA_DIR = "app.data.dir"

    /** Data-directory name under the user's home when [PROP_APP_DATA_DIR] is not set. */
    const val DEFAULT_DATA_DIR_NAME = ".browser4"

    /**
     * Credential locations that stay refused even when they sit inside a readable root.
     *
     * The same list [SensitivePaths] starts from; kept as an explicit subset so the relaxation
     * cannot be widened by editing the sensitive list alone.
     */
    val ALWAYS_REFUSED: List<String> = listOf("~/.ssh", "~/.aws", "~/.gnupg", "~/.config/gcloud")

    private val rootsCache = AtomicReference<CacheEntry?>(null)

    private val alwaysRefusedCache = AtomicReference<CacheEntry?>(null)

    private data class CacheEntry(val key: String, val roots: List<Path>)

    /**
     * Every readable root: the built-ins first (data, checkout), then the configured ones.
     *
     * Resolved on every call but memoized against the environment it depends on
     * (`browser4.agent.readPaths`, `app.data.dir`, `user.dir`), so a test that changes a property
     * sees the change while a per-path resolution stays cheap.
     */
    fun roots(): List<Path> =
        cachedRoots(rootsCache, environmentKey()) { (builtinRoots() + configuredRoots()).distinct() }

    /** The built-in roots: the Browser4 data root and the source checkout it runs from. */
    fun builtinRoots(): List<Path> = buildList {
        appDataRoot()?.let(::add)
        sourceCheckout()?.let(::add)
    }

    /**
     * The Browser4 data root: `app.data.dir` when set, else `~/.browser4`.
     *
     * Returns `null` only when neither the property nor `user.home` is usable — a broken
     * environment, in which case adding no built-in root is the safe answer.
     */
    fun appDataRoot(): Path? {
        val configured = System.getProperty(PROP_APP_DATA_DIR)?.trim()
        if (!configured.isNullOrEmpty()) {
            return absoluteOrNull(configured)
        }
        val home = System.getProperty("user.home")?.trim()
        if (home.isNullOrEmpty()) return null
        return absoluteOrNull(home)?.resolve(DEFAULT_DATA_DIR_NAME)?.normalize()
    }

    /**
     * The checkout the backend was started from, or `null` for a packaged runtime.
     *
     * This is the "code folder" of the request: a task's workspace mirrors it, but the real
     * checkout (with `.git`, the other worktrees and the untracked fixtures) is more than the
     * mirror and worth reading directly.
     */
    fun sourceCheckout(): Path? = runCatching {
        WorkspaceMirror.defaultSourceRepo()?.toAbsolutePath()?.normalize()
    }.getOrNull()

    /** The roots named by [PROP_READ_PATHS], `~`-expanded and absolute. */
    fun configuredRoots(): List<Path> = parse(System.getProperty(PROP_READ_PATHS))

    /**
     * Parses a raw property value into roots.
     *
     * Entries are separated by the platform path separator (`;` on Windows, `:` elsewhere) and by
     * `;` on every platform, because operators copy Windows-style lists across. Blank entries are
     * ignored, `~`/`~/x` are expanded against `user.home`, and an entry that is not a valid path
     * is dropped rather than failing the task (operator convenience, like the shell switches).
     */
    fun parse(raw: String?): List<Path> {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return emptyList()
        // `\Q…\E` inside a character class is fragile across regex engines, and a path list is not
        // a pattern: split on the two separator characters directly.
        val separators = setOf(File.pathSeparatorChar, ';').toCharArray()
        val entries = text.split(*separators)
        val home = System.getProperty("user.home")?.trim()?.takeIf { it.isNotEmpty() }?.let { absoluteOrNull(it) }
        return entries.mapNotNull { entry ->
            val e = entry.trim()
            if (e.isEmpty()) return@mapNotNull null
            val expanded = when {
                e == "~" -> home
                e.startsWith("~/") || e.startsWith("~\\") -> home?.resolve(e.substring(2))
                else -> absoluteOrNull(e)
            }
            expanded?.normalize()
        }.distinct()
    }

    /**
     * Whether [candidate] lies inside one of [roots] (default: [roots]).
     *
     * Both the lexical and the real path are checked, the same way [SensitivePaths.isSensitive]
     * does: a junction inside a readable root can resolve into a location that is not readable.
     */
    fun isReadable(candidate: Path, roots: List<Path> = roots()): Boolean {
        if (roots.isEmpty()) return false
        val lexical = candidate.toAbsolutePath().normalize()
        if (roots.any { lexical.startsWith(it) }) return true
        val real = runCatching { if (Files.exists(lexical)) lexical.toRealPath() else null }.getOrNull()
            ?: return false
        return roots.any { real.startsWith(it) }
    }

    /**
     * Whether [candidate] names an OS credential store that must stay unreadable.
     *
     * Checked *before* [isReadable] by [SensitivePaths.isRefused], so a readable root that happens
     * to contain one (`$HOME`, `/`) cannot expose it.
     */
    fun isAlwaysRefused(candidate: Path): Boolean {
        val roots = cachedRoots(alwaysRefusedCache, "always-refused:" + System.getProperty("user.home")) {
            SensitivePaths.roots(ALWAYS_REFUSED.toSet())
        }
        return SensitivePaths.isSensitive(candidate, roots)
    }

    /** A one-line description for `coding.permissions`; `none` when nothing is configured. */
    fun describe(): String = roots().joinToString(", ").ifEmpty { "none" }

    private fun environmentKey(): String = listOf(
        System.getProperty(PROP_READ_PATHS).orEmpty(),
        System.getProperty(PROP_APP_DATA_DIR).orEmpty(),
        System.getProperty("user.dir").orEmpty(),
        System.getProperty("user.home").orEmpty(),
    ).joinToString("\u0000")

    private fun cachedRoots(cache: AtomicReference<CacheEntry?>, key: String, compute: () -> List<Path>): List<Path> {
        val cached = cache.get()
        if (cached != null && cached.key == key) return cached.roots
        val roots = compute()
        cache.set(CacheEntry(key, roots))
        return roots
    }

    private fun absoluteOrNull(raw: String): Path? =
        runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull()
}
