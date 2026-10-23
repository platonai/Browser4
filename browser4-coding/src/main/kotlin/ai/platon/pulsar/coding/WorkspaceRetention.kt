package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant

/**
 * Reclaims stale agent workspaces (coding-run-isolation-design.md §6.7).
 *
 * Runs once per backend process (first agent creation), inside the agent-data
 * root only. The sweeper is *junction-safe*: it must never descend into a
 * reparse point, because `workspace/browser4-*` are junctions to the real
 * checkout, and `Files.walkFileTree` + recursive delete would wipe the source
 * repo. [deleteWorkspaceTree] uses [isLink] to detect reparse points and
 * deletes only the link itself.
 *
 * @param keep number of agent directories to retain regardless of age
 * @param maxDays terminal workspaces older than this are eligible for deletion
 * @param dryRun when true, log what *would* be deleted without deleting
 */
class WorkspaceRetention(
    private val store: WorkspaceManifestStore = WorkspaceManifestStore(),
    private val keep: Int = 3,
    private val maxDays: Long = 7,
    private val dryRun: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(WorkspaceRetention::class.java)

    /** Write a `.lock` file (pid + startedAt) when an agent dir is created.
     *  Deleted by [removeLock] on agent shutdown. A missing/expired lock lets
     *  the sweeper treat the dir as eligible (rule 3/4). */
    fun writeLock(agentDir: Path) {
        runCatching {
            val pid = ProcessHandle.current().pid()
            val content = "pid=$pid\nstartedAt=${Instant.now()}"
            Files.writeString(agentDir.resolve(".lock"), content)
        }.onFailure { logger.warn("failed to write lock for {}", agentDir, it) }
    }

    /** Remove the agent's lock file (best-effort; process exit is also a signal). */
    fun removeLock(agentDir: Path) {
        runCatching { Files.deleteIfExists(agentDir.resolve(".lock")) }
    }

    /** Whether the `.lock` claims a still-living process. Missing lock → false. */
    private fun hasLiveLock(agentDir: Path): Boolean = runCatching {
        val lock = agentDir.resolve(".lock")
        if (!Files.isRegularFile(lock)) return false
        val pidLine = Files.readString(lock).lineSequence()
            .firstOrNull { it.startsWith("pid=") } ?: return false
        val pid = pidLine.removePrefix("pid=").toLongOrNull() ?: return false
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }.getOrDefault(false)

    /** Sweep the agent-data root. Safe to call multiple times (idempotent within
     *  a process; cross-process last-writer-wins). */
    fun sweep(agentDataRoot: Path) {
        if (!Files.isDirectory(agentDataRoot)) return
        val agentDirs = runCatching {
            Files.list(agentDataRoot).use { it.filter { Files.isDirectory(it) }.toList() }
        }.getOrElse { emptyList() }

        // Read all manifests + sort by lastTask.endedAt desc (missing → dir mtime).
        data class Entry(val dir: Path, val manifest: WorkspaceManifest?, val lastEndedAt: Instant?)
        val entries = agentDirs.map { dir ->
            val m = runCatching { store.read(dir) }.getOrNull()
            val ended = m?.lastTask?.endedAt
                ?: runCatching { Files.getLastModifiedTime(dir).toInstant() }.getOrNull()
            Entry(dir, m, ended)
        }

        // Rule 1: keep the latest `keep` by ended-at.
        val sorted = entries.sortedByDescending { it.lastEndedAt ?: Instant.EPOCH }
        val retained = sorted.take(keep).map { it.dir }.toSet()

        val now = Instant.now()
        for (e in entries) {
            if (e.dir in retained) continue
            val m = e.manifest
            // Rule 3: live lock → skip.
            if (hasLiveLock(e.dir)) continue
            // Rule 4: active without live lock → mark stale, keep this cycle.
            if (m?.state == WorkspaceState.ACTIVE) {
                markStale(e.dir, m)
                continue
            }
            // Rule 2: must be terminal and older than maxDays.
            if (m == null || !m.state.isTerminal()) continue
            val ended = e.lastEndedAt ?: continue
            if (Duration.between(ended, now).toDays() < maxDays) continue
            // Rule 5: never delete outside agentDataRoot.
            if (!e.dir.startsWith(agentDataRoot)) continue
            deleteWorkspaceTree(e.dir)
        }
    }

    private fun markStale(agentDir: Path, m: WorkspaceManifest) {
        runCatching {
            store.write(agentDir, m.copy(state = WorkspaceState.STALE))
        }.onFailure { logger.warn("failed to mark {} stale", agentDir, it) }
        logger.info("marked stale (active without live lock): {}", agentDir)
    }

    /** Junction-safe recursive delete. Never descends into a link; a link entry
     *  is deleted at the reparse-point level only. */
    fun deleteWorkspaceTree(root: Path) {
        if (!Files.exists(root)) return
        if (dryRun) {
            logger.info("[dry-run] would delete {}", root)
            return
        }
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isLink(file)) Files.delete(file)
                    else Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // If the directory itself is a link (junction), delete it in
                    // place and do NOT descend — descending would walk the source
                    // repo.
                    if (isLink(dir)) {
                        Files.delete(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }.onFailure { logger.warn("deleteWorkspaceTree failed for {}", root, it) }
    }

    /** A link is any path whose real location differs from its absolute path —
     *  catches both symlinks and Windows reparse points (junctions). */
    private fun isLink(p: Path): Boolean = runCatching {
        val absolute = p.toAbsolutePath().normalize()
        val real = p.toRealPath()
        absolute != real
    }.getOrDefault(false)
}
