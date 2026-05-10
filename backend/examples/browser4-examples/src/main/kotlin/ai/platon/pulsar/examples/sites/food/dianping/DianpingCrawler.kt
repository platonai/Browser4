package ai.platon.pulsar.examples.sites.food.dianping

import com.google.gson.GsonBuilder

fun main() {
    val portalUrl = "https://www.dianping.com/beijing/ch10/g110"
    val args = "-i 1s -ii 5s -ol \"#shop-all-list .tit a[href~=shop]\" -ignoreFailure"

    val crawler = RestaurantCrawler()
    val fields = crawler.session.scrapeOutPages(portalUrl, crawler.options(args), crawler.fieldSelectors)
    println(GsonBuilder().setPrettyPrinting().create().toJson(fields))
}
