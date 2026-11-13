package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * One coding task per checkout at a time.
 *
 * Isolation gives each task its own worktree, but everything downstream of it is
 * shared: `~/.m2`, the Maven local repository, the runtime bundle, the port the
 * backend listens on, and the main work tree the merge applies to. Two coding
 * tasks running concurrently therefore do not collide in the worktree — they
 * collide at merge time and in the build cache. This lock serialises them.
 *
 * Lock file: `<repo>/.git/b4-coding.lock` (beside git's own locks, never tracked).
 * It records `pid`, `host`, `taskId` and `startedAt`, so a task that blocks
 * another can say exactly who holds it. A lock is **stale** when its process is
 * gone or it is older than `browser4.agent.lock.staleHours` (default 12) — the
 * takeover is logged with the old holder, never silent.
 *
 * Acquire before creating the worktree; release in the task's `finally`.
 */
class RepoLock private constructor(
    val repoRoot: Path,
    val lockFile: Path,
    val taskId: String,
    private val holderPid: Long,
) : AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(RepoLock::class.java)
        private val JSON = ObjectMapper()

        const val LOCK_FILE = ".git/b4-coding.lock"
        const val STALE_HOURS_PROPERTY = "browser4.agent.lock.staleHours"

        /**
         * Acquire the checkout's coding lock.
         *
         * @throws IllegalStateException when a live task holds it; the message names
         *   the holder and the file, so the caller can report it as-is
         */
        fun acquire(
            repoRoot: Path,
            taskId: String,
            staleHours: Long = staleHoursFromConfig(),
        ): RepoLock {
            val root = repoRoot.toAbsolutePath().normalize()
            val lockFile = root.resolve(LOCK_FILE)
            val pid = ProcessHandle.current().pid()
            val now = Instant.now()

            val previous = read(lockFile)
            if (previous != null) {
                if (!isStale(previous, staleHours, now)) {
                    throw IllegalStateException(
                        "another coding task is already running in $root: " +
                            "task=${previous.taskId} pid=${previous.pid} started=${previous.startedAt}. " +
                            "Wait for it to finish, or delete $lockFile if that process is gone."
                    )
                }
                logger.warn(
                    "Taking over a stale coding lock in {} (task={} pid={} started={})",
                    root, previous.taskId, previous.pid, previous.startedAt,
                )
            }

            runCatching { Files.createDirectories(lockFile.parent) }
            val record = LockRecord(pid, hostName(), taskId, now.toString())
            Files.writeString(lockFile, JSON.writeValueAsString(record.toJsonMap()))
            logger.info("coding lock acquired: {} (task={}, pid={})", lockFile, taskId, pid)
            return RepoLock(root, lockFile, taskId, pid)
        }

        /** The current holder, or null when the checkout is free. */
        fun holder(repoRoot: Path): LockRecord? = read(repoRoot.toAbsolutePath().normalize().resolve(LOCK_FILE))

        /** Whether [holder] can be taken over: dead process, or older than [staleHours]. */
        fun isStale(
            holder: LockRecord,
            staleHours: Long = staleHoursFromConfig(),
            now: Instant = Instant.now(),
        ): Boolean {
            val alive = ProcessHandle.of(holder.pid).map { it.isAlive }.orElse(false)
            if (!alive) return true
            val started = runCatching { Instant.parse(holder.startedAt) }.getOrNull() ?: return true
            return Duration.between(started, now).toHours() >= staleHours
        }

        fun staleHoursFromConfig(): Long =
            System.getProperty(STALE_HOURS_PROPERTY, "12").trim().toLongOrNull()?.coerceAtLeast(1) ?: 12

        private fun read(lockFile: Path): LockRecord? = runCatching {
            if (!Files.isRegularFile(lockFile)) return null
            val node = JSON.readTree(Files.readString(lockFile))
            val pid = node.path("pid").asLong(0)
            if (pid <= 0) return null
            LockRecord(
                pid = pid,
                host = node.path("host").asText(""),
                taskId = node.path("taskId").asText(""),
                startedAt = node.path("startedAt").asText(""),
            )
        }.getOrNull()

        private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?: System.getenv("COMPUTERNAME")
            ?: System.getenv("HOSTNAME")
            ?: "unknown"
    }

    /** Release the lock, but only when this instance still owns it. */
    fun release() {
        val current = read(lockFile) ?: return
        if (current.pid != holderPid || current.taskId != taskId) {
            logger.debug(
                "coding lock at {} is held by task={} pid={}; not releasing it from task={} pid={}",
                lockFile, current.taskId, current.pid, taskId, holderPid,
            )
            return
        }
        runCatching { Files.deleteIfExists(lockFile) }
            .onFailure { logger.warn("could not release the coding lock {}: {}", lockFile, it.message) }
    }

    override fun close() = release()

    /** The on-disk record of one lock holder. */
    data class LockRecord(
        val pid: Long,
        val host: String,
        val taskId: String,
        val startedAt: String,
    ) {
        internal fun toJsonMap(): Map<String, Any> = linkedMapOf(
            "pid" to pid,
            "host" to host,
            "taskId" to taskId,
            "startedAt" to startedAt,
        )
    }
}
