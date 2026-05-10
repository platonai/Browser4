package ai.platon.pulsar.examples.advanced

import ai.platon.pulsar.skeleton.context.PulsarContexts

/**
 * Use custom configured components.
 * */
fun main() {
    val context = PulsarContexts.create("classpath:advanced/custom-app-context.xml")
    val session = context.createSession()

    val portalUrl = "https://www.amazon.com/Best-Sellers-Beauty/zgbs/beauty"
    val pages = session.submitForOutPages(portalUrl, "-expires 1s -itemExpires 7d -outLink a[href~=/dp/]")

    PulsarContexts.await()
}
