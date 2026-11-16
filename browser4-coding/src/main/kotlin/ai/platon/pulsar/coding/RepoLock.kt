package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * It records `pid`, `host`, `taskId`, `startedAt` and a per-acquire `token`, so a
 * task that blocks another can say exactly who holds it, and a release can only
 * remove the record it wrote. The file is claimed with
 * `CREATE_NEW` — a check-then-write would let two processes (two backends on one
 * checkout, or two sessions of one backend) both see "no lock" and both win.
 *
 * Staleness: the age limit `browser4.agent.lock.staleHours` (default 12) applies
 * always; the pid probe applies **only on the host that wrote the lock**, because
 * pids are not globally unique and a dead-looking pid on another machine says
 * nothing about that holder. A takeover is logged with the old holder, never silent.
 *
 * Acquire before creating the worktree; release in the task's `finally`.
 */
class RepoLock private constructor(
    val repoRoot: Path,
    val lockFile: Path,
    val taskId: String,
    val holderPid: Long,
    /** Nonce identifying THIS acquisition; only the writer may release the file. */
    private val token: String,
) : AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(RepoLock::class.java)
        private val JSON = ObjectMapper()

        const val LOCK_FILE = ".git/b4-coding.lock"
        const val STALE_HOURS_PROPERTY = "browser4.agent.lock.staleHours"

        /** Claim attempts before giving up: a takeover may itself lose a race. */
        internal const val ACQUIRE_ATTEMPTS = 3

        /**
         * Locks held by THIS JVM, keyed by lock file.
         *
         * The file protocol alone leaves a check-then-write window between
         * coroutines sharing a process (the runner mutex is per session, so two
         * sessions of one backend can arrive here together). The map is the
         * authoritative answer inside the process; the file covers everything else.
         */
        private val held = ConcurrentHashMap<Path, RepoLock>()

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

            held[lockFile]?.let { live ->
                throw IllegalStateException(
                    "another coding task is already running in $root: " +
                        "task=${live.taskId} pid=${live.holderPid}. " +
                        "Wait for it to finish."
                )
            }

            runCatching { Files.createDirectories(lockFile.parent) }
            val token = UUID.randomUUID().toString()

            repeat(ACQUIRE_ATTEMPTS) {
                val now = Instant.now()
                val record = LockRecord(pid, hostName(), taskId, now.toString(), token)
                try {
                    // CREATE_NEW is the atomic claim: exactly one writer creates the
                    // file, everyone else gets FileAlreadyExistsException.
                    Files.writeString(
                        lockFile,
                        JSON.writeValueAsString(record.toJsonMap()),
                        StandardOpenOption.CREATE_NEW,
                    )
                } catch (e: FileAlreadyExistsException) {
                    val previous = read(lockFile) ?: return@repeat  // vanished mid-check: retry
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
                    runCatching { Files.deleteIfExists(lockFile) }.onFailure { e2 ->
                        throw IllegalStateException(
                            "could not take over the stale coding lock $lockFile: ${e2.message}"
                        )
                    }
                    return@repeat
                }
                val lock = RepoLock(root, lockFile, taskId, pid, token)
                held.putIfAbsent(lockFile, lock)
                logger.info("coding lock acquired: {} (task={}, pid={})", lockFile, taskId, pid)
                return lock
            }
            throw IllegalStateException(
                "could not acquire the coding lock at $lockFile after $ACQUIRE_ATTEMPTS attempts " +
                    "(another task keeps claiming it)"
            )
        }

        /** The current holder, or null when the checkout is free. */
        fun holder(repoRoot: Path): LockRecord? = read(repoRoot.toAbsolutePath().normalize().resolve(LOCK_FILE))

        /**
         * Whether [holder] can be taken over: older than [staleHours], or — on this
         * host — a pid that no longer exists.
         *
         * The pid probe is skipped for a lock written by another host: that pid means
         * nothing here (it is either absent or belongs to an unrelated process), and a
         * false takeover runs two live coding tasks against one checkout.
         */
        fun isStale(
            holder: LockRecord,
            staleHours: Long = staleHoursFromConfig(),
            now: Instant = Instant.now(),
        ): Boolean {
            val started = runCatching { Instant.parse(holder.startedAt) }.getOrNull() ?: return true
            if (Duration.between(started, now).toHours() >= staleHours) return true
            val localHost = hostName()
            if (holder.host.isNotBlank() && !holder.host.equals(localHost, ignoreCase = true)) return false
            val alive = ProcessHandle.of(holder.pid).map { it.isAlive }.orElse(false)
            return !alive
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
                token = node.path("token").asText(""),
            )
        }.getOrNull()

        /** This machine's name, as written into the lock record. */
        internal fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("COMPUTERNAME")
            ?: System.getenv("HOSTNAME")
            ?: "unknown"
    }

    /**
     * Release the lock, but only when this instance still owns it.
     *
     * Ownership is the record this acquisition wrote (pid + taskId + token): after a
     * takeover the file belongs to another task, and deleting it there would let a
     * third coding task in while the second is still running.
     */
    fun release() {
        held.remove(lockFile, this)
        val current = read(lockFile) ?: return
        val ownsIt = current.pid == holderPid &&
            current.taskId == taskId &&
            (current.token.isEmpty() || current.token == token)
        if (!ownsIt) {
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
        /** Nonce of the acquisition; empty for a record written before it existed. */
        val token: String = "",
    ) {
        internal fun toJsonMap(): Map<String, Any> = linkedMapOf(
            "pid" to pid,
            "host" to host,
            "taskId" to taskId,
            "startedAt" to startedAt,
            "token" to token,
        )
    }
}
