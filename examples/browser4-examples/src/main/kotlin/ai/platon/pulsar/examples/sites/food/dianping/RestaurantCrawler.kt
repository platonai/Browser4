package ai.platon.pulsar.examples.sites.food.dianping

import ai.platon.pulsar.common.*
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.session.PulsarSession
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import java.util.*

class Screenshot(
    val page: WebPage,
    val driver: WebDriver
) {
    companion object {
        val OCR = "OCR-"
    }

    private val logger = getLogger(this)

    suspend fun screenshot(name: String, selector: String): String? {
        try {
            val screenshot = driver.captureScreenshot(selector)
            if (screenshot == null) {
                logger.info("Failed to take screenshot for {}", selector)
                return null
            }

            val path = AppPaths.WEB_CACHE_DIR
                .resolve("screenshot")
                .resolve(AppPaths.fileId(page.url))
                .resolve("$name.jpg")
            val bytes = Base64.getDecoder().decode(screenshot)
            AppFiles.saveTo(bytes, path, true)

            return path.toString()
        } catch (t: Throwable) {
            logger.warn(t.brief())
        }

        return null
    }
}

class RestaurantCrawler(
    val session: PulsarSession = PulsarContexts.createSession()
) {
    val commentSelectors = IntRange(1, 10)
        .associate { i -> "comment-$i" to "#reviewlist-wrapper li.comment-item:nth-child($i) p.desc.J-desc" }

    val fieldSelectors = mutableMapOf(
        "title" to ".basic-info h2",
        "score" to ".basic-info .brief-info .mid-score",
        "reviewCount" to "#reviewCount",
        "avgPrice" to "#avgPriceTitle",
        "commentScores" to "#comment_score",
        "address" to "#address",
        "tel" to ".tel",
    ).also { it.putAll(commentSelectors) }

    fun options(args: String): LoadOptions {
        val options = session.options(args)
        val be = options.eventHandlers.browseEventHandlers
        be.onWillComputeFeature.addLast { page, driver ->
            IntRange(1, commentSelectors.size)
                .map { "#reviewlist-wrapper .comment-item:nth-child($it) .more" }
                .asFlow().flowOn(Dispatchers.IO).collect { selector ->
                    if (driver.exists(selector)) {
                        driver.click(selector)
                        delay(500)
                    }
                }
        }

        be.onFeatureComputed.addLast { page, driver ->
            fieldSelectors.entries.asFlow().flowOn(Dispatchers.IO).collect { (name, selector) ->
                if (driver.exists(selector)) {
                    Screenshot(page, driver).screenshot(name, selector)
                    delay(1500)
                }
            }
        }

        return options
    }
}

fun main() {
    val url = "https://www.dianping.com/shop/Enk0gTkqu0Cyj7Ch"
    val args = "-i 1s -ignoreFailure -parse"

    val session = PulsarContexts.createSession()
    val crawler = RestaurantCrawler(session)

    val fields = session.scrape(url, crawler.options(args), crawler.fieldSelectors)
    println(GsonBuilder().setPrettyPrinting().create().toJson(fields))
}
