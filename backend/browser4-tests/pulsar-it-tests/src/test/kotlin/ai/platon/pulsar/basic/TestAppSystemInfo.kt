package ai.platon.pulsar.basic

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.brief
import kotlin.test.*
import oshi.SystemInfo

/**
    ISSUE: OSHI failed on Windows in module browser4-tests and productions,
    VERSION: pulsar-1.10.12
    CAUSE: jna relevant packages are overridden by kotlin-compiler-${version}.jar:com.sun.jna.Memory,
    which has no close() method.
 * */
class TestAppSystemInfo {

    @Test
    fun testOSHIAvailable() {
        val si = SystemInfo()

        runCatching {
            val versionInfo = si.operatingSystem.versionInfo
            printlnPro("Operation system: $versionInfo")
        }.onFailure { printlnPro(it.brief()) }

        runCatching {
            val processor = si.hardware.processor
            printlnPro("Processor: $processor")
        }.onFailure { printlnPro(it.brief()) }

        runCatching {
            val memory = si.hardware.memory
            printlnPro("Memory: $memory")
        }.onFailure { printlnPro(it.brief()) }
    }
}

