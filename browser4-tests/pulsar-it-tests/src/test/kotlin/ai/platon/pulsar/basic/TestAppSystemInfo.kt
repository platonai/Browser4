package ai.platon.pulsar.basic

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.brief
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

/**
 * System metrics availability smoke test.
 *
 * Previously verified that the OSHI library was loadable; OSHI has been
 * removed in favor of the JDK built-in [OperatingSystemMXBean] from the
 * `jdk.management` module.
 * */
class TestAppSystemInfo {

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
        }.onFailure { printlnPro(it.brief()) }

        runCatching {
            printlnPro("Available processors: ${bean.availableProcessors}")
        }.onFailure { printlnPro(it.brief()) }

        runCatching {
            printlnPro("Total physical memory: ${bean.totalMemorySize}")
        }.onFailure { printlnPro(it.brief()) }
    }
}
