package ai.platon.pulsar.common

import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.common.measure.ByteUnit
import kotlinx.coroutines.delay
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JFrame
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runtime utility
 * */
object Runtimes {
    private val logger = LoggerFactory.getLogger(Runtimes::class.java)
    private val heavyOperationResultCache = ConcurrentExpiringLRUCache<String, Any>(ttl = Duration.ofSeconds(10))

    fun exec(name: String): List<String> {
        val processBuilder = if (SystemUtils.IS_OS_WINDOWS) {
            ProcessBuilder("cmd.exe", "/c", name)
        } else {
            ProcessBuilder("bash", "-c", name)
        }

        try {
            val process = processBuilder.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().useLines { it.toList() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            return output
        } catch (err: Exception) {
            logger.debug("Failed to exec command {}", name, err)
        }

        return listOf()
    }

    fun locateBinary(executable: String): List<String> {
        val command = when {
            SystemUtils.IS_OS_WINDOWS -> "where $executable"
            SystemUtils.IS_OS_LINUX -> "whereis $executable"
            // TODO: more OS support
            else -> "whereis $executable"
        }

        return exec(command).asSequence()
            .filter { it.contains(File.pathSeparatorChar) }
            .filter { it.contains(executable) }
            .flatMap { it.split(" ") }
            .filter { Files.exists(Paths.get(it)) }
            .toList()
    }

    fun countSystemProcess(namePattern: String): Int {
        val command = when {
            SystemUtils.IS_OS_WINDOWS -> "tasklist /NH"
            SystemUtils.IS_OS_LINUX -> "ps -ef"
            // TODO: more OS support
            else -> "ps -ef"
        }
        return exec(command).count { it.contains(namePattern.toRegex()) }
    }

    fun checkIfProcessRunning(pattern: String): Boolean {
        return countSystemProcess(pattern) > 0
    }

    fun listAllChromeProcesses(): List<String> {
        return when {
            SystemUtils.IS_OS_WINDOWS -> listAllChromeProcessesOnWindows()
            SystemUtils.IS_OS_LINUX -> listAllChromeProcessesOnPosix()
            SystemUtils.IS_OS_MAC -> listAllChromeProcessesOnPosix()
            else -> listOf()
        }
    }

    fun listAllChromeProcessesOnPosix(): List<String> {
        val result = mutableListOf<String>()
        try {
            // Command to list all Chrome processes
            val command = "ps -ef | grep -i 'chrome' | grep -v 'grep'"

            // Execute the command
            val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            // Read and print each line of the output
            var line: String?
            // println("Running Chrome Processes:")
            while ((reader.readLine().also { line = it }) != null) {
                line?.let { result.add(it) }
            }

            // Wait for the process to complete
            process.waitFor()
        } catch (e: java.lang.Exception) {
            System.err.println("An error occurred: " + e.message)
        }

        return result
    }

    fun listAllChromeProcessesOnWindows(): List<String> {
        val result = mutableListOf<String>()
        try {
            // Command to list all Chrome processes
            val command = "tasklist | findstr /I \"chrome chromium\""

            // Execute the command
            val process = Runtime.getRuntime().exec(arrayOf("cmd.exe", "/c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            // Read and print each line of the output
            var line: String?
            // println("Running Chrome Processes:")
            while ((reader.readLine().also { line = it }) != null) {
                line?.let { result.add(it) }
            }

            // Wait for the process to complete
            process.waitFor()
        } catch (e: java.lang.Exception) {
            System.err.println("An error occurred: " + e.message)
        }

        return result
    }

    /**
     * Attempts to terminate a started [process] gracefully.
     *
     * This is the preferred shutdown path for a process that is still under this JVM's control.
     * The implementation first requests a normal termination via [Process.destroy]. If the
     * process does not exit within [shutdownWaitTime], it escalates by terminating known child
     * processes and then force-killing the parent process.
     *
     * Shutdown order:
     * - first request graceful parent exit
     * - wait up to [shutdownWaitTime]
     * - if needed, kill child processes first and then force-kill the parent
     *
     * @param process the running process to terminate
     * @param shutdownWaitTime the maximum time to wait in each shutdown phase before escalation;
     * negative durations are treated as zero
     * @throws InterruptedException if the current thread is interrupted while waiting for the
     * process to exit; the interrupt flag is restored before rethrowing
     */
    fun destroyProcess(process: Process, shutdownWaitTime: Duration) {
        terminateProcessGracefully(process, shutdownWaitTime)
    }

    /**
     * Forcibly terminates the process identified by [pid].
     *
     * This method is intended for cleanup paths where graceful shutdown is no longer sufficient.
     * When the process can be resolved through [ProcessHandle], the whole process tree is cleaned
     * in child-first order. If that fails, a platform-specific system command is used as a
     * fallback.
     *
     * Fallback commands:
     * - Windows: `taskkill /F /T /PID <pid>`
     * - Linux/macOS: `kill -9 <pid>`
     *
     * @param pid the process id to terminate; non-positive values are ignored
     */
    fun destroyProcessForcibly(pid: Int) {
        killProcessTree(pid)
    }

    /**
     * Forcibly terminates processes that match [namePattern].
     *
     * Matching is platform dependent:
     * - Windows treats [namePattern] as an image name passed to `taskkill /IM`
     * - Linux/macOS treat [namePattern] as a full command-line pattern passed to `pkill -f`
     *
     * Blank patterns are ignored. This method is intended as a broad cleanup utility and may
     * terminate multiple matching processes.
     *
     * @param namePattern the image name or command-line pattern used to select processes
     */
    fun destroyProcessForcibly(namePattern: String) {
        killProcessesByNamePattern(namePattern)
    }

    /**
     * Implements the graceful-then-force shutdown policy for [destroyProcess].
     *
     * The parent process is always given the first chance to exit cleanly. Child processes are
     * only terminated when escalation is required or the waiting thread is interrupted.
     */
    private fun terminateProcessGracefully(process: Process, shutdownWaitTime: Duration) {
        val pid = process.pid()
        val info = runCatching { formatProcessInfo(process.toHandle()) }.getOrElse { "pid=$pid" }
        val shutdownWaitMillis = shutdownWaitTime.toMillis().coerceAtLeast(0L)

        try {
            process.destroy()
            if (!process.waitFor(shutdownWaitMillis, TimeUnit.MILLISECONDS)) {
                val children = process.children().toList()
                if (children.isNotEmpty()) {
                    logger.info("Process {} did not exit within {}, terminating {} child process(es) before forcing parent", pid, shutdownWaitTime, children.size)
                }
                children.forEach { killProcessSubtree(it) }

                logger.warn("Process {} did not exit gracefully within {}, force killing", pid, shutdownWaitTime)
                process.destroyForcibly()
                if (!process.waitFor(shutdownWaitMillis, TimeUnit.MILLISECONDS)) {
                    logger.error("Process {} still alive after destroyForcibly + {} wait", pid, shutdownWaitTime)
                }
            }

            logger.info("Exit | {}", info)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val children = runCatching { process.children().toList() }.getOrElse { emptyList() }
            children.forEach { killProcessSubtree(it) }
            logger.warn("Interrupted while waiting for process {} to exit, force killing", pid)
            process.destroyForcibly()
            throw e
        }
    }

    /**
     * Kills a process tree identified by [pid].
     *
     * The implementation prefers the Java [ProcessHandle] API and falls back to a native command
     * only when handle-based termination cannot be used.
     */
    private fun killProcessTree(pid: Int) {
        if (pid <= 0) return

        try {
            ProcessHandle.of(pid.toLong()).ifPresent { handle ->
                killProcessTree(handle)
            }
            return
        } catch (e: Exception) {
            logger.debug("ProcessHandle kill failed for pid {}, falling back to system command", pid, e)
        }

        val command = when {
            SystemUtils.IS_OS_WINDOWS -> "taskkill /F /T /PID $pid"
            SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC -> "kill -9 $pid"
            else -> "kill -9 $pid"
        }

        runCatching {
            exec(command)
            logger.info("Executed kill command for pid {}: {}", pid, command)
        }.onFailure {
            logger.warn("Failed to forcibly kill pid {} using command: {}", pid, command, it)
        }
    }

    /**
     * Kills [handle] and its descendants in child-first order.
     *
     * Descendants are terminated recursively before the target process itself is asked to exit and,
     * if necessary, forcibly destroyed.
     */
    private fun killProcessTree(handle: ProcessHandle) {
        val pid = handle.pid()
        val children = handle.children().toList()
        val childCount = children.size
        if (childCount > 0) {
            logger.info("Forcibly killing process {} with {} child process(es)", pid, childCount)
        }

        children.forEach { killProcessSubtree(it) }

        if (handle.isAlive) {
            handle.destroy()
            try {
                handle.onExit().get(2, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
            }
            if (handle.isAlive) {
                handle.destroyForcibly()
            }
        }

        if (handle.isAlive) {
            logger.error("Failed to forcibly kill process {} using ProcessHandle", pid)
        } else {
            logger.info("Successfully killed process {} using ProcessHandle", pid)
        }
    }

    /**
     * Executes a platform-specific command that terminates processes matching [namePattern].
     *
     * This helper normalizes blank input, builds the command via
     * [buildKillProcessesByNamePatternCommand], waits for completion with a timeout, and records
     * diagnostic output on failure.
     */
    private fun killProcessesByNamePattern(namePattern: String) {
        val normalizedPattern = namePattern.trim()
        if (normalizedPattern.isBlank()) {
            logger.warn("Skipping destroyProcessForcibly because the name pattern is blank")
            return
        }

        val command = buildKillProcessesByNamePatternCommand(normalizedPattern) ?: run {
            logger.info("Unsupported operating system")
            return
        }

        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().useLines { it.toList() }

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(
                    "Timed out while terminating processes matching pattern {} using command: {}",
                    normalizedPattern,
                    command.joinToString(" ")
                )
                return
            }

            if (process.exitValue() == 0) {
                logger.info("Processes matching pattern {} have been terminated", normalizedPattern)
            } else {
                logger.warn(
                    "Failed to terminate processes matching pattern {} using command: {} | exitCode={} | output={}",
                    normalizedPattern,
                    command.joinToString(" "),
                    process.exitValue(),
                    output.joinToString(System.lineSeparator())
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to terminate processes matching pattern {}", normalizedPattern, e)
        }
    }

    /**
     * Builds the native command used by [destroyProcessForcibly] for name-based matching.
     *
     * The returned list is designed for [ProcessBuilder], so each token is separated and no shell
     * parsing is required.
     *
     * @param namePattern the non-blank image name or command-line pattern to match
     * @param isWindows whether to build the Windows variant; defaults to the current runtime OS
     * @param isPosix whether to build the Linux/macOS variant; defaults to the current runtime OS
     * @return the command tokens, or `null` when the pattern is blank or the platform is not
     * supported
     */
    internal fun buildKillProcessesByNamePatternCommand(
        namePattern: String,
        isWindows: Boolean = SystemUtils.IS_OS_WINDOWS,
        isPosix: Boolean = SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC,
    ): List<String>? {
        if (namePattern.isBlank()) {
            return null
        }

        return when {
            isWindows -> listOf("taskkill", "/F", "/T", "/IM", namePattern)
            isPosix -> listOf("pkill", "-f", "--", namePattern)
            else -> null
        }
    }

    fun formatProcessInfo(process: ProcessHandle): String {
        val info = process.info()
        val user = info.user().orElse("")
        val pid = process.pid()
        val ppid = process.parent().orElseGet { null }?.pid()?.toString() ?: "?"
        val startTime = info.startInstant().orElse(null)
        val cpuDuration = info.totalCpuDuration()?.orElse(null)
        val cmdLine = info.commandLine().orElseGet { "" }

        return String.format(
            "%-8s %-6d %-6s %-25s %-10s %s",
            user,
            pid,
            ppid,
            startTime ?: "",
            cpuDuration ?: "",
            cmdLine
        )
    }

    fun deleteBrokenSymbolicLinks(symbolicLink: Path) {
        if (SystemUtils.IS_OS_WINDOWS) {
            // TODO: use command line
            Files.deleteIfExists(symbolicLink)
        } else if (SystemUtils.IS_OS_LINUX) {
            exec("find -L $symbolicLink -type l -delete")
        } else {
            // TODO: more OS support
        }
    }

    suspend fun randomDelay(timeMillis: Long, delta: Int) {
        delay((timeMillis + Random.nextInt(delta)).milliseconds)
    }

    /**
     * Return the number of unallocated bytes of each file stores
     * */
    fun unallocatedDiskSpaces(): List<Long> {
        return try {
            FileSystems.getDefault().fileStores
                .filter { ByteUnit.BYTE.toGB(totalSpaceOr0(it)) > 20 }
                .map { unallocatedSpaceOr0(it) }
                .filter { it > 0 }
        } catch (e: Throwable) {
            return listOf()
        }
    }

    fun isRunningInDocker(): Boolean {
        return heavyOperationResultCache.computeIfAbsent("isRunningInDocker") { isRunningInDockerRT() } == true
    }

    /**
     * Check if the current process is running in Docker
     * */
    fun isRunningInDockerRT(): Boolean {
        // Check for /.dockerenv file
        if (File("/.dockerenv").exists()) {
            return true
        }
        // Check for 'docker' or 'kubepods' in /proc/1/cgroup
        return try {
            Files.readAllLines(Paths.get("/proc/1/cgroup")).any {
                it.contains("docker") || it.contains("kubepods")
            }
        } catch (e: Exception) {
            false
        }
    }

    fun supportHeadedBrowser(): Boolean {
        return heavyOperationResultCache.computeIfAbsent("supportHeadedChromium") { supportHeadedChromiumRT() } == true
    }

    fun supportHeadedChromiumRT(): Boolean {
        return when {
            isRunningInDocker() -> false
            SystemUtils.IS_OS_WINDOWS -> true
            SystemUtils.IS_OS_LINUX -> hasXGraphicalInterface()
            else -> isGUIAvailable()
        }
    }

    fun hasOnlyHeadlessBrowser(): Boolean {
        return !supportHeadedBrowser()
    }

    fun isGUIAvailable(): Boolean {
        return heavyOperationResultCache.computeIfAbsent("isGUIAvailable") { isGUIAvailableRT() } == true
    }

    fun isGUIAvailableRT(): Boolean {
        // First check: Java headless mode
        if (GraphicsEnvironment.isHeadless()) {
            return false
        }

        // Third check: Try to create a Swing window (safe fallback)
        return try {
            JFrame().apply { isVisible = false; dispose() }
            true
        } catch (e: HeadlessException) {
            false
        } catch (e: Exception) {
            false // In case of unexpected GUI-related errors
        }
    }

    fun hasXGraphicalInterface(): Boolean {
        // 方法 1: 检查 DISPLAY 环境变量
        val display = System.getenv("DISPLAY")
        if (!display.isNullOrEmpty()) {
            logger.info("Detected DISPLAY environment variable: $display")
            return true
        }

        // 方法 2: 检查 Xorg 是否安装
        val xorgPath = File("/usr/bin/Xorg")
        if (xorgPath.exists()) {
            logger.info("Xorg is installed at: ${xorgPath.path}")
            return true
        }

        // 方法 3: 检查常见桌面环境进程是否运行
        val desktopProcesses = listOf("gnome-session", "kdeinit", "xfce4-session")
        for (process in desktopProcesses) {
            if (checkIfProcessRunning(process)) {
                // println("Detected running desktop environment process: $process")
                return true
            }
        }

        // 如果所有检查都失败，则认为没有图形化界面
        logger.info("No graphical interface detected.")
        return false
    }

    private fun totalSpaceOr0(store: FileStore) = store.runCatching { totalSpace }.getOrNull() ?: 0L

    private fun unallocatedSpaceOr0(store: FileStore) = store.runCatching { unallocatedSpace }.getOrNull() ?: 0L

    /**
     * Recursively terminates [process] and all of its descendants in child-first order.
     *
     * This helper is used by the force-kill paths after the caller has already decided that a
     * subtree should be removed. It first visits children, then terminates the current node, and
     * finally waits briefly for the process to disappear.
     */
    private fun killProcessSubtree(process: ProcessHandle) {
        val children = process.children().toList()
        children.forEach { killProcessSubtree(it) }

        val info = formatProcessInfo(process)
        val pid = process.pid()

        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }

        var n = 10
        while (process.isAlive && n-- > 0) {
            Thread.sleep(200)
        }

        // Verify child was killed
        if (process.isAlive) {
            logger.warn("Failed to kill children of process {} | {}", pid, info)
        } else {
            logger.debug("Exit child | {}", info)
        }
    }

    /**
     * Checks if a process with the given PID is currently alive/running.
     *
     * @param pid The process ID to check
     * @return true if the process is alive, false otherwise
     */
    fun isProcessAlive(pid: Long): Boolean {
        if (pid <= 0) {
            return false
        }

        return try {
            // Use Java 9+ ProcessHandle API for cross-platform process checking
            val processHandle = ProcessHandle.of(pid)
            processHandle.isPresent && processHandle.get().isAlive
        } catch (e: Exception) {
            // Fallback to system commands if ProcessHandle fails
            try {
                isProcessAliveByCommand(pid)
            } catch (fallbackException: Exception) {
                logger.debug("Failed to check process alive status for PID {}: {}", pid, fallbackException.message)
                false
            }
        }
    }

    /**
     * Checks if a process with the given PID is currently alive/running.
     *
     * @param pid The process ID to check (as Int)
     * @return true if the process is alive, false otherwise
     */
    fun isProcessAlive(pid: Int): Boolean = isProcessAlive(pid.toLong())

    /**
     * Fallback method to check if a process is alive using system commands.
     *
     * @param pid The process ID to check
     * @return true if the process is alive, false otherwise
     */
    private fun isProcessAliveByCommand(pid: Long): Boolean {
        val command = when {
            SystemUtils.IS_OS_WINDOWS -> "tasklist /FI \"PID eq $pid\" /NH"
            SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC -> "ps -p $pid"
            else -> "ps -p $pid" // Default to POSIX command
        }

        return try {
            val result = exec(command)
            if (SystemUtils.IS_OS_WINDOWS) {
                // On Windows, if process exists, tasklist will return a line with the process info
                result.any { it.contains(pid.toString()) }
            } else {
                // On Unix-like systems, ps will return the process info if it exists
                // The first line is usually the header, so we check if there's more than just the header
                result.size > 1
            }
        } catch (e: Exception) {
            logger.debug("Failed to execute command to check process {}: {}", pid, e.message)
            false
        }
    }
}
