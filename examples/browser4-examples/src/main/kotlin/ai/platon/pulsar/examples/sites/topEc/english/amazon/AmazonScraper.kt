package ai.platon.pulsar.examples.sites.topEc.english.amazon

import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.test.TestResourceUtil.Companion.PRODUCT_DETAIL_URL

fun main() = PulsarContexts.createSession().scrape(
    PRODUCT_DETAIL_URL, "-expires 1s",
    mapOf("title" to "#title", "ratings" to "#acrCustomerReviewText")
).let { println(it) }
