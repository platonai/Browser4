package ai.platon.browser4.chrome

import ai.platon.browser4.chrome.handler.transport.ChromeImpl
import ai.platon.browser4.chrome.util.ChromeLaunchException
import ai.platon.browser4.chrome.util.ChromeOptions
import ai.platon.browser4.chrome.util.LauncherOptions
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.Browsers
import ai.platon.pulsar.common.concurrent.RuntimeShutdownHookRegistry
import ai.platon.pulsar.common.concurrent.ShutdownHookRegistry
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket
import java.nio.channels.FileLockInterruptionException
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.io.path.notExists

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

        private val DEVTOOLS_LISTENING_LINE_PATTERN = Pattern.compile("^DevTools listening on (ws://.+:(\\d+)/.+)$")

        internal fun normalizeCommandText(text: String): String {
            return text.trim().replace("\\", "/")
        }

        internal fun commandLineContainsUserDataDir(
            cmdLine: String,
            userDataDir: String,
            ignoreCase: Boolean = false
        ): Boolean {
            if (cmdLine.isBlank() || userDataDir.isBlank()) {
                return false
            }

            val normalizedCmd = normalizeCommandText(cmdLine)
            val normalizedPath = normalizeCommandText(userDataDir)
            return normalizedCmd.contains(normalizedPath, ignoreCase = ignoreCase)
        }

        internal fun parseProcessListingLine(line: String): Pair<Long, String>? {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                return null
            }

            val match = Regex("^(\\d+)\\s+(.+)$").find(trimmed) ?: return null
            val pid = match.groupValues[1].toLongOrNull() ?: return null
            val commandLine = match.groupValues[2].trim()
            if (commandLine.isBlank()) {
                return null
            }

            return pid to commandLine
        }
    }

    private val closed = AtomicBoolean()
    private val temporaryUddExpiry = Duration.ofMinutes(60) // BrowserFiles.TEMPORARY_UDD_EXPIRY

    // The number of recent temporary user data directories to keep, the browser has to be closed
    private val recentNToKeep = 5
    private val browserFileSystem = BrowserFileSystem(userDataDir)
    private val chromeDestroyer = ChromeDestroyer(userDataDir)
    private var process: Process? = null

    @Volatile
    private var lastChromeProcessOutput: String = ""
    private val shutdownHookRegistered = AtomicBoolean(false)

    private val isActive get() = AppContext.isActive && !Thread.currentThread().isInterrupted
    private val shutdownHookThread = Thread { this.close() }

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
    @Synchronized
    fun launch(
        chromeBinaryPath: Path,
        options: ChromeOptions
    ): RemoteChrome {
        return browserFileSystem.withUserDataDirLock {
            // Destroy zombie Chrome processes associated with the user data directory if any
            if (chromeDestroyer.isZombie()) {
                chromeDestroyer.destroyForcibly()
            }

            // Check if there's already an active Chrome process using this userDataDir
            val existingPort = checkExistingChromeProcess()
            if (existingPort > 0) {
                logger.info("Found existing Chrome process on port: {} for userDataDir: {}", existingPort, userDataDir)
                // logger.info("Reusing existing Chrome process feature is disabled temporarily")
                return@withUserDataDirLock ChromeImpl(
                    existingPort
                )
            }

            // Attempt to prepare the user data directory
            prepareUserDataDir()

            // Launch the Chrome process with the specified binary path, user data directory, and options.
            val startTime = System.currentTimeMillis()
            var port = 0
            var lastException: Exception? = null

            // Retry if the profile is locked, it happens when the previous process is exiting
            for (i in 1..5) {
                try {
                    port = launchChromeProcess(chromeBinaryPath, userDataDir, options)
                    break
                } catch (e: ChromeLaunchException) {
                    lastException = e
                    // If the profile is locked, wait for the previous process to exit
                    if (i < 5) {
                        chromeDestroyer.killProcess()

                        try {
                            Thread.sleep(3000)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw e
                        }
                    }
                }
            }

            if (port == 0) {
                throw lastException
                    ?: ChromeLaunchException("Failed to launch chrome with unknown port")
            }

            val launchDuration = System.currentTimeMillis() - startTime

            // Generate launch report
            generateLaunchReport(chromeBinaryPath, options, port, launchDuration)

            // Return a new instance of ChromeImpl initialized with port
            ChromeImpl(port)
        }
    }

    /**
     * Launch chrome
     * */
    @Throws(ChromeLaunchException::class)
    @Synchronized
    fun launch(options: ChromeOptions) = launch(Browsers.searchChromeBinary(), options)

    /**
     * Launch chrome
     * */
    @Throws(ChromeLaunchException::class)
    @Synchronized
    fun launch(headless: Boolean) =
        launch(
            Browsers.searchChromeBinary(), ChromeOptions()
                .also { it.headless = headless })

    /**
     * Launch chrome
     * */
    @Throws(ChromeLaunchException::class)
    @Synchronized
    fun launch() = launch(true)

    /**
     * Destroy the chrome process forcibly.
     * */
    @Synchronized
    fun destroyForcibly() {
        chromeDestroyer.destroyForcibly(process?.pid())
    }

    /**
     * Stop the chrome process but keep the launcher active.
     * */
    @Synchronized
    fun stop() {
        val p = process
        this.process = null
        try {
            if (p != null && p.isAlive) {
                chromeDestroyer.destroyGracefully(p, shutdownWaitTime = Duration.ofSeconds(5))
                if (p.isAlive) {
                    destroyForcibly()
                }
            }
        } catch (t: Throwable) {
            warnForClose(this, t)
        } finally {
            unregisterShutdownHookIfRegistered()
            chromeDestroyer.clearProcessMarkers()
        }

        cleanUpContextFiles()
    }

    /**
     * Close the chrome process.
     * The method throws nothing by design.
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unregisterShutdownHookIfRegistered()
            stop()
        }
    }

    private fun registerShutdownHookIfNeeded() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            shutdownHookRegistry.register(shutdownHookThread)
        }
    }

    private fun unregisterShutdownHookIfRegistered() {
        if (shutdownHookRegistered.compareAndSet(true, false)) {
            shutdownHookRegistry.remove(shutdownHookThread)
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
    @get:Synchronized
    val isAlive: Boolean get() = process?.isAlive == true

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
            // Clean up any existing invalid port files before creating new ones
            cleanupInvalidPortFile()

            // --- Write launch arguments to file ---
            val argFile = writeLaunchArgumentsToFile(executable, arguments)

            // Create port file with "0" to indicate process is starting
            browserFileSystem.writeStartingPort()

            registerShutdownHookIfNeeded()
            process = ProcessLauncher.launch(executable, arguments)

            val p = process
            if (p == null) {
                logger.warn(
                    "Failed to launch Chrome process, process is null | arguments are written: {}",
                    argFile?.toUri()
                )
                throw ChromeLaunchException("Failed to launch Chrome process | $executable")
            }

            // Write PID file to indicate the process is alive
            browserFileSystem.writePid(p.pid())

            val port = waitForDevToolsServer(p)

            // write port to indicate the process can be connected
            browserFileSystem.writePort(port)

            port
        } catch (e: ChromeLaunchException) {
            stop()
            throw e
        } catch (e: IllegalStateException) {
            stop()
            throw ChromeLaunchException("IllegalStateException while trying to launch chrome", e)
        } catch (e: IOException) {
            stop()
            throw ChromeLaunchException("IOException while trying to start chrome", e)
        } catch (e: Exception) {
            // Close the process if failed to start, it throws nothing by design.
            stop()
            throw e
        }
    }

    /**
     * Checks if there's an existing Chrome process using the port specified in the port file.
     * This method provides robust port file management by validating both the port and process status.
     *
     * @return The port number if an existing Chrome process is found, 0 otherwise.
     */
    private fun checkExistingChromeProcess(): Int {
        return try {
            val port = browserFileSystem.readPositivePort() ?: return 0

            // Verify that the port is actually in use and the process is alive
            if (isPortInUse(port) && isProcessAlive()) {
                logger.info("Found valid existing Chrome process on port: {}", port)
                // Read and log CDP URL if available
                val cdpUrl = readCdpUrl()
                if (cdpUrl != null) {
                    logger.info("Reusing existing Chrome process, CDP URL: {}", cdpUrl)
                } else {
                    logger.info("Reusing existing Chrome process, CDP URL file not found (port: {})", port)
                }

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
     * Reads the CDP WebSocket URL from the CDP URL file.
     *
     * @return The CDP URL if the file exists and is readable, null otherwise.
     */
    private fun readCdpUrl(): String? {
        return browserFileSystem.readCdpUrl()
    }

    /**
     * Checks if the given port is in use by attempting to connect to it.
     *
     * @param port The port number to check.
     * @return True if the port is in use, false otherwise.
     */
    fun isPortInUse(port: Int): Boolean {
        return try {
            Socket("localhost", port).use {
                true // Successfully connected, port is in use
            }
        } catch (_: Exception) {
            false // Failed to connect, port is not in use
        }
    }

    /**
     * Checks if the Chrome process recorded in the PID file is still alive.
     *
     * @return True if the process is alive, false otherwise.
     */
    fun isProcessAlive(): Boolean {
        return try {
            val pid = browserFileSystem.readPid() ?: return false

            // Check if process with this PID is still running
            Runtimes.isProcessAlive(pid)
        } catch (e: Exception) {
            logger.debug("Failed to check process alive status: {}", e.message)
            false
        }
    }

    /**
     * Cleans up invalid port, PID, and CDP URL files when the associated process is no longer alive.
     *
     * @return Always returns 0 to indicate no valid port was found.
     */
    private fun cleanupInvalidPortFile(): Int {
        val result = browserFileSystem.cleanupInvalidProcessFiles()
        logger.debug("Cleaned up invalid port, PID, and CDP URL files for userDataDir: {}", userDataDir)
        return result
    }

    /**
     * Waits for DevTools server is upon chrome process.
     * Captures the full CDP WebSocket URL and saves it to file.
     *
     * @param process Chrome process.
     * @return DevTools listening port.
     * @throws ChromeLaunchException If timeout expired while waiting for a chrome process.
     */
    @Throws(ChromeLaunchException::class)
    private fun waitForDevToolsServer(process: Process): Int {
        var port = 0
        var cdpUrl: String?
        val processOutput = StringBuilder()
        val charset = if (SystemUtils.IS_OS_WINDOWS) Charset.forName("GBK") else Charsets.UTF_8
        val readLineThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream, charset)).use { reader ->
                // Wait for DevTools listening line and extract port number and CDP URL.
                var line: String? = reader.readLine()
                while (process.isAlive && line != null) {
                    if (line.isNotBlank()) {
                        // If chrome launched successfully, the output is like the following:
                        // 2025-09-16 23:16:03.247  INFO [Thread-2] a.p.p.b.d.c.ChromeLauncher - [output] - DevTools listening on ws://127.0.0.1:50658/devtools/browser/ab3ec7cd-f800-4cc7-9ea1-7d3563e30d7c
                        logger.info("[output] - $line")
                        val matcher = DEVTOOLS_LISTENING_LINE_PATTERN.matcher(line)
                        if (matcher.find() && matcher.groupCount() >= 2) {
                            cdpUrl = matcher.group(1) // Full WebSocket URL
                            port = matcher.group(2).toInt() // Port number

                            // Save CDP URL to file
                            try {
                                browserFileSystem.writeCdpUrl(cdpUrl)
                                logger.info("CDP WebSocket URL saved: {}", cdpUrl)
                            } catch (e: Exception) {
                                logger.warn("Failed to write CDP URL to file: {}", e.message)
                            }

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
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error("Interrupted while waiting for devtools server, close it", e)
            close(readLineThread)
        } finally {
            persistLastProcessOutput(processOutput.toString())
        }

        if (port == 0) {
            close(readLineThread)
            val output = processOutput.toString()
            val isAlive = process.isAlive
            val exitValue = if (!isAlive) process.exitValue() else "N/A"
            val message = String.format(
                "Failed to start chrome process. alive: %s, exit code: %s\n" +
                        "Process output:>>>\n%s\n<<<", isAlive, exitValue, output
            )
            logger.warn(message)

//            if (output.contains("Opening in existing browser session") || output.contains("正在现有的浏览器会话中打开")) {
//                throw ChromeLaunchException("Chrome profile is locked by another process | $userDataDir")
//            }

            logChromeFailedToStart()

            throw ChromeLaunchException("$message | $userDataDir")
        }

        return port
    }

    private fun close(thread: Thread) {
        try {
            thread.join(options.threadWaitTime.toMillis())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun logChromeFailedToStart() {
        val count = Runtimes.countSystemProcess("chrome")
        if (count == 0) {
            logger.error("Failed to start Chrome, no chrome process running in the system")
            logLastLaunchDetails()
            return
        }

        // val isSystemDefaultBrowser = userDataDir == AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER

        val scriptFileName = if (SystemUtils.IS_OS_WINDOWS) "kill-browsers.ps1" else "kill-browsers.sh"
        val scriptPath = AppPaths.SCRIPT_DIR.resolve(scriptFileName)
        if (scriptPath.notExists()) {
            val content = ResourceLoader.readString(scriptFileName)
            Files.write(scriptPath, content.toByteArray())
        }

        if (!SystemUtils.IS_OS_WINDOWS) {
            runCatching {
                val view = Files.getFileAttributeView(scriptPath, PosixFileAttributeView::class.java)
                if (view != null) {
                    val perms = view.readAttributes().permissions().toMutableSet()
                    perms.addAll(setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE))
                    view.setPermissions(perms)
                }
            }.onFailure { logger.warn("Failed to set execute permission for {} | {}", scriptPath.toUri(), it.message) }
        }

        val message = """

===============================================================================
!!!   FAILED TO START CHROME   !!!

Run the script to kill Chrome processes and run the program again:

${scriptPath.toUri()}

===============================================================================

                    """.trimIndent()

        logger.warn(message)
        return
    }

    private fun logLastLaunchDetails() {
        try {
            val reportPath = browserFileSystem.launchReportPath
            val reportContent = browserFileSystem.readLaunchReport()
            if (!reportContent.isNullOrBlank()) {
                logger.warn("Last chrome launch report ({}):\n{}", reportPath, reportContent)
            } else {
                logger.warn("Last chrome launch report not found at {}", reportPath)
            }
        } catch (e: Exception) {
            logger.warn("Failed to read chrome launch report: {}", e.message)
        }

        val output = readLastProcessOutput().orEmpty()
        if (output.isNotBlank()) {
            logger.warn("Last chrome process output:\n{}", output)
        } else {
            logger.warn("Last chrome process output is empty")
        }
    }

    /**
     * Prepare user data dir.
     *
     * @throws IOException If failed to create user data dir.
     * */
    @Throws(IOException::class)
    private fun prepareUserDataDir() {
        try {
            browserFileSystem.prepareUserDataDir()
        } catch (e: OverlappingFileLockException) {
            logger.warn("OverlappingFileLockException, rethrow | {} | \n{}", userDataDir, e.brief())
            throw ChromeLaunchException("Failed to prepare user data dir", e)
        } catch (e: FileLockInterruptionException) {
            logger.warn("FileLockInterruptionException, rethrow | {} | \n{}", userDataDir, e.brief())
            Thread.currentThread().interrupt()
            throw ChromeLaunchException("Failed to prepare user data dir", e)
        }
    }

    private fun cleanUpContextFiles() {
        try {
            runCatching {
                chromeDestroyer.clearProcessMarkers()
                BrowserFiles.cleanUpContextTmpDir(temporaryUddExpiry)
                BrowserFiles.cleanOldestContextTmpDirs(Duration.ofMinutes(2), recentNToKeep)
            }.onFailure { warnForClose(this, it) }
        } catch (_: Throwable) {
            // ignored
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
            val jsonReport = formatJsonReport(reportData)
            val reportPaths = browserFileSystem.writeLaunchReport(jsonReport)
            if (reportPaths != null) {
                logger.debug("Chrome launch report saved to: {}", reportPaths.first)
                logger.debug("Chrome launch history saved to: {}", reportPaths.second)
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate launch report: {}", e.message)
        }
    }

    /**
     * Builds comprehensive launch report data.
     */
    private fun buildLaunchReportData(
        chromeBinaryPath: Path,
        options: ChromeOptions,
        port: Int,
        launchDuration: Long
    ): Map<String, Any> {
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
        chromeOptionsInfo["isSystemDefaultBrowser"] =
            userDataDir.startsWith(AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER)
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
        encodingInfo["fileEncoding"] = Charset.defaultCharset().displayName()
        encodingInfo["charset"] = if (SystemUtils.IS_OS_WINDOWS) "GBK" else "UTF-8"
        systemInfo["encoding"] = encodingInfo

        reportData["systemInfo"] = systemInfo

        // File system information
        val fileSystemInfo = mutableMapOf<String, Any>()
        fileSystemInfo["portFilePath"] = browserFileSystem.portPath.toString()
        fileSystemInfo["pidFilePath"] = browserFileSystem.pidPath.toString()
        fileSystemInfo["userDataDirExists"] = Files.exists(userDataDir)
        fileSystemInfo["userDataDirSize"] = browserFileSystem.getUserDataDirSize()
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

        @Suppress("UNCHECKED_CAST")
        val launchInfo = data["launchInfo"] as Map<String, Any>
        report.appendLine("Launch Time: ${launchInfo["timestamp"]}")
        report.appendLine("Duration: ${launchInfo["launchDuration"]}")
        report.appendLine("DevTools Port: ${launchInfo["devToolsPort"]}")
        report.appendLine("Chrome Binary: ${launchInfo["chromeBinary"]}")
        report.appendLine("User Data Dir: ${launchInfo["userDataDirectory"]}")

        @Suppress("UNCHECKED_CAST")
        val processInfo = data["processInfo"] as Map<String, Any>
        report.appendLine("Process ID: ${processInfo["pid"]}")
        report.appendLine("Process Alive: ${processInfo["isAlive"]}")

        @Suppress("UNCHECKED_CAST")
        val chromeOptions = data["chromeOptions"] as Map<String, Any>
        report.appendLine("Headless Mode: ${chromeOptions["headless"]}")
        @Suppress("UNCHECKED_CAST")
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
     * Writes the launch arguments to a separate file, with each argument on its own line.
     *
     * @param executable The chrome executable path.
     * @param arguments The list of arguments used to launch chrome.
     */
    private fun writeLaunchArgumentsToFile(executable: String, arguments: List<String>): Path? {
        return browserFileSystem.writeLaunchArguments(executable, arguments)?.also {
            logger.debug("Chrome launch arguments saved to: {}", it)
        }
    }

    private fun persistLastProcessOutput(output: String) {
        lastChromeProcessOutput = output
        browserFileSystem.writeLastProcessOutput(output)
    }

    private fun readLastProcessOutput(): String? {
        return try {
            browserFileSystem.readLastProcessOutput() ?: lastChromeProcessOutput
        } catch (e: Exception) {
            logger.warn("Failed to read chrome process output file: {}", e.message)
            lastChromeProcessOutput
        }
    }
}
