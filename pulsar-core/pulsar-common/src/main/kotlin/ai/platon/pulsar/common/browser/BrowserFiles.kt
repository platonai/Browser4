package ai.platon.pulsar.common.browser

import ai.platon.pulsar.common.*
import com.google.common.collect.Iterators
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.RandomStringUtils
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.MonthDay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.io.path.exists
import kotlin.io.path.notExists

internal class ContextGroup(val group: String) {

    class PathIterator(private val paths: Iterable<Path>) : Iterator<Path> {
        private val iterator = Iterators.cycle(paths)

        override fun hasNext(): Boolean {
            return paths.iterator().hasNext()
        }

        override fun next(): Path {
            return iterator.next()
        }
    }

    private val paths = ConcurrentSkipListSet<Path>()

    val size: Int
        get() = paths.size

    val iterator = PathIterator(paths)

    fun add(path: Path) {
        paths.add(path)
    }
}

object BrowserFiles {

    private val logger = getLogger(this)

    // The prefix for all temporary privacy contexts. System context, prototype context and default context are not
    // required to start with the prefix.
    const val CONTEXT_DIR_PREFIX = "cx."

    const val PID_FILE_NAME = "launcher.pid"

    const val PORT_FILE_NAME = "port"

    const val CONTEXT_LOCK_NAME = "context.lock"

    val TEMPORARY_UDD_EXPIRY = Duration.ofHours(12)

    private val contextGroups = ConcurrentHashMap<String, ContextGroup>()

    private val cleanedUserDataDirs = ConcurrentSkipListSet<Path>()

    /**
     * Compute the next sequential test context directory.
     * A typical context directory is like: /tmp/pulsar-vincent/context/group/test/cx.1
     *
     * @param fingerprint The fingerprint
     * @param maxAgents The maximum number of available agents, every agent has its own context directory
     * @return The next sequential context directory
     * @throws IOException If some I/O error occurs.
     * */
    @Throws(IOException::class)
    @Synchronized
    fun computeTestContextDir(fingerprint: Fingerprint = Fingerprint.DEFAULT, maxAgents: Int = 10): Path {
        val group = "test"

        val lockFile = getContextGroupDirLockFile(group)
        return runWithFileLockWithRetry(lockFile) { channel ->
            computeNextSequentialContextDir0(group, fingerprint, maxAgents, channel = channel)
        }
    }

    /**
     * Compute the next sequential context directory.
     * A typical context directory is like: /tmp/pulsar-vincent/context/group/default/cx.1
     *
     * @param group The group name, default is "default"
     * @param fingerprint The fingerprint
     * @param maxAgents The maximum number of available agents, every agent has its own context directory
     * @return The next sequential context directory
     * */
    @Throws(IOException::class)
    @Synchronized
    fun computeNextSequentialContextDir(
        group: String = "default", fingerprint: Fingerprint = Fingerprint.DEFAULT, maxAgents: Int = 10
    ): Path {
        val lockFile = getContextGroupDirLockFile(group)
        return runWithFileLockWithRetry(lockFile) { channel ->
            computeNextSequentialContextDir0(group, fingerprint, maxAgents, channel = channel)
        }
    }

    @Throws(IOException::class)
    @Synchronized
    fun computeRandomTmpContextDir(group: String = "rand", browserType: BrowserType = BrowserType.PULSAR_CHROME): Path {
        // val lockFile = AppPaths.BROWSER_TMP_DIR_LOCK
        // return computeRandomContextDir0(group)
        val lockFile = getTempContextGroupDirLockFile(group)
        return runWithFileLockWithRetry(lockFile) { channel -> computeRandomContextDir0(group, browserType, channel = channel) }
    }

    @Throws(IOException::class)
    fun cleanOldestContextTmpDirs(expiry: Duration, recentNToKeep: Int = 10) {
        // Remove directories that have too many context directories
        val files = Files.walk(AppPaths.CONTEXT_TMP_DIR, 3)
            .filter { it !in cleanedUserDataDirs } // not processed
            .filter { it.toString().contains("cx.") } // context dir
            .filter { it.resolve(PID_FILE_NAME).exists() } // already launched
            .filter { it.resolve(PORT_FILE_NAME).notExists() } // already closed
            .toList()
            .toSet()

        try {
            files.sortedByDescending { Files.getLastModifiedTime(it) }  // newest first
                .drop(recentNToKeep)  // drop the newest N context dirs, so them are not cleaned
                .forEach { cleanUpContextDir(it, expiry) } // clean the rest
        } catch (ignored: LinkageError) {
            // This prevents NoClassDefFoundError when classes have been unloaded
            // (e.g., when running via maven exec:java)
            // ignored
        }
    }

    /**
     * Clean up the context directories that are expired.
     *
     * @param expiry The expiry duration
     * */
    @Throws(IOException::class)
    fun cleanUpContextTmpDir(expiry: Duration) {
        Files.walk(AppPaths.CONTEXT_TMP_DIR, 3)
            .filter { it !in cleanedUserDataDirs }
            .filter { it.fileName.toString().startsWith("cx.") }
            .filter { it.resolve(PID_FILE_NAME).exists() } // already launched
            .filter { it.resolve(PORT_FILE_NAME).notExists() } // already closed
            .forEach { path -> cleanUpContextDir(path, expiry) } // clean the rest
    }

    /**
     * Clear the browser's user data dir inside the given context path.
     *
     * Only clean the context directory which starts with "cx."
     *
     * @param contextDir The path to the context directory
     * @param expiry The expiry duration
     * */
    @Throws(IOException::class)
    fun cleanUpContextDir(contextDir: Path, expiry: Duration) {
        // only clean the context directory which starts with "cx."
        if (!contextDir.fileName.toString().startsWith("cx.")) {
            logger.info("Not a context directory | {}", contextDir)
            return
        }

        // a typical path is:
        // %USERPROFILE%\context\groups\rand\PULSAR_CHROME\cx.2\PULSAR_CHROME
        val groupName = contextDir.parent.fileName.toString()
        Files.list(contextDir)
            .filter { it.fileName.toString().uppercase() == "PULSAR_CHROME" }
            .forEach { dirToDelete ->
                deleteTemporaryUserDataDirWithLock(groupName, dirToDelete, expiry)
            }
    }

    /**
     * Deletes the temporary user data directory if specific conditions are met.
     *
     * This function ensures that the directory is only deleted if:
     * 1. The lock file is locked, no other thread is accessing it.
     * 2. The directory is not already in the cleaned set.
     * 3. The directory is within the temporary context directory.
     * 4. The directory has expired based on its modification time.
     * 5. The directory has a sibling file named "[PID_FILE_NAME]".
     *
     * @param group The group name, default is "default"
     * @param userDataDir The path to the user data directory that needs to be checked and potentially deleted.
     * @param expiry The duration after which the directory is considered expired and eligible for deletion.
     */
    @Throws(IOException::class)
    @Synchronized
    fun deleteTemporaryUserDataDirWithLock(group: String, userDataDir: Path, expiry: Duration) {
        val lockFile = getTempContextGroupDirLockFile(group)
        runWithFileLockWithRetry(lockFile) { channel -> deleteTemporaryUserDataDir0(userDataDir, expiry, channel) }
    }

    /**
     * Locks the specified file and executes the given supplier function.
     *
     * This function attempts to acquire a lock on the specified file and executes the supplier function while the file is locked.
     * The lock is released after the supplier function completes, regardless of whether an exception is thrown.
     *
     * @param lockFile The path to the file to be locked, of type [Path].
     * @param supplier A function that takes a [FileChannel] as an argument and returns a value of type [T]. This function will be executed while the file is locked.
     * @return The result of the supplier function, of type [T].
     * @throws IOException If some other I/O error occurs.
     */
    @Throws(OverlappingFileLockException::class, IOException::class)
    @Synchronized
    private fun <T> runWithFileLockWithRetry(lockFile: Path, supplier: (FileChannel) -> T): T {
        var retryCount = 0
        var result: Result<T> = kotlin.runCatching { runWithFileLock0(lockFile, supplier) }
        while (result.isFailure && ++retryCount <= 3) {
            sleepSeconds(1)
            result = kotlin.runCatching { runWithFileLock0(lockFile, supplier) }
        }

        return if (result.isFailure) {
            when (val e = result.exceptionOrNull()!!) {
                is IOException -> throw e
                else -> throw IOException("runWithFileLockWithRetry", result.exceptionOrNull()!!)
            }
        } else {
            result.getOrThrow()
        }
    }

    /**
     * Locks the specified file and executes the given supplier function.
     *
     * This function attempts to acquire a lock on the specified file and executes the supplier function while the file is locked.
     * The lock is released after the supplier function completes, regardless of whether an exception is thrown.
     *
     * @param lockFile The path to the file to be locked, of type [Path].
     * @param supplier A function that takes a [FileChannel] as an argument and returns a value of type [T]. This function will be executed while the file is locked.
     * @return The result of the supplier function, of type [T].
     * @throws ClosedChannelException If this channel is closed.
     * @throws AsynchronousCloseException If another thread closes this channel while the invoking thread is blocked in this method.
     * @throws FileLockInterruptionException If the invoking thread is interrupted while blocked in this method.
     * @throws OverlappingFileLockException If a lock that overlaps the requested region is already held by this Java virtual machine, or if another thread is already blocked in this method and is attempting to lock an overlapping region of the same file.
     * @throws NonWritableChannelException If this channel was not opened for writing.
     * @throws IOException If some other I/O error occurs.
     *
     * TODO: handle all the exceptions properly
     */
    @Throws(OverlappingFileLockException::class, IOException::class)
    @Synchronized
    private fun <T> runWithFileLock0(lockFile: Path, supplier: (FileChannel) -> T): T {
        // Opens or creates a file and returns a file channel to access the file.
        val channel = FileChannel.open(lockFile, StandardOpenOption.APPEND)
        channel.use {
            // Attempts to acquire a lock on the file.
//            val lock = it.tryLock() ?: throw IllegalStateException("Failed to acquire file lock.")
            val lock = it.lock()
            try {
                // Executes the supplier function while the file is locked and returns its result.
                return supplier(it)
            } finally {
                // Releases the file lock, regardless of whether an exception was thrown.
                lock.release()
            }
        }
    }

    /**
     * Deletes the temporary user data directory if specific conditions are met.
     *
     * This function ensures that the directory is only deleted if:
     * 1. The lock file is locked.
     * 2. The directory is not already in the cleaned set.
     * 3. The directory is within the temporary context directory.
     * 4. The directory has expired based on its modification time.
     * 5. The directory has a sibling file named "[PID_FILE_NAME]".
     *
     * @param userDataDir The path to the user data directory that needs to be checked and potentially deleted.
     * @param expiry The duration after which the directory is considered expired and eligible for deletion.
     * @param channel The file channel associated with the lock file, used to ensure the lock file is open.
     */
    private fun deleteTemporaryUserDataDir0(userDataDir: Path, expiry: Duration, channel: FileChannel) {
        require(channel.isOpen) { "The lock file channel is closed" }

        val dirToDelete = userDataDir

        // If the directory does not exist, it has already been deleted by another thread.
        if (!Files.exists(dirToDelete)) {
            // The directory has been deleted by other threads
            return
        }

        // If the directory is already in the cleaned set, no further action is needed.
        val cleanedUp = dirToDelete in cleanedUserDataDirs
        if (cleanedUp) {
            return
        }

        // Ensure the directory is within the temporary context directory to avoid accidental deletions.
        // Be careful, do not delete files by mistake, so delete files only inside AppPaths.CONTEXT_TMP_DIR
        val isTemporary = dirToDelete.startsWith(AppPaths.CONTEXT_TMP_DIR)
        if (!isTemporary) {
            logger.error("Not a temporary user data dir | {}", dirToDelete)
            return
        }

        // Check if the directory has expired based on its last modified time.
        val lastModifiedTime = Files.getLastModifiedTime(dirToDelete).toInstant()
        val isExpired = DateTimes.isExpired(lastModifiedTime, expiry)
        if (!isExpired) {
            return
        }

        // Double-check for the presence of a sibling PID file to ensure it's safe to delete the directory.
        val hasSiblingPidFile = Files.exists(dirToDelete.resolveSibling(PID_FILE_NAME))
        if (!hasSiblingPidFile) {
            return
        }

        // Attempt to delete the directory quietly, logging any failures.
        try {
            FileUtils.deleteQuietly(dirToDelete.toFile())
        } catch (e: IOException) {
            logger.warn("Failed to delete directory | {} | {}", e.message, dirToDelete)
        }

        // Verify if the directory was successfully deleted and update the cleaned set accordingly.
        if (Files.exists(dirToDelete)) {
            logger.error("Browser data dir not deleted | {}", dirToDelete)
        } else {
            cleanedUserDataDirs.add(dirToDelete)
        }
    }

    /**
     * Compute the next sequential context directory.
     * A typical context directory is like: /tmp/pulsar-vincent/context/group/default/PULSAR_CHROME/cx.1
     *
     * @param group The group name.
     * @param fingerprint The fingerprint of the browser.
     * @param maxAgents The maximum number of agents.
     * @param channel The file channel associated with
     * @return The next sequential context directory.
     * @throws IOException If an I/O error occurs.
     * */
    @Throws(IOException::class)
    private fun computeNextSequentialContextDir0(
        group: String,
        fingerprint: Fingerprint,
        maxAgents: Int,
        channel: FileChannel
    ): Path {
        require(channel.isOpen) { "The lock file channel is closed" }

        val prefix = CONTEXT_DIR_PREFIX
        val contextBaseDir = AppPaths.getContextBaseDir(group, fingerprint.browserType)

        val expectedContextPaths = IntRange(1, maxAgents)
            .map { String.format("%s%s", prefix, it) }
            .map { contextBaseDir.resolve(it) }
        expectedContextPaths.filter { it.notExists() }.forEach {
            Files.createDirectories(it)
        }

        val contextGroup = contextGroups.computeIfAbsent(group) { ContextGroup(group) }
        // can throw too many open files in ubuntu when using Files.list()
        // see too-many-open-files.md to resolve the problem
        Files.list(contextBaseDir)
            .filter { Files.isDirectory(it) }
            .filter { it.fileName.toString().startsWith(prefix) }
            .filter { it in expectedContextPaths }
            .forEach { contextGroup.add(it) }

        return contextGroup.iterator.next()
    }

    /**
     * Compute a random context directory.
     * A typical context directory is like: /tmp/pulsar-vincent/context/tmp/groups/{group}/cx.0109aNcTxq5
     * */
    @Throws(IOException::class)
    private fun computeRandomContextDir0(group: String, browserType: BrowserType, channel: FileChannel? = null): Path {
        if (channel != null) {
            require(channel.isOpen) { "The lock file channel is closed" }
        }

        // build the base dir
        val baseDir = AppPaths.getTmpContextGroupDir(group)
            // .resolve(browserType.name) // when create the user data dir, the dir with browserType will be created
        Files.createDirectories(baseDir)

        // build the file name
        val prefix = CONTEXT_DIR_PREFIX
        val monthDay = MonthDay.now()
        val monthValue = monthDay.monthValue
        val dayOfMonth = monthDay.dayOfMonth
        val rand = RandomStringUtils.secure().nextAlphanumeric(5)
        val contextCount = computeContextCount(baseDir, prefix, channel)
        val fileName = String.format("%s%02d%02d%s%s", prefix, monthValue, dayOfMonth, rand, contextCount)

        val path = baseDir.resolve(fileName)
        return path
    }

    /**
     * Get the lock file of the context group.
     * The lock file is used to ensure that only one process can access the context group directory at a time.
     *
     * A typical lock file is like: /tmp/pulsar-vincent/context/group/default/context.lock
     * The lock file is created in the context group directory.
     *
     * @param group The context group name.
     * @return The lock file of the context group.
     * */
    fun getContextGroupDirLockFile(group: String): Path {
        val lockFile = AppPaths.getContextGroupDir(group).resolve(CONTEXT_LOCK_NAME)
        if (lockFile.notExists()) {
            Files.createDirectories(lockFile.parent)
            Files.createFile(lockFile)
        }
        return lockFile
    }

    /**
     * Get the lock file of the context group.
     * The lock file is used to ensure that only one process can access the context group directory at a time.
     *
     * A typical lock file is like: /tmp/pulsar-vincent/context/tmp/groups/default/context.lock
     * The lock file is created in the context group directory.
     *
     * @param group The context group name.
     * @return The lock file of the context group.
     * */
    fun getTempContextGroupDirLockFile(group: String): Path {
        val lockFile = AppPaths.getTmpContextGroupDir(group).resolve(CONTEXT_LOCK_NAME)
        if (lockFile.notExists()) {
            Files.createDirectories(lockFile.parent)
            Files.createFile(lockFile)
        }
        return lockFile
    }

    /**
     * Get the lock file of the context group from the user data directory.
     * The lock file is used to ensure that only one process can access the context group directory at a time.
     *
     * A typical lock file is like: /tmp/pulsar-vincent/context/group/default/context.lock
     * The lock file is created in the context group directory.
     *
     * @param userDataDir The user data directory of the context group.
     * @return The lock file of the context group.
     * */
    fun getContextGroupLockFileFromUserDataDir(userDataDir: Path): Path {
        val contextBaseDir = userDataDir.parent
        val groupDir = contextBaseDir.parent
        val lockFile = groupDir.resolveSibling(CONTEXT_LOCK_NAME)
        if (lockFile.notExists()) {
            Files.createDirectories(lockFile.parent)
            Files.createFile(lockFile)
        }
        return lockFile
    }

    private fun computeContextCount(baseDir: Path, prefix: String, channel: FileChannel? = null): Long {
        if (channel != null) {
            require(channel.isOpen) { "The lock file channel is closed" }
        }

        return 1 + Files.list(baseDir)
            .filter { Files.isDirectory(it) }
            .filter { it.toString().contains(prefix) }
            .count()
    }
}
