package ai.platon.pulsar.rest

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.getLogger
import org.springframework.boot.logging.logback.LogbackLoggingSystem
import org.springframework.util.ClassUtils
import kotlin.test.Test

class EnvironmentTests {

    @Test
    fun testLogging() {
        val PRESENT = ClassUtils.isPresent(
            "ch.qos.logback.classic.LoggerContext",
            LogbackLoggingSystem.Factory::class.java.classLoader
        )
        printlnPro(PRESENT)

        getLogger(this).info("Logging system works correctly")
    }

}

