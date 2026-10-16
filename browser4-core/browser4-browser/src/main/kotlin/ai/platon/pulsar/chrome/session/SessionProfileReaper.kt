package ai.platon.pulsar.chrome.session

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * What happened to a closed session's context directory.
 *
 * [RETAINED] is a decision, not a failure: a directory that is not provably this
 * session's disposable one is kept, and the reason is reported so the choice is
 * auditable instead of silent.
 */
enum class ProfileCleanup {
    /** Nothing to clean (no context dir, or a session that owns no browser). */
    NOT_APPLICABLE,

    /** The directory is gone; [ProfileCleanupReport.freedBytes] was reclaimed. */
    DELETED,

    /** The directory was already gone (a previous close, or a manual removal). */
    MISSING,

    /** Deliberately kept; [ProfileCleanupReport.reason] says why. */
    RETAINED,

    /** The delete was attempted and failed; the reason carries the cause. */
    FAILED,
    ;

    /** True when the directory is no longer on disk. */
    val isGone: Boolean get() = this == DELETED || this == MISSING
}

/**
 * Honest outcome of reclaiming one session's context directory.
 *
 * @property outcome the classified result
 * @property path the context directory that was inspected
 * @property reason why it was kept, or what failed (empty when deleted)
 * @property freedBytes approximate bytes reclaimed
 */
data class ProfileCleanupReport(
    val outcome: ProfileCleanup,
    val path: Path? = null,
    val reason: String = "",
    val freedBytes: Long = 0,
) {
    /** True when the directory is no longer on disk. */
    val isGone: Boolean get() = outcome.isGone

    /** One line a caller can log or return. */
    val summary: String
        get() = when (outcome) {
            ProfileCleanup.NOT_APPLICABLE -> "no disposable context dir to clean"
            ProfileCleanup.DELETED -> "reclaimed ${freedBytes / 1024} KiB from $path"
            ProfileCleanup.MISSING -> "context dir $path was already gone"
            ProfileCleanup.RETAINED -> "kept $path: $reason"
            ProfileCleanup.FAILED -> "could not remove $path: $reason"
        }
}

/**
 * Deletes the temporary chrome context directory a session's browser was
 * launched with — and nothing else.
 *
 * Every session launch allocates a fresh context dir under the app's context
 * tree (`…/context/groups/<group>/PULSAR_CHROME/cx.<n>`, ~80 MB of profile each);
 * without this they accumulate for the lifetime of the temp directory. The
 * cleanup is deliberately paranoid, because the same tree also holds profiles
 * that MUST survive a close:
 *
 * - `cx.<digits>` — the SEQUENTIAL rotation dir, created per launch, reusable by
 *   nobody: **disposable**.
 * - anything under `…/context/tmp` — the library's own temporary contexts, whose
 *   documented contract is "the user data will be deleted after the browser is
 *   closed": **disposable**.
 * - `…/groups/named/…/cx.<session-uuid>` — a named session's dedicated profile,
 *   pinned across restarts by the session's `contextDir` capability: **kept**.
 * - the persistent default context, the prototype, the system-default
 *   placeholder, anything under the browser data home, and anything the caller
 *   lists as explicitly persisted: **kept**.
 *
 * Anything not positively identified as disposable is kept: a leaked directory
 * costs disk, deleting a user's profile costs their logins.
 */
object SessionProfileReaper {
    private val logger = getLogger(SessionProfileReaper::class)

    /** `cx.<digits>`: the sequential rotation directory. */
    private val ROTATION_DIR_NAME = Regex("""cx\.\d+""", RegexOption.IGNORE_CASE)

    /** The group that holds named sessions' dedicated, persistent profiles. */
    private const val NAMED_CONTEXT_GROUP = "named"

    /**
     * Whether [contextDir] is provably this session's disposable context dir.
     *
     * @param contextDir the session's context directory (not the user data dir
     *   inside it)
     * @param protectedPaths directories the caller has explicitly pinned
     *   (a session capability such as `contextDir`, or an `--profile <path>`)
     */
    fun isDisposableContextDir(contextDir: Path?, protectedPaths: Set<Path> = emptySet()): Boolean =
        classify(contextDir, protectedPaths).first

    /**
     * Why [contextDir] would or would not be deleted, for logs and tests.
     *
     * Pure path reasoning: it never touches the file system, so the policy can be
     * asserted for paths that do not exist.
     *
     * @return `true` when disposable, plus the human-readable reason either way
     */
    internal fun classify(contextDir: Path?, protectedPaths: Set<Path> = emptySet()): Pair<Boolean, String> {
        if (contextDir == null) return false to "no context dir"
        val dir = contextDir.toAbsolutePath().normalize()

        if (dir.nameCount < 2) return false to "not a session directory"

        if (protectedPaths.any { pinned -> dir == pinned.toAbsolutePath().normalize() }) {
            return false to "explicitly persisted by the session (contextDir/profile capability)"
        }

        val permanentRoots = listOf(
            AppPaths.SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER,
            AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER,
            AppPaths.CONTEXT_DEFAULT_DIR,
            AppPaths.CHROME_DATA_DIR_PROTOTYPE,
            AppPaths.BROWSER_DATA_DIR,
        )
        permanentRoots.forEach { root ->
            val normalized = root.toAbsolutePath().normalize()
            if (dir == normalized || dir.startsWith(normalized)) {
                return false to "inside the persistent root $normalized"
            }
        }

        // The named group holds one dedicated, stable profile per session name,
        // created with a `cx.<session-uuid>` name that must never be read as a
        // rotation directory.
        val namedGroup = AppPaths.getContextBaseDir(NAMED_CONTEXT_GROUP, ai.platon.pulsar.common.browser.BrowserType.PULSAR_CHROME)
            .toAbsolutePath()
            .normalize()
        if (dir == namedGroup || dir.startsWith(namedGroup)) {
            return false to "named session profile (persisted across restarts)"
        }

        // Everything below is only reclaimed when the whole context tree lives in
        // the OS temp directory: outside it the tree may be a deliberate,
        // user-managed location and disk is not scarce in the same way.
        val contextTree = AppPaths.CONTEXT_BASE_DIR.toAbsolutePath().normalize()
        if (!dir.startsWith(contextTree)) {
            return false to "outside the app context tree ($contextTree)"
        }
        val tmpTree = AppPaths.CONTEXT_TMP_DIR.toAbsolutePath().normalize()
        val inTmpTree = dir.startsWith(tmpTree)

        val name = dir.fileName?.toString().orEmpty()
        if (ROTATION_DIR_NAME.matches(name)) {
            return true to "sequential rotation directory"
        }
        if (inTmpTree) {
            return true to "temporary context directory"
        }

        return false to "not a rotation (`cx.<n>`) or temporary context directory"
    }

    /**
     * Delete [contextDir] when — and only when — it is provably disposable.
     *
     * The caller is responsible for having closed the browser first: a live
     * browser's files are locked, and a partially deleted profile is worse than
     * a whole one.
     *
     * @param contextDir the session's context directory
     * @param protectedPaths explicitly persisted directories (see
     *   [isDisposableContextDir])
     * @return an honest report; only [ProfileCleanupReport.isGone] may be
     *   reported as "the profile was cleaned up"
     */
    fun deleteIfDisposable(contextDir: Path?, protectedPaths: Set<Path> = emptySet()): ProfileCleanupReport {
        if (contextDir == null) {
            return ProfileCleanupReport(ProfileCleanup.NOT_APPLICABLE, reason = "no context dir")
        }
        val dir = contextDir.toAbsolutePath().normalize()
        val (disposable, reason) = classify(dir, protectedPaths)
        if (!disposable) {
            return ProfileCleanupReport(ProfileCleanup.RETAINED, path = dir, reason = reason)
        }
        if (!Files.exists(dir)) {
            return ProfileCleanupReport(ProfileCleanup.MISSING, path = dir)
        }
        if (!Files.isDirectory(dir)) {
            // Never unlink a file that merely shares the rotation dir's name.
            return ProfileCleanupReport(ProfileCleanup.RETAINED, path = dir, reason = "not a directory")
        }

        return try {
            val bytes = directorySize(dir)
            deleteRecursively(dir)
            if (Files.exists(dir)) {
                ProfileCleanupReport(ProfileCleanup.FAILED, path = dir, reason = "directory still exists after delete")
            } else {
                logger.info("Reclaimed {} KiB by removing session context dir {}", bytes / 1024, dir)
                ProfileCleanupReport(ProfileCleanup.DELETED, path = dir, freedBytes = bytes)
            }
        } catch (e: Exception) {
            logger.warn("Failed to remove session context dir {}: {}", dir, e.message)
            ProfileCleanupReport(ProfileCleanup.FAILED, path = dir, reason = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun deleteRecursively(dir: Path) {
        // Deepest-first so every child is removable when reached; a file locked
        // by a lingering process fails the delete, which the caller reports.
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { path.deleteIfExists() }
            }
        }
    }

    private fun directorySize(dir: Path): Long = runCatching {
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }.getOrDefault(0L)
}
