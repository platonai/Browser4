package ai.platon.pulsar.protocol.browser.driver.playwright

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.sleepSeconds
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import org.junit.jupiter.api.Tag
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

/**
 * In this test, each tag is bound to a single thread. The page operations in a single browser context
 * are performed in sequence to guarantee thread safety.
 *
 * Playwright Java is not thread safe, i.e. all its methods as well as methods on all objects created by it
 * (such as BrowserContext, Browser, Page etc.) are expected to be called on the same thread where the
 * Playwright object was created or proper synchronization should be implemented to ensure only one thread calls
 * Playwright methods at any given time. Having said that it's okay to create multiple Playwright instances each on its
 * own thread.
 * */
@Tag("OptionalTest")
class PlaywrightSafeMultiTabTest {

    private val originURL = "https://www.amazon.com/"
    private val testUrls = LinkExtractors.fromResource("seeds/seeds.txt").toList()
    private val pageId = AtomicInteger()

    fun run() {
        Playwright.create().use { playwright ->
            val contextDir = Paths.get("user-data-dir-1")
            val context = playwright.chromium().launchPersistentContext(
                contextDir,
                BrowserType.LaunchPersistentContextOptions().apply {
                    headless = false
                }
            )

            // 打开多个标签页
            val pages = mutableListOf<Page>()
            for (i in 0..9) {
                val page = context.newPage()
                page.navigate("about:blank")
                pages.add(page)
            }

            // has to do everything in sequence
            for (k in 0..10) {
                for (page in pages) {
                    val url = testUrls.getOrNull(pageId.get())
                    if (url == null) {
                        printlnPro("No more URLs to visit.")
                        break
                    }
                    visit(url, page)
                }
            }

            context.close()
        }
    }

    private fun visit(url: String, page: Page) {
        try {
            visit0(url, page)
        } catch (e: Exception) {
            printlnPro("❌ Error visiting $url: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun visit0(url: String, page: Page) {
        val name = pageId.incrementAndGet().toString()

        val asins = mutableListOf<String>()
        val navigateOptions = Page.NavigateOptions()
            .setTimeout(30_000.0)
            .setReferer(originURL)
        page.navigate(url, navigateOptions)
        page.waitForLoadState(LoadState.LOAD)
        page.waitForSelector("div[data-asin]", Page.WaitForSelectorOptions().setTimeout(30_000.0))
        printlnPro("$name \t ✅ Opened in tab ${page.hashCode()} | $url")
        sleepSeconds(1)

        val elements = page.querySelectorAll("div[data-asin]")
        elements.mapTo(asins) { it.getAttribute("data-asin") }
        printlnPro(asins.joinToString())
    }
}

fun main() {
    val test = PlaywrightSafeMultiTabTest()
    test.run()
}

