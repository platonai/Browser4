package ai.platon.browser4.chrome

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.browser.BrowserFiles
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.FileFilter
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists

class BrowserFileSystem(val userDataDir: Path) {
    companion object {
        private val logger = LoggerFactory.getLogger(BrowserFileSystem::class.java)
        private val intraProcessFileLocks = ConcurrentHashMap<String, Any>()

        private val TRANSIENT_USER_DATA_DIR_ENTRY_NAMES = setOf(
            "SingletonLock",
            "SingletonSocket",
            "SingletonCookie",
            "DevToolsActivePort"
        )
    }

    val pidPath get() = userDataDir.resolveSibling(BrowserFiles.PID_FILE_NAME)
    val portPath get() = userDataDir.resolveSibling(BrowserFiles.PORT_FILE_NAME)
    val cdpUrlPath get() = userDataDir.resolveSibling(BrowserFiles.CDP_URL_FILE_NAME)

    val pidBakPath get() = userDataDir.resolveSibling("${BrowserFiles.PID_FILE_NAME}.bak")
    val portBakPath get() = userDataDir.resolveSibling("${BrowserFiles.PORT_FILE_NAME}.bak")
    val cdpUrlBakPath get() = userDataDir.resolveSibling("${BrowserFiles.CDP_URL_FILE_NAME}.bak")

    val contextLockFilePath get() = BrowserFiles.getContextGroupLockFileFromUserDataDir(userDataDir)
    val userDataDirLockFilePath get() = userDataDir.resolveSibling(BrowserFiles.USER_DATA_DIR_LOCK_NAME)

    val launchArgumentsPath get() = userDataDir.resolveSibling("chrome-launch-arguments.txt")
    val launchReportPath get() = userDataDir.resolveSibling("chrome-launch-report.json")
    val launchHistoryDir get() = userDataDir.resolveSibling("history")
    val lastOutputPath get() = userDataDir.resolveSibling("chrome-launch-output.log")

    val defaultProfileDir get() = userDataDir.resolve("Default")

    val processMarkerPaths get() = listOf(portPath, pidPath, cdpUrlPath)
    val processMarkerBackupPaths get() = listOf(portBakPath, pidBakPath, cdpUrlBakPath)

    fun prepareUserDataDir() {
        if (isSystemDefaultBrowser() || isPrototypeOrDefaultUserDataDir()) {
            logger.info("Skip preparing special user data dir | {}", userDataDir)
            return
        }

        val prototypeUserDataDir = AppPaths.CHROME_DATA_DIR_PROTOTYPE
        if (!Files.exists(prototypeUserDataDir.resolve("Default"))) {
            Files.createDirectories(userDataDir)
            return
        }

        withContextLock {
            if (Files.exists(defaultProfileDir)) {
                return@withContextLock
            }

            logger.info(
                "User data dir does not exist, copy from prototype | {} <- {}",
                userDataDir,
                prototypeUserDataDir
            )

            removeDeadSymbolicLinks(prototypeUserDataDir)

            val fileFilter = FileFilter { !Files.isSymbolicLink(it.toPath()) }
            FileUtils.copyDirectory(prototypeUserDataDir.toFile(), userDataDir.toFile(), fileFilter)
        }
    }

    fun clearUserDataDir() {
        if (!Files.exists(userDataDir) || isSystemDefaultBrowser() || isPrototypeOrDefaultUserDataDir()) {
            return
        }

        clearProcessMarkers()
        clearTransientUserDataDirEntries()
    }

    fun hasBackupMarks(): Boolean = processMarkerBackupPaths.any { Files.exists(it) }

    fun clearProcessMarkers() {
        runCatching {
            ensureMarkerDirectory()
            withContextLock {
                processMarkerPaths.forEach(::backupIfExists)
                processMarkerPaths.forEach { it.deleteIfExists() }
            }
        }.onFailure { logger.warn("Failed to clear process markers for {} | {}", userDataDir, it.message) }
    }

    fun cleanupInvalidProcessFiles(): Int {
        runCatching {
            ensureMarkerDirectory()
            withContextLock {
                processMarkerPaths.forEach { it.deleteIfExists() }
            }
        }.onFailure { logger.warn("Failed to cleanup invalid process files for {} | {}", userDataDir, it.message) }
        return 0
    }

    fun backupProcessMarkers() {
        runCatching {
            ensureMarkerDirectory()
            withContextLock {
                processMarkerPaths.forEach(::backupIfExists)
            }
        }.onFailure { logger.warn("Failed to backup process markers for {} | {}", userDataDir, it.message) }
    }

    fun restoreProcessMarkersFromBackup(overwrite: Boolean = false): Boolean {
        return runCatching {
            ensureMarkerDirectory()
            withContextLock {
                restoreBackup(portBakPath, portPath, overwrite)
                restoreBackup(pidBakPath, pidPath, overwrite)
                restoreBackup(cdpUrlBakPath, cdpUrlPath, overwrite)
            }
            true
        }.getOrElse {
            logger.warn("Failed to restore process marker backups for {} | {}", userDataDir, it.message)
            false
        }
    }

    fun deleteBackupMarks() {
        runCatching {
            withContextLock {
                processMarkerBackupPaths.forEach { it.deleteIfExists() }
            }
        }.onFailure { logger.warn("Failed to delete process marker backups for {} | {}", userDataDir, it.message) }
    }

    fun readPort(): Int? = readString(portPath)?.toIntOrNull()

    fun readPositivePort(): Int? = readPort()?.takeIf { it > 0 }

    fun writeStartingPort() {
        writePort(0)
    }

    fun writePort(port: Int) {
        writeString(portPath, port.toString())
    }

    fun readPid(preferBackup: Boolean = false): Long? {
        val paths = if (preferBackup) {
            listOf(pidBakPath, pidPath)
        } else {
            listOf(pidPath, pidBakPath)
        }

        return paths.asSequence()
            .mapNotNull(::readString)
            .mapNotNull(String::toLongOrNull)
            .firstOrNull()
    }

    fun writePid(pid: Long) {
        writeString(pidPath, pid.toString())
    }

    fun readCdpUrl(preferBackup: Boolean = false): String? {
        val paths = if (preferBackup) {
            listOf(cdpUrlBakPath, cdpUrlPath)
        } else {
            listOf(cdpUrlPath, cdpUrlBakPath)
        }

        return paths.asSequence()
            .mapNotNull(::readString)
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
    }

    fun writeCdpUrl(cdpUrl: String) {
        writeString(cdpUrlPath, cdpUrl)
    }

    fun writeLaunchArguments(executable: String, arguments: List<String>): Path? {
        return runCatching {
            val content = buildString {
                append(executable)
                arguments.forEach {
                    appendLine()
                    append(it)
                }
            }
            writeString(launchArgumentsPath, content)
            launchArgumentsPath
        }.getOrElse {
            logger.warn("Failed to write launch arguments for {} | {}", userDataDir, it.message)
            null
        }
    }

    fun writeLaunchReport(jsonReport: String, timestamp: LocalDateTime = LocalDateTime.now()): Pair<Path, Path>? {
        return runCatching {
            writeString(launchReportPath, jsonReport)

            val yearMonth = timestamp.format(DateTimeFormatter.ofPattern("yyyyMM"))
            val timestampText = timestamp.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            val historyFile = launchHistoryDir.resolve(yearMonth).resolve("chrome-launch-report-$timestampText.json")
            Files.createDirectories(historyFile.parent)
            Files.writeString(historyFile, jsonReport, StandardOpenOption.CREATE_NEW)

            launchReportPath to historyFile
        }.getOrElse {
            logger.warn("Failed to write launch report for {} | {}", userDataDir, it.message)
            null
        }
    }

    fun writeLastProcessOutput(output: String): Path? {
        return runCatching {
            writeString(lastOutputPath, output)
            lastOutputPath
        }.getOrElse {
            logger.warn("Failed to write chrome process output for {} | {}", userDataDir, it.message)
            null
        }
    }

    fun readLaunchReport(): String? = readText(launchReportPath)

    fun readLastProcessOutput(): String? = readText(lastOutputPath)

    fun getUserDataDirSize(): String {
        return try {
            if (Files.exists(userDataDir)) {
                Files.walk(userDataDir).use { stream ->
                    val size = stream
                        .filter { Files.isRegularFile(it) }
                        .mapToLong { Files.size(it) }
                        .sum()
                    "${size / 1024 / 1024}MB"
                }
            } else {
                "0MB"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun ensureMarkerDirectory() {
        processMarkerPaths.firstOrNull()?.parent?.let { Files.createDirectories(it) }
    }

    private fun writeString(path: Path, value: String) {
        ensureParentDirectory(path)
        Files.writeString(path, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun readString(path: Path): String? {
        return readText(path)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun readText(path: Path): String? {
        return try {
            if (Files.exists(path)) {
                Files.readString(path)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to read file {} | {}", path, e.message)
            null
        }
    }

    private fun backupIfExists(path: Path) {
        if (!Files.exists(path)) {
            return
        }

        val backup = path.resolveSibling("${path.fileName}.bak")
        ensureParentDirectory(backup)
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun restoreBackup(source: Path, target: Path, overwrite: Boolean) {
        if (!Files.exists(source)) {
            return
        }
        if (!overwrite && Files.exists(target)) {
            return
        }

        ensureParentDirectory(target)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun clearTransientUserDataDirEntries() {
        TRANSIENT_USER_DATA_DIR_ENTRY_NAMES
            .map(userDataDir::resolve)
            .forEach { path ->
                runCatching { path.deleteIfExists() }
                    .onFailure { logger.debug("Failed to delete transient user-data-dir entry {} | {}", path, it.message) }
            }

        runCatching {
            Files.list(userDataDir).use { stream ->
                stream.filter { Files.isSymbolicLink(it) && !Files.exists(it) }
                    .forEach { it.deleteIfExists() }
            }
        }.onFailure { logger.debug("Failed to clean dead symbolic links under {} | {}", userDataDir, it.message) }
    }

    private fun removeDeadSymbolicLinks(dir: Path) {
        if (!Files.exists(dir)) {
            return
        }

        Files.list(dir).use { stream ->
            stream.filter { Files.isSymbolicLink(it) && !Files.exists(it) }
                .forEach { it.deleteIfExists() }
        }
    }

    private fun ensureParentDirectory(path: Path) {
        path.parent?.let { Files.createDirectories(it) }
    }

    private fun isSystemDefaultBrowser(): Boolean {
        return userDataDir.startsWith(AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER)
    }

    private fun isPrototypeOrDefaultUserDataDir(): Boolean {
        val prototypeUserDataDir = AppPaths.CHROME_DATA_DIR_PROTOTYPE
        if (userDataDir == prototypeUserDataDir) {
            return true
        }

        val normalized = userDataDir.toAbsolutePath().toString().replace('\\', '/').lowercase()
        return normalized.contains("/default/")
    }

    fun <T> withUserDataDirLock(action: () -> T): T {
        return withFileLock(userDataDirLockFilePath, action)
    }

    private fun <T> withContextLock(action: () -> T): T {
        return withFileLock(contextLockFilePath, action)
    }

    private fun <T> withFileLock(lockFile: Path, action: () -> T): T {
        ensureParentDirectory(lockFile)
        val intraProcessLock = intraProcessFileLocks.computeIfAbsent(lockFile.toAbsolutePath().normalize().toString()) { Any() }

        var lastException: Exception? = null

        repeat(5) { attempt ->
            try {
                return acquireWithFileLock(lockFile, intraProcessLock, action)
            } catch (e: FileLockInterruptionException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: OverlappingFileLockException) {
                lastException = e
            } catch (e: IOException) {
                lastException = e
            }

            if (attempt < 4) {
                try {
                    Thread.sleep(50L * (attempt + 1))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for context lock | $lockFile", e)
                }
            }
        }

        throw IOException("Failed to acquire context lock | $lockFile", lastException)
    }

    private fun <T> acquireWithFileLock(lockFile: Path, intraProcessLock: Any, action: () -> T): T {
        synchronized(intraProcessLock) {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { channel ->
                val lock = channel.lock()
                try {
                    return action()
                } finally {
                    lock.release()
                }
            }
        }
    }
}
