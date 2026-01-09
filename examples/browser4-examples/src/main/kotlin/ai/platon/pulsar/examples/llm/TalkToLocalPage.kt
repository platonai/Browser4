package ai.platon.pulsar.examples.llm

import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.test.TestResourceUtil

/**
 * Demonstrates chat with a webpage.
 * */
suspend fun main() {
    // Use the default browser which has an isolated profile
    PulsarSettings.withDefaultBrowser()
    // Create a pulsar session
    val session = PulsarContexts.createSession()
    // The main url we are playing with
    val url = TestResourceUtil.PRODUCT_DETAIL_URL

    val page = session.load(url)
    val document = session.parse(page)

    var response = session.chat("Tell me a joke about programming . . .")
    println(response)

    response = session.chat("Tell me something about this webpage", document)
    println(response)
}
