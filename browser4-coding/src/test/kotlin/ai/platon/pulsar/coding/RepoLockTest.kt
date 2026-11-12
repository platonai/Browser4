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

    private fun writeLock(pid: Long, taskId: String, startedAt: Instant = Instant.now()) {
        Files.writeString(
            lockFile(),
            """{"pid":$pid,"host":"test","taskId":"$taskId","startedAt":"$startedAt"}""",
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
    @DisplayName("a lock left by a dead process is taken over")
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
            host = "test",
            taskId = "t",
            startedAt = Instant.now().toString(),
        )
        assertFalse(RepoLock.isStale(mine, staleHours = 12))
        assertTrue(RepoLock.isStale(mine, staleHours = 1, now = Instant.now().plusSeconds(2 * 3600)))
        // A pid that does not exist can never hold a live lock.
        assertTrue(RepoLock.isStale(mine.copy(pid = 999_999_999L), staleHours = 12))
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
}
