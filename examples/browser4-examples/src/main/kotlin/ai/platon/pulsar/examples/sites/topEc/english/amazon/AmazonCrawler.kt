package ai.platon.pulsar.examples.sites.topEc.english.amazon

import ai.platon.pulsar.skeleton.context.PulsarContexts

fun main() = PulsarContexts.createSession().scrapeOutPages(
    "https://www.amazon.com/Best-Sellers/zgbs",
    "-outLink a[href~=/dp/]",
    mapOf("title" to "#title", "ratings" to "#acrCustomerReviewText")
).let { println(it) }
