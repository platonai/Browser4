package ai.platon.pulsar.examples.sites.food.dianping

import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.session.PulsarSession
import com.google.gson.GsonBuilder
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.util.*

class RestaurantCrawlerSlim(val session: PulsarSession) {
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
        val browseEvent = options.eventHandlers.browseEventHandlers
        browseEvent.onWillComputeFeature.addLast { page, driver ->
            IntRange(1, commentSelectors.size)
                .map { i -> "#reviewlist-wrapper .comment-item:nth-child($i) .more" }
                .forEach { selector ->
                    if (driver.exists(selector)) {
                        driver.click(selector)
                        delay(500)
                    }
                }
        }

        browseEvent.onFeatureComputed.addLast { page, driver ->
            fieldSelectors.forEach { (name, selector) ->
                if (driver.exists(selector)) {
                    val screenshot = driver.captureScreenshot(selector)

                    val path = AppFiles.createTempFile("screenshot", "png")
                    val bytes = Base64.getDecoder().decode(screenshot)
                    Files.write(path, bytes)

                    delay(500)
                }
            }
        }

        return options
    }
}

fun main() {
    val portalUrl = "https://www.dianping.com/beijing/ch10/g110"
    val args = "-i 1s -ii 5s -ol \"#shop-all-list .tit a[href~=shop]\" -ignoreFailure"

    val crawler = RestaurantCrawlerSlim(PulsarContexts.createSession())
    val fields = crawler.session.scrapeOutPages(portalUrl, crawler.options(args), crawler.fieldSelectors)
    println(GsonBuilder().setPrettyPrinting().create().toJson(fields))
}
