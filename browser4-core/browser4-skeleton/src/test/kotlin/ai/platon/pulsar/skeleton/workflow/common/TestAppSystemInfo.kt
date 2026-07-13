package ai.platon.pulsar.skeleton.workflow.common

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

class TestAppSystemInfo {
    var sum = 0.0

    private val osBean: OperatingSystemMXBean?
        get() = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean

    @Test
    fun testOSHIAvailable() {
        val bean = osBean
        if (bean == null) {
            printlnPro("System metrics bean (jdk.management) is unavailable")
            return
        }

        runCatching {
            printlnPro("Operation system: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        }.onFailure { printlnPro(it.message) }

        runCatching {
            printlnPro("Available processors: ${bean.availableProcessors}")
        }.onFailure { printlnPro(it.message) }

        runCatching {
            printlnPro("Total physical memory: ${bean.totalMemorySize}")
        }.onFailure { printlnPro(it.message) }
    }

    @Test
    fun testOSVersionInfo() {
        printlnPro("${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
    }

    @Test
    fun testSystemCpuLoad() {
        val systemCpuLoad = AppSystemInfo.systemCpuLoad
        assert(systemCpuLoad >= 0)
    }

    @Test
    fun testCPULoad() {
        val bean = osBean ?: return

        Executors.newScheduledThreadPool(1).scheduleAtFixedRate({
            printlnPro()
            measureCPU()
        }, 2, 2, TimeUnit.SECONDS)

        val nThreads = (bean.availableProcessors - 2).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(nThreads)
        repeat(nThreads) {
            executor.submit { compute() }
        }
        executor.awaitTermination(10, TimeUnit.SECONDS)
        printlnPro(sum)
    }

    fun compute() {
        var result = Random.nextDouble(1.0)

        val endTime = Instant.now().plusSeconds(10)
        while (Instant.now().isBefore(endTime)) {
            result = 0.5 * (sin(result) + cos(result))
            sum += result
        }
    }

    fun measureCPU() {
        val bean = osBean ?: return

        val cpuLoad = bean.cpuLoad * 100
        printlnPro(String.format("cpuLoad: %.2f%%", cpuLoad))

        val systemLoadAverage = bean.systemLoadAverage
        printlnPro("Sys load average (1m): $systemLoadAverage")
    }
}
