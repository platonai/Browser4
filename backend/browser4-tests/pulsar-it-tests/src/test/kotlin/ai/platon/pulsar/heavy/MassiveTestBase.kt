package ai.platon.pulsar.heavy

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.ql.context.SQLContexts
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertTrue

open class MassiveTestBase {
    protected val session = SQLContexts.createSession()

    protected val testFileCount: Int
        get() {
            val clazzName = this.javaClass.simpleName
            val propertyName = "${clazzName}_TestFileCount"

            printlnPro("------------- Massive Task Test Message -----------------")
            printlnPro("Set system property $propertyName to enable the massive test")
            printlnPro("For example: -D$propertyName=10000")
            printlnPro("---------------------------------------------------------")

            return System.getProperty(propertyName)?.toInt() ?: 0
        }

    protected val testPaths = ConcurrentSkipListSet<Path>()

    protected lateinit var startTime: LocalDateTime

    /**
     * Generate [testFileCount] temporary files in the local file system before all the tests.
     * */
    @BeforeEach
    fun generateTestFiles() {
        Assumptions.assumeTrue { testFileCount > 0 }
        TestResourceHelper.generateTestFiles(testFileCount).toCollection(testPaths)
    }

    @BeforeEach
    fun prepareContextDirs() {
        Assumptions.assumeTrue { testFileCount > 0 }
        val tempContextBaseDir = AppPaths.CONTEXT_TMP_DIR
        Files.createDirectories(tempContextBaseDir)
        assertTrue { Files.exists(tempContextBaseDir) }
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun clearContextDirs() {
        Assumptions.assumeTrue { testFileCount > 0 }
        kotlin.runCatching { session.close() }

        val tempContextBaseDir = AppPaths.CONTEXT_TMP_DIR
        repeat(5) {
            Thread.sleep(1000)
            kotlin.runCatching { tempContextBaseDir.deleteRecursively() }
            if (!Files.exists(tempContextBaseDir)) return
        }

        assertTrue { !Files.exists(tempContextBaseDir) }
    }

    /**
     * Run the test for 10000 urls, and print the performance results.
     * */
    @BeforeTest
    fun setUp() {
        startTime = LocalDateTime.now()
    }

    @AfterTest
    fun tearDown() {
        val endTime = LocalDateTime.now()
        val duration = Duration.between(startTime, endTime)
        printlnPro("Test finished, duration: $duration")
    }
}
