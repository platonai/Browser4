package ai.platon.pulsar.browser.driver.chrome

import ai.platon.pulsar.browser.driver.chrome.common.ChromeOptions
import ai.platon.pulsar.browser.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.browser.driver.chrome.impl.ChromeImpl
import ai.platon.pulsar.browser.driver.chrome.util.ChromeLaunchException
import ai.platon.pulsar.browser.driver.chrome.util.ChromeLaunchTimeoutException
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.BrowserFiles.PID_FILE_NAME
import ai.platon.pulsar.common.browser.BrowserFiles.PORT_FILE_NAME
import ai.platon.pulsar.common.browser.Browsers
import ai.platon.pulsar.common.concurrent.RuntimeShutdownHookRegistry
import ai.platon.pulsar.common.concurrent.ShutdownHookRegistry
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.FileFilter
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.Charset
import java.nio.file.*
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The chrome launcher
 * */
class ChromeLauncher constructor(
    val userDataDir: Path = AppPaths.CONTEXT_DEFAULT_DIR.resolve("chrome"),
    val options: LauncherOptions = LauncherOptions(),
    private val shutdownHookRegistry: ShutdownHookRegistry = RuntimeShutdownHookRegistry()
) : AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(ChromeLauncher::class.java)

        private val DEVTOOLS_LISTENING_LINE_PATTERN = Pattern.compile("^DevTools listening on ws://.+:(\\d+)/")
    }

    private val closed = AtomicBoolean()
    private val pidPath get() = userDataDir.resolveSibling(PID_FILE_NAME)
    private val portPath get() = userDataDir.resolveSibling(PORT_FILE_NAME)
    private val temporaryUddExpiry = BrowserFiles.TEMPORARY_UDD_EXPIRY
    // The number of recent temporary user data directories to keep, the browser has to be closed
    private val recentNToKeep = 10
    private var process: Process? = null

    private val isClosed get() = closed.get()
    private val isActive get() = AppContext.isActive && !Thread.currentThread().isInterrupted
    private val shutdownHookThread = Thread {
        // System.err.println("Shutting down chrome process ...")

        // the upper layer should also close the launcher
        if (!isClosed) {
            sleepSeconds(10)
            this.close()
        }
    }

    private fun clearProcessMarkers() {
        runCatching {
            fun backupIfExists(path: Path) {
                if (Files.exists(path)) {
                    val backup = path.resolveSibling("${path.fileName}.bak")
                    Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            backupIfExists(portPath)
            backupIfExists(pidPath)

            Files.deleteIfExists(portPath)
            Files.deleteIfExists(pidPath)
        }.onFailure { logger.warn("Failed to delete process marker files | {}", it.message) }
    }

    /**
     * Launches a Chrome process using the specified Chrome binary and options.
     *
     * This function prepares the user data directory and then launches the Chrome process with the given binary path and options.
     * If the preparation of the user data directory fails, a warning is logged but the process continues.
     * The function returns a [RemoteChrome] instance that represents the launched Chrome process.
     *
     * @param chromeBinaryPath The path to the Chrome binary executable.
     * @param options The Chrome options to be used when launching the Chrome process.
     * @return A [RemoteChrome] instance representing the launched Chrome process.
     * @throws ChromeLaunchException If an error occurs during the Chrome process launch.
     */
    @Throws(ChromeLaunchException::class)
    fun launch(chromeBinaryPath: Path, options: ChromeOptions): RemoteChrome {
        // Check if there's already a Chrome process using this userDataDir
        val existingPort = checkExistingChromeProcess()
        if (existingPort > 0) {
            logger.info("Found existing Chrome process on port: {} for userDataDir: {}", existingPort, userDataDir)
            return ChromeImpl(existingPort)
        }

        // Attempt to prepare the user data directory
        prepareUserDataDir()

        // Launch the Chrome process with the specified binary path, user data directory, and options.
        val startTime = System.currentTimeMillis()
        val port = launchChromeProcess(chromeBinaryPath, userDataDir, options)
        val launchDuration = System.currentTimeMillis() - startTime

        // Generate launch report
        generateLaunchReport(chromeBinaryPath, options, port, launchDuration)

        // Return a new instance of ChromeImpl initialized with port
        return ChromeImpl(port)
    }

    /**
     * Checks if there's an existing Chrome process using the port specified in the port file.
     * This method provides robust port file management by validating both the port and process status.
     *
     * @return The port number if an existing Chrome process is found, 0 otherwise.
     */
    private fun checkExistingChromeProcess(): Int {
        if (!portPath.exists()) {
            return 0
        }

        return try {
            val portContent = Files.readString(portPath).trim()
            val port = portContent.toIntOrNull() ?: return cleanupInvalidPortFile()

            // Port must be greater than 0 to be valid
            if (port <= 0) {
                return cleanupInvalidPortFile()
            }

            // Verify that the port is actually in use and the process is alive
            if (isPortInUse(port) && isProcessAlive()) {
                logger.info("Found valid existing Chrome process on port: {}", port)
                port
            } else {
                logger.warn("Found port file but process is not alive, cleaning up invalid state")
                cleanupInvalidPortFile()
            }
        } catch (e: Exception) {
            logger.warn("Failed to read existing port file: {}, cleaning up", e.message)
            cleanupInvalidPortFile()
        }
    }

    /**
     * Checks if the given port is in use by attempting to connect to it.
     *
     * @param port The port number to check.
     * @return True if the port is in use, false otherwise.
     */
    private fun isPortInUse(port: Int): Boolean {
        return try {
            Socket("localhost", port).use {
                true // Successfully connected, port is in use
            }
        } catch (e: Exception) {
            false // Failed to connect, port is not in use
        }
    }

    /**
     * Checks if the Chrome process recorded in the PID file is still alive.
     *
     * @return True if the process is alive, false otherwise.
     */
    private fun isProcessAlive(): Boolean {
        if (!pidPath.exists()) {
            return false
        }

        return try {
            val pidContent = Files.readString(pidPath).trim()
            val pid = pidContent.toLongOrNull() ?: return false

            // Check if process with this PID is still running
            Runtimes.isProcessAlive(pid)
        } catch (e: Exception) {
            logger.debug("Failed to check process alive status: {}", e.message)
            false
        }
    }

    /**
     * Cleans up invalid port and PID files when the associated process is no longer alive.
     *
     * @return Always returns 0 to indicate no valid port was found.
     */
    private fun cleanupInvalidPortFile(): Int {
        try {
            portPath.deleteIfExists()
            pidPath.deleteIfExists()
            logger.debug("Cleaned up invalid port and PID files for userDataDir: {}", userDataDir)
        } catch (e: Exception) {
            logger.warn("Failed to cleanup invalid files: {}", e.message)
        }
        return 0
    }

    /**
     * Launch chrome
     * */
    @Throws(ChromeLaunchException::class)
    fun launch(options: ChromeOptions) = launch(Browsers.searchChromeBinary(), options)

    /**
     * Launch chrome
     * */
    @Throws(ChromeLaunchException::class)
    fun launch(headless: Boolean) =
        launch(Browsers.searchChromeBinary(), ChromeOptions().also { it.headless = headless })

    /**
     * Launch chrome
     * */
    @Throws(ChromeLaunchException::class)
    fun launch() = launch(true)

    /**
     * Destroy the chrome process forcibly.
     * */
    fun destroyForcibly() {
        try {
            val pid = Files.readAllLines(pidPath).firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: 0
            if (pid > 0) {
                logger.warn("Destroy chrome launcher forcibly, pid: {} | {}", pid, userDataDir)
                Runtimes.destroyProcessForcibly(pid)
            }
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
     * Close the chrome process.
     * The method throws nothing by design.
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            shutdownHookRegistry.remove(shutdownHookThread)

            val p = process
            this.process = null
            try {
                if (p != null && p.isAlive) {
                    Runtimes.destroyProcess(p, options.shutdownWaitTime)
                    if (p.isAlive) {
                        destroyForcibly()
                    }
                }
            } catch (t: Throwable) {
                warnForClose(this, t)
            } finally {
                clearProcessMarkers()
            }

            try {
                BrowserFiles.runCatching {
                    cleanUpContextTmpDir(temporaryUddExpiry)
                    cleanOldestContextTmpDirs(Duration.ofMinutes(2), recentNToKeep)
                }.onFailure { warnForClose(this, it) }
            } catch (t: Throwable) {
                // ignored
            }
        }
    }

    /**
     * Returns an exit value. This is just proxy to [Process.exitValue].
     *
     * @return Exit value of the process if exited.
     * @throws [IllegalThreadStateException] if the subprocess has not yet terminated. [     ] If the process hasn't even started.
     */
    fun exitValue(): Int {
        checkNotNull(process) { "Chrome process has not been started" }
        return process!!.exitValue()
    }

    /**
     * Tests whether the subprocess is alive. This is just proxy to [Process.isAlive].
     *
     * @return True if the subprocess has not yet terminated.
     * @throws IllegalThreadStateException if the subprocess has not yet terminated.
     */
    val isAlive: Boolean get() = process?.isAlive == true

    val shouldBeWorking: Boolean get() = portPath.exists()

    /**
     * Launches a chrome process given a chrome binary and its arguments.
     *
     * Launching chrome processes is CPU consuming, so we do this in a synchronized manner
     *
     * @param chromeBinary Chrome binary path.
     * @param userDataDir Chrome user data dir.
     * @param chromeOptions Chrome arguments.
     * @return Port on which devtools is listening.
     * @throws ChromeLaunchException If an error occurs during chrome process start.
     */
    @Throws(ChromeLaunchException::class)
    @Synchronized
    private fun launchChromeProcess(chromeBinary: Path, userDataDir: Path, chromeOptions: ChromeOptions): Int {
        if (!isActive) {
            return 0
        }

        check(process == null) { "Chrome process has already been started" }
        check(!isAlive) { "Chrome process has already been started" }

        var supervisorProcess = options.supervisorProcess
        if (supervisorProcess != null && Runtimes.locateBinary(supervisorProcess).isEmpty()) {
            logger.warn("Supervisor program {} can not be located", options.supervisorProcess)
            supervisorProcess = null
        }

        if (Runtimes.hasOnlyHeadlessBrowser()) {
            logger.info("The current environment has no GUI support, force to headless mode")
            chromeOptions.headless = true
        }

        val executable = supervisorProcess ?: "$chromeBinary"
        var arguments = if (supervisorProcess == null) chromeOptions.toList() else {
            options.supervisorProcessArgs + arrayOf("$chromeBinary") + chromeOptions.toList()
        }.toMutableList()

        if (userDataDir.startsWith(AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER)) {
            // Open the default browser just like a real user daily do,
            // open a blank page not to choose the profile
            val args = "--remote-debugging-port=0 --remote-allow-origins=* about:blank"
            arguments = args.split(" ").toMutableList()
        } else {
            arguments.add("--user-data-dir=$userDataDir")
        }

        return try {
            Files.createDirectories(portPath.parent)

            // Clean up any existing invalid port files before creating new ones
            cleanupInvalidPortFile()

            // --- Write launch arguments to file ---
            writeLaunchArgumentsToFile(executable, arguments)

            // Create port file with "0" to indicate process is starting
            Files.writeString(portPath, "0", StandardOpenOption.CREATE)

            shutdownHookRegistry.register(shutdownHookThread)
            process = ProcessLauncher.launch(executable, arguments)

            val p = process ?: throw ChromeLaunchException("Failed to start chrome process")

            // Write PID file to indicate the process is alive
            Files.writeString(pidPath, p.pid().toString(), StandardOpenOption.CREATE)

            val port = waitForDevToolsServer(p)

            // write port to indicate the process can be connected
            Files.writeString(portPath, port.toString(), StandardOpenOption.TRUNCATE_EXISTING)

            port
        } catch (e: IllegalStateException) {
            close()
            throw ChromeLaunchException("IllegalStateException while trying to launch chrome", e)
        } catch (e: IOException) {
            close()
            throw ChromeLaunchException("IOException while trying to start chrome", e)
        } catch (e: Exception) {
            // Close the process if failed to start, it throws nothing by design.
            close()
            throw e
        }
    }

    /**
     * Waits for DevTools server is upon chrome process.
     *
     * @param process Chrome process.
     * @return DevTools listening port.
     * @throws ChromeLaunchTimeoutException If timeout expired while waiting for a chrome process.
     */
    @Throws(ChromeLaunchTimeoutException::class)
    private fun waitForDevToolsServer(process: Process): Int {
        var port = 0
        val processOutput = StringBuilder()
        val charset = if (SystemUtils.IS_OS_WINDOWS) Charset.forName("GBK") else Charsets.UTF_8
        val readLineThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream, charset)).use { reader ->
                // Wait for DevTools listening line and extract port number.
                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        // If chrome launched successfully, the output is like the following:
                        // 2025-09-16 23:16:03.247  INFO [Thread-2] a.p.p.b.d.c.ChromeLauncher - [output] - DevTools listening on ws://127.0.0.1:50658/devtools/browser/ab3ec7cd-f800-4cc7-9ea1-7d3563e30d7c
                        logger.info("[output] - $line")
                        val matcher = DEVTOOLS_LISTENING_LINE_PATTERN.matcher(line)
                        if (matcher.find()) {
                            port = matcher.group(1).toInt()
                            break
                        }
                        processOutput.appendLine(line)
                    }

                    line = reader.readLine()
                }
            }
        }
        readLineThread.start()

        try {
            readLineThread.join(options.startupWaitTime.toMillis())

            if (port == 0) {
                close(readLineThread)
                logger.debug("Process output:>>>\n{}\n<<<", processOutput)

                handleChromeFailedToStart()

                throw ChromeLaunchTimeoutException("Timeout to waiting for chrome to start | $userDataDir")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error("Interrupted while waiting for devtools server, close it", e)
            close(readLineThread)
        }

        return port
    }

    private fun close(thread: Thread) {
        try {
            thread.join(options.threadWaitTime.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handleChromeFailedToStart() {
        val count = Runtimes.countSystemProcess("chrome")
        if (count == 0) {
            logger.warn("Failed to start Chrome, no chrome process running in the system")
            return
        }

        // val isSystemDefaultBrowser = userDataDir == AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER

        val message = """

===============================================================================
!!!   FAILED TO START CHROME   !!!

Failed to start Chrome programmatically, but there are already $count chrome
processes running in the system.

Kill all Chrome processes and run the program again.

===============================================================================

                    """.trimIndent()

//        Runtimes.listAllChromeProcesses().forEach {
//            println(it)
//        }

        logger.warn(message)
        return
    }

    /**
     * Prepare user data dir.
     *
     * @throws IOException If failed to create user data dir.
     * */
    @Throws(IOException::class)
    private fun prepareUserDataDir() {
        try {
            prepareUserDataDir0()
        } catch (e: OverlappingFileLockException) {
            logger.warn("OverlappingFileLockException, rethrow | {} | \n{}", userDataDir, e.brief())
            throw ChromeLaunchException("Failed to prepare user data dir", e)
        } catch (e: FileLockInterruptionException) {
            logger.warn("FileLockInterruptionException, rethrow | {} | \n{}", userDataDir, e.brief())
            Thread.currentThread().interrupt()
            throw ChromeLaunchException("Failed to prepare user data dir", e)
        }
    }

    /**
     * Prepare user data dir.
     *
     * @throws FileLockInterruptionException – If the invoking thread is interrupted while blocked in this method
     * @throws OverlappingFileLockException – If a lock that overlaps the requested region is already held
     *      by this Java virtual machine, or if another thread is already blocked in this method and is
     *      attempting to lock an overlapping region of the same file
     * @throws IOException If failed to create user data dir.
     * */
    @Throws(FileLockInterruptionException::class, OverlappingFileLockException::class, IOException::class)
    private fun prepareUserDataDir0() {
        val prototypeUserDataDir = AppPaths.CHROME_DATA_DIR_PROTOTYPE
        if (userDataDir == prototypeUserDataDir || userDataDir.toString().contains("/default/")) {
            logger.info("Running chrome with prototype/default data dir, no cleaning | {}", userDataDir)
            return
        }

        // Lock the group so that only one instance can run at the same time
        val lock = BrowserFiles.getContextGroupLockFileFromUserDataDir(userDataDir)
        if (isActive && Files.exists(prototypeUserDataDir.resolve("Default"))) {
            FileChannel.open(lock, StandardOpenOption.APPEND).use {
                it.lock()

                if (!isActive) {
                    return
                }

                if (!Files.exists(userDataDir.resolve("Default"))) {
                    logger.info(
                        "User data dir does not exist, copy from prototype | {} <- {}",
                        userDataDir,
                        prototypeUserDataDir
                    )
                    // remove dead symbolic links
                    Files.list(prototypeUserDataDir)
                        .filter { Files.isSymbolicLink(it) && !Files.exists(it) }
                        .forEach { Files.delete(it) }

                    // ISSUE#29: https://github.com/platonai/browser4/issues/29
                    // Failed to copy chrome data dir when there is a SingletonSocket symbol link
                    val fileFilter = FileFilter { !Files.isSymbolicLink(it.toPath()) }

//                    val fileFilter = { f: File -> !Files.isSymbolicLink(f.toPath())
//                        // Copy only the default profile directory
//                        && f.name == "Default"
//                    }
                    FileUtils.copyDirectory(prototypeUserDataDir.toFile(), userDataDir.toFile(), fileFilter)
                } else {
                    handleExistUserDataDir(prototypeUserDataDir)
                }
            }
        }
    }

    private fun handleExistUserDataDir(prototypeUserDataDir: Path) {
        // the user data dir exists
        Files.deleteIfExists(userDataDir.resolve("Default/Cookies"))
        val leveldb = userDataDir.resolve("Default/Local Storage/leveldb")
        if (Files.exists(leveldb)) {
            // might have permission issue on Windows
            // FileUtils.deleteDirectory(leveldb.toFile())
        }

        arrayOf("Default/Cookies", "Default/Local Storage/leveldb").forEach {
            val target = userDataDir.resolve(it)
            Files.createDirectories(target.parent)
            val source = prototypeUserDataDir.resolve(it)
            if (Files.exists(source)) {
                // Files.copy(prototypeUserDataDir.resolve(it), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * Generates a comprehensive launch report after Chrome launch.
     *
     * @param chromeBinaryPath The path to the Chrome binary executable.
     * @param options The Chrome options used when launching the Chrome process.
     * @param port The port on which the DevTools is listening.
     * @param launchDuration The duration of the Chrome launch in milliseconds.
     */
    private fun generateLaunchReport(chromeBinaryPath: Path, options: ChromeOptions, port: Int, launchDuration: Long) {
        try {
            val reportData = buildLaunchReportData(chromeBinaryPath, options, port, launchDuration)

            // Write to both console and file
            val textReport = formatTextReport(reportData)
            logger.debug("Chrome Launch Report:\n{}", textReport)

            // Write JSON report to file
            val reportPath = userDataDir.resolveSibling("chrome-launch-report.json")
            val jsonReport = formatJsonReport(reportData)
            Files.writeString(reportPath, jsonReport, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

            // --- Write launch history ---
            val launchHistoryDir = userDataDir.resolveSibling("history")
            Files.createDirectories(launchHistoryDir)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            val historyFile = launchHistoryDir.resolve("chrome-launch-report-$timestamp.json")
            Files.writeString(historyFile, jsonReport, StandardOpenOption.CREATE_NEW)

            logger.debug("Chrome launch report saved to: {}", reportPath)
            logger.debug("Chrome launch history saved to: {}", historyFile)
        } catch (e: Exception) {
            logger.warn("Failed to generate launch report: {}", e.message)
        }
    }

    /**
     * Builds comprehensive launch report data.
     */
    private fun buildLaunchReportData(chromeBinaryPath: Path, options: ChromeOptions, port: Int, launchDuration: Long): Map<String, Any> {
        val currentProcess = process
        val reportData = mutableMapOf<String, Any>()

        // Launch information
        val launchInfo = mutableMapOf<String, Any>()
        launchInfo["timestamp"] = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        launchInfo["launchDuration"] = "${launchDuration}ms"
        launchInfo["devToolsPort"] = port
        launchInfo["userDataDirectory"] = userDataDir.toString()
        launchInfo["chromeBinary"] = chromeBinaryPath.toString()
        reportData["launchInfo"] = launchInfo

        // Process information
        val processInfo = mutableMapOf<String, Any>()
        processInfo["pid"] = currentProcess?.pid() ?: 0
        processInfo["isAlive"] = currentProcess?.isAlive() ?: false
        processInfo["supervisorProcess"] = this.options.supervisorProcess ?: "none"
        reportData["processInfo"] = processInfo

        // Chrome options
        val chromeOptionsInfo = mutableMapOf<String, Any>()
        chromeOptionsInfo["headless"] = options.headless
        chromeOptionsInfo["arguments"] = options.toList()
        chromeOptionsInfo["isSystemDefaultBrowser"] = userDataDir.startsWith(AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER)
        reportData["chromeOptions"] = chromeOptionsInfo

        // System information
        val systemInfo = mutableMapOf<String, Any>()

        // Operating system info
        val osInfo = mutableMapOf<String, Any>()
        osInfo["name"] = System.getProperty("os.name")
        osInfo["version"] = System.getProperty("os.version")
        osInfo["arch"] = System.getProperty("os.arch")
        osInfo["isWindows"] = SystemUtils.IS_OS_WINDOWS
        osInfo["hasGuiSupport"] = !Runtimes.hasOnlyHeadlessBrowser()
        systemInfo["os"] = osInfo

        // JVM information
        val jvmInfo = mutableMapOf<String, Any>()
        jvmInfo["version"] = System.getProperty("java.version")
        jvmInfo["vendor"] = System.getProperty("java.vendor")
        jvmInfo["home"] = System.getProperty("java.home")
        jvmInfo["maxMemory"] = "${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB"
        jvmInfo["totalMemory"] = "${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB"
        jvmInfo["freeMemory"] = "${Runtime.getRuntime().freeMemory() / 1024 / 1024}MB"
        systemInfo["jvm"] = jvmInfo

        // Encoding information
        val encodingInfo = mutableMapOf<String, Any>()
        encodingInfo["fileEncoding"] = System.getProperty("file.encoding")
        encodingInfo["charset"] = if (SystemUtils.IS_OS_WINDOWS) "GBK" else "UTF-8"
        systemInfo["encoding"] = encodingInfo

        reportData["systemInfo"] = systemInfo

        // File system information
        val fileSystemInfo = mutableMapOf<String, Any>()
        fileSystemInfo["portFilePath"] = portPath.toString()
        fileSystemInfo["pidFilePath"] = pidPath.toString()
        fileSystemInfo["userDataDirExists"] = Files.exists(userDataDir)
        fileSystemInfo["userDataDirSize"] = getUserDataDirSize()
        reportData["fileSystem"] = fileSystemInfo

        // Performance information
        val performanceInfo = mutableMapOf<String, Any>()
        performanceInfo["startupWaitTime"] = this.options.startupWaitTime.toString()
        performanceInfo["shutdownWaitTime"] = this.options.shutdownWaitTime.toString()
        performanceInfo["threadWaitTime"] = this.options.threadWaitTime.toString()
        performanceInfo["systemChromeProcessCount"] = Runtimes.countSystemProcess("chrome")
        reportData["performance"] = performanceInfo

        return reportData
    }

    /**
     * Formats the report data as human-readable text.
     */
    private fun formatTextReport(data: Map<String, Any>): String {
        val report = StringBuilder()
        report.appendLine("Chrome Launch Report")
        report.appendLine("=".repeat(50))

        val launchInfo = data["launchInfo"] as Map<String, Any>
        report.appendLine("Launch Time: ${launchInfo["timestamp"]}")
        report.appendLine("Duration: ${launchInfo["launchDuration"]}")
        report.appendLine("DevTools Port: ${launchInfo["devToolsPort"]}")
        report.appendLine("Chrome Binary: ${launchInfo["chromeBinary"]}")
        report.appendLine("User Data Dir: ${launchInfo["userDataDirectory"]}")

        val processInfo = data["processInfo"] as Map<String, Any>
        report.appendLine("Process ID: ${processInfo["pid"]}")
        report.appendLine("Process Alive: ${processInfo["isAlive"]}")

        val chromeOptions = data["chromeOptions"] as Map<String, Any>
        report.appendLine("Headless Mode: ${chromeOptions["headless"]}")
        val args = chromeOptions["arguments"] as List<String>
        report.appendLine("Arguments: ${args.joinToString(" ")}")

        report.appendLine("=".repeat(50))
        return report.toString()
    }

    /**
     * Formats the report data as JSON.
     */
    private fun formatJsonReport(data: Map<String, Any>): String {
        return prettyPulsarObjectMapper().writeValueAsString(data)
    }

    /**
     * Gets the size of the user data directory.
     */
    private fun getUserDataDirSize(): String {
        return try {
            if (Files.exists(userDataDir)) {
                val size = Files.walk(userDataDir)
                    .filter { Files.isRegularFile(it) }
                    .mapToLong { Files.size(it) }
                    .sum()
                "${size / 1024 / 1024}MB"
            } else {
                "0MB"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Writes the launch arguments to a separate file, with each argument on its own line.
     *
     * @param executable The chrome executable path.
     * @param arguments The list of arguments used to launch chrome.
     */
    private fun writeLaunchArgumentsToFile(executable: String, arguments: List<String>) {
        return try {
            val argsFile = userDataDir.resolveSibling("chrome-launch-arguments.txt")
            Files.writeString(argsFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

            // Write the executable path
            Files.writeString(argsFile, "$executable", StandardOpenOption.APPEND)

            // Write each argument on a new line
            arguments.forEach { arg ->
                Files.writeString(argsFile, "\n$arg", StandardOpenOption.APPEND)
            }

            logger.debug("Chrome launch arguments saved to: {}", argsFile)
        } catch (e: Exception) {
            logger.warn("Failed to write launch arguments to file: {}", e.message)
        }
    }
}
