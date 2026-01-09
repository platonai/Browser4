package ai.platon.pulsar.protocol.browser.driver.playwright

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.Runtimes
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue

@Tag("OptionalTest")
open class PlaywrightTestBase {

    companion object {
        const val PARALLELISM_NOTICE = """
    Playwright Java is not thread safe, i.e. all its methods as well as methods on all objects created by it
    (such as BrowserContext, Browser, Page etc.) are expected to be called on the same thread where the
    Playwright object was created or proper synchronization should be implemented to ensure only one thread calls
    Playwright methods at any given time. Having said that it's okay to create multiple Playwright instances each on its
    own thread.

    @see https://playwright.dev/java/docs/multithreading#introduction
    """

        const val BAD_PARALLELISM_WARNING = """
    This is a bad example of using Playwright in a multi-threaded environment.

    $PARALLELISM_NOTICE

    """

        lateinit var playwright: Playwright

        @JvmStatic
        @BeforeAll
        fun installPlaywright() {
            Assumptions.assumeTrue(Runtimes.isGUIAvailable(), "Test playwright only in a GUI environment")

            try {
                // 尝试安装 Playwright
                // Runtimes.exec("npx playwright install")
                playwright = Playwright.create()
                Assumptions.assumeTrue(true) // 如果安装成功，继续执行测试
            } catch (e: Exception) {
                Assumptions.assumeTrue(false) // 如果安装失败，跳过所有测试
            }
        }

        @JvmStatic
        @AfterAll
        fun cleanUp() {
            if (::playwright.isInitialized) {
                playwright.close()
            }
        }
    }

    @BeforeEach
    fun checkIfGUIAvailable() {
        Assumptions.assumeTrue(Runtimes.isGUIAvailable(), "Test playwright only in a GUI environment")
    }

    @BeforeEach
    fun checkNodeJSAvailability() {
        var output = Runtimes.exec("node --version")
        // a typical version is v16.15.1
        printlnPro(output.joinToString())
        Assumptions.assumeTrue({ output.size == 1 }, output.joinToString())
        Assumptions.assumeTrue({ output[0].startsWith("v") }, output.joinToString())

        output = Runtimes.exec("""node -e "console.log(1 + 1);" """)
        printlnPro(output.joinToString())
        // not all node version supports the expression
        // Assumptions.assumeTrue { output[0] == "2" }
    }

    @BeforeEach
    fun checkPlaywrightAvailability() {
        val output = Runtimes.exec("playwright --version")
        printlnPro(output.joinToString())
        Assumptions.assumeTrue { output.isNotEmpty() }
    }

    @Test
    fun testSomethingToEnsurePrecondition() {
        assertTrue { "You are a great creator".isNotEmpty() }
    }
}

