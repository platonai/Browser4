package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The task workspaces a run may write besides its own — "all tasks' workspaces".
 *
 * A task normally writes only its own workspace
 * (`<app data>/agent/<yyyy>/<MM>/<dd>/<agent-uuid>/workspace.<task-id>`), which is what keeps
 * `filesChanged` attributable and a failed run disposable
 * (`skills/browser4-cli/references/agent-workspace.md`). Collaboration needs the other half:
 * handing a deliverable to, or continuing the work of, a *different* task. This object resolves
 * that set so [CodingComponents] can put it in the boundary grants, in the OS sandbox grants and
 * in the `coding.permissions` report — one derivation, three consumers, no drift.
 *
 * ## What is in the set
 *
 * Only directories that *are* a task workspace by layout, never an ancestor of one:
 *
 * - under `<app data>/agent/<yyyy>/<MM>/<dd>/<agent-uuid>/`;
 * - named `workspace` (the legacy shared workspace) or `workspace.<task-id>`;
 * - **not** `workspace.archive` (`WorkspaceTaskLifecycle.archiveDir`), which holds the
 *   deliverables of aborted tasks and is not a workspace a run may rewrite;
 * - never `<app data>/agent/skills` — deploying an executable skill is a privilege escalation the
 *   CLI already refuses for the same reason (`B4CliToolExecutor`'s skill refusal).
 *
 * Isolated coding worktrees (`<repo>/.browser4/worktrees/<task-id>`) are deliberately **not**
 * included: an unmerged worktree is the only copy of a change that has not passed
 * `coding.verify`, and `.browser4` is a protected subtree. Hand one over explicitly with
 * `agent run --add-dir <worktree>` instead.
 *
 * ## How much of it
 *
 * The scan is bounded so a run start never walks months of history: the newest
 * [writeDays] day directories, newest first, at most [MAX_ROOTS] workspaces. Both are tunable
 * (`-Dbrowser4.agent.taskWorkspaceWriteDays=`, and the cap keeps the boundary from becoming
 * "every directory this machine ever produced"). The set is resolved when the task's policy is
 * applied; a task that starts *later* is not in it — `--add-dir` is the explicit route for that
 * case, and `coding.permissions` shows what this run actually got.
 */
object TaskWorkspaces {

    /** Directory name of the agent tree under the Browser4 data root (`<app data>/agent`). */
    const val AGENT_DIR_NAME = "agent"

    /** Name of the legacy shared workspace and prefix of a per-task one. */
    const val WORKSPACE_PREFIX = "workspace"

    /** [WorkspaceTaskLifecycle.archiveDir]'s name — looks like a workspace, is not one. */
    const val ARCHIVE_NAME = "workspace.archive"

    /** Newest task workspaces that may be in scope at once. */
    const val MAX_ROOTS = 64

    /** Day directories considered by default. */
    const val DEFAULT_DAYS = 7

    /** Entries [describe] spells out before summarizing the rest by day directory. */
    const val REPORT_LIMIT = 6

    /**
     * JVM property widening (or narrowing) the day window the scan looks at.
     *
     * `1` means "today's tasks only"; the value is clamped to `1..400` so a typo cannot make the
     * scan unbounded.
     */
    const val PROP_WRITE_DAYS = "browser4.agent.taskWorkspaceWriteDays"

    private val YEAR = Regex("\\d{4}")
    private val MONTH_OR_DAY = Regex("\\d{1,2}")
    private val TASK_ID = Regex("[A-Za-z0-9_-]{1,64}")

    private val logger = LoggerFactory.getLogger(TaskWorkspaces::class.java)

    /** The agent tree, `<app data>/agent`, or `null` when the data root cannot be resolved. */
    fun agentBaseDir(): Path? = ReadablePaths.appDataRoot()?.resolve(AGENT_DIR_NAME)

    /** The day window in effect, clamped to `1..400`. */
    fun writeDays(): Int =
        System.getProperty(PROP_WRITE_DAYS)?.trim()?.toIntOrNull()?.coerceIn(1, 400) ?: DEFAULT_DAYS

    /**
     * Whether [path] is a task workspace by layout — `<app data>/agent/<yyyy>/<MM>/<dd>/<uuid>/workspace[.<id>]`.
     *
     * Checked rather than assumed: the scan below only produces such paths, but the sandbox
     * coalescing ([sandboxRoots]) and tests both need the predicate on its own.
     */
    fun isTaskWorkspace(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        val name = normalized.fileName?.toString() ?: return false
        if (!isWorkspaceName(name)) return false
        return isUnderAgentLayout(normalized)
    }

    /**
     * Whether [name] is a task workspace directory name.
     *
     * `workspace` (the legacy shared one) and `workspace.<task id>` pass; `workspace.archive` is
     * excluded explicitly because it matches the prefix.
     */
    fun isWorkspaceName(name: String): Boolean {
        if (name == WORKSPACE_PREFIX) return true
        if (name == ARCHIVE_NAME) return false
        if (!name.startsWith("$WORKSPACE_PREFIX.")) return false
        val suffix = name.substring(WORKSPACE_PREFIX.length + 1)
        return TASK_ID.matches(suffix)
    }

    /**
     * The sibling task workspaces in scope: the newest [maxRoots], sorted by path.
     *
     * The scan walks the newest day directories first and each one newest-first, so the cap keeps
     * the most recent workspaces; the result is then sorted by path so two calls with the same tree
     * produce the same boundary (the authorizer cache key and the report both depend on it).
     *
     * [currentWorkspace] is dropped (it is already the boundary's own root), and so is every
     * entry that is not a task workspace by layout — including `workspace.archive` and the agent
     * tree's `skills`.
     *
     * @param currentWorkspace this task's workspace; `null` keeps every candidate
     * @param baseDir the agent tree to scan; defaults to [agentBaseDir]
     * @param maxRoots upper bound on the returned roots
     * @param maxDays how many of the newest day directories to look at ([writeDays] by default)
     */
    fun writableRoots(
        currentWorkspace: Path? = null,
        baseDir: Path? = agentBaseDir(),
        maxRoots: Int = MAX_ROOTS,
        maxDays: Int = writeDays(),
    ): List<Path> {
        val base = baseDir?.toAbsolutePath()?.normalize() ?: return emptyList()
        if (base.fileName?.toString() != AGENT_DIR_NAME || !Files.isDirectory(base)) return emptyList()
        if (maxRoots <= 0) return emptyList()
        val current = currentWorkspace?.toAbsolutePath()?.normalize()
        val found = mutableListOf<Path>()
        for (dayDir in recentDayDirs(base, maxDays)) {
            for (agentDir in listDirs(dayDir)) {
                for (candidate in listDirs(agentDir)) {
                    if (found.size >= maxRoots) break
                    if (!isWorkspaceName(candidate.fileName.toString())) continue
                    val normalized = candidate.toAbsolutePath().normalize()
                    if (normalized == current || !isTaskWorkspace(normalized)) continue
                    found.add(normalized)
                }
                if (found.size >= maxRoots) break
            }
            if (found.size >= maxRoots) break
        }
        // Newest first while collecting (the scan walks the newest day directories first and sorts
        // each one by modification time), then by path so the boundary and the report are stable
        // between two calls with the same tree.
        return found.distinct().sortedBy { it.toString() }
    }

    /**
     * The roots to hand to the OS sandbox: each task workspace coalesced to its day directory.
     *
     * The string layer keeps the exact workspace list, so this widening is only about cost: a
     * low-integrity label or a container ACE is one external command per root, and granting
     * `<yyyy>/<MM>/<dd>` costs one per *day* instead of one per task while covering every
     * workspace of that day (including one that starts after this task).
     *
     * A root that is not in the agent layout is returned unchanged — never silently dropped.
     */
    fun sandboxRoots(roots: Collection<Path>): List<Path> {
        if (roots.isEmpty()) return emptyList()
        return roots.map { root ->
            val normalized = root.toAbsolutePath().normalize()
            val day = normalized.parent?.parent
            if (isTaskWorkspace(normalized) && day != null) day else normalized
        }.distinct().sortedBy { it.toString() }
    }

    /**
     * One line for `coding.permissions`: how many other task workspaces are writable, and which.
     *
     * Absolute paths only — a relative one would name the task's own workspace — and bounded so
     * the report stays readable: entries past [REPORT_LIMIT] are summarized with the day
     * directories they live under, which are themselves readable (`coding.glob` lists the rest).
     */
    fun describe(roots: Collection<Path>): String {
        if (roots.isEmpty()) return "none"
        val sorted = roots.map { it.toAbsolutePath().normalize() }.sortedBy { it.toString() }
        val head = sorted.take(REPORT_LIMIT).joinToString(", ") { it.toString() }
        if (sorted.size <= REPORT_LIMIT) return head
        val days = sorted.drop(REPORT_LIMIT).mapNotNull { it.parent?.parent }.distinct().sortedBy { it.toString() }
        return "$head … (+${sorted.size - REPORT_LIMIT} more under ${days.joinToString(", ")})"
    }

    /** The newest [maxDays] day directories (`<yyyy>/<MM>/<dd>`), newest first. */
    private fun recentDayDirs(base: Path, maxDays: Int): List<Path> {
        val days = mutableListOf<Path>()
        if (maxDays <= 0) return days
        val years = listDirs(base).filter { YEAR.matches(it.fileName.toString()) }
            .sortedByDescending { it.fileName.toString() }
        for (year in years) {
            val months = listDirs(year).filter { MONTH_OR_DAY.matches(it.fileName.toString()) }
                .sortedByDescending { it.fileName.toString().toIntOrNull() ?: 0 }
            for (month in months) {
                val dayDirs = listDirs(month).filter { MONTH_OR_DAY.matches(it.fileName.toString()) }
                    .sortedByDescending { it.fileName.toString().toIntOrNull() ?: 0 }
                for (day in dayDirs) {
                    days.add(day)
                    if (days.size >= maxDays) return days
                }
            }
        }
        return days
    }

    /**
     * Directories under [dir], newest modification time first.
     *
     * Modification time is the only ordering that survives the layout: an agent directory is named
     * by a random UUID, so the name says nothing about when the task ran.
     */
    private fun listDirs(dir: Path): List<Path> = runCatching {
        Files.newDirectoryStream(dir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .sortedByDescending { lastModified(it) }
        }
    }.getOrElse { e ->
        // An unreadable day directory is not a reason to fail a task start: the workspaces in it
        // simply stay out of scope, and the refusal the model gets names the path it asked for.
        logger.debug("task workspace scan skipped {}: {}", dir, e.message)
        emptyList()
    }

    private fun lastModified(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    /** `<app data>/agent/<yyyy>/<MM>/<dd>/<uuid>/workspace[.<id>]` — all four levels checked. */
    private fun isUnderAgentLayout(workspace: Path): Boolean {
        val agentDir = workspace.parent ?: return false
        val day = agentDir.parent ?: return false
        val month = day.parent ?: return false
        val year = month.parent ?: return false
        val base = year.parent ?: return false
        if (base.fileName?.toString() != AGENT_DIR_NAME) return false
        if (!YEAR.matches(year.fileName.toString())) return false
        if (!MONTH_OR_DAY.matches(month.fileName.toString())) return false
        if (!MONTH_OR_DAY.matches(day.fileName.toString())) return false
        return TASK_ID.matches(agentDir.fileName.toString())
    }
}
