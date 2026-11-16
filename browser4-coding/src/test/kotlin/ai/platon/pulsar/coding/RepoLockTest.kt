package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RepoLockTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var repo: Path

    @BeforeEach
    fun setUp() {
        repo = tempDir.resolve("repo")
        Files.createDirectories(repo.resolve(".git"))
    }

    private fun lockFile(): Path = repo.resolve(RepoLock.LOCK_FILE)

    private fun writeLock(
        pid: Long,
        taskId: String,
        startedAt: Instant = Instant.now(),
        host: String = RepoLock.hostName(),
        token: String = "",
    ) {
        val tokenField = if (token.isEmpty()) "" else ""","token":"$token""""
        Files.writeString(
            lockFile(),
            """{"pid":$pid,"host":"$host","taskId":"$taskId","startedAt":"$startedAt"$tokenField}""",
        )
    }

    @Test
    @DisplayName("acquire writes the lock, holder reports it, release removes it")
    fun acquireAndRelease() {
        val lock = RepoLock.acquire(repo, "task-a")
        try {
            assertTrue(Files.exists(lockFile()))
            val holder = RepoLock.holder(repo)
            assertNotNull(holder)
            assertEquals("task-a", holder!!.taskId)
            assertEquals(ProcessHandle.current().pid(), holder.pid)
            assertTrue(holder.token.isNotBlank(), "the record must carry the acquisition nonce")
        } finally {
            lock.release()
        }
        assertNull(RepoLock.holder(repo))
    }

    @Test
    @DisplayName("a second coding task is refused while the first holds a live lock")
    fun secondAcquireRefused() {
        val lock = RepoLock.acquire(repo, "task-first")
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                RepoLock.acquire(repo, "task-second")
            }
            assertTrue(error.message!!.contains("task-first"), error.message!!)
            assertTrue(error.message!!.contains("another coding task"), error.message!!)
        } finally {
            lock.release()
        }
    }

    @Test
    @DisplayName("a lock left by a dead process on this host is taken over")
    fun staleDeadPidTakenOver() {
        writeLock(pid = 999_999_999L, taskId = "task-dead")

        val lock = RepoLock.acquire(repo, "task-new")
        try {
            assertEquals("task-new", RepoLock.holder(repo)!!.taskId)
        } finally {
            lock.release()
        }
    }

    @Test
    @DisplayName("a live lock is not stale; an old one is")
    fun stalenessRules() {
        val mine = RepoLock.LockRecord(
            pid = ProcessHandle.current().pid(),
            host = RepoLock.hostName(),
            taskId = "t",
            startedAt = Instant.now().toString(),
        )
        assertFalse(RepoLock.isStale(mine, staleHours = 12))
        assertTrue(RepoLock.isStale(mine, staleHours = 1, now = Instant.now().plusSeconds(2 * 3600)))
        // A pid that does not exist can never hold a live lock.
        assertTrue(RepoLock.isStale(mine.copy(pid = 999_999_999L), staleHours = 12))
    }

    @Test
    @DisplayName("a lock written by another host is judged by age only, never by its pid")
    fun foreignHostIsNotJudgedByPid() {
        // Pids are not globally unique: the same number here is either absent or belongs to
        // an unrelated process. Taking the lock over on that evidence runs two live coding
        // tasks against one checkout.
        val foreign = RepoLock.LockRecord(
            pid = 999_999_999L,
            host = "some-other-host",
            taskId = "t-remote",
            startedAt = Instant.now().toString(),
        )
        assertFalse(RepoLock.isStale(foreign, staleHours = 12), "a foreign pid proves nothing here")
        assertTrue(
            RepoLock.isStale(foreign, staleHours = 1, now = Instant.now().plusSeconds(2 * 3600)),
            "the age limit still applies to another host's lock",
        )
    }

    @Test
    @DisplayName("a lock from another host is not taken over")
    fun foreignHostLockIsNotTakenOver() {
        writeLock(pid = 999_999_999L, taskId = "task-remote", host = "some-other-host")

        val error = assertThrows(IllegalStateException::class.java) {
            RepoLock.acquire(repo, "task-local")
        }
        assertTrue(error.message!!.contains("task-remote"), error.message!!)
        Files.deleteIfExists(lockFile())
    }

    @Test
    @DisplayName("concurrent acquires from several threads have exactly one winner")
    fun concurrentAcquireHasOneWinner() {
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = ConcurrentLinkedQueue<String>()
        val winnerLock = AtomicReference<RepoLock?>(null)
        val failures = ConcurrentLinkedQueue<String>()
        repeat(threads) { i ->
            Thread({
                start.await()
                runCatching { RepoLock.acquire(repo, "task-$i") }
                    .onSuccess {
                        winners.add(it.taskId)
                        winnerLock.compareAndSet(null, it)
                    }
                    .onFailure { failures.add(it.javaClass.simpleName) }
                done.countDown()
            }, "repo-lock-$i").start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "the acquire attempts must finish")
        try {
            assertEquals(1, winners.size, "exactly one task may hold the lock: $winners")
            assertEquals(threads - 1, failures.size, "every loser must fail: $failures")
        } finally {
            winnerLock.get()?.release()
        }
        assertNull(RepoLock.holder(repo), "the winner must leave no record behind")
    }

    @Test
    @DisplayName("release from a non-owner leaves the current holder's lock in place")
    fun nonOwnerReleaseKeepsLock() {
        val owner = RepoLock.acquire(repo, "task-owner")
        // Another process took the lock over after a stale takeover: same file,
        // different owner. Releasing our stale handle must not delete their lock.
        writeLock(pid = ProcessHandle.current().pid(), taskId = "task-taken-over")

        owner.release()

        val holder = RepoLock.holder(repo)
        assertNotNull(holder, "a non-owner release must keep the lock")
        assertEquals("task-taken-over", holder!!.taskId)
        Files.deleteIfExists(lockFile())
    }

    @Test
    @DisplayName("release with another acquisition's nonce keeps the lock")
    fun releaseWithAnotherTokenKeepsLock() {
        val lock = RepoLock.acquire(repo, "task-a")
        // Same pid and task id (a takeover by a fork of this very task), different nonce.
        writeLock(pid = ProcessHandle.current().pid(), taskId = "task-a", token = "someone-else")

        lock.release()

        val holder = RepoLock.holder(repo)
        assertNotNull(holder, "a record we did not write must survive our release")
        assertEquals("someone-else", holder!!.token)
        Files.deleteIfExists(lockFile())
    }

    @Test
    @DisplayName("a legacy record without a nonce is still releasable")
    fun legacyRecordWithoutTokenIsReleased() {
        val lock = RepoLock.acquire(repo, "task-legacy")
        // A record written before the nonce existed: pid + task id only.
        writeLock(pid = ProcessHandle.current().pid(), taskId = "task-legacy")

        lock.release()

        assertNull(RepoLock.holder(repo), "a legacy record we own must be releasable")
    }
}
