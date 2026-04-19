package ai.platon.pulsar.skeleton.crawl.common

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import kotlin.test.*
import oshi.SystemInfo
import oshi.hardware.CentralProcessor.TickType
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TestAppSystemInfo {
    var sum = 0.0
    var prevTicks = LongArray(TickType.entries.size)

    @Test
    fun testOSHIAvailable() {
        val si = SystemInfo()

        runCatching {
            val versionInfo = si.operatingSystem.versionInfo
            printlnPro("Operation system: $versionInfo")
        }.onFailure { printlnPro(it.message) }

        runCatching {
            val processor = si.hardware.processor
            printlnPro("Processor: $processor")
        }.onFailure { printlnPro(it.message) }

        runCatching {
            val memory = si.hardware.memory
            printlnPro("Memory: $memory")
        }.onFailure { printlnPro(it.message) }
    }

    @Test
    fun testOSVersionInfo() {
        val systemInfo = AppSystemInfo.systemInfo ?: return

        val versionInfo = systemInfo.operatingSystem.versionInfo
        printlnPro(versionInfo)
    }

    @Test
    fun testSystemCpuLoad() {
        val systemCpuLoad = AppSystemInfo.systemCpuLoad
        assert(systemCpuLoad > 0)
    }

    @Test
    fun testCPULoad() {
        val systemInfo = AppSystemInfo.systemInfo ?: return

        Executors.newScheduledThreadPool(1).scheduleAtFixedRate({
            printlnPro()
            measureCPU()
        }, 2, 2, TimeUnit.SECONDS)

        val nThreads = systemInfo.hardware.processor.logicalProcessorCount - 2
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
        val systemInfo = AppSystemInfo.systemInfo ?: return

        val processor = systemInfo.hardware.processor

        val cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100
        prevTicks = processor.systemCpuLoadTicks
        printlnPro(String.format("cpuLoad: %.2f%%", cpuLoad))

        val systemLoadAverage = processor.getSystemLoadAverage(3).joinToString()
        printlnPro("Sys: $systemLoadAverage")
    }
}

