package ai.platon.pulsar.skeleton.common

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.measure.ByteUnit
import ai.platon.pulsar.common.measure.ByteUnitConverter
import com.sun.management.OperatingSystemMXBean
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Application specific system information.
 *
 * System metrics (CPU load, physical memory) are read from the JDK built-in
 * [com.sun.management.OperatingSystemMXBean], which requires the `jdk.management`
 * module to be present on the runtime image. When that module is unavailable
 * (e.g. a stripped JRE), every metric degrades gracefully to `null` / `0.0` /
 * `false` instead of throwing.
 * */
class AppSystemInfo {
    companion object {
        private val logger = getLogger(AppSystemInfo::class)

        /**
         * The platform [OperatingSystemMXBean] exposed by the `com.sun.management`
         * package, or `null` when the `jdk.management` module is not on the
         * runtime image. All metric accessors null-check this bean.
         * */
        private val osBean: OperatingSystemMXBean? by lazy {
            runCatching {
                ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
            }.onFailure { logger.warn("System metrics bean unavailable: {}", it.stringify()) }
                .getOrNull()
        }

        private var isMetricsChecked = false
        private var isMetricsAvailable = false

        var CRITICAL_CPU_THRESHOLD = System.getProperty("critical.cpu.threshold") ?.toDoubleOrNull() ?: 0.85
        var CRITICAL_MEMORY_THRESHOLD_MIB = System.getProperty("critical.memory.threshold.MiB")?.toDouble() ?: 0.0

        val startTime: Instant = Instant.now()
        val elapsedTime: Duration get() = Duration.between(startTime, Instant.now())

        /**
         * Total physical memory in bytes, or `null` if unavailable.
         * */
        val totalPhysicalMemory: Long? get() = osBean?.totalMemorySize?.takeIf { it > 0 }

        /**
         * Free physical memory in bytes, or `null` if unavailable.
         * */
        val physicalFreeMemory: Long? get() = osBean?.freeMemorySize?.takeIf { it >= 0 }

        /**
         * System cpu load in [0, 1]. Returns `0.0` when unavailable.
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
         * Note: the JDK exposes only the 1-minute average via
         * [OperatingSystemMXBean.getSystemLoadAverage]; the returned array
         * therefore contains a single element, or `null` when unavailable.
         *
         * @see [Load average: What is it, and what's the best load average for your Linux servers?](https://www.site24x7.com/blog/load-average-what-is-it-and-whats-the-best-load-average-for-your-linux-servers)
         * */
        val systemLoadAverage: DoubleArray? get() {
            val bean = osBean ?: return null
            val oneMin = bean.systemLoadAverage
            return if (oneMin < 0) null else doubleArrayOf(oneMin)
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
        val availableMemory: Long? get() = physicalFreeMemory

        val usedMemory: Long? get() {
            val bean = osBean ?: return null
            val used = bean.totalMemorySize - bean.freeMemorySize
            return used.takeIf { it >= 0 }
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
         * Checks whether the system metrics backend ([OperatingSystemMXBean] from the
         * `jdk.management` module) is available.
         *
         * The method name is retained for backward compatibility; it no longer
         * refers to the OSHI library.
         * */
        @Synchronized
        fun isOSHIAvailable(): Boolean {
            if (isMetricsChecked) {
                return isMetricsAvailable
            }

            isMetricsAvailable = try {
                report()
                osBean != null
            } catch (e: Throwable) {
                handleMetricsNotAvailable()
                false
            }

            isMetricsChecked = true

            return isMetricsAvailable
        }

        fun report() {
            if (AppContext.isActive) {
                return
            }

            val bean = osBean
            if (bean == null) {
                logger.info(
                    "Operating system: {} {} ({})",
                    System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch")
                )
                logger.info("System metrics bean (jdk.management) is unavailable")
                return
            }

            logger.info(
                "Operating system: {} {} ({})",
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch")
            )
            logger.info("Available processors: {}", bean.availableProcessors)
            logger.info("Total physical memory: {}", Strings.compactFormat(bean.totalMemorySize))
            logger.info("Free physical memory: {}", Strings.compactFormat(bean.freeMemorySize))
        }

        /**
         * Total bytes received across all network interfaces.
         *
         * Network interface byte counters are no longer tracked after the OSHI
         * dependency was removed; there is no portable JDK equivalent. Returns
         * `-1` to signal "unavailable", which callers already handle.
         * */
        fun networkIFsReceivedBytes(): Long {
            return -1
        }

        private fun handleMetricsNotAvailable() {
            val path = AppPaths.TMP_DIR.resolve("system.properties")
            try {
                val text = System.getProperties().entries.joinToString("\n") { "" + it.key + "=" + it.value}
                Files.writeString(path, text)
            } catch (e: IOException) {
                System.err.println(e.stringify())
                logger.warn(e.stringify())
            }

            val message = "System metrics are disabled (jdk.management unavailable)"
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
            val bean = osBean ?: return 0.0
            val load = bean.cpuLoad
            // getCpuLoad() returns -1 when unavailable or before the first reading.
            return if (load < 0) 0.0 else load.coerceIn(0.0, 1.0)
        }
    }
}
