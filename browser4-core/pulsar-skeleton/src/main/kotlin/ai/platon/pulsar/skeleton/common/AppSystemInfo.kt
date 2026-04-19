package ai.platon.pulsar.skeleton.common

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.measure.ByteUnit
import ai.platon.pulsar.common.measure.ByteUnitConverter
import oshi.SystemInfo
import oshi.hardware.CentralProcessor
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Application specific system information
 * */
class AppSystemInfo {
    companion object {
        private val logger = getLogger(AppSystemInfo::class)

        private var prevCPUTicks = LongArray(CentralProcessor.TickType.entries.size)

        private var isOSHIChecked = false
        private var isOSHIAvailable = false

        var CRITICAL_CPU_THRESHOLD = System.getProperty("critical.cpu.threshold") ?.toDoubleOrNull() ?: 0.85
        var CRITICAL_MEMORY_THRESHOLD_MIB = System.getProperty("critical.memory.threshold.MiB")?.toDouble() ?: 0.0

        val startTime = Instant.now()
        val elapsedTime get() = Duration.between(startTime, Instant.now())

        val systemInfo: SystemInfo? by lazy {
            when {
                !AppContext.isActive -> null
                isOSHIAvailable() -> SystemInfo()
                else -> null
            }
        }

        /**
         * OSHI cached the value, so it's fast and safe to be called frequently.
         *
         * for example, on Windows,
         * WindowsGlobalMemory.availTotalSize is defined as:
         * memoize(WindowsGlobalMemory::readPerfInfo, defaultExpiration());
         * */
        val memoryInfo get() = systemInfo?.hardware?.memory

        /**
         * System cpu load in [0, 1]
         * */
        val systemCpuLoad get() = computeSystemCpuLoad()

        /**
         * Check whether CPU usage reaches critical status.
         * */
        val isCriticalCPULoad get() = systemCpuLoad > CRITICAL_CPU_THRESHOLD

        /**
         * An array of the system load averages for 1, 5, and 15 minutes
         * with the size of the array specified by nelem; or negative values if not available.
         *
         * Load average, also called average system load, is an important metric that indicates
         * if there are multiple tasks in queue on the Linux server. The load average can be high or low,
         * depending on the number of cores your server has, how many CPUs are integrated into the system server,
         * and the load average number itself.
         *
         * A load average value is considered to be high when it’s greater than the number of CPUs the server has.
         * For example, if the number of CPUs in our server is only 4, but the load average we’re seeing is 5.4,
         * we’re experiencing a high load average.
         *
         * Load average is considered to be ideal when its value is lower than the number of CPUs in the Linux server.
         * For example, with only one CPU in the Linux server, it’s best if the load average is below 1.
         *
         * High load average tends to occur for the three reasons mentioned below:
         * 1. A high number of threads executed in the server
         * 2. Lack of RAM forcing the server to use swap memory
         * 3. A high number of I/O traffic
         *
         * @see [Load average: What is it, and what's the best load average for your Linux servers?](https://www.site24x7.com/blog/load-average-what-is-it-and-whats-the-best-load-average-for-your-linux-servers)
         * */
        val systemLoadAverage: DoubleArray? get() {
            val si = systemInfo ?: return null
            return si.hardware.processor.getSystemLoadAverage(3)
        }

        /**
         * Free memory in bytes.
         * Free memory is the amount of memory which is currently not used for anything.
         * This number should be small, because memory which is not used is simply wasted.
         * */
        val freeMemory get() = Runtime.getRuntime().freeMemory()
        val freeMemoryGiB get() = ByteUnit.BYTE.toGiB(freeMemory.toDouble())

        /**
         * Available memory in bytes.
         * Available memory is the amount of memory which is available for allocation to a new process or to existing
         * processes.
         * */
        val availableMemory: Long? get() = memoryInfo?.available

        val usedMemory: Long? get() {
            val mi = memoryInfo ?: return null
            return mi.total - mi.available
        }

        val totalMemory get() = Runtime.getRuntime().totalMemory()
        val totalMemoryGiB get() = ByteUnit.BYTE.toGiB(totalMemory.toDouble())
        val availableMemoryGiB: Double? get() {
            val m = availableMemory ?: return null
            return ByteUnit.BYTE.toGiB(m.toDouble())
        }

        val memoryToReserve = when {
            // user specified
            CRITICAL_MEMORY_THRESHOLD_MIB >= 1 -> ByteUnit.MIB.toBytes(CRITICAL_MEMORY_THRESHOLD_MIB)
            // autodetected
            totalMemoryGiB >= 14 -> ByteUnit.GIB.toBytes(3.0) // 3 GiB
            totalMemoryGiB >= 30 -> AppConstants.DEFAULT_BROWSER_RESERVED_MEMORY
            else -> AppConstants.BROWSER_TAB_REQUIRED_MEMORY
        }

        /**
         * Check whether memory usage reaches critical status.
         * */
        val isCriticalMemory: Boolean get() {
            val am = availableMemory ?: return false
            return am < memoryToReserve
        }

        val freeDiskSpaces get() = Runtimes.unallocatedDiskSpaces()

        /**
         * Check whether disk usage reaches critical status.
         * */
        val isCriticalDiskSpace get() = checkIsOutOfDisk()

        /**
         * Determines if any of the monitored hardware resources (CPU, memory, or disk space)
         * have reached a critical usage level. Returns `true` if at least one resource is in
         * a critical state, otherwise returns `false`.
         */
        val isSystemOverCriticalLoad get() = isCriticalMemory || isCriticalCPULoad || isCriticalDiskSpace

        /**
         *
         * */
        @Synchronized
        fun isOSHIAvailable(): Boolean {
            if (isOSHIChecked) {
                return isOSHIAvailable
            }

            isOSHIAvailable = try {
                report()
                true
            } catch (e: Throwable) {
                handleOSHINotAvailable()
                false
            }

            isOSHIChecked = true

            return isOSHIAvailable
        }

        fun report() {
            if (AppContext.isActive) {
                return
            }

            val si = SystemInfo()

            val versionInfo = si.operatingSystem.versionInfo
            logger.info("Operation system: {}", versionInfo)

            val processor = si.hardware.processor
            logger.info("Processor: {}", processor)

            // Failed on Windows:
            // si.hardware.memory throws exception since jna used by kotlin is too old,
            // but we can not specify the version
            val memory = si.hardware.memory
            logger.info("Memory: {}", memory)
        }

        fun networkIFsReceivedBytes(): Long {
            val si = systemInfo ?: return -1
            return si.hardware.networkIFs.sumOf { it.bytesRecv.toInt() }.toLong().coerceAtLeast(0)
        }

        private fun handleOSHINotAvailable() {
            val path = AppPaths.TMP_DIR.resolve("system.properties")
            try {
                val text = System.getProperties().entries.joinToString("\n") { "" + it.key + "=" + it.value}
                Files.writeString(path, text)
            } catch (e: IOException) {
                System.err.println(e.stringify())
                logger.warn(e.stringify())
            }

            val message = "OSHI is disabled"
            logger.warn(message)
        }

        fun formatAvailableMemory(): String {
            return availableMemory?.let { Strings.compactFormat(it) } ?: "N/A"
        }

        fun formatMemoryToReserve(): String {
            return Strings.compactFormat(memoryToReserve.toLong())
        }

        fun formatMemoryShortage(): String {
            val availableMemory = AppSystemInfo.availableMemory ?: return "N/A"
            val shortage = availableMemory - memoryToReserve.toLong()
            if (shortage > 0) {
                return "N/A"
            }
            return Strings.compactFormat(shortage)
        }

        private fun checkIsOutOfDisk(): Boolean {
            val freeSpace = freeDiskSpaces.maxOfOrNull { ByteUnitConverter.convert(it, "G") } ?: 0.0
            return freeSpace < 10.0
        }

        private fun computeSystemCpuLoad(): Double {
            val processor = systemInfo?.hardware?.processor ?: return 0.0

            synchronized(prevCPUTicks) {
                // Returns the "recent cpu usage" for the whole system by counting ticks
                val cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevCPUTicks)
                // Get System-wide CPU Load tick counters. Returns an array with seven elements
                // representing milliseconds spent in User (0), Nice (1), System (2), Idle (3), IOwait (4),
                // Hardware interrupts (IRQ) (5), Software interrupts/DPC (SoftIRQ) (6), or Steal (7) states.
                prevCPUTicks = processor.systemCpuLoadTicks
                return cpuLoad
            }
        }
    }
}
