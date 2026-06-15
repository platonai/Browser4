package ai.platon.browser4.chrome

import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.warnInterruptible
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Destroys Chrome processes associated with a specific user data directory.
 */
class ChromeDestroyer(
    val userDataDir: Path
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ChromeDestroyer::class.java)
        private val BROWSER_PROCESS_KEYWORDS = listOf("chrome", "chromium", "google-chrome", "chromium-browser")

        private val POSIX_PROCESS_LISTING_COMMAND = """
            if command -v pgrep >/dev/null 2>&1; then
                pgrep -af 'chrome|chromium|google-chrome|chromium-browser'
            else
                ps -eo pid=,args= | grep -E -i 'chrome|chromium|google-chrome|chromium-browser' | grep -v grep
            fi
        """.trimIndent()
    }

    private val browserFileSystem = BrowserFileSystem(userDataDir)

    fun destroyGracefully(process: Process, shutdownWaitTime: Duration) {
        Runtimes.destroyProcess(process, shutdownWaitTime)
    }

    /**
     * Destroys the matching Chrome process forcibly and clears process markers.
     *
     * @param primaryPid Preferred pid to destroy before scanning the system process list.
     */
    fun destroyForcibly(primaryPid: Long? = null) {
        try {
            val candidatePids = distinctPositivePids(primaryPid, readRecordedPid())

            killProcess(candidatePids)
        } catch (e: NoSuchFileException) {
            logger.warn("NoSuchFileException | {}", e.message)
        } catch (e: IOException) {
            logger.warn("IOException | {}", e.message)
        } catch (t: Throwable) {
            warnInterruptible(this, t, "Failed to destroy chrome launcher forcibly | {}", userDataDir)
        } finally {
            clearProcessMarkers()
        }
    }

    /**
     * Check if there are Chrome processes that are likely associated with the user data directory and may be zombies.
     *
     * The associated Chrome processes are zombie when:
     * 1. Chrome processes associated with the user data directory exist in the system
     * 2. one of the following backup files exists: port file, pid file, cdp url file
     * */
    fun isZombie(): Boolean {
        if (!hasZombieProcessMarkers()) {
            return false
        }

        return try {
            if (isRecordedProcessAlive()) {
                return true
            }

            val foundByProcessHandle = ProcessHandle.allProcesses()
                .filter { isBrowserProcess(it) }
                .anyMatch { isCommandLineMatch(it.info().commandLine().orElse(null).orEmpty()) }

            foundByProcessHandle || when {
                SystemUtils.IS_OS_WINDOWS -> hasMatchingProcessWindows()
                SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_LINUX -> hasMatchingProcessPosix()
                else -> false
            }
        } catch (_: SecurityException) {
            false
        } catch (e: Exception) {
            logger.debug("Failed to check zombie chrome process for {} | {}", userDataDir, e.message)
            false
        }
    }

    fun destroyZombie() {
        if (isZombie()) {
            destroyForcibly()
        }
    }

    internal fun distinctPositivePids(vararg pids: Long?): List<Long> {
        return pids.asSequence()
            .filterNotNull()
            .filter { it > 0 }
            .distinct()
            .toList()
    }

    internal fun clearProcessMarkers() {
        browserFileSystem.clearProcessMarkers()
    }

    internal fun killProcess(candidatePids: Collection<Long> = emptyList()): Int {
        try {
            logger.info("Attempting to find and kill process holding lock on {}", userDataDir)
            val knownPids = candidatePids.asSequence()
                .filter { it > 0 }
                .distinct()
                .toList()

            val attemptedPids = mutableSetOf<Long>()

            val killedKnownPids = knownPids.count { pid ->
                if (!isProcessAlive(pid)) {
                    logger.info("Skipping exited candidate pid {} while cleaning {}", pid, userDataDir)
                    return@count false
                }

                attemptedPids.add(pid)
                logger.warn("Destroy chrome launcher forcibly, pid: {} | {}", pid, userDataDir)
                killProcessByPid(pid, "known-pid")
            }

            val killedByProcessHandle = ProcessHandle.allProcesses()
                .filter { it.pid() !in attemptedPids }
                .filter { isBrowserProcess(it) }
                .filter { isCommandLineMatch(it.info().commandLine().orElse(null).orEmpty()) }
                .map { it.pid() }
                .distinct()
                .map { pid ->
                    attemptedPids.add(pid)
                    killProcessByPid(pid, "ProcessHandle")
                }
                .count()
                .toInt()

            val killedByFallback = if (killedByProcessHandle == 0) {
                when {
                    SystemUtils.IS_OS_WINDOWS -> killLockingProcessWindows(attemptedPids)
                    SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_LINUX -> killLockingProcessPosix(attemptedPids)
                    else -> {
                        logger.warn("No platform-specific fallback is available for {}", System.getProperty("os.name"))
                        0
                    }
                }
            } else {
                0
            }

            val totalKilled = killedKnownPids + killedByProcessHandle + killedByFallback
            if (totalKilled > 0) {
                logger.info("Killed {} browser process(es) holding lock on {}", totalKilled, userDataDir)
                Thread.sleep(2000)
            } else {
                logger.info("No locking browser process found for {}", userDataDir)
            }

            return totalKilled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while killing locking process for {}", userDataDir)
        } catch (e: Exception) {
            logger.warn("Failed to kill locking process", e)
        }
        return 0
    }

    private fun readRecordedPid(): Long? {
        return browserFileSystem.readPid(preferBackup = true)
    }

    internal fun hasZombieProcessMarkers(): Boolean {
        // TODO: race condition: a other thread might be processing the bak files, a file lock is required
        return browserFileSystem.hasBackupMarks()
    }

    private fun isRecordedProcessAlive(): Boolean {
        val pid = readRecordedPid() ?: return false
        if (!Runtimes.isProcessAlive(pid)) {
            return false
        }

        val handle = ProcessHandle.of(pid).orElse(null) ?: return browserFileSystem.pidBakPath.toFile().exists()
        if (!isBrowserProcess(handle)) {
            return false
        }

        val commandLine = handle.info().commandLine().orElse(null).orEmpty()
        return commandLine.isBlank() || isCommandLineMatch(commandLine)
    }

    private fun isCommandLineMatch(cmdLine: String): Boolean {
        return ChromeLauncher.commandLineContainsUserDataDir(
            cmdLine,
            userDataDir.toAbsolutePath().toString(),
            ignoreCase = SystemUtils.IS_OS_WINDOWS
        )
    }

    private fun killLockingProcessWindows(excludedPids: Set<Long> = emptySet()): Int {
        try {
            logger.info("Trying PowerShell fallback to kill locking process")
            val output = runWindowsProcessListingCommand()
            return killProcessesFromListing(output, "PowerShell", excludedPids)
        } catch (e: Exception) {
            logger.warn("Failed to kill locking process via PowerShell", e)
        }
        return 0
    }

    private fun hasMatchingProcessWindows(): Boolean {
        return try {
            hasMatchingProcessFromListing(runWindowsProcessListingCommand())
        } catch (e: Exception) {
            logger.debug("Failed to detect zombie chrome via PowerShell for {} | {}", userDataDir, e.message)
            false
        }
    }

    private fun killLockingProcessPosix(excludedPids: Set<Long> = emptySet()): Int {
        return try {
            logger.info("Trying POSIX fallback to kill locking process")
            val output = runPosixProcessListingCommand()
            killProcessesFromListing(output, "POSIX", excludedPids)
        } catch (e: Exception) {
            logger.warn("Failed to kill locking process via POSIX fallback", e)
            0
        }
    }

    private fun hasMatchingProcessPosix(): Boolean {
        return try {
            hasMatchingProcessFromListing(runPosixProcessListingCommand())
        } catch (e: Exception) {
            logger.debug("Failed to detect zombie chrome via POSIX fallback for {} | {}", userDataDir, e.message)
            false
        }
    }

    private fun runWindowsProcessListingCommand(): List<String> {
        val command = createWindowsProcessListingCommand()
        return runCommandAndCollectOutput("powershell.exe", "-NoProfile", "-Command", command)
    }

    private fun createWindowsProcessListingCommand(): String {
        val normalizedPath =
            ChromeLauncher.normalizeCommandText(userDataDir.toAbsolutePath().toString()).replace("'", "''")
        return $$"""
            $path = '$$normalizedPath'
            Get-CimInstance Win32_Process |
                Where-Object {
                    $_.CommandLine -and
                    (
                        $_.Name -match '^(chrome|chromium)\.exe$' -or
                        $_.CommandLine -match '(?i)(chrome|chromium|google-chrome|chromium-browser)'
                    ) -and
                    $_.CommandLine.Replace('\\', '/') -like "*$path*"
                } |
                ForEach-Object { "{0}`t{1}" -f $_.ProcessId, $_.CommandLine }
        """.trimIndent()
    }

    private fun runPosixProcessListingCommand(): List<String> {
        return runCommandAndCollectOutput("bash", "-lc", POSIX_PROCESS_LISTING_COMMAND)
    }

    private fun killProcessesFromListing(
        lines: List<String>,
        source: String,
        excludedPids: Set<Long> = emptySet()
    ): Int {
        return lines.asSequence()
            .mapNotNull(ChromeLauncher::parseProcessListingLine)
            .filter { (_, cmdLine) -> isCommandLineMatch(cmdLine) }
            .map { (pid, _) -> pid }
            .filter { it !in excludedPids }
            .distinct()
            .count { pid -> killProcessByPid(pid, source) }
    }

    private fun hasMatchingProcessFromListing(lines: List<String>): Boolean {
        return lines.asSequence()
            .mapNotNull(ChromeLauncher::parseProcessListingLine)
            .any { (_, cmdLine) -> isCommandLineMatch(cmdLine) }
    }

    private fun killProcessByPid(pid: Long, source: String): Boolean {
        if (pid <= 0 || pid > Int.MAX_VALUE) {
            logger.warn("Skipping invalid pid {} from {} while cleaning {}", pid, source, userDataDir)
            return false
        }

        if (!isProcessAlive(pid)) {
            logger.info("Skipping exited pid {} from {} while cleaning {}", pid, source, userDataDir)
            return false
        }

        logger.warn("Killing process holding lock ({}): pid={} | {}", source, pid, userDataDir)
        Runtimes.destroyProcessForcibly(pid.toInt())
        return true
    }

    private fun isProcessAlive(pid: Long): Boolean {
        return try {
            Runtimes.isProcessAlive(pid)
        } catch (e: Exception) {
            logger.debug("Failed to check pid {} for {} | {}", pid, userDataDir, e.message)
            false
        }
    }

    private fun isBrowserProcess(handle: ProcessHandle): Boolean {
        val info = handle.info()
        val command = info.command().orElse(null).orEmpty()
        val commandLine = info.commandLine().orElse(null).orEmpty()
        return isBrowserCommand(command) || isBrowserCommand(commandLine)
    }

    private fun isBrowserCommand(command: String): Boolean {
        if (command.isBlank()) {
            return false
        }

        val normalized = command.lowercase()
        return BROWSER_PROCESS_KEYWORDS.any { normalized.contains(it) }
    }

    private fun runCommandAndCollectOutput(vararg command: String): List<String> {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use(BufferedReader::readLines)
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            logger.warn("Timed out while executing fallback command: {}", command.joinToString(" "))
        }

        return output
    }
}
